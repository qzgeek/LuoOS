package heos.storage;

import heos.Heos;

import java.io.File;
import java.nio.file.Path;

/**
 * Central paths for Heos server-side data under `server/heos`.
 */
public final class StoragePaths {
    private static final String ROOT_DIR = "heos";

    private StoragePaths() {
    }

    public static Path root() {
        return Heos.gameDirectory.resolve(ROOT_DIR);
    }

    public static File file(String name) {
        return root().resolve(name).toFile();
    }

    public static void ensureRoot() {
        File root = root().toFile();
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("Failed to create Heos data directory: " + root.getPath());
        }
    }
}
