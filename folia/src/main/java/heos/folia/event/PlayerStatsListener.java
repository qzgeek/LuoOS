package heos.folia.event;

import heos.folia.utils.PlayerStatsTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Listener for player statistics tracking. */
public class PlayerStatsListener implements Listener {
    private final PlayerStatsTracker tracker;

    public PlayerStatsListener(PlayerStatsTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { tracker.onJoin(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { tracker.onQuit(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { tracker.onBlockBreak(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { tracker.onBlockPlace(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) { tracker.onChat(e.getPlayer(), e.getMessage()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKill(EntityDeathEvent e) {
        if (e.getEntity().getKiller() != null) tracker.onEntityKill(e.getEntity().getKiller());
    }
}
