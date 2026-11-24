# FAQ

English | [简体中文](#常见问题faq)

Answers to what people actually ask before installing PerfScope-Velocity. Every answer here was
checked against the source (`SummaryPacket` / `NetworkView` / `NetworkRenderer` / `NetworkCommand` /
`PerfScopeVelocity`), not written from memory — where something is a measurement it says so; where it
is a design decision it says that instead.

---

## "No data" is showing on every row. Is the plugin broken?

Almost certainly not — and the panel is designed to send you to the right place. `/pscope network`
prints a three-step checklist under the summary line whenever no backend is in OK:

1. **`network.enabled` is `true` in each backend's `config.yml`.** Off by default; a backend with it
   off never registers the sending side.
2. **Someone is online on that backend.** The summary rides a player connection through plugin
   messaging. An empty backend has no forwarding path at all — this is the single most common cause.
3. **The proxy actually has this plugin, and nothing is eating the channel.** Installing only on the
   backends registers a channel nobody consumes; a proxy plugin that intercepts and re-sends
   `perfscope:summary` will also break it.

An empty backend showing "no data" is **expected behaviour**. The panel refuses to render it as
"healthy" or as "0 TPS", because those three states mean completely different things to whoever reads
them.

If you have players online, `network.enabled: true`, and the plugin on the proxy, then check the
proxy console: drops are logged (rate-limited to one line a minute) with the count and **the peer's
declared protocol version** — that line tells you whether it is a version mismatch or something else.

## A backend I definitely run does not appear at all

The panel's row set is **the names registered under `[servers]` in `velocity.toml` ∪ the
`server-id`s actually received**. So a backend can be missing in exactly two ways:

- **It is not in `velocity.toml`'s `[servers]` section.** Then it only appears once a packet arrives,
  and an empty backend never sends one. Add it to `[servers]` (it has to be there anyway for players
  to be sent to it) and it will at least show as "no data", which is honest.
- **It is in `[servers]` but its `getServerInfo()` is broken** (a third-party plugin registered it
  with incomplete info). The panel deliberately **skips only that one** and lists the rest; it used to
  drop the whole table on a single bad entry, which produced a symptom indistinguishable from "plugin
  not installed".

Also worth knowing: the plugin tracks at most **256 distinct `server-id`s**, and beyond that the least
recently updated entry is evicted. Real networks are nowhere near this; it is a bound against
network-driven memory growth.

## Why do I see one row with data and one row saying "no data" for the same machine?

You have a **ghost row**: `server-id` on that backend does not match its name in `velocity.toml`.

```
velocity.toml:        survival-1 = "10.0.0.11:25565"
backend config.yml:   network.server-id: "survival"
```

The panel shows `survival-1` (registered, never reported → "no data") and `survival` (has data). This
is the easiest first-install misdiagnosis there is, and it is not a fault — it is the row set being
the union of two sources.

**Fix:** set `network.server-id` to the `velocity.toml` name, character for character (case counts),
then `/perfscope reload` on that backend. Or `/pscope network reset` on the proxy if you just want the
stale rows gone.

The proxy logs this too: one warning per (id, backend name) pair, naming both — so you never have to
guess which machine it was.

## How should `server-id` be configured?

```yaml
# config.yml on each Paper backend
network:
  enabled: true
  server-id: "survival-1"       # unique per backend, ideally identical to velocity.toml
  push-interval-seconds: 10
```

Rules, both of which are enforced identically on **both** sides:

| Rule | Value | What happens if you break it |
|---|---|---|
| Length | ≤ **64** characters (characters, not bytes; one CJK character is 1) | The backend refuses to send and names the actual count; the proxy drops the whole packet |
| Reserved word | not **`unknown`** (case sensitive — `Unknown` is fine) | Same |
| Characters | no control characters (C0 / C1 / DEL) and no `§` | The backend refuses to send, quoting the code point |
| Uniqueness | one `server-id` per backend | The second backend to use it is dropped as a squatter, **permanently** |

Two things worth understanding:

- **Uniqueness is what actually protects you.** The proxy pins each `server-id` to the first backend
  that used it (`ServerConnection.getServerInfo().getName()` — a backend cannot forge which TCP
  connection it is). A different backend claiming it later is dropped. That is the anti-overwrite
  guarantee: A cannot repaint B's numbers.
- **The backend refuses to send bad ids, so the failure is visible.** An over-long or
  control-character-laden id used to be silently dropped by the proxy, whose log only said
  "unparseable cross-server summary, check versions" — pointing you at a version mismatch that did
  not exist.

## Do I have to install it on both sides?

**Yes.** Both halves are required and neither does anything useful alone:

| Setup | Result |
|---|---|
| Only the backend plugin, `network.enabled: true` | A channel is registered and pushed to; nobody consumes it. No error anywhere, no visible effect |
| Only the proxy plugin | Every backend in `[servers]` shows "no data" |
| Both | The panel works |

The proxy side is a separate download from a separate repository:
[`sqkl520/Minecraft-server-plugin-PerfScope-Velocity`](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity).

## What does "stale" mean, and how long until it happens?

By default **3 × that backend's push interval** — 30 seconds at the default 10-second interval.

The interval used is the one the **backend** sends with each packet (`push-interval-seconds`). Raise
it to 60 seconds and the proxy judges staleness at 180 seconds; you do not have to change anything on
the proxy. Only when an older backend omits the field does the proxy fall back to its own
10-second assumption.

One interaction worth knowing, because it produces spurious "stale" rows: on the backend, the actual
push period is `ceil(push-interval-seconds / collection interval) × collection interval`, and the
proxy still judges by `3 × push-interval-seconds`. If you raise `general.update_interval` to, say, 1200
ticks (≈60 s) while `push-interval-seconds` stays at 10, the backend plugin automatically raises the
push interval to match the collection interval and warns in the console — it does not write that
change back to your file.

## The panel shows high cross-server latency / the age column climbs. Why?

The "data age" column is **how old this data is right now** = time since the last packet arrived +
the age that packet already had on arrival. So it always includes:

- the backend's collection-to-send delay,
- the network hop,
- the time since that backend last pushed.

With a 10-second push interval, a healthy row typically reads a few seconds, drifting up to ~10
before the next packet resets it. That upward drift between pushes is **normal** — a value that keeps
climbing *past* 3 × the interval is what "stale" means.

There is no cross-server request/response here: the proxy never asks the backend anything. Everything
is one-directional push, so there is no round-trip to measure. If you want per-backend detail, use
`/perfscope` on that backend itself.

## The clocks on my machines are not in sync. Will the panel lie to me?

Not any more, and it is worth knowing how: the proxy **learns** each backend's clock offset from the
`sentAt` field of every packet, and converts ages onto the backend's timeline.

- **Backend clock minutes fast** used to mean "permanently healthy" (age clamped to 0 forever, so a
  dead backend stayed green). Now a just-arrived packet reads OK and goes stale 30 seconds after it
  stops, like everyone else.
- **Backend clock minutes slow** used to mean "permanently stale". Now it reads OK while it keeps
  reporting.
- **Offset beyond ±10 minutes**: that sample is discarded, not learned. Beyond roughly that point the
  correction falls back to 0, so a fast backend shows `时钟异常` (clock skew) while a slow one usually
  reads "stale". Either way the age on such a machine is untrustworthy — fix NTP rather than have the
  panel guess.
- The age cell then says "ahead 1h (timestamp in the future)" or "behind 3d (timestamp in the past)",
  i.e. which way to adjust. It never says "0 seconds ago", because that is exactly the sentence a
  forged timestamp is trying to buy.

A forged future timestamp cannot win either: age is anchored at the moment the packet arrived, so a
forgery buys at most one "0 seconds" at arrival.

## What happens if the two sides have different versions?

Two different numbers, do not confuse them:

| | What it is | When it changes |
|---|---|---|
| **Artifact version** (`1.4.5-SNAPSHOT-fix4`) | Which build of the code this is | Every release |
| **Protocol version** (`SummaryPacket.PROTOCOL_VERSION`, currently `1`) | Whether the two sides can talk | Only when fields or semantics become incompatible |

The rule is **one-directional**: the receiver accepts packets whose protocol version is ≤ its own, and
drops anything higher. So:

- The two sides **do not need to be identical**. Same protocol version → any combination works.
- The proxy's protocol version **must never be lower** than the backend's. The moment the backend's
  number is higher, every one of its packets is dropped.
- A drop is **silent from the player's point of view**, and logged at most once a minute with the
  count and **the peer's declared version**, so you can tell which side to upgrade.

**Upgrade order: proxy first, then backends.** Within the same protocol version, order does not
matter. Every artifact pairing and its protocol version is listed in `CHANGELOG.md`.

Dropped packets are never "parsed as best we can" — that reads new fields as missing, which is the
exact class of silent corruption this project keeps fixing.

## Why does the panel show different colours than `/perfscope` on the backend?

Because the thresholds live in two different places and cannot share code. The proxy hard-codes the
backends' **shipped defaults** (TPS warn 18.0 / critical 15.0, MSPT warn 45.0 / critical 50.0) in
`NetworkRenderer`; it has no way to read each backend's `config.yml`.

The **comparison operator** is aligned, though: both sides use strict comparison, so "the colour
changed, therefore the alert has fired" holds on both panels. TPS exactly 15.00 is yellow on both;
exactly 18.00 is green on both. That alignment is deliberate and has been broken twice by accident in
the past, which is why there is now a cross-repo gate for it in the backend repository.

If you changed your backend's `tps.*` / `mspt.*` numbers, the two panels will disagree on colour —
**trust the backend panel** (it reads your real config). To remove the difference entirely you would
have to edit those four constants on the proxy and rebuild.

## Does it cost anything on the proxy?

It does no collection and runs no scheduled task — there is no tick loop on a proxy, and nothing to
collect.

Work happens in exactly two places: when a packet arrives (parse the JSON, strip unsafe characters,
update three in-memory maps) and when someone runs `/pscope network` (build the table and pad cells).
The summary itself is deliberately tiny — four metrics and a handful of fields, a few dozen bytes per
backend per push.

There is no per-tick cost, no world scan, no disk I/O and no outbound connection. The things that can
have an effect are all bounded: 256 tracked backends, 512 bindings, and rate-limited logging.

## Does it write anything to disk?

No. No config, no history file, no report, no cache. Everything is in memory and is gone on restart —
which is why `/pscope network reset` exists for "start counting from scratch" rather than deleting a
file.

The `@DataDirectory` Velocity injects is never read or written by this plugin.

## Can I see history or trends on the proxy?

No. The proxy keeps only **the latest packet per backend**, so there is no history to chart, no "what
was it an hour ago", no daily summary. It answers "how is the network right now", nothing else.

Per-backend history and trends exist on the backend plugin (`/perfscope history`, `/perfscope trend`,
and the Prometheus endpoint) — that is where time series belong.

## Players say they cannot use `/pscope network`. Why?

**Velocity gives no default value to a permission node**, and with no permission plugin installed its
permission function is `ALWAYS_UNDEFINED`, so `hasPermission("perfscope.network")` returns `false`.
Nobody can use it, including OPs — Velocity has no `op` concept.

Fix: install a permission plugin (LuckPerms and friends) and grant `perfscope.network` (or `*`).

**The confusing part:** without permission, Velocity **forwards** the command to the backend to
execute. So the player sees the **backend PerfScope help page**, not a "no permission" message. If a
command seems to do nothing or shows a help page you did not expect, check permissions first.

**Before granting it, check what the panel reveals**: every backend name (including ones that never
reported), each one's TPS / MSPT / player count, and the network-wide total. Backend names are
truncated to 20 display cells but not hidden.

**The console always works** — allowed by a type whitelist (`ConsoleCommandSource`), not by "anything
that is not a player", precisely so a plugin that wraps player identities in a custom command source
cannot bypass the permission.

## Installing this broke `/perfscope` on the backends

It did not break them; it **shadowed** them. Proxy commands take priority over backend commands, and
this plugin registers `perfscope` as an alias of `pscope`. A player typing `/perfscope tps` gets
caught by the proxy and receives only the usage line.

Workarounds:

- Use the **backend's own console** (the Paper server's terminal, or its RCON) — those commands never
  pass through Velocity, so nothing shadows them.
