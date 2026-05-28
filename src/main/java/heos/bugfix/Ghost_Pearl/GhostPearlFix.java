package heos.bugfix.Ghost_Pearl;

//? if >= 1.21.2 {
import heos.bugfix.Ghost_Pearl.mixin.GhostPearlProjectileAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
//? if >= 1.21.6 {
import net.minecraft.world.entity.EntityReference;
//?}
//? if >= 1.21.11 {
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
//?} else {
/*import net.minecraft.world.entity.projectile.ThrownEnderpearl;
*///?}

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class GhostPearlFix {
    private static final Map<UUID, Set<UUID>> PEARLS_SAVED_WITH_PLAYER = new ConcurrentHashMap<>();

    private GhostPearlFix() {
    }

    public static boolean hasLivePearlWithSameUuid(ServerPlayer player, ThrownEnderpearl pearl) {
        if (pearl == null) {
            return false;
        }

        cleanupPearls(player);
        return hasRegisteredPearlWithSameUuid(player, pearl);
    }

    public static void discardDuplicatePearl(ServerPlayer player, ThrownEnderpearl pearl) {
        if (pearl == null) {
            return;
        }

        cleanupPearls(player);
        if (hasRegisteredPearlWithSameUuid(player, pearl) && player.getEnderPearls().contains(pearl)) {
            pearl.discard();
        }
    }

    private static boolean hasRegisteredPearlWithSameUuid(ServerPlayer player, ThrownEnderpearl pearl) {
        UUID uuid = pearl.getUUID();
        for (ThrownEnderpearl existing : player.getEnderPearls()) {
            if (existing != pearl && !existing.isRemoved() && existing.getUUID().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public static void cleanupPearls(ServerPlayer player) {
        Set<UUID> seen = new HashSet<>();
        Iterator<ThrownEnderpearl> iterator = player.getEnderPearls().iterator();
        while (iterator.hasNext()) {
            ThrownEnderpearl pearl = iterator.next();
            if (!isValidPearlForPlayer(player, pearl) || !seen.add(pearl.getUUID())) {
                iterator.remove();
            }
        }
    }

    public static void rememberPearlsSavedWithPlayer(ServerPlayer player) {
        cleanupPearls(player);
        Set<UUID> pearlUuids = ConcurrentHashMap.newKeySet();
        for (ThrownEnderpearl pearl : player.getEnderPearls()) {
            pearlUuids.add(pearl.getUUID());
        }

        if (pearlUuids.isEmpty()) {
            PEARLS_SAVED_WITH_PLAYER.remove(player.getUUID());
        } else {
            PEARLS_SAVED_WITH_PLAYER.put(player.getUUID(), pearlUuids);
        }
    }

    public static void removePearlReferences(ServerPlayer player, ThrownEnderpearl pearl) {
        if (pearl == null) {
            cleanupPearls(player);
            return;
        }

        UUID uuid = pearl.getUUID();
        boolean pearlWasRegistered = player.getEnderPearls().contains(pearl);
        player.getEnderPearls().removeIf(existing ->
                existing == pearl
                        || existing.isRemoved()
                        || (pearlWasRegistered && existing.getUUID().equals(uuid))
        );
    }

    private static boolean isValidPearlForPlayer(ServerPlayer player, ThrownEnderpearl pearl) {
        if (pearl == null || pearl.isRemoved()) {
            return false;
        }

        Entity owner = pearl.getOwner();
        return owner == player;
    }

    public static boolean shouldSaveToChunk(ThrownEnderpearl pearl) {
        if (pearl == null || pearl.isRemoved()) {
            return true;
        }

        UUID ownerUuid = getOwnerUuid(pearl);
        if (ownerUuid == null) {
            return true;
        }

        MinecraftServer server = pearl.level().getServer();
        if (server == null) {
            return true;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(ownerUuid);
        if (player == null) {
            return !wasSavedWithPlayer(ownerUuid, pearl.getUUID());
        }

        cleanupPearls(player);
        return !player.getEnderPearls().contains(pearl) && !hasRegisteredPearlWithSameUuid(player, pearl);
    }

    private static boolean wasSavedWithPlayer(UUID ownerUuid, UUID pearlUuid) {
        Set<UUID> pearlUuids = PEARLS_SAVED_WITH_PLAYER.get(ownerUuid);
        return pearlUuids != null && pearlUuids.contains(pearlUuid);
    }

    private static UUID getOwnerUuid(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        if (owner != null) {
            return owner.getUUID();
        }

        //? if >= 1.21.6 {
        EntityReference<Entity> ownerReference = ((GhostPearlProjectileAccessor) pearl).heos$getOwner();
        return ownerReference == null ? null : ownerReference.getUUID();
        //?} else {
        /*return ((GhostPearlProjectileAccessor) pearl).heos$getOwner();
        *///?}
    }
}
//?}
