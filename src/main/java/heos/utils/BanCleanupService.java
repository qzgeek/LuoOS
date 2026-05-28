package heos.utils;

import heos.Heos;
import net.minecraft.server.MinecraftServer;

/**
 * Periodically removes expired ban records while the server has enough tick headroom.
 */
public final class BanCleanupService {
    private static final long MINUTE_MILLIS = 60_000L;
    private static final long CLEANUP_INTERVAL_MILLIS = 6L * 60L * MINUTE_MILLIS;
    private static final double IDLE_MSPT_THRESHOLD = 40.0D;
    private static long nextCleanupTime = -1L;
    private static long nextIdleRetryTime = -1L;

    private BanCleanupService() {
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (nextCleanupTime < 0L) {
            nextCleanupTime = now + CLEANUP_INTERVAL_MILLIS;
            return;
        }

        if (now < nextCleanupTime) {
            return;
        }

        if (!isIdle()) {
            if (now >= nextIdleRetryTime) {
                nextIdleRetryTime = now + MINUTE_MILLIS;
            }
            return;
        }

        boolean removed = Heos.getBanData().removeExpiredBans();
        if (removed) {
            HeosLogger.info("Cleaned expired ban records during idle maintenance");
        }
        nextCleanupTime = now + CLEANUP_INTERVAL_MILLIS;
        nextIdleRetryTime = -1L;
    }

    private static boolean isIdle() {
        return TpsTracker.currentMspt() <= IDLE_MSPT_THRESHOLD;
    }
}
