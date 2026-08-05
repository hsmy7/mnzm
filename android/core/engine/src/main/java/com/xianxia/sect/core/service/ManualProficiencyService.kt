package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 功法熟练度服务。
 *
 * 职责：
 * - 计算单弟子功法熟练度增量
 * - 熟练度增量写入累积映射
 * - 熟练度增益应用（新建或更新）
 */
@Singleton
@GameService("ManualProficiencyService")
class ManualProficiencyService @Inject constructor() {

    /** 纯函数：计算单弟子功法熟练度增量 */
    fun computeProficiencyDelta(
        disciple: Disciple, gameData: GameData,
        manualMap: Map<String, ManualInstance>, phasesToSettle: Int,
        baseProficiencies: Map<String, List<ManualProficiencyData>>,
        resultMap: MutableMap<String, List<ManualProficiencyData>>
    ) {
        val inLibrary = gameData.librarySlots.any { it.discipleId == disciple.id }
        val libraryBonus = if (inLibrary)
            ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val profGainPerPhase =
            ManualProficiencySystem.calculateProficiencyGainPerPhase(
                disciple.skills.comprehension, libraryBonus
            )
        val totalProfGain = profGainPerPhase * phasesToSettle
        if (totalProfGain <= 0.0) return

        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val profList = baseProficiencies
            .getOrDefault(disciple.id, emptyList())
            .toMutableList()
        for (manualId in disciple.manualIds) {
            manualMap[manualId]?.let { manual ->
                applyProficiencyGain(profList, manualId, manual.name,
                    totalProfGain, maxProf)
            }
        }
        resultMap[disciple.id] = profList
    }

    /** 功法熟练度结算：单弟子熟练度增量写入累积映射。 */
    fun settleProficiencyInPlace(
        disciple: Disciple, gameData: GameData,
        manualMap: Map<String, ManualInstance>, phasesToSettle: Int,
        updatedProficiencies: MutableMap<String, List<ManualProficiencyData>>
    ) {
        if (disciple.manualIds.isEmpty()) return
        val inLibrary = gameData.librarySlots.any {
            it.discipleId == disciple.id
        }
        val libraryBonus = if (inLibrary)
            ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val profGainPerPhase =
            ManualProficiencySystem.calculateProficiencyGainPerPhase(
                disciple.skills.comprehension, libraryBonus
            )
        val totalProfGain = profGainPerPhase * phasesToSettle
        if (totalProfGain <= 0.0) return

        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val profList = updatedProficiencies
            .getOrDefault(disciple.id, emptyList())
            .toMutableList()
        for (manualId in disciple.manualIds) {
            manualMap[manualId]?.let { manual ->
                applyProficiencyGain(profList, manualId, manual.name,
                    totalProfGain, maxProf)
            }
        }
        updatedProficiencies[disciple.id] = profList
    }

    /**
     * 将熟练度增益应用到指定功法列表。
     * 如果功法已存在则更新熟练度值，否则新建条目。
     */
    private fun applyProficiencyGain(
        profList: MutableList<ManualProficiencyData>,
        manualId: String, manualName: String, gain: Double, maxProf: Int
    ) {
        val idx = profList.indexOfFirst { it.manualId == manualId }
        if (idx >= 0) {
            val cp = profList[idx]
            val newProf = (cp.proficiency + gain)
                .coerceAtMost(maxProf.toDouble())
            if (newProf != cp.proficiency) {
                profList[idx] = cp.copy(
                    proficiency = newProf,
                    masteryLevel = ManualProficiencySystem.MasteryLevel
                        .fromProficiency(newProf).level
                )
            }
        } else {
            val initProf = gain.coerceAtMost(maxProf.toDouble())
            profList.add(ManualProficiencyData(
                manualId = manualId, manualName = manualName,
                proficiency = initProf, maxProficiency = maxProf,
                masteryLevel = ManualProficiencySystem.MasteryLevel
                    .fromProficiency(initProf).level
            ))
        }
    }

    // ── 每旬列级直读版（D2 迁移自 CultivationCore，CultivationCore 保留委托） ──

    /**
     * 每旬功法熟练度增长（批量模式，D2 迁移自 CultivationCore）。
     *
     * 对所有存活且有功法装备的弟子，结算 1 旬的熟练度增长。
     * 全部使用列级直读（manualIds、comprehensions），不调用 assemble()。
     * 内部循环累积到 pending 后单次提交，将每旬 O(D²) 全量 Map 拷贝降为 O(D)。
     *
     * @param state 可变游戏状态
     */
    fun processManualProficiencyPerPhase(state: MutableGameState) {
        val tables = state.discipleTables
        val manualMap = state.manualInstances.associateBy { it.id }
        val libraryDiscipleIds = state.gameData.librarySlots
            .map { it.discipleId }.toSet()
        val pending = mutableMapOf<String, List<ManualProficiencyData>?>()
        for (id in tables.ids) {
            processManualProficiencySingle(state, id, manualMap, pending, libraryDiscipleIds)
        }
        commitManualProficiencies(state, pending)
    }

