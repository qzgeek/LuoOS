package heos.integrations;

import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
//? if >= 1.21.11 {
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
//?}

public class Permissions {

    public static @NotNull Predicate<CommandSourceStack> require(@NotNull String permission, boolean defaultValue) {
        return source -> defaultValue;
    }

    public static @NotNull Predicate<CommandSourceStack> requireLevel(int level) {
        //? if >= 1.21.11 {
        return source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
        //?} else {
        /*return source -> source.hasPermission(level);
        *///?}
    }
}
