package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.profession.ProfessionRules
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.WatchItemButton
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SmallScreenDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.ALCHEMY_THEME
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionCommonDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import com.xianxia.sect.ui.game.delegate.releaseDiscipleForReassignment

/** 炼丹炉派生状态 */
private data class AlchemyDialogState(
    val buildingIndex: Int,
    val slotIndex: Int,
    val mySlot: AlchemySlot?,
    val assignedDiscipleId: String?,
    val workerDisciple: DiscipleAggregate?,
    val discipleMap: Map<String, DiscipleAggregate>,
    val battleAndExplorationIds: Set<String>,
    val showAllEnabled: Boolean,
    val coroutineScope: CoroutineScope,
    val gameData: GameData?
)

/** 炼丹炉对话框回调组 */
private data class AlchemyDialogActions(
    val onWorkerSlotEmptyClick: () -> Unit,
    val onWorkerDismiss: () -> Unit,
    val onWorkerSwap: () -> Unit,
    val onAutoToggle: () -> Unit,
    val onReplace: () -> Unit,
    val onIdleClick: () -> Unit
)

/** 炼丹弹窗输入快照（AlchemyDialog 参数分组）：槽位 + 材料/草药 + 档案 + 弟子 */
data class AlchemyDialogInputs(
    val alchemySlots: List<AlchemySlot>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>
)

@Composable
@Suppress("UnusedParameter") // productionViewModel: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
fun AlchemyDialog(
    inputs: AlchemyDialogInputs,
    buildingInstanceId: String = "",
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val alchemySlots = inputs.alchemySlots
    val materials = inputs.materials
    val herbs = inputs.herbs
    val gameData = inputs.gameData
    val disciples = inputs.disciples
    var showPillSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showWorkerSelection by remember { mutableStateOf(false) }
    var replaceSlotIndex by remember { mutableStateOf<Int?>(null) }

    val state = rememberAlchemyDialogState(
        buildingInstanceId = buildingInstanceId, alchemySlots = alchemySlots, gameData = gameData,
        disciples = disciples, viewModel = viewModel
    )

    UnifiedGameDialog(
        onDismissRequest = onDismiss, title = "炼丹炉", mode = DialogMode.Half, scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AlchemyDialogBody(
                state = state, viewModel = viewModel, disciples = disciples, alchemyViewModel = alchemyViewModel,
                actions = AlchemyDialogActions(
                    onWorkerSlotEmptyClick = { showWorkerSelection = true },
                    onWorkerDismiss = { alchemyViewModel.removeWorker(state.buildingIndex) },
                    onWorkerSwap = { showWorkerSelection = true },
                    onAutoToggle = { alchemyViewModel.toggleAuto(state.buildingIndex) },
                    onReplace = {
                        replaceSlotIndex = state.slotIndex
                        selectedSlotIndex = state.slotIndex
                        showPillSelection = true
                    },
                    onIdleClick = {
                        selectedSlotIndex = state.slotIndex
                        showPillSelection = true
                    }
                )
            )
        }
    }

    if (showWorkerSelection) {
        AlchemyWorkerSelectionSection(
            state = state, viewModel = viewModel, alchemyViewModel = alchemyViewModel,
            onDismiss = { showWorkerSelection = false }, onWorkerAssigned = { showWorkerSelection = false }
        )
    }

    if (showPillSelection) {
        AlchemyPillSelectionGate(
            slotIdx = selectedSlotIndex,
            replacing = replaceSlotIndex != null,
            state = state, inputs = inputs, viewModel = viewModel,
            alchemyViewModel = alchemyViewModel,
            onDismiss = {
                showPillSelection = false
                selectedSlotIndex = null
                replaceSlotIndex = null
            }
        )
    }

}

/** 换丹/选丹弹窗门：替换臂取消当前炼制后再启动新配方 */
@Composable
private fun AlchemyPillSelectionGate(
    slotIdx: Int?,
    replacing: Boolean,
    state: AlchemyDialogState,
    inputs: AlchemyDialogInputs,
    viewModel: GameViewModel,
    alchemyViewModel: AlchemyViewModel,
    onDismiss: () -> Unit
) {
    slotIdx?.let { idx ->
        PillSelectionDialog(
            materials = inputs.materials, herbs = inputs.herbs,
            slotIndex = idx, workerDisciple = state.workerDisciple,
            viewModel = viewModel, alchemyViewModel = alchemyViewModel,
            onDismiss = onDismiss,
            onConfirmOverride = if (replacing) { { recipe ->
                alchemyViewModel.cancelAlchemy(idx)
                alchemyViewModel.startAlchemy(idx, recipe)
            } } else null
        )
    }
}

