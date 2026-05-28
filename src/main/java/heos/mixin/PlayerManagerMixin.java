package heos.mixin;

import com.mojang.authlib.GameProfile;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import heos.Heos;
import heos.interfaces.ConnectionProtocolInfo;
import heos.interfaces.PlayerAuth;
import heos.storage.PlayerData;
import heos.utils.HeosLogger;
import heos.utils.Messages;
import heos.utils.ProtocolCompatibility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
//? if >= 1.21.11 {
import net.minecraft.server.players.NameAndId;
//?}
//? if >= 1.20.5 {
import net.minecraft.server.network.CommonListenerCookie;
//?}
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.util.Locale;

/**
 * Handles player join and leave events
 */
@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    //? if >= 1.20.5 {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void onPlayerJoin(Connection connection, ServerPlayer player, CommonListenerCookie clientData, CallbackInfo ci) {
        heos$handlePlayerJoin(connection, player);
    }
    //?} else {
    /*@Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void onPlayerJoin(Connection connection, ServerPlayer player, CallbackInfo ci) {
        heos$handlePlayerJoin(connection, player);
    }
    *///?}

    private void heos$handlePlayerJoin(Connection connection, ServerPlayer player) {
        PlayerAuth authState = (PlayerAuth) player;
        String username = player.getName().getString();

        if (heos$isSyntheticPlayer(player)) {
            heos$acceptSyntheticPlayer(authState, username);
            return;
        }

        boolean onlineSession = heos$isVerifiedOnlineSession(player, username);
        PlayerData storedData = Heos.getPlayerData(username, onlineSession);
        heos$prepareSession(authState, connection, storedData, username);
        if (heos$exceedsIpSessionLimit(player)) {
            player.connection.disconnect(Component.literal(Heos.getConfig().sessionLimitKickMessage));
            return;
        }

        if (!Heos.getConfig().enableAuthentication) {
            heos$acceptWithoutPassword(authState, username);
            return;
        }

        if (onlineSession) {
            heos$acceptPremiumPlayer(player, authState, storedData, username);
            return;
        }

        heos$requirePasswordLogin(player, authState, storedData, username);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void onPlayerLeave(ServerPlayer player, CallbackInfo ci) {
        String username = player.getName().getString();
        ((PlayerAuth) player).heos$stopTpsDisplay();
        HeosLogger.debug("Player " + username + " left the server");
    }

    //? if >= 1.21.11 {
    @Inject(method = "canPlayerLogin", at = @At("TAIL"), cancellable = true)
    private void heos$checkWhitelist(SocketAddress address, NameAndId profile, CallbackInfoReturnable<Component> cir) {
        if (profile != null) {
            heos$applyWhitelistDecision(profile.name(), cir);
        }
    }
    //?} else {
    /*@Inject(method = "canPlayerLogin", at = @At("TAIL"), cancellable = true)
    private void heos$checkWhitelist(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        if (profile != null) {
            heos$applyWhitelistDecision(profile.getName(), cir);
        }
    }
    *///?}

    //? if >= 1.21.11 {
    @ModifyReturnValue(method = "isWhiteListed", at = @At("RETURN"))
    private boolean heos$allowHeosWhitelistedPlayers(boolean original, NameAndId profile) {
        return original || (profile != null && Heos.getWhitelistData().isWhitelisted(profile.name()));
    }
    //?} else {
    /*@ModifyReturnValue(method = "isWhiteListed", at = @At("RETURN"))
    private boolean heos$allowHeosWhitelistedPlayers(boolean original, GameProfile profile) {
        return original || (profile != null && Heos.getWhitelistData().isWhitelisted(profile.getName()));
    }
    *///?}

    private boolean heos$exceedsIpSessionLimit(ServerPlayer player) {
        int maxSessions = Heos.getConfig().maxConcurrentSessionsPerIp;
        if (maxSessions <= 0) {
            return false;
        }
        String ip = ((PlayerAuth) player).heos$getIpAddress();
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        int sessions = 0;
        for (ServerPlayer onlinePlayer : player.level().getServer().getPlayerList().getPlayers()) {
            if (ip.equals(((PlayerAuth) onlinePlayer).heos$getIpAddress())) {
                sessions++;
            }
        }
        return sessions > maxSessions;
    }

    private void heos$prepareSession(PlayerAuth authState, Connection connection, PlayerData storedData, String username) {
        authState.heos$setPlayerData(storedData);
        authState.heos$setConnection(connection);
        authState.heos$setIpAddress(connection);
        ConnectionProtocolInfo connectionInfo = (ConnectionProtocolInfo) connection;
        int handshakeProtocol = connectionInfo.heos$getClientProtocolVersion();
        int resolvedProtocol = ProtocolCompatibility.resolveClientProtocol((ServerPlayer) authState, handshakeProtocol);
        authState.heos$setClientProtocolVersion(resolvedProtocol);
        connectionInfo.heos$setClientProtocolVersion(resolvedProtocol);
        connectionInfo.heos$setDebugPlayerName(username);
    }

    private boolean heos$isSyntheticPlayer(ServerPlayer player) {
        return player.getClass() != ServerPlayer.class;
    }

    private void heos$acceptSyntheticPlayer(PlayerAuth authState, String username) {
        heos$markAuthenticationState(authState, true, false, true);
        HeosLogger.info("Fake/mod player " + username + " joined, authentication skipped");
    }

    private void heos$acceptWithoutPassword(PlayerAuth authState, String username) {
        heos$markAuthenticationState(authState, true, false, true);
        HeosLogger.info("Authentication disabled, player " + username + " joined without auth");
    }

    private boolean heos$isVerifiedOnlineSession(ServerPlayer player, String username) {
        return player.level().getServer().usesAuthentication()
                && !player.getUUID().equals(UUIDUtil.createOfflinePlayerUUID(username));
    }

    private void heos$acceptPremiumPlayer(ServerPlayer player, PlayerAuth authState, PlayerData storedData, String username) {
        heos$syncIdentity(storedData, true, player.getUUID());
        heos$markAuthenticationState(authState, true, true, true);
        HeosLogger.info("Premium player " + username + " joined, authentication skipped");
        if (authState.heos$isSameProtocol()) {
            player.sendSystemMessage(Component.literal(Messages.premiumWelcome()), false);
        } else {
            HeosLogger.debug("Skipped premium welcome message for cross-protocol player " + username);
        }
    }

    private void heos$requirePasswordLogin(ServerPlayer player, PlayerAuth authState, PlayerData storedData, String username) {
        heos$syncIdentity(storedData, false, player.getUUID());
        heos$markAuthenticationState(authState, false, false, false);
        HeosLogger.info("Offline player " + username + " joined, authentication required");
        player.level().getServer().execute(authState::heos$sendAuthMessage);
    }

    private void heos$syncIdentity(PlayerData storedData, boolean onlineAccount, java.util.UUID currentUuid) {
        if (storedData.isOnlineAccount == onlineAccount && currentUuid.equals(storedData.uuid)) {
            return;
        }
        storedData.isOnlineAccount = onlineAccount;
        storedData.uuid = currentUuid;
        storedData.save();
    }

    private void heos$markAuthenticationState(PlayerAuth authState, boolean canSkipAuth, boolean usingMojangAccount, boolean authenticated) {
        authState.heos$setCanSkipAuth(canSkipAuth);
        authState.heos$setUsingMojangAccount(usingMojangAccount);
        authState.heos$setAuthenticated(authenticated);
    }

    private void heos$applyWhitelistDecision(String username, CallbackInfoReturnable<Component> cir) {
        if (username == null || username.isBlank()) {
            return;
        }

        boolean heosWhitelisted = Heos.getWhitelistData().isWhitelisted(username);
        Component currentDecision = cir.getReturnValue();
        if (heosWhitelisted && heos$isVanillaWhitelistDenial(currentDecision)) {
            cir.setReturnValue(null);
            HeosLogger.debug("Allowed " + username + " through vanilla whitelist by Heos whitelist");
            return;
        }

        if (Heos.getConfig().enableWhitelist && !heosWhitelisted && currentDecision == null) {
            cir.setReturnValue(Component.literal(Messages.whitelistKick()));
            HeosLogger.info(Messages.whitelistDeniedLog(username));
        }
    }

    private boolean heos$isVanillaWhitelistDenial(Component decision) {
        if (decision == null) {
            return false;
        }
        String normalized = decision.getString().toLowerCase(Locale.ROOT);
        return normalized.contains("not white-listed")
                || normalized.contains("not whitelisted")
                || normalized.contains("whitelist");
    }
}
