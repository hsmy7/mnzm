package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
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
    var showListingDialog by remember { mutableStateOf(false) }

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
                    onDismiss = onDismiss,
                    onListClick = { showListingDialog = true }
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

    if (showListingDialog) {
        ListingManagementDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showListingDialog = false }
        )
    }
}

@Composable
private fun MerchantHeader(
    gameData: GameData?,
    onDismiss: () -> Unit,
    onListClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameButton(
                text = "上架",
                onClick = onListClick
            )
            TextButton(onClick = onDismiss) {
                Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
            }
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
                .background(GameColors.PageBackground)
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
        color = GameColors.PageBackground,
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
                        
                        GameButton(
                            text = "确认购买",
                            onClick = onConfirm,
                            modifier = Modifier.height(32.dp),
                            enabled = canAfford && quantity > 0
                        )
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
        containerColor = GameColors.PageBackground,
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
        val recipes = PillRecipeDatabase.getRecipesByHerb(herb.id)
        return if (recipes.isNotEmpty()) {
            val recipeNames = recipes.take(3).map { it.name }
            val result = "可用于炼制: ${recipeNames.joinToString("、")}"
            if (recipes.size > 3) "$result 等${recipes.size}种丹药" else result
        } else {
            "用于炼制丹药的材料"
        }
    }

    val seed = HerbDatabase.getSeedByName(item.name)
    if (seed != null) {
        val herbFromSeed = HerbDatabase.getHerbFromSeed(seed.id)
        val baseInfo = "种植后可获得${seed.yield}个${seed.name.removeSuffix("种")}"
        return if (herbFromSeed != null) {
            val recipes = PillRecipeDatabase.getRecipesByHerb(herbFromSeed.id)
            if (recipes.isNotEmpty()) {
                val recipeNames = recipes.take(2).map { it.name }
                val pillInfo = "，可炼制${recipeNames.joinToString("、")}"
                if (recipes.size > 2) {
                    "$baseInfo$pillInfo 等${recipes.size}种丹药"
                } else {
                    "$baseInfo$pillInfo"
                }
            } else {
                baseInfo
            }
        } else {
            baseInfo
        }
    }

    val material = BeastMaterialDatabase.getMaterialByName(item.name)
    if (material != null) {
        return "用于炼制装备的材料"
    }

    return "神秘的物品，用途未知"
}

data class PlayerListItem(
    val id: String,
    val name: String,
    val type: String,
    val rarity: Int,
    val quantity: Int,
    val price: Int,
    val itemId: String
)

@Composable
fun ListingManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val playerListedItems = gameData?.playerListedItems ?: emptyList()
    var showInventorySelectDialog by remember { mutableStateOf(false) }

    val listItems = remember(playerListedItems) {
        playerListedItems.map { item ->
            PlayerListItem(
                id = item.id,
                name = item.name,
                type = item.type,
                rarity = item.rarity,
                quantity = item.quantity,
                price = item.price,
                itemId = item.itemId
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GameColors.PageBackground)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "上架管理",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GameButton(
                            text = "上架",
                            onClick = { showInventorySelectDialog = true }
                        )
                        TextButton(onClick = onDismiss) {
                            Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                if (listItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无上架道具",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GameColors.Background)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "道具名称",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "数量",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "单价",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "操作",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(50.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(listItems) { item ->
                            ListedItemCard(
                                item = item,
                                onDelist = { viewModel.removePlayerListedItem(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInventorySelectDialog) {
        InventorySelectDialog(
            viewModel = viewModel,
            onDismiss = { showInventorySelectDialog = false }
        )
    }
}

@Composable
private fun ListedItemCard(
    item: PlayerListItem,
    onDelist: () -> Unit
) {
    val rarityColor = getRarityColor(item.rarity)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground, RoundedCornerShape(4.dp))
            .border(1.dp, rarityColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = rarityColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "${item.quantity}",
            fontSize = 11.sp,
            color = GameColors.TextPrimary,
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.Center
        )

        Text(
            text = "${item.price}",
            fontSize = 11.sp,
            color = GameColors.GoldDark,
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.Center
        )

        GameButton(
            text = "下架",
            onClick = onDelist,
            modifier = Modifier.width(50.dp).height(24.dp)
        )
    }
}

@Composable
fun InventorySelectDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val gameData by viewModel.gameData.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("装备", "功法", "丹药", "材料", "灵药", "种子")
    val selectedItems = remember { mutableStateMapOf<String, Int>() }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val sortedEquipment = remember(equipment) {
        equipment.sortedWith(compareByDescending<Equipment> { it.rarity }.thenBy { it.name })
    }
    val sortedManuals = remember(manuals) {
        manuals.sortedWith(compareByDescending<Manual> { it.rarity }.thenBy { it.name })
    }
    val sortedPills = remember(pills) {
        pills.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
    }
    val sortedMaterials = remember(materials) {
        materials.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
    }
    val sortedHerbs = remember(herbs) {
        herbs.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
    }
    val sortedSeeds = remember(seeds) {
        seeds.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GameColors.PageBackground)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择上架道具",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选: ${selectedItems.size}种",
                            fontSize = 11.sp,
                            color = GameColors.TextSecondary
                        )
                        GameButton(
                            text = "确认上架",
                            onClick = { showConfirmDialog = true },
                            enabled = selectedItems.isNotEmpty() && !isSubmitting
                        )
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = GameColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = GameColors.CardBackground,
                    contentColor = GameColors.Primary
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 13.sp) }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> InventorySelectGrid(
                            items = sortedEquipment,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无装备",
                            isStackable = false
                        )
                        1 -> InventorySelectGrid(
                            items = sortedManuals,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无功法",
                            isStackable = false
                        )
                        2 -> InventorySelectGrid(
                            items = sortedPills,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无丹药",
                            isStackable = true
                        )
                        3 -> InventorySelectGrid(
                            items = sortedMaterials,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无材料",
                            isStackable = true
                        )
                        4 -> InventorySelectGrid(
                            items = sortedHerbs,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无灵药",
                            isStackable = true
                        )
                        5 -> InventorySelectGrid(
                            items = sortedSeeds,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无种子",
                            isStackable = true
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog && selectedItems.isNotEmpty()) {
        ConfirmListingDialog(
            selectedCount = selectedItems.size,
            totalCount = selectedItems.values.sum(),
            onConfirm = {
                if (!isSubmitting) {
                    isSubmitting = true
                    val itemsToList = selectedItems.entries.map { it.key to it.value }
                    viewModel.listItemsToMerchant(itemsToList)
                    selectedItems.clear()
                    showConfirmDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showConfirmDialog = false }
        )
    }
}

