package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaGrantResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPitySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaRarityWeightSpec
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.ui.game.dialogs.GachaMainInputs
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GachaViewModelTest — 寻访界面状态机与可购判据（G11）。
 *
 * 覆盖 VM 的四个出口（结果层内容 / 代次 / 失败内联文案 / 忙碌态）与 `GachaMainInputs`
 * 的三条禁用判据（池未装载、余额不足、请求进行中）。抽卡本体不在这里——
 * 双臂与保底口径归 `:core:engine` 的 `GachaPullGuardTest` / `DiffGachaPullTest`。
 *
 * ## 判别力自证（把实现改回旧口径 ⇒ 哪条红）
 * | 构造反例（只改一处） | 变红的用例 |
 * |---|---|
 * | `onPullResult` 的 Failure 分支不写 `_failureMessage` | `失败结果只出内联文案 - 不出结果层` |
 * | 失败分支把结果码原样显示给玩家 | 同上（期望的是中文文案） |
 * | `GachaMainInputs.canAffordOnce` 去掉 `pulling` 判定 | `请求进行中 - 两个招募口都禁用` |
 * | 池未装载时 pricePerPull 兜底成常量 | `池未装载时不得给出可读价格` |
 * | `onPullRequested` 不取星级锚点 | `锚点必须是请求发出那一刻的星级快照` |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GachaViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var facade: FakeGachaFacade
    private lateinit var poolConfig: GachaPoolConfig

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        facade = FakeGachaFacade()
        poolConfig = mockk()
        every { poolConfig.pool(POOL_ID) } returns standardPool()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `出货成功后写结果层内容并递换代次`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull("初始没有结果层", viewModel.showcase.value)
        assertEquals("初始代次为 0", 0, viewModel.resultToken.value)

        viewModel.onPullRequested()
        assertTrue("请求发出后进入忙碌态", viewModel.pulling.value)

        viewModel.onPullResult(success(count = 10))
        advanceUntilIdle()

        assertEquals("出货后离开忙碌态", false, viewModel.pulling.value)
        assertEquals("十连的十格原样进入结果层", 10, viewModel.showcase.value?.result?.rows?.size)
        assertEquals("每叠一轮换代次（结果层靠它重建并重播流光）", 1, viewModel.resultToken.value)
        assertNull("出货不得留失败文案", viewModel.failureMessage.value)

        viewModel.onPullResult(success(count = 1))
        assertEquals("第二轮仍走同一条链", 2, viewModel.resultToken.value)
    }

    @Test
    fun `失败结果只出内联文案 - 不出结果层也不占通知总线`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onPullRequested()
        viewModel.onPullResult(GachaPullResult.Failure("InsufficientSpiritStones"))

        assertNull("余额不足不该画出结果页", viewModel.showcase.value)
        assertEquals(
            "失败码要翻成玩家可读文案（D-4：文案在寻访界面内联展示，不新建通知总线）",
            "灵石不足，无法寻访",
            viewModel.failureMessage.value,
        )

        viewModel.onPullResult(GachaPullResult.Failure("PoolDisabled"))
        assertEquals("池未开放另有文案", "本次寻访暂未开放", viewModel.failureMessage.value)

        viewModel.onPullResult(GachaPullResult.Failure("SomeFutureCode"))
        assertEquals(
            "未知结果码走兜底文案，不得把内部码透给玩家",
            "寻访未能完成，请稍后再试",
            viewModel.failureMessage.value,
        )
    }

    @Test
    fun `锚点必须是请求发出那一刻的星级快照`() = runTest {
        facade.starMap.value = mapOf("zhouming" to 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onPullRequested()
        // 抽卡事务在引擎线程提交后账本才前进，回调时流上已是新值
        facade.starMap.value = mapOf("zhouming" to 2)
        viewModel.onPullResult(success(count = 1))

        assertEquals(
            "跨星提示要用抽前快照做差；在回调里现取 starMap 会读到提交后的值，跳变恒为 0",
            mapOf("zhouming" to 1),
            viewModel.showcase.value?.anchorStarMap,
        )
    }

    @Test
    fun `关闭结果层不动账本`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onPullRequested()
        viewModel.onPullResult(success(count = 10))

        viewModel.dismissResult()

        assertNull("关掉结果层只是收起展示面", viewModel.showcase.value)
        assertEquals("星级账本仍来自门面（UI 不回写）", mapOf<String, Int>(), facade.starMap.value)
    }

    @Test
    fun `装载后池读面可用 - 正向对照`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            "init 里那次 IO 派发必须真的把池读面交出来（这条不红就说明前面几条对 null 的断言是空转）",
            standardPool(),
            viewModel.poolSpec.value,
        )
    }

    @Test
    fun `池未装载时不得给出可读价格`() = runTest {
        every { poolConfig.pool(POOL_ID) } returns null
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull("配置里没有这张池 ⇒ 读面为 null，UI 显式提示不可用", viewModel.poolSpec.value)
        val inputs = GachaMainInputs(
            pool = null,
            spiritStones = Long.MAX_VALUE,
            pityCount = 0,
            pulling = false,
            failureMessage = null,
        )
        assertEquals("池缺失时价格视为不可读（0），不兜底成任何常量", 0, inputs.pricePerPull)
        assertEquals("池缺失时两个招募口都必须禁用", false, inputs.canAffordOnce)
        assertEquals("池缺失时十连同样禁用", false, inputs.canAffordTen)
    }

    @Test
    fun `余额判据 - 单抽与十连各自比对`() {
        val pool = standardPool()
        val price = pool.pricePerPull.toLong()

        fun affordable(stones: Long, ten: Boolean): Boolean {
            val inputs = GachaMainInputs(
                pool = pool,
                spiritStones = stones,
                pityCount = 0,
                pulling = false,
                failureMessage = null,
            )
            return if (ten) inputs.canAffordTen else inputs.canAffordOnce
        }

        assertTrue("刚好够单抽", affordable(stones = price, ten = false))
        assertEquals("差 1 块就不能单抽", false, affordable(stones = price - 1, ten = false))
        assertTrue("够十连（10 倍价）", affordable(stones = price * TEN, ten = true))
        assertEquals("只够九次的余额不能十连（不做差额部分抽取）", false, affordable(stones = price * 9, ten = true))
        assertTrue("够十连必然够单抽", affordable(stones = price * TEN, ten = true) && affordable(price * TEN, false))
    }

    @Test
    fun `请求进行中 - 两个招募口都禁用`() {
        val inputs = GachaMainInputs(
            pool = standardPool(),
            spiritStones = Long.MAX_VALUE,
            pityCount = 0,
            pulling = true,
            failureMessage = null,
        )

        assertEquals(
            "连点期间必须禁用，否则第二次点击会再扣一笔（十连的原子性只覆盖单笔事务内）",
            false,
            inputs.canAffordOnce,
        )
        assertEquals("十连同判据", false, inputs.canAffordTen)
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    private fun viewModel(): GachaViewModel = GachaViewModel(
        gachaFacade = facade,
        gachaPoolConfig = poolConfig,
        ioDispatcher = IoDispatcher(testDispatcher),
    )

    private fun success(count: Int): GachaPullResult.Success = GachaPullResult.Success(
        poolId = POOL_ID,
        pricePaid = standardPool().pricePerPull.toLong() * count,
        spiritStonesAfter = 0L,
        pityAfter = count % 10,
        rows = (1..count).map { GachaPullRow("item", "", "spiritGrass1", 1, 1, false) },
        unlockedTemplateIds = emptyList(),
    )

    private companion object {
        const val POOL_ID = "standard"
        const val TEN = 10
    }
}

