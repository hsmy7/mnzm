#!/usr/bin/env node
/**
 * build-rock-texture.mjs — ⛔ 已退役（2026-09-23，底部 Mesh 重构）。
 *
 * map_rock_base 改由美术管线供给：`scripts/source-mapping.json` 登记
 * `宗门地图/底部.png`（bake = square 1024 + seamless）→
 * `node scripts/import-art-assets.mjs` 烘焙 1024² 可平铺 WebP。
 * 本脚本保留作程序化备胎参考——**勿再直接运行覆盖**美术产物。
 *
 * ——以下为历史说明——
 *
 * 弯曲地皮轮廓系统的「纯岩石」无缝材质生成器。
 *
 * 地图边缘 v2：岩石材质只负责表现、不携带岛屿轮廓（底部形状由
 * ground_boundary.h 程序生成）。本脚本确定性生成 128×128 可平铺 RGBA PNG
 * （双轴无缝：所有噪声以格数为周期缠绕），输出
 * `android/feature/game/src/main/res/drawable-nodpi/map_rock_base.png`。
 *
 * 纹理构成（确定性，无随机源依赖）：
 *   3 个倍频的周期值噪声（周期 4/8/16 格）+ hash 高频颗粒 + 少量暗色裂纹带
 *   （正弦条带域扭曲），暖灰-褐岩 colore，gamma 空间直接输出（与图集素材
 *   同为 sRGB 常规位图）。
 *
 * 用法：node scripts/build-rock-texture.mjs
 * 产物提交入库（构建不依赖 node 运行时重生成——与 build-atlas.mjs 同口径）。
 */

import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const SIZE = 128;
const OUT = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'android/feature/game/src/main/res/drawable-nodpi/map_rock_base.png',
);

// ── 确定性哈希（splitmix32 风格；素材本地使用，不与引擎 RNG 分区共享）──
function hash2(x, y, seed) {
  let h = (x * 374761393 + y * 668265263 + seed * 974634211) >>> 0;
  h = (h ^ (h >>> 13)) >>> 0;
  h = Math.imul(h, 1274126177) >>> 0;
  h = (h ^ (h >>> 16)) >>> 0;
  return h / 4294967296;
}

function smooth(t) {
  return t * t * (3 - 2 * t);
}

/** 周期值噪声：格点在 [0,cells) 上缠绕 → 双轴无缝 */
function periodicValueNoise(px, py, cells, seed) {
  const gx = (px / SIZE) * cells;
  const gy = (py / SIZE) * cells;
  const x0 = Math.floor(gx);
  const y0 = Math.floor(gy);
  const fx = smooth(gx - x0);
  const fy = smooth(gy - y0);
  const wrap = (v, c) => ((v % c) + c) % c;
  const v00 = hash2(wrap(x0, cells), wrap(y0, cells), seed);
  const v10 = hash2(wrap(x0 + 1, cells), wrap(y0, cells), seed);
  const v01 = hash2(wrap(x0, cells), wrap(y0 + 1, cells), seed);
  const v11 = hash2(wrap(x0 + 1, cells), wrap(y0 + 1, cells), seed);
  const a = v00 + (v10 - v00) * fx;
  const b = v01 + (v11 - v01) * fx;
  return a + (b - a) * fy;
}

// ── 岩石像素场 ──────────────────────────────────────────────────
const pixels = Buffer.alloc(SIZE * SIZE * 4);
for (let y = 0; y < SIZE; y++) {
  for (let x = 0; x < SIZE; x++) {
    // 三个倍频：大块明暗 / 中尺度岩块 / 细颗粒
    const n1 = periodicValueNoise(x, y, 4, 11) * 0.55;
    const n2 = periodicValueNoise(x, y, 8, 23) * 0.30;
    const n3 = periodicValueNoise(x, y, 16, 47) * 0.15;
    let v = n1 + n2 + n3;

    // 裂纹带：正弦条带（域扭曲后取窄带），双轴无缝（频率取整数周期）
    const warp = periodicValueNoise(x, y, 8, 71);
    const band = Math.sin(((x / SIZE) * 6 + (y / SIZE) * 4 + warp * 2.0) * Math.PI * 2);
    const crack = Math.max(0, 1 - Math.abs(band) * 6.0);
    v -= crack * 0.16;

    // 高频颗粒（砂质感）
    v += (hash2(x, y, 97) - 0.5) * 0.06;

    // 暖灰-褐色 palette：v∈[0,1] → 深褐灰 → 亮暖灰
    const t = Math.min(1, Math.max(0, v));
    const r = 92 + t * 88; // 92..180
    const g = 84 + t * 78; // 84..162
    const b = 76 + t * 66; // 76..142
    const i = (y * SIZE + x) * 4;
    pixels[i] = Math.round(r);
    pixels[i + 1] = Math.round(g);
    pixels[i + 2] = Math.round(b);
    pixels[i + 3] = 255;
  }
}

// ── PNG 编码（RGBA8，filter 0）─────────────────────────────────
function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type RGBA
const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0; // filter none
  pixels.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, png);
console.log(`map_rock_base.png written: ${OUT} (${png.length} bytes, ${SIZE}x${SIZE} seamless RGBA)`);
