package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.concurrent.NoOpResult
import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ParallelPhaseResult
import com.xianxia.sect.core.concurrent.ItemOp
import com.xianxia.sect.core.concurrent.ProductionBatchDelta
import com.xianxia.sect.core.concurrent.ProductionBatchResult
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 205)
@Singleton
class ProductionSubsystem @Inject constructor(
    private val cultivationService: CultivationService,
    private val productionSlotRepository: ProductionSlotRepository,
    private val stateStore: GameStateStore
) : GameSystem {

    companion object {
        private const val TAG = "ProductionSubsystem"
        const val SYSTEM_NAME = "ProductionSubsystem"
    }

    override val systemName: String = SYSTEM_NAME
    override val settlementPhase = 2

    private var lastRealtimeTickMs = 0L
    private val realtimeTickInterval = 200L

    @Volatile
    private var batchSettledByParallel = false

    @Volatile
    private var pendingSlotBatch: List<ProductionSlot>? = null

    override fun initialize() {
        DomainLog.d(TAG, "ProductionSubsystem initialized")
    }

    override fun release() {
        DomainLog.d(TAG, "ProductionSubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    // ═══════════════════════════════════════════════════════════════
    // 并行 compute/apply 模式
    // ═══════════════════════════════════════════════════════════════

    override val supportsParallelTick: Boolean get() = true

    override suspend fun computePhaseTick(
        ctx: ParallelExecutionContext,
        phasesToSettle: Int
    ): ParallelPhaseResult {
        if (phasesToSettle < 3) return NoOpResult.INSTANCE
        batchSettledByParallel = false
        pendingSlotBatch = null

        val months = phasesToSettle / 3

        // 1. 读取当前槽位（Phase 1 无人写，安全）
        val slots = productionSlotRepository.getSlots().toMutableList()

        // 2. 创建影子状态（含全部游戏状态 + 生产槽位）
        val shadow = stateStore.createSettlementShadow(slots)
        // 记录影子创建时的原始快照，用于 diff → 生成增量 ItemOp
        val origHerbQtys = shadow.herbs.all().associate { it.id to it.quantity }
        val origMatQtys = shadow.materials.all().associate { it.id to it.quantity }
        val origSeedQtys = shadow.seeds.all().associate { it.id to it.quantity }
        val origPillQtys = shadow.pills.all().associate { it.id to it.quantity }
        val origEqQtys = shadow.equipmentStacks.all().associate { it.id to it.quantity }
        val originalStatuses = shadow.discipleTables.ids.associate { id ->
            id to shadow.discipleTables.statuses.getOrNull(id)
        }

        // 3. 在影子上运行 N 个月的真实生产代码
        cultivationService.processMonthlyProductionOnSlots(slots, shadow, months)

        // 4. diff 影子 vs 原始 → 生成增量 ItemOp（不整表替换）
        val delta = ProductionBatchDelta()
        diffEntityStore(shadow.herbs, origHerbQtys,
            onAdded = { delta.itemsToAdd.add(ItemOp.AddHerb(it)) },
            onConsumed = { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeHerb(id, qty)) })
        diffEntityStore(shadow.materials, origMatQtys,
            onAdded = { delta.itemsToAdd.add(ItemOp.AddMaterial(it)) },
            onConsumed = { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeMaterial(id, qty)) })
        diffEntityStore(shadow.seeds, origSeedQtys,
            onAdded = {},
            onConsumed = { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeSeed(id, qty)) })
        diffEntityStore(shadow.pills, origPillQtys,
            onAdded = { delta.itemsToAdd.add(ItemOp.AddPill(it)) },
            onConsumed = { _, _ -> })
        diffEntityStore(shadow.equipmentStacks, origEqQtys,
            onAdded = { delta.itemsToAdd.add(ItemOp.AddEquipment(it)) },
            onConsumed = { _, _ -> })
        // 补充检测：原始有但影子中被完全移除的物品（消耗到 0 后 EntityStore.remove 了）
        diffRemovedItems(shadow.herbs, origHerbQtys) { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeHerb(id, qty)) }
        diffRemovedItems(shadow.materials, origMatQtys) { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeMaterial(id, qty)) }
        diffRemovedItems(shadow.seeds, origSeedQtys) { id, qty -> delta.itemsToAdd.add(ItemOp.ConsumeSeed(id, qty)) }

        // 灵田植物变更（gameData 不在 EntityStore diff 范围内）
        val currentPlants = shadow.gameData.spiritFieldPlants
        if (currentPlants != ctx.gameData.spiritFieldPlants) {
            delta.updatedSpiritFieldPlants = currentPlants
        }

        // 弟子状态 diff
        for (id in shadow.discipleTables.ids) {
            val orig = originalStatuses[id]
            if (orig != null && orig != DiscipleStatus.IDLE &&
                shadow.discipleTables.statuses.getOrNull(id) == DiscipleStatus.IDLE) {
                delta.freedDiscipleIds.add(id)
            }
        }
        delta.finalSlots = slots.toList()

        // 5. 返回增量 delta
        val result = ProductionBatchResult(
            delta = delta,
            onApplied = {
                pendingSlotBatch = slots.toList()
                batchSettledByParallel = true
            }
        )
        // ★ batchSettledByParallel 时序：
        //   Phase 1 (parallel):  = false  ← 当前行
        //   Phase 2 (stateStore.update): result.apply → onApplied() 设 = true
        //   Phase 2 (续):        onPhaseTick 检查 flag = true → 跳过
        batchSettledByParallel = false
        return result
    }

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        if (phasesToSettle >= 3) {
            if (batchSettledByParallel) {
                pendingSlotBatch?.let { productionSlotRepository.loadSlots(it) }
                return
            }
            DomainLog.d(TAG, "Serial fallback for ${phasesToSettle} phases")
            val months = phasesToSettle / 3
            repeat(months) { processMonthlyProduction(state) }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastRealtimeTickMs < realtimeTickInterval) return
        lastRealtimeTickMs = now

        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        cultivationService.processBuildingProduction(year, month)
        cultivationService.processHerbGardenGrowth(state)
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
    }

    /**
     * diff 影子 vs 原始的 EntityStore，产出增减操作。
     */
    /** 原始快照有但影子中被完全移除的物品 → 整额消耗 */
    private fun diffRemovedItems(
        store: com.xianxia.sect.core.state.EntityStore<*>,
        origQtys: Map<String, Int>,
        onConsumed: (String, Int) -> Unit
    ) {
        val currentIds = store.all().map { (it as com.xianxia.sect.core.model.HasId).id }.toSet()
        for ((id, qty) in origQtys) {
            if (id !in currentIds) onConsumed(id, qty)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> diffEntityStore(
        store: com.xianxia.sect.core.state.EntityStore<T>,
        origQtys: Map<String, Int>,
        onAdded: (T) -> Unit,
        onConsumed: (String, Int) -> Unit
    ) where T : com.xianxia.sect.core.model.HasId,
            T : com.xianxia.sect.core.util.StackableItem {
        for (item in store.all()) {
            val orig = origQtys[item.id] ?: 0
            val diff = item.quantity - orig
            when {
                diff > 0 -> onAdded(item.withQuantity(diff) as T)
                diff < 0 -> onConsumed(item.id, -diff)
            }
        }
    }

    private suspend fun processMonthlyProduction(state: MutableGameState) {
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
        cultivationService.processBuildingProduction(
            state.gameData.gameYear, state.gameData.gameMonth
        )
        cultivationService.processHerbGardenGrowth(state)
        cultivationService.processSpiritFieldHarvest(state)
        cultivationService.processAutoPlant(state)
    }
}
