package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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

@Singleton
class SystemManager @Inject constructor(
    systems: Set<@JvmSuppressWildcards GameSystem>
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
        Log.d(TAG, "System execution order: ${sortedSystems.map { it.systemName }}")
        Log.d(TAG, "Parallel groups: ${priorityGroups.map { group -> group.map { it.systemName } }}")
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
            Log.w(TAG, "Systems already initialized")
            return
        }
        Log.d(TAG, "Initializing all systems...")
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.initialize()
                    Log.d(TAG, "System ${system.systemName} initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize system ${system.systemName}", e)
                }
            }
        }
        isInitialized = true
        Log.d(TAG, "All systems initialized")
    }

    fun releaseAll() {
        Log.d(TAG, "Releasing all systems...")
        systemOrder.reversed().forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.release()
                    Log.d(TAG, "System ${system.systemName} released")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release system ${system.systemName}", e)
                }
            }
        }
        isInitialized = false
        Log.d(TAG, "All systems released")
    }

    suspend fun clearAll() {
        mutex.withLock {
            Log.d(TAG, "Clearing all systems...")
            systemOrder.reversed().forEach { kClass ->
                systemMap[kClass]?.let { system ->
                    try {
                        system.clear()
                        Log.d(TAG, "System ${system.systemName} cleared")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear system ${system.systemName}", e)
                    }
                }
            }
            Log.d(TAG, "All systems cleared")
        }
    }

    suspend fun onPhaseTick(state: MutableGameState) {
        executeInParallelGroups(state) { system, s -> system.onPhaseTick(s) }
    }

    /**
     * 两档制执行：活跃域每 tick 都跑，非活跃域按时间间隔执行。
     *
     * @param activeDomains 当前活跃的关注域集合
     * @param shouldExecute 判断某系统是否应在当前 tick 执行
     * @param markExecuted 记录系统已执行（用于非活跃域计时）
     */
    suspend fun onPhaseTickWithDomainFilter(
        state: MutableGameState,
        activeDomains: Set<FocusDomain>,
        shouldExecute: (FocusDomain, Set<FocusDomain>) -> Boolean,
        markExecuted: (FocusDomain) -> Unit
    ) {
        for (group in priorityGroups) {
            if (group.size == 1) {
                val system = group.first()
                val domain = system.focusDomain
                if (shouldExecute(domain, activeDomains)) {
                    try {
                        system.onPhaseTick(state)
                        markExecuted(domain)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in ${system.systemName}", e)
                        _errors.trySend(SystemError(system.systemName, "tick", e))
                    }
                }
            } else {
                coroutineScope {
                    group.forEach { system ->
                        val domain = system.focusDomain
                        if (shouldExecute(domain, activeDomains)) {
                            launch {
                                try {
                                    system.onPhaseTick(state)
                                    markExecuted(domain)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in ${system.systemName}", e)
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
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ${system.systemName}", e)
                    _errors.trySend(SystemError(system.systemName, "tick", e))
                }
            } else {
                coroutineScope {
                    group.forEach { system ->
                        launch {
                            try {
                                action(system, state)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in ${system.systemName}", e)
                                _errors.trySend(SystemError(system.systemName, "tick", e))
                            }
                        }
                    }
                }
            }
        }
    }
}
