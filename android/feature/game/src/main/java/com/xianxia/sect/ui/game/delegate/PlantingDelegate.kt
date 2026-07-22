package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.*

class PlantingDelegate(
    private val gameEngine: GameEngine
) {

    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.plantOnSpiritField(buildingInstanceId, seedId, sectId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                /* error handled by BaseViewModel */
            }
        }
    }

    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.plantOnSpiritFields(instanceIds, seedId, sectId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                /* error handled by BaseViewModel */
            }
        }
    }

    fun removePlantFromSpiritField(buildingInstanceId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.removePlantFromSpiritField(buildingInstanceId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                /* error handled by BaseViewModel */
            }
        }
    }

    fun removePlantsFromSpiritFields(instanceIds: List<String>) {
        if (instanceIds.isEmpty()) return
        gameEngine.launchOnEngine {
            try {
                gameEngine.removePlantsFromSpiritFields(instanceIds)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                /* error handled by BaseViewModel */
            }
        }
    }
}
