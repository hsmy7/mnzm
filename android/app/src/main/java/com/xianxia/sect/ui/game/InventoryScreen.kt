package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun InventoryDialog(
    equipment: List<Equipment>,
    manuals: List<Manual>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("装备", "功法", "丹药", "材料", "灵药", "种子")
    var showBulkSellDialog by remember { mutableStateOf(false) }

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
                InventoryHeader(
                    onDismiss = onDismiss,
                    onBulkSellClick = { showBulkSellDialog = true }
                )

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

                when (selectedTab) {
                    0 -> InventoryGrid(
                        items = sortedEquipment,
                        emptyMessage = "暂无装备"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                    1 -> InventoryGrid(
                        items = sortedManuals,
                        emptyMessage = "暂无功法"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity,
                                quantity = item.quantity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                    2 -> InventoryGrid(
                        items = sortedPills,
                        emptyMessage = "暂无丹药"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity,
                                quantity = item.quantity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                    3 -> InventoryGrid(
                        items = sortedMaterials,
                        emptyMessage = "暂无材料"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity,
                                quantity = item.quantity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                    4 -> InventoryGrid(
                        items = sortedHerbs,
                        emptyMessage = "暂无灵药"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity,
                                quantity = item.quantity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                    5 -> InventoryGrid(
                        items = sortedSeeds,
                        emptyMessage = "暂无种子"
                    ) { item, isSelected, onSelect, onViewDetail ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                description = item.description,
                                rarity = item.rarity,
                                quantity = item.quantity
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            onClick = onSelect,
                            onViewDetail = onViewDetail
                        )
                    }
                }

                // 一键出售对话框
                if (showBulkSellDialog) {
                    BulkSellDialog(
                        equipment = sortedEquipment,
                        manuals = sortedManuals,
                        pills = sortedPills,
                        materials = sortedMaterials,
                        herbs = sortedHerbs,
                        seeds = sortedSeeds,
                        viewModel = viewModel,
                        onDismiss = { showBulkSellDialog = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun InventoryHeader(
    onDismiss: () -> Unit,
    onBulkSellClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "背包",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameButton(
                text = "一键出售",
                onClick = onBulkSellClick
            )
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun <T> InventoryGrid(
    items: List<T>,
    emptyMessage: String,
    itemContent: @Composable (T, Boolean, () -> Unit, () -> Unit) -> Unit
) {
    var selectedItem by remember { mutableStateOf<T?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }

    if (items.isEmpty()) {
        EmptyListMessage(emptyMessage)
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
                val isSelected = selectedItem == item
                itemContent(
                    item,
                    isSelected,
                    {
                        // 点击卡片选中/取消选中
                        selectedItem = if (isSelected) null else item
                    },
                    {
                        // 点击查看按钮显示详情
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

// 道具类型枚举
internal enum class ItemType(val displayName: String) {
    EQUIPMENT("装备"),
    MANUAL("功法"),
    PILL("丹药"),
    MATERIAL("材料"),
    HERB("灵药"),
    SEED("种子")
}

// 品阶配置
internal val rarityOptions = listOf(
    1 to "凡品",
    2 to "灵品",
    3 to "宝品",
    4 to "玄品",
    5 to "地品",
    6 to "天品"
)

// 可出售道具卡片
@Composable
private fun SellableItemCard(item: Any) {
    val name: String
    val rarity: Int
    val type: String
    val quantity: Int
    val price: Int

    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            type = "装备"
            quantity = 1
            price = (item.basePrice * 0.8).toInt()
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            type = "功法"
            quantity = 1
            price = (item.basePrice * 0.8).toInt()
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            type = "丹药"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            type = "材料"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            type = "灵药"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        else -> {
            name = "未知物品"
            rarity = 1
            type = "未知"
            quantity = 1
            price = 0
        }
    }

    val rarityColor = getRarityColor(rarity)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(1.dp, rarityColor, RoundedCornerShape(6.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = type,
                fontSize = 8.sp,
                color = GameColors.TextSecondary
            )
            if (quantity > 1) {
                Text(
                    text = "x$quantity",
                    fontSize = 9.sp,
                    color = GameColors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$price",
                fontSize = 9.sp,
                color = GameColors.Success,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// 可出售道具列表项
@Composable
private fun SellableItemRow(item: Any) {
    val name: String
    val rarity: Int
    val type: String
    val quantity: Int
    val price: Int

    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            type = "装备"
            quantity = 1
            price = (item.basePrice * 0.8).toInt()
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            type = "功法"
            quantity = 1
            price = (item.basePrice * 0.8).toInt()
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            type = "丹药"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            type = "材料"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            type = "灵药"
            quantity = item.quantity
            price = (item.basePrice * item.quantity * 0.8).toInt()
        }
        else -> {
            name = "未知物品"
            rarity = 1
            type = "未知"
            quantity = 1
            price = 0
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.Background, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = type,
                fontSize = 10.sp,
                color = GameColors.TextSecondary
            )
            Text(
                text = name,
                fontSize = 12.sp,
                color = getRarityColor(rarity),
                fontWeight = FontWeight.Medium
            )
            if (quantity > 1) {
                Text(
                    text = "x$quantity",
                    fontSize = 11.sp,
                    color = GameColors.TextSecondary
                )
            }
        }
        Text(
            text = "$price 灵石",
            fontSize = 11.sp,
            color = GameColors.Success
        )
    }
}

@Composable
internal fun BulkSellDialog(
    equipment: List<Equipment>,
    manuals: List<Manual>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedRarities by remember { mutableStateOf(setOf<Int>()) }
    var selectedTypes by remember { mutableStateOf(setOf<ItemType>()) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    // 计算可出售物品和总价
    val (sellableItems, totalValue) = remember(
        equipment, manuals, pills, materials, herbs, seeds,
        selectedRarities, selectedTypes
    ) {
        calculateBulkSellValue(
            equipment, manuals, pills, materials, herbs, seeds,
            selectedRarities, selectedTypes
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题
                Text(
                    text = "一键出售",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 品阶选择
                Text(
                    text = "选择品阶（可多选）：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rarityOptions.toList().chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { (rarity, name) ->
                                val isSelected = selectedRarities.contains(rarity)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedRarities = if (isSelected) {
                                            selectedRarities - rarity
                                        } else {
                                            selectedRarities + rarity
                                        }
                                    },
                                    label = { Text(name, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.Primary,
                                        selectedLabelColor = Color.White
                            )
                                )
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 道具类型选择
                Text(
                    text = "选择道具类型（可多选）：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ItemType.values().toList().chunked(4).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { type ->
                                val isSelected = selectedTypes.contains(type)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedTypes = if (isSelected) {
                                            selectedTypes - type
                                        } else {
                                            selectedTypes + type
                                        }
                                    },
                                    label = { Text(type.displayName, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GameColors.Primary,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = GameColors.Background)
                Spacer(modifier = Modifier.height(16.dp))

                // 预览信息
                Text(
                    text = "可出售物品: ${sellableItems.size} 件",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "预计获得: ${totalValue} 灵石（原价80%）",
                    fontSize = 12.sp,
                    color = GameColors.Success
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 可出售道具列表
                if (sellableItems.isNotEmpty()) {
                    Text(
                        text = "待出售道具列表：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sellableItems) { item ->
                            SellableItemCard(item = item)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    GameButton(
                        text = "一键出售",
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = sellableItems.isNotEmpty()
                    )
                }
            }
        }
    }

    // 二次确认对话框
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认出售",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Column {
                    Text(
                        text = "确定要出售以下物品吗？",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "物品数量: ${sellableItems.size} 件",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                    Text(
                        text = "获得灵石: $totalValue（原价80%）",
                        fontSize = 12.sp,
                        color = GameColors.Success
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "此操作不可撤销！",
                        fontSize = 11.sp,
                        color = GameColors.Error
                    )
                }
            },
            confirmButton = {
                GameButton(
                    text = "确认出售",
                    onClick = {
                        viewModel.bulkSellItems(
                            selectedRarities = selectedRarities,
                            selectedTypes = selectedTypes.map { it.name }.toSet()
                        )
                        showConfirmDialog = false
                        onDismiss()
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showConfirmDialog = false }
                )
            }
        )
    }
}

// 计算一键出售的物品和价值
internal fun calculateBulkSellValue(
    equipment: List<Equipment>,
    manuals: List<Manual>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedRarities: Set<Int>,
    selectedTypes: Set<ItemType>
): Pair<List<Any>, Int> {
    if (selectedRarities.isEmpty() || selectedTypes.isEmpty()) {
        return emptyList<Any>() to 0
    }

    val items = mutableListOf<Any>()
    var totalValue = 0

    // 装备
    if (selectedTypes.contains(ItemType.EQUIPMENT)) {
        equipment.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * 0.8).toInt()
        }
    }

    // 功法
    if (selectedTypes.contains(ItemType.MANUAL)) {
        manuals.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * 0.8).toInt()
        }
    }

    // 丹药
    if (selectedTypes.contains(ItemType.PILL)) {
        pills.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * it.quantity * 0.8).toInt()
        }
    }

    // 材料
    if (selectedTypes.contains(ItemType.MATERIAL)) {
        materials.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * it.quantity * 0.8).toInt()
        }
    }

    // 灵药
    if (selectedTypes.contains(ItemType.HERB)) {
        herbs.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * it.quantity * 0.8).toInt()
        }
    }

    // 种子
    if (selectedTypes.contains(ItemType.SEED)) {
        seeds.filter { selectedRarities.contains(it.rarity) }.forEach {
            items.add(it)
            totalValue += (it.basePrice * it.quantity * 0.8).toInt()
        }
    }

    return items to totalValue
}

// FlowRow 组件（简化版）
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val hGapPx = 8.dp.roundToPx()
        val vGapPx = 8.dp.roundToPx()

        val rows = mutableListOf<List<Placeable>>()
        val rowWidths = mutableListOf<Int>()
        val rowHeights = mutableListOf<Int>()

        val row = mutableListOf<Placeable>()
        var rowWidth = 0
        var rowHeight = 0

        measurables.forEach { measurable: androidx.compose.ui.layout.Measurable ->
            val placeable = measurable.measure(constraints)

            if (row.isNotEmpty() && rowWidth + hGapPx + placeable.width > constraints.maxWidth) {
                rows.add(row.toList())
                rowWidths.add(rowWidth)
                rowHeights.add(rowHeight)
                row.clear()
                rowWidth = 0
                rowHeight = 0
            }

            if (row.isNotEmpty()) {
                rowWidth += hGapPx
            }
            row.add(placeable)
            rowWidth += placeable.width
            rowHeight = maxOf(rowHeight, placeable.height)
        }

        if (row.isNotEmpty()) {
            rows.add(row.toList())
            rowWidths.add(rowWidth)
            rowHeights.add(rowHeight)
        }

        val width = constraints.maxWidth
        val height = rowHeights.sum() + (rowHeights.size - 1).coerceAtLeast(0) * vGapPx

        layout(width, height) {
            var y = 0
            rows.forEachIndexed { index: Int, rowPlaceables: List<Placeable> ->
                var x = when (horizontalArrangement) {
                    Arrangement.Start, Arrangement.SpaceBetween -> 0
                    Arrangement.End -> width - rowWidths[index]
                    Arrangement.Center -> (width - rowWidths[index]) / 2
                    else -> 0
                }
                rowPlaceables.forEach { placeable: Placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + hGapPx
                }
                y += rowHeights[index] + vGapPx
            }
        }
    }
}
