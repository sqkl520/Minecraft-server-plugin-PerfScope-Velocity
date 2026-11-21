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

    /**
     * TPS 阈值，与后端 {@code config.yml} 的随包默认值同口径。
     *
     * <p><b>为什么必须同口径</b>：同一台后端在两个面板上被染成不同颜色是最伤信任的一种
     * 不一致——服主会先怀疑某一个插件坏了。此前这里写 18/15 用开区间，而后端
     * {@code ThresholdColors} 用闭区间（{@code value <= threshold}），于是恰好 15.00 与
     * 18.00 两个点两边着色相反。后端那几个值可配置，代理侧读不到，所以这里对齐的是
     * **随包默认值**；改了后端阈值就该同步改这里（README 有写明）。</p>
     */
    public static final double TPS_WARN = 18.0;
    /** 低于等于这个 TPS 算严重。 */
    public static final double TPS_CRITICAL = 15.0;

    /** MSPT 阈值，与后端默认值一致（45 警告 / 50 严重）。 */
    public static final double MSPT_WARN = 45.0;
    /** 高于等于这个 MSPT 算严重。 */
    public static final double MSPT_CRITICAL = 50.0;

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
        if (tps <= TPS_CRITICAL) {
            level = Level.CRITICAL;
        } else if (tps <= TPS_WARN) {
            level = Level.WARN;
        } else {
            level = Level.OK;
        }
        return cell(String.format(Locale.ROOT, "%.2f", tps), level);
    }

    /** MSPT 格：缺失写"—"。越高越糟，闭区间（与后端 {@code ThresholdColors} 一致）。 */
    static Cell msptCell(Double mspt) {
        if (mspt == null) {
            return cell("—", Level.UNKNOWN);
        }
        Level level;
        if (mspt >= MSPT_CRITICAL) {
            level = Level.CRITICAL;
        } else if (mspt >= MSPT_WARN) {
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

    /**
     * 一格的显示宽度，按"半角 1 / 全角 2"估算。
     *
     * <p><b>为什么需要它</b>：面板靠空格分隔，而中文与数字的宽度不同——「数据过期」四字与
     * 「正常」两字差一倍，`6` 与 `57` 又差一格。不做填充时每一行的第 3 列起都在不同的横坐标上，
     * 六行以上完全无法纵向比较，而"横向比较哪台不对劲"正是这个面板存在的理由。</p>
     *
     * <p><b>为什么不追求像素级对齐</b>：Minecraft 默认字体里 ASCII 是变宽的（`i` 与 `W` 差三倍），
     * 真正的像素对齐需要一张逐字符宽度表，而那张表会随资源包变化。这里按"ASCII 占 1 格、
     * 其余（中文、全角标点、破折号）占 2 格"估算，代价是等宽字体下会有半格以内的偏差，
     * 收益是列起点稳定下来。刻意不引入字体表：代价远大于收益。</p>
     */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            // 非 ASCII 一律按 2 格算：中文、全角标点，以及 — / ｜ 这类符号在默认字体里
            // 都走 unifont 的 9px 字形，实际占位接近两个半角字符。
            w += cp < 0x80 ? 1 : 2;
        }
        return w;
    }

    /** 把一格里补到指定显示宽度（不足补半角空格）。 */
    static String padTo(String s, int width) {
        int pad = width - displayWidth(s);
        if (pad <= 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.toString();
    }

    /** 一组行（含表头）每列的最大显示宽度，供命令层按列填充。 */
    static int[] columnWidths(List<Row> rows, String[] headers) {
        int[] w = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            w[i] = displayWidth(headers[i]);
        }
        for (Row row : rows) {
            List<Cell> cells = row.cells();
            for (int i = 0; i < cells.size() && i < w.length; i++) {
                w[i] = Math.max(w[i], displayWidth(cells.get(i).text()));
            }
        }
        return w;
    }

    private static Cell cell(String text, Level level) {
        return new Cell(text, level);
    }

    private static Row row(Cell... cells) {
        return new Row(java.util.Arrays.asList(cells));
    }
}
