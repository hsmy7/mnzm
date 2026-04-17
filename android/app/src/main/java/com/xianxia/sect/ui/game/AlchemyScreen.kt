package com.xianxia.sect.ui.game

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.ProductionViewModel
import java.util.Locale

@Composable
fun AlchemyDialog(
    alchemySlots: List<AlchemySlot>,
    materials: List<Material>,
    herbs: List<Herb>,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    val theme = ALCHEMY_THEME
    var showPillSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val alchemyElder = disciples.find { it.id == elderSlots.alchemyElder }
    val alchemyDisciples = elderSlots.alchemyDisciples

    ProductionCommonDialog(
        title = theme.displayName,
        theme = theme,
        onDismiss = onDismiss,
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProductionElderSection(
                theme = theme,
                elder = alchemyElder,
                onElderClick = { showElderSelection = true },
                onElderRemove = { productionViewModel.removeElder("alchemy") }
            )

            ProductionDirectDiscipleSection(
                theme = theme,
                directDisciples = alchemyDisciples,
                slotCount = alchemySlots.size,
                onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                onDirectDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("alchemy", index) }
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
                    text = theme.slotLabelPrefix,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                val autoAlchemyEnabled by productionViewModel.autoAlchemyEnabled.collectAsState()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (autoAlchemyEnabled) Color(0xFFFFD700) else Color(0xFF999999))
                        .clickable { productionViewModel.toggleAutoAlchemy() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (autoAlchemyEnabled) "自动炼丹:开" else "自动炼丹:关",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (autoAlchemyEnabled) Color.Black else Color.White
                    )
                }
            }

            val slotCount = alchemySlots.size
            (0 until slotCount).chunked(3).forEach { rowIndexes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowIndexes.forEach { index ->
                        val slot = alchemySlots.getOrNull(index)
                        val isIdle = slot?.status == AlchemySlotStatus.IDLE || slot == null
                        val isWorking = slot?.status == AlchemySlotStatus.WORKING
                        val remainingMonths = if (isWorking && gameData != null)
                            slot.getRemainingMonths(gameData.gameYear, gameData.gameMonth) else 0

                        ProductionSlotItem(
                            theme = theme,
                            productName = slot?.pillName,
                            isWorking = isWorking,
                            isIdle = isIdle,
                            remainingMonths = remainingMonths,
                            index = index,
                            onClick = {
                                if (isIdle) {
                                    selectedSlotIndex = index
                                    showPillSelection = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPillSelection) {
        selectedSlotIndex?.let { slotIdx ->
            PillSelectionDialog(
                materials = materials,
                herbs = herbs,
                slotIndex = slotIdx,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = {
                    showPillSelection = false
                    selectedSlotIndex = null
                }
            )
        }
    }

    if (showElderSelection) {
        ProductionElderSelectionDialog(
            theme = theme,
            disciples = disciples.filter { it.isAlive && it.realm <= 6 },
            currentElderId = elderSlots.alchemyElder,
            elderSlots = elderSlots,
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                productionViewModel.assignElder("alchemy", discipleId)
                showElderSelection = false
            }
        )
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        ProductionDirectDiscipleSelectionDialog(
            theme = theme,
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                productionViewModel.assignDirectDisciple("alchemy", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

    if (showReserveDiscipleDialog) {
        AlchemyReserveDiscipleDialogWrapper(
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { showReserveDiscipleDialog = false }
        )
    }
}

@Composable
private fun AlchemyReserveDiscipleDialogWrapper(
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    val reserveDisciples = productionViewModel.getAlchemyReserveDisciplesWithInfo()

    ProductionReserveDiscipleDialog(
        theme = ALCHEMY_THEME,
        reserveDisciples = reserveDisciples,
        onDismiss = onDismiss,
        onAddClick = { showAddDialog = true },
        onRemove = { productionViewModel.removeAlchemyReserveDisciple(it) }
    )

    if (showAddDialog) {
        ProductionAddReserveDiscipleDialog(
            theme = ALCHEMY_THEME,
            availableDisciples = productionViewModel.getAvailableDisciplesForAlchemyReserve(),
            onDismiss = { showAddDialog = false },
            onConfirm = { selectedIds ->
                productionViewModel.addAlchemyReserveDisciples(selectedIds)
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
    onDismiss: () -> Unit
) {
    var selectedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    ProductionCommonDialog(
        title = ALCHEMY_THEME.selectionDialogTitle,
        theme = ALCHEMY_THEME,
        onDismiss = onDismiss,
        enableScroll = false
    ) {
        val allRecipes = PillRecipeDatabase.getAllRecipes()

        data class RecipeWithStatus(
            val recipe: PillRecipeDatabase.PillRecipe,
            val canCraft: Boolean
        )

        val recipesWithStatus = remember(allRecipes, herbs) {
            allRecipes.map { recipe ->
                val canCraft = recipe.materials.all { (materialId, requiredQuantity) ->
                    val herbData = com.xianxia.sect.core.data.HerbDatabase.getHerbById(materialId)
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

                    val isSelected = selectedRecipe?.id == recipe.id

                    Box(modifier = Modifier.wrapContentSize(Alignment.Center).requiredSize(56.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFFFFF8E1) else if (hasEnoughMaterials) GameColors.PageBackground else GameColors.CardBackground)
                                .border(
                                    if (isSelected) 3.dp else 2.dp,
                                    if (isSelected) Color(0xFFFFD700) else rarityColor,
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

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-2).dp, y = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFFD700))
                                    .clickable { showDetail = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "查看",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
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
                        productionViewModel.startAlchemy(slotIndex, recipe)
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
            PillDetailDialog(
                recipe = recipe,
                herbs = herbs,
                onDismiss = { showDetail = false }
            )
        }
    }
}

@Composable
private fun PillDetailDialog(
    recipe: PillRecipeDatabase.PillRecipe,
    herbs: List<Herb>,
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
                        val herbData = com.xianxia.sect.core.data.HerbDatabase.getHerbById(materialId)
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

                Text(text = "效果:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "类型: ${recipe.category.displayName}", fontSize = 11.sp, color = Color(0xFF666666))

                    if (recipe.breakthroughChance > 0) {
                        Text(text = "突破成功率 +${String.format(Locale.getDefault(), "%.1f", recipe.breakthroughChance * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                        if (recipe.targetRealm > 0) {
                            Text(text = "目标境界: ${recipe.targetRealm}阶", fontSize = 11.sp, color = Color(0xFF666666))
                        }
                    }
                    if (recipe.cultivationSpeed > 1.0) {
                        Text(text = "修炼速度 +${String.format(Locale.getDefault(), "%.1f", (recipe.cultivationSpeed - 1.0) * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.cultivation > 0) {
                        Text(text = "修为 +${recipe.cultivation}", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.cultivationPercent > 0) {
                        Text(text = "修为 +${String.format(Locale.getDefault(), "%.1f", recipe.cultivationPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.physicalAttackPercent > 0) {
                        Text(text = "物理攻击 +${String.format(Locale.getDefault(), "%.1f", recipe.physicalAttackPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.magicAttackPercent > 0) {
                        Text(text = "法术攻击 +${String.format(Locale.getDefault(), "%.1f", recipe.magicAttackPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.physicalDefensePercent > 0) {
                        Text(text = "物理防御 +${String.format(Locale.getDefault(), "%.1f", recipe.physicalDefensePercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.magicDefensePercent > 0) {
                        Text(text = "法术防御 +${String.format(Locale.getDefault(), "%.1f", recipe.magicDefensePercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.hpPercent > 0) {
                        Text(text = "生命值 +${String.format(Locale.getDefault(), "%.1f", recipe.hpPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.mpPercent > 0) {
                        Text(text = "灵力容量 +${String.format(Locale.getDefault(), "%.1f", recipe.mpPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.speedPercent > 0) {
                        Text(text = "身法 +${String.format(Locale.getDefault(), "%.1f", recipe.speedPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.heal > 0) {
                        Text(text = "恢复生命值 ${recipe.heal}点", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.healPercent > 0) {
                        Text(text = "恢复生命值 ${String.format(Locale.getDefault(), "%.1f", recipe.healPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.healMaxHpPercent > 0) {
                        Text(text = "恢复 ${String.format(Locale.getDefault(), "%.1f", recipe.healMaxHpPercent * 100)}%最大生命值", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.mpRecoverMaxMpPercent > 0) {
                        Text(text = "恢复 ${String.format(Locale.getDefault(), "%.1f", recipe.mpRecoverMaxMpPercent * 100)}%最大灵力", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.skillExpPercent > 0) {
                        Text(text = "功法熟练度 +${String.format(Locale.getDefault(), "%.1f", recipe.skillExpPercent * 100)}%", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.extendLife > 0) {
                        Text(text = "延长寿命 ${recipe.extendLife}年", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.effectDuration > 0) {
                        Text(text = "持续: ${recipe.effectDuration}个月", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    if (recipe.battleCount > 0) {
                        Text(text = "持续: ${recipe.battleCount}场战斗", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                }

                Text(text = "描述:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = recipe.description, fontSize = 11.sp, color = Color(0xFF666666))
            }
        },
        confirmButton = {}
    )
}
