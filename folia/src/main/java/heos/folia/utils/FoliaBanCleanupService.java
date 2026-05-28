package heos.folia.utils;

import heos.folia.storage.FoliaBanData;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public final class FoliaBanCleanupService {
    private static final double IDLE_TPS_THRESHOLD = 19.9D;

    public FoliaBanCleanupService(Plugin plugin, FoliaBanData banData) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (isIdle()) {
                boolean removed = banData.removeExpired();
                if (removed) {
                    plugin.getLogger().info("Cleaned expired ban records during idle maintenance");
                }
            }
        }, 7200L, 7200L);
    }

    private boolean isIdle() {
        try {
            Method method = Bukkit.class.getMethod("getTPS");
            Object value = method.invoke(null);
            if (value instanceof double[] tpsValues && tpsValues.length > 0) {
                return tpsValues[0] >= IDLE_TPS_THRESHOLD;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }
}
