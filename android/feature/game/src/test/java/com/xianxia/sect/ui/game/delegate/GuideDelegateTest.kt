package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.incrementGuideCounter
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import io.mockk.coEvery
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * GuideDelegateTest — 引导委托的「打开寻访」计数写点（G12 任务 D / id=26）。
 *
 * 两条判据：
 * 1. 计数递增**只在 `launchOnEngine` 的派发块内发生**（`guideCounters` 是 GameData 字段，
 *    写入必须走引擎线程——UI 线程直写会被状态存储的架构监护判错）；
 * 2. 计数键单源 [GuideCounterKeys.GACHA_OPENED]、每次打开 +1——写第二份键字面量或
 *    改成布尔字段都会偏离「CumulativeCounter + 零新增 GameData 字段」的 D-3 决策。
 *
 * 判别力自证：去掉 `launchOnEngine` 直调扩展 ⇒ `调用瞬间不写` 的 exactly=0 校验红；
 * 改键名/改增量 ⇒ verify 的参数匹配红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideDelegateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var engine: GameEngine
    private lateinit var delegate: GuideDelegate

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
        delegate = GuideDelegate(engine)
        mockkStatic(GUIDE_OPS_FILE)
        justRun { any<GameEngine>().incrementGuideCounter(any(), any()) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `打开寻访计数在引擎线程派发块内递增 - 调用瞬间不写`() = runTest {
        delegate.notifyGachaOpened()

        verify(exactly = 0) { any<GameEngine>().incrementGuideCounter(any(), any()) }
        assertNotNull("写点必须挂进 launchOnEngine（请求发出时计数器还没被碰）", engineBlocks.singleOrNull())

        engineBlocks.first().invoke(this)

        verify(exactly = 1) {
            engine.incrementGuideCounter(GuideCounterKeys.GACHA_OPENED, 1L)
        }
    }

    private companion object {
        /** `incrementGuideCounter` 所在的顶层扩展文件（mockkStatic 按文件类名拦截） */
        const val GUIDE_OPS_FILE = "com.xianxia.sect.core.engine.GameEngineGuideOpsKt"
    }
}
