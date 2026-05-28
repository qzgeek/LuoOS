package heos.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import heos.Heos;
import heos.integrations.Permissions;
import heos.storage.PlayerData;
import heos.storage.WhitelistData;
import heos.utils.HeosLogger;
import heos.utils.PasswordHasher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Admin commands for managing player accounts
 */
public class HeosAdminCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("heos")
                .requires(Permissions.requireLevel(3))
                .then(Commands.literal("resetpassword")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .then(Commands.argument("newPassword", StringArgumentType.string())
                            .executes(HeosAdminCommand::resetPassword)
                        )
                    )
                )
                .then(Commands.literal("info")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(HeosAdminCommand::info)
                    )
                )
                .then(Commands.literal("migrate")
                    .requires(source -> Heos.getConfig().enablePlayerDataMigration)
                    .then(Commands.argument("sourcePlayer", StringArgumentType.string())
                        .then(Commands.argument("targetPlayer", StringArgumentType.string())
                            .executes(MigrateCommand::prepareMigrate)
                        )
                    )
                )
                .then(Commands.literal("confirm-click")
                    .requires(source -> Heos.getConfig().enablePlayerDataMigration)
                    .then(Commands.argument("token", StringArgumentType.string())
                        .executes(MigrateCommand::confirmMigrateFromClick)
                    )
                )
                .then(Commands.literal("whitelist")
                    .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.string())
                            .executes(HeosAdminCommand::whitelistAdd)
                        )
                    )
                    .then(Commands.literal("remove")
                        .then(Commands.argument("player", StringArgumentType.string())
                            .executes(HeosAdminCommand::whitelistRemove)
                        )
                    )
                    .then(Commands.literal("list")
                        .executes(HeosAdminCommand::whitelistList)
                    )
                )
        );
    }

    private static int resetPassword(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String targetUsername = StringArgumentType.getString(context, "player");
        String newPassword = StringArgumentType.getString(context, "newPassword");
        return resetPassword(source, targetUsername, newPassword);
    }

    public static int resetPassword(CommandSourceStack source, String targetUsername, String newPassword) {
        if (newPassword.length() < Heos.getConfig().minPasswordLength) {
            source.sendFailure(Component.literal("New password is too short. Minimum length is " + Heos.getConfig().minPasswordLength + " characters"));
            return 0;
        }

        if (newPassword.length() > Heos.getConfig().maxPasswordLength) {
            source.sendFailure(Component.literal("New password is too long. Maximum length is " + Heos.getConfig().maxPasswordLength + " characters"));
            return 0;
        }

        PlayerData data = Heos.getPlayerData(targetUsername);
        if (!data.isRegistered()) {
            source.sendFailure(Component.literal("Player " + targetUsername + " is not registered"));
            return 0;
        }

        String newPasswordHash = PasswordHasher.hashPassword(newPassword);
        if (newPasswordHash == null) {
            source.sendFailure(Component.literal("Failed to reset password"));
            HeosLogger.error("Failed to hash password for " + targetUsername);
            return 0;
        }

        data.passwordHash = newPasswordHash;
        data.save();

        source.sendSuccess(() -> Component.literal("Reset password for player " + targetUsername), true);
        HeosLogger.info("Admin " + source.getTextName() + " reset password for " + targetUsername);

        ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(targetUsername);
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(Component.literal("================================="), false);
            targetPlayer.sendSystemMessage(Component.literal("Your password was reset by an administrator"), false);
            targetPlayer.sendSystemMessage(Component.literal("New password: " + newPassword), false);
            targetPlayer.sendSystemMessage(Component.literal("Please use /changepassword soon"), false);
            targetPlayer.sendSystemMessage(Component.literal("================================="), false);
        }

        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String targetUsername = StringArgumentType.getString(context, "player");

        PlayerData data = Heos.getPlayerData(targetUsername);
        if (!data.isRegistered()) {
            source.sendFailure(Component.literal("Player " + targetUsername + " is not registered"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("================================="), false);
        source.sendSuccess(() -> Component.literal("Player info: " + targetUsername), false);
        source.sendSuccess(() -> Component.literal("UUID: " + (data.uuid != null ? data.uuid.toString() : "unknown")), false);
        source.sendSuccess(() -> Component.literal("Last IP: " + (data.lastIp != null && !data.lastIp.isEmpty() ? data.lastIp : "unknown")), false);
        source.sendSuccess(() -> Component.literal("Registered at: " + (data.registeredTime > 0 ? new java.util.Date(data.registeredTime).toString() : "unknown")), false);
        source.sendSuccess(() -> Component.literal("Last login: " + (data.lastLoginTime > 0 ? new java.util.Date(data.lastLoginTime).toString() : "unknown")), false);
        source.sendSuccess(() -> Component.literal("Account type: " + (data.isOnlineAccount ? "premium" : "offline")), false);
        source.sendSuccess(() -> Component.literal("================================="), false);

        return 1;
    }

    private static int whitelistAdd(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "player");
        WhitelistData whitelistData = Heos.getWhitelistData();
        boolean addedHeos = whitelistData.add(username);

        if (addedHeos) {
            source.sendSuccess(() -> Component.literal("Added " + username + " to whitelist"), true);
            return 1;
        }

        source.sendFailure(Component.literal("Player is already in whitelist: " + username));
        return 0;
    }

    private static int whitelistRemove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "player");
        WhitelistData whitelistData = Heos.getWhitelistData();
        boolean removedHeos = whitelistData.remove(username);

        if (removedHeos) {
            source.sendSuccess(() -> Component.literal("Removed " + username + " from whitelist"), true);
            return 1;
        }

        source.sendFailure(Component.literal("Player is not in whitelist: " + username));
        return 0;
    }

    private static int whitelistList(CommandContext<CommandSourceStack> context) {
        WhitelistData whitelistData = Heos.getWhitelistData();
        context.getSource().sendSuccess(() -> Component.literal("Whitelist size: " + whitelistData.usernames.size()), false);
        if (!whitelistData.usernames.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(String.join(", ", whitelistData.usernames)), false);
        }
        return 1;
    }
}
