package studio.liuye.cosmos.listener;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import studio.liuye.cosmos.CosmosPlugin;
import studio.liuye.cosmos.config.CosmosSettings;

/**
 * 重力方块在 Cosmos 中悬停。
 *
 * <p>沙子、沙砾、混凝土粉末、铁砧、滴水石锥这类方块在下落时会先被替换成空气，
 * 再生成一个 {@link FallingBlock} 实体带着方块数据往下掉。这里在实体真正加入世界
 * 之前取消生成，并把方块原样放回原坐标——于是它看起来「悬浮」在原地，
 * 和掉落物、玩家的处理方式保持一致。</p>
 *
 * <p>要点：</p>
 * <ul>
 *   <li>只在 Cosmos 世界生效，主世界照常下落；</li>
 *   <li>回填用 {@code setBlockData(data, false)}（不触发物理），否则方块会立刻再次
 *       触发下落，形成「生成→取消→再生成」的循环；</li>
 *   <li>只回填当前是空气的坐标：如果原位置已经被别的方块占用（活塞推动等），
 *       宁可丢掉这一块，也不覆盖别人的方块。</li>
 * </ul>
 */
public final class GravityBlockListener implements Listener {

    private final CosmosPlugin plugin;

    public GravityBlockListener(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFallingBlockSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) {
            return;
        }
        CosmosSettings settings = plugin.settings();
        if (settings == null || !settings.floatGravityBlocks()) {
            return;
        }
        World world = falling.getWorld();
        if (!settings.isCosmosWorld(world)) {
            return;
        }

        BlockData data = falling.getBlockData();
        Block block = falling.getLocation().getBlock();
        event.setCancelled(true);

        if (block.getType().isAir() || block.getType() == Material.MOVING_PISTON) {
            block.setBlockData(data, false);
            if (settings.debug()) {
                plugin.getLogger().info("重力方块已悬停: " + data.getMaterial().getKey()
                        + " @ " + block.getX() + ", " + block.getY() + ", " + block.getZ());
            }
        } else if (settings.debug()) {
            plugin.getLogger().info("重力方块原位置已被占用，跳过回填: " + data.getMaterial().getKey()
                    + " @ " + block.getX() + ", " + block.getY() + ", " + block.getZ());
        }
    }

    /**
     * 区块加载时已经存在的掉落实体（旧版本存档、{@code /summon}、其它插件生成）不会触发
     * {@link EntitySpawnEvent}，这里补一次：原地放回方块并移除实体。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        CosmosSettings settings = plugin.settings();
        if (settings == null || !settings.floatGravityBlocks()) {
            return;
        }
        if (!settings.isCosmosWorld(event.getWorld())) {
            return;
        }
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (!(entity instanceof FallingBlock falling)) {
                continue;
            }
            Block block = falling.getLocation().getBlock();
            BlockData data = falling.getBlockData();
            try {
                falling.remove();
                if (block.getType().isAir()) {
                    block.setBlockData(data, false);
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "冻结已存在的掉落实体失败（区块可能尚未完全加载）", failure);
            }
        }
    }
}
