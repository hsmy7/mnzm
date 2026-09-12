/**
 * 一次性诊断脚本（任务完成后删除）：对每个 mip 级尺寸实测 astcenc 输出的数据字节数，
 * 反推精确的「块数 / dataSize」公式，供 lib/ktx1.mjs 与 KtxLoader.cpp 同步。
 *
 * 背景：ASTC 4×4 的块数在「尺寸为 4 的倍数」时无歧义，但本管线存在
 * 147 / 36 / 17 / 5 / 73 这类非 4 倍数级（非 2 的幂底图 >> level 的必然结果），
 * 补齐规则必须实测确定，不能推导猜测。
 */
import fs from 'fs';
import path from 'path';
import os from 'os';
import sharp from 'sharp';
import { findAstcenc, compressAstc, ASTC_BLOCK, ASTC_BLOCK_BYTES } from './lib/ktx1.mjs';

const ASTCENC_DIR = path.resolve('scripts/tools/astcenc/bin');
const astcenc = findAstcenc(ASTCENC_DIR);
if (!astcenc) throw new Error('astcenc 未找到');

// 本轮 KTX 涉及的全部级尺寸（去重）
const DIMS = [
  [2560, 1696], [1280, 848], [640, 424], [320, 212], [160, 106], [80, 53], [40, 26], [20, 13], [10, 6], [5, 4],
  [2304, 1888], [1152, 944], [576, 472], [288, 236], [144, 118], [72, 59], [36, 29], [18, 14], [9, 7], [4, 4],
  [1832, 2400], [916, 1200], [458, 600], [229, 300], [114, 150], [57, 75], [28, 37],
  [1828, 2396], [914, 1198], [457, 599], [228, 299],
  [1176, 3552], [588, 1776], [294, 888], [147, 444], [73, 222], [36, 111],
  [1120, 3368], [560, 1684], [280, 842], [140, 421], [70, 210], [35, 105], [17, 52],
  [1180, 3552], [590, 1776], [295, 888],
];

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'astc-probe-'));
const rows = [];
try {
  for (const [w, h] of DIMS) {
    const png = path.join(tmp, `p_${w}x${h}.png`);
    const astc = path.join(tmp, `p_${w}x${h}.astc`);
    await sharp({ create: { width: w, height: h, channels: 4, background: { r: 10, g: 200, b: 20, alpha: 255 } } })
      .png().toFile(png);
    compressAstc(astcenc, png, astc);
    const dataBytes = fs.statSync(astc).size;   // 含 16 字节 astcenc 头
    const payload = dataBytes - 16;

    const blocksCeil = Math.ceil(w / 4) * Math.ceil(h / 4);
    const blocksFloor = Math.floor(w / 4) * Math.floor(h / 4);
    // 假设：尺寸先补齐到 4 的倍数再算块数
    const blocksPad = (Math.ceil(w / 4) * 4 / 4) * (Math.ceil(h / 4) * 4 / 4);
    const actualBlocks = payload / ASTC_BLOCK_BYTES;

    const which = actualBlocks === blocksCeil ? 'ceil' : actualBlocks === blocksFloor ? 'floor' : actualBlocks === blocksPad ? 'pad4' : '???';
    rows.push({ w, h, payload, actualBlocks, blocksCeil, blocksFloor, blocksPad, which });
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('尺寸'.padEnd(13) + 'payload'.padEnd(10) + '实际块'.padEnd(9) + 'ceil'.padEnd(9) + 'floor'.padEnd(9) + 'pad4'.padEnd(9) + '判定');
for (const r of rows) {
  console.log(
    `${r.w}x${r.h}`.padEnd(13) + String(r.payload).padEnd(10) + String(r.actualBlocks).padEnd(9) +
    String(r.blocksCeil).padEnd(9) + String(r.blocksFloor).padEnd(9) + String(r.blocksPad).padEnd(9) + r.which
  );
}
const bad = rows.filter((r) => r.which === '???');
console.log(`\n无法用任一公式解释的级数: ${bad.length}`);
for (const r of bad) console.log(`  ${r.w}x${r.h}: payload=${r.payload} blocks=${r.actualBlocks}`);
