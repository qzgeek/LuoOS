package heos.integrations;

import heos.Heos;
import heos.utils.HeosLogger;
import heos.utils.ProtocolCompatibility;
import heos.utils.TpsTracker;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

//? if >= 1.21.2 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if >= 1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//? if >= 1.21.11 {
import net.minecraft.world.level.storage.LevelData;
//?}
//?}

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Servux HUD channel compatibility for MiniHUD's server TPS info line.
 */
public final class ServuxMinihudSyncFeature {
    //? if >= 1.21.2 {
    private static final int PROTOCOL_VERSION = 2;
    private static final int PACKET_S2C_METADATA = 1;
    private static final int PACKET_C2S_METADATA_REQUEST = 2;
    private static final int PACKET_S2C_DATA_LOGGER_TICK = 7;
    private static final int PACKET_C2S_DATA_LOGGER_REQUEST = 8;
    private static final CustomPacketPayload.Type<ServuxHudPayload> HUD_METADATA =
            type("servux", "hud_metadata");
    private static final Map<UUID, Integer> ACTIVE_TPS_LOGGERS = new ConcurrentHashMap<>();
    //?}

    private ServuxMinihudSyncFeature() {
    }

    public static void initialize() {
        //? if >= 1.21.2 {
        if (ProtocolCompatibility.isModLoaded("servux")) {
            HeosLogger.info("MiniHUD Servux TPS sync skipped because Servux is installed");
            return;
        }

        StreamCodec<RegistryFriendlyByteBuf, ServuxHudPayload> codec = ServuxHudPayload.codec(HUD_METADATA);
        //? if >= 26 {
        /*PayloadTypeRegistry.serverboundPlay().register(HUD_METADATA, codec);
        PayloadTypeRegistry.clientboundPlay().register(HUD_METADATA, codec);
        *///?} else {
        PayloadTypeRegistry.playC2S().register(HUD_METADATA, codec);
        PayloadTypeRegistry.playS2C().register(HUD_METADATA, codec);
        //?}
        ServerPlayNetworking.registerGlobalReceiver(HUD_METADATA, (payload, context) ->
                handlePacket(context.player(), context.server(), payload));
        HeosLogger.info("MiniHUD Servux TPS sync registered");
        //?}
    }

    public static void stop(ServerPlayer player) {
        //? if >= 1.21.2 {
        ACTIVE_TPS_LOGGERS.remove(player.getUUID());
        //?}
    }

    public static void tick(MinecraftServer server) {
        //? if >= 1.21.2 {
        if (!Heos.getConfig().enableAutoLogTps || ACTIVE_TPS_LOGGERS.isEmpty()) {
            return;
        }

        int delay = Math.max(1, Heos.getConfig().autoLogTpsDelayTicks);
        for (Map.Entry<UUID, Integer> entry : ACTIVE_TPS_LOGGERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !ServerPlayNetworking.canSend(player, HUD_METADATA)) {
                ACTIVE_TPS_LOGGERS.remove(entry.getKey());
                continue;
            }

            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft > 0) {
                ACTIVE_TPS_LOGGERS.put(entry.getKey(), ticksLeft);
                continue;
            }

            ServerPlayNetworking.send(player, dataLoggerTickPayload());
            ACTIVE_TPS_LOGGERS.put(entry.getKey(), delay);
        }
        //?}
    }

    //? if >= 1.21.2 {
    private static CustomPacketPayload.Type<ServuxHudPayload> type(String namespace, String path) {
        //? if >= 1.21.11 {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(namespace, path));
        //?} else {
        /*return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(namespace, path));
        *///?}
    }

    private static void handlePacket(ServerPlayer player, MinecraftServer server, ServuxHudPayload payload) {
        if (!Heos.getConfig().enableAutoLogTps) {
            ACTIVE_TPS_LOGGERS.remove(player.getUUID());
            return;
        }

        switch (payload.packetId()) {
            case PACKET_C2S_METADATA_REQUEST -> sendMetadata(player, server);
            case PACKET_C2S_DATA_LOGGER_REQUEST -> handleDataLoggerRequest(player, payload.nbt());
            default -> {
            }
        }
    }

    private static void sendMetadata(ServerPlayer player, MinecraftServer server) {
        if (!ServerPlayNetworking.canSend(player, HUD_METADATA)) {
            return;
        }

        CompoundTag nbt = new CompoundTag();
        //? if >= 1.21.11 {
        LevelData.RespawnData respawnData = server.overworld().getLevelData().getRespawnData();
        BlockPos spawn = respawnData.pos();
        //?} else {
        /*BlockPos spawn = server.overworld().getLevelData().getSpawnPos();
        *///?}
        nbt.putInt("version", PROTOCOL_VERSION);
        nbt.putString("servux", "Heos");
        //? if >= 1.21.11 {
        nbt.putString("spawnDimension", respawnData.dimension().identifier().toString());
        //?} else {
        /*nbt.putString("spawnDimension", server.overworld().dimension().location().toString());
        *///?}
        nbt.putInt("spawnPosX", spawn.getX());
        nbt.putInt("spawnPosY", spawn.getY());
        nbt.putInt("spawnPosZ", spawn.getZ());
        ServerPlayNetworking.send(player, new ServuxHudPayload(HUD_METADATA, PACKET_S2C_METADATA, nbt));
    }

    private static void handleDataLoggerRequest(ServerPlayer player, CompoundTag nbt) {
        //? if >= 1.21.5 {
        boolean wantsTps = nbt.getBooleanOr("TPS", false);
        //?} else {
        /*boolean wantsTps = nbt.contains("TPS") && nbt.getBoolean("TPS");
        *///?}
        UUID uuid = player.getUUID();
        if (!wantsTps) {
            ACTIVE_TPS_LOGGERS.remove(uuid);
            return;
        }

        ACTIVE_TPS_LOGGERS.put(uuid, 1);
    }

    private static ServuxHudPayload dataLoggerTickPayload() {
        CompoundTag tps = new CompoundTag();
        tps.putDouble("mspt", TpsTracker.currentMspt());
        tps.putDouble("tps", TpsTracker.currentTps());
        tps.putLong("sprintTicks", 0L);
        tps.putBoolean("frozen", false);
        tps.putBoolean("sprinting", false);
        tps.putBoolean("stepping", false);

        CompoundTag root = new CompoundTag();
        root.put("tps", tps);
        return new ServuxHudPayload(HUD_METADATA, PACKET_S2C_DATA_LOGGER_TICK, root);
    }

    private record ServuxHudPayload(
            CustomPacketPayload.Type<ServuxHudPayload> type,
            int packetId,
            CompoundTag nbt
    ) implements CustomPacketPayload {
        private static StreamCodec<RegistryFriendlyByteBuf, ServuxHudPayload> codec(
                CustomPacketPayload.Type<ServuxHudPayload> type
        ) {
            return CustomPacketPayload.codec(ServuxHudPayload::write, buffer -> read(type, buffer));
        }

        private static ServuxHudPayload read(
                CustomPacketPayload.Type<ServuxHudPayload> type,
                RegistryFriendlyByteBuf buffer
        ) {
            int packetId = buffer.readVarInt();
            CompoundTag nbt = buffer.readNbt();
            return new ServuxHudPayload(type, packetId, nbt == null ? new CompoundTag() : nbt);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(packetId);
            buffer.writeNbt(nbt);
        }
    }
    //?}
}
