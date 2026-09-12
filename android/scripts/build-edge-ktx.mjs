/**
 * 浮空岛崖壁独立纹理 KTX 构建（ASTC 4×4 + mip 链）。
 *
 * 背景：崖壁素材单张最大 1180×3552，**超出 4096² 图集容量**，故走独立纹理
 * （Rhi `Renderer2D::uploadTexture`/`draw(textureId)` 天然支持多纹理）。
 * 消费端三级降级：
 *   ① 本脚本产物（ASTC KTX，仅 Vulkan）→ ② RGBA mip 链 → ③ RGBA 单级（GLES/Canvas 用 WebP）。
 *   故产物缺失/astcenc 缺失/设备不支持 ASTC 时**不是错误**，只是落到②③。
 *
 * 源：`feature/game/src/main/res/drawable-nodpi/map_edge_*.webp`（唯一权威，
 *    由 import-art-assets.mjs 按 bake{preserve,roundUp4} 从美术源目录烘焙）。
 * 产物：`app/src/main/assets/atlas/edge/map_edge_*.ktx`（7 张）。
 *
 * 尺寸契约：源 WebP 宽高已是 4 的倍数（roundUp4 保证）——非 4 倍数会被
 * KtxLoader/astcenc 拒绝，故此处在入口断言，防上游规则回退时静默产出坏纹理。
 *
 * 特性：内容 hash 增量（源 MD5 + 生成器版本 → 未变跳过）；astcenc 缺失时
 * **明确报错并非零退出**（与 build-atlas.mjs 同纪律，禁止静默跳过产物）。
 *
 * 用法：node scripts/build-edge-ktx.mjs [--dry-run]
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import os from 'os';
import { fileURLToPath } from 'url';
import sharp from 'sharp';
import { findAstcenc, buildMipChain, wrapKtx1, ASTC_BLOCK } from './lib/ktx1.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const SRC_DIR = path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi');
const OUT_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas/edge');
const HASH_FILE = path.join(OUT_DIR, '.edge-ktx.hash');
const ASTCENC_DIR = path.resolve(ANDROID_DIR, 'scripts/tools/astcenc/bin');

/** 生成器版本：改动 mip/容器算法时递增，使全部产物重生成 */
const GENERATOR_VERSION = 'edge-ktx-2';

/** 待压缩纹理清单：drawable 名（= 产物文件名，与 map_edge_* WebP 同名） */
const EDGE_TEXTURES = [
  'map_edge_left_1',
  'map_edge_left_2',
  'map_edge_left_3',
  'map_edge_bottom_1',
  'map_edge_bottom_2',
  'map_edge_corner_bl',
  'map_edge_corner_br',
];

/**
 * KTX 自检：按消费端 `KtxLoader.cpp` 的逐级几何推导复现校验，产物落盘前拦截
 * 尺寸/级数不符的坏纹理。
 *
 * 必要性：本管线存在 147 / 36 / 17 / 5 这类非 4 倍数的 mip 级（非 2 的幂底图
 * `base >> level` 的必然结果），块数用 ceil 还是 floor 会直接决定 KtxLoader 接受
 * 或**整张拒绝**——自检把该契约固化在产物生成侧，避免"构建通过但运行时不生效"的静默失败。
 *
 * @param {Buffer} ktx wrapKtx1 产物
 * @param {number} width mip0 宽
 * @param {number} height mip0 高
 */
function assertKtxLoadable(ktx, width, height) {
  const HEADER = 64, FIELD = 4, BLOCK = 4, BLOCK_BYTES = 16;
  const mipLevels = ktx.readUInt32LE(52);
  let cursor = HEADER;
  for (let i = 0; i < mipLevels; i++) {
    let lw = width >> i, lh = height >> i;
    if (lw < BLOCK) lw = BLOCK;
    if (lh < BLOCK) lh = BLOCK;
    const expected = Math.ceil(lw / BLOCK) * Math.ceil(lh / BLOCK) * BLOCK_BYTES;
    if (ktx.length < cursor + FIELD) throw new Error(`自检失败：缺 mip ${i} 的 dataSize 字段`);
    const stored = ktx.readUInt32LE(cursor);
    if (stored !== expected) {
      throw new Error(`自检失败：mip ${i} (${lw}x${lh}) dataSize stored=${stored} expected=${expected}`);
    }
    cursor += FIELD + stored;
  }
  if (cursor !== ktx.length) {
    throw new Error(`自检失败：数据区尺寸不精确 cursor=${cursor} file=${ktx.length}`);
  }
}

