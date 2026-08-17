import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const SOURCE_DIR = 'D:/模拟宗门美术素材';
const DRAWABLE_DIRS = [
  path.resolve(__dirname, '..', 'app/src/main/res/drawable-nodpi'),
  path.resolve(__dirname, '..', 'feature/game/src/main/res/drawable-nodpi'),
];

/**
 * 宗门地图地面草皮（单一草皮）与入口固定结构（阶梯/门楼）无损转 webp。
 * 源文件名 → { drawable名, 目标像素宽, 目标像素高 }。
 * 草皮为 64×64（图集瓦片 2× 分辨率）并做**无缝平铺**处理；
 * 阶梯/门楼按世界格换算：阶梯 2 宽×6 高 = 128×384、门楼 6 宽×4 高 = 384×256。
 */
const GRASS_TILES = {
  '草皮.png': { drawable: 'map_grass_1', w: 64, h: 64, seamless: true },
  '宗门门楼.png': { drawable: 'sect_gate', w: 384, h: 256 },
};

/** 被替换/废弃的草皮（删除，避免 stale 资源残留） */
const REMOVED = [
  'map_tile.webp', 'map_tile_v2.webp',
  'map_grass_2.webp', 'map_grass_light_1.webp',
  'map_grass_flower_1.webp', 'map_grass_flower_2.webp', 'map_grass_light_2.webp',
  'sect_stairs.webp',
];

/**
 * 无缝平铺处理（偏移平均法，数学保证无缝 + 高分辨率降采样平滑环绕）。
 *
 * P(x,y) = avg( I(x,y), I(x+W/2,y), I(x,y+H/2), I(x+W/2,y+H/2) )，坐标按周期环绕。
 * 因平移 W/2 只是置换四项，P 严格周期（平铺完全连续，零接缝、无人工模糊带）。
 * 在 4× 工作分辨率上执行后降采样到目标尺寸——低通滤波进一步平滑周期环绕过渡，
 * 消除"暗接缝"（纹理环绕点相邻色差）。
 */
async function makeSeamless(buffer, size) {
  const work = size * 4;
  const { data, info } = await sharp(buffer)
    .resize(work, work, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .raw()
    .toBuffer({ resolveWithObject: true });
  const w = info.width, h = info.height;
  const ch = info.channels;
  const halfW = w >> 1, halfH = h >> 1;
  const out = Buffer.alloc(data.length);
  for (let y = 0; y < h; y++) {
    const y0 = y, y1 = (y + halfH) % h;
    for (let x = 0; x < w; x++) {
      const x0 = x, x1 = (x + halfW) % w;
      const b00 = (y0 * w + x0) * ch;
      const b10 = (y1 * w + x0) * ch;
      const b01 = (y0 * w + x1) * ch;
      const b11 = (y1 * w + x1) * ch;
      const bo = (y * w + x) * ch;
      for (let c = 0; c < ch; c++) {
        out[bo + c] = (data[b00 + c] + data[b10 + c] + data[b01 + c] + data[b11 + c]) >> 2;
      }
    }
  }
  const seamless = await sharp(out, { raw: { width: w, height: h, channels: ch } }).png().toBuffer();
  return sharp(seamless)
    .resize(size, size, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
    .png()
    .toBuffer();
}

async function main() {
  let converted = 0;
  for (const [name, cfg] of Object.entries(GRASS_TILES)) {
    const srcFile = path.join(SOURCE_DIR, name);
    if (!fs.existsSync(srcFile)) {
      console.error(`FAIL: source not found: ${srcFile}`);
      process.exitCode = 1;
      continue;
    }
    const meta = await sharp(srcFile).metadata();
    const resized = await sharp(srcFile)
      .resize(cfg.w, cfg.h, { fit: 'fill', kernel: sharp.kernel.lanczos3 })
      .toBuffer();
    const final = cfg.seamless ? await makeSeamless(resized, cfg.w) : resized;
    const buffer = await sharp(final)
      .webp({ lossless: true, effort: 6 })
      .toBuffer();
    for (const dir of DRAWABLE_DIRS) {
      fs.mkdirSync(dir, { recursive: true });
      const dstFile = path.join(dir, cfg.drawable + '.webp');
      fs.writeFileSync(dstFile, buffer);
    }
    const dstSize = (buffer.length / 1024).toFixed(0);
    console.log(`${name.padEnd(14)} ${cfg.w}x${cfg.h}  src ${meta.width}x${meta.height} ` +
      `→ ${cfg.drawable}.webp (lossless ${dstSize}KB${cfg.seamless ? ' seamless' : ''})`);
    converted++;
  }

  for (const rm of REMOVED) {
    for (const dir of DRAWABLE_DIRS) {
      const f = path.join(dir, rm);
      if (fs.existsSync(f)) {
        fs.unlinkSync(f);
        console.log(`REMOVE: ${rm}`);
      }
    }
  }

  console.log(`\nDone. ${converted}/${Object.keys(GRASS_TILES).length} converted, lossless webp.`);
}

main().catch((err) => { console.error(err); process.exit(1); });
