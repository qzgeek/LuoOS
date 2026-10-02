package heos.folia.bot;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 官Q图片分片上传。不需要图床；预签名PUT绝不携带机器人密钥。 */
public final class OfficialQQMedia {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private OfficialQQMedia() {}

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
            JsonObject request = new JsonObject();
            request.addProperty("file_type", 1);
            request.addProperty("file_name", name);
            request.addProperty("file_size", String.valueOf(bytes.length));
            request.addProperty("md5", digest("MD5", bytes));
            request.addProperty("sha1", digest("SHA-1", bytes));
            request.addProperty("md5_10m", digest("MD5", Arrays.copyOf(bytes, Math.min(bytes.length, 10002432))));
            return post(http, path + "/upload_prepare", token, request).thenCompose(prepared -> {
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
                        HttpRequest put = HttpRequest.newBuilder(safePutUri(required(part, "presigned_url"), apiBase))
                                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/octet-stream")
                                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)).build();
                        return http.sendAsync(put, HttpResponse.BodyHandlers.discarding()).thenCompose(resp -> {
                            if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                                throw new IllegalStateException("图片分片上传失败 HTTP " + resp.statusCode());
                            JsonObject finish = new JsonObject();
                            finish.addProperty("upload_id", uploadId);
                            finish.addProperty("part_index", part.get("index").getAsInt());
                            finish.addProperty("block_size", String.valueOf(chunk.length));
                            finish.addProperty("md5", digest("MD5", chunk));
                            return post(http, path + "/upload_part_finish", token, finish).thenApply(r -> null);
                        });
                    });
                }
                return chain.thenCompose(ignored -> {
                    JsonObject merge = new JsonObject();
                    merge.addProperty("file_type", 1);
                    merge.addProperty("srv_send_msg", false);
                    merge.addProperty("file_name", name);
                    merge.addProperty("upload_id", uploadId);
                    return post(http, path + "/files", token, merge).thenApply(r -> required(r, "file_info"));
                });
            });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    static CompletableFuture<JsonObject> post(HttpClient http, String url, String token, JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "QQBot " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
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
