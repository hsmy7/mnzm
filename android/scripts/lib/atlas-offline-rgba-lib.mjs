/**
 * 图集离线 RGBA 产物共享实现（B15 / R6.1）。
 *
 * 被两条路径消费，保证不漂移：
 *   1. `scripts/atlas-offline-rgba.mjs` —— 产出 `atlas-rgba-raw.bin` /
 *      `atlas-rgba-mips.bin` / `atlas-rgba-manifest.json`（构建期）；
 *   2. 守卫脚本 `scripts/verify-offline-rgba-equivalence.mjs` —— 对照验证。
 *
 * ## 槽位权威
 *
 * 槽位清单（名字 / 4096 坐标系矩形 / 源 drawable 文件）解析自
 * `core/engine/build/generated/sprite/com/xianxia/sect/core/render/SpriteAtlasDef.kt`
 * （`build-atlas.mjs --atlas-def-only` 的生成物，**已入库**），因此本库不复制
 * 布局常量——权威数值只有一份，新增精灵只改 `build-atlas.mjs LAYOUT` 再跑
 * codegen 即自动传导；**布局常量读取失败即抛错**（不允许静默用旧值）。
 *
 * 源像素文件来自 `app` 与 `feature/game` 两个 `drawable-nodpi`（文件名 ↔ 图集
 * 精灵名的映射规则与 `build-atlas.mjs` 的 `TILE_DRAWABLE` / `ROAD_DRAWABLE` /
 * `CROP_DRAWABLE` 同源）。
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
/** 本库位于 `android/scripts/lib/`，故上溯两级到 `android/` */
const ANDROID_DIR = path.resolve(__dirname, '..', '..');

/** 离线产物相对源图集的缩放系数（2048 / 4096；Canvas 软渲染封顶语义） */
export const OFFLINE_SCALE = 0.5;

/** 半透明判定阈值：alpha 低于此值的像素视为具备 alpha 语义（需预乘空间缩放） */
export const LOAD_ALPHA_THRESHOLD = 255;

/** drawable 名 → 绝对路径的候选目录（顺序 = 查找优先级） */
const DRAWABLE_DIRS = [
  path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi'),
  path.resolve(ANDROID_DIR, 'app/src/main/res/drawable-nodpi'),
];

/** SpriteAtlasDef.kt 生成物路径（入库；无需先跑 codegen） */
const ATLAS_DEF_KT = path.resolve(
  ANDROID_DIR,
  'core/engine/build/generated/sprite/com/xianxia/sect/core/render/SpriteAtlasDef.kt'
);

/**
 * 从 SpriteAtlasDef.kt 抽出 `const val NAME = 数字` 形式的布局常量。
 *
 * @param {string} ktSource 生成物源码
 * @param {string} name 常量名
 * @returns {number}
 */
function intConst(ktSource, name) {
  const m = ktSource.match(new RegExp(`const val ${name}\\s*=\\s*(\\d+)`));
  if (!m) throw new Error(`SpriteAtlasDef.kt: 未找到常量 ${name}`);
  return +m[1];
}

/**
 * 解析 `enum class X(...) { A(i, SpriteRect(x, y, w, h)), ... }` 形式的枚举。
 *
 * 生成物形态（稳定，见 build-atlas.mjs 模板）：
 *   TileType:  `GROUND(0, SpriteRect(0, 0, 128, 128)),`
 *   CropStage: `SEEDLING(SpriteRect(1384, 0, 128, 128)),`   ← 无 index 形参
 * 故索引段（`\d+,`）按可选处理。
 *
 * @returns {Array<{name: string, x: number, y: number, w: number, h: number}>}
 */
function parseRectEnum(ktSource, enumName) {
  const block = ktSource.match(new RegExp(`enum class ${enumName}\\([\\s\\S]*?\\n    \\}`));
  if (!block) throw new Error(`SpriteAtlasDef.kt: 未找到枚举 ${enumName}`);
  const out = [];
  const re = /^\s*([A-Z0-9_]+)\(\s*(?:\d+\s*,\s*)?SpriteRect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)/gm;
  for (const m of block[0].matchAll(re)) {
    out.push({ name: m[1], x: +m[2], y: +m[3], w: +m[4], h: +m[5] });
  }
  if (out.length === 0) throw new Error(`SpriteAtlasDef.kt: 枚举 ${enumName} 未解析到任何条目`);
  return out;
}

