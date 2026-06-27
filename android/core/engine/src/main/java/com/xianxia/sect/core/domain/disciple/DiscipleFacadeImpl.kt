package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.util.DomainLog
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
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.addEquipmentInstanceToDiscipleBag
import com.xianxia.sect.core.util.addManualInstanceToDiscipleBag
import com.xianxia.sect.core.util.equipmentBagStackIds
import com.xianxia.sect.core.util.manualBagStackIds
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
    private val inventoryConfig: InventoryConfig,
    private val pillManager: DisciplePillManager
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

    override fun removeDisciple(discipleId: String): DomainResult<Unit> = discipleService.removeDisciple(discipleId)

    override fun getDiscipleById(discipleId: String): Disciple? = discipleService.getDiscipleById(discipleId)

    override fun updateDisciple(disciple: Disciple) = discipleService.updateDisciple(disciple)

    override suspend fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val current = discipleTables.assemble(id)
            val updated = update(current)
            discipleTables.remove(id)
            discipleTables.insert(updated)
        }
    }

    override fun getDiscipleStatus(discipleId: String): DiscipleStatus =
        discipleService.getDiscipleStatus(discipleId)

    override fun syncAllDiscipleStatuses() = discipleService.syncAllDiscipleStatuses()

    override suspend fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    override fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    override suspend fun expelDisciple(discipleId: String): DomainResult<Unit> = discipleService.expelDisciple(discipleId)

    override suspend fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> = discipleService.apprenticeToMaster(discipleId, masterId)

    override suspend fun expelTheftDisciple(discipleId: String): DomainResult<Unit> = discipleService.expelDisciple(discipleId)

    override suspend fun imprisonTheftDisciple(discipleId: String, currentYear: Int) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            discipleTables.statuses[id] = DiscipleStatus.REFLECTING
            val existingData = discipleTables.statusData[id]
            discipleTables.statusData[id] = existingData + mapOf(
                "reflectionStartYear" to currentYear.toString(),
                "reflectionEndYear" to (currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
            )
        }
    }

    override suspend fun releaseTheftDisciple(discipleId: String): Int {
        val loyaltyChange = (1..10).random()
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            discipleTables.statuses[id] = DiscipleStatus.IDLE
            val existingData = discipleTables.statusData[id]
            discipleTables.statusData[id] = existingData - setOf("reflectionStartYear", "reflectionEndYear")
            val disciple = discipleTables.assemble(id)
            val baseStats = DiscipleStatCalculator.getBaseStats(disciple)
            discipleTables.loyalties[id] = (baseStats.loyalty + loyaltyChange).coerceAtLeast(0)
        }
        return loyaltyChange
    }

    override suspend fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.equipEquipment(discipleId, equipmentId)

    override suspend fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.unequipEquipment(discipleId, equipmentId)

    override fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean =
        discipleService.isDiscipleAssignedToSpiritMine(discipleId)

    override fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) =
        discipleService.updateYearlySalaryEnabled(realm, enabled)

    override fun getAliveDisciplesCount(): Int = discipleService.getAliveDisciplesCount()

    override fun getIdleDisciples(): List<Disciple> = discipleService.getIdleDisciples()

    override suspend fun autoFillLawEnforcementSlots(): Int = discipleService.autoFillLawEnforcementSlots()

    override fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? =
        discipleService.getDiscipleAggregate(discipleId)

    override fun getAllDiscipleAggregates(): List<DiscipleAggregate> =
        discipleService.getAllDiscipleAggregates()

    override suspend fun approveMarriage(maleId: String, femaleId: String) {
        stateStore.update {
            val maleIntId = maleId.toIntOrNull()
            val femaleIntId = femaleId.toIntOrNull()
            if (maleIntId != null && discipleTables.ids.contains(maleIntId)) {
                discipleTables.partnerIds[maleIntId] = femaleId
            }
            if (femaleIntId != null && discipleTables.ids.contains(femaleIntId)) {
                discipleTables.partnerIds[femaleIntId] = maleId
            }
        }
    }

    override suspend fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            discipleTables.statuses[id] = status
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
        val disciple = data.recruitList.toList().find { it.id == discipleId } ?: return
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val newId = ((stateStore.discipleTables.ids.maxOrNull() ?: 0) + 1).toString()
        val recruitedDisciple = disciple.copy(
            id = newId,
            usage = disciple.usage.copy(recruitedMonth = currentMonthValue)
        )
        gameEngineCore.launchInScope {
            stateStore.update {
                discipleTables.insert(recruitedDisciple)
                gameData = gameData.copy(recruitList = gameData.recruitList.filter { it.id != discipleId })
            }
        }
    }

    override suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>): DomainResult<Unit> {
        items.forEach { item ->
            when (item.type.lowercase(java.util.Locale.getDefault())) {
                "equipment" -> rewardEquipment(discipleId, item)
                "manual" -> rewardManual(discipleId, item)
                "pill" -> rewardPill(discipleId, item, item.quantity.coerceAtLeast(1))
                "material" -> rewardMaterial(discipleId, item, item.quantity.coerceAtLeast(1))
                "herb" -> rewardHerb(discipleId, item, item.quantity.coerceAtLeast(1))
                "seed" -> rewardSeed(discipleId, item, item.quantity.coerceAtLeast(1))
            }
        }
        return DomainResult.Success(Unit)
    }

    private suspend fun rewardEquipment(discipleId: String, item: RewardSelectedItem) {
        stateStore.update {
            val stack = equipmentStacks.get(item.id)
            if (stack == null || stack.quantity < 1) return@update
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) return@update
            val discipleRealm = discipleTables.realms[id]
            val canEquip = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
            if (canEquip) {
                val slot = stack.slot
                val oldEquipId = when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
                    else -> ""
                }
                if (oldEquipId.isNotEmpty()) {
                    val oldInstance = equipmentInstances.get(oldEquipId)
                    if (oldInstance != null) {
                        val updatedDisciple = discipleTables.assemble(id)
                        val bagStackIds = updatedDisciple.equipmentBagStackIds()
                        val result = addEquipmentInstanceToDiscipleBag(
                            disciple = updatedDisciple, instance = oldInstance,
                            bagStackIds = bagStackIds, excludeStackId = stack.id,
                            gameYear = gameData.gameYear, gameMonth = gameData.gameMonth,
                            gamePhase = gameData.gamePhase,
                            maxStackSize = inventoryConfig.getMaxStackSize("equipment_stack")
                        )
                        discipleTables.storageBagItems[id] = result.updatedDisciple.equipment.storageBagItems
                        discipleTables.storageBagSpiritStones[id] = result.updatedDisciple.equipment.storageBagSpiritStones
                        discipleTables.discipleSpiritStones[id] = result.updatedDisciple.equipment.spiritStones
                    }
                    when (slot) {
                        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = ""
                        EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = ""
                        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = ""
                        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = ""
                        else -> {}
                    }
                }
                if (stack.quantity > 1) {
                    equipmentStacks.update(item.id) { it.copy(quantity = it.quantity - 1) }
                } else {
                    equipmentStacks.remove(item.id)
                }
                val instanceId = java.util.UUID.randomUUID().toString()
                equipmentInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isEquipped = true))
                when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = instanceId
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = instanceId
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = instanceId
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = instanceId
                    else -> {}
                }
            } else {
                if (stack.quantity > 1) {
                    equipmentStacks.update(item.id) { it.copy(quantity = it.quantity - 1) }
                } else {
                    equipmentStacks.remove(item.id)
                }
                val currentDisciple = discipleTables.assemble(id)
                val bagStackIds = currentDisciple.equipmentBagStackIds()
                val existingBagStack = equipmentStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot
                        && it.id != item.id && it.id in bagStackIds
                        && it.quantity < inventoryConfig.getMaxStackSize("equipment_stack")
                }
                val bagStackId: String
                if (existingBagStack != null) {
                    equipmentStacks.update(existingBagStack.id) { it.copy(quantity = it.quantity + 1) }
                    bagStackId = existingBagStack.id
                } else {
                    val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                    equipmentStacks.add(newStack)
                    bagStackId = newStack.id
                }
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = bagStackId, itemType = "equipment_stack",
                        name = stack.name, rarity = stack.rarity, quantity = 1,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                        forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                        forgetPhase = gameData.gamePhase),
                    inventoryConfig.getMaxStackSize("equipment_stack")
                )
            }
        }
    }

    private suspend fun rewardManual(discipleId: String, item: RewardSelectedItem) {
        stateStore.update {
            val stack = manualStacks.get(item.id)
            if (stack == null || stack.quantity < 1) return@update
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) return@update
            val discipleRealm = discipleTables.realms[id]
            val currentManualIds = discipleTables.manualIds[id]
            val canLearn = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm) &&
                currentManualIds.size < DiscipleStatCalculator.getMaxManualSlots(discipleTables.assemble(id)) &&
                !(stack.type == ManualType.MIND && currentManualIds.any { manualInstances.get(it)?.type == ManualType.MIND }) &&
                !currentManualIds.any { manualInstances.get(it)?.name == stack.name }
            if (canLearn) {
                if (stack.quantity <= 1) manualStacks.remove(item.id)
                else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
                val instanceId = java.util.UUID.randomUUID().toString()
                manualInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true))
                discipleTables.manualIds[id] = currentManualIds + instanceId
            } else {
                if (stack.quantity <= 1) manualStacks.remove(item.id)
                else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
                val currentDisciple = discipleTables.assemble(id)
                val bagStackIds = currentDisciple.manualBagStackIds()
                val existingBagStack = manualStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type
                        && it.id != item.id && it.id in bagStackIds
                        && it.quantity < inventoryConfig.getMaxStackSize("manual_stack")
                }
                val storageItemId: String
                if (existingBagStack != null) {
                    manualStacks.update(existingBagStack.id) { it.copy(quantity = it.quantity + 1) }
                    storageItemId = existingBagStack.id
                } else {
                    val newStack = stack.copy(id = java.util.UUID.randomUUID().toString(), quantity = 1)
                    manualStacks.add(newStack)
                    storageItemId = newStack.id
                }
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = storageItemId, itemType = "manual_stack",
                        name = stack.name, rarity = stack.rarity, quantity = 1,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                        forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                        forgetPhase = gameData.gamePhase),
                    inventoryConfig.getMaxStackSize("manual_stack")
                )
            }
        }
    }

    /**
     * 统一的丹药效果应用逻辑。消除 rewardPill 与 usePill 约 150 行重复。
     * 调用前须确保 pill 已从库存扣除，且 realm/cannotStack/functionalType 检查已通过。
     */
    private fun MutableGameState.applyPillEffectsToDisciple(id: Int, pill: Pill) {
        val effect = pill.effects

        if (effect.cultivationAdd > 0) {
            discipleTables.cultivations[id] = discipleTables.cultivations[id] + effect.cultivationAdd
        }

        if (effect.skillExpAdd > 0) {
            discipleTables.manualMasteries[id] = discipleTables.manualMasteries[id].mapValues { (_, v) ->
                (v + effect.skillExpAdd).coerceAtMost(10000)
            }
        }

        if (effect.cultivationSpeedPercent > 0) {
            discipleTables.cultivationSpeedBonuses[id] = effect.cultivationSpeedPercent
            // 以旬为单位，不再 *30
            discipleTables.cultivationSpeedDurations[id] = if (effect.duration > 0) effect.duration
                else discipleTables.cultivationSpeedDurations[id]
        }

        if (effect.extendLife > 0) {
            discipleTables.lifespans[id] = discipleTables.lifespans[id] + effect.extendLife
            val usedExtendLife = discipleTables.usedExtendLifePillTypes[id]
            if (pill.pillType !in usedExtendLife) {
                discipleTables.usedExtendLifePillTypes[id] = usedExtendLife + pill.pillType
            }
        }

        if (DisciplePillManager.hasAnyBaseAttrAdd(
                pillManager.pillToItemEffect(pill)
            )
        ) {
            discipleTables.intelligences[id] = discipleTables.intelligences[id] + effect.intelligenceAdd
            discipleTables.charms[id] = discipleTables.charms[id] + effect.charmAdd
            discipleTables.loyalties[id] = discipleTables.loyalties[id] + effect.loyaltyAdd
            discipleTables.comprehensions[id] = discipleTables.comprehensions[id] + effect.comprehensionAdd
            discipleTables.artifactRefinings[id] = discipleTables.artifactRefinings[id] + effect.artifactRefiningAdd
            discipleTables.pillRefinings[id] = discipleTables.pillRefinings[id] + effect.pillRefiningAdd
            discipleTables.spiritPlantings[id] = discipleTables.spiritPlantings[id] + effect.spiritPlantingAdd
            discipleTables.teachings[id] = discipleTables.teachings[id] + effect.teachingAdd
            discipleTables.moralities[id] = discipleTables.moralities[id] + effect.moralityAdd
            discipleTables.minings[id] = discipleTables.minings[id] + effect.miningAdd

            // 记录永久属性丹使用
            val itemEffect = pillManager.pillToItemEffect(pill)
            val keys = DisciplePillManager.buildUsedKeys(itemEffect, pill.rarity)
            val usedKeys = discipleTables.usedPermanentPillKeys[id]
            discipleTables.usedPermanentPillKeys[id] = usedKeys + keys
        }

        val itemEffect = pillManager.pillToItemEffect(pill)
        val rule = DisciplePillManager.classify(itemEffect)

        if (DisciplePillManager.hasAnyBattleAttrAdd(itemEffect) ||
            effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 ||
            effect.nurtureSpeedPercent > 0
        ) {
            discipleTables.pillPhysicalAttackBonuses[id] = effect.physicalAttackAdd
            discipleTables.pillMagicAttackBonuses[id] = effect.magicAttackAdd
            discipleTables.pillPhysicalDefenseBonuses[id] = effect.physicalDefenseAdd
            discipleTables.pillMagicDefenseBonuses[id] = effect.magicDefenseAdd
            discipleTables.pillHpBonuses[id] = effect.hpAdd
            discipleTables.pillMpBonuses[id] = effect.mpAdd
            discipleTables.pillSpeedBonuses[id] = effect.speedAdd
            discipleTables.pillCritRateBonuses[id] = effect.critRateAdd
            discipleTables.pillCritEffectBonuses[id] = effect.critEffectAdd
            discipleTables.pillCultivationSpeedBonuses[id] = effect.cultivationSpeedPercent
            discipleTables.pillSkillExpSpeedBonuses[id] = effect.skillExpSpeedPercent
            discipleTables.pillNurtureSpeedBonuses[id] = effect.nurtureSpeedPercent
            // 以旬为单位，不再 *30
            val currentDuration = discipleTables.pillEffectDurations[id]
            discipleTables.pillEffectDurations[id] = if (effect.duration > 0)
                maxOf(currentDuration, effect.duration)
            else currentDuration

            // 持续/临时效果记录 pillType
            if (rule == PillRule.SUSTAINED_SPEED || rule == PillRule.TEMPORARY_BATTLE) {
                val activeTypes = discipleTables.activePillTypes[id]
                if (pill.pillType.isNotEmpty()) {
                    discipleTables.activePillTypes[id] = activeTypes + pill.pillType
                }
            }
        }

        if (effect.healMaxHpPercent > 0) {
            val rawHp = discipleTables.currentHps[id]
            val maxHp = discipleTables.baseHps[id]
            val currentHp = if (rawHp < 0) maxHp else rawHp
            val healAmount = (maxHp * effect.healMaxHpPercent).toInt().coerceAtLeast(1)
            discipleTables.currentHps[id] = (currentHp + healAmount).coerceAtMost(maxHp)
        }

        if (effect.clearAll) {
            discipleTables.pillPhysicalAttackBonuses[id] = 0
            discipleTables.pillMagicAttackBonuses[id] = 0
            discipleTables.pillPhysicalDefenseBonuses[id] = 0
            discipleTables.pillMagicDefenseBonuses[id] = 0
            discipleTables.pillHpBonuses[id] = 0
            discipleTables.pillMpBonuses[id] = 0
            discipleTables.pillSpeedBonuses[id] = 0
            discipleTables.pillEffectDurations[id] = 0
            discipleTables.pillCritRateBonuses[id] = 0.0
            discipleTables.pillCritEffectBonuses[id] = 0.0
            discipleTables.pillCultivationSpeedBonuses[id] = 0.0
            discipleTables.pillSkillExpSpeedBonuses[id] = 0.0
            discipleTables.pillNurtureSpeedBonuses[id] = 0.0
            discipleTables.activePillCategories[id] = ""
            discipleTables.activePillTypes[id] = emptySet()
        }
    }

    private suspend fun rewardPill(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            val pill = pills.get(item.id)
            if (pill == null || pill.quantity < quantity) return@update
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) return@update
            val pillItem = StorageBagItem(itemId = item.id, itemType = "pill",
                name = pill.name, rarity = pill.rarity, quantity = quantity,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                effect = pillManager.pillToItemEffect(pill),
                grade = pill.grade.displayName)
            val disciple = discipleTables.assemble(id)
            val canUse = pillManager.canUsePill(disciple, pillItem).canUse
            if (pill.quantity == quantity) pills.remove(item.id)
            else pills.update(item.id) { it.copy(quantity = pill.quantity - quantity) }
            if (canUse) {
                applyPillEffectsToDisciple(id, pill)
            } else {
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id], pillItem, inventoryConfig.getMaxStackSize("pill"))
            }
        }
    }

    private suspend fun rewardMaterial(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            val material = materials.get(item.id)
            if (material == null || material.isLocked || quantity !in 1..material.quantity) return@update
            if (material.quantity == quantity) materials.remove(item.id)
            else materials.update(item.id) { it.copy(quantity = material.quantity - quantity) }
            val id = discipleId.toIntOrNull()
            if (id != null && discipleTables.ids.contains(id)) {
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = item.id, itemType = "material", name = item.name,
                        rarity = item.rarity, quantity = quantity,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth),
                    inventoryConfig.getMaxStackSize("material"))
            }
        }
    }

    private suspend fun rewardHerb(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            val herb = herbs.get(item.id)
            if (herb == null || herb.isLocked || quantity !in 1..herb.quantity) return@update
            if (herb.quantity == quantity) herbs.remove(item.id)
            else herbs.update(item.id) { it.copy(quantity = herb.quantity - quantity) }
            val id = discipleId.toIntOrNull()
            if (id != null && discipleTables.ids.contains(id)) {
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = item.id, itemType = "herb", name = item.name,
                        rarity = item.rarity, quantity = quantity,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth),
                    inventoryConfig.getMaxStackSize("herb"))
            }
        }
    }

    private suspend fun rewardSeed(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            val seed = seeds.get(item.id)
            if (seed == null || seed.isLocked || quantity !in 1..seed.quantity) return@update
            if (seed.quantity == quantity) seeds.remove(item.id)
            else seeds.update(item.id) { it.copy(quantity = seed.quantity - quantity) }
            val id = discipleId.toIntOrNull()
            if (id != null && discipleTables.ids.contains(id)) {
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = item.id, itemType = "seed", name = item.name,
                        rarity = item.rarity, quantity = quantity,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth),
                    inventoryConfig.getMaxStackSize("seed"))
            }
        }
    }

    override fun updateElderSlots(newElderSlots: ElderSlots) {
        gameEngineCore.launchInScope {
            stateStore.update {
                gameData = gameData.copy(elderSlots = newElderSlots)
                discipleService.syncAllDiscipleStatuses()
            }
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
        gameEngineCore.launchInScope {
            stateStore.update {
                gameData = gameData.copy(elderSlots = updatedSlots)
                discipleService.syncAllDiscipleStatuses()
                if (elderSlotType == "lawEnforcement") {
                    discipleService.autoFillLawEnforcementSlots()
                }
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
        gameEngineCore.launchInScope {
            stateStore.update {
                gameData = gameData.copy(elderSlots = updatedSlots)
                discipleService.syncAllDiscipleStatuses()
                if (elderSlotType == "lawEnforcement") {
                    discipleService.autoFillLawEnforcementSlots()
                }
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
            stateStore.update {
                gameData = gameData.copy(librarySlots = slots)
                discipleService.syncAllDiscipleStatuses()
            }
        }
    }

    override fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        val data = stateStore.gameData.value
        if (slotIndex < 0 || slotIndex >= data.librarySlots.size) return
        val slots = data.librarySlots.toMutableList()
        slots[slotIndex] = LibrarySlot(index = slotIndex)
        gameEngineCore.launchInScope {
            stateStore.update {
                gameData = gameData.copy(librarySlots = slots)
                discipleService.syncAllDiscipleStatuses()
            }
        }
    }

    override fun clearPendingNotification() {
        stateStore.clearPendingNotification()
    }

    private fun usePill(discipleId: String, pillId: String) {
        gameEngineCore.launchInScope {
            stateStore.update {
                val pill = pills.get(pillId) ?: return@update
                if (pill.quantity <= 0) return@update
                val id = discipleId.toIntOrNull() ?: return@update
                if (!discipleTables.ids.contains(id)) return@update

                // 委托 pillManager 统一检查资格
                val disciple = discipleTables.assemble(id)
                val itemEffect = pillManager.pillToItemEffect(pill)
                val bagItem = StorageBagItem(
                    itemId = pillId, itemType = "pill",
                    name = pill.name, rarity = pill.rarity, quantity = 1,
                    effect = itemEffect
                )
                if (!pillManager.canUsePill(disciple, bagItem).canUse) return@update

                if (pill.quantity > 1) {
                    pills.update(pillId) { it.copy(quantity = it.quantity - 1) }
                } else {
                    pills.remove(pillId)
                }

                applyPillEffectsToDisciple(id, pill)
            }
        }
    }

    private suspend fun learnManual(discipleId: String, stackId: String) {
        stateStore.update {
            val stack = manualStacks.get(stackId) ?: return@update
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update

            val discipleRealm = discipleTables.realms[id]
            if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)) return@update

            val currentManualIds = discipleTables.manualIds[id]
            val maxSlots = DiscipleStatCalculator.getMaxManualSlots(discipleTables.assemble(id))
            if (currentManualIds.size >= maxSlots) return@update

            if (stack.type == ManualType.MIND) {
                val hasMind = currentManualIds.any { mid ->
                    manualInstances.get(mid)?.type == ManualType.MIND
                }
                if (hasMind) return@update
            }

            val hasSameName = currentManualIds.any { mid ->
                manualInstances.get(mid)?.name == stack.name
            }
            if (hasSameName) return@update

            val newQty = stack.quantity - 1
            if (newQty <= 0) {
                manualStacks.remove(stackId)
            } else {
                manualStacks.update(stackId) { it.copy(quantity = newQty) }
            }

            val instanceId = java.util.UUID.randomUUID().toString()
            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
            manualInstances.add(instance)

            if (!currentManualIds.contains(instanceId)) {
                val hpDelta = stack.stats["hp"] ?: stack.stats["maxHp"] ?: 0
                val mpDelta = stack.stats["mp"] ?: stack.stats["maxMp"] ?: 0
                val rawHp = discipleTables.currentHps[id]
                val rawMp = discipleTables.currentMps[id]
                val newHp = if (rawHp >= 0 && hpDelta > 0) rawHp + hpDelta else rawHp
                val newMp = if (rawMp >= 0 && mpDelta > 0) rawMp + mpDelta else rawMp
                discipleTables.manualIds[id] = currentManualIds + instanceId
                discipleTables.currentHps[id] = newHp
                discipleTables.currentMps[id] = newMp
            }
        }
    }

    private suspend fun forgetManual(discipleId: String, instanceId: String) {
        stateStore.update {
            val instance = manualInstances.get(instanceId) ?: return@update
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val currentDisciple = discipleTables.assemble(id)
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

            // Write back updated fields from result.updatedDisciple
            discipleTables.storageBagItems[id] = result.updatedDisciple.equipment.storageBagItems
            discipleTables.storageBagSpiritStones[id] = result.updatedDisciple.equipment.storageBagSpiritStones
            discipleTables.discipleSpiritStones[id] = result.updatedDisciple.equipment.spiritStones
            discipleTables.manualIds[id] = result.updatedDisciple.manualIds
            manualInstances.remove(instanceId)
        }
    }
}