/**
 * 手写 [GachaFacade] 替身：四个流可摆值，抽卡方法只是把可预期失败摆回去。
 *
 * 不用 mockk 的 relaxed 桩：`GachaPullResult` / `GachaGrantResult` 是 sealed interface，
 * ByteBuddy 代理不了密封返回类型（G09 实测：`RETURNS_SMART_NULLS` 会在间接调用链上抛
 * MockitoException），且星级账本要能被测试主动改写来验锚点语义。
 * 抽卡调用本身归 `GameViewModel.gacha` 那条链（本测试不触发）。
 */
private class FakeGachaFacade : GachaFacade {
    override val pityCounters = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val fragmentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val starMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val history = MutableStateFlow<List<GachaHistoryEntry>>(emptyList())

    override suspend fun pullOnce(poolId: String): GachaPullResult =
        GachaPullResult.Failure(NOT_USED)

    override suspend fun pullTen(poolId: String): GachaPullResult =
        GachaPullResult.Failure(NOT_USED)

    override suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult =
        GachaGrantResult.Invalid(NOT_USED)

    private companion object {
        const val NOT_USED = "NOT_USED_IN_UI"
    }
}

private fun standardPool(): GachaPoolSpec = GachaPoolSpec(
    poolId = "standard",
    enabled = true,
    pricePerPull = 5000,
    categories = emptyList(),
    itemRarityWeights = listOf(GachaRarityWeightSpec(1, 100)),
    fragmentCountWeights = emptyList(),
    itemCountWeights = emptyList(),
    pity = GachaPitySpec(10, 5, "random"),
)
