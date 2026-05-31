package com.xianxia.sect.core.engine.settlement

import android.util.Log

data class SettlementMetrics(
    val monthYear: Pair<Int, Int>,
    val totalDurationMs: Float,
    val cacheBuildMs: Float,
    val focusedDiscipleMs: Float,
    val cleanBatchMs: Float,
    val dirtyBatchMs: Float,
    val dirtyDiscipleCount: Int,
    val totalDiscipleCount: Int,
    val productionMs: Float,
    val worldEventsMs: Float,
    val shadowSwapMs: Float,
    val frameCount: Int
) {
    fun toSummary(): String {
        return "Y${monthYear.first}M${monthYear.second} " +
            "total=${totalDurationMs.format()}ms " +
            "cache=${cacheBuildMs.format()} " +
            "focused=${focusedDiscipleMs.format()} " +
            "clean=${cleanBatchMs.format()} " +
            "dirty=${dirtyBatchMs.format()}(${dirtyDiscipleCount}/${totalDiscipleCount}) " +
            "prod=${productionMs.format()} " +
            "events=${worldEventsMs.format()} " +
            "swap=${shadowSwapMs.format()} " +
            "frames=$frameCount"
    }

    private fun Float.format(): String = String.format("%.2f", this)
}

class SettlementMetricsCollector {
    private val history = mutableListOf<SettlementMetrics>()
    private var reportInterval = 10

    fun record(metrics: SettlementMetrics) {
        history.add(metrics)
        if (history.size >= reportInterval) {
            val batch = history.toList()
            history.clear()
            logAggregate(batch)
        }
    }

    private fun logAggregate(batch: List<SettlementMetrics>) {
        if (batch.isEmpty()) return
        val avgTotal = batch.map { it.totalDurationMs }.average().toFloat()
        val avgCache = batch.map { it.cacheBuildMs }.average().toFloat()
        val avgClean = batch.map { it.cleanBatchMs }.average().toFloat()
        val avgDirty = batch.map { it.dirtyBatchMs }.average().toFloat()
        val avgFrames = batch.map { it.frameCount }.average()
        val avgDirtyCount = batch.map { it.dirtyDiscipleCount }.average()
        val avgTotalCount = batch.map { it.totalDiscipleCount }.average()

        Log.i(TAG, "SettlementMetrics[${batch.size}x]: " +
            "avgTotal=${String.format("%.2f", avgTotal)}ms " +
            "avgCache=${String.format("%.2f", avgCache)}ms " +
            "avgClean=${String.format("%.2f", avgClean)}ms " +
            "avgDirty=${String.format("%.2f", avgDirty)}ms " +
            "avgDirtyRatio=${String.format("%.0f", avgDirtyCount)}/${String.format("%.0f", avgTotalCount)} " +
            "avgFrames=${String.format("%.1f", avgFrames)}"
        )
    }

    companion object {
        private const val TAG = "SettlementMetrics"
    }
}

class SettlementTimer {
    private var startTimeNs: Long = 0L
    private var accumulatedMs: Float = 0f

    fun start() {
        startTimeNs = System.nanoTime()
    }

    fun stop(): Float {
        val elapsed = (System.nanoTime() - startTimeNs) / 1_000_000f
        accumulatedMs += elapsed
        return elapsed
    }

    fun accumulated(): Float = accumulatedMs

    fun reset() {
        accumulatedMs = 0f
    }
}
