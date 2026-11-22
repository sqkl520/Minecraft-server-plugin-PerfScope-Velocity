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

    /**
     * 把一包投进视图，并**假定它刚好在 collectedAt 之后 1 秒到达**。
     *
     * <p><b>为什么要这么包一层</b>：过期判据现在是"距最后一次到达过了多久"
     * （而不是包里的年龄），所以测试必须给出到达时刻，否则会拿真实系统时间去和
     * 测试里虚构的 {@code now = 100_000L} 相减，得到"沉默 50 年"这种荒谬结论——
     * 那是测试自己写错，不是实现坏了。</p>
     *
     * <p>偏移有意保持为 0：这些老用例测的是状态机与聚合口径，不是时钟；
     * 时钟相关用例在文件末尾单独一节，用 {@link #packetWithSkew} 显式施加偏移。</p>
     */
    private static void feed(NetworkView view, SummaryPacket p) {
        view.onPacket(p, p.collectedAtMs() + 1_000L);
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
        feed(view, okPacket("a", now - 1_000L, 20.0, 5));
        // B：数据很旧
        feed(view, okPacket("b", now - 120_000L, 19.0, 2));
        // C：包是新的，但后端自报采集器已死
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"c\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":false,\"players\":1,\"tps1m\":20.0}"));
        feed(view, dead);
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
        feed(view, dead);
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(NetworkView.State.COLLECTOR_DEAD, nodes.get(0).state());
    }

    // ==================== 聚合口径 ====================

    @Test
    @DisplayName("聚合：只算 OK 的后端，缺失不参与平均")
    void aggregateIgnoresNonOkNodes() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        feed(view, okPacket("a", now - 1000, 20.0, 10));
        feed(view, okPacket("b", now - 1000, 10.0, 5));
        feed(view, okPacket("c", now - 120_000, 2.0, 1));   // STALE，不参与
        SummaryPacket dead = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"d\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":false,\"players\":99,\"tps1m\":1.0}"));
        feed(view, dead);                                   // COLLECTOR_DEAD，不参与

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("a", "b", "c", "d", "e"));
        Double avg = NetworkView.averageTps1m(nodes);
        assertNotNull(avg);
        // 只有 a(20) 与 b(10) 参与 → (20+10)/2 = 15。
        // 若把缺失当 0 按 5 个算，结果会是 6 —— 那正是要防的"凭空变好/变坏"。
        assertEquals(15.0, avg, 1e-9);

        NetworkView.PlayerTotal players = NetworkView.totalPlayers(nodes);
        assertNotNull(players.counted());
        assertEquals(15, players.counted(), "只合计 a(10)+b(5)");
        assertTrue(players.isComplete() || players.uncountedKnown() > 0,
            "这条用例只关心怎么合计，不关心不完整标记（另有专门用例）");
    }

    @Test
    @DisplayName("聚合：一个可用后端都没有时返回 null，不是 0")
    void aggregateIsNullWhenNothingUsable() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> nodes = view.snapshot(1000L, 10, List.of("a", "b"));
        assertNull(NetworkView.averageTps1m(nodes), "没有可用数据时必须是 null——0 会被读成全网冻结");
        assertNull(NetworkView.totalPlayers(nodes).counted());
    }

    @Test
    @DisplayName("聚合：同一个 serverId 后者覆盖前者（只保留最近一次）")
    void laterPacketOverwritesEarlier() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        feed(view, okPacket("a", now - 5000, 20.0, 10));
        feed(view, okPacket("a", now - 1000, 12.0, 20));
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
        feed(view, okPacket("ok", now - 1_000L, 20.0, 7));
        // STALE（age 120s > 3×10s）
        feed(view, okPacket("stale", now - 120_000L, 19.5, 3));
        // COLLECTOR_DEAD
        feed(view, SummaryPacket.parse(json(
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
        mixed.onPacket(okPacket("a", now - 1_000L, 20.0, 10), now - 1_000L + 1_000L);
        mixed.onPacket(okPacket("b", now - 1_000L, 10.0, 5), now - 1_000L + 1_000L);
        mixed.onPacket(okPacket("c", now - 120_000L, 2.0, 1), now - 120_000L + 1_000L);   // STALE
        List<NetworkView.Node> nodes = mixed.snapshot(now, 10, List.of("d"));
        Double avg = NetworkView.averageTps1m(nodes);
        NetworkView.PlayerTotal players = NetworkView.totalPlayers(nodes);
        // c 是 STALE 且带 1 人：它不进合计，但必须被**写出来**，否则读者会把 15 当成全服在线。
        assertEquals("后端 4 个：正常 2 · 数据过期 1 · 无数据 1 ｜ 全网 TPS 15.00 ｜ 在线合计 15（+1 未计入）",
            NetworkRenderer.summaryLine(nodes, avg, players));
    }

    @Test
    @DisplayName("聚合边角：OK 后端缺 tps1m 不参与平均、也不 NPE")
    void aggregateSkipsOkNodeWithMissingTps() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        feed(view, okPacket("has", now - 1_000L, 20.0, 4));
        // alive=true、数据新鲜，但没有 tps1m（后端 performance 模块被关掉）
        feed(view, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"notps\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":true,\"players\":2}")));

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(20.0, NetworkView.averageTps1m(nodes), 1e-9,
            "缺 tps1m 的 OK 后端不能参与平均，更不能让 averageTps1m 抛 NPE");
        assertEquals(6, NetworkView.totalPlayers(nodes).counted(), "玩家数仍然可以合计");
    }

    @Test
    @DisplayName("聚合边角：OK 后端 players=-1 不参与合计（不能凭空少人）")
    void aggregateSkipsUnavailablePlayerCount() {
        NetworkView view = new NetworkView();
        long now = 100_000L;
        feed(view, okPacket("a", now - 1_000L, 20.0, 10));
        feed(view, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"b\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000) + ",\"alive\":true,\"tps1m\":20.0}")));

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(10, NetworkView.totalPlayers(nodes).counted(),
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
        // 显式给到达时刻：过期判据是"距最后一次到达多久"，用真实系统时间喂进去会
        // 与这里虚构的 now(=200s) 差几十年，什么都测不到。
        view.onPacket(slow, now - 70_000L);
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
        view.onPacket(dead, 2_000L);
        NetworkRenderer.Row row = NetworkRenderer.renderOne(
            view.snapshot(2_000L, 10, List.of()).get(0));

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
        // 超出目标宽度时**截断**（以前是原样返回）。padTo 的契约是"返回的串正好占
        // width 格"，原样返回会让调用方拼出的行悄悄变宽，而没有任何地方会报错。
        String over = NetworkRenderer.padTo("19.84", 4);
        assertEquals(4, NetworkRenderer.displayWidth(over),
            "超宽必须截到目标宽度，实际是：" + over);
        assertTrue(over.endsWith("…"), "截断要看得出来，实际是：" + over);
    }

    @Test
    @DisplayName("列宽：emoji / 组合字符 / 半角假名 / 希腊字母的宽度判据")
    void displayWidthHandlesNonAsciiUntruthfully() {
        // 旧判据是「cp < 0x80 ? 1 : 2」，这几条里只有前两条是对的。
        assertEquals(5, NetworkRenderer.displayWidth("lobby"), "ASCII");
        assertEquals(6, NetworkRenderer.displayWidth("生存服"), "CJK 汉字各 2 格");

        // emoji 家庭：4 个 W 码点 + 3 个 ZWJ，客户端里是**一个** 2 格字形。
        // 旧判据给出 14，把整列撑爆；现在按字素簇算。
        assertEquals(2, NetworkRenderer.displayWidth("👨‍👩‍👧‍👦"),
            "ZWJ 序列必须算一个字素簇");

        // NFD 的 é = e + U+0301（组合记号，0 格）。
        assertEquals(1, NetworkRenderer.displayWidth("é"), "组合记号不占位");
        // 预组合的 é 也是 1 格——两种写法必须等价，否则规范化会改变对齐。
        assertEquals(1, NetworkRenderer.displayWidth("é"), "预组合字符与 NFD 必须同宽");

        // 半角片假名是 East Asian Width 的 H（窄），不是 W。
        assertEquals(3, NetworkRenderer.displayWidth("ｱｲｳ"), "半角假名各 1 格");

        // 希腊/西里尔是 A（Ambiguous）：按 1 格（见本类"已知不精确"，中文语境下可能差 1 格）。
        assertEquals(1, NetworkRenderer.displayWidth("Ω"), "希腊字母按 1 格");
        assertEquals(1, NetworkRenderer.displayWidth("А"), "西里尔字母按 1 格");

        // emoji 单字符与肤色修饰：修饰符不单独占位。
        assertEquals(2, NetworkRenderer.displayWidth("👍"), "👍 是 W");
        assertEquals(2, NetworkRenderer.displayWidth("👍🏽"), "👍🏽 仍是 2 格");

        // VS16：❤ 本身是 N（1 格），加 VS16 后按 emoji 呈现成 2 格。
        assertEquals(1, NetworkRenderer.displayWidth("❤"));
        assertEquals(2, NetworkRenderer.displayWidth("❤️"));

        // 韩国/日本宽字符、全角标点。
        assertEquals(2, NetworkRenderer.displayWidth("가"), "韩文音节");
        assertEquals(2, NetworkRenderer.displayWidth("、"), "全角顿号");

        // 混合串：CJK 与 ASCII 相邻也要正确累加（旧实现这里是对的，钉住防回归）。
        assertEquals(9, NetworkRenderer.displayWidth("生存服abc"), "3 个汉字(6) + 3 个 ASCII(3)");
    }

    @Test
    @DisplayName("列宽：截断落在字素簇边界上，不会切出半个 emoji")
    void truncateNeverSplitsACluster() {
        String family = "👨‍👩‍👧‍👦";
        // 预算 4 格 - 省略号 1 格 = 3 格：abc(3) 放得下，family(2) 放不下，于是切在簇边界上。
        String cut = NetworkRenderer.truncateToWidth("abc" + family, 4);
        assertEquals("abc…", cut, "切点必须落在字素簇边界，不能切出半个 emoji：" + cut);
        assertTrue(NetworkRenderer.displayWidth(cut) <= 4, "截断结果不得超过上限：" + cut);

        // 宽度正好等于上限时不截断（不该平白丢字、也不该多出省略号）。
        assertEquals("abc" + family, NetworkRenderer.truncateToWidth("abc" + family, 5));

        // 不变式：任意截断宽度下，结果宽度都不超过上限；只要**真的**截掉了东西，
        // 就必须带省略号（让人看得出来）。宽度本来就够时不该截断，也不该多出省略号。
        int full = NetworkRenderer.displayWidth("生存服abc");
        for (int limit = 1; limit <= 12; limit++) {
            String t = NetworkRenderer.truncateToWidth("生存服abc", limit);
            assertTrue(NetworkRenderer.displayWidth(t) <= limit,
                "limit=" + limit + " 时结果超宽：" + t);
            if (limit < full) {
                assertTrue(t.endsWith("…"), "limit=" + limit + " 时看不出被截断：" + t);
            } else {
                assertEquals("生存服abc", t, "limit=" + limit + " 已经够宽，不该截断");
            }
        }
    }

    @Test
    @DisplayName("列宽：取表头与所有行的最大值，不是第一行")
    void columnWidthsUseMaxAcrossRows() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        // 第一行是最窄的「正常」，后面再来一个最宽的「数据过期」——
        // 只用第一行算宽度的话，后面的行会把它挤歪。
        feed(view, okPacket("s", now - 1_000L, 20.0, 7));
        feed(view, okPacket("t", now - 120_000L, 19.0, 1));
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("a-very-long-backend-name"));
        List<NetworkRenderer.Row> rows = NetworkRenderer.render(nodes);
        int[] w = NetworkRenderer.columnWidths(rows, NetworkRenderer.HEADERS);

        assertEquals(NetworkRenderer.displayWidth("TPS(1m)"), w[2]);

        // 后端名带横线（`a-very-long-backend-name` = 24 格）已经超过 MAX_ID_DISPLAY_WIDTH(20)，
        // 渲染层会先把它截到 20 格并加省略号，所以列宽是 20 而不是 24。
        assertEquals(NetworkRenderer.MAX_ID_DISPLAY_WIDTH, w[0],
            "第 1 列（后端名）必须被封在 MAX_ID_DISPLAY_WIDTH，而不是容器里的 24 格");

        // 不变式：任一格的宽度都不超过它所在列的宽度，否则填充是假的。
        for (NetworkRenderer.Row row : rows) {
            for (int i = 0; i < row.cells().size(); i++) {
                assertTrue(NetworkRenderer.displayWidth(row.cells().get(i).text()) <= w[i],
                    "第 " + i + " 格超出了列宽：" + row.cells().get(i).text());
            }
        }
    }

    @Test
    @DisplayName("列宽：超长后端名截断到 20 格（否则整行被一格撑爆）")
    void overlongServerIdIsTruncatedNotWidening() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        // 64 个汉字能过解析层的 UTF-16 闸门（64 个 code unit），却是 128 显示格。
        // 修复前：columnWidths 取到 128 → 整行 165 格 → 聊天框里折成三行。
        String wideName = "服".repeat(64);
        feed(view, okPacket(wideName, now - 1_000L, 20.0, 3));
        List<NetworkRenderer.Row> rows =
            NetworkRenderer.render(view.snapshot(now, 10, List.of()));
        int[] w = NetworkRenderer.columnWidths(rows, NetworkRenderer.HEADERS);

        // 第 1 列的宽度来自**实际渲染出的格子**（已截断），所以是 ≤ 上限，不会是容器里的 128 格。
        // 注意它可能是 19 而不是 20：预算是 19 格（20 减去省略号那 1 格），汉字占 2 格，
        // 于是最多用掉 18 格再加 1 格省略号。差这 1 格是字素簇边界切分的结果，不是漏洞。
        assertTrue(w[0] <= NetworkRenderer.MAX_ID_DISPLAY_WIDTH,
            "第 1 列必须被封在 MAX_ID_DISPLAY_WIDTH 以内，实际是 " + w[0]);
        assertTrue(w[0] >= NetworkRenderer.MAX_ID_DISPLAY_WIDTH - 1,
            "也不该白白浪费一格，实际是 " + w[0]);
        String cellText = rows.get(0).cells().get(0).text();
        assertTrue(NetworkRenderer.displayWidth(cellText) <= NetworkRenderer.MAX_ID_DISPLAY_WIDTH,
            "后端名格超宽：" + cellText);
        assertTrue(cellText.endsWith("…"), "截断必须看得出来：" + cellText);
        // 前端（20 - 1 = 19 格；汉字占 2 格 → 9 个汉字）保留，不能被截成一个空壳。
        assertTrue(cellText.startsWith("服服服"), "至少要保留一部分真实名字：" + cellText);

        // 整行总宽度有上界：6 列各封 MAX_CELL_WIDTH，加 5 处两空格分隔。
        int total = 0;
        for (int i = 0; i < w.length; i++) total += w[i];
        assertTrue(total + 2 * (w.length - 1) <= 6 * NetworkRenderer.MAX_CELL_WIDTH + 10,
            "整行宽度的上界不成立：" + total);
    }

    @Test
    @DisplayName("列宽：整表宽度不依赖调用顺序（单格超长被总闸封住）")
    void tableWidthIsBoundedWithoutRelyingOnCallOrder() {
        // 「靠 columnWidths 取全表最大值兜住」是一种只在调用顺序正确时才成立的安全。
        // 这条直接把一格里塞进一个荒谬的长串，断言列宽仍被封住、且 padTo 会截断它。
        List<NetworkRenderer.Row> rows = List.of(
            NetworkRenderer.renderOne(new NetworkView().snapshot(1000L, 10, List.of("x")).get(0)));
        int[] w = NetworkRenderer.columnWidths(rows, NetworkRenderer.HEADERS);
        for (int i = 0; i < w.length; i++) {
            assertTrue(w[i] <= NetworkRenderer.MAX_CELL_WIDTH,
                "第 " + i + " 列超过了 MAX_CELL_WIDTH：" + w[i]);
        }

        // padTo 自己也得防住：即使调用方传一个夸张的宽度，返回值也不会是那个长度。
        String huge = "长".repeat(2000);
        String padded = NetworkRenderer.padTo(huge, 5000);
        assertTrue(NetworkRenderer.displayWidth(padded) > NetworkRenderer.MAX_CELL_WIDTH,
            "padTo 本身不管上限（上限是列宽的事），但下面这条不变式必须成立");
        String cut = NetworkRenderer.padTo(huge, NetworkRenderer.MAX_CELL_WIDTH);
        assertEquals(NetworkRenderer.MAX_CELL_WIDTH, NetworkRenderer.displayWidth(cut));
    }

    @Test
    @DisplayName("量级：tps1m = 1e308 当作没有值，整表列宽不受影响")
    void absurdTpsMagnitudeIsTreatedAsMissing() {
        // 实测修复前：该格字符串 312 个字符，全表列宽 [8,4,312,4,4,8]，整行 346 格。
        // 而且影响的是**所有行**，不只是坏行——列宽取全表最大值。
        assertEquals("—", NetworkRenderer.tpsCell(1e308).text(),
            "1e308 不是一次测量，必须写 — 而不是 309 位的数字");
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.tpsCell(1e308).level(),
            "不可信的值绝不能标绿");
        assertEquals("—", NetworkRenderer.tpsCell(-1.0).text(), "负数 TPS 不是读数");
        assertEquals("—", NetworkRenderer.tpsCell(-0.0001).text());
        assertEquals("—", NetworkRenderer.tpsCell(Double.NaN).text(), "NaN 不是读数");
        assertEquals("—", NetworkRenderer.tpsCell(1000.5).text(), "越过物理上限");
        // 上界之内仍然照常显示（sprint 场景可能到几百）。
        assertEquals("1000.00", NetworkRenderer.tpsCell(1000.0).text());
        assertEquals("35.00", NetworkRenderer.tpsCell(35.0).text());

        // 关键：0 是合法读数（服务器冻结），必须照常判红，不能被当成"没有值"。
        assertEquals("0.00", NetworkRenderer.tpsCell(0.0).text(),
            "0 是「服务器冻结」的真实读数，写成 — 会掩盖最严重的故障");
        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.tpsCell(0.0).level());
    }

    @Test
    @DisplayName("量级：荒谬 tps1m 不进全网平均（否则整个面板读数被污染）")
    void absurdTpsDoesNotPoisonAverage() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        feed(view, okPacket("good", now - 1_000L, 19.5, 3));
        // tps1m = 1e308 通过 JSON 解析是合法的 double；它若进平均，全网读数变成 ≈5e307。
        feed(view, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"evil\",\"sentAt\":" + (now - 1000)
                + ",\"collectedAt\":" + (now - 1000)
                + ",\"alive\":true,\"players\":1,\"tps1m\":1e308}")));
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        assertEquals(19.5, NetworkView.averageTps1m(nodes), 1e-9,
            "量级不合法的读数必须排除，不只是不显示");
    }

    @Test
    @DisplayName("MSPT 负哨兵：-1.0 是「没采到」，绝不是绿色的 -1.0")
    void negativeMsptIsUnknownNotGreen() {
        // 这条特意用**字面量 -1.0**，不引用任何常量：用常量会让"常量哪天被改成别的值"
        // 时测试跟着一起变绿/变红，等于自指。这里钉的是"小于等于 0 就是不可信"这个事实。
        assertEquals("—", NetworkRenderer.msptCell(-1.0).text(),
            "后端 TPS 反推路径在满 20 TPS 时置 msptAvg = -1，渲染成 -1.0 就是把哨兵当读数");
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.msptCell(-1.0).level(),
            "负 MSPT 绝不能标绿——修复前 -1.0 落进 else 分支被染成绿色");
        assertEquals("—", NetworkRenderer.msptCell(0.0).text(),
            "0 毫秒/tick 不是真实读数（后端 ThresholdColors 对 MSPT 用 value <= 0 → UNKNOWN）");
        assertEquals(NetworkRenderer.Level.UNKNOWN, NetworkRenderer.msptCell(0.0).level());
        assertEquals("—", NetworkRenderer.msptCell(-0.5).text());
        assertEquals("—", NetworkRenderer.msptCell(60_000.5).text(), "越过物理上限");

        // 上界之内照常显示：卡住几秒是真实且该看见的故障。
        assertEquals(NetworkRenderer.Level.CRITICAL, NetworkRenderer.msptCell(3000.0).level(),
            "3 秒/tick 是必须被看见的故障，不能当「没有值」");
        assertEquals(NetworkRenderer.Level.OK, NetworkRenderer.msptCell(0.1).level());

        // 与后端同口径的直接证据：-1 在后端是 UNKNOWN，在代理这里也必须是 UNKNOWN。
        assertEquals(NetworkRenderer.tpsCell(-1.0).level(), NetworkRenderer.msptCell(-1.0).level(),
            "同一个坏值在 TPS 格与 MSPT 格必须给出同一类结论");
    }

    @Test
    @DisplayName("在线合计：只算 OK，但把「没计入多少」写出来")
    void playerTotalExpressesIncompleteness() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        feed(view, okPacket("a", now - 1_000L, 20.0, 10));
        feed(view, okPacket("b", now - 120_000L, 19.0, 50));   // STALE：50 人，不计入
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());

        NetworkView.PlayerTotal t = NetworkView.totalPlayers(nodes);
        assertEquals(10, t.counted(), "只合计 OK 的 a");
        assertEquals(50, t.uncountedKnown(), "b 的 50 人已知但不可信，必须被记下来");
        assertEquals(0, t.uncountedUnknown());
        assertFalse(t.isComplete(), "合计只覆盖了部分后端，必须标出来");

        // 最关键的一条：面板文字必须让人看出「不是全服只有 10 人」。
        assertEquals("10（+50 未计入）", NetworkRenderer.playersTotalText(t),
            "只写 10 会被读成「全服在线 10 人」，差了一个数量级且看不出差在哪");
    }

    @Test
    @DisplayName("在线合计：连读数都没有的后端单列成「另 N 台无读数」")
    void playerTotalSeparatesUnknownFromUncounted() {
        long now = 100_000L;
        NetworkView view = new NetworkView();
        feed(view, okPacket("a", now - 1_000L, 20.0, 7));
        feed(view, okPacket("b", now - 120_000L, 19.0, 3));   // STALE，有数
        // STALE 且 players = -1：有包、但连玩家数都没采到。
        feed(view, SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"c\",\"sentAt\":" + (now - 120_000L)
                + ",\"collectedAt\":" + (now - 120_000L) + ",\"alive\":true,\"tps1m\":19.0}")));
        // NO_DATA：从未上报（典型是空服），不该被算作"不完整"。
        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of("d"));

        NetworkView.PlayerTotal t = NetworkView.totalPlayers(nodes);
        assertEquals(7, t.counted());
        assertEquals(3, t.uncountedKnown(), "b 有数但过期");
        assertEquals(1, t.uncountedUnknown(), "c 连玩家数都没有");
        assertEquals("7（+3 未计入 · 另 1 台无读数）", NetworkRenderer.playersTotalText(t));
    }

    @Test
    @DisplayName("在线合计：全是「无数据」时不解「不完整」（空服是正常形态）")
    void noDataAloneDoesNotMarkIncomplete() {
        NetworkView view = new NetworkView();
        List<NetworkView.Node> nodes = view.snapshot(1000L, 10, List.of("a", "b"));
        NetworkView.PlayerTotal t = NetworkView.totalPlayers(nodes);
        // 从没上报 = 几乎必然空服（plugin messaging 走玩家连接，没人就没通道）。
        // 若把它算作"合计不完整"，每个有若干空服后端的网络都会永远带着标记，标记就贬值了。
        assertTrue(t.isComplete(), "只有 NO_DATA 时不该标「不完整」");
        assertNull(t.counted(), "但没有可计入数据时仍是 null，不是 0");
        assertEquals("—", NetworkRenderer.playersTotalText(t));
    }

    private static NetworkView.Node find(List<NetworkView.Node> nodes, String id) {
        for (NetworkView.Node n : nodes) {
            if (n.serverId().equals(id)) return n;
        }
        throw new AssertionError("node not found: " + id);
    }

    // ==================== P1-V：伪造 / 不同步的时间戳 ====================
    //
    // 这一节的每条用例都对应一个"修复前是绿的、现在必须红"的靶点：
    //   修复前 SummaryPacket.ageMs 只判 collectedAt <= 0，负年龄被 clamp 成 0
    //   → 未来时间戳 = 年龄恒 0 = 状态恒 OK。下面 4 条场景（正常延迟 / 后端快 5 分钟 /
    //   后端慢 5 分钟 / 伪造未来）里，只有第一条在修复前也是 OK，其余三条都会
    //   被伪造成"健康"或错误地"永远过期"。

    /** 造一条"后端时钟相对代理偏移 {@code skewMs}"的包，并在 {@code receivedAt} 收到。 */
    private static SummaryPacket packetWithSkew(String id, long proxyReceivedAt, long skewMs,
                                                long collectedAgoOnBackendMs, boolean alive) {
        // 后端时钟 = 代理时钟 + skew。它写下的 collectedAt 是"后端此刻 - 采集延迟"。
        long backendNow = proxyReceivedAt + skewMs;
        long collectedAt = backendNow - collectedAgoOnBackendMs;
        return SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + id
            + "\",\"sentAt\":" + backendNow + ",\"collectedAt\":" + collectedAt
            + ",\"alive\":" + alive + ",\"players\":3,\"tps1m\":19.5,\"msptAvg\":12.0,"
            + "\"pushIntervalSec\":10}"));
    }

    @Test
    @DisplayName("时钟：两端时间一致时年龄即 collectedAt 的年龄")
    void normalClockGivesRealAge() {
        long now = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        // 在 now+2000 收到"采集于 now"的包：年龄应当就是 2000ms 的采集→到达耗时。
        view.onPacket(packetWithSkew("a", now, 0L, 2_000L, true), now + 2_000L);

        NetworkView.Node n = view.snapshot(now + 2_000L, 10, List.of()).get(0);
        assertEquals(NetworkView.State.OK, n.state());
        assertEquals(2_000L, n.ageMs(), 1L, "两端同钟时年龄应等于真实的采集→到达耗时");
    }

    @Test
    @DisplayName("时钟：后端快 5 分钟不再永远 OK —— 停摆后必须滑向过期")
    void backendFastFiveMinutesEventuallyGoesStale() {
        // 修复前：now - collectedAt 恒为负 → clamp 0 → 永远 OK。
        // 这正是"后端卡死、面板仍显示正常并展示冻结数字"的那条路径。
        long t0 = 1_700_000_000_000L;
        long skew = 5L * 60_000L;   // 后端钟快 5 分钟
        NetworkView view = new NetworkView();
        // 先喂几个健康包，让偏移被学到（真实世界的常态）。
        for (int i = 0; i < 4; i++) {
            // 收到时刻 t0-30s / -20s / -10s / 0s；每包的"采集→到达"耗时都是 1 秒。
            long at = t0 - (3 - i) * 10_000L;
            view.onPacket(packetWithSkew("fast", at, skew, 1_000L, true), at);
        }
        assertEquals(skew, view.skewMs("fast"), 1_000L,
            "学到的偏移应接近真实的 +5 分钟（误差只应有网络延迟量级）");

        // 最后一包在 t0 收到；此后后端停摆。用真实时间前进来判过期。
        NetworkView.Node fresh = view.snapshot(t0, 10, List.of()).get(0);
        assertEquals(NetworkView.State.OK, fresh.state(),
            "刚发的包必须正常 —— 校正正确时，钟快 5 分钟与钟准的结论一样");
        assertEquals(1_000L, fresh.ageMs(), 1_000L, "年龄应约为真实采集延迟，而不是 0");
        // 3 × 10s = 30s 后仍未更新 → 必须过期。修复前这里恒 OK。
        NetworkView.Node later = view.snapshot(t0 + 60_000L, 10, List.of()).get(0);
        assertEquals(NetworkView.State.STALE, later.state(),
            "钟快 5 分钟的后端停摆 60 秒后必须显示「数据过期」，"
                + "修复前它会被 clamp 成 age=0 而永远「正常」");
    }

    @Test
    @DisplayName("时钟：后端慢 5 分钟不再永远过期 —— 健康上报必须显示正常")
    void backendSlowFiveMinutesIsNotPermanentlyStale() {
        // 反方向：修复前 now - collectedAt 恒为 +5min+，年龄永远越过过期阈值 → 永远 STALE。
        long t0 = 1_700_000_000_000L;
        long skew = -5L * 60_000L;   // 后端钟慢 5 分钟
        NetworkView view = new NetworkView();
        for (int i = 0; i < 4; i++) {
            long at = t0 - (3 - i) * 10_000L;
            view.onPacket(packetWithSkew("slow", at, skew, 1_000L, true), at);
        }

        NetworkView.Node n = view.snapshot(t0, 10, List.of()).get(0);
        assertEquals(NetworkView.State.OK, n.state(),
            "钟慢 5 分钟的后端只要在正常上报，就必须显示「正常」——"
                + "修复前它的年龄恒为 5 分钟以上，永远「数据过期」");
        assertEquals(1_000L, n.ageMs(), 1_000L);
    }

    @Test
    @DisplayName("伪造：collectedAt 在未来 1 天 → 不可信，绝不是 OK")
    void forgedFutureDayIsNotTrusted() {
        long now = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        long fake = now + 24L * 60L * 60_000L;
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"liar\",\"sentAt\":" + fake
                + ",\"collectedAt\":" + fake + ",\"alive\":true,\"players\":9,"
                + "\"tps1m\":20.0}")), now);

        NetworkView.Node n = view.snapshot(now, 10, List.of()).get(0);
        assertEquals(NetworkView.State.CLOCK_SKEW, n.state(),
            "未来 1 天的时间戳必须判为不可信。修复前 ageMs 把它 clamp 成 0 → 恒 OK");
        assertEquals(NetworkView.ClockIssue.FUTURE, n.clockIssue());
        assertEquals(24L * 60L * 60_000L, n.aheadMs(), 1_000L, "要能说清超前多少");
        assertEquals(Long.MAX_VALUE, n.ageMs(), "不可信的行不该给一个看起来精确的年龄");
        assertFalse(n.contributesToAggregate(), "不可信的数据不能进全网聚合");
    }

    @Test
    @DisplayName("伪造：collectedAt 在未来 1 小时 / 5 秒的边界行为")
    void forgedFutureHourRejectedButFiveSecondsTolerated() {
        long now = 1_700_000_000_000L;

        // 1 小时：远超容差，判为不可信。
        NetworkView far = new NetworkView();
        long fakeHour = now + 60L * 60_000L;
        far.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"h\",\"sentAt\":" + fakeHour
                + ",\"collectedAt\":" + fakeHour + ",\"alive\":true,\"players\":1}")), now);
        assertEquals(NetworkView.State.CLOCK_SKEW, far.snapshot(now, 10, List.of()).get(0).state(),
            "未来 1 小时必须判为不可信");

        // 5 秒：合理范围（网络延迟 / 采集粒度 / 轻微漂移），必须照常接受。
        NetworkView near = new NetworkView();
        long almostNow = now + 5_000L;
        near.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"n\",\"sentAt\":" + almostNow
                + ",\"collectedAt\":" + almostNow + ",\"alive\":true,\"players\":1}")), now);
        NetworkView.Node ok = near.snapshot(now, 10, List.of()).get(0);
        assertEquals(NetworkView.State.OK, ok.state(),
            "未来 5 秒属于合理的网络延迟/时钟漂移，不能报异常——"
                + "把它也判成异常会让面板在最普通的同机部署下满屏「时钟异常」");
        assertEquals(0L, ok.ageMs(), "轻微超前被 clamp 到 0 岁是合理的（它就是刚采的）");
    }

    @Test
    @DisplayName("伪造上界正好在 MAX_FUTURE_AHEAD_MS：之内接受、之外拒绝")
    void futureToleranceBoundaryIsExact() {
        long now = 1_700_000_000_000L;
        // 关键：sentAt 诚实（= 到达时刻），只把 collectedAt 推到未来。这样偏移估计为 0，
        // 判定的就是纯粹的"超前量"边界。若两个字段一起前移，偏移会把它吸收掉一万（最多
        // MAX_CORRECTABLE_SKEW_MS），那是"坏钟容忍度"而不是这条边界要测的东西。
        long within = now + SummaryPacket.MAX_FUTURE_AHEAD_MS;
        long beyond = now + SummaryPacket.MAX_FUTURE_AHEAD_MS + 1L;

        NetworkView a = new NetworkView();
        a.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":" + now
                + ",\"collectedAt\":" + within + ",\"alive\":true}")), now);
        assertEquals(NetworkView.State.OK, a.snapshot(now, 10, List.of()).get(0).state(),
            "恰好等于容差必须接受（边界是闭的，否则真实抖动会在阈值附近闪烁）");

        NetworkView b = new NetworkView();
        b.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"b\",\"sentAt\":" + now
                + ",\"collectedAt\":" + beyond + ",\"alive\":true}")), now);
        assertEquals(NetworkView.State.CLOCK_SKEW, b.snapshot(now, 10, List.of()).get(0).state(),
            "超出 1ms 就必须拒绝 —— 否则上界形同虚设");
    }

    @Test
    @DisplayName("伪造：只改 collectedAt（不改 sentAt）时偏移无法吸收，立即判不可信")
    void forgedCollectedAtOnlyIsCaughtImmediately() {
        // 这条是最好的"靶点"：修复前 ageMs 会把这个未来时间戳 clamp 成 0 → 恒 OK。
        // 现在它连偏移都无法解释（sentAt 是诚实的），必须立刻落到 CLOCK_SKEW。
        long now = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"liar\",\"sentAt\":" + now
                + ",\"collectedAt\":" + (now + 24L * 60L * 60_000L)
                + ",\"alive\":true,\"tps1m\":20.0}")), now);

        NetworkView.Node n = view.snapshot(now, 10, List.of()).get(0);
        assertEquals(NetworkView.State.CLOCK_SKEW, n.state());
        assertEquals(NetworkView.ClockIssue.FUTURE, n.clockIssue());
        assertEquals(0L, view.skewMs("liar"), 1L,
            "诚实的 sentAt 意味着学不到任何偏移 —— 伪造者不能靠 collectedAt 制造偏移");
    }

    @Test
    @DisplayName("伪造样本不会污染偏移估计（否则所有后端一起失真）")
    void forgedSampleDoesNotPoisonSkewEstimate() {
        long t0 = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        // 正常后端：钟准。
        view.onPacket(packetWithSkew("good", t0, 0L, 500L, true), t0);
        assertEquals(0L, view.skewMs("good"), 500L);

        // 恶意后端：把 sentAt 前移一天。样本超出 ±10 分钟 → 不进窗口。
        long fake = t0 + 24L * 60L * 60_000L;
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"liar\",\"sentAt\":" + fake
                + ",\"collectedAt\":" + fake + ",\"alive\":true}")), t0);
        assertEquals(0L, view.skewMs("liar"),
            "越界样本不能被学进去 —— 否则该后端的「伪造预算」会跟着涨");
        assertEquals(0L, view.skewMs("good"),
            "偏移是按后端估的，一条伪造包不能影响别的后端");

        // 伪造者自己那一行仍必须不可信。
        List<NetworkView.Node> nodes = view.snapshot(t0, 10, List.of());
        assertEquals(NetworkView.State.CLOCK_SKEW, find(nodes, "liar").state());
        assertEquals(NetworkView.State.OK, find(nodes, "good").state());
    }

    @Test
    @DisplayName("伪造的收益有上限：伪造包到期后必须过期，不能永远 OK")
    void forgedTimestampsCanOnlyBuyBoundedGrace() {
        // 最坏情形：伪造者希望在**任意时刻**都算出 0 岁。修复前这是真的——负年龄被
        // clamp 成 0，与时间无关。现在年龄锚定在到达时刻，过了过期窗口就必须滑向 STALE。
        long t0 = 1_700_000_000_000L;
        long grant = SummaryPacket.MAX_CORRECTABLE_SKEW_MS;   // 伪造 10 分钟（再多直接判不可信）
        NetworkView view = new NetworkView();
        view.onPacket(packetWithSkew("cheat", t0, grant, 0L, true), t0);

        assertEquals(NetworkView.State.OK,
            view.snapshot(t0, 10, List.of()).get(0).state(),
            "刚到达时确实能被骗到一次「正常」——这是可接受的上界（只值一次）");

        // 真实时间前进到超过过期窗口之后，必须重新变成过期。
        // 修复前这里恒 OK，且 age 恒 0 —— 那才是"永久健康"。
        NetworkView.Node later = view.snapshot(t0 + 60_000L, 10, List.of()).get(0);
        assertEquals(NetworkView.State.STALE, later.state(),
            "伪造只能骗到到达那一刻，30 秒后必然滑向「数据过期」；修复前恒 OK");
        assertTrue(later.ageMs() <= 60_000L,
            "年龄必须锚定在到达时刻而不是被伪造成负数再 clamp，实际：" + later.ageMs());
    }

    @Test
    @DisplayName("渲染：时钟异常整行 UNKNOWN，年龄格写「早 N」而不是「0秒前」")
    void clockSkewRowNeverRendersZeroSeconds() {
        long now = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        long fake = now + 60L * 60_000L;
        // sentAt 诚实、collectedAt 在未来 1 小时：偏移吸收不了，必然判不可信。
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"liar\",\"sentAt\":" + now
                + ",\"collectedAt\":" + fake + ",\"alive\":true,\"players\":4,"
                + "\"tps1m\":19.9,\"msptAvg\":11.0}")), now);

        NetworkRenderer.Row row = NetworkRenderer.renderOne(
            view.snapshot(now, 10, List.of()).get(0));
        assertEquals("时钟异常", row.cells().get(1).text());
        // 第 0 格（后端名）、第 1 格（状态）、第 5 格（年龄）必须 UNKNOWN：
        // 绿色会被读成健康，而"时钟异常"恰恰是不该下的结论。
        assertEquals(NetworkRenderer.Level.UNKNOWN, row.cells().get(0).level());
        assertEquals(NetworkRenderer.Level.UNKNOWN, row.cells().get(1).level());
        assertEquals(NetworkRenderer.Level.UNKNOWN, row.cells().get(5).level());
        String ageCell = row.cells().get(5).text();
        assertTrue(ageCell.contains("早 1小时"),
            "年龄格要说明超前多少，实际是：" + ageCell);
        assertFalse(ageCell.contains("0秒前"),
            "「0秒前」正是伪造时间戳想换来的那句话，绝不能出现：" + ageCell);
    }

    @Test
    @DisplayName("汇总行：时钟异常单独计数，不混进「数据过期」")
    void summaryLineCountsClockSkewSeparately() {
        long now = 1_700_000_000_000L;
        NetworkView view = new NetworkView();
        view.onPacket(packetWithSkew("ok", now, 0L, 1_000L, true), now);
        // sentAt 诚实、collectedAt 在未来 1 天：偏移吸收不了，必然判不可信。
        view.onPacket(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"liar\",\"sentAt\":" + now
                + ",\"collectedAt\":" + (now + 24L * 60L * 60_000L)
                + ",\"alive\":true,\"tps1m\":20.0}")), now);

        List<NetworkView.Node> nodes = view.snapshot(now, 10, List.of());
        String line = NetworkRenderer.summaryLine(nodes,
            NetworkView.averageTps1m(nodes), NetworkView.totalPlayers(nodes));
        assertTrue(line.contains("正常 1"), line);
        assertTrue(line.contains("时钟异常 1"), line);
        assertFalse(line.contains("数据过期"), "时钟异常不该被混报成数据过期：" + line);
        assertEquals(19.5, NetworkView.averageTps1m(nodes), 1e-9,
            "只有 ok 那一台（19.5）参与平均；时钟异常的后端不能进聚合，"
                + "否则伪造的 20.0 会把全网读数拉高");
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
        // ⚠️ 这条测试此前是**自指的**：它用 `MAX_SERVER_ID_LENGTH + 1` 构造长度，
        // 而生产代码判的是 `server.length() > MAX_SERVER_ID_LENGTH`。两者同一个常量 →
        // 两边一起变，把常量从 64 改成 1 亿，这条仍然绿，什么也没测到。
        //
        // 修法：把"线上真正的上限"写成**字面量**（64），与后端
        // SummaryPublisher.MAX_SERVER_ID_LENGTH 的契约值逐字对应。常量若被改坏，
        // 下面第一条断言立刻红。这是本项目反复用的同一招（见 unavailableCountIsMinusOne）。
        assertEquals(64, SummaryPacket.MAX_SERVER_ID_LENGTH,
            "协议约定 server-id 上限就是 64。本测试的构造长度由这个字面量独立推出，"
                + "不再与常量共变——否则改坏常量时测试会跟着一起绿");

        // 65 个字符：超限，必须拒。
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 65; i++) over.append('a');
        assertNull(SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + over
            + "\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")),
            "65 字符的 serverId 必须被拒（上限是字面量 64，不是常量倒推出来的值）");

        // 恰好 64：边界内，照常接受（别把闸门做过头）。
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 64; i++) ok.append('a');
        assertNotNull(SummaryPacket.parse(json("{\"v\":1,\"kind\":\"summary\",\"server\":\"" + ok
            + "\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")),
            "恰好 64 字符必须被接受——闸门是 > 64，不是 >= 64");

        // 再加一条独立靶点：1 字符的 id 永远合法（防止有人把闸门写成 >= MAX 之类的反向错误）。
        assertNotNull(SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"a\",\"sentAt\":1,\"collectedAt\":1,\"alive\":true}")));
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
            // 到达时刻与 sentAt 同基，让每一条都真的产出偏移样本。
            view.onPacket(okPacket("srv" + i, 1_000L, 20.0, 1), 2_000L);
        }
        assertEquals(NetworkView.MAX_TRACKED_SERVERS, view.trackedCount(),
            "跟踪数必须被限制在 MAX_TRACKED_SERVERS，否则恶意 id 能填满堆");
        // 最新的一定还在，最老的一定被淘汰了
        assertTrue(view.knownServers().contains("srv" + (over - 1)), "最新的必须保留");
        assertFalse(view.knownServers().contains("srv0"), "最久未更新的应被淘汰");
        // 偏移样本与锚点也必须跟着淘汰：它们是本轮新增的两张按 id 索引的表，
        // 只清 latest 会留下一条与"恶意 id 撑爆堆"等价的泄漏路径。
        assertEquals(0L, view.skewMs("srv0"),
            "被淘汰的 id 不该仍在偏移表里留下样本（0 = 没有样本）");
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
                            // 显式给到达时刻（与 sentAt 同一时间基）：让偏移样本有效，
                            // 从而真正压到 skewSamples / anchors 这两条新路径。
                            view.onPacket(okPacket("r" + r + "-w" + id + "-i" + i, 1_000L, 20.0, 1),
                                2_000L);
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
        view.onPacket(okPacket("a", 1_000L, 20.0, 1), 2_000L);
        assertEquals(1, view.trackedCount());
        view.clear();
        assertEquals(0, view.trackedCount(), "clear() 必须真的清空，否则 reset 子命令是假的");
    }

    // ==================== P2：控制字符过滤 ====================

    /**
     * 控制字符必须被剔除，否则面板行会被劈成两行 / 列对齐全乱。
     *
     * <p>修前 NUL / BEL / {@code \n} / {@code \t} / ESC 实测全部原样通过：一条
     * {@code server="survival\nlobby"} 就能让面板上凭空多出一行，管理员会把不存在的
     * 服务器当成真的。</p>
     */
    @Test
    @DisplayName("控制字符被剔除：\\n / \\r / \\t / NUL / BEL / ESC / DEL / C1")
    void stripsControlCharactersFromServerId() {
        // 逐字符构造 id："ok" + 一个坏字符 + "ok"，解析回来后坏字符必须消失。
        char[] bad = {
            '\n', '\r', '\t', '\u0000', '\u0007', '\u001B', '\u007F', '\u0085', '\u009F'
        };
        for (char c : bad) {
            String raw = "survival" + c + "lobby";
            SummaryPacket p = SummaryPacket.parse(json(
                "{\"v\":1,\"kind\":\"summary\",\"server\":\"" + escapeJson(raw) + "\","
                    + "\"sentAt\":1,\"collectedAt\":1,\"alive\":true}"));
            assertNotNull(p, "含控制字符的包不该被整包拒（那会让后端隐身）：U+"
                + String.format("%04X", (int) c));
            assertEquals("survivallobby", p.serverId(),
                "U+" + String.format("%04X", (int) c) + " 必须从 serverId 里剔除");
            // 关键反证：如果实现改成"原样保留"，这里会红；如果改成"整包拒收"，上面的
            // assertNotNull 会红。两条一起把"剔除"这个语义钉死。
            assertFalse(p.serverId().contains(String.valueOf(c)));
        }
    }

    @Test
    @DisplayName("§ (U+00A7) 也剔除：legacy 格式码前缀不能进日志/聊天回放")
    void stripsSectionSign() {
        SummaryPacket p = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"\\u00A7csurvival\","
                + "\"sentAt\":1,\"collectedAt\":1,\"alive\":true}"));
        assertNotNull(p);
        assertEquals("csurvival", p.serverId(),
            "§ 必须剔除（保留的话 §c 会在任何 legacy 序列化场合把面板染色）");
    }

    @Test
    @DisplayName("过滤不过度：普通字符、中文、emoji、连字符都原样保留")
    void sanitizeKeepsLegitimateCharacters() {
        assertEquals("survival-1", SummaryPacket.sanitize("survival-1"));
        assertEquals("世界一区", SummaryPacket.sanitize("世界一区"),
            "中文 server-id 必须无损（README 明确支持）");
        assertEquals("srv_2.eu", SummaryPacket.sanitize("srv_2.eu"));
        // 补充平面字符按代理对存储，不能被当成控制字符砍掉一半。
        assertEquals("srv\ud83d\ude00", SummaryPacket.sanitize("srv\ud83d\ude00"));
        // null / 空串原样返回，不 NPE。
        assertNull(SummaryPacket.sanitize(null));
        assertEquals("", SummaryPacket.sanitize(""));
    }

    @Test
    @DisplayName("换行不会把面板劈成两行：解析后的 id 一定单行")
    void newlineCannotSplitThePanelRow() {
        SummaryPacket p = SummaryPacket.parse(json(
            "{\"v\":1,\"kind\":\"summary\",\"server\":\"real\\n fake-row 20.00\","
                + "\"sentAt\":1,\"collectedAt\":1,\"alive\":true}"));
        assertNotNull(p);
        assertFalse(p.serverId().contains("\n"), "serverId 里绝不能残留换行");
        assertFalse(p.serverId().contains("\r"));
    }

    /** 把一个 Java 字符串转成 JSON 字符串字面量的内容（只处理控制字符与本测试用到的字符）。 */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c == 0x7F) {
                sb.append(String.format("\\u%04X", (int) c));
            } else if (c >= 0x80 && c <= 0x9F) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ==================== 来源绑定 ====================

    /**
     * 核心用例：A 用 B 的 {@code server-id} 发一条包，必须被丢弃而不是覆盖 B。
     *
     * <p>修前只判 {@code instanceof ServerConnection}，不比对来源——任何后端都能冒充任何
     * 其它后端，把对方的 TPS 改成 0 或改成 20。</p>
     */
    @Test
    @DisplayName("来源绑定：A 冒充 B 的 server-id 必须被丢弃（不能覆盖 B 的数据）")
    void squattingAnotherServersIdIsRejected() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();

        // B 先正常上报，把 "survival-2" 这个身份绑到自己身上（名字一致 → 无告警）。
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("survival-2", "survival-2"));
        assertEquals("survival-2", binder.boundServerOf("survival-2"));

        // A 想用 "survival-2" 上报：来源是另一条后端 → 必须 REJECT_SQUAT。
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.REJECT_SQUAT,
            binder.bind("survival-2", "survival-1"),
            "同一个 server-id 从另一条后端来，就是覆盖攻击的形状，必须拒");

        // 再确认绑定没被那次尝试改掉（否则攻击者只要发一次就能夺走身份）。
        assertEquals("survival-2", binder.boundServerOf("survival-2"));
    }

    @Test
    @DisplayName("来源绑定：同一后端持续用同一个 id 永远 OK（即便名字与 velocity.toml 不一致）")
    void sameBackendSameIdStaysOkEvenWhenNameDiffers() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();

        // 服主把 server-id 设成 "survival" 而 velocity.toml 里叫 "survival-1"：
        // 首次判定是"不一致但接受"（会告警引导他改），后续照常收数——绝不拒。
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.WARN_MISMATCH,
            binder.bind("survival", "survival-1"));
        for (int i = 0; i < 100; i++) {
            assertEquals(PerfScopeVelocity.SourceBinder.Verdict.WARN_MISMATCH,
                binder.bind("survival", "survival-1"),
                "名字不一致但身份自洽的后端必须一直能上报——强制一致会破坏现有部署");
        }
    }

    @Test
    @DisplayName("来源绑定：名字逐字一致时不告警（正常部署安安静静）")
    void matchingNameProducesNoWarning() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("lobby", "lobby"));
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("lobby", "lobby"));
    }

    @Test
    @DisplayName("来源绑定：取不到来源名时放行（不能因为 API 异常就让全网隐身）")
    void nullOriginIsTolerated() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("x", null),
            "取不到 velocity.toml 的名字时必须放行，否则一次 API 异常会让整个面板归零");
        // 而且不能把 "null" 绑进去——否则之后真实来源会被判成抢占。
        assertNull(binder.boundServerOf("x"));
        // 紧接着真实来源上报：名字与 id 一致 → OK，而不是被上面那次空来源影响。
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("x", "x"));
    }

    @Test
    @DisplayName("来源绑定：两个不同 id 由两台不同后端上报，互不干扰")
    void distinctIdsOnDistinctBackendsCoexist() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("a", "a"));
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("b", "b"));
        assertEquals(2, binder.size());
        // clear() 之后能重新绑定（reset 子命令的语义）。注意这里换成另一条后端，
        // 但 id 的绑定已被清空 → 允许重新绑定（不是抢占）。
        binder.clear();
        assertEquals(0, binder.size());
        assertEquals(PerfScopeVelocity.SourceBinder.Verdict.OK, binder.bind("a", "a"),
            "clear 之后允许重新绑定，否则换过后端的部署会永久丢包");
    }

    @Test
    @DisplayName("来源绑定表有界（网络输入驱动，不能无限增长）")
    void bindingTableIsBounded() {
        PerfScopeVelocity.SourceBinder binder = new PerfScopeVelocity.SourceBinder();
        int over = PerfScopeVelocity.SourceBinder.BINDINGS_MAX + 50;
        for (int i = 0; i < over; i++) {
            binder.bind("id" + i, "backend" + i);
        }
        assertTrue(binder.size() <= PerfScopeVelocity.SourceBinder.BINDINGS_MAX,
            "绑定表必须封顶，否则恶意后端能用不断变化的 id 把它撑大");
    }
}
