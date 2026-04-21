package com.xianxia.sect.ui.game.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.components.getRarityName
import com.xianxia.sect.ui.components.GameButton

@Composable
fun GiftDialog(
    sect: WorldSect?,
    gameData: GameData?,
    equipment: List<EquipmentInstance>,
    manuals: List<ManualInstance>,
    pills: List<Pill>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("灵石送礼", "物品送礼")
    
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
                            Column {
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
                                    val giftPref = gameData?.sectDetails?.get(sect?.id)?.giftPreference
                                    if (giftPref != null && giftPref != GiftPreferenceType.NONE) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFD700).copy(alpha = 0.9f)
                                        ) {
                                            Text(
                                                text = "偏好: ${giftPref.displayName}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF333333),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                FavorProgressBar(currentFavor = relation, maxFavor = 100)
                            }
                            
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
                
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = GameColors.PageBackground,
                    contentColor = GameColors.Primary,
                    divider = {
                        HorizontalDivider(color = GameColors.Border)
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { 
                                Text(
                                    title, 
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == index) GameColors.Primary else GameColors.TextSecondary
                                ) 
                            }
                        )
                    }
                }
                
                when (selectedTab) {
                    0 -> SpiritStoneGiftTab(
                        sect = sect,
                        gameData = gameData,
                        hasGiftedThisYear = hasGiftedThisYear,
                        viewModel = viewModel,
                        worldMapViewModel = worldMapViewModel,
                        onDismiss = onDismiss
                    )
                    1 -> ItemGiftTab(
                        sect = sect,
                        gameData = gameData,
                        equipment = equipment,
                        manuals = manuals,
                        pills = pills,
                        hasGiftedThisYear = hasGiftedThisYear,
                        viewModel = viewModel,
                        worldMapViewModel = worldMapViewModel,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun FavorProgressBar(currentFavor: Int, maxFavor: Int) {
    val progress = (currentFavor.toFloat() / maxFavor).coerceIn(0f, 1f)
    
    val favorColor = when {
        currentFavor >= 70 -> GameColors.Success
        currentFavor >= 50 -> GameColors.JadeGreen
        currentFavor >= 40 -> GameColors.Warning
        else -> GameColors.Error
    }
    
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

@Suppress("UNUSED_PARAMETER")
@Composable
private fun ItemGiftTab(
    sect: WorldSect?,
    gameData: GameData?,
    equipment: List<EquipmentInstance>,
    manuals: List<ManualInstance>,
    pills: List<Pill>,
    hasGiftedThisYear: Boolean,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<GiftableItem?>(null) }
    var selectedQuantity by remember { mutableIntStateOf(1) }
    var selectedType by remember { mutableStateOf("manual") }
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    
    val giftableItems = remember(equipment, manuals, pills) {
        val items = mutableListOf<GiftableItem>()
        
        manuals.groupBy { it.name }.forEach { (name, manualList) ->
            val first = manualList.first()
            items.add(GiftableItem(
                id = first.id,
                name = name,
                type = "manual",
                rarity = first.rarity,
                quantity = manualList.size,
                originalItem = first
            ))
        }
        
        equipment.groupBy { it.name }.forEach { (name, equipList) ->
            val first = equipList.first()
            items.add(GiftableItem(
                id = first.id,
                name = name,
                type = "equipment",
                rarity = first.rarity,
                quantity = equipList.size,
                originalItem = first
            ))
        }
        
        pills.groupBy { it.name }.forEach { (name, pillList) ->
            val first = pillList.first()
            items.add(GiftableItem(
                id = first.id,
                name = name,
                type = "pill",
                rarity = first.rarity,
                quantity = pillList.sumOf { it.quantity },
                originalItem = first,
                grade = first.grade.displayName
            ))
        }
        
        items.sortedWith(compareByDescending<GiftableItem> { it.rarity }.thenBy { it.name })
    }
    
    val filteredItems = remember(giftableItems, selectedType) {
        giftableItems.filter { it.type == selectedType }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GiftTypeChip(
                label = "功法",
                selected = selectedType == "manual",
                onClick = { 
                    selectedType = "manual"
                    selectedItem = null
                }
            )
            GiftTypeChip(
                label = "装备",
                selected = selectedType == "equipment",
                onClick = { 
                    selectedType = "equipment"
                    selectedItem = null
                }
            )
            GiftTypeChip(
                label = "丹药",
                selected = selectedType == "pill",
                onClick = { 
                    selectedType = "pill"
                    selectedItem = null
                }
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无可送礼的${getTypeName(selectedType)}",
                    fontSize = 14.sp,
                    color = GameColors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredItems, key = { "${it.id}_${it.type}" }) { item ->
                    UnifiedItemCard(
                        data = ItemCardData(
                            id = item.id,
                            name = item.name,
                            rarity = item.rarity,
                            quantity = item.quantity,
                            type = getTypeName(item.type),
                            grade = item.grade
                        ),
                        isSelected = selectedItem?.id == item.id && selectedItem?.type == item.type,
                        showViewButton = true,
                        onClick = {
                            selectedItem = if (selectedItem?.id == item.id && selectedItem?.type == item.type) {
                                null
                            } else {
                                selectedQuantity = 1
                                item
                            }
                        },
                        onViewDetail = {
                            detailItem = item.originalItem
                            showDetailDialog = true
                        }
                    )
                }
            }
        }
        
        AnimatedVisibility(
            visible = selectedItem != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            val item = selectedItem
            if (item != null) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已选: ${item.name}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GameColors.TextPrimary
                            )
                            Text(
                                text = "库存: ${item.quantity}",
                                fontSize = 12.sp,
                                color = GameColors.TextSecondary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("送礼数量", fontSize = 13.sp, color = GameColors.TextSecondary)
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(GameColors.Primary.copy(alpha = 0.1f))
                                        .clickable(enabled = selectedQuantity > 1) { 
                                            if (selectedQuantity > 1) selectedQuantity-- 
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "-", 
                                        fontSize = 18.sp, 
                                        color = if (selectedQuantity > 1) GameColors.Primary else GameColors.TextTertiary
                                    )
                                }
                                
                                Text(
                                    text = "$selectedQuantity",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GameColors.TextPrimary,
                                    modifier = Modifier.widthIn(min = 32.dp),
                                    textAlign = TextAlign.Center
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(GameColors.Primary.copy(alpha = 0.1f))
                                        .clickable(enabled = selectedQuantity < item.quantity) { 
                                            if (selectedQuantity < item.quantity) selectedQuantity++ 
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "+", 
                                        fontSize = 18.sp, 
                                        color = if (selectedQuantity < item.quantity) GameColors.Primary else GameColors.TextTertiary
                                    )
                                }
                                
                                Text(
                                    text = "全部",
                                    fontSize = 12.sp,
                                    color = GameColors.Primary,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clickable { 
                                            selectedQuantity = item.quantity 
                                        }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GameButton(
                                text = "送礼",
                                onClick = {
                                    if (!hasGiftedThisYear) {
                                        worldMapViewModel.giftItem(
                                            sectId = sect?.id ?: "",
                                            itemId = item.id,
                                            itemType = item.type,
                                            quantity = selectedQuantity
                                        )
                                        onDismiss()
                                    }
                                },
                                enabled = !hasGiftedThisYear
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showDetailDialog) {
        detailItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { 
                    showDetailDialog = false
                    detailItem = null
                }
            )
        }
    }
}

@Composable
private fun GiftTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) GameColors.Primary else Color(0xFFF0F0F0),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.White else GameColors.TextSecondary
            )
        }
    }
}

private data class GiftableItem(
    val id: String,
    val name: String,
    val type: String,
    val rarity: Int,
    val quantity: Int,
    val originalItem: Any? = null,
    val grade: String? = null
)

private fun formatSpiritStones(amount: Long): String {
    return when {
        amount >= 100000000 -> "${amount / 100000000}亿"
        amount >= 10000 -> "${amount / 10000}万"
        else -> amount.toString()
    }
}

private fun getTypeName(type: String): String = when (type) {
    "manual" -> "功法"
    "equipment" -> "装备"
    "pill" -> "丹药"
    else -> "物品"
}
