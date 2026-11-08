package studio.liuye.cosmos.player;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import studio.liuye.cosmos.CosmosPlugin;
import studio.liuye.cosmos.config.CosmosSettings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmos 维度内的「低重力悬浮」。
 *
 * <p><b>不授予飞行权限</b>。玩家的物理是客户端模拟的，服务端能做的“降重力”手段是：
 * 在玩家所属区域线程上周期性把下落速度夹到一个小值（{@code -player-float-fall-speed}），
 * 并清空坠落距离（不会因为缓慢下沉而摔死）。效果是：玩家会缓慢往下飘，而不是自由落体，
 * 也不是像创造模式那样能飞。</p>
 *
 * <p>线程：每个玩家一个 {@code EntityScheduler} 周期任务，跟随玩家跨区域；
 * 修改速度只在自己的区域线程上做。</p>
 *
 * <p>跳过的情况：骑乘实体（由载具决定）、鞘翅滑翔中、液体中、创造/旁观且正在飞行、
 * 以及胸口穿着鞘翅的玩家（见下）。</p>
 *
 * <h2>为什么穿鞘翅要整个跳过</h2>
 * <p>鞘翅起飞依赖客户端的跳跃输入与本地速度：玩家在空中按跳跃后，客户端先进入滑翔、
 * 再发 {@code START_FALL_FLYING} 给服务端。服务端在处理这个包<b>之前</b>就已经不再是
 * {@code isGliding()}，此时如果悬浮巡检恰好下发一个速度包（把 Y 夹到 -0.08），
 * 客户端会认为速度被外部改写，起步的滑翔被直接打断，表现为「在 Cosmos 里按跳跃无法起飞」
 * 或「刚滑起来就掉下去」。因此只要胸口穿着鞘翅，就完全不碰这个玩家 —— 低重力、
 * 坠落距离都交给原版逻辑（星球表面本来就在传送高度以上，摔不死人）。</p>
 */
public final class PlayerFloatService {

    private final CosmosPlugin plugin;

    /** 每个玩家的低重力巡检任务。 */
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    public PlayerFloatService(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 单个玩家
    // ------------------------------------------------------------------

    /** 给玩家开启低重力巡检（重复调用会先取消旧任务）。 */
    public void startMonitoring(Player player) {
        CosmosSettings settings = plugin.settings();
        int interval = Math.max(1, settings.playerFloatIntervalTicks());
        stopMonitoring(player.getUniqueId());
        if (!settings.playerFloatEnabled()) {
            return;
        }
        ScheduledTask task = plugin.scheduler().entityTimer(player, () -> {
            if (!player.isOnline()) {
                stopMonitoring(player.getUniqueId());
                return;
            }
            apply(player);
        }, interval, interval);
        tasks.put(player.getUniqueId(), task);
    }

    public void stopMonitoring(UUID playerId) {
        ScheduledTask task = tasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    /** 在玩家区域线程上把下落速度夹到上限（低重力）。 */
    private void apply(Player player) {
        CosmosSettings settings = plugin.settings();
        if (!settings.playerFloatEnabled() || !settings.isCosmosWorld(player.getWorld())) {
            return;
        }
        if (player.isInsideVehicle() || player.isGliding() || player.isSwimming()
                || player.isInWaterOrBubbleColumn() || player.isInLava()) {
            return;
        }
        if (player.isFlying()) {
            return;
        }
        if (settings.playerFloatSkipElytra() && wearsElytra(player)) {
            return;
        }
        double maxFall = -Math.abs(settings.playerFloatFallSpeed());
        Vector velocity = player.getVelocity();
        if (maxFall >= 0.0D) {
            // 0 = 完全悬停
            if (velocity.getY() < 0.0D) {
                player.setVelocity(velocity.setY(0.0D));
            }
        } else if (velocity.getY() < maxFall) {
            player.setVelocity(velocity.setY(maxFall));
        }
        if (player.getFallDistance() > 0.0F) {
            player.setFallDistance(0.0F);
        }
    }

    /**
     * 胸口是否穿着鞘翅。
     *
     * <p>不检查耐久 / 是否损坏：损坏的鞘翅同样不应该被速度包干扰（玩家可能正在换装）。</p>
     */
    private static boolean wearsElytra(Player player) {
        ItemStack chest = player.getInventory().getItem(EquipmentSlot.CHEST);
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 插件启用 / reload 后按当前配置重建所有在线玩家的任务。 */
    public void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            startMonitoring(player);
        }
    }

    /** reload 后只重建任务（与 {@link #refreshAll()} 等价，命名对齐其它服务）。 */
    public void restartMonitoring() {
        refreshAll();
    }

    public void forget(Player player) {
        if (player != null) {
            stopMonitoring(player.getUniqueId());
        }
    }

    public void shutdown() {
        for (ScheduledTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }
}
