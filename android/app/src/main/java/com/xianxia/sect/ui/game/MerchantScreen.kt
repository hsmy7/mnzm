package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun MerchantDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val merchantItems = gameData?.travelingMerchantItems ?: emptyList()
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                MerchantHeader(
                    gameData = gameData,
                    onDismiss = onDismiss
                )

                if (merchantItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "商人正在旅途中...\n请稍后再来",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(68.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(merchantItems) { item ->
                            MerchantItemCard(
                                item = item,
                                isSelected = selectedItem?.id == item.id,
                                onClick = {
                                    if (selectedItem?.id == item.id) {
                                        selectedItem = null
                                        buyQuantity = 1
                                    } else {
                                        selectedItem = item
                                        buyQuantity = 1
                                    }
                                },
                                onViewDetail = {
                                    selectedItem = item
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                }

                PurchasePanel(
                    item = selectedItem,
                    quantity = buyQuantity,
                    maxQuantity = selectedItem?.quantity ?: 1,
                    spiritStones = gameData?.spiritStones ?: 0,
                    onQuantityChange = { qty -> 
                        selectedItem?.let { buyQuantity = qty.coerceIn(1, it.quantity) }
                    },
                    onConfirm = {
                        selectedItem?.let { item ->
                            viewModel.buyFromMerchant(item.id, buyQuantity)
                            selectedItem = null
                            buyQuantity = 1
                        }
                    },
                    onCancel = {
                        selectedItem = null
                        buyQuantity = 1
                    }
                )
            }
        }
    }

    if (showDetailDialog && selectedItem != null) {
        ItemDetailDialog(
            item = selectedItem!!,
            onDismiss = { showDetailDialog = false }
        )
    }
}

