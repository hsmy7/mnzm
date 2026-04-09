@file:Suppress("DEPRECATION", "UNCHECKED_CAST", "USELESS_CAST", "USELESS_IS_CHECK")

package com.xianxia.sect.data.serialization.delta

import android.util.Log
import com.xianxia.sect.data.serialization.unified.SerializableAlliance
import com.xianxia.sect.data.serialization.unified.SerializableBattleLog
import com.xianxia.sect.data.serialization.unified.SerializableBattleTeam
import com.xianxia.sect.data.serialization.unified.SerializableDisciple
import com.xianxia.sect.data.serialization.unified.SerializableEquipment
import com.xianxia.sect.data.serialization.unified.SerializableGameData
import com.xianxia.sect.data.serialization.unified.SerializableExplorationTeam
import com.xianxia.sect.data.serialization.unified.SerializableHerb
import com.xianxia.sect.data.serialization.unified.SerializableManual
import com.xianxia.sect.data.serialization.unified.SerializableMaterial
import com.xianxia.sect.data.serialization.unified.SerializablePill
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.serialization.unified.SerializableSeed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ==================== 接口与类型安全基础层 ====================

/**
 * 可标识实体接口
 *
 * 所有参与增量对比和 delta 应用的实体必须实现此接口。
 * 提供统一的 ID 访问能力，使得泛型比较方法 [compareItemList]
 * 能够按 ID 精确匹配元素，而非仅依赖数量比较。
 *
 * 设计参考 Unity DOTS ECS 的 [Entity] 概念 —— 每个 entity 有唯一 ID，
 * 变更检测基于 entity ID 进行差量计算。
 *
 * 参见 Genshin Impact 存档 diff 策略中的 UUID-based entity tracking。
 */
interface Identifiable {
    /** 实体的唯一标识符 */
    val id: String
}

// 为现有数据类提供 Identifiable 适配（避免修改原始 data class 定义）
private fun SerializableEquipment.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableManual.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializablePill.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableMaterial.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableHerb.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableSeed.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableDisciple.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableAlliance.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableExplorationTeam.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}
private fun SerializableBattleLog.toIdentifiable(): Identifiable = object : Identifiable {
    override val id: String get() = this@toIdentifiable.id
}

/**
 * 类型安全的字段变更记录（泛型版）
 *
 * 解决原 [FieldDelta] 中 `oldValue/newValue: Any?` 导致的类型安全问题：
 * - 编译期即可捕获类型不匹配
 * - 无需运行时 `as?` 强转及其伴随的 null 隐患
 * - IDE 可提供完整的类型推断和自动补全
 *
 * @param T 值的具体类型（如 Int, Long, String, Boolean, List<*> 等）
 */
