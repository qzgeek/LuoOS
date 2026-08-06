package heos.folia.bot;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Wrapper for incoming OneBot events with convenience API call methods. */
public class OneBotEvent {
    public final JsonObject raw;
    public final String clientId;
    private final OneBotServer server;

    public OneBotEvent(JsonObject raw, String clientId, OneBotServer server) {
        this.raw = raw;
        this.clientId = clientId;
        this.server = server;
    }

    // ---- Accessors ----
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
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"at\",\"data\":{\"qq\":\"" + userId() + "\"}},"
                    + "{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}");
        } else {
            reply(message);
        }
    }

    public void replyImage(String file) {
        if (messageType().equals("group")) {
            callApi("send_group_msg", "{\"group_id\":" + groupId()
                    + ",\"message\":[{\"type\":\"image\",\"data\":{\"file\":\"" + escape(file) + "\"}}]}");
        }
    }

    /** Send a plain text message to a group (works for notice events too, not just messages). */
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
        String groupPart = groupId() > 0 ? ",\"group_id\":" + groupId() : "";
        callApiAsync("send_private_msg",
                "{\"user_id\":" + targetQq + groupPart
                        + ",\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"" + escape(message) + "\"}}]}")
                .thenAccept(resp -> {
                    boolean ok = resp != null && resp.has("status") && "ok".equals(resp.get("status").getAsString())
                            && resp.has("retcode") && resp.get("retcode").getAsInt() == 0;
                    callback.accept(ok);
                });
    }

    public void react(boolean success) {
        int emoji = success ? 124 : 123;  // Match original: 124=✅, 123=❌
        callApi("set_msg_emoji_like", "{\"message_id\":" + messageId() + ",\"emoji_id\":" + emoji + ",\"set\":true}");
    }

    public void reactDeny() {
        callApi("set_msg_emoji_like", "{\"message_id\":" + messageId() + ",\"emoji_id\":15,\"set\":true}");
    }

    public CompletableFuture<JsonObject> callApiAsync(String action, String params) {
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
                server.logger.warning("[Bot] API call failed: " + action + " - " + e.getMessage());
                return null;
            });
        } catch (Exception e) {
            server.logger.warning("[Bot] API call exception: " + action + " - " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
