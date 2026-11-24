package cn.perfscope.velocity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全网视图 —— 把各后端的最近一次摘要聚合成一屏能看的东西。
 *
 * <p><b>这个类刻意是纯逻辑</b>（不 import 任何 {@code com.velocitypowered.*}）：
 * 状态机与判定规则是唯一值得反复测试的部分，而 Velocity 的 API 在单测里没法实例化。
 * 把"哪些后端健康 / 哪些没数据 / 哪些失联"的判定留在这里，命令层只负责把它渲染成文字。</p>
 *
 * <h2>三种"没有好数字"的状态必须分开</h2>
 *
 * <p>这是本文件存在的主要理由。一个后端在面板上"没有正常数字"，可能是完全不同的三件事：</p>
 *
 * <ul>
 *   <li>{@link State#NO_DATA} —— 从没收到过任何包。典型原因是 plugin messaging 走玩家连接，
 *       该后端<b>空服</b>，通道根本不存在。这不是故障，是方案的固有边界。</li>
 *   <li>{@link State#STALE} —— 收到过，但很久没更新。可能是空服了，也可能是后端卡死/重启中。</li>
 *   <li>{@link State#COLLECTOR_DEAD} —— 后端连上了、包在到，但后端自己报告采集器已经死了。
 *       这时后面的数字都是陈的，<b>必须整条都不显示</b>，否则就是把冻结的数字当现状展示。</li>
 * </ul>
 *
 * <p>把这三者混成一个"-"或"0"，是仪表盘最常见也最致命的谎言：服主看到"-"会以为没问题，
 * 看到"0"会以为服务器挂了，而真相往往是第三种（只是没人玩所以没有转发通道）。</p>
 *
 * <h2>第四种：时间戳不可信（{@link State#CLOCK_SKEW}）</h2>
 *
 * <p>前三态都假定"包里的 {@code collectedAt} 是可信的"。这条假定在两种情况不成立：</p>
 *
 * <ul>
 *   <li><b>时间戳在未来。</b>一旦 {@code now - collectedAt} 为负，旧实现会把它 clamp 成 0，
 *       于是年龄恒为 0、状态恒为 OK —— 一个伪造的 2100 年时间戳能让一台已经死掉的后端
 *       <b>永久</b>显示"正常"。这与最初给 {@code pushIntervalSec} 加 1–300 钳制是同一个理由
 *       （"一个伪造的极大值会让该后端永远显示正常"），只是当时漏了时间戳。</li>
 *   <li><b>两端时钟不同步。</b>后端快 5 分钟 → 年龄恒为 0 → 状态恒 OK，此后后端卡死、
 *       重启、采集停摆，面板仍显示"正常"并展示冻结的数字，直到代理自己的时钟追上来；
 *       反方向（代理快）则是永远 STALE。</li>
 * </ul>
 *
 * <p>两种都归到 {@link State#CLOCK_SKEW}：对使用者来说是同一件事（"这行数字的时间不可信，
 * 去看时钟"），但对诊断是两条不同的线索，所以 {@link Node#clockIssue()} 仍分开记录。</p>
 *
 * <p><b>时钟偏移怎么估</b>：收到包时取样本 {@code sentAt - 收到时刻}。设真实偏移
 * {@code S = 后端钟 - 本机钟}，网络单向延迟 {@code d >= 0}，则样本 {@code = S - d <= S}，
 * 且 {@code d} 最小时样本最接近 {@code S}。所以窗口内取<b>最大值</b>（即 NTP 的
 * min-delay 滤波器），而不是最小值：最小值会把"最慢那一包"的延迟当成时钟偏移，
 * 使年龄系统性偏大（永远偏 STALE）。估计值只用来把本机 {@code now} 搬到对方时间轴上，
 * 差一个延迟量级的误差对"3 倍上报间隔"这种阈值没有影响。</p>
 *
 * <h2>聚合的口径</h2>
 *
 * <p>全网 TPS 只对<b>有真实 TPS 数据且采集器活着</b>的后端求平均——不是对所有后端。
 * 把缺失当 0 参与平均会让全网读数凭空变好（5 个后端里 3 个没数据、2 个是 20 TPS，
 * 若按 5 个算平均值就是 8，看起来像全网告急；按 2 个算才是 20）。</p>
 */
public final class NetworkView {

    /** 判定一个后端状态的阈值：超过 3 倍上报间隔没更新就算 STALE。 */
    public static final int STALE_MULTIPLIER = 3;

    /**
     * 时钟偏移估计的滑动窗口长度（样本数）。
     *
     * <p>16 个样本在默认 10 秒间隔下约覆盖 160 秒：足够滤掉单包的调度抖动与偶发延迟尖峰，
     * 又能在对端时钟被校正（NTP 跳变）后两分多钟内跟上，不会长期卡在旧偏移上。</p>
     */
    public static final int SKEW_WINDOW = 16;

    /** 一个后端在视图里的状态。 */
    public enum State {
        /** 有新鲜数据，采集器活着。 */
        OK,
        /** 有过数据但现在过期了（可能空服，也可能后端没响应）。 */
        STALE,
        /** 还在收到包，但后端自己说采集器已死——数字不可信。 */
        COLLECTOR_DEAD,
        /** 包里的时间戳不可信（在未来 / 缺失），年龄无从判断——**绝不能当 OK**。 */
        CLOCK_SKEW,
        /** 从来没有收到过这个后端的任何包。 */
        NO_DATA
    }

    /** 时间戳不可信的具体原因。 */
    public enum ClockIssue {
        /** 时间戳可用。 */
        NONE,
        /**
         * 校正后仍在未来：时钟不同步到不可校正的程度，或者时间戳是伪造的。
         *
         * <p>**"时间戳缺失"不是时钟问题**：那种包连"后端说是几点"都没有，
         * 归进「时钟异常」会让服主去查 NTP，方向是错的。缺失包仍按旧路径处理
         * （年龄不可知 → 判为 {@link State#STALE}，文案「数据过期」），
         * 因为对使用者来说结论一样是"这行数字不保证是现状"。</p>
         */
        FUTURE,
        /**
         * 校正后仍在**过去**到荒谬的程度（超过 {@link #MAX_PLAUSIBLE_AGE_MS}）：
         * 这个时间戳不是一次真实的采集时刻。
         *
         * <p><b>为什么必须与 FUTURE 分开、又必须也判 CLOCK_SKEW</b>：修复前只有"太新"这一侧
         * 有闸门，于是 {@code collectedAt=1969} 的包算出一个巨大的正年龄，状态仍是 OK、
         * 照常参与全网聚合，面板上给出<b>绿色的 20.00</b> 和「498888小时前」。
         * 结论是错的，而且是往"健康"那一侧错。</p>
         *
         * <p>判成 {@link State#STALE} 也能挡住绿色，但文案会写「可能空服」——
         * 没有任何空服会产出 1969 年的时间戳，那是把"时钟/伪造"说成了"没人玩"，
         * 方向同样是错的。所以走 CLOCK_SKEW，让人去查那台机器的时钟。</p>
         */
        PAST
    }

    /** 单个后端在某一时刻的视图。 */
    public static final class Node {
        private final String serverId;
        private final State state;
        private final SummaryPacket packet;
        private final long ageMs;
        private final long silentMs;
        private final ClockIssue clockIssue;
        private final long aheadMs;

        Node(String serverId, State state, SummaryPacket packet, long ageMs, long silentMs,
             ClockIssue clockIssue, long aheadMs) {
            this.serverId = serverId;
            this.state = state;
            this.packet = packet;
            this.ageMs = ageMs;
            this.silentMs = silentMs;
            this.clockIssue = clockIssue;
            this.aheadMs = aheadMs;
        }

        public String serverId() { return serverId; }
        public State state() { return state; }
        /** 原始包；{@link State#NO_DATA} 时为 {@code null}。 */
        public SummaryPacket packet() { return packet; }

        /**
         * **锚定的**数据年龄：这一包在到达的那一刻，它自己有多旧（采集 → 发送 → 到达的耗时）。
         *
         * <p><b>它不是"这块数据现在多旧"，而且它不会随时间增长</b>——锚点在
         * {@code onPacket} 里按到达时刻冻结（见 {@link SummaryPacket.PacketAnchor}），
         * 因为让年龄"随现在时刻前进"正是 P1-V 要堵的形状（伪造未来时间戳能换来永久 0 岁）。
         * 代价是：一台一小时没上报的后端，这个数仍停在它刚到时的 0.3 秒。</p>
         *
         * <p>所以要显示"多久没数据"必须用 {@link #dataAgeMs()} 而不是它。
         * 这个访问器保留原义，给"测量延迟本身"这件事用（例如诊断链路是否很慢）。
         * 不可判定时（{@link State#NO_DATA} / {@link State#CLOCK_SKEW}）为 {@link Long#MAX_VALUE}。</p>
         */
        public long ageMs() { return ageMs; }

        /**
         * 距最后一次收到这一包的时长（毫秒）——"这个后端还在说话吗"。
         *
         * <p>STALE / COLLECTOR_DEAD 的判据用的就是它，显示层也应该用它：
         * 一台静默 60 分钟的后端，锚定年龄恒为 0.3 秒，直接显示出来就是
         * 「0秒前」——把"一小时没有数据"写成"刚刚才采的"。</p>
         */
        public long silentMs() { return silentMs; }

        /**
         * **这块数据现在有多旧** = 静默时长 + 锚定年龄。
         *
         * <p>推导：数据在 {@code collectedAt} 被采集，在 {@code arrivedAt} 到达（那时它已经
         * {@code ageMs} 旧了），此后又静默了 {@code silentMs}。所以"现在"距采集时刻
         * 恰好是两者之和。它是**唯一**一个对三种情形都成立的单一数字：</p>
         *
         * <ul>
         *   <li>正常上报：静默 ≈ 0～3×间隔，年龄 ≈ 采集延迟，合计 ≈ 数据真实年龄（几十毫秒到几十秒）。</li>
         *   <li>后端停摆一小时：静默 3600 秒 + 年龄 0.3 秒 → 「1小时前」。这正是要修的那条。</li>
         *   <li>采集器死了但包还在到（{@code collectedAt} 冻结、{@code arrivedAt} 前进）：
         *       年龄自己在长，静默 ≈ 0，合计仍然正确。</li>
         * </ul>
         *
         * <p>只显示 {@code ageMs} 会在前两种情形说谎（恒 0）；只显示 {@code silentMs}
         * 会在第三种说谎（恒 0）。相加是唯一不需要按状态分支、也不说谎的口径。</p>
         *
         * <p>时间戳不可信时为 {@link Long#MAX_VALUE}（渲染成「—」），
         * {@link State#NO_DATA} 时同理。</p>
         */
        public long dataAgeMs() {
            if (ageMs == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            // 锚定年龄可能是负数哨兵（缺失 / 未来）——那时"数据多旧"无从计算，
            // 不能拿哨兵值参与相加，否则 -1 会让显示年轻一毫秒（数值错得很轻微，
            // 但错就是错）。这部分按 0 计，静默时长仍然是可信的下界。
            long anchored = ageMs > 0L ? ageMs : 0L;
            long sum = silentMs + anchored;
            // 静默时长与年龄都来自 long，极端输入下可能溢出成负数。夹回 MAX 表示"算不出来"，
            // 而不是让一个负年龄渲染成"0秒前"——那正是本类要防的那句话。
            return sum < 0L ? Long.MAX_VALUE : sum;
        }

        /** 时间戳的问题（{@link State#CLOCK_SKEW} 之外的节点恒为 {@link ClockIssue#NONE}）。 */
        public ClockIssue clockIssue() { return clockIssue; }
        /**
         * 快照时间比校正后的本机时间超前多少毫秒。
         *
         * <p>只在 {@link ClockIssue#FUTURE} 时有意义——面板要写出"超前多少"，
         * 否则服主只知道"有异常"而不知道该把时钟往回调多少。其余情况为 0。</p>
         */
        public long aheadMs() { return aheadMs; }

        /** 这个后端能不能参与聚合：状态 OK 才算数。 */
        public boolean contributesToAggregate() {
            return state == State.OK;
        }
    }

    /** 最多跟踪多少个后端。超出时淘汰最久没更新的那个。 */
    public static final int MAX_TRACKED_SERVERS = 256;

    /**
     * 一次读数"还可能是一次测量"的上限（TPS）。
     *
     * <p><b>为什么需要这个上限</b>：{@code tps1m} 完全来自网络输入。后端正常上限是 20，
     * Spigot/Tuinity 的 {@code /tick sprint} 能把它顶到几百，所以开到 20 会把合法的高速
     * 读数误判成垃圾；但 {@code 1e308} 这种值只可能是坏包或伪造包。它一旦进了面板会有两个
     * 后果：该格字符串变成 309 个字符，把整列宽度撑到 312 格（**所有行**一起错位，
     * 不只是坏行），以及把 {@link #averageTps1m} 的全网平均值污染成 ≈1e308。</p>
     *
     * <p>判据只看"物理上是否可能"，不看"是否健康"：越界值不是一次 TPS 测量，
     * 所以按"没有值"处理，而不是截断成 1000 假装测到了 1000。截断会凭空造出一个读数，
     * 比"—"更会误导。下界同理：{@code 0} 是"服务器冻结"的真实读数（必须照常判红），
     * 负数则不是读数。{@link SummaryPacket} 的解析不做这层判断——它管"能不能解析"，
     * 这里管"能不能当读数用"。</p>
     */
    public static final double TPS_PLAUSIBLE_MAX = 1000.0;

    /**
     * 一次读数"还可能是一次测量"的上限（MSPT，毫秒）。
     *
     * <p>取 60000 而不是 1000：单 tick 卡住几秒是真实且必须被看见的故障（那时 MSPT 是
     * 3000、5000，都该显示成红色），把它当"没有值"等于在最糟的时候把面板变灰。
     * 一分钟/tick 已经是"服务器事实上不工作了"的上限，再大只能是坏包。</p>
     *
     * <p>下界是 {@code > 0}：{@code 0} 与负数是后端 {@code msptAvg} 的"未采集"哨兵
     * （见后端 {@code ThresholdColors} 对 MSPT 的 {@code value <= 0 → UNKNOWN}）。</p>
     */
    public static final double MSPT_PLAUSIBLE_MAX = 60_000.0;

    /** TPS 读数是否在物理可能范围内。 */
    public static boolean isPlausibleTps(double v) {
        // 写成"取反"而不是连写两个比较：NaN 与任何数比较都是 false，
        // 连写会让 NaN 悄悄通过（NaN 已经是"不是值"，必须挡在外面）。
        return v >= 0.0 && v <= TPS_PLAUSIBLE_MAX;
    }

    /** MSPT 读数是否在物理可能范围内。 */
    public static boolean isPlausibleMspt(double v) {
        return v > 0.0 && v <= MSPT_PLAUSIBLE_MAX;
    }

    /**
     * 已收到的最近一包，key 是 serverId。
     *
     * <p><b>必须是并发容器</b>：写入来自 Netty 的 event-loop 线程（每条后端连接可能在
     * 不同的 loop 上），读取来自命令执行线程。裸 {@code LinkedHashMap} 在这种用法下会
     * 抛 {@code ConcurrentModificationException}（管理员执行命令直接失败），
     * 并在并发扩容时丢条目或产生重复 key——后者会让面板显示的数字与实际不符，
     * 是这里最不该出现的一种错。</p>
     */
    private final Map<String, SummaryPacket> latest =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 每个后端的时钟偏移样本窗口（{@code sentAt - 收到时刻}，毫秒）。
     *
     * <p>**必须与 {@link #latest} 分开存、且同样并发安全**：偏移是按<b>后端</b>估的
     * （每台后端的时钟各自独立），而条目淘汰时两者要一起清，否则一个被淘汰的 id
     * 会在偏移表里留下永久垃圾——那本身又是一条可被恶意 id 撑爆堆的路径。
     * 用 {@code ArrayDeque} 保存最近 {@link #SKEW_WINDOW} 个样本，
     * 取最大值即 min-delay 估计（见类注释）。</p>
     */
    private final Map<String, java.util.ArrayDeque<Long>> skewSamples =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 每个后端最近一包的到达锚点（到达时刻 + 当时学到的偏移）。
     *
     * <p><b>为什么锚点要在 onPacket 里冻结，而不是在 snapshot 里现算</b>：年龄必须锚定在
     * "这一包到达的那一刻"，否则一个伪造未来时间戳的包会在<b>任何</b>时刻都算出负年龄、
     * 被 clamp 成 0 岁，从而永久"正常"（这正是要修的 P1-V）。另见
     * {@link SummaryPacket.PacketAnchor} 的说明：用"现在的偏移"重算老包会让偏移漂移被
     * 误读成数据变新鲜。</p>
     */
    private final Map<String, SummaryPacket.PacketAnchor> anchors =
        java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 收到一条摘要。同一个 serverId 后来者覆盖前者（只保留最近一次）。
     *
     * <p>条目数上限 {@link #MAX_TRACKED_SERVERS}：serverId 完全来自网络输入，
     * 不设上限时一台恶意/被入侵的后端可以用不断变化的 id 把堆填满
     * （实测约 500 个 2MB 的 id 就能吃满 1G 堆）。超限时淘汰**最久没更新**的那条，
     * 而不是拒绝新的：拒绝新 id 会让一台刚重启的后端永远显示不出来。</p>
     *
     * @param packet 解析成功的包；{@code null} 会被忽略
     */
    public void onPacket(SummaryPacket packet) {
        onPacket(packet, System.currentTimeMillis());
    }

    /**
     * 收到一条摘要，并显式给出"收到它的那一刻"。
     *
     * <p><b>为什么需要显式时间</b>：年龄的基准就是这一包的到达时刻，偏移样本也是
     * {@code sentAt - 到达时刻}。用 {@link System#currentTimeMillis()} 在测试里无法施加
     * "后端快 5 分钟 / 慢 5 分钟 / 伪造未来"，而这三条正是本次修复的核心场景。
     * 把时间作为参数传进来，整条判定链就能被回放。</p>
     *
     * @param packet  解析成功的包；{@code null} 会被忽略
     * @param receivedAtMs 收到该包的本地时刻
     */
    public void onPacket(SummaryPacket packet, long receivedAtMs) {
        if (packet == null) {
            return;
        }
        synchronized (latest) {
            if (!latest.containsKey(packet.serverId()) && latest.size() >= MAX_TRACKED_SERVERS) {
                // LinkedHashMap 的插入序即"第一次出现的顺序"；淘汰头部最久未见的。
                java.util.Iterator<String> it = latest.keySet().iterator();
                if (it.hasNext()) {
                    String evicted = it.next();
                    it.remove();
                    // 偏移样本与锚点必须一起清：留着就是一条被恶意 id 撑爆堆的路径。
                    skewSamples.remove(evicted);
                    anchors.remove(evicted);
                }
            }
            // 先采样本再取估计值：这一包自己的样本也要参与，否则客户端刚建立连接时
            // 第一包永远用 0 偏移判"是否在未来"，一台钟快 5 分钟的后端会立刻被误报。
            recordSkewSample(packet, receivedAtMs);
            // 重新插入以刷新插入序，让淘汰顺序真正反映"最近是否活跃"。
            latest.remove(packet.serverId());
            latest.put(packet.serverId(), packet);
            anchors.put(packet.serverId(),
                new SummaryPacket.PacketAnchor(receivedAtMs, skewMs(packet.serverId())));
        }
    }

    /**
     * 采一个时钟偏移样本。
     *
     * <p>只收"有意义"的样本：{@code sentAt} 缺失（旧版包 / 外来包）、以及绝对值超过
     * {@link SummaryPacket#MAX_CORRECTABLE_SKEW_MS} 的样本都不参与。后者是关键——
     * 一个伪造的 {@code +1 天} 时间戳如果被学进去，估计值会被拉到一天量级，
     * 于是<b>所有</b>其他后端的年龄一起失真。让越界样本在入口就被丢弃，
     * 伪造者就只能影响它自己那一条包。</p>
     */
    private void recordSkewSample(SummaryPacket packet, long receivedAtMs) {
        long sentAt = packet.sentAtMs();
        if (sentAt <= 0L) {
            return;
        }
        long sample = sentAt - receivedAtMs;
        if (Math.abs(sample) > SummaryPacket.MAX_CORRECTABLE_SKEW_MS) {
            return;
        }
        java.util.ArrayDeque<Long> q = skewSamples.get(packet.serverId());
        if (q == null) {
            q = new java.util.ArrayDeque<>(SKEW_WINDOW);
            skewSamples.put(packet.serverId(), q);
        }
        if (q.size() >= SKEW_WINDOW) {
            q.pollFirst();
        }
        q.addLast(sample);
    }

    /**
     * 该后端的时钟偏移估计（毫秒）；{@code 0} = 没有样本，按"两端时间一致"处理。
     *
     * <p>取窗口内<b>最大值</b>而不是最小值或平均：样本恒 {@code <= 真实偏移}
     * （延迟只会让 {@code sentAt} 看起来更晚），最大值最接近真实偏移。平均值会把
     * 平均延迟算成时钟偏移，使年龄系统性偏小、面板整体偏"新鲜"；最小值则相反，
     * 会让一台时钟准确的后端永远显示偏旧。NTP 的 min-delay 滤波器取的是同一口径。</p>
     */
    public long skewMs(String serverId) {
        synchronized (latest) {
            java.util.ArrayDeque<Long> q = skewSamples.get(serverId);
            if (q == null || q.isEmpty()) {
                return 0L;
            }
            long max = Long.MIN_VALUE;
            for (Long v : q) {
                if (v > max) max = v;
            }
            return max == Long.MIN_VALUE ? 0L : max;
        }
    }

    /**
     * 这个后端快照时间比"该包的到达时刻（已扣偏移）"超前多少毫秒；用于诊断"快了多少"。
     */
    public long aheadMs(String serverId) {
        synchronized (latest) {
            SummaryPacket p = latest.get(serverId);
            SummaryPacket.PacketAnchor a = anchors.get(serverId);
            return (p == null || a == null) ? 0L : p.aheadMs(a);
        }
    }

    /** 已知的后端 id 集合（收到过包的）。返回快照副本，避免调用方与写入线程竞争。 */
    public java.util.Set<String> knownServers() {
        synchronized (latest) {
            return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(latest.keySet()));
        }
    }

    /** 当前跟踪的后端条数（给配置/诊断用）。 */
    public int trackedCount() {
        return latest.size();
    }

    /**
     * 当前锚点表的条数（给测试与诊断用）。
     *
     * <p><b>为什么要暴露这个数字</b>：`anchors` 是与 `latest` 成对写入的第二张表，
     * "两张表必须同生同灭"是一条<b>结构性质</b>，但没有任何生产路径会读它的大小——
     * 于是漏清（本次实测的那条 bug）在行为上完全不可见：面板照常工作，只是表在长。
     * 只有把大小暴露出来，测试才能钉住"clear 之后两张表都为空"。</p>
     */
    public int anchorCount() {
        return anchors.size();
    }

    /**
     * 计算当前视图。
     *
     * @param nowMs            当前时间（用来判"最后一次到达已经过去多久"）
     * @param pushIntervalSec  默认上报间隔（秒）；包自带间隔时以包为准
     * @param expectedServers  配置里列出的后端 id；这些即使没收到包也要出现在视图里
     *                         （显示为 {@link State#NO_DATA}），否则"少了一台"这件事看不出来
     * @return 按 id 排序的节点列表
     */
    public List<Node> snapshot(long nowMs, int pushIntervalSec, java.util.Collection<String> expectedServers) {
        // 先把 latest / anchors 的副本拿出来，之后的计算全在副本上做。
        // 这样即使写入线程此刻正在 put，也不会让下面的迭代抛 ConcurrentModificationException。
        java.util.Map<String, SummaryPacket> copy;
        java.util.Map<String, SummaryPacket.PacketAnchor> anchorCopy = new java.util.LinkedHashMap<>();
        synchronized (latest) {
            copy = new LinkedHashMap<>(latest);
            anchorCopy = new LinkedHashMap<>(anchors);
        }

        // 先收集要展示的全部 id：收到过包的 ∪ 配置里声明的。
        // 并集很重要——只看收到过包的话，一台从没上报过的后端会彻底隐身，
        // 而"少了哪一台"正是这个视图最该回答的问题。
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (expectedServers != null) {
            ids.addAll(expectedServers);
        }
        ids.addAll(copy.keySet());

        long fallbackStaleMs = Math.max(1, pushIntervalSec) * 1000L * STALE_MULTIPLIER;

        List<Node> out = new ArrayList<>();
        for (String id : ids) {
            SummaryPacket p = copy.get(id);
            if (p == null) {
                // NO_DATA：连"静默多久"都无从谈起（从没到过），两个年龄都给 MAX（渲染成 —）。
                out.add(new Node(id, State.NO_DATA, null, Long.MAX_VALUE, Long.MAX_VALUE,
                    ClockIssue.NONE, 0L));
                continue;
            }
            SummaryPacket.PacketAnchor anchor = anchorCopy.get(id);
            if (anchor == null) {
                // 理论不可达（两者在同一把锁里成对写入）；给一个不致命的降级：
                // 按"刚到"处理，宁可显示得乐观一点也不要 NPE 掉整个面板。
                anchor = new SummaryPacket.PacketAnchor(nowMs - 1L, 0L);
            }
            long ahead = p.aheadMs(anchor);
            long age = p.ageMs(anchor);
            // 过期阈值按这台后端**自己**的间隔算（包里有就用包的，没有才回退默认）。
            // 否则服主把间隔调大到 60 秒后，每两轮上报之间都会被误判成"数据过期"。
            long staleMs = p.pushIntervalSec() > 0
                ? p.pushIntervalSec() * 1000L * STALE_MULTIPLIER
                : fallbackStaleMs;
            // ⚠️ 过期判据是"**这块数据现在多旧**"，不是"最后一次到达过了多久"，也不是包里的年龄。
            //
            // 三个量必须分开，正是本次修复的核心：
            //   - 锚定年龄 age（collectedAt 距到达时刻有多久）回答"这条读数采于多久以前"。
            //     它锚定在到达时刻，所以伪造时间戳最多换来刚到时的一次 0 岁，之后不再前进，
            //     也就骗不到"永续正常"。**但它不随时间增长**，不能单独用来判过期。
            //   - 距最后一次到达的时长 silentMs 回答"这个后端还在说话吗"。
            //     它随时间增长，但看不见"包到的时候就已经很旧"这种情形。
            //   - dataAge = silentMs + age 才是"这块数据现在多旧"，两者都覆盖。
            //
            // 旧实现把三者混成一个 clamp 过的数，于是任何一个方向的时间戳异常
            // 都能让后者永远算成 0。
            //
            // 为什么用 dataAge 而不是只用 silentMs：一个 collectedAt 比现在早 10 年、
            // 但刚刚到达的包，silentMs ≈ 0 —— 只看它就会判 OK 并给它绿色，而那块数据
            // 明明已经十年没更新了。dataAge 把这一段也算进去，且**不会削弱 P1-V**：
            // 伪造未来时间戳时 age 被夹到 0，dataAge 退化成 silentMs，与修复后的行为一致。
            long silentMs = Math.max(0L, nowMs - anchor.arrivedAtMs());
            // 负数（缺失 / 未来）不参与相加：那些情形由下面的分支单独定性。
            long anchoredForAge = age > 0L ? age : 0L;
            long dataAgeForGate = silentMs + anchoredForAge;
            // 顺序是**判定语义**，不是随便排的：
            //   1. 时间戳在未来 —— 优先于一切。旧实现把负年龄 clamp 成 0，
            //      于是一个 2100 年的时间戳 = 年龄恒 0 = 状态恒 OK，一台死掉的后端
            //      能在面板上**永久**"正常"。现在它落到 CLOCK_SKEW。
            //   2. COLLECTOR_DEAD —— 包还在到，说明链路通，问题在后端采集器。
            //   3. STALE / OK —— 用 silentMs 判，与时间戳内容无关。
            // 注意"时间戳缺失"不在这里特判：它走 STALE 路径（结论是「数据过期」），
            // 因为那不是时钟问题，不该把人指向 NTP。
            State s;
            ClockIssue issue = ClockIssue.NONE;
            long shownAhead = 0L;
            if (p.collectedAtMs() <= 0L) {
                // 没有（或荒谬的负数）时间戳：年龄无从判断，保守判为过期。
                //
                // 这一条必须**先**于下面的 `age < 0` 判：SummaryPacket 用负数哨兵表示
                // "时间戳缺失/不可用"，若先判负数就会被误读成"时间戳在未来"，
                // 于是所有旧版包都会挂上「时钟异常」——那是把"缺数据"说成了"时钟坏"，
                // 方向完全错的诊断。缺失包的结论仍然是「数据过期」，与修复前一致。
                s = State.STALE;
            } else if (age < 0L) {
                // 换算到对方时间轴上仍在未来超过 MAX_FUTURE_AHEAD_MS：时间戳伪造，或
                // 时钟偏差超出我们愿意校正的 10 分钟。两种情况都不可信，且都不许读成 0 岁。
                s = State.CLOCK_SKEW;
                issue = ClockIssue.FUTURE;
                shownAhead = ahead;
            } else if (!p.collectorAlive()) {
                // COLLECTOR_DEAD 先于"太旧"判：包还是新鲜的，只是后端自己说采集器死了。
                // 这个结论比"时间戳可疑"更具体、更可行动，不该被后者抢走。
                s = State.COLLECTOR_DEAD;
            } else if (age > MAX_PLAUSIBLE_AGE_MS) {
                // 太旧：超过了"这还可能是一次真实采集"的下限合理性（见 MAX_PLAUSIBLE_AGE_MS）。
                //
                // 修复前只有"太新"这一侧有闸门：collectedAt=1969 的包算出一个巨大的正年龄，
                // 状态照常 OK、参与全网平均，面板上给出**绿色的 20.00** 和「498888小时前」。
                // 那个数字来自一条根本不是测量的时间戳，却因为"是正数"被一路信任。
                //
                // 判 CLOCK_SKEW 而不是 STALE，是因为文案要说准：没有任何空服会产出 1969 年的
                // 时间戳，写成「可能空服」等于把人指向"没人玩"，而真正该看的是那台机器的时钟。
                s = State.CLOCK_SKEW;
                issue = ClockIssue.PAST;
                // 渲染层用 `-ahead` 表示"落后多少"，所以这里必须给**负值**。
                //
                // 这一行此前漏了，`shownAhead` 于是停在上面的初始值 0，
                // `clockDetail` 走 PAST 分支时渲染成「落后 0秒（时间戳在过去）」——
                // 方向是对的，量永远是 0，等于把这句诊断最有用的那一半丢了。
                // 实测：collectedAt=1 与 collectedAt=现在-11年 都渲染成「落后 0秒」。
                //
                // 取负的 age：age 是"快照比到达时刻早多少"，正是该往前拨的量。
                shownAhead = -age;
            } else if (dataAgeForGate > staleMs) {
                s = State.STALE;
            } else {
                s = State.OK;
            }
            // 年龄格显示什么，与"用什么判过期"是两个问题——修 P1-V 时把它们混在一起过。
            //
            // 历史教训（本批的回归）：判定已经改用 silentMs，显示却还在用 age。age 锚定在
            // 到达时刻，所以**几乎恒为 0**：静默 31 秒 / 120 秒 / 3600 秒的后端，行上全写
            // 「0秒前（可能空服）」，采集器死一小时后写「0秒前（数字已冻结）」——
            // 正是"把已经一小时没数据写成 0 秒前"这类谎报，而且出现在最需要看年龄的两行。
            //
            // 现在：渲染层用 {@link Node#dataAgeMs()} = 静默时长 + 锚定年龄，三种状态都对。
            // 这里仍把两个分量分开存，渲染层才能按状态挑选措辞（例如 OK 行只写数据年龄）。
            long shownAge = age;
            if (s == State.CLOCK_SKEW) {
                // 时间戳不可信时年龄本身没有意义：给 MAX，渲染层写 —，
                // 绝不显示一个看起来精确的"0 秒前"（那正是伪造者想换来的那句话）。
                shownAge = Long.MAX_VALUE;
            } else if (p.collectedAtMs() <= 0L) {
                // 拿不到时间戳的包（旧版/外来包）。它的静默时长是可信的，但它自报的
                // collectedAt=0 会让年龄算成"很久以前"。锚定年龄部分按"不可知"处理
                // （dataAgeMs 会把它当 0），于是显示的就是静默时长这个可信下界。
                shownAge = SummaryPacket.MISSING_TIME;
            }
            out.add(new Node(id, s, p, shownAge, silentMs, issue, shownAhead));
        }

        out.sort(Comparator.comparing(Node::serverId));
        return out;
    }

    /**
     * 全网 TPS 平均。
     *
     * <p>只统计 {@link State#OK} 且真有 {@code tps1m} 的后端。没有任何可用数据时返回
     * {@code null} 而不是 0——0 在这里是一个灾难性的误读。</p>
     *
     * <p><b>量级不合法的读数必须排除，不只是不显示</b>：{@link #isPlausibleTps} 挡下的
     * {@code 1e308} 若参与求平均，会把整个网络的读数污染成 ≈1e308——面板上每一格都写着
     * "正常"，只有一个数字是天文数字。渲染层挡它只能保护"那一格"，聚合层还得再挡一次，
     * 因为这是两个不同的出口。判据共用一个函数，两处不会漂移。</p>
     */
    public static Double averageTps1m(List<Node> nodes) {
        double sum = 0;
        int n = 0;
        for (Node node : nodes) {
            if (!node.contributesToAggregate()) continue;
            Double v = node.packet().tps1m();
            if (v == null || !isPlausibleTps(v)) continue;
            sum += v;
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /**
     * 全网在线玩家合计，**且知道自己缺了什么**。
     *
     * <p><b>为什么不能只返回一个 int</b>：合计只收 {@link State#OK} 的后端，这是对的
     * （把过期数字混进来等于编一个总数）。但只给了个数字，读者会把它当成"全服在线人数"。
     * 一台 OK(10 人) + 一台 STALE(50 人) 的网络上，面板写"在线合计 <b>10</b>"，
     * 管理员读到的却是"全服只有 10 人"——<b>差了一个数量级，而且看不出差在哪</b>。
     * 这正是本项目反复要修的那类"没有说谎的字，但结论是错的"。</p>
     *
     * <p>所以返回三样东西：确认计入的数、已知但未计入的数、以及"连数都不知道"的后端个数。
     * 渲染层据此写出「10（+50 未计入）」，读者不需要自己去数状态行。</p>
     *
     * <p><b>为什么记 {@code uncountedUnknown} 而不只是"有/没有"</b>：一个没有数、
     * 一个有一万个数，对"这个合计有多可信"的影响完全不同；写"部分未计入"时最好能说几台，
     * 否则读者只能猜。</p>
     */
    public static final class PlayerTotal {
        private final Integer counted;
        private final int uncountedKnown;
        private final int uncountedUnknown;

        PlayerTotal(Integer counted, int uncountedKnown, int uncountedUnknown) {
            this.counted = counted;
            this.uncountedKnown = uncountedKnown;
            this.uncountedUnknown = uncountedUnknown;
        }

        /** 确认计入的合计；一台可计入的都没有时为 {@code null}（不是 0）。 */
        public Integer counted() { return counted; }

        /** 已知读数但没计入的玩家数之和（来自非 OK 状态的后端）。 */
        public int uncountedKnown() { return uncountedKnown; }

        /** 连读数都没有、因而无从计入的后端个数。 */
        public int uncountedUnknown() { return uncountedUnknown; }

        /** 这个合计是否覆盖了全部后端（{@code false} = 必须在面板上标出来）。 */
        public boolean isComplete() {
            return uncountedKnown == 0 && uncountedUnknown == 0;
        }
    }

    /**
     * 全网在线玩家数合计。没采到（{@code -1}）的后端不参与，与 TPS 同口径。
     *
     * <p>结果同时带回"没计入多少"，见 {@link PlayerTotal} 的说明。</p>
     *
     * <p><b>{@link State#NO_DATA} 不计入"未计入"</b>，这是有意的：这条链路走玩家连接，
     * 一台从没上报过的后端<b>几乎必然是空服</b>（没有玩家 → 没有通道）。若把它也算作
     * "合计不完整"，那么每个有若干空服后端的网络都会永远带着这个标记，标记就此贬值——
     * 真正需要它的时候（一台有 50 人的后端失联）反而没人看。状态行里的「无数据 N」
     * 已经承担了"哪几台没说话"这件事。</p>
     */
    public static PlayerTotal totalPlayers(List<Node> nodes) {
        int sum = 0;
        int n = 0;
        int uncountedKnown = 0;
        int uncountedUnknown = 0;
        for (Node node : nodes) {
            if (node.state() == State.NO_DATA) {
                continue; // 见上面的说明：空服的正常形态，不算"缺失"
            }
            int v = node.packet().playerCount();
            boolean known = v != COUNT_UNAVAILABLE;
            if (node.contributesToAggregate()) {
                if (known) {
                    sum += v;
                    n++;
                } else {
                    uncountedUnknown++;
                }
            } else if (known) {
                // STALE / COLLECTOR_DEAD / CLOCK_SKEW：有读数，但时间或采集器不可信，
                // 所以不进合计——只是把金额记下来告诉读者"另有这么多没算"。
                uncountedKnown += v;
            } else {
                uncountedUnknown++;
            }
        }
        return new PlayerTotal(n == 0 ? null : sum, uncountedKnown, uncountedUnknown);
    }

    /**
     * 与 {@link SummaryPacket#COUNT_UNAVAILABLE} 同值。
     *
     * <p>单独暴露一个常量而不是直接引用 SummaryPacket 的，是为了让这个类在没有
     * SummaryPacket 的情况下也能被理解——它是"视图层的约定"，两者数值必须一致，
     * 有测试钉住。</p>
     */
    public static final int COUNT_UNAVAILABLE = SummaryPacket.COUNT_UNAVAILABLE;

    /** 有几个后端处于某个状态。给汇总行用。 */
    public static int count(List<Node> nodes, State state) {
        int n = 0;
        for (Node node : nodes) {
            if (node.state() == state) n++;
        }
        return n;
    }

    /**
     * 一条读数"还可能是一次真实采集"的年龄上限（毫秒）。
     *
     * <p><b>为什么需要这条下限闸门</b>：P1-V 只补了"太新"那一侧（{@link SummaryPacket#MAX_FUTURE_AHEAD_MS}），
     * 于是 {@code collectedAt=1969} 这类值算出一个巨大的<b>正</b>年龄，一路通过所有判据：
     * 状态 OK、参与全网平均、面板上给出绿色的 20.00，年龄格写「498888小时前」。
     * 一个明显不是测量的时间戳，只因为"结果是正数"就被当成事实。</p>
     *
     * <p><b>为什么取 10 年</b>：
     * <ul>
     *   <li>它必须大到不影响任何真实部署：一台正常上报的后端，年龄是<b>毫秒到秒</b>级；
     *       就算后端整机宕机一年再恢复，它的第一包也只会有"一年"的年龄（且那时早就 STALE 了，
     *       不会走到这条判据之外的路径）。10 年留了十倍余量，不存在误伤。</li>
     *   <li>它必须小到能挡住"纪元零点"这类值：{@code collectedAt=0} 由
     *       {@link SummaryPacket#MISSING_TIME} 那条分支单独处理；而 1969 / 1970 /
     *       1970 之前任何值都会落在 10 年之外。</li>
     *   <li>10 年是<b>可解释</b>的：它对应"这个插件不可能有这么老的运行数据"
     *       （PerfScope 自身的版本历史还不到一年）。用一个"宇宙尺度"的数字（比如 100 年）
     *       会让人以为判据是随便取的；用"一天"又会误伤"后端宕机一周后重启、
     *       代理缓存里还留着老包"这种真实但少见的形态。</li>
     * </ul></p>
     *
     * <p>超限判 {@link State#CLOCK_SKEW}（{@link ClockIssue#PAST}）而不是 STALE：
     * 见 {@link ClockIssue#PAST} 的说明——STALE 的文案「可能空服」会把方向指错。</p>
     */
    public static final long MAX_PLAUSIBLE_AGE_MS = 10L * 365L * 24L * 60L * 60L * 1000L;

    /** 清空。给"重新开始统计"用；调用点见 {@code NetworkCommand} 的 {@code reset} 子命令。 */
    public void clear() {
        synchronized (latest) {
            latest.clear();
            // 偏移样本一起清：reset 的语义是"从头开始"，留着旧偏移会让下一包立刻
            // 带着上一轮的估计值，且被 reset 的 id 从此在偏移表里永久驻留。
            skewSamples.clear();
            // ⚠️ 锚点也必须清。这一条是实测漏掉的：正常路径下 anchors 跟着 latest 的
            // LRU 淘汰一起删（见 onPacket），但 clear() 走的是另一条路径，两个兄弟表清了、
            // 它没清。于是每次 reset 留下当轮全部 id 的锚点，实测 10 次 reset 后
            // 表里常驻 2560 条——一张网络输入驱动、只会增长的表。
            //
            // 后果不只是内存：残留的锚点会让"重新开始统计"后的第一包沿用旧到达时刻，
            // 静默时长算出一个远大于真实值的数，刚 reset 完就显示「数据过期」。
            anchors.clear();
        }
    }
}
