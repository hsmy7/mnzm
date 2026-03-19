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
import androidx.compose.foundation.rememberScrollState
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
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameItem
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
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "道具名称",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "数量",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(60.dp),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "价格",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(60.dp),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "操作",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GameColors.TextSecondary,
                            modifier = Modifier.width(60.dp),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )

        Text(
            text = "${item.price}",
            fontSize = 11.sp,
            color = GameColors.GoldDark,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Center
        )

        GameButton(
            text = "下架",
            onClick = onDelist,
            modifier = Modifier.width(60.dp).height(32.dp)
        )
    }
}

private fun <T : GameItem> filterAndSortItems(
    items: List<T>,
    listedItemIds: Set<String>
): List<T> {
    return items
        .filter { it.id !in listedItemIds }
        .sortedWith(compareByDescending<T> { it.rarity }.thenBy { it.name })
}

private enum class ListingFilter(val displayName: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    MANUAL("功法"),
    PILL("丹药"),
    HERB("灵药"),
    SEED("种子"),
    MATERIAL("材料")
}

@Composable
fun InventorySelectDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    val gameData by viewModel.gameData.collectAsState()

    var selectedFilter by remember { mutableStateOf(ListingFilter.ALL) }
    val selectedItems = remember { mutableStateMapOf<String, Int>() }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val listedItemIds = remember(gameData?.playerListedItems) {
        gameData?.playerListedItems?.map { it.itemId }?.toSet() ?: emptySet()
    }

    val sortedEquipment = remember(equipment, listedItemIds) {
        filterAndSortItems(equipment, listedItemIds)
    }
    val sortedManuals = remember(manuals, listedItemIds) {
        filterAndSortItems(manuals, listedItemIds)
    }
    val sortedPills = remember(pills, listedItemIds) {
        filterAndSortItems(pills, listedItemIds)
    }
    val sortedMaterials = remember(materials, listedItemIds) {
        filterAndSortItems(materials, listedItemIds)
    }
    val sortedHerbs = remember(herbs, listedItemIds) {
        filterAndSortItems(herbs, listedItemIds)
    }
    val sortedSeeds = remember(seeds, listedItemIds) {
        filterAndSortItems(seeds, listedItemIds)
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GameColors.PageBackground)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ListingFilterButton(
                            text = ListingFilter.ALL.displayName,
                            selected = selectedFilter == ListingFilter.ALL,
                            onClick = { selectedFilter = ListingFilter.ALL },
                            modifier = Modifier.weight(1f)
                        )
                        ListingFilterButton(
                            text = ListingFilter.EQUIPMENT.displayName,
                            selected = selectedFilter == ListingFilter.EQUIPMENT,
                            onClick = { selectedFilter = ListingFilter.EQUIPMENT },
                            modifier = Modifier.weight(1f)
                        )
                        ListingFilterButton(
                            text = ListingFilter.PILL.displayName,
                            selected = selectedFilter == ListingFilter.PILL,
                            onClick = { selectedFilter = ListingFilter.PILL },
                            modifier = Modifier.weight(1f)
                        )
                        ListingFilterButton(
                            text = ListingFilter.MANUAL.displayName,
                            selected = selectedFilter == ListingFilter.MANUAL,
                            onClick = { selectedFilter = ListingFilter.MANUAL },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ListingFilterButton(
                            text = ListingFilter.HERB.displayName,
                            selected = selectedFilter == ListingFilter.HERB,
                            onClick = { selectedFilter = ListingFilter.HERB },
                            modifier = Modifier.weight(1f)
                        )
                        ListingFilterButton(
                            text = ListingFilter.SEED.displayName,
                            selected = selectedFilter == ListingFilter.SEED,
                            onClick = { selectedFilter = ListingFilter.SEED },
                            modifier = Modifier.weight(1f)
                        )
                        ListingFilterButton(
                            text = ListingFilter.MATERIAL.displayName,
                            selected = selectedFilter == ListingFilter.MATERIAL,
                            onClick = { selectedFilter = ListingFilter.MATERIAL },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedFilter) {
                        ListingFilter.ALL -> AllItemsSelectGrid(
                            equipment = sortedEquipment,
                            manuals = sortedManuals,
                            pills = sortedPills,
                            materials = sortedMaterials,
                            herbs = sortedHerbs,
                            seeds = sortedSeeds,
                            selectedItems = selectedItems
                        )
                        ListingFilter.EQUIPMENT -> InventorySelectGrid(
                            items = sortedEquipment,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无装备"
                        )
                        ListingFilter.MANUAL -> InventorySelectGrid(
                            items = sortedManuals,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无功法"
                        )
                        ListingFilter.PILL -> InventorySelectGrid(
                            items = sortedPills,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无丹药"
                        )
                        ListingFilter.MATERIAL -> InventorySelectGrid(
                            items = sortedMaterials,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无材料"
                        )
                        ListingFilter.HERB -> InventorySelectGrid(
                            items = sortedHerbs,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无灵药"
                        )
                        ListingFilter.SEED -> InventorySelectGrid(
                            items = sortedSeeds,
                            selectedItems = selectedItems,
                            emptyMessage = "暂无种子"
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
    emptyMessage: String
) {
    var selectedItem by remember { mutableStateOf<T?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

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
                val (id, name, description, rarity, quantity) = when (item) {
                    is Equipment -> Tuple5(item.id, item.name, item.description, item.rarity, 1)
                    is Manual -> Tuple5(item.id, item.name, item.description, item.rarity, 1)
                    is Pill -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Material -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Herb -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Seed -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    else -> Tuple5("", "", "", 1, 1)
                }

                val isSelected = selectedItems.containsKey(id)

                UnifiedItemCard(
                    data = ItemCardData(
                        id = id,
                        name = name,
                        description = description,
                        rarity = rarity,
                        quantity = quantity
                    ),
                    isSelected = isSelected,
                    showViewButton = true,
                    onClick = {
                        if (isSelected) {
                            selectedItems.remove(id)
                        } else {
                            selectedItems[id] = quantity
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

    if (showDetailDialog && selectedItem != null) {
        InventoryItemDetailDialog(
            item = selectedItem!!,
            onDismiss = { showDetailDialog = false }
        )
    }
}

private data class Tuple5<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

@Composable
private fun ListingFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.Black else GameColors.ButtonBackground)
            .border(1.dp, if (selected) Color.Black else GameColors.ButtonBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.Black
        )
    }
}

@Composable
private fun AllItemsSelectGrid(
    equipment: List<Equipment>,
    manuals: List<Manual>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedItems: MutableMap<String, Int>
) {
    var selectedItem by remember { mutableStateOf<Any?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

    val allItems = remember(equipment, manuals, pills, materials, herbs, seeds) {
        val items = mutableListOf<Any>()
        items.addAll(equipment)
        items.addAll(manuals)
        items.addAll(pills)
        items.addAll(materials)
        items.addAll(herbs)
        items.addAll(seeds)
        items.sortedWith(compareByDescending<Any> {
            when (it) {
                is Equipment -> it.rarity
                is Manual -> it.rarity
                is Pill -> it.rarity
                is Material -> it.rarity
                is Herb -> it.rarity
                is Seed -> it.rarity
                else -> 1
            }
        }.thenBy {
            when (it) {
                is Equipment -> it.name
                is Manual -> it.name
                is Pill -> it.name
                is Material -> it.name
                is Herb -> it.name
                is Seed -> it.name
                else -> ""
            }
        })
    }

    if (allItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无道具",
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
            items(allItems) { item ->
                val (id, name, description, rarity, quantity) = when (item) {
                    is Equipment -> Tuple5(item.id, item.name, item.description, item.rarity, 1)
                    is Manual -> Tuple5(item.id, item.name, item.description, item.rarity, 1)
                    is Pill -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Material -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Herb -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Seed -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    else -> Tuple5("", "", "", 1, 1)
                }

                val isSelected = selectedItems.containsKey(id)

                UnifiedItemCard(
                    data = ItemCardData(
                        id = id,
                        name = name,
                        description = description,
                        rarity = rarity,
                        quantity = quantity
                    ),
                    isSelected = isSelected,
                    showViewButton = true,
                    onClick = {
                        if (isSelected) {
                            selectedItems.remove(id)
                        } else {
                            selectedItems[id] = quantity
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

    if (showDetailDialog && selectedItem != null) {
        InventoryItemDetailDialog(
            item = selectedItem!!,
            onDismiss = { showDetailDialog = false }
        )
    }
}

@Composable
private fun InventoryItemDetailDialog(
    item: Any,
    onDismiss: () -> Unit
) {
    val name: String
    val rarity: Int
    val description: String
    val effects: List<String>

    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("部位: ${item.slot.displayName}")
                add("稀有度: ${getRarityName(item.rarity)}")
                if (item.minRealm < 9) {
                    add("需求境界: ${com.xianxia.sect.core.GameConfig.Realm.getName(item.minRealm)}")
                }
                if (item.nurtureLevel > 0) {
                    add("孕养等级: Lv.${item.nurtureLevel}")
                    val nurtureBonus = (item.totalMultiplier / com.xianxia.sect.core.GameConfig.Rarity.get(item.rarity).multiplier - 1.0) * 100
                    if (nurtureBonus > 0) {
                        add("  孕养加成: +${String.format("%.1f", nurtureBonus)}%")
                    }
                }
                add("")
                add("属性:")
                val finalStats = item.getFinalStats()
                val baseStats = item.stats
                if (finalStats.physicalAttack > 0) {
                    val bonus = finalStats.physicalAttack - baseStats.physicalAttack
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  物理攻击 +${finalStats.physicalAttack}$bonusText")
                }
                if (finalStats.magicAttack > 0) {
                    val bonus = finalStats.magicAttack - baseStats.magicAttack
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  法术攻击 +${finalStats.magicAttack}$bonusText")
                }
                if (finalStats.physicalDefense > 0) {
                    val bonus = finalStats.physicalDefense - baseStats.physicalDefense
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  物理防御 +${finalStats.physicalDefense}$bonusText")
                }
                if (finalStats.magicDefense > 0) {
                    val bonus = finalStats.magicDefense - baseStats.magicDefense
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  法术防御 +${finalStats.magicDefense}$bonusText")
                }
                if (finalStats.speed > 0) {
                    val bonus = finalStats.speed - baseStats.speed
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  速度 +${finalStats.speed}$bonusText")
                }
                if (finalStats.hp > 0) {
                    val bonus = finalStats.hp - baseStats.hp
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  生命 +${finalStats.hp}$bonusText")
                }
                if (finalStats.mp > 0) {
                    val bonus = finalStats.mp - baseStats.mp
                    val bonusText = if (bonus > 0) " (↑$bonus)" else ""
                    add("  灵力 +${finalStats.mp}$bonusText")
                }
                if (item.critChance > 0) add("  暴击率 +${String.format("%.1f", item.critChance * 100)}%")
            }
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型: ${item.type.displayName}")
                if (item.minRealm < 9) {
                    add("需求境界: ${com.xianxia.sect.core.GameConfig.Realm.getName(item.minRealm)}")
                }
                add("")
                val stats = item.stats
                if (stats.isNotEmpty()) {
                    add("属性加成:")
                    stats.forEach { (key, value) ->
                        val statName = when (key) {
                            "cultivationSpeedPercent" -> "修炼速度"
                            "physicalAttack" -> "物理攻击"
                            "magicAttack" -> "法术攻击"
                            "physicalDefense" -> "物理防御"
                            "magicDefense" -> "法术防御"
                            "hp" -> "生命"
                            "mp" -> "灵力"
                            "speed" -> "速度"
                            "critRate" -> "暴击率"
                            else -> key
                        }
                        if (key.contains("Percent")) {
                            add("  $statName +$value%")
                        } else {
                            add("  $statName +$value")
                        }
                    }
                }
                item.skill?.let { skill ->
                    add("")
                    add("技能: ${skill.name}")
                    if (skill.description.isNotEmpty()) {
                        add("  ${skill.description}")
                    }
                    add("  伤害类型: ${if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) "物理" else "法术"}")
                    add("  伤害倍率: ${String.format("%.1f", skill.damageMultiplier * 100)}%")
                    add("  连击次数: ${skill.hits}")
                    add("  冷却回合: ${skill.cooldown}")
                    add("  灵力消耗: ${skill.mpCost}")
                }
            }
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型: ${item.category.displayName}")
                add("数量: ${item.quantity}")
                add("")
                add("效果:")
                when (item.category) {
                    com.xianxia.sect.core.model.PillCategory.BREAKTHROUGH -> {
                        if (item.breakthroughChance > 0) {
                            add("  突破概率 +${String.format("%.1f", item.breakthroughChance * 100)}%")
                        }
                        if (item.targetRealm > 0) {
                            add("  目标境界: ${com.xianxia.sect.core.GameConfig.Realm.getName(item.targetRealm)}")
                        }
                        if (item.isAscension) {
                            add("  可用于渡劫")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.CULTIVATION -> {
                        if (item.cultivationPercent > 0) {
                            add("  修为 +${String.format("%.1f", item.cultivationPercent * 100)}%")
                        }
                        if (item.cultivationSpeed > 1.0) {
                            add("  修炼速度 x${item.cultivationSpeed}")
                        }
                        if (item.skillExpPercent > 0) {
                            add("  功法熟练度 +${String.format("%.1f", item.skillExpPercent * 100)}%")
                        }
                        if (item.extendLife > 0) {
                            add("  延寿 ${item.extendLife} 年")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.BATTLE_PHYSICAL, com.xianxia.sect.core.model.PillCategory.BATTLE_MAGIC, com.xianxia.sect.core.model.PillCategory.BATTLE_STATUS -> {
                        if (item.physicalAttackPercent > 0) add("  物理攻击 +${String.format("%.1f", item.physicalAttackPercent * 100)}%")
                        if (item.magicAttackPercent > 0) add("  法术攻击 +${String.format("%.1f", item.magicAttackPercent * 100)}%")
                        if (item.physicalDefensePercent > 0) add("  物理防御 +${String.format("%.1f", item.physicalDefensePercent * 100)}%")
                        if (item.magicDefensePercent > 0) add("  法术防御 +${String.format("%.1f", item.magicDefensePercent * 100)}%")
                        if (item.hpPercent > 0) add("  生命 +${String.format("%.1f", item.hpPercent * 100)}%")
                        if (item.mpPercent > 0) add("  灵力 +${String.format("%.1f", item.mpPercent * 100)}%")
                        if (item.speedPercent > 0) add("  速度 +${String.format("%.1f", item.speedPercent * 100)}%")
                        if (item.battleCount > 0) add("  持续 ${item.battleCount} 场战斗")
                    }
                    com.xianxia.sect.core.model.PillCategory.HEALING -> {
                        if (item.heal > 0) add("  恢复生命 ${item.heal}")
                        if (item.healPercent > 0) add("  恢复生命 ${String.format("%.1f", item.healPercent * 100)}%")
                        if (item.healMaxHpPercent > 0) add("  恢复生命 ${String.format("%.1f", item.healMaxHpPercent * 100)}% 最大生命")
                        if (item.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${String.format("%.1f", item.mpRecoverMaxMpPercent * 100)}% 最大灵力")
                        if (item.revive) add("  可复活弟子")
                        if (item.clearAll) add("  清除所有负面状态")
                    }
                }
                if (item.duration > 0 && !item.category.isBattlePill) {
                    add("  持续 ${item.duration} 月")
                }
            }
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型: ${item.category.displayName}")
                add("数量: ${item.quantity}")
                val forgeRecipes = com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipesByMaterial(item.id)
                if (forgeRecipes.isNotEmpty()) {
                    add("")
                    add("可用于炼器:")
                    forgeRecipes.take(5).forEach { recipe ->
                        add("  · ${recipe.name}")
                    }
                    if (forgeRecipes.size > 5) {
                        add("  · 等${forgeRecipes.size}种装备")
                    }
                }
            }
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                if (item.category.isNotEmpty()) {
                    add("类型: ${item.category}")
                }
                add("数量: ${item.quantity}")
                val pillRecipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(item.id)
                if (pillRecipes.isNotEmpty()) {
                    add("")
                    add("可用于炼丹:")
                    pillRecipes.take(5).forEach { recipe ->
                        add("  · ${recipe.name}")
                    }
                    if (pillRecipes.size > 5) {
                        add("  · 等${pillRecipes.size}种丹药")
                    }
                }
            }
        }
        is Seed -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型: 种子")
                add("生长时间: ${item.growTime}个月")
                add("收获数量: ${item.yield}")
                add("数量: ${item.quantity}")
                val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeed(item.id)
                if (herb != null) {
                    add("")
                    add("长成后:")
                    add("  · ${herb.name}")
                    add("  · ${herb.description}")
                    val pillRecipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(herb.id)
                    if (pillRecipes.isNotEmpty()) {
                        add("")
                        add("可用于炼丹:")
                        pillRecipes.take(3).forEach { recipe ->
                            add("  · ${recipe.name}")
                        }
                        if (pillRecipes.size > 3) {
                            add("  · 等${pillRecipes.size}种丹药")
                        }
                    }
                } else {
                    val herbName = com.xianxia.sect.core.data.HerbDatabase.getHerbNameFromSeedName(item.name)
                    add("")
                    add("长成后: $herbName")
                }
            }
        }
        else -> {
            name = "未知物品"
            rarity = 1
            description = ""
            effects = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = getRarityColor(rarity)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = getRarityName(rarity),
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = GameColors.Background, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                effects.forEach { effect ->
                    if (effect.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Text(
                            text = effect,
                            fontSize = 12.sp,
                            color = if (effect.startsWith("属性") || effect.startsWith("效果") || effect.startsWith("技能")) {
                                GameColors.Primary
                            } else {
                                GameColors.TextPrimary
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = GameColors.Background, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    )
}

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
