package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors

val LocalItemSpriteCache = staticCompositionLocalOf<Map<Int, ImageBitmap>> { emptyMap() }

data class ItemCardData(
    val id: String = "",
    val name: String,
    val description: String = "",
    val rarity: Int,
    val quantity: Int = 1,
    val type: String? = null,
    val stats: Map<String, Int> = emptyMap(),
    val additionalInfo: String? = null,
    val price: Long = 0L,
    val grade: String? = null,
    val isLocked: Boolean = false,
    val isManual: Boolean = false,
    val isPill: Boolean = false,
    val isMaterial: Boolean = false,
    val spiritStoneGrade: SpiritStoneGrade? = null,
    val isBag: Boolean = false,
    val isHerb: Boolean = false,
    val isSeed: Boolean = false
)

@Composable
fun UnifiedItemCard(
    data: ItemCardData,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    isSelected: Boolean = false,
    selectedBorderColor: Color = Color(0xFFFFD700),
    showQuantity: Boolean = true,
    showPrice: Boolean = false,
    craftable: Boolean = true,
    onClick: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    overlayButtonText: String? = null,
    onOverlayButtonClick: (() -> Unit)? = null,
    showPlaceholderText: Boolean = true,
    nameFontSize: androidx.compose.ui.unit.TextUnit = 9.sp
) {
    val rarityColor = getRarityColor(data.rarity)
    val spriteRes = when {
        data.spiritStoneGrade != null -> spiritStoneSpriteRes(data.spiritStoneGrade)
        data.isBag -> storageBagSpriteRes(data.rarity)
        data.isManual -> manualSpriteRes(data.rarity)
        data.isPill -> pillSpriteRes(data.rarity)
        data.isMaterial -> materialSpriteRes(data.name)
        data.isHerb -> herbSpriteRes(data.name)
        data.isSeed -> seedSpriteRes(data.name)
        else -> equipmentSpriteRes(data.name)
    }

    Box(
        modifier = modifier.wrapContentSize(Alignment.Center).size(size),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) selectedBorderColor else GameColors.Border,
                    shape = RoundedCornerShape(6.dp)
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(rarityColor),
                contentAlignment = Alignment.Center
            ) {
                if (spriteRes != null) {
                    val cachedBitmap = LocalItemSpriteCache.current[spriteRes]
                    if (cachedBitmap != null) {
                        Image(
                            bitmap = cachedBitmap,
                            contentDescription = data.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Image(
                            painter = painterResource(id = spriteRes),
                            contentDescription = data.name,
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else if (showPlaceholderText) {
                    Text(
                        text = "敬请期待",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(2.dp)
                    )
                }

                if (data.isLocked) {
                    Text(
                        text = "锁定",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 3.dp, top = 2.dp)
                    )
                }

                if (!data.grade.isNullOrEmpty()) {
                    Text(
                        text = data.grade,
                        fontSize = 8.sp,
                        color = getQualityColor(data.grade),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 3.dp, bottom = 2.dp)
                    )
                }

                if (showQuantity) {
                    Text(
                        text = GameUtils.formatNumber(data.quantity),
                        fontSize = 8.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 3.dp, bottom = 2.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = data.name,
                    fontSize = nameFontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }

        if (isSelected && overlayButtonText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFD700))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onOverlayButtonClick?.invoke() }
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = overlayButtonText,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        if (!craftable) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "材料不足",
                    fontSize = 8.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EmptyListMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = GameColors.TextSecondary
        )
    }
}

fun getRarityColor(rarity: Int): Color = when (rarity) {
    1 -> GameColors.RarityCommon
    2 -> GameColors.RaritySpirit
    3 -> GameColors.RarityTreasure
    4 -> GameColors.RarityMystic
    5 -> GameColors.RarityEarth
    6 -> GameColors.RarityHeaven
    else -> GameColors.RarityCommon
}

fun getRarityName(rarity: Int): String = when (rarity) {
    1 -> "凡品"
    2 -> "灵品"
    3 -> "宝品"
    4 -> "玄品"
    5 -> "地品"
    6 -> "天品"
    else -> "凡品"
}

fun getQualityColor(quality: String?): Color = when (quality) {
    "上品" -> Color(0xFFE74C3C)
    "中品" -> Color(0xFF3498DB)
    "下品" -> Color(0xFF95A5A6)
    else -> Color(0xFF95A5A6) // 默认灰色，防止异常值导致不可见文字
}
