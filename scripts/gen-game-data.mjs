#!/usr/bin/env node
/**
 * gen-game-data.mjs — B16（R6.2 数值外置）统一数据文件生成器
 *
 * 把 C++ `gamecore/data/*.h` 头文件 DB 的**条目数据**落到一份数据文件：
 *   android/app/src/main/assets/data/game-data.json
 *
 * 单一源链（本批确立，取代「头文件即真相源」的旧链）：
 *   scripts/data/<db>_sample.json（中性源）
 *     → 本脚本
 *       ├─ assets/data/game-data.json           （运行时注入的数据文件）
 *       └─ 头文件内联默认值兜底（防双真相源由 C++ 守卫锁定：
 *          equipment/herb = gen-templates.mjs、manual = gen-manual-db.mjs、
 *          beast_material = gen-beast-material-db.mjs 的既有生成物；
 *          recipe/trait = C++ 手写等价复刻——数值变更须人工同步头文件兜底，
 *          漂移时 `DataStoreGuardTest` 兜底变红；自动再生成工具属后续批次）
 *
 * 设计要点（对齐批次的 6 项任务与红线）：
 *   ① 单文件聚合 JSON：7 个 DB 一张文件，天然满足 trait→equipment/herb 的
 *      加载顺序约束（无需拓扑排序）。
 *   ② 数值真相源在资产侧（Kotlin/JSON），C++ 只「解析 + 兜底」——沿
 *      game_config.json / nativeSetGameConfig 先例。
 *   ③ 与 Kotlin 侧同源可校验：本脚本只读 scripts/data/*.json（即 Kotlin
 *      Registry 的单一源快照，由既有 StaticDataSingleSourceGuardTest /
 *      *RegistryGuardTest 全量比对兜底），因此 C++ 侧数据文件与 Kotlin
 *      Registry 同源由**结构**保证，不靠新增比对。
 *
 * 用法：
 *   node scripts/gen-game-data.mjs            # 生成 assets/data/game-data.json
 *   node scripts/gen-game-data.mjs --check    # 只校验（CI/守卫用，不落盘）
 *   node scripts/gen-game-data.mjs --hash     # 打印 sha256（codegen hash 门）
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA_DIR = join(ROOT, 'scripts/data');

/** 输出路径（与 GAMECORE_DATA_DB 契约一致） */
const OUT_JSON = join(
  ROOT, 'android/app/src/main/assets/data/game-data.json');
/** 摘要 sidecar（codegen hash 门锚点，入库防漂移） */
const OUT_HASH = join(
  ROOT, 'android/app/src/main/assets/data/game-data.hash.txt');

/** 数据文件 schema 版本（C++ 侧 data_store.h 同字面量校验） */
const SCHEMA_VERSION = 1;

const argv = process.argv.slice(2);
const CHECK_ONLY = argv.includes('--check');
const HASH_ONLY = argv.includes('--hash');

// ── 中性源读取 ────────────────────────────────────────────────────────

function readJson(name) {
  const p = join(DATA_DIR, name);
  if (!existsSync(p)) {
    console.error(`错误：中性源缺失 ${p}`);
    process.exit(1);
  }
  return JSON.parse(readFileSync(p, 'utf-8'));
}

function requireArray(obj, key, file) {
  const v = obj[key];
  if (!Array.isArray(v) || v.length === 0) {
    console.error(`错误：中性源 ${file} 的 ${key} 缺失或为空`);
    process.exit(1);
  }
  return v;
}

function dedupe(rows, file) {
  const seen = new Set();
  const dupes = rows.filter((r) => (seen.has(r.id) ? true : (seen.add(r.id), false)));
  if (dupes.length > 0) {
    console.error(`错误：${file} 重复 id ${dupes.map((d) => d.id).join(',')}`);
    process.exit(1);
  }
  return rows;
}

// ── 各 DB 条目装载（字段与 C++ struct 逐字段对齐）──────────────────────

function loadEquipment() {
  const j = readJson('equipment_db_sample.json');
  return dedupe(requireArray(j, 'entries', 'equipment_db_sample.json'), 'equipment');
}

function loadHerb() {
  const j = readJson('herb_db_sample.json');
  return {
    herbs: dedupe(requireArray(j, 'herbs', 'herb_db_sample.json'), 'herb'),
    seeds: dedupe(requireArray(j, 'seeds', 'herb_db_sample.json'), 'seed'),
  };
}

function loadManual() {
  const j = readJson('manual_db_sample.json');
  return dedupe(requireArray(j, 'entries', 'manual_db_sample.json'), 'manual');
}

