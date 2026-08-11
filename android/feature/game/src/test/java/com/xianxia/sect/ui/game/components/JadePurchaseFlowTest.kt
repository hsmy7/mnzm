package com.xianxia.sect.ui.game.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 玉符购买弹窗（突破率/商人刷新共用）UI 测试（2026-08-11 弹窗渲染位置修复后补盲区）：
 * - 组合即渲染：标题/描述/底部"消耗1玉符"小字/「消耗玉符」按钮全部可见
 * - 点击消耗 → Success → 触发 onDismiss
 * - 点击消耗 → Insufficient → 平台 StandardPromptDialog 显示不足文案
 *   （平台 Dialog 独立 Window 可见性守卫，防嵌套覆盖层裁剪回归——TraitWash 同源）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JadePurchaseFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `组合后标题描述小字与按钮全部可见`() {
        composeRule.setContent {
            JadePurchaseFlow(
                title = "提高突破率",
                description = "消耗1玉符提高弟子突破率15%，最多提高两次",
                jadeSymbols = 5,
                insufficientText = "玉符不足，无法提高突破率",
                purchase = { JadePurchaseOutcome.Success },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提高突破率").assertIsDisplayed()
        composeRule.onNodeWithText("消耗1玉符提高弟子突破率15%，最多提高两次").assertIsDisplayed()
        composeRule.onNodeWithText("消耗1玉符").assertIsDisplayed()
        composeRule.onNodeWithText("消耗玉符").assertIsDisplayed()
    }

    @Test
    fun `点击消耗玉符成功时触发 onDismiss`() {
        var dismissed = false
        composeRule.setContent {
            JadePurchaseFlow(
                title = "提高突破率",
                description = "描述",
                jadeSymbols = 5,
                insufficientText = "玉符不足，无法提高突破率",
                purchase = { JadePurchaseOutcome.Success },
                onDismiss = { dismissed = true }
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消耗玉符").performClick()
        composeRule.waitForIdle()
        assertTrue("成功购买后应触发 onDismiss 关闭弹窗", dismissed)
    }

    @Test
    fun `玉符不足时点击显示不足提示框`() {
        composeRule.setContent {
            JadePurchaseFlow(
                title = "获取刷新次数",
                description = "描述",
                jadeSymbols = 0,
                insufficientText = "玉符不足，无法获取刷新次数",
                purchase = { JadePurchaseOutcome.Insufficient },
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消耗玉符").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("玉符不足，无法获取刷新次数").assertIsDisplayed()
    }
}
