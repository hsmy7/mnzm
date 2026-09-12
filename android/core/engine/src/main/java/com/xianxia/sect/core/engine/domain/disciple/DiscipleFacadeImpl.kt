@file:Suppress("LargeClass") // 提取的私有辅助函数集中于本文件，文件级函数数为拆分的固有代价
package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.PillEffect

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
@Suppress("TooManyFunctions")  // 37 个 override 镜像 DiscipleFacade 接口契约下界 + 深耦合成员扩展——TMF 余量为契约骨架，可移动函数已拆出功法/战斗域文件
class DiscipleFacadeImpl @Inject constructor(
    // 放宽为 internal 供 DiscipleLifecycleNativeTx 扩展读取（stateStore/mailRepo 同款三重防护）
    internal val discipleService: DiscipleService,
    internal val stateStore: GameStateStore,
    private val cultivationService: CultivationService,
    internal val gameEngineCore: GameEngineCore,
    internal val pillManager: DisciplePillManager,
    private val assignmentGate: DiscipleAssignmentGate,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val lawEnforcementProcessor: LawEnforcementProcessor,
    private val productionCoordinator: ProductionCoordinator,
) : DiscipleFacade {

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "DiscipleFacadeImpl"
        private const val MAX_NAME_DISPLAY_LEN = 30
        /** native 信封 reason：意外异常兜底（调用方回退 Kotlin 原实现） */
        internal const val REASON_UNKNOWN = "UNKNOWN"
    }

    override val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    override val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates
    /** 高频修炼数据（Q-2：对外只读，写入经 [updateHighFrequencyData]） */
    override val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()

    override val pendingNotification: StateFlow<GameNotification?> get() = stateStore.pendingNotification

    override fun addDisciple(disciple: Disciple) = discipleService.addDisciple(disciple)

    override fun removeDisciple(discipleId: String): DomainResult<Unit> = discipleService.removeDisciple(discipleId)

    override fun getDiscipleById(discipleId: String): Disciple? = discipleService.getDiscipleById(discipleId)

    override fun updateDisciple(disciple: Disciple) = discipleService.updateDisciple(disciple)

    override fun updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
        stateStore.update {
            val id = discipleId.toIntOrNull() ?: return@update
            if (!discipleTables.ids.contains(id)) return@update
            /** 当前设备的电源管理配置 */
            val current = discipleTables.assemble(id)
            val updated = update(current)
            discipleTables.remove(id)
            discipleTables.insert(updated)
        }
    }

    /**
     * Get disciple status based on current assignments
     */
    override fun getDiscipleStatus(discipleId: String): DiscipleStatus =
        discipleService.getDiscipleStatus(discipleId)

    override fun syncAllDiscipleStatuses() = discipleService.syncAllDiscipleStatuses()

    override fun syncSingleDiscipleStatus(discipleId: String) = discipleService.syncSingleDiscipleStatus(discipleId)

    override suspend fun resetAllDisciplesStatus() = discipleService.resetAllDisciplesStatus()

    override fun recruitDisciple(): Disciple = discipleService.recruitDisciple()

    /**
     * Expel disciple from sect
     */
    @Suppress("ReturnCount")  // native 转发/回退双臂逐级早退（tryNativeManualRecruit 同构）
    override fun expelDisciple(discipleId: String): DomainResult<Unit> {
        // AUTHORITATIVE：逐出下沉 C++ 单真相源（batch-14）——C++ 校验链先行
        // 失败零写入，失败信封/降级回退 Kotlin 原实现（双实现并行契约）
        tryNativeExpelDisciple(discipleId)?.let { return it }
        return discipleService.expelDisciple(discipleId)
    }

    override fun apprenticeToMaster(discipleId: String,
        masterId: String): DomainResult<Unit> {
        // AUTHORITATIVE：拜师下沉 C++（batch-14）——三相校验 + masterIds 落表，
        // 双侧 lifeEvents 草稿经信封回写；失败回退 Kotlin 原实现
        tryNativeApprenticeToMaster(discipleId, masterId)?.let { return it }
        return discipleService.apprenticeToMaster(discipleId, masterId)
    }

    override fun releaseReflectionDisciple(discipleId: String) {
        // AUTHORITATIVE：native 臂（batch-14）——null=未转发/失败信封 → 回退原路径
        val nativeWritten = tryNativeReleaseReflection(discipleId)
        if (nativeWritten == null) {
            stateStore.update {
                val id = discipleId.toIntOrNull() ?: return@update
                if (!discipleTables.ids.contains(id)) return@update
                if (discipleTables.isAlive[id] != 1) return@update
                val existingData = discipleTables.statusData[id]
                discipleTables.statusData[id] = existingData - setOf("reflectionStartYear", "reflectionEndYear")
                // 清除受保护状态标记，使 deriveDiscipleStatus 可以重新推导（否则 REFLECTING 受保护检查会锁定状态）
                discipleTables.statuses[id] = DiscipleStatus.IDLE
            }
        }
        discipleService.syncSingleDiscipleStatus(discipleId)
    }

    override fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.equipEquipment(discipleId, equipmentId)

    override fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> =
        discipleService.unequipEquipment(discipleId, equipmentId)

    override fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean =
        discipleService.isDiscipleAssignedToSpiritMine(discipleId)

    override fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        // AUTHORITATIVE：年俸开关下沉 C++（batch-14）——失败回退 Kotlin 原实现
        if (tryNativeSalaryToggle(realm, enabled) == null) {
            discipleService.updateYearlySalaryEnabled(realm, enabled)
        }
    }

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

    /**
     * 获取指定弟子的全部日志事件，按添加顺序排列。
     */
    override fun getLifeEvents(discipleId: String): List<String> =
        discipleService.getLifeEvents(discipleId)

    /**
     * 根据弟子当前状态生成合成历史事件（仅当尚无日志时）。
     * 用于加载旧存档后首次查看日志。
     */
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

    @Suppress("ReturnCount")  // 分发链：空校验/native 转发/回退——逐级早退（与库存转发 tryForward 同构）
    override fun recruitDiscipleFromList(discipleId: String): String {
        if (discipleId.isBlank()) {
            DomainLog.w(TAG, "recruitDiscipleFromList: empty discipleId")
            return ""
        }
        // AUTHORITATIVE：手动招募下沉 C++ 单真相源（与自动招募同侧）——C++ 直接
        // 招募入宗、下一 tick 前向 diff 推送镜像，消除"Kotlin 镜像修改 vs C++
        // 权威结算"竞态与反向回导失败窗口（自动招募正常而手动招募失效的根因域）。
        // native 不可用/信封 UNKNOWN（异常兜底）时回退 Kotlin 原实现（双实现并行契约）。
        if (NativeEngineFlag.authoritative && GameCoreBridge.isLoaded) {
            val nativeResult = tryNativeManualRecruit(discipleId)
            if (nativeResult != null) return nativeResult
        }
        return recruitDiscipleFromListLegacy(discipleId)
    }

    /** 完整性校验失败时同事务移除损坏条目并通知（防幽灵残留；扩展保持 update 事务作用域） */
    internal fun MutableGameState.purgeCorruptedRecruit(discipleId: String, name: String) {
        gameData = gameData.copy(
            recruitList = gameData.recruitList.filter { it.id != discipleId }
        )
        pendingNotification = GameNotification.RecruitFailed(
            "招募失败：「${name.take(MAX_NAME_DISPLAY_LEN)}」数据异常"
        )
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

    /**
     * 统一的丹药效果应用逻辑。消除 rewardPill 与 usePill 约 150 行重复。
     * 调用前须确保 pill 已从库存扣除，且 realm/cannotStack/functionalType 检查已通过。
     */
    internal fun MutableGameState.applyPillEffectsToDisciple(id: Int, pill: Pill) {
        val effect = pill.effects

        if (effect.cultivationAdd > 0) {
            applyCultivationAddEffect(id = id, effect = effect)
        }

        if (effect.skillExpAdd > 0) {
            applySkillExpEffect(id = id, effect = effect)
        }

        if (effect.extendLife > 0) {
            applyExtendLifeEffect(id = id, effect = effect, pill = pill)
        }

        if (DisciplePillManager.hasAnyBaseAttrAdd(
                pillManager.pillToItemEffect(pill)
            )
        ) {
            applyBaseAttrEffects(id = id, effect = effect, pill = pill)
        }

        val itemEffect = pillManager.pillToItemEffect(pill)
        val rule = DisciplePillManager.classify(itemEffect)

        val hasBattleOrSpeedEffect = DisciplePillManager.hasAnyBattleAttrAdd(itemEffect) ||
            effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 ||
            effect.nurtureSpeedPercent > 0
        if (hasBattleOrSpeedEffect) {
            applyBattleAttrEffects(id = id, effect = effect, pill = pill, rule = rule)
        }

        if (effect.healMaxHpPercent > 0) {
            applyHealEffect(id = id, effect = effect)
        }

        if (effect.clearAll) {
            applyClearAllEffect(id = id)
        }
    }

    /** 永久基础属性丹效果：技能属性 + 道德触发偷盗判定 + 记录使用 */
    internal fun MutableGameState.applyBaseAttrEffects(id: Int, effect: PillEffect, pill: Pill) {
        discipleTables.intelligences[id] = discipleTables.intelligences[id].boundedAdd(effect.intelligenceAdd)
        discipleTables.charms[id] = discipleTables.charms[id].boundedAdd(effect.charmAdd)
        discipleTables.loyalties[id] =
            discipleTables.loyalties[id].boundedAdd(effect.loyaltyAdd, GameConfig.Disciple.MAX_LOYALTY)
        discipleTables.comprehensions[id] = discipleTables.comprehensions[id].boundedAdd(effect.comprehensionAdd)
        discipleTables.artifactRefinings[id] =
            discipleTables.artifactRefinings[id].boundedAdd(effect.artifactRefiningAdd)
        discipleTables.pillRefinings[id] = discipleTables.pillRefinings[id].boundedAdd(effect.pillRefiningAdd)
        discipleTables.spiritPlantings[id] = discipleTables.spiritPlantings[id].boundedAdd(effect.spiritPlantingAdd)
        discipleTables.teachings[id] = discipleTables.teachings[id].boundedAdd(effect.teachingAdd)
        discipleTables.moralities[id] = discipleTables.moralities[id].boundedAdd(effect.moralityAdd)
        // 道德降低后即时触发偷盗判定（事务内版本，避免重入写覆盖）
        val newMoral = discipleTables.moralities[id]
        if (newMoral < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
            lawEnforcementProcessor.processSingleDiscipleTheft(id, this)
        }
        discipleTables.minings[id] =
            (discipleTables.minings[id] + effect.miningAdd)
                .coerceIn(0, GameConfig.Disciple.SKILL_MAX)

        // 记录永久属性丹使用
        val itemEffect = pillManager.pillToItemEffect(pill)
        val keys = DisciplePillManager.buildUsedKeys(itemEffect, pill.rarity)
        val usedKeys = discipleTables.usedPermanentPillKeys[id]
        discipleTables.usedPermanentPillKeys[id] = usedKeys + keys
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
                oldOccupantId = getElderSlotOccupant(
                    slots = slots,
                    elderSlotType = elderSlotType,
                    slotIndex = slotIndex
                )
                gameData = gameData.copy(
                    elderSlots = replaceElderSlot(
                        slots = slots,
                        elderSlotType = elderSlotType,
                        slotIndex = slotIndex,
                        newSlot = DirectDiscipleSlot(
                            index = slotIndex,
                            discipleId = discipleId,
                            discipleName = discipleName,
                            discipleRealm = discipleRealm,
                            discipleSpiritRootColor = discipleSpiritRootColor,
                            sectId = gameData.activeSectId
                        )
                    )
                )
            }
            val slotRef = SlotRef(
                category = SlotCategory.ELDER_POSITION,
                slotType = "$elderSlotType:$slotIndex",
                slotId = "elder_${elderSlotType}_$slotIndex"
            )
            assignmentGate.confirmAssign(discipleId, slotRef)
            // 换人后释放并同步旧 occupant（不释放会使旧弟子 gate 注册
            // 与状态残留，从选择弹窗消失）
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
                val updatedSlots = clearDirectDiscipleSlot(slots, elderSlotType, slotIndex)
                gameData = gameData.copy(elderSlots = updatedSlots)
            }
            if (currentDiscipleId.isNotEmpty()) {
                assignmentGate.release(currentDiscipleId)
            }
            discipleService.syncSingleDiscipleStatus(currentDiscipleId)
        }
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
            // 换人后释放并同步旧 occupant（不释放会使旧弟子 gate 注册
            // 与状态残留 STUDYING，从选择弹窗消失）
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

}

