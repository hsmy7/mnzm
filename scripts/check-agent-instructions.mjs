#!/usr/bin/env node
/**
 * check-agent-instructions.mjs — 规范分发架构门禁
 *
 * 背景：项目规范分三层——根 AGENTS.md（正文，唯一真源）、rules/*.md（专题规则）、
 * docs/**（架构文档）。各 agent 的指令加载能力差异极大，规范能否被读到取决于四条
 * 不变式；本脚本把它们从人工纪律变成硬闸：
 *
 *   - Codex CLI：只在会话启动时构建「Git 根 → cwd」指令链，每级只取一个文件，
 *     且合并上限 project_doc_max_bytes **默认 32768 字节**——超出即截断，根文件尾部
 *     的规范对 Codex 静默失效（不报错、不提示）。
 *   - DSH：默认候选 ['AGENTS.md','CLAUDE.md']，预算 65536 字节，超预算按
 *     「丢前缀、保后缀」淘汰——同目录内 AGENTS.md 排在 CLAUDE.md 之前，
 *     故 AGENTS.md 会先被牺牲。
 *   - 其余按 AGENTS.md 标准的工具：只读根文件，不会自动加载嵌套文件，
 *     所以根文件的「任务 → 必读文档」路由表是它们唯一的引导手段。
 *
 * 规则（五条，全部结构性）：
 *   ① 预算闸：根 AGENTS.md 的 UTF-8 字节数 ≤ CODEX_LIMIT(32768)——超出即被 Codex 截断；
 *   ② 单一真源：仓库不维护 CLAUDE.md（规范只此一份）。该文件若被重新引入，
 *      必须只是 `@AGENTS.md` 指针，不得复制正文——副本会漂移，且两份内容同时
 *      占用每个 agent 的指令预算；
 *   ③ 引用无死链：AGENTS.md / rules/*.md / docs/** 中的内部文件引用必须指向真实文件——
 *      死链意味着 agent 按路由表去读时扑空（先例：根文件曾把
 *      android/docs/renderer-feature-checklist.md 写成 docs/renderer-feature-checklist.md）；
 *   ④ 路由表完整：磁盘上每个 AGENTS.md 都必须被根 AGENTS.md 登记——新增嵌套规范
 *      文件却不登记入口，等于它永远不会被任何 agent 读到；
 *   ⑤ 子目录启动链路（告警级）：从子目录启动 Codex 时链上文件合并计费，
 *      过大即截断——只告警不拦（从仓库根启动是常态）。
 *
 * 豁免方式 = 无。五条规则都是结构性不变式：①要留预算就改内容而不是放宽阈值；
 * ②③④的正确修法是删副本、修路径、登记路由表，不是加白名单。本脚本不提供
 * --force 类跳过通道。
 *
 * 用法：
 *   node scripts/check-agent-instructions.mjs            # 本地/CI 同一入口
 *   node scripts/check-agent-instructions.mjs --strict   # 把「路径写法不精确」也当失败（清理用）
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Codex CLI 合并项目指令的默认上限（project_doc_max_bytes） */
const CODEX_LIMIT = 32768;
/** 带余量的告警线：逼近上限时提示，避免下一次编辑就翻车 */
const WARN_LINE = 30000;

const ROOT_AGENTS = 'AGENTS.md';
const ROOT_CLAUDE = 'CLAUDE.md';
/** 指针文件的唯一合法内容（Claude Code 的官方 import 语法；DSH 侧为无害的空操作） */
const POINTER_CONTENT = '@AGENTS.md';

/** 不参与扫描的目录（副本 / 依赖 / 产物 / 次级工作树） */
const EXCLUDED_DIRS = new Set([
  'node_modules',
  '.git',
  '.worktrees',
  'build',
  '.gradle',
  '.idea',
  '.kotlin',
  '.claude',
  '.superpowers',
  '.zcode',
  '.trae',
  '.qodo',
  '.workbuddy',
  '.workbuddy-ai',
  '.memsearch',
  '.clash-repair',
]);

/** 仓库内所有 AGENTS.md（用于规则④） */
function collectAgentsFiles(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    if (EXCLUDED_DIRS.has(name)) continue;
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      collectAgentsFiles(full, acc);
    } else if (name === 'AGENTS.md') {
      acc.push(full);
    }
  }
  return acc;
}

