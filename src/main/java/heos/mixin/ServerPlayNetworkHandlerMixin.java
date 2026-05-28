package heos.mixin;

import heos.event.AuthEventHandler;
import heos.commands.SensitiveCommandHandler;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS;
import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ITEM;
import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND;

/**
 * Restricts unauthenticated player network actions
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onPlayerCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        InteractionResult sensitiveResult = SensitiveCommandHandler.handle(this.player, packet.command());
        if (sensitiveResult == InteractionResult.FAIL) {
            ci.cancel();
            return;
        }

        InteractionResult result = AuthEventHandler.onPlayerCommand(this.player, packet.command());
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;unpackAndApplyLastSeen(Lnet/minecraft/network/chat/LastSeenMessages$Update;)Ljava/util/Optional;",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true
    )
    private void onPlayerChat(ServerboundChatPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onPlayerChat(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == SWAP_ITEM_WITH_OFFHAND) {
            InteractionResult result = AuthEventHandler.onTakeItem(this.player);
            if (result == InteractionResult.FAIL) {
                ci.cancel();
            }
        }

        if (packet.getAction() == DROP_ITEM || packet.getAction() == DROP_ALL_ITEMS) {
            InteractionResult result = AuthEventHandler.onDropItem(this.player);
            if (result == InteractionResult.FAIL) {
                ci.cancel();
            }
        }
    }

    @Inject(
            method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onPlayerMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onPlayerMove(player, packet);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onPlayerInteractBlock(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onUseBlock(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onPlayerInteractItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onUseItem(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onPlayerInteractEntity(ServerboundInteractPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onUseEntity(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleAnimate(Lnet/minecraft/network/protocol/game/ServerboundSwingPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onHandSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onUseItem(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleSetCreativeModeSlot(Lnet/minecraft/network/protocol/game/ServerboundSetCreativeModeSlotPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onCreativeInventoryAction(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onInventoryAction(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onClickSlot(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onInventoryAction(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerClose(Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onCloseHandledScreen(ServerboundContainerClosePacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onInventoryAction(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleContainerButtonClick(Lnet/minecraft/network/protocol/game/ServerboundContainerButtonClickPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onButtonClick(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onInventoryAction(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleSetCarriedItem(Lnet/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true
    )
    private void onUpdateSelectedSlot(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        InteractionResult result = AuthEventHandler.onHotbarChange(this.player);
        if (result == InteractionResult.FAIL) {
            ci.cancel();
        }
    }
}
