package heos.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import heos.integrations.Permissions;
import heos.interfaces.PlayerAuth;
import heos.storage.PlayerData;
import heos.utils.HeosLogger;
import heos.utils.LoginFailureTracker;
import heos.utils.Messages;
import heos.utils.PasswordHasher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handles `/login`.
 */
public class LoginCommand {
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(primaryNode());
        dispatcher.register(
            Commands.literal("l")
                .requires(Permissions.require("heos.commands.login", true))
                .redirect(dispatcher.getRoot().getChild("login"))
        );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> primaryNode() {
        return Commands.literal("login")
            .requires(Permissions.require("heos.commands.login", true))
            .then(Commands.argument("password", StringArgumentType.string())
                .executes(LoginCommand::run))
            .executes(context -> sendHint(context.getSource()));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return authenticate(
            context.getSource().getPlayerOrException(),
            StringArgumentType.getString(context, "password")
        );
    }

    public static int authenticate(ServerPlayer player, String password) {
        PlayerAuth auth = (PlayerAuth) player;
        String username = player.getName().getString();
        String ipAddress = auth.heos$getIpAddress();
        HeosLogger.info("Player " + username + " is trying to login");

        if (LoginFailureTracker.isBlocked(username, ipAddress)) {
            return disconnect(player, LoginFailureTracker.blockMessage(username, ipAddress));
        }

        int gate = rejectUnavailableAttempt(player, auth, username);
        if (gate >= 0) {
            return gate;
        }

        PlayerData data = auth.heos$getPlayerData();
        if (!data.isRegistered()) {
            HeosLogger.info("Player " + username + " is not registered");
            return reply(player, Messages.notRegistered());
        }

        if (!PasswordHasher.verifyPassword(password, data.passwordHash)) {
            HeosLogger.warn("Player " + username + " provided wrong password");
            if (LoginFailureTracker.recordFailure(username, ipAddress)) {
                return disconnect(player, LoginFailureTracker.blockMessage(username, ipAddress));
            }
            return reply(player, Messages.wrongPassword());
        }

        return completeLogin(player, auth, data, password, username, ipAddress);
    }

    public static int execute(ServerPlayer player, String password) {
        return authenticate(player, password);
    }

    private static int rejectUnavailableAttempt(ServerPlayer player, PlayerAuth auth, String username) {
        if (auth.heos$isAuthenticated()) {
            HeosLogger.info("Player " + username + " is already authenticated");
            return reply(player, Messages.alreadyLoggedIn());
        }
        if (auth.heos$canSkipAuth()) {
            HeosLogger.info("Player " + username + " is premium, no need to login");
            return reply(player, Messages.premiumNoLogin());
        }
        return -1;
    }

    private static int completeLogin(ServerPlayer player, PlayerAuth auth, PlayerData data, String password, String username, String ipAddress) {
        HeosLogger.info("Player " + username + " provided correct password");
        LoginFailureTracker.reset(username, ipAddress);
        auth.heos$setAuthenticated(true);
        maybeUpgradeHash(data, password);
        data.lastIp = auth.heos$getIpAddress();
        data.lastLoginTime = System.currentTimeMillis();
        data.save();
        HeosLogger.info("Player " + username + " logged in successfully");
        return reply(player, Messages.loginSuccess(), 1);
    }

    private static void maybeUpgradeHash(PlayerData data, String password) {
        if (!PasswordHasher.needsRehash(data.passwordHash)) {
            return;
        }
        String upgradedHash = PasswordHasher.hashPassword(password);
        if (upgradedHash != null) {
            data.passwordHash = upgradedHash;
        }
    }

    private static int sendHint(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(Messages.loginInputHint()));
        return 0;
    }

    private static int reply(ServerPlayer player, String message) {
        return reply(player, message, 0);
    }

    private static int reply(ServerPlayer player, String message, int result) {
        player.sendSystemMessage(Component.literal(message), false);
        return result;
    }

    private static int disconnect(ServerPlayer player, String message) {
        player.connection.disconnect(Component.literal(message));
        return 0;
    }
}