const toPosix = (p) => relative(repoRoot, p).split(sep).join('/');

/**
 * 剥离 fenced code block 的内容（保留行数，行号仍可对齐）。
 * 代码块里的相对路径是 shell 命令的执行上下文（如 cwd=android/ 时写 `../scripts/x.mjs`），
 * 不是文档引用——不剥离必然误报。
 */
function stripFencedCode(source) {
  let inFence = false;
  return source.split('\n').map((line) => {
    if (/^\s*```/.test(line)) {
      inFence = !inFence;
      return '';
    }
    return inFence ? '' : line;
  }).join('\n');
}

/**
 * 提取文档内的内部文件引用。
 * 覆盖两种现实写法：
 *   1. Markdown 链接 [文字](路径)
 *   2. 反引号包裹的文档路径 `rules/xxx.md`、`docs/xxx.md#锚点`
 * 反引号分支只收文档类扩展名——代码路径（`GameEngine.kt:99`）不是引用检查的对象。
 */
function extractRefs(source) {
  const refs = [];
  const push = (raw, line) => {
    const target = raw.split('#')[0].trim();
    if (target === '' || /^[a-z][a-z0-9+.-]*:/i.test(target)) return; // URL / 协议
    if (target.startsWith('/') || target.startsWith('~')) return; // 绝对路径不在仓库内
    if (target.includes('...')) return; // 省略式路径（app/.../core/）不是可校验引用
    refs.push({ target, line });
  };

  const linkRe = /\[[^\]]*\]\(([^)\s]+)\)/g;
  let m;
  while ((m = linkRe.exec(source)) !== null) {
    push(m[1], source.slice(0, m.index).split('\n').length);
  }

  const codeRe = /`([A-Za-z0-9_][A-Za-z0-9_\-./]*\.(?:md|html|json|ya?ml)(?:#[^`]*)?)`/g;
  while ((m = codeRe.exec(source)) !== null) {
    push(m[1], source.slice(0, m.index).split('\n').length);
  }
  return refs;
}

const strict = process.argv.includes('--strict');
let failed = false;

// ── 规则① 预算闸 ────────────────────────────────────────────────────────────
const rootAgentsPath = join(repoRoot, ROOT_AGENTS);
if (!existsSync(rootAgentsPath)) {
  console.error(`✗ 规则①：根 ${ROOT_AGENTS} 不存在——规范唯一真源缺失，所有 agent 都读不到项目规范。`);
  failed = true;
} else {
  const bytes = readFileSync(rootAgentsPath).length;
  if (bytes > CODEX_LIMIT) {
    console.error(
      `✗ 规则① 预算闸：${ROOT_AGENTS} = ${bytes} 字节 > Codex 上限 ${CODEX_LIMIT} 字节。\n` +
      `  Codex 会在此截断，文件尾部的规范对它静默失效。把内容下沉到 rules/ 并在路由表登记，不要放宽阈值。`,
    );
    failed = true;
  } else if (bytes > WARN_LINE) {
    console.log(`↘ 规则① 预算闸：${ROOT_AGENTS} = ${bytes} 字节（上限 ${CODEX_LIMIT}，余量仅 ${CODEX_LIMIT - bytes}）`);
  } else {
    console.log(`✓ 规则① 预算闸：${ROOT_AGENTS} = ${bytes} / ${CODEX_LIMIT} 字节`);
  }
}

// ── 规则② 单一真源 ──────────────────────────────────────────────────────────
// 项目不维护 CLAUDE.md（规范只在 AGENTS.md）。本规则是「防双份维护」的哨兵：
// 该文件一旦被重新引入，必须只是纯指针，不得复制规范正文。
const rootClaudePath = join(repoRoot, ROOT_CLAUDE);
if (!existsSync(rootClaudePath)) {
  console.log(`✓ 规则② 单一真源：无 ${ROOT_CLAUDE}（规范只在 ${ROOT_AGENTS}，无双份维护）`);
} else {
  const content = readFileSync(rootClaudePath, 'utf8').trim();
  if (content !== POINTER_CONTENT) {
    console.error(
      `✗ 规则② 单一真源：${ROOT_CLAUDE} 被重新引入时必须是纯指针（内容仅 \`${POINTER_CONTENT}\`），当前 ${content.length} 字符。\n` +
      `  禁止在两份文件里维护规范副本：会漂移，且双份内容同时占用每个 agent 的指令预算。`,
    );
    failed = true;
  } else {
    console.log(`✓ 规则② 单一真源：${ROOT_CLAUDE} → ${POINTER_CONTENT}`);
  }
}

