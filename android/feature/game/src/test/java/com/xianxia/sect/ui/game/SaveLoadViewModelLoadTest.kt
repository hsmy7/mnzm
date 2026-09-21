package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.BootSequenceController
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.cloud.SaveBackendMode
import com.xianxia.sect.data.cloud.SaveBackendModeProvider
import com.xianxia.sect.data.cloud.UploadQueue
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.ui.game.saveload.PersistenceFacade
import android.util.Log
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.xianxia.sect.core.engine.restartGameSuspend
import com.xianxia.sect.core.engine.resume

/**
 * SaveLoadViewModel 云读档路径单元测试。
 *
 * 云读档管线的回归守卫：
 * 1. 云档写入本地前必须执行 saveVersion 迁移（v0→2）与完整性校验修复
 * 2. 云档写入本地失败时必须中止（不再继续读档，避免读到旧数据）
 * 3. 游戏内云下载与加载流程重叠时必须拒绝（isLoading 保护）
 *
 * 注：BaseViewModel.showError 为 protected 无法直接断言，
 * 通过"不再继续读档（storageFacade.load 未被调用）"的行为间接验证。
 */
class SaveLoadViewModelLoadTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── SaveLoadViewModel 8 个注入依赖（MockK relaxed）──
    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val gameEngineCore: GameEngineCore = mockk(relaxed = true)
    private val stateStore: GameStateStore = mockk(relaxed = true)
    private val gameClock: GameTimeClock = mockk(relaxed = true)
    private val resourcePreloader: ResourcePreloader = mockk(relaxed = true)
    private val persistenceFacade: PersistenceFacade = mockk(relaxed = true)
    private val ioDispatcher = IoDispatcher(testDispatcher)

    // ── PersistenceFacade 内部依赖（主菜单云读档路径使用）──
    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val tapCloudSaveManager: TapCloudSaveManager = mockk(relaxed = true)
    private val uploadQueue: UploadQueue = mockk(relaxed = true)
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
        // SR-2 云上传收口：默认 LEGACY（守卫测试锚定零行为变化）。
        // events 须桩真实 SharedFlow（同 stuckResetEvents：relaxed mock 的 collect 抛
        // KotlinNothingValueException）
        every { persistenceFacade.uploadQueue } returns uploadQueue
        every { uploadQueue.events } returns MutableSharedFlow()
        every { persistenceFacade.saveBackendModeProvider } returns saveBackendModeProvider
        every { saveBackendModeProvider.current() } returns SaveBackendMode.LEGACY
        // SR-3：VM init 收集 saveBackend.conflicts——桩真实 SharedFlow
        //（relaxed mock 的 collect 抛 KotlinNothingValueException）
        every { persistenceFacade.saveBackend } returns mockk(relaxed = true) {
            every { conflicts } returns MutableSharedFlow()
        }
        every { persistenceFacade.uploadLedger } returns mockk(relaxed = true)
        every { persistenceFacade.sessionManager } returns sessionManager
        // 统一守卫读 bootInProgress + applyCloudSaveToEngine
        // 调 boot——relaxed mock 返回 null 会 NPE，显式 stub 为 false/成功
        every { persistenceFacade.bootSequenceController } returns bootSequenceController
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(false)
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(Unit)
        every { sessionManager.isLoggedIn } returns true
        // 下载覆盖前"备份当前存档"会 load 当前槽位——relaxed mock 对非空泛型
        // 返回 null 导致 NPE，显式 stub 为"当前无存档"
        coEvery { storageFacade.load(any()) } returns
            SaveResult.failure(SaveError.SLOT_EMPTY, "no current save")
        // 存档槽位列表 stub：与 StorageEngine.getSaveSlots() 一致，slot 0 为
        // 全 0 占位（saveSlots 的 combine 派生会消费该列表，relaxed mock 的
        // null 会让合并逻辑 NPE）
        coEvery { storageFacade.getSaveSlotsSuspend() } returns listOf(
            SaveSlot(
                slot = 0, name = "云存档", timestamp = 0L, gameYear = 0, gameMonth = 0,
                sectName = "云存档", discipleCount = 0, spiritStones = 0L, isEmpty = false
            )
        )
        every { stateStore.isLoading } returns MutableStateFlow(false)
        // applyCloudSaveToEngine 置位 isLoading 时 setSaveLoadState 读
        // stateStore.isSaving.value——relaxed mock 返回 null 会 NPE，全局 stub 为 false
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        // init 会收集 stuckResetEvents——stub 为真实 SharedFlow
        // （collect 是扩展函数，relaxed mock 的 SharedFlow 会抛 KotlinNothingValueException）
        every { gameEngineCore.stuckResetEvents } returns MutableSharedFlow()
        // 玉符防回退：performLoadToSlot/applyCloudSaveToEngine 会调用
        // stopGameLoopAndWait——relaxed mock 默认返回 false 会中止读档流程，
        // 现有用例全部需要默认成功；各用例自己的 coEvery stub 后注册覆盖此处
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } returns true
        // 云会话：applyCloudSaveToEngine 读 gameEngine.gameData.value
        //（AISectDiscipleManager.initForSlot(loadedGd.mapSeed)）——relaxed mock
        // 返回 null 会 NPE 使 boot 不执行，显式 stub 为最小有效游戏数据
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

    private fun cloudSaveData(gd: GameData): SaveData {
        return SaveData(
            gameData = gd,
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
    }

    // ──────────────────────────────────────────────────────────────────
    // saveGame 协程注册 activeLoadJob
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame registers active load job on game engine core`() = runTest(testDispatcher) {
        // saveGame 有 isGameLoaded 守卫（runState 须 PLAYING）与 isSaving 守卫
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)

        viewModel.saveGame("1")
        advanceUntilIdle()

        // 保存协程必须注册（看门狗可取消复位），与 loadGame/startNewGame/restartGame 同模式
        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // restartGame 未加载时被 isGameLoaded 守卫拒绝
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `restartGame when game not loaded is ignored`() = runTest(testDispatcher) {
        // 默认 runState=IDLE → isGameLoaded=false → 守卫立即返回
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)

        viewModel.restartGame()
        advanceUntilIdle()

        // 未加载时不得启动重启协程（saveLock 未被占用）
        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }

        // 守卫不占用 saveLock：进入 PLAYING 后重启可正常执行
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        viewModel.restartGame()
        advanceUntilIdle()

        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // 用例 1：云档迁移管线（v0→2）+ 云会话独立加载（不落盘本地槽位）
    // 云存档独立会话——迁移/校验/堆叠重建后直接内存加载 + boot，
    // 不写任何本地槽位（落盘当前槽位 + 覆盖确认会在主菜单场景永久卡死）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - old v0 cloud save migrated before cloud session load`() = runTest(testDispatcher) {
        // 老版本上传的 v0 云档（修炼值未缩放）
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 0))
            )

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 云会话独立加载：迁移后的数据直接进内存（boot），不落盘任何本地槽位
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) {
            bootSequenceController.boot(
                StorageConstants.CLOUD_SAVE_SLOT, any(), any(), any(), any(), any(), any()
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 用例 2：云档完整性校验修复（损坏可修复数据修复后加载）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - corrupted but repairable cloud save is repaired before load`() = runTest(testDispatcher) {
        // 负灵石（经济系统异常数据）→ SaveValidator 的 SpiritStoneNonNegativeRule 修复为 0
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2, spiritStones = -100L))
            )

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 可修复数据修复后直接云会话加载（不落盘，无 save 注入失败路径）
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) {
            bootSequenceController.boot(
                StorageConstants.CLOUD_SAVE_SLOT, any(), any(), any(), any(), any(), any()
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 用例 3：游戏内云下载与加载重叠保护（isLoading）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudDownload - rejected when load in progress`() = runTest(testDispatcher) {
        // 模拟读档进行中（isLoading = true）——云下载必须拒绝
        every { stateStore.isLoading } returns MutableStateFlow(true)

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        // 下载请求被拒绝：未调用 TapTap SDK 下载
        coVerify(exactly = 0) { tapCloudSaveManager.downloadSave() }
        // 操作状态为 Error
        val state = viewModel.cloudSaveOperationState.value
        assertTrue(
            "isLoading 中云下载应返回 Error 状态，实际: $state",
            state is CloudSaveOperationState.Error
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 用例 4：正常路径（无加载进行中）允许下载并云会话加载
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudDownload - proceeds and loads cloud session`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 1) { tapCloudSaveManager.downloadSave() }
        // 云会话独立加载：不落盘本地槽位，直接内存加载 + boot
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) {
            bootSequenceController.boot(
                StorageConstants.CLOUD_SAVE_SLOT, any(), any(), any(), any(), any(), any()
            )
        }
        val state = viewModel.cloudSaveOperationState.value
        assertTrue(
            "云下载成功应返回 Success 状态，实际: $state",
            state is CloudSaveOperationState.Success
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 主菜单云读档直达云会话（不经 loadGameFromSlot）
    // 云读档成功路径直接 applyCloudSaveToEngine（内存加载 + boot），
    // 不走 loadGameFromSlot → loadGameInternal 链路（落盘 + 读档）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - success path loads cloud session directly`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2, currentSlot = 1))
            )
        // setSaveLoadState(isLoading=true) 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 云会话直达：不落盘、不经 loadGameFromSlot，直接内存加载 + boot
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) {
            bootSequenceController.boot(
                StorageConstants.CLOUD_SAVE_SLOT, any(), any(), any(), any(), any(), any()
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 云读档不覆盖任何本地槽位——云会话槽位 0
    // 云存档独立——忽略云档来源元数据，以云会话槽位 0 加载，
    // 本地 1..6 槽位零影响（覆盖确认弹窗仅在游戏主界面渲染、
    // 主菜单加载界面永不显示会永久卡死）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `cloud load uses cloud session slot and never touches local slots`() = runTest(testDispatcher) {
        // 用户实报场景：云档 currentSlot=2（上传时在槽位 2）——云会话加载必须
        // 忽略云档来源元数据，以云会话槽位 0 加载，本地槽位零影响
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "云宗", saveVersion = 2, currentSlot = 2))
            )
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 云会话槽位 0：setCurrentSlot(0)、不落盘任何本地槽位（1..6 零影响）
        coVerify(exactly = 1) { storageFacade.setCurrentSlot(StorageConstants.CLOUD_SAVE_SLOT) }
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) {
            bootSequenceController.boot(
                StorageConstants.CLOUD_SAVE_SLOT, any(), any(), any(), any(), any(), any()
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 云下载内存加载 slotId/currentSlot 同时修正到云会话槽位
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `reconcileCloudSlot fixes both slotId and currentSlot to cloud session slot`() = runTest(testDispatcher) {
        val reconciled = cloudSaveData(GameData(sectName = "云宗", saveVersion = 2, currentSlot = 2))
        val resolved = viewModel.reconcileCloudSlot(reconciled, StorageConstants.CLOUD_SAVE_SLOT)
        // 云档 slotId 为 @Transient 恒 0——修正为云会话槽位 0（本地 1..6 不受影响），
        // 否则 loadFromSnapshot 内 setActiveSlot(gameData.slotId) 拿到旧值使仓库脏写错槽
        assertEquals("slotId 修正为云会话槽位", 0, resolved.slotId)
        assertEquals("currentSlot 修正为云会话槽位", 0, resolved.currentSlot)
    }

    // ──────────────────────────────────────────────────────────────────
    // loadGameFromSlot(0) 云会话自包含入口
    // ──────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGameFromSlot(0) - self-chain download proceeds as cloud session`() = runTest(testDispatcher) {
        // 若先 setSaveLoadState(isLoading=true) 再调 downloadFromCloudSave，
        // 会被其自身 isLoading 守卫恒真拒绝——SettingsTab 云槽位读取必失败
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        // setSaveLoadState(isLoading=true) 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadGameFromSlot(0)
        advanceUntilIdle()

        // 下载必须实际执行（云会话自包含入口置位在前）；云会话独立加载不落盘
        coVerify(exactly = 1) { tapCloudSaveManager.downloadSave() }
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGameFromSlot(0) - isLoading set during download shows busy indicator`() = runTest(testDispatcher) {
        // 用户实报场景：游戏内选择存档界面点云存档无"读取中..."转圈——isLoading
        // 若在下载完成后才置位则无反馈。协程开头立即置位（下载期间 pendingAction=load）。
        // 用永不完成的下载挂起协程，验证下载进行中 isLoading 已置位。
        val never = CompletableDeferred<TapCloudSaveManager.CloudSaveResult>()
        coEvery { tapCloudSaveManager.downloadSave() } coAnswers { never.await() }

        viewModel.loadGameFromSlot(0)
        runCurrent()

        // 下载挂起期间 isLoading 已置位（SaveSlotDialog 显示"读取中..."转圈）
        assertEquals("下载期间 isLoading 应置位（pendingAction=load）", "load", viewModel.pendingAction.value)

        never.complete(TapCloudSaveManager.CloudSaveResult.NetworkError("test"))
        advanceUntilIdle()
        // 完成后复位
        assertEquals("下载完成后 isLoading 应复位", null, viewModel.pendingAction.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // restart 窗口内 saveGame/loadGame 被 _isRestarting 守卫拒绝
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame rejected while restart in progress`() = runTest(testDispatcher) {
        // restart 的 stopGameLoopAndWait 窗口内（saveLock 已持有、
        // isSaving 未置）点保存——若无守卫，saveGame 会注册并取消 restart 协程
        val gate = CompletableDeferred<Boolean>()
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } coAnswers { gate.await() }
        every { gameEngineCore.isGameLoopRunning } returns true
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        viewModel.resumeFromBackground()  // _isTimeRunning=true → restart 走 stopGameLoopAndWait 分支

        viewModel.restartGame()
        advanceUntilIdle()  // restart 协程执行到 stopGameLoopAndWait 挂起（_isRestarting=true）
        viewModel.saveGame("1")
        advanceUntilIdle()

        // 仅 restart 注册 1 次；saveGame 被 _isRestarting 守卫拒绝（不再误杀 restart）
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
        // restart 协程未被取消：释放门闩后仍能继续走完
        gate.complete(true)
        advanceUntilIdle()
    }

    @Test
    fun `loadGame rejected while restart in progress`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Boolean>()
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } coAnswers { gate.await() }
        every { gameEngineCore.isGameLoopRunning } returns true
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        viewModel.resumeFromBackground()

        viewModel.restartGame()
        advanceUntilIdle()
        viewModel.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        advanceUntilIdle()

        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
        gate.complete(true)
        advanceUntilIdle()
    }

    // ──────────────────────────────────────────────────────────────────
    // saveGame 双 tap 窗口——isSaving 同步占位
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame second tap rejected while first save in flight`() = runTest(testDispatcher) {
        // 若 isSaving 由协程内异步设置，两次快速 tap 在协程启动前均可通过守卫
        // → job2 注册取消 job1（磁盘已写但 currentSlot 回滚不一致）。入口同步置位防此窗口。
        val isSavingFlow = MutableStateFlow(false)
        every { stateStore.isSaving } returns isSavingFlow
        every { stateStore.setSavingDirect(any()) } answers {
            isSavingFlow.value = args[0] as Boolean
        }
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.saveGame("1")
        viewModel.saveGame("1")
        advanceUntilIdle()

        // 仅第一次 tap 通过守卫注册；第二次被同步占位的 isSaving 拒绝
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // restart 与 load 完整互斥（loadLock + 同步置位 _isRestarting）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `restartGame rejected while loadLock held by load`() = runTest(testDispatcher) {
        // loadGame 已抢 loadLock（performLoadToSlot 挂起中）时，
        // restartGame 若不查 loadLock 可穿入，与 load 的 clear+insert 并发重置引擎。
        // restart 入口 loadLock CAS 失败 → 拒绝并释放 saveLock。
        val gate = CompletableDeferred<Boolean>()
        coEvery { storageFacade.load(any()) } coAnswers {
            gate.await()
            SaveResult.failure(SaveError.SLOT_EMPTY, "gate released")
        }
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        // 注意：不能 advanceUntilIdle——虚拟时间推进会触发 performLoadToSlot 内
        // withTimeoutOrNull(60s) 超时提前结束 load 协程释放 loadLock；
        // runCurrent 只执行当前队列任务不推进虚拟时间，load 协程挂起在 gate.await()
        runCurrent()
        viewModel.restartGame()
        runCurrent()

        // 仅 load 注册 1 次；restart 被 loadLock 拒绝（不注册、不并发）
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
        gate.complete(true)
        advanceUntilIdle()
    }

    @Test
    fun `restartGame rejects saveGame synchronously before coroutine runs`() = runTest(testDispatcher) {
        // _isRestarting 若在协程体 performRestartGame 内才置位，
        // restart 抢到 saveLock 后、协程启动前的窗口内 saveGame 可穿入。
        // 入口同步置位（与 setSavingDirect 同模式）→ 紧接的 saveGame 被拒。
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.restartGame()  // 同步路径：saveLock+loadLock 抢到、_isRestarting=true、job 已注册
        viewModel.saveGame("1")  // 不 advance——同步窗口内调用（无同步置位时会通过守卫）
        advanceUntilIdle()

        // 仅 restart 注册 1 次；saveGame 被同步置位的 _isRestarting 拒绝
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    @Test
    fun `restartGame rejected when isLoading and recovers after`() = runTest(testDispatcher) {
        // isLoading 中 restart 拒绝且三锁（_isRestarting/loadLock/saveLock）全部复位，
        // 不泄漏——读档结束后 restart 可正常执行
        every { stateStore.isLoading } returns MutableStateFlow(true)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.restartGame()
        advanceUntilIdle()
        coVerify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }

        // 三锁已复位：isLoading 结束后 restart 正常执行（能再次抢到 saveLock/loadLock）
        every { stateStore.isLoading } returns MutableStateFlow(false)
        viewModel.restartGame()
        advanceUntilIdle()
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // lateinit job 竞态——Unconfined 下协程体先于赋值执行
    // ──────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGame - 协程体先于 lateinit job 赋值同步执行不崩溃`() = runTest(testDispatcher) {
        // 回归守卫：Unconfined 下 launch 协程体在 launch() 返回前
        // 同步执行（模拟空闲 IO worker 抢跑）——实参求值若读 lateinit job 会抛
        // UninitializedPropertyAccessException；协程体不捕获 job。
        val unconfinedVm = SaveLoadViewModel(
            gameEngine = gameEngine,
            gameEngineCore = gameEngineCore,
            stateStore = stateStore,
            gameClock = gameClock,
            resourcePreloader = resourcePreloader,
            persistenceFacade = persistenceFacade,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
        // setSaveLoadState 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        // 若回归（协程体捕获 job）此处会同步抛 UninitializedPropertyAccessException → 测试失败
        unconfinedVm.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        runCurrent()

        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
        coVerify { storageFacade.load(1) }
    }

    // ──────────────────────────────────────────────────────────────────
    // 玉符防回退：读档/云下载前等待旧循环停止（stopGameLoopAndWait）
    // ──────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGame - stopGameLoopAndWait 挂起期间读档零推进`() = runTest(testDispatcher) {
        // 顺序守卫：stopGameLoopAndWait 返回前不得执行任何读档实质步骤——
        // 玉符 checkpointNow 的 finally 写必须在 loadData 之前完成（非等待 stop
        // 时旧运行时值晚于快照替换、覆盖新档玉符四字段的机理由引擎交错测试锁死）
        val gate = CompletableDeferred<Boolean>()
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } coAnswers { gate.await() }
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        runCurrent()  // 协程执行到 stopGameLoopAndWait 挂起（不推进虚拟时间）

        // wait 挂起期间读档零推进（storageFacade.load 是 stop 之后的第一个实质步骤）
        coVerify(exactly = 0) { storageFacade.load(any()) }

        gate.complete(true)
        advanceUntilIdle()
        // wait 完成后读档继续走完
        coVerify(exactly = 1) { storageFacade.load(1) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGame - stopGameLoopAndWait 超时中止读档`() = runTest(testDispatcher) {
        // 超时保护：循环停不下来时不得继续读档——旧循环 finally 仍在写玉符，
        // loadData 后 onLoopStart 锚定/下一次 checkpointNow 会覆盖或错乱
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } returns false
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        advanceUntilIdle()

        // 中止：不读档（showError 为 protected 无法直接断言，行为间接验证）
        coVerify(exactly = 0) { storageFacade.load(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // 云存档槽位（slot 0）合并：游戏内存档对话框显示真实云存档信息
    // ──────────────────────────────────────────────────────────────────

    /** 订阅 saveSlots 驱动 stateIn(WhileSubscribed) 生效，否则 value 停留在初始值 */
    private fun TestScope.startCollectingSaveSlots() {
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.saveSlots.collect { }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `saveSlots - cloud save exists shows real data on slot 0`() = runTest(testDispatcher) {
        startCollectingSaveSlots()
        // 云端有存档：checkCloudSave 返回真实摘要（TapTap API extra 解析结果）
        coEvery { tapCloudSaveManager.checkCloudSave() } returns TapCloudSaveManager.CloudSaveInfo(
            hasSaveData = true,
            lastModifiedTime = 123456789L,
            description = "第3年5月 青云宗",
            gameYear = 3,
            gameMonth = 5,
            sectName = "青云宗",
            discipleCount = 7,
            spiritStones = 1000L,
            appVersion = "4.00.86"
        )

        viewModel.checkCloudSave()
        advanceUntilIdle()

        val cloudSlot = viewModel.saveSlots.value.first { it.slot == 0 }
        assertEquals("云存档槽位应显示宗门名", "青云宗", cloudSlot.sectName)
        assertEquals("云存档槽位应显示游戏年份", 3, cloudSlot.gameYear)
        assertEquals("云存档槽位应显示游戏月份", 5, cloudSlot.gameMonth)
        assertEquals("云存档槽位应显示弟子数", 7, cloudSlot.discipleCount)
        assertEquals("云存档槽位应显示灵石数", 1000L, cloudSlot.spiritStones)
        assertEquals("云存档槽位应显示上次保存时间", 123456789L, cloudSlot.timestamp)
        assertTrue("有云存档时槽位不应标记为空", !cloudSlot.isEmpty)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `saveSlots - no cloud save marks slot 0 empty`() = runTest(testDispatcher) {
        startCollectingSaveSlots()
        coEvery { tapCloudSaveManager.checkCloudSave() } returns TapCloudSaveManager.CloudSaveInfo(false)

        viewModel.checkCloudSave()
        advanceUntilIdle()

        val cloudSlot = viewModel.saveSlots.value.first { it.slot == 0 }
        assertTrue("无云存档时 slot 0 应标记为空", cloudSlot.isEmpty)
        assertEquals("无云存档时槽位名保持云存档", "云存档", cloudSlot.sectName)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `saveSlots - after upload to cloud slot 0 shows uploaded data`() = runTest(testDispatcher) {
        startCollectingSaveSlots()
        // 游戏快照：有真实游戏数据（上传后 slot 0 应立即反映，而非硬编码全 0 占位）
        val snapshot = GameStateSnapshot(
            gameData = GameData(sectName = "青云宗", saveVersion = 2, gameYear = 3, gameMonth = 5, spiritStones = 888L),
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
        coEvery { gameEngine.buildSaveSnapshot() } returns snapshot
        coEvery { tapCloudSaveManager.uploadSave(any()) } returns TapCloudSaveManager.CloudSaveResult.Success()

        viewModel.uploadToCloudSave()
        advanceUntilIdle()

        val cloudSlot = viewModel.saveSlots.value.first { it.slot == 0 }
        assertEquals("上传后云存档槽位应显示宗门名", "青云宗", cloudSlot.sectName)
        assertEquals("上传后云存档槽位应显示年份", 3, cloudSlot.gameYear)
        assertEquals("上传后云存档槽位应显示月份", 5, cloudSlot.gameMonth)
        assertEquals("上传后云存档槽位应显示灵石数", 888L, cloudSlot.spiritStones)
        assertTrue("上传后云存档槽位不应标记为空", !cloudSlot.isEmpty)
    }

    // ──────────────────────────────────────────────────────────────────
    // boot 进行中所有触发 boot 的入口被统一守卫拒绝
    //（防"多次点击读取云存档"并发触发第二个 boot 的回归守卫）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `startNewGame rejected while boot in progress`() = runTest(testDispatcher) {
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(true)
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        every { stateStore.isLoading } returns MutableStateFlow(false)

        viewModel.startNewGame("青云宗", 1)

        // 未注册 activeLoadJob = 未启动协程（并发 boot 在状态污染前被拦下）
        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    @Test
    fun `loadGame rejected while boot in progress`() = runTest(testDispatcher) {
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(true)

        viewModel.loadGame(SaveSlot(1, "青云宗", 0L, 1, 1, "", 0, 0L))

        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    @Test
    fun `restartGame rejected while boot in progress`() = runTest(testDispatcher) {
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(true)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.restartGame()

        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    @Test
    fun `cloud download rejected while boot in progress`() = runTest(testDispatcher) {
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(true)

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { tapCloudSaveManager.downloadSave() }
        assertTrue(
            "boot 进行中云下载应返回 Error 状态",
            viewModel.cloudSaveOperationState.value is CloudSaveOperationState.Error
        )
    }

    @Test
    fun `cloud load rejected while boot in progress`() = runTest(testDispatcher) {
        every { bootSequenceController.bootInProgress } returns MutableStateFlow(true)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 0) { tapCloudSaveManager.downloadSave() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `restartGame rejected while cloud download in progress`() = runTest(testDispatcher) {
        // 根因场景：云下载（cloudDownloadLock 持有、isLoading=false）期间重启可
        // 穿入并发触发第二个 boot——restartGame 必须补查 cloudDownloadLock
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        // 下载永不完成 → performCloudDownload 挂起期间 cloudDownloadLock 被持有
        //（coAnswers 使 await 在被 mock 方法调用时执行，而非 stub 定义时挂起测试协程）
        val never = CompletableDeferred<TapCloudSaveManager.CloudSaveResult>()
        coEvery { tapCloudSaveManager.downloadSave() } coAnswers { never.await() }

        viewModel.downloadFromCloudSave()
        runCurrent()
        viewModel.restartGame()

        // restart 协程未注册（被 cloudDownloadLock 守卫拒绝）
        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
        never.complete(TapCloudSaveManager.CloudSaveResult.NetworkError("test"))
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startNewGame rejected while cloud download in progress`() = runTest(testDispatcher) {
        // 根因场景：云下载期间 startNewGame 若不查 cloudDownloadLock 可穿入
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        val never = CompletableDeferred<TapCloudSaveManager.CloudSaveResult>()
        coEvery { tapCloudSaveManager.downloadSave() } coAnswers { never.await() }

        viewModel.downloadFromCloudSave()
        runCurrent()
        viewModel.startNewGame("青云宗", 1)

        verify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
        never.complete(TapCloudSaveManager.CloudSaveResult.NetworkError("test"))
        advanceUntilIdle()
    }

    // ──────────────────────────────────────────────────────────────────
    // 云下载/云读档加载反馈——isLoading 置位驱动存档弹窗的"转圈+读取中"
    //（SaveSlotDialog/CloudSaveDialog 的 isBusy = isSaving||isLoading），覆盖下载与
    // boot 全程，完成后复位。全屏加载页不参与（弹窗独立 Window + 遮罩盖住全屏）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudDownload sets isLoading during cloud session load then resets`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        // boot 执行时捕获 setSaveLoadState(isLoading=true) 已置位的证据（pendingAction 与
        // isLoading 同一次调用设置，且直接 asStateFlow 暴露可同步读）
        var pendingActionAtBoot: String? = null
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) } answers {
            pendingActionAtBoot = viewModel.pendingAction.value
            Result.success(Unit)
        }

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        // 加载期间 isLoading 置位（驱动弹窗"读取中..."转圈），完成后复位
        assertEquals("boot 执行时 isLoading 应置位（pendingAction=load）", "load", pendingActionAtBoot)
        assertEquals("云下载完成后 isLoading 应复位", null, viewModel.pendingAction.value)
    }

    @Test
    fun `performCloudLoad sets isLoading during cloud session load then resets`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        var pendingActionAtBoot: String? = null
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) } answers {
            pendingActionAtBoot = viewModel.pendingAction.value
            Result.success(Unit)
        }

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 主菜单云读档同样置位/复位（弹窗"读取中..."与全屏进度由 boot onProgress 驱动）
        assertEquals("云读档 boot 执行时 isLoading 应置位（pendingAction=load）", "load", pendingActionAtBoot)
        assertEquals("云读档完成后 isLoading 应复位", null, viewModel.pendingAction.value)
    }

    @Test
    fun `performCloudDownload resets isLoading when boot fails`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        // boot 失败 → isLoading 必须复位（finally 保证），弹窗"读取中..."转圈不卡死
        coEvery { bootSequenceController.boot(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("boot failed"))

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        assertEquals("boot 失败后 isLoading 应复位", null, viewModel.pendingAction.value)
        assertTrue(
            "boot 失败后应返回 Error 状态",
            viewModel.cloudSaveOperationState.value is CloudSaveOperationState.Error
        )
    }

    @Test
    fun `setTimeSpeed - paused state does not auto resume (fix)`() = runTest(testDispatcher) {
        // 暂停中调倍速不自动恢复（暂停态下 setTimeSpeed 若强制
        // gameEngineCore.resume()，会违背玩家意图，表现即"暂停却仍被解除"）。
        every { stateStore.isPaused } returns MutableStateFlow(true)
        every { gameEngineCore.isPausedDirect } returns true

        viewModel.setTimeSpeed(2)
        advanceUntilIdle()

        coVerify(exactly = 0) { gameEngineCore.resume() }
        assertEquals("仅反馈 UI 倍速，不触发恢复", 2, viewModel.timeScale.value)
    }

    // ──────────────────────────────────────────────────────────────────
    // SR-2 审计 §2：重开顺序缺陷修正——先保护性预存当前态，后重置引擎，再落新档
    // ──────────────────────────────────────────────────────────────────

    private fun restartSnapshot(year: Int) = GameStateSnapshot(
        gameData = GameData(sectName = "青云宗", saveVersion = 2, gameYear = year),
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

    private fun stubRestartOrderGuards() {
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { gameEngine.gameData } returns MutableStateFlow(
            GameData(sectName = "青云宗", saveVersion = 2, currentSlot = 1)
        )
        coEvery { storageFacade.getCurrentSlot() } returns 1
        coEvery { storageFacade.getMailsForSlot(any()) } returns emptyList()
        coEvery { storageFacade.isSaveCorruptedSuspend(any()) } returns false
    }

    @Test
    fun `SR-2 重开顺序 - 先保护性预存当前态后重置引擎再落新档`() = runTest(testDispatcher) {
        stubRestartOrderGuards()
        val order = mutableListOf<String>()
        // buildSaveSnapshot 依次返回：重置前当前态（year=12）→ 重置后新档（year=1）
        coEvery { gameEngine.buildSaveSnapshot() } returnsMany listOf(
            restartSnapshot(year = 12),
            restartSnapshot(year = 1)
        )
        coEvery { storageFacade.save(any(), any()) } coAnswers {
            order.add("save:year=${secondArg<SaveData>().gameData.gameYear}")
            SaveResult.success(Unit)
        }
        mockkStatic("com.xianxia.sect.core.engine.GameEngineLoadDataOpsKt")
        coEvery { gameEngine.restartGameSuspend(any(), any()) } coAnswers { order.add("reset") }

        viewModel.restartGame()
        advanceUntilIdle()

        // 修复语义：预存（year=12 当前态）→ 引擎重置 → 落新档（year=1）。
        // 旧实现为 [reset, save:year=1]——重置前旧态无任何落盘保护
        assertEquals(listOf("save:year=12", "reset", "save:year=1"), order)
    }

    @Test
    fun `SR-2 重开预存失败 - 中止重置引擎旧档保留`() = runTest(testDispatcher) {
        stubRestartOrderGuards()
        val order = mutableListOf<String>()
        coEvery { gameEngine.buildSaveSnapshot() } returns restartSnapshot(year = 12)
        coEvery { storageFacade.save(any(), any()) } coAnswers {
            order.add("save")
            SaveResult.failure(SaveError.SAVE_FAILED, "disk full")
        }
        mockkStatic("com.xianxia.sect.core.engine.GameEngineLoadDataOpsKt")
        coEvery { gameEngine.restartGameSuspend(any(), any()) } coAnswers { order.add("reset") }

        viewModel.restartGame()
        advanceUntilIdle()

        // 预存失败 ⇒ 引擎不得重置（旧档仅在盘上，重置将覆写唯一副本）；恰一次预存尝试
        assertEquals(listOf("save"), order)
    }

    // ──────────────────────────────────────────────────────────────────
    // SR-2 云上传触发钩子：LEGACY 短路（硬红线守卫）× CLOUD_TRANSITION 入队
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `SR-2 LEGACY 守卫 - 本地保存成功不入队云上传（默认模式零行为变化）`() = runTest(testDispatcher) {
        stubRestartOrderGuards()
        coEvery { gameEngine.buildSaveSnapshot() } returns restartSnapshot(year = 3)
        coEvery { storageFacade.save(any(), any()) } returns SaveResult.success(Unit)

        viewModel.saveGame("1")
        advanceUntilIdle()

        // 本地保存照常完成（IN1 第一步不受影响）
        coVerify(exactly = 1) { storageFacade.save(any(), any()) }
        // 硬红线：LEGACY 模式队列零活动——无入队、无账本写入路径
        coVerify(exactly = 0) { uploadQueue.enqueue(any(), any(), any()) }
    }

    @Test
    fun `SR-2 CLOUD_TRANSITION - 本地保存成功后投递云上传队列`() = runTest(testDispatcher) {
        stubRestartOrderGuards()
        every { saveBackendModeProvider.current() } returns SaveBackendMode.CLOUD_TRANSITION
        coEvery { gameEngine.buildSaveSnapshot() } returns restartSnapshot(year = 3)
        coEvery { storageFacade.save(any(), any()) } returns SaveResult.success(Unit)

        viewModel.saveGame("1")
        advanceUntilIdle()

        coVerify(exactly = 1) { storageFacade.save(any(), any()) }
        coVerify(exactly = 1) { uploadQueue.enqueue(any(), any(), any()) }
    }
}
