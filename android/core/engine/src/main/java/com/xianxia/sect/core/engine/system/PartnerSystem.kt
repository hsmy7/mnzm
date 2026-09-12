package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.ComponentTable
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 240)
/**
 * 伴侣系统（道侣配对）。
 *
 * 月度伴侣配对（不监听突破事件，无伴侣突破忠诚加成）。
 */
class PartnerSystem @Inject constructor(
    private val rngManager: GameRngManager
) : GameSystem {

    override val systemName: String = "PartnerSystem"

    companion object {
        private const val PAIRING_PROBABILITY = 0.006
    }

    override fun onMonthlyEvent(state: MutableGameState) {
        processPartnerMatching(state)
    }

    // ═══════════════════════════════════════════════════════════════
    // 伴侣配对
    // ═══════════════════════════════════════════════════════════════

    internal fun processPartnerMatching(state: MutableGameState) {
        val allDisciples = state.discipleTables.assembleAll()

        // 清理失效提议：已死亡或已有道侣的提议应移除
        val aliveIds = allDisciples.filter { it.isAlive }.map { it.id }.toSet()
        val partnerIds = state.discipleTables.partnerIds
        state.pendingMarriageProposals = state.pendingMarriageProposals.filter { p ->
            isProposalStillValid(p, aliveIds, partnerIds)
        }

        val bannedRootCounts = state.gameData.daoCompanionBannedRootCounts

        val eligibleMales = allDisciples.filter { isPairingEligible(it, bannedRootCounts, "male") }
        val eligibleFemales = allDisciples.filter { isPairingEligible(it, bannedRootCounts, "female") }

        if (eligibleMales.isEmpty() || eligibleFemales.isEmpty()) return

        val pairedFemaleIds = mutableSetOf<String>()

        for (male in eligibleMales) {
            for (female in eligibleFemales) {
                // 已配对或血亲女性跳过（跳过者不消耗 SYSTEM 抽取——抽取序为对拍红线）
                if (female.id in pairedFemaleIds || hasBloodRelation(male, female)) continue
                if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < PAIRING_PROBABILITY) {
                    attemptPairing(state, male, female, pairedFemaleIds)
                }
            }
        }
    }

    /** 失效提议判定：双方存活且均未结成道侣 */
    private fun isProposalStillValid(
        p: PendingMarriageProposal,
        aliveIds: Set<String>,
        partnerIds: ComponentTable<String?>
    ): Boolean {
        if (p.maleId !in aliveIds || p.femaleId !in aliveIds) return false
        if (partnerIds.getOrNull(p.maleId.toIntOrNull() ?: return false) != null) return false
        return partnerIds.getOrNull(p.femaleId.toIntOrNull() ?: return false) == null
    }

    /** 配对资格判定：成年未婚未禁灵根 */
    private fun isPairingEligible(
        disciple: Disciple,
        bannedRootCounts: Set<Int>,
        gender: String
    ): Boolean {
        return disciple.isAlive && disciple.age >= 18 && disciple.social.partnerId == null &&
            disciple.gender == gender &&
            !bannedRootCounts.contains(disciple.spiritRootType.split(",").size)
    }

    /** 需同意模式：加入待处理提议（同对去重），锁定女方 */
    /** 单对配对尝试：按宗门同意模式分派提议/直接配对 */
    private fun attemptPairing(
        state: MutableGameState,
        male: Disciple,
        female: Disciple,
        pairedFemaleIds: MutableSet<String>
    ) {
        val consentRequired = state.gameData.daoCompanionConsentRequired
        if (consentRequired) {
            // 需同意模式：加入待处理列表，不自作主张配对
            proposePairing(state, male, female, pairedFemaleIds)
        } else {
            // 自动配对模式：直接配对
            pairDirectly(state, male, female, pairedFemaleIds)
        }
    }

    private fun proposePairing(
        state: MutableGameState,
        male: Disciple,
        female: Disciple,
        pairedFemaleIds: MutableSet<String>
    ) {
        // 去重检查：避免同一对已存在提议
        val alreadyProposed = state.pendingMarriageProposals.any {
            it.maleId == male.id && it.femaleId == female.id
        }
        if (alreadyProposed) return
        state.pendingMarriageProposals = state.pendingMarriageProposals +
            PendingMarriageProposal(male.id, male.name, female.id, female.name)
        pairedFemaleIds.add(female.id)
    }

    /** 自动配对模式：写入双向伴侣映射 + 婚礼事件，锁定女方 */
    private fun pairDirectly(
        state: MutableGameState,
        male: Disciple,
        female: Disciple,
        pairedFemaleIds: MutableSet<String>
    ) {
        val maleId = male.id.toIntOrNull() ?: return
        val femaleId = female.id.toIntOrNull() ?: return
        state.discipleTables.partnerIds[maleId] = female.id
        state.discipleTables.partnerIds[femaleId] = male.id
        pairedFemaleIds.add(female.id)
        state.recordGameEvent(
            GameEventCategory.SECT, GameEventType.MARRIAGE,
            "弟子${male.name}与弟子${female.name}结为道侣",
            male.id, male.name
        )
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
