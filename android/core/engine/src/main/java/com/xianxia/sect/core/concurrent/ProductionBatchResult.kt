package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState

/**
 * 生产批量结算的操作描述。
 * 由 [ProductionSubsystem.computePhaseTick] 产生，
 * 包含影子状态与原始快照的 diff → 增量 ItemOp。
 */
class ProductionBatchDelta(
    /** 待添加/消耗的物品 */
    val itemsToAdd: MutableList<ItemOp> = mutableListOf(),
    /** 批量结算中释放的弟子 ID */
    val freedDiscipleIds: MutableSet<Int> = mutableSetOf(),
    /** 最终生产槽位列表 */
    var finalSlots: List<ProductionSlot> = emptyList(),
    /** 灵田植物状态（processSpiritFieldHarvest 修改了 gameData.spiritFieldPlants） */
    var updatedSpiritFieldPlants: List<SpiritFieldPlant>? = null
)

/**
 * 单次物品操作。
 */
sealed class ItemOp {
    class AddPill(val pill: Pill) : ItemOp()
    class AddEquipment(val stack: EquipmentStack) : ItemOp()
    class AddHerb(val herb: Herb) : ItemOp()
    class ConsumeHerb(val id: String, val quantity: Int) : ItemOp()
    class ConsumeSeed(val id: String, val quantity: Int) : ItemOp()
    class ConsumeMaterial(val id: String, val quantity: Int) : ItemOp()
    class AddMaterial(val material: Material) : ItemOp()
}

/**
 * [ProductionSubsystem] 并行 compute 的结算结果。
 *
 * 在 Phase 1 中计算 [ProductionBatchDelta]，
 * [apply] 在 Phase 2 中将 delta 增量应用到真实状态。
 * 增量操作为主——不整表替换 EntityStore，避免覆盖 Phase 1 期间
 * 玩家通过 UI 产生的并发变更。
 */
class ProductionBatchResult(
    private val delta: ProductionBatchDelta,
    private val onApplied: () -> Unit
) : ParallelPhaseResult {

    override suspend fun apply(state: MutableGameState) {
        for (op in delta.itemsToAdd) {
            when (op) {
                is ItemOp.AddPill -> state.pills.add(op.pill)
                is ItemOp.AddEquipment -> state.equipmentStacks.add(op.stack)
                is ItemOp.AddHerb -> state.herbs.add(op.herb)
                is ItemOp.AddMaterial -> state.materials.add(op.material)
                is ItemOp.ConsumeHerb -> {
                    val h = state.herbs.get(op.id) ?: continue
                    val nq = h.quantity - op.quantity
                    if (nq <= 0) state.herbs.remove(op.id) else state.herbs.update(op.id) { it.copy(quantity = nq) }
                }
                is ItemOp.ConsumeSeed -> {
                    val s = state.seeds.get(op.id) ?: continue
                    val nq = s.quantity - op.quantity
                    if (nq <= 0) state.seeds.remove(op.id) else state.seeds.update(op.id) { it.copy(quantity = nq) }
                }
                is ItemOp.ConsumeMaterial -> {
                    val m = state.materials.get(op.id) ?: continue
                    val nq = m.quantity - op.quantity
                    if (nq <= 0) state.materials.remove(op.id) else state.materials.update(op.id) { it.copy(quantity = nq) }
                }
            }
        }
        for (id in delta.freedDiscipleIds) {
            state.discipleTables.statuses[id] = DiscipleStatus.IDLE
        }
        val plants = delta.updatedSpiritFieldPlants
        if (plants != null) {
            state.gameData = state.gameData.copy(spiritFieldPlants = plants)
        }
        state.productionSlots = delta.finalSlots
        onApplied()
    }
}
