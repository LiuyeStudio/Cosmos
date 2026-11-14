package studio.liuye.cosmos.config;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.List;
import java.util.Map;

/**
 * 解析并校验后的完整配置快照。
 *
 * <p>不可变对象：{@code /cosmos reload} 时整体替换（{@code AtomicReference} 交换），
 * 因此其它线程读到的永远是一份自洽的配置，不需要加锁。</p>
 */
public final class CosmosSettings {

    /** 兜底落点：世界名 + 坐标。世界可能尚未加载，所以延迟到使用时解析。 */
    public record Fallback(String worldName, double x, double y, double z, float yaw, float pitch) {

        /** @return 解析后的 Location；世界不存在时返回 {@code null}。 */
        public Location resolve(Server server) {
            if (worldName == null || worldName.isBlank()) {
                return null;
            }
            World world = server.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private final String mainWorldName;
    private final String cosmosWorldName;
    private final World.Environment cosmosEnvironment;
    private final Biome cosmosBiome;
    private final boolean cosmosWorldBorderSync;
    private final int scale;
    private final int teleportYWorld;
    private final int teleportYCosmos;
    private final int arrivalYOffset;
    private final int planetFloorY;
    private final long teleportCooldownMs;
    private final int portalCooldownTicks;
    private final int yCheckIntervalTicks;
    private final int worldCheckIntervalTicks;
    private final int cosmosViewDistance;
    private final int cosmosSendViewDistance;
    private final int teleportPreloadRadius;
    private final boolean itemFloatEnabled;
    private final boolean floatExperienceOrb;
    private final boolean floatFreezeVelocity;
    private final boolean floatUnlimitedLifetime;
    private final boolean floatGravityBlocks;
    private final int floatSweepIntervalTicks;
    private final double floatSweepRadius;
    private final boolean playerFloatEnabled;
    private final boolean playerFloatSkipElytra;
    private final double playerFloatFallSpeed;
    private final int playerFloatIntervalTicks;
    private final ProceduralPlanetSettings proceduralPlanets;
    private final Fallback fallback;
    private final long worldSeed;
    private final boolean worldAutoCreate;
    private final Map<String, String> gamerules;
    private final boolean debug;

    private CosmosSettings(Builder builder) {
        this.mainWorldName = builder.mainWorldName;
        this.cosmosWorldName = builder.cosmosWorldName;
        this.cosmosEnvironment = builder.cosmosEnvironment;
        this.cosmosBiome = builder.cosmosBiome;
        this.cosmosWorldBorderSync = builder.cosmosWorldBorderSync;
        this.scale = builder.scale;
        this.teleportYWorld = builder.teleportYWorld;
        this.teleportYCosmos = builder.teleportYCosmos;
        this.arrivalYOffset = builder.arrivalYOffset;
        this.planetFloorY = builder.planetFloorY;
        this.teleportCooldownMs = builder.teleportCooldownMs;
        this.portalCooldownTicks = builder.portalCooldownTicks;
        this.yCheckIntervalTicks = builder.yCheckIntervalTicks;
        this.worldCheckIntervalTicks = builder.worldCheckIntervalTicks;
        this.cosmosViewDistance = builder.cosmosViewDistance;
        this.cosmosSendViewDistance = builder.cosmosSendViewDistance;
        this.teleportPreloadRadius = builder.teleportPreloadRadius;
        this.itemFloatEnabled = builder.itemFloatEnabled;
        this.floatExperienceOrb = builder.floatExperienceOrb;
        this.floatFreezeVelocity = builder.floatFreezeVelocity;
        this.floatUnlimitedLifetime = builder.floatUnlimitedLifetime;
        this.floatGravityBlocks = builder.floatGravityBlocks;
        this.floatSweepIntervalTicks = builder.floatSweepIntervalTicks;
        this.floatSweepRadius = builder.floatSweepRadius;
        this.playerFloatEnabled = builder.playerFloatEnabled;
        this.playerFloatSkipElytra = builder.playerFloatSkipElytra;
        this.playerFloatFallSpeed = builder.playerFloatFallSpeed;
        this.playerFloatIntervalTicks = builder.playerFloatIntervalTicks;
        this.proceduralPlanets = builder.proceduralPlanets;
        this.fallback = builder.fallback;
        this.worldSeed = builder.worldSeed;
        this.worldAutoCreate = builder.worldAutoCreate;
        this.gamerules = Map.copyOf(builder.gamerules);
        this.debug = builder.debug;
    }

    // ------------------------------------------------------------------
    // 世界判定
    // ------------------------------------------------------------------

    public String mainWorldName() {
        return mainWorldName;
    }

    public String cosmosWorldName() {
        return cosmosWorldName;
    }

    /** 世界名比较即可：Bukkit 世界名唯一且大小写敏感。 */
    public boolean isMainWorld(World world) {
        return world != null && world.getName().equals(mainWorldName);
    }

    public boolean isCosmosWorld(World world) {
        return world != null && world.getName().equals(cosmosWorldName);
    }

    // ------------------------------------------------------------------
    // 传送相关
    // ------------------------------------------------------------------

    /** 缩放系数，恒 >= 1。 */
    public double scale() {
        return scale;
    }

    public int scaleInt() {
        return scale;
    }

    /**
     * 主世界的传送高度：{@code Y >= teleport-y-world} 时传送到 Cosmos。
     */
    public int teleportYWorld() {
        return teleportYWorld;
    }

    /**
     * Cosmos 的传送高度：{@code Y <= teleport-y-cosmos} 时传回主世界。
     */
    public int teleportYCosmos() {
        return teleportYCosmos;
    }

    /**
     * 到达高度偏移：去 Cosmos 落在 {@code teleport-y-cosmos + offset}，
     * 回主世界落在 {@code teleport-y-world - offset}（默认 10，形成 10 格的迟滞区）。
     */
    public int arrivalYOffset() {
        return arrivalYOffset;
    }

    /**
     * 星球生成下限：默认等于 {@link #teleportYCosmos()}（星球只出现在 Cosmos 传送高度以上）。
     * 如果配置里所有星球都在该高度以下，加载器会退化成 {@link Integer#MIN_VALUE}
     * （不裁剪），避免整个世界变成空的。
     */
    public int planetFloorY() {
        return planetFloorY;
    }

    public long teleportCooldownMs() {
        return teleportCooldownMs;
    }

    public int portalCooldownTicks() {
        return portalCooldownTicks;
    }

    public int yCheckIntervalTicks() {
        return yCheckIntervalTicks;
    }

    /**
     * Cosmos 世界被外部世界管理插件卸载 / 重新加载时的接管检查周期（tick）。
     * {@code 0} 表示关闭看门狗。
     */
    public int worldCheckIntervalTicks() {
        return worldCheckIntervalTicks;
    }

    /**
     * Cosmos 世界的模拟 / 渲染视距（区块）。{@code 0} 表示沿用服务端全局设置。
     * 跨维度时客户端要为整个视距内的区块建网格，视距越大切世界的那一下越卡。
     */
    public int cosmosViewDistance() {
        return cosmosViewDistance;
    }

    /**
     * Cosmos 世界单独下发给客户端的视距（区块）。{@code 0} 表示沿用 {@link #cosmosViewDistance()}。
     * 比 {@link #cosmosViewDistance()} 小时，客户端看到的区块更少、切世界更快，
     * 但服务端仍然按 {@link #cosmosViewDistance()} 加载与模拟。
     */
    public int cosmosSendViewDistance() {
        return cosmosSendViewDistance;
    }

    /**
     * 传送前在目标点周围预加载的区块半径（{@code 0} = 只加载落点那一个区块）。
     * 预加载能让客户端落地后立刻有邻居区块可渲染，而不是边落边等。
     */
    public int teleportPreloadRadius() {
        return teleportPreloadRadius;
    }

    public Fallback fallback() {
        return fallback;
    }

    /** @return Cosmos 世界的兜底落点（{@code /cosmos tp} 使用）；世界不存在时为 {@code null}。 */
    public Location fallbackLocation(Server server) {
        return fallback.resolve(server);
    }

    // ------------------------------------------------------------------
    // 世界生成
    // ------------------------------------------------------------------

    public World.Environment cosmosEnvironment() {
        return cosmosEnvironment;
    }

    /**
     * Cosmos 世界的生物群系。{@code Biome} 常量由服务端注册表驱动，
     * 因此没有服务端时（单元测试）builder 默认不解析它；这里统一回退到 {@code the_end}。
     */
    public Biome cosmosBiome() {
        Biome current = cosmosBiome;
        return current != null ? current : Biome.THE_END;
    }

    public long worldSeed() {
        return worldSeed;
    }

    public boolean worldAutoCreate() {
        return worldAutoCreate;
    }

    /** Cosmos 世界边界是否与主世界同步（中心 / 尺寸按 {@code scale} 换算）。 */
    public boolean cosmosWorldBorderSync() {
        return cosmosWorldBorderSync;
    }

    public Map<String, String> gamerules() {
        return gamerules;
    }

    /** 程序化星球场配置（{@code planets.yml} 的 {@code procedural} 段）。 */
    public ProceduralPlanetSettings proceduralPlanets() {
        return proceduralPlanets;
    }

    // ------------------------------------------------------------------
    // 掉落物悬浮
    // ------------------------------------------------------------------

    public boolean itemFloatEnabled() {
        return itemFloatEnabled;
    }

    public boolean floatExperienceOrb() {
        return floatExperienceOrb;
    }

    public boolean floatFreezeVelocity() {
        return floatFreezeVelocity;
    }

    public boolean floatUnlimitedLifetime() {
        return floatUnlimitedLifetime;
    }

    /** 沙子 / 沙砾 / 混凝土粉末 / 铁砧等重力方块在 Cosmos 中不再下落。 */
    public boolean floatGravityBlocks() {
        return floatGravityBlocks;
    }

    public int floatSweepIntervalTicks() {
        return floatSweepIntervalTicks;
    }

    public double floatSweepRadius() {
        return floatSweepRadius;
    }

    // ------------------------------------------------------------------
    // 玩家悬浮
    // ------------------------------------------------------------------

    /**
     * 玩家在 Cosmos 内是否使用低重力悬浮（缓慢下沉，不授予飞行）。
     *
     * <p>穿上鞘翅的玩家会被完全跳过，见 {@link #playerFloatSkipElytra()}。</p>
     */
    public boolean playerFloatEnabled() {
        return playerFloatEnabled;
    }

    /**
     * 胸口穿着鞘翅的玩家是否跳过悬浮巡检。
     *
     * <p>悬浮巡检每 {@code player-float-interval-ticks} tick 下发一次速度包，会打断鞘翅的
     * 起飞与滑翔手感。默认 {@code true}：穿鞘翅的玩家完全交给原版滑翔逻辑。</p>
     */
    public boolean playerFloatSkipElytra() {
        return playerFloatSkipElytra;
    }

    /** 低重力下缓慢下沉的最大速度（格/tick，0 = 完全悬停）。 */
    public double playerFloatFallSpeed() {
        return playerFloatFallSpeed;
    }

    /** 低重力巡检周期（tick）。 */
    public int playerFloatIntervalTicks() {
        return playerFloatIntervalTicks;
    }

    public boolean debug() {
        return debug;
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /** 配置构建器（仅 {@link SettingsLoader} 使用）。 */
    public static final class Builder {
        private String mainWorldName = "world";
        private String cosmosWorldName = "cosmos";
        private World.Environment cosmosEnvironment = World.Environment.THE_END;
        // null = 未显式配置，由 cosmosBiome() 回退到 the_end（避免在无服务端时解析注册表）
        private Biome cosmosBiome = null;
        private boolean cosmosWorldBorderSync = true;
        private int scale = 64;
        private int teleportYWorld = 150;
        private int teleportYCosmos = 150;
        private int arrivalYOffset = 10;
        private int planetFloorY = Integer.MIN_VALUE;
        private long teleportCooldownMs = 3000L;
        private int portalCooldownTicks = 100;
        private int yCheckIntervalTicks = 10;
        private int worldCheckIntervalTicks = 100;
        private int cosmosViewDistance = 0;
        private int cosmosSendViewDistance = 8;
        private int teleportPreloadRadius = 2;
        private boolean itemFloatEnabled = true;
        private boolean floatExperienceOrb = false;
        private boolean floatFreezeVelocity = true;
        private boolean floatUnlimitedLifetime = false;
        private boolean floatGravityBlocks = true;
        private int floatSweepIntervalTicks = 200;
        private double floatSweepRadius = 16.0D;
        private boolean playerFloatEnabled = true;
        private boolean playerFloatSkipElytra = true;
        private double playerFloatFallSpeed = 0.08D;
        private int playerFloatIntervalTicks = 2;
        private ProceduralPlanetSettings proceduralPlanets = ProceduralPlanetSettings.disabled();
        private Fallback fallback = new Fallback("cosmos", 0.5D, 160.0D, 0.5D, 0.0F, 0.0F);
        private long worldSeed = 1337L;
        private boolean worldAutoCreate = true;
        private Map<String, String> gamerules = Map.of();
        private boolean debug = false;

        private Builder() {
        }

        public Builder mainWorldName(String value) {
            this.mainWorldName = value;
            return this;
        }

        public Builder cosmosWorldName(String value) {
            this.cosmosWorldName = value;
            return this;
        }

        public Builder cosmosEnvironment(World.Environment value) {
            this.cosmosEnvironment = value;
            return this;
        }

        public Builder cosmosBiome(Biome value) {
            this.cosmosBiome = value;
            return this;
        }

        public Builder cosmosWorldBorderSync(boolean value) {
            this.cosmosWorldBorderSync = value;
            return this;
        }

        public Builder scale(int value) {
            this.scale = value;
            return this;
        }

        public Builder teleportYWorld(int value) {
            this.teleportYWorld = value;
            return this;
        }
        public Builder teleportYCosmos(int value) {
            this.teleportYCosmos = value;
            return this;
        }

        public Builder arrivalYOffset(int value) {
            this.arrivalYOffset = value;
            return this;
        }

        public Builder planetFloorY(int value) {
            this.planetFloorY = value;
            return this;
        }

        public Builder teleportCooldownMs(long value) {
            this.teleportCooldownMs = value;
            return this;
        }

        public Builder portalCooldownTicks(int value) {
            this.portalCooldownTicks = value;
            return this;
        }

        public Builder yCheckIntervalTicks(int value) {
            this.yCheckIntervalTicks = value;
            return this;
        }

        public Builder worldCheckIntervalTicks(int value) {
            this.worldCheckIntervalTicks = value;
            return this;
        }

        public Builder cosmosViewDistance(int value) {
            this.cosmosViewDistance = value;
            return this;
        }

        public Builder cosmosSendViewDistance(int value) {
            this.cosmosSendViewDistance = value;
            return this;
        }

        public Builder teleportPreloadRadius(int value) {
            this.teleportPreloadRadius = value;
            return this;
        }

        public Builder itemFloatEnabled(boolean value) {
            this.itemFloatEnabled = value;
            return this;
        }

        public Builder floatExperienceOrb(boolean value) {
            this.floatExperienceOrb = value;
            return this;
        }

        public Builder floatFreezeVelocity(boolean value) {
            this.floatFreezeVelocity = value;
            return this;
        }

        public Builder floatUnlimitedLifetime(boolean value) {
            this.floatUnlimitedLifetime = value;
            return this;
        }

        public Builder floatGravityBlocks(boolean value) {
            this.floatGravityBlocks = value;
            return this;
        }

        public Builder floatSweepIntervalTicks(int value) {
            this.floatSweepIntervalTicks = value;
            return this;
        }

        public Builder floatSweepRadius(double value) {
            this.floatSweepRadius = value;
            return this;
        }

        public Builder playerFloatEnabled(boolean value) {
            this.playerFloatEnabled = value;
            return this;
        }

        public Builder playerFloatSkipElytra(boolean value) {
            this.playerFloatSkipElytra = value;
            return this;
        }

        public Builder playerFloatFallSpeed(double value) {
            this.playerFloatFallSpeed = value;
            return this;
        }

        public Builder playerFloatIntervalTicks(int value) {
            this.playerFloatIntervalTicks = value;
            return this;
        }

        public Builder proceduralPlanets(ProceduralPlanetSettings value) {
            this.proceduralPlanets = value == null ? ProceduralPlanetSettings.disabled() : value;
            return this;
        }

        public Builder fallback(Fallback value) {
            this.fallback = value;
            return this;
        }

        public Builder worldSeed(long value) {
            this.worldSeed = value;
            return this;
        }

        public Builder worldAutoCreate(boolean value) {
            this.worldAutoCreate = value;
            return this;
        }

        public Builder gamerules(Map<String, String> value) {
            this.gamerules = value;
            return this;
        }

        public Builder debug(boolean value) {
            this.debug = value;
            return this;
        }

        public CosmosSettings build() {
            return new CosmosSettings(this);
        }
    }
}
