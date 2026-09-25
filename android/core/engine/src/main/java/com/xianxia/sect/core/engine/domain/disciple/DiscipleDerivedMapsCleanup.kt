package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.state.MutableGameState

/**
 * 弟子强化派生 map 统一收口（审计 P2-7 + P3-4 / 方案 D3 改动 4）。
 *
 * 死亡（[com.xianxia.sect.core.engine.service.DiscipleLifecycleProcessor]）
 * 等弟子移除链统一经本函数清键——功法熟练度。
 * 唯一收口点（R2/R5：新增按弟子 id 键控的派生 map 必须双端同步登记）。
 *
 * 调用契约：`stateStore.update { }` 事务内（MutableGameState 接收者）。
 */
internal fun MutableGameState.eraseDiscipleDerivedMaps(discipleId: String) {
    gameData = gameData.copy(
        manualProficiencies = gameData.manualProficiencies - discipleId
    )
}
