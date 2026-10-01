package com.xianxia.sect.ui.game

import android.util.Log
import com.xianxia.sect.core.engine.BootSequenceController
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
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
import com.xianxia.sect.data.cloud.SaveConflictEvent
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.ui.game.saveload.CloudSaveCacheWriter
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * SaveLoadViewModel 云档下载落盘链单测（换设备续玩的持久化面锚定）。
 *
 * 锚定面：
 * 1. 落盘链：下载 → 校验 → **storageFacade.save 落缓存** →
 *    账本 adoptCloudState → 既有 boot 链；
 * 2. verdict 分流：UPLOAD_PENDING 拒绝覆盖；CONFLICT 短路（不落盘不报错不 boot）；
 * 3. LEGACY 模式门控（硬红线）：download 零调用；
 * 4. 落缓存失败中止 boot（不带病进游戏）；
 * 5. 高版本云档版本戳仅作识别，校验通过即落盘。
 *
 * 夹具的 `cloudSaveCacheWriter` 是**真实组件**（吃同一批 mock，不 stub 空转），
 * 上面 5 条锚定面同时覆盖下载落盘段（`CloudSaveCacheWriter`）的实际行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveLoadViewModelCloudSaveLoadTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── SaveLoadViewModel 注入依赖（MockK relaxed，同 SaveLoadViewModelLoadTest 基建）──
    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val stateStore: GameStateStore = mockk(relaxed = true)
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

    // 双源冲突事件流（真实 SharedFlow 桩：VM init 收集器消费；SR-2 §5.2 教训）
    private val conflictEvents = MutableSharedFlow<SaveConflictEvent>(extraBufferCapacity = 8)
    private val queueEvents = MutableSharedFlow<UploadQueue.Event>(extraBufferCapacity = 8)

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
        every { uploadQueue.events } returns queueEvents
        // 云档链依赖 + conflicts 流须桩真实 SharedFlow（relaxed mock 的
        // collect 抛 KotlinNothingValueException，SR-2 §5.2 教训）
        every { persistenceFacade.saveBackend } returns saveBackend
        every { saveBackend.conflicts } returns conflictEvents
        // 下载落盘段为**真实组件**（吃同一批 mock 依赖，不用空转 stub）
        every { persistenceFacade.cloudSaveCacheWriter } returns
            CloudSaveCacheWriter(saveBackend, storageFacade, uploadLedger)
        every { persistenceFacade.uploadLedger } returns uploadLedger
        every { persistenceFacade.saveBackendModeProvider } returns saveBackendModeProvider
        every { saveBackendModeProvider.current() } returns SaveBackendMode.CLOUD_TRANSITION
        every { persistenceFacade.sessionManager } returns sessionManager
        every { persistenceFacade.bootSequenceController } returns bootSequenceController
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(false)
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any()) } returns
            Result.success(Unit)
        every { sessionManager.isLoggedIn } returns true
        coEvery { storageFacade.load() } returns
            SaveResult.failure(SaveError.SLOT_EMPTY, "no current save")
        coEvery { storageFacade.getSaveInfoSuspend() } returns
            com.xianxia.sect.data.unified.SaveInfo(isEmpty = true)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        every { gameEngineCore.stuckResetEvents } returns MutableSharedFlow()
        // init 另收集 monthSettledEvents（月变自动存档触发）——同口径桩真实流
        every { gameEngineCore.monthSettledEvents } returns MutableSharedFlow()
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } returns true
        every { gameEngine.gameData } returns MutableStateFlow(
            GameData(sectName = "青云宗", saveVersion = 2)
        )

        // 单测不启动节拍循环：主线显式启动案下循环由 MainGameScreen
        // LaunchedEffect 调 startRealtimeAutoSaveTicker 启动，构造不自启
        viewModel = SaveLoadViewModel(
            gameEngine = gameEngine,
            gameEngineCore = gameEngineCore,
            stateStore = stateStore,
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

    private fun stubDownload(payload: CloudSavePayload) {
        coEvery { saveBackend.download() } returns SaveBackendResult.Success(payload)
        coEvery { storageFacade.save(any()) } returns SaveResult.success(Unit)
    }

    // ──────────────────────────────────────────────────────────────────
    // 换设备续玩落盘链：下载 → 落缓存 → 账本收敛 → boot
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `cloud save load writes cache to disk adopts ledger and boots`() = runTest(testDispatcher) {
        stubDownload(CloudSavePayload(cloudSaveData(), saveId = 7, verdict = ArbitrationVerdict.LOCAL_BEHIND))

        viewModel.loadCloudSave()
        advanceUntilIdle()

        // 落缓存核心断言：下载的数据写入本地缓存（换设备续玩后进度持久化）
        coVerify { storageFacade.save(any()) }
        // 账本基线收敛：W=7 已知 → adoptCloudState（非 recordLocalSave——下载不是新保存）
        verify { uploadLedger.adoptCloudState(7L) }
        verify(exactly = 0) { uploadLedger.recordLocalSave() }
        // 既有 boot 链照走
        coVerify { gameEngineCore.stopGameLoopAndWait(any()) }
        coVerify {
            bootSequenceController.boot(any(), any(), any(), any(), any(), any())
        }
        assertEquals(
            CloudSaveOperationState.Success::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    @Test
    fun `cloud save load with unknown W keeps ledger untouched`() = runTest(testDispatcher) {
        // 存量档无 saveId（W 未知，U11）——账本保持原状
        stubDownload(CloudSavePayload(cloudSaveData(), saveId = null, verdict = ArbitrationVerdict.IN_SYNC))

        viewModel.loadCloudSave()
        advanceUntilIdle()

        coVerify { storageFacade.save(any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // verdict 分流：UPLOAD_PENDING 拒绝覆盖
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `upload pending verdict refuses overwrite and skips cache write`() = runTest(testDispatcher) {
        // 本机有未上传新进度（L>C）且云端并不更新——下载覆盖会丢本机进度
        stubDownload(CloudSavePayload(cloudSaveData(), saveId = 5, verdict = ArbitrationVerdict.UPLOAD_PENDING))

        viewModel.loadCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { storageFacade.save(any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
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
        coEvery { saveBackend.download() } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度，需要选择保留哪一份"
        )

        viewModel.loadCloudSave()
        advanceUntilIdle()

        // 禁止静默覆盖：冲突时不写缓存、不 boot、不置 Error（等玩家二选一）
        coVerify(exactly = 0) { storageFacade.save(any()) }
        coVerify(exactly = 0) { bootSequenceController.boot(any(), any(), any(), any(), any(), any()) }
        assertEquals(
            CloudSaveOperationState.Idle,
            viewModel.cloudSaveOperationState.value
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // LEGACY 模式门控（硬红线）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `legacy mode rejects cloud save load before any backend call`() = runTest(testDispatcher) {
        every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY

        viewModel.loadCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { saveBackend.download() }
        coVerify(exactly = 0) { storageFacade.save(any()) }
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
        coEvery { saveBackend.download() } returns SaveBackendResult.Success(
            CloudSavePayload(cloudSaveData(), saveId = 9, verdict = ArbitrationVerdict.LOCAL_BEHIND)
        )
        coEvery { storageFacade.save(any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "disk io error")

        viewModel.loadCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { bootSequenceController.boot(any(), any(), any(), any(), any(), any()) }
        assertEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 版本戳仅识别：高版本云档校验通过即落盘
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `高版本云档版本戳仅作识别 - 校验通过即落盘加载`() = runTest(testDispatcher) {
        stubDownload(
            CloudSavePayload(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 99)),
                saveId = 3,
                verdict = ArbitrationVerdict.LOCAL_BEHIND
            )
        )

        viewModel.loadCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 1) { storageFacade.save(any()) }
        assertNotEquals(
            CloudSaveOperationState.Error::class,
            viewModel.cloudSaveOperationState.value::class
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 真冲突弹窗数据面（SR-3）：双源置态 + 二选一收口
    // ──────────────────────────────────────────────────────────────────

    private fun conflictEvent(source: String) = SaveConflictEvent(
        lastLocalSaveId = 5L,
        lastConfirmedCloudId = 3L,
        cloudSaveId = 7L,
        source = source
    )

    @Test
    fun `download side conflict event surfaces pending conflict`() = runTest(testDispatcher) {
        conflictEvents.tryEmit(conflictEvent("download"))
        advanceUntilIdle()

        assertEquals(conflictEvent("download"), viewModel.pendingCloudConflict.value)
    }

    @Test
    fun `upload side ConflictHeld surfaces pending conflict`() = runTest(testDispatcher) {
        queueEvents.tryEmit(UploadQueue.Event.ConflictHeld(conflictEvent("upload")))
        advanceUntilIdle()

        assertEquals(conflictEvent("upload"), viewModel.pendingCloudConflict.value)
    }

    @Test
    fun `resolve keepCloud on download conflict adopts ledger and reloads`() = runTest(testDispatcher) {
        // 首次下载撞冲突（CONFLICT Failure 短路，见上方用例）；真实后端此时发
        // conflicts 事件——测试侧手工注入等价事件
        coEvery { saveBackend.download() } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度"
        )
        viewModel.loadCloudSave()
        advanceUntilIdle()
        conflictEvents.tryEmit(conflictEvent("download"))
        advanceUntilIdle()

        viewModel.resolveCloudConflict(keepLocal = false)
        advanceUntilIdle()

        // 基线收敛到云端 W（IN2 序号语义）+ 重跑下载（玩家选云的显式授权路径）
        verify { uploadLedger.adoptCloudState(7L) }
        coVerify(exactly = 2) { saveBackend.download() }
        assertEquals(null, viewModel.pendingCloudConflict.value)
    }

    @Test
    fun `resolve keepLocal on download conflict keeps both sides untouched`() = runTest(testDispatcher) {
        coEvery { saveBackend.download() } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度"
        )
        viewModel.loadCloudSave()
        advanceUntilIdle()

        viewModel.resolveCloudConflict(keepLocal = true)
        advanceUntilIdle()

        // 保留本机：不覆盖任何一侧（无账本收敛、无重下载）
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
        coVerify(exactly = 1) { saveBackend.download() }
        assertEquals(null, viewModel.pendingCloudConflict.value)
    }

    @Test
    fun `resolve on upload conflict delegates to queue`() = runTest(testDispatcher) {
        queueEvents.tryEmit(UploadQueue.Event.ConflictHeld(conflictEvent("upload")))
        advanceUntilIdle()

        viewModel.resolveCloudConflict(keepLocal = true)
        advanceUntilIdle()

        coVerify { uploadQueue.resolveConflict(true) }
        assertEquals(null, viewModel.pendingCloudConflict.value)
    }

    @Test
    fun `resolve without pending conflict is a no-op`() = runTest(testDispatcher) {
        viewModel.resolveCloudConflict(keepLocal = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { uploadQueue.resolveConflict(any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
    }
}
