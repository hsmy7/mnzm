package com.xianxia.sect.data.wal

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.concurrent.withLock
import java.security.MessageDigest

// FunctionalWAL 的条目编解码与刷盘域:二进制条目解析/写入原语 + 刷盘编排。
// 跨文件消费类内 internal 字段(同模块,行为零变更);格式常量经文件级别名引用。

private val TAG = FunctionalWAL.TAG
private const val MAGIC_BYTE_1 = FunctionalWAL.MAGIC_BYTE_1
private const val MAGIC_BYTE_2 = FunctionalWAL.MAGIC_BYTE_2
private const val ENTRY_HEADER_SIZE = FunctionalWAL.ENTRY_HEADER_SIZE
private const val CHECKSUM_SIZE = FunctionalWAL.CHECKSUM_SIZE
private const val ENTRY_MIN_SIZE = FunctionalWAL.ENTRY_MIN_SIZE

internal fun sha256(data: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data)
}

/** 从字节数组大端序读取 Long */
internal fun readLong(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 56) or
            ((data[offset + 1].toLong() and 0xFF) shl 48) or
            ((data[offset + 2].toLong() and 0xFF) shl 40) or
            ((data[offset + 3].toLong() and 0xFF) shl 32) or
            ((data[offset + 4].toLong() and 0xFF) shl 24) or
            ((data[offset + 5].toLong() and 0xFF) shl 16) or
            ((data[offset + 6].toLong() and 0xFF) shl 8) or
            (data[offset + 7].toLong() and 0xFF)
}

/** 从字节数组大端序读取 Int */
internal fun readInt(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

/**
 * 解析 WAL 文件中的所有条目。
 * 跳过校验和无效的条目，从 magic 字节重新同步。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun parseEntries(data: ByteArray): List<ParsedEntry> {
    val entries = mutableListOf<ParsedEntry>()
    var offset = 0

    var parseComplete = false
    while (!parseComplete && offset + ENTRY_MIN_SIZE <= data.size) {
        try {
            // 查找 magic 字节：失配时单字节步进重同步
            if (data[offset] != MAGIC_BYTE_1 || data[offset + 1] != MAGIC_BYTE_2) {
                offset++
                continue
            }

            val headerStart = offset

            // 读取 type：非法序号按 3 字节步进重同步
            val typeOrdinal = data[offset + 2].toInt() and 0xFF
            val type = WALEntryType.entries.getOrNull(typeOrdinal)
            // dataLength 仅在 type 合法时读取（头部字段）；非法时走重同步臂
            val dataLength = if (type != null) readInt(data, offset + 23) else -1
            val entryEnd = offset + ENTRY_HEADER_SIZE + dataLength.coerceAtLeast(0) + CHECKSUM_SIZE

            if (type == null || dataLength < 0) {
                offset += 3
            } else if (entryEnd > data.size) {
                // 不完整条目，停止解析
                parseComplete = true
            } else {
                // 读取固定头部字段
                val txnId = readLong(data, offset + 3)
                val slotId = readInt(data, offset + 11)
                val timestamp = readLong(data, offset + 15)

                // 提取 data
                val entryData = data.copyOfRange(
                    offset + ENTRY_HEADER_SIZE,
                    offset + ENTRY_HEADER_SIZE + dataLength
                )

                // 验证校验和
                val storedChecksum = data.copyOfRange(
                    offset + ENTRY_HEADER_SIZE + dataLength,
                    entryEnd
                )
                val computedChecksum = sha256(
                    data.copyOfRange(headerStart, offset + ENTRY_HEADER_SIZE + dataLength)
                )
                val valid = storedChecksum.contentEquals(computedChecksum)

                entries.add(ParsedEntry(type, txnId, slotId, timestamp, entryData, valid))

                offset = entryEnd
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing WAL entry at offset $offset", e)
            offset++
        }
    }

    return entries
}

/**
 * 写入一条 WAL 条目。
 *
 * 格式: [magic(2B)] [type(1B)] [txnId(8B)] [slotId(4B)] [timestamp(8B)] [dataLen(4B)] [data(var)] [checksum(32B)]
 * 校验和覆盖范围: magic 到 data 的全部字节。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.writeEntry(
    type: WALEntryType,
    txnId: Long,
    slotId: Int,
    data: ByteArray = ByteArray(0)
): Boolean {
    writeLock.withLock {
        try {
            val timestamp = System.currentTimeMillis()

            // 构建条目字节 (不含校验和)
            val baos = ByteArrayOutputStream(ENTRY_HEADER_SIZE + data.size)
            val dos = DataOutputStream(baos)
            dos.writeByte(MAGIC_BYTE_1.toInt())
            dos.writeByte(MAGIC_BYTE_2.toInt())
            dos.writeByte(type.ordinal)
            dos.writeLong(txnId)
            dos.writeInt(slotId)
            dos.writeLong(timestamp)
            dos.writeInt(data.size)
            dos.write(data)
            dos.flush()

            val entryBytes = baos.toByteArray()
            val checksum = sha256(entryBytes)

            // 写入缓冲流
            val bos = bufferedOutputStream ?: run {
                Log.e(TAG, "BufferedOutputStream is null, cannot write entry")
                return false
            }
            bos.write(entryBytes)
            bos.write(checksum)

            conditionalFlush(entryBytes.size + CHECKSUM_SIZE)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAL entry: type=$type, txnId=$txnId, slot=$slotId", e)
            return false
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun FunctionalWAL.flushInternal() {
    writeLock.withLock {
        try {
            bufferedOutputStream?.flush()
            pendingBytes.set(0L)
        } catch (e: Exception) {
            Log.e(TAG, "Flush failed", e)
        }
    }
}

internal data class ParsedEntry(
    val type: WALEntryType,
    val txnId: Long,
    val slotId: Int,
    val timestamp: Long,
    val data: ByteArray,
    val valid: Boolean
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 恢复过程中追踪的事务信息 */
internal data class RecoveryTxnInfo(
    val slotId: Int,
    val operation: WALEntryType?,
    val timestamp: Long
)
