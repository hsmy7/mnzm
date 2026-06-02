package com.xianxia.sect.core.engine.domain.disciple

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.addEquipmentInstanceToDiscipleBag
import com.xianxia.sect.core.state.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.state.equipmentBagStackIds
import com.xianxia.sect.core.state.manualBagStackIds
import com.xianxia.sect.core.util.StorageBagUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscipleFacadeImpl @Inject constructor(
    private val discipleService: DiscipleService,
    private val stateStore: GameStateStore,
    private val cultivationService: CultivationService,
    private val gameEngineCore: GameEngineCore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig
) : DiscipleFacade {

    companion object {
        private const val TAG = "DiscipleFacadeImpl"
    }

    override val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    override val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates
    override val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()

    override val realtimeCultivation: StateFlow<Map<String, Double>> by lazy {
        cultivationService.getHighFrequencyData()
            .map { it.realtimeCultivation ?: emptyMap() }
            .stateIn(
                gameEngineCore.scopeForStateIn(),
                SharingStarted.WhileSubscribed(5000),
                emptyMap()
            )
    }

    override val pendingNotification: StateFlow<GameNotification?> get() = stateStore.pendingNotification

    override fun addDisciple(disciple: Disciple) = discipleService.addDisciple(disciple)

    override fun removeDisciple(discipleId: String): Boolean = discipleService.removeDisciple(discipleId)

    override fun getDiscipleById(discipleId: String): Disciple? = discipleService.getDiscipleById(discipleId)

    override fun updateDisciple(disciple: Disciple) = discipleService.updateDisciple(disciple)

    override suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateStore.update {
            val list = disciples.toMutableList()
            val index = list.indexOfFirst { it.id == discipleId }
            if (index >= 0) {
                list[index] = update(list[index])
                disciples = list
            }
        }
    }

    override fun getDiscipleStatus(discipleId: String): DiscipleStatus =
        discipleService.getDiscipleStatus(discipleId)

    override fun syncAllDiscipleStatuses() = discipleService.syncAllDiscipleStatuses()

    override suspend fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    override fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    override suspend fun expelDisciple(discipleId: String): Boolean = discipleService.expelDisciple(discipleId)

    override suspend fun expelTheftDisciple(discipleId: String): Boolean = discipleService.expelDisciple(discipleId)

    override fun imprisonTheftDisciple(discipleId: String, currentYear: Int) {
        stateStore.updateDisciplesDirect { disciples ->
            disciples.map {
                if (it.id == discipleId) it.copy(
                    status = DiscipleStatus.REFLECTING,
                    statusData = it.statusData + mapOf(
                        "reflectionStartYear" to currentYear.toString(),
                        "reflectionEndYear" to (currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                    )
                ) else it
            }
        }
    }

    override fun releaseTheftDisciple(discipleId: String): Int {
        val loyaltyChange = (1..10).random()
        stateStore.updateDisciplesDirect { disciples ->
            disciples.map {
                if (it.id == discipleId) {
                    val baseStats = DiscipleStatCalculator.getBaseStats(it)
                    it.copy(
                        status = DiscipleStatus.IDLE,
                        statusData = it.statusData - setOf("reflectionStartYear", "reflectionEndYear"),
                        skills = it.skills.copy(
                            loyalty = (baseStats.loyalty + loyaltyChange).coerceAtLeast(0)
                        )
                    )
                } else it
            }
        }
        return loyaltyChange
    }

    override suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean =
        discipleService.equipEquipment(discipleId, equipmentId)

    override suspend fun unequipEquipment(discipleId: String, equipmentId: String): Boolean =
        discipleService.unequipEquipment(discipleId, equipmentId)

    override fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean =
        discipleService.isDiscipleAssignedToSpiritMine(discipleId)

    override fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) =
        discipleService.updateMonthlySalaryEnabled(realm, enabled)

    override fun getAliveDisciplesCount(): Int = discipleService.getAliveDisciplesCount()

    override fun getIdleDisciples(): List<Disciple> = discipleService.getIdleDisciples()

    override suspend fun autoFillLawEnforcementSlots(): Int = discipleService.autoFillLawEnforcementSlots()

    override fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? =
        discipleService.getDiscipleAggregate(discipleId)

    override fun getAllDiscipleAggregates(): List<DiscipleAggregate> =
        discipleService.getAllDiscipleAggregates()

    override suspend fun approveMarriage(maleId: String, femaleId: String) {
        updateDisciple(maleId) { it.copyWith(partnerId = femaleId) }
        updateDisciple(femaleId) { it.copyWith(partnerId = maleId) }
    }

    override suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            disciples = disciples.map {
                if (it.id == discipleId) it.copy(status = status) else it
            }
        }
    }

    override suspend fun updateFocusedDisciple(discipleId: String) {
        stateStore.update {
            cultivationService.updateFocusedDisciple(discipleId, this)
        }
    }

    override suspend fun dismissDisciple(discipleId: String) {
        expelDisciple(discipleId)
    }

    override fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        when (itemType) {
            "pill" -> usePill(discipleId, itemId)
        }
    }

    override fun assignManual(discipleId: String, stackId: String) {
        gameEngineCore.launchInScope { learnManual(discipleId, stackId) }
    }

    override fun removeManual(discipleId: String, instanceId: String) {
        gameEngineCore.launchInScope { forgetManual(discipleId, instanceId) }
    }

    override fun recruitDiscipleFromList(discipleId: String) {
        val data = stateStore.gameData.value
        val disciple = data.recruitList.find { it.id == discipleId } ?: return
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val recruitedDisciple = disciple.copyWith(recruitedMonth = currentMonthValue)
        gameEngineCore.launchInScope {
            stateStore.update {
                disciples = disciples + recruitedDisciple
                gameData = gameData.copy(recruitList = gameData.recruitList.filter { it.id != discipleId })
            }
        }
    }

    override suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        val data = stateStore.gameData.value
        items.forEach { item ->
            val quantity = item.quantity.coerceAtLeast(1)
            when (item.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> {
                    stateStore.update {
                        val stack = equipmentStacks.find { it.id == item.id }
                        if (stack == null || stack.quantity < 1) return@update

                        val disciple = disciples.find { it.id == discipleId }
                        if (disciple == null) return@update

                        val canEquip = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)

                        if (canEquip) {
                            val slot = stack.slot
                            val oldEquipId = when (slot) {
                                EquipmentSlot.WEAPON -> disciple.equipment.weaponId
                                EquipmentSlot.ARMOR -> disciple.equipment.armorId
                                EquipmentSlot.BOOTS -> disciple.equipment.bootsId
                                EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
                                else -> ""
                            }

                            var updatedDisciple = disciple

                            if (oldEquipId.isNotEmpty()) {
                                val oldInstance = equipmentInstances.find { it.id == oldEquipId }
                                if (oldInstance != null) {
                                    val bagStackIds = updatedDisciple.equipmentBagStackIds()
                                    val result = addEquipmentInstanceToDiscipleBag(
                                        disciple = updatedDisciple,
                                        instance = oldInstance,
                                        bagStackIds = bagStackIds,
                                        excludeStackId = stack.id,
                                        gameYear = gameData.gameYear,
                                        gameMonth = gameData.gameMonth,
                                        gamePhase = gameData.gamePhase,
                                        maxStackSize = inventoryConfig.getMaxStackSize("equipment_stack")
                                    )
                                    updatedDisciple = result.updatedDisciple
                                }

                                updatedDisciple = when (slot) {
                                    EquipmentSlot.WEAPON -> updatedDisciple.copyWith(weaponId = "")
                                    EquipmentSlot.ARMOR -> updatedDisciple.copyWith(armorId = "")
                                    EquipmentSlot.BOOTS -> updatedDisciple.copyWith(bootsId = "")
                                    EquipmentSlot.ACCESSORY -> updatedDisciple.copyWith(accessoryId = "")
                                    else -> updatedDisciple
                                }
                            }

                            if (stack.quantity > 1) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == item.id) s.copy(quantity = s.quantity - 1) else s
                                }
                            } else {
                                equipmentStacks = equipmentStacks.filter { it.id != item.id }
                            }

                            val instanceId = java.util.UUID.randomUUID().toString()
                            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isEquipped = true)
                            equipmentInstances = equipmentInstances + instance

                            updatedDisciple = when (slot) {
                                EquipmentSlot.WEAPON -> updatedDisciple.copyWith(weaponId = instanceId)
                                EquipmentSlot.ARMOR -> updatedDisciple.copyWith(armorId = instanceId)
                                EquipmentSlot.BOOTS -> updatedDisciple.copyWith(bootsId = instanceId)
                                EquipmentSlot.ACCESSORY -> updatedDisciple.copyWith(accessoryId = instanceId)
                                else -> updatedDisciple
                            }

                            disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }

                        } else {
                            if (stack.quantity > 1) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == item.id) s.copy(quantity = s.quantity - 1) else s
                                }
                            } else {
                                equipmentStacks = equipmentStacks.filter { it.id != item.id }
                            }

                            val bagStackIds = disciple.equipmentBagStackIds()
                            val bagStackId: String
                            val existingBagStack = equipmentStacks.find {
                                it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id != item.id && it.id in bagStackIds && it.quantity < inventoryConfig.getMaxStackSize("equipment_stack")
                            }
                            if (existingBagStack != null) {
                                equipmentStacks = equipmentStacks.map { s ->
                                    if (s.id == existingBagStack.id) s.copy(quantity = existingBagStack.quantity + 1) else s
                                }
                                bagStackId = existingBagStack.id
                            } else {
                                val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                                equipmentStacks = equipmentStacks + newStack
                                bagStackId = newStack.id
                            }

                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = bagStackId,
                                                itemType = "equipment_stack",
                                                name = stack.name,
                                                rarity = stack.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth,
                                                forgetYear = gameData.gameYear,
                                                forgetMonth = gameData.gameMonth,
                                                forgetPhase = gameData.gamePhase
                                            ),
                                            inventoryConfig.getMaxStackSize("equipment_stack")
                                        )
                                    )
                                } else d
                            }

                        }
                    }
                }
                "manual" -> {
                    stateStore.update {
                        val stack = manualStacks.find { it.id == item.id }
                        if (stack == null || stack.quantity < 1) return@update

                        val disciple = disciples.find { it.id == discipleId }
                        if (disciple == null) return@update

                        val canLearn = GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm) &&
                            disciple.manualIds.size < DiscipleStatCalculator.getMaxManualSlots(disciple) &&
                            !(stack.type == ManualType.MIND && disciple.manualIds.any { mid ->
                                manualInstances.find { m -> m.id == mid }?.type == ManualType.MIND
                            }) &&
                            !disciple.manualIds.any { mid ->
                                manualInstances.find { m -> m.id == mid }?.name == stack.name
                            }

                        if (canLearn) {
                            val newQty = stack.quantity - 1
                            if (newQty <= 0) {
                                manualStacks = manualStacks.filter { it.id != item.id }
                            } else {
                                manualStacks = manualStacks.map {
                                    if (it.id == item.id) it.copy(quantity = newQty) else it
                                }
                            }

                            val instanceId = java.util.UUID.randomUUID().toString()
                            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
                            manualInstances = manualInstances + instance

                            disciples = disciples.map {
                                if (it.id == discipleId) {
                                    it.copy(manualIds = it.manualIds + instanceId)
                                } else it
                            }

                        } else {
                            val newQty = stack.quantity - 1
                            if (newQty <= 0) {
                                manualStacks = manualStacks.filter { it.id != item.id }
                            } else {
                                manualStacks = manualStacks.map {
                                    if (it.id == item.id) it.copy(quantity = newQty) else it
                                }
                            }

                            val bagStackIds = disciple.manualBagStackIds()

                            val existingBagStack = manualStacks.find {
                                it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type && it.id != item.id && it.id in bagStackIds && it.quantity < inventoryConfig.getMaxStackSize("manual_stack")
                            }

                            val storageItemId: String
                            if (existingBagStack != null) {
                                manualStacks = manualStacks.map {
                                    if (it.id == existingBagStack.id) it.copy(quantity = it.quantity + 1) else it
                                }
                                storageItemId = existingBagStack.id
                            } else {
                                val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                                manualStacks = manualStacks + newStack
                                storageItemId = newStack.id
                            }

                            disciples = disciples.map {
                                if (it.id == discipleId) {
                                    it.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            it.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = storageItemId,
                                                itemType = "manual_stack",
                                                name = stack.name,
                                                rarity = stack.rarity,
                                                quantity = 1,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth,
                                                forgetYear = gameData.gameYear,
                                                forgetMonth = gameData.gameMonth,
                                                forgetPhase = gameData.gamePhase
                                            ),
                                            inventoryConfig.getMaxStackSize("manual_stack")
                                        )
                                    )
                                } else it
                            }
                        }
                    }
                }
                "pill" -> {
                    stateStore.update {
                        val pill = pills.find { it.id == item.id }
                        if (pill != null && pill.quantity >= quantity) {
                            val disciple = disciples.find { it.id == discipleId }
                            if (disciple == null) return@update
                            val pillItem = StorageBagItem(
                                itemId = item.id,
                                itemType = "pill",
                                name = pill.name,
                                rarity = pill.rarity,
                                quantity = quantity,
                                obtainedYear = gameData.gameYear,
                                obtainedMonth = gameData.gameMonth,
                                effect = DisciplePillManager.pillToItemEffect(pill),
                                grade = pill.grade.displayName
                            )
                            val canUse = DisciplePillManager.canUsePill(disciple, pillItem).canUse

                            pills = pills.mapNotNull { p ->
                                if (p.id == item.id) {
                                    val newQty = p.quantity - quantity
                                    if (newQty == 0) null else p.copy(quantity = newQty)
                                } else p
                            }

                            if (canUse) {
                                var updatedDisciple = disciple
                                val effect = pill.effects
                                if (effect.cultivationAdd > 0) {
                                    updatedDisciple = updatedDisciple.copy(cultivation = (updatedDisciple.cultivation + effect.cultivationAdd).coerceAtLeast(0.0))
                                }
                                if (effect.skillExpAdd > 0) {
                                    updatedDisciple = updatedDisciple.copy(manualMasteries = updatedDisciple.manualMasteries.mapValues { (_, v) -> (v + effect.skillExpAdd).coerceAtMost(10000) })
                                }
                                if (effect.cultivationSpeedPercent > 0) {
                                    updatedDisciple = updatedDisciple.copy(
                                        cultivationSpeedBonus = effect.cultivationSpeedPercent,
                                        cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.cultivationSpeedDuration
                                    )
                                }
                                if (effect.extendLife > 0) {
                                    updatedDisciple = updatedDisciple.copy(lifespan = updatedDisciple.lifespan + effect.extendLife)
                                    if (!updatedDisciple.usage.usedExtendLifePillIds.contains(pill.pillType)) {
                                        updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedExtendLifePillIds = updatedDisciple.usage.usedExtendLifePillIds + pill.pillType))
                                    }
                                }
                                if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 ||
                                    effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
                                    effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0 ||
                                    effect.miningAdd > 0
                                ) {
                                    updatedDisciple = updatedDisciple.copy(
                                        skills = updatedDisciple.skills.copy(
                                            intelligence = (updatedDisciple.skills.intelligence + effect.intelligenceAdd).coerceAtLeast(0),
                                            charm = (updatedDisciple.skills.charm + effect.charmAdd).coerceAtLeast(0),
                                            loyalty = (updatedDisciple.skills.loyalty + effect.loyaltyAdd).coerceAtLeast(0),
                                            comprehension = (updatedDisciple.skills.comprehension + effect.comprehensionAdd).coerceAtLeast(0),
                                            artifactRefining = (updatedDisciple.skills.artifactRefining + effect.artifactRefiningAdd).coerceAtLeast(0),
                                            pillRefining = (updatedDisciple.skills.pillRefining + effect.pillRefiningAdd).coerceAtLeast(0),
                                            spiritPlanting = (updatedDisciple.skills.spiritPlanting + effect.spiritPlantingAdd).coerceAtLeast(0),
                                            teaching = (updatedDisciple.skills.teaching + effect.teachingAdd).coerceAtLeast(0),
                                            morality = (updatedDisciple.skills.morality + effect.moralityAdd).coerceAtLeast(0),
                                            mining = (updatedDisciple.skills.mining + effect.miningAdd).coerceAtLeast(0)
                                        )
                                    )
                                }
                                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                                    updatedDisciple = updatedDisciple.copy(usage = updatedDisciple.usage.copy(usedFunctionalPillTypes = updatedDisciple.usage.usedFunctionalPillTypes + pill.pillType))
                                }
                                if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
                                    effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
                                    effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
                                    effect.critRateAdd > 0 || effect.critEffectAdd > 0 ||
                                    effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0
                                ) {
                                    updatedDisciple = updatedDisciple.copy(pillEffects = updatedDisciple.pillEffects.copy(
                                        pillPhysicalAttackBonus = effect.physicalAttackAdd,
                                        pillMagicAttackBonus = effect.magicAttackAdd,
                                        pillPhysicalDefenseBonus = effect.physicalDefenseAdd,
                                        pillMagicDefenseBonus = effect.magicDefenseAdd,
                                        pillHpBonus = effect.hpAdd,
                                        pillMpBonus = effect.mpAdd,
                                        pillSpeedBonus = effect.speedAdd,
                                        pillCritRateBonus = effect.critRateAdd,
                                        pillCritEffectBonus = effect.critEffectAdd,
                                        pillCultivationSpeedBonus = effect.cultivationSpeedPercent,
                                        pillSkillExpSpeedBonus = effect.skillExpSpeedPercent,
                                        pillNurtureSpeedBonus = effect.nurtureSpeedPercent,
                                        pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.pillEffects.pillEffectDuration,
                                        activePillCategory = if (effect.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                                    ))
                                }
                                if (effect.healMaxHpPercent > 0) {
                                    updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
                                }
                                if (effect.clearAll) {
                                    updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
                                }
                                disciples = disciples.map { if (it.id == discipleId) updatedDisciple else it }
                            } else {
                                disciples = disciples.map { d ->
                                    if (d.id == discipleId) {
                                        d.copyWith(
                                            storageBagItems = StorageBagUtils.increaseItemQuantity(
                                                d.equipment.storageBagItems,
                                                pillItem,
                                                inventoryConfig.getMaxStackSize("pill")
                                            )
                                        )
                                    } else d
                                }
                            }
                        }
                    }
                }
                "material" -> {
                    stateStore.update {
                        val material = materials.find { it.id == item.id }
                        if (material != null && !material.isLocked && quantity in 1..material.quantity) {
                            materials = materials.mapNotNull { m ->
                                if (m.id == item.id) {
                                    val newQty = m.quantity - quantity
                                    if (newQty == 0) null else m.copy(quantity = newQty)
                                } else m
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "material",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("material")
                                        )
                                    )
                                } else d
                            }
                        }
                    }
                }
                "herb" -> {
                    stateStore.update {
                        val herb = herbs.find { it.id == item.id }
                        if (herb != null && !herb.isLocked && quantity in 1..herb.quantity) {
                            herbs = herbs.mapNotNull { h ->
                                if (h.id == item.id) {
                                    val newQty = h.quantity - quantity
                                    if (newQty == 0) null else h.copy(quantity = newQty)
                                } else h
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "herb",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("herb")
                                        )
                                    )
                                } else d
                            }
                        }
                    }
                }
                "seed" -> {
                    stateStore.update {
                        val seed = seeds.find { it.id == item.id }
                        if (seed != null && !seed.isLocked && quantity in 1..seed.quantity) {
                            seeds = seeds.mapNotNull { s ->
                                if (s.id == item.id) {
                                    val newQty = s.quantity - quantity
                                    if (newQty == 0) null else s.copy(quantity = newQty)
                                } else s
                            }
                            disciples = disciples.map { d ->
                                if (d.id == discipleId) {
                                    d.copyWith(
                                        storageBagItems = StorageBagUtils.increaseItemQuantity(
                                            d.equipment.storageBagItems,
                                            StorageBagItem(
                                                itemId = item.id,
                                                itemType = "seed",
                                                name = item.name,
                                                rarity = item.rarity,
                                                quantity = quantity,
                                                obtainedYear = gameData.gameYear,
                                                obtainedMonth = gameData.gameMonth
                                            ),
                                            inventoryConfig.getMaxStackSize("seed")
                                        )
                                    )
                                } else d
                            }
                        }
                    }
                }
            }
        }
    }

    override fun updateElderSlots(newElderSlots: ElderSlots) {
        stateStore.updateGameDataDirect { it.copy(elderSlots = newElderSlots) }
        gameEngineCore.launchInScope {
            discipleService.syncAllDiscipleStatuses()
        }
    }

    override fun assignDirectDisciple(
        elderSlotType: String,
        slotIndex: Int,
        discipleId: String,
        discipleName: String,
        discipleRealm: String,
        discipleSpiritRootColor: String
    ) {
        val data = stateStore.gameData.value
        val slots = data.elderSlots
        val newSlot = DirectDiscipleSlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName,
            discipleRealm = discipleRealm,
            discipleSpiritRootColor = discipleSpiritRootColor,
            sectId = data.activeSectId
        )
        val updatedSlots = when (elderSlotType) {
            "herbGarden" -> {
                val list = slots.herbGardenDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(herbGardenDisciples = list)
            }
            "alchemy" -> {
                val list = slots.alchemyDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(alchemyDisciples = list)
            }
            "forge" -> {
                val list = slots.forgeDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(forgeDisciples = list)
            }
            "preaching" -> {
                val list = slots.preachingMasters.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(preachingMasters = list)
            }
            "lawEnforcement" -> {
                val list = slots.lawEnforcementDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(lawEnforcementDisciples = list)
            }
            "lawEnforcementReserve" -> {
                val list = slots.lawEnforcementReserveDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(lawEnforcementReserveDisciples = list)
            }
            "qingyunPreaching" -> {
                val list = slots.qingyunPreachingMasters.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(qingyunPreachingMasters = list)
            }
            "spiritMineDeacon" -> {
                val list = slots.spiritMineDeaconDisciples.toMutableList()
                while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                list[slotIndex] = newSlot
                slots.copy(spiritMineDeaconDisciples = list)
            }
            else -> slots
        }
        stateStore.updateGameDataDirect { it.copy(elderSlots = updatedSlots) }
        gameEngineCore.launchInScope {
            discipleService.syncAllDiscipleStatuses()
            if (elderSlotType == "lawEnforcement") {
                discipleService.autoFillLawEnforcementSlots()
            }
        }
    }

    override fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        val slots = stateStore.gameData.value.elderSlots
        val updatedSlots = when (elderSlotType) {
            "herbGarden" -> {
                val list = slots.herbGardenDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(herbGardenDisciples = list)
            }
            "alchemy" -> {
                val list = slots.alchemyDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(alchemyDisciples = list)
            }
            "forge" -> {
                val list = slots.forgeDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(forgeDisciples = list)
            }
            "preaching" -> {
                val list = slots.preachingMasters.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(preachingMasters = list)
            }
            "lawEnforcement" -> {
                val list = slots.lawEnforcementDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(lawEnforcementDisciples = list)
            }
            "lawEnforcementReserve" -> {
                val list = slots.lawEnforcementReserveDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(lawEnforcementReserveDisciples = list)
            }
            "qingyunPreaching" -> {
                val list = slots.qingyunPreachingMasters.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(qingyunPreachingMasters = list)
            }
            "spiritMineDeacon" -> {
                val list = slots.spiritMineDeaconDisciples.toMutableList()
                if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                slots.copy(spiritMineDeaconDisciples = list)
            }
            else -> slots
        }
        stateStore.updateGameDataDirect { it.copy(elderSlots = updatedSlots) }
        gameEngineCore.launchInScope {
            discipleService.syncAllDiscipleStatuses()
            if (elderSlotType == "lawEnforcement") {
                discipleService.autoFillLawEnforcementSlots()
            }
        }
    }

    override fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        val data = stateStore.gameData.value
        val slots = data.librarySlots.toMutableList()
        if (slots.any { it.discipleId == discipleId && it.index != slotIndex }) return
        while (slots.size <= slotIndex) {
            slots.add(LibrarySlot(index = slots.size))
        }
        slots[slotIndex] = LibrarySlot(
            index = slotIndex,
            discipleId = discipleId,
            discipleName = discipleName
        )
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(librarySlots = slots) }
            discipleService.syncAllDiscipleStatuses()
        }
    }

    override fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        val data = stateStore.gameData.value
        if (slotIndex < 0 || slotIndex >= data.librarySlots.size) return
        val slots = data.librarySlots.toMutableList()
        slots[slotIndex] = LibrarySlot(index = slotIndex)
        gameEngineCore.launchInScope {
            stateStore.update { gameData = gameData.copy(librarySlots = slots) }
            discipleService.syncAllDiscipleStatuses()
        }
    }

    override fun clearPendingNotification() {
        stateStore.clearPendingNotification()
    }

    private fun usePill(discipleId: String, pillId: String) {
        gameEngineCore.launchInScope {
            stateStore.update {
                val pill = pills.find { it.id == pillId } ?: return@update
                if (pill.quantity <= 0) return@update
                val disciple = disciples.find { it.id == discipleId } ?: return@update

                if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, pill.minRealm)) return@update

                if (pill.effects.cannotStack && disciple.pillEffects.activePillCategory == pill.category.name) return@update

                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                    if (disciple.usage.usedFunctionalPillTypes.contains(pill.pillType)) return@update
                }

                val updatedPills = pills.map {
                    if (it.id == pillId && it.quantity > 0) it.copy(quantity = it.quantity - 1) else it
                }.filter { it.quantity > 0 }
                pills = updatedPills

                val effect = pill.effects
                var updatedDisciple = disciple

                if (effect.cultivationAdd > 0) {
                    val newCultivation = (updatedDisciple.cultivation + effect.cultivationAdd).coerceAtLeast(0.0)
                    updatedDisciple = updatedDisciple.copy(cultivation = newCultivation)
                }

                if (effect.skillExpAdd > 0) {
                    val updatedMasteries = updatedDisciple.manualMasteries.mapValues { (_, v) ->
                        (v + effect.skillExpAdd).coerceAtMost(10000)
                    }
                    updatedDisciple = updatedDisciple.copy(manualMasteries = updatedMasteries)
                }

                if (effect.cultivationSpeedPercent > 0) {
                    updatedDisciple = updatedDisciple.copy(
                        cultivationSpeedBonus = effect.cultivationSpeedPercent,
                        cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.cultivationSpeedDuration
                    )
                }

                if (effect.extendLife > 0) {
                    updatedDisciple = updatedDisciple.copy(lifespan = updatedDisciple.lifespan + effect.extendLife)
                    if (!updatedDisciple.usage.usedExtendLifePillIds.contains(pill.pillType)) {
                        updatedDisciple = updatedDisciple.copy(
                            usage = updatedDisciple.usage.copy(
                                usedExtendLifePillIds = updatedDisciple.usage.usedExtendLifePillIds + pill.pillType
                            )
                        )
                    }
                }

                if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 ||
                    effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
                    effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0 ||
                    effect.miningAdd > 0
                ) {
                    updatedDisciple = updatedDisciple.copy(
                        skills = updatedDisciple.skills.copy(
                            intelligence = (updatedDisciple.skills.intelligence + effect.intelligenceAdd).coerceAtLeast(0),
                            charm = (updatedDisciple.skills.charm + effect.charmAdd).coerceAtLeast(0),
                            loyalty = (updatedDisciple.skills.loyalty + effect.loyaltyAdd).coerceAtLeast(0),
                            comprehension = (updatedDisciple.skills.comprehension + effect.comprehensionAdd).coerceAtLeast(0),
                            artifactRefining = (updatedDisciple.skills.artifactRefining + effect.artifactRefiningAdd).coerceAtLeast(0),
                            pillRefining = (updatedDisciple.skills.pillRefining + effect.pillRefiningAdd).coerceAtLeast(0),
                            spiritPlanting = (updatedDisciple.skills.spiritPlanting + effect.spiritPlantingAdd).coerceAtLeast(0),
                            teaching = (updatedDisciple.skills.teaching + effect.teachingAdd).coerceAtLeast(0),
                            morality = (updatedDisciple.skills.morality + effect.moralityAdd).coerceAtLeast(0),
                            mining = (updatedDisciple.skills.mining + effect.miningAdd).coerceAtLeast(0)
                        )
                    )
                }

                if (pill.category == PillCategory.FUNCTIONAL && pill.pillType.isNotEmpty()) {
                    updatedDisciple = updatedDisciple.copy(
                        usage = updatedDisciple.usage.copy(
                            usedFunctionalPillTypes = updatedDisciple.usage.usedFunctionalPillTypes + pill.pillType
                        )
                    )
                }

                if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
                    effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
                    effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
                    effect.critRateAdd > 0 || effect.critEffectAdd > 0 ||
                    effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0
                ) {
                    val newEffects = updatedDisciple.pillEffects.copy(
                        pillPhysicalAttackBonus = effect.physicalAttackAdd,
                        pillMagicAttackBonus = effect.magicAttackAdd,
                        pillPhysicalDefenseBonus = effect.physicalDefenseAdd,
                        pillMagicDefenseBonus = effect.magicDefenseAdd,
                        pillHpBonus = effect.hpAdd,
                        pillMpBonus = effect.mpAdd,
                        pillSpeedBonus = effect.speedAdd,
                        pillCritRateBonus = effect.critRateAdd,
                        pillCritEffectBonus = effect.critEffectAdd,
                        pillCultivationSpeedBonus = effect.cultivationSpeedPercent,
                        pillSkillExpSpeedBonus = effect.skillExpSpeedPercent,
                        pillNurtureSpeedBonus = effect.nurtureSpeedPercent,
                        pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updatedDisciple.pillEffects.pillEffectDuration,
                        activePillCategory = if (effect.cannotStack) pill.category.name else updatedDisciple.pillEffects.activePillCategory
                    )
                    updatedDisciple = updatedDisciple.copy(pillEffects = newEffects)
                }

                if (effect.healMaxHpPercent > 0) {
                    updatedDisciple = updatedDisciple.copy(combat = updatedDisciple.combat.copy(hpVariance = 0))
                }

                if (effect.clearAll) {
                    updatedDisciple = updatedDisciple.copy(pillEffects = PillEffects())
                }

                disciples = disciples.map {
                    if (it.id == discipleId) updatedDisciple else it
                }
            }
        }
    }

    private suspend fun learnManual(discipleId: String, stackId: String) {
        stateStore.update {
            val stack = manualStacks.find { it.id == stackId } ?: return@update
            val disciple = disciples.find { it.id == discipleId } ?: return@update

            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)) return@update

            val maxSlots = DiscipleStatCalculator.getMaxManualSlots(disciple)
            if (disciple.manualIds.size >= maxSlots) return@update

            if (stack.type == ManualType.MIND) {
                val hasMind = disciple.manualIds.any { mid ->
                    manualInstances.find { it.id == mid }?.type == ManualType.MIND
                }
                if (hasMind) return@update
            }

            val hasSameName = disciple.manualIds.any { mid ->
                manualInstances.find { it.id == mid }?.name == stack.name
            }
            if (hasSameName) return@update

            val newQty = stack.quantity - 1
            if (newQty <= 0) {
                manualStacks = manualStacks.filter { it.id != stackId }
            } else {
                manualStacks = manualStacks.map {
                    if (it.id == stackId) it.copy(quantity = newQty) else it
                }
            }

            val instanceId = java.util.UUID.randomUUID().toString()
            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
            manualInstances = manualInstances + instance

            disciples = disciples.map {
                if (it.id == discipleId && !it.manualIds.contains(instanceId)) {
                    val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
                    val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
                    val rawHp = it.combat.currentHp
                    val rawMp = it.combat.currentMp
                    val newHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp
                    val newMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp
                    it.copy(
                        manualIds = it.manualIds + instanceId,
                        combat = it.combat.copy(currentHp = newHp, currentMp = newMp)
                    )
                } else it
            }
        }
    }

    private suspend fun forgetManual(discipleId: String, instanceId: String) {
        stateStore.update {
            val instance = manualInstances.find { it.id == instanceId } ?: return@update
            val currentDisciple = disciples.find { it.id == discipleId } ?: return@update
            val bagStackIds = currentDisciple.manualBagStackIds()

            val result = addManualInstanceToDiscipleBag(
                disciple = currentDisciple,
                instance = instance,
                bagStackIds = bagStackIds,
                gameYear = gameData.gameYear,
                gameMonth = gameData.gameMonth,
                gamePhase = gameData.gamePhase,
                maxStackSize = inventoryConfig.getMaxStackSize("manual_stack")
            )

            disciples = disciples.map {
                if (it.id == discipleId) result.updatedDisciple else it
            }
            manualInstances = manualInstances.filter { it.id != instanceId }
        }
    }
}