/**
 * 功法学习资格守卫：境界 / 名额 / 心法唯一 / 同名唯一四道校验，
 * 校验序与原早退链一致，全部通过返回 true。
 */
internal fun MutableGameState.canLearnManualFromStack(stack: ManualStack, id: Int): Boolean {
    val discipleRealm = discipleTables.realms[id]
    if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)) return false

    val currentManualIds = discipleTables.manualIds[id]
    val maxSlots = DiscipleStatCalculator.getMaxManualSlots(discipleTables.assemble(id))
    if (currentManualIds.size >= maxSlots) return false

    if (stack.type == ManualType.MIND &&
        currentManualIds.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }
    ) return false

    val hasSameName = currentManualIds.any { mid ->
        manualInstances.get(mid)?.name == stack.name
    }
    if (hasSameName) return false
    return true
}

/** 功法堆叠消耗：最后一本整摞移除，否则数量减一 */
internal fun MutableGameState.consumeManualStackForLearn(stackId: String, stack: ManualStack) {
    val newQty = stack.quantity - 1
    if (newQty <= 0) {
        manualStacks.remove(stackId)
    } else {
        manualStacks.update(stackId) { it.copy(quantity = newQty) }
    }
}

/** 学习成果落组件表：功法挂载 + HP/MP 增益 + 入门日志（幂等守卫保留） */
internal fun MutableGameState.applyLearnedManualToTables(id: Int, stack: ManualStack, instanceId: String) {
    val currentManualIds = discipleTables.manualIds[id]
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

/** 单个亲传槽重置：索引有效时覆写为空槽，越界原样返回 */
private fun resetDirectSlotAt(slots: List<DirectDiscipleSlot>, slotIndex: Int): List<DirectDiscipleSlot> {
    val list = slots.toMutableList()
    if (slotIndex < list.size) list[slotIndex] = DirectDiscipleSlot(index = slotIndex)
    return list
}

/** 亲传槽位清空：按槽位类型重置目标槽，未知类型原样返回 */
private fun clearDirectDiscipleSlot(slots: ElderSlots, elderSlotType: String, slotIndex: Int): ElderSlots =
    when (elderSlotType) {
        SLOT_TYPE_HERB_GARDEN ->
            slots.copy(herbGardenDisciples = resetDirectSlotAt(slots.herbGardenDisciples, slotIndex))
        SLOT_TYPE_ALCHEMY ->
            slots.copy(alchemyDisciples = resetDirectSlotAt(slots.alchemyDisciples, slotIndex))
        SLOT_TYPE_FORGE ->
            slots.copy(forgeDisciples = resetDirectSlotAt(slots.forgeDisciples, slotIndex))
        SLOT_TYPE_PREACHING ->
            slots.copy(preachingMasters = resetDirectSlotAt(slots.preachingMasters, slotIndex))
        SLOT_TYPE_LAW_ENFORCEMENT ->
            slots.copy(lawEnforcementDisciples = resetDirectSlotAt(slots.lawEnforcementDisciples, slotIndex))
        SLOT_TYPE_QINGYUN ->
            slots.copy(qingyunPreachingMasters = resetDirectSlotAt(slots.qingyunPreachingMasters, slotIndex))
        SLOT_TYPE_SPIRIT_MINE_DEACON ->
            slots.copy(spiritMineDeaconDisciples = resetDirectSlotAt(slots.spiritMineDeaconDisciples, slotIndex))
        else -> slots
    }

/**
 * 有界累加：`this + add` 后钳制到 [0, max]（属性上限 200 / 忠诚 100 统一入口）。
 * 列直写场景的行宽受限（120 字符），抽为扩展函数保持调用点单行可读。
 */
/** 属性值有界累加：默认钳到技能上限（忠诚等特殊上限显式传参覆盖）。 */
private fun Int.boundedAdd(add: Int, max: Int = GameConfig.Disciple.SKILL_MAX): Int = (this + add).coerceIn(0, max)
