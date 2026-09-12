package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*



// ═══════════ 附属宗门聊天文本 ═══════════

/**
 * 玩家请求附属文本
 * @param sectName 目标宗门名称
 */
internal fun buildPlayerVassalRequestText(sectName: String): String {
    val texts = listOf(
        "贵宗实力尚弱，不如归附我宗。每年上贡灵石，我宗保你周全，如何？",
        "{S}的道友，我宗有意收纳贵宗为附属，每年只需按例上贡，不知意下如何？",
        "道友，我宗如今势大，愿庇护贵宗。归附于我，每年上贡灵石即可，你意如何？"
    )
    return texts.random().replace("{S}", sectName)
}

/**
 * AI回复附属请求文本
 * @param favor 好感度
 * @param success 是否接受
 */
internal fun getVassalAiResponseText(favor: Int, success: Boolean): String {
    return if (success) {
        when {
            favor >= 90 -> "哈哈哈！以贵宗之能愿意收纳我宗，是我宗的福气！我宗愿附骥尾！"
            favor >= 80 -> "道友诚意相邀，我宗岂有不从之理？从今日起，愿奉贵宗为主！"
            favor >= 60 -> "贵宗实力雄厚，我宗心服口服。愿遵贵宗号令，年年上贡。"
            favor >= 40 -> "......也罢，以贵宗之能确实远胜我宗，我宗愿意成为附属。"
            favor >= 20 -> "哼......既然你们这么说了，我宗便给这个面子，答应便是。"
            else -> "......算你们厉害，我宗认了。从今往后唯命是从。"
        }
    } else {
        when {
            favor >= 90 -> "道友厚爱，本宗心领。只是我宗历来独立惯了，做他人附属实在不妥，还望见谅。"
            favor >= 80 -> "这......道友盛情，只是此事关系重大，容我宗三思。"
            favor >= 60 -> "贵宗好意心领，但我宗虽弱，也不愿寄人篱下，此议就此作罢吧。"
            favor >= 40 -> "哼，我宗立派百年，岂能屈居人下？道友请回吧！"
            favor >= 20 -> "不必多言！我宗自有傲骨，绝不做他人附属！"
            else -> "就凭你们也想收我宗为附属？痴心妄想！速速离去，否则休怪本宗不客气！"
        }
    }
}

/**
 * 玩家回应附属请求文本
 * @param success 是否成功
 */
internal fun buildPlayerVassalReplyText(success: Boolean): String {
    return if (success) {
        listOf(
            "哈哈，好！有我宗一日，必保你宗平安。",
            "善！从今往后你我二宗便是一体，年年上贡即可。",
            "放心，我宗自会照拂于你。每年上贡按例即可。"
        ).random()
    } else {
        listOf(
            "既然贵宗无意，那便罢了，告辞。",
            "是在下唐突了，这便告辞。",
            "也罢，既然贵宗不愿，那此事不提便是。"
        ).random()
    }
}

/**
 * 玩家宣告解散附属文本
 */
internal fun buildPlayerVassalDissolveText(): String {
    return listOf(
        "从今日起，你宗不再是我宗附属，去吧。",
        "经我宗慎重考虑，从今日起解除附属关系，你宗自便。",
        "道友，我宗决定解除附属关系。从今往后各走各路，好自为之。"
    ).random()
}

/**
 * AI告别回复（被解散附属时）
 */
internal fun getVassalAiDissolveText(): String {
    return listOf(
        "......多谢宗主这些年来照拂。告辞。",
        "既如此，我宗也不强留。后会无期。",
        "也好，我宗本就该独立发展。承蒙关照了。"
    ).random()
}
