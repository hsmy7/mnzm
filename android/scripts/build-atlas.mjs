/**
 * 图集 KTX 生成管线（WP7 Vulkan ASTC 压缩）。
 *
 * 输入（唯一权威源，脚本只读不写）：
 * - core/engine/.../core/render/SpriteAtlasDef.kt  — 布局（rect/名称/枚举）
 * - feature/game/.../building/BuildingFeatureBoot.kt — 建筑 displayName→drawable 映射
 * - feature/game/src/main/res/drawable-nodpi/*.webp — 精灵资源
 *
 * 输出：
 * - app/src/main/assets/atlas/atlas_astc.ktx  — KTX1 封装的 ASTC 4×4 LDR 图集（~4MB）
 * - app/src/main/assets/atlas/atlas-manifest.json — 精灵清单 + 布局哈希（守卫测试比对基准）
 *
 * 依赖：Node + sharp（scripts/node_modules）、astcenc（scripts/tools/astcenc/bin/）。
 * 运行：node scripts/build-atlas.mjs
 * astcenc 缺失时给出明确错误并退出非零（由 gradle task 决定是否跳过）。
 */
import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');

// ── 路径 ──
const SPRITE_ATLAS_DEF = path.resolve(ANDROID_DIR,
  'core/engine/src/main/java/com/xianxia/sect/core/render/SpriteAtlasDef.kt');
const BUILDING_BOOT = path.resolve(ANDROID_DIR,
  'feature/game/src/main/java/com/xianxia/sect/ui/game/building/BuildingFeatureBoot.kt');
const DRAWABLE_DIR = path.resolve(ANDROID_DIR,
  'feature/game/src/main/res/drawable-nodpi');
const ASTCENC_DIR = path.resolve(ANDROID_DIR, 'scripts/tools/astcenc/bin');
const OUT_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');
const TMP_PNG = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.png');
const TMP_ASTC = path.resolve(ANDROID_DIR, 'scripts/tools/atlas_tmp.astc');

// ── 常量（必须与 SpriteAtlasDef.kt 一致——脚本解析失败时此处为兜底） ──
const ATLAS_W = 2048;
const ATLAS_H = 2048;
const BUILDING_SIZE = 128;

// ASTC 4×4 块尺寸（astcenc -cl 4x4）
const ASTC_BLOCK = 4;
const ASTC_BLOCK_BYTES = 16;
const GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
const GL_RGBA = 0x1908;

// ── Kotlin 源解析（三种枚举形式分离，正则只匹配 SpriteAtlasDef.kt 内结构） ──

