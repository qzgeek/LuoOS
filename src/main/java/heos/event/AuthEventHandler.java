package heos.event;

import heos.interfaces.PlayerAuth;
import net.minecraft.network.protocol.Packet;
//? if >= 1.20.5 {
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
//?} else {
/*import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundResourcePackPacket;
*///?}
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
//? if >= 1.20.5 {
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
//?}
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
//? if >= 1.21.2 {
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
//?}
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
//? if >= 1.20.5 {
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
//?}
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
//? if >= 1.21.5 {
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
//?}
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * Handles authentication events and restricts unauthenticated players
 */
public class AuthEventHandler {

    public static boolean isAllowedPacket(Packet<?> packet) {
        if (packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundResourcePackPacket
                || packet instanceof ServerboundAcceptTeleportationPacket
                || packet instanceof ServerboundClientCommandPacket
                || packet instanceof ServerboundPongPacket
                || packet instanceof ServerboundClientInformationPacket
                //? if >= 1.20.5 {
                || packet instanceof ServerboundChunkBatchReceivedPacket
                || packet instanceof ServerboundConfigurationAcknowledgedPacket
                //?}
                //? if >= 1.19.3 {
                || packet instanceof ServerboundChatSessionUpdatePacket
                //?}
                || packet instanceof ServerboundCommandSuggestionPacket
                || packet instanceof ServerboundChatCommandPacket
                //? if >= 1.21.2 {
                || packet instanceof ServerboundClientTickEndPacket
                //?}
                //? if >= 1.21.5 {
                /*|| packet instanceof ServerboundPlayerLoadedPacket
                *///?}
        ) {
            return true;
        }

        if (packet instanceof ServerboundMovePlayerPacket
                || packet instanceof ServerboundMoveVehiclePacket
                || packet instanceof ServerboundPlayerInputPacket) {
            return true;
        }

        return false;
    }

    public static InteractionResult onPlayerMove(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            double nextX = packet.getX(player.getX());
            double nextY = packet.getY(player.getY());
            double nextZ = packet.getZ(player.getZ());

            boolean moved = Double.compare(nextX, player.getX()) != 0
                    || Double.compare(nextY, player.getY()) != 0
                    || Double.compare(nextZ, player.getZ()) != 0;

            float yaw = packet.getYRot(player.getYRot());
            float pitch = packet.getXRot(player.getXRot());
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setYHeadRot(yaw);

            if (!moved) {
                return InteractionResult.PASS;
            }
            player.connection.teleport(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    yaw,
                    pitch
            );
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onPlayerChat(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static boolean onBreakBlock(Player player) {
        if (player.getClass() != ServerPlayer.class) {
            return true;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return false;
        }
        return true;
    }

    public static InteractionResult onUseBlock(Player player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onUseItem(Player player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onTakeItem(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onInventoryAction(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onHotbarChange(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onDropItem(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onAttackEntity(Player player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onUseEntity(Player player) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult onPlayerCommand(ServerPlayer player, String command) {
        if (player.getClass() != ServerPlayer.class) {
            return InteractionResult.PASS;
        }
        if (command.startsWith("login ") || command.startsWith("register ") || command.startsWith("l ") || command.startsWith("reg ")) {
            return InteractionResult.PASS;
        }

        if (!((PlayerAuth) player).heos$isAuthenticated()) {
            ((PlayerAuth) player).heos$sendAuthMessage();
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }
}
