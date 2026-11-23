package cn.perfscope.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static cn.perfscope.velocity.VelocityStubs.console;
import static cn.perfscope.velocity.VelocityStubs.invocation;
import static cn.perfscope.velocity.VelocityStubs.player;
import static cn.perfscope.velocity.VelocityStubs.plugin;
import static cn.perfscope.velocity.VelocityStubs.proxyServer;
import static cn.perfscope.velocity.VelocityStubs.registeredServer;
import static cn.perfscope.velocity.VelocityStubs.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NetworkCommand} —— 代理侧<b>唯一的权限边界</b>（覆盖此前 0%）。
 *
 * <h2>为什么这个类必须单独有一组测试</h2>
 *
 * <p>它 172 行，做的全是"把已算好的东西拼成一行字"和"判谁能执行"。前者错了顶多难看，
 * 后者错了是<b>安全边界</b>。审计确认它是零覆盖，也就是说：把 {@code hasPermission}
 * 整段删掉、改成一个常量 {@code true}，此前<b>没有任何测试会红</b>。</p>
 *
 * <h2>权限判据的两条不对称要求</h2>
 *
 * <ol>
 *   <li><b>控制台必须放行</b>——它是唯一能拿到控制台面板的通道，不给就等于功能坏了；</li>
 *   <li><b>非控制台必须查 {@code perfscope.network}，且默认拒绝</b>。
 *       写成"任何非 Player 都放行"是 fail-open：Velocity 允许第三方插件构造自己的
 *       {@code CommandSource}，一个"命令方块 / 脚本引擎"类插件把玩家身份包装成自定义 source 时，
 *       那个玩家就绕过了权限。</li>
 * </ol>
 *
 * <p>所以本类的核心断言不是"某一种 source 被拒绝"，而是
 * <b>「source 的<b>类型</b>决定结果，不是'它是不是 Player'决定结果」</b>——
 * 见 {@link #nonConsoleCustomSourceIsCheckedNotTrusted()}。</p>
 */
class NetworkCommandTest {

    // ==================== 权限：控制台放行 ====================

    /**
     * 真控制台（{@code instanceof ConsoleCommandSource}）无条件放行。
     *
     * <p>替身刻意把 {@code hasPermission} 回答成 {@code false}：如果实现是"先查权限再判类型"，
     * 这条会红。这比"给控制台 true 然后断言 true"强——后者在两种实现下都绿。</p>
     */
    @Test
    @DisplayName("权限：控制台无条件放行（即便它回答没有权限）")
    void consoleIsAlwaysAllowed() {
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        assertTrue(cmd.hasPermission(invocation(console(inbox))),
            "控制台必须放行，否则服主根本拿不到面板");
    }

    // ==================== 权限：非控制台必须查权限 ====================

    /** 普通 source 有权限 → 放行，且确实问过 {@code perfscope.network}。 */
    @Test
    @DisplayName("权限：非控制台有权限时放行，且查的是 perfscope.network")
    void plainSourceWithPermissionIsAllowed() {
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        assertTrue(cmd.hasPermission(invocation(source(inbox, true))));
        assertTrue(inbox.permissionsAsked().contains("perfscope.network"),
            "必须查这个权限节点；问过别的名字等于权限没生效。实际问过：" + inbox.permissionsAsked());
    }

    /** 普通 source 没权限 → 拒绝（fail-closed 的默认方向）。 */
    @Test
    @DisplayName("权限：非控制台无权限时拒绝")
    void plainSourceWithoutPermissionIsDenied() {
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        assertFalse(cmd.hasPermission(invocation(source(inbox, false))));
    }

    /**
     * <b>核心用例</b>：一个<b>不是</b> {@code Player}、也<b>不是</b>控制台的第三方自定义 source，
     * 仍必须走权限检查。
     *
     * <p>这正是"任何非 Player 都放行"那种写法的漏洞形状。Velocity 的 {@code CommandSource}
     * 是公开接口，任何插件都能实现它——所以"类型不是 Player"绝不能作为放行理由。
     * 本用例用 {@code Proxy} 造了一个纯 {@code CommandSource}（不带 {@code Player}、
     * 不带 {@code ConsoleCommandSource}），它代表"命令方块 / 脚本引擎包装出来的玩家身份"。</p>
     *
     * <p><b>变异实测</b>：把 {@code hasPermission} 改成
     * {@code return !(src instanceof Player)} 或直接 {@code return true}，本用例变红。</p>
     */
    @Test
    @DisplayName("权限：非 Player 的自定义 source 仍必须查权限（fail-closed）")
    void nonConsoleCustomSourceIsCheckedNotTrusted() {
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        // 一个"什么都不是"的 source：不是 Player、不是 ConsoleCommandSource。
        CommandSource custom = source(inbox, false);
        assertFalse(custom instanceof com.velocitypowered.api.proxy.ConsoleCommandSource,
            "前提：这个替身不是控制台（否则本用例测的就不是自定义 source）");
        assertFalse(custom instanceof com.velocitypowered.api.proxy.Player,
            "前提：这个替身不是 Player —— 这正是「按类型白名单」与「按非 Player 放行」的分水岭");

        assertFalse(cmd.hasPermission(invocation(custom)),
            "自定义 source 无权限时必须拒绝。写成「任何非 Player 都放行」＝ fail-open："
                + "任何插件包装出的 source 都能绕过 perfscope.network。");
    }

    /** 玩家 source：没权限拒绝、有权限放行，两者都不能落进控制台分支。 */
    @Test
    @DisplayName("权限：玩家 source 按 perfscope.network 判定")
    void playerSourceIsGatedByPermissionNode() {
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        VelocityStubs.Inbox denied = new VelocityStubs.Inbox();
        assertFalse(cmd.hasPermission(invocation(player(denied, false))));
        assertTrue(denied.permissionsAsked().contains("perfscope.network"),
            "玩家必须真的被问过权限节点，实际问过：" + denied.permissionsAsked());

        VelocityStubs.Inbox allowed = new VelocityStubs.Inbox();
        assertTrue(cmd.hasPermission(invocation(player(allowed, true))));
    }

    /**
     * 未装权限插件时（{@code Tristate.UNDEFINED}）默认拒绝。
     *
     * <p>Velocity 的真实行为是 {@code ALWAYS_UNDEFINED}，{@code asBoolean() == false}。
     * 这是正确的保守默认，但部署者必须知道（README 的权限一节写明了）。
     * 本用例把"默认拒绝"钉住，防止有人为了"开箱即用"把它改成默认放行——
     * 那会让一台没装权限插件的代理对所有人开放。</p>
     */
    @Test
    @DisplayName("权限：未定义（未装权限插件）时默认拒绝")
    void undefinedPermissionIsDenied() {
        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));

        // channels 里没登记 hasPermission → 默认返回 false；这正是 UNDEFINED.asBoolean() 的值。
        CommandSource bare = VelocityStubs.stub(CommandSource.class);
        assertFalse(bare.hasPermission("perfscope.network"),
            "前提：未登记时 hasPermission 返回 false（模拟 ALWAYS_UNDEFINED）");
        assertFalse(cmd.hasPermission(invocation(bare)),
            "没装权限插件时默认拒绝才是对的——改成默认放行等于对所有人开放");
    }

    // ==================== reset 子命令 ====================

    /**
     * <b>变异杀手</b>：{@code /pscope network reset} 必须真的清空视图与绑定表。
     *
     * <p>审计里"reset 不 clear 直接 return"这个变异此前 SURVIVED。它之所以危险，
     * 是因为 {@code NetworkView.clear()} 的<b>唯一调用点</b>就是这个分支——
     * 不 clear 的话那个方法从此是死代码，而"换过后端想重新统计"这条路直接断掉。</p>
     *
     * <p>断言的是<b>可观测状态</b>（{@code trackedCount()} 与 {@code binder.size()}），
     * 不是"调用过 clear"——后者在"忘了 clear"的实现下当然也是 false，但若实现改成
     * "clear 了另一个对象"就测不出来。</p>
     */
    @Test
    @DisplayName("reset：真的清空视图与来源绑定表")
    void resetClearsViewAndBindings() {
        PerfScopeVelocity plugin = plugin(proxyServer(List.of()));
        NetworkCommand cmd = new NetworkCommand(plugin);

        // 先塞一条包，并让绑定表里有内容。
        plugin.view().onPacket(SummaryPacket.parse((
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"survival\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":3,\"tps1m\":20.0}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2200L);
        plugin.sourceBinder().bind("survival", "survival");
        assertEquals(1, plugin.view().trackedCount(), "前提：视图里有 1 台");
        assertEquals(1, plugin.sourceBinder().size(), "前提：绑定表里有 1 条");

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network", "reset"));

        assertEquals(0, plugin.view().trackedCount(),
            "reset 必须真的清空视图。写成「不 clear 直接 return」时 clear() 变成死代码，"
                + "换过后端就再也无法重新统计。");
        assertEquals(0, plugin.sourceBinder().size(),
            "绑定表也必须清：换过后端 / 改过 server-id 后，旧绑定会把新后端判成抢占而永久丢包。");
        assertEquals(1, inbox.size(), "reset 应当只回一条确认消息");
        assertTrue(inbox.joined().contains("已清空"), "确认消息要说明发生了什么：" + inbox.joined());
    }

    /** {@code RESET} 大小写不敏感（与 {@code network} 同款处理）。 */
    @Test
    @DisplayName("reset：大小写不敏感")
    void resetIsCaseInsensitive() {
        PerfScopeVelocity plugin = plugin(proxyServer(List.of()));
        NetworkCommand cmd = new NetworkCommand(plugin);
        plugin.view().onPacket(SummaryPacket.parse((
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":1}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2200L);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "NETWORK", "ReSeT"));
        assertEquals(0, plugin.view().trackedCount());
    }

    /**
     * 没有 {@code reset} 参数时<b>不</b>清空——否则每次看面板都会把历史抹掉。
     *
     * <p>这条与上一条成对：只断言"reset 会清空"的话，"所有调用都清空"这种实现也能通过。</p>
     */
    @Test
    @DisplayName("不带 reset 参数时不清空")
    void plainInvocationDoesNotClear() {
        PerfScopeVelocity plugin = plugin(proxyServer(List.of()));
        NetworkCommand cmd = new NetworkCommand(plugin);
        plugin.view().onPacket(SummaryPacket.parse((
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":1}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2200L);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        assertEquals(1, plugin.view().trackedCount(),
            "只看面板不能把已收到的数据清掉");
        assertTrue(inbox.size() >= 2, "应当输出汇总行 + 表头（可能还有每行）：" + inbox.lines());
    }

    // ==================== 参数校验 ====================

    /**
     * 子命令拼错时只回一句用法，不做任何渲染。
     *
     * <p>不校验的话 {@code /pscope network rest} 会被当成"看面板"，玩家以为 reset 生效了。</p>
     */
    @Test
    @DisplayName("未知子命令：只回用法提示")
    void unknownSubcommandPrintsUsageOnly() {
        PerfScopeVelocity plugin = plugin(proxyServer(List.of()));
        NetworkCommand cmd = new NetworkCommand(plugin);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "netwrok"));

        assertEquals(1, inbox.size(), "只应回一条用法提示，不能顺手把面板也渲染出来");
        assertTrue(inbox.joined().contains("用法"), "提示要说明正确写法：" + inbox.joined());
        assertFalse(inbox.joined().contains("TPS(1m)"), "没有渲染表头，说明真的早退了");
    }

    // ==================== 渲染：期望后端列表 ====================

    /**
     * {@code velocity.toml} 里注册过的后端，即便一包都没收到，也必须出现在面板上。
     *
     * <p>这是"少了哪一台"唯一能被看见的途径。此前面板只显示收到过包的，
     * 于是"某台后端压根没配好"这件事在面板上<b>彻底隐身</b>——恰恰是最需要看出来的情况。</p>
     */
    @Test
    @DisplayName("渲染：未上报过的后端也会列出来（显示「无数据」）")
    void expectedServersAppearEvenWithoutData() {
        List<RegisteredServer> servers = new ArrayList<>();
        servers.add(registeredServer("survival", 25566));
        servers.add(registeredServer("creative", 25567));
        PerfScopeVelocity plugin = plugin(proxyServer(servers));
        NetworkCommand cmd = new NetworkCommand(plugin);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        String all = inbox.joined();
        assertTrue(all.contains("survival"), "注册过但没上报的后端必须列出来：" + all);
        assertTrue(all.contains("creative"));
        assertTrue(all.contains("无数据"), "它们的正确文案是「无数据」，不是「正常」或「0」");
        assertFalse(all.contains("0.00"), "没数据绝不能渲染成 0.00：" + all);
    }

    /**
     * 名字是空白字符串时跳过，不渲染出空行。
     *
     * <p>Velocity 的 {@code RegisteredServer} 由第三方插件提供，名字不保证非空。
     * 渲染一个空名字会让面板出现一行"什么都没写"，看起来像插件坏了。</p>
     */
    @Test
    @DisplayName("渲染：跳过空白后端名，不渲染空行")
    void blankBackendNamesAreSkipped() {
        List<RegisteredServer> servers = new ArrayList<>();
        servers.add(registeredServer("  ", 25566));   // 纯空白
        servers.add(registeredServer("survival", 25567));

        PerfScopeVelocity plugin = plugin(proxyServer(servers));
        NetworkCommand cmd = new NetworkCommand(plugin);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        String all = inbox.joined();
        assertTrue(all.contains("survival"), "正常名字必须还在：" + all);
        for (String line : inbox.lines()) {
            assertFalse(line.isBlank(), "不应渲染出空行");
        }
    }

    /**
     * 某一台后端 {@code getServerInfo()} 返回 {@code null} 时，<b>只有那一台</b>被跳过，
     * 其余后端照常列出。
     *
     * <p>这条曾经是「记录现状」的哨兵，钉住的是一个真缺口：{@code expectedServers()} 把整段
     * 循环包在同一个 {@code try/catch(Exception)} 里，一台坏后端抛出的 NPE 会让整个方法走
     * catch 分支返回 {@code emptyList()}，于是<b>本来正常的那几台也一起从面板上消失</b>。
     * 症状是"面板上所有后端都显示无数据"，与"插件没装好"逐字相同，而真实原因只是其中一台
     * 第三方插件的注册信息不完整。</p>
     *
     * <p>缺口已修：降级粒度改成单台。本用例随之从"钉住整表降级"翻转成"钉住逐台跳过"——
     * 若有人把 try 又挪回循环外面，正常那台会一起消失，这条会立刻红。</p>
     */
    @Test
    @DisplayName("一台后端 getServerInfo()=null 只跳过它自己，不牵连其余后端")
    void nullServerInfoSkipsOnlyThatServer() {
        List<RegisteredServer> servers = new ArrayList<>();
        servers.add(VelocityStubs.stub(RegisteredServer.class,
            VelocityStubs.on("getServerInfo", a -> null)));
        servers.add(registeredServer("survival", 25567));
        servers.add(registeredServer("creative", 25568));

        PerfScopeVelocity plugin = plugin(proxyServer(servers));
        NetworkCommand cmd = new NetworkCommand(plugin);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        String all = inbox.joined();
        assertTrue(all.contains("survival"),
            "坏的那台不该把正常的那台一起抹掉：" + all);
        assertTrue(all.contains("creative"),
            "坏的那台不该把正常的那台一起抹掉：" + all);
        assertTrue(all.contains("无数据"),
            "跳过一台之后，其余没上报的后端仍必须显示「无数据」：" + all);
        assertFalse(all.contains("null "),
            "坏后端的名字绝不能渲染成字面量 null：" + all);
        assertTrue(all.contains("后端 "),
            "汇总行仍应渲染出来：" + all);
    }

    /**
     * {@code getAllServers()} 抛异常时降级成"只显示收到过包的"，命令不能崩。
     *
     * <p>命令层崩掉在 Velocity 上是"玩家执行命令没有任何反应"，最难自查的一类。
     * 降级是可接受的（少看到"哪台没上报"），崩溃不是。</p>
     */
    @Test
    @DisplayName("渲染：取后端列表抛异常时降级，不崩")
    void brokenServerListDegradesGracefully() {
        ProxyServer broken = VelocityStubs.stub(ProxyServer.class,
            VelocityStubs.on("getAllServers", a -> {
                throw new IllegalStateException("simulated: velocity.toml 读不出来");
            }));
        PerfScopeVelocity plugin = plugin(broken);
        NetworkCommand cmd = new NetworkCommand(plugin);

        // 先塞一条，这样"只显示收到过包的"这条降级路径有东西可显示。
        plugin.view().onPacket(SummaryPacket.parse((
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"survival\",\"sentAt\":2200,"
                + "\"collectedAt\":2100,\"alive\":true,\"players\":3,\"tps1m\":20.0}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2200L);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        assertTrue(inbox.size() >= 2, "降级后仍应渲染出收到过包的：" + inbox.lines());
        assertTrue(inbox.joined().contains("survival"));
    }

    // ==================== 渲染：没有任何后端正常时的排查提示 ====================

    /**
     * 一台都不正常时补一段三步排查提示。
     *
     * <p>它挂在"没有一个后端正常"上而不是"一行都没有"上——后者在真实代理上几乎不可达
     * （注册了后端就必然有行，显示「无数据」），那段最需要的提示会一次也显示不出来。</p>
     */
    @Test
    @DisplayName("渲染：零个后端正常时给出排查提示（不是只在空列表时给）")
    void troubleshootingHintIsShownWhenNothingIsOk() {
        List<RegisteredServer> servers = List.of(registeredServer("survival", 25566));
        PerfScopeVelocity plugin = plugin(proxyServer(servers));
        NetworkCommand cmd = new NetworkCommand(plugin);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        String all = inbox.joined();
        assertTrue(all.contains("没有任何后端处于"), "一行数据都没有时必须给排查提示：" + all);
        assertTrue(all.contains("network.enabled"), "提示要指向后端配置开关");
        assertTrue(all.contains("空服"), "要点明空服是预期行为，不是故障");
    }

    /**
     * 至少一台正常时<b>不</b>显示排查提示（否则一天到晚刷一段无用的长文）。
     *
     * <p>C 类断言：只测"有提示"的话，"永远显示提示"的实现也能通过。</p>
     */
    @Test
    @DisplayName("渲染：有后端正常时不显示排查提示")
    void noTroubleshootingHintWhenOneIsOk() {
        List<RegisteredServer> servers = List.of(registeredServer("survival", 25566));
        PerfScopeVelocity plugin = plugin(proxyServer(servers));
        NetworkCommand cmd = new NetworkCommand(plugin);

        // 时间戳用未来一点的值，确保 age 很小 → OK。
        long now = System.currentTimeMillis();
        plugin.view().onPacket(SummaryPacket.parse(
            ("{\"v\":1,\"kind\":\"summary\",\"server\":\"survival\",\"sentAt\":" + now
                + ",\"collectedAt\":" + now + ",\"alive\":true,\"players\":3,\"tps1m\":20.0}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), now);

        VelocityStubs.Inbox inbox = new VelocityStubs.Inbox();
        cmd.execute(invocation(source(inbox, true), "network"));

        assertFalse(inbox.joined().contains("没有任何后端处于"),
            "有正常后端时不该再刷排查提示：" + inbox.joined());
    }

    // ==================== 渲染：颜色 ====================

    /**
     * 状态格的颜色必须与语义级别一致，且 UNKNOWN 是灰而不是绿。
     *
     * <p>这是命令层唯一真正"做"的判断（{@code colorOf}）。绿色会被读成"健康"，
     * 而未知恰恰是不该下结论——把它渲染成绿色是这个面板最坏的一种谎言。</p>
     *
     * <p>实现细节：{@code renderRow} 把每格颜色写在 {@code Component} 上，
     * 但纯文本序列化会丢掉颜色。所以这里直接反射调私有的 {@code colorOf}，
     * 逐级断言映射表。它不碰 Velocity 运行时，是纯映射。</p>
     */
    @Test
    @DisplayName("渲染：Level→颜色的映射，UNKNOWN 必须是灰（不能是绿）")
    void levelToColorMapping() throws Exception {
        for (NetworkRenderer.Level level : NetworkRenderer.Level.values()) {
            var m = NetworkCommand.class.getDeclaredMethod("colorOf", NetworkRenderer.Level.class);
            m.setAccessible(true);
            var color = m.invoke(null, level);
            assertNotNull(color, "每个级别都必须有颜色，null 会渲染成默认色");
            switch (level) {
                case OK -> assertEquals(net.kyori.adventure.text.format.NamedTextColor.GREEN, color);
                case WARN -> assertEquals(net.kyori.adventure.text.format.NamedTextColor.YELLOW, color);
                case CRITICAL -> assertEquals(net.kyori.adventure.text.format.NamedTextColor.RED, color);
                case UNKNOWN -> assertEquals(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY, color,
                    "未知必须是灰色：绿色会被读成健康，而未知恰恰是不该下结论");
            }
        }
    }

    // ==================== 补全 ====================

    /** Tab 补全只给 {@code network}。 */
    @Test
    @DisplayName("补全：只建议 network")
    void suggestsOnlyNetwork() {
        NetworkCommand cmd = new NetworkCommand(plugin(proxyServer(List.of())));
        assertEquals(List.of("network"), cmd.suggest(invocation(source(new VelocityStubs.Inbox(), true))));
    }
}
