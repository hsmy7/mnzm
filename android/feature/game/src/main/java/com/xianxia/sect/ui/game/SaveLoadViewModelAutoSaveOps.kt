package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.SaveTriggerFlag
import com.xianxia.sect.data.shouldAutoSave
import com.xianxia.sect.ui.game.saveload.AUTO_SAVE_NOTICE_LABEL
import com.xianxia.sect.ui.game.saveload.AutoSaveTrigger
import com.xianxia.sect.ui.game.saveload.SaveFeedback
import com.xianxia.sect.ui.game.saveload.saveFeedbackFor

// ── 自动存档触发面（现实墙钟节拍 + onStop 经 SaveOrchestrator 合并后落盘）────────────────
// 独立成文件：SaveLoadViewModelSaveOps 已接近 detekt 文件函数数上限（SR-1/SR-2/SR-3 教训）。

/**
 * 现实墙钟自动存档节拍（用户 2026-09-27 拍板：每 10 现实秒一存）。
 *
 * 与游戏速度解耦：2x 下不会变成 5 秒、暂停期间同样按真实时间落盘。
 * 取代历史的"游戏月月变触发"（那套是每 6 游戏秒，随速度 1x/2x 变成 6s/3s）。
 */
internal const val REALTIME_AUTO_SAVE_INTERVAL_MS = 10_000L

/**
 * 节拍轮询间隔（1 秒）。用固定短间隔轮询而非一次性 `delay(10s)`：
 * 轮询让"当前会话已累计多少现实时间"可被 UI/诊断观测，且单次 `delay` 的调度抖动
 * （OEM 挂起/主线程阻塞）不会推迟到点的落盘——累计量按轮询步长补齐，到点即存。
 */
internal const val REALTIME_AUTO_SAVE_POLL_MS = 1_000L

/**
 * 节拍循环启动开关（`SaveTriggerFlag` 同款 volatile 全局旗标惯用法）。
 *
 * 默认 **true = 生产行为不变**（init 启动节拍循环）。单测置 false：测试按
 * [onRealtimeAutoSaveTick] 的契约**直接驱动节拍推进点**，不启动循环——
 * `while(isActive) + delay` 的无穷链在虚拟时间调度器上永不空闲，
 * 会与 runTest 的结束检查互锁（UncompletedCoroutinesError），且测试本就
 * 无需验证"delay 一秒"这一平台机制。
 */
@Volatile
internal var realtimeAutoSaveTickLoopEnabled = true

/**
 * 现实墙钟节拍累计一步（纯函数，JVM 直测）。
 *
 * @param elapsedMs 本会话已累计的现实毫秒
 * @return 累计后的毫秒数（未触发时）或 0（本轮已触发落盘 ⇒ 累计清零重新计时）
 */
internal fun consumeRealtimeAutoSave(
    elapsedMs: Long,
    stepMs: Long = REALTIME_AUTO_SAVE_POLL_MS,
    intervalMs: Long = REALTIME_AUTO_SAVE_INTERVAL_MS
): Long {
    val next = elapsedMs + stepMs
    return if (next >= intervalMs) 0L else next
}

/**
 * 现实节拍单步推进（挂起；节拍循环每 [REALTIME_AUTO_SAVE_POLL_MS] 调一次）。
 *
 * 累计到 [REALTIME_AUTO_SAVE_INTERVAL_MS] 时提交一次自动存档并**清零重新计时**；
 * 未到点只更新可观测累计量。状态放在 ViewModel（`realtimeAutoSaveElapsedMsFlow`）而非本函数局部，
 * 以便 UI/诊断观察当前会话进度，且不引入额外类。
 *
 * 归零语义：到点即归零并**丢弃溢出量**——节拍是"至多每 10 现实秒一次"，
 * 不是"补足错过的周期"（补足会在长时间挂起恢复后连续落盘多次，反而制造卡顿与无效 IO）。
 *
 * 本函数是节拍的**唯一推进点**：节拍循环只负责 `delay + 调用`，
 * 断言与落盘全部在此，故测试可直接调用它推进节拍，无需与循环的调度时序耦合。
 */
internal suspend fun SaveLoadViewModel.onRealtimeAutoSaveTick() {
    val next = consumeRealtimeAutoSave(realtimeAutoSaveElapsedMsFlow.value)
    if (next > 0L) {
        realtimeAutoSaveElapsedMsFlow.value = next
        return
    }
    realtimeAutoSaveElapsedMsFlow.value = 0L
    requestAutoSave(AutoSaveTrigger.REALTIME)
}

/**
 * 自动保存触发入口（现实墙钟节拍 / `onStop`）——旗标 + 槽位 + 引擎三前置齐备才入编排窗。
 *
 * 非挂起、任意线程可调：节拍来自 UI 层定时协程，`onStop` 来自主线程；
 * 排队与合并全部在 [SaveLoadViewModel.saveOrchestrator] 内。
 */
internal fun SaveLoadViewModel.requestAutoSave(trigger: AutoSaveTrigger) {
    val flagOn = when (trigger) {
        AutoSaveTrigger.REALTIME -> SaveTriggerFlag.realtimeTick
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
 * 保存成功后的**分流反馈**——三口径共享"降级不得谎报"红线（审计 §12-C）：
 *
 * - [SaveFeedback.Manual]：现状逐行不变（成功 snackbar，备份未写入如实带后缀）；
 * - [SaveFeedback.AutoNotice]：写消息栏常驻一行（现实节拍每 10 秒一次 ⇒ 不弹 snackbar）；
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
 * 保存**失败**的分流反馈（方案 §4 SR-4"失败走告警通道，不再静默"）。
 *
 * 手动口径不变（snackbar）。自动口径改走消息栏**持久一行**而非 snackbar：
 * 现实节拍每 10 秒一次，存储退化（例如连续失败触发 [recordSaveCircuitResult] 熔断 30s）
 * 时 snackbar 会以 10 秒节奏刷屏并把真事件消息挤掉；持久行既满足"不静默"
 * （一直挂在消息栏直到下一次成功），又自带"最后一次失败原因"语义。日志同步留痕。
 */
internal fun SaveLoadViewModel.reportSaveFailure(feedback: SaveFeedback, message: String) {
    if (feedback == SaveFeedback.Manual) {
        showError(message)
        return
    }
    Log.e(SaveLoadViewModelConstants.TAG, "自动存档失败 feedback=$feedback: $message")
    autoSaveNoticeFlow.value = "$AUTO_SAVE_FAILED_PREFIX$message"
}

/** 自动存档失败行的前缀（与成功行同一承载位，玩家看到的是一行连续状态） */
internal const val AUTO_SAVE_FAILED_PREFIX = "自动存档失败："

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
