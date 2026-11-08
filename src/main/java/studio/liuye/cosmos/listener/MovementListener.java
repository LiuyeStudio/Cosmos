package studio.liuye.cosmos.listener;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import studio.liuye.cosmos.teleport.TeleportService;

/**
 * Y 轴阈值检测（主路径）。
 *
 * <p>事件本身在玩家所属区域线程上触发，但本监听器<b>只做纯数学判断</b>：
 * 世界名比较 + Y 比较。真正的区块加载、落点搜索、传送全部由
 * {@link TeleportService} 拆到正确的区域线程上完成，因此这里不会阻塞 tick，
 * 也不会在错误的线程上操作世界数据。</p>
 *
 * <p>使用 {@code MONITOR} 优先级且忽略被取消的事件：其它插件取消移动（例如区域保护）
 * 时我们不应该把玩家传走。</p>
 */
public final class MovementListener implements Listener {

    private final TeleportService teleports;

    public MovementListener(TeleportService teleports) {
        this.teleports = teleports;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        teleports.checkTransition(event.getPlayer(), to);
    }
}
