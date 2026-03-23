package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElderManagementUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    sealed class ElderSlotType(val key: String) {
        object ViceSectMaster : ElderSlotType("viceSectMaster")
        object LawEnforcementElder : ElderSlotType("lawEnforcementElder")
        object OuterElder : ElderSlotType("outerElder")
        object PreachingElder : ElderSlotType("preachingElder")
        object InnerElder : ElderSlotType("innerElder")
        object QingyunPreachingElder : ElderSlotType("qingyunPreachingElder")
        object AlchemyElder : ElderSlotType("alchemyElder")
        object HerbGardenElder : ElderSlotType("herbGardenElder")
        object ForgeElder : ElderSlotType("forgeElder")
        object SpiritMineDeacon : ElderSlotType("spiritMineDeacon")
    }
    
    data class AssignElderParams(
        val slotType: ElderSlotType,
        val discipleId: String,
        val disciples: List<Disciple>,
        val currentElderSlots: ElderSlots
    )
    
    sealed class AssignElderResult {
        data class Success(val newElderSlots: ElderSlots) : AssignElderResult()
        data class Error(val message: String) : AssignElderResult()
    }
    
    fun assignElder(params: AssignElderParams): AssignElderResult {
        val disciple = params.disciples.find { it.id == params.discipleId }
            ?: return AssignElderResult.Error("弟子不存在")
        
        val minRealm = getMinRealmForSlot(params.slotType)
        if (disciple.realm < minRealm) {
            val realmName = getRealmName(minRealm)
            return AssignElderResult.Error("境界不足，需要${realmName}及以上")
        }
        
        if (isAlreadyElder(params.discipleId, params.currentElderSlots)) {
            return AssignElderResult.Error("该弟子已担任其他长老职位")
        }
        
        val newElderSlots = updateElderSlot(params.currentElderSlots, params.slotType, params.discipleId)
        
        gameEngine.updateElderSlots(newElderSlots)
        gameEngine.syncAllDiscipleStatuses()
        
        return AssignElderResult.Success(newElderSlots)
    }
    
    fun removeElder(
        slotType: ElderSlotType,
        currentElderSlots: ElderSlots
    ): AssignElderResult {
        val currentDiscipleId = getElderDiscipleId(currentElderSlots, slotType)
        if (currentDiscipleId.isNullOrEmpty()) {
            return AssignElderResult.Error("该职位当前空缺")
        }
        
        val newElderSlots = updateElderSlot(currentElderSlots, slotType, null)
        
        gameEngine.updateElderSlots(newElderSlots)
        gameEngine.syncAllDiscipleStatuses()
        
        return AssignElderResult.Success(newElderSlots)
    }
    
    private fun updateElderSlot(elderSlots: ElderSlots, slotType: ElderSlotType, discipleId: String?): ElderSlots {
        return when (slotType) {
            ElderSlotType.ViceSectMaster -> elderSlots.copy(viceSectMaster = discipleId)
            ElderSlotType.LawEnforcementElder -> elderSlots.copy(lawEnforcementElder = discipleId)
            ElderSlotType.OuterElder -> elderSlots.copy(outerElder = discipleId)
            ElderSlotType.PreachingElder -> elderSlots.copy(preachingElder = discipleId)
            ElderSlotType.InnerElder -> elderSlots.copy(innerElder = discipleId)
            ElderSlotType.QingyunPreachingElder -> elderSlots.copy(qingyunPreachingElder = discipleId)
            ElderSlotType.AlchemyElder -> elderSlots.copy(alchemyElder = discipleId)
            ElderSlotType.HerbGardenElder -> elderSlots.copy(herbGardenElder = discipleId)
            ElderSlotType.ForgeElder -> elderSlots.copy(forgeElder = discipleId)
            ElderSlotType.SpiritMineDeacon -> elderSlots
        }
    }
    
    private fun getElderDiscipleId(elderSlots: ElderSlots, slotType: ElderSlotType): String? {
        return when (slotType) {
            ElderSlotType.ViceSectMaster -> elderSlots.viceSectMaster
            ElderSlotType.LawEnforcementElder -> elderSlots.lawEnforcementElder
            ElderSlotType.OuterElder -> elderSlots.outerElder
            ElderSlotType.PreachingElder -> elderSlots.preachingElder
            ElderSlotType.InnerElder -> elderSlots.innerElder
            ElderSlotType.QingyunPreachingElder -> elderSlots.qingyunPreachingElder
            ElderSlotType.AlchemyElder -> elderSlots.alchemyElder
            ElderSlotType.HerbGardenElder -> elderSlots.herbGardenElder
            ElderSlotType.ForgeElder -> elderSlots.forgeElder
            ElderSlotType.SpiritMineDeacon -> null
        }
    }
    
    private fun getMinRealmForSlot(slotType: ElderSlotType): Int {
        return when (slotType) {
            ElderSlotType.ViceSectMaster -> 4
            ElderSlotType.LawEnforcementElder -> 6
            ElderSlotType.OuterElder -> 8
            ElderSlotType.PreachingElder -> 5
            ElderSlotType.InnerElder -> 7
            ElderSlotType.QingyunPreachingElder -> 5
            ElderSlotType.AlchemyElder -> 5
            ElderSlotType.HerbGardenElder -> 6
            ElderSlotType.ForgeElder -> 5
            ElderSlotType.SpiritMineDeacon -> 8
        }
    }
    
    private fun getRealmName(realm: Int): String {
        val realms = listOf("炼气", "筑基", "金丹", "元婴", "化神", "炼虚", "合体", "大乘", "渡劫", "真仙")
        return if (realm in 0..9) realms[realm] else "未知"
    }
    
    private fun isAlreadyElder(discipleId: String, elderSlots: ElderSlots): Boolean {
        return listOfNotNull(
            elderSlots.viceSectMaster,
            elderSlots.lawEnforcementElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder,
            elderSlots.alchemyElder,
            elderSlots.herbGardenElder,
            elderSlots.forgeElder
        ).contains(discipleId)
    }
    
    fun getElderDisciple(elderId: String, disciples: List<Disciple>): Disciple? {
        return disciples.find { it.id == elderId }
    }
    
    fun isElderSlotOccupied(slotType: ElderSlotType, elderSlots: ElderSlots): Boolean {
        val discipleId = getElderDiscipleId(elderSlots, slotType)
        return !discipleId.isNullOrEmpty()
    }
}
