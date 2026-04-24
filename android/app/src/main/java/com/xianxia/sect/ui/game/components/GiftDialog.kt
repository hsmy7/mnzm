package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel

@Composable
fun GiftDialog(
    sect: WorldSect?,
    gameData: GameData?,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect?.id)?.lastGiftYear ?: 0) == currentYear
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null && sect != null) {
        gameData.sectRelations.find { 
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GameColors.Primary,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "向 ${sect?.name ?: "宗门"} 送礼",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                FavorProgressBar(currentFavor = relation, maxFavor = 100)
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "灵石",
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )
                                        Text(
                                            text = formatSpiritStones(gameData?.spiritStones ?: 0),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFFD700)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (hasGiftedThisYear) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFE74C3C).copy(alpha = 0.9f)
                                        ) {
                                            Text(
                                                text = "本年已送礼",
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .clickable { onDismiss() }
                                        .background(Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "×",
                                        fontSize = 16.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (!hasGiftedThisYear) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = GameColors.JadeGreen.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "每年只能向同一宗门送礼一次，请谨慎选择",
                                fontSize = 12.sp,
                                color = GameColors.JadeGreen
                            )
                        }
                    }
                }
                
                SpiritStoneGiftTab(
                    sect = sect,
                    gameData = gameData,
                    hasGiftedThisYear = hasGiftedThisYear,
                    viewModel = viewModel,
                    worldMapViewModel = worldMapViewModel,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun FavorProgressBar(currentFavor: Int, maxFavor: Int) {
    val progress = (currentFavor.toFloat() / maxFavor).coerceIn(0f, 1f)
    val relationLevel = GameUtils.getSectRelationLevel(currentFavor)
    
    val favorColor = Color(relationLevel.colorHex)
    
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "好感度",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(3.dp))
                        .background(favorColor)
                )
            }
            Text(
                text = "$currentFavor",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = favorColor.copy(alpha = 0.9f)
            ) {
                Text(
                    text = relationLevel.displayName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SpiritStoneGiftTab(
    sect: WorldSect?,
    gameData: GameData?,
    hasGiftedThisYear: Boolean,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val tiers = GiftConfig.SpiritStoneGiftConfig.getAllTiers()
    val spiritStones = gameData?.spiritStones ?: 0
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "选择送礼档位",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.TextPrimary
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        tiers.forEach { tier ->
            val canAfford = spiritStones >= tier.spiritStones
            val isDisabled = hasGiftedThisYear || !canAfford
            
            GiftTierCard(
                tier = tier,
                isDisabled = isDisabled,
                canAfford = canAfford,
                onClick = {
                    if (!isDisabled) {
                        worldMapViewModel.giftSpiritStones(sect?.id ?: "", tier.tier)
                        onDismiss()
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(10.dp))
        }
        
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GiftTierCard(
    tier: GiftConfig.SpiritStoneGiftTier,
    isDisabled: Boolean,
    canAfford: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isDisabled) GameColors.Border else Color(0xFFD2B48C)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDisabled) GameColors.CardBackground else GameColors.PageBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDisabled) 0.dp else 2.dp),
        onClick = onClick,
        enabled = !isDisabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = tier.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDisabled) GameColors.TextTertiary else GameColors.TextPrimary
                )
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSpiritStones(tier.spiritStones),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDisabled) GameColors.TextTertiary else Color(0xFFFFD700)
                )
                if (!canAfford) {
                    Text(
                        text = "灵石不足",
                        fontSize = 11.sp,
                        color = GameColors.Error
                    )
                }
            }
        }
    }
}

private fun formatSpiritStones(amount: Long): String {
    return when {
        amount >= 100000000 -> "${amount / 100000000}亿"
        amount >= 10000 -> "${amount / 10000}万"
        else -> amount.toString()
    }
}
