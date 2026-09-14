package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.ForgeSlot
import com.xianxia.sect.core.model.ForgeSlotStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SmallScreenDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.components.WatchItemButton
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.FORGE_THEME
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionCommonDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLocale
import com.xianxia.sect.ui.game.delegate.releaseDiscipleForReassignment

/** 锻造坊派生状态 */
private data class ForgeDialogState(
    val buildingIndex: Int,
    val slotIndex: Int,
    val mySlot: ForgeSlot?,
    val assignedDiscipleId: String?,
    val workerDisciple: DiscipleAggregate?,
    val discipleMap: Map<String, DiscipleAggregate>,
    val battleAndExplorationIds: Set<String>,
    val showAllEnabled: Boolean,
    val coroutineScope: CoroutineScope,
    val gameData: GameData?
)

/** 锻造坊对话框回调组 */
private data class ForgeDialogActions(
    val onWorkerSlotEmptyClick: () -> Unit,
    val onWorkerDismiss: () -> Unit,
    val onWorkerSwap: () -> Unit,
    val onAutoToggle: () -> Unit,
    val onReplace: () -> Unit,
    val onIdleClick: () -> Unit
)

/** 锻造弹窗输入快照（ForgeDialog 参数分组）：槽位 + 材料 + 档案 + 弟子 */
data class ForgeDialogInputs(
    val forgeSlots: List<ForgeSlot>,
    val materials: List<Material>,
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>
)

@Composable
@Suppress("UnusedParameter") // productionViewModel: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
fun ForgeDialog(
    inputs: ForgeDialogInputs,
    buildingInstanceId: String = "",
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    forgeViewModel: ForgeViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val forgeSlots = inputs.forgeSlots
    val materials = inputs.materials
    val gameData = inputs.gameData
    val disciples = inputs.disciples
    var showEquipmentSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showWorkerSelection by remember { mutableStateOf(false) }
    var replaceSlotIndex by remember { mutableStateOf<Int?>(null) }

    val forgeState = rememberForgeDialogState(
        buildingInstanceId = buildingInstanceId, forgeSlots = forgeSlots, gameData = gameData,
        disciples = disciples, viewModel = viewModel
    )

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "锻造坊",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ForgeDialogBody(
                state = forgeState, viewModel = viewModel, disciples = disciples, forgeViewModel = forgeViewModel,
                actions = ForgeDialogActions(
                    onWorkerSlotEmptyClick = { showWorkerSelection = true },
                    onWorkerDismiss = { forgeViewModel.removeWorker(forgeState.buildingIndex) },
                    onWorkerSwap = { showWorkerSelection = true },
                    onAutoToggle = { forgeViewModel.toggleAuto(forgeState.buildingIndex) },
                    onReplace = {
                        replaceSlotIndex = forgeState.slotIndex
                        selectedSlotIndex = forgeState.slotIndex
                        showEquipmentSelection = true
                    },
                    onIdleClick = {
                        selectedSlotIndex = forgeState.slotIndex
                        showEquipmentSelection = true
                    }
                )
            )
        }
    }

    if (showWorkerSelection) {
        ForgeWorkerSelectionSection(
            state = forgeState, viewModel = viewModel, forgeViewModel = forgeViewModel,
            onDismiss = { showWorkerSelection = false }, onWorkerAssigned = { showWorkerSelection = false }
        )
    }

    if (showEquipmentSelection) {
        selectedSlotIndex?.let { slotIdx ->
            ForgeEquipmentSelectionSection(
                slotIdx = slotIdx, isReplacing = replaceSlotIndex != null,
                materials = materials, workerDisciple = forgeState.workerDisciple,
                viewModel = viewModel, forgeViewModel = forgeViewModel,
                onDismiss = { showEquipmentSelection = false; selectedSlotIndex = null; replaceSlotIndex = null }
            )
        }
    }
}

