package studio.liuye.cosmos.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import studio.liuye.cosmos.item.ItemFloatService;
import studio.liuye.cosmos.player.PlayerFloatService;
import studio.liuye.cosmos.teleport.TeleportService;

/**
 * 玩家生命周期：建立 / 清理玩家级别的周期任务。
 *
 * <p>所有任务都通过 {@code EntityScheduler} 绑定到玩家实体，
 * 玩家跨区域时会自动跟随，退出时统一取消。</p>
 */
public final class PlayerLifecycleListener implements Listener {

    private final TeleportService teleports;
    private final ItemFloatService itemFloatService;
    private final PlayerFloatService playerFloatService;

    public PlayerLifecycleListener(TeleportService teleports, ItemFloatService itemFloatService,
                                   PlayerFloatService playerFloatService) {
        this.teleports = teleports;
        this.itemFloatService = itemFloatService;
        this.playerFloatService = playerFloatService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        teleports.startMonitoring(player);
        itemFloatService.startMonitoring(player);
        playerFloatService.startMonitoring(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        teleports.forget(player);
        itemFloatService.stopMonitoring(player.getUniqueId());
        playerFloatService.forget(player);
    }
}
