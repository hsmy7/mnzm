package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.MutableGameState

/**
 * [CultivationTickSystem] 并行 compute 的计算结果。
 *
 * 在 [Dispatchers.Default] 上并行计算所有弟子的修炼增量、功法和装备熟练度，
 * 通过 [apply] 方法在游戏主线程的 [stateStore.update] 块中原子写入。
 */
class CultivationBatchResult(
    /** 修改后的弟子表（deepCopy → 并行计算 → 此处为结果） */
    private val updatedTables: DiscipleTables,
    /** 修改后的功法熟练度映射（discipleId → profList），null 表示无变化 */
    private val proficiencies: Map<String, List<ManualProficiencyData>>?,
    /** 修改后的装备实例映射（equipmentId → EquipmentInstance），null 表示无变化 */
    private val equipmentMap: Map<String, EquipmentInstance>?,
    /** apply 完成后的回调（用于通知 CultivationTickSystem 跳过重复结算） */
    private val onApplied: (() -> Unit)? = null
) : ParallelPhaseResult {

    override suspend fun apply(state: MutableGameState) {
        // 1. 写入弟子表（整表替换）
        state.discipleTables = updatedTables

        // 2. 写入功法熟练度
        if (proficiencies != null) {
            state.gameData = state.gameData.copy(
                manualProficiencies = proficiencies
            )
        }

        // 3. 写入装备实例
        if (equipmentMap != null && equipmentMap.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq ->
                equipmentMap[eq.id] ?: eq
            }
        }

        // 4. 通知调用方
        onApplied?.invoke()
    }
}

/**
 * 单个弟子在一旬内的修炼计算结果。
 */
data class DiscipleCultivationDelta(
    val discipleId: Int,
    val cultivationDelta: Double,
    val maxCultivation: Double,
    val alive: Boolean
)
