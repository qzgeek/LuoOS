package heos.bugfix.Ghost_Pearl.mixin;

//? if >= 1.21.2 {
import net.minecraft.world.entity.Entity;
//? if >= 1.21.6 {
import net.minecraft.world.entity.EntityReference;
//?} else {
/*import java.util.UUID;
*///?}
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Projectile.class)
public interface GhostPearlProjectileAccessor {
    //? if >= 1.21.6 {
    @Accessor("owner")
    EntityReference<Entity> heos$getOwner();
    //?} else {
    /*@Accessor("ownerUUID")
    UUID heos$getOwner();
    *///?}
}
//?}
