[简体中文](README.md) | English

<div align="center">

# PerfScope-Velocity

**The Velocity proxy side of PerfScope — a whole network on one screen**
Receives the performance summaries each backend (Paper) pushes, and shows every backend's
TPS / MSPT / player count through `/pscope network`

![Version](https://img.shields.io/badge/version-1.4.5--SNAPSHOT--fix4-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Velocity%203.x-blue?style=flat-square)
![Java](https://img.shields.io/badge/java-17-success?style=flat-square)
![Tests](https://img.shields.io/badge/unit%20tests-131%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-AGPL--3.0-green?style=flat-square)

[![Build](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/actions/workflows/build.yml/badge.svg)](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/actions/workflows/build.yml)

**Zero outbound connections** · **Read-only** · **Writes no files** — drop it in `plugins/` and it works

**[⬇️ Download the latest build](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/releases)** ·
[Report an issue](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/issues)

</div>

> **A separate repository**: <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity>
>
> The backend (Paper) plugin lives at
> <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope>.
> The two repositories keep their artifact versions in sync.

> **The [Releases page](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/releases) is
> the source of truth for versions.** The badge above reads its number from `pom.xml`; whenever it
> disagrees with the Releases page, **the Releases page wins**. Every release is flagged Pre-release,
> so `/releases/latest` will never point at them — take the topmost entry on the Releases list
> instead, and the higher the `fixN` suffix the newer the build. The test count on the badge is a
> measurement from the last `mvn test`, and it moves with each version.

## What it does

```
Paper backends × N                   Velocity proxy
┌──────────────┐                     ┌──────────────────┐
│ PerfScope    │  plugin messaging   │ PerfScope-       │
│ + network    │ ──────────────────► │ Velocity         │
│   .enabled   │  perfscope:summary  │ /pscope network  │
└──────────────┘                     └──────────────────┘
```

Each backend only pushes a **tiny summary** (TPS / MSPT / player count / whether collection is still
alive); the proxy aggregates and displays it. This plugin collects nothing itself — a proxy has no
tick loop, no worlds and no entities, so there is nothing to collect.

## One limitation you must know

**Plugin messaging has to ride a player connection, so an empty backend never reports.**

That is not an implementation defect; it is inherent to the channel. It directly determines what the
panel must look like: an empty backend has to show **"no data"**, and must **not** show "healthy" or
"TPS 0". Collapsing those three into one "-" is the most common lie in dashboards — a server owner
reads "-" as "fine", "0" as "the server died", when the truth was usually just nobody playing so
there is no forwarding path.

## Permissions

Command `/pscope network` (aliases `/perfscope network`, `/ps network`), permission node
`perfscope.network`.

> ⚠️ **The alias shadows the backend command of the same name.** On Velocity the proxy command wins
> over the backend one, and this plugin registers `perfscope` as an alias — so a player typing
> `/perfscope tps` in game is caught here and only gets
> "usage: /pscope network", leaving all 24 backend subcommands unreachable. To use backend commands,
> go through the **console**, or write the plugin namespace explicitly:
> `/perfscope:perfscope tps`.

> **Why not `/perfscope:tps`** (verified on a real Paper 1.21.4): Bukkit keys `knownCommands` as
> `<plugin name>:<command name>` — the plugin name comes from `plugin.yml`'s `name` (`PerfScope`),
> the command name from `Command.getName()` (`perfscope` / `pscope` / `perf`). `tps` is a
> **subcommand** and never enters the command table, so `/perfscope:tps` returns
> `Unknown or incomplete command`, while `/perfscope:perfscope tps` and `/perfscope:pscope tps`
> both reach the backend subcommand.

**By default nobody can see it, players included.** Velocity has no `plugin.yml`-style permission
declarations, so a plugin cannot give a node a default value; with no permission plugin installed,
Velocity's `permissionFunction` is `ALWAYS_UNDEFINED` and `hasPermission` returns `false`. That is
the conservative correct default, but you need to know it — **"installed it and players can't use
it" is usually exactly this**.

To grant access: install a permission plugin (LuckPerms and friends) → give the player or group
`perfscope.network`.

**The failure mode is easy to misdiagnose**: without permission Velocity forwards the command to the
backend to execute, so the player sees the **backend PerfScope help page**, not "you lack
permission". If you see a help page and nothing happens, check permissions first.

**Before granting it, be clear about what the panel reveals**: every backend name (including ones
that never reported), each one's TPS / MSPT / player count, and the network-wide player total. If a
backend name contains something like an internal hostname, it appears verbatim in that player's chat
window (backend names are truncated to 20 display cells with a trailing `…`, but they are **not
hidden**).

**The console always works** (allowed by type whitelist, not by "anything that is not a player").

## The five states

Every row on the panel is exactly one of these five, and they mean completely different things.
The state names below are the **literal strings the panel prints**; the proxy side currently has no
i18n layer, so the in-game output is Chinese-only regardless of this document's language.

| State | Meaning | What to check |
|---|---|---|
| `正常` (OK) | Fresh data, collector alive | Nothing |
| `数据过期` (stale) | Was received, but nothing for more than 3 × that backend's push interval | Maybe it just went empty, maybe the backend is not responding |
| `采集已停` (collector dead) | Packets still arrive, but the backend reports its own collector is dead | The **backend's** collection, not the network |
| `时钟异常` (clock skew) | The packet's timestamp is untrustworthy (in the future, or absurdly old) | The **system clock** of the backend or the proxy — see "Clock skew" below |
| `无数据` (no data) | Nothing was ever received from this backend | Backend has no `network.enabled` / the backend has always been empty / the proxy does not have this plugin |

**On a failed first install the panel hands you the troubleshooting steps itself.** As long as no
backend is in OK, the command appends a three-step checklist under the summary line
(`network.enabled`, whether any player is online, whether the proxy actually has this plugin). An
empty backend showing "no data" is **expected behaviour**, not a fault — which is the first thing
that checklist says.

A `采集已停` row **shows no numbers at all**, even if the packet carries TPS — once the collector is
dead those numbers are frozen, and showing them means presenting history as the present. This is the
core display discipline of the plugin.

`数据过期` and `时钟异常` rows **keep their numbers but demote all of them to grey** (`UNKNOWN`): the
text still carries information ("it was 19.5 when it stopped" tells you where it stopped), but green
would be read as "this one is healthy", and neither row's conclusion supports that. All six cells of
those rows lose the healthy colour.

The age cell of a `时钟异常` row reads something like "ahead 1h (timestamp in the future)" or
"behind 3d (timestamp in the past)" — deliberately phrased as a direction, and it **never** says
"0 seconds ago". When the timestamp is untrustworthy, the conclusion "this data was just collected"
is itself untrustworthy; writing 0 seconds would take a forgery as fact.

### How to read the data age column

The "data age" column is **how old this piece of data is right now** = time since the last packet
arrived + the age that packet's snapshot already had when it arrived. Both parts are necessary:

- The packet's own age alone will not do — it is anchored at the moment the packet arrived (that is
  the anti-forgery design) and **does not grow with time**. A backend that stopped an hour ago still
  sits at the 0.3 seconds it had on arrival, which displayed directly reads "0 seconds ago".
- Time since the last arrival alone will not do either — a packet whose timestamp is ten years old
  but which just arrived has a near-zero time-since-arrival, and would be judged "OK".

So the panel shows the sum, which is correct in all three cases: during normal reporting it advances
with the push cadence; when a backend stops it grows with the outage; and when the collector is dead
while packets still arrive it grows as well.

### Clock skew (new in v1.4.5-fix5)

The backend and the proxy are two machines, and their clocks may disagree. The proxy **learns** each
backend's clock offset from the `sentAt` field of every packet (the **maximum** of a sliding window
of `sentAt - arrival time`, i.e. the NTP min-delay filter), and uses it to convert ages onto the
other machine's timeline. Consequently:

- **Backend 5 minutes fast**: no longer "always healthy". A just-sent packet reads OK, and 30 seconds
  after it stops the row turns "stale" as usual.
- **Backend 5 minutes slow**: no longer "always stale". As long as it keeps reporting, it reads OK.
- **Offset beyond 10 minutes**: no longer used for learning — the correction is capped at 10 minutes,
  and anything beyond that is not absorbed. Note this does **not** mean "beyond 10 minutes always
  shows clock skew": the two thresholds are different (see below).
- **Forged timestamps**: age is anchored at the moment the packet arrives, so a forgery buys at most
  one "0 seconds ago" at arrival and is stale 30 seconds later. Before the fix it could buy a
  **permanent** "healthy".

**The actual predicates behind the "clock skew" state** (one per direction, different thresholds):

| Direction | Threshold | Constant |
|---|---|---|
| Timestamp in the **future** | beyond **60 seconds** | `SummaryPacket.MAX_FUTURE_AHEAD_MS` |
| Timestamp in the **past** | beyond **10 years** | `NetworkView.MAX_PLAUSIBLE_AGE_MS` |

Why the two differ by so much: in the future direction, more than 60 seconds is already enough to
rule out network latency, so the gate is tight. In the past direction it cannot be tight — real data
may be seconds old right after a backend restart, or minutes old because of a network partition, so
an hour-scale gate would produce false positives. The purpose of the 10-year figure is to catch
**structural** errors such as a 1969 timestamp (a missing field used as zero), not to measure skew.

Both directions report "clock skew" rather than "stale" because the wording has to be accurate: no
empty server produces a 1969 timestamp, so saying "possibly empty" points the operator at "nobody is
playing" when the thing to look at is that machine's clock.

Network latency can only make the age larger (more conservative) by construction; it cannot be used
to make data look fresher.

### Threshold semantics

The TPS / MSPT tier thresholds are **hard-coded on the proxy side** (`NetworkRenderer`), using the
backends' **shipped defaults**: TPS warn 18.0 / critical 15.0, MSPT warn 45.0 / critical 50.0 — and,
like the backend, with **strict comparison** (a reading has to cross the threshold to change tier;
being exactly equal does not).

"Strict" is not arbitrary: every alert-related decision on the backend comes from the single
operator table `ThresholdOperator`, in which `tps` / `mspt` are strict (`tps < 15`, `mspt > 50`) and
only memory and player ratio are closed intervals. So the sentence "the colour changed, therefore
the alert has fired" holds on both panels — TPS exactly 15.00 is yellow on both, exactly 18.00 is
green on both.

The proxy cannot read each backend's config, so if you change the backend's `tps.*` / `mspt.*`
thresholds the two panels will disagree on colour — either change the four constants in
`NetworkRenderer` too, or accept the difference.

**Unbelievable readings become "—", not a nicer-looking number.** The summary arrives over the
network, and a corrupt or forged packet can carry absurd magnitudes (`tps1m = 1e308`). Such a value
is not a measurement, so it is treated as "no value":

| Column | Treated as "—" | Why |
|---|---|---|
| TPS | negative, `NaN`, `> 1000` | The normal ceiling is 20 and `/tick sprint` can reach hundreds, so 1000 leaves plenty of room; `0` **is a legal reading** (a frozen server, and it must be red) |
| MSPT | `≤ 0`, `> 60000` | `≤ 0` is the backend's "not collected" sentinel (same rule as the backend's `ThresholdColors`); a tick stuck for a few seconds is a real fault and must be shown |

