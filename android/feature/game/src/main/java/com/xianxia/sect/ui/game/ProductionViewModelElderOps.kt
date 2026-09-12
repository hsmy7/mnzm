package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType

// ── 长老任命域扩展（自 ProductionViewModel 拆出，行为零变更）──────────────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。

fun ProductionViewModel.getElderDisciple(elderId: String?): DiscipleAggregate? {
    if (elderId == null) return null
    return gameEngine.getDiscipleAggregate(elderId)
}

fun ProductionViewModel.assignElder(slotType: ElderSlotType, discipleId: String) =
    launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")

fun ProductionViewModel.removeElder(slotType: ElderSlotType) =
    launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")

fun ProductionViewModel.assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
    launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")

fun ProductionViewModel.removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
    launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")

fun ProductionViewModel.setViceSectMaster(discipleId: String) =
    launchElderAction({ elderManagement.assignElder(ElderSlotType.VICE_SECT_MASTER, discipleId) }, "任命副宗主失败")

fun ProductionViewModel.removeViceSectMaster() =
    launchElderAction({ elderManagement.removeElder(ElderSlotType.VICE_SECT_MASTER) }, "卸任副宗主失败")

fun ProductionViewModel.getViceSectMaster(): DiscipleAggregate? {
    val viceSectMasterId = gameEngine.gameDataSnapshot?.elderSlots?.viceSectMaster
    return getElderDisciple(viceSectMasterId)
}

fun ProductionViewModel.getViceSectMasterIntelligenceBonus(): Double {
    val viceSectMaster = getViceSectMaster() ?: return 0.0
    return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster)
}

fun ProductionViewModel.getOuterElder(): DiscipleAggregate? {
    val outerElderId = gameEngine.gameDataSnapshot?.elderSlots?.outerElder
    return getElderDisciple(outerElderId)
}

fun ProductionViewModel.getPreachingElder(): DiscipleAggregate? {
    val preachingElderId = gameEngine.gameDataSnapshot?.elderSlots?.preachingElder
    return getElderDisciple(preachingElderId)
}

fun ProductionViewModel.getPreachingMasters(): List<DirectDiscipleSlot> {
    return gameEngine.gameDataSnapshot?.elderSlots?.preachingMasters ?: emptyList()
}

fun ProductionViewModel.getLawEnforcementElder(): DiscipleAggregate? {
    val elderId = gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementElder
    return getElderDisciple(elderId)
}

fun ProductionViewModel.getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
    return gameEngine.gameDataSnapshot?.elderSlots?.lawEnforcementDisciples ?: emptyList()
}