/** 炼丹炉派生状态计算 */
@Composable
private fun rememberAlchemyDialogState(
    buildingInstanceId: String,
    alchemySlots: List<AlchemySlot>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel
): AlchemyDialogState {
    val globalFurnaces = gameData?.placedBuildings?.filter { it.displayName == "炼丹炉" } ?: emptyList()
    val buildingIndex = globalFurnaces.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)

    val battleAndExplorationIds = remember(gameData) {
        if (gameData != null) {
            val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
                .toSet()
            val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }
                .toSet()
            battleIds + explorationIds
        } else emptySet()
    }
    val alchemySlotsState by viewModel.alchemySlots.collectAsStateWithLifecycle()
    val mySlot = alchemySlotsState.find { it.slotIndex == buildingIndex }
    val slotIndex = mySlot?.slotIndex ?: buildingIndex
    val assignedDiscipleId = mySlot?.assignedDiscipleId
    val discipleMap = disciples.associateBy { it.id }
    val workerDisciple = if (assignedDiscipleId.isNullOrEmpty()) null
        else discipleMap[assignedDiscipleId]
    val coroutineScope = rememberCoroutineScope()
    // Composition 内禁止读 StateFlow.value（不触发重组）；gameData 参数已由调用方 collect 派生
    val showAllEnabled = gameData?.showAllAvailableDisciples ?: false
    return AlchemyDialogState(
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

/** 炼丹炉主内容区 */
@Composable
private fun ColumnScope.AlchemyDialogBody(
    state: AlchemyDialogState,
    viewModel: GameViewModel,
    disciples: List<DiscipleAggregate>,
    alchemyViewModel: AlchemyViewModel,
    actions: AlchemyDialogActions
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Worker disciple section
        val workerDisciple = state.workerDisciple
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start)
            ) {
                Text(
                    text = "炼丹弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                ProfessionInfoButton(isAlchemy = true)
            }
            Spacer(modifier = Modifier.height(4.dp))
            ProfessionProgressSection(disciple = workerDisciple, isAlchemy = true)
            ProfessionLabel(level = workerDisciple?.alchemyLevel, isAlchemy = true)
            Spacer(modifier = Modifier.height(2.dp))
            DiscipleSlot(
                disciple = workerDisciple,
                showActions = true,
                onSlotClick = { workerDisciple?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it,
                    disciples)) } },
                onEmptySlotClick = actions.onWorkerSlotEmptyClick,
                onDismiss = actions.onWorkerDismiss,
                onSwap = actions.onWorkerSwap
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = GameColors.Border, thickness = 1.dp)

        AlchemySlotSection(
            state = state,
            alchemyViewModel = alchemyViewModel,
            onAutoToggle = actions.onAutoToggle,
            onReplace = actions.onReplace,
            onIdleClick = actions.onIdleClick
        )
    }
}

/** 炼丹槽位区：自动开关行 + 槽位条目 */
@Suppress("CyclomaticComplexMethod")
@Composable
private fun AlchemySlotSection(
    state: AlchemyDialogState,
    alchemyViewModel: AlchemyViewModel,
    onAutoToggle: () -> Unit,
    onReplace: () -> Unit,
    onIdleClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ALCHEMY_THEME.slotLabelPrefix,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        val autoEnabled = state.mySlot?.autoRestartEnabled ?: false
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (autoEnabled) GameColors.Gold else Color.Black)
                .clickable { onAutoToggle() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (autoEnabled) "自动炼丹:开" else "自动炼丹:关",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (autoEnabled) Color.Black else Color.White
            )
        }
    }

    val isIdle = state.mySlot?.status == AlchemySlotStatus.IDLE || state.mySlot == null
    val isWorking = state.mySlot?.status == AlchemySlotStatus.WORKING
    val remainingMonths = if (isWorking && state.gameData != null)
        state.mySlot.getRemainingMonths(state.gameData.gameYear, state.gameData.gameMonth) else 0

    ProductionSlotItem(
        theme = ALCHEMY_THEME,
        productName = state.mySlot?.pillName,
        isWorking = isWorking,
        isIdle = isIdle,
        remainingMonths = remainingMonths,
        index = state.slotIndex,
        productRarity = state.mySlot?.pillRarity ?: 1,
        totalDuration = state.mySlot?.duration ?: 1,
        isPill = true,
        successRate = state.mySlot?.successRate ?: 0.0,
        gamePhase = state.gameData?.gamePhase ?: 0,
        onCancel = if (isWorking) { { alchemyViewModel.cancelAlchemy(state.slotIndex) } } else null,
        onReplace = if (isWorking) { onReplace } else null,
        onClick = { if (isIdle) onIdleClick() }
    )
}

