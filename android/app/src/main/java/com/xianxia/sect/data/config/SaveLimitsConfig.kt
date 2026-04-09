package com.xianxia.sect.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存档限制集中配置管理器。
 *
 * 统一管理所有与存档大小、数量、归档策略相关的运行时可调参数。
 * 所有值均通过 SharedPreferences 持久化，支持运行时热更新。
 * 配置变更会自动约束在 [minValue, maxValue] 范围内。
 */
@Singleton
class SaveLimitsConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("save_limits_config", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "SaveLimitsConfig"

        // ==================== SharedPreferences Key 常量 ====================

        const val KEY_MAX_SAVE_SIZE = "max_save_size"
        const val KEY_MAX_BATTLE_LOGS = "max_battle_logs"
        const val KEY_MAX_GAME_EVENTS = "max_game_events"
        const val KEY_TOTAL_STORAGE_QUOTA = "total_storage_quota"
        const val KEY_SHARDING_THRESHOLD = "sharding_threshold"
        const val KEY_SHARD_MAX_SIZE = "shard_max_size"
        const val KEY_DYNAMIC_QUOTA_ENABLED = "dynamic_quota_enabled"

        // ==================== 单个存档大小 ====================

        /** 默认单个存档上限：100 MB */
        const val DEFAULT_MAX_SAVE_SIZE_MB = 100L

        /** 单个存档绝对上限：200 MB（防止误配导致 OOM） */
        const val ABSOLUTE_MAX_SAVE_SIZE_MB = 200L

        // ==================== BattleLog 限制 ====================

        /** 默认主存档中保留的 BattleLog 最大条数 */
        const val DEFAULT_MAX_BATTLE_LOGS = 1000

        /** BattleLog 绝对上限 */
        const val ABSOLUTE_MAX_BATTLE_LOGS = 5000

        // ==================== GameEvent 限制 ====================

        /** 默认主存档中保留的 GameEvent 最大条数 */
        const val DEFAULT_MAX_GAME_EVENTS = 2000

        /** GameEvent 绝对上限 */
        const val ABSOLUTE_MAX_GAME_EVENTS = 10_000

        // ==================== 总存储配额 ====================

        /** 默认总存储配额上限：1024 MB (1 GB) */
        const val DEFAULT_TOTAL_STORAGE_QUOTA_MB = 1024L

        /** 总存储绝对上限：2048 MB (2 GB) */
        const val ABSOLUTE_MAX_TOTAL_STORAGE_MB = 2048L

        // ==================== 分片降级阈值 ====================

        /** 触发分片降级的存档大小阈值（MB），超过此值启用分片写入 */
        const val DEFAULT_SHARDING_THRESHOLD_MB = 80L

        /** 单个分片的最大大小（MB） */
        const val DEFAULT_SHARD_MAX_SIZE_MB = 50L

        // ==================== 动态配额调整 - 游戏时长 ====================

        /** 短期游戏（< 6 游戏月）：不额外增加配额 */
        const val GAME_PHASE_SHORT_MONTHS = 6

        /** 中期游戏（6-24 游戏月）：配额倍率 */
        const val GAME_PHASE_MEDIUM_MULTIPLIER = 1.2f

        /** 长期游戏（> 24 游戏月）：配额倍率 */
        const val GAME_PHASE_LONG_MULTIPLIER = 1.5f

        /** 长期游戏的起始游戏月数 */
        const val GAME_PHASE_LONG_MONTHS = 24

        // ==================== 自动清理策略 ====================

        /** 重要数据保护天数（自动清理时不删除此时间内的数据） */
        const val IMPORTANT_DATA_PROTECTION_DAYS = 30

        /** 备份文件默认保留天数 */
        const val BACKUP_RETENTION_DAYS = 90

        /** WAL 快照默认保留小时数 */
        const val WAL_SNAPSHOT_RETENTION_HOURS = 24
    }

    // ==================== 公开属性：存档大小限制 ====================

    /**
     * 单个存档的最大允许大小（字节）。
     * 运行时可通过 [setMaxSaveSizeMb] 调整。
     */
    val maxSaveSizeBytes: Long
        get() = prefs.getLong(
            KEY_MAX_SAVE_SIZE,
            DEFAULT_MAX_SAVE_SIZE_MB * 1024 * 1024
        ).coerceIn(1L * 1024 * 1024, ABSOLUTE_MAX_SAVE_SIZE_MB * 1024 * 1024)

    /**
     * 主存档中保留的 BattleLog 最大条数。
     * 超出部分通过 DataArchiver 归档到磁盘。
     */
    val maxBattleLogs: Int
        get() = prefs.getInt(KEY_MAX_BATTLE_LOGS, DEFAULT_MAX_BATTLE_LOGS)
            .coerceIn(100, ABSOLUTE_MAX_BATTLE_LOGS)

    /**
     * 主存档中保留的 GameEvent 最大条数。
     * 超出部分通过 DataArchiver 归档到磁盘。
     */
    val maxGameEvents: Int
        get() = prefs.getInt(KEY_MAX_GAME_EVENTS, DEFAULT_MAX_GAME_EVENTS)
            .coerceIn(200, ABSOLUTE_MAX_GAME_EVENTS)

    // ==================== 公开属性：总存储配额 ====================

    /**
     * 存储总配额（字节）。
     * 可通过 [setTotalStorageQuotaMb] 在运行时调整。
     */
    val totalStorageQuotaBytes: Long
        get() = prefs.getLong(KEY_TOTAL_STORAGE_QUOTA, DEFAULT_TOTAL_STORAGE_QUOTA_MB * 1024 * 1024)
            .coerceIn(100L * 1024 * 1024, ABSOLUTE_MAX_TOTAL_STORAGE_MB * 1024 * 1024)

    // ==================== 公开属性：分片降级 ====================

    /**
     * 触发分片写入的存档大小阈值（字节）。
     * 当预估存档大小超过此值时，启用分片方案降低单次写入压力。
     */
    val shardingThresholdBytes: Long
        get() = prefs.getLong(KEY_SHARDING_THRESHOLD, DEFAULT_SHARDING_THRESHOLD_MB * 1024 * 1024)
            .coerceIn(20L * 1024 * 1024, maxSaveSizeBytes)

    /**
     * 单个分片的最大大小（字节）。
     */
    val shardMaxSizeBytes: Long
        get() = prefs.getLong(KEY_SHARD_MAX_SIZE, DEFAULT_SHARD_MAX_SIZE_MB * 1024 * 1024)
            .coerceIn(10L * 1024 * 1024, maxSaveSizeBytes / 2)

    // ==================== 公开属性：动态配额 ====================

    /**
     * 是否启用按游戏时长动态调整配额。
     * 启用后，长期游戏的存储配额会自动放大。
     */
    val isDynamicQuotaEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_QUOTA_ENABLED, true)

    /**
     * 当前生效的总存储配额（字节），已包含动态调整因子。
     *
     * @param gameMonth 当前游戏月份（用于计算动态倍率）
     */
    fun getEffectiveTotalStorageQuota(gameMonth: Int): Long {
        val baseQuota = totalStorageQuotaBytes
        if (!isDynamicQuotaEnabled) return baseQuota

        val multiplier = when {
            gameMonth < GAME_PHASE_SHORT_MONTHS -> 1.0f
            gameMonth < GAME_PHASE_LONG_MONTHS -> GAME_PHASE_MEDIUM_MULTIPLIER
            else -> GAME_PHASE_LONG_MULTIPLIER
        }

        val adjusted = (baseQuota * multiplier).toLong()
        return adjusted.coerceAtMost(ABSOLUTE_MAX_TOTAL_STORAGE_MB * 1024 * 1024)
    }

    /**
     * 根据游戏时长获取当前阶段描述，用于日志和监控。
     */
    fun getGamePhaseDescription(gameMonth: Int): String {
        return when {
            gameMonth < GAME_PHASE_SHORT_MONTHS -> "短期(初期)"
            gameMonth < GAME_PHASE_LONG_MONTHS -> "中期(发展)"
            else -> "长期(成熟)"
        }
    }

    // ==================== 运行时配置修改 API ====================

    fun setMaxSaveSizeMb(sizeMb: Long) {
        val clamped = sizeMb.coerceIn(1, ABSOLUTE_MAX_SAVE_SIZE_MB)
        prefs.edit().putLong(KEY_MAX_SAVE_SIZE, clamped * 1024 * 1024).apply()
        Log.i(TAG, "maxSaveSize updated: ${clamped}MB (${clamped * 1024 * 1024} bytes)")
    }

    fun setMaxBattleLogs(count: Int) {
        val clamped = count.coerceIn(100, ABSOLUTE_MAX_BATTLE_LOGS)
        prefs.edit().putInt(KEY_MAX_BATTLE_LOGS, clamped).apply()
        Log.i(TAG, "maxBattleLogs updated: $clamped")
    }

    fun setMaxGameEvents(count: Int) {
        val clamped = count.coerceIn(200, ABSOLUTE_MAX_GAME_EVENTS)
        prefs.edit().putInt(KEY_MAX_GAME_EVENTS, clamped).apply()
        Log.i(TAG, "maxGameEvents updated: $clamped")
    }

    fun setTotalStorageQuotaMb(quotaMb: Long) {
        val clamped = quotaMb.coerceIn(100, ABSOLUTE_MAX_TOTAL_STORAGE_MB)
        prefs.edit().putLong(KEY_TOTAL_STORAGE_QUOTA, clamped * 1024 * 1024).apply()
        Log.i(TAG, "totalStorageQuota updated: ${clamped}MB")
    }

    fun setShardingThresholdMb(thresholdMb: Long) {
        val clamped = thresholdMb.coerceIn(20, ABSOLUTE_MAX_SAVE_SIZE_MB)
        prefs.edit().putLong(KEY_SHARDING_THRESHOLD, clamped * 1024 * 1024).apply()
        Log.i(TAG, "shardingThreshold updated: ${clamped}MB")
    }

    fun setDynamicQuotaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_QUOTA_ENABLED, enabled).apply()
        Log.i(TAG, "dynamicQuota enabled=$enabled")
    }

    /**
     * 重置所有配置为默认值。
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.i(TAG, "All save limits reset to defaults")
    }

    // ==================== 配置快照 / 调试用 ====================

    /**
     * 导出当前全部配置为可读字符串，便于调试和诊断。
     */
    fun dumpConfig(): String {
        return buildString {
            appendLine("=== SaveLimitsConfig ===")
            appendLine("maxSaveSize:       ${maxSaveSizeBytes / 1024 / 1024} MB")
            appendLine("maxBattleLogs:     $maxBattleLogs")
            appendLine("maxGameEvents:     $maxGameEvents")
            appendLine("totalStorageQuota: ${totalStorageQuotaBytes / 1024 / 1024} MB")
            appendLine("shardingThreshold: ${shardingThresholdBytes / 1024 / 1024} MB")
            appendLine("shardMaxSize:      ${shardMaxSizeBytes / 1024 / 1024} MB")
            appendLine("dynamicQuota:      $isDynamicQuotaEnabled")
        }
    }
}
