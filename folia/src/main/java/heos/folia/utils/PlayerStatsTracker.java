package heos.folia.utils;

import heos.folia.storage.FoliaStorage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Player statistics tracker with PAPI support and leaderboard.
 * Tracks: play time, blocks mined, blocks placed, chat characters, entities killed.
 *
 * THREAD SAFETY (rewritten for async+batching):
 * - All stat writes are ACCUMULATED in memory on the calling thread (fast, lock-free).
 * - Flush to SQLite runs on the dedicated LuoOS-DB-Writer thread via
 *   {@code storage.submitWrite()}, never on a Folia region thread.
 * - Read queries ({@code getStats}, {@code getTop}, {@code getRank}) still use
 *   {@code synchronized(storage)} because they need a consistent snapshot.
 */
public class PlayerStatsTracker {
    private final Plugin plugin;
    private final Logger logger;
    private final FoliaStorage storage;
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    // --- Async batching ---
    /** Per-player accumulated stat deltas not yet flushed to DB. */
    private final Map<UUID, StatAccum> pending = new ConcurrentHashMap<>();
    /** Flush interval in milliseconds. */
    private static final long FLUSH_INTERVAL_MS = 30_000;
    /** Last flush timestamp (volatile — writer thread reads, any thread writes). */
    private volatile long lastFlushMs;

