package com.xianxia.sect.ui.components

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InlineStandardPromptDialog 内联覆盖层形态测试（2026-08 键盘频闪根治）：
 * - 内联渲染不创建平台 Dialog 窗口（消除 Dialog 窗口与 IME 交互）
 * - 按钮/遮罩/BackHandler 行为
 * - isInsideDialogWindow 窗口上下文检测
 *
 * IME insets 布局断言无法在 Robolectric 可靠模拟，由真机清单覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandardPromptDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 实现 DialogWindowProvider 的伪 Dialog 视图（用于窗口上下文检测单测） */
    private class FakeDialogWindowView(context: Context, private val windowRef: Window) :
        FrameLayout(context), DialogWindowProvider {
        override val window: Window
            get() = windowRef
    }

    // ── 纯逻辑：窗口上下文检测 ─────────────────────────────

    @Test
    fun `isInsideDialogWindow - 普通 Activity 视图返回 false`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(isInsideDialogWindow(View(activity)))
    }

    @Test
    fun `isInsideDialogWindow - 存在 DialogWindowProvider 祖先时返回 true`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialogView = FakeDialogWindowView(activity, activity.window)
        val child = View(activity)
        dialogView.addView(child)
        assertTrue(isInsideDialogWindow(dialogView))
        assertTrue(isInsideDialogWindow(child))
        assertFalse(isInsideDialogWindow(View(activity)))
    }

    // ── Compose 渲染形态 ───────────────────────────────────

    @Test
    fun `内联渲染不创建平台 Dialog 窗口 - rootView 即 Activity decorView`() {
        var rootView: View? = null
        var activityWindow: Window? = null
        composeRule.setContent {
            val activity = LocalActivity.current
            activityWindow = activity?.window
            rootView = LocalView.current.rootView
            InlineStandardPromptDialog(
                onDismissRequest = {},
                title = "测试标题",
                confirmLabel = "确定"
            ) {
                Text("对话框内容")
            }
        }
        composeRule.waitForIdle()
        assertFalse("内联覆盖层不应创建平台 Dialog 窗口", rootView is DialogWindowProvider)
        assertEquals("覆盖层应渲染在 Activity 窗口内", activityWindow?.decorView, rootView)
    }

    @Test
    fun `标题与确认取消按钮均渲染`() {
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = {},
                title = "测试标题",
                confirmLabel = "确定",
                dismissLabel = "取消"
            ) {
                Text("对话框内容")
            }
        }
        composeRule.onNodeWithText("测试标题").assertExists()
        composeRule.onNodeWithText("确定").assertExists()
        composeRule.onNodeWithText("取消").assertExists()
        composeRule.onNodeWithText("对话框内容").assertIsDisplayed()
    }

    @Test
    fun `点击确认按钮触发 onConfirm 且不触发 onDismiss`() {
        var confirmCount = 0
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                onConfirm = { confirmCount++ }
            ) {
                Text("内容")
            }
        }
        composeRule.onNodeWithText("确定").performClick()
        assertEquals(1, confirmCount)
        assertEquals(0, dismissCount)
    }

    @Test
    fun `点击取消按钮触发 onDismiss`() {
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                dismissLabel = "取消",
                onDismiss = { dismissCount++ }
            ) {
                Text("内容")
            }
        }
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `dismissOnClickOutside 为 true 时点击遮罩触发 onDismiss`() {
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                dismissOnClickOutside = true
            ) {
                Text("内容")
            }
        }
        // 点击屏幕角落（对话框内容之外的遮罩区域）
        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        assertEquals(1, dismissCount)
    }

    @Test
    fun `dismissOnClickOutside 为 false 时点击遮罩不触发 onDismiss`() {
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                dismissOnClickOutside = false
            ) {
                Text("内容")
            }
        }
        composeRule.onRoot().performTouchInput { click(Offset(5f, 5f)) }
        assertEquals(0, dismissCount)
    }

    @Test
    fun `showCloseButton 为 true 时正常渲染`() {
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = {},
                title = "测试标题",
                confirmLabel = "确定",
                showCloseButton = true
            ) {
                Text("内容")
            }
        }
        // 关闭按钮为图标组件无语义文本，点击行为由真机覆盖
        composeRule.onNodeWithText("测试标题").assertExists()
    }

    @Test
    fun `back 键触发 onDismissRequest`() {
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                dismissOnBackPress = true
            ) {
                Text("内容")
            }
        }
        // performKeyInput 注入的是 KeyEvent，不经过 OnBackPressedDispatcher；
        // 直接派发返回事件走真实 BackHandler 链路
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `dismissOnBackPress 为 false 时 back 键不触发 onDismiss`() {
        var dismissCount = 0
        composeRule.setContent {
            InlineStandardPromptDialog(
                onDismissRequest = { dismissCount++ },
                title = "测试标题",
                confirmLabel = "确定",
                dismissOnBackPress = false
            ) {
                Text("内容")
            }
        }
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertEquals(0, dismissCount)
    }
}
