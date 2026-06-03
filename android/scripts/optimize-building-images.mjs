import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const SOURCE_DIR = 'D:/模拟宗门美术素材';
const DRAWABLE_DIR = path.resolve(__dirname, '..', 'app/src/main/res/drawable-nodpi');
const BACKUP_DIR = path.resolve(__dirname, '..', 'app/src/main/drawable-nodpi-backup');
const QUALITY_PRESETS = {
    thumbnail: { shortSide: 200, webpQuality: 75 },
    main:      { shortSide: 400, webpQuality: 85 },
    hd:        { shortSide: 800, webpQuality: 90 },
};

const SHORT_SIDE = QUALITY_PRESETS.main.shortSide;

const BUILDINGS = {
  '炼丹炉': { drawable: 'building_alchemy',          gridW: 2, gridH: 2 },
  '锻造坊': { drawable: 'building_forge',            gridW: 2, gridH: 2 },
  '灵矿场': { drawable: 'building_spirit_mine',      gridW: 2, gridH: 2 },
  '灵植阁': { drawable: 'building_herb_garden',      gridW: 2, gridH: 2 },
  '任务阁': { drawable: 'building_mission_hall',     gridW: 2, gridH: 2 },
  '监牢':   { drawable: 'building_reflection_cliff', gridW: 2, gridH: 2 },
  '天枢殿': { drawable: 'building_tianshu_hall',     gridW: 3, gridH: 2 },
  '藏经阁': { drawable: 'building_library',          gridW: 3, gridH: 2 },
  '执法堂': { drawable: 'building_law_enforcement',  gridW: 3, gridH: 2 },
  '问道塔': { drawable: 'building_wen_dao_peak',     gridW: 2, gridH: 3 },
  '青云塔': { drawable: 'building_qingyun_peak',     gridW: 2, gridH: 3 },
};

async function main() {
  fs.mkdirSync(BACKUP_DIR, { recursive: true });

  for (const [name, cfg] of Object.entries(BUILDINGS)) {
    const srcFile = path.join(SOURCE_DIR, name + '.png');
    const dstFile = path.join(DRAWABLE_DIR, cfg.drawable + '.webp');

    if (!fs.existsSync(srcFile)) {
      console.log(`SKIP: source not found: ${name}`);
      continue;
    }

    if (fs.existsSync(dstFile)) {
      fs.copyFileSync(dstFile, path.join(BACKUP_DIR, cfg.drawable + '.webp'));
    }

    const meta = await sharp(srcFile).metadata();
    const srcRatio = meta.width / meta.height;
    const targetRatio = cfg.gridW / cfg.gridH;

    // Step 1: resize source so shorter side = SHORT_SIDE
    let step1W, step1H;
    if (meta.width <= meta.height) {
      step1W = SHORT_SIDE;
      step1H = Math.round(SHORT_SIDE / srcRatio);
    } else {
      step1H = SHORT_SIDE;
      step1W = Math.round(SHORT_SIDE * srcRatio);
    }

    // Step 2: calculate extend amounts to match target ratio
    const step1Ratio = step1W / step1H;
    let extendTop = 0, extendBottom = 0, extendLeft = 0, extendRight = 0;

    if (Math.abs(step1Ratio - targetRatio) > 0.01) {
      if (step1Ratio > targetRatio) {
        const targetH = Math.round(step1W / targetRatio);
        const diff = targetH - step1H;
        extendTop = Math.floor(diff / 2);
        extendBottom = diff - extendTop;
      } else {
        const targetW = Math.round(step1H * targetRatio);
        const diff = targetW - step1W;
        extendLeft = Math.floor(diff / 2);
        extendRight = diff - extendLeft;
      }
    }

    const result = await sharp(srcFile)
      .resize(step1W, step1H, { fit: 'fill' })
      .extend({
        top: extendTop,
        bottom: extendBottom,
        left: extendLeft,
        right: extendRight,
        background: { r: 0, g: 0, b: 0, alpha: 0 }
      })
      .webp({ quality: QUALITY_PRESETS.main.webpQuality, effort: 4 })
      .toBuffer();

    fs.writeFileSync(dstFile, result);

    const dstM = await sharp(result).metadata();
    const finalRatio = dstM.width / dstM.height;
    const srcSize = (fs.statSync(srcFile).size / 1024).toFixed(0);
    const dstSize = (fs.statSync(dstFile).size / 1024).toFixed(0);
    const padStr = `T${extendTop}B${extendBottom}L${extendLeft}R${extendRight}`;
    console.log(`${name.padEnd(6)} ${cfg.gridW}x${cfg.gridH}  src ${meta.width}x${meta.height} r${srcRatio.toFixed(3)}  ${padStr.padEnd(14)} → ${dstM.width}x${dstM.height} r${finalRatio.toFixed(3)}  ${srcSize}KB→${dstSize}KB`);
  }

  console.log(`\nDone. ${Object.keys(BUILDINGS).length} images. Backups: ${BACKUP_DIR}`);
}

main().catch(err => { console.error(err); process.exit(1); });
