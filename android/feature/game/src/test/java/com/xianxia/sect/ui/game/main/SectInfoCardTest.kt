package com.xianxia.sect.ui.game.main

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.xianxia.sect.core.SectLevel
import org.junit.Rule
import org.junit.Test

/**
 * SectInfoCard 组件冒烟测试。
 *
 * 验证宗门信息卡片在默认参数下可正常渲染。
 */
class SectInfoCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `sectInfoCard displays sect name and year`() {
        composeTestRule.setContent {
            SectInfoCard(
                sectName = "青云宗",
                gameYear = 1,
                gameMonth = 3,
                gamePhase = 1,
                lowStones = 1000L,
                midStones = 500L,
                highStones = 100L,
                discipleCount = 10,
                combatPower = 5000L,
                sectLevel = SectLevel.MEDIUM
            )
        }

        composeTestRule.onNodeWithText("青云宗").assertIsDisplayed()
        composeTestRule.onNodeWithText("1年3月上旬").assertIsDisplayed()
        composeTestRule.onNodeWithText("弟子 10").assertIsDisplayed()
    }

    @Test
    fun `sectInfoCard displays spirit stones`() {
        composeTestRule.setContent {
            SectInfoCard(
                sectName = "测试宗门",
                gameYear = 5,
                gameMonth = 6,
                gamePhase = 0,
                lowStones = 99999L,
                midStones = 8888L,
                highStones = 777L,
                discipleCount = 25,
                combatPower = 15000L,
                sectLevel = SectLevel.LARGE
            )
        }

        composeTestRule.onNodeWithText("测试宗门").assertIsDisplayed()
        composeTestRule.onNodeWithText("弟子 25").assertIsDisplayed()
    }

    @Test
    fun `sectInfoCard shows placeholder values`() {
        composeTestRule.setContent {
            SectInfoCard(
                sectName = "青云宗",
                gameYear = 0,
                gameMonth = 1,
                gamePhase = 0,
                lowStones = 0L,
                midStones = 0L,
                highStones = 0L,
                discipleCount = 0,
                combatPower = 0L,
                sectLevel = SectLevel.SMALL
            )
        }

        composeTestRule.onNodeWithText("青云宗").assertIsDisplayed()
    }
}
