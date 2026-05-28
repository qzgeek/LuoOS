package heos.folia.integrations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class FoliaRecipeSyncService implements Listener, PluginMessageListener, AutoCloseable {
    private static final String JEI_NETWORK_CHANNEL = "jei:network";
    private static final String REI_NETWORK_CHANNEL = "rei:networking";
    private static final String REI_MOVE_ITEMS_CHANNEL = "roughlyenoughitems:move_items_new";
    private static final byte HANDSHAKE_PACKET_ID = 0;
    private static final int LATEST_JEI_REI_PROTOCOL_VERSION = 19;

    private final Plugin plugin;
    private final FoliaFabricRecipeSyncBridge fabricRecipeSync;

    public FoliaRecipeSyncService(Plugin plugin) {
        this.plugin = plugin;
        this.fabricRecipeSync = new FoliaFabricRecipeSyncBridge(plugin);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, JEI_NETWORK_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, JEI_NETWORK_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, REI_NETWORK_CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, REI_NETWORK_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, FoliaFabricRecipeSyncBridge.PAYLOAD_CHANNEL);
        // REI checks for this channel before it trusts vanilla recipe updates.
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, REI_MOVE_ITEMS_CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("Recipe viewer sync registered");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sendFabricRecipeSync(player);
        giveAllRecipes(player);
        sendHandshake(player, JEI_NETWORK_CHANNEL);
        sendHandshake(player, REI_NETWORK_CHANNEL);
    }

    @EventHandler
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event) {
        if (FoliaFabricRecipeSyncBridge.PAYLOAD_CHANNEL.equals(event.getChannel())) {
            sendFabricRecipeSync(event.getPlayer());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (REI_MOVE_ITEMS_CHANNEL.equals(channel)) {
            return;
        }
        if (!JEI_NETWORK_CHANNEL.equals(channel) && !REI_NETWORK_CHANNEL.equals(channel)) {
            return;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            byte packetId = input.readByte();
            if (packetId != HANDSHAKE_PACKET_ID) {
                return;
            }

            int clientProtocolVersion = input.readInt();
            plugin.getLogger().fine("Received recipe viewer handshake from " + player.getName()
                    + " on " + channel + " protocol " + clientProtocolVersion);
            giveAllRecipes(player);
            sendHandshake(player, channel);
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to handle recipe viewer handshake from "
                    + player.getName() + " on " + channel, exception);
        }
    }

    @Override
    public void close() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, JEI_NETWORK_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, JEI_NETWORK_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, REI_NETWORK_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, REI_NETWORK_CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, FoliaFabricRecipeSyncBridge.PAYLOAD_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, REI_MOVE_ITEMS_CHANNEL, this);
    }

    private void giveAllRecipes(Player player) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            List<NamespacedKey> recipeKeys = collectRecipeKeys();
            player.getScheduler().run(plugin, playerTask -> {
                if (player.isOnline() && !recipeKeys.isEmpty()) {
                    player.discoverRecipes(recipeKeys);
                }
            }, null);
        }, 1L);
    }

    private void sendFabricRecipeSync(Player player) {
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                fabricRecipeSync.send(player);
            }
        }, null);
    }

    private List<NamespacedKey> collectRecipeKeys() {
        List<NamespacedKey> keys = new ArrayList<>();
        var iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe instanceof Keyed keyed) {
                keys.add(keyed.getKey());
            }
        }
        return keys;
    }

    private void sendHandshake(Player player, String channel) {
        if (!player.isOnline()) {
            return;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(HANDSHAKE_PACKET_ID);
            output.writeInt(LATEST_JEI_REI_PROTOCOL_VERSION);
            player.sendPluginMessage(plugin, channel, bytes.toByteArray());
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to send recipe viewer handshake to "
                    + player.getName() + " on " + channel, exception);
        }
    }
}
