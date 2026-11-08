package studio.liuye.cosmos.world;

import org.junit.Test;
import studio.liuye.cosmos.config.PlanetSettings;
import studio.liuye.cosmos.config.ProceduralPlanetSettings;
import studio.liuye.cosmos.config.ProceduralPlanetSettings.PaletteEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static studio.liuye.cosmos.TestSupport.block;

/** 程序化星球场：确定性、密度、Y 范围与区块裁剪的单元测试（不依赖服务端）。 */
public class ProceduralPlanetFieldTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    private static ProceduralPlanetSettings settings(double chance, double minRadius, double maxRadius,
                                                     int minY, int maxY) {
        return new ProceduralPlanetSettings(true, 64, chance, minRadius, maxRadius, 2.0D, 4.0D,
                minY, maxY, 0, 42L, false, 42.0D, 60,
                List.of(new PaletteEntry(block("outer"), block("core"))));
    }

    private static ProceduralPlanetSettings spawnSettings(double chance, double spawnRadius, int spawnCenterY) {
        return new ProceduralPlanetSettings(true, 64, chance, 6.0D, 20.0D, 2.0D, 4.0D,
                0, 255, 0, 42L, true, spawnRadius, spawnCenterY,
                List.of(new PaletteEntry(block("outer"), block("core"))));
    }

    private static boolean intersects(Planet planet, int baseX, int baseZ, int minY, int maxY) {
        return planet.maxBlockX() >= baseX && planet.minBlockX() <= baseX + 15
                && planet.maxBlockZ() >= baseZ && planet.minBlockZ() <= baseZ + 15
                && planet.maxBlockY() >= minY && planet.minBlockY() <= maxY;
    }

    @Test
    public void disabledFieldIsInert() {
        ProceduralPlanetField field = ProceduralPlanetField.disabled();

        assertFalse(field.enabled());
        assertTrue(field.planetsNear(0, 0, 0, 255).isEmpty());
        assertNull(field.planetFor(0, 0, 0, 255));
    }

    @Test
    public void sameSeedAlwaysProducesTheSamePlanets() {
        ProceduralPlanetField first = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 0, 255));
        ProceduralPlanetField second = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 0, 255), 42L);

        for (int cellX = -3; cellX <= 3; cellX++) {
            for (int cellZ = -3; cellZ <= 3; cellZ++) {
                Planet a = first.planetFor(cellX, cellZ, 0, 255);
                Planet b = second.planetFor(cellX, cellZ, 0, 255);
                assertNotNull(a);
                assertEquals(a.name(), b.name());
                assertEquals(a.centerX(), b.centerX(), 1.0E-9D);
                assertEquals(a.centerY(), b.centerY(), 1.0E-9D);
                assertEquals(a.centerZ(), b.centerZ(), 1.0E-9D);
                assertEquals(a.outerRadius(), b.outerRadius(), 1.0E-9D);
                assertEquals(a.coreRadius(), b.coreRadius(), 1.0E-9D);
            }
        }
    }

    @Test
    public void differentSeedsProduceDifferentFields() {
        ProceduralPlanetField first = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 0, 255), 1L);
        ProceduralPlanetField second = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 0, 255), 2L);

        int differences = 0;
        for (int cellX = -5; cellX <= 5; cellX++) {
            for (int cellZ = -5; cellZ <= 5; cellZ++) {
                Planet a = first.planetFor(cellX, cellZ, 0, 255);
                Planet b = second.planetFor(cellX, cellZ, 0, 255);
                if (a == null || b == null
                        || Math.abs(a.centerX() - b.centerX()) > 1.0E-6D
                        || Math.abs(a.centerZ() - b.centerZ()) > 1.0E-6D
                        || Math.abs(a.outerRadius() - b.outerRadius()) > 1.0E-6D) {
                    differences++;
                }
            }
        }
        assertTrue("换种子后星球分布应当改变", differences > 0);
    }

    @Test
    public void chanceControlsDensity() {
        ProceduralPlanetField empty = new ProceduralPlanetField(settings(0.0D, 6.0D, 20.0D, 0, 255));
        ProceduralPlanetField full = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 0, 255));

        for (int cellX = -4; cellX <= 4; cellX++) {
            for (int cellZ = -4; cellZ <= 4; cellZ++) {
                assertNull(empty.planetFor(cellX, cellZ, 0, 255));
                assertNotNull(full.planetFor(cellX, cellZ, 0, 255));
            }
        }
    }

    @Test
    public void centerYKeepsTheWholeSphereInsideTheAllowedRange() {
        ProceduralPlanetField field = new ProceduralPlanetField(settings(1.0D, 6.0D, 20.0D, 40, 200));

        for (int cellX = -4; cellX <= 4; cellX++) {
            for (int cellZ = -4; cellZ <= 4; cellZ++) {
                Planet planet = field.planetFor(cellX, cellZ, 0, 255);
                assertNotNull(planet);
                assertTrue(planet.centerY() - planet.outerRadius() >= 39.999D);
                assertTrue(planet.centerY() + planet.outerRadius() <= 200.001D);
            }
        }
    }

    @Test
    public void planetsNearMatchesBruteForceOverTheCellGrid() {
        ProceduralPlanetSettings settings = settings(0.6D, 4.0D, 20.0D, 0, 255);
        ProceduralPlanetField field = new ProceduralPlanetField(settings);
        int cellSize = settings.cellSize();
        int reach = (int) Math.ceil(settings.maxRadius()) + cellSize;

        for (int chunkX = -2; chunkX <= 2; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                int baseX = chunkX * 16;
                int baseZ = chunkZ * 16;

                Set<String> expected = new HashSet<>();
                for (int cellX = Math.floorDiv(baseX - reach, cellSize);
                     cellX <= Math.floorDiv(baseX + reach, cellSize); cellX++) {
                    for (int cellZ = Math.floorDiv(baseZ - reach, cellSize);
                         cellZ <= Math.floorDiv(baseZ + reach, cellSize); cellZ++) {
                        Planet planet = field.planetFor(cellX, cellZ, 0, 255);
                        if (planet != null && intersects(planet, baseX, baseZ, 0, 255)) {
                            expected.add(planet.name());
                        }
                    }
                }

                Set<String> actual = new HashSet<>();
                for (Planet planet : field.planetsNear(baseX, baseZ, 0, 255)) {
                    actual.add(planet.name());
                }
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    public void paletteIsAppliedToOuterAndCore() {
        Planet planet = new ProceduralPlanetField(settings(1.0D, 10.0D, 10.0D, 0, 255))
                .planetFor(0, 0, 0, 255);

        assertNotNull(planet);
        assertEquals("outer", planet.outerBlock().toString());
        assertEquals("core", planet.coreBlock().toString());
        assertTrue(planet.coreRadius() > 0.0D);
        assertTrue(planet.coreRadius() < planet.outerRadius());
    }

    @Test
    public void spawnPlanetIsAlwaysGeneratedAtTheOrigin() {
        ProceduralPlanetField field = new ProceduralPlanetField(spawnSettings(0.0D, 42.0D, 60));

        assertTrue(field.enabled());
        Planet spawn = field.planetFor(0, 0, 0, 255);
        assertNotNull("chance 为 0 时原点地标仍然存在", spawn);
        assertEquals("spawn", spawn.name());
        assertEquals(0.0D, spawn.centerX(), 1.0E-9D);
        assertEquals(60.0D, spawn.centerY(), 1.0E-9D);
        assertEquals(0.0D, spawn.centerZ(), 1.0E-9D);
        assertEquals(42.0D, spawn.outerRadius(), 1.0E-9D);
        assertTrue("地标星球要能被原点附近的区块枚举到",
                field.planetsNear(32, 0, 0, 255).stream()
                        .anyMatch(planet -> planet.name().equals("spawn")));
    }

    @Test
    public void spawnPlanetUsesTheConfiguredSizeAndHeight() {
        Planet spawn = new ProceduralPlanetField(spawnSettings(1.0D, 12.0D, 120))
                .planetFor(0, 0, 0, 255);

        assertNotNull(spawn);
        assertEquals(12.0D, spawn.outerRadius(), 1.0E-9D);
        assertEquals(120.0D, spawn.centerY(), 1.0E-9D);
    }

    @Test
    public void spawnPlanetCenterYIsClampedIntoTheWorldRange() {
        // 配置的 400 超出世界顶层 255：球心必须被夹到 255 - 42 = 213，整颗球才留在世界里
        Planet spawn = new ProceduralPlanetField(spawnSettings(0.0D, 42.0D, 400))
                .planetFor(0, 0, 0, 255);

        assertNotNull(spawn);
        assertEquals(213.0D, spawn.centerY(), 1.0E-9D);
        assertTrue(spawn.minBlockY() >= 0);
        assertTrue(spawn.maxBlockY() <= 255);
    }

    @Test
    public void spawnPlanetCanBeDisabled() {
        ProceduralPlanetField field = new ProceduralPlanetField(settings(0.0D, 6.0D, 20.0D, 0, 255));

        assertFalse(field.enabled());
        assertNull(field.planetFor(0, 0, 0, 255));
        assertTrue(field.planetsNear(0, 0, 0, 255).isEmpty());
    }

    @Test
    public void registryMergesExplicitAndProceduralPlanetsInPaintOrder() {
        PlanetSettings explicit = new PlanetSettings("explicit", true, 8.0D, 100.0D, 8.0D, 10.0D, 2.0D, -1.0D,
                block("explicit-outer"), block("explicit-core"), 5);
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(explicit),
                settings(1.0D, 4.0D, 10.0D, 0, 255), LOGGER);

        assertFalse(registry.isEmpty());
        assertTrue(registry.hasProcedural());
        assertEquals(1, registry.planets().size());

        Planet explicitPlanet = registry.planets().get(0);
        assertTrue("显式星球总会被送进候选列表",
                registry.planetsNear(0, 0, 0, 255).contains(explicitPlanet));

        // 找一个能看到程序化星球的区块，验证合并后仍严格按绘制顺序排序
        List<Planet> merged = null;
        for (int chunkX = 0; chunkX < 8 && merged == null; chunkX++) {
            for (int chunkZ = 0; chunkZ < 8 && merged == null; chunkZ++) {
                List<Planet> candidate = registry.planetsNear(chunkX * 16, chunkZ * 16, 0, 255);
                if (candidate.stream().anyMatch(planet -> planet.name().startsWith("proc-"))) {
                    merged = candidate;
                }
            }
        }
        assertNotNull("附近应当生成程序化星球", merged);

        List<Planet> sorted = new ArrayList<>(merged);
        sorted.sort(Planet::compareTo);
        assertEquals(sorted, merged);
        // 程序化星球 weight 0，排在 weight 5 的固定星球前面（先画、后被覆盖）
        assertTrue(merged.get(0).name().startsWith("proc-"));
    }
}
