package heos.folia.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.rewrite.RewriteAppender;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class FoliaLogFilterService {
    private static final Filter HEOS_LOG_FILTER = new YggdrasilPublicKeyFilter();
    private static boolean installed;

    private FoliaLogFilterService() {
    }

    public static void installConfiguredFilters(Plugin plugin) {
        if (installed) {
            return;
        }

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig rootLogger = configuration.getRootLogger();
        rootLogger.addFilter(HEOS_LOG_FILTER);

        // Install password masking on all appenders
        installPasswordMasking(context, configuration, rootLogger, plugin);

        context.updateLoggers();
        installed = true;
        plugin.getLogger().info("Log filters enabled (Yggdrasil noise + password masking)");
    }

    /**
     * Wrap all existing root-logger appenders in a RewriteAppender that redacts
     * password arguments from auth-command log lines (login, register, changepassword,
     * resetpassword and their aliases).
     */
    private static void installPasswordMasking(LoggerContext ctx, Configuration cfg,
                                                LoggerConfig rootLogger, Plugin plugin) {
        Map<String, Appender> appenders = rootLogger.getAppenders();
        if (appenders.isEmpty()) return;

        List<AppenderRef> refs = new ArrayList<>();
        for (Map.Entry<String, Appender> entry : appenders.entrySet()) {
            // Skip self to avoid infinite recursion
            if ("PasswordMask".equals(entry.getKey())) continue;
            refs.add(AppenderRef.createAppenderRef(entry.getKey(), null, null));
        }
        if (refs.isEmpty()) return;

        RewriteAppender rewrite = RewriteAppender.createAppender(
                "PasswordMask",        // name
                "true",                // ignoreExceptions
                refs.toArray(new AppenderRef[0]),
                cfg,
                new PasswordMaskRewritePolicy(),
                null                   // filter
        );
        rewrite.start();

        // Replace original appenders with the rewrite wrapper
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Appender> entry : appenders.entrySet()) {
            if (!"PasswordMask".equals(entry.getKey())) {
                toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            rootLogger.removeAppender(name);
        }
        rootLogger.addAppender(rewrite, null, null);

        plugin.getLogger().fine("[LogFilter] Password masking rewrite installed.");
    }

    // ==================== rewrite policy ====================

    /**
     * Rewrites log messages that contain auth commands so that password
     * arguments are replaced with {@code ***} before the message hits the
     * console / log file.
     * <p>
     * Patterns cover both bare commands ({@code /register}) and {@code /los}
     * sub-commands ({@code /los register}) as well as aliases ({@code /reg},
     * {@code /l}, {@code /changepw}).
     */
    private static final class PasswordMaskRewritePolicy implements RewritePolicy {

        // /login <pwd>  and  /los login <pwd>  (aliases: /l)
        private static final Pattern LOGIN = Pattern.compile(
                "(issued server command: /(?:los )?(?:l|login) )(\\S+)",
                Pattern.CASE_INSENSITIVE);

        // /register <pwd> <confirm>  and  /los register <pwd> <confirm>  (aliases: /reg)
        private static final Pattern REGISTER = Pattern.compile(
                "(issued server command: /(?:los )?(?:reg|register) )(\\S+)(\\s+)(\\S+)",
                Pattern.CASE_INSENSITIVE);

        // /changepassword <old> <new>  and  /los changepassword <old> <new>  (aliases: /changepw)
        private static final Pattern CHANGEPW = Pattern.compile(
                "(issued server command: /(?:los )?(?:changepw|changepassword) )(\\S+)(\\s+)(\\S+)",
                Pattern.CASE_INSENSITIVE);

        // /los resetpassword <player> <new>
        private static final Pattern RESETPW = Pattern.compile(
                "(issued server command: /los resetpassword \\S+ )(\\S+)",
                Pattern.CASE_INSENSITIVE);

        @Override
        public LogEvent rewrite(LogEvent event) {
            if (event.getMessage() == null) return event;
            String original = event.getMessage().getFormattedMessage();
            if (original == null || original.isEmpty()) return event;

            String masked = original;
            masked = LOGIN.matcher(masked).replaceAll("$1***");
            masked = REGISTER.matcher(masked).replaceAll("$1***$3***");
            masked = CHANGEPW.matcher(masked).replaceAll("$1***$3***");
            masked = RESETPW.matcher(masked).replaceAll("$1***");

            if (masked.equals(original)) {
                return event;
            }

            return new Log4jLogEvent.Builder(event)
                    .setMessage(new SimpleMessage(masked))
                    .build();
        }
    }

    // ==================== Yggdrasil noise filter ====================

    private static final class YggdrasilPublicKeyFilter extends AbstractFilter {
        private static final String YGGDRASIL_PUBLIC_KEY_FAILURE = "failed to request yggdrasil public key";
        private static final String PUBLIC_KEYS_ENDPOINT = "api.minecraftservices.com/publickeys";
        private static final String REMOTE_HOST_TERMINATED_HANDSHAKE = "remote host terminated the handshake";
        private static final String SSL_PEER_SHUT_DOWN = "ssl peer shut down incorrectly";

        @Override
        public Result filter(LogEvent event) {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            return isYggdrasilPublicKeyFailure(message, event.getThrown()) ? Result.DENY : Result.NEUTRAL;
        }

        private boolean isYggdrasilPublicKeyFailure(String message, Throwable thrown) {
            if (message.toLowerCase(Locale.ROOT).contains(YGGDRASIL_PUBLIC_KEY_FAILURE)) {
                return true;
            }
            Throwable current = thrown;
            while (current != null) {
                String text = (current.getClass().getName() + " " + current.getMessage()).toLowerCase(Locale.ROOT);
                if (text.contains(PUBLIC_KEYS_ENDPOINT)
                        || text.contains(REMOTE_HOST_TERMINATED_HANDSHAKE)
                        || text.contains(SSL_PEER_SHUT_DOWN)) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
