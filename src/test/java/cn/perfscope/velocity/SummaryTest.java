package cn.perfscope.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析与聚合的契约测试。
 *
 * <p>这里是代理侧唯一能完整跑的逻辑层——Velocity 的 API 在单测里无法实例化，
 * 所以 {@link NetworkView} / {@link NetworkRenderer} 被刻意做成不依赖任何
 * {@code com.velocitypowered.*} 的纯逻辑，好让真正会出错的部分（判定与渲染）可测。</p>
 */
class SummaryTest {

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static SummaryPacket okPacket(String id, long collectedAt, double tps, int players) {
        return SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + id
            + "\",\"sentAt\":" + collectedAt + ",\"collectedAt\":" + collectedAt
            + ",\"alive\":true,\"players\":" + players
            + ",\"tps1m\":" + tps + ",\"tps5m\":" + tps + ",\"tps15m\":" + tps
            + ",\"msptAvg\":12.0}"));
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("解析：正常包各字段到位")
    void parsesFullPacket() {
        SummaryPacket p = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"lobby\",\"sentAt\":2000,"
                + "\"collectedAt\":1000,\"alive\":true,\"players\":7,"
                + "\"tps1m\":19.98,\"tps5m\":19.9,\"tps15m\":19.8,\"msptAvg\":12.5}"));
        assertNotNull(p);
        assertEquals("lobby", p.serverId());
        assertEquals(1000L, p.collectedAtMs());
        assertEquals(2000L, p.sentAtMs());
        assertTrue(p.collectorAlive());
        assertEquals(7, p.playerCount());
        assertEquals(19.98, p.tps1m(), 1e-9);
        assertEquals(12.5, p.msptAvg(), 1e-9);
    }

    @Test
    @DisplayName("解析：缺失的数值字段是 null，不是 0")
    void missingNumericFieldsAreNullNotZero() {
        // 后端在模块被关掉时不发这几个字段。补成 0 会把"没采到"读成"TPS 归零"。
        SummaryPacket p = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"nether\",\"sentAt\":2000,"
                + "\"collectedAt\":1000,\"alive\":true,\"players\":3}"));
        assertNotNull(p);
        assertNull(p.tps1m(), "缺失的 TPS 必须是 null，绝不能补成 0");
        assertNull(p.msptAvg(), "缺失的 MSPT 必须是 null");
        assertEquals(3, p.playerCount());
    }

    @Test
    @DisplayName("解析：没采到的玩家数是 -1，0 是合法的空服读数")
    void unavailableCountIsMinusOneAndZeroIsLegal() {
        SummaryPacket unset = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}"));
        assertNotNull(unset);
        assertEquals(-1, unset.playerCount(), "没给玩家数时是 -1");

        SummaryPacket empty = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"b\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":0}"));
        assertNotNull(empty);
        assertEquals(0, empty.playerCount(), "0 人是合法读数，必须如实保留");
    }

    @Test
    @DisplayName("版本闸门：比自己高的协议版本直接拒，低的容忍")
    void rejectsFutureProtocolVersionButAcceptsOlder() {
        // 更高版本：可能改了字段语义，不能"尽力解析"——那正是把改动读成 0 的来源。
        assertNull(SummaryPacket.parse(json(
            "{\"v\":2,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")),
            "未来版本的包必须被拒绝");

        assertNull(SummaryPacket.parse(json(
            "{\"v\":0,\"kind\":\"summary\",\"server\":\"x\"}")), "版本 0 非法");

        assertNotNull(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")),
            "当前版本必须接受");
    }

    @Test
    @DisplayName("kind 闸门：同一通道上的其它消息不当作摘要")
    void ignoresOtherKindsOnSameChannel() {
        assertNull(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"query\",\"server\":\"x\"}")), "别的 kind 不是我们的");
        assertNull(SummaryPacket.parse(json("{\"v\":1,\"server\":\"x\"}")), "没有 kind 也不行");
    }

    @Test
    @DisplayName("坏输入一律返回 null，不抛异常（网络输入不可信）")
    void malformedInputReturnsNull() {
        assertNull(SummaryPacket.parse(null));
        assertNull(SummaryPacket.parse(new byte[0]));
        assertNull(SummaryPacket.parse(json("not json at all")));
        assertNull(SummaryPacket.parse(json("[1,2,3]")), "数组不是对象");
        assertNull(SummaryPacket.parse(json("{\"v\":\"not-a-number\"}")));
    }

    @Test
    @DisplayName("NaN / Infinity 当作没有值（防御后端发出非法浮点）")
    void nanAndInfinityTreatedAsMissing() {
        SummaryPacket p = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"tps1m\":\"NaN\"}"));
        // Gson 把字符串 "NaN" getAsDouble 时可能抛，也可能给出 NaN——两种都必须落到 null。
        if (p != null) {
            assertNull(p.tps1m(), "NaN 绝不能当作一个可比较的读数");
        }
    }

    // ==================== 视图三态 ====================

    @Test
    @DisplayName("三态必须分开：从未上报 / 数据过期 / 采集已死")
    void distinguishesTheThreeNoGoodNumberStates() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        int interval = 10; // 过期阈值 = 10s * 3 = 30s

        // A：新鲜数据
        view.onPacket(okPacket("a", now - 1_000L, 20.0, 5));
        // B：数据很旧
        view.onPacket(okPacket("b", now - 120_000L, 19.0, 2));
        // C：包是新的，但后端自报采集器已死
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"c\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":false,\"players\":1,\"tps1m\":20.0}"));
        view.onPacket(dead);
        // D：配置里存在但从未上报
        List<NetworkView.Node> nodes = view.snapshot(now, interval, List.of("a", "b", "c", "d"));
        assertEquals(4, nodes.size());

        assertEquals(NetworkView.State.OK, find(nodes, "a").state());
        assertEquals(NetworkView.State.STALE, find(nodes, "b").state());
        assertEquals(NetworkView.State.COLLECTOR_DEAD, find(nodes, "c").state());
        assertEquals(NetworkView.State.NO_DATA, find(nodes, "d").state());
    }

    @Test
    @DisplayName("从未上报的后端也要出现（否则「少了哪一台」看不出来）")
    void expectedServersAppearEvenWithoutData() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> nodes = view.snapshot(1000L, 10, List.of("survival", "creative"));
        assertEquals(2, nodes.size(), "配置里声明的后端即使没数据也必须列出来");
        assertEquals(NetworkView.State.NO_DATA, nodes.get(0).state());
        assertEquals(NetworkView.State.NO_DATA, nodes.get(1).state());
    }

    @Test
    @DisplayName("COLLECTOR_DEAD 优先于 STALE（包还在到，问题在后端不在网络）")
    void collectorDeadTakesPrecedenceOverStale() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        // 包已经很旧，且采集器已死——两者同时成立时，应该报告"采集已停"，
        // 因为那告诉服主去查后端的采集器，而不是查网络。
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1000,\"collectedAt\":1000,"
                + "\"alive\":false,\"players\":1}"));
        view.onPacket(dead);
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(NetworkView.State.COLLECTOR_DEAD, nodes.get(0).state());
    }

    // ==================== 聚合口径 ====================

    @Test
    @DisplayName("聚合：只算 OK 的后端，缺失不参与平均")
    void aggregateIgnoresNonOkNodes() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        view.onPacket(okPacket("a", now - 1000, 20.0, 10));
        view.onPacket(okPacket("b", now - 1000, 10.0, 5));
        view.onPacket(okPacket("c", now - 120_000, 2.0, 1));   // STALE，不参与
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"d\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":false,\"players\":99,\"tps1m\":1.0}"));
        view.onPacket(dead);                                   // COLLECTOR_DEAD，不参与

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("a", "b", "c", "d", "e"));
        Double avg = NetworkView.averageTps1m(nodes);
        assertNotNull(avg);
        // 只有 a(20) 与 b(10) 参与 → (20+10)/2 = 15。
        // 若把缺失当 0 按 5 个算，结果会是 6 —— 那正是要防的"凭空变好/变坏"。
        assertEquals(15.0, avg, 1e-9);

        Integer players = NetworkView.totalPlayers(nodes);
        assertNotNull(players);
        assertEquals(15, players, "只合计 a(10)+b(5)");
    }

    @Test
    @DisplayName("聚合：一个可用后端都没有时返回 null，不是 0")
    void aggregateIsNullWhenNothingUsable() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> nodes = view.snapshot(1000L, 10, List.of("a", "b"));
        assertNull(NetworkView.averageTps1m(nodes), "没有可用数据时必须是 null——0 会被读成全网冻结");
        assertNull(NetworkView.totalPlayers(nodes));
    }

    @Test
    @DisplayName("聚合：同一个 serverId 后者覆盖前者（只保留最近一次）")
    void laterPacketOverwritesEarlier() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        view.onPacket(okPacket("a", now - 5000, 20.0, 10));
        view.onPacket(okPacket("a", now - 1000, 12.0, 20));
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(1, nodes.size());
        assertEquals(12.0, nodes.get(0).packet().tps1m(), 1e-9);
        assertEquals(20, nodes.get(0).packet().playerCount());
    }

    // ==================== 渲染 ====================

    @Test
    @DisplayName("渲染：无数据不能渲染成 0，必须写 — 且标 UNKNOWN")
    void noDataRendersAsDashNotZero() {
        NetworkRenderer.Row row = NetworkRenderer.renderOne(
            new NetworkView().snapshot(1000L, 10, List.of("x")).get(0));
        assertEquals("x", row.cells().get(0).text());
        assertEquals("无数据", row.cells().get(1).text());
        assertEquals("—", row.cells().get(2).text(), "TPS 格必须是 —，绝不能是 0");
        assertEquals(NetworkRenderer.Level.UNKNOWN, row.cells().get(2).level(),
            "未知必须标 UNKNOWN，不能标 OK（绿色会被读成健康）");
        assertEquals("从未上报", row.cells().get(5).text());
    }

    @Test
    @DisplayName("渲染：玩家数 -1 写 —，0 如实写 0（两者不能混）")
    void playerCellDistinguishesMinusOneFromZero() {
        assertEquals("—", NetworkRenderer.playerCell(-1).text());
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.playerCell(-1).level());
        assertEquals("0", NetworkRenderer.playerCell(0).text(), "空服 0 人必须如实显示");
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.playerCell(0).level());
    }

    @Test
    @DisplayName("渲染：TPS 缺失写 —，有值时按阈值分级")
    void tpsCellHandlesMissingAndTiers() {
        assertEquals("—", NetworkRenderer.tpsCell(null).text());
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.tpsCell(null).level());

        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.tpsCell(10.0).level());
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.tpsCell(17.0).level());
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.tpsCell(20.0).level());
    }

    @Test
    @DisplayName("渲染：采集已死时整行都不给数字（陈数据不能当现状）")
    void collectorDeadRowShowsNoNumbers() {
        NetworkView view = new NetworkView();
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1000,\"collectedAt\":1000,"
                + "\"alive\":false,\"players\":5,\"tps1m\":19.5,\"msptAvg\":10.0}"));
        view.onPacket(dead);
        NetworkRenderer.Row row = NetworkRenderer.renderOne(
            view.snapshot(2000L, 10, List.of()).get(0));

        assertEquals("采集已停", row.cells().get(1).text());
        // 关键：包里其实有 tps1m=19.5，但采集器已死 → 不能显示它。
        assertEquals("—", row.cells().get(2).text(), "采集器已死时 TPS 必须隐藏，否则就是把冻结的数字当现状");
        assertEquals("—", row.cells().get(3).text());
        assertEquals("—", row.cells().get(4).text());
    }

    @Test
    @DisplayName("汇总行：完全没有可用数据时 TPS 与在线显示 —，不显示 0")
    void summaryLineUsesDashWhenNoAggregate() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> nodes = view.snapshot(1000L, 10, List.of("a"));
        String line = NetworkRenderer.summaryLine(nodes, null, null);
        assertTrue(line.contains("—"), "无聚合值时必须是 —：" + line);
        assertFalse(line.contains("0.00"), "不能出现 0.00：" + line);
    }

    private static NetworkView.Node find(List<NetworkView.Node> nodes, String id) {
        for (NetworkView.Node n : nodes) {
            if (n.serverId().equals(id)) return n;
        }
        throw new AssertionError("node not found: " + id);
    }
}