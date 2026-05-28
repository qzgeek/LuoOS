package heos.folia.utils;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class FoliaTpsDisplayService implements Listener {
    private static final int WINDOW_SIZE = 100;

    private final Plugin plugin;
    private final Map<UUID, Integer> activePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Deque<Long> tickIntervals = new ArrayDeque<>();
    private final Deque<Long> tickDurations = new ArrayDeque<>();
    private long lastTickStartIntervalNanos = -1L;
    private long lastTickNanos = -1L;

    public FoliaTpsDisplayService(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> safeTick(), 1L, 1L);
    }

    public void start(Player player) {
        if (!plugin.getConfig().getBoolean("enableAutoLogTps", true)) {
            return;
        }
        players.put(player.getUniqueId(), player);
        activePlayers.put(player.getUniqueId(), Math.max(1, plugin.getConfig().getInt("autoLogTpsDelayTicks", 20)));
    }

    public void stop(Player player) {
        activePlayers.remove(player.getUniqueId());
        players.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.setPlayerListFooter("");
        }
    }

    public void close() {
        for (Player player : players.values()) {
            if (player.isOnline()) {
                player.setPlayerListFooter("");
            }
        }
        activePlayers.clear();
        players.clear();
        HandlerList.unregisterAll(this);
    }

    private void tick() {
        if (activePlayers.isEmpty() || !plugin.getConfig().getBoolean("enableAutoLogTps", true)) {
            return;
        }
        int delay = Math.max(1, plugin.getConfig().getInt("autoLogTpsDelayTicks", 20));
        for (Map.Entry<UUID, Integer> entry : activePlayers.entrySet()) {
            UUID uuid = entry.getKey();
            Player player = players.get(uuid);
            if (player == null || !player.isOnline()) {
                activePlayers.remove(uuid);
                players.remove(uuid);
                continue;
            }

            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft > 0) {
                activePlayers.put(uuid, ticksLeft);
                continue;
            }

            String footer = footer();
            try {
                player.getScheduler().run(plugin, task -> player.setPlayerListFooter(footer), () -> {
                    activePlayers.remove(uuid);
                    players.remove(uuid);
                });
            } catch (RuntimeException exception) {
                activePlayers.remove(uuid);
                players.remove(uuid);
                plugin.getLogger().log(Level.WARNING, "Failed to schedule TPS footer update for " + player.getName(), exception);
                continue;
            }
            activePlayers.put(uuid, delay);
        }
    }

    @EventHandler
    public void onServerTickStart(ServerTickStartEvent event) {
        recordTickStart();
    }

    @EventHandler
    public void onServerTickEnd(ServerTickEndEvent event) {
        recordTickEnd(event.getTickDuration());
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "TPS footer update failed", exception);
        }
    }

    private synchronized void recordTickStart() {
        long now = System.nanoTime();
        if (lastTickStartIntervalNanos > 0L) {
            addSample(tickIntervals, now - lastTickStartIntervalNanos);
        }
        lastTickStartIntervalNanos = now;
        lastTickNanos = now;
    }

    private synchronized void recordTickEnd(double tickDurationMillis) {
        long durationNanos = tickDurationMillis > 0.0D
                ? (long) (tickDurationMillis * 1_000_000.0D)
                : fallbackTickDuration();
        if (durationNanos > 0L) {
            addSample(tickDurations, durationNanos);
        }
    }

    private long fallbackTickDuration() {
        return lastTickNanos > 0L ? System.nanoTime() - lastTickNanos : -1L;
    }

    private static void addSample(Deque<Long> samples, long sample) {
        samples.addLast(sample);
        while (samples.size() > WINDOW_SIZE) {
            samples.removeFirst();
        }
    }

    private String footer() {
        double tps = currentTps();
        double mspt = currentMspt();
        ChatColor color = color(tps, mspt);
        return ChatColor.GRAY + "TPS: " + color + String.format(Locale.ROOT, "%.1f", tps)
                + ChatColor.GRAY + " MSPT: " + color + String.format(Locale.ROOT, "%.1f", mspt);
    }

    private synchronized double currentMspt() {
        if (tickDurations.isEmpty()) {
            return 50.0D;
        }
        return averageNanos(tickDurations) / 1_000_000.0D;
    }

    private synchronized double currentTps() {
        if (tickIntervals.isEmpty()) {
            return 20.0D;
        }
        double intervalMillis = averageNanos(tickIntervals) / 1_000_000.0D;
        if (intervalMillis <= 0.0D) {
            return 20.0D;
        }
        return Math.min(20.0D, 1000.0D / intervalMillis);
    }

    private static double averageNanos(Deque<Long> samples) {
        long totalNanos = 0L;
        for (Long sample : samples) {
            totalNanos += sample;
        }
        return totalNanos / (double) samples.size();
    }

    private static ChatColor color(double tps, double mspt) {
        if (tps < 10.0D || mspt > 60.0D) {
            return ChatColor.RED;
        }
        if (tps < 18.0D || mspt > 50.0D) {
            return ChatColor.YELLOW;
        }
        return ChatColor.GREEN;
    }
}
