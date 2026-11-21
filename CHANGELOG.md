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
