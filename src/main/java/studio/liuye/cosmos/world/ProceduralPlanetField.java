package studio.liuye.cosmos.world;

import studio.liuye.cosmos.config.PlanetSettings;
import studio.liuye.cosmos.config.ProceduralPlanetSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * 程序化星球场：按固定网格 + 确定性哈希生成星球，不依赖生成顺序。
 *
 * <h2>为什么是「网格 + 哈希」</h2>
 * <p>每个水平网格单元 {@code (cellX, cellZ)} 最多有一颗星球。单元内是否生成、球心、
 * 半径、外壳厚度、材质全部由 {@code (seed, cellX, cellZ)} 哈希得到，因此：</p>
 * <ul>
 *   <li>同一坐标永远得到同一颗星球（区块重载不漂移）；</li>
 *   <li>不需要保存任何状态，生成器可以并行调用；</li>
 *   <li>想知道某个 chunk 里有哪些星球，只需枚举它周围的单元。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>本类不可变，所有方法都是纯函数，可被多个区块生成线程并发调用。</p>
 *
 * <h2>高度</h2>
 * <p>球心 Y 会被夹到 {@code [minY + radius, maxY - radius]}，其中 {@code minY / maxY}
 * 来自当前 {@code ChunkData}（世界建筑范围 ∩ {@code teleport-y}）。因此同一个种子在不同
 * 高度的世界（THE_END 0..255 / NORMAL -64..319）里都会把星球放在可见范围内。</p>
 *
 * <h2>原点地标</h2>
 * <p>{@code spawn-planet: true} 时单元 {@code (0,0)} 必定有一颗球心在 {@code (0, spawnCenterY, 0)}
 * 的星球（名为 {@code spawn}），它不受 {@code chance} 影响，用来保证进入 Cosmos 时
 * 抬头就能看到一颗星球，而不是一片虚空。</p>
 */
public final class ProceduralPlanetField {

    /** 关闭状态的共享实例。 */
    private static final ProceduralPlanetField DISABLED =
            new ProceduralPlanetField(ProceduralPlanetSettings.disabled(), 0L);

    /** 黄金分割常数（SplitMix64 里用来打散相邻 salt）。 */
    private static final long PHI = 0x9E3779B97F4A7C15L;

    private final ProceduralPlanetSettings settings;
    private final long seed;
    private final boolean active;

    public ProceduralPlanetField(ProceduralPlanetSettings settings) {
        this(settings, settings == null ? 0L : settings.seed());
    }

    public ProceduralPlanetField(ProceduralPlanetSettings settings, long seed) {
        this.settings = settings == null ? ProceduralPlanetSettings.disabled() : settings;
        this.seed = seed;
        this.active = this.settings.active();
    }