**This check runs twice, because there are two exits**: the render layer keeps it out of the cell
(otherwise one 309-character cell stretches that column — and therefore **every row** — to 312
cells), and the aggregate layer checks again (otherwise `1e308` poisons the network-wide TPS into an
astronomical number).

### Table width: a section of its own

The panel aligns with spaces, so every cell is padded by **display width**. The rule is:

1. **Combining marks / format characters → 0 cells** (the accent on `é`, variation selectors, ZWJ,
   BOM, soft hyphen)
2. **East Asian Width W / F → 2 cells** (CJK ideographs, full-width kana, Hangul, full-width
   punctuation, the vast majority of emoji)
3. **Everything else → 1 cell** (ASCII, half-width kana `ｱ`, and the Ambiguous class)

Emoji are measured by **grapheme cluster**: `👨‍👩‍👧‍👦` (4 code points + 3 ZWJ) is **one** 2-cell
glyph on the client, not 8 or 14. The skin-tone modifier on `👍🏽` works the same way.

**Known imprecision, stated here rather than pretending it does not exist:**

- **The Ambiguous class counts as 1 cell** (`Ω`, `α`, `—`, `…`). The only ones this panel actually
  prints are `—` and `…`, so the effect is "a cell containing one of those may be off by 1 cell",
  and it does not accumulate across rows. Counting them as 2 would double the width of plain
  Latin/Greek text, which is the more common direction of error.
