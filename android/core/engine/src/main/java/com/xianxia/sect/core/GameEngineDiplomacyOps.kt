package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.DomainLog

fun GameEngine.generateSectTradeItems(year: Int): List<MerchantItem> = diplomacyFacade.generateSectTradeItems(year)
fun GameEngine.getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> = diplomacyFacade.getOrRefreshSectTradeItems(sectId)
fun GameEngine.buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) = diplomacyFacade.buyFromSectTrade(sectId, itemId, quantity)
suspend fun GameEngine.buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) = diplomacyFacade.buyFromSectTradeSync(sectId, itemId, quantity)
fun GameEngine.giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): GiftResult = diplomacyFacade.giftSpiritStones(sectId, tier, bypassYearLimit)

/** 攻击预警 — 缓和关系：自动薄礼并取消此宗门的所有攻击预警 */
suspend fun GameEngine.appeaseAttackingSect(sectId: String): GiftResult {
    val result = diplomacyFacade.giftSpiritStones(sectId, 1, bypassYearLimit = true)
    if (result.success && result.newFavor > 0) {
        updateGameData { data ->
            data.copy(activeAttackWarnings = data.activeAttackWarnings.filter {
                it.attackerSectId != sectId
            })
        }
    }
    return result
}

/** 攻击预警 — 附庸宗门：成为该宗门的附庸并取消攻击预警 */
suspend fun GameEngine.becomeVassalOfAttacker(sectId: String) {
    updateGameData { data ->
        data.copy(
            suzerainSectId = sectId,
            activeAttackWarnings = data.activeAttackWarnings.filter {
                it.attackerSectId != sectId
            }
        )
    }
}

/** 标记预警阶段已展示（避免重复弹窗） */
suspend fun GameEngine.markWarningStageShown(stageKey: String) {
    updateGameData { data ->
        data.copy(shownWarningStageIds = data.shownWarningStageIds + stageKey)
    }
}
suspend fun GameEngine.requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> = diplomacyFacade.requestAlliance(sectId, envoyDiscipleId)
fun GameEngine.dissolveAlliance(sectId: String): Pair<Boolean, String> = diplomacyFacade.dissolveAlliance(sectId)
fun GameEngine.getRejectProbability(sectLevel: Int, rarity: Int): Int = diplomacyFacade.getRejectProbability(sectLevel, rarity)
fun GameEngine.checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> = diplomacyFacade.checkAllianceConditions(sectId, envoyDiscipleId)
fun GameEngine.calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double = diplomacyFacade.calculatePersuasionSuccessRate(favorability, intelligence, charm)
fun GameEngine.getEnvoyRealmRequirement(sectLevel: Int): Int = diplomacyFacade.getEnvoyRealmRequirement(sectLevel)
fun GameEngine.getAllianceCost(sectLevel: Int): Long = diplomacyFacade.getAllianceCost(sectLevel)
fun GameEngine.isAlly(sectId: String): Boolean = diplomacyFacade.isAlly(sectId)
fun GameEngine.getAllianceRemainingYears(sectId: String): Int = diplomacyFacade.getAllianceRemainingYears(sectId)
fun GameEngine.getPlayerAllies(): List<String> = diplomacyFacade.getPlayerAllies()
fun GameEngine.interactWithSect(sectId: String, action: String) { DomainLog.w("GameEngine", "interactWithSect 尚未实现: sectId=$sectId, action=$action") }
