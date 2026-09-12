package com.xianxia.sect.core.dialog

import android.util.Log
import com.xianxia.sect.core.domain.dialog.DialogEntry
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.domain.dialog.DialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DialogManager] 的 Hilt 单例实现。
 *
 * 使用 [MutableStateFlow] 存储当前对话框，[open] / [close] 直接同步赋值，
 * 无挂起/Channel 间接跳转。
 */
@Singleton
class DialogManagerImpl @Inject constructor() : DialogManager {

    companion object {
        private const val TAG = "DialogManager"
    }

    private val _currentDialog = MutableStateFlow<DialogEntry?>(null)

    override val currentDialog: StateFlow<DialogEntry?> = _currentDialog.asStateFlow()

    override fun open(type: DialogType, params: Map<String, Any?>) {
        // Java interop 防御：Kotlin 非空类型对 Java 不强制
        requireNotNull(type) { "DialogType must not be null" }

        // None 表示"无对话框"，open(None) 等价于 close()
        if (type == DialogType.None) {
            Log.d(TAG, "open(None) → close")
            _currentDialog.value = null
            return
        }

        // 防御性拷贝：外部可变 Map 不影响 DialogEntry
        val safeParams: Map<String, Any?> = params ?: emptyMap()
        Log.d(TAG, "open: $type params=$safeParams")
        _currentDialog.value = DialogEntry(type, safeParams.toMap())
    }

    override fun close() {
        Log.d(TAG, "close")
        _currentDialog.value = null
    }

    override fun closeAll() {
        Log.d(TAG, "closeAll")
        _currentDialog.value = null
    }
}
