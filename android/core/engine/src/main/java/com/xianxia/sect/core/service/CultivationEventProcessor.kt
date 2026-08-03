package com.xianxia.sect.core.engine.service
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
@GameService("CultivationEventProcessor")
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
    internal val equipmentManager: DiscipleEquipmentManager,
    internal val manualManager: DiscipleManualManager,
    internal val autoBuyService: AutoBuyService,
    internal val vassalService: VassalService,
    internal val disciplePurchaseService: DisciplePurchaseService,
    internal val aiSectBeastAttackProcessor: AISectBeastAttackProcessor,
    internal val lawEnforcementProcessor: LawEnforcementProcessor,
    internal val rngManager: GameRngManager,
    internal val secretRealmService: SecretRealmService,
    internal val deathHandler: DiscipleDeathHandler
) {
    private val scope get() = scopeProvider.scope
    companion object {
        internal const val TAG = "CultivationEventProc"

        /** 招募列表刷新间隔（年）— 与启动补刷路径（checkAndRepairMerchantAndRecruit）共用差值判据 */
        internal const val RECRUIT_REFRESH_INTERVAL_YEARS = 3
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
    private fun safelyRun(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.e(TAG, "Error in $name", e)
        }
    }
    // ── 自动从仓库装备/学习 ──────────────────────────────────────────
    /**
     * 实时轨专用：自动从仓库装备/学习。
     * 仅由 [CultivationTickSystem.onPhaseTick] 在 phasesToSettle==1 时调用。
     */
    fun processAutoFromWarehouseRealtime(state: MutableGameState) {
        val d = state.gameData
        processAutoFromWarehouse(d.gameYear, d.gameMonth, d.gamePhase, state)
    }
    private fun processAutoFromWarehouse(
        year: Int, month: Int, phase: Int, state: MutableGameState
    ) {
        val gameData = state.gameData
        val equipFocused = gameData.autoEquipFromWarehouseFocused
        val equipRootCounts = gameData.autoEquipFromWarehouseRootCounts
        val learnFocused = gameData.autoLearnFromWarehouseFocused
        val learnRootCounts = gameData.autoLearnFromWarehouseRootCounts
        val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
        val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()
        if (!hasAutoEquip && !hasAutoLearn) return
        val tables = state.discipleTables

        // Phase 1: 列级直读收集所有储物袋中的物品ID（无需 assemble）
        val bagEqIds = mutableSetOf<String>()
        val bagMnIds = mutableSetOf<String>()
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            for (item in tables.storageBagItems.getOrDefault(id, emptyList())) {
                when (item.itemType) {
                    "equipment_stack" -> bagEqIds.add(item.itemId)
                    "manual_stack" -> bagMnIds.add(item.itemId)
                }
            }
        }

        // Phase 2: 列级预过滤后只 assemble 储物袋有匹配物品的弟子
        // 远古秘境：探索中弟子不自动装备/学习（状态冻结语义，与修炼/服药跳过一致）
        val secretRealmMemberIds = gameData.secretRealmMemberIds()
        val updatedDisciples = tables.ids.filter { tables.isAlive[it] == 1 }
            .filter { id -> id !in secretRealmMemberIds }
            .filter { id ->
                val bags = tables.storageBagItems.getOrDefault(id, emptyList())
                bags.any { item ->
                    (item.itemType == "equipment_stack" && hasAutoEquip) ||
                    (item.itemType == "manual_stack" && hasAutoLearn)
                }
            }
            .mapNotNull { tables.assemble(it)?.takeIf { d -> d.isAlive } }
            .toMutableList()
        var eqStacks = state.equipmentStacks.all().filter { it.id !in bagEqIds }
        var mnStacks = state.manualStacks.all().filter { it.id !in bagMnIds }
        val eqInstancesById = state.equipmentInstances.associateById()
        val mnInstancesById = state.manualInstances.associateById()
        val newEqInstances = mutableListOf<EquipmentInstance>()
        val newMnInstances = mutableListOf<ManualInstance>()
        val sortedIndices = updatedDisciples.indices
            .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
                .thenBy { updatedDisciples[it].realm }
                .thenByDescending { updatedDisciples[it].realmLayer })
        for (idx in sortedIndices) {
            val disciple = updatedDisciples[idx]
            var d = disciple
            if (qualifiesForSectAutoPublic(d, equipFocused, equipRootCounts)) {
                val result = processSingleAutoEquip(d, year, month, phase, tables, eqStacks, eqInstancesById, newEqInstances)
                d = result.first
                eqStacks = result.second
            }
            if (qualifiesForSectAutoPublic(d, learnFocused, learnRootCounts)) {
                val result = processSingleAutoLearn(d, year, month, phase, tables, mnStacks, mnInstancesById, newMnInstances)
                d = result.first
                mnStacks = result.second
            }
            if (d !== disciple) {
                updatedDisciples[idx] = d
            }
        }
        writeAutoWarehouseResults(state, tables, updatedDisciples, bagEqIds, bagMnIds, eqStacks, mnStacks, newEqInstances, newMnInstances)
    }

    /**
     * 处理单个弟子的自动装备：调用 equipmentManager 后更新堆叠状态并记录日志。
     * @return (更新后的弟子, 更新后的装备堆叠列表)
     */
    private fun processSingleAutoEquip(
        d: Disciple, year: Int, month: Int, phase: Int, tables: DiscipleTables,
        eqStacks: List<EquipmentStack>, eqInstancesById: Map<String, EquipmentInstance>,
        newEqInstances: MutableList<EquipmentInstance>
    ): Pair<Disciple, List<EquipmentStack>> {
        val result = equipmentManager.processAutoEquipFromWarehouse(
            disciple = d, warehouseStacks = eqStacks, equipmentInstances = eqInstancesById,
            gameYear = year, gameMonth = month, gamePhase = phase,
            maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
        )
        if (result.newInstances.isEmpty()) return d to eqStacks
        var stacks = eqStacks
        newEqInstances.addAll(result.newInstances)
        val equipName = result.newInstances.firstOrNull()?.name ?: ""
        if (equipName.isNotEmpty()) {
            discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动装备了${equipName}")
        }
        for (update in result.stackUpdates) {
            stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
            else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
        }
        return result.disciple to stacks
    }

    /**
     * 处理单个弟子的自动学习功法：调用 manualManager 后更新堆叠状态并记录日志。
     * @return (更新后的弟子, 更新后的功法堆叠列表)
     */
    private fun processSingleAutoLearn(
        d: Disciple, year: Int, month: Int, phase: Int, tables: DiscipleTables,
        mnStacks: List<ManualStack>, mnInstancesById: Map<String, ManualInstance>,
        newMnInstances: MutableList<ManualInstance>
    ): Pair<Disciple, List<ManualStack>> {
        val result = manualManager.processAutoLearnFromWarehouse(
            disciple = d, warehouseStacks = mnStacks, manualInstances = mnInstancesById,
            gameYear = year, gameMonth = month, gamePhase = phase,
            maxStack = inventoryConfig.getMaxStackSize("manual_stack")
        )
        if (result.newInstance == null) return d to mnStacks
        var stacks = mnStacks
        newMnInstances.add(result.newInstance)
        val manualName = result.newInstance.name
        if (manualName.isNotEmpty()) {
            discipleService.addLifeEvent(d.id, "${tables.ages[d.id.toInt()]}岁：自动学习了${manualName}")
        }
        result.stackUpdate?.let { update ->
            stacks = if (update.isDeletion) stacks.filter { it.id != update.stackId }
            else stacks.map { if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it }
        }
        return result.disciple to stacks
    }

    /**
     * 精准字段写回：仅写回自动装备/学习实际修改的字段，不执行全量 clear()+insert()。
     */
    private fun writeAutoWarehouseResults(
        state: MutableGameState, tables: DiscipleTables,
        updatedDisciples: List<Disciple>, bagEqIds: Set<String>, bagMnIds: Set<String>,
        eqStacks: List<EquipmentStack>, mnStacks: List<ManualStack>,
        newEqInstances: List<EquipmentInstance>, newMnInstances: List<ManualInstance>
    ) {
        for (disciple in updatedDisciples) {
            val id = disciple.id.toInt()
            tables.storageBagItems[id] = disciple.equipment.storageBagItems
            tables.weaponIds[id] = disciple.equipment.weaponId
            tables.armorIds[id] = disciple.equipment.armorId
            tables.bootsIds[id] = disciple.equipment.bootsId
            tables.accessoryIds[id] = disciple.equipment.accessoryId

            // 清理被替换功法的残留熟练度
            val oldManualIds = tables.manualIds.getOrDefault(id, emptyList())
            tables.manualIds[id] = disciple.manualIds
            val removedIds = oldManualIds - disciple.manualIds.toSet()
            if (removedIds.isNotEmpty()) {
                val profMap = state.gameData.manualProficiencies.toMutableMap()
                profMap[disciple.id]?.let { list ->
                    val filtered = list.filter { it.manualId !in removedIds }
                    if (filtered.isEmpty()) profMap.remove(disciple.id)
                    else profMap[disciple.id] = filtered
                }
                state.gameData = state.gameData.copy(manualProficiencies = profMap)
            }
        }
        state.equipmentStacks.setItems(
            state.equipmentStacks.all().filter { it.id in bagEqIds } + eqStacks
        )
        state.manualStacks.setItems(
            state.manualStacks.all().filter { it.id in bagMnIds } + mnStacks
        )
        newEqInstances.forEach { state.equipmentInstances.add(it) }
        newMnInstances.forEach { state.manualInstances.add(it) }
    }
    // ── 战斗/探索辅助 ──────────────────────────────────────────────────
    internal fun MutableGameState.applyMissionRewards(rewards: List<MissionReward>) {
        for (reward in rewards) {
            // 发放物品（通过重入缓冲在同一事务内生效）
            reward.materials.forEach { material ->
                val r = inventorySystem.withTrackingSource("quest") { inventorySystem.addMaterial(material) }
                when (r) {
                    is DomainResult.Success -> {}
                    is DomainResult.Partial -> DomainLog.w(TAG, "${material.name} 溢出 ${r.overflow} 个")
                    is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${material.name} 失败: ${r.error}")
                }
            }
            inventorySystem.withTrackingSource("trial") {
                reward.pills.forEach { pill ->
                    val r = inventorySystem.addPill(pill)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${pill.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${pill.name} 失败: ${r.error}")
                    }
                }
                reward.equipmentStacks.forEach { equip ->
                    val r = inventorySystem.addEquipmentStack(equip)
                    when (r) {
                        is DomainResult.Success -> {}
                        is DomainResult.Partial -> DomainLog.w(TAG, "${equip.name} 溢出 ${r.overflow} 个")
                        is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${equip.name} 失败: ${r.error}")
                    }
                }
            }
            reward.manualStacks.forEach { manual ->
                val r = inventorySystem.addManualStack(manual)
                when (r) {
                    is DomainResult.Success -> {}
                    is DomainResult.Partial -> DomainLog.w(TAG, "${manual.name} 溢出 ${r.overflow} 个")
                    is DomainResult.Failure -> DomainLog.w(TAG, "添加 ${manual.name} 失败: ${r.error}")
                }
            }
            // 灵石
            if (reward.spiritStones > 0) {
                spiritStoneWallet.add(this, reward.spiritStones.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.Quest)
            }
            // 弟子状态
            for (did in reward.discipleIds) {
                val tid = did.toIntOrNull() ?: continue
                val dTables = discipleTables
                val tableIds = dTables.ids
                if (tid < 0 || tid >= tableIds.size || dTables.isAlive[tid] != 1) continue
                // ★ 修复：重置状态为 IDLE — processCompletedMissionsLazy 此前漏掉了状态重置，
                // 导致任务已从 activeMissions 移除但弟子永远卡在 ON_MISSION。
                // 随后 syncAllDiscipleStatuses() 看到 IDLE 状态后推导正确，不会触发 ON_MISSION 保护守卫。
                dTables.statuses[tid] = DiscipleStatus.IDLE
                if (did in reward.survivors) {
                    dTables.soulPowers[tid] = dTables.soulPowers.getOrDefault(tid, 0) + 1
                }
            }
        }
    }

    fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        val deadIds = battleMembers.filter { it.id !in survivorIds }.map { it.id }.toSet()
        val disciples = stateStore.disciples.value.toMutableList()
        var changed = false
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
                deathHandler.markAllDead(discipleTables, deadIds, stateStore.gameData.value.gameYear)
            }
        }
    }
    fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        val currentDisciplesList = stateStore.disciples.value.toMutableList()
        team.memberIds.forEach { memberId ->
            val index = currentDisciplesList.indexOfFirst { it.id == memberId }
            if (index >= 0) {
                val disciple = currentDisciplesList[index]
                val shouldKeepAlive = disciple.isAlive && survivorIds.contains(memberId)
                if (shouldKeepAlive) {
                    val hp = survivorHpMap[memberId] ?: disciple.combat.currentHp
                    val mp = survivorMpMap[memberId] ?: disciple.combat.currentMp
                    currentDisciplesList[index] = disciple.copy(status = DiscipleStatus.IDLE, combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                } else {
                    discipleLifecycleProcessor.handleDiscipleDeath(disciple, isOutsideSect = true)
                    currentDisciplesList[index] = disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                }
            }
        }
        stateStore.update {
            discipleTables.replaceAll(currentDisciplesList)
            // handleDiscipleDeath 已设置 deathYears 但被 replaceAll 清空，
            // 由 DiscipleDeathHandler 统一补写
            deathHandler.backfillDeathYears(
                discipleTables, currentDisciplesList, stateStore.gameData.value.gameYear
            )
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
