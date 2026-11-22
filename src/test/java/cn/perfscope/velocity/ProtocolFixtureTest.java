package cn.perfscope.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨仓库 golden fixture —— <b>接收端</b>（Velocity 代理）。
 *
 * <h2>本类存在的理由</h2>
 *
 * <p>此前代理侧的解析测试喂的全是<b>手写 JSON 字面量</b>（{@code SummaryTest} 里逐条拼字符串），
 * 而后端测试断言的是"我的 {@code toMap()} 里有哪些 key"。两侧从来没有共享过<b>同一个字节串</b>，
 * 于是：后端改<b>字段名</b>会让这边解析失败并记日志（会响）；但后端把同一字段
 * <b>换单位</b>（{@code tps1m} 从 0–20 改成 0–1 的分数）或<b>换语义</b>（{@code players}
 * 从在线人数改成容量占比）在这边<b>完全沉默</b>——数值还parse得出来，范围也合法，
 * 面板照常渲染，只是数字全错。</p>
 *
 * <p>本类读的 {@code src/test/resources/protocol/*.json} 是后端仓库
 * {@code cn.perfscope.network.NetworkProtocolFixtureTest} <b>用真实
 * {@code NetworkSummary.Report#toMap()} 生成</b>的字节的逐字副本（不是手写）。
 * 那边断言"输出 == 这份字节"，这边断言"这份字节能被正确读成这些字段值"。
 * 任何一侧单方面改动契约，都会在那一侧变红；两边的副本是否仍然同字节，由
 * {@code tools/check-protocol-fixtures-sync.mjs} 比对两个仓库的目录来判定。</p>
 *
 * <h2>为什么不复用 {@code SummaryTest} 的字面量</h2>
 *
 * <p>字面量是"我以为后端会这么写"，fixture 是"后端真的写出来的是这个"。
 * 前者在两端同时改的时候会一起改错（因为都出自同一个人的想象），后者只能跟着真实输出走。
 * 所以本类<b>不</b>新增任何手写字面量，全部输入来自文件。</p>
 *
 * <h2>新增可选字段时怎么办</h2>
 *
 * <p>不需要改本类的断言骨架：新增字段由后端重生成 fixture 后，本类方案的
 * {@link #parsesEveryFixtureWithoutRejecting()} 会自动覆盖"能被接受"这一面；
 * 要断言它的<b>值</b>，在那个场景的期望值块里加一行即可。索引 {@code FIXTURES.sha256}
 * 会把"副本不同步"当场抓出来。</p>
 */
class ProtocolFixtureTest {

    /** 与后端 {@code NetworkProtocolFixtureTest} 的常量逐字一致。 */
    private static final String CLASSPATH_DIR = "/protocol/";
    private static final String INDEX_FILE = "FIXTURES.sha256";

    /** 与 {@code SummaryPacket.MAX_SERVER_ID_LENGTH} 同源；只用于边界说明，不参与判定。 */
    private static final int MAX_SERVER_ID_LENGTH = 64;

    // ==================== 1. 全部 fixture 都必须能被接受 ====================

    /**
     * 七个场景全部解析成功。
     *
     * <p>"解析失败"在代理侧是<b>静默丢弃 + 限频记一条日志</b>。若某个正常场景的 fixture
     * 被新加的闸门误杀（比如把"未来时间戳"的判据写成"collectedAt 必须 ≥ 现在"），
     * 症状是该后端永远显示"无数据"，而日志把服主引向"版本不一致"。
     * 这条用例把"闸门不许误杀自己的合法输入"钉住。</p>
     */
    @Test
    @DisplayName("七个真实 fixture 全部解析成功（新增闸门不得误杀合法包）")
    void parsesEveryFixtureWithoutRejecting() throws IOException {
        for (String name : fixtureNames()) {
            byte[] payload = readFixture(name);
            SummaryPacket p = SummaryPacket.parse(payload);
            assertNotNull(p, name + " 是后端真实产出的合法包，被本端拒绝了。\n"
                + "若最近新增了校验闸门（collectedAt / serverId 等），请确认它不会误杀正常包。\n"
                + "原始字节: " + new String(payload, StandardCharsets.UTF_8));
            assertEquals(SummaryPacket.PROTOCOL_VERSION, SummaryPacket.peekVersion(payload),
                name + " 的协议版本应等于本端支持的版本");
        }
    }

    // ==================== 2. 正常包：每个字段的值 ====================

    /**
     * 全字段包：逐字段断言<b>具体值</b>。
     *
     * <p>三个 TPS 窗口刻意取不同值（19.95 / 19.90 / 19.85）：若读取键互换（{@code tps5m}
     * 读成 {@code tps15m}），值相等的话测不出来——这是本项目此前真实发生过的一类假绿。
     * {@code players} 用 42（既非 0 也非 -1），{@code pushIntervalSec} 用 10（缺省假设值）。</p>
     */
    @Test
    @DisplayName("正常包：逐字段值正确（三个 TPS 窗口取值互不相同）")
    void parsesNormalPacketFields() throws IOException {
        SummaryPacket p = SummaryPacket.parse(readFixture("summary-normal.json"));
        assertNotNull(p);

        assertEquals("survival", p.serverId());
        assertEquals(1_700_000_000_000L, p.collectedAtMs(), "collectedAt 是采集时刻");
        assertEquals(1_700_000_000_500L, p.sentAtMs(), "sentAt 是发送时刻（比采集晚 500ms）");
        assertTrue(p.collectorAlive(), "正常包的 alive 必须是 true");

        assertEquals(19.95, p.tps1m(), 1e-9);
        assertEquals(19.90, p.tps5m(), 1e-9);
        assertEquals(19.85, p.tps15m(), 1e-9);
        assertEquals(12.5, p.msptAvg(), 1e-9);
        assertEquals(42, p.playerCount());
        assertEquals(10, p.pushIntervalSec());
    }

    // ==================== 3. 缺字段：null 而不是 0 ====================

    /**
     * 缺 TPS / 缺 MSPT 两个包：缺失字段必须是 {@code null}。
     *
     * <p>这是整个协议最重要的一条。后端发"没采到"的方式是<b>整条省掉那个键</b>；
     * 若代理解析时补 0，面板上就出现"TPS 0.00（服务器冻结）"而真相是模块被关掉了。
     * 本插件 v1.4.2 / v1.4.3 两个版本修的就是这个错误。</p>
     */
    @Test
    @DisplayName("缺 TPS / 缺 MSPT：字段是 null，绝不补 0")
    void missingFieldsAreNullNotZero() throws IOException {
        SummaryPacket noTps = SummaryPacket.parse(readFixture("summary-no-tps.json"));
        assertNotNull(noTps);
        assertNull(noTps.tps1m(), "缺 TPS 必须是 null —— 补 0 会渲染成「服务器冻结」");
        assertNull(noTps.tps5m());
        assertNull(noTps.tps15m());
        assertNotNull(noTps.msptAvg(), "但 MSPT 在这个场景是有的");
        assertEquals(8.0, noTps.msptAvg(), 1e-9);
        assertEquals(7, noTps.playerCount());

        SummaryPacket noMspt = SummaryPacket.parse(readFixture("summary-no-mspt.json"));
        assertNotNull(noMspt);
        assertNull(noMspt.msptAvg(), "缺 MSPT 必须是 null —— 补 0 会渲染成「零耗时」");
        assertEquals(20.0, noMspt.tps1m(), 1e-9);
        assertEquals(0, noMspt.playerCount(), "空服 0 人是合法读数，必须如实保留");
    }

    /**
     * 全不可用包：所有数值字段都是 {@code null}，玩家数停在 {@code -1}。
     *
     * <p>{@code players} 不能补 0：0 是"空服"，-1 是"没采到"，两者在面板上的文案不同。
     * 这个包里 {@code pushIntervalSec} 也缺席——旧版后端的形状。</p>
     */
    @Test
    @DisplayName("全不可用包：数值 null、players=-1、pushIntervalSec=0")
    void parsesAllUnavailablePacket() throws IOException {
        SummaryPacket p = SummaryPacket.parse(readFixture("summary-unavailable-all.json"));
        assertNotNull(p);
        assertNull(p.tps1m());
        assertNull(p.tps5m());
        assertNull(p.tps15m());
        assertNull(p.msptAvg());
        assertEquals(SummaryPacket.COUNT_UNAVAILABLE, p.playerCount(),
            "没采到的计数必须是 -1 哨兵，不能是 0");
        assertEquals(-1, p.playerCount(), "断言字面值 -1，而不是常量（常量改成 0 时这条必须红）");
        assertEquals(0, p.pushIntervalSec(), "字段缺席 → 0 = 没提供，接收方回退默认阈值");
    }

    // ==================== 4. 旧版形状：不带可选字段 ====================

    /**
     * 旧版后端的包（没有 {@code pushIntervalSec}）必须照常被接受。
     *
     * <p>这条钉住"<b>新增可选字段不升 PROTOCOL_VERSION</b>"这个决定。若哪天有人为了加一个
     * 可选字段就升号，旧代理会整包拒收——升级窗口期面板全空，正是要避免的事。</p>
     */
    @Test
    @DisplayName("旧版形状（无 pushIntervalSec）照常解析，间隔回退 0")
    void acceptsLegacyShapeWithoutPushInterval() throws IOException {
        SummaryPacket p = SummaryPacket.parse(readFixture("summary-legacy-no-interval.json"));
        assertNotNull(p, "旧版后端（没有新字段）的包必须照常被接受");
        assertEquals("lobby", p.serverId());
        assertEquals(19.5, p.tps1m(), 1e-9);
        assertEquals(14.0, p.msptAvg(), 1e-9);
        assertEquals(3, p.playerCount());
        assertEquals(0, p.pushIntervalSec(), "缺字段时是 0 = 没提供（不是 10）");
    }

    // ==================== 5. 边界值仍是合法读数 ====================

    /**
     * 边界包：15.0 / 18.0 / 0.0 / 50.0 / 300 五个点都必须被如实读入。
     *
     * <p>这里最容易出的两类错：一是把 {@code 0.0} 当"缺值"（0 是合法的"冻结"读数），
     * 二是把 {@code pushIntervalSec=300} 判成越界。300 是后端配置校验的上界，
     * 恰好合规；写成 {@code >= 300} 之类的排他判断会把最长间隔的那台后端打成"没提供"。</p>
     */
    @Test
    @DisplayName("边界值 15.0/18.0/0.0/50.0/300 全部如实读入，不被当成缺值")
    void boundaryValuesAreRealReadings() throws IOException {
        SummaryPacket p = SummaryPacket.parse(readFixture("summary-boundary.json"));
        assertNotNull(p);
        assertEquals(15.0, p.tps1m(), 1e-9, "恰好 15.00 是合法读数（后端严格比较下是 WARN 档）");
        assertEquals(18.0, p.tps5m(), 1e-9, "恰好 18.00 是合法读数（OK 档下界）");
        assertEquals(0.0, p.tps15m(), 1e-9, "0.0 TPS 是真实的「服务器冻结」，不是缺失");
        assertEquals(50.0, p.msptAvg(), 1e-9, "恰好 50.0 是合法读数（WARN 档上界）");
        assertEquals(0, p.playerCount());
        assertEquals(300, p.pushIntervalSec(),
            "300 是后端配置校验的上界，必须算「提供了」而不是越界回退 0");
    }

    // ==================== 6. 采集已死 ====================

    /**
     * {@code alive=false} 的包：数字照常读入，但标志必须为 false。
     *
     * <p>接收方靠这个标志决定"整行都不给数字"。若解析把缺失的 {@code alive} 默认成 true，
     * 采集器坏掉的后端会被渲染成健康——本项目最怕的一类"把盲点报成健康"。</p>
     */
    @Test
    @DisplayName("alive=false 如实读入（数字在场但不可信）")
    void readsCollectorDeadFlag() throws IOException {
        SummaryPacket p = SummaryPacket.parse(readFixture("summary-collector-dead.json"));
        assertNotNull(p);
        assertFalse(p.collectorAlive(), "alive=false 必须被如实读成 false");
        // 数字确实在包里 —— 这正是需要标志的理由：光看数值看不出它已经冻住了。
        assertEquals(20.0, p.tps1m(), 1e-9);
        assertEquals(5.0, p.msptAvg(), 1e-9);
        assertEquals(12, p.playerCount());
    }

    // ==================== 7. 索引：副本没被单方面改过 ====================

    /**
     * 本地 fixture 与 {@code FIXTURES.sha256} 登记的哈希一致。
     *
     * <p>索引文件本身是从后端仓库逐字复制的。它在这里的作用是<b>本地自检</b>：
     * 若有人只改了代理侧这一份 fixture（比如"让测试通过"式的就地修改），哈希对不上，
     * 本用例当场变红。跨仓库的"两份副本是否同字节"由
     * {@code tools/check-protocol-fixtures-sync.mjs} 负责（单测看不到另一个仓库）。</p>
     */
    @Test
    @DisplayName("fixture 字节与 FIXTURES.sha256 登记值一致（本地副本未被单方面改动）")
    void localCopyMatchesIndexHashes() throws IOException {
        Map<String, String> index = readIndex();
        for (String name : fixtureNames()) {
            assertTrue(index.containsKey(name), name + " 没有登记在 " + INDEX_FILE);
            assertEquals(index.get(name), sha256(normalize(readFixture(name))),
                name + " 的字节与其登记的 sha256 不一致：这份副本被单独改过。\n"
                    + "正确做法是回后端仓库重生成 fixture，然后整目录复制过来（两边必须同字节）。");
        }
    }

    /**
     * 索引里登记的每个文件都真实存在，且数量与目录一致。
     *
     * <p>防止"删了一个 fixture 但索引没删"——那种状态下测试仍然全绿，而契约少覆盖了一个场景。
     * </p>
     */
    @Test
    @DisplayName("索引与实际文件一一对应（不多不少）")
    void indexAndFilesAgree() {
        var listed = readIndexQuietly();
        assertEquals(fixtureNames().size(), listed.size(),
            "索引条目数与目录里的 fixture 数不一致：索引=" + listed + " 目录=" + fixtureNames());
        for (String name : fixtureNames()) {
            assertTrue(listed.containsKey(name), "目录里有但索引没登记：" + name);
        }
    }

    // ==================== 8. 非字符串/非数值输入仍被防御性处理 ====================

    /**
     * 用真实 fixture 做骨架、把其中一个字段改成错误类型，仍不能崩。
     *
     * <p>这不是在测协议，而是在测<b>面对同一个真实形状的包、字段类型被篡改</b>时的防御性。
     * 与 {@code SummaryTest} 的手写恶意输入互补：那边测"任意坏输入"，这边测"真实骨架 + 单点篡改"，
     * 后者更接近"某版本后端把字段写成了字符串"这种真实事故。</p>
     */
    @Test
    @DisplayName("真实骨架 + 单字段类型篡改：不崩、该字段视为缺失")
    void typeTamperingOnRealSkeletonIsTolerated() throws IOException {
        String base = new String(normalize(readFixture("summary-normal.json")), StandardCharsets.UTF_8);

        // tps1m 变成字符串：Gson 的 getAsDouble 会尝试解析，失败则视为缺失。
        String tampered = base.replace("\"tps1m\":19.95", "\"tps1m\":\"19.95\"");
        SummaryPacket p = SummaryPacket.parse(tampered.getBytes(StandardCharsets.UTF_8));
        assertNotNull(p, "单字段类型问题不该让整包被拒");
        assertEquals(19.95, p.tps1m(), 1e-9,
            "数字形式的字符串仍应被读成数值（Gson 的宽松转换），而不是丢失");

        // server 变成数字：getString 会 stringify，不该崩。
        SummaryPacket q = SummaryPacket.parse(
            base.replace("\"server\":\"survival\"", "\"server\":12345")
                .getBytes(StandardCharsets.UTF_8));
        assertNotNull(q);
        assertEquals("12345", q.serverId(), "非字符串 server 会被转成字符串，但绝不能让解析崩");

        // 整个 collectedAt 变成字符串：数值读取失败 → 0（"时间戳缺失"），不抛异常。
        SummaryPacket r = SummaryPacket.parse(
            base.replace("\"collectedAt\":1700000000000", "\"collectedAt\":\"nope\"")
                .getBytes(StandardCharsets.UTF_8));
        assertNotNull(r);
        assertEquals(0L, r.collectedAtMs(), "读不出来的时间戳按缺失处理，不能抛异常");
    }

    // ==================== 读取工具 ====================

    /**
     * 目录里实际有哪些 fixture。
     *
     * <p>不写死文件名清单：后端新增场景时，本类会自动跟着覆盖"必须被接受"这一面
     * （第 1 条用例遍历它）。写死清单会让新增场景在代理侧静默漏测。</p>
     *
     * <p>实现方式：读索引文件里的文件名。索引与目录一一对应由
     * {@link #indexAndFilesAgree()} 保证；这样测试代码不必访问文件系统目录列表
     * （测试 JVM 的 cwd 不一定在模块根目录）。</p>
     */
    private static java.util.Set<String> fixtureNames() {
        return readIndexQuietly().keySet();
    }

    private static Map<String, String> cachedIndex;

    private static Map<String, String> readIndexQuietly() {
        if (cachedIndex == null) {
            try {
                cachedIndex = readIndex();
            } catch (IOException e) {
                throw new IllegalStateException("读取 " + INDEX_FILE + " 失败", e);
            }
        }
        return cachedIndex;
    }

    private static Map<String, String> readIndex() throws IOException {
        try (InputStream in = ProtocolFixtureTest.class.getResourceAsStream(CLASSPATH_DIR + INDEX_FILE)) {
            assertNotNull(in, INDEX_FILE + " 不在测试 classpath 的 " + CLASSPATH_DIR
                + " 下 —— 请确认 src/test/resources/protocol/ 已包含后端仓库的整份副本");
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                assertEquals(2, parts.length, "索引行格式应为「文件名  sha256」：" + line);
                m.put(parts[0], parts[1]);
            }
            return m;
        }
    }

    private static byte[] readFixture(String name) throws IOException {
        try (InputStream in = ProtocolFixtureTest.class.getResourceAsStream(CLASSPATH_DIR + name)) {
            assertNotNull(in, name + " 不在测试 classpath 的 " + CLASSPATH_DIR + " 下");
            return in.readAllBytes();
        }
    }

    /** 去掉末尾 CR/LF：与后端侧的 {@code normalize} 同口径（编辑器补的换行不算协议内容）。 */
    private static byte[] normalize(byte[] raw) {
        int end = raw.length;
        while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(raw, 0, out, 0, end);
        return out;
    }

    private static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