// ── 规则③ 引用无死链 ────────────────────────────────────────────────────────
// 检查范围 = 从根 AGENTS.md 出发的引用闭包：agent 的实际行为就是照着根文件的路由表
// 跳进 rules/ 与 docs/，再顺着那些文档内的引用继续走。只保证这条路径不扑空。
//
// 两处收窄，都是为了不把"历史演进的正常结果"误判成缺陷：
//   ① 历史/过程档案（CHANGELOG.md、批次派工档案等）不参与检查——它们的引用指向
//      文档当时的名字，随架构演进而失效是正常现象，修它们既无收益也污染变更；
//   ② 超过一跳的文件只在"必读区"（根文件 / rules/ / android/docs/ / docs/ 顶层）
//      继续展开——docs/adr/、docs/parallel-batches*/ 等子目录属决策与过程记录。
//
// 路径解析顺序：① 相对引用所在文件 ② 相对仓库根——项目文档两种写法都在用
// （docs/adr/ 下的 ADR 惯用仓库根写法 docs/xxx.md，而非 ../xxx.md）。
// 两者都不中时按 basename 反查：能反查到说明「路径不精确」，反查不到才是真死链；
// 两类都会让 agent 扑空，故都失败。
const MAX_DEPTH = 2;

/** 历史/过程档案：内部引用不参与检查 */
const HISTORICAL_FILES = new Set(['CHANGELOG.md', 'task_plan.md', 'progress.md', 'findings.md']);

/** 超过一跳仍继续展开的「必读区」 */
function isRequiredReading(posixPath) {
  return (
    posixPath === ROOT_AGENTS
    || posixPath.startsWith('rules/')
    || posixPath.startsWith('android/docs/')
    || (posixPath.startsWith('docs/') && !posixPath.slice('docs/'.length).includes('/'))
  );
}

const byBasename = new Map();
(function indexAll(dir) {
  for (const name of readdirSync(dir)) {
    if (EXCLUDED_DIRS.has(name)) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) indexAll(full);
    else if (!byBasename.has(name)) byBasename.set(name, toPosix(full));
  }
})(repoRoot);

const deadLinks = [];
const imprecise = [];
let refCount = 0;
let scannedDocs = 0;

/** 解析结果必须落在仓库内——`../../` 越界会去仓外找文件，绝不能算命中 */
function insideRepo(candidate) {
  const rel = relative(repoRoot, candidate);
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel);
}

function resolveRef(refFile, target) {
  for (const candidate of [join(dirname(refFile), target), join(repoRoot, target)]) {
    if (insideRepo(candidate) && existsSync(candidate)) return 'ok';
  }
  return byBasename.has(target.split('/').pop()) ? 'imprecise' : 'dead';
}

if (existsSync(rootAgentsPath)) {
  const visited = new Set();
  const queue = [{ file: rootAgentsPath, depth: 0 }];
  while (queue.length > 0) {
    const { file, depth } = queue.shift();
    if (visited.has(file)) continue;
    visited.add(file);
    if (!file.endsWith('.md')) continue;
    const posix = toPosix(file);
    if (HISTORICAL_FILES.has(posix)) continue;
    if (depth >= MAX_DEPTH && !isRequiredReading(posix)) continue;
    scannedDocs += 1;
    const source = stripFencedCode(readFileSync(file, 'utf8'));
    for (const { target, line } of extractRefs(source)) {
      refCount += 1;
      const verdict = resolveRef(file, target);
      if (verdict === 'dead') {
        deadLinks.push({ file: posix, line, target });
      } else if (verdict === 'imprecise') {
        imprecise.push({ file: posix, line, target, actual: byBasename.get(target.split('/').pop()) });
      } else if (depth + 1 < MAX_DEPTH) {
        queue.push({ file: join(repoRoot, target), depth: depth + 1 });
        const local = join(dirname(file), target);
        if (existsSync(local)) queue.push({ file: local, depth: depth + 1 });
      }
    }
  }
}

