package heos.commands;

import heos.integrations.Permissions;
import heos.interfaces.PlayerAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SensitiveCommandHandler {
    private SensitiveCommandHandler() {
    }

    public static InteractionResult handle(ServerPlayer player, String command) {
        List<String> parts = split(command);
        if (parts.isEmpty()) {
            return InteractionResult.PASS;
        }

        String root = parts.get(0).toLowerCase(Locale.ROOT);
        if ("login".equals(root) || "l".equals(root)) {
            if (parts.size() != 2) {
                player.sendSystemMessage(Component.literal("Usage: /login <password>"), false);
                return InteractionResult.FAIL;
            }
            LoginCommand.execute(player, parts.get(1));
            return InteractionResult.FAIL;
        }

        if ("register".equals(root) || "reg".equals(root)) {
            if (parts.size() != 3) {
                player.sendSystemMessage(Component.literal("Usage: /register <password> <confirmPassword>"), false);
                return InteractionResult.FAIL;
            }
            RegisterCommand.execute(player, parts.get(1), parts.get(2));
            return InteractionResult.FAIL;
        }

        if ("changepassword".equals(root) || "changepw".equals(root)) {
            if (!((PlayerAuth) player).heos$isAuthenticated()) {
                return InteractionResult.PASS;
            }
            if (parts.size() != 3) {
                player.sendSystemMessage(Component.literal("Usage: /changepassword <oldPassword> <newPassword>"), false);
                return InteractionResult.FAIL;
            }
            ChangePasswordCommand.execute(player, parts.get(1), parts.get(2));
            return InteractionResult.FAIL;
        }

        if ("heos".equals(root) && parts.size() >= 2 && "resetpassword".equalsIgnoreCase(parts.get(1))) {
            if (!Permissions.requireLevel(3).test(player.createCommandSourceStack())) {
                player.sendSystemMessage(Component.literal("You do not have permission to use this command"), false);
                return InteractionResult.FAIL;
            }
            if (parts.size() != 4) {
                player.sendSystemMessage(Component.literal("Usage: /heos resetpassword <player> <newPassword>"), false);
                return InteractionResult.FAIL;
            }
            HeosAdminCommand.resetPassword(player.createCommandSourceStack(), parts.get(2), parts.get(3));
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    private static List<String> split(String command) {
        List<String> parts = new ArrayList<>();
        for (String part : command.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }
}