    public static ProceduralPlanetField disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return active;
    }

    public ProceduralPlanetSettings settings() {
        return settings;
    }

    /**
     * 枚举与某个 chunk 的水平包围盒相交的程序化星球。
     *
     * @param baseX  chunk 最小方块 X
     * @param baseZ  chunk 最小方块 Z
     * @param minY  允许生成的最低 Y（含）
     * @param maxY  允许生成的最高 Y（含）
     * @return 与 chunk 水平范围相交的星球（可能为空）
     */
    public List<Planet> planetsNear(int baseX, int baseZ, int minY, int maxY) {
        if (!active) {
            return List.of();
        }
        int cellSize = settings.cellSize();
        double maxRadius = settings.widestRadius();

        int minCellX = (int) Math.floor((baseX - maxRadius) / cellSize);
        int maxCellX = (int) Math.floor((baseX + 15 + maxRadius) / cellSize);
        int minCellZ = (int) Math.floor((baseZ - maxRadius) / cellSize);
        int maxCellZ = (int) Math.floor((baseZ + 15 + maxRadius) / cellSize);

        List<Planet> planets = new ArrayList<>(4);
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                Planet planet = planetFor(cellX, cellZ, minY, maxY);
                if (planet == null) {
                    continue;
                }
                if (planet.maxBlockX() < baseX || planet.minBlockX() > baseX + 15
                        || planet.maxBlockZ() < baseZ || planet.minBlockZ() > baseZ + 15
                        || planet.maxBlockY() < minY || planet.minBlockY() > maxY) {
                    continue;
                }
                planets.add(planet);
            }
        }
        return planets;
    }

    /** 计算某个网格单元里的星球；该单元没有星球时返回 {@code null}。 */
    Planet planetFor(int cellX, int cellZ, int minY, int maxY) {
        long hash = mix(seed
                ^ (cellX * 0x9E3779B97F4A7C15L)
                ^ (cellZ * 0xC2B2AE3D27D4EB4FL));

        // 原点单元是地标星球：不掷骰子、球心固定在 (0, spawnCenterY, 0)
        boolean spawn = settings.spawnPlanet() && cellX == 0 && cellZ == 0;
        if (!spawn && unit(hash, 0) >= settings.chance()) {
            return null;
        }

        int cellSize = settings.cellSize();
        double radius = spawn
                ? settings.spawnRadius()
                : lerp(settings.minRadius(), settings.maxRadius(), unit(hash, 1));
        double centerX = spawn ? 0.0D : cellX * (double) cellSize + cellSize * unit(hash, 2);
        double centerZ = spawn ? 0.0D : cellZ * (double) cellSize + cellSize * unit(hash, 3);

        List<ProceduralPlanetSettings.PaletteEntry> palette = settings.palette();
        int paletteIndex = Math.min(palette.size() - 1,
                (int) Math.floor(unit(hash, 4) * palette.size()));
        paletteIndex = Math.max(0, paletteIndex);
        ProceduralPlanetSettings.PaletteEntry entry = palette.get(paletteIndex);

        double shell = lerp(settings.minShellThickness(), settings.maxShellThickness(), unit(hash, 5));
        // 地标球心也要夹：否则 spawn-center-y 配得比世界还高时，整颗地标会被画到世界外、静默消失
        double centerY = spawn
                ? clampCenterY(settings.spawnCenterY(), radius, minY, maxY)
                : chooseCenterY(hash, radius, minY, maxY);

        PlanetSettings planetSettings = new PlanetSettings(
                spawn ? "spawn" : "proc-" + cellX + "-" + cellZ,
                true,
                centerX,
                centerY,
                centerZ,
                radius,
                shell,
                -1.0D,
                entry.outerBlock(),
                entry.coreBlock(),
                settings.weight());
        return new Planet(planetSettings);
    }

    /** 把固定的球心 Y 夹进 {@code [minY + radius, maxY - radius]}；放不下时退回可区间的中点。 */
    static double clampCenterY(double centerY, double radius, int minY, int maxY) {
        double low = minY + radius;
        double high = maxY - radius;
        if (low > high) {
            return (minY + maxY) / 2.0D;
        }
        return Math.max(low, Math.min(high, centerY));
    }

    /**
     * 选择球心 Y，使整颗星球尽量落在 {@code [minY, maxY]} 内，同时尊重配置的 Y 范围。
     * 如果星球比可用高度还高（极端配置），就放在中间，剩下的交给绘制阶段裁剪。
     */
    private double chooseCenterY(long hash, double radius, int minY, int maxY) {
        double low = minY + radius;
        double high = maxY - radius;
        if (low > high) {
            return (minY + maxY) / 2.0D;
        }
        double configLow = Math.max(low, settings.minY() + radius);
        double configHigh = Math.min(high, settings.maxY() - radius);
        if (configLow > configHigh) {
            // 配置范围与世界范围没有交集：以世界范围为准
            configLow = low;
            configHigh = high;
        }
        return configLow + (configHigh - configLow) * unit(hash, 6);
    }

    // ------------------------------------------------------------------
    // 确定性哈希（SplitMix64 变体）
    // ------------------------------------------------------------------

    private double unit(long hash, int salt) {
        long mixed = mix(hash + (salt + 1) * PHI);
        // 取高 53 位，得到 [0, 1) 的均匀分布
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double lerp(double min, double max, double t) {
        return min + (max - min) * t;
    }
}
