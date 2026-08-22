#!/usr/bin/env node
/**
 * gen-action-ids.mjs — ActionId 协议单一数据源生成器
 *
 * 生成两份产物（提交 git，防漂移，与 build-atlas.mjs → TextureAtlas.h 同模式）：
 *   1. android/app/src/main/cpp/gamecore/include/gamecore/action_ids.h   （C++）
 *   2. android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/ActionIds.kt （Kotlin）
 *
 * 清单：本脚本内嵌 ACTION_CATALOG（批次 0 为空，随子系统迁移逐步填充）。
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
 * ActionId 清单（单一事实源）
 * 结构：{ id: number, name: string, desc: string }
 * id 分配：1000 起为业务动作段；系统动作（生命周期/tick/快照）不走 execute，无需占位。
 * 批次 0 为空骨架；批次 1+ 按子系统迁移顺序追加（每批新增动作 id 连续递增）。
 */
const ACTION_CATALOG = [
  // 示例（仅演示格式，批次 1 前请勿实现）：
  // { id: 1000, name: 'PING', desc: '连通性自检（演示用）' },
];

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
