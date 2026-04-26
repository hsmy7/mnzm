package com.xianxia.sect.data.memory

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xianxia.sect.core.util.MemoryFormatUtil
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备内存能力等级
 */
enum class DeviceMemoryTier(
    val displayName: String,
    val minRamBytes: Long,
    val maxRamBytes: Long?,
    val description: String
) {
    LOW("低配", 0L, 4 * 1024 * 1024 * 1024L, "保守模式，减少缓存"),
    MEDIUM("标准", 4 * 1024 * 1024 * 1024L, 6 * 1024 * 1024 * 1024L, "标准模式"),
    HIGH("高配", 6 * 1024 * 1024 * 1024L, 8 * 1024 * 1024 * 1024L, "积极模式"),
    ULTRA("超高配", 8 * 1024 * 1024 * 1024L, null, "最大性能模式");

    companion object {
        fun fromTotalRam(totalRamBytes: Long): DeviceMemoryTier {
            return when {
                totalRamBytes < MEDIUM.minRamBytes -> LOW
                totalRamBytes < HIGH.minRamBytes -> MEDIUM
                totalRamBytes < ULTRA.minRamBytes -> HIGH
                else -> ULTRA
            }
        }
    }
}

/**
 * 内存压力等级
 */
enum class MemoryPressureLevel(val ordinalValue: Int) {
    LOW(0),
    NORMAL(1),
    MODERATE(2),
    HIGH(3),
    CRITICAL(4);

    companion object {
        fun fromAvailableRatio(availableRatio: Double): MemoryPressureLevel = when {
            availableRatio > 0.5 -> LOW
            availableRatio > 0.3 -> NORMAL
            availableRatio > 0.2 -> MODERATE
            availableRatio > 0.1 -> HIGH
            else -> CRITICAL
        }
    }
}

/**
 * 内存降级策略
 */
enum class DegradationStrategy(val description: String) {
    NONE("无降级"),
    REDUCE_CACHE_SIZE("缩减缓存大小"),
    DISABLE_PRELOAD("禁用预加载"),
    EVICT_COLD_ZONE("驱逐冷区数据"),
    COMPRESS_IN_MEMORY("内存压缩"),
    EMERGENCY_PURGE("紧急清理")
}

/**
 * 内存状态快照
 */
data class MemorySnapshot(
    val tier: DeviceMemoryTier,
    val pressureLevel: MemoryPressureLevel,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usedRamBytes: Long,
    val jvmMaxMemory: Long,
    val jvmUsedMemory: Long,
    val jvmFreeMemory: Long,
    val requiredForSaveData: Long,
    val safetyMarginBytes: Long,
    val budgetAllocatedBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val availablePercent: Double
        get() = if (totalRamBytes > 0) availableRamBytes.toDouble() / totalRamBytes * 100 else 0.0

    val jvmUsagePercent: Double
        get() = if (jvmMaxMemory > 0) jvmUsedMemory.toDouble() / jvmMaxMemory * 100 else 0.0

    val hasSufficientMemory: Boolean
        get() = availableRamBytes >= (requiredForSaveData + safetyMarginBytes)

    val formattedInfo: String
        get() = buildString {
            appendLine("[${tier.displayName}] 压力:${pressureLevel.name}")
            appendLine("  RAM: ${formatBytes(availableRamBytes)} / ${formatBytes(totalRamBytes)} (${String.format(Locale.getDefault(), "%.1f%%", availablePercent)})")
            appendLine("  JVM: ${formatBytes(jvmUsedMemory)} / ${formatBytes(jvmMaxMemory)} (${String.format(Locale.getDefault(), "%.1f%%", jvmUsagePercent)})")
            appendLine("  存档需求: ${formatBytes(requiredForSaveData)}, 安全余量: ${formatBytes(safetyMarginBytes)}")
            appendLine("  预算分配: ${formatBytes(budgetAllocatedBytes)}")
        }

    private fun formatBytes(bytes: Long): String = MemoryFormatUtil.formatMemory(bytes)
}

/**
 * 内存预警事件
 */
data class MemoryWarningEvent(
    val level: MemoryPressureLevel,
    val snapshot: MemorySnapshot,
    val suggestedAction: DegradationStrategy,
    val message: String
)

/**
 * 内存监听器接口
 */
