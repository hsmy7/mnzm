package com.xianxia.sect.data.wal

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 快照重要性级别
 *
 * 分级说明：
 * - CRITICAL: 关键快照，永不清除（境界突破、重大事件等）
 * - IMPORTANT: 重要快照，保留更长时间（年份变化、重要进度节点）
 * - NORMAL: 普通快照，按常规策略清理
 */
enum class SnapshotPriority(val level: Int, val retentionMultiplier: Float) {
    CRITICAL(3, Float.MAX_VALUE),    // 永不清除
    IMPORTANT(2, 4.0f),              // 保留 4 倍基础时间
    NORMAL(1, 1.0f);                 // 标准保留时间

    companion object {
        fun fromLevel(level: Int): SnapshotPriority? =
            entries.find { it.level == level }
    }
}

/**
 * 扩展的快照元数据，包含重要性级别和游戏上下文信息
 */
data class SnapshotMetadata(
    val snapshotPath: String,
    val timestamp: Long,
    val priority: SnapshotPriority = SnapshotPriority.NORMAL,
    val gameYear: Int = 0,
    val gameEvent: String? = null,
    val slot: Int = 0,
    val txnId: Long = 0L,
    val snapshotSize: Long = 0L,
    val isPermanent: Boolean = false
)

/**
 * 智能快照保留策略
 *
 * 设计目标：
 * 1. 确保关键恢复点不被过早清理
 * 2. 基于游戏进度自动识别重要快照
 * 3. 提供多级重要性分级管理
 * 4. 支持安全清理机制，防止误删关键数据
 *
 * 使用场景：
 * - 境界突破时自动创建 CRITICAL 级别快照
 * - 年份变化时标记为 IMPORTANT 级别
 * - 常规操作使用 NORMAL 级别
 * - 清理时根据优先级和保留规则智能决策
 */
