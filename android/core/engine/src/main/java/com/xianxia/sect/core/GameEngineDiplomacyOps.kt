package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.MerchantItem



suspend fun GameEngine.getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> = diplomacyFacade.getOrRefreshSectTradeItems(sectId)
suspend fun GameEngine.buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) = diplomacyFacade.buyFromSectTradeSync(sectId, itemId, quantity)
suspend fun GameEngine.giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): GiftResult =
    engineContextDispatcher.withEngineContext {
        diplomacyFacade.giftSpiritStones(sectId, tier, bypassYearLimit)
    }

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

/** 简化版结盟请求（聊天流使用） */
suspend fun GameEngine.requestAllianceSimple(sectId: String): Boolean =
    engineContextDispatcher.withEngineContext { diplomacyFacade.requestAllianceSimple(sectId) }

/** 简化版解除结盟（聊天流使用） */
suspend fun GameEngine.dissolveAllianceSimple(sectId: String): Boolean =
    engineContextDispatcher.withEngineContext { diplomacyFacade.dissolveAllianceSimple(sectId) }

/** 获取玩家第一个弟子的名字（用于聊天显示） */
fun GameEngine.getFirstPlayerDiscipleName(): String {
    val tables = stateStore.discipleTables
    val firstId = tables.ids.firstOrNull() ?: return "掌门"
    return tables.names[firstId] ?: "掌门"
}

/** 获取玩家第一个弟子的头像资源名（用于聊天头像） */
fun GameEngine.getFirstPlayerDisciplePortrait(): String {
    val tables = stateStore.discipleTables
    val firstId = tables.ids.firstOrNull() ?: return ""
    return tables.portraitRes.getOrNull(firstId) ?: ""
}

fun GameEngine.isAlly(sectId: String): Boolean = diplomacyFacade.isAlly(sectId)
fun GameEngine.getPlayerAllies(): List<String> = diplomacyFacade.getPlayerAllies()

// ═══ 附属宗门 ═══
suspend fun GameEngine.requestVassalContract(sectId: String): Boolean =
    engineContextDispatcher.withEngineContext { diplomacyFacade.requestVassalContract(sectId) }
suspend fun GameEngine.dissolveVassalContract(sectId: String): Boolean =
    engineContextDispatcher.withEngineContext { diplomacyFacade.dissolveVassalContract(sectId) }
fun GameEngine.isPlayerVassal(sectId: String): Boolean = diplomacyFacade.isPlayerVassal(sectId)
fun GameEngine.getPlayerVassals(): List<String> = diplomacyFacade.getPlayerVassals()
