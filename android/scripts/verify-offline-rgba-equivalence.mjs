#!/usr/bin/env node
/**
 * 离线 RGBA 产物等价性守卫（B15 / R6.1 任务 5）。
 *
 * ## 守卫什么
 *
 * B15 把 Canvas 软渲染 + RGBA 回退臂的**像素来源**从「设备上运行时拼装
 * （`SectAtlasAssembler.buildAtlasBitmap`，Skia 双线性逐精灵绘制）」切换为
 * 「构建期离线产物（sharp lanczos3 逐精灵降采样）」。两条独立缩放实现不可能
 * 逐位相同，故本脚本按**三层口径**给出可复跑的对照：
 *
 *   1. **结构等价（逐位）**：产物槽位几何必须与 `SpriteAtlasDef` 权威一致
 *      （`round(x × 0.5)` / `slot >> 1`，且 2048 下无重叠）；
 *   2. **golden 校验和（逐槽位）**：产物逐精灵切片的 sha256 必须与清单一致
 *      ——锁住产物未被意外改写（手工替换资产、构建中断留下半截文件）；
 *   3. **像素级对照**：分两个子层，**确定性**与**容差**分开判定：
 *      - **3a 确定性（逐位，硬门）**：按**产物真实生产链**重建每个精灵
 *        （`源 → 预乘 → lanczos3 缩到槽位尺寸 → lanczos3 缩到槽位半尺寸 → 解预乘`），
 *        要求与产物切片**逐位相等**（`Buffer.compare === 0`）。
 *        这锁住「产物可被本仓库脚本确定性复现」，是仓库级硬门。
 *      - **3b 消费面容差（软门）**：把「运行时拼装（Skia 双线性，预乘空间）」
 *        与「离线产物（lanczos3）」的差异量化，按**实测分布**给阈值。
 *
 * ## 口径说明（显式登记，见批次 B15 任务 5）
 *
 * **实测结论一：无任何重采样核能与 Skia 双线性逐位对齐。** 本轮已实测四组
 * 候选（源 → 槽位半尺寸，同源同尺寸）：
 *
 *   | 配方 | 超阈值（>8/255）占比区间 |
 *   |---|---|
 *   | `lanczos3`（产物所发） | 4.68% – 12.50% |
 *   | `linear`（双线性同核） | 4.68% – 12.50% |
 *   | 链式减半（双线性分步） | 10.47% – 26.50% |
 *   | 精确区域平均（box） | 3.59% – 24.00% |
 *
 * 差异随源图 → 槽位的缩放比而变（2:1 的 128² 源最小、436×524 → 64² 最大），
 * 但**没有任何核能把占比压到 0**——Skia 的 `isFilterBitmap` 采样相位/边界
 * 外推与 libvips 实现相互独立，逐位一致在工程上不可达。
 *
 * **实测结论二：链式缩放与单步缩放不等价。** 产物链是
 * `源 →(lanczos3) 槽位 →(lanczos3) 半槽位`（因为产物复用 ASTC 图集的
 * `contents`，那已是「源 → 槽位」的结果）；任何「源 → 半槽位」单步重建
 * （无论何种核）都与产物**逐位不符**（实测 GRASS1 maxDiff=255）。故 3a
 * 必须按真实生产链复现，不能假定「缩放可合并」。
 *
 * **实测结论三：alpha 与颜色分列。** alpha 是几何/覆盖率的不变量，两配方
 * 都在预乘空间做面积重采样 ⇒ alpha 差异只来自核形状；颜色在低 alpha 区被
 * `unpremultiply`（除以小 a）放大 ⇒ 颜色阈值必然宽于 alpha。故分列阈值。
 *
 * ## 实跑
 *
 *   node scripts/verify-offline-rgba-equivalence.mjs
 *
 * 退出码 0 = 全绿；非 0 = 守卫变红（附明细）。
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import {
  OFFLINE_SCALE,
  buildOfflineSpriteList,
} from './lib/atlas-offline-rgba-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const ASSET_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');

/**
 * **软门阈值（消费面容差）** —— 基于实测分布设定（见头注「实测结论一」）。
 *
 * 四组候选配方的实测占比区间为 3.59% – 26.50%，其中产物所发的 `lanczos3`
 * 与双线性同核 `linear` 在 4.68% – 12.50%。故：
 *   - 颜色通道：容差 8/255，占比上界 **20%**（覆盖 lanczos3 实测上界 12.50%
 *     并留出余量；同时能拦截「核替换错到 box/链式」一类的退化——它们可达
 *     24%–26.5%，一旦越界即红）；
 *   - alpha 通道：容差 8/255，占比上界 **15%**（alpha 为几何不变量，理应更窄；
 *     实测 SEEDLING 50/255 的最大差只出现在极低 alpha 的抖动像素上）。
 *
 * ⚠ 阈值是「**守卫退化**」而非「**证明等价**」：本层无法把差异降到 0（实测
 * 结论一）。真正的等价性硬门是 3a 确定性逐位对照 + 层 1/层 2 的几何与 golden
 * 校验和。此处若变红，说明差异已超出「核形状噪声」的解释范围。
 */
