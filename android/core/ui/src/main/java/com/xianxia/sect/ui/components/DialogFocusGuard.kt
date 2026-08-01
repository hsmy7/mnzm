package com.xianxia.sect.ui.components

import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 对话框窗口销毁前清除焦点并隐藏软键盘。
 *
 * 防止文本选择 FloatingActionMode 在窗口 token 失效后尝试弹出 PopupWindow
 * 导致 BadTokenException（Bugly #3026）。必须在 Dialog 内容组合内调用
 * （[LocalView] 解析为 Dialog 窗口视图）；Activity 内联布局也可用。
 *
 * 与 [DialogSoftInputGuard]（窗口 softInputMode 切换）职责互补：
 * 本守卫负责组合销毁时的焦点/键盘清理。
 */
@Composable
fun DialogFocusGuard() {
    val dialogView = LocalView.current
    DisposableEffect(Unit) {
        onDispose {
            dialogView.clearFocusAndHideKeyboard()
        }
    }
}

/**
 * 清除焦点并隐藏软键盘（若窗口 token 仍有效）。
 *
 * 提取为顶层函数便于 Robolectric 单测。窗口 token 失效时
 * [InputMethodManager.hideSoftInputFromWindow] 可能抛异常，安全忽略。
 */
internal fun View.clearFocusAndHideKeyboard() {
    clearFocus()
    try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    } catch (e: Exception) {
        Log.w("DialogFocusGuard", "hideSoftInputFromWindow failed: ${e.message}")
    }
}
