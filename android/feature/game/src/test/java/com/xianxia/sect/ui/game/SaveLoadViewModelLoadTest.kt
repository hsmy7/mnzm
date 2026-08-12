package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * SaveLoadViewModel 云读档路径单元测试。
 *
 * 2026-08-04 云读档管线统一修复的回归守卫：
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
    private val coroutineScopeProvider: CoroutineScopeProvider = mockk(relaxed = true)
    private val gameClock: GameTimeClock = mockk(relaxed = true)
    private val resourcePreloader: ResourcePreloader = mockk(relaxed = true)
    private val persistenceFacade: PersistenceFacade = mockk(relaxed = true)
    private val ioDispatcher = IoDispatcher(testDispatcher)

    // ── PersistenceFacade 内部依赖（主菜单云读档路径使用）──
    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val tapCloudSaveManager: TapCloudSaveManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

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
        every { persistenceFacade.sessionManager } returns sessionManager
        every { sessionManager.isLoggedIn } returns true
        // 下载覆盖前"备份当前存档"会 load 当前槽位——relaxed mock 对非空泛型
        // 返回 null 导致 NPE，显式 stub 为"当前无存档"
        coEvery { storageFacade.load(any()) } returns
            SaveResult.failure(SaveError.SLOT_EMPTY, "no current save")
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.IDLE)
        // T12（2026-08-05）：init 会收集 stuckResetEvents——stub 为真实 SharedFlow
        // （collect 是扩展函数，relaxed mock 的 SharedFlow 会抛 KotlinNothingValueException）
        every { gameEngineCore.stuckResetEvents } returns MutableSharedFlow()
        // 玉符防回退（2026-08-10）：performLoadToSlot/applyCloudSaveToEngine 新增
        // stopGameLoopAndWait——relaxed mock 默认返回 false 会中止读档流程，
        // 现有用例全部需要默认成功；各用例自己的 coEvery stub 后注册覆盖此处
        coEvery { gameEngineCore.stopGameLoopAndWait(any()) } returns true

        viewModel = SaveLoadViewModel(
            gameEngine = gameEngine,
            gameEngineCore = gameEngineCore,
            stateStore = stateStore,
            coroutineScopeProvider = coroutineScopeProvider,
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
    // T14（2026-08-05）：saveGame 协程注册 activeLoadJob
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame registers active load job on game engine core`() = runTest(testDispatcher) {
        // saveGame 有 isGameLoaded 守卫（runState 须 PLAYING）与 isSaving 守卫
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.isLoading } returns MutableStateFlow(false)

        viewModel.saveGame("1")
        advanceUntilIdle()

        // T14：保存协程必须注册（看门狗可取消复位），与 loadGame/startNewGame/restartGame 同模式
        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // T16（2026-08-05）：restartGame 缺 isGameLoaded 守卫
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `restartGame when game not loaded is ignored`() = runTest(testDispatcher) {
        // 默认 runState=IDLE → isGameLoaded=false → T16 守卫立即返回
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
    // 用例 1：云档迁移管线（v0→2）+ save 失败中止
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - old v0 cloud save migrated to v2 before local write`() = runTest(testDispatcher) {
        // 老版本上传的 v0 云档（修炼值未缩放）
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 0))
            )
        // save 失败注入：同时验证"迁移发生在 save 前"与"失败后不再继续读档"
        //（MockK 标准模式：stub 用 any()，捕获用 verify 的 capture）
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "injected failure")

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 迁移管线已在 save 前应用：saveVersion 0 → 2
        val saveSlot = slot<SaveData>()
        coVerify { storageFacade.save(any(), capture(saveSlot)) }
        assertEquals("云档迁移后 saveVersion 应为 2", 2, saveSlot.captured.gameData.saveVersion)
        // save 失败 → 中止：不再刷新存档元数据、不进入读档流程
        //（getSaveSlotsSuspend 与 loadGameFromSlot 均在 save 成功之后才执行）
        coVerify(exactly = 1) { storageFacade.getSaveSlotsSuspend() }
    }

    // ──────────────────────────────────────────────────────────────────
    // 用例 2：云档完整性校验修复（损坏可修复数据修复后写入）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - corrupted but repairable cloud save is repaired before write`() = runTest(testDispatcher) {
        // 负灵石（经济系统异常数据）→ SaveValidator 的 SpiritStoneNonNegativeRule 修复为 0
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2, spiritStones = -100L))
            )
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "injected failure")

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        val saveSlot = slot<SaveData>()
        coVerify { storageFacade.save(any(), capture(saveSlot)) }
        assertEquals("负灵石应在写入前修复为 0", 0L, saveSlot.captured.gameData.spiritStones)
        coVerify(exactly = 1) { storageFacade.getSaveSlotsSuspend() }
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
    // 用例 4：正常路径（无加载进行中）允许下载
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudDownload - proceeds when no load in progress`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        // 游戏内下载会持久化到当前槽位（本次修复），save 失败注入避免深链
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "injected failure")

        viewModel.downloadFromCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 1) { tapCloudSaveManager.downloadSave() }
        val state = viewModel.cloudSaveOperationState.value
        assertTrue(
            "save 失败应返回 Error 状态，实际: $state",
            state is CloudSaveOperationState.Error
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // C1（2026-08-05）：主菜单云读档自阻塞——loadGameFromSlot 透传 fromCloudLoad
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `performCloudLoad - success path proceeds to loadGame`() = runTest(testDispatcher) {
        // C1 修复前：handleCloudLoadSuccess 调 loadGameFromSlot → loadGame 的
        // cloudDownloadLock 守卫拒绝（performCloudLoad 全程持锁）→ 云档已落盘但
        // 内存加载永不执行（主菜单云读档必失败）。修复后 fromCloudLoad=true 绕过。
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2, currentSlot = 1))
            )
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.Success(Unit)
        coEvery { storageFacade.getSaveSlotsSuspend() } returns emptyList()
        // A6：目标槽位 = getCurrentSlot()（relaxed mock 返回 0 会走向非法槽位）
        every { storageFacade.getCurrentSlot() } returns 1
        coEvery { storageFacade.hasSaveSuspend(1) } returns false
        // setSaveLoadState(isLoading=true) 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 走到读档流程：loadGame 的 launch 已注册 activeLoadJob
        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // A6（2026-08-05）：云读档目标槽位 = 当前槽位（忽略云档 currentSlot 元数据）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `cloud load ignores cloud currentSlot metadata and uses current slot`() = runTest(testDispatcher) {
        // 用户实报场景：云档 currentSlot=2（上传时在槽位 2），当前槽位=1——
        // 修复前云档被写入槽位 2 静默覆盖本地存档 b
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "云宗", saveVersion = 2, currentSlot = 2))
            )
        every { storageFacade.getCurrentSlot() } returns 1
        coEvery { storageFacade.hasSaveSuspend(1) } returns false
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.Success(Unit)
        coEvery { storageFacade.getSaveSlotsSuspend() } returns emptyList()
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 目标槽位 = 1（当前槽位），云档 currentSlot=2 仅作来源元数据被忽略
        coVerify(exactly = 1) { storageFacade.setCurrentSlot(1) }
        coVerify(exactly = 1) { storageFacade.save(1, any()) }
        coVerify(exactly = 0) { storageFacade.save(2, any()) }
    }

    @Test
    fun `cloud load saves data with slotId corrected to target slot`() = runTest(testDispatcher) {
        // 对抗性审查修复（2026-08-06）：主菜单云读档落盘前必须修正 slotId——
        // 云档 slotId 为 @Transient 恒 0，直接 save 会把 slotId=0 写入缓存，
        // 随后 loadGameFromSlot 缓存命中读回时 setActiveSlot(0) 仓库脏写错槽
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "云宗", saveVersion = 2))
            )
        every { storageFacade.getCurrentSlot() } returns 1
        coEvery { storageFacade.hasSaveSuspend(1) } returns false
        val savedData = slot<SaveData>()
        coEvery { storageFacade.save(any(), capture(savedData)) } returns
            SaveResult.Success(Unit)
        coEvery { storageFacade.getSaveSlotsSuspend() } returns emptyList()
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        coVerify(exactly = 1) { storageFacade.save(1, any()) }
        assertEquals("落盘数据 slotId 修正为目标槽位", 1, savedData.captured.gameData.slotId)
        assertEquals("落盘数据 currentSlot 修正为目标槽位", 1, savedData.captured.gameData.currentSlot)
    }

    @Test
    fun `cloud load waits for player confirmation when target slot has local save`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "云宗", saveVersion = 2, currentSlot = 2))
            )
        every { storageFacade.getCurrentSlot() } returns 1
        // 目标槽位 1 已有本地存档 → 挂起等待确认
        coEvery { storageFacade.hasSaveSuspend(1) } returns true
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.Success(Unit)
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        // 确认请求已发出，未确认前不落盘
        assertTrue("覆盖确认请求应已发出", viewModel.cloudOverwriteRequest.value != null)
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }

        // 玩家确认 → 继续落盘
        viewModel.confirmCloudOverwrite()
        advanceUntilIdle()
        coVerify(exactly = 1) { storageFacade.save(1, any()) }
    }

    @Test
    fun `cloud load aborts when player rejects overwrite`() = runTest(testDispatcher) {
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "云宗", saveVersion = 2))
            )
        every { storageFacade.getCurrentSlot() } returns 1
        coEvery { storageFacade.hasSaveSuspend(1) } returns true
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadFromCloudSave()
        advanceUntilIdle()

        viewModel.cancelCloudOverwrite()
        advanceUntilIdle()

        // 拒绝后不落盘、不读档（本地存档原样保留）
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        coVerify(exactly = 0) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    // ──────────────────────────────────────────────────────────────────
    // B8（2026-08-05）：云下载内存加载 slotId/currentSlot 同时修正
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `reconcileCloudSlot fixes both slotId and currentSlot to target slot`() = runTest(testDispatcher) {
        val reconciled = cloudSaveData(GameData(sectName = "云宗", saveVersion = 2, currentSlot = 2))
        val resolved = viewModel.reconcileCloudSlot(reconciled, 3)
        // 云档 slotId 为 @Transient 恒 0——必须修正为 3，否则 loadFromSnapshot
        // 内 setActiveSlot(gameData.slotId) 拿到 0 导致 repository 脏写错槽
        assertEquals("slotId 修正为目标槽位", 3, resolved.slotId)
        assertEquals("currentSlot 修正为目标槽位", 3, resolved.currentSlot)
    }

    // ──────────────────────────────────────────────────────────────────
    // C2（2026-08-05）：loadGameFromSlot(0) 自链下载自阻塞
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `loadGameFromSlot(0) - self-chain download proceeds`() = runTest(testDispatcher) {
        // C2 修复前：先 setSaveLoadState(isLoading=true) 再调 downloadFromCloudSave，
        // 被其自身 isLoading 守卫（L1339）恒真拒绝——SettingsTab 云槽位读取必失败
        coEvery { tapCloudSaveManager.downloadSave() } returns
            TapCloudSaveManager.CloudSaveResult.Success(
                cloudSaveData(GameData(sectName = "青云宗", saveVersion = 2))
            )
        // save 失败注入避免深链（下载本身是否执行才是断言目标）
        coEvery { storageFacade.save(any(), any()) } returns
            SaveResult.failure(SaveError.SAVE_FAILED, "injected failure")
        // setSaveLoadState(isLoading=true) 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        viewModel.loadGameFromSlot(0)
        advanceUntilIdle()

        // 下载必须实际执行（修复前 0 次）
        coVerify(exactly = 1) { tapCloudSaveManager.downloadSave() }
    }

    // ──────────────────────────────────────────────────────────────────
    // C4（2026-08-05）：restart 窗口内 saveGame/loadGame 被 _isRestarting 守卫拒绝
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame rejected while restart in progress`() = runTest(testDispatcher) {
        // C4 配套守卫：restart 的 stopGameLoopAndWait 窗口内（saveLock 已持有、
        // isSaving 未置）点保存——修复前 saveGame 通过守卫注册并取消 restart 协程
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
    // C5（2026-08-05）：saveGame 双 tap 窗口——isSaving 同步占位
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `saveGame second tap rejected while first save in flight`() = runTest(testDispatcher) {
        // C5 修复前：isSaving 由协程内异步设置，两次快速 tap 在协程启动前均可通过守卫
        // → job2 注册取消 job1（磁盘已写但 currentSlot 回滚不一致）。修复后入口同步置位。
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
    // T2（2026-08-05）：restart 与 load 完整互斥（loadLock + 同步置位 _isRestarting）
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `restartGame rejected while loadLock held by load`() = runTest(testDispatcher) {
        // T2 修复前：loadGame 已抢 loadLock（performLoadToSlot 挂起中），
        // restartGame 不查 loadLock 可穿入，与 load 的 clear+insert 并发重置引擎。
        // 修复后 restart 入口 loadLock CAS 失败 → 拒绝并释放 saveLock。
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
        // T2 修复前：_isRestarting 在协程体 performRestartGame 内才置位，
        // restart 抢到 saveLock 后、协程启动前的窗口内 saveGame 可穿入。
        // 修复后入口同步置位（与 C5 setSavingDirect 同模式）→ 紧接的 saveGame 被拒。
        every { stateStore.isLoading } returns MutableStateFlow(false)
        every { stateStore.isSaving } returns MutableStateFlow(false)
        every { stateStore.runState } returns MutableStateFlow(RunState.PLAYING)

        viewModel.restartGame()  // 同步路径：saveLock+loadLock 抢到、_isRestarting=true、job 已注册
        viewModel.saveGame("1")  // 不 advance——同步窗口内调用，修复前会通过守卫
        advanceUntilIdle()

        // 仅 restart 注册 1 次；saveGame 被同步置位的 _isRestarting 拒绝
        coVerify(exactly = 1) { gameEngineCore.registerActiveLoadJob(any()) }
    }

    @Test
    fun `restartGame rejected when isLoading and recovers after`() = runTest(testDispatcher) {
        // T2：isLoading 中 restart 拒绝且三锁（_isRestarting/loadLock/saveLock）全部复位，
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
    // Bugly #11021/#14002：lateinit job 竞态——Unconfined 下协程体先于赋值执行
    // ──────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadGame - 协程体先于 lateinit job 赋值同步执行不崩溃`() = runTest(testDispatcher) {
        // Bugly #11021/#14002 回归：Unconfined 下 launch 协程体在 launch() 返回前
        // 同步执行（模拟空闲 IO worker 抢跑）——旧代码实参求值读 lateinit job 抛
        // UninitializedPropertyAccessException；修复后协程体不再捕获 job。
        val unconfinedVm = SaveLoadViewModel(
            gameEngine = gameEngine,
            gameEngineCore = gameEngineCore,
            stateStore = stateStore,
            coroutineScopeProvider = coroutineScopeProvider,
            gameClock = gameClock,
            resourcePreloader = resourcePreloader,
            persistenceFacade = persistenceFacade,
            ioDispatcher = IoDispatcher(Dispatchers.Unconfined)
        )
        // setSaveLoadState 评估 isSaving.value——relaxed mock 返回 Object 必崩
        every { stateStore.isSaving } returns MutableStateFlow(false)

        // 旧代码此处同步抛 UninitializedPropertyAccessException → 测试失败即回归复现
        unconfinedVm.loadGame(com.xianxia.sect.data.model.SaveSlot(1, "", 0L, 1, 1, "", 0, 0L))
        runCurrent()

        coVerify { gameEngineCore.registerActiveLoadJob(any()) }
        coVerify { storageFacade.load(1) }
    }

    // ──────────────────────────────────────────────────────────────────
    // 玉符防回退（2026-08-10）：读档/云下载前等待旧循环停止（stopGameLoopAndWait）
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
}
