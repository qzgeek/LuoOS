package heos.folia.bot;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 官Q图片分片上传。不需要图床；预签名PUT绝不携带机器人密钥。 */
public final class OfficialQQMedia {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    /**
     * 整条上传链路的时间预算。必须小于调用方 45 秒兜底超时，
     * 否则文字降级先发出去、图片随后又到达，会重复回复同一张卡片。
     */
    private static final long UPLOAD_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(40);
    private static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PUT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FINISH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MERGE_TIMEOUT = Duration.ofSeconds(10);

    private OfficialQQMedia() {}

    /** 上传阶段失败。阶段名必须进日志：只报 HttpTimeoutException 无法判断卡在 prepare/PUT/finish/files。 */
    static final class StageFailure extends RuntimeException {
        final String stage;
        StageFailure(String stage, Throwable cause) {
            super("阶段[" + stage + "]", cause);
            this.stage = stage;
        }
    }

    /** 访问凭证失效（HTTP 401）。单独成类，调用方据此刷新凭证后重试一次。 */
    static final class Unauthorized extends RuntimeException {
        Unauthorized(String message) { super(message); }
    }

    /** 失败链上是否含「凭证失效」。stage()/retrying() 会各包一层，必须遍历 cause 链。 */
    static boolean isUnauthorized(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof Unauthorized) return true;
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return false;
    }

    public static CompletableFuture<String> upload(HttpClient http, String apiBase, String token,
                                                    String group, String file) {
        try {
            if (!group.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("无效群标识");
            if (file == null || !file.startsWith("base64://") || file.length() > MAX_BYTES * 4L / 3 + 16)
                throw new IllegalArgumentException("仅支持20MiB以内的内存图片");
            byte[] bytes = Base64.getDecoder().decode(file.substring(9));
            boolean png = bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                    new byte[]{(byte)137,80,78,71,13,10,26,10});
            boolean jpg = bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255;
            if (bytes.length > MAX_BYTES || (!png && !jpg)) throw new IllegalArgumentException("图片必须是PNG或JPEG");
            String path = apiBase.replaceAll("/$", "") + "/v2/groups/" + group;
            String name = png ? "status.png" : "status.jpg";
            long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
            JsonObject request = new JsonObject();
            request.addProperty("file_type", 1);
            request.addProperty("file_name", name);
            request.addProperty("file_size", String.valueOf(bytes.length));
            request.addProperty("md5", digest("MD5", bytes));
            request.addProperty("sha1", digest("SHA-1", bytes));
            request.addProperty("md5_10m", digest("MD5", Arrays.copyOf(bytes, Math.min(bytes.length, 10002432))));
            return stage("upload_prepare", post(http, path + "/upload_prepare", token, request, PREPARE_TIMEOUT, deadline))
                .thenCompose(prepared -> {
                String uploadId = required(prepared, "upload_id");
                int block = prepared.get("block_size").getAsInt();
                JsonArray parts = prepared.getAsJsonArray("parts");
                if (block <= 0 || parts == null || parts.isEmpty() || parts.size() > 128
                        || parts.size() != (bytes.length + (long)block - 1) / block)
                    throw new IllegalArgumentException("分片数量或大小无效");
                // 先校验完整分片表，避免畸形响应导致部分上传或越界。
                List<JsonObject> sorted = new ArrayList<>();
                for (JsonElement part : parts) sorted.add(part.getAsJsonObject());
                sorted.sort(Comparator.comparingInt(p -> p.get("index").getAsInt()));
                // 文档示例从0开始，真实接口也会从1开始；保留平台给定的序号。
                int firstIndex = sorted.get(0).get("index").getAsInt();
                if (firstIndex != 0 && firstIndex != 1) throw new IllegalArgumentException("分片起始序号无效");
                for (int i = 0; i < sorted.size(); i++) {
                    JsonObject p = sorted.get(i);
                    int size = (int)Math.min(block, bytes.length - (long)i * block);
                    if (p.get("index").getAsInt() != i + firstIndex || size <= 0 || p.get("block_size").getAsLong() != size)
                        throw new IllegalArgumentException("分片未完整覆盖图片");
                    safePutUri(required(p, "presigned_url"), apiBase);
                }
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (int i = 0; i < sorted.size(); i++) {
                    JsonObject part = sorted.get(i);
                    byte[] chunk = Arrays.copyOfRange(bytes, i * block, (int)Math.min(bytes.length, (long)(i + 1) * block));
                    chain = chain.thenCompose(ignored -> {
                        String stage = "PUT part " + part.get("index").getAsInt();
                        HttpRequest put = HttpRequest.newBuilder(safePutUri(required(part, "presigned_url"), apiBase))
                                .timeout(PUT_TIMEOUT).header("Content-Type", "application/octet-stream")
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build();
                        return stage(stage, retrying(() -> http.sendAsync(put, HttpResponse.BodyHandlers.discarding()), deadline)
                                .thenCompose(resp -> {
                            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                                throw new IllegalStateException("图片分片上传失败 HTTP " + resp.statusCode());
                            JsonObject finish = new JsonObject();
                            finish.addProperty("upload_id", uploadId);
                            finish.addProperty("part_index", part.get("index").getAsInt());
                            finish.addProperty("block_size", String.valueOf(chunk.length));
                            finish.addProperty("md5", digest("MD5", chunk));
                            return stage("upload_part_finish", post(http, path + "/upload_part_finish", token,
                                    finish, FINISH_TIMEOUT, deadline)).thenApply(r -> null);
                        }));
                    });
                }
                return chain.thenCompose(ignored -> {
                    JsonObject merge = new JsonObject();
                    merge.addProperty("file_type", 1);
                    merge.addProperty("srv_send_msg", false);
                    merge.addProperty("file_name", name);
                    merge.addProperty("upload_id", uploadId);
                    return stage("files", post(http, path + "/files", token, merge, MERGE_TIMEOUT, deadline))
                            .thenApply(r -> required(r, "file_info"));
                });
            });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    /** 给失败加上阶段名，便于从线上日志定位到底卡在哪一步。 */
    static <T> CompletableFuture<T> stage(String stage, CompletableFuture<T> future) {
        return future.handle((value, error) -> {
            if (error == null) return value;
            Throwable cause = rootCause(error);
            if (cause instanceof StageFailure failure) throw failure;
            throw new StageFailure(stage, cause);
        });
    }

    /**
     * 网络类错误（超时、连接被重置、EOF）重试一次；HTTP 状态码/协议错误不重试。
     * 重试前检查总预算，避免把整条链路拖到调用方兜底超时之后。
     */
    static <T> CompletableFuture<T> retrying(java.util.function.Supplier<CompletableFuture<T>> attempt, long deadlineNanos) {
        return attempt.get().handle((value, error) -> {
            if (error == null) return CompletableFuture.completedFuture(value);
            Throwable cause = rootCause(error);
            if (!transientFailure(cause) || System.nanoTime() >= deadlineNanos) {
                return CompletableFuture.<T>failedFuture(cause);
            }
            return attempt.get();
        }).thenCompose(future -> future);
    }

    private static boolean transientFailure(Throwable cause) {
        return cause instanceof HttpTimeoutException
                || cause instanceof java.net.ConnectException
                || cause instanceof java.io.IOException
                || cause instanceof java.util.concurrent.TimeoutException;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }

    static CompletableFuture<JsonObject> post(HttpClient http, String url, String token, JsonObject body,
                                              Duration timeout, long deadlineNanos) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Authorization", "QQBot " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        return retrying(() -> http.sendAsync(req, HttpResponse.BodyHandlers.ofString()), deadlineNanos).thenApply(resp -> {
            // 401 必须与普通 HTTP 错误区分：它不是参数问题，而是凭证过期，刷新后重试有效。
            if (resp.statusCode() == 401) throw new Unauthorized("官方接口 401（访问凭证失效）");
            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                throw new IllegalStateException("官方接口失败 HTTP " + resp.statusCode());
            JsonObject result = JsonParser.parseString(resp.body()).getAsJsonObject();
            for (String field : List.of("code", "err_code"))
                if (result.has(field) && result.get(field).getAsInt() != 0)
                    throw new IllegalStateException("官方接口错误码 " + result.get(field).getAsInt());
            return result;
        });
    }

    private static URI safePutUri(String url, String apiBase) {
        URI uri = URI.create(url), base = URI.create(apiBase);
        boolean loopbackTest = "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost())
                && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !loopbackTest) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) throw new IllegalArgumentException("非安全预签名上传地址");
        return uri;
    }
    static String required(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || object.get(key).getAsString().isBlank())
            throw new IllegalStateException("官方响应缺少 " + key);
        return object.get(key).getAsString();
    }
    static String digest(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("校验和计算失败", e); }
    }
}
