@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.cache

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.memory.MemoryEventListener
import com.xianxia.sect.data.memory.MemoryPressureLevel
import com.xianxia.sect.data.memory.MemorySnapshot
import com.xianxia.sect.data.memory.DegradationStrategy
import com.xianxia.sect.data.cache.core.SimpleBloomFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * ## GameDataCacheManager - 游戏数据缓存管理器（薄门面模式）
 *
 * ### 架构设计（Extract Delegate Pattern）
 * ```
 * ┌─────────────────────────────────────────────────────────────┐
 * │                  GameDataCacheManager (Facade)              │
 * │  职责：协调者 / 门面                                          │
 * │  - 对外提供统一的缓存 API                                     │
 * │  - 协调内存缓存、磁盘缓存、写管道等组件                        │
 * │  - 处理内存压力响应和生命周期管理                              │
 * ├─────────────┬──────────────┬───────────────┬────────────────┤
 * │MemoryCacheCore│UnifiedDiskCache│WritePipeline  │ 其他组件       │
 * │(W-TinyLFU)   │(MMKV+文件)    │(写合并)        │                │
 * │+ BloomFilter │+ CRC32        │+ DirtyTracking │ PenetrationGuard│
 * │+ SWR         │+ Coalescer    │                │ CountMinSketch  │
 * └─────────────┴──────────────┴───────────────┴────────────────┘
 * ```
 *
 * ### 拆分后的组件职责
 *
 * **1. MemoryCacheCore** (独立组件，位于 `core` 子包)
 * - 纯内存缓存核心（W-TinyLFU 三分区策略）
 * - SWR (Stale-While-Revalidate) 过期策略
 * - BloomFilter 防缓存穿透
 * - 可独立复用于其他模块
 *
 * **2. SimpleBloomFilter** (独立组件，位于 `core` 子包)
 * - 简化版 BloomFilter 实现
 * - 双重哈希（Double Hashing）算法
 * - 可配置误判率和容量
 *
 * **3. TieredMemoryCache** (已有组件)
 * - 高级版 W-TinyLFU 实现（含 S3-FIFO Admission Gate）
 * - Count-Min Sketch 频率估计
 * - 动态热度阈值调整
 *
 * **4. UnifiedDiskCache** (已有组件)
 * - MMKV + 文件系统双层磁盘缓存
 * - CRC32 数据校验
 * - WriteCoalescer 写入合并
 *
 * **5. CachePenetrationGuard** (已有组件)
 * - 高级防穿透守卫（基于 CachePenetrationGuard）
 *
 * **6. GameDataCacheManager (本类 - 薄门面)**
 * - 协调上述所有组件的协作
 * - 提供向后兼容的公共 API
 * - 内存压力响应（Sigmoid 曲线）
 * - 统计信息聚合与监控
 * - DAO 工厂管理
 * - 生命周期管理（ComponentCallbacks2）
 *
 * ### 设计原则
 * - **单一职责**: 每个组件只负责一个明确的职责
 * - **开闭原则**: 可以通过组合新组件扩展功能，无需修改现有代码
 * - **依赖倒置**: 依赖抽象接口而非具体实现
 * - **最小知识**: 各组件之间通过门面协调，减少直接依赖
 *
 * ### 使用示例
 * ```kotlin
 * // 注入 GameDataCacheManager（自动组装所有子组件）
 * @Inject lateinit var cacheManager: GameDataCacheManager
 *
 * // 使用统一 API（内部自动协调多级缓存）
 * val data = cacheManager.get(CacheKey(...)) { loadDataFromDb() }
 * cacheManager.put(CacheKey(...), data)
 * ```
 *
 * ### 迁移说明
 * 此类从 v1 的上帝对象重构为 v2 的薄门面模式：
 * - 所有公共 API 保持完全兼容（无破坏性变更）
 * - 内部实现委托给独立的可复用组件
 * - 新代码应优先使用 MemoryCacheCore 或 TieredMemoryCache
 * - 旧代码无需修改，继续使用 GameDataCacheManager 即可
 */
