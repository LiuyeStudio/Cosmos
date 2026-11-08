package studio.liuye.cosmos.platform;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

/**
 * 运行环境探测。
 *
 * <p>按需求必须通过 {@link ServerBuildInfo#buildInfo()} +
 * {@link ServerBuildInfo#isBrandCompatible(Key)} 判断当前服务端是否为 Folia，
 * 而不是依赖类名字符串或 {@code Bukkit.getVersion()} 之类的脆弱手段。</p>
 *
 * <p>检测结果只做两件事：</p>
 * <ul>
 *   <li>日志 / {@code /cosmos info} 中声明运行环境；</li>
 *   <li>选择“遍历世界内全部实体”的不同实现（Folia 没有全局实体列表）。</li>
 * </ul>
 * <p>所有调度路径本身在 Paper 与 Folia 上是同一套区域化 API，因此不需要分叉实现。</p>
 */
public final class FoliaSupport {

    /** Folia 的 brand id：{@code papermc:folia}。 */
    private static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    private static volatile Boolean folia;

    private FoliaSupport() {
        throw new AssertionError("utility class");
    }

    /**
     * @return {@code true} 表示运行在 Folia（区域化多线程服务端）上。
     */
    public static boolean isFolia() {
        Boolean cached = folia;
        if (cached != null) {
            return cached;
        }
        boolean detected;
        try {
            ServerBuildInfo info = ServerBuildInfo.buildInfo();
            detected = info.isBrandCompatible(FOLIA_BRAND);
        } catch (Throwable failure) {
            // 极端情况下（非常老的 API / 反射被拦截）降级为 Paper 语义：
            // Paper 上所有 Bukkit API 都能在全局线程安全调用，因此这是保守选择。
            detected = false;
        }
        folia = detected;
        return detected;
    }

    /**
     * 便于日志与 /cosmos info 输出的人类可读描述，例如
     * {@code Paper 26.3 (build 226)} / {@code Folia 26.3 (build 5)}。
     */
    public static String serverSummary() {
        try {
            ServerBuildInfo info = ServerBuildInfo.buildInfo();
            StringBuilder builder = new StringBuilder(info.brandName())
                    .append(' ').append(info.minecraftVersionName());
            if (info.buildNumber().isPresent()) {
                builder.append(" (build ").append(info.buildNumber().getAsInt()).append(')');
            }
            return builder.toString();
        } catch (Throwable failure) {
            return "unknown server";
        }
    }
}
