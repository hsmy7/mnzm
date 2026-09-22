package com.xianxia.sect.ui.game.saveload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-4 自动存档编排点测试（方案 §4 SR-4 门"去抖单测"）。
 *
 * 守卫契约：
 * 1. 月变触发经合并窗后**恰好一次**保存，反馈口径 [SaveFeedback.AutoNotice]；
 * 2. `onStop` 立即冲刷（进程可能被杀，等窗 = 不存），并把窗内已积累的月变并成同一快照
 *    （反馈 [SaveFeedback.Silent]）；
 * 3. [SaveOrchestrator.invalidate] 作废待触发窗（手动保存/读档/重开覆盖）；
 * 4. **合并窗不是节流下限**：连续两次月变各自落一次盘（用户拍板"月月必存"，卡 §1）；
 * 5. 触发集 → 反馈口径映射为纯函数且穷尽。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveOrchestratorTest {

    private class Recorder {
        val fired = mutableListOf<Pair<Set<AutoSaveTrigger>, SaveFeedback>>()

        suspend fun onFire(triggers: Set<AutoSaveTrigger>) {
            fired += triggers to saveFeedbackFor(triggers)
        }
    }

    @Test
    fun `monthly trigger fires once after the merge window`() = runTest {
        val rec = Recorder()
        val orchestrator = SaveOrchestrator(scope = this, config = CONFIG, onFire = rec::onFire)

        orchestrator.submit(AutoSaveTrigger.MONTHLY)
        runCurrent()
        assertEquals("合并窗未到期不得落盘", emptyList<Pair<Set<AutoSaveTrigger>, SaveFeedback>>(), rec.fired)
        assertEquals(setOf(AutoSaveTrigger.MONTHLY), orchestrator.pendingTriggers())

        advanceTimeBy(WINDOW_MS + 1)
        runCurrent()

        assertEquals(
            listOf(setOf(AutoSaveTrigger.MONTHLY) to SaveFeedback.AutoNotice),
            rec.fired
        )
    }

    @Test
    fun `background trigger flushes the window immediately and merges the monthly trigger`() = runTest {
        val rec = Recorder()
        val orchestrator = SaveOrchestrator(scope = this, config = CONFIG, onFire = rec::onFire)

        orchestrator.submit(AutoSaveTrigger.MONTHLY)
        advanceTimeBy(WINDOW_MS / 2)
        runCurrent()
        orchestrator.submit(AutoSaveTrigger.BACKGROUND)
        runCurrent()

        assertEquals("退后台须立即落盘（不等窗）", 1, rec.fired.size)
        assertEquals(
            "窗内月变与 onStop 合并为同一次快照",
            setOf(AutoSaveTrigger.MONTHLY, AutoSaveTrigger.BACKGROUND),
            rec.fired.first().first
        )
        assertEquals(SaveFeedback.Silent, rec.fired.first().second)

        advanceTimeBy(WINDOW_MS * 4)
        runCurrent()
        assertEquals("冲刷后原窗口不得二次触发", 1, rec.fired.size)
        assertTrue(orchestrator.pendingTriggers().isEmpty())
    }

    @Test
    fun `invalidate drops the pending window`() = runTest {
        val rec = Recorder()
        val orchestrator = SaveOrchestrator(scope = this, config = CONFIG, onFire = rec::onFire)

        orchestrator.submit(AutoSaveTrigger.MONTHLY)
        runCurrent()
        orchestrator.invalidate()
        runCurrent()
        advanceTimeBy(WINDOW_MS * 4)
        runCurrent()

        assertTrue("手动保存已覆盖同一状态 ⇒ 自动窗必须作废", rec.fired.isEmpty())
        assertTrue(orchestrator.pendingTriggers().isEmpty())
    }

    @Test
    fun `successive monthly triggers each persist - the window is not a throttle floor`() = runTest {
        val rec = Recorder()
        val orchestrator = SaveOrchestrator(scope = this, config = CONFIG, onFire = rec::onFire)

        // 游戏月 = 6 秒真实时间（GameTimeClock 2000ms/旬 ×3 旬）远大于合并窗 ⇒ 月月必存
        repeat(2) {
            orchestrator.submit(AutoSaveTrigger.MONTHLY)
            advanceTimeBy(MONTH_INTERVAL_MS)
            runCurrent()
        }

        assertEquals(
            "月变触发不做按秒节流（用户 2026-09-22 拍板月月必存，卡 §1）",
            2,
            rec.fired.size
        )
    }

    @Test
    fun `feedback mapping covers every trigger combination`() {
        assertEquals(SaveFeedback.AutoNotice, saveFeedbackFor(setOf(AutoSaveTrigger.MONTHLY)))
        assertEquals(SaveFeedback.Silent, saveFeedbackFor(setOf(AutoSaveTrigger.BACKGROUND)))
        assertEquals(
            "含 onStop 的合并集按静默口径（玩家已离场）",
            SaveFeedback.Silent,
            saveFeedbackFor(setOf(AutoSaveTrigger.MONTHLY, AutoSaveTrigger.BACKGROUND))
        )
    }

    private companion object {
        const val WINDOW_MS = 500L
        const val MONTH_INTERVAL_MS = 6_000L
        val CONFIG = SaveOrchestrator.Config(mergeWindowMs = WINDOW_MS)
    }
}
