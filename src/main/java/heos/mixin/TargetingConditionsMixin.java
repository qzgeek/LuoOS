package heos.mixin;

import heos.interfaces.PlayerAuth;
//? if >= 1.21.2 {
import net.minecraft.server.level.ServerLevel;
//?}
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents mobs from choosing players who are still locked at login/register.
 */
@Mixin(TargetingConditions.class)
public abstract class TargetingConditionsMixin {
    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    //? if >= 1.21.2 {
    private void heos$ignoreUnauthenticatedPlayers(ServerLevel world, LivingEntity attacker, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
    //?} else {
    /*private void heos$ignoreUnauthenticatedPlayers(LivingEntity attacker, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
    *///?}
        if (target instanceof PlayerAuth auth && !auth.heos$isAuthenticated()) {
            cir.setReturnValue(false);
        }
    }
}
