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
 *  - SPIRIT_STONE：res 后缀 low/mid/high → 材料/{下品|中品|上品}灵石.png
 *  - SECT_ICON：res 后缀 small/medium/large/top → ui/{小型|中型|大型|顶级}宗门图标.png
 *  - BEAST：name（tiger/wolf/...） → 妖兽/{虎|狼|蛇|熊|鹰|狐|龙|龟}妖.png
 *  - HEAVENLY_TRIAL：island_N → 地图关卡场景/天道试炼{活动第一|第<中文数字>}关岛屿.png；
 *    其余（挑战背景/战斗场景/战斗栏/防御/普攻/两阶段图标）固定映射
 *  - BACKGROUND / UI：固定 drawable→源 映射表
 *  - ITEM：(herb_/seed_) 沿用中文名→草药/种子文件；growing_ 用对应 herb_ 的中文名
 *    → 草药生长期/<中文名>成长期图片.png（不存在则回退 <中文名>成长期.png）
 *  - MANUAL_OVERRIDES（优先级最高）：无法或不适合自动推导的条目（装备名与源名不一致、
 *    种子用「种」而非「核」、CAVE 四件、待核验确认的 UI/背景 等）
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

/**
 * 手动覆盖（drawable → source 相对路径）；优先级最高，先于 deriveSource 规则。
 * 用于：源文件名与 display name 不一致、无法用分类规则可靠推导、或经 read_image 人工确认的条目。
 */
const MANUAL_OVERRIDES = {
  // EQUIPMENT：源文件名与注册名不一致（龙灵珠在 材料/；鸾羽履 官方文件用「靴」）
  long_ling_zhu: '材料/龙灵珠.png',
  luan_yu_lv: '装备/鸾羽靴.png',
  // ITEM：玄灵莓核 的种子文件用「种」而非「核」
  seed_spiritfruit8: '种子/玄灵莓种.png',
  // CAVE：洞府素材 + 远古秘境（无分类名可推）
  cave_1: '建筑/洞府素材1.png',
  cave_2: '建筑/洞府素材2.png',
  cave_3: '建筑/洞府素材3.png',
  secret_realm: '地图关卡场景/远古秘境.png',
};

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

/** growing_spirit* → 对应 herb_spirit* 的中文名（由资源注册表构建，见下方填充） */
let herbNameByRes = new Map();

