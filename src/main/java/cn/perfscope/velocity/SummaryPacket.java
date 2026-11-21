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

    /** 快照年龄（毫秒）。负值按 0 处理——时钟不同步不该读成"未来的数据"。 */
    public long ageMs(long nowMs) {
        return collectedAtMs <= 0L ? Long.MAX_VALUE : Math.max(0L, nowMs - collectedAtMs);
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

    private static String getString(JsonObject o, String key, String dflt) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return dflt;
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return dflt;
        }
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