if (deadLinks.length > 0) {
  console.error(`✗ 规则③ 引用无死链：${deadLinks.length} 处引用在仓库内找不到目标——agent 按路由表去读会扑空：`);
  for (const { file, line, target } of deadLinks.slice(0, 30)) {
    console.error(`    - ${file}:${line} → ${target}`);
  }
  if (deadLinks.length > 30) console.error(`    … 另有 ${deadLinks.length - 30} 处`);
  failed = true;
}
if (imprecise.length > 0) {
  // 目标存在、只是写法不可直接解析（裸文件名 / 缺前缀）——默认告警不拦：表格里的「产物名」
  // 类引用本就该写文件名（位置另列）。要彻底清理时用 --strict 把它也算失败。
  console.error(`${strict ? '✗' : '⚠'} 规则③ 引用路径不精确：${imprecise.length} 处只在 basename 上成立：`);
  for (const { file, line, target, actual } of imprecise.slice(0, 20)) {
    console.error(`    - ${file}:${line} → ${target}  （实际在 ${actual}）`);
  }
  if (imprecise.length > 20) console.error(`    … 另有 ${imprecise.length - 20} 处`);
  if (strict) failed = true;
}
if (deadLinks.length === 0 && imprecise.length === 0) {
  console.log(`✓ 规则③ 引用无死链：路由闭包 ${scannedDocs} 篇文档、${refCount} 条内部引用全部可解析`);
} else if (deadLinks.length === 0) {
  console.log(`✓ 规则③ 引用无死链：路由闭包 ${scannedDocs} 篇文档、${refCount} 条引用无死链（${imprecise.length} 处写法不精确，见上方告警）`);
}

// ── 规则④ 路由表完整 ────────────────────────────────────────────────────────
const agentsFiles = collectAgentsFiles(repoRoot).map(toPosix).sort();
if (existsSync(rootAgentsPath)) {
  const rootSource = readFileSync(rootAgentsPath, 'utf8');
  const unregistered = agentsFiles.filter((p) => p !== ROOT_AGENTS && !rootSource.includes(p));
  if (unregistered.length > 0) {
    console.error(`✗ 规则④ 路由表完整：以下 AGENTS.md 未在根 ${ROOT_AGENTS} 登记——不会被任何 agent 读到：`);
    for (const p of unregistered) console.error(`    - ${p}`);
    console.error(`  在根 ${ROOT_AGENTS} 的路由表中补一行链接与触发条件。`);
    failed = true;
  } else {
    console.log(`✓ 规则④ 路由表完整：${agentsFiles.length} 个 AGENTS.md 全部已登记（根文件 + ${agentsFiles.length - 1} 个模块级）`);
  }
}

// ── 规则⑤ 子目录启动的链路预算（告警级）────────────────────────────────────
// Codex 从子目录启动时把「root → cwd」链上每一级的 AGENTS.md 合并计算，合计超上限同样截断。
// 从仓库根启动是常态（链上只有根文件），故此处只告警不拦；模块级文件保持精简即不会触发。
if (existsSync(rootAgentsPath) && agentsFiles.length > 1) {
  const rootBytes = readFileSync(rootAgentsPath).length;
  const chains = agentsFiles
    .filter((p) => p !== ROOT_AGENTS)
    .map((p) => {
      const dirs = p.split('/').slice(0, -1);
      let total = rootBytes;
      for (let i = 1; i <= dirs.length; i += 1) {
        const ancestor = `${dirs.slice(0, i).join('/')}/AGENTS.md`;
        const full = join(repoRoot, ancestor);
        if (existsSync(full)) total += readFileSync(full).length;
      }
      return { path: p, total };
    })
    .sort((a, b) => b.total - a.total);
  const worst = chains[0];
  if (worst.total > CODEX_LIMIT) {
    console.log(`⚠ 规则⑤ 子目录启动链路：最坏 ${worst.path} 的链路 = ${worst.total} 字节 > ${CODEX_LIMIT}`
      + '——从该目录用 Codex 启动会截断；保持模块级 AGENTS.md 精简（从仓库根启动不受影响）。');
  } else {
    console.log(`✓ 规则⑤ 子目录启动链路：最坏 ${worst.path} = ${worst.total} / ${CODEX_LIMIT} 字节`);
  }
}

if (failed) process.exit(1);
console.log('✓ 规范分发架构门禁全部通过。');
