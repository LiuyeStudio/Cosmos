package studio.liuye.cosmos;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import studio.liuye.cosmos.command.CosmosCommand;
import studio.liuye.cosmos.config.CosmosSettings;
import studio.liuye.cosmos.config.ProceduralPlanetSettings;
import studio.liuye.cosmos.config.SettingsLoader;
import studio.liuye.cosmos.item.ItemFloatService;
import studio.liuye.cosmos.listener.ItemFloatListener;
import studio.liuye.cosmos.listener.MovementListener;
import studio.liuye.cosmos.listener.PlayerLifecycleListener;
import studio.liuye.cosmos.listener.WorldBorderSyncListener;
import studio.liuye.cosmos.platform.FoliaSupport;
import studio.liuye.cosmos.platform.RegionSchedulerFacade;
import studio.liuye.cosmos.player.PlayerFloatService;
import studio.liuye.cosmos.teleport.TeleportCooldowns;
import studio.liuye.cosmos.teleport.TeleportService;
import studio.liuye.cosmos.util.MessageService;
import studio.liuye.cosmos.world.CosmosBiomeProvider;
import studio.liuye.cosmos.world.CosmosWorldService;
import studio.liuye.cosmos.world.PlanetChunkGenerator;
import studio.liuye.cosmos.world.PlanetRegistry;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Cosmos 主类。
 *
 * <h2>启动顺序（与 Folia 的关系）</h2>
 * <ol>
 *   <li>{@link #onLoad()}：运行在全局线程，早于任何世界加载。这里先读一次配置，
 *       这样 {@link #getDefaultWorldGenerator(String, String)} 在服务端加载 Cosmos 世界时
 *       就能拿到真实的星球列表（否则出生区块会用空列表生成，变成永久虚空）。</li>
 *   <li>{@link #onEnable()}：仍在全局线程。构造各个服务、再读一次配置（此时
 *       {@code Bukkit.getWorlds()} 已完整，可正确解析默认主世界）、创建/加载 Cosmos 世界、
 *       注册监听器与命令。世界数据的写入被推迟到出生区块的 RegionScheduler 任务。</li>
 *   <li>{@code /cosmos reload}：AsyncScheduler 读盘 → GlobalRegionScheduler 解析与应用 →
 *       世界数据由区域任务写入。</li>
 * </ol>
 */
public final class CosmosPlugin extends JavaPlugin {

    private final AtomicReference<CosmosSettings> settings = new AtomicReference<>();
    private final TeleportCooldowns cooldowns = new TeleportCooldowns();

    private volatile PlanetRegistry planetRegistry = PlanetRegistry.empty();

    private RegionSchedulerFacade scheduler;
    private CosmosWorldService worlds;
    private TeleportService teleports;
    private ItemFloatService itemFloatService;
    private PlayerFloatService playerFloatService;
    private final MessageService messages = new MessageService(this);

    /** onLoad 阶段创建的生成器（世界可能早于 onEnable 被加载）。 */
    private volatile PlanetChunkGenerator preEnableGenerator;

    /** Cosmos 世界看门狗：外部世界管理插件卸载/重新加载世界后自动重新接管。 */
    private volatile ScheduledTask worldWatchdog;

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void onLoad() {
        this.scheduler = new RegionSchedulerFacade(this);
        saveDefaultConfig();
        saveBundled("planets.yml");
        saveBundled("message.yml");

        try {
            CosmosSettings loaded = SettingsLoader.parse(getConfig(), readBundled("planets.yml"), getLogger());
            this.settings.set(loaded);
            this.planetRegistry = PlanetRegistry.of(loaded.proceduralPlanets(), getLogger());
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "onLoad 阶段解析配置失败，稍后会在 onEnable 重试。", failure);
        }
        messages.applyFile(messageFile());
    }

    @Override
    public void onEnable() {
        getLogger().info("Cosmos 启动中 —— 运行环境: " + FoliaSupport.serverSummary()
                + "，Folia=" + FoliaSupport.isFolia());

        if (scheduler == null) {
            scheduler = new RegionSchedulerFacade(this);
        }
        this.worlds = new CosmosWorldService(this);
        this.teleports = new TeleportService(this, worlds, cooldowns);
        this.itemFloatService = new ItemFloatService(this);
        this.playerFloatService = new PlayerFloatService(this);

        // 世界此时已全部加载，main-world 的默认解析才是准确的
        CosmosSettings loaded;
        try {
            loaded = SettingsLoader.parse(getConfig(), readBundled("planets.yml"), getLogger());
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "解析 config.yml 失败，Cosmos 将不会启动。", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        applySettings(loaded);
        messages.applyFile(messageFile());

        PlanetChunkGenerator pending = this.preEnableGenerator;
        if (pending != null) {
            worlds.adoptGenerator(pending);
        }

        startWorldWatchdog();
        registerListeners();
        registerCommand();

        // 世界创建 / 游戏规则 / 出生点都是服务端级操作：必须在全局区域线程执行。
        // onEnable 不保证在全局线程（例如 PlugManX 的 /plugman reload 在玩家区域线程里调用它）。
        scheduler.global(() -> {
            if (worlds.ensureWorld(loaded) == null) {
                getLogger().warning("Cosmos 世界当前不可用：传送功能会在世界可用前保持静默，"
                        + "可使用 /cosmos reload 重试创建。");
                return;
            }
            // 世界就绪之后才能扫到已有掉落物：抢在建世界之前调用只会扫到 null。
            itemFloatService.sweepExisting();
        });

        // 在线玩家的悬浮状态与世界无关，可以立即校正
        playerFloatService.refreshAll();
    }

    @Override
    public void onDisable() {
        ScheduledTask watchdog = this.worldWatchdog;
        if (watchdog != null) {
            watchdog.cancel();
            this.worldWatchdog = null;
        }
        if (teleports != null) {
            teleports.shutdown();
        }
        if (itemFloatService != null) {
            itemFloatService.shutdown();
        }
        if (playerFloatService != null) {
            playerFloatService.shutdown();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        getLogger().info("Cosmos 已关闭。");
    }

    /**
     * 服务端（或世界管理插件）加载 / 创建自定义生成器世界时调用。
     *
     * <p>必须实现，否则世界重启后无法恢复自定生成器。调用时机可能早于 {@link #onLoad()}：
     * 默认 {@code load: POSTWORLD} 时世界加载先于 onLoad 执行，因此这里会在必要时
     * 就地补读一次 config.yml，保证出生区块第一次生成就是完整星球。</p>
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        ensureGeneratorRegistryReady();
        CosmosSettings current = this.settings.get();
        Biome biome = current == null ? Biome.THE_END : current.cosmosBiome();
        PlanetChunkGenerator generator = new PlanetChunkGenerator(this.planetRegistry, biome);
        if (current != null) {
            generator.setFloorY(current.planetFloorY());
        }
        this.preEnableGenerator = generator;
        if (this.worlds != null) {
            this.worlds.adoptGenerator(generator);
        }
        getLogger().info("为世界 '" + worldName + "' 提供 Cosmos 星球生成器 (id=" + id + ")");
        return generator;
    }

    /**
     * 世界管理插件只指定了生成器、没有指定 biome provider 时的兜底。
     */
    @Override
    public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        ensureGeneratorRegistryReady();
        CosmosSettings current = this.settings.get();
        return new CosmosBiomeProvider(current == null ? Biome.THE_END : current.cosmosBiome());
    }

    /**
     * 若配置还没解析（世界加载早于 onLoad），就地读一次 config.yml。
     * 只在世界加载线程上调用，且幂等。
     */
    private synchronized void ensureGeneratorRegistryReady() {
        if (this.settings.get() != null) {
            return;
        }
        File dataFolder = getDataFolder();
        if (dataFolder == null) {
            // 极早期加载（世界先于插件初始化）时数据目录还不存在：不能用 new File(null, ...)
            getLogger().warning("插件数据目录尚未初始化（世界加载早于插件初始化），"
                    + "Cosmos 生成器暂时使用空星球列表；下一次世界加载会恢复正常。");
            return;
        }
        File file = new File(dataFolder, "config.yml");
        if (!file.isFile()) {
            // 首次启动且世界加载早于 onLoad：补一份默认配置，否则生成器只能拿到空星球列表
            try {
                saveDefaultConfig();
                saveBundled("planets.yml");
                saveBundled("message.yml");
            } catch (RuntimeException failure) {
                getLogger().log(Level.WARNING, "生成默认配置文件失败", failure);
            }
        }
        if (!file.isFile()) {
            getLogger().warning("世界加载阶段找不到 config.yml，Cosmos 生成器暂时使用空星球列表。");
            return;
        }
        try {
            CosmosSettings parsed = SettingsLoader.parse(SettingsLoader.read(file),
                    readBundled("planets.yml"), getLogger());
            this.settings.set(parsed);
            this.planetRegistry = PlanetRegistry.of(parsed.proceduralPlanets(), getLogger());
            messages.applyFile(messageFile());
            getLogger().info("世界加载阶段提前读取了配置（程序化星球: "
                    + (planetRegistry.hasProcedural() ? "启用" : "关闭") + "）。");
        } catch (RuntimeException failure) {
            getLogger().log(Level.SEVERE, "世界加载阶段读取配置失败", failure);
        }
    }

    /** 把插件内置的默认文件写到数据目录（已存在则不覆盖）。 */
    private void saveBundled(String fileName) {
        if (!new File(getDataFolder(), fileName).isFile()) {
            try {
                saveResource(fileName, false);
            } catch (RuntimeException failure) {
                getLogger().log(Level.WARNING, "生成默认 " + fileName + " 失败", failure);
            }
        }
    }

    /** 读取数据目录里的 YAML；不存在或解析失败返回 {@code null}。 */
    private YamlConfiguration readBundled(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.isFile()) {
            return null;
        }
        try {
            return SettingsLoader.read(file);
        } catch (RuntimeException failure) {
            getLogger().warning("读取 " + fileName + " 失败: " + failure.getMessage());
            return null;
        }
    }

    private File messageFile() {
        return new File(getDataFolder(), "message.yml");
    }

    /**
     * Cosmos 世界看门狗：只观察、不创建。
     *
     * <p>世界管理插件（Multiverse / MyWorlds / PhantomWorlds 等）可能在本插件启用之后
     * 才加载 Cosmos 世界，或在运行期卸载/重载它。看门狗每
     * {@code world-check-interval-ticks} 在全局区域线程上比对一次引用，发现变化就重新接管，
     * 不会创建、卸载或改写任何世界。</p>
     */
    private void startWorldWatchdog() {
        ScheduledTask previousTask = this.worldWatchdog;
        if (previousTask != null) {
            previousTask.cancel();
            this.worldWatchdog = null;
        }
        if (worlds == null) {
            return;
        }
        int interval = Math.max(0, settings().worldCheckIntervalTicks());
        if (interval == 0) {
            return;
        }
        long ticks = Math.max(20L, interval);
        this.worldWatchdog = scheduler.globalTimer(() -> {
            if (worlds != null) {
                worlds.refresh();
            }
        }, ticks, ticks);
    }

    // ==================================================================
    // 配置
    // ==================================================================

    private void applySettings(CosmosSettings loaded) {
        CosmosSettings previous = settings.getAndSet(loaded);

        PlanetRegistry registry = PlanetRegistry.of(loaded.proceduralPlanets(), getLogger());
        this.planetRegistry = registry;
        if (worlds != null) {
            worlds.updateRegistry(registry);
        }

        if (previous != null && !previous.cosmosWorldName().equals(loaded.cosmosWorldName())) {
            getLogger().info("Cosmos 世界名已从 '" + previous.cosmosWorldName() + "' 变为 '"
                    + loaded.cosmosWorldName() + "'，旧世界保持加载。");
        }
        if (worlds != null) {
            startWorldWatchdog();
        }
    }

    /**
     * reload：异步读盘 → 全局线程校验/应用 → 区域线程写入世界数据。
     * 全程不占用区域 tick，也不会在异步线程触碰 Bukkit 世界对象。
     */
    public void reloadAsync(CommandSender sender) {
        messages.send(sender, "reload-start");
        File file = new File(getDataFolder(), "config.yml");

        // AsyncScheduler：纯文件 IO（config.yml + planets.yml + message.yml）
        scheduler.async(() -> {
            YamlConfiguration yaml;
            YamlConfiguration planetsYaml;
            YamlConfiguration messageYaml;
            try {
                yaml = SettingsLoader.read(file);
            } catch (RuntimeException failure) {
                getLogger().log(Level.WARNING, "读取 config.yml 失败", failure);
                // 消息发送内部会自动切回发送者的区域线程
                messages.send(sender, "reload-failed", "reason", String.valueOf(failure.getMessage()));
                return;
            }
            planetsYaml = readBundled("planets.yml");
            messageYaml = readBundled("message.yml");

            // GlobalRegionScheduler：解析（创建 BlockData / 查询注册表）与应用
            scheduler.global(() -> {
                CosmosSettings loaded;
                try {
                    loaded = SettingsLoader.parse(yaml, planetsYaml, getLogger());
                } catch (RuntimeException failure) {
                    getLogger().log(Level.SEVERE, "解析配置失败", failure);
                    messages.send(sender, "reload-failed", "reason", String.valueOf(failure.getMessage()));
                    return;
                }
                messages.apply(messageYaml);

                applySettings(loaded);
                World cosmos = worlds.ensureWorld(loaded);

                teleports.restartMonitoring();
                itemFloatService.restartMonitoring();
                itemFloatService.sweepExisting();
                playerFloatService.refreshAll();

                if (cosmos == null) {
                    messages.send(sender, "reload-success-no-world");
                } else {
                    messages.send(sender, "reload-success",
                            "scale", loaded.scaleInt());
                }
            });
        });
    }

    /** {@code /cosmos info}：全局线程读取快照并回复。 */
    public void sendInfo(CommandSender sender) {
        scheduler.global(() -> {
            CosmosSettings current = settings.get();
            if (current == null) {
                messages.send(sender, "info-unavailable");
                return;
            }
            World main = getServer().getWorld(current.mainWorldName());
            // refresh 只观察不创建：/cosmos info 也能反映被世界管理插件卸载/重载后的真实状态
            World cosmos = worlds.refresh();

            messages.send(sender, "info-header");
            messages.send(sender, "info-runtime",
                    "servers", FoliaSupport.serverSummary(),
                    "folia", FoliaSupport.isFolia());
            messages.send(sender, "info-scale", "scale", current.scaleInt());
            messages.send(sender, main == null ? "info-main-world-missing" : "info-main-world",
                    "world", current.mainWorldName());
            messages.send(sender, cosmos == null ? "info-cosmos-world-missing" : "info-cosmos-world",
                    "world", current.cosmosWorldName(),
                    "environment", cosmos == null ? "-" : cosmos.getEnvironment());
            messages.send(sender, "info-teleport",
                    "y-world", current.teleportYWorld(),
                    "y-cosmos", current.teleportYCosmos(),
                    "scale", current.scaleInt(),
                    "up", current.teleportYCosmos() + current.arrivalYOffset(),
                    "down", current.teleportYWorld() - current.arrivalYOffset());
            messages.send(sender, "info-border",
                    "border", current.cosmosWorldBorderSync()
                            ? messages.raw("border-synced", "scale", current.scaleInt())
                            : messages.raw("border-not-synced"));
            messages.send(sender, "info-status",
                    "cooldown", current.teleportCooldownMs(),
                    "items", messages.raw(current.itemFloatEnabled() ? "enabled" : "disabled"),
                    "players", messages.raw(current.playerFloatEnabled() ? "enabled" : "disabled"));

            if (planetRegistry.hasProcedural()) {
                ProceduralPlanetSettings procedural = current.proceduralPlanets();
                messages.send(sender, "info-procedural",
                        "cell", procedural.cellSize(),
                        "chance", Math.round(procedural.chance() * 100.0D) + "%",
                        "min", number(procedural.minRadius()),
                        "max", number(procedural.maxRadius()),
                        "y-min", procedural.minY(),
                        "y-max", procedural.maxY(),
                        "palette", procedural.palette().size(),
                        "spawn", procedural.spawnPlanet()
                                ? "半径 " + number(procedural.spawnRadius()) + " / Y " + procedural.spawnCenterY()
                                : "无");
            } else {
                messages.send(sender, "info-procedural-off");
            }

            if (sender instanceof Player player) {
                // 玩家坐标只能在其所属区域线程读取，因此把这一行单独调度过去
                scheduler.entity(player, () -> sendPlayerCoordinates(player, current), null);
            }
        });
    }

    /** 在玩家所属区域线程执行：显示当前坐标与换算结果。 */
    private void sendPlayerCoordinates(Player player, CosmosSettings current) {
        double scale = current.scale();
        int arrival = current.isCosmosWorld(player.getWorld())
                ? current.teleportYWorld() - current.arrivalYOffset()
                : current.teleportYCosmos() + current.arrivalYOffset();
        boolean inCosmos = current.isCosmosWorld(player.getWorld());
        if (!inCosmos && !current.isMainWorld(player.getWorld())) {
            return;
        }
        messages.send(player, inCosmos ? "info-coords-cosmos" : "info-coords-main",
                "x", String.format(Locale.ROOT, "%.1f", player.getLocation().getX()),
                "y", String.format(Locale.ROOT, "%.1f", player.getLocation().getY()),
                "z", String.format(Locale.ROOT, "%.1f", player.getLocation().getZ()),
                "main-x", String.format(Locale.ROOT, "%.1f",
                        inCosmos ? player.getLocation().getX() * scale : player.getLocation().getX() / scale),
                "main-z", String.format(Locale.ROOT, "%.1f",
                        inCosmos ? player.getLocation().getZ() * scale : player.getLocation().getZ() / scale),
                "scale", current.scaleInt(),
                "arrival", arrival);
    }

    // ==================================================================
    // 注册
    // ==================================================================

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new MovementListener(teleports), this);
        getServer().getPluginManager().registerEvents(new ItemFloatListener(itemFloatService), this);
        getServer().getPluginManager().registerEvents(new WorldBorderSyncListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(teleports, itemFloatService, playerFloatService), this);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("cosmos");
        if (command == null) {
            getLogger().warning("plugin.yml 中未声明 /cosmos 命令，命令不可用。");
            return;
        }
        CosmosCommand executor = new CosmosCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    // ==================================================================
    // 访问器
    // ==================================================================

    /** 当前配置快照（永不返回 null；初始化前返回一份默认值）。 */
    public CosmosSettings settings() {
        CosmosSettings current = settings.get();
        return current != null ? current : CosmosSettings.builder().build();
    }

    public RegionSchedulerFacade scheduler() {
        return scheduler;
    }

    public PlanetRegistry planetRegistry() {
        return planetRegistry;
    }

    public CosmosWorldService worlds() {
        return worlds;
    }

    public TeleportService teleports() {
        return teleports;
    }

    public TeleportCooldowns cooldowns() {
        return cooldowns;
    }

    public ItemFloatService itemFloatService() {
        return itemFloatService;
    }

    public PlayerFloatService playerFloat() {
        return playerFloatService;
    }

    /** 统一的消息服务（message.yml）。 */
    public MessageService messages() {
        return messages;
    }

    /** 整数值不带小数点，其余保留一位（用于 info 输出半径）。 */
    private static String number(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
