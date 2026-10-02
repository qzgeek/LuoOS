package heos.folia.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import heos.folia.storage.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** 实际命令处理器、仓储、HTTP传输回归；所有账号与HTTP服务均为测试夹具。 */
public class CommandParityRegression {
    static int passed;
    static final Logger LOG = Logger.getLogger("qq-parity-test");
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    static void pass(String text) { passed++; System.out.println("PASS " + text); }
    static JsonObject ok() { JsonObject r = new JsonObject(); r.addProperty("status", "ok"); r.addProperty("retcode", 0); return r; }
    static final List<JsonObject> output = new CopyOnWriteArrayList<>();
    static OneBotEvent command(String text, boolean verified, String role, boolean official) {
        JsonObject data = new JsonObject(), sender = new JsonObject();
        data.addProperty("post_type", "message"); data.addProperty("message_type", "group");
        data.addProperty("group_id", 100); data.addProperty("user_id", verified ? 10001 : 0);
        data.addProperty("raw_message", text); sender.addProperty("role", role); data.add("sender", sender);
        if (official) data.addProperty("official_verified", verified);
        return new OneBotEvent(data, official ? "official:test" : "onebot:test", (action, params) -> {
            JsonObject response = JsonParser.parseString(params).getAsJsonObject(); response.addProperty("action", action);
            output.add(response); return CompletableFuture.completedFuture(ok());
        });
    }
    static String run(BotCommandHandler handler, String text, boolean verified, String role) {
        return run(handler, text, verified, role, verified ? 10001 : 0);
    }
    static String run(BotCommandHandler handler, String text, boolean verified, String role, long qq) {
        output.clear();
        JsonObject data = new JsonObject(), sender = new JsonObject();
        data.addProperty("post_type", "message"); data.addProperty("message_type", "group");
        data.addProperty("group_id", 100); data.addProperty("user_id", qq);
        data.addProperty("raw_message", text); data.addProperty("official_verified", verified);
        sender.addProperty("role", role); data.add("sender", sender);
        handler.handle(new OneBotEvent(data, "official:test", (action, params) -> {
            JsonObject response = JsonParser.parseString(params).getAsJsonObject(); response.addProperty("action", action);
            output.add(response); return CompletableFuture.completedFuture(ok());
        }));
        return output.toString();
    }
    static JsonObject message(String id, String openid, String group) {
        JsonObject data = new JsonObject(), author = new JsonObject();
        data.addProperty("id", id); data.addProperty("group_openid", group); data.addProperty("content", "帮助");
        author.addProperty("member_openid", openid); author.addProperty("member_role", "member"); data.add("author", author); return data;
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("luoos-command-regression-");
        FoliaStorage storage = new FoliaStorage(dir);
        FoliaWhitelistRepository repo = new FoliaWhitelistRepository(storage, LOG);
        repo.createOfficialBindingTable();
        BotCommandHandler handler = new BotCommandHandler(LOG, new BotDb(LOG, repo), storage, repo, null,
                3, "a-zA-Z0-9_-", 16, new long[0], "自定义状态", 1000, 60, 0, 0);
        try {
            for (String alias : List.of("help", "帮助", "菜单", "命令")) {
                String answer = run(handler, alias, false, "member");
                check(answer.contains("绑定QQ") && answer.contains("申请白名单") && answer.contains("人机列表"), "public help " + alias);
            }
            pass("未绑定官Q可用全部帮助别名并看到完整命令与验证指引");
            for (String cmd : List.of("申请白名单 TestUser", "查询白名单", "删除白名单 TestUser", "重置密码 TestUser", "封禁 22222", "解封 22222", "封禁列表", "删除 22222 TestUser")) {
                check(run(handler, cmd, false, "admin").contains("尚未通过邮箱验证"), "unverified admin blocked " + cmd);
            }
            check(repo.getAllWhitelistEntries().isEmpty() && repo.getBlacklistEntries(50).isEmpty(), "no unverified mutations");
            pass("未验证管理员也不能查询/写白名单、封禁、改密");
            check(run(handler, "封禁 22222", true, "member").contains("权限不足"), "member denied");
            check(!repo.isBlacklisted(22222), "denial no mutation"); pass("成员管理命令明确拒绝");
            check(run(handler, "封禁 22222", true, "admin").contains("操作成功"), "ban reply");
            check(repo.isBlacklisted(22222), "ban persisted");
            check(run(handler, "解封 22222", true, "admin").contains("操作成功") && !repo.isBlacklisted(22222), "unban persisted");
            pass("管理员封禁解封复用真实仓储且反馈成功");
            try (var stmt = storage.getConnection().createStatement()) {
                stmt.execute("CREATE TRIGGER reject_ban BEFORE INSERT ON qq_blacklist BEGIN SELECT RAISE(ABORT,'test failure'); END");
            }
            check(run(handler, "封禁 33333", true, "admin").contains("操作失败"), "failed SQL is not success");
            check(!repo.isBlacklisted(33333), "failed ban absent");
            try (var stmt = storage.getConnection().createStatement()) { stmt.execute("DROP TRIGGER reject_ban"); }
            pass("注入数据库写失败后不谎报成功");
            check(repo.addWhitelist(22222, "123456", null), "numeric name fixture");
            check(repo.addWhitelist(22222, "KeepUser", null), "other name fixture");
            check(run(handler, "删除 22222 123456", true, "admin").contains("操作成功"), "delete numeric id reply");
            check(!repo.hasWhitelist(22222, "123456") && repo.hasWhitelist(22222, "KeepUser"), "no accidental delete all");
            pass("管理员删除纯数字游戏ID不会误删全账号");
            var account = new FoliaPlayerData("KeepUser", java.util.UUID.randomUUID(), false);
            account.passwordHash = heos.folia.utils.FoliaPasswordHasher.hashPassword("oldPassword1");
            storage.save(account);
            for (int i = 0; i < 100 && storage.load("KeepUser") == null; i++) Thread.sleep(50);
            check(storage.load("KeepUser") != null && storage.load("KeepUser").isRegistered(), "account registered fixture");
            // 官Q必须邮件送达；未配置邮件时拒绝改密，传统通道不受影响
            check(run(handler, "重置密码 KeepUser", true, "member", 22222).contains("邮件服务未配置"), "official needs SMTP");
            pass("官Q未配置邮件时拒绝改密且不报成功");
            String missing = run(handler, "封禁", true, "admin");
            check(missing.contains("缺少目标QQ号") && missing.contains("用法"), "missing target guidance");
            check(run(handler, "解封", true, "admin").contains("缺少目标QQ号"), "unban guidance");
            check(run(handler, "删除", true, "admin").contains("缺少目标QQ号"), "delete guidance");
            check(run(handler, "删除", true, "member").contains("未知命令"), "non-admin delete not executed");
            check(repo.hasWhitelist(22222, "KeepUser"), "non-admin delete no mutation");
            pass("管理命令缺目标时统一给出用法提示而非含糊失败");
            output.clear(); handler.handle(command("帮助", true, "member", false));
            check(output.stream().anyMatch(r -> "set_msg_emoji_like".equals(r.get("action").getAsString())), "legacy emoji retained");
            check(!output.toString().contains("绑定QQ"), "no official binding requirement in legacy help");
            pass("传统OneBot帮助和表情回应保持可用");
            java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
            handler.setMailService(new SmtpCodeService("127.0.0.1", 2525, "", "", "bot@test", false) {
                @Override public void send(String recipient, String code) { throw new UnsupportedOperationException("test double"); }
                @Override public void sendPasswordReset(String recipient, String account, String password) {
                    check(recipient.equals("22222@qq.com") && account.equals("KeepUser") && password.length() == 12, "mail payload");
                    sent.incrementAndGet();
                }
            });
            String reply = run(handler, "重置密码 KeepUser", true, "member", 22222);
            check(sent.get() == 1, "password emailed once (official)");
            check(reply.contains("22222@qq.com") && !reply.matches(".*[A-Za-z0-9]{12}.*"), "group reply hides password");
            pass("官Q重置密码发往QQ邮箱，群回复不含密码");

            // 传统通道：应走私聊，且完全不使用邮件
            int mailsBefore = sent.get();
            // 避开 60 秒冷却：使用另一个已被传统通道占用、但不是 22222 的 QQ
            check(repo.addWhitelist(33333, "LegacyUser", null), "legacy fixture");
            var legacyAccount = new FoliaPlayerData("LegacyUser", java.util.UUID.randomUUID(), false);
            legacyAccount.passwordHash = heos.folia.utils.FoliaPasswordHasher.hashPassword("oldPassword1");
            storage.save(legacyAccount);
            for (int i = 0; i < 100 && storage.load("LegacyUser") == null; i++) Thread.sleep(50);
            output.clear();
            JsonObject legacyData = new JsonObject(), legacySender = new JsonObject();
            legacyData.addProperty("post_type", "message");
            legacyData.addProperty("message_type", "group");
            legacyData.addProperty("group_id", 100);
            legacyData.addProperty("user_id", 33333);
            legacyData.addProperty("raw_message", "重置密码 LegacyUser");
            legacySender.addProperty("role", "member");
            legacyData.add("sender", legacySender);
            handler.handle(new OneBotEvent(legacyData, "onebot:test", (action, params) -> {
                JsonObject response = JsonParser.parseString(params).getAsJsonObject();
                response.addProperty("action", action);
                output.add(response);
                return CompletableFuture.completedFuture(ok());
            }));
            String legacyOut = output.toString();
            check(sent.get() == mailsBefore, "legacy must NOT send email");
            check(legacyOut.contains("send_private_msg"), "legacy uses private message");
            check(!legacyOut.contains("已发送到") || !legacyOut.contains("@qq.com")
                    || !legacyOut.contains("新密码已发送到"), "legacy reply must not claim email delivery");
            pass("传统通道重置密码走私聊且不使用邮件");
            AtomicInteger callbacks = new AtomicInteger();
            OneBotEvent failing = new OneBotEvent(command("",true,"member",true).raw, "official:test", (a,p) -> CompletableFuture.failedFuture(new Exception("fixture")));
            failing.sendPrivateThen(10001,"test", result -> { check(!result,"failed future false"); callbacks.incrementAndGet(); });
            check(callbacks.get() == 1, "callback once"); pass("私信异常也返回失败回调且只调用一次");
            transport(repo);
        } finally { storage.close(); }
        System.out.println("COMMAND/TRANSPORT PASS " + passed + " (isolated DB " + dir + ")");
    }
    static void transport(FoliaWhitelistRepository repo) throws Exception {
        BlockingQueue<JsonObject> requests = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            body.addProperty("path", ex.getRequestURI().getPath()); requests.add(body);
            byte[] response = "{\"id\":\"TEST_RESPONSE\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, response.length); ex.getResponseBody().write(response); ex.close();
        }); server.start();
        List<OneBotEvent> events = new ArrayList<>();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        OfficialQQBot bot = new OfficialQQBot(LOG, "TEST", "NOT_A_REAL_SECRET", base, base, "", repo, null, 6, 10, Set.of(), events::add);
        try {
            var token = OfficialQQBot.class.getDeclaredField("token"); token.setAccessible(true); token.set(bot,"TEST_ONLY_TOKEN");
            var dispatch = OfficialQQBot.class.getDeclaredMethod("dispatchMessage", JsonObject.class); dispatch.setAccessible(true);
            dispatch.invoke(bot,message("A", "unknown", "Aa")); dispatch.invoke(bot,message("B", "unknown", "Aa"));
            check(events.size() == 2 && !events.get(0).officialIdentityVerified(), "unverified forwarded public");
            OneBotEvent a = events.get(0), b = events.get(1);
            // 故意颠倒回复顺序，确认较晚请求不能覆盖较早请求msg_id。
            b.reply("B回复"); a.reply("A回复\t\r\n\"引号\"");
            JsonObject first = requests.poll(3,TimeUnit.SECONDS), second = requests.poll(3,TimeUnit.SECONDS);
            check(first != null && second != null, "two HTTP responses");
            Map<String,String> content = new HashMap<>(); for (var r : List.of(first,second)) content.put(r.get("msg_id").getAsString(),r.get("content").getAsString());
            check(content.get("A").startsWith("A回复") && content.get("B").equals("B回复"), "message isolation");
            a.reply("A第二条"); check(requests.poll(3,TimeUnit.SECONDS).get("msg_seq").getAsInt() == 2,"unique reply sequence");
            pass("同群乱序回复保留各自msg_id、独立递增序号及JSON控制字符");
            dispatch.invoke(bot,message("A", "unknown", "Aa")); check(events.size() == 2, "duplicate ignored");
            dispatch.invoke(bot,message("C", "unknown", "BB")); check(events.get(2).groupId() != a.groupId(), "hash collision groups isolated");
            pass("重复事件不重复执行，Java哈希碰撞的群仍隔离");
            a.replyImageWithFallback("base64://AA==", "图片失败文字回退");
            check(requests.poll(3,TimeUnit.SECONDS).get("content").getAsString().equals("图片失败文字回退"),"image fallback");
            pass("图片上传失败时通过真实HTTP发送文字回退");
            JsonObject ret = a.callApiAsync("send_private_msg", "{\"user_id\":10001,\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"must not leak\"}}]}").get();
            check(ret.get("retcode").getAsInt() != 0 && requests.isEmpty(), "unknown private never sent");
            pass("未验证私聊目标不发送敏感内容");
            // @目标：带mentions时明确提示直接填QQ号，而不是静默失败
            JsonObject mentioned = message("D", "unknown", "Aa");
            mentioned.addProperty("content", " 封禁  1h ");
            JsonArray ms = new JsonArray(); JsonObject who = new JsonObject();
            who.addProperty("id", "OPENID_X"); who.addProperty("member_openid", "OPENID_X");
            who.addProperty("username", "sunset"); who.addProperty("bot", false); ms.add(who);
            mentioned.add("mentions", ms);
            dispatch.invoke(bot, mentioned);
            JsonObject warning = requests.poll(3, TimeUnit.SECONDS);
            check(warning != null && warning.get("content").getAsString().contains("直接填写QQ号"), "mention rejected with guidance");
            pass("@目标拒绝并提示直接填写QQ号（官方不提供QQ号）");
        } finally { bot.stop(); server.stop(0); }
    }
}
