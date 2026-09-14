#!/usr/bin/env node
/**
 * gen-beast-material-db.mjs — 妖兽材料静态表生成器
 *
 * 从 Kotlin BeastMaterialDatabase.kt 的 listOf 字面量提取妖兽材料（288 条），
 * 生成两份产物（提交 git 防漂移）：
 *   1. C++ 静态表头  gamecore/include/gamecore/data/beast_material_db.h
 *   2. 提取数据快照 gamecore/test/data/beast_material_db_sample.json（守卫测试锚点）
 *
 * 解析目标格式（BeastMaterialDatabase 形态，位置参数）：
 *   BeastMaterial("tigerHide0", "凡虎皮", 1, 1, "hide", "凡品虎妖的皮毛，蕴含狂暴之力", "🟧", 1.0),
 *
 * 字段：id, name, tier, rarity, category, description, icon, dropWeight
 * 派生字段（与 Kotlin 一致）：
 *   - price = GameConfig.Rarity.get(rarity).materialBasePrice
 *   - materialCategory = category → MaterialCategory.name（hide→BEAST_HIDE 等）
 *
 * 用法：node scripts/gen-beast-material-db.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GAMECORE = join(ROOT, 'android/app/src/main/cpp/gamecore');

// ── 提取 ──────────────────────────────────────────────────────────────

// 位置参数：BeastMaterial("id", "name", tier, rarity, "category", "description", "icon", dropWeight)
const ENTRY_RE = /BeastMaterial\("([^"]+)",\s*"([^"]+)",\s*(\d+),\s*(\d+),\s*"([^"]+)",\s*"([^"]*)",\s*"([^"]*)",\s*([\d.]+)\)/g;

/** 品阶 → materialBasePrice（GameConfig.Rarity 配置，与 Kotlin price 派生一致） */
const MATERIAL_BASE_PRICE = { 1: 400, 2: 1600, 3: 8000, 4: 48000, 5: 336000, 6: 2688000 };

/** category 字符串 → MaterialCategory.name（Kotlin BeastMaterial.materialCategory 映射） */
const CATEGORY_TO_ENUM = {
  hide: 'BEAST_HIDE', bone: 'BEAST_BONE', tooth: 'BEAST_TOOTH', core: 'BEAST_CORE',
  claw: 'BEAST_CLAW', feather: 'BEAST_FEATHER', tail: 'BEAST_TAIL',
  scale: 'BEAST_SCALE', horn: 'BEAST_HORN', shell: 'BEAST_SHELL', blood: 'BEAST_BLOOD',
};

function extract(source) {
  const entries = [];
  for (const m of source.matchAll(ENTRY_RE)) {
    const rarity = Number(m[4]);
    entries.push({
      id: m[1], name: m[2], tier: Number(m[3]), rarity,
      category: m[5], description: m[6], icon: m[7], dropWeight: Number(m[8]),
      price: MATERIAL_BASE_PRICE[rarity] ?? 0,
      materialCategory: CATEGORY_TO_ENUM[m[5]] ?? 'BEAST_HIDE',
    });
  }
  return entries;
}

// ── C++ 表生成 ────────────────────────────────────────────────────────

