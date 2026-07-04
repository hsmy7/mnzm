package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.concurrent.DeviceCapabilityProfiler
import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ParallelPhaseResult
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

data class SystemError(
    val systemName: String,
    val tickType: String,
    val error: Throwable
)

@Singleton
class SystemManager @Inject constructor(
    systems: Set<@JvmSuppressWildcards GameSystem>,
    private val profiler: DeviceCapabilityProfiler = DeviceCapabilityProfiler()
) {
    companion object {
        private const val TAG = "SystemManager"
        private const val DEFAULT_PRIORITY = 500
    }

    private val systemMap = mutableMapOf<KClass<out GameSystem>, GameSystem>()
    private val systemOrder = mutableListOf<KClass<out GameSystem>>()
    private val mutex = Mutex()

    private val _errors = Channel<SystemError>(Channel.BUFFERED)
    val errors: Flow<SystemError> = _errors.receiveAsFlow()

    private var isInitialized = false

    private val priorityGroups: List<List<GameSystem>>

    init {
        val sortedSystems = systems.sortedBy { system ->
            system::class.java.getAnnotation(SystemPriority::class.java)?.order
                ?: DEFAULT_PRIORITY
        }
        sortedSystems.forEach { system ->
            systemMap[system::class] = system
            systemOrder.add(system::class)
        }
        priorityGroups = sortedSystems
            .groupBy { system ->
                system::class.java.getAnnotation(SystemPriority::class.java)?.order
                    ?: DEFAULT_PRIORITY
            }
            .toSortedMap()
            .values
            .map { group -> group.toList() }
        DomainLog.d(TAG, "System execution order: ${sortedSystems.map { it.systemName }}")
        DomainLog.d(TAG, "Parallel groups: ${priorityGroups.map { group -> group.map { it.systemName } }}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : GameSystem> getSystem(clazz: KClass<T>): T {
        return systemMap[clazz] as? T
            ?: throw IllegalStateException("System ${clazz.simpleName} not found")
    }

    inline fun <reified T : GameSystem> getSystem(): T = getSystem(T::class)

    @Suppress("UNCHECKED_CAST")
    fun <T : GameSystem> getSystem(name: String): T? {
        return systemMap.values.find { it.systemName == name } as? T
    }

    fun <T : GameSystem> getSystemByClass(clazz: Class<T>): T? {
        return systemMap.values.filterIsInstance(clazz).firstOrNull()
    }

    fun getAllSystems(): List<GameSystem> = systemOrder.mapNotNull { systemMap[it] }

    fun isSystemRegistered(name: String): Boolean =
        systemMap.values.any { it.systemName == name }

    fun initializeAll() {
        if (isInitialized) {
            DomainLog.w(TAG, "Systems already initialized")
            return
        }
        DomainLog.d(TAG, "Initializing all systems...")
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.initialize()
                    DomainLog.d(TAG, "System ${system.systemName} initialized")
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Failed to initialize system ${system.systemName}", e)
                }
            }
        }
        isInitialized = true
        DomainLog.d(TAG, "All systems initialized")
    }

    fun releaseAll() {
        DomainLog.d(TAG, "Releasing all systems...")
        systemOrder.reversed().forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.release()
                    DomainLog.d(TAG, "System ${system.systemName} released")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Failed to release system ${system.systemName}", e)
                }
            }
        }
        isInitialized = false
        DomainLog.d(TAG, "All systems released")
    }

    suspend fun clearAll() {
        mutex.withLock {
            DomainLog.d(TAG, "Clearing all systems...")
            systemOrder.reversed().forEach { kClass ->
                systemMap[kClass]?.let { system ->
                    try {
                        system.clear()
                        DomainLog.d(TAG, "System ${system.systemName} cleared")
                    } catch (e: Exception) {
                        DomainLog.e(TAG, "Failed to clear system ${system.systemName}", e)
                    }
                }
            }
            DomainLog.d(TAG, "All systems cleared")
        }
    }

    suspend fun onPhaseTick(state: MutableGameState) {
        executeInParallelGroups(state) { system, s -> system.onPhaseTick(s, phasesToSettle = 1) }
    }

    /**
     * 两档制执行 + 分旬调度 + 热状态联动 + 累积结算。
     *
     * - 活跃域每 tick 都跑，非活跃域按时间间隔执行
     * - 分旬调度：非焦点域的系统仅在对应旬执行（settlementPhase）
     * - 热状态联动：发热时跳过非焦点域的中旬/下旬系统
     * - 累积结算：通过 [getPhasesToSettle] 传入跳过旬数，批量轨一次补完
     *
     * @param activeDomains 当前活跃的关注域集合
     * @param shouldExecute 判断某系统是否应在当前 tick 执行
     * @param markExecuted 记录系统已执行（用于非活跃域计时）
     * @param currentPhase 当前旬（1=上旬, 2=中旬, 3=下旬）
     * @param getPhasesToSettle 根据焦点域返回应结算的旬数（焦点域=1，非焦点域=跳过旬数）
     */
    suspend fun onPhaseTickWithDomainFilter(
        state: MutableGameState,
        activeDomains: Set<FocusDomain>,
        shouldExecute: (FocusDomain, Set<FocusDomain>) -> Boolean,
        markExecuted: (FocusDomain) -> Unit,
        currentPhase: Int,
        getPhasesToSettle: (FocusDomain) -> Int = { 1 },
        /** 跳过已通过 [computePhaseTick] 并行执行过的系统 */
        skipSystems: Set<KClass<out GameSystem>> = emptySet()
    ) {
        val activeSystemClasses = FocusDomain.activeSystemsFor(activeDomains)
        val skipAll = skipSystems.isEmpty().not()

        for (group in priorityGroups) {
            // 整组跳过：如果组内所有系统都要跳过，整组跳过以减少迭代
            if (skipAll) {
                val allSkipped = group.all { it::class in skipSystems }
                if (allSkipped) continue
            }

            if (group.size == 1) {
                val system = group.first()
                if (system::class in skipSystems) continue
                val domain = FocusDomain.assignedDomainFor(system::class, activeDomains)
                val isActiveDomain = system::class in activeSystemClasses
                val shouldSettleByPhase = if (isActiveDomain) {
                    true
                } else {
                    system.settlementPhase == 0 || system.settlementPhase == currentPhase
                }
                if (shouldExecute(domain, activeDomains) && shouldSettleByPhase) {
                    try {
                        system.onPhaseTick(state, phasesToSettle = getPhasesToSettle(domain))
                        markExecuted(domain)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DomainLog.e(TAG, "Error in ${system.systemName}", e)
                        _errors.trySend(SystemError(system.systemName, "tick", e))
                    }
                }
            } else {
                coroutineScope {
                    group.forEach { system ->
                        if (system::class in skipSystems) return@forEach
                        val domain = FocusDomain.assignedDomainFor(system::class, activeDomains)
                        val isActiveDomain = system::class in activeSystemClasses
                        val shouldSettleByPhase = if (isActiveDomain) {
                            true
                        } else {
                            system.settlementPhase == 0 || system.settlementPhase == currentPhase
                        }
                        if (shouldExecute(domain, activeDomains) && shouldSettleByPhase) {
                            launch {
                                try {
                                    system.onPhaseTick(state, phasesToSettle = getPhasesToSettle(domain))
                                    markExecuted(domain)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    DomainLog.e(TAG, "Error in ${system.systemName}", e)
                                    _errors.trySend(SystemError(system.systemName, "tick", e))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun onMonthTick(state: MutableGameState) {
        executeInParallelGroups(state) { system, s -> system.onMonthTick(s) }
    }

    suspend fun onYearTick(state: MutableGameState) {
        executeInParallelGroups(state) { system, s -> system.onYearTick(s) }
    }

    private suspend fun executeInParallelGroups(
        state: MutableGameState,
        action: suspend (GameSystem, MutableGameState) -> Unit
    ) {
        for (group in priorityGroups) {
            if (group.size == 1) {
                val system = group.first()
                try {
                    action(system, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DomainLog.e(TAG, "Error in ${system.systemName}", e)
                    _errors.trySend(SystemError(system.systemName, "tick", e))
                }
            } else {
                coroutineScope {
                    group.forEach { system ->
                        launch {
                            try {
                                action(system, state)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                DomainLog.e(TAG, "Error in ${system.systemName}", e)
                                _errors.trySend(SystemError(system.systemName, "tick", e))
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 1 新增：并行 compute/apply 执行
    // ═══════════════════════════════════════════════════════════════

    /**
     * 并行执行已迁移系统的 [computePhaseTick] 阶段。
     *
     * 在 tick 开始时创建只读快照 [ctx]，将所有 [supportsParallelTick] 系统的
     * compute 阶段分发到 [Dispatchers.Default] 并行执行。
     * 返回的结果列表在调用方通过 [stateStore.update] 串行 apply。
     *
     * @param ctx 只读上下文快照
     * @param activeDomains 当前活跃域集合
     * @param phasesToSettleByDomain 每个域的应结算旬数映射
     */
    suspend fun executeParallelCompute(
        ctx: ParallelExecutionContext,
        activeDomains: Set<FocusDomain>,
        phasesToSettleByDomain: (FocusDomain) -> Int
    ): List<ParallelPhaseResult> {
        if (!profiler.enableParallelTick) return emptyList()

        val activeSystemClasses = FocusDomain.activeSystemsFor(activeDomains)
        val parallelSystems = mutableListOf<Pair<GameSystem, Int>>()

        // 收集所有支持并行且应在本次 tick 执行的中系统
        for (group in priorityGroups) {
            for (system in group) {
                if (!system.supportsParallelTick) continue
                val domain = FocusDomain.assignedDomainFor(system::class, activeDomains)
                if (domain !in activeDomains && domain != FocusDomain.ALWAYS) continue
                val isActiveDomain = system::class in activeSystemClasses
                if (!isActiveDomain) {
                    // 分旬调度检查
                    val phase = ctx.gamePhase + 1
                    if (system.settlementPhase != 0 && system.settlementPhase != phase) continue
                }
                val phasesToSettle = phasesToSettleByDomain(domain)
                parallelSystems.add(system to phasesToSettle)
            }
        }

        if (parallelSystems.isEmpty()) return emptyList()

        return withContext(profiler.parallelDispatcher) {
            coroutineScope {
                parallelSystems.map { (system, phases) ->
                    async {
                        try {
                            system.computePhaseTick(ctx, phases)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            DomainLog.e(TAG, "Parallel compute error in ${system.systemName}", e)
                            _errors.trySend(SystemError(system.systemName, "parallel_compute", e))
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }
        }
    }
}
