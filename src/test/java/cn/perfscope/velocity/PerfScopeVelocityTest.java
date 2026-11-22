package cn.perfscope.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.perfscope.velocity.VelocityStubs.proxyServer;
import static cn.perfscope.velocity.VelocityStubs.serverConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PerfScopeVelocity} 的事件入口 —— 代理侧<b>唯一的信任边界</b>（覆盖此前 0%）。
 *
 * <h2>为什么这个类必须被测</h2>
 *
 * <p>它是整条链路上唯一接触"外部输入"的地方：{@code PluginMessageEvent} 的数据来自网络，
 * 而通道名 {@code perfscope:summary} 是<b>可枚举的</b>——任何客户端都能往它发东西。
 * 这里少一道检查，后果不是"显示难看"，而是"任何人都能把面板涂成任意颜色"：</p>
 *
 * <ul>
 *   <li>不判 {@code ServerConnection} → 玩家自己发一条 {@code tps1m=0} 的包，
 *       面板上那台后端立刻变红，或反过来把一台真在卡的服务器伪装成 20 TPS 让告警永不响。</li>
 *   <li>不判通道 → 别的插件的消息被我们当摘要解析，垃圾进垃圾出。</li>
 *   <li>不认领（不 {@code setResult(handled)}）→ 同一条消息继续转发给玩家和其它后端，
 *       等于把后端的内部指标泄漏给所有客户端，同时放大流量。</li>
 *   <li>解析失败不吞 → 一个坏包抛异常，Velocity 会记一条错误并中断这次事件分发。</li>
 * </ul>
 *
 * <h2>怎么测</h2>
 *
 * <p>全部依赖都是接口，用 {@link VelocityStubs} 的动态代理造；
 * {@code PluginMessageEvent} 反而是个<b>普通类</b>（有公开构造器），可以直接 new——
 * 这让"来源是玩家 vs 来源是后端"这两种场景能真实地构造出来，而不必绕反射。</p>
 */
class PerfScopeVelocityTest {

    // ==================== 通道闸门 ====================

    /**
     * 别的通道的消息一律不处理：不解析、不认领、不写视图。
     *
     * <p>注意"不认领"也是断言的一部分：若这里就 {@code setResult(handled)}，
     * 我们会把<b>别的插件</b>的消息吞掉——它们从此收不到自己的数据了。
     * 那是比不处理更隐蔽的破坏。</p>
     */
    @Test
    @DisplayName("通道闸门：别的通道既不解析也不认领")
    void otherChannelsAreIgnoredEntirely() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
        ChannelIdentifier other = MinecraftChannelIdentifier.create("otherplugin", "data");

        PluginMessageEvent event = new PluginMessageEvent(
            serverConnection("survival", 25566), VelocityStubs.sink(), other, validPacket("survival"));

        plugin.onPluginMessage(event);

