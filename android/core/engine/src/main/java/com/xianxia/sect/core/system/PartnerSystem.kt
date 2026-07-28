package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
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
 * 月度伴侣配对。不再监听突破事件（伴侣突破忠诚加成已移除）。
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
            p.maleId in aliveIds && p.femaleId in aliveIds &&
                partnerIds.getOrNull(p.maleId.toIntOrNull() ?: return@filter false) == null &&
                partnerIds.getOrNull(p.femaleId.toIntOrNull() ?: return@filter false) == null
        }

        val bannedRootCounts = state.gameData.daoCompanionBannedRootCounts

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

                if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < PAIRING_PROBABILITY) {
                    val consentRequired = state.gameData.daoCompanionConsentRequired
                    if (consentRequired) {
                        // 需同意模式：加入待处理列表，不自作主张配对
                        // 去重检查：避免同一对已存在提议
                        val alreadyProposed = state.pendingMarriageProposals.any {
                            it.maleId == male.id && it.femaleId == female.id
                        }
                        if (!alreadyProposed) {
                            state.pendingMarriageProposals = state.pendingMarriageProposals +
                                PendingMarriageProposal(male.id, male.name, female.id, female.name)
                            pairedFemaleIds.add(female.id)
                        }
                    } else {
                        // 自动配对模式：直接配对
                        val maleId = male.id.toIntOrNull() ?: continue
                        val femaleId = female.id.toIntOrNull() ?: continue
                        state.discipleTables.partnerIds[maleId] = female.id
                        state.discipleTables.partnerIds[femaleId] = male.id
                        pairedFemaleIds.add(female.id)
                        state.recordGameEvent(
                            GameEventCategory.SECT, GameEventType.MARRIAGE,
                            "弟子${male.name}与弟子${female.name}结为道侣",
                            male.id, male.name
                        )
                    }
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
