#!/usr/bin/env node
/**
 * check-velocity-descriptor.mjs -- jar 里的 velocity-plugin.json 必须是已解析的。
 *
 * 背景（本门禁存在的原因，一次真实踩坑）：
 *
 *   Velocity 的注解处理器在**编译期**生成 velocity-plugin.json，它把 @Plugin 注解里的
 *   version 字符串原样写进去，**不经过 Maven 的 resource filtering**。于是第一次打包出来
 *   的描述符里是字面量 "${project.version}"：
 *
 *       {"id":"perfscope","version":"${project.version}", ...}
 *
 *   这不是无害的。Velocity 会把它当作插件版本显示在 /velocity plugins 里，
 *   排错时核对版本也读它——于是"我装的到底是哪一版"永远答不上来。
 *   更糟的是它不会报错：插件照常加载，只是版本那一栏是个占位符。
 *
 *   而主仓的 CI 里已经有一条同型检查（"Assert packaged plugin.yml version is resolved"，
 *   检查 ${ 没有被替换），说明这个坑在本项目出现过一次了。Velocity 侧是第二次。
 *
 * 判定口径：
 *   - 从 pom.xml 取本项目的 <version>（锚在 <artifactId> 之后，避免误取依赖版本）。
 *   - 解 jar 里的 velocity-plugin.json，逐字段检查：
 *       id 必须存在且非空
 *       main 必须存在且非空
 *       version 必须等于 pom 版本，且**不含** `${`
 *   - 顺带检查 relocate：com/google/gson/ 条目必须为 0（不 relocate 会与代理上其它插件撞类），
 *     且 com/velocitypowered/ 条目必须为 0（API 必须在运行期由代理提供）。
 *
 * 用法：
 *   node tools/check-velocity-descriptor.mjs
 *   node tools/check-velocity-descriptor.mjs --jar target/xxx.jar
 *   node tools/check-velocity-descriptor.mjs --json
 *
 * 退出码：0 = PASS，1 = 失败，2 = 用法/路径错误
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const argOf = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : dflt;
};
const ROOT = path.resolve(argOf('--root', path.join(HERE, '..')));
const AS_JSON = argv.includes('--json');

const problems = [];
const pass = [];

function ok(label, detail) { pass.push({ label, detail }); }
function fail(label, detail) { problems.push({ label, detail }); }

// ---- 1. 取 pom 版本 ----
const pomPath = path.join(ROOT, 'pom.xml');
if (!fs.existsSync(pomPath)) {
  console.error(`check-velocity-descriptor: no pom.xml at ${pomPath}`);
  process.exit(2);
}
const pom = fs.readFileSync(pomPath, 'utf8');
const ownVersion = (() => {
  const m = pom.match(/<artifactId>\s*PerfScope-Velocity\s*<\/artifactId>\s*\n\s*<version>\s*([^<]+?)\s*<\/version>/);
  if (m) return m[1];
  const m2 = pom.match(/<version>\s*([^<]+?)\s*<\/version>/);
  return m2 ? m2[1] : null;
})();
if (!ownVersion) {
  console.error('check-velocity-descriptor: cannot read <version> from pom.xml');
  process.exit(2);
}

// ---- 2. 找 jar ----
let jar = argOf('--jar', null);
if (!jar) {
  const targetDir = path.join(ROOT, 'target');
  const candidates = fs.existsSync(targetDir)
    ? fs.readdirSync(targetDir).filter((f) => /^PerfScope-Velocity-.*\.jar$/.test(f) && !f.startsWith('original-'))
    : [];
  if (candidates.length === 0) {
    console.error(`check-velocity-descriptor: no PerfScope-Velocity-*.jar under ${targetDir} (run mvn package first)`);
    process.exit(2);
  }
  jar = path.join(targetDir, candidates.sort().pop());
} else if (!path.isAbsolute(jar)) {
  jar = path.resolve(ROOT, jar);
}
if (!fs.existsSync(jar)) {
  console.error(`check-velocity-descriptor: jar not found: ${jar}`);
  process.exit(2);
}

const unzip = (entry) => {
  try {
    return execFileSync('unzip', ['-p', jar, entry], { encoding: 'utf8' });
  } catch {
    return null;
  }
};
const listEntries = () => {
  const out = execFileSync('unzip', ['-Z1', jar], { encoding: 'utf8' });
  return out.split('\n').map((s) => s.trim()).filter(Boolean);
};

// ---- 3. 描述符 ----
const raw = unzip('velocity-plugin.json');
if (raw == null) {
  fail('velocity-plugin.json present', 'the jar has no velocity-plugin.json -- Velocity will not load this plugin');
} else {
  ok('velocity-plugin.json present', 'found');

  // 占位符没被替换是这个门禁的核心靶点
  if (raw.includes('${')) {
    const m = raw.match(/"version"\s*:\s*"([^"]*)"/);
    fail('version is resolved',
      `descriptor still contains a Maven placeholder: version=${m ? m[1] : '(not found)'}`);
  } else {
    ok('version is resolved', 'no ${...} placeholder in the descriptor');
  }

  let desc = null;
  try {
    desc = JSON.parse(raw);
  } catch (e) {
    fail('descriptor is valid JSON', String(e.message));
  }
  if (desc) {
    if (!desc.id) fail('id present', 'descriptor has no "id"');
    else ok('id present', desc.id);

    if (!desc.main) fail('main present', 'descriptor has no "main" class');
    else ok('main present', desc.main);

    if (desc.version !== ownVersion) {
      fail('version matches pom', `descriptor says "${desc.version}" but pom.xml says "${ownVersion}"`);
    } else {
      ok('version matches pom', desc.version);
    }
  }
}

// ---- 4. relocate 与依赖边界 ----
const entries = listEntries();
const rawGson = entries.filter((e) => e.startsWith('com/google/gson/')).length;
const shadedGson = entries.filter((e) => e.startsWith('cn/perfscope/velocity/shaded/gson/')).length;
const velocityApi = entries.filter((e) => e.startsWith('com/velocitypowered/')).length;

if (rawGson > 0) {
  fail('gson is relocated', `${rawGson} raw com/google/gson/ entries -- will clash with other plugins on the proxy`);
} else {
  ok('gson is relocated', `${shadedGson} shaded entries, 0 raw`);
}
if (velocityApi > 0) {
  fail('velocity-api is not bundled', `${velocityApi} com/velocitypowered/ entries -- the proxy provides this API at runtime`);
} else {
  ok('velocity-api is not bundled', '0 entries');
}

// ---- 报告 ----
const okay = problems.length === 0;
if (AS_JSON) {
  console.log(JSON.stringify({ ok: okay, jar, pomVersion: ownVersion, pass, problems }, null, 2));
} else {
  console.log('PerfScope-Velocity descriptor check');
  console.log(`  jar  : ${jar}`);
  console.log(`  pom  : ${ownVersion}`);
  console.log('');
  for (const p of pass) console.log(`  ok    ${p.label.padEnd(28)} ${p.detail}`);
  for (const p of problems) console.log(`  FAIL  ${p.label.padEnd(28)} ${p.detail}`);
  console.log('');
  console.log(okay ? 'VELOCITY DESCRIPTOR CHECK: PASS' : `VELOCITY DESCRIPTOR CHECK: FAIL (${problems.length})`);
}
process.exit(okay ? 0 : 1);
