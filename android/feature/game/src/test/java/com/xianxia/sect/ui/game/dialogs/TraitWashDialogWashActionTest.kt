package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.SpiritRootWashResult
import com.xianxia.sect.core.engine.TraitWashResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleExtended
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.ui.game.GameViewModel
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 洗炼弹窗 UI↔引擎集成段测试（2026-08-11 补盲区）：
 * 现有 TalentDetailDialogWashTest 全部 viewModel=null，从未覆盖"点击洗炼 → 引擎返回 → 结果列显示"。
 * 本测试 mock viewModel 返回各结果分支，断言 UI 正确回显/报错：
 * - Success → 洗炼结果列显示新特质 + 出现"确认替换"
 * - Error → 错误弹窗显示引擎 message（平台 Dialog 可见性守卫，防嵌套裁剪回归）
 * - 玉符不足 → 固定文案"玉符不足，无法洗炼"可见
 * - 灵根洗炼 Error（首个 SpiritRootWashDialog 测试，同源缺陷回归）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraitWashDialogWashActionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `天赋 - mock Success 点击洗炼后结果列显示新天赋且出现确认替换`() {
        val target = TalentDatabase.getByRarity(1).first()
        val result = TalentDatabase.getByRarity(1).last()
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.washTalent(any(), any(), any()) } returns TraitWashResult.Success(result.id, 0)

        composeRule.setContent {
            TraitWashDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                targetId = target.id,
                jadeSymbols = 10,
                viewModel = vm,
                washSession = WashSessionControl(
                    initialPityCount = 0,
                    onPityCountChanged = {},
                    washing = false,
                    onWashingChange = {}
                ),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 弹窗标题"洗炼天赋"（容器）与按钮"洗炼天赋"（叶子）文本重复——取第 2 个（按钮）
        composeRule.onAllNodesWithText("洗炼天赋")[1].performClick()
        composeRule.waitForIdle()
        // 结果列必须显示新天赋名（target 与 result 取不同条目避免文本重复）
        composeRule.onNodeWithText(result.name).assertIsDisplayed()
        composeRule.onNodeWithText("确认替换").assertIsDisplayed()
    }

    @Test
    fun `天赋 - mock Error 点击洗炼后显示错误提示`() {
        val target = TalentDatabase.getByRarity(1).first()
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.washTalent(any(), any(), any()) } returns TraitWashResult.Error("该特质已不存在")

        composeRule.setContent {
            TraitWashDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                targetId = target.id,
                jadeSymbols = 10,
                viewModel = vm,
                washSession = WashSessionControl(0, {}, false, {}),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 弹窗标题"洗炼天赋"（容器）与按钮"洗炼天赋"（叶子）文本重复——取第 2 个（按钮）
        composeRule.onAllNodesWithText("洗炼天赋")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("该特质已不存在").assertIsDisplayed()
    }

    @Test
    fun `天赋 - mock 玉符不足点击洗炼后显示固定文案`() {
        // 真实玩家场景：玉符不足 → 引擎返回 InsufficientJadeSymbols → 必须弹"玉符不足，无法洗炼"
        // （修复前嵌套内联弹窗被外层 clip 裁剪，此文案不可见 = 用户报告的"洗炼无效"）
        val target = TalentDatabase.getByRarity(1).first()
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.washTalent(any(), any(), any()) } returns TraitWashResult.InsufficientJadeSymbols(0, 1)

        composeRule.setContent {
            TraitWashDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                targetId = target.id,
                jadeSymbols = 0,
                viewModel = vm,
                washSession = WashSessionControl(0, {}, false, {}),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("洗炼天赋")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("玉符不足，无法洗炼").assertIsDisplayed()
    }

    @Test
    fun `灵根 - mock Error 点击洗炼后显示错误提示`() {
        // 灵根洗炼的 ErrorDialog 与特质洗炼同源缺陷，一并回归
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.washSpiritRoot(any(), any()) } returns SpiritRootWashResult.Error("弟子已死亡")

        composeRule.setContent {
            SpiritRootWashDialog(
                disciple = testDisciple(),
                jadeSymbols = 10,
                viewModel = vm,
                initialPityCount = 0,
                onPityCountChanged = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 弹窗标题"洗炼灵根"（容器）与按钮"洗炼灵根"（叶子）文本重复——取第 2 个（按钮）
        composeRule.onAllNodesWithText("洗炼灵根")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子已死亡").assertIsDisplayed()
    }

    private fun testDisciple() = DiscipleAggregate(
        core = DiscipleCore(id = "d1", name = "测试弟子", discipleType = "outer"),
        combatStats = null,
        equipment = null,
        extended = DiscipleExtended(discipleId = "d1"),
        attributes = null
    )
}