class SnapshotRetentionPolicy(
    private val snapshotDir: File,
    private val minSnapshotsToKeep: Int = 3,
    private val baseRetentionMs: Long = 12 * 60 * 60 * 1000L  // 12小时基准时间
) {
    companion object {
        private const val TAG = "SnapshotRetentionPolicy"
        private const val REGISTRY_FILE_NAME = "snapshot_registry.json"

        // 重要事件类型标识
        const val EVENT_REALM_BREAKTHROUGH = "realm_breakthrough"      // 境界突破
        const val EVENT_MAJOR_QUEST_COMPLETE = "quest_complete"         // 重大任务完成
        const val EVENT_LEGACY_UNLOCK = "legacy_unlock"                // 遗产解锁
        const val EVENT_YEAR_CHANGE = "year_change"                    // 年份变化
        const val EVENT_MANUAL_SAVE = "manual_save"                    // 手动存档
        
        // 优先级映射表：根据事件类型自动确定优先级
        private val EVENT_PRIORITY_MAP = mapOf(
            EVENT_REALM_BREAKTHROUGH to SnapshotPriority.CRITICAL,
            EVENT_MAJOR_QUEST_COMPLETE to SnapshotPriority.CRITICAL,
            EVENT_LEGACY_UNLOCK to SnapshotPriority.CRITICAL,
            EVENT_YEAR_CHANGE to SnapshotPriority.IMPORTANT,
            EVENT_MANUAL_SAVE to SnapshotPriority.IMPORTANT
        )
    }

    // 已注册的快照元数据（线程安全）
    private val registeredSnapshots = ConcurrentHashMap<String, SnapshotMetadata>()

    // 注册表持久化文件
    private val registryFile: File by lazy {
        File(snapshotDir, REGISTRY_FILE_NAME)
    }

    // 游戏上下文追踪（用于检测进度变化）
    @Volatile
    private var lastKnownGameYear: Int = 0
    
    /**
     * 注册快照并分配优先级
     *
     * @param snapshotPath 快照文件路径
     * @param txnId 事务ID
     * @param slot 存储槽位
     * @param gameEvent 关联的游戏事件类型（可选）
     * @param currentGameYear 当前游戏年份（可选）
     * @return 注册后的快照元数据
     */
    fun registerSnapshot(
        snapshotPath: String,
        txnId: Long,
        slot: Int,
        gameEvent: String? = null,
        currentGameYear: Int = 0
    ): SnapshotMetadata {
        val file = File(snapshotPath)
        val fileSize = if (file.exists()) file.length() else 0L
        
        // 自动推断优先级
        val priority = determinePriority(gameEvent, currentGameYear)
        
        // 检测是否应该设为永久快照
        val isPermanent = (priority == SnapshotPriority.CRITICAL)
        
        val metadata = SnapshotMetadata(
            snapshotPath = snapshotPath,
            timestamp = System.currentTimeMillis(),
            priority = priority,
            gameYear = currentGameYear,
            gameEvent = gameEvent,
            slot = slot,
            txnId = txnId,
            snapshotSize = fileSize,
            isPermanent = isPermanent
        )
        
        registeredSnapshots[snapshotPath] = metadata

        // 持久化注册表到磁盘
        persistRegistry()

        Log.d(TAG, "Registered snapshot: $snapshotPath with priority=$priority, event=$gameEvent")
        
        return metadata
    }

    /**
     * 确定快照优先级
     *
     * 规则：
     * 1. 如果有明确的事件类型，按事件映射表确定
     * 2. 如果年份发生变化，提升为 IMPORTANT
     * 3. 否则默认为 NORMAL
     */
    private fun determinePriority(gameEvent: String?, currentGameYear: Int): SnapshotPriority {
        // 规则1：基于事件类型的显式优先级
        if (gameEvent != null) {
            EVENT_PRIORITY_MAP[gameEvent]?.let { return it }
        }
        
        // 规则2：年份变化检测
        if (currentGameYear > 0 && lastKnownGameYear > 0 && currentGameYear != lastKnownGameYear) {
            lastKnownGameYear = currentGameYear
            return SnapshotPriority.IMPORTANT
        }
        
        // 更新年份记录
        if (currentGameYear > 0) {
            lastKnownGameYear = currentGameYear
        }
        
        // 规则3：默认普通级别
        return SnapshotPriority.NORMAL
    }

    /**
     * 获取可安全清理的快照列表
     *
     * 清理逻辑：
     * 1. CRITICAL 和 isPermanent 的快照永不清理
     * 2. 至少保留 [minSnapshotsToKeep] 个最新快照（无论优先级如何）
     * 3. 按优先级和时间计算过期阈值
     * 4. 返回满足所有清理条件的快照列表
     *
     * @return 可安全删除的快照路径列表
     */
    fun getSnapshotsEligibleForCleanup(): List<SnapshotMetadata> {
        val now = System.currentTimeMillis()
        val allSnapshots = registeredSnapshots.values.sortedByDescending { it.timestamp }
        
        if (allSnapshots.isEmpty()) return emptyList()
        
        // 安全检查1：确保至少保留最小数量的快照
        val mustKeepCount = minOf(minSnapshotsToKeep, allSnapshots.size)
        val candidates = allSnapshots.drop(mustKeepCount)
        
        // 安全检查2：过滤掉永久快照和CRITICAL级别
        val eligible = candidates.filter { !it.isPermanent && it.priority != SnapshotPriority.CRITICAL }
        
        // 计算每个快照的过期时间
        val expiredSnapshots = eligible.filter { snapshot ->
            val retentionTime = calculateRetentionTime(snapshot.priority)
            val age = now - snapshot.timestamp
            
            // 额外安全检查：如果这是某个slot的最新快照，延长保留时间
            val isNewestForSlot = allSnapshots
                .filter { it.slot == snapshot.slot }
                .firstOrNull() == snapshot
            
            if (isNewestForSlot) {
                // 该slot的最新快照，需要保留更久
                age > retentionTime * 2
            } else {
                age > retentionTime
            }
        }
        
        Log.d(TAG, "Cleanup eligible: ${expiredSnapshots.size} of ${allSnapshots.size} snapshots")
        
        return expiredSnapshots
    }

    /**
     * 根据优先级计算保留时长
     */
    private fun calculateRetentionTime(priority: SnapshotPriority): Long {
        return when (priority) {
            SnapshotPriority.CRITICAL -> Long.MAX_VALUE  // 永不清理
            SnapshotPriority.IMPORTANT -> (baseRetentionMs * priority.retentionMultiplier).toLong()
            SnapshotPriority.NORMAL -> baseRetentionMs
        }
    }

    /**
     * 执行安全的快照清理
     *
     * 在执行实际删除前进行多重验证：
     * 1. 确认快照文件存在
     * 2. 验证不是永久快照
     * 3. 验证不会导致某个slot无可用快照
     * 4. 删除后更新注册表
     *
     * @return 清理结果统计
     */
    data class CleanupResult(
        val successCount: Int,
        val skippedCount: Int,
        val errorCount: Int,
        val details: List<String>
    )

    fun performSafeCleanup(): CleanupResult {
        val eligibleSnapshots = getSnapshotsEligibleForCleanup()
        
        var successCount = 0
        var skippedCount = 0
        var errorCount = 0
        val details = mutableListOf<String>()
        
        for (snapshot in eligibleSnapshots) {
            try {
                // 安全检查1：确认文件存在
                val file = File(snapshot.snapshotPath)
                if (!file.exists()) {
                    details.add("SKIP: ${snapshot.snapshotPath} - file not found")
                    skippedCount++
                    continue
                }
                
                // 安全检查2：再次验证不是永久快照（防御性编程）
                if (snapshot.isPermanent || snapshot.priority == SnapshotPriority.CRITICAL) {
                    details.add("PROTECTED: ${snapshot.snapshotPath} - permanent/critical snapshot")
                    skippedCount++
                    continue
                }
                
                // 安全检查3：验证该slot是否还有其他快照
                val slotSnapshots = registeredSnapshots.values
                    .filter { it.slot == snapshot.slot && it.snapshotPath != snapshot.snapshotPath }
                
                if (slotSnapshots.isEmpty()) {
                    details.add("PROTECTED: ${snapshot.snapshotPath} - only snapshot for slot ${snapshot.slot}")
                    skippedCount++
                    continue
                }
                
                // 执行删除
                if (file.delete()) {
                    registeredSnapshots.remove(snapshot.snapshotPath)
                    successCount++
                    details.add("DELETED: ${snapshot.snapshotPath}")
                    Log.d(TAG, "Cleaned up snapshot: ${snapshot.snapshotPath}")
                } else {
                    errorCount++
                    details.add("ERROR: Failed to delete ${snapshot.snapshotPath}")
                    Log.w(TAG, "Failed to delete snapshot: ${snapshot.snapshotPath}")
                }
                
            } catch (e: Exception) {
                errorCount++
                details.add("ERROR: ${e.message}")
                Log.e(TAG, "Error cleaning up snapshot ${snapshot.snapshotPath}", e)
            }
        }
        
        Log.i(TAG, "Cleanup completed: $successCount deleted, $skippedCount protected, $errorCount errors")
        
        return CleanupResult(successCount, skippedCount, errorCount, details)
    }

    /**
     * 从磁盘重新扫描并注册现有快照
     *
     * 用于启动时恢复快照注册表状态
     * 会解析文件名中的元数据信息
     */
    fun scanExistingSnapshots() {
        if (!snapshotDir.exists()) return
        
        val snapshotFiles = snapshotDir.listFiles { file ->
            file.extension == "snap" && file.name.startsWith("slot_")
        } ?: return
        
        for (file in snapshotFiles) {
            try {
                // 解析文件名格式: slot_X_txn_Y.snap
                val nameParts = file.name.removeSuffix(".snap").split("_")
                if (nameParts.size >= 4 && nameParts[0] == "slot" && nameParts[2] == "txn") {
                    val slot = nameParts[1].toIntOrNull() ?: continue
                    val txnId = nameParts[3].toLongOrNull() ?: continue
                    
                    // 对于已存在的快照，默认设为NORMAL级别
                    // 如果后续发现是重要的，可以通过 markAsImportant 升级
                    registerSnapshot(
                        snapshotPath = file.absolutePath,
                        txnId = txnId,
                        slot = slot
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse snapshot filename: ${file.name}", e)
            }
        }
        
        Log.i(TAG, "Scanned and registered ${snapshotFiles.size} existing snapshots")
    }

    /**
     * 将已注册的快照升级为更高优先级
     *
     * @param snapshotPath 快照路径
     * @param newPriority 新的优先级
     * @return 是否升级成功
     */
    fun upgradePriority(snapshotPath: String, newPriority: SnapshotPriority): Boolean {
        val existing = registeredSnapshots[snapshotPath] ?: return false
        
        // 只允许升级，不允许降级
        if (newPriority.level <= existing.priority.level) {
            Log.w(TAG, "Cannot downgrade priority for $snapshotPath")
            return false
        }
        
        val upgraded = existing.copy(
            priority = newPriority,
            isPermanent = newPriority == SnapshotPriority.CRITICAL
        )
        
        registeredSnapshots[snapshotPath] = upgraded

        // 持久化优先级变更
        persistRegistry()

        Log.i(TAG, "Upgraded snapshot $snapshotPath to $newPriority")
        return true
    }

    /**
     * 获取指定slot的最佳恢复快照
     *
     * 选择策略：
     * 1. 优先选择最高优先级的有效快照
     * 2. 同优先级下选择最新的
     * 3. 验证文件存在性和完整性
     *
     * @param slot 目标槽位
     * @return 最佳恢复快照元数据，如果没有可用快照则返回null
     */
    fun getBestRecoverySnapshot(slot: Int): SnapshotMetadata? {
        val slotSnapshots = registeredSnapshots.values
            .filter { it.slot == slot }
            .sortedWith(compareByDescending<SnapshotMetadata> { it.priority.level }
                .thenByDescending { it.timestamp })
        
        for (snapshot in slotSnapshots) {
            val file = File(snapshot.snapshotPath)
            if (file.exists() && file.length() > 0) {
                return snapshot
            }
        }
        
        return null
    }

    /**
     * 获取保留策略统计信息
     */
    fun getStats(): RetentionPolicyStats {
        val now = System.currentTimeMillis()
        val snapshots = registeredSnapshots.values.toList()
        
        return RetentionPolicyStats(
            totalRegisteredSnapshots = snapshots.size,
            criticalCount = snapshots.count { it.priority == SnapshotPriority.CRITICAL },
            importantCount = snapshots.count { it.priority == SnapshotPriority.IMPORTANT },
            normalCount = snapshots.count { it.priority == SnapshotPriority.NORMAL },
            permanentSnapshots = snapshots.count { it.isPermanent },
            totalSizeBytes = snapshots.sumOf { it.snapshotSize },
            oldestSnapshotAge = if (snapshots.isNotEmpty()) 
                now - snapshots.minOf { it.timestamp } else 0L,
            slotsWithSnapshots = snapshots.map { it.slot }.distinct().size
        )
    }

    data class RetentionPolicyStats(
        val totalRegisteredSnapshots: Int,
        val criticalCount: Int,
        val importantCount: Int,
        val normalCount: Int,
        val permanentSnapshots: Int,
        val totalSizeBytes: Long,
        val oldestSnapshotAge: Long,
        val slotsWithSnapshots: Int
    )

    /**
     * 清空所有注册信息（用于重置或测试）
     */
    fun clearRegistry() {
        registeredSnapshots.clear()
        lastKnownGameYear = 0
        // 删除持久化文件
        if (registryFile.exists()) {
            registryFile.delete()
        }
        Log.d(TAG, "Snapshot registry cleared")
    }

    // ==================== 注册表持久化 ====================

    /**
     * 将注册表持久化到 JSON 文件
     *
     * 在每次 registerSnapshot、upgradePriority 后自动调用，
     * 确保进程崩溃后能恢复完整的快照元数据。
     */
    fun persistRegistry() {
        try {
            val jsonArray = JSONArray()
            for (metadata in registeredSnapshots.values) {
                jsonArray.put(metadataToJson(metadata))
            }
            registryFile.writeText(jsonArray.toString(2))
            Log.d(TAG, "Persisted registry with ${registeredSnapshots.size} entries to ${registryFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist snapshot registry", e)
        }
    }

    /**
     * 从 JSON 文件加载注册表
     *
     * 在初始化时自动调用，恢复上次的快照元数据状态。
     * 应在 scanExistingSnapshots() 之前调用，以避免覆盖已有元数据。
     *
     * @return 成功加载的条目数量
     */
    fun loadRegistry(): Int {
        if (!registryFile.exists()) {
            Log.d(TAG, "No existing registry file found at ${registryFile.absolutePath}")
            return 0
        }

        return try {
            val content = registryFile.readText()
            val jsonArray = JSONArray(content)
            var loadedCount = 0

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val metadata = jsonToMetadata(json)
                if (metadata != null) {
                    // 验证快照文件是否仍然存在
                    val file = File(metadata.snapshotPath)
                    if (file.exists()) {
                        registeredSnapshots[metadata.snapshotPath] = metadata
                        loadedCount++
                    } else {
                        Log.d(TAG, "Skipping non-existent snapshot: ${metadata.snapshotPath}")
                    }
                }
            }

            Log.i(TAG, "Loaded $loadedCount snapshot metadata entries from registry")
            loadedCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot registry", e)
            0
        }
    }

    /**
     * 初始化：加载已有注册表并扫描磁盘上的快照文件
     *
     * 建议在应用启动时调用此方法完成完整初始化：
     * 1. 先从 JSON 恢复带优先级的元数据
     * 2. 再扫描磁盘补充新发现的快照
     */
    fun initialize() {
        loadRegistry()
        scanExistingSnapshots()
        Log.i(TAG, "SnapshotRetentionPolicy initialized with ${registeredSnapshots.size} snapshots")
    }

    // ==================== JSON 序列化辅助方法 ====================

    private fun metadataToJson(metadata: SnapshotMetadata): JSONObject {
        return JSONObject().apply {
            put("snapshotPath", metadata.snapshotPath)
            put("timestamp", metadata.timestamp)
            put("priority", metadata.priority.name)
            put("gameYear", metadata.gameYear)
            put("gameEvent", metadata.gameEvent)
            put("slot", metadata.slot)
            put("txnId", metadata.txnId)
            put("snapshotSize", metadata.snapshotSize)
            put("isPermanent", metadata.isPermanent)
        }
    }

    private fun jsonToMetadata(json: JSONObject): SnapshotMetadata? {
        return try {
            val priorityStr = json.optString("priority", SnapshotPriority.NORMAL.name)
            val priority = SnapshotPriority.valueOf(priorityStr)

            SnapshotMetadata(
                snapshotPath = json.getString("snapshotPath"),
                timestamp = json.getLong("timestamp"),
                priority = priority,
                gameYear = json.optInt("gameYear", 0),
                gameEvent = json.optNullableString("gameEvent"),
                slot = json.optInt("slot", 0),
                txnId = json.optLong("txnId", 0L),
                snapshotSize = json.optLong("snapshotSize", 0L),
                isPermanent = json.optBoolean("isPermanent", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse snapshot metadata from JSON", e)
            null
        }
    }

    /**
     * JSONObject 扩展：安全获取可空字符串
     */
    private fun JSONObject.optNullableString(key: String): String? {
        return if (this.isNull(key)) null else this.optString(key)
    }
}
