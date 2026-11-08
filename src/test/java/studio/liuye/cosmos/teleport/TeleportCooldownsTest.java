package studio.liuye.cosmos.teleport;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 传送冷却表测试：保证阈值附近不会来回弹跳。 */
public class TeleportCooldownsTest {

    @Test
    public void zeroCooldownNeverBlocks() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        UUID player = UUID.randomUUID();

        cooldowns.mark(player);

        assertFalse(cooldowns.isCoolingDown(player, 0L));
    }

    @Test
    public void markStartsCooldown() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        UUID player = UUID.randomUUID();

        assertFalse(cooldowns.isCoolingDown(player, 3_000L));
        cooldowns.mark(player);

        assertTrue(cooldowns.isCoolingDown(player, 3_000L));
        assertTrue(cooldowns.remainingMillis(player, 3_000L) > 0L);
        assertTrue(cooldowns.remainingMillis(player, 3_000L) <= 3_000L);
    }

    @Test
    public void clearReleasesPlayer() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        UUID player = UUID.randomUUID();

        cooldowns.mark(player);
        cooldowns.clear(player);

        assertFalse(cooldowns.isCoolingDown(player, 3_000L));
    }

    @Test
    public void differentPlayersAreIndependent() {
        TeleportCooldowns cooldowns = new TeleportCooldowns();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cooldowns.mark(first);

        assertTrue(cooldowns.isCoolingDown(first, 3_000L));
        assertFalse(cooldowns.isCoolingDown(second, 3_000L));
    }
}
