package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.theme.GameColors
import kotlin.math.ceil

/**
 * 按种植状态分组的灵田数据。
 * seedId 为空表示未种植分组，非空表示同一种种子种植的灵田集合。
 */
private data class FieldGroup(
    val seedId: String,
    val seedName: String,
    val seedRarity: Int,
    val fields: List<GridBuildingData>,
    val plantEntries: List<SpiritFieldPlant>
)

/**
 * 灵田种植面板 — 全屏对话框。
 *
 * 左侧列出仓库所有种子（翻页网格），右侧按种植状态分组显示灵田，
 * 底部提供种植数量选择和「种植」按钮。
 *
 * @param seeds 仓库中所有种子（含 quantity == 0 的条目）
 * @param gameData 当前存档完整数据
 * @param viewModel GameViewModel（需提供 plantOnSpiritField / removePlantFromSpiritField 方法）
 * @param activeSectId 当前活跃宗门 ID
 * @param onDismiss 关闭回调
 */
@Composable
fun PlantingDialog(
    seeds: List<Seed>,
    gameData: GameData,
    viewModel: GameViewModel,
    activeSectId: String,
    onDismiss: () -> Unit
) {
    // ── 本地状态 ───────────────────────────────────────────
    var selectedSeedId by remember { mutableStateOf<String?>(null) }
    var seedPage by remember { mutableIntStateOf(1) }
    var plantQuantity by remember { mutableIntStateOf(1) }
    var isEditingQty by remember { mutableStateOf(false) }
    var qtyInput by remember { mutableStateOf("1") }
    var removeDialogGroup by remember { mutableStateOf<FieldGroup?>(null) }
    var removeQuantity by remember { mutableIntStateOf(1) }
    var isEditingRemoveQty by remember { mutableStateOf(false) }
    var removeQtyInput by remember { mutableStateOf("1") }
    var showSeedDetail by remember { mutableStateOf(false) }
    var detailSeed by remember { mutableStateOf<Seed?>(null) }

    // ── 派生数据 ───────────────────────────────────────────
    // 可用种子：排序 稀有度降 → 名称升
    val activeSeeds = remember(seeds) {
        seeds
            .filter { it.quantity > 0 }
            .sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
    }

    // 当前宗门的灵田
    val spiritFields = remember(gameData.placedBuildings, activeSectId) {
        gameData.placedBuildings.filter { field ->
            (field.buildingId == "spirit_field" || field.displayName == "灵田")
                && field.sectId == activeSectId
        }
    }

    // 当前选中的种子对象
    val selectedSeed = remember(selectedSeedId, activeSeeds) {
        activeSeeds.find { it.id == selectedSeedId }
    }

    // 按种植状态分组：未种植 → 同种种子分组
    val fieldGroups = remember(spiritFields, gameData.spiritFieldPlants, activeSeeds) {
        val plantsByBuilding = gameData.spiritFieldPlants.associateBy { it.buildingInstanceId }
        val seedMap = activeSeeds.associateBy { it.id }

        val unplanted = spiritFields.filter { field ->
            val plant = plantsByBuilding[field.instanceId]
            plant == null || plant.seedId.isEmpty()
        }
        val planted = spiritFields.filterNot { field ->
            val plant = plantsByBuilding[field.instanceId]
            plant == null || plant.seedId.isEmpty()
        }
        val plantedBySeedId = planted.groupBy { field ->
            plantsByBuilding.getValue(field.instanceId).seedId
        }

        buildList {
            // 未种植分组排在最前
            if (unplanted.isNotEmpty()) {
                add(
                    FieldGroup(
                        seedId = "",
                        seedName = "未种植",
                        seedRarity = 0,
                        fields = unplanted,
                        plantEntries = emptyList()
                    )
                )
            }
            // 已种植，按 seedId 分组
            for ((sid, fds) in plantedBySeedId) {
                val entry = plantsByBuilding[fds.first().instanceId] ?: continue
                val rarity = seedMap[sid]?.rarity ?: 1
                add(
                    FieldGroup(
                        seedId = sid,
                        seedName = entry.seedName,
                        seedRarity = rarity,
                        fields = fds,
                        plantEntries = fds.mapNotNull { plantsByBuilding[it.instanceId] }
                    )
                )
            }
        }
    }

    val unplantedCount = fieldGroups.firstOrNull { it.seedId.isEmpty() }?.fields?.size ?: 0

    // ── 分页（动态计算每页数量） ─────────────────────────────
    var dynPageSize by remember { mutableIntStateOf(12) }
    val totalPages = maxOf(1, ceil(activeSeeds.size.toDouble() / dynPageSize.coerceAtLeast(1)).toInt())
    val currentPage = seedPage.coerceIn(1, totalPages)
    val pagedSeeds = remember(currentPage, activeSeeds, dynPageSize) {
        activeSeeds.drop((currentPage - 1) * dynPageSize).take(dynPageSize)
    }

    // ── 主体布局 ───────────────────────────────────────────
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏：种植标题 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "种植",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CloseButton(onClick = onDismiss)
                }

                // 主区域：左（60%）| 分割线 | 右（40%）
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

                // ═════════════════ 左侧：种子网格 ════════════
                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 4.dp, end = 8.dp)
                ) {
                    Spacer(modifier = Modifier.height(4.dp))

                    if (activeSeeds.isEmpty()) {
                        // 空状态
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("仓库中没有种子", fontSize = 12.sp, color = Color.Black)
                        }
                    } else {
                        // 种子网格 — 用 BoxWithConstraints 动态计算每页行列数
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            val itemW = 60.dp
                            val gap = 6.dp
                            val cols = maxOf(1, ((maxWidth - gap) / (itemW + gap)).toInt())
                            val rows = maxOf(2, ((maxHeight - gap) / (itemW + 20.dp + gap)).toInt())
                            val calcSize = cols * rows
                            if (calcSize != dynPageSize && calcSize > 0) {
                                LaunchedEffect(Unit) { dynPageSize = calcSize }
                            }
                            LazyVerticalGrid(
                            columns = GridCells.Adaptive(60.dp),
                            modifier = Modifier
                                .fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(2.dp)
                        ) {
                            items(pagedSeeds, key = { it.id }) { seed ->
                                UnifiedItemCard(
                                    data = ItemCardData(
                                        id = seed.id,
                                        name = seed.name,
                                        description = seed.description,
                                        rarity = seed.rarity,
                                        quantity = seed.quantity
                                    ),
                                    isSelected = seed.id == selectedSeedId,
                                    onClick = {
                                        selectedSeedId =
                                            if (selectedSeedId == seed.id) null else seed.id
                                        plantQuantity = 1
                                        qtyInput = "1"
                                    },
                                    onLongPress = {
                                        detailSeed = seed
                                        showSeedDetail = true
                                    }
                                )
                            }
                        }
                        } // BoxWithConstraints

                        // 分页
                        Spacer(modifier = Modifier.height(4.dp))
                        PlantingPagination(
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onFirstPage = {
                                if (currentPage > 1) {
                                    seedPage = 1
                                }
                            },
                            onPreviousPage = {
                                if (currentPage > 1) {
                                    seedPage = currentPage - 1
                                }
                            },
                            onNextPage = {
                                if (currentPage < totalPages) {
                                    seedPage = currentPage + 1
                                }
                            },
                            onLastPage = {
                                if (currentPage < totalPages) {
                                    seedPage = totalPages
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ═════════════════ 竖直分割线 ════════════════
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFBDBDBD))
                )

                // ═════════════════ 右侧：种子卡片列表 ════════════
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                ) {
                    // 灵田统计行固定第一行
                    val totalFields = fieldGroups.sumOf { it.fields.size }
                    val plantedFields = fieldGroups.filter { it.seedId.isNotEmpty() }.sumOf { it.fields.size }
                    val unplantedFields = fieldGroups.find { it.seedId.isEmpty() }?.fields?.size ?: 0
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("灵田", "总数", "已种植", "未种植").forEach { label ->
                                Text(label, fontSize = 10.sp, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("灵田", "$totalFields", "$plantedFields", "$unplantedFields").forEach { value ->
                                Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(thickness = 1.dp, color = Color(0xFFBDBDBD))
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 已种植种子卡片列表（可滚动）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val plantedGroups = fieldGroups.filter { g -> g.seedId.isNotEmpty() }
                        if (plantedGroups.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无种植", fontSize = 12.sp, color = Color.Black)
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                plantedGroups.forEach { group ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(72.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        val plantedSeed = activeSeeds.find { it.id == group.seedId }
                                            ?: seeds.find { it.id == group.seedId }
                                        if (plantedSeed != null) {
                                            UnifiedItemCard(
                                                data = ItemCardData(
                                                    id = plantedSeed.id,
                                                    name = plantedSeed.name,
                                                    description = plantedSeed.description,
                                                    rarity = plantedSeed.rarity,
                                                    quantity = plantedSeed.quantity
                                                ),
                                                isSelected = false,
                                                onClick = { selectedSeedId = plantedSeed.id },
                                                onLongPress = {
                                                    detailSeed = plantedSeed
                                                    showSeedDetail = true
                                                }
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.size(60.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFFE0E0E0))
                                                    .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(group.seedName, fontSize = 8.sp, color = Color.Black, textAlign = TextAlign.Center)
                                            }
                                        }
                                        Text(
                                            text = "${group.fields.size}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                        GameButton(
                                            text = "铲除",
                                            onClick = {
                                                removeQuantity = 1
                                                removeQtyInput = "1"
                                                removeDialogGroup = group
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 底部操作栏：数量选择 + 种植按钮
                    if (spiritFields.isNotEmpty()) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = Color(0xFFBDBDBD)
                        )
                        val minInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val decInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val incInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val maxInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最小",
                                fontSize = 12.sp,
                                color = Color.Black,
                                modifier = Modifier.clickable(interactionSource = minInteraction, indication = null) {
                                    plantQuantity = 1
                                    qtyInput = "1"
                                }
                            )
                            Text(
                                text = "-1",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier
                                    .alpha(if (plantQuantity > 1) 1f else 0.3f)
                                    .clickable(
                                        interactionSource = decInteraction,
                                        indication = null,
                                        enabled = plantQuantity > 1
                                    ) {
                                        plantQuantity--
                                        qtyInput = plantQuantity.toString()
                                    }
                            )
                            // 数量显示 — 始终可见的输入框
                            val displayText = qtyInput.ifEmpty { plantQuantity.toString() }
                            BasicTextField(
                                value = displayText,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    val num = filtered.toIntOrNull()
                                    qtyInput = if (num != null) {
                                        num.coerceIn(1, unplantedCount.coerceAtLeast(1)).toString()
                                    } else {
                                        filtered
                                    }
                                    if (num != null) plantQuantity = num.coerceIn(1, unplantedCount.coerceAtLeast(1))
                                },
                                modifier = Modifier.width(40.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = Color.Black, textAlign = TextAlign.Center
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text(
                                text = "+1",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier
                                    .alpha(if (plantQuantity < unplantedCount) 1f else 0.3f)
                                    .clickable(
                                        interactionSource = incInteraction,
                                        indication = null,
                                        enabled = plantQuantity < unplantedCount
                                    ) {
                                        plantQuantity++
                                        qtyInput = plantQuantity.toString()
                                    }
                            )
                            Text(
                                text = "最大",
                                fontSize = 12.sp,
                                color = Color.Black,
                                modifier = Modifier.clickable(interactionSource = maxInteraction, indication = null) {
                                    plantQuantity = unplantedCount.coerceAtLeast(1)
                                    qtyInput = plantQuantity.toString()
                                }
                            )
                            GameButton(
                                text = "种植",
                                enabled = selectedSeed != null && unplantedCount > 0,
                                onClick = {
                                    val toPlant = plantQuantity.coerceAtMost(unplantedCount)
                                    val unplantedFields =
                                        fieldGroups.firstOrNull { it.seedId.isEmpty() }?.fields
                                            ?: emptyList()
                                    // 批量收集instanceId，一次性种植
                                    val ids = unplantedFields.take(toPlant).map { it.instanceId }
                                    if (ids.isNotEmpty() && selectedSeed != null) {
                                        viewModel.planting.plantOnSpiritFields(ids, selectedSeed.id, activeSectId)
                                    }
                                    selectedSeedId = null
                                    plantQuantity = 1
                                    qtyInput = "1"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSeedDetail && detailSeed != null) {
        ItemDetailDialog(
            item = detailSeed!!,
            onDismiss = {
                showSeedDetail = false
                detailSeed = null
            }
        )
    }

    // ═════════════════ 铲除确认弹窗 ════════════════
    removeDialogGroup?.let { group ->
        StandardPromptDialog(
            onDismissRequest = { removeDialogGroup = null },
            title = "确认铲除",
            confirmLabel = "确认",
            dismissLabel = "取消",
            onConfirm = {
                val toRemove = removeQuantity.coerceAtMost(group.plantEntries.size)
                for (i in 0 until toRemove) {
                    viewModel.planting.removePlantFromSpiritField(
                        group.plantEntries[i].buildingInstanceId
                    )
                }
                removeDialogGroup = null
                removeQuantity = 1
                removeQtyInput = "1"
            },
            onDismiss = { removeDialogGroup = null }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "当前分组：${group.seedName}（${group.fields.size}块）",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("铲除数量:", fontSize = 12.sp, color = Color.Black)
                    val ri = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Text(
                        text = "-1", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.alpha(if (removeQuantity > 1) 1f else 0.3f)
                            .clickable(interactionSource = ri, indication = null, enabled = removeQuantity > 1) {
                                removeQuantity--; removeQtyInput = removeQuantity.toString()
                            }
                    )
                    Text(
                        text = "$removeQuantity", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black
                    )
                    val ri2 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val maxQty = group.fields.size.coerceAtLeast(1)
                    Text(
                        text = "+1", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.alpha(if (removeQuantity < maxQty) 1f else 0.3f)
                            .clickable(interactionSource = ri2, indication = null, enabled = removeQuantity < maxQty) {
                                removeQuantity++; removeQtyInput = removeQuantity.toString()
                            }
                    )
                }
            }
        }
    }
    }
}

// ────────────────────────────────────────────────────────────
//  辅助组件
// ────────────────────────────────────────────────────────────

/**
 * 种子选择区底部分页控件。
 * 提供 << < 第 X/Y 页 > >> 四个按钮。
 */
@Composable
private fun PlantingPagination(
    currentPage: Int,
    totalPages: Int,
    onFirstPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onLastPage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // << 首页
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (currentPage > 1) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = currentPage > 1) { onFirstPage() },
            contentAlignment = Alignment.Center
        ) {
            Text("<<", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // < 上一页
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (currentPage > 1) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = currentPage > 1) { onPreviousPage() },
            contentAlignment = Alignment.Center
        ) {
            Text("<", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 页码
        Text(
            text = "第 $currentPage/$totalPages 页",
            fontSize = 12.sp,
            color = Color.Black,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.width(12.dp))

        // > 下一页
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (currentPage < totalPages) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = currentPage < totalPages) { onNextPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(">", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // >> 末页
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (currentPage < totalPages) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = currentPage < totalPages) { onLastPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(">>", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
