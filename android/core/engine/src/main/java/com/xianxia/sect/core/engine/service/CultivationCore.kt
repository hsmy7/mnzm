package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 单旬 tick 时间与倍率参数。
 *
 * @property year 当前游戏年
 * @property month 当前游戏月
 * @property phase 当前游戏旬
 * @property multiplier HP/MP 恢复倍率
 * @property decay 持续效果衰减旬数
 */
@Singleton
@GameService("CultivationCore")
class CultivationCore @Inject constructor(
    // 仅保留被方法体引用的域服务依赖；熟练度核心逻辑在 ManualProficiencyService
    private val hpMpRecoveryService: HpMpRecoveryService,
    private val autoPillService: AutoPillService,
    private val manualProficiencyService: ManualProficiencyService,
    private val cultivationRateCalculator: CultivationRateCalculator
) {

    // ── 委托到子服务的方法 ────────────────────────────────────
    fun calculateDiscipleCultivationPerPhase(disciple: Disciple, data: GameData, tables: DiscipleTables): Double =
        cultivationRateCalculator.calculateDiscipleCultivationPerPhase(disciple, data, tables)

    /** 列直读版每旬修炼速率（无 Disciple 组装），供每旬热点循环使用 */
    fun calculateCultivationPerPhaseById(
        id: Int, data: GameData, tables: DiscipleTables,
        residenceByDiscipleId: Map<Int, ResidenceSlot> = emptyMap(),
        buildingByInstanceId: Map<String, GridBuildingData> = emptyMap()
    ): Double = cultivationRateCalculator.calculateCultivationPerPhaseById(
        id, data, tables, residenceByDiscipleId, buildingByInstanceId
    )

    fun isDiscipleFullHpMp(disciple: Disciple, state: MutableGameState): Boolean =
        hpMpRecoveryService.isDiscipleFullHpMp(disciple, state)

    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables, state: MutableGameState): Boolean =
        hpMpRecoveryService.isDiscipleFullHpMp(id, tables, state)

    fun recoverHpMpSingle(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null
    ) = hpMpRecoveryService.recoverHpMpSingle(state, id, phasesToSettle, equipmentMap = equipmentMap,
        manualMap = manualMap)

    /**
     * 列直读版 HP/MP 恢复（每旬热点专用，无 assemble）。
     */
    fun recoverHpMpSingleColumn(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null,
        manualProficiencies: Map<String, List<ManualProficiencyData>>? = null
    ): Boolean = hpMpRecoveryService.recoverHpMpSingleColumn(
        state, id, phasesToSettle,
        equipmentMap = equipmentMap, manualMap = manualMap,
        manualProficiencies = manualProficiencies
    )

    fun applyMonthlyDurationDecay(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) =
        hpMpRecoveryService.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)

    fun processRealtimeAutoPills(state: MutableGameState) =
        autoPillService.processRealtimeAutoPills(state)

    // ── 每旬熟练度 + 孕养增长 ────────────────────────────────

    /**
     * 每旬功法熟练度增长（委托 [ManualProficiencyService]）。
     *
     * @param state 可变游戏状态
     */
    fun processManualProficiencyPerPhase(state: MutableGameState) =
        manualProficiencyService.processManualProficiencyPerPhase(state)

    /**
     * 单弟子每旬功法熟练度增长（委托 [ManualProficiencyService]）。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param manualInstanceMap 功法实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param pendingProficiencies 批量累积目标（null 时单弟子直写）
     * @param libraryDiscipleIds 藏经阁弟子 ID 预构建集合
     */
    fun processManualProficiencySingle(
        state: MutableGameState, id: Int,
        manualInstanceMap: Map<String, ManualInstance>? = null,
        pendingProficiencies: MutableMap<String, List<ManualProficiencyData>?>? = null,
        libraryDiscipleIds: Set<String>? = null
    ) = manualProficiencyService.processManualProficiencySingle(
        state, id, manualInstanceMap, pendingProficiencies, libraryDiscipleIds
    )

    /**
     * 批量提交功法熟练度累积结果（委托 [ManualProficiencyService]）。
     *
     * @param state 可变游戏状态
     * @param pending 由 [processManualProficiencySingle] 批量模式累积的变更
     */
    fun commitManualProficiencies(
        state: MutableGameState,
        pending: MutableMap<String, List<ManualProficiencyData>?>
    ) = manualProficiencyService.commitManualProficiencies(state, pending)

}
