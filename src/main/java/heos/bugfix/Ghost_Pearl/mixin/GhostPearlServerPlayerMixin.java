package heos.bugfix.Ghost_Pearl.mixin;

//? if >= 1.21.2 {
import heos.bugfix.Ghost_Pearl.GhostPearlFix;
import net.minecraft.server.level.ServerPlayer;
//? if >= 1.21.11 {
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
//?} else {
/*import net.minecraft.world.entity.projectile.ThrownEnderpearl;
*///?}
//? if >= 1.21.6 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*///?}
//? if < 1.21.5 {
/*import java.util.Optional;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class GhostPearlServerPlayerMixin {
    @Inject(method = "registerEnderPearl", at = @At("HEAD"), cancellable = true)
    private void heos$preventDuplicateEnderPearlRegistration(ThrownEnderpearl pearl, CallbackInfo ci) {
        if (GhostPearlFix.hasLivePearlWithSameUuid((ServerPlayer) (Object) this, pearl)) {
            GhostPearlFix.discardDuplicatePearl((ServerPlayer) (Object) this, pearl);
            ci.cancel();
        }
    }

    @Inject(method = "deregisterEnderPearl", at = @At("TAIL"))
    private void heos$removeGhostPearlReferences(ThrownEnderpearl pearl, CallbackInfo ci) {
        GhostPearlFix.removePearlReferences((ServerPlayer) (Object) this, pearl);
    }

    @Inject(method = "saveEnderPearls", at = @At("HEAD"))
    //? if >= 1.21.6 {
    private void heos$cleanupGhostPearlsBeforeSaving(ValueOutput output, CallbackInfo ci) {
    //?} else {
    /*private void heos$cleanupGhostPearlsBeforeSaving(CompoundTag output, CallbackInfo ci) {
    *///?}
        GhostPearlFix.rememberPearlsSavedWithPlayer((ServerPlayer) (Object) this);
    }

    //? if >= 1.21.5 {
    @Inject(method = "loadAndSpawnEnderPearls", at = @At("RETURN"))
    //?} else {
    /*@Inject(method = "loadAndSpawnEnderpearls", at = @At("RETURN"))
    *///?}
    //? if >= 1.21.6 {
    private void heos$cleanupGhostPearlsAfterLoading(ValueInput input, CallbackInfo ci) {
    //?} else if < 1.21.5 {
    /*private void heos$cleanupGhostPearlsAfterLoading(Optional<CompoundTag> input, CallbackInfo ci) {
    *///?} else {
    /*private void heos$cleanupGhostPearlsAfterLoading(CompoundTag input, CallbackInfo ci) {
    *///?}
        GhostPearlFix.cleanupPearls((ServerPlayer) (Object) this);
    }
}
//?}
