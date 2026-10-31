package heos.folia.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import heos.folia.storage.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 重连风暴回归：用本地模拟网关复现「连上→RESUMED→4008 限频」的真实序列，
 * 断言修复后不会每 3 秒重连一次、心跳不会随重连累积。
 *
 * 不联网腾讯、不使用线上凭据。运行约 100 秒，因此不放进快速回归套件。
 * 运行：java -cp "<jar>:<deps>:<out>" heos.folia.bot.ReconnectStormRegression
 */
public final class ReconnectStormRegression {
    static final int WINDOW_SECONDS = 100;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("luoos-storm-regression-");
        List<String> logs = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger("storm");
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logs.add(String.valueOf(record.getMessage())); }
            @Override public void flush() { }
            @Override public void close() { }
        });

        HttpServer token = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        token.createContext("/app/getAppAccessToken", exchange -> {
            byte[] body = "{\"access_token\":\"TEST_TOKEN\",\"expires_in\":7200}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        token.start();
        String apiBase = "http://127.0.0.1:" + token.getAddress().getPort();

        Gateway gateway = new Gateway();
        gateway.start();
        gateway.waitReady();

        FoliaStorage storage = new FoliaStorage(dir);
        FoliaWhitelistRepository repo = new FoliaWhitelistRepository(storage, log);
        OfficialQQBot bot = new OfficialQQBot(log, "APP", "SECRET", apiBase, apiBase,
                "ws://127.0.0.1:" + gateway.getPort(), repo, null, 6, 10, Set.of(), event -> { });
        try {
            bot.start();
            Thread.sleep(WINDOW_SECONDS * 1000L);

            System.out.println("connections=" + gateway.connections.get()
                    + " heartbeats=" + gateway.heartbeats.get());
            check(gateway.connections.get() >= 1, "gateway never connected");
            // 修复前：4008 后 3 秒重连一轮，本机实测每秒 ~1 次（100 秒约 30 次）。
            check(gateway.connections.get() <= 3, "reconnect storm: " + gateway.connections.get() + " connections");
            // 修复前：每次重连新增一个心跳任务且永不取消，心跳数随时间线性暴涨。
            check(gateway.heartbeats.get() >= 1, "no heartbeat at all: mock interval too short");
            check(gateway.heartbeats.get() < 100, "heartbeat leak: " + gateway.heartbeats.get() + " heartbeats");
            check(logs.stream().anyMatch(l -> l.contains("60 秒后尝试第 1 次重连")),
                    "rate-limit backoff missing: " + logs.stream().filter(l -> l.contains("重连")).toList());
            check(logs.stream().anyMatch(l -> l.contains("4008")), "mock gateway never rate limited");
            System.out.println("STORM PASS");
        } finally {
            bot.stop();
            gateway.stop(1000);
            token.stop(0);
            storage.close();
        }
    }

    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

    /** 模拟网关：Hello → 任何 Identify/Resume 都先回 RESUMED 再以 4008 关闭。 */
    static final class Gateway extends WebSocketServer {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger heartbeats = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(1);
        final Gson gson = new Gson();

        Gateway() { this(0); }

        Gateway(int port) { super(new InetSocketAddress("127.0.0.1", port)); }

        void waitReady() { try { ready.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            connections.incrementAndGet();
            JsonObject hello = new JsonObject();
            hello.addProperty("op", 10);
            JsonObject d = new JsonObject();
            d.addProperty("heartbeat_interval", 1000);
            hello.add("d", d);
            conn.send(gson.toJson(hello));
        }

        @Override public void onMessage(WebSocket conn, String message) {
            try {
                JsonObject payload = JsonParser.parseString(message).getAsJsonObject();
                int op = payload.get("op").getAsInt();
                if (op == 1) { heartbeats.incrementAndGet(); return; }
                if (op != 2 && op != 6) return;
                JsonObject resumed = new JsonObject();
                resumed.addProperty("op", 0);
                resumed.addProperty("t", "RESUMED");
                resumed.addProperty("s", 1);
                resumed.add("d", new JsonObject());
                conn.send(gson.toJson(resumed));
                // 4.5 秒后以限频错误码关闭：连接存活期间会真实发出心跳，复现线上序列
                CompletableFuture.delayedExecutor(4500, TimeUnit.MILLISECONDS).execute(() -> conn.close(4008, "Rate limited"));
            } catch (RuntimeException ignored) { }
        }

        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { }
        @Override public void onError(WebSocket conn, Exception ex) { }
        @Override public void onStart() { ready.countDown(); }
    }
}
