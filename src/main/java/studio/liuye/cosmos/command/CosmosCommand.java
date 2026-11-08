package studio.liuye.cosmos.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import studio.liuye.cosmos.CosmosPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /cosmos} 命令。
 *
 * <p>所有回复文本都来自 {@code message.yml}（{@link studio.liuye.cosmos.util.MessageService}）。</p>
 *
 * <p><b>线程说明：</b>Bukkit 命令执行线程在不同实现下并不保证是“拥有玩家区域”的线程，
 * 因此命令体里绝不直接调用 {@code player.teleport()} 或读写方块：</p>
 * <ul>
 *   <li>{@code reload}：异步读盘 → {@code GlobalRegionScheduler} 校验并应用配置；</li>
 *   <li>{@code tp}：交给 {@code TeleportService}，最终在目标玩家的 {@code EntityScheduler} 上执行；</li>
 *   <li>{@code info}：在 {@code GlobalRegionScheduler} 上读取配置快照并回复。</li>
 * </ul>
 */
public final class CosmosCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "tp", "info", "help");

    private final CosmosPlugin plugin;

    public CosmosCommand(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (require(sender, "cosmos.reload")) {
                    plugin.reloadAsync(sender);
                }
            }
            case "tp" -> {
                if (require(sender, "cosmos.tp")) {
                    handleTeleport(sender, args);
                }
            }
            case "info" -> {
                if (require(sender, "cosmos.info")) {
                    plugin.sendInfo(sender);
                }
            }
            case "help" -> sendHelp(sender, label);
            default -> plugin.messages().send(sender, "command-unknown", "arg", args[0], "label", label);
        }
        return true;
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                plugin.messages().send(sender, "command-player-offline", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.messages().send(sender, "command-tp-usage");
            return;
        }

        if (!plugin.teleports().teleportToCosmos(target)) {
            plugin.messages().send(sender, "teleport-rejected");
            return;
        }
        plugin.messages().send(sender, "teleport-pending", "player", target.getName());
        if (!target.equals(sender)) {
            plugin.messages().send(target, "teleport-receiving");
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "command-no-permission", "permission", permission);
        return false;
    }

    private void sendHelp(CommandSender sender, String label) {
        plugin.messages().send(sender, "help-header");
        plugin.messages().send(sender, "help-reload", "label", label);
        plugin.messages().send(sender, "help-tp", "label", label);
        plugin.messages().send(sender, "help-info", "label", label);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            List<String> names = new ArrayList<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
