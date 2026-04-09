package com.xianxia.sect.data.cache

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ## 写入合并器（Write Coalescer）
 *
 * 将短时间内对同一 key 的多次写入操作合并为一次，
 * 减少实际的 I/O 操作次数，提升写入吞吐量。
 *
 * ### 工作机制
 * 1. 当收到对 key 的写入请求时，检查是否在合并窗口内已有同 key 的待处理写入
 * 2. 如果有，则替换为最新值（合并），不立即执行写入
 * 3. 如果没有，则立即执行写入，并记录时间戳
 * 4. 后台协程定期扫描超时的合并条目并执行 flush
 *
 * ### 典型应用场景
 * - 磁盘缓存批量写入
 * - 数据库批量提交
 * - 日志聚合写入
 */
class WriteCoalescer(
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
    private val maxPendingPerKey: Int = DEFAULT_MAX_PENDING_PER_KEY
) {
    companion object {
        private const val TAG = "WriteCoalescer"

        /** 默认合并窗口（毫秒） */
        const val DEFAULT_COALESCE_WINDOW_MS = 500L

        /** 每个 key 最大待处理数 */
        const val DEFAULT_MAX_PENDING_PER_KEY = 10

        /** 后台 flush 扫描间隔 */
        private const val FLUSH_SCAN_INTERVAL_MS = 100L
    }

    /**
     * 合并条目数据结构
     */
    data class CoalescedEntry(
        val key: String,
        var value: Any?,           // 最新值（会被覆盖）
        val createdAt: Long,       // 首次创建时间
        var lastUpdateTime: Long,  // 最后更新时间（用于判断是否超时）
        var pendingCount: Int = 1, // 被合并的次数
        var flushed: Boolean = false // 是否已被 flush
    )

    /** 待合并写入存储：key -> CoalescedEntry */
    private val pendingMap = ConcurrentHashMap<String, CoalescedEntry>()

    /** 统计计数器 */
    private val totalWrites = AtomicLong(0)
    private val coalescedWrites = AtomicLong(0)   // 被合并的写入次数
    private val immediateWrites = AtomicLong(0)   // 立即执行的写入次数
    private val flushCount = AtomicLong(0)        // flush 次数
    private val currentPendingCount = AtomicInteger(0)

    /** 协程作用域 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台定时 flush 任务 */
    private var flushJob: Job? = null

    /** 是否已关闭 */
    @Volatile
    private var isShutdown = false

    /** 可选的默认 writer 回调（用于自动 flush 超时条目） */
    @Volatile
    private var defaultWriter: ((String, Any?) -> Boolean)? = null

    init {
        startFlushScheduler()
    }

    /**
     * 合并写入或立即执行
     *
     * @param key 缓存键
     * @param value 要写入的值
     * @param writer 实际执行写入的回调函数
     * @return true 表示写入成功（可能被合并延迟），false 表示失败
     */
    fun coalesce(key: String, value: Any?, writer: (String, Any?) -> Boolean): Boolean {
        if (isShutdown) {
            Log.w(TAG, "Coalescer is shutdown, rejecting write for key: $key")
            return false
        }

        totalWrites.incrementAndGet()
        val now = System.currentTimeMillis()

        synchronized(this) {
            val existing = pendingMap[key]
            if (existing != null && !existing.flushed) {
                // 在合并窗口内且未 flush -> 合并（只保留最新值）
                if (now - existing.createdAt < coalesceWindowMs && existing.pendingCount < maxPendingPerKey) {
                    existing.value = value
                    existing.lastUpdateTime = now
                    existing.pendingCount++
                    coalescedWrites.incrementAndGet()
                    Log.d(TAG, "Coalesced write for key=$key, pendingCount=${existing.pendingCount}")
                    return true
                }
            }

            // 不在窗口内或首次写入 -> 先 flush 已有的旧条目（如果有），然后记录新的
            if (existing != null && !existing.flushed) {
                doFlushEntry(existing, writer)
            }

            // 记录新条目
            val entry = CoalescedEntry(
                key = key,
                value = value,
                createdAt = now,
                lastUpdateTime = now
            )
            pendingMap[key] = entry
            currentPendingCount.incrementAndGet()
            immediateWrites.incrementAndGet()

            Log.d(TAG, "New immediate write for key=$key")
            return true
        }
    }

    /**
     * 强制刷新所有待处理的合并条目
     *
     * @param writer 实际执行写入的回调函数
     * @return 成功 flush 的条目数量
     */
    fun flushAll(writer: (String, Any?) -> Boolean): Int {
        if (isShutdown) return 0

        var flushed = 0
        synchronized(this) {
            val entries = pendingMap.values.toList()
            for (entry in entries) {
                if (!entry.flushed) {
                    if (doFlushEntry(entry, writer)) {
                        flushed++
                    }
                }
            }
        }

        if (flushed > 0) {
            Log.d(TAG, "Flushed $flushed entries manually")
        }
        return flushed
    }

    /**
     * 刷新单个条目
     */
    private fun doFlushEntry(entry: CoalescedEntry, writer: (String, Any?) -> Boolean): Boolean {
        return try {
            entry.flushed = true
            val success = writer(entry.key, entry.value)
            pendingMap.remove(entry.key)
            currentPendingCount.decrementAndGet()
            flushCount.incrementAndGet()

            if (!success) {
                Log.w(TAG, "Writer returned false for key=${entry.key}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing entry: ${entry.key}", e)
            // 即使失败也移除，避免无限堆积
            pendingMap.remove(entry.key)
            currentPendingCount.decrementAndGet()
            false
        }
    }

    /**
     * 启动后台定期 flush 调度器
     */
    private fun startFlushScheduler() {
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_SCAN_INTERVAL_MS)
                try {
                    scanAndExpire()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in flush scheduler", e)
                }
            }
        }
    }

    /**
     * 设置默认 writer 回调
     *
     * 设置后，scanAndExpire() 会自动 flush 超时条目。
     */
    fun setDefaultWriter(writer: (String, Any?) -> Boolean) {
        this.defaultWriter = writer
    }

    /**
     * 扫描并刷新所有超时的合并条目
     *
     * 如果已设置 defaultWriter，则自动 flush 所有超时条目；
     * 否则仅记录日志（向后兼容）。
     */
    private fun scanAndExpire() {
        val now = System.currentTimeMillis()
        val expiredEntries = mutableListOf<CoalescedEntry>()

        for ((key, entry) in pendingMap) {
            if (!entry.flushed && now - entry.lastUpdateTime > coalesceWindowMs) {
                expiredEntries.add(entry)
            }
        }

        if (expiredEntries.isEmpty()) return

        Log.d(TAG, "Found ${expiredEntries.size} expired coalesced entries")

        // 如果有默认 writer，自动 flush 超时条目
        val writer = defaultWriter
        if (writer != null) {
            var flushed = 0
            for (entry in expiredEntries) {
                synchronized(this) {
                    if (!entry.flushed) {
                        if (doFlushEntry(entry, writer)) {
                            flushed++
                        }
                    }
                }
            }
            if (flushed > 0) {
                Log.d(TAG, "Auto-flushed $flushed expired entries")
            }
        }
    }

    /**
     * 关闭写入合并器，释放资源
     *
     * @param writer 可选的最终 flush 回调，如果提供则在关闭前尝试 flush 所有剩余条目
     */
    fun shutdown(writer: ((String, Any?) -> Boolean)? = null) {
        if (isShutdown) return
        isShutdown = true

        // 取消后台任务
        flushJob?.cancel()
        flushJob = null

        // 尝试最终 flush
        if (writer != null && pendingMap.isNotEmpty()) {
            try {
                flushAll(writer)
            } catch (e: Exception) {
                Log.e(TAG, "Error during final flush on shutdown", e)
            }
        }

        // 清理剩余条目
        pendingMap.clear()
        currentPendingCount.set(0)

        // 取消协程作用域
        scope.cancel()

        Log.i(TAG, "WriteCoalescer shutdown completed. Stats: totalWrites=${totalWrites.get()}, " +
                "coalesced=${coalescedWrites.get()}, immediate=${immediateWrites.get()}, flushes=${flushCount.get()}")
    }

    // ==================== 统计方法 ====================

    fun getTotalWrites(): Long = totalWrites.get()
    fun getCoalescedWrites(): Long = coalescedWrites.get()
    fun getImmediateWrites(): Long = immediateWrites.get()
    fun getFlushCount(): Long = flushCount.get()
    fun getCurrentPendingCount(): Int = currentPendingCount.get()

    /**
     * 获取合并率（被合并的写入 / 总写入）
     */
    fun getCoalesceRate(): Double {
        val total = totalWrites.get()
        return if (total > 0) coalescedWrites.get().toDouble() / total else 0.0
    }

    fun isRunning(): Boolean = !isShutdown
}
