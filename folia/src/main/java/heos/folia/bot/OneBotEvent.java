package heos.folia.bot;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Wrapper for incoming OneBot events with convenience API call methods. */
public class OneBotEvent {
    public final JsonObject raw;
    public final String clientId;
    private final OneBotServer server;
    private volatile boolean replied;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private final BiFunction<String, String, CompletableFuture<JsonObject>> externalApi;

    public OneBotEvent(JsonObject raw, String clientId, OneBotServer server) {
        this.raw = raw;
        this.clientId = clientId;
        this.server = server;
        this.externalApi = null;
    }

    public OneBotEvent(JsonObject raw, String clientId,
                       BiFunction<String, String, CompletableFuture<JsonObject>> externalApi) {
        this.raw = raw;
        this.clientId = clientId;
        this.server = null;
        this.externalApi = externalApi;
    }

    // ---- Accessors ----
    public boolean isOfficial() {
        return (clientId != null && clientId.startsWith("official:")) || flag("official") || flag("official_bot") || raw.has("official_verified");
    }
    public boolean officialIdentityVerified() { return flag("official_verified"); }
    public boolean officialPrivateReady() { return flag("official_private_ready"); }
    private boolean flag(String key) {
        try { return raw.has(key) && raw.get(key).getAsBoolean(); }
        catch (RuntimeException e) { return false; }
    }
    public String postType() { return str("post_type"); }
    public String messageType() { return str("message_type"); }
    public String noticeType() { return str("notice_type"); }
    public String subType() { return str("sub_type"); }
    public long groupId() { return raw.has("group_id") ? raw.get("group_id").getAsLong() : 0; }
    public long userId() { return raw.has("user_id") ? raw.get("user_id").getAsLong() : 0; }
    public long operatorId() { return raw.has("operator_id") ? raw.get("operator_id").getAsLong() : 0; }
    public long messageId() { return raw.has("message_id") ? raw.get("message_id").getAsLong() : 0; }
    public String rawMessage() { return str("raw_message"); }
    public JsonObject sender() { return raw.has("sender") ? raw.getAsJsonObject("sender") : null; }
    public String senderRole() {
        JsonObject s = sender();
        return s != null && s.has("role") ? s.get("role").getAsString() : "member";
    }

    private String str(String key) {
        return raw.has(key) && !raw.get(key).isJsonNull() ? raw.get(key).getAsString() : "";
    }

