package com.xianxia.sect.ui.game.dialogs

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.PresentationRandom
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.clickableWithSound
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*


// ═══════════ 送礼选项面板 ═══════════

@Composable
internal fun GiftOptionsPanel(
    spiritStones: Long,
    onGiftTierClick: (Int) -> Unit,
    onCancelClick: () -> Unit
) {
    val tiers = GiftConfig.SpiritStoneGiftConfig.getAllTiers().sortedByDescending { it.tier }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        tiers.forEachIndexed { index, tier ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color.Gray.copy(alpha = 0.3f)
                )
            }
            val canAfford = spiritStones >= tier.spiritStones
            val displayText = if (canAfford) {
                "${tier.name} - ${GameUtils.formatNumber(tier.spiritStones)}"
            } else {
                "${tier.name} - 灵石不足"
            }
            Text(
                text = displayText,
                fontSize = 16.sp,
                color = if (canAfford) Color.Black else Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableWithSound(enabled = canAfford) { onGiftTierClick(tier.tier) }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(
            thickness = 1.dp,
            color = Color.Gray.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        GameButton(
            text = "取消",
            onClick = onCancelClick,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )
    }
}

// ═══════════ 送礼聊天文本 ═══════════

/**
 * 玩家送礼描述文本
 * @param sectName 目标宗门名称
 * @param tier 送礼档位 (1-4)
 * @param random 表现类随机源（文案选择不写任何状态，ADR R3：表现随机与决策随机隔离）
 */
internal fun buildPlayerGiftText(sectName: String, tier: Int, random: PresentationRandom): String {
    val texts = GIFTS_TEMPLATES[tier] ?: listOf("${sectName}的道友，这是我宗的一点心意，还请笑纳。")
    return random.pick(texts).replace("{S}", sectName)
}

/**
 * AI接受送礼文本
 * @param relationLevel 当前关系等级
 * @param random 表现类随机源（同上）
 */
internal fun getGiftAiAcceptText(relationLevel: SectRelationLevel, random: PresentationRandom): String {
    return random.pick(GIFT_AI_ACCEPT_TEXTS[relationLevel] ?: listOf("多谢道友厚礼。"))
}

/**
 * AI拒绝送礼文本
 * @param relationLevel 当前关系等级
 * @param random 表现类随机源（同上）
 */
internal fun getGiftAiRejectText(relationLevel: SectRelationLevel, random: PresentationRandom): String {
    return random.pick(GIFT_AI_REJECT_TEXTS[relationLevel] ?: listOf("本宗不能接受。"))
}

/**
 * 玩家回应送礼文本
 * @param success 送礼是否成功（接受=true，拒绝=false）
 * @param random 表现类随机源（同上）
 */
internal fun buildPlayerReplyText(success: Boolean, random: PresentationRandom): String {
    return random.pick(if (success) PLAYER_REPLY_ACCEPT_TEXTS else PLAYER_REPLY_REJECT_TEXTS)
}
