package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.CoroutineScopeProvider
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
    private val productionSlotRepository: ProductionSlotRepository
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "DiscipleLifecycle"
    }

    // ── 弟子老化/死亡 ──────────────────────────────────────────────────

    fun processGriefExpiry(currentYear: Int) {
        val currentList = stateStore.disciples.value
        val updated = currentList.map { disciple ->
            val griefEnd = disciple.social.griefEndYear
            if (griefEnd != null && currentYear >= griefEnd) {
                disciple.copy(social = disciple.social.copy(griefEndYear = null))
            } else {
                disciple
            }
        }
        scope.launch { stateStore.update {
            discipleTables.clear()
            updated.forEach { discipleTables.insert(it) }
        } }
    }

    suspend fun processDiscipleAging(currentYear: Int) {
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
        }
    }

    suspend fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        val currentDiscipleList = stateStore.disciples.value
        val currentYear = stateStore.gameData.value.gameYear

        val griefUpdated = DiscipleStatCalculator.applyGriefToRelatives(
            currentDiscipleList, listOf(disciple), currentYear
        ).toMutableList()

        // 清除死亡弟子的伴侣关系：若死者有伴侣，清除伴侣的 partnerId 指向
        val partnerId = disciple.social.partnerId
        if (partnerId != null) {
            val partnerIndex = griefUpdated.indexOfFirst { it.id == partnerId }
            if (partnerIndex >= 0) {
                griefUpdated[partnerIndex] = griefUpdated[partnerIndex].copy(
                    social = griefUpdated[partnerIndex].social.copy(partnerId = null)
                )
            }
        }

        stateStore.update {
            discipleTables.clear()
            griefUpdated.forEach { discipleTables.insert(it) }
        }

        if (isOutsideSect) {
            disciple.equipment.weaponId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.armorId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.bootsId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.accessoryId?.let { removeEquipmentFromDisciple(disciple.id, it) }

            val manualIdSet = disciple.manualIds.toSet()
            stateStore.update {
                manualInstances = manualInstances.map {
                    if (it.id in manualIdSet) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = stateStore.gameData.value
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                stateStore.update {
                    gameData = gameData.copy(manualProficiencies = updatedProficiencies)
                }
            }
        } else {
            disciple.equipment.weaponId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.armorId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.bootsId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.accessoryId?.let { returnEquipmentToWarehouse(it) }

            disciple.equipment.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { bagItem ->
                returnEquipmentToWarehouse(bagItem.itemId)
            }

            val storageBagManualIds = disciple.equipment.storageBagItems
                .filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }
                .map { it.itemId }
                .toSet()
            val allManualIds = storageBagManualIds + disciple.manualIds.toSet()
            stateStore.update {
                manualInstances = manualInstances.map {
                    if (it.id in allManualIds) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = stateStore.gameData.value
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                stateStore.update {
                    gameData = gameData.copy(manualProficiencies = updatedProficiencies)
                }
            }
        }
    }

    fun processYearlyAging(year: Int) {
        // 当前版本：年度老化效果尚未实现，保留为扩展点。
    }

    fun processReflectionRelease(year: Int) {
        val currentList = stateStore.disciples.value
        val reflectingDisciples = currentList.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
        if (reflectingDisciples.isEmpty()) return

        val updatedDisciples = currentList.map { disciple ->
            if (disciple.status != DiscipleStatus.REFLECTING || !disciple.isAlive) return@map disciple

            val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: return@map disciple
            if (year < endYear) return@map disciple

            disciple.copy(
                status = DiscipleStatus.IDLE,
                statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear",
                skills = disciple.skills.copy(
                    morality = disciple.skills.morality + 5,
                    loyalty = disciple.skills.loyalty + 5
                )
            )
        }

        scope.launch { stateStore.update {
            discipleTables.clear()
            updatedDisciples.forEach { discipleTables.insert(it) }
        } }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        val data = stateStore.gameData.value
        val cleaned = DiscipleSlotCleanup.clearAllSlots(data, discipleId)
        stateStore.update {
            gameData = cleaned
        }

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
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
        scope.launch { stateStore.update {
            if (existingStack != null) {
                val maxQty = inventoryConfig.getMaxStackSize("equipment_stack")
                val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxQty)
                equipmentStacks = equipmentStacks.map { s ->
                    if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                }
            } else {
                equipmentStacks = equipmentStacks + stack
            }
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
        } }
    }

    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        val equipment = stateStore.equipmentInstances.value.find { it.id == equipmentId } ?: return
        if (!equipment.isEquipped) return

        scope.launch { stateStore.update {
            equipmentInstances = equipmentInstances.map { eq ->
                if (eq.id == equipmentId) {
                    eq.copy(isEquipped = false, ownerId = null, nurtureLevel = 0, nurtureProgress = 0.0)
                } else eq
            }
        } }
    }
}
