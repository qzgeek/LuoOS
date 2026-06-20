package heos.folia.utils;

import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaBanData;
import heos.folia.storage.FoliaWhitelistData;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Installs a Netty hook before Minecraft handles the login hello packet.
 * This service:
 * 1. Removes character restrictions for offline players (any valid-length name allowed)
 * 2. Applies UUID remapping for bound accounts (account binding)
 * 3. Blocks banned/not-whitelisted players early
 */
public final class FoliaLoginUsernameValidationBypassService implements AutoCloseable {
    private static final String ACCEPTOR_HANDLER = "heos_login_acceptor";
    private static final String CHILD_BOOTSTRAP_HANDLER = "heos_login_child_bootstrap";
    private static final String PACKET_HANDLER = "heos_login_username_validation_bypass";
    private static final String VANILLA_PACKET_HANDLER = "packet_handler";

    private final Plugin plugin;
    private final FoliaBanData banData;
    private final FoliaWhitelistData whitelistData;
    private final FoliaAccountBinding accountBinding;
    private final Set<Channel> serverChannels = Collections.newSetFromMap(new IdentityHashMap<>());

    public FoliaLoginUsernameValidationBypassService(Plugin plugin, FoliaBanData banData,
                                                      FoliaWhitelistData whitelistData,
                                                      FoliaAccountBinding accountBinding) {
        this.plugin = plugin;
        this.banData = banData;
        this.whitelistData = whitelistData;
        this.accountBinding = accountBinding;
    }

    public void install() {
        for (Channel channel : serverChannels()) {
            installServerChannel(channel);
        }
        plugin.getLogger().info("Installed Folia login bypass (UUID mapping + name restriction removal)");
    }

    @Override
    public void close() {
        for (Channel channel : new ArrayList<>(serverChannels)) {
            channel.eventLoop().execute(() -> removeHandler(channel, ACCEPTOR_HANDLER));
        }
        serverChannels.clear();
    }

