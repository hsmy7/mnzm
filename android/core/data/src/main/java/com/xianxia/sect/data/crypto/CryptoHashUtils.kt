package com.xianxia.sect.data.crypto

import java.security.MessageDigest

/**
 * 加密哈希与校验和工具
 * 从 SaveCrypto.kt 提取的无状态纯函数
 */
object CryptoHashUtils {

    /**
     * 计算 SHA-256 哈希
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * 计算 SHA-256 哈希并返回十六进制字符串
     */
    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * 生成数据的校验和（SHA-256）
     */
    fun generateChecksum(data: ByteArray): ByteArray {
        return sha256(data)
    }

    /**
     * 验证校验和是否匹配（时序安全比较）
     */
    fun verifyChecksum(data: ByteArray, expectedChecksum: ByteArray): Boolean {
        val actualChecksum = sha256(data)
        return timingSafeEqual(actualChecksum, expectedChecksum)
    }

    /**
     * 将校验和嵌入数据头部
     */
    fun embedChecksum(data: ByteArray): ByteArray {
        val checksum = sha256(data)
        val result = ByteArray(checksum.size + data.size)
        System.arraycopy(checksum, 0, result, 0, checksum.size)
        System.arraycopy(data, 0, result, checksum.size, data.size)
        return result
    }

    /**
     * 从嵌入数据中提取校验和与数据
     */
    fun extractChecksumAndData(embeddedData: ByteArray): Pair<ByteArray, ByteArray>? {
        if (embeddedData.size < 32) return null
        val checksum = embeddedData.copyOfRange(0, 32)
        val data = embeddedData.copyOfRange(32, embeddedData.size)
        return Pair(checksum, data)
    }

    /**
     * 验证嵌入的校验和是否有效
     */
    fun verifyEmbeddedChecksum(embeddedData: ByteArray): Boolean {
        val (storedChecksum, data) = extractChecksumAndData(embeddedData) ?: return false
        val actualChecksum = sha256(data)
        return timingSafeEqual(storedChecksum, actualChecksum)
    }
}