- Or write the **namespace**: `/perfscope:perfscope tps` (and `/perfscope:pscope tps`). Both work.
- `/perfscope:tps` does **not** work — `tps` is a subcommand, and Bukkit only registers
  `<plugin name>:<command name>`, never subcommands. It returns
  `Unknown or incomplete command`.

## Does plugin forwarding mode matter? Do I have to switch to legacy?

**No, and do not.** Both `modern` and `traditional` / `legacy` work with this channel.

Modern forwarding itself completes its handshake through the `velocity:player_info` plugin message,
and the backend→proxy plugin message path contains no forwarding-mode branch at all — the only gate is
whether the channel is registered. What genuinely does not work under `modern` is the legacy channel
dedicated to BungeeCord, which has nothing to do with this plugin. An earlier version of the README
claimed otherwise; that was wrong and has been corrected.

Downgrading to legacy to "fix plugin messaging" brings in a worse trust model than the problem it is
meant to solve.

## Does it clash with other proxy plugins?

Not by construction. Gson is shaded and relocated to `cn.perfscope.velocity.shaded.gson`, and the
Velocity API is a `provided` dependency that is never bundled — both of which are checked by
`tools/check-velocity-descriptor.mjs` and by CI.

The channel name `perfscope:summary` is namespaced by the plugin id, so no other plugin uses it by
accident. If some other plugin deliberately intercepts and re-sends that channel, the panel will show
nothing useful — that is the third item on the "no data" checklist.

