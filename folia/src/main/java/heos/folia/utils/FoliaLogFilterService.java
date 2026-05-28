package heos.folia.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

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
        context.updateLoggers();
        installed = true;
        plugin.getLogger().info("Yggdrasil public key failure filter enabled");
    }

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
