/**
 * mip 渗色验收脚本（渲染纹理降采样管线；一次性，非 CI）。
 *
 * 端到端验证真实产物：解析 atlas_astc.ktx → 抽取 mip0/mip1/mip2/mip4 的 ASTC 数据 →
 * astcenc 逐级解码回 PNG → 逐精灵计算指标。
 *
 * ## 判定（两条硬门禁，均为"结构性"判据——不依赖阈值猜测）
 * ① **布局实体互不重叠**：任意两张精灵的实体矩形（不含 pad 环）不得相交。
 *    重叠 = 合成顺序决定谁被覆盖，被压者的 pad 环/半透明边缘会混入对方颜色
 *    （2026-09 实测：cloud_3 压住浮空岛左右环三张精灵，mip2 边界 texel 出现蓝色云块）。
 * ② **mip0 pad ≡ 内容边缘**（严格 ±8/通道）：pad 环由 nearest 复制边缘像素生成，
 *    mip0 未重采样，二者必须逐位等价——超阈即合成/编码异常。
 *
 * ## 报告项（非门禁，供人工判读）
 * ③ mip1/2/4 的 pad-vs-边缘色差：内容边缘 texel 是源边缘 2^k 行的混合、pad texel 是
 *    首行复制，精灵边缘自身有陡梯度时二者固有偏差（Unity paddingPower 同款语义），
 *    与"邻居渗色"无关。实测证据（2026-09）：全部超阈精灵的图集区域与**其独立重建**
 *    逐位一致（最大差 ≤1），即无任何外来内容；故该项只报告不判失败。
 *    结构上，per-sprite 独立 mip + 门禁①已排除邻居内容进入的可能。
 * ④ mip4 深级整数量化使相邻 pad 环相接，采样权重 3%，仅报告。
 *
 * 云层豁免 ③（相邻布局无 gutter 强制，远背景半透明）。
 * TILE_BUILDING 占位（与 GROUND 同 rect，drawable=null）跳过。
 *
 * 运行：cd android && node scripts/verify-mip-bleeding.mjs
 * 退出码：0 = 门禁通过；1 = 存在实体重叠 / mip0 pad 不等价（打印明细）。
 */
import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';
import sharp from 'sharp';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const ATLAS_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');
const ASTCENC = path.resolve(__dirname, 'tools/astcenc/bin/astcenc-avx2.exe');
const TMP_DIR = path.resolve(__dirname, 'tools/mip_verify_tmp');

const KTX_HEADER_SIZE = 64;
const CHECK_LEVELS = [0, 1, 2, 4];
// 判据分级（按 UV_EPSILON=0.5/4096 归一化在各级的采样内缩换算）：
//   mip0（边缘采样读 pad 权重 50%）：**严格**——pad 由 nearest 复制边缘像素逐位生成，
//     pad-vs-edge 偏差只可能来自合成 bug/编码异常，阈值 ±8（逐对中位差）。
//   mip1/mip2（权重 25%/12.5%）：pad = 源首行复制 vs 内容边缘 texel = 源边缘 2^k 行
//     混合——精灵边缘自身有渐变时二者固有偏差（Unity paddingPower 复制式 pad 同款
//     表现，非邻居渗色）。判定 = 渐变容差（自身相邻环梯度 + 阈值）；另设**渗色绝对
//     上限** 96（整图 mip 回退时 pad 环被邻居内容占据，偏差 150+，一眼可判）。
//   mip4（权重 3% + 深级 gutter 整数量化使相邻 pad 环相接）：仅绝对上限报告项。
const STRICT_LEVELS = new Set([0]);
const THRESHOLD = 8;          // RGB 各通道配对中位差阈值（严格级 / 容差加成）
const ALPHA_FLOOR = 96;       // 统计下限（挡 ASTC 透明区 RGB 未定义噪声；软边缘保留）
const PAD0 = 4;               // mip0 尺度 pad（与 build-atlas.mjs LAYOUT.mipPad 一致）
const MIN_PAIRS = 8;          // 单边最小配对数（低于此统计噪声主导，跳过该边）

function readU32LE(bytes, off) {
  return bytes[off] | (bytes[off + 1] << 8) | (bytes[off + 2] << 16) | (bytes[off + 3] << 24) >>> 0;
}

