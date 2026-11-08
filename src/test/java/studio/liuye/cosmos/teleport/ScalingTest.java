package studio.liuye.cosmos.teleport;

import org.bukkit.Location;
import org.junit.Test;
import studio.liuye.cosmos.config.CosmosSettings;

import static org.junit.Assert.assertEquals;

/**
 * 坐标换算测试（纯数学，不需要服务端）。
 *
 * <p>{@link Location} 允许 {@code world == null}，因此这里不需要真实世界即可验证换算。</p>
 *
 * <p>语义：X/Z 按 {@code scale} 缩放；Y 由两个世界共用的 {@code teleport-y} 推导：
 * 去 Cosmos 是 {@code +offset}，回主世界是 {@code -offset}。</p>
 */
public class ScalingTest {

    private static CosmosSettings settings() {
        return CosmosSettings.builder()
                .mainWorldName("world")
                .cosmosWorldName("cosmos")
                .scale(64)
                .teleportYWorld(150).teleportYCosmos(200)
                .arrivalYOffset(10)
                .build();
    }

    @Test
    public void mainToCosmosDividesHorizontallyAndLandsAboveTheThreshold() {
        Location from = new Location(null, 640.0D, 300.0D, -1280.0D, 90.0F, 10.0F);

        Location to = TeleportService.computeDestination(settings(),
                TeleportService.Direction.MAIN_TO_COSMOS, from, null);

        assertEquals(10.0D, to.getX(), 1.0E-9D);
        assertEquals(-20.0D, to.getZ(), 1.0E-9D);
        assertEquals(210.0D, to.getY(), 1.0E-9D);
        assertEquals(90.0F, to.getYaw(), 1.0E-6F);
        assertEquals(10.0F, to.getPitch(), 1.0E-6F);
    }

    @Test
    public void cosmosToMainMultipliesHorizontallyAndLandsBelowTheThreshold() {
        Location from = new Location(null, 3.0D, 250.0D, -4.0D, 0.0F, 0.0F);

        Location to = TeleportService.computeDestination(settings(),
                TeleportService.Direction.COSMOS_TO_MAIN, from, null);

        assertEquals(192.0D, to.getX(), 1.0E-9D);
        assertEquals(-256.0D, to.getZ(), 1.0E-9D);
        assertEquals(140.0D, to.getY(), 1.0E-9D);
    }

    /** 落点就是换算结果本身：X/Z 不对齐到方块中心，小数位必须原样保留。 */
    @Test
    public void fractionalCoordinatesAreNotSnappedToBlockCenters() {
        Location from = new Location(null, 640.7D, 300.25D, -1280.35D, 0.0F, 0.0F);

        Location to = TeleportService.computeDestination(settings(),
                TeleportService.Direction.MAIN_TO_COSMOS, from, null);

        assertEquals(10.0109375D, to.getX(), 1.0E-9D);
        assertEquals(-20.00546875D, to.getZ(), 1.0E-9D);
    }

    /** 到达高度只由 teleport-y 决定，与出发高度无关。 */
    @Test
    public void arrivalHeightIgnoresTheSourceHeight() {
        CosmosSettings settings = settings();
        Location low = new Location(null, 16.0D, 300.0D, 8.0D, 0.0F, 0.0F);
        Location high = new Location(null, 16.0D, 512.0D, 8.0D, 0.0F, 0.0F);

        assertEquals(210.0D, TeleportService.computeDestination(settings,
                TeleportService.Direction.MAIN_TO_COSMOS, low, null).getY(), 1.0E-9D);
        assertEquals(210.0D, TeleportService.computeDestination(settings,
                TeleportService.Direction.MAIN_TO_COSMOS, high, null).getY(), 1.0E-9D);
    }

    @Test
    public void zeroOffsetKeepsTheThresholdHeight() {
        CosmosSettings noOffset = CosmosSettings.builder()
                .scale(8).teleportYWorld(128).teleportYCosmos(128).arrivalYOffset(0).build();
        Location from = new Location(null, 16.0D, 128.0D, 8.0D, 0.0F, 0.0F);

        Location to = TeleportService.computeDestination(noOffset,
                TeleportService.Direction.MAIN_TO_COSMOS, from, null);

        assertEquals(2.0D, to.getX(), 1.0E-9D);
        assertEquals(128.0D, to.getY(), 1.0E-9D);
    }

    /** 未裁剪的 Y：去 Cosmos +offset、回主世界 -offset。 */
    @Test
    public void rawDestinationYFollowsTheThreshold() {
        CosmosSettings settings = settings();

        assertEquals(210.0D, TeleportService.rawDestinationY(settings,
                TeleportService.Direction.MAIN_TO_COSMOS), 1.0E-9D);
        assertEquals(140.0D, TeleportService.rawDestinationY(settings,
                TeleportService.Direction.COSMOS_TO_MAIN), 1.0E-9D);
    }

    @Test
    public void horizontalCoordinatesAreClampedToTheWorldLimit() {
        Location from = new Location(null, 1.0E12D, 300.0D, -1.0E12D, 0.0F, 0.0F);

        Location to = TeleportService.computeDestination(settings(),
                TeleportService.Direction.MAIN_TO_COSMOS, from, null);

        assertEquals(29_999_000.0D, to.getX(), 1.0E-9D);
        assertEquals(-29_999_000.0D, to.getZ(), 1.0E-9D);
    }

    @Test
    public void nonFiniteCoordinatesBecomeZero() {
        Location from = new Location(null, Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, 0.0F, 0.0F);

        Location to = TeleportService.computeDestination(settings(),
                TeleportService.Direction.COSMOS_TO_MAIN, from, null);

        assertEquals(0.0D, to.getX(), 1.0E-9D);
        assertEquals(0.0D, to.getZ(), 1.0E-9D);
        assertEquals(140.0D, to.getY(), 1.0E-9D);
    }

    /** X/Z 往返无损；Y 按「+offset 进、-offset 出」的对角线关系，回到阈值下方 offset 格。 */
    @Test
    public void roundTripKeepsHorizontalCoordinatesAndTheOffsetRelation() {
        Location main = new Location(null, 64.0D, 300.0D, -192.0D, 0.0F, 0.0F);

        Location cosmos = TeleportService.computeDestination(settings(),
                TeleportService.Direction.MAIN_TO_COSMOS, main, null);
        Location back = TeleportService.computeDestination(settings(),
                TeleportService.Direction.COSMOS_TO_MAIN, cosmos, null);

        assertEquals(main.getX(), back.getX(), 1.0E-9D);
        assertEquals(main.getZ(), back.getZ(), 1.0E-9D);
        assertEquals(210.0D, cosmos.getY(), 1.0E-9D);
        assertEquals(140.0D, back.getY(), 1.0E-9D);
    }
}
