/**
 * 资源清单生成脚本（对标 Godot 导入管线 .import sidecar）。
 *
 * 扫描 feature/game 与 app 两个模块的 drawable-nodpi 目录，生成 atlas-manifest.json：
 * 每个精灵条目包含名称（无扩展名）、所属模块、相对路径、文件大小、MD5、UID。
 *
 * UID 分配规则（对标 Godot 提交到版本库的 .uid sidecar 稳定引用）：
 * 名称→UID 映射持久化于 `android/scripts/sprite-uid-map.json`（提交版本库）——
 * 既有资源 UID 永不变化（含新增资源、clean 重建、内容变更），新资源按字典序
 * 追加新 UID（max+1，已删除名称的 UID 不复用）。
 *
 * 同名资源规则：两模块同名且内容（MD5）相同视为同一资源的双模块副本（合法，
 * 见 rules/static-resources.md 双模块放置要求）；同名但内容不同视为冲突，构建失败。
 *
 * 用法：
 *   node scripts/resource-manifest.mjs                # 默认扫描两模块，输出 app/build/generated/sprite/atlas-manifest.json
 *   node scripts/resource-manifest.mjs --dir <dir>    # 仅扫描指定目录（守卫测试用）
 *   node scripts/resource-manifest.mjs --out <path>   # 指定输出路径
 *   node scripts/resource-manifest.mjs --uid-map <path>  # 指定 UID 映射文件（默认 scripts/sprite-uid-map.json）
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ANDROID_DIR = path.resolve(__dirname, '..');

/** manifest 结构版本 */
const MANIFEST_VERSION = 1;

/** 资源扩展名白名单（与 rules/static-resources.md 一致） */
const IMAGE_EXTS = new Set(['.webp', '.png', '.jpg', '.jpeg']);

/**
 * 扫描单个资源目录，返回按文件名排序的条目（不含 UID）。
 * @param {string} dir 资源目录绝对路径
 * @param {string} moduleName 模块名（"app" / "feature/game"）
 */
export function scanDir(dir, moduleName) {
  if (!fs.existsSync(dir)) return [];
  const entries = [];
  for (const fileName of fs.readdirSync(dir).sort()) {
    const ext = path.extname(fileName).toLowerCase();
    if (!IMAGE_EXTS.has(ext)) continue;
    const absPath = path.join(dir, fileName);
    const stat = fs.statSync(absPath);
    if (!stat.isFile()) continue;
    entries.push({
      name: path.basename(fileName, path.extname(fileName)),
      module: moduleName,
      relPath: path.relative(ANDROID_DIR, absPath).replace(/\\/g, '/'),
      size: stat.size,
      md5: md5OfFile(absPath),
    });
  }
  return entries;
}

/**
 * 构建 manifest 对象。
 * @param {string} appDir app 模块 drawable-nodpi 目录
 * @param {string} gameDir feature/game 模块 drawable-nodpi 目录
 * @param {Map<string, number>} uidMap 既有名称→UID 映射（稳定引用持久层；空 Map = 全新分配）
 * @throws 同名但内容（MD5）不同的资源冲突时抛错
 */
export function buildManifest(appDir, gameDir, uidMap = new Map()) {
  const entries = [...scanDir(appDir, 'app'), ...scanDir(gameDir, 'feature/game')];

  // 同名冲突检查：同名同 MD5 = 双模块副本（合法）；同名异 MD5 = 冲突
  const byName = new Map();
  for (const e of entries) {
    if (byName.has(e.name)) {
      const prev = byName.get(e.name);
      if (prev.md5 !== e.md5) {
        throw new Error(
          `资源同名冲突: "${e.name}" 在 ${prev.module} 与 ${e.module} 中内容不一致（MD5 不同）——` +
          '同一资源双模块放置必须使用相同文件，不同资源禁止同名'
        );
      }
    } else {
      byName.set(e.name, e);
    }
  }

  // UID 分配：既有名称沿用映射值（永不漂移）；新名称按字典序追加 max+1
  let nextUid = uidMap.size > 0 ? Math.max(...uidMap.values()) + 1 : 0;
  const sorted = [...entries].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const e of sorted) {
    const existing = uidMap.get(e.name);
    if (existing !== undefined) {
      e.uid = existing;
    } else {
      e.uid = nextUid;
      nextUid++;
      uidMap.set(e.name, e.uid);
    }
  }

  // UID 唯一性校验（对抗性审查 2026-08-13 边界#6）：按**名称**去重后校验——
  // 双模块同名副本共享同一 UID 合法（同名同 UID），跨名称重复 UID 才破坏稳定引用契约
  const uidByName = new Map();
  for (const e of sorted) {
    const prev = uidByName.get(e.name);
    if (prev !== undefined && prev !== e.uid) {
      throw new Error(`UID 冲突: "${e.name}" 双模块副本 UID 不一致（${prev} vs ${e.uid}）`);
    }
    uidByName.set(e.name, e.uid);
  }
  const uidSet = new Set();
  for (const [name, uid] of uidByName) {
    if (uidSet.has(uid)) {
      throw new Error(`UID 冲突: "${name}" 与既有资源共用 UID ${uid}——sprite-uid-map.json 可能被手改`);
    }
    uidSet.add(uid);
  }

  return {
    version: MANIFEST_VERSION,
    generatedAt: new Date().toISOString(),
    entryCount: entries.length,
    entries: sorted,
  };
}

