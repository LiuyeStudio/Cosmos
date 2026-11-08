package studio.liuye.cosmos;

import org.bukkit.block.data.BlockData;

import java.lang.reflect.Proxy;

/**
 * 测试辅助：用一个动态代理伪造 {@link BlockData}。
 *
 * <p>单元测试不启动服务端，因此不能调用 {@code Material#createBlockData()}；
 * 这里只关心 {@code getAsString()}（用来标记“这是哪个方块”），
 * 其余方法返回类型默认值即可。</p>
 */
public final class TestSupport {

    private TestSupport() {
        throw new AssertionError("utility class");
    }

    /** 生成一个 {@code getAsString()/toString()} 返回 {@code label} 的假 BlockData。 */
    public static BlockData block(String label) {
        return (BlockData) Proxy.newProxyInstance(
                TestSupport.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAsString":
                        case "toString":
                            return label;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && proxy == args[0];
                        case "clone":
                        case "merge":
                            return proxy;
                        case "matches":
                            return args != null && (args[0] == proxy
                                    || (args[0] != null && label.equals(String.valueOf(args[0]))));
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }
}
