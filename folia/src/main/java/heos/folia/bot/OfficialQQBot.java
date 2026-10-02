package heos.folia.bot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import heos.folia.storage.FoliaWhitelistRepository;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class OfficialQQBot {
    private final Logger logger;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String appId;
    private final String appSecret;
    private final String apiBase;
    private final String tokenBase;
    private final String gatewayUrl;
    private final FoliaWhitelistRepository repository;
    private final SmtpCodeService mail;
    private final int codeDigits;
    private final int expiresMinutes;
    private final Consumer<OneBotEvent> handler;
    private final AtomicLong sequence = new AtomicLong(-1);
    private final Map<String, Long> groupIds = new ConcurrentHashMap<>();
    private final AtomicLong nextGroupId = new AtomicLong(-1);
    private final Map<String, ReplyContext> replies = new ConcurrentHashMap<>();
    private final Map<Long, ReplyContext> privateReplies = new ConcurrentHashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor commands = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128), r -> {
                Thread t = new Thread(r, "LuoOS-Official-QQ-Commands"); t.setDaemon(true); return t;
            });
    static final class ReplyContext {
        final String target, messageId;
        final boolean privateMessage;
        final long createdAt = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicInteger replySequence = new java.util.concurrent.atomic.AtomicInteger();
        ReplyContext(String target, String messageId, boolean privateMessage) {
            this.target = target; this.messageId = messageId; this.privateMessage = privateMessage;
        }
        boolean valid() { return !messageId.isBlank() && System.currentTimeMillis() - createdAt < 280_000L; }
    }
    private final Set<String> allowedGroups;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "LuoOS-Official-QQ-Heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String token;
    private volatile WebSocketClient socket;

    public OfficialQQBot(Logger logger, String appId, String appSecret, String apiBase, String tokenBase, String gatewayUrl,
                         FoliaWhitelistRepository repository, SmtpCodeService mail, int codeDigits,
                         int expiresMinutes, Set<String> allowedGroups, Consumer<OneBotEvent> handler) {
        this.logger = logger;
        this.appId = appId;
        this.appSecret = appSecret;
        this.apiBase = apiBase;
        this.tokenBase = tokenBase;
        this.gatewayUrl = gatewayUrl;
        this.repository = repository;
        this.mail = mail;
        this.codeDigits = codeDigits;
        this.expiresMinutes = expiresMinutes;
        this.allowedGroups = Set.copyOf(allowedGroups);
        this.handler = handler;
    }

    public void start() {
        repository.createOfficialBindingTable();
        try {
            token = getToken();
            connect(gatewayUrl == null || gatewayUrl.isBlank() ? discoverGateway() : gatewayUrl);
        } catch (Exception e) {
            logger.severe("[LuoOS-QQ] Failed to start official QQ bot: " + e.getMessage());
        }
    }

    public void stop() {
        commands.shutdownNow();
        heartbeatExecutor.shutdownNow();
        WebSocketClient current = socket;
        if (current != null) current.close();
    }

    private String getToken() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("appId", appId);
        body.addProperty("clientSecret", appSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenBase + "/app/getAppAccessToken"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject result = gson.fromJson(response.body(), JsonObject.class);
        if (response.statusCode() != 200 || result == null || !result.has("access_token")
                || (result.has("code") && result.get("code").getAsInt() != 0)) {
            throw new IllegalStateException("获取官方访问凭证失败，HTTP " + response.statusCode());
        }
        return result.get("access_token").getAsString();
    }

    private String discoverGateway() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/gateway/bot"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "QQBot " + token).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject result = gson.fromJson(response.body(), JsonObject.class);
        if (response.statusCode() != 200 || result == null || !result.has("url")) {
            throw new IllegalStateException("获取官方网关失败，HTTP " + response.statusCode());
        }
        return result.get("url").getAsString();
    }

    private void connect(String url) {
        socket = new WebSocketClient(URI.create(url)) {
            @Override public void onOpen(ServerHandshake handshake) { logger.info("[LuoOS-QQ] Official QQ Gateway connected"); }
            @Override public void onMessage(String text) {
                try { onGatewayMessage(gson.fromJson(text, JsonObject.class)); }
                catch (RuntimeException e) { logger.warning("[LuoOS-QQ] 无法处理网关消息: " + e.getClass().getSimpleName()); }
            }
            @Override public void onClose(int code, String reason, boolean remote) { logger.warning("[LuoOS-QQ] Gateway disconnected: " + reason); }
            @Override public void onError(Exception ex) { logger.warning("[LuoOS-QQ] Gateway error: " + ex.getMessage()); }
        };
        socket.addHeader("Authorization", "QQBot " + token);
        socket.connect();
    }

    private void onGatewayMessage(JsonObject payload) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) sequence.set(payload.get("s").getAsLong());
        int op = payload.get("op").getAsInt();
        if (op == 10) {
            JsonObject d = payload.getAsJsonObject("d");
            if (d.has("heartbeat_interval")) {
                long interval = Math.max(1000L, d.get("heartbeat_interval").getAsLong());
                heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
            }
            JsonObject identify = new JsonObject();
            identify.addProperty("op", 2);
            JsonObject data = new JsonObject();
            data.addProperty("token", "QQBot " + token);
            data.addProperty("intents", 1 << 25);
            com.google.gson.JsonArray shard = new com.google.gson.JsonArray();
            shard.add(0);
            shard.add(1);
            data.add("shard", shard);
            identify.add("d", data);
            socket.send(gson.toJson(identify));
        } else if (op == 1) {
            sendHeartbeat();
        } else if (op == 7) {
            logger.warning("[LuoOS-QQ] Gateway requested reconnect");
            WebSocketClient current = socket;
            if (current != null) current.close();
        } else if (op == 9) {
            logger.warning("[LuoOS-QQ] Gateway authentication failed");
        } else if (op == 0) {
            String event = payload.has("t") ? payload.get("t").getAsString() : "";
            if ("READY".equals(event)) logger.info("[LuoOS-QQ] 官方机器人鉴权成功，READY");
            if ("GROUP_AT_MESSAGE_CREATE".equals(event) || "C2C_MESSAGE_CREATE".equals(event)) {
                JsonObject data = payload.getAsJsonObject("d");
                try { commands.execute(() -> {
                    try {
                        if ("C2C_MESSAGE_CREATE".equals(event)) dispatchPrivateMessage(data);
                        else dispatchMessage(data);
                    } catch (RuntimeException e) { logger.warning("[LuoOS-QQ] 命令处理失败: " + e.getClass().getSimpleName()); }
                }); } catch (java.util.concurrent.RejectedExecutionException e) {
                    logger.warning("[LuoOS-QQ] 命令队列已满或机器人正在关闭");
                }
            }
        }
    }

    private void dispatchMessage(JsonObject data) {
        String openid = OfficialQQMedia.required(data.getAsJsonObject("author"), "member_openid");
        String group = OfficialQQMedia.required(data, "group_openid");
        String content = data.get("content").getAsString().trim();
        String messageId = OfficialQQMedia.required(data, "id");
        if (!allowedGroups.isEmpty() && !allowedGroups.contains(group)) return;
        if (!group.matches("[A-Za-z0-9_-]{1,128}")) return;
        if (groupIds.size() >= 1024 && !groupIds.containsKey(group)) return;
        long groupId = groupIds.computeIfAbsent(group, ignored -> nextGroupId.getAndDecrement());
        ReplyContext context = remember(group, messageId, false);
        if (context == null) return; // 重复投递不得重复执行管理操作。
        if (content.matches("^(验证码|绑定QQ)(\\s.*)?$")) {
            handleBinding(openid, group, messageId, content);
            return;
        }
        long qq = repository.officialQq(openid).orElse(0L);
        JsonObject normalized = new JsonObject();
        normalized.addProperty("post_type", "message");
        normalized.addProperty("message_type", "group");
        normalized.addProperty("group_id", groupId);
        normalized.addProperty("user_id", qq);
        normalized.addProperty("official_verified", qq > 0);
        ReplyContext privateContext = privateReplies.get(qq);
        normalized.addProperty("official_private_ready", privateContext != null && privateContext.valid());
        normalized.addProperty("official_openid", openid);
        normalized.addProperty("official_group_openid", group);
        // 官方群事件不提供被@成员的任何标识：无mentions字段，content里@文本也被平台抹除。
        // 因此官Q不支持@目标，出现@痕迹时按“缺少QQ号”一并提示，不再单独区分。
        if (data.has("mentions")) {
            send(context, "官方QQ接口不提供被@成员的QQ号，请直接填写QQ号。例如：封禁 12345678 1h。", null);
            return;
        }
        // 平台可能残留连续空格，归一化以免污染参数解析。
        content = content.replaceAll("@\\S+", " ").replaceAll("\\s+", " ").trim();
        normalized.addProperty("raw_message", content.trim());
        JsonObject sender = new JsonObject(), author = data.getAsJsonObject("author");
        String role = author.has("member_role") ? author.get("member_role").getAsString() : "member";
        sender.addProperty("role", "admin".equals(role) || "owner".equals(role) ? role : "member");
        normalized.add("sender", sender);
        handler.accept(new OneBotEvent(normalized, "official:" + openid,
                (action, params) -> callApi(context, qq, groupId, action, params)));
    }

    private ReplyContext remember(String target, String id, boolean privateMessage) {
        replies.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAt > 600_000L);
        privateReplies.entrySet().removeIf(e -> !e.getValue().valid());
        if (replies.size() >= 4096) return null;
        ReplyContext context = new ReplyContext(target, id, privateMessage);
        return replies.putIfAbsent((privateMessage ? "user:" : "group:") + target + ":" + id, context) == null ? context : null;
    }

    private void dispatchPrivateMessage(JsonObject data) {
        String user = OfficialQQMedia.required(data.getAsJsonObject("author"), "user_openid");
        if (!user.matches("[A-Za-z0-9_-]{1,128}")) return;
        ReplyContext context = remember(user, OfficialQQMedia.required(data, "id"), true);
        if (context == null) return;
        long qq = repository.officialQq(user).orElse(0L);
        // 不把群member_openid直接当成可发私信的身份；必须收到已验证身份的C2C事件。
        if (qq <= 0) {
            send(context, "此单聊身份尚未通过群内邮箱验证，不能接收账号密码。请先回群验证；若群聊与单聊身份不同，请联系管理员。", null);
            return;
        }
        privateReplies.put(qq, context);
        send(context, "单聊会话已就绪，请在4分钟内回群@机器人发送：重置密码 <账号名>。密码只发送到此单聊。", null);
    }

    private void handleBinding(String openid, String group, String messageId, String content) {
        String[] parts = content.split("\\s+", 3);
        if (parts.length == 2 && "绑定QQ".equals(parts[0])) {
            try {
                long qq = Long.parseLong(parts[1]);
                if (qq <= 0 || repository.officialQq(openid).isPresent() || repository.officialBindingExistsForQq(qq)) { send(group, "绑定失败！原因：QQ号无效或身份已被绑定，不允许直接覆盖。", messageId); return; }
                String code = randomCode();
                if (!repository.requestOfficialCode(openid, qq, hash(code), System.currentTimeMillis() + expiresMinutes * 60_000L,
                        () -> mail.send(qq + "@qq.com", code))) {
                    send(group, "绑定申请失败：请求过于频繁、身份已绑定、邮件或数据库异常。请至少60秒后重试；本次未授予身份。", messageId);
                    return;
                }
                send(group, "验证码已发送到‘" + qq + "@qq.com’，" + expiresMinutes + "分钟内有效。接收到验证码后请@我发送‘验证码 " + code.replaceAll(".", "x") + "’以完成绑定", messageId);
            } catch (Exception e) { send(group, "绑定失败！原因：QQ号格式错误或邮件发送失败。", messageId); }
        } else if (parts.length == 2 && "验证码".equals(parts[0])) {
            if (repository.confirmOfficialCode(openid, hash(parts[1]), System.currentTimeMillis())) {
                repository.officialQq(openid).ifPresent(qq -> {
                    send(group, "绑定成功！您绑定的QQ号：" + qq + "，现在您可以进行申请白名单等操作了。", messageId);
                });
            } else send(group, "绑定失败！原因：验证码错误、过期、尝试次数已达上限、身份冲突或数据库异常。", messageId);
        } else {
            send(group, "尚未通过邮箱验证，不能执行账号命令。请发送：绑定QQ <QQ号>，然后提交邮箱收到的验证码。", messageId);
        }
    }

    private CompletableFuture<JsonObject> callApi(ReplyContext origin, long sourceQq, long groupId, String action, String params) {
        try {
            JsonObject p = gson.fromJson(params, JsonObject.class);
            ReplyContext target;
            if ("send_group_msg".equals(action)) {
                if (p.get("group_id").getAsLong() != groupId) return failed("不允许跨群回复");
                target = origin;
            } else if ("send_private_msg".equals(action)) {
                if (p.get("user_id").getAsLong() != sourceQq || sourceQq <= 0) return failed("不允许向其他身份发送密码");
                target = privateReplies.get(sourceQq);
                if (target == null || !target.valid()) return failed("请先与机器人建立单聊会话");
            } else return failed("官方QQ不支持此操作: " + action);
            StringBuilder text = new StringBuilder();
            String image = null;
            for (var item : p.getAsJsonArray("message")) {
                JsonObject segment = item.getAsJsonObject(), value = segment.getAsJsonObject("data");
                switch (segment.get("type").getAsString()) {
                    case "text" -> text.append(value.get("text").getAsString());
                    case "image" -> {
                        if (image != null || target.privateMessage) return failed("仅支持群内单张图片");
                        image = value.get("file").getAsString();
                    }
                    default -> { return failed("官方QQ不支持此消息段"); }
                }
            }
            if (image != null) {
                final ReplyContext destination = target;
                final String caption = text.toString();
                return OfficialQQMedia.upload(http, apiBase, token, target.target, image)
                        .orTimeout(45, TimeUnit.SECONDS)
                        .thenCompose(info -> send(destination, caption, info).thenApply(outcome -> {
                            if (outcome.get("retcode").getAsInt() != 0)
                                logger.warning("[LuoOS-QQ] 图片消息发送失败: " + outcome.get("msg").getAsString());
                            return outcome;
                        })).exceptionally(e -> failure("图片上传失败: " + describe(e)));
            }
            return send(target, text.toString(), null);
        } catch (RuntimeException e) { return failed("官方消息参数错误"); }
    }

    private CompletableFuture<JsonObject> send(String group, String text, String messageId) {
        return send(replies.get("group:" + group + ":" + messageId), text, null);
    }

    private CompletableFuture<JsonObject> send(ReplyContext context, String text, String fileInfo) {
        if (context == null || !context.valid()) return failed("回复窗口已过期，请重新发送命令");
        int seq = context.replySequence.incrementAndGet();
        if (seq > 5) return failed("本条消息回复次数已用完，请重新发送命令");
        if (fileInfo == null && text.isBlank()) return failed("不能发送空消息");
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        body.addProperty("msg_type", fileInfo == null ? 0 : 7);
        body.addProperty("msg_id", context.messageId);
        body.addProperty("msg_seq", seq);
        if (fileInfo != null) {
            JsonObject media = new JsonObject(); media.addProperty("file_info", fileInfo); body.add("media", media);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v2/"
                        + (context.privateMessage ? "users/" : "groups/") + context.target + "/messages"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "QQBot " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        return sendChecked(request);
    }

    private CompletableFuture<JsonObject> failed(String reason) {
        return CompletableFuture.completedFuture(failure(reason));
    }
    private JsonObject failure(String reason) {
        logger.warning("[LuoOS-QQ] " + reason);
        JsonObject result = new JsonObject();
        result.addProperty("status", "failed"); result.addProperty("retcode", -1); result.addProperty("msg", reason);
        return result;
    }

    /** 日志只保留错误类别，避免把平台URL或内容写进日志。 */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String text = String.valueOf(cause.getMessage());
        if (!text.matches("[\\p{IsHan}0-9A-Za-z _:，。、（）-]{1,120}")) return cause.getClass().getSimpleName() + "（平台返回内容已隐去）";
        return cause.getClass().getSimpleName() + ": " + text;
    }

    private CompletableFuture<JsonObject> sendChecked(HttpRequest request) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle((response, error) -> {
            JsonObject result = new JsonObject();
            boolean success = false;
            int code = -1;
            if (error == null) {
                try {
                    JsonObject body = gson.fromJson(response.body(), JsonObject.class);
                    code = body != null && body.has("err_code") ? body.get("err_code").getAsInt()
                            : body != null && body.has("code") ? body.get("code").getAsInt() : 0;
                    success = response.statusCode() == 200 && code == 0 && body != null && body.has("id");
                } catch (RuntimeException ignored) { code = -1; }
            }
            result.addProperty("status", success ? "ok" : "failed");
            result.addProperty("retcode", success ? 0 : code == 0 ? -1 : code);
            if (!success) logger.warning("[LuoOS-QQ] 消息发送失败，HTTP="
                    + (response == null ? "network-error" : response.statusCode()) + "，code=" + code);
            return result;
        });
    }

    private void sendHeartbeat() {
        JsonObject heartbeat = new JsonObject();
        heartbeat.addProperty("op", 1);
        if (sequence.get() >= 0) heartbeat.addProperty("d", sequence.get());
        else heartbeat.add("d", com.google.gson.JsonNull.INSTANCE);
        WebSocketClient current = socket;
        if (current != null && current.isOpen()) current.send(gson.toJson(heartbeat));
    }

    private String randomCode() {
        if (codeDigits < 6 || codeDigits > 9) throw new IllegalArgumentException("code_digits must be between 6 and 9");
        int bound = (int) Math.pow(10, codeDigits - 1);
        return String.valueOf(bound + new SecureRandom().nextInt(bound * 9));
    }
    private static String hash(String value) { try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
