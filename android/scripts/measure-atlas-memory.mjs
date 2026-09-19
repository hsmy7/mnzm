#!/usr/bin/env node
/**
 * 内存尖峰消除实证（B15 / R6.1 任务 4）。
 *
 * ## 为什么需要本脚本
 *
 * B15 前，启动期像素来源有三个消费者共用一个「运行时 Canvas 拼装」实现
 * （`SectAtlasAssembler.buildAtlasBitmap`）：
 *
 *   - RGBA 回退臂（ASTC 不可用时的降级纹理）
 *   - RGBA mip 链（`encodeBitmapToRgbaMipChain`，逐级 Bitmap 缩放）
 *   - Canvas 软渲染位图（`SoftwareCanvasBackend` 的像素源）
 *
 * 该路径的分配特征（每项都是**峰值叠加**量，非「稳定占用」）：
 *
 *   1. 目标图集位图 `Bitmap.createBitmap(2048, 2048, ARGB_8888)` = 16 MiB；
 *   2. 逐精灵 `BitmapFactory.decodeResource` 的中文源图解码缓冲——最大的
 *      drawable 为 2176×1888（GRASS1 源），解码后 ≈ 16 MiB，且**同时**存在；
 *   3. mip 链编码：逐级 `Bitmap.createScaledBitmap` 的中间位图（2048→1024→…）；
 *   4. 每精灵的 `Canvas.drawBitmap` 会按需在目标位图内做采样，无额外大缓冲。
 *
 * B15 后：像素源改为构建期产物，运行时只剩
 *   - `FileChannel.map` 的**只读 direct ByteBuffer**（mapped，不计入 Java heap；
 *     受 OS page cache 管理，按需换入）；
 *   - 软渲路径一次性 `Bitmap.copyPixelsFromBuffer`（16 MiB，与旧目标位图同量——
 *     这一项**未消除**，属诚实残余）。
 *
 * ## 本脚本做什么
 *
 * 用**真实源图尺寸与真实槽位布局**（来自 `SpriteAtlasDef` 权威解析）在 Node
 * 侧做同口径的**解析式**分配核算 + sharp 实测单张解码峰值，给出前后对照表。
 * 数字可复跑（源图未变则结果稳定），不依赖设备/模拟器。
 *
 * 实跑：
 *   node scripts/measure-atlas-memory.mjs
 *   node scripts/measure-atlas-memory.mjs --json     # 机器可读
 *
 * ## 诚实边界（重要）
 *
 * - 本脚本量的是**算法分配量**（按位图规格推导），不是 Android 进程 RSS。
 *   真机 RSS 还受 GC 时机、NativeAllocationRegistry、page cache 影响。
 * - 「逐精灵解码缓冲峰值」取**单张最大源图**（保守下界；真实峰值是
 *   「目标位图 + 当前这一张」——因为旧实现逐张解码、绘制后交由 GC，
 *   未显式 recycle）。若多张在同一 GC 周期内存活，峰值会更高。
 * - 软渲 16 MiB `Bitmap` 两项都在（旧：目标位图；新：产物解码位图），
 *   故**净消除**是「逐精灵解码 + mip 中间位图 + 拼装期 Canvas 采样开销」。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildOfflineSpriteList,
  loadPremultipliedRgba,
} from './lib/atlas-offline-rgba-lib.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const ASSET_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');

const DOWN = 2048;
const MIP_MIN_EDGE = 2;

const MiB = (bytes) => bytes / (1024 * 1024);
const fmt = (bytes) => `${MiB(bytes).toFixed(2)} MiB`;

/** 旧路径：目标图集位图（2048² ARGB_8888 = 4 B/px） */
function oldAtlasBitmapBytes() {
  return DOWN * DOWN * 4;
}

/** 旧路径：逐精灵解码缓冲峰值（取单张最大源图，ARGB_8888 解码口径） */
function oldDecodePeakBytes(spriteSizes) {
  let maxSrc = 0;
  let maxName = null;
  for (const s of spriteSizes) {
    const bytes = s.w * s.h * 4;
    if (bytes > maxSrc) {
      maxSrc = bytes;
      maxName = s.name;
    }
  }
  return { bytes: maxSrc, name: maxName };
}

/** 旧路径：mip 链编码中间位图峰值（逐级 createScaledBitmap，峰值 = 最大两级同时存活） */
function oldMipEncodingPeakBytes() {
  // 自 2048 逐级减半到 2：编码第 k 级时，源级与目标级同时存活，
  // 故峰值 = L0（2048²）+ L1（1024²）——实际实现里「上一级」就是源。
  const l0 = DOWN * DOWN * 4;
  const l1 = (DOWN >> 1) * (DOWN >> 1) * 4;
  return { peak: l0 + l1, l0, l1 };
}

/** 新路径：离线产物 mapped 区（只读 direct ByteBuffer，非 Java heap） */
function newMappedBytes(rawBytes, mipBytes) {
  return { raw: rawBytes, mip: mipBytes, total: rawBytes + mipBytes };
}

/** 新路径：软渲一次性解码位图（16 MiB，未消除） */
function newSoftwareBitmapBytes() {
  return DOWN * DOWN * 4;
}

