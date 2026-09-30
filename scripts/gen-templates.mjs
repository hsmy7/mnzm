#!/usr/bin/env node
/**
 * gen-templates.mjs — 静态数据模板生成器（装备重构 B3 版）
 *
 * 数据权威 = scripts/data/*.json **中性源**；本生成器只读中性源产出
 * C++ 静态表头 + Kotlin/引擎测试快照（全部提交 git 防漂移，G0 门禁 =
 * 重跑零差异）。Kotlin 侧声明表由单一源守卫测试逐条比对兜底。
 *
 * 产物（6 份）：
 *   1. gamecore/include/gamecore/data/equipment_db.h        装备部件表（12 条）
 *   2. gamecore/include/gamecore/data/equip_set_db.h        套装效果表（2 套 × 3 档）
 *   3. gamecore/include/gamecore/data/equip_main_stat_db.h  部位主词条池表
 *   4. gamecore/include/gamecore/data/equip_affix_db.h      副词条池表（7 项）
 *   5. core/engine/src/test/resources/templates/equipment_db_sample.json   快照
 *   6. core/engine/src/test/resources/templates/herb_db_sample.json       快照
 *
 * D9/D10 收口（B3）：本生成器输出**幂等**——含 operator== 与
 * *TemplatesMutable() 注入入口（data_inject.h 运行期注入依赖）；
 * 旧「从 Kotlin 源码正则提取」的死代码已删（D10）。
 *
 * 用法：node scripts/gen-templates.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const GAMECORE = join(ROOT, 'android/app/src/main/cpp/gamecore');
const DATA_DIR = join(ROOT, 'scripts/data');

// ── 中性源读取 ────────────────────────────────────────────────────────

const equipJson = JSON.parse(readFileSync(join(DATA_DIR, 'equipment_db_sample.json'), 'utf-8'));
const setPieces = equipJson.setPieces;
const sets = equipJson.sets;
const mainStatPools = equipJson.mainStatPools;
const mainStatBase = equipJson.mainStatBase;
const subAffixes = equipJson.subAffixes;
if (!Array.isArray(setPieces) || setPieces.length === 0) {
  console.error('错误：中性源 equipment_db_sample.json 无部件条目');
  process.exit(1);
}
// 五行属性伤害系统：6 套（物理 + 金木水火土）
if (!Array.isArray(sets) || sets.length !== 6) {
  console.error('错误：中性源套装定义必须为 6 套（物理 + 五行）');
  process.exit(1);
}

const seen = new Set();
const dupes = setPieces.filter((e) => (seen.has(e.id) ? true : (seen.add(e.id), false)));
if (dupes.length > 0) {
  console.error(`错误：重复部件 id ${dupes.map((d) => d.id).join(',')}`);
  process.exit(1);
}

// ── 装备部件表（equipment_db.h）──────────────────────────────────────

function genCppTable(pieces) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 equipment_db_sample.json 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 装备套装部件静态表（B3：2 套 × 6 部位 = 12 条；按品阶展开为');
  lines.push('// 72 条可生成条目的基表）');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct SetPieceTemplate {');
  lines.push('    std::string id;        // "{setId}_{part}"，如 lietian_HEAD');
  lines.push('    std::string setId;     // 套装 id（lietian/zifu）');
  lines.push('    std::string part;      // 六部位 EquipmentSlot.name');
  lines.push('    std::string name;');
  lines.push('    std::string description;');
  lines.push('    std::int32_t priceByRarity[6];   // 品阶 1..6 价格');
  lines.push('    std::int32_t minRealmByRarity[6]; // 品阶 1..6 穿戴门槛');
  lines.push('};');
  lines.push('');
  lines.push('inline bool operator==(const SetPieceTemplate& a, const SetPieceTemplate& b) {');
  lines.push('    if (a.id != b.id || a.setId != b.setId || a.part != b.part ||');
  lines.push('        a.name != b.name || a.description != b.description) return false;');
  lines.push('    for (int i = 0; i < 6; ++i) {');
  lines.push('        if (a.priceByRarity[i] != b.priceByRarity[i]) return false;');
  lines.push('        if (a.minRealmByRarity[i] != b.minRealmByRarity[i]) return false;');
  lines.push('    }');
  lines.push('    return true;');
  lines.push('}');
  lines.push('');
  lines.push('/// 全部套装部件（12 条；Mutable 入口供 data_inject.h 运行期注入）');
  lines.push('inline std::vector<SetPieceTemplate>& setPieceTemplatesMutable() {');
  lines.push('    static std::vector<SetPieceTemplate> kPieces = {');
  for (const e of pieces) {
    const prices = e.priceByRarity.join(', ');
    const realms = e.minRealmByRarity.join(', ');
    lines.push('        {');
    lines.push(`            "${e.id}", "${e.setId}", "${e.part}", "${e.name}", "${e.description}",`);
    lines.push(`            {${prices}},`);
    lines.push(`            {${realms}}`);
    lines.push('        },');
  }
  lines.push('    };');
  lines.push('    return kPieces;');
  lines.push('}');
  lines.push('');
  lines.push('/// 只读视图');
  lines.push('inline const std::vector<SetPieceTemplate>& setPieceTemplates() {');
  lines.push('    return setPieceTemplatesMutable();');
  lines.push('}');
  lines.push('');
  lines.push('/// 部件 id → 下标（未命中返回 -1）');
  lines.push('inline int setPieceIndexOf(const std::string& id) {');
  lines.push('    const auto& pieces = setPieceTemplates();');
  lines.push('    for (std::size_t i = 0; i < pieces.size(); ++i) {');
  lines.push('        if (pieces[i].id == id) return static_cast<int>(i);');
  lines.push('    }');
  lines.push('    return -1;');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── 套装效果表（equip_set_db.h）──────────────────────────────────────

function genCppSetDb(defs) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 sets 段同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 套装效果静态表（B3：2 套 × 2/4/6 三档；件数达档即生效、');
  lines.push('// 可越级不叠加，穿满 6 件三档同时生效）');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct EquipStatValueDef {');
  lines.push('    std::string stat;   // EquipStat.name');
  lines.push('    double value = 0.0;');
  lines.push('};');
  lines.push('');
  lines.push('inline bool operator==(const EquipStatValueDef& a, const EquipStatValueDef& b) {');
  lines.push('    return a.stat == b.stat && a.value == b.value;');
  lines.push('}');
  lines.push('');
  lines.push('struct EquipmentSetDef {');
  lines.push('    std::string id;');
  lines.push('    std::string name;');
  lines.push('    std::string school; // PHYSICAL / MAGIC');
  lines.push('    std::vector<EquipStatValueDef> bonus2;');
  lines.push('    std::vector<EquipStatValueDef> bonus4;');
  lines.push('    std::vector<EquipStatValueDef> bonus6;');
  lines.push('};');
  lines.push('');
  lines.push('// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）');
  lines.push('inline bool operator==(const EquipmentSetDef& a, const EquipmentSetDef& b) {');
  lines.push('    return a.id == b.id && a.name == b.name && a.school == b.school &&');
  lines.push('           a.bonus2 == b.bonus2 && a.bonus4 == b.bonus4 && a.bonus6 == b.bonus6;');
  lines.push('}');
  lines.push('');
  lines.push('inline std::vector<EquipmentSetDef>& equipmentSetDefsMutable() {');
  lines.push('    static std::vector<EquipmentSetDef> kSets = {');
  for (const def of defs) {
    lines.push('        {');
    lines.push(`            "${def.id}", "${def.name}", "${def.school}",`);
    lines.push('            {');
    for (const b of def.bonus2) lines.push(`                {"${b.stat}", ${b.value}},`);
    lines.push('            },');
    lines.push('            {');
    for (const b of def.bonus4) lines.push(`                {"${b.stat}", ${b.value}},`);
    lines.push('            },');
    lines.push('            {');
    for (const b of def.bonus6) lines.push(`                {"${b.stat}", ${b.value}},`);
    lines.push('            },');
    lines.push('        },');
  }
  lines.push('    };');
  lines.push('    return kSets;');
  lines.push('}');
  lines.push('');
  lines.push('inline const std::vector<EquipmentSetDef>& equipmentSetDefs() {');
  lines.push('    return equipmentSetDefsMutable();');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── 部位主词条池表（equip_main_stat_db.h）────────────────────────────

function genCppMainStatDb(pools, base) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 mainStatPools/mainStatBase 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 部位主词条池静态表（B3，方案 §3.4.2）：主词条从部位候选池等权');
  lines.push('// 抽取（nextInt(size) 索引序），声明序禁重排（跨端对拍基准）；');
  lines.push('// 暴击伤害主词条基数 = 暴击率同档 × 2');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct MainStatPoolDef {');
  lines.push('    std::string part;   // EquipmentSlot.name');
  lines.push('    std::vector<std::string> stats;  // 候选池（EquipStat.name，序 = 抽取序）');
  lines.push('    double coefficient = 1.0;        // 部位数值系数');
  lines.push('};');
  lines.push('');
  lines.push('// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）');
  lines.push('inline bool operator==(const MainStatPoolDef& a, const MainStatPoolDef& b) {');
  lines.push('    return a.part == b.part && a.stats == b.stats &&');
  lines.push('           a.coefficient == b.coefficient;');
  lines.push('}');
  lines.push('');
  lines.push('inline std::vector<MainStatPoolDef>& mainStatPoolsMutable() {');
  lines.push('    static std::vector<MainStatPoolDef> kPools = {');
  for (const [part, def] of Object.entries(pools)) {
    const stats = def.stats.map((s) => `"${s}"`).join(', ');
    lines.push(`        {"${part}", {${stats}}, ${def.coefficient}},`);
  }
  lines.push('    };');
  lines.push('    return kPools;');
  lines.push('}');
  lines.push('');
  lines.push('inline const std::vector<MainStatPoolDef>& mainStatPools() {');
  lines.push('    return mainStatPoolsMutable();');
  lines.push('}');
  lines.push('');
  lines.push('/// 主词条品阶基数表（品阶 1..6）');
  lines.push('struct MainStatBaseRow {');
  lines.push('    std::string stat;');
  lines.push('    double values[6];');
  lines.push('};');
  lines.push('');
  lines.push('inline bool operator==(const MainStatBaseRow& a, const MainStatBaseRow& b) {');
  lines.push('    if (a.stat != b.stat) return false;');
  lines.push('    for (int i = 0; i < 6; ++i) {');
  lines.push('        if (a.values[i] != b.values[i]) return false;');
  lines.push('    }');
  lines.push('    return true;');
  lines.push('}');
  lines.push('');
  lines.push('inline std::vector<MainStatBaseRow>& mainStatBaseMutable() {');
  lines.push('    static std::vector<MainStatBaseRow> kBase = {');
  for (const [stat, values] of Object.entries(base)) {
    lines.push(`        {"${stat}", {${values.join(', ')}}},`);
  }
  lines.push('    };');
  lines.push('    return kBase;');
  lines.push('}');
  lines.push('');
  lines.push('inline const std::vector<MainStatBaseRow>& mainStatBase() {');
  lines.push('    return mainStatBaseMutable();');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── 副词条池表（equip_affix_db.h）────────────────────────────────────

function genCppAffixDb(affixes) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 subAffixes 段同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 副词条池静态表（B3，方案 §3.4.3）：7 项全局池、权重即概率');
  lines.push('//（合计 100）；不放回抽 3 条 = 权重前缀和 + nextInt(total) 定点整数');
  lines.push('// 定位，声明序禁重排（同种子抽取序列跨端对拍基准）');
  lines.push('// ============================================================');
  lines.push('namespace gamecore::data {');
  lines.push('');
  lines.push('struct EquipAffixDef {');
  lines.push('    std::string stat;      // EquipStat.name');
  lines.push('    std::int32_t weight = 0;  // 权重（=概率%，全表合计 100）');
  lines.push('    double tierValues[6];  // 品阶 1..6 单次强化档位值');
  lines.push('};');
  lines.push('');
  lines.push('// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）');
  lines.push('inline bool operator==(const EquipAffixDef& a, const EquipAffixDef& b) {');
  lines.push('    if (a.stat != b.stat || a.weight != b.weight) return false;');
  lines.push('    for (int i = 0; i < 6; ++i) {');
  lines.push('        if (a.tierValues[i] != b.tierValues[i]) return false;');
  lines.push('    }');
  lines.push('    return true;');
  lines.push('}');
  lines.push('');
  lines.push('inline std::vector<EquipAffixDef>& equipAffixesMutable() {');
  lines.push('    static std::vector<EquipAffixDef> kAffixes = {');
  for (const a of affixes) {
    lines.push(`        {"${a.stat}", ${a.weight}, {${a.tierValues.join(', ')}}},`);
  }
  lines.push('    };');
  lines.push('    return kAffixes;');
  lines.push('}');
  lines.push('');
  lines.push('inline const std::vector<EquipAffixDef>& equipAffixes() {');
  lines.push('    return equipAffixesMutable();');
  lines.push('}');
  lines.push('');
  lines.push('/// 权重合计（应为 100）');
  lines.push('inline std::int32_t equipAffixTotalWeight() {');
  lines.push('    std::int32_t total = 0;');
  lines.push('    for (const auto& a : equipAffixes()) total += a.weight;');
  lines.push('    return total;');
  lines.push('}');
  lines.push('');
  lines.push('}  // namespace gamecore::data');
  lines.push('');
  return lines.join('\n');
}

// ── 灵草/种子表（herb_db.h，沿用既有链）──────────────────────────────

function genCppHerbTable(herbs, seeds) {
  const lines = [];
  lines.push('// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 herb_db_sample.json 同源）');
  lines.push('#pragma once');
  lines.push('');
  lines.push('#include <cstdint>');
  lines.push('#include <string>');
  lines.push('#include <vector>');
  lines.push('');
  lines.push('// ============================================================');
  lines.push('// 灵草/种子静态表（字段与 HerbDatabase.Herb/Seed 构造参数一致）');
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
  lines.push('// 幂等比较（data_inject 注入守卫逐字段比对面；B3 重写补齐——与');
  lines.push('// equipment_db.h SetPieceTemplate 同约定）');
  lines.push('inline bool operator==(const HerbTemplate& a, const HerbTemplate& b) {');
  lines.push('    return a.id == b.id && a.name == b.name && a.tier == b.tier &&');
  lines.push('           a.rarity == b.rarity && a.category == b.category &&');
  lines.push('           a.description == b.description;');
  lines.push('}');
  lines.push('inline bool operator==(const SeedTemplate& a, const SeedTemplate& b) {');
  lines.push('    return a.id == b.id && a.name == b.name && a.tier == b.tier &&');
  lines.push('           a.rarity == b.rarity && a.growTime == b.growTime &&');
  lines.push('           a.yield == b.yield && a.description == b.description;');
  lines.push('}');
  lines.push('');
  lines.push('/// 全部灵草模板（Mutable 入口供 data_inject.h 运行期注入——equipment_db.h 同约定）');
  lines.push('inline std::vector<HerbTemplate>& herbTemplatesMutable() {');
  lines.push('    static std::vector<HerbTemplate> kHerbs = {');
  for (const h of herbs) {
    lines.push(`        {"${h.id}", "${h.name}", ${h.tier}, ${h.rarity}, "${h.category}", "${h.description}"},`);
  }
  lines.push('    };');
  lines.push('    return kHerbs;');
  lines.push('}');
  lines.push('');
  lines.push('/// 只读视图');
  lines.push('inline const std::vector<HerbTemplate>& herbTemplates() {');
  lines.push('    return herbTemplatesMutable();');
  lines.push('}');
  lines.push('');
  lines.push('/// 全部种子模板（Mutable 入口供 data_inject.h 运行期注入）');
  lines.push('inline std::vector<SeedTemplate>& seedTemplatesMutable() {');
  lines.push('    static std::vector<SeedTemplate> kSeeds = {');
  for (const s of seeds) {
    lines.push(`        {"${s.id}", "${s.name}", ${s.tier}, ${s.rarity}, ${s.growTime}, ${s.yield}, "${s.description}"},`);
  }
  lines.push('    };');
  lines.push('    return kSeeds;');
  lines.push('}');
  lines.push('');
  lines.push('/// 只读视图');
  lines.push('inline const std::vector<SeedTemplate>& seedTemplates() {');
  lines.push('    return seedTemplatesMutable();');
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

const cppDir = join(GAMECORE, 'include/gamecore/data');
mkdirSync(cppDir, { recursive: true });
writeFileSync(join(cppDir, 'equipment_db.h'), genCppTable(setPieces));
writeFileSync(join(cppDir, 'equip_set_db.h'), genCppSetDb(sets));
writeFileSync(join(cppDir, 'equip_main_stat_db.h'), genCppMainStatDb(mainStatPools, mainStatBase));
writeFileSync(join(cppDir, 'equip_affix_db.h'), genCppAffixDb(subAffixes));

const sampleDir = join(ROOT, 'android/core/engine/src/test/resources/templates');
mkdirSync(sampleDir, { recursive: true });
writeFileSync(join(sampleDir, 'equipment_db_sample.json'),
  JSON.stringify(equipJson, null, 1));

console.log(`装备表生成完成：${setPieces.length} 部件 + ${sets.length} 套装 + 六部位池 + ${subAffixes.length} 副词条`);
console.log(`  -> ${join(cppDir, 'equipment_db.h')}`);
console.log(`  -> ${join(cppDir, 'equip_set_db.h')}`);
console.log(`  -> ${join(cppDir, 'equip_main_stat_db.h')}`);
console.log(`  -> ${join(cppDir, 'equip_affix_db.h')}`);
console.log(`  -> ${join(sampleDir, 'equipment_db_sample.json')}`);

// ── 灵草/种子表（中性源）─────────────────────────────────────────────
const herbJson = JSON.parse(readFileSync(join(DATA_DIR, 'herb_db_sample.json'), 'utf-8'));
const herbs = herbJson.herbs;
const seeds = herbJson.seeds;
if (!Array.isArray(herbs) || herbs.length === 0 ||
    !Array.isArray(seeds) || seeds.length === 0) {
  console.error('错误：中性源 herb_db_sample.json 无条目');
  process.exit(1);
}

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
