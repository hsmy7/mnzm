package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SkillStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 槽位上方职业晋升进度区 [ProfessionProgressSection] 渲染测试：
 * - 无弟子/已满级不渲染进度条与红色提示
 * - 已任命弟子显示晋升进度条（进度条用 testTag 定位）
 * - 数量已达标但境界不足 → 红色提示"弟子境界需到XX"
 * - 数量已达标但属性不足 → 红色提示"弟子炼丹（炼器）属性需到XX"
 * - 数量未达标不显示红色提示
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfessionProgressSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun disciple(
        realm: Int = 9,
        skills: SkillStats = SkillStats()
    ) = DiscipleAggregate.fromDisciple(Disciple(name = "测试弟子", realm = realm, skills = skills))

    private fun assertBarShown() {
        composeRule.onNodeWithTag(PROFESSION_PROMOTION_BAR_TAG).assertIsDisplayed()
    }

    private fun assertBarHidden() {
        composeRule.onNodeWithTag(PROFESSION_PROMOTION_BAR_TAG).assertDoesNotExist()
    }

    private fun assertNoRedHint() {
        composeRule.onAllNodes(hasText("弟子境界需到", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("弟子炼丹属性需到", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("弟子炼器属性需到", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `无弟子时不渲染进度条与红色提示`() {
        composeRule.setContent {
            ProfessionProgressSection(disciple = null, isAlchemy = true)
        }
        composeRule.waitForIdle()
        assertBarHidden()
        assertNoRedHint()
    }

    @Test
    fun `已任命弟子显示晋升进度条`() {
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 7,
                    skills = SkillStats(alchemyLevel = 1, alchemyPromotionCount = 12, pillRefining = 55)
                ),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        assertBarShown()
        assertNoRedHint()
    }

    @Test
    fun `已满级丹圣不显示进度条与提示`() {
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(skills = SkillStats(alchemyLevel = 5)),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        assertBarHidden()
        assertNoRedHint()
    }

    @Test
    fun `数量达标但境界不足显示境界红色提示`() {
        // level 1 需金丹（realm <= 7），炼气 realm 9 不足
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 9,
                    skills = SkillStats(alchemyLevel = 1, alchemyPromotionCount = 200, pillRefining = 55)
                ),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子境界需到金丹").assertIsDisplayed()
        assertBarShown()
        composeRule.onAllNodes(hasText("弟子炼丹属性需到", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `数量达标但属性不足显示属性红色提示`() {
        // level 1 需炼丹属性 55，实际 40 不足
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 7,
                    skills = SkillStats(alchemyLevel = 1, alchemyPromotionCount = 200, pillRefining = 40)
                ),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子炼丹属性需到55").assertIsDisplayed()
        assertBarShown()
        composeRule.onAllNodes(hasText("弟子境界需到", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `数量达标且境界属性均不足显示两条红色提示`() {
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 9,
                    skills = SkillStats(alchemyLevel = 1, alchemyPromotionCount = 200, pillRefining = 40)
                ),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子境界需到金丹").assertIsDisplayed()
        composeRule.onNodeWithText("弟子炼丹属性需到55").assertIsDisplayed()
        assertBarShown()
    }

    @Test
    fun `数量未达标不显示红色提示`() {
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 9,
                    skills = SkillStats(alchemyLevel = 1, alchemyPromotionCount = 50, pillRefining = 40)
                ),
                isAlchemy = true
            )
        }
        composeRule.waitForIdle()
        assertBarShown()
        assertNoRedHint()
    }

    @Test
    fun `锻造路径属性不足显示炼器红色提示`() {
        // 锻造职业 level 1 需炼器属性 55，实际 40 不足
        composeRule.setContent {
            ProfessionProgressSection(
                disciple = disciple(
                    realm = 7,
                    skills = SkillStats(forgeLevel = 1, forgePromotionCount = 200, artifactRefining = 40)
                ),
                isAlchemy = false
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子炼器属性需到55").assertIsDisplayed()
        assertBarShown()
        composeRule.onAllNodes(hasText("弟子境界需到", substring = true)).assertCountEquals(0)
    }
}