@Composable
private fun <T> InventorySelectGrid(
    items: List<T>,
    selectedItems: MutableMap<String, Int>,
    emptyMessage: String,
    isStackable: Boolean
) {
    var showQuantityDialog by remember { mutableStateOf(false) }
    var pendingItemId by remember { mutableStateOf<String?>(null) }
    var pendingItemName by remember { mutableStateOf("") }
    var pendingMaxQuantity by remember { mutableStateOf(1) }

    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                val (id, name, rarity, quantity) = when (item) {
                    is Equipment -> Tuple4(item.id, item.name, item.rarity, 1)
                    is Manual -> Tuple4(item.id, item.name, item.rarity, 1)
                    is Pill -> Tuple4(item.id, item.name, item.rarity, item.quantity)
                    is Material -> Tuple4(item.id, item.name, item.rarity, item.quantity)
                    is Herb -> Tuple4(item.id, item.name, item.rarity, item.quantity)
                    is Seed -> Tuple4(item.id, item.name, item.rarity, item.quantity)
                    else -> Tuple4("", "", 1, 1)
                }

                val isSelected = selectedItems.containsKey(id)
                val selectedQuantity = selectedItems[id] ?: 0

                UnifiedItemCard(
                    data = ItemCardData(
                        id = id,
                        name = name,
                        rarity = rarity,
                        quantity = quantity
                    ),
                    isSelected = isSelected,
                    showViewButton = false,
                    onClick = {
                        if (isSelected) {
                            selectedItems.remove(id)
                        } else {
                            if (isStackable && quantity > 1) {
                                pendingItemId = id
                                pendingItemName = name
                                pendingMaxQuantity = quantity
                                showQuantityDialog = true
                            } else {
                                selectedItems[id] = 1
                            }
                        }
                    }
                )
            }
        }
    }

    if (showQuantityDialog && pendingItemId != null) {
        QuantitySelectDialog(
            itemName = pendingItemName,
            maxQuantity = pendingMaxQuantity,
            onConfirm = { qty ->
                selectedItems[pendingItemId!!] = qty
                showQuantityDialog = false
                pendingItemId = null
            },
            onDismiss = {
                showQuantityDialog = false
                pendingItemId = null
            }
        )
    }
}

@Composable
private fun QuantitySelectDialog(
    itemName: String,
    maxQuantity: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var quantity by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "选择上架数量",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column {
                Text(
                    text = itemName,
                    fontSize = 12.sp,
                    color = GameColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GameButton(
                        text = "-",
                        onClick = { if (quantity > 1) quantity-- },
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "$quantity / $maxQuantity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary
                    )
                    GameButton(
                        text = "+",
                        onClick = { if (quantity < maxQuantity) quantity++ },
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameButton(
                        text = "最小",
                        onClick = { quantity = 1 },
                        modifier = Modifier.weight(1f)
                    )
                    GameButton(
                        text = "一半",
                        onClick = { quantity = maxQuantity / 2 },
                        modifier = Modifier.weight(1f)
                    )
                    GameButton(
                        text = "全部",
                        onClick = { quantity = maxQuantity },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认",
                onClick = { onConfirm(quantity) }
            )
        },
        dismissButton = {
            GameButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun ConfirmListingDialog(
    selectedCount: Int,
    totalCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "确认上架",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column {
                Text(
                    text = "确定要上架 $selectedCount 种道具（共 $totalCount 件）吗？",
                    fontSize = 12.sp,
                    color = GameColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "弟子购买价格为原价的80%",
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary
                )
            }
        },
        confirmButton = {
            GameButton(
                text = "确认",
                onClick = onConfirm
            )
        },
        dismissButton = {
            GameButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}