- **Keycap sequences (`1️⃣`)** count as 1 cell; they render as 2.
- Perfect accuracy would need an emoji presentation sequence table plus per-font glyph width tables,
  and both drift with Unicode versions and resource packs. The payoff is a few pixels; the cost is a
  table to maintain forever. So it is not done.

**Width has three ceilings, so one cell cannot blow up the table:**

| Position | Ceiling | Notes |
|---|---|---|
| Backend-name cell | 20 display cells | Truncated with `…`. Real backend names are all within 16 |
| Any single column | 32 display cells | The overall gate; keeps a row at roughly 202 cells or fewer |
| `padTo` | the width the caller passes | **Truncates** on overflow (it used to return the string unchanged, and only worked while callers passed widths in the right order) |

> Why the backend name is capped by **display width** rather than UTF-16 length: the parser-level
> gate counts characters (64), which governs memory and single-message size; but 64 CJK characters
> are **128 display cells**, pushing a row to roughly 165 cells, which wraps into three lines in a
> ~53-cell chat box. The two rules ask different questions.

### When the player total is incomplete

The total **counts only OK backends** — mixing stale numbers in would be inventing a total. But a
bare number gets read as "the whole network is online", so the panel also states **how much was left
out**:

```
后端 4 个：正常 2 · 数据过期 1 · 无数据 1 ｜ 全网 TPS 15.00 ｜ 在线合计 15（+1 未计入）
```

