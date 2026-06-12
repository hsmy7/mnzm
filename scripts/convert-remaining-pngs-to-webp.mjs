import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

// All directories containing PNGs to convert
const PNG_DIRS = [
    'android/app/src/main/res/drawable',
    'android/app/src/main/res/drawable-nodpi',
    'android/feature/game/src/main/res/drawable',
    'android/feature/game/src/main/res/drawable-nodpi',
];

// WebP files that are actually PNG data (need re-encode)
const MISLABELED_WEBP = [
    'android/app/src/main/res/drawable-nodpi/bg_dialog_mail.webp',
    'android/app/src/main/res/drawable-nodpi/ui_mail_button.webp',
    'android/feature/game/src/main/res/drawable-nodpi/bg_dialog_mail.webp',
    'android/feature/game/src/main/res/drawable-nodpi/ui_mail_button.webp',
];

const WEBP_OPTIONS = { lossless: true, effort: 6 };

async function convertPngToWebp(pngPath) {
    const dir = path.dirname(pngPath);
    const name = path.basename(pngPath, '.png');
    const webpPath = path.join(dir, name + '.webp');

    const pngSize = (fs.statSync(pngPath).size / 1024).toFixed(0);

    await sharp(pngPath)
        .webp(WEBP_OPTIONS)
        .toFile(webpPath);

    const webpSize = (fs.statSync(webpPath).size / 1024).toFixed(0);
    const ratio = ((1 - fs.statSync(webpPath).size / fs.statSync(pngPath).size) * 100).toFixed(1);

    // Delete original PNG
    fs.unlinkSync(pngPath);

    console.log(`  ${name}.png → .webp  ${pngSize}KB → ${webpSize}KB  (-${ratio}%)`);
}

async function fixMislabeledWebp(webpPath) {
    const name = path.basename(webpPath);
    const oldSize = (fs.statSync(webpPath).size / 1024).toFixed(0);

    // Sharp auto-detects the real format (PNG) and re-encodes as proper WebP
    await sharp(webpPath)
        .webp(WEBP_OPTIONS)
        .toFile(webpPath + '.tmp');

    // Replace original with proper WebP
    fs.renameSync(webpPath + '.tmp', webpPath);

    const newSize = (fs.statSync(webpPath).size / 1024).toFixed(0);
    const ratio = ((1 - fs.statSync(webpPath).size / (oldSize * 1024)) * 100).toFixed(1);
    const delta = oldSize - newSize > 0 ? `-${(oldSize - newSize).toFixed(0)}KB` : `+${(newSize - oldSize).toFixed(0)}KB`;

    console.log(`  ${name}  re-encoded as lossless WebP  ${oldSize}KB → ${newSize}KB  (${delta})`);
}

async function main() {
    console.log('=== 无损 WebP 转换 ===\n');

    // Step 1: Convert PNGs
    let pngCount = 0;
    for (const dir of PNG_DIRS) {
        const fullDir = path.join(ROOT, dir);
        if (!fs.existsSync(fullDir)) continue;

        const pngs = fs.readdirSync(fullDir).filter(f => f.endsWith('.png'));
        if (pngs.length === 0) continue;

        console.log(`${dir}/`);
        for (const png of pngs) {
            try {
                await convertPngToWebp(path.join(fullDir, png));
                pngCount++;
            } catch (err) {
                console.error(`  FAILED: ${png} — ${err.message}`);
            }
        }
        console.log('');
    }

    // Step 2: Fix mislabeled WebP files
    console.log('修复格式错标的 WebP 文件:');
    let fixedCount = 0;
    for (const wp of MISLABELED_WEBP) {
        const fullPath = path.join(ROOT, wp);
        if (!fs.existsSync(fullPath)) {
            console.log(`  SKIP: not found — ${wp}`);
            continue;
        }
        try {
            await fixMislabeledWebp(fullPath);
            fixedCount++;
        } catch (err) {
            console.error(`  FAILED: ${wp} — ${err.message}`);
        }
    }

    console.log('');
    console.log(`=== Done ===`);
    console.log(`PNG → WebP: ${pngCount} files`);
    console.log(`Mislabeled fixed: ${fixedCount} files`);
}

main().catch(err => { console.error(err); process.exit(1); });
