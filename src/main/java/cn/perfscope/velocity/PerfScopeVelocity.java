package cn.perfscope.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * PerfScope 的 Velocity 代理侧。
 *
 * <p>职责只有两件：<b>收</b>各后端通过 plugin messaging 上报的摘要，和<b>把它显示出来</b>。
 * 它不采集任何指标——代理端没有 tick 循环、没有世界、没有实体，本来就没有可采的东西。</p>
 *
 * <h2>为什么接收侧要判断来源是 ServerConnection</h2>
 *
 * <p>通道是双向可用的：玩家能往代理发、代理能往玩家发、后端能往代理发。我们只关心
 * <b>后端</b>发来的摘要，所以必须 {@code instanceof ServerConnection} 过滤掉玩家发来的
 * 消息——否则任何客户端都能伪造一条"某后端 TPS 是 0"，把面板涂红。
 * 这是一个真实的攻击面，不是假想：plugin messaging 通道名是可枚举的。</p>
 *
 * <h2>为什么光判 ServerConnection 不够：来源绑定</h2>
 *
 * <p>{@code instanceof} 只回答"这条消息来自某个后端连接"，回答不了<b>来自哪一个</b>。
 * 而包里的 {@code server} 字段是后端自己填的，完全可伪造——于是 A 后端（或被植入插件的
 * 任一后端）可以发一条 {@code server="B"} 的包，把 B 的数据整个覆盖掉：
 * 把 B 染红（写 0 TPS），或者反过来把一台真正在卡的 B 伪装成 20 TPS 让告警永远不响。
 * 包里的数字再"看起来对"也没用，因为<b>身份</b>是错的。</p>
 *
 * <p>因此这里做<b>来源绑定</b>：{@code ServerConnection.getServerInfo().getName()} 就在手边
 * （那是 {@code velocity.toml} 里注册的名字，由代理自己给出，不可能被后端伪造），
 * 用它把"某个 server-id"钉在"某一条后端"上。见 {@link SourceBinder}。</p>
 *
 * <h2>这条链路的固有限制（复述，因为它会影响使用者对面板的解读）</h2>
 *
 * <p>后端只能借玩家连接发消息，所以<b>空服的后端不会上报</b>。本插件不可能改变这一点，
 * 只能把它如实显示成"无数据"而不是"健康"或"0"。见 {@link NetworkView.State}。</p>
 */