/** 锻造坊派生状态计算 */
@Composable
private fun rememberForgeDialogState(
    buildingInstanceId: String,
    forgeSlots: List<ForgeSlot>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel
): ForgeDialogState {
    val globalForges = gameData?.placedBuildings?.filter { it.displayName == "锻造坊" } ?: emptyList()
    val buildingIndex = globalForges.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)

    val battleAndExplorationIds = remember(gameData) {
        if (gameData != null) {
            val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
                .toSet()
            val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }
                .toSet()
            battleIds + explorationIds
        } else emptySet()
    }
    val forgeSlotsState by viewModel.forgeSlots.collectAsStateWithLifecycle()
    val mySlot = forgeSlotsState.find { it.slotIndex == buildingIndex }
    val slotIndex = mySlot?.slotIndex ?: buildingIndex
    val assignedDiscipleId = mySlot?.assignedDiscipleId
    val discipleMap = disciples.associateBy { it.id }
    val workerDisciple = if (assignedDiscipleId.isNullOrEmpty()) null
        else discipleMap[assignedDiscipleId]
    val coroutineScope = rememberCoroutineScope()
    // Composition 内禁止读 StateFlow.value（不触发重组）；gameData 参数已由调用方 collect 派生
    val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
    return ForgeDialogState(
        buildingIndex = buildingIndex,
        slotIndex = slotIndex,
        mySlot = mySlot,
        assignedDiscipleId = assignedDiscipleId,
        workerDisciple = workerDisciple,
        discipleMap = discipleMap,
        battleAndExplorationIds = battleAndExplorationIds,
        showAllEnabled = showAllEnabled,
        coroutineScope = coroutineScope,
        gameData = gameData
    )
}

/** 锻造坊主内容区 */
@Composable
private fun ColumnScope.ForgeDialogBody(
    state: ForgeDialogState,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    forgeViewModel: ForgeViewModel,
    actions: ForgeDialogActions
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ForgeWorkerSection(
            workerDisciple = state.workerDisciple,
            viewModel = viewModel,
            disciples = disciples,
            onEmptySlotClick = actions.onWorkerSlotEmptyClick,
            onDismiss = actions.onWorkerDismiss,
            onSwap = actions.onWorkerSwap
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = GameColors.Border, thickness = 1.dp)

        // 自动炼器开关行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = FORGE_THEME.slotLabelPrefix + "位",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            val isAutoEnabled = state.mySlot?.autoRestartEnabled ?: false
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isAutoEnabled) GameColors.Gold else Color.Black)
                    .clickable { actions.onAutoToggle() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isAutoEnabled) "自动炼器:开" else "自动炼器:关",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAutoEnabled) Color.Black else Color.White
                )
            }
        }

        ForgeSlotItem(
            mySlot = state.mySlot,
            gameData = state.gameData,
            slotIndex = state.slotIndex,
            forgeViewModel = forgeViewModel,
            onReplace = actions.onReplace,
            onIdleClick = actions.onIdleClick
        )
    }
}

/** 锻造弟子区 */
@Composable
private fun ForgeWorkerSection(
    workerDisciple: DiscipleAggregate?,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    onEmptySlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start)
        ) {
            Text(
                text = "锻造弟子",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ProfessionInfoButton(isAlchemy = false)
        }
        Spacer(modifier = Modifier.height(4.dp))
        ProfessionProgressSection(disciple = workerDisciple, isAlchemy = false)
        ProfessionLabel(level = workerDisciple?.forgeLevel, isAlchemy = false)
        Spacer(modifier = Modifier.height(2.dp))
        DiscipleSlot(
            disciple = workerDisciple,
            showActions = true,
            onSlotClick = { workerDisciple?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it,
                disciples)) } },
            onEmptySlotClick = onEmptySlotClick,
            onDismiss = onDismiss,
            onSwap = onSwap
        )
    }
}

/** 锻造槽位条目 */
@Composable
private fun ForgeSlotItem(
    mySlot: ForgeSlot?,
    gameData: GameData?,
    slotIndex: Int,
    forgeViewModel: ForgeViewModel,
    onReplace: () -> Unit,
    onIdleClick: () -> Unit
) {
    val isIdle = mySlot?.status == ForgeSlotStatus.IDLE || mySlot == null
    val isWorking = mySlot?.status == ForgeSlotStatus.WORKING
    val remainingMonths = if (isWorking && gameData != null)
        mySlot.getRemainingMonths(gameData.gameYear, gameData.gameMonth) else 0

    ProductionSlotItem(
        theme = FORGE_THEME,
        productName = mySlot?.equipmentName,
        isWorking = isWorking,
        isIdle = isIdle,
        remainingMonths = remainingMonths,
        index = slotIndex,
        productRarity = mySlot?.equipmentRarity ?: 1,
        totalDuration = mySlot?.duration ?: 1,
        successRate = mySlot?.successRate ?: 0.0,
        gamePhase = gameData?.gamePhase ?: 0,
        onCancel = if (isWorking) { { forgeViewModel.cancelForge(slotIndex) } } else null,
        onReplace = if (isWorking) { onReplace } else null,
        onClick = { if (isIdle) onIdleClick() }
    )
}

