package com.xianxia.sect.core.util

import kotlinx.coroutines.*

/**
 * 统一后台任务调度器：用一个共享 1 秒心跳替代多个独立 while(isActive) 循环。
 *
 * 使用方式：
 * ```
 * val scheduler = BackgroundTaskScheduler(scope)
 * scheduler.register("MemoryMonitor", 30) { memoryMonitor.sample() }
 * scheduler.register("CacheCleanup", 60) { cacheLayer.cleanup() }
 * scheduler.start()
 * ```
 */
class BackgroundTaskScheduler(
    private val scope: CoroutineScope,
    private val heartbeatMs: Long = 1000L
) {
    private var job: Job? = null
    private val tasks = java.util.concurrent.CopyOnWriteArrayList<ScheduledTask>()
    private var tickCount = 0L

    data class ScheduledTask(
        val name: String,
        val intervalSeconds: Int,
        val action: suspend () -> Unit
    )

    fun register(name: String, intervalSeconds: Int, action: suspend () -> Unit) {
        tasks.add(ScheduledTask(name, intervalSeconds.coerceAtLeast(1), action))
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val tick = tickCount++
                tasks.forEach { task ->
                    if (tick % task.intervalSeconds == 0L) {
                        try {
                            task.action()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DomainLog.e("BgTaskScheduler", "Error in ${task.name}", e)
                        }
                    }
                }
                delay(heartbeatMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun pause() {
        job?.cancel()
        job = null
        tickCount = 0L
    }

    fun resume() {
        if (job?.isActive == true) return
        start()
    }

    val isActive: Boolean get() = job?.isActive == true
}
