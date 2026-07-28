package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.RewardCardItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BagDelegate(
    private val gameEngine: GameEngine,
    private val dailySignInService: DailySignInService,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var pendingBagCards: List<RewardCardItem> = emptyList()

    private val _bagRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val bagRewardCards: StateFlow<List<RewardCardItem>> = _bagRewardCards.asStateFlow()

    suspend fun openStorageBag(bagId: String): List<BattleRewardItem> = withContext(dispatcher) {
        val (rewards, cards) = gameEngine.openStorageBag(bagId)
        pendingBagCards = cards
        _bagRewardCards.value = cards
        rewards
    }

    suspend fun openAllStorageBags(bagId: String): List<BattleRewardItem> = withContext(dispatcher) {
        val allRewards = mutableListOf<BattleRewardItem>()
        val allCards = mutableListOf<RewardCardItem>()
        while (true) {
            val bags = gameEngine.storageBags.value
            val bag = bags.find { it.id == bagId } ?: break
            if (bag.quantity <= 0) break
            val (rewards, cards) = gameEngine.openStorageBag(bagId)
            allRewards.addAll(rewards)
            allCards.addAll(cards)
        }
        pendingBagCards = allCards
        _bagRewardCards.value = allCards
        allRewards
    }

    fun enqueueBagRewardCards() {
        if (pendingBagCards.isNotEmpty()) {
            dailySignInService.enqueueSignInCards(pendingBagCards)
            pendingBagCards = emptyList()
            _bagRewardCards.value = emptyList()
        }
    }
}
