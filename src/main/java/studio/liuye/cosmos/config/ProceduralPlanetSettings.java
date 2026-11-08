package studio.liuye.cosmos.config;

import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * 程序化星球场（procedural planet field）的不可变配置。
 *
 * <p>星球坐标不写在配置里：生成器按固定的网格单元 + 确定性哈希决定每个单元里
 * 是否有一颗星球、它的球心 / 半径 / 外壳厚度 / 材质。同一份配置与同一个 chunk 坐标
 * 永远得到同一颗星球，因此区块卸载重载不会漂移。</p>
 *
 * <p>{@code spawnPlanet} 让 {@code (0,0)} 所在的单元必定有一颗位于原点的星球，
 * 作为进入 Cosmos 时的地标（否则落点附近可能什么都没有）。</p>
 *
 * @param enabled            是否启用程序化星球
 * @param cellSize           水平网格单元边长（格）；每个单元最多一颗星球
 * @param chance             每个单元出现星球的概率（0..1）
 * @param minRadius          最小外半径（格）
 * @param maxRadius          最大外半径（格）
 * @param minShellThickness  最小外壳厚度（格）
 * @param maxShellThickness  最大外壳厚度（格）
 * @param minY               球心最小 Y（还会被世界建筑范围和 teleport-y 进一步裁剪）
 * @param maxY               球心最大 Y
 * @param weight             程序化星球之间重叠时的绘制权重（大者覆盖小者）
 * @param seed               程序化星球的随机种子
 * @param spawnPlanet        是否在原点强制生成一颗地标星球
 * @param spawnRadius        地标星球的半径（格）
 * @param spawnCenterY       地标星球的球心 Y
 * @param palette            材质调色板（外壳 / 核心成对出现）
 */
public record ProceduralPlanetSettings(
        boolean enabled,
        int cellSize,
        double chance,
        double minRadius,
        double maxRadius,
        double minShellThickness,
        double maxShellThickness,
        int minY,
        int maxY,
        int weight,
        long seed,
        boolean spawnPlanet,
        double spawnRadius,
        int spawnCenterY,
        List<PaletteEntry> palette
) {

    /** 一种星球材质组合。 */
    public record PaletteEntry(BlockData outerBlock, BlockData coreBlock) {
    }

    public ProceduralPlanetSettings {
        palette = palette == null ? List.of() : List.copyOf(palette);
    }

    /** 关闭状态（不生成任何程序化星球）。 */
    public static ProceduralPlanetSettings disabled() {
        return new ProceduralPlanetSettings(false, 96, 0.0D, 6.0D, 28.0D, 2.0D, 8.0D,
                40, 240, 0, 0L, false, 42.0D, 60, List.of());
    }

    /**
     * 是否真的会生成星球：开关打开、有调色板、半径与网格合法，
     * 并且要么有随机星球、要么有地标星球。
     * {@code PlanetRegistry} 用它决定「空世界」判定。
     */
    public boolean active() {
        return enabled
                && cellSize > 0
                && !palette.isEmpty()
                && maxRadius >= 0.5D
                && (chance > 0.0D || spawnPlanet);
    }

    /** 生成器枚举单元时需要考虑的最大半径（地标星球可能比普通星球大）。 */
    public double widestRadius() {
        return spawnPlanet ? Math.max(maxRadius, spawnRadius) : maxRadius;
    }
}
