# PerfScope-Velocity

PerfScope 的 **Velocity 代理侧**：接收各后端（Paper）上报的性能摘要，用 `/pscope network` 一屏看全网。

> 这个目录暂时放在主仓 `PerfScope/` 的旁边，**还不是一个独立的 git 仓库**。
> 它有自己的 `pom.xml`、自己的测试、自己的构建产物，可以独立 `mvn package`。

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

## 四种状态

面板上每一行必定是这四种之一，且它们的含义完全不同：

| 状态 | 含义 | 该去查什么 |
|---|---|---|
| `正常` | 有新鲜数据，采集器活着 | 无需处理 |
| `数据过期` | 收到过，但超过 3 倍上报间隔没更新 | 可能刚空服，也可能后端没响应 |
| `采集已停` | 包还在到，但后端自报采集器已死 | **后端**的采集出了问题，不是网络 |
| `无数据` | 从来没收到过这个后端的包 | 后端没配 `network.enabled` / 代理不是传统转发模式 / 后端一直空服 |

`采集已停` 这一行**整行都不显示数字**，哪怕包里带着 TPS——采集器死了以后那些数字是冻结的，
显示它们等于把历史当现状。这是本插件最核心的一条展示纪律。

## 后端侧要配什么

在各 Paper 后端的 `config.yml`：

```yaml
network:
  enabled: true
  server-id: "survival"       # 每个后端必须不同，否则代理侧会把它们混成一条
  push-interval-seconds: 10
```

还要保证代理的转发模式是 `traditional` / `legacy`。**Velocity 的 `modern` 转发模式下
plugin messaging 不可用**，这是「配了但代理收不到」最常见的原因。

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
