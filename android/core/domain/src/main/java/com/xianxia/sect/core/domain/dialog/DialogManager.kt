package com.xianxia.sect.core.domain.dialog

import kotlinx.coroutines.flow.StateFlow

/**
 * 对话框状态管理器 — 单一真相源。
 *
 * 非挂起、零 Android 依赖。所有 [open] / [close] 调用直接同步更新 StateFlow，
 * 无 Channel / EventBus 等间接跳转。
 *
 * 特性：
 * - 单一可见对话框（不支持栈 — 上层通过本地 remember 状态管理子对话框）
 * - [open] 时自动 close 当前对话框
 * - [close] / [closeAll] 幂等，无对话框时调用安全
 *
 * iOS 接入时直接实现此接口即可。
 */
interface DialogManager {
    /** 当前对话框（null = 无） */
    val currentDialog: StateFlow<DialogEntry?>

    /**
     * 打开对话框。如已有对话框则先关闭当前。
     * @param type 对话框类型
     * @param params 额外参数（如预填数据、回调标记等）
     */
    fun open(type: DialogType, params: Map<String, Any?> = emptyMap())

    /** 关闭当前对话框。无对话框时安全调用。 */
    fun close()

    /** 关闭所有对话框。当前行为等价于 [close]（无栈）。 */
    fun closeAll()
}
