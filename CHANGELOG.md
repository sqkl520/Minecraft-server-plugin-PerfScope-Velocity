# Changelog

PerfScope 的 Velocity 代理侧：接收各 Paper 后端上报的性能摘要，`/pscope network` 一屏看全网。

本文件记录**代理侧**的变更。后端插件（Paper 侧）的变更在另一个仓库：
<https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/blob/main/CHANGELOG.md>

## 版本与协议版本的对应

两个概念不要混：

| | 是什么 | 什么时候变 |
|---|---|---|
| **artifact 版本**（`1.4.5-SNAPSHOT-fix2` 这种） | 这是哪一版代码 | 每次发布 |
| **协议版本**（`SummaryPacket.PROTOCOL_VERSION`） | 两端能不能通信 | 只在字段/语义不兼容时递增 |

协议版本不一致时，高版本发的包会被低版本**直接丢弃并记日志**，而不是"尽力解析"。
两个仓库的 artifact 版本保持同步。

| artifact 版本 | 协议版本 | 备注 |
|---|---|---|
| `1.4.5-SNAPSHOT-fix2` | 1 | 首个发布 |
| `1.4.5-SNAPSHOT-fix3` | 1 | 阈值口径、过期判定、面板对齐；协议兼容 |
| `1.4.5-SNAPSHOT-fix4` | 1 | 阈值闭合性从闭区间改回严格（跟随后端算子表）；协议兼容 |

---

## [1.4.5-SNAPSHOT-fix4] — 2026-11-22 — 阈值闭合性跟随后端算子表（严格比较）

### Fixed — 恰好等于阈值时两个面板颜色不同

fix3 把 `tpsCell` / `msptCell` 改成闭区间（`<=` / `>=`），理由是"后端 `ThresholdColors`
用闭区间"。**那个理由在当时成立，但那份后端闭区间本身是错的**：它与后端采集层
`ThresholdOperator`（严格比较）分裂，同一个 `tps = 15.00` 在后端界面上是红、在后端告警里只是黄，
差一整档。后端 fix4 把展示层改成从算子表派生（`ThresholdColors` 不再自己写比较符），
两处同时变成严格；于是代理侧这份闭区间**重新变成那个"两边着色相反"的源头**——
恰恰是 fix3 想修的东西，方向反过来了。

改回严格比较：`tps < 15.0` 才 CRITICAL、`tps < 18.0` 才 WARN、`mspt > 50.0` 才 CRITICAL、
`mspt > 45.0` 才 WARN。于是 15.00 / 18.00 / 45.0 / 50.0 四个点上，代理与后端给出一致结论。

**选代理改而不是改后端算子表**：算子表是告警行为源，把 `entities` 改成闭区间会让「恰好 30000 个实体」
的一批服务器突然开始报 critical（`config.yml` 明写 20000/30000 是"上界留余量"取的整数），
那是远超阈值口径修复范围的生产行为变更；代理是只读聚合面板、阈值硬编码、改动面小。

### Tests

`tpsTierBoundariesAreClosedLikeBackend` → `tpsTierBoundariesAreStrictLikeBackend`，
`msptCellHandlesMissingAndTiers` 的边界期望同步（45.0 由 WARN 改 OK、50.0 由 CRITICAL 改 WARN）。
36 个用例全过。

### Changed

- README「阈值口径」：闭区间 → 严格比较，并写明判据是"与后端对同一读数给同一结论"，
  而不是"跟某个类一致"（后者正是 fix3 踩的坑：它跟的是一个后来被判为缺陷的实现）。

> **留一条教训**：这次修的是"对齐了一个错误的参照物"。fix3 的对齐动作本身是对的，
> 但当时把 `ThresholdColors` 的闭区间当成了规格，而它其实是要被修掉的那一半。
> 现在两侧唯一可依赖的参照物是后端 `ThresholdOperator` 那张表。

---

## [1.4.5-SNAPSHOT-fix3] — 2026-11-22 — 复审修复：阈值口径与面板可用性

### Fixed

