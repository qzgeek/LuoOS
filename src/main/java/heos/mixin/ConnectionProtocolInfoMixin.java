package heos.mixin;

import heos.interfaces.ConnectionProtocolInfo;
import heos.utils.HeosLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
//? if >= 1.20.5 {
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
//? if >= 1.21.6 {
import io.netty.channel.ChannelFutureListener;
//?} else {
/*import net.minecraft.network.PacketSendListener;
*///?}
//?} else {
/*import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
*///?}
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
//? if >= 1.20.5 {
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Carries handshake protocol information forward to the player join path.
 */
@Mixin(Connection.class)
public abstract class ConnectionProtocolInfoMixin implements ConnectionProtocolInfo {
    @Shadow
    private Channel channel;

    @Unique
    private static final String HEOS_C2ME_EXT_RENDER_DISTANCE_CHANNEL = "c2me:ext_render_distance_v1";

    @Unique
    private int heos$clientProtocolVersion = SharedConstants.getProtocolVersion();

    @Unique
    private boolean heos$viaDetailsSeen = false;

    @Unique
    private String heos$debugPlayerName = "unknown";

    @Override
    public void heos$setClientProtocolVersion(int protocolVersion) {
        this.heos$clientProtocolVersion = protocolVersion;
    }

    @Override
    public int heos$getClientProtocolVersion() {
        return this.heos$clientProtocolVersion;
    }

    @Override
    public void heos$setDebugPlayerName(String playerName) {
        this.heos$debugPlayerName = playerName == null || playerName.isBlank() ? "unknown" : playerName;
    }

    @Override
    public String heos$getDebugPlayerName() {
        return this.heos$debugPlayerName;
    }

    @Override
    public Object heos$getChannel() {
        return this.channel;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void heos$dropCrossProtocolInboundPayload(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (heos$shouldDropInboundPayload(packet)) {
            ci.cancel();
        }
    }

    //? if >= 1.20.5 {
    //? if >= 1.21.6 {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void heos$filterCrossProtocolOutboundPayload(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        Packet<?> replacement = heos$rewriteOutboundPayload(packet);
        if (replacement != packet) {
            ci.cancel();
            if (replacement != null) {
                ((Connection) (Object) this).send(replacement, listener, flush);
            }
        }
    }
    //?} else {
    /*@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void heos$filterCrossProtocolOutboundPayload(Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        Packet<?> replacement = heos$rewriteOutboundPayload(packet);
        if (replacement != packet) {
            ci.cancel();
            if (replacement != null) {
                ((Connection) (Object) this).send(replacement, listener, flush);
            }
        }
    }
    *///?}
    //?} else {
    /*@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void heos$filterCrossProtocolOutboundPayload(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        Packet<?> replacement = heos$rewriteOutboundPayload(packet);
        if (replacement != packet) {
            ci.cancel();
            if (replacement != null) {
                ((Connection) (Object) this).send(replacement, listener);
            }
        }
    }
    *///?}

    @Unique
    private boolean heos$shouldDropInboundPayload(Packet<?> packet) {
        //? if >= 1.20.5 {
        if (!(packet instanceof ServerboundCustomPayloadPacket customPacket)) {
            return false;
        }

        String channel = customPacket.payload().type().id().toString();
        //?} else {
        /*if (!(packet instanceof ServerboundCustomPayloadPacket customPacket)) {
            return false;
        }

        String channel = customPacket.getIdentifier().toString();
        *///?}
        if (heos$isViaDetailsChannel(channel)) {
            this.heos$viaDetailsSeen = true;
            return false;
        }

        if (!heos$shouldBlockCompatibilityChannel(channel)) {
            return false;
        }

        HeosLogger.info("Dropped client compatibility payload " + channel
                + heos$protocolDescription());
        return true;
    }

    @Unique
    private Packet<?> heos$rewriteOutboundPayload(Packet<?> packet) {
        //? if >= 1.20.5 {
        if (!(packet instanceof ClientboundCustomPayloadPacket customPacket)) {
            return packet;
        }

        CustomPacketPayload filteredPayload = heos$filterOutboundPayload(customPacket.payload());
        if (filteredPayload == customPacket.payload()) {
            return packet;
        }
        if (filteredPayload == null) {
            return null;
        }

        return new ClientboundCustomPayloadPacket(filteredPayload);
        //?} else {
        /*if (!(packet instanceof ClientboundCustomPayloadPacket customPacket)) {
            return packet;
        }

        String channel = customPacket.getIdentifier().toString();
        if (heos$isBlockedCompatibilityChannel(channel)) {
            HeosLogger.info("Dropped server compatibility payload " + channel
                    + heos$protocolDescription());
            return null;
        }

        return packet;
        *///?}
    }

