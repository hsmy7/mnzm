/**
 * 发现脚本 — 生成 scripts/source-mapping.json（权威源 ↔ drawable 映射）。
 *
 * 数据源（只读）：
 * - scripts/resource-registry.json — 精灵注册源（name → res，按 SpriteCategory 分类）
 * - 仓库根的 模拟宗门美术素材/ — 美术素材源目录（子目录 = 分类；解析见 art-source.mjs）
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
 *  - CHARACTER：寻访角色素材逐角色一目录（<角色名>/头像.png、<角色名>/全身像.png），
 *    源与烘焙档位均在 CHARACTERS 登记表显式登记（目录名全/半角括号混用，不做推导）
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
import sharp from 'sharp';
import { fileURLToPath } from 'url';
import { ensureManifest } from './resource-manifest.mjs';
import { ART_SOURCE_REL, resolveArtSourceDir } from './art-source.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const REGISTRY_FILE = path.resolve(__dirname, 'resource-registry.json');
const OUT_FILE = path.resolve(__dirname, 'source-mapping.json');

/**
 * 美术素材源目录 = 仓库根的 `模拟宗门美术素材/`（rules/media-source-assets.md §1 指定的唯一原始素材来源）。
 * 解析与 fail-fast 规则集中在 art-source.mjs，与 import-art-assets.mjs 共用同一真源。
 */
const SOURCE_DIR = resolveArtSourceDir();

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

/**
 * 寻访角色素材登记表（G16）：`id` 与 `game-data.json` 的 `characterTemplates[*].id` 逐字符相同，
 * `srcDir` 是源目录里的角色目录名（全/半角括号混用是美术侧现状，见 rules/media-source-assets.md §1，
 * 故逐条显式登记而非按目录名推导）。
 */
const CHARACTERS = [
  { id: 'zhouming', srcDir: '周明（男）' },
  { id: 'suqing', srcDir: '苏晴（女）' },
  { id: 'linxuetang', srcDir: '林雪棠（女)' },
  { id: 'xuhe', srcDir: '许荷（女）' },
  { id: 'xieche', srcDir: '谢澈(男）' },
  { id: 'zhaoyan', srcDir: '赵言(男）' },
];

/**
 * 头像走寻访结果页 2×5 正方形框（横屏高 1080px ⇒ 格子约 170px），立绘走图鉴 6 格
 * （约 120dp 宽 ≈ 360px@3x）——两者显示尺寸差一个量级，故按 drawable 而非按分类定档。
 * 调档只需改这两个常数 + 重跑 import-art-assets.mjs（映射是幂等的）。
 */
const CHARACTER_AVATAR_MAX_DIM = 512;
const CHARACTER_PORTRAIT_MAX_DIM = 1024;

/** drawable → 源相对路径 */
const CHARACTER_SOURCE = new Map();
/** drawable → bake（分类级 CAT_BAKE 表达不了同分类内的档位差） */
const DRAWABLE_BAKE = new Map();
for (const c of CHARACTERS) {
  CHARACTER_SOURCE.set(`avatar_${c.id}`, `${c.srcDir}/头像.png`);
  CHARACTER_SOURCE.set(`portrait_${c.id}`, `${c.srcDir}/全身像.png`);
  DRAWABLE_BAKE.set(`avatar_${c.id}`, { maxDim: CHARACTER_AVATAR_MAX_DIM });
  DRAWABLE_BAKE.set(`portrait_${c.id}`, { maxDim: CHARACTER_PORTRAIT_MAX_DIM });
}


/** 品级字 → 源文件名片段（PILL/STORAGE_BAG 共用） */
const GRADE_SRC = { fan: '凡品', ling: '灵品', bao: '宝品', xuan: '玄品', di: '地品', tian: '天品' };

/**
 * 手动覆盖（drawable → source 相对路径）；优先级最高，先于 deriveSource 规则。
 * 用于：源文件名与 display name 不一致、无法用分类规则可靠推导、或经 read_image 人工确认的条目。
 */
