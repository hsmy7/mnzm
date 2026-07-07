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

/** 地砖配置：源文件名 → { drawable名, 目标像素宽, 目标像素高 } */
const FLOOR_TILES = {
  '地砖2x2': { drawable: 'floor_tile_2x2', w: 128, h: 128 },
  '地砖2x3': { drawable: 'floor_tile_2x3', w: 128, h: 192 },
  '地砖3x2': { drawable: 'floor_tile_3x2', w: 192, h: 128 },
  '地砖3x3': { drawable: 'floor_tile_3x3', w: 192, h: 192 },
};

async function main() {
  for (const [name, cfg] of Object.entries(FLOOR_TILES)) {
    const srcFile = path.join(SOURCE_DIR, name + '.png');

    if (!fs.existsSync(srcFile)) {
      console.log(`SKIP: source not found: ${name}`);
      continue;
    }

    const meta = await sharp(srcFile).metadata();
    const buffer = await sharp(srcFile)
      .resize(cfg.w, cfg.h, { fit: 'fill' })
      .webp({ lossless: true, effort: 6 })
      .toBuffer();

    // 写入两个模块的 drawable-nodpi
    for (const dir of DRAWABLE_DIRS) {
      fs.mkdirSync(dir, { recursive: true });
      const dstFile = path.join(dir, cfg.drawable + '.webp');
      fs.writeFileSync(dstFile, buffer);
      const dstMeta = await sharp(buffer).metadata();
      const srcSize = (fs.statSync(srcFile).size / 1024).toFixed(0);
      const dstSize = (buffer.length / 1024).toFixed(0);
      console.log(
        `${name.padEnd(8)} ${cfg.w}x${cfg.h}  src ${meta.width}x${meta.height} ` +
        `${srcSize}KB → ${dstMeta.width}x${dstMeta.height} ${dstSize}KB  → ${path.relative(__dirname, dstFile)}`
      );
    }
  }

  console.log('\nDone. All floor tiles converted.');
}

main().catch(err => { console.error(err); process.exit(1); });
