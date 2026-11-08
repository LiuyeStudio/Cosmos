package studio.liuye.cosmos.world;

import org.bukkit.block.data.BlockData;
import studio.liuye.cosmos.config.PlanetSettings;

/**
 * 运行时星球（不可变、线程安全）。
 *
 * <p>只用纯数学判断某个方块中心是否落在球体/核心内，因此：</p>
 * <ul>
 *   <li>确定性：同一份配置 + 同一坐标永远得到同一结果，区块重复加载不会漂移；</li>
 *   <li>无状态：可以在多个区块生成线程上并发调用；</li>
 *   <li>不触碰 Bukkit 世界对象，生成器只写当前 {@code WorldGenRegion} 的 ChunkData。</li>
 * </ul>
 */
public final class Planet implements Comparable<Planet> {

    private final String name;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double outerRadius;
    private final double coreRadius;
    private final double outerRadiusSquared;
    private final double coreRadiusSquared;
    private final BlockData outerBlock;
    private final BlockData coreBlock;
    private final int weight;

    // 方块坐标包围盒（闭区间），仅用于快速裁剪，不做精确判定。
    private final int minBlockX;
    private final int maxBlockX;
    private final int minBlockY;
    private final int maxBlockY;
    private final int minBlockZ;
    private final int maxBlockZ;

    Planet(PlanetSettings settings) {
        this.name = settings.name();
        this.centerX = settings.centerX();
        this.centerY = settings.centerY();
        this.centerZ = settings.centerZ();
        this.outerRadius = Math.max(0.5D, settings.radius());
        this.coreRadius = settings.resolvedCoreRadius();
        this.outerRadiusSquared = this.outerRadius * this.outerRadius;
        this.coreRadiusSquared = this.coreRadius * this.coreRadius;
        this.outerBlock = settings.outerBlock();
        this.coreBlock = settings.coreBlock();
        this.weight = settings.weight();

        this.minBlockX = (int) Math.floor(centerX - this.outerRadius);
        this.maxBlockX = (int) Math.ceil(centerX + this.outerRadius);
        this.minBlockY = (int) Math.floor(centerY - this.outerRadius);
        this.maxBlockY = (int) Math.ceil(centerY + this.outerRadius);
        this.minBlockZ = (int) Math.floor(centerZ - this.outerRadius);
        this.maxBlockZ = (int) Math.ceil(centerZ + this.outerRadius);
    }

    /** 方块中心是否位于球体内（用于调试 / 外部查询）。 */
    public boolean contains(int blockX, int blockY, int blockZ) {
        double dx = (blockX + 0.5D) - centerX;
        double dy = (blockY + 0.5D) - centerY;
        double dz = (blockZ + 0.5D) - centerZ;
        return dx * dx + dy * dy + dz * dz <= outerRadiusSquared;
    }

    /** 该方块应使用的材质：核心以内为 core-block，否则为 outer-block。 */
    public BlockData blockAt(int blockX, int blockY, int blockZ) {
        double dx = (blockX + 0.5D) - centerX;
        double dy = (blockY + 0.5D) - centerY;
        double dz = (blockZ + 0.5D) - centerZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (coreRadiusSquared > 0.0D && distanceSquared <= coreRadiusSquared) {
            return coreBlock;
        }
        return outerBlock;
    }

    /**
     * 某一列「站上去的 Y」：球面最高方块的上一格；该列不在球体水平范围内时返回 {@link Double#NaN}。
     *
     * <p>计算方式与 {@code PlanetChunkGenerator#paint} 完全一致（{@code floor(cy + h - 0.5)} 是最高方块），
     * 因此传送落点与真实生成结果不会错位。</p>
     */
    public double standYAt(int blockX, int blockZ) {
        double dx = (blockX + 0.5D) - centerX;
        double dz = (blockZ + 0.5D) - centerZ;
        double horizontal = dx * dx + dz * dz;
        if (horizontal > outerRadiusSquared) {
            return Double.NaN;
        }
        double halfHeight = Math.sqrt(outerRadiusSquared - horizontal);
        return Math.floor(centerY + halfHeight - 0.5D) + 1.0D;
    }

    public String name() {
        return name;
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    public double centerZ() {
        return centerZ;
    }

    public double outerRadius() {
        return outerRadius;
    }

    public double outerRadiusSquared() {
        return outerRadiusSquared;
    }

    public double coreRadius() {
        return coreRadius;
    }

    public double coreRadiusSquared() {
        return coreRadiusSquared;
    }

    public BlockData outerBlock() {
        return outerBlock;
    }

    public BlockData coreBlock() {
        return coreBlock;
    }

    public int weight() {
        return weight;
    }

    public int minBlockX() {
        return minBlockX;
    }

    public int maxBlockX() {
        return maxBlockX;
    }

    public int minBlockY() {
        return minBlockY;
    }

    public int maxBlockY() {
        return maxBlockY;
    }

    public int minBlockZ() {
        return minBlockZ;
    }

    public int maxBlockZ() {
        return maxBlockZ;
    }

    /**
     * 绘制顺序：权重从小到大（后画的覆盖先画的），权重相同时大球先画、小球后画，
     * 最后用名字保证完全确定性。
     */
    @Override
    public int compareTo(Planet other) {
        int byWeight = Integer.compare(this.weight, other.weight);
        if (byWeight != 0) {
            return byWeight;
        }
        int byRadius = Double.compare(other.outerRadius, this.outerRadius);
        if (byRadius != 0) {
            return byRadius;
        }
        return this.name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return "Planet{" + name + ", radius=" + outerRadius + ", core=" + coreRadius
                + ", center=(" + centerX + ", " + centerY + ", " + centerZ + "), weight=" + weight + '}';
    }
}
