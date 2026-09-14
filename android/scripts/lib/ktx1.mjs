/**
 * KTX1 压缩纹理公共库（astcenc 调用 + ASTC 4×4 mip 链 + KTX1 容器封装）。
 *
 * 从 build-atlas.mjs 抽出复用——图集（build-atlas.mjs 的 per-sprite pad 环 mip）
 * 与独立纹理（build-edge-ktx.mjs 的级联 mip）共享同一 ASTC/KTX1 实现，
 * 避免两处双写漂移（容器字段与 astcenc 参数任一处改动必须同时生效）。
 *
 * 容器契约与消费端 `app/src/main/cpp/KtxLoader.cpp` 对齐：
 *   magic "«KTX 11»" / endianness 0x04030201 / glType 0 / glFormat 0 /
 *   glInternalFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR / numberOfFaces 1 /
 *   numberOfMipmapLevels = mips.length / 数据区 = 逐级 [imageSize u32][ASTC 数据]
 *   .astc 文件头布局因 astcenc 版本而异（16/20 字节）→ 按几何推导尺寸从文件尾部截取。
 */
import fs from 'fs';
import path from 'path';
import { execFileSync } from 'child_process';
import sharp from 'sharp';

/** ASTC 4×4 块尺寸（像素） */
export const ASTC_BLOCK = 4;
/** ASTC 4×4 每块字节数（16 字节 = 128 bit / 块） */
export const ASTC_BLOCK_BYTES = 16;
/** KTX1 容器头长度 */
export const KTX1_HEADER_SIZE = 64;
/** KTX1 每级数据前的 imageSize 字段长度 */
export const KTX1_LEVEL_SIZE_FIELD = 4;
/** glInternalFormat: GL_COMPRESSED_RGBA_ASTC_4x4_KHR */
export const GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
/** glBaseInternalFormat: GL_RGBA */
export const GL_RGBA = 0x1908;

/**
 * 探测可用 astcenc 可执行文件（avx2 → sse4.1 → sse2）。
 *
 * @param {string} astcencDir astcenc bin 目录
 * @returns {string|null} 可执行文件绝对路径；null = 目录不存在或无可用文件
 */
export function findAstcenc(astcencDir) {
  if (!fs.existsSync(astcencDir)) return null;
  for (const name of ['astcenc-avx2.exe', 'astcenc-sse4.1.exe', 'astcenc-sse2.exe']) {
    const p = path.join(astcencDir, name);
    if (fs.existsSync(p)) return p;
  }
  return null;
}

/**
 * 运行 astcenc（-cl <in> <out> 4x4 -medium，LDR——5.x 位置式参数）。
 *
 * @param {string} astcenc astcenc 可执行路径
 * @param {string} inPng 输入 PNG 路径
 * @param {string} outAstc 输出 .astc 路径
 */
export function compressAstc(astcenc, inPng, outAstc) {
  execFileSync(astcenc, ['-cl', inPng, outAstc, '4x4', '-medium'], { stdio: 'inherit' });
}

/**
 * 生成单纹理的 mip 链（级数与每级尺寸由消费端 KtxLoader 的推导式决定）。
 *
 * ★ 尺寸契约（**必须**与 `app/src/main/cpp/KtxLoader.cpp` loadKtx1 逐级校验一致）：
 *     lw = max(ASTC_BLOCK, width  >> level)
 *     lh = max(ASTC_BLOCK, height >> level)
 *   任一级尺寸不符 → KtxLoader 的 dataSize 几何校验失败 → **整张纹理被拒**。
 *   故不能自行按「取整到 4 的倍数」递推（非 2 的幂尺寸会漂移，
 *   例：1176 的 mip2 loader=294 而取整递推=296）。
 *
 *   级数 = 最后一个既非 4 的倍数下限、又仍有缩减空间的那一级 + 1：
 *   持续到 lw==ASTC_BLOCK 或 lh==ASTC_BLOCK（与 build-atlas.mjs 停止条件同语义）。
 *
 * mip0 为源尺寸，其余级由**源图**单次降采样（非级联）：
 *   - 级联在非 2 的幂尺寸上会因逐级取整累积偏差（与上述契约冲突）；
 *   - 单次降采样与级联各自采样点重合（resize(W>>k) 与 resize(W>>(k-1))>>1
 *     在浮点一致时结果逐位相同），视觉无差异。
 *
 * @param {object} opts
 * @param {string} opts.astcenc astcenc 可执行路径
 * @param {Buffer} opts.sourceBuffer 源图缓冲（WebP/PNG，sharp 解码）
 * @param {number} opts.width mip0 宽（须为 ASTC_BLOCK 的倍数）
 * @param {number} opts.height mip0 高（须为 ASTC_BLOCK 的倍数）
 * @param {string} opts.tmpDir 临时文件目录（须已存在）
 * @param {string} opts.tag 临时文件名标签
 * @param {(msg: string) => void} [opts.log] 日志回调
 * @returns {Promise<Array<{w:number,h:number,data:Buffer}>>} 每级 mip 的 .astc 原始文件内容
 */
