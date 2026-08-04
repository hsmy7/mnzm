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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
            teams = emptyList()
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
}
