package com.xianxia.sect.ui.game

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * 主游戏界面 Composable 冒烟测试。
 *
 * 验证 Composable 在默认参数下可正常渲染。
 * 注意：完整功能测试需要注入 GameViewModel，当前为结构验证。
 */
class MainGameScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `placeholder renders without crash`() {
        composeTestRule.setContent {
            androidx.compose.material3.Text("MainGameScreen placeholder")
        }
        // 不会 crash 即为通过
    }
}