    private void installServerChannel(Channel channel) {
        if (channel == null || serverChannels.contains(channel)) {
            return;
        }
        serverChannels.add(channel);
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(ACCEPTOR_HANDLER) != null) {
                return;
            }
            channel.pipeline().addFirst(ACCEPTOR_HANDLER, new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                    if (message instanceof Channel child) {
                        installChildBootstrap(child);
                    }
                    super.channelRead(context, message);
                }
            });
        });
    }

    private void installChildBootstrap(Channel channel) {
        if (channel.pipeline().get(CHILD_BOOTSTRAP_HANDLER) != null || channel.pipeline().get(PACKET_HANDLER) != null) {
            return;
        }
        channel.pipeline().addFirst(CHILD_BOOTSTRAP_HANDLER, new ChannelDuplexHandler() {
            @Override
            public void channelRegistered(ChannelHandlerContext context) throws Exception {
                installChildChannel(context.channel(), 0);
                super.channelRegistered(context);
            }

            @Override
            public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                installChildChannel(context.channel(), 0);
                super.channelRead(context, message);
            }
        });
    }

    private void installChildChannel(Channel channel, int attempts) {
        if (!channel.isRegistered() || !channel.isOpen()) {
            return;
        }
        if (channel.pipeline().get(PACKET_HANDLER) != null) {
            removeHandler(channel, CHILD_BOOTSTRAP_HANDLER);
            return;
        }
        if (channel.pipeline().get(VANILLA_PACKET_HANDLER) == null) {
            if (attempts < 20) {
                channel.eventLoop().schedule(() -> installChildChannel(channel, attempts + 1), 25L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
        }

        channel.pipeline().addBefore(VANILLA_PACKET_HANDLER, PACKET_HANDLER, new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                if (isHelloPacket(message)) {
                    String username = packetUsername(message);
                    if (username == null) {
                        super.channelRead(context, message);
                        return;
                    }

                    // 1) Reject if banned or not whitelisted
                    if (rejectHeosLogin(context.channel(), username)) {
                        return;
                    }

                    // 2) Check for account binding — remap UUID
                    UUID boundUuid = null;
                    if (plugin.getConfig().getBoolean("enableAccountBinding", true)) {
                        // We don't know the UUID yet from just the Hello packet.
                        // But we can do the binding lookup after Mojang/fake auth assigns a UUID.
                        // Store a hint on the channel so we can remap later.
                        context.channel().attr(BIND_HINT_KEY).set(username);
                    }

                    // 3) If this is an offline player, short-circuit Mojang validation
                    if (shouldAcceptOfflineLogin(username)) {
                        if (acceptOfflineLogin(context.channel(), username)) {
                            return;
                        }
                        enableValidationBypass(context.channel());
                    }
                }
                super.channelRead(context, message);
            }
        });
        removeHandler(channel, CHILD_BOOTSTRAP_HANDLER);
    }

    // Channel attribute key for passing binding info to the login listener
    private static final io.netty.util.AttributeKey<String> BIND_HINT_KEY =
            io.netty.util.AttributeKey.valueOf("heos_bind_hint");

    private boolean rejectHeosLogin(Channel channel, String username) {
        return rejectOfflineUsername(username, channel)
                || rejectWhitelist(username, channel)
                || rejectBan(username, channel);
    }

    private boolean rejectOfflineUsername(String username, Channel channel) {
        // REMOVED character restrictions! Only check basic length.
        if (username == null || username.isEmpty()) {
            return disconnectLogin(channel, FoliaMessages.offlineNameHint());
        }
        // Still check length (Minecraft protocol requirement: 3-16 characters)
        int length = username.codePointCount(0, username.length());
        if (length < 3 || length > 16) {
            plugin.getLogger().info("Invalid name length: " + username);
            return disconnectLogin(channel, FoliaMessages.offlineNameHint());
        }
        if (!plugin.getConfig().getBoolean("allowOfflinePlayers", true)) {
            // Check if this is a premium UUID — if so, allow; otherwise reject
            // Since we don't have UUID yet, we'll let it through and let prelogin handle it
        }
        return false;
    }

    private boolean rejectWhitelist(String username, Channel channel) {
        if (!plugin.getConfig().getBoolean("enableWhitelist", false) || whitelistData.isWhitelisted(username)) {
            return false;
        }
        plugin.getLogger().info(FoliaMessages.whitelistDeniedLog(username));
        return disconnectLogin(channel, FoliaMessages.whitelistKick());
    }

    private boolean rejectBan(String username, Channel channel) {
        FoliaBanData.BanEntry playerBan = banData.getPlayerBan(username, null);
        if (playerBan != null) {
            if (!plugin.getConfig().getBoolean("enableCustomBan", true) && !FoliaMessages.isMigrationReason(playerBan.reason)) {
                return false;
            }
            if (FoliaMessages.isMigrationReason(playerBan.reason)) {
                plugin.getLogger().info(FoliaMessages.migrationBanAttemptLog(username));
            }
            return disconnectLogin(channel, FoliaMessages.banMessage(playerBan.reason, FoliaTimeParser.formatAbsolute(playerBan.expiryTime)));
        }

        if (!plugin.getConfig().getBoolean("enableCustomBan", true)) {
            return false;
        }
        FoliaBanData.IpBanEntry ipBan = banData.getIpBan(channelIp(channel));
        if (ipBan == null) {
            return false;
        }
        return disconnectLogin(channel, FoliaMessages.banIpMessage(ipBan.reason, FoliaTimeParser.formatAbsolute(ipBan.expiryTime)));
    }

    private boolean disconnectLogin(Channel channel, String message) {
        ChannelHandler handler = channel.pipeline().get(VANILLA_PACKET_HANDLER);
        if (handler == null) {
            return false;
        }
        Object listener = findPacketListener(handler);
        if (listener == null) {
            return false;
        }
        try {
            Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
            Object component = componentClass.getMethod("literal", String.class).invoke(null, message);
            Method disconnect = listener.getClass().getMethod("disconnect", componentClass);
            disconnect.invoke(listener, component);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Failed to send native Folia login disconnect", exception);
            return false;
        }
    }

    private static String channelIp(Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "";
    }

    /**
     * Now accepts ALL offline players regardless of character set.
     * Only rejects valid Mojang usernames that might be premium players.
     */
    private boolean shouldAcceptOfflineLogin(String username) {
        if (!plugin.getConfig().getBoolean("allowOfflinePlayers", true)) {
            return false;
        }
        // Accept as offline if username doesn't look like a valid Mojang username
        // (non-standard characters indicate offline player)
        return !FoliaMojangApi.isValidMojangUsername(username);
    }

    private boolean acceptOfflineLogin(Channel channel, String username) {
        ChannelHandler handler = channel.pipeline().get(VANILLA_PACKET_HANDLER);
        if (handler == null) {
            return false;
        }
        Object listener = findPacketListener(handler);
        if (listener == null) {
            return false;
        }
        try {
            UUID uuid = offlineUuid(username);

            // Check for account binding — if this offline UUID is bound to an online UUID
            if (plugin.getConfig().getBoolean("enableAccountBinding", true)) {
                UUID boundUuid = accountBinding.resolveEffectiveUuid(uuid);
                if (!boundUuid.equals(uuid)) {
                    uuid = boundUuid;
                    plugin.getLogger().info("Bound offline player " + username + " remapped to UUID " + boundUuid);
                }
            }

            Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
            Object profile = profileClass
                    .getConstructor(UUID.class, String.class)
                    .newInstance(uuid, username);

            setFieldIfPresent(listener, "requestedUsername", username);
            setFieldIfPresent(listener, "requestedUuid", uuid);

            Method startVerification = findGameProfileMethod(listener.getClass(), "startClientVerification", profileClass);
            if (startVerification != null) {
                startVerification.setAccessible(true);
                startVerification.invoke(listener, profile);
                plugin.getLogger().info("Accepted offline player (bound): " + username + " -> " + uuid);
                return true;
            }

            Method finishLogin = findGameProfileMethod(listener.getClass(), "finishLoginAndWaitForClient", profileClass);
            if (finishLogin == null) {
                plugin.getLogger().fine("Could not find Folia offline login methods");
                return false;
            }
            setFieldIfPresent(listener, "authenticatedProfile", profile);
            finishLogin.setAccessible(true);
            finishLogin.invoke(listener, profile);
            plugin.getLogger().info("Accepted offline player (bound): " + username + " -> " + uuid);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Failed to accept Folia offline login for " + username, exception);
            return false;
        }
    }

    /**
     * Generate UUID for an offline player.
     * Uses the standard Mojang offline UUID formula unless the player is bound.
     */
    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private void enableValidationBypass(Channel channel) {
        ChannelHandler handler = channel.pipeline().get(VANILLA_PACKET_HANDLER);
        if (handler == null) {
            return;
        }
        Object listener = findPacketListener(handler);
        if (listener == null) {
            return;
        }
        Field field = findField(listener.getClass(), "iKnowThisMayNotBeTheBestIdeaButPleaseDisableUsernameValidation");
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setBoolean(listener, true);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Failed to disable vanilla username validation", exception);
        }
    }

    private Object findPacketListener(Object connection) {
        Class<?> type = connection.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().contains("PacketListener")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(connection);
                    if (value != null && value.getClass().getName().contains("ServerLoginPacketListenerImpl")) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private List<Channel> serverChannels() {
        Object connection = serverConnection();
        if (connection == null) {
            return List.of();
        }
        List<Channel> channels = new ArrayList<>();
        Class<?> type = connection.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(connection);
                    if (value instanceof List<?> list) {
                        collectChannels(list, channels);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return channels;
    }

    private void collectChannels(List<?> values, List<Channel> channels) {
        for (Object value : values) {
            if (value instanceof ChannelFuture future) {
                channels.add(future.channel());
            } else if (value instanceof Channel channel) {
                channels.add(channel);
            }
        }
    }

    private Object serverConnection() {
        try {
            Method getServer = plugin.getServer().getClass().getMethod("getServer");
            Object minecraftServer = getServer.invoke(plugin.getServer());
            Method getConnection = minecraftServer.getClass().getMethod("getConnection");
            return getConnection.invoke(minecraftServer);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to access Folia server connection", exception);
            return null;
        }
    }

    private boolean isHelloPacket(Object packet) {
        return packet != null && packet.getClass().getName().endsWith("ServerboundHelloPacket");
    }

    private String packetUsername(Object packet) {
        for (Method method : packet.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
                try {
                    return (String) method.invoke(packet);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        for (Field field : packet.getClass().getDeclaredFields()) {
            if (field.getType() == String.class) {
                try {
                    field.setAccessible(true);
                    return (String) field.get(packet);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void setFieldIfPresent(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Failed to set Folia login field " + name, exception);
        }
    }

    private Method findGameProfileMethod(Class<?> type, String preferredName, Class<?> profileClass) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || parameters[0] != profileClass || method.getReturnType() != Void.TYPE) {
                    continue;
                }
                if (method.getName().equals(preferredName)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == profileClass && method.getReturnType() == Void.TYPE) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void removeHandler(Channel channel, String name) {
        if (channel.pipeline().get(name) != null) {
            channel.pipeline().remove(name);
        }
    }
}
