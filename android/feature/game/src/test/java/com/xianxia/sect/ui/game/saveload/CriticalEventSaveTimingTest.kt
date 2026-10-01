package com.xianxia.sect.ui.game.saveload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.JadeLedgerEntry
import com.xianxia.sect.core.model.JadeLedgerReasons
import com.xianxia.sect.core.state.CriticalSaveEventBus
import com.xianxia.sect.core.state.CriticalSaveKind
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.ui.game.toAutoSaveTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关键事件自动存档时序断言（SS6 验收③/④——Robolectric 真打 Room DB）。
 *
 * 被测链 = 生产接线的完整事件路径：
 * 事件点 `notify(kind)` → [CriticalSaveEventBus.events]（SaveLoadViewModel 的订阅位形）→
 * `SaveOrchestrator.submit`（涉钱立即冲刷 / 其余 500ms 合并窗）→ 保存链落盘（真 Room 写）→
 * `notifySaveCompleted` → 涉钱等待方返回。
 *
 * 守卫契约：
 * 1. **涉钱时序**（验收③）：`awaitNextSaveCompletion` 返回时，game_data 行已含本笔
 *    玉符流水——"事件方法返回前 DB 已落盘"的机制级证明（写路径正确性由 SS5
 *    双路径对拍铁门另行保证，本测试锁的是"信号必然晚于写"的时序）；
 * 2. **防风暴计数**（验收④）：500ms 合并窗内 10 次 GACHA 事件 → 恰好 1 次落盘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CriticalEventSaveTimingTest {

    private lateinit var db: GameDatabase
    private val bus = CriticalSaveEventBus()

    /** onFire 落盘次数（防风暴计数的观测面） */
    private var firedCount = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        firedCount = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 含一笔玉符流水扣费的存档（模拟购买成功后的内存态快照） */
    private fun saveDataAfterSpend(): GameData = GameData(
        id = "game_data",
        sectName = "青云宗",
        jadeSymbols = 19,
        jadeLedger = listOf(
            JadeLedgerEntry(
                atEpochMs = 1_760_000_000_000L,
                delta = -1,
                reason = JadeLedgerReasons.SPEND_BREAKTHROUGH_BONUS,
                balanceAfter = 19
            )
        )
    )

    @Test
    fun `money event returns only after the ledger entry is persisted`() = runTest {
        val orchestrator = SaveOrchestrator(scope = backgroundScope) { _ ->
            firedCount += 1
            // 保存链落盘段替身：真 Room 写（与 StorageEngine 的 game_data 写同表同编码链）
            db.gameDataDao().insert(saveDataAfterSpend())
            bus.notifySaveCompleted()
        }
        // SaveLoadViewModel init 的订阅位形：事件 → 触发映射 → 编排器
        backgroundScope.launch {
            bus.events.collect { kind -> orchestrator.submit(kind.toAutoSaveTrigger()) }
        }
        runCurrent()

        // 事件方法（购买成功路径）的等待段等价形：挂起直到保存完成信号——
        // 信号在 onFire 的 DB 写**之后**发出，故信号到达 ⇒ 写已完成（超时/返回值语义
        // 由 CriticalSaveEventBusTest 纯虚拟时间锁定）。等待期间 runTest 的 idle 虚拟
        // 推进无害：本链路无虚拟定时任务（涉钱走立即冲刷，不经合并窗 delay）。
        bus.notify(CriticalSaveKind.MONEY)
        bus.saveCompleted.first()

        assertEquals("涉钱事件恰好触发一次落盘", 1, firedCount)
        val persisted = db.gameDataDao().getGameDataSync()
        assertEquals(
            "事件方法返回时 DB 已含该笔流水（验收③：返回前已落盘）",
            JadeLedgerReasons.SPEND_BREAKTHROUGH_BONUS,
            persisted?.jadeLedger?.singleOrNull()?.reason
        )
        assertEquals(19, persisted?.jadeSymbols)
        assertEquals(19, persisted?.jadeLedger?.singleOrNull()?.balanceAfter)
    }

    @Test
    fun `ten gacha events inside the merge window persist exactly once`() = runTest {
        val orchestrator = SaveOrchestrator(scope = backgroundScope) { _ ->
            firedCount += 1
            db.gameDataDao().insert(
                GameData(id = "game_data", sectName = "青云宗", jadeSymbols = firedCount)
            )
            bus.notifySaveCompleted()
        }
        backgroundScope.launch {
            bus.events.collect { kind -> orchestrator.submit(kind.toAutoSaveTrigger()) }
        }
        runCurrent()

        repeat(10) { bus.notify(CriticalSaveKind.GACHA) }
        // 窗未到期断言只用非挂起的内存观测：runTest 在 body 挂起期间会自动推进虚拟
        // 时间，任何挂起断言都会把 500ms 窗推到点（虚拟时间与真实 Room IO 的竞争面）
        runCurrent()
        assertEquals("合并窗未到期不得落盘", 0, firedCount)

        advanceTimeBy(SAVE_MERGE_WINDOW_MS + 1)
        runCurrent()

        assertEquals("十次事件必须合并为一次落盘（验收④）", 1, firedCount)
        assertEquals(1, db.gameDataDao().getGameDataSync()?.jadeSymbols)
    }

    private companion object {
        /** 与生产 [SaveOrchestrator.Config] 默认值一致的合并窗（测试显式对齐防漂移）。 */
        const val SAVE_MERGE_WINDOW_MS = 500L
    }
}
