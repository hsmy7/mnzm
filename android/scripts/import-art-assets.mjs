/**
 * 素材源目录导入脚本（读 source-mapping.json 权威映射，按烘焙规则重烘焙）。
 *
 * 数据源：
 * - scripts/source-mapping.json — 权威 source↔drawable 映射（由 scaffold-source-mapping.mjs 生成/维护）
 * - D:\模拟宗门美术素材 — 美术素材源目录
 *
 * 烘焙规则（mapping 每条 bake）：
 * - { preserve: true }           保留源分辨率（大图：立绘/建筑/UI/背景/妖兽等）
 * - { maxDim: N }                等比缩放到最长边 = N（小物件：丹药/材料/装备/种子/储物袋/功法）
 * - { square: N }                中心裁方到 min(短边, N) 再 cover 缩放（REPEAT 地面纹理
 *                                须 POT——如底部岩石材质；N 传 2 的幂值）
 * - { canvas: {w,h} }            等比缩放并透明延展到 w×h 画布（contain，天枢殿专用）
 * - { roundUp4: true }           宽高各自向上取整到 4 的倍数（contain 同比例画布，不拉伸）
 *                                —— ASTC 4×4 压缩纹理的硬性尺寸要求（非 4 倍数被 KtxLoader 拒绝），
 *                                最多 +3px；取整尺寸同为运行时世界布局尺寸，双路径逐像素同布局
 * - { seamless: true }           叠加无缝平铺处理（REPEAT 平铺地面如草皮；与尺寸规则组合使用）
 *
 * 特性：
 * - 内容 hash 增量：per-drawable 记录「源 MD5 + bake 参数 + 目标模块」，未变则跳过
 * - fail-fast：映射指向的源文件缺失 → 抛错退出非零（禁止静默产出缺素材 WebP）
 * - --dry-run：不写文件，仅输出「将变更/将跳过/将失败」清单
 * - 输出 scripts/sources-imported.json（drawable → 源 MD5 + bake 后尺寸），供校验守卫消费
 *
 * 用法：node scripts/import-art-assets.mjs [--dry-run]
 */
import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const MAPPING_FILE = path.resolve(__dirname, 'source-mapping.json');
const OUT_SUMMARY = path.resolve(__dirname, 'sources-imported.json');

