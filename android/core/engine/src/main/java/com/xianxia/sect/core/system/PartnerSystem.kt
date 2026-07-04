package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.concurrent.NoOpResult
import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ParallelPhaseResult
import com.xianxia.sect.core.concurrent.PartnerMatchResult
import com.xianxia.sect.core.event.BreakthroughEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRandom
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
    private val scope get() = scopeProvider.scope

    override val subscribedTypes: Set<String> = setOf("breakthrough")

    /**
     * 标志位：本 tick 的配对是否已由并行 compute 完成。
     * [computePhaseTick] 中设为 false，[apply] 后设为 true，
     * [onPhaseTick] 检查此标志跳过重复结算。
     */
    @Volatile
    private var parallelSettled = false

    override fun onEvent(event: DomainEvent) {
        if (event !is BreakthroughEvent || !event.success) return
        scope.launch {
            stateStore.update {
                val partnerId = event.discipleId.toIntOrNull()
                    ?.let { id -> discipleTables.partnerIds[id] }
                    ?: return@update
                for (id in discipleTables.ids) {
                    if (id.toString() == partnerId && discipleTables.isAlive[id] == 1) {
                        discipleTables.loyalties[id] = (discipleTables.loyalties[id] + 3).coerceAtMost(100)
                    }
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

    // ═══════════════════════════════════════════════════════════════
    // 并行 compute/apply 模式
    // ═══════════════════════════════════════════════════════════════

    override val supportsParallelTick: Boolean get() = true

    /**
     * 并行 compute 阶段 — 在 [Dispatchers.Default] 上执行。
     *
     * 从 [ParallelExecutionContext] 的只读快照中读取弟子数据，
     * 使用列直读（maps）替代 [assembleAll] 全量对象组装，
     * 计算所有配对与忠诚度变更后返回 [PartnerMatchResult]。
     *
     * 随机数使用 [GameRandom]（ThreadLocal 安全，无需额外同步）。
     */
    override suspend fun computePhaseTick(
        ctx: ParallelExecutionContext,
        phasesToSettle: Int
    ): ParallelPhaseResult {
        if (phasesToSettle < 3) return NoOpResult.INSTANCE
        parallelSettled = false

        val months = phasesToSettle / 3
        val t = ctx.discipleTables
        val gd = ctx.gameData

        // 列直读 → 本地 maps（零对象分配，只读快照）
        val ids = t.ids.toList()
        val alive    = ids.associateWith { t.isAlive[it] == 1 }
        val age      = ids.associateWith { t.ages[it] }
        val gender   = ids.associateWith { t.genders.getOrNull(it) }
        val partner  = ids.associateWith { t.partnerIds.getOrNull(it) }.toMutableMap()
        val rootType = ids.associateWith { t.spiritRootTypes.getOrNull(it) }
        val parent1  = ids.associateWith { t.parentId1s.getOrNull(it) }
        val parent2  = ids.associateWith { t.parentId2s.getOrNull(it) }
        val loyalty  = ids.associateWith { t.loyalties[it] }.toMutableMap()

        val bannedCounts: Set<Int> = gd.daoCompanionBannedRootCounts
        val consent = gd.daoCompanionConsentRequired

        var consentRequest: Pair<Int, Int>? = null

        repeat(months) {
            if (consentRequest != null) return@repeat // 已触发确认请求，不再继续

            val eligibleMales = ids.filter { id ->
                alive[id] == true &&
                    (age[id] ?: 0) >= 18 &&
                    partner[id] == null &&
                    gender[id] == "male" &&
                    !isBannedRootCount(rootType[id], bannedCounts)
            }
            val eligibleFemales = ids.filter { id ->
                alive[id] == true &&
                    (age[id] ?: 0) >= 18 &&
                    partner[id] == null &&
                    gender[id] == "female" &&
                    !isBannedRootCount(rootType[id], bannedCounts)
            }
            if (eligibleMales.isEmpty() || eligibleFemales.isEmpty()) return@repeat

            val pairedFemaleIds = mutableSetOf<Int>()
            var foundAny = false

            for (male in eligibleMales) {
                for (female in eligibleFemales) {
                    if (female in pairedFemaleIds) continue
                    if (hasBloodRelation(male, female, parent1, parent2)) continue

                    if (GameRandom.nextDouble() < 0.006) {
                        if (consent) {
                            consentRequest = male to female
                            foundAny = true
                            break
                        }
                        partner[male] = female.toString()
                        loyalty[male] = (loyalty[male] ?: 50).coerceAtMost(100)
                        pairedFemaleIds.add(female)
                    }
                }
                if (foundAny || consentRequest != null) break
            }
        }

        return PartnerMatchResult(
            partnerUpdates = partner,
            loyaltyUpdates = loyalty,
            consentRequest = consentRequest
        ).also { parallelSettled = true }
    }

    /**
     * 旬级 tick。
     *
     * 如果并行 compute 已完成（[parallelSettled] == true），
     * 所有工作已由 [computePhaseTick] + [apply] 完成，此处跳过。
     * 否则走串行兜底（热控关闭并行时的 fallback）。
     */
    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        if (parallelSettled) return
        // 串行兜底
        if (phasesToSettle < 3) return
        val months = phasesToSettle / 3
        repeat(months) { processPartnerMatching(state) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 串行兜底（保持原逻辑不变）
    // ═══════════════════════════════════════════════════════════════

    @Suppress("MemberVisibilityCanBePrivate")
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
                            male.id -> disciple.copy(social = disciple.social.copy(partnerId = female.id))
                            female.id -> disciple.copy(social = disciple.social.copy(partnerId = male.id))
                            else -> disciple
                        }
                    }
                    pairedFemaleIds.add(female.id)
                }
            }
        }

        if (pairedFemaleIds.isNotEmpty()) {
            state.discipleTables.clear()
            currentList.forEach { state.discipleTables.insert(it) }
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

    /** 列直读版 hasBloodRelation — 用于并行 compute 阶段（无需 Disciple 对象） */
    private fun hasBloodRelation(
        aId: Int,
        bId: Int,
        parent1: Map<Int, String?>,
        parent2: Map<Int, String?>
    ): Boolean {
        val aP1 = parent1[aId]
        val aP2 = parent2[aId]
        val bP1 = parent1[bId]
        val bP2 = parent2[bId]
        val aStr = aId.toString()
        val bStr = bId.toString()
        return aStr == bP1 || aStr == bP2 ||
            bStr == aP1 || bStr == aP2 ||
            (aP1 != null && aP1 == bP1) ||
            (aP1 != null && aP1 == bP2) ||
            (aP2 != null && aP2 == bP1) ||
            (aP2 != null && aP2 == bP2)
    }

    private fun isBannedRootCount(rootType: String?, bannedCounts: Set<Int>): Boolean {
        return bannedCounts.contains(rootType?.split(",")?.size ?: 0)
    }
}
