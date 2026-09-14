package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.state.MutableGameState

/**
 * 弟子强化派生 map 统一收口（审计 P2-7 + P3-4 / 方案 D3 改动 4）。
 *
 * 死亡（[com.xianxia.sect.core.engine.service.DiscipleLifecycleProcessor] 两处）/
 * 逐出（[DiscipleService.expelDisciple]）等弟子移除链统一经本函数清键——
 * 血炼三 map（BonusTotals/PctTotals/Refinements）+ 功法熟练度。
 * 与 C++ `blood_refinement.h::eraseDiscipleDerivedMaps` 镜像同源（R2/R5：
 * 唯一收口点，新增按弟子 id 键控的派生 map 必须双端同步登记）。
 *
 * 调用契约：`stateStore.update { }` 事务内（MutableGameState 接收者）。
 */
internal fun MutableGameState.eraseDiscipleDerivedMaps(discipleId: String) {
    gameData = gameData.copy(
        bloodRefinementBonusTotals = gameData.bloodRefinementBonusTotals - discipleId,
        bloodRefinementPctTotals = gameData.bloodRefinementPctTotals - discipleId,
        bloodRefinements = gameData.bloodRefinements - discipleId,
        manualProficiencies = gameData.manualProficiencies - discipleId
    )
}
