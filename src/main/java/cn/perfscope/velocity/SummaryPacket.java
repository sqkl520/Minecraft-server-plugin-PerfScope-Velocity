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

    /** 与主仓 NetworkSummary.PROTOCOL_VERSION 必须一致。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 通道名，与主仓 NetworkSummary.CHANNEL 逐字一致。 */
    public static final String CHANNEL_NAMESPACE = "perfscope";
    public static final String CHANNEL_NAME = "summary";

    /** 计数型字段的"没采到"，与主仓 COUNT_UNAVAILABLE 一致。 */
    public static final int COUNT_UNAVAILABLE = -1;

    private final String serverId;
    private final long sentAtMs;
    private final long collectedAtMs;
    private final boolean collectorAlive;
    private final Double tps1m;
    private final Double tps5m;
    private final Double tps15m;
    private final Double msptAvg;
    private final int playerCount;

    private SummaryPacket(JsonObject o) {
        this.serverId = getString(o, "server", "unknown");
        this.sentAtMs = getLong(o, "sentAt", 0L);
        this.collectedAtMs = getLong(o, "collectedAt", 0L);
        this.collectorAlive = getBool(o, "alive", false);
        this.tps1m = getDoubleOrNull(o, "tps1m");
        this.tps5m = getDoubleOrNull(o, "tps5m");
        this.tps15m = getDoubleOrNull(o, "tps15m");
        this.msptAvg = getDoubleOrNull(o, "msptAvg");
        this.playerCount = (int) getLong(o, "players", COUNT_UNAVAILABLE);
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

    /** 快照年龄（毫秒）。负值按 0 处理——时钟不同步不该读成"未来的数据"。 */
    public long ageMs(long nowMs) {
        return collectedAtMs <= 0L ? Long.MAX_VALUE : Math.max(0L, nowMs - collectedAtMs);
    }

    // ---- 解析入口 ----

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
        return m;
    }
}
