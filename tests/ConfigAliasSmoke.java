package heos.folia.bot;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;

/** 只装入全新本地测试服：验证新版分组配置被插件正确读取（双通道可并存）。 */
public class ConfigAliasSmoke extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            StringBuilder out = new StringBuilder();
            var luoos = getServer().getPluginManager().getPlugin("luoos");
            var config = luoos.getConfig();
            var cfg = new java.io.File(luoos.getDataFolder(), "config.yml");
            try {
                var text = Files.readString(cfg.toPath());
                out.append("generated_has_qq_bot=").append(text.contains("qq_bot")).append('\n');
                out.append("generated_has_card_cmd=").append(text.contains("card-cmd")).append('\n');
                out.append("generated_has_groups_key=").append(text.contains("groups:")).append('\n');
            } catch (Exception e) { out.append("read_error=").append(e.getMessage()).append('\n'); }
            out.append("flat_language=").append(config.getString("language", "MISSING")).append('\n');
            out.append("flat_bot_display_name=").append(config.getString("bot.mc_display_name", "MISSING")).append('\n');
            out.append("flat_bot_port=").append(config.getInt("bot.mc_port", -1)).append('\n');
            out.append("flat_official_api_base=").append(config.getString("official_qq.api_base", "MISSING")).append('\n');
            out.append("grouped_private_card_cmd=").append(config.getStringList("qq_bot.private-bot.card-cmd")).append('\n');
            out.append("grouped_official_card_cmd=").append(config.getStringList("qq_bot.official-bot.card-cmd")).append('\n');
            out.append("grouped_official_groups=").append(config.getStringList("qq_bot.official-bot.groups")).append('\n');
            out.append("onebot_enabled=").append(config.getBoolean("bot.enabled", false)).append('\n');
            out.append("official_enabled=").append(config.getBoolean("official_qq.enabled", false)).append('\n');
            out.append("auth_enabled=").append(config.getBoolean("enableAuthentication", false)).append('\n');
            try { Files.writeString(getDataFolder().toPath().resolve("result.txt"), out.toString()); } catch (Exception ignored) {}
            getLogger().info("CONFIG SMOKE:\n" + out);
            Bukkit.getGlobalRegionScheduler().run(this, x -> Bukkit.shutdown());
        }, 40);
    }
}