async function main() {
  const dryRun = process.argv.includes('--dry-run');
  console.log(`=== 崖壁纹理 KTX 构建（${dryRun ? 'DRY-RUN' : '写入'}）===\n`);

  const astcenc = findAstcenc(ASTCENC_DIR);
  if (!astcenc) {
    throw new Error(
      'astcenc 未找到（scripts/tools/astcenc/bin/）——请从 ' +
      'https://github.com/ARM-software/astc-encoder/releases 下载 windows-x64 版本'
    );
  }
  console.log(`astcenc: ${path.basename(astcenc)}\n`);

  // 源清单 + 内容 hash（源 MD5 + 生成器版本）
  const sources = [];
  for (const name of EDGE_TEXTURES) {
    const src = path.join(SRC_DIR, `${name}.webp`);
    if (!fs.existsSync(src)) {
      throw new Error(`源缺失: ${src}——先运行 node scripts/import-art-assets.mjs`);
    }
    const meta = await sharp(src).metadata();
    if (meta.width % ASTC_BLOCK !== 0 || meta.height % ASTC_BLOCK !== 0) {
      throw new Error(
        `${name} 尺寸 ${meta.width}x${meta.height} 非 ${ASTC_BLOCK} 的倍数——` +
        '检查 source-mapping.json 的 bake.roundUp4'
      );
    }
    const md5 = crypto.createHash('md5').update(fs.readFileSync(src)).digest('hex');
    sources.push({ name, src, width: meta.width, height: meta.height, md5 });
  }
  const contentHash = crypto
    .createHash('sha256')
    .update(GENERATOR_VERSION + '|' + sources.map((s) => `${s.name}:${s.md5}`).join('|'))
    .digest('hex');

  const prevHash = fs.existsSync(HASH_FILE) ? fs.readFileSync(HASH_FILE, 'utf8').trim() : null;
  const allExist = EDGE_TEXTURES.every((n) => fs.existsSync(path.join(OUT_DIR, `${n}.ktx`)));
  if (prevHash === contentHash && allExist) {
    console.log('源与生成器均未变化，跳过（产物 7 张已在位）');
    return;
  }

  if (dryRun) {
    console.log('将生成：');
    for (const s of sources) {
      console.log(`  ${s.name}.ktx  ←  ${s.width}x${s.height}  (${(fs.statSync(s.src).size / 1048576).toFixed(2)}MB 源)`);
    }
    console.log('\nDRY-RUN 完成。');
    return;
  }

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'edge-ktx-'));

  let totalKtx = 0;
  try {
    for (const s of sources) {
      console.log(`${s.name}  ${s.width}x${s.height}`);
      const mips = await buildMipChain({
        astcenc,
        sourceBuffer: fs.readFileSync(s.src),
        width: s.width,
        height: s.height,
        tmpDir,
        tag: s.name,
        log: (m) => console.log(m),
      });
      const ktx = wrapKtx1(mips);
      assertKtxLoadable(ktx, s.width, s.height);
      fs.writeFileSync(path.join(OUT_DIR, `${s.name}.ktx`), ktx);
      totalKtx += ktx.length;
      console.log(`  → ${s.name}.ktx  ${mips.length} 级 mip  ${(ktx.length / 1024).toFixed(0)}KB\n`);
    }
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }

  fs.writeFileSync(HASH_FILE, contentHash);
  console.log(`完成：7 张 KTX 合计 ${(totalKtx / 1048576).toFixed(2)}MB（ASTC 4×4 + mip 链）`);
}

main().catch((err) => {
  console.error('崖壁 KTX 构建失败:', err.message);
  process.exit(1);
});