        assertEquals(0, plugin.view().trackedCount(), "别的通道的数据不归我们");
        assertTrue(event.getResult().isAllowed(),
            "不认领：这条消息必须继续转发给它的真正接收方，不能被我们吞掉");
    }

    // ==================== 安全边界：来源必须是后端 ====================

    /**
     * <b>最重要的用例</b>：玩家发来的消息（来源不是 {@code ServerConnection}）必须丢弃。
     *
     * <p>这是整个代理侧唯一的安全边界。通道名可枚举，客户端可以往 {@code perfscope:summary}
     * 发任意字节；若这里判错方向，任何客户端都能覆盖任意后端的读数。</p>
     *
     * <p>{@code Player} 在 Velocity 里同时是 {@code ChannelMessageSource}（能发插件消息）
     * 但<b>不是</b> {@code ServerConnection}——这个替身精确复现了那个位置。</p>
     */
    @Test
    @DisplayName("安全边界：玩家伪造的包被丢弃，且不认领")
    void playerSourcedMessagesAreRejected() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        Player attacker = VelocityStubs.player(inbox, false);

        assertFalse(attacker instanceof ServerConnection,
            "前提：Player 不是 ServerConnection —— 这正是判据的分界线");

        PluginMessageEvent event = new PluginMessageEvent(
            attacker, VelocityStubs.sink(), PerfScopeVelocity.CHANNEL, validPacket("survival"));

        plugin.onPluginMessage(event);

        assertEquals(0, plugin.view().trackedCount(),
            "玩家发来的包绝不能进视图——否则任何人都能把面板涂成任意颜色");
        assertTrue(event.getResult().isAllowed(),
            "丢弃即可，不必认领：这条消息本来就不该被我们拦下");
    }

    /** 来源是后端时正常收下并认领。正向对照。 */
    @Test
    @DisplayName("正向：后端来的合法包被收下并认领（handled）")
    void backendSourcedMessagesAreAccepted() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
        PluginMessageEvent event = new PluginMessageEvent(
            serverConnection("survival", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            validPacket("survival"));

        plugin.onPluginMessage(event);

        assertEquals(1, plugin.view().trackedCount(), "后端来的合法包必须被收下");
        assertFalse(event.getResult().isAllowed(),
            "认领之后这条消息不该继续转发（旧签名 isAllowed() 对 handled 返回 false）");
    }

    /**
     * 认领发生在本端之后：即使包<b>解析失败</b>也要认领。
     *
     * <p>理由：这条消息确实是我们通道上的，转发给玩家或其它后端没有意义，
     * 只会把一条垃圾放大 N 份。若把 {@code setResult} 挪到"解析成功之后"，
     * 一台配错版本的后端每秒产生的坏包就会持续转发——那是可观测的流量放大。</p>
     */
    @Test
    @DisplayName("认领时机：解析失败的包也要认领（不放大垃圾流量）")
    void malformedPayloadIsStillClaimed() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
        PluginMessageEvent event = new PluginMessageEvent(
            serverConnection("survival", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            "not json at all".getBytes(StandardCharsets.UTF_8));

        plugin.onPluginMessage(event);

        assertEquals(0, plugin.view().trackedCount());
        assertFalse(event.getResult().isAllowed(),
            "解析失败也要认领：否则坏包会被转发给玩家与其它后端，流量被放大");
    }

    // ==================== 解析闸门 ====================

    /**
     * 版本不兼容 / 缺字段 / 格式错的包：静默丢弃，不抛异常，不污染视图。
     *
     * <p>"静默"是刻意的——网络输入不能刷屏。日志由 {@code noteDropped} 限频输出，
     * 本类不直接断言日志（那属于实现细节），只断言"没进视图、没崩"。</p>
     */
    @Test
    @DisplayName("解析闸门：坏包/高版本包被丢弃且不崩")
    void badPayloadsAreDroppedWithoutThrowing() {
        String[] bad = {
            "",
            "not json",
            "[1,2,3]",
            "{\"v\":2,\"kind\":\"summary\",\"server\":\"x\"}",          // 未来版本
            "{\"v\":0,\"kind\":\"summary\",\"server\":\"x\"}",          // 非法版本
            "{\"v\":1,\"kind\":\"query\",\"server\":\"x\"}",            // 别的 kind
            "{\"v\":1,\"server\":\"x\"}",                                // 没有 kind
        };
        for (String payload : bad) {
            PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
            PluginMessageEvent event = new PluginMessageEvent(
                serverConnection("survival", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
                payload.getBytes(StandardCharsets.UTF_8));

            plugin.onPluginMessage(event);   // 不许抛

            assertEquals(0, plugin.view().trackedCount(),
                "坏包不该进视图：" + payload);
        }
    }

    /**
     * 没有 server-id / 空 / 保留字 {@code unknown} 的包：丢弃。
     *
     * <p>两个都叫 {@code unknown} 的后端会在 {@code latest.put} 上互相覆盖，
     * 面板上只剩 1 行——而"少了哪台"正是这个面板要回答的问题。
     * 所以没有身份就不收，与后端侧 {@code resolveServerId} 的拒发判据对称。</p>
     */
    @Test
    @DisplayName("身份闸门：缺失 / 空白 / unknown 的 server-id 一律丢弃")
    void packetsWithoutUsableIdentityAreDropped() {
        String[] noId = {
            // 完全没有 server 字段 → 解析器给默认值 "unknown"
            "{\"v\":1,\"kind\":\"summary\",\"sentAt\":2200,\"collectedAt\":2100,"
                + "\"alive\":true,\"players\":1}",
            // 空串
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":1}",
            // 纯空白
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"   \",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":1}",
            // 显式写保留字
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"unknown\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":1}",
        };
        for (String payload : noId) {
            PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));
            PluginMessageEvent event = new PluginMessageEvent(
                serverConnection("survival", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
                payload.getBytes(StandardCharsets.UTF_8));

            plugin.onPluginMessage(event);
            assertEquals(0, plugin.view().trackedCount(),
                "没有可用身份就不收（多个 unknown 会互相覆盖）：" + payload);
        }
    }

    // ==================== 来源绑定（另一 Agent 新加的安全层） ====================

    /**
     * 同一个 server-id 从<b>另一条</b>后端再来时被拒绝：这是覆盖攻击的形状。
     *
     * <p>包里的 {@code server} 字段是后端自己填的，完全可伪造。{@code instanceof ServerConnection}
     * 只能回答"来自某个后端"，回答不了"来自哪一个"。所以来源绑定把 id 钉在
     * {@code velocity.toml} 给的名字上——那个名字由代理自己维护，后端伪造不了。</p>
     *
     * <p>本用例走的是<b>完整的 {@code onPluginMessage} 路径</b>（不是直接调
     * {@code SourceBinder}）：从而连带证明"拒绝发生在 {@code view.onPacket} 之前"——
     * 一旦 put 进去，真相就已经被覆盖了，事后再拒也没用。</p>
     */
    @Test
    @DisplayName("来源绑定：另一条后端抢占已绑定的 id 时整包丢弃（不覆盖真相）")
    void squattingAnotherBackendsIdIsRejectedBeforeTouchingTheView() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));

        // 真正的 survival 先上报，把自己钉在 "survival" 上。
        plugin.onPluginMessage(new PluginMessageEvent(
            serverConnection("survival", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            packet("survival", 20.0, 3)));
        assertEquals(1, plugin.view().trackedCount());

        // 另一条后端（creative）冒用 "survival" 的身份，试图把它的数据改掉。
        plugin.onPluginMessage(new PluginMessageEvent(
            serverConnection("creative", 25567), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            packet("survival", 0.0, 999)));

        var nodes = plugin.view().snapshot(System.currentTimeMillis() + 1000, 10, List.of());
        assertEquals(1, nodes.size(), "冒充者不该新增一行");
        assertEquals(20.0, nodes.get(0).packet().tps1m(), 1e-9,
            "真正的 survival 的数据不能被覆盖——这正是来源绑定要防的那件事");
        assertEquals(3, nodes.get(0).packet().playerCount());
    }

    /**
     * 名字不一致但没被占用时<b>接受</b>并允许绑定（不破坏现有部署）。
     *
     * <p>现有部署里一定有人把 {@code network.server-id} 设成与 {@code velocity.toml}
     * 不同的值（比如对外展示名 {@code 生存一区}）。若强制两者相等，这些部署升级后
     * 会<b>一夜之间全部隐身</b>——症状与"代理没装插件"一样，极难自查。
     * 所以规则是"一个身份只归一条后端"，不是"名字必须相等"。</p>
     */
    @Test
    @DisplayName("来源绑定：id 与后端名不一致但未被占用时照常接受")
    void mismatchedButUnoccupiedIdIsAccepted() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));

        plugin.onPluginMessage(new PluginMessageEvent(
            serverConnection("survival-1", 25566), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            packet("生存一区", 19.5, 7)));

        assertEquals(1, plugin.view().trackedCount(),
            "名字对不上不该拒包——那会让所有没按建议命名的部署一夜之间全部隐身");
        assertEquals("survival-1", plugin.sourceBinder().boundServerOf("生存一区"),
            "第一次见到这个 id 就把它钉在当时那条后端上");
    }

    /** 取不到后端名时放弃绑定判断，但照常收数（不能因为取不到名字就把正常后端全丢掉）。 */
    @Test
    @DisplayName("来源绑定：取不到后端名时不拒包、也不写入绑定表")
    void unknownOriginSkipsBindingButStillAccepts() {
        PerfScopeVelocity plugin = VelocityStubs.plugin(proxyServer(List.of()));

        plugin.onPluginMessage(new PluginMessageEvent(
            serverConnection(null, 0), VelocityStubs.sink(), PerfScopeVelocity.CHANNEL,
            packet("survival", 20.0, 3)));

        assertEquals(1, plugin.view().trackedCount(), "取不到来源名时不能拒包");
        assertNull(plugin.sourceBinder().boundServerOf("survival"),
            "更不能拿一个猜出来的名字写进绑定表——那会让真正的抢占检测失效");
    }

    // ==================== 生命周期 ====================

    /**
     * {@code onProxyInitialize} 登记通道并注册命令；{@code onProxyShutdown} 反登记。
     *
     * <p>不登记通道的症状是"后端起劲发、代理一条也收不到，且没有任何报错"——
     * 事件永远不会触发。这是最难自查的一类，所以必须有一条测试看着它。</p>
     *
     * <p>两个方法只依赖 {@code ProxyServer} 上的三个 getter，用动态代理就能驱动。
     * 难点只有一个：生产代码的链是 {@code metaBuilder(...).aliases(...).plugin(this).build()}，
     * 链式方法必须返回<b>同一个 builder 替身</b>，否则下一条链会 NPE。这里用一个
     * 自引用的 handler（{@code aliases} / {@code plugin} 都返回 this）解决。</p>
     */
    @Test
    @DisplayName("生命周期：启用时登记通道 + 注册命令，关闭时反登记")
    void registersChannelAndCommandOnEnableAndUnregistersOnShutdown() {
        List<String> registered = new java.util.ArrayList<>();
        List<String> unregistered = new java.util.ArrayList<>();
        List<Object> registeredCommands = new java.util.ArrayList<>();

        // ⚠️ varargs 记账：register(ChannelIdentifier...) 只有一个<b>数组</b>形参，
        // 所以 handler 收到的 args 是 Object[]{ChannelIdentifier[]}，而不是逐个通道。
        // 直接 String.valueOf(args[0]) 会得到 "[Lcom.velocitypowered...;" 这种垃圾。
        // 这里展开一层再记。
        var registrar = VelocityStubs.stub(
            com.velocitypowered.api.proxy.messages.ChannelRegistrar.class,
            VelocityStubs.on("register", a -> {
                flattenInto(a, registered);
                return null;
            }),
            VelocityStubs.on("unregister", a -> {
                flattenInto(a, unregistered);
                return null;
            }));

        // 自引用的 builder：链上每个方法都返回它自己，build() 才产出一个 meta。
        // 用数组装箱是为了在 handler 里引用"稍后才赋值的那个对象"。
        final Object[] builderRef = new Object[1];
        var builder = VelocityStubs.stub(
            com.velocitypowered.api.command.CommandMeta.Builder.class,
            VelocityStubs.on("aliases", a -> builderRef[0]),
            VelocityStubs.on("plugin", a -> builderRef[0]),
            VelocityStubs.on("hint", a -> builderRef[0]),
            VelocityStubs.on("build", a -> VelocityStubs.stub(
                com.velocitypowered.api.command.CommandMeta.class)));
        builderRef[0] = builder;

        var commandManager = VelocityStubs.stub(
            com.velocitypowered.api.command.CommandManager.class,
            VelocityStubs.on("metaBuilder", a -> builder),
            VelocityStubs.on("register", a -> {
                registeredCommands.add(a.length > 1 ? a[1] : a[0]);
                return null;
            }));

        ProxyServer server = VelocityStubs.stub(ProxyServer.class,
            VelocityStubs.on("getChannelRegistrar", a -> registrar),
            VelocityStubs.on("getCommandManager", a -> commandManager),
            VelocityStubs.on("getAllServers", a -> List.of()));

        PerfScopeVelocity plugin = VelocityStubs.plugin(server);

        plugin.onProxyInitialize(null);

        assertEquals(1, registered.size(), "启用时必须登记通道，否则事件永远不触发");
        assertTrue(registered.get(0).contains("perfscope"),
            "登记的必须是 perfscope 的通道：" + registered);
        assertEquals(1, registeredCommands.size(), "启用时必须注册 /pscope 命令");
        assertTrue(registeredCommands.get(0) instanceof NetworkCommand,
            "注册的必须是 NetworkCommand（而不是别的执行器）：" + registeredCommands.get(0));

        plugin.onProxyShutdown(null);
        assertEquals(1, unregistered.size(), "关闭时必须反登记，否则重载会留下重复注册");
        assertTrue(unregistered.get(0).contains("perfscope"));
    }

    // ==================== 载荷工具 ====================

    /**
     * 把"一个 varargs 数组"摊平成一个通道列表。
     *
     * <p>{@code register(ChannelIdentifier...)} 的实参在反射里是
     * {@code Object[]{ChannelIdentifier[]}}：外层是形参数组、内层是 varargs 数组。
     * 不摊平的话拿到的是一句数组的 {@code toString()}，断言会对着垃圾字符串做。
     * 但对 {@code getId()} 取真值又需要真的调接口——这里两者都做：能取 id 就取，否则 toString。</p>
     */
    private static void flattenInto(Object[] args, List<String> out) {
        for (Object arg : args) {
            if (arg instanceof Object[] inner) {
                for (Object o : inner) {
                    out.add(describe(o));
                }
            } else {
                out.add(describe(arg));
            }
        }
    }

    private static String describe(Object o) {
        if (o instanceof ChannelIdentifier id) {
            try {
                return id.getId();
            } catch (Exception ignored) {
                // 取不到 id 就退回 toString（替身可能没实现 getId）
            }
        }
        return String.valueOf(o);
    }

    /** 一条合法的摘要载荷。 */
    private static byte[] validPacket(String serverId) {
        return packet(serverId, 20.0, 3);
    }

    private static byte[] packet(String serverId, double tps, int players) {
        long now = 1_700_000_000_000L;
        return ("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + serverId
            + "\",\"sentAt\":" + (now + 500) + ",\"collectedAt\":" + now
            + ",\"alive\":true,\"players\":" + players + ",\"tps1m\":" + tps
            + ",\"tps5m\":" + tps + ",\"tps15m\":" + tps + "}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