const MANUAL_OVERRIDES = {
  // EQUIPMENT（四部位化 F3）：源文件名与注册名不一致——裂天罡煞两件
  //（官方文件在 装备/裂天罡煞/ 子目录，用「头部/上身」而非「头冠/重铠」）
  lie_tian_gang_sha_tou_guan: '装备/裂天罡煞/裂天罡煞（头部）.png',
  lie_tian_gang_sha_zhong_kai: '装备/裂天罡煞/裂天罡煞（上身）.png',
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

/**
 * 不可用源图集合（相对 SOURCE_DIR 的路径）：文件缺失之外的**内容级**故障——
 * 损坏/全零/非图片容器。登记为待补（source=null）而非映射，避免 import 阶段
 * fail-fast 卡住整条管线；美术重新导出后本集合自然为空，映射自动恢复。
 */
const unusableSources = new Set();

/** sharp 探测源图是否可解码（损坏/空文件返回 false） */
async function isDecodable(absPath) {
  try {
    const meta = await sharp(absPath).metadata();
    return Boolean(meta.width) && Boolean(meta.height);
  } catch {
    return false;
  }
}

/** 全源目录预扫描（图片文件逐个 metadata 探测） */
async function scanUnusableSources() {
  const exts = /\.(png|jpg|jpeg|webp)$/i;
  const walk = async (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, e.name);
      if (e.isDirectory()) {
        await walk(abs);
      } else if (exts.test(e.name)) {
        if (!(await isDecodable(abs))) {
          unusableSources.add(path.relative(SOURCE_DIR, abs).replace(/\\/g, '/'));
        }
      }
    }
  };
  // 源目录必然存在——resolveArtSourceDir() 缺失即抛错，此处直接全量预扫描
  await walk(SOURCE_DIR);
}

function fileExists(rel) {
  return fs.existsSync(path.join(SOURCE_DIR, rel)) && !unusableSources.has(rel);
}

/** growing_spirit* → 对应 herb_spirit* 的中文名（由资源注册表构建，见下方填充） */
let herbNameByRes = new Map();

