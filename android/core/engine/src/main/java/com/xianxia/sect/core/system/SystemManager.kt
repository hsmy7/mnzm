package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

data class SystemError(
    val systemName: String,
    val tickType: String,
    val error: Throwable
)

/**
 * 系统管理器 — 在惰性结算引擎中仅管理系统生命周期。
 *
 * 不再支持：
 * - onPhaseTickWithDomainFilter（焦点域调度）
 * - executeParallelCompute（并行计算）
 */
@Singleton
class SystemManager @Inject constructor(
    systems: Set<@JvmSuppressWildcards GameSystem>
) {
    companion object {
        private const val TAG = "SystemManager"
        private const val DEFAULT_PRIORITY = 500
    }

    private val systemMap = mutableMapOf<KClass<out GameSystem>, GameSystem>()
    private val mutex = Mutex()

    private val _errors = Channel<SystemError>(Channel.BUFFERED)
    val errors: Flow<SystemError> = _errors.receiveAsFlow()

    private var isInitialized = false

    init {
        systems.sortedBy { system ->
            system::class.java.getAnnotation(SystemPriority::class.java)?.order
                ?: DEFAULT_PRIORITY
        }.forEach { system ->
            systemMap[system::class] = system
        }
    }

    /** 获取系统实例（泛型安全） */
    @Suppress("UNCHECKED_CAST")
    fun <T : GameSystem> getSystem(kClass: KClass<out T>): T {
        return systemMap[kClass] as? T
            ?: error("System ${kClass.simpleName} not found")
    }

    /** 初始化所有系统 */
    fun initializeAll() {
        if (isInitialized) return
        systemMap.values.forEach { it.initialize() }
        isInitialized = true
    }

    /** 释放所有系统 */
    fun releaseAll() {
        if (!isInitialized) return
        systemMap.values.forEach { it.release() }
        isInitialized = false
    }

    /** 通知所有系统进行月变处理 */
    suspend fun onMonthlyEvent(state: MutableGameState) {
        for (system in systemMap.values) {
            try {
                system.onMonthlyEvent(state)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error in ${system.systemName} monthly event", e)
                _errors.trySend(SystemError(system.systemName, "monthly", e))
            }
        }
    }

    /** 通知所有系统进行年变处理 */
    suspend fun onYearlyEvent(state: MutableGameState) {
        for (system in systemMap.values) {
            try {
                system.onYearlyEvent(state)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.e(TAG, "Error in ${system.systemName} yearly event", e)
                _errors.trySend(SystemError(system.systemName, "yearly", e))
            }
        }
    }
}
