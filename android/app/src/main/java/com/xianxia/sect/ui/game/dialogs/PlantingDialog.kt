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
            plantsByBuilding[field.instanceId]!!.seedId
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

    // ── 分页 ───────────────────────────────────────────────
    val pageSize = 12
    val totalPages = maxOf(1, ceil(activeSeeds.size.toDouble() / pageSize).toInt())
    val currentPage = seedPage.coerceIn(1, totalPages)
    val pagedSeeds = remember(currentPage, activeSeeds) {
        activeSeeds.drop((currentPage - 1) * pageSize).take(pageSize)
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
                // 标题栏：标题 + 关闭按钮同行
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "种子（${activeSeeds.size}）",
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
                        // 种子网格
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(60.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
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
                                    }
                                )
                            }
                        }

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

                // ═════════════════ 右侧：灵田信息 ════════════
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                ) {
                    // 灵田标题 + 滚动列表
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "灵田",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (spiritFields.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "当前宗门没有灵田",
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                fieldGroups.forEach { group ->
                                    FieldGroupRow(
                                        group = group,
                                        onRemove = {
                                            removeQuantity = 1
                                            removeQtyInput = "1"
                                            removeDialogGroup = group
                                        }
                                    )
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("种植数量:", fontSize = 12.sp, color = Color.Black)
                            PlantQuantitySelector(
                                quantity = plantQuantity,
                                maxQuantity = unplantedCount.coerceAtLeast(1),
                                isEditing = isEditingQty,
                                input = qtyInput,
                                onMinus = {
                                    if (plantQuantity > 1) {
                                        plantQuantity--
                                        qtyInput = plantQuantity.toString()
                                    }
                                },
                                onPlus = {
                                    if (plantQuantity < unplantedCount) {
                                        plantQuantity++
                                        qtyInput = plantQuantity.toString()
                                    }
                                },
                                onEditingChange = { editing ->
                                    if (!editing && isEditingQty) {
                                        val parsed = qtyInput.toIntOrNull()
                                        plantQuantity =
                                            (parsed ?: 1).coerceIn(1, unplantedCount.coerceAtLeast(1))
                                        qtyInput = plantQuantity.toString()
                                    }
                                    isEditingQty = editing
                                },
                                onInputChange = { v ->
                                    isEditingQty = true
                                    qtyInput = v
                                }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            GameButton(
                                text = "种植",
                                enabled = selectedSeed != null && unplantedCount > 0,
                                onClick = {
                                    val toPlant = plantQuantity.coerceAtMost(unplantedCount)
                                    val unplantedFields =
                                        fieldGroups.firstOrNull { it.seedId.isEmpty() }?.fields
                                            ?: emptyList()
                                    for (i in 0 until toPlant) {
                                        viewModel.plantOnSpiritField(
                                            unplantedFields[i].instanceId,
                                            selectedSeed!!.id,
                                            activeSectId
                                        )
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
                    viewModel.removePlantFromSpiritField(
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
                    "铲除后作物将消失，是否确认？",
                    fontSize = 12.sp,
                    color = Color.Black
                )
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
                    PlantQuantitySelector(
                        quantity = removeQuantity,
                        maxQuantity = group.fields.size.coerceAtLeast(1),
                        isEditing = isEditingRemoveQty,
                        input = removeQtyInput,
                        onMinus = {
                            if (removeQuantity > 1) {
                                removeQuantity--
                                removeQtyInput = removeQuantity.toString()
                            }
                        },
                        onPlus = {
                            if (removeQuantity < group.fields.size) {
                                removeQuantity++
                                removeQtyInput = removeQuantity.toString()
                            }
                        },
                        onEditingChange = { editing ->
                            if (!editing && isEditingRemoveQty) {
                                val parsed = removeQtyInput.toIntOrNull()
                                removeQuantity =
                                    (parsed ?: 1).coerceIn(1, group.fields.size.coerceAtLeast(1))
                                removeQtyInput = removeQuantity.toString()
                            }
                            isEditingRemoveQty = editing
                        },
                        onInputChange = { v ->
                            isEditingRemoveQty = true
                            removeQtyInput = v
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

/**
 * 右侧灵田分组行。
 *
 * 布局：[铲除] [灵田图标] [名称 + 数量] [种子卡片 / 空占位]
 *
 * @param group 当前分组数据
 * @param onRemove 点击铲除按钮的回调
 */
@Composable
private fun FieldGroupRow(
    group: FieldGroup,
    onRemove: () -> Unit
) {
    val isPlanted = group.seedId.isNotEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 铲除按钮
        GameButton(
            text = "铲除",
            enabled = isPlanted,
            onClick = onRemove
        )

        Spacer(modifier = Modifier.width(6.dp))

        // 灵田图标
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isPlanted) Color(0xFF27AE60) else Color(0xFF95A5A6)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "灵田",
                fontSize = 10.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 灵田名称 + 数量
        Column {
            Text(
                text = "灵田",
                fontSize = 10.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "×${group.fields.size}",
                fontSize = 10.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // 已种植 → 种子卡片；未种植 → 空占位
        if (isPlanted) {
            UnifiedItemCard(
                data = ItemCardData(
                    id = group.seedId,
                    name = group.seedName,
                    rarity = group.seedRarity,
                    quantity = group.fields.size,
                    description = group.seedName
                ),
                showQuantity = false
            )
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFFBDBDBD), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "空",
                    fontSize = 12.sp,
                    color = Color(0xFFBDBDBD),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 数量选择器 [-][数值/编辑框][+]
 *
 * 点击中间数值切换为输入框，可通过键盘直接填入数值。
 * 失去焦点或按 Done 键后自动校验并提交。
 */
@Composable
private fun PlantQuantitySelector(
    quantity: Int,
    maxQuantity: Int,
    isEditing: Boolean,
    input: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onInputChange: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── 减号按钮 ──
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (quantity > 1) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = quantity > 1) { onMinus() },
            contentAlignment = Alignment.Center
        ) {
            Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // ── 数值显示 / 编辑框 ──
        if (isEditing) {
            BasicTextField(
                value = input,
                onValueChange = { newVal ->
                    val filtered = newVal.filter { it.isDigit() }
                    if (filtered.length <= 5) onInputChange(filtered)
                },
                modifier = Modifier
                    .width(48.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (!it.isFocused) onEditingChange(false) },
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = Color.Black
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onEditingChange(false) }
                ),
                singleLine = true
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(4.dp))
                    .clickable {
                        onInputChange(quantity.toString())
                        onEditingChange(true)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$quantity",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ── 加号按钮 ──
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (quantity < maxQuantity) Color(0xFF3498DB) else Color(0xFFCCCCCC)
                )
                .clickable(enabled = quantity < maxQuantity) { onPlus() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
