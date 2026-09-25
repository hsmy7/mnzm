package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.releaseDiscipleFromAllSlotsAtomic

// ── 弟子生命周期操作族扩展（自 DiscipleDelegate/GameViewModel 拆出，行为零变更）──
// 释放弟子换岗：batch-02 TooManyFunctions 收敛（类内 ≤19）
// 外移为同包扩展，调用点语法不变（GameViewModel 侧调用方改为直连 disciple）。

fun DiscipleDelegate.releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
    gameEngine.launchOnEngine { gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId) }
}

/**
 * 释放弟子为其分配新任务。根据当前状态决定释放方式：
 * - REFLECTING（思过中）→ 释放思过（调用 releaseReflectionDisciple）
 * - 其他状态 → releaseDiscipleFromAllSlotsAtomic
 */
fun DiscipleDelegate.releaseDiscipleForReassignment(discipleId: String) {
    val status = gameEngine.getDiscipleAggregate(discipleId)?.status ?: return
    when (status) {
        com.xianxia.sect.core.model.DiscipleStatus.REFLECTING -> {
            releaseReflectionDisciple(discipleId)
        }
        else -> releaseDiscipleFromAllSlotsAtomic(discipleId)
    }
}