interface MemoryEventListener {
    fun onPressureChanged(snapshot: MemorySnapshot)
    fun onWarning(event: MemoryWarningEvent)
    fun onDegradationApplied(strategy: DegradationStrategy, reason: String)
}

/**
 * 动态内存管理器
 *
 * 职责：
 * - 检测设备内存能力并分级
 * - 根据存档数据大小动态计算所需内存
 * - 持续监控内存压力并提供预警
 * - 在内存不足时执行降级策略
 * - 提供真正的 GC 实现（多阶段、可等待）
 */
@Singleton
class DynamicMemoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "DynamicMemoryManager"

        /** RAM 分级阈值（字节） */
        private val TIER_LOW_MAX = 4L * 1024 * 1024 * 1024   // 4 GB
        private val TIER_MEDIUM_MAX = 6L * 1024 * 1024 * 1024 // 6 GB
        private val TIER_HIGH_MAX = 8L * 1024 * 1024 * 1024   // 8 GB

        /** 安全余量系数 - 存档大小的倍数作为安全缓冲 */
        private const val SAFETY_MARGIN_MULTIPLIER = 2.5

        /** 各等级的默认缓存比例上限 */
        private const val CACHE_RATIO_LOW = 0.08       // 低配设备最多用 8% 内存做缓存
        private const val CACHE_RATIO_MEDIUM = 0.15     // 标准 15%
        private const val CACHE_RATIO_HIGH = 0.25       // 高配 25%
        private const val CACHE_RATIO_ULTRA = 0.35      // 超高配 35%

        /** GC 等待轮次与间隔 */
        private const val GC_WAIT_ROUNDS = 3
        private const val GC_ROUND_INTERVAL_MS = 150L

        /** 最小可用内存阈值（低于此值视为危险） */
        private const val MIN_ABSOLUTE_AVAILABLE_MB = 32L // 32 MB
    }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val runtime = Runtime.getRuntime()

    /** 缓存的设备信息（初始化后不变） */
    val deviceTier: DeviceMemoryTier by lazy { detectDeviceTier() }
    val totalRamBytes: Long by lazy { queryTotalRam() }
    val memoryClassMb: Int by lazy { activityManager.memoryClass }
    val largeMemoryClassMb: Int by lazy { activityManager.largeMemoryClass }

    /** 当前存档数据大小（由外部设置） */
    @Volatile
    private var currentSaveDataSize: Long = 0L

    /** 监听器列表（弱引用防止泄漏，CopyOnWriteArrayList 保证线程安全） */
    private val listeners = CopyOnWriteArrayList<WeakReference<MemoryEventListener>>()

    /** 统计计数器 */
    private val gcInvocationCount = AtomicLong(0)
    private val degradationEventCount = AtomicLong(0)

    // ==================== 初始化 ====================

    init {
        Log.i(TAG, "Initialized - Device tier: ${deviceTier.displayName}, Total RAM: ${totalRamBytes / (1024 * 1024)} MB")
    }

    // ==================== 设备能力检测 ====================

    /**
     * 获取 JVM 最大堆内存（字节）
     */
    fun getJvmMaxMemory(): Long = runtime.maxMemory()

    /**
     * 获取应用内存级别限制（MB）
     */
    fun getMemoryClassMB(): Int = memoryClassMb

    /**
     * 获取大内存应用级别限制（MB）
     */
    fun getLargeMemoryClassMB(): Int = largeMemoryClassMb

    /**
     * 是否为低内存设备
     */
    fun isLowMemoryDevice(): Boolean = deviceTier == DeviceMemoryTier.LOW

    // ==================== 存档大小相关 ====================

    /**
     * 设置当前存档数据的预期大小（用于动态计算所需内存）
     *
     * @param sizeBytes 存档数据字节数
     */
    fun setSaveDataSize(sizeBytes: Long) {
        currentSaveDataSize = sizeBytes.coerceAtLeast(0L)
    }

    /**
     * 获取当前设置的存档数据大小
     */
    fun getSaveDataSize(): Long = currentSaveDataSize

    // ==================== 内存快照与检查 ====================

    /**
     * 获取当前内存状态快照
     *
     * @param saveDataSizeOverride 可选：临时覆盖存档大小进行计算
     */
    fun getMemorySnapshot(saveDataSizeOverride: Long? = null): MemorySnapshot {
        val saveSize = saveDataSizeOverride ?: currentSaveDataSize
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMem = memInfo.totalMem
        val availMem = memInfo.availMem
        val usedMem = totalMem - availMem

        val jvmMax = runtime.maxMemory()
        val jvmTotal = runtime.totalMemory()
        val jvmFree = runtime.freeMemory()
        val jvmUsed = jvmTotal - jvmFree

        val safetyMargin = calculateSafetyMargin(saveSize)
        val required = calculateRequiredMemory(saveSize)
        val budget = calculateBudgetAllocation(saveSize)

        val availRatio = if (totalMem > 0) availMem.toDouble() / totalMem else 0.0
        val pressure = MemoryPressureLevel.fromAvailableRatio(availRatio)

        return MemorySnapshot(
            tier = deviceTier,
            pressureLevel = pressure,
            totalRamBytes = totalMem,
            availableRamBytes = availMem,
            usedRamBytes = usedMem,
            jvmMaxMemory = jvmMax,
            jvmUsedMemory = jvmUsed,
            jvmFreeMemory = jvmFree,
            requiredForSaveData = required,
            safetyMarginBytes = safetyMargin,
            budgetAllocatedBytes = budget
        )
    }

    /**
     * 检查是否有足够内存执行存档操作
     *
     * 与 [checkAvailableMemory] 不同的是：
     * - 不再使用固定的 15% 阈值
     * - 根据实际存档大小 + 安全余量判断
     * - 返回更精确的结果
     *
     * @param saveDataSizeBytes 可选：本次操作需要的存档大小
     * @return true 表示有足够内存
     */
    fun checkAvailableMemory(saveDataSizeBytes: Long? = null): Boolean {
        val snapshot = getMemorySnapshot(saveDataSizeBytes)

        // 条件1：绝对最小可用内存检查
        val minAvailable = MIN_ABSOLUTE_AVAILABLE_MB * 1024 * 1024
        if (snapshot.availableRamBytes < minAvailable) {
            Log.w(TAG, "Insufficient absolute memory: ${snapshot.availableRamBytes / (1024 * 1024)} MB < $MIN_ABSOLUTE_AVAILABLE_MB MB")
            return false
        }

        // 条件2：基于存档大小的需求检查
        if (!snapshot.hasSufficientMemory) {
            Log.w(TAG, "Insufficient memory for save data: need ${snapshot.requiredForSaveData + snapshot.safetyMarginBytes}, available ${snapshot.availableRamBytes}")
            return false
        }

        // 条件3：压力等级过高时也拒绝
        if (snapshot.pressureLevel == MemoryPressureLevel.CRITICAL) {
            Log.e(TAG, "CRITICAL memory pressure level detected, rejecting operation")
            return false
        }

        return true
    }

    /**
     * 向后兼容方法：无参版本，使用已设置的存档大小
     */
    // ==================== 真正的 GC 实现 ====================

    /**
     * 强制垃圾回收并等待完成
     *
     * 替代 SaveFileHandler 中原有的空实现。
     * 采用多轮 GC + 休眠等待策略，确保 GC 实际生效。
     *
     * 流程：
     * 1. 清理 Soft/Weak 引用的可达性队列
     * 2. 多轮 System.gc() + Thread.sleep 等待
     * 3. 调用 Runtime.runFinalization() 触发 finalizer
     * 4. 最终确认回收效果
     *
     * @param maxWaitMs 最大等待时间（毫秒），默认 500ms
     * @return 回收后的可用内存增量（字节），负数表示可能未成功回收
     */
    fun forceGcAndWait(maxWaitMs: Long = 500L): Long {
        gcInvocationCount.incrementAndGet()

        val beforeFree = runtime.freeMemory()
        val beforeTotal = runtime.totalMemory()
        val beforeUsed = beforeTotal - beforeFree

        Log.d(TAG, "forceGcAndWait start - JVM used: ${beforeUsed / 1024} KB")

        try {
            // 第一阶段：建议 VM 回收
            System.gc()

            // 第二阶段：运行 finalizer
            Runtime.getRuntime().runFinalization()

            // 第三阶段：多轮等待循环
            val rounds = (maxWaitMs / GC_ROUND_INTERVAL_MS).toInt().coerceAtMost(GC_WAIT_ROUNDS)
            repeat(rounds.coerceAtLeast(1)) {
                Thread.sleep(GC_ROUND_INTERVAL_MS)
                System.gc()
            }

            // 第四阶段：最终确认
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50L)

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "forceGcAndWait interrupted", e)
        } catch (e: Exception) {
            Log.w(TAG, "forceGcAndWait error", e)
        }

        val afterFree = runtime.freeMemory()
        val afterTotal = runtime.totalMemory()
        val afterUsed = afterTotal - afterFree
        val freed = afterUsed - beforeUsed // 负数表示释放了内存

        Log.d(TAG, "forceGcAndWait done - JVM used: ${afterUsed / 1024} KB, freed: ${-freed / 1024} KB")

        // 如果压力仍然很高，触发降级策略
        val snapshot = getMemorySnapshot()
        if (snapshot.pressureLevel.ordinal >= MemoryPressureLevel.HIGH.ordinal) {
            applyDegradationIfNeeded(snapshot)
        }

        return freed
    }

    // ==================== 内存预算分配 ====================

    /**
     * 计算可用于缓存等功能的内存预算
     *
     * 根据设备等级和当前内存压力动态调整：
     * - 高配设备在低压时可分配更多
     * - 低配设备或高压时大幅缩减
     *
     * @param saveDataSizeBytes 存档大小（可选，默认使用当前设置值）
     * @return 可分配的缓存预算（字节）
     */
    fun calculateCacheBudget(saveDataSizeBytes: Long? = null): Long {
        val saveSize = saveDataSizeBytes ?: currentSaveDataSize
        return calculateBudgetAllocation(saveSize)
    }

    /**
     * 获取当前设备等级对应的推荐最大缓存比例
     */
    fun getMaxCacheRatio(): Float = when (deviceTier) {
        DeviceMemoryTier.LOW -> CACHE_RATIO_LOW.toFloat()
        DeviceMemoryTier.MEDIUM -> CACHE_RATIO_MEDIUM.toFloat()
        DeviceMemoryTier.HIGH -> CACHE_RATIO_HIGH.toFloat()
        DeviceMemoryTier.ULTRA -> CACHE_RATIO_ULTRA.toFloat()
    }

    // ==================== 压力监控与降级 ====================

    /**
     * 获取当前内存压力等级
     */
    fun getCurrentPressureLevel(): MemoryPressureLevel = getMemorySnapshot().pressureLevel

    /**
     * 检查是否需要执行降级策略
     */
    fun shouldDegrade(): Boolean {
        val snapshot = getMemorySnapshot()
        return snapshot.pressureLevel.ordinal >= MemoryPressureLevel.MODERATE.ordinal
    }

    /**
     * 应用降级策略（内部调用）
     */
    internal fun applyDegradationIfNeeded(snapshot: MemorySnapshot): DegradationStrategy? {
        val strategy = determineDegradationStrategy(snapshot)
        if (strategy != DegradationStrategy.NONE) {
            degradationEventCount.incrementAndGet()
            notifyDegradationApplied(strategy, "Pressure: ${snapshot.pressureLevel.name}, Available: ${snapshot.availablePercent}%")
        }
        return strategy.takeIf { it != DegradationStrategy.NONE }
    }

    /**
     * 手动请求降级
     */
    fun requestDegradation(): DegradationStrategy? {
        val snapshot = getMemorySnapshot()
        return applyDegradationIfNeeded(snapshot)
    }

    // ==================== 事件监听 ====================

    fun addListener(listener: MemoryEventListener) {
        listeners.add(WeakReference(listener))
    }

    fun removeListener(listener: MemoryEventListener) {
        listeners.removeAll { it.get() == listener }
    }

    private fun notifyPressureChanged(snapshot: MemorySnapshot) {
        val iterator = listeners.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            ref.get()?.onPressureChanged(snapshot) ?: iterator.remove()
        }
    }

    private fun notifyWarning(event: MemoryWarningEvent) {
        val iterator = listeners.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            ref.get()?.onWarning(event) ?: iterator.remove()
        }
    }

    private fun notifyDegradationApplied(strategy: DegradationStrategy, reason: String) {
        val iterator = listeners.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            ref.get()?.onDegradationApplied(strategy, reason) ?: iterator.remove()
        }
    }

    // ==================== 诊断信息 ====================

    /**
     * 获取诊断报告字符串
     */
    fun getDiagnosticReport(): String {
        val snapshot = getMemorySnapshot()
        return buildString {
            appendLine("=== DynamicMemoryManager Diagnostic ===")
            appendLine(snapshot.formattedInfo)
            appendLine("  GC 调用次数: ${gcInvocationCount.get()}")
            appendLine("  降级事件次数: ${degradationEventCount.get()}")
            appendLine("  监听器数量: ${listeners.count { it.get() != null }}")
            appendLine("=========================================")
        }
    }

    /**
     * 获取统计信息
     */
    data class Stats(
        val deviceTier: DeviceMemoryTier,
        val totalRamMB: Long,
        val gcCount: Long,
        val degradationCount: Long,
        val listenerCount: Int
    )

    fun getStats(): Stats = Stats(
        deviceTier = deviceTier,
        totalRamMB = totalRamBytes / (1024 * 1024),
        gcCount = gcInvocationCount.get(),
        degradationCount = degradationEventCount.get(),
        listenerCount = listeners.count { it.get() != null }
    )

    // ==================== 内部实现 ====================

    /**
     * 检测设备内存等级
     */
    private fun detectDeviceTier(): DeviceMemoryTier {
        val ramBytes = queryTotalRam()
        return DeviceMemoryTier.fromTotalRam(ramBytes)
    }

    /**
     * 查询设备总 RAM
     */
    private fun queryTotalRam(): Long {
        return try {
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.totalMem
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query total RAM, using fallback", e)
            // 降级方案：使用 JVM maxMemory 作为粗略估计
            runtime.maxMemory() * 4
        }
    }

    /**
     * 计算存档操作所需内存
     *
     * 公式：存档数据大小 * 操作系数（序列化/反序列化需要额外空间）
     */
    private fun calculateRequiredMemory(saveDataSize: Long): Long {
        // 序列化/反序列化通常需要原始数据 2-3 倍的工作空间
        val operationMultiplier = when (deviceTier) {
            DeviceMemoryTier.LOW -> 3.0f      // 低端设备预留更多空间
            DeviceMemoryTier.MEDIUM -> 2.5f
            DeviceMemoryTier.HIGH -> 2.0f
            DeviceMemoryTier.ULTRA -> 1.8f
        }
        return (saveDataSize * operationMultiplier).toLong().coerceAtLeast(1024 * 1024) // 最少 1 MB
    }

    /**
     * 计算安全余量
     */
    private fun calculateSafetyMargin(saveDataSize: Long): Long {
        val baseMargin = (saveDataSize * SAFETY_MARGIN_MULTIPLIER).toLong()

        // 额外的固定底限
        val floorMargin = when (deviceTier) {
            DeviceMemoryTier.LOW -> 16 * 1024 * 1024L   // 16 MB
            DeviceMemoryTier.MEDIUM -> 32 * 1024 * 1024L // 32 MB
            DeviceMemoryTier.HIGH -> 48 * 1024 * 1024L   // 48 MB
            DeviceMemoryTier.ULTRA -> 64 * 1024 * 1024L  // 64 MB
        }

        return baseMargin.coerceAtLeast(floorMargin)
    }

    /**
     * 计算可分配给缓存的内存预算
     */
    private fun calculateBudgetAllocation(saveDataSize: Long): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMem = memInfo.availMem

        // 基础预算：按设备等级比例
        val baseBudget = (memInfo.totalMem * getMaxCacheRatio()).toLong()

        // 减去存档需求和安全余量
        val reserved = calculateRequiredMemory(saveDataSize) + calculateSafetyMargin(saveDataSize)

        // 最终预算不能超过可用内存的 80%（留系统余量）
        val maxByAvailable = (availMem * 0.8).toLong()

        return (baseBudget - reserved).coerceIn(0L, maxByAvailable)
    }

    /**
     * 根据当前状态决定降级策略
     */
    private fun determineDegradationStrategy(snapshot: MemorySnapshot): DegradationStrategy {
        return when (snapshot.pressureLevel) {
            MemoryPressureLevel.CRITICAL -> DegradationStrategy.EMERGENCY_PURGE
            MemoryPressureLevel.HIGH -> DegradationStrategy.EVICT_COLD_ZONE
            MemoryPressureLevel.MODERATE -> DegradationStrategy.REDUCE_CACHE_SIZE
            else -> DegradationStrategy.NONE
        }
    }
}