    //? if >= 1.20.5 {
    @Unique
    private CustomPacketPayload heos$filterOutboundPayload(CustomPacketPayload payload) {
        String channel = payload.type().id().toString();
        if (heos$shouldBlockCompatibilityChannel(channel)) {
            HeosLogger.debug("Dropped server compatibility payload " + channel
                    + heos$protocolDescription());
            return null;
        }

        if (payload instanceof RegistrationPayload registrationPayload) {
            return heos$filterRegistrationPayload(registrationPayload);
        }
        if (payload instanceof CommonRegisterPayload commonRegisterPayload) {
            return heos$filterCommonRegisterPayload(commonRegisterPayload);
        }

        return payload;
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private CustomPacketPayload heos$filterRegistrationPayload(RegistrationPayload payload) {
        List filteredChannels = heos$filterChannelList(payload.channels());
        if (filteredChannels == payload.channels()) {
            return payload;
        }
        if (filteredChannels.isEmpty()) {
            HeosLogger.info("Dropped Fabric channel registration containing only compatibility-blocked channels");
            return null;
        }

        HeosLogger.info("Removed compatibility-blocked Fabric channel registrations" + heos$protocolDescription());
        return new RegistrationPayload((CustomPacketPayload.Type) payload.type(), filteredChannels);
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private CustomPacketPayload heos$filterCommonRegisterPayload(CommonRegisterPayload payload) {
        Set filteredChannels = heos$filterChannelSet(payload.channels());
        if (filteredChannels == payload.channels()) {
            return payload;
        }
        if (filteredChannels.isEmpty()) {
            HeosLogger.info("Dropped common channel registration containing only compatibility-blocked channels");
            return null;
        }

        HeosLogger.info("Removed compatibility-blocked common channel registrations" + heos$protocolDescription());
        //? if >= 26 {
        /*return new CommonRegisterPayload(payload.version(), payload.protocol(), filteredChannels);
        *///?} else {
        return new CommonRegisterPayload(payload.version(), payload.phase(), filteredChannels);
        //?}
    }

    @Unique
    private List<?> heos$filterChannelList(List<?> channels) {
        boolean containsBlockedChannel = channels.stream()
                .anyMatch(channel -> heos$shouldBlockCompatibilityChannel(channel.toString()));
        if (!containsBlockedChannel) {
            return channels;
        }

        List<Object> filteredChannels = new ArrayList<>(channels.size());
        for (Object channel : channels) {
            if (!heos$shouldBlockCompatibilityChannel(channel.toString())) {
                filteredChannels.add(channel);
            }
        }
        return filteredChannels;
    }

    @Unique
    private Set<?> heos$filterChannelSet(Set<?> channels) {
        boolean containsBlockedChannel = channels.stream()
                .anyMatch(channel -> heos$shouldBlockCompatibilityChannel(channel.toString()));
        if (!containsBlockedChannel) {
            return channels;
        }

        Set<Object> filteredChannels = new LinkedHashSet<>();
        for (Object channel : channels) {
            if (!heos$shouldBlockCompatibilityChannel(channel.toString())) {
                filteredChannels.add(channel);
            }
        }
        return filteredChannels;
    }
    //?}

    @Unique
    private boolean heos$isCrossProtocol() {
        return this.heos$clientProtocolVersion != SharedConstants.getProtocolVersion();
    }

    @Unique
    private String heos$protocolDescription() {
        if (heos$isCrossProtocol()) {
            return " for protocol " + this.heos$clientProtocolVersion;
        }
        if (this.heos$viaDetailsSeen) {
            return " for Via-translated connection";
        }
        return "";
    }

    @Unique
    private boolean heos$shouldBlockCompatibilityChannel(String channel) {
        return heos$isCrossProtocol() && heos$isBlockedCompatibilityChannel(channel);
    }

    @Unique
    private static boolean heos$isBlockedCompatibilityChannel(String channel) {
        return HEOS_C2ME_EXT_RENDER_DISTANCE_CHANNEL.equals(channel);
    }

    @Unique
    private static boolean heos$isViaDetailsChannel(String channel) {
        return "vv:mod_details".equals(channel)
                || "vv:proxy_details".equals(channel)
                || "vv:app_details".equals(channel);
    }
}
