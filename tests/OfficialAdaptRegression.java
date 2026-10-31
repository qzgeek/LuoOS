package heos.folia.bot;

import com.google.gson.*;
import heos.folia.storage.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 官方通道适配回归：成员进退群冻结/恢复、resume 与重连决策。
 * 使用真实仓储与真实 OfficialQQBot 内部方法，不联网到腾讯。
 */
public final class OfficialAdaptRegression {
    static int passed;

    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("luoos-adapt-regression-");
        Logger log = Logger.getLogger("adapt");
        FoliaStorage storage = new FoliaStorage(dir);
        FoliaWhitelistRepository repo = new FoliaWhitelistRepository(storage, log);
        repo.createOfficialBindingTable();

        List<OneBotEvent> events = new CopyOnWriteArrayList<>();
        OfficialQQBot bot = new OfficialQQBot(log, "T", "S", "https://api.bot.qq.com",
                "https://api.bot.qq.com", "", repo, null, 6, 10, Set.of(), events::add);
        try {
            // 建立一条已验证的官方身份：openid -> QQ
            bindIdentity(repo, "MEMBER_OPENID", 10001);
            check(repo.officialQq("MEMBER_OPENID").orElse(0L) == 10001, "identity bound");

            // ---- 成员退群：应产生 group_decrease 事件并冻结白名单 ----
            check(repo.addWhitelist(10001, "AdaptUser", null), "whitelist fixture");
            dispatchMember(bot, "GROUP_MEMBER_REMOVE", "MEMBER_OPENID");
            check(events.size() == 1, "member remove produced one event");
            OneBotEvent removed = events.get(0);
            check("notice".equals(removed.postType()), "post_type=notice");
            check("group_decrease".equals(removed.noticeType()), "notice_type=group_decrease got " + removed.noticeType());
            check(removed.userId() == 10001, "resolved to bound QQ");
            check(removed.isOfficial(), "marked official");
            // 真实处理器应据此冻结
            var handler = new BotCommandHandler(log, new BotDb(log, repo), storage, repo, null,
                    3, "a-zA-Z0-9_-", 16, new long[0], "t", 1000, 60, 0, 0);
            handler.handle(removed);
            check(repo.getWhitelistEntries(10001).stream().anyMatch(e -> e.frozen), "whitelist frozen on leave");
            pass("官方GROUP_MEMBER_REMOVE → 冻结该成员名下白名单");

            // ---- 成员重新进群：应恢复 ----
            events.clear();
            dispatchMember(bot, "GROUP_MEMBER_ADD", "MEMBER_OPENID");
            check(events.size() == 1 && "group_increase".equals(events.get(0).noticeType()),
                    "member add produced group_increase");
            handler.handle(events.get(0));
            check(repo.getWhitelistEntries(10001).stream().noneMatch(e -> e.frozen), "whitelist restored on join");
            pass("官方GROUP_MEMBER_ADD → 恢复该成员名下白名单");

            // ---- 未绑定成员的进退群应被忽略（无身份可冻结）----
            events.clear();
            dispatchMember(bot, "GROUP_MEMBER_REMOVE", "UNKNOWN_OPENID");
            check(events.isEmpty(), "unverified member ignored");
            pass("未验证成员的进退群不产生事件（无白名单可冻结）");

            // ---- resume 决策 ----
            check(!canResume(bot), "no resume before session");
            setField(bot, "sessionId", "sess-123");
            check(!canResume(bot), "no resume without seq");
            SequenceSet(bot, 42L);
            check(canResume(bot), "resume available with session+seq");
            pass("断线后具备会话与序号时优先 resume");

            // ---- 不可恢复错误码不应触发重连 ----
            setField(bot, "stopping", false);
            check(!shouldReconnect(4001), "4001 not retryable");
            check(!shouldReconnect(4013), "4013 not retryable");
            check(!shouldReconnect(4915), "4915 not retryable");
            check(shouldReconnect(4009), "4009 retryable");
            check(shouldReconnect(1006), "normal close retryable");
            pass("区分可重试与不可恢复错误码，避免无效重连");

            // ---- 退避策略：限频(4008)不得 3 秒一轮，撞上限后不得永久放弃 ----
            check(delayMillis(1006, 1) == 3000L, "normal first retry 3s");
            check(delayMillis(1006, 3) == 12000L, "normal backoff grows");
            check(delayMillis(4008, 1) >= 60_000L, "rate limited waits at least 60s");
            check(delayMillis(4008, 5) >= 60_000L, "rate limited never falls back to 3s");
            check(delayMillis(1006, 11) == 600_000L, "beyond cap uses slow retry, not permanent stop");
            check(delayMillis(4008, 11) == 600_000L, "rate limited beyond cap also slow retries");
            pass("限频长退避 + 超过上限改为慢速兜底（不再需要重启服务器）");

            // ---- 限频后必须丢弃脏会话，重新 Identify 而不是无限 resume ----
            check(mustDropSession(4008), "4008 drops session");
            check(!mustDropSession(4009), "4009 keeps session for resume");
            pass("4008 丢弃会话引用，避免反复 resume 被限频的会话");

            // ---- READY/RESUMED 不再清零退避计数（限频死循环的根因）----
            setField(bot, "reconnectAttempts", new java.util.concurrent.atomic.AtomicInteger(5));
            gatewayEvent(bot, "RESUMED", new JsonObject());
            check(attempts(bot) == 5, "RESUMED must not reset backoff, got " + attempts(bot));
            gatewayEvent(bot, "READY", new JsonObject());
            check(attempts(bot) == 5, "READY must not reset backoff, got " + attempts(bot));
            pass("READY/RESUMED 不清零退避（刚连上就被踢不算成功）");

            // ---- 只有存活够久的连接才清零 ----
            setField(bot, "sessionEstablishedAt", System.currentTimeMillis() - 61_000L);
            resetBackoff(bot);
            check(attempts(bot) == 0, "healthy connection resets backoff");
            setField(bot, "reconnectAttempts", new java.util.concurrent.atomic.AtomicInteger(5));
            setField(bot, "sessionEstablishedAt", System.currentTimeMillis());
            resetBackoff(bot);
            check(attempts(bot) == 5, "short-lived connection keeps backoff");
            pass("按连接存活时长（≥60s）判定是否清零退避");

            // ---- 心跳任务不得累积：重复 Hello 必须取消上一条任务 ----
            Object first = startHeartbeat(bot, 50L);
            Object second = startHeartbeat(bot, 50L);
            check(first != second, "heartbeat rescheduled");
            check(((java.util.concurrent.ScheduledFuture<?>) first).isCancelled(), "previous heartbeat cancelled");
            check(!((java.util.concurrent.ScheduledFuture<?>) second).isCancelled(), "current heartbeat active");
            pass("重复 Hello 取消旧心跳任务，心跳数不随重连次数增长");

            System.out.println("ADAPT PASS " + passed);
        } finally {
            bot.stop();
            storage.close();
        }
    }

    static void bindIdentity(FoliaWhitelistRepository repo, String openid, long qq) {
        repo.requestOfficialCode(openid, qq, "hash", System.currentTimeMillis() + 600_000, () -> { });
        repo.confirmOfficialCode(openid, "hash", System.currentTimeMillis());
    }

    static void dispatchMember(OfficialQQBot bot, String event, String openid) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("group_openid", "GROUP_X");
        data.addProperty("member_openid", openid);
        Method m = OfficialQQBot.class.getDeclaredMethod("dispatchMemberChange", JsonObject.class, boolean.class);
        m.setAccessible(true);
        m.invoke(bot, data, "GROUP_MEMBER_REMOVE".equals(event));
    }

    static boolean canResume(OfficialQQBot bot) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("canResume");
        m.setAccessible(true);
        return (boolean) m.invoke(bot);
    }

    static boolean shouldReconnect(int code) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("isRetryableClose", int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, code);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field f = OfficialQQBot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static void SequenceSet(OfficialQQBot bot, long value) throws Exception {
        Field f = OfficialQQBot.class.getDeclaredField("sequence");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) f.get(bot)).set(value);
    }

    static long delayMillis(int closeCode, int attempt) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("reconnectDelayMillis", int.class, int.class);
        m.setAccessible(true);
        return (long) m.invoke(null, closeCode, attempt);
    }

    static boolean mustDropSession(int closeCode) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("mustDropSession", int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, closeCode);
    }

    /** 直接投递一条网关事件（不经过 WebSocket）。 */
    static void gatewayEvent(OfficialQQBot bot, String event, JsonObject data) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", 0);
        payload.addProperty("t", event);
        payload.add("d", data);
        Method m = OfficialQQBot.class.getDeclaredMethod("handleGatewayEvent", String.class, JsonObject.class);
        m.setAccessible(true);
        m.invoke(bot, event, payload);
    }

    static int attempts(OfficialQQBot bot) throws Exception {
        Field f = OfficialQQBot.class.getDeclaredField("reconnectAttempts");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicInteger) f.get(bot)).get();
    }

    static void resetBackoff(OfficialQQBot bot) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("resetBackoffIfHealthy");
        m.setAccessible(true);
        m.invoke(bot);
    }

    static Object startHeartbeat(OfficialQQBot bot, long interval) throws Exception {
        Method m = OfficialQQBot.class.getDeclaredMethod("startHeartbeat", long.class);
        m.setAccessible(true);
        m.invoke(bot, interval);
        Field f = OfficialQQBot.class.getDeclaredField("heartbeatFuture");
        f.setAccessible(true);
        return f.get(bot);
    }
}
