package heos.folia.bot;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Minecraft server status service — direct Bukkit API, no network ping.
 * Runs in-process so there is no reason to ping via socket.
 */
public class BotStatusService {
    private final Logger logger;
    private final String displayName;
    private final String description;
    private final String displayIp;
    private final java.io.File dataFolder;
    private final int bgMaskAlpha;
    private final String mcHost;
    private final int mcPort;

    public BotStatusService(Logger logger, String host, int port, String displayName, String description,
                            String displayIp, java.io.File dataFolder, int bgMaskAlpha) {
        this.logger = logger;
        this.displayName = displayName;
        this.description = description;
        this.displayIp = displayIp;
        this.dataFolder = dataFolder;
        this.bgMaskAlpha = bgMaskAlpha;
        this.mcHost = host;
        this.mcPort = port;
    }

    record ServerStatus(boolean online, String version, int onlinePlayers, int maxPlayers,
                        String motd, List<String> playerNames, long latency, String error) {}  // unused fields kept for compat
    record LocalPingResult(BufferedImage icon, String description) {}

    /** Get server status directly via Bukkit API — always online since we're in-process. */
    public ServerStatus ping() {
        try {
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            String version = server.getMinecraftVersion();
            int online = server.getOnlinePlayers().size();
            int max = server.getMaxPlayers();
            String motd = server.getMotd();

            List<String> names = new ArrayList<>();
            for (org.bukkit.entity.Player p : server.getOnlinePlayers()) {
                names.add(p.getName());
                if (names.size() >= 20) break;
            }

            return new ServerStatus(true, version, online, max, motd, names, 0, "");
        } catch (Exception e) {
            return new ServerStatus(false, "", 0, 0, "", List.of(), -1, e.getMessage());
        }
    }

    public String formatStatusText(ServerStatus info, Double cpu, Double mem) {
        if (!info.online) return "❌ 服务器离线: " + (info.error.isEmpty() ? "未知原因" : info.error);

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(displayName).append(" ===\n");
        sb.append("🎮 版本: ").append(info.version).append("\n");
        sb.append("👥 在线: ").append(info.onlinePlayers).append("/").append(info.maxPlayers).append("\n");
        if (!info.motd.isEmpty()) sb.append("📝 MOTD: ").append(info.motd).append("\n");
        if (cpu != null) sb.append("💻 CPU: ").append(String.format("%.1f%%", cpu)).append("\n");
        if (mem != null) sb.append("🧠 内存: ").append(String.format("%.1f%%", mem)).append("\n");
        if (!info.playerNames.isEmpty()) {
            sb.append("👤 在线玩家: ");
            int show = Math.min(info.playerNames.size(), 15);
            for (int i = 0; i < show; i++) {
                sb.append(info.playerNames.get(i));
                if (i < show - 1) sb.append(", ");
            }
            if (info.playerNames.size() > 15) sb.append(" ...等").append(info.playerNames.size()).append("人");
            sb.append("\n");
        }
        sb.append("\nWrite by 黔中极客 / LuoOS");
        return sb.toString();
    }

    public byte[] renderLocal() {
        Runtime rt = Runtime.getRuntime();
        double mem = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
        double cpu = -1;

        org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        String ver = server.getMinecraftVersion();
        int online = server.getOnlinePlayers().size();
        int max = server.getMaxPlayers();

        BotCardRenderer renderer = new BotCardRenderer(1500, 700, bgMaskAlpha);

        // Ping local server MOTD to get the icon (supports icons set by other plugins)
        LocalPingResult pingResult = pingLocal();
        BufferedImage icon = null;
        if (pingResult != null && pingResult.icon != null) {
            icon = pingResult.icon;
        }
        if (icon == null) {
            icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            Graphics2D ig = icon.createGraphics();
            ig.setColor(Color.decode("#2C3E50")); ig.fillRect(0, 0, 64, 64);
            ig.setColor(Color.decode("#5D6D7E")); ig.fillOval(4, 4, 56, 56);
            ig.dispose();
        }

        java.util.List<String> bottom = java.util.List.of(
                "查询时间：" + java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19),
                "Write by 黔中极客 / LuoOS");

        BufferedImage background = loadBackground();
        // If no custom background, try default embedded
        if (background == null) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("background.png")) {
                if (is != null) background = ImageIO.read(is);
            } catch (Exception ignored) {}
        }

        // Collect online player names
        java.util.List<String> playerNames = new ArrayList<>();
        for (org.bukkit.entity.Player p : server.getOnlinePlayers()) {
            playerNames.add(p.getName());
        }

        BufferedImage card = renderer.render(displayName, icon, displayIp,
                0, ver, description, description, online, max, bottom, cpu, mem, background, playerNames);

        try { return renderer.toPngBytes(card); }
        catch (Exception e) { logger.warning("Card render failed: " + e.getMessage()); return null; }
    }

    /** Load first image from plugins/luoos/img/ directory, auto-create if missing. */
    private BufferedImage loadBackground() {
        java.io.File imgDir = new java.io.File(dataFolder, "img");
        if (!imgDir.exists()) imgDir.mkdirs();
        if (!imgDir.isDirectory()) return null;
        java.io.File[] files = imgDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png") || name.toLowerCase().endsWith(".jpg")
                        || name.toLowerCase().endsWith(".jpeg"));
        if (files == null || files.length == 0) return null;
        try {
            // Pick a random image on each request
            return ImageIO.read(files[new java.util.Random().nextInt(files.length)]);
        } catch (Exception e) {
            logger.warning("Failed to load background: " + e.getMessage());
            return null;
        }
    }

    /** Connect to localhost MC server and extract icon from MOTD handshake. */
    private LocalPingResult pingLocal() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(mcHost, mcPort), 3000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());

            // Send handshake + status request
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(buf);
            dos.writeByte(0x00); // packet ID
            writeVarInt(dos, 767); // protocol version
            writeVarInt(dos, mcHost.length());
            dos.writeBytes(mcHost);
            dos.writeShort(mcPort);
            writeVarInt(dos, 1); // next state: status
            writePacket(out, buf.toByteArray());

            buf.reset();
            writeVarInt(dos, 0x00); // status request
            writePacket(out, buf.toByteArray());

            // Read response
            int len = readVarInt(in);
            int packetId = readVarInt(in);
            if (packetId != 0x00) return null;
            int jsonLen = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLen];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            // Extract icon
            BufferedImage icon = null;
            if (root.has("favicon")) {
                String favicon = root.get("favicon").getAsString();
                if (favicon.startsWith("data:image/png;base64,")) {
                    byte[] imgBytes = java.util.Base64.getDecoder().decode(favicon.substring(22));
                    icon = ImageIO.read(new java.io.ByteArrayInputStream(imgBytes));
                }
            }
            return new LocalPingResult(icon, "");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeVarInt(java.io.DataOutputStream dos, int value) throws java.io.IOException {
        while ((value & 0xFFFFFF80) != 0) { dos.writeByte((value & 0x7F) | 0x80); value >>>= 7; }
        dos.writeByte(value & 0x7F);
    }

    private static int readVarInt(java.io.DataInputStream in) throws java.io.IOException {
        int value = 0, shift = 0;
        byte b;
        do { b = in.readByte(); value |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
        return value;
    }

    private static void writePacket(java.io.OutputStream out, byte[] data) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(buf);
        writeVarInt(dos, data.length);
        dos.write(data);
        out.write(buf.toByteArray());
        out.flush();
    }
}
