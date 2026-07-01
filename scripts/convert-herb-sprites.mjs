/**
 * Tier3 草药精灵图批量转换脚本
 *
 * 从 D:\模拟宗门美术素材 读取 tier3 草药的 PNG 素材，
 * 转换为无损 WebP 并同步输出到 app 和 feature/game 两个模块的 drawable-nodpi。
 *
 * 映射规则：
 *   源文件名含"草药" → herb_{herbId}.webp
 *   源文件名含"种"/"核" → seed_{herbId}.webp
 *   源文件名含"成长期" → growing_{herbId}.webp
 */

import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

// 素材源目录
const SRC_DIR = 'D:/模拟宗门美术素材';

// 目标目录（两个模块各放一份）
const TARGET_DIRS = [
    'android/app/src/main/res/drawable-nodpi',
    'android/feature/game/src/main/res/drawable-nodpi',
];

// Tier3 草药名 → herbId 映射
const HERB_NAME_MAP = {
    '龙血草': 'spiritGrass7',
    '风铃草': 'spiritGrass8',
    '九转灵草': 'spiritGrass9',
    '九转仙兰': 'spiritFlower7',
    '凤凰花': 'spiritFlower8',
    '青龙花': 'spiritFlower9',
    '赤阳果': 'spiritFruit7',
    '玄灵莓': 'spiritFruit8',
    '天元果': 'spiritFruit9',
};

const WEBP_OPTIONS = { lossless: true, effort: 6 };

/**
 * 从文件名中提取草药名和类型
 * 类型：herb / seed / growing
 */
function parseFileInfo(filename) {
    const base = path.basename(filename, '.png');

    // 去掉 "图片" 后缀（有些文件命名带"图片"）
    const clean = base.replace(/图片$/, '');

    if (clean.includes('成长期')) {
        const herbName = clean.replace('成长期', '');
        return { herbName, type: 'growing' };
    }
    if (clean.includes('草药')) {
        const herbName = clean.replace('草药', '');
        return { herbName, type: 'herb' };
    }
    if (clean.includes('种')) {
        const herbName = clean.replace('种', '');
        return { herbName, type: 'seed' };
    }
    if (clean.includes('核')) {
        const herbName = clean.replace('核', '');
        return { herbName, type: 'seed' };
    }

    return null;
}

/**
 * 获取前缀
 */
function getPrefix(type) {
    switch (type) {
        case 'herb': return 'herb_';
        case 'seed': return 'seed_';
        case 'growing': return 'growing_';
        default: throw new Error(`Unknown type: ${type}`);
    }
}

async function main() {
    console.log('=== Tier3 草药精灵图转换 ===\n');

    // 扫描源目录所有 PNG
    const allFiles = fs.readdirSync(SRC_DIR).filter(f => f.endsWith('.png'));
    console.log(`源目录共 ${allFiles.length} 个 PNG 文件`);

    // 只处理 tier3 草药
    const matchedFiles = [];

    for (const file of allFiles) {
        const info = parseFileInfo(file);
        if (!info) continue;
        if (!HERB_NAME_MAP[info.herbName]) continue;
        matchedFiles.push({ file, ...info });
    }

    console.log(`匹配到 ${matchedFiles.length} 个 tier3 文件\n`);

    if (matchedFiles.length === 0) {
        console.log('未找到任何 tier3 草药素材，退出。');
        return;
    }

    // 按 herbId 分组统计
    const byHerbId = {};
    for (const m of matchedFiles) {
        const herbId = HERB_NAME_MAP[m.herbName];
        if (!byHerbId[herbId]) byHerbId[herbId] = [];
        byHerbId[herbId].push(m);
    }

    console.log('覆盖列表：');
    for (const [herbId, files] of Object.entries(byHerbId).sort()) {
        console.log(`  ${herbId}: ${files.map(f => f.type).join(', ')}`);
    }
    console.log('');

    // 确保目标目录存在
    for (const target of TARGET_DIRS) {
        const fullDir = path.join(ROOT, target);
        if (!fs.existsSync(fullDir)) {
            fs.mkdirSync(fullDir, { recursive: true });
            console.log(`创建目录: ${target}`);
        }
    }

    // 转换并输出
    let successCount = 0;
    let failCount = 0;

    for (const { file, herbName, type } of matchedFiles) {
        const herbId = HERB_NAME_MAP[herbName];
        const prefix = getPrefix(type);
        const targetName = `${prefix}${herbId}.webp`.toLowerCase();
        const srcPath = path.join(SRC_DIR, file);

        // 读源文件
        let srcBuffer;
        try {
            srcBuffer = fs.readFileSync(srcPath);
        } catch (err) {
            console.error(`  FAILED: 读取 ${file} — ${err.message}`);
            failCount++;
            continue;
        }

        // 转换 WebP
        let webpBuffer;
        try {
            webpBuffer = await sharp(srcBuffer)
                .webp(WEBP_OPTIONS)
                .toBuffer();
        } catch (err) {
            console.error(`  FAILED: 转换 ${file} — ${err.message}`);
            failCount++;
            continue;
        }

        const srcKB = (srcBuffer.length / 1024).toFixed(0);
        const webpKB = (webpBuffer.length / 1024).toFixed(0);
        const ratio = webpBuffer.length > 0
            ? ((1 - webpBuffer.length / srcBuffer.length) * 100).toFixed(1)
            : '0.0';

        // 写入两个目标目录
        for (const target of TARGET_DIRS) {
            const targetPath = path.join(ROOT, target, targetName);
            try {
                fs.writeFileSync(targetPath, webpBuffer);
            } catch (err) {
                console.error(`  FAILED: 写入 ${target}/${targetName} — ${err.message}`);
                failCount++;
                continue;
            }
        }

        console.log(`  ${targetName}  ← ${file}  ${srcKB}KB → ${webpKB}KB  (-${ratio}%)`);
        successCount++;
    }

    console.log('\n=== 完成 ===');
    console.log(`成功: ${successCount} 个`);
    console.log(`失败: ${failCount} 个`);

    if (successCount > 0) {
        console.log(`\n下一步：`);
        console.log(`1. 更新 XianxiaApplication.kt 注册表`);
        console.log(`2. 更新 EquipmentSprite.kt fallback 阈值`);
        console.log(`3. 更新 Changelog`);
    }
}

main().catch(err => {
    console.error('脚本异常:', err);
    process.exit(1);
});
