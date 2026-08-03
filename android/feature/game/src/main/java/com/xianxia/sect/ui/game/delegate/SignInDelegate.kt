package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.service.ClaimDailyResult
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SignInDelegate(
    private val gameEngine: GameEngine,
    private val dailySignInService: DailySignInService,
    private val scope: CoroutineScope,
    private val sharingStarted: SharingStarted,
    /** 容量不足提示回调（GameViewModel → showCapacityWarning 统一提示框） */
    private val onCapacityWarning: (String) -> Unit
) {

    val signInState: StateFlow<SignInState> = gameEngine.gameData
        .map { it.signInState }.distinctUntilChanged()
        .stateIn(scope, sharingStarted, SignInState())

    val canClaimToday: StateFlow<Boolean> = signInState
        .map { state ->
            val calendar = java.util.Calendar.getInstance()
            val today = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            today !in state.claimedDays
        }.distinctUntilChanged().stateIn(scope, sharingStarted, true)

    val heavenlyTrialClaimable: StateFlow<Boolean> = gameEngine.gameData
        .map { data -> (0 until 8).any { data.heavenlyTrialState.canClaimReward(it) } }
        .distinctUntilChanged().stateIn(scope, sharingStarted, false)

    val anyActivityClaimable: StateFlow<Boolean> =
        combine(canClaimToday, heavenlyTrialClaimable) { signIn, trial -> signIn || trial }
            .distinctUntilChanged().stateIn(scope, sharingStarted, false)

    val claimedDaysCount: StateFlow<Int> = signInState
        .map { it.claimedDays.size }.distinctUntilChanged()
        .stateIn(scope, sharingStarted, 0)

    val claimedMilestones: StateFlow<List<Int>> = signInState
        .map { it.claimedMilestones }.distinctUntilChanged()
        .stateIn(scope, sharingStarted, emptyList())

    val milestoneRewards: List<MilestoneReward> = dailySignInService.getMilestoneRewards()

    fun getRewardForWeekday(weekday: Int): DailySignInReward = dailySignInService.getRewardForWeekday(weekday)
    fun getDayState(dayOfMonth: Int, signInState: SignInState): SignInDayState = dailySignInService.getDayState(dayOfMonth, signInState)
    fun getDaysInMonth(): Int = dailySignInService.getDaysInMonth()
    fun getWeekdayForDay(dayOfMonth: Int): Int = dailySignInService.getWeekdayForDay(dayOfMonth)

    fun claimDailySignIn() {
        gameEngine.launchOnEngine {
            val result = dailySignInService.claimDailySignIn()
            when (result) {
                is ClaimDailyResult.Success -> dailySignInService.enqueueSignInCards(result.cards)
                is ClaimDailyResult.SuccessWithMilestones -> dailySignInService.enqueueSignInCards(result.cards)
                is ClaimDailyResult.AlreadyClaimed -> { }
                // 统一容量提示框（GameOverlayHost 渲染"仓库容量不足/知道了"）
                is ClaimDailyResult.CapacityInsufficient -> withContext(Dispatchers.Main) {
                    onCapacityWarning(result.message)
                }
            }
        }
    }

    fun enqueueRewardCards(cards: List<RewardCardItem>) { dailySignInService.enqueueSignInCards(cards) }
}
