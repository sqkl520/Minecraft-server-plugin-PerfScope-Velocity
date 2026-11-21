# PerfScope-Velocity

PerfScope 的 **Velocity 代理侧**：接收各后端（Paper）上报的性能摘要，用 `/pscope network` 一屏看全网。

> **独立仓库**：<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity>
>
> 后端（Paper）插件在 <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope>。
> 两个仓库的 artifact 版本保持同步。

## 它做什么

```
Paper 后端 × N                        Velocity 代理
┌──────────────┐                     ┌──────────────────┐
│ PerfScope    │  plugin messaging   │ PerfScope-       │
│ + network    │ ──────────────────► │ Velocity         │
│   .enabled   │  perfscope:summary  │ /pscope network  │
└──────────────┘                     └──────────────────┘
```

后端只发一份**极小的摘要**（TPS / MSPT / 玩家数 / 采集是否还活着），代理侧负责聚合与显示。

## 一条必须知道的限制

**plugin messaging 必须搭玩家的连接转发，所以空服的后端不会上报。**

这不是实现缺陷，是这条链路本身的固有限制。它直接决定了面板该长什么样：空服的后端必须显示成
「无数据」，**不能**显示成「正常」或「TPS 0」。把这三件事混成一个「-」，是仪表盘最常见的谎言——
服主看到「-」会以为没问题，看到「0」会以为服务器挂了，而真相往往只是没人玩所以没有转发通道。

## 权限

命令 `/pscope network`（别名 `/perfscope network`、`/ps network`），权限节点 `perfscope.network`。

> ⚠️ **别名会盖住后端的同名命令。** Velocity 上代理命令优先于后端命令，而本插件注册了
> `perfscope` 作为别名，所以玩家在游戏里敲 `/perfscope tps` 会被这里接住、只回一句
> 「用法：/pscope network」，后端 22 个子命令全部不可达。要用后端命令请走控制台，
> 或写成带命名空间的 `/perfscope:tps`。

**默认谁都看不到，包括玩家。** Velocity 没有 `plugin.yml` 那种权限声明，插件无法给节点设默认值；
没装权限插件时 Velocity 的 `permissionFunction` 是 `ALWAYS_UNDEFINED`，`hasPermission` 返回 `false`。
这是保守的正确默认，但你需要知道它——**装完发现玩家用不了，通常就是这个原因**。

授权步骤：装一个权限插件（LuckPerms 等）→ 给玩家或组加 `perfscope.network`。

**失败形态容易误判**：没权限时 Velocity 会把命令**转发到后端**执行，玩家看到的是
**后端 PerfScope 的帮助页**，不是"你没有权限"。看到帮助页而命令没反应时，先查权限。

**授权前请确认面板会展示什么**：所有后端名（含从未上报的）、各自 TPS / MSPT / 在线人数、
全网在线合计。如果后端名里带了内网主机名之类信息，它会原样出现在这个玩家的聊天窗口里。

**控制台始终可用**（按类型白名单放行，不是"任何非玩家都放行"）。

## 四种状态

面板上每一行必定是这四种之一，且它们的含义完全不同：

| 状态 | 含义 | 该去查什么 |
|---|---|---|
| `正常` | 有新鲜数据，采集器活着 | 无需处理 |
| `数据过期` | 收到过，但超过 3 × 该后端上报间隔没更新 | 可能刚空服，也可能后端没响应 |
| `采集已停` | 包还在到，但后端自报采集器已死 | **后端**的采集出了问题，不是网络 |
| `无数据` | 从来没收到过这个后端的包 | 后端没配 `network.enabled` / 后端一直空服 / 代理没装本插件 |

**首装失败时面板会自己给排查线索。** 只要没有任何一台后端处于「正常」，命令会在汇总行下面
追加一段三步排查提示（`network.enabled`、是否有玩家在线、代理是否已装本插件）。空服后端显示
「无数据」是**预期行为**，不是故障——这也是这段提示要说明的第一件事。

`采集已停` 这一行**整行都不显示数字**，哪怕包里带着 TPS——采集器死了以后那些数字是冻结的，
显示它们等于把历史当现状。这是本插件最核心的一条展示纪律。

### 阈值口径

TPS / MSPT 的分级阈值**硬编码在代理侧**（`NetworkRenderer`），取的是后端 `config.yml` 的
**随包默认值**：TPS 警告 18.0 / 严重 15.0，MSPT 警告 45.0 / 严重 50.0，且与后端一样用
**闭区间**（恰好等于阈值就算越界）。

代理侧读不到各后端的配置，所以如果你改了后端的 `tps.*` / `mspt.*` 阈值，两个面板的着色会不一致
——改了后端阈值就请同步改 `NetworkRenderer` 里的四个常量，或接受这个差异。

### 过期判定跟随后端的上报间隔

包里有 `pushIntervalSec` 字段（后端 `network.push-interval-seconds` 原值）。代理按
`3 × 该值` 判"数据过期"，所以服主把间隔调大到 60 秒也没问题；只有旧版后端发的包不带这个字段时，
才回退到代理自己的 10 秒假设（3 × 10 = 30 秒）。

