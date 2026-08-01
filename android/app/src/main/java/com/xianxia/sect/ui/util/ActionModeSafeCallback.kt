package com.xianxia.sect.ui.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.Window
import androidx.appcompat.view.menu.MenuBuilder

/**
 * 安全的 [Window.Callback] 包装器，拦截 [ActionMode]（FloatingActionMode /
 * 文本选择工具栏）生命周期，防止其在 Activity 销毁窗口期弹出 PopupWindow
 * 导致 [android.view.WindowManager.BadTokenException]（Bugly #3026）。
 *
 * 三层防御：
 * 1. [onWindowStartingActionMode] — FloatingActionMode 在框架回调返回 null 时
 *    即构造并 show() PopupWindow（早于 [onActionModeStarted] 派发），
 *    销毁期返回已取消的 stub ActionMode，令框架直接使用 stub、不创建
 *    FloatingActionMode，崩溃路径完全短路。
 * 2. [finishActiveActionMode] — 无条件进入销毁态（无论当前是否有活跃
 *    ActionMode），并结束已跟踪的 ActionMode。
 * 3. [resetForResume] — Activity 回到前台时复位销毁态，恢复文本选择能力
 *    （修复旧实现 onStop 置位后永不复位的隐患）。
 *
 * 注意：仅覆盖 Activity 窗口；Compose Dialog 独立窗口内的文本选择由
 * [com.xianxia.sect.ui.components.DialogFocusGuard] 在对话框销毁时清除焦点。
 */
class ActionModeSafeCallback(
    private val delegate: Window.Callback,
    private val appContext: Context
) : Window.Callback by delegate {

    private companion object {
        const val TAG = "ActionModeSafeCallback"
    }

    @Volatile
    var activeActionMode: ActionMode? = null
        private set

    @Volatile
    var isTearingDown: Boolean = false
        private set

    override fun onActionModeStarted(mode: ActionMode) {
        if (isTearingDown) {
            // 窗口正在销毁，立即结束新创建的 ActionMode 防止崩溃
            try {
                mode.finish()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "onActionModeStarted finish failed", e)
            }
            return
        }
        activeActionMode = mode
        delegate.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        if (activeActionMode === mode) {
            activeActionMode = null
        }
        delegate.onActionModeFinished(mode)
    }

    /**
     * 创建期拦截（API 23+ 双参重载）：[onActionModeStarted] 太晚——
     * FloatingActionMode 在框架调用本回调返回 null 时即构造并 show
     * PopupWindow（崩溃发生在窗口 token 失效后）。销毁期返回 stub，
     * 框架直接使用 stub 而非创建 FloatingActionMode。
     */
    override fun onWindowStartingActionMode(
        callback: ActionMode.Callback,
        type: Int
    ): ActionMode? {
        if (isTearingDown) {
            Log.d(TAG, "onWindowStartingActionMode(type=$type) 拦截，返回 stub ActionMode")
            return CanceledActionMode(appContext)
        }
        return delegate.onWindowStartingActionMode(callback, type)
    }

    @Suppress("DEPRECATION") // 覆盖 Java 弃用重载以覆盖旧系统路径
    @Deprecated("Deprecated in Java")
    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? {
        if (isTearingDown) {
            Log.d(TAG, "onWindowStartingActionMode 拦截，返回 stub ActionMode")
            return CanceledActionMode(appContext)
        }
        return delegate.onWindowStartingActionMode(callback)
    }

    /**
     * 进入销毁态并结束活跃 ActionMode。无论当前是否有活跃 ActionMode
     * 都置 [isTearingDown]，保证窗口拆卸期间新创建的 ActionMode 被拦截。
     */
    fun finishActiveActionMode() {
        isTearingDown = true
        activeActionMode?.let { mode ->
            try {
                mode.finish()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "finishActiveActionMode failed", e)
            }
        }
        activeActionMode = null
    }

    /** Activity 回到前台（onStart）：窗口 token 重新有效，恢复文本选择能力。 */
    fun resetForResume() {
        isTearingDown = false
    }

    /**
     * 销毁期拦截用的空 ActionMode：阻止框架创建 FloatingActionMode。
     * 抽象方法全部 no-op；getMenu 返回空的 [MenuBuilder]（框架可能遍历
     * 菜单，必须非 null）。
     */
    // TooManyFunctions：ActionMode 抽象方法必须全部覆写为 no-op，属接口契约
    @Suppress("TooManyFunctions")
    private class CanceledActionMode(appCtx: Context) : ActionMode() {
        // 注意：属性名不能是 menuInflater——合成 JVM getter getMenuInflater()
        // 会与下方 override fun getMenuInflater() 平台签名冲突
        private val lazyMenuInflater by lazy { MenuInflater(appCtx) }

        // RestrictedApi：Menu 接口无公开实现类，MenuBuilder 是框架/各库标准做法
        // （getMenu 返回 null 会 NPE，stub 必须提供非空 Menu）
        @SuppressLint("RestrictedApi")
        private val emptyMenu: Menu = MenuBuilder(appCtx)

        override fun setTitle(title: CharSequence?) = Unit

        override fun setTitle(resId: Int) = Unit

        override fun setSubtitle(subtitle: CharSequence?) = Unit

        override fun setSubtitle(resId: Int) = Unit

        override fun setCustomView(view: View?) = Unit

        override fun invalidate() = Unit

        override fun finish() = Unit

        override fun getMenu(): Menu = emptyMenu

        override fun getMenuInflater(): MenuInflater = lazyMenuInflater

        override fun getTitle(): CharSequence = ""

        override fun getSubtitle(): CharSequence = ""

        override fun getCustomView(): View? = null

        override fun getType(): Int = ActionMode.TYPE_FLOATING
    }
}
