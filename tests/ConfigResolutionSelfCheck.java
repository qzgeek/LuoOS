package heos.folia.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.util.List;

/**
 * 只装入隔离测试服：核对每个配置键在新版分组结构下都能真正取到值。
 * 这是针对「配置静默读到硬编码默认值」问题的自检。
 */
public class ConfigResolutionSelfCheck extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            Plugin luoos = getServer().getPluginManager().getPlugin("luoos");
            StringBuilder out = new StringBuilder();
            int checked = 0;
            int unresolved = 0;
            for (var entry : FoliaConfig.aliases().entrySet()) {
                String flat = entry.getKey();
                String grouped = entry.getValue();
                // 运行时字段允许缺失
                if (grouped.endsWith("currentSeed") || grouped.endsWith("nextRefreshTime")) continue;
                boolean existsInFile = luoos.getConfig().contains(grouped) || luoos.getConfig().contains(flat);
                checked++;
                if (!existsInFile) {
                    unresolved++;
                    out.append("  未解析: ").append(flat).append(" -> ").append(grouped).append('\n');
                }
            }
            StringBuilder summary = new StringBuilder();
            summary.append("已检查键: ").append(checked).append('\n');
            summary.append("未解析键: ").append(unresolved).append('\n');
            // 抽查关键项的实际取值
            summary.append("maintenance=").append(FoliaConfig.getBoolean(luoos, "maintenance", false)).append('\n');
            summary.append("enableCustomBan=").append(FoliaConfig.getBoolean(luoos, "enableCustomBan", true)).append('\n');
            summary.append("resourceWorld.refreshHour=").append(FoliaConfig.getInt(luoos, "resourceWorld.refreshHour", 8)).append('\n');
            summary.append("resourceWorld.enabled=").append(FoliaConfig.getBoolean(luoos, "resourceWorld.enabled", false)).append('\n');
            summary.append("usernameLoginFailureLimit=").append(FoliaConfig.getInt(luoos, "usernameLoginFailureLimit", 5)).append('\n');
            summary.append("ipLoginFailureLimit=").append(FoliaConfig.getInt(luoos, "ipLoginFailureLimit", 10)).append('\n');
            summary.append("migrationBanSeconds=").append(FoliaConfig.getInt(luoos, "migrationBanSeconds", 30)).append('\n');
            summary.append("bot.mc_description=").append(FoliaConfig.getString(luoos, "bot.mc_description", "")).append('\n');
            summary.append("official_qq.smtp.host=").append(FoliaConfig.getString(luoos, "official_qq.smtp.host", "")).append('\n');
            summary.append("bot.access_token 非空=").append(!FoliaConfig.getString(luoos, "bot.access_token", "").isBlank()).append('\n');
            getLogger().info("CONFIG SELF CHECK:\n" + summary + (unresolved > 0 ? "明细:\n" + out : ""));
            try {
                Files.writeString(getDataFolder().toPath().resolve("result.txt"), summary.toString() + out.toString());
            } catch (Exception ignored) {
            }
            Bukkit.getGlobalRegionScheduler().run(this, x -> Bukkit.shutdown());
        }, 40);
    }
}
