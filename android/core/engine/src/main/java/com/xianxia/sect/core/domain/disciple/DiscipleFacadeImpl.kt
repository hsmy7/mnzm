package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.materializeCaptiveGear
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.StorageBagUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.model.BagStackedData



@Singleton
class DiscipleFacadeImpl @Inject constructor(
    private val discipleService: DiscipleService,
    private val stateStore: GameStateStore,
    private val cultivationService: CultivationService,
    private val gameEngineCore: GameEngineCore,
    private val inventorySystem: InventorySystem,
    private val pillManager: DisciplePillManager,
    private val assignmentGate: DiscipleAssignmentGate,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val lawEnforcementProcessor: LawEnforcementProcessor,
    private val productionCoordinator: ProductionCoordinator,
) : DiscipleFacade {

    companion object {
        private const val TAG = "DiscipleFacadeImpl"
        private const val MAX_NAME_DISPLAY_LEN = 30
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

    override fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
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

    override fun syncSingleDiscipleStatus(discipleId: String) = discipleService.syncSingleDiscipleStatus(discipleId)

    override suspend fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    override fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    override fun expelDisciple(discipleId: String): DomainResult<Unit> = discipleService.expelDisciple(discipleId)

    override fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> = discipleService.apprenticeToMaster(discipleId, masterId)

    override fun releaseReflectionDisciple(discipleId: String) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            if (discipleTables.isAlive[id] != 1) return@update
            val existingData = discipleTables.statusData[id]
            discipleTables.statusData[id] = existingData - setOf("reflectionStartYear", "reflectionEndYear")
            // 清除受保护状态标记，使 deriveDiscipleStatus 可以重新推导（否则 REFLECTING 受保护检查会锁定状态）
            discipleTables.statuses[id] = DiscipleStatus.IDLE
        }
        discipleService.syncSingleDiscipleStatus(discipleId)
    }

    override fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.equipEquipment(discipleId, equipmentId)

    override fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.unequipEquipment(discipleId, equipmentId)

    override fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean =
        discipleService.isDiscipleAssignedToSpiritMine(discipleId)

    override fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) =
        discipleService.updateYearlySalaryEnabled(realm, enabled)

    override fun getAliveDisciplesCount(): Int = discipleService.getAliveDisciplesCount()

    override fun getIdleDisciples(): List<Disciple> = discipleService.getIdleDisciples()

    override fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? =
        discipleService.getDiscipleAggregate(discipleId)

    override fun getAllDiscipleAggregates(): List<DiscipleAggregate> =
        discipleService.getAllDiscipleAggregates()

    override fun updateDiscipleStatus(discipleId: String, status: DiscipleStatus) {
        // 受保护状态（ON_MISSION）必须直接写入，syncAllDiscipleStatuses 不会覆盖它们
        // 但不会主动设置。非受保护状态（IDLE）委托给 syncAllDiscipleStatuses 推导。
        val protectedStatuses = setOf(
            DiscipleStatus.ON_MISSION, DiscipleStatus.REFLECTING, DiscipleStatus.REFINING
        )
        if (status in protectedStatuses) {
            val id = discipleId.toIntOrNull()
            if (id != null) {
                stateStore.update {
                    if (id in discipleTables.ids) {
                        discipleTables.statuses[id] = status
                    }
                }
            }
        }
        discipleService.syncSingleDiscipleStatus(discipleId)
    }


    override fun dismissDisciple(discipleId: String) {
        expelDisciple(discipleId)
    }

    override fun addLifeEvent(discipleId: String, event: String) =
        discipleService.addLifeEvent(discipleId, event)

    override fun getLifeEvents(discipleId: String): List<String> =
        discipleService.getLifeEvents(discipleId)

    override fun initializeLifeEvents(discipleId: String) =
        discipleService.initializeLifeEvents(discipleId)

    override fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        when (itemType) {
            ITEM_TYPE_PILL -> usePill(discipleId, itemId)
        }
    }

    override fun assignManual(discipleId: String, stackId: String) {
        gameEngineCore.launchInScope { learnManual(discipleId, stackId) }
    }

    override fun removeManual(discipleId: String, instanceId: String) {
        gameEngineCore.launchInScope { forgetManual(discipleId, instanceId) }
    }

    override fun recruitDiscipleFromList(discipleId: String): String {
        if (discipleId.isBlank()) {
            DomainLog.w(TAG, "recruitDiscipleFromList: empty discipleId")
            return ""
        }
        var newId: String = ""
        stateStore.update {
            // 事务内检查招募上限（消除事务外读取的 TOCTOU 窗口）
            if (gameData.recruitCountThisMonth.coerceAtLeast(0) >= GameConfig.RECRUIT_MONTHLY_LIMIT) {
                DomainLog.w(TAG, "recruitDiscipleFromList: monthly limit reached (${gameData.recruitCountThisMonth}/${GameConfig.RECRUIT_MONTHLY_LIMIT})")
                pendingNotification = GameNotification.RecruitFailed(
                    "本月招募已达上限（${GameConfig.RECRUIT_MONTHLY_LIMIT}人）"
                )
                return@update
            }
            val disciple = gameData.recruitList.toList().find { it.id == discipleId }
            if (disciple == null) {
                DomainLog.w(TAG, "recruitDiscipleFromList: disciple $discipleId not in recruitList, size=${gameData.recruitList.size}")
                pendingNotification = GameNotification.RecruitFailed("招募失败：该弟子已不在招募列表中")
                return@update
            }
            // ── 完整性校验：损坏条目同事务移除（幽灵立即消失，不再永久残留）──
            if (!RecruitIntegrity.isValidRecruit(disciple)) {
                DomainLog.w(TAG, "recruitDiscipleFromList: skipping corrupted disciple $discipleId: name='${disciple.name}' age=${disciple.age} realm=${disciple.realm}")
                gameData = gameData.copy(
                    recruitList = gameData.recruitList.filter { it.id != discipleId }
                )
                pendingNotification = GameNotification.RecruitFailed(
                    "招募失败：「${disciple.name.take(MAX_NAME_DISPLAY_LEN)}」数据异常"
                )
                return@update
            }
            val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
            val recruitedDisciple = disciple.copy(
                usage = disciple.usage.copy(recruitedMonth = currentMonthValue)
            )
            // 年龄-境界合理性软校验（不阻断：俘虏玩法允许年轻高境界）
            if (disciple.age < GameConfig.Realm.minReasonableAge(disciple.realm)) {
                DomainLog.w(TAG, "recruitDiscipleFromList: recruit ${disciple.name} age=${disciple.age} realm=${disciple.realm} 低于境界最小合理年龄")
            }
            // 原子分配 ID + 写入组件表 + 加入宗门日志（消灭悬空窗口）
            newId = discipleTables.allocateAndInsert(recruitedDisciple)
            if (newId.isNotEmpty()) {
                val intId = newId.toIntOrNull()
                if (intId != null) {
                    val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
                    discipleTables.lifeEvents[intId] = events + "${disciple.age}岁：加入宗门"
                }
                // 俘虏自带装备/功法落库为玩家实例（幂等；普通招募弟子无装备/功法字段，直接跳过）
                materializeCaptiveGear(recruitedDisciple, newId)
            }
            DomainLog.i(TAG, "recruitDiscipleFromList: recruited $discipleId → id=$newId")
            // 招募成功后同步移除同内容双胞胎（防"完全相同弟子"重复招募）
            gameData = gameData.copy(
                recruitList = gameData.recruitList.filter {
                    it.id != discipleId && !RecruitIntegrity.isSamePerson(it, recruitedDisciple)
                },
                recruitCountThisMonth = gameData.recruitCountThisMonth + 1,
                // 年报新增弟子计数（2026-08-11 修复：手动招募主路径漏计）
                annualNewDisciples = gameData.annualNewDisciples + 1
            )
        }
        if (newId.isEmpty()) {
            DomainLog.w(TAG, "recruitDiscipleFromList: FAILED for $discipleId")
        }
        return newId
    }

    override fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>): DomainResult<Unit> {
        items.forEach { item ->
            when (item.type.lowercase(java.util.Locale.getDefault())) {
                ITEM_TYPE_EQUIPMENT -> rewardEquipment(discipleId, item)
                ITEM_TYPE_MANUAL -> rewardManual(discipleId, item)
                ITEM_TYPE_PILL -> rewardPill(discipleId, item, item.quantity.coerceAtLeast(1))
                ITEM_TYPE_MATERIAL -> rewardMaterial(discipleId, item, item.quantity.coerceAtLeast(1))
                ITEM_TYPE_HERB -> rewardHerb(discipleId, item, item.quantity.coerceAtLeast(1))
                ITEM_TYPE_SEED -> rewardSeed(discipleId, item, item.quantity.coerceAtLeast(1))
            }
        }
        return DomainResult.Success(Unit)
    }

    private fun rewardEquipment(discipleId: String, item: RewardSelectedItem) {
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
                        // D-03：卸下的装备实例直接铸造入袋（容量无上限，永不失败），
                        // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
                        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                            updatedDisciple.equipment.storageBagItems,
                            StorageBagItem(
                                itemId = oldEquipId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                                name = oldInstance.name, rarity = oldInstance.rarity, quantity = 1,
                                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                                equipmentInstance = oldInstance
                            )
                        )
                        discipleTables.storageBagSpiritStones[id] = updatedDisciple.equipment.storageBagSpiritStones
                        discipleTables.discipleSpiritStones[id] = updatedDisciple.equipment.spiritStones
                        // 实例入袋后从实例表删除，防止双持有
                        equipmentInstances = equipmentInstances.filter { it.id != oldEquipId }
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
                // D-03：赏赐装备铸造袋条目（容量无上限，永不失败）——扣仓库数量后
                // 袋条目自带 stackedData（minRealm/slot 供取回重建），不再经仓库中转
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_EQUIPMENT_STACK,
                        name = stack.name, rarity = stack.rarity, quantity = 1,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                        forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                        forgetPhase = gameData.gamePhase,
                        stackedData = BagStackedData(minRealm = stack.minRealm, slot = stack.slot.name))
                )
            }
        }
    }

    private fun rewardManual(discipleId: String, item: RewardSelectedItem) {
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
                // D-03：赏赐功法铸造袋条目（容量无上限，永不失败）——扣仓库数量后
                // 袋条目自带 stackedData（minRealm/manualType 供取回重建）
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id],
                    StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_MANUAL_STACK,
                        name = stack.name, rarity = stack.rarity, quantity = 1,
                        obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                        forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                        forgetPhase = gameData.gamePhase,
                        stackedData = BagStackedData(minRealm = stack.minRealm, manualType = stack.type.name))
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
            // 2026-08-01 修复：速率变化点必须同步 checkpoint——
            // 缺失会导致 getEffectiveCultivation 投影用旧速率推导（checkpoint 死代码埋雷）
            discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)
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
            // 道德降低后即时触发偷盗判定（事务内版本，避免重入写覆盖）
            val newMoral = discipleTables.moralities[id]
            if (newMoral < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
                lawEnforcementProcessor.processSingleDiscipleTheft(id, this)
            }
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

    private fun rewardPill(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            val pill = pills.get(item.id)
            if (pill == null || pill.quantity < quantity) return@update
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) return@update
            val pillItem = StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_PILL,
                name = pill.name, rarity = pill.rarity, quantity = quantity,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                effect = pillManager.pillToItemEffect(pill),
                grade = pill.grade.displayName,
                // D-03：已物化标记（数据全在顶层字段，无需堆叠查找）
                stackedData = BagStackedData())
            val disciple = discipleTables.assemble(id)
            val canUse = pillManager.canUsePill(disciple, pillItem).canUse
            if (pill.quantity == quantity) pills.remove(item.id)
            else pills.update(item.id) { it.copy(quantity = pill.quantity - quantity) }
            if (canUse) {
                applyPillEffectsToDisciple(id, pill)
            } else {
                discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                    discipleTables.storageBagItems[id], pillItem)
            }
        }
    }

    private fun rewardMaterial(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            // [对抗性审查-边界 5] 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val material = materials.get(item.id)
            if (material == null || material.isLocked || quantity !in 1..material.quantity) return@update
            if (material.quantity == quantity) materials.remove(item.id)
            else materials.update(item.id) { it.copy(quantity = material.quantity - quantity) }
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id],
                StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_MATERIAL, name = item.name,
                    rarity = item.rarity, quantity = quantity,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    stackedData = BagStackedData())
            )
        }
    }

    private fun rewardHerb(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            // [对抗性审查-边界 5] 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val herb = herbs.get(item.id)
            if (herb == null || herb.isLocked || quantity !in 1..herb.quantity) return@update
            if (herb.quantity == quantity) herbs.remove(item.id)
            else herbs.update(item.id) { it.copy(quantity = herb.quantity - quantity) }
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id],
                StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_HERB, name = item.name,
                    rarity = item.rarity, quantity = quantity,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    stackedData = BagStackedData())
            )
        }
    }

    private fun rewardSeed(discipleId: String, item: RewardSelectedItem, quantity: Int) {
        stateStore.update {
            // [对抗性审查-边界 5] 先校验弟子存在再扣仓库：无效 id 时仓库不被扣减（物品不消失）
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val seed = seeds.get(item.id)
            if (seed == null || seed.isLocked || quantity !in 1..seed.quantity) return@update
            if (seed.quantity == quantity) seeds.remove(item.id)
            else seeds.update(item.id) { it.copy(quantity = seed.quantity - quantity) }
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id],
                StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_SEED, name = item.name,
                    rarity = item.rarity, quantity = quantity,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    stackedData = BagStackedData())
            )
        }
    }

    override fun updateElderSlots(newElderSlots: ElderSlots) {
        gameEngineCore.launchInScope {
            stateStore.update {
                gameData = gameData.copy(elderSlots = newElderSlots)
                
            }
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
        gameEngineCore.launchInScope {
            // 释放旧槽位（自动移除前职务，允许弟子担任新职务）
            var oldOccupantId = ""
            stateStore.update {
                val id = discipleId.toIntOrNull()
                if (id != null && id in discipleTables.ids) {
                    gameData = discipleSlotCleanup.clearAllSlots(gameData, discipleId)
                }
                // 覆写前捕获目标槽旧 occupant（槽位扩容前的原始列表）
                val slots = gameData.elderSlots
                oldOccupantId = when (elderSlotType) {
                    SLOT_TYPE_HERB_GARDEN ->
                        slots.herbGardenDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_ALCHEMY ->
                        slots.alchemyDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_FORGE ->
                        slots.forgeDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_PREACHING ->
                        slots.preachingMasters.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_LAW_ENFORCEMENT ->
                        slots.lawEnforcementDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_QINGYUN ->
                        slots.qingyunPreachingMasters.getOrNull(slotIndex)?.discipleId.orEmpty()
                    SLOT_TYPE_SPIRIT_MINE_DEACON ->
                        slots.spiritMineDeaconDisciples.getOrNull(slotIndex)?.discipleId.orEmpty()
                    else -> ""
                }
                val newSlot = DirectDiscipleSlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = discipleName,
                    discipleRealm = discipleRealm,
                    discipleSpiritRootColor = discipleSpiritRootColor,
                    sectId = gameData.activeSectId
                )
                val updatedSlots = when (elderSlotType) {
                    SLOT_TYPE_HERB_GARDEN -> {
                        val list = slots.herbGardenDisciples.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(herbGardenDisciples = list)
                    }
                    SLOT_TYPE_ALCHEMY -> {
                        val list = slots.alchemyDisciples.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(alchemyDisciples = list)
                    }
                    SLOT_TYPE_FORGE -> {
                        val list = slots.forgeDisciples.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(forgeDisciples = list)
                    }
                    SLOT_TYPE_PREACHING -> {
                        val list = slots.preachingMasters.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(preachingMasters = list)
                    }
                    SLOT_TYPE_LAW_ENFORCEMENT -> {
                        val list = slots.lawEnforcementDisciples.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(lawEnforcementDisciples = list)
                    }
                    SLOT_TYPE_QINGYUN -> {
                        val list = slots.qingyunPreachingMasters.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(qingyunPreachingMasters = list)
                    }
                    SLOT_TYPE_SPIRIT_MINE_DEACON -> {
                        val list = slots.spiritMineDeaconDisciples.toMutableList()
                        while (list.size <= slotIndex) list.add(DirectDiscipleSlot())
                        list[slotIndex] = newSlot
                        slots.copy(spiritMineDeaconDisciples = list)
                    }
                    else -> slots
                }
                gameData = gameData.copy(elderSlots = updatedSlots)
            }
            val slotRef = SlotRef(
                category = SlotCategory.ELDER_POSITION,
                slotType = "$elderSlotType:$slotIndex",
                slotId = "elder_${elderSlotType}_$slotIndex"
            )
            assignmentGate.confirmAssign(discipleId, slotRef)
            // 换人后释放并同步旧 occupant（回归：此前从不 release/sync，
            // 旧弟子 gate 注册残留 + 状态残留从选择弹窗消失）
            if (oldOccupantId.isNotEmpty() && oldOccupantId != discipleId) {
                assignmentGate.release(oldOccupantId)
                discipleService.syncSingleDiscipleStatus(oldOccupantId)
            }
            discipleService.syncSingleDiscipleStatus(discipleId)
            // 双存储同步：事务内 clearAllSlots 清了镜像（含生产槽），
            // 必须同步清 Room 生产槽 Repository，否则残留占用经月度自动重启复活（双槽分叉根因）
            productionCoordinator.clearDiscipleInRepository(gameEngineCore.scopeForStateIn(), discipleId)
        }
    }

    override fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        gameEngineCore.launchInScope {
            // 取出当前亲传弟子 ID 用于释放注册表
            val currentDiscipleId = getDirectDiscipleId(elderSlotType, slotIndex)
            stateStore.update {
                val slots = gameData.elderSlots
                val updatedSlots = when (elderSlotType) {
                    SLOT_TYPE_HERB_GARDEN -> {
                        val list = slots.herbGardenDisciples.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(herbGardenDisciples = list)
                    }
                    SLOT_TYPE_ALCHEMY -> {
                        val list = slots.alchemyDisciples.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(alchemyDisciples = list)
                    }
                    SLOT_TYPE_FORGE -> {
                        val list = slots.forgeDisciples.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(forgeDisciples = list)
                    }
                    SLOT_TYPE_PREACHING -> {
                        val list = slots.preachingMasters.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(preachingMasters = list)
                    }
                    SLOT_TYPE_LAW_ENFORCEMENT -> {
                        val list = slots.lawEnforcementDisciples.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(lawEnforcementDisciples = list)
                    }
                    SLOT_TYPE_QINGYUN -> {
                        val list = slots.qingyunPreachingMasters.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(qingyunPreachingMasters = list)
                    }
                    SLOT_TYPE_SPIRIT_MINE_DEACON -> {
                        val list = slots.spiritMineDeaconDisciples.toMutableList()
                        if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
                        slots.copy(spiritMineDeaconDisciples = list)
                    }
                    else -> slots
                }
                gameData = gameData.copy(elderSlots = updatedSlots)
            }
            if (currentDiscipleId.isNotEmpty()) {
                assignmentGate.release(currentDiscipleId)
            }
            discipleService.syncSingleDiscipleStatus(currentDiscipleId)
        }
    }

    private fun getDirectDiscipleId(elderSlotType: String, slotIndex: Int): String {
        val slots = stateStore.gameDataSnapshot.elderSlots
        val list = when (elderSlotType) {
            SLOT_TYPE_HERB_GARDEN -> slots.herbGardenDisciples
            SLOT_TYPE_ALCHEMY -> slots.alchemyDisciples
            SLOT_TYPE_FORGE -> slots.forgeDisciples
            SLOT_TYPE_PREACHING -> slots.preachingMasters
            SLOT_TYPE_LAW_ENFORCEMENT -> slots.lawEnforcementDisciples
            SLOT_TYPE_QINGYUN -> slots.qingyunPreachingMasters
            SLOT_TYPE_SPIRIT_MINE_DEACON -> slots.spiritMineDeaconDisciples
            else -> emptyList()
        }
        return list.getOrNull(slotIndex)?.discipleId.orEmpty()
    }

    override fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        gameEngineCore.launchInScope {
            val targetSlot = SlotRef(
                category = SlotCategory.LIBRARY_SLOT,
                slotType = "library:$slotIndex",
                slotId = "library_$slotIndex"
            )

            // 释放旧槽位（自动移除前职务，允许弟子担任新职务）
            var oldOccupantId = ""
            stateStore.update {
                val id = discipleId.toIntOrNull()
                if (id != null && id in discipleTables.ids) {
                    gameData = discipleSlotCleanup.clearAllSlots(gameData, discipleId)
                }
                val slots = gameData.librarySlots.toMutableList()
                // 覆写前捕获旧 occupant（槽位扩容前的原始列表）
                oldOccupantId = slots.getOrNull(slotIndex)?.discipleId.orEmpty()
                while (slots.size <= slotIndex) {
                    slots.add(LibrarySlot(index = slots.size))
                }
                slots[slotIndex] = LibrarySlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = discipleName
                )
                gameData = gameData.copy(librarySlots = slots)
            }
            assignmentGate.confirmAssign(discipleId, targetSlot)
            // 换人后释放并同步旧 occupant（回归：此前从不 release/sync，
            // 旧弟子 gate 注册残留 + 状态残留 STUDYING 从选择弹窗消失）
            if (oldOccupantId.isNotEmpty() && oldOccupantId != discipleId) {
                assignmentGate.release(oldOccupantId)
                discipleService.syncSingleDiscipleStatus(oldOccupantId)
            }
            discipleService.syncSingleDiscipleStatus(discipleId)
            // 双存储同步：事务内 clearAllSlots 清了镜像（含生产槽），
            // 必须同步清 Room 生产槽 Repository（双槽分叉根因）
            productionCoordinator.clearDiscipleInRepository(gameEngineCore.scopeForStateIn(), discipleId)
        }
    }

    override fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        gameEngineCore.launchInScope {
            val discipleId = stateStore.gameDataSnapshot.librarySlots
                .getOrNull(slotIndex)?.discipleId.orEmpty()
            stateStore.update {
                if (slotIndex < 0 || slotIndex >= gameData.librarySlots.size) return@update
                val slots = gameData.librarySlots.toMutableList()
                slots[slotIndex] = LibrarySlot(index = slotIndex)
                gameData = gameData.copy(librarySlots = slots)
            }
            if (discipleId.isNotEmpty()) {
                assignmentGate.release(discipleId)
            }
            discipleService.syncSingleDiscipleStatus(discipleId)
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
                    itemId = pillId, itemType = ITEM_TYPE_PILL,
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

                // 记录服药日志
                val pillAge = discipleTables.ages[id]
                val currentLifeEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
                discipleTables.lifeEvents[id] = currentLifeEvents +
                    "${pillAge}岁：服用了${pill.name}"
            }
        }
    }

    private fun learnManual(discipleId: String, stackId: String) {
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

                // 记录学习功法日志
                val learnAge = discipleTables.ages[id]
                val learnEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
                discipleTables.lifeEvents[id] = learnEvents +
                    "${learnAge}岁：学习了${stack.name}"
            }
        }
    }

    private fun forgetManual(discipleId: String, instanceId: String) {
        stateStore.update {
            val instance = manualInstances.get(instanceId) ?: return@update
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            val currentDisciple = discipleTables.assemble(id)

            // D-03：遗忘的功法实例直接铸造入袋（容量无上限，永不失败），
            // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                currentDisciple.equipment.storageBagItems,
                StorageBagItem(
                    itemId = instanceId, itemType = ITEM_TYPE_MANUAL_INSTANCE,
                    name = instance.name, rarity = instance.rarity, quantity = 1,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    manualInstance = instance
                )
            )
            discipleTables.storageBagSpiritStones[id] = currentDisciple.equipment.storageBagSpiritStones
            discipleTables.discipleSpiritStones[id] = currentDisciple.equipment.spiritStones
            discipleTables.manualIds[id] = currentDisciple.manualIds
            manualInstances.remove(instanceId)
        }
    }
}
