package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.theme.GameColors

/** 事件区/选择区纯色面板背景（与消息栏展开态/通关奖励面板同色） */
internal val SecretRealmBackground = GameColors.ButtonBackground

/**
 * 探索弟子圆形头像（参考天道试炼 CombatantPortrait 圆形肖像；死亡置灰）。
 */
@Composable
internal fun SecretRealmPortrait(
    portraitRes: String,
    size: Int = 44,
    isDead: Boolean = false
) {
    val portraitResId = remember(portraitRes) {
        val id = if (portraitRes.isNotBlank()) PortraitPool.getResourceId(portraitRes) else 0
        if (id != 0) id else (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
    }
    val sizeDp = size.dp
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
            .border(2.dp, if (isDead) Color(0xFF9E9E9E) else Color.Gray, CircleShape)
            .background(if (isDead) GameColors.ButtonDisabled else Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = portraitResId),
            contentDescription = null,
            modifier = Modifier
                .size((size - 4).dp)
                .graphicsLayer {
                    alpha = if (isDead) 0.4f else 1f
                },
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * 探索弟子血量条（>50% 绿 / >25% 黄 / else 红；-1 视为满血）。
 */
@Composable
internal fun SecretRealmHpBar(
    currentHp: Int,
    maxHp: Int
) {
    val effectiveMax = if (maxHp > 0) maxHp else 1
    val effectiveHp = if (currentHp < 0) effectiveMax else currentHp.coerceIn(0, effectiveMax)
    val hpPercent = effectiveHp.toFloat() / effectiveMax
    val barColor = when {
        hpPercent > 0.5f -> GameColors.Success
        hpPercent > 0.25f -> Color(0xFFFFEB3B)
        else -> GameColors.Error
    }
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(5.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.DarkGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = hpPercent)
                .background(barColor, RoundedCornerShape(2.dp))
        )
    }
}

/**
 * 探索事件选项卡片（secret_realm_option_card 背景 + 居中文字 + 底部体力消耗，整卡可点）。
 *
 * @param staminaCost 选择该选项消耗的体力（卡片底部显示"体力-X"）
 */
@Composable
internal fun SecretRealmOptionCard(
    modifier: Modifier = Modifier,
    label: String,
    description: String,
    staminaCost: Int = 1,
    onClick: () -> Unit
) {
    // 篡改档防御：显示与引擎一致的 clamp 值（0/负值显示 -1，超大值显示 -20）
    val displayCost = staminaCost.coerceIn(
        com.xianxia.sect.core.GameConfig.SecretRealm.STAMINA_COST_PER_CHOICE,
        com.xianxia.sect.core.GameConfig.SecretRealm.STAMINA_MAX
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        SpriteImage(
            name = "secret_realm_option_card",
            contentDescription = null,
            modifier = Modifier.matchParentSize()
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 内容超高时在卡片内滚动，防止超长描述撑破卡片边界
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    fontSize = 10.sp,
                    color = Color.Black,
                    // 描述文字按卡片宽度自然换行（行列动态，无行数限制）
                    textAlign = TextAlign.Center
                )
            }
        }
        // 体力消耗（与滚动内容独立，固定贴底；警示红与秘境体力告警同色）
        Text(
            text = "体力-$displayCost",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.Error
        )
    }
}
