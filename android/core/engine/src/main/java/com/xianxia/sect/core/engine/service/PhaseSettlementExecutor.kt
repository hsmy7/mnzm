package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.state.MutableGameState

/** 修炼累积跳过门限（凡界最大修为约 2e7，≥1e8 视为异常满值直接跳过） */
private const val CULTIVATION_SKIP_THRESHOLD = 1e8

/**
 * PhaseSettlementExecutor — 每旬弟子结算纯编排器（计划 v2 阶段 2 / T2.1）。
 *
 * 从 [GameEngineCore.checkBreakthroughsAndPills] 原样提取的编排逻辑：
 * 生产 tick 与跨语言对拍测试共用同一入口（God Method 拆分 + 对拍基准双重需要）。
 *
 * 六步结算顺序（与 C++ `gamecore::system::runPhaseSettlement` 逐位对应）：
 * 1. HP/MP 恢复（列直读版）
 * 2. 修炼累积（≥[CULTIVATION_SKIP_THRESHOLD] 跳过）
 * 3. 功法熟练度增长（批量暂存）
 * 4. 装备孕养增长（批量暂存）
 * 5. 批量提交熟练度 + 单次重建装备列表 + realtime 投影单次发射
 * 6. 自动丹药补服 + 突破检测
 *
 * 行为契约：与提取前的 `checkBreakthroughsAndPills` 逐行等价，生产行为零变化。
 */
