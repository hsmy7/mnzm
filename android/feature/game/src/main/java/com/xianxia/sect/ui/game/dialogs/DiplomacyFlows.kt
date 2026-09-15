package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.util.PresentationRandom
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

/**
 * 外交文案场景流入口（键规则 = `"diplomacy.<sectId>.<文案种类>"`）：
 * 同一宗门同一类文案恒定（跨会话一致）；关系值/成功与否等仍按原入参参与
 * 变体选择。键含场景实例身份（键纪律见 PresentationRandom KDoc）。
 */
private fun WorldMapInteractionViewModel.diplomacyScene(sectId: String, kind: String): PresentationRandom =
    presentationRandom.scene("diplomacy.$sectId.$kind")


/** 送礼聊天流程（SectDiplomacyDialog 拆分，原 onGiftTierClick 内联逻辑） */
internal suspend fun performGiftFlow(
    interactionViewModel: WorldMapInteractionViewModel,
    sectId: String,
    tier: Int,
    sectName: String,
    relationLevel: SectRelationLevel
): List<ChatMessage> {
    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        interactionViewModel.performGiftSpiritStones(sectId, tier)
    }
    val playerGiftText = buildPlayerGiftText(
        sectName, tier, interactionViewModel.diplomacyScene(sectId, "gift.player")
    )
    if (result != null) {
        val aiResponseText = if (result.success) {
            getGiftAiAcceptText(relationLevel, interactionViewModel.diplomacyScene(sectId, "gift.aiAccept"))
        } else {
            getGiftAiRejectText(relationLevel, interactionViewModel.diplomacyScene(sectId, "gift.aiReject"))
        }
        val playerReplyText = buildPlayerReplyText(
            result.success, interactionViewModel.diplomacyScene(sectId, "gift.reply")
        )
        return listOf(
            ChatMessage(text = playerGiftText, isPlayer = true),
            ChatMessage(text = aiResponseText, isPlayer = false),
            ChatMessage(text = playerReplyText, isPlayer = true)
        )
    }
    return listOf(
        ChatMessage(text = playerGiftText, isPlayer = true)
    )
}

/** 结盟聊天流程（SectDiplomacyDialog 拆分，原 onAllianceClick 内联逻辑） */
internal suspend fun performAllianceFlow(
    interactionViewModel: WorldMapInteractionViewModel,
    gameData: GameData?,
    sect: WorldSect
): List<ChatMessage> {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val playerSectId = playerSect?.id ?: ""
    val favor = if (playerSectId.isNotEmpty()) {
        gameData?.sectRelations?.find {
            (it.sectId1 == playerSectId && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSectId)
        }?.favor ?: 0
    } else 0

    val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        interactionViewModel.requestAllianceSimple(sect.id)
    }
    val aiText = getAiResponseText(favor, success)
    val playerReply = if (success) {
        "太好了！从今往后你我二宗同气连枝，守望相助！"
    } else {
        "既然贵宗无意，那我等也不便强求。告辞。"
    }

    return listOf(
        ChatMessage(
            text = "尊敬的道友，我宗愿与贵宗结为同盟，共谋发展，不知尊意如何？",
            isPlayer = true
        ),
        ChatMessage(text = aiText, isPlayer = false),
        ChatMessage(text = playerReply, isPlayer = true)
    )
}

/** 散盟聊天流程（SectDiplomacyDialog 拆分，原 onDissolveClick 内联逻辑） */
internal suspend fun performDissolveFlow(
    interactionViewModel: WorldMapInteractionViewModel,
    sectId: String
): List<ChatMessage> {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        interactionViewModel.dissolveAllianceSimple(sectId)
    }
    return listOf(
        ChatMessage(
            text = "道友，我宗深思熟虑后决定解除盟约，日后各走各路，还望见谅。",
            isPlayer = true
        ),
        ChatMessage(
            text = "既如此，我宗也不强留。从此两清，各自珍重。",
            isPlayer = false
        ),
        ChatMessage(
            text = "多谢成全，后会有期。",
            isPlayer = true
        )
    )
}

/** 附属聊天流程（SectDiplomacyDialog 拆分，原 onVassalClick 内联逻辑） */
internal suspend fun performVassalFlow(
    interactionViewModel: WorldMapInteractionViewModel,
    gameData: GameData?,
    sect: WorldSect
): List<ChatMessage> {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val favor = if (playerSect != null) {
        gameData?.sectRelations?.find {
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0

    val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        interactionViewModel.requestVassalContract(sect.id)
    }
    val aiText = getVassalAiResponseText(favor, success)
    val playerReply = buildPlayerVassalReplyText(
        success, interactionViewModel.diplomacyScene(sect.id, "vassal.reply")
    )
    return listOf(
        ChatMessage(
            text = buildPlayerVassalRequestText(
                sect.name, interactionViewModel.diplomacyScene(sect.id, "vassal.request")
            ),
            isPlayer = true
        ),
        ChatMessage(text = aiText, isPlayer = false),
        ChatMessage(text = playerReply, isPlayer = true)
    )
}

/** 解除附属聊天流程（SectDiplomacyDialog 拆分，原 onDissolveVassalClick 内联逻辑） */
internal suspend fun performDissolveVassalFlow(
    interactionViewModel: WorldMapInteractionViewModel,
    sectId: String
): List<ChatMessage> {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        interactionViewModel.dissolveVassalContract(sectId)
    }
    return listOf(
        ChatMessage(
            text = buildPlayerVassalDissolveText(
                interactionViewModel.diplomacyScene(sectId, "vassal.dissolvePlayer")
            ),
            isPlayer = true
        ),
        ChatMessage(
            text = getVassalAiDissolveText(interactionViewModel.diplomacyScene(sectId, "vassal.dissolveAi")),
            isPlayer = false
        ),
        ChatMessage(text = "好自为之。", isPlayer = true)
    )
}
