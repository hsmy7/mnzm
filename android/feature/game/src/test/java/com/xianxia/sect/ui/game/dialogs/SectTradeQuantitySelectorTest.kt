package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 宗门交易购买面板数量选择器接线测试：
 * 验证 SectTradeDialog 已接入统一 [com.xianxia.sect.ui.game.components.QuantitySelector]
 * （-10/+10 四向步进 + 键盘输入），上限正确传递商品库存。
 *
 * 组件内部逻辑（输入净化/钳制/编辑态）由 QuantitySelectorFlowTest 覆盖，
 * 本测试只守卫"宗门交易面板接入了组件且上限参数正确"这条接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SectTradeQuantitySelectorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val gameVm = mockk<GameViewModel>(relaxed = true)
    private val interactionVm = mockk<WorldMapInteractionViewModel>(relaxed = true)

    @Before
    fun setUp() {
        // mockk relaxed 无法为 StateFlow<Set> 泛型生成正确 value（CCE），手动 stub 收集源
        every { gameVm.watchedItemIds } returns MutableStateFlow(emptySet())
    }

    /** 渲染宗门交易弹窗：玩家与目标宗门好感度 100（至交，可购买全部品阶） */
    private fun launchSectTradeDialog(item: MerchantItem) {
        val player = WorldSect(id = "player", name = "玩家宗门", isPlayerSect = true)
        val sect = WorldSect(id = "sect-1", name = "测试宗门")
        val gameData = GameData(
            worldMapSects = listOf(player, sect),
            sectRelations = listOf(SectRelation(sectId1 = "player", sectId2 = "sect-1", favor = 100))
        )
        composeRule.setContent {
            SectTradeDialog(
                sect = sect,
                gameData = gameData,
                tradeItems = listOf(item),
                viewModel = gameVm,
                interactionViewModel = interactionVm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `宗门交易购买面板接入统一数量选择器 - 展示-10与+10并可步进`() {
        val item = MerchantItem(id = "m1", name = "聚气丹", type = "pill", rarity = 1, price = 10L, quantity = 50)
        launchSectTradeDialog(item)

        // 点击商品卡片 → 购买面板出现
        composeRule.onNodeWithText("聚气丹").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        // 统一数量选择器具备 -10/+10 四向步进按钮（旧版自建步进器无此功能）
        composeRule.onNodeWithText("−10").assertIsDisplayed()
        composeRule.onNodeWithText("+10").assertIsDisplayed()

        // 点击 +10 → 数量 1 → 11
        composeRule.onNodeWithText("+10").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("11").assertIsDisplayed()
    }

    @Test
    fun `宗门交易数量选择器上限正确钳制到商品库存`() {
        val item = MerchantItem(id = "m1", name = "聚气丹", type = "pill", rarity = 1, price = 10L, quantity = 5)
        launchSectTradeDialog(item)

        composeRule.onNodeWithText("聚气丹").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        // 库存仅 5：+10 步进被钳制到 5（与旧实现 coerceAtMost(item.quantity) 语义一致）。
        // 商品卡片角标同样显示 "5"，故用带 SetText 语义的输入框节点精确定位数量文本
        composeRule.onNodeWithText("+10").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("5") and hasSetTextAction()).assertIsDisplayed()

        // 已达上限：+10 与 + 步进按钮禁用
        composeRule.onNodeWithText("+10").assertIsNotEnabled()
    }
}