/** 炼丹弟子选择区块 */
@Composable
private fun AlchemyWorkerSelectionSection(
    state: AlchemyDialogState,
    viewModel: GameViewModel,
    alchemyViewModel: AlchemyViewModel,
    onDismiss: () -> Unit,
    onWorkerAssigned: () -> Unit
) {
    val workerTheme = remember {
        val gameData = state.gameData
        ProductionTheme(
            buildingId = "alchemy",
            displayName = "炼丹炉",
            elderTitle = "炼丹弟子",
            elderBonusInfo = ElderBonusInfo(
                title = "炼丹弟子",
                requiredAttribute = "炼丹",
                effectDescription = "负责炼丹槽位的工作，炼丹属性影响产出",
                bonusFormula = "炼丹越高，产出越高"
            ),
            coreAttributeName = "炼丹",
            coreAttributeColor = Color(0xFF9C27B0),
            defaultBorderColor = Color(0xFF9C27B0),
            workingStatusColor = GameColors.Info,
            selectedHighlightColor = GameColors.Gold,
            slotLabelPrefix = "炼丹槽",
            selectionDialogTitle = "选择炼丹弟子",
            startProductionText = "确认",
            elderSelectionTitle = "选择炼丹弟子",
            recommendAttributeText = "炼丹",
            getCoreAttributeValue = { it.pillRefining },
            getElderId = { it.alchemyElder },
            getDirectDisciples = { it.alchemyDisciples.filter { d -> d.sectId == (gameData?.activeSectId ?: "") } },
            elderSortComparator = compareByDescending<DiscipleAggregate> { it.pillRefining }
                .thenBy { it.realm }.thenByDescending { it.realmLayer },
            directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }
    ProductionElderSelectionDialog(
        theme = workerTheme,
        disciples = alchemyViewModel.getAvailableWorkers(),
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
                alchemyViewModel.assignWorker(state.buildingIndex, discipleId, d?.name ?: "")
            }
            onWorkerAssigned()
        },
        battleAndExplorationIds = state.battleAndExplorationIds,
    )
}

/** 点击炼丹配方：无弟子/职业等级不够弹提示，否则切换选中状态 */
private fun handlePillRecipeClick(
    recipe: PillRecipeDatabase.PillRecipe,
    workerDisciple: DiscipleAggregate?,
    alchemyViewModel: AlchemyViewModel,
    isSelected: Boolean,
    onSelectionChange: (PillRecipeDatabase.PillRecipe?) -> Unit
) {
    val workerLevel = workerDisciple?.alchemyLevel ?: 0
    when {
        workerDisciple == null -> alchemyViewModel.showNoWorkerHint()
        !ProfessionRules.canCraftTier(workerLevel, recipe.tier) -> alchemyViewModel.showTierLockedHint()
        isSelected -> onSelectionChange(null)
        else -> onSelectionChange(recipe)
    }
}

/** 配方 + 可制作状态 */
private data class PillRecipeWithStatus(
    val recipe: PillRecipeDatabase.PillRecipe,
    val canCraft: Boolean
)

/** 丹药配方可制作状态 */
private fun pillRecipesWithStatus(
    displayedRecipes: List<PillRecipeDatabase.PillRecipe>,
    herbs: List<Herb>
): List<PillRecipeWithStatus> = displayedRecipes.map { recipe ->
    val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
        val herbData = com.xianxia.sect.core.registry.HerbDatabase.getHerbById(materialId)
        val herbName = herbData?.name
        val herbRarity = herbData?.rarity ?: 1
        val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
        herb != null && herb.quantity >= requiredQuantity
    }
    PillRecipeWithStatus(recipe, canCraft)
}