@Composable
private fun MerchantHeader(
    gameData: GameData?,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "云游商人",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "灵石: ${gameData?.spiritStones ?: 0}",
                fontSize = 11.sp,
                color = GameColors.TextSecondary
            )
        }

        TextButton(onClick = onDismiss) {
            Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MerchantItemCard(
    item: MerchantItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onViewDetail: () -> Unit
) {
    val rarityColor = getRarityColor(item.rarity)
    
    Box(
        modifier = Modifier.size(68.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.TextPrimary,
                    maxLines = if (item.name.length > 5) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = "${item.price}灵石",
                    fontSize = 9.sp,
                    color = GameColors.GoldDark,
                    maxLines = 1
                )
            }
            
            Text(
                text = "x${item.quantity}",
                fontSize = 9.sp,
                color = GameColors.TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }
        
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable(onClick = onViewDetail)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PurchasePanel(
    item: MerchantItem?,
    quantity: Int,
    maxQuantity: Int,
    spiritStones: Long,
    onQuantityChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val totalPrice = (item?.price ?: 0) * quantity
    val canAfford = spiritStones >= totalPrice && item != null
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (item != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = item.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextPrimary
                        )
                        Text(
                            text = "单价: ${item.price} 灵石",
                            fontSize = 10.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "购买数量:",
                            fontSize = 11.sp,
                            color = GameColors.TextSecondary
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(GameColors.Background)
                                .clickable { onQuantityChange(quantity - 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                        }
                        
                        Text(
                            text = "$quantity",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextPrimary,
                            modifier = Modifier.widthIn(min = 24.dp),
                            textAlign = TextAlign.Center
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(GameColors.Background)
                                .clickable { onQuantityChange(quantity + 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "总价: $totalPrice 灵石",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canAfford) GameColors.GoldDark else Color.Red
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = onCancel) {
                            Text("取消", fontSize = 11.sp, color = GameColors.TextSecondary)
                        }
                        
                        Button(
                            onClick = onConfirm,
                            enabled = canAfford && quantity > 0,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text("确认购买", fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Text(
                    text = "请选择要购买的商品",
                    fontSize = 12.sp,
                    color = GameColors.TextSecondary
                )
            }
        }
    }
}

private fun getRarityColor(rarity: Int): Color = when (rarity) {
    1 -> GameColors.RarityCommon
    2 -> GameColors.RaritySpirit
    3 -> GameColors.RarityTreasure
    4 -> GameColors.RarityMystic
    5 -> GameColors.RarityEarth
    6 -> GameColors.RarityHeaven
    else -> GameColors.RarityCommon
}

private fun getRarityName(rarity: Int): String = when (rarity) {
    1 -> "凡品"
    2 -> "灵品"
    3 -> "宝品"
    4 -> "玄品"
    5 -> "地品"
    6 -> "天品"
    else -> "凡品"
}

@Composable
private fun ItemDetailDialog(
    item: MerchantItem,
    onDismiss: () -> Unit
) {
    val rarityColor = getRarityColor(item.rarity)
    val rarityName = getRarityName(item.rarity)
    val typeName = when (item.type) {
        "equipment" -> "装备"
        "manual" -> "功法"
        "pill" -> "丹药"
        "material" -> "材料"
        "herb" -> "灵草"
        "seed" -> "种子"
        else -> "物品"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Column {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                Text(
                    text = "$typeName · $rarityName",
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Divider(color = GameColors.Background, thickness = 1.dp)
                
                Text(
                    text = "道具效果",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                val effectText = getItemEffectText(item)
                Text(
                    text = effectText,
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = GameColors.TextSecondary)
            }
        }
    )
}

private fun getItemEffectText(item: MerchantItem): String {
    val template = EquipmentDatabase.getTemplateByName(item.name)
    if (template != null) {
        val effects = mutableListOf<String>()
        if (template.physicalAttack > 0) effects.add("物理攻击+${template.physicalAttack}")
        if (template.magicAttack > 0) effects.add("法术攻击+${template.magicAttack}")
        if (template.physicalDefense > 0) effects.add("物理防御+${template.physicalDefense}")
        if (template.magicDefense > 0) effects.add("法术防御+${template.magicDefense}")
        if (template.hp > 0) effects.add("生命值+${template.hp}")
        if (template.mp > 0) effects.add("灵力值+${template.mp}")
        if (template.speed > 0) effects.add("速度+${template.speed}")
        if (template.critChance > 0) effects.add("暴击率+${String.format("%.1f", template.critChance * 100)}%")
        return if (effects.isNotEmpty()) effects.joinToString("，") else template.description
    }

    val manualTemplate = ManualDatabase.getByName(item.name)
    if (manualTemplate != null) {
        val effects = mutableListOf<String>()
        val stats = manualTemplate.stats
        if (stats.containsKey("cultivationSpeedPercent")) effects.add("修炼速度+${stats["cultivationSpeedPercent"]}%")
        if (stats.containsKey("breakthroughChance")) effects.add("突破概率+${stats["breakthroughChance"]}%")
        if (stats.containsKey("physicalAttack")) effects.add("物攻+${stats["physicalAttack"]}")
        if (stats.containsKey("magicAttack")) effects.add("法攻+${stats["magicAttack"]}")
        if (stats.containsKey("physicalDefense")) effects.add("物防+${stats["physicalDefense"]}")
        if (stats.containsKey("magicDefense")) effects.add("法防+${stats["magicDefense"]}")
        if (stats.containsKey("hp")) effects.add("生命+${stats["hp"]}")
        if (stats.containsKey("mp")) effects.add("灵力+${stats["mp"]}")
        if (stats.containsKey("speed")) effects.add("速度+${stats["speed"]}")
        if (stats.containsKey("critRate")) effects.add("暴击率+${stats["critRate"]}%")
        return if (effects.isNotEmpty()) effects.joinToString("，") else manualTemplate.description
    }

    val pillRecipe = PillRecipeDatabase.getRecipeByName(item.name)
    if (pillRecipe != null) {
        return pillRecipe.description
    }

    val herb = HerbDatabase.getHerbByName(item.name)
    if (herb != null) {
        return "用于炼制丹药的材料"
    }

    val seed = HerbDatabase.getSeedByName(item.name)
    if (seed != null) {
        return "种植后可获得${seed.yield}个${seed.name.removeSuffix("种")}"
    }

    val material = BeastMaterialDatabase.getMaterialByName(item.name)
    if (material != null) {
        return "用于炼制装备的材料"
    }

    return "神秘的物品，用途未知"
}
