package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
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
    // D2（2026-08-05）：删除 10 个方法体内零引用依赖（stateStore/inventoryConfig/
    // thermalMonitor/gameClock/scopeProvider/pillManager/equipmentManager/manualManager/
    // profiler），构造依赖 15 → 6；熟练度核心逻辑迁至 ManualProficiencyService
    private val hpMpRecoveryService: HpMpRecoveryService,
    private val autoPillService: AutoPillService,
    private val equipmentNurtureService: EquipmentNurtureService,
    private val manualProficiencyService: ManualProficiencyService,
    private val cultivationRateCalculator: CultivationRateCalculator,
    private val battleSettlementService: BattleSettlementService
) {

    val phaseMultiplier: Int get() = 10

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

    fun getLifespanGainForRealm(realm: Int): Int = cultivationRateCalculator.getLifespanGainForRealm(realm)

    fun isDiscipleFullHpMp(disciple: Disciple): Boolean = hpMpRecoveryService.isDiscipleFullHpMp(disciple)

    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables): Boolean = hpMpRecoveryService.isDiscipleFullHpMp(id, tables)

    fun recoverHpMpForAllDisciples(state: MutableGameState, phasesToSettle: Int = 3) =
        hpMpRecoveryService.recoverHpMpForAllDisciples(state, phasesToSettle)

    fun recoverHpMpSingle(
        state: MutableGameState, id: Int, phasesToSettle: Int = 1,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        manualMap: Map<String, ManualInstance>? = null
    ) = hpMpRecoveryService.recoverHpMpSingle(state, id, phasesToSettle, equipmentMap = equipmentMap, manualMap = manualMap)

    /**
     * 列直读版 HP/MP 恢复（2026-08-01 每旬热点专用，无 assemble）。
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

    fun recoverMonthlyHpMp(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0,
        zones: RecoveryZones = RecoveryZones()
    ) = hpMpRecoveryService.recoverMonthlyHpMp(tables, id, focusedPhaseCount, zones)

    fun applyMonthlyDurationDecay(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) =
        hpMpRecoveryService.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)

    fun recoverHpMpForBattleParticipants(state: MutableGameState, discipleIds: List<String>,
        zones: RecoveryZones = RecoveryZones()
    ) = hpMpRecoveryService.recoverHpMpForBattleParticipants(state, discipleIds, zones)

    fun processRealtimeAutoPills(state: MutableGameState, year: Int, month: Int, phase: Int) =
        autoPillService.processRealtimeAutoPills(state, year, month, phase)

    fun forceSettleDisciplesBeforeBattle(state: MutableGameState, discipleIds: List<String>) =
        battleSettlementService.forceSettleDisciplesBeforeBattle(state, discipleIds)

    // ── 每旬熟练度 + 孕养增长 ────────────────────────────────

    /**
     * 每旬功法熟练度增长（D2 迁移至 [ManualProficiencyService]，此处保留委托）。
     *
     * @param state 可变游戏状态
     */
    fun processManualProficiencyPerPhase(state: MutableGameState) =
        manualProficiencyService.processManualProficiencyPerPhase(state)

    /**
     * 每旬装备孕养经验增长。
     *
     * 对所有存活且有装备的弟子，结算1旬的装备孕养经验增长。
     * 无需 `assemble`，通过 `tables.weaponIds/armorIds/bootsIds/accessoryIds` 列级直读装备 ID。
     *
     * @param state 可变游戏状态
     */
    fun processEquipmentNurturePerPhase(state: MutableGameState) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val equipmentUpdates = mutableMapOf<String, EquipmentInstance>()

        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            equipmentNurtureService.settleNurtureInPlace(
                id = id, tables = tables, equipmentMap = equipmentMap,
                nurtureGainPerPhase = EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE,
                phasesToSettle = 1, equipmentUpdates = equipmentUpdates
            )
        }

        if (equipmentUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq ->
                equipmentUpdates[eq.id] ?: eq
            }
        }
    }

    /**
     * 单弟子每旬功法熟练度增长（D2 迁移至 [ManualProficiencyService]，此处保留委托）。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param manualInstanceMap 功法实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param pendingProficiencies 批量累积目标（null 时保持旧的单弟子直写行为）
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
     * 批量提交功法熟练度累积结果（D2 迁移至 [ManualProficiencyService]，此处保留委托）。
     *
     * @param state 可变游戏状态
     * @param pending 由 [processManualProficiencySingle] 批量模式累积的变更
     */
    fun commitManualProficiencies(
        state: MutableGameState,
        pending: MutableMap<String, List<ManualProficiencyData>?>
    ) = manualProficiencyService.commitManualProficiencies(state, pending)

    /**
     * 单弟子每旬装备孕养经验增长。
     *
     * 从 [processEquipmentNurturePerPhase] 的循环体中提取，
     * 仅处理指定 ID 的存活弟子。无需 assemble，
     * 通过 tables.weaponIds/armorIds/bootsIds/accessoryIds 列级直读装备 ID。
     *
     * 批量模式（P-2 优化）：当 [sharedUpdates] 非空时，本函数把装备更新累积到
     * sharedUpdates 且**不写 state**；调用方循环结束后统一调用
     * [applyEquipmentUpdates] 单次重建 List——将每旬 O(D×E) 全量列表重建
     * （每弟子 map 全部装备）降为 O(E)。
     *
     * @param state 可变游戏状态
     * @param id 弟子 ID
     * @param equipmentMap 装备实例映射（每旬热点循环共享构建，null 时内部构建）
     * @param sharedUpdates 批量累积目标（null 时保持旧的单弟子直写行为）
     */
    fun processEquipmentNurtureSingle(
        state: MutableGameState, id: Int,
        equipmentMap: Map<String, EquipmentInstance>? = null,
        sharedUpdates: MutableMap<String, EquipmentInstance>? = null
    ) {
        val tables = state.discipleTables
        if (tables.isAlive[id] != 1) return
        val eqMap = equipmentMap ?: state.equipmentInstances.associateBy { it.id }
        val updates = sharedUpdates ?: mutableMapOf<String, EquipmentInstance>()

        equipmentNurtureService.settleNurtureInPlace(
            id = id, tables = tables, equipmentMap = eqMap,
            nurtureGainPerPhase = EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE,
            phasesToSettle = 1, equipmentUpdates = updates
        )

        // 单弟子模式才立即写 state；批量模式由调用方统一提交
        if (sharedUpdates == null && updates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq ->
                updates[eq.id] ?: eq
            }
        }
    }

    /**
     * 批量提交装备孕养累积结果（P-2：单次 List 重建）。
     *
     * 与单弟子模式逐弟子写等价：`map { updates[it.id] ?: it }` 保持原列表顺序，
     * 最终列表逐元素相同。updates 为空时不做任何事（无变化则不重建）。
     *
     * @param state 可变游戏状态
     * @param updates 由 [processEquipmentNurtureSingle] 批量模式累积的装备更新
     */
    fun applyEquipmentUpdates(
        state: MutableGameState,
        updates: Map<String, EquipmentInstance>
    ) {
        if (updates.isEmpty()) return
        state.equipmentInstances = state.equipmentInstances.map { eq ->
            updates[eq.id] ?: eq
        }
    }

}
