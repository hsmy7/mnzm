package com.xianxia.sect.data.compression

import java.util.zip.CRC32

data class CompressedData(
    val data: ByteArray,
    val algorithm: CompressionAlgorithm,
    val originalSize: Int,
    val compressionTime: Long,
    private val checksum: Int = 0
) {
    val compressionRatio: Double
        get() = if (data.isNotEmpty()) originalSize.toDouble() / data.size else 1.0

    val savedBytes: Int
        get() = originalSize - data.size

    val savedPercentage: Double
        get() = if (originalSize > 0) {
            (1.0 - data.size.toDouble() / originalSize) * 100
        } else 0.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CompressedData
        return data.contentEquals(other.data) && algorithm == other.algorithm
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        return result
    }

    override fun toString(): String {
        return "CompressedData(algorithm=$algorithm, originalSize=$originalSize, " +
               "compressedSize=${data.size}, ratio=${"%.2f".format(compressionRatio)}, " +
               "saved=${"%.1f".format(savedPercentage)}%)"
    }

    fun toStorageFormat(): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = algorithm.ordinal.toByte()
        writeInt(header, 1, originalSize)
        writeInt(header, 5, data.size)
        writeLong(header, 9, compressionTime)
        val calculatedChecksum = calculateChecksum(data)
        writeInt(header, 17, calculatedChecksum)
        return header + data
    }

    fun verifyChecksum(): Boolean {
        if (checksum == 0) return true
        return calculateChecksum(data) == checksum
    }

    companion object {
        const val HEADER_SIZE = 21

        fun fromStorageFormat(bytes: ByteArray): CompressedData {
            require(bytes.size >= HEADER_SIZE) { "Invalid compressed data: too short (need at least $HEADER_SIZE bytes)" }

            val algorithmOrdinal = bytes[0].toInt() and 0xFF
            val algorithm = CompressionAlgorithm.values().getOrNull(algorithmOrdinal)
                ?: throw IllegalArgumentException("Unknown compression algorithm: $algorithmOrdinal")
            val originalSize = readInt(bytes, 1)
            val compressedSize = readInt(bytes, 5)
            val compressionTime = readLong(bytes, 9)
            val storedChecksum = readInt(bytes, 17)

            require(originalSize > 0) { "Invalid original size: $originalSize" }
            require(compressedSize > 0) { "Invalid compressed size: $compressedSize" }
            require(bytes.size >= HEADER_SIZE + compressedSize) {
                "Data truncated: expected ${HEADER_SIZE + compressedSize} bytes, got ${bytes.size}"
            }

            val data = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + compressedSize)

            val calculatedChecksum = calculateChecksum(data)
            if (storedChecksum != 0 && calculatedChecksum != storedChecksum) {
                throw IllegalArgumentException("Checksum mismatch: expected $storedChecksum, got $calculatedChecksum")
            }

            return CompressedData(
                data = data,
                algorithm = algorithm,
                originalSize = originalSize,
                compressionTime = compressionTime,
                checksum = storedChecksum
            )
        }

        fun calculateChecksum(data: ByteArray): Int {
            val crc32 = CRC32()
            crc32.update(data)
            return crc32.value.toInt()
        }

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value shr 24).toByte()
            bytes[offset + 1] = (value shr 16).toByte()
            bytes[offset + 2] = (value shr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 0xFF) shl 24) or
                   ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                   ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                   (bytes[offset + 3].toInt() and 0xFF)
        }

        private fun writeLong(bytes: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) {
                bytes[offset + i] = (value shr (56 - i * 8)).toByte()
            }
        }

        private fun readLong(bytes: ByteArray, offset: Int): Long {
            var result: Long = 0
            for (i in 0 until 8) {
                result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return result
        }
    }
}
