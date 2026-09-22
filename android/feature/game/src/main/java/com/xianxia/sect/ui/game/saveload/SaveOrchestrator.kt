package com.xianxia.sect.ui.game.saveload

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自动存档触发源（方案 §2 触发矩阵的自动两行；手动保存不入编排器——见 [SaveOrchestrator]）。 */
enum class AutoSaveTrigger {

    /** 游戏月月变（D6 拍板：月月必存，游戏月 = 6 秒真实时间） */
    MONTHLY,

    /** `onStop` 退到后台（审计 §16 #6 方案 A + D6） */
    BACKGROUND
}

/**
 * 合并触发集 → 保存链反馈口径（纯函数，JVM 直测）。
 *
 * 含 [AutoSaveTrigger.BACKGROUND] ⇒ [SaveFeedback.Silent]：玩家已离场，成功不提示；
 * 仅月变 ⇒ [SaveFeedback.AutoNotice]：消息栏常驻一行。
 */
fun saveFeedbackFor(triggers: Set<AutoSaveTrigger>): SaveFeedback =
    if (AutoSaveTrigger.BACKGROUND in triggers) SaveFeedback.Silent else SaveFeedback.AutoNotice

/**
 * 自动存档编排点（方案 §2"去抖合并：窗口内多触发合并为一次快照"）。
 *
 * 三条规则：
 * - [AutoSaveTrigger.MONTHLY] 入队即开一个合并窗（[Config.mergeWindowMs]），窗内后续触发并入
 *   同一集合，窗到点**一次**回调 [onFire]——月变每 6 秒一次，窗只做"同刻多源合并"，
 *   **不构成节流下限**（用户 2026-09-22 拍板月月必存，见施工卡 §1）；
 * - [AutoSaveTrigger.BACKGROUND] 取消窗口**立即**冲刷（含窗内已积累的月变）：进程可能马上被杀，
 *   等窗等于不存；
 * - [invalidate] 丢弃待触发窗——手动保存/读档/重开已经（或将要）落一次全量快照，
 *   自动窗再存一次纯属重复（这就是"手动"在合并语义里的位置：手动即存 ⇒ 作废自动窗）。
 *
 * 线程模型：[submit]/[invalidate] 非挂起、任意线程可调（月变事件来自引擎线程，
 * `onStop` 来自主线程）；内部状态变更一律派发到 [scope] 串行执行，由 [mutex] 保证互斥。
 *
 * 与 [UploadQueue] 的窗口合并是**两级不同职责**：本类合并的是"要不要再落一次本地盘"，
 * 队列合并的是"落盘后的快照投不投云端"（TapTap 1 次/分钟限频适配面）。
 */
class SaveOrchestrator(
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val onFire: suspend (Set<AutoSaveTrigger>) -> Unit
) {

    /** [mergeWindowMs] = 0 ⇒ 月变也立即执行（测试与极端低频场景可用） */
    data class Config(val mergeWindowMs: Long = 500L)

    private val mutex = Mutex()
    private val pending = LinkedHashSet<AutoSaveTrigger>()
    private var windowJob: Job? = null

    /** 提交一个自动存档触发（见类 KDoc 规则） */
    fun submit(trigger: AutoSaveTrigger) {
        scope.launch {
            mutex.withLock {
                pending += trigger
                if (trigger == AutoSaveTrigger.BACKGROUND) {
                    windowJob?.cancel()
                    windowJob = null
                    fireLocked()
                } else if (windowJob?.isActive != true) {
                    windowJob = scope.launch { openWindow() }
                }
            }
        }
    }

    /** 作废待触发窗（手动保存/读档/重开覆盖时调用）；不影响正在执行的保存 */
    fun invalidate() {
        scope.launch {
            mutex.withLock {
                windowJob?.cancel()
                windowJob = null
                pending.clear()
            }
        }
    }

    /** 测试/观测面：当前窗内已积累但未触发的触发集 */
    suspend fun pendingTriggers(): Set<AutoSaveTrigger> = mutex.withLock { pending.toSet() }

    private suspend fun openWindow() {
        delay(config.mergeWindowMs)
        mutex.withLock {
            windowJob = null
            fireLocked()
        }
    }

    /** 冲刷：把窗内触发集交给 [onFire]（空集 = 已被 [invalidate] 作废，不动作） */
    private suspend fun fireLocked() {
        if (pending.isEmpty()) return
        val triggers = pending.toSet()
        pending.clear()
        onFire(triggers)
    }
}
