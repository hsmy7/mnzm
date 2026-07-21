package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GridRow
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
@Composable
internal fun BulkSellDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

    var selectedRarities by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    var isSelling by remember { mutableStateOf(false) }

    val rarityOptions = listOf(
        1 to "凡品",
        2 to "灵品",
        3 to "宝品",
        4 to "玄品",
        5 to "地品",
        6 to "天品"
    )

    val typeOptions = listOf(
        "ALL" to "全部",
        "EQUIPMENT" to "装备",
        "PILL" to "丹药",
        "MANUAL" to "功法",
        "HERB" to "草药",
        "SEED" to "种子",
        "MATERIAL" to "材料"
    )

    val finalTypes = remember(selectedTypes) {
        if (selectedTypes.contains("ALL")) {
            setOf("EQUIPMENT", "PILL", "MANUAL", "HERB", "SEED", "MATERIAL")
        } else {
            selectedTypes
        }
    }

    val sellableEquipment = remember(equipmentStacks, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("EQUIPMENT")) {
            equipmentStacks.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val sellableManuals = remember(manualStacks, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MANUAL")) {
            manualStacks.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val sellablePills = remember(pills, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("PILL")) {
            pills.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val sellableMaterials = remember(materials, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("MATERIAL")) {
            materials.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val sellableHerbs = remember(herbs, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("HERB")) {
            herbs.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val sellableSeeds = remember(seeds, selectedRarities, finalTypes) {
        if (selectedRarities.isNotEmpty() && finalTypes.contains("SEED")) {
            seeds.filter { selectedRarities.contains(it.rarity) && !it.isLocked }
        } else emptyList()
    }

    val totalItems = sellableEquipment.size + sellableManuals.size + sellablePills.size +
            sellableMaterials.size + sellableHerbs.size + sellableSeeds.size

    val totalValue = sellableEquipment.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableManuals.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellablePills.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableMaterials.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableHerbs.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) } +
            sellableSeeds.sumOf { GameConfig.Rarity.calculateSellPrice(it.basePrice, it.quantity) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "一键出售",
        mode = DialogMode.Half,
        showCloseButton = true,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "选择品阶（可多选）：",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        GridRow(items = rarityOptions, maxColumnWidth = 80.dp) { (rarity, name) ->
                            val isSelected = selectedRarities.contains(rarity)
                            GameButton(
                                text = if (isSelected) "✓ $name" else name,
                                onClick = {
                                    selectedRarities = if (isSelected) {
                                        selectedRarities - rarity
                                    } else {
                                        selectedRarities + rarity
                                    }
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                height = 34.dp,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "选择物品类型（可多选）：",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        GridRow(items = typeOptions, maxColumnWidth = 80.dp) { (type, name) ->
                            val isSelected = selectedTypes.contains(type)
                            GameButton(
                                text = if (isSelected) "✓ $name" else name,
                                onClick = {
                                    selectedTypes = if (isSelected) {
                                        selectedTypes - type
                                    } else {
                                        selectedTypes + type
                                    }
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                height = 34.dp,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                item {
                    if (totalItems > 0) {
                        Text(
                            text = "可出售物品（共${totalItems}件）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SellableEquipmentSection(sellableEquipment, onItemLongPress = { detailItem = it; showDetailDialog = true })
                            SellableManualSection(sellableManuals, onItemLongPress = { detailItem = it; showDetailDialog = true })
                            SellablePillSection(sellablePills, onItemLongPress = { detailItem = it; showDetailDialog = true })
                            SellableMaterialSection(sellableMaterials, onItemLongPress = { detailItem = it; showDetailDialog = true })
                            SellableHerbSection(sellableHerbs, onItemLongPress = { detailItem = it; showDetailDialog = true })
                            SellableSeedSection(sellableSeeds, onItemLongPress = { detailItem = it; showDetailDialog = true })
                        }
                    } else if (selectedRarities.isNotEmpty() && selectedTypes.isNotEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "没有符合条件的物品",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                        }
                    }
                }
            }

            // 底部按钮 — 固定显示，无需滚动
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                GameButton(
                    text = "确认出售",
                    onClick = { showConfirmDialog = true },
                    enabled = totalItems > 0,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showConfirmDialog) {
        StandardPromptDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = "确认出售",
            confirmLabel = "确认出售",
            onConfirm = {
                if (isSelling) return@StandardPromptDialog
                isSelling = true
                viewModel.bulkSellItems(selectedRarities, finalTypes)
                showConfirmDialog = false
                onDismiss()
            },
            dismissLabel = "取消",
            onDismiss = { showConfirmDialog = false }
        ) {
            Text(
                text = "确定要出售以下物品吗？",
                fontSize = 12.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "物品数量: ${totalItems} 件",
                fontSize = 12.sp,
                color = Color.Black
            )
            Text(
                text = "获得灵石: ${GameUtils.formatNumber(totalValue)}（原价80%）",
                fontSize = 12.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "此操作不可撤销！",
                fontSize = 11.sp,
                color = Color.Black
            )
        }
    }

    if (showDetailDialog && detailItem != null) {
        ItemDetailDialog(
            item = checkNotNull(detailItem) { "detailItem is null" },
            onDismiss = {
                showDetailDialog = false
                detailItem = null
            }
        )
    }
}
