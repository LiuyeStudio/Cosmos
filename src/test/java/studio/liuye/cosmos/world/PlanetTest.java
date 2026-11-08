package studio.liuye.cosmos.world;

import org.junit.Test;
import studio.liuye.cosmos.config.PlanetSettings;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static studio.liuye.cosmos.TestSupport.block;

/** 星球几何与绘制顺序的单元测试（不依赖服务端）。 */
public class PlanetTest {

    private static PlanetSettings settings(String name, double centerX, double centerY, double centerZ,
                                           double radius, double shellThickness, double coreRadius, int weight) {
        return new PlanetSettings(name, true, centerX, centerY, centerZ, radius, shellThickness, coreRadius,
                block("outer-" + name), block("core-" + name), weight);
    }

    @Test
    public void boundingBoxCoversWholeSphere() {
        Planet planet = new Planet(settings("home", 0.5D, 64.5D, 0.5D, 10.0D, 2.0D, -1.0D, 0));

        assertEquals(-10, planet.minBlockX());
        assertEquals(11, planet.maxBlockX());
        assertEquals(54, planet.minBlockY());
        assertEquals(75, planet.maxBlockY());
        assertEquals(-10, planet.minBlockZ());
        assertEquals(11, planet.maxBlockZ());
    }

    @Test
    public void containsUsesBlockCentersAndIsInclusiveAtTheSurface() {
        Planet planet = new Planet(settings("home", 0.5D, 64.5D, 0.5D, 10.0D, 2.0D, -1.0D, 0));

        assertTrue(planet.contains(0, 64, 0));
        // 方块中心 y=74.5 与球心 64.5 相距正好 10 → 球面上，包含
        assertTrue(planet.contains(0, 74, 0));
        // y=75 → 距离 11
        assertFalse(planet.contains(0, 75, 0));
        // 对角：sqrt(7^2 + 7^2) ≈ 9.899 < 10
        assertTrue(planet.contains(7, 64, 7));
        // 对角：sqrt(8^2 + 8^2) ≈ 11.31 > 10
        assertFalse(planet.contains(8, 64, 8));
    }

    @Test
    public void coreRadiusDefaultsToRadiusMinusShellThickness() {
        Planet auto = new Planet(settings("auto", 0.0D, 64.0D, 0.0D, 10.0D, 3.0D, -1.0D, 0));
        assertEquals(7.0D, auto.coreRadius(), 1.0E-9D);

        Planet explicit = new Planet(settings("explicit", 0.0D, 64.0D, 0.0D, 10.0D, 3.0D, 5.0D, 0));
        assertEquals(5.0D, explicit.coreRadius(), 1.0E-9D);

        // 外壳厚度大于半径时核心半径被夹到 0（整颗球都是外壳）
        Planet shellOnly = new Planet(settings("shell", 0.0D, 64.0D, 0.0D, 10.0D, 99.0D, -1.0D, 0));
        assertEquals(0.0D, shellOnly.coreRadius(), 1.0E-9D);
    }

    @Test
    public void paintOrderIsWeightThenRadiusThenName() {
        Planet light = new Planet(settings("light", 0.0D, 0.0D, 0.0D, 10.0D, 0.0D, -1.0D, 1));
        Planet heavyLarge = new Planet(settings("heavy-large", 0.0D, 0.0D, 0.0D, 20.0D, 0.0D, -1.0D, 5));
        Planet heavySmall = new Planet(settings("heavy-small", 0.0D, 0.0D, 0.0D, 5.0D, 0.0D, -1.0D, 5));

        List<Planet> planets = new ArrayList<>(List.of(heavyLarge, heavySmall, light));
        planets.sort(Planet::compareTo);

        // 权重小的先画；权重相同时大球先画、小球后画（后画的覆盖先画的）
        assertEquals("light", planets.get(0).name());
        assertEquals("heavy-large", planets.get(1).name());
        assertEquals("heavy-small", planets.get(2).name());
    }
}
