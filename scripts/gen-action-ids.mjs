#!/usr/bin/env node
/**
 * gen-action-ids.mjs — ActionId 协议单一数据源生成器
 *
 * 生成两份产物（提交 git，防漂移，与 build-atlas.mjs → TextureAtlas.h 同模式）：
 *   1. android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h   （C++）
 *   2. android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt （Kotlin）
 *
 * 清单：`action-catalog/` 下四份分段清单的顺序拼接（单一事实源）。
 * 约定：actionId 唯一、稳定（存档无关，仅进程内协议；可自由演进）。
 *
 * 用法：node scripts/gen-action-ids.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CPP_HEADER = join(
  ROOT, 'android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h');
const KT_FILE = join(
  ROOT, 'android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt');

/**
 * ActionId 清单（单一事实源）= 四份分段清单的顺序拼接。
 *
 * ## 分段与所有权（W4-00 并行前置批切分，见 docs/parallel-batches-w4/README.md §3.1 项 1）
 * 🔴 三个批次**并行实施**：每批只写自己的清单文件，故本文件与各分段文件
 *    永不产生跨批文本冲突。
 *   - `action-catalog/core.mjs` — 批次 0–21 既有 171 条（段 1000–1734）；
 *     **W4-00 后冻结**，三批禁改
 *   - `action-catalog/w4a.mjs`  — W4-A 独占（1740–1749 / 1750–1759 / 1810–1819 /
 *     1820–1829 / 1850–1854 条件段）
 *   - `action-catalog/w4b.mjs`  — W4-B 独占（1760–1765 / 1766–1769 / 1770–1779 /
 *     1840–1849）
 *   - `action-catalog/w4c.mjs`  — W4-C 独占（1780–1789 / 1790–1799 / 1800–1809 /
 *     1855–1859 条件段）
 *   - `action-catalog/w4d.mjs`  — W4-D 汇流波独占（1830–1839 / 1860–1869 机动段）；
 *     W4-D 为 A/B/C 合入后的**串行**收口波，本文件对它的 import 扩展即其一次性接线
 *
 * ## 结构
 *   { id: number, name: string, desc: string }
 *   id 分配：1000 起为业务动作段；系统动作（生命周期/tick/快照）不走 execute，无需占位。
 *
 * ## 合并规则（W4-00 §3.1 项 2）
 *   本脚本生成的两份产物（`action_ids.h` / `ActionIds.kt`）是**生成物**：
 *   合并冲突一律「重生成」——在合并后的树上跑本脚本再以
 *   `git diff --exit-code` 自证零漂移；**禁止手工解冲突**。
 *   每批提交前必须自证无漂移。
 */
import { CATALOG as CORE_CATALOG } from './action-catalog/core.mjs';
import { CATALOG as W4A_CATALOG } from './action-catalog/w4a.mjs';
import { CATALOG as W4B_CATALOG } from './action-catalog/w4b.mjs';
import { CATALOG as W4C_CATALOG } from './action-catalog/w4c.mjs';
import { CATALOG as W4D_CATALOG } from './action-catalog/w4d.mjs';

/** 分段清单的顺序拼接（顺序即产物顺序；新增分段只在此处追加一行）。 */
const ACTION_CATALOG = [
  ...CORE_CATALOG,
  ...W4A_CATALOG,
  ...W4B_CATALOG,
  ...W4C_CATALOG,
  ...W4D_CATALOG,
];

// ── 清单自检（W4-00 新增；任一条不成立即生成失败，不写出半成品产物）───────────

// 1) id 全局唯一：重复 id 会让两处代码被同一动作号驱动（静默缺陷）
const seenIds = new Map();
for (const a of ACTION_CATALOG) {
  if (seenIds.has(a.id)) {
    throw new Error(
      `ACTION_CATALOG id 重复: ${a.id} 同时用于 ${seenIds.get(a.id)} 与 ${a.name}`);
  }
  seenIds.set(a.id, a.name);
}

// 2) 分段区间两两不相交（预分配表，与 docs/parallel-batches-w4/README.md §6 同源）
const BATCH_SEGMENTS = [
  { batch: 'W4-A', file: 'action-catalog/w4a.mjs', ranges: [[1740, 1749], [1750, 1759], [1810, 1819], [1820, 1829], [1850, 1854]] },
  { batch: 'W4-B', file: 'action-catalog/w4b.mjs', ranges: [[1760, 1765], [1766, 1769], [1770, 1779], [1840, 1849]] },
  { batch: 'W4-C', file: 'action-catalog/w4c.mjs', ranges: [[1780, 1789], [1790, 1799], [1800, 1809], [1855, 1859]] },
  { batch: 'W4-D', file: 'action-catalog/w4d.mjs', ranges: [[1830, 1839], [1860, 1869]] },
];
const flatRanges = [];
for (const seg of BATCH_SEGMENTS) {
  for (const [lo, hi] of seg.ranges) flatRanges.push({ batch: seg.batch, lo, hi });
}
for (let i = 0; i < flatRanges.length; i++) {
  for (let j = i + 1; j < flatRanges.length; j++) {
    const a = flatRanges[i];
    const b = flatRanges[j];
    if (a.lo <= b.hi && b.lo <= a.hi) {
      throw new Error(
        `预分配段重叠: ${a.batch}[${a.lo}-${a.hi}] 与 ${b.batch}[${b.lo}-${b.hi}]`);
    }
  }
}