/** 丹药配方排序：已关注优先 → 阶数降序 */
private fun sortPillRecipes(
    recipesWithStatus: List<PillRecipeWithStatus>,
    watchedKeys: Set<String>
): List<PillRecipeWithStatus> {
    val (craftable, uncraftable) = recipesWithStatus.partition { it.canCraft }
    val comparator =
        compareByDescending<PillRecipeWithStatus> {
            watchKey("pill", it.recipe.name) in watchedKeys
        }.thenByDescending { it.recipe.tier }
    return craftable.sortedWith(comparator) + uncraftable.sortedWith(comparator)
}

@Suppress("UnusedParameter")
@Composable
private fun PillSelectionDialog(
    materials: List<Material>,
    herbs: List<Herb>,
    slotIndex: Int,
    workerDisciple: DiscipleAggregate?,
    viewModel: GameViewModel,
    alchemyViewModel: AlchemyViewModel,
    onDismiss: () -> Unit,
    onConfirmOverride: ((PillRecipeDatabase.PillRecipe) -> Unit)? = null
) {
    var selectedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    val allRecipes = PillRecipeDatabase.getAllRecipes()

    // 详情弹窗需要同组全部品质变体
    val recipeGroups = remember(allRecipes) { allRecipes.groupBy { it.tier to it.name } }

    // 展示用：每组取 MEDIUM 品质作为代表
    val displayedRecipes = remember(recipeGroups) {
        recipeGroups.values.map { group ->
            group.firstOrNull { it.grade == PillGrade.MEDIUM } ?: group.first()
        }
    }
    val recipesWithStatus = remember(displayedRecipes, herbs) { pillRecipesWithStatus(displayedRecipes, herbs) }
    val watchedKeys by viewModel.watchedItemIds.collectAsStateWithLifecycle()
    val sortedRecipes = remember(recipesWithStatus, watchedKeys) { sortPillRecipes(recipesWithStatus, watchedKeys) }

    ProductionCommonDialog(
        title = ALCHEMY_THEME.selectionDialogTitle, theme = ALCHEMY_THEME,
        onDismiss = onDismiss, enableScroll = false
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PillRecipeGrid(
                sortedRecipes = sortedRecipes,
                selectedRecipeId = selectedRecipe?.id,
                workerDisciple = workerDisciple,
                alchemyViewModel = alchemyViewModel,
                watchedKeys = watchedKeys,
                onSelectionChange = { recipe -> selectedRecipe = recipe; clickedRecipe = recipe },
                onLongPress = { recipe -> clickedRecipe = recipe; showDetail = true }
            )

            PillSelectionConfirmBar(
                selectedRecipe = selectedRecipe, sortedRecipes = sortedRecipes, slotIndex = slotIndex,
                alchemyViewModel = alchemyViewModel, onConfirmOverride = onConfirmOverride, onDismiss = onDismiss
            )
        }
    }

    if (showDetail) {
        clickedRecipe?.let { recipe ->
            val allGrades = recipeGroups[recipe.tier to recipe.name] ?: listOf(recipe)
            PillDetailDialog(
                recipes = allGrades, herbs = herbs, viewModel = viewModel,
                onDismiss = { showDetail = false }
            )
        }
    }
}

/** 丹药配方网格 */
@Composable
private fun ColumnScope.PillRecipeGrid(
    sortedRecipes: List<PillRecipeWithStatus>,
    selectedRecipeId: String?,
    workerDisciple: DiscipleAggregate?,
    alchemyViewModel: AlchemyViewModel,
    watchedKeys: Set<String>,
    onSelectionChange: (PillRecipeDatabase.PillRecipe?) -> Unit,
    onLongPress: (PillRecipeDatabase.PillRecipe) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(60.dp),
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sortedRecipes, key = { it.recipe.id }, contentType = { "recipe" }) { recipeWithStatus ->
            val recipe = recipeWithStatus.recipe
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UnifiedItemCard(
                    data = ItemCardData(
                        name = recipe.name,
                        rarity = recipe.rarity,
                        isPill = true
                    ),
                    isSelected = selectedRecipeId == recipe.id,
                    isFollowed = watchKey("pill", recipe.name) in watchedKeys,
                    craftable = recipeWithStatus.canCraft,
                    showQuantity = false,
                    onClick = {
                        // 职业门禁提示框：无弟子/职业等级不够时点击配方不选中，弹提示
                        handlePillRecipeClick(
                            recipe = recipe,
                            workerDisciple = workerDisciple,
                            alchemyViewModel = alchemyViewModel,
                            isSelected = selectedRecipeId == recipe.id,
                            onSelectionChange = onSelectionChange
                        )
                    },
                    onLongPress = { onLongPress(recipe) }
                )
                Text(
                    text = "${recipe.duration}月",
                    fontSize = 9.sp,
                    color = Color.Black
                )
            }
        }
    }
}

