package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.plantOnSpiritField
import com.xianxia.sect.core.engine.plantOnSpiritFields
import com.xianxia.sect.core.engine.removePlantFromSpiritField
import com.xianxia.sect.core.engine.removePlantsFromSpiritFields



class PlantingDelegate(
    private val gameEngine: GameEngine
) {

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 统一翻译为领域错误后重抛
    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.plantOnSpiritField(buildingInstanceId, seedId, sectId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (ignored: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 统一翻译为领域错误后重抛
    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.plantOnSpiritFields(instanceIds, seedId, sectId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (ignored: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 统一翻译为领域错误后重抛
    fun removePlantFromSpiritField(buildingInstanceId: String) {
        gameEngine.launchOnEngine {
            try {
                gameEngine.removePlantFromSpiritField(buildingInstanceId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (ignored: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 统一翻译为领域错误后重抛
    fun removePlantsFromSpiritFields(instanceIds: List<String>) {
        if (instanceIds.isEmpty()) return
        gameEngine.launchOnEngine {
            try {
                gameEngine.removePlantsFromSpiritFields(instanceIds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (ignored: Exception) {
                /* error handled by BaseViewModel */
            }
        }
    }
}
