package studio.liuye.cosmos.listener;

import io.papermc.paper.event.world.border.WorldBorderBoundsChangeEvent;
import io.papermc.paper.event.world.border.WorldBorderCenterChangeEvent;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import studio.liuye.cosmos.CosmosPlugin;

/**
 * 主世界边界变化时，把 Cosmos 的边界重新同步一次。
 *
 * <p>只处理「主世界」的边界事件；同步写入的是 Cosmos 的边界，因此不会自我触发。
 * 真正的边界写入放到全局区域线程执行（世界数据只允许在那里改）。</p>
 */
public final class WorldBorderSyncListener implements Listener {

    private final CosmosPlugin plugin;

    public WorldBorderSyncListener(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoundsChange(WorldBorderBoundsChangeEvent event) {
        if (!plugin.settings().isMainWorld(event.getWorld())) {
            return;
        }
        if (event.getType() == WorldBorderBoundsChangeEvent.Type.STARTED_MOVE) {
            // 逐步扩张/收缩过程中的第一步不必同步，等收缩/扩张结束（或瞬间移动）时再同步
            return;
        }
        resync();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCenterChange(WorldBorderCenterChangeEvent event) {
        if (!plugin.settings().isMainWorld(event.getWorld())) {
            return;
        }
        resync();
    }

    private void resync() {
        plugin.scheduler().global(() -> {
            World cosmos = plugin.worlds() == null ? null : plugin.worlds().cosmosWorld();
            if (cosmos != null) {
                plugin.worlds().syncWorldBorder(cosmos, plugin.settings());
            }
        });
    }
}
