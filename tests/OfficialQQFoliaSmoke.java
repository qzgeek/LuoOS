package heos.folia.bot;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import heos.folia.storage.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** 只装入全新本地测试服：真实Bukkit卡片/命令 + 明确的HTTP平台替身。 */
public class OfficialQQFoliaSmoke extends JavaPlugin {
    private HttpServer mock;
    private OfficialQQBot bot;
    private FoliaStorage storage;
    private final BlockingQueue<JsonObject> delivered = new LinkedBlockingQueue<>();
    private volatile byte[] image;
    private String base;
    private int size;
    @Override public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t ->
                Bukkit.getAsyncScheduler().runNow(this, task -> executeTests()), 40);
    }
    private void executeTests() {
        String result;
        try {
            getDataFolder().mkdirs();
            mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + mock.getAddress().getPort();
            mock.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                byte[] bytes = ex.getRequestBody().readAllBytes();
                JsonObject response = new JsonObject();
                if (path.equals("/put")) image = bytes;
                else {
                    JsonObject req = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (path.endsWith("/upload_prepare")) {
                        size = req.get("file_size").getAsInt();
                        response.addProperty("upload_id", "LOCAL_SMOKE"); response.addProperty("block_size", String.valueOf(size));
                        JsonObject part = new JsonObject(); part.addProperty("index", 0); part.addProperty("block_size", String.valueOf(size));
                        part.addProperty("presigned_url", base + "/put"); JsonArray parts = new JsonArray(); parts.add(part); response.add("parts", parts);
                    } else if (path.endsWith("/files")) response.addProperty("file_info", "LOCAL_FILE_INFO");
                    else if (path.endsWith("/messages")) { response.addProperty("id", "DELIVERED"); delivered.add(req); }
                }
                byte[] out = response.toString().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200,out.length); ex.getResponseBody().write(out); ex.close();
            }); mock.start();
            storage = new FoliaStorage(getDataFolder().toPath().resolve("isolated-db"));
            var repo = new FoliaWhitelistRepository(storage, getLogger()); repo.createOfficialBindingTable();
            var status = new BotStatusService(getLogger(), "127.0.0.1", Bukkit.getPort(), "官Q本地测试", "实际Folia状态卡渲染", "127.0.0.1", getDataFolder(), 140);
            var handler = new BotCommandHandler(getLogger(), new BotDb(getLogger(),repo), storage, repo, status,
                    3,"a-zA-Z0-9_-",16,new long[0],"自定义状态",1000,60,0,0);
            bot = new OfficialQQBot(getLogger(), "LOCAL", "NOT_A_SECRET", base, base, "", repo, null, 6, 10, Set.of(),handler::handle);
            var token = OfficialQQBot.class.getDeclaredField("token"); token.setAccessible(true); token.set(bot,"LOCAL_TOKEN");
            var dispatch = OfficialQQBot.class.getDeclaredMethod("dispatchMessage",JsonObject.class); dispatch.setAccessible(true);
            for (String command : List.of("帮助","服务器状态","自定义状态","看看人机")) {
                JsonObject message = new JsonObject(), author = new JsonObject();
                message.addProperty("id",UUID.randomUUID().toString()); message.addProperty("group_openid","LOCAL_GROUP");
                message.addProperty("content",command); author.addProperty("member_openid","UNVERIFIED_TEST_USER");
                author.addProperty("member_role","member"); message.add("author",author); dispatch.invoke(bot,message);
                JsonObject reply = delivered.poll(20,TimeUnit.SECONDS);
                if (reply == null) throw new AssertionError(command + " 没有HTTP回复");
                if (command.contains("状态")) {
                    if (reply.get("msg_type").getAsInt() != 7 || image == null || image.length != size) throw new AssertionError("未发出图片");
                    var png = ImageIO.read(new java.io.ByteArrayInputStream(image));
                    if (png == null || png.getWidth()!=1500 || png.getHeight()!=700) throw new AssertionError("卡片尺寸异常");
                    Files.write(getDataFolder().toPath().resolve("status-card.png"),image);
                    getLogger().info("SMOKE PASS 实际状态卡PNG 1500x700 bytes=" + image.length);
                } else {
                    String text=reply.get("content").getAsString();
                    if (command.equals("帮助") && (!text.contains("绑定QQ") || !text.contains("人机列表"))) throw new AssertionError("帮助内容不全");
                    if (command.equals("看看人机") && !text.contains("当前没有在线的人机")) throw new AssertionError("人机列表结果不符");
                    getLogger().info("SMOKE PASS " + command);
                }
            }
            result = "PASS: real Folia 26.1.2 + production OfficialQQBot/BotCommandHandler/BotStatusService; help, status PNG 1500x700, custom trigger, bot list; HTTP platform is a local test double, NOT Tencent.\n";
        } catch (Throwable e) { e.printStackTrace(); result="FAIL: " + e + "\n"; }
        finally { if (bot!=null) bot.stop(); if (mock!=null) mock.stop(0); if(storage!=null) storage.close(); }
        try { Files.writeString(getDataFolder().toPath().resolve("result.txt"),result); } catch(Exception e){e.printStackTrace();}
        getLogger().info(result);
        Bukkit.getGlobalRegionScheduler().run(this,t -> Bukkit.shutdown());
    }
}
