package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.eraseDiscipleDerivedMaps
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
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
import com.xianxia.sect.core.engine.domain.disciple.computeGriefEndYearMap
import com.xianxia.sect.core.engine.system.materializeBagItemsToWarehouse
import com.xianxia.sect.core.engine.system.returnEquipmentToStack







@Singleton
@GameService("DiscipleLifecycleProcessor")
@Suppress("LongParameterList") // 10 个领域服务依赖注入（老化/死亡编排中枢，detekt 上限 10）
class DiscipleLifecycleProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val productionCoordinator: ProductionCoordinator,
    private val eventBus: EventBusPort,
    private val discipleSlotCleanup: DiscipleSlotCleanup,
    private val discipleStatusService: DiscipleStatusService,
    private val ioDispatcher: IoDispatcher,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
    private val deathHandler: DiscipleDeathHandler,
) {
    /** 后台协程作用域 — 使用 [DeviceCapabilityProfiler.backgroundDispatcher] */
    private val scope get() = scopeProvider.scope

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        private const val TAG = "DiscipleLifecycle"
        private const val CULL_DEAD_AFTER_YEARS = 1
        private const val REFLECTION_RELEASE_MORALITY_BONUS = 5
    }

    // ── 弟子老化/死亡 ──────────────────────────────────────────────────

    fun processGriefExpiry(currentYear: Int) {
        stateStore.update {
            // 列直写：替代 assembleAll + map + replaceAll 全表重建。
            // 哨兵 -1 表示无哀悼（assembleAll 时映射回 null，读取端等价）
            val expiredIds = discipleTables.ids.filter { id ->
                val griefEnd = discipleTables.griefEndYears.getOrDefault(id, DiscipleTables.GRIEF_YEAR_NULL_SENTINEL)
                griefEnd != DiscipleTables.GRIEF_YEAR_NULL_SENTINEL && currentYear >= griefEnd
            }
            expiredIds.forEach { discipleTables.griefEndYears[it] = DiscipleTables.GRIEF_YEAR_NULL_SENTINEL }
        }
    }

    @Suppress("UnusedParameter") // currentYear: 语义时点形参：标注年变触发编排的可读契约，函数体当前不消费
    fun processDiscipleAging(currentYear: Int) {
        discipleStatusService.syncAllDiscipleStatuses()
    }

    /**
     * G07 玩家侧战斗败北 → **重伤**：只把气血钳到
     * [com.xianxia.sect.core.GameConfig.Disciple.INJURED_HP] 且保持存活；
     * 不清槽、不解绑、不清装、不物化行囊、不计年报死亡、不广播死亡事件
     * （UI 由「存活且气血=1」派生「重伤」）。回血走既有每旬回血机制。
     */
    private fun applyCombatInjury(disciple: Disciple) {
        val idInt = disciple.id.toIntOrNull() ?: return
        stateStore.update {
            deathHandler.markDead(this, idInt, gameData.gameYear)
        }
    }

    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        // G07 玩家侧战斗败北 → 重伤：只写 HP=1 且保持存活，不触发任何死亡副作用
        if (isOutsideSect) {
            applyCombatInjury(disciple)
            return
        }

        // 非战斗路径完整死亡链（旧档/存量路径）
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
            val id = disciple.id.toInt()
            // 袋物品物化回仓库（玩家保留，溢出自动转邮件）。
            // 事务内重读组件表袋状态——幂等：重复死亡处理时袋已空 → 不重复物化（防复制）
            val currentBag = discipleTables.storageBagItems.getOrNull(id) ?: emptyList()
            if (currentBag.isNotEmpty()) {
                inventorySystem.withTrackingSource("disciple_death") {
                    inventorySystem.materializeBagItemsToWarehouse(currentBag)
                }
            }
            // 幂等清袋：无条件执行（袋空无害）
            discipleTables.storageBagItems[id] = emptyList()

            /** 丧亲事件草稿（lifeEvents 瞬态列写入） */
            // 列直写：哀悼批量写 + 解绑 + 丧亲事件 + 死亡年份
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
            // 非战斗死亡写入死亡三元组（存活标记 / DEAD 状态 / 死亡年份——同步原子，
            // 派生推导（deriveDiscipleStatus）与列投影在下一次 sync 前即可见死）
            discipleTables.markDead(id, currentYear)
            discipleTables.isAlive[id] = 0
            discipleTables.statuses[id] = DiscipleStatus.DEAD
            discipleTables.deathYears[id] = currentYear
            gameData = gameData.copy(
                annualDeceasedDisciples = gameData.annualDeceasedDisciples + 1
            )

            // 审计 P2-7/P3-4：统一收口（原漏 PctTotals 与 manualProficiencies）
            eraseDiscipleDerivedMaps(disciple.id)
            equipmentInstances = equipmentInstances.filter { it.id !in deleteEquipIds }
            manualInstances = manualInstances.filter { it.id !in deleteManualIds }
            recordGameEvent(
                GameEventCategory.SECT, GameEventType.DEATH,
                "${disciple.name}陨落",
                disciple.id, disciple.name
            )
        }

        eventBus.emitSync(DeathEvent(
            discipleId = disciple.id,
            discipleName = disciple.name,
            cause = "unknown",
            deathYear = currentYear
        ))
    }

    // ── 以下为 handleDiscipleDeath 的拆分子函数 ────────────────────────────

    /** 收集死亡弟子的装备/功法实例 ID */
    private fun collectDeleteIds(disciple: Disciple): Pair<Set<String>, Set<String>> {
        val deleteEquipIds = mutableSetOf<String>()
        disciple.equipment.weaponId?.let { deleteEquipIds.add(it) }
        disciple.equipment.armorId?.let { deleteEquipIds.add(it) }
        disciple.equipment.bootsId?.let { deleteEquipIds.add(it) }
        disciple.equipment.accessoryId?.let { deleteEquipIds.add(it) }
        return deleteEquipIds to disciple.manualIds.toSet()
    }

    // ── 列直写辅助 ──

    /** 丧亲事件记录：关系文本（列直读判定结果） */
    private data class BereavementRecord(val relationship: String)

    /**
     * 列直读判定丧亲事件（O(D) 列访问）：
     * 仅对 [griefMap] 中"新进入哀悼"者生成（列值为哨兵 -1 判定 wasGrieving），
     * 关系文本按列直读（partnerIds/parentId1s/parentId2s）
     * （第 4 分支"子女"因 `==` 对称不可达，实际输出"亲属"）。
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
            records[grievingId] = BereavementRecord(relationship)
        }
        return records
    }

    private fun buildBereavementEvent(record: BereavementRecord, deceased: Disciple): String =
        "因${record.relationship}${deceased.name}离世陷入悲痛，修炼速度降低50%"

    /** 道侣解绑（列直写）：仅清空死者侧记录指向的伴侣行 */
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

    fun processYearlyAging(currentYear: Int) {
        val cullThreshold = currentYear - CULL_DEAD_AFTER_YEARS
        stateStore.update { discipleTables.cullDeadDisciples(cullThreshold) }
    }

    /**
     * 年变死亡链：事务外平台效应——Room 生产槽 Repository
     * 清理（DAO 主源同步，防读档重建残留死亡弟子）+ DeathEvent 事件分发。
     * C++ 侧已完成状态面（11 槽镜像/哀悼/解绑/血炼/装备清/死亡记录/事件/计数），
     * 本方法仅补 Kotlin 平台效应（与 [processDiscipleAging] 的事务外段语义一致——
     * DAO 批量清理毫秒级、DeathEvent 无消费方，实害为零）。
     *
     * @param deaths 死亡弟子草稿（nativeSettleYear 信封回传）
     */
    internal fun applyAgedDeathPlatformEffects(
        deaths: List<com.xianxia.sect.core.engine.AgedDeathDraft>
    ) {
        if (deaths.isEmpty()) return
        // 双存储同步：清 Room 生产槽 Repository（镜像清理已在 C++ 事务内完成）
        kotlinx.coroutines.runBlocking(ioDispatcher.dispatcher) {
            productionCoordinator.clearDisciplesFromRepository(
                deaths.map { it.discipleId }
            )
        }
        for (d in deaths) {
            eventBus.emitSync(DeathEvent(
                discipleId = d.discipleId,
                discipleName = d.name,
                cause = d.cause,
                deathYear = d.deathYear
            ))
        }
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
                        morality = (disciple.skills.morality + REFLECTION_RELEASE_MORALITY_BONUS)
                            .coerceAtMost(GameConfig.Disciple.SKILL_MAX)
                    )
                )
            }
            // 字段级更新而非 clear+insert：GameStateStoreImpl 事务中
            // _discipleTables 引用不变，clear+insert 后 !（引用不等）检测不触发，
            // mutationVersion 虽变化但 batchEmission/reentrantBuffer 路径可能跳过。
            // 直接写字段确保值落盘且 mutationVersion 正确递增。
            for (d in updatedDisciples) {
                val id = d.id.toIntOrNull() ?: continue
                discipleTables.statuses[id] = d.status
                discipleTables.statusData[id] = d.statusData
                discipleTables.moralities[id] = d.skills.morality
            }
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    fun clearDiscipleFromAllSlots(discipleId: String) {
        // 在 update 锁内完成清理，避免 TOCTOU（锁外读取 gameData 再用整块覆写会丢其他并发写入）
        // state 级：Gate + GameData 槽位一次清完（世界地图探索队已下线移除）
        stateStore.update {
            discipleSlotCleanup.clearAllSlotsState(this, discipleId, includeResidence = true)
        }

        // 双存储同步：清全部生产槽 Repository（DAO 操作不能放入 stateStore.update，异常时无法回滚内存已写入的清理）
        // 必须清全部建筑槽位：只清单一槽位会让炼丹/灵田槽残留，
        // 死亡弟子继续显示在生产界面（双槽分叉根因）。
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
                // 仓库满时保留装备实例（否则装备永久丢失）
                val error = (result as? DomainResult.Failure)?.error ?: "溢出"
                DomainLog.w(TAG, "归还装备 ${eq.name} 失败（仓库空间不足），保留装备实例: $error")
            }
        }
    }

    @Suppress("UnusedParameter") // discipleId: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
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
