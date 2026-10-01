package com.xianxia.sect.ui.game.saveload

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 自动存档触发源（现实墙钟节拍 + 后台保存 + 关键事件；手动保存不入编排器——见 [SaveOrchestrator]）。 */
enum class AutoSaveTrigger {

    /**
     * 现实墙钟节拍（用户 2026-09-27 拍板：每 10 现实秒一存）。
     *
     * 时间基 = 现实单调时钟，与游戏速度/暂停/游戏日历解耦；间隔常量见
     * `SaveLoadViewModelAutoSaveOps.REALTIME_AUTO_SAVE_INTERVAL_MS`。
     * 取代历史的 `MONTHLY`（游戏月月变触发）：结算改现实时间连续化后月界不再有
     * "进度完整点"语义。
     */
    REALTIME,

    /** `onStop` 退到后台（审计 §16 #6 方案 A + D6） */
    BACKGROUND,

    /**
     * 关键事件（涉钱/不可逆/唯一性，方案 §2.5）——500ms 合并窗语义：
     * 寻访出货、碎片入账、里程碑等事件在窗内合并为一次落盘（十连十事件 → 一次写盘）。
     */
    CRITICAL_EVENT,

    /**
     * 涉钱关键事件（玉符流水 append，方案 §2.5"同步落盘"行）——立即冲刷语义：
     * 取消待触发窗并立即落盘（窗内已积累的触发一并带走），把涉钱丢失窗口压到最小。
     */
    CRITICAL_EVENT_MONEY
}

/**
 * 合并触发集 → 保存链反馈口径（纯函数，JVM 直测）。
 *
 * 含 [AutoSaveTrigger.BACKGROUND] ⇒ [SaveFeedback.Silent]：玩家已离场，成功不提示；
 * 其余（仅节拍或含关键事件）⇒ [SaveFeedback.AutoNotice]：消息栏常驻一行。
 */
fun saveFeedbackFor(triggers: Set<AutoSaveTrigger>): SaveFeedback =
    if (AutoSaveTrigger.BACKGROUND in triggers) SaveFeedback.Silent else SaveFeedback.AutoNotice

/**
 * 自动存档编排点（方案 §2"去抖合并：窗口内多触发合并为一次快照"）。
 *
 * 三条规则：
 * - [AutoSaveTrigger.REALTIME] 与 [AutoSaveTrigger.CRITICAL_EVENT] 入队即开一个合并窗
 *   （[Config.mergeWindowMs]），窗内后续触发并入同一集合，窗到点**一次**回调 [onFire]——
 *   本类只做"同刻多源合并"，**不构成节流下限**（节拍下限由触发源自己的 10 秒间隔保证，
 *   见 `SaveLoadViewModelAutoSaveOps.REALTIME_AUTO_SAVE_INTERVAL_MS`）；
 * - [AutoSaveTrigger.BACKGROUND] 与 [AutoSaveTrigger.CRITICAL_EVENT_MONEY] 取消窗口**立即**冲刷
 *   （含窗内已积累的节拍触发）：进程可能马上被杀 / 涉钱数据要求最短丢失窗口，等窗等于不存；
 * - [invalidate] 丢弃待触发窗——手动保存/读档/重开已经（或将要）落一次全量快照，
 *   自动窗再存一次纯属重复（这就是"手动"在合并语义里的位置：手动即存 ⇒ 作废自动窗）。
 *
 * 线程模型：[submit]/[invalidate] 非挂起、任意线程可调（节拍来自 UI 层定时协程，
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

    /** [mergeWindowMs] = 0 ⇒ 触发即立即执行（测试与极端低频场景可用） */
    data class Config(val mergeWindowMs: Long = 500L)

    private val mutex = Mutex()
    private val pending = LinkedHashSet<AutoSaveTrigger>()
    private var windowJob: Job? = null

    /** 提交一个自动存档触发（见类 KDoc 规则） */
    fun submit(trigger: AutoSaveTrigger) {
        scope.launch {
            mutex.withLock {
                pending += trigger
                if (trigger == AutoSaveTrigger.BACKGROUND || trigger == AutoSaveTrigger.CRITICAL_EVENT_MONEY) {
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
