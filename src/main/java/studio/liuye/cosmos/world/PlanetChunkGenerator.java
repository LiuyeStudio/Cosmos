package studio.liuye.cosmos.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Cosmos 星球世界生成器。
 *
 * <h2>确定性</h2>
 * <p>每个方块是否属于某个星球完全由该星球的参数与其方块坐标决定（纯数学、无随机数、
 * 不依赖生成顺序）。因此：</p>
 * <ul>
 *   <li>区块被卸载后重新加载，生成结果完全一致；</li>
 *   <li>不会因为“先生成哪个区块”而出现偏移或截断；</li>
 *   <li>不存在“补写已完成区块”的路径 —— 生成阶段只写 {@code WorldGenRegion} 提供的
 *       {@link ChunkData}，绝不调用 {@code Bukkit.getWorld().getBlockAt(...).setType(...)}。</li>
 * </ul>
 *
 * <h2>Folia / 并行安全</h2>
 * <p>生成器无状态（除一个 volatile 快照引用），符合 ChunkGenerator 文档要求的
 * “必须完全线程安全、可被多个调用者并发调用”。所有对 {@link ChunkData} 的写入都限制在
 * {@code 0 <= x,z <= 15} 与 {@code [minHeight, maxHeight)} 内，不会越界写到其它区块。</p>
 *
 * <h2>为什么没有 shouldGenerate* 覆写</h2>
 * <p>Bukkit 的 {@code shouldGenerateNoise/Surface/Caves/Decorations/Mobs/Structures}
 * 默认为 {@code false}，含义是“<b>不要</b>让服务端先跑原版生成再交给我们”。
 * 这正好是我们需要的纯虚空画布：只有本类 {@link #generateNoise} 画出的星球方块存在，
 * 没有原版地形、洞穴、树木、结构与自然生物。{@code shouldGenerateBedrock()} 与
 * {@code isParallelCapable()} 已被标记废弃（基岩属于 surface 阶段、并行安全已是硬性要求），
 * 因此不再覆写。</p>
 *
 * <h2>重叠</h2>
 * <p>多个星球重叠时按 {@link Planet#compareTo(Planet)} 的顺序依次绘制，后绘制的覆盖先绘制的，
 * 即 weight 大者胜出；weight 相同时小球覆盖大球。程序化星球的 weight 默认为 0，
 * 因此固定星球（weight &gt;= 1）会覆盖与之重叠的程序化星球。</p>
 */
public final class PlanetChunkGenerator extends ChunkGenerator {

    private volatile PlanetRegistry registry;
    private volatile Biome biome;
    /** 星球只在这个高度以上生成（= teleport-y），玩家到达高度之上才是星球区。 */
    private volatile int floorY = Integer.MIN_VALUE;

    /**
     * @param registry 星球快照，{@code null} 等价于空
     * @param biome    生物群系，可为 {@code null}（{@link CosmosBiomeProvider} 会回退到
     *                 {@code the_end}）。允许 null 是为了让单元测试能在没有服务端时构造生成器。
     */
    public PlanetChunkGenerator(PlanetRegistry registry, Biome biome) {
        this.registry = registry == null ? PlanetRegistry.empty() : registry;
        this.biome = biome;
    }

    /** 原子替换星球快照（reload 时从全局线程调用）。 */
    public void setRegistry(PlanetRegistry registry) {
        this.registry = registry == null ? PlanetRegistry.empty() : registry;
    }

    public PlanetRegistry registry() {
        return registry;
    }

    public void setBiome(Biome biome) {
        this.biome = biome;
    }

    /**
     * 设置星球生成的下限（含）：低于该高度的方块不会被画出来。
     * 由 {@code teleport-y} 决定，保证「星球只在传送高度以上」。
     */
    public void setFloorY(int floorY) {
        this.floorY = floorY;
    }

    public int floorY() {
        return floorY;
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        PlanetRegistry snapshot = this.registry;
        if (snapshot.isEmpty()) {
            return;
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int minHeight = Math.max(data.getMinHeight(), this.floorY);
        int maxHeight = data.getMaxHeight() - 1;
        if (minHeight > maxHeight) {
            return;
        }

        // 固定星球 + 与本区块相交的程序化星球（已按绘制顺序排好）
        for (Planet planet : snapshot.planetsNear(baseX, baseZ, minHeight, maxHeight)) {
            // 包围盒快速裁剪：星球完全不在这个区块内就跳过
            if (planet.maxBlockX() < baseX || planet.minBlockX() > baseX + 15
                    || planet.maxBlockZ() < baseZ || planet.minBlockZ() > baseZ + 15
                    || planet.maxBlockY() < minHeight || planet.minBlockY() > maxHeight) {
                continue;
            }
            paint(planet, data, baseX, baseZ, minHeight, maxHeight);
        }
    }

    private void paint(Planet planet, ChunkData data, int baseX, int baseZ, int minHeight, int maxHeight) {
        int localMinX = Math.max(0, planet.minBlockX() - baseX);
        int localMaxX = Math.min(15, planet.maxBlockX() - baseX);
        int localMinZ = Math.max(0, planet.minBlockZ() - baseZ);
        int localMaxZ = Math.min(15, planet.maxBlockZ() - baseZ);

        double centerY = planet.centerY();
        double outerRadiusSquared = planet.outerRadiusSquared();
        double coreRadiusSquared = planet.coreRadiusSquared();
        BlockData outerBlock = planet.outerBlock();
        BlockData coreBlock = planet.coreBlock();

        for (int localX = localMinX; localX <= localMaxX; localX++) {
            int worldX = baseX + localX;
            double dx = (worldX + 0.5D) - planet.centerX();
            double dxSquared = dx * dx;

            for (int localZ = localMinZ; localZ <= localMaxZ; localZ++) {
                int worldZ = baseZ + localZ;
                double dz = (worldZ + 0.5D) - planet.centerZ();
                double horizontalSquared = dxSquared + dz * dz;
                if (horizontalSquared > outerRadiusSquared) {
                    continue;
                }

                double halfHeight = Math.sqrt(outerRadiusSquared - horizontalSquared);
                int outerLow = (int) Math.ceil(centerY - halfHeight - 0.5D);
                int outerHigh = (int) Math.floor(centerY + halfHeight - 0.5D);
                if (outerLow > outerHigh) {
                    continue;
                }

                int coreLow = 0;
                int coreHigh = -1;
                if (coreRadiusSquared > 0.0D && horizontalSquared <= coreRadiusSquared) {
                    double coreHalfHeight = Math.sqrt(coreRadiusSquared - horizontalSquared);
                    coreLow = (int) Math.ceil(centerY - coreHalfHeight - 0.5D);
                    coreHigh = (int) Math.floor(centerY + coreHalfHeight - 0.5D);
                }

                if (coreHigh >= coreLow) {
                    // 外壳（下） | 核心 | 外壳（上）
                    fillColumn(data, localX, localZ, outerLow, coreLow - 1, outerBlock, minHeight, maxHeight);
                    fillColumn(data, localX, localZ, Math.max(coreLow, outerLow), Math.min(coreHigh, outerHigh),
                            coreBlock, minHeight, maxHeight);
                    fillColumn(data, localX, localZ, coreHigh + 1, outerHigh, outerBlock, minHeight, maxHeight);
                } else {
                    fillColumn(data, localX, localZ, outerLow, outerHigh, outerBlock, minHeight, maxHeight);
                }
            }
        }
    }

    /**
     * 在单个区块列上填充一段连续方块。
     *
     * <p>{@link ChunkData#setRegion(int, int, int, int, int, int, BlockData)} 的三个上界
     * 都是<b>开区间</b>：只写 {@code [min, max)}。因此单列填充必须写成
     * {@code (x, start, z) -> (x + 1, end + 1, z + 1)}，否则宽度为 0，什么都不会写。
     * 坐标已裁剪到 {@code [minHeight, maxHeight]}，不会写到别的区块。</p>
     */
    private void fillColumn(ChunkData data, int x, int z, int from, int to, BlockData block,
                            int minHeight, int maxHeight) {
        int start = Math.max(from, minHeight);
        int end = Math.min(to, maxHeight);
        if (start > end) {
            return;
        }
        if (start == end) {
            data.setBlock(x, start, z, block);
        } else {
            data.setRegion(x, start, z, x + 1, end + 1, z + 1, block);
        }
    }

    // ------------------------------------------------------------------
    // 其它钩子
    // ------------------------------------------------------------------

    /** 当世界只保存了生成器名称、没有保存 biome provider 时，由这里兜底。 */
    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new CosmosBiomeProvider(this.biome);
    }

    /** 不绑定固定出生点，出生点由 {@link CosmosWorldService} 在区域线程上设置。 */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return null;
    }
}
