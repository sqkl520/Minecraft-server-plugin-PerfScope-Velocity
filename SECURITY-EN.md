[简体中文](SECURITY.md) | English

# Security Policy

PerfScope-Velocity is a **read-only** display plugin that runs on a **Velocity proxy**. It collects
nothing: it only receives a performance summary that each Paper backend pushes over Minecraft's
plugin messaging channel (`perfscope:summary`), and renders it as a table behind `/pscope network`.

The plugin **makes no outbound connections, writes no files, reads no files, and carries no bStats or
any other telemetry**. The only place it touches the network is a plugin messaging channel that both
players and backends can address — which is exactly what this document is specific about: **what it
accepts, what it drops, and what a compromised backend can and cannot do.**

---

## Supported Versions

Security fixes are issued for the **latest minor line, matching the backend repository**.

| Version | Supported | Notes |
|---------|-----------|-------|
| 1.4.x | ✅ Yes | Security fixes land here |
| 1.3.x and older | ❌ No | The proxy side only became a separate artifact at 1.4.5; earlier versions do not exist |

Artifact versions stay in sync with the
[PerfScope backend](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope). The proxy's
protocol version must **never be lower** than the backend's: the receiver only accepts packets with
`v ≤ its own`, so as soon as the backend's number is higher every packet is dropped. The drop is **silent** (a once-a-minute log line carrying the count
and the peer's declared version), so upgrade order is itself part of operational safety — see
"Hardening recommendations" below.

---

## Reporting a Vulnerability

**Do not open a public issue for a security problem.** A public issue is visible to everyone,
including anyone who would exploit it before a fix exists.

**Preferred — GitHub Security Advisories:**

> <https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/security/advisories/new>

**Alternative — email:** `sqkl520soap@gmail.com` with `[PerfScope Security]` in the subject line.

You do not need to encrypt, and there is no PGP key. Do **not** paste server addresses, player names
or production credentials into the report; redact them.

### What to include

- Proxy-side PerfScope version (`velocity-plugin.json`, or the jar filename; formatted like
  `1.4.5-SNAPSHOT-fix4`)
- Velocity version and build, e.g. `Velocity 3.4.0 build 512`
- Backend plugin version, backend server software and build, e.g. `1.4.5-SNAPSHOT-fix4` /
  `Paper 1.21.4 build 232`
- Java version and OS
- What the issue is, and what an attacker gains from it — impact matters more than the mechanism
- Reproduction steps, or a proof of concept. A minimal repro is worth more than a long write-up
- Relevant log excerpts, **redacted** (server IPs, player names, `server-id`s)

### What to expect

| Stage | Target |
|-------|--------|
| Acknowledgement | **within 7 days** |
| Initial triage — accepted / need more info / out of scope | **within 14 days** |
| Fix, or a mitigation you can apply yourself | **within 90 days**; faster for high severity |
| Advisory published with credit | once the fixed version is available |

These are **targets from one person**, not a contractual SLA. The project is free and
volunteer-maintained, with no bug bounty. Unless you ask to stay anonymous, you will be credited in
the advisory and the CHANGELOG.

---

## Threat model

The proxy's trust boundary is **completely different** from the backend's. The backend collects its
own JVM's data, so its threats are mostly about "who can make it read or write". The proxy
**collects nothing**; its only job is to **believe numbers somebody else reported**. So the central
question here is an old one:

> A reading can look perfectly normal while its **identity** is fake.

This plugin handles the two separately: **identity** through source binding, **values** through
magnitude checks.

### 1. The channel is bidirectionally addressable: why only backend connections count

The channel name `perfscope:summary` is enumerable. Players can send to the proxy, the proxy can send
to players, backends can send to the proxy. The plugin only cares about summaries from backends, so
the first gate in the event handler is a type filter:

```java
if (!(event.getSource() instanceof ServerConnection)) return;
```

Without it the consequence is concrete: any client can forge "some backend has TPS 0" and paint the
panel red — pure denial of service, and the administrator goes to investigate a machine that was
healthy all along. Once the plugin claims the message it marks it `handled()`, so it is not
forwarded on to other receivers.

