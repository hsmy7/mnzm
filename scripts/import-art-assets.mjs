/**
 * 素材源目录导入脚本（美术素材单一权威源：D:\模拟宗门美术素材）。
 *
 * 依赖说明：本脚本依赖 sharp（npm 包），由仓库根目录 package.json / package-lock.json
 * 管理（devDependencies 声明 sharp ^0.35.x）。Node 从脚本所在目录向上解析 node_modules，
 * 命中仓库根 node_modules——运行前请确认根目录已执行 `npm install`（或 `npm ci`）。
 *
 * 职责：把素材源目录中的 PNG 无损转换为 WebP（lossless: true, effort: 6），
 * 写入各模块 drawable-nodpi（见 rules/static-resources.md「素材源目录」章节）。
 * - 天枢殿：等比缩放并透明延展到 600×400（3:2，与原精灵一致），输出 feature/game 模块
 * - 云层：保持原生尺寸，输出 feature/game 与 app 双模块（同名同内容）
 *
 * 新增素材：在下方 IMPORT 映射表登记一行（源文件名 → drawable 名 + 目标模块），
 * 再运行本脚本即可。重复运行幂等（源文件缺失时 SKIP 并提示）。
 */
import sharp from 'sharp';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

/** 素材源目录（唯一权威源，素材改动一律放这里） */
const SOURCE_DIR = 'D:/模拟宗门美术素材';

/** 目标模块 drawable-nodpi 目录（与 rules/static-resources.md 双模块放置规则一致） */
const MODULE_DIRS = {
  'feature/game': path.resolve(ROOT, 'android/feature/game/src/main/res/drawable-nodpi'),
  'app': path.resolve(ROOT, 'android/app/src/main/res/drawable-nodpi'),
};

/**
 * 导入映射表：
 * - out：输出 drawable 文件名（无扩展名）
 * - canvasW/canvasH：可选，等比缩放并透明延展到该画布尺寸（fit: contain，不拉伸变形）
 * - modules：输出到的模块列表（feature/game 为建筑/地图精灵惯例，app 为双模块副本）
 */
const IMPORT = {
  // 天枢殿（新素材 1442×1091，2026-08-22 更新）——保持 600×400 与原精灵一致
  '天枢殿': { out: 'building_tianshu_hall', canvasW: 600, canvasH: 400, modules: ['feature/game'] },
  // 云层（世界顶部动态云朵，5 种形态，保持原生尺寸）
  '云层1': { out: 'cloud_1', modules: ['feature/game', 'app'] },
  '云层2': { out: 'cloud_2', modules: ['feature/game', 'app'] },
  '云层3': { out: 'cloud_3', modules: ['feature/game', 'app'] },
  '云层4': { out: 'cloud_4', modules: ['feature/game', 'app'] },
  '云层5': { out: 'cloud_5', modules: ['feature/game', 'app'] },
};

/** 无损 WebP 编码参数（rules/static-resources.md：lossless: true, effort: 6） */
const WEBP_OPTIONS = { lossless: true, effort: 6 };

async function main() {
  console.log('=== 素材源目录导入（无损 WebP）===');
  console.log(`源目录: ${SOURCE_DIR}\n`);

  for (const [name, cfg] of Object.entries(IMPORT)) {
    const srcFile = path.join(SOURCE_DIR, name + '.png');
    if (!fs.existsSync(srcFile)) {
      console.log(`SKIP: 源文件不存在: ${name}.png`);
      continue;
    }

    const meta = await sharp(srcFile).metadata();
    const encode = sharp(srcFile);
    let outW = meta.width;
    let outH = meta.height;
    if (cfg.canvasW && cfg.canvasH) {
      encode.resize(cfg.canvasW, cfg.canvasH, {
        fit: 'contain',
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      });
      outW = cfg.canvasW;
      outH = cfg.canvasH;
    }
    const result = await encode.webp(WEBP_OPTIONS).toBuffer();

    for (const mod of cfg.modules) {
      const dir = MODULE_DIRS[mod];
      if (!dir) throw new Error(`未知模块: ${mod}（映射表配置错误）`);
      fs.mkdirSync(dir, { recursive: true });
      const dstFile = path.join(dir, cfg.out + '.webp');
      fs.writeFileSync(dstFile, result);
      const dstSize = (fs.statSync(dstFile).size / 1024).toFixed(0);
      const srcSize = (fs.statSync(srcFile).size / 1024).toFixed(0);
      console.log(`${name}.png → ${mod}/${cfg.out}.webp  ${srcSize}KB → ${dstSize}KB  (${outW}x${outH} 无损)`);
    }
  }

  console.log('\n=== Done ===');
  console.log('说明：所有产物为 WebP 无损（lossless, effort=6）；PNG 源文件不进入仓库。');
}

main().catch((err) => {
  console.error('导入失败:', err);
  process.exit(1);
});
