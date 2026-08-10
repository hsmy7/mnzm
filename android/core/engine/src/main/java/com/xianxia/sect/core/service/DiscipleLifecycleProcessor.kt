package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.state.DeathRecord
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.domain.disciple.computeMaxAge
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
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.util.AppError
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
@GameService("DiscipleLifecycleProcessor")
@Suppress("LongParameterList") // 10 个领域服务依赖注入（老化/死亡编排中枢，detekt 上限 10），2026-08-10 加入 deathHandler
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
    private val deathHandler: DiscipleDeathHandler,
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
            // 列直写（L1b）：替代 assembleAll + map + replaceAll 全表重建。
            // 哨兵 -1 表示无哀悼（assembleAll 时映射回 null，读取端等价）
            val expiredIds = discipleTables.ids.filter { id ->
                val griefEnd = discipleTables.griefEndYears.getOrDefault(id, DiscipleTables.GRIEF_YEAR_NULL_SENTINEL)
                griefEnd != DiscipleTables.GRIEF_YEAR_NULL_SENTINEL && currentYear >= griefEnd
            }
            expiredIds.forEach { discipleTables.griefEndYears[it] = DiscipleTables.GRIEF_YEAR_NULL_SENTINEL }
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

        // ── 双存储同步清理 + 事件分发（非状态事务；注意：本方法在年变 T1
        //（processYearlyEvents 外层 update）内被调用，此处实际仍持 transactionLock，
        // 仅在 T1 外层的"内层事务"之外——DAO 批量清理毫秒级，DeathEvent 无消费方，
        // 实害为零；非 1 月路径（单独调用）则为真事务外）──
        // 镜像清理已在 applyAgedDeath 事务内完成（幂等）；此处仅清 Room 生产槽 Repository
        //（原 clearForgeSlotsIfNeeded 只清锻造槽，炼丹/灵田槽残留导致死亡弟子继续显示在
        // 生产界面——双槽分叉根因）。
        // L3 批处理：N 次 runBlocking + N×M 次 dao.update → 1 次 runBlocking + 1 次
        // dao.updateAll。保留同步语义（TOCTOU/竞态为刻意设计——必须立即生效，注释见
        // clearDiscipleFromAllSlots）。
        kotlinx.coroutines.runBlocking(ioDispatcher.dispatcher) {
            productionCoordinator.clearDisciplesFromRepository(
                deadDiscipleData.keys.map { it.toString() }
            )
        }
        for ((id, agedDisciple) in deadDiscipleData) {
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
        // state 级：Gate + GameData 槽位 + 世界地图探索队（teams）一次清完
        discipleSlotCleanup.clearAllSlotsState(this, agedDisciple.id, includeResidence = true)

        // ── 哀悼期批量写（L1b 列直写：替代 propagateGriefToRelatives 全列表 map + replaceAll 全表重建）──
        // computeBereavementRecords 必须在写列之前列读（事件判定需要传播前的 griefEndYears 列值）
        val griefMap = DiscipleStatCalculator.computeGriefEndYearMap(
            currentList, listOf(agedDisciple), currentYear
        )
        val bereavements = computeBereavementRecords(griefMap, agedDisciple)
        for ((grievingId, endYear) in griefMap) {
            discipleTables.griefEndYears[grievingId] = endYear
        }

        // ── 伴侣/师徒解绑（列直写，替代 griefUpdated 列表改动）──
        unbindPartnerColumns(agedDisciple)
        unbindMasterColumns(agedDisciple.id)

        // ── 丧亲生命事件（列直读判定，替代 originalList.find O(D)/人）──
        bereavements.forEach { (grievingId, record) ->
            val event = buildBereavementEvent(record, agedDisciple)
            discipleTables.lifeEvents[grievingId] =
                discipleTables.lifeEvents.getOrDefault(grievingId, emptyList()) + event
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

        val griefMap = DiscipleStatCalculator.computeGriefEndYearMap(
            originalList, listOf(disciple), currentYear
        )

        // 收集要删除的装备/功法 ID（不论内外都是直接删除）
        val (deleteEquipIds, deleteManualIds) = collectDeleteIds(disciple)

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
            // 幂等清袋：无条件执行（袋空无害）
            discipleTables.storageBagItems[idInt] = emptyList()

            // L1b 列直写：哀悼批量写 + 解绑 + 丧亲事件 + 死亡年份
            //（替代 propagateGriefToRelatives + stripBagFromSnapshot + writeDeathRecords 的
            //  全列表 map + replaceAll 全表重建；computeBereavementRecords 须在写列前列读）
            val bereavements = computeBereavementRecords(griefMap, disciple)
            for ((grievingId, endYear) in griefMap) {
                discipleTables.griefEndYears[grievingId] = endYear
            }
            unbindPartnerColumns(disciple)
            unbindMasterColumns(disciple.id)
            bereavements.forEach { (grievingId, record) ->
                val event = buildBereavementEvent(record, disciple)
                discipleTables.lifeEvents[grievingId] =
                    discipleTables.lifeEvents.getOrDefault(grievingId, emptyList()) + event
            }
            // 2026-08-10 统一死亡入口：markDead 写 isAlive=0 + status=DEAD + deathYear
            //（原实现只写 deathYears，isAlive/status 依赖调用方补偿——洞窟/战斗事件
            //  两个调用点已补偿标记，本改动为统一入口防御，防止未来调用点遗漏；
            //  对已补偿路径幂等无害）
            deathHandler.markDead(discipleTables, idInt, currentYear)

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

    // ── L1b 列直写辅助（替代 propagateGriefToRelatives/解绑/事件判定的全列表 map + replaceAll）──

    /** 丧亲事件记录（L1b）：关系文本 + 事件年龄（列直读判定结果） */
    private data class BereavementRecord(val relationship: String, val age: Int)

    /**
     * 列直读判定丧亲事件（替代 originalList.find O(D)/人）：
     * 仅对 [griefMap] 中"新进入哀悼"者生成（原列值为哨兵 -1 判定 wasGrieving），
     * 关系文本按列直读（partnerIds/parentId1s/parentId2s），与旧版
     * `deduceRelationship` 逐分支等价（第 4 分支"子女"因 `==` 对称不可达，实际输出"亲属"）。
     * 必须在写 griefEndYears 列之前调用（需要传播前列值）。
     */
    private fun MutableGameState.computeBereavementRecords(
        griefMap: Map<Int, Int>,
        deceased: Disciple
    ): Map<Int, BereavementRecord> {
        val records = mutableMapOf<Int, BereavementRecord>()
        val deadId = deceased.id
        for ((grievingId, _) in griefMap) {
            val sentinel = DiscipleTables.GRIEF_YEAR_NULL_SENTINEL
            val wasGrieving =
                discipleTables.griefEndYears.getOrDefault(grievingId, sentinel) != sentinel
            if (wasGrieving) continue
            val relationship = when {
                discipleTables.partnerIds.getOrNull(grievingId) == deadId -> "道侣"
                discipleTables.parentId1s.getOrNull(grievingId) == deadId -> "父/母"
                discipleTables.parentId2s.getOrNull(grievingId) == deadId -> "父/母"
                else -> "亲属"
            }
            records[grievingId] = BereavementRecord(relationship, discipleTables.ages[grievingId])
        }
        return records
    }

    private fun buildBereavementEvent(record: BereavementRecord, deceased: Disciple): String =
        "${record.age}岁：因${record.relationship}${deceased.name}离世陷入悲痛，修炼速度降低50%"

    /** 道侣解绑（列直写）：仅清空死者侧记录指向的伴侣行，与旧版 indexOfFirst 语义一致 */
    private fun MutableGameState.unbindPartnerColumns(deceased: Disciple) {
        val partnerInt = deceased.social.partnerId?.toIntOrNull() ?: return
        if (discipleTables.partnerIds.getOrNull(partnerInt) != null) {
            discipleTables.partnerIds[partnerInt] = null
        }
    }

    /** 师徒解绑（列直写）：扫描 masterIds 列清空指向死者的徒弟行（O(D) 列读，替代列表遍历） */
    private fun MutableGameState.unbindMasterColumns(deadId: String) {
        for (discipleId in discipleTables.ids) {
            if (discipleTables.masterIds.getOrNull(discipleId) == deadId) {
                discipleTables.masterIds[discipleId] = null
            }
        }
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
        // state 级：Gate + GameData 槽位 + 世界地图探索队（teams）一次清完
        stateStore.update {
            discipleSlotCleanup.clearAllSlotsState(this, discipleId, includeResidence = true)
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
