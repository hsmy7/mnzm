#!/usr/bin/env node
/**
 * gen-templates.mjs — 静态数据模板提取生成器
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

// ── 灵草/种子表提取（HerbDatabase.kt，listOf 字面量）────────────────────
//
// 目标格式（位置参数）：
//   Herb("spiritGrass1", "聚灵草", 1, 1, "grass", "吸收天地灵气而生的灵草，炼丹基础材料")
//   Seed("spiritGrass1Seed", "聚灵草种", 1, 1, 36, 5, "种植后可收获聚灵草")

const HERB_ENTRY_RE = /Herb\("([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*"([^"]+)",\s*"([^"]*)"\)/g;
const SEED_ENTRY_RE = /Seed\("([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+),\s*"([^"]*)"\)/g;

function extractHerbs(source) {
  const entries = [];
  for (const m of source.matchAll(HERB_ENTRY_RE)) {
    entries.push({
      id: m[1], name: m[2], tier: Number(m[3]), rarity: Number(m[4]),
      category: m[5], description: m[6],
    });
  }
  return entries;
}

function extractSeeds(source) {
  const entries = [];
  for (const m of source.matchAll(SEED_ENTRY_RE)) {
    entries.push({
      id: m[1], name: m[2], tier: Number(m[3]), rarity: Number(m[4]),
      growTime: Number(m[5]), yield: Number(m[6]), description: m[7],
    });
  }
  return entries;
}

// ── C++ 灵草/种子表生成 ────────────────────────────────────────────────

function genCppHerbTable(herbs, seeds) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与 Kotlin HerbDatabase 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 灵草/种子静态表（Kotlin HerbDatabase 提取，批次 4c）');
  lines.push('// 字段与 HerbDatabase.Herb/Seed 构造参数一致');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct HerbTemplate {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    int32_t tier = 1;');
  lines.push('    int32_t rarity = 1;');
  lines.push('    std::string category;');
  lines.push('    std::string description;');
  lines.push('};');
  lines.push('');
  lines.push('struct SeedTemplate {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    int32_t tier = 1;');
  lines.push('    int32_t rarity = 1;');
  lines.push('    int32_t growTime = 36;');
  lines.push('    int32_t yield = 1;');
  lines.push('    std::string description;');
  lines.push('};');
  lines.push('');
  lines.push('/// 全部灵草模板');
  lines.push('inline const std::vector<HerbTemplate>& herbTemplates() {');
  lines.push('    static const std::vector<HerbTemplate> kHerbs = {');
  for (const h of herbs) {
    lines.push(`        {"${h.id}", "${h.name}", ${h.tier}, ${h.rarity}, "${h.category}", "${h.description}"},`);
  }
  lines.push('    };');
  lines.push('    return kHerbs;');
  lines.push('}');
  lines.push('');
  lines.push('/// 全部种子模板');
  lines.push('inline const std::vector<SeedTemplate>& seedTemplates() {');
  lines.push('    static const std::vector<SeedTemplate> kSeeds = {');
  for (const s of seeds) {
    lines.push(`        {"${s.id}", "${s.name}", ${s.tier}, ${s.rarity}, ${s.growTime}, ${s.yield}, "${s.description}"},`);
  }
  lines.push('    };');
  lines.push('    return kSeeds;');
  lines.push('}');
  lines.push('');
  lines.push('/// 种子 id → 灵草 id（Kotlin seedToHerbMap：seed.id 去 "Seed" 后缀）');
  lines.push('inline std::string herbIdFromSeedId(const std::string& seedId) {');
  lines.push('    if (seedId.size() > 4 && seedId.compare(seedId.size() - 4, 4, "Seed") == 0) {');
  lines.push('        return seedId.substr(0, seedId.size() - 4);');
  lines.push('    }');
  lines.push('    return {};');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── main ──────────────────────────────────────────────────────────────
// 静态数据单一源——数据权威在 scripts/data/*.json
//（中性源），生成器只读中性源产出 C++ 表 + 测试快照；Kotlin Registry 由
// 单一源守卫测试（StaticDataSingleSourceGuardTest）全量比对兜底防漂移。

const DATA_DIR = join(ROOT, 'scripts/data');

// ── 装备表（中性源）──────────────────────────────────────────────────
const equipJson = JSON.parse(
  readFileSync(join(DATA_DIR, 'equipment_db_sample.json'), 'utf-8'));
const entries = equipJson.entries;
if (!Array.isArray(entries) || entries.length === 0) {
  console.error('错误：中性源 equipment_db_sample.json 无条目');
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

console.log(`装备表生成完成：${entries.length} 条`);
console.log(`  -> ${join(cppDir, 'equipment_db.h')}`);
console.log(`  -> ${join(sampleDir, 'equipment_db_sample.json')}`);

// ── 灵草/种子表（中性源）─────────────────────────────────────────────
const herbJson = JSON.parse(
  readFileSync(join(DATA_DIR, 'herb_db_sample.json'), 'utf-8'));
const herbs = herbJson.herbs;
const seeds = herbJson.seeds;
if (!Array.isArray(herbs) || herbs.length === 0 ||
    !Array.isArray(seeds) || seeds.length === 0) {
  console.error('错误：中性源 herb_db_sample.json 无条目');
  process.exit(1);
}

// 去重校验
const herbIds = new Set();
const herbDupes = herbs.filter((h) => (herbIds.has(h.id) ? true : (herbIds.add(h.id), false)));
if (herbDupes.length > 0) {
  console.error(`错误：重复灵草 id ${herbDupes.map((d) => d.id).join(',')}`);
  process.exit(1);
}
const seedIds = new Set();
const seedDupes = seeds.filter((s) => (seedIds.has(s.id) ? true : (seedIds.add(s.id), false)));
if (seedDupes.length > 0) {
  console.error(`错误：重复种子 id ${seedDupes.map((d) => d.id).join(',')}`);
  process.exit(1);
}

writeFileSync(join(cppDir, 'herb_db.h'), genCppHerbTable(herbs, seeds));
writeFileSync(join(sampleDir, 'herb_db_sample.json'),
  JSON.stringify({ herbCount: herbs.length, seedCount: seeds.length, herbs, seeds }, null, 1));

console.log(`灵草/种子表生成完成：${herbs.length} 灵草 + ${seeds.length} 种子`);
console.log(`  -> ${join(cppDir, 'herb_db.h')}`);
console.log(`  -> ${join(sampleDir, 'herb_db_sample.json')}`);
