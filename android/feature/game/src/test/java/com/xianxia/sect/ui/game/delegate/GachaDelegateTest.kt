package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaGrantResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.model.GachaHistoryEntry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GachaDelegateTest — 寻访委托的线程派发与回调收口（G11 验收⑦）。
 *
 * 三条判据：
 * 1. 门面的 suspend 抽卡方法**只在 `launchOnEngine` 的块里被调用**（UI 线程直调会被
 *    状态存储的架构监护判错，见 `GachaFacade.pullOnce` 的线程要求）；
 * 2. 门面的结果原样回传给界面回调；
 * 3. 门面抛非取消异常时回 `Failure(EngineFault)` 而**不是漏掉回调**——回调缺失会让
 *    界面的忙碌态永久置灰招募按钮（软锁）。`CancellationException` 必须原样重抛。
 *
 * ## 判别力自证（把实现改坏 ⇒ 哪条红）
 * | 构造反例 | 变红的用例 |
 * |---|---|
 * | 去掉 `launchOnEngine`，改成 `viewModelScope.launch` 或直接同步调用 | `抽卡只在引擎线程派发块内执行` |
 * | 去掉 `catch (Exception)` 兜底 | `门面异常仍必须回调 - 否则招募按钮永久置灰` |
 * | 把 `catch (CancellationException)` 也吞掉 | `取消异常必须原样重抛` |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GachaDelegateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var engine: GameEngine
    private lateinit var facade: RecordingGachaFacade
    private lateinit var delegate: GachaDelegate

    /** 被 launchOnEngine 捕获但尚未执行的引擎线程块 */
    private val engineBlocks = mutableListOf<suspend CoroutineScope.() -> Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        engine = mockk(relaxed = true)
        coEvery { engine.launchOnEngine(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            engineBlocks += firstArg<suspend CoroutineScope.() -> Unit>()
            mockk<Job>(relaxed = true)
        }
        facade = RecordingGachaFacade()
        delegate = GachaDelegate(engine, facade)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `抽卡只在引擎线程派发块内执行 - 调用瞬间不碰门面`() = runTest {
        var received: GachaPullResult? = null

        delegate.pullOnce { received = it }

        assertEquals("发出请求时门面还不得被调用（否则等于在 UI 线程做事务）", 0, facade.calls.size)
        assertNotNull("请求必须挂进 launchOnEngine", engineBlocks.singleOrNull())

        engineBlocks.first().invoke(this)

        assertEquals(listOf("pullOnce"), facade.calls)
        assertTrue("门面的出货结果原样回传", received is GachaPullResult.Success)
        assertEquals("十连的十格不因委托层改写", 10, (received as GachaPullResult.Success).rows.size)
    }

    @Test
    fun `十连走同一条派发口`() = runTest {
        var received: GachaPullResult? = null

        delegate.pullTen { received = it }
        engineBlocks.first().invoke(this)

        assertEquals(listOf("pullTen"), facade.calls)
        assertEquals("十连失败码也要回传", "InsufficientSpiritStones",
            (received as GachaPullResult.Failure).reason)
    }

    @Test
    fun `门面异常仍必须回调 - 否则招募按钮永久置灰`() = runTest {
        facade.throwOnCall = IllegalStateException("桥断开")
        var received: GachaPullResult? = null

        delegate.pullOnce { received = it }
        engineBlocks.first().invoke(this)

        assertNotNull("异常路径也必须收到一次回调（忙碌态靠它解除）", received)
        assertEquals(
            "异常降级为显式结果码，界面据此走兜底文案而不是把按钮卡死",
            GachaDelegate.REASON_ENGINE_FAULT,
            (received as GachaPullResult.Failure).reason,
        )
    }

    @Test
    fun `取消异常必须原样重抛 - 不得伪装成业务失败`() = runTest {
        facade.throwOnCall = CancellationException("引擎停机")
        var received: GachaPullResult? = null
        var propagated: Throwable? = null

        try {
            delegate.pullOnce { received = it }
            engineBlocks.first().invoke(this)
        } catch (cancellation: CancellationException) {
            propagated = cancellation
        }

        assertNull("协程取消不是寻访失败，不该回任何业务结果", received)
        assertNotNull("CancellationException 必须向上抛（AGENTS §5 8.1）", propagated)
        assertTrue("不吞掉不重抛", propagated is CancellationException)
    }

    @Test
    fun `委托不自带池 id 字面量 - 一律取门面默认参数`() = runTest {
        delegate.pullOnce { }
        engineBlocks.first().invoke(this)

        assertEquals(
            "门面收到的是它自己的默认池（standard）；委托再写一份就是第二真源",
            DEFAULT_POOL_ID,
            facade.lastPoolId,
        )
    }

    private companion object {
        /** 与 `GachaFacade.pullOnce(poolId: String = "standard")` 的默认值对齐 */
        const val DEFAULT_POOL_ID = "standard"
    }
}

/**
 * 手写 [GachaFacade] 替身：记录被调方法与入参池 id，结果与异常由用例摆好。
 *
 * 不用 mockk 桩：`GachaPullResult` / `GachaGrantResult` 是 sealed interface，
 * ByteBuddy 代理不了密封返回类型（G09 实测会在间接调用链上抛 MockitoException）。
 */
private class RecordingGachaFacade : GachaFacade {
    val calls = mutableListOf<String>()
    var lastPoolId: String? = null
    var throwOnCall: Throwable? = null

    override val pityCounters: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val fragmentCounts: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val starMap: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
    override val history: StateFlow<List<GachaHistoryEntry>> = MutableStateFlow(emptyList())

    override suspend fun pullOnce(poolId: String): GachaPullResult = record("pullOnce", poolId) {
        GachaPullResult.Success(
            poolId = poolId,
            pricePaid = 5000L,
            spiritStonesAfter = 0L,
            pityAfter = 1,
            rows = (1..10).map { GachaPullRow("item", "", "spiritGrass1", 1, 1, false) },
            unlockedTemplateIds = emptyList(),
        )
    }

    override suspend fun pullTen(poolId: String): GachaPullResult = record("pullTen", poolId) {
        GachaPullResult.Failure("InsufficientSpiritStones")
    }

    override suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult =
        GachaGrantResult.Invalid("NOT_USED_IN_UI")

    private fun record(name: String, poolId: String, body: () -> GachaPullResult): GachaPullResult {
        calls += name
        lastPoolId = poolId
        throwOnCall?.let { throw it }
        return body()
    }
}
