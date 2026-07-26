package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.MutableGameState
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
                    // 消息栏系统：移除了 consentRequired 弹窗，改为自动配对 + 事件记录
                    // ★ 直接列写入 partnerIds，避免 assembleAll → map → replaceAll
                    // 全表替换走 writeGuard 可能触发竞态崩溃 #3057
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
