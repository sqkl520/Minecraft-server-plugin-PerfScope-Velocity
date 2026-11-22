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
        // 三个窗口故意用不同值：此前三个都填同一个数，于是把 tps5m / tps15m 读取键
        // 互换也测不出来（读到的还是同一个数）。
        return SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + id
            + "\",\"sentAt\":" + collectedAt + ",\"collectedAt\":" + collectedAt
            + ",\"alive\":true,\"players\":" + players
            + ",\"tps1m\":" + tps + ",\"tps5m\":" + (tps - 0.1) + ",\"tps15m\":" + (tps - 0.2)
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
        assertEquals(19.9, p.tps5m(), 1e-9);
        assertEquals(19.8, p.tps15m(), 1e-9);
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
        // 这里刻意断言 p 非 null：只写 "if (p != null) assertNull(...)" 的话，
        // 一旦解析开始整包拒绝，p 就是 null，测试什么都不检查、照样绿——
        // 那是一种比没有测试更糟的假绿。
        String[] bad = {"\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e999"};
        for (String raw : bad) {
            SummaryPacket p = SummaryPacket.parse(json(
                "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,"
                    + "\"alive\":true,\"tps1m\":" + raw + "}"));
            assertNotNull(p, "单个非法浮点不应导致整包被拒：" + raw);
            assertNull(p.tps1m(), "非法浮点绝不能当作可比较的读数：" + raw);
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
        // 按 id 断言而不是 get(0)/get(1)：后者隐式依赖排序，实现一改排序就会
        // 因为「两台都是 NO_DATA」而侥幸通过，什么也测不到。
        assertEquals(NetworkView.State.NO_DATA, find(nodes, "survival").state());
        assertEquals(NetworkView.State.NO_DATA, find(nodes, "creative").state());
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

    /**
     * 阈值边界：15.00 / 18.00 两点必须与后端<b>严格比较</b>口径一致。
     *
     * <p><b>这条曾经断言的是相反的事。</b>fix2 把它改成闭区间，理由是"后端 ThresholdColors
     * 用 {@code value <= threshold}"——那个理由在当时是真的，因为后端展示层确实自己写了一份
     * 闭区间。但那份闭区间是与后端采集层（{@code ThresholdOperator}，严格比较）分裂的，
     * 同一个 {@code tps=15.00} 在后端界面上是红的、在后端告警里只是黄。fix4 把后端展示层
     * 改成从算子表派生，两处同时变成严格——于是这里也必须跟严格。
     *
     * <p>要记住的判据不是"跟某个类一致"，而是"与后端对同一读数给出同一结论"。
     * 后端 {@code ThresholdOperator.TPS} 是 {@code tps < threshold}，所以恰好 15.00 是 WARN、
     * 14.99 才是 CRITICAL；恰好 18.00 是 OK、17.99 才是 WARN。</p>
     */
    @Test
    @DisplayName("阈值边界：15.00 / 18.00 两点与后端严格比较口径一致")
    void tpsTierBoundariesAreStrictLikeBackend() {
        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.tpsCell(14.99).level(),
            "低于 15.00 才是 CRITICAL");
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.tpsCell(15.0).level(),
            "恰好 15.00 是 WARN，不是 CRITICAL——后端 tps < 15 不成立，只到 warning 档");
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.tpsCell(15.01).level());
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.tpsCell(17.99).level());
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.tpsCell(18.0).level(),
            "恰好 18.00 是 OK——后端 tps < 18 不成立");
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.tpsCell(18.01).level());
    }

    @Test
    @DisplayName("MSPT 分级：缺失写 —，45/50 两档边界是严格比较")
    void msptCellHandlesMissingAndTiers() {
        // msptCell 此前零覆盖：把只读全改成 OK 也测不出来，于是 MSPT 红色永不出现。
        assertEquals("—", NetworkRenderer.msptCell(null).text(),
            "缺失 MSPT 必须写 —，写成 0.0 等于把「没采到」读成「零耗时」");
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.msptCell(null).level());

        assertEquals("12.3", NetworkRenderer.msptCell(12.34).text());
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.msptCell(44.9).level());
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.msptCell(45.0).level(),
            "恰好 45.0 是 OK——后端 mspt > 45 不成立");
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.msptCell(45.1).level());
        assertEquals(NetworkRenderer.Level.WARN, NetworkRenderer.msptCell(50.0).level(),
            "恰好 50.0 是 WARN——后端 mspt > 50 不成立");
        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.msptCell(50.1).level());
        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.msptCell(120.0).level());
    }

    @Test
    @DisplayName("四种状态行逐格断言 text 与 level（未知绝不能是绿色）")
    void everyStateRowHasCorrectLevels() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        // OK
        view.onPacket(okPacket("ok", now - 1_000L, 20.0, 7));
        // STALE（age 120s > 3×10s）
        view.onPacket(okPacket("stale", now - 120_000L, 19.5, 3));
        // COLLECTOR_DEAD
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"dead\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":false,"
                + "\"players\":5,\"tps1m\":19.5,\"msptAvg\":10.0}")));
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("nodata"));

        NetworkRenderer.Row ok = NetworkRenderer.renderOne(find(nodes, "ok"));
        assertEquals("正常", ok.cells().get(1).text());
        assertEquals("20.00", ok.cells().get(2).text());
        assertEquals(NetworkRenderer.Level.OK, ok.cells().get(2).level());
        assertEquals("12.0", ok.cells().get(3).text());
        assertEquals(NetworkRenderer.Level.OK, ok.cells().get(3).level());
        assertEquals("7", ok.cells().get(4).text());
        assertEquals(NetworkRenderer.Level.OK, ok.cells().get(4).level());

        NetworkRenderer.Row stale = NetworkRenderer.renderOne(find(nodes, "stale"));
        assertEquals("数据过期", stale.cells().get(1).text());
        assertEquals(NetworkRenderer.Level.UNKNOWN, stale.cells().get(1).level(),
            "「数据过期」绝不能标 OK");
        assertEquals("19.50", stale.cells().get(2).text(), "过期行仍显示最后已知值");
        assertEquals(NetworkRenderer.Level.UNKNOWN, stale.cells().get(5).level(),
            "年龄格必须标 UNKNOWN——它的主体是「过期了」这件事");

        NetworkRenderer.Row dead = NetworkRenderer.renderOne(find(nodes, "dead"));
        assertEquals("采集已停", dead.cells().get(1).text());
        for (int i = 1; i <= 5; i++) {
            assertEquals(NetworkRenderer.Level.UNKNOWN, dead.cells().get(i).level(),
                "采集已停的行整行都必须 UNKNOWN，第 " + i + " 格被标成了别的");
        }

        NetworkRenderer.Row none = NetworkRenderer.renderOne(find(nodes, "nodata"));
        assertEquals("无数据", none.cells().get(1).text());
        for (int i = 0; i < 6; i++) {
            assertEquals(NetworkRenderer.Level.UNKNOWN, none.cells().get(i).level(),
                "无数据的行整行都必须 UNKNOWN，第 " + i + " 格被标成了别的");
        }
    }

    @Test
    @DisplayName("汇总行整串等值断言（不靠 contains 蒙对）")
    void summaryLineIsExact() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> none = view.snapshot(1000L, 10, List.of("a"));
        assertEquals("后端 1 个：正常 0 · 无数据 1 ｜ 全网 TPS — ｜ 在线合计 —",
            NetworkRenderer.summaryLine(none, null, null));

        long now = 100_000L;
        NetworkView mixed = new NetworkView();
        mixed.onPacket(okPacket("a", now - 1_000L, 20.0, 10));
        mixed.onPacket(okPacket("b", now - 1_000L, 10.0, 5));
        mixed.onPacket(okPacket("c", now - 120_000L, 2.0, 1));   // STALE
        List<NetworkView.Node> nodes = mixed.snapshot(now, 10, List.of("d"));
        Double avg = NetworkView.averageTps1m(nodes);
        Integer players = NetworkView.totalPlayers(nodes);
        assertEquals("后端 4 个：正常 2 · 数据过期 1 · 无数据 1 ｜ 全网 TPS 15.00 ｜ 在线合计 15",
            NetworkRenderer.summaryLine(nodes, avg, players));
    }

    @Test
    @DisplayName("聚合边角：OK 后端缺 tps1m 不参与平均、也不 NPE")
    void aggregateSkipsOkNodeWithMissingTps() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        view.onPacket(okPacket("has", now - 1_000L, 20.0, 4));
        // alive=true、数据新鲜，但没有 tps1m（后端 performance 模块被关掉）
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"notps\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":true,\"players\":2}")));

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(20.0, NetworkView.averageTps1m(nodes), 1e-9,
            "缺 tps1m 的 OK 后端不能参与平均，更不能让 averageTps1m 抛 NPE");
        assertEquals(6, NetworkView.totalPlayers(nodes), "玩家数仍然可以合计");
    }

    @Test
    @DisplayName("聚合边角：OK 后端 players=-1 不参与合计（不能凭空少人）")
    void aggregateSkipsUnavailablePlayerCount() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        view.onPacket(okPacket("a", now - 1_000L, 20.0, 10));
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"b\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":true,\"tps1m\":20.0}")));

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(10, NetworkView.totalPlayers(nodes),
            "-1 是「没采到」，混进合计会让在线人数凭空少 1");
    }

    @Test
    @DisplayName("过期阈值跟随后端自带的上报间隔，而不是写死的 10 秒")
    void staleWindowFollowsPacketInterval() {
        // 后端把 push-interval-seconds 调成 60 秒：代理必须按 3×60=180 秒判过期。
        // 否则每两轮上报之间都会闪一段「数据过期」，看起来像故障。
        long now = 200_000L;
        SummaryPacket slow = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"slow\",\"sentAt\":" + (now - 70_000L)
                + ",\"collectedAt\":" + (now - 70_000L) + ",\"alive\":true,"
                + "\"players\":1,\"tps1m\":20.0,\"pushIntervalSec\":60}"));
        assertEquals(60, slow.pushIntervalSec());

        NetworkView view = new NetworkView();
        view.onPacket(slow);
        assertEquals(NetworkView.State.OK, view.snapshot(now, 10, List.of()).get(0).state(),
            "70 秒前发的、但该后端每 60 秒才发一次，不应当判过期");

        // 超过 3×60=180 秒才该过期
        assertEquals(NetworkView.State.STALE,
            view.snapshot(now + 120_000L, 10, List.of()).get(0).state());
    }

    @Test
    @DisplayName("越界/缺失的 pushIntervalSec 回退默认阈值，不能被伪造的极值骗过")
    void absurdPushIntervalFallsBackToDefault() {
        assertEquals(0, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"pushIntervalSec\":0}")).pushIntervalSec());
        assertEquals(0, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"pushIntervalSec\":999999}")).pushIntervalSec(),
            "极大值会让该后端永远显示正常——必须当成「没提供」");
        assertEquals(0, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"x\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true}")).pushIntervalSec(), "旧版后端没有这个字段");
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

    @Test
    @DisplayName("列宽：中文按 2 格算，填充后各列起点对齐")
    void columnsAlignAfterPadding() {
        // 中文与 ASCII 的宽度不同，「数据过期」(4 字) 与「正常」(2 字) 差一倍；
        // 不填充时每行第 3 列起都在不同位置，纵向比较无从谈起。
        assertEquals(4, NetworkRenderer.displayWidth("正常"));
        assertEquals(8, NetworkRenderer.displayWidth("数据过期"));
        assertEquals(5, NetworkRenderer.displayWidth("19.84"));

        assertEquals("正常    ", NetworkRenderer.padTo("正常", 8));
        assertEquals("19.84", NetworkRenderer.padTo("19.84", 4),
            "超出目标宽度时原样返回，不能截断");
    }

    @Test
    @DisplayName("列宽：取表头与所有行的最大值，不是第一行")
    void columnWidthsUseMaxAcrossRows() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        // 第一行是最窄的「正常」，后面再来一个最宽的「数据过期」——
        // 只用第一行算宽度的话，后面的行会把它挤歪。
        view.onPacket(okPacket("s", now - 1_000L, 20.0, 7));
        view.onPacket(okPacket("t", now - 120_000L, 19.0, 1));
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("a-very-long-backend-name"));
        List<NetworkRenderer.Row> rows = NetworkRenderer.render(nodes);
        int[] w = NetworkRenderer.columnWidths(rows, NetworkRenderer.HEADERS);

        assertEquals(NetworkRenderer.displayWidth("a-very-long-backend-name"), w[0],
            "第 1 列要容得下最长的后端名");
        assertEquals(NetworkRenderer.displayWidth("数据过期"), w[1],
            "第 2 列要容得下最宽的状态文案（而不是第一行的「正常」）");
        assertEquals(NetworkRenderer.displayWidth("TPS(1m)"), w[2]);

        // 不变式：任一格的宽度都不超过它所在列的宽度，否则填充是假的。
        for (NetworkRenderer.Row row : rows) {
            for (int i = 0; i < row.cells().size(); i++) {
                assertTrue(NetworkRenderer.displayWidth(row.cells().get(i).text()) <= w[i],
                    "第 " + i + " 格超出了列宽：" + row.cells().get(i).text());
            }
        }
    }

    private static NetworkView.Node find(List<NetworkView.Node> nodes, String id) {
        for (NetworkView.Node n : nodes) {
            if (n.serverId().equals(id)) return n;
        }
        throw new AssertionError("node not found: " + id);
    }

    // ==================== 验收修复后新增：防回归 ====================

    @Test
    @DisplayName("新增可选字段不破坏旧包：不带 pushIntervalSec 的包照常解析")
    void optionalFieldKeepsOlderPacketsWorking() {
        // 这条钉住"加字段不必升 PROTOCOL_VERSION"这个决定。如果哪天有人为了加一个可选
        // 字段就升号，旧代理会整包拒收——升级窗口期面板全空，正是要避免的。
        SummaryPacket old = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"legacy\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":2,\"tps1m\":19.9}"));
        assertNotNull(old, "旧版后端（没有新字段）的包必须照常被接受");
        assertEquals(0, old.pushIntervalSec(), "缺字段时是 0 = 没提供");
        assertEquals(19.9, old.tps1m(), 1e-9);

        // 反向：新包里的未知键也不能让旧逻辑崩（解析按 key 取值，多余键被忽略）。
        SummaryPacket extra = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"future\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"somethingNew\":123}"));
        assertNotNull(extra);
        assertEquals("future", extra.serverId());
    }

    @Test
    @DisplayName("peekVersion 只为日志服务：坏包也能报出对方版本")
    void peekVersionReadsVersionEvenWhenParseFails() {
        // 版本不兼容时 parse 返回 null，日志若只报本端版本，服主看不出该升哪一边。
        assertEquals(7, SummaryPacket.peekVersion(json(
            "{\"v\":7,\"kind\":\"summary\",\"server\":\"x\"}")));
        assertNull(SummaryPacket.peekVersion(json("not json")));
        assertNull(SummaryPacket.peekVersion(json("{\"kind\":\"summary\"}")),
            "没有 v 字段时是 null（日志会写「无法识别」）");
        assertNull(SummaryPacket.peekVersion(null));
    }

    @Test
    @DisplayName("超长 serverId 直接拒（否则撑爆堆、撑爆聊天行）")
    void rejectsOverlongServerId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SummaryPacket.MAX_SERVER_ID_LENGTH + 1; i++) sb.append('a');
        String json = "{\"v\":1,\"kind\":\"summary\",\"server\":\"" + sb
            + "\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}";
        assertNull(SummaryPacket.parse(json(json)),
            "超过 " + SummaryPacket.MAX_SERVER_ID_LENGTH + " 字符的 serverId 必须被拒");

        // 边界内的要照常接受（别把闸门做过头）。
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < SummaryPacket.MAX_SERVER_ID_LENGTH; i++) ok.append('a');
        assertNotNull(SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + ok
            + "\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")));
    }

    @Test
    @DisplayName("荒谬的 players 值当作没采到，不显示到面板上")
    void clampsAbsurdPlayerCounts() {
        // -99999 不是 -1 哨兵，原样显示会出现"负九万人"
        assertEquals(-1, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":-99999}")).playerCount());
        // long → int 截断会变成 1，也是假的
        assertEquals(-1, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":4294967297}")).playerCount());
        // 正常值必须原样保留
        assertEquals(42, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":42}")).playerCount());
        // 0 是合法读数
        assertEquals(0, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,"
                + "\"alive\":true,\"players\":0}")).playerCount());
    }

    @Test
    @DisplayName("条目数有上限，超出时淘汰最久未更新的（防止被灌爆堆）")
    void capsTrackedServers() {
        NetworkView view = new NetworkView();
        int over = NetworkView.MAX_TRACKED_SERVERS + 10;
        for (int i = 0; i < over; i++) {
            view.onPacket(okPacket("srv" + i, 1_000L, 20.0, 1));
        }
        assertEquals(NetworkView.MAX_TRACKED_SERVERS, view.trackedCount(),
            "跟踪数必须被限制在 MAX_TRACKED_SERVERS，否则恶意 id 能填满堆");
        // 最新的一定还在，最老的一定被淘汰了
        assertTrue(view.knownServers().contains("srv" + (over - 1)), "最新的必须保留");
        assertFalse(view.knownServers().contains("srv0"), "最久未更新的应被淘汰");
    }

    @Test
    @DisplayName("并发安全靠结构保证，不靠压测碰运气")
    void concurrentSafetyIsStructuralNotProbabilistic() throws Exception {
        // ⚠️ 这条测试的由来，值得记下来：
        //
        // 我先写了一个多线程压测（6 写 × 300 不同 id + 4 读 × 3000 次全量遍历 × 3 轮），
        // 满以为能抓到"裸 LinkedHashMap"。结果把实现换回裸容器，测试**依然全绿**——
        // 连跑两轮都没撞出 ConcurrentModificationException。
        //
        // 这说明压测路线不可靠：CME 是否出现取决于 JIT、CPU 数、调度时序，
        // 在别人的机器上红、在我这里是绿的，是最糟的一种测试（比没有更糟，
        // 因为它给人以"已经测过了"的错觉）。
        //
        // 所以改成**结构性断言**：直接问这个字段到底是不是并发安全的容器。
        // 这不依赖时序，改动实现必然变红。
        java.lang.reflect.Field f = NetworkView.class.getDeclaredField("latest");
        f.setAccessible(true);
        NetworkView view = new NetworkView();
        Object map = f.get(view);

        String cls = map.getClass().getName();
        boolean safeWrapper = cls.contains("SynchronizedMap")
            || cls.contains("ConcurrentHashMap");
        assertTrue(safeWrapper,
            "NetworkView.latest 必须是并发安全的容器（当前是 " + cls + "）。"
                + "写入来自 Netty 的多个 event-loop 线程，读取来自命令线程，"
                + "裸 Map 会抛 ConcurrentModificationException 并在扩容时丢条目。"
                + "注意：普通的 HashMap/LinkedHashMap 即使加了 synchronized 块也不够——"
                + "snapshot() 里对副本的遍历同样需要与写入互斥。");
    }

    @Test
    @DisplayName("并发读写不抛异常（辅助验证，不作为唯一防线）")
    void survivesConcurrentReadWrite() throws Exception {
        // 这一条是补充，不是主防线（主防线见上一条的结构断言）。
        // 它验的是"在真实并发下不会崩"，但压测撞不出 CME 不代表没有问题，
        // 所以不能只靠它。
        NetworkView view = new NetworkView();
        java.util.concurrent.atomic.AtomicReference<Throwable> boom =
            new java.util.concurrent.atomic.AtomicReference<>();

        for (int round = 0; round < 3 && boom.get() == null; round++) {
            view.clear();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Thread> threads = new java.util.ArrayList<>();
            final int r = round;

            for (int w = 0; w < 6; w++) {
                final int id = w;
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 300; i++) {
                            view.onPacket(okPacket("r" + r + "-w" + id + "-i" + i, 1_000L, 20.0, 1));
                        }
                    } catch (Throwable e) {
                        boom.compareAndSet(null, e);
                    }
                });
                threads.add(t);
                t.start();
            }
            for (int rd = 0; rd < 4; rd++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 3000; i++) {
                            view.snapshot(2_000L + i, 10, List.of());
                        }
                    } catch (Throwable e) {
                        boom.compareAndSet(null, e);
                    }
                });
                threads.add(t);
                t.start();
            }

            start.countDown();
            for (Thread t : threads) t.join(60_000);
        }

        if (boom.get() != null) {
            throw new AssertionError("并发读写抛异常了（说明容器/锁没做对）：", boom.get());
        }
    }

    @Test
    @DisplayName("clear() 真的能清空（它是 reset 子命令的实现）")
    void clearActuallyClears() {
        NetworkView view = new NetworkView();
        view.onPacket(okPacket("a", 1_000L, 20.0, 1));
        assertEquals(1, view.trackedCount());
        view.clear();
        assertEquals(0, view.trackedCount(), "clear() 必须真的清空，否则 reset 子命令是假的");
    }
}