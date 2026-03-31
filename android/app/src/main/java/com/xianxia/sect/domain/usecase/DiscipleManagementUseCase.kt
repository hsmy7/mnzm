package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.GameConfig
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
    
    fun recruitDisciple(discipleId: String, recruitList: List<Disciple>, currentDiscipleCount: Int): RecruitResult {
        val disciple = recruitList.find { it.id == discipleId }
            ?: return RecruitResult(false, message = "弟子不存在")
        
        if (currentDiscipleCount >= GameConfig.Disciple.MAX_DISCIPLES) {
            return RecruitResult(false, message = "弟子数量已达上限(${GameConfig.Disciple.MAX_DISCIPLES}人)")
        }
        
        if (!validateDiscipleData(disciple)) {
            return RecruitResult(false, message = "弟子数据异常，无法招募")
        }
        
        gameEngine.recruitDiscipleFromList(disciple)
        return RecruitResult(true, disciple = disciple)
    }
    
    fun recruitAllDisciples(recruitList: List<Disciple>, currentSpiritStones: Long, currentDiscipleCount: Int): RecruitResult {
        val availableSlots = GameConfig.Disciple.MAX_DISCIPLES - currentDiscipleCount
        if (availableSlots <= 0) {
            return RecruitResult(false, message = "弟子数量已达上限(${GameConfig.Disciple.MAX_DISCIPLES}人)")
        }
        
        val actualRecruitCount = minOf(recruitList.size, availableSlots)
        
        val validDisciples = recruitList.take(actualRecruitCount).filter { validateDiscipleData(it) }
        validDisciples.forEach { disciple ->
            gameEngine.recruitDiscipleFromList(disciple)
        }
        return RecruitResult(true)
    }
    
    private fun validateDiscipleData(disciple: Disciple): Boolean {
        if (disciple.id.isBlank()) return false
        if (disciple.name.isBlank()) return false
        if (disciple.age < GameConfig.Disciple.MIN_AGE || disciple.age > GameConfig.Disciple.MAX_AGE) return false
        if (disciple.lifespan <= 0) return false
        if (disciple.loyalty < GameConfig.Disciple.MIN_LOYALTY || disciple.loyalty > GameConfig.Disciple.MAX_LOYALTY) return false
        if (disciple.realm < 0 || disciple.realm > 9) return false
        return true
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
