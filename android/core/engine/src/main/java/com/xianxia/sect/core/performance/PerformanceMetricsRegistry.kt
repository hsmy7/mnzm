package com.xianxia.sect.core.performance

import com.xianxia.sect.core.util.DomainLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 性能指标注册表基类（自 [UnifiedPerformanceMonitor] 抽取）。
 *
 * 职责：指标定义/采集器注册、指标记录与查询、监听器注册与阈值通知。
 * 运行期采集（tick/帧/保存队列/实体数）、帧质量追踪与报告生成留在派生类。
 *
 * 抽取为继承而非顶层扩展：本类是跨模块消费面（app `GameMonitorManager`）
 * 且被 core:engine 测试以 mock 作为缝隙——扩展化会同时破坏跨模块调用与
 * mock stub/verify 语义，继承则两者零变化。
 */
abstract class PerformanceMetricsRegistry {

    companion object {
        internal const val TAG = "UnifiedPerformanceMonitor"
        private const val MAX_COLLECTORS = 100
    }

    protected val metricsCollectors = ConcurrentHashMap<String, MetricCollector>()
    protected val metricDefinitions = ConcurrentHashMap<String, MetricDefinition>()
    protected val listeners = CopyOnWriteArrayList<MetricsListener>()

    fun registerMetric(definition: MetricDefinition) {
        if (metricsCollectors.size >= MAX_COLLECTORS) {
            DomainLog.w(TAG, "Maximum metric collectors reached, cannot register: ${definition.name}")
            return
        }
        metricDefinitions[definition.name] = definition
        metricsCollectors.getOrPut(definition.name) { MetricCollector(definition.name) }
        DomainLog.d(TAG, "Registered metric: ${definition.name} (${definition.category})")
    }

    fun recordMetric(name: String, value: Long) {
        // 审计 P3-14：未注册名不得绕过 MAX_COLLECTORS 上限创建 collector
        //（原 getOrPut 直建——任意调用方字符串即可无限增表）
        if (!metricsCollectors.containsKey(name)) {
            if (metricsCollectors.size >= MAX_COLLECTORS) {
                DomainLog.w(TAG, "Maximum metric collectors reached, drop metric: $name")
                return
            }
        }
        val collector = metricsCollectors.getOrPut(name) { MetricCollector(name) }
        collector.record(value)
        val stats = collector.getStats()
        notifyMetricRecorded(name, value, stats)
        checkThresholds(name, value)
    }

    fun recordMetric(name: String, value: Double) { recordMetric(name, value.toLong()) }
    fun recordMetric(name: String, value: Int) { recordMetric(name, value.toLong()) }

    fun getMetrics(): Map<String, MetricStats> = metricsCollectors.mapValues { it.value.getStats() }
    fun getMetric(name: String): MetricStats? = metricsCollectors[name]?.getStats()
    fun getMetricCollector(name: String): MetricCollector? = metricsCollectors[name]
    fun getMetricDefinition(name: String): MetricDefinition? = metricDefinitions[name]
    fun getAllMetricDefinitions(): Map<String, MetricDefinition> = metricDefinitions.toMap()
    fun resetMetric(name: String) { metricsCollectors[name]?.reset() }

    fun addListener(listener: MetricsListener) { listeners.add(listener) }
    fun removeListener(listener: MetricsListener) { listeners.remove(listener) }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun notifyMetricRecorded(name: String, value: Long, stats: MetricStats) {
        listeners.forEach { listener ->
            try {
                listener.onMetricRecorded(name, value, stats)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error notifying listener", e)
            }
        }
    }

    internal fun checkThresholds(name: String, value: Long) {
        val definition = metricDefinitions[name] ?: return

        definition.criticalThreshold?.let { threshold ->
            if (value > threshold) {
                notifyThresholdExceeded(name, value, threshold, isCritical = true)
            }
        }

        definition.warningThreshold?.let { threshold ->
            if (value > threshold) {
                notifyThresholdExceeded(name, value, threshold, isCritical = false)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    internal fun notifyThresholdExceeded(
        name: String,
        value: Long,
        threshold: Long,
        isCritical: Boolean
    ) {
        listeners.forEach { listener ->
            try {
                listener.onThresholdExceeded(name, value, threshold, isCritical)
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error notifying listener", e)
            }
        }
    }
}