function genCppTable(entries) {
  const lines = [];
  lines.push('// 由 scripts/gen-beast-material-db.mjs 生成 — 禁止手改（与 Kotlin BeastMaterialDatabase 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 妖兽材料静态表（Kotlin BeastMaterialDatabase 提取，批次 2 剩余）');
  lines.push('// 字段与 BeastMaterial 构造参数一致；price/materialCategory 为派生值');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct BeastMaterialTemplate {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    int32_t tier = 1;');
  lines.push('    int32_t rarity = 1;');
  lines.push('    std::string category;');
  lines.push('    std::string description;');
  lines.push('    std::string icon;');
  lines.push('    double dropWeight = 1.0;');
  lines.push('    int32_t price = 0;');
  lines.push('    std::string materialCategory;');
  lines.push('};');
  lines.push('');
  lines.push('/// 全部妖兽材料模板');
  lines.push('inline const std::vector<BeastMaterialTemplate>& beastMaterialTemplates() {');
  lines.push('    static const std::vector<BeastMaterialTemplate> kTemplates = {');
  for (const e of entries) {
    lines.push(`        {"${e.id}", "${e.name}", ${e.tier}, ${e.rarity}, "${e.category}", ` +
      `"${e.description}", "${e.icon}", ${e.dropWeight}, ${e.price}, "${e.materialCategory}"},`);
  }
  lines.push('    };');
  lines.push('    return kTemplates;');
  lines.push('}');
  lines.push('');
  lines.push('/// 按 id 查询妖兽材料');
  lines.push('inline const BeastMaterialTemplate* beastMaterialById(const std::string& id) {');
  lines.push('    for (const auto& m : beastMaterialTemplates()) {');
  lines.push('        if (m.id == id) return &m;');
  lines.push('    }');
  lines.push('    return nullptr;');
  lines.push('}');
  lines.push('');
  lines.push('/// 按妖兽类型查询（Kotlin getMaterialsByBeastType：接受中文妖兽名如"虎妖"，');
  lines.push('/// 也接受英文前缀如 "tiger"；按 id 前缀匹配，如 tigerHide/tigerBlood/tigerTooth/tigerCore）');
  lines.push('inline std::vector<const BeastMaterialTemplate*> beastMaterialsByBeastType(');
  lines.push('    const std::string& beastType) {');
  lines.push('    std::string prefix;');
  lines.push('    if (beastType == "虎妖") prefix = "tiger";');
  lines.push('    else if (beastType == "狼妖") prefix = "wolf";');
  lines.push('    else if (beastType == "蛇妖") prefix = "snake";');
  lines.push('    else if (beastType == "熊妖") prefix = "bear";');
  lines.push('    else if (beastType == "鹰妖") prefix = "eagle";');
  lines.push('    else if (beastType == "狐妖") prefix = "fox";');
  lines.push('    else if (beastType == "龙妖") prefix = "dragon";');
  lines.push('    else if (beastType == "龟妖") prefix = "turtle";');
  lines.push('    else prefix = beastType;  // 已传英文前缀');
  lines.push('    std::vector<const BeastMaterialTemplate*> out;');
  lines.push('    for (const auto& m : beastMaterialTemplates()) {');
  lines.push('        if (m.id.rfind(prefix, 0) == 0) {');
  lines.push('            out.push_back(&m);');
  lines.push('        }');
  lines.push('    }');
  lines.push('    return out;');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── main ──────────────────────────────────────────────────────────────
// 静态数据单一源——数据权威在 scripts/data/*.json
//（中性源），生成器只读中性源；Kotlin Registry 由单一源守卫测试全量比对兜底。

const DATA_DIR = join(ROOT, 'scripts/data');

const beastJson = JSON.parse(
  readFileSync(join(DATA_DIR, 'beast_material_db_sample.json'), 'utf-8'));
const entries = beastJson.entries;
if (!Array.isArray(entries) || entries.length === 0) {
  console.error('错误：中性源 beast_material_db_sample.json 无条目');
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
writeFileSync(join(cppDir, 'beast_material_db.h'), genCppTable(entries));

// 全量快照 JSON（守卫测试锚点）——输出到 core/engine 测试资源（classpath 可加载）
const sampleDir = join(ROOT, 'android/core/engine/src/test/resources/templates');
mkdirSync(sampleDir, { recursive: true });
writeFileSync(join(sampleDir, 'beast_material_db_sample.json'),
  JSON.stringify({ count: entries.length, entries }, null, 1));

console.log(`妖兽材料表提取完成：${entries.length} 条`);
console.log(`  -> ${join(cppDir, 'beast_material_db.h')}`);
console.log(`  -> ${join(sampleDir, 'beast_material_db_sample.json')}`);
