package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.getQualityColor
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ALCHEMY_THEME
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionReserveDiscipleDialog
import com.xianxia.sect.ui.game.ProductionAddReserveDiscipleDialog
import com.xianxia.sect.ui.game.ProductionCommonDialog
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import java.util.Locale

@Composable
fun AlchemyDialog(
    buildingIndex: Int = 0,
    alchemySlots: List<AlchemySlot>,
    materials: List<Material>,
    herbs: List<Herb>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val theme = ALCHEMY_THEME
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var showPillSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showWorkerSelection by remember { mutableStateOf(false) }
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }
    var replaceSlotIndex by remember { mutableStateOf<Int?>(null) }

    val alchemySlotsState by viewModel.alchemySlots.collectAsState()
    val mySlot = alchemySlotsState.find { it.slotIndex == buildingIndex }
    val slotIndex = mySlot?.slotIndex ?: buildingIndex
    val assignedDiscipleId = mySlot?.assignedDiscipleId
    val workerDisciple = if (assignedDiscipleId.isNullOrEmpty()) null
        else disciples.find { it.id == assignedDiscipleId }

    UnifiedGameDialog(
        onDismissRequest = { viewModel.closeCurrentDialog() },
        title = "炼丹炉 #${buildingIndex + 1}",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerActions = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.reserveButtonBackgroundColor)
                    .clickable { showReserveDiscipleDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "储备弟子",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.reserveButtonTextColor
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Worker disciple section
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "炼丹弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    DiscipleSlotWithActions(
                        disciple = workerDisciple,
                        onSlotClick = { selectedDiscipleDetail = workerDisciple },
                        onEmptySlotClick = { showWorkerSelection = true },
                        onDismiss = { alchemyViewModel.removeWorker(buildingIndex) },
                        onSwap = { showWorkerSelection = true }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = GameColors.Border,
                    thickness = 1.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = theme.slotLabelPrefix,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    val autoEnabled = mySlot?.autoRestartEnabled ?: false
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (autoEnabled) Color(0xFFFFD700) else Color.Black)
                            .clickable { alchemyViewModel.toggleAuto(buildingIndex) }
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

                val isIdle = mySlot?.status == AlchemySlotStatus.IDLE || mySlot == null
                val isWorking = mySlot?.status == AlchemySlotStatus.WORKING
                val remainingMonths = if (isWorking && gameData != null)
                    mySlot.getRemainingMonths(gameData.gameYear, gameData.gameMonth) else 0

                ProductionSlotItem(
                    theme = theme,
                    productName = mySlot?.pillName,
                    isWorking = isWorking,
                    isIdle = isIdle,
                    remainingMonths = remainingMonths,
                    index = slotIndex,
                    productRarity = mySlot?.pillRarity ?: 1,
                    totalDuration = mySlot?.duration ?: 1,
                    isPill = true,
                    onCancel = if (isWorking) { { alchemyViewModel.cancelAlchemy(slotIndex) } } else null,
                    onReplace = if (isWorking) { {
                        replaceSlotIndex = slotIndex
                        selectedSlotIndex = slotIndex
                        showPillSelection = true
                    } } else null,
                    onClick = {
                        if (isIdle) {
                            selectedSlotIndex = slotIndex
                            showPillSelection = true
                        }
                    }
                )
            }
        }
    }

    if (showWorkerSelection) {
        val workerTheme = remember {
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
                workingStatusColor = Color(0xFF2196F3),
                selectedHighlightColor = Color(0xFFFFD700),
                reserveButtonBackgroundColor = GameColors.ButtonBackground,
                reserveButtonTextColor = Color.Black,
                slotLabelPrefix = "炼丹槽",
                selectionDialogTitle = "选择炼丹弟子",
                startProductionText = "确认",
                elderSelectionTitle = "选择炼丹弟子",
                recommendAttributeText = "炼丹",
                getCoreAttributeValue = { it.pillRefining },
                getElderId = { it.alchemyElder },
                getDirectDisciples = { it.alchemyDisciples },
                elderSortComparator = compareByDescending<DiscipleAggregate> { it.pillRefining }
                    .thenBy { it.realm }.thenByDescending { it.realmLayer },
                directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                    .thenByDescending { it.realmLayer }
            )
        }
        ProductionElderSelectionDialog(
            theme = workerTheme,
            disciples = alchemyViewModel.getAvailableWorkers(),
            currentElderId = assignedDiscipleId,
            elderSlots = gameData?.elderSlots ?: ElderSlots(),
            onDismiss = { showWorkerSelection = false },
            onSelect = { discipleId ->
                val d = disciples.find { it.id == discipleId }
                alchemyViewModel.assignWorker(buildingIndex, discipleId, d?.name ?: "")
                showWorkerSelection = false
            }
        )
    }

    if (showPillSelection) {
        selectedSlotIndex?.let { slotIdx ->
            val isReplacing = replaceSlotIndex != null
            PillSelectionDialog(
                materials = materials,
                herbs = herbs,
                slotIndex = slotIdx,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                alchemyViewModel = alchemyViewModel,
                onDismiss = {
                    showPillSelection = false
                    selectedSlotIndex = null
                    replaceSlotIndex = null
                },
                onConfirmOverride = if (isReplacing) { { recipe ->
                    alchemyViewModel.cancelAlchemy(slotIdx)
                    alchemyViewModel.startAlchemy(slotIdx, recipe)
                } } else null
            )
        }
    }

    if (showReserveDiscipleDialog) {
        AlchemyReserveDiscipleDialogWrapper(
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            alchemyViewModel = alchemyViewModel,
            onDismiss = { showReserveDiscipleDialog = false }
        )
    }

    selectedDiscipleDetail?.let { disciple ->
        DiscipleDetailDialog(
            disciple = disciple,
            allDisciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
        )
    }
}

