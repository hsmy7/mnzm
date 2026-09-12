/**
 * 一次性测量脚本（任务完成后删除）：确认两个下转角素材的「岩体水平分布」，
 * 用于确定下环与转角的锚定约定（bottomStartInset / bottomEndInset 取值方向）。
 */
import sharp from 'sharp';
import path from 'node:path';

const DIR = 'D:\\模拟宗门美术素材\\宗门地图边缘';
for (const [f, label] of [['边缘6.png', '左下角'], ['边缘7.png', '右下角']]) {
  const { data, info } = await sharp(path.join(DIR, f)).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width: W, height: H, channels: C } = info;
  // 逐列不透明覆盖率
  const cover = new Float64Array(W);
  for (let x = 0; x < W; x++) {
    let op = 0;
    for (let y = 0; y < H; y++) if (data[(y * W + x) * C + 3] > 8) op++;
    cover[x] = op / H;
  }
  console.log(`### ${f} (${label}) ${W}x${H}`);
  console.log('  列覆盖率剖面（每 1/12 宽采样）:');
  const parts = [];
  for (let k = 0; k < 12; k++) {
    const x = Math.floor((k + 0.5) * W / 12);
    parts.push(`${Math.round(x * 100 / W)}%:${(cover[x] * 100).toFixed(0)}%`);
  }
  console.log('    ' + parts.join('  '));
  // 两端 1/4 区域的平均覆盖率（判断哪端是岩体主体、哪端是崖柱尖细收尾）
  let outerSum = 0, innerSum = 0;
  const q = Math.floor(W / 4);
  for (let x = 0; x < q; x++) outerSum += cover[x];              // 左端（外侧，向左伸入地图外）
  for (let x = W - q; x < W; x++) innerSum += cover[x];          // 右端（内侧，贴地图左边）
  console.log(`  左1/4平均覆盖=${(outerSum / q * 100).toFixed(0)}%   右1/4平均覆盖=${(innerSum / q * 100).toFixed(0)}%`);
  console.log('');
}
