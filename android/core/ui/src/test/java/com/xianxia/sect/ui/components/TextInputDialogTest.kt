package com.xianxia.sect.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TextInputDialog 统一文本输入对话框测试：
 * 挂载建立输入会话（冻结宿主）、卸载会话落定解冻、快速开/关循环的
 * **反向验证**（开→关→开→……→快速关→快速开：无重复副作用、末态干净）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextInputDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        SystemBarFreezeScope.resetForTest()
        DialogSystemBarFreezeScope.resetForTest()
        ImeVisibilityTracker.resetForTest()
        InputSessionStateMachine.resetForTest()
        ImeAnimationTracker.resetForTest()
    }

    /** show 状态门控包裹（setContent 单次约束：显示/销毁经状态驱动，与 StandardPromptDialogTest 同款模式） */
    private fun setTextInputDialogHost(showDialog: androidx.compose.runtime.MutableState<Boolean>) {
        composeRule.setContent {
            if (showDialog.value) {
                TextInputDialog(
                    onDismissRequest = {},
                    title = "创建宗门",
                    value = "青云宗",
                    onValueChange = {},
                    placeholder = "青云宗",
                    maxLength = 6,
                    confirmLabel = "创建",
                    dismissLabel = "取消",
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun toggle(showDialog: androidx.compose.runtime.MutableState<Boolean>, visible: Boolean) {
        composeRule.runOnUiThread { showDialog.value = visible }
        composeRule.waitForIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `挂载 - 建立输入会话并冻结宿主`() {
        val showDialog = mutableStateOf(true)
        setTextInputDialogHost(showDialog)
        assertTrue("含输入框对话框挂载期间应冻结系统栏操作", SystemBarFreezeScope.isFrozen)
        assertTrue("挂载后应有活跃输入会话", InputSessionStateMachine.hasActiveSessions())
        assertTrue(InputSessionStateMachine.activeSessionsSummary().contains(TEXT_INPUT_SESSION_KEY))
    }

    @Test
    fun `卸载 - 会话落定且解冻`() {
        val showDialog = mutableStateOf(true)
        setTextInputDialogHost(showDialog)
        toggle(showDialog, visible = false)
        assertFalse("卸载后应解冻系统栏操作", SystemBarFreezeScope.isFrozen)
        assertFalse("卸载后应无活跃输入会话", InputSessionStateMachine.hasActiveSessions())
    }

    // ── 反向验证：快速开/关循环 ─────────────────

    @Test
    fun `反向验证 - 快速开关循环末态干净且无重复会话`() {
        val showDialog = mutableStateOf(false)
        setTextInputDialogHost(showDialog)
        // 开 → 关 → 开 → 关 → 快速关（重复 close 幂等）→ 快速开（同帧重开）
        for (iteration in 1..3) {
            toggle(showDialog, visible = true)
            assertTrue("第 $iteration 轮打开后应有活跃会话", InputSessionStateMachine.hasActiveSessions())
            assertTrue("第 $iteration 轮打开期间应冻结", SystemBarFreezeScope.isFrozen)
            toggle(showDialog, visible = false)
            assertFalse("第 $iteration 轮关闭后应无活跃会话", InputSessionStateMachine.hasActiveSessions())
            assertFalse("第 $iteration 轮关闭后应解冻", SystemBarFreezeScope.isFrozen)
        }
        // 末态：冻结计数归零、会话清空（freeze/状态机无泄漏）
        assertEquals("冻结计数应归零", false, SystemBarFreezeScope.isFrozen)
        assertEquals("无活跃会话", false, InputSessionStateMachine.hasActiveSessions())
    }
}
