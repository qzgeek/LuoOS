package heos.bugfix.Ghost_Pearl.mixin;

//? if >= 1.21.2 {
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import heos.bugfix.Ghost_Pearl.GhostPearlFix;
import net.minecraft.world.entity.Entity;
//? if >= 1.21.11 {
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
//?} else {
/*import net.minecraft.world.entity.projectile.ThrownEnderpearl;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class GhostPearlEntitySaveMixin {
    @ModifyReturnValue(method = "method_31746", at = @At("RETURN"), remap = false, require = 1)
    private boolean heos$doNotSavePlayerManagedPearlsToChunk(boolean original) {
        if (!original || !((Object) this instanceof ThrownEnderpearl pearl)) {
            return original;
        }
        return GhostPearlFix.shouldSaveToChunk(pearl);
    }
}
//?}
