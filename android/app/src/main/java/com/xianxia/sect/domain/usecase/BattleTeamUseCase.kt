package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BattleTeamSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BattleTeamUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class BattleTeamSlotData(
        val index: Int,
        val discipleId: String = "",
        val discipleName: String = "",
        val realm: Int = 0
    )
    
    data class CreateTeamParams(
        val leaderId: String,
        val memberIds: List<String>,
        val disciples: List<Disciple>
    )
    
    sealed class TeamResult {
        data class Success(val teamId: String) : TeamResult()
        data class Error(val message: String) : TeamResult()
    }
    
    fun assignDiscipleToSlot(
        slotIndex: Int,
        discipleId: String,
        disciples: List<Disciple>,
        currentSlots: List<BattleTeamSlotData>
    ): TeamResult {
        val disciple = disciples.find { it.id == discipleId }
            ?: return TeamResult.Error("弟子不存在")
        
        if (disciple.status != DiscipleStatus.IDLE) {
            return TeamResult.Error("该弟子当前忙碌")
        }
        
        val isAlreadyInTeam = currentSlots.any { it.discipleId == discipleId }
        if (isAlreadyInTeam) {
            return TeamResult.Error("该弟子已在队伍中")
        }
        
        return TeamResult.Success(discipleId)
    }
    
    fun removeDiscipleFromSlot(slotIndex: Int): TeamResult {
        return TeamResult.Success("")
    }
    
    fun getMovableTargetSects(gameData: GameData): List<String> {
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id
        return gameData.worldMapSects
            .filter { it.id != playerSectId }
            .map { it.id }
    }
    
    fun hasBattleTeam(gameData: GameData): Boolean {
        val team = gameData.battleTeam ?: return false
        return team.slots.any { it.discipleId != null }
    }
    
    fun isTeamAtSect(gameData: GameData): Boolean {
        val team = gameData.battleTeam ?: return true
        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id
        return team.originSectId == playerSectId
    }
}
