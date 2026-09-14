package com.xianxia.sect.ui.game.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 * 数量选择器组件行为测试（自绘数字面板）：
 * - 四向步进显示与禁用态
 * - 点击数字框弹出 NumberInputPanel（自绘面板，不弹系统 IME）
 * - 面板输入钳制（超上限截断）与确定/取消提交语义
 * - 外部数量变化同步（非编辑态）与初始超限钳制
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuantitySelectorFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 记录最后一次 onQuantityChange 回调值 */
    private fun launchSelector(quantity: Int, maxQuantity: Int): IntBox {
        val changed = IntBox(-1)
        composeRule.setContent {
            QuantitySelector(
                quantity = quantity,
                maxQuantity = maxQuantity,
                onQuantityChange = { changed.value = it }
            )
        }
        composeRule.waitForIdle()
        return changed
    }

    private class IntBox(var value: Int)

    // ── 步进按钮显示与禁用态 ────────────────────────────────────────────

    @Test
    fun `初始显示数量与四向步进按钮`() {
        launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").assertIsDisplayed()
        composeRule.onNodeWithText("−10").assertIsDisplayed()
        composeRule.onNodeWithText("−").assertIsDisplayed()
        composeRule.onNodeWithText("+").assertIsDisplayed()
        composeRule.onNodeWithText("+10").assertIsDisplayed()
    }

    @Test
    fun `数量为下限时减号步进按钮禁用`() {
        launchSelector(quantity = QUANTITY_MIN, maxQuantity = 10)
        composeRule.onNodeWithText("−10").assertIsNotEnabled()
        composeRule.onNodeWithText("−").assertIsNotEnabled()
    }

    @Test
    fun `数量为上限时加号步进按钮禁用`() {
        launchSelector(quantity = 10, maxQuantity = 10)
        composeRule.onNodeWithText("+").assertIsNotEnabled()
        composeRule.onNodeWithText("+10").assertIsNotEnabled()
    }

    // ── 自绘数字面板（绕开系统 IME）──────────────────────────────

    @Test
    fun `点击数字框弹出数字面板并隐藏大步进`() {
        launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performClick()
        composeRule.waitForIdle()
        // 面板出现（确定按钮为面板锚点）
        composeRule.onNodeWithText("确定").assertIsDisplayed()
        composeRule.onNodeWithTag("number_pad_key_5").assertIsDisplayed()
    }

    @Test
    fun `面板输入超上限确定后钳制为上限`() {
        val changed = launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performClick()
        composeRule.waitForIdle()
        // 面板初始显示当前数量 5；追加 9 和 9 → 钳制为 10
        composeRule.onNodeWithTag("number_pad_key_9").performClick()
        composeRule.onNodeWithTag("number_pad_key_9").performClick()
        composeRule.onNodeWithText("确定").performClick()
        composeRule.waitForIdle()
        assertEquals("超上限应钳制为 10", 10, changed.value)
        composeRule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun `面板点外取消不改变数量`() {
        val changed = launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("number_pad_key_7").performClick()
        // 点击面板外空白（scrim）取消
        composeRule.onRoot().performTouchInput { click(centerLeft) }
        composeRule.waitForIdle()
        assertEquals("未确认不改变数量", -1, changed.value)
    }

    // ── 步进提交 ────────────────────────────────────────────────────────

    @Test
    fun `点击步进按钮更新数量`() {
        val changed = launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()
        assertEquals(4, changed.value)
        composeRule.onNodeWithText("+10").performClick()
        composeRule.waitForIdle()
        assertEquals(10, changed.value)
    }

    // ── 外部数量变化同步 ────────────────────────────────────────────────

    @Test
    fun `外部数量变化同步显示`() {
        var quantity by mutableStateOf(5)
        composeRule.setContent {
            QuantitySelector(
                quantity = quantity,
                maxQuantity = 10,
                onQuantityChange = { quantity = it }
            )
        }
        composeRule.onNodeWithText("5").assertIsDisplayed()
        quantity = 8
        composeRule.waitForIdle()
        composeRule.onNodeWithText("8").assertIsDisplayed()
    }

    // ── 防御性钳制 ──────────────────────────────────────────────────────

    @Test
    fun `初始超限数量被钳制到上限`() {
        val changed = launchSelector(quantity = 15, maxQuantity = 10)
        assertEquals(10, changed.value)
        composeRule.onNodeWithText("10").assertIsDisplayed()
    }
}
