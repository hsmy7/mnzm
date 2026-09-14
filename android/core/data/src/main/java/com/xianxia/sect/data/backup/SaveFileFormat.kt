package com.xianxia.sect.data.backup

import java.util.zip.CRC32

// SaveFileManager 的文件格式原语层：魔数/版本常量 + 文件头构建 + CRC 校验族。
// 全部为无状态纯函数（SaveFileFormat），与槽位文件 IO 编排（SaveFileManager）分文件承载。

/** Magic bytes: "XSBK" (Xianxia Sect BaKup) */
internal val MAGIC = byteArrayOf(0x58, 0x53, 0x42, 0x4B)

/** 文件头总长度：16 字节 */
internal const val HEADER_SIZE = 16

/** 标记位：LZ4 压缩 */
internal const val FLAG_COMPRESSED = 0x01

/** CRC 算法标识：0=CRC32 */
internal const val CRC_ALGO_CRC32 = 0

/** CRC 算法标识：1=CRC32C */
internal const val CRC_ALGO_CRC32C = 1

/** 格式版本 (major << 8 | minor)——0x0101 起字节 11 携带 CRC 算法标识 */
internal const val FORMAT_VERSION = 0x0101

/** 旧格式版本（0x0100，无 CRC 算法标识） */
internal const val FORMAT_VERSION_LEGACY = 0x0100

/** 首个携带算法标识的格式版本（低于此版本无标识，读取走双算法探测） */
internal const val FORMAT_VERSION_WITH_CRC_ALGO = 0x0101

/** 构建文件头（0x0101：字节 11 记录 CRC 算法标识） */
internal fun buildHeader(payload: ByteArray): ByteArray {
    // 恒写 CRC32C（自实现查表，全 API 可算）——java.util.zip.CRC32C 仅 API 34+ 存在，
    // 自实现保证 API<34 设备也能校验文件；恒 CRC32C + algo 标识双向一致。
    val crc = computeCrc32c(payload)
    val header = ByteArray(HEADER_SIZE)

    // Magic
    System.arraycopy(MAGIC, 0, header, 0, 4)
    // Format version (big-endian)
    header[4] = ((FORMAT_VERSION shr 8) and 0xFF).toByte()
    header[5] = (FORMAT_VERSION and 0xFF).toByte()
    // CRC (big-endian)
    header[6] = ((crc shr 24) and 0xFF).toByte()
    header[7] = ((crc shr 16) and 0xFF).toByte()
    header[8] = ((crc shr 8) and 0xFF).toByte()
    header[9] = (crc and 0xFF).toByte()
    // Flags: LZ4 compressed
    header[10] = FLAG_COMPRESSED.toByte()
    // CRC 算法标识（0x0101 起有效，0=CRC32, 1=CRC32C）——恒 CRC32C
    header[11] = CRC_ALGO_CRC32C.toByte()
    // Uncompressed length (big-endian) — 当前 payload 已压缩，存原始长度
    val len = payload.size
    header[12] = ((len shr 24) and 0xFF).toByte()
    header[13] = ((len shr 16) and 0xFF).toByte()
    header[14] = ((len shr 8) and 0xFF).toByte()
    header[15] = (len and 0xFF).toByte()

    return header
}

/**
 * CRC 校验。
 * - 0x0101+：按文件头记录的算法标识精确校验；未知标识判损坏
 * - 0x0100（旧格式）：无算法标识 → 双算法探测（CRC32C 自实现全 API 可算，无 SDK 分支）
 */
internal fun verifyCrc(stored: Int, payload: ByteArray, formatVersion: Int, algoByte: Int): Boolean {
    if (formatVersion >= FORMAT_VERSION_WITH_CRC_ALGO) {
        return when (algoByte) {
            CRC_ALGO_CRC32 -> stored == computeCrc32(payload)
            CRC_ALGO_CRC32C -> stored == computeCrc32c(payload)
            else -> false
        }
    }
    return stored == computeCrc32c(payload) || stored == computeCrc32(payload)
}

/** 计算 CRC32 校验和（跨 API 一致） */
internal fun computeCrc32(data: ByteArray): Int {
    val crc = CRC32()
    crc.update(data)
    return crc.value.toInt()
}

/** 计算 CRC32C 校验和（自实现查表，全 API 一致） */
internal fun computeCrc32c(data: ByteArray): Int = Crc32c.update(data)

/**
 * 自实现 CRC32C（Castagnoli 多项式，reflected 0x82F63B78）。
 *
 * java.util.zip.CRC32C 仅 API 34+ 存在，API<34 设备无法直接使用。
 * 纯 Java 查表实现与 java.util.zip.CRC32C 输出一致（Castagnoli 标准），全 API 可用。
 */
private object Crc32c {
    private val TABLE = IntArray(256).also { table ->
        val reflectedPoly = 0x82F63B78.toInt() // > Int.MAX 的字面量需显式转换
        for (i in 0..255) {
            var crc = i
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor reflectedPoly else crc ushr 1
            }
            table[i] = crc
        }
    }

    /** 计算字节数组的 CRC32C 校验值（与 java.util.zip.CRC32C 输出一致） */
    fun update(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = (crc ushr 8) xor TABLE[(crc xor b.toInt()) and 0xFF]
        }
        return crc.inv()
    }
}