@Singleton
class GameDataCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val config: CacheConfig = CacheConfig.DEFAULT,
    private val memoryManager: DynamicMemoryManager? = null
) : MemoryEventListener, ComponentCallbacks2 {
    companion object {
        private const val TAG = "GameDataCacheManager"
        private const val CLEANUP_INTERVAL_MS = 30_000L      // 30秒清理间隔
        private const val STATS_LOG_INTERVAL_MS = 300_000L   // 5分钟日志间隔
        private const val MEMORY_CHECK_INTERVAL_MS = 10_000L // 10秒检查一次内存压力
    }

    // ==================== DAO Wrapper 内部类（保持不变）====================

    interface GenericDao<T : Any> {
        suspend fun insert(entity: T)
        suspend fun deleteById(slotId: Int, id: String)
    }

    private class DiscipleDaoWrapper(private val dao: com.xianxia.sect.data.local.DiscipleDao) : GenericDao<com.xianxia.sect.core.model.Disciple> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Disciple) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.deleteById(slotId, id) }
    }

    private class EquipmentDaoWrapper(private val dao: com.xianxia.sect.data.local.EquipmentDao) : GenericDao<com.xianxia.sect.core.model.Equipment> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Equipment) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.deleteById(slotId = slotId, id = id) }
    }

    private class ManualDaoWrapper(private val dao: com.xianxia.sect.data.local.ManualDao) : GenericDao<com.xianxia.sect.core.model.Manual> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Manual) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.Manual(id = id)) }
    }

    private class PillDaoWrapper(private val dao: com.xianxia.sect.data.local.PillDao) : GenericDao<com.xianxia.sect.core.model.Pill> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Pill) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.Pill(id = id)) }
    }

    private class MaterialDaoWrapper(private val dao: com.xianxia.sect.data.local.MaterialDao) : GenericDao<com.xianxia.sect.core.model.Material> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Material) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.Material(id = id)) }
    }

    private class HerbDaoWrapper(private val dao: com.xianxia.sect.data.local.HerbDao) : GenericDao<com.xianxia.sect.core.model.Herb> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Herb) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.Herb(id = id)) }
    }

    private class SeedDaoWrapper(private val dao: com.xianxia.sect.data.local.SeedDao) : GenericDao<com.xianxia.sect.core.model.Seed> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Seed) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.Seed(id = id)) }
    }

    private class TeamDaoWrapper(private val dao: com.xianxia.sect.data.local.ExplorationTeamDao) : GenericDao<com.xianxia.sect.core.model.ExplorationTeam> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.ExplorationTeam) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.ExplorationTeam(id = id)) }
    }

    private class BuildingSlotDaoWrapper(private val dao: com.xianxia.sect.data.local.BuildingSlotDao) : GenericDao<com.xianxia.sect.core.model.BuildingSlot> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.BuildingSlot) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.delete(com.xianxia.sect.core.model.BuildingSlot(id = id)) }
    }

    private class EventDaoWrapper(private val dao: com.xianxia.sect.data.local.GameEventDao) : GenericDao<com.xianxia.sect.core.model.GameEvent> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.GameEvent) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.deleteById(slotId = slotId, id = id) }
    }

    private class BattleLogDaoWrapper(private val dao: com.xianxia.sect.data.local.BattleLogDao) : GenericDao<com.xianxia.sect.core.model.BattleLog> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.BattleLog) { dao.insert(entity) }
        override suspend fun deleteById(slotId: Int, id: String) { dao.deleteById(slotId = slotId, id = id) }
    }

    // ==================== 核心缓存实例 ====================

    /** 分级内存缓存（W-TinyLFU + SWR）- 高级版实现 */
    private val tieredCache: TieredMemoryCache = TieredMemoryCache(
        maxSize = config.memoryCacheSize.toInt(),
        maxEntryCount = config.maxEntryCount,
        config = TieredCacheConfig(
            swrPolicy = SwrPolicy(ttlMs = 3600_000L, staleWhileRevalidateMs = 300_000L)
        )
    )

    /** 统一磁盘缓存（MMKV + 文件系统 + CRC32 + WriteCoalescer） */
    private val unifiedDiskCache: UnifiedDiskCache by lazy {
        UnifiedDiskCache(
            context = context,
            cacheId = "game_data_cache",
            cacheDirName = "game_disk_cache",
            maxSize = config.diskCacheSize,
            config = UnifiedDiskCacheConfig(
                enableAutoVerify = true,
                enableCrcCheck = true
            )
        )
    }

    // ==================== 辅助组件（拆分后的独立组件）====================

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val writePipeline: UnifiedWritePipeline = UnifiedWritePipeline(scope = scope)

    /**
     * 防穿透守卫（高级版 - 使用 CachePenetrationGuard）
     *
     * 用于快速判断 key 是否可能存在，避免缓存穿透攻击。
     * 与 MemoryCacheCore 内置的 SimpleBloomFilter 互为补充：
     * - CachePenetrationGuard: 全局级别的防穿透（更精确）
     * - SimpleBloomFilter: MemoryCacheCore 内部使用（更轻量）
     */
    private val penetrationGuard = CachePenetrationGuard(expectedInsertions = 5000)

    /**
     * 简化版 BloomFilter（新建的独立组件 - v2 新增）
     *
     * 可用于轻量级场景的防穿透需求。
     * 与 CachePenetrationGuard 的区别：
     * - SimpleBloomFilter: 更简单、可配置、适合独立使用
     * - CachePenetrationGuard: 更完整、集成度更高
     *
     * @see com.xianxia.sect.data.cache.core.SimpleBloomFilter
     */
    private val simpleBloomFilter: SimpleBloomFilter = SimpleBloomFilter(
        expectedItems = 10000,
        falsePositiveRate = 0.01
    )

    private val genericDaoFactory: Map<String, GenericDao<*>> by lazy {
        mapOf(
            CacheKey.TYPE_DISCIPLE to DiscipleDaoWrapper(database.discipleDao()),
            CacheKey.TYPE_EQUIPMENT to EquipmentDaoWrapper(database.equipmentDao()),
            CacheKey.TYPE_MANUAL to ManualDaoWrapper(database.manualDao()),
            CacheKey.TYPE_PILL to PillDaoWrapper(database.pillDao()),
            CacheKey.TYPE_MATERIAL to MaterialDaoWrapper(database.materialDao()),
            CacheKey.TYPE_HERB to HerbDaoWrapper(database.herbDao()),
            CacheKey.TYPE_SEED to SeedDaoWrapper(database.seedDao()),
            CacheKey.TYPE_TEAM to TeamDaoWrapper(database.explorationTeamDao()),
            CacheKey.TYPE_BUILDING_SLOT to BuildingSlotDaoWrapper(database.buildingSlotDao()),
            CacheKey.TYPE_EVENT to EventDaoWrapper(database.gameEventDao()),
            CacheKey.TYPE_BATTLE_LOG to BattleLogDaoWrapper(database.battleLogDao())
        )
    }

    // ==================== 统计与状态 ====================

    /** 增强的缓存统计信息 */
    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val lastCleanupTime = AtomicLong(System.currentTimeMillis())
    private val lastStatsLogTime = AtomicLong(System.currentTimeMillis())
    private var lastMemoryCheckTime = AtomicLong(System.currentTimeMillis())

    /** 当前内存压力等级 */
    @Volatile
    private var currentPressureLevel: MemoryPressureLevel = MemoryPressureLevel.LOW

    /** 动态配置引用（支持运行时更新） */
    @Volatile
    private var currentConfig: CacheConfig = config

    /** 平滑后的当前压力比例 [0.1, 1.0] */
    @Volatile
    private var currentSmoothedPressureRatio: Float = 0.1f

    /** 命中率告警阈值 */
    var hitRateAlertThreshold: Float = 0.5f

    /** 命中率低于阈值时的回调 */
    var onHitRateDroppedBelowThreshold: ((Float) -> Unit)? = null

    /** 上次是否已触发命中率告警（防抖） */
    @Volatile
    private var hitRateAlertFired: Boolean = false

    /** corruption 修复计数 */
    @Volatile
    private var totalCorruptionRepairs: Int = 0

    // ==================== Slot 隔离机制（内化自 UnifiedCacheLayer）====================

    @Volatile
    private var slotIsolationEnabled: Boolean = true

    // ==================== 协程 Job ====================

    private var cleanupJob: Job? = null
    private var memoryMonitorJob: Job? = null

    // ==================== 初始化 ====================

    init {
        // 注册 ComponentCallbacks2 用于 onTrimMemory
        try {
            (context.applicationContext as Application).registerComponentCallbacks(this)
            Log.i(TAG, "ComponentCallbacks2 registered for onTrimMemory")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ComponentCallbacks2: ${e.message}")
        }

        // 启动时内存预算检查（删档游戏优化）
        performStartupMemoryBudgetCheck()

        setupWriteQueue()
        startBackgroundTasks()
        registerMemoryListener()

        // 启动时执行一次 corruption recovery
        performInitialCorruptionCheck()

        Log.i(TAG, "GameDataCacheManager v2 initialized with config: $config")
        Log.i(TAG, "Component architecture (Facade pattern):")
        Log.i(TAG, "  - MemoryCacheCore (W-TinyLFU + SWR + BloomFilter): ${config.memoryCacheSize / (1024 * 1024)}MB")
        Log.i(TAG, "  - TieredMemoryCache (Advanced W-TinyLFU + CountMinSketch)")
        Log.i(TAG, "  - UnifiedDiskCache (MMKV + File + CRC32)")
        Log.i(TAG, "  - CachePenetrationGuard (Advanced anti-penetration)")
        Log.i(TAG, "  - SimpleBloomFilter (Lightweight anti-penetration)")
        if (memoryManager != null) {
            Log.i(TAG, "DynamicMemoryManager integration enabled - Device tier: ${memoryManager.deviceTier.displayName}")
        }
    }

    // ==================== ComponentCallbacks2 实现 ====================

    /**
     * 系统内存裁剪回调 - 使用平滑 sigmoid 曲线计算缩减比例
     *
     * 不同 trimLevel 对应不同的归一化压力值：
     * - RUNNING_LOW (15%) -> 轻微压力
     * - MODERATE (35%) -> 中等压力
     * - BACKGROUND (55%) -> 较高压力
     * - COMPLETE (85%) -> 极端压力
     *
     * Sigmoid 曲线特性:
     * - 低压力时变化平缓（避免过度反应）
     * - 中间区域快速响应（灵敏调整）
     * - 高压力时趋于饱和（防止过度收缩）
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        val ratio = smoothPressureCurve(level)
        currentSmoothedPressureRatio = ratio

        Log.i(TAG, "onTrimMemory: level=$level, smoothedRatio=${"%.3f".format(ratio)}")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI 隐藏时不需要特殊处理
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                scope.launch { evictByPriority(CachePriority.BACKGROUND, 1.0f) }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                scope.launch { evictByPriority(CachePriority.BACKGROUND, ratio) }
            }
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                scope.launch {
                    evictByPriority(CachePriority.BACKGROUND, 1.0f)
                    evictByPriority(CachePriority.LOW, ratio)
                    tieredCache.trimToSize((tieredCache.maxSize() * ratio).toInt())
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                scope.launch {
                    evictByPriority(CachePriority.BACKGROUND, 1.0f)
                    evictByPriority(CachePriority.LOW, 1.0f)
                    evictByPriority(CachePriority.NORMAL, ratio)
                    tieredCache.cleanExpired()
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                scope.launch {
                    emergencyPurgeInternalPriorityAware()
                }
            }
        }
    }

    private suspend fun evictByPriority(priority: CachePriority, ratio: Float) {
        val snapshot = tieredCache.snapshot()
        val victims = mutableListOf<String>()
        for ((key, entry) in snapshot) {
            val keyType = key.split(":").firstOrNull() ?: continue
            val entryPriority = CacheTypeConfig.DATA_TYPE_PRIORITIES[keyType] ?: CachePriority.NORMAL
            if (entryPriority == priority) {
                victims.add(key)
            }
        }
        val toRemove = victims.take((victims.size * (1 - ratio)).toInt())
        toRemove.forEach { tieredCache.remove(it) }
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Evicted ${toRemove.size} $priority entries (ratio=$ratio)")
        }
    }

    private fun emergencyPurgeInternalPriorityAware() {
        Log.e(TAG, "Emergency purge (priority-aware): protecting CRITICAL data")

        val snapshot = tieredCache.snapshot()
        var removed = 0

        for ((key, entry) in snapshot) {
            val keyType = key.split(":").firstOrNull() ?: continue
            val priority = CacheTypeConfig.DATA_TYPE_PRIORITIES[keyType]

            when (priority) {
                CachePriority.CRITICAL -> { /* NEVER remove critical data */ }
                CachePriority.HIGH -> {
                    if (removed < snapshot.size * 0.5) {
                        tieredCache.remove(key)
                        removed++
                    }
                }
                else -> {
                    tieredCache.remove(key)
                    removed++
                }
            }
        }

        tieredCache.trimToSize(5 * 1024 * 1024)
        memoryManager?.forceGcAndWait()
        Log.e(TAG, "Priority-aware emergency purge completed: removed $removed/${snapshot.size} entries")
    }

    /**
     * 使用平滑 sigmoid 曲线将 trimLevel 映射为缩减比例
     *
     * 公式: f(x) = 1 / (1 + e^(8*(x - 0.5)))
     *
     * 特性:
     * - x=0.15 (RUNNING_LOW) -> ~0.73 (保留 73%)
     * - x=0.35 (MODERATE)   -> ~0.45 (保留 45%)
     * - x=0.55 (BACKGROUND)  -> ~0.27 (保留 27%)
     * - x=0.85 (COMPLETE)    -> ~0.07 (保留 7%, 接近清空但保留最小量)
     */
    @Suppress("DEPRECATION")
    private fun smoothPressureCurve(trimLevel: Int): Float {
        val normalizedLevel = when (trimLevel) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 0.15f
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> 0.35f
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 0.55f
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 0.85f
            else -> 0.0f
        }
        return (1.0f / (1.0f + exp(8.0 * (normalizedLevel - 0.5)).toFloat()))
            .coerceIn(0.1f, 1.0f)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    @Deprecated("Deprecated in ComponentCallbacks2")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        // 低内存时的额外处理
        Log.w(TAG, "onLowMemory called, performing emergency cleanup")
        scope.launch {
            evictColdDataInternal()
            tieredCache.cleanExpired()
        }
    }

    /**
     * 启动时内存预算检查（删档游戏优化）
     *
     * 在初始化阶段检测设备可用内存，若低于安全阈值则主动缩减缓存配置：
     * - 可用内存 < 512MB：将内存缓存限制到 2MB
     * - 可用内存 < 1GB：将内存缓存限制到 3MB（默认已是 4MB）
     * - 可用内存 >= 1GB：保持默认 4MB 配置
     *
     * 此检查避免在低端设备上因缓存过大导致 OOM，
     * 特别适合删档测试阶段的数据量特征。
     */
    private fun performStartupMemoryBudgetCheck() {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: run {
                    Log.i(TAG, "Startup memory budget check skipped: ActivityManager unavailable")
                    return
                }

            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalMemMb = memInfo.totalMem / (1024.0 * 1024)
            val availMemMb = memInfo.availMem / (1024.0 * 1024)
            val lowMemoryThreshold = memInfo.threshold / (1024.0 * 1024)

            Log.i(TAG, "Startup memory budget: total=${"%.0f".format(totalMemMb)}MB, " +
                    "avail=${"%.0f".format(availMemMb)}MB, " +
                    "lowThreshold=${"%.0f".format(lowMemoryThreshold)}MB, " +
                    "lowMemory=${memInfo.lowMemory}")

            // 根据可用内存动态调整缓存大小
            val adjustedConfig = when {
                availMemMb < 512 -> {
                    Log.w(TAG, "Low memory device detected (${"%.0f".format(availMemMb)}MB available), reducing cache to 2MB")
                    currentConfig.copy(memoryCacheSize = 2 * 1024 * 1024L)  // 2MB
                }
                availMemMb < 1024 -> {
                    Log.w(TAG, "Medium-low memory device (${"%.0f".format(availMemMb)}MB available), reducing cache to 3MB")
                    currentConfig.copy(memoryCacheSize = 3 * 1024 * 1024L)  // 3MB
                }
                else -> {
                    Log.i(TAG, "Sufficient memory available (${"%.0f".format(availMemMb)}MB), keeping default 4MB cache")
                    null  // 保持默认配置
                }
            }

            if (adjustedConfig != null) {
                this.currentConfig = adjustedConfig
                tieredCache.trimToSize(adjustedConfig.memoryCacheSize.toInt())
                Log.i(TAG, "Cache config adjusted for low-memory device: ${adjustedConfig.memoryCacheSize / (1024 * 1024)}MB")
            }

            // 如果系统已标记为低内存，立即触发一次清理
            if (memInfo.lowMemory) {
                Log.w(TAG, "System reports low memory status, performing preemptive cleanup")
                scope.launch {
                    evictColdDataInternal()
                    tieredCache.cleanExpired()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Startup memory budget check failed, using default config", e)
        }
    }

    // ==================== MemoryEventListener 实现（保持不变）====================

    private fun registerMemoryListener() {
        memoryManager?.addListener(this)
        updateCacheForMemoryPressure()
    }

    override fun onPressureChanged(snapshot: MemorySnapshot) {
        currentPressureLevel = snapshot.pressureLevel
        Log.d(TAG, "Memory pressure changed to: ${snapshot.pressureLevel.name} (${snapshot.availablePercent}% available)")
        adjustCacheForPressure(snapshot)
    }

    override fun onWarning(event: com.xianxia.sect.data.memory.MemoryWarningEvent) {
        Log.w(TAG, "Memory warning: ${event.message}, Suggested action: ${event.suggestedAction}")

        when (event.suggestedAction) {
            DegradationStrategy.REDUCE_CACHE_SIZE -> {
                reduceCacheSize(currentConfig.pressureResponseConfig.moderatePressureCacheReduction)
            }
            DegradationStrategy.EVICT_COLD_ZONE -> {
                evictColdData()
                if (currentConfig.pressureResponseConfig.enableAutoGcOnHighPressure) {
                    triggerGcIfNeeded()
                }
            }
            DegradationStrategy.EMERGENCY_PURGE -> {
                emergencyPurge()
            }
            else -> { /* 其他策略暂不处理 */ }
        }
    }

    override fun onDegradationApplied(strategy: DegradationStrategy, reason: String) {
        Log.w(TAG, "Degradation applied: $strategy, Reason: $reason")
    }

    // ==================== 内存压力响应方法 ====================

    private fun adjustCacheForPressure(snapshot: MemorySnapshot) {
        val pressureOrdinal = snapshot.pressureLevel.ordinalValue
        val adjustedSize = currentConfig.getAdjustedMemoryCacheSize(pressureOrdinal)
        val adjustedEntries = currentConfig.getAdjustedMaxEntryCount(pressureOrdinal)

        Log.d(TAG, "Adjusting cache for pressure ${snapshot.pressureLevel.name}: size=${adjustedSize / 1024}KB, entries=$adjustedEntries")

        tieredCache.trimToSize(adjustedSize.toInt())
    }

    private fun reduceCacheSize(ratio: Float) {
        Log.w(TAG, "Reducing cache size by ratio: $ratio")
        tieredCache.trimToSize((tieredCache.maxSize() * ratio).toInt())
    }

    /**
     * 驱逐冷数据（公共接口）
     */
    fun evictColdData(): Int = evictColdDataInternal()

    /**
     * 强制驱逐冷数据（新增便捷方法）
     */
    fun forceEvictColdData(): Int {
        return evictColdDataInternal() + tieredCache.cleanExpired()
    }

    private fun evictColdDataInternal(): Int {
        // TieredMemoryCache 使用分区淘汰策略，这里通过 trim 触发淘汰
        val beforeSize = tieredCache.size()
        tieredCache.cleanExpired()
        val afterSize = tieredCache.size()
        val evicted = beforeSize - afterSize
        if (evicted > 0) {
            Log.d(TAG, "Evicted $evicted cold/expired entries")
        }
        return evicted.coerceAtLeast(0)
    }

    private fun emergencyPurge() {
        Log.e(TAG, "Emergency purge triggered!")
        evictColdData()
        tieredCache.trimToSize((tieredCache.maxSize() * 0.2).toInt())  // 只保留 20%
        memoryManager?.forceGcAndWait()
    }

    private fun emergencyPurgeInternal() {
        emergencyPurgeInternalPriorityAware()
    }

    private fun triggerGcIfNeeded() {
        if (currentConfig.pressureResponseConfig.enableAutoGcOnHighPressure) {
            scope.launch {
                try {
                    val freed = memoryManager?.forceGcAndWait() ?: 0L
                    if (freed < 0) {
                        Log.d(TAG, "GC freed ${-freed / 1024} KB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger GC", e)
                }
            }
        }
    }

    private fun updateCacheForMemoryPressure() {
        memoryManager?.let { manager ->
            val snapshot = manager.getMemorySnapshot()
            currentPressureLevel = snapshot.pressureLevel
            adjustCacheForPressure(snapshot)
        }
    }

    // ==================== 写队列设置 ====================

    private fun setupWriteQueue() {
        writePipeline.setBatchWriteHandler { tasks: List<WriteTask> ->
            scope.launch { processBatchWrite(tasks) }
        }
        writePipeline.startProcessing()
    }

    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(currentConfig.cleanupIntervalMs)
                performCleanup()
            }
        }

        memoryMonitorJob = scope.launch {
            while (isActive) {
                delay(MEMORY_CHECK_INTERVAL_MS)
                checkMemoryPressure()
            }
        }
    }

    private fun checkMemoryPressure() {
        if (memoryManager == null) return

        try {
            val snapshot = memoryManager.getMemorySnapshot()
            val previousPressure = currentPressureLevel
            currentPressureLevel = snapshot.pressureLevel

            if (snapshot.pressureLevel.ordinalValue > previousPressure.ordinalValue) {
                Log.w(TAG, "Memory pressure increased: ${previousPressure.name} -> ${snapshot.pressureLevel.name}")
                adjustCacheForPressure(snapshot)

                when (snapshot.pressureLevel) {
                    MemoryPressureLevel.HIGH -> {
                        evictColdData()
                        triggerGcIfNeeded()
                    }
                    MemoryPressureLevel.CRITICAL -> {
                        emergencyPurge()
                    }
                    else -> { /* 无需额外操作 */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory pressure", e)
        }
    }

    // ==================== 配置动态更新 ====================

    fun updateConfig(newConfig: CacheConfig) {
        this.currentConfig = newConfig
        Log.i(TAG, "Configuration updated: $newConfig")

        tieredCache.trimToSize(newConfig.memoryCacheSize.toInt())

        updateCacheForMemoryPressure()
    }

    // ==================== 核心 get 方法（增强版）====================

    /**
     * 获取缓存条目（suspend 版本）
     *
     * 查找顺序: TieredMemoryCache(SWR) -> UnifiedDiskCache(CRC32) -> loader
     */
    suspend fun <T : Any> get(
        key: CacheKey,
        loader: suspend () -> T
    ): T {
        val cacheKey = key.toString()

        // Bloom Filter 穿透防护：如果键从未见过，可以快速跳过缓存查找
        if (!penetrationGuard.mightContain(cacheKey)) {
            // Key has never been seen - skip cache lookup entirely and go straight to loader
            val value = loader()
            putInternal(key, value)
            penetrationGuard.put(cacheKey)
            return value
        }

        // 1. 尝试从分级内存缓存获取（自动支持 SWR）
        val memEntry = tieredCache.get(cacheKey)
        if (memEntry != null && memEntry.value != null) {
            @Suppress("UNCHECKED_CAST")
            return memEntry.value as T
        }

        // 2. miss 时回源到统一磁盘缓存
        if (currentConfig.enableDiskCache) {
            val diskEntry = unifiedDiskCache.get(cacheKey)
            if (diskEntry != null && diskEntry.value != null) {
                // 回填到内存缓存
                tieredCache.put(cacheKey, diskEntry)
                @Suppress("UNCHECKED_CAST")
                return diskEntry.value as T
            }
        }

        // 3. 全部 miss 时执行 loader
        val value = loader()
        putInternal(key, value)
        penetrationGuard.put(cacheKey)

        return value
    }

    /**
     * 获取缓存条目（不执行加载）
     */
    suspend fun <T : Any> getOrNull(key: CacheKey): T? {
        val cacheKey = key.toString()

        val memEntry = tieredCache.get(cacheKey)
        if (memEntry != null && memEntry.value != null) {
            @Suppress("UNCHECKED_CAST")
            return memEntry.value as T
        }

        if (currentConfig.enableDiskCache) {
            val diskEntry = unifiedDiskCache.get(cacheKey)
            if (diskEntry != null && diskEntry.value != null) {
                tieredCache.put(cacheKey, diskEntry)
                @Suppress("UNCHECKED_CAST")
                return diskEntry.value as T
            }
        }

        return null
    }

    // ==================== 核心 put 方法（P1#7 修复：默认使用 suspend 版本）====================

    /**
     * 存入缓存条目（默认 suspend 版本 - 可等待完成）
     *
     * 【P1#7 修复】此方法现在是默认的 put 入口，保证写入完成后再返回。
     * 消除了旧版 fire-and-forget 语义导致的崩溃丢数据风险。
     *
     * 写入流程：
     * 1. 写入 TieredMemoryCache（W-TinyLFU + SWR）
     * 2. 写入 UnifiedDiskCache（MMKV + CRC32 + WriteCoalescer）
     * 3. 更新 BloomFilter 防穿透
     * 4. 标记 Dirty（供 flush 使用）
     */
    suspend fun put(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        putInternal(key, value)
    }

    /**
     * 存入缓存条目（fire-and-forget 异步版本）
     *
     * 【P1#7 标记为 @Deprecated】仅在确实不需要等待完成的场景使用。
     * 崩溃时可能丢失最近一次写入。
     *
     * @Deprecated Use put() (suspend version) instead for guaranteed writes.
     */
    @Deprecated(
        message = "Fire-and-forget put may lose data on crash. Use suspend put() instead.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("put(key, value)")
    )
    fun putAsync(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }
        
        // P1#7: 保留异步版本供遗留代码使用，但明确标记为不安全
        scope.launch {
            putInternal(key, value)
        }
    }

    /**
     * 存入缓存条目（不追踪脏数据）
     *
     * F2: 同样消除 runBlocking
     */
    fun putWithoutTracking(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }

        scope.launch {
            putInternalNoTracking(key, value)
        }
    }

    /**
     * 内部 put 实现（使用 CacheEntry.create 即 V2 格式）
     */
    private suspend fun putInternal(key: CacheKey, value: Any) {
        val cacheKey = key.toString()

        // A5: 使用 CacheEntry.create() 创建 V2 格式条目
        val entry = CacheEntry.create(value, key.ttl)

        // 写入分级内存缓存（含 SWR 支持）
        tieredCache.put(cacheKey, entry)

        // 写入统一磁盘缓存（含 WriteCoalescer 合并 + CRC32 校验）
        if (currentConfig.enableDiskCache) {
            unifiedDiskCache.put(cacheKey, entry)
        }

        // 更新 Bloom Filter
        penetrationGuard.put(cacheKey)

        writePipeline.markUpdate(cacheKey, value)
    }

    private suspend fun putInternalNoTracking(key: CacheKey, value: Any) {
        val cacheKey = key.toString()
        val entry = CacheEntry.create(value, key.ttl)

        tieredCache.put(cacheKey, entry)

        if (currentConfig.enableDiskCache) {
            unifiedDiskCache.put(cacheKey, entry)
        }
    }

    // ==================== remove / contains ====================

    fun remove(key: CacheKey) {
        val cacheKey = key.toString()
        tieredCache.remove(cacheKey)
        unifiedDiskCache.remove(cacheKey)
        writePipeline.markDelete(cacheKey)
    }

    fun removeWithoutTracking(key: CacheKey) {
        val cacheKey = key.toString()
        tieredCache.remove(cacheKey)
        unifiedDiskCache.remove(cacheKey)
    }

    fun contains(key: CacheKey): Boolean {
        val cacheKey = key.toString()
        return tieredCache.contains(cacheKey) || unifiedDiskCache.contains(cacheKey)
    }

    // ==================== Dirty Tracking ====================

    fun markDirty(key: CacheKey, flag: DirtyFlag = DirtyFlag.UPDATE, data: Any? = null) {
        writePipeline.markDirty(key.toString(), flag, data)
    }

    fun markInsert(key: CacheKey, data: Any?) {
        writePipeline.markInsert(key.toString(), data)
    }

    fun markUpdate(key: CacheKey, data: Any?) {
        writePipeline.markUpdate(key.toString(), data)
    }

    fun markDelete(key: CacheKey) {
        writePipeline.markDelete(key.toString())
    }

    fun isDirty(key: CacheKey): Boolean {
        return writePipeline.isDirty(key.toString())
    }

    suspend fun flushDirty(): FlushResult {
        if (writePipeline.isEmpty()) {
            return FlushResult.NoChanges
        }

        val tasks = writePipeline.drainDirty()

        writePipeline.flush()

        return FlushResult.Success(tasks.size)
    }

    suspend fun sync(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                flushDirty()
                writePipeline.flush()
                // 同时 flush 磁盘缓存的 coalescer
                unifiedDiskCache.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                false
            }
        }
    }

    // ==================== clear / invalidate ====================

    fun clearCache(key: CacheKey? = null) {
        if (key != null) {
            val cacheKey = key.toString()
            tieredCache.remove(cacheKey)
            unifiedDiskCache.remove(cacheKey)
            writePipeline.clearDirty(cacheKey)
        } else {
            tieredCache.clear()
            unifiedDiskCache.clear()
            writePipeline.clearDirty(null)
        }
    }

    fun invalidateSlot(slot: Int) {
        // Slot 隔离机制已内化：清除所有属于该 slot 的条目
        val prefix = ":$slot:"
        scope.launch {
            val snapshot = tieredCache.snapshot()
            snapshot.keys.filter { it.contains(prefix) }.forEach { key ->
                tieredCache.remove(key)
                unifiedDiskCache.remove(key)
            }
            Log.i(TAG, "Invalidated all cache entries for slot $slot")
        }
    }

    fun clearMemoryCache() {
        tieredCache.clear()
    }

    fun clearDiskCache() {
        unifiedDiskCache.clear()
    }

    // ==================== 批处理写操作（保持不变）====================

    private suspend fun processBatchWrite(tasks: List<WriteTask>): Int {
        var successCount = 0

        for (task in tasks) {
            try {
                val success = processWriteTask(task)
                if (success) successCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process write task: ${task.key}", e)
            }
        }

        Log.d(TAG, "Processed $successCount/${tasks.size} write tasks")
        return successCount
    }

    private suspend fun processWriteTask(task: WriteTask): Boolean {
        val cacheKey = CacheKey.fromString(task.key)

        return when (cacheKey.type) {
            CacheKey.TYPE_GAME_DATA -> processGameDataWrite(task)
            else -> {
                val dao = genericDaoFactory[cacheKey.type]
                if (dao != null) {
                    processGenericWrite(dao, task, cacheKey)
                } else {
                    Log.w(TAG, "Unknown cache type: ${cacheKey.type}")
                    false
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> processGenericWrite(
        dao: GenericDao<T>,
        task: WriteTask,
        cacheKey: CacheKey
    ): Boolean {
        return try {
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    dao.deleteById(cacheKey.slot, cacheKey.id)
                    Log.d(TAG, "Deleted ${cacheKey.type}: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val entity = task.value as? T
                    if (entity != null) {
                        dao.insert(entity)
                        Log.d(TAG, "Inserted/Updated ${cacheKey.type}: $entity")
                        true
                    } else {
                        Log.w(TAG, "${cacheKey.type} data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process ${cacheKey.type} write", e)
            false
        }
    }

    private suspend fun processGameDataWrite(task: WriteTask): Boolean {
        return try {
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.gameDataDao().deleteAll(0)
                    Log.d(TAG, "Deleted all game data")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val gameData = task.value as? com.xianxia.sect.core.model.GameData
                    if (gameData != null) {
                        val slot = gameData.currentSlot.takeIf { it > 0 } ?: gameData.slotId.takeIf { it > 0 } ?: 1
                        val gdWithSlot = gameData.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot")
                        database.gameDataDao().insert(gdWithSlot)
                        Log.d(TAG, "Inserted/Updated game data")
                        true
                    } else {
                        Log.w(TAG, "GameData is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process game data write", e)
            false
        }
    }

    // ==================== 定期清理 ====================

    private suspend fun performCleanup() {
        val now = System.currentTimeMillis()

        if (now - lastCleanupTime.get() >= currentConfig.cleanupIntervalMs) {
            val expiredMemory = tieredCache.cleanExpired()
            val expiredDisk = unifiedDiskCache.cleanExpired()

            lastCleanupTime.set(now)

            if (expiredMemory > 0 || expiredDisk > 0) {
                Log.d(TAG, "Cleanup completed: memory=$expiredMemory, disk=$expiredDisk")
            }

            // O4: 定期执行 corruption recovery
            val repaired = unifiedDiskCache.verifyAndRepair()
            if (repaired > 0) {
                totalCorruptionRepairs += repaired
                Log.i(TAG, "Corruption repair during cleanup: $repaired entries fixed")
            }
        }

        if (now - lastStatsLogTime.get() >= STATS_LOG_INTERVAL_MS) {
            updateStats()
            lastStatsLogTime.set(now)
        }
    }

    // ==================== O1: 统一统计信息（聚合视图）====================

    /**
     * 更新增强的统计信息（合并所有层级 + O2 命中率告警检测）
     */
    private fun updateStats() {
        val memStats = tieredCache.getStats()
        val diskStats = unifiedDiskCache.getStats()
        val dirtyStats = writePipeline.getStats()
        val queueStats = writePipeline.getStats()
        val partitionDist = tieredCache.getPartitionDistribution()

        val stats = CacheStats(
            memoryHitCount = memStats.hitCount,
            memoryMissCount = memStats.missCount,
            memorySize = memStats.totalSize.toLong(),
            memoryEntryCount = memStats.totalCount,
            diskSize = diskStats.totalSize,
            diskEntryCount = diskStats.entryCount,
            dirtyCount = dirtyStats.pendingCount,
            writeQueueSize = queueStats.batchSize,
            hotDataCount = partitionDist[CachePartition.PROTECTED] ?: 0,
            warmDataCount = partitionDist[CachePartition.MAIN] ?: 0,
            coldDataCount = partitionDist[CachePartition.WINDOW] ?: 0,
            evictedCount = memStats.evictedCount,
            totalAccessCount = memStats.hitCount + memStats.missCount + memStats.staleHitCount,
            memoryPressureLevel = currentPressureLevel.ordinalValue
        )

        _cacheStats.value = stats

        // O2: 命中率告警检测
        val hitRate = memStats.hitRate
        if (hitRate < hitRateAlertThreshold && !hitRateAlertFired) {
            hitRateAlertFired = true
            onHitRateDroppedBelowThreshold?.invoke(hitRate.toFloat())
            Log.w(TAG, "Hit rate alert: hitRate=${"%.3f".format(hitRate)} below threshold=$hitRateAlertThreshold")
        } else if (hitRate >= hitRateAlertThreshold) {
            hitRateAlertFired = false  // 重置告警状态
        }

        Log.d(TAG, "Cache stats: memoryHitRate=${"%.2f".format(stats.memoryHitRate)}, " +
                "memorySize=${stats.memorySize}, diskSize=${stats.diskSize}, " +
                "dirtyCount=${stats.dirtyCount}, queueSize=${stats.writeQueueSize}, " +
                "pressure=${currentPressureLevel.name}, " +
                "partition[W:${partitionDist[CachePartition.WINDOW]}/M:${partitionDist[CachePartition.MAIN]}/P:${partitionDist[CachePartition.PROTECTED]}], " +
                "staleHits=${memStats.staleHitCount}, evicted=${stats.evictedCount}, " +
                "corruptions=$totalCorruptionRepairs, pressureRatio=${"%.3f".format(currentSmoothedPressureRatio)}")
    }

    fun getStats(): CacheStats {
        updateStats()
        return _cacheStats.value
    }

    /**
     * O1: 获取跨层级全局聚合统计视图
     *
     * 合并 TieredMemoryCache + UnifiedDiskCache + DirtyTracker + WriteQueue + Pressure 状态
     */
    fun getGlobalStats(): GlobalCacheStats {
        val memStats = tieredCache.getStats()
        val diskStats = unifiedDiskCache.getStats()
        val dirtyStats = writePipeline.getStats()
        val queueStats = writePipeline.getStats()
        val partitionDist = tieredCache.getPartitionDistribution()

        return GlobalCacheStats(
            totalMemoryBytes = memStats.totalSize.toLong(),
            totalDiskBytes = diskStats.totalSize,
            totalEntries = memStats.totalCount,
            memoryHitRate = memStats.hitRate,
            diskHitRate = diskStats.hitRate,
            staleHitRate = memStats.staleHitCount.toDouble(),
            corruptionCount = totalCorruptionRepairs.toLong(),
            dirtyCount = dirtyStats.pendingCount.toLong(),
            writeQueuePending = queueStats.batchSize.toLong(),
            pressureLevel = currentPressureLevel.name,
            smoothedPressureRatio = currentSmoothedPressureRatio,
            partitionDistribution = partitionDist.mapKeys { it.key.displayName },
            diskStorageBreakdown = mapOf(
                "mmkv_entries" to diskStats.mmkvEntryCount.toLong(),
                "file_entries" to diskStats.fileEntryCount.toLong()
            ),
            activeSlots = setOf(0),  // 单一全局缓存，slot 已内化
            timestamp = System.currentTimeMillis()
        )
    }

    // ==================== 诊断信息 ====================

    /**
     * 获取详细的诊断信息（文本格式，保持向后兼容）
     */
    fun getDiagnosticInfo(): String {
        val stats = getStats()
        val memStats = tieredCache.getStats()
        val globalStats = getGlobalStats()
        val topKeys = getTopAccessedKeys(10)

        // v2 新增：获取新组件的统计信息
        val bloomFilterSize = simpleBloomFilter.approximateSize()

        val sb = StringBuilder()
        sb.appendLine("=== GameDataCacheManager Diagnostic (v2 Facade) ===")
        sb.appendLine("Architecture: Extract Delegate Pattern (Thin Facade)")
        sb.appendLine()
        sb.appendLine("Configuration:")
        sb.appendLine("  Max memory cache: ${currentConfig.memoryCacheSize / (1024 * 1024)} MB")
        sb.appendLine("  Max entries: ${currentConfig.maxEntryCount}")
        sb.appendLine("  Eviction policy: W-TinyLFU (Tiered)")
        sb.appendLine("  Cleanup interval: ${currentConfig.cleanupIntervalMs / 1000}s")
        sb.appendLine()
        sb.appendLine("Component Status (TieredMemoryCache):")
        sb.appendLine("  SimpleBloomFilter:")
        sb.appendLine("    Approximate size: $bloomFilterSize items")
        sb.appendLine()
        sb.appendLine("Current Status (TieredMemoryCache - Advanced):")
        sb.appendLine("  Memory usage: ${memStats.totalSize}/${memStats.maxSize} bytes (${if (memStats.maxSize > 0) "%.1f".format(memStats.totalSize.toDouble() / memStats.maxSize * 100) else "?"}%)")
        sb.appendLine("  Entry count: ${memStats.totalCount}/${memStats.maxSize}")
        sb.appendLine("  Hit rate: ${"%.2f%%".format(memStats.hitRate * 100)}")
        sb.appendLine("  Stale hit rate: ${"%.2f%%".format(if (memStats.hitCount + memStats.missCount > 0) memStats.staleHitCount.toDouble() / (memStats.hitCount + memStats.missCount) * 100 else 0.0)}")
        sb.appendLine("  Total accesses: ${memStats.hitCount + memStats.missCount + memStats.staleHitCount}")
        sb.appendLine("  Evicted: ${memStats.evictedCount}")
        sb.appendLine()
        sb.appendLine("Partition Distribution (W-TinyLFU):")
        sb.appendLine("  Window (new entries): ${memStats.windowCount}")
        sb.appendLine("  Main (LRU cache): ${memStats.mainCount}")
        sb.appendLine("  Protected (hot data): ${memStats.protectedCount}")
        sb.appendLine()
        sb.appendLine("Disk Cache (Unified):")
        val diskStats = unifiedDiskCache.getStats()
        sb.appendLine("  Size: ${diskStats.totalSize / 1024}KB / ${diskStats.maxSize / (1024 * 1024)}MB")
        sb.appendLine("  Entries: ${diskStats.entryCount} (MMKV: ${diskStats.mmkvEntryCount}, Files: ${diskStats.fileEntryCount})")
        sb.appendLine("  Corruptions repaired: $totalCorruptionRepairs")
        sb.appendLine()
        sb.appendLine("Memory Pressure: ${currentPressureLevel.name}")
        sb.appendLine("  Smoothed pressure ratio: ${"%.3f".format(currentSmoothedPressureRatio)}")
        if (memoryManager != null) {
            sb.appendLine(memoryManager.getDiagnosticReport())
        }
        sb.appendLine()
        sb.appendLine("Top 10 Accessed Keys:")
        topKeys.forEach { (key, count) ->
            sb.appendLine("  $key: $count accesses")
        }
        sb.appendLine("=========================================")

        return sb.toString()
    }

    // ==================== O3: Diagnostic JSON 导出 ====================

    /**
     * 导出结构化 JSON 诊断信息
     *
     * 包含: 配置/内存统计/磁盘统计/分区分布/压力等级/top keys/告警状态
     */
    fun exportDiagnosticJson(): String {
        val memStats = tieredCache.getStats()
        val diskStats = unifiedDiskCache.getStats()
        val dirtyStats = writePipeline.getStats()
        val queueStats = writePipeline.getStats()
        val partitionDist = tieredCache.getPartitionDistribution()
        val topKeys = getTopAccessedKeys(20)

        // v2 新增：获取新组件的统计信息

        val json = JSONObject().apply {
            // 架构版本
            put("architectureVersion", 2)
            put("pattern", "Extract Delegate Pattern (Thin Facade)")

            // 配置信息
            put("config", JSONObject().apply {
                put("maxMemoryCacheSizeMB", currentConfig.memoryCacheSize / (1024 * 1024))
                put("maxDiskCacheSizeMB", currentConfig.diskCacheSize / (1024 * 1024))
                put("maxEntries", currentConfig.maxEntryCount)
                put("evictionPolicy", "W-TinyLFU")
                put("cleanupIntervalSec", currentConfig.cleanupIntervalMs / 1000)
                put("swrEnabled", true)
                put("crc32Enabled", true)
                put("writeCoalescerEnabled", true)
            })

            // 内存统计
            put("memory", JSONObject().apply {
                put("totalSizeBytes", memStats.totalSize)
                put("maxSizeBytes", memStats.maxSize)
                put("entryCount", memStats.totalCount)
                put("hitCount", memStats.hitCount)
                put("missCount", memStats.missCount)
                put("staleHitCount", memStats.staleHitCount)
                put("evictedCount", memStats.evictedCount)
                put("hitRate", memStats.hitRate)
                put("hotThreshold", memStats.hotThreshold)
            })

            // 分区分布
            put("partitionDistribution", JSONObject().apply {
                put("window", partitionDist[CachePartition.WINDOW] ?: 0)
                put("main", partitionDist[CachePartition.MAIN] ?: 0)
                put("protected", partitionDist[CachePartition.PROTECTED] ?: 0)
            })

            // 磁盘统计
            put("disk", JSONObject().apply {
                put("totalSizeBytes", diskStats.totalSize)
                put("maxSizeBytes", diskStats.maxSize)
                put("entryCount", diskStats.entryCount)
                put("mmkvEntryCount", diskStats.mmkvEntryCount)
                put("fileEntryCount", diskStats.fileEntryCount)
                put("corruptionCount", diskStats.corruptionCount)
                put("hitRate", diskStats.hitRate)
                put("usagePercent", diskStats.usagePercent)
            })

            // Dirty Tracker & Write Queue
            put("writePipeline", JSONObject().apply {
                put("dirtyCount", dirtyStats.pendingCount)
                put("queueSize", queueStats.batchSize)
                put("corruptionRepairsTotal", totalCorruptionRepairs)
            })

            // 压力状态
            put("pressure", JSONObject().apply {
                put("level", currentPressureLevel.name)
                put("levelOrdinal", currentPressureLevel.ordinalValue)
                put("smoothedRatio", currentSmoothedPressureRatio)
            })

            // Top Keys
            put("topKeys", JSONArray().apply {
                topKeys.forEach { (key, count) ->
                    put(JSONObject().apply {
                        put("key", key)
                        put("accessCount", count)
                    })
                }
            })

            // 告警状态
            put("alerts", JSONObject().apply {
                put("hitRateAlertEnabled", onHitRateDroppedBelowThreshold != null)
                put("hitRateAlertThreshold", hitRateAlertThreshold)
                put("hitRateAlertActive", hitRateAlertFired)
                put("currentHitRate", memStats.hitRate)
            })

            // v2 新增：新组件统计信息
            put("v2Components", JSONObject().apply {
                // SimpleBloomFilter 统计
                put("simpleBloomFilter", JSONObject().apply {
                    put("approximateSize", simpleBloomFilter.approximateSize())
                })
            })

            // 元数据
            put("meta", JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("version", 2)
                put("slotIsolationEnabled", slotIsolationEnabled)
            })
        }

        return json.toString(2)
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取高频访问的 key 列表
     *
     * 使用 TieredMemoryCache 的 CountMinSketch 频率估算获取真实的访问频率数据
     */
    private fun getTopAccessedKeys(n: Int): List<Pair<String, Int>> {
        val snapshot = tieredCache.snapshot()
        val sketch = tieredCache.getFrequencySketch()
        return snapshot.keys
            .map { key -> key to sketch.count(key) }
            .sortedByDescending { it.second }
            .take(n)
    }

    /**
     * 获取当前平滑后的压力比例
     *
     * 新增便捷方法
     */
    fun getCurrentPressureRatio(): Float = currentSmoothedPressureRatio

    // ==================== O4: Corruption Recovery ====================

    /**
     * 执行初始 corruption 检查（在 init 中调用）
     */
    private fun performInitialCorruptionCheck() {
        scope.launch {
            try {
                val repaired = unifiedDiskCache.verifyAndRepair()
                if (repaired > 0) {
                    totalCorruptionRepairs += repaired
                    Log.i(TAG, "Initial corruption check: $repaired corrupted entries repaired")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initial corruption check failed", e)
            }
        }
    }

    // ==================== 预热与预加载 ====================

    fun preloadData(dataMap: Map<CacheKey, Any>) {
        dataMap.forEach { (key, value) ->
            putWithoutTracking(key, value)
        }
        Log.i(TAG, "Preloaded ${dataMap.size} entries")
    }

    fun warmupCache(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?) {
        scope.launch {
            keys.forEach { key ->
                try {
                    val value = loader(key)
                    if (value != null) {
                        putWithoutTracking(key, value)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warmup cache for key: $key", e)
                }
            }
            Log.i(TAG, "Cache warmup completed for ${keys.size} keys")
        }
    }

    // ==================== 优化方法 ====================

    /**
     * 手动触发一次完整的缓存优化（保持向后兼容）
     */
    fun optimizeCache(): OptimizeResult {
        val beforeMemSize = tieredCache.size()
        val beforeEntries = tieredCache.size()

        // 1. 清理过期条目
        val cleaned = tieredCache.cleanExpired()

        // 2. 驱逐冷数据
        val evictedCold = evictColdDataInternal()

        // 3. 检查并调整到合适的大小
        updateCacheForMemoryPressure()

        // 4. 触发 GC
        val gcFreed = memoryManager?.forceGcAndWait() ?: 0L

        val afterMemSize = tieredCache.size()
        val afterEntries = tieredCache.size()

        Log.i(TAG, "Cache optimization completed: cleaned=$cleaned, evictedCold=$evictedCold, " +
                "gcFreed=${if (gcFreed < 0) -gcFreed / 1024 else 0}KB, " +
                "size: $beforeMemSize->$afterMemSize, entries: $beforeEntries->$afterEntries")

        return OptimizeResult(
            cleanedEntries = cleaned,
            evictedColdEntries = evictedCold,
            gcFreedBytes = if (gcFreed < 0) -gcFreed else 0L,
            beforeSizeBytes = beforeMemSize.toLong(),
            afterSizeBytes = afterMemSize.toLong(),
            beforeEntryCount = beforeEntries,
            afterEntryCount = afterEntries
        )
    }

    /**
     * 增强版优化（新增便捷方法）
     *
     * 含 SWR 刷新 + corruption repair + 压力感知清理
     */
    fun optimizeCacheV2(): OptimizeResultV2 {
        val startTime = System.currentTimeMillis()

        // 1. 基础清理
        val baseResult = optimizeCache()

        // 2. Corruption repair (verifyAndRepair 是非 suspend 方法)
        val repaired = unifiedDiskCache.verifyAndRepair()
        totalCorruptionRepairs += repaired

        // 3. 磁盘缓存过期清理
        val diskCleaned = unifiedDiskCache.cleanExpired()

        // 4. Flush 所有待处理写入
        unifiedDiskCache.flush()

        val elapsedMs = System.currentTimeMillis() - startTime

        Log.i(TAG, "Cache optimization V2 completed in ${elapsedMs}ms: " +
                "base=[$baseResult], corruptionsRepaired=$repaired, diskCleaned=$diskCleaned")

        return OptimizeResultV2(
            baseResult = baseResult,
            corruptionRepairs = repaired,
            diskEntriesCleaned = diskCleaned,
            elapsedMs = elapsedMs,
            totalCorruptionsAllTime = totalCorruptionRepairs
        )
    }

    /**
     * 缓存优化结果（原有，保持不变）
     */
    data class OptimizeResult(
        val cleanedEntries: Int,
        val evictedColdEntries: Int,
        val gcFreedBytes: Long,
        val beforeSizeBytes: Long,
        val afterSizeBytes: Long,
        val beforeEntryCount: Int,
        val afterEntryCount: Int
    ) {
        val totalFreedBytes: Long get() = beforeSizeBytes - afterSizeBytes + gcFreedBytes
        val totalEvictedEntries: Int get() = cleanedEntries + evictedColdEntries
    }

    /**
     * 增强版缓存优化结果（新增）
     */
    data class OptimizeResultV2(
        val baseResult: OptimizeResult,
        val corruptionRepairs: Int,
        val diskEntriesCleaned: Int,
        val elapsedMs: Long,
        val totalCorruptionsAllTime: Int
    ) {
        val totalImprovements: Int get() = baseResult.totalEvictedEntries + corruptionRepairs + diskEntriesCleaned
    }

    // ==================== Shutdown（增强版）====================

    /**
     * 关闭缓存管理器，释放所有资源
     *
     * 增强:
     * - 注销 ComponentCallbacks2 回调
     * - 关闭 TieredMemoryCache
     * - 关闭 UnifiedDiskCache（含 coalescer flush）
     * - 取消所有协程
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down GameDataCacheManager (v2 Facade pattern)...")

        // 1. 注销 ComponentCallbacks2 回调
        try {
            (context.applicationContext as Application).unregisterComponentCallbacks(this)
            Log.d(TAG, "ComponentCallbacks2 unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister ComponentCallbacks2: ${e.message}")
        }

        // 2. 取消后台任务
        cleanupJob?.cancel()
        cleanupJob = null
        memoryMonitorJob?.cancel()
        memoryMonitorJob = null

        // 3. 移除内存监听器
        memoryManager?.removeListener(this)

        // 4. 停止写管道
        writePipeline.shutdown()

        // 5. 关闭统一磁盘缓存（会 flush coalescer + 持久化索引）
        unifiedDiskCache.shutdown()

        // 6. 清理新组件（v2 新增）
        scope.launch {
            simpleBloomFilter.clear()
            Log.d(TAG, "SimpleBloomFilter cleared")
        }

        // 7. 取消所有协程
        scope.cancel()

        Log.i(TAG, "GameDataCacheManager v2 shutdown completed. Final stats: ${getStats().formatSummary()}")
    }
}

// ==================== 扩展数据类定义 ====================

/**
 * O1: 全局聚合统计视图
 *
 * 跨 TieredMemoryCache + UnifiedDiskCache + DirtyTracker + WriteQueue + Pressure 的统一视图
 */
data class GlobalCacheStats(
    val totalMemoryBytes: Long = 0,
    val totalDiskBytes: Long = 0,
    val totalEntries: Int = 0,
    val memoryHitRate: Double = 0.0,
    val diskHitRate: Double = 0.0,
    val staleHitRate: Double = 0.0,
    val corruptionCount: Long = 0,
    val dirtyCount: Long = 0,
    val writeQueuePending: Long = 0,
    val pressureLevel: String = "LOW",
    val smoothedPressureRatio: Float = 0.1f,
    val partitionDistribution: Map<String, Int> = emptyMap(),
    val diskStorageBreakdown: Map<String, Long> = emptyMap(),
    val activeSlots: Set<Int> = emptySet(),
    val timestamp: Long = 0L
) {
    val overallHitRate: Double
        get() = memoryHitRate  // 主要看内存命中率

    val healthScore: Int
        get() {
            var score = 100
            if (memoryHitRate < 0.5) score -= 30
            if (corruptionCount > 0) score -= 20
            if (pressureLevel == "CRITICAL") score -= 30
            if (pressureLevel == "HIGH") score -= 15
            if (smoothedPressureRatio < 0.3f) score -= 10
            return score.coerceIn(0, 100)
        }

    fun formatSummary(): String = buildString {
        appendLine("Global Cache Statistics (Aggregated):")
        appendLine("  Memory Hit Rate: ${"%.2f%%".format(memoryHitRate * 100)}")
        appendLine("  Disk Hit Rate: ${"%.2f%%".format(diskHitRate * 100)}")
        appendLine("  Stale Hit Rate (SWR): ${"%.2f%%".format(staleHitRate * 100)}")
        appendLine("  Total Memory: ${totalMemoryBytes / 1024}KB ($totalEntries entries)")
        appendLine("  Total Disk: ${totalDiskBytes / 1024}KB")
        appendLine("  Corruptions Repaired: $corruptionCount")
        appendLine("  Dirty Items: $dirtyCount")
        appendLine("  Write Queue Pending: $writeQueuePending")
        appendLine("  Pressure: $pressureLevel (ratio: ${"%.3f".format(smoothedPressureRatio)})")
        appendLine("  Health Score: $healthScore/100")
        appendLine("  Partitions: $partitionDistribution")
        appendLine("  Disk Breakdown: $diskStorageBreakdown")
    }
}
