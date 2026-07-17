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
import com.xianxia.sect.core.GameConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 240)
/**
 * 伴侣系统（道侣配对）。
 *
 * 收到突破事件后增加道侣忠诚度（通过 [stateStore.update]）。
 * EventBus.notifySubscribers 已在协程内调用 onEvent，
 * 且 stateStore.update 使用 ReentrantLock 非挂起，
 * 可直接在 onEvent 内调用，无需 scope.launch 包装。
 */
class PartnerSystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort
) : GameSystem, DomainEventSubscriber {

    override val systemName: String = "PartnerSystem"

    override val subscribedTypes: Set<String> = setOf("breakthrough")

    companion object {
        private const val PAIRING_PROBABILITY = 0.006
        private const val BREAKTHROUGH_LOYALTY_GAIN = 3
    }

    override fun onEvent(event: DomainEvent) {
        if (event !is BreakthroughEvent || !event.success) return
        // EventBus.notifySubscribers 已在 scope.launch 内调用 onEvent，
        // 此处无需再套 scope.launch（stateStore.update 使用 ReentrantLock 非挂起）
        stateStore.update {
            val partnerId = event.discipleId.toIntOrNull()
                ?.let { id -> discipleTables.partnerIds[id] }
                ?: return@update
            for (id in discipleTables.ids) {
                if (id.toString() == partnerId && discipleTables.isAlive[id] == 1) {
                    discipleTables.loyalties[id] = (discipleTables.loyalties[id] + BREAKTHROUGH_LOYALTY_GAIN).coerceAtMost(GameConfig.Disciple.MAX_LOYALTY)
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

    override fun clearForSlot(slotId: Int) {}

    override fun onMonthlyEvent(state: MutableGameState) {
        // 月度伴侣配对
        processPartnerMatching(state)
    }

    // ═══════════════════════════════════════════════════════════════
    // 伴侣配对
    // ═══════════════════════════════════════════════════════════════

    internal fun processPartnerMatching(state: MutableGameState) {
        val allDisciples = state.discipleTables.assembleAll()
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

        val pairedFemaleIds = mutableSetOf<String>()

        for (male in eligibleMales) {
            for (female in eligibleFemales) {
                if (female.id in pairedFemaleIds) continue
                if (hasBloodRelation(male, female)) continue

                if (GameRandom.nextDouble() < PAIRING_PROBABILITY) {
                    if (consentRequired) {
                        state.pendingNotification = GameNotification.MarriageRequest(male, female)
                        return
                    }
                    // ★ 直接列写入 partnerIds，避免 assembleAll → map → replaceAll
                    // 全表替换走 writeGuard 可能触发竞态崩溃 #3057
                    val maleId = male.id.toIntOrNull() ?: continue
                    val femaleId = female.id.toIntOrNull() ?: continue
                    state.discipleTables.partnerIds[maleId] = female.id
                    state.discipleTables.partnerIds[femaleId] = male.id
                    pairedFemaleIds.add(female.id)
                }
            }
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
