package com.xianxia.sect.ui.game

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
fun ForgeDialog(
    forgeSlots: List<ForgeSlot>,
    materials: List<Material>,
    gameData: GameData?,
    viewModel: GameViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val theme = FORGE_THEME
    var showEquipmentSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }

    val disciples by viewModel.discipleAggregates.collectAsState()
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val forgeElder = elderSlots.forgeElder?.let { viewModel.getElderDisciple(it) }
    val forgeDisciples = elderSlots.forgeDisciples

    var showReserveDiscipleDialog by remember { mutableStateOf(false) }
    var showAddReserveDialog by remember { mutableStateOf(false) }

    ProductionCommonDialog(
        title = theme.displayName,
        theme = theme,
        onDismiss = onDismiss,
        enableScroll = false,
        titleActions = {
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
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            ProductionElderSection(
                theme = theme,
                elder = forgeElder,
                onElderClick = { showElderSelection = true },
                onElderRemove = { viewModel.removeElder("forge") }
            )

            ProductionDirectDiscipleSection(
                theme = theme,
                directDisciples = forgeDisciples,
                onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                onDirectDiscipleRemove = { index -> viewModel.removeDirectDisciple("forge", index) }
            )

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
                    color = Color(0xFF666666)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.reserveButtonBackgroundColor)
                        .clickable { viewModel.autoForgeAllSlots() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "自动炼器",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.reserveButtonTextColor
                    )
                }
            }

            (0 until theme.slotCount).chunked(3).forEach { rowIndexes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowIndexes.forEach { index ->
                        val slot = forgeSlots.getOrNull(index)
                        val isIdle = slot?.status == ForgeSlotStatus.IDLE || slot == null
                        val isWorking = slot?.status == ForgeSlotStatus.WORKING
                        val remainingMonths = if (isWorking && gameData != null)
                            slot.getRemainingMonths(gameData.gameYear, gameData.gameMonth) else 0

                        ProductionSlotItem(
                            theme = theme,
                            productName = slot?.equipmentName,
                            isWorking = isWorking,
                            isIdle = isIdle,
                            remainingMonths = remainingMonths,
                            index = index,
                            onClick = {
                                if (isIdle) {
                                    selectedSlotIndex = index
                                    showEquipmentSelection = true
                                }
                            },
                            onRemove = { viewModel.clearForgeSlot(index) }
                        )
                    }
                }
            }
        }
    }

    if (showEquipmentSelection) {
        selectedSlotIndex?.let { slotIdx ->
            EquipmentSelectionDialog(
                materials = materials,
                slotIndex = slotIdx,
                viewModel = viewModel,
                onDismiss = {
                    showEquipmentSelection = false
                    selectedSlotIndex = null
                }
            )
        }
    }

    if (showElderSelection) {
        ProductionElderSelectionDialog(
            theme = theme,
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = elderSlots?.forgeElder,
            elderSlots = elderSlots ?: ElderSlots(),
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                viewModel.assignElder("forge", discipleId)
                showElderSelection = false
            }
        )
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        ProductionDirectDiscipleSelectionDialog(
            theme = theme,
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots ?: ElderSlots(),
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignDirectDisciple("forge", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

    if (showReserveDiscipleDialog) {
        ForgeReserveDiscipleDialogWrapper(
            viewModel = viewModel,
            onDismiss = { showReserveDiscipleDialog = false },
            onAddClick = { showAddReserveDialog = true }
        )
    }

    if (showAddReserveDialog) {
        ProductionAddReserveDiscipleDialog(
            theme = theme,
            availableDisciples = viewModel.getAvailableDisciplesForForgeReserve(),
            onDismiss = { showAddReserveDialog = false },
            onConfirm = { selectedIds ->
                viewModel.addForgeReserveDisciples(selectedIds)
                showAddReserveDialog = false
            }
        )
    }
}

@Composable
private fun ForgeReserveDiscipleDialogWrapper(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onAddClick: () -> Unit
) {
    val reserveDisciples by remember { derivedStateOf { viewModel.getForgeReserveDisciplesWithInfo() } }

    ProductionReserveDiscipleDialog(
        theme = FORGE_THEME,
        reserveDisciples = reserveDisciples,
        onDismiss = onDismiss,
        onAddClick = onAddClick,
        onRemove = { viewModel.removeForgeReserveDisciple(it) }
    )
}

@Composable
private fun EquipmentSelectionDialog(
    materials: List<Material>,
    slotIndex: Int,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<ForgeRecipeDatabase.ForgeRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    val allRecipes by viewModel.allForgeRecipes.collectAsState()

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
                    val materialData = com.xianxia.sect.core.data.BeastMaterialDatabase.getMaterialById(materialId)
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
                columns = GridCells.Adaptive(56.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedRecipes) { recipeWithStatus ->
                    val recipe = recipeWithStatus.recipe
                    val hasEnoughMaterials = recipeWithStatus.canCraft
                    val rarityColor = try {
                        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(recipe.rarity)))
                    } catch (e: Exception) {
                        Color(0xFF95a5a6)
                    }

                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (hasEnoughMaterials) GameColors.PageBackground else GameColors.CardBackground)
                                .border(
                                    2.dp,
                                    if (selectedRecipe?.id == recipe.id) FORGE_THEME.selectedHighlightColor else rarityColor,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    if (selectedRecipe?.id == recipe.id) {
                                        selectedRecipe = null
                                        clickedRecipe = null
                                    } else {
                                        selectedRecipe = recipe
                                        clickedRecipe = recipe
                                    }
                                }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = recipe.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasEnoughMaterials) Color.Black else Color(0xFF999999),
                                    maxLines = 2,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${recipe.duration}月",
                                    fontSize = 9.sp,
                                    color = if (hasEnoughMaterials) Color(0xFF666666) else Color(0xFF999999)
                                )
                            }
                        }

                        if (clickedRecipe?.id == recipe.id) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-2).dp, y = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(FORGE_THEME.selectedHighlightColor)
                                    .clickable { showDetail = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = "查看", fontSize = 8.sp, color = Color.White)
                            }
                        }
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
                        viewModel.startForge(slotIndex, recipe)
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(text = recipe.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "品阶: ${recipe.tier}阶", fontSize = 12.sp, color = Color(0xFF666666))
                    Text(text = "时间: ${recipe.duration}月", fontSize = 12.sp, color = Color(0xFF666666))
                }

                Text(text = "所需材料:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recipe.materials.forEach { (materialId, requiredQuantity) ->
                        val materialData = com.xianxia.sect.core.data.BeastMaterialDatabase.getMaterialById(materialId)
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
                    Text(text = "部位: ${recipe.type.displayName}", fontSize = 11.sp, color = Color(0xFF666666))

                    val template = com.xianxia.sect.core.data.EquipmentDatabase.getTemplateByName(recipe.name)
                    if (template != null) {
                        if (template.physicalAttack > 0) Text(text = "物理攻击 +${template.physicalAttack}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.magicAttack > 0) Text(text = "法术攻击 +${template.magicAttack}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.physicalDefense > 0) Text(text = "物理防御 +${template.physicalDefense}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.magicDefense > 0) Text(text = "法术防御 +${template.magicDefense}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.speed > 0) Text(text = "身法 +${template.speed}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.hp > 0) Text(text = "生命 +${template.hp}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.mp > 0) Text(text = "法力 +${template.mp}", fontSize = 11.sp, color = Color(0xFF666666))
                        if (template.critChance > 0) Text(text = "暴击率 +${String.format(Locale.getDefault(), "%.1f", template.critChance * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color(0xFF666666))
            }
        },
        confirmButton = {}
    )
}
