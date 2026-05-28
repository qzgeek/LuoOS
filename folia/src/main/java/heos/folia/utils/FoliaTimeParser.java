package heos.folia.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FoliaTimeParser {
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d+)([smhdy]?)$", Pattern.CASE_INSENSITIVE);

    private FoliaTimeParser() {
    }

    public static long parse(String input) {
        if (input == null || input.isBlank() || input.equals("-1")) {
            return -1L;
        }

        Matcher matcher = TIME_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return -2L;
        }

        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        if (unit.isEmpty()) {
            unit = "s";
        }

        long millis = switch (unit) {
            case "s" -> amount * 1000L;
            case "m" -> amount * 60L * 1000L;
            case "h" -> amount * 60L * 60L * 1000L;
            case "d" -> amount * 24L * 60L * 60L * 1000L;
            case "y" -> amount * 365L * 24L * 60L * 60L * 1000L;
            default -> -2L;
        };
        return millis == -2L ? -2L : System.currentTimeMillis() + millis;
    }

    public static String formatDuration(long expiryTime) {
        if (expiryTime == -1L) {
            return "Permanent";
        }
        long remaining = expiryTime - System.currentTimeMillis();
        if (remaining <= 0L) {
            return "Expired";
        }
        long seconds = remaining / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        long years = days / 365L;
        if (years > 0L) {
            return years + " year" + (years == 1L ? "" : "s");
        }
        if (days > 0L) {
            return days + " day" + (days == 1L ? "" : "s");
        }
        if (hours > 0L) {
            return hours + " hour" + (hours == 1L ? "" : "s");
        }
        if (minutes > 0L) {
            return minutes + " minute" + (minutes == 1L ? "" : "s");
        }
        return seconds + " second" + (seconds == 1L ? "" : "s");
    }

    public static String formatAbsolute(long expiryTime) {
        if (expiryTime == -1L) {
            return "Permanent";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(expiryTime));
    }
}
