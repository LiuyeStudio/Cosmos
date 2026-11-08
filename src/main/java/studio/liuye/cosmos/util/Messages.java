package studio.liuye.cosmos.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import studio.liuye.cosmos.CosmosPlugin;

/**
 * 底层发送工具。
 *
 * <p>文本本身（含前缀）由 {@link MessageService} 从 {@code message.yml} 取，
 * 这里只负责“解析 {@code &} 颜色代码 + 切到正确线程”：
 * 玩家的消息通过 {@code EntityScheduler} 切回其所属区域线程，
 * 因此可以从全局线程 / 异步线程 / 区块线程安全调用；控制台发送与线程无关。</p>
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Messages() {
        throw new AssertionError("utility class");
    }

    /** 解析 {@code &} 颜色代码。 */
    public static Component component(String message) {
        return LEGACY.deserialize(message == null ? "" : message);
    }

    /** 发送一段已完成格式化的文本（前缀由调用方决定）；玩家的消息会调度到其区域线程。 */
    public static void send(CosmosPlugin plugin, CommandSender sender, String message) {
        Component component = component(message);
        if (sender instanceof Player player) {
            plugin.scheduler().entity(player, () -> player.sendMessage(component), null);
        } else {
            sender.sendMessage(component);
        }
    }
}
