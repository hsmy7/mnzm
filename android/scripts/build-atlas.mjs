/**
 * 图集 KTX 生成管线（WP7 Vulkan ASTC 压缩）+ 资源 codegen（Godot 导入管线对标）。
 *
 * 输入（唯一权威源，脚本只读不写）：
 * - 本文件 LAYOUT 常量 — 图集布局（rect/名称/枚举/占地，从原 SpriteAtlasDef.kt 提取）
 * - scripts/resource-registry.json — 精灵注册源数据（分类 → name/res 映射）
 * - scripts/resource-manifest.mjs 生成的 atlas-manifest.json — 资源文件清单（MD5/UID）
 * - feature/game 与 app 的 drawable-nodpi/*.webp — 精灵资源
 *
 * 输出：
 * - core/engine/build/generated/sprite/.../SpriteAtlasDef.kt — Kotlin 图集布局常量（--atlas-def-only）
 * - app/build/generated/sprite/SpriteRegistryData.kt — 精灵注册数据（--codegen）
 * - app/build/generated/sprite/TextureAtlas.h — C++ MAP_SPRITES（--codegen）
 * - app/src/main/assets/atlas/atlas_astc.ktx — KTX1 封装 ASTC 4×4 LDR 图集（无参数模式）
 * - app/src/main/assets/atlas/atlas-manifest.json — 图集布局清单 + 布局哈希（无参数模式）
 *
 * hash 增量：生成物首次生成后记录内容 hash（非 mtime），源数据/manifest 未变时跳过重生成。
 *
 * 依赖：Node + sharp（scripts/node_modules）、astcenc（scripts/tools/astcenc/bin/）。
 * 运行：node scripts/build-atlas.mjs [--atlas-def-only | --codegen]
 * astcenc 缺失时给出明确错误并退出非零（由 gradle task 决定是否跳过）。
 */
import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';
import { ensureManifest } from './resource-manifest.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');

// ── 路径 ──
const REGISTRY_FILE = path.resolve(__dirname, 'resource-registry.json');
const APP_DRAWABLE_DIR = path.resolve(ANDROID_DIR, 'app/src/main/res/drawable-nodpi');
const GAME_DRAWABLE_DIR = path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi');
const MANIFEST_OUT = path.resolve(ANDROID_DIR, 'app/build/generated/sprite/atlas-manifest.json');
const ATLAS_DEF_OUT_DIR = path.resolve(ANDROID_DIR, 'core/engine/build/generated/sprite');
const SPRITE_CODE_OUT_DIR = path.resolve(ANDROID_DIR, 'app/build/generated/sprite');
const ASTCENC_DIR = path.resolve(ANDROID_DIR, 'scripts/tools/astcenc/bin');
const OUT_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');
const TMP_PNG = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.png');
const TMP_ASTC = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.astc');

// ASTC 4×4 块尺寸（astcenc -cl 4x4）
const ASTC_BLOCK = 4;
const ASTC_BLOCK_BYTES = 16;
const GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
const GL_RGBA = 0x1908;

/**
 * 图集布局源数据（唯一权威——由原 core/engine/.../SpriteAtlasDef.kt 与
 * app/src/main/cpp/TextureAtlas.h 提取，两个生成物与图集拼装共用）。
 * 修改布局只改这里，运行 codegen 后 Kotlin/C++ 双端自动同步。
 */
