package com.xianxia.sect.ui.game.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.ui.components.getRewardSprite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 奖励卡片覆盖层 — 屏幕正中央，所有卡片向上飘散。
 */
@Composable
fun RewardCardHost(
    rewardCards: List<RewardCardItem>,
    onAnimationComplete: () -> Unit
) {
    if (rewardCards.isEmpty()) return

    // 记录总时长，确保动画完全结束后才清理
    val totalMs = 1500L + rewardCards.size * 100L + 300L

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // offset up by half total height so stack is vertically centered
        val cardH = 44
        val stackH = rewardCards.size * cardH
        Column(
            modifier = Modifier.offset(y = (-stackH / 2).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(
                color = Color.Gray,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .widthIn(max = 280.dp)
            )
            rewardCards.forEachIndexed { index, item ->
                AnimatedRewardCard(
                    item = item,
                    staggerMs = index * 100L,
                    cardIndex = index,
                    totalCards = rewardCards.size,
                    onAllDone = { onAnimationComplete() }
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
    onAllDone: () -> Unit
) {
    val animTY = remember { Animatable(0f) }
    val animAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(staggerMs)
        // parallel: fade in + move up
        launch { animAlpha.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
        launch { animTY.animateTo(-200f, tween(1500, easing = FastOutSlowInEasing)) }
        // hold ~1s then fade out
        delay(1000)
        animAlpha.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
        // last card signals completion
        if (cardIndex == totalCards - 1) {
            delay(300)
            onAllDone()
        }
    }

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .height(44.dp)
            .graphicsLayer {
                translationY = animTY.value
                alpha = animAlpha.value.coerceIn(0f, 1f)
            },
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
}
