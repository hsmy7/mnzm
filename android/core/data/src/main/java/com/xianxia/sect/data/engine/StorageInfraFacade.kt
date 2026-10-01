package com.xianxia.sect.data.engine

import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的基础设施依赖（协程作用域、熔断器、指标、变更日志、
 * 保存脏集跟踪器），将 StorageEngine 构造参数收敛在本 Facade。
 */
@Singleton
class StorageInfraFacade @Inject constructor(
    val scopeProvider: CoroutineScopeProvider,
    val circuitBreaker: StorageCircuitBreaker,
    val storageMetrics: StorageMetrics,
    val changeLogPersistence: ChangeLogPersistence,
    /** 增量落盘保存脏集跟踪器（SS5）：路径基线判定 / 保存结算 / 全量标记。 */
    val dirtySetTracker: DirtySetTracker
)
