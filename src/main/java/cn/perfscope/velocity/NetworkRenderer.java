package cn.perfscope.velocity;

import java.util.List;
import java.util.Locale;

/**
 * 把 {@link NetworkView.Node} 列表渲染成纯文本表格。
 *
 * <p><b>为什么单独拆出来且只产出纯文本</b>：命令层要的是 Adventure 的 Component，
 * 但那需要 Velocity 运行时才能构造。渲染的**内容**（每一行说什么、缺数据怎么写）
 * 才是真正要测的部分，颜色只是最后一层装饰。所以这里产出
 * {@link Row}（每格文本 + 一个语义级别），命令层再把级别映射成颜色。</p>
 *
 * <p>这个拆分有个额外好处：测试能逐格断言，而不是对着带颜色标记的字符串做子串匹配
 * ——后者在改动措辞时会假红、在把"未知"误写成"0"时又不会红。</p>
 */
public final class NetworkRenderer {

    /** 一格的语义级别，由命令层映射为颜色。 */
    public enum Level {
        /** 正常。 */
        OK,
        /** 警告区间。 */
        WARN,
        /** 严重。 */
        CRITICAL,
        /** 没有判定（没数据 / 采集器已死 / 失联）。**绝不能渲染成绿色或 0**。 */
        UNKNOWN
    }

    /** 一行里的一个格子。 */
    public static final class Cell {
        private final String text;
        private final Level level;

        Cell(String text, Level level) {
            this.text = text;
            this.level = level;
        }

        public String text() { return text; }
        public Level level() { return level; }
    }

    /** 一整行。 */
    public static final class Row {
        private final List<Cell> cells;

        Row(List<Cell> cells) {
            this.cells = cells;
        }

        public List<Cell> cells() { return cells; }
    }

    /** 表头。 */
    public static final String[] HEADERS = {"后端", "状态", "TPS(1m)", "MSPT", "玩家", "数据年龄"};

    /** 阈值：低于这个 TPS 算警告。与主仓 README 的早预警语义同口径（闭区间）。 */
    public static final double TPS_WARN = 18.0;
    /** 阈值：低于这个 TPS 算严重。 */
    public static final double TPS_CRITICAL = 15.0;

    private NetworkRenderer() {
    }

    /**
     * 渲染所有行。
     *
     * @param nodes 视图节点
     * @return 与 {@link #HEADERS} 等宽的行列表
     */
    public static List<Row> render(List<NetworkView.Node> nodes) {
        List<Row> rows = new java.util.ArrayList<>();
        for (NetworkView.Node node : nodes) {
            rows.add(renderOne(node));
        }
        return rows;
    }

