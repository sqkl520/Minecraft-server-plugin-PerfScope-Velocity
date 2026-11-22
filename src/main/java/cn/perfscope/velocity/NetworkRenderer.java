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
     * 不一致——服主会先怀疑某一个插件坏了。后端那几个值可配置，代理侧读不到，所以这里对齐的是
     * **随包默认值**；改了后端阈值就该同步改这里（README 有写明）。</p>
     *
     * <p><b>闭合性也属于"口径"的一部分，改阈值时一起看</b>：后端的判定出自
     * {@code ThresholdOperator} 那张算子表——{@code tps} / {@code mspt} 是<b>严格</b>比较
     * （{@code tps < 15}、{@code mspt > 50}），{@code memory} / {@code players} 才是闭区间。
     * 代理侧自己只显示 TPS 与 MSPT 两列，所以这里必须是严格比较：<b>恰好 15.00 不报警、
     * 14.99 才报</b>。这一版之前代理用的是闭区间（{@code <=} / {@code >=}），于是 15.00 / 18.00
     * / 45.0 / 50.0 四个点上「代理红、后端黄」——与后端"颜色变了就等于告警已触发"的承诺相反。
     * 判定与文案只允许有一处出处，这个面板没有第二份可以漂移的副本。</p>
     */
    public static final double TPS_WARN = 18.0;
    /** 低于这个 TPS 算严重（严格，与后端 {@code ThresholdOperator.TPS} 的 {@code <} 同口径）。 */
    public static final double TPS_CRITICAL = 15.0;

    /** MSPT 阈值，与后端默认值一致（45 警告 / 50 严重）。 */
    public static final double MSPT_WARN = 45.0;
    /** 高于这个 MSPT 算严重（严格，与后端 {@code ThresholdOperator.MSPT} 的 {@code >} 同口径）。 */
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
        switch (node.state()) {
            case NO_DATA:
                // 关键：这里不能显示 0。没收到过包 = 通道不存在（多半是空服），
                // 与"这个后端 TPS 是 0"是完全不同的两件事。
                return row(
                    idCell(node),
                    cell("无数据", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("从未上报", Level.UNKNOWN)
                );
            case COLLECTOR_DEAD:
                return row(
                    idCell(node),
                    cell("采集已停", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell("—", Level.UNKNOWN),
                    cell(formatAge(node.ageMs()) + "（数字已冻结）", Level.UNKNOWN)
                );
            case CLOCK_SKEW:
                // 时间戳不可信：数可以显示（服主能看出"最后是 19.8"，也知道那是哪一档），
                // 但整行必须 UNKNOWN，且年龄格**不能**写成"0 秒前"——那正是伪造时间戳
                // 想换来的那句话。改写成"超前 N"或"—"，让人知道问题出在时间而不是负载。
                return row(
                    idCell(node),
                    cell("时钟异常", Level.UNKNOWN),
                    tpsCell(node.packet().tps1m()),
                    msptCell(node.packet().msptAvg()),
                    playerCell(node.packet().playerCount()),
                    cell(clockDetail(node), Level.UNKNOWN)
                );
            case STALE:
                // 数据是陈的：数值仍然显示（服主能看出"最后是 19.8"），但整行标 UNKNOWN，
                // 且明确写出"已过期"，避免被当成当前值。
                return row(
                    idCell(node),
                    cell("数据过期", Level.UNKNOWN),
                    tpsCell(node.packet().tps1m()),
                    msptCell(node.packet().msptAvg()),
                    playerCell(node.packet().playerCount()),
                    cell(formatAge(node.ageMs()) + "（可能空服）", Level.UNKNOWN)
                );
            case OK:
            default:
                return row(
                    idCell(node),
                    cell("正常", Level.OK),
                    tpsCell(node.packet().tps1m()),
                    msptCell(node.packet().msptAvg()),
                    playerCell(node.packet().playerCount()),
                    cell(formatAge(node.ageMs()), Level.OK)
                );
        }
    }

    /**
     * 后端名格：按**显示宽度**封顶，超出部分截断并加省略号。
     *
     * <p>为什么不能只靠解析层的长度闸门：那道闸门按 UTF-16 长度算（管内存与单条消息尺寸），
     * 而列宽按显示宽度算。64 个汉字能过那道闸门，却是 128 格——整行会被撑到 165 格，
     * 在约 53 格的聊天框里折成三行，面板的纵向对照彻底失效。两个判据问的不是一件事，
     * 所以这里必须再封一次顶（见 {@link #MAX_ID_DISPLAY_WIDTH}）。</p>
     *
     * <p>截断而不是换行：换行会把一行拆成两行，行数一变，"第几行是哪台"这个视觉锚点
     * 就没了；而截断只损失名字尾部，行结构完整。</p>
     */
    private static Cell idCell(NetworkView.Node node) {
        // 即使 NO_DATA 行的 id 也来自配置/网络，同样要封顶——它一样会撑列宽。
        return cell(truncateToWidth(node.serverId(), MAX_ID_DISPLAY_WIDTH), levelOfId(node));
    }

    /**
     * 后端名格的级别：只有状态 OK 才是绿，其余一律灰。
     *
     * <p>后端名本身没有"好/坏"之分，但它在四种状态下的角色不同：OK 行的名字是"这台没事"的
     * 标题，其余三行的名字是"这台有问题"的标题。跟随行状态上色，比统一用灰色更能让人
     * 一眼看出哪几台要处理。</p>
     */
    private static Level levelOfId(NetworkView.Node node) {
        return node.state() == NetworkView.State.OK ? Level.OK : Level.UNKNOWN;
    }

    /**
     * TPS 格：缺失 / 不可信写"—"，不是 0；有值时按阈值分级。
     *
     * <p><b>严格比较</b>（{@code <}）：恰好等于 15.00 / 18.00 不报警。与后端
     * {@code ThresholdOperator.TPS} 逐点相同——后端那颗"颜色变了就等于告警已触发"的承诺，
     * 在这个面板上也必须成立，否则同一份数据两处颜色不同。</p>
     *
     * <p><b>量级校验</b>：{@code tps1m} 来自网络，{@code 1e308} 这种值不是一次测量。
     * 放它进来会让这一格变成 309 个字符，把整列（进而**每一行**）撑到 312 格。
     * 越界值按"没有值"处理而不是截断成上限——截断会凭空造出一个"测到 1000"的读数，
     * 比"—"更能误导。判据见 {@link NetworkView#isPlausibleTps}。</p>
     *
     * <p>注意 {@code 0} 是<b>合法</b>读数：它意味着服务器冻结，必须照常判红，
     * 不能当成"没采到"。这与后端 {@code ThresholdColors} 对 TPS 的特判一致
     * （那里明确不为 TPS 设负数哨兵）。</p>
     */
    static Cell tpsCell(Double tps) {
        if (tps == null || !NetworkView.isPlausibleTps(tps)) {
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

    /**
     * MSPT 格：缺失 / 不可信写"—"。越高越糟，<b>严格比较</b>（{@code >}）：恰好 45.0 是 OK、
     * 恰好 50.0 是 WARN，越过才变色。与后端 {@code ThresholdOperator.MSPT} 逐点相同。
     *
     * <p><b>下界是必须的，而且以前漏了</b>：{@code mspt ≤ 0} 是后端 {@code msptAvg} 的
     * "未采集"哨兵——TPS 反推路径在满 20 TPS 时会把 {@code msptAvg} 置成 {@code -1}，
     * 却没有把模块标成不可用。旧实现只判 {@code null}，于是 {@code -1.0} 落进最后的
     * {@code else}，被渲染成<b>绿色</b>的 {@code -1.0}。"tick 耗时负一毫秒"既不是真读数，
     * 又被上了代表健康的颜色，是这个面板最糟的一种错。</p>
     *
     * <p>后端 {@code ThresholdColors} 早就有这条（{@code value <= 0 → UNKNOWN}），
     * 代理侧一直缺——两边对同一个 {@code -1} 给出不同结论，正是本项目反复修的那类分裂。</p>
     */
    static Cell msptCell(Double mspt) {
        if (mspt == null || !NetworkView.isPlausibleMspt(mspt)) {
            return cell("—", Level.UNKNOWN);
        }
        Level level;
        if (mspt > MSPT_CRITICAL) {
            level = Level.CRITICAL;
        } else if (mspt > MSPT_WARN) {
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
     * 「时钟异常」那一行的年龄格写什么。
     *
     * <p><b>绝不能写"0 秒前"</b>：那正是伪造成未来时间戳想换来的那句话。这里写
     * "早 1天3小时" 这类正向表述，既说明问题在时间，也直接告诉服主"该往回拨多少"。</p>
     *
     * <p>超前量按 {@code aheadMs} 而不是按年龄算——后者的符号在进入本状态时已经被判定为
     * 不可用，再用它就等于拿坏数据算坏数据。</p>
     */
    static String clockDetail(NetworkView.Node node) {
        long ahead = node.aheadMs();
        if (ahead <= 0L) {
            // 理论上进不了这里（CLOCK_SKEW 必须来自超前判定）；留一条降级路径，
            // 宁肯写 — 也不要凭空编一个时间差。
            return "时间戳不可信";
        }
        long sec = ahead / 1000L;
        if (sec < 60) {
            return "早 " + sec + "秒（时间戳在未来）";
        }
        long min = sec / 60;
        if (min < 60) {
            return "早 " + min + "分钟（时间戳在未来）";
        }
        long hour = min / 60;
        if (hour < 24) {
            return "早 " + hour + "小时（时间戳在未来）";
        }
        return "早 " + (hour / 24) + "天（时间戳在未来）";
    }

    /**
     * 汇总行：几个后端在跑、几个正常、几个没数据。
     *
     * <p>写成一行文字而不是表格，因为它回答的是"整个网络还好吗"，与逐行细节不同层级。</p>
     */
    public static String summaryLine(List<NetworkView.Node> nodes, Double avgTps,
                                     NetworkView.PlayerTotal total) {
        int ok = NetworkView.count(nodes, NetworkView.State.OK);
        int stale = NetworkView.count(nodes, NetworkView.State.STALE);
        int dead = NetworkView.count(nodes, NetworkView.State.COLLECTOR_DEAD);
        int skew = NetworkView.count(nodes, NetworkView.State.CLOCK_SKEW);
        int noData = NetworkView.count(nodes, NetworkView.State.NO_DATA);

        StringBuilder sb = new StringBuilder();
        sb.append("后端 ").append(nodes.size()).append(" 个：正常 ").append(ok);
        if (stale > 0) sb.append(" · 数据过期 ").append(stale);
        if (dead > 0) sb.append(" · 采集已停 ").append(dead);
        // 时钟异常单独成一项：它和"数据过期"经常同时成立（一只坏钟的后端也会不回话），
        // 合并计数会让服主以为网络有问题。
        if (skew > 0) sb.append(" · 时钟异常 ").append(skew);
        if (noData > 0) sb.append(" · 无数据 ").append(noData);

        sb.append(" ｜ 全网 TPS ");
        sb.append(avgTps == null ? "—" : String.format(Locale.ROOT, "%.2f", avgTps));
        sb.append(" ｜ 在线合计 ");
        sb.append(playersTotalText(total));
        return sb.toString();
    }

    /**
     * 「在线合计」这一段怎么写——**不完整的合计必须自己说出来**。
     *
     * <p><b>这道题为什么不能用"加个符号"或"标个星"解决</b>：合计只收到 OK 后端，
     * 这个口径是对的（把过期数字混进来等于编一个总数）。问题在于读者把它读成"全服在线"。
     * 一台 OK(10 人) + 一台 STALE(50 人) 时面板写「10」，<b>没有任何标记能阻止这个误读</b>——
     * 读者不知道标记的含义，也不知道差了多少。所以这里直接把差额写进同一个格子里：
     * 「10（+50 未计入）」。</p>
     *
     * <p><b>为什么选「A（+B 未计入）」而不是「A / B（部分不可信）」</b>：后者把
     * 两个数的关系写反了——`10 / 60` 读起来像"进了 10、总共 60、还差 50"，
     * 而真实含义是"确认 10、另有 50 但不可信"。`+50` 明确了方向（是<b>额外</b>的人），
     * 「未计入」明确了原因（不是"不存在"，是"没算"）。总数本身（60）由读者自己加，
     * 面板不该替他给出一个把不可信数字和可信数字等量相加的和。</p>
     *
     * <p>连读数都没有的后端单列成「另 N 台无数据」，不与「+B」混在一起：
     * 一个是"有数但不信"，一个是"连数都没有"，对"到底有多少人"的贡献完全不同。</p>
     */
    static String playersTotalText(NetworkView.PlayerTotal total) {
        if (total == null) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(total.counted() == null ? "—" : String.valueOf(total.counted()));
        if (total.uncountedKnown() > 0 || total.uncountedUnknown() > 0) {
            sb.append("（");
            boolean needSep = false;
            if (total.uncountedKnown() > 0) {
                sb.append("+").append(total.uncountedKnown()).append(" 未计入");
                needSep = true;
            }
            if (total.uncountedUnknown() > 0) {
                if (needSep) sb.append(" · ");
                sb.append("另 ").append(total.uncountedUnknown()).append(" 台无读数");
            }
            sb.append("）");
        }
        return sb.toString();
    }

    // ---- 构造辅助 ----

    /**
     * 省略号：截断时必须**看得出来被截断了**，而不是悄悄给出一段看起来完整的前缀。
     *
     * <p>U+2026 属于 East Asian Width 的 Ambiguous，按本类的判据算 1 格（见
     * {@link #displayWidth} 的"已知不精确"一节）。</p>
     */
    static final String ELLIPSIS = "…";

    /** 省略号本身的显示宽度，与 {@link #displayWidth} 保持一致。 */
    private static final int ELLIPSIS_WIDTH = 1;

    /**
     * **后端名**这一格的硬上限（显示格）。
     *
     * <p><b>为什么需要它</b>：{@code serverId} 完全来自网络输入，而 {@link #columnWidths}
     * 取的是全表最大值——也就是说，只要有<b>一格</b>超长，整列（进而每一行）都会被撑到那个宽度。
     * 这不是"某一格难看"，是面板整体失效：一行 165 格在约 53 格的聊天框里折成三行，
     * 纵向对照彻底没了。</p>
     *
     * <p><b>为什么 20 格</b>：真实后端名（`survival-1`、`skyblock-asia-2`、`minigames-3`）
     * 都在 16 格以内，20 留了一点余量；再大就只是让每行更容易折行。
     * 20 格 = 20 个半角字符，或 10 个汉字。</p>
     *
     * <p><b>为什么这里还要再做一次，而不是只靠解析层的长度闸门</b>：那一道问的是
     * "这个包要不要"（判据是 UTF-16 长度，管内存与单条消息尺寸），这一道问的是
     * "这一格能占多宽"（判据是显示宽度，管表格不被撑坏）。两个问题不重合：
     * 64 个 ASCII 字符能过解析闸门，但 64 格仍会把一行推到约 120 格；
     * 而 64 个汉字同样能过 UTF-16 闸门，却是 <b>128 格</b>。只有按显示宽度再封一次顶，
     * 面板才不依赖"上游闸门的具体数值"。</p>
     */
    static final int MAX_ID_DISPLAY_WIDTH = 20;

    /**
     * East Asian Width 为 W（Wide）或 F（Fullwidth）的码点区间，已合并相邻项。
     *
     * <p><b>来源</b>：Unicode 18.0.0 的 {@code EastAsianWidth.txt}，取 {@code W} / {@code F}
     * 两种值并合并为 126 段（原表 298 条）。合并后用"起点、终点"成对存储，二分查找。
     * 只收 W/F 是刻意的：这两种才是"一个字符占两个半角位"的确定事实，
     * 其余（N / Na / H / A）都不能仅凭表断言宽度。</p>
     *
     * <p><b>为什么手写表而不是引第三方库</b>：本项目的特色是零运行时依赖
     * （连 Gson 都是 shade 进来的）。为一张 126 段的常量表引入一个库，等于把
     * "会不会与代理上其它插件撞类"这种风险换进来，不划算。</p>
     *
     * <p><b>为什么不直接用 {@code Character.UnicodeBlock}</b>：块（Block）是"这批码点放一起"
     * 的排版分组，与"这个字符有多宽"不是一回事。反例就在同一块里：
     * {@code CJK_SYMBOLS_AND_PUNCTUATION} 里 U+3007（〇）是 W，而 U+303F 是 N；
     * {@code HALFWIDTH_AND_FULLWIDTH_FORMS} 里 U+FF71（ｱ 半角片假名）是 H（窄），
     * U+FF21（Ａ 全角 A）才是 F。按块判断会把这两组各错一半。</p>
     */
    private static final int[] WIDE_RANGES = {
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC,
        0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2630, 0x2637, 0x2648, 0x2653, 0x267F, 0x267F, 0x268A, 0x268F,
        0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA,
        0x26F2, 0x26F3, 0x26F5, 0x26F5, 0x26FA, 0x26FA, 0x26FD, 0x26FD,
        0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
        0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50,
        0x2B55, 0x2B55, 0x2E80, 0x2E99, 0x2E9B, 0x2EF3, 0x2F00, 0x2FD5,
        0x2FF0, 0x303E, 0x3041, 0x3096, 0x3099, 0x30FF, 0x3105, 0x312F,
        0x3131, 0x318E, 0x3190, 0x31E5, 0x31EF, 0x321E, 0x3220, 0x3247,
        0x3250, 0xA48C, 0xA490, 0xA4C6, 0xA960, 0xA97C, 0xAC00, 0xD7A3,
        0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE52, 0xFE54, 0xFE66,
        0xFE68, 0xFE6B, 0xFF01, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4,
        0x16FF0, 0x16FF6, 0x17000, 0x18CDA, 0x18CFF, 0x18D20, 0x18D80, 0x18DF2,
        0x18E00, 0x19191, 0x191A0, 0x191D2, 0x1AFF0, 0x1AFF3, 0x1AFF5, 0x1AFFB,
        0x1AFFD, 0x1AFFE, 0x1B000, 0x1B128, 0x1B132, 0x1B132, 0x1B150, 0x1B152,
        0x1B155, 0x1B155, 0x1B164, 0x1B168, 0x1B170, 0x1B2FB, 0x1D300, 0x1D356,
        0x1D360, 0x1D376, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A, 0x1F1AE, 0x1F1AE, 0x1F200, 0x1F202, 0x1F210, 0x1F23B,
        0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265, 0x1F300, 0x1F320,
        0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA,
        0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E,
        0x1F440, 0x1F440, 0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E,
        0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4,
        0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2,
        0x1F6D5, 0x1F6D9, 0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC,
        0x1F7DA, 0x1F7DA, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FAC6,
        0x1FAC8, 0x1FAC8, 0x1FACC, 0x1FADD, 0x1FADF, 0x1FAEB, 0x1FAEF, 0x1FAFA,
        0x20000, 0x2FFFD, 0x30000, 0x3FFFD
    };

    /** 零宽连接符：把前后两个字符合成一个字形（emoji 家庭、职业等）。 */
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    /** 变体选择符 16：要求前一个字符以**彩色 emoji** 形态呈现（宽度因此变成 2）。 */
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    /** Keycap 组合用圈（`1️⃣` 那个）。 */
    private static final int COMBINING_ENCLOSING_KEYCAP = 0x20E3;

    /** 一个"字素簇"的终点（不含）与显示宽度。 */
    private static final class Cluster {
        final int end;
        final int width;

        Cluster(int end, int width) {
            this.end = end;
            this.width = width;
        }
    }

    /**
     * 一格的显示宽度，按"半角 1 格 / 全角 2 格 / 组合记号 0 格"计算。
     *
     * <p><b>为什么需要它</b>：面板靠空格分隔，而中文与数字的宽度不同——「数据过期」四字与
     * 「正常」两字差一倍，`6` 与 `57` 又差一格。不做填充时每一行的第 3 列起都在不同的横坐标上，
     * 六行以上完全无法纵向比较，而"横向比较哪台不对劲"正是这个面板存在的理由。</p>
     *
     * <p><b>为什么不追求像素级对齐</b>：Minecraft 默认字体里 ASCII 是变宽的（`i` 与 `W` 差三倍），
     * 真正的像素对齐需要一张逐字符宽度表，而那张表会随资源包变化。这里按"字符占几个半角位"
     * 计算，收益是列起点稳定下来，代价是等宽字体下的位内偏差。刻意不引入字体表。</p>
     *
     * <h2>判据（按顺序，先命中先用）</h2>
     *
     * <ol>
     *   <li><b>组合记号 / 格式符 → 0 格。</b>Unicode 类别 Mn（`é` 的重音、变体选择符）、
     *       Me（keycap 圈）、Cf（ZWJ、ZWNJ、BOM、软连字符）都不单独占位，它们附着在
     *       前一个基字符上。这是"`é` 算 1 格、`e` + U+0301 也算 1 格"的来源。
     *       旧实现按码点 `>= 0x80 → 2` 把它算成 3 格。</li>
     *   <li><b>W / F 区间 → 2 格。</b>见 {@link #WIDE_RANGES}。中文、日文假名（全角）、
     *       韩文、全角标点、以及绝大多数 emoji 都在这里。</li>
     *   <li><b>其余 → 1 格。</b>含 ASCII、H（如 `ｱ` 半角片假名）、N、Na、以及 A（Ambiguous）。</li>
     * </ol>
     *
     * <h2>字素簇：ZWJ 序列 / 变体选择符 / 肤色修饰</h2>
     *
     * <p>emoji 家庭 `👨&#x200D;👩&#x200D;👧&#x200D;👦` 由 4 个 W 码点 + 3 个 ZWJ 组成。
     * 逐码点相加会得到 8～14 格，而它在客户端里是<b>一个</b> 2 格宽的字形。所以这里按
     * "字素簇"累加：一个基字符后面跟着 ZWJ（及其后的基字符）、变体选择符、肤色修饰符时，
     * 整簇仍只算基字符的宽度（且至少 2 格——ZWJ 序列只用于 emoji 呈现）。</p>
     *
     * <h2>已知不精确（明确标注，不假装精确）</h2>
     *
     * <ul>
     *   <li><b>A（Ambiguous）按 1 格。</b>`Ω`、`α`、`—`、`…` 都属于这一类。这是刻意的取舍：
     *       把它们算 2 格会让 `Ω` 这种纯拉丁/希腊文本被高估一倍（旧实现正是这样），
     *       而算 1 格只在<b>中文语境、且客户端用双宽字体渲染</b>时才差 1 格。
     *       面板里实际会打印的 A 类字符只有 `—` 与 `…`，影响是<b>含这两个字符的格子</b>
     *       最多偏 1 格，不会累积到所有行。</li>
     *   <li><b>keycap（`1️⃣`）</b>按 1 格算（`1` 是窄字符），实际渲染是 2 格。</li>
     *   <li><b>区域旗帜</b>（两个 Regional Indicator）逐字符各 1 格，合计 2 格，与真实宽度一致。
     *       三个以上 RI 会各算各的，实践中不存在。</li>
     *   <li><b>未分配码点</b>按 1 格。它们在客户端里通常显示成豆腐块（1 格），可接受。</li>
     *   <li><b>组合记号叠在宽字符上</b>（如 `中` + 变体选择符）算 2 格，正确。</li>
     * </ul>
     *
     * <p>要做到"完全精确"还需要一张 emoji 呈现序列表与逐字体的 glyph 宽度表；
     * 那两张表都会随 Unicode 版本 / 资源包漂移，维护成本远高于这里能买到的对齐收益。</p>
     */
    static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            Cluster c = clusterAt(s, i);
            w += c.width;
            i = c.end;
        }
        return w;
    }

    /**
     * 取出从 {@code start} 开始的一个字素簇。
     *
     * <p>{@link #displayWidth} 与 {@link #truncateToWidth} 都走这一个函数，
     * 这样"量宽度"和"按宽度截断"用的判据在结构上就是同一份——否则很容易出现
     * "测量算 2 格、截断时按 1 格切"这种自我矛盾的 bug。</p>
     */
    private static Cluster clusterAt(String s, int start) {
        int cp = s.codePointAt(start);
        int i = start + Character.charCount(cp);

        // 没有基字符可依附的孤立组合记号 / 格式符：自己就是 0 格。
        if (isZeroWidth(cp)) {
            return new Cluster(i, 0);
        }

        int width = isWide(cp) ? 2 : 1;

        // 吸收后续的"附加"码点：ZWJ 连字、变体选择符、肤色修饰、组合记号。
        boolean emojiPresentation = false;
        while (i < s.length()) {
            int next = s.codePointAt(i);
            if (next == ZERO_WIDTH_JOINER) {
                // ZWJ：把 ZWJ 与**下一个基字符**一起吸进来，整簇宽度不变。
                // 这正是 `👨‍👩‍👧‍👦` 算 2 格而不是 8 格的原因。
                i += 1;
                if (i < s.length()) {
                    int joined = s.codePointAt(i);
                    i += Character.charCount(joined);
                }
                emojiPresentation = true;
                continue;
            }
            if (next == VARIATION_SELECTOR_16) {
                // 变体选择符 16 = 要求 emoji 呈现。`❤` 本身是 N（1 格），
                // 加了 VS16 之后按 emoji 渲染成 2 格。
                i += 1;
                emojiPresentation = true;
                continue;
            }
            if (next == COMBINING_ENCLOSING_KEYCAP) {
                i += Character.charCount(next);
                emojiPresentation = true;
                continue;
            }
            if (isEmojiModifier(next)) {
                // 肤色修饰（U+1F3FB..U+1F3FF）本身是 W，但它是"修饰"不是"新字符"，
                // 不吸收就会把 👍🏽 算成 4 格。
                i += Character.charCount(next);
                emojiPresentation = true;
                continue;
            }
            if (isZeroWidth(next)) {
                i += Character.charCount(next);
                continue;
            }
            break;
        }

        // ZWJ 序列 / VS16 / 肤色修饰都只出现在 emoji 上，而 emoji 一律至少占 2 格。
        if (emojiPresentation && width < 2) {
            width = 2;
        }
        return new Cluster(i, width);
    }

    /**
     * 该码点是否**不单独占位**（组合记号 / 格式符）。
     *
     * <p>按 Unicode 通用类别判断，而不是枚举码点区间：Mn / Me / Cf 这三类在 Unicode 里的
     * 定义就是"附着在别的字符上"或"不可见控制"，用类别判断天然覆盖全部语言
     * （阿拉伯语元音符、天城文、泰文、韩文终声……），且随 JDK 的 Unicode 版本自动更新。</p>
     *
     * <p>注意<b>不含</b> Mc（Spacing Combining Mark，如天城文的元音符号）：那一类在实际渲染里
     * 是要占位的，算 0 会让整行偏窄。</p>
     */
    private static boolean isZeroWidth(int cp) {
        switch (Character.getType(cp)) {
            case Character.NON_SPACING_MARK:   // Mn
            case Character.ENCLOSING_MARK:     // Me
            case Character.FORMAT:             // Cf（ZWJ/ZWNJ/BOM/软连字符）
                return true;
            default:
                return false;
        }
    }

    /** 肤色修饰符 U+1F3FB..U+1F3FF：只修饰前一个 emoji，不单独占位。 */
    private static boolean isEmojiModifier(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    /** 该码点的 East Asian Width 是否为 W / F（二分查找 {@link #WIDE_RANGES}）。 */
    private static boolean isWide(int cp) {
        int lo = 0;
        int hi = WIDE_RANGES.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = WIDE_RANGES[mid * 2];
            int end = WIDE_RANGES[mid * 2 + 1];
            if (cp < start) {
                hi = mid - 1;
            } else if (cp > end) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一格里补到指定显示宽度（不足补半角空格）；**超出时按宽度截断**。
     *
     * <p><b>为什么超宽要截断，而不是像以前那样原样返回</b>：这个函数的契约是
     * "返回的字符串恰好占 {@code width} 格"，调用方（命令层拼行）唯一的依赖就是它。
     * 原样返回会在"调用方算错了列宽"或"上游放进来一个超长格子"时，把一行推成
     * 原来那个 312 格的样子——而那时面板已经废了，没有任何地方会报错。
     * 截断把最坏情况限制成"这一格被剪短"，并且用省略号标出来。</p>
     *
     * <p>正常路径上它<b>不会</b>触发：列宽来自 {@link #columnWidths}（全表最大值），
     * 每格都不超过自己那一列。所以截断是第二道防线，不是常规行为。</p>
     */
    static String padTo(String s, int width) {
        if (s == null) return "";
        if (width <= 0) return "";
        int w = displayWidth(s);
        if (w > width) {
            // 截断后再补一次：按字素簇切时预算未必能被占满（汉语 2 格、预算 19 →
            // 只能用到 18 格再加 1 格省略号），留下 1 格缺口就会让这一列短一格。
            // 补空格是唯一能让"返回串恰好 width 格"这件事在**所有**输入下都成立的做法。
            s = truncateToWidth(s, width);
            w = displayWidth(s);
        }
        if (w == width) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < width - w; i++) sb.append(' ');
        return sb.toString();
    }

    /**
     * 把字符串截到不超过 {@code maxWidth} 显示格，末尾加省略号。
     *
     * <p>预留 1 格给省略号：宁可少显示一个字，也不要给出一个**看起来完整**的前缀——
     * 后者会让服主以为后端名就是那么短，把一个截断当成了事实。</p>
     *
     * <p>按字素簇切，不会把 emoji 家族或 `é` 切成半个：切点永远落在簇边界上，
     * 这也保证 {@code displayWidth(truncateToWidth(s, w)) <= w} 恒成立。</p>
     */
    static String truncateToWidth(String s, int maxWidth) {
        if (s == null) return "";
        if (maxWidth <= 0) return "";
        if (displayWidth(s) <= maxWidth) return s;

        int budget = maxWidth - ELLIPSIS_WIDTH;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            Cluster c = clusterAt(s, i);
            if (w + c.width > budget) {
                break;
            }
            sb.append(s, i, c.end);
            w += c.width;
            i = c.end;
        }
        return sb.append(ELLIPSIS).toString();
    }

    /**
     * 一组行（含表头）每列的最大显示宽度，供命令层按列填充。
     *
     * <p><b>为什么这里要封顶</b>：宽度取全表最大值，于是任意一格超长都会把整列
     * （进而每一行）撑到那个宽度。{@link #MAX_ID_DISPLAY_WIDTH} 管住了后端名那一格，
     * 但"数据年龄"格里拼了文案（`12分钟前（数字已冻结）`），状态格也可能因将来改动变长。
     * 这道总闸把单列宽度封在 {@link #MAX_CELL_WIDTH}，让"整行不超过约
     * {@code 6 × 32 + 10 = 202} 格"成为本类的一个**可断言**性质，而不是恰好成立的巧合
     * （旧实现靠"调用方按顺序算宽度"，`padTo` 自己没有防护）。</p>
     *
     * <p>它与 {@link #padTo} 的分工：这里决定"最多留多宽"，{@code padTo} 保证
     * "返回的串正好这么宽"（超了截断）。两道防线各自独立成立，不再互相依赖。</p>
     */
    static int[] columnWidths(List<Row> rows, String[] headers) {
        int[] w = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            w[i] = Math.min(MAX_CELL_WIDTH, displayWidth(headers[i]));
        }
        for (Row row : rows) {
            List<Cell> cells = row.cells();
            for (int i = 0; i < cells.size() && i < w.length; i++) {
                w[i] = Math.max(w[i], Math.min(MAX_CELL_WIDTH, displayWidth(cells.get(i).text())));
            }
        }
        return w;
    }

    /**
     * 单列的硬上限（显示格）。
     *
     * <p>32 格是"最长的合法内容"的量级：`12分钟前（数字已冻结）` 是 22 格，
     * 表头 `TPS(1m)` 是 7 格，后端名另由 {@link #MAX_ID_DISPLAY_WIDTH} 管到 20 格。
     * 留到 32 是为了让文案将来小改（比如「采集已停（数字已冻结）」）不必动这个数，
     * 同时仍然保证整行不会被一格推成几百格。</p>
     *
     * <p>超出的格子由 {@link #padTo} 截断——所以<b>到达这里之前</b>就该尽量截短
     * （后端名已经先截了一次，这样省略号出现在名字里而不是整行末尾）。</p>
     */
    static final int MAX_CELL_WIDTH = 32;

    private static Cell cell(String text, Level level) {
        return new Cell(text, level);
    }

    private static Row row(Cell... cells) {
        return new Row(java.util.Arrays.asList(cells));
    }
}
