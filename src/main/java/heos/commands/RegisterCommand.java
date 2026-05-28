package heos.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import heos.Heos;
import heos.integrations.Permissions;
import heos.interfaces.PlayerAuth;
import heos.storage.PlayerData;
import heos.utils.HeosLogger;
import heos.utils.Messages;
import heos.utils.PasswordHasher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handles `/register`.
 */
public class RegisterCommand {
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(primaryNode());
        dispatcher.register(
            Commands.literal("reg")
                .requires(Permissions.require("heos.commands.register", true))
                .redirect(dispatcher.getRoot().getChild("register"))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> primaryNode() {
        return Commands.literal("register")
            .requires(Permissions.require("heos.commands.register", true))
            .then(Commands.argument("password", StringArgumentType.string())
                .then(Commands.argument("confirmPassword", StringArgumentType.string())
                    .executes(RegisterCommand::run)))
            .executes(context -> sendHint(context.getSource()));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return createAccount(
            context.getSource().getPlayerOrException(),
            StringArgumentType.getString(context, "password"),
            StringArgumentType.getString(context, "confirmPassword")
        );
    }

    public static int createAccount(ServerPlayer player, String password, String confirmPassword) {
        PlayerAuth auth = (PlayerAuth) player;
        String username = player.getName().getString();
        HeosLogger.info("Player " + username + " is trying to register");

        int gate = rejectUnavailableAttempt(player, auth, username);
        if (gate >= 0) {
            return gate;
        }

        PlayerData data = auth.heos$getPlayerData();
        if (data.isRegistered()) {
            HeosLogger.info("Player " + username + " is already registered");
            return reply(player, Messages.alreadyRegistered());
        }

        String validationError = validatePasswordInput(password, confirmPassword);
        if (validationError != null) {
            return reply(player, validationError);
        }

        String passwordHash = PasswordHasher.hashPassword(password);
        if (passwordHash == null) {
            HeosLogger.error("Failed to hash password for " + username);
            return reply(player, Messages.registerFailed());
        }

        persistRegistration(player, auth, data, passwordHash);
        auth.heos$setAuthenticated(true);
        player.sendSystemMessage(Component.literal(Messages.registerSuccess()), false);
        player.sendSystemMessage(Component.literal(Messages.keepPasswordSafe()), false);
        HeosLogger.info("Player " + username + " registered successfully");
        return 1;
    }

    public static int execute(ServerPlayer player, String password, String confirmPassword) {
        return createAccount(player, password, confirmPassword);
    }

    private static int rejectUnavailableAttempt(ServerPlayer player, PlayerAuth auth, String username) {
        if (auth.heos$isAuthenticated()) {
            HeosLogger.info("Player " + username + " is already authenticated");
            return reply(player, Messages.alreadyLoggedIn());
        }
        if (auth.heos$canSkipAuth()) {
            HeosLogger.info("Player " + username + " is premium, no need to register");
            return reply(player, Messages.premiumNoRegister());
        }
        return -1;
    }

    private static String validatePasswordInput(String password, String confirmPassword) {
        if (!password.equals(confirmPassword)) {
            return Messages.passwordMismatch();
        }
        if (password.length() < Heos.getConfig().minPasswordLength) {
            return Messages.passwordTooShort();
        }
        if (password.length() > Heos.getConfig().maxPasswordLength) {
            return Messages.passwordTooLong();
        }
        return null;
    }

    private static void persistRegistration(ServerPlayer player, PlayerAuth auth, PlayerData data, String passwordHash) {
        long now = System.currentTimeMillis();
        data.passwordHash = passwordHash;
        data.lastIp = auth.heos$getIpAddress();
        data.uuid = player.getUUID();
        data.registeredTime = now;
        data.lastLoginTime = now;
        data.save();
    }

    private static int sendHint(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(Messages.registerInputHint()));
        return 0;
    }

    private static int reply(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message), false);
        return 0;
    }
}