@Composable
private fun AlchemyReserveDiscipleDialogWrapper(
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    onDismiss: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val reserveDisciples = alchemyViewModel.getAlchemyReserveDisciplesWithInfo()

    ProductionReserveDiscipleDialog(
        theme = ALCHEMY_THEME,
        reserveDisciples = reserveDisciples,
        onDismiss = onDismiss,
        onAddClick = { showAddDialog = true },
        onRemove = { alchemyViewModel.removeAlchemyReserveDisciple(it) }
    )

    if (showAddDialog) {
        ProductionAddReserveDiscipleDialog(
            theme = ALCHEMY_THEME,
            availableDisciples = alchemyViewModel.getAvailableDisciplesForAlchemyReserve(),
            onDismiss = { showAddDialog = false },
            onConfirm = { selectedIds ->
                alchemyViewModel.addAlchemyReserveDisciples(selectedIds)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun PillSelectionDialog(
    materials: List<Material>,
    herbs: List<Herb>,
    slotIndex: Int,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    onDismiss: () -> Unit,
    onConfirmOverride: ((PillRecipeDatabase.PillRecipe) -> Unit)? = null
) {
    var selectedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    val allRecipes = PillRecipeDatabase.getAllRecipes()

    // 按 (tier, pillType) 分组，每组包含 3 个品质变体
    val recipeGroups = remember(allRecipes) {
        allRecipes.groupBy { it.tier to it.name }
    }

    // 展示用：每组取 MEDIUM 品质作为代表
    val displayedRecipes = remember(recipeGroups) {
        recipeGroups.values.map { group ->
            group.firstOrNull { it.grade == PillGrade.MEDIUM } ?: group.first()
        }
    }

    ProductionCommonDialog(
        title = ALCHEMY_THEME.selectionDialogTitle,
        theme = ALCHEMY_THEME,
        onDismiss = onDismiss,
        enableScroll = false
    ) {

        data class RecipeWithStatus(
            val recipe: PillRecipeDatabase.PillRecipe,
            val canCraft: Boolean
        )

        val recipesWithStatus = remember(displayedRecipes, herbs) {
            displayedRecipes.map { recipe ->
                val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
                    val herbData = com.xianxia.sect.core.registry.HerbDatabase.getHerbById(materialId)
                    val herbName = herbData?.name
                    val herbRarity = herbData?.rarity ?: 1
                    val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
                    herb != null && herb.quantity >= requiredQuantity
                }
                RecipeWithStatus(recipe, canCraft)
            }
        }

        val sortedRecipes = remember(recipesWithStatus) {
            val (craftable, uncraftable) = recipesWithStatus.partition { it.canCraft }
            val comparator = compareByDescending<RecipeWithStatus> { it.recipe.tier }
            craftable.sortedWith(comparator) + uncraftable.sortedWith(comparator)
        }

        Column(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(60.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedRecipes) { recipeWithStatus ->
                    val recipe = recipeWithStatus.recipe
                    val hasEnoughMaterials = recipeWithStatus.canCraft
                    val isSelected = selectedRecipe?.id == recipe.id

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UnifiedItemCard(
                            data = ItemCardData(
                                name = recipe.name,
                                rarity = recipe.rarity,
                                isPill = true
                            ),
                            isSelected = isSelected,
                            showViewButton = true,
                            craftable = hasEnoughMaterials,
                            showQuantity = false,
                            onClick = {
                                if (selectedRecipe?.id == recipe.id) {
                                    selectedRecipe = null
                                    clickedRecipe = null
                                } else {
                                    selectedRecipe = recipe
                                    clickedRecipe = recipe
                                }
                            },
                            onViewDetail = { showDetail = true }
                        )
                        Text(
                            text = "${recipe.duration}月",
                            fontSize = 9.sp,
                            color = Color.Black
                        )
                        Text(
                            text = PillRecipeDatabase.getTierName(recipe.tier),
                            fontSize = 9.sp,
                            color = Color.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val selectedRecipeStatus = sortedRecipes.find { it.recipe.id == selectedRecipe?.id }
            val hasEnoughMaterialsForSelected = selectedRecipeStatus?.canCraft ?: false

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
    }

    if (showDetail) {
        clickedRecipe?.let { recipe ->
            val allGrades = recipeGroups[recipe.tier to recipe.name] ?: listOf(recipe)
            PillDetailDialog(
                recipes = allGrades,
                herbs = herbs,
                onDismiss = { showDetail = false }
            )
        }
    }
}

@Composable
private fun PillDetailDialog(
    recipes: List<PillRecipeDatabase.PillRecipe>,
    herbs: List<Herb>,
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

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = recipe.name,
        mode = DialogMode.Half
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "品阶: ${recipe.tier}阶", fontSize = 12.sp, color = Color.Black)
                    Text(text = "时间: ${recipe.duration}月", fontSize = 12.sp, color = Color.Black)
                }

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
                            Text(text = materialName, fontSize = 11.sp, color = if (hasEnough) Color.Black else Color(0xFFE74C3C))
                            Text(text = "${herb?.quantity ?: 0}/$requiredQuantity", fontSize = 11.sp, color = if (hasEnough) Color(0xFF4CAF50) else Color(0xFFE74C3C))
                        }
                    }
                }

                Text(text = "效果 (下品~上品):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "类型: ${recipe.category.displayName}", fontSize = 11.sp, color = Color.Black)

                    if (recipe.breakthroughChance > 0) {
                        Text(text = "突破成功率 ${pctRange { it.breakthroughChance }}", fontSize = 11.sp, color = Color.Black)
                        if (recipe.targetRealm > 0) {
                            Text(text = "目标境界: ${recipe.targetRealm}阶", fontSize = 11.sp, color = Color.Black)
                        }
                    }
                    if (recipe.cultivationSpeedPercent > 0) {
                        Text(text = "修炼速度 ${pctRange { it.cultivationSpeedPercent }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.cultivationAdd > 0) {
                        Text(text = "修为 ${intRange { it.cultivationAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.physicalAttackAdd > 0) {
                        Text(text = "物理攻击 ${intRange { it.physicalAttackAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.magicAttackAdd > 0) {
                        Text(text = "法术攻击 ${intRange { it.magicAttackAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.physicalDefenseAdd > 0) {
                        Text(text = "物理防御 ${intRange { it.physicalDefenseAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.magicDefenseAdd > 0) {
                        Text(text = "法术防御 ${intRange { it.magicDefenseAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.hpAdd > 0) {
                        Text(text = "生命值 ${intRange { it.hpAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.mpAdd > 0) {
                        Text(text = "灵力容量 ${intRange { it.mpAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.speedAdd > 0) {
                        Text(text = "身法 ${intRange { it.speedAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.critRateAdd > 0) {
                        Text(text = "暴击率 ${pctRange { it.critRateAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.critEffectAdd > 0) {
                        Text(text = "暴击效果 ${pctRange { it.critEffectAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.skillExpAdd > 0) {
                        Text(text = "功法熟练度 ${intRange { it.skillExpAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.nurtureAdd > 0) {
                        Text(text = "孕育值 ${intRange { it.nurtureAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.extendLife > 0) {
                        Text(text = "延长寿命 ${intRange { it.extendLife }}年", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.intelligenceAdd > 0) {
                        Text(text = "悟性 ${intRange { it.intelligenceAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.charmAdd > 0) {
                        Text(text = "魅力 ${intRange { it.charmAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.loyaltyAdd > 0) {
                        Text(text = "忠诚 ${intRange { it.loyaltyAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.comprehensionAdd > 0) {
                        Text(text = "领悟 ${intRange { it.comprehensionAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.artifactRefiningAdd > 0) {
                        Text(text = "炼器 ${intRange { it.artifactRefiningAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.pillRefiningAdd > 0) {
                        Text(text = "炼丹 ${intRange { it.pillRefiningAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.spiritPlantingAdd > 0) {
                        Text(text = "种植 ${intRange { it.spiritPlantingAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.teachingAdd > 0) {
                        Text(text = "传授 ${intRange { it.teachingAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                    if (recipe.moralityAdd > 0) {
                        Text(text = "道德 ${intRange { it.moralityAdd }}", fontSize = 11.sp, color = Color.Black)
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color.Black)
                }
            }
    }
}
