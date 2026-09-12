/**
 * 一次性验证脚本（任务完成后删除）：按 KtxLoader.cpp 的完整契约复现校验，
 * 独立于生成器检查每张 KTX 能否被消费端接受。
 */
import fs from 'fs';
import path from 'path';

const DIR = 'app/src/main/assets/atlas/edge';
const HEADER = 64, FIELD = 4, BLOCK = 4, BLOCK_BYTES = 16;
const MAX_DIM = 4096;
const ASTC_4x4 = 0x93B0;

let allOk = true;
for (const f of fs.readdirSync(DIR).filter((x) => x.endsWith('.ktx')).sort()) {
  const b = fs.readFileSync(path.join(DIR, f));
  const errs = [];
  const u32 = (o) => b.readUInt32LE(o);

  if (b.length < HEADER) errs.push('文件过短');
  if (u32(0) !== 0x58544BAB || u32(4) !== 0xBB313120) errs.push('magic');
  if (u32(8) !== 0x04030201) errs.push('endianness');
  if (u32(12) !== 0 || u32(20) !== 0) errs.push('glType/glFormat 非 0');
  if (u32(24) !== ASTC_4x4) errs.push('glInternalFormat 非 ASTC_4x4');
  const mipLevels = u32(52);
  if (u32(40) !== 0 || u32(44) !== 0 || u32(48) !== 1 || mipLevels < 1) errs.push('容器维度');
  if (u32(56) !== 0) errs.push('key-value 非空');
  const width = u32(32), height = u32(36);
  if (width % BLOCK !== 0 || height % BLOCK !== 0) errs.push(`mip0 尺寸非 4 倍数 ${width}x${height}`);
  if (width > MAX_DIM || height > MAX_DIM) errs.push('尺寸超上限');

  // 逐级 dataSize + 末尾精确校验
  let cursor = HEADER;
  const levelDims = [];
  for (let i = 0; i < mipLevels; i++) {
    let lw = width >>> i, lh = height >>> i;
    if (lw < BLOCK) lw = BLOCK;
    if (lh < BLOCK) lh = BLOCK;
    levelDims.push(`${lw}x${lh}`);
    const expected = Math.ceil(lw / BLOCK) * Math.ceil(lh / BLOCK) * BLOCK_BYTES;
    if (b.length < cursor + FIELD) { errs.push(`mip ${i} 缺 dataSize`); break; }
    const stored = u32(cursor);
    if (stored !== expected) { errs.push(`mip ${i} dataSize stored=${stored} expected=${expected} (${lw}x${lh})`); break; }
    cursor += FIELD + stored;
  }
  if (errs.length === 0 && cursor !== b.length) errs.push(`数据区不精确 cursor=${cursor} file=${b.length}`);

  const ok = errs.length === 0;
  allOk = allOk && ok;
  console.log(`${ok ? '✅' : '❌'} ${f.padEnd(26)} ${width}x${height} mips=${mipLevels} ${(b.length / 1048576).toFixed(2)}MB`);
  if (levelDims.length) console.log(`     级尺寸: ${levelDims.join(' ')}`);
  if (!ok) for (const e of errs) console.log(`     ⚠ ${e}`);
}
console.log(allOk ? '\n全部通过 KtxLoader 契约校验' : '\n存在不通过项');
process.exit(allOk ? 0 : 1);
