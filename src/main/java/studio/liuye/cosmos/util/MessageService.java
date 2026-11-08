package studio.liuye.cosmos.util;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import studio.liuye.cosmos.CosmosPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一的消息服务：所有发给玩家的文本都来自 {@code message.yml}。
 *
 * <p>文件里缺失的键会回落到 {@link #DEFAULTS}，因此即使玩家把文件删了、键写错了，
 * 插件也不会发出空消息。{@code /cosmos reload} 会整体替换消息快照。</p>
 *
 * <p>线程约定：{@link #apply(YamlConfiguration)} 在全局线程调用；
 * 发送时 {@link Messages#send} 会把玩家消息切回其区域线程。</p>
 */
public final class MessageService {

    /** 内置默认前缀。 */
    public static final String DEFAULT_PREFIX = "&8[&bCosmos&8] &r";

    /** 内置默认消息：与 message.yml 保持一致，作为缺失键的兜底。 */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("teleport-success", "&a已传送 &7({direction}) &f→ &b{world} &f{x}, {y}, {z}"),
            Map.entry("teleport-failed", "&c传送失败: &f{reason}"),
            Map.entry("teleport-unavailable", "&cCosmos 世界不可用，无法传送。"),
            Map.entry("teleport-rejected", "&c传送请求未被接受（可能正在传送中，或 Cosmos 世界不可用）。"),
            Map.entry("teleport-pending", "&a正在传送 &f{player} &a到 Cosmos…"),
            Map.entry("teleport-receiving", "&e你正在被传送到 Cosmos…"),
            Map.entry("command-unknown", "&c未知子命令: &f{arg}&7，使用 /{label} help"),
            Map.entry("command-no-permission", "&c你没有权限: &f{permission}"),
            Map.entry("command-tp-usage", "&e用法: /cosmos tp <player>"),
            Map.entry("command-player-offline", "&c玩家 &f{player} &c不在线。"),
            Map.entry("help-header", "&b===== Cosmos ====="),
            Map.entry("help-reload", "&f/{label} reload &7- 重载配置与消息 (cosmos.reload)"),
            Map.entry("help-tp", "&f/{label} tp [player] &7- 传送到 Cosmos (cosmos.tp)"),
            Map.entry("help-info", "&f/{label} info &7- 查看缩放 / 阈值 / 世界状态 (cosmos.info)"),
            Map.entry("reload-start", "&e正在重载配置…"),
            Map.entry("reload-success", "&a配置已重载并生效：缩放 1:{scale}。"),
            Map.entry("reload-success-no-world", "&e配置已重载，但 Cosmos 世界不可用（详见控制台）。"),
            Map.entry("reload-failed", "&c重载失败: &f{reason}"),
            Map.entry("info-unavailable", "&c配置尚未加载完成，请稍后再试。"),
            Map.entry("info-header", "&b===== Cosmos 状态 ====="),
            Map.entry("info-runtime", "&7运行环境: &f{servers} &7(Folia={folia})"),
            Map.entry("info-scale", "&7缩放: &f1 : {scale} &7(Cosmos 1 格 = 主世界 {scale} 格)"),
            Map.entry("info-main-world", "&7主世界: &f{world}"),
            Map.entry("info-main-world-missing", "&7主世界: &f{world} &c(未加载)"),
            Map.entry("info-cosmos-world", "&7Cosmos 世界: &f{world} &a(已加载, 环境 {environment})"),
            Map.entry("info-cosmos-world-missing", "&7Cosmos 世界: &f{world} &c(不可用)"),
            Map.entry("info-teleport", "&7传送高度: &f主世界 Y>={y-world} &7→ &fCosmos Y={up} &7| &fCosmos Y<={y-cosmos} &7→ &f主世界 Y={down} &7(X/{scale} 或 X*{scale})"),
            Map.entry("info-border", "&7世界边界: &f{border}"),
            Map.entry("info-status", "&7冷却: &f{cooldown} ms &7| 悬浮掉落物: &f{items} &7| 玩家悬浮: &f{players}"),
            Map.entry("info-procedural", "&7程序化星球: &a开 &7(网格 &f{cell} &7格, 每格 &f{chance} &7, 半径 &f{min}..{max} &7, Y &f{y-min}..{y-max} &7, 材质 &f{palette} &7种, 原点 &f{spawn})"),
            Map.entry("info-procedural-off", "&7程序化星球: &7关 &8(planets.yml 的 procedural.enabled)"),
            Map.entry("info-coords-cosmos", "&7你正在 Cosmos (&f{x}, {y}, {z}&7)，对应主世界 X/Z = &f{main-x}, {main-z}&7 (X*{scale}, Z*{scale})；回主世界时 Y 固定为 {arrival}"),
            Map.entry("info-coords-main", "&7你正在主世界 (&f{x}, {y}, {z}&7)，对应 Cosmos X/Z = &f{main-x}, {main-z}&7 (X/{scale}, Z/{scale})；去 Cosmos 时 Y 固定为 {arrival}"),
            Map.entry("enabled", "&a开"),
            Map.entry("disabled", "&7关"),
            Map.entry("border-synced", "与主世界同步 (1:{scale})"),
            Map.entry("border-not-synced", "不同步")
    );

    private final CosmosPlugin plugin;

    private volatile String prefix = DEFAULT_PREFIX;
    private volatile Map<String, String> templates = DEFAULTS;
    private volatile boolean teleportMessage = false;

    public MessageService(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 应用 {@code message.yml}（全局线程）。缺失或不存在的键回落到内置默认值。
     */
    public void apply(YamlConfiguration yaml) {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        String configuredPrefix = yaml == null ? DEFAULT_PREFIX : yaml.getString("prefix", DEFAULT_PREFIX);
        this.prefix = configuredPrefix == null ? "" : configuredPrefix;
        this.teleportMessage = yaml != null && yaml.getBoolean("teleport-message", false);

        ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    merged.put(key, value);
                }
            }
        }
        this.templates = Map.copyOf(merged);
    }

    /** 从磁盘读取并应用（异常时保留旧快照并记日志）。 */
    public void applyFile(File file) {
        try {
            apply(studio.liuye.cosmos.config.SettingsLoader.read(file));
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("读取 message.yml 失败，继续使用当前消息: " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    /** 世界切换时是否提示玩家（默认 false）。 */
    public boolean teleportMessageEnabled() {
        return teleportMessage;
    }

    /** 读取并格式化一条消息（不带前缀），可用于日志或二次拼接。 */
    public String raw(String key, Object... placeholders) {
        String template = templates.get(key);
        if (template == null) {
            template = "&c[缺少消息: " + key + "]";
        }
        return fill(template, placeholders);
    }

    /** 读取并格式化一条消息（带配置的前缀）。 */
    public String text(String key, Object... placeholders) {
        return prefix + raw(key, placeholders);
    }

    /** 发给命令发送者 / 玩家；玩家消息会自动切回其区域线程。 */
    public void send(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) {
            return;
        }
        Messages.send(plugin, sender, text(key, placeholders));
    }

    /** 只发给玩家（便于在实体任务里调用）。 */
    public void send(Player player, String key, Object... placeholders) {
        send((CommandSender) player, key, placeholders);
    }

    private static String fill(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length < 2) {
            return template;
        }
        String result = template;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String name = String.valueOf(placeholders[index]);
            String value = String.valueOf(placeholders[index + 1]);
            result = result.replace("{" + name + "}", value);
        }
        return result;
    }
}
