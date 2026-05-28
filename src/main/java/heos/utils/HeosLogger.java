package heos.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple and compatible logger for Heos
 */
public class HeosLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("Heos");

    public static void info(String message) {
        LOGGER.info("[Heos] {}", message);
    }

    public static void info(String source, String message) {
        LOGGER.info("[Heos][{}] {}", source, message);
    }

    public static void warn(String message) {
        LOGGER.warn("[Heos] {}", message);
    }

    public static void warn(String source, String message) {
        LOGGER.warn("[Heos][{}] {}", source, message);
    }

    public static void error(String message) {
        LOGGER.error("[Heos] {}", message);
    }

    public static void error(String source, String message) {
        LOGGER.error("[Heos][{}] {}", source, message);
    }

    public static void error(String message, Throwable throwable) {
        LOGGER.error("[Heos] {}", message, throwable);
    }

    public static void error(String source, String message, Throwable throwable) {
        LOGGER.error("[Heos][{}] {}", source, message, throwable);
    }

    public static void debug(String message) {
        LOGGER.debug("[Heos] {}", message);
    }

    public static void debug(String source, String message) {
        LOGGER.debug("[Heos][{}] {}", source, message);
    }

    public static Logger getLogger() {
        return LOGGER;
    }
}
