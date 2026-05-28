package heos.mixin;

import heos.interfaces.PlayerAuth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks health loss while a player is still waiting for login or registration.
 */
@Mixin(Player.class)
public abstract class PlayerDamageGateMixin extends LivingEntity {
    protected PlayerDamageGateMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
    private void heos$blockDamageUntilAuthenticated(ServerLevel serverLevel, DamageSource source, float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer && (Object) this instanceof PlayerAuth auth && !auth.heos$isAuthenticated()) {
            ci.cancel();
        }
    }
}
