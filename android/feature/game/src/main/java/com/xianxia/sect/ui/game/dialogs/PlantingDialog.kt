package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogSoftInputGuard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.SystemBarFreezeScope
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.core.util.watchKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.QuantitySelector
import com.xianxia.sect.ui.game.components.QuantitySelectorSizes
import com.xianxia.sect.ui.theme.GameColors
import kotlin.math.ceil
import com.xianxia.sect.ui.components.clickableWithSound



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

/** 种植对话框状态（PlantingDialog 拆分） */
private class PlantingDialogState {
    var selectedSeedId by mutableStateOf<String?>(null)
    var seedPage by mutableIntStateOf(1)
    var plantQuantity by mutableIntStateOf(1)
    var removeDialogGroup by mutableStateOf<FieldGroup?>(null)
    var removeQuantity by mutableIntStateOf(1)
    var showSeedDetail by mutableStateOf(false)
    var detailSeed by mutableStateOf<Seed?>(null)
    var dynPageSize by mutableIntStateOf(12)
}

/** 种植对话框派生数据（PlantingDialog 拆分） */
private data class PlantingDerivedData(
    val watchedKeys: Set<String>,
    val activeSeeds: List<Seed>,
    val spiritFields: List<GridBuildingData>,
    val selectedSeed: Seed?,
    val fieldGroups: List<FieldGroup>,
    val unplantedCount: Int,
    val maxPlantable: Int,
    val totalPages: Int,
    val currentPage: Int,
    val pagedSeeds: List<Seed>
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
    // 切换 softInputMode，防止 Xiaomi HyperOS 键盘频闪
    DialogSoftInputGuard()

    // 含数量常驻输入框：挂载期间冻结宿主窗口系统栏操作
    // （荣耀X70键盘频闪根治，见 SystemBarFreezeScope KDoc）
    DisposableEffect(Unit) {
        SystemBarFreezeScope.enterFreeze()
        onDispose { SystemBarFreezeScope.exitFreeze() }
    }

    // ── 本地状态 ───────────────────────────────────────────
    val state = remember { PlantingDialogState() }

    // ── 派生数据 ───────────────────────────────────────────
    val derived = rememberPlantingDerivedData(
        seeds = seeds, gameData = gameData, activeSectId = activeSectId, viewModel = viewModel, state = state
    )

    // ── 主体布局 ───────────────────────────────────────────
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize(), color = GameColors.PageBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal), contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏：种植标题 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "种植", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(modifier = Modifier.weight(1f))
                    CloseButton(onClick = onDismiss)
                }

                // 主区域：左（60%）| 分割线 | 右（40%）
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SeedGridPanel(state = state, derived = derived)
                    Box(
                        modifier = Modifier.width(1.dp).fillMaxHeight().background(GameColors.ButtonDisabled)
                    )
                    PlantingFieldPanel(
                        state = state, derived = derived, seeds = seeds,
                        viewModel = viewModel, activeSectId = activeSectId
                    )
                }
            }
        }
    }

    // P-2：种子详情弹窗提取（行为逐行一致）
    SeedDetailDialog(
        show = state.showSeedDetail, seed = state.detailSeed,
        onDismiss = { state.showSeedDetail = false; state.detailSeed = null },
        viewModel = viewModel
    )

    // P-2：铲除确认弹窗提取（行为逐行一致）
    RemoveConfirmationDialog(
        group = state.removeDialogGroup,
        removeQuantity = state.removeQuantity,
        onQuantityChange = { qty -> state.removeQuantity = qty },
        onConfirm = { state.removeDialogGroup = null; state.removeQuantity = 1 },
        onDismiss = { state.removeDialogGroup = null },
        onRemove = { ids -> viewModel.planting.removePlantsFromSpiritFields(ids) }
    )
}

