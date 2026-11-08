package studio.liuye.cosmos.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 统一的调度入口 —— 插件内所有异步 / 延时 / 定时 / 区域任务都必须经过这里。
 *
 * <p>Folia 没有主线程，{@code BukkitScheduler} 已被废弃，因此本类只使用四种区域化调度器：</p>
 * <table border="1">
 *   <tr><th>方法</th><th>调度器</th><th>典型用途</th></tr>
 *   <tr><td>{@link #global}</td><td>GlobalRegionScheduler</td><td>与具体位置无关的全局逻辑：reload、状态校验、日志</td></tr>
 *   <tr><td>{@link #region}</td><td>RegionScheduler</td><td>绑定某个 {@link Location} 的方块读写、区块加载后的落点搜索</td></tr>
 *   <tr><td>{@link #entity}</td><td>EntityScheduler</td><td>绑定实体的操作：传送、setGravity、速度清零（实体跨区域时自动跟随）</td></tr>
 *   <tr><td>{@link #async}</td><td>AsyncScheduler</td><td>纯 IO：读取配置文件</td></tr>
 * </table>
 *
 * <p>关键约束（本类的存在就是为了不违反它们）：</p>
 * <ul>
 *   <li>RegionScheduler 里<b>不操作实体</b>，实体一律交给 EntityScheduler；</li>
 *   <li>AsyncScheduler 里<b>不碰</b>方块 / 实体 / 世界数据；</li>
 *   <li>方块读写与传送只在拥有该位置的区域线程执行；</li>
 *   <li>跨区域的工作按位置分别调度，绝不假设“一次调度覆盖全局”。</li>
 * </ul>
 */
public final class RegionSchedulerFacade {

    private final Plugin plugin;

    public RegionSchedulerFacade(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ------------------------------------------------------------------
    // GlobalRegionScheduler：世界无关的全局逻辑
    // ------------------------------------------------------------------

    /** 在全局区域线程执行一次（Paper 上等价于主线程）。 */
    public void global(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, guard(task));
    }

    /** 在全局区域线程延时执行（单位：tick）。 */
    public ScheduledTask globalLater(Runnable task, long delayTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runDelayed(plugin, ignore -> guard(task).run(), Math.max(1L, delayTicks));
    }

    /** 在全局区域线程周期执行（单位：tick）。 */
    public ScheduledTask globalTimer(Runnable task, long delayTicks, long periodTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, ignore -> guard(task).run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    // ------------------------------------------------------------------
    // RegionScheduler：绑定位置的方块 / 区块操作
    // ------------------------------------------------------------------

    /**
     * 在 {@code location} 所属区域线程执行。用于方块读写、安全落点扫描等。
     * 不要在这里操作实体 —— 实体请使用 {@link #entity(Entity, Runnable, Runnable)}。
     */
    public void region(Location location, Runnable task) {
        World world = location.getWorld();
        if (world == null) {
            plugin.getLogger().warning("RegionScheduler 调度被忽略：Location 的 world 为 null");
            return;
        }
        plugin.getServer().getRegionScheduler().execute(plugin, location, guard(task));
    }

    /** 便捷重载：按方块坐标定位区块。 */
    public void region(World world, int blockX, int blockZ, Runnable task) {
        plugin.getServer().getRegionScheduler()
                .execute(plugin, world, blockX >> 4, blockZ >> 4, guard(task));
    }

    // ------------------------------------------------------------------
    // EntityScheduler：绑定实体的操作，跟随实体跨区域
    // ------------------------------------------------------------------

    /**
     * 在实体所属区域线程执行任务。
     *
     * <p>如果当前线程已经拥有该实体的区域（{@code Server#isOwnedByCurrentRegion}），
     * 直接就地执行，避免多一个 tick 的延迟；否则通过 {@link Entity#getScheduler()} 排队。
     * 这不是“绕过调度器”，而是用官方线程归属检查做无锁快路径。</p>
     *
     * @param retired 实体被移除时执行的回调，可为 {@code null}
     */
    public void entity(Entity entity, Runnable task, Runnable retired) {
        if (entity == null) {
            return;
        }
        if (plugin.getServer().isOwnedByCurrentRegion(entity)) {
            guard(task).run();
            return;
        }
        entity.getScheduler().run(plugin, ignore -> guard(task).run(), retired);
    }

    /**
     * 在实体所属区域线程的<b>下一个 tick</b> 执行（即使当前线程已经拥有该实体也不就地执行）。
     *
     * <p>用于「绝不能在当前调用栈里完成」的操作：例如 {@code PlayerMoveEvent} 里发起跨世界传送。
     * Canvas / Folia 的移动包处理在事件返回后还会执行 {@code absSnapTo(包内坐标)}，
     * 若在事件回调里就调用 {@code teleportAsync}，位置会立刻被改回出发坐标，
     * 目标世界放置实体时区域线程校验失败，最终 halt 整个服务端。</p>
     *
     * @param retired 实体被移除时执行的回调，可为 {@code null}
     */
    public ScheduledTask entityNextTick(Entity entity, Runnable task, Runnable retired) {
        return entity.getScheduler().run(plugin, ignore -> guard(task).run(), retired);
    }

    /** 在实体所属区域线程延时执行（单位：tick）。 */
    public ScheduledTask entityLater(Entity entity, Runnable task, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin, ignore -> guard(task).run(), null,
                Math.max(1L, delayTicks));
    }

    /** 在实体所属区域线程周期执行（单位：tick）。 */
    public ScheduledTask entityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, ignore -> guard(task).run(), null,
                Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }

    // ------------------------------------------------------------------
    // AsyncScheduler：纯 IO，不得触碰任何 Bukkit 世界数据
    // ------------------------------------------------------------------

    /** 在异步线程执行（只允许文件 / 网络 / 纯计算）。 */
    public void async(Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, ignore -> guard(task).run());
    }

    /** 在异步线程延时执行（单位：毫秒）。 */
    public void asyncLater(Runnable task, long delayMillis) {
        plugin.getServer().getAsyncScheduler()
                .runDelayed(plugin, ignore -> guard(task).run(), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // 线程归属检查（只读，可在任意线程调用）
    // ------------------------------------------------------------------

    /** @return 当前线程是否拥有该实体的区域。 */
    public boolean ownsEntity(Entity entity) {
        return entity != null && plugin.getServer().isOwnedByCurrentRegion(entity);
    }

    /** @return 当前线程是否拥有该位置的区域。 */
    public boolean ownsLocation(Location location) {
        return location != null && location.getWorld() != null
                && plugin.getServer().isOwnedByCurrentRegion(location);
    }

    /**
     * @return 当前线程是否是全局区域线程（Paper 的主线程 / Folia 的全局线程）。
     *         Folia / Canvas 的世界级设置（游戏规则、出生点）只允许在这里修改。
     */
    public boolean isGlobalThread() {
        return plugin.getServer().isGlobalTickThread();
    }

    /** 关闭时取消本插件在全局 / 异步调度器上的任务（实体任务随实体与插件卸载自动取消）。 */
    public void cancelAll() {
        plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
    }

    /**
     * 任何任务异常都只记录日志，绝不让异常冒泡到区域线程 ——
     * 一个坏任务不应该拖垮所在区域（甚至整个服务端）的 tick 循环。
     */
    private Runnable guard(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE, "Cosmos 任务执行失败（已隔离，不影响区域 tick）", failure);
            }
        };
    }

    /** 供需要 {@code Consumer<ScheduledTask>} 的内部场景使用。 */
    public Consumer<ScheduledTask> consumer(Runnable task) {
        return ignore -> guard(task).run();
    }
}
