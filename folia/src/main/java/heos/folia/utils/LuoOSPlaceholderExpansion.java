package heos.folia.utils;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI expansion for LuoOS player stats.
 *
 * All placeholders prefixed with %luoos_<...>%:
 *
 *   Value:   %luoos_stat_<stat>%, %luoos_stat_<stat>_<time>%
 *   Rank:    %luoos_stat_rank_<stat>%, %luoos_stat_rank_<stat>_<time>%
 *   Top:     %luoos_stat_top_name_<stat>_<N>%, %luoos_stat_top_name_<stat>_<time>_<N>%
 *            %luoos_stat_top_value_<stat>_<N>%, %luoos_stat_top_value_<stat>_<time>_<N>%
 *
 * Time suffix: Nd (days), Nw (weeks), Nm (months), Nq (quarters), Ny (years).
 */
public class LuoOSPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern TIME_SUFFIX = Pattern.compile("_(\\d+)([dwmqy])$");

    private final PlayerStatsTracker tracker;
    private final ResourceWorldManager resourceWorldManager;

    public LuoOSPlaceholderExpansion(PlayerStatsTracker tracker, ResourceWorldManager resourceWorldManager) {
        this.tracker = tracker;
        this.resourceWorldManager = resourceWorldManager;
    }

    @Override public @NotNull String getIdentifier() { return "luoos"; }
    @Override public @NotNull String getAuthor() { return "qzgeek"; }
    @Override public @NotNull String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Resource world refresh countdown
        if (params.equals("resource_refresh")) {
            return formatResourceRefresh();
        }

        // All LuoOS stat placeholders start with "stat_"
        if (!params.startsWith("stat_")) return null;
        String rest = params.substring(5); // strip "stat_"
        UUID uuid = player.getUniqueId();

        // Extract optional time suffix from the END of the whole params
        int days = parseTimeSuffix(params);

        // Rank: %luoos_stat_rank_<stat>%
        if (rest.startsWith("rank_")) {
            String stat = rest.substring(5);
            int td = parseTimeSuffix(stat); // may have time embedded in stat part
            if (td > 0) { stat = stripTimeSuffix(stat); days = td; }
            if (!PlayerStatsTracker.isValidColumn(stat)) return null;
            if (days > 0) {
                var list = tracker.getTopForPeriod(stat, 1000, days);
                int r = 1;
                for (var e : list) { if (e.uuid().equals(uuid)) return String.valueOf(r); r++; }
                return "0";
            }
            return String.valueOf(tracker.getRank(uuid, stat));
        }

        // Top name: %luoos_stat_top_name_<stat>_<N>%
        if (rest.startsWith("top_name_")) {
            return handleTopQuery(rest.substring(9), days, false);
        }

        // Top value: %luoos_stat_top_value_<stat>_<N>%
        if (rest.startsWith("top_value_")) {
            return handleTopQuery(rest.substring(10), days, true);
        }

        // Plain stat value
        String stat = rest;
        int td = parseTimeSuffix(stat);
        if (td > 0) { stat = stripTimeSuffix(stat); days = td; }
        if (!PlayerStatsTracker.isValidColumn(stat)) return null;
        var e = tracker.getStatsForPeriod(uuid, days);
        if (e == null) return "0";
        long val = PlayerStatsTracker.valueOf(e, stat);
        return stat.equals("play_time_seconds") ? PlayerStatsTracker.formatPlayTime(val) : String.valueOf(val);
    }

    // ============ Helpers ============

    /**
     * Format remaining time until next resource world refresh in Chinese.
     * Returns like "12小时30分钟", "5天3小时", "即将刷新", or "未启用".
     */
    private String formatResourceRefresh() {
        if (resourceWorldManager == null) return "未启用";
        long nextRefresh = resourceWorldManager.getNextRefreshTime();
        if (nextRefresh <= 0) return "未启用";

        long now = System.currentTimeMillis();
        long remaining = nextRefresh - now;

        if (remaining <= 0) return "即将刷新";

        long totalSeconds = remaining / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天");
        if (hours > 0) sb.append(hours).append("小时");
        if (days == 0 && hours == 0 && minutes > 0) sb.append(minutes).append("分钟");
        if (days == 0 && hours == 0 && minutes == 0 && totalSeconds > 0) sb.append(totalSeconds).append("秒");

        return sb.length() > 0 ? sb.toString() : "即将刷新";
    }

    private String handleTopQuery(String tail, int days, boolean returnValue) {
        // tail = <stat>_<N>  or  <stat>_<time>_<N>
        // Find the last underscore for rank N
        int lastUnder = tail.lastIndexOf('_');
        if (lastUnder < 0) return null;

        String rankStr = tail.substring(lastUnder + 1);
        int rank;
        try { rank = Integer.parseInt(rankStr); } catch (NumberFormatException e) { return null; }
        if (rank < 1 || rank > 100) return null;

        String statPart = tail.substring(0, lastUnder);
        // statPart may have embedded time: <stat>_<time>
        int td = parseTimeSuffix(statPart);
        String stat = td > 0 ? stripTimeSuffix(statPart) : statPart;
        if (td > 0) days = td;

        if (!PlayerStatsTracker.isValidColumn(stat)) return null;

        var list = days > 0 ? tracker.getTopForPeriod(stat, rank, days) : tracker.getTop(stat, rank);
        if (list.size() < rank) return returnValue ? "0" : "无";

        var entry = list.get(rank - 1);
        if (returnValue) {
            long val = PlayerStatsTracker.valueOf(entry, stat);
            return stat.equals("play_time_seconds") ? PlayerStatsTracker.formatPlayTime(val) : String.valueOf(val);
        }
        return entry.name() != null ? entry.name() : "未知";
    }

    private static int parseTimeSuffix(String s) {
        Matcher m = TIME_SUFFIX.matcher(s);
        if (!m.find()) return 0;
        int num = Integer.parseInt(m.group(1));
        return switch (m.group(2)) {
            case "d" -> num;
            case "w" -> num * 7;
            case "m" -> num * 30;
            case "q" -> num * 90;
            case "y" -> num * 365;
            default -> 0;
        };
    }

    private static String stripTimeSuffix(String s) {
        Matcher m = TIME_SUFFIX.matcher(s);
        return m.find() ? s.substring(0, m.start()) : s;
    }
}