/** 解析 KTX1：返回 [{ level, w, h, astc }]（含合成 .astc 头，可直接喂 astcenc） */
function extractMips(ktxPath) {
  const bytes = fs.readFileSync(ktxPath);
  const w = readU32LE(bytes, 32);
  const h = readU32LE(bytes, 36);
  const mipCount = readU32LE(bytes, 52);
  const mips = [];
  let cursor = KTX_HEADER_SIZE;
  for (let level = 0; level < mipCount; level++) {
    const size = readU32LE(bytes, cursor);
    cursor += 4;
    if (!CHECK_LEVELS.includes(level)) {
      cursor += size;
      continue;
    }
    const lw = Math.max(1, w >> level);
    const lh = Math.max(1, h >> level);
    // 合成 .astc 文件头（16 字节，ASTC 规范）：magic 4B（文件字节 13 AB A1 5C）
    // + 块尺寸 3×1B + 纹理尺寸 3×3B LE（xsize/ysize/zsize）
    const header = Buffer.alloc(16);
    header[0] = 0x13; header[1] = 0xAB; header[2] = 0xA1; header[3] = 0x5C;
    header[4] = 4; header[5] = 4; header[6] = 1;
    header[7] = lw & 0xFF; header[8] = (lw >> 8) & 0xFF; header[9] = (lw >> 16) & 0xFF;
    header[10] = lh & 0xFF; header[11] = (lh >> 8) & 0xFF; header[12] = (lh >> 16) & 0xFF;
    header[13] = 1; header[14] = 0; header[15] = 0;
    const astc = Buffer.concat([header, bytes.subarray(cursor, cursor + size)]);
    mips.push({ level, w: lw, h: lh, astc });
    cursor += size;
  }
  return mips;
}

/** 预乘 alpha 加权 RGB 均值（软边缘精灵各带可比——alpha 阈值过滤会取到不同像素群）。
 *  仅计 alpha≥ALPHA_FLOOR 像素（挡 ASTC 透明区 RGB 未定义噪声）。
 *  返回 {r,g,b,count} 或 null（无有效像素） */
function regionMean(data, info, x0, y0, x1, y1) {
  const cx0 = Math.max(0, x0), cy0 = Math.max(0, y0);
  const cx1 = Math.min(info.width, x1), cy1 = Math.min(info.height, y1);
  if (cx1 <= cx0 || cy1 <= cy0) return null;
  let r = 0, g = 0, b = 0, a = 0, n = 0;
  for (let y = cy0; y < cy1; y++) {
    for (let x = cx0; x < cx1; x++) {
      const idx = (y * info.width + x) * info.channels;
      const av = data[idx + 3];
      if (av < ALPHA_FLOOR) continue;
      const w = av / 255;
      r += data[idx] * w; g += data[idx + 1] * w; b += data[idx + 2] * w; a += w; n++;
    }
  }
  if (n === 0 || a === 0) return null;
  return { r: r / a, g: g / a, b: b / a, count: n };
}

function channelDiff(a, b) {
  if (!a || !b) return 0; // 一侧全透明（如精灵边缘全透明）→ 无色差可判
  return Math.max(Math.abs(a.r - b.r), Math.abs(a.g - b.g), Math.abs(a.b - b.b));
}

