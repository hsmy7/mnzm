#!/usr/bin/env node
/**
 * gen-manual-db.mjs — 功法静态表生成器（Kotlin→C++ 迁移批次 2 剩余）
 *
 * 从 Android assets 数据文件 manuals.json（ManualDatabase 数据源，含
 * attack/defense/support/mind 四类功法）提取 540 条功法模板，生成两份产物：
 *   1. C++ 静态表头  gamecore/include/gamecore/data/manual_db.h
 *   2. 提取数据快照 gamecore/test/data/manual_db_sample.json（守卫测试锚点）
 *
 * 字段与 Kotlin ManualDatabase.ManualTemplate / ManualJsonDataSync 一致。
 *
 * 用法：node scripts/gen-manual-db.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GAMECORE = join(ROOT, 'android/app/src/main/cpp/gamecore');
const SRC_JSON = join(ROOT, 'android/app/src/main/assets/data/manuals.json');

const MANUAL_TYPES = ['attack', 'defense', 'support', 'mind'];

function loadManuals() {
  const root = JSON.parse(readFileSync(SRC_JSON, 'utf-8'));
  const out = [];
  for (const type of MANUAL_TYPES) {
    const list = root[`${type}Manuals`];
    if (!Array.isArray(list)) {
      console.error(`错误：manuals.json 缺少 ${type}Manuals 数组`);
      process.exit(1);
    }
    for (const m of list) {
      out.push({
        id: m.id, name: m.name, type: m.type, rarity: Number(m.rarity),
        description: m.description ?? '',
        stats: m.stats ?? {},
        skillName: m.skillName ?? null,
        skillDescription: m.skillDescription ?? null,
        skillType: m.skillType ?? 'attack',
        skillDamageType: m.skillDamageType ?? 'physical',
        skillHits: Number(m.skillHits ?? 1),
        skillDamageMultiplier: Number(m.skillDamageMultiplier ?? 1.0),
        skillCooldown: Number(m.skillCooldown ?? 3),
        skillMpCost: Number(m.skillMpCost ?? 10),
        skillHealPercent: Number(m.skillHealPercent ?? 0.0),
        skillHealFixed: Number(m.skillHealFixed ?? 0),
        skillHealType: m.skillHealType ?? 'hp',
        skillBuffType: m.skillBuffType ?? null,
        skillBuffValue: Number(m.skillBuffValue ?? 0.0),
        skillBuffDuration: Number(m.skillBuffDuration ?? 0),
        skillBuffs: (m.skillBuffs ?? []).map((b) => ({
          type: b.type, value: Number(b.value), duration: Number(b.duration),
        })),
        price: Number(m.price ?? 0),
        minRealm: Number(m.minRealm ?? 9),
        skillIsAoe: Boolean(m.skillIsAoe ?? false),
        skillTargetScope: m.skillTargetScope ?? 'self',
        skillShieldPercent: Number(m.skillShieldPercent ?? 0.0),
        skillTurnAdvancePercent: Number(m.skillTurnAdvancePercent ?? 0.0),
        skillDamageSharePercent: Number(m.skillDamageSharePercent ?? 0.0),
        skillDamageLinkPercent: Number(m.skillDamageLinkPercent ?? 0.0),
      });
    }
  }
  return out;
}

// ── C++ 表生成 ────────────────────────────────────────────────────────

function cppStr(s) {
  return '"' + (s ?? '').replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n') + '"';
}

function genCppTable(entries) {
  const lines = [];
  lines.push('// 由 scripts/gen-manual-db.mjs 生成 — 禁止手改（与 assets/data/manuals.json 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <map>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 功法静态表（Kotlin ManualDatabase 数据源 manuals.json 提取，批次 2 剩余）');
  lines.push('// 字段与 ManualTemplate 一致；type 为 ManualType.name');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct ManualBuffInfo {');
  lines.push('    std::string type;');
  lines.push('    double value = 0.0;');
  lines.push('    int32_t duration = 0;');
  lines.push('};');
  lines.push('');
  lines.push('struct ManualTemplate {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    std::string type;');
  lines.push('    int32_t rarity = 1;');
  lines.push('    std::string description;');
  lines.push('    std::map<std::string, int32_t> stats;');
  lines.push('    std::string skillName;');
  lines.push('    std::string skillDescription;');
  lines.push('    std::string skillType = "attack";');
  lines.push('    std::string skillDamageType = "physical";');
  lines.push('    int32_t skillHits = 1;');
  lines.push('    double skillDamageMultiplier = 1.0;');
  lines.push('    int32_t skillCooldown = 3;');
  lines.push('    int32_t skillMpCost = 10;');
  lines.push('    double skillHealPercent = 0.0;');
  lines.push('    int32_t skillHealFixed = 0;');
  lines.push('    std::string skillHealType = "hp";');
  lines.push('    std::string skillBuffType;');
  lines.push('    double skillBuffValue = 0.0;');
  lines.push('    int32_t skillBuffDuration = 0;');
  lines.push('    std::vector<ManualBuffInfo> skillBuffs;');
  lines.push('    int32_t price = 0;');
  lines.push('    int32_t minRealm = 9;');
  lines.push('    bool skillIsAoe = false;');
  lines.push('    std::string skillTargetScope = "self";');
  lines.push('    double skillShieldPercent = 0.0;');
  lines.push('    double skillTurnAdvancePercent = 0.0;');
  lines.push('    double skillDamageSharePercent = 0.0;');
  lines.push('    double skillDamageLinkPercent = 0.0;');
  lines.push('};');
  lines.push('');
  lines.push('/// 全部功法模板（attack + defense + support + mind）');
  lines.push('inline const std::vector<ManualTemplate>& manualTemplates() {');
  lines.push('    static const std::vector<ManualTemplate> kTemplates = {');
  for (const e of entries) {
    const stats = Object.entries(e.stats).map(([k, v]) => `{${cppStr(k)}, ${Number(v)}}`).join(', ');
    const buffs = e.skillBuffs.map((b) => `{${cppStr(b.type)}, ${b.value}, ${b.duration}}`).join(', ');
    lines.push('        {');
    lines.push(`            ${cppStr(e.id)}, ${cppStr(e.name)}, ${cppStr(e.type)}, ${e.rarity},`);
    lines.push(`            ${cppStr(e.description)},`);
    lines.push(`            {${stats}},`);
    lines.push(`            ${cppStr(e.skillName)}, ${cppStr(e.skillDescription)},`);
    lines.push(`            ${cppStr(e.skillType)}, ${cppStr(e.skillDamageType)},`);
    lines.push(`            ${e.skillHits}, ${e.skillDamageMultiplier}, ${e.skillCooldown}, ${e.skillMpCost},`);
    lines.push(`            ${e.skillHealPercent}, ${e.skillHealFixed}, ${cppStr(e.skillHealType)},`);
    lines.push(`            ${cppStr(e.skillBuffType)}, ${e.skillBuffValue}, ${e.skillBuffDuration},`);
    lines.push(`            {${buffs}},`);
    lines.push(`            ${e.price}, ${e.minRealm}, ${e.skillIsAoe ? 'true' : 'false'},`);
    lines.push(`            ${cppStr(e.skillTargetScope)}, ${e.skillShieldPercent}, ${e.skillTurnAdvancePercent},`);
    lines.push(`            ${e.skillDamageSharePercent}, ${e.skillDamageLinkPercent},`);
    lines.push('        },');
  }
  lines.push('    };');
  lines.push('    return kTemplates;');
  lines.push('}');
  lines.push('');
  lines.push('/// 按 id 查询功法');
  lines.push('inline const ManualTemplate* manualById(const std::string& id) {');
  lines.push('    for (const auto& m : manualTemplates()) {');
  lines.push('        if (m.id == id) return &m;');
  lines.push('    }');
  lines.push('    return nullptr;');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── main ──────────────────────────────────────────────────────────────

const entries = loadManuals();
if (entries.length === 0) {
  console.error('错误：功法表提取为空（manuals.json 不存在或格式不符）');
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
writeFileSync(join(cppDir, 'manual_db.h'), genCppTable(entries));

// 全量快照 JSON（守卫测试锚点）——输出到 core/engine 测试资源（classpath 可加载）
const sampleDir = join(ROOT, 'android/core/engine/src/test/resources/templates');
mkdirSync(sampleDir, { recursive: true });
writeFileSync(join(sampleDir, 'manual_db_sample.json'),
  JSON.stringify({ count: entries.length, entries }, null, 1));

console.log(`功法表提取完成：${entries.length} 条`);
console.log(`  -> ${join(cppDir, 'manual_db.h')}`);
console.log(`  -> ${join(sampleDir, 'manual_db_sample.json')}`);
