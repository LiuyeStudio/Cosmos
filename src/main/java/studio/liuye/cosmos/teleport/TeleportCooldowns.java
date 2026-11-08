package studio.liuye.cosmos.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送冷却表。
 *
 * <p>纯内存、线程安全，可在区域线程 / 全局线程 / 异步线程任意调用
 * （只有 {@link ConcurrentHashMap} 操作，不触碰任何 Bukkit 世界数据）。</p>
 *
 * <p>时间基准用 {@link System#nanoTime()}（单调时钟）而不是 {@code currentTimeMillis}：
 * 服务器校时或用户改系统时间时墙钟会跳变，跳变一次就可能让冷却立刻失效或永久卡住。</p>
 */
public final class TeleportCooldowns {

    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    /** 单调毫秒。只用于比较两次记录之间的间隔，不表示任何绝对时间。 */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    public boolean isCoolingDown(UUID playerId, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return false;
        }
        Long last = lastTeleport.get(playerId);
        return last != null && now() - last < cooldownMillis;
    }

    public long remainingMillis(UUID playerId, long cooldownMillis) {
        Long last = lastTeleport.get(playerId);
        if (last == null) {
            return 0L;
        }
        long remaining = cooldownMillis - (now() - last);
        return Math.max(0L, remaining);
    }

    public void mark(UUID playerId) {
        lastTeleport.put(playerId, now());
    }

    public void clear(UUID playerId) {
        lastTeleport.remove(playerId);
    }

    public void clearAll() {
        lastTeleport.clear();
    }
}