/** 形式 A：TileType（NAME(index, SpriteRect(x, y, w, h))） */
function parseTileTypeRects(source) {
  const results = [];
  const re = /([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*SpriteRect\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/g;
  let m;
  while ((m = re.exec(source)) !== null) {
    results.push({
      name: m[1], index: parseInt(m[2], 10),
      rect: { x: +m[3], y: +m[4], w: +m[5], h: +m[6] },
    });
  }
  return results;
}

/** 形式 B：FloorTileType（NAME("key", a, b, SpriteRect(x, y, w, h))，名字可含 x 如 TILE_2x2） */
function parseFloorTileRects(source) {
  const results = [];
  const re = /([A-Z][A-Z0-9_]*(?:x\d+)?)\s*\(\s*"[^"]+"\s*,\s*\d+\s*,\s*\d+\s*,\s*SpriteRect\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/g;
  let m;
  while ((m = re.exec(source)) !== null) {
    results.push({
      name: m[1],
      rect: { x: +m[2], y: +m[3], w: +m[4], h: +m[5] },
    });
  }
  return results;
}

/** 形式 C：CropStage（NAME(SpriteRect(x, y, w, h))，无 index） */
function parseCropRects(source) {
  const results = [];
  const re = /(SEEDLING|GROWING|MATURE)\s*\(\s*SpriteRect\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/g;
  let m;
  while ((m = re.exec(source)) !== null) {
    results.push({
      name: m[1],
      rect: { x: +m[2], y: +m[3], w: +m[4], h: +m[5] },
    });
  }
  return results;
}

/** 解析 BUILDING_NAMES 列表 */
function parseBuildingNames(source) {
  const m = source.match(/val BUILDING_NAMES = listOf\((.*?)\)/s);
  if (!m) throw new Error('BUILDING_NAMES 解析失败');
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

/** 解析 BUILDING_COLS_PER_ROW */
function parseBuildingCols(source) {
  const m = source.match(/BUILDING_COLS_PER_ROW = intArrayOf\(([^)]+)\)/);
  if (!m) throw new Error('BUILDING_COLS_PER_ROW 解析失败');
  return m[1].split(',').map((s) => parseInt(s.trim(), 10));
}

/** 复刻 SpriteAtlasDef.buildingRect（图集行分布公式） */
function buildingRectOf(colsPerRow, nameIndex) {
  let idx = 0;
  for (let rowIndex = 0; rowIndex < colsPerRow.length; rowIndex++) {
    for (let col = 0; col < colsPerRow[rowIndex]; col++) {
      if (idx === nameIndex) {
        return { x: col * BUILDING_SIZE, y: BUILDING_SIZE + rowIndex * BUILDING_SIZE, w: BUILDING_SIZE, h: BUILDING_SIZE };
      }
      idx++;
    }
  }
  throw new Error(`buildingRect 越界: ${nameIndex}`);
}

/**
 * 解析 BuildingFeatureBoot.kt：displayName → drawable 名。
 *
 * BuildingFeature 使用位置参数（displayName 为第 2 个位置参数），drawableRes
 * 为命名参数——两者在源中按声明顺序一一对应，按序提取后按 index 配对。
 */
function parseBuildingDrawables(source) {
  const displayNames = [];
  const nameRe = /BuildingFeature\(\s*"[^"]+"\s*,\s*"([^"]+)"/g;
  let m;
  while ((m = nameRe.exec(source)) !== null) displayNames.push(m[1]);

  const drawables = [];
  const resRe = /drawableRes\s*=\s*R\.drawable\.(\w+)/g;
  while ((m = resRe.exec(source)) !== null) drawables.push(m[1]);

  if (displayNames.length !== drawables.length) {
    throw new Error(`BuildingFeatureBoot 解析数量不符: displayName=${displayNames.length} drawable=${drawables.length}`);
  }
  const map = new Map();
  for (let i = 0; i < displayNames.length; i++) map.set(displayNames[i], drawables[i]);
  return map;
}

// ── 精灵清单构建 ──

/**
 * 瓦片资源名映射（与 NativeSurfaceView.buildAtlasBitmap 的 when 分支一致——
 * 修改时双端必须同步；AtlasManifestSyncTest 守卫锁住 manifest↔SpriteAtlasDef 一致性）
 */
const TILE_DRAWABLE = {
  GROUND: 'map_tile',
  GRASS_SMALL: 'decoration_grass_small',
  GRASS_MEDIUM: 'decoration_grass_medium',
  GRASS_LARGE: 'decoration_grass_large',
  TREE1: 'decoration_tree1',
  TREE2: 'decoration_tree2',
  TILE_BUILDING: null, // 占位（与 GROUND 重叠，buildAtlasBitmap 同样跳过）
  GROUND_V2: 'map_tile_v2',
};

/** 地砖资源名映射（与 buildAtlasBitmap floorTileDrawableMap 一致） */
const FLOOR_DRAWABLE = {
  TILE_2x2: 'floor_tile_2x2',
  TILE_2x3: 'floor_tile_2x3',
  TILE_3x2: 'floor_tile_3x2',
  TILE_3x3: 'floor_tile_3x3',
  SPIRIT_MINE_GROUND: 'spirit_mine_ground',
};

/** 作物资源名（与 buildAtlasBitmap cropDrawableMap 一致，按 ordinal） */
const CROP_DRAWABLE = ['growing_spiritgrass7', 'growing_spiritgrass8', 'growing_spiritgrass9'];

/**
 * 构建精灵清单：[{ name, x, y, w, h, drawable|null }]
 * 顺序 = manifest 顺序 = 守卫测试复现顺序（tile → building → floor → crop）
 */
function buildSpriteList(defSource, bootSource) {
  const tiles = parseTileTypeRects(defSource);
  const floors = parseFloorTileRects(defSource);
  const crops = parseCropRects(defSource);
  const buildingNames = parseBuildingNames(defSource);
  const colsPerRow = parseBuildingCols(defSource);
  const buildingDrawables = parseBuildingDrawables(bootSource);

  const sprites = [];

  // 瓦片（TileType 声明顺序；TILE_BUILDING 为 GROUND 同 rect 占位，drawable=null）
  for (const tile of tiles) {
    sprites.push({
      name: tile.name,
      x: tile.rect.x, y: tile.rect.y, w: tile.rect.w, h: tile.rect.h,
      drawable: TILE_DRAWABLE[tile.name] ?? null,
    });
  }

  // 建筑（BUILDING_NAMES 顺序，rect 由行分布公式计算）
  for (let i = 0; i < buildingNames.length; i++) {
    const rect = buildingRectOf(colsPerRow, i);
    sprites.push({
      name: buildingNames[i], x: rect.x, y: rect.y, w: rect.w, h: rect.h,
      drawable: buildingDrawables.get(buildingNames[i]) ?? null,
    });
  }

  // 地砖（FloorTileType 声明顺序；drawable = 资源名）
  for (const ft of floors) {
    sprites.push({
      name: ft.name, x: ft.rect.x, y: ft.rect.y, w: ft.rect.w, h: ft.rect.h,
      drawable: FLOOR_DRAWABLE[ft.name] ?? null,
    });
  }

  // 作物（CropStage 声明顺序；CROP_DRAWABLE 按 ordinal）
  let cropIdx = 0;
  for (const crop of crops) {
    const drawable = CROP_DRAWABLE[cropIdx++] ?? null;
    sprites.push({
      name: crop.name, x: crop.rect.x, y: crop.rect.y, w: crop.rect.w, h: crop.rect.h,
      drawable,
    });
  }

  return sprites;
}

// ── 图集拼装 ──

/** 拼装 2048×2048 RGBA 图集（sharp composite，拉伸到目标 rect 与 Android drawBitmap 同语义） */
async function buildAtlasPng(sprites) {
  const overlays = [];
  let loaded = 0;
  for (const s of sprites) {
    if (!s.drawable) continue;
    const file = path.join(DRAWABLE_DIR, s.drawable + '.webp');
    if (!fs.existsSync(file)) {
      console.warn(`  [warn] 资源缺失，跳过: ${s.drawable} (${s.name})`);
      continue;
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
    create: { width: ATLAS_W, height: ATLAS_H, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
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
 *
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

// ── manifest ──

/** 布局哈希：sprites 清单的 sha256 前缀（守卫测试用 SpriteAtlasDef 数据复现比对） */
function layoutHashOf(sprites) {
  const canonical = sprites
    .map((s) => `${s.name}:${s.x},${s.y},${s.w},${s.h}`)
    .join('|');
  return crypto.createHash('sha256').update(canonical).digest('hex').slice(0, 16);
}

// ── main ──

async function main() {
  console.log('build-atlas: 解析权威源 ...');
  const defSource = fs.readFileSync(SPRITE_ATLAS_DEF, 'utf8');
  const bootSource = fs.readFileSync(BUILDING_BOOT, 'utf8');
  const sprites = buildSpriteList(defSource, bootSource);

  const astcenc = findAstcenc();
  if (!astcenc) {
    throw new Error('astcenc 未找到（scripts/tools/astcenc/bin/）——请从 https://github.com/ARM-software/astc-encoder/releases 下载 windows-x64 版本');
  }

  console.log(`拼装 ${ATLAS_W}×${ATLAS_H} 图集（${sprites.length} 精灵）...`);
  await buildAtlasPng(sprites);
  compressAstc(astcenc);

  console.log('封装 KTX1 ...');
  const ktx = wrapKtx1(fs.readFileSync(TMP_ASTC), ATLAS_W, ATLAS_H);
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'atlas_astc.ktx'), ktx);

  const manifest = {
    version: 1,
    format: 'ASTC_4x4_LDR',
    width: ATLAS_W,
    height: ATLAS_H,
    layoutHash: layoutHashOf(sprites),
    generatedAt: new Date().toISOString(),
    spriteCount: sprites.length,
    sprites: sprites.map((s) => ({
      name: s.name, x: s.x, y: s.y, w: s.w, h: s.h,
      drawable: s.drawable ?? null,
    })),
  };
  fs.writeFileSync(path.join(OUT_DIR, 'atlas-manifest.json'),
    JSON.stringify(manifest, null, 2) + '\n');

  // 清理临时文件
  for (const f of [TMP_PNG, TMP_ASTC]) {
    if (fs.existsSync(f)) fs.unlinkSync(f);
  }

  console.log(`完成: atlas_astc.ktx ${(ktx.length / 1024 / 1024).toFixed(2)}MB, sprites=${sprites.length}`);
  console.log(`      layoutHash=${manifest.layoutHash}`);
}

main().catch((e) => {
  console.error('build-atlas 失败:', e.message);
  process.exit(1);
});
