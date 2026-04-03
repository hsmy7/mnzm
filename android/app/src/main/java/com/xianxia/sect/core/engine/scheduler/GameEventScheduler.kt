package com.xianxia.sect.core.engine.scheduler

import android.util.Log

/**
 * 游戏事件调度器 - 用于组织月度/年度事件的有序执行
 *
 * 特性：
 * - 支持优先级分类（CRITICAL / HIGH / NORMAL / LOW）
 * - 每个事件独立 try-catch，单个失败不影响其他
 * - 内置执行耗时监控，超阈值输出警告日志
 */
class GameEventScheduler(private val tag: String = "GameEventScheduler") {

    private val events = mutableListOf<ScheduledEvent>()

    /**
     * 注册一个事件到调度器
     *
     * @param name 事件名称（用于日志和调试）
     * @param priority 优先级（决定执行顺序）
     * @param executionBlock 事件执行逻辑
     */
    fun register(
        name: String,
        priority: EventPriority,
        executionBlock: () -> Unit
    ) {
        events.add(ScheduledEvent(name, priority, executionBlock))
    }

    /**
     * 按优先级顺序执行所有已注册的事件
     *
     * 执行规则：
     * 1. 按 priority.order 升序排列（CRITICAL=0 最先执行）
     * 2. 每个事件独立 try-catch
     * 3. 记录执行耗时，超过 [executionTimeWarningThresholdMs] 输出警告
     */
    fun executeAll(contextInfo: String = "") {
        // 按优先级排序：CRITICAL(0) -> HIGH(1) -> NORMAL(2) -> LOW(3)
        val sortedEvents = events.sortedBy { it.priority.order }

        sortedEvents.forEach { event ->
            val startTime = System.currentTimeMillis()
            try {
                event.executionBlock()
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > executionTimeWarningThresholdMs) {
                    Log.w(
                        tag,
                        "[PERF] ${event.name} took ${elapsed}ms (threshold: ${executionTimeWarningThresholdMs}ms)${
                            if (contextInfo.isNotEmpty()) " | $contextInfo" else ""
                        }"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    tag,
                    "Error in ${event.name}${if (contextInfo.isNotEmpty()) " | $contextInfo" else ""}",
                    e
                )
            }
        }
    }

    /**
     * 清空所有已注册的事件（允许复用实例）
     */
    fun clear() {
        events.clear()
    }

    companion object {
        /** 执行耗时警告阈值（毫秒） */
        const val executionTimeWarningThresholdMs: Long = 50L
    }
}

/**
 * 已调度的游戏事件
 */
data class ScheduledEvent(
    val name: String,
    val priority: EventPriority,
    val executionBlock: () -> Unit
)

/**
 * 事件优先级枚举
 *
 * 数值越小优先级越高，执行越靠前。
 */
enum class EventPriority(val order: Int) {
    /** 核心逻辑：薪资发放、弟子 aging 等 */
    CRITICAL(0),

    /** 重要逻辑：建筑、探索队、战斗队移动 */
    HIGH(1),

    /** 常规逻辑：商人刷新、外交、任务 */
    NORMAL(2),

    /** 低优先级：统计、日志清理等 */
    LOW(3)
}
