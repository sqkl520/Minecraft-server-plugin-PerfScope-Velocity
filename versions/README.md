# versions/ —— 本地构建存档

本目录为**本地**编译产物备份，JAR 不纳入 git（`.gitignore` 排除，仅本 `README.md` 例外入库）。
正式发布请走 GitHub Releases。

> **与后端仓库同一个口径**：`/versions/*` + `!/versions/README.md`。必须先排除目录**内容**
> 而不是目录本身，否则 git 不会进入该目录，`!` 例外失效。参考
> [`PerfScope` 的 `versions/README.md`](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope/blob/main/versions/README.md)。

## 当前版本

版本号与后端仓库**保持同步**（同一个 `-SNAPSHOT-fixN` 补丁线），当前是
`1.4.5-SNAPSHOT-fix4`。

所有 GitHub Release 一律标为 **Pre-release**，因此 `/releases/latest` 不指向任何东西——
请从 [Releases 列表页](https://github.com/sqkl520/Minecraft-server-plugin-PerfScope-Velocity/releases)
取最上面一条。

| 版本 | 文件 | 说明 |
|---|---|---|
| **1.4.5-SNAPSHOT-fix4** | `PerfScope-Velocity-1.4.5-SNAPSHOT-fix4.jar` | **推荐使用**。逐后端降级：一台后端的注册信息有缺陷不再抹掉整张面板。 |
| 1.4.5-SNAPSHOT-fix3 | `PerfScope-Velocity-1.4.5-SNAPSHOT-fix3.jar` | 阈值口径、过期判定、面板对齐。 |
| 1.4.5-SNAPSHOT-fix2 | `PerfScope-Velocity-1.4.5-SNAPSHOT-fix2.jar` | 首个发布：跨服摘要接收与 `一屏看全网`。 |

## 拿哪个 jar

`mvn clean package` 会产出三个文件：

| 文件 | 用途 |
|---|---|
| `PerfScope-Velocity-<version>.jar` | **装这个** |
| `PerfScope-Velocity-<version>-shaded.jar` | 同上（shade 后的副本） |
| `original-PerfScope-Velocity-<version>.jar` | shade 前的原始产物，**不要装** |

Gson 被 shade 并 relocate 到 `cn.perfscope.velocity.shaded.gson`；`velocity-api` 是
`provided`，运行期由代理提供，**绝不能打进 jar**（打进去会造成类加载冲突）。
`tools/check-velocity-descriptor.mjs` 会校验这两点，构建后请跑一遍。

## 归档怎么做

```bash
mvn clean package -DskipTests
cp target/PerfScope-Velocity-<version>.jar versions/
sha256sum versions/PerfScope-Velocity-<version>.jar
```

**归档的是 GitHub Release 附件的原始字节**，不是重新构建的产物——两者在 CI（Linux）与
本机（Windows）之间会有 `pom.properties` 的换行符差异（LF vs CRLF），整包 sha 因此不同而
内容等价。后端仓库有 `tools/verify-release-artifact.mjs` 专门处理这件事；本仓库的
`versions/` 目前只做本地备份，不做逐字节归档校验。
