package studio.liuye.cosmos.world;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.generator.ChunkGenerator;
import studio.liuye.cosmos.CosmosPlugin;
import studio.liuye.cosmos.config.CosmosSettings;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Cosmos 世界的创建、加载与配置应用。
 *
 * <p>线程约定：</p>
 * <ul>
 *   <li>{@link #ensureWorld(CosmosSettings)} 必须在全局区域线程调用（插件 onEnable 本身就在全局线程，
 *       reload 也会通过 {@code GlobalRegionScheduler} 进入）。world 的创建/查询属于全局操作。</li>
 *   <li>世界的游戏规则与出生点同样属于服务端级数据：Folia / Canvas 里 {@code World#setGameRule}
 *       会显式要求全局区域线程（否则抛 {@code Cannot modify server settings off of the global region}），
 *       因此 {@link #applyWorldSettings(World, CosmosSettings)} 也在全局线程同步执行，不再投递到区域线程。</li>
 * </ul>
 */
public final class CosmosWorldService {

    private final CosmosPlugin plugin;

    private volatile World cosmosWorld;
    private volatile PlanetChunkGenerator generator;
    /** 生成器不匹配的警告只打一次，避免看门狗刷屏。 */
    private volatile boolean warnedGeneratorMismatch;
    /** 世界环境与配置不一致的警告只打一次。 */
    private volatile boolean warnedEnvironmentMismatch;
    /** 服务端不支持按世界设置视距的警告只打一次。 */
    private volatile boolean warnedViewDistance;

    public CosmosWorldService(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    public World cosmosWorld() {
        return cosmosWorld;
    }

    public PlanetChunkGenerator generator() {
        return generator;
    }

    /** 世界从磁盘加载时由 {@code JavaPlugin#getDefaultWorldGenerator} 提供的实例登记到这里。 */
    public void adoptGenerator(PlanetChunkGenerator adopted) {
        if (adopted != null && this.generator == null) {
            this.generator = adopted;
        }
    }

    /**
     * 只做「接管」：当 Cosmos 世界被外部世界管理插件（Multiverse-Core / MyWorlds /
     * PhantomWorlds / Worlds 等）加载、卸载或重新加载时，更新内部引用。
     *
     * <p>这个方法<b>绝不</b>创建、卸载或改写世界，只观察 {@code Server#getWorld(name)}，
     * 因此可以安全地被全局区域线程上的看门狗周期调用。</p>
     *
     * @return 当前可用的 Cosmos 世界；未加载时为 {@code null}
     */
    public World refresh() {
        CosmosSettings settings = plugin.settings();
        try {
            World current = plugin.getServer().getWorld(settings.cosmosWorldName());
            if (current == null) {
                if (this.cosmosWorld != null) {
                    plugin.getLogger().info("Cosmos 世界 '" + settings.cosmosWorldName()
                            + "' 当前未加载（可能被世界管理插件卸载），等待其重新加载。");
                    this.cosmosWorld = null;
                    this.generator = null;
                    this.warnedGeneratorMismatch = false;
                }
                return null;
            }
            if (current != this.cosmosWorld) {
                adoptWorld(current, settings);
            }
            return current;
        } catch (RuntimeException failure) {
            // 世界正在被外部插件卸载/重载时可能抛异常：看门狗每 5 秒跑一次，不能刷屏
            plugin.getLogger().log(Level.FINE, "刷新 Cosmos 世界引用失败（世界可能正在卸载）", failure);
            return null;
        }
    }

    /**
     * 接管一个已经存在的世界：记录引用与生成器，并把当前星球快照推给生成器。
     * 生成器不是 Cosmos 的星球生成器时只警告一次（并给出可执行的修复建议）。
     */
    private void adoptWorld(World world, CosmosSettings settings) {
        boolean firstAdoption = this.cosmosWorld == null;
        this.cosmosWorld = world;

        ChunkGenerator currentGenerator = world.getGenerator();
        if (currentGenerator instanceof PlanetChunkGenerator ourGenerator) {
            this.warnedGeneratorMismatch = false;
            this.generator = ourGenerator;
            ourGenerator.setBiome(settings.cosmosBiome());
            // 世界上一次会话 / 外部插件加载时就存在，注册表必须立刻生效
            ourGenerator.setRegistry(plugin.planetRegistry());
            ourGenerator.setFloorY(settings.planetFloorY());
            if (firstAdoption) {
                plugin.getLogger().info("已接管世界 '" + world.getName() + "'（使用 Cosmos 星球生成器）。");
            }
        } else if (isOurGeneratorClass(currentGenerator)) {
            // 热重载（PlugManX 等）之后，世界里的生成器实例来自上一次插件加载的类加载器：
            // instanceof 必然为 false，也无法强转成新类，只能继续沿用旧实例。
            this.generator = null;
            if (!this.warnedGeneratorMismatch) {
                this.warnedGeneratorMismatch = true;
                plugin.getLogger().warning("世界 '" + world.getName() + "' 里的 Cosmos 生成器来自上一次插件加载"
                        + "（热重载产生的旧类加载器实例）。生成器仍然有效，但本次加载的星球配置无法推送给它："
                        + "已加载区块与新区块都沿用旧配置。要让新配置生效，请完整重启服务器。");
            }
        } else if (!this.warnedGeneratorMismatch) {
            this.warnedGeneratorMismatch = true;
            plugin.getLogger().warning("世界 '" + world.getName() + "' 已存在，但它的生成器不是 Cosmos 星球生成器（实际: "
                    + (currentGenerator == null ? "vanilla" : currentGenerator.getClass().getName()) + "）。"
                    + "新生成的区块不会出现星球。请让世界管理插件创建该世界时指定 Cosmos 生成器"
                    + "（例如 Multiverse: /mv create " + world.getName() + " <ENV> -g Cosmos），"
                    + "或删除该世界文件夹后由 Cosmos 自行创建。");
        }
    }

    /** 类名相同但类加载器不同（热重载）时 {@code instanceof} 会失败，这里用类名兜底识别。 */
    private static boolean isOurGeneratorClass(ChunkGenerator generator) {
        return generator != null && PlanetChunkGenerator.class.getName().equals(generator.getClass().getName());
    }

    /**
     * 确保 Cosmos 世界存在并应用配置。
     *
     * <p>世界创建 / 接管 / 游戏规则 / 出生点都属于服务端级操作，只能在全局区域线程执行。
     * 插件不一定在全局线程上被启用（例如 PlugManX 的 <code>/plugman reload</code>
     * 会在玩家的区域线程里调用 {@code onEnable}），因此这里会自行检查并切到全局线程重试。</p>
     *
     * @return 可用的 Cosmos 世界；失败或已转交全局线程时返回 {@code null}
     */
    public World ensureWorld(CosmosSettings settings) {
        if (!plugin.scheduler().isGlobalThread()) {
            plugin.scheduler().global(() -> ensureWorld(settings));
            return null;
        }
        World existing = plugin.getServer().getWorld(settings.cosmosWorldName());
        if (existing != null) {
            adoptWorld(existing, settings);
            if (existing.getEnvironment() != settings.cosmosEnvironment() && !this.warnedEnvironmentMismatch) {
                this.warnedEnvironmentMismatch = true;
                plugin.getLogger().warning("世界 '" + existing.getName() + "' 已存在，环境是 "
                        + existing.getEnvironment() + "（建筑范围 " + existing.getMinHeight() + ".."
                        + (existing.getMaxHeight() - 1) + "），而配置里是 " + settings.cosmosEnvironment()
                        + "。世界环境在创建时固定，改配置对已有世界无效；已经生成过的区块也不会按新星球配置重画。"
                        + "要让新配置生效：停服 → 删除该世界目录（或改 cosmos-world-name）→ 启动。");
            }
            applyWorldSettings(existing, settings);
            return existing;
        }

        if (!settings.worldAutoCreate()) {
            plugin.getLogger().warning("Cosmos 世界 '" + settings.cosmosWorldName()
                    + "' 不存在，且 cosmos-world-auto-create=false，传送功能将不可用。");
            return null;
        }

        PlanetChunkGenerator created = new PlanetChunkGenerator(plugin.planetRegistry(), settings.cosmosBiome());
        created.setFloorY(settings.planetFloorY());
        WorldCreator creator = new WorldCreator(settings.cosmosWorldName())
                .environment(settings.cosmosEnvironment())
                .type(WorldType.NORMAL)
                .seed(settings.worldSeed())
                .generateStructures(false)
                .generator(created)
                .biomeProvider(new CosmosBiomeProvider(settings.cosmosBiome()));

        World world;
        try {
            world = plugin.getServer().createWorld(creator);
        } catch (UnsupportedOperationException unsupported) {
            // Folia 的 CraftServer#createWorld 就是抛这个异常：世界只能由外部插件/服务端创建
            plugin.getLogger().severe("当前服务端不支持 Bukkit#createWorld（Folia 未实现该 API），无法自动创建 Cosmos 世界 '"
                    + settings.cosmosWorldName() + "'。请改用支持创建世界的服务端（Paper / Canvas），"
                    + "或按 README §12 方式 B 让世界管理插件用生成器 'Cosmos' 创建该世界后 /cosmos reload。");
            return null;
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "创建 Cosmos 世界 '" + settings.cosmosWorldName() + "' 失败。", failure);
            return null;
        }
        if (world == null) {
            plugin.getLogger().severe("创建 Cosmos 世界 '" + settings.cosmosWorldName() + "' 失败。");
            return null;
        }

        this.cosmosWorld = world;
        this.generator = created;
        this.warnedGeneratorMismatch = false;
        plugin.getLogger().info("已创建 Cosmos 世界 '" + world.getName() + "'（环境 "
                + world.getEnvironment() + "，群系 " + settings.cosmosBiome().getKey() + "）");
        applyWorldSettings(world, settings);
        return world;
    }

    /** reload 时更新生成器使用的星球快照；已生成区块不受影响。 */
    public void updateRegistry(PlanetRegistry registry) {
        PlanetChunkGenerator current = this.generator;
        if (current == null) {
            // 世界可能已被外部插件重新加载：先观察一次再推进快照
            World observed = refresh();
            if (observed != null && observed.getGenerator() instanceof PlanetChunkGenerator fromWorld) {
                this.generator = fromWorld;
                current = fromWorld;
            }
        }
        if (current != null) {
            current.setRegistry(registry);
            current.setFloorY(plugin.settings().planetFloorY());
        }
    }

    /**
     * 出生点与游戏规则：全部是服务端级数据，必须在全局区域线程执行
     * （{@code World#setGameRule} 在 Folia / Canvas 上会显式校验全局线程）。
     *
     * <p>不再生成任何平台：Cosmos 是纯虚空 + 星球，出生点只做坐标设置。</p>
     */
    public void applyWorldSettings(World world, CosmosSettings settings) {
        if (!plugin.scheduler().isGlobalThread()) {
            // 兜底：调用方不在全局线程时转交（Folia / Canvas 会拒绝非全局线程写游戏规则）
            plugin.scheduler().global(() -> applyWorldSettings(world, settings));
            return;
        }
        world.setAutoSave(true);

        for (Map.Entry<String, String> entry : settings.gamerules().entrySet()) {
            applyGameRule(world, entry.getKey(), entry.getValue());
        }

        Location spawn = settings.fallbackLocation(plugin.getServer());
        if (spawn == null || spawn.getWorld() == null
                || !spawn.getWorld().getName().equals(world.getName())) {
            // 没有配置（或配置指向别的世界）时保留生成器/原版决定的出生点
            spawn = world.getSpawnLocation();
        }
        world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ(), spawn.getYaw());

        applyViewDistance(world, settings);

        syncWorldBorder(world, settings);

        if (settings.debug()) {
            plugin.getLogger().info("Cosmos 世界 '" + world.getName() + "' 出生点设为 ("
                    + spawn.getBlockX() + ", " + spawn.getBlockY() + ", "
                    + spawn.getBlockZ() + ")");
        }
    }

    /**
     * 单独调整 Cosmos 世界的视距（不影响服务端全局设置）。
     *
     * <p>跨维度传送时客户端要为落点视距内的每个区块重建网格；Cosmos 的星球是实心大球，
     * 视距 10 以上会让「进入 / 离开宇宙」那一下明显卡。这里只收紧 Cosmos 一个世界：
     * 模拟视距用 {@code cosmos-view-distance}，下发给客户端的视距用
     * {@code cosmos-send-view-distance}（可以更小，远处提前起雾但切世界更快）。</p>
     *
     * <p>世界级设置，必须在全局区域线程执行。</p>
     */
    private void applyViewDistance(World world, CosmosSettings settings) {
        try {
            int view = settings.cosmosViewDistance();
            if (view > 0) {
                world.setViewDistance(Math.max(2, view));
            }
            int effectiveView = world.getViewDistance();
            int send = settings.cosmosSendViewDistance();
            if (send > 0) {
                // Paper 要求下发视距不超过视距，配置写大了就夹住
                world.setSendViewDistance(Math.max(2, Math.min(send, effectiveView)));
            }
            if (settings.debug()) {
                plugin.getLogger().info("世界 '" + world.getName() + "' 视距: 模拟 "
                        + world.getViewDistance() + "，下发 " + world.getSendViewDistance());
            }
        } catch (RuntimeException | LinkageError failure) {
            // 老服务端没有按世界设置视距的 API：忽略，不要影响世界加载
            if (!this.warnedViewDistance) {
                this.warnedViewDistance = true;
                plugin.getLogger().warning("当前服务端不支持按世界设置视距（" + failure + "），"
                        + "已忽略 cosmos-view-distance / cosmos-send-view-distance。");
            }
        }
    }

    /**
     * 把主世界的世界边界同步到 Cosmos：中心与尺寸都按 {@code 1:scale} 换算，
     * 这样「Cosmos 的边界」对应「主世界同一条边界」，与坐标映射保持一致。
     *
     * <p>告警距离 / 时间 / 伤害沿用主世界设置。主世界未加载时跳过。</p>
     */
    public void syncWorldBorder(World cosmos, CosmosSettings settings) {
        if (cosmos == null || !settings.cosmosWorldBorderSync()) {
            return;
        }
        World main = plugin.getServer().getWorld(settings.mainWorldName());
        if (main == null || main.equals(cosmos)) {
            return;
        }
        double scale = settings.scale();
        WorldBorder source = main.getWorldBorder();
        WorldBorder target = cosmos.getWorldBorder();
        if (source == null || target == null) {
            return;
        }
        try {
            Location center = source.getCenter();
            target.setCenter(center.getX() / scale, center.getZ() / scale);
            target.setSize(Math.max(1.0D, source.getSize() / scale));
            target.setWarningDistance(source.getWarningDistance());
            target.setWarningTime(source.getWarningTime());
            target.setDamageAmount(source.getDamageAmount());
            target.setDamageBuffer(source.getDamageBuffer());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "同步世界边界失败（世界可能正在卸载）", failure);
            return;
        }
        if (settings.debug()) {
            plugin.getLogger().info("已将主世界边界同步到 '" + cosmos.getName() + "'：中心 ("
                    + target.getCenter().getX() + ", " + target.getCenter().getZ() + ")，尺寸 "
                    + target.getSize());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGameRule(World world, String rawName, String rawValue) {
        GameRule<?> rule = findGameRule(rawName);
        if (rule == null) {
            plugin.getLogger().warning("未知的游戏规则 '" + rawName + "'（cosmos-world-gamerules），已忽略。");
            return;
        }
        if (rule.getType() == Boolean.class) {
            world.setGameRule((GameRule<Boolean>) rule, parseBoolean(rawValue));
        } else if (rule.getType() == Integer.class) {
            try {
                world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(rawValue.trim()));
            } catch (NumberFormatException failure) {
                plugin.getLogger().warning("游戏规则 '" + rawName + "' 需要整数，但配置为 '" + rawValue + "'，已忽略。");
            }
        }
    }

    private static boolean parseBoolean(String rawValue) {
        return rawValue != null && (rawValue.equalsIgnoreCase("true") || rawValue.equalsIgnoreCase("1"));
    }

    /**
     * 同时接受原版 camelCase（doMobSpawning）、枚举名（DO_MOB_SPAWNING）与完整 key
     * （minecraft:do_mob_spawning）：统一去掉下划线/连字符后做忽略大小写查找。
     *
     * <p>{@code GameRule#getByName} 已废弃（forRemoval），因此改用游戏规则注册表。</p>
     */
    private static GameRule<?> findGameRule(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        return RuleIndex.RULES.get(normalizeRuleName(rawName));
    }

    private static String normalizeRuleName(String name) {
        String trimmed = name.trim();
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            trimmed = trimmed.substring(colon + 1);
        }
        return trimmed.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** 延迟初始化：注册表访问需要服务端已就绪。 */
    private static final class RuleIndex {
        private static final Map<String, GameRule<?>> RULES = build();

        private static Map<String, GameRule<?>> build() {
            Map<String, GameRule<?>> index = new HashMap<>();
            Registry<GameRule<?>> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE);
            registry.stream().forEach(rule ->
                    index.put(normalizeRuleName(rule.getKey().getKey()), rule));
            return Map.copyOf(index);
        }
    }
}
