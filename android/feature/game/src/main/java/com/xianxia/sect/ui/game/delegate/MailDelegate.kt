package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.RewardCardItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.engine.service.deleteAllReadAndClaimed



class MailDelegate(
    private val gameEngine: GameEngine,
    private val mailService: MailService,
    private val onShowError: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "MailDelegate"
    }

    private val currentSlotId: Int get() = gameEngine.gameData.value?.slotId ?: 0

    val mails: StateFlow<List<MailEntity>> get() = mailService.activeMails
    val mailUnreadCount: StateFlow<Int> get() = mailService.unreadCount

    fun markMailAsRead(mailId: String) { gameEngine.launchOnEngine { mailService.markAsRead(mailId, currentSlotId) } }

    private val _mailRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val mailRewardCards: StateFlow<List<RewardCardItem>> = _mailRewardCards.asStateFlow()
    private val mailCardQueueMutex = Mutex()

    @Suppress("TooGenericExceptionCaught") // 防御兜底：领取路径不可预期异常须记录并让玩家可重试（CancellationException 已先重抛）
    fun claimMailAttachment(mailId: String, onResult: (ClaimResult) -> Unit = {}) {
        gameEngine.launchOnEngine {
            // 诊断增强（问题3）：区分"引擎协程抛异常被吞→玩家无反应"与"正常失败→弹窗"。
            // 正常情况下 claimAttachment 不抛异常（业务失败走 ClaimResult 三态），
            // 一旦抛出说明存在未预期路径（如领取并发/状态损坏），须记录并让玩家可重试。
            val result = try {
                mailService.claimAttachment(mailId, currentSlotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "claimAttachment failed: mailId=$mailId", e)
                ClaimResult.DistributeFailed("领取失败，请重试")
            }
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
                    gameEngine.enqueueRewardCards(cards)
                    _mailRewardCards.value = emptyList()
                }
            }
        }
    }

    fun deleteAllReadAndClaimedMails() { gameEngine.launchOnEngine { mailService
        .deleteAllReadAndClaimed(currentSlotId) } }
}