const LAYOUT = {
  atlasW: 2048,
  atlasH: 2048,
  tileSize: 64,
  buildingSize: 256,
  // 瓦片（TileType：名称/index/rect）
  tiles: [
    { name: 'GROUND', index: 0, rect: [0, 0, 64, 64] },
    { name: 'GRASS_SMALL', index: 1, rect: [64, 0, 64, 64] },
    { name: 'GRASS_MEDIUM', index: 2, rect: [128, 0, 64, 64] },
    { name: 'GRASS_LARGE', index: 3, rect: [192, 0, 64, 64] },
    { name: 'TREE1', index: 4, rect: [256, 0, 128, 128] },
    { name: 'TREE2', index: 5, rect: [384, 0, 128, 128] },
    { name: 'TILE_BUILDING', index: 6, rect: [0, 0, 64, 64] }, // 占位（与 GROUND 重叠）
  ],
  // 建筑（BUILDING_NAMES，按图集排列顺序）
  buildingNames: [
    '灵矿场', '灵植阁', '灵田', '炼丹炉', '锻造坊',
    '仓库', '藏经阁', '问道塔', '青云塔', '天枢殿',
    '执法堂', '任务阁', '巡视楼', '监牢',
    '单人住所', '中级单人住所', '多人住所', '血炼池',
    '中级多人住所',
  ],
  buildingColsPerRow: [5, 5, 5, 4],
  // 建筑专属槽位覆盖（图集名 → 自定义 rect）：默认所有建筑走行公式 256×256 槽位；
  // 大显示尺寸建筑（天枢殿 18×15 格 ≈ 576×480 世界像素）分配 512×512 高清槽位，
  // 显示放大比从 2.25x 降至 ~1.1x（2026-08-23 清晰度根治）。位置须避开建筑行区
  // （y=256~1280, x=0~1280）、地砖列（x=1280~1536, y=256~1152）、门楼（1536,256,384,256）
  // 与云层区（y≥1408）——置于右侧 (1536,512) 起 512×512。
  buildingRectOverrides: {
    '天枢殿': [1536, 512, 512, 512],
  },
  // 占地尺寸（FOOTPRINT_BY_NAME_INDEX，按建筑索引）
  footprints: [
    [4, 4], [4, 3], [1, 1], [4, 3], [5, 3], [6, 4], [6, 3], [4, 3], [4, 3], [18, 13],
    [6, 3], [4, 3], [4, 3], [4, 4], [4, 4], [6, 6], [6, 4], [4, 4], [6, 5],
  ],
  // 灵田作物三阶段（CropStage）
  crops: [
    { name: 'SEEDLING', rect: [832, 0, 64, 64] },
    { name: 'GROWING', rect: [896, 0, 64, 64] },
    { name: 'MATURE', rect: [960, 0, 64, 64] },
  ],
  // 地砖（FloorTileType：名称/key/占地/rect）。
  // 2026-08 建筑槽位 128→256 后图集重排：建筑区 4 行 × 256px（行公式 y=256/512/768/1024，
  // x=0~1280），地砖与固定结构移入建筑区右侧列（x≥1280）。
  floors: [
    { name: 'TILE_2x2', key: 'floor_tile_2x2', gridW: 2, gridH: 2, rect: [1280, 256, 128, 128] },
    { name: 'TILE_2x3', key: 'floor_tile_2x3', gridW: 2, gridH: 3, rect: [1280, 384, 128, 192] },
    { name: 'TILE_3x2', key: 'floor_tile_3x2', gridW: 3, gridH: 2, rect: [1280, 576, 192, 128] },
    { name: 'TILE_3x3', key: 'floor_tile_3x3', gridW: 3, gridH: 3, rect: [1280, 704, 192, 192] },
    { name: 'SPIRIT_MINE_GROUND', key: 'spirit_mine_ground', gridW: 4, gridH: 4, rect: [1280, 896, 256, 256] },
  ],
  // 固定结构（宗门入口门楼——渲染走建筑层，nameIdx = BUILDING_NAMES.size + index）
  structures: [
    { name: '宗门门楼', key: 'sect_gate', rect: [1536, 256, 384, 256], footprint: [6, 2], spriteSize: [6, 4] },
  ],
  // 石板道路系统（RoadSprite：主体/路口/边缘条/转角/十字装饰）
  // 渲染叠加层按位掩码合成：直路用 base，转角/T/十字用 junction，
  // 外缘描边条用 edge_*，外角用 corner_*，十字中心装饰用 cross_center。
  // 槽位取自 y=0..128 空闲行（x≥1024）与 y=1024..1280 右列"×256"空闲区，均不与其他条目重叠。
  roads: [
    { name: 'road_base',       rect: [1024, 0, 64, 64] },
    { name: 'road_base_v',     rect: [1088, 0, 64, 64] },
    { name: 'road_junction',   rect: [1152, 0, 64, 64] },
    { name: 'road_edge_h',     rect: [1216, 0, 64, 16] },
    { name: 'road_edge_v',     rect: [1216, 16, 16, 64] },
    { name: 'road_corner_tr',  rect: [1232, 64, 48, 48] },
    { name: 'road_corner_tl',  rect: [1280, 64, 48, 48] },
    { name: 'road_corner_br',  rect: [1328, 64, 48, 48] },
    { name: 'road_corner_bl',  rect: [1376, 64, 48, 48] },
    { name: 'road_cross_center', rect: [1024, 1024, 256, 256] },
  ],
  // 云层精灵（世界顶部动态云朵的图集槽位——仅提供精灵，位置/运动由
  // CloudLayerAnimator 逐帧驱动。放在图集 y≥1408 空闲区，保持源素材纵横比）
  clouds: [
    { name: 'cloud_1', rect: [0, 1408, 484, 120] },
    { name: 'cloud_2', rect: [484, 1408, 452, 188] },
    { name: 'cloud_3', rect: [936, 1408, 488, 96] },
    { name: 'cloud_4', rect: [0, 1620, 524, 108] },
    { name: 'cloud_5', rect: [524, 1620, 472, 200] },
  ],
  // 双端共享渲染常量（原 NativeBridge.cpp / RenderLodPolicy.kt / BuildingRenderGeometry.kt
  // 三处同值手工同步——2026-08-13 收敛为单一数据源，Kotlin/C++ 双产物自动一致）
  lodThreshold: 0.6,
  shadowOffsetTiles: 0.25,
  shadowAlpha: 0.2,
  // C++ MAP_SPRITES（由原 TextureAtlas.h 提取——C++ 命名与 Kotlin 枚举名不同，单独维护）
  // 2026-08 建筑槽位 128→256：建筑 rect 按行公式 [5,5,5,4] × 256px 同步；
  // 地砖/结构随 LAYOUT.floors/structures 重排到建筑区右侧列（x≥1280）。
  mapSprites: [
    { name: 'ground_tile', rect: [0, 0, 64, 64] },
    { name: 'grass_small', rect: [64, 0, 64, 64] },
    { name: 'grass_medium', rect: [128, 0, 64, 64] },
    { name: 'grass_large', rect: [192, 0, 64, 64] },
    { name: 'tree1', rect: [256, 0, 128, 128] },
    { name: 'tree2', rect: [384, 0, 128, 128] },
    { name: 'crop_seedling', rect: [832, 0, 64, 64] },
    { name: 'crop_growing', rect: [896, 0, 64, 64] },
    { name: 'crop_mature', rect: [960, 0, 64, 64] },
    { name: '灵矿场', rect: [0, 256, 256, 256] },
    { name: '灵植阁', rect: [256, 256, 256, 256] },
    { name: '灵田', rect: [512, 256, 256, 256] },
    { name: '炼丹炉', rect: [768, 256, 256, 256] },
    { name: '锻造坊', rect: [1024, 256, 256, 256] },
    { name: '仓库', rect: [0, 512, 256, 256] },
    { name: '藏经阁', rect: [256, 512, 256, 256] },
    { name: '问道塔', rect: [512, 512, 256, 256] },
    { name: '青云塔', rect: [768, 512, 256, 256] },
    { name: '天枢殿', rect: [1536, 512, 512, 512] },  // 专属 512×512 高清槽位（buildingRectOverrides）
    { name: '执法堂', rect: [0, 768, 256, 256] },
    { name: '任务阁', rect: [256, 768, 256, 256] },
    { name: '巡视楼', rect: [512, 768, 256, 256] },
    { name: '监牢', rect: [768, 768, 256, 256] },
    { name: '单人住所', rect: [1024, 768, 256, 256] },
    { name: '中级单人住所', rect: [0, 1024, 256, 256] },
    { name: '多人住所', rect: [256, 1024, 256, 256] },
    { name: '血炼池', rect: [512, 1024, 256, 256] },
    { name: '中级多人住所', rect: [768, 1024, 256, 256] },
    { name: 'floor_tile_2x2', rect: [1280, 256, 128, 128] },
    { name: 'floor_tile_2x3', rect: [1280, 384, 128, 192] },
    { name: 'floor_tile_3x2', rect: [1280, 576, 192, 128] },
    { name: 'floor_tile_3x3', rect: [1280, 704, 192, 192] },
    { name: 'spirit_mine_ground', rect: [1280, 896, 256, 256] },
    { name: 'sect_gate', rect: [1536, 256, 384, 256] },
    { name: 'cloud_1', rect: [0, 1408, 484, 120] },
    { name: 'cloud_2', rect: [484, 1408, 452, 188] },
    { name: 'cloud_3', rect: [936, 1408, 488, 96] },
    { name: 'cloud_4', rect: [0, 1620, 524, 108] },
    { name: 'cloud_5', rect: [524, 1620, 472, 200] },
    // 石板道路系统（与 LAYOUT.roads 同源；C++ 经 getRegion("road_*") 取 UV）
    { name: 'road_base', rect: [1024, 0, 64, 64] },
    { name: 'road_base_v', rect: [1088, 0, 64, 64] },
    { name: 'road_junction', rect: [1152, 0, 64, 64] },
    { name: 'road_edge_h', rect: [1216, 0, 64, 16] },
    { name: 'road_edge_v', rect: [1216, 16, 16, 64] },
    { name: 'road_corner_tr', rect: [1232, 64, 48, 48] },
    { name: 'road_corner_tl', rect: [1280, 64, 48, 48] },
    { name: 'road_corner_br', rect: [1328, 64, 48, 48] },
    { name: 'road_corner_bl', rect: [1376, 64, 48, 48] },
    { name: 'road_cross_center', rect: [1024, 1024, 256, 256] },
  ],
};

/** 瓦片资源名映射（与 NativeSurfaceView.buildAtlasBitmap 的 when 分支一致） */
const TILE_DRAWABLE = {
  GROUND: 'map_grass_1',
  GRASS_SMALL: 'decoration_grass_small',
  GRASS_MEDIUM: 'decoration_grass_medium',
  GRASS_LARGE: 'decoration_grass_large',
  TREE1: 'decoration_tree1',
  TREE2: 'decoration_tree2',
  TILE_BUILDING: null, // 占位（与 GROUND 重叠，buildAtlasBitmap 同样跳过）
};

/** 地砖资源名映射（与 buildAtlasBitmap floorTileDrawableMap 一致） */
const FLOOR_DRAWABLE = {
  TILE_2x2: 'floor_tile_2x2',
  TILE_2x3: 'floor_tile_2x3',
  TILE_3x2: 'floor_tile_3x2',
  TILE_3x3: 'floor_tile_3x3',
  SPIRIT_MINE_GROUND: 'spirit_mine_ground',
};

/** 石板道路资源名映射（LAYOUT.roads → drawable-nodpi 资源名；由 prepare-road-textures.mjs 生成） */
const ROAD_DRAWABLE = {
  road_base: 'road_base',
  road_base_v: 'road_base_v',
  road_junction: 'road_junction',
  road_edge_h: 'road_edge_h',
  road_edge_v: 'road_edge_v',
  road_corner_tr: 'road_corner_tr',
  road_corner_tl: 'road_corner_tl',
  road_corner_br: 'road_corner_br',
  road_corner_bl: 'road_corner_bl',
  road_cross_center: 'road_cross_center',
};