(Read it as: "Backends: 4 — OK 2 · stale 1 · no data 1 | network TPS 15.00 | players online 15
(+1 not counted)".)

How to read it: 15 confirmed; one more player sits on a backend in "stale", whose number is
untrustworthy and therefore not counted.

Backends with no reading at all are listed separately:
`7（+3 未计入 · 另 1 台无读数）` — "has a number but we do not trust it" and "has no number at all"
are two different things, and merging them makes the reader think the whole gap is real people.

"No data" does **not** count toward "incomplete": this channel rides player connections, so a
backend that never reported is almost certainly empty. Counting it as missing would leave every
network with a few empty backends permanently carrying that marker, and the marker would lose its
meaning.

### Staleness follows the backend's push interval

The packet carries a `pushIntervalSec` field (the backend's `network.push-interval-seconds` value).
The proxy judges "stale" at `3 × that value`, so raising the interval to 60 seconds is fine; only
when an old backend omits the field does the proxy fall back to its own 10-second assumption
(3 × 10 = 30 seconds).

## What to configure on the backend side

In each Paper backend's `config.yml`:

```yaml
network:
  enabled: true
  server-id: "survival"       # must differ per backend, or the proxy merges them into one row
  push-interval-seconds: 10
```

**`server-id` has three hard limits, enforced on both sides with character-for-character identical
rules:**

| Limit | Value | Consequence when violated |
|---|---|---|
| Length | ≤ **64** characters (characters, not bytes; one CJK character counts as 1) | The backend **refuses to send** and logs a warning; the proxy drops the whole packet |
| Reserved word | may not be **`unknown`** (case sensitive, `Unknown` is legal) | Same as above |
| Characters | no control characters (C0 0x00–0x1F, C1 0x80–0x9F, DEL 0x7F) and no **`§`** (U+00A7) | The backend **refuses to send** and quotes the code point; the proxy **strips** them from any packet it does receive and carries on |

The backend blocks it **before sending**, naming "length / reserved word / illegal character" in the
log along with the actual character count and the ceiling — precisely to kill the old failure mode
where an over-long id was silently dropped by the proxy and the proxy log only said "unparseable
cross-server summary, check versions", pointing the owner at a completely unrelated direction
(version mismatch) and sending them down the wrong path entirely.

> Why control characters are blocked on the backend too: the proxy **strips** them, so
> `survival\u0007` and a genuine `survival` collapse into the same id — whichever reports first wins
> the binding, and the real backend is then judged an impostor and dropped **permanently**.

> The 64-character ceiling is a **protocol constant**, not a setting. The two sides cannot share
> code (one is Paper, one is Velocity), so they meet at the same literal `64`, pinned by tests on
> each side.

### Control characters are stripped from the summary

The `server` field comes from the network and gets spliced into a line of an administrator's chat
table. So the proxy **strips** control characters (`\n` `\r` `\t`, C0/C1 controls, DEL) and `§`
(U+00A7, the legacy formatting prefix) after reading it:

- `\n` / `\r` split a panel row into two — that is pure visual forgery, and an administrator will
  take a server that does not exist for a real one;
- `\t` destroys column alignment; BEL / ESC ring bells or emit escape sequences in terminal logs;
- `§c` and friends are colour codes anywhere legacy serialisation happens, letting one backend
  paint the panel and swallow the adjacent columns.

Stripping rather than rejecting the packet: rejecting would make that backend invisible (the same
failure shape as an over-long id), while its numbers may be perfectly good.

**It is strongly recommended that `server-id` match the backend's name in `velocity.toml` verbatim**
(not mandatory — see below for why). The panel's row set is "the names registered under `[servers]`"
∪ "the `server-id`s we have received packets from", so when the two disagree you get **ghost rows**:

```yaml
# velocity.toml
[servers]
survival-1 = "10.0.0.11:25565"
skyblock-1 = "10.0.0.12:25565"
lobby      = "10.0.0.13:25565"
```

```yaml
# config.yml on the survival-1 machine
network:
  enabled: true
  server-id: "survival-1"     # recommended: identical to the name above, case included
```

Set it to `survival` instead and the panel shows two rows at once — `survival-1` (no data) and
`survival` (has data).

### Source binding: why "inconsistent" is not rejected, but "squatting" must be

The `server` field is filled in by the backend itself, so "this message came from some backend
connection" cannot stop **backend A impersonating backend B** — a backend with a planted plugin can
send `server="B"` and overwrite all of B's data (paint B red, or disguise a genuinely struggling B
as 20 TPS so the alert never fires). The proxy therefore does **source binding**: on the first
packet it pins each `server-id` to one backend, using
`ServerConnection.getServerInfo().getName()` (the `[servers]` key in `velocity.toml`, which a backend
cannot forge).

The rule deliberately **does not require the names to be equal**, only that "one identity belongs to
one backend":

| Situation | Handling | Why |
|---|---|---|
| A `server-id` appears for the first time | Bound to the backend it came from | — |
| Later packets with the same `server-id` from the **same** backend | Accepted normally | Regardless of whether the name equals the `velocity.toml` name |
| Later packets with the same `server-id` from a **different** backend | **Dropped** with a warning | This is exactly the shape of A overwriting B |

**Why not enforce `server-id == backend name`.** An early README said it was required, but that was
only a **recommendation**, and existing deployments certainly include people who did not follow it
(using `server-id` as a friendly display name, separate from the internal registration name). If
equality were enforced — or "mismatch means drop" — those deployments would **go dark overnight**
after upgrading, with a symptom (an empty panel) identical to "the proxy does not have the plugin",
and very hard to self-diagnose. The weaker rule "one identity belongs to one backend" already
**closes the overwrite attack**: to impersonate B, A must either use B's id (already held by B →
dropped) or its own id (which overwrites only A itself, harmless).

