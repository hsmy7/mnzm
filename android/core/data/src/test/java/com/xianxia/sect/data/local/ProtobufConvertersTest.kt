package com.xianxia.sect.data.local

import com.xianxia.sect.core.model.GameHeavyData
import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.*
import org.junit.Test

/**
 * ProtobufConverters BLOB 解码安全性测试
 *
 * 覆盖 decodeFromBlobInternal 的三层防御：
 * - L1: 分配前 originalSize 上限校验 (MAX_DECOMPRESS_SIZE)
 * - L1: 解压比校验 (MAX_LZ4_DECOMPRESS_RATIO)
 * - L2: 损坏头导致的 OOM 被优雅拒绝（不崩溃）
 */
class ProtobufConvertersTest {

    private val keyPrefix = "test/manualProficiencies"
    private val lz4Available: Boolean by lazy {
        try {
            LZ4Factory.fastestInstance()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun makeRow(key: String, data: ByteArray) =
        GameHeavyData(
            slotId = 1,
            dataKey = GameHeavyData.chunkKey(keyPrefix, key),
            dataValue = data
        )

    // ═══════════════════════════════════════════════════════════
    // 空 / 边界输入
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `decode empty rows returns empty map`() {
        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            emptyList(), keyPrefix
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decode row with empty dataValue returns default`() {
        val row = makeRow("testKey", ByteArray(0))
        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(row), keyPrefix
        )
        assertTrue(result["testKey"].isNullOrEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // L1: 损坏 header — originalSize 超限
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `corrupt header - originalSize zero returns default`() {
        val blob = ByteArray(10)
        blob[0] = 0x01 // BLOB_COMPRESSED_MARKER
        // bytes 1-4 all zero → originalSize = 0

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        assertTrue("zero originalSize should return empty list",
            result["k"].isNullOrEmpty())
    }

    @Test
    fun `corrupt header - originalSize exceeds max returns default`() {
        if (!lz4Available) return

        val blob = ByteArray(10)
        blob[0] = 0x01
        val big = 600_000_000 // 600MB > 500MB MAX_DECOMPRESS_SIZE
        blob[1] = (big shr 24).toByte()
        blob[2] = (big shr 16).toByte()
        blob[3] = (big shr 8).toByte()
        blob[4] = big.toByte()

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        assertTrue("oversized originalSize should return empty list",
            result["k"].isNullOrEmpty())
    }

    @Test
    fun `corrupt header - compression ratio exceeds max returns default`() {
        if (!lz4Available) return

        // originalSize = 1MB, compressed = 10 bytes → ratio 100000x > 25x
        val blob = ByteArray(15)
        blob[0] = 0x01
        val big = 1_000_000
        blob[1] = (big shr 24).toByte()
        blob[2] = (big shr 16).toByte()
        blob[3] = (big shr 8).toByte()
        blob[4] = big.toByte()

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        assertTrue("excessive ratio should return empty list",
            result["k"].isNullOrEmpty())
    }

    @Test
    fun `corrupt header - negative originalSize returns default`() {
        if (!lz4Available) return

        val blob = ByteArray(10)
        blob[0] = 0x01
        blob[1] = 0xFF.toByte()
        blob[2] = 0xFF.toByte()
        blob[3] = 0xFF.toByte()
        blob[4] = 0xFF.toByte() // interpreted as negative

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        assertTrue("negative originalSize should return empty list",
            result["k"].isNullOrEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // 复现线上崩溃场景
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `repro crash - 1GB header bytes rejected without OOM`() {
        if (!lz4Available) return

        val blob = ByteArray(20)
        blob[0] = 0x01
        // 0x41 0x0B 0x2C 0x30 = 1,091,183,728 (线上崩溃的 originalSize)
        blob[1] = 0x41.toByte()
        blob[2] = 0x0B.toByte()
        blob[3] = 0x2C.toByte()
        blob[4] = 0x30.toByte()
        for (i in 5 until 20) blob[i] = i.toByte()

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        // 关键断言: 不应 OOM — L1 校验应优雅拒绝
        assertTrue("1GB header must be rejected without OOM",
            result["k"].isNullOrEmpty())
    }

    @Test
    fun `non-LZ4 data starting with 0x01 marker byte falls back gracefully`() {
        if (!lz4Available) return

        // 0x01 marker 巧合匹配 + 随机 size 字节
        val blob = ByteArray(30)
        blob[0] = 0x01
        blob[1] = 0x12.toByte()
        blob[2] = 0x34.toByte()
        blob[3] = 0x56.toByte()
        blob[4] = 0x78.toByte()
        // 填充不可解压的随机数据
        for (i in 5 until 30) blob[i] = ((i * 37 + 13) % 256).toByte()

        val result = ProtobufConverters.decodeManualProficiencyMapFromRows(
            listOf(makeRow("k", blob)), keyPrefix
        )
        // 应优雅降级：LZ4 解压失败 → 尝试直接 protobuf 解码 → 可能失败 → 返回 default
        // 无论如何不能 OOM
        assertTrue("non-LZ4 data with 0x01 prefix should not OOM",
            result["k"].isNullOrEmpty())
    }
}