/** 作物资源名（与 buildAtlasBitmap cropDrawableMap 一致，按 ordinal） */
const CROP_DRAWABLE = ['growing_spiritgrass7', 'growing_spiritgrass8', 'growing_spiritgrass9'];

/**
 * 建筑资源名映射（图集名 → drawable-nodpi 资源文件名，与 BuildingFeatureBoot.kt
 * 的 drawableRes 一一对应；与 SectAtlasAssembler 的 effectiveSpriteName 语义一致——
 * 图集名是精灵名，可能不同于带分级前缀的显示名）。
 */
const BUILDING_DRAWABLE = {
  '灵矿场': 'building_spirit_mine',
  '灵植阁': 'building_herb_garden',
  '灵田': 'building_spirit_field',
  '炼丹炉': 'building_alchemy',
  '锻造坊': 'building_forge',
  '仓库': 'building_warehouse',
  '藏经阁': 'building_library',
  '问道塔': 'building_wen_dao_peak',
  '青云塔': 'building_qingyun_peak',
  '天枢殿': 'building_tianshu_hall',
  '执法堂': 'building_law_enforcement',
  '任务阁': 'building_mission_hall',
  '巡视楼': 'building_patrol_tower',
  '监牢': 'building_reflection_cliff',
  '单人住所': 'building_single_residence',
  '中级单人住所': 'building_single_residence_upgraded',
  '多人住所': 'building_multi_residence',
  '血炼池': 'blood_refining_pool',
  '中级多人住所': 'building_multi_residence_upgraded',
};

/** 语义索引推导（构建期断言：LAYOUT 缺语义名称即报错，防索引漂移） */
function semanticIndices(layout) {
  const idx = (names, name) => {
    const i = names.indexOf(name);
    if (i < 0) throw new Error(`LAYOUT 缺少语义名称: "${name}"（codegen 渲染常量依赖）`);
    return i;
  };
  const tileNames = layout.tiles.map((t) => t.name);
  const floorNames = layout.floors.map((f) => f.name);
  // 地面草皮变体：TileType 名以 GROUND 开头的瓦片（渲染器按此把变体格映射到自身地面纹理）
  const groundVariants = layout.tiles
    .filter((t) => t.name.startsWith('GROUND'))
    .map((t) => t.index)
    .sort((a, b) => a - b);
  return {
    spiritMine: idx(layout.buildingNames, '灵矿场'),
    spiritField: idx(layout.buildingNames, '灵田'),
    spiritMineGround: idx(floorNames, 'SPIRIT_MINE_GROUND'),
    tileGround: idx(tileNames, 'GROUND'),
    tileBuilding: idx(tileNames, 'TILE_BUILDING'),
    structureNameBase: layout.buildingNames.length,
    groundVariants,
  };
}

/**
 * 复刻 SpriteAtlasDef.buildingRect（图集行分布公式，支持建筑专属槽位覆盖）。
 * 覆盖表：图集名 → [x, y, w, h]（大显示建筑用高清槽位，见 LAYOUT.buildingRectOverrides）。
 */
function buildingRectOf(colsPerRow, nameIndex) {
  const name = LAYOUT.buildingNames[nameIndex];
  const override = name != null ? LAYOUT.buildingRectOverrides?.[name] : null;
  if (override) {
    return { x: override[0], y: override[1], w: override[2], h: override[3] };
  }
  let idx = 0;
  for (let rowIndex = 0; rowIndex < colsPerRow.length; rowIndex++) {
    for (let col = 0; col < colsPerRow[rowIndex]; col++) {
      if (idx === nameIndex) {
        return { x: col * LAYOUT.buildingSize, y: LAYOUT.buildingSize + rowIndex * LAYOUT.buildingSize, w: LAYOUT.buildingSize, h: LAYOUT.buildingSize };
      }
      idx++;
    }
  }
  throw new Error(`buildingRect 越界: ${nameIndex}`);
}

// ── 内容 hash 增量 ──

/**
 * 内容 hash：源数据 JSON 序列化后的 sha256 前 16 位（非 mtime，内容不变即跳过重生成）。
 * 生成器函数源码纳入 hash——模板代码变更同样触发重新生成（防止改模板后残留旧产物）。
 */
function contentHash(obj) {
  return crypto.createHash('sha256').update(JSON.stringify(obj)).digest('hex').slice(0, 16);
}

/** 生成器函数源码（hash 因子：模板代码变更 → 内容变更 → 强制重生成） */
function codegenSource() {
  return [
    generateSpriteAtlasDef.toString(),
    generateSpriteRegistryData.toString(),
    generateTextureAtlasH.toString(),
  ].join('\n');
}

/** hash 文件比对：无记录或内容变化时返回 true（需要重新生成） */
function shouldRegenerate(hashFile, hash) {
  if (!fs.existsSync(hashFile)) return true;
  return fs.readFileSync(hashFile, 'utf8').trim() !== hash;
}

/**
 * 剔除 manifest 的构建时间戳（generatedAt）后再入 hash——
 * 否则时间戳每次构建必变 → hash 恒失配 → 增量跳过永久失效
 * （对抗性审查 2026-08-13 边界#1/逆向#2 发现）。
 */
function manifestForHash(manifest) {
  if (!manifest || !Array.isArray(manifest.entries)) return manifest;
  return {
    version: manifest.version,
    entryCount: manifest.entryCount,
    entries: manifest.entries,
  };
}

// ── 生成器：SpriteAtlasDef.kt（core/engine 编译单元） ──

