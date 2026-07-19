package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.SlotAssignment
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.production.ProductionSlot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子分配注册表门卫 — 记录"弟子ID → 槽位"的映射，用于查询和审计。
 *
 * ## 责任
 *
 * 1. **分配登记** — 通过 [confirmAssign] 记录弟子所在槽位
 * 2. **分配释放** — 通过 [release] 移除弟子在注册表中的记录
 * 3. **分配查询** — 通过 [getAssignment] / [isAssigned] 查询弟子当前分配
 * 4. **过滤** — 通过 [filterAvailableDisciples] 获取未分配的弟子
 * 5. **读档重建** — 通过 [rebuildFromGameData] 从存档数据重建注册表
 *
 * ## 使用模式
 *
 * Gate **不阻止分配**。调用方在分配前自行调用 [releaseDiscipleFromAllSlotsAtomic]
 * 释放旧槽位，再写入新槽位，最后调用 [confirmAssign] 登记：
 * ```
 * releaseDiscipleFromAllSlotsAtomic(discipleId) // 清理旧槽位 + gate.release()
 * stateStore.update { gameData = gameData.copy(elderSlots = ...) }
 * gate.confirmAssign(discipleId, newSlotRef)    // 登记新分配
 * ```
 */
@Singleton
class DiscipleAssignmentGate @Inject constructor(
    private val registry: DiscipleAssignmentRegistry,
) {

    // ==================== 登记 ====================

    /**
     * 确认分配 — 在写入完成后登记分配记录。
     * 覆盖现有记录（因为旧槽位已在分配前清理）。
     */
    fun confirmAssign(discipleId: String, slotRef: SlotRef) {
        if (discipleId.isEmpty()) return
        registry.registerOrUpdate(discipleId, slotRef)
    }

    // ==================== 释放 ====================

    /**
     * 释放弟子在注册表中的分配。
     * 由 [releaseDiscipleFromAllSlotsAtomic] 或各释放方法在清理槽位后调用。
     */
    fun release(discipleId: String) {
        registry.unregister(discipleId)
    }

    // ==================== 查询 ====================

    /** 查询弟子当前分配，null 表示未分配任何槽位。 */
    fun getAssignment(discipleId: String): SlotAssignment? = registry.getAssignment(discipleId)

    /** 检查弟子是否已分配任意槽位。 */
    fun isAssigned(discipleId: String): Boolean = registry.isAssigned(discipleId)

    /** 获取可用弟子列表（过滤掉已分配的）。 */
    fun filterAvailableDisciples(
        disciples: List<DiscipleAggregate>,
        excludeAssigned: Boolean = true,
    ): List<DiscipleAggregate> {
        if (!excludeAssigned) return disciples
        return disciples.filter { !registry.isAssigned(it.id) }
    }

    /** 当前已分配弟子数量。 */
    fun size(): Int = registry.size()

    // ==================== 读档重建 ====================

    /**
     * 从 GameData 快照重建注册表。
     * 在读档/新游戏初始化后调用。
     */
    fun rebuildFromGameData(
        gameData: GameData,
        productionSlots: List<ProductionSlot> = emptyList(),
    ) {
        registry.clear()
        scanAndRegister(gameData, productionSlots)
    }

    /**
     * 手动注册（用于非标准路径的预分配）。
     */
    fun manualRegister(discipleId: String, slotRef: SlotRef) {
        registry.tryRegister(discipleId, slotRef)
    }

    /** 清空注册表。 */
    fun clear() {
        registry.clear()
    }

    // ==================== 内部辅助 ====================

    private fun scanAndRegister(
        gameData: GameData,
        productionSlots: List<ProductionSlot>,
    ) {
        scanElderSlots(gameData.elderSlots)
        scanListSlots(gameData)
        scanProductionSlots(productionSlots)
    }

    private fun scanElderSlots(elderSlots: com.xianxia.sect.core.model.ElderSlots) {
        registerIfNotEmpty(elderSlots.viceSectMaster, SlotCategory.ELDER_POSITION, "viceSectMaster")
        registerIfNotEmpty(elderSlots.herbGardenElder, SlotCategory.ELDER_POSITION, "herbGardenElder")
        registerIfNotEmpty(elderSlots.alchemyElder, SlotCategory.ELDER_POSITION, "alchemyElder")
        registerIfNotEmpty(elderSlots.forgeElder, SlotCategory.ELDER_POSITION, "forgeElder")
        registerIfNotEmpty(elderSlots.outerElder, SlotCategory.ELDER_POSITION, "outerElder")
        registerIfNotEmpty(elderSlots.preachingElder, SlotCategory.ELDER_POSITION, "preachingElder")
        registerIfNotEmpty(elderSlots.lawEnforcementElder, SlotCategory.ELDER_POSITION, "lawEnforcementElder")
        registerIfNotEmpty(elderSlots.innerElder, SlotCategory.ELDER_POSITION, "innerElder")
        registerIfNotEmpty(elderSlots.recruitingElder, SlotCategory.ELDER_POSITION, "recruitingElder")
        registerIfNotEmpty(elderSlots.qingyunPreachingElder, SlotCategory.ELDER_POSITION, "qingyunPreachingElder")

        registerIfNotEmpty(elderSlots.herbGardenDisciples, SlotCategory.ELDER_POSITION, "herbGardenDisciple")
        registerIfNotEmpty(elderSlots.alchemyDisciples, SlotCategory.ELDER_POSITION, "alchemyDisciple")
        registerIfNotEmpty(elderSlots.forgeDisciples, SlotCategory.ELDER_POSITION, "forgeDisciple")
        registerIfNotEmpty(elderSlots.preachingMasters, SlotCategory.ELDER_POSITION, "preachingMaster")
        registerIfNotEmpty(elderSlots.lawEnforcementDisciples, SlotCategory.ELDER_POSITION, "lawEnforcementDisciple")
        registerIfNotEmpty(elderSlots.qingyunPreachingMasters, SlotCategory.ELDER_POSITION, "qingyunPreachingMaster")
        registerIfNotEmpty(elderSlots.spiritMineDeaconDisciples, SlotCategory.ELDER_POSITION, "spiritMineDeacon")
    }

    private fun scanListSlots(gameData: GameData) {
        (gameData.spiritMineSlots).forEach { slot ->
            registerIfNotEmpty(slot.discipleId, SlotCategory.SPIRIT_MINE, "miner")
        }
        (gameData.librarySlots).forEach { slot ->
            registerIfNotEmpty(slot.discipleId, SlotCategory.LIBRARY_SLOT, "library")
        }
        (gameData.residenceSlots).forEach { slot ->
            registerIfNotEmpty(slot.discipleId, SlotCategory.RESIDENCE_SLOT, "residence")
        }
        (gameData.warehouseGarrisons).forEach { slot ->
            registerIfNotEmpty(slot.discipleId, SlotCategory.WAREHOUSE_GARRISON, "warehouse")
        }
        (gameData.patrolSlots).forEach { slot ->
            registerIfNotEmpty(slot.discipleId, SlotCategory.PATROL_SLOT, "patrol")
        }
        gameData.activeBloodRefinements.values.forEach { refinement ->
            registerIfNotEmpty(refinement.discipleId, SlotCategory.BLOOD_REFINEMENT, "blood")
        }
        gameData.worldMapSects.filter { it.isPlayerSect }.forEach { sect ->
            sect.garrisonSlots.forEach { slot ->
                registerIfNotEmpty(slot.discipleId, SlotCategory.GARRISON_SLOT, "garrison")
            }
        }
        gameData.battleTeams.forEach { team ->
            team.slots.forEach { slot ->
                registerIfNotEmpty(slot.discipleId, SlotCategory.BATTLE_TEAM, "battle:${team.id}:${slot.index}")
            }
        }
    }

    private fun scanProductionSlots(productionSlots: List<ProductionSlot>) {
        productionSlots.forEach { slot ->
            registerIfNotEmpty(
                slot.assignedDiscipleId.orEmpty(),
                SlotCategory.PRODUCTION_SLOT,
                "${slot.buildingType}:${slot.slotIndex}"
            )
        }
    }

    private fun registerIfNotEmpty(
        discipleId: String?,
        category: SlotCategory,
        slotType: String,
    ) {
        if (discipleId.isNullOrEmpty()) return
        val slotRef = SlotRef(category, slotType, "${category.name}_$slotType")
        registry.tryRegister(discipleId, slotRef)
    }

    private fun registerIfNotEmpty(
        slots: List<com.xianxia.sect.core.model.DirectDiscipleSlot>,
        category: SlotCategory,
        prefix: String,
    ) {
        slots.forEach { slot ->
            slot.discipleId?.let { id ->
                if (id.isNotEmpty()) {
                    val slotRef = SlotRef(
                        category, "$prefix:${slot.index}",
                        "${category.name}_${prefix}_${slot.index}"
                    )
                    registry.tryRegister(id, slotRef)
                }
            }
        }
    }
}
