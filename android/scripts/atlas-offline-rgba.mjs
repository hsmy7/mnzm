#!/usr/bin/env node
/**
 * 图集离线 RGBA 降采样产物生成器（B15 / R6.1）——**独立入口**。
 *
 * ## 为什么需要这个脚本
 *
 * 运行时历史路径用 `SectAtlasAssembler.buildAtlasBitmap()` 在**设备上**做
 * 「逐精灵解码 + Canvas 画布拼装」2048² 位图，服务三条支路：
 *   1. RGBA 上传回退臂（ASTC 缺失/设备不支持/上传被拒）；
 *   2. RGBA mip 链编码源；
 *   3. Canvas 软渲染路径像素源。
 * 该路径带来启动期 Canvas 依赖 + 数百毫秒拼装 + 16MB 位图与逐精灵解码中间
 * 缓冲的内存尖峰（低端机 OOM 高危），也是 **iOS 后端的启动前置阻塞项**。
 *
 * B15 把同一份像素在**构建期**产出为设备可直接映射的离线产物：
 *
 *   - `atlas-rgba-raw.bin`：2048² 直通 alpha RGBA8888 **裸像素**。运行时零解码、
 *     零逐精灵循环（`ByteBuffer` 直接上传 / 直接填 `Bitmap`）。
 *   - `atlas-rgba-mips.bin`：level-major 紧凑 mip 链（2048→2 共 11 级）。
 *   - `atlas-rgba-manifest.json`：产物元数据 + 逐精灵 golden 校验和。
 *
 * ## 与 build-atlas.mjs 的关系
 *
 * 权威产出路径是 **`build-atlas.mjs` 图集模式内联调用**
 * （`writeOfflineRgbaArtifacts`）——它复用 ASTC 图集已加载的槽位内容，
 * 使「两条图集路径的精灵内容同源」成为结构事实。
 *
 * 本脚本是**独立入口**：不跑 astcenc/ASTC 压缩（仅 sharp 缩放），用于
 *   - 只想重生成离线产物（权威源未变、产物损坏）；
 *   - 环境无 astcenc 时的产物重建；
 *   - CI 里作为独立校验步骤。
 * 两条入口共用 `lib/atlas-offline-rgba-lib.mjs`，产出**逐位一致**。
 *
 * ## 降采样配方（与 B15 前 Canvas 语义对位）
 *
 * 历史 `SectAtlasAssembler` 的产出**不是** 4096 图集缩到 2048 的结果，而是
 * 「每个精灵**独立**缩到 `slot × 0.5` 后按 `round(x × 0.5)` 槽位落位」。
 * 本管线同构复现，且落位重叠区丢弃 = `Canvas.drawBitmap` 的 `over` 覆盖语义
 * （2048 下精灵从不重叠，自动化断言 `assertNoOverlap`）。
 *
 * ## 纪律
 *
 * - 产物**入库**（`app/src/main/assets/atlas/`）——桌面测试与 NDK 构建无需先跑本脚本；
 * - 写入为**原子**（临时文件 + rename）——Gradle up-to-date 检查不会看到半截文件；
 * - 缺源图即抛错（fail-fast），不允许静默产出缺精灵产物。
 *
 * 用法：`node scripts/atlas-offline-rgba.mjs`（工作目录无关，路径由 __dirname 推导）
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  OFFLINE_SCALE,
  buildOfflineSpriteList,
  loadPremultipliedRgba,
  writeOfflineRgbaArtifacts,
} from './lib/atlas-offline-rgba-lib.mjs';
import sharp from 'sharp';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');
const OUT_DIR = path.resolve(ANDROID_DIR, 'app/src/main/assets/atlas');

async function main() {
  console.log('atlas-offline-rgba: 解析槽位清单...');
  const sprites = await buildOfflineSpriteList();
  console.log(`  可绘制精灵 ${sprites.length}`);

  // 槽位内容（预乘 PNG）——与 build-atlas.mjs loadSpriteContents 同口径
  const contents = new Map();
  const transparent = new Set();
  console.log(`逐精灵加载并缩放内容（预乘空间，lanczos3）...`);
  for (const s of sprites) {
    const { pm, width, height, transparent: hasAlpha } = await loadPremultipliedRgba(s.srcPath);
    if (hasAlpha) transparent.add(s.name);
    contents.set(
      s.name,
      await sharp(pm, { raw: { width, height, channels: 4 } })
        .resize(s.w, s.h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
        .png()
        .toBuffer()
    );
  }
  console.log(`  半透明精灵 ${transparent.size}`);

  fs.mkdirSync(OUT_DIR, { recursive: true });
  console.log('产出离线 RGBA 降采样产物（每精灵独立降采样 + 落位）...');
  const manifest = await writeOfflineRgbaArtifacts(contents, transparent, sprites, OUT_DIR);

  console.log(
    `完成: ${manifest.rawFile} ${(manifest.rawBytes / 1024 / 1024).toFixed(2)}MB, ` +
      `${manifest.mipFile} ${(manifest.mipBytes / 1024 / 1024).toFixed(2)}MB, ` +
      `mip ${manifest.mipLevels} 级 (2048→${manifest.mipWidths[manifest.mipWidths.length - 1]}), ` +
      `sprites=${manifest.spriteCount}`
  );
  console.log(`      scale=${OFFLINE_SCALE} frameSha256=${manifest.frameSha256}`);
}

main().catch((e) => {
  console.error('atlas-offline-rgba 失败:', e.message);
  process.exit(1);
});
