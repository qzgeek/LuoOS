package heos.folia.event;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Prevents password leakage in server logs by replacing password arguments
 * with masked text before the command is logged.
 */
public final class PasswordMaskListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        final String msg = event.getMessage();
        if (msg == null || msg.isEmpty()) return;

        final String lower = msg.toLowerCase();
        String masked;

        // /los register <password> <confirm>
        if (lower.startsWith("/los register ") || lower.startsWith("/register ")) {
            masked = maskArgs(msg, 2, 2);
        }
        // /los login <password>
        else if (lower.startsWith("/los login ") || lower.startsWith("/login ")) {
            masked = maskArgs(msg, 2, 1);
        }
        // /los changepassword <old> <new>
        else if (lower.startsWith("/los changepassword ") || lower.startsWith("/changepassword ")) {
            masked = maskArgs(msg, 2, 2);
        }
        // /los resetpassword <player> <new>
        else if (lower.startsWith("/los resetpassword ")) {
            masked = maskArgs(msg, 2, 2);
        }
        else {
            return;
        }

        event.setMessage(masked);
    }

    /**
     * Replace the specified number of trailing arguments with masked text.
     * @param raw the raw command message
     * @param startArg 1-based index of the first arg to mask
     * @param count number of args to mask
     */
    private static String maskArgs(String raw, int startArg, int count) {
        String[] parts = raw.split("\\s+", startArg + count + 1);
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(startArg + count, parts.length);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            if (i >= startArg && i < limit) {
                sb.append("***");
            } else {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }
}
