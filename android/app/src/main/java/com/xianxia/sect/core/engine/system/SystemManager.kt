package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

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

    private var isInitialized = false

    init {
        val sortedSystems = systems.sortedBy { system ->
            system::class.java.getAnnotation(SystemPriority::class.java)?.order
                ?: DEFAULT_PRIORITY
        }
        sortedSystems.forEach { system ->
            systemMap[system::class] = system
            systemOrder.add(system::class)
        }
        Log.d(TAG, "System execution order: ${sortedSystems.map { it.systemName }}")
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

    suspend fun onSecondTick(state: MutableGameState) {
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.onSecondTick(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSecondTick for ${system.systemName}", e)
                }
            }
        }
    }

    suspend fun onDayTick(state: MutableGameState) {
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.onDayTick(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onDayTick for ${system.systemName}", e)
                }
            }
        }
    }

    suspend fun onMonthTick(state: MutableGameState) {
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.onMonthTick(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onMonthTick for ${system.systemName}", e)
                }
            }
        }
    }

    suspend fun onYearTick(state: MutableGameState) {
        systemOrder.forEach { kClass ->
            systemMap[kClass]?.let { system ->
                try {
                    system.onYearTick(state)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onYearTick for ${system.systemName}", e)
                }
            }
        }
    }
}
