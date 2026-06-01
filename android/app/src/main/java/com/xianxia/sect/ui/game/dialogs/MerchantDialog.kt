package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameItem
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
fun MerchantDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val merchantItems = gameData?.travelingMerchantItems ?: emptyList()
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showListingDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(MerchantFilter.ALL) }

    val filteredItems = remember(merchantItems, selectedFilter) {
        val items = if (selectedFilter == MerchantFilter.ALL) merchantItems
        else merchantItems.filter { it.type == selectedFilter.typeValue }
        items.sortedWith(compareByDescending<MerchantItem> { it.rarity }.thenBy { it.name })
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "云游商人",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            Text(
                text = "灵石: ${gameData?.spiritStones ?: 0}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(end = 8.dp)
            )
            GameButton(
                text = "上架",
                onClick = { showListingDialog = true }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                                MerchantFilter.entries.forEach { filter ->
                                    ListingFilterButton(
                                        text = filter.displayName,
                                        selected = selectedFilter == filter,
                                        onClick = {
                                            selectedFilter = filter
                                            selectedItem = null
                                            buyQuantity = 1
                                        }
                                    )
                                }
                            }

                        if (filteredItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "该分类暂无物品",
                                    fontSize = 12.sp,
                                    color = GameColors.TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(60.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredItems, key = { it.id }) { item ->
                                UnifiedItemCard(
                                    data = ItemCardData(
                                        id = item.id,
                                        name = item.name,
                                        rarity = item.rarity,
                                        quantity = item.quantity,
                                        additionalInfo = "${item.price}灵石",
                                        grade = item.grade,
                                        isManual = item.type == "manual",
                                        isPill = item.type == "pill",
                                        isMaterial = item.type == "material"
                                    ),
                                    isSelected = selectedItem?.id == item.id,
                                    showViewButton = true,
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

    if (showDetailDialog) {
        selectedItem?.let { item ->
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
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
private fun PurchasePanel(
    item: MerchantItem?,
    quantity: Int,
    maxQuantity: Int,
    spiritStones: Long,
    onQuantityChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val totalPrice = (item?.price ?: 0L) * quantity
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
                        GameButton(
                            text = "取消",
                            onClick = onCancel
                        )

                        GameButton(
                            text = "确认购买",
                            onClick = onConfirm,
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

private fun getRarityColor(rarity: Int): Color = com.xianxia.sect.ui.theme.getRarityColor(rarity)

private fun getRarityName(rarity: Int): String = when (rarity) {
    1 -> "凡品"
    2 -> "灵品"
    3 -> "宝品"
    4 -> "玄品"
    5 -> "地品"
    6 -> "天品"
    else -> "凡品"
}

@Immutable
data class PlayerListItem(
    val id: String,
    val name: String,
    val type: String,
    val rarity: Int,
    val quantity: Int,
    val price: Long,
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "上架管理",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            GameButton(
                text = "上架",
                onClick = { showInventorySelectDialog = true }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

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
                        items(listItems, key = { it.id }) { item ->
                            ListedItemCard(
                                item = item,
                                onDelist = { viewModel.removePlayerListedItem(item.id) }
                            )
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
            onClick = onDelist
        )
    }
}

private fun <T : GameItem> filterAndSortItems(
    items: List<T>,
    listedItemIds: Set<String>
): List<T> {
    return items
        .filter { item ->
            item.id !in listedItemIds
        }
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

private enum class MerchantFilter(val displayName: String, val typeValue: String?) {
    ALL("全部", null),
    EQUIPMENT("装备", "equipment"),
    MANUAL("功法", "manual"),
    PILL("丹药", "pill"),
    MATERIAL("材料", "material"),
    HERB("灵草", "herb"),
    SEED("种子", "seed")
}

@Composable
fun InventorySelectDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val equipment by viewModel.equipmentStacks.collectAsState()
    val manuals by viewModel.manualStacks.collectAsState()
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择上架道具",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
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
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.bg_horizontal),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                onClick = { selectedFilter = ListingFilter.ALL }
                            )
                            ListingFilterButton(
                                text = ListingFilter.EQUIPMENT.displayName,
                                selected = selectedFilter == ListingFilter.EQUIPMENT,
                                onClick = { selectedFilter = ListingFilter.EQUIPMENT }
                            )
                            ListingFilterButton(
                                text = ListingFilter.PILL.displayName,
                                selected = selectedFilter == ListingFilter.PILL,
                                onClick = { selectedFilter = ListingFilter.PILL }
                            )
                            ListingFilterButton(
                                text = ListingFilter.MANUAL.displayName,
                                selected = selectedFilter == ListingFilter.MANUAL,
                                onClick = { selectedFilter = ListingFilter.MANUAL }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ListingFilterButton(
                                text = ListingFilter.HERB.displayName,
                                selected = selectedFilter == ListingFilter.HERB,
                                onClick = { selectedFilter = ListingFilter.HERB }
                            )
                            ListingFilterButton(
                                text = ListingFilter.SEED.displayName,
                                selected = selectedFilter == ListingFilter.SEED,
                                onClick = { selectedFilter = ListingFilter.SEED }
                            )
                            ListingFilterButton(
                                text = ListingFilter.MATERIAL.displayName,
                                selected = selectedFilter == ListingFilter.MATERIAL,
                                onClick = { selectedFilter = ListingFilter.MATERIAL }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
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
            columns = GridCells.Adaptive(60.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                val (id, name, description, rarity, quantity) = when (item) {
                    is EquipmentStack -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is ManualStack -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Pill -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Material -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Herb -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Seed -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    else -> Tuple5("", "", "", 1, 1)
                }
                val grade = (item as? Pill)?.grade?.displayName

                val isSelected = selectedItems.containsKey(id)

                UnifiedItemCard(
                    data = ItemCardData(
                        id = id,
                        name = name,
                        description = description,
                        rarity = rarity,
                        quantity = quantity,
                        grade = grade,
                        isManual = item is ManualStack,
                        isPill = item is Pill,
                        isMaterial = item is Material
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

    if (showDetailDialog) {
        selectedItem?.let { item ->
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
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
            .width(ButtonSizes.StandardWidth)
            .height(ButtonSizes.Large)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ui_button),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
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
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
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
                is EquipmentStack -> it.rarity
                is ManualStack -> it.rarity
                is Pill -> it.rarity
                is Material -> it.rarity
                is Herb -> it.rarity
                is Seed -> it.rarity
                else -> 1
            }
        }.thenBy {
            when (it) {
                is EquipmentStack -> it.name
                is ManualStack -> it.name
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
            columns = GridCells.Adaptive(60.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allItems) { item ->
                val (id, name, description, rarity, quantity) = when (item) {
                    is EquipmentStack -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is ManualStack -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Pill -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Material -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Herb -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    is Seed -> Tuple5(item.id, item.name, item.description, item.rarity, item.quantity)
                    else -> Tuple5("", "", "", 1, 1)
                }
                val grade = (item as? Pill)?.grade?.displayName

                val isSelected = selectedItems.containsKey(id)

                UnifiedItemCard(
                    data = ItemCardData(
                        id = id,
                        name = name,
                        description = description,
                        rarity = rarity,
                        quantity = quantity,
                        grade = grade,
                        isManual = item is ManualStack,
                        isPill = item is Pill,
                        isMaterial = item is Material
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

    if (showDetailDialog) {
        selectedItem?.let { item ->
            com.xianxia.sect.ui.game.components.ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
    }
}

@Composable
private fun ConfirmListingDialog(
    selectedCount: Int,
    totalCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "确认上架",
        mode = DialogMode.Half
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                    GameButton(
                        text = "确认",
                        onClick = onConfirm,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
        }
    }
}
