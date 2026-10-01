package com.xianxia.sect.core.util

import java.security.MessageDigest

/**
 * 账号数据空间隔离键（accountKey）的派生与校验——纯函数，零 Android 依赖。
 *
 * accountKey = 账号标识经 SHA-256 后截断的十六进制串。**不落明文标识到文件名**：
 * 目录名会出现在系统备份、崩溃日志与文件管理器中，明文账号标识属不必要暴露。
 *
 * ## iOS 对等实现（rules/code-quality.md §1.5）
 *
 * 双端必须产出同一 accountKey（换设备续玩时空间可对账）：
 *
 * ```swift
 * import CryptoKit
 * func accountKey(_ identifier: String) -> String {
 *     let digest = SHA256.hash(data: Data(identifier.utf8))
 *     return digest.map { String(format: "%02x", $0) }.joined().prefix(16).lowercased()
 * }
 * ```
 *
 * 即 UTF-8 编码 → SHA-256 → 小写十六进制 → 截断前 [KEY_HEX_CHARS] 位，与 Kotlin 侧完全一致。
 */
object AccountKey {

    /** accountKey 十六进制长度（SHA-256 截断；64 bit 抗碰撞对单设备账号数远超充足） */
    const val KEY_HEX_CHARS = 16

    /**
     * 从账号标识派生 accountKey。
     *
     * @param identifier 账号唯一标识（登录 SDK 的 unionId 等）；调用方保证非空白——
     *   空白标识属于登录链路的程序错误，按 fail-fast 抛出而非静默派生。
     * @return 十六进制小写、长度 [KEY_HEX_CHARS] 的文件安全串
     * @throws IllegalArgumentException identifier 空白（程序错误，不得进入建空间流程）
     */
    fun derive(identifier: String): String {
        require(identifier.isNotBlank()) {
            "账号标识为空白，禁止派生 accountKey（无账号不得建数据空间）"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identifier.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return hex.take(KEY_HEX_CHARS)
    }

    /** accountKey 合法性校验：非空、长度 [KEY_HEX_CHARS]、纯小写十六进制 */
    fun isValid(key: String): Boolean =
        key.length == KEY_HEX_CHARS && key.all { it.isDigit() || it in 'a'..'f' }
}
