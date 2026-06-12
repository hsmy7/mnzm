package com.xianxia.sect.core.performance

import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder

data class MetricStats(
    val count: Long = 0,
    val min: Long = Long.MAX_VALUE,
    val max: Long = Long.MIN_VALUE,
    val avg: Double = 0.0,
    val p50: Long = 0,
    val p95: Long = 0,
    val p99: Long = 0,
    val sum: Long = 0,
    val lastValue: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = count == 0L
    
    val isNotEmpty: Boolean get() = count > 0L
    
    fun toFormattedString(): String {
        if (isEmpty) return "No data"
        return "count=$count, avg=${String.format(Locale.ROOT, "%.2f", avg)}, min=$min, max=$max, p50=$p50, p95=$p95, p99=$p99"
    }
}

class MetricCollector(
    val name: String,
    private val maxSamples: Int = 1000
) {
    private val samples = ConcurrentLinkedQueue<Long>()
    private val count = AtomicLong(0)
    private val sum = AtomicLong(0)
    private val min = AtomicLong(Long.MAX_VALUE)
    private val max = AtomicLong(Long.MIN_VALUE)
    private val lastValue = AtomicLong(0)
    private val sumSquares = DoubleAdder()
    
    @Synchronized
    fun record(value: Long) {
        samples.offer(value)
        while (samples.size > maxSamples) {
            val removed = samples.poll()
            if (removed != null) {
                sum.addAndGet(-removed)
                sumSquares.add(-removed.toDouble() * removed.toDouble())
            }
        }
        
        count.incrementAndGet()
        sum.addAndGet(value)
        sumSquares.add(value.toDouble() * value.toDouble())
        lastValue.set(value)
        
        min.updateAndGet { current -> if (current == Long.MAX_VALUE) value else minOf(current, value) }
        max.updateAndGet { current -> if (current == Long.MIN_VALUE) value else maxOf(current, value) }
    }
    
    fun getStats(): MetricStats {
        val sampleList: List<Long>
        val currentCount: Long
        val currentSum: Long
        val currentMin: Long
        val currentMax: Long
        val currentLastValue: Long
        
        synchronized(this) {
            sampleList = samples.toList()
            currentCount = count.get()
            currentSum = sum.get()
            currentMin = min.get()
            currentMax = max.get()
            currentLastValue = lastValue.get()
        }
        
        if (currentCount == 0L || sampleList.isEmpty()) {
            return MetricStats()
        }
        
        val sorted = sampleList.sorted()
        
        return MetricStats(
            count = sampleList.size.toLong(),
            min = sorted.minOrNull() ?: 0,
            max = sorted.maxOrNull() ?: 0,
            avg = if (sampleList.isNotEmpty()) sampleList.average() else 0.0,
            p50 = getPercentile(sorted, 0.50),
            p95 = getPercentile(sorted, 0.95),
            p99 = getPercentile(sorted, 0.99),
            sum = sorted.sum(),
            lastValue = currentLastValue
        )
    }
    
    private fun getPercentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
    
    fun reset() {
        samples.clear()
        count.set(0)
        sum.set(0)
        min.set(Long.MAX_VALUE)
        max.set(Long.MIN_VALUE)
        lastValue.set(0)
        sumSquares.reset()
    }
    
    fun getSampleCount(): Int = samples.size
    
    fun getSamples(): List<Long> = samples.toList()
    
    fun getVariance(): Double {
        val currentCount = count.get()
        if (currentCount < 2) return 0.0
        
        val mean = sum.get().toDouble() / currentCount
        val currentSumSquares = sumSquares.sum()
        
        return (currentSumSquares / currentCount) - (mean * mean)
    }
    
    fun getStandardDeviation(): Double {
        return kotlin.math.sqrt(getVariance())
    }
}

data class Trace(
    val name: String,
    val startTime: Long,
    val tags: Map<String, String> = emptyMap(),
    val endTime: Long = 0,
    val duration: Long = 0,
    val isCompleted: Boolean = false
) {
    fun complete(): Trace {
        val endTime = System.nanoTime()
        return copy(
            endTime = endTime,
            duration = endTime - startTime,
            isCompleted = true
        )
    }
    
    fun durationMs(): Double = duration / 1_000_000.0
    
    fun durationMicros(): Double = duration / 1_000.0
    
    fun toFormattedString(): String {
        val status = if (isCompleted) "completed" else "running"
        return "Trace[$name]: ${String.format(Locale.ROOT, "%.3f", durationMs())}ms ($status)"
    }
}

enum class MetricCategory {
    PERFORMANCE,
    MEMORY,
    DATABASE,
    NETWORK,
    UI,
    GAME_LOOP,
    STORAGE,
    CUSTOM
}

data class MetricDefinition(
    val name: String,
    val category: MetricCategory,
    val unit: String,
    val description: String = "",
    val warningThreshold: Long? = null,
    val criticalThreshold: Long? = null
)

interface MetricsListener {
    fun onMetricRecorded(name: String, value: Long, stats: MetricStats)
    fun onThresholdExceeded(name: String, value: Long, threshold: Long, isCritical: Boolean)
    fun onTraceCompleted(trace: Trace)
}
