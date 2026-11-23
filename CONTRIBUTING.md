[English](CONTRIBUTING-EN.md) | 简体中文

# 贡献指南

## 1. 先说清楚这是什么项目

这是一个**个人项目**：高一牲用 AI 辅助（vibe coding）写出来、在真实群组服上一点点磨的
Velocity 代理侧插件（Java 17，AGPL-3.0）。它是
[PerfScope](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope)（Paper 后端插件）的配套一半：
后端负责采集并上报一份极小的性能摘要，本仓库负责接收并渲染 `/pscope network`。

先说三件实话，免得你白等：

- **不承诺响应时效**。上课、考试、被 bug 卡住都可能导致几周不看 Issue。
- **不承诺"这个功能我要做"**。这里的方向由作者自己定，Issue 里说得很好的点子也可能不做。
- **但 PR 会被认真读**。带测试、说清动机的 PR，通过率比"只改一行"高得多。

如果你能接受这三点，往下看。

## 2. 报告问题

**先搜一遍 [Issues](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/issues)
（含已关闭的）**——"面板全是无数据"这类问题很可能已经有人问过。

报 bug 请带上这六项，缺一项都可能让排查多绕几轮：

1. **Velocity 版本与构建号**（如 `Velocity 3.4.0 build 512`）
2. **各 Paper 后端的版本与构建号**（如 `Paper 1.20.4 build 343`）
3. **两侧 PerfScope 的版本**（代理侧读 `velocity-plugin.json` 或 jar 文件名；
   后端侧用 `/perfscope status` 首行或 jar 文件名；形如 `1.4.5-SNAPSHOT-fix4`）
4. **复现步骤**——哪条命令、面板上具体是哪一行哪一列不对
5. **相关日志**：代理的 `logs/latest.log` 里含 `PerfScope` 的行（有异常请贴完整堆栈），
   以及对应后端的日志
6. **配置**：后端 `config.yml` 的 `network` 段（`server-id` 可以打码但请保留格式），
   以及 `velocity.toml` 的 `[servers]` 段（同理）

如果你说的是"某台后端不出现"或"某台显示无数据"，**请一并说明那台当时有没有玩家在线**。
这条链路的摘要走玩家连接，空服的后端本来就发不出来——这是设计边界，不是故障。把"当时是否空服"
写进报告，能省掉一整轮往返。

如果是指标数值不对，请一并说明「你以为是多少、实际显示多少」。这个插件的大部分 bug
都是「把健康说成毛病 / 把毛病说成健康」这一类的判定错误，对照值比日志更有用。

## 3. 提交 Pull Request

1. Fork 本仓库，从 `main` 新建分支（如 `fix/stale-threshold`），改动聚焦——一个 PR 只做一件事。
2. 提交前确保本地构建通过（见下），并跑过描述符门禁脚本。
3. 向 `main` 提交 PR。
4. CI（`.github/workflows/build.yml`）会在 JDK 17 与 21 上各构建一次并校验 jar 描述符；
   CI 不通过不会被合并。

**跨仓库改动请两个仓库一起开 PR。** 本仓库与
[后端仓库](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope) 通过一条
**协议契约**对接（字段名、语义、`PROTOCOL_VERSION`、`server-id` 的长度上限与保留字）。
契约是两边各写一份、靠测试钉住的，没有共享代码的可能。只改一侧会出现一段"两边判据不一致"
的窗口，而**没有任何测试会因此变红**——所以两边必须同时开、同时合。

## 4. 构建要求

需要 **JDK 17** 与 **Maven**（本项目无 Maven Wrapper，请使用已安装的 `mvn`）。在仓库根目录
（`pom.xml` 所在处）执行：

```bash
mvn clean package              # 完整构建（含测试），产物在 target/
mvn clean package -DskipTests  # 跳过测试的快速构建
mvn test                       # 仅运行测试
mvn test -Dtest=SummaryTest      # 运行单个测试类
```

- 目标平台是 **Velocity 3.x**（Java 17）。`velocity-api` 是 `provided` 依赖，运行期由代理提供，
  **绝不打进 jar**——打进去会在代理上造成类加载冲突。
- Gson 会被 shade 并 relocate 到 `cn.perfscope.velocity.shaded.gson`。不要去掉 relocate：
  代理上可能已有别的插件带着不同版本的 Gson，不 relocate 就会撞类。
- 修改代码后必须保证 `mvn clean package` 编译通过。
- 新增/修改命令、功能或权限时，请同步更新 `README.md` 与 `README-EN.md`，
  并在 `CHANGELOG.md` 的当前版本段落下追加一行变更记录。

### 提交前的自检（重要）

构建完成后跑一遍描述符门禁：

```bash
node tools/check-velocity-descriptor.mjs
```

它校验 jar 里的 `velocity-plugin.json`：`version` 必须是已解析的真实版本（不能是字面量
`${project.version}`）、必须与 `pom.xml` 一致，并顺带确认 `com/google/gson/` 与
`com/velocitypowered/` 两类条目都是 0。这三条各自都对应一次真实踩过的坑：

- 注解处理器在**编译期**写出描述符，不经过 Maven 的 resource filtering，所以不处理的话
  版本那一栏永远是占位符，且**不报错**；
- Gson 忘了 relocate 会与代理上其它插件撞类，症状是某个无关插件开始抛
  `NoSuchMethodError`——排查方向会被指错；
- `velocity-api` 漏成 `compile` scope 会把整个 API 打进 jar。

**加测试比改代码更重要。** 这个项目的每一条修复都要求「有一条断言能在修复前变红」，
否则修了等于没修。只要你能证明测试真的能抓住那个 bug，PR 基本就过了。

用例打在纯逻辑层（`SummaryPacket` / `NetworkView` / `NetworkRenderer`）——这三个类刻意
不 import `com.velocitypowered.*`。新逻辑请尽量放进这三层，而不是塞进命令层或事件处理器：
后者在单测里没法实例化，判定规则一旦进去就只能靠实机验证，而那是最慢也最容易漏的一种验证。

## 5. CLA（贡献者许可协议）

**首次提交 PR 之前，请先阅读 [CLA.md](CLA.md)。** 你保留自己贡献的版权；同时授予维护者一项永久、全球、非独占、免版税、不可撤销的许可，其中包括**再许可权**（可将你的贡献以任何许可证再许可，含闭源/商业许可证）。

- **首次贡献者**：请在 PR 描述中写明下面任一语句，即视为签署 CLA：

  ```
  我已阅读并同意 CLA（CLA.md）
  I have read and agree to the CLA (CLA.md)
  ```

- 维护者会在合并你的首个 PR 时记录你的 **GitHub 用户名与同意日期**；此后的后续贡献自动沿用，无需重复声明。
- 若你不代表自己（例如代表雇主）贡献，请确保已获得相应授权。

## 6. 行为期望

- 友善、就事论事；中文或英文交流均可。
- 提功能建议前，可先看看 README 的「它看不到什么」——本插件有意只显示四个指标，
  实体/区块/内存/CPU 的定位请登到后端上用 `/perfscope` 看，不要把代理侧做成第二个后端面板。
- 维护者不承诺响应时限，也不保证合并任何 PR。
- commit message / PR 描述里不要带任何 AI 工具署名行。提交只保留作者本人的 git 身份。
