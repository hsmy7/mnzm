package com.xianxia.sect.data.config

import com.xianxia.sect.data.prefs.KeyValueStore
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.SerializationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储运行时配置（偏好统一存于 MMKV）。
 *
 * 键名与旧 SharedPreferences 完全一致，旧值一次性迁移（首次访问懒执行，幂等）。
 */
@Singleton
class StorageConfig @Inject constructor(
    private val keyValueStore: KeyValueStore
) {
    @Volatile
    private var migrated = false

    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            keyValueStore.migrateFromSharedPreferences(PREFS_NAME)
            migrated = true
        }
    }

    private fun store(): KeyValueStore {
        ensureMigrated()
        return keyValueStore
    }

    val maxSlots: Int
        get() = store().getInt("max_slots", DEFAULT_MAX_SLOTS)

    val maxBattleLogs: Int
        get() = store().getInt("max_battle_logs", DEFAULT_MAX_BATTLE_LOGS)

    val maxSaveSize: Long
        get() = store().getLong("max_save_size", DEFAULT_MAX_SAVE_SIZE)

    val minMemoryRatio: Float
        get() = store().getFloat("min_memory_ratio", DEFAULT_MIN_MEMORY_RATIO)

    val gzipBufferSize: Int
        get() = store().getInt("gzip_buffer_size", DEFAULT_GZIP_BUFFER_SIZE)

    val maxBackupVersions: Int
        get() = store().getInt("max_backup_versions", DEFAULT_MAX_BACKUP_VERSIONS)

    val autoBackupOnSave: Boolean
        get() = store().getBoolean("auto_backup_on_save", DEFAULT_AUTO_BACKUP_ON_SAVE)

    val enablePreSaveValidation: Boolean
        get() = store().getBoolean("enable_pre_save_validation", DEFAULT_ENABLE_PRE_SAVE_VALIDATION)

    val maxRetryCount: Int
        get() = store().getInt("max_retry_count", DEFAULT_MAX_RETRY_COUNT)

    val retryDelayMs: Long
        get() = store().getLong("retry_delay_ms", DEFAULT_RETRY_DELAY_MS)

    val compactionThreshold: Int
        get() = store().getInt("compaction_threshold", DEFAULT_COMPACTION_THRESHOLD)

    val maxDeltaChainLength: Int
        get() = store().getInt("max_delta_chain_length", DEFAULT_MAX_DELTA_CHAIN_LENGTH)


    val cacheDerivedKey: Boolean
        get() = store().getBoolean("cache_derived_key", DEFAULT_CACHE_DERIVED_KEY)

    val keyCacheDurationMs: Long
        get() = store().getLong("key_cache_duration_ms", DEFAULT_KEY_CACHE_DURATION_MS)

    val updateCacheAfterSave: Boolean
        get() = store().getBoolean("update_cache_after_save", DEFAULT_UPDATE_CACHE_AFTER_SAVE)

    val defaultSerializationFormat: SerializationFormat
        get() = SerializationFormat.valueOf(
            store().getString("serialization_format", DEFAULT_SERIALIZATION_FORMAT.name)
                ?: DEFAULT_SERIALIZATION_FORMAT.name
        )

    val defaultCompressionType: CompressionType
        get() = CompressionType.valueOf(
            store().getString("compression_type", DEFAULT_COMPRESSION_TYPE.name)
                ?: DEFAULT_COMPRESSION_TYPE.name
        )

    fun getQuickSaveContext(): SerializationContext {
        return SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            compressThreshold = 512,
            includeChecksum = true
        )
    }

    fun setMaxBackupVersions(versions: Int) {
        store().putInt("max_backup_versions", versions.coerceIn(1, 20))
    }

    fun setCacheDerivedKey(enabled: Boolean) {
        store().putBoolean("cache_derived_key", enabled)
    }

    fun setUpdateCacheAfterSave(enabled: Boolean) {
        store().putBoolean("update_cache_after_save", enabled)
    }

    fun resetToDefaults() {
        store().clearAll()
    }

    companion object {
        const val PREFS_NAME = "storage_config"
        const val DEFAULT_MAX_SLOTS = 6
        const val DEFAULT_MAX_BATTLE_LOGS = 500
        const val DEFAULT_MAX_SAVE_SIZE = 50L * 1024 * 1024L
        const val DEFAULT_MIN_MEMORY_RATIO = 0.15f
        const val DEFAULT_GZIP_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MAX_BACKUP_VERSIONS = 5
        const val DEFAULT_AUTO_BACKUP_ON_SAVE = true
        const val DEFAULT_ENABLE_PRE_SAVE_VALIDATION = true
        const val DEFAULT_MAX_RETRY_COUNT = 2
        const val DEFAULT_RETRY_DELAY_MS = 100L
        const val DEFAULT_COMPACTION_THRESHOLD = 10
        const val DEFAULT_MAX_DELTA_CHAIN_LENGTH = 50
        const val DEFAULT_CACHE_DERIVED_KEY = true
        const val DEFAULT_KEY_CACHE_DURATION_MS = 300_000L
        const val DEFAULT_UPDATE_CACHE_AFTER_SAVE = true
        val DEFAULT_SERIALIZATION_FORMAT = SerializationFormat.PROTOBUF
        val DEFAULT_COMPRESSION_TYPE = CompressionType.LZ4
    }
}
