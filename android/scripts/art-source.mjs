/**
 * 美术素材源目录的唯一解析入口（scaffold-source-mapping.mjs 与 import-art-assets.mjs 共用）。
 *
 * 真源依据 `rules/media-source-assets.md` §1-§2（2026-09-24 用户拍板）：
 * 项目指定的原始素材目录是**仓库根**的 `模拟宗门美术素材/`（另有 `模拟宗门音乐音效/`），
 * 两目录已写入 `.gitignore`——只登记不入库，换机器后需从项目外渠道同步。
 *
 * 缺失时**抛错而非静默返回**：源目录不存在若继续跑，脚手架会把 `source-mapping.json`
 * 全部条目的 `source` 改写成 null，而 import 对 null 条目直接跳过——坏会拖到下次重烘焙才暴露。
 *
 * 覆盖入口：环境变量 `MNZM_ART_SOURCE` 指向任意绝对路径（CI / 素材放在别处时）。
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** 环境变量覆盖入口名（报错文案与此保持一致） */
export const ART_SOURCE_ENV = 'MNZM_ART_SOURCE';

/** 仓库根（android/scripts → android → 仓库根） */
export const REPO_ROOT = path.resolve(__dirname, '..', '..');

/** 源目录相对仓库根的规范写法（写入 source-mapping.json，机器无关） */
export const ART_SOURCE_REL = '模拟宗门美术素材';

/**
 * 解析美术素材源目录的绝对路径。
 * @returns {string} 存在且可读的目录绝对路径
 * @throws {Error} 目录不存在时抛错（禁止静默产出全 null 映射）
 */
export function resolveArtSourceDir() {
  const configured = process.env[ART_SOURCE_ENV]?.trim();
  const dir = configured
    ? path.resolve(configured)
    : path.join(REPO_ROOT, ART_SOURCE_REL);
  if (!fs.existsSync(dir)) {
    throw new Error(
      `美术素材源目录不存在: ${dir}\n` +
      `  该目录是项目指定源资产（rules/media-source-assets.md §1），按拍板只登记不入库，需从项目外渠道同步。\n` +
      `  同步后放回仓库根，或用 ${ART_SOURCE_ENV}=<绝对路径> 指定。`
    );
  }
  return dir;
}