    // ---- API calls ----
    public void reply(String message) {
        replied = true;
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId() + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\""
                    + escape(message) + "\"}}]}");
        }
    }

    public void replyPrivate(String message) {
        String groupPart = groupId() > 0 ? ",\"group_id\":" + groupId() : "";
        callApi("send_private_msg", "{\"user_id\":" + userId() + groupPart
                + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\""
                + escape(message) + "\"}}]}");
    }

    public void replyAt(String message) {
        if (isOfficial()) { reply(message); return; }
        replied = true;
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"" + userId() + "\"}},"
                    + "{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}");
        } else {
            reply(message);
        }
    }

    public void replyImage(String file) {
        replied = true;
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"image\",\"data\":{\"file\":\"" + escape(file) + "\"}}]}");
        }
    }

    /** Send a plain text message to a group (works for notice events too, not just messages). */
    public void replyImageWithFallback(String file, String fallback) {
        if (!isOfficial()) { replyImage(file); return; }
        replied = true;
        try {
            callApiAsync("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"image\",\"data\":{\"file\":\"" + escape(file) + "\"}}]}")
                    .orTimeout(60, TimeUnit.SECONDS).whenComplete((resp, error) -> {
                        if (error != null || !accepted(resp)) reply(fallback);
                    });
        } catch (Exception e) { reply(fallback); }
    }

    private static boolean accepted(JsonObject resp) {
        try {
            return resp != null && "ok".equals(resp.get("status").getAsString())
                    && resp.get("retcode").getAsInt() == 0;
        } catch (RuntimeException e) { return false; }
    }

    public void sendGroupMessage(long groupId, String message) {
        callApi("send_group_msg", "{\"group_id\":" + groupId
                + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}");
    }

    /** Send a private message to an arbitrary QQ and report whether it was accepted (retcode == 0). */
    public boolean sendPrivateChecked(long targetQq, String message) {
        try {
            String groupPart = groupId() > 0 ? ",\"group_id\":" + groupId() : "";
            JsonObject resp = callApiAsync("send_private_msg",
                    "{\"user_id\":" + targetQq + groupPart
                            + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}")
                    .get(8, TimeUnit.SECONDS);
            if (resp == null) return false;
            return resp.has("status") && "ok".equals(resp.get("status").getAsString())
                    && resp.has("retcode") && resp.get("retcode").getAsInt() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send a private message asynchronously and invoke the callback with the result.
     * CRITICAL: never block the WebSocket worker thread waiting for an API response —
     * the worker that dispatched the request is also the one that must process the
     * reply, so a synchronous get() deadlocks until the 10s timeout.
     *
     * Includes group_id when the event came from a group: NapCat/OneBot then sends
     * via GROUP TEMP SESSION (TEMPC2CFROMGROUP) for non-friends instead of failing
     * on C2C. QQ blocks direct private messages to non-friends; group temp chat
     * works as long as the target hasn't disabled it.
     */
    public void sendPrivateThen(long targetQq, String message, java.util.function.Consumer<Boolean> callback) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<Boolean> once = ok -> {
            if (done.compareAndSet(false, true)) callback.accept(ok);
        };
        String groupPart = groupId() > 0 ? ",\"group_id\":" + groupId() : "";
        try {
        callApiAsync("send_private_msg",
                "{\"user_id\":" + targetQq + groupPart
                        + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}")
                .orTimeout(10, TimeUnit.SECONDS).whenComplete((resp, error) -> {
                    once.accept(error == null && accepted(resp));
                });
        } catch (Exception e) { once.accept(false); }
    }

    /** 目标缺失：官Q给出明确格式，传统通道沿用表情回应，避免静默失败。 */
    public void missingTarget(String usage) {
        if (isOfficial()) { if (!replied) reply("命令缺少目标QQ号。用法：" + usage
                + "（官方QQ不提供被@成员的QQ号，请直接填写QQ号）"); return; }
        react(false);
    }

    public void react(boolean success) {
        if (isOfficial()) {
            if (!replied) reply(success ? "操作成功。" : "操作失败，请检查命令参数或稍后重试。");
            return;
        }
        int emoji = success ? 124 : 123;  // Match original: 124=✅, 123=❌
        callApi("set_msg_emoji_like", "{\"message_id\":" + messageId() + ",\"emoji_id\":" + emoji + ",\"set\":true}");
    }

    public void reactDeny() {
        if (isOfficial()) { if (!replied) reply("操作被拒绝：权限不足或操作过于频繁。"); return; }
        callApi("set_msg_emoji_like", "{\"message_id\":" + messageId() + ",\"emoji_id\":15,\"set\":true}");
    }

    public CompletableFuture<JsonObject> callApiAsync(String action, String params) {
        if (externalApi != null) return externalApi.apply(action, params);
        OneBotSession session = server.sessions.get(clientId);
        if (session == null) {
            server.logger.warning("[Bot] callApiAsync: no session for clientId=" + clientId);
            return CompletableFuture.failedFuture(new RuntimeException("No session"));
        }

        String echo = String.valueOf(System.nanoTime());
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        session.pending.put(echo, future);

        String request = "{\"action\":\"" + action + "\",\"params\":" + params + ",\"echo\":\"" + echo + "\"}";
        server.send(clientId, request);

        return future.completeOnTimeout(null, 10, TimeUnit.SECONDS);
    }

    public void callApi(String action, String params) {
        try {
            callApiAsync(action, params).exceptionally(e -> {
                if (server != null) server.logger.warning("[Bot] API call failed: " + action + " - " + e.getMessage());
                return null;
            });
        } catch (Exception e) {
            if (server != null) server.logger.warning("[Bot] API call exception: " + action + " - " + e.getMessage());
        }
    }

    private static String escape(String s) {
        String json = GSON.toJson(s == null ? "" : s);
        return json.substring(1, json.length() - 1);
    }
}