A name mismatch still raises a **warning** (once per id/backend pair), because it is the only clue
that explains a ghost row — but only when there really is a mismatch, so a normal deployment stays
quiet.

**`/pscope network reset` clears the binding table as well**: after swapping backends or changing
`server-id`, an old "id belongs to backend" entry left in place would make the new backend look like
a squatter and get dropped forever.

You also need PerfScope installed on each backend with `network.enabled` turned on in its
`config.yml`.

**About the proxy's forwarding mode**: `modern` and `traditional` / `legacy` **both work**. An
earlier version of this document said plugin messaging was unavailable under `modern`; that was
**wrong** — modern forwarding itself completes its handshake through the `velocity:player_info`
plugin message, and the backend→proxy plugin message path (`BackendPlaySessionHandler`) contains no
forwarding-mode branch at all. The only gate is whether the channel is registered. What genuinely
does not work under `modern` is the legacy channel dedicated to BungeeCord, which has nothing to do
with this plugin. **Do not downgrade the proxy to legacy over this** — that brings in a worse trust
model than the problem it solves.

### What it cannot see

The panel has only four metrics: **TPS(1m) / MSPT / online players / whether collection is alive**.
Entity counts, chunk counts, memory, CPU and hotspot chunks still require logging into that backend
and running `/perfscope` — the proxy has no tick loop and no worlds, so there is genuinely nothing to
collect, and the summary is deliberately kept tiny (a few dozen bytes per backend per round).

