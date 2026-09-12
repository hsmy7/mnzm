package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData

// ── 事件编排委托域（自 CultivationService 拆出，行为零变更） ──

/** 实时轨专用：自动从仓库装备/学习 */
fun CultivationService.processAutoFromWarehouseRealtime(state: MutableGameState) {
        eventProcessor.processAutoFromWarehouseRealtime(state)
}

/**
     * 带状态版本的月度事件处理 — 在已存在的事务内使用。
     * 操作在传入的 state 上，而非打开新的 [stateStore.update]。
     */
fun CultivationService.processMonthlyEventsOnState(state: MutableGameState) {
        val data = state.gameData
        eventProcessor.processMonthlyEvents(data.gameYear, data.gameMonth, state)
}

suspend fun CultivationService.updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        eventProcessor.updateDiscipleHpMpAfterBattle(battleMembers)
}
