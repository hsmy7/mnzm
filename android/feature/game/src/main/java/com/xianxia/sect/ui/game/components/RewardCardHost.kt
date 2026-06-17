package com.xianxia.sect.ui.game.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.components.getRewardSprite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 奖励卡片覆盖层 — 卡片以 3dp 间距依次弹出，向上飘散。
 */
@Composable
fun RewardCardHost(
    rewardCards: List<RewardCardItem>,
    onAnimationComplete: () -> Unit
) {
    if (rewardCards.isEmpty()) return

    // 匀速上飘 0→-900px 耗时 1600ms + 淡出 500ms
    val totalMs = rewardCards.size * 300L + 2500L

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // zIndex 控制绘制顺序：先出现的卡片在上层
        rewardCards.forEachIndexed { index, item ->
            key(item.id) {
                Box(modifier = Modifier.zIndex((-index).toFloat())) {
                    AnimatedRewardCard(
                        item = item,
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

private val CARD_BG = Color(0xFF6A6A6A)
private val GOLD = Color(0xFFFFC107)

@Composable
private fun AnimatedRewardCard(
    item: RewardCardItem,
    cardIndex: Int,
    totalCards: Int,
    showTopDivider: Boolean = false,
    onAllDone: () -> Unit
) {
    // 每张卡片需等上一张上升 卡片高度+2dp间距 后才出发
    // 卡片高度 = 上横线1dp + 内边距1dp×2 + 内容30dp + 下横线1dp + 间隔2dp = 36dp
    val staggerMs = with(LocalDensity.current) {
        ((36.dp + 2.dp).toPx() / 900f * 1600f).toLong()
    }
    val animTY = remember { Animatable(0f) }
    val animAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(cardIndex * staggerMs)
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
                    .width(200.dp)
            )
        }
        // Card body — dark background between dividers
        Box(
            modifier = Modifier
                .width(200.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.15f to CARD_BG,
                        0.85f to CARD_BG,
                        1.0f to Color.Transparent
                    )
                )
                .padding(horizontal = 10.dp, vertical = 1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 品阶色背景 + 灰色边框，无精灵时显示占位文本
                val rarityColor = getRarityColor(item.rarity)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(rarityColor)
                        .border(1.dp, Color.Gray),
                    contentAlignment = Alignment.Center
                ) {
                    val sprite = getRewardSprite(item.itemType, item.itemName, item.rarity)
                    if (sprite != null && sprite != 0) {
                        Image(
                            painter = painterResource(id = sprite),
                            contentDescription = item.itemName,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(2.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "敬请期待",
                            color = Color.Black,
                            fontSize = 7.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = item.itemName,
                    color = GOLD,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "x${item.quantity}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        HorizontalDivider(
            color = Color.Gray,
            thickness = 1.dp,
            modifier = Modifier
                .width(200.dp)
        )
        // 卡片间隔 2dp
        Spacer(modifier = Modifier.height(2.dp))
    }
}
