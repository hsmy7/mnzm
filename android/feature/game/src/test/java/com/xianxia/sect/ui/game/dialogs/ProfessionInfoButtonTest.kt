package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 炼丹/锻造职业等级详情按钮 [ProfessionInfoButton] 渲染测试：
 * - 点击详情按钮弹出对应职业等级弹窗（炼丹等级/锻造等级）
 * - 弹窗展示等级条目（6 级内容完整性由 ProfessionRulesTest 纯函数测试覆盖）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfessionInfoButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `点击详情按钮弹出炼丹等级弹窗`() {
        composeRule.setContent {
            ProfessionInfoButton(isAlchemy = true)
        }
        composeRule.onNodeWithContentDescription("详情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("炼丹等级").assertIsDisplayed()
        composeRule.onNodeWithText("无职业").assertIsDisplayed()
    }

    @Test
    fun `点击详情按钮弹出锻造等级弹窗`() {
        composeRule.setContent {
            ProfessionInfoButton(isAlchemy = false)
        }
        composeRule.onNodeWithContentDescription("详情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("锻造等级").assertIsDisplayed()
        composeRule.onNodeWithText("无职业").assertIsDisplayed()
    }
}
