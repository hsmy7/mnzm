package com.xianxia.sect.ui.game.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数量选择器组件行为测试（常驻输入框重构，2026-08-08 键盘自动收起根治）：
 * - 四向步进显示与禁用态
 * - 点击数字框进入编辑态（−10/+10 隐藏）
 * - 输入净化（超上限截断/非法字符过滤）
 * - 失焦/Done 提交
 * - 外部数量变化同步（非编辑态）与编辑态不覆盖用户输入
 * - 初始超限钳制
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

    // ── 编辑态切换 ──────────────────────────────────────────────────────

    @Test
    fun `点击数字框进入编辑态隐藏大步进按钮`() {
        launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performClick()
        composeRule.waitForIdle()
        // 编辑态仅保留 [−][输入框][+]：键盘弹出空间有限，避免步进作用于未提交文本
        composeRule.onNodeWithText("−10").assertDoesNotExist()
        composeRule.onNodeWithText("+10").assertDoesNotExist()
        composeRule.onNodeWithText("−").assertIsDisplayed()
        composeRule.onNodeWithText("+").assertIsDisplayed()
    }

    // ── 输入净化 ────────────────────────────────────────────────────────

    @Test
    fun `编辑态输入超上限实时截断为上限`() {
        val changed = launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performTextReplacement("99")
        composeRule.waitForIdle()
        assertEquals(10, changed.value)
        composeRule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun `编辑态输入非法字符被过滤为空文本`() {
        val changed = launchSelector(quantity = 5, maxQuantity = 10)
        composeRule.onNodeWithText("5").performTextReplacement("abc")
        composeRule.waitForIdle()
        assertEquals(QUANTITY_MIN, changed.value)
        composeRule.onNodeWithText("5").assertDoesNotExist()
    }

    // ── 提交 ────────────────────────────────────────────────────────────

    @Test
    fun `Done 提交输入并退出编辑态`() {
        // 外部 State 驱动（IntBox 不触发重组，commit 会把输入串覆盖回旧值）
        var quantity by mutableStateOf(5)
        composeRule.setContent {
            QuantitySelector(
                quantity = quantity,
                maxQuantity = 10,
                onQuantityChange = { quantity = it }
            )
        }
        composeRule.onNodeWithText("5").performTextReplacement("7")
        composeRule.onNodeWithText("7").performImeAction()
        composeRule.waitForIdle()
        assertEquals(7, quantity)
        composeRule.onNodeWithText("7").assertIsDisplayed()
        // 退出编辑态后大步进恢复
        composeRule.onNodeWithText("+10").assertIsDisplayed()
    }

    @Test
    fun `点击步进按钮失焦先提交输入再步进`() {
        // 外部 State 驱动（IntBox 捕获不触发重组，step 会读到旧 quantity）
        var quantity by mutableStateOf(5)
        composeRule.setContent {
            QuantitySelector(
                quantity = quantity,
                maxQuantity = 10,
                onQuantityChange = { quantity = it }
            )
        }
        // 编辑态输入 7 → 点击 −：先失焦提交 7，再执行步进 −1 → 最终 6
        composeRule.onNodeWithText("5").performTextReplacement("7")
        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()
        assertEquals(6, quantity)
        composeRule.onNodeWithText("6").assertIsDisplayed()
    }

    // ── 外部数量变化同步 ────────────────────────────────────────────────

    @Test
    fun `外部数量变化非编辑态同步显示`() {
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

    @Test
    fun `外部数量变化编辑态不覆盖用户输入串`() {
        var quantity by mutableStateOf(5)
        composeRule.setContent {
            QuantitySelector(
                quantity = quantity,
                maxQuantity = 10,
                onQuantityChange = { quantity = it }
            )
        }
        composeRule.onNodeWithText("5").performTextReplacement("7")
        quantity = 8
        composeRule.waitForIdle()
        // 编辑态跳过外部同步，输入串保持用户输入
        composeRule.onNodeWithText("7").assertIsDisplayed()
        composeRule.onNodeWithText("8").assertDoesNotExist()
    }

    // ── 防御性钳制 ──────────────────────────────────────────────────────

    @Test
    fun `初始超限数量被钳制到上限`() {
        val changed = launchSelector(quantity = 15, maxQuantity = 10)
        assertEquals(10, changed.value)
        composeRule.onNodeWithText("10").assertIsDisplayed()
    }
}
