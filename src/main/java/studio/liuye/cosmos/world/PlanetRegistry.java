package studio.liuye.cosmos.world;

import studio.liuye.cosmos.config.PlanetSettings;
import studio.liuye.cosmos.config.ProceduralPlanetSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * 星球来源（不可变快照）。
 *
 * <p>Cosmos 里的星球<b>全部</b>由 {@link ProceduralPlanetField} 按「网格 + 坐标哈希」即时生成：
 * 没有手写星球列表，不需要逐颗配置，数量随视野无限延伸，同一份种子永远得到同一片星空。
 * 每个 chunk 只需要问一次「哪些星球和我相交」，生成器据此绘制。</p>
 *
 * <p>{@code /cosmos reload} 会构造一个新的 registry 并通过
 * {@link PlanetChunkGenerator#setRegistry(PlanetRegistry)} 原子替换旧快照；
 * 已生成区块不受影响，新加载区块使用新配置。</p>
 *
 * <p>{@link #ofManual(List, Logger)} 只给单元测试用：直接喂一组星球来验证绘制与排序，
 * 生产路径不会走到它。</p>
 */
public final class PlanetRegistry {

    private final List<Planet> manual;
    private final ProceduralPlanetField procedural;

    private PlanetRegistry(List<Planet> manual, ProceduralPlanetField procedural) {
        this.manual = List.copyOf(manual);
        this.procedural = procedural == null ? ProceduralPlanetField.disabled() : procedural;
    }

    public static PlanetRegistry empty() {
        return new PlanetRegistry(List.of(), ProceduralPlanetField.disabled());
    }

    /**
     * 由配置构建快照：星球全部来自程序化星球场。
     */
    public static PlanetRegistry of(ProceduralPlanetSettings settings, Logger logger) {
        if (settings == null || !settings.active()) {
            if (logger != null) {
                logger.warning("没有可用的程序化星球配置：Cosmos 世界将是纯虚空。"
                        + "请检查 planets.yml 的 procedural 段是否 enabled: true 且 palette 非空。");
            }
        }
        return new PlanetRegistry(List.of(), new ProceduralPlanetField(settings));
    }

    /**
     * 仅供测试：直接给一组星球，绕过配置解析与坐标哈希。
     */
    static PlanetRegistry ofManual(List<PlanetSettings> settings, Logger logger) {
        return ofManual(settings, ProceduralPlanetSettings.disabled(), logger);
    }

    /**
     * 仅供测试：一组显式星球 + 一个程序化星球场，用来验证两者的合并顺序。
     */
    static PlanetRegistry ofManual(List<PlanetSettings> settings,
                                   ProceduralPlanetSettings proceduralSettings,
                                   Logger logger) {
        List<Planet> planets = new ArrayList<>(settings.size());
        for (PlanetSettings planet : settings) {
            if (!planet.enabled()) {
                continue;
            }
            if (planet.radius() < 0.5D) {
                logger.warning("星球 '" + planet.name() + "' 的半径无效，已跳过。");
                continue;
            }
            planets.add(new Planet(planet));
        }
        planets.sort(Comparator.naturalOrder());
        return new PlanetRegistry(planets, new ProceduralPlanetField(proceduralSettings));
    }

    /** 显式给定的星球（仅测试用，生产路径恒为空表）。 */
    List<Planet> planets() {
        return manual;
    }

    /**
     * 某个 chunk 需要绘制的全部星球，统一按 {@link Planet#compareTo(Planet)} 排序，
     * 保证重叠时的覆盖关系稳定。
     */
    public List<Planet> planetsNear(int baseX, int baseZ, int minY, int maxY) {
        List<Planet> near = procedural.planetsNear(baseX, baseZ, minY, maxY);
        if (manual.isEmpty()) {
            // 生产路径必须也排序：同一区块内多颗球重叠时，绘制顺序决定谁盖住谁。
            near.sort(Comparator.naturalOrder());
            return near;
        }
        List<Planet> merged = new ArrayList<>(manual.size() + near.size());
        merged.addAll(manual);
        merged.addAll(near);
        merged.sort(Comparator.naturalOrder());
        return merged;
    }

    /** 是否启用了程序化星球。 */
    public boolean hasProcedural() {
        return procedural.enabled();
    }

    public ProceduralPlanetField procedural() {
        return procedural;
    }

    public boolean isEmpty() {
        return manual.isEmpty() && !procedural.enabled();
    }
}
