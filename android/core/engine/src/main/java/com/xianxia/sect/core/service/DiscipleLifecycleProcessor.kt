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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
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

    // ── 状态访问器 ──────────────────────────────────────────────────────

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }

    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch { stateStore.update { equipmentInstances = value } }
        }

    private var currentEquipmentStacks: List<EquipmentStack>
        get() = stateStore.currentTransactionMutableState()?.equipmentStacks ?: stateStore.equipmentStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentStacks = value; return }
            scope.launch { stateStore.update { equipmentStacks = value } }
        }

    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }

    // ── 弟子老化/死亡 ──────────────────────────────────────────────────

    fun processGriefExpiry(currentYear: Int) {
        currentDisciples = currentDisciples.map { disciple ->
            val griefEnd = disciple.social.griefEndYear
            if (griefEnd != null && currentYear >= griefEnd) {
                disciple.copy(social = disciple.social.copy(griefEndYear = null))
            } else {
                disciple
            }
        }
    }

    suspend fun processDiscipleAging(currentYear: Int) {
        val data = currentGameData
        val updatedDisciples = currentDisciples.mapNotNull { disciple ->
            if (!disciple.isAlive) return@mapNotNull disciple

            var agedDisciple = disciple.copy(age = disciple.age + 1)

            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copyWith(realmLayer = 1, status = DiscipleStatus.IDLE)
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

        currentDisciples = updatedDisciples
    }

    suspend fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        currentDisciples = DiscipleStatCalculator.applyGriefToRelatives(
            currentDisciples, listOf(disciple), currentGameData.gameYear
        )

        if (isOutsideSect) {
            disciple.equipment.weaponId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.armorId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.bootsId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.accessoryId?.let { removeEquipmentFromDisciple(disciple.id, it) }

            disciple.manualIds.forEach { manualId ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == manualId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = currentGameData
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                currentGameData = data.copy(manualProficiencies = updatedProficiencies)
            }
        } else {
            disciple.equipment.weaponId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.armorId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.bootsId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.accessoryId?.let { returnEquipmentToWarehouse(it) }

            disciple.equipment.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { bagItem ->
                returnEquipmentToWarehouse(bagItem.itemId)
            }

            disciple.equipment.storageBagItems.filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }.forEach { bagItem ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == bagItem.itemId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            disciple.manualIds.forEach { manualId ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == manualId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = currentGameData
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                currentGameData = data.copy(manualProficiencies = updatedProficiencies)
            }
        }
    }

    fun processYearlyAging(year: Int) {
        // 当前版本：年度老化效果尚未实现，保留为扩展点。
    }

    fun processReflectionRelease(year: Int) {
        val reflectingDisciples = currentDisciples.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
        if (reflectingDisciples.isEmpty()) return

        val updatedDisciples = currentDisciples.map { disciple ->
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

        currentDisciples = updatedDisciples
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        currentGameData = DiscipleSlotCleanup.clearAllSlots(currentGameData, discipleId)

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
        val eq = currentEquipmentInstances.find { it.id == equipmentId } ?: return
        val stack = eq.toStack()
        val existingStack = currentEquipmentStacks.find {
            it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot
        }
        if (existingStack != null) {
            val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
            currentEquipmentStacks = currentEquipmentStacks.map { s ->
                if (s.id == existingStack.id) s.copy(quantity = newQty) else s
            }
        } else {
            currentEquipmentStacks = currentEquipmentStacks + stack
        }
        currentEquipmentInstances = currentEquipmentInstances.filter { it.id != equipmentId }
    }

    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        val equipment = currentEquipmentInstances.find { it.id == equipmentId } ?: return
        if (!equipment.isEquipped) return

        currentEquipmentInstances = currentEquipmentInstances.map { eq ->
            if (eq.id == equipmentId) {
                eq.copy(isEquipped = false, ownerId = null, nurtureLevel = 0, nurtureProgress = 0.0)
            } else eq
        }
    }
}
