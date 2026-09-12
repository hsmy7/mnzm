package com.xianxia.sect.core.model

/**
 * 卸任门卫纯函数：根据弟子状态决定"卸任"按钮的分流行为。
 *
 * 分流语义（与 UI 按钮状态一一对应）：
 * - [Disabled]：按钮置灰（空闲/死亡）
 * - [CanResign]：直接卸任（普通职务槽位）
 * - [ConfirmRequired]：弹二次确认框后卸任（血炼=视为失败不返还材料、监牢=是否释放）
 * - [Blocked]：弹提示框告知无法卸任（任务中/秘境中/队伍中）
 */
sealed interface ResignGateResult {
    /** 空闲/死亡 → 按钮置灰，不可点击 */
    data object Disabled : ResignGateResult

    /** 普通职务槽位 → 直接卸任 */
    data object CanResign : ResignGateResult

    /** 需要二次确认（[message] 为确认框文案） */
    data class ConfirmRequired(val message: String) : ResignGateResult

    /** 无法卸任（[message] 为提示框文案） */
    data class Blocked(val message: String) : ResignGateResult
}

/**
 * 评估卸任按钮分流。
 *
 * @param status 弟子当前状态
 * @param isAlive 弟子是否存活
 */
fun evaluateResignGate(status: DiscipleStatus, isAlive: Boolean): ResignGateResult = when {
    !isAlive || status == DiscipleStatus.IDLE || status == DiscipleStatus.DEAD ->
        ResignGateResult.Disabled
    status == DiscipleStatus.REFINING ->
        ResignGateResult.ConfirmRequired("弟子正在血炼中，卸任将视为血炼失败且不返还材料")
    status == DiscipleStatus.REFLECTING ->
        ResignGateResult.ConfirmRequired("该弟子处于监牢中，是否释放？")
    status == DiscipleStatus.ON_MISSION ->
        ResignGateResult.Blocked("弟子正在执行任务中，无法卸任")
    status == DiscipleStatus.SECRET_REALM ->
        ResignGateResult.Blocked("弟子正在远古秘境探索中，无法卸任")
    status == DiscipleStatus.IN_TEAM ->
        ResignGateResult.Blocked("该弟子正在队伍中，无法卸任")
    else -> ResignGateResult.CanResign
}
