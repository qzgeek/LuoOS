package heos.mixin;

import com.mojang.authlib.GameProfile;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import heos.Heos;
//? if >= 1.21.11 {
import net.minecraft.server.players.NameAndId;
//?}
import net.minecraft.server.players.UserWhiteList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(UserWhiteList.class)
public abstract class UserWhiteListMixin {
    //? if >= 1.21.11 {
    @ModifyReturnValue(method = "isWhiteListed", at = @At("RETURN"))
    private boolean heos$allowFromHeosWhitelist(boolean original, NameAndId profile) {
        return original || (profile != null && Heos.getWhitelistData().isWhitelisted(profile.name()));
    }
    //?} else {
    /*@ModifyReturnValue(method = "isWhiteListed", at = @At("RETURN"))
    private boolean heos$allowFromHeosWhitelist(boolean original, GameProfile profile) {
        return original || (profile != null && Heos.getWhitelistData().isWhitelisted(profile.getName()));
    }
    *///?}
}