## Can I turn rows off, change colours, or configure the panel?

No. There is no config file, so there is nothing to tune: the columns, the five states and the
thresholds are fixed. The only runtime control is `/pscope network reset`, which clears the received
data (and the source-binding table, so a backend you swapped out can bind again).

That is deliberate. A proxy panel that can be configured differently per network is a panel where two
admins can disagree about whether a server is healthy — and the whole point is that this one answer is
the same everywhere.

## What can it *not* tell me?

Four metrics only: **TPS(1m) / MSPT / player count / whether collection is alive**.

Entity counts, chunk counts, memory, CPU, hotspot chunks, reports, alerts, Prometheus — all of that
requires logging into the backend and running `/perfscope` there. The proxy has no worlds and no tick
loop, so the summary is deliberately tiny.

And one limit that no configuration can remove: **an empty backend never reports**. Not a bug — the
channel needs a player connection to ride. The panel says "no data" for it, which is the honest answer.

---

# 常见问题（FAQ）

[English](#faq) | 简体中文

这里回答的是"装之前真的会问"的问题。下面每条都与代码核对过（`SummaryPacket` / `NetworkView` /
`NetworkRenderer` / `NetworkCommand` / `PerfScopeVelocity`），不是凭记忆写的——凡是实测数字都会说明；
凡是设计取舍也会说明。

---

## 面板每一行都显示「无数据」，插件坏了？

几乎肯定没坏——面板本身就会把你引向正确方向。只要没有任何一台后端处于「正常」，
`/pscope network` 会在汇总行下面追加一段三步排查提示：

1. **各后端 `config.yml` 的 `network.enabled` 为 `true`。** 默认关闭；关着的那台从不注册发送端。
2. **那台后端有玩家在线。** 摘要走玩家连接的 plugin messaging，空服根本没有转发通道——这是
   最常见的一种原因。
3. **代理确实装了本插件，且没有别的东西吃掉这个通道。** 只装后端时通道没人消费；某个代理插件
   拦截并重发 `perfscope:summary` 也会让它失效。

空服后端显示「无数据」是**预期行为**。面板刻意不把它渲染成「正常」或「TPS 0」——这三件事对读它的人
意味着完全不同的东西。

如果确实有玩家在线、`network.enabled` 为 true、代理也装了插件，那就去看代理控制台：
丢弃会记日志（每分钟最多一条），带丢弃条数与**对方声明的协议版本**——那一行能告诉你是版本问题
还是别的。

## 我明明有一台后端，它却完全不出现

面板的行集合是 **`velocity.toml` 的 `[servers]` 里注册的名字 ∪ 实际收到过包的 `server-id`**。
所以一台后端消失，只有两种可能：

- **它不在 `velocity.toml` 的 `[servers]` 里。** 那它只能在收到包之后才出现，而空服从来不发包。
  把它加进 `[servers]`（玩家要被送到那台机器时本来也必须在那儿），至少会显示成「无数据」，
  这是诚实的。
- **它在 `[servers]` 里，但 `getServerInfo()` 有缺陷**（第三方插件注册信息不完整时会发生）。
  面板会**只跳过那一台**、其余照常列出；它以前会因为一条坏数据把整张表清空，症状与
  「插件没装」一模一样。

还有一条值得知道：最多跟踪 **256 个不同的 `server-id`**，超限时淘汰最久没更新的那条真实网络远
到不了这个数；它是对"网络输入撑爆内存"的兜底。

## 同一台机器为什么出现一行有数据、一行「无数据」？

这是**幽灵行**：那台后端的 `server-id` 与它在 `velocity.toml` 里的名字不一致。

```
velocity.toml:        survival-1 = "10.0.0.11:25565"
后端 config.yml:      network.server-id: "survival"
```

面板于是同时显示 `survival-1`（已注册、从未上报 → 无数据）与 `survival`（有数）。这是首装时最容易
误判成"插件坏了"的情形，而它不是故障——行集合本来就是两个来源的并集。

**修法：** 把 `network.server-id` 改成 `velocity.toml` 里的名字，逐字（大小写也算），
然后在那台后端执行 `/perfscope reload`。若只想让残留行消失，在代理上执行 `/pscope network reset`。

代理也会记这条：每一对 (id, 后端名) 一条告警，两个名字并排写出来，不需要你猜是哪台。

## `server-id` 到底怎么配？

```yaml
# 各 Paper 后端的 config.yml
network:
  enabled: true
  server-id: "survival-1"       # 每台不同，最好与 velocity.toml 逐字一致
  push-interval-seconds: 10
```

规则，**两端判据逐字一致**：

| 规则 | 值 | 破坏后果 |
|---|---|---|
| 长度 | ≤ **64** 个字符（按字符数，中文一字算 1） | 后端**拒发**并给出实际字符数；代理对越界包整包丢弃 |
| 保留字 | 不能是 **`unknown`**（大小写敏感，`Unknown` 合法） | 同上 |
| 字符 | 不能含控制符（C0 / C1 / DEL）与 `§` | 后端拒发，并点出是哪个码点 |
| 唯一性 | 每台后端一个 | 后一个用它的后端被判成抢占而**永久**丢包 |

两件值得理解的：

- **唯一性才是真正保护你的那条。** 代理把每个 `server-id` 钉在**第一个**用它的后端上
  （`ServerConnection.getServerInfo().getName()`，后端无法伪造"我这条 TCP 是哪条"），
  之后另一条后端来认领会被丢弃。这就是防覆盖：A 改不了 B 的数字。
- **非法的 id 由后端在发送前拦下，所以失败是可见的。** 早年超长/带控制符的 id 会被代理静默丢弃，
  而代理日志只说「无法解析的跨服摘要，请检查版本」——把你引向一个根本不存在的版本问题。

## 两边都要装吗？

**都要装。** 两半各自单独都没用：

| 装法 | 结果 |
|---|---|
| 只装后端插件并开 `network.enabled` | 通道注册了、也在往里发，但没人消费。不报错，也没有任何效果 |
| 只装代理插件 | `[servers]` 里的每台都显示「无数据」 |
| 两边都装 | 面板正常工作 |

代理侧是独立下载，在另一个仓库：
[`sqkl520/Minecraft-server-plugin-PerfScope-Velocity`](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity)。

## 「数据过期」是什么意思，多久算过期？

默认 **3 × 该后端的实际上报间隔**——默认 10 秒间隔下就是 30 秒。

用的间隔是**后端随包带来的** `push-interval-seconds`。调到 60 秒，代理就按 180 秒判过期，
代理侧什么都不用改。只有旧版后端不带这个字段时，代理才回退到自己的 10 秒假设。

有一个会产生"假的过期行"的联动，值得知道：后端实际上报周期是
`ceil(push-interval-seconds / 采集周期) × 采集周期`，而代理仍按 `3 × push-interval-seconds` 判。
如果你把 `general.update_interval` 调到 1200 tick（约 60 秒）而 `push-interval-seconds` 还是 10，
后端插件会自动把上报间隔上调到等于采集周期并在控制台告警——它**不会**把这个改动写回你的文件。

## 面板上的「数据年龄」一直在涨 / 跨服延迟很高？

「数据年龄」= **这块数据现在多旧** = 距最后一次收到包的时间 + 包里那份快照在到达时就已有的年龄。
所以它天然包含：

- 后端从采集到发送的延迟，
- 网络那一跳，
- 距那台后端上一次上报的时间。

10 秒上报间隔下，健康的一行通常读数是几秒，会一路涨到约 10 秒然后被下一个包重置。
**两次上报之间的这个上涨是正常的**——持续涨过 3 倍间隔才是「数据过期」。

这里没有跨服请求/响应：代理从不向任何后端提问，全是单向推送，所以没有往返可以测。要看单机细节，
就去那台后端上用 `/perfscope`。

## 我的机器时钟不同步，面板会骗我吗？

不会了，而机制值得知道：代理会从每个包的 `sentAt` 字段**学习**该后端的时钟偏移，
再把年龄换算到后端的时间轴上。

- **后端快几分钟**：以前意味着"永久正常"（年龄恒被 clamp 到 0，死掉的后端一直绿）。现在刚到的包
  显示正常，停发 30 秒后照常变「数据过期」。
- **后端慢几分钟**：以前意味着"永久过期"。现在只要它在正常上报，就显示「正常」。
- **偏移超过 ±10 分钟**：那条样本被丢弃、不参与学习。越过这个点之后校正值回到 0，于是**快**的机器
  显示「时钟异常」、**慢**的机器通常显示「数据过期」。两种情况下年龄都不可信——去修 NTP，
  比让面板替你猜有用。
- 年龄格这时写「早 1小时（时间戳在未来）」或「落后 3天（时间戳在过去）」，也就是该往哪边调。
  它**绝不**写「0 秒前」，因为那正是伪造时间戳想换来的那句话。

伪造的未来时间戳也赢不了：年龄锚定在包到达的那一刻，伪造最多换来刚到时的一次「0 秒前」。

## 两边版本不一致会怎样？

两个数字不要混：

| | 是什么 | 什么时候变 |
|---|---|---|
| **artifact 版本**（`1.4.5-SNAPSHOT-fix4`） | 这是哪一版代码 | 每次发布 |
| **协议版本**（`SummaryPacket.PROTOCOL_VERSION`，当前 `1`） | 两端能不能通信 | 只在字段/语义不兼容时递增 |

规则是**单向的**：接收方接受协议版本 ≤ 本端的包，高于本端的整包丢弃。于是：

- 两侧**不必完全相同**。同协议版本下任意组合都能用。
- 代理侧协议版本**绝不能低于**后端。后端一旦更高，它的包会**全被丢弃**。
- 丢弃从玩家视角**完全静默**，只在日志里每分钟最多一条，带丢弃条数与**对方声明的版本**，
  便于判断该升哪一侧。

**升级顺序：先代理、后后端。** 同一协议版本内顺序无所谓。每一对 artifact 与协议版本的对应关系
写在 `CHANGELOG.md`。

被丢的包**不会**"尽力解析"——那会把新字段读成缺失，正是本项目反复修的那类静默失真。

## 为什么面板颜色与后端 `/perfscope` 不一致？

因为阈值在两处，物理上不可能共享代码。代理把后端**随包默认值**硬编码在 `NetworkRenderer`
（TPS 警告 18.0 / 严重 15.0，MSPT 警告 45.0 / 严重 50.0），它读不到各后端的 `config.yml`。

但**比较符是对齐的**：两边都用严格比较，所以「颜色变了就等于告警已触发」在两个面板上都成立。
恰好 15.00 的 TPS 两边都是黄，恰好 18.00 两边都是绿。这个对齐是刻意的，历史上曾两次被无意改坏，
所以后端仓库里现在有一条跨仓库门禁钉住它。

如果你改过后端的 `tps.*` / `mspt.*` 数值，两个面板的着色就会不一致——**以后端面板为准**
（它读的是你真实的配置）。要彻底消除差异只能改代理侧那四个常量并重新构建。

## 它在代理上有开销吗？

不做采集，也没有任何定时任务——代理上没有 tick 循环，也没有东西可采。

工作只发生在两处：包到达时（解析 JSON、剔除不安全字符、更新三张内存表），以及有人执行
`/pscope network` 时（拼表格、按宽度补齐）。摘要本身刻意做得很小——四个指标加几个字段，
每台每轮几十字节。

没有逐 tick 开销、没有世界扫描、没有磁盘 I/O、没有出站连接。可能有影响的东西全部有界：
256 个跟踪后端、512 条绑定，以及限频日志。

## 它会往磁盘写东西吗？

不会。没有配置文件、没有历史文件、没有报告、没有缓存。全部在内存里，重启即清空——这也是
「从头开始统计」要用 `/pscope network reset` 而不是删文件的原因。

Velocity 注入的 `@DataDirectory` 本插件既不读也不写。

## 代理上能看到历史或趋势吗？

不能。代理只保留**每台后端的最近一包**，没有历史可画、没有"一小时前是多少"、没有每日摘要。
它回答的是"全网现在怎么样"，仅此而已。

单机的历史与趋势在后端插件上（`/perfscope history`、`/perfscope trend`、以及 Prometheus 端点）
——时序数据本来就该在那里。

## 玩家说 `/pscope network` 用不了，为什么？

**Velocity 不给权限节点设默认值**，而未装权限插件时它的权限函数是 `ALWAYS_UNDEFINED`，
所以 `hasPermission("perfscope.network")` 返回 `false`。谁都看不到，包括 OP——Velocity 没有 `op` 概念。

修法：装一个权限插件（LuckPerms 等）并授予 `perfscope.network`（或 `*`）。

**容易误判的地方：** 没权限时 Velocity 会把命令**转发到后端**执行，所以玩家看到的是
**后端 PerfScope 的帮助页**，不是「无权限」提示。某条命令看着没反应、或出现了你没预期的帮助页时，
先查权限。

**授权前请确认面板会展示什么**：所有后端名（含从未上报的）、各自的 TPS / MSPT / 在线人数、
以及全网合计。后端名会按显示宽度截到 20 格，但不会被隐藏。

**控制台始终可用**——按类型白名单（`ConsoleCommandSource`）放行，而不是"任何非玩家都放行"，
正是为了防止某个把玩家身份包装成自定义 source 的插件绕过权限。

## 装了它之后，后端的 `/perfscope` 不能用了

不是坏了，是被**盖住**了。代理命令优先于后端命令，而本插件注册了 `perfscope` 作为 `pscope` 的别名。
玩家敲 `/perfscope tps` 会被代理接住，只回一句用法提示。

绕法：

- 用**控制台**。
- 或写**命名空间**：`/perfscope:perfscope tps`（以及 `/perfscope:pscope tps`），两者都能执行。
- `/perfscope:tps` **不行**——`tps` 是子命令，Bukkit 只注册 `<插件名>:<命令名>`，从不注册子命令。
  它会返回 `Unknown or incomplete command`。

## 转发模式有影响吗？要改成 legacy 吗？

**没有，也不要改。** `modern` 与 `traditional` / `legacy` 都能用。

modern 转发本身就是靠 `velocity:player_info` 这条 plugin message 完成握手的；后端→代理的
plugin message 路径里没有任何转发模式分支，唯一的门控是"这个通道有没有被注册"。
真正在 modern 下不可用的是 BungeeCord 那条 legacy 专用通道，与本插件无关。README 早年写过相反的话，
那是错的，已经改掉了。

为了"修 plugin messaging"把代理降级成 legacy，会引入比它要解决的问题更糟的信任模型。

## 会和其它代理插件冲突吗？

按构造不会。Gson 被 shade 并 relocate 到 `cn.perfscope.velocity.shaded.gson`，
Velocity API 是 `provided`、绝不打进 jar——这两条都由 `tools/check-velocity-descriptor.mjs` 与 CI 检查。

通道名 `perfscope:summary` 按插件 id 命名空间化，别的插件不会碰巧用到。如果某个插件**故意**拦截并
重发这条通道，面板会什么都显示不出来——那就是「无数据」排查清单里的第三条。

## 能关掉某些行、改颜色、或配置面板吗？

不能。没有配置文件，所以没有可调项：列、五种状态、阈值都是固定的。唯一的运行时控制是
`/pscope network reset`，它清空已收到的数据（以及来源绑定表，让你换过的后端能重新绑定）。

这是有意的。一个"每个网络都能配得不一样"的代理面板，意味着两个管理员可能对同一台机器是否健康
得出不同结论——而这里的全部价值就在于这个答案处处相同。

## 它**看不到**什么？

只有四个指标：**TPS(1m) / MSPT / 在线人数 / 采集是否还活着**。

实体数、区块数、内存、CPU、热点区块、报告、告警、Prometheus——这些都要登到那台后端上跑
`/perfscope`。代理没有世界也没有 tick 循环，摘要因此刻意做得很小。

还有一条任何配置都去不掉的边界：**空服的后端永远不会上报**。这不是 bug——通道需要一条玩家连接
来承载。面板对它显示「无数据」，这是诚实的答案。
