package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.state.MutableGameState

// ── CultivationCore 委托域（自 CultivationService 拆出，行为零变更） ──

/** 单弟子旬级 HP/MP 恢复（委托 CultivationCore） */
fun CultivationService.recoverHpMpSingle(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null
) {
        cultivationCore.recoverHpMpSingle(
            state, id, phasesToSettle,
            equipmentMap = equipmentMap, manualMap = manualMap
        )
}

/**
     * 列直读版 HP/MP 恢复（每旬热点专用，无 assemble）。
     * @return 是否发生写入
     */
fun CultivationService.recoverHpMpSingleColumn(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null,
        manualProficiencies: Map<String, List<ManualProficiencyData>>? = null
): Boolean = cultivationCore.recoverHpMpSingleColumn(
        state, id, phasesToSettle,
        equipmentMap = equipmentMap, manualMap = manualMap,
        manualProficiencies = manualProficiencies
)

fun CultivationService.calculateDiscipleCultivationPerPhase(
        disciple: com.xianxia.sect.core.model.Disciple,
        data: com.xianxia.sect.core.model.GameData,
        tables: com.xianxia.sect.core.state.DiscipleTables
): Double = cultivationCore.calculateDiscipleCultivationPerPhase(disciple, data, tables)

/**
     * 每旬修炼累积：按当前速率累加1旬修为。
     *
     * 列直读版（无 assemble）：每旬热点循环（每 2 秒 × 每弟子）不再组装
     * 完整 Disciple 对象，速率计算走 [CultivationRateCalculator.calculateCultivationPerPhaseById]。
     *
     * 不更新检查点（checkpoint 只在速率变化点更新——政策/长老/丹药/突破）。
     * 每旬累积只改变修为、从不改变速率，每旬同步 checkpoint 会让
     * cultivationCheckpoints 恒等于 cultivations，投影退化为恒等函数，纯属浪费 2 次列写。
     */
fun CultivationService.accumulateCultivationPerPhase(
        id: Int,
        state: com.xianxia.sect.core.state.MutableGameState,
        residenceByDiscipleId: Map<Int, ResidenceSlot> = emptyMap(),
        buildingByInstanceId: Map<String, GridBuildingData> = emptyMap()
) {
        val tables = state.discipleTables
        // 死者守卫保持最前（热点循环最常见跳过）；满修为/零速率以嵌套分支
        // 承接（ReturnCount ≤2 约束，语义与原三段守卫逐位等价）
        if (tables.isAlive[id] != 1) return
        val realm = tables.realms.getOrDefault(id, 9)
        val realmLayer = tables.realmLayers.getOrDefault(id, 1)
        val curCult = tables.cultivations.getOrDefault(id, 0.0)
        val maxCultivation = computeMaxCultivation(realm, realmLayer, curCult)
        if (curCult < maxCultivation) {
            val rate = cultivationCore.calculateCultivationPerPhaseById(
                id, state.gameData, tables, residenceByDiscipleId, buildingByInstanceId
            )
            if (rate > 0.0) {
                tables.cultivations[id] = (curCult + rate).coerceAtMost(maxCultivation)
            }
        }
}

/** 每旬功法熟练度增长（委托 CultivationCore） */
fun CultivationService.processManualProficiencyPerPhase(state: MutableGameState) {
        cultivationCore.processManualProficiencyPerPhase(state)
}

/** 单弟子每旬功法熟练度增长（委托 CultivationCore） */
fun CultivationService.processManualProficiencySingle(
        state: MutableGameState, id: Int,
        manualInstanceMap: Map<String, ManualInstance>? = null,
        pendingProficiencies: MutableMap<String, List<ManualProficiencyData>?>? = null,
        libraryDiscipleIds: Set<String>? = null
) {
        cultivationCore.processManualProficiencySingle(
            state, id, manualInstanceMap, pendingProficiencies, libraryDiscipleIds
        )
}

/** 批量提交功法熟练度（委托 CultivationCore） */
fun CultivationService.commitManualProficiencies(
        state: MutableGameState,
        pending: MutableMap<String, List<ManualProficiencyData>?>
) {
        cultivationCore.commitManualProficiencies(state, pending)
}

/** 每旬装备孕养经验增长（委托 CultivationCore） */
fun CultivationService.processEquipmentNurturePerPhase(state: MutableGameState) {
        cultivationCore.processEquipmentNurturePerPhase(state)
}

/** 单弟子每旬装备孕养经验增长（委托 CultivationCore） */
fun CultivationService.processEquipmentNurtureSingle(
        state: MutableGameState, id: Int,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        sharedUpdates: MutableMap<String, EquipmentInstance>? = null
) {
        cultivationCore.processEquipmentNurtureSingle(state, id, equipmentMap, sharedUpdates)
}

/** 批量提交装备孕养更新（委托 CultivationCore） */
fun CultivationService.applyEquipmentUpdates(
        state: MutableGameState,
        updates: Map<String, EquipmentInstance>
) {
        cultivationCore.applyEquipmentUpdates(state, updates)
}

/** 实时轨专用：自动服用储物袋丹药（突破丹除外） */
fun CultivationService.processAutoPillsRealtime(state: MutableGameState) {
        cultivationCore.processRealtimeAutoPills(state)
}

/** 月度持续效果衰减（月结制专用） */
fun CultivationService.applyMonthlyDurationDecay(
        tables: com.xianxia.sect.core.state.DiscipleTables, id: Int,
        focusedPhaseCount: Int = 0
) {
        cultivationCore.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)
}

/**
     * 月结全量持续效果衰减。
     * 对所有存活弟子执行一次月衰减（丹药 duration 以旬为单位，每月减 3 旬）。
     * 在月变事务内调用，与自动排班等月结操作同事务原子提交。
     */
fun CultivationService.applyMonthlyDurationDecayAll(state: MutableGameState) {
        val tables = state.discipleTables
        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            cultivationCore.applyMonthlyDurationDecay(tables, id)
        }
}

/**
 * 仅对指定弟子列表执行突破检测。
 *
 * 复用 [DiscipleBreakthroughHandler.processRealtimeBreakthroughs]
 * 核心逻辑，但过滤为只处理目标弟子。
 */
internal fun CultivationService.processBreakthroughsForDisciples(
    state: MutableGameState,
    discipleIds: List<String>
) {
    val tables = state.discipleTables
    val targetDisciples = discipleIds.mapNotNull { idStr ->
        val id = idStr.toIntOrNull() ?: return@mapNotNull null
        if (tables.isAlive[id] != 1) return@mapNotNull null
        tables.assemble(id)
    }
    if (targetDisciples.isEmpty()) return
    breakthroughHandler.processRealtimeBreakthroughs(
        targetDisciples, state.gameData, state
    )
}
