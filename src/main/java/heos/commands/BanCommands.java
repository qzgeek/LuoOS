package heos.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import heos.Heos;
import heos.integrations.Permissions;
import heos.storage.BanData;
import heos.utils.HeosLogger;
import heos.utils.Messages;
import heos.utils.TimeParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("unused")
public class BanCommands {
    private static final String DEFAULT_BAN_REASON = "\u4f60\u88ab\u5c01\u7981\u4e86";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Heos.getConfig().enableCustomBan) {
            dispatcher.register(customUnbanCommand());
            dispatcher.register(customUnbanIpCommand());
            HeosLogger.info("Custom ban disabled, keeping vanilla ban commands");
            return;
        }

        removeVanillaBanCommands(dispatcher);

        dispatcher.register(customBanCommand());
        dispatcher.register(customBanIpCommand());
        dispatcher.register(customUnbanCommand());
        dispatcher.register(customUnbanIpCommand());
        dispatcher.register(
            Commands.literal("banlist")
                .requires(Permissions.requireLevel(3))
                .executes(BanCommands::listBans)
        );
        HeosLogger.info("Registered custom ban commands");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> customBanCommand() {
        return Commands.literal("ban")
                .requires(Permissions.requireLevel(3))
                .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> banPlayer(ctx, null, DEFAULT_BAN_REASON))
                        .then(Commands.argument("durationOrReason", StringArgumentType.string())
                                .executes(ctx -> banPlayerFlexible(ctx,
                                        StringArgumentType.getString(ctx, "durationOrReason"),
                                        null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> banPlayerFlexible(ctx,
                                                StringArgumentType.getString(ctx, "durationOrReason"),
                                                StringArgumentType.getString(ctx, "reason")))
                                )
                        )
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> customUnbanCommand() {
        return Commands.literal("unban")
                .requires(Permissions.requireLevel(3))
                .then(Commands.argument("target", StringArgumentType.string())
                        .executes(BanCommands::unbanPlayer)
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> customUnbanIpCommand() {
        return Commands.literal("unban-ip")
                .requires(Permissions.requireLevel(3))
                .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(BanCommands::unbanIp)
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> customBanIpCommand() {
        return Commands.literal("ban-ip")
                .requires(Permissions.requireLevel(3))
                .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(ctx -> banIp(ctx, null, DEFAULT_BAN_REASON))
                        .then(Commands.argument("durationOrReason", StringArgumentType.string())
                                .executes(ctx -> banIpFlexible(ctx,
                                        StringArgumentType.getString(ctx, "durationOrReason"),
                                        null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> banIpFlexible(ctx,
                                                StringArgumentType.getString(ctx, "durationOrReason"),
                                                StringArgumentType.getString(ctx, "reason")))
                                )
                        )
                );
    }

    private static int banPlayerFlexible(CommandContext<CommandSourceStack> context, String durationOrReason, String reasonTail) {
        ParsedBanArgs args = parseBanArgs(durationOrReason, reasonTail);
        return banPlayer(context, args.timeStr, args.reason);
    }

    private static int banPlayer(CommandContext<CommandSourceStack> context, String timeStr, String reason) {
        CommandSourceStack source = context.getSource();
        String targetUsername = StringArgumentType.getString(context, "player");

        long expiryTime = -1;
        if (timeStr != null) {
            expiryTime = TimeParser.parseTime(timeStr);
            if (expiryTime == -2) {
                source.sendFailure(Component.literal("Invalid time format. Use: 15s, 3m, 24h, 7d, 1y or -1"));
                return 0;
            }
        }

        ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(targetUsername);
        UUID targetUuid = targetPlayer != null ? targetPlayer.getUUID() : null;

        BanData banData = Heos.getBanData();
        banData.addPlayerBan(targetUsername, targetUuid, reason, expiryTime, source.getTextName());

        final String timeInfo = TimeParser.formatExpiryTime(expiryTime);
        source.sendSuccess(() -> Component.literal("Banned player " + targetUsername), true);
        source.sendSuccess(() -> Component.literal("Reason: " + reason), false);
        source.sendSuccess(() -> Component.literal("Duration: " + timeInfo), false);

        HeosLogger.info(source.getTextName() + " banned " + targetUsername + " for " + timeInfo + " - Reason: " + reason);

        if (targetPlayer != null) {
            String kickMessage = Messages.banMessage(reason, TimeParser.formatAbsoluteTime(expiryTime));
            targetPlayer.connection.disconnect(Component.literal(kickMessage));
        }

        return 1;
    }

    static int banPlayerProgrammatic(String username, UUID uuid, String reason, long expiryTime, String bannedBy) {
        BanData banData = Heos.getBanData();
        banData.addPlayerBan(username, uuid, reason, expiryTime, bannedBy);
        return 1;
    }

    private static int banIpFlexible(CommandContext<CommandSourceStack> context, String durationOrReason, String reasonTail) {
        ParsedBanArgs args = parseBanArgs(durationOrReason, reasonTail);
        return banIp(context, args.timeStr, args.reason);
    }

    private static int banIp(CommandContext<CommandSourceStack> context, String timeStr, String reason) {
        CommandSourceStack source = context.getSource();
        String targetIp = StringArgumentType.getString(context, "ip");

        long expiryTime = -1;
        if (timeStr != null) {
            expiryTime = TimeParser.parseTime(timeStr);
            if (expiryTime == -2) {
                source.sendFailure(Component.literal("Invalid time format. Use: 15s, 3m, 24h, 7d, 1y or -1"));
                return 0;
            }
        }

        BanData banData = Heos.getBanData();
        banData.addIpBan(targetIp, reason, expiryTime, source.getTextName());

        final String timeInfo = TimeParser.formatExpiryTime(expiryTime);
        source.sendSuccess(() -> Component.literal("Banned IP " + targetIp), true);
        source.sendSuccess(() -> Component.literal("Reason: " + reason), false);
        source.sendSuccess(() -> Component.literal("Duration: " + timeInfo), false);

        HeosLogger.info(source.getTextName() + " banned IP " + targetIp + " for " + timeInfo + " - Reason: " + reason);

        String kickMessage = Messages.banIpMessage(reason, TimeParser.formatAbsoluteTime(expiryTime));
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (targetIp.equals(player.getIpAddress())) {
                player.connection.disconnect(Component.literal(kickMessage));
            }
        }

        return 1;
    }

    private static ParsedBanArgs parseBanArgs(String durationOrReason, String reasonTail) {
        if (durationOrReason == null) {
            return new ParsedBanArgs(null, DEFAULT_BAN_REASON);
        }

        if (TimeParser.parseTime(durationOrReason) != -2) {
            return new ParsedBanArgs(durationOrReason, reasonTail == null ? DEFAULT_BAN_REASON : reasonTail);
        }

        String reason = reasonTail == null ? durationOrReason : durationOrReason + " " + reasonTail;
        return new ParsedBanArgs(null, reason);
    }

    private static int unbanPlayer(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String target = StringArgumentType.getString(context, "target");

        BanData banData = Heos.getBanData();
        boolean removedPlayer = banData.removePlayerBan(target);
        boolean removedIp = banData.removeIpBan(target);
        if (removedPlayer || removedIp) {
            source.sendSuccess(() -> Component.literal("Unbanned " + target), true);
            HeosLogger.info(source.getTextName() + " unbanned " + target);
            return 1;
        }

        source.sendFailure(Component.literal(target + " is not banned"));
        return 0;
    }

    private static int unbanIp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String targetIp = StringArgumentType.getString(context, "ip");

        BanData banData = Heos.getBanData();
        if (banData.removeIpBan(targetIp)) {
            source.sendSuccess(() -> Component.literal("Unbanned IP " + targetIp), true);
            HeosLogger.info(source.getTextName() + " unbanned IP " + targetIp);
            return 1;
        }

        source.sendFailure(Component.literal(targetIp + " is not IP banned"));
        return 0;
    }

    private static int listBans(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BanData banData = Heos.getBanData();
        banData.removeExpiredBans();

        source.sendSuccess(() -> Component.literal("================================="), false);
        source.sendSuccess(() -> Component.literal("Ban List"), false);
        source.sendSuccess(() -> Component.literal("================================="), false);

        if (banData.playerBans.isEmpty() && banData.ipBans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No ban records"), false);
            return 1;
        }

        if (!banData.playerBans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Player bans (" + banData.playerBans.size() + "):"), false);
            for (BanData.BanEntry ban : banData.playerBans) {
                final String ti = TimeParser.formatExpiryTime(ban.expiryTime);
                source.sendSuccess(() -> Component.literal("- " + ban.username + " | " + ti + " | " + ban.reason), false);
            }
        }

        if (!banData.ipBans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("IP bans (" + banData.ipBans.size() + "):"), false);
            for (BanData.IpBanEntry ban : banData.ipBans) {
                final String ti = TimeParser.formatExpiryTime(ban.expiryTime);
                source.sendSuccess(() -> Component.literal("- " + ban.ip + " | " + ti + " | " + ban.reason), false);
            }
        }

        source.sendSuccess(() -> Component.literal("================================="), false);
        return 1;
    }

    private static void removeVanillaBanCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        removeRootCommand(dispatcher, "ban");
        removeRootCommand(dispatcher, "pardon");
        removeRootCommand(dispatcher, "ban-ip");
        removeRootCommand(dispatcher, "pardon-ip");
    }

    @SuppressWarnings("unchecked")
    private static void removeRootCommand(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
        try {
            CommandNode<CommandSourceStack> root = dispatcher.getRoot();
            removeFromCommandMap(root, "children", command);
            removeFromCommandMap(root, "literals", command);
            removeFromCommandMap(root, "arguments", command);
        } catch (ReflectiveOperationException e) {
            HeosLogger.warn("Failed to remove vanilla command /" + command + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromCommandMap(CommandNode<CommandSourceStack> node, String fieldName, String command) throws ReflectiveOperationException {
        Field field = CommandNode.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<String, CommandNode<CommandSourceStack>> map = (Map<String, CommandNode<CommandSourceStack>>) field.get(node);
        map.remove(command.toLowerCase(Locale.ROOT));
    }

    private record ParsedBanArgs(String timeStr, String reason) {
    }
}
