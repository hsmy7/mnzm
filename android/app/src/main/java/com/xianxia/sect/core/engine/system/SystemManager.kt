package com.xianxia.sect.core.engine.system

import android.util.Log
import javax.inject.Inject

class SystemManager @Inject constructor(
    private val sectSystem: SectSystem,
    private val discipleSystem: DiscipleSystem,
    private val inventorySystem: InventorySystem,
    private val explorationSystem: ExplorationSystem,
    private val buildingSubsystem: BuildingSubsystem,
    private val diplomacySubsystem: DiplomacySubsystem,
    private val merchantSubsystem: MerchantSubsystem,
    private val eventSubsystem: EventSubsystem
) {
    
    companion object {
        private const val TAG = "SystemManager"
    }
    
    fun initializeAll() {
        Log.d(TAG, "Initializing all systems...")
        sectSystem.initialize()
        discipleSystem.initialize()
        inventorySystem.initialize()
        explorationSystem.initialize()
        buildingSubsystem.initialize()
        diplomacySubsystem.initialize()
        merchantSubsystem.initialize()
        eventSubsystem.initialize()
        Log.d(TAG, "All systems initialized")
    }
    
    fun releaseAll() {
        Log.d(TAG, "Releasing all systems...")
        sectSystem.release()
        discipleSystem.release()
        inventorySystem.release()
        explorationSystem.release()
        buildingSubsystem.release()
        diplomacySubsystem.release()
        merchantSubsystem.release()
        eventSubsystem.release()
        Log.d(TAG, "All systems released")
    }
    
    fun clearAll() {
        Log.d(TAG, "Clearing all systems...")
        sectSystem.clear()
        discipleSystem.clear()
        inventorySystem.clear()
        explorationSystem.clear()
        buildingSubsystem.clear()
        diplomacySubsystem.clear()
        merchantSubsystem.clear()
        eventSubsystem.clear()
        Log.d(TAG, "All systems cleared")
    }
}