/** 种植对话框派生数据计算（PlantingDialog 拆分） */
@Composable
private fun rememberPlantingDerivedData(
    seeds: List<Seed>,
    gameData: GameData,
    activeSectId: String,
    viewModel: GameViewModel,
    state: PlantingDialogState
): PlantingDerivedData {
    // 可用种子：已关注优先 → 稀有度降 → 名称升
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val activeSeeds = remember(seeds, watchedKeys) {
        seeds
            .filter { it.quantity > 0 }
            .sortedByWatchedThenRarity(watchedKeys)
    }

    // 当前宗门的灵田
    val spiritFields = remember(gameData.placedBuildings, activeSectId) {
        gameData.placedBuildings.filter { field ->
            (field.buildingId == "spirit_field" || field.displayName == "灵田")
                && field.sectId == activeSectId
        }
    }

    // 当前选中的种子对象
    val selectedSeed = remember(state.selectedSeedId, activeSeeds) {
        activeSeeds.find { it.id == state.selectedSeedId }
    }

    // 按种植状态分组：未种植 → 同种种子分组
    val fieldGroups = remember(spiritFields, gameData.spiritFieldPlants, activeSeeds) {
        plantingFieldGroups(spiritFields, gameData.spiritFieldPlants, activeSeeds, seeds)
    }

    val unplantedCount = fieldGroups.firstOrNull { it.seedId.isEmpty() }?.fields?.size ?: 0

    // Bug B 修复：可种植数量同时受种子数量约束（引擎层有兜底，此处避免按钮误导）
    val maxPlantable = minOf(unplantedCount, selectedSeed?.quantity ?: 0)

    // ── 分页（动态计算每页数量） ─────────────────────────────
    val totalPages = maxOf(1, ceil(activeSeeds.size.toDouble() / state.dynPageSize.coerceAtLeast(1)).toInt())
    val currentPage = state.seedPage.coerceIn(1, totalPages)
    val pagedSeeds = remember(currentPage, activeSeeds, state.dynPageSize) {
        activeSeeds.drop((currentPage - 1) * state.dynPageSize).take(state.dynPageSize)
    }
    return PlantingDerivedData(
        watchedKeys = watchedKeys,
        activeSeeds = activeSeeds,
        spiritFields = spiritFields,
        selectedSeed = selectedSeed,
        fieldGroups = fieldGroups,
        unplantedCount = unplantedCount,
        maxPlantable = maxPlantable,
        totalPages = totalPages,
        currentPage = currentPage,
        pagedSeeds = pagedSeeds
    )
}

