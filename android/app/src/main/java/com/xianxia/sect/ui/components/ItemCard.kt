package com.xianxia.sect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.theme.GameColors

data class ItemCardData(
    val id: String = "",
    val name: String,
    val description: String = "",
    val rarity: Int,
    val quantity: Int = 1,
    val type: String? = null,
    val stats: Map<String, Int> = emptyMap(),
    val additionalInfo: String? = null,
    val price: Int = 0
)

@Composable
fun UnifiedItemCard(
    data: ItemCardData,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    showViewButton: Boolean = false,
    showQuantity: Boolean = true,
    showPrice: Boolean = false,
    onClick: () -> Unit = {},
    onViewDetail: (() -> Unit)? = null
) {
    val rarityColor = getRarityColor(data.rarity)

    Box(
        modifier = modifier.wrapContentSize(Alignment.Center).requiredSize(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = data.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            if (showQuantity && data.quantity > 1) {
                Text(
                    text = "x${data.quantity}",
                    fontSize = 9.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }

        if (showViewButton && isSelected && onViewDetail != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFD700))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onViewDetail() }
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ItemCard(
    data: ItemCardData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick ?: {}
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = data.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )
                RarityBadge(rarity = data.rarity)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = data.description,
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )

            if (data.type != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "类型: ${data.type}",
                    fontSize = 12.sp,
                    color = GameColors.TextTertiary
                )
            }

            if (data.stats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    data.stats.forEach { (label, value) ->
                        if (value > 0) {
                            StatBonus(label, "+$value")
                        }
                    }
                }
            }

            if (data.quantity > 1 || data.additionalInfo != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = data.additionalInfo ?: "数量: ${data.quantity}",
                    fontSize = 12.sp,
                    color = GameColors.JadeGreen
                )
            }

            content()
        }
    }
}

@Composable
fun CompactItemCard(
    data: ItemCardData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary
                )
                Text(
                    text = data.description,
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.Primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "x${data.quantity}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.Primary
                )
            }
        }
    }
}

@Composable
fun RarityBadge(rarity: Int) {
    val (text, color) = when (rarity) {
        1 -> "普通" to GameColors.RarityCommon
        2 -> "灵品" to GameColors.RaritySpirit
        3 -> "宝品" to GameColors.RarityTreasure
        4 -> "玄品" to GameColors.RarityMystic
        5 -> "地品" to GameColors.RarityEarth
        6 -> "天品" to GameColors.RarityHeaven
        else -> "普通" to GameColors.RarityCommon
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = color
        )
    }
}

@Composable
fun StatBonus(label: String, value: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(GameColors.Primary.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label $value",
            fontSize = 11.sp,
            color = GameColors.Primary
        )
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
    1 -> "普通"
    2 -> "灵品"
    3 -> "宝品"
    4 -> "玄品"
    5 -> "地品"
    6 -> "天品"
    else -> "普通"
}