@Plugin(
    id = "perfscope",
    name = "PerfScope",
    version = "${project.version}",
    description = "PerfScope 的代理侧：接收各后端上报的性能摘要，一屏看全网",
    authors = {"sqkl520"},
    url = "https://github.com/sqkl520/Minecraft-server-plugin-PerfScope"
)
public final class PerfScopeVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    /** 通道标识，namespace/name 与后端逐字一致。 */
    static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create(
        SummaryPacket.CHANNEL_NAMESPACE, SummaryPacket.CHANNEL_NAME);

    /** 全网视图（收到包就更新它）。 */
    private final NetworkView view = new NetworkView();

    @Inject
    public PerfScopeVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 登记通道。不登记的话代理不会把这条通道的消息转发给我们，事件永远不触发——
        // 表现为"后端起劲发、代理一条也收不到"，且没有任何报错。
        server.getChannelRegistrar().register(CHANNEL);

        // 注册命令。Velocity 的命令必须显式注册，没有 plugin.yml 那种声明式入口。
        //
        // 只构造一次 meta。此前写了两次 metaBuilder 链，第一次的 .plugin(this) 挂在
        // 一个被丢弃的对象上（纯死代码），真正注册的那份反过来没有 .plugin(this)，
        // 于是命令归到一个匿名虚拟插件、执行器线程名也是别人的——排错时会误导。
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("pscope")
                .aliases("perfscope", "ps")
                .plugin(this)
                .build(),
            new NetworkCommand(this));

        logger.info("PerfScope (Velocity) 已启用，监听通道 {}/{}",
            SummaryPacket.CHANNEL_NAMESPACE, SummaryPacket.CHANNEL_NAME);
        logger.info("注意：后端为空服时 plugin messaging 无转发通道，该后端会显示为「无数据」而非「正常」。");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        server.getChannelRegistrar().unregister(CHANNEL);
    }

    /**
     * 接收后端上报。
     *
     * <p>三个必须做的检查，缺任何一个都会有具体的坏后果：</p>
     * <ol>
     *   <li><b>通道匹配</b>——不匹配的消息不归我们管。</li>
     *   <li><b>来源必须是 ServerConnection</b>——否则任何玩家都能伪造后端的读数。</li>
     *   <li><b>解析失败要静默丢弃</b>——一个坏包不能让代理崩，也不能影响其它后端的数据。</li>
     * </ol>
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // 安全边界：只接受来自后端的消息。玩家发来的一律丢弃。
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }
        // 既然认领了这条消息，就不该让它继续转发给玩家/其它后端。
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        SummaryPacket packet = SummaryPacket.parse(event.getData());
        if (packet == null) {
            // 版本不兼容 / 字段缺失 / 格式错误。
            //
            // 文档里承诺过"会记日志"，所以这里必须真的记——但网络输入不能刷屏：
            // 一台配错版本的后端每秒都在发，逐条记会把磁盘写满。
            // 因此按固定窗口内只记一条，并带上丢弃计数与**对方声明的版本**。
            noteDropped(SummaryPacket.peekVersion(event.getData()));
            return;
        }
        if (packet.serverId() == null || packet.serverId().isBlank()
            || "unknown".equals(packet.serverId())) {
            // 没有身份就无法归位——两个都叫 unknown 的后端会互相覆盖。
            // 同样限频：恶意后端可以每包都发空 id。
            noteBadId();
            return;
        }

        // 来源绑定：包里自称的身份必须能钉在这条连接对应的后端上。
        // 放在"身份是否为空"之后、view.onPacket 之前——前者是包内自洽性，这里才谈得上信任。
        String originName = serverNameOf(event);
        SourceBinder.Verdict bind = sourceBinder.bind(packet.serverId(), originName);
        if (bind == SourceBinder.Verdict.REJECT_SQUAT) {
            // server-id 已被**另一条**后端占着：这是最明确的覆盖攻击形状。
            // 直接丢，且不让它接触 view——一旦 put 进去，真相已经被覆盖了。
            noteSourceMismatch(packet.serverId(), originName, true);
            return;
        }
        // 用**收到时刻**调 onPacket，而不是让它自己再去取一次时间：
        // 时钟偏移样本定义为 sentAt - 收到时刻，两者必须来自同一个瞬间，
        // 否则采样本身的抖动会被当成时钟偏移学进去。
        view.onPacket(packet, System.currentTimeMillis());
        // 不一致但被接受：记一条限频告警（每对 id/后端只报一次），让服主知道
        // velocity.toml 与 network.server-id 对不上——这是幽灵行的唯一可诊断信号。
        if (bind == SourceBinder.Verdict.WARN_MISMATCH) {
            noteSourceMismatch(packet.serverId(), originName, false);
        }
    }

    /**
     * 取这条插件消息来自哪个后端。
     *
     * <p>{@code getServerInfo().getName()} 是 {@code velocity.toml} 的 {@code [servers]} 键，
     * 由代理自己维护——后端无法伪造它（伪造不了"我这条 TCP 是哪条"），这正是它可以当锚点的理由。
     * 取不到时返回 {@code null}：宁可放弃绑定判断，也不要用一个猜出来的名字去拒包。</p>
     */
    private static String serverNameOf(PluginMessageEvent event) {
        try {
            ServerConnection conn = (ServerConnection) event.getSource();
            return conn.getServerInfo() == null ? null : conn.getServerInfo().getName();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** 丢弃包的限频日志窗口。 */
    private static final long DROP_LOG_INTERVAL_MS = 60_000L;
    /** 0 = 还没记过（首次丢弃时的窗口长度按 0 秒算，不能拿 epoch 当起点）。 */
    private long lastDropLogAtMs;
    private int droppedSinceLog;
    /** 窗口内最近一次能读到的对方协议版本；-1 = 读不出来（不是 JSON / 没 v 字段）。 */
    private int lastSeenVersion = -1;

    /**
     * 记录一次"包不能用"并限频输出。
     *
     * <p>方法本身加锁：丢弃来自多个 Netty event-loop，不加锁时计数会丢增量，
     * 而"丢了多少条"正是判断问题规模唯一的数据。日志窗口本身已经把开销压到一分钟一次，
     * 同步的代价可以忽略。</p>
     *
     * @param peerVersion 对方包里声明的协议版本；{@code null} = 读不出来
     */
    private synchronized void noteDropped(Integer peerVersion) {
        droppedSinceLog++;
        if (peerVersion != null) {
            lastSeenVersion = peerVersion;
        }
        long now = System.currentTimeMillis();
        if (lastDropLogAtMs == 0L || now - lastDropLogAtMs >= DROP_LOG_INTERVAL_MS) {
            String peer = lastSeenVersion < 0 ? "无法识别" : String.valueOf(lastSeenVersion);
            // 首次没有"上一个窗口"可算，写 0 秒而不是拿 epoch 当起点（那会打出十几亿秒）。
            long windowSec = lastDropLogAtMs == 0L ? 0L : (now - lastDropLogAtMs) / 1000L;
            logger.warn("丢弃了 {} 条无法解析的跨服摘要（近 {} 秒）。对方协议版本 {}，本端 {}。"
                    + "若两边版本不一致，请把较旧的一侧升级到最新。",
                droppedSinceLog, windowSec, peer, SummaryPacket.PROTOCOL_VERSION);
            lastDropLogAtMs = now;
            droppedSinceLog = 0;
            lastSeenVersion = -1;
        }
    }

    private long lastBadIdLogAtMs;
    private int badIdSinceLog;

    /** 记录一次"没有 server-id"并限频输出。加锁理由同 {@link #noteDropped}。 */
    private synchronized void noteBadId() {
        badIdSinceLog++;
        long now = System.currentTimeMillis();
        if (lastBadIdLogAtMs == 0L || now - lastBadIdLogAtMs >= DROP_LOG_INTERVAL_MS) {
            long windowSec = lastBadIdLogAtMs == 0L ? 0L : (now - lastBadIdLogAtMs) / 1000L;
            logger.warn("丢弃了 {} 条没有 server-id 的跨服摘要（近 {} 秒）。"
                    + "请在各后端 config.yml 的 network.server-id 配置一个不同的值。",
                badIdSinceLog, windowSec);
            lastBadIdLogAtMs = now;
            badIdSinceLog = 0;
        }
    }

    /** 来源绑定器。持有"哪个 server-id 已经钉在哪条后端上"的记忆。 */
    private final SourceBinder sourceBinder = new SourceBinder();

    /** 已就该 (server-id → 后端名) 组合报过警的，避免每分钟重复。key 见 {@link #mismatchKey}。 */
    private final Map<String, Boolean> mismatchWarned = new HashMap<>();

    /**
     * 记录一次"包里自称的身份与来源后端对不上"。
     *
     * <p>分两种后果，日志措辞必须不同——它们该修的东西不一样：</p>
     *
     * <ul>
     *   <li><b>{@code rejected=true}</b>（抢占）：某个 server-id 已经被<b>另一条</b>后端用着，
     *       这条包被丢弃。这是明确的越权（无论恶意还是配错成同一个 id），要去查是哪个后端
     *       发了不属于它的名字。</li>
     *   <li><b>{@code rejected=false}</b>（不一致但接受）：这是<b>最常见也最无害</b>的情形——
     *       服主没把 {@code network.server-id} 设成 {@code velocity.toml} 里的名字。
     *       本插件<b>刻意不拒绝</b>它（见 README「来源绑定」一节的理由），但面板上会出现
     *       「<code>[servers] 里的名字</code> 无数据 + <code>server-id</code> 有数」两行幽灵。
     *       告警把这两个名字并排写出来，服主一眼就能对上。</li>
     * </ul>
     *
     * <p>两种都按 (id, 后端名) 去重：配置稳定时同一对组合只报一次，改回一致后自然不再出现。</p>
     */
    private synchronized void noteSourceMismatch(String claimedId, String originName, boolean rejected) {
        String key = mismatchKey(claimedId, originName, rejected);
        if (mismatchWarned.containsKey(key)) {
            return;
        }
        // 记忆表要有界：恶意后端可以不断换 id 把这张表撑大（与 NetworkView 的
        // MAX_TRACKED_SERVERS 同一个理由）。到这里时 server-id 已过长度闸门（≤64），
        // 但数量仍无上限，所以封顶后整体清空而不是无限增长——重报一次的成本远低于无界。
        if (mismatchWarned.size() >= 512) {
            mismatchWarned.clear();
        }
        mismatchWarned.put(key, Boolean.TRUE);
        if (rejected) {
            logger.warn("丢弃一条跨服摘要：它自称 server-id=\"{}\"，但该身份已被另一条后端占用，"
                    + "而这条消息来自后端 \"{}\"。同一个 server-id 只能由一个后端上报——"
                    + "请检查各后端的 network.server-id 是否配成了同一个值。",
                claimedId, originName);
        } else {
            logger.warn("跨服摘要的 server-id=\"{}\" 与它来自的后端 \"{}\" 不一致。"
                    + "数据已接受，但面板上可能出现两行（velocity.toml 的名字显示「无数据」、"
                    + "server-id 显示数据）。若想让它们合成一行，请把该后端的 network.server-id "
                    + "改得与 velocity.toml 里的名字逐字一致。",
                claimedId, originName);
        }
    }

    private static String mismatchKey(String claimedId, String originName, boolean rejected) {
        return (rejected ? "R:" : "W:") + originName + '\u0000' + claimedId;
    }

    /**
     * 来源绑定：把 {@code server-id} 钉在它实际来自的后端上。
     *
     * <h2>为什么不"强制一致"（这是本类最重要的一个决定）</h2>
     *
     * <p>README 建议 {@code server-id} 与 {@code velocity.toml} 里的后端名一致，但那是<b>建议</b>，
     * 而且现有部署里一定有人没照做——他们可能把 {@code server-id} 设成 {@code survival}
     * 而 {@code velocity.toml} 里叫 {@code survival-1}，也可能用 {@code server-id} 当
     * "对外展示的友好名"（{@code 生存一区}）而与内部注册名分开。</p>
     *
     * <p>如果<br>
     * &nbsp;&nbsp;(a) 强制要求两者逐字相等，<b>这些部署会在一夜之间全部隐身</b>——升级即故障，
     * 而且症状（面板全空）与"代理没装插件"一模一样，极难自查；<br>
     * &nbsp;&nbsp;(b) 只做"不一致就丢弃"，与 (a) 等价，只是措辞温和一点。</p>
     *
     * <p>所以本插件选<b>第三种</b>：<b>不要求名字相等，但要求"一个身份只归一条后端"</b>。
     * 具体规则：</p>
     *
     * <ol>
     *   <li>某 {@code server-id} <b>第一次</b>出现时，把它钉到当时那条后端上（{@code 绑定}）。</li>
     *   <li>之后同一 {@code server-id} 再从<b>同一条</b>后端来 → 正常（无论名字是否相等）。</li>
     *   <li>之后同一 {@code server-id} 从<b>另一条</b>后端来 → <b>丢弃</b>并告警。
     *       这才是真正要防的那件事：A 覆盖 B。它与"名字不一致"无关——
     *       没被占用的 id 根本走不到这一条。</li>
     * </ol>
     *
     * <p>这个判据同时满足两个目标：</p>
     * <ul>
     *   <li><b>不破坏现有部署</b>：只要每个 server-id 确实只由一台后端发（正常配置必然如此），
     *       名字对不对得上都不影响收数。</li>
     *   <li><b>堵住覆盖攻击</b>：A 想冒充 B，要么用 B 的 id（被 B 先占着 → 丢），
     *       要么用 A 自己的 id（那覆盖的是 A 自己，无害）。它拿不到"把 B 的数据改掉"的能力。</li>
     * </ul>
     *
     * <p>名字不一致仍会告警（{@link Verdict#WARN_MISMATCH}），因为那是幽灵行的唯一线索——
     * 但<b>只在确实不一致时</b>报，正常部署安安静静。</p>
     *
     * <p><b>纯逻辑、不碰 Velocity API</b>，所以能在单测里逐条验证这张表。</p>
     */
    static final class SourceBinder {

        /** 绑定判定的三种结果。 */
        enum Verdict {
            /** 身份与来源自洽，或第一次见到这个 id（已绑定）。 */
            OK,
            /** 身份与来源后端名不一致，但没被别的后端占用——接受并告警。 */
            WARN_MISMATCH,
            /** 这个 server-id 已被另一条后端占用——丢弃（覆盖攻击 / 重复配置）。 */
            REJECT_SQUAT
        }

        /** server-id → 已经用它上报过的后端名。 */
        private final Map<String, String> boundTo = new HashMap<>();

        /**
         * 判定一次上报。
         *
         * @param claimedId  包里自称的 server-id（已过非空 / 非保留字 / 长度闸门）
         * @param originName 这条消息实际来自的后端名；{@code null} = 取不到，放弃绑定判断
         * @return 判定结果
         */
        synchronized Verdict bind(String claimedId, String originName) {
            if (claimedId == null || originName == null) {
                // 取不到来源名时不能拒包（会把正常后端全丢掉），也不能绑定（绑了就无法再判抢占）。
                // 宁可放过这一条，也不要用猜出来的数据污染绑定表。
                return Verdict.OK;
            }
            String bound = boundTo.get(claimedId);
            if (bound == null) {
                // 这个 id 从没被任何后端用过 —— 第一眼看到谁就绑谁。有界：见 BINDINGS_MAX。
                if (boundTo.size() >= BINDINGS_MAX) {
                    // 与 mismatchWarned 同理：宁可整体清空（重新绑定会放过一次抢占窗口），
                    // 也不让一张网络输入驱动的表无限增长。
                    boundTo.clear();
                }
                boundTo.put(claimedId, originName);
                // 第一次见到这个 id：即便名字和 velocity.toml 不一致也只是"建议未遵守"，
                // 照常接受。但要告警——幽灵行就藏在这里。
                return claimedId.equals(originName) ? Verdict.OK : Verdict.WARN_MISMATCH;
            }
            if (bound.equals(originName)) {
                // 同一个 id、同一条后端 —— 正常。名字是否等于后端名不影响（见类注释）。
                return claimedId.equals(originName) ? Verdict.OK : Verdict.WARN_MISMATCH;
            }
            // 同一个 id、**不同的**后端 —— 这就是 A 覆盖 B 的形状。
            return Verdict.REJECT_SQUAT;
        }

        /** 绑定表上限（网络输入驱动，必须有界）。 */
        static final int BINDINGS_MAX = 512;

        /** 供测试与诊断：某个 id 当前绑在哪条后端上（未绑定返回 {@code null}）。 */
        synchronized String boundServerOf(String claimedId) {
            return boundTo.get(claimedId);
        }

        /** 清空绑定（{@code /pscope network reset} 时用，让换过后端的部署能重新绑定）。 */
        synchronized void clear() {
            boundTo.clear();
        }

        synchronized int size() {
            return boundTo.size();
        }
    }

    // ---- 给命令层用的访问器 ----

    public ProxyServer server() {
        return server;
    }

    public Logger logger() {
        return logger;
    }

    public NetworkView view() {
        return view;
    }

    /** 来源绑定表；{@code /pscope network reset} 与单测经由它清空 / 检查绑定。 */
    SourceBinder sourceBinder() {
        return sourceBinder;
    }

    Path dataDirectory() {
        return dataDirectory;
    }
}
