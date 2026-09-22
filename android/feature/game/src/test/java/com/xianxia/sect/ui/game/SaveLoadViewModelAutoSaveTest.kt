package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.SaveTriggerFlag
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.facade.StorageFacade

import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.ui.game.saveload.AutoSaveTrigger
import com.xianxia.sect.ui.game.saveload.PersistenceFacade
import com.xianxia.sect.ui.game.saveload.SaveFeedback
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every

import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * SR-4 自动存档触发面测试（月变 → 合并窗 → 落盘 → 消息栏一行；onStop → 立即冲刷）。
 *
 * 守卫契约：
 * 1. 月变事件在**旗标开 + 已加载 + 槽位有效**时经合并窗落一次盘，成功写消息栏一行
 *    （不弹 snackbar——月月必存口径下每 6 秒一次，弹窗等于刷屏）；
 * 2. 旗标关（回滚臂）⇒ 月变事件零副作用，保存链完全不被触达；
 * 3. `onStop` 走同一入口但**不等窗**，成功口径为静默（不写 notice）；
 * 4. 手动保存 [SaveFeedback.Manual] 作废待触发自动窗（合并语义：同一状态存一次即够）；
 * 5. LEGACY 模式（默认）自动保存同样不投递云上传——SR-2 硬红线在自动触发面同样成立。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveLoadViewModelAutoSaveTest {

    private val testDispatcher = StandardTestDispatcher()

    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val stateStore: GameStateStore = mockk(relaxed = true)
    private val gameClock: GameTimeClock = mockk(relaxed = true)
    private val resourcePreloader: ResourcePreloader = mockk(relaxed = true)
    private val persistenceFacade: PersistenceFacade = mockk(relaxed = true)
    private val ioDispatcher = IoDispatcher(testDispatcher)

    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val uploadQueue: UploadQueue = mockk(relaxed = true)
    private val saveBackendModeProvider: SaveBackendModeProvider = mockk()
    private val monthEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private lateinit var viewModel: SaveLoadViewModel

    private var monthFlagBefore = true
    private var backgroundFlagBefore = true

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        monthFlagBefore = SaveTriggerFlag.autoSaveOnMonthChange
        backgroundFlagBefore = SaveTriggerFlag.saveOnBackground

        every { persistenceFacade.storageFacade } returns storageFacade
        every { persistenceFacade.uploadQueue } returns uploadQueue
        every { uploadQueue.events } returns MutableSharedFlow()
        every { persistenceFacade.saveBackendModeProvider } returns saveBackendModeProvider
        every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY
        every { persistenceFacade.saveBackend } returns mockk(relaxed = true) {
            every { conflicts } returns MutableSharedFlow()
        }
        every { persistenceFacade.tapCloudSaveManager } returns mockk(relaxed = true)
        every { persistenceFacade.uploadLedger } returns mockk(relaxed = true)
        every { persistenceFacade.sessionManager } returns mockk<SessionManager>(relaxed = true) {
            every { isLoggedIn } returns true
        }
        every { persistenceFacade.bootSequenceController } returns mockk(relaxed = true) {
            every { bootInProgress } returns MutableStateFlow(false)
        }
        coEvery { storageFacade.getSaveSlotsSuspend() } returns emptyList()
        coEvery { storageFacade.getMailsForSlot(any()) } returns emptyList()
        coEvery { storageFacade.save(any(), any()) } returns SaveResult.success(Unit)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { gameEngineCore.stuckResetEvents } returns MutableSharedFlow()
        every { gameEngineCore.monthSettledEvents } returns monthEvents
        every { gameEngine.gameData } returns MutableStateFlow(gameData(year = 3, month = 5))
        coEvery { gameEngine.buildSaveSnapshot() } returns snapshot()

        viewModel = SaveLoadViewModel(
            gameEngine = gameEngine,
            gameEngineCore = gameEngineCore,
            stateStore = stateStore,
            gameClock = gameClock,
            resourcePreloader = resourcePreloader,
            persistenceFacade = persistenceFacade,
            ioDispatcher = ioDispatcher
        )
    }

    @After
    fun tearDown() {
        SaveTriggerFlag.autoSaveOnMonthChange = monthFlagBefore
        SaveTriggerFlag.saveOnBackground = backgroundFlagBefore
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `month change persists once through the merge window and writes the message bar line`() =
        runTest(testDispatcher) {
            SaveTriggerFlag.autoSaveOnMonthChange = true

            monthEvents.tryEmit(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { storageFacade.save(1, any()) }
            assertEquals(
                "月变自动存档成功写消息栏常驻一行（带游戏内时间）",
                "已自动存档 · 第3年5月",
                viewModel.autoSaveNotice.value
            )
        }

    @Test
    fun `flag off makes the month event a no-op - rollback arm`() = runTest(testDispatcher) {
        SaveTriggerFlag.autoSaveOnMonthChange = false

        monthEvents.tryEmit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        assertEquals("关闭态不得积累待触发窗", emptySet<AutoSaveTrigger>(), viewModel.saveOrchestrator.pendingTriggers())
        assertNull(viewModel.autoSaveNotice.value)
    }

    @Test
    fun `onStop trigger flushes immediately without a notice line`() = runTest(testDispatcher) {
        SaveTriggerFlag.saveOnBackground = true

        viewModel.saveOnBackground()
        advanceUntilIdle()

        coVerify(exactly = 1) { storageFacade.save(1, any()) }
        assertNull("后台保存成功不产生消息栏行（玩家已离场）", viewModel.autoSaveNotice.value)
    }

    @Test
    fun `manual save drops the pending auto window`() = runTest(testDispatcher) {
        SaveTriggerFlag.autoSaveOnMonthChange = true

        monthEvents.tryEmit(Unit)
        testScheduler.advanceTimeBy(1L) // 开窗但不等窗（窗 = 500ms）
        viewModel.saveGame("1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            storageFacade.save(1, any())
        }
        assertEquals("手动保存已覆盖同一状态 ⇒ 自动窗必须作废", emptySet<AutoSaveTrigger>(), viewModel.saveOrchestrator.pendingTriggers())
    }

    @Test
    fun `legacy mode auto save does not enqueue cloud upload`() = runTest(testDispatcher) {
        SaveTriggerFlag.autoSaveOnMonthChange = true
        every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY

        monthEvents.tryEmit(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { storageFacade.save(1, any()) }
        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `onStop in legacy mode never touches the upload queue - SR-2 hard red line`() =
        runTest(testDispatcher) {
            SaveTriggerFlag.saveOnBackground = true
            every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY

            viewModel.saveOnBackground()
            advanceUntilIdle()

            verify(exactly = 0) { uploadQueue.requestDrain() }
        }

    @Test
    fun `onStop outside legacy requests one queue drain after the local commit`() =
        runTest(testDispatcher) {
            SaveTriggerFlag.saveOnBackground = true
            every { saveBackendModeProvider.current() } returns SaveBackendMode.CLOUD_TRANSITION

            viewModel.saveOnBackground()
            advanceUntilIdle()

            coVerify(exactly = 1) { storageFacade.save(1, any()) }
            coVerify(exactly = 1) { uploadQueue.enqueue(eq(1), any(), any()) }
            verify(exactly = 1) { uploadQueue.requestDrain() }
        }

    @Test
    fun `auto save failure lands on the persistent line, manual keeps the toast`() =
        runTest(testDispatcher) {
            SaveTriggerFlag.autoSaveOnMonthChange = true
            coEvery { storageFacade.save(any(), any()) } returns
                SaveResult.failure(SaveError.SLOT_EMPTY, "模拟落盘失败")

            monthEvents.tryEmit(Unit)
            advanceUntilIdle()

            assertEquals(
                "自动口径失败必须可见但不刷屏（持久一行）",
                "自动存档失败：保存失败，请重试",
                viewModel.autoSaveNotice.value
            )

            viewModel.autoSaveNoticeFlow.value = null
            viewModel.saveGame("1")
            advanceUntilIdle()
            assertNull("手动口径失败仍走 snackbar，不占用自动存档行", viewModel.autoSaveNotice.value)
        }

    @Test
    fun `reportSaveSuccess routes each feedback to its own channel`() {
        val data = gameData(year = 7, month = 11)

        viewModel.reportSaveSuccess(SaveFeedback.Manual, data, postSaveWarning = null)
        assertNull("手动口径不写自动存档行", viewModel.autoSaveNotice.value)

        viewModel.reportSaveSuccess(SaveFeedback.AutoNotice, data, postSaveWarning = "mirror failed")
        assertEquals(
            "自动存档降级必须带进可见文案（审计 §12-C 同纪律）",
            "已自动存档 · 第7年11月（备份未写入）",
            viewModel.autoSaveNotice.value
        )

        viewModel.autoSaveNoticeFlow.value = null
        viewModel.reportSaveSuccess(SaveFeedback.Silent, data, postSaveWarning = "mirror failed")
        assertNull("后台口径不产生 UI 态", viewModel.autoSaveNotice.value)
    }

    @Test
    fun `notice text carries game time and degradation suffix`() {
        assertEquals("已自动存档 · 第1年3月", autoSaveNoticeText(gameYear = 1, gameMonth = 3, degraded = false))
        assertEquals(
            "已自动存档 · 第1年3月（备份未写入）",
            autoSaveNoticeText(gameYear = 1, gameMonth = 3, degraded = true)
        )
    }

    private fun gameData(year: Int, month: Int) = GameData(
        sectName = "青云宗",
        saveVersion = 2,
        gameYear = year,
        gameMonth = month,
        currentSlot = 1
    )

    private fun snapshot() = GameStateSnapshot(
        gameData = gameData(year = 3, month = 5),
        disciples = emptyList(),
        equipmentStacks = emptyList(),
        equipmentInstances = emptyList(),
        manualStacks = emptyList(),
        manualInstances = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
        battleLogs = emptyList(),
        alliances = emptyList()
    )

}
