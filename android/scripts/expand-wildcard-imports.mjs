// D-06 一次性迁移工具：将自有包 com.xianxia.sect.* 通配 import 展开为显式 import
// 用法（在 android/ 目录）：node scripts/expand-wildcard-imports.mjs [--dry-run]
// 原则：
//  - 包内顶层符号集按文件内的 package 声明分组提取（包声明与物理路径无关，
//    core.engine 系包声明与目录不一致——不能按路径匹配目录）
//  - 文件清单 = git 已跟踪 + 未跟踪（D-01~D-17 新文件未被 git 跟踪，只跑
//    `git ls-files` 会漏掉）
//  - 引用匹配用状态机扫描正文（排除 import 段/注释/字符串），得到实际用到的符号
//  - 无引用的通配行直接删除；同名符号跨包冲突的文件跳过（保通配，报告人工处理）；
//    文件内自身声明的符号优先于 import（不构成冲突）
import { execSync } from 'node:child_process'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'

const DRY = process.argv.includes('--dry-run')
const ROOT = process.cwd()

const tracked = execSync('git ls-files core app feature -- "*.kt"', { cwd: ROOT, encoding: 'utf8' })
const untracked = execSync('git ls-files --others --exclude-standard -- core app feature "*.kt"', { cwd: ROOT, encoding: 'utf8' })
const files = [...new Set([...tracked.trim().split('\n'), ...untracked.trim().split('\n')])]
  .filter(Boolean).filter((f) => existsSync(f)).sort()

