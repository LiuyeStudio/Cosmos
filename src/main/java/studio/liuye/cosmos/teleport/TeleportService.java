package studio.liuye.cosmos.teleport;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import studio.liuye.cosmos.CosmosPlugin;
import studio.liuye.cosmos.config.CosmosSettings;
import studio.liuye.cosmos.util.Messages;
import studio.liuye.cosmos.world.CosmosWorldService;
import studio.liuye.cosmos.world.Planet;
import studio.liuye.cosmos.world.PlanetRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 双向 Y 轴传送的核心实现。
 *
 * <h2>坐标语义（严格一一对应）</h2>
 * <pre>
 * 主世界 → Cosmos :  x' = x / scale , z' = z / scale , y' = teleport-y-cosmos + offset
 * Cosmos → 主世界 :  x' = x * scale , z' = z * scale , y' = teleport-y-world - offset
 * </pre>
 * <p>换算本身不做落点搜索、不生成平台、也不回退到配置的兜底点；但「去 Cosmos」的落点会经过
 * {@link #adjustCosmosArrival(Location)} 做一次安全修正：那一列有星球就站在球面上，
 * 没有任何星球就改到原点地标上方。否则出厂默认（到达 Y 低于所有星球）会让玩家一路下坠、
 * 几秒后又被传回主世界。</p>
 * <p>一次往返（中途不自己移动）X/Z/Y 都与出发时一致，只有 ±offset 的往返偏移被互相抵消；
 * 例外是「落点列没有星球」被吸附到地标的那一次。</p>
 *
 * <h2>Folia 线程模型</h2>
 * <p>一次传送分三步，每一步都跑在正确的线程上：</p>
 * <ol>
 *   <li><b>判定</b>（调用方线程，通常是玩家区域线程上的 {@code PlayerMoveEvent}）：
 *       只做纯数学比较，不读方块、不改实体。</li>
 *   <li><b>排队 + 区块就绪</b>：用 {@code entityNextTick} 排到玩家的下一个 tick（<b>绝不</b>在事件回调里内联传送），
 *       再用 {@code getChunkAtAsync} 让目标区块就绪（只加载，不读方块）。</li>
 *   <li><b>传送</b>（玩家的 {@code EntityScheduler} 线程）：{@code teleportAsync}；
 *       若玩家骑乘载具，则传送“根载具”，原版会把乘客一起带走。</li>
 * </ol>
 * <p>冷却（{@code teleport-cooldown-ms}）与“在途标记”（{@code inFlight}）共同保证
 * 不会出现来回弹跳或同一玩家并发多次传送。</p>
 */
public final class TeleportService {

    /** 传送方向。 */
    public enum Direction {
        MAIN_TO_COSMOS("主世界 -> Cosmos"),
        COSMOS_TO_MAIN("Cosmos -> 主世界");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 原版世界水平坐标上限（30000000 减去安全余量）。 */
    private static final double MAX_HORIZONTAL_COORDINATE = 29_999_000.0D;

    /** 原版可接受的最大 Y（与 /tp 一致）。超出它的坐标没有意义，只在这里兜底。 */
    private static final double MAX_VERTICAL_COORDINATE = 20_000_000.0D;

    private final CosmosPlugin plugin;
    private final CosmosWorldService worlds;
    private final TeleportCooldowns cooldowns;

    /** 正在传送中的玩家，防止同一玩家并发触发两次传送。 */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** 每个玩家的低频 Y 轴巡检任务。 */
    private final Map<UUID, ScheduledTask> monitors = new ConcurrentHashMap<>();

    public TeleportService(CosmosPlugin plugin, CosmosWorldService worlds, TeleportCooldowns cooldowns) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.cooldowns = cooldowns;
    }

    // ==================================================================
    // 1) 判定
    // ==================================================================

    /**
     * 轻量判定：只比较 Y 与世界名，不做任何 IO。
     * 由 {@code PlayerMoveEvent}（玩家区域线程）与低频巡检调用。
     */
    public void checkTransition(Player player, Location to) {
        if (to == null || !player.isOnline()) {
            return;
        }
        CosmosSettings settings = plugin.settings();
        World world = to.getWorld();
        if (world == null) {
            return;
        }
        if (settings.isMainWorld(world)) {
            if (to.getY() >= settings.teleportYWorld()) {
                request(player, Direction.MAIN_TO_COSMOS, "y>=" + settings.teleportYWorld());
            }
        } else if (settings.isCosmosWorld(world)) {
            if (to.getY() <= settings.teleportYCosmos()) {
                request(player, Direction.COSMOS_TO_MAIN, "y<=" + settings.teleportYCosmos());
            }
        }
    }

    /**
     * 按当前玩家位置计算目标并请求传送。
     *
     * @return 是否已接受本次请求（冷/在途时返回 false）
     */
    public boolean request(Player player, Direction direction, String reason) {
        CosmosSettings settings = plugin.settings();
        World targetWorld = direction == Direction.MAIN_TO_COSMOS
                ? worlds.cosmosWorld()
                : plugin.getServer().getWorld(settings.mainWorldName());
        if (targetWorld == null) {
            plugin.getLogger().fine(() -> "忽略传送请求：目标世界不存在 (" + direction.label() + ")");
            return false;
        }
        Location from = player.getLocation();
        Location desired = computeDestination(settings, direction, from, targetWorld);
        if (direction == Direction.MAIN_TO_COSMOS) {
            desired = adjustCosmosArrival(desired);
        }
        return requestTo(player, desired, direction, reason);
    }

    /**
     * 命令入口：把玩家送到 Cosmos 的兜底落点（本来就已在 Cosmos 时使用 fallback-location）。
     *
     * <p>命令线程不保证是玩家区域线程，因此这里只检查世界可用性并登记在途标记，
     * 目标坐标（需要读取玩家位置）与传送全部在玩家自己的 {@code EntityScheduler} 上完成。</p>
     */
    public boolean teleportToCosmos(Player player) {
        if (!player.isOnline()) {
            return false;
        }
        if (worlds.cosmosWorld() == null) {
            plugin.messages().send(player, "teleport-unavailable");
            return false;
        }

        UUID playerId = player.getUniqueId();
        if (!inFlight.add(playerId)) {
            return false;
        }
        ScheduledTask task = plugin.scheduler().entityNextTick(player, () -> {
            if (!player.isOnline()) {
                inFlight.remove(playerId);
                return;
            }
            CosmosSettings settings = plugin.settings();
            World cosmos = worlds.cosmosWorld();
            if (cosmos == null) {
                fail(player, "Cosmos 世界不可用");
                return;
            }
            Location from = player.getLocation();
            Location desired;
            if (settings.isCosmosWorld(from.getWorld())) {
                desired = fallbackInCosmos(settings, cosmos, from);
            } else {
                desired = adjustCosmosArrival(
                        computeDestination(settings, Direction.MAIN_TO_COSMOS, from, cosmos));
            }
            beginTransfer(player, desired, Direction.MAIN_TO_COSMOS, "command");
        }, () -> inFlight.remove(playerId));
        if (task == null) {
            // 实体已被移除时 EntityScheduler 不会执行任何回调：必须在这里自己释放，否则 UUID 永久占位
            inFlight.remove(playerId);
            plugin.getLogger().fine(() -> "传送请求被丢弃：实体已不可调度 (" + player.getName() + ")");
            return false;
        }
        return true;
    }

    /** {@code /cosmos tp} 在玩家已经处于 Cosmos 时使用的配置落点（纯配置数据，不读世界）。 */
    private static Location fallbackInCosmos(CosmosSettings settings, World cosmos, Location from) {
        CosmosSettings.Fallback fallback = settings.fallback();
        double x = cosmos.getName().equals(fallback.worldName()) ? fallback.x() : 0.5D;
        double z = cosmos.getName().equals(fallback.worldName()) ? fallback.z() : 0.5D;
        return new Location(cosmos, x, fallback.y(), z, from.getYaw(), from.getPitch());
    }

    /**
     * 坐标换算（纯数学，任意线程安全）。
     *
     * <pre>
     * 主世界 → Cosmos :  x' = x / scale , z' = z / scale , y' = teleport-y-cosmos + offset
     * Cosmos → 主世界 :  x' = x * scale , z' = z * scale , y' = teleport-y-world - offset
     * </pre>
     *
     * <p>Y 由<b>各自世界</b>的传送高度推导（主世界 teleport-y-world、Cosmos teleport-y-cosmos），并自带
     * {@code offset} 格的迟滞区。</p>
     *
     * <p><b>不做任何裁剪与落点修正</b>：X/Y/Z 就是换算结果本身。世界高度只是「可放置方块的范围」，
     * 实体可以被传送到建筑上限之上（与 {@code /tp} 一致），所以这里不会把 310 改成 255。
     * 只有 {@code NaN}/{@code Infinity} 与非法的极端值会被兜底。</p>
     */
    public static Location computeDestination(CosmosSettings settings, Direction direction,
                                               Location from, World target) {
        double scale = settings.scale();
        double x;
        double z;
        if (direction == Direction.MAIN_TO_COSMOS) {
            x = from.getX() / scale;
            z = from.getZ() / scale;
        } else {
            x = from.getX() * scale;
            z = from.getZ() * scale;
        }
        double y = rawDestinationY(settings, direction);
        return new Location(target, clampCoordinate(x), clampVertical(y), clampCoordinate(z),
                from.getYaw(), from.getPitch());
    }

    /** 未裁剪的目标 Y：{@code teleport-y + offset}（去 Cosmos）或 {@code teleport-y - offset}（回主世界）。 */
    static double rawDestinationY(CosmosSettings settings, Direction direction) {
        double offset = settings.arrivalYOffset();
        return direction == Direction.MAIN_TO_COSMOS
                ? settings.teleportYCosmos() + offset
                : settings.teleportYWorld() - offset;
    }

    /** 只挡掉原版也无法处理的极端 Y（±20,000,000，与 {@code /tp} 的上限一致），不做“对齐世界高度”。 */
    private static double clampVertical(double y) {
        if (!Double.isFinite(y)) {
            return 0.0D;
        }
        return Math.max(-MAX_VERTICAL_COORDINATE, Math.min(MAX_VERTICAL_COORDINATE, y));
    }

    private static double clampCoordinate(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(-MAX_HORIZONTAL_COORDINATE, Math.min(MAX_HORIZONTAL_COORDINATE, value));
    }

    /**
     * 「去 Cosmos」落点的安全修正（在主线程之外只读数学，不触碰方块）。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>落点那一列有星球 → 至少站到球面上（换算出的 Y 更高就保持不变，落地后自然下落到星球）；</li>
     *   <li>那一列没有任何星球 → 改到原点地标正上方，避免出厂默认下「落进虚空一路下坠」；</li>
     *   <li>没有地标（关掉了 {@code spawn-planet}）→ 保持原坐标，并在日志里说明。</li>
     * </ol>
     */
    private Location adjustCosmosArrival(Location desired) {
        World world = desired.getWorld();
        if (world == null) {
            return desired;
        }
        PlanetRegistry registry = plugin.planetRegistry();
        if (registry == null || registry.isEmpty()) {
            return desired;
        }

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        Location adjusted = desired.clone();
        double stand = highestStandY(registry, adjusted.getBlockX(), adjusted.getBlockZ(), minY, maxY);
        if (!Double.isNaN(stand)) {
            if (adjusted.getY() < stand) {
                adjusted.setY(stand);
            }
            return adjusted;
        }

        double spawnStand = highestStandY(registry, 0, 0, minY, maxY);
        if (Double.isNaN(spawnStand)) {
            plugin.getLogger().fine(() -> "落点列没有星球，且原点地标已关闭：保持换算坐标 "
                    + "(" + adjusted.getX() + ", " + adjusted.getY() + ", " + adjusted.getZ() + ")");
            return adjusted;
        }
        adjusted.setX(0.5D);
        adjusted.setZ(0.5D);
        adjusted.setY(Math.max(spawnStand, minY + 1.0D));
        plugin.getLogger().fine(() -> "落点列没有星球，已改到原点地标 (0.5, " + adjusted.getY() + ", 0.5)");
        return adjusted;
    }

    /** 某一列所有星球里最高的可站立 Y；没有星球时返回 {@link Double#NaN}。 */
    private static double highestStandY(PlanetRegistry registry, int blockX, int blockZ, int minY, int maxY) {
        double best = Double.NaN;
        for (Planet planet : registry.planetsNear(blockX, blockZ, minY, maxY)) {
            double stand = planet.standYAt(blockX, blockZ);
            if (Double.isNaN(stand) || stand > maxY) {
                continue;
            }
            if (Double.isNaN(best) || stand > best) {
                best = stand;
            }
        }
        return best;
    }

    // ==================================================================
    // 2) 请求入队（EntityScheduler）
    // ==================================================================

    private boolean requestTo(Player player, Location desired, Direction direction, String reason) {
        if (!player.isOnline() || desired.getWorld() == null) {
            return false;
        }
        CosmosSettings settings = plugin.settings();
        UUID playerId = player.getUniqueId();

        if (cooldowns.isCoolingDown(playerId, settings.teleportCooldownMs())) {
            return false;
        }
        if (!inFlight.add(playerId)) {
            return false;
        }

        // 从任意线程（事件 / 命令 / 全局）排队到玩家的下一个 tick。
        // 这里刻意使用 entityNextTick 而不是 entity：entity 在「当前线程已拥有该实体」时会就地执行，
        // 而 PlayerMoveEvent 的回调返回后服务端还会 absSnapTo(移动包坐标)，
        // 会把刚刚设好的目标位置改回出发坐标（跨世界放置实体时区域线程校验失败 → 服务端 halt）。
        ScheduledTask task = plugin.scheduler().entityNextTick(player, () -> {
            if (!player.isOnline()) {
                inFlight.remove(playerId);
                return;
            }
            beginTransfer(player, desired.clone(), direction, reason);
        }, () -> inFlight.remove(playerId));
        if (task == null) {
            inFlight.remove(playerId);
            plugin.getLogger().fine(() -> "传送请求被丢弃：实体已不可调度 (" + player.getName()
                    + ", " + direction.label() + ")");
            return false;
        }
        return true;
    }

    /**
     * 目标区块就绪后再传送。
     *
     * <p>这一步<b>只加载区块</b>：不读方块、不搜索落点、不改坐标。
     * 让目标区块（以及它所在的区域）先进入tick，跨世界放置实体才能稳定地在目标区域线程上完成。</p>
     *
     * <p>除了落点那一个区块，还会按 {@code teleport-preload-radius} 预加载周围几圈：
     * 跨维度时客户端要为目标视距内的区块建网格，先攒好一圈邻居再传送，
     * 落地后就不会出现「边落边等区块」的卡顿。预加载有 5 秒上限，超时照常传送。</p>
     */
    private void beginTransfer(Player player, Location desired, Direction direction, String reason) {
        UUID playerId = player.getUniqueId();
        World target = desired.getWorld();
        if (target == null) {
            fail(player, "目标世界为空");
            return;
        }

        int chunkX = desired.getBlockX() >> 4;
        int chunkZ = desired.getBlockZ() >> 4;
        int radius = Math.max(0, plugin.settings().teleportPreloadRadius());

        List<CompletableFuture<?>> loads = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        try {
            // 落点区块必须生成（否则 teleportAsync 会替我们同步加载）
            loads.add(target.getChunkAtAsync(chunkX, chunkZ, true));
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    // 外圈只「从磁盘加载已经存在的区块」，generate=false：
                    // 绝不为了预热在传送前批量生成区块（那才会拖慢/卡住传送）。
                    // 没生成过或加载失败都不影响传送，直接吞掉异常。
                    loads.add(target.getChunkAtAsync(chunkX + dx, chunkZ + dz, false)
                            .exceptionally(error -> null));
                }
            }
        } catch (RuntimeException failure) {
            fail(player, "请求区块加载时出错: " + failure.getMessage());
            return;
        }

        CompletableFuture<Void> ready = CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0]))
                // 预加载只是锦上添花：迟迟不就绪时不能把玩家卡在传送中
                .orTimeout(3, TimeUnit.SECONDS);
        ready.whenComplete((ignored, error) -> {
            ScheduledTask task = plugin.scheduler().entityNextTick(player, () -> {
            if (!player.isOnline()) {
                inFlight.remove(playerId);
                return;
            }
            if (error != null && !(error instanceof TimeoutException)) {
                fail(player, "目标区块加载失败: " + error.getMessage());
                return;
            }
            if (error instanceof TimeoutException) {
                plugin.getLogger().fine(() -> "落点区块预加载超时，仍按原计划传送 (" + player.getName() + ")");
            }
            doTeleport(player, desired, direction, reason);
            }, () -> inFlight.remove(playerId));
            if (task == null) {
                inFlight.remove(playerId);
            }
        });
    }

    // ==================================================================
    // 3) 玩家区域线程：真正执行传送
    // ==================================================================

    private void doTeleport(Player player, Location destination, Direction direction, String reason) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()) {
            inFlight.remove(playerId);
            return;
        }

        Location target = destination.clone();
        Location current = player.getLocation();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());

        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            teleportEntity(player, target, direction, reason);
            return;
        }

        // 骑乘中：传送根载具，原版会连同乘客一起移动（跨世界同样成立）
        Entity root = rootVehicle(vehicle);
        plugin.scheduler().entity(root, () -> {
            try {
                root.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success, error) -> {
                            if (error != null || !Boolean.TRUE.equals(success)) {
                                plugin.scheduler().entity(player, () -> {
                                    player.leaveVehicle();
                                    teleportEntity(player, target, direction,
                                            reason + " (载具传送失败，已下车)");
                                }, () -> inFlight.remove(playerId));
                                return;
                            }
                            plugin.scheduler().entity(player,
                                    // 骑乘中不可能在滑翔
                                    () -> afterTeleport(player, direction, reason, false),
                                    () -> inFlight.remove(playerId));
                        });
            } catch (RuntimeException failure) {
                plugin.scheduler().entity(player, () -> {
                    player.leaveVehicle();
                    teleportEntity(player, target, direction, reason + " (载具异常，已下车)");
                }, () -> inFlight.remove(playerId));
            }
        }, () -> inFlight.remove(playerId));
    }

    private void teleportEntity(Player player, Location target, Direction direction, String reason) {
        UUID playerId = player.getUniqueId();
        // 传送会跨世界重建实体数据，滑翔状态必须现在读，晚了可能已经被清掉
        boolean gliding = player.isGliding();
        try {
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> {
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            plugin.scheduler().entity(player,
                                    () -> fail(player, "传送被拒绝或失败: "
                                            + (error == null ? "unknown" : error.getMessage())),
                                    () -> inFlight.remove(playerId));
                            return;
                        }
                        plugin.scheduler().entity(player,
                                () -> afterTeleport(player, direction, reason, gliding),
                                () -> inFlight.remove(playerId));
                    });
        } catch (RuntimeException failure) {
            fail(player, "teleportAsync 抛出异常: " + failure.getMessage());
        }
    }

    private void afterTeleport(Player player, Direction direction, String reason, boolean gliding) {
        UUID playerId = player.getUniqueId();
        inFlight.remove(playerId);
        cooldowns.mark(playerId);

        player.setFallDistance(0.0F);
        if (!gliding) {
            try {
                player.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            } catch (RuntimeException ignored) {
                // 某些状态（例如刚退出载具）下速度不可写，忽略即可
            }
        }
        // 滑翔中不清速度：鞘翅的动量就是玩家飞过来的动力，
        // 清零会让玩家在到达点直接失速掉下去（而且下一次滑翔要重新点火）。
        if (gliding && !player.isOnGround() && wearsElytra(player)) {
            // 跨世界传送后客户端可能已经退出滑翔（服务端标志也在某些路径上被重置），
            // 这里补一次，让「飞到一半穿过 Y 阈值」的玩家继续滑翔而不是直接掉下去。
            try {
                player.setGliding(true);
            } catch (RuntimeException ignored) {
                // 不支持时保持原样（玩家仍可再按一次跳跃重新起飞）
            }
        }
        CosmosSettings settings = plugin.settings();
        if (settings.portalCooldownTicks() > 0) {
            try {
                player.setPortalCooldown(settings.portalCooldownTicks());
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }

        Location location = player.getLocation();
        // 默认不提示（message.yml: teleport-message: false）
        if (plugin.messages().teleportMessageEnabled()) {
            plugin.messages().send(player, "teleport-success",
                    "direction", direction.label(),
                    "world", location.getWorld().getName(),
                    "x", String.format(Locale.ROOT, "%.1f", location.getX()),
                    "y", String.format(Locale.ROOT, "%.1f", location.getY()),
                    "z", String.format(Locale.ROOT, "%.1f", location.getZ()));
        }

        if (settings.debug()) {
            plugin.getLogger().info("传送完成: " + player.getName() + " " + direction.label()
                    + " -> " + location.getWorld().getName() + " "
                    + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", location.getX(), location.getY(), location.getZ())
                    + " (" + reason + ")");
        }
    }

    private void fail(Player player, String why) {
        inFlight.remove(player.getUniqueId());
        plugin.getLogger().warning("传送失败 (" + player.getName() + "): " + why);
        plugin.messages().send(player, "teleport-failed", "reason", why);
    }

    /** 胸口是否穿着鞘翅（不检查耐久，损坏的鞘翅同样算）。 */
    private static boolean wearsElytra(Player player) {
        ItemStack chest = player.getInventory().getItem(EquipmentSlot.CHEST);
        return chest != null && chest.getType() == Material.ELYTRA;
    }

    private static Entity rootVehicle(Entity entity) {
        Entity current = entity;
        for (int guard = 0; guard < 32 && current.getVehicle() != null; guard++) {
            current = current.getVehicle();
        }
        return current;
    }

    // ==================================================================
    // 低频巡检（EntityScheduler 周期任务，跟随玩家跨区域）
    // ==================================================================

    /**
     * 为玩家开启周期巡检。{@code PlayerMoveEvent} 仍是主路径，
     * 巡检只是兜底（例如插件/原版把玩家瞬间抬高而未触发移动事件的极端情况）。
     */
    public void startMonitoring(Player player) {
        CosmosSettings settings = plugin.settings();
        int interval = settings.yCheckIntervalTicks();
        stopMonitoring(player.getUniqueId());
        if (interval <= 0) {
            return;
        }
        ScheduledTask task = plugin.scheduler().entityTimer(player, () -> {
            if (!player.isOnline()) {
                stopMonitoring(player.getUniqueId());
                return;
            }
            checkTransition(player, player.getLocation());
        }, interval, interval);
        monitors.put(player.getUniqueId(), task);
    }

    public void stopMonitoring(UUID playerId) {
        ScheduledTask task = monitors.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    /** reload 后按新周期重建所有在线玩家的巡检任务。 */
    public void restartMonitoring() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            startMonitoring(player);
        }
    }

    /** 玩家退出：清掉在途标记、冷却与巡检任务。 */
    public void forget(Player player) {
        UUID playerId = player.getUniqueId();
        stopMonitoring(playerId);
        inFlight.remove(playerId);
        cooldowns.clear(playerId);
    }

    /** 插件关闭。 */
    public void shutdown() {
        for (ScheduledTask task : monitors.values()) {
            task.cancel();
        }
        monitors.clear();
        inFlight.clear();
        cooldowns.clearAll();
    }
}
