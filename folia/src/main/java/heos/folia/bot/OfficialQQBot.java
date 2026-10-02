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
    private volatile String gateway;
    /** 会话 id 与事件序号：用于断线后 resume 补发遗漏事件。 */
    private volatile String sessionId;
    private volatile long tokenExpiresAt;
    private volatile boolean stopping;
    private final java.util.concurrent.atomic.AtomicInteger reconnectAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatFuture;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "LuoOS-Official-QQ-Reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private static final int INTENT_GROUP_AND_C2C = 1 << 25;
    private static final int INTENT_GROUP_MEMBER = 1 << 24;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long RECONNECT_BASE_MS = 3000L;
    private static final long RECONNECT_MAX_MS = 300000L;
    /** 令牌提前刷新余量：官方令牌约 2 小时有效，余量取 10 分钟。 */
    private static final long TOKEN_REFRESH_MARGIN_MS = 600_000L;
    /** 调试日志开关，由插件启动时注入。 */
    public volatile boolean debugLog = false;

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
        stopping = true;
        commands.shutdownNow();
        reconnectExecutor.shutdownNow();
        java.util.concurrent.ScheduledFuture<?> beat = heartbeatFuture;
        if (beat != null) beat.cancel(false);
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
            // 平台在参数错误时也可能返回 HTTP 200，只报 HTTP 200 无法排查；带上错误码与信息（不含密钥）。
            String code = result != null && result.has("code") ? String.valueOf(result.get("code")) : "无";
            String message = result != null && result.has("message") ? String.valueOf(result.get("message")) : "无";
            throw new IllegalStateException("获取官方访问凭证失败，HTTP " + response.statusCode()
                    + "，code=" + code + "，message=" + message);
        }
        // 官方令牌默认约 2 小时有效；若响应带 expires_in 则按其计算，重连前据此刷新。
        long expiresIn = result.has("expires_in") ? result.get("expires_in").getAsLong() : 7200L;
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L;
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
        gateway = url;
        socket = new WebSocketClient(URI.create(url)) {
            @Override public void onOpen(ServerHandshake handshake) { logger.info("[LuoOS-QQ] Official QQ Gateway connected"); }
            @Override public void onMessage(String text) {
                try { onGatewayMessage(gson.fromJson(text, JsonObject.class)); }
                catch (RuntimeException e) { logger.warning("[LuoOS-QQ] 无法处理网关消息: " + e.getClass().getSimpleName()); }
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                logger.warning("[LuoOS-QQ] Gateway disconnected: code=" + code + " reason=" + reason);
                scheduleReconnect(code);
            }
            @Override public void onError(Exception ex) { logger.warning("[LuoOS-QQ] Gateway error: " + ex.getMessage()); }
        };
        socket.addHeader("Authorization", "QQBot " + token);
        socket.connect();
    }

    /**
     * 断线重连。
     * 4xxx 中不可重试的错误码直接放弃，避免无意义的重连风暴；
     * 其余情况按退避策略重连，并能 resume 时优先 resume（补发遗漏事件）。
     */
    /**
     * 该关闭码是否值得重连。
     * 官方文档中 4001/4002/4010-4014/4914/4915 属于不可恢复错误，
     * 重连不会成功（例如机器人已下架或封禁），继续重连只是徒劳刷日志。
     */
    static boolean isRetryableClose(int closeCode) {
        if (closeCode == 4001 || closeCode == 4002) return false;
        if (closeCode >= 4010 && closeCode <= 4014) return false;
        return closeCode != 4914 && closeCode != 4915;
    }

    private void scheduleReconnect(int closeCode) {
        if (stopping) return;
        if (!isRetryableClose(closeCode)) {
            logger.severe("[LuoOS-QQ] 网关返回不可恢复错误码 " + closeCode + "，已停止重连。请检查机器人状态或权限。");
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            logger.severe("[LuoOS-QQ] 连续重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍失败，已停止。请检查网络或重新配置。");
            return;
        }
        long delay = Math.min(RECONNECT_BASE_MS * (1L << Math.min(attempt - 1, 5)), RECONNECT_MAX_MS);
        logger.info("[LuoOS-QQ] 将在 " + (delay / 1000) + " 秒后尝试第 " + attempt + " 次重连"
                + (canResume() ? "（含会话恢复）" : ""));
        try {
            reconnectExecutor.schedule(this::doReconnect, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 正在关闭
        }
    }

    private void doReconnect() {
        if (stopping) return;
        try {
            // 访问令牌有效期有限，重连前确保令牌仍然可用
            if (tokenExpiresAt > 0 && System.currentTimeMillis() > tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS) {
                token = getToken();
            }
            String target = gateway == null || gateway.isBlank() ? discoverGateway() : gateway;
            connect(target);
        } catch (Exception e) {
            logger.warning("[LuoOS-QQ] 重连失败: " + OfficialQQBot.describe(e));
            scheduleReconnect(-1);
        }
    }

    /** 是否具备 resume 条件：有会话 id 且已收到过事件序号。 */
    private boolean canResume() {
        return sessionId != null && !sessionId.isBlank() && sequence.get() >= 0;
    }

    private void onGatewayMessage(JsonObject payload) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) sequence.set(payload.get("s").getAsLong());
        int op = payload.get("op").getAsInt();
        if (op == 10) {
            JsonObject d = payload.getAsJsonObject("d");
            if (d.has("heartbeat_interval")) {
                long interval = Math.max(1000L, d.get("heartbeat_interval").getAsLong());
                heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
                        this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
            }
            // 有可用会话时优先 resume：网关会补发断线期间遗漏的事件，避免漏处理命令
            if (canResume()) {
                JsonObject resume = new JsonObject();
                resume.addProperty("op", 6);
                JsonObject data = new JsonObject();
                data.addProperty("token", "QQBot " + token);
                data.addProperty("session_id", sessionId);
                data.addProperty("seq", sequence.get());
                resume.add("d", data);
                socket.send(gson.toJson(resume));
                return;
            }
            JsonObject identify = new JsonObject();
            identify.addProperty("op", 2);
            JsonObject data = new JsonObject();
            data.addProperty("token", "QQBot " + token);
            // 订阅群/C2C 消息事件与群成员进退事件
            data.addProperty("intents", INTENT_GROUP_AND_C2C | INTENT_GROUP_MEMBER);
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
            // 4007/4006 允许重新 identify；其余视为鉴权失败
            logger.warning("[LuoOS-QQ] 会话无效（op 9），将重新鉴权连接");
            sessionId = null;
            sequence.set(-1);
            WebSocketClient current = socket;
            if (current != null) current.close();
        } else if (op == 0) {
            String event = payload.has("t") ? payload.get("t").getAsString() : "";
            handleGatewayEvent(event, payload);
        }
    }

    private void handleGatewayEvent(String event, JsonObject payload) {
        switch (event) {
            case "READY" -> {
                reconnectAttempts.set(0);
                JsonObject d = payload.getAsJsonObject("d");
                if (d != null && d.has("session_id")) sessionId = d.get("session_id").getAsString();
                logger.info("[LuoOS-QQ] 官方机器人鉴权成功，READY"
                        + (sessionId != null ? "（会话 " + sessionId.substring(0, Math.min(8, sessionId.length())) + "）" : ""));
            }
            case "RESUMED" -> {
                reconnectAttempts.set(0);
                logger.info("[LuoOS-QQ] 会话已恢复，断线期间的事件已补发");
            }
            case "GROUP_AT_MESSAGE_CREATE", "C2C_MESSAGE_CREATE", "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE" ->
                    submitEvent(event, payload.getAsJsonObject("d"));
            default -> {
                // 记录所有其他事件类型，便于确认平台实际推送了什么。
                // 官方文档中部分事件需要单独申请权限，未开通时不会下发，
                // 静默忽略会让人误以为「功能已实现但没触发」。
                if (!event.isBlank()) logger.info("[LuoOS-QQ] 收到未处理的事件类型: " + event);
            }
        }
    }

    private void submitEvent(String event, JsonObject data) {
        if (data == null) return;
        try {
            commands.execute(() -> {
                try {
                    switch (event) {
                        case "C2C_MESSAGE_CREATE" -> dispatchPrivateMessage(data);
                        case "GROUP_AT_MESSAGE_CREATE" -> dispatchMessage(data);
                        case "GROUP_MEMBER_ADD" -> dispatchMemberChange(data, false);
                        case "GROUP_MEMBER_REMOVE" -> dispatchMemberChange(data, true);
                        default -> { }
                    }
                } catch (RuntimeException e) {
                    logger.warning("[LuoOS-QQ] 事件处理失败(" + event + "): " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warning("[LuoOS-QQ] 命令队列已满或机器人正在关闭");
        }
    }

    /**
     * 群成员进退群 → 冻结/恢复白名单。
     * 官方事件只提供 member_openid，因此仅对「已完成邮箱验证」的成员生效：
     * 未验证成员本来就是未绑定状态，没有白名单可冻结。
     */
    private void dispatchMemberChange(JsonObject data, boolean left) {
        String group = OfficialQQMedia.required(data, "group_openid");
        String openid = OfficialQQMedia.required(data, "member_openid");
        if (!allowedGroups.isEmpty() && !allowedGroups.contains(group)) return;
        long qq = repository.officialQq(openid).orElse(0L);
        if (qq <= 0) {
            // 始终记录：用于确认事件确实到达，以及该成员是否已完成邮箱验证
            logger.info("[LuoOS-QQ] 群成员" + (left ? "退出" : "加入")
                    + "：该 openid 尚未绑定身份，跳过白名单" + (left ? "冻结" : "恢复"));
            return;
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("post_type", "notice");
        normalized.addProperty("notice_type", left ? "group_decrease" : "group_increase");
        normalized.addProperty("sub_type", left ? "leave" : "approve");
        normalized.addProperty("group_id", replyGroupId(group));
        normalized.addProperty("user_id", qq);
        normalized.addProperty("official_verified", true);
        handler.accept(new OneBotEvent(normalized, "official:member",
                (action, params) -> CompletableFuture.completedFuture(new JsonObject())));
    }

    /** 群 openid 与内部群号的双向映射，复用消息路径的稳定编号。 */
    private long replyGroupId(String group) {
        return groupIds.computeIfAbsent(group, ignored -> nextGroupId.getAndDecrement());
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
                var result = repository.requestOfficialCodeDetailed(openid, qq, hash(code),
                        System.currentTimeMillis() + expiresMinutes * 60_000L, () -> mail.send(qq + "@qq.com", code));
                if (result != heos.folia.storage.OfficialBindingRepository.RequestResult.OK) {
                    // 按真实原因提示，避免把四种情况混成一句让用户无从判断。
                    String reason = switch (result) {
                        case ALREADY_BOUND -> "该QQ号或当前账号已完成绑定，不能重复绑定。";
                        case RATE_LIMITED -> "请求过于频繁，请至少60秒后再试。";
                        case MAIL_FAILED -> "验证码邮件发送失败，请联系管理员检查邮箱配置。";
                        case STORAGE_FAILED -> "数据库写入失败，请稍后重试或联系管理员。";
                        default -> "请求参数无效，请检查QQ号格式。";
                    };
                    send(group, "绑定申请失败：" + reason + " 本次未授予身份。", messageId);
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
