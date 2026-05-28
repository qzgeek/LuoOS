package heos.mixin;

import heos.interfaces.ConnectionProtocolInfo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the client's requested protocol before login switches state.
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakeProtocolMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention(Lnet/minecraft/network/protocol/handshake/ClientIntentionPacket;)V", at = @At("HEAD"))
    private void heos$captureClientProtocol(ClientIntentionPacket packet, CallbackInfo ci) {
        //? if >= 1.20.5 {
        int protocolVersion = packet.protocolVersion();
        //?} else {
        /*int protocolVersion = packet.getProtocolVersion();
        *///?}
        ((ConnectionProtocolInfo) connection).heos$setClientProtocolVersion(protocolVersion);
    }
}