@GameService("PhaseSettlementExecutor")
internal class PhaseSettlementExecutor(
    private val cultivationService: CultivationService
) {

    /** 每旬共享映射视图（循环前构建一次，O(D×N) → O(D+N)，P0.1/P-1/P-2/P-4/P-6 语义保留） */
    private data class PhaseSettlementViewData(
        val equipmentMap: Map<String, EquipmentInstance>,
        val manualMap: Map<String, ManualInstance>,
        val residenceByDiscipleId: Map<Int, ResidenceSlot>,
        val buildingByInstanceId: Map<String, GridBuildingData>,
        val libraryDiscipleIds: Set<String>,
        val secretRealmMemberIds: Set<Int>,
    )

    /**
     * 执行一旬弟子结算（完整版六步：跨语言对拍/回归基准路径）。
     *
     * 六步顺序与提取前的 checkBreakthroughsAndPills 逐行等价：
     * 自动装备/学习 → 核心批次（1-5）→ 丹药补服 + 突破检测。
     *
     * 退役专项批 9-2 起生产 tick 不再调用本方法（原 OFF 旬结算路径删除，
     * 生产 AUTHORITATIVE 路径走 [executeResidual]）；完整版保留为
     * DiffPhaseSettlementTest / DiffAuthoritativeTickTest 的 Kotlin 基准。
     *
     * 必须在 [GameStateStore.update] 事务内调用（与原生产 tick 路径一致）。
     *
     * @param state 可变游戏状态（事务内就地修改）
     */
    fun execute(state: MutableGameState) {
        // 前置：仓库自动装备/学习（开关全关时纯早退）
        cultivationService.processAutoFromWarehouseRealtime(state)
        executeCultivationBatch(state)
        executePillsAndBreakthroughs(state)
    }

    /**
     * 残留结算（T2.4 AUTHORITATIVE 模式每旬调用）：自动装备/学习 + 自动丹药
     * 补服 + 突破检测——携带全部 Kotlin 副作用面（偷盗钩子/亲属赠送/埋点/
     * 引导计数），C++ 核心批次不含这些行为。
     *
     * RNG：BREAKTHROUGH 分区（突破）+ SYSTEM 分区（亲属赠送/偷盗钩子）；
     * AUTHORITATIVE 下经 [com.xianxia.sect.core.util.NativeBackedRng] 委托
     * native 单一真相源，序列逐位统一。
     */
    fun executeResidual(state: MutableGameState) {
        cultivationService.processAutoFromWarehouseRealtime(state)
        executePillsAndBreakthroughs(state)
    }

    /** 6a) 自动丹药补服；6b) 突破检测（唯一 RNG 消耗点：BREAKTHROUGH 分区） */
    private fun executePillsAndBreakthroughs(state: MutableGameState) {
        cultivationService.processAutoPillsRealtime(state)
        cultivationService.processBreakthroughs(state)
    }

    /**
     * 核心批次（步骤 1-5）：HP/MP 恢复 + 修炼累积 + 功法熟练度 + 装备孕养 +
     * 批量提交。零 RNG、纯状态——AUTHORITATIVE 模式下由 C++
     * runPhaseCoreBatch 接管（本方法仅 OFF/SHADOW/对拍路径使用）。
     */
    fun executeCultivationBatch(state: MutableGameState) {
        val views = buildSharedViews(state)
        val pendingProficiencies = mutableMapOf<String, List<ManualProficiencyData>?>()
        val pendingEquipmentUpdates = mutableMapOf<String, EquipmentInstance>()

        for (id in state.discipleTables.ids) {
            // 存活 + 非秘境成员才参与恢复/修炼（合并跳转条件，保持循环单跳转）
            if (state.discipleTables.isAlive[id] != 1 ||
                id in views.secretRealmMemberIds
            ) continue
            // 1) HP/MP 恢复（2026-08-01 列直读版：无 assemble，满血提前退出）
            cultivationService.recoverHpMpSingleColumn(
                state, id, phasesToSettle = 1,
                equipmentMap = views.equipmentMap, manualMap = views.manualMap,
                manualProficiencies = state.gameData.manualProficiencies
            )
            // 2) 修炼累积（列级快速跳过：cultivation >= 1e8 表示已满）
            if (state.discipleTables.cultivations.getOrDefault(id, 0.0) <
                CULTIVATION_SKIP_THRESHOLD
            ) {
                cultivationService.accumulateCultivationPerPhase(
                    id, state,
                    views.residenceByDiscipleId, views.buildingByInstanceId
                )
            }
            // 3) 功法熟练度增长（P-1 批量模式：只累积不写 state）
            cultivationService.processManualProficiencySingle(
                state, id, views.manualMap, pendingProficiencies,
                views.libraryDiscipleIds
            )
            // 4) 装备孕养增长（P-2 批量模式：只累积不重建 List）
            cultivationService.processEquipmentNurtureSingle(
                state, id, views.equipmentMap, pendingEquipmentUpdates
            )
        }

        // P-1：单次提交熟练度（O(D²) → O(D)）
        cultivationService.commitManualProficiencies(state, pendingProficiencies)
        // P-2：单次重建装备实例列表（O(D×E) → O(E)）
        cultivationService.applyEquipmentUpdates(state, pendingEquipmentUpdates)
    }

    /** 构建每旬共享映射（所有弟子复用，避免每弟子 O(N) 重建）。 */
    private fun buildSharedViews(state: MutableGameState): PhaseSettlementViewData = PhaseSettlementViewData(
        equipmentMap = state.equipmentInstances.associateBy { it.id },
        manualMap = state.manualInstances.associateBy { it.id },
        residenceByDiscipleId = buildResidenceIndex(state),
        buildingByInstanceId = state.gameData.placedBuildings.associateBy {
            it.instanceId
        },
        libraryDiscipleIds = state.gameData.librarySlots.mapTo(HashSet()) {
            it.discipleId
        },
        secretRealmMemberIds = state.gameData.secretRealmMemberIds(),
    )

    /**
     * P-4 住所索引：discipleId → 槽位。
     *
     * 与原内联实现语义一致——重复 discipleId 保留第一个匹配槽位；
     * 非数值 discipleId（如空串）永不匹配弟子 id，直接跳过。
     */
    private fun buildResidenceIndex(state: MutableGameState): HashMap<Int, ResidenceSlot> {
        val map = HashMap<Int, ResidenceSlot>()
        for (r in state.gameData.residenceSlots) {
            val rid = r.discipleId.toIntOrNull() ?: continue
            if (rid !in map) map[rid] = r
        }
        return map
    }
}
