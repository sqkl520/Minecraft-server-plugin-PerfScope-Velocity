package cn.perfscope.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /pscope network} —— 一屏看全网。
 *
 * <p>命令层只做三件事：拿配置的期望后端列表、调 {@link NetworkView} 算视图、
 * 把 {@link NetworkRenderer} 产出的格子映射成颜色。所有判定逻辑都在那两个纯逻辑类里，
 * 因为那些才是要反复测的部分。</p>
 */
public final class NetworkCommand implements SimpleCommand {

    /** 默认上报间隔（秒）。与后端 {@code network.push-interval-seconds} 的默认值一致。 */
    static final int DEFAULT_PUSH_INTERVAL_SEC = 10;

    private final PerfScopeVelocity plugin;

    public NetworkCommand(PerfScopeVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();

        if (args.length > 0 && !"network".equalsIgnoreCase(args[0])) {
            src.sendMessage(Component.text("用法：/pscope network —— 查看全网各后端的性能摘要", NamedTextColor.YELLOW));
            return;
        }

        // /pscope network reset —— 清空已收到的数据（换后端、排查时想让面板从头开始）。
        // 这是 NetworkView.clear() 的唯一调用点；没有它 clear() 就是死代码，
        // 连"出问题想重新统计"都做不到。
        if (args.length > 1 && "reset".equalsIgnoreCase(args[1])) {
            plugin.view().clear();
            // 绑定表也一起清：换过后端 / 改过 server-id 之后，旧的"某个 id 归哪条后端"
            // 若留着，新后端会被判成抢占而永久丢包——那是与 reset 语义直接矛盾的。
            plugin.sourceBinder().clear();
            src.sendMessage(Component.text("已清空已收到的跨服摘要，等待后端重新上报。", NamedTextColor.YELLOW));
            return;
        }

        long now = System.currentTimeMillis();
        List<NetworkView.Node> nodes = plugin.view().snapshot(now, DEFAULT_PUSH_INTERVAL_SEC, expectedServers());

        Double avgTps = NetworkView.averageTps1m(nodes);
        // 合计带回"没计入多少"：只给一个数字会被读成"全服在线"，而它只覆盖 OK 后端。
        // 详见 NetworkView.PlayerTotal 与 NetworkRenderer.playersTotalText。
        NetworkView.PlayerTotal totalPlayers = NetworkView.totalPlayers(nodes);

        src.sendMessage(Component.text(
            NetworkRenderer.summaryLine(nodes, avgTps, totalPlayers), NamedTextColor.GRAY));

        // 排查提示挂在「没有一个后端正常」上，而不是「一行都没有」上。
        //
        // 后者在真实代理上几乎不可达：velocity.toml 里只要配了后端，expectedServers()
        // 就非空，视图里必然有行（显示成「无数据」）。于是首装失败时服主只看到满屏
        // 深灰的「无数据」，而那段写得最用心的三步排查提示一次也显示不出来——
        // 恰恰是最需要它的时候。
        if (NetworkView.count(nodes, NetworkView.State.OK) == 0) {
            src.sendMessage(Component.text(
                "没有任何后端处于「正常」状态。请确认："
                    + "(1) 各后端 config.yml 的 network.enabled 为 true；"
                    + "(2) 该后端有玩家在线（空服没有转发通道）；"
                    + "(3) 代理已装本插件、且未把本通道转发走。"
                    + "空服后端显示「无数据」是预期行为，不是故障。",
                NamedTextColor.YELLOW));
        }
        if (nodes.isEmpty()) {
            return;
        }

        List<NetworkRenderer.Row> rows = NetworkRenderer.render(nodes);
        // 按各列最大宽度填充后再拼：否则中文状态（4 字）与数字（1-2 格）会让每一行的
        // 第 3 列起落在不同位置，六行以上看不出哪台不对劲——而横向比较正是这个
        // 面板存在的理由。填充在命令层做，因为只有这里知道要拼成一行。
        int[] widths = NetworkRenderer.columnWidths(rows, NetworkRenderer.HEADERS);
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < NetworkRenderer.HEADERS.length; i++) {
            if (i > 0) header.append("  ");
            String h = NetworkRenderer.HEADERS[i];
            header.append(i == NetworkRenderer.HEADERS.length - 1
                ? h : NetworkRenderer.padTo(h, widths[i]));
        }
        src.sendMessage(Component.text(header.toString(), NamedTextColor.WHITE));

        for (NetworkRenderer.Row row : rows) {
            src.sendMessage(renderRow(row, widths));
        }
    }

    /**
     * 期望的后端列表。
     *
     * <p>取自 Velocity 自己配置里已注册的后端——这样"某一台从来没上报过"也能显示出来，
     * 而不是从面板上彻底消失。只看"收到过包的"会漏掉最重要的一种情况：某台后端压根没配好。</p>
     */
    private java.util.Collection<String> expectedServers() {
        try {
            List<String> ids = new ArrayList<>();
            for (var registered : plugin.server().getAllServers()) {
                String name = registered.getServerInfo().getName();
                if (name != null && !name.isBlank()) {
                    ids.add(name);
                }
            }
            return ids;
        } catch (Exception e) {
            // 取不到就退回"只显示收到过包的"，仍然可用，只是看不出哪台没上报。
            return java.util.Collections.emptyList();
        }
    }

    /** 把一行的各格拼成一个 Component，逐格上色，按 {@code widths} 补齐列宽。 */
    private static Component renderRow(NetworkRenderer.Row row, int[] widths) {
        Component out = Component.empty();
        List<NetworkRenderer.Cell> cells = row.cells();
        for (int i = 0; i < cells.size(); i++) {
            NetworkRenderer.Cell cell = cells.get(i);
            if (i > 0) {
                out = out.append(Component.text("  ", NamedTextColor.DARK_GRAY));
            }
            // 最后一列不补：行尾空格在聊天框里看不见，只是白白多几个字符。
            String text = i == cells.size() - 1
                ? cell.text()
                : NetworkRenderer.padTo(cell.text(), widths[i]);
            out = out.append(Component.text(text, colorOf(cell.level())));
        }
        return out;
    }

    private static NamedTextColor colorOf(NetworkRenderer.Level level) {
        switch (level) {
            case OK:
                return NamedTextColor.GREEN;
            case WARN:
                return NamedTextColor.YELLOW;
            case CRITICAL:
                return NamedTextColor.RED;
            case UNKNOWN:
            default:
                // 未知用灰色而不是绿色：绿色会被读成"健康"，而未知恰恰是不该下结论。
                return NamedTextColor.DARK_GRAY;
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource src = invocation.source();
        // 控制台直接放行——但**只认控制台**，不能写成"任何非 Player 都放行"。
        //
        // 后者是 fail-open：Velocity 的 API 允许第三方插件构造自己的 CommandSource，
        // 某个"命令方块 / 脚本引擎"类插件把玩家身份包装成自定义 source 时，
        // 那个玩家就无条件绕过了权限。按类型白名单才是 fail-closed。
        if (src instanceof com.velocitypowered.api.proxy.ConsoleCommandSource) {
            return true;
        }
        // 其余一律查权限。注意：未装权限插件时 Velocity 的 permissionFunction 是
        // ALWAYS_UNDEFINED，asBoolean() 为 false —— 也就是默认谁都用不了。
        // 这是正确的保守默认，但部署者必须知道（见 README 的权限一节）。
        return src.hasPermission("perfscope.network");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("network");
    }
}