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
        view.onPacket(packet);
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

    Path dataDirectory() {
        return dataDirectory;
    }
}