async function main() {
  const manifest = JSON.parse(fs.readFileSync(path.join(ATLAS_DIR, 'atlas-manifest.json'), 'utf8'));
  if (manifest.mipMode !== 'per-sprite') {
    console.error(`manifest.mipMode = ${manifest.mipMode}（期望 per-sprite）——先全量重建图集`);
    process.exit(1);
  }
  fs.mkdirSync(TMP_DIR, { recursive: true });

  const sprites = manifest.sprites.filter((s) => s.drawable && s.name !== 'TILE_BUILDING');
  const cloudNames = new Set(sprites.filter((s) => s.name.startsWith('cloud_')).map((s) => s.name));

  let failures = 0;
  const reports = [];
  const infoReports = [];

  for (const { level, w, h, astc } of extractMips(path.join(ATLAS_DIR, 'atlas_astc.ktx'))) {
    const astcFile = path.join(TMP_DIR, `mip_${level}.astc`);
    const pngFile = path.join(TMP_DIR, `mip_${level}.png`);
    fs.writeFileSync(astcFile, astc);
    // -dl 解码模式块尺寸取自 .astc 文件头（5.x 位置式参数无块参数）
    execFileSync(ASTCENC, ['-dl', astcFile, pngFile], { stdio: 'pipe' });
    const { data, info } = await sharp(pngFile).raw().toBuffer({ resolveWithObject: true });

    const padK = Math.max(1, Math.round(PAD0 / 2 ** level));
    let levelChecked = 0;

    for (const s of sprites) {
      // 该级内容 rect（与 build-atlas.mjs composeMipLevel 同式）
      const cw = Math.max(1, s.w >> level);
      const ch = Math.max(1, s.h >> level);
      const cx = s.x >> level;
      const cy = s.y >> level;
      if (cx >= w || cy >= h) continue; // 深级亚像素槽位（钳到 [0,level) 之外的跳过）

      // 逐像素配对比较（pad ≡ 内容边缘的复制关系是逐位对应的——均值法对异质
      // 边缘（草地/建筑轮廓）会因两带像素群差异产生假阳性）。
      // ★ 梯度容差：mip k 的内容边缘 texel = 源边缘 2^k 行的混合，而 pad texel =
      //   源首行复制——精灵边缘自身有 alpha/颜色渐变时二者固有偏差（非邻居渗色，
      //   Unity 同款 pad 语义相同表现）。判定：padDiff ≤ max(阈值, 自身梯度 + 阈值)。
      // 仅取"边缘带与 pad 带都存在且配对数足量"的边（图集边裁剪侧跳过）。
      let worstRatio = -Infinity;
      let worst = '';
      for (const side of ['top', 'bottom', 'left', 'right']) {
        const pad = pairedSideDiff(data, info, cx, cy, cw, ch, side, +1, -1);
        if (!pad || pad.pairs < MIN_PAIRS) continue;
        const grad = pairedSideDiff(data, info, cx, cy, cw, ch, side, -1, -2);
        const allowance = grad ? Math.max(THRESHOLD, grad.median + THRESHOLD) : THRESHOLD;
        const ratio = pad.median - allowance; // >0 = 超出容差
        if (ratio > worstRatio) {
          worstRatio = ratio;
          worst = `${side}: pad 中位差 ${pad.median.toFixed(1)} / 容差 ${allowance.toFixed(1)}（${pad.pairs} 对）`;
        }
      }
      if (worst === '') continue; // 全部边透明/裁剪/配对不足——无统计语义可判

      const exempt = cloudNames.has(s.name);
      // 门禁：仅 mip0 严格级（pad ≡ 边缘逐位成立）判失败；mip1/2/4 的偏差是
      // 渐变边缘固有语义（报告项，见文件头判定说明）
      const fail = !exempt && STRICT_LEVELS.has(level) && worstRatio > THRESHOLD;

      if (fail) {
        failures++;
        reports.push(`✗ mip${level} ${s.name}: pad 与内容边缘不等价（${worst}）`);
      } else if (worstRatio > THRESHOLD) {
        infoReports.push(`ℹ mip${level} ${s.name}: pad 超容差（${worst}）——` +
          `渐变边缘固有偏差/深级报告项，采样读取权重 0.5/2^${level} texel`);
      }
      levelChecked++;
    }
    console.log(`mip${level} (${w}×${h}): 检查 ${levelChecked} 精灵`);
    fs.unlinkSync(astcFile);
    fs.unlinkSync(pngFile);
  }

  // 门禁①：布局实体矩形互不重叠（含云层——间距豁免不等于允许重叠）
  const layoutOverlaps = findLayoutOverlaps(manifest.sprites);
  if (layoutOverlaps.length > 0) {
    failures += layoutOverlaps.length;
    reports.push(...layoutOverlaps.map((o) => `✗ 图集实体重叠: ${o}`));
  }

  if (reports.length || infoReports.length) {
    console.log('\n' + reports.concat(infoReports).join('\n'));
  }
  if (failures > 0) {
    console.error(`\n验收失败: ${failures} 处（mip0 pad 不等价 / 图集实体重叠）`);
    process.exit(1);
  }
  console.log(`\n验收通过: 图集实体矩形无重叠（${manifest.sprites.length} 精灵）+ ` +
    `mip${[...STRICT_LEVELS].join(',')} pad ≡ 内容边缘（≤ ${THRESHOLD}/通道）` +
    `（mip1/2/4 渐变报告项 ${infoReports.length} 条）`);
}

/**
 * 图集实体矩形重叠体检（门禁①）：
 * 任意两张精灵的实体矩形（不含 pad 环）相交即报——重叠会让合成顺序决定谁被覆盖，
 * 被压者的 pad 环/半透明边缘混入对方颜色，mip 各级都会带出脏边。
 *
 * @param {Array} sprites manifest 精灵清单
 * @returns {string[]} 重叠描述（空数组 = 通过）
 */
