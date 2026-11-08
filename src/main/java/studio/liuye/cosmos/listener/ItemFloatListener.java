package studio.liuye.cosmos.listener;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import studio.liuye.cosmos.item.ItemFloatService;

/**
 * 掉落物悬浮的监听入口。
 *
 * <p>两个事件都在拥有该实体的区域线程上触发，但这里<b>不直接</b>调用
 * {@code setGravity(false)}：一律交给 {@link ItemFloatService} 通过
 * {@code EntityScheduler} 执行（若当前线程已经是该实体区域线程，则会就地执行）。
 * 这样 Paper / Folia 行为一致，也不会因为在事件里直接改实体而踩到线程检查。</p>
 */
public final class ItemFloatListener implements Listener {

    private final ItemFloatService service;

    public ItemFloatListener(ItemFloatService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        service.handleSpawn(event.getEntity());
    }

    /**
     * 已经存在的实体（区块加载 / 服务器启动 / 区块重载）也会被校正，
     * 因此重启服务器后 Cosmos 里的掉落物依然悬浮。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            service.handleSpawn(entity);
        }
    }
}
