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
        server.getCommandManager().metaBuilder("pscope")
            .aliases("perfscope", "ps")
            .plugin(this)
            .build();
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("pscope").aliases("perfscope", "ps").build(),
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
            // 版本不兼容 / 字段缺失 / 格式错误。静默丢弃：这是网络输入，
            // 不值得为一个坏包刷日志，但代理必须活着。
            return;
        }
        if (packet.serverId() == null || packet.serverId().isBlank()
            || "unknown".equals(packet.serverId())) {
            // 没有身份就无法归位——两个都叫 unknown 的后端会互相覆盖。
            logger.warn("收到一条没有 server-id 的跨服摘要，已丢弃。"
                + "请在该后端配置 network.server-id（多后端必须各不相同）。");
            return;
        }
        view.onPacket(packet);
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
