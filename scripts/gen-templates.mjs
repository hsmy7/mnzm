#!/usr/bin/env node
/**
 * gen-templates.mjs — 静态数据模板提取生成器（Kotlin→C++ 迁移批次 2）
 *
 * 从 Kotlin Registry 源码（EquipmentDatabase.kt 的 Map 字面量）提取模板数据，
 * 生成两份产物（提交 git 防漂移）：
 *   1. C++ 静态表头  gamecore/include/gamecore/data/equipment_db.h
 *   2. 提取数据快照 gamecore/test/data/equipment_db_sample.json（守卫测试锚点）
 *
 * 解析目标格式（EquipmentDatabase 形态）：
 *   "ironSword" to EquipmentTemplate("ironSword", "精铁剑", EquipmentSlot.WEAPON, 1,
 *                                    physicalAttack = 15, critChance = 0.03,
 *                                    description = "...", price = 4000),
 *
 * 用法：node scripts/gen-templates.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GAMECORE = join(ROOT, 'android/app/src/main/cpp/gamecore');

// ── 装备表提取（EquipmentDatabase.kt）─────────────────────────────────

const ENTRY_RE = /"(\w+)"\s+to\s+EquipmentTemplate\(\s*"([^"]*)",\s*"([^"]*)",\s*EquipmentSlot\.(\w+),\s*(\d+),([\s\S]*?)\)/g;
const FIELD_RE = /(\w+)\s*=\s*("(?:\\.|[^"])*"|-?\d+\.?\d*|-?\d+)/g;

/** 提取装备模板（字段默认值与 Kotlin EquipmentTemplate 构造一致） */
function extractEquipment(source) {
  const entries = [];
  for (const m of source.matchAll(ENTRY_RE)) {
    const entry = {
      id: m[2], name: m[3], slot: m[4], rarity: Number(m[5]),
      physicalAttack: 0, magicAttack: 0, physicalDefense: 0, magicDefense: 0,
      speed: 0, hp: 0, mp: 0, critChance: 0.0, description: '', price: 0,
    };
    // 命名参数区在组 6
    for (const f of m[6].matchAll(FIELD_RE)) {
      const name = f[1];
      const raw = f[2];
      if (!(name in entry)) continue;
      if (raw.startsWith('"')) {
        entry[name] = raw.slice(1, -1);
      } else if (raw.includes('.')) {
        entry[name] = Number(raw);
      } else {
        entry[name] = Number(raw);
      }
    }
    entries.push(entry);
  }
  return entries;
}

// ── C++ 表生成 ────────────────────────────────────────────────────────

function genCppTable(entries) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与 Kotlin EquipmentDatabase 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 装备模板静态表（Kotlin EquipmentDatabase 提取，批次 2）');
  lines.push('// 字段与 EquipmentTemplate 构造参数一致；slot 为 EquipmentSlot.name');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct EquipmentTemplate {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    std::string slot;');
  lines.push('    int32_t rarity = 0;');
  lines.push('    int32_t physicalAttack = 0;');
  lines.push('    int32_t magicAttack = 0;');
  lines.push('    int32_t physicalDefense = 0;');
  lines.push('    int32_t magicDefense = 0;');
  lines.push('    int32_t speed = 0;');
  lines.push('    int32_t hp = 0;');
  lines.push('    int32_t mp = 0;');
  lines.push('    double critChance = 0.0;');
  lines.push('    std::string description;');
  lines.push('    int32_t price = 0;');
  lines.push('};');
  lines.push('');
  lines.push('/// 全部装备模板（weapons + armors + boots + accessories）');
  lines.push('inline const std::vector<EquipmentTemplate>& equipmentTemplates() {');
  lines.push('    static const std::vector<EquipmentTemplate> kTemplates = {');
  for (const e of entries) {
    lines.push('        {');
    lines.push(`            "${e.id}", "${e.name}", "${e.slot}", ${e.rarity},`);
    lines.push(`            ${e.physicalAttack}, ${e.magicAttack},`);
    lines.push(`            ${e.physicalDefense}, ${e.magicDefense},`);
    lines.push(`            ${e.speed}, ${e.hp}, ${e.mp},`);
    lines.push(`            ${e.critChance}, "${e.description}", ${e.price},`);
    lines.push('        },');
  }
  lines.push('    };');
  lines.push('    return kTemplates;');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── main ──────────────────────────────────────────────────────────────

const equipSrcPath = join(
  ROOT, 'android/core/domain/src/main/java/com/xianxia/sect/core/registry/EquipmentDatabase.kt');
const source = readFileSync(equipSrcPath, 'utf-8');

const entries = extractEquipment(source);
if (entries.length === 0) {
  console.error('错误：装备表提取为空（正则可能不匹配当前源码格式）');
  process.exit(1);
}

// 去重校验
const seen = new Set();
const dupes = entries.filter((e) => (seen.has(e.id) ? true : (seen.add(e.id), false)));
if (dupes.length > 0) {
  console.error(`错误：重复 id ${dupes.map((d) => d.id).join(',')}`);
  process.exit(1);
}

const cppDir = join(GAMECORE, 'include/gamecore/data');
mkdirSync(cppDir, { recursive: true });
writeFileSync(join(cppDir, 'equipment_db.h'), genCppTable(entries));

// 抽样快照 JSON（守卫测试锚点）——输出到 core/engine 测试资源（classpath 可加载）
const sampleDir = join(ROOT, 'android/core/engine/src/test/resources/templates');
mkdirSync(sampleDir, { recursive: true });
writeFileSync(join(sampleDir, 'equipment_db_sample.json'),
  JSON.stringify({ count: entries.length, entries }, null, 1));

console.log(`装备表提取完成：${entries.length} 条`);
console.log(`  -> ${join(cppDir, 'equipment_db.h')}`);
console.log(`  -> ${join(sampleDir, 'equipment_db_sample.json')}`);
