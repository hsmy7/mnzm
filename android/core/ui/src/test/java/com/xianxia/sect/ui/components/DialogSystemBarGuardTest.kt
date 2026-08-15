package com.xianxia.sect.ui.components

import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DialogSystemBarGuard IME 感知测试（2026-08 荣耀 GT 系列键盘频闪根治）：
 *
 * API < 35 上传统 SYSTEM_UI_FLAG_* 被 SystemUI 完整执行，Dialog 窗口的
 * HIDE_NAVIGATION 与键盘（IME）所需导航栏区域冲突会引发 insets 翻转
 * （放大器 B）。本守卫在键盘可见期间清除 HIDE_NAVIGATION 并恢复导航栏，
 * 键盘收起后恢复隐藏。
 *
 * 键盘状态经 [ImeVisibilityTracker.imeVisibilityExtractor] 注入驱动——
 * Robolectric 对 android.view.WindowInsets 的 ime 类型支持不全，
 * 状态机逻辑与框架 insets 解析解耦验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogSystemBarGuardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        ImeVisibilityTracker.resetForTest()
        SystemBarFreezeScope.resetForTest()
    }

    private fun imeInsetsWith(height: Int): WindowInsetsCompat =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .build()

    /** 挂载 DialogSystemBarGuard 并捕获 Dialog 窗口引用（与 isInsideDialogWindow 同款祖先遍历） */
    private fun mountDialogWithGuard(): Window {
        val dialogWindow = mutableStateOf<Window?>(null)
        composeRule.setContent {
            Dialog(onDismissRequest = {}) {
                DialogSystemBarGuard()
                val dialogView = LocalView.current
                DisposableEffect(Unit) {
                    dialogWindow.value = generateSequence(dialogView) {
                        it.parent as? View
                    }
                        .filterIsInstance<DialogWindowProvider>()
                        .firstOrNull()
                        ?.window
                    onDispose {}
                }
                Text("守卫测试")
            }
        }
        composeRule.waitForIdle()
        val window = dialogWindow.value
        checkNotNull(window) { "应捕获到 Dialog 窗口（DialogWindowProvider 祖先）" }
        return window
    }

    @Suppress("DEPRECATION")
    private fun hasHideNavigation(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0

    @Suppress("DEPRECATION")
    private fun hasFullscreen(window: Window): Boolean =
        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0

    @Test
    fun `挂载后 - 应用传统隐藏标志`() {
        val window = mountDialogWithGuard()
        assertTrue("挂载后应含 HIDE_NAVIGATION", hasHideNavigation(window))
        assertTrue("挂载后应含 FULLSCREEN", hasFullscreen(window))
    }

    @Test
    fun `键盘可见 - 清除 HIDE_NAVIGATION 冲突标志且保留 FULLSCREEN`() {
        val window = mountDialogWithGuard()
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(200), window)
        composeRule.waitForIdle()
        assertFalse(
            "键盘可见期间应清除与 IME 冲突的 HIDE_NAVIGATION",
            hasHideNavigation(window)
        )
        assertTrue("FULLSCREEN（状态栏）与键盘无冲突应保留", hasFullscreen(window))
    }

    @Test
    fun `键盘收起 - 恢复 HIDE_NAVIGATION 隐藏标志`() {
        val window = mountDialogWithGuard()
        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(200), window)
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(0), window)
        composeRule.waitForIdle()
        assertTrue("键盘收起后应恢复 HIDE_NAVIGATION", hasHideNavigation(window))
    }

    @Test
    fun `无键盘事件 - 隐藏标志保持不抖动`() {
        val window = mountDialogWithGuard()
        // 模拟无输入框对话框的常态：无 IME 翻转 → 标志恒为隐藏态
        ImeVisibilityTracker.imeVisibilityExtractor = { false }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(0), window)
        composeRule.waitForIdle()
        assertTrue("无键盘时保持隐藏态", hasHideNavigation(window))
    }

    @Test
    fun `窗口销毁 - 解除跟踪且全局可见性回落`() {
        val showDialog = mutableStateOf(true)
        val dialogWindow = mutableStateOf<Window?>(null)
        composeRule.setContent {
            if (showDialog.value) {
                Dialog(onDismissRequest = {}) {
                    DialogSystemBarGuard()
                    val dialogView = LocalView.current
                    DisposableEffect(Unit) {
                        dialogWindow.value = generateSequence(dialogView) {
                            it.parent as? View
                        }
                            .filterIsInstance<DialogWindowProvider>()
                            .firstOrNull()
                            ?.window
                        onDispose {}
                    }
                    Text("守卫测试")
                }
            }
        }
        composeRule.waitForIdle()
        val window = checkNotNull(dialogWindow.value) { "应捕获到 Dialog 窗口" }

        ImeVisibilityTracker.imeVisibilityExtractor = { true }
        ImeVisibilityTracker.onInsetsApplied(window.decorView, imeInsetsWith(200), window)
        assertTrue("键盘可见期间全局应可见", ImeVisibilityTracker.isImeVisible)

        composeRule.runOnUiThread { showDialog.value = false }
        composeRule.waitForIdle()
        assertFalse("窗口销毁后应解除跟踪", ImeVisibilityTracker.isImeVisible)
        assertFalse("销毁窗口应不可查询", ImeVisibilityTracker.isImeVisibleFor(window))
    }
}