/** 生成 Kotlin 图集布局常量文件（public 成员签名与原手工版一致，不含死代码命令类） */
function generateSpriteAtlasDef(layout) {
  const si = semanticIndices(layout);
  const tileLines = layout.tiles
    .map((t) => `        ${t.name}(${t.index}, SpriteRect(${t.rect.join(', ')}))`)
    .join(',\n');
  // JSON.stringify 生成 Kotlin 字符串字面量（对抗性审查 2026-08-13 边界#10：
  // 引号/$ 转义防注入——名称来自仓库内源数据，防手改破坏生成代码）
  const buildingNameLines = layout.buildingNames.map((n) => `        ${JSON.stringify(n)}`).join(',\n');
  const footprintLines = layout.footprints
    .map((fp, i) => `        ${fp[0]} to ${fp[1]},   // ${i}: ${layout.buildingNames[i]}`)
    .join('\n');
  // 建筑专属槽位覆盖（图集名 → [x,y,w,h]，大显示建筑用高清槽位）
  const buildingOverrideLines = Object.entries(layout.buildingRectOverrides ?? {})
    .map(([name, rect]) => {
      const i = layout.buildingNames.indexOf(name);
      if (i < 0) throw new Error(`buildingRectOverrides 引用未知建筑: "${name}"`);
      return `        ${i} to SpriteRect(${rect.join(', ')}),   // ${name}`;
    })
    .join('\n');
  const cropLines = layout.crops
    .map((c) => `        ${c.name}(SpriteRect(${c.rect.join(', ')}))`)
    .join(',\n');
  const floorLines = layout.floors
    .map((f) => `        ${f.name}(${JSON.stringify(f.key)}, ${f.gridW}, ${f.gridH}, SpriteRect(${f.rect.join(', ')}))`)
    .join(',\n');
  const structureDefLines = layout.structures
    .map((s) => `        StructureDef(${JSON.stringify(s.name)}, ${JSON.stringify(s.key)}, SpriteRect(${s.rect.join(', ')}), ${s.footprint[0]}, ${s.footprint[1]}, ${s.spriteSize[0]}, ${s.spriteSize[1]})`)
    .join(',\n');
  const cloudRectLines = layout.clouds
    .map((c) => `        ${JSON.stringify(c.name)} to SpriteRect(${c.rect.join(', ')})`)
    .join(',\n');
  const roadLines = layout.roads
    .map((r) => `        ${JSON.stringify(r.name)} to SpriteRect(${r.rect.join(', ')})`)
    .join(',\n');
  const groundVariantLines = si.groundVariants.join(', ');

  return [
    'package com.xianxia.sect.core.render',
    '',
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行',
    '//   cd android && node scripts/build-atlas.mjs --atlas-def-only',
    '// 重新生成。',
    '// ============================================================',
    '',
    '/**',
    ' * 精灵在图集中的像素矩形（Vulkan UV 计算和 Canvas rect 共用）。',
    ' */',
    'data class SpriteRect(val x: Int, val y: Int, val w: Int, val h: Int)',
    '',
    '/**',
    ' * 统一精灵图集定义。',
    ' *',
    ' * 这是地面瓦片、装饰和建筑在图集中位置的唯一来源。',
    ' * 布局与 C++ TextureAtlas.h 中的 MAP_SPRITES 定义一致。',
    ' * 新增建筑类型时只需在 build-atlas.mjs 的 LAYOUT 添加，Vulkan/Canvas 两路径自动同步。',
    ' */',
    'object SpriteAtlasDef {',
    `    const val ATLAS_W = ${layout.atlasW}`,
    `    const val ATLAS_H = ${layout.atlasH}`,
    `    const val TILE_SIZE = ${layout.tileSize}`,
    `    const val BUILDING_SIZE = ${layout.buildingSize}`,
    '',
    '    // ============================================================',
    '    // 双端共享渲染常量（与 C++ TextureAtlas.h 同源生成）',
    '    // ============================================================',
    `    const val DECOR_QUALITY_THRESHOLD = ${layout.lodThreshold}f`,
    `    const val SHADOW_OFFSET_TILES = ${layout.shadowOffsetTiles}f`,
    `    const val SHADOW_ALPHA = ${layout.shadowAlpha}f`,
    `    const val SPIRIT_MINE_NAME_INDEX = ${si.spiritMine}`,
    `    const val SPIRIT_FIELD_NAME_INDEX = ${si.spiritField}`,
    `    const val SPIRIT_MINE_GROUND_UV_INDEX = ${si.spiritMineGround}`,
    `    const val TILE_GROUND_INDEX = ${si.tileGround}`,
    `    const val TILE_BUILDING_INDEX = ${si.tileBuilding}`,
    '',
    '    // ============================================================',
    '    // 瓦片类型定义',
    '    // ============================================================',
    '',
    '    /** 瓦片类型（与 C++ TextureAtlas.h MAP_SPRITES 索引一致） */',
    '    enum class TileType(',
    '        val index: Int,',
    '        val rect: SpriteRect',
    '    ) {',
    tileLines + ';',
    '',
    '        companion object {',
    '            private val BY_INDEX = values().associateBy { it.index }',
    '            fun fromIndex(index: Int): TileType = BY_INDEX[index] ?: GROUND',
    '        }',
    '    }',
    '',
    '    /**',
    '     * 瓦片 UV 映射（归一化 0-1，用于 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h 的 UV 计算一致。',
    '     */',
    '    val TILE_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(TileType.values().size * 4)',
    '        for (tile in TileType.values()) {',
    '            val r = tile.rect',
    '            val i = tile.index * 4',
    '            uv[i] = r.x.toFloat() / ATLAS_W',
    '            uv[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 宗门入口固定结构（门楼/阶梯）——渲染走建筑层',
    '    // 须在 BUILDING_UV_MAP 之前声明（BUILDING_UV_MAP 尾部追加结构 UV）',
    '    // ============================================================',
    '',
    '    /** 固定结构定义（渲染走建筑层，nameIdx = BUILDING_NAMES.size + index） */',
    '    data class StructureDef(',
    '        val name: String,',
    '        val key: String,',
    '        val rect: SpriteRect,',
    '        val footprintW: Int,',
    '        val footprintH: Int,',
    '        val spriteW: Int,',
    '        val spriteH: Int',
    '    )',
    '',
    '    val STRUCTURES = listOf(',
    structureDefLines,
    '    )',
    '',
    '    /** 结构 UV 映射（归一化 0-1，追加于 BUILDING_UV_MAP 尾部） */',
    '    val STRUCTURE_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(STRUCTURES.size * 4)',
    '        for ((i, s) in STRUCTURES.withIndex()) {',
    '            val r = s.rect',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    /** 地面草皮变体瓦片索引（渲染器按此把变体格映射到自身地面纹理） */',
    `    val GROUND_VARIANT_INDICES = intArrayOf(${groundVariantLines})`,
    '',
    '    // ============================================================',
    '    // 建筑定义',
    '    // ============================================================',
    '',
    '    /** 建筑名称（按图集排列顺序，与 C++ TextureAtlas.h MAP_SPRITES 一致） */',
    '    val BUILDING_NAMES = listOf(',
    buildingNameLines,
    '    )',
    '',
    '    /** 每行建筑数（图集行分布） */',
    `    private val BUILDING_COLS_PER_ROW = intArrayOf(${layout.buildingColsPerRow.join(', ')})`,
    '',
    '    /** 建筑名称 → 索引 */',
    '    val BUILDING_NAME_INDEX: Map<String, Int> by lazy {',
    '        BUILDING_NAMES.withIndex().associate { it.value to it.index }',
    '    }',
    '',
    '    /** 建筑专属槽位覆盖（索引 → 自定义图集 rect；大显示建筑用高清槽位） */',
    '    private val BUILDING_RECT_OVERRIDES: Map<Int, SpriteRect> = mapOf(',
    buildingOverrideLines,
    '    )',
    '',
    '    /**',
    '     * 占地尺寸（按 BUILDING_NAMES 索引，供渲染器查找占地面积用于地砖选择）。',
    '     * 建筑数据数组中传递的是精灵比例尺寸（spriteWidth/spriteHeight），',
    '     * 渲染器需通过此表获取占地尺寸来计算地砖索引。',
    '     */',
    '    val FOOTPRINT_BY_NAME_INDEX: Array<Pair<Int, Int>> = arrayOf(',
    footprintLines,
    '    )',
    '',
    '    /**',
    '     * 建筑 UV 映射（归一化 0-1，与 C++ NativeBridge drawAllTiles 的',
    '     * buildingUVMap 参数一致）。',
    '     */',
    '    val BUILDING_UV_MAP: FloatArray by lazy {',
    '        val uvs = FloatArray((BUILDING_NAMES.size + STRUCTURES.size) * 4)',
    '        var idx = 0',
    '        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {',
    '            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {',
    '                // 专属槽位覆盖优先，否则行公式 256×256 槽位',
    '                val r = BUILDING_RECT_OVERRIDES[idx]',
    '                    ?: SpriteRect(col * BUILDING_SIZE, BUILDING_SIZE + rowIndex * BUILDING_SIZE, BUILDING_SIZE, BUILDING_SIZE)',
    '                val i = idx * 4',
    '                uvs[i] = r.x.toFloat() / ATLAS_W',
    '                uvs[i + 1] = r.y.toFloat() / ATLAS_H',
    '                uvs[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '                uvs[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '                idx++',
    '            }',
    '        }',
    '        // 固定结构 UV 追加在建筑 UV 尾部（nameIdx = BUILDING_NAMES.size + index）',
    '        for ((si, s) in STRUCTURES.withIndex()) {',
    '            val r = s.rect',
    '            val i = (BUILDING_NAMES.size + si) * 4',
    '            uvs[i] = r.x.toFloat() / ATLAS_W',
    '            uvs[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uvs[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uvs[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uvs',
    '    }',
    '',
    '    /**',
    '     * 获取建筑在图集中的像素矩形（供 Canvas 渲染器使用）。',
    '     * @param nameIndex 建筑索引',
    '     */',
    '    fun buildingRect(nameIndex: Int): SpriteRect {',
    '        BUILDING_RECT_OVERRIDES[nameIndex]?.let { return it }',
    '        var idx = 0',
    '        for (rowIndex in BUILDING_COLS_PER_ROW.indices) {',
    '            for (col in 0 until BUILDING_COLS_PER_ROW[rowIndex]) {',
    '                if (idx == nameIndex) {',
    '                    return SpriteRect(',
    '                        col * BUILDING_SIZE,',
    '                        BUILDING_SIZE + rowIndex * BUILDING_SIZE,',
    '                        BUILDING_SIZE,',
    '                        BUILDING_SIZE',
    '                    )',
    '                }',
    '                idx++',
    '            }',
    '        }',
    '        // 越界回退',
    '        return SpriteRect(0, BUILDING_SIZE, BUILDING_SIZE, BUILDING_SIZE)',
    '    }',
    '',
    '    // ============================================================',
    '    // 灵田作物生长阶段（图集 y=0 行空闲区——与 C++ TextureAtlas.h',
    '    // MAP_SPRITES crop_seedling/crop_growing/crop_mature 同步）',
    '    // ============================================================',
    '',
    '    /** 灵田作物三阶段精灵（64×64，y=0 行 x=832/896/960 空槽） */',
    '    enum class CropStage(val rect: SpriteRect) {',
    cropLines,
    '    }',
    '',
    '    /**',
    '     * 作物 UV 映射（归一化 0-1，按阶段索引，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h UV 计算一致。',
    '     */',
    '    val CROP_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(CropStage.values().size * 4)',
    '        for (stage in CropStage.values()) {',
    '            val r = stage.rect',
    '            val i = stage.ordinal * 4',
    '            uv[i] = r.x.toFloat() / ATLAS_W',
    '            uv[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 云层精灵（世界顶部动态云朵——图集槽位，位置/运动由',
    '    // CloudLayerAnimator 逐帧驱动，渲染端只按槽位取 UV/源矩形）',
    '    // ============================================================',
    '',
    '    /** 云层精灵图集 rect（按 LAYOUT.clouds 声明顺序，Canvas 渲染取源矩形） */',
    '    val CLOUD_RECTS: List<Pair<String, SpriteRect>> = listOf(',
    cloudRectLines,
    '    )',
    '',
    '    /**',
    '     * 云层 UV 映射（归一化 0-1，按云层索引，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h MAP_SPRITES cloud_* 同源。',
    '     */',
    '    val CLOUD_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(CLOUD_RECTS.size * 4)',
    '        for ((i, entry) in CLOUD_RECTS.withIndex()) {',
    '            val r = entry.second',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 石板道路系统（RoadSprite：主体/路口/边缘条/转角/十字装饰）',
    '    // 渲染叠加层按位掩码合成：直路用 base，转角/T/十字用 junction，',
    '    // 外缘描边条用 edge_*，外角用 corner_*，十字中心装饰用 cross_center。',
    '    // 与 C++ TextureAtlas.h MAP_SPRITES 的 road_* 同源。',
    '    // ============================================================',
    '',
    '    /** 道路精灵图集 rect（按 LAYOUT.roads 声明顺序；Canvas 渲染取源矩形） */',
    '    val ROAD_RECTS: List<Pair<String, SpriteRect>> = listOf(',
    roadLines,
    '    )',
    '',
    '    /** 道路精灵名称 → rect（渲染器按 bitMask 合成时查询） */',
    '    val ROAD_RECT_BY_KEY: Map<String, SpriteRect> by lazy {',
    '        ROAD_RECTS.associate { it.first to it.second }',
    '    }',
    '',
    '    /**',
    '     * 道路 UV 映射（归一化 0-1，按 ROAD_RECTS 声明顺序，供 Vulkan 纹理采样）。',
    '     * 与 C++ TextureAtlas.h 的 road UV 计算一致。',
    '     */',
    '    val ROAD_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(ROAD_RECTS.size * 4)',
    '        for ((i, entry) in ROAD_RECTS.withIndex()) {',
    '            val r = entry.second',
    '            val j = i * 4',
    '            uv[j] = r.x.toFloat() / ATLAS_W',
    '            uv[j + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[j + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[j + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    // ============================================================',
    '    // 地砖类型定义',
    '    // ============================================================',
    '',
    '    /** 地砖精灵尺寸（原始像素，与建筑占地一致） */',
    '    enum class FloorTileType(',
    '        val key: String,',
    '        val gridW: Int, val gridH: Int,',
    '        val pixelRect: SpriteRect',
    '    ) {',
    floorLines,
    '    }',
    '',
    '    /** 地砖 UV 映射（归一化 0-1，用于 Vulkan 纹理采样） */',
    '    val FLOOR_TILE_UV_MAP: FloatArray by lazy {',
    '        val uv = FloatArray(FloorTileType.values().size * 4)',
    '        for (tile in FloorTileType.values()) {',
    '            val r = tile.pixelRect',
    '            val i = tile.ordinal * 4',
    '            uv[i] = r.x.toFloat() / ATLAS_W',
    '            uv[i + 1] = r.y.toFloat() / ATLAS_H',
    '            uv[i + 2] = (r.x + r.w).toFloat() / ATLAS_W',
    '            uv[i + 3] = (r.y + r.h).toFloat() / ATLAS_H',
    '        }',
    '        uv',
    '    }',
    '',
    '    /**',
    '     * 根据建筑占地尺寸获取地砖类型索引。',
    '     * 新占地尺寸会映射到最接近的现有地砖类型（纹理拉伸后视觉效果接近）。',
    '     * @param gw 建筑占地宽度（格数）',
    '     * @param gh 建筑占地高度（格数）',
    '     * @return 地砖索引（0-3），或 -1（无匹配地砖）',
    '     */',
    '    @Suppress("CyclomaticComplexMethod") // 生成代码——映射表为游戏数学常量，detekt 阈值不适用',
    '    fun floorTileIndex(gw: Int, gh: Int): Int = when {',
    '        gw == 2 && gh == 2 -> 0  // 地砖2x2',
    '        gw == 2 && gh == 3 -> 1  // 地砖2x3',
    '        gw == 3 && gh == 2 -> 2  // 地砖3x2',
    '        gw == 3 && gh == 3 -> 3  // 地砖3x3',
    '        // 新占地尺寸映射到最接近的现有地砖',
    '        gw == 4 && gh == 4 -> 3  // 方形 → 3x3 地砖（拉伸）',
    '        gw == 6 && gh == 4 -> 2  // 宽扁 → 3x2 地砖',
    '        gw == 5 && gh == 3 -> 2  // 宽扁 → 3x2 地砖',
    '        gw == 6 && gh == 3 -> 2  // 宽扁 → 3x2 地砖',
    '        gw == 4 && gh == 6 -> 1  // 窄高 → 2x3 地砖',
    '        gw == 6 && gh == 6 -> 3  // 大方 → 3x3 地砖',
    '        gw == 4 && gh == 8 -> 1  // 瘦高 → 2x3 地砖',
    '        gw == 2 && gh == 4 -> 1  // 窄高 → 2x3 地砖',
    '        gw == 4 && gh == 3 -> 2  // 宽扁 → 3x2 地砖',
    '        gw == 6 && gh == 5 -> 2  // 宽扁 → 3x2 地砖',
    '        gw == 6 && gh == 2 -> 2  // 门楼占地 6x2 → 3x2 地砖（拉伸）',
    '        gw == 18 && gh == 13 -> 3  // 天枢殿占地 18x13（近方形）→ 3x3 地砖（拉伸）',
    '        else -> -1',
    '    }',
    '',
    '    /** 地砖在图集中的像素矩形（供 Canvas 渲染器使用） */',
    '    fun floorTileRect(index: Int): SpriteRect =',
    '        FloorTileType.values().getOrNull(index)?.pixelRect ?: SpriteRect(0, 640, 128, 128)',
    '}',
    '',
  ].join('\n');
}

// ── 生成器：SpriteRegistryData.kt（app 编译单元） ──

/**
 * 生成精灵注册数据文件（与原有手工 SpriteRegistryData.kt 功能等价：
 * registerAllSprites() 签名与注册语义不变，调用方 XianxiaApplication 无感）。
 * R 引用规则：资源在 app 模块有副本 → 用 app 的 R；仅 feature/game 有 → 用 FeatureGameR。
 */
function generateSpriteRegistryData(registry, manifest) {
  const byName = new Map(manifest.entries.map((e) => [e.name, e]));
  const lines = [];

  lines.push(
    'package com.xianxia.sect',
    '',
    'import com.xianxia.sect.ui.components.SpriteCategory',
    'import com.xianxia.sect.ui.components.SpriteResRegistry',
    '',
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 源数据：scripts/resource-registry.json（注册映射）+ atlas-manifest.json（资源清单）。',
    '// 运行 cd android && node scripts/build-atlas.mjs --codegen 重新生成。',
    '// ============================================================',
    ''
  );

  let needGameR = false;
  const registerLines = [];
  for (const cat of registry.categories) {
    const categoryName = cat.category;
    const valName = `SPRITES_${categoryName}`;
    const entryLines = [];
    for (const entry of cat.entries) {
      const resEntry = byName.get(entry.res);
      if (!resEntry) {
        throw new Error(`注册源数据引用了清单中不存在的资源: "${entry.res}"（${categoryName}/${entry.name}）——检查 resource-registry.json 或 drawable-nodpi 目录`);
      }
      const usesGameR = !manifest.entries.some((e) => e.name === entry.res && e.module === 'app');
      if (usesGameR) needGameR = true;
      const ref = usesGameR ? `FeatureGameR.drawable.${entry.res}` : `R.drawable.${entry.res}`;
      // JSON.stringify 转义（对抗性审查 2026-08-13 边界#10）
      entryLines.push(`        ${JSON.stringify(entry.name)} to ${ref},`);
    }
    lines.push(`/** ${categoryName} — 精灵图资源映射（由 resource-registry.json 生成） */`);
    lines.push(`internal val ${valName}: Map<String, Int> = mapOf(`);
    lines.push(entryLines.join('\n'));
    lines.push(')');
    lines.push('');
    registerLines.push(`    SpriteResRegistry.register(SpriteCategory.${categoryName}, ${valName})`);
  }

  lines.push('/** 全部装备资源 ID（按注册顺序，供预加载与兜底查询使用） */');
  lines.push('internal val ALLEQUIPMENTRESIDS: List<Int> = SPRITES_EQUIPMENT.values.toList()');
  lines.push('');
  lines.push('/** 统一精灵图注册入口（onCreate 调用，签名与原手工版一致） */');
  lines.push('internal fun registerAllSprites() {');
  lines.push(registerLines.join('\n'));
  lines.push('}');
  lines.push('');

  const body = lines.join('\n');
  if (needGameR) {
    return 'import com.xianxia.sect.feature.game.R as FeatureGameR\n\n' + body;
  }
  return body;
}

// ── 生成器：TextureAtlas.h（app native 编译单元） ──

/** 生成 C++ 图集头文件（MAP_SPRITES 与 Kotlin 侧同源） */
function generateTextureAtlasH(layout) {
  const si = semanticIndices(layout);
  const namePad = Math.max(...layout.mapSprites.map((s) => s.name.length));
  const spriteLines = layout.mapSprites
    .map((s) => `    { "${s.name}",${' '.repeat(namePad - s.name.length + 1)}${s.rect[0]}, ${s.rect[1]}, ${s.rect[2]}, ${s.rect[3]} },`)
    .join('\n');

  return [
    '// ============================================================',
    '// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。',
    '// 图集布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行',
    '//   cd android && node scripts/build-atlas.mjs --codegen',
    '// 重新生成。',
    '// ============================================================',
    '#pragma once',
    '',
    '#include "Renderer2D.h"',
    '#include <unordered_map>',
    '#include <string>',
    '',
    '// ============================================================',
    '// TextureAtlas — 精灵图集',
    '// 将所有精灵打包到一张 ASTC 纹理中，通过名称查询 UV 坐标',
    '// ============================================================',
    '',
    'struct AtlasRegion {',
    '    float u0, v0, u1, v1;  // 归一化 UV 坐标 [0,1]',
    '    int pixelW, pixelH;     // 原始像素尺寸',
    '};',
    '',
    'struct SpriteDef {',
    '    const char* name;',
    '    int x, y, w, h;         // 图集中的像素位置（原始图集坐标）',
    '};',
    '',
    'class TextureAtlas {',
    'public:',
    '    TextureAtlas() = default;',
    '    ~TextureAtlas() = default;',
    '',
    '    // 定义图集布局（描述每张精灵在图集中的像素位置）',
    '    void defineAtlas(int totalWidth, int totalHeight,',
    '                     const SpriteDef* sprites, int count);',
    '',
    '    // 查询 UV 坐标',
    '    const AtlasRegion* getRegion(const char* name) const;',
    '',
    '    // 获取图集尺寸',
    '    int width() const { return m_width; }',
    '    int height() const { return m_height; }',
    '',
    'private:',
    '    int m_width = 0;',
    '    int m_height = 0;',
    '    std::unordered_map<std::string, AtlasRegion> m_regions;',
    '};',
    '',
    '// ============================================================',
    '// 地图精灵图集布局定义',
    '// 单张 2048×2048 ASTC 图集，包含所有地图精灵',
    '// ============================================================',
    '',
    '// 地面（64×64）',
    `#define TILE_SIZE ${layout.tileSize}`,
    `#define TREE_SIZE ${layout.tileSize * 2}`,
    '',
    '// 图集总尺寸',
    `#define ATLAS_W ${layout.atlasW}`,
    `#define ATLAS_H ${layout.atlasH}`,
    '',
    '// 建筑精灵尺寸（所有建筑统一为 128×128）',
    `#define BUILDING_W ${layout.buildingSize}`,
    `#define BUILDING_H ${layout.buildingSize}`,
    '',
    '// ============================================================',
    '// 双端共享渲染常量（与 SpriteAtlasDef.kt 同源生成，禁止手改）',
    '// ============================================================',
    `#define DECOR_QUALITY_THRESHOLD ${layout.lodThreshold}f`,
    `#define SHADOW_OFFSET_TILES ${layout.shadowOffsetTiles}f`,
    `#define SHADOW_ALPHA ${layout.shadowAlpha}f`,
    '',
    '// 语义索引（由 LAYOUT 名称推导生成，防建筑/地砖列表调整后索引漂移）',
    `#define SPIRIT_MINE_NAME_INDEX ${si.spiritMine}`,
    `#define SPIRIT_FIELD_NAME_INDEX ${si.spiritField}`,
    `#define SPIRIT_MINE_GROUND_UV_INDEX ${si.spiritMineGround}`,
    '',
    '// 瓦片类型索引（与 SpriteAtlasDef.TileType.index 同源）',
    `#define TILE_GROUND ${si.tileGround}`,
    `#define TILE_BUILDING ${si.tileBuilding}`,
    '',
    '// 固定结构（渲染走建筑层；nameIdx = STRUCTURE_NAME_BASE + index；占地表供底部对齐）',
    `#define STRUCTURE_NAME_BASE ${si.structureNameBase}`,
    `static const int STRUCTURE_FP_W[] = {${layout.structures.map((s) => s.footprint[0]).join(', ')}};`,
    `static const int STRUCTURE_FP_H[] = {${layout.structures.map((s) => s.footprint[1]).join(', ')}};`,
    '// 地面草皮变体索引（渲染器把变体格映射到自身地面纹理）',
    `static const int GROUND_VARIANT_COUNT = ${si.groundVariants.length};`,
    `static const int GROUND_VARIANTS[] = {${si.groundVariants.join(', ')}};`,
    '',
    'static const SpriteDef MAP_SPRITES[] = {',
    spriteLines,
    '};',
    '',
    'static constexpr int MAP_SPRITE_COUNT =',
    '    sizeof(MAP_SPRITES) / sizeof(MAP_SPRITES[0]);',
    '',
  ].join('\n');
}

// ── 注册源数据加载 ──

/** 读取 resource-registry.json（校验结构完整性） */
function loadRegistry() {
  const raw = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8'));
  if (raw.version !== 1 || !Array.isArray(raw.categories) || raw.categories.length === 0) {
    throw new Error(`resource-registry.json 结构异常（version=${raw.version}）`);
  }
  return raw;
}

// ── 精灵清单构建（图集拼装用，顺序 = manifest 顺序 = 守卫测试复现顺序） ──

/** 从 LAYOUT 源数据构建精灵清单：[{ name, x, y, w, h, drawable|null }] */
function buildSpriteList() {
  const sprites = [];

  // 瓦片（TileType 声明顺序；TILE_BUILDING 为 GROUND 同 rect 占位，drawable=null）
  for (const tile of LAYOUT.tiles) {
    sprites.push({
      name: tile.name, x: tile.rect[0], y: tile.rect[1], w: tile.rect[2], h: tile.rect[3],
      drawable: TILE_DRAWABLE[tile.name] ?? null,
    });
  }

  // 建筑（BUILDING_NAMES 顺序，rect 由行分布公式计算；drawable = 资源文件名——
  // 2026-08 修复：此前为 null 导致 KTX/ASTC 图集建筑槽位全空、Vulkan+ASTC 设备建筑不显示）
  for (let i = 0; i < LAYOUT.buildingNames.length; i++) {
    const rect = buildingRectOf(LAYOUT.buildingColsPerRow, i);
    sprites.push({
      name: LAYOUT.buildingNames[i], x: rect.x, y: rect.y, w: rect.w, h: rect.h,
      drawable: BUILDING_DRAWABLE[LAYOUT.buildingNames[i]] ?? null,
    });
  }

  // 地砖（FloorTileType 声明顺序；drawable = 资源名）
  for (const ft of LAYOUT.floors) {
    sprites.push({
      name: ft.name, x: ft.rect[0], y: ft.rect[1], w: ft.rect[2], h: ft.rect[3],
      drawable: FLOOR_DRAWABLE[ft.name] ?? null,
    });
  }

  // 作物（CropStage 声明顺序；CROP_DRAWABLE 按 ordinal）
  LAYOUT.crops.forEach((crop, idx) => {
    sprites.push({
      name: crop.name, x: crop.rect[0], y: crop.rect[1], w: crop.rect[2], h: crop.rect[3],
      drawable: CROP_DRAWABLE[idx] ?? null,
    });
  });

  // 固定结构（宗门入口：门楼/阶梯；drawable = key）
  LAYOUT.structures.forEach((st) => {
    sprites.push({
      name: st.key, x: st.rect[0], y: st.rect[1], w: st.rect[2], h: st.rect[3],
      drawable: st.key,
    });
  });

  // 云层精灵（LAYOUT.clouds 声明顺序；drawable = key = 资源文件名）
  LAYOUT.clouds.forEach((c) => {
    sprites.push({
      name: c.name, x: c.rect[0], y: c.rect[1], w: c.rect[2], h: c.rect[3],
      drawable: c.name,
    });
  });

  // 石板道路（LAYOUT.roads 声明顺序；drawable = ROAD_DRAWABLE 资源名）
  LAYOUT.roads.forEach((r) => {
    sprites.push({
      name: r.name, x: r.rect[0], y: r.rect[1], w: r.rect[2], h: r.rect[3],
      drawable: ROAD_DRAWABLE[r.name] ?? null,
    });
  });

  return sprites;
}

// ── 图集拼装 ──

/** 按名称在 manifest 中查找资源文件绝对路径（feature/game 优先，与双模块放置规则一致） */
function resolveDrawablePath(manifest, name) {
  const matches = manifest.entries.filter((e) => e.name === name);
  if (matches.length === 0) return null;
  const gameEntry = matches.find((e) => e.module === 'feature/game');
  const entry = gameEntry ?? matches[0];
  return path.join(ANDROID_DIR, entry.relPath);
}

/** 拼装 2048×2048 RGBA 图集（sharp composite，拉伸到目标 rect 与 Android drawBitmap 同语义） */
async function buildAtlasPng(sprites, manifest) {
  const overlays = [];
  let loaded = 0;
  for (const s of sprites) {
    if (!s.drawable) continue;
    const file = resolveDrawablePath(manifest, s.drawable);
    if (!file || !fs.existsSync(file)) {
      // fail-fast（对抗性审查 2026-08-13 边界#4）：预期精灵缺失 = 构建失败，
      // 禁止静默产出缺精灵图集（线上地图透明/建筑隐形且无运行时错误）。
      // 允许缺省的条目（TILE_BUILDING 占位）已由上层 `if (!s.drawable) continue` 跳过
      throw new Error(
        `资源缺失: ${s.drawable} (${s.name})——精灵图必须双模块放置（rules/static-resources.md），缺失即构建失败`
      );
    }
    const buf = await sharp(file)
      .resize(s.w, s.h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
      .png()
      .toBuffer();
    overlays.push({ input: buf, left: s.x, top: s.y });
    loaded++;
  }
  console.log(`  精灵加载: ${loaded}/${sprites.length}`);
  await sharp({
    create: { width: LAYOUT.atlasW, height: LAYOUT.atlasH, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  }).composite(overlays).png().toFile(TMP_PNG);
}

// ── astcenc 压缩 ──

/** 探测可用 astcenc 可执行文件（avx2 → sse4.1 → sse2） */
function findAstcenc() {
  if (!fs.existsSync(ASTCENC_DIR)) return null;
  for (const name of ['astcenc-avx2.exe', 'astcenc-sse4.1.exe', 'astcenc-sse2.exe']) {
    const p = path.join(ASTCENC_DIR, name);
    if (fs.existsSync(p)) return p;
  }
  return null;
}

/** 运行 astcenc（-cl <in> <out> 4x4 -medium，LDR——5.x 位置式参数） */
function compressAstc(astcenc) {
  console.log('  astcenc -cl 4x4 -medium ...');
  execFileSync(astcenc, ['-cl', TMP_PNG, TMP_ASTC, '4x4', '-medium'], { stdio: 'inherit' });
}

// ── KTX1 封装 ──

/**
 * KTX1 单 mip 压缩纹理封装（ASTC 4×4；输入为 .astc 文件）。
 * .astc 文件头布局因 astcenc 版本有差异（16/20 字节），但数据区恒为
 * 文件尾部的几何计算尺寸（w/4 × h/4 × 16）——按尾部截取数据，头大小
 * 校验在合理范围（≤32 字节）即可，避免解析头字段的版本兼容问题。
 */
function wrapKtx1(astcBuffer, width, height) {
  const expected = Math.floor(width / ASTC_BLOCK) * Math.floor(height / ASTC_BLOCK) * ASTC_BLOCK_BYTES;
  if (astcBuffer.length < expected + 16) throw new Error('ASTC 文件过短');
  const headerSize = astcBuffer.length - expected;
  if (headerSize > 32) throw new Error(`ASTC 头尺寸异常: ${headerSize}`);
  const data = astcBuffer.subarray(headerSize);
  const dataSize = data.length;
  if (dataSize !== expected) {
    throw new Error(`ASTC 数据尺寸不符: ${dataSize} != ${expected}`);
  }
  const header = Buffer.alloc(64);
  header[0] = 0xAB; header[1] = 0x4B; header[2] = 0x54; header[3] = 0x58; // "«KTX"
  header[4] = 0x20; header[5] = 0x31; header[6] = 0x31; header[7] = 0xBB; // " 11»"
  header.writeUInt32LE(0x04030201, 8); // endianness（小端）
  header.writeUInt32LE(0, 12); // glType（压缩纹理 = 0）
  header.writeUInt32LE(1, 16); // glTypeSize
  header.writeUInt32LE(0, 20); // glFormat（压缩纹理 = 0）
  header.writeUInt32LE(GL_COMPRESSED_RGBA_ASTC_4x4_KHR, 24); // glInternalFormat
  header.writeUInt32LE(GL_RGBA, 28); // glBaseInternalFormat
  header.writeUInt32LE(width, 32); // pixelWidth
  header.writeUInt32LE(height, 36); // pixelHeight
  header.writeUInt32LE(0, 40); // pixelDepth
  header.writeUInt32LE(0, 44); // numberOfArrayElements
  header.writeUInt32LE(1, 48); // numberOfFaces
  header.writeUInt32LE(1, 52); // numberOfMipmapLevels
  header.writeUInt32LE(0, 56); // bytesOfKeyValueData

  const sizeField = Buffer.alloc(4);
  sizeField.writeUInt32LE(dataSize, 0);
  return Buffer.concat([header, sizeField, data]);
}

// ── 图集布局 manifest ──

/** 布局哈希：sprites 清单的 sha256 前缀（守卫测试用 SpriteAtlasDef 数据复现比对） */
function layoutHashOf(sprites) {
  const canonical = sprites
    .map((s) => `${s.name}:${s.x},${s.y},${s.w},${s.h}`)
    .join('|');
  return crypto.createHash('sha256').update(canonical).digest('hex').slice(0, 16);
}

// ── 模式：codegen ──

/** --atlas-def-only：生成 SpriteAtlasDef.kt 到 core/engine 编译单元（hash 增量跳过） */
function runAtlasDefCodegen() {
  const hash = contentHash({ layout: LAYOUT, codegen: codegenSource() });
  const hashFile = path.join(ATLAS_DEF_OUT_DIR, '.atlas-def.hash');
  if (!shouldRegenerate(hashFile, hash)) {
    console.log('SpriteAtlasDef.kt 内容未变化，跳过生成');
    return;
  }
  const outFile = path.join(ATLAS_DEF_OUT_DIR, 'com/xianxia/sect/core/render/SpriteAtlasDef.kt');
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, generateSpriteAtlasDef(LAYOUT));
  fs.writeFileSync(hashFile, hash);
  console.log(`生成 SpriteAtlasDef.kt -> ${outFile}`);
}

/** --codegen：生成 SpriteRegistryData.kt + TextureAtlas.h 到 app 编译单元（hash 增量跳过） */
function runSpriteCodegen() {
  const registry = loadRegistry();
  const manifest = ensureManifest(APP_DRAWABLE_DIR, GAME_DRAWABLE_DIR, MANIFEST_OUT);
  const hash = contentHash({ layout: LAYOUT, registry, manifest: manifestForHash(manifest), codegen: codegenSource() });
  const hashFile = path.join(SPRITE_CODE_OUT_DIR, '.sprite-code.hash');
  if (!shouldRegenerate(hashFile, hash)) {
    console.log('SpriteRegistryData.kt / TextureAtlas.h 内容未变化，跳过生成');
    return;
  }
  fs.mkdirSync(SPRITE_CODE_OUT_DIR, { recursive: true });

  const ktOut = path.join(SPRITE_CODE_OUT_DIR, 'com/xianxia/sect/SpriteRegistryData.kt');
  fs.mkdirSync(path.dirname(ktOut), { recursive: true });
  fs.writeFileSync(ktOut, generateSpriteRegistryData(registry, manifest));
  console.log(`生成 SpriteRegistryData.kt -> ${ktOut}`);

  const hOut = path.join(SPRITE_CODE_OUT_DIR, 'TextureAtlas.h');
  fs.writeFileSync(hOut, generateTextureAtlasH(LAYOUT));
  console.log(`生成 TextureAtlas.h -> ${hOut}`);

  fs.writeFileSync(hashFile, hash);
}

// ── main ──

async function main() {
  const args = process.argv.slice(2);

  // codegen 模式（不依赖 astcenc/sharp 拼装）
  if (args.includes('--atlas-def-only')) {
    runAtlasDefCodegen();
    return;
  }
  if (args.includes('--codegen')) {
    runSpriteCodegen();
    return;
  }

  // 图集模式：拼装 KTX + 布局 manifest（保留原行为）
  console.log('build-atlas: 构建精灵清单（LAYOUT 源数据）...');
  const sprites = buildSpriteList();
  const manifest = ensureManifest(APP_DRAWABLE_DIR, GAME_DRAWABLE_DIR, MANIFEST_OUT);

  const astcenc = findAstcenc();
  if (!astcenc) {
    throw new Error('astcenc 未找到（scripts/tools/astcenc/bin/）——请从 https://github.com/ARM-software/astc-encoder/releases 下载 windows-x64 版本');
  }

  console.log(`拼装 ${LAYOUT.atlasW}×${LAYOUT.atlasH} 图集（${sprites.length} 精灵）...`);
  await buildAtlasPng(sprites, manifest);
  compressAstc(astcenc);

  console.log('封装 KTX1 ...');
  const ktx = wrapKtx1(fs.readFileSync(TMP_ASTC), LAYOUT.atlasW, LAYOUT.atlasH);
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'atlas_astc.ktx'), ktx);

  const outManifest = {
    version: 1,
    format: 'ASTC_4x4_LDR',
    width: LAYOUT.atlasW,
    height: LAYOUT.atlasH,
    layoutHash: layoutHashOf(sprites),
    generatedAt: new Date().toISOString(),
    spriteCount: sprites.length,
    sprites: sprites.map((s) => ({
      name: s.name, x: s.x, y: s.y, w: s.w, h: s.h,
      drawable: s.drawable ?? null,
    })),
  };
  fs.writeFileSync(path.join(OUT_DIR, 'atlas-manifest.json'),
    JSON.stringify(outManifest, null, 2) + '\n');

  // 清理临时文件
  for (const f of [TMP_PNG, TMP_ASTC]) {
    if (fs.existsSync(f)) fs.unlinkSync(f);
  }

  console.log(`完成: atlas_astc.ktx ${(ktx.length / 1024 / 1024).toFixed(2)}MB, sprites=${sprites.length}`);
  console.log(`      layoutHash=${outManifest.layoutHash}`);
}

main().catch((e) => {
  console.error('build-atlas 失败:', e.message);
  process.exit(1);
});
