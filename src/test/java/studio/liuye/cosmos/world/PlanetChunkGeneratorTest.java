package studio.liuye.cosmos.world;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.material.MaterialData;
import org.junit.Test;
import studio.liuye.cosmos.config.PlanetSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static studio.liuye.cosmos.TestSupport.block;

/**
 * {@link PlanetChunkGenerator} 的生成结果测试。
 *
 * <p>用假的 {@link ChunkData} 记录每一次写入，验证：</p>
 * <ul>
 *   <li>只写自己区块的坐标（不越界到相邻区块）；</li>
 *   <li>{@code setRegion} 的上界是开区间（历史 bug：会丢掉每一列最上面的一个方块）；</li>
 *   <li>核心 / 外壳分层高度正确；</li>
 *   <li>重叠星球按 weight 覆盖；</li>
 *   <li>生成是确定性的。</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // 测试替身必须实现 ChunkData 的废弃重载
public class PlanetChunkGeneratorTest {

    private static final Logger LOGGER = Logger.getLogger("CosmosTest");
    private static final int MIN_HEIGHT = -64;
    private static final int MAX_HEIGHT = 320;

    // ------------------------------------------------------------------
    // 测试样例
    // ------------------------------------------------------------------

    @Test
    public void paintsCoreAndShellAtExpectedHeights() {
        PlanetRegistry registry = registry(planet("home", 8.5D, 64.5D, 8.5D, 4.0D, 1.0D, 0));
        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);

        generator(registry).generateNoise(null, new Random(1L), 0, 0, data);

        assertFalse("生成器写到了自己区块之外", data.outOfBounds);
        assertTrue(data.writes > 0);

        // 球心列 (8,8)：y=60 外壳，61..67 核心，68 外壳
        assertEquals("home-outer", data.labelAt(8, 60, 8));
        assertEquals("home-core", data.labelAt(8, 61, 8));
        assertEquals("home-core", data.labelAt(8, 64, 8));
        assertEquals("home-core", data.labelAt(8, 67, 8));
        assertEquals("home-outer", data.labelAt(8, 68, 8));
        assertNull("球体顶部之外不应有方块", data.labelAt(8, 69, 8));
        assertNull("球体底部之外不应有方块", data.labelAt(8, 59, 8));
    }

    @Test
    public void clipsWritesToTheOwningChunk() {
        // 球心 x=15.5，半径 4 → 覆盖 x=11..19，横跨 chunk 0 与 chunk 1
        PlanetRegistry registry = registry(planet("edge", 15.5D, 64.5D, 8.5D, 4.0D, 4.0D, 0));

        FakeChunkData chunk0 = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator(registry).generateNoise(null, new Random(1L), 0, 0, chunk0);

        FakeChunkData chunk1 = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator(registry).generateNoise(null, new Random(1L), 1, 0, chunk1);

        assertFalse("chunk0 越界写入", chunk0.outOfBounds);
        assertFalse("chunk1 越界写入", chunk1.outOfBounds);

        // chunk0 最大只能写到 x=15；chunk1 的局部 x=0 对应世界 x=16
        assertNotNull(chunk0.labelAt(15, 64, 8));
        assertNotNull(chunk1.labelAt(0, 64, 8));
        assertNotNull(chunk1.labelAt(3, 64, 8));   // 世界 x=19，球体边缘
        assertNull(chunk1.labelAt(4, 64, 8));      // 世界 x=20，球外

        // 两个区块合起来应覆盖球体在该行的全部方块：世界 x=11..19
        assertEquals(5, countInRow(chunk0, 64, 8));
        assertEquals(4, countInRow(chunk1, 64, 8));
    }

    @Test
    public void higherWeightOverwritesLowerWeight() {
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(
                planet("low", 8.5D, 64.5D, 8.5D, 4.0D, 4.0D, 1),
                planet("high", 8.5D, 64.5D, 8.5D, 4.0D, 4.0D, 9)
        ), LOGGER);

        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator(registry).generateNoise(null, new Random(1L), 0, 0, data);

        assertEquals("high-outer", data.labelAt(8, 64, 8));
    }

    @Test
    public void generationIsDeterministic() {
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(
                planet("home", 8.5D, 64.5D, 8.5D, 6.0D, 2.0D, 3),
                planet("moon", 20.5D, 70.5D, 2.5D, 3.0D, 1.0D, 1)
        ), LOGGER);

        FakeChunkData first = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        FakeChunkData second = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator(registry).generateNoise(null, new Random(42L), 0, 0, first);
        generator(registry).generateNoise(null, new Random(42L), 0, 0, second);

        assertEquals(first.labels, second.labels);
    }

    @Test
    public void emptyRegistryLeavesChunkEmpty() {
        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator(PlanetRegistry.empty()).generateNoise(null, new Random(1L), 0, 0, data);

        assertEquals(0, data.writes);
        assertTrue(data.labels.isEmpty());
    }

    @Test
    public void planetsOutsideTheChunkAreIgnored() {
        PlanetRegistry registry = registry(planet("far", 1000.5D, 64.5D, 1000.5D, 5.0D, 1.0D, 0));
        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);

        generator(registry).generateNoise(null, new Random(1L), 0, 0, data);

        assertEquals(0, data.writes);
    }

    /** 裁剪到世界高度：星球远低于世界底部时不能写入任何方块。 */
    @Test
    public void planetsBelowTheWorldAreIgnored() {
        PlanetRegistry registry = registry(planet("below", 8.5D, -500.5D, 8.5D, 5.0D, 1.0D, 0));
        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);

        generator(registry).generateNoise(null, new Random(1L), 0, 0, data);

        assertEquals(0, data.writes);
    }

    /** 星球只生成在 floorY（= teleport-y）以上：低于它的方块一律不画。 */
    @Test
    public void blocksBelowTheFloorAreNotGenerated() {
        PlanetRegistry registry = registry(planet("home", 8.5D, 64.5D, 8.5D, 8.0D, 1.0D, 0));
        PlanetChunkGenerator generator = new PlanetChunkGenerator(registry, null);
        generator.setFloorY(66);

        FakeChunkData data = new FakeChunkData(MIN_HEIGHT, MAX_HEIGHT);
        generator.generateNoise(null, new Random(1L), 0, 0, data);

        assertFalse("生成器写到了自己区块之外", data.outOfBounds);
        assertNull("floorY 以下的方块不应生成", data.labelAt(8, 65, 8));
        assertNotNull("floorY 及以上的方块应正常生成", data.labelAt(8, 66, 8));
        assertNotNull(data.labelAt(8, 72, 8));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static int countInRow(FakeChunkData data, int y, int z) {
        int count = 0;
        for (int x = 0; x <= 15; x++) {
            if (data.labelAt(x, y, z) != null) {
                count++;
            }
        }
        return count;
    }

    private static PlanetChunkGenerator generator(PlanetRegistry registry) {
        // biome 传 null：单元测试没有服务端注册表
        return new PlanetChunkGenerator(registry, null);
    }

    private static PlanetRegistry registry(PlanetSettings settings) {
        return PlanetRegistry.ofManual(List.of(settings), LOGGER);
    }

    private static PlanetSettings planet(String name, double centerX, double centerY, double centerZ,
                                         double radius, double shellThickness, int weight) {
        return new PlanetSettings(name, true, centerX, centerY, centerZ, radius, shellThickness, -1.0D,
                block(name + "-outer"), block(name + "-core"), weight);
    }

    /**
     * 记录写入的假 ChunkData。{@code setRegion} 按 Bukkit 语义实现为
     * {@code [min, max)} 开区间，因此可以验证生成器的边界计算。
     */
    private static final class FakeChunkData implements ChunkGenerator.ChunkData {

        private final int minHeight;
        private final int maxHeight;
        private final Map<Long, String> labels = new LinkedHashMap<>();

        private boolean outOfBounds;
        private int writes;

        private FakeChunkData(int minHeight, int maxHeight) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        @Override
        public int getMinHeight() {
            return minHeight;
        }

        @Override
        public int getMaxHeight() {
            return maxHeight;
        }

        @Override
        public Biome getBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public void setBlock(int x, int y, int z, Material material) {
            record(x, y, z, material == null ? "null" : material.name());
        }

        @Override
        public void setBlock(int x, int y, int z, MaterialData material) {
            // 生成器不使用 MaterialData 重载
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData blockData) {
            record(x, y, z, blockData == null ? "null" : blockData.getAsString());
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, Material material) {
            // 生成器不使用 Material 重载
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, MaterialData material) {
            // 生成器不使用 MaterialData 重载
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, BlockData blockData) {
            String label = blockData == null ? "null" : blockData.getAsString();
            for (int x = xMin; x < xMax; x++) {
                for (int y = yMin; y < yMax; y++) {
                    for (int z = zMin; z < zMax; z++) {
                        record(x, y, z, label);
                    }
                }
            }
        }

        @Override
        public Material getType(int x, int y, int z) {
            return labels.containsKey(key(x, y, z)) ? Material.STONE : Material.AIR;
        }

        @Override
        public MaterialData getTypeAndData(int x, int y, int z) {
            return null;
        }

        @Override
        public BlockData getBlockData(int x, int y, int z) {
            String label = labels.get(key(x, y, z));
            return label == null ? null : block(label);
        }

        @Override
        public byte getData(int x, int y, int z) {
            return 0;
        }

        @Override
        public int getHeight(HeightMap heightMap, int x, int z) {
            return minHeight;
        }

        private void record(int x, int y, int z, String label) {
            if (x < 0 || x > 15 || z < 0 || z > 15 || y < minHeight || y >= maxHeight) {
                outOfBounds = true;
                return;
            }
            writes++;
            labels.put(key(x, y, z), label);
        }

        private String labelAt(int x, int y, int z) {
            return labels.get(key(x, y, z));
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (z & 0xFFFFF) << 20) | (y & 0xFFFFFL);
        }
    }
}
