package heos.folia.bot;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 访问凭证刷新回归（2026-11-07 生产事故）。
 *
 * 事故形态：access_token 是 appid 级共享凭证，有效期内重复获取返回同一个值，
 * 所以「启动时拿到的」可能是一份只剩几十分钟的旧凭证。旧实现只在 start() 与
 * 临近过期的重连里刷新，网关不断线时凭证一过期，所有 HTTP 调用持续 401
 * （code=40011027），最长静默到下一次断线重连。
 *
 * 本测试用本地 HTTP 替身复现三件事，不联网腾讯、不使用线上凭据：
 *   1. getToken 记录「剩余有效期」，便于事后从日志判断启动时拿到的是否快过期凭证
 *   2. refreshTokenIfDue 在距过期不足提前量时会刷新
 *   3. 发送遇 401 会自动刷新凭证并重试一次（第二次请求必须带新凭证）
 *
 * 运行：java -cp "<jar>:<deps>:<out>" heos.folia.bot.TokenRefreshRegression
 */
public final class TokenRefreshRegression {
    private static final List<String> logs = new CopyOnWriteArrayList<>();
    private static final List<String> sendAuth = new CopyOnWriteArrayList<>();
    private static final AtomicInteger tokenCalls = new AtomicInteger();
    private static final AtomicInteger sendCalls = new AtomicInteger();
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Logger log = Logger.getLogger("token-refresh");
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(String.valueOf(record.getMessage())); }
            @Override public void flush() { }
            @Override public void close() { }
        });

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        // 三次取凭证：OLD(5 秒) -> NEW(2 小时) -> NEWER(2 小时)
        server.createContext("/app/getAppAccessToken", exchange -> {
            int n = tokenCalls.incrementAndGet();
            String body = switch (n) {
                case 1 -> "{\"access_token\":\"OLD\",\"expires_in\":\"5\"}";
                case 2 -> "{\"access_token\":\"NEW\",\"expires_in\":\"7200\"}";
                default -> "{\"access_token\":\"NEWER\",\"expires_in\":\"7200\"}";
            };
            reply(exchange, 200, body);
        });
        // 第一次发消息返回 401（模拟凭证失效），之后成功
        server.createContext("/v2/groups/G1/messages", exchange -> {
            int n = sendCalls.incrementAndGet();
            sendAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            if (n == 1) reply(exchange, 401, "{\"code\":40011027,\"message\":\"token invalid\"}");
            else reply(exchange, 200, "{\"id\":\"m1\"}");
        });
        server.start();
        try {
            OfficialQQBot bot = new OfficialQQBot(log, "app", "secret", base, base,
                    "ws://127.0.0.1:1", null, null, 6, 10, Set.of(), event -> { });

            Method getToken = OfficialQQBot.class.getDeclaredMethod("getToken");
            getToken.setAccessible(true);
            Method refreshIfDue = OfficialQQBot.class.getDeclaredMethod("refreshTokenIfDue", String.class);
            refreshIfDue.setAccessible(true);
            java.lang.reflect.Field tokenField = OfficialQQBot.class.getDeclaredField("token");
            tokenField.setAccessible(true);

            // 1) 首次取凭证：日志必须带剩余有效期
            String first = (String) getToken.invoke(bot);
            check("首次取到 OLD 凭证", "OLD".equals(first));
            check("取凭证日志含剩余有效期",
                    logs.stream().anyMatch(line -> line.contains("剩余有效期") && line.contains("过期时刻")));

            // 2) 距过期仅 5 秒（远小于 10 分钟提前量）→ 定时检查必须刷新
            refreshIfDue.invoke(bot, "回归-定时检查");
            check("临近过期时定时检查会刷新", "NEW".equals(tokenField.get(bot)));
            check("刷新日志可读", logs.stream().anyMatch(line -> line.contains("访问凭证已刷新（回归-定时检查）")));

            // 3) 发送遇 401 → 刷新凭证 → 重试一次并成功
            Class<?> contextClass = Class.forName("heos.folia.bot.OfficialQQBot$ReplyContext");
            Constructor<?> contextCtor = contextClass.getDeclaredConstructor(String.class, String.class, boolean.class);
            contextCtor.setAccessible(true);
            Object context = contextCtor.newInstance("G1", "m1", false);
            Method send = OfficialQQBot.class.getDeclaredMethod("send", contextClass, String.class, String.class);
            send.setAccessible(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<JsonObject> future = (CompletableFuture<JsonObject>) send.invoke(bot, context, "hi", null);
            JsonObject outcome = future.get(15, TimeUnit.SECONDS);

            check("401 后重试成功", "ok".equals(outcome.get("status").getAsString()));
            check("共发出两次消息请求", sendCalls.get() == 2);
            check("第一次用旧凭证", sendAuth.size() > 0 && "QQBot NEW".equals(sendAuth.get(0)));
            check("重试用刷新后的凭证", sendAuth.size() > 1 && "QQBot NEWER".equals(sendAuth.get(1)));
            check("401 触发了凭证刷新", tokenCalls.get() == 3);
            check("401 刷新日志可读", logs.stream().anyMatch(line -> line.contains("访问凭证已刷新（401 重试）")));
        } finally {
            server.stop(0);
        }

        System.out.println("TokenRefreshRegression: PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + "  logs=" + logs + " sendAuth=" + sendAuth); }
    }

    private static void reply(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }
}
