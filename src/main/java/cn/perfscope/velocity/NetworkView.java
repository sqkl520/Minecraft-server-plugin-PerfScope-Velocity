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
 * <h2>聚合的口径</h2>
 *
 * <p>全网 TPS 只对<b>有真实 TPS 数据且采集器活着</b>的后端求平均——不是对所有后端。
 * 把缺失当 0 参与平均会让全网读数凭空变好（5 个后端里 3 个没数据、2 个是 20 TPS，
 * 若按 5 个算平均值就是 8，看起来像全网告急；按 2 个算才是 20）。</p>
 */
public final class NetworkView {

    /** 判定一个后端状态的阈值：超过 3 倍上报间隔没更新就算 STALE。 */
    public static final int STALE_MULTIPLIER = 3;

    /** 一个后端在视图里的状态。 */
    public enum State {
        /** 有新鲜数据，采集器活着。 */
        OK,
        /** 有过数据但现在过期了（可能空服，也可能后端没响应）。 */
        STALE,
        /** 还在收到包，但后端自己说采集器已死——数字不可信。 */
        COLLECTOR_DEAD,
        /** 从来没有收到过这个后端的任何包。 */
        NO_DATA
    }

    /** 单个后端在某一时刻的视图。 */
    public static final class Node {
        private final String serverId;
        private final State state;
        private final SummaryPacket packet;
        private final long ageMs;

        Node(String serverId, State state, SummaryPacket packet, long ageMs) {
            this.serverId = serverId;
            this.state = state;
            this.packet = packet;
            this.ageMs = ageMs;
        }

        public String serverId() { return serverId; }
        public State state() { return state; }
        /** 原始包；{@link State#NO_DATA} 时为 {@code null}。 */
        public SummaryPacket packet() { return packet; }
        /** 快照年龄；{@link State#NO_DATA} 时为 {@link Long#MAX_VALUE}。 */
        public long ageMs() { return ageMs; }

        /** 这个后端能不能参与聚合：状态 OK 才算数。 */
        public boolean contributesToAggregate() {
            return state == State.OK;
        }
    }

    private final Map<String, SummaryPacket> latest = new LinkedHashMap<>();

    /**
     * 收到一条摘要。同一个 serverId 后来者覆盖前者（只保留最近一次）。
     *
     * @param packet 解析成功的包；{@code null} 会被忽略
     */
    public void onPacket(SummaryPacket packet) {
        if (packet == null) {
            return;
        }
        latest.put(packet.serverId(), packet);
    }

    /** 已知的后端 id 集合（收到过包的）。 */
    public java.util.Set<String> knownServers() {
        return java.util.Collections.unmodifiableSet(latest.keySet());
    }

    /**
     * 计算当前视图。
     *
     * @param nowMs            当前时间
     * @param pushIntervalSec  配置的上报间隔（秒），用于算 STALE 阈值
     * @param expectedServers  配置里列出的后端 id；这些即使没收到包也要出现在视图里
     *                         （显示为 {@link State#NO_DATA}），否则"少了一台"这件事看不出来
     * @return 按 id 排序的节点列表
     */
    public List<Node> snapshot(long nowMs, int pushIntervalSec, java.util.Collection<String> expectedServers) {
        // 先收集要展示的全部 id：收到过包的 ∪ 配置里声明的。
        // 并集很重要——只看收到过包的话，一台从没上报过的后端会彻底隐身，
        // 而"少了哪一台"正是这个视图最该回答的问题。
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (expectedServers != null) {
            ids.addAll(expectedServers);
        }
        ids.addAll(latest.keySet());

        long staleAfterMs = Math.max(1, pushIntervalSec) * 1000L * STALE_MULTIPLIER;

        List<Node> out = new ArrayList<>();
        for (String id : ids) {
            SummaryPacket p = latest.get(id);
            if (p == null) {
                out.add(new Node(id, State.NO_DATA, null, Long.MAX_VALUE));
                continue;
            }
            long age = p.ageMs(nowMs);
            // COLLECTOR_DEAD 优先于 STALE：包还在到，说明链路是通的，
            // 问题出在后端采集器而不是网络。这个区分决定了该去查哪一头。
            State s;
            if (!p.collectorAlive()) {
                s = State.COLLECTOR_DEAD;
            } else if (age > staleAfterMs) {
                s = State.STALE;
            } else {
                s = State.OK;
            }
            out.add(new Node(id, s, p, age));
        }

        out.sort(Comparator.comparing(Node::serverId));
        return out;
    }

    /**
     * 全网 TPS 平均。
     *
     * <p>只统计 {@link State#OK} 且真有 {@code tps1m} 的后端。没有任何可用数据时返回
     * {@code null} 而不是 0——0 在这里是一个灾难性的误读。</p>
     */
    public static Double averageTps1m(List<Node> nodes) {
        double sum = 0;
        int n = 0;
        for (Node node : nodes) {
            if (!node.contributesToAggregate()) continue;
            Double v = node.packet().tps1m();
            if (v == null) continue;
            sum += v;
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    /** 全网在线玩家数合计。没采到（{@code -1}）的后端不参与，与 TPS 同口径。 */
    public static Integer totalPlayers(List<Node> nodes) {
        int sum = 0;
        int n = 0;
        for (Node node : nodes) {
            if (!node.contributesToAggregate()) continue;
            int v = node.packet().playerCount();
            if (v == COUNT_UNAVAILABLE) continue;
            sum += v;
            n++;
        }
        return n == 0 ? null : sum;
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

    /** 清空（reload 时用）。 */
    public void clear() {
        latest.clear();
    }
}
