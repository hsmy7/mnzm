package com.xianxia.sect.data.crypto

// SaveCrypto 的摘要/校验和域:SHA-256 与内嵌校验和编解码头——CryptoHashUtils 的
// 门面转发(无状态纯函数)。

object SaveCryptoDigest {

/**
 * 计算 SHA-256 哈希
 */
fun sha256(data: ByteArray): ByteArray = CryptoHashUtils.sha256(data)

/**
 * 计算 SHA-256 哈希并返回十六进制字符串
 */
fun sha256Hex(data: ByteArray): String = CryptoHashUtils.sha256Hex(data)

/**
 * 生成数据的校验和（SHA-256）
 */
fun generateChecksum(data: ByteArray): ByteArray = CryptoHashUtils.generateChecksum(data)

/**
 * 验证校验和是否匹配（时序安全比较）
 */
fun verifyChecksum(data: ByteArray, expectedChecksum: ByteArray): Boolean =
    CryptoHashUtils.verifyChecksum(data, expectedChecksum)

/**
 * 将校验和嵌入数据头部
 */
fun embedChecksum(data: ByteArray): ByteArray = CryptoHashUtils.embedChecksum(data)

/**
 * 从嵌入数据中提取校验和与数据
 */
fun extractChecksumAndData(embeddedData: ByteArray): Pair<ByteArray, ByteArray>? =
    CryptoHashUtils.extractChecksumAndData(embeddedData)

/**
 * 验证嵌入的校验和是否有效
 */
fun verifyEmbeddedChecksum(embeddedData: ByteArray): Boolean =
    CryptoHashUtils.verifyEmbeddedChecksum(embeddedData)
}
