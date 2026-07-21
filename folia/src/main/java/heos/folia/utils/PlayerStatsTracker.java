package heos.folia.utils;

import heos.folia.storage.FoliaStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Player statistics tracker with PAPI support and leaderboard.
 * Tracks: play time, blocks mined, blocks placed, chat characters.
 */
public class PlayerStatsTracker {
    private final Plugin plugin;
    private final Logger logger;
    private final FoliaStorage storage;
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public PlayerStatsTracker(Plugin plugin, FoliaStorage storage) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storage = storage;
        createTable();
    }

    private void createTable() {
        try {
            var conn = storage.getConnection();
            // Aggregate stats (all-time)
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
            // Daily stats (by date for time-range queries)
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
        } catch (Exception e) {
            logger.warning("[Stats] Failed to create tables: " + e.getMessage());
        }
    }

    // =========== Session tracking ===========

    public void onJoin(Player player) {
        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onQuit(Player player) {
        Long start = sessionStart.remove(player.getUniqueId());
        if (start == null) return;
        long seconds = (System.currentTimeMillis() - start) / 1000;
        if (seconds <= 0) return;
        addStat(player, "play_time_seconds", seconds);
        updateName(player);
    }

    // =========== Block/chat tracking ===========

    public void onBlockBreak(Player player) {
        addStat(player, "blocks_mined", 1);
    }

    public void onBlockPlace(Player player) {
        addStat(player, "blocks_placed", 1);
    }

    public void onChat(Player player, String message) {
        addStat(player, "chat_chars", message.length());
    }

    public void onEntityKill(Player player) {
        addStat(player, "entities_killed", 1);
    }

    // =========== Database ops ===========

    private void addStat(Player player, String column, long delta) {
        UUID uuid = player.getUniqueId();
        String today = java.time.LocalDate.now().toString();
        String name = player.getName();
        long now = System.currentTimeMillis();
        try {
            var conn = storage.getConnection();
            synchronized (conn) {
                String sql = "INSERT INTO player_stats (uuid, " + column
                    + ", play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_seen_name, last_updated) "
                    + "VALUES (?1, ?2, 0, 0, 0, 0, 0, ?3, ?4) "
                    + "ON CONFLICT(uuid) DO UPDATE SET " + column + " = " + column + " + ?2, last_seen_name = ?3, last_updated = ?4";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setString(1, uuid.toString());
                ps.setLong(2, delta);
                ps.setString(3, name);
                ps.setLong(4, now);
                ps.executeUpdate();
                ps.close();

                String dsql = "INSERT INTO player_stats_daily (uuid, date, " + column
                    + ", play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed) "
                    + "VALUES (?1, ?2, ?3, 0, 0, 0, 0, 0) "
                    + "ON CONFLICT(uuid, date) DO UPDATE SET " + column + " = " + column + " + ?3";
                PreparedStatement dps = conn.prepareStatement(dsql);
                dps.setString(1, uuid.toString());
                dps.setString(2, today);
                dps.setLong(3, delta);
                dps.executeUpdate();
                dps.close();
            }
        } catch (Exception e) {
            logger.warning("[Stats] Failed to update " + column + " for " + name + ": " + e.getMessage());
        }
    }

    private void updateName(Player player) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_stats SET last_seen_name = ?, last_updated = ? WHERE uuid = ?"
            );
            ps.setString(1, player.getName());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, player.getUniqueId().toString());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // =========== Query ===========

    public record StatsEntry(String name, UUID uuid, long playTime, long blocksMined,
                             long blocksPlaced, long chatChars, long entitiesKilled, long lastUpdated) {}

    public StatsEntry getStats(UUID uuid) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT last_seen_name, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_updated FROM player_stats WHERE uuid = ?"
            );
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new StatsEntry(rs.getString("last_seen_name"), uuid,
                    rs.getLong("play_time_seconds"), rs.getLong("blocks_mined"),
                    rs.getLong("blocks_placed"), rs.getLong("chat_chars"),
                    rs.getLong("entities_killed"), rs.getLong("last_updated"));
            }
        } catch (Exception e) {
            logger.warning("[Stats] Query failed: " + e.getMessage());
        }
        return null;
    }

    /** Top N players by a stat column. */
    public List<StatsEntry> getTop(String column, int limit) {
        List<StatsEntry> list = new ArrayList<>();
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, last_seen_name, play_time_seconds, blocks_mined, blocks_placed, chat_chars, entities_killed, last_updated " +
                "FROM player_stats ORDER BY " + column + " DESC LIMIT ?"
            );
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new StatsEntry(
                    rs.getString("last_seen_name"),
                    UUID.fromString(rs.getString("uuid")),
                    rs.getLong("play_time_seconds"), rs.getLong("blocks_mined"),
                    rs.getLong("blocks_placed"), rs.getLong("chat_chars"),
                    rs.getLong("entities_killed"), rs.getLong("last_updated")));
            }
        } catch (Exception e) {
            logger.warning("[Stats] Top query failed: " + e.getMessage());
        }
        return list;
    }

    /** Player's rank in a stat (1-based). Returns 0 if not found. */
    public int getRank(UUID uuid, String column) {
        try {
            var conn = storage.getConnection();
            // First get the player's value
            PreparedStatement ps = conn.prepareStatement(
                "SELECT " + column + " FROM player_stats WHERE uuid = ?"
            );
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return 0;
            long value = rs.getLong(1);

            // Count how many have higher
            ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM player_stats WHERE " + column + " > ?"
            );
            ps.setLong(1, value);
            rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) + 1;
        } catch (Exception e) {
            logger.warning("[Stats] Rank query failed: " + e.getMessage());
        }
        return 0;
    }

    /** Format seconds to readable string. */
    public static String formatPlayTime(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "小时" + (m > 0 ? m + "分" : "");
    }

    /** Get stat value by column name. */
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

    /** Get Chinese label for stat column. */
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

    /** Validate stat column name. */
    public static boolean isValidColumn(String col) {
        return switch (col) {
            case "play_time_seconds", "blocks_mined", "blocks_placed", "chat_chars", "entities_killed" -> true;
            default -> false;
        };
    }

    /** Get stats for a specific time range using daily table. Days=0 means all-time. */
    public StatsEntry getStatsForPeriod(UUID uuid, int days) {
        if (days <= 0) return getStats(uuid);
        try {
            var conn = storage.getConnection();
            String today = java.time.LocalDate.now().toString();
            String since = java.time.LocalDate.now().minusDays(days).toString();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT SUM(play_time_seconds) as pt, SUM(blocks_mined) as bm, " +
                "SUM(blocks_placed) as bp, SUM(chat_chars) as cc, SUM(entities_killed) as ek " +
                "FROM player_stats_daily WHERE uuid = ? AND date >= ? AND date <= ?"
            );
            ps.setString(1, uuid.toString());
            ps.setString(2, since);
            ps.setString(3, today);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new StatsEntry(null, uuid,
                    rs.getLong("pt"), rs.getLong("bm"), rs.getLong("bp"),
                    rs.getLong("cc"), rs.getLong("ek"), System.currentTimeMillis());
            }
        } catch (Exception e) {
            logger.warning("[Stats] Period query failed: " + e.getMessage());
        }
        return null;
    }

    /** Top N for a time range. */
    public List<StatsEntry> getTopForPeriod(String column, int limit, int days) {
        if (days <= 0) return getTop(column, limit);
        List<StatsEntry> list = new ArrayList<>();
        try {
            var conn = storage.getConnection();
            String since = java.time.LocalDate.now().minusDays(days).toString();
            String today = java.time.LocalDate.now().toString();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, SUM(" + column + ") as total FROM player_stats_daily " +
                "WHERE date >= ? AND date <= ? GROUP BY uuid ORDER BY total DESC LIMIT ?"
            );
            ps.setString(1, since);
            ps.setString(2, today);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
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
        } catch (Exception e) {
            logger.warning("[Stats] Top period query failed: " + e.getMessage());
        }
        return list;
    }
}
