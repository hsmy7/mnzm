package com.xianxia.sect.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 关键事件类别（方案 §2.5 五类的 v2 映射；MIGRATION 类已随 SS0 迁移链退役不存在）。 */
enum class CriticalSaveKind {

    /** 涉钱：玉符流水 append（购买扣费/广告发放/时长发放）——立即冲刷语义。 */
    MONEY,

    /** 不可逆随机结果：寻访出货（角色碎片/高品阶物品入库）。 */
    GACHA,

    /** 不可逆消耗：碎片入账（含满档自动升星）等消耗与产出同窗口的事件。 */
    IRREVERSIBLE_CONSUME,

    /** 唯一性里程碑：天劫首次通关奖励领取、突破（渡劫）成功。 */
    MILESTONE
}

/**
 * 关键事件自动存档请求总线（方案 §2.5"关键事件立即落盘"的事件传输面）。
 *
 * 职责边界：事件源（core:engine 服务 / feature:game ViewModel）只经本总线**发事件**，
 * 不直接持有 SaveOrchestrator——编排器归 SaveLoadViewModel 所有，本总线把两侧
 * 解耦（core 层事件点 → SaveLoadViewModel 订阅 → `SaveOrchestrator.submit`）。
 * 事件防风暴语义（合并窗/立即冲刷）全部由编排器承担，本总线只保证不丢事件
 * （[extraBufferCapacity] 远超事件产生速率，emit 即达）。
 *
 * 涉钱同步落盘的应答面：涉钱事件点的调用方需承诺「事件方法返回前数据已落盘」，
 * 经 [awaitNextSaveCompletion] 挂起等待**本事件之后完成的任意一次保存**——
 * 快照构造晚于流水 append，故该次保存必然包含本笔流水。
 *
 * 线程模型：[notify]/[notifySaveCompleted] 非挂起、任意线程可调（引擎线程/主线程/SDK
 * 回调线程）；[awaitNextSaveCompletion] 挂起，可在任意协程内等待。
 */
@Singleton
class CriticalSaveEventBus @Inject constructor() {

    /** 关键事件请求流（SaveLoadViewModel 订阅并转交编排器）。 */
    private val _events = MutableSharedFlow<CriticalSaveKind>(extraBufferCapacity = EXTRA_BUFFER_CAPACITY)
    val events: SharedFlow<CriticalSaveKind> = _events.asSharedFlow()

    /** 保存完成信号流（performLocalSave 收尾发出，涉钱等待方消费）。 */
    private val _saveCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = SAVE_COMPLETED_BUFFER_CAPACITY)
    val saveCompleted: SharedFlow<Unit> = _saveCompleted.asSharedFlow()

    /**
     * 发出一个关键事件存档请求（非挂起；缓冲容量内不丢，超出返回 false——
     * 事件频率为玩家操作级，缓冲溢出在结构上不可达，false 仅作诊断留痕）。
     */
    fun notify(kind: CriticalSaveKind): Boolean = _events.tryEmit(kind)

    /** 保存流程收尾信号（成功与失败都发：等待方语义是"保存链已走完"）。 */
    fun notifySaveCompleted() {
        _saveCompleted.tryEmit(Unit)
    }

    /**
     * 挂起等待下一次保存完成（涉钱同步落盘的应答面）。
     *
     * @return true = 等到了保存完成（事件数据已随该次保存落盘）；
     *         false = 超时（保存被前置门控/互斥守卫拒绝等，数据由后续节拍兜底）
     */
    suspend fun awaitNextSaveCompletion(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            _saveCompleted.first()
            true
        } ?: false

    private companion object {
        /** 事件缓冲：十连/连续购买等爆发场景的峰值容量（玩家操作级速率远低于此）。 */
        const val EXTRA_BUFFER_CAPACITY = 64

        /** 保存完成信号缓冲（同一时刻至多一条保存链在跑——isSaving 互斥保证）。 */
        const val SAVE_COMPLETED_BUFFER_CAPACITY = 8
    }
}

/**
 * 涉钱事件同步落盘的默认等待上限。
 *
 * 覆盖面：500ms 合并窗冲突 + isSaving 互斥下等待在途保存完成 + 增量落盘毫秒级耗时；
 * 超时后事件方法正常返回（数据由 10s 节拍兜底），购买 UI 不被存储故障拖死。
 */
const val MONEY_SAVE_ACK_TIMEOUT_MS = 5_000L
