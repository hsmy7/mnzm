package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.config.GameConfigNativeBridge
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmAIProcessor
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
@GameService("CultivationEventProcessor")
@Suppress("LongParameterList") // 27 个领域服务依赖注入（月度事件编排中枢，分域聚合），detekt 上限 10
class CultivationEventProcessor @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val spiritStoneWallet: SpiritStoneWallet,
    internal val inventorySystem: InventorySystem,
    internal val inventoryConfig: InventoryConfig,
    internal val scopeProvider: CoroutineScopeProvider,
    internal val discipleService: DiscipleService,
    internal val cultivationCore: CultivationCore,
    internal val breakthroughHandler: DiscipleBreakthroughHandler,
    internal val cultivationSettlement: CultivationSettlement,
    internal val battleSystem: BattleSystem,
    internal val recruitService: RecruitService,
    internal val merchantAndRecruitService: MerchantAndRecruitService,
    internal val caveExplorationProcessor: javax.inject.Provider<CaveExplorationProcessor>,
    internal val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
    internal val diplomacyEventProcessor: DiplomacyEventProcessor,
    internal val diplomacyService: DiplomacyService,
    internal val equipmentManager: DiscipleEquipmentManager,
    internal val manualManager: DiscipleManualManager,
    internal val autoBuyService: AutoBuyService,
    internal val vassalService: VassalService,
    internal val disciplePurchaseService: DisciplePurchaseService,
    internal val aiSectBeastAttackProcessor: AISectBeastAttackProcessor,
    internal val lawEnforcementProcessor: LawEnforcementProcessor,
    internal val rngManager: GameRngManager,
    internal val secretRealmService: SecretRealmService,
    internal val secretRealmAIProcessor: SecretRealmAIProcessor,
    internal val deathHandler: DiscipleDeathHandler,
    internal val gameConfigProvider: GameConfigProvider
) {
    init {
        // 运行时配置注入 C++（注册 Provider + native
        // 已加载则立即注入；未加载时由 ensureAuthoritativeNative 补注——双点幂等）
        GameConfigNativeBridge.register(gameConfigProvider)
    }

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
        internal const val TAG = "CultivationEventProc"

        /** 招募列表刷新间隔（年）— 与启动补刷路径（checkAndRepairMerchantAndRecruit）共用差值判据 */
        internal const val RECRUIT_REFRESH_INTERVAL_YEARS = 3

        /** AI 宗门弟子周期性招募间隔（年）— 差值判据，老档相位漂移自愈/失败次年重试 */
        internal const val AI_SECT_RECRUIT_INTERVAL_YEARS = 3

        /** L3a 年变延迟队列 drain 时间预算（ms）：逐 tick 分摊延迟组的最大耗时 */
        internal const val YEARLY_OPS_DRAIN_BUDGET_MS = 30L
    }

    // ── L3a 年变延迟队列（延迟组操作由 tick 预算 drain 分摊执行）─────────
    // 注意：不能 private —— CultivationEventMonthlyOps 的 extension 函数
    // 经 internal 可见性访问同一实例
    internal val yearlyOpsQueue = YearlyOpsQueue()

    /**
     * 逐 tick 预算 drain 年变延迟队列（L3a）。
     *
     * 由 [GameEngineCore.tickInternal] 每 tick 调用：
     * - 1 月（年变月）：按 [YEARLY_OPS_DRAIN_BUDGET_MS] 预算分摊，剩余留到下个 tick
     * - 非 1 月：不允许延迟组跨月残留，无预算全量清空（兜底）
     *
     * 每个 op 独立事务（[stateStore.update]），FIFO 保序 = 年变原相对序。
     */
    fun drainYearlyOpsQueue(timeBudgetMs: Long = YEARLY_OPS_DRAIN_BUDGET_MS) {
        val month = stateStore.gameData.value.gameMonth
        val runOp: (MutableGameState.() -> Unit) -> Unit = { op -> stateStore.update { op() } }
        val cleared = if (month == 1) {
            yearlyOpsQueue.drain(timeBudgetMs, runOp)
        } else {
            yearlyOpsQueue.forceDrain(runOp)
        }
        if (!cleared) {
            DomainLog.w(TAG, "yearly ops queue not drained in budget: ${yearlyOpsQueue.size} ops pending")
        }
    }

    /** 存档前全量清空年变延迟队列（保证"快照 ⇒ 队列已空"不变量）。 */
    fun flushYearlyOpsQueue() {
        // 与在途年变 T1 串行（闭合"快照竞态窗口"）：T1 事务内最后一步
        // enqueueYearlyOps 入队（见 CultivationEventMonthlyOps.processYearlyEvents），
        // 此处空事务拿 transactionLock 确保"T1 提交（含全部 T2 入队）已完成"，
        // 再 forceDrain 全清——闭合"快照 ⇒ 队列已空"不变量（forceDrain 与
        // 引擎线程 drain 的 FIFO 互斥见 YearlyOpsQueue.inFlight）
        stateStore.update { }
        yearlyOpsQueue.forceDrain { op -> stateStore.update { op() } }
    }

    /** 丢弃队列中所有未执行延迟组（读档/切档入口调用，防旧档残留污染新档）。 */
    fun clearYearlyOpsQueue() {
        yearlyOpsQueue.clear()
    }
    // ── 时间推进 ──────────────────────────────────────────────────────
    fun advanceMonth(state: MutableGameState? = null) {
        val data = state?.gameData ?: stateStore.gameData.value
        var newMonth = data.gameMonth + 1
        var newYear = data.gameYear
        if (newMonth > 12) {
            newMonth = 1
            newYear++
        }
        val isYearChanged = newYear > data.gameYear
        val updatedData = data.copy(
            gameMonth = newMonth,
            gameYear = newYear,
            gamePhase = 0
        )
        if (state != null) {
            state.gameData = updatedData
            // 新月份开始时重置招募月度计数，使年变/月变中的招募共享同一月配额
            state.gameData = state.gameData.copy(recruitCountThisMonth = 0)
        } else {
            stateStore.update {
                gameData = updatedData.copy(recruitCountThisMonth = 0)
            }
        }
        if (isYearChanged) {
            processYearlyEvents(newYear)
        }
        processMonthlyEvents(newYear, newMonth)
    }
    fun advanceYear(state: MutableGameState? = null) {
        val data = state?.gameData ?: stateStore.gameData.value
        val newYear = data.gameYear + 1
        val updatedData = data.copy(
            gameYear = newYear,
            gameMonth = 1,
            gamePhase = 0
        )
        val resetData = updatedData.copy(recruitCountThisMonth = 0)
        if (state != null) state.gameData = resetData else stateStore.update { gameData = resetData }
        processYearlyEvents(newYear)
        processMonthlyEvents(newYear, 1)
    }

    /**
     * 清理被替换功法的残留熟练度——
     * removedIds 为空零成本早退（不构建 profMap、不 copy state）。
     */
    internal fun clearRemovedManualProficiencies(
        state: MutableGameState,
        disciple: Disciple,
        removedIds: Set<String>
    ) {
        if (removedIds.isEmpty()) return
        val profMap = state.gameData.manualProficiencies.toMutableMap()
        profMap[disciple.id]?.let { list ->
            val filtered = list.filter { it.manualId !in removedIds }
            if (filtered.isEmpty()) profMap.remove(disciple.id)
            else profMap[disciple.id] = filtered
        }
        state.gameData = state.gameData.copy(manualProficiencies = profMap)
    }
    // ── 战斗/探索辅助 ──────────────────────────────────────────────────
    internal fun MutableGameState.applyMissionRewards(rewards: List<MissionReward>) {
        for (reward in rewards) {
            // 发放物品（通过重入缓冲在同一事务内生效）
            reward.materials.forEach { material ->
                logGrantOutcome(material.name, inventorySystem.withTrackingSource("quest") {
                    inventorySystem.addMaterial(material)
                })
            }
            inventorySystem.withTrackingSource("trial") {
                reward.pills.forEach { pill ->
                    logGrantOutcome(pill.name, inventorySystem.addPill(pill))
                }
                reward.equipmentStacks.forEach { equip ->
                    logGrantOutcome(equip.name, inventorySystem.addEquipmentStack(equip))
                }
            }
            reward.manualStacks.forEach { manual ->
                logGrantOutcome(manual.name, inventorySystem.addManualStack(manual))
            }
            // 灵石
            if (reward.spiritStones > 0) {
                spiritStoneWallet.add(this, reward.spiritStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.Quest)
            }
            // 弟子状态
            applyMissionDiscipleState(reward)
        }
    }

    /** 发放结果日志：Success 静默，Partial/Failure 降级告警 */
    private fun <T> logGrantOutcome(itemName: String, result: DomainResult<T>) {
        when (result) {
            is DomainResult.Success -> {}
            is DomainResult.Partial -> DomainLog.w(TAG, "$itemName 溢出 ${result.overflow} 个")
            is DomainResult.Failure -> DomainLog.w(TAG, "添加 $itemName 失败: ${result.error}")
        }
    }

    /** 弟子状态段：状态重置 IDLE + 幸存者神魂 +1 */
    internal fun MutableGameState.applyMissionDiscipleState(reward: MissionReward) {
        for (did in reward.discipleIds) {
            val dTables = discipleTables
            val tableIds = dTables.ids
            // S5 顺手修复（对齐 C++ rowOf 语义）：原守卫 `tid >= tableIds.size`
            // 假定 0-based 稠密 id——id 从 1 起（DiscipleTables.insert 生成
            // max+1）时恒排除 id==size 的弟子（任务完成无魂力/状态重置）。
            // 存在性改 id contains 探测。
            val tid = did.toIntOrNull()
            if (tid == null || tid !in tableIds || dTables.isAlive[tid] != 1) continue
            // 重置状态为 IDLE — 任务完成必须显式重置
            // （否则任务已从 activeMissions 移除但弟子永远卡在 ON_MISSION）；
            // 随后 syncAllDiscipleStatuses() 看到 IDLE 状态后推导正确，不会触发 ON_MISSION 保护守卫。
            dTables.statuses[tid] = DiscipleStatus.IDLE
            if (did in reward.survivors) {
                dTables.soulPowers[tid] = dTables.soulPowers.getOrDefault(tid, 0) + 1
            }
        }
    }

    /**
     * 空闲期间焦点弟子轻量 HFD 累积。
     *
     * 仅更新焦点弟子一人的修炼值/功法熟练度/装备孕养，
     *
     * @param focusedId 焦点弟子 ID
     * @param state 可变游戏状态
     */
    fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        /** 本场永久死亡弟子 ID（调用方事务外触发哀伤） */
        val deadIds = battleMembers.filter { it.id !in survivorIds }.map { it.id }.toSet()
        val disciples = stateStore.disciples.value.toMutableList()
        // 全灭场景（survivorIds 为空）：无幸存者更新但仍需标记死亡，deadIds 非空即触发事务
        var changed = deadIds.isNotEmpty()
        team@ for (member in battleMembers) {
            val discipleIndex = disciples.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0 || member.id !in survivorIds) continue@team
            val disciple = disciples[discipleIndex]
            val hp = member.hp.coerceAtMost(member.maxHp)
            val mp = member.mp.coerceAtMost(member.maxMp)
            disciples[discipleIndex] = disciple.copy(combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
            changed = true
        }
        if (changed) {
            stateStore.update {
                discipleTables.replaceAll(disciples)
                // 死亡标记 + deathYears 统一由 DiscipleDeathHandler 写入列
                deathHandler.markAllDead(this, deadIds, stateStore.gameData.value.gameYear)
            }
        }
    }
    // ── 游戏结束 ──────────────────────────────────────────────────────
    fun checkGameOverCondition() {
        stateStore.update { checkGameOverCondition(this) }
    }
    fun checkGameOverCondition(state: MutableGameState) {
        val currentData = state.gameData
        if (currentData.isGameOver) return
        val playerSect = currentData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id
        val playerControlsAnySect = currentData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }
        if (!playerControlsAnySect) {
            state.gameData = state.gameData.copy(isGameOver = true)
        }
    }
    // ── 辅助方法 ──────────────────────────────────────────────────────
    fun clearDiscipleFromAllSlots(discipleId: String) {
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(discipleId)
    }
    fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        discipleLifecycleProcessor.handleDiscipleDeath(disciple, isOutsideSect)
    }
    fun returnEquipmentToWarehouse(equipmentId: String) {
        discipleLifecycleProcessor.returnEquipmentToWarehouse(equipmentId)
    }
    fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        discipleLifecycleProcessor.removeEquipmentFromDisciple(discipleId, equipmentId)
    }
    fun qualifiesForSectAutoPublic(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && disciple.statusData["followed"] == "true") return true
            val rootCount = disciple.spiritRootType.split(",").size
            return rootCount in rootCounts
        }
        return false
    }
}
