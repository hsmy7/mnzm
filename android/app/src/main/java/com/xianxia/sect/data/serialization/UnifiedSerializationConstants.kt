package com.xianxia.sect.data.serialization

object UnifiedSerializationConstants {
    const val MAGIC_HEADER: Short = 0x5853
    const val FORMAT_VERSION: Byte = 3
    const val HEADER_SIZE = 8
    const val CHECKSUM_SIZE = 32
    const val TYPE_NAME_MAX_LENGTH = 256
    const val MAX_DATA_SIZE = 200 * 1024 * 1024
    
    object Compression {
        const val LZ4_THRESHOLD = 256
        const val ZSTD_THRESHOLD = 1024
        const val PERFORMANCE_CRITICAL_THRESHOLD = 512
    }
    
    object Cache {
        const val MAGIC_HEADER: Short = 0x4350
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_SIZE = 12
    }
    
    object DiskCache {
        const val MAGIC_HEADER: Short = 0x4443
        const val FORMAT_VERSION: Byte = 1
    }
    
    object SmartCache {
        const val MAGIC_HEADER: Short = 0x534D
        const val FORMAT_VERSION: Byte = 1
    }
    
    object TieredCache {
        const val MAGIC_HEADER: Short = 0x4C33
        const val FORMAT_VERSION: Byte = 1
    }
    
    object Delta {
        const val MAGIC_HEADER: Short = 0x444C
        const val FORMAT_VERSION: Byte = 2
    }
    
    fun isValidMagicHeader(magic: Short): Boolean {
        return magic == MAGIC_HEADER ||
               magic == Cache.MAGIC_HEADER ||
               magic == DiskCache.MAGIC_HEADER ||
               magic == SmartCache.MAGIC_HEADER ||
               magic == TieredCache.MAGIC_HEADER ||
               magic == Delta.MAGIC_HEADER
    }
    
    fun getFormatName(magic: Short): String {
        return when (magic) {
            MAGIC_HEADER -> "UnifiedSerialization"
            Cache.MAGIC_HEADER -> "CacheSerialization"
            DiskCache.MAGIC_HEADER -> "DiskCache"
            SmartCache.MAGIC_HEADER -> "SmartCache"
            TieredCache.MAGIC_HEADER -> "TieredCache"
            Delta.MAGIC_HEADER -> "Delta"
            else -> "Unknown"
        }
    }
}
