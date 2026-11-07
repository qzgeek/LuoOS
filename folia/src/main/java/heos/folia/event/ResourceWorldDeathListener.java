package heos.folia.event;

import heos.folia.utils.ResourceWorldManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.logging.Logger;

/**
 * 资源世界（主世界 / 下界 / 末地）死亡不掉落的硬保证。
 *
 * <p>资源世界不掉落原本只靠 keepInventory 这一条世界规则保证，而规则是持久化状态：
 * 维度重建、被 Worlds 卸载重载、关卡数据被回档覆盖时都会回退成 false，
 * 玩家就在「规则看似设好、实际已回退」的窗口里掉了装备（生产 2026-11 报障即此）。
 * {@link ResourceWorldManager} 的周期收敛会把规则拉回来，但收敛有最多 60 秒延迟，
 * 这里在死亡事件层再兜一次，做到「任何时候在资源世界死亡都不掉落」。</p>
 */
public final class ResourceWorldDeathListener implements Listener {

    private final ResourceWorldManager resourceWorlds;
    private final Logger logger;

    public ResourceWorldDeathListener(ResourceWorldManager resourceWorlds, Logger logger) {
        this.resourceWorlds = resourceWorlds;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player == null) return;
        if (!resourceWorlds.isResourceWorld(player.getWorld())) return;

        // 规则已生效：背包本就不掉，事件里的 drops 也是空的，不必干预。
        if (event.getKeepInventory()) return;

        event.setKeepInventory(true);
        // 必须同时清空 drops：Paper 在事件回调之后会无条件丢弃事件里的 drops 列表
        // （CraftEventFactory#callPlayerDeathEvent 之后那段不看 keepInventory），
        // 只设 keepInventory 不清 drops 会变成「既保留又掉落」的复制。
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
        // 这行只在「规则本该是 true 却为 false」时出现，线上看到它就说明规则确实回退过，
        // 属于需要追查的信号，不能静默。
        logger.info("[ResourceWorld] 死亡兜底生效: " + player.getName() + " 在 "
                + player.getWorld().getName() + " 死亡时 keepInventory=false，已强制保留背包与经验");
    }
}
