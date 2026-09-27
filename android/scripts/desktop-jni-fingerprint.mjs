#!/usr/bin/env node
// 桌面对拍桥「源码-产物同源」指纹（ps1 / sh 构建脚本共用，唯一实现）。
//
// 用法：node android/scripts/desktop-jni-fingerprint.mjs <libgamecorejni.so 绝对路径>
// 产出：<so>.fingerprint —— 由 DiffBridgeSourceSyncGuardTest 校验；
//       桥与 C++ 源码不同源（旧产物复制、改源未重编）时该守卫单点判红并给出重建指令。
//
// 口径（必须与守卫测试逐字一致）：
//   根目录（相对 android/app/src/main/cpp/gamecore）：include / jni-include / third_party / src / jni
//   扩展名：.h .hpp .hh .hxx .inc .c .cc .cpp .cxx
//   相对路径 = POSIX 分隔符、相对 gamecore 目录；按路径做**序数**升序
//   每行哈希 = 文件原始字节的 SHA-256 小写十六进制

import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const SIDECAR_HEADER = 'gamecore-jni-source-sync v1'
export const FINGERPRINT_ROOTS = ['include', 'jni-include', 'third_party', 'src', 'jni']
export const FINGERPRINT_EXTENSIONS = [
    '.h', '.hpp', '.hh', '.hxx', '.inc', '.c', '.cc', '.cpp', '.cxx'
]

/** gamecore 源码根（仓库根/android/app/src/main/cpp/gamecore） */
export function gamecoreDir(repoRoot) {
    return join(repoRoot, 'android', 'app', 'src', 'main', 'cpp', 'gamecore')
}

/** 枚举口径内的全部源文件，返回按相对路径序数升序的 [{ relPath, absPath }] */
export function enumerateFingerprintFiles(gamecore) {
    const files = []
    for (const root of FINGERPRINT_ROOTS) {
        const rootDir = join(gamecore, root)
        collect(rootDir, root, files)
    }
    files.sort((a, b) => (a.relPath < b.relPath ? -1 : a.relPath > b.relPath ? 1 : 0))
    return files
}

function collect(dir, relDir, out) {
    let entries
    try {
        entries = readdirSync(dir, { withFileTypes: true })
    } catch {
        return // 根目录可缺省（例如无 jni-include 的变体树）
    }
    for (const entry of entries) {
        const abs = join(dir, entry.name)
        const rel = `${relDir}/${entry.name}`
        if (entry.isDirectory()) {
            collect(abs, rel, out)
        } else if (entry.isFile() && FINGERPRINT_EXTENSIONS.some((ext) => entry.name.endsWith(ext))) {
            out.push({ relPath: rel, absPath: abs })
        }
    }
}

export function sha256File(absPath) {
    return createHash('sha256').update(readFileSync(absPath)).digest('hex')
}

/** 构造指纹旁挂文件正文（LF 结尾） */
export function buildSidecarText(gamecore) {
    const files = enumerateFingerprintFiles(gamecore)
    const lines = [SIDECAR_HEADER, `root android/app/src/main/cpp/gamecore`, `count ${files.length}`]
    for (const { relPath, absPath } of files) {
        lines.push(`file ${sha256File(absPath)} ${relPath}`)
    }
    return { text: lines.join('\n') + '\n', count: files.length }
}

function main() {
    const soPath = process.argv[2]
    if (!soPath) {
        console.error('用法: node android/scripts/desktop-jni-fingerprint.mjs <libgamecorejni.so 绝对路径>')
        process.exit(2)
    }
    const soAbs = resolve(soPath)
    if (!statSync(soAbs, { throwIfNoEntry: false })?.isFile()) {
        console.error(`桌面对拍桥产物不存在：${soAbs}`)
        process.exit(2)
    }
    const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
    const gamecore = gamecoreDir(repoRoot)
    const { text, count } = buildSidecarText(gamecore)
    const sidecar = `${soAbs}.fingerprint`
    writeFileSync(sidecar, text, 'utf8')
    console.log(`桌面 JNI 同源指纹: ${count} 个源文件 -> ${sidecar}`)
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
    main()
}
