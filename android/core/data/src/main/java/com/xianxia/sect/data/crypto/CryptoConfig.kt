package com.xianxia.sect.data.crypto

import java.util.concurrent.TimeUnit

/**
 * 加密版本枚举 — 用于密钥版本化、未来平滑迁移和算法升级。
 *
 * 版本演进路线：
 * - V1 (0x01): 已废弃 - 初始 PBKDF2 版本（无版本头）
 * - V2 (0x02): 当前生产 - Argon2id (AES-GCM)
 * - V3 (0x03): 预留 - XChaCha20-Poly1305 (2025+ 趋势)
 */
enum class KeyVersion(val byteValue: Byte, val description: String) {
    /** 初始 PBKDF2 版本，当前仅用于解密历史存档 */
    PBKDF2_AES_CBC(0x01, "PBKDF2-AES-CBC"),
    /** 当前生产版本：Argon2id 密钥派生 + AES-GCM 加密 */
    ARGON2ID_AES_GCM(0x02, "Argon2id-AES-GCM"),
    /** 预留：未来 XChaCha20-Poly1305 */
    XCHACHA20_POLY1305(0x03, "XChaCha20-Poly1305 (预留)");

    companion object {
        private val BYTE_MAP = entries.associateBy { it.byteValue }

        fun fromByte(byteValue: Byte): KeyVersion? = BYTE_MAP[byteValue]

        /** 默认激活版本 */
        val DEFAULT = ARGON2ID_AES_GCM
    }
}

/**
 * 加密常量配置
 */
object CryptoConstants {
    /** 标签 */
    const val TAG = "SaveCrypto"

    // ==================== 密钥版本化系统 ====================

    /** 活跃版本（可运行时切换，用于密钥轮换测试） */
    @Volatile
    var activeVersion: KeyVersion = KeyVersion.DEFAULT

    /** 所有支持的版本（解密时按此列表顺序尝试） */
    val supportedVersions = setOf(
        KeyVersion.PBKDF2_AES_CBC,
        KeyVersion.ARGON2ID_AES_GCM,
        KeyVersion.XCHACHA20_POLY1305
    )

    // ==================== PBKDF2 参数 ====================

    /** PBKDF2 迭代次数（当前标准，加�?解密 / Argon2id API<30 fallback） */
    const val PBKDF2_ITERATIONS = 310_000

    /** PBKDF2 历史版本迭代次数（用于兼容解密旧存档） */
    const val PBKDF2_LEGACY_ITERATIONS = 60_000

    /** PBKDF2 密钥长度 (bits) */
    const val PBKDF2_KEY_LENGTH = 256

    // ==================== Argon2id 参数 ====================

    /** Argon2id 内存用量 (KiB) — OWASP 2025 推荐最小值 */
    const val ARGON2ID_MEMORY_KIB_DEFAULT = 65536

    /** Argon2id 迭代次数 */
    const val ARGON2ID_ITERATIONS = 3

    /** Argon2id 并行度 */
    const val ARGON2ID_PARALLELISM = 2

    /** Argon2id 输出密钥长度 */
    const val ARGON2ID_KEY_LENGTH = 32

    // ==================== AES-GCM 参数 ====================

    /** AES-GCM nonce 长度 (96 bits, NIST 推荐) */
    const val GCM_NONCE_LENGTH = 12

    /** AES-GCM 认证标签长度 (128 bits) */
    const val GCM_TAG_LENGTH = 16

    /** AES 密钥长度 (256 bits) */
    const val AES_KEY_LENGTH = 32

    // ==================== 版本头常量 ====================

    /** 版本头字节长度 */
    const val VERSION_HEADER_LENGTH = 1

    /** 版本头中 Argon2id salt 长度 */
    const val VERSION_ARGON2ID_SALT_LENGTH = 16

    /** 版本头中 Argon2id 内存参数长度 */
    const val VERSION_ARGON2ID_MEMORY_LENGTH = 4

    /** 版本头数据区起始位置（版本字节后） */
    const val VERSION_HEADER_DATA_START = 1

    /** Argon2id 版本字节 */
    val VERSION_ARGON2ID: Byte = KeyVersion.ARGON2ID_AES_GCM.byteValue

    // ==================== 版本头结构定义 ====================

    /** V2 Argon2id 版本头长度: 版本(1) + salt(16) + 内存(4) = 21 */
    const val V2_HEADER_LENGTH = VERSION_HEADER_LENGTH + VERSION_ARGON2ID_SALT_LENGTH + VERSION_ARGON2ID_MEMORY_LENGTH

    /** V1 PBKDF2 版本头长度: 版本(1) + salt(16) = 17 */
    const val V1_HEADER_LENGTH = VERSION_HEADER_LENGTH + 16

    // ==================== HMAC 参数 ====================

    /** HMAC 密钥长度 */
    const val HMAC_KEY_LENGTH = 32

    /** HMAC 输出长度 */
    const val HMAC_OUTPUT_LENGTH = 32

    // ==================== 硬件密钥存储参数 ====================

    /** Android KeyStore 别名前缀 */
    const val KEYSTORE_ALIAS_PREFIX = "xianxia_sect_key_"

    // ==================== 缓存配置 ====================

    /** 缓存 TTL (毫秒) */
    val UNIFIED_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)

    /** 缓存清理间隔 (毫秒) */
    val CACHE_CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    // ==================== 编码配置 ====================

    const val SALT_LENGTH = 16
}
