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

    public BotStatusService(Logger logger, String host, int port, String displayName, String description) {
        this.logger = logger;
        this.displayName = displayName;
        this.description = description;
    }

    record ServerStatus(boolean online, String version, int onlinePlayers, int maxPlayers,
                        String motd, List<String> playerNames, long latency, String error) {}

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

        BotCardRenderer renderer = new BotCardRenderer(1500, 700);

        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D ig = icon.createGraphics();
        ig.setColor(Color.decode("#2C3E50")); ig.fillRect(0, 0, 64, 64);
        ig.setColor(Color.decode("#5D6D7E")); ig.fillOval(4, 4, 56, 56);
        ig.dispose();

        java.util.List<String> bottom = java.util.List.of(
                "查询时间：" + java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19),
                "Write by 黔中极客 / LuoOS");

        BufferedImage background = null;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("background.png")) {
            if (is != null) background = ImageIO.read(is);
        } catch (Exception ignored) {}

        // Collect online player names
        java.util.List<String> playerNames = new ArrayList<>();
        for (org.bukkit.entity.Player p : server.getOnlinePlayers()) {
            playerNames.add(p.getName());
        }

        BufferedImage card = renderer.render(displayName, icon, server.getIp() + ":" + server.getPort(),
                online, ver, description, description, 0, max, bottom, cpu, mem, background, playerNames);

        try { return renderer.toPngBytes(card); }
        catch (Exception e) { logger.warning("Card render failed: " + e.getMessage()); return null; }
    }
}