const PIXEL_TOLERANCE = 8;
const MAX_OUTLIER_RATIO = 0.2;
const ALPHA_TOLERANCE = 8;
const MAX_ALPHA_OUTLIER_RATIO = 0.15;

const DOWN = 2048;

function sha256hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/** 按槽位从 2048² 裸像素切出单精灵缓冲（与 atlas-offline-rgba.mjs 同式） */
function sliceSlot(frame, x, y, w, h) {
  const dw = w >> 1;
  const dh = h >> 1;
  const dx = Math.round(x * OFFLINE_SCALE);
  const dy = Math.round(y * OFFLINE_SCALE);
  const rows = [];
  for (let r = 0; r < dh; r++) {
    const off = ((dy + r) * DOWN + dx) * 4;
    rows.push(frame.subarray(off, off + dw * 4));
  }
  return Buffer.concat(rows);
}

/**
 * **配方 A（产物真实生产链，3a 硬门）**：逐位复现产物像素。
 *
 * 产物链与「一步到位」的 `extractSlotRgba` **不同**（实测结论二）：产物复用
 * ASTC 图集的 `contents`，而 `contents` 已是「源 →(lanczos3) 槽位」的结果，
 * 产物再对其做「槽位 →(lanczos3) 半槽位」。故必须两段复现：
 *
 *   源 →(预乘) →(lanczos3) 槽位(4096 系) →(lanczos3) 半槽位(2048 系) →(解预乘)
 *
 * 这与 `build-atlas.mjs loadSpriteContents` + `lib/…writeOfflineRgbaArtifacts`
 * 的接线**逐字对齐**（同一 `loadPremultipliedRgba`、同一 `lanczos3`、同一
 * `fit:'fill'`）。
 */
