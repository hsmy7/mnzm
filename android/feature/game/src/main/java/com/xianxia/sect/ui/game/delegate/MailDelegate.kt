package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.RewardCardItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MailDelegate(
    private val gameEngine: GameEngine,
    private val mailService: MailService,
    private val dailySignInService: DailySignInService,
    private val onShowError: (String) -> Unit = {}
) {

    private val currentSlotId: Int get() = gameEngine.gameData.value?.slotId ?: 0

    val mails: StateFlow<List<MailEntity>> get() = mailService.activeMails
    val mailUnreadCount: StateFlow<Int> get() = mailService.unreadCount

    fun markMailAsRead(mailId: String) { gameEngine.launchOnEngine { mailService.markAsRead(mailId, currentSlotId) } }

    private val _mailRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val mailRewardCards: StateFlow<List<RewardCardItem>> = _mailRewardCards.asStateFlow()
    private val mailCardQueueMutex = Mutex()

    fun claimMailAttachment(mailId: String, onResult: (ClaimResult) -> Unit = {}) {
        gameEngine.launchOnEngine {
            val result = mailService.claimAttachment(mailId, currentSlotId)
            if (result is ClaimResult.Success && result.cards.isNotEmpty()) {
                _mailRewardCards.value = result.cards
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun markAllMailsAsRead() {
        gameEngine.launchOnEngine {
            val result = mailService.markAllAsRead(currentSlotId)
            if (result.cards.isNotEmpty()) { _mailRewardCards.value = result.cards }
            withContext(Dispatchers.Main) {
                if (result.skippedCount > 0) { onShowError(result.skipReasons.first()) }
            }
        }
    }

    fun enqueueMailRewardCards() {
        gameEngine.launchOnEngine {
            mailCardQueueMutex.withLock {
                val cards = _mailRewardCards.value
                if (cards.isNotEmpty()) {
                    dailySignInService.enqueueSignInCards(cards)
                    _mailRewardCards.value = emptyList()
                }
            }
        }
    }

    fun deleteAllReadAndClaimedMails() { gameEngine.launchOnEngine { mailService.deleteAllReadAndClaimed(currentSlotId) } }
}
