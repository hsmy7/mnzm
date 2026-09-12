package com.xianxia.sect.ui.game.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NumberInputPanel 自绘数字输入面板测试（数量输入绕开系统 IME，
 * 行业"自绘 UI + 事件流"范式）：
 * 初始值显示、数字按键、退格、清空、确定回调（钳制）、空输入兜底、取消。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberInputPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun launchPanel(
        initialValue: Int,
        maxQuantity: Int,
        onConfirm: (Int) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeRule.setContent {
            NumberInputPanel(
                initialValue = initialValue,
                maxQuantity = maxQuantity,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
        composeRule.waitForIdle()
    }

    private fun tapKey(key: String) {
        composeRule.onNodeWithTag("number_pad_key_$key").performClick()
        composeRule.waitForIdle()
    }

    private fun displayText(): String {
        // 显示值语义节点文本（testTag number_input_display；Text 语义在 unmerged 树，
        // 需 useUnmergedTree 定位——Text 自身节点持 testTag + Text 语义）
        return composeRule.onNodeWithTag("number_input_display", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
    }

    @Test
    fun `初始值显示`() {
        launchPanel(initialValue = 5, maxQuantity = 10)
        assertEquals("5", displayText())
    }

    @Test
    fun `数字按键追加输入`() {
        launchPanel(initialValue = 0, maxQuantity = 100)
        tapKey("5")
        tapKey("5")
        assertEquals("55", displayText())
    }

    @Test
    fun `退格删除末位`() {
        launchPanel(initialValue = 0, maxQuantity = 999)
        tapKey("1")
        tapKey("2")
        tapKey("3")
        tapKey("⌫")
        assertEquals("12", displayText())
    }

    @Test
    fun `清空后输入归零显示`() {
        launchPanel(initialValue = 0, maxQuantity = 100)
        tapKey("7")
        tapKey("C")
        assertEquals("0", displayText())
    }

    @Test
    fun `确定回调返回钳制后的数量`() {
        var confirmed = -1
        launchPanel(initialValue = 0, maxQuantity = 10, onConfirm = { confirmed = it })
        tapKey("9")
        tapKey("9") // 99 > 10 → 钳制为 10
        composeRule.onNodeWithText("确定").performClick()
        composeRule.waitForIdle()
        assertEquals("超上限应钳制到 maxQuantity", 10, confirmed)
    }

    @Test
    fun `空输入确定兜底为 1`() {
        var confirmed = -1
        launchPanel(initialValue = 0, maxQuantity = 10, onConfirm = { confirmed = it })
        tapKey("C") // 清空
        composeRule.onNodeWithText("确定").performClick()
        composeRule.waitForIdle()
        assertEquals("空输入应兜底为 QUANTITY_MIN", QUANTITY_MIN, confirmed)
    }

    @Test
    fun `点空白触发取消回调`() {
        var dismissed = false
        launchPanel(initialValue = 5, maxQuantity = 10, onDismiss = { dismissed = true })
        // 点击面板外空白区域（scrim）：通过点击面板外布局位置触发
        composeRule.onRoot().performTouchInput { click(centerLeft) }
        composeRule.waitForIdle()
        assertEquals(true, dismissed)
    }
}