/** 按分类给单个 registry entry 推导 source（相对路径）；null = 待补 */
function deriveSource(category, res, name) {
  // 手动覆盖优先（不可可靠推导/名称不一致的条目）
  if (MANUAL_OVERRIDES[res] !== undefined) return MANUAL_OVERRIDES[res];

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
    case 'SPIRIT_STONE': {
      // res: spirit_stone_low/mid/high → 材料/{下品|中品|上品}灵石.png
      const map = {
        spirit_stone_low: '下品灵石',
        spirit_stone_mid: '中品灵石',
        spirit_stone_high: '上品灵石',
      };
      const n = map[res];
      if (n && fileExists(`材料/${n}.png`)) return `材料/${n}.png`;
      return null;
    }
    case 'SECT_ICON': {
      // res: sect_icon_small/medium/large/top → ui/{小型|中型|大型|顶级}宗门图标.png
      const map = {
        sect_icon_small: '小型宗门图标',
        sect_icon_medium: '中型宗门图标',
        sect_icon_large: '大型宗门图标',
        sect_icon_top: '顶级宗门图标',
      };
      const n = map[res];
      if (n && fileExists(`ui/${n}.png`)) return `ui/${n}.png`;
      return null;
    }
    case 'BEAST': {
      // name: tiger/wolf/snake/bear/eagle/fox/dragon/turtle → 妖兽/{虎|狼|蛇|熊|鹰|狐|龙|龟}妖.png
      const map = {
        tiger: '虎', wolf: '狼', snake: '蛇', bear: '熊',
        eagle: '鹰', fox: '狐', dragon: '龙', turtle: '龟',
      };
      const a = map[name];
      if (a && fileExists(`妖兽/${a}妖.png`)) return `妖兽/${a}妖.png`;
      return null;
    }
    case 'HEAVENLY_TRIAL': {
      // island_N：1 → 活动第一关；2..8 → 第<中文数字>关
      const CN = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];
      const isl = res.match(/^heavenly_trial_island_(\d)$/);
      if (isl) {
        const idx = parseInt(isl[1], 10);
        if (idx === 1 && fileExists('地图关卡场景/天道试炼活动第一关岛屿.png')) {
          return '地图关卡场景/天道试炼活动第一关岛屿.png';
        }
        if (idx >= 2 && idx <= 8 && CN[idx] && fileExists(`地图关卡场景/天道试炼第${CN[idx]}关岛屿.png`)) {
          return `地图关卡场景/天道试炼第${CN[idx]}关岛屿.png`;
        }
        return null;
      }
      // 其余固定映射
      const map = {
        heavenly_trial_challenge_bg: '背景图/天道试炼活动背景图.png',
        heavenly_trial_battle_scene: '背景图/战斗场景.png',
        heavenly_trial_battle_bar: 'ui/战斗栏背景图.png',
        heavenly_trial_defend: 'ui/防御图标.png',
        heavenly_trial_atk_normal: 'ui/普通攻击图标.png',
        heavenly_trial_phase1: 'ui/天道试炼活动挑战界面第一关图标.png',
        heavenly_trial_phase2: 'ui/天道试炼活动挑战界面第二关图标.png',
        // heavenly_trial_map：drawable 为多岛屿合成地图场景，无唯一匹配源 → 保持待补
      };
      const s = map[res];
      if (s && fileExists(s)) return s;
      return null;
    }
    case 'BACKGROUND': {
      const map = {
        bg_horizontal: '背景图/横向背景图.png',
        map_zhongzhou: '地图关卡场景/中州.png',
        dialogue_bg: 'ui/聊天背景图.png',
        dialogue_bubble_left: 'ui/聊天框（左）.png',
        dialogue_bubble_right: 'ui/聊天框（右）.png',
        secret_realm_bg: '背景图/远古秘境背景图.png',
        bg_screen: 'ui/界面背景图.png',
        bg_dialog_mail: 'ui/提示框背景图.png',
      };
      const s = map[res];
      if (s && fileExists(s)) return s;
      return null;
    }
    case 'UI': {
      const map = {
        ui_button: 'ui/按钮（长方形）.png',
        ui_close_button: 'ui/关闭按钮（圆形）.png',
        ui_detail_button: 'ui/详情按钮（圆形）.png',
        ui_add_button: 'ui/+号按钮.png',
        ui_diplomacy_button: 'ui/外交按钮（圆形）.png',
        ui_guide_button: 'ui/引导按钮.png',
        ui_merchant_button: 'ui/商人按钮（圆形）.png',
        ui_build_button: 'ui/建造按钮.png',
        ui_warehouse_button: 'ui/仓库按钮.png',
        ui_team_button: 'ui/弟子按钮（圆形）.png',
        ui_map_button: 'ui/地图按钮(圆形）.png',
        ui_planting_button: 'ui/种植按钮.png',
        ui_recruit_button: 'ui/招募按钮（圆形）.png',
        ui_mail_button: 'ui/邮件按钮.png',
        ui_log_button: 'ui/日志按钮（圆形）.png',
        dialog_box: 'ui/提示框背景图.png',
        ui_hide_button: 'ui/隐藏ui按钮.png',
        ui_show_button: 'ui/ui显示按钮.png',
        ui_play_button: 'ui/播放按钮（圆形）.png',
        ui_pause_button: 'ui/暂停按钮（圆形）.png',
        ui_settings_button: 'ui/设置按钮（圆形）.png',
        ui_start_button: 'ui/进入游戏.png',
        loading_background: 'ui/加载界面（横屏）.png',
        combat_power_bg: 'ui/战力背景图片.png',
        jade_symbol: 'ui/玉符.png',
        jade_add_button: 'ui/+号按钮.png',
        golden_finger: 'ui/金手指.png',
        secret_realm_option_card: 'ui/远古秘境选项卡片.png',
        ui_lizhan_button: 'ui/历战图标.png',
        li_zhan_card: 'ui/历战卡片.png',
        heavenly_trial_icon: 'ui/天道试炼图标.png',
        ui_leaderboard_button: 'ui/排行榜图标.png',
        ui_flip_left: 'ui/翻页按钮（左）.png',
        ui_flip_right: 'ui/翻页按钮(右）.png',
        area_select_button: 'ui/区域选择按钮.png',
        // ui_sysmsg：drawable 为带「系统消息」标题+关闭按钮的合成图，无独立源文件 → 保持待补
      };
      const s = map[res];
      if (s && fileExists(s)) return s;
      return null;
    }
    case 'ITEM': {
      // name 为中文；res 为 herb_spirit* / seed_spirit* / growing_spirit*
      if (/^growing_/.test(res)) {
        // 生长阶段精灵：由对应 herb_* 的中文名映射到 草药生长期/<中文名>成长期图片.png
        const herbRes = res.replace(/^growing_/, 'herb_');
        const herbName = herbNameByRes.get(herbRes);
        if (!herbName) return null;
        if (fileExists(`草药生长期/${herbName}成长期图片.png`)) return `草药生长期/${herbName}成长期图片.png`;
        if (fileExists(`草药生长期/${herbName}成长期.png`)) return `草药生长期/${herbName}成长期.png`;
        return null;
      }
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

// 构建 growing_spirit* → 中文名 查找表（取 ITEM 分类下 herb_* 的中文名）
herbNameByRes = new Map();
for (const cat of registry.categories) {
  if (cat.category !== 'ITEM') continue;
  for (const e of cat.entries) {
    if (/^herb_/.test(e.res)) herbNameByRes.set(e.res, e.name);
  }
}

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
