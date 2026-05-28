package heos.utils;

import heos.Heos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogFilterService {
    private static final Filter HEOS_LOG_FILTER = new HeosLogFilter();
    private static boolean installed;
    private static long lastCrashNoticeMillis;

    private LogFilterService() {
    }

    public static void installConfiguredFilters() {
        if (!Heos.getConfig().enableLogFilter || installed) {
            return;
        }

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig rootLogger = configuration.getRootLogger();
        rootLogger.addFilter(HEOS_LOG_FILTER);
        context.updateLoggers();
        installed = true;
        HeosLogger.info("日志过滤: Enabled");
    }

    private static final class HeosLogFilter extends AbstractFilter {
        private static final String PACKET_ERROR = "failed to handle packet";
        private static final String UPDATE_NEIGHBORS = "exception while updating neighbours";
        private static final String CAUSED_SERVER_CRASH = "you just caused a server crash";
        private static final String UPDATE_SUPPRESSION = "update suppression";
        private static final String STACK_OVERFLOW_SUPPRESSION = "stackoverflowsuppression";
        private static final String TIS_UPDATE_SUPPRESSION = "yeetupdatesuppressioncrash";
        private static final String METHOD_OVERWRITE_CONFLICT = "method overwrite conflict";
        private static final String REDIRECT_CONFLICT = "@redirect conflict";
        private static final String SKIPPING_CARPET_EXTRA = "skipping carpet-extra.mixins.json:serverplaynetworkhandlermixin";
        private static final String YGGDRASIL_PUBLIC_KEY_FAILURE = "failed to request yggdrasil public key";
        private static final long STACK_SUMMARY_SUPPRESSION_MILLIS = 1000L;
        private static final Pattern BLOCK_POSITION = Pattern.compile("\\[[\\-0-9]+,\\s*[\\-0-9]+,\\s*[\\-0-9]+\\]");
        private static final Pattern BLOCK_POS_OBJECT = Pattern.compile("BlockPos\\{x=([\\-0-9]+),\\s*y=([\\-0-9]+),\\s*z=([\\-0-9]+)\\}");
        private static final Pattern NAMED_COORDINATES = Pattern.compile("\\bx=([\\-0-9]+),\\s*y=([\\-0-9]+),\\s*z=([\\-0-9]+)\\b");
        private static final Pattern PLAIN_COORDINATES = Pattern.compile("\\b(?:at|in)\\s+([\\-0-9]+),\\s*([\\-0-9]+),\\s*([\\-0-9]+)\\b", Pattern.CASE_INSENSITIVE);

        @Override
        public Result filter(LogEvent event) {
            if (!Heos.getConfig().enableLogFilter) {
                return Result.NEUTRAL;
            }

            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            if (isKnownMixinNoise(message) || isKnownAuthlibNoise(message, thrown)) {
                return Result.DENY;
            }
            if (isUpdateSuppressionCrashNotice(message)) {
                lastCrashNoticeMillis = System.currentTimeMillis();
                HeosLogger.warn(Messages.updateSuppressionCrash(updateSuppressionDetail(message)));
                return Result.DENY;
            }
            if (matches(message, thrown)) {
                String detail = updateSuppressionPosition(message, event.getMessage() == null ? null : event.getMessage().getParameters(), thrown);
                if (!recentCrashNotice()) {
                    HeosLogger.warn(Messages.updateSuppressionCrash(detail));
                }
                return Result.DENY;
            }
            return Result.NEUTRAL;
        }

        private boolean isKnownMixinNoise(String message) {
            String normalizedMessage = message.toLowerCase(Locale.ROOT);
            return isKnownOverwriteConflict(normalizedMessage)
                    || (normalizedMessage.contains(REDIRECT_CONFLICT)
                    && normalizedMessage.contains(SKIPPING_CARPET_EXTRA));
        }

        private boolean isKnownAuthlibNoise(String message, Throwable thrown) {
            if (message.toLowerCase(Locale.ROOT).contains(YGGDRASIL_PUBLIC_KEY_FAILURE)) {
                return true;
            }
            Throwable current = thrown;
            while (current != null) {
                String text = (current.getClass().getName() + " " + current.getMessage()).toLowerCase(Locale.ROOT);
                if (text.contains("api.minecraftservices.com/publickeys")
                        || text.contains("remote host terminated the handshake")
                        || text.contains("ssl peer shut down incorrectly")) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private boolean isKnownOverwriteConflict(String normalizedMessage) {
            if (!normalizedMessage.contains(METHOD_OVERWRITE_CONFLICT)) {
                return false;
            }
            return (normalizedMessage.contains("getmergedtnt")
                    && normalizedMessage.contains("rems.mixins.json"))
                    || (normalizedMessage.contains("getclimatesettings")
                    && normalizedMessage.contains("architectury.mixins.json"));
        }

        private boolean matches(String message, Throwable thrown) {
            String normalizedMessage = message.toLowerCase(Locale.ROOT);
            if (normalizedMessage.contains(PACKET_ERROR) && hasUpdateSuppressionCause(thrown)) {
                return true;
            }
            return normalizedMessage.contains(UPDATE_NEIGHBORS) && hasUpdateSuppressionCause(thrown);
        }

        private boolean isUpdateSuppressionCrashNotice(String message) {
            String normalizedMessage = message.toLowerCase(Locale.ROOT);
            return normalizedMessage.contains(CAUSED_SERVER_CRASH) && normalizedMessage.contains(UPDATE_SUPPRESSION);
        }

        private String updateSuppressionDetail(String message) {
            return updateSuppressionPosition(message, null, null);
        }

        private String updateSuppressionPosition(String message, Object[] parameters, Throwable thrown) {
            String detail = findBlockPosition(message);
            if (!detail.isEmpty()) {
                return detail;
            }

            detail = findBlockPosition(parameters);
            if (!detail.isEmpty()) {
                return detail;
            }

            detail = findBlockPosition(thrown);
            if (!detail.isEmpty()) {
                return detail;
            }

            return Messages.unknownPosition();
        }

        private String findBlockPosition(Object[] values) {
            if (values == null) {
                return "";
            }
            for (Object value : values) {
                String detail = findBlockPosition(value);
                if (!detail.isEmpty()) {
                    return detail;
                }
            }
            return "";
        }

        private String findBlockPosition(Object value) {
            if (value == null) {
                return "";
            }
            if (value instanceof Throwable throwable) {
                return findBlockPosition(throwable);
            }
            String detail = findCoordinatesByAccessors(value);
            if (!detail.isEmpty()) {
                return detail;
            }
            return findBlockPosition(String.valueOf(value));
        }

        private String findBlockPosition(Throwable throwable) {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            return findBlockPosition(throwable, visited);
        }

        private String findBlockPosition(Throwable throwable, Set<Throwable> visited) {
            if (throwable == null || !visited.add(throwable)) {
                return "";
            }

            String detail = findBlockPosition(throwable.toString());
            if (!detail.isEmpty()) {
                return detail;
            }

            detail = findBlockPosition(throwable.getMessage());
            if (!detail.isEmpty()) {
                return detail;
            }

            detail = findCoordinatesFromFields(throwable);
            if (!detail.isEmpty()) {
                return detail;
            }

            for (Throwable suppressed : throwable.getSuppressed()) {
                detail = findBlockPosition(suppressed, visited);
                if (!detail.isEmpty()) {
                    return detail;
                }
            }

            return findBlockPosition(throwable.getCause(), visited);
        }

        private String findBlockPosition(String text) {
            if (text == null || text.isBlank()) {
                return "";
            }
            Matcher positionMatcher = BLOCK_POSITION.matcher(text);
            if (positionMatcher.find()) {
                return positionMatcher.group();
            }

            positionMatcher = BLOCK_POS_OBJECT.matcher(text);
            if (positionMatcher.find()) {
                return formatPosition(positionMatcher.group(1), positionMatcher.group(2), positionMatcher.group(3));
            }

            positionMatcher = NAMED_COORDINATES.matcher(text);
            if (positionMatcher.find()) {
                return formatPosition(positionMatcher.group(1), positionMatcher.group(2), positionMatcher.group(3));
            }

            positionMatcher = PLAIN_COORDINATES.matcher(text);
            if (positionMatcher.find()) {
                return formatPosition(positionMatcher.group(1), positionMatcher.group(2), positionMatcher.group(3));
            }
            return "";
        }

        private String findCoordinatesFromFields(Throwable throwable) {
            Class<?> type = throwable.getClass();
            while (type != null && type != Throwable.class) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(throwable);
                        String detail = findBlockPosition(value);
                        if (!detail.isEmpty()) {
                            return detail;
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                    }
                }
                type = type.getSuperclass();
            }
            return "";
        }

        private String findCoordinatesByAccessors(Object value) {
            Class<?> type = value.getClass();
            if (!type.getName().toLowerCase(Locale.ROOT).contains("blockpos")) {
                return "";
            }
            try {
                Method getX = type.getMethod("getX");
                Method getY = type.getMethod("getY");
                Method getZ = type.getMethod("getZ");
                Object x = getX.invoke(value);
                Object y = getY.invoke(value);
                Object z = getZ.invoke(value);
                return formatPosition(String.valueOf(x), String.valueOf(y), String.valueOf(z));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return "";
            }
        }

        private String formatPosition(String x, String y, String z) {
            return "[" + x + ", " + y + ", " + z + "]";
        }

        private boolean recentCrashNotice() {
            return System.currentTimeMillis() - lastCrashNoticeMillis < STACK_SUMMARY_SUPPRESSION_MILLIS;
        }

        private boolean hasUpdateSuppressionCause(Throwable throwable) {
            Throwable current = throwable;
            while (current != null) {
                String className = current.getClass().getName().toLowerCase(Locale.ROOT);
                String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
                if (className.contains(STACK_OVERFLOW_SUPPRESSION)
                        || className.contains(TIS_UPDATE_SUPPRESSION)
                        || message.contains(UPDATE_NEIGHBORS)) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
