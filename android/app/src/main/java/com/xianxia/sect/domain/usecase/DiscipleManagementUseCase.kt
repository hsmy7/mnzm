package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleManagementUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class RecruitResult(
        val success: Boolean,
        val disciple: Disciple? = null,
        val message: String? = null
    )
    
    data class EquipResult(
        val success: Boolean,
        val message: String? = null
    )
    
    fun recruitDisciple(discipleId: String, recruitList: List<Disciple>): RecruitResult {
        val disciple = recruitList.find { it.id == discipleId }
            ?: return RecruitResult(false, message = "弟子不存在")
        
        val gameData = gameEngine.gameData.value
        if (gameData.spiritStones < 1000L) {
            return RecruitResult(false, message = "灵石不足，需要1000灵石")
        }
        
        gameEngine.recruitDiscipleFromList(disciple)
        return RecruitResult(true, disciple = disciple)
    }
    
    fun recruitAllDisciples(recruitList: List<Disciple>, currentSpiritStones: Long): RecruitResult {
        val totalCost = recruitList.size * 1000L
        if (currentSpiritStones < totalCost) {
            return RecruitResult(false, message = "灵石不足，需要${totalCost}灵石")
        }
        
        recruitList.forEach { disciple ->
            gameEngine.recruitDiscipleFromList(disciple)
        }
        return RecruitResult(true)
    }
    
    fun expelDisciple(discipleId: String, disciples: List<Disciple>): RecruitResult {
        val disciple = disciples.find { it.id == discipleId }
            ?: return RecruitResult(false, message = "弟子不存在")
        
        gameEngine.expelDisciple(discipleId)
        return RecruitResult(true, message = "${disciple.name}已被驱逐")
    }
    
    fun equipItem(discipleId: String, equipmentId: String): EquipResult {
        gameEngine.equipItem(discipleId, equipmentId)
        return EquipResult(true)
    }
    
    fun unequipItem(discipleId: String, slot: EquipmentSlot): EquipResult {
        gameEngine.unequipItem(discipleId, slot)
        return EquipResult(true)
    }
    
    fun learnManual(discipleId: String, manualId: String): EquipResult {
        gameEngine.learnManual(discipleId, manualId)
        return EquipResult(true)
    }
    
    fun forgetManual(discipleId: String, manualId: String): EquipResult {
        gameEngine.forgetManual(discipleId, manualId)
        return EquipResult(true)
    }
    
    fun usePill(discipleId: String, pillId: String): EquipResult {
        gameEngine.usePill(discipleId, pillId)
        return EquipResult(true)
    }
    
    fun getDiscipleById(discipleId: String): Disciple? {
        return gameEngine.disciples.value.find { it.id == discipleId }
    }
    
    fun getAvailableDisciplesForPosition(
        disciples: List<Disciple>,
        minRealm: Int,
        excludeIds: Set<String> = emptySet()
    ): List<Disciple> {
        return disciples.filter { disciple ->
            disciple.realm <= minRealm &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.id !in excludeIds
        }
    }
}
