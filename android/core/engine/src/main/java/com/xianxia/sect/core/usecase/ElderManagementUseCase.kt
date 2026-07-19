package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
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
    private val gameEngine: GameEngine,
    private val assignmentGate: DiscipleAssignmentGate,
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

    /** 影响生产速率的长老类型 — 变更后触发 checkpointAllProduction() */
    private val productionElderTypes = setOf(
        ElderSlotType.ALCHEMY, ElderSlotType.FORGE, ElderSlotType.HERB_GARDEN
    )

    /** 影响修炼速度的长老类型 — 变更后触发 checkpointAllDisciples() */
    private val cultivationElderTypes = setOf(
        ElderSlotType.PREACHING, ElderSlotType.CLOUD_PREACHING,
        ElderSlotType.INNER_ELDER, ElderSlotType.OUTER_ELDER,
        ElderSlotType.VICE_SECT_MASTER
    )

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
            recruitingElder,
            qingyunPreachingElder
        ).filter { !it.isNullOrBlank() }
    }

    fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return listOf(
            herbGardenDisciples,
            alchemyDisciples,
            forgeDisciples,
            preachingMasters,
            lawEnforcementDisciples,
            qingyunPreachingMasters,
            spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId.ifEmpty { null } }
    }

    // ==================== 长老任命 ====================

    suspend fun assignElder(slotType: ElderSlotType, discipleId: String): ElderResult {
        val disciples = gameEngine.discipleAggregatesSnapshot
        val disciple = disciples.find { it.id == discipleId }
            ?: return ElderResult.Error("弟子不存在")

        if (!disciple.isAlive) {
            return ElderResult.Error("弟子已死亡")
        }

        // 释放旧槽位（自动移除前职务，允许弟子担任新职务）
        gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)

        val targetSlot = SlotRef(
            category = SlotCategory.ELDER_POSITION,
            slotType = slotType.name,
            slotId = "elder_${slotType.name}"
        )
        val currentGameData = gameEngine.gameDataSnapshot
        val elderSlots = currentGameData.elderSlots

        val newElderSlots = when (slotType) {
            ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                herbGardenElder = discipleId,
                herbGardenDisciples = emptyList()
            )
            ElderSlotType.ALCHEMY -> elderSlots.copy(
                alchemyElder = discipleId,
                alchemyDisciples = emptyList()
            )
            ElderSlotType.FORGE -> elderSlots.copy(
                forgeElder = discipleId,
                forgeDisciples = emptyList()
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
                lawEnforcementDisciples = emptyList()
            )
            ElderSlotType.INNER_ELDER -> elderSlots.copy(
                innerElder = discipleId
            )
            ElderSlotType.RECRUITING -> elderSlots.copy(
                recruitingElder = discipleId
            )
            ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                qingyunPreachingElder = discipleId,
                qingyunPreachingMasters = emptyList()
            )
        }
        gameEngine.updateElderSlots(newElderSlots)
        // 登记分配
        assignmentGate.confirmAssign(discipleId, targetSlot)
        // Checkpoint：长老变化后重算生产 duration
        if (slotType in productionElderTypes) {
            gameEngine.checkpointAllProduction()
        }
        // Checkpoint：影响修炼速度的长老变化后同步弟子检查点
        if (slotType in cultivationElderTypes) {
            gameEngine.checkpointAllDisciples()
        }
        return ElderResult.Success("长老任命成功")
    }

    // ==================== 长老卸任 ====================

    suspend fun removeElder(slotType: ElderSlotType): ElderResult {
        val currentGameData = gameEngine.gameDataSnapshot
        val elderSlots = currentGameData.elderSlots
        // 取出当前长老的弟子 ID（卸任后清理注册表）
        val previousDiscipleId = getElderIdBySlotType(elderSlots, slotType)
        val newElderSlots = when (slotType) {
            ElderSlotType.HERB_GARDEN -> elderSlots.copy(
                herbGardenElder = "",
                herbGardenDisciples = emptyList()
            )
            ElderSlotType.ALCHEMY -> elderSlots.copy(
                alchemyElder = "",
                alchemyDisciples = emptyList()
            )
            ElderSlotType.FORGE -> elderSlots.copy(
                forgeElder = "",
                forgeDisciples = emptyList()
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
                lawEnforcementDisciples = emptyList()
            )
            ElderSlotType.INNER_ELDER -> elderSlots.copy(
                innerElder = ""
            )
            ElderSlotType.RECRUITING -> elderSlots.copy(
                recruitingElder = ""
            )
            ElderSlotType.CLOUD_PREACHING -> elderSlots.copy(
                qingyunPreachingElder = "",
                qingyunPreachingMasters = emptyList()
            )
        }
        gameEngine.updateElderSlots(newElderSlots)
        // 清理注册表
        if (previousDiscipleId.isNotEmpty()) {
            assignmentGate.release(previousDiscipleId)
        }
        if (slotType in productionElderTypes) {
            gameEngine.checkpointAllProduction()
        }
        if (slotType in cultivationElderTypes) {
            gameEngine.checkpointAllDisciples()
        }
        return ElderResult.Success("长老已卸任")
    }

    // ==================== 亲传弟子任命 ====================

    suspend fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String
    ): ElderResult {
        val disciples = gameEngine.discipleAggregatesSnapshot
        val disciple = disciples.find { it.id == discipleId }
            ?: return ElderResult.Error("弟子不存在")

        if (!disciple.isAlive) {
            return ElderResult.Error("弟子已死亡")
        }

        val currentGameData = gameEngine.gameDataSnapshot
        val elderSlots = currentGameData.elderSlots

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

    // ==================== 内部辅助 ====================

    /**
     * 根据长老类型获取当前任命的弟子 ID。
     */
    private fun getElderIdBySlotType(slots: ElderSlots, slotType: ElderSlotType): String {
        return when (slotType) {
            ElderSlotType.VICE_SECT_MASTER -> slots.viceSectMaster
            ElderSlotType.HERB_GARDEN -> slots.herbGardenElder
            ElderSlotType.ALCHEMY -> slots.alchemyElder
            ElderSlotType.FORGE -> slots.forgeElder
            ElderSlotType.OUTER_ELDER -> slots.outerElder
            ElderSlotType.PREACHING -> slots.preachingElder
            ElderSlotType.LAW_ENFORCEMENT -> slots.lawEnforcementElder
            ElderSlotType.INNER_ELDER -> slots.innerElder
            ElderSlotType.RECRUITING -> slots.recruitingElder
            ElderSlotType.CLOUD_PREACHING -> slots.qingyunPreachingElder
        }
    }
}
