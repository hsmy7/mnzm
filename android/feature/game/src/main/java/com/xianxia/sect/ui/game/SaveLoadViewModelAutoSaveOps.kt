package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.SaveTriggerFlag
import com.xianxia.sect.data.shouldAutoSave
import com.xianxia.sect.ui.game.saveload.AUTO_SAVE_NOTICE_LABEL
import com.xianxia.sect.ui.game.saveload.AutoSaveTrigger
import com.xianxia.sect.ui.game.saveload.SaveFeedback
import com.xianxia.sect.ui.game.saveload.saveFeedbackFor

// ── 自动存档触发面（SR-4：月变 + onStop 经 SaveOrchestrator 合并后落盘）─────────────────
// 独立成文件：SaveLoadViewModelSaveOps 已接近 detekt 文件函数数上限（SR-1/SR-2/SR-3 教训）。

/**
 * 自动保存触发入口（月变事件 / `onStop`）——旗标 + 槽位 + 引擎三前置齐备才入编排窗。
 *
 * 非挂起、任意线程可调：月变事件来自引擎线程，`onStop` 来自主线程；
 * 排队与合并全部在 [SaveLoadViewModel.saveOrchestrator] 内。
 */
internal fun SaveLoadViewModel.requestAutoSave(trigger: AutoSaveTrigger) {
    val flagOn = when (trigger) {
        AutoSaveTrigger.MONTHLY -> SaveTriggerFlag.autoSaveOnMonthChange
        AutoSaveTrigger.BACKGROUND -> SaveTriggerFlag.saveOnBackground
    }
    val slot = gameEngine.gameData.value?.currentSlot ?: -1
    if (!shouldAutoSave(
            flagOn = flagOn,
            hasActiveSlot = slot >= MIN_AUTO_SAVE_SLOT,
            engineLoaded = isGameLoaded
        )
    ) {
        Log.d(
            SaveLoadViewModelConstants.TAG,
            "autoSave skipped trigger=$trigger flagOn=$flagOn slot=$slot loaded=$isGameLoaded"
        )
        return
    }
    saveOrchestrator.submit(trigger)
}

/**
 * 编排窗到点（或 onStop 冲刷）后的实际落盘——复用手动保存链，仅反馈口径不同。
 *
 * slot 取 `gameData.currentSlot`（[requestAutoSave] 已保证 ≥[MIN_AUTO_SAVE_SLOT]）。
 */
internal suspend fun SaveLoadViewModel.onAutoSaveFire(triggers: Set<AutoSaveTrigger>) {
    val slot = gameEngine.gameData.value?.currentSlot ?: return
    if (slot < MIN_AUTO_SAVE_SLOT) {
        Log.i(SaveLoadViewModelConstants.TAG, "autoSave 放弃：槽位在触发后失效（slot=$slot）")
        return
    }
    Log.i(SaveLoadViewModelConstants.TAG, "autoSave 触发 triggers=$triggers slot=$slot")
    saveGame(slotId = slot.toString(), feedback = saveFeedbackFor(triggers))
}

/**
 * 保存成功后的**分流反馈**（SR-4）——三口径共享"降级不得谎报"红线（审计 §12-C）：
 *
 * - [SaveFeedback.Manual]：现状逐行不变（成功 snackbar，备份未写入如实带后缀）；
 * - [SaveFeedback.AutoNotice]：写消息栏常驻一行（月变每 6 秒一次 ⇒ 不弹 snackbar）；
 * - [SaveFeedback.Silent]：成功零提示（玩家已离场），降级仅日志留痕。
 */
internal fun SaveLoadViewModel.reportSaveSuccess(
    feedback: SaveFeedback,
    gameData: GameData,
    postSaveWarning: String?
) {
    when (feedback) {
        SaveFeedback.Manual -> if (postSaveWarning == null) {
            showSuccess("游戏保存成功")
        } else {
            Log.w(SaveLoadViewModelConstants.TAG, "saveGame 降级（主保存成功）: $postSaveWarning")
            showSuccess("游戏保存成功（备份未写入）")
        }

        SaveFeedback.AutoNotice -> {
            if (postSaveWarning != null) {
                Log.w(SaveLoadViewModelConstants.TAG, "自动存档降级（主保存成功）: $postSaveWarning")
            }
            autoSaveNoticeFlow.value = autoSaveNoticeText(
                gameYear = gameData.gameYear,
                gameMonth = gameData.gameMonth,
                degraded = postSaveWarning != null
            )
        }

        SaveFeedback.Silent -> if (postSaveWarning != null) {
            Log.w(SaveLoadViewModelConstants.TAG, "后台保存降级（主保存成功）: $postSaveWarning")
        }
    }
}

/**
 * 消息栏自动存档行文案（纯函数，JVM 直测）。
 *
 * 带游戏内时间戳而非墙钟：月变按游戏时间发生，玩家对照的是"存到哪一月"；
 * 且墙钟口径与 IN2（仲裁无时钟）无关——这是展示文案，不参与任何"谁新"判定。
 */
internal fun autoSaveNoticeText(gameYear: Int, gameMonth: Int, degraded: Boolean): String =
    "$AUTO_SAVE_NOTICE_LABEL · 第${gameYear}年${gameMonth}月" + if (degraded) "（备份未写入）" else ""

/** 自动存档可落盘的最小槽位：slot 0 是云存档伪槽，不是本地档 */
internal const val MIN_AUTO_SAVE_SLOT = 1
