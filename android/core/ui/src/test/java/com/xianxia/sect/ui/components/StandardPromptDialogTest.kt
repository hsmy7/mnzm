package com.xianxia.sect.ui.components

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
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

    @After
    fun tearDown() {
        // 冻结作用域为全局单例，测试间隔离，防跨用例污染
        SystemBarFreezeScope.resetForTest()
    }

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

    // ── 平台 Dialog 窗口内嵌套渲染（57352e02 回归教训：SettingsTab 兑换码 0 高度不可见）──
    // 内联覆盖层不创建独立窗口，作为普通布局节点参与宿主布局：
    // - 渲染在 Box 内与内容重叠 → 可见（修复后结构，用例 A 守卫）
    // - 渲染在 Column 中 fillMaxSize 兄弟节点之后 → 剩余高度 0 → 不可见（回归机制，用例 B 文档）

    @Test
    fun `平台Dialog窗口内容区Box内渲染内联覆盖层可见 - 修复结构守卫`() {
        composeRule.setContent {
            Dialog(onDismissRequest = {}) {
                Box(Modifier.fillMaxSize()) {
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "兑换码",
                        confirmLabel = "兑换",
                        dismissLabel = "取消"
                    ) {
                        Text("请输入兑换码")
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("兑换码").assertIsDisplayed()
    }

    @Test
    fun `平台Dialog窗口内容区Column兄弟节点渲染内联覆盖层不可见 - 0高度回归文档`() {
        composeRule.setContent {
            Dialog(onDismissRequest = {}) {
                Column(Modifier.fillMaxSize()) {
                    // 模拟修复前 SettingsTab 结构：根 Box(fillMaxSize) 作为首子节点占满全部高度
                    Box(Modifier.fillMaxSize()) {}
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "兑换码",
                        confirmLabel = "兑换",
                        dismissLabel = "取消"
                    ) {
                        Text("请输入兑换码")
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // 后续兄弟节点测量时 maxHeight = 0 → 覆盖层高度归零不可见
        // （57352e02 将 InlineStandardPromptDialog 改回内联覆盖层时漏适配
        //   SettingsTab.RedeemCodeDialog 的根因机制，v4.00.92 兑换码不弹窗）
        composeRule.onNodeWithText("兑换码").assertIsNotDisplayed()
    }

    // ── 系统栏冻结接线（2026-08 荣耀 X70 键盘频闪根治）──
    // 含输入框的对话框挂载期间冻结宿主窗口系统栏操作，销毁后解冻。

    @Test
    fun `freezeSystemBars 为 true 时挂载进入冻结销毁退出冻结`() {
        assertFalse("测试前应为未冻结状态", SystemBarFreezeScope.isFrozen)
        val showDialog = mutableStateOf(true)
        composeRule.setContent {
            if (showDialog.value) {
                InlineStandardPromptDialog(
                    onDismissRequest = {},
                    title = "创建宗门",
                    confirmLabel = "创建",
                    dismissLabel = "取消",
                    freezeSystemBars = true
                ) {
                    Text("输入框内容")
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("含输入框对话框挂载期间应冻结", SystemBarFreezeScope.isFrozen)

        composeRule.runOnUiThread { showDialog.value = false }
        composeRule.waitForIdle()
        assertFalse("对话框销毁后应解冻", SystemBarFreezeScope.isFrozen)
    }

    @Test
    fun `freezeSystemBars 默认 false 时不冻结`() {
        val showDialog = mutableStateOf(true)
        composeRule.setContent {
            if (showDialog.value) {
                InlineStandardPromptDialog(
                    onDismissRequest = {},
                    title = "提示框",
                    confirmLabel = "确定"
                ) {
                    Text("内容")
                }
            }
        }
        composeRule.waitForIdle()
        assertFalse("无输入框提示框不应冻结", SystemBarFreezeScope.isFrozen)
        composeRule.runOnUiThread { showDialog.value = false }
        composeRule.waitForIdle()
        assertFalse("卸载后仍应未冻结", SystemBarFreezeScope.isFrozen)
    }

    @Test
    fun `freezeSystemBars 为 true 时解冻触发监听器回调`() {
        var unfreezeCount = 0
        val listener: () -> Unit = { unfreezeCount++ }
        SystemBarFreezeScope.addOnUnfreezeListener(listener)
        val showDialog = mutableStateOf(true)
        try {
            composeRule.setContent {
                if (showDialog.value) {
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "创建宗门",
                        confirmLabel = "创建",
                        freezeSystemBars = true
                    ) {
                        Text("输入框内容")
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnUiThread { showDialog.value = false }
            composeRule.waitForIdle()
            assertEquals("解冻应通知监听器（宿主恢复系统栏隐藏）", 1, unfreezeCount)
        } finally {
            SystemBarFreezeScope.removeOnUnfreezeListener(listener)
            SystemBarFreezeScope.resetForTest()
        }
    }

    // ── UnifiedGameDialog 窗口级 overlay 槽位（2026-08-08 兑换码遮罩全屏根治）──
    // 兑换码弹窗从 SettingsTab 内容区内联渲染迁移至窗口级 overlay 槽位：
    // overlay 在 frame 之后渲染（外层 BoxScope 内 z 序最高），
    // 内联覆盖层 fillMaxSize 覆盖整个窗口（含 header 与内容区 padding）

    @Test
    fun `UnifiedGameDialog overlay 槽位内内联覆盖层可见 - 窗口级遮罩全屏守卫`() {
        composeRule.setContent {
            UnifiedGameDialog(
                onDismissRequest = {},
                title = "设置",
                mode = DialogMode.Full,
                scrimEnabled = false,
                overlay = {
                    InlineStandardPromptDialog(
                        onDismissRequest = {},
                        title = "兑换码",
                        confirmLabel = "兑换",
                        dismissLabel = "取消"
                    ) {
                        Text("请输入兑换码")
                    }
                }
            ) {
                Text("设置内容")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("兑换码").assertIsDisplayed()
        composeRule.onNodeWithText("请输入兑换码").assertIsDisplayed()
    }
}