/** 锻造弟子选择区块 */
@Composable
private fun ForgeWorkerSelectionSection(
    state: ForgeDialogState,
    viewModel: GameViewModel,
    forgeViewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    onWorkerAssigned: () -> Unit
) {
    val workerTheme = remember {
        ProductionTheme(
            buildingId = "forge",
            displayName = "锻造坊",
            elderTitle = "锻造弟子",
            elderBonusInfo = ElderBonusInfo(
                title = "锻造弟子",
                requiredAttribute = "炼器",
                effectDescription = "负责锻造槽位的工作，炼器属性影响产出",
                bonusFormula = "炼器越高，产出越高"
            ),
            coreAttributeName = "炼器",
            coreAttributeColor = GameColors.Success,
            defaultBorderColor = GameColors.Warning,
            workingStatusColor = GameColors.Warning,
            selectedHighlightColor = GameColors.Warning,
            slotLabelPrefix = "炼器槽",
            selectionDialogTitle = "选择锻造弟子",
            startProductionText = "确认",
            elderSelectionTitle = "选择锻造弟子",
            recommendAttributeText = "炼器",
            getCoreAttributeValue = { it.artifactRefining },
            getElderId = { it.forgeElder },
            getDirectDisciples = { it.forgeDisciples.filter { d -> d.sectId == (state.gameData?.activeSectId ?: "") } },
            elderSortComparator = compareByDescending<DiscipleAggregate> { it.artifactRefining }
                .thenBy { it.realm }.thenByDescending { it.realmLayer },
            directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }
    ProductionElderSelectionDialog(
        theme = workerTheme,
        disciples = forgeViewModel.getAvailableWorkers(),
        currentElderId = state.assignedDiscipleId,
        elderSlots = state.gameData?.elderSlots ?: ElderSlots(),
        viewModel = viewModel,
        onDismiss = onDismiss,
        onSelect = { discipleId ->
            val disciple = state.discipleMap[discipleId]
            state.coroutineScope.launch {
                if (state.showAllEnabled && disciple?.status != DiscipleStatus.IDLE) {
                    viewModel.disciple.releaseDiscipleForReassignment(discipleId)
                }
                val d = state.discipleMap[discipleId]
                forgeViewModel.assignWorker(state.buildingIndex, discipleId, d?.name ?: "")
            }
            onWorkerAssigned()
        },
        battleAndExplorationIds = state.battleAndExplorationIds,
    )
}

/** 装备选择弹窗区块 */
@Composable
private fun ForgeEquipmentSelectionSection(
    slotIdx: Int,
    isReplacing: Boolean,
    materials: List<Material>,
    workerDisciple: DiscipleAggregate?,
    viewModel: GameViewModel,
    forgeViewModel: ForgeViewModel,
    onDismiss: () -> Unit
) {
    EquipmentSelectionDialog(
        materials = materials,
        slotIndex = slotIdx,
        workerDisciple = workerDisciple,
        viewModel = viewModel,
        forgeViewModel = forgeViewModel,
        onDismiss = onDismiss,
        onConfirmOverride = if (isReplacing) { { recipe ->
            forgeViewModel.cancelForge(slotIdx)
            forgeViewModel.startForge(slotIdx, recipe)
        } } else null
    )
}

/** 配方 + 可制作状态 */
private data class EquipmentRecipeWithStatus(
    val recipe: ForgeRecipeDatabase.ForgeRecipe,
    val canCraft: Boolean
)

/** 装备配方可制作状态 */
private fun equipmentRecipesWithStatus(
    allRecipes: List<ForgeRecipeDatabase.ForgeRecipe>,
    materialIndex: Map<Pair<String, Int>, Int>
): List<EquipmentRecipeWithStatus> = allRecipes.map { recipe ->
    val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
        val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)
        materialData != null && run {
            val available = materialIndex[materialData.name to materialData.rarity] ?: 0
            available >= requiredQuantity
        }
    }
    EquipmentRecipeWithStatus(recipe, canCraft)
}

