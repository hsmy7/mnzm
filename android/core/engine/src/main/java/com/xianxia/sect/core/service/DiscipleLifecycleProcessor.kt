package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.griefEndYear
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.parentId1
import com.xianxia.sect.core.model.parentId2
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.state.DeathRecord
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.disciple.computeMaxAge
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.util.AppError
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
@GameService("DiscipleLifecycleProcessor")
class DiscipleLifecycleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val productionCoordinator: ProductionCoordinator,
    private val eventBus: EventBusPort,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val lawEnforcementProcessor: javax.inject.Provider<LawEnforcementProcessor>,
    private val discipleStatusService: DiscipleStatusService,
    private val ioDispatcher: IoDispatcher,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
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
            discipleTables.replaceAll(updated)
        }
    }

    fun processDiscipleAging(currentYear: Int) {
        // 从组件表读取，不依赖 Flow（防止 Flow 缺失数据被 replaceAll 永久覆盖）
        val currentList = stateStore.discipleTables.assembleAll()
        val (deadThisYear, deadDiscipleData) = computeAgedDeathData(currentList)

        if (deadThisYear.isEmpty()) {
            // 无死亡，仅更新活弟子字段（单事务）
            stateStore.update {
                applyAliveUpdates(currentList, emptySet())
            }
            discipleStatusService.syncAllDiscipleStatuses()
            return
        }

        // 第二阶段+第三阶段合并：单事务内完成死亡处理+活弟子字段更新
        // 消除原两阶段间的 TOCTOU 窗口（handleDiscipleDeath 的事务与 Phase3 事务之间）
        stateStore.update {
            for ((id, agedDisciple) in deadDiscipleData) {
                applyAgedDeath(currentList, agedDisciple, currentYear)
            }
            // ── 活弟子字段更新 ──
            applyAliveUpdates(currentList, deadThisYear)
        }

        discipleStatusService.syncAllDiscipleStatuses()

        // ── 事务外操作：双存储同步清理 + 事件分发（非原子操作，不影响游戏状态一致性）──
        // clearDiscipleFromAllSlots = 事务内清镜像（幂等，applyAgedDeath 已清）+ 事务外清
        // Room 生产槽 Repository（原 clearForgeSlotsIfNeeded 只清锻造槽，炼丹/灵田槽残留
        // 导致死亡弟子继续显示在生产界面——双槽分叉根因）
        for ((id, agedDisciple) in deadDiscipleData) {
            clearDiscipleFromAllSlots(agedDisciple.id)
            eventBus.emitSync(DeathEvent(
                discipleId = agedDisciple.id,
                discipleName = agedDisciple.name,
                cause = "age",
                deathYear = currentYear
            ))
        }
    }

    /** 老化判断结果：寿元耗尽弟子（id → 老化后快照） */
    private data class AgedDeathData(
        val deadThisYear: Set<Int>,
        val deadDiscipleData: Map<Int, Disciple>
    )

    /** 第一阶段：判断生死、标记回生（5 岁境界层回正） */
    private fun computeAgedDeathData(currentList: List<Disciple>): AgedDeathData {
        val deadThisYear = mutableSetOf<Int>()
        val deadDiscipleData = mutableMapOf<Int, Disciple>() // id → aged disciple snapshot

        for (disciple in currentList) {
            if (!disciple.isAlive) continue

            var agedDisciple = disciple.copy(age = disciple.age + 1)

            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copy(realmLayer = 1, status = DiscipleStatus.IDLE)
            }

            val maxAge = agedDisciple.computeMaxAge()
            if (agedDisciple.age >= maxAge) {
                val intId = disciple.id.toIntOrNull() ?: continue
                deadThisYear.add(intId)
                deadDiscipleData[intId] = agedDisciple
            }
        }
        return AgedDeathData(deadThisYear, deadDiscipleData)
    }

    /**
     * 单死者老化死亡事务处理：槽位清理、哀悼传播、伴侣/师徒解绑、
     * 血炼清理、装备/功法清除、死亡记录。
     * 在调用方 stateStore.update 事务内执行。
     */
    private fun MutableGameState.applyAgedDeath(
        currentList: List<Disciple>,
        agedDisciple: Disciple,
        currentYear: Int
    ) {
        val id = agedDisciple.id.toIntOrNull() ?: return
        // ── 槽位清理（事务内版本，替换 handleDiscipleDeath 的独立 update）──
        gameData = discipleSlotCleanup.clearAllSlots(gameData, agedDisciple.id, includeResidence = true)

        // ── 哀悼期传播 + 伴侣/师徒解绑 ──
        val griefUpdated = propagateGriefToRelatives(currentList, agedDisciple, currentYear)
        unbindPartnerRelationship(griefUpdated, agedDisciple)
        unbindMasterRelationships(griefUpdated, agedDisciple)

        val lifeEventsToWrite = computeBereavementLifeEvents(
            griefUpdated, currentList, agedDisciple, discipleTables
        )

        discipleTables.replaceAll(griefUpdated)
        discipleTables.deathYears[id] = currentYear

        lifeEventsToWrite.forEach { (grievingId, event) ->
            val prevEvents = discipleTables.lifeEvents.getOrDefault(grievingId, emptyList())
            discipleTables.lifeEvents[grievingId] = prevEvents + event
        }

        // ── 血炼清理 ──
        gameData = gameData.copy(
            bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - agedDisciple.id,
            bloodRefinements = gameData.bloodRefinements - agedDisciple.id
        )

        // ── D-03：袋物品物化回仓库（玩家保留，溢出自动转邮件）──
        val agedBagItems = agedDisciple.equipment.storageBagItems
        if (agedBagItems.isNotEmpty()) {
            inventorySystem.withTrackingSource("disciple_death") {
                inventorySystem.materializeBagItemsToWarehouse(agedBagItems)
            }
        }

        // ── 装备/功法清除 ──
        val deleteEquipIds = mutableSetOf<String>()
        agedDisciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        agedDisciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        agedDisciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        agedDisciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        val deleteManualIds = agedDisciple.manualIds.toSet()

        equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
        manualInstances = manualInstances.filter { it.id !in deleteManualIds }

        // ── 死亡记录 ──
        discipleTables.addDeathRecord(DeathRecord(
            id = id, name = agedDisciple.name, surname = agedDisciple.surname,
            realm = agedDisciple.realm, realmLayer = agedDisciple.realmLayer,
            deathAge = agedDisciple.age, deathYear = currentYear, cause = "age"
        ))
        discipleTables.remove(id)
        // remove 会清空 deathYears，重新写入以保留记录
        discipleTables.deathYears[id] = currentYear

        recordGameEvent(
            GameEventCategory.SECT, GameEventType.DEATH,
            "${agedDisciple.name}陨落（寿元耗尽）",
            agedDisciple.id, agedDisciple.name
        )
        gameData = gameData.copy(
            annualDeceasedDisciples = gameData.annualDeceasedDisciples + 1
        )
    }

    /** 活弟子老化字段更新（年龄 +1、5 岁境界层回正），跳过死亡弟子 */
    private fun MutableGameState.applyAliveUpdates(
        currentList: List<Disciple>,
        deadThisYear: Set<Int>
    ) {
        for (disciple in currentList) {
            val id = disciple.id.toIntOrNull() ?: continue
            if (id in deadThisYear || !disciple.isAlive) continue
            val agedAge = disciple.age + 1
            discipleTables.ages[id] = agedAge
            if (agedAge == 5 && disciple.realmLayer == 0) {
                discipleTables.realmLayers[id] = 1
            }
        }
    }

    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        // 从组件表读取，不依赖 Flow（同 processDiscipleAging 修复模式，防止 Flow 缺失数据被 replaceAll 永久覆盖）
        val originalList = stateStore.discipleTables.assembleAll()
        val currentYear = stateStore.gameData.value.gameYear

        val griefUpdated = propagateGriefToRelatives(originalList, disciple, currentYear)
        unbindPartnerRelationship(griefUpdated, disciple)
        unbindMasterRelationships(griefUpdated, disciple)
        val tables = stateStore.discipleTables
        val lifeEventsToWrite = computeBereavementLifeEvents(griefUpdated, originalList, disciple, tables)

        // 收集要删除的装备/功法 ID（不论内外都是直接删除）
        val (deleteEquipIds, deleteManualIds) = collectDeleteIds(disciple)

        // D-03：死弟子的袋条目从 replaceAll 快照中剥离——replaceAll 全量重建组件表，
        // 若快照携带旧袋条目，会把物化后的清袋覆盖回旧值（重复死亡处理 → 重复物化 → 物品复制）
        val griefUpdatedWithoutBag = stripBagFromSnapshot(griefUpdated, disciple)

        // 单事务写入：弟子表 + 血炼清理 + 装备/功法清除 + 袋物化回仓库
        stateStore.update {
            val idInt = disciple.id.toInt()
            // D-03：袋物品物化回仓库（玩家保留，溢出自动转邮件）。
            // 事务内重读组件表袋状态——幂等：重复死亡处理时袋已空 → 不重复物化（防复制）
            val currentBag = discipleTables.storageBagItems.getOrNull(idInt) ?: emptyList()
            if (currentBag.isNotEmpty()) {
                inventorySystem.withTrackingSource("disciple_death") {
                    inventorySystem.materializeBagItemsToWarehouse(currentBag)
                }
            }
            // 幂等清袋：无条件执行（袋空无害）；在 replaceAll 之前执行（replaceAll 用剥离
            // 快照，不会把旧袋条目恢复，清袋持久）
            discipleTables.writeDeathRecords(idInt, currentYear, griefUpdatedWithoutBag, lifeEventsToWrite)
            gameData = gameData.copy(
                bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - disciple.id,
                bloodRefinements = gameData.bloodRefinements - disciple.id
            )
            equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
            manualInstances = manualInstances.filter { it.id !in deleteManualIds }
            recordGameEvent(
                GameEventCategory.SECT, GameEventType.DEATH,
                "${disciple.name}陨落（${if (isOutsideSect) "战斗" else "寿元耗尽"}）",
                disciple.id, disciple.name
            )
            gameData = gameData.copy(
                annualDeceasedDisciples = gameData.annualDeceasedDisciples + 1
            )
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

    /** D-17 收集死亡弟子的装备/功法实例 ID（handleDiscipleDeath 拆分） */
    private fun collectDeleteIds(disciple: Disciple): Pair<Set<String>, Set<String>> {
        val deleteEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        disciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        disciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        return deleteEquipIds to disciple.manualIds.toSet()
    }

    /** D-17 死弟子的袋条目从快照剥离（防重复物化复制） */
    private fun stripBagFromSnapshot(list: List<Disciple>, disciple: Disciple): List<Disciple> =
        if (disciple.equipment.storageBagItems.isEmpty()) list
        else list.map { d ->
            if (d.id == disciple.id) d.copy(equipment = d.equipment.copy(storageBagItems = emptyList()))
            else d
        }

    /** D-17 事务内写入弟子表死亡记录（handleDiscipleDeath 拆分）：清袋 + 全量重建 + 死亡年份 + 丧事事件 */
    private fun DiscipleTables.writeDeathRecords(
        idInt: Int,
        currentYear: Int,
        griefUpdatedWithoutBag: List<Disciple>,
        lifeEventsToWrite: Map<Int, String>
    ) {
        storageBagItems[idInt] = emptyList()
        replaceAll(griefUpdatedWithoutBag)
        deathYears[idInt] = currentYear
        lifeEventsToWrite.forEach { (grievingId, event) ->
            val prevEvents = lifeEvents.getOrDefault(grievingId, emptyList())
            lifeEvents[grievingId] = prevEvents + event
        }
    }

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
        stateStore.update { discipleTables.cullDeadDisciples(cullThreshold) }
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
                // 道德降低后即时触发偷盗判定（事务内版本）
                if (d.skills.morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
                    lawEnforcementProcessor.get().processSingleDiscipleTheft(id, this)
                }
            }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    fun clearDiscipleFromAllSlots(discipleId: String) {
        // 在 update 锁内完成清理，避免 TOCTOU（锁外读取 gameData 再用整块覆写会丢其他并发写入）
        stateStore.update {
            gameData = discipleSlotCleanup.clearAllSlots(gameData, discipleId, includeResidence = true)
        }

        // 双存储同步：清全部生产槽 Repository（DAO 操作不能放入 stateStore.update，异常时无法回滚内存已写入的清理）
        // 原实现仅清 forge 槽位，炼丹/灵田槽残留导致死亡弟子继续显示在生产界面（双槽分叉根因）。
        // 同步阻塞执行 DAO 写，消除 scope.launch 跨线程竞态
        kotlinx.coroutines.runBlocking(ioDispatcher.dispatcher) {
            productionCoordinator.clearDiscipleFromRepository(discipleId)
        }
    }

    fun returnEquipmentToWarehouse(equipmentId: String) {
        val currentInstances = stateStore.equipmentInstances.value
        val eq = currentInstances.find { it.id == equipmentId } ?: return
        stateStore.update {
            // 统一委托 returnEquipmentToStack（走 StackableItemStore 合并），
            // 消除手写"找第一个堆叠 + 追加"导致同种装备分裂为多个堆叠的问题
            val result = inventorySystem.returnEquipmentToStack(eq)
            // Failure(Full)：handleOverflowResult 已把物品转邮件，实例删除防复制（对齐
            // materializeBagItemsToWarehouse 语义）；其他失败保留实例（装备不丢失）
            val completed = result is DomainResult.Success || result is DomainResult.Partial ||
                (result is DomainResult.Failure && result.error is AppError.Domain.Inventory.Full)
            if (completed) {
                equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
            } else {
                // 对抗性审查修复：仓库满时保留装备实例（否则装备永久丢失）
                val error = (result as? DomainResult.Failure)?.error ?: "溢出"
                DomainLog.w(TAG, "归还装备 ${eq.name} 失败（仓库空间不足），保留装备实例: $error")
            }
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
