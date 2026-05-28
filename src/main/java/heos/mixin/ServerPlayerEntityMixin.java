package heos.mixin;

import heos.Heos;
import heos.interfaces.PlayerAuth;
import heos.storage.PlayerData;
import heos.utils.HeosLogger;
import heos.utils.Messages;
import heos.utils.TpsDisplayService;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements PlayerAuth interface for ServerPlayerEntity
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin implements PlayerAuth {

    @Unique
    private boolean heos$authenticated = false;

    @Unique
    private boolean heos$canSkipAuth = false;

    @Unique
    private boolean heos$usingMojangAccount = false;

    @Unique
    private String heos$ipAddress = "";

    @Unique
    private Connection heos$connection = null;

    @Unique
    private int heos$clientProtocolVersion = SharedConstants.getProtocolVersion();

    @Unique
    private PlayerData heos$playerData = null;

    @Unique
    private long heos$kickTimer = Heos.getConfig().loginTimeout * 20L;

    @Unique
    private long heos$lastAuthPromptTick = -40;

    @Override
    public void heos$setAuthenticated(boolean authenticated) {
        this.heos$authenticated = authenticated;
        ServerPlayer player = (ServerPlayer) (Object) this;

        if (authenticated) {
            heos$lastAuthPromptTick = -40;
            HeosLogger.debug("Player authenticated: " + player.getName().getString());
            heos$kickTimer = Heos.getConfig().loginTimeout * 20L;
            heos$onAuthenticated();
            if (heos$isSameProtocol()) {
                player.level().getServer().getCommands().sendCommands(player);
            } else {
                HeosLogger.debug("Skipped command tree refresh for cross-protocol player " + player.getName().getString());
            }

            ServerLevel world = heos$getServerWorld(player);
            heos$clearNearbyMobTargets(player, world);
            if (!heos$isSameProtocol()) {
                HeosLogger.debug("Skipped post-auth block refresh for cross-protocol player " + player.getName().getString());
                return;
            }

            BlockPos pos = player.blockPosition();
            world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            world.sendBlockUpdated(pos.above(), world.getBlockState(pos.above()), world.getBlockState(pos.above()), 3);
        }
    }

    @Override
    public boolean heos$isAuthenticated() {
        return this.heos$authenticated;
    }

    @Override
    public boolean heos$canSkipAuth() {
        return this.heos$canSkipAuth;
    }

    @Override
    public void heos$setCanSkipAuth(boolean canSkip) {
        this.heos$canSkipAuth = canSkip;
    }

    @Override
    public boolean heos$isUsingMojangAccount() {
        return this.heos$usingMojangAccount;
    }

    @Override
    public void heos$setUsingMojangAccount(boolean usingMojang) {
        this.heos$usingMojangAccount = usingMojang;
    }

    @Override
    public String heos$getIpAddress() {
        return this.heos$ipAddress;
    }

    @Override
    public void heos$setIpAddress(Connection connection) {
        if (connection == null || connection.getRemoteAddress() == null) {
            this.heos$ipAddress = "";
            return;
        }

        String address = connection.getRemoteAddress().toString();
        if (address.contains("/")) {
            address = address.substring(address.indexOf('/') + 1);
        }
        if (address.contains(":")) {
            address = address.substring(0, address.indexOf(':'));
        }
        this.heos$ipAddress = address;
    }

    @Override
    public void heos$setConnection(Connection connection) {
        this.heos$connection = connection;
    }

    @Override
    public Connection heos$getConnection() {
        return this.heos$connection;
    }

    @Override
    public void heos$setIpAddress(String ipAddress) {
        this.heos$ipAddress = ipAddress;
    }

    @Override
    public int heos$getClientProtocolVersion() {
        return this.heos$clientProtocolVersion;
    }

    @Override
    public void heos$setClientProtocolVersion(int protocolVersion) {
        this.heos$clientProtocolVersion = protocolVersion;
    }

    @Override
    public PlayerData heos$getPlayerData() {
        return this.heos$playerData;
    }

    @Override
    public void heos$setPlayerData(PlayerData data) {
        this.heos$playerData = data;
    }

    @Override
    public void heos$sendAuthMessage() {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (heos$playerData == null) {
            return;
        }

        long currentTick = heos$getServerWorld(player).getGameTime();
        if (heos$lastAuthPromptTick >= 0 && currentTick - heos$lastAuthPromptTick < 40) {
            return;
        }
        heos$lastAuthPromptTick = currentTick;

        if (heos$playerData.isRegistered()) {
            player.sendSystemMessage(Component.literal(Messages.authPromptLogin()), false);
        } else {
            player.sendSystemMessage(Component.literal(Messages.authPromptRegister()), false);
        }
    }

    @Override
    public void heos$onAuthenticated() {
        ServerPlayer player = (ServerPlayer) (Object) this;
        HeosLogger.info("Player fully authenticated: " + player.getName().getString());
        heos$startTpsDisplay();
    }

    @Override
    public void heos$startTpsDisplay() {
        TpsDisplayService.start((ServerPlayer) (Object) this);
    }

    @Override
    public void heos$stopTpsDisplay() {
        TpsDisplayService.stop((ServerPlayer) (Object) this);
    }

    @Unique
    private ServerLevel heos$getServerWorld(ServerPlayer player) {
        //? if >= 1.21.6 {
        return (ServerLevel) player.level();
        //?} else {
        /*return player.serverLevel();
        *///?}
    }

    @Inject(method = "doTick()V", at = @At("HEAD"), cancellable = true)
    private void onPlayerTick(CallbackInfo ci) {
        if (!heos$authenticated) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            if (player.getClass() != ServerPlayer.class) {
                return;
            }

            if (heos$kickTimer <= 0 && player.connection.isAcceptingMessages()) {
                player.connection.disconnect(Component.literal(Messages.loginTimeout()));
            } else {
                if (heos$kickTimer % 200 == 0) {
                    heos$sendAuthMessage();
                }
                --heos$kickTimer;
            }

            ServerLevel world = heos$getServerWorld(player);
            heos$clearNearbyMobTargets(player, world);
            BlockPos pos = player.blockPosition();
            if (world.getBlockState(pos).getBlock().equals(Blocks.NETHER_PORTAL)
                    || world.getBlockState(pos.above()).getBlock().equals(Blocks.NETHER_PORTAL)) {
                player.randomTeleport(pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5, false);

                ClientboundBlockUpdatePacket feetPacket = new ClientboundBlockUpdatePacket(pos, Blocks.AIR.defaultBlockState());
                player.connection.send(feetPacket);

                ClientboundBlockUpdatePacket headPacket = new ClientboundBlockUpdatePacket(pos.above(), Blocks.AIR.defaultBlockState());
                player.connection.send(headPacket);
            }

            ci.cancel();
        }
    }

    @Unique
    private void heos$clearNearbyMobTargets(ServerPlayer player, ServerLevel world) {
        for (Mob mob : world.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16.0D), mob -> mob.getTarget() == player)) {
            mob.setTarget(null);
        }
    }

    @Inject(method = "restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at = @At("RETURN"))
    private void onCopyFrom(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        PlayerAuth oldAuth = (PlayerAuth) oldPlayer;
        PlayerAuth newAuth = (PlayerAuth) (Object) this;

        newAuth.heos$setAuthenticated(oldAuth.heos$isAuthenticated());
        newAuth.heos$setCanSkipAuth(oldAuth.heos$canSkipAuth());
        newAuth.heos$setUsingMojangAccount(oldAuth.heos$isUsingMojangAccount());
        newAuth.heos$setPlayerData(oldAuth.heos$getPlayerData());
        newAuth.heos$setConnection(oldAuth.heos$getConnection());
        newAuth.heos$setIpAddress(oldAuth.heos$getIpAddress());
        newAuth.heos$setClientProtocolVersion(oldAuth.heos$getClientProtocolVersion());
    }
}