/** 按分类给单个 registry entry 推导 source（相对路径）；null = 待补 */
function deriveSource(category, res, name) {
  // 角色素材：逐角色一目录，走显式登记表（CHARACTERS）
  if (category === 'CHARACTER') {
    const s = CHARACTER_SOURCE.get(res);
    return s && fileExists(s) ? s : null;
  }
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
        bg_recruit_normal: '背景图/普通招募背景图.png',
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
        ui_diplomacy_button: 'ui/外交.png',
        ui_guide_button: 'ui/引导按钮.png',
        ui_merchant_button: 'ui/商人按钮（圆形）.png',
        ui_build_button: 'ui/建造.png',
        ui_warehouse_button: 'ui/仓库.png',
        ui_team_button: 'ui/人物.png',
        ui_map_button: 'ui/地图.png',
        ui_planting_button: 'ui/种植ui.png',
        ui_recruit_button: 'ui/招募.png',
        ui_mail_button: 'ui/邮件.png',
        ui_menubar: 'ui/菜单栏ui.png',
        ui_recruit_once: 'ui/普通招募一次ui.png',
        ui_recruit_ten: 'ui/普通招募十次ui.png',
        ui_recruit_tab: 'ui/普通招募标签ui.png',
        ui_recruit_odds: 'ui/招募概率公示ui.png',
        ui_recruit_history: 'ui/招募历史记录ui.png',
        ui_log_button: 'ui/日志.png',
        dialog_box: 'ui/提示框背景图.png',
        ui_hide_button: 'ui/隐藏ui按钮.png',
        ui_show_button: 'ui/ui显示按钮.png',
        ui_play_button: 'ui/播放按钮（圆形）.png',
        ui_pause_button: 'ui/暂停按钮（圆形）.png',
        ui_settings_button: 'ui/设置.png',
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
        ui_check_button: 'ui/勾图标.png',
        ui_x_button: 'ui/x图标.png',
        ui_enter: 'ui/进入ui.png',
        ui_planting: 'ui/种植ui.png',
        ui_alchemy: 'ui/炼丹ui.png',
        ui_forge: 'ui/锻造ui.png',
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

// 源目录预扫描（损坏/空文件登记为不可用 → 该条目落 pending，import 保持可跑）
await scanUnusableSources();
if (unusableSources.size > 0) {
  console.log(`⚠ 源图不可用（损坏/非图片，已置为待补）: ${[...unusableSources].join(', ')}`);
}

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
    // 烘焙：小物件 maxDim 1024 / 大图 preserve；ITEM 内草药+种子是 maxDim，growing 是 preserve；
    // 角色素材按 drawable 定档（头像与立绘显示尺寸差一个量级，分类级默认表达不了）
    const bake = DRAWABLE_BAKE.get(e.res)
      ?? (cat.category === 'ITEM'
        ? (/^(herb_|seed_)/.test(e.res) ? { maxDim: 1024 } : { preserve: true })
        : (CAT_BAKE[cat.category] ?? { preserve: true }));
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

// 地图精灵（MAP）：宗门地图图集直取的装饰/地面精灵——不经 resource-registry.json
// （图集槽位与瓦片索引在 build-atlas.mjs LAYOUT.tiles 登记），但**必须**经本映射
// 才能从源图重烘焙（草皮无缝平铺 / 装饰变体 / 门楼均在此表登记烘焙规则）。
// bake 约定：装饰 preserve（槽位由图集按显示尺寸收敛）；草皮 64² 无缝平铺（seamless）；
// REPEAT 地面底色 square（须 2 的幂，GLES POT 守卫 / Vulkan REPEAT 采样器）。
// modules 缺省双模块；仅图集使用、不进 Compose 的纹理可只放 feature/game。
//
const MAP_KNOWN = [
  { drawable: 'map_grass_1', source: '宗门地图/草皮.png', bake: { maxDim: 64, seamless: true } },
  {
    drawable: 'map_rock_base',
    source: '宗门地图/底部.png',
    modules: ['feature/game'],
    bake: { square: 1024, seamless: true },
  },
  { drawable: 'sect_gate', source: '建筑/宗门门楼.png', bake: { preserve: true } },
  { drawable: 'decoration_grass1', source: '装饰物/花草1.png', bake: { preserve: true } },
  { drawable: 'decoration_grass2', source: '装饰物/花草2.png', bake: { preserve: true } },
  { drawable: 'decoration_grass3', source: '装饰物/花草3.png', bake: { preserve: true } },
  { drawable: 'decoration_grass4', source: '装饰物/花草4.png', bake: { preserve: true } },
  { drawable: 'decoration_stone1', source: '装饰物/石头1.png', bake: { preserve: true } },
  { drawable: 'decoration_stone2', source: '装饰物/石头2.png', bake: { preserve: true } },
  { drawable: 'decoration_stone3', source: '装饰物/石头3.png', bake: { preserve: true } },
  { drawable: 'decoration_tree1', source: '装饰物/树木1.png', bake: { preserve: true } },
  { drawable: 'decoration_tree2', source: '装饰物/树木2.png', bake: { preserve: true } },
];
for (const m of MAP_KNOWN) {
  const source = fileExists(m.source) ? m.source : null;
  if (!source) pending.push({ category: 'MAP', drawable: m.drawable, name: m.drawable });
  KNOWN.push({
    category: 'MAP', drawable: m.drawable, source,
    modules: m.modules ?? ['feature/game', 'app'], bake: m.bake,
  });
}

const mapping = {
  version: 1,
  description: '美术素材 source↔drawable 权威映射（由 scaffold-source-mapping.mjs 生成）。source 相对仓库根的 模拟宗门美术素材/（可用 MNZM_ART_SOURCE 覆盖）；bake.preserve=true 保留源分辨率，bake.maxDim 等比缩放最长边，bake.roundUp4=true 宽高向上取整到 4 的倍数（ASTC 压缩纹理尺寸要求），bake.seamless=true 做无缝平铺处理（草皮）。',
  sourceDir: ART_SOURCE_REL,
  bakeDefaults: {
    PILL: { maxDim: 1024 }, MATERIAL: { maxDim: 1024 }, EQUIPMENT: { maxDim: 1024 },
    STORAGE_BAG: { maxDim: 1024 }, MANUAL: { maxDim: 1024 }, SEED: { maxDim: 1024 },
    PORTRAIT: { preserve: true }, BUILDING: { preserve: true }, UI: { preserve: true },
    BACKGROUND: { preserve: true }, BEAST: { preserve: true }, CAVE: { preserve: true },
    HEAVENLY_TRIAL: { preserve: true }, MAP: { preserve: true },
    // 角色素材两档并存：立绘 1024 / 头像 512，实际值逐 drawable 落在 entries.bake
    CHARACTER: { maxDim: CHARACTER_PORTRAIT_MAX_DIM },
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

/**
 * 防「脚手架静默冲掉人工映射」——本脚本无条件整体重写 source-mapping.json，
 * 而映射里存在只能人工确认的条目（源图不在分类默认目录、单模块放置等）。
 * 旧映射有 source、新映射却丢了该条目或把它退化为 null，而该 drawable 的产物仍在
 * drawable-nodpi（= 资源还活着）⇒ 判定为真源丢失，抛错要求把该条登记进本脚本的表。
 * 确属删除素材时产物 WebP 会先被移除，本检查自动放行。
 *
 * 实测动因（G16 侦察）：`map_rock_base`（宗门地图/底部.png，仅 feature/game）整条被旧版
 * 脚手架丢弃、`map_grass_1` 因 MAP_KNOWN 记错目录（装饰物/ 而非 宗门地图/）退化为 null，
 * 两处都发生在重生成那一刻且编译与守卫均不报警。
 */
function assertNoMappingLoss(nextMapping) {
  if (!fs.existsSync(OUT_FILE)) return;
  let prev;
  try {
    prev = JSON.parse(fs.readFileSync(OUT_FILE, 'utf8'));
  } catch {
    return; // 损坏的旧文件不构成真源
  }
  const liveProducts = new Set();
  for (const dir of [
    path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi'),
    path.resolve(ANDROID_DIR, 'app/src/main/res/drawable-nodpi'),
  ]) {
    if (!fs.existsSync(dir)) continue;
    for (const f of fs.readdirSync(dir)) {
      if (/\.(webp|png|jpe?g)$/i.test(f)) liveProducts.add(f.replace(/\.[^.]+$/i, ''));
    }
  }
  const next = new Map();
  for (const cat of nextMapping.categories) {
    for (const e of cat.entries) next.set(e.drawable, e.source);
  }
  const lost = [];
  for (const cat of prev.categories ?? []) {
    for (const e of cat.entries ?? []) {
      if (!e.source || !liveProducts.has(e.drawable)) continue;
      if (next.get(e.drawable)) continue;
      lost.push(`${e.drawable}: ${next.has(e.drawable) ? 'source 退化为 null' : '条目被丢弃'}（原 source = ${e.source}）`);
    }
  }
  if (lost.length > 0) {
    throw new Error(
      `拒绝重写 source-mapping.json：以下人工映射会丢失\n  ${lost.join('\n  ')}\n` +
      '修复：把该条目登记进 scaffold-source-mapping.mjs 的表（MANUAL_OVERRIDES / MAP_KNOWN / CHARACTERS），' +
      '或确认素材已删除并先移除其 drawable-nodpi 产物。'
    );
  }
}

assertNoMappingLoss(mapping);
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
