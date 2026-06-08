package com.xianxia.sect.core.state

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val GameStateStore.pendingNotification: StateFlow<GameNotification?>
    get() = _pendingNotificationFlow.asStateFlow()

val GameStateStore.pendingBattleResult: StateFlow<BattleResultUIData?>
    get() = _pendingBattleResultFlow.asStateFlow()

fun GameStateStore.setPendingNotification(notification: GameNotification) {
    _pendingNotificationFlow.value = notification
    _updateVersion.value++
}

fun GameStateStore.clearPendingNotification() {
    _pendingNotificationFlow.value = null
    _updateVersion.value++
}

fun GameStateStore.setPendingBattleResult(result: BattleResultUIData) {
    _pendingBattleResultFlow.value = result
    _updateVersion.value++
}

fun GameStateStore.clearPendingBattleResult() {
    _pendingBattleResultFlow.value = null
    _updateVersion.value++
}
