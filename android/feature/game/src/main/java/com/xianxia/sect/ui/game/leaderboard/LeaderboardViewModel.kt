package com.xianxia.sect.ui.game.leaderboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.taptap.LeaderboardEntry
import com.xianxia.sect.taptap.LeaderboardManager
import com.xianxia.sect.taptap.LeaderboardResult
import com.xianxia.sect.taptap.LocalLeaderboardEntry
import com.xianxia.sect.taptap.TapTapLoginBridge
import com.xianxia.sect.ui.game.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 排行榜 ViewModel：双标签（天下宗门本地榜 / 玩家排行云端榜）状态机。
 *
 * 本地榜：三流 combine 派生，即时可得，无网络态。
 * 云端榜：Idle → NeedLogin（未登录）或 Loading → Success/Empty/Error；重试与登录回调再入。
 */
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val leaderboardManager: LeaderboardManager,
    private val loginBridge: TapTapLoginBridge
) : BaseViewModel() {

    enum class LeaderboardTab { LOCAL, CLOUD }

    /** 云端榜状态（UI 分派渲染） */
    sealed interface CloudLeaderboardState {
        data object Idle : CloudLeaderboardState
        data object Loading : CloudLeaderboardState

        /** 未登录，需引导 TapTap 登录 */
        data object NeedLogin : CloudLeaderboardState
        data object Empty : CloudLeaderboardState
        data class Error(val message: String) : CloudLeaderboardState
        data class Success(
            val entries: List<LeaderboardEntry>,
            val myRanking: LeaderboardEntry?
        ) : CloudLeaderboardState
    }

    @Immutable
    data class LeaderboardUiState(
        val selectedTab: LeaderboardTab = LeaderboardTab.LOCAL,
        val cloudState: CloudLeaderboardState = CloudLeaderboardState.Idle
    )

    /** 本地榜：即时派生（玩家 + AI 宗门，战力降序） */
    val localEntries: StateFlow<List<LocalLeaderboardEntry>> = combine(
        gameEngine.sectCombatPower,
        gameEngine.aiSectCombatPowers,
        gameEngine.gameData
    ) { power, aiPowers, data ->
        LocalLeaderboardComposer.compose(
            playerPower = power,
            playerSectName = data.sectName,
            aiSectCombatPowers = aiPowers,
            worldSects = data.worldMapSects
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 世界宗门是否已加载（主菜单等无存档上下文为 false）。
     * 主菜单入口（initialTab = CLOUD）打开排行榜时，天下宗门 Tab 展示引导提示而非空榜。
     */
    val isWorldLoaded: StateFlow<Boolean> = gameEngine.gameData
        .map { it.worldMapSects.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    fun selectLocalTab() {
        _uiState.update { it.copy(selectedTab = LeaderboardTab.LOCAL) }
    }

    /** 切到玩家排行 Tab：首次进入（Idle）触发加载，后续保持已拉取数据 */
    fun selectCloudTab() {
        _uiState.update { it.copy(selectedTab = LeaderboardTab.CLOUD) }
        if (_uiState.value.cloudState is CloudLeaderboardState.Idle) {
            loadCloud()
        }
    }

    /** 重试（Error 态或登录成功后） */
    fun retryCloud() {
        _uiState.update { it.copy(cloudState = CloudLeaderboardState.Loading) }
        loadCloud()
    }

    /** 每日首次进游戏静默上报当前战力（节流判定在 LeaderboardManager 内部） */
    suspend fun reportDailyIfDue() {
        leaderboardManager.uploadIfNeeded(gameEngine.sectCombatPower.value)
    }

    /** 排行榜内拉起 TapTap 登录（未登录引导态按钮点击） */
    fun login(activity: android.app.Activity) {
        loginBridge.login(activity) { result -> onLoginResult(result) }
    }

    /** 排行榜内完成 TapTap 登录后的回调 */
    fun onLoginResult(result: TapTapLoginBridge.LoginResult) {
        when (result) {
            is TapTapLoginBridge.LoginResult.Success -> loadCloud()
            is TapTapLoginBridge.LoginResult.Canceled -> Unit // 保持 NeedLogin 引导态
            is TapTapLoginBridge.LoginResult.Error -> showError(result.message)
        }
    }

    /**
     * 拉取云端榜：未登录 → NeedLogin；已登录 → 上报当前战力（best-effort，
     * 失败不阻塞榜单展示）→ 拉取榜单与我的排名 → 分派结果。
     */
    private fun loadCloud() {
        if (!loginBridge.isLoggedIn()) {
            _uiState.update { it.copy(cloudState = CloudLeaderboardState.NeedLogin) }
            return
        }
        _uiState.update { it.copy(cloudState = CloudLeaderboardState.Loading) }
        viewModelScope.launch {
            // 打开榜单即上报最新战力（节流由 LeaderboardManager 内部判定）
            leaderboardManager.uploadIfNeeded(gameEngine.sectCombatPower.value)
            val result = runCatching { leaderboardManager.fetchLeaderboard() }.getOrElse { e ->
                com.xianxia.sect.taptap.LeaderboardResult.Error(e.message ?: "加载失败")
            }
            _uiState.update {
                it.copy(cloudState = result.toCloudState())
            }
        }
    }

    private fun LeaderboardResult.toCloudState(): CloudLeaderboardState = when (this) {
        is LeaderboardResult.Success -> CloudLeaderboardState.Success(entries, myRanking)
        is LeaderboardResult.Empty -> CloudLeaderboardState.Empty
        is LeaderboardResult.NeedLogin -> CloudLeaderboardState.NeedLogin
        is LeaderboardResult.Error -> CloudLeaderboardState.Error(message)
    }
}
