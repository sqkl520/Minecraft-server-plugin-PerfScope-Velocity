[简体中文](CONTRIBUTING.md) | English

# Contributing guide

## 1. What this project is, stated plainly

This is a **personal project**: the Velocity proxy side of a performance monitoring setup for
Minecraft networks (Java 17, AGPL-3.0), built by a high-school student with AI assistance (vibe
coding) and ground down bit by bit on a real proxy network. It is one half of
[PerfScope](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope) (a Paper backend plugin):
the backend collects metrics and pushes a tiny summary, this repository receives it and renders
`/pscope network`.

Three honest facts up front, so your time is not wasted:

- **No response-time promise.** School, exams and being stuck on a bug can all mean weeks without
  looking at Issues.
- **No promise that "I'll build this feature".** Direction is set by the author; a genuinely good
  idea in an Issue may still not get built.
- **But PRs are read carefully.** A PR with tests and a clear motivation has a much higher chance
  than a "one-line change".

If you can accept those three points, read on.

## 2. Reporting a problem

**Search the [Issues](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/issues)
first (closed ones included)** — a question like "the panel shows no data everywhere" has likely
been asked before.

Please include all six of these in a bug report; missing one usually costs an extra round trip:

1. **Velocity version and build** (e.g. `Velocity 3.4.0 build 512`)
2. **Each Paper backend's version and build** (e.g. `Paper 1.20.4 build 343`)
3. **Both PerfScope versions** (proxy side: read `velocity-plugin.json` or the jar filename;
   backend side: first line of `/perfscope status`, or the jar filename; formatted like
   `1.4.5-SNAPSHOT-fix4`)
4. **Steps to reproduce** — which command, and exactly which row or column of the panel is wrong
5. **Relevant logs**: the `PerfScope` lines in the proxy's `logs/latest.log` (paste the full stack
   trace if there is one), plus the matching backend's log
6. **Configuration**: the `network` section of the backend's `config.yml` (you may redact
   `server-id`, but keep the shape) and the `[servers]` section of `velocity.toml` (likewise)

If your report is "a backend does not show up" or "a backend shows no data", **also say whether that
backend had any players online at the time**. The summary rides player connections, so an empty
backend cannot send one — that is a design boundary, not a fault. Stating "was it empty at the time"
saves an entire round trip.

If a *number* looks wrong, also say "I expected X, it shows Y". Most bugs in this plugin are
judgement errors of one class — a healthy reading reported as a fault, or the reverse — so the value
you expected is often more useful than the log.

## 3. Submitting a Pull Request

1. Fork this repository, create a branch off `main` (e.g. `fix/stale-threshold`), and keep the
   change focused — one PR, one thing.
2. Make sure the build passes locally (see below) and run the descriptor gate.
3. Open a PR against `main`.
4. CI (`.github/workflows/build.yml`) builds on both JDK 17 and 21 and verifies the jar descriptor;
   PRs with failing CI will not be merged.

**Open PRs in both repositories for cross-repo changes.** This repository talks to the
[backend repository](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope) through a
**protocol contract** (field names, semantics, `PROTOCOL_VERSION`, the length ceiling and reserved
word for `server-id`). The contract is written once on each side and pinned by tests; sharing code
is not an option. Changing only one side creates a window where the two sides disagree, and **no
test anywhere goes red** — so both must open and merge together.

## 4. Build requirements

You need **JDK 17** and **Maven** (no Maven Wrapper in this repo — use your installed `mvn`). Run
from the repository root (where `pom.xml` lives):

```bash
mvn clean package                 # full build (with tests), output in target/
mvn clean package -DskipTests     # quick build, skipping tests
mvn test                          # run tests only
mvn test -Dtest=SummaryTest        # run a single test class
```

- The target platform is **Velocity 3.x** (Java 17). `velocity-api` is a `provided` dependency
  supplied by the proxy at runtime and **must never be bundled** — bundling it causes class-loading
  conflicts on the proxy.
- Gson is shaded and relocated to `cn.perfscope.velocity.shaded.gson`. Do not remove the relocation:
  other plugins on the proxy may ship a different Gson, and without relocation the classes collide.
- After any code change, `mvn clean package` must compile successfully.
- When adding or changing commands, features, or permissions, update `README.md` and `README-EN.md`
  accordingly, and append a one-line entry under the current version section of `CHANGELOG.md`.

### Self-checks before you push (important)

After building, run the descriptor gate:

```bash
node tools/check-velocity-descriptor.mjs
```

It verifies the jar's `velocity-plugin.json`: `version` must be resolved (never the literal
`${project.version}`) and must match `pom.xml`, and it also confirms that both the `com/google/gson/`
and `com/velocitypowered/` entry counts are zero. Each of those three maps to a bug that actually
happened:

- The annotation processor writes the descriptor at **compile time**, bypassing Maven's resource
  filtering, so without the workaround the version field is a placeholder forever — and it does
  **not** error;
- A missing Gson relocation collides with another plugin's Gson, whose symptom is some unrelated
  plugin throwing `NoSuchMethodError` — which points troubleshooting in the wrong direction;
- A `velocity-api` dependency accidentally left at `compile` scope bundles the whole API into the
  jar.

**Tests matter more than the code change itself.** Every fix in this project is required to come with
an assertion that goes red *before* the fix — otherwise it is not fixed, just edited. If you can show
that your test actually catches the bug, your PR is very likely to land.

Cases land on the pure-logic layer (`SummaryPacket` / `NetworkView` / `NetworkRenderer`) — those
three deliberately do not import `com.velocitypowered.*`. Put new logic in those layers rather than
in the command layer or the event handler: the latter cannot be instantiated in a unit test, so once
a judgement rule moves in there it can only be verified on real hardware, which is the slowest and
leakiest kind of verification there is.

## 5. CLA (Contributor License Agreement)

**Before your first PR, please read [CLA.md](CLA.md).** You retain copyright of your Contribution,
while granting the Maintainer a perpetual, worldwide, non-exclusive, royalty-free, irrevocable
license that includes the **right to sublicense** (including under proprietary/commercial licenses).

- **First-time contributors**: state either of the following in your PR description to sign the CLA:

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- The Maintainer will record your **GitHub username and the date of agreement** when merging your
  first PR; later Contributions are covered automatically with no repeated statement.
- If you contribute on behalf of someone else (e.g. your employer), make sure you are authorized to
  do so.

## 6. Behavior expectations

- Be kind and stay on topic; Chinese or English are both fine.
- Before suggesting features, check the README's "What it cannot see" — this plugin deliberately
  shows four metrics only. Entity, chunk, memory and CPU investigation belongs on the backend with
  `/perfscope`; the proxy side must not become a second backend panel.
- The Maintainer does not promise response times and does not guarantee that any PR will be merged.
- Do not put any AI tool attribution lines in commit messages or PR descriptions. Commits carry the
  author's own git identity only.