/** 装备配方排序：已关注优先 → 稀有度降序 */
private fun sortEquipmentRecipes(
    recipesWithStatus: List<EquipmentRecipeWithStatus>,
    watchedKeys: Set<String>
): List<EquipmentRecipeWithStatus> {
    val (craftable, uncraftable) = recipesWithStatus.partition { it.canCraft }
    val comparator =
        compareByDescending<EquipmentRecipeWithStatus> {
            watchKey("equipment", it.recipe.name) in watchedKeys
        }.thenByDescending { it.recipe.rarity }
    return craftable.sortedWith(comparator) + uncraftable.sortedWith(comparator)
}

/** 点击锻造配方：无弟子/职业等级不够弹提示，否则切换选中状态 */
private fun handleEquipmentRecipeClick(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    workerDisciple: DiscipleAggregate?,
    forgeViewModel: ForgeViewModel,
    isSelected: Boolean,
    onSelectionChange: (ForgeRecipeDatabase.ForgeRecipe?) -> Unit
) {
    val workerLevel = workerDisciple?.forgeLevel ?: 0
    when {
        workerDisciple == null -> forgeViewModel.showNoWorkerHint()
        !ProfessionRules.canCraftTier(workerLevel, recipe.tier) -> forgeViewModel.showTierLockedHint()
        isSelected -> onSelectionChange(null)
        else -> onSelectionChange(recipe)
    }
}

@Composable
private fun EquipmentSelectionDialog(
    materials: List<Material>,
    slotIndex: Int,
    workerDisciple: DiscipleAggregate?,
    viewModel: GameViewModel,
    forgeViewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    onConfirmOverride: ((ForgeRecipeDatabase.ForgeRecipe) -> Unit)? = null
) {
    var selectedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    val allRecipes by forgeViewModel.allForgeRecipes.collectAsStateWithLifecycle()

    ProductionCommonDialog(
        title = FORGE_THEME.selectionDialogTitle,
        theme = FORGE_THEME,
        onDismiss = onDismiss,
        enableScroll = false
    ) {
        val materialIndex = remember(materials) {
            materials.groupBy { it.name to it.rarity }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }
        }
        val recipesWithStatus = remember(allRecipes, materialIndex) {
            equipmentRecipesWithStatus(allRecipes, materialIndex)
        }

        val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
        val sortedRecipes = remember(recipesWithStatus, watchedKeys) {
            sortEquipmentRecipes(recipesWithStatus, watchedKeys)
        }

        Column(modifier = Modifier.weight(1f)) {
            EquipmentRecipeGrid(
                sortedRecipes = sortedRecipes,
                selectedRecipeId = selectedRecipe?.id,
                workerDisciple = workerDisciple,
                forgeViewModel = forgeViewModel,
                watchedKeys = watchedKeys,
                onSelectionChange = { recipe -> selectedRecipe = recipe; clickedRecipe = recipe },
                onLongPress = { recipe -> clickedRecipe = recipe; showDetail = true }
            )

            Spacer(modifier = Modifier.height(12.dp))
            val hasEnoughMaterialsForSelected = sortedRecipes
                .find { it.recipe.id == selectedRecipe?.id }?.canCraft ?: false

            GameButton(
                text = FORGE_THEME.startProductionText,
                onClick = {
                    selectedRecipe?.let { recipe ->
                        if (onConfirmOverride != null) onConfirmOverride(recipe)
                        else forgeViewModel.startForge(slotIndex, recipe)
                        onDismiss()
                    }
                },
                enabled = selectedRecipe != null && hasEnoughMaterialsForSelected
            )
        }
    }

    if (showDetail) {
        clickedRecipe?.let { recipe ->
            EquipmentDetailDialog(
                recipe = recipe, materials = materials, viewModel = viewModel,
                onDismiss = { showDetail = false }
            )
        }
    }
}

