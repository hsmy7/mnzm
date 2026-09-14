package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.MutableGameState

// ── 生产委托域（自 CultivationService 拆出，行为零变更） ──

/**
     * 全量 Checkpoint：策略/长老变化后调用，重算所有生产系统的有效速率。
     * 炼丹/锻造的 completionMonth 会根据当前政策/长老重新计算。
     */
suspend fun CultivationService.checkpointAllProduction() {
        productionProcessor.recalculateAllCompletionMonths()
}

/** 月结窗口前置对齐（nativeSettleMonth 之前）：repo 为真源整表写镜像。 */
internal fun CultivationService.alignProductionSlotsForNativeMonth() {
        productionProcessor.alignMirrorFromRepository()
}

/** S4 月结窗口后置写回（残留执行器之后）：镜像整表重放 repo（restoreSlots）。 */
internal fun CultivationService.restoreProductionSlotsFromMirror(slotId: Int) {
        productionProcessor.restoreRepositoryFromMirror(slotId)
}

internal fun CultivationService.processSpiritFieldHarvest(state: MutableGameState) {
            productionProcessor.processSpiritFieldHarvest(state)
}

internal suspend fun CultivationService.processAutoAlchemy() {
            productionProcessor.processAutoAlchemy()
}

/** 影子状态批量生产循环（委托 ProductionProcessor 的 shadow 版方法） */
internal suspend fun CultivationService.processMonthlyProductionOnSlots(
        slots: MutableList<ProductionSlot>,
        state: MutableGameState,
        months: Int
) {
        productionProcessor.processMonthlyProductionOnSlots(slots, state, months)
}
