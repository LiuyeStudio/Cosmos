package studio.liuye.cosmos.item;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import studio.liuye.cosmos.CosmosPlugin;
import studio.liuye.cosmos.config.CosmosSettings;
import studio.liuye.cosmos.platform.FoliaSupport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Cosmos 维度内的“掉落物悬浮”服务。
 *
 * <h2>Folia 安全的实现方式</h2>
 * <ul>
 *   <li><b>新生成的实体</b>：{@code EntitySpawnEvent} / {@code ItemSpawnEvent} 里只做过滤，
 *       然后统一走 {@code EntityScheduler}（{@link studio.liuye.cosmos.platform.RegionSchedulerFacade#entity}），
 *       在实体所属区域线程上调用 {@code setGravity(false)}。</li>
 *   <li><b>区块加载时已存在的实体</b>：{@code EntitiesLoadEvent} 在拥有该区块的区域线程上触发，
 *       同样逐个通过 {@code EntityScheduler} 校正。这样重启服、区块卸载重载后都能恢复悬浮。</li>
 *   <li><b>启动/定期巡检</b>：Folia 没有“世界内全部实体”的全局视图，
 *       因此按玩家分批调度到各自区域线程，扫描玩家附近半径内的物品；
 *       在 Paper（非 Folia）上则可以在全局/主线程直接遍历 {@code World#getEntities()}。</li>
 * </ul>
 *
 * <p>规则只作用于 Cosmos 世界，主世界与其它世界完全不受影响。</p>
 */
public final class ItemFloatService {

    private final CosmosPlugin plugin;
    private final Map<UUID, ScheduledTask> sweeps = new ConcurrentHashMap<>();

    public ItemFloatService(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 处理一次实体生成 / 实体加载事件（可能在任意区域线程）。 */
    public void handleSpawn(Entity entity) {
        if (entity == null) {
            return;
        }
        CosmosSettings settings = plugin.settings();
        if (!settings.itemFloatEnabled() || !isTarget(entity, settings)) {
            return;
        }
        if (!settings.isCosmosWorld(entity.getWorld())) {
            return;
        }
        apply(entity);
    }

    private boolean isTarget(Entity entity, CosmosSettings settings) {
        if (entity instanceof Item) {
            return true;
        }
        return settings.floatExperienceOrb() && entity instanceof ExperienceOrb;
    }

    /** 通过 EntityScheduler 在实体所属区域线程上关闭重力。 */
    private void apply(Entity entity) {
        CosmosSettings settings = plugin.settings();
        plugin.scheduler().entity(entity, () -> {
            if (!entity.isValid()) {
                return;
            }
            if (entity.hasGravity()) {
                entity.setGravity(false);
            }
            if (settings.floatFreezeVelocity()) {
                entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            }
            if (settings.floatUnlimitedLifetime() && entity instanceof Item item && !item.isUnlimitedLifetime()) {
                item.setUnlimitedLifetime(true);
            }
        }, null);
    }

    // ------------------------------------------------------------------
    // 启动 / 定期巡检
    // ------------------------------------------------------------------

    /**
     * 插件启用（以及每次 reload）后校正已存在的掉落物。
     *
     * <p>Folia：从全局线程出发，按在线玩家分别调度区域任务扫描附近实体；
     * 其余实体在区块加载时由 {@code EntitiesLoadEvent} 覆盖。
     * Paper：直接在主线程遍历世界实体列表。</p>
     */
    public void sweepExisting() {
        CosmosSettings settings = plugin.settings();
        if (!settings.itemFloatEnabled()) {
            return;
        }
        World cosmos = plugin.worlds().cosmosWorld();
        if (cosmos == null) {
            return;
        }

        plugin.scheduler().global(() -> {
            if (FoliaSupport.isFolia()) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (settings.isCosmosWorld(player.getWorld())) {
                        plugin.scheduler().entity(player, () -> sweepNear(player), null);
                    }
                }
            } else {
                for (Entity entity : cosmos.getEntities()) {
                    handleSpawn(entity);
                }
            }
        });
    }

    /** 扫描玩家附近的掉落物（必须在玩家所属区域线程调用）。 */
    private void sweepNear(Player player) {
        CosmosSettings settings = plugin.settings();
        double radius = settings.floatSweepRadius();
        if (radius <= 0.0D) {
            // 0 = 关闭「按玩家位置重新扫描」；掉落物的持续悬浮仍由每个掉落物自己的任务维持
            return;
        }
        try {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (entity.equals(player)) {
                    continue;
                }
                if (entity instanceof Item || (settings.floatExperienceOrb() && entity instanceof ExperienceOrb)) {
                    apply(entity);
                }
            }
        } catch (RuntimeException failure) {
            // 理论上不应发生；若某个平台实现不支持区域内的邻近实体查询，只跳过本次巡检。
            plugin.getLogger().log(Level.FINE, "邻近实体扫描不可用，已跳过本次悬浮校正", failure);
        }
    }

    // ------------------------------------------------------------------
    // 每玩家巡检任务（EntityScheduler 周期任务）
    // ------------------------------------------------------------------

    public void startMonitoring(Player player) {
        CosmosSettings settings = plugin.settings();
        int interval = settings.floatSweepIntervalTicks();
        stopMonitoring(player.getUniqueId());
        if (!settings.itemFloatEnabled() || interval <= 0) {
            return;
        }
        ScheduledTask task = plugin.scheduler().entityTimer(player, () -> {
            if (!player.isOnline()) {
                stopMonitoring(player.getUniqueId());
                return;
            }
            if (plugin.settings().isCosmosWorld(player.getWorld())) {
                sweepNear(player);
            }
        }, interval, interval);
        sweeps.put(player.getUniqueId(), task);
    }

    public void stopMonitoring(UUID playerId) {
        ScheduledTask task = sweeps.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    /** reload 后按新配置重建巡检任务。 */
    public void restartMonitoring() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            startMonitoring(player);
        }
    }

    public void shutdown() {
        for (ScheduledTask task : sweeps.values()) {
            task.cancel();
        }
        sweeps.clear();
    }
}