function loadRecipe() {
  const j = readJson('recipe_db_sample.json');
  // 中性源结构：{forgeRecipeCount, pillRecipeCount, forgeRecipes:[…], pillRecipes:[…]}
  const forge = dedupe(requireArray(j, 'forgeRecipes', 'recipe_db_sample.json'), 'forgeRecipe');
  const pills = dedupe(requireArray(j, 'pillRecipes', 'recipe_db_sample.json'), 'pillRecipe');
  return { forge, pills };
}

function loadTrait() {
  const j = readJson('trait_db_sample.json');
  return {
    talents: dedupe(requireArray(j, 'talents', 'trait_db_sample.json'), 'talent'),
    physiques: dedupe(requireArray(j, 'physiques', 'trait_db_sample.json'), 'physique'),
    affixes: dedupe(requireArray(j, 'affixes', 'trait_db_sample.json'), 'affix'),
  };
}

function loadBeastMaterial() {
  const j = readJson('beast_material_db_sample.json');
  return dedupe(requireArray(j, 'entries', 'beast_material_db_sample.json'),
    'beastMaterial');
}

// beast_config 的 realm 表 / 技能表 / 类型表为**派生型数值**
// （realm 表按 realm 索引复制；技能表按 8 类 beast 展开）——其真相源为
// C++ 侧既有结构，本批登记为"结构性数值，仍在 C++ 侧生成"（见批次残余登记），
// 不进入数据文件，避免把派生逻辑搬到数据侧造成双真相源。

// ── 汇总 ──────────────────────────────────────────────────────────────

const equipment = loadEquipment();
const herb = loadHerb();
const manual = loadManual();
const recipe = loadRecipe();
const trait = loadTrait();
const beastMaterial = loadBeastMaterial();

const doc = {
  schemaVersion: SCHEMA_VERSION,
  generatedBy: 'scripts/gen-game-data.mjs',
  note: '由本脚本生成 — 禁止手改；数值真相源 = scripts/data/*_sample.json（Kotlin 侧单一源）',
  db: {
    equipment,
    herbs: herb.herbs,
    seeds: herb.seeds,
    manuals: manual,
    forgeRecipes: recipe.forge,
    pillRecipes: recipe.pills,
    talents: trait.talents,
    physiques: trait.physiques,
    affixes: trait.affixes,
    beastMaterials: beastMaterial,
  },
};

function counts() {
  const d = doc.db;
  return [
    ['equipment', d.equipment.length],
    ['herbs', d.herbs.length],
    ['seeds', d.seeds.length],
    ['manuals', d.manuals.length],
    ['forgeRecipes', d.forgeRecipes.length],
    ['pillRecipes', d.pillRecipes.length],
    ['talents', d.talents.length],
    ['physiques', d.physiques.length],
    ['affixes', d.affixes.length],
    ['beastMaterials', d.beastMaterials.length],
  ];
}

/** 稳定序列化（键序固定 + 2 空格缩进）——确定性产物，支持逐位复现 */
function stableStringify(value) {
  if (Array.isArray(value)) {
    return '[' + value.map(stableStringify).join(',') + ']';
  }
  if (value && typeof value === 'object') {
    const keys = Object.keys(value).sort();
    return '{' + keys.map((k) => JSON.stringify(k) + ':' + stableStringify(value[k]))
      .join(',') + '}';
  }
  return JSON.stringify(value);
}

const payload = stableStringify(doc);
const sha = createHash('sha256').update(payload, 'utf-8').digest('hex');

if (HASH_ONLY) {
  console.log(sha);
  process.exit(0);
}

if (CHECK_ONLY) {
  if (!existsSync(OUT_JSON)) {
    console.error(`校验失败：产物不存在 ${OUT_JSON}`);
    process.exit(1);
  }
  const onDisk = readFileSync(OUT_JSON, 'utf-8');
  const diskSha = createHash('sha256').update(onDisk, 'utf-8').digest('hex');
  if (diskSha !== sha) {
    console.error('校验失败：game-data.json 与中性源不一致——请重跑 node scripts/gen-game-data.mjs');
    console.error(`  期望 sha256 ${sha}`);
    console.error(`  实际 sha256 ${diskSha}`);
    process.exit(1);
  }
  console.log(`校验通过：game-data.json 与中性源一致（sha256 ${sha}）`);
  process.exit(0);
}

mkdirSync(dirname(OUT_JSON), { recursive: true });
writeFileSync(OUT_JSON, payload);
writeFileSync(
  OUT_HASH,
  `${sha}  game-data.json\n# 由 scripts/gen-game-data.mjs 生成（B16/R6.2 codegen hash 门锚点）\n`);

console.log('数据文件生成完成：');
for (const [k, n] of counts()) console.log(`  ${k}: ${n}`);
console.log(`  -> ${OUT_JSON}`);
console.log(`  -> ${OUT_HASH}`);
console.log(`  sha256: ${sha}`);