async function main() {
  const asJson = process.argv.includes('--json');
  const sprites = await buildOfflineSpriteList();

  // 真实源图尺寸（sharp metadata 实测，与解码口径一致）
  const sizes = [];
  for (const s of sprites) {
    const { width, height } = await loadPremultipliedRgba(s.srcPath).then((r) => r);
    sizes.push({ name: s.name, w: width, h: height, slotW: s.w, slotH: s.h });
  }

  const oldAtlas = oldAtlasBitmapBytes();
  const decode = oldDecodePeakBytes(sizes);
  const mipEnc = oldMipEncodingPeakBytes();

  const manifest = JSON.parse(
    fs.readFileSync(path.join(ASSET_DIR, 'atlas-rgba-manifest.json'), 'utf8')
  );
  const mapped = newMappedBytes(manifest.rawBytes, manifest.mipBytes);
  const swBitmap = newSoftwareBitmapBytes();

  // ── 回退臂（非软渲）：旧 = 目标位图 + 解码峰值 + mip 编码峰值；新 = mapped ──
  const oldFallback = oldAtlas + decode.bytes + mipEnc.peak;
  // 注：旧路径的「目标位图」与「解码缓冲」确实同时存活（解码后立即绘制进目标位图）。
  const newFallback = mapped.raw; // 回退臂只映射 raw（mip 另按需映射）

  // ── 软渲臂：旧 = 目标位图 + 解码峰值；新 = 解码位图（一次性） ──
  const oldSoftware = oldAtlas + decode.bytes;
  const newSoftware = swBitmap;

  const rows = [
    {
      项: '目标图集位图（ARGB_8888 2048²）',
      旧: fmt(oldAtlas),
      新: '—（已消除）',
      说明: '旧：SectAtlasAssembler.buildAtlasBitmap 的目标位图',
    },
    {
      项: '逐精灵源图解码缓冲（单张峰值）',
      旧: fmt(decode.bytes),
      新: '—（已消除）',
      说明: `旧：BitmapFactory.decodeResource 最大源图 ${decode.name} ${sizes.find((s) => s.name === decode.name)?.w}×${sizes.find((s) => s.name === decode.name)?.h}`,
    },
    {
      项: 'mip 链编码中间位图（峰值）',
      旧: fmt(mipEnc.peak),
      新: '—（已消除）',
      说明: '旧：encodeBitmapToRgbaMipChain 逐级 createScaledBitmap',
    },
    {
      项: '离线产物映射区（只读 direct ByteBuffer）',
      旧: '—',
      新: fmt(mapped.raw) + '（raw）',
      说明: '新：FileChannel.map，非 Java heap，受 OS page cache 管理',
    },
    {
      项: '软渲解码位图（一次性）',
      旧: fmt(oldAtlas) + '（= 目标位图）',
      新: fmt(swBitmap),
      说明: '软渲臂旧新均为 16 MiB 级——此项**未消除**，属诚实残余',
    },
    {
      项: '回退臂峰值合计（Java heap 口径）',
      旧: fmt(oldFallback),
      新: fmt(0) + '（mapped 不计 heap）',
      说明: '旧 = 目标位图 + 解码峰值 + mip 编码峰值',
    },
    {
      项: '软渲臂峰值合计（Java heap 口径）',
      旧: fmt(oldSoftware),
      新: fmt(newSoftware),
      说明: '旧 = 目标位图 + 解码峰值；新 = 产物解码位图',
    },
  ];

  if (asJson) {
    console.log(
      JSON.stringify(
        {
          oldFallbackBytes: oldFallback,
          newFallbackHeapBytes: 0,
          newFallbackMappedBytes: newFallback,
          oldSoftwareBytes: oldSoftware,
          newSoftwareBytes: newSoftware,
          oldAtlasBytes: oldAtlas,
          decodePeakBytes: decode.bytes,
          decodePeakName: decode.name,
          mipEncodingPeakBytes: mipEnc.peak,
          mappedRawBytes: mapped.raw,
          mappedMipBytes: mapped.mip,
          spriteCount: sprites.length,
          sourceSizes: sizes.map((s) => ({ name: s.name, w: s.w, h: s.h })),
        },
        null,
        2
      )
    );
    return;
  }

  console.log('B15 / R6.1 内存尖峰前后对照（算法分配量，Java heap 口径）');
  console.log(`可绘制精灵 ${sprites.length}，源图最大 ${decode.name}`);
  console.log('');
  const widths = [4, 30, 12, 46];
  const header = rows[0] && Object.keys(rows[0]);
  console.log(
    header.map((h, i) => h.padEnd(widths[i] ?? 20)).join('')
  );
  console.log('-'.repeat(widths.reduce((a, b) => a + b, 0)));
  for (const r of rows) {
    console.log(
      [r.项, r.旧, r.新, r.说明]
        .map((v, i) => String(v).padEnd(widths[i] ?? 20))
        .join('')
    );
  }
  console.log('');
  console.log('净效果（Java heap 峰值）：');
  console.log(
    `  回退臂：${fmt(oldFallback)} → 0（mapped ${fmt(newFallback)} 转出 heap）` +
      `  ⇒ 消除 ${fmt(oldFallback)}`
  );
  console.log(
    `  软渲臂：${fmt(oldSoftware)} → ${fmt(newSoftware)}  ⇒ 消除 ${fmt(oldSoftware - newSoftware)}` +
      `（保留 16 MiB 级解码位图，诚实残余）`
  );
}

main().catch((e) => {
  console.error('measure-atlas-memory 失败:', e.message);
  process.exit(1);
});
