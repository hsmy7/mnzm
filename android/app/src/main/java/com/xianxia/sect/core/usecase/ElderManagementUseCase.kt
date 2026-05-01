package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.ElderSlots
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 长老管理用例
 *
 * 整合了 SectViewModel 和 ProductionViewModel 中重复的长老任命/卸任逻辑，
 * 包括 assignElder、removeElder、assignDirectDisciple、removeDirectDisciple。
 */
@Singleton
class ElderManagementUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    companion object {
        const val REALM_VICE_SECT_MASTER = GameConfig.Elder.REALM_VICE_SECT_MASTER
        const val REALM_LAW_ENFORCEMENT = GameConfig.Elder.REALM_LAW_ENFORCEMENT
        const val REALM_ELDER = GameConfig.Elder.REALM_ELDER
        const val REALM_PREACHING_MASTER = GameConfig.Elder.REALM_PREACHING_MASTER
    }

    sealed class ElderResult {
        data class Success(val message: String) : ElderResult()
        data class Error(val message: String) : ElderResult()
    }

    // ==================== Elder ID 查询辅助方法 ====================

    fun ElderSlots.getAllElderIds(): List<String> {
        return listOf(
            viceSectMaster,
            herbGardenElder,
            alchemyElder,
            forgeElder,
            outerElder,
            preachingElder,
            lawEnforcementElder,
            innerElder,
            qingyunPreachingElder
        ).filter { !it.isNullOrBlank() }
    }

    fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return listOf(
            herbGardenDisciples,
            herbGardenReserveDisciples,
            alchemyDisciples,
            alchemyReserveDisciples,
            forgeDisciples,
            forgeReserveDisciples,
            preachingMasters,
            lawEnforcementDisciples,
            lawEnforcementReserveDisciples,
            qingyunPreachingMasters,
            spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId.ifEmpty { null } }
    }

    // ==================== 长老任命 ====================

    suspend fun assignElder(slotType: ElderSlotType, discipleId: String): ElderResult {
        val disciples = gameEngine.discipleAggregates.value
        val disciple = disciples.find { it.id == discipleId }
            ?: return ElderResult.Error("弟子不存在")

        val currentGameData = gameEngine.gameData.value
        val elderSlots = currentGameData.elderSlots

        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        if (allElderIds.contains(discipleId)) {
            return ElderResult.Error("该弟子已担任长老职位")
        }

        if (allDirectDiscipleIds.contains(discipleId)) {
            return ElderResult.Error("该弟子已是其他长老的亲传弟子")
        }

        val newElderSlots = when (slotType) {
            ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                herbGardenElder = discipleId,
                herbGardenDisciples = emptyList(),
                herbGardenReserveDisciples = emptyList()
            )
            ElderSlotType.ALCHEMY -> elderSlots.copy(
                alchemyElder = discipleId,
                alchemyDisciples = emptyList(),
                alchemyReserveDisciples = emptyList()
            )
            ElderSlotType.FORGE -> elderSlots.copy(
                forgeElder = discipleId,
                forgeDisciples = emptyList(),
                forgeReserveDisciples = emptyList()
            )
            ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                viceSectMaster = discipleId
            )
            ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                outerElder = discipleId
            )
            ElderSlotType.PREACHING -> elderSlots.copy(
                preachingElder = discipleId,
                preachingMasters = emptyList()
            )
            ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                lawEnforcementElder = discipleId,
                lawEnforcementDisciples = emptyList(),
                lawEnforcementReserveDisciples = emptyList()
            )
            ElderSlotType.INNER_ELDER -> elderSlots.copy(
                innerElder = discipleId
            )
            ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                qingyunPreachingElder = discipleId,
                qingyunPreachingMasters = emptyList()
            )
        }
        gameEngine.updateElderSlots(newElderSlots)
        return ElderResult.Success("长老任命成功")
    }

    // ==================== 长老卸任 ====================

    suspend fun removeElder(slotType: ElderSlotType): ElderResult {
        val currentGameData = gameEngine.gameData.value
        val elderSlots = currentGameData.elderSlots
        val newElderSlots = when (slotType) {
            ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                herbGardenElder = "",
                herbGardenDisciples = emptyList(),
                herbGardenReserveDisciples = emptyList()
            )
            ElderSlotType.ALCHEMY -> elderSlots.copy(
                alchemyElder = "",
                alchemyDisciples = emptyList(),
                alchemyReserveDisciples = emptyList()
            )
            ElderSlotType.FORGE -> elderSlots.copy(
                forgeElder = "",
                forgeDisciples = emptyList(),
                forgeReserveDisciples = emptyList()
            )
            ElderSlotType.VICE_SECT_MASTER -> elderSlots.copy(
                viceSectMaster = ""
            )
            ElderSlotType.OUTER_ELDER -> elderSlots.copy(
                outerElder = ""
            )
            ElderSlotType.PREACHING -> elderSlots.copy(
                preachingElder = "",
                preachingMasters = emptyList()
            )
            ElderSlotType.LAW_ENFORCEMENT -> elderSlots.copy(
                lawEnforcementElder = "",
                lawEnforcementDisciples = emptyList(),
                lawEnforcementReserveDisciples = emptyList()
            )
            ElderSlotType.INNER_ELDER -> elderSlots.copy(
                innerElder = ""
            )
            ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                qingyunPreachingElder = "",
                qingyunPreachingMasters = emptyList()
            )
        }
        gameEngine.updateElderSlots(newElderSlots)
        return ElderResult.Success("长老已卸任")
    }

    // ==================== 亲传弟子任命 ====================

    suspend fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String
    ): ElderResult {
        val disciples = gameEngine.discipleAggregates.value
        val disciple = disciples.find { it.id == discipleId }
            ?: return ElderResult.Error("弟子不存在")

        val currentGameData = gameEngine.gameData.value
        val elderSlots = currentGameData.elderSlots

        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        if (allElderIds.contains(discipleId)) {
            return ElderResult.Error("该弟子已担任长老职位")
        }

        if (allDirectDiscipleIds.contains(discipleId)) {
            return ElderResult.Error("该弟子已是其他长老的亲传弟子")
        }

        gameEngine.assignDirectDisciple(
            elderSlotType = elderSlotType,
            slotIndex = slotIndex,
            discipleId = discipleId,
            discipleName = disciple.name,
            discipleRealm = disciple.realmName,
            discipleSpiritRootColor = disciple.spiritRoot.countColor
        )
        return ElderResult.Success("亲传弟子任命成功")
    }

    // ==================== 亲传弟子移除 ====================

    suspend fun removeDirectDisciple(elderSlotType: String, slotIndex: Int): ElderResult {
        gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
        return ElderResult.Success("亲传弟子已移除")
    }
}
