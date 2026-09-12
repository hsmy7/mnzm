package com.xianxia.sect.core.performance

// ── 运行期采集域（自 UnifiedPerformanceMonitor 拆出，行为零变更） ─────────
// 指标注册表/监听器域已上提基类 PerformanceMetricsRegistry（同包同文件族）

private val MAX_SAMPLES = UnifiedPerformanceMonitor.MAX_SAMPLES

fun UnifiedPerformanceMonitor.recordTick(durationMs: Float) {
    tickTimes.offer(durationMs)
    if (tickTimes.size > MAX_SAMPLES) {
        tickTimes.poll()
    }
    tickCounter.incrementAndGet()
}

fun UnifiedPerformanceMonitor.recordFrame(durationMs: Float) {
    frameTimesGpm.offer(durationMs)
    if (frameTimesGpm.size > MAX_SAMPLES) {
        frameTimesGpm.poll()
    }
    frameCountGpm++
}

fun UnifiedPerformanceMonitor.recordSaveQueueSize(size: Int) {
    currentSaveQueueSize = size
}

fun UnifiedPerformanceMonitor.recordEntityCount(count: Int) {
    currentEntityCount = count
}

fun UnifiedPerformanceMonitor.clearLoadReductionRequest() { _loadReductionRequested = false }

