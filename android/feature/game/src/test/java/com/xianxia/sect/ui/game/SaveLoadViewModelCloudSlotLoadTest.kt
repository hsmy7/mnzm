package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.BootSequenceController
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.ui.game.saveload.PersistenceFacade
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
import org.junit.Before
import org.junit.Test

/**
 * SaveLoadViewModel 云槽位下载落盘链单测（SR-3，方案 §2 读路径 CLOUD_TRANSITION 起）。
 *
 * 锚定面：
 * 1. 落盘链（审计 §3/§12-I 修复面）：下载 → 校验/迁移 → **storageFacade.save 落缓存** →
 *    账本 adoptCloudState → 既有 boot 链；
 * 2. verdict 分流：UPLOAD_PENDING 拒绝覆盖；CONFLICT 短路（不落盘不报错不 boot）；
 * 3. LEGACY 模式门控（硬红线）：download 零调用；
 * 4. 落缓存失败中止 boot（不带病进游戏）；
 * 5. 迁移拒绝（saveVersion 越界）不落盘。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveLoadViewModelCloudSlotLoadTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── SaveLoadViewModel 注入依赖（MockK relaxed，同 SaveLoadViewModelLoadTest 基建）──
    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val stateStore: GameStateStore = mockk(relaxed = true)
    private val gameClock: GameTimeClock = mockk(relaxed = true)
    private val resourcePreloader: ResourcePreloader = mockk(relaxed = true)
    private val persistenceFacade: PersistenceFacade = mockk(relaxed = true)
    private val ioDispatcher = IoDispatcher(testDispatcher)

    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val tapCloudSaveManager: TapCloudSaveManager = mockk(relaxed = true)
    private val uploadQueue: UploadQueue = mockk(relaxed = true)
    private val saveBackend: SaveBackend = mockk(relaxed = true)
    private val uploadLedger: UploadLedger = mockk(relaxed = true)
    private val saveBackendModeProvider: SaveBackendModeProvider = mockk()
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val bootSequenceController: BootSequenceController = mockk(relaxed = true)

    private lateinit var viewModel: SaveLoadViewModel

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(testDispatcher)

        // 纯 JVM 环境（非 Robolectric）：android.util.Log 需要 mock
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        every { persistenceFacade.storageFacade } returns storageFacade
        every { persistenceFacade.tapCloudSaveManager } returns tapCloudSaveManager
        every { persistenceFacade.uploadQueue } returns uploadQueue
        every { uploadQueue.events } returns MutableSharedFlow()
        // SR-3：云槽位链依赖 + conflicts 流须桩真实 SharedFlow（relaxed mock 的
        // collect 抛 KotlinNothingValueException，SR-2 §5.2 教训）
        every { persistenceFacade.saveBackend } returns saveBackend
        every { saveBackend.conflicts } returns MutableSharedFlow()
        every { persistenceFacade.uploadLedger } returns uploadLedger
        every { persistenceFacade.saveBackendModeProvider } returns saveBackendModeProvider
        every { saveBackendModeProvider.current() } returns SaveBackendMode.CLOUD_TRANSITION
        every { persistenceFacade.sessionManager } returns sessionManager
        every { persistenceFacade.bootSequenceController } returns bootSequenceController
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(false)
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(Unit)
        every { sessionManager.isLoggedIn } returns true
        coEvery { storageFacade.load(any()) } returns
            SaveResult.failure(SaveError.SLOT_EMPTY, "no current save")
        coEvery { storageFacade.getSaveSlotsSuspend() } returns listOf(
            com.xianxia.sect.data.model.SaveSlot(
                slot = 0, name = "云存档", timestamp = 0L, gameYear = 0, gameMonth = 0,
                sectName = "云存档", discipleCount = 0, spiritStones = 0L, isEmpty = false
            )
        )
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        every { gameEngineCore.stuckResetEvents } returns MutableSharedFlow()
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } returns true
        every { gameEngine.gameData } returns MutableStateFlow(
            GameData(sectName = "青云宗", saveVersion = 2)
        )

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
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun cloudSaveData(gd: GameData = GameData(sectName = "青云宗", saveVersion = 2)): SaveData =
        SaveData(
            gameData = gd,
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )

    private fun stubDownload(
        slot: Int,
        payload: CloudSavePayload
    ) {
        coEvery { saveBackend.download(slot) } returns SaveBackendResult.Success(payload)
        coEvery { storageFacade.save(slot, any()) } returns SaveResult.success(Unit)
    }

    // ──────────────────────────────────────────────────────────────────
    // 落盘链：下载 → 落缓存 → 账本收敛 → boot
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `cloud slot load writes cache to disk adopts ledger and boots`() = runTest(testDispatcher) {
        stubDownload(2, CloudSavePayload(cloudSaveData(), saveId = 7, verdict = ArbitrationVerdict.LOCAL_BEHIND))

        viewModel.loadCloudSlot(2)
        advanceUntilIdle()

        // 落缓存（审计 §3/§12-I 修复面核心断言）：下载的数据写入本地缓存槽 2
        coVerify { storageFacade.save(2, any()) }
        // 账本基线收敛：W=7 已知 → adoptCloudState（非 recordLocalSave——下载不是新保存）
        verify { uploadLedger.adoptCloudState(2, 7L) }
        verify(exactly = 0) { uploadLedger.recordLocalSave(2) }
        // 既有 boot 链照走
        coVerify { gameEngineCore.stopGameLoopAndWait(any()) }
        coVerify {
            bootSequenceController.boot(slot = 2, any(), any(), any(), any(), any(), any())
        }
        assertEquals(
            CloudSaveOperationState.Success::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    @Test
    fun `cloud slot load with unknown W keeps ledger untouched`() = runTest(testDispatcher) {
        // 存量档无 saveId（W 未知，U11）——账本保持原状
        stubDownload(3, CloudSavePayload(cloudSaveData(), saveId = null, verdict = ArbitrationVerdict.IN_SYNC))

        viewModel.loadCloudSlot(3)
        advanceUntilIdle()

        coVerify { storageFacade.save(3, any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // verdict 分流：UPLOAD_PENDING 拒绝覆盖
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `upload pending verdict refuses overwrite and skips cache write`() = runTest(testDispatcher) {
        // 本机有未上传新进度（L>C）且云端并不更新——下载覆盖会丢本机进度
        stubDownload(1, CloudSavePayload(cloudSaveData(), saveId = 5, verdict = ArbitrationVerdict.UPLOAD_PENDING))

        viewModel.loadCloudSlot(1)
        advanceUntilIdle()

        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
        assertEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // CONFLICT 短路：不落盘、不 boot、不置硬错误（冲突弹窗接管）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `conflict failure short circuits without cache write or boot`() = runTest(testDispatcher) {
        coEvery { saveBackend.download(4) } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度，需要选择保留哪一份"
        )

        viewModel.loadCloudSlot(4)
        advanceUntilIdle()

        // 禁止静默覆盖：冲突时不写缓存、不 boot、不置 Error（等玩家二选一）
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 0) { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(
            CloudSaveOperationState.Idle,
            viewModel.cloudSaveOperationState.value
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // LEGACY 模式门控（硬红线）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `legacy mode rejects cloud slot load before any backend call`() = runTest(testDispatcher) {
        every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY

        viewModel.loadCloudSlot(1)
        advanceUntilIdle()

        coVerify(exactly = 0) { saveBackend.download(any()) }
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        assertEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 落缓存失败：如实报错中止 boot（不带病进游戏）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `cache write failure aborts before boot with honest error`() = runTest(testDispatcher) {
        coEvery { saveBackend.download(5) } returns SaveBackendResult.Success(
            CloudSavePayload(cloudSaveData(), saveId = 9, verdict = ArbitrationVerdict.LOCAL_BEHIND)
        )
        coEvery { storageFacade.save(5, any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "disk io error")

        viewModel.loadCloudSlot(5)
        advanceUntilIdle()

        coVerify(exactly = 0) { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 迁移拒绝：saveVersion 越界不落盘
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `rejected migration skips cache write`() = runTest(testDispatcher) {
        stubDownload(
            6,
            CloudSavePayload(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 99)),
                saveId = 3,
                verdict = ArbitrationVerdict.LOCAL_BEHIND
            )
        )

        viewModel.loadCloudSlot(6)
        advanceUntilIdle()

        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        assertEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }
}