    /**
     * 单弟子每旬功法熟练度增长（D2 迁移自 CultivationCore）。
     *
     * 使用列级直读（manualIds、comprehensions），不调用 assemble()。
     * 批量模式：当 [pendingProficiencies] 非空时只做单弟子条目级计算并累积到
     * pending（O(P)），**不写 state**；调用方循环结束后统一调用
     * [commitManualProficiencies] 单次构建 Map + 单次 copy。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param manualInstanceMap 功法实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param pendingProficiencies 批量累积目标（null 时保持旧的单弟子直写行为）；
     *   值 null 表示该弟子条目应被移除（等价于单弟子版的 remove）
     * @param libraryDiscipleIds 藏经阁弟子 ID 预构建集合（消除每弟子 O(L) 扫描，
     *   null 时内部线性扫描）
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 卫语句密集的列级直读结算（迁自 CultivationCore 原逻辑）
    fun processManualProficiencySingle(
        state: MutableGameState, id: Int,
        manualInstanceMap: Map<String, ManualInstance>? = null,
        pendingProficiencies: MutableMap<String, List<ManualProficiencyData>?>? = null,
        libraryDiscipleIds: Set<String>? = null
    ) {
        val tables = state.discipleTables
        if (tables.isAlive[id] != 1) return
        val manualIds = tables.manualIds.getOrDefault(id, emptyList())
        if (manualIds.isEmpty()) return

        val manualMap = manualInstanceMap ?: state.manualInstances.associateBy { it.id }
        val data = state.gameData
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val discipleId = id.toString()
        val comprehension = tables.comprehensions.getOrDefault(id, 0)
        val inLibrary = libraryDiscipleIds?.contains(discipleId)
            ?: data.librarySlots.any { it.discipleId == discipleId }
        val libraryBonus = if (inLibrary)
            ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val profGain = ManualProficiencySystem.calculateProficiencyGainPerPhase(
            comprehension, libraryBonus
        )
        if (profGain <= 0.0) return

        // S10 修复（对抗性审查）：批量模式下从 pending 读累积视图。
        // 用 containsKey 区分"pending 显式 null（移除计划）"与"pending 无该条目
        // （首次处理）"——原实现两者都读旧 data 的残留条目，若同一批量周期内
        // 弟子被处理两次（或调用方预置 null=移除），残留条目会复活并叠加双倍增长。
        val hasPending = pendingProficiencies != null &&
            pendingProficiencies.containsKey(discipleId)
        val profList = if (hasPending) {
            (pendingProficiencies?.get(discipleId) ?: emptyList()).toMutableList()
        } else {
            data.manualProficiencies.getOrDefault(discipleId, emptyList()).toMutableList()
        }
        var changed = false

        for (manualId in manualIds) {
            manualMap[manualId]?.let { manual ->
                if (accumulateProficiencyForManual(profList, manualId, manual, profGain, maxProf)) {
                    changed = true
                }
            }
        }

        // ★ 清理已替换/遗忘功法的残留熟练度，防止僵尸条目累积
        val currentSet = manualIds.toSet()
        if (profList.removeAll { it.manualId !in currentSet }) changed = true

        if (changed) {
            if (pendingProficiencies != null) {
                // 批量模式：累积到 pending，不写 state（空列表=移除条目，与单弟子版 remove 等价）
                pendingProficiencies[discipleId] = profList.ifEmpty { null }
            } else {
                // 单弟子模式（兼容旧调用方与测试）：直接写 state
                val newProficiencies = data.manualProficiencies.toMutableMap()
                if (profList.isEmpty()) {
                    newProficiencies.remove(discipleId)
                } else {
                    newProficiencies[discipleId] = profList
                }
                state.gameData = data.copy(manualProficiencies = newProficiencies)
            }
        }
    }

    /**
     * 批量提交功法熟练度累积结果（D2 迁移自 CultivationCore）。
     *
     * 与单弟子模式逐弟子写等价：null/空列表条目移除，其余按 key 覆盖。
     * pending 为空时不做任何事（无变化则不触发 GameData.copy）。
     *
     * @param state 可变游戏状态
     * @param pending 由 [processManualProficiencySingle] 批量模式累积的变更
     */
    fun commitManualProficiencies(
        state: MutableGameState,
        pending: MutableMap<String, List<ManualProficiencyData>?>
    ) {
        if (pending.isEmpty()) return
        val data = state.gameData
        val merged = data.manualProficiencies.toMutableMap()
        for ((discipleId, list) in pending) {
            if (list == null) merged.remove(discipleId)
            else merged[discipleId] = list
        }
        state.gameData = data.copy(manualProficiencies = merged)
    }

    /** 单功法条目熟练度结算（processManualProficiencySingle 提取）；返回是否变化 */
    @Suppress("ReturnCount") // 单功法条目结算（更新/新增/无变化 三出口）
    private fun accumulateProficiencyForManual(
        profList: MutableList<ManualProficiencyData>,
        manualId: String,
        manual: ManualInstance,
        profGain: Double,
        maxProf: Int
    ): Boolean {
        val idx = profList.indexOfFirst { it.manualId == manualId }
        if (idx >= 0) {
            val cp = profList[idx]
            val newProf = (cp.proficiency + profGain)
                .coerceAtMost(maxProf.toDouble())
            if (newProf != cp.proficiency) {
                profList[idx] = cp.copy(
                    proficiency = newProf,
                    masteryLevel = ManualProficiencySystem.MasteryLevel
                        .fromProficiency(newProf).level
                )
                return true
            }
        } else {
            profList.add(ManualProficiencyData(
                manualId = manualId, manualName = manual.name,
                proficiency = profGain.coerceAtMost(maxProf.toDouble()),
                maxProficiency = maxProf,
                masteryLevel = ManualProficiencySystem.MasteryLevel
                    .fromProficiency(profGain).level
            ))
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "ManualProficiencyService"
    }
}