export async function buildMipChain({ astcenc, sourceBuffer, width, height, tmpDir, tag, log }) {
  const say = log ?? (() => {});
  if (width % ASTC_BLOCK !== 0 || height % ASTC_BLOCK !== 0) {
    throw new Error(`尺寸须为 ${ASTC_BLOCK} 的倍数：${width}x${height}（${tag}）`);
  }

  // 级尺寸表（与 KtxLoader 推导式同源）
  const levels = [];
  for (let i = 0; ; i++) {
    const lw = Math.max(ASTC_BLOCK, width >> i);
    const lh = Math.max(ASTC_BLOCK, height >> i);
    levels.push({ w: lw, h: lh });
    if (lw <= ASTC_BLOCK || lh <= ASTC_BLOCK) break;
  }

  const mips = [];
  for (let i = 0; i < levels.length; i++) {
    const { w: lw, h: lh } = levels[i];
    const mipPng = path.join(tmpDir, `edge_${tag}_mip${i}.png`);
    const mipAstc = path.join(tmpDir, `edge_${tag}_mip${i}.astc`);
    // mip0 = 源原样（无损，避免无意义重采样）；其余级从源单次降采样
    const pngBuf = i === 0
      ? await sharp(sourceBuffer).png().toBuffer()
      : await sharp(sourceBuffer)
          .resize(lw, lh, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
          .png()
          .toBuffer();
    fs.writeFileSync(mipPng, pngBuf);
    compressAstc(astcenc, mipPng, mipAstc);
    mips.push({ w: lw, h: lh, data: fs.readFileSync(mipAstc) });
    fs.unlinkSync(mipAstc);
    fs.unlinkSync(mipPng);
    say(`    mip ${i}: ${lw}×${lh}`);
  }
  return mips;
}

/**
 * KTX1 多 mip 压缩纹理封装（ASTC 4×4）。
 *
 * 数据区 = 逐级 [imageSize u32][ASTC 数据]。.astc 文件头布局因 astcenc 版本有
 * 差异（16/20 字节），但数据区恒为文件尾部的几何计算尺寸
 * （w/4 × h/4 × 16）——按尾部截取，头大小校验在合理范围（≤32 字节）即可，
 * 避免解析头字段的版本兼容问题。
 *
 * @param {Array<{w:number,h:number,data:Buffer}>} mips 每级 mip 数据（mip0 最大在前）
 * @returns {Buffer} 完整 KTX1 容器字节
 */
export function wrapKtx1(mips) {
  if (!Array.isArray(mips) || mips.length === 0) throw new Error('wrapKtx1: mips 为空');

  const perLevel = mips.map((m, i) => {
    // ASTC 块数 = ceil(尺寸/4)——末行/末列不足一块时**补齐整块**（编码器行为）。
    // 消费端 KtxLoader 用 base>>level 推导每级尺寸后同式求块数；两处必须同为 ceil，
    // 用 floor 会在 147 这类非 4 倍数的 mip 级上少算一块 → dataSize 校验失败、
    // 整张纹理被拒（KtxLoader.cpp 会打出 "dataSize 不一致"）。
    const blocksX = Math.ceil(m.w / ASTC_BLOCK);
    const blocksY = Math.ceil(m.h / ASTC_BLOCK);
    const expected = blocksX * blocksY * ASTC_BLOCK_BYTES;
    if (m.data.length < expected + 16) throw new Error(`ASTC 文件过短 (mip ${i})`);
    const headerSize = m.data.length - expected;
    if (headerSize > 32) throw new Error(`ASTC 头尺寸异常: ${headerSize} (mip ${i})`);
    const data = m.data.subarray(headerSize);
    if (data.length !== expected) throw new Error(`ASTC 数据尺寸不符: ${data.length} != ${expected} (mip ${i})`);
    return { size: expected, data };
  });

  const header = Buffer.alloc(KTX1_HEADER_SIZE);
  header[0] = 0xAB; header[1] = 0x4B; header[2] = 0x54; header[3] = 0x58; // "«KTX"
  header[4] = 0x20; header[5] = 0x31; header[6] = 0x31; header[7] = 0xBB; // " 11»"
  header.writeUInt32LE(0x04030201, 8); // endianness（小端）
  header.writeUInt32LE(0, 12); // glType（压缩纹理 = 0）
  header.writeUInt32LE(1, 16); // glTypeSize
  header.writeUInt32LE(0, 20); // glFormat（压缩纹理 = 0）
  header.writeUInt32LE(GL_COMPRESSED_RGBA_ASTC_4x4_KHR, 24); // glInternalFormat
  header.writeUInt32LE(GL_RGBA, 28); // glBaseInternalFormat
  header.writeUInt32LE(mips[0].w, 32); // pixelWidth（mip0）
  header.writeUInt32LE(mips[0].h, 36); // pixelHeight（mip0）
  header.writeUInt32LE(0, 40); // pixelDepth
  header.writeUInt32LE(0, 44); // numberOfArrayElements
  header.writeUInt32LE(1, 48); // numberOfFaces
  header.writeUInt32LE(mips.length, 52); // numberOfMipmapLevels
  header.writeUInt32LE(0, 56); // bytesOfKeyValueData

  const bodies = perLevel.map((pl) => {
    const sizeField = Buffer.alloc(KTX1_LEVEL_SIZE_FIELD);
    sizeField.writeUInt32LE(pl.size, 0);
    return Buffer.concat([sizeField, pl.data]);
  });
  return Buffer.concat([header, ...bodies]);
}