/** 装备配方网格 */
@Composable
private fun ColumnScope.EquipmentRecipeGrid(
    sortedRecipes: List<EquipmentRecipeWithStatus>,
    selectedRecipeId: String?,
    workerDisciple: DiscipleAggregate?,
    forgeViewModel: ForgeViewModel,
    watchedKeys: Set<String>,
    onSelectionChange: (ForgeRecipeDatabase.ForgeRecipe?) -> Unit,
    onLongPress: (ForgeRecipeDatabase.ForgeRecipe) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(60.dp),
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortedRecipes, key = { it.recipe.id }, contentType = { "recipe" }) { recipeWithStatus ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UnifiedItemCard(
                    data = ItemCardData(
                        name = recipeWithStatus.recipe.name,
                        rarity = recipeWithStatus.recipe.rarity
                    ),
                    isSelected = selectedRecipeId == recipeWithStatus.recipe.id,
                    isFollowed = watchKey("equipment", recipeWithStatus.recipe.name) in watchedKeys,
                    craftable = recipeWithStatus.canCraft,
                    showQuantity = false,
                    onClick = {
                        // 职业门禁提示框：无弟子/职业等级不够时点击配方不选中，弹提示
                        handleEquipmentRecipeClick(
                            recipe = recipeWithStatus.recipe,
                            workerDisciple = workerDisciple,
                            forgeViewModel = forgeViewModel,
                            isSelected = selectedRecipeId == recipeWithStatus.recipe.id,
                            onSelectionChange = onSelectionChange
                        )
                    },
                    onLongPress = { onLongPress(recipeWithStatus.recipe) }
                )
            }
        }
    }
}

@Composable
private fun EquipmentDetailDialog(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    materials: List<Material>,
    viewModel: GameViewModel? = null,
    onDismiss: () -> Unit
) {
    SmallScreenDialog(onDismissRequest = onDismiss, title = recipe.name) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "品阶: ${recipe.tier}阶", fontSize = 12.sp, color = Color.Black)
                    Text(text = "时间: ${recipe.duration}月", fontSize = 12.sp, color = Color.Black)
                }

                EquipmentMaterialRequirementList(recipe = recipe, materials = materials)

                Text(text = "属性加成:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "部位: ${recipe.type.displayName}", fontSize = 11.sp, color = Color.Black)

                    val template = com.xianxia.sect.core.registry.EquipmentDatabase.getTemplateByName(recipe.name)
                    if (template != null) {
                        if (template.physicalAttack > 0) Text(text = "物理攻击 +${template.physicalAttack}",
                            fontSize = 11.sp, color = Color.Black)
                        if (template.magicAttack > 0) Text(text = "法术攻击 +${template.magicAttack}", fontSize = 11.sp,
                            color = Color.Black)
                        if (template.physicalDefense > 0) Text(text = "物理防御 +${template.physicalDefense}",
                            fontSize = 11.sp, color = Color.Black)
                        if (template.magicDefense > 0) Text(text = "法术防御 +${template.magicDefense}", fontSize = 11.sp,
                            color = Color.Black)
                        if (template.speed > 0) Text(text = "身法 +${template.speed}", fontSize = 11.sp,
                            color = Color.Black)
                        if (template.hp > 0) Text(text = "生命 +${template.hp}", fontSize = 11.sp, color = Color.Black)
                        if (template.mp > 0) Text(text = "法力 +${template.mp}", fontSize = 11.sp, color = Color.Black)
                        if (template.critChance > 0) {
                            val critRateText = String.format(
                                LocalLocale.current.platformLocale, "%.1f", template.critChance * 100)
                            Text(
                                text = "暴击率 +$critRateText%",
                                fontSize = 11.sp, color = Color.Black,
                            )
                        }
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color.Black)

                // 关注按钮：viewModel 非空时显示（装备按名称关注），位于底部操作区
                if (viewModel != null) {
                    val watchedKeys = viewModel.watchedItemIds.collectAsStateWithLifecycle().value
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        WatchItemButton(
                            watchKey = watchKey("equipment", recipe.name),
                            watchedKeys = watchedKeys,
                            onToggleWatch = { key -> viewModel.inventory.toggleWatchItem(key) }
                        )
                    }
                }
                }
            }
    }
}

/** 所需材料列表 */
@Composable
private fun EquipmentMaterialRequirementList(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    materials: List<Material>
) {
    Text(text = "所需材料:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recipe.materials.forEach { (materialId, requiredQuantity) ->
            val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = materialData?.name
            val materialRarity = materialData?.rarity ?: 1
            val material = materials.find { it.name == materialName && it.rarity == materialRarity }
            val hasEnough = material != null && material.quantity >= requiredQuantity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = material?.name ?: materialName ?: materialId,
                    fontSize = 11.sp,
                    color = if (hasEnough) Color.Black else Color(0xFFE74C3C)
                )
                Text(
                    text = "${GameUtils.formatNumber(material?.quantity ?: 0)}/$requiredQuantity",
                    fontSize = 11.sp,
                    color = if (hasEnough) GameColors.Success else Color(0xFFE74C3C)
                )
            }
        }
    }
}
