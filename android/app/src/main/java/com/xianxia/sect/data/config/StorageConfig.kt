package com.xianxia.sect.data.config

import android.content.Context
import android.content.SharedPreferences
import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.unified.SerializationContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("storage_config", Context.MODE_PRIVATE)
    }

    val maxSlots: Int
        get() = prefs.getInt("max_slots", DEFAULT_MAX_SLOTS)
    
    val maxBattleLogs: Int
        get() = prefs.getInt("max_battle_logs", DEFAULT_MAX_BATTLE_LOGS)
    
    val maxGameEvents: Int
        get() = prefs.getInt("max_game_events", DEFAULT_MAX_GAME_EVENTS)
    
    val maxSaveSize: Long
        get() = prefs.getLong("max_save_size", DEFAULT_MAX_SAVE_SIZE)
    
    val minMemoryRatio: Float
        get() = prefs.getFloat("min_memory_ratio", DEFAULT_MIN_MEMORY_RATIO)
    
    val gzipBufferSize: Int
        get() = prefs.getInt("gzip_buffer_size", DEFAULT_GZIP_BUFFER_SIZE)
    
    val maxBackupVersions: Int
        get() = prefs.getInt("max_backup_versions", DEFAULT_MAX_BACKUP_VERSIONS)
    
    val autoSaveIntervalMonths: Int
        get() = prefs.getInt("auto_save_interval_months", DEFAULT_AUTO_SAVE_INTERVAL_MONTHS)
    
    val maxRetryCount: Int
        get() = prefs.getInt("max_retry_count", DEFAULT_MAX_RETRY_COUNT)
    
    val retryDelayMs: Long
        get() = prefs.getLong("retry_delay_ms", DEFAULT_RETRY_DELAY_MS)
    
    val compactionThreshold: Int
        get() = prefs.getInt("compaction_threshold", DEFAULT_COMPACTION_THRESHOLD)
    
    val maxDeltaChainLength: Int
        get() = prefs.getInt("max_delta_chain_length", DEFAULT_MAX_DELTA_CHAIN_LENGTH)
    
    val forceFullSaveInterval: Int
        get() = prefs.getInt("force_full_save_interval", DEFAULT_FORCE_FULL_SAVE_INTERVAL)
    
    val incrementalSaveThreshold: Int
        get() = prefs.getInt("incremental_save_threshold", DEFAULT_INCREMENTAL_SAVE_THRESHOLD)
    
    val maxDisciples: Int
        get() = prefs.getInt("max_disciples", DEFAULT_MAX_DISCIPLES)
    
    val cacheDerivedKey: Boolean
        get() = prefs.getBoolean("cache_derived_key", DEFAULT_CACHE_DERIVED_KEY)
    
    val keyCacheDurationMs: Long
        get() = prefs.getLong("key_cache_duration_ms", DEFAULT_KEY_CACHE_DURATION_MS)
    
    val updateCacheAfterSave: Boolean
        get() = prefs.getBoolean("update_cache_after_save", DEFAULT_UPDATE_CACHE_AFTER_SAVE)
    
    val defaultSerializationFormat: SerializationFormat
        get() = SerializationFormat.valueOf(
            prefs.getString("serialization_format", DEFAULT_SERIALIZATION_FORMAT.name) ?: DEFAULT_SERIALIZATION_FORMAT.name
        )
    
    val defaultCompressionType: CompressionType
        get() = CompressionType.valueOf(
            prefs.getString("compression_type", DEFAULT_COMPRESSION_TYPE.name) ?: DEFAULT_COMPRESSION_TYPE.name
        )

    fun getAutoSaveContext(): SerializationContext {
        return SerializationContext(
            format = defaultSerializationFormat,
            compression = defaultCompressionType,
            compressThreshold = 512,
            includeChecksum = true
        )
    }

    fun getQuickSaveContext(): SerializationContext {
        return SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            compressThreshold = 512,
            includeChecksum = true
        )
    }

    fun setMaxBackupVersions(versions: Int) {
        prefs.edit().putInt("max_backup_versions", versions.coerceIn(1, 20)).apply()
    }

    fun setIncrementalSaveThreshold(threshold: Int) {
        prefs.edit().putInt("incremental_save_threshold", threshold.coerceIn(1, 50)).apply()
    }

    fun setCacheDerivedKey(enabled: Boolean) {
        prefs.edit().putBoolean("cache_derived_key", enabled).apply()
    }

    fun setUpdateCacheAfterSave(enabled: Boolean) {
        prefs.edit().putBoolean("update_cache_after_save", enabled).apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_MAX_SLOTS = 10
        const val DEFAULT_MAX_BATTLE_LOGS = 500
        const val DEFAULT_MAX_GAME_EVENTS = 1000
        const val DEFAULT_MAX_SAVE_SIZE = 50L * 1024 * 1024L
        const val DEFAULT_MIN_MEMORY_RATIO = 0.15f
        const val DEFAULT_GZIP_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MAX_BACKUP_VERSIONS = 5
        const val DEFAULT_AUTO_SAVE_INTERVAL_MONTHS = 3
        const val DEFAULT_MAX_RETRY_COUNT = 2
        const val DEFAULT_RETRY_DELAY_MS = 100L
        const val DEFAULT_COMPACTION_THRESHOLD = 10
        const val DEFAULT_MAX_DELTA_CHAIN_LENGTH = 50
        const val DEFAULT_FORCE_FULL_SAVE_INTERVAL = 20
        const val DEFAULT_INCREMENTAL_SAVE_THRESHOLD = 10
        const val DEFAULT_MAX_DISCIPLES = 1000
        const val DEFAULT_CACHE_DERIVED_KEY = true
        const val DEFAULT_KEY_CACHE_DURATION_MS = 300_000L
        const val DEFAULT_UPDATE_CACHE_AFTER_SAVE = true
        val DEFAULT_SERIALIZATION_FORMAT = SerializationFormat.PROTOBUF
        val DEFAULT_COMPRESSION_TYPE = CompressionType.LZ4
    }
}
