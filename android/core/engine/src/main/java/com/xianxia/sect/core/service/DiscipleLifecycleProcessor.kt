package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.domain.disciple.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBusPort
import kotlinx.coroutines.launch
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("DiscipleLifecycleProcessor")
class DiscipleLifecycleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val scopeProvider: CoroutineScopeProvider,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventBus: EventBusPort
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "DiscipleLifecycle"
        private const val CULL_DEAD_AFTER_YEARS = 1
        private const val REFLECTION_RELEASE_MORALITY_BONUS = 5
        private const val REFLECTION_RELEASE_LOYALTY_BONUS = 5
    }

    // ── 弟子老化/死亡 ──────────────────────────────────────────────────

    fun processGriefExpiry(currentYear: Int) {
        stateStore.update {
            val updated = discipleTables.assembleAll().map { disciple ->
                val griefEnd = disciple.social.griefEndYear
                if (griefEnd != null && currentYear >= griefEnd) {
                    disciple.copy(social = disciple.social.copy(griefEndYear = null))
                } else {
                    disciple
                }
            }
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        }
    }

    fun processDiscipleAging(currentYear: Int) {
        val data = stateStore.gameData.value
        val currentList = stateStore.disciples.value
        val updatedDisciples = currentList.mapNotNull { disciple ->
            if (!disciple.isAlive) return@mapNotNull disciple

            var agedDisciple = disciple.copy(age = disciple.age + 1)

            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copy(realmLayer = 1, status = DiscipleStatus.IDLE)
            }

            val talentEffects = TalentDatabase.calculateTalentEffects(agedDisciple.talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val realmMaxAge = GameConfig.Realm.get(agedDisciple.realm).maxAge
            val talentLifespan = (realmMaxAge * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            val maxAge = maxOf(agedDisciple.lifespan, realmMaxAge, talentLifespan)
            if (agedDisciple.age >= maxAge) {
                handleDiscipleDeath(agedDisciple)
                null
            } else {
                agedDisciple
            }
        }

        stateStore.update {
            discipleTables.clear()
            updatedDisciples.forEach { discipleTables.insert(it) }

            // 安全网：deathYears 由各个死亡路径的 markDead/handleDiscipleDeath 设置，
            // 此处仅对极少数遗漏情况（如旧存档兼容）做补充
            for (d in updatedDisciples) {
                if (!d.isAlive) {
                    val id = d.id.toIntOrNull() ?: continue
                    if (!discipleTables.deathYears.contains(id)) {
                        discipleTables.deathYears[id] = currentYear
                    }
                }
            }
        }
    }

    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        val originalList = stateStore.disciples.value
        val currentYear = stateStore.gameData.value.gameYear

        val griefUpdated = propagateGriefToRelatives(originalList, disciple, currentYear)
        unbindPartnerRelationship(griefUpdated, disciple)
        unbindMasterRelationships(griefUpdated, disciple)
        val tables = stateStore.discipleTables
        val lifeEventsToWrite = computeBereavementLifeEvents(griefUpdated, originalList, disciple, tables)

        // 收集要删除的装备/功法 ID（不论内外都是直接删除）
        val deleteEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        disciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        disciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        val deleteManualIds = disciple.manualIds.toSet()

        // 单事务写入：弟子表 + 血炼清理 + 装备/功法清除
        stateStore.update {
            discipleTables.clear()
            griefUpdated.forEach { discipleTables.insert(it) }
            val idInt = disciple.id.toInt()
            discipleTables.deathYears[idInt] = currentYear
            lifeEventsToWrite.forEach { (grievingId, event) ->
                val prevEvents = discipleTables.lifeEvents.getOrDefault(grievingId, emptyList())
                discipleTables.lifeEvents[grievingId] = prevEvents + event
            }
            gameData = gameData.copy(
                bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - disciple.id,
                bloodRefinements = gameData.bloodRefinements - disciple.id
            )
            equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
            manualInstances = manualInstances.filter { it.id !in deleteManualIds }
        }

        if (isOutsideSect) removeProficiencies(disciple.id)

        eventBus.emitSync(DeathEvent(
            discipleId = disciple.id,
            discipleName = disciple.name,
            cause = if (isOutsideSect) "combat" else "age",
            deathYear = currentYear
        ))
    }

    // ── 以下为 handleDiscipleDeath 的拆分子函数 ────────────────────────────

    private fun propagateGriefToRelatives(
        currentList: List<Disciple>, disciple: Disciple, year: Int
    ): MutableList<Disciple> = DiscipleStatCalculator.applyGriefToRelatives(
        currentList, listOf(disciple), year
    ).toMutableList()

    private fun unbindPartnerRelationship(griefUpdated: MutableList<Disciple>, disciple: Disciple) {
        val partnerId = disciple.social.partnerId ?: return
        val partnerIndex = griefUpdated.indexOfFirst { it.id == partnerId }
        if (partnerIndex >= 0) {
            griefUpdated[partnerIndex] = griefUpdated[partnerIndex].copy(
                social = griefUpdated[partnerIndex].social.copy(partnerId = null)
            )
        }
    }

    private fun unbindMasterRelationships(griefUpdated: MutableList<Disciple>, disciple: Disciple) {
        val deadId = disciple.id
        griefUpdated.indices.forEach { i ->
            if (griefUpdated[i].social.masterId == deadId) {
                griefUpdated[i] = griefUpdated[i].copy(
                    social = griefUpdated[i].social.copy(masterId = null)
                )
            }
        }
    }

    private fun computeBereavementLifeEvents(
        griefUpdated: List<Disciple>,
        originalList: List<Disciple>,
        deceased: Disciple,
        tables: DiscipleTables
    ): Map<Int, String> {
        val events = mutableMapOf<Int, String>()
        val deadId = deceased.id
        for (grievingD in griefUpdated) {
            val originalD = originalList.find { it.id == grievingD.id } ?: continue
            val wasGrieving = originalD.social.griefEndYear != null
            val isNowGrieving = grievingD.social.griefEndYear != null
            if (wasGrieving || !isNowGrieving) continue

            val relationship = deduceRelationship(originalD, deadId)
            val grievingId = grievingD.id.toIntOrNull() ?: continue
            val grievingAge = tables.ages[grievingId]
            events[grievingId] = "${grievingAge}岁：因${relationship}${deceased.name}离世陷入悲痛，修炼速度降低50%"
        }
        return events
    }

    private fun deduceRelationship(disciple: Disciple, deadId: String): String = when {
        disciple.social.partnerId == deadId -> "道侣"
        deadId == disciple.social.partnerId -> "道侣"
        disciple.social.parentId1 == deadId || disciple.social.parentId2 == deadId -> "父/母"
        deadId == disciple.social.parentId1 || deadId == disciple.social.parentId2 -> "子女"
        else -> "亲属"
    }

    private fun cleanupEquipmentAndManuals(disciple: Disciple, isOutsideSect: Boolean) {
        if (isOutsideSect) {
            clearExternalEquipmentAndManuals(disciple)
        } else {
            clearInternalEquipmentAndManuals(disciple)
        }
    }

    private fun clearExternalEquipmentAndManuals(disciple: Disciple) {
        val externalEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { externalEquipIds.add(it) }
        disciple.equipment.armorId?.let { externalEquipIds.add(it) }
        disciple.equipment.bootsId?.let { externalEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { externalEquipIds.add(it) }

        val externalManualIds = disciple.manualIds.toSet()

        stateStore.update {
            equipmentInstances = equipmentInstances.filter { it.id !in externalEquipIds }
            manualInstances = manualInstances.filter { it.id !in externalManualIds }
        }

        removeProficiencies(disciple.id)
    }

    private fun clearInternalEquipmentAndManuals(disciple: Disciple) {
        // 直接删除装备/功法实例，不返还仓库
        val deleteEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        disciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        disciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        disciple.equipment.storageBagItems
            .filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }
            .map { it.itemId }.forEach { deleteEquipIds.add(it) }

        val bagManualIds = disciple.equipment.storageBagItems
            .filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }
            .map { it.itemId }.toSet()
        val deleteManualIds = bagManualIds + disciple.manualIds.toSet()

        stateStore.update {
            equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
            manualInstances = manualInstances.filter { it.id !in deleteManualIds }
        }

        removeProficiencies(disciple.id)
    }

    private fun removeProficiencies(discipleId: String) {
        val data = stateStore.gameData.value
        val updated = data.manualProficiencies.toMutableMap()
        updated.remove(discipleId)
        if (updated != data.manualProficiencies) {
            stateStore.update {
                gameData = gameData.copy(manualProficiencies = updated)
            }
        }
    }

    fun processYearlyAging(currentYear: Int) {
        val cullThreshold = currentYear - CULL_DEAD_AFTER_YEARS
        stateStore.discipleTables.cullDeadDisciples(cullThreshold)
    }

    fun processReflectionRelease(year: Int) {
        stateStore.update {
            val currentList = discipleTables.assembleAll()
            val reflectingDisciples = currentList.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
            if (reflectingDisciples.isEmpty()) return@update

            val updatedDisciples = currentList.map { disciple ->
                if (disciple.status != DiscipleStatus.REFLECTING || !disciple.isAlive) return@map disciple

                val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: return@map disciple
                if (year < endYear) return@map disciple

                disciple.copy(
                    status = DiscipleStatus.IDLE,
                    statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear",
                    skills = disciple.skills.copy(
                        morality = disciple.skills.morality + REFLECTION_RELEASE_MORALITY_BONUS,
                        loyalty = disciple.skills.loyalty + REFLECTION_RELEASE_LOYALTY_BONUS
                    )
                )
            }
            // ★ 字段级更新替代 clear+insert：GameStateStoreImpl 事务中
            // _discipleTables 引用不变，clear+insert 后 !（引用不等）检测不触发，
            // mutationVersion 虽变化但 batchEmission/reentrantBuffer 路径可能跳过。
            // 直接写字段确保值落盘且 mutationVersion 正确递增。
            for (d in updatedDisciples) {
                val id = d.id.toIntOrNull() ?: continue
                discipleTables.statuses[id] = d.status
                discipleTables.statusData[id] = d.statusData
                discipleTables.moralities[id] = d.skills.morality
                discipleTables.loyalties[id] = d.skills.loyalty
            }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    fun clearDiscipleFromAllSlots(discipleId: String) {
        val data = stateStore.gameData.value
        val cleaned = DiscipleSlotCleanup.clearAllSlots(data, discipleId)
        stateStore.update {
            gameData = cleaned
        }

        clearForgeSlotsIfNeeded(discipleId)
    }

    private fun clearForgeSlotsIfNeeded(discipleId: String) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BUILDING_FORGE)
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId) {
                productionSlotRepository.updateSlotByBuildingId(BUILDING_FORGE, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    fun returnEquipmentToWarehouse(equipmentId: String) {
        val currentInstances = stateStore.equipmentInstances.value
        val eq = currentInstances.find { it.id == equipmentId } ?: return
        val stack = eq.toStack()
        val currentStacks = stateStore.equipmentStacks.value
        val existingStack = currentStacks.find {
            it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot
        }
        stateStore.update {
            if (existingStack != null) {
                val maxQty = inventoryConfig.getMaxStackSize(ITEM_TYPE_EQUIPMENT_STACK)
                val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxQty)
                equipmentStacks = equipmentStacks.map { s ->
                    if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                }
            } else {
                equipmentStacks = equipmentStacks + stack
            }
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
        }
    }

    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        stateStore.update {
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
        }
    }
}

/** 弟子死亡事件 */
data class DeathEvent(
    val discipleId: String,
    val discipleName: String,
    val cause: String,
    val deathYear: Int,
    override val type: String = "disciple.death"
) : DomainEvent
