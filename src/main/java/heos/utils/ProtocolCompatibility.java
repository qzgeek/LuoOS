package heos.utils;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Keeps optional ViaVersion integration reflective so Heos can still load without Via installed.
 */
public final class ProtocolCompatibility {
    private static final int UNKNOWN_VIA_PROTOCOL = -1;

    private ProtocolCompatibility() {
    }

    public static boolean isViaPlatformLoaded() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(container -> container.getMetadata().getId())
                .anyMatch(id -> id.equals("viafabric")
                        || id.equals("viafabricplus")
                        || id.equals("viaversion")
                        || id.equals("viabackwards")
                        || id.equals("viarewind")
                        || id.startsWith("viafabric-mc"));
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static int resolveClientProtocol(ServerPlayer player, int fallbackProtocol) {
        int viaProtocol = getViaPlayerProtocol(player.getUUID());
        if (viaProtocol > 0) {
            return viaProtocol;
        }
        if (isViaPlatformLoaded()) {
            return UNKNOWN_VIA_PROTOCOL;
        }
        return fallbackProtocol;
    }

    private static int getViaPlayerProtocol(UUID uuid) {
        try {
            Object api = getViaApi();
            Method getPlayerVersion = api.getClass().getMethod("getPlayerVersion", UUID.class);
            Object version = getPlayerVersion.invoke(api, uuid);
            return version instanceof Number number ? number.intValue() : UNKNOWN_VIA_PROTOCOL;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return UNKNOWN_VIA_PROTOCOL;
        }
    }

    private static Object getViaApi() throws ReflectiveOperationException {
        Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
        Method getApi = viaClass.getMethod("getAPI");
        return getApi.invoke(null);
    }
}