async function extractSlotRgbaProductionChain(srcPath, w, h) {
  const { default: sharp } = await import('sharp');
  const { loadPremultipliedRgba, unpremultiplyRgba } = await import(
    './lib/atlas-offline-rgba-lib.mjs'
  );
  const dw = Math.max(1, w >> 1);
  const dh = Math.max(1, h >> 1);
  const { pm, width, height, transparent } = await loadPremultipliedRgba(srcPath);
  // 第一段：源 → 槽位（= ASTC 图集的 contents 口径）
  const slotPng = await sharp(pm, { raw: { width, height, channels: 4 } })
    .resize(w, h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .png()
    .toBuffer();
  // 第二段：槽位 → 半槽位（= 离线产物口径）
  const scaledPm = await sharp(slotPng)
    .resize(dw, dh, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .ensureAlpha()
    .raw()
    .toBuffer();
  return {
    data: transparent ? unpremultiplyRgba(scaledPm) : scaledPm,
    premultiplied: transparent,
  };
}

/**
 * **配方 B（运行时消费面，3b 软门）**：与 `SectAtlasAssembler.drawSlotsToAtlas`
 * + Skia 语义对位。
 *
 * 三层对位：
 *   1. **加载期预乘** —— 历史上 `drawBitmap` 到 `ARGB_8888` 目标位图，Android
 *      位图恒为预乘存储，故「解码 → 绘制」链路里参与插值的颜色值恒是**预乘**值；
 *      半透明像素透明区的直通颜色**不参与**插值（a=0 处颜色被丢弃）。
 *   2. **预乘空间重采样** —— `isFilterBitmap=true` 的双线性在预乘空间做，
 *      采样口径取 `sharp.kernel.linear`（三角窗，双线性同核）。
 *   3. **解回直通 alpha** —— 输出统一为直通 alpha（与产物口径一致）。
 *
 * ⚠ 若此处省略预乘（直接对 straight alpha 做 `resize`），透明区的未定义颜色会
 * 被当成有效色值参与插值，产生 maxDiff=255 的**假阳性**（实测 GRASS1 16.5%
 * 超阈值）——那不是核差异，而是口径错误。
 *
 * ⚠ 本配方刻意走「源 → 半槽位」**单步**：运行时拼装确实是单步（Skia 一次性
 * 把源 drawable 绘制到 2048 目标位图的半尺寸槽位上）。产物是**两段**（实测
 * 结论二），故 3b 量化的是「两段 lanczos3 vs 单步双线性」的**端到端**差异，
 * 这正是用户实际会看到的口径。
 */
async function extractSlotRgbaBilinear(srcPath, w, h, scale) {
  const { default: sharp } = await import('sharp');
  const { premultiplyRgba, unpremultiplyRgba } = await import(
    './lib/atlas-offline-rgba-lib.mjs'
  );
  const dw = Math.max(1, Math.round(w * scale));
  const dh = Math.max(1, Math.round(h * scale));
  const { data, info } = await sharp(srcPath)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const pm = premultiplyRgba(data);
  const scaledPm = await sharp(pm, {
    raw: { width: info.width, height: info.height, channels: 4 },
  })
    .resize(dw, dh, { fit: 'fill', kernel: 'linear' })
    .raw()
    .toBuffer();
  return unpremultiplyRgba(scaledPm);
}


async function main() {
  const manifestPath = path.join(ASSET_DIR, 'atlas-rgba-manifest.json');
  const rawPath = path.join(ASSET_DIR, 'atlas-rgba-raw.bin');
  if (!fs.existsSync(manifestPath) || !fs.existsSync(rawPath)) {
    console.error('守卫失败：离线产物缺失——先运行 node scripts/atlas-offline-rgba.mjs');
    process.exit(1);
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const frame = fs.readFileSync(rawPath);

  let failures = 0;
  const fail = (msg) => {
    console.error(`  ✗ ${msg}`);
    failures++;
  };

  // ── 层 1：结构等价（槽位几何 vs 权威清单） ──
  console.log('层 1：结构等价（槽位几何 vs SpriteAtlasDef 权威）');
  const sprites = await buildOfflineSpriteList();
  if (manifest.spriteCount !== sprites.length) {
    fail(`清单 spriteCount ${manifest.spriteCount} != 权威可绘制精灵数 ${sprites.length}`);
  }
  if (manifest.width !== DOWN || manifest.height !== DOWN) {
    fail(`产物尺寸 ${manifest.width}×${manifest.height} != ${DOWN}²（sourceScale 契约 0.5）`);
  }
  // 无重叠（Canvas over 覆盖顺序无歧义前提）
  const placed = [];
  for (const s of sprites) {
    const p = {
      name: s.name,
      x: Math.round(s.x * OFFLINE_SCALE),
      y: Math.round(s.y * OFFLINE_SCALE),
      w: s.w >> 1,
      h: s.h >> 1,
    };
    for (const q of placed) {
      const hitX = p.x < q.x + q.w && q.x < p.x + p.w;
      const hitY = p.y < q.y + q.h && q.y < p.y + p.h;
      if (hitX && hitY) fail(`槽位重叠: ${p.name} × ${q.name}（over 覆盖顺序会改变像素）`);
    }
    if (p.x + p.w > DOWN || p.y + p.h > DOWN) {
      fail(`槽位越界: ${p.name} (${p.x},${p.y})+${p.w}×${p.h}`);
    }
    placed.push(p);
  }
  if (!failures) console.log(`  ✓ ${sprites.length} 槽位几何一致、无重叠、无越界`);

  // ── 层 2：golden 校验和（逐槽位） ──
  console.log('层 2：golden 校验和（逐槽位 sha256）');
  if (sha256hex(frame) !== manifest.frameSha256) {
    fail(`整图 sha256 不符：产物被改写（${sha256hex(frame)} != ${manifest.frameSha256}）`);
  }
  const byName = new Map(manifest.sprites.map((s) => [s.name, s]));
  let checked = 0;
  for (const s of sprites) {
    const rec = byName.get(s.name);
    if (!rec) {
      fail(`清单缺少槽位记录: ${s.name}`);
      continue;
    }
    const slot = sliceSlot(frame, s.x, s.y, s.w, s.h);
    if (slot.length !== rec.bytes) {
      fail(`${s.name} 切片长度 ${slot.length} != 清单 ${rec.bytes}`);
      continue;
    }
    if (sha256hex(slot) !== rec.sha256) {
      fail(`${s.name} 槽位 sha256 不符（产物像素与清单记录不一致）`);
      continue;
    }
    checked++;
  }
  if (checked === sprites.length) {
    console.log(`  ✓ 整图 sha256 匹配 + ${checked}/${sprites.length} 逐槽位校验和一致`);
  }

  // ── 层 3a：确定性逐位对照（硬门——产物必须可被本仓库脚本复现） ──
  console.log('层 3a：确定性逐位对照（产物真实生产链重建 vs 产物切片）');
  const spritesRebuilt = [];
  let bitExact = 0;
  for (const s of sprites) {
    const a = await extractSlotRgbaProductionChain(s.srcPath, s.w, s.h);
    const fromProduct = sliceSlot(frame, s.x, s.y, s.w, s.h);
    if (Buffer.compare(a.data, fromProduct) !== 0) {
      // 首个差异像素（定位用）
      let at = -1;
      for (let i = 0; i < a.data.length; i++) {
        if (a.data[i] !== fromProduct[i]) {
          at = i;
          break;
        }
      }
      fail(
        `${s.name} 生产链重建与产物切片逐位不符（首个差异 byte ${at}：` +
          `重建=${a.data[at]} 产物=${fromProduct[at]}）`
      );
      continue;
    }
    spritesRebuilt.push({ s, data: a.data });
    bitExact++;
  }
  if (bitExact === sprites.length) {
    console.log(`  ✓ ${bitExact}/${sprites.length} 逐位复现（产物 = 生产链确定性输出，无手工改写）`);
  }

  // ── 层 3b：消费面容差对照（软门——两段 lanczos3 vs 单步双线性） ──
  console.log('层 3b：消费面容差对照（离线产物两段 lanczos3 vs 运行时单步双线性）');
  let worstName = null;
  let worstMax = -1;
  let totalPixels = 0;
  let totalOutliers = 0;
  let totalAlphaPixels = 0;
  let totalAlphaOutliers = 0;
  const perSprite = [];
  for (const { s, data: a } of spritesRebuilt) {
    // 配方 B（运行时配方对位：预乘空间双线性 + 解预乘，单步到半槽位）
    const b = await extractSlotRgbaBilinear(s.srcPath, s.w, s.h, OFFLINE_SCALE);
    let maxDiff = 0;
    let outliers = 0;
    let alphaMaxDiff = 0;
    let alphaOutliers = 0;
    for (let i = 0; i < a.length; i++) {
      const d = Math.abs(a[i] - b[i]);
      const isAlpha = i % 4 === 3;
      if (d > maxDiff) maxDiff = d;
      if (d > PIXEL_TOLERANCE) outliers++;
      if (isAlpha) {
        if (d > alphaMaxDiff) alphaMaxDiff = d;
        if (d > ALPHA_TOLERANCE) alphaOutliers++;
      }
    }
    const px = a.length;
    const alphaPx = px / 4;
    totalPixels += px;
    totalOutliers += outliers;
    totalAlphaPixels += alphaPx;
    totalAlphaOutliers += alphaOutliers;
    perSprite.push({ name: s.name, maxDiff, outliers, px, alphaMaxDiff, alphaOutliers, alphaPx });
    if (alphaMaxDiff > worstMax) {
      worstMax = alphaMaxDiff;
      worstName = s.name;
    }
  }
  const ratio = totalPixels === 0 ? 0 : totalOutliers / totalPixels;
  const alphaRatio = totalAlphaPixels === 0 ? 0 : totalAlphaOutliers / totalAlphaPixels;
  // 明细按 alpha 最大差排序（alpha = 几何不变量，最敏感），取前 5
  perSprite.sort((x, y) => y.alphaMaxDiff - x.alphaMaxDiff || y.maxDiff - x.maxDiff);
  console.log('  alpha 最大差 Top5（颜色通道差异随低 alpha 放大，属预期）:');
  for (const p of perSprite.slice(0, 5)) {
    console.log(
      `    ${p.name}: alphaMaxDiff=${p.alphaMaxDiff}/255 (${p.alphaOutliers}/${p.alphaPx}), ` +
        `colorMaxDiff=${p.maxDiff}/255`
    );
  }
  console.log(
    `  汇总: worst-alpha='${worstName}' alphaMaxDiff=${worstMax}/255, ` +
      `alphaOutliers=${totalAlphaOutliers}/${totalAlphaPixels} (${(alphaRatio * 100).toFixed(4)}%)`
  );
  console.log(
    `        颜色通道 outliers=${totalOutliers}/${totalPixels} (${(ratio * 100).toFixed(4)}%), ` +
      `阈值 ≤${MAX_OUTLIER_RATIO * 100}%`
  );
  if (alphaRatio > MAX_ALPHA_OUTLIER_RATIO) {
    fail(
      `alpha 超阈值占比 ${(alphaRatio * 100).toFixed(4)}% > ${MAX_ALPHA_OUTLIER_RATIO * 100}%` +
        `（超出核形状噪声区间——几何/覆盖率语义可能已变）`
    );
  } else {
    console.log(`  ✓ alpha 通道容差判定通过（阈值 ≤${ALPHA_TOLERANCE}/255 且占比 ≤${MAX_ALPHA_OUTLIER_RATIO * 100}%）`);
  }
  if (ratio > MAX_OUTLIER_RATIO) {
    fail(`颜色超阈值像素占比 ${(ratio * 100).toFixed(4)}% > ${MAX_OUTLIER_RATIO * 100}%（配方系统性差异）`);
  } else {
    console.log(`  ✓ 颜色通道容差判定通过（阈值 ≤${PIXEL_TOLERANCE}/255 且占比 ≤${MAX_OUTLIER_RATIO * 100}%）`);
  }

  if (failures > 0) {
    console.error(`\n等价性守卫变红：${failures} 项失败`);
    process.exit(1);
  }
  console.log(
    '\n等价性守卫全绿：结构等价 + golden 校验和 + 确定性逐位复现 + 消费面容差对照'
  );
  console.log(
    '（口径登记：3b 容差为「守卫退化」而非「证明等价」——见头注实测结论一）'
  );
}

main().catch((e) => {
  console.error('verify-offline-rgba-equivalence 失败:', e.message);
  process.exit(1);
});
