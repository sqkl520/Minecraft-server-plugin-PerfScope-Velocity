package cn.perfscope.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用的 Velocity 运行时替身（动态代理，零 mock 框架依赖）。
 *
 * <h2>为什么必须自造</h2>
 *
 * <p>代理侧的两个零覆盖类（{@code NetworkCommand} 与 {@code PerfScopeVelocity}）都"看起来"
 * 需要真实 Velocity 才能测：前者要 {@code CommandSource} 与 {@code Invocation}，
 * 后者要 {@code ProxyServer} 与 {@code PluginMessageEvent}。<b>但它们全是接口</b>，
 * {@code Proxy.newProxyInstance} 就够了。</p>
 *
 * <p>这与后端仓库正好相反：后端卡住的是 {@code org.bukkit.Server}，它的方法签名引用了
 * {@code StructureType}，创建 Proxy 会触发那个类的静态初始化并 NPE（见后端仓库
 * {@code tools/KNOWN-TEST-LIMITS.md}）。Velocity 的 API 没有这种静态初始化地雷，
 * 所以<b>同一手法在这边可行、在那边不可行</b>。记住这个差异的原因：
 * 能不能造假对象取决于接口签名会牵出什么，而不取决于它是不是接口。</p>
 *
 * <h2>默认值的安全方向</h2>
 *
 * <p>未登记的方法一律返回"没有授权 / 没有数据"：{@code false}、{@code 0}、
 * {@link Optional#empty()}、{@link Tristate#UNDEFINED}。默认值绝不返回"看起来健康"的东西——
 * 否则测试里一个漏登记的方法会让断言以<b>假绿</b>方式通过。</p>
 */
final class VelocityStubs {

    private VelocityStubs() {
    }

    /** 方法名 → 处理函数；{@code args} 是方法实参（无参时为空数组）。 */
    static final class Spec {
        private final String method;
        private final Function<Object[], Object> handler;

        private Spec(String method, Function<Object[], Object> handler) {
            this.method = method;
            this.handler = handler;
        }
    }

    static Spec on(String method, Function<Object[], Object> handler) {
        return new Spec(method, handler);
    }

    /** 未登记方法的返回值。刻意全是"没有"：见类注释"默认值的安全方向"。 */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == Optional.class) return Optional.empty();
        if (returnType == List.class || returnType == Collection.class) return Collections.emptyList();
        if (returnType == Tristate.class) {
            // 未装权限插件时 Velocity 的真实行为就是 ALWAYS_UNDEFINED（asBoolean() = false）。
            // 替身必须复现这个保守默认，否则"默认谁都用不了"这条性质测不出来。
            return Tristate.UNDEFINED;
        }
        return null;
    }

    /**
     * 造替身。
     *
     * @param interfaces 要实现的一组接口。可以多于一个——伪造"控制台"就靠这个：
     *                   {@code NetworkCommand.hasPermission} 用 {@code instanceof ConsoleCommandSource}
     *                   判断，只实现 {@code CommandSource} 的替身<b>不是</b>控制台。
     */
    static Object stubWith(Class<?>[] interfaces, Spec... specs) {
        final List<Spec> list = List.of(specs);
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "stub:" + interfaces[0].getSimpleName();
                    default:
                        return null;
                }
            }
            for (Spec s : list) {
                if (s.method.equals(method.getName())) {
                    return s.handler.apply(args == null ? new Object[0] : args);
                }
            }
            return defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(VelocityStubs.class.getClassLoader(), interfaces, h);
    }

    static <T> T stub(Class<T> iface, Spec... specs) {
        return iface.cast(stubWith(new Class<?>[]{iface}, specs));
    }

    // ==================== 消息记录 ====================

    /**
     * 一次命令执行里收到的全部消息（已渲染成纯文本）。
     *
     * <p>断言必须落在"玩家实际看到的那几行字"上：只断言"调用过 sendMessage"
     * 测不出"渲染成了 0"这类错误。</p>
     */
    static final class Inbox {
        private final List<String> lines = new ArrayList<>();
        private final List<String> permissionsAsked = new ArrayList<>();

        List<String> lines() {
            return Collections.unmodifiableList(lines);
        }

        /** {@code hasPermission} 被问过哪些权限字符串（用于证明"根本没查权限"）。 */
        List<String> permissionsAsked() {
            return Collections.unmodifiableList(permissionsAsked);
        }

        String joined() {
            return String.join("\n", lines);
        }

        int size() {
            return lines.size();
        }
    }

    private static Spec[] recording(Inbox inbox, boolean permissionAnswer) {
        return new Spec[]{
            on("sendMessage", args -> {
                if (args.length > 0 && args[0] instanceof Component c) {
                    inbox.lines.add(PlainTextComponentSerializer.plainText().serialize(c));
                }
                return null;
            }),
            on("hasPermission", args -> {
                if (args.length > 0) inbox.permissionsAsked.add(String.valueOf(args[0]));
                return permissionAnswer;
            }),
            on("getPermissionValue", args -> {
                if (args.length > 0) inbox.permissionsAsked.add(String.valueOf(args[0]));
                return permissionAnswer ? Tristate.TRUE : Tristate.FALSE;
            }),
        };
    }

    /** 普通来源（可能是第三方插件包装出来的自定义 source，<b>不是</b>控制台）。 */
    static CommandSource source(Inbox inbox, boolean permissionAnswer) {
        return (CommandSource) stubWith(new Class<?>[]{CommandSource.class},
            recording(inbox, permissionAnswer));
    }

    /**
     * 真控制台：同时实现 {@code ConsoleCommandSource}。
     *
     * <p>这是 {@code instanceof ConsoleCommandSource} 判据的<b>唯一正例</b>。
     * 权限回答刻意给 {@code true}：若 {@code hasPermission} 走的是"查权限"而不是"类型白名单"，
     * 控制台也会拿到 true，于是"非控制台必须被拒绝"那几条才能区分出差别——
     * 见 {@code NetworkCommandTest} 里用"控制台不装权限"验证放行的用例。</p>
     */
    static CommandSource console(Inbox inbox) {
        return (CommandSource) stubWith(new Class<?>[]{
            CommandSource.class, com.velocitypowered.api.proxy.ConsoleCommandSource.class},
            recording(inbox, false));
    }

    /**
     * 一个玩家的 source（{@code Player extends CommandSource}，但<b>不是</b>控制台）。
     *
     * <p>用途：证明"非控制台必须查权限"里 {@code Player} 这一支。
     * 陷阱在于 {@code Player} 与 {@code ConsoleCommandSource} 是<b>互斥</b>接口，
     * 所以这里的替身天然通不过控制台白名单。</p>
     */
    static Player player(Inbox inbox, boolean permissionAnswer) {
        return stub(Player.class, concat(recording(inbox, permissionAnswer),
            new Spec[]{on("getUsername", a -> "Notch")}));
    }

    private static Spec[] concat(Spec[] a, Spec[] b) {
        Spec[] out = new Spec[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 一次命令调用：source + 参数。 */
    static SimpleCommand.Invocation invocation(CommandSource source, String... args) {
        return stub(SimpleCommand.Invocation.class,
            on("source", a -> source),
            on("arguments", a -> args),
            on("alias", a -> "pscope"));
    }

    // ==================== 代理服务端 / 后端 ====================

    /** 只填了需要的那几个方法的 {@link ProxyServer}。 */
    static ProxyServer proxyServer(Collection<RegisteredServer> servers) {
        return stub(ProxyServer.class, on("getAllServers", a -> servers));
    }

    /** 一个已注册后端，{@code getServerInfo().getName()} 返回 {@code name}。 */
    static RegisteredServer registeredServer(String name, int port) {
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", port));
        return stub(RegisteredServer.class,
            on("getServerInfo", a -> info),
            on("getPlayersConnected", a -> Collections.emptyList()));
    }

    /**
     * 一条来自后端的连接，其 {@code getServerInfo().getName()} 为 {@code name}。
     *
     * <p>这是 {@code PerfScopeVelocity.onPluginMessage} 的来源绑定锚点。
     * {@code name == null} 用来模拟"取不到后端名"的降级路径。</p>
     */
    static ServerConnection serverConnection(String name, int port) {
        if (name == null) {
            return stub(ServerConnection.class, on("getServerInfo", a -> null));
        }
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved("127.0.0.1", port));
        return stub(ServerConnection.class, on("getServerInfo", a -> info));
    }

    /**
     * 一个不是后端的消息源（玩家）。精确复现"来自客户端"这个位置：
     * {@code Player} 同时是 {@code ChannelMessageSource}，但<b>不是</b> {@code ServerConnection}。
     */
    static com.velocitypowered.api.proxy.messages.ChannelMessageSource playerSource() {
        return stub(com.velocitypowered.api.proxy.messages.ChannelMessageSource.class);
    }

    /**
     * 消息的接收目标。{@code PluginMessageEvent} 的构造器要求它<b>非 null</b>
     * （{@code Preconditions.checkNotNull}），所以每次构造事件都得给一个。
     *
     * <p>被测代码从不读 {@code getTarget()}，所以这里不需要任何行为——只要是个非 null 的
     * {@code ChannelMessageSink} 实例即可。</p>
     */
    static com.velocitypowered.api.proxy.messages.ChannelMessageSink sink() {
        return stub(com.velocitypowered.api.proxy.messages.ChannelMessageSink.class);
    }

    // ==================== 插件实例 ====================

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * 造一个可用的插件实例。
     *
     * <p>{@code PerfScopeVelocity} 的构造器只赋三个字段，不碰任何静态初始化，
     * 所以这里不需要 {@code Unsafe.allocateInstance}——用真实构造器即可，
     * 也就顺带证明了"这个类可以脱离代理进程构造"。</p>
     */
    static PerfScopeVelocity plugin(ProxyServer server) {
        Logger logger = LoggerFactory.getLogger("pscope-velocity-test-" + SEQ.incrementAndGet());
        return new PerfScopeVelocity(server, logger, Path.of("target", "stub-data"));
    }
}
