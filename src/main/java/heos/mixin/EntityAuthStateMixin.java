package heos.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import heos.interfaces.PlayerAuth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies pre-login visibility and damage protection to players carrying Heos auth state.
 */
@Mixin(Entity.class)
public abstract class EntityAuthStateMixin {
    @ModifyReturnValue(method = "isInvisible()Z", at = @At("RETURN"))
    private boolean heos$hideUnauthenticatedPlayers(boolean original) {
        return original || heos$isWaitingForLogin();
    }

    @ModifyReturnValue(method = "isInvulnerable()Z", at = @At("RETURN"))
    private boolean heos$protectUnauthenticatedPlayers(boolean original) {
        return original || heos$isWaitingForLogin();
    }

    private boolean heos$isWaitingForLogin() {
        return (Object) this instanceof PlayerAuth auth && !auth.heos$isAuthenticated();
    }
}
