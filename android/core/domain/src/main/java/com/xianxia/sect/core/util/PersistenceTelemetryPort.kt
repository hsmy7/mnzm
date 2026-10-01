package com.xianxia.sect.core.util

/**
 * 持久化遥测窄端口——引擎层服务的持久化面计数通道。
 *
 * 依赖方向：`:core:engine` 不可见 `:core:data` 实现（`StorageMetrics`），
 * 经本端口（Hilt 绑定）反向接线；实现方负责计数与后续上报
 * （`StorageMetricsReporter` 周期聚合经 [AnalyticsTracker] 出报）。
 */
interface PersistenceTelemetryPort {

    /**
     * 记录一次账本↔派生缓存不一致（C++ 已以账本为准重锚）。
     *
     * @param op 触发不一致的玉符事务操作名（诊断归因用，非 PII）
     */
    fun recordJadeLedgerDrift(op: String)
}
