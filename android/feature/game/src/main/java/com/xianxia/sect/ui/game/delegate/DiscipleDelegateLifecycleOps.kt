package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.cancelBloodRefinement
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.releaseDiscipleAssignment
import com.xianxia.sect.core.engine.releaseDiscipleFromAllSlotsAtomic

// ── 弟子生命周期操作族扩展（自 DiscipleDelegate/GameViewModel 拆出，行为零变更）──
// 婚姻提议审批 + 释放弟子换岗：batch-02 TooManyFunctions 收敛（类内 ≤19）
// 外移为同包扩展，调用点语法不变（GameViewModel 侧调用方改为直连 disciple）。

/**
 * 批准婚姻提议：通知引擎执行配对并移除待处理提议。
 */
fun DiscipleDelegate.approveMarriage(maleId: String, femaleId: String) {
    gameEngine.launchOnEngine { gameEngine.approveMarriageProposal(maleId, femaleId) }
}

/**
 * 拒绝婚姻提议：通知引擎移除待处理提议，不执行配对。
 */
fun DiscipleDelegate.rejectMarriage(maleId: String, femaleId: String) {
    gameEngine.launchOnEngine { gameEngine.rejectMarriageProposal(maleId, femaleId) }
}

fun DiscipleDelegate.releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
    gameEngine.launchOnEngine { gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId) }
}

/**
 * 释放弟子为其分配新任务。根据当前状态决定释放方式：
 * - REFLECTING（思过中）→ 释放思过（调用 releaseReflectionDisciple）
 * - REFINING（血炼中）→ 中止血炼（不返还材料）
 * - 其他状态 → releaseDiscipleFromAllSlotsAtomic
 */
fun DiscipleDelegate.releaseDiscipleForReassignment(discipleId: String) {
    val status = gameEngine.getDiscipleAggregate(discipleId)?.status ?: return
    when (status) {
        com.xianxia.sect.core.model.DiscipleStatus.REFLECTING -> {
            releaseReflectionDisciple(discipleId)
        }
        com.xianxia.sect.core.model.DiscipleStatus.REFINING -> {
            // 找到该弟子对应的血炼建筑实例 → 中止血炼（不返还材料）
            val gd = gameEngine.gameDataSnapshot
            val buildingInstanceId = gd?.activeBloodRefinements?.entries
                ?.firstOrNull { it.value.discipleId == discipleId }
                ?.key
            if (buildingInstanceId != null) {
                gameEngine.launchOnEngine {
                    gameEngine.cancelBloodRefinement(buildingInstanceId, discipleId)
                    // 同步释放 gate 注册（cancelBloodRefinement 不清 gate，
                    // 与 BloodRefiningViewModel.cancelRefine 的释放语义对齐）
                    gameEngine.releaseDiscipleAssignment(discipleId)
                }
            } else {
                releaseDiscipleFromAllSlotsAtomic(discipleId)
            }
        }
        else -> releaseDiscipleFromAllSlotsAtomic(discipleId)
    }
}
