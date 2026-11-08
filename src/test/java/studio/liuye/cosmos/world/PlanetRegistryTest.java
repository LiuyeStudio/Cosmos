package studio.liuye.cosmos.world;

import org.junit.Test;
import studio.liuye.cosmos.config.PlanetSettings;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static studio.liuye.cosmos.TestSupport.block;

/** {@link PlanetRegistry} 的过滤与排序测试。 */
public class PlanetRegistryTest {

    private static final Logger LOGGER = Logger.getLogger("CosmosTest");

    private static PlanetSettings settings(String name, boolean enabled, double radius, int weight) {
        return new PlanetSettings(name, enabled, 0.0D, 64.0D, 0.0D, radius, 1.0D, -1.0D,
                block("outer-" + name), block("core-" + name), weight);
    }

    @Test
    public void disabledPlanetsAreIgnored() {
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(
                settings("enabled", true, 10.0D, 1),
                settings("disabled", false, 10.0D, 2)
        ), LOGGER);

        assertEquals(1, registry.planets().size());
        assertEquals("enabled", registry.planets().get(0).name());
    }

    @Test
    public void invalidRadiusIsIgnored() {
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(
                settings("broken", true, 0.1D, 1)
        ), LOGGER);

        assertTrue(registry.isEmpty());
    }

    @Test
    public void emptySettingsProduceEmptyRegistry() {
        assertTrue(PlanetRegistry.ofManual(List.of(), LOGGER).isEmpty());
        assertTrue(PlanetRegistry.empty().isEmpty());
    }

    @Test
    public void planetsAreSortedForPainting() {
        PlanetRegistry registry = PlanetRegistry.ofManual(List.of(
                settings("high", true, 10.0D, 9),
                settings("low", true, 10.0D, 1)
        ), LOGGER);

        assertEquals("low", registry.planets().get(0).name());
        assertEquals("high", registry.planets().get(1).name());
    }
}
