/**
 * 一次性测量脚本（任务完成后删除）：求下边崖壁与两个转角之间的锚定参数——
 * 转角贴角点后，其「草带」在水平方向的内缩量（世界像素）。
 *
 * 用途：C++ 合成器的 bottomStartInset / bottomEndInset 默认值。
 * 定义：转角内缘（贴合地图左/右边的那条边）到「该转角不再被天空/岩柱占据、
 * 下边崖壁应开始接续」的位置之距离 = 转角内 90% 不透明内容的水平内缩量。
 */
import sharp from 'sharp';
import path from 'node:path';

const DIR = 'D:\\模拟宗门美术素材\\宗门地图边缘';
const FILES = { '边缘6.png': '左下角', '边缘7.png': '右下角' };

for (const [f, label] of Object.entries(FILES)) {
  const { data, info } = await sharp(path.join(DIR, f)).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width: W, height: H, channels: C } = info;

  // 逐列不透明覆盖率
  const cover = new Float64Array(W);
  for (let x = 0; x < W; x++) {
    let op = 0;
    for (let y = 0; y < H; y++) if (data[(y * W + x) * C + 3] > 8) op++;
    cover[x] = op / H;
  }

  // 内容左右界（任一列有 >2% 不透明即算内容）
  let x0 = -1, x1 = -1;
  for (let x = 0; x < W; x++) if (cover[x] > 0.02) { if (x0 < 0) x0 = x; x1 = x; }
  // 90% 内容界（覆盖率达峰值 90% 的边界——消除崖柱尖端细线的干扰）
  let peak = 0;
  for (let x = 0; x < W; x++) if (cover[x] > peak) peak = cover[x];
  const thr = peak * 0.9;
  let x0n = -1, x1n = -1;
  for (let x = 0; x < W; x++) if (cover[x] >= thr) { if (x0n < 0) x0n = x; x1n = x; }

  const isLeft = label.startsWith('左下');
  // 内缘 = 左下角为「右缘贴地图左边」→ 内缘在右端；右下角镜像
  const innerInset = isLeft ? (W - 1 - x0) : x1;      // 内缘到内容界的距离
  const tailInset90 = isLeft ? (W - 1 - x0n) : x1n;

  console.log(`### ${f} (${label})  ${W}x${H}`);
  console.log(`   内容列界: [${x0}..${x1}]  宽=${x1 - x0 + 1}`);
  console.log(`   90%内容界: [${x0n}..${x1n}]  宽=${x1n - x0n + 1}`);
  console.log(`   内缘(${isLeft ? '右端' : '左端'})内缩:  全内容=${innerInset}px   90%内容=${tailInset90}px`);
  // 关键：内缘是否与地图边重合（转角内缘应正贴合地图边）
  console.log(`   → 贴合地图边后，横向占据地图内 ${isLeft ? (x1 + 1) : (W - x0)}px  （=W-内缩）`);
}