/** 灵田按种植状态分组（PlantingDialog 拆分） */
private fun plantingFieldGroups(
    spiritFields: List<GridBuildingData>,
    spiritFieldPlants: List<SpiritFieldPlant>,
    activeSeeds: List<Seed>,
    seeds: List<Seed>
): List<FieldGroup> {
    val plantsByBuilding = spiritFieldPlants.associateBy { it.buildingInstanceId }
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

    return buildList {
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
            val rarity = seedMap[sid]?.rarity
                ?: seeds.find { it.id == sid }?.rarity
                ?: HerbDatabase.getSeedById(sid)?.rarity
                ?: 1
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

/** 左侧种子网格面板（PlantingDialog 拆分） */
@Composable
private fun RowScope.SeedGridPanel(
    state: PlantingDialogState,
    derived: PlantingDerivedData
) {
    Column(
        modifier = Modifier
            .weight(0.6f)
            .fillMaxHeight()
            .padding(start = 12.dp, top = 4.dp, end = 8.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        if (derived.activeSeeds.isEmpty()) {
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
            PlantingSeedGrid(state = state, derived = derived)

            // 分页
            Spacer(modifier = Modifier.height(4.dp))
            PlantingPagination(
                currentPage = derived.currentPage,
                totalPages = derived.totalPages,
                onFirstPage = { if (derived.currentPage > 1) state.seedPage = 1 },
                onPreviousPage = { if (derived.currentPage > 1) state.seedPage = derived.currentPage - 1 },
                onNextPage = { if (derived.currentPage < derived.totalPages) state.seedPage = derived.currentPage + 1 },
                onLastPage = { if (derived.currentPage < derived.totalPages) state.seedPage = derived.totalPages }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 种子翻页网格（PlantingDialog 拆分）：BoxWithConstraints 动态分页 + 种子卡片 */
@Composable
private fun ColumnScope.PlantingSeedGrid(
    state: PlantingDialogState,
    derived: PlantingDerivedData
) {
    BoxWithConstraints(
        modifier = Modifier.weight(1f).fillMaxWidth()
    ) {
        val itemW = 60.dp
        val gap = 6.dp
        val cols = maxOf(1, ((maxWidth - gap) / (itemW + gap)).toInt())
        val rows = maxOf(2, ((maxHeight - gap) / (itemW + 20.dp + gap)).toInt())
        val calcSize = cols * rows
        if (calcSize != state.dynPageSize && calcSize > 0) {
            LaunchedEffect(Unit) { state.dynPageSize = calcSize }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(60.dp),
            modifier = Modifier
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(2.dp)
        ) {
            items(derived.pagedSeeds, key = { it.id }, contentType = { "seed" }) { seed ->
                UnifiedItemCard(
                    data = ItemCardData(
                        id = seed.id,
                        name = seed.name,
                        description = seed.description,
                        rarity = seed.rarity,
                        quantity = seed.quantity,
                        isSeed = true
                    ),
                    isSelected = seed.id == state.selectedSeedId,
                    isFollowed = seed.watchKey() in derived.watchedKeys,
                    onClick = {
                        state.selectedSeedId =
                            if (state.selectedSeedId == seed.id) null else seed.id
                        state.plantQuantity = 1
                    },
                    onLongPress = {
                        state.detailSeed = seed
                        state.showSeedDetail = true
                    }
                )
            }
        }
    }
}

/** 右侧灵田面板（PlantingDialog 拆分）：统计 + 已种植列表 + 底部操作栏 */
@Composable
private fun RowScope.PlantingFieldPanel(
    state: PlantingDialogState,
    derived: PlantingDerivedData,
    seeds: List<Seed>,
    viewModel: GameViewModel,
    activeSectId: String
) {
    Column(
        modifier = Modifier
            .weight(0.4f)
            .fillMaxHeight()
    ) {
        // 灵田统计行固定第一行
        val totalFields = derived.fieldGroups.sumOf { it.fields.size }
        val plantedFields = derived.fieldGroups.filter { it.seedId.isNotEmpty() }.sumOf { it.fields.size }
        val unplantedFields = derived.fieldGroups.find { it.seedId.isEmpty() }?.fields?.size ?: 0
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
            HorizontalDivider(thickness = 1.dp, color = GameColors.ButtonDisabled)
            Spacer(modifier = Modifier.height(6.dp))
        }

        PlantedGroupsList(
            state = state,
            derived = derived,
            seeds = seeds
        )

        // 底部操作栏：数量选择 + 种植按钮（灵田非空时显示）
        if (derived.spiritFields.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = GameColors.ButtonDisabled)
            PlantingQuantityControl(
                state = state,
                derived = derived,
                viewModel = viewModel,
                activeSectId = activeSectId
            )
        }
    }
}

/** 已种植种子卡片列表（PlantingDialog 拆分） */
@Composable
private fun ColumnScope.PlantedGroupsList(
    state: PlantingDialogState,
    derived: PlantingDerivedData,
    seeds: List<Seed>
) {
    // 已种植种子卡片列表（可滚动）
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val plantedGroups = derived.fieldGroups.filter { g -> g.seedId.isNotEmpty() }
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
                        PlantedGroupSeedCard(
                            group = group,
                            activeSeeds = derived.activeSeeds,
                            seeds = seeds,
                            watchedKeys = derived.watchedKeys,
                            state = state
                        )
                        Text(
                            text = "${group.fields.size}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        GameButton(
                            text = "铲除",
                            onClick = {
                                state.removeQuantity = 1
                                state.removeDialogGroup = group
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 已种植分组种子卡片（PlantingDialog 拆分）：仓库种子 → 图鉴种子 → 未知兜底 */
@Composable
private fun PlantedGroupSeedCard(
    group: FieldGroup,
    activeSeeds: List<Seed>,
    seeds: List<Seed>,
    watchedKeys: Set<String>,
    state: PlantingDialogState
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
                quantity = plantedSeed.quantity,
                isSeed = true
            ),
            isSelected = false,
            isFollowed = plantedSeed.watchKey() in watchedKeys,
            onClick = { state.selectedSeedId = plantedSeed.id },
            onLongPress = {
                state.detailSeed = plantedSeed
                state.showSeedDetail = true
            }
        )
    } else {
        val fbSeed = HerbDatabase.getSeedByName(group.seedName)
        if (fbSeed != null) {
            UnifiedItemCard(
                data = ItemCardData(
                    id = fbSeed.id,
                    name = fbSeed.name,
                    rarity = fbSeed.rarity,
                    quantity = 0,
                    isSeed = true
                ),
                isSelected = false,
                isFollowed = watchKey("seed", fbSeed.name) in watchedKeys,
                onClick = {},
                onLongPress = {
                    state.detailSeed = Seed(
                        id = fbSeed.id,
                        name = fbSeed.name,
                        rarity = fbSeed.rarity,
                        description = fbSeed.description,
                        growTime = fbSeed.growTime,
                        yield = fbSeed.yield,
                        quantity = 0
                    )
                    state.showSeedDetail = true
                }
            )
        } else {
            UnknownSeedFallbackBox(group = group, state = state)
        }
    }
}

/** 未知种子兜底卡片（PlantingDialog 拆分） */
@Composable
private fun UnknownSeedFallbackBox(
    group: FieldGroup,
    state: PlantingDialogState
) {
    val fallbackName = group.seedName.ifEmpty { "未知种子" }
    Box(
        modifier = Modifier.size(60.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(GameColors.SurfaceLightGray)
            .border(1.dp, GameColors.ButtonDisabled, RoundedCornerShape(4.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    state.detailSeed = Seed(
                        id = group.seedId,
                        name = fallbackName,
                        rarity = group.seedRarity,
                        description = "",
                        growTime = 0,
                        yield = 0,
                        quantity = 0
                    )
                    state.showSeedDetail = true
                },
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(fallbackName, fontSize = 8.sp, color = Color.Black, textAlign = TextAlign.Center)
    }
}

/** 种植数量器紧凑尺寸（26dp 按钮：灵田面板仅占屏宽 40%，竖屏需更小尺寸防溢出） */
private val plantingQuantitySizes = QuantitySelectorSizes(
    buttonSize = 26.dp,
    numberBoxWidth = 48.dp,
    numberBoxHeight = 26.dp,
    buttonCornerRadius = 4.dp,
    buttonFontSize = 13.sp,
)

/** 种植数量控制区 + 种植按钮（PlantingDialog 拆分）：数量器行 + 种植按钮行 */
@Composable
private fun PlantingQuantityControl(
    state: PlantingDialogState,
    derived: PlantingDerivedData,
    viewModel: GameViewModel,
    activeSectId: String
) {
    fun performPlanting() {
        val toPlant = state.plantQuantity.coerceAtMost(derived.maxPlantable)
        val unplantedFields =
            derived.fieldGroups.firstOrNull { it.seedId.isEmpty() }?.fields
                ?: emptyList()
        // 批量收集instanceId，一次性种植
        val ids = unplantedFields.take(toPlant).map { it.instanceId }
        if (ids.isNotEmpty() && derived.selectedSeed != null) {
            viewModel.planting.plantOnSpiritFields(ids, derived.selectedSeed.id, activeSectId)
        }
        state.selectedSeedId = null
        state.plantQuantity = 1
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 统一数量选择器：-10/-1/输入/+1/+10（与商人/宗门交易一致）；
        // key 种子切换重建，清空编辑态残留的输入串与焦点
        key(derived.selectedSeed?.id) {
            QuantitySelector(
                quantity = state.plantQuantity,
                maxQuantity = derived.maxPlantable.coerceAtLeast(1),
                onQuantityChange = { state.plantQuantity = it },
                sizes = plantingQuantitySizes
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        GameButton(
            text = "种植",
            enabled = derived.selectedSeed != null && derived.unplantedCount > 0,
            onClick = { performPlanting() }
        )
    }
}

/** P-2：种子详情弹窗（从 PlantingDialog 提取）。 */
@Composable
private fun SeedDetailDialog(
    show: Boolean,
    seed: Seed?,
    onDismiss: () -> Unit,
    viewModel: com.xianxia.sect.ui.game.GameViewModel
) {
    if (show && seed != null) {
        ItemDetailDialog(
            item = checkNotNull(seed) { "detailSeed is null" },
            onDismiss = onDismiss,
            viewModel = viewModel
        )
    }
}

/** P-2：铲除确认弹窗（从 PlantingDialog 提取，状态由调用方持有）。 */
@Composable
private fun RemoveConfirmationDialog(
    group: FieldGroup?,
    removeQuantity: Int,
    onQuantityChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: (List<String>) -> Unit
) {
    group?.let {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "确认铲除",
            confirmLabel = "确认",
            dismissLabel = "取消",
            onConfirm = {
                val toRemove = removeQuantity.coerceAtMost(it.plantEntries.size)
                val ids = it.plantEntries.take(toRemove).map { e -> e.buildingInstanceId }
                if (ids.isNotEmpty()) {
                    onRemove(ids)
                }
                onConfirm()
            },
            onDismiss = onDismiss
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "当前分组：${it.seedName}（${it.fields.size}块）",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("铲除数量:", fontSize = 12.sp, color = Color.Black)
                    // key(seedId)：切换铲除分组时重建组件，清空编辑态残留的输入串与焦点
                    key(it.seedId) {
                        QuantitySelector(
                            quantity = removeQuantity,
                            maxQuantity = it.fields.size.coerceAtLeast(1),
                            onQuantityChange = onQuantityChange,
                            sizes = plantingQuantitySizes
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
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(
                if (currentPage > 1) Color(0xFF3498DB) else GameColors.DividerGray
            ).clickableWithSound(enabled = currentPage > 1) { onFirstPage() },
            contentAlignment = Alignment.Center
        ) {
            Text("<<", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // < 上一页
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(
                if (currentPage > 1) Color(0xFF3498DB) else GameColors.DividerGray
            ).clickableWithSound(enabled = currentPage > 1) { onPreviousPage() },
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
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(
                if (currentPage < totalPages) Color(0xFF3498DB) else GameColors.DividerGray
            ).clickableWithSound(enabled = currentPage < totalPages) { onNextPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(">", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // >> 末页
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(
                if (currentPage < totalPages) Color(0xFF3498DB) else GameColors.DividerGray
            ).clickableWithSound(enabled = currentPage < totalPages) { onLastPage() },
            contentAlignment = Alignment.Center
        ) {
            Text(">>", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
