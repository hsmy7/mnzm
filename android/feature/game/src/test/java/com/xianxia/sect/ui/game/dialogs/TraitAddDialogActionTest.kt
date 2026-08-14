package com.xianxia.sect.ui.game.dialogs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.TraitAddResult
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
 * 新增弹窗 UI↔引擎集成段测试（镜像 TraitWashDialogWashActionTest）：
 * - Success → 结果区显示新特质 + 出现"确认新增""继续消耗"
 * - Error → 错误弹窗显示引擎 message
 * - 玉符不足 → 固定文案"玉符不足，无法新增"可见
 * - 有持久化 pending（关闭界面再打开）→ 直接显示产物与双按钮，可确认新增
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraitAddDialogActionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `天赋 - mock Success 点击消耗玉符后结果区显示新天赋且出现确认新增继续消耗`() {
        val result = TalentDatabase.getByRarity(1).first()
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.addTalent(any()) } returns TraitAddResult.Success(result.id)

        composeRule.setContent {
            TraitAddDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                jadeSymbols = 10,
                pendingTraitId = null,
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 初始：单个"消耗玉符"按钮，无结果区产物
        composeRule.onNodeWithText("消耗玉符").assertIsDisplayed()
        composeRule.onNodeWithText("确认新增").assertDoesNotExist()

        composeRule.onNodeWithText("消耗玉符").performClick()
        composeRule.waitForIdle()

        // 刷新成功：结果区显示新天赋名 + 双按钮
        composeRule.onNodeWithText(result.name).assertIsDisplayed()
        composeRule.onNodeWithText("确认新增").assertIsDisplayed()
        composeRule.onNodeWithText("继续消耗").assertIsDisplayed()
    }

    @Test
    fun `天赋 - mock Error 点击消耗玉符后显示错误提示`() {
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.addTalent(any()) } returns TraitAddResult.Error("弟子已死亡")

        composeRule.setContent {
            TraitAddDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                jadeSymbols = 10,
                pendingTraitId = null,
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消耗玉符").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("弟子已死亡").assertIsDisplayed()
    }

    @Test
    fun `天赋 - mock 玉符不足点击消耗玉符后显示固定文案`() {
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.addTalent(any()) } returns TraitAddResult.InsufficientJadeSymbols(0, 1)

        composeRule.setContent {
            TraitAddDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                jadeSymbols = 0,
                pendingTraitId = null,
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("消耗玉符").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("玉符不足，无法新增").assertIsDisplayed()
    }

    @Test
    fun `天赋 - 有持久化pending时直接显示产物与双按钮 可直接确认新增`() {
        // 需求：刷新结果持久化——关闭界面再打开仍显示刷新出的天赋，并可直接确认新增
        val pending = TalentDatabase.getByRarity(2).first()
        val vm = mockk<GameViewModel>(relaxed = true)
        coEvery { vm.confirmAddTalent(any(), any()) } returns com.xianxia.sect.core.engine.TraitAddConfirmResult.Success

        composeRule.setContent {
            TraitAddDialog(
                disciple = testDisciple(),
                type = TraitWashType.TALENT,
                jadeSymbols = 10,
                pendingTraitId = pending.id,
                viewModel = vm,
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()
        // 未点击任何按钮：结果区直接显示持久化产物 + 双按钮（无需重新消耗玉符）
        composeRule.onNodeWithText(pending.name).assertIsDisplayed()
        composeRule.onNodeWithText("确认新增").assertIsDisplayed()
        composeRule.onNodeWithText("继续消耗").assertIsDisplayed()
        composeRule.onNodeWithText("消耗玉符").assertDoesNotExist()
    }

    private fun testDisciple() = DiscipleAggregate(
        core = DiscipleCore(id = "d1", name = "测试弟子", discipleType = "outer"),
        combatStats = null,
        equipment = null,
        extended = DiscipleExtended(discipleId = "d1"),
        attributes = null
    )
}
