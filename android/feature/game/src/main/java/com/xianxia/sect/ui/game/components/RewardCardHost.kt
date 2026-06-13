package com.xianxia.sect.ui.game.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.ui.components.getRewardSprite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 奖励卡片覆盖层 — 所有卡片从屏幕正中央同一位置依次弹出，向上飘散。
 */
@Composable
fun RewardCardHost(
    rewardCards: List<RewardCardItem>,
    onAnimationComplete: () -> Unit
) {
    if (rewardCards.isEmpty()) return

    // 弹出间隔 100ms，匀速上飘 0→-900px 耗时 1600ms
    val staggerInterval = 100L
    val totalMs = rewardCards.size * staggerInterval + 2800L

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 所有卡片叠加在中心，依次弹出向上飘散
        // zIndex 控制绘制顺序：先出现的卡片在上层，避免后出现的卡片遮盖已飞出的卡片
        rewardCards.forEachIndexed { index, item ->
            key(item.id) {
                Box(modifier = Modifier.zIndex((-index).toFloat())) {
                    AnimatedRewardCard(
                    item = item,
                    staggerMs = index * staggerInterval,
                    cardIndex = index,
                    totalCards = rewardCards.size,
                    showTopDivider = true,
                    onAllDone = { onAnimationComplete() }
                    )
                }
            }
        }
    }

    // Fallback: clear after max time
    LaunchedEffect(Unit) {
        delay(totalMs)
        onAnimationComplete()
    }
}

@Composable
private fun AnimatedRewardCard(
    item: RewardCardItem,
    staggerMs: Long,
    cardIndex: Int,
    totalCards: Int,
    showTopDivider: Boolean = false,
    onAllDone: () -> Unit
) {
    val animTY = remember { Animatable(0f) }
    val animAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(staggerMs)
        // parallel: fade in (200ms) + float up (1600ms)
        launch { animAlpha.animateTo(1f, tween(200, easing = LinearEasing)) }
        launch { animTY.animateTo(-900f, tween(1600, easing = LinearEasing)) }
        delay(1600)
        animAlpha.animateTo(0f, tween(500, easing = LinearEasing))
        // last card signals completion
        if (cardIndex == totalCards - 1) {
            delay(300)
            onAllDone()
        }
    }

    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .graphicsLayer {
                translationY = animTY.value
                alpha = animAlpha.value.coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showTopDivider) {
            HorizontalDivider(
                color = Color.Gray,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .widthIn(max = 280.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val sprite = getRewardSprite(item.itemType, item.itemName, item.rarity)
            if (sprite != null && sprite != 0) {
                Image(
                    painter = painterResource(id = sprite),
                    contentDescription = item.itemName,
                    modifier = Modifier.size(36.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = item.itemName,
                color = Color.Black,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = "x${item.quantity}",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        HorizontalDivider(
            color = Color.Gray,
            thickness = 1.dp,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .widthIn(max = 280.dp)
        )
    }
}
