package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.xianxia.sect.core.engine.MerchantRefreshResult
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.SpriteCategory
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.game.GameViewModel
import io.mockk.coEvery
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
 * 商人界面玉符购买弹窗 UI 测试（2026-08-11 真机实测「+」按钮不弹后补盲区）：
 * 点击 headerActions 的「+」按钮（contentDescription="获取刷新次数"）→
 * 必须弹出 JadePurchaseFlow 小屏（标题"获取刷新次数" + 描述 + 「消耗玉符」按钮）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MerchantDialogJadeFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        // Robolectric 默认 Application 不执行 registerAllSprites（XianxiaApplication.onCreate），
        // SpriteResRegistry 为空则 SpriteImage 静默不渲染——手动注册测试所需精灵图
        SpriteResRegistry.register(SpriteCategory.UI, mapOf("ui_add_button" to R.drawable.ui_add_button))
    }

    @Test
    fun `点击加号按钮弹出玉符购买弹窗`() {
        val vm = mockk<GameViewModel>(relaxed = true)
        // mockk relaxed 无法为 StateFlow<Set> 泛型生成正确 value（CCE），手动 stub 全部收集源
        every { vm.watchedItemIds } returns MutableStateFlow(emptySet())
        every { vm.equipmentStacks } returns MutableStateFlow(emptyList())
        every { vm.manualStacks } returns MutableStateFlow(emptyList())
        every { vm.pills } returns MutableStateFlow(emptyList())
        every { vm.materials } returns MutableStateFlow(emptyList())
        every { vm.herbs } returns MutableStateFlow(emptyList())
        every { vm.seeds } returns MutableStateFlow(emptyList())
        composeRule.setContent {
            MerchantDialog(
                gameData = GameData(merchantRefreshChances = 0, jadeSymbols = 5),
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 前置守卫：弹窗未点击前不存在（防合并节点与按钮 CD 文本歧义）
        composeRule.onAllNodesWithText("消耗1玉符获取3次刷新次数").assertCountEquals(0)
        // 语义动作触发点击：Robolectric 触摸注入不跨 Dialog 独立窗口（多窗口限制），
        // performClick 的坐标无法到达 Dialog 内节点；语义动作直接在节点上调用 OnClick，
        // 验证 MerchantDialog 完整逻辑链路（点击 → showJadeDialog → overlay 渲染弹窗）
        composeRule.onNodeWithContentDescription("获取刷新次数")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // 弹窗标题与按钮 contentDescription 文本重复——JadePurchaseDialog 为合并语义节点，
        // 标题/描述/小字/按钮文本聚合在一个节点内（MergeDescendants），逐项断言可见
        composeRule.onAllNodesWithText("获取刷新次数").assertCountEquals(1)
        composeRule.onNodeWithText("消耗1玉符获取3次刷新次数").assertIsDisplayed()
        composeRule.onNodeWithText("消耗玉符").assertIsDisplayed()
    }

    @Test
    fun `玉符不足时点击消耗玉符弹出不足提示框且小屏弹窗保留`() {
        val vm = mockk<GameViewModel>(relaxed = true)
        every { vm.watchedItemIds } returns MutableStateFlow(emptySet())
        every { vm.equipmentStacks } returns MutableStateFlow(emptyList())
        every { vm.manualStacks } returns MutableStateFlow(emptyList())
        every { vm.pills } returns MutableStateFlow(emptyList())
        every { vm.materials } returns MutableStateFlow(emptyList())
        every { vm.herbs } returns MutableStateFlow(emptyList())
        every { vm.seeds } returns MutableStateFlow(emptyList())
        // 真实引擎链路：玉符 0 时 deduct 失败返回 InsufficientJadeSymbols
        coEvery { vm.purchaseMerchantRefresh() } returns MerchantRefreshResult.InsufficientJadeSymbols(0, 1)
        composeRule.setContent {
            MerchantDialog(
                gameData = GameData(merchantRefreshChances = 0, jadeSymbols = 0),
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 打开玉符购买小屏弹窗
        composeRule.onNodeWithContentDescription("获取刷新次数")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消耗1玉符获取3次刷新次数").assertIsDisplayed()
        // 点击「消耗玉符」→ 引擎返回不足 → 平台提示框出现，小屏弹窗保留
        composeRule.onNodeWithText("消耗玉符").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("玉符不足，无法获取刷新次数").assertIsDisplayed()
        composeRule.onNodeWithText("消耗1玉符获取3次刷新次数").assertIsDisplayed()
        composeRule.onNodeWithText("消耗玉符").assertIsDisplayed()
    }
}
