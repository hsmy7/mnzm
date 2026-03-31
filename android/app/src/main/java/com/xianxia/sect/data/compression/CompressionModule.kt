package com.xianxia.sect.data.compression

import android.content.Context
import com.tencent.mmkv.MMKV
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CompressionModule {

    @Provides
    @Singleton
    fun provideDataCompressor(): DataCompressor {
        return DataCompressor(CompressionAlgorithm.LZ4)
    }

    @Provides
    @Singleton
    fun provideCompressionManager(compressor: DataCompressor): CompressionManager {
        return CompressionManager(compressor)
    }

    @Provides
    @Singleton
    fun provideCompressedCache(@ApplicationContext context: Context): CompressedCache {
        MMKV.initialize(context)
        val mmkv = MMKV.mmkvWithID("compression_cache", MMKV.SINGLE_PROCESS_MODE)
        return CompressedCache(mmkv)
    }
}

class CompressedCache(private val mmkv: MMKV) {

    companion object {
        private const val KEY_PREFIX = "comp_"
        private const val META_PREFIX = "meta_"
        private const val MAX_CACHE_ENTRIES = 100
    }

    fun put(key: String, compressedData: CompressedData) {
        val storageKey = KEY_PREFIX + key
        val metaKey = META_PREFIX + key

        mmkv.encode(storageKey, compressedData.toStorageFormat())
        mmkv.encode(metaKey, System.currentTimeMillis())
    }

    fun get(key: String): CompressedData? {
        val storageKey = KEY_PREFIX + key
        val bytes = mmkv.decodeBytes(storageKey) ?: return null
        return CompressedData.fromStorageFormat(bytes)
    }

    fun contains(key: String): Boolean {
        return mmkv.contains(KEY_PREFIX + key)
    }

    fun remove(key: String) {
        mmkv.remove(KEY_PREFIX + key)
        mmkv.remove(META_PREFIX + key)
    }

    fun clear() {
        mmkv.clearAll()
    }

    fun getCacheSize(): Long {
        return mmkv.totalSize()
    }

    fun getEntryCount(): Int {
        var count = 0
        val allKeys = mmkv.allKeys() ?: return 0
        for (key in allKeys) {
            if (key.startsWith(KEY_PREFIX)) {
                count++
            }
        }
        return count
    }

    fun evictOldest(count: Int = MAX_CACHE_ENTRIES / 4) {
        val allKeys = mmkv.allKeys() ?: return
        val metaEntries = mutableListOf<Pair<String, Long>>()

        for (key in allKeys) {
            if (key.startsWith(META_PREFIX)) {
                val timestamp = mmkv.decodeLong(key, 0)
                metaEntries.add(Pair(key.removePrefix(META_PREFIX), timestamp))
            }
        }

        metaEntries.sortBy { it.second }

        for (i in 0 until minOf(count, metaEntries.size)) {
            remove(metaEntries[i].first)
        }
    }

    fun getStats(): CacheStats {
        val entryCount = getEntryCount()
        val totalSize = getCacheSize()
        return CacheStats(
            entryCount = entryCount,
            totalSize = totalSize,
            averageEntrySize = if (entryCount > 0) totalSize / entryCount else 0
        )
    }
}

data class CacheStats(
    val entryCount: Int,
    val totalSize: Long,
    val averageEntrySize: Long
)
