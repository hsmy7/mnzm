package com.xianxia.sect.core.engine.system

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemManager @Inject constructor(
    private val sectSystem: SectSystem,
    private val discipleSystem: DiscipleSystem,
    private val inventorySystem: InventorySystem,
    private val explorationSystem: ExplorationSystem,
    private val buildingSubsystem: BuildingSubsystem,
    private val diplomacySubsystem: DiplomacySubsystem,
    private val merchantSubsystem: MerchantSubsystem,
    private val eventSubsystem: EventSubsystem,
    private val timeSystem: TimeSystem,
    private val cultivationSystem: CultivationSystem,
    private val alchemySubsystem: AlchemySubsystem,
    private val forgingSubsystem: ForgingSubsystem,
    private val herbGardenSubsystem: HerbGardenSubsystem,
    private val productionSubsystem: ProductionSubsystem
) {
    
    companion object {
        private const val TAG = "SystemManager"
    }
    
    private val systems = mutableMapOf<String, GameSystem>()
    private val systemOrder = mutableListOf<String>()
    private val mutex = Mutex()
    
    private var isInitialized = false
    
    init {
        registerInternal(timeSystem)
        registerInternal(sectSystem)
        registerInternal(discipleSystem)
        registerInternal(inventorySystem)
        registerInternal(cultivationSystem)
        registerInternal(alchemySubsystem)
        registerInternal(forgingSubsystem)
        registerInternal(herbGardenSubsystem)
        registerInternal(productionSubsystem)
        registerInternal(explorationSystem)
        registerInternal(buildingSubsystem)
        registerInternal(diplomacySubsystem)
        registerInternal(merchantSubsystem)
        registerInternal(eventSubsystem)
    }
    
    fun register(system: GameSystem) {
        registerInternal(system)
    }
    
    private fun registerInternal(system: GameSystem) {
        if (systems.containsKey(system.systemName)) {
            Log.w(TAG, "System ${system.systemName} already registered")
            return
        }
        systems[system.systemName] = system
        systemOrder.add(system.systemName)
    }
    
    fun initializeAll() {
        if (isInitialized) {
            Log.w(TAG, "Systems already initialized")
            return
        }
        Log.d(TAG, "Initializing all systems...")
        systemOrder.forEach { name ->
            systems[name]?.let { system ->
                try {
                    system.initialize()
                    Log.d(TAG, "System $name initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize system $name", e)
                }
            }
        }
        isInitialized = true
        Log.d(TAG, "All systems initialized")
    }
    
    fun releaseAll() {
        Log.d(TAG, "Releasing all systems...")
        systemOrder.reversed().forEach { name ->
            systems[name]?.let { system ->
                try {
                    system.release()
                    Log.d(TAG, "System $name released")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release system $name", e)
                }
            }
        }
        isInitialized = false
        Log.d(TAG, "All systems released")
    }
    
    suspend fun clearAll() {
        mutex.withLock {
            Log.d(TAG, "Clearing all systems...")
            systemOrder.reversed().forEach { name ->
                systems[name]?.let { system ->
                    try {
                        system.clear()
                        Log.d(TAG, "System $name cleared")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear system $name", e)
                    }
                }
            }
            Log.d(TAG, "All systems cleared")
        }
    }
    
    suspend fun onGameTick(year: Int, month: Int) {
        mutex.withLock {
            systemOrder.forEach { name ->
                (systems[name] as? TickableSystem)?.onGameTick(year, month)
            }
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T : GameSystem> getSystem(name: String): T? {
        return systems[name] as? T
    }
    
    fun <T : GameSystem> getSystemByClass(clazz: Class<T>): T? {
        return systems.values.filterIsInstance(clazz).firstOrNull()
    }
    
    fun getAllSystems(): List<GameSystem> = systemOrder.mapNotNull { systems[it] }
    
    fun isSystemRegistered(name: String): Boolean = systems.containsKey(name)
}

interface TickableSystem : GameSystem {
    suspend fun onGameTick(year: Int, month: Int)
}
