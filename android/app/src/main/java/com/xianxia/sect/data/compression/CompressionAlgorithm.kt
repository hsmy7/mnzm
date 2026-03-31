package com.xianxia.sect.data.compression

enum class CompressionAlgorithm(
    val displayName: String,
    val speedRatio: Double,
    val compressionRatio: Double,
    val supportsDictionary: Boolean = false,
    val supportsParallel: Boolean = false
) {
    LZ4("LZ4", 10.0, 2.5),
    ZSTD("Zstandard", 3.0, 3.5, supportsDictionary = true, supportsParallel = true),
    GZIP("GZIP", 1.0, 4.0);

    companion object {
        fun fromName(name: String): CompressionAlgorithm {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: LZ4
        }
        
        fun selectOptimal(dataSize: Int, speedPriority: Double = 0.5): CompressionAlgorithm {
            return when {
                dataSize > 1024 * 1024 -> ZSTD
                dataSize > 64 * 1024 -> if (speedPriority > 0.7) LZ4 else ZSTD
                else -> LZ4
            }
        }
    }
}