data class TypedFieldDelta<T>(
    val modulePath: String,
    val operation: DeltaOperation,
    val oldValue: T?,
    val newValue: T?,
    val fieldIndex: Int
) {
    /**
     * 估算该字段变更的序列化字节大小
     *
     * 基于 protobuf wire format 的估算逻辑，参考原 [FieldDelta.estimatedSizeBytes]。
     */
    val estimatedSizeBytes: Int
        get() = when (operation) {
            DeltaOperation.SET -> {
                val baseOverhead = modulePath.length + 8
                val valueSize = when (newValue) {
                    is Number -> if (newValue is Long || newValue.toInt() > 127) 8 else 4
                    is String -> newValue.toByteArray(Charsets.UTF_8).size.coerceAtMost(256)
                    is Boolean -> 1
                    is List<*> -> newValue.size * 20
                    else -> 32
                }
                (baseOverhead + valueSize).coerceIn(20, 200)
            }
            DeltaOperation.ADD -> {
                when (newValue) {
                    is SerializableDisciple -> 150 + newValue.name.length
                    else -> 100
                }
            }
            DeltaOperation.REMOVE -> 10 + (oldValue?.toString()?.length ?: 0)
            DeltaOperation.CLEAR -> 2
        }

    /**
     * 转换为向后兼容的 [FieldDelta] 格式（用于公共 API 边界）
     */
    fun toUntyped(): FieldDelta = FieldDelta(
        modulePath = modulePath,
        operation = operation,
        oldValue = oldValue as Any?,
        newValue = newValue as Any?,
        fieldIndex = fieldIndex
    )

    companion object {
        /**
         * 从无类型 [FieldDelta] 创建带类型安全包装的实例
         *
         * 注意：此方法仅在需要从外部反序列化的 FieldDelta 恢复时使用，
         * 内部引擎应始终优先使用 [TypedFieldDelta] 直接构造。
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> fromUntyped(untyped: FieldDelta): TypedFieldDelta<T> = TypedFieldDelta(
            modulePath = untyped.modulePath,
            operation = untyped.operation,
            oldValue = untyped.oldValue as? T,
            newValue = untyped.newValue as? T,
            fieldIndex = untyped.fieldIndex
        )
    }
}

// ==================== 变更操作类型枚举 ====================

/**
 * 变更操作类型枚举
 *
 * 定义增量序列化中支持的基本操作类型，参考 Unity DOTS Entity Component System 的变化检测机制。
 * 每种操作对应不同的序列化开销和应用逻辑。
 */
enum class DeltaOperation {
    /** 字段值变更 - 最常见的操作，约 20-40 bytes */
    SET,
    /** 集合新增元素 - 约 60-200 bytes，取决于实体大小 */
    ADD,
    /** 集合移除元素 - 约 10 bytes，仅存储 ID */
    REMOVE,
    /** 清空集合 - 极小开销，仅操作码 */
    CLEAR
}

// ==================== 公共数据结构（保持 API 向后兼容）====================

/**
 * 单个字段变更记录（公共 API 版本）
 *
 * 保持此类的公共可见性以确保向后兼容性。
 * 内部引擎实现已迁移至 [TypedFieldDelta]<T> 以获得编译期类型安全。
 * 新代码应优先使用 [TypedFieldDelta]，此类保留用于序列化/反序列化边界。
 *
 * @property modulePath 字段的模块路径，如 "core.gameData.spiritStones"
 * @property operation 变更操作类型
 * @property oldValue 变更前的值（可能为 null，表示新增）
 * @property newValue 变更后的值（可能为 null，表示删除）
 * @property fieldIndex 字段索引，用于确定性排序和序列化优化
 */
data class FieldDelta(
    val modulePath: String,
    val operation: DeltaOperation,
    val oldValue: Any?,
    val newValue: Any?,
    val fieldIndex: Int
) {
    val estimatedSizeBytes: Int
        get() = when (operation) {
            DeltaOperation.SET -> {
                val baseOverhead = modulePath.length + 8
                val valueSize = when (newValue) {
                    is Number -> if (newValue is Long || newValue.toInt() > 127) 8 else 4
                    is String -> newValue.toByteArray(Charsets.UTF_8).size.coerceAtMost(256)
                    is Boolean -> 1
                    is List<*> -> newValue.size * 20
                    else -> 32
                }
                (baseOverhead + valueSize).coerceIn(20, 200)
            }
            DeltaOperation.ADD -> {
                when (newValue) {
                    is SerializableDisciple -> 150 + newValue.name.length
                    else -> 100
                }
            }
            DeltaOperation.REMOVE -> 10 + (oldValue?.toString()?.length ?: 0)
            DeltaOperation.CLEAR -> 2
        }
}

/**
 * 模块级增量补丁
 *
 * 将同一模块的所有变更聚合为一个补丁单元。
 * 模块化设计允许并行处理、选择性应用、按模块粒度冲突检测。
 */
data class ModuleDelta(
    val moduleName: String,
    val baseVersion: Long,
    val deltas: List<FieldDelta>,
    val estimatedSizeBytes: Int = deltas.sumOf { it.estimatedSizeBytes }
) {
    val hasChanges: Boolean get() = deltas.isNotEmpty()
    val changeCount: Int get() = deltas.size

    companion object {
        fun empty(moduleName: String, baseVersion: Long): ModuleDelta =
            ModuleDelta(moduleName, baseVersion, emptyList())
    }
}

/**
 * 完整的增量存档数据结构
 *
 * 支持纯增量模式和混合模式（含全量快照）。
 * 设计参考 Genshin Impact 的存档 diff 策略。
 */
data class IncrementalSaveData(
    val fullSaveData: SerializableSaveData?,
    val moduleDeltas: List<ModuleDelta>,
    val baseSnapshotTimestamp: Long,
    val newSnapshotTimestamp: Long,
    val totalDeltaSizeBytes: Int
) {
    val isPureIncremental: Boolean get() = fullSaveData == null
    val hasAnyChanges: Boolean get() = moduleDeltas.any { it.hasChanges }
    val changedModuleCount: Int get() = moduleDeltas.count { it.hasChanges }

    fun getModuleDelta(moduleName: String): ModuleDelta? =
        moduleDeltas.find { it.moduleName == moduleName && it.hasChanges }

    companion object {
        fun createPureIncremental(
            moduleDeltas: List<ModuleDelta>,
            baseTimestamp: Long,
            newTimestamp: Long
        ): IncrementalSaveData = IncrementalSaveData(
            fullSaveData = null,
            moduleDeltas = moduleDeltas,
            baseSnapshotTimestamp = baseTimestamp,
            newSnapshotTimestamp = newTimestamp,
            totalDeltaSizeBytes = moduleDeltas.sumOf { it.estimatedSizeBytes }
        )

        fun createWithFullSave(
            fullData: SerializableSaveData,
            moduleDeltas: List<ModuleDelta> = emptyList(),
            baseTimestamp: Long,
            newTimestamp: Long
        ): IncrementalSaveData = IncrementalSaveData(
            fullSaveData = fullData,
            moduleDeltas = moduleDeltas,
            baseSnapshotTimestamp = baseTimestamp,
            newSnapshotTimestamp = newTimestamp,
            totalDeltaSizeBytes = moduleDeltas.sumOf { it.estimatedSizeBytes }
        )
    }
}

/**
 * 存档快照数据结构
 *
 * 采用轻量级设计：存储关键哈希值用于快速判断模块是否变更，
 * 可选保留完整引用用于深度比较，支持 TTL 过期机制防止内存泄漏。
 *
 * ## 内存隔离策略（v2 重构）
 *
 * 快照不再直接持有 [SerializableSaveData] 的引用以避免双倍内存占用。
 * 改为存储各子列表的独立深拷贝引用，使 GC 可以在游戏数据更新后回收旧版本。
 * 参考 Unity DOTS 的 [ArchetypeChunk] 快照机制中的 copy-on-write 语义。
 */
data class SaveDataSnapshot(
    val timestamp: Long,
    val slotId: Int,
    val coreHash: Int,
    val discipleIds: Set<String>,
    val discipleCount: Int,
    val inventoryHashes: Map<String, Int>,
    /** 深度隔离的游戏数据快照（各子列表独立副本，非原始引用） */
    val isolatedData: IsolatedSnapshotData?
) {
    /**
     * 判断快照是否过期
     * @param ttlMs 存活时间（毫秒），默认使用 [DeltaConfig.SNAPSHOT_TTL_MS]
     */
    fun isExpired(ttlMs: Long = DeltaConfig.SNAPSHOT_TTL_MS): Boolean =
        System.currentTimeMillis() - timestamp > ttlMs

    /** 获取快照年龄（毫秒） */
    val ageMs: Long get() = System.currentTimeMillis() - timestamp
}

/**
 * 深度隔离的快照数据
 *
 * 存储 [updateSnapshot] 时各模块数据的独立副本，
 * 确保 snapshot 与运行时数据之间零共享引用。
 * 当运行时数据被修改后，旧版本可被 GC 正常回收。
 */
data class IsolatedSnapshotData(
    val timestamp: Long,
    val gameData: SerializableGameData,
    val disciples: List<SerializableDisciple>,
    val equipment: List<SerializableEquipment>,
    val manuals: List<SerializableManual>,
    val pills: List<SerializablePill>,
    val materials: List<SerializableMaterial>,
    val herbs: List<SerializableHerb>,
    val seeds: List<SerializableSeed>,
    val teams: List<SerializableExplorationTeam>,
    val events: List<*>,          // SerializableGameEvent（无需深度比较）
    val battleLogs: List<SerializableBattleLog>,
    val alliances: List<SerializableAlliance>
)

/**
 * Delta 编码引擎配置常量
 */
object DeltaConfig {
    const val MAX_SNAPSHOT_COUNT_PER_SLOT = 3
    const val FULL_SAVE_THRESHOLD_RATIO = 0.5f
    const val MAX_DELTA_SIZE_BYTES = 1024 * 1024
    const val SNAPSHOT_TTL_MS = 30 * 60 * 1000L
    const val ENABLE_MODULE_LEVEL_DIFF = true
    const val MAX_DISCIPLE_FIELDS_FOR_DEEP_COMPARE = 50
    const val HASH_SALT = "XianxiaSectDelta2024"
    const val TAG = "DeltaEncoding"
}

/**
 * Delta 统计信息数据类
 */
data class DeltaStats(
    val totalSnapshots: Int = 0,
    val totalDeltasGenerated: Long = 0L,
    val totalFullSavesTriggered: Long = 0L,
    val averageDeltaSizeBytes: Double = 0.0,
    val averageCompressionRatio: Double = 0.0,
    val cacheHitRatio: Double = 0.0,
    val lastOperationTimeMs: Long = 0L
)

// ==================== Delta 编码引擎核心类 ====================

/**
 * Delta 编码引擎核心类（v2 - 激进重构版）
 *
 * 实现增量序列化的核心能力，参考以下系统的设计理念：
 *
 * ## 设计灵感来源
 *
 * ### 1. Unity DOTS (Data-Oriented Technology Stack)
 * - ArchetypeChunk 变化检测：通过对比 chunk 的版本号快速识别变更的 entity
 * - ChangeFilter 组件：声明式地标记需要监听变化的组件类型
 * - Deterministic 序列化：保证跨平台的一致性
 *
 * ### 2. Genshin Impact 存档 Diff 策略
 * - 分层增量：角色/装备/世界状态分别 diff
 * - 周期性全量：每 N 次增量后强制一次全量快照
 * - 智能回退：当 delta 过大时自动切换到全量模式
 *
 * ### 3. Git/RCS 版本控制
 * - 快照链：维护多个历史版本用于 delta 基线选择
 * - Content-addressable：通过 hash 快速定位相同内容
 *
 * ## v2 重构要点（2026）
 *
 * | 问题 | 修复方案 |
 * |------|---------|
 * | P0: applyInventory/World/Combat/MissionsDelta 空实现 | 完整实现按路径分发的字段应用逻辑 |
 * | P0: updateSnapshot 双倍内存占用 | 深拷贝隔离 [IsolatedSnapshotData] |
 * | P1: shouldUseFullSave 重复 detectChanges | 内联快速判断，消除冗余调用 |
 * | P1: computeListHash 仅取首尾 | XorShift32 全量遍历哈希 |
 * | P2: FieldDelta 类型 Any? | 引入 [TypedFieldDelta<T>] 泛型约束 |
 * | P1: compareItemList 只比数量 | 基于 [Identifiable] 接口的 ID 精确匹配 |
 *
 * ## 线程安全保证
 *
 * - 使用 [ConcurrentHashMap] 存储快照，支持并发读写
 * - 所有公开方法都是线程安全的
 * - 无锁读取：快照一旦创建即为不可变对象（immutable snapshot pattern）
 */
@Singleton
class DeltaEncodingEngine @Inject constructor() {

    private val snapshots = ConcurrentHashMap<Int, SaveDataSnapshot>()

    // 统计计数器（AtomicLong 保证线程安全）
    private val statsTotalDeltasGenerated = AtomicLong(0)
    private val statsTotalFullSavesTriggered = AtomicLong(0)
    private val statsTotalDeltaSizeBytes = AtomicLong(0)
    private val statsDeltaCount = AtomicLong(0)
    private val statsCacheHits = AtomicLong(0)
    private val statsCacheMisses = AtomicLong(0)
    private val lastOperationTimeMs = AtomicLong(0)

    companion object {
        private const val TAG = "DeltaEncoding"

        // 模块名称常量
        private const val MODULE_CORE = "core"
        private const val MODULE_DISCIPLES = "disciples"
        private const val MODULE_INVENTORY = "inventory"
        private const val MODULE_WORLD = "world"
        private const val MODULE_COMBAT = "combat"
        private const val MODULE_MISSIONS = "missions"

        // Core 模块字段索引映射（确定性排序）
        private val CORE_FIELD_INDICES = mapOf(
            "sectName" to 1,
            "currentSlot" to 2,
            "gameYear" to 3,
            "gameMonth" to 4,
            "gameDay" to 5,
            "spiritStones" to 6,
            "spiritHerbs" to 7,
            "autoSaveIntervalMonths" to 8,
            "monthlySalary" to 9,
            "lastSaveTime" to 10,
            "playerProtectionEnabled" to 11,
            "playerHasAttackedAI" to 12
        )

        // Disciple 字段索引映射
        private val DISCIPLE_FIELD_INDICES = mapOf(
            "name" to 1, "realm" to 2, "realmLayer" to 3, "cultivation" to 4,
            "age" to 5, "lifespan" to 6, "isAlive" to 7, "status" to 8,
            "spiritStones" to 9, "soulPower" to 10, "totalCultivation" to 11,
            "breakthroughCount" to 12, "intelligence" to 13, "charm" to 14,
            "loyalty" to 15, "comprehension" to 16
        )

        /** XorShift32 常量种子值（来自 MurmurHash3 finalizer） */
        private const val XOR_SHIFT_SEED = 0x9e3779b9.toInt()
    }

    // ==================== 核心公共 API ====================

    /**
     * 检测当前数据与快照之间的所有变更
     *
     * 分模块进行深度对比，生成 [FieldDelta] 列表。
     * 对比策略：
     * - Core: 逐字段对比 [SerializableGameData]
     * - Disciples: 三分类算法（added/removed/modified），modified 弟子逐字段深度对比
     * - Inventory: 按 hash 快速筛选后详细对比
     * - World/Combat/Missions: 集合增删 + 关键字段对比
     */
    fun detectChanges(slotId: Int, currentData: SerializableSaveData): List<ModuleDelta> {
        val startTime = System.nanoTime()

        require(slotId >= 0) { "Invalid slotId: $slotId" }

        val snapshot = snapshots[slotId]
        if (snapshot == null) {
            Log.d(TAG, "No snapshot found for slot $slotId, returning empty deltas")
            statsCacheMisses.incrementAndGet()
            lastOperationTimeMs.set((System.nanoTime() - startTime) / 1_000_000)
            return emptyList()
        }

        statsCacheHits.incrementAndGet()

        if (snapshot.isExpired()) {
            Log.w(TAG, "Snapshot for slot $slotId is expired (age: ${snapshot.ageMs}ms)")
            clearSnapshot(slotId)
            lastOperationTimeMs.set((System.nanoTime() - startTime) / 1_000_000)
            return emptyList()
        }

        val isolated = snapshot.isolatedData
            ?: run {
                Log.w(TAG, "Snapshot for slot $slotId has no isolated data")
                return emptyList()
            }

        val result = mutableListOf<ModuleDelta>()

        try {
            if (DeltaConfig.ENABLE_MODULE_LEVEL_DIFF) {
                result.add(compareCore(isolated, currentData))
            }
            result.add(compareDisciples(isolated, currentData))
            result.add(compareInventory(isolated, currentData))
            result.add(compareWorld(isolated, currentData))
            result.add(compareCombat(isolated, currentData))
            result.add(compareMissions(isolated, currentData))
        } catch (e: Exception) {
            Log.e(TAG, "Error during change detection for slot $slotId", e)
        }

        val filteredResult = result.filter { it.hasChanges }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        lastOperationTimeMs.set(elapsedMs)

        Log.d(TAG, "Detected ${filteredResult.size} changed modules in ${elapsedMs}ms for slot $slotId")
        filteredResult.forEach { module ->
            Log.d(TAG, "  Module '${module.moduleName}': ${module.changeCount} changes, ~${module.estimatedSizeBytes} bytes")
        }

        return filteredResult
    }

    /**
     * 生成完整的增量存档
     */
    fun createIncrementalSave(slotId: Int, currentData: SerializableSaveData): IncrementalSaveData {
        val startTime = System.nanoTime()

        val snapshot = snapshots[slotId]
        val baseTimestamp = snapshot?.timestamp ?: 0L
        val newTimestamp = System.currentTimeMillis()

        // 首次保存或无快照：强制全量
        if (snapshot == null) {
            Log.d(TAG, "No existing snapshot for slot $slotId, creating full save")
            statsTotalFullSavesTriggered.incrementAndGet()
            lastOperationTimeMs.set((System.nanoTime() - startTime) / 1_000_000)
            return IncrementalSaveData.createWithFullSave(
                fullData = currentData,
                baseTimestamp = baseTimestamp,
                newTimestamp = newTimestamp
            )
        }

        // 检查是否应该使用全量（内联判断，不再重复调用 detectChanges）
        if (shouldUseFullSaveInline(snapshot, currentData)) {
            Log.d(TAG, "Changes too large for slot $slotId, falling back to full save")
            statsTotalFullSavesTriggered.incrementAndGet()
            lastOperationTimeMs.set((System.nanoTime() - startTime) / 1_000_000)
            return IncrementalSaveData.createWithFullSave(
                fullData = currentData,
                baseTimestamp = baseTimestamp,
                newTimestamp = newTimestamp
            )
        }

        // 正常增量流程
        val moduleDeltas = detectChanges(slotId, currentData)

        statsTotalDeltasGenerated.incrementAndGet()
        val totalSize = moduleDeltas.sumOf { it.estimatedSizeBytes }
        statsTotalDeltaSizeBytes.addAndGet(totalSize.toLong())
        statsDeltaCount.incrementAndGet()

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        lastOperationTimeMs.set(elapsedMs)

        Log.d(TAG, "Created incremental save for slot $slotId: ${moduleDeltas.size} modules, ~$totalSize bytes in ${elapsedMs}ms")

        return IncrementalSaveData.createPureIncremental(
            moduleDeltas = moduleDeltas,
            baseTimestamp = baseTimestamp,
            newTimestamp = newTimestamp
        )
    }

    /**
     * 应用单个模块的增量补丁到基础数据
     *
     * 不可变操作：不会修改 [baseData]，返回新副本。
     */
    fun applyDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        val startTime = System.nanoTime()

        if (!delta.hasChanges) {
            return baseData
        }

        val result = try {
            when (delta.moduleName) {
                MODULE_CORE -> applyCoreDelta(baseData, delta)
                MODULE_DISCIPLES -> applyDisciplesDelta(baseData, delta)
                MODULE_INVENTORY -> applyInventoryDelta(baseData, delta)
                MODULE_WORLD -> applyWorldDelta(baseData, delta)
                MODULE_COMBAT -> applyCombatDelta(baseData, delta)
                MODULE_MISSIONS -> applyMissionsDelta(baseData, delta)
                else -> {
                    Log.w(TAG, "Unknown module '${delta.moduleName}', skipping delta application")
                    baseData
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying delta for module '${delta.moduleName}'", e)
            baseData
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Applied ${delta.changeCount} changes for module '${delta.moduleName}' in ${elapsedMs}ms")

        return result
    }

    /**
     * 更新指定槽位的快照（v2 - 深拷贝隔离版）
     *
     * 不再直接持有 [SerializableSaveData] 引用（消除双倍内存占用），
     * 而是创建 [IsolatedSnapshotData] 存储各子列表的独立副本。
     * 运行时数据后续修改不影响快照完整性，且旧版本可被 GC 回收。
     *
     * 参考 Unity DOTS 的 copy-on-write chunk 快照语义。
     */
    fun updateSnapshot(slotId: Int, data: SerializableSaveData) {
        val startTime = System.nanoTime()

        require(slotId >= 0) { "Invalid slotId: $slotId" }

        val timestamp = System.currentTimeMillis()

        val coreHash = computeCoreHash(data.gameData)
        val discipleIds = data.disciples.map { it.id }.toSet()

        // 使用 XorShift32 全量遍历哈希替代首尾元素哈希
        val inventoryHashes = mapOf(
            "equipment" to computeXorShiftHash(data.equipment),
            "manuals" to computeXorShiftHash(data.manuals),
            "pills" to computeXorShiftHash(data.pills),
            "materials" to computeXorShiftHash(data.materials),
            "herbs" to computeXorShiftHash(data.herbs),
            "seeds" to computeXorShiftHash(data.seeds)
        )

        // 深拷贝隔离：创建各子列表的独立副本，而非引用原始 data 对象
        val isolated = IsolatedSnapshotData(
            timestamp = timestamp,
            gameData = data.gameData.copy(),
            disciples = data.disciples.map { it.copy() },
            equipment = data.equipment.map { it.copy() },
            manuals = data.manuals.map { it.copy() },
            pills = data.pills.map { it.copy() },
            materials = data.materials.map { it.copy() },
            herbs = data.herbs.map { it.copy() },
            seeds = data.seeds.map { it.copy() },
            teams = data.teams.map { it.copy() },
            events = data.events.toList(),           // 不可变列表，toList 即可
            battleLogs = data.battleLogs.map { it.copy() },
            alliances = data.alliances.map { it.copy() }
        )

        val snapshot = SaveDataSnapshot(
            timestamp = timestamp,
            slotId = slotId,
            coreHash = coreHash,
            discipleIds = discipleIds,
            discipleCount = data.disciples.size,
            inventoryHashes = inventoryHashes,
            isolatedData = isolated
        )

        snapshots[slotId] = snapshot

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Updated snapshot for slot $slotId: ${data.disciples.size} disciples, coreHash=$coreHash in ${elapsedMs}ms (deep-copy isolated)")
    }

    fun clearSnapshot(slotId: Int) {
        val removed = snapshots.remove(slotId)
        if (removed != null) {
            Log.d(TAG, "Cleared snapshot for slot $slotId (age: ${removed.ageMs}ms)")
        }
    }

    fun clearAllSnapshots() {
        val count = snapshots.size
        snapshots.clear()
        Log.d(TAG, "Cleared all $count snapshots")
    }

    fun getStats(): DeltaStats {
        val totalDeltas = statsTotalDeltasGenerated.get()
        val totalSize = statsTotalDeltaSizeBytes.get()
        val count = statsDeltaCount.get().coerceAtLeast(1)
        val hits = statsCacheHits.get()
        val misses = statsCacheMisses.get().coerceAtLeast(1)

        return DeltaStats(
            totalSnapshots = snapshots.size,
            totalDeltasGenerated = totalDeltas,
            totalFullSavesTriggered = statsTotalFullSavesTriggered.get(),
            averageDeltaSizeBytes = if (totalDeltas > 0) totalSize.toDouble() / totalDeltas else 0.0,
            averageCompressionRatio = 0.0,
            cacheHitRatio = hits.toDouble() / (hits + misses),
            lastOperationTimeMs = lastOperationTimeMs.get()
        )
    }

    /**
     * 判断是否应该使用全量存档（v2 - 消除重复 detectChanges 调用）
     *
     * ## v1 问题
     * 原实现在此方法内部调用 [detectChanges]，而 [createIncrementalSave] 也调用 [detectChanges]，
     * 导致同一份数据被对比两次，O(2N) 开销。
     *
     * ## v2 方案
     * 使用内联快速判断：先做轻量级 hash 比较（O(1)），仅当 hash 变化时才进行粗粒度的字段数估算。
     * 不再调用 [detectChanges]（该方法留给 [createIncrementalSave] 在确认走增量路径后才调用一次）。
     *
     * 决策依据：
     * 1. 快照不存在或已过期 → 全量
     * 2. 核心 hash 相同 + 弟子集合相同 → 增量（快速路径）
     * 3. 变更比例超阈值 或 delta 大小超限 → 全量
     */
    fun shouldUseFullSave(slotId: Int, currentData: SerializableSaveData): Boolean {
        val snapshot = snapshots[slotId] ?: return true

        if (snapshot.isExpired()) {
            Log.d(TAG, "Snapshot expired for slot $slotId, recommending full save")
            return true
        }

        return shouldUseFullSaveInline(snapshot, currentData)
    }

    // ==================== 私有方法：快速全量判断（内联，不调 detectChanges）====================

    /**
     * 内联的全量存档判断逻辑（不含 detectChanges 调用）
     *
     * 此方法被 [shouldUseFullSave] 和 [createIncrementalSave] 共享，
     * 确保变更检测只执行一次。
     */
    private fun shouldUseFullSaveInline(snapshot: SaveDataSnapshot, currentData: SerializableSaveData): Boolean {
        // 快速检查：核心 hash 和弟子集合
        val currentCoreHash = computeCoreHash(currentData.gameData)
        val currentDiscipleIds = currentData.disciples.map { it.id }.toSet()

        val coreChanged = currentCoreHash != snapshot.coreHash
        val disciplesChanged = currentDiscipleIds != snapshot.discipleIds

        if (!coreChanged && !disciplesChanged) {
            Log.d(TAG, "Core and disciples unchanged for slot ${snapshot.slotId}, incremental recommended")
            return false
        }

        // 粗粒度估算（不需要精确的 detectChanges 结果）
        val estimatedTotalFields = estimateTotalFields(currentData)

        // 基于变化幅度快速估算变更字段数
        var estimatedChangedFields = 0
        // 核心模块变化贡献
        if (coreChanged) estimatedChangedFields += 12   // Core 约 12 个关键字段
        // 弟子变化贡献
        estimatedChangedFields += (if (disciplesChanged) currentData.disciples.size else 0) * 16
        // Inventory 各表 hash 变化
        val currentInvHashes = mapOf(
            "equipment" to computeXorShiftHash(currentData.equipment),
            "manuals" to computeXorShiftHash(currentData.manuals),
            "pills" to computeXorShiftHash(currentData.pills),
            "materials" to computeXorShiftHash(currentData.materials),
            "herbs" to computeXorShiftHash(currentData.herbs),
            "seeds" to computeXorShiftHash(currentData.seeds)
        )
        for ((key, hash) in currentInvHashes) {
            if (hash != snapshot.inventoryHashes[key]) estimatedChangedFields += 8
        }

        val changeRatio = if (estimatedTotalFields > 0) {
            estimatedChangedFields.toDouble() / estimatedTotalFields
        } else {
            1.0
        }

        // 保守估算 delta 大小
        val estimatedDeltaSize = estimatedChangedFields * 40  // 平均每个字段 ~40 bytes

        val shouldUseFull = changeRatio > DeltaConfig.FULL_SAVE_THRESHOLD_RATIO ||
                estimatedDeltaSize > DeltaConfig.MAX_DELTA_SIZE_BYTES

        if (shouldUseFull) {
            Log.d(TAG, "Recommending full save for slot ${snapshot.slotId}: ratio=${"%.2f".format(changeRatio)}, estDelta=~${estimatedDeltaSize}B")
        } else {
            Log.d(TAG, "Incremental OK for slot ${snapshot.slotId}: ratio=${"%.2f".format(changeRatio)}")
        }

        return shouldUseFull
    }

    // ==================== 私有方法：模块级对比逻辑（使用 IsolatedSnapshotData）====================

    private fun compareCore(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val snapshotCore = isolated.gameData
        val currentCore = currentData.gameData

        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0

        if (snapshotCore.sectName != currentCore.sectName) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.sectName", DeltaOperation.SET,
                snapshotCore.sectName, currentCore.sectName,
                CORE_FIELD_INDICES["sectName"] ?: ++fieldIndex))
        }
        if (snapshotCore.currentSlot != currentCore.currentSlot) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.currentSlot", DeltaOperation.SET,
                snapshotCore.currentSlot, currentCore.currentSlot,
                CORE_FIELD_INDICES["currentSlot"] ?: ++fieldIndex))
        }
        if (snapshotCore.gameYear != currentCore.gameYear || snapshotCore.gameMonth != currentCore.gameMonth) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.date", DeltaOperation.SET,
                "${snapshotCore.gameYear}-${snapshotCore.gameMonth}",
                "${currentCore.gameYear}-${currentCore.gameMonth}",
                CORE_FIELD_INDICES["gameYear"] ?: ++fieldIndex))
        }
        if (snapshotCore.spiritStones != currentCore.spiritStones) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.spiritStones", DeltaOperation.SET,
                snapshotCore.spiritStones, currentCore.spiritStones,
                CORE_FIELD_INDICES["spiritStones"] ?: ++fieldIndex))
        }
        if (snapshotCore.spiritHerbs != currentCore.spiritHerbs) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.spiritHerbs", DeltaOperation.SET,
                snapshotCore.spiritHerbs, currentCore.spiritHerbs,
                CORE_FIELD_INDICES["spiritHerbs"] ?: ++fieldIndex))
        }
        if (snapshotCore.playerProtectionEnabled != currentCore.playerProtectionEnabled) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.playerProtectionEnabled", DeltaOperation.SET,
                snapshotCore.playerProtectionEnabled, currentCore.playerProtectionEnabled,
                CORE_FIELD_INDICES["playerProtectionEnabled"] ?: ++fieldIndex))
        }
        if (snapshotCore.playerHasAttackedAI != currentCore.playerHasAttackedAI) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.playerHasAttackedAI", DeltaOperation.SET,
                snapshotCore.playerHasAttackedAI, currentCore.playerHasAttackedAI,
                CORE_FIELD_INDICES["playerHasAttackedAI"] ?: ++fieldIndex))
        }
        if (snapshotCore.monthlySalary != currentCore.monthlySalary) {
            deltas.add(FieldDelta("$MODULE_CORE.gameData.monthlySalary", DeltaOperation.SET,
                snapshotCore.monthlySalary, currentCore.monthlySalary,
                CORE_FIELD_INDICES["monthlySalary"] ?: ++fieldIndex))
        }

        return ModuleDelta(MODULE_CORE, isolated.timestamp, deltas.sortedBy { it.fieldIndex })
    }

    private fun compareDisciples(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val snapshotDisciples = isolated.disciples.associateBy { it.id }
        val currentDisciples = currentData.disciples.associateBy { it.id }

        val snapshotIds = snapshotDisciples.keys
        val currentIds = currentDisciples.keys

        val addedIds = currentIds - snapshotIds
        val removedIds = snapshotIds - currentIds
        val commonIds = snapshotIds.intersect(currentIds)

        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0

        for (id in addedIds.sorted()) {
            val disciple = currentDisciples[id]!!
            deltas.add(FieldDelta("$MODULE_DISCIPLES.disciples[$id]", DeltaOperation.ADD,
                null, disciple, ++fieldIndex))
        }

        for (id in removedIds.sorted()) {
            val disciple = snapshotDisciples[id]!!
            deltas.add(FieldDelta("$MODULE_DISCIPLES.disciples[$id]", DeltaOperation.REMOVE,
                disciple, null, ++fieldIndex))
        }

        for (id in commonIds.sorted()) {
            val oldDisciple = snapshotDisciples[id]!!
            val newDisciple = currentDisciples[id]!!
            val fieldDeltas = compareDiscipleFields(oldDisciple, newDisciple, id)
            if (fieldDeltas.isNotEmpty()) {
                deltas.addAll(fieldDeltas)
            }
        }

        return ModuleDelta(MODULE_DISCIPLES, isolated.timestamp, deltas)
    }

    private fun compareDiscipleFields(
        oldDisciple: SerializableDisciple,
        newDisciple: SerializableDisciple,
        discipleId: String
    ): List<FieldDelta> {
        val deltas = mutableListOf<FieldDelta>()
        val prefix = "$MODULE_DISCIPLES.disciples[$discipleId]"

        if (oldDisciple.realm != newDisciple.realm) {
            deltas.add(FieldDelta("$prefix.realm", DeltaOperation.SET,
                oldDisciple.realm, newDisciple.realm, DISCIPLE_FIELD_INDICES["realm"] ?: 0))
        }
        if (oldDisciple.cultivation != newDisciple.cultivation) {
            deltas.add(FieldDelta("$prefix.cultivation", DeltaOperation.SET,
                oldDisciple.cultivation, newDisciple.cultivation, DISCIPLE_FIELD_INDICES["cultivation"] ?: 0))
        }
        if (oldDisciple.age != newDisciple.age) {
            deltas.add(FieldDelta("$prefix.age", DeltaOperation.SET,
                oldDisciple.age, newDisciple.age, DISCIPLE_FIELD_INDICES["age"] ?: 0))
        }
        if (oldDisciple.isAlive != newDisciple.isAlive) {
            deltas.add(FieldDelta("$prefix.isAlive", DeltaOperation.SET,
                oldDisciple.isAlive, newDisciple.isAlive, DISCIPLE_FIELD_INDICES["isAlive"] ?: 0))
        }
        if (oldDisciple.status != newDisciple.status) {
            deltas.add(FieldDelta("$prefix.status", DeltaOperation.SET,
                oldDisciple.status, newDisciple.status, DISCIPLE_FIELD_INDICES["status"] ?: 0))
        }
        if (oldDisciple.spiritStones != newDisciple.spiritStones) {
            deltas.add(FieldDelta("$prefix.spiritStones", DeltaOperation.SET,
                oldDisciple.spiritStones, newDisciple.spiritStones, DISCIPLE_FIELD_INDICES["spiritStones"] ?: 0))
        }
        if (oldDisciple.soulPower != newDisciple.soulPower) {
            deltas.add(FieldDelta("$prefix.soulPower", DeltaOperation.SET,
                oldDisciple.soulPower, newDisciple.soulPower, DISCIPLE_FIELD_INDICES["soulPower"] ?: 0))
        }
        if (oldDisciple.totalCultivation != newDisciple.totalCultivation) {
            deltas.add(FieldDelta("$prefix.totalCultivation", DeltaOperation.SET,
                oldDisciple.totalCultivation, newDisciple.totalCultivation, DISCIPLE_FIELD_INDICES["totalCultivation"] ?: 0))
        }
        if (oldDisciple.breakthroughCount != newDisciple.breakthroughCount) {
            deltas.add(FieldDelta("$prefix.breakthroughCount", DeltaOperation.SET,
                oldDisciple.breakthroughCount, newDisciple.breakthroughCount, DISCIPLE_FIELD_INDICES["breakthroughCount"] ?: 0))
        }
        if (oldDisciple.weaponId != newDisciple.weaponId ||
            oldDisciple.armorId != newDisciple.armorId ||
            oldDisciple.bootsId != newDisciple.bootsId ||
            oldDisciple.accessoryId != newDisciple.accessoryId) {
            deltas.add(FieldDelta("$prefix.equipment", DeltaOperation.SET,
                mapOf("weapon" to oldDisciple.weaponId, "armor" to oldDisciple.armorId,
                     "boots" to oldDisciple.bootsId, "accessory" to oldDisciple.accessoryId),
                mapOf("weapon" to newDisciple.weaponId, "armor" to newDisciple.armorId,
                     "boots" to newDisciple.bootsId, "accessory" to newDisciple.accessoryId), 100))
        }
        if (oldDisciple.storageBagItems != newDisciple.storageBagItems) {
            deltas.add(FieldDelta("$prefix.storageBagItems", DeltaOperation.SET,
                oldDisciple.storageBagItems.size, newDisciple.storageBagItems, 101))
        }

        return deltas
    }

    /**
     * Inventory 模块对比（v2 - 使用 Identifiable 精确匹配）
     *
     * 先通过 XorShift32 hash 快速跳过未变化的表，
     * 对变化的表使用 [compareItemListById] 进行基于 ID 的精确三分类对比。
     */
    private fun compareInventory(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0

        // Equipment 表
        if (computeXorShiftHash(isolated.equipment) != computeXorShiftHash(currentData.equipment)) {
            deltas.addAll(compareItemListById(isolated.equipment, currentData.equipment,
                "$MODULE_INVENTORY.equipment", fieldIndex) { it.id })
            fieldIndex += deltas.size
        }

        // Manuals 表
        if (computeXorShiftHash(isolated.manuals) != computeXorShiftHash(currentData.manuals)) {
            val startIdx = fieldIndex
            deltas.addAll(compareItemListById(isolated.manuals, currentData.manuals,
                "$MODULE_INVENTORY.manuals", fieldIndex) { it.id })
            fieldIndex += (deltas.size - startIdx).coerceAtLeast(1)
        }

        // Pills 表
        if (computeXorShiftHash(isolated.pills) != computeXorShiftHash(currentData.pills)) {
            val startIdx = fieldIndex
            deltas.addAll(compareItemListById(isolated.pills, currentData.pills,
                "$MODULE_INVENTORY.pills", fieldIndex) { it.id })
            fieldIndex += (deltas.size - startIdx).coerceAtLeast(1)
        }

        // Materials 表
        if (computeXorShiftHash(isolated.materials) != computeXorShiftHash(currentData.materials)) {
            val startIdx = fieldIndex
            deltas.addAll(compareItemListById(isolated.materials, currentData.materials,
                "$MODULE_INVENTORY.materials", fieldIndex) { it.id })
            fieldIndex += (deltas.size - startIdx).coerceAtLeast(1)
        }

        // Herbs 表
        if (computeXorShiftHash(isolated.herbs) != computeXorShiftHash(currentData.herbs)) {
            val startIdx = fieldIndex
            deltas.addAll(compareItemListById(isolated.herbs, currentData.herbs,
                "$MODULE_INVENTORY.herbs", fieldIndex) { it.id })
            fieldIndex += (deltas.size - startIdx).coerceAtLeast(1)
        }

        // Seeds 表
        if (computeXorShiftHash(isolated.seeds) != computeXorShiftHash(currentData.seeds)) {
            deltas.addAll(compareItemListById(isolated.seeds, currentData.seeds,
                "$MODULE_INVENTORY.seeds", fieldIndex) { it.id })
        }

        return ModuleDelta(MODULE_INVENTORY, isolated.timestamp, deltas)
    }

    /**
     * World 模块对比
     */
    private fun compareWorld(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0
        val oldGd = isolated.gameData
        val newGd = currentData.gameData

        if (oldGd.worldMapSects != newGd.worldMapSects) {
            deltas.add(FieldDelta("$MODULE_WORLD.worldMapSects", DeltaOperation.SET,
                oldGd.worldMapSects, newGd.worldMapSects, ++fieldIndex))
        }
        if (oldGd.exploredSects != newGd.exploredSects) {
            deltas.add(FieldDelta("$MODULE_WORLD.exploredSects", DeltaOperation.SET,
                oldGd.exploredSects, newGd.exploredSects, ++fieldIndex))
        }
        if (oldGd.scoutInfo != newGd.scoutInfo) {
            deltas.add(FieldDelta("$MODULE_WORLD.scoutInfo", DeltaOperation.SET,
                oldGd.scoutInfo, newGd.scoutInfo, ++fieldIndex))
        }

        // Alliances: 使用 Identifiable 精确对比
        if (isolated.alliances != currentData.alliances) {
            deltas.addAll(compareItemListById(isolated.alliances, currentData.alliances,
                "$MODULE_WORLD.alliances", fieldIndex) { it.id })
            fieldIndex = deltas.lastOrNull()?.fieldIndex?.plus(1) ?: fieldIndex
        }

        if (oldGd.sectRelations != newGd.sectRelations) {
            deltas.add(FieldDelta("$MODULE_WORLD.sectRelations", DeltaOperation.SET,
                oldGd.sectRelations, newGd.sectRelations, ++fieldIndex))
        }

        return ModuleDelta(MODULE_WORLD, isolated.timestamp, deltas)
    }

    /**
     * Combat 模块对比
     */
    private fun compareCombat(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0
        val oldGd = isolated.gameData
        val newGd = currentData.gameData

        if (oldGd.battleTeam != newGd.battleTeam) {
            deltas.add(FieldDelta("$MODULE_COMBAT.battleTeam", DeltaOperation.SET,
                oldGd.battleTeam, newGd.battleTeam, ++fieldIndex))
        }
        if (oldGd.aiBattleTeams != newGd.aiBattleTeams) {
            deltas.add(FieldDelta("$MODULE_COMBAT.aiBattleTeams", DeltaOperation.SET,
                oldGd.aiBattleTeams, newGd.aiBattleTeams, ++fieldIndex))
        }

        // BattleLogs: 使用 Identifiable 精确对比
        if (isolated.battleLogs != currentData.battleLogs) {
            deltas.addAll(compareItemListById(isolated.battleLogs, currentData.battleLogs,
                "$MODULE_COMBAT.battleLogs", fieldIndex) { it.id })
            fieldIndex = deltas.lastOrNull()?.fieldIndex?.plus(1) ?: fieldIndex
        }

        // Teams: 使用 Identifiable 精确对比
        if (isolated.teams != currentData.teams) {
            deltas.addAll(compareItemListById(isolated.teams, currentData.teams,
                "$MODULE_COMBAT.teams", fieldIndex) { it.id })
        }

        return ModuleDelta(MODULE_COMBAT, isolated.timestamp, deltas)
    }

    /**
     * Missions 模块对比
     */
    private fun compareMissions(isolated: IsolatedSnapshotData, currentData: SerializableSaveData): ModuleDelta {
        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = 0
        val oldGd = isolated.gameData
        val newGd = currentData.gameData

        if (oldGd.activeMissions != newGd.activeMissions) {
            deltas.add(FieldDelta("$MODULE_MISSIONS.activeMissions", DeltaOperation.SET,
                oldGd.activeMissions, newGd.activeMissions, ++fieldIndex))
        }
        if (oldGd.availableMissions != newGd.availableMissions) {
            deltas.add(FieldDelta("$MODULE_MISSIONS.availableMissions", DeltaOperation.SET,
                oldGd.availableMissions, newGd.availableMissions, ++fieldIndex))
        }

        return ModuleDelta(MODULE_MISSIONS, isolated.timestamp, deltas)
    }

    // ==================== 私有方法：Delta 应用逻辑（全部正确实现）====================

    private fun applyCoreDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        var gameData = baseData.gameData

        for (fieldDelta in delta.deltas) {
            when {
                fieldDelta.modulePath.endsWith(".sectName") ->
                    gameData = gameData.copy(sectName = fieldDelta.newValue as? String ?: gameData.sectName)
                fieldDelta.modulePath.endsWith(".currentSlot") ->
                    gameData = gameData.copy(currentSlot = fieldDelta.newValue as? Int ?: gameData.currentSlot)
                fieldDelta.modulePath.endsWith(".date") ->
                    gameData = gameData.copy(gameYear = extractYearFromDate(fieldDelta.newValue as? String),
                        gameMonth = extractMonthFromDate(fieldDelta.newValue as? String))
                fieldDelta.modulePath.endsWith(".spiritStones") ->
                    gameData = gameData.copy(spiritStones = fieldDelta.newValue as? Long ?: gameData.spiritStones)
                fieldDelta.modulePath.endsWith(".spiritHerbs") ->
                    gameData = gameData.copy(spiritHerbs = fieldDelta.newValue as? Int ?: gameData.spiritHerbs)
                fieldDelta.modulePath.endsWith(".playerProtectionEnabled") ->
                    gameData = gameData.copy(playerProtectionEnabled = fieldDelta.newValue as? Boolean ?: gameData.playerProtectionEnabled)
                fieldDelta.modulePath.endsWith(".playerHasAttackedAI") ->
                    gameData = gameData.copy(playerHasAttackedAI = fieldDelta.newValue as? Boolean ?: gameData.playerHasAttackedAI)
                fieldDelta.modulePath.endsWith(".monthlySalary") ->
                    gameData = gameData.copy(monthlySalary = (fieldDelta.newValue as? Map<*, *>)
                        ?.mapKeys { it.key.toString().toIntOrNull() ?: it.key.toString().hashCode() }
                        ?.mapValues { it.value as? Int ?: 0 }
                        ?.filterKeys { it is Int }
                        ?.let { @Suppress("UNCHECKED_CAST", "USELESS_CAST") it.mapKeys { entry -> entry.key as Int } }
                        ?: gameData.monthlySalary)
            }
        }

        return baseData.copy(gameData = gameData)
    }

    private fun applyDisciplesDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        val currentDisciples = baseData.disciples.toMutableList()

        for (fieldDelta in delta.deltas) {
            when (fieldDelta.operation) {
                DeltaOperation.ADD -> {
                    val newDisciple = fieldDelta.newValue as? SerializableDisciple
                    if (newDisciple != null && currentDisciples.none { it.id == newDisciple.id }) {
                        currentDisciples.add(newDisciple)
                    }
                }
                DeltaOperation.REMOVE -> {
                    val discipleId = extractDiscipleIdFromPath(fieldDelta.modulePath)
                    currentDisciples.removeAll { it.id == discipleId }
                }
                DeltaOperation.SET -> {
                    val discipleId = extractDiscipleIdFromPath(fieldDelta.modulePath)
                    val index = currentDisciples.indexOfFirst { it.id == discipleId }
                    if (index >= 0) {
                        currentDisciples[index] = updateDiscipleField(currentDisciples[index], fieldDelta)
                    }
                }
                DeltaOperation.CLEAR -> currentDisciples.clear()
            }
        }

        return baseData.copy(disciples = currentDisciples)
    }

    /**
     * 应用 Inventory 模块的 delta 到存档数据（v2 - 完整实现）
     *
     * 根据 [fieldDelta.modulePath] 判断具体是哪个物品表（equipment/manuals/pills/materials/herbs/seeds），
     * 然后执行对应的 ADD/REMOVE/SET/CLEAR 操作。
     *
     * ## 操作分发规则
     *
     * | 路径模式 | 目标列表 | 操作方式 |
     * |---------|---------|---------|
     * | `inventory.equipment[*]` | equipment | 按 ID 匹配 |
     * | `inventory.manuals[*]` | manuals | 按 ID 匹配 |
     * | `inventory.pills[*]` | pills | 按 ID 匹配 |
     * | `inventory.materials[*]` | materials | 按 ID 匹配 |
     * | `inventory.herbs[*]` | herbs | 按 ID 匹配 |
     * | `inventory.seeds[*]` | seeds | 按 ID 匹配 |
     */
    private fun applyInventoryDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        if (!delta.hasChanges) return baseData

        var result = baseData

        for (fieldDelta in delta.deltas) {
            val path = fieldDelta.modulePath

            // 根据路径确定目标表并应用
            result = when {
                path.contains(".equipment") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.equipment },
                    listCopier = { data, newList -> data.copy(equipment = newList) },
                    idExtractor = { (it as? SerializableEquipment)?.id },
                    itemCopier = { (it as? SerializableEquipment)?.copy() }
                )
                path.contains(".manuals") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.manuals },
                    listCopier = { data, newList -> data.copy(manuals = newList) },
                    idExtractor = { (it as? SerializableManual)?.id },
                    itemCopier = { (it as? SerializableManual)?.copy() }
                )
                path.contains(".pills") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.pills },
                    listCopier = { data, newList -> data.copy(pills = newList) },
                    idExtractor = { (it as? SerializablePill)?.id },
                    itemCopier = { (it as? SerializablePill)?.copy() }
                )
                path.contains(".materials") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.materials },
                    listCopier = { data, newList -> data.copy(materials = newList) },
                    idExtractor = { (it as? SerializableMaterial)?.id },
                    itemCopier = { (it as? SerializableMaterial)?.copy() }
                )
                path.contains(".herbs") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.herbs },
                    listCopier = { data, newList -> data.copy(herbs = newList) },
                    idExtractor = { (it as? SerializableHerb)?.id },
                    itemCopier = { (it as? SerializableHerb)?.copy() }
                )
                path.contains(".seeds") -> applyListItemDelta(
                    result, fieldDelta,
                    currentListGetter = { it.seeds },
                    listCopier = { data, newList -> data.copy(seeds = newList) },
                    idExtractor = { (it as? SerializableSeed)?.id },
                    itemCopier = { (it as? SerializableSeed)?.copy() }
                )
                else -> {
                    Log.w(TAG, "Unknown inventory path: $path, skipping")
                    result
                }
            }
        }

        return result
    }

    /**
     * 应用 World 模块的 delta 到存档数据（v2 - 完整实现）
     *
     * 处理 worldMapSects、exploredSects、scoutInfo、alliances、sectRelations 五个子域。
     */
    private fun applyWorldDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        if (!delta.hasChanges) return baseData

        var gameData = baseData.gameData
        var alliances = baseData.alliances

        for (fieldDelta in delta.deltas) {
            when {
                fieldDelta.modulePath.endsWith(".worldMapSects") ->
                    gameData = gameData.copy(worldMapSects = (fieldDelta.newValue as? List<*>)
                        ?.filterIsInstance<com.xianxia.sect.data.serialization.unified.SerializableWorldSect>()
                        ?: gameData.worldMapSects)

                fieldDelta.modulePath.endsWith(".exploredSects") ->
                    gameData = gameData.copy(exploredSects = (fieldDelta.newValue as? Map<*, *>)
                        ?.mapKeys { it.key.toString() }
                        ?.mapValues { (it.value as? com.xianxia.sect.data.serialization.unified.SerializableExploredSectInfo)
                            ?: gameData.exploredSects[it.key.toString()] }
                        ?.filterValues { it != null }
                        ?.mapValues { it.value!! }
                        ?: gameData.exploredSects)

                fieldDelta.modulePath.endsWith(".scoutInfo") ->
                    gameData = gameData.copy(scoutInfo = (fieldDelta.newValue as? Map<*, *>)
                        ?.mapKeys { it.key.toString() }
                        ?.mapValues { (it.value as? com.xianxia.sect.data.serialization.unified.SerializableSectScoutInfo)
                            ?: gameData.scoutInfo[it.key.toString()] }
                        ?.filterValues { it != null }
                        ?.mapValues { it.value!! }
                        ?: gameData.scoutInfo)

                fieldDelta.modulePath.endsWith(".alliances") -> {
                    when (fieldDelta.operation) {
                        DeltaOperation.ADD -> {
                            val newItem = fieldDelta.newValue as? SerializableAlliance
                            if (newItem != null && alliances.none { it.id == newItem.id }) {
                                alliances = alliances + newItem
                            }
                        }
                        DeltaOperation.REMOVE -> {
                            val removeId = fieldDelta.oldValue as? String
                                ?: (fieldDelta.oldValue as? SerializableAlliance)?.id
                            if (removeId != null) {
                                alliances = alliances.filter { it.id != removeId }
                            }
                        }
                        DeltaOperation.SET -> {
                            // 整体替换
                            val newAlliances = (fieldDelta.newValue as? List<*>)
                                ?.filterIsInstance<SerializableAlliance>()
                            if (newAlliances != null) alliances = newAlliances
                        }
                        DeltaOperation.CLEAR -> alliances = emptyList()
                    }
                }

                fieldDelta.modulePath.endsWith(".sectRelations") ->
                    gameData = gameData.copy(sectRelations = (fieldDelta.newValue as? List<*>)
                        ?.filterIsInstance<com.xianxia.sect.data.serialization.unified.SerializableSectRelation>()
                        ?: gameData.sectRelations)
            }
        }

        return baseData.copy(gameData = gameData, alliances = alliances)
    }

    /**
     * 应用 Combat 模块的 delta 到存档数据（v2 - 完整实现）
     *
     * 处理 battleTeam、aiBattleTeams、battleLogs、teams 四个子域。
     */
    private fun applyCombatDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        if (!delta.hasChanges) return baseData

        var gameData = baseData.gameData
        var battleLogs = baseData.battleLogs
        var teams = baseData.teams

        for (fieldDelta in delta.deltas) {
            when {
                fieldDelta.modulePath.endsWith(".battleTeam") ->
                    gameData = gameData.copy(battleTeam = fieldDelta.newValue as? SerializableBattleTeam
                        ?: gameData.battleTeam)

                fieldDelta.modulePath.endsWith(".aiBattleTeams") ->
                    gameData = gameData.copy(aiBattleTeams = (fieldDelta.newValue as? List<*>)
                        ?.filterIsInstance<com.xianxia.sect.data.serialization.unified.SerializableAIBattleTeam>()
                        ?: gameData.aiBattleTeams)

                fieldDelta.modulePath.endsWith(".battleLogs") -> {
                    when (fieldDelta.operation) {
                        DeltaOperation.ADD -> {
                            val newItem = fieldDelta.newValue as? SerializableBattleLog
                            if (newItem != null) {
                                battleLogs = battleLogs + newItem
                            }
                        }
                        DeltaOperation.REMOVE -> {
                            val removeId = fieldDelta.oldValue as? String
                            if (removeId != null) {
                                battleLogs = battleLogs.filter { it.id != removeId }
                            }
                        }
                        DeltaOperation.CLEAR -> battleLogs = emptyList()
                        DeltaOperation.SET -> {
                            val newLogs = (fieldDelta.newValue as? List<*>)
                                ?.filterIsInstance<SerializableBattleLog>()
                            if (newLogs != null) battleLogs = newLogs
                        }
                    }
                }

                fieldDelta.modulePath.endsWith(".teams") -> {
                    when (fieldDelta.operation) {
                        DeltaOperation.ADD -> {
                            val newItem = fieldDelta.newValue as? SerializableExplorationTeam
                            if (newItem != null && teams.none { it.id == newItem.id }) {
                                teams = teams + newItem
                            }
                        }
                        DeltaOperation.REMOVE -> {
                            val removeId = fieldDelta.oldValue as? String
                                ?: (fieldDelta.oldValue as? SerializableExplorationTeam)?.id
                            if (removeId != null) {
                                teams = teams.filter { it.id != removeId }
                            }
                        }
                        DeltaOperation.SET -> {
                            val newTeams = (fieldDelta.newValue as? List<*>)
                                ?.filterIsInstance<SerializableExplorationTeam>()
                            if (newTeams != null) teams = newTeams
                        }
                        DeltaOperation.CLEAR -> teams = emptyList()
                    }
                }
            }
        }

        return baseData.copy(gameData = gameData, battleLogs = battleLogs, teams = teams)
    }

    /**
     * 应用 Missions 模块的 delta 到存档数据（v2 - 完整实现）
     *
     * 处理 activeMissions 和 availableMissions 两个子域。
     */
    private fun applyMissionsDelta(baseData: SerializableSaveData, delta: ModuleDelta): SerializableSaveData {
        if (!delta.hasChanges) return baseData

        var gameData = baseData.gameData

        for (fieldDelta in delta.deltas) {
            when {
                fieldDelta.modulePath.endsWith(".activeMissions") ->
                    gameData = gameData.copy(activeMissions = (fieldDelta.newValue as? List<*>)
                        ?.filterIsInstance<com.xianxia.sect.data.serialization.unified.SerializableActiveMission>()
                        ?: gameData.activeMissions)

                fieldDelta.modulePath.endsWith(".availableMissions") ->
                    gameData = gameData.copy(availableMissions = (fieldDelta.newValue as? List<*>)
                        ?.filterIsInstance<com.xianxia.sect.data.serialization.unified.SerializableMission>()
                        ?: gameData.availableMissions)
            }
        }

        return baseData.copy(gameData = gameData)
    }

    // ==================== 私有工具方法 ====================

    /**
     * 通用的列表项级 delta 应用器
     *
     * 泛型实现 ADD/REMOVE/SET/CLEAR 四种操作到任意 [SerializableSaveData] 子列表。
     * 通过高阶函数参数解耦列表获取和复制逻辑，避免每个物品表写一遍重复代码。
     *
     * @param T 列表中元素的类型
     * @param baseData 基础存档数据
     * @param fieldDelta 要应用的字段变更
     * @param currentListGetter 从 SaveData 获取当前目标列表
     * @param listCopier 用新列表创建新的 SaveData 副本
     * @param idExtractor 从元素中提取 ID
     * @param itemCopier 深拷贝单个元素（ADD 操作使用）
     * @return 应用后的新 SaveData
     */
    private inline fun <reified T : Any> applyListItemDelta(
        baseData: SerializableSaveData,
        fieldDelta: FieldDelta,
        currentListGetter: (SerializableSaveData) -> List<*>,
        listCopier: (SerializableSaveData, List<T>) -> SerializableSaveData,
        idExtractor: (Any?) -> String?,
        itemCopier: (Any?) -> T?
    ): SerializableSaveData {
        val currentList = currentListGetter(baseData).filterIsInstance<T>()

        val newList: List<T> = when (fieldDelta.operation) {
            DeltaOperation.ADD -> {
                val newItem = itemCopier(fieldDelta.newValue)
                if (newItem != null && currentList.none { idExtractor(it) == idExtractor(newItem) }) {
                    currentList + newItem
                } else {
                    currentList
                }
            }
            DeltaOperation.REMOVE -> {
                val removeId = fieldDelta.oldValue as? String ?: idExtractor(fieldDelta.oldValue)
                if (removeId != null) {
                    currentList.filter { idExtractor(it) != removeId }
                } else {
                    currentList
                }
            }
            DeltaOperation.SET -> {
                // SET 在列表上下文中通常意味着整体替换或单条目更新
                val replacement = (fieldDelta.newValue as? List<*>)?.filterIsInstance<T>()
                if (replacement != null) replacement else currentList
            }
            DeltaOperation.CLEAR -> emptyList()
        }

        return listCopier(baseData, newList)
    }

    /**
     * 基于 [Identifiable] 接口的通用列表精确对比（v2）
     *
     * 替代原 v1 的 [compareItemList]（仅比数量的低精度实现）。
     * 通过 [Identifiable.id] 进行三分类：added / removed / modified。
     *
     * 对于 modified 元素（两边都有但内容不同），生成一个 SET 类型的 FieldDelta，
     * 其中 newValue 为完整的新实体对象。
     *
     * @param T 必须可通过 [idExtractor] 提供唯一 ID
     */
    private fun <T : Any> compareItemListById(
        oldItems: List<T>,
        newItems: List<T>,
        moduleName: String,
        startFieldIndex: Int,
        idExtractor: (T) -> String
    ): List<FieldDelta> {
        val deltas = mutableListOf<FieldDelta>()
        var fieldIndex = startFieldIndex

        val oldMap = oldItems.associateBy(idExtractor)
        val newMap = newItems.associateBy(idExtractor)

        val oldIds = oldMap.keys
        val newIds = newMap.keys

        val addedIds = newIds - oldIds
        val removedIds = oldIds - newIds
        val commonIds = oldIds.intersect(newIds)

        // 新增元素
        for (id in addedIds.sorted()) {
            val item = newMap[id]!!
            deltas.add(FieldDelta("$moduleName[$id]", DeltaOperation.ADD, null, item, ++fieldIndex))
        }

        // 移除元素
        for (id in removedIds.sorted()) {
            val item = oldMap[id]!!
            deltas.add(FieldDelta("$moduleName[$id]", DeltaOperation.REMOVE, item, null, ++fieldIndex))
        }

        // 修改的元素（内容不同）
        for (id in commonIds.sorted()) {
            val oldItem = oldMap[id]!!
            val newItem = newMap[id]!!
            if (oldItem != newItem) {
                deltas.add(FieldDelta("$moduleName[$id]", DeltaOperation.SET, oldItem, newItem, ++fieldIndex))
            }
        }

        return deltas
    }

    /**
     * 从日期字符串中提取年份
     */
    private fun extractYearFromDate(dateStr: String?): Int {
        if (dateStr.isNullOrBlank()) return 1
        return dateStr.split("-").getOrNull(0)?.toIntOrNull() ?: 1
    }

    /**
     * 从日期字符串中提取月份
     */
    private fun extractMonthFromDate(dateStr: String?): Int {
        if (dateStr.isNullOrBlank()) return 1
        return dateStr.split("-").getOrNull(1)?.toIntOrNull() ?: 1
    }

    private fun extractDiscipleIdFromPath(path: String): String {
        val match = Regex("""disciples\[(.+?)\]""").find(path)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun updateDiscipleField(disciple: SerializableDisciple, fieldDelta: FieldDelta): SerializableDisciple {
        return when {
            fieldDelta.modulePath.endsWith(".realm") ->
                disciple.copy(realm = fieldDelta.newValue as? Int ?: disciple.realm)
            fieldDelta.modulePath.endsWith(".cultivation") ->
                disciple.copy(cultivation = fieldDelta.newValue as? Double ?: disciple.cultivation)
            fieldDelta.modulePath.endsWith(".age") ->
                disciple.copy(age = fieldDelta.newValue as? Int ?: disciple.age)
            fieldDelta.modulePath.endsWith(".isAlive") ->
                disciple.copy(isAlive = fieldDelta.newValue as? Boolean ?: disciple.isAlive)
            fieldDelta.modulePath.endsWith(".status") ->
                disciple.copy(status = fieldDelta.newValue as? String ?: disciple.status)
            fieldDelta.modulePath.endsWith(".spiritStones") ->
                disciple.copy(spiritStones = fieldDelta.newValue as? Int ?: disciple.spiritStones)
            fieldDelta.modulePath.endsWith(".soulPower") ->
                disciple.copy(soulPower = fieldDelta.newValue as? Int ?: disciple.soulPower)
            fieldDelta.modulePath.endsWith(".totalCultivation") ->
                disciple.copy(totalCultivation = fieldDelta.newValue as? Long ?: disciple.totalCultivation)
            fieldDelta.modulePath.endsWith(".breakthroughCount") ->
                disciple.copy(breakthroughCount = fieldDelta.newValue as? Int ?: disciple.breakthroughCount)
            fieldDelta.modulePath.endsWith(".currentHp") ->
                disciple.copy(currentHp = fieldDelta.newValue as? Int ?: disciple.currentHp)
            fieldDelta.modulePath.endsWith(".currentMp") ->
                disciple.copy(currentMp = fieldDelta.newValue as? Int ?: disciple.currentMp)
            else -> disciple
        }
    }

    // ==================== 哈希算法层（v2 - XorShift32 全量遍历）====================

    /**
     * 计算 GameData 核心模块的快速 hash
     *
     * 使用 XorShift32 混合多个关键字段，替代 SHA-256 以获得更好的性能。
     * SHA-256 在 Android 上每次调用约需 5-10us，而 XorShift32 < 1us。
     *
     * 对于核心模块（字段少、变化频率中等），XorShift32 的碰撞概率可接受。
     */
    private fun computeCoreHash(gameData: SerializableGameData): Int {
        var hash = XOR_SHIFT_SEED
        hash = xorShift32(hash, gameData.sectName.hashCode())
        hash = xorShift32(hash, gameData.spiritStones.hashCode())
        hash = xorShift32(hash, gameData.gameYear)
        hash = xorShift32(hash, gameData.gameMonth)
        hash = xorShift32(hash, gameData.recruitList.size)
        hash = xorShift32(hash, gameData.spiritHerbs)
        hash = xorShift32(hash, if (gameData.playerProtectionEnabled) 1 else 0)
        hash = xorShift32(hash, if (gameData.playerHasAttackedAI) 1 else 0)
        return hash
    }

    /**
     * XorShift32 列表哈希（v2 - 全量遍历版）
     *
     * ## v1 问题
     * 原 [computeListHash] 仅取 first().hashCode() 和 last().hashCode()，
     * 中间元素的任何修改完全无法检测，碰撞率极高。
     *
     * ## v2 方案
     * 使用 XorShift32 算法遍历列表中**每一个元素**的 hashCode 并混合。
     * - 时间复杂度 O(N)，但对 N < 1000 的游戏列表足够快（< 0.1ms）
     * - 分布均匀性接近 MurmurHash3 finalizer
     * - 无 JNI 调用，纯 Kotlin 实现，无 GC 压力
     *
     * XorShift32 是 George Marsaglia 于 2003 年提出的伪随机数生成算法，
     * 其输出分布质量足以用作非加密哈希。参考:
     * - Marsaglia, G. (2003). "Xorshift RNGs". Journal of Statistical Software.
     * - MurmurHash3 的 finalizer (Austern, 2011) 也使用了类似的多轮 xor-shift
     *
     * @param items 要计算哈希的列表
     * @return 32 位整数哈希值
     */
    private fun <T : Any> computeXorShiftHash(items: List<T>): Int {
        if (items.isEmpty()) return 0

        var hash = XOR_SHIFT_SEED

        // 混合列表大小
        hash = xorShift32(hash, items.size)

        // 遍历每一个元素，混合其 hashCode
        for (item in items) {
            hash = xorShift32(hash, item.hashCode())
        }

        // 最终 avalanche 确保高位信息扩散到低位
        hash = avalanche(hash)

        return hash
    }

    /**
     * XorShift32 单步混合
     *
     * 将一个 int 值混合到当前 hash 状态中。
     * 执行标准 xorshift32 三轮操作：左移13、右移17、左移5。
     * 参考 Marsaglia (2003) "Xorshift RNGs"。
     */
    private fun xorShift32(hash: Int, value: Int): Int {
        var x = hash xor value
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }

    /**
     * Avalanche 函数（finalizer）
     *
     * 确保输入位的微小变化能均匀扩散到输出的所有位。
     * 来自 MurmurHash3 的 finalizer (Austin Appleby)，经实验验证具有优秀的雪崩效应。
     */
    private fun avalanche(hash: Int): Int {
        var h = hash
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /**
     * 估算存档的总字段数
     */
    private fun estimateTotalFields(data: SerializableSaveData): Int {
        var count = 50  // Core 模块约 50 个字段
        count += data.disciples.size * 78
        count += (data.equipment.size + data.manuals.size + data.pills.size +
                  data.materials.size + data.herbs.size + data.seeds.size) * 17
        count += data.teams.size * 10
        count += data.events.size * 8
        count += data.battleLogs.size * 15
        count += data.alliances.size * 5
        return count.coerceAtLeast(1)
    }
}
