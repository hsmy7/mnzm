package com.xianxia.sect.data.engine

import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 封装 StorageEngine 的基础设施依赖（协程作用域、熔断器、指标、变更日志），
 * 将 StorageEngine 构造参数减少 3 个。
 */
@Singleton
class StorageInfraFacade @Inject constructor(
    val scopeProvider: CoroutineScopeProvider,
    val circuitBreaker: StorageCircuitBreaker,
    val storageMetrics: StorageMetrics,
    val changeLogPersistence: ChangeLogPersistence
)
