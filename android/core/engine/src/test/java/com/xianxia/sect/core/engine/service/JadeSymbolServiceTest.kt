package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.model.GameData
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 玉符在线时长结算服务测试。
 *
 * 覆盖：发放/整除边界/10s 裁剪/日上限冻结/跨天重置/墙钟回拨防御/
 * 快进多天只重置一次/旧档锚定/存档恢复/checkpoint 幂等/多档隔离/
 * 1Hz UI 节流/delta<=0 跳过/跨天优先于发放。
 */
class JadeSymbolServiceTest {

    /** 单调时钟 fake：手动推进 nowMs 模拟真实前台运行。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var fakeTime: FakeTimeSource
    private var fakeWallMs = 0L
    private lateinit var store: FakeAtomicStateStore
    private lateinit var service: JadeSymbolService

    @Before
    fun setup() {
        fakeTime = FakeTimeSource(1_000_000L)
        // 墙钟固定在"某日正午"（午夜由 Calendar 按本地时区计算）
        fakeWallMs = midnightOf(1_700_000_000_000L) + 12 * 60 * 60 * 1000L
        store = FakeAtomicStateStore()
        service = JadeSymbolService(
            timeSource = fakeTime,
            stateStore = store,
            wallClock = WallClock { fakeWallMs }
        )
    }

    // ── 发放 ──

    @Test
    fun `20 minutes of front-time ticks grant 1 jade`() {
        service.onLoopStart()
        advance(INTERVAL)
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
        assertEquals(1, store.gameDataSnapshot.jadeSymbolsToday)
        assertEquals(0L, store.gameDataSnapshot.jadeAccumMs)
        assertEquals(1, service.runtimeState.value.total)
    }

    @Test
    fun `40 minutes grant 2 jades`() {
        service.onLoopStart()
        advance(INTERVAL * 2)
        assertEquals(2, store.gameDataSnapshot.jadeSymbols)
        assertEquals(2, store.gameDataSnapshot.jadeSymbolsToday)
        assertEquals(0L, store.gameDataSnapshot.jadeAccumMs)
    }

    @Test
    fun `single tick delta above 10s is clamped and does not grant`() {
        service.onLoopStart()
        fakeTime.nowMs += 30 * 60 * 1000L
        service.onLoopTick()
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        // accumMs 为内存态，checkpoint 后持久化
        service.checkpointNow()
        assertEquals(GameConfig.Jade.MAX_TICK_DELTA_MS, store.gameDataSnapshot.jadeAccumMs)
    }

    @Test
    fun `exact 20 minute boundary grants and keeps remainder`() {
        service.onLoopStart()
        advance(INTERVAL)
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0L, store.gameDataSnapshot.jadeAccumMs)
        advance(1_000L)
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
        // 余量 1s 为内存态，checkpoint 后持久化
        service.checkpointNow()
        assertEquals(1_000L, store.gameDataSnapshot.jadeAccumMs)
    }

    @Test
    fun `zero or negative delta is skipped`() {
        service.onLoopStart()
        val before = store.gameDataSnapshot
        service.onLoopTick() // delta = 0
        assertEquals(before.jadeSymbols, store.gameDataSnapshot.jadeSymbols)
        fakeTime.nowMs -= 5_000L
        service.onLoopTick() // 单调回拨：delta < 0
        assertEquals(before.jadeSymbols, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0L, store.gameDataSnapshot.jadeAccumMs)
    }

    // ── 日上限 ──

    @Test
    fun `daily cap freezes further grants`() {
        service.onLoopStart()
        advance(INTERVAL * 30)
        assertEquals(30, store.gameDataSnapshot.jadeSymbols)
        assertEquals(30, store.gameDataSnapshot.jadeSymbolsToday)
        assertTrue(service.runtimeState.value.capped)
        advance(INTERVAL)
        assertEquals(30, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0L, store.gameDataSnapshot.jadeAccumMs)
    }

    @Test
    fun `day rollover after cap resets today and resumes`() {
        service.onLoopStart()
        advance(INTERVAL * 30)
        val oldAnchor = store.gameDataSnapshot.jadeDayAnchorMs
        fakeWallMs += DAY_MS + 1_000L
        advance(2_000L)
        assertEquals(0, store.gameDataSnapshot.jadeSymbolsToday)
        assertNotEquals(oldAnchor, store.gameDataSnapshot.jadeDayAnchorMs)
        advance(INTERVAL)
        assertEquals(31, store.gameDataSnapshot.jadeSymbols)
        assertEquals(1, store.gameDataSnapshot.jadeSymbolsToday)
        assertTrue(!service.runtimeState.value.capped)
    }

    // ── 跨天 / 回拨 ──

    @Test
    fun `wall clock rollback does not reset today`() {
        service.onLoopStart()
        advance(INTERVAL * 5)
        assertEquals(5, store.gameDataSnapshot.jadeSymbolsToday)
        fakeWallMs = midnightOf(1_700_000_000_000L) - 1_000L // 回拨到前一天 23:59:59
        advance(2_000L)
        assertEquals(5, store.gameDataSnapshot.jadeSymbolsToday)
    }

    @Test
    fun `multi-day fast forward resets exactly once`() {
        service.onLoopStart()
        advance(INTERVAL * 5)
        fakeWallMs += 3 * DAY_MS
        advance(2_000L)
        assertEquals(0, store.gameDataSnapshot.jadeSymbolsToday)
        val anchor = store.gameDataSnapshot.jadeDayAnchorMs
        advance(2_000L)
        assertEquals(anchor, store.gameDataSnapshot.jadeDayAnchorMs)
        assertEquals(0, store.gameDataSnapshot.jadeSymbolsToday)
    }

    @Test
    fun `legacy save without anchor is anchored without retro grants`() {
        service.onLoopStart()
        advance(1_000L) // 首帧 tick 执行跨天检查（onLoopStart 的写入延迟到引擎线程）
        assertEquals(midnightOf(fakeWallMs), store.gameDataSnapshot.jadeDayAnchorMs)
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        advance(INTERVAL)
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `day reset write is deferred to first engine-thread tick`() {
        // onLoopStart 本身不做 GameData 写入（startGameLoop 可能在主线程被调，
        // update 有主线程运行时守卫）——锚定写入发生在引擎线程首帧
        service.onLoopStart()
        assertEquals(0L, store.gameDataSnapshot.jadeDayAnchorMs)
        service.onLoopTick()
        assertEquals(midnightOf(fakeWallMs), store.gameDataSnapshot.jadeDayAnchorMs)
    }

    @Test
    fun `checkpoint before onLoopStart does not overwrite persisted jade`() {
        store.update { gameData = gameData.copy(jadeSymbols = 5, jadeSymbolsToday = 2) }
        service.checkpointNow() // 未启动：跳过（防零值覆盖已持久化玉符）
        assertEquals(5, store.gameDataSnapshot.jadeSymbols)
        assertEquals(2, store.gameDataSnapshot.jadeSymbolsToday)
    }

    @Test
    fun `restored accumMs at interval threshold is clamped below`() {
        // 防御纵深：绕过存档校验的写路径（对抗性审查 F1）——
        // 恢复值钳到 INTERVAL-1：0ms 真实时间不兑现（免费 +1 消除），
        // +1ms 真实累计后正常发放（钳制不拦合法时长）
        store.update { gameData = gameData.copy(jadeAccumMs = GameConfig.Jade.INTERVAL_MS) }
        service.onLoopStart()
        service.onLoopTick() // 首帧 delta=0：不发放
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        fakeTime.nowMs += 1L
        service.onLoopTick() // INTERVAL-1 + 1ms = INTERVAL：发放（真实时长语义）
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `day rollover is processed before grant in same frame sequence`() {
        service.onLoopStart()
        advance(INTERVAL * 30) // 今天拿满 30
        fakeWallMs += DAY_MS + 1_000L // 跨天
        advance(INTERVAL) // 跨天后第一天累计 20 分钟
        assertEquals(31, store.gameDataSnapshot.jadeSymbols)
        assertEquals(1, store.gameDataSnapshot.jadeSymbolsToday)
    }

    // ── 存档恢复 / checkpoint ──

    @Test
    fun `onLoopStart restores accum and counts from snapshot`() {
        val anchor = midnightOf(fakeWallMs)
        store.update {
            gameData = gameData.copy(
                jadeSymbols = 7,
                jadeSymbolsToday = 3,
                jadeAccumMs = 600_000L,
                jadeDayAnchorMs = anchor
            )
        }
        service.onLoopStart()
        advance(5 * 60 * 1000L) // 600_000 + 300_000 = 900_000 < INTERVAL
        assertEquals(7, store.gameDataSnapshot.jadeSymbols)
        advance(15 * 60 * 1000L) // 累计满 20 分钟 → 第 8 枚
        assertEquals(8, store.gameDataSnapshot.jadeSymbols)
        assertEquals(4, store.gameDataSnapshot.jadeSymbolsToday)
    }

    @Test
    fun `checkpointNow and onLoopStop persist accum idempotently`() {
        service.onLoopStart()
        advance(5 * 60 * 1000L)
        service.checkpointNow()
        assertEquals(300_000L, store.gameDataSnapshot.jadeAccumMs)
        val afterFirst = store.gameDataSnapshot
        service.checkpointNow()
        assertEquals(afterFirst.jadeAccumMs, store.gameDataSnapshot.jadeAccumMs)
        service.onLoopStop()
        assertEquals(300_000L, store.gameDataSnapshot.jadeAccumMs)
    }

    @Test
    fun `slot switch restores independent jade state`() {
        // 档 A：累计 10 分钟
        service.onLoopStart()
        advance(10 * 60 * 1000L)
        service.checkpointNow()
        assertEquals(600_000L, store.gameDataSnapshot.jadeAccumMs)
        // 切档 B：全新数据，A 的累计不追溯
        store.update { gameData = GameData() }
        service.onLoopStart()
        advance(10 * 60 * 1000L)
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        advance(10 * 60 * 1000L)
        assertEquals(1, store.gameDataSnapshot.jadeSymbols)
    }

    // ── 扣减（洗炼灵根消耗入口）──

    @Test
    fun `deduct - 充足时同步递减 totalCount 与 GameData`() {
        store.update { gameData = gameData.copy(jadeSymbols = 5) }
        service.onLoopStart()

        val ok = store.updateAndReturn { service.deduct(this, 1) }

        assertTrue(ok)
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)
        // checkpoint 用运行时 totalCount 绝对值覆盖写——totalCount 未同步则玉符回涨
        service.checkpointNow()
        assertEquals(4, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `deduct - 余额不足返回 false 且状态不变`() {
        store.update { gameData = gameData.copy(jadeSymbols = 0) }
        service.onLoopStart()

        val ok = store.updateAndReturn { service.deduct(this, 1) }

        assertTrue(!ok)
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        service.checkpointNow()
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `deduct - 非正金额返回 false 且状态不变`() {
        store.update { gameData = gameData.copy(jadeSymbols = 5) }
        service.onLoopStart()

        assertTrue(!store.updateAndReturn { service.deduct(this, 0) })
        assertTrue(!store.updateAndReturn { service.deduct(this, -3) })
        assertEquals(5, store.gameDataSnapshot.jadeSymbols)
        service.checkpointNow()
        assertEquals(5, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `publishJadeSymbolStateNow - 清 1Hz 节流立即发布最新余额`() {
        service.onLoopStart()
        fakeTime.nowMs += 500L
        service.onLoopTick() // 距上次发布 500ms：节流内不发布
        assertEquals("节流内保持旧状态", INTERVAL, service.runtimeState.value.remainingMs)

        service.publishJadeSymbolStateNow()

        // 清节流标记后立即发布：剩余时间应反映刚累计的 500ms
        assertEquals("强制发布应反映最新累计", INTERVAL - 500L,
            service.runtimeState.value.remainingMs)
    }

    // ── 广告发放（玉符栏"+"按钮路径，2026-08-11 新增）──

    @Test
    fun `grantFromAd - 正常发放 3 枚并同步 GameData 与运行时`() {
        service.onLoopStart()

        val ok = service.grantFromAd(3)

        assertTrue(ok)
        assertEquals(3, service.runtimeState.value.total)
        assertEquals(3, store.gameDataSnapshot.jadeSymbols)
        // 用户决策：广告玉符不计入每日 30 上限
        assertEquals(0, store.gameDataSnapshot.jadeSymbolsToday)
        // checkpoint 绝对值覆盖写幂等：不回涨不丢失
        service.checkpointNow()
        assertEquals(3, store.gameDataSnapshot.jadeSymbols)
        assertEquals(3, service.runtimeState.value.total)
    }

    @Test
    fun `grantFromAd - 多次发放累计`() {
        service.onLoopStart()

        service.grantFromAd(3)
        service.grantFromAd(3)

        assertEquals(6, store.gameDataSnapshot.jadeSymbols)
        assertEquals(6, service.runtimeState.value.total)
        service.checkpointNow()
        assertEquals(6, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `grantFromAd - 负值或零返回 false 且无副作用`() {
        service.onLoopStart()

        assertTrue(!service.grantFromAd(0))
        assertTrue(!service.grantFromAd(-3))
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
        assertEquals(0, service.runtimeState.value.total)
        service.checkpointNow()
        assertEquals(0, store.gameDataSnapshot.jadeSymbols)
    }

    @Test
    fun `grantFromAd - 发放立即发布状态清 1Hz 节流`() {
        service.onLoopStart()
        fakeTime.nowMs += 500L
        service.onLoopTick() // 节流内不发布（remainingMs 保持满值）

        service.grantFromAd(3)

        // publishJadeSymbolStateNow 清节流：剩余时间反映刚累计的 500ms
        assertEquals(INTERVAL - 500L, service.runtimeState.value.remainingMs)
        assertEquals(3, service.runtimeState.value.total)
    }

    // ── 懒重锚守卫（冷启动读档窗口竞态纵深防御，2026-08-12 新增）──

    @Test
    fun `grantFromAd - 未 onLoopStart 时懒重锚并基于快照发放`() {
        // 模拟"持久化余额已就位但循环从未启动"（冷启动读档窗口）：
        // store 有 20，运行时 totalCount 仍是进程初值 0
        store.update { gameData = gameData.copy(jadeSymbols = 20) }

        val ok = service.grantFromAd(3)

        assertTrue(ok)
        // 懒重锚后基于快照 20 发放：20 + 3 = 23（修复前为 0 + 3 = 3，覆盖持久化余额）
        assertEquals(23, store.gameDataSnapshot.jadeSymbols)
        assertEquals(23, service.runtimeState.value.total)
    }

    @Test
    fun `grantFromAd - 懒重锚后 checkpointNow 幂等不回涨`() {
        store.update { gameData = gameData.copy(jadeSymbols = 20) }

        service.grantFromAd(3)
        service.checkpointNow()

        assertEquals("checkpoint 绝对值写不回退", 23, store.gameDataSnapshot.jadeSymbols)
        assertEquals("今日计数不受广告发放影响", 0, store.gameDataSnapshot.jadeSymbolsToday)
        assertEquals(23, service.runtimeState.value.total)
    }

    // ── UI 节流 ──

    @Test
    fun `ui state publishes at 1Hz`() {
        service.onLoopStart()
        assertEquals(INTERVAL, service.runtimeState.value.remainingMs)
        fakeTime.nowMs += 500L
        service.onLoopTick() // 距上次发布 500ms：不发布
        assertEquals(INTERVAL, service.runtimeState.value.remainingMs)
        fakeTime.nowMs += 500L
        service.onLoopTick() // 距上次发布 1000ms：发布
        assertEquals(INTERVAL - 1_000L, service.runtimeState.value.remainingMs)
    }

    // ── 工具 ──

    /** 以 1s 粒度推进单调时钟 [ms] 毫秒（单 tick 远低于 10s 裁剪线）。 */
    private fun advance(ms: Long) {
        val steps = (ms / TICK_STEP_MS).toInt()
        repeat(steps) {
            fakeTime.nowMs += TICK_STEP_MS
            service.onLoopTick()
        }
    }

    /** 计算 [wallMs] 所在自然日的午夜 epoch ms（与实现一致的 Calendar 算法）。 */
    private fun midnightOf(wallMs: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = wallMs }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val TICK_STEP_MS = 1_000L
        const val INTERVAL = 20 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
