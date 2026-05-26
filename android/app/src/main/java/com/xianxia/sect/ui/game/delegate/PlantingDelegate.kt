package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PlantingDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {

    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        scope.launch {
            try {
                gameEngine.plantOnSpiritField(buildingInstanceId, seedId, sectId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        scope.launch {
            try {
                gameEngine.plantOnSpiritFields(instanceIds, seedId, sectId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    fun removePlantFromSpiritField(buildingInstanceId: String) {
        scope.launch {
            try {
                gameEngine.removePlantFromSpiritField(buildingInstanceId)
            } catch (e: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }
}
