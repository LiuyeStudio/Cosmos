package studio.liuye.cosmos.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送冷却表。
 *
 * <p>纯内存、线程安全，可在区域线程 / 全局线程 / 异步线程任意调用
 * （只有 {@link ConcurrentHashMap} 操作，不触碰任何 Bukkit 世界数据）。</p>
 */
public final class TeleportCooldowns {

    private final Map<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public boolean isCoolingDown(UUID playerId, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return false;
        }
        Long last = lastTeleport.get(playerId);
        return last != null && System.currentTimeMillis() - last < cooldownMillis;
    }

    public long remainingMillis(UUID playerId, long cooldownMillis) {
        Long last = lastTeleport.get(playerId);
        if (last == null) {
            return 0L;
        }
        long remaining = cooldownMillis - (System.currentTimeMillis() - last);
        return Math.max(0L, remaining);
    }

    public void mark(UUID playerId) {
        lastTeleport.put(playerId, System.currentTimeMillis());
    }

    public void clear(UUID playerId) {
        lastTeleport.remove(playerId);
    }

    public void clearAll() {
        lastTeleport.clear();
    }
}
