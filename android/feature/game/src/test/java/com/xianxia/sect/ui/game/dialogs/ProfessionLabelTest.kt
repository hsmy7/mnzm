package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 槽位职业标签 [ProfessionLabel] 渲染测试（2026-08-09 无弟子时不显示职业）：
 * - level = null（无弟子）不渲染任何职业文本
 * - 任命弟子后按职业等级显示对应职业名（炼丹/炼器两系）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfessionLabelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `level 为 null 时不渲染任何职业文本 - 无弟子不显示`() {
        composeRule.setContent {
            ProfessionLabel(level = null, isAlchemy = true)
        }
        composeRule.waitForIdle()
        // 全职业名与"无职业"兜底文本均不应存在
        composeRule.onNodeWithText("无职业").assertDoesNotExist()
        composeRule.onNodeWithText("炼丹师").assertDoesNotExist()
        composeRule.onNodeWithText("炼丹大师").assertDoesNotExist()
        composeRule.onNodeWithText("丹圣").assertDoesNotExist()
    }

    @Test
    fun `level 非空时炼丹职业显示对应等级名`() {
        composeRule.setContent {
            ProfessionLabel(level = 2, isAlchemy = true)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("炼丹大师").assertIsDisplayed()
    }

    @Test
    fun `level 非空时炼器职业显示对应等级名`() {
        composeRule.setContent {
            ProfessionLabel(level = 2, isAlchemy = false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("炼器大师").assertIsDisplayed()
    }

    @Test
    fun `已任命但职业等级为 0 仍显示无职业`() {
        composeRule.setContent {
            ProfessionLabel(level = 0, isAlchemy = true)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("无职业").assertIsDisplayed()
    }
}
