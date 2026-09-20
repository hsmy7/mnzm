#!/usr/bin/env node
/**
 * check-jni-count.mjs — JNI 面计数门禁（b02 发现 6 根治；B17-min 交付物）
 *
 * 方案 §「CI 与度量执法」第 3 条「JNI 面计数不增」的执法物：
 * W5 期间 external fun 从 74 涨到 91（+17，全部有人工豁免登记但无工具拦截），
 * 「每批 +1 无人察觉地累积」最终会把 G3/G4 的病根养回来——本脚本把人工纪律
 * 变成硬闸。
 *
 * 规则（三条，全部结构性）：
 *   ① 计数不增：两个生产桥文件的 `external fun` 总数不得超过基线
 *      （scripts/jni-count.baseline.json）；
 *   ② 面不扩散：生产源码（android 各模块 src/main）中 `external fun` 只允许出现在
 *      两个在册桥文件里——新建第三个桥文件（或把 JNI 声明挪进业务类）立即红，
 *      防止「计数不变、面变大」的绕行；
 *   ③ 收缩提示：计数低于基线时打印建议（收缩合法，随 PR 同步降基线即可），
 *      不失败。
 *
 * 豁免方式 = 在同一 PR 显式修改基线文件（git diff 可见、评审可拦截），并在
 * PR 描述/CHANGELOG 附豁免理由（先例：nativeFpDeterminismProbe / nativeSetDirtyExportProtobuf /
 * nativeSetGameData）。本脚本不提供 --force 类跳过通道。
 *
 * 用法：
 *   node scripts/check-jni-count.mjs            # 本地/CI 同一入口
 *   node scripts/check-jni-count.mjs --update   # 按当前实数重写基线（豁免 PR 用；
 *                                               # 仍会因②的面扩散检查先行失败而拒绝）
 */

import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const BRIDGES = [
  'android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/GameCoreBridge.kt',
  'android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/NativeBridge.kt',
];
const BASELINE_PATH = 'scripts/jni-count.baseline.json';
/** 生产源码扫描根（kotlin/java 主源集；测试源集与生成物不在面上） */
const SCAN_ROOTS = BRIDGES.map((p) => join(repoRoot, p.split('/src/main/')[0], 'src/main'));
/** 声明匹配：行首缩进 + external fun + 空白 + 标识符（KDoc/注释行天然不命中） */
const EXTERNAL_FUN_RE = /^[ \t]*external[ \t]+fun[ \t]+[A-Za-z_]/gm;

function countExternalFuns(source) {
  const matches = source.match(EXTERNAL_FUN_RE);
  return matches ? matches.length : 0;
}

function listKotlinFiles(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'build') continue; // 生成物/产物目录不入面
      listKotlinFiles(full, acc);
    } else if (name.endsWith('.kt')) {
      acc.push(full);
    }
  }
  return acc;
}

const updateMode = process.argv.includes('--update');
const counts = {};
for (const bridge of BRIDGES) {
  counts[bridge] = countExternalFuns(readFileSync(join(repoRoot, bridge), 'utf8'));
}
const total = Object.values(counts).reduce((a, b) => a + b, 0);

// ② 面不扩散：生产主源集里 external fun 只允许出现在两个在册桥文件
const allowedAbs = new Set(BRIDGES.map((b) => join(repoRoot, b)));
const offenders = [];
for (const root of SCAN_ROOTS) {
  for (const file of listKotlinFiles(root)) {
    if (allowedAbs.has(file)) continue;
    const src = readFileSync(file, 'utf8');
    EXTERNAL_FUN_RE.lastIndex = 0;
    if (EXTERNAL_FUN_RE.test(src)) offenders.push(relative(repoRoot, file));
  }
}
if (offenders.length > 0) {
  console.error(`✗ JNI 面扩散：以下生产源码文件含 external fun 声明（只允许在册双桥）：`);
  for (const f of offenders) console.error(`    - ${f}`);
  console.error('  新增 JNI 端口请落在 GameCoreBridge.kt / NativeBridge.kt 并同步基线，勿新建桥文件。');
  process.exit(1);
}

const baselinePath = join(repoRoot, BASELINE_PATH);
const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));

if (updateMode) {
  const next = {
    ...baseline,
    perFile: Object.fromEntries(Object.entries(counts).map(([k, v]) => [relative(repoRoot, k), v])),
    total,
    updatedAt: new Date().toISOString().slice(0, 10),
  };
  writeFileSync(baselinePath, `${JSON.stringify(next, null, 2)}\n`);
  console.log(`✓ 基线已按当前实数重写：total=${total}`);
  process.exit(0);
}

let failed = false;
for (const [bridge, count] of Object.entries(counts)) {
  const base = baseline.perFile[relative(repoRoot, bridge)];
  if (count > base) {
    console.error(`✗ ${relative(repoRoot, bridge)}：${count} > 基线 ${base}（+${count - base}）`);
    failed = true;
  } else if (count < base) {
    console.log(`↘ ${relative(repoRoot, bridge)}：${count} < 基线 ${base}（收缩合法，建议随本 PR 降基线）`);
  }
}
if (total > baseline.total) {
  console.error(`✗ 生产 JNI 面总数 ${total} > 基线 ${baseline.total}（G3/G4 收敛方向红线）`);
  failed = true;
}
if (failed) {
  console.error(`
豁免流程（无跳过通道）：
  1. 同一 PR 修改 ${BASELINE_PATH}（可用 node scripts/check-jni-count.mjs --update 生成）；
  2. PR 描述/CHANGELOG 附豁免理由（先例：nativeSetGameData 的"单端口批量注入"论证）；
  3. 评审按「能复用既有端口吗 / 是否每帧路径 / 是否有回滚臂」三问拦截。`);
  process.exit(1);
}
console.log(`✓ JNI 面计数在基线内：total=${total}/${baseline.total}，双桥无扩散。`);
