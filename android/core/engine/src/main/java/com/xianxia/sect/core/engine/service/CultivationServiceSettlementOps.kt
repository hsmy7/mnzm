package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.MutableGameState

// ── 结算/政策委托域（自 CultivationService 拆出，行为零变更） ──

suspend fun CultivationService.settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        cultivationSettlement.settleSalaryOnBreakthrough(discipleId, currentYear)
}

suspend fun CultivationService.processAnnualSalary(year: Int) {
        cultivationSettlement.processAnnualSalary(year)
}

fun CultivationService.processResidenceLoyalty(state: MutableGameState) {
        cultivationSettlement.processResidenceLoyalty(state)
}

/** 月度自动排班 + 住所忠诚度，在事务 A 内由 [GameEngineCore.processMonthYearChange] 调用。 */
fun CultivationService.processMonthlyAutoAssignments(state: MutableGameState) {
        productionProcessor.processAutoAssign(state)
        cultivationSettlement.processResidenceLoyalty(state)
}

internal fun CultivationService.processPolicyCosts(state: MutableGameState): PolicyCostResult {
        return cultivationSettlement.processPolicyCosts(state)
}

internal fun CultivationService.processPolicyMonthlyEffects(state: MutableGameState) {
        cultivationSettlement.processPolicyMonthlyEffects(state)
}
