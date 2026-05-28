package heos.integrations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import heos.interfaces.PlayerAuth;
import heos.utils.HeosLogger;
import heos.utils.ProtocolCompatibility;

//? if >= 1.21.2 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//?}

import java.nio.charset.StandardCharsets;

/**
 * Consumes ViaVersion's player details payload so compatibility checks do not rely only on the handshake protocol.
 */
public final class ViaVersionDetailsFeature {
    //? if >= 1.21.2 {
    private static final CustomPacketPayload.Type<ViaDetailsPayload> VIA_MOD_DETAILS =
            type("vv", "mod_details");
    private static final CustomPacketPayload.Type<ViaDetailsPayload> VIA_PROXY_DETAILS =
            type("vv", "proxy_details");
    private static final CustomPacketPayload.Type<ViaDetailsPayload> VIA_APP_DETAILS =
            type("vv", "app_details");
    //?}

    private ViaVersionDetailsFeature() {
    }

    public static void initialize() {
        //? if >= 1.21.2 {
        if (ProtocolCompatibility.isViaPlatformLoaded()) {
            HeosLogger.info("ViaVersion player details compatibility skipped because ViaVersion/ViaFabric is installed");
            return;
        }

        register(VIA_MOD_DETAILS);
        register(VIA_PROXY_DETAILS);
        register(VIA_APP_DETAILS);
        HeosLogger.info("ViaVersion player details compatibility registered");
        //?}
    }

    //? if >= 1.21.2 {
    private static CustomPacketPayload.Type<ViaDetailsPayload> type(String namespace, String path) {
        //? if >= 1.21.11 {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(namespace, path));
        //?} else {
        /*return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(namespace, path));
        *///?}
    }

    private static void register(CustomPacketPayload.Type<ViaDetailsPayload> type) {
        StreamCodec<RegistryFriendlyByteBuf, ViaDetailsPayload> codec = ViaDetailsPayload.codec(type);
        //? if >= 26 {
        /*PayloadTypeRegistry.serverboundPlay().register(type, codec);
        *///?} else {
        PayloadTypeRegistry.playC2S().register(type, codec);
        //?}
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> applyDetails(payload, (PlayerAuth) context.player()));
    }

    private static void applyDetails(ViaDetailsPayload payload, PlayerAuth player) {
        try {
            JsonObject json = JsonParser.parseString(payload.asUtf8()).getAsJsonObject();
            if (!json.has("version")) {
                return;
            }

            int clientProtocol = json.get("version").getAsInt();
            player.heos$setClientProtocolVersion(clientProtocol);
            if (clientProtocol != SharedConstants.getProtocolVersion()) {
                String versionName = json.has("versionName") ? json.get("versionName").getAsString() : "unknown";
                HeosLogger.debug("Detected Via-translated client protocol " + clientProtocol
                        + " (" + versionName + ") for recipe/network compatibility checks");
            }
        } catch (RuntimeException exception) {
            HeosLogger.debug("Ignored malformed ViaVersion player details payload: " + exception.getMessage());
        }
    }

    private record ViaDetailsPayload(
            CustomPacketPayload.Type<ViaDetailsPayload> type,
            byte[] data
    ) implements CustomPacketPayload {
        private static StreamCodec<RegistryFriendlyByteBuf, ViaDetailsPayload> codec(
                CustomPacketPayload.Type<ViaDetailsPayload> type
        ) {
            return CustomPacketPayload.codec(ViaDetailsPayload::write, buffer -> read(type, buffer));
        }

        private static ViaDetailsPayload read(
                CustomPacketPayload.Type<ViaDetailsPayload> type,
                RegistryFriendlyByteBuf buffer
        ) {
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return new ViaDetailsPayload(type, data);
        }

        private String asUtf8() {
            return new String(data, StandardCharsets.UTF_8);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBytes(data);
        }
    }
    //?}
}
