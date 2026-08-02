package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.autoAssignSecretRealmTeam
import com.xianxia.sect.core.engine.chooseSecretRealmOption
import com.xianxia.sect.core.engine.continueSecretRealmExploration
import com.xianxia.sect.core.engine.endSecretRealmExploration
import com.xianxia.sect.core.engine.pauseForSecretRealm
import com.xianxia.sect.core.engine.resumeFromSecretRealm
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult
import com.xianxia.sect.core.engine.startSecretRealmExploration
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.util.DomainResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 远古秘境 ViewModel——详情界面（选人/一键任命/出发/继续）与探索界面共用。
 */
@HiltViewModel
class SecretRealmViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    /** 当前地图上的秘境（不存在为 null） */
    val realmState: StateFlow<SecretRealmState?> = gameEngine.gameData
        .map { it.secretRealmState.takeIf { s -> s.exists } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 当前探索会话（无会话为 null） */
    val session: StateFlow<SecretRealmExplorationSession?> = gameEngine.gameData
        .map { it.secretRealmSession.takeIf { s -> s.isActive } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gameData: StateFlow<GameData> = gameEngine.gameData

    val disciples: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates

    // ── 详情界面操作 ───────────────────────────────────────────────────

    /** 一键任命：引擎按境界优先选出 4 人，返回 ID 列表供 UI 填槽 */
    fun autoAssignTeam(onDone: (List<String>) -> Unit) {
        gameEngine.launchOnEngine {
            val ids = gameEngine.autoAssignSecretRealmTeam()
            onDone(ids)
        }
    }

    /** 出发探索（满 4 人校验通过后回调） */
    fun startExploration(memberIds: List<String>, onDone: (Boolean) -> Unit) {
        gameEngine.launchOnEngine {
            val result = gameEngine.startSecretRealmExploration(memberIds)
            onDone(result is DomainResult.Success)
        }
    }

    /** 继续探索（读档后恢复会话） */
    fun continueExploration(onDone: (Boolean) -> Unit) {
        gameEngine.launchOnEngine {
            val ok = gameEngine.continueSecretRealmExploration()
            onDone(ok)
        }
    }

    /** 主动结束探索（结算背包 + 秘境消失） */
    fun endExploration(onDone: () -> Unit) {
        gameEngine.launchOnEngine {
            gameEngine.endSecretRealmExploration()
            onDone()
        }
    }

    // ── 探索界面操作 ───────────────────────────────────────────────────

    /** 进入探索界面：暂停游戏时间（退出时自动恢复） */
    fun enterExploration() {
        gameEngine.launchOnEngine { gameEngine.pauseForSecretRealm() }
    }

    /** 退出探索界面（暂存退出/结束）：恢复游戏时间 */
    fun exitExploration() {
        gameEngine.launchOnEngine { gameEngine.resumeFromSecretRealm() }
    }

    /** 选择事件选项（返回战斗播放数据等） */
    fun chooseOption(
        optionIndex: Int,
        onDone: (SecretRealmChoiceResult) -> Unit
    ) {
        gameEngine.launchOnEngine {
            val result = try {
                gameEngine.chooseSecretRealmOption(optionIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常转 Error 结果回调：UI 的 choosing 请求锁得以释放，避免选项被永久静默禁用
                SecretRealmChoiceResult.Error(message = "选择失败，请重试")
            }
            onDone(result)
        }
    }
}
