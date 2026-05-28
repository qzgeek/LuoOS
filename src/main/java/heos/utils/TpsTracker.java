package heos.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Tracks server TPS and MSPT locally.
 */
public final class TpsTracker {
    private static final int WINDOW_SIZE = 100;
    private static final Deque<Long> TICK_TIMES = new ArrayDeque<>();
    private static long lastTickStartNanos = -1L;

    private TpsTracker() {
    }

    public static void onServerTickStart() {
        lastTickStartNanos = System.nanoTime();
    }

    public static void onServerTickEnd(MinecraftServer server) {
        if (lastTickStartNanos <= 0L) {
            lastTickStartNanos = System.nanoTime();
            return;
        }

        long tickDuration = System.nanoTime() - lastTickStartNanos;
        TICK_TIMES.addLast(tickDuration);
        while (TICK_TIMES.size() > WINDOW_SIZE) {
            TICK_TIMES.removeFirst();
        }
    }

    public static double currentMspt() {
        if (TICK_TIMES.isEmpty()) {
            return 50.0D;
        }
        long totalNanos = 0L;
        for (Long tickTime : TICK_TIMES) {
            totalNanos += tickTime;
        }
        return totalNanos / (double) TICK_TIMES.size() / 1_000_000.0D;
    }

    public static double currentTps() {
        double mspt = currentMspt();
        if (mspt <= 0.0D) {
            return 20.0D;
        }
        return Math.min(20.0D, 1000.0D / mspt);
    }

    public static String formatTps() {
        return String.format("%.2f", currentTps());
    }

    public static String formatMspt() {
        return String.format("%.2f", currentMspt());
    }

    public static String formatStatus() {
        return "TPS: " + formatTps() + " | MSPT: " + formatMspt();
    }

    public static String formatCarpetStatus() {
        return "TPS: " + formatCarpetTps() + " MSPT: " + formatCarpetMspt();
    }

    public static String formatCarpetTps() {
        return String.format(Locale.ROOT, "%.1f", currentTps());
    }

    public static String formatCarpetMspt() {
        return String.format(Locale.ROOT, "%.1f", currentMspt());
    }

    public static ChatFormatting currentStatusColor() {
        double tps = currentTps();
        double mspt = currentMspt();
        if (tps < 10.0D || mspt > 60.0D) {
            return ChatFormatting.RED;
        }
        if (tps < 18.0D || mspt > 50.0D) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.GREEN;
    }

    public static void logStatus(String source) {
        HeosLogger.info(source, formatStatus());
    }
}
