package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.startAlchemy
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 炼丹职业提示框测试（2026-08-09 职业系统）。
 *
 * 覆盖 AlchemyViewModel 的三个提示事件出口：
 * 1. 槽位无弟子点击配方 → "需要有弟子才可炼制"
 * 2. 弟子职业等级不够点击配方 → "弟子职业等级不够无法炼制"
 * 3. 引擎层 RecipeTierLocked 拦截失败 → 错误事件透传引擎提示语
 *
 * 事件经 BaseViewModel.errorEvents（Channel → receiveAsFlow）由 GameOverlayHost 渲染为提示框。
 */
class AlchemyViewModelProfessionHintTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var gameEngine: GameEngine
    private lateinit var viewModel: AlchemyViewModel

    /** launchOnEngine 捕获列表（relaxed mock 不执行 lambda，需手动触发） */
    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        gameEngine = mockk(relaxed = true)
        coEvery { gameEngine.launchOnEngine(any()) } answers {
            engineBlocks += args[0] as suspend CoroutineScope.() -> Unit
            mockk<Job>(relaxed = true)
        }
        viewModel = AlchemyViewModel(gameEngine, mockk<ElderManagementUseCase>(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `showNoWorkerHint - 槽位无弟子点击配方弹出提示事件`() = runTest {
        viewModel.showNoWorkerHint()

        assertEquals("需要有弟子才可炼制", viewModel.errorEvents.first())
    }

    @Test
    fun `showTierLockedHint - 弟子职业不够点击配方弹出提示事件`() = runTest {
        viewModel.showTierLockedHint()

        assertEquals("弟子职业等级不够无法炼制", viewModel.errorEvents.first())
    }

    @Test
    fun `startAlchemy - 引擎职业锁定错误透传为提示事件`() = runTest {
        // startAlchemy 为顶层扩展（GameEngineProductionOps.kt），静态 stub
        mockkStatic("com.xianxia.sect.core.engine.GameEngineProductionOpsKt")
        coEvery { gameEngine.startAlchemy(any(), any()) } returns DomainResult.Failure(
            AppError.Domain.Production.RecipeTierLocked(
                message = "弟子职业等级不足，无法炼制该品阶",
                recipeId = "spiritPill", requiredTier = 2, maxCraftableTier = 1
            )
        )

        val tier2Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 2 }
        viewModel.startAlchemy(0, tier2Pill)
        // 手动执行引擎 lambda（对齐 BuildingDelegateOverlapTest 模式）
        engineBlocks.first().invoke(this)

        assertEquals("弟子职业等级不足，无法炼制该品阶", viewModel.errorEvents.first())
    }

    @Test
    fun `startAlchemy - 引擎成功不产生提示事件`() = runTest {
        mockkStatic("com.xianxia.sect.core.engine.GameEngineProductionOpsKt")
        coEvery { gameEngine.startAlchemy(any(), any()) } returns DomainResult.Success(
            com.xianxia.sect.core.model.production.ProductionSlot(
                slotIndex = 0,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                buildingId = "alchemy_0",
                status = com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING
            )
        )

        val tier1Pill = PillRecipeDatabase.getAllRecipes().first { it.tier == 1 }
        viewModel.startAlchemy(0, tier1Pill)
        engineBlocks.first().invoke(this)

        // 成功路径不应有错误事件（超时探测：虚拟时间内 100ms 无事件即视为空）
        val event = withTimeoutOrNull(100) { viewModel.errorEvents.first() }
        assertEquals("成功路径不应产生错误提示", null, event)
    }
}
