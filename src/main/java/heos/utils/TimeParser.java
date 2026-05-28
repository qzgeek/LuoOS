package heos.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for parsing time durations
 */
public class TimeParser {
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d+)([smhdy]?)$", Pattern.CASE_INSENSITIVE);
    
    /**
     * Parses time string to milliseconds
     * Supports: 15 or 15s (seconds), 3m (minutes), 24h (hours), 7d (days), 1y (years)
     * Returns -1 for permanent ban
     * 
     * @param timeStr Time string (e.g., "15s", "3m", "24h", "7d", "1y", "-1")
     * @return Expiry time in milliseconds from now, or -1 for permanent
     */
    public static long parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return -1; // Permanent
        }
        
        timeStr = timeStr.trim();
        
        // Check for -1 (permanent)
        if (timeStr.equals("-1")) {
            return -1;
        }
        
        Matcher matcher = TIME_PATTERN.matcher(timeStr);
        if (!matcher.matches()) {
            return -2; // Invalid format
        }
        
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();
        
        // Default to seconds if no unit specified
        if (unit.isEmpty()) {
            unit = "s";
        }
        
        long milliseconds;
        switch (unit) {
            case "s":
                milliseconds = amount * 1000L;
                break;
            case "m":
                milliseconds = amount * 60L * 1000L;
                break;
            case "h":
                milliseconds = amount * 60L * 60L * 1000L;
                break;
            case "d":
                milliseconds = amount * 24L * 60L * 60L * 1000L;
                break;
            case "y":
                milliseconds = amount * 365L * 24L * 60L * 60L * 1000L;
                break;
            default:
                return -2; // Invalid unit
        }
        
        return System.currentTimeMillis() + milliseconds;
    }
    
    /**
     * Formats expiry time to readable string
     */
    public static String formatExpiryTime(long expiryTime) {
        if (expiryTime == -1) {
            return "姘镐箙 (Permanent)";
        }
        
        long remaining = expiryTime - System.currentTimeMillis();
        if (remaining <= 0) {
            return "宸茶繃鏈?(Expired)";
        }
        
        long seconds = remaining / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        long years = days / 365;
        
        if (years > 0) {
            return years + " 骞?(" + years + " year" + (years > 1 ? "s" : "") + ")";
        } else if (days > 0) {
            return days + " 澶?(" + days + " day" + (days > 1 ? "s" : "") + ")";
        } else if (hours > 0) {
            return hours + " 灏忔椂 (" + hours + " hour" + (hours > 1 ? "s" : "") + ")";
        } else if (minutes > 0) {
            return minutes + " 鍒嗛挓 (" + minutes + " minute" + (minutes > 1 ? "s" : "") + ")";
        } else {
            return seconds + " 绉?(" + seconds + " second" + (seconds > 1 ? "s" : "") + ")";
        }
    }
    
    /**
     * Formats absolute time to readable date string
     */
    public static String formatAbsoluteTime(long expiryTime) {
        if (expiryTime == -1) {
            return "姘镐箙 (Permanent)";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(expiryTime));
    }
}