const MODULE_DIRS = {
  'feature/game': path.resolve(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi'),
  'app': path.resolve(ANDROID_DIR, 'app/src/main/res/drawable-nodpi'),
};

const WEBP_OPTIONS = { lossless: true, effort: 6 };

/** 单素材硬上限（GPU 纹理上限，防个别超高清源撑爆） */
const MAX_BAKE_DIM = 4096;

/** 无缝平铺处理的工作分辨率倍率（4× 后降采样，低通平滑周期环绕过渡） */
const SEAMLESS_WORK_SCALE = 4;

function md5OfFile(p) { return crypto.createHash('md5').update(fs.readFileSync(p)).digest('hex'); }

/**
 * 无缝平铺处理（偏移平均法，数学保证无缝 + 高分辨率降采样平滑环绕）。
 *
 * P(x,y) = avg( I(x,y), I(x+W/2,y), I(x,y+H/2), I(x+W/2,y+H/2) )，坐标按周期环绕。
 * 因平移 W/2 只是置换四项，P 严格周期（平铺完全连续，零接缝、无人工模糊带）。
 * 在 4× 工作分辨率上执行后降采样到目标尺寸——低通滤波进一步平滑周期环绕过渡，
 * 消除"暗接缝"（纹理环绕点相邻色差）。
 *
 * @param {Buffer} buffer 已缩放到目标尺寸的图片缓冲
 * @param {number} w 目标宽
 * @param {number} h 目标高
 * @returns {Promise<Buffer>} 处理后的 PNG 缓冲（尺寸不变）
 */
async function makeSeamless(buffer, w, h) {
  const workW = w * SEAMLESS_WORK_SCALE;
  const workH = h * SEAMLESS_WORK_SCALE;
  const { data, info } = await sharp(buffer)
    .resize(workW, workH, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .raw()
    .toBuffer({ resolveWithObject: true });
  const cw = info.width, chh = info.height, ch = info.channels;
  const halfW = cw >> 1, halfH = chh >> 1;
  const out = Buffer.alloc(data.length);
  for (let y = 0; y < chh; y++) {
    const y0 = y, y1 = (y + halfH) % chh;
    for (let x = 0; x < cw; x++) {
      const x0 = x, x1 = (x + halfW) % cw;
      const b00 = (y0 * cw + x0) * ch;
      const b10 = (y1 * cw + x0) * ch;
      const b01 = (y0 * cw + x1) * ch;
      const b11 = (y1 * cw + x1) * ch;
      const bo = (y * cw + x) * ch;
      for (let c = 0; c < ch; c++) {
        out[bo + c] = (data[b00 + c] + data[b10 + c] + data[b01 + c] + data[b11 + c]) >> 2;
      }
    }
  }
  const seamless = await sharp(out, { raw: { width: cw, height: chh, channels: ch } }).png().toBuffer();
  return sharp(seamless)
    .resize(w, h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .png()
    .toBuffer();
}

/** 读取映射（校验结构） */
function loadMapping() {
  const raw = JSON.parse(fs.readFileSync(MAPPING_FILE, 'utf8'));
  if (raw.version !== 1 || !Array.isArray(raw.categories)) throw new Error(`source-mapping.json 结构异常（version=${raw.version}）`);
  const byName = new Map();
  for (const cat of raw.categories) {
    for (const e of cat.entries) {
      if (byName.has(e.drawable)) throw new Error(`source-mapping.json drawable 重复: ${e.drawable}`);
      byName.set(e.drawable, { ...e, category: cat.category });
    }
  }
  return byName;
}

/**
 * 按 bake 规则生成输出尺寸（含 MAX_BAKE_DIM 硬上限）。
 *
 * 同时给出缩放方式 [fit]：'contain'（同比例画布，不拉伸——canvas/roundUp4 用）、
 * 'cover'（中心裁方到正方形——square 用，裁切不拉伸）
 * 或 'fill'（等比目标框，1px 内尺寸差直接拉伸）。
 *
 * @returns {Promise<{width:number,height:number,fit:'contain'|'cover'|'fill'}>} 目标尺寸 + 缩放方式
 */
async function computeTarget(meta, bake) {
  let w = meta.width, h = meta.height;
  let fit = 'fill';
  if (bake?.canvas) {
    const srcRatio = meta.width / meta.height;
    const canvasRatio = bake.canvas.w / bake.canvas.h;
    let cw = bake.canvas.w, ch = bake.canvas.h;
    if (Math.abs(srcRatio - canvasRatio) > 0.001) {
      if (srcRatio > canvasRatio) ch = Math.round(bake.canvas.w / srcRatio);
      else cw = Math.round(bake.canvas.h * srcRatio);
    }
    w = cw; h = ch;
    fit = 'contain';
  } else if (bake?.square) {
    // 中心裁方（REPEAT 地面纹理须 POT——GLES POT 守卫 / Vulkan REPEAT 采样器）：
    // 裁方到 min(短边, square) 再 cover 缩放——内容裁切、不失真；square 传 2 的幂值
    const side = Math.min(Math.min(w, h), bake.square);
    w = side; h = side;
    fit = 'cover';
  } else if (bake?.maxDim) {
    const longest = Math.max(w, h);
    if (longest > bake.maxDim) {
      const k = bake.maxDim / longest;
      w = Math.round(w * k); h = Math.round(h * k);
    }
  }
  // 单素材硬上限
  if (w > MAX_BAKE_DIM || h > MAX_BAKE_DIM) {
    const k = MAX_BAKE_DIM / Math.max(w, h);
    w = Math.round(w * k); h = Math.round(h * k);
  }
  w = Math.max(w, 1); h = Math.max(h, 1);
  // 4 倍数取整（ASTC 4×4 压缩纹理硬性尺寸要求）：向上取整并透明延展，
  // 内容 1:1 落在同比例画布左上——不拉伸、不失真（最多 +3px 透明边）
  if (bake?.roundUp4) {
    w = Math.ceil(w / 4) * 4;
    h = Math.ceil(h / 4) * 4;
    fit = 'contain';
  }
  return { width: w, height: h, fit };
}

async function main() {
  const dryRun = process.argv.includes('--dry-run');
  const mapping = loadMapping();
  console.log(`=== 素材导入（${dryRun ? 'DRY-RUN' : '写入'}）===\n`);

  const summary = { generated: [], unchanged: [], failed: [], version: 1 };
  const outManifest = [];

  // 既有 hash 比对（增量跳过）
  const prev = fs.existsSync(OUT_SUMMARY) ? JSON.parse(fs.readFileSync(OUT_SUMMARY, 'utf8')) : null;
  const prevHashes = new Map((prev?.entries ?? []).map((e) => [e.drawable, e.hash + '|' + (e.bakeKey ?? '')]));

  for (const [drawable, entry] of mapping) {
    if (!entry.source) continue; // 待补（source null）不处理，保持既有产物不动
    const srcFile = path.join(entry.sourceDir ?? 'D:/模拟宗门美术素材', entry.source);
    if (!fs.existsSync(srcFile)) {
      summary.failed.push(drawable);
      throw new Error(`资源缺失: ${entry.source} (${drawable})——检查 source-mapping.json 或源目录`);
    }
    const meta = await sharp(srcFile).metadata();
    const target = await computeTarget(meta, entry.bake);
    // bakeKey 只含「bake 规则 + 目标尺寸」——不含 fit（编码实现细节，非产出内容参数），
    // 否则新增尺寸规则会扰动全部既有条目的 hash，导致全量无谓重烘焙
    const bakeKey = JSON.stringify({ bake: entry.bake, target: { width: target.width, height: target.height } });
    const srcMd5 = md5OfFile(srcFile);
    const hash = srcMd5 + '|' + bakeKey;
    // 内容 hash 增量：未变则跳过（dry-run 也如实报"未变更"，仅显示真实增量）
    if (prevHashes.get(drawable) === hash) {
      summary.unchanged.push(drawable);
      outManifest.push({ drawable, source: entry.source, hash: srcMd5, bakeKey, width: target.width, height: target.height });
      continue;
    }

    // 处理入（仅非 dry-run 才编码 + 写模块；dry-run 只报清单）
    let buf = null;
    if (!dryRun) {
      const encode = sharp(srcFile);
      if (target.fit === 'contain') {
        // 同比例画布：内容 1:1 落位 + 透明边（canvas 专用 / roundUp4 的 4 倍数取整）
        encode.resize(target.width, target.height, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } });
      } else if (target.fit === 'cover') {
        // 中心裁方：内容裁切到正方形（square 规则），不拉伸
        encode.resize(target.width, target.height, { fit: 'cover', position: 'centre', kernel: sharp.kernel.lanczos3 });
      } else if (target.width !== meta.width || target.height !== meta.height) {
        encode.resize(target.width, target.height, { fit: 'fill', kernel: sharp.kernel.lanczos3 });
      }
      if (entry.bake?.seamless) {
        // 先落到目标尺寸，再做无缝平铺处理（偏移平均），最后编码
        const resized = await encode.png().toBuffer();
        const seamless = await makeSeamless(resized, target.width, target.height);
        buf = await sharp(seamless).webp(WEBP_OPTIONS).toBuffer();
      } else {
        buf = await encode.webp(WEBP_OPTIONS).toBuffer();
      }
    }

    if (dryRun) {
      summary.generated.push(drawable);
      outManifest.push({ drawable, source: entry.source, hash: srcMd5, bakeKey, width: target.width, height: target.height, dryRun: true });
      continue;
    }
    for (const mod of entry.modules ?? ['feature/game', 'app']) {
      const dir = MODULE_DIRS[mod];
      if (!dir) throw new Error(`未知模块: ${mod}`);
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, drawable + '.webp'), buf);
    }
    summary.generated.push(drawable);
    outManifest.push({ drawable, source: entry.source, hash: srcMd5, bakeKey, width: target.width, height: target.height });
  }

  if (!dryRun) {
    fs.writeFileSync(OUT_SUMMARY, JSON.stringify({ version: 1, entries: outManifest }, null, 2) + '\n');
  }
  console.log(`  待补(source null): ${[...mapping.values()].filter((e) => !e.source).length}`);
  console.log(`  跳过(未变更): ${summary.unchanged.length}`);
  console.log(`  生成/将生成: ${summary.generated.length}`);
  console.log(`${dryRun ? 'DRY-RUN ' : ''}完成。`);
}

main().catch((err) => {
  console.error('导入失败:', err.message);
  process.exit(1);
});
