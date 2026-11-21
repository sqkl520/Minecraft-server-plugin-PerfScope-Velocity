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
            src.sendMessage(Component.text("已清空已收到的跨服摘要，等待后端重新上报。", NamedTextColor.YELLOW));
            return;
        }

        long now = System.currentTimeMillis();
        List<NetworkView.Node> nodes = plugin.view().snapshot(now, DEFAULT_PUSH_INTERVAL_SEC, expectedServers());

        if (nodes.isEmpty()) {
            src.sendMessage(Component.text(
                "还没有任何后端上报数据。请确认："
                    + "(1) 各后端 config.yml 的 network.enabled 为 true；"
                    + "(2) 代理转发模式为 traditional/legacy（modern 模式下 plugin messaging 不可用）；"
                    + "(3) 该后端有玩家在线（空服没有转发通道）。",
                NamedTextColor.YELLOW));
            return;
        }

        Double avgTps = NetworkView.averageTps1m(nodes);
        Integer totalPlayers = NetworkView.totalPlayers(nodes);

        src.sendMessage(Component.text(
            NetworkRenderer.summaryLine(nodes, avgTps, totalPlayers), NamedTextColor.GRAY));
        src.sendMessage(Component.text(
            String.join("  ", NetworkRenderer.HEADERS), NamedTextColor.WHITE));

        List<NetworkRenderer.Row> rows = NetworkRenderer.render(nodes);
        for (NetworkRenderer.Row row : rows) {
            src.sendMessage(renderRow(row));
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

    /** 把一行的各格拼成一个 Component，逐格上色。 */
    private static Component renderRow(NetworkRenderer.Row row) {
        Component out = Component.empty();
        boolean first = true;
        for (NetworkRenderer.Cell cell : row.cells()) {
            if (!first) {
                out = out.append(Component.text("  ", NamedTextColor.DARK_GRAY));
            }
            first = false;
            out = out.append(Component.text(cell.text(), colorOf(cell.level())));
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