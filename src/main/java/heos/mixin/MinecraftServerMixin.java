package heos.mixin;

import heos.utils.BanCleanupService;
import heos.utils.TpsDisplayService;
import heos.utils.TpsTracker;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks server ticks and refreshes TPS displays.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void heos$onTickStart(CallbackInfo ci) {
        TpsTracker.onServerTickStart();
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void heos$onTickEnd(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        TpsTracker.onServerTickEnd(server);
        TpsDisplayService.tick(server);
        BanCleanupService.tick(server);
    }
}