## How the two repositories relate

| Repository | Installed on | Role |
|---|---|---|
| [Minecraft-server-plugin-PerfScope](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope) | Every Paper backend | Collects metrics + pushes the summary |
| This repository | The Velocity proxy | Receives the summary + renders `/pscope network` |

**Version compatibility rule**: `SummaryPacket.PROTOCOL_VERSION` decides whether the two sides can
talk. The rule is **one-directional** — the receiver accepts packets with "protocol version ≤ its
own" and drops anything "higher than its own" (`version < 1 || version > PROTOCOL_VERSION` is
rejected). So the two sides **need not be identical**, but the proxy's protocol version **must not be
lower** than the backend's: as soon as the backend's number is higher, its packets are dropped
wholesale. The artifact version (`1.4.5-SNAPSHOT-fix4` and the like) only says "which build of the
code this is"; do not conflate the two. On a protocol mismatch the higher version's packets are
**dropped outright and logged** (at most once a minute, carrying the drop count and **the peer's
declared version**, so you can tell which side to upgrade) rather than "parsed as best we can" —
the latter reads new fields as missing, exactly the class of silent distortion this project keeps
fixing.

**Upgrade order**: upgrade the proxy first, then the backends. The receiver only rejects packets
"higher than its own", so a proxy protocol version ≥ the backend's is the smoothest; the reverse
(backends first, on a higher protocol) means a window where everything is dropped. Within the same
protocol version the two sides can be upgraded in any order.

## Clearing the panel: `reset`

```
/pscope network reset
```

Clears every summary received so far, so the panel starts counting from scratch. Useful after
swapping backends, after changing `server-id`, or when troubleshooting and you want a ghost row to
disappear; until the next packet arrives the panel is empty (genuinely empty, not a leftover of the
previous round). Same permission as `/pscope network` (`perfscope.network`), and the alias spellings
`/perfscope network reset` and `/ps network reset` work too.

## Building

```bash
mvn clean package
node tools/check-velocity-descriptor.mjs   # verify the descriptor inside the jar
```

Output: `target/PerfScope-Velocity-<version>.jar` — drop it into Velocity's `plugins/` directory.

`mvn clean package` runs the unit tests first and then shades Gson into the jar under
`cn.perfscope.velocity.shaded.gson` (the Velocity API itself is `provided` and never bundled).
Useful variants:

```bash
mvn test                        # tests only
mvn test -Dtest=SummaryTest     # one test class
mvn clean package -DskipTests   # package without tests
```

For the current test count, read the `Tests run: …` summary line `mvn test` prints at the end
instead of counting `@Test` annotations in the source — the two drift.

## What that `resources-filtered` directory is for

Velocity's annotation processor generates `velocity-plugin.json` at **compile time**, and it writes
the version string from the `@Plugin` annotation verbatim, **without going through Maven's resource
filtering**. So the first packaging produces a descriptor containing the literal
`"version": "${project.version}"`.

That is not harmless: Velocity displays it as the plugin version in `/velocity plugins`, and version
checks during troubleshooting read it too. Worse, it does not error — the plugin loads fine, the
version field is just permanently a placeholder.

`src/main/resources-filtered/velocity-plugin.json` is a template carrying `${project.version}`,
which `maven-resources-plugin` copies over the one the processor wrote during `process-classes`
(after compile, before package). `tools/check-velocity-descriptor.mjs` keeps it from regressing.

## Tests

Every case lands on the pure-logic layer (`SummaryPacket` / `NetworkView` / `NetworkRenderer`) —
those three deliberately do not import `com.velocitypowered.*`, because the Velocity API cannot be
instantiated in a unit test, and the judgement rules and the rendering are what actually break.

(To get the current number, read the `Tests run: …` summary line `mvn test` prints at the end rather
than counting `@Test` annotations in the source — conditionally skipped cases are still one `@Test`
each, so the two numbers differ. Past versions of this page pinned a constant here and it went stale;
the badge at the top is a snapshot from one run, and the surefire line is always the live answer.)

Mutation verification was run on four of them (really editing production code and re-running):
missing fields defaulting to 0, the version gate being disabled, the aggregate counting non-OK
states, and a dead collector still showing numbers — all four KILLED.