package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DirectDiscipleSlot

// ── 长老候选查询域扩展（自 ProductionViewModel 拆出，行为零变更）──────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。

fun ProductionViewModel.getInnerElder(): DiscipleAggregate? {
    val innerElderId = gameEngine.gameDataSnapshot?.elderSlots?.innerElder
    return getElderDisciple(innerElderId)
}

fun ProductionViewModel.getQingyunPreachingElder(): DiscipleAggregate? {
    val preachingElderId = gameEngine.gameDataSnapshot?.elderSlots?.qingyunPreachingElder
    return getElderDisciple(preachingElderId)
}

fun ProductionViewModel.getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
    return gameEngine.gameDataSnapshot?.elderSlots?.qingyunPreachingMasters ?: emptyList()
}

fun ProductionViewModel.getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}

fun ProductionViewModel.getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}

fun ProductionViewModel.getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}

fun ProductionViewModel.getAvailableDisciplesForInnerElder(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}

fun ProductionViewModel.getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}

fun ProductionViewModel.getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
    return gameEngine.discipleAggregatesSnapshot
        .eligibleElderCandidates()
        .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
}