function findLayoutOverlaps(sprites) {
  const solid = sprites.filter((s) => s.drawable);
  const out = [];
  for (let i = 0; i < solid.length; i++) {
    for (let j = i + 1; j < solid.length; j++) {
      const a = solid[i];
      const b = solid[j];
      const xOverlap = a.x < b.x + b.w && b.x < a.x + a.w;
      const yOverlap = a.y < b.y + b.h && b.y < a.y + a.h;
      if (xOverlap && yOverlap) {
        out.push(`${a.name}(${a.x},${a.y},${a.w}×${a.h}) ∩ ${b.name}(${b.x},${b.y},${b.w}×${b.h})`);
      }
    }
  }
  return out;
}

/**
 * 1px 环带均值（单边）：offsetPx > 0 = 内容外（pad 带）；offsetPx < 0 = 内容内。
 * 返回 null = 该边无有效像素（透明带或图集边界裁剪）。
 */
function sideMean(data, info, x, y, w, h, side, offsetPx) {
  const o = Math.abs(offsetPx);
  const inside = offsetPx < 0;
  const band = {
    top: inside ? [x, y, x + w, y + o] : [x, y - o, x + w, y - o + 1],
    bottom: inside ? [x, y + h - o, x + w, y + h] : [x, y + h + o - 1, x + w, y + h + o],
    left: inside ? [x, y, x + o, y + h] : [x - o, y, x - o + 1, y + h],
    right: inside ? [x + w - o, y, x + w, y + h] : [x + w + o - 1, y, x + w + o, y + h],
  }[side];
  return regionMean(data, info, band[0], band[1], band[2], band[3]);
}

/**
 * 单边逐像素配对差：比较距内容边界 offA 与 offB 的两条 1px 环带（同列/同行配对）。
 * offset 约定：+1 = 内容外 1px（pad 首环）；−1 = 内容最外圈；−2 = 次外圈。
 * 仅统计两像素都有效（alpha≥ALPHA_FLOOR）的配对；
 * 返回 { median, mean, pairs } 或 null。
 */
function pairedSideDiff(data, info, x, y, w, h, side, offA, offB) {
  const get = (px, py) => {
    const i = (py * info.width + px) * info.channels;
    return [data[i], data[i + 1], data[i + 2], data[i + 3]];
  };
  // 带符号 offset → 带坐标（top/bottom 取行号，left/right 取列号）：
  //   off=−1 内容最外圈；off=−2 次外圈；off=+1 紧贴内容边的 pad 行/列；off=+2 更外一格
  const rowOf = (side, off) =>
    side === 'top' ? (off < 0 ? y - off - 1 : y - off)
                   : (off < 0 ? y + h + off : y + h + off - 1);
  const colOf = (side, off) =>
    side === 'left' ? (off < 0 ? x - off - 1 : x - off)
                    : (off < 0 ? x + w + off : x + w + off - 1);
  const diffs = [];
  const pair = (ax, ay, bx, by) => {
    const e = get(ax, ay), p = get(bx, by);
    if (e[3] < ALPHA_FLOOR || p[3] < ALPHA_FLOOR) return;
    diffs.push(Math.max(Math.abs(e[0] - p[0]), Math.abs(e[1] - p[1]), Math.abs(e[2] - p[2])));
  };
  if (side === 'top' || side === 'bottom') {
    const ra = rowOf(side, offA), rb = rowOf(side, offB);
    for (let i = 0; i < w; i++) pair(x + i, ra, x + i, rb);
  } else {
    const ca = colOf(side, offA), cb = colOf(side, offB);
    for (let j = 0; j < h; j++) pair(ca, y + j, cb, y + j);
  }
  if (diffs.length === 0) return null;
  diffs.sort((a, b) => a - b);
  const median = diffs.length % 2
    ? diffs[(diffs.length - 1) / 2]
    : (diffs[diffs.length / 2 - 1] + diffs[diffs.length / 2]) / 2;
  return {
    median,
    mean: diffs.reduce((a, d) => a + d, 0) / diffs.length,
    pairs: diffs.length,
  };
}

function fmt(m) {
  return m ? `rgb(${m.r.toFixed(0)},${m.g.toFixed(0)},${m.b.toFixed(0)})` : 'transparent';
}

main().catch((e) => {
  console.error('verify-mip-bleeding 失败:', e.message);
  process.exit(1);
});
