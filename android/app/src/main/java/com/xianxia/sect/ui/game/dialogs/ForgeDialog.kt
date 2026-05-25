package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.R
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.UnifiedDiscipleSlot
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.FORGE_THEME
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionReserveDiscipleDialog
import com.xianxia.sect.ui.game.ProductionCommonDialog
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import java.util.Locale

@Composable
fun ForgeDialog(
    buildingInstanceId: String = "",
    forgeSlots: List<ForgeSlot>,
    materials: List<Material>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    forgeViewModel: ForgeViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val theme = FORGE_THEME
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var showEquipmentSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showWorkerSelection by remember { mutableStateOf(false) }
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }
    var showAddReserveDialog by remember { mutableStateOf(false) }
    var replaceSlotIndex by remember { mutableStateOf<Int?>(null) }

    val globalForges = gameData?.placedBuildings?.filter { it.displayName == "锻造坊" } ?: emptyList()
    val buildingIndex = globalForges.indexOfFirst { it.instanceId == buildingInstanceId }.coerceAtLeast(0)
    val forgeSlotsState by viewModel.forgeSlots.collectAsState()
    val mySlot = forgeSlotsState.find { it.slotIndex == buildingIndex }
    val slotIndex = mySlot?.slotIndex ?: buildingIndex
    val assignedDiscipleId = mySlot?.assignedDiscipleId
    val workerDisciple = if (assignedDiscipleId.isNullOrEmpty()) null
        else disciples.find { it.id == assignedDiscipleId }

    UnifiedGameDialog(
        onDismissRequest = { viewModel.closeCurrentDialog() },
        title = "锻造坊",
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
                            text = "锻造弟子",
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
                        onDismiss = { forgeViewModel.removeWorker(buildingIndex) },
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
                        text = theme.slotLabelPrefix + "位",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    val isAutoEnabled = mySlot?.autoRestartEnabled ?: false
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isAutoEnabled) Color(0xFFFFD700) else Color.Black)
                            .clickable { forgeViewModel.toggleAuto(buildingIndex) }
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

                val isIdle = mySlot?.status == ForgeSlotStatus.IDLE || mySlot == null
                val isWorking = mySlot?.status == ForgeSlotStatus.WORKING
                val remainingMonths = if (isWorking && gameData != null)
                    mySlot.getRemainingMonths(gameData.gameYear, gameData.gameMonth) else 0

                ProductionSlotItem(
                    theme = theme,
                    productName = mySlot?.equipmentName,
                    isWorking = isWorking,
                    isIdle = isIdle,
                    remainingMonths = remainingMonths,
                    index = slotIndex,
                    productRarity = mySlot?.equipmentRarity ?: 1,
                    totalDuration = mySlot?.duration ?: 1,
                    successRate = mySlot?.successRate ?: 0.0,
                    gameDay = gameData?.gameDay ?: 1,
                    onCancel = if (isWorking) { { forgeViewModel.cancelForge(slotIndex) } } else null,
                    onReplace = if (isWorking) { {
                        replaceSlotIndex = slotIndex
                        selectedSlotIndex = slotIndex
                        showEquipmentSelection = true
                    } } else null,
                    onClick = {
                        if (isIdle) {
                            selectedSlotIndex = slotIndex
                            showEquipmentSelection = true
                        }
                    }
                )
            }
        }
    }

    if (showWorkerSelection) {
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
                coreAttributeColor = Color(0xFF4CAF50),
                defaultBorderColor = Color(0xFFFF9800),
                workingStatusColor = Color(0xFFFF9800),
                selectedHighlightColor = Color(0xFFFF9800),
                reserveButtonBackgroundColor = Color(0xFFFF9800),
                reserveButtonTextColor = Color.White,
                slotLabelPrefix = "炼器槽",
                selectionDialogTitle = "选择锻造弟子",
                startProductionText = "确认",
                elderSelectionTitle = "选择锻造弟子",
                recommendAttributeText = "炼器",
                getCoreAttributeValue = { it.artifactRefining },
                getElderId = { it.forgeElder },
                getDirectDisciples = { it.forgeDisciples.filter { d -> d.sectId == (gameData?.activeSectId ?: "") } },
                elderSortComparator = compareByDescending<DiscipleAggregate> { it.artifactRefining }
                    .thenBy { it.realm }.thenByDescending { it.realmLayer },
                directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                    .thenByDescending { it.realmLayer }
            )
        }
        ProductionElderSelectionDialog(
            theme = workerTheme,
            disciples = forgeViewModel.getAvailableWorkers(),
            currentElderId = assignedDiscipleId,
            elderSlots = gameData?.elderSlots ?: ElderSlots(),
            onDismiss = { showWorkerSelection = false },
            onSelect = { discipleId ->
                val d = disciples.find { it.id == discipleId }
                forgeViewModel.assignWorker(buildingIndex, discipleId, d?.name ?: "")
                showWorkerSelection = false
            }
        )
    }

    if (showEquipmentSelection) {
        selectedSlotIndex?.let { slotIdx ->
            val isReplacing = replaceSlotIndex != null
            EquipmentSelectionDialog(
                materials = materials,
                slotIndex = slotIdx,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                forgeViewModel = forgeViewModel,
                onDismiss = {
                    showEquipmentSelection = false
                    selectedSlotIndex = null
                    replaceSlotIndex = null
                },
                onConfirmOverride = if (isReplacing) { { recipe ->
                    forgeViewModel.cancelForge(slotIdx)
                    forgeViewModel.startForge(slotIdx, recipe)
                } } else null
            )
        }
    }

    if (showReserveDiscipleDialog) {
        ForgeReserveDiscipleDialogWrapper(
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            forgeViewModel = forgeViewModel,
            onDismiss = { showReserveDiscipleDialog = false },
            onAddClick = { showAddReserveDialog = true }
        )
    }

    if (showAddReserveDialog) {
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(title = "添加储备弟子（推荐炼器）"),
            disciples = forgeViewModel.getAvailableDisciplesForForgeReserve(),
            onDismiss = { showAddReserveDialog = false },
            onConfirm = { selected ->
                forgeViewModel.addForgeReserveDisciples(selected.map { it.id })
                showAddReserveDialog = false
            }
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
private fun ForgeReserveDiscipleDialogWrapper(
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    forgeViewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit
) {
    val reserveDisciples by remember { derivedStateOf { forgeViewModel.getForgeReserveDisciplesWithInfo() } }

    ProductionReserveDiscipleDialog(
        theme = FORGE_THEME,
        reserveDisciples = reserveDisciples,
        onDismiss = onDismiss,
        onAddClick = onAddClick,
        onRemove = { forgeViewModel.removeForgeReserveDisciple(it) }
    )
}

@Composable
private fun EquipmentSelectionDialog(
    materials: List<Material>,
    slotIndex: Int,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    forgeViewModel: ForgeViewModel,
    onDismiss: () -> Unit,
    onConfirmOverride: ((ForgeRecipeDatabase.ForgeRecipe) -> Unit)? = null
) {
    var selectedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    val allRecipes by forgeViewModel.allForgeRecipes.collectAsState()

    ProductionCommonDialog(
        title = FORGE_THEME.selectionDialogTitle,
        theme = FORGE_THEME,
        onDismiss = onDismiss,
        enableScroll = false
    ) {
        data class RecipeWithStatus(
            val recipe: ForgeRecipeDatabase.ForgeRecipe,
            val canCraft: Boolean
        )

        val materialIndex = remember(materials) {
            materials.groupBy { it.name to it.rarity }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }
        }

        val recipesWithStatus = remember(allRecipes, materialIndex) {
            allRecipes.map { recipe ->
                val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
                    val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)
                    materialData != null && run {
                        val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                        available >= requiredQuantity
                    }
                }
                RecipeWithStatus(recipe, canCraft)
            }
        }

        val sortedRecipes = remember(recipesWithStatus) {
            val (craftable, uncraftable) = recipesWithStatus.partition { it.canCraft }
            craftable.sortedByDescending { it.recipe.rarity } + uncraftable
        }

        Column(modifier = Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(60.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedRecipes, key = { it.recipe.id }) { recipeWithStatus ->
                    val recipe = recipeWithStatus.recipe
                    val hasEnoughMaterials = recipeWithStatus.canCraft
                    val isSelected = selectedRecipe?.id == recipe.id

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UnifiedItemCard(
                            data = ItemCardData(
                                name = recipe.name,
                                rarity = recipe.rarity
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val selectedRecipeStatus = sortedRecipes.find { it.recipe.id == selectedRecipe?.id }
            val hasEnoughMaterialsForSelected = selectedRecipeStatus?.canCraft ?: false

            GameButton(
                text = FORGE_THEME.startProductionText,
                onClick = {
                    selectedRecipe?.let { recipe ->
                        if (onConfirmOverride != null) {
                            onConfirmOverride(recipe)
                        } else {
                            forgeViewModel.startForge(slotIndex, recipe)
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
            EquipmentDetailDialog(
                recipe = recipe,
                materials = materials,
                onDismiss = { showDetail = false }
            )
        }
    }
}

@Composable
private fun EquipmentDetailDialog(
    recipe: ForgeRecipeDatabase.ForgeRecipe,
    materials: List<Material>,
    onDismiss: () -> Unit
) {
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
                        val materialData = com.xianxia.sect.core.registry.BeastMaterialDatabase.getMaterialById(materialId)
                        val materialName = materialData?.name
                        val materialRarity = materialData?.rarity ?: 1
                        val material = materials.find { it.name == materialName && it.rarity == materialRarity }
                        val hasEnough = material != null && material.quantity >= requiredQuantity
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = material?.name ?: materialName ?: materialId, fontSize = 11.sp, color = if (hasEnough) Color.Black else Color(0xFFE74C3C))
                            Text(text = "${material?.quantity ?: 0}/$requiredQuantity", fontSize = 11.sp, color = if (hasEnough) Color(0xFF4CAF50) else Color(0xFFE74C3C))
                        }
                    }
                }

                Text(text = "属性加成:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "部位: ${recipe.type.displayName}", fontSize = 11.sp, color = Color.Black)

                    val template = com.xianxia.sect.core.registry.EquipmentDatabase.getTemplateByName(recipe.name)
                    if (template != null) {
                        if (template.physicalAttack > 0) Text(text = "物理攻击 +${template.physicalAttack}", fontSize = 11.sp, color = Color.Black)
                        if (template.magicAttack > 0) Text(text = "法术攻击 +${template.magicAttack}", fontSize = 11.sp, color = Color.Black)
                        if (template.physicalDefense > 0) Text(text = "物理防御 +${template.physicalDefense}", fontSize = 11.sp, color = Color.Black)
                        if (template.magicDefense > 0) Text(text = "法术防御 +${template.magicDefense}", fontSize = 11.sp, color = Color.Black)
                        if (template.speed > 0) Text(text = "身法 +${template.speed}", fontSize = 11.sp, color = Color.Black)
                        if (template.hp > 0) Text(text = "生命 +${template.hp}", fontSize = 11.sp, color = Color.Black)
                        if (template.mp > 0) Text(text = "法力 +${template.mp}", fontSize = 11.sp, color = Color.Black)
                        if (template.critChance > 0) Text(text = "暴击率 +${String.format(Locale.getDefault(), "%.1f", template.critChance * 100)}%", fontSize = 11.sp, color = Color.Black)
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color.Black)
                }
            }
    }
}