## 后端侧要配什么

在各 Paper 后端的 `config.yml`：

```yaml
network:
  enabled: true
  server-id: "survival"       # 每个后端必须不同，否则代理侧会把它们混成一条
  push-interval-seconds: 10
```

**`server-id` 必须与 `velocity.toml` 里该后端的名字逐字一致。** 代理面板的行集合是
「`[servers]` 里注册的名字」∪「收到过包的 `server-id`」，两者对不上就会出现**幽灵行**：

```yaml
# velocity.toml
[servers]
survival-1 = "10.0.0.11:25565"
skyblock-1 = "10.0.0.12:25565"
lobby      = "10.0.0.13:25565"
```

```yaml
# survival-1 那台的 config.yml
network:
  enabled: true
  server-id: "survival-1"     # 必须与上面的名字逐字一致（大小写也算）
```

若填成 `survival`，面板上会同时出现 `survival-1`（无数据）与 `survival`（有数）两行。

还要保证各后端装了 PerfScope 并在它们的 `config.yml` 里开了 `network.enabled`。

**关于代理的转发模式**：`modern` 与 `traditional` / `legacy` **都能用**。
此前本文档写过"modern 下 plugin messaging 不可用"，那是**错的**——modern 转发本身就是靠
`velocity:player_info` 这条 plugin message 完成握手的；后端→代理的 plugin message 路径
（`BackendPlaySessionHandler`）里没有任何转发模式分支，唯一的门控是"这个通道有没有被注册"。
真正在 modern 下不可用的是 BungeeCord 那条 legacy 专用通道，与本插件无关。
**不要为了这个问题把代理降级成 legacy**——那会引入比它更糟的信任模型。

### 它看不到什么

面板只有四个指标：**TPS(1m) / MSPT / 在线玩家数 / 采集是否活着**。实体数、区块数、内存、
CPU、热点区块这些仍然要登到那台后端上用 `/perfscope` 看——代理侧没有 tick 循环，也没有世界，
本来就没有可采的东西，摘要因此刻意做得很小（每台每轮几十字节）。

## 两个仓库的关系

| 仓库 | 装在哪 | 作用 |
|---|---|---|
| [Minecraft-server-plugin-PerfScope](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope) | 每个 Paper 后端 | 采集指标 + 发送摘要 |
| 本仓库 | Velocity 代理 | 接收摘要 + 渲染 `/pscope network` |

**版本必须共存**：`SummaryPacket.PROTOCOL_VERSION` 决定能不能通信，两侧必须一致。
artifact 版本（`1.4.5-SNAPSHOT-fix3` 这种）只决定"这是哪一版代码"，两者不要混。
协议版本不一致时，高版本发的包会被低版本**直接丢弃并记日志**（每分钟最多一条，附带丢弃条数
与**对方声明的版本**，便于判断该升哪一侧），而不是"尽力解析"——
后者会把新字段读成缺失，正是本项目反复修的那类静默失真。

**升级顺序**：先升代理侧，再升后端。解析规则是"高于本端版本就拒、低于就容忍"，所以代理侧
版本 ≥ 后端版本时最平稳；反过来（后端先升到更高协议）会有一段时间全被丢弃。同一协议版本内
前后端可以任意顺序升级。

## 构建

```bash
mvn clean package
node tools/check-velocity-descriptor.mjs   # 校验 jar 里的描述符
```

产物：`target/PerfScope-Velocity-<version>.jar`，丢进 Velocity 的 `plugins/` 目录。

## 那个 `resources-filtered` 目录是干什么的

Velocity 的注解处理器在**编译期**生成 `velocity-plugin.json`，它把 `@Plugin` 注解里的 version
字符串原样写进去，**不经过 Maven 的 resource filtering**。所以第一次打包出来的描述符里是字面量
`"version": "${project.version}"`。

这不是无害的：Velocity 会把它作为插件版本显示在 `/velocity plugins` 里，排错时核对版本也读它。
更糟的是它不报错——插件照常加载，只是版本那一栏永远是个占位符。

`src/main/resources-filtered/velocity-plugin.json` 是一份带 `${project.version}` 的模板，
由 `maven-resources-plugin` 在 `process-classes` 阶段（晚于 compile、早于 package）覆盖处理器
写出的那一份。`tools/check-velocity-descriptor.mjs` 负责防止它再次退化。

## 测试

18 个用例，全部打在纯逻辑层（`SummaryPacket` / `NetworkView` / `NetworkRenderer`）——
这三个类刻意不 import `com.velocitypowered.*`，因为 Velocity 的 API 在单测里没法实例化，
而判定与渲染才是真正会出错的部分。

变异验证过四条（都是真改生产代码再跑）：缺失字段补成 0、版本闸门失效、聚合把非 OK 状态算进去、
采集已死仍显示数字——四条全部 KILLED。
