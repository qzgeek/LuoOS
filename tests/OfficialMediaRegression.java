package heos.folia.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** 本地HTTP契约测试，调用真实上传代码；不是腾讯线上实测。 */
public final class OfficialMediaRegression {
    static int passed;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    static String digest(String alg, byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(bytes));
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final byte[] image;
        final String base;
        final String mode;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, Integer> attempts = new ConcurrentHashMap<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        Fixture(String mode) throws Exception {
            this.mode = mode;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "png", out);
            image = out.toByteArray();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); calls.add(path);
                int hit = attempts.merge(path, 1, Integer::sum);
                // flaky: 第一次预上传直接断开连接（模拟网络超时/连接被重置），用于验证重试
                if (mode.equals("flaky") && path.endsWith("/upload_prepare") && hit == 1) {
                    exchange.close();
                    return;
                }
                int code = 200; JsonObject reply = new JsonObject();
                try {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    if (path.startsWith("/put/")) {
                        check("PUT".equals(exchange.getRequestMethod()), "PUT verb");
                        check(exchange.getRequestHeaders().getFirst("Authorization") == null, "no token on signed PUT");
                        int index = Integer.parseInt(path.substring(5));
                        int block = (image.length + 1) / 2;
                        check(Arrays.equals(bytes, Arrays.copyOfRange(image, index * block, Math.min(image.length, (index + 1) * block))), "exact chunk bytes");
                        if (mode.equals("put")) code = 403;
                    } else {
                        check("QQBot TEST_ONLY_TOKEN".equals(exchange.getRequestHeaders().getFirst("Authorization")), "API authentication");
                        JsonObject request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (path.endsWith("/upload_prepare")) {
                            check(request.get("file_size").getAsInt() == image.length, "file size");
                            check(request.get("md5").getAsString().equals(digest("MD5", image)), "MD5");
                            check(request.get("sha1").getAsString().equals(digest("SHA-1", image)), "SHA1");
                            check(request.get("md5_10m").getAsString().equals(digest("MD5", image)), "prefix MD5");
                            check(request.get("file_type").getAsInt() == 1, "image type");
                            if (mode.equals("prepare")) code = 500;
                            reply.addProperty("upload_id", "TEST_UPLOAD");
                            int block = (image.length + 1) / 2; reply.addProperty("block_size", String.valueOf(block));
                            JsonArray parts = new JsonArray();
                            for (int i = 0; i < 2; i++) {
                                JsonObject part = new JsonObject(); part.addProperty("index", mode.equals("index") ? 0 : mode.equals("one-based") ? i + 1 : i);
                                part.addProperty("block_size", String.valueOf(Math.min(block, image.length - i * block)));
                                part.addProperty("presigned_url", mode.equals("url") ? "http://example.invalid/" : base + "/put/" + i); parts.add(part);
                            }
                            reply.add("parts", parts);
                            if (mode.equals("api-code")) reply.addProperty("code", 850019);
                        } else if (path.endsWith("/upload_part_finish")) {
                            int i = request.get("part_index").getAsInt() - (mode.equals("one-based") ? 1 : 0), block = (image.length + 1) / 2;
                            byte[] chunk = Arrays.copyOfRange(image, i * block, Math.min(image.length, (i + 1) * block));
                            check(request.get("block_size").getAsInt() == chunk.length, "finish size");
                            check(request.get("md5").getAsString().equals(digest("MD5", chunk)), "chunk MD5");
                            if (mode.equals("finish")) code = 500;
                        } else if (path.endsWith("/files")) {
                            check(!request.get("srv_send_msg").getAsBoolean(), "upload not auto send");
                            check(request.get("upload_id").getAsString().equals("TEST_UPLOAD"), "merge upload id");
                            if (!mode.equals("missing")) reply.addProperty("file_info", "TEST_FILE_INFO");
                        } else throw new AssertionError("unexpected path " + path);
                    }
                } catch (Throwable e) { errors.add(e); code = 500; }
                byte[] response = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, response.length); exchange.getResponseBody().write(response); exchange.close();
            }); server.start();
        }
        String imageFile() { return "base64://" + Base64.getEncoder().encodeToString(image); }
        CompletableFuture<String> upload(String file) { return OfficialQQMedia.upload(HttpClient.newHttpClient(), base, "TEST_ONLY_TOKEN", "GROUP", file); }
        public void close() { server.stop(0); }
    }
    static Throwable failure(Fixture f, String input) throws Exception {
        try { f.upload(input).get(8, TimeUnit.SECONDS); throw new AssertionError("expected failure"); }
        catch (ExecutionException expected) { return expected.getCause(); }
    }
    public static void main(String[] args) throws Exception {
        try (Fixture f = new Fixture("ok")) {
            check(f.upload(f.imageFile()).get(5, TimeUnit.SECONDS).equals("TEST_FILE_INFO"), "file_info");
            check(f.errors.isEmpty(), "contract errors " + f.errors);
            check(f.calls.equals(List.of("/v2/groups/GROUP/upload_prepare", "/put/0", "/v2/groups/GROUP/upload_part_finish",
                    "/put/1", "/v2/groups/GROUP/upload_part_finish", "/v2/groups/GROUP/files")), "request order");
            pass("真实HTTP分片/校验和/字节/顺序/认证头隔离/合并完成");
        }
        try (Fixture f = new Fixture("one-based")) {
            check(f.upload(f.imageFile()).get(5, TimeUnit.SECONDS).equals("TEST_FILE_INFO"), "1-based real API format");
            check(f.errors.isEmpty(), "1-based exact bytes and original part_index " + f.errors);
            pass("真实平台从1开始的分片序号仍上传正确字节并回传原始序号");
        }
        for (String mode : List.of("prepare", "api-code", "index", "url", "put", "finish", "missing")) {
            try (Fixture f = new Fixture(mode)) {
                failure(f, f.imageFile());
                check(f.errors.isEmpty(), "mock contract errors: " + f.errors);
                if (!mode.equals("missing")) check(f.calls.stream().noneMatch(p -> p.endsWith("/files")), "failure never merges");
                if (List.of("prepare", "api-code", "index", "url").contains(mode)) check(f.calls.size() == 1, "invalid prepare never PUTs");
                if (mode.equals("put")) check(f.calls.size() == 2, "failed PUT never finishes");
                if (mode.equals("finish")) check(f.calls.size() == 3, "finish failure stops second chunk");
                pass("拒绝失败且停止后续请求: " + mode);
            }
        }
        // 失败信息必须带上传阶段，否则线上只看到 HttpTimeoutException 无法定位卡在哪一步。
        try (Fixture f = new Fixture("prepare")) {
            String detail = OfficialQQBot.describe(failure(f, f.imageFile()));
            check(detail.contains("upload_prepare"), "stage name missing: " + detail);
            check(detail.contains("HTTP 500"), "root cause lost: " + detail);
        }
        try (Fixture f = new Fixture("put")) {
            String detail = OfficialQQBot.describe(failure(f, f.imageFile()));
            check(detail.contains("PUT part 0"), "PUT stage name missing: " + detail);
        }
        pass("失败信息带上传阶段与真实原因（prepare / PUT）");

        // 网络类错误（连接被重置/超时）重试一次；协议错误不重试（见上面的失败模式断言）。
        try (Fixture f = new Fixture("flaky")) {
            check(f.upload(f.imageFile()).get(8, TimeUnit.SECONDS).equals("TEST_FILE_INFO"), "retry succeeds");
            check(f.calls.equals(List.of("/v2/groups/GROUP/upload_prepare", "/v2/groups/GROUP/upload_prepare",
                    "/put/0", "/v2/groups/GROUP/upload_part_finish", "/put/1", "/v2/groups/GROUP/upload_part_finish",
                    "/v2/groups/GROUP/files")), "retry order " + f.calls);
            check(f.errors.isEmpty(), "retry contract errors: " + f.errors);
        }
        pass("连接中断时自动重试一次并完成上传，HTTP 错误不重试");
        for (String file : List.of("file:///etc/passwd", "https://example.invalid/image.png", "base64://AA==", "base64://!")) {
            try (Fixture f = new Fixture("ok")) { failure(f, file); check(f.calls.isEmpty(), "invalid file never connects"); }
        }
        pass("拒绝任意文件读取/外链/非法图片/损坏base64");
        System.out.println("MEDIA PASS " + passed);
    }
}
