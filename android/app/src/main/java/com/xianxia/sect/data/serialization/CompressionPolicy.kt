package com.xianxia.sect.data.serialization

import com.xianxia.sect.data.serialization.unified.CompressionType

enum class CompressionStrategy {
    LZ4_FAST,
    LZ4_HIGH,
    ZSTD_FAST,
    ZSTD_HIGH,
    AUTO
}

data class CompressionConfig(
    val strategy: CompressionStrategy = CompressionStrategy.AUTO,
    val lz4Threshold: Int = UnifiedSerializationConstants.Compression.LZ4_THRESHOLD,
    val zstdThreshold: Int = UnifiedSerializationConstants.Compression.ZSTD_THRESHOLD,
    val performanceCriticalThreshold: Int = UnifiedSerializationConstants.Compression.PERFORMANCE_CRITICAL_THRESHOLD,
    val enableCompression: Boolean = true
) {
    companion object {
        val DEFAULT = CompressionConfig()
        val PERFORMANCE_CRITICAL = CompressionConfig(
            strategy = CompressionStrategy.LZ4_FAST,
            lz4Threshold = 512,
            zstdThreshold = Int.MAX_VALUE
        )
        val MAX_COMPRESSION = CompressionConfig(
            strategy = CompressionStrategy.ZSTD_HIGH,
            lz4Threshold = 128,
            zstdThreshold = 256
        )
        val NO_COMPRESSION = CompressionConfig(
            enableCompression = false
        )
    }
    
    fun selectAlgorithm(dataSize: Int, isPerformanceCritical: Boolean = false): CompressionType {
        if (!enableCompression || dataSize < lz4Threshold) {
            return CompressionType.LZ4
        }
        
        return when (strategy) {
            CompressionStrategy.LZ4_FAST, CompressionStrategy.LZ4_HIGH -> CompressionType.LZ4
            CompressionStrategy.ZSTD_FAST, CompressionStrategy.ZSTD_HIGH -> CompressionType.ZSTD
            CompressionStrategy.AUTO -> {
                when {
                    isPerformanceCritical -> CompressionType.LZ4
                    dataSize >= zstdThreshold -> CompressionType.ZSTD
                    else -> CompressionType.LZ4
                }
            }
        }
    }
}

object CompressionPolicy {
    fun getRecommendedConfig(dataType: DataType): CompressionConfig {
        return when (dataType) {
            DataType.HOT_DATA -> CompressionConfig(
                strategy = CompressionStrategy.LZ4_FAST,
                lz4Threshold = 512
            )
            DataType.COLD_DATA -> CompressionConfig(
                strategy = CompressionStrategy.ZSTD_FAST,
                lz4Threshold = 256,
                zstdThreshold = 512
            )
            DataType.DELTA -> CompressionConfig(
                strategy = CompressionStrategy.ZSTD_HIGH,
                lz4Threshold = 128,
                zstdThreshold = 256
            )
            DataType.PERFORMANCE_CRITICAL -> CompressionConfig.PERFORMANCE_CRITICAL
        }
    }
}

enum class DataType {
    HOT_DATA,
    COLD_DATA,
    DELTA,
    PERFORMANCE_CRITICAL
}
