package studio.liuye.cosmos.config;

import org.bukkit.block.data.BlockData;

/**
 * 单个星球的不可变配置（解析后的结果）。
 *
 * <p>{@link BlockData} 在构造时解析一次并复用；生成器只读取、不修改，
 * 因此可以安全地被多个区块生成线程并发读取（{@link org.bukkit.generator.ChunkGenerator#isParallelCapable()} = true）。</p>
 *
 * @param name           星球名（唯一，用于日志）
 * @param enabled        是否启用
 * @param centerX        球心 X
 * @param centerY        球心 Y
 * @param centerZ        球心 Z
 * @param radius         外半径（格）
 * @param shellThickness 外壳厚度（格），仅在 {@code coreRadius < 0} 时参与计算
 * @param coreRadius     核心半径；{@code < 0} 表示自动 = radius - shellThickness
 * @param outerBlock     外壳方块
 * @param coreBlock      核心方块
 * @param weight         重叠时的优先级（大者覆盖小者）
 */
public record PlanetSettings(
        String name,
        boolean enabled,
        double centerX,
        double centerY,
        double centerZ,
        double radius,
        double shellThickness,
        double coreRadius,
        BlockData outerBlock,
        BlockData coreBlock,
        int weight
) {

    /**
     * 实际核心半径：显式配置优先，否则用 外半径 - 外壳厚度。
     * 结果一定落在 {@code [0, radius]} 内。
     */
    public double resolvedCoreRadius() {
        double raw = coreRadius >= 0.0D ? coreRadius : radius - Math.max(0.0D, shellThickness);
        return Math.max(0.0D, Math.min(radius, raw));
    }
}
