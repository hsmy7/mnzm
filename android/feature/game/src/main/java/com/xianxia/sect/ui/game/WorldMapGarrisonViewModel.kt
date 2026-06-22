package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.WorldSect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorldMapGarrisonViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    val gameData = gameEngine.gameData

    fun getPlayerAllies(): List<WorldSect> {
        val allyIds = gameEngine.getPlayerAllies()
        val data = gameData.value
        return data.worldMapSects.filter { allyIds.contains(it.id) }
    }

    fun getMovableTargetSectIds(): List<String> {
        val data = gameEngine.gameData.value
        val playerSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: ""
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect && !(sect.isPlayerOccupied && sect.occupierSectId == playerSectId)
        }.map { it.id }
    }

    fun attackSect(sectId: String, attackSlots: List<Pair<Int, DiscipleAggregate>>) {
        viewModelScope.launch {
            try {
                gameEngine.attackSect(sectId, attackSlots)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "进攻失败")
            }
        }
    }

    fun assignGarrisonDisciple(sectId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignGarrisonDisciple(sectId, slotIndex, discipleId)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "驻守失败")
            }
        }
    }

    fun removeGarrisonDisciple(sectId: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeGarrisonDisciple(sectId, slotIndex)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "卸任失败")
            }
        }
    }
}