/** 丹药选择确认栏 */
@Composable
private fun PillSelectionConfirmBar(
    selectedRecipe: PillRecipeDatabase.PillRecipe?,
    sortedRecipes: List<PillRecipeWithStatus>,
    slotIndex: Int,
    alchemyViewModel: AlchemyViewModel,
    onConfirmOverride: ((PillRecipeDatabase.PillRecipe) -> Unit)?,
    onDismiss: () -> Unit
) {
    Spacer(modifier = Modifier.height(12.dp))
    val hasEnoughMaterialsForSelected = sortedRecipes.find { it.recipe.id == selectedRecipe?.id }?.canCraft ?: false

    GameButton(
        text = ALCHEMY_THEME.startProductionText,
        onClick = {
            selectedRecipe?.let { recipe ->
                if (onConfirmOverride != null) {
                    onConfirmOverride(recipe)
                } else {
                    alchemyViewModel.startAlchemy(slotIndex, recipe)
                }
                onDismiss()
            }
        },
        enabled = selectedRecipe != null && hasEnoughMaterialsForSelected
    )
}

/** 丹药详情属性行 */
@Composable
private fun PillDetailDialog(
    recipes: List<PillRecipeDatabase.PillRecipe>,
    herbs: List<Herb>,
    viewModel: GameViewModel? = null,
    onDismiss: () -> Unit
) {
    val recipe = recipes.first()
    val low = recipes.minByOrNull { it.grade.ordinal }
    val high = recipes.maxByOrNull { it.grade.ordinal }

    fun intRange(getter: (PillRecipeDatabase.PillRecipe) -> Int): String {
        val min = low?.let(getter) ?: 0
        val max = high?.let(getter) ?: 0
        return if (min != max) "+${min}~+${max}" else "+$min"
    }

    fun pctRange(getter: (PillRecipeDatabase.PillRecipe) -> Double): String {
        val min = low?.let(getter) ?: 0.0
        val max = high?.let(getter) ?: 0.0
        val minPct = String.format(Locale.getDefault(), "%.1f", min * 100)
        val maxPct = String.format(Locale.getDefault(), "%.1f", max * 100)
        return if (min != max) "+${minPct}%~+${maxPct}%" else "+${minPct}%"
    }

    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = recipe.name
    ) {
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

                PillMaterialRequirementList(recipe = recipe, herbs = herbs)

                Text(text = "效果 (下品~上品):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "类型: ${recipe.category.displayName}", fontSize = 11.sp, color = Color.Black)

                    val statLines = pillDetailStatLines(recipe, low, high, ::intRange, ::pctRange)
                    statLines.forEach { line ->
                        Text(text = "${line.label} ${line.value}", fontSize = 11.sp, color = Color.Black)
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color.Black)

                // 关注按钮：viewModel 非空时显示（丹药按名称关注），位于底部操作区
                if (viewModel != null) {
                    val watchedKeys =
                        viewModel.watchedItemIds.collectAsStateWithLifecycle().value
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        WatchItemButton(
                            watchKey = watchKey("pill", recipe.name),
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
private fun PillMaterialRequirementList(
    recipe: PillRecipeDatabase.PillRecipe,
    herbs: List<Herb>
) {
    Text(text = "所需材料:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recipe.materials.forEach { (materialId, requiredQuantity) ->
            val herbData = com.xianxia.sect.core.registry.HerbDatabase.getHerbById(materialId)
            val herbName = herbData?.name
            val herbRarity = herbData?.rarity ?: 1
            val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
            val hasEnough = herb != null && herb.quantity >= requiredQuantity
            val materialName = herb?.name ?: herbData?.name ?: materialId
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = materialName,
                    fontSize = 11.sp,
                    color = if (hasEnough) Color.Black else Color(0xFFE74C3C)
                )
                Text(
                    text = "${GameUtils.formatNumber(herb?.quantity ?: 0)}/$requiredQuantity",
                    fontSize = 11.sp,
                    color = if (hasEnough) GameColors.Success else Color(0xFFE74C3C)
                )
            }
        }
    }
}
