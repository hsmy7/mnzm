package com.xianxia.sect.data.compression

data class CompressionConfig(
    val algorithm: CompressionAlgorithm,
    val minSize: Int,
    val maxSize: Int,
    val enableParallelCompression: Boolean = false,
    val compressionLevel: Int = 3
) {
    companion object {
        val DEFAULT = CompressionConfig(
            algorithm = CompressionAlgorithm.LZ4,
            minSize = 512,
            maxSize = 100 * 1024 * 1024
        )
    }

    fun shouldCompress(dataSize: Int): Boolean {
        return dataSize >= minSize && dataSize <= maxSize
    }

    fun validate(): Boolean {
        return minSize > 0 && maxSize > minSize && compressionLevel in 1..22
    }
}

object CompressionStrategy {

    val HOT_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.LZ4,
        minSize = 512,
        maxSize = 10 * 1024 * 1024,
        enableParallelCompression = false
    )

    val WARM_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.LZ4,
        minSize = 256,
        maxSize = 50 * 1024 * 1024,
        enableParallelCompression = true
    )

    val COLD_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.ZSTD,
        minSize = 128,
        maxSize = 100 * 1024 * 1024,
        enableParallelCompression = true,
        compressionLevel = 3
    )

    val ARCHIVE_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.ZSTD,
        minSize = 64,
        maxSize = 200 * 1024 * 1024,
        enableParallelCompression = true,
        compressionLevel = 9
    )

    val NETWORK_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.LZ4,
        minSize = 256,
        maxSize = 10 * 1024 * 1024,
        enableParallelCompression = false
    )

    fun selectStrategy(dataType: DataType): CompressionConfig {
        return when (dataType) {
            DataType.DISCIPLE -> HOT_DATA
            DataType.EQUIPMENT -> HOT_DATA
            DataType.EVENT -> WARM_DATA
            DataType.BATTLE_LOG -> COLD_DATA
            DataType.ARCHIVED -> ARCHIVE_DATA
        }
    }

    fun selectStrategyByAccessFrequency(frequency: AccessFrequency): CompressionConfig {
        return when (frequency) {
            AccessFrequency.HOT -> HOT_DATA
            AccessFrequency.WARM -> WARM_DATA
            AccessFrequency.COLD -> COLD_DATA
            AccessFrequency.ARCHIVE -> ARCHIVE_DATA
        }
    }

    fun selectStrategyBySize(dataSize: Int): CompressionConfig {
        return when {
            dataSize < 10 * 1024 -> HOT_DATA
            dataSize < 1024 * 1024 -> WARM_DATA
            dataSize < 10 * 1024 * 1024 -> COLD_DATA
            else -> ARCHIVE_DATA
        }
    }

    fun selectOptimalAlgorithm(
        dataSize: Int,
        speedPriority: Double = 0.5
    ): CompressionAlgorithm {
        require(speedPriority in 0.0..1.0) { "speedPriority must be between 0.0 and 1.0" }

        val algorithms = CompressionAlgorithm.values()
        val scores = algorithms.map { algo ->
            val speedScore = algo.speedRatio / 10.0
            val compressionScore = algo.compressionRatio / 4.0
            speedPriority * speedScore + (1 - speedPriority) * compressionScore
        }

        val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        return algorithms[maxIndex]
    }
}

enum class AccessFrequency {
    HOT,
    WARM,
    COLD,
    ARCHIVE
}
