package heos.mixin;

import com.mojang.authlib.GameProfile;
import heos.Heos;
import heos.integrations.MojangApi;
import heos.storage.BanData;
import heos.storage.PlayerData;
import heos.utils.HeosLogger;
import heos.utils.LoginFailureTracker;
import heos.utils.Messages;
import heos.utils.TimeParser;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
//? if >= 1.21.2 {
import net.minecraft.network.DisconnectionDetails;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Mixin to handle offline players joining online-mode server
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow
    //? if >= 1.20.5 {
    public GameProfile authenticatedProfile;
    //?} else {
    /*public GameProfile gameProfile;
    *///?}

    @Shadow
    @Final
    MinecraftServer server;

    @Shadow
    @Final
    Connection connection;

    @Shadow
    public abstract String getUserName();

    @Shadow
    public abstract void disconnect(Component reason);

    //? if >= 1.20.5 {
    @Invoker("finishLoginAndWaitForClient")
    abstract void heos$finishLoginAndWaitForClient(GameProfile profile);
    //?}

    @Unique
    private boolean heos$pendingPremiumVerification = false;
    @Unique
    private String heos$loginUsername = "unknown";
    @Unique
    private static final String[] heos$SILENCED_REASONS = {
            Messages.offlineNameLogOnly(),
            Messages.migrationBanLogOnly(),
            Messages.whitelistLogOnly()
    };

    @Inject(method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V", at = @At("HEAD"), cancellable = true)
    private void checkBan(ServerboundHelloPacket packet, CallbackInfo ci) {
        String username = packet.name();
        heos$loginUsername = username;
        String remoteIp = heos$extractRemoteIp();
        if (heos$rejectFailureLock(username, remoteIp)
                || heos$rejectIpBan(remoteIp)
                || heos$rejectPlayerBan(username)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"),
        cancellable = true
    )
    private void checkPremiumAccount(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!server.usesAuthentication()) {
            return;
        }

        String username = packet.name();
        HeosLogger.debug("Checking player: " + username);

        if (heos$routeExplicitOfflineName(username)) {
            ci.cancel();
            return;
        }

        if (heos$shouldContinueVanillaPremiumFlow(username)) {
            heos$pendingPremiumVerification = true;
        } else {
            ci.cancel();
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaDisconnectLog(Component reason, CallbackInfo ci) {
        if (reason != null) {
            String text = reason.getString();
            if (heos$isWhitelistDisconnect(text)) {
                HeosLogger.info(Messages.whitelistDeniedLog(heos$loginUsername));
                heos$disconnectWithoutVanillaLogs(Component.literal(Messages.whitelistKick()), Component.literal(Messages.whitelistLogOnly()));
                ci.cancel();
                return;
            }
            if (heos$shouldSilenceReason(text)) {
                ci.cancel();
            }
        }
    }

    //? if >= 1.21.2 {
    @Inject(method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaLostConnectionLog(DisconnectionDetails info, CallbackInfo ci) {
        if (info != null && info.reason() != null) {
            String text = info.reason().getString();
            if (heos$shouldSilenceReason(text)) {
                ci.cancel();
            }
        }
    }
    //?}

    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    private void rewriteInvalidSessionDisconnect(Component reason, CallbackInfo ci) {
        if (!heos$pendingPremiumVerification || reason == null) {
            return;
        }

        String message = reason.getString();
        if (message == null) {
            return;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("invalid session")
                || normalized.contains("failed to verify username")
                || normalized.contains("authentication servers are down")
                || normalized.contains("multiplayer.disconnect.authservers_down")) {
            heos$pendingPremiumVerification = false;
            if (heos$fallbackToPasswordLogin()) {
                ci.cancel();
                return;
            }
            heos$disconnectWithoutVanillaLogs(Component.literal(Messages.authServiceUnavailable()), Component.literal(Messages.offlineNameLogOnly()));
            ci.cancel();
        }
    }

    @Unique
    private void heos$disconnectWithoutVanillaLogs(Component playerMessage, Component internalReason) {
        connection.send(new ClientboundLoginDisconnectPacket(playerMessage));
        connection.disconnect(internalReason);
    }

    @Unique
    private boolean heos$rejectFailureLock(String username, String remoteIp) {
        if (!LoginFailureTracker.isBlocked(username, remoteIp)) {
            return false;
        }
        heos$disconnectWithoutVanillaLogs(
                Component.literal(LoginFailureTracker.blockMessage(username, remoteIp)),
                Component.literal("Heos login failure lock")
        );
        return true;
    }

    @Unique
    private boolean heos$rejectIpBan(String remoteIp) {
        if (!Heos.getConfig().enableCustomBan) {
            return false;
        }
        BanData.IpBanEntry ipBan = Heos.getBanData().getIpBan(remoteIp);
        if (ipBan == null) {
            return false;
        }
        String message = Messages.banIpMessage(ipBan.reason, TimeParser.formatAbsoluteTime(ipBan.expiryTime));
        ((ServerLoginPacketListenerImpl) (Object) this).disconnect(Component.literal(message));
        return true;
    }

    @Unique
    private boolean heos$rejectPlayerBan(String username) {
        BanData.BanEntry playerBan = Heos.getBanData().getPlayerBan(username, null);
        if (playerBan == null) {
            return false;
        }
        if (!Heos.getConfig().enableCustomBan && !Messages.isMigrationReason(playerBan.reason)) {
            return false;
        }

        String message = Messages.banMessage(playerBan.reason, TimeParser.formatAbsoluteTime(playerBan.expiryTime));
        if (Messages.isMigrationReason(playerBan.reason)) {
            HeosLogger.info(Messages.migrationBanAttemptLog(username));
            heos$disconnectWithoutVanillaLogs(Component.literal(message), Component.literal(Messages.migrationBanLogOnly()));
        } else {
            ((ServerLoginPacketListenerImpl) (Object) this).disconnect(Component.literal(message));
        }
        return true;
    }

    @Unique
    private boolean heos$routeExplicitOfflineName(String username) {
        if (MojangApi.isValidMojangUsername(username)) {
            return false;
        }
        if (!MojangApi.isAllowedOfflineUsername(username, Heos.getConfig().allowMoreOfflineUsernameCharacters)) {
            HeosLogger.info(Messages.invalidOfflineNameLog() + ": " + username);
            heos$disconnectWithoutVanillaLogs(Component.literal(Messages.offlineNameHint()), Component.literal(Messages.offlineNameLogOnly()));
            return true;
        }
        if (!Heos.getConfig().allowOfflinePlayers) {
            HeosLogger.info("Offline player is not allowed: " + username);
            heos$disconnectWithoutVanillaLogs(Component.literal(Messages.offlineNameHint()), Component.literal(Messages.offlineNameLogOnly()));
            return true;
        }

        HeosLogger.info("Offline player is using an allowed non-premium username: " + username);
        heos$acceptOfflineLogin(username);
        return true;
    }

    @Unique
    private boolean heos$shouldContinueVanillaPremiumFlow(String username) {
        PlayerData data = Heos.getPlayerData(username, true);
        if (data.isOnlineAccount && data.uuid != null) {
            HeosLogger.debug("Player " + username + " is cached as premium, continuing vanilla auth");
            return true;
        }

        MojangApi.LookupResult lookup = MojangApi.lookupAccount(username);
        if (lookup.type == MojangApi.LookupResultType.ERROR) {
            HeosLogger.warn("Mojang API lookup failed for " + username + ", refusing to guess account type");
            heos$disconnectWithoutVanillaLogs(Component.literal(Messages.authServiceUnavailable()), Component.literal(Messages.offlineNameLogOnly()));
            return false;
        }

        if (lookup.type == MojangApi.LookupResultType.NOT_FOUND) {
            if (Heos.getConfig().allowOfflinePlayers) {
                HeosLogger.info("Offline player is using an available non-premium username: " + username);
                heos$acceptOfflineLogin(username);
            } else {
                HeosLogger.info("Offline player is not allowed: " + username);
                heos$disconnectWithoutVanillaLogs(Component.literal(Messages.offlineNameHint()), Component.literal(Messages.offlineNameLogOnly()));
            }
            return false;
        }

        HeosLogger.info("Player " + username + " uses a premium name, deferring to vanilla authentication");
        return true;
    }

    @Unique
    private boolean heos$fallbackToPasswordLogin() {
        if (!Heos.getConfig().allowOfflinePlayers || heos$loginUsername == null || heos$loginUsername.isBlank()) {
            return false;
        }
        MojangApi.LookupResult lookup = MojangApi.lookupAccount(heos$loginUsername);
        if (lookup.type != MojangApi.LookupResultType.NOT_FOUND) {
            HeosLogger.warn("Premium authentication failed for " + heos$loginUsername + ", refusing offline fallback for "
                    + lookup.type + " Mojang account lookup");
            return false;
        }
        HeosLogger.warn("Premium authentication failed for non-premium name " + heos$loginUsername + ", falling back to Heos password login");
        heos$acceptOfflineLogin(heos$loginUsername);
        return true;
    }

    @Unique
    private void heos$acceptOfflineLogin(String username) {
        GameProfile offlineProfile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(username), username);
        //? if >= 1.20.5 {
        this.authenticatedProfile = offlineProfile;
        heos$finishLoginAndWaitForClient(offlineProfile);
        //?} else {
        /*this.gameProfile = offlineProfile;
        ((ServerLoginPacketListenerImpl) (Object) this).handleAcceptedLogin();
        *///?}
    }

    @Unique
    private String heos$extractRemoteIp() {
        String remote = getUserName();
        int slashIndex = remote.indexOf('/');
        if (slashIndex >= 0) {
            remote = remote.substring(slashIndex + 1);
        }
        int colonIndex = remote.indexOf(':');
        if (colonIndex >= 0) {
            remote = remote.substring(0, colonIndex);
        }
        return remote;
    }

    @Unique
    private boolean heos$shouldSilenceReason(String text) {
        if (text == null) {
            return false;
        }
        for (String reason : heos$SILENCED_REASONS) {
            if (reason.equals(text)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private boolean heos$isWhitelistDisconnect(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("not white-listed")
                || normalized.contains("not whitelisted")
                || normalized.contains("whitelist");
    }
}