/** 解析 `val NAME = listOf("a", "b", ...)` 形式的字符串表 */
function parseStringList(ktSource, constName) {
  const m = ktSource.match(new RegExp(`val ${constName}\\s*=\\s*listOf\\(([\\s\\S]*?)\\n    \\)`));
  if (!m) throw new Error(`SpriteAtlasDef.kt: 未找到字符串表 ${constName}`);
  return [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]);
}

/**
 * 解析 `val NAME: List<Pair<String, SpriteRect>> = listOf("n" to SpriteRect(...), ...)`。
 */
function parseNamedRectPairs(ktSource, constName) {
  const m = ktSource.match(
    new RegExp(`val ${constName}[^=]*=\\s*listOf\\(([\\s\\S]*?)\\n    \\)`)
  );
  if (!m) throw new Error(`SpriteAtlasDef.kt: 未找到命名矩形表 ${constName}`);
  const out = [];
  const re = /"([^"]+)"\s*to\s*SpriteRect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)/g;
  for (const mm of m[1].matchAll(re)) {
    out.push({ name: mm[1], x: +mm[2], y: +mm[3], w: +mm[4], h: +mm[5] });
  }
  if (out.length === 0) throw new Error(`SpriteAtlasDef.kt: ${constName} 未解析到任何条目`);
  return out;
}

