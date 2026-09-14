package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.json.put



suspend fun GameEngine.getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> = diplomacyFacade
    .getOrRefreshSectTradeItems(sectId)
suspend fun GameEngine.buyFromSectTradeSync(sectId: String, itemId: String,
    quantity: Int = 1) = diplomacyFacade.buyFromSectTradeSync(sectId, itemId, quantity)
suspend fun GameEngine.giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): GiftResult =
    engineContextDispatcher.withEngineContext {
        diplomacyFacade.giftSpiritStones(sectId, tier, bypassYearLimit)
    }

/**
 * 标记预警阶段已展示（避免重复弹窗）。
 *
 * native 臂（W4-B/B4，DIPLOMACY_WARNING_STAGE_TX）：shownWarningStageIds 参与存档
 * ⇒ 按 ① 处置（保守判定，批文档盲区 #2）——C++ 追加（不去重，与回退臂逐位一致）；
 * 失败/降级 → Kotlin 原路径。镜像服务可空判空（handover findings 13）。
 */
suspend fun GameEngine.markWarningStageShown(stageKey: String) {
    if (NativeEngineFlag.authoritative) {
        val sync: StateSyncService? = stateSyncService
        if (sync != null) {
            val data = GameEngineNativeOps.tryExecuteNative(
                stateSyncService = sync,
                actionId = ActionIds.DIPLOMACY_WARNING_STAGE_TX,
                paramsJson = params {
                    put("stageKey", stageKey)
                }
            )
            if (data != null) return
        }
    }
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
