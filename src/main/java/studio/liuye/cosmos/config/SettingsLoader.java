package studio.liuye.cosmos.config;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * config.yml 的读取与校验。
 *
 * <p>职责被刻意拆成两段，以符合 Folia 的线程模型：</p>
 * <ul>
 *   <li>{@link #read(File)} —— 纯文件 IO，可以在 AsyncScheduler 线程执行；</li>
 *   <li>{@link #parse(ConfigurationSection, Logger)} —— 会创建 {@link BlockData}、查询
 *       {@link Biome} 注册表、解析世界名，属于全局数据/注册表访问，必须在全局区域线程执行。</li>
 * </ul>
 */
public final class SettingsLoader {

    /** 程序化星球的默认材质组合。 */
    private static final List<ProceduralPlanetSettings.PaletteEntry> DEFAULT_PROCEDURAL_PALETTE = List.of(
            palette(Material.STONE, Material.DEEPSLATE),
            palette(Material.TUFF, Material.ANDESITE),
            palette(Material.SAND, Material.SANDSTONE),
            palette(Material.ICE, Material.SNOW_BLOCK),
            palette(Material.CALCITE, Material.DRIPSTONE_BLOCK),
            palette(Material.BASALT, Material.MAGMA_BLOCK));

    /** 键不存在时的哨兵值（与合法的传送高度区分开）。 */
    private static final int MISSING = Integer.MIN_VALUE;

    /** 出厂默认的主世界上升触发高度（与 config.yml / README 保持一致）。 */
    private static final int DEFAULT_TELEPORT_Y_WORLD = 400;

    /** 出厂默认的 Cosmos 回程触发高度。 */
    private static final int DEFAULT_TELEPORT_Y_COSMOS = 0;

    /** 单颗星球半径的硬上限（方块）。 */
    private static final double MAX_PLANET_RADIUS = 128.0D;

    /** 只提示一次的消息键（解析会在 onLoad / onEnable / reload 各跑一次）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SettingsLoader() {
        throw new AssertionError("utility class");
    }

    // ------------------------------------------------------------------
    // 读取（异步安全）
    // ------------------------------------------------------------------

    /**
     * 从磁盘读取 YAML。
     *
     * @throws IllegalStateException 文件缺失或语法错误
     */
    public static YamlConfiguration read(File file) {
        if (!file.isFile()) {
            throw new IllegalStateException("配置文件不存在: " + file.getAbsolutePath());
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException failure) {
            throw new IllegalStateException("读取 config.yml 失败: " + failure.getMessage(), failure);
        }
        return configuration;
    }

    // ------------------------------------------------------------------
    // 解析（全局区域线程）
    // ------------------------------------------------------------------

    public static CosmosSettings parse(ConfigurationSection config, Logger logger) {
        return parse(config, null, logger);
    }

    /**
     * @param config        config.yml
     * @param planetsConfig planets.yml（星球列表）；{@code null} 表示文件缺失
     */
    public static CosmosSettings parse(ConfigurationSection config, ConfigurationSection planetsConfig, Logger logger) {
        CosmosSettings.Builder builder = CosmosSettings.builder();

        builder.scale(boundedInt(config, "scale", 64, 1, 1_000_000, logger));

        // --- 世界名 -----------------------------------------------------
        String mainWorldName = config.getString("main-world", "");
        mainWorldName = mainWorldName == null ? "" : mainWorldName.trim();
        List<World> loadedWorlds = Bukkit.getWorlds();
        if (mainWorldName.isEmpty()) {
            mainWorldName = loadedWorlds.isEmpty() ? "world" : loadedWorlds.get(0).getName();
            logger.info("main-world 未配置，使用服务器第一个世界: " + mainWorldName);
        } else if (Bukkit.getWorld(mainWorldName) == null && !loadedWorlds.isEmpty()) {
            // onLoad 阶段世界可能还没加载，此时不报警，onEnable 会再解析一次
            logger.warning("main-world '" + mainWorldName + "' 目前未加载，主世界 -> Cosmos 的触发将不会生效，直到该世界存在。");
        }
        builder.mainWorldName(mainWorldName);

        String cosmosWorldName = config.getString("cosmos-world-name", "cosmos");
        cosmosWorldName = cosmosWorldName == null ? "cosmos" : cosmosWorldName.trim();
        if (cosmosWorldName.isEmpty()) {
            cosmosWorldName = "cosmos";
        }
        builder.cosmosWorldName(cosmosWorldName);
        if (mainWorldName.equals(cosmosWorldName)) {
            logger.severe("main-world 与 cosmos-world-name 相同 (" + cosmosWorldName
                    + ")：上升触发会把玩家再次送入同一世界，请修正配置！");
        }

        builder.cosmosEnvironment(environment(config.getString("cosmos-world-environment"), logger));
        builder.cosmosBiome(biome(config.getString("cosmos-biome"), logger));
        builder.worldSeed(config.getLong("cosmos-world-seed", 1337L));
        builder.worldAutoCreate(config.getBoolean("cosmos-world-auto-create", true));

        // --- 传送高度（两个世界共用同一个 Y）-----------------------------
        // --- 传送高度（两个世界各一个 Y）---------------------------------
        int teleportYWorld = readTeleportY(config, "teleport-y-world", DEFAULT_TELEPORT_Y_WORLD, logger);
        int teleportYCosmos = readTeleportY(config, "teleport-y-cosmos", DEFAULT_TELEPORT_Y_COSMOS, logger);
        if (teleportYWorld == MISSING || teleportYCosmos == MISSING) {
            // 迁移：旧的单个 teleport-y / main-world-to-cosmos-y 同时喂给两边
            int legacy = readTeleportY(config, "teleport-y", MISSING, logger);
            if (legacy == MISSING) {
                legacy = readTeleportY(config, "main-world-to-cosmos-y", MISSING, logger);
            }
            if (legacy != MISSING) {
                if (teleportYWorld == MISSING) {
                    teleportYWorld = legacy;
                }
                if (teleportYCosmos == MISSING) {
                    teleportYCosmos = legacy;
                }
                warnOnce(logger, "legacy-threshold", "旧的 teleport-y / main-world-to-cosmos-y 已被"
                        + " teleport-y-world 与 teleport-y-cosmos 取代（两个世界各自一个高度）。"
                        + "当前按 teleport-y-world=" + teleportYWorld + "、teleport-y-cosmos=" + teleportYCosmos
                        + " 处理，请改用新键。");
            } else {
                // 两个新键都缺失：不能再静默退到 150（出厂配置是 400 / 0，对不上会让玩家找不到入口）
                if (teleportYWorld == MISSING) {
                    teleportYWorld = DEFAULT_TELEPORT_Y_WORLD;
                }
                if (teleportYCosmos == MISSING) {
                    teleportYCosmos = DEFAULT_TELEPORT_Y_COSMOS;
                }
                warnOnce(logger, "threshold-missing", "config.yml 缺少 teleport-y-world / teleport-y-cosmos："
                        + "已按出厂默认 teleport-y-world=" + DEFAULT_TELEPORT_Y_WORLD
                        + "、teleport-y-cosmos=" + DEFAULT_TELEPORT_Y_COSMOS
                        + " 处理。请补上这两个键，否则升级后上升触发点会变。");
            }
        }
        int arrivalYOffset = boundedInt(config, "teleport-y-offset", 10, 0, 256, logger);
        builder.teleportYWorld(teleportYWorld);
        builder.teleportYCosmos(teleportYCosmos);
        builder.arrivalYOffset(arrivalYOffset);
        builder.teleportCooldownMs(Math.max(0L, config.getLong("teleport-cooldown-ms", 3000L)));
        builder.portalCooldownTicks(Math.max(0, config.getInt("portal-cooldown-ticks", 100)));
        builder.yCheckIntervalTicks(Math.max(0, config.getInt("y-check-interval-ticks", 10)));
        builder.worldCheckIntervalTicks(Math.max(0, config.getInt("world-check-interval-ticks", 100)));
        // 视距：0 = 不修改；下发视距 0 = 跟随 cosmos-view-distance
        builder.cosmosViewDistance(Math.max(0, Math.min(32, config.getInt("cosmos-view-distance", 0))));
        builder.cosmosSendViewDistance(Math.max(0, Math.min(32, config.getInt("cosmos-send-view-distance", 8))));
        builder.teleportPreloadRadius(Math.max(0, Math.min(8, config.getInt("teleport-preload-radius", 2))));

        // 旧的「固定到达高度」已取消：到达 Y = 目标世界的 teleport-y ± offset
        warnLegacyArrivalY(config, "cosmos-arrival-y", logger);
        warnLegacyArrivalY(config, "main-world-arrival-y", logger);

        // 解析会在 onLoad / onEnable / reload 各跑一次，用 warnOnce 避免重复刷屏
        World mainWorld = Bukkit.getWorld(mainWorldName);
        World cosmosWorld = Bukkit.getWorld(cosmosWorldName);
        int cosmosTop = cosmosWorld == null ? 0 : cosmosWorld.getMaxHeight() - 1;
        int cosmosBottom = cosmosWorld == null ? 0 : cosmosWorld.getMinHeight();
        if (cosmosWorld != null && (teleportYCosmos > cosmosTop || teleportYCosmos < cosmosBottom)) {
            warnOnce(logger, "threshold-outside-cosmos", "teleport-y-cosmos=" + teleportYCosmos
                    + " 在 Cosmos 世界的建筑范围 [" + cosmosBottom + ", " + cosmosTop + "] 之外（该世界环境为 "
                    + cosmosWorld.getEnvironment() + "，范围由维度类型固定：THE_END=0..255、NORMAL=-64..319）。"
                    + "传送仍然会精确落在 teleport-y-cosmos±offset（实体可以位于建筑上限之上），"
                    + "但那个高度没有方块可站，星球也无法生成在那里。"
                    + "需要「星球在传送高度以上」时，请把 cosmos-world-environment 设为 NORMAL 并重建该世界"
                    + "（停服 → 删除世界目录，或改 cosmos-world-name → 启动），并把这个高度设到 -64..319 内。");
        }
        if (cosmosWorld != null && teleportYCosmos + arrivalYOffset > cosmosTop) {
            warnOnce(logger, "arrival-range-cosmos", "去 Cosmos 的到达 Y=" + (teleportYCosmos + arrivalYOffset)
                    + " 在建筑上限 " + cosmosTop + " 之上：玩家会落在世界之上并开始下落（坐标不裁剪）。");
        }
        if (mainWorld != null) {
            int mainBottom = mainWorld.getMinHeight();
            if (teleportYWorld < mainBottom) {
                warnOnce(logger, "threshold-outside-main", "teleport-y-world=" + teleportYWorld
                        + " 低于主世界最低高度 " + mainBottom + "，而判定是 Y >= teleport-y-world ——"
                        + "条件恒成立：玩家在任何高度都会被传送去 Cosmos。"
                        + "回程落点 Y=" + (teleportYWorld - arrivalYOffset) + " 也在世界之外，玩家会掉出世界。"
                        + "要「在主世界下落到某个高度才传送」，需要把主世界判定改成 Y <=（当前是上穿触发）。");
            }
            // 不再对「teleport-y-world 高于建筑上限」告警：建筑上限只限制放方块，
            // 玩家用鞘翅/烟花可以飞到上限之上，Y >= 阈值 依然能成立。
            if (teleportYWorld - arrivalYOffset < mainBottom) {
                warnOnce(logger, "arrival-range-main", "回主世界的到达 Y=" + (teleportYWorld - arrivalYOffset)
                        + " 低于主世界高度下限 " + mainBottom + "：玩家会掉出世界。");
            }
        }

        // --- 世界边界 ---------------------------------------------------
        builder.cosmosWorldBorderSync(config.getBoolean("cosmos-world-border-sync", true));


        // --- 掉落物悬浮 -------------------------------------------------
        builder.itemFloatEnabled(config.getBoolean("item-float-enabled", true));
        builder.floatExperienceOrb(config.getBoolean("float-experience-orb", false));
        builder.floatFreezeVelocity(config.getBoolean("float-freeze-velocity", true));
        builder.floatUnlimitedLifetime(config.getBoolean("float-unlimited-lifetime", false));
        builder.floatGravityBlocks(config.getBoolean("float-gravity-blocks", true));
        builder.floatSweepIntervalTicks(Math.max(0, config.getInt("float-sweep-interval-ticks", 200)));
        // 0 = 关闭「按玩家位置重新扫描」（掉落物自己的悬浮任务不受影响）
        builder.floatSweepRadius(Math.max(0.0D, config.getDouble("float-sweep-radius", 16.0D)));

        // --- 玩家悬浮 ---------------------------------------------------
        builder.playerFloatEnabled(config.getBoolean("player-float-enabled", true));
        // 穿鞘翅的玩家交给原版滑翔：悬浮巡检下发的速度包会打断起飞/滑翔
        builder.playerFloatSkipElytra(config.getBoolean("player-float-skip-elytra", true));
        builder.playerFloatFallSpeed(Math.max(0.0D, config.getDouble("player-float-fall-speed", 0.08D)));
        builder.playerFloatIntervalTicks(Math.max(1, config.getInt("player-float-interval-ticks", 2)));

        // --- 兜底落点（仅 /cosmos tp 使用；到达坐标一律按缩放换算，不再做落点搜索）---
        builder.fallback(readFallback(config.getConfigurationSection("fallback-location"),
                new CosmosSettings.Fallback(cosmosWorldName, 0.5D, 160.0D, 0.5D, 0.0F, 0.0F),
                "fallback-location", logger));

        // --- 世界规则 ---------------------------------------------------
        Map<String, String> gamerules = new LinkedHashMap<>();
        ConfigurationSection rules = config.getConfigurationSection("cosmos-world-gamerules");
        if (rules != null) {
            for (String key : rules.getKeys(false)) {
                gamerules.put(key, String.valueOf(rules.get(key)));
            }
        }
        builder.gamerules(gamerules);

        // --- 星球：全部由程序化星球场生成（planets.yml 只有 procedural 段）---
        if (config.isSet("planets") || (planetsConfig != null && planetsConfig.isSet("planets"))) {
            warnOnce(logger, "planets-removed", "planets 段已废弃并被忽略：星球现在全部由坐标哈希生成，"
                    + "不再支持逐颗手写。请删除该段（旧的手写星球会消失，新区域会生成程序化星球）。");
        }
        // --- 程序化星球（planets.yml 的 procedural 段）--------------------
        ProceduralPlanetSettings procedural = readProcedural(planetsConfig, logger);
        builder.proceduralPlanets(procedural);
        // 星球只生成在传送高度以上：低于 teleport-y 的星球玩家永远看不到，
        // 程序化星球在 [floor, 世界顶] 之间自己夹高度。
        builder.planetFloorY(teleportYCosmos);

        builder.debug(config.getBoolean("debug", false));

        return builder.build();
    }

    // ------------------------------------------------------------------
    // 程序化星球
    // ------------------------------------------------------------------

    /**
     * 读取 {@code planets.yml} 的 {@code procedural} 段。
     *
     * <p>该段缺失时默认<b>启用</b>程序化星球：旧版本的 planets.yml 只有 11 颗固定星球，
     * 宇宙会显得非常空，默认开启可以避免升级后仍然是「一片虚空」。</p>
     */
    private static ProceduralPlanetSettings readProcedural(ConfigurationSection planetsConfig, Logger logger) {
        ConfigurationSection section = planetsConfig == null
                ? null
                : planetsConfig.getConfigurationSection("procedural");
        if (section == null) {
            ProceduralPlanetSettings defaults = defaultProcedural();
            if (WARNED.add("procedural-default")) {
                logger.info("planets.yml 未配置 procedural 段：已启用默认程序化星球（" + describe(defaults) + "）。"
                        + "如需关闭，在 planets.yml 里添加 procedural.enabled: false。");
            }
            return defaults;
        }

        boolean enabled = section.getBoolean("enabled", true);
        int cellSize = boundedInt(section, "cell-size", 96, 16, 4096, logger);
        double chance = clamp(section.getDouble("chance", 0.6D), 0.0D, 1.0D);
        double minRadius = Math.max(0.5D, section.getDouble("min-radius", 6.0D));
        // 半径必须留在自己所在的单元里：否则每个区块要枚举的单元数按 (r / cell)^2 增长，
        // cell=16 + r=300 时单区块要扫上千个单元、写百万级方块（生成线程直接卡死）。
        double radiusLimit = Math.min(MAX_PLANET_RADIUS, Math.max(0.5D, cellSize / 2.0D));
        double maxRadius = Math.max(minRadius, section.getDouble("max-radius", 28.0D));
        if (maxRadius > radiusLimit) {
            warnOnce(logger, "procedural-max-radius", "procedural.max-radius=" + trim(maxRadius)
                    + " 超过上限 " + trim(radiusLimit) + "（网格 " + cellSize + " 格时半径最多取网格的一半），"
                    + "已修正；想要更大的星球请同时调大 cell-size。");
            maxRadius = radiusLimit;
        }
        if (minRadius > maxRadius) {
            minRadius = maxRadius;
        }
        double minShell = Math.max(0.0D, section.getDouble("min-shell-thickness", 2.0D));
        double maxShell = Math.max(minShell, section.getDouble("max-shell-thickness", 8.0D));
        int minY = section.getInt("min-y", 40);
        int maxY = section.getInt("max-y", 240);
        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
            logger.warning("procedural.min-y 大于 max-y，已自动交换为 " + minY + ".." + maxY + "。");
        }
        int weight = (int) Math.round(section.getDouble("weight", 0.0D));
        long seed = section.getLong("seed", 20260101L);
        boolean spawnPlanet = section.getBoolean("spawn-planet", true);
        double spawnRadius = Math.max(1.0D, section.getDouble("spawn-radius", 42.0D));
        if (spawnRadius > MAX_PLANET_RADIUS) {
            warnOnce(logger, "procedural-spawn-radius", "procedural.spawn-radius=" + trim(spawnRadius)
                    + " 超过上限 " + trim(MAX_PLANET_RADIUS) + "，已修正。");
            spawnRadius = MAX_PLANET_RADIUS;
        }
        int spawnCenterY = section.getInt("spawn-center-y", 60);

        List<ProceduralPlanetSettings.PaletteEntry> palette = readPalette(section.getMapList("palette"), logger);
        if (palette.isEmpty()) {
            palette = DEFAULT_PROCEDURAL_PALETTE;
            warnOnce(logger, "procedural-palette", "procedural.palette 缺失或全部无效，已使用内置的 "
                    + palette.size() + " 种材质（石头/凝灰岩/沙子/冰/方解石/玄武岩）。");
        }

        ProceduralPlanetSettings settings = new ProceduralPlanetSettings(enabled, cellSize, chance,
                minRadius, maxRadius, minShell, maxShell, minY, maxY, weight, seed,
                spawnPlanet, spawnRadius, spawnCenterY, palette);
        if (settings.active() && WARNED.add("procedural-info:" + describe(settings))) {
            logger.info("程序化星球已启用：" + describe(settings));
        }
        return settings;
    }

    private static ProceduralPlanetSettings defaultProcedural() {
        return new ProceduralPlanetSettings(true, 96, 0.6D, 6.0D, 28.0D, 2.0D, 8.0D,
                40, 240, 0, 20260101L, true, 42.0D, 60, DEFAULT_PROCEDURAL_PALETTE);
    }

    /** 读取材质调色板；每项是 {@code outer-block} + {@code core-block}。 */
    private static List<ProceduralPlanetSettings.PaletteEntry> readPalette(List<Map<?, ?>> raw, Logger logger) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ProceduralPlanetSettings.PaletteEntry> palette = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<?, ?> entry = raw.get(index);
            if (entry == null) {
                continue;
            }
            String label = "procedural[" + index + "]";
            BlockData outer = blockData(entry.get("outer-block"), null, label, logger);
            if (outer == null) {
                continue;
            }
            BlockData core = blockData(entry.get("core-block"), outer, label, logger);
            palette.add(new ProceduralPlanetSettings.PaletteEntry(outer, core == null ? outer : core));
        }
        return palette;
    }

    private static ProceduralPlanetSettings.PaletteEntry palette(Material outer, Material core) {
        return new ProceduralPlanetSettings.PaletteEntry(outer.createBlockData(), core.createBlockData());
    }

    private static String describe(ProceduralPlanetSettings settings) {
        return "网格 " + settings.cellSize() + " 格、每单元概率 " + Math.round(settings.chance() * 100.0D)
                + "%、半径 " + trim(settings.minRadius()) + ".." + trim(settings.maxRadius())
                + "、Y " + settings.minY() + ".." + settings.maxY()
                + "、材质 " + settings.palette().size() + " 种"
                + (settings.spawnPlanet()
                ? "、原点地标半径 " + trim(settings.spawnRadius()) + " Y " + settings.spawnCenterY()
                : "、无原点地标");
    }

    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // 解析辅助
    // ------------------------------------------------------------------

    private static CosmosSettings.Fallback readFallback(ConfigurationSection section,
                                                        CosmosSettings.Fallback fallback,
                                                        String path,
                                                        Logger logger) {
        if (section == null) {
            return fallback;
        }
        String world = section.getString("world", fallback.worldName());
        if (world == null || world.isBlank()) {
            world = fallback.worldName();
        }
        double x = section.getDouble("x", fallback.x());
        double y = section.getDouble("y", fallback.y());
        double z = section.getDouble("z", fallback.z());
        float yaw = (float) section.getDouble("yaw", fallback.yaw());
        float pitch = (float) section.getDouble("pitch", fallback.pitch());
        if (Bukkit.getWorld(world) == null && !Bukkit.getWorlds().isEmpty()) {
            warnOnce(logger, "fallback-world-" + world,
                    path + " 指定的世界 '" + world + "' 目前不存在，该兜底落点在世界上线前不可用。");
        }
        return new CosmosSettings.Fallback(world, x, y, z, yaw, pitch);
    }

    private static World.Environment environment(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return World.Environment.THE_END;
        }
        try {
            return World.Environment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            logger.warning("cosmos-world-environment '" + raw + "' 无效（可选 NORMAL / NETHER / THE_END），已回退到 THE_END。");
            return World.Environment.THE_END;
        }
    }

    private static Biome biome(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return Biome.THE_END;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) {
            id = id.substring(colon + 1);
        }
        // Registry.BIOME 已废弃，改用新的 RegistryAccess（1.21.3+）
        try {
            Registry<Biome> biomes = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            Biome biome = biomes.get(NamespacedKey.minecraft(id));
            if (biome == null) {
                logger.warning("cosmos-biome '" + raw + "' 不存在，已回退到 the_end。");
                return Biome.THE_END;
            }
            return biome;
        } catch (RuntimeException failure) {
            // NamespacedKey.minecraft 对空格 / 中文 / 多个冒号会抛 IllegalArgumentException：
            // 这里必须接住，否则 onEnable 失败会把整个插件 disable 掉。
            logger.warning("cosmos-biome '" + raw + "' 不是合法的命名空间 ID（" + failure.getMessage()
                    + "），已回退到 the_end。");
            return Biome.THE_END;
        }
    }

    private static BlockData blockData(Object raw, BlockData fallback, String planet, Logger logger) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return Bukkit.createBlockData(text);
        } catch (RuntimeException failure) {
            if (fallback == null) {
                logger.warning("星球 '" + planet + "' 的方块 '" + text + "' 无法解析，已跳过该项。");
                return null;
            }
            logger.warning("星球 '" + planet + "' 的方块 '" + text + "' 无法解析，已回退到 "
                    + fallback.getMaterial().getKey() + "。");
            return fallback;
        }
    }

    /** 旧的「固定到达高度」配置项已废弃：目标 Y 现在由出发高度 ± {@code teleport-y-offset} 推导。 */
    private static void warnLegacyArrivalY(ConfigurationSection config, String path, Logger logger) {
        if (config.isSet(path)) {
            warnOnce(logger, "legacy-" + path, path + " 已废弃并被忽略：传送目标 Y 现在由出发高度 ± teleport-y-offset 计算。"
                    + "请删除该项（或参考配置注释迁移到 teleport-y-offset）。");
        }
    }

    /** 同一进程内只提示一次：配置会被 onLoad / onEnable / reload 反复解析。 */
    private static void warnOnce(Logger logger, String key, String message) {
        if (WARNED.add(key)) {
            logger.warning(message);
        }
    }

    /**
     * 读取一个传送高度键。
     *
     * <p>{@code getInt} 在「键不存在」和「值不是整数」（例如 YAML 里写成 {@code "400"}）时都会静默返回默认值，
     * 旧实现因此悄悄退到 150；这里把两种情况分开：不存在返回 {@link #MISSING} 交给迁移逻辑，
     * 类型错误则告警并按 {@code fallback} 处理。</p>
     */
    private static int readTeleportY(ConfigurationSection config, String path, int fallback, Logger logger) {
        Object raw = config.get(path);
        if (raw == null) {
            return MISSING;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException failure) {
            warnOnce(logger, "bad-int-" + path, path + " 的值 '" + raw + "' 不是整数，已按 " + fallback
                    + " 处理（请检查是否多写了引号或小数点）。");
            return fallback;
        }
    }

    private static int boundedInt(ConfigurationSection config, String path, int fallback, int min, int max, Logger logger) {
        if (config == null) {
            return fallback;
        }
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            int clamped = Math.max(min, Math.min(max, value));
            logger.warning(path + " = " + value + " 超出允许范围 [" + min + ", " + max + "]，已修正为 " + clamped + "。");
            return clamped;
        }
        return value;
    }
}
