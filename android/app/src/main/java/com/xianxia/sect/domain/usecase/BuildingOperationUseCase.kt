@file:Suppress("DEPRECATION")
package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingOperationUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class AssignParams(
        val buildingId: String,
        val slotIndex: Int,
        val discipleId: String,
        val disciples: List<Disciple>
    )
    
    sealed class AssignResult {
        data class Success(val slotIndex: Int) : AssignResult()
        data class Error(val message: String) : AssignResult()
    }
    
    fun assignDiscipleToBuilding(params: AssignParams): AssignResult {
        val disciple = params.disciples.find { it.id == params.discipleId }
            ?: return AssignResult.Error("弟子不存在")

        if (disciple.status != DiscipleStatus.IDLE) {
            return AssignResult.Error("该弟子当前忙碌")
        }

        gameEngine.assignDiscipleToBuilding(
            params.buildingId,
            params.slotIndex,
            params.discipleId
        )
        return AssignResult.Success(params.slotIndex)
    }

    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int): AssignResult {
        gameEngine.removeDiscipleFromBuilding(buildingId, slotIndex)
        return AssignResult.Success(slotIndex)
    }
    
    fun getBuildingSlots(buildingId: String): List<BuildingSlot> {
        return gameEngine.getBuildingSlots(buildingId)
    }
    
    fun collectBuildingResult(buildingId: String, slotIndex: Int): AssignResult {
        return AssignResult.Error("建筑产物自动收取，无需手动操作")
    }
}