// 3) 各批次条目必须落在自己的段内（跨批占段即失败）
const batchOf = (id) => flatRanges.find((r) => id >= r.lo && id <= r.hi)?.batch;
const checkOwnership = (entries, batch, file) => {
  for (const a of entries) {
    const owner = batchOf(a.id);
    if (owner === undefined) {
      throw new Error(
        `${file}: ${a.name}(${a.id}) 不在任何预分配段内——请先在 README §6 分配段号`);
    }
    if (owner !== batch) {
      throw new Error(`${file}: ${a.name}(${a.id}) 落在 ${owner} 的段内——跨批占段禁止`);
    }
  }
};
checkOwnership(W4A_CATALOG, 'W4-A', 'action-catalog/w4a.mjs');
checkOwnership(W4B_CATALOG, 'W4-B', 'action-catalog/w4b.mjs');
checkOwnership(W4C_CATALOG, 'W4-C', 'action-catalog/w4c.mjs');
checkOwnership(W4D_CATALOG, 'W4-D', 'action-catalog/w4d.mjs');

// 4) 既有条目不得落进任何批次段（core.mjs 只到 1734，天然成立；防将来误改）
for (const a of CORE_CATALOG) {
  if (batchOf(a.id) !== undefined) {
    throw new Error(
      `action-catalog/core.mjs: ${a.name}(${a.id}) 落在 W4 批次预分配段内`);
  }
}

const MAX_ID = ACTION_CATALOG.reduce((m, a) => Math.max(m, a.id), 0);

function genCpp() {
  const lines = [];
  lines.push('// 由 scripts/gen-action-ids.mjs 生成 — 禁止手改（与 ActionIds.kt 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// ActionId 协议（业务操作码）— 与 Kotlin ActionIds.kt 同步生成');
  lines.push('// 参数/结果一律 JSON 字节（nlohmann/json ↔ kotlinx.serialization）');
  lines.push('// ============================================================');
  lines.push('namespace gamecore {');
  lines.push('');
  lines.push('/// 业务操作码（Kotlin GameCoreBridge.nativeExecute 的 actionId）');
  lines.push('namespace action {');
  for (const a of ACTION_CATALOG) {
    lines.push(`/// ${a.desc}`);
    lines.push(`inline constexpr int32_t ${a.name} = ${a.id};`);
    lines.push('');
  }
  lines.push('/// 全部已注册业务动作号（升序）——供**分派覆盖守卫**枚举使用。');
  lines.push('///');
  lines.push('/// 存在理由（W4-00 并行前置批新增）：`GameCore::execute` 的分发表是连续区间');
  lines.push('/// `else if` 链，历史上发生过「区间写法吞掉动作号」的真实事故（1730 被');
  lines.push('/// 1520–1531 区间吞进库存 handler，返回 "inventory tx action 1730"');
  lines.push('/// UNKNOWN_ACTION）。本数组让 test/dispatch_guard_test.cpp 能对**每一个**');
  lines.push('/// 已注册动作断言"分派可达且落到本域 handler"，把该缺陷类变成可执行断言。');
  lines.push('/// 🔴 本数组由生成器自动产出，禁止手改。');
  const idList = ACTION_CATALOG.map((a) => a.id).sort((x, y) => x - y).join(', ');
  lines.push(`inline constexpr int32_t kAllActionIds[] = {${idList}};`);
  lines.push('');
  lines.push('/** `kAllActionIds` 的元素个数。 */');
  lines.push(`inline constexpr int kAllActionIdsCount = ${ACTION_CATALOG.length};`);
  lines.push('');
  lines.push('}  // namespace action');
  lines.push('');
  lines.push('}  // namespace gamecore');
  lines.push('');
  return lines.join('\n');
}

function genKotlin() {
  const lines = [];
  lines.push('package com.xianxia.sect.core.nativebridge');
  lines.push('');
  lines.push('/**');
  lines.push(' * ActionIds — 业务操作码（与 C++ action_ids.h 由 gen-action-ids.mjs 同源生成）。');
  lines.push(' * 禁止手改：修改清单后运行 `node scripts/gen-action-ids.mjs`。');
  lines.push(' */');
  // ACTION_CATALOG 为空 → 空对象体触发 detekt EmptyClassBlock，
  // 由生成器在清单为空时显式抑制（清单非空后抑制自动消失）
  if (ACTION_CATALOG.length === 0) {
    lines.push('@Suppress("EmptyClassBlock") // 批次 0 骨架：ACTION_CATALOG 为空，随子系统迁移填充');
  }
  lines.push('object ActionIds {');
  for (const a of ACTION_CATALOG) {
    lines.push('    /** ' + a.desc + ' */');
    lines.push(`    const val ${a.name}: Int = ${a.id}`);
    lines.push('');
  }
  lines.push('}');
  lines.push('');
  return lines.join('\n');
}

mkdirSync(dirname(CPP_HEADER), { recursive: true });
mkdirSync(dirname(KT_FILE), { recursive: true });
writeFileSync(CPP_HEADER, genCpp());
writeFileSync(KT_FILE, genKotlin());

console.log(`gen-action-ids: ${ACTION_CATALOG.length} actions (maxId=${MAX_ID})`);
console.log(`  -> ${CPP_HEADER}`);
console.log(`  -> ${KT_FILE}`);
