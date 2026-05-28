package heos.utils;

import heos.Heos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically publishes TPS/MSPT in the Carpet tab footer format used by MiniHUD.
 */
public final class TpsDisplayService {
    private static final Map<UUID, Integer> ACTIVE = new ConcurrentHashMap<>();

    private TpsDisplayService() {
    }

    public static void start(ServerPlayer player) {
        if (!Heos.getConfig().enableAutoLogTps) {
            return;
        }
        ACTIVE.put(player.getUUID(), Math.max(1, Heos.getConfig().autoLogTpsDelayTicks));
    }

    public static void stop(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        clearDisplay(player);
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        int delay = Math.max(1, Heos.getConfig().autoLogTpsDelayTicks);
        for (Map.Entry<UUID, Integer> entry : ACTIVE.entrySet()) {
            UUID uuid = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                ACTIVE.remove(uuid);
                continue;
            }
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft > 0) {
                ACTIVE.put(uuid, ticksLeft);
                continue;
            }
            ChatFormatting valueColor = TpsTracker.currentStatusColor();
            Component footer = Component.literal("")
                    .append(Component.literal("TPS: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(TpsTracker.formatCarpetTps()).withStyle(valueColor))
                    .append(Component.literal(" MSPT: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(TpsTracker.formatCarpetMspt()).withStyle(valueColor));
            sendDisplay(player, footer);
            ACTIVE.put(uuid, delay);
        }
    }

    private static void sendDisplay(ServerPlayer player, Component footer) {
        player.connection.send(new ClientboundTabListPacket(Component.empty(), footer));
    }

    private static void clearDisplay(ServerPlayer player) {
        player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
    }
}
