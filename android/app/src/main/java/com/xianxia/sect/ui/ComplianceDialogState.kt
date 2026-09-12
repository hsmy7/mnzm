package com.xianxia.sect.ui

/**
 * 防沉迷合规限制对话框状态（共享类型）。
 *
 * 游戏内防沉迷回调（时长限制/时间限制/年龄限制）需要与主界面同一套展示类型，
 * 定义为 app 层共享 sealed class，`MainActivity` 与 `GameActivity` 共用。
 */
sealed class ComplianceDialogState {
    /** 时间/时长限制（强制退出/切换账号） */
    data class Restrict(val title: String, val message: String) : ComplianceDialogState()

    /** 适龄限制 */
    data object AgeLimit : ComplianceDialogState()
}
