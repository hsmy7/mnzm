package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import javax.inject.Provider

/**
 * JadeRuntimeNativeTxGateTest — 玉符运行时 native 臂门控降级守卫（W4-B/B2，w3-04）。
 *
 * 守护契约（双实现并行契约 + handover findings 13）：
 * - **降级等价**：flag OFF 与 AUTHORITATIVE（JVM 无生产 .so，GameCoreBridge 未加载；
 *   或镜像服务缺失）下 `onLoopTick` 结算/跨天重置、`checkpointNow`、`grantFromAd`
 *   均回退 Kotlin 原实现，两侧**返回值与终态逐位一致**
 * - **镜像缺失不 NPE**：W4-B 新增的 `Provider<GameEngineCore>` 惰性边在
 *   `stateSyncServiceRef` 未 stub（null）时必须可空判空降级（findings 13 在
 *   新注入面上的回归网），不得抛 NullPointerException
 * - **平台读数参数化不改变行为**：跨天判定仍由 Kotlin 墙钟读数驱动（回拨不
 *   重置、同 tick 幂等语义在两臂一致）
 *
 * C++ 侧判定序/幂等/零写入语义由 GTest `jade_runtime_tx_test.cpp`（13 用例）逐位守护。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class JadeRuntimeNativeTxGateTest {

    /** 可变单调时钟 fake（tick 差分推进）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    @get:Rule
    val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore

    /** 可变墙钟 fake（跨天/回拨推进）。 */
    private var wallNowMs = 1_700_000_000_000L

    /** 单调时钟（两臂共享推进节奏，各自实例）。 */
    private lateinit var mono: FakeTimeSource

    private val intervalMs = com.xianxia.sect.core.GameConfig.Jade.INTERVAL_MS

    @Before
    fun setup() {
        store = FakeAtomicStateStore()
        mono = FakeTimeSource(1_000_000L)
    }

    /** 双臂构造：flag OFF 臂无 Provider；AUTHORITATIVE 臂 Provider 指向 sync 未 stub 的 mockCore。 */
    private fun makeService(withProvider: Boolean): JadeSymbolService {
        val provider = if (withProvider) {
            // mockCore 未 stub stateSyncServiceRef → null（findings 13 契约面：
            // native 臂必须可空判空降级，不得 NPE）
            val mockCore = mock<GameEngineCore>()
            Provider { mockCore }
        } else {
            null
        }
        return JadeSymbolService(
            timeSource = mono,
            stateStore = store,
            wallClock = WallClock { wallNowMs },
            gameEngineCoreProvider = provider
        )
    }

    /** 播种玉符持久化基线（对齐生产 onLoopStart 读快照语义）。 */
    private fun seedJade(total: Int, today: Int, accumMs: Long, dayAnchorMs: Long) {
        store.update {
            gameData = gameData.copy(
                jadeSymbols = total,
                jadeSymbolsToday = today,
                jadeAccumMs = accumMs,
                jadeDayAnchorMs = dayAnchorMs
            )
        }
    }

    private fun gameData(): GameData = store.gameDataSnapshot

    @Test
    fun `onLoopTick settle degrades identically and never NPEs with provider`() = runBlocking {
        fun runArm(withProvider: Boolean, mode: NativeEngineFlag.Mode): GameData {
            reset()
            val service = makeService(withProvider)
            service.onLoopStart()
            // 推进单调时钟跨过一个发放周期（10 分钟 → +1 枚）
            mono.nowMs += intervalMs + 1_000L
            NativeEngineFlag.withMode(mode) {
                service.onLoopTick()
            }
            return gameData()
        }

        val offState = runArm(withProvider = false, mode = NativeEngineFlag.Mode.OFF)
        val authoritativeState = runArm(withProvider = true, mode = NativeEngineFlag.Mode.AUTHORITATIVE)

        // 回退臂语义逐位一致（且 AUTHORITATIVE 下 provider/sync 缺失不得 NPE）
        assertEquals(offState.jadeSymbols, authoritativeState.jadeSymbols)
        assertEquals(offState.jadeSymbolsToday, authoritativeState.jadeSymbolsToday)
        assertEquals(offState.jadeAccumMs, authoritativeState.jadeAccumMs)
    }

    @Test
    fun `checkpointNow degrades identically with provider present`() = runBlocking {
        seedJade(total = 33, today = 5, accumMs = 123_456L, dayAnchorMs = wallNowMs)
        fun runArm(withProvider: Boolean, mode: NativeEngineFlag.Mode): GameData {
            reset()
            seedJade(total = 33, today = 5, accumMs = 123_456L, dayAnchorMs = wallNowMs)
            val service = makeService(withProvider)
            service.onLoopStart()
            NativeEngineFlag.withMode(mode) {
                service.checkpointNow()
            }
            return gameData()
        }

        val offState = runArm(false, NativeEngineFlag.Mode.OFF)
        val authoritativeState = runArm(true, NativeEngineFlag.Mode.AUTHORITATIVE)

        assertEquals(offState.jadeSymbols, authoritativeState.jadeSymbols)
        assertEquals(offState.jadeSymbolsToday, authoritativeState.jadeSymbolsToday)
        assertEquals(offState.jadeAccumMs, authoritativeState.jadeAccumMs)
        assertEquals(offState.jadeDayAnchorMs, authoritativeState.jadeDayAnchorMs)
        assertEquals("checkpoint 绝对值覆盖写语义两臂一致", 33, authoritativeState.jadeSymbols)
    }

    @Test
    fun `day rollover resets identically and rollback resets nothing`() = runBlocking {
        val day1Midnight = wallNowMs / 86_400_000L * 86_400_000L
        val day2Midnight = day1Midnight + 86_400_000L

        fun runArm(withProvider: Boolean, mode: NativeEngineFlag.Mode, crossed: Boolean): GameData {
            reset()
            val anchor = if (crossed) day1Midnight else day2Midnight
            seedJade(total = 10, today = 9, accumMs = 300_000L, dayAnchorMs = anchor)
            val service = makeService(withProvider)
            if (crossed) wallNowMs = day2Midnight + 1_000L else wallNowMs = day2Midnight + 1_000L
            service.onLoopStart()
            mono.nowMs += 10L
            NativeEngineFlag.withMode(mode) {
                service.onLoopTick()  // 首帧强制跨天检查
            }
            return gameData()
        }

        // 真跨天：两臂均归零 today/accum 并锚定新午夜
        val offCrossed = runArm(false, NativeEngineFlag.Mode.OFF, crossed = true)
        val authCrossed = runArm(true, NativeEngineFlag.Mode.AUTHORITATIVE, crossed = true)
        assertEquals(0, offCrossed.jadeSymbolsToday)
        assertEquals(offCrossed.jadeSymbolsToday, authCrossed.jadeSymbolsToday)
        assertEquals(offCrossed.jadeAccumMs, authCrossed.jadeAccumMs)
        assertEquals(offCrossed.jadeDayAnchorMs, authCrossed.jadeDayAnchorMs)

        // 墙钟回拨（anchor 已是新午夜，读数回到同日）→ 两臂均不重置
        val offSameDay = runArm(false, NativeEngineFlag.Mode.OFF, crossed = false)
        val authSameDay = runArm(true, NativeEngineFlag.Mode.AUTHORITATIVE, crossed = false)
        assertEquals(9, offSameDay.jadeSymbolsToday)
        assertEquals(offSameDay.jadeSymbolsToday, authSameDay.jadeSymbolsToday)
        assertEquals(offSameDay.jadeAccumMs, authSameDay.jadeAccumMs)
    }

    @Test
    fun `grantFromAd degrades identically and never touches today count`() = runBlocking {
        fun runArm(withProvider: Boolean, mode: NativeEngineFlag.Mode): GameData {
            reset()
            seedJade(total = 10, today = 20, accumMs = 0L, dayAnchorMs = wallNowMs)
            val service = makeService(withProvider)
            service.onLoopStart()
            NativeEngineFlag.withMode(mode) {
                val ok = service.grantFromAd(3)
                assertEquals(true, ok)
            }
            return gameData()
        }

        val offState = runArm(false, NativeEngineFlag.Mode.OFF)
        val authoritativeState = runArm(true, NativeEngineFlag.Mode.AUTHORITATIVE)

        assertEquals(13, offState.jadeSymbols)
        assertEquals(offState.jadeSymbols, authoritativeState.jadeSymbols)
        // 广告渠道独立于时间渠道每日上限：today 不动
        assertEquals(20, authoritativeState.jadeSymbolsToday)
        assertEquals(offState.jadeSymbolsToday, authoritativeState.jadeSymbolsToday)
    }

    /** 重建 store/mono（两臂隔离，防运行时 volatile 串场）。 */
    private fun reset() {
        store = FakeAtomicStateStore()
        mono = FakeTimeSource(1_000_000L)
        wallNowMs = 1_700_000_000_000L
    }
}