    /** 渲染单个后端。 */
    static Row renderOne(NetworkView.Node node) {
        String id = node.serverId();
        switch (node.state()) {
            case NO_DATA:
                // 关键：这里不能显示 0。没收到过包 = 通道不存在（多半是空服），
                // 与"这个后端 TPS 是 0"是完全不同的两件事。
                return row(
                    cell(id, Level.UNKNOWN),
                    cell("无数据", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("从未上报", Level.UNKNOWN)
                );
            case COLLECTOR_DEAD:
                return row(
                    cell(id, Level.UNKNOWN),
                    cell("采集已停", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell(formatAge(node.ageMs()) + "（数字已冻结）", Level.UNKNOWN)
                );
            case STALE:
                // 数据是陈的：数值仍然显示（服主能看出"最后是 19.8"），但整行标 UNKNOWN，
                // 且明确写出"已过期"，避免被当成当前值。
                return row(
                    cell(id, Level.UNKNOWN),
                    cell("数据过期", Level.UNKNOWN),
                    tpsCell(node.packet().tps1m()),
                    msptCell(node.packet().msptAvg()),
                    playerCell(node.packet().playerCount()),
                    cell(formatAge(node.ageMs()) + "（可能空服）", Level.UNKNOWN)
                );
            case OK:
            default:
                return row(
                    cell(id, Level.OK),
                    cell("正常", Level.OK),
                    tpsCell(node.packet().tps1m()),
                    msptCell(node.packet().msptAvg()),
                    playerCell(node.packet().playerCount()),
                    cell(formatAge(node.ageMs()), Level.OK)
                );
        }
    }

    /** TPS 格：缺失写"—"，不是 0；有值时按阈值分级。 */
    static Cell tpsCell(Double tps) {
        if (tps == null) {
            return cell("—", Level.UNKNOWN);
        }
        Level level;
        if (tps < TPS_CRITICAL) {
            level = Level.CRITICAL;
        } else if (tps < TPS_WARN) {
            level = Level.WARN;
        } else {
            level = Level.OK;
        }
        return cell(String.format(Locale.ROOT, "%.2f", tps), level);
    }

    /** MSPT 格：缺失写"—"。越高越糟。 */
    static Cell msptCell(Double mspt) {
        if (mspt == null) {
            return cell("—", Level.UNKNOWN);
        }
        Level level;
        if (mspt > 45.0) {
            level = Level.CRITICAL;
        } else if (mspt > 35.0) {
            level = Level.WARN;
        } else {
            level = Level.OK;
        }
        return cell(String.format(Locale.ROOT, "%.1f", mspt), level);
    }

    /**
     * 玩家格：{@code -1}（没采到）写"—"，{@code 0} 如实写"0"。
     *
     * <p>这一格是整个渲染层最容易写错的地方：如果不加判断直接打印，
     * {@code -1} 会显示成"-1 人"，比"未知"更糟——它看起来像一个真实读数。
     * 反过来把 0 也写成"—"就抹掉了"空服"这个合法状态。</p>
     */
    static Cell playerCell(int count) {
        if (count == NetworkView.COUNT_UNAVAILABLE) {
            return cell("—", Level.UNKNOWN);
        }
        return cell(String.valueOf(count), Level.OK);
    }

    /** 数据年龄：秒级，超过一分钟用分钟。 */
    static String formatAge(long ageMs) {
        if (ageMs == Long.MAX_VALUE) {
            return "—";
        }
        long sec = Math.max(0, ageMs / 1000L);
        if (sec < 60) {
            return sec + "秒前";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "分钟前";
        }
        return (min / 60) + "小时前";
    }

    /**
     * 汇总行：几个后端在跑、几个正常、几个没数据。
     *
     * <p>写成一行文字而不是表格，因为它回答的是"整个网络还好吗"，与逐行细节不同层级。</p>
     */
    public static String summaryLine(List<NetworkView.Node> nodes, Double avgTps, Integer totalPlayers) {
        int ok = NetworkView.count(nodes, NetworkView.State.OK);
        int stale = NetworkView.count(nodes, NetworkView.State.STALE);
        int dead = NetworkView.count(nodes, NetworkView.State.COLLECTOR_DEAD);
        int noData = NetworkView.count(nodes, NetworkView.State.NO_DATA);

        StringBuilder sb = new StringBuilder();
        sb.append("后端 ").append(nodes.size()).append(" 个：正常 ").append(ok);
        if (stale > 0) sb.append(" · 数据过期 ").append(stale);
        if (dead > 0) sb.append(" · 采集已停 ").append(dead);
        if (noData > 0) sb.append(" · 无数据 ").append(noData);

        sb.append(" ｜ 全网 TPS ");
        sb.append(avgTps == null ? "—" : String.format(Locale.ROOT, "%.2f", avgTps));
        sb.append(" ｜ 在线合计 ");
        sb.append(totalPlayers == null ? "—" : String.valueOf(totalPlayers));
        return sb.toString();
    }

    // ---- 构造辅助 ----

    private static Cell cell(String text, Level level) {
        return new Cell(text, level);
    }

    private static Row row(Cell... cells) {
        return new Row(java.util.Arrays.asList(cells));
    }
}
