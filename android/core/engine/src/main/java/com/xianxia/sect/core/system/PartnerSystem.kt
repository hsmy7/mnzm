package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.event.BreakthroughEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRandom
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "PartnerSystem"
@Singleton
@SystemPriority(order = 240)
class PartnerSystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val eventBus: EventBusPort
) : GameSystem, DomainEventSubscriber {

    override val systemName: String = "PartnerSystem"
    override val focusDomain = FocusDomain.BACKGROUND
    private val scope get() = scopeProvider.scope

    override val subscribedTypes: Set<String> = setOf("breakthrough")

    override fun onEvent(event: DomainEvent) {
        if (event !is BreakthroughEvent || !event.success) return
        scope.launch {
            stateStore.update {
                val partnerId = disciples.find { it.id == event.discipleId }?.social?.partnerId
                    ?: return@update
                disciples = disciples.map { disciple ->
                    if (disciple.id == partnerId && disciple.isAlive) {
                        disciple.copyWith(loyalty = (disciple.skills.loyalty + 3).coerceAtMost(100))
                    } else disciple
                }
            }
        }
    }

    override fun initialize() {
        eventBus.subscribe(this)
    }

    override fun release() {
        eventBus.unsubscribe(this)
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onMonthTick(state: MutableGameState) {
        processPartnerMatching(state)
    }

    private fun processPartnerMatching(state: MutableGameState) {
        val allDisciples = state.disciples
        val bannedRootCounts = state.gameData.daoCompanionBannedRootCounts
        val consentRequired = state.gameData.daoCompanionConsentRequired

        val eligibleMales = allDisciples.filter {
            it.isAlive && it.age >= 18 && it.social.partnerId == null && it.gender == "male" &&
                !bannedRootCounts.contains(it.spiritRootType.split(",").size)
        }
        val eligibleFemales = allDisciples.filter {
            it.isAlive && it.age >= 18 && it.social.partnerId == null && it.gender == "female" &&
                !bannedRootCounts.contains(it.spiritRootType.split(",").size)
        }

        if (eligibleMales.isEmpty() || eligibleFemales.isEmpty()) return

        var currentList = allDisciples
        val pairedFemaleIds = mutableSetOf<String>()

        for (male in eligibleMales) {
            for (female in eligibleFemales) {
                if (female.id in pairedFemaleIds) continue
                if (hasBloodRelation(male, female)) continue

                if (GameRandom.nextDouble() < 0.006) {
                    if (consentRequired) {
                        state.pendingNotification = GameNotification.MarriageRequest(male, female)
                        return
                    }
                    currentList = currentList.map { disciple ->
                        when (disciple.id) {
                            male.id -> disciple.copyWith(partnerId = female.id)
                            female.id -> disciple.copyWith(partnerId = male.id)
                            else -> disciple
                        }
                    }
                    pairedFemaleIds.add(female.id)
                }
            }
        }

        if (pairedFemaleIds.isNotEmpty()) {
            state.disciples = currentList
        }
    }

    private fun hasBloodRelation(a: Disciple, b: Disciple): Boolean {
        val aParent1 = a.social.parentId1
        val aParent2 = a.social.parentId2
        val bParent1 = b.social.parentId1
        val bParent2 = b.social.parentId2
        return a.id == bParent1 || a.id == bParent2 ||
            b.id == aParent1 || b.id == aParent2 ||
            (aParent1 != null && aParent1 == bParent1) ||
            (aParent1 != null && aParent1 == bParent2) ||
            (aParent2 != null && aParent2 == bParent1) ||
            (aParent2 != null && aParent2 == bParent2)
    }
}
