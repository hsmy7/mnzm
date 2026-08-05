package com.xianxia.sect.ui.game.leaderboard

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.taptap.LeaderboardEntry
import com.xianxia.sect.taptap.LeaderboardManager
import com.xianxia.sect.taptap.LeaderboardResult
import com.xianxia.sect.taptap.TapTapLoginBridge
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
 * 排行榜 ViewModel 测试：本地榜派生、云端榜状态机、登录回调分派与 Tab 切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val gameEngine: GameEngine = mockk(relaxed = true)
    private val leaderboardManager: LeaderboardManager = mockk(relaxed = true)
    private val loginBridge: TapTapLoginBridge = mockk(relaxed = true)

    private val sectCombatPower = MutableStateFlow(500L)
    private val aiSectCombatPowers = MutableStateFlow<Map<String, Long>>(mapOf("a" to 900L))
    private val gameData = MutableStateFlow(
        GameData(
            sectName = "青云宗",
            worldMapSects = listOf(WorldSect(id = "a", name = "太玄门", isPlayerSect = false))
        )
    )

    private lateinit var viewModel: LeaderboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { gameEngine.sectCombatPower } returns sectCombatPower
        every { gameEngine.aiSectCombatPowers } returns aiSectCombatPowers
        every { gameEngine.gameData } returns gameData
        viewModel = LeaderboardViewModel(gameEngine, leaderboardManager, loginBridge)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 本地榜派生 ──
    // 注意：stateIn 订阅时立即发射 initialValue（emptyList），first() 会直接拿到
    // 初始值而返回；drop(1) 跳过初始值，等待 combine 的真实发射（runTest 自动推进调度）。

    @Test
    fun `localEntries - 三流 combine 派生且战力降序`() = runTest {
        val vm = LeaderboardViewModel(gameEngine, leaderboardManager, loginBridge)
        val entries = vm.localEntries.drop(1).first()

        assertEquals(listOf("太玄门", "青云宗"), entries.map { it.name })
        assertEquals(listOf(900L, 500L), entries.map { it.power })
        assertTrue(entries.first { it.isPlayer }.name == "青云宗")
    }

    @Test
    fun `localEntries - 战力流更新后派生结果刷新`() = runTest {
        val vm = LeaderboardViewModel(gameEngine, leaderboardManager, loginBridge)
        sectCombatPower.value = 1000L
        val entries = vm.localEntries.drop(1).first()

        assertEquals(listOf("青云宗", "太玄门"), entries.map { it.name })
    }

    // ── Tab 切换 ──

    @Test
    fun `selectLocalTab - 切回本地榜`() {
        viewModel.selectLocalTab()
        assertEquals(
            LeaderboardViewModel.LeaderboardTab.LOCAL,
            viewModel.uiState.value.selectedTab
        )
    }

    @Test
    fun `selectCloudTab - 未登录进入 NeedLogin 引导态`() = runTest {
        every { loginBridge.isLoggedIn() } returns false

        viewModel.selectCloudTab()
        advanceUntilIdle()

        assertEquals(
            LeaderboardViewModel.CloudLeaderboardState.NeedLogin,
            viewModel.uiState.value.cloudState
        )
    }

    // ── 云端榜加载与结果分派 ──

    @Test
    fun `selectCloudTab - 已登录加载成功展示榜单与我的排名`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns true
        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Success(
            entries = listOf(LeaderboardEntry(1, "张三", power = 5000)),
            myRanking = LeaderboardEntry(2, "青云宗", power = 4000, isMe = true)
        )

        viewModel.selectCloudTab()
        advanceUntilIdle()

        val state = viewModel.uiState.value.cloudState
        assertTrue(state is LeaderboardViewModel.CloudLeaderboardState.Success)
        state as LeaderboardViewModel.CloudLeaderboardState.Success
        assertEquals(1, state.entries.size)
        assertEquals(2, state.myRanking?.rank)
    }

    @Test
    fun `selectCloudTab - 打开时上报当前战力`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns true
        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Empty

        viewModel.selectCloudTab()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { leaderboardManager.uploadIfNeeded(500L) }
    }

    @Test
    fun `selectCloudTab - 空榜进入 Empty 态`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns false
        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Empty

        viewModel.selectCloudTab()
        advanceUntilIdle()

        assertEquals(
            LeaderboardViewModel.CloudLeaderboardState.Empty,
            viewModel.uiState.value.cloudState
        )
    }

    @Test
    fun `selectCloudTab - 拉取异常进入 Error 态`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns false
        coEvery { leaderboardManager.fetchLeaderboard() } throws RuntimeException("boom")

        viewModel.selectCloudTab()
        advanceUntilIdle()

        val state = viewModel.uiState.value.cloudState
        assertTrue(state is LeaderboardViewModel.CloudLeaderboardState.Error)
    }

    @Test
    fun `selectCloudTab - 二次进入不重复拉取（保留已加载数据）`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns true
        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Success(
            entries = listOf(LeaderboardEntry(1, "张三", power = 5000)),
            myRanking = null
        )

        viewModel.selectCloudTab()
        advanceUntilIdle()
        viewModel.selectLocalTab()
        viewModel.selectCloudTab()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { leaderboardManager.fetchLeaderboard() }
    }

    @Test
    fun `retryCloud - 错误态重试重新加载`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns true
        coEvery { leaderboardManager.fetchLeaderboard() } throws RuntimeException("boom")

        viewModel.selectCloudTab()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cloudState is LeaderboardViewModel.CloudLeaderboardState.Error)

        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Empty
        viewModel.retryCloud()
        advanceUntilIdle()

        assertEquals(
            LeaderboardViewModel.CloudLeaderboardState.Empty,
            viewModel.uiState.value.cloudState
        )
        io.mockk.coVerify(exactly = 2) { leaderboardManager.fetchLeaderboard() }
    }

    // ── 登录回调分派 ──

    @Test
    fun `onLoginResult - 登录成功自动拉取榜单`() = runTest {
        every { loginBridge.isLoggedIn() } returns true
        coEvery { leaderboardManager.uploadIfNeeded(any()) } returns true
        coEvery { leaderboardManager.fetchLeaderboard() } returns LeaderboardResult.Success(
            entries = listOf(LeaderboardEntry(1, "张三", power = 5000)),
            myRanking = null
        )

        viewModel.onLoginResult(TapTapLoginBridge.LoginResult.Success)
        advanceUntilIdle()

        val state = viewModel.uiState.value.cloudState
        assertTrue(state is LeaderboardViewModel.CloudLeaderboardState.Success)
    }

    @Test
    fun `onLoginResult - 取消登录保持引导态`() = runTest {
        every { loginBridge.isLoggedIn() } returns false
        viewModel.selectCloudTab()
        advanceUntilIdle()
        assertEquals(
            LeaderboardViewModel.CloudLeaderboardState.NeedLogin,
            viewModel.uiState.value.cloudState
        )

        viewModel.onLoginResult(TapTapLoginBridge.LoginResult.Canceled)

        assertEquals(
            LeaderboardViewModel.CloudLeaderboardState.NeedLogin,
            viewModel.uiState.value.cloudState
        )
    }
}
