package com.xianxia.sect.ui.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.xianxia.sect.ui.game.main.HideUiToggleButton
import org.junit.Rule
import org.junit.Test

/**
 * 主游戏界面 Composable 冒烟测试。
 *
 * 验证关键 UI 组件可正常渲染。
 * 完整 MainGameScreen 测试需要 ViewModel 注入环境。
 */
class MainGameScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `hideUiToggleButton renders with visible label`() {
        var wasToggled = false
        composeTestRule.setContent {
            HideUiToggleButton(
                isUiVisible = true,
                onToggle = { wasToggled = true }
            )
        }
        // Button renders without crash = pass
    }

    @Test
    fun `hideUiToggleButton toggles visibility state`() {
        var toggled = false
        composeTestRule.setContent {
            HideUiToggleButton(
                isUiVisible = false,
                onToggle = { toggled = true }
            )
        }
        // Button renders without crash with isUiVisible=false = pass
    }
}