/**
 * 确保 manifest 存在（缺失或损坏时重新生成）。
 * @param {string} appDir app 模块 drawable-nodpi 目录
 * @param {string} gameDir feature/game 模块 drawable-nodpi 目录
 * @param {string} outPath 输出路径（绝对路径）
 * @returns manifest 对象
 */
export function ensureManifest(appDir, gameDir, outPath, uidMapPath = null) {
  if (fs.existsSync(outPath)) {
    try {
      const manifest = JSON.parse(fs.readFileSync(outPath, 'utf8'));
      if (manifest && manifest.version === MANIFEST_VERSION && Array.isArray(manifest.entries)) {
        return manifest;
      }
    } catch {
      // 解析失败视为损坏，重新生成
    }
  }
  // 重建分支同样持久化 UID（对抗性审查 2026-08-13 边界#12：原实现不传
  // uidMapPath 导致 UID 全新分配且不写回——下次构建 UID 漂移）
  return writeManifest(appDir, gameDir, outPath, uidMapPath);
}

/**
 * 生成并写入 manifest 文件。
 * @param {string} uidMapPath UID 映射文件路径（null = 不持久化，纯临时分配）
 * @returns manifest 对象
 */
export function writeManifest(appDir, gameDir, outPath, uidMapPath = null) {
  const uidMap = readUidMap(uidMapPath);
  const manifest = buildManifest(appDir, gameDir, uidMap);
  if (uidMapPath) writeUidMap(uidMapPath, uidMap);
  const content = JSON.stringify(manifest, null, 2) + '\n';
  // 增量跳过：剥离 generatedAt 后内容不变则不重写——mtime 稳定，
  // 下游 build-atlas.mjs hash 增量与 CMake 头文件依赖检查不再每构建失效
  //（对抗性审查 2026-08-13 边界#1/逆向#2 发现）
  const previous = fs.existsSync(outPath) ? fs.readFileSync(outPath, 'utf8') : '';
  if (stripGeneratedAt(previous) === stripGeneratedAt(content)) return manifest;
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, content);
  return manifest;
}

/** 剥离构建时间戳（比较用，不修改原内容） */
function stripGeneratedAt(text) {
  return text.replace(/"generatedAt": "[^"]*"/, '"generatedAt": ""');
}

/** 读取 UID 映射文件（不存在 = 空映射；非法条目过滤——对抗性审查 2026-08-13 边界#6） */
function readUidMap(uidMapPath) {
  const map = new Map();
  if (!uidMapPath || !fs.existsSync(uidMapPath)) return map;
  try {
    const parsed = JSON.parse(fs.readFileSync(uidMapPath, 'utf8'));
    for (const [name, uid] of Object.entries(parsed)) {
      // 仅接受安全整数非负 UID：手改负数/小数/1e30（浮点精度下 max+1 恒等）
      // 等非法值一律过滤，防止重复 UID 与 nextUid 停滞
      if (typeof name === 'string' && name.length > 0 &&
          Number.isSafeInteger(uid) && uid >= 0) {
        map.set(name, uid);
      }
    }
  } catch {
    // 损坏视为空映射（新名称重新分配；既有名称 UID 无法恢复时以新分配为准）
  }
  return map;
}

/** 写入 UID 映射文件（按名称排序，保证版本库 diff 稳定） */
function writeUidMap(uidMapPath, uidMap) {
  const sortedEntries = [...uidMap.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  const obj = Object.fromEntries(sortedEntries);
  fs.mkdirSync(path.dirname(uidMapPath), { recursive: true });
  fs.writeFileSync(uidMapPath, JSON.stringify(obj, null, 2) + '\n');
}

/** 计算文件 MD5 */
function md5OfFile(filePath) {
  return crypto.createHash('md5').update(fs.readFileSync(filePath)).digest('hex');
}

function main() {
  const args = process.argv.slice(2);
  const dirIndex = args.indexOf('--dir');
  const outIndex = args.indexOf('--out');
  const uidMapIndex = args.indexOf('--uid-map');

  let appDir = path.join(ANDROID_DIR, 'app/src/main/res/drawable-nodpi');
  let gameDir = path.join(ANDROID_DIR, 'feature/game/src/main/res/drawable-nodpi');
  if (dirIndex >= 0 && args[dirIndex + 1]) {
    appDir = path.resolve(process.cwd(), args[dirIndex + 1]);
    gameDir = null;
  }
  const outPath = outIndex >= 0 && args[outIndex + 1]
    ? path.resolve(process.cwd(), args[outIndex + 1])
    : path.join(ANDROID_DIR, 'app/build/generated/sprite/atlas-manifest.json');
  const uidMapPath = uidMapIndex >= 0 && args[uidMapIndex + 1]
    ? path.resolve(process.cwd(), args[uidMapIndex + 1])
    : path.join(__dirname, 'sprite-uid-map.json');

  const manifest = gameDir
    ? writeManifest(appDir, gameDir, outPath, uidMapPath)
    : writeManifest(appDir, '', outPath, uidMapPath);
  console.log(`resource-manifest: ${manifest.entryCount} 条资源 -> ${outPath}`);
}

// 作为 CLI 运行时执行 main；被 import 时不执行
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