    public PlayerStatsTracker(Plugin plugin, FoliaStorage storage) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storage = storage;
        this.lastFlushMs = System.currentTimeMillis();
        createTable();
    }

    private void createTable() {
        try {
            synchronized (storage) {
                var conn = storage.getConnection();
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        play_time_seconds BIGINT DEFAULT 0,
                        blocks_mined BIGINT DEFAULT 0,
                        blocks_placed BIGINT DEFAULT 0,
                        chat_chars BIGINT DEFAULT 0,
                        entities_killed BIGINT DEFAULT 0,
                        last_seen_name TEXT,
                        last_updated BIGINT
                    )
                """);
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS player_stats_daily (
                        uuid TEXT,
                        date TEXT,
                        play_time_seconds BIGINT DEFAULT 0,
                        blocks_mined BIGINT DEFAULT 0,
                        blocks_placed BIGINT DEFAULT 0,
                        chat_chars BIGINT DEFAULT 0,
                        entities_killed BIGINT DEFAULT 0,
                        PRIMARY KEY (uuid, date)
                    )
                """);
            }
        } catch (Exception e) {
            logger.warning("[Stats] Failed to create tables: " + e.getMessage());
        }
    }

    // =========== Session tracking ===========

    public void onJoin(Player player) {
        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        Long start = sessionStart.remove(uuid);
        if (start != null) {
            long seconds = (System.currentTimeMillis() - start) / 1000;
            if (seconds > 0) {
                addStat(uuid, player.getName(), "play_time_seconds", seconds);
            }
        }
        // Force flush this player's pending data immediately on quit
        flushPlayer(uuid);
    }

    // =========== Block/chat tracking (fast, no I/O) ===========

    public void onBlockBreak(Player player) {
        addStat(player.getUniqueId(), player.getName(), "blocks_mined", 1);
    }

    public void onBlockPlace(Player player) {
        addStat(player.getUniqueId(), player.getName(), "blocks_placed", 1);
    }

    public void onChat(Player player, String message) {
        addStat(player.getUniqueId(), player.getName(), "chat_chars", message.length());
    }

    public void onEntityKill(Player player) {
        addStat(player.getUniqueId(), player.getName(), "entities_killed", 1);
    }

    // =========== Async batching core ===========

    /**
     * Accumulate a stat delta in memory. Fast — no DB I/O.
     * Triggers a periodic flush to DB on the writer thread.
     */
    private void addStat(UUID uuid, String name, String column, long delta) {
        pending.compute(uuid, (k, v) -> {
            StatAccum a = (v != null) ? v : new StatAccum();
            a.name = name;
            switch (column) {
                case "play_time_seconds" -> a.playTime += delta;
                case "blocks_mined"     -> a.blocksMined += delta;
                case "blocks_placed"    -> a.blocksPlaced += delta;
                case "chat_chars"       -> a.chatChars += delta;
                case "entities_killed"  -> a.entitiesKilled += delta;
            }
            return a;
        });

        // Trigger flush if interval has passed (non-blocking check)
        long now = System.currentTimeMillis();
        if (now - lastFlushMs > FLUSH_INTERVAL_MS) {
            scheduleFlush();
        }
    }

    /** Schedule a flush on the dedicated writer thread (non-blocking). */
    private void scheduleFlush() {
        lastFlushMs = System.currentTimeMillis();
        storage.submitWrite(this::flushAll);
    }

    /** Flush ALL pending stat deltas to DB in batch. Runs on writer thread. */
    private void flushAll() {
        if (pending.isEmpty()) return;

        // Snapshot and clear
        Map<UUID, StatAccum> batch = new HashMap<>(pending);
        pending.clear();

        try {
            synchronized (storage) {
                var conn = storage.getConnection();
                String today = java.time.LocalDate.now().toString();

                for (var entry : batch.entrySet()) {
                    UUID uuid = entry.getKey();
                    StatAccum a = entry.getValue();
                    long now = System.currentTimeMillis();

                    // Aggregate table — one UPSERT per player
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_stats (uuid, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_seen_name, last_updated) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) "
                        + "ON CONFLICT(uuid) DO UPDATE SET "
                        + "play_time_seconds = play_time_seconds + ?2, "
                        + "blocks_mined = blocks_mined + ?3, "
                        + "blocks_placed = blocks_placed + ?4, "
                        + "chat_chars = chat_chars + ?5, "
                        + "entities_killed = entities_killed + ?6, "
                        + "last_seen_name = ?7, last_updated = ?8"
                    )) {
                        ps.setString(1, uuid.toString());
                        ps.setLong(2, a.playTime);
                        ps.setLong(3, a.blocksMined);
                        ps.setLong(4, a.blocksPlaced);
                        ps.setLong(5, a.chatChars);
                        ps.setLong(6, a.entitiesKilled);
                        ps.setString(7, a.name);
                        ps.setLong(8, now);
                        ps.executeUpdate();
                    }

                    // Daily table — one UPSERT per player
                    try (PreparedStatement dps = conn.prepareStatement(
                        "INSERT INTO player_stats_daily (uuid, date, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) "
                        + "ON CONFLICT(uuid, date) DO UPDATE SET "
                        + "play_time_seconds = play_time_seconds + ?3, "
                        + "blocks_mined = blocks_mined + ?4, "
                        + "blocks_placed = blocks_placed + ?5, "
                        + "chat_chars = chat_chars + ?6, "
                        + "entities_killed = entities_killed + ?7"
                    )) {
                        dps.setString(1, uuid.toString());
                        dps.setString(2, today);
                        dps.setLong(3, a.playTime);
                        dps.setLong(4, a.blocksMined);
                        dps.setLong(5, a.blocksPlaced);
                        dps.setLong(6, a.chatChars);
                        dps.setLong(7, a.entitiesKilled);
                        dps.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Flush failed: " + e.getMessage());
            // Re-merge failed batch back into pending to avoid data loss
            for (var entry : batch.entrySet()) {
                pending.merge(entry.getKey(), entry.getValue(), StatAccum::merge);
            }
        }
    }

    /** Immediately flush a single player's stats (used on quit). */
    private void flushPlayer(UUID uuid) {
        storage.submitWrite(() -> {
            StatAccum a = pending.remove(uuid);
            if (a == null) return;
            try {
                synchronized (storage) {
                    var conn = storage.getConnection();
                    String today = java.time.LocalDate.now().toString();
                    long now = System.currentTimeMillis();

                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_stats (uuid, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_seen_name, last_updated) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) "
                        + "ON CONFLICT(uuid) DO UPDATE SET "
                        + "play_time_seconds = play_time_seconds + ?2, "
                        + "blocks_mined = blocks_mined + ?3, "
                        + "blocks_placed = blocks_placed + ?4, "
                        + "chat_chars = chat_chars + ?5, "
                        + "entities_killed = entities_killed + ?6, "
                        + "last_seen_name = ?7, last_updated = ?8"
                    )) {
                        ps.setString(1, uuid.toString());
                        ps.setLong(2, a.playTime);
                        ps.setLong(3, a.blocksMined);
                        ps.setLong(4, a.blocksPlaced);
                        ps.setLong(5, a.chatChars);
                        ps.setLong(6, a.entitiesKilled);
                        ps.setString(7, a.name);
                        ps.setLong(8, now);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement dps = conn.prepareStatement(
                        "INSERT INTO player_stats_daily (uuid, date, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) "
                        + "ON CONFLICT(uuid, date) DO UPDATE SET "
                        + "play_time_seconds = play_time_seconds + ?3, "
                        + "blocks_mined = blocks_mined + ?4, "
                        + "blocks_placed = blocks_placed + ?5, "
                        + "chat_chars = chat_chars + ?6, "
                        + "entities_killed = entities_killed + ?7"
                    )) {
                        dps.setString(1, uuid.toString());
                        dps.setString(2, today);
                        dps.setLong(3, a.playTime);
                        dps.setLong(4, a.blocksMined);
                        dps.setLong(5, a.blocksPlaced);
                        dps.setLong(6, a.chatChars);
                        dps.setLong(7, a.entitiesKilled);
                        dps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                logger.warning("[Stats] Flush player " + uuid + " failed: " + e.getMessage());
                // Put back for later retry
                pending.merge(uuid, a, StatAccum::merge);
            }
        });
    }

    // =========== In-memory accumulator ===========

    private static class StatAccum {
        String name;
        long playTime;
        long blocksMined;
        long blocksPlaced;
        long chatChars;
        long entitiesKilled;

        StatAccum merge(StatAccum other) {
            this.playTime += other.playTime;
            this.blocksMined += other.blocksMined;
            this.blocksPlaced += other.blocksPlaced;
            this.chatChars += other.chatChars;
            this.entitiesKilled += other.entitiesKilled;
            if (other.name != null) this.name = other.name;
            return this;
        }
    }

    // =========== Query (unchanged — reads still need synchronized snapshot) ===========

    public record StatsEntry(String name, UUID uuid, long playTime, long blocksMined,
                             long blocksPlaced, long chatChars, long entitiesKilled, long lastUpdated) {}

    public StatsEntry getStats(UUID uuid) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                    "SELECT last_seen_name, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_updated FROM player_stats WHERE uuid = ?"
                )) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new StatsEntry(rs.getString("last_seen_name"), uuid,
                                rs.getLong("play_time_seconds"), rs.getLong("blocks_mined"),
                                rs.getLong("blocks_placed"), rs.getLong("chat_chars"),
                                rs.getLong("entities_killed"), rs.getLong("last_updated"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Query failed: " + e.getMessage());
        }
        return null;
    }

    public List<StatsEntry> getTop(String column, int limit) {
        List<StatsEntry> list = new ArrayList<>();
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                    "SELECT uuid, last_seen_name, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_updated " +
                    "FROM player_stats ORDER BY " + column + " DESC LIMIT ?"
                )) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new StatsEntry(
                                rs.getString("last_seen_name"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getLong("play_time_seconds"), rs.getLong("blocks_mined"),
                                rs.getLong("blocks_placed"), rs.getLong("chat_chars"),
                                rs.getLong("entities_killed"), rs.getLong("last_updated")));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Top query failed: " + e.getMessage());
        }
        return list;
    }

    public int getRank(UUID uuid, String column) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                    "SELECT " + column + " FROM player_stats WHERE uuid = ?"
                )) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return 0;
                        long value = rs.getLong(1);
                        try (PreparedStatement ps2 = storage.getConnection().prepareStatement(
                            "SELECT COUNT(*) FROM player_stats WHERE " + column + " > ?"
                        )) {
                            ps2.setLong(1, value);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                if (rs2.next()) return rs2.getInt(1) + 1;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Rank query failed: " + e.getMessage());
        }
        return 0;
    }

    public static String formatPlayTime(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "小时" + (m > 0 ? m + "分" : "");
    }

    public static long valueOf(StatsEntry e, String column) {
        return switch (column) {
            case "play_time_seconds" -> e.playTime;
            case "blocks_mined" -> e.blocksMined;
            case "blocks_placed" -> e.blocksPlaced;
            case "chat_chars" -> e.chatChars;
            case "entities_killed" -> e.entitiesKilled;
            default -> 0;
        };
    }

    public static String labelOf(String column) {
        return switch (column) {
            case "play_time_seconds" -> "在线时长";
            case "blocks_mined" -> "挖掘方块";
            case "blocks_placed" -> "放置方块";
            case "chat_chars" -> "聊天字数";
            case "entities_killed" -> "击杀实体";
            default -> column;
        };
    }

    public static boolean isValidColumn(String col) {
        return switch (col) {
            case "play_time_seconds", "blocks_mined", "blocks_placed", "chat_chars", "entities_killed" -> true;
            default -> false;
        };
    }

    public StatsEntry getStatsForPeriod(UUID uuid, int days) {
        if (days <= 0) return getStats(uuid);
        try {
            synchronized (storage) {
                String today = java.time.LocalDate.now().toString();
                String since = java.time.LocalDate.now().minusDays(days).toString();
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                    "SELECT SUM(play_time_seconds) as pt, SUM(blocks_mined) as bm, " +
                    "SUM(blocks_placed) as bp, SUM(chat_chars) as cc, SUM(entities_killed) as ek " +
                    "FROM player_stats_daily WHERE uuid = ? AND date >= ? AND date <= ?"
                )) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, since);
                    ps.setString(3, today);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new StatsEntry(null, uuid,
                                rs.getLong("pt"), rs.getLong("bm"), rs.getLong("bp"),
                                rs.getLong("cc"), rs.getLong("ek"), System.currentTimeMillis());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Period query failed: " + e.getMessage());
        }
        return null;
    }

    public List<StatsEntry> getTopForPeriod(String column, int limit, int days) {
        if (days <= 0) return getTop(column, limit);
        List<StatsEntry> list = new ArrayList<>();
        try {
            synchronized (storage) {
                String since = java.time.LocalDate.now().minusDays(days).toString();
                String today = java.time.LocalDate.now().toString();
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                    "SELECT uuid, SUM(" + column + ") as total FROM player_stats_daily " +
                    "WHERE date >= ? AND date <= ? GROUP BY uuid ORDER BY total DESC LIMIT ?"
                )) {
                    ps.setString(1, since);
                    ps.setString(2, today);
                    ps.setInt(3, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID uid = UUID.fromString(rs.getString("uuid"));
                            long val = rs.getLong("total");
                            var agg = getStats(uid);
                            String name = agg != null ? agg.name() : "未知";
                            list.add(new StatsEntry(name, uid,
                                column.equals("play_time_seconds") ? val : 0,
                                column.equals("blocks_mined") ? val : 0,
                                column.equals("blocks_placed") ? val : 0,
                                column.equals("chat_chars") ? val : 0,
                                column.equals("entities_killed") ? val : 0,
                                System.currentTimeMillis()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[Stats] Top period query failed: " + e.getMessage());
        }
        return list;
    }
}