/** 解析 `data class StructureDef(...)` 表（取 key + rect） */
function parseStructures(ktSource) {
  const m = ktSource.match(/val STRUCTURES\s*=\s*listOf\(([\s\S]*?)\n    \)/);
  if (!m) throw new Error('SpriteAtlasDef.kt: 未找到 STRUCTURES');
  const out = [];
  const re = /StructureDef\(\s*"[^"]*"\s*,\s*"([^"]+)"\s*,\s*SpriteRect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)/g;
  for (const mm of m[1].matchAll(re)) {
    out.push({ name: mm[1], x: +mm[2], y: +mm[3], w: +mm[4], h: +mm[5] });
  }
  if (out.length === 0) throw new Error('SpriteAtlasDef.kt: STRUCTURES 未解析到任何条目');
  return out;
}

/** 解析 `BUILDING_RECT_OVERRIDES`：`9 to SpriteRect(3008, 1032, 1024, 1024)` */
function parseBuildingOverrides(ktSource) {
  const m = ktSource.match(/BUILDING_RECT_OVERRIDES[^=]*=\s*mapOf\(([\s\S]*?)\n    \)/);
  if (!m) throw new Error('SpriteAtlasDef.kt: 未找到 BUILDING_RECT_OVERRIDES');
  const map = new Map();
  const re = /(\d+)\s*to\s*SpriteRect\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(\d+)\s*,\s*(\d+)/g;
  for (const mm of m[1].matchAll(re)) {
    map.set(+mm[1], { x: +mm[2], y: +mm[3], w: +mm[4], h: +mm[5] });
  }
  return map;
}

/** drawable 名 → 绝对路径（两个 nodpi 目录按序查找；找不到抛错——不允许静默跳过） */
export function resolveDrawablePath(drawableName) {
  for (const dir of DRAWABLE_DIRS) {
    for (const ext of ['.webp', '.png', '.jpg']) {
      const p = path.join(dir, `${drawableName}${ext}`);
      if (fs.existsSync(p)) return p;
    }
  }
  throw new Error(
    `drawable '${drawableName}' 未在两个 drawable-nodpi 目录中找到` +
      `（${DRAWABLE_DIRS.join(' / ')}）`
  );
}

/** 瓦片类型 → drawable 名（与 build-atlas.mjs TILE_DRAWABLE 同源）；null = 占位无精灵 */
export function tileDrawableName(tileName) {
  const map = {
    GROUND: 'map_grass_1',
    GRASS1: 'decoration_grass1',
    GRASS2: 'decoration_grass2',
    GRASS3: 'decoration_grass3',
    GRASS4: 'decoration_grass4',
    STONE1: 'decoration_stone1',
    STONE2: 'decoration_stone2',
    STONE3: 'decoration_stone3',
    TREE1: 'decoration_tree1',
    TREE2: 'decoration_tree2',
    TILE_BUILDING: null,
  };
  if (!(tileName in map)) throw new Error(`瓦片 ${tileName} 未在本库 drawable 映射中登记`);
  return map[tileName];
}

/** 作物阶段 → drawable 名（与 build-atlas.mjs CROP_DRAWABLE 同源，按 ordinal） */
const CROP_DRAWABLE = ['growing_spiritgrass7', 'growing_spiritgrass8', 'growing_spiritgrass9'];

/**
 * 建筑**图集精灵名** → drawable 名。
 *
 * 键必须与 `SpriteAtlasDef.BUILDING_NAMES` 逐字一致（图集名，非显示名）——
 * 住所类建筑显示名带分级前缀（「初级单人住所」）而图集名保持历史名称
 * （「单人住所」），按显示名建映射会查空。
 *
 * 权威源：`feature/game/.../building/BuildingFeatureBoot.kt` 的
 * `BuildingFeature(spriteName ?: displayName, ..., drawableRes = R.drawable.X)`。
 * 本表是该注册表在**构建期**的只读快照（Kotlin 侧 `effectiveSpriteName()` 的
 * 对位实现）——新增建筑须两侧同步，`BuildingFeatureRegistry` 侧有守卫测试，
 * 本侧由 `atlas-offline-rgba.mjs` 的 fail-fast 兜底（缺项即构建失败）。
 */
const BUILDING_DRAWABLE = {
  灵矿场: 'building_spirit_mine',
  灵植阁: 'building_herb_garden',
  灵田: 'building_spirit_field',
  炼丹炉: 'building_alchemy',
  锻造坊: 'building_forge',
  仓库: 'building_warehouse',
  藏经阁: 'building_library',
  问道塔: 'building_wen_dao_peak',
  青云塔: 'building_qingyun_peak',
  天枢殿: 'building_tianshu_hall',
  执法堂: 'building_law_enforcement',
  任务阁: 'building_mission_hall',
  巡视楼: 'building_patrol_tower',
  监牢: 'building_reflection_cliff',
  单人住所: 'building_single_residence',
  中级单人住所: 'building_single_residence_upgraded',
  多人住所: 'building_multi_residence',
  中级多人住所: 'building_multi_residence_upgraded',
  血炼池: 'blood_refining_pool',
};

/**
 * 建筑图集名 → drawable 名（未登记即抛错——图集名与注册表漂移必须暴露）。
 */
export function buildingDrawableName(atlasName) {
  const n = BUILDING_DRAWABLE[atlasName];
  if (n === undefined) {
    throw new Error(
      `建筑图集名 '${atlasName}' 未在本库 BUILDING_DRAWABLE 中登记——` +
        '新增建筑须同步 BuildingFeatureBoot.kt 的 spriteName 与 drawableRes'
    );
  }
  return n;
}

/**
 * 构建离线产物的槽位清单（`SpriteAtlasDef.kt` 权威 + drawable 解析）。
 *
 * **顺序与 build-atlas.mjs buildSpriteList 完全一致**
 * （tile → crops → buildings → structures → clouds → roads），
 * 因为运行时 `TileType.ordinal` / `CropStage.ordinal` / 建筑索引都依赖段内序。
 *
 * @param {boolean} [skipMissing] true = 缺源图的精灵跳过（不抛错）；
 *   默认 false（**fail-fast**：布局与 drawable 漂移必须暴露）
 * @returns {Promise<Array<{name: string, x: number, y: number, w: number, h: number, srcPath: string}>>}
 */
export async function buildOfflineSpriteList({ skipMissing = false } = {}) {
  if (!fs.existsSync(ATLAS_DEF_KT)) {
    throw new Error(
      `SpriteAtlasDef.kt 生成物缺失：${ATLAS_DEF_KT}\n` +
        '  先运行 node scripts/build-atlas.mjs --atlas-def-only（或 ./gradlew generateSpriteAtlasDef）'
    );
  }
  const kt = fs.readFileSync(ATLAS_DEF_KT, 'utf8');
  const list = [];

  const push = (entry, drawableName) => {
    if (drawableName == null) return; // 占位条目（TILE_BUILDING）
    try {
      list.push({ ...entry, srcPath: resolveDrawablePath(drawableName) });
    } catch (e) {
      if (!skipMissing) throw e;
    }
  };

  // 1. 瓦片段（含 TILE_BUILDING 占位——运行时槽位索引须与 TileType.ordinal 对齐）
  //    占位条目不入产物（无 drawable），但必须经本段显式判定而非被静默过滤。
  for (const t of parseRectEnum(kt, 'TileType')) {
    push(t, tileDrawableName(t.name));
  }

  // 2. 作物段
  const crops = parseRectEnum(kt, 'CropStage');
  crops.forEach((c, i) => push(c, CROP_DRAWABLE[i]));

  // 3. 建筑段（BUILDING_NAMES × buildingRect 复现：override → 行公式）
  const names = parseStringList(kt, 'BUILDING_NAMES');
  const overrides = parseBuildingOverrides(kt);
  const colsPerRow = intArrayConst(kt, 'BUILDING_COLS_PER_ROW');
  const bSize = intConst(kt, 'BUILDING_SIZE');
  const bPitch = intConst(kt, 'BUILDING_PITCH');
  const bOriginY = intConst(kt, 'BUILDING_GRID_ORIGIN_Y');
  names.forEach((name, nameIndex) => {
    push(
      { name, ...buildingRectOf(nameIndex, overrides, colsPerRow, bSize, bPitch, bOriginY) },
      buildingDrawableName(name)
    );
  });

  // 4. 固定结构段
  for (const s of parseStructures(kt)) push(s, s.name);

  // 5. 云层段
  for (const c of parseNamedRectPairs(kt, 'CLOUD_RECTS')) push(c, c.name);

  // 6. 道路段
  for (const r of parseNamedRectPairs(kt, 'ROAD_RECTS')) push(r, r.name);

  return list;
}

/** 解析 `private val NAME = intArrayOf(5, 5, 5, 4)` */
function intArrayConst(ktSource, name) {
  const m = ktSource.match(new RegExp(`val ${name}\\s*=\\s*intArrayOf\\(([^)]*)\\)`));
  if (!m) throw new Error(`SpriteAtlasDef.kt: 未找到 intArrayOf 常量 ${name}`);
  return m[1].split(',').map((s) => +s.trim()).filter((n) => !Number.isNaN(n));
}

/**
 * 复现 `SpriteAtlasDef.buildingRect(nameIndex)`（与生成物实现同式——override 优先，
 * 否则行公式 `col × PITCH / ORIGIN_Y + rowIndex × PITCH`）。
 */
export function buildingRectOf(nameIndex, overrides, colsPerRow, size, pitch, originY) {
  const ov = overrides.get(nameIndex);
  if (ov) return ov;
  let idx = 0;
  for (let rowIndex = 0; rowIndex < colsPerRow.length; rowIndex++) {
    for (let col = 0; col < colsPerRow[rowIndex]; col++) {
      if (idx === nameIndex) {
        return { x: col * pitch, y: originY + rowIndex * pitch, w: size, h: size };
      }
      idx++;
    }
  }
  throw new Error(`buildingRect(${nameIndex}) 越界（BUILDING_COLS_PER_ROW 总格数不足）`);
}

/**
 * 源图解码 → **预乘** RGBA（`build-atlas.mjs loadSpriteContents` 与离线产物管线
 * 共用的**唯一**加载口径）。
 *
 * 抽取为独立函数的理由（等价性可证明性）：`build-atlas.mjs` 的 ASTC 图集与
 * 本库的离线 RGBA 产物必须**同源同配方**——同一份 drawable、同一套预乘/解码
 * 语义。共享此函数后，「两条图集路径的精灵内容一致」是**结构事实**而非需要
 * 逐次比对的断言（见 `verify-offline-rgba-equivalence.mjs` 层 3 的确定性校验）。
 *
 * @param {string} srcPath 源图路径
 * @returns {Promise<{pm: Buffer, width: number, height: number, transparent: boolean}>}
 *   `pm` 为预乘 RGBA8888；`transparent` = 存在 alpha < 255 的像素
 */
export async function loadPremultipliedRgba(srcPath) {
  const meta = await sharp(srcPath).metadata();
  const { data, info } = await sharp(srcPath)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  // 无 alpha 源：无需预乘往返（与 build-atlas.mjs 的 !meta.hasAlpha 分支同义）
  const transparent = meta.hasAlpha === true && hasNonOpaque(data, LOAD_ALPHA_THRESHOLD);
  return {
    pm: transparent ? premultiplyRgba(data) : data,
    width: info.width,
    height: info.height,
    transparent,
  };
}

/**
 * 把源图缩放并转换为直通 alpha RGBA 缓冲。
 *
 * 缩放语义：`sharp.resize(dstW, dstH, { fit: 'fill', kernel: lanczos3 })`——
 * 与 `build-atlas.mjs loadSpriteContents` 的槽位缩放**同核同参**（保证离线产物
 * 与 ASTC 图集的精灵内容来自同一套缩放口径）。
 *
 * 预乘处理：与 build-atlas 一致，先预乘再缩放（避免透明边缘的黑色渗入），
 * 缩放后**解回直通 alpha** 输出。
 *
 * @param {string} srcPath 源图路径
 * @param {number} w 源槽位宽（4096 坐标系）
 * @param {number} h 源槽位高
 * @param {number} scale 缩放系数（0.5）
 * @returns {Promise<{data: Buffer, premultiplied: boolean}>} RGBA8888 直通 alpha 缓冲
 */
export async function extractSlotRgba(srcPath, w, h, scale) {
  const { pm, width, height, transparent } = await loadPremultipliedRgba(srcPath);
  const dw = Math.max(1, Math.round(w * scale));
  const dh = Math.max(1, Math.round(h * scale));
  const scaledPm = await sharp(pm, {
    raw: { width, height, channels: 4 },
  })
    .resize(dw, dh, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .raw()
    .toBuffer();
  return {
    data: transparent ? unpremultiplyRgba(scaledPm) : scaledPm,
    premultiplied: transparent,
  };
}


/** 是否存在 alpha < threshold 的像素（判定是否需要走预乘空间） */
function hasNonOpaque(rgba, threshold) {
  for (let i = 3; i < rgba.length; i += 4) {
    if (rgba[i] < threshold) return true;
  }
  return false;
}

/** 直通 alpha → 预乘（`r' = round(r·a/255)`）；字节序无关 */
export function premultiplyRgba(rgba) {
  const out = Buffer.allocUnsafe(rgba.length);
  for (let i = 0; i < rgba.length; i += 4) {
    const a = rgba[i + 3];
    out[i] = Math.round((rgba[i] * a) / 255);
    out[i + 1] = Math.round((rgba[i + 1] * a) / 255);
    out[i + 2] = Math.round((rgba[i + 2] * a) / 255);
    out[i + 3] = a;
  }
  return out;
}

/** 预乘 → 直通 alpha（`r = a == 0 ? 0 : min(255, round(r·255/a))`） */
export function unpremultiplyRgba(pm) {
  const out = Buffer.allocUnsafe(pm.length);
  for (let i = 0; i < pm.length; i += 4) {
    const a = pm[i + 3];
    if (a === 0) {
      out[i] = 0;
      out[i + 1] = 0;
      out[i + 2] = 0;
      out[i + 3] = 0;
      continue;
    }
    out[i] = Math.min(255, Math.round((pm[i] * 255) / a));
    out[i + 1] = Math.min(255, Math.round((pm[i + 1] * 255) / a));
    out[i + 2] = Math.min(255, Math.round((pm[i + 2] * 255) / a));
    out[i + 3] = a;
  }
  return out;
}

/**
 * 精确 2:1 盒式下采样（每 2×2 均值，四舍五入）。
 *
 * 字节序无关（逐字节处理），且与 `Bitmap.createScaledBitmap(filter=true)` 在
 * 严格 2:1 下的面积平均语义对齐——参见 `atlas-offline-rgba.mjs` 头注的守卫口径。
 *
 * @param {Buffer} src RGBA8888 源缓冲
 * @param {number} w 源宽（偶数）
 * @param {number} h 源高（偶数）
 */
export function downsampleBoxHalf(src, w, h) {
  const dw = w >> 1;
  const dh = h >> 1;
  const out = Buffer.allocUnsafe(dw * dh * 4);
  for (let y = 0; y < dh; y++) {
    const r0 = y * 2 * w * 4;
    const r1 = (y * 2 + 1) * w * 4;
    for (let x = 0; x < dw; x++) {
      const c0 = r0 + x * 8;
      const c1 = r1 + x * 8;
      const o = (y * dw + x) * 4;
      for (let k = 0; k < 4; k++) {
        out[o + k] = Math.round((src[c0 + k] + src[c0 + 4 + k] + src[c1 + k] + src[c1 + 4 + k]) / 4);
      }
    }
  }
  return out;
}

// ── 离线 RGBA 产物写出（build-atlas.mjs 图集模式内联调用） ──

/** 离线产物边长（源图集边长 × [OFFLINE_SCALE]） */
export const DOWN_SIZE = 2048;
/** mip 链最小边长（与运行时 encodeBitmapToRgbaMipChain 同口径：>2 才继续） */
const MIP_MIN_EDGE = 2;

/** 原子写：先写 .tmp 再 rename（避免半截产物被 Gradle up-to-date 判定为完整） */
function writeAtomic(file, buf) {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, buf);
  fs.renameSync(tmp, file);
}

/** 校验：降采样后任意两精灵不得重叠（保证 Canvas `over` 覆盖顺序不产生歧义） */
function assertNoOverlap(placements) {
  for (let i = 0; i < placements.length; i++) {
    const a = placements[i];
    for (let j = i + 1; j < placements.length; j++) {
      const b = placements[j];
      const hitX = a.x < b.x + b.w && b.x < a.x + a.w;
      const hitY = a.y < b.y + b.h && b.y < a.y + a.h;
      if (hitX && hitY) {
        throw new Error(
          `降采样槽位重叠: ${a.name}(${a.x},${a.y},${a.w},${a.h}) 与 ` +
            `${b.name}(${b.x},${b.y},${b.w},${b.h})——over 覆盖顺序会改变像素结果`
        );
      }
    }
  }
}

/**
 * 产出离线 RGBA 降采样三项资产到 `assets/atlas/`。
 *
 * **在 build-atlas.mjs 图集模式内联调用**——复用其已加载好的槽位内容
 * （`contents`，与 ASTC 图集**同一份** premultiply + lanczos3 缩放产物），
 * 故「离线 RGBA 产物与 ASTC 图集的精灵内容同源」是结构事实。
 *
 * 降采样配方（与 B15 前 `SectAtlasAssembler.buildAtlasBitmap` 的 Canvas 语义对位）：
 *   1. 每精灵独立 `resize(slot/2)`（内容已在槽位尺寸，此处再去一半）；
 *   2. 落位 `round(x × 0.5)`；相邻槽位取整重叠区**丢弃**（= Canvas `over` 覆盖）；
 *   3. 2048 下落位**无重叠**（自动化断言），故覆盖顺序无歧义。
 *
 * 产物为**直通 alpha**（`unpremultiplyRgba`；Canvas 位图 ARGB_8888 亦为直通语义，
 * 逐字节 R,G,B,A 与 ARGB 在小端内存布局上等值——运行时 `copyPixelsFromBuffer`
 * 无需 swizzle）。
 *
 * @param {Map<string, Buffer>} contents 图集名 → 槽位 PNG（预乘，build-atlas 产物）
 * @param {Set<string>} transparentSprites 走预乘路径的精灵名
 * @param {Array} sprites 精灵清单（name/x/y/w/h）
 * @param {string} outDir 产物目录（app/src/main/assets/atlas）
 * @returns {Promise<object>} manifest 对象
 */
export async function writeOfflineRgbaArtifacts(contents, transparentSprites, sprites, outDir) {
  const frame = Buffer.alloc(DOWN_SIZE * DOWN_SIZE * 4, 0);
  const placements = [];

  for (const s of sprites) {
    const slotPng = contents.get(s.name);
    if (!slotPng) continue; // drawable=null 占位条目
    const dw = Math.max(1, s.w >> 1);
    const dh = Math.max(1, s.h >> 1);
    const dx = Math.round(s.x * OFFLINE_SCALE);
    const dy = Math.round(s.y * OFFLINE_SCALE);
    if (dx + dw > DOWN_SIZE || dy + dh > DOWN_SIZE) {
      throw new Error(`精灵 ${s.name} 降采样越界: (${dx},${dy})+${dw}×${dh} > ${DOWN_SIZE}²`);
    }
    // 槽位内容 → 目标尺寸（预乘空间缩放，与其余管线一致）
    const scaledPm = await sharp(slotPng)
      .resize(dw, dh, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
      .ensureAlpha()
      .raw()
      .toBuffer();
    // 解预乘 → 直通 alpha（位图/C++ 纹理均为直通；全不透明精灵免此步）
    const slot = transparentSprites.has(s.name) ? unpremultiplyRgba(scaledPm) : scaledPm;
    if (slot.length !== dw * dh * 4) {
      throw new Error(`精灵 ${s.name} 降采样缓冲尺寸非法: ${slot.length} != ${dw * dh * 4}`);
    }
    for (let y = 0; y < dh; y++) {
      const srcOff = y * dw * 4;
      const dstOff = ((dy + y) * DOWN_SIZE + dx) * 4;
      slot.copy(frame, dstOff, srcOff, srcOff + dw * 4);
    }
    placements.push({ name: s.name, x: dx, y: dy, w: dw, h: dh });
  }
  assertNoOverlap(placements);

  const rawPath = path.join(outDir, 'atlas-rgba-raw.bin');
  writeAtomic(rawPath, frame);

  // mip 链（精确 2:1 盒式级联，level-major 紧凑）
  const levels = [];
  let cur = frame;
  let w = DOWN_SIZE;
  let h = DOWN_SIZE;
  levels.push({ buf: cur, w, h });
  while (w > MIP_MIN_EDGE && h > MIP_MIN_EDGE) {
    cur = downsampleBoxHalf(cur, w, h);
    w >>= 1;
    h >>= 1;
    levels.push({ buf: cur, w, h });
  }
  const chain = Buffer.concat(levels.map((l) => l.buf));
  writeAtomic(path.join(outDir, 'atlas-rgba-mips.bin'), chain);

  const manifest = {
    version: 1,
    kind: 'offline-rgba',
    format: 'RGBA8888',
    /** 源图集边长（若权威常量改尺寸本产物须重新生成） */
    sourceSize: DOWN_SIZE / OFFLINE_SCALE,
    /** 离线产物边长（Canvas 软渲染 + RGBA 回退共用的 2048 封顶语义） */
    width: DOWN_SIZE,
    height: DOWN_SIZE,
    scale: OFFLINE_SCALE,
    rawFile: 'atlas-rgba-raw.bin',
    rawBytes: frame.length,
    mipMode: 'box-2x1',
    mipLevels: levels.length,
    mipWidths: levels.map((l) => l.w),
    mipFile: 'atlas-rgba-mips.bin',
    mipBytes: chain.length,
    spriteCount: placements.length,
    transparentSpriteCount: placements.filter((p) => transparentSprites.has(p.name)).length,
    frameSha256: sha256hex(frame),
    sprites: placements.map((p) => {
      const rows = [];
      for (let y = 0; y < p.h; y++) {
        const off = ((p.y + y) * DOWN_SIZE + p.x) * 4;
        rows.push(frame.subarray(off, off + p.w * 4));
      }
      const slot = Buffer.concat(rows);
      return { name: p.name, sha256: sha256hex(slot), bytes: slot.length };
    }),
    generatedAt: new Date().toISOString(),
  };
  writeAtomic(
    path.join(outDir, 'atlas-rgba-manifest.json'),
    Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  );
  return manifest;
}

function sha256hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

