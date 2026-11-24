package cn.perfscope.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * 跨服摘要协议 —— 代理侧的**读取端**。
 *
 * <p>这份类与主仓 {@code cn.perfscope.network.NetworkSummary} 是同一份契约的两个实现。
 * 两个仓库不能共享代码（一个跑在 Paper 上、一个跑在 Velocity 上、依赖的 API 完全不同），
 * 所以只能靠"字段名与语义逐字一致 + 两侧各有测试钉住"来保证不漂移。</p>
 *
 * <h2>解析必须是防御性的</h2>
 *
 * <p>摘要来自网络，虽然来自自家后端，但仍可能是：旧版后端（字段少）、未来版后端（字段多）、
 * 版本不兼容的后端（语义不同）、或者别的插件往同一通道里塞的垃圾。所以这里的每一条读取
 * 都遵守三条规则：</p>
 *
 * <ol>
 *   <li><b>版本高的拒绝，版本低的容忍。</b>见到 {@code v > }{@link #PROTOCOL_VERSION} 的包，
 *       返回 {@code null} 让调用方丢弃并记一条日志。理由：新后端可能改了字段语义，
 *       "尽力解析"会把改动读成 0——正是主仓 v1.4.2/v1.4.3 修的那类静默失真。</li>
 *   <li><b>缺字段就是"没有值"，不是 0。</b>数值字段缺失时返回 {@code null}，不做补零。
 *       与后端发送端的哨兵约定严格对称。</li>
 *   <li><b>任何解析异常都吞掉并返回 {@code null}。</b>一个坏包不能让代理侧崩，
 *       也不能污染其它后端的数据。</li>
 * </ol>
 */
public final class SummaryPacket {

    /**
     * 与主仓 NetworkSummary.PROTOCOL_VERSION 必须一致。
     *
     * <p>升号的含义是"旧接收方会拒收"：本端拒绝 {@code v} 比自己高的包。所以新增**可选**
     * 字段不升号（缺字段本来就要容忍，多一个不认识的键也不影响按 key 取值）；
     * 只有删字段、改语义、改单位才升。</p>
     */
    public static final int PROTOCOL_VERSION = 1;
    /** 通道名，与主仓 NetworkSummary.CHANNEL 逐字一致。 */
    public static final String CHANNEL_NAMESPACE = "perfscope";
    public static final String CHANNEL_NAME = "summary";

    /** 计数型字段的"没采到"，与主仓 COUNT_UNAVAILABLE 一致。 */
    public static final int COUNT_UNAVAILABLE = -1;

    /**
     * serverId 的最大长度。
     *
     * <p>这个字段**完全来自网络输入**，而且会被原样打印到游戏内面板上。
     * 不设上限时有两个后果：一台恶意后端可以用超长 id 把代理的堆填满
     * （实测约 500 个 2MB 的 id 就能吃满 1G 堆），以及把一条 2MB 的聊天消息
     * 塞进管理员的客户端。64 对真实用途绰绰有余（`survival`、`skyblock-a`、
     * `minigames-3` 这种），超出的直接判为无效包。</p>
     */
    public static final int MAX_SERVER_ID_LENGTH = 64;

    /** 合理的玩家数上限（防止伪造包把聚合值撑到荒谬的量级）。 */
    private static final int MAX_PLAYERS = 100_000;

    /**
     * 「快照时间最多能比此刻的对方时间轴晚多少」——超过就判为时间戳不可信（毫秒）。
     *
     * <p><b>为什么必须有这条上界。</b>此前 {@link #ageMs(long)} 只判 {@code collectedAt <= 0}
     * （缺值分支），对"在未来"没有任何约束，于是 {@code Math.max(0, now - collectedAt)} 把
     * 未来的时间戳 clamp 成 {@code 0} —— 快照年龄恒为 0，状态恒为 OK。一个伪造的 2100 年
     * 时间戳就能让一台已经死掉的后端在面板上<b>永久</b>"正常"。这里与 {@link #pushIntervalSec()}
     * 的 1–300 钳制是同一个理由（"否则一个伪造的极大值会让该后端永远显示正常"），
     * 只是当时漏了时间戳这一条。</p>
     *
     * <p><b>为什么是 60 秒。</b>比对基准是<b>包到达的那一刻</b>（见 {@link #PacketAnchor}），
     * 而不是"当前时刻减去学到的偏移"。这是本修复最关键的一个选择：以到达时刻为锚，
     * 下列残差在正确实现下<b>结构上不超过 0</b>——{@code collectedAt} 不晚于发送时刻，
     * 发送又必然发生在到达之前。剩下的只有"采集时间舍入到毫秒"这种可忽略的量。
     * 60 秒是给对方的坏钟留的余量：偏移估计本身有网络延迟量级的误差。</p>
     *
     * <p><b>为什么它同时是"伪造收益"的上限</b>：年龄是从到达时刻<b>起算</b>的，
     * 不是从包里的时间戳推算的。所以一个伪造 {@code +1 天} 的时间戳最多只能在到达的那一刻
     * 换到一次"0 岁"，之后 30 秒（默认 3×10s）就滑向过期，与时钟准的后端完全一样。
     * 修复前它是"永远 OK"，因为负年龄被 clamp 成 0 且再也不会前进。</p>
     */
    public static final long MAX_FUTURE_AHEAD_MS = 60_000L;

    /**
     * 愿意用来<b>校正</b>（而不是判为异常）的时钟偏移上限（毫秒，绝对值）。
     *
     * <p>取 10 分钟：</p>
     *
     * <ul>
     *   <li><b>覆盖真实故障</b>：手动改过时间的机器常见偏差是几分钟；10 分钟给
     *       "后端快 5 分钟" 这个典型场景留了一倍余量。</li>
     *   <li><b>不给伪造留宽窗口</b>：偏移是<b>按后端</b>从 {@code sentAt - 到达时刻}
     *       的滑动窗口学出来的，而年龄的基准是到达时刻。所以偏移只用于
     *       "这个时间戳是不是在未来"这一项判定，不能延长任何一条数据的寿命——
     *       把上限压到 10 分钟，同时限制了伪造者能让自己"看起来不超前"的空间。</li>
     *   <li><b>超过 10 分钟的样本整条丢弃</b>：不是"截断到 10 分钟"，是 {@code return}
     *       （见 {@link NetworkView} 的 {@code recordSkewSample}）。越过这个点之后校正值回到 0，
     *       于是<b>快</b>的机器因残差超过 {@link #MAX_FUTURE_AHEAD_MS} 而被判「时钟异常」，
     *       <b>慢</b>的机器则通常落进「数据过期」。
     *       <p>注意这一条<b>不是</b>"超过 10 分钟就显示时钟异常"——那个说法把本常量
     *       （偏移学习窗口）与两个真正的判据（{@link #MAX_FUTURE_AHEAD_MS} 60 秒残差、
     *       {@code NetworkView.MAX_PLAUSIBLE_AGE_MS} 10 年）混成了一句。慢 30 分钟的机器
     *       不会显示时钟异常，它显示「数据过期（可能空服）」。</p></li>
     * </ul>
     *
     * <p>注意越界样本<b>不参与学习</b>（见 {@link NetworkView}）：一个伪造的
     * {@code +1 天} 样本不能把估计值拉走，它只会让该包自己落进"时间戳在未来"的判定。</p>
     */
    public static final long MAX_CORRECTABLE_SKEW_MS = 10L * 60_000L;

    private final String serverId;
    private final long sentAtMs;
    private final long collectedAtMs;
    private final boolean collectorAlive;
    private final Double tps1m;
    private final Double tps5m;
    private final Double tps15m;
    private final Double msptAvg;
    private final int playerCount;
    private final int pushIntervalSec;

    private SummaryPacket(JsonObject o) {
        this.serverId = getString(o, "server", "unknown");
        this.sentAtMs = getLong(o, "sentAt", 0L);
        this.collectedAtMs = getLong(o, "collectedAt", 0L);
        this.collectorAlive = getBool(o, "alive", false);
        this.tps1m = getDoubleOrNull(o, "tps1m");
        this.tps5m = getDoubleOrNull(o, "tps5m");
        this.tps15m = getDoubleOrNull(o, "tps15m");
        this.msptAvg = getDoubleOrNull(o, "msptAvg");
        // 上报间隔由后端随包带来（0 = 旧版后端没提供）。范围校验与后端
        // ConfigManager 的 1–300 一致——越界值不能拿来算过期阈值，否则一个
        // 伪造的极大值会让该后端永远显示"正常"。
        long rawInterval = getLong(o, "pushIntervalSec", 0L);
        this.pushIntervalSec = (rawInterval >= 1 && rawInterval <= 300) ? (int) rawInterval : 0;
        long rawPlayers = getLong(o, "players", COUNT_UNAVAILABLE);
        // 范围校验：-1 是"没采到"的合法哨兵；其余必须落在 [0, MAX_PLAYERS]。
        // 越界值（-99999 或 long→int 截断后的荒谬数字）一律当作"没采到"，
        // 而不是原样显示到面板上。
        this.playerCount = (rawPlayers == COUNT_UNAVAILABLE
            || rawPlayers < 0 || rawPlayers > MAX_PLAYERS)
            ? COUNT_UNAVAILABLE
            : (int) rawPlayers;
    }

    // ---- 访问器 ----

    public String serverId() { return serverId; }
    public long sentAtMs() { return sentAtMs; }
    public long collectedAtMs() { return collectedAtMs; }
    public boolean collectorAlive() { return collectorAlive; }
    /** {@code null} = 该后端这一轮没有 TPS 数据（模块关掉或平台不支持），不是 0。 */
    public Double tps1m() { return tps1m; }
    public Double tps5m() { return tps5m; }
    public Double tps15m() { return tps15m; }
    /** {@code null} = 没有 MSPT 数据。 */
    public Double msptAvg() { return msptAvg; }
    /** {@link #COUNT_UNAVAILABLE} = 没采到；{@code 0} 是合法的空服读数。 */
    public int playerCount() { return playerCount; }

    /**
     * 后端自己的上报间隔（秒）。
     *
     * <p>{@code 0} = 这条包没带这个字段（旧版后端），调用方应回退到默认假设。
     * 非 0 时它是**权威值**：过期判定必须按它算，而不是按写死的 10 秒——否则
     * 服主把间隔调到 60 秒后，面板会有一半时间显示"数据过期"。</p>
     */
    public int pushIntervalSec() { return pushIntervalSec; }

    /**
     * 快照年龄（毫秒），以**包到达那一刻**为基准。
     *
     * <p><b>为什么基准是到达时刻，而不是"当前时刻"。</b>这是本次修复最关键的一处选择，
     * 有两个原因：</p>
     *
     * <ol>
     *   <li><b>伪造买不到寿命。</b>若以"当前时刻"为基准，一条 {@code collectedAt} 被伪造成
     *       未来 1 天的包，在任何时刻都算出负年龄 → 被 clamp 成 0 → <b>永远</b>"正常"，
     *       与修复前完全一样，只是换了个写法。以到达时刻为基准时，年龄从到达那一刻<b>起算</b>：
     *       伪造最多换来刚到时的一次"0 岁"，30 秒后就过期，与诚实后端毫无区别。</li>
     *   <li><b>跨机时钟不同步不再双向失真。</b>{@code age = (arrivedAt + skew) - collectedAt}，
     *       其中 {@code skew} 是从 {@code sentAt - arrivedAt} 学到的偏移。诚实后端下
     *       {@code collectedAt <= sentAt <= arrivedAt + skew}，于是年龄恰好等于
     *       "采集→发送→到达"的真实耗时，与两台机器的钟差无关。后端快 5 分钟不再恒 OK，
     *       慢 5 分钟也不再恒 STALE。</li>
     * </ol>
     *
     * <p><b>为什么 {@code nowMs} 参数还在</b>：过期判定用的是"距最后一次到达过了多久"，
     * 那确实是当前时刻。所以年龄本身由 {@link PacketAnchor} 提供（它记着到达时刻），
     * 而"这条锚点过期了没有"由视图层用当前时刻判。两个量分开算，是"永不永续"与
     * "是否已过期"不再互相污染的前提。</p>
     *
     * @param anchor 到达锚点（到达时刻 + 学到的偏移）；{@code null} 按"刚到达、无偏移"处理
     *               （见下方说明）
     * @return 年龄；{@link #AHEAD_OF_NOW} = 快照时间在未来且超出容差（伪造/坏钟）；
     *         {@link #MISSING_TIME} = 包里没有时间戳
     */
    public long ageMs(PacketAnchor anchor) {
        if (collectedAtMs <= 0L) {
            return MISSING_TIME;
        }
        if (anchor == null) {
            // 没有锚点算不出年龄。这里按"刚到、无偏移"处理（年龄 0），
            // 与 {@link NetworkView#snapshot} 里"锚点缺失时按刚到处理，宁可乐观也不要 NPE"
            // 的降级方向一致：一个缺失的锚点不该让整张面板崩掉。
            // 这条路径生产代码走不到（见 {@link #aheadMs(PacketAnchor)} 的说明）。
            return 0L;
        }
        long ahead = aheadMs(anchor);
        if (ahead > MAX_FUTURE_AHEAD_MS) {
            // 换算到对方时间轴上仍在未来：时间戳被伪造，或对方钟差超过我们愿意校正的范围。
            // 两种情况都意味着"这个数的年龄不可信"，而不是"这个数很新鲜"。绝不能 clamp 成 0。
            return AHEAD_OF_NOW;
        }
        // 把到达时刻搬到对方的时间轴上：对方时钟在"我们收到这一包"的那一刻读作
        // arrivedAt + skew。
        //
        // 符号推导（这是本方法最容易写反的一行）：设真实时刻 T、代理偏移 p、后端偏移 b，
        // 则样本 skew = sentAt(=T+b) - arrivedAt(=T+p) = b - p。所以对方在到达那一刻的读数
        // 是 T+b = (arrivedAt - p) + b = arrivedAt + (b-p) = arrivedAt + skew。
        // 于是 age = (arrivedAt + skew) - collectedAt —— 偏移恰好被抵掉。
        return Math.max(0L, anchor.arrivedAtMs() + anchor.skewMs() - collectedAtMs);
    }

    /**
     * 快照时间比"对方时间轴上这一包的到达时刻"超前多少毫秒（负数 = 快照在过去）。
     *
     * <p>判定 {@link #MAX_FUTURE_AHEAD_MS} 的原始量。单独暴露是为了让测试与视图层
     * 能断言"超前多少"而不是只能看到被归约过的年龄。</p>
     *
     * <p><b>{@code anchor == null} 的处理</b>：javadoc 以前写"null 按 0 校正"，实现却直接
     * {@code anchor.arrivedAtMs()} → NPE。两者必须对齐，而<b>对齐到哪一边是有讲究的</b>：</p>
     *
     * <ul>
     *   <li>抛 NPE 会让整个面板崩掉——一个缺失的锚点不该有这个后果（{@link NetworkView#snapshot}
     *       本来就有"锚点缺失时降级"的分支，它需要一个能用的返回值）。</li>
     *   <li>把 null 当成"到达时刻 = 0、偏移 = 0"是错的：那会让年龄算成
     *       {@code 0 - collectedAt}，一个巨大的负数（→ 判 MISSING_TIME 之外的东西），
     *       荒谬程度不亚于 NPE。</li>
     *   <li>这里选择<b>加 null 守卫、返回"零超前"</b>：没有锚点就没有证据说时间戳在未来，
     *       于是按"不超前"处理（等价于"这一包刚到达、两端同钟"）。这是三条里最保守的
     *       ——它不会凭空判人不可信，也不会崩。</li>
     * </ul>
     *
     * <p>注意生产路径走不到 null：{@code NetworkView} 在 {@code onPacket} 里成对写入 packet
     * 与 anchor，{@code snapshot} 取不到 anchor 时自己造一个。这个守卫只保护外来调用方。</p>
     */
    public long aheadMs(PacketAnchor anchor) {
        if (anchor == null) {
            return 0L;
        }
        return collectedAtMs - (anchor.arrivedAtMs() + anchor.skewMs());
    }

    /** 时间戳缺失（包里没有 {@code collectedAt}，或它是 0/负数）。 */
    public static final long MISSING_TIME = -1L;
    /** 快照时间在未来且超出容差——年龄不可信。 */
    public static final long AHEAD_OF_NOW = -2L;

    /**
     * 一包到达时的锚点：本地到达时刻 + 该后端当时学到的时钟偏移。
     *
     * <p><b>为什么要把它单独做成一个不可变值对象，而不是在 {@code NetworkView} 里现算</b>：
     * 年龄必须<b>冻结在到达那一刻</b>（见 {@link SummaryPacket#ageMs(PacketAnchor)} 的第 1 条
     * 理由），而 {@code NetworkView} 里保存的偏移会随新样本继续变化。如果每次
     * {@code snapshot()} 都用"现在的偏移"重算一个老包，那么偏移漂移会被误读成"数据变新鲜
     * 或变陈旧"——这又是一条静默失真。锚点在 {@code onPacket} 里一次算好，之后只读。</p>
     *
     * @param arrivedAtMs 本地收到该包的时刻
     * @param skewMs      当时学到的偏移（{@code sentAt - arrivedAt} 的滑动最大值；0 = 未知）
     */
    public static final class PacketAnchor {
        private final long arrivedAtMs;
        private final long skewMs;

        public PacketAnchor(long arrivedAtMs, long skewMs) {
            this.arrivedAtMs = arrivedAtMs;
            this.skewMs = skewMs;
        }

        public long arrivedAtMs() { return arrivedAtMs; }
        public long skewMs() { return skewMs; }
    }

    // ---- 解析入口 ----

    /**
     * 只读出包里的协议版本，不做任何校验。
     *
     * <p>用途只有一个：{@link #parse} 失败时把"对方发的到底是哪一版"写进告警日志。
     * 不写这一句的话，日志只能报本端版本，服主看不出该升哪一边——而"只升了一侧"
     * 恰恰是这类丢弃最常见的原因。</p>
     *
     * @param payload 原始字节；读不出来返回 {@code null}
     */
    public static Integer peekVersion(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(
                new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement v = root.getAsJsonObject().get("v");
            if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) {
                return null;
            }
            return v.getAsInt();
        } catch (Exception e) {
            // 连 JSON 都不是：没有版本可读，这本身就是一条信息。
            return null;
        }
    }

    /**
     * 解析一条摘要。
     *
     * @param payload 通道里的原始字节
     * @return 解析结果；{@code null} 表示"这个包不能用"，调用方应丢弃
     */
    public static SummaryPacket parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject o = root.getAsJsonObject();

            // 版本闸门：比自己高的直接拒。低的容忍（旧后端的字段少，但语义是已知的）。
            long version = getLong(o, "v", -1L);
            if (version < 1 || version > PROTOCOL_VERSION) {
                return null;
            }

            // 只处理自己认识的 kind。同一通道将来可能承载别的东西（比如代理→后端的查询）。
            String kind = getString(o, "kind", "");
            if (!"summary".equals(kind)) {
                return null;
            }

            // serverId 长度闸门：留着超长 id 会撑爆堆，也会把面板撑成几 MB 的聊天行。
            String server = getString(o, "server", "");
            if (server.length() > MAX_SERVER_ID_LENGTH) {
                return null;
            }

            return new SummaryPacket(o);
        } catch (Exception e) {
            // 一个坏包不能拖垮代理：丢弃并让调用方决定要不要记日志。
            return null;
        }
    }

    // ---- 防御性读取辅助 ----

    /**
     * 从 JSON 里读一个字符串，并**剔除控制字符**。
     *
     * <p><b>为什么必须过滤</b>：这些值最终会被拼进管理员聊天窗口的一行表格（{@code /pscope
     * network}）。一个后端只要把 {@code server} 写成 {@code "survival\nlobby"}，面板上就会
     * 凭空多出一行——那是纯粹的视觉伪造，管理员会把不存在的服务器当成真的。{@code \r} 同效，
     * {@code \t} 会把列对齐全部打乱（本插件的列宽计算假设没有制表符）。
     * BEL / ESC / NUL 之流还会在部分客户端与终端日志里触发响铃或转义序列。
     * 实测修前 NUL、BEL、{@code \n}、{@code \t}、ESC 全部原样通过。</p>
     *
     * <p><b>为什么是"剔除"而不是"整包拒收"</b>：拒收会让那台后端直接隐身（与 P1-C 的
     * 超长 id 同一个失败形态），而它的数字其实可能是好的。剔除之后 id 仍然可用、面板仍然
     * 能显示它，只是不能再用控制字符伪造排版。失败要可见，不能变成"消失"。</p>
     *
     * <p><b>{@code §}（U+00A7）也剔除。</b>理由：Adventure 的 {@code Component.text()}
     * 本身不解释它，但同一个字符串还会流进 {@code logger}、聊天回放与任何第三方
     * legacy 序列化器——在那些地方 {@code §c} 就是一个颜色码，能让一台后端把管理员的
     * 面板染色、把相邻列"吃掉"。合法的后端名不需要这个字符（{@code survival} /
     * {@code skyblock-a} 这种），剔除的代价近乎为零，而保留的代价是一整类视觉伪造。
     * <b>两端同口径</b>：后端 {@code SummaryPublisher.validateServerId} 用<b>同一条判据</b>
     * （{@code isISOControl} + DEL + {@code §}）但它选择<b>拒发</b>而不是剔除——
     * 因为对后端来说这是"服主配错了"，可以修，而且必须说清是哪个码点；
     * 而对代理来说这多半是"外来包"，没得修，只能剔除后继续用（整包拒收会让那台后端隐身）。</p>
     *
     * <p><b>这里防的是谁</b>：正常部署走不到这条剔除路径（发送端已经拒发了）。
     * 它防的是伪造包、被入侵的后端、以及别的插件往同一通道塞进来的垃圾。</p>
     *
     * <p>剔除集合刻意用"白名单"而不是"黑名单"（{@code Character.isISOControl} 之外还排除
     * 了 U+00A7）：黑名单永远漏，而 id 不需要控制字符。</p>
     *
     * @param o    JSON 对象
     * @param key  字段名
     * @param dflt 缺失 / 类型不对时的默认值
     */
    private static String getString(JsonObject o, String key, String dflt) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return dflt;
        try {
            String raw = e.getAsString();
            return sanitize(raw);
        } catch (Exception ex) {
            return dflt;
        }
    }

    /**
     * 剔除控制字符与 {@code §}。见 {@link #getString} 的注释。
     *
     * <p><b>public 是为了可测</b>：{@code parse} 是唯一入口，但直接断言它的返回值只能是
     * "某条 JSON 解析出来是什么"；把这个判据单独暴露出来，测试才能逐字符覆盖
     * （NUL / BEL / DEL / C1 / U+00A7 / 高位 emoji）而不必拼一堆 JSON 字符串。</p>
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // 先扫一遍：绝大多数 id 是干净的，没有可删字符时原样返回，不产生新对象。
        int firstBad = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (isUnsafeChar(raw.charAt(i))) {
                firstBad = i;
                break;
            }
        }
        if (firstBad < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!isUnsafeChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 这个字符该不该从显示的字符串里剔除。
     *
     * <p>{@code Character.isISOControl} 覆盖 C0（0x00–0x1F）与 C1（0x80–0x9F），
     * 但**不含** DEL（0x7F）与 {@code §}——两者都要单独判。</p>
     */
    private static boolean isUnsafeChar(char c) {
        if (Character.isISOControl(c)) {
            return true;
        }
        // DEL：ISO 控制符表里没有它，但它是 C0 之后那个"删除"控制字符，同样不可见。
        if (c == '\u007F') {
            return true;
        }
        // § (U+00A7)：legacy 格式码前缀。理由见 getString 的注释。
        return c == '§';
    }

    private static long getLong(JsonObject o, String key, long dflt) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return dflt;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            return dflt;
        }
    }

    /** 关键：字段缺失返回 {@code null} 而不是 0——这是与后端哨兵约定的对称实现。 */
    private static Double getDoubleOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        try {
            double v = e.getAsDouble();
            // NaN / Infinity 不是合法读数（后端不该发，但防御一下）：当作"没有值"。
            return (Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean getBool(JsonObject o, String key, boolean dflt) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return dflt;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            return dflt;
        }
    }

    /** 供测试与调试：把解析后的字段摊平，避免测试只能靠 getter 一个个点。 */
    public Map<String, Object> toDebugMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("server", serverId);
        m.put("sentAt", sentAtMs);
        m.put("collectedAt", collectedAtMs);
        m.put("alive", collectorAlive);
        m.put("players", playerCount);
        m.put("tps1m", tps1m);
        m.put("tps5m", tps5m);
        m.put("tps15m", tps15m);
        m.put("msptAvg", msptAvg);
        m.put("pushIntervalSec", pushIntervalSec);
        return m;
    }
}