### 2. `server-id` is filled in by the backend: source binding

`instanceof` only answers "this message came from **some** backend connection", not "**which** one".
And the `server` field in the packet is written by the backend, fully forgeable. So backend A (or any
backend with a planted plugin) can send a packet with `server="B"` and:

- paint B red (write 0 TPS / 0 players), or
- conversely disguise a **genuinely struggling** B as 20 TPS so the alert never fires.

However plausible the numbers look, they are useless because the **identity** is wrong. The plugin
therefore does **source binding**: `ServerConnection.getServerInfo().getName()` (the `[servers]` key
of `velocity.toml`, maintained by the proxy itself — a backend cannot forge "which TCP connection is
mine") pins each `server-id` to **one** backend.

| Situation | Handling |
|---|---|
| `server-id` appears for the first time | Bound to the backend it came from |
| Later packets with the same `server-id` from the **same** backend | Accepted |
| Later packets with the same `server-id` from a **different** backend | **Dropped** with a warning (this is the shape of an overwrite attack) |

The rule deliberately **does not require** `server-id` to equal the backend name: enforcing that
would make existing deployments (which use `server-id` as a friendly display name) **go dark
overnight** after upgrading, with a symptom character-for-character identical to "the proxy does not
have the plugin". A name mismatch still raises a warning (once per id/backend pair), because it is
the only clue that explains a ghost row.

### 3. After identity, filter length and characters

- **Length gate**: a packet whose `server-id` exceeds **64** characters is dropped whole. The field
  comes straight off the network and gets printed verbatim onto an in-game panel; without a ceiling a
  malicious backend can fill the proxy's heap with over-long ids and push a multi-megabyte chat
  message into an administrator's client.
- **Control characters stripped**: the `server` value that gets read is **stripped** of `\n` `\r`
  `\t`, C0/C1 controls, DEL and `§` (U+00A7, the legacy formatting prefix). `\n` splits one row into
  two — pure **visual forgery**, and an administrator will take a non-existent server for a real one;
  `§c` lets one backend paint the whole panel. Stripping rather than rejecting the packet, because
  rejecting makes that backend invisible (the same failure shape as an over-long id) while its
  numbers may be fine.
- **Display-width gate**: the backend-name cell is capped at 20 **display** cells, truncated with
  `…`. This is a different rule from the one above — 64 CJK characters pass the UTF-16 length gate
  but are 128 display cells, enough to push the row out of the chat box and destroy the panel's
  vertical comparison.

### 4. Values: not every number deserves belief

The summary arrives over the network, so:

| Rule | Handling | Why |
|---|---|---|
| `tps1m` negative / `NaN` / `> 1000` | Treated as "no value" (`—`) | `0` **is** a legal reading (a frozen server, and it must be red); `1e308` is not a measurement |
| `msptAvg` `≤ 0` / `> 60000` | Treated as "no value" | `≤ 0` is the backend's "not collected" sentinel; a tick stuck for seconds is a real fault |
| `players` out of range (`< 0` or `> 100000`) | Treated as "not collected" | A forged packet must not blow the aggregate up to an absurd magnitude |
| `pushIntervalSec` out of range (not 1–300) | Treated as "not given" | A forged enormous interval would make that backend read "healthy" **forever** |

The magnitude check runs **twice**, because there are two exits: the render layer (otherwise one
309-character cell stretches that column — and every row — to 312 cells) and the aggregate layer
(otherwise `1e308` poisons the network-wide TPS into an astronomical number).

### 5. Timestamps: forgery and clock drift

Snapshot age is built on a timestamp **written by the remote side**. The implementation does three
things, each of which has a concrete bad outcome if omitted:

- **Age is anchored at the moment the packet arrived**, not at "the current time". With the current
  time as the basis, a forged future timestamp computes a negative age at any moment, gets clamped to
  0, and a dead backend reads "healthy" **forever**. Anchored at arrival, a forgery buys at most one
  "0 seconds" at arrival and goes stale 30 seconds later.
- **Clock offset is only corrected within ±10 minutes, and out-of-range samples are not learned
  from.** The offset is estimated per backend from a sliding window of `sentAt - arrival time`
  (taking the maximum, i.e. the NTP min-delay filter). If a forged `+1 day` sample were learned, the
  estimate would be dragged to the scale of a day and the ages of **every other** backend would
  distort with it. Out-of-range samples are discarded at the entrance, so a forger can only affect
  its own packet.
- **Both directions become "clock skew"**: a timestamp in the future (beyond tolerance) or absurdly
  far in the past (over 10 years) is not used as a reading; the row is demoted to unknown and the age
  cell says "ahead N" / "behind N", and **never** "0 seconds ago" — which is precisely the sentence a
  forger is trying to buy.

### 6. Memory and logging: network input must be bounded

All three tables are driven by network input, so all three are bounded:

| Structure | Ceiling | Eviction |
|---|---|---|
| Received-packet tables (`latest` / `anchors` / `skewSamples`) | 256 backends | Evict the least recently updated, clearing all three together |
| Source-binding table | 512 entries | **LRU eviction**, never a wholesale clear |
| "Already warned" table | 256 entries | LRU eviction |

Why the binding table cannot be `clear()`ed: clearing **reopens the squatting window**. An attacker
floods new ids to fill the table, the clear wipes it, the real victim's binding is gone, the attacker
immediately squats on it successfully, and the real victim is judged a squatter **permanently** from
then on.

Logging is rate-limited throughout, and **the rate-limit criterion must not depend on the diversity
of the input**:

- Unparseable packets: at most one line per 60 seconds, carrying the drop count and **the peer's
  declared protocol version**;
- Packets with no `server-id`: at most one line per 60 seconds, carrying the drop count;
- Source mismatches: two layers — one per (id, backend name) pair (recorded in a 256-entry LRU table,
  so it only repeats once that entry has been evicted), plus **one globally per 60 seconds**. The
  second layer is mandatory, because the first layer's key is entirely controlled by the sender: a new
  id per packet yields a new key — measured at 1000 ids → 1000 WARN lines. The number of suppressed
  entries is reported in the next line, so the scale stays visible.

### 7. Command permission: fail-closed

`/pscope network` (including `reset`) requires `perfscope.network`. The console is allowed by a
**type whitelist** (`ConsoleCommandSource`), not by "anything that is not a `Player`" — the latter is
fail-open: Velocity lets third-party plugins construct their own `CommandSource`, so a
command-block / scripting plugin that wraps a player identity in a custom source would let that
player bypass the permission unconditionally.

**With no permission plugin installed, nobody can see it** (`permissionFunction` is
`ALWAYS_UNDEFINED` and `hasPermission` returns false). That is the conservative correct default, but
deployers must know it — see "Known non-issues".

---

## What this plugin sends and stores

**Nothing is sent, nothing is stored.**

| Item | Proxy side |
|---|---|
| Outbound HTTP / socket | **None.** No `HttpClient` / `URL` / `Socket` anywhere in the source |
| Telemetry / bStats | **None** |
| Files written | **None.** No config, no log file, no report, no cache |
| Files read | **None** (`@DataDirectory` is injected by Velocity, but this plugin never reads or writes it) |
| Player names / UUIDs / IPs | **Never collected or displayed.** The panel holds backend names and four metrics only |

All state lives in memory and is gone on restart. You can verify this yourself:

```bash
grep -rn "HttpClient\|HttpRequest\|openConnection\|new URL(\|new Socket\|Files\." src/main/java/
```

(There should be no hits. If that ever changes, this section must change with it.)

### What it shows, and to whom

The output of `/pscope network` goes to **whoever ran the command** (a player holding
`perfscope.network`, or the console). It contains:

- Every backend name — including those registered in `velocity.toml` that **never reported**;
- Each one's TPS / MSPT / player count / data age;
- The network-wide player total.

If a backend name contains something like an internal hostname, it appears verbatim in that player's
chat window (truncated to 20 display cells, but **not hidden**). Confirm that is acceptable before
granting the permission.

---

## Scope — what counts as a security issue

**In scope:**

- Any path that lets **a player without the permission** run `/pscope network`, read the panel, or
  clear it
- **Cross-backend identity forgery**: backend A overwrites B's data using B's `server-id` without
  being rejected
- A **non-backend source** (a player client, or another plugin) getting a forged reading onto the
  panel
- Layout / visual forgery: fabricating a non-existent server inside a row through control characters
  or `§` in a `server-id`
- **Unbounded memory growth** or **unbounded log growth** from network input (a denial of service
  any connected backend can trigger)
- Making a dead backend read "healthy" **permanently** via timestamps
- Build-chain problems: a shaded Gson copy without relocation, `velocity-api` bundled into the jar,
  CI workflows, release artifacts

**Out of scope** — these are expected behaviour, not vulnerabilities:

- **Anything requiring proxy console / OP permission.** An administrator can already read logs, edit
  config and run arbitrary commands.
- **Anything requiring another malicious plugin** or a modified proxy jar. Plugins share one JVM and
  modern Minecraft has no SecurityManager.
- **A compromised backend lying about its own numbers.** Source binding guarantees "one identity
  belongs to one backend", not "this reading is true". A backend can absolutely report **its own**
  TPS as 20 (or 0) — it is forging itself, that cannot be distinguished from the proxy side, and it
  is outside the boundary this plugin can hold. See "Known non-issues" below.
- **Display oddities caused by backend/proxy clock drift.** There is a dedicated "clock skew" state
  that describes it; that is a feature, not a vulnerability.
- **An empty backend showing "no data".** An inherent limit of the channel, and a deliberate display
  discipline.
- **Vulnerabilities in Minecraft / Velocity / Paper themselves.** Report those upstream.
- **Any issue in a version not in the supported list above.**

### Known non-issues

These get reported as bugs or security problems, but all are intentional:

1. **"A backend shows 'no data'."** Plugin messaging rides player connections; an empty backend has no
   forwarding path. Showing "no data" rather than "healthy" or "0" is deliberate — collapsing the
   three into one "-" is the most common lie in dashboards.
2. **"Players can't use `/pscope network` after I installed it."** With no permission plugin,
   Velocity's permission function is undefined and `hasPermission` returns `false`. That is the
   conservative correct default: install a permission plugin (LuckPerms and friends) and grant
   `perfscope.network`.
3. **"Without permission a player sees the backend PerfScope help page, not a 'no permission'
   message."** Velocity **forwards** the unpermitted command to the backend to execute. If you see a
   help page and nothing happens, check permissions first.
4. **"`/perfscope tps` is caught by the proxy and every backend subcommand is unreachable."** This
   plugin registers `perfscope` as an alias and proxy commands win over backend ones. Use the console,
   or write `/perfscope:perfscope tps`. `/perfscope:tps` does not work — `tps` is a subcommand and
   never enters the command table.
5. **"A backend reports fake TPS."** See "Out of scope" above. The proxy can guarantee identity
   uniqueness, not value honesty. If you suspect a backend is compromised, investigate on that
   machine, not on the proxy.
6. **"Two backends use the same `server-id` and one is dropped forever."** Intentional: one identity
   belongs to one backend. The warning names both sides; fix the `network.server-id` as it says.

---

## Hardening recommendations

- **Grant `perfscope.network` only to people who genuinely need network-wide status.** The panel
  exposes every backend name (including ones that never reported) and player counts.
- **Make `server-id` identical to the backend name in `velocity.toml`.** Not for security (the rule
  does not require equality) but to avoid ghost rows — one "no data" row next to one row with data is
  the easiest first-install misdiagnosis there is.
- **Give every backend a different `server-id`.** A duplicate configuration makes one of them be
  treated as a squatter and dropped forever.
- **Upgrade order: proxy first, then backends.** The receiver only rejects packets "higher than its
  own"; the reverse (backends first) leaves a window where everything is dropped — and the drop is
  **silent**, visible only in a once-a-minute counter warning.
- **Do not downgrade the proxy to legacy forwarding "to make plugin messaging work".** `modern` and
  `legacy` both work; downgrading brings in a worse trust model than the problem it is meant to solve.
- Run the proxy under an account that cannot read other users' files. This plugin writes none, but
  other plugins do.