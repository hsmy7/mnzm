/**
 * 发现脚本 — 生成 scripts/source-mapping.json（权威源 ↔ drawable 映射）。
 *
 * 数据源（只读）：
 * - scripts/resource-registry.json — 精灵注册源（name → res，按 SpriteCategory 分类）
 * - D:\模拟宗门美术素材 — 美术素材源目录（子目录 = 分类）
 * - scripts/import-art-assets.mjs IMPORT 表 — 既有 6 条（天枢殿 + 云层）
 *
 * 关联规则（按分类，能可靠推导的自动盖上；不可靠的置 pending 并列入报告）：
 *  - EQUIPMENT / MATERIAL：registry.name(中文) == 源文件名 → <子目录>/<name>.png
 *  - PILL / STORAGE_BAG：res 前缀厂级后缀 凡/灵/宝/玄/地/天 → <子目录>/<X品丹药|储物袋>.png
 *  - MANUAL：res → 功法/凡品与灵品|宝品和玄品|地品和天品 功法精灵图.png
 *  - 天枢殿 / 云层1~5：来自 import-art-assets.mjs IMPORT 表
 *
 * 输出：
 * - scripts/source-mapping.json（唯一权威映射，入库）
 * - 控制台报告：自动盖上 / 待补(pending) 计数 + 待补清单（供人工补齐）
 *
 * 用法：node scripts/scaffold-source-mapping.mjs
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { ensureManifest } from './resource-manifest.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const REGISTRY_FILE = path.resolve(__dirname, 'resource-registry.json');
const OUT_FILE = path.resolve(__dirname, 'source-mapping.json');
const SOURCE_DIR = 'D:/模拟宗门美术素材';

/** 分类 → 源目录（中文子目录名） */
const CAT_SRC_DIR = {
  EQUIPMENT: '装备', MATERIAL: '材料', PILL: '丹药', STORAGE_BAG: '储物袋',
  MANUAL: '功法', ITEM: '草药', UI: 'ui', BEAST: '妖兽', CAVE: '地图关卡场景',
  HEAVENLY_TRIAL: '地图关卡场景', BACKGROUND: '背景图', PORTRAIT: '弟子肖像图',
};

/** 品级后缀（小物件分类烘焙默认 maxDim） */
const CAT_BAKE = {
  PILL: { maxDim: 1024 }, MATERIAL: { maxDim: 1024 }, EQUIPMENT: { maxDim: 1024 },
  STORAGE_BAG: { maxDim: 1024 }, MANUAL: { maxDim: 1024 }, SEED: { maxDim: 1024 },
};

/** 品级字 → 源文件名片段（PILL/STORAGE_BAG 共用） */
const GRADE_SRC = { fan: '凡品', ling: '灵品', bao: '宝品', xuan: '玄品', di: '地品', tian: '天品' };

/** listDirFiles：返回目录内图片文件名的 Set（无扩展名） */
function listDirFiles(dir) {
  const s = new Set();
  if (!fs.existsSync(dir)) return s;
  for (const f of fs.readdirSync(dir)) {
    if (/\.(png|jpg|jpeg)$/i.test(f)) s.add(f.replace(/\.[^.]+$/, ''));
  }
  return s;
}

function fileExists(rel) { return fs.existsSync(path.join(SOURCE_DIR, rel)); }

/** 按分类给单个 registry entry 推导 source（相对路径）；null = 待补 */
function deriveSource(category, res, name) {
  switch (category) {
    case 'EQUIPMENT': case 'MATERIAL': {
      const dir = CAT_SRC_DIR[category];
      if (fileExists(`${dir}/${name}.png`)) return `${dir}/${name}.png`;
      // 源文件名可能与 display name 不同（去常见后缀再试）
      const stripped = name.replace(/(图片|草药图片|种图片|图标)$/, '');
      if (stripped !== name && fileExists(`${dir}/${stripped}.png`)) return `${dir}/${stripped}.png`;
      return null;
    }
    case 'PILL': {
      // res 形如 pill_<grade>；源为 丹药/<grade>丹药.png
      const grade = res.replace(/^[a-z]+_/, '');
      const g = GRADE_SRC[grade];
      if (g && fileExists(`丹药/${g}丹药.png`)) return `丹药/${g}丹药.png`;
      return null;
    }
    case 'STORAGE_BAG': {
      const grade = res.replace(/^[a-z]+_/, '');
      const g = GRADE_SRC[grade];
      if (g && fileExists(`储物袋/${g}储物袋.png`)) return `储物袋/${g}储物袋.png`;
      return null;
    }
    case 'MANUAL': {
      // res: manual_fan_ling / manual_bao_xuan / manual_di_tian
      const map = {
        manual_fan_ling: '凡品与灵品功法精灵图',
        manual_bao_xuan: '宝品和玄品功法精灵图',
        manual_di_tian: '地品和天品功法精灵图',
      };
      const base = map[res];
      if (base && fileExists(`功法/${base}.png`)) return `功法/${base}.png`;
      return null;
    }
    case 'ITEM': {
      // name 为中文；res 为 herb_spirit* / seed_spirit* / growing_spirit*
      if (/^growing_/.test(res)) return null; // 生长阶段精灵，无独立中文源
      const isSeed = name.endsWith('种') || /^seed_/.test(res);
      const suffix = isSeed ? ['种图片', '种', '图片'] : ['草药图片', '草药', '图片'];
      const dir = isSeed ? '种子' : '草药';
      for (const sfx of suffix) {
        const cand = `${name}${sfx}.png`;
        if (fileExists(`${dir}/${cand}`)) return `${dir}/${cand}`;
      }
      // 退路：<name>图片.png
      if (fileExists(`${dir}/${name}图片.png`)) return `${dir}/${name}图片.png`;
      if (fileExists(`${dir}/${name}.png`)) return `${dir}/${name}.png`;
      return null;
    }
    default:
      return null;
  }
}

const registry = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8'));
const pending = [];

// 全局按 drawable 去重（同一 res 可跨分类重复：spirit_stone_low 在 SPIRIT_STONE 与 ITEM 都有）
const byDrawable = new Map();
for (const cat of registry.categories) {
  for (const e of cat.entries) {
    if (byDrawable.has(e.res)) continue; // 首次出现优先，后续跨分类重复跳过
    const source = deriveSource(cat.category, e.res, e.name);
    if (!source) pending.push({ category: cat.category, drawable: e.res, name: e.name });
    // 烘焙：小物件 maxDim 1024 / 大图 preserve；ITEM 内草药+种子是 maxDim，growing 是 preserve
    const bake = cat.category === 'ITEM'
      ? (/^(herb_|seed_)/.test(e.res) ? { maxDim: 1024 } : { preserve: true })
      : (CAT_BAKE[cat.category] ?? { preserve: true });
    byDrawable.set(e.res, { drawable: e.res, source, category: cat.category, bake });
  }
}

// 按首次出现分类聚合，保证 JSON 结构按分类分组且 drawable 全局唯一
const categories = [];
const catIndex = new Map();
for (const entry of byDrawable.values()) {
  let c = catIndex.get(entry.category);
  if (!c) {
    c = { category: entry.category, entries: [] };
    catIndex.set(entry.category, c);
    categories.push(c);
  }
  c.entries.push({ drawable: entry.drawable, source: entry.source, modules: ['feature/game', 'app'], bake: entry.bake });
}

// 天枢殿 + 云层（import-art-assets.mjs 既有 6 条；天枢殿源在 建筑/，云层源在 装饰物/）
const KNOWN = [
  { category: 'BUILDING', drawable: 'building_tianshu_hall', source: '建筑/天枢殿.png', modules: ['feature/game', 'app'], bake: { preserve: true } },
];
for (let i = 1; i <= 5; i++) {
  KNOWN.push({ category: 'BACKGROUND', drawable: `cloud_${i}`, source: `装饰物/云层${i}.png`, modules: ['feature/game', 'app'], bake: { preserve: true } });
}

const mapping = {
  version: 1,
  description: '美术素材 source↔drawable 权威映射（由 scaffold-source-mapping.mjs 生成）。source 相对 D:\\模拟宗门美术素材；bake.preserve=true 保留源分辨率，bake.maxDim 等比缩放最长边。',
  sourceDir: SOURCE_DIR,
  bakeDefaults: {
    PILL: { maxDim: 1024 }, MATERIAL: { maxDim: 1024 }, EQUIPMENT: { maxDim: 1024 },
    STORAGE_BAG: { maxDim: 1024 }, MANUAL: { maxDim: 1024 }, SEED: { maxDim: 1024 },
    PORTRAIT: { preserve: true }, BUILDING: { preserve: true }, UI: { preserve: true },
    BACKGROUND: { preserve: true }, BEAST: { preserve: true }, CAVE: { preserve: true },
    HEAVENLY_TRIAL: { preserve: true }, MAP: { preserve: true },
  },
  categories,
};
mapping.categories = mapping.categories.filter((c) => c.entries.length > 0);
// 合并已知（天枢殿/云层）：既有类目追加，或新建类目
for (const k of KNOWN) {
  let c = mapping.categories.find((x) => x.category === k.category);
  if (!c) { c = { category: k.category, entries: [] }; mapping.categories.push(c); }
  c.entries.push({ drawable: k.drawable, source: k.source, modules: k.modules, bake: k.bake });
}

fs.writeFileSync(OUT_FILE, JSON.stringify(mapping, null, 2) + '\n');

const mappedCount = mapping.categories.reduce((n, c) => n + c.entries.filter((e) => e.source).length, 0);
const pendingCount = pending.length;
console.log(`source-mapping.json 生成: ${OUT_FILE}`);
console.log(`  分类数: ${mapping.categories.length}`);
console.log(`  已映射(source): ${mappedCount}`);
console.log(`  待补(pending): ${pendingCount}`);
if (pending.length > 0) {
  console.log('\n待补清单（source 为 null，需人工或补充分类规则）：');
  for (const p of pending.slice(0, 40)) console.log(`  [${p.category}] ${p.drawable} (${p.name})`);
  if (pending.length > 40) console.log(`  ... 共 ${pending.length} 条`);
}