// ── 顶层声明提取 ──
// 类/接口/对象/typealias：关键字后直接跟名字
// 函数：fun [<T>] [Receiver.]Name[(<T>)] ( —— 接收者贪婪匹配，防止把外来接收者
//   类型（如 `fun IntArray.sum()` 的 IntArray）误当本包符号
// val/var：关键字后跟名字
const TOP_CLASS_RE = /\b(?:class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)\b/
const TOP_FUN_RE = /\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>]*\.)?([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*\(/
// 扩展属性 `val Receiver.name`：接收者贪婪匹配，防止把接收者类型误当符号
const TOP_VAL_RE = /\b(?:val|var)\s+(?:[A-Za-z_][A-Za-z0-9_.<>]*\.)?([A-Za-z_][A-Za-z0-9_]*)\b/
function collectDeclared(content) {
  const symbols = new Set()
  for (const line of content.split(/\r?\n/)) {
    const code = line.replace(/\/\/.*$/, '').trimStart()
    if (!code || code.startsWith('@') || code.startsWith('{') || code.startsWith('}')) continue
    const m = code.match(TOP_CLASS_RE)
    if (m) { symbols.add(m[1]); continue }
    const m2 = code.match(TOP_FUN_RE)
    if (m2) { symbols.add(m2[1]); continue }
    const m3 = code.match(TOP_VAL_RE)
    if (m3) symbols.add(m3[1])
  }
  return symbols
}

/** 顶层声明（无缩进）——用于构建包符号表 */
function topLevelSymbols(content) {
  const symbols = new Set()
  for (const line of content.split(/\r?\n/)) {
    if (/^\s/.test(line)) continue // 缩进 = 类成员（字段/方法），非顶层声明
    const code = line.replace(/\/\/.*$/, '').trimStart()
    if (!code || code.startsWith('@') || code.startsWith('{') || code.startsWith('}')) continue
    if (/\bprivate\b/.test(code)) continue // private 顶层仅文件内可见，不可跨文件 import
    const m = code.match(TOP_CLASS_RE)
    if (m) { symbols.add(m[1]); continue }
    const m2 = code.match(TOP_FUN_RE)
    if (m2) { symbols.add(m2[1]); continue }
    const m3 = code.match(TOP_VAL_RE)
    if (m3) symbols.add(m3[1])
  }
  return symbols
}

/** 文件内全部声明（含缩进的类成员）——文件内声明优先于 import，不构成跨包冲突 */
function declaredSymbols(content) {
  return collectDeclared(content)
}

// ── 引用扫描：状态机排除注释与字符串 ──
function codeTokens(content) {
  const tokens = new Set()
  let i = 0
  let state = 'code'
  while (i < content.length) {
    const c = content[i]
    if (state === 'code') {
      if (c === '/' && content[i + 1] === '/') { state = 'line'; i += 2; continue }
      if (c === '/' && content[i + 1] === '*') { state = 'block'; i += 2; continue }
      if (c === '"') {
        if (content[i + 1] === '"' && content[i + 2] === '"') { state = 'trip'; i += 3 } else { state = 'str'; i += 1 }
        continue
      }
      if (/[A-Za-z_]/.test(c)) {
        let j = i
        while (j < content.length && /[A-Za-z0-9_]/.test(content[j])) j++
        tokens.add(content.slice(i, j))
        i = j
        continue
      }
      i++
    } else if (state === 'line') {
      if (c === '\n') state = 'code'
      i++
    } else if (state === 'block') {
      if (c === '*' && content[i + 1] === '/') { state = 'code'; i += 2; continue }
      i++
    } else if (state === 'str') {
      if (c === '\\') { i += 2; continue }
      if (c === '$' && content[i + 1] === '{') {
        // Kotlin 字符串模板 ${expr}：表达式内是代码，递归扫描标识符（支持嵌套）
        let depth = 1
        i += 2
        while (i < content.length && depth > 0) {
          const ch = content[i]
          if (ch === '{') { depth++; i++; continue }
          if (ch === '}') { depth--; i++; continue }
          if (ch === '"') {
            i++
            while (i < content.length && content[i] !== '"') {
              if (content[i] === '\\') i++
              i++
            }
            i++
            continue
          }
          if (/[A-Za-z_]/.test(ch)) {
            let j = i
            while (j < content.length && /[A-Za-z0-9_]/.test(content[j])) j++
            tokens.add(content.slice(i, j))
            i = j
            continue
          }
          i++
        }
        continue
      }
      if (c === '$') {
        // Kotlin 字符串模板 $identifier：收集标识符
        i++
        if (/[A-Za-z_]/.test(content[i])) {
          let j = i
          while (j < content.length && /[A-Za-z0-9_]/.test(content[j])) j++
          tokens.add(content.slice(i, j))
          i = j
          continue
        }
        continue
      }
      if (c === '"') state = 'code'
      i++
    } else {
      if (c === '"' && content[i + 1] === '"' && content[i + 2] === '"') { state = 'code'; i += 3; continue }
      i++
    }
  }
  return tokens
}

// ── 包 → 顶层符号集（按 package 声明分组构建，全量扫描一次）──
const pkgSymbols = new Map() // pkg -> Set(symbol)
for (const f of files) {
  let content
  try { content = readFileSync(f, 'utf8') } catch { continue }
  const m = content.match(/^package ([a-zA-Z0-9_.]+)/m)
  if (!m || !m[1].startsWith('com.xianxia.sect.')) continue
  const pkg = m[1]
  if (!pkgSymbols.has(pkg)) pkgSymbols.set(pkg, new Set())
  for (const s of topLevelSymbols(content)) pkgSymbols.get(pkg).add(s)
}

const WILD_RE = /^import (com\.xianxia\.sect\.[A-Za-z0-9_.]+)\.\*\r?$/

let expandedFiles = 0
let expandedLines = 0
let deletedLines = 0
let skippedFiles = 0
const skipped = []
const preservedPkgs = new Set()

for (const file of files) {
  const original = readFileSync(file, 'utf8')
  const crlf = original.includes('\r\n')
  const lines = original.split(/\r?\n/)

  // 定位 import 段：最后一个 import 行之后为正文起点
  let lastImportIdx = -1
  for (let i = 0; i < lines.length; i++) {
    if (/^import /.test(lines[i])) lastImportIdx = i
  }
  const body = lastImportIdx < 0 ? original : lines.slice(lastImportIdx + 1).join('\n')
  const tokens = codeTokens(body)

  // 收集通配行
  const wilds = []
  const preserved = [] // 符号表缺失的包（生成代码如 proto 生成源不在 git 清单）——原样保留通配
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(WILD_RE)
    if (m) {
      if (!pkgSymbols.has(m[1])) preserved.push(m[1]) // 保留原行，不展开不删除
      else wilds.push({ idx: i, pkg: m[1] })
    }
  }
  if (wilds.length === 0 && preserved.length === 0) continue
  for (const p of preserved) preservedPkgs.add(p)

  // 已有显式 import 的符号（Kotlin 已解析，通配展开跳过——重复 import 会报 Conflicting）
  const existingImports = new Set(lines.filter((l) => /^import /.test(l))
    .map((l) => l.replace(/^import /, '').replace(/;\s*$/, '')))

  // 每个通配包实际引用
  const usedByPkg = new Map() // pkg -> [symbols]
  for (const w of wilds) {
    const used = [...(pkgSymbols.get(w.pkg) || new Set())]
      .filter((s) => tokens.has(s))
      .filter((s) => !existingImports.has(`${w.pkg}.${s}`))
    usedByPkg.set(w.pkg, used)
  }

  // 同名跨包冲突检测（排除文件内自身声明——含类成员，Kotlin 解析时文件内声明优先于 import）
  const ownSymbols = declaredSymbols(original)
  const byName = new Map()
  for (const [pkg, used] of usedByPkg) {
    for (const s of used) {
      if (ownSymbols.has(s)) continue
      if (!byName.has(s)) byName.set(s, new Set())
      byName.get(s).add(pkg)
    }
  }
  const conflicts = [...byName].filter(([, pkgs]) => pkgs.size > 1)
  if (conflicts.length > 0) {
    skippedFiles++
    skipped.push({ file, conflicts: conflicts.map(([s, pkgs]) => `${s}@${[...pkgs].join('|')}`) })
    continue
  }

  // 生成新行
  const perLine = new Map() // idx -> replacement lines | null(删除)
  for (const w of wilds) {
    const used = [...(usedByPkg.get(w.pkg) || [])].sort()
    if (used.length === 0) {
      perLine.set(w.idx, null) // 删除该行
      deletedLines++
    } else {
      perLine.set(w.idx, used.map((s) => `import ${w.pkg}.${s}`))
      expandedLines += used.length
    }
  }

  const newLines = []
  for (let i = 0; i < lines.length; i++) {
    if (perLine.has(i)) {
      const repl = perLine.get(i)
      if (repl) newLines.push(...repl)
    } else {
      newLines.push(lines[i])
    }
  }
  const output = newLines.join(crlf ? '\r\n' : '\n')
  if (output !== original) {
    expandedFiles++
    if (!DRY) writeFileSync(file, output, 'utf8')
  }
}

console.log(`文件数: ${files.length}`)
console.log(`展开文件: ${expandedFiles}，展开 import 行: ${expandedLines}，删除零引用行: ${deletedLines}`)
console.log(`跳过（跨包同名冲突）: ${skippedFiles}`)
for (const s of skipped) console.log(`  冲突 ${s.file}: ${s.conflicts.join(', ')}`)
console.log(`保留通配（生成代码包，符号表不可得）: ${preservedPkgs.size}`)
for (const p of [...preservedPkgs].sort()) console.log(`  保留 ${p}.*`)