- **TPS / MSPT 阈值与后端不同口径**：此处是 `tps < 15`（开区间）、MSPT 用 35/45，而后端
  `ThresholdColors` 是闭区间、默认 45/50。于是 TPS 恰好 15.00 / 18.00 两边着色相反，
  MSPT 在 35–50 之间整体错一档。改为闭区间 + 45/50。代理侧读不到后端配置，对齐的是**随包默认值**。
- **过期判定写死 10 秒**：后端 `push-interval-seconds` 允许 1–300，这里却固定 `3 × 10 = 30s`，
  服主调大间隔后每两轮之间都闪「数据过期」。改为读包里的 `pushIntervalSec`，按每台后端
  自己的节奏判过期；字段缺失/越界时回退默认。**协议版本保持 1**（新增可选字段是双向兼容的，
  升号会让旧代理整包拒收）。
- **首装失败的排查提示从未显示**：原来挂在 `nodes.isEmpty()`，而真实代理上 `velocity.toml`
  配了后端就必定非空。改为挂在「一台都不正常」上。
- **面板列不对齐**：六列靠两个空格分隔、无填充，中文状态与数字宽度不同导致每行第 3 列起
  错位。新增按显示宽度（ASCII 1 / 其余 2）填充。
- **丢包日志报的"对方版本"其实是本端版本**：新增 `SummaryPacket.peekVersion()`，
  日志改为「对方 N / 本端 M」，便于判断该升哪一侧。

### Changed

- README：写明 `server-id` 必须等于 `velocity.toml` 的后端名（附幽灵行示例）、
  代理 `/pscope` 会盖住后端子命令、阈值口径、间隔随包走、升级顺序（先升代理侧）、
  「它看不到什么」（只有四个指标）。

### Tests

24 → 36 个用例：状态行 `Level` 矩阵、`msptCell` 全套、阈值边界、`summaryLine` 整串断言、
聚合两个边角（缺 `tps1m` 的 NPE 路径、`players=-1`）、去掉 NaN 测试的逃生门、
`tps5m`/`tps15m` 独立取值、列宽不变式、新旧包兼容。

变异验证（真改生产代码）：TPS 开区间、MSPT 开区间、COLLECTOR_DEAD 分支失效、
忽略包内间隔——四条全部 KILLED。

---

## [1.4.5-SNAPSHOT-fix2] — 2026-11-21 — 首个发布

### Added

- `/pscope network`：一屏看全网。四种状态分开显示（正常 / 数据过期 / 采集已停 / 无数据），
  采集器已死的后端整行不显示数字。
- `/pscope network reset`：清空已收到的数据，等待后端重新上报。

### Fixed

（无——首个发布）

### Known limitations

- **空服的后端不上报。** plugin messaging 借玩家连接转发，没有玩家就没有通道。
- **只有四个指标**：TPS、MSPT、在线人数、数据新鲜程度。区块/实体/内存/CPU 不在摘要里。
- **信任边界是"后端"。** 网络里任意一台后端都能伪造任意 serverId 的读数。详见下方"安全"。

### Security

- 玩家无法伪造读数：`instanceof ServerConnection` 过滤，玩家消息被准确拦下（有反编译证据）。
- `serverId` 长度上限 64 字符、跟踪条目上限 256 条、`players` 范围校验——
  防的是"一台恶意后端把代理堆填满 / 把面板撑爆"。
- 丢弃日志限频为每分钟一条，防日志刷屏。

### Upgrade notes

- 新装：把 jar 放进 Velocity 的 `plugins/` 目录，重启代理。
- 各后端也要装后端插件并开 `network.enabled`，两边都装才有数据。
- 玩家需要 `perfscope.network` 权限，**默认谁都没有**，详见 README 的"权限"一节。

---

## 验收记录（2026-11-21）

首个发布后做了一轮六维度验收（运行时可行性 / 协议对称性 / 红队 / 测试有效性 / 文档一致性 /
可用性）。运行时可行性那条把插件真的装进一台 Velocity 3.5.0 跑通了。

发现的问题与修复状态记在 `.audit-findings.md`（本地文件，不入库）。
