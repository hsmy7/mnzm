package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun AlchemyDialog(
    alchemySlots: List<AlchemySlot>,
    materials: List<Material>,
    herbs: List<Herb>,
    gameData: GameData?,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    colors: com.xianxia.sect.ui.theme.XianxiaColorScheme,
    onDismiss: () -> Unit
) {
    var showPillSelection by remember { mutableStateOf(false) }
    var selectedSlotIndex by remember { mutableStateOf<Int?>(null) }
    var showElderSelection by remember { mutableStateOf(false) }
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }
    var showReserveDiscipleDialog by remember { mutableStateOf(false) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val alchemyElder = disciples.find { it.id == elderSlots.alchemyElder }
    val alchemyDisciples = elderSlots.alchemyDisciples

    CommonDialog(
        title = "丹鼎殿",
        onDismiss = onDismiss,
        titleActions = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF9C27B0))
                    .clickable { showReserveDiscipleDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "储备弟子",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AlchemyElderSection(
                elder = alchemyElder,
                onElderClick = { showElderSelection = true },
                onElderRemove = { viewModel.removeElder("alchemy") }
            )
            
            AlchemyDirectDiscipleSection(
                directDisciples = alchemyDisciples,
                onDirectDiscipleClick = { index -> showDirectDiscipleSelection = index },
                onDirectDiscipleRemove = { index -> viewModel.removeDirectDisciple("alchemy", index) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = GameColors.Border,
                thickness = 1.dp
            )

            Text(
                text = "炼丹槽",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            (0 until 3).chunked(3).forEach { rowIndexes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowIndexes.forEach { index ->
                        val slot = alchemySlots.getOrNull(index)
                        AlchemySlotItem(
                            slot = slot,
                            index = index,
                            gameData = gameData,
                            onClick = {
                                if (slot?.status == AlchemySlotStatus.IDLE || slot == null) {
                                    selectedSlotIndex = index
                                    showPillSelection = true
                                }
                            },
                            onRemove = {
                                viewModel.clearAlchemySlot(index)
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
                onDismiss = {
                    showPillSelection = false
                    selectedSlotIndex = null
                }
            )
        }
    }

    if (showElderSelection) {
        AlchemyElderSelectionDialog(
            disciples = disciples.filter { it.isAlive && it.realm <= 5 },
            currentElderId = elderSlots.alchemyElder,
            elderSlots = elderSlots,
            onDismiss = { showElderSelection = false },
            onSelect = { discipleId ->
                viewModel.assignElder("alchemy", discipleId)
                showElderSelection = false
            }
        )
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        AlchemyDirectDiscipleSelectionDialog(
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                viewModel.assignDirectDisciple("alchemy", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

    if (showReserveDiscipleDialog) {
        AlchemyReserveDiscipleDialog(
            disciples = disciples,
            viewModel = viewModel,
            onDismiss = { showReserveDiscipleDialog = false }
        )
    }
}

@Composable
private fun AlchemySlotItem(
    slot: AlchemySlot?,
    index: Int,
    gameData: GameData?,
    onClick: () -> Unit,
    onRemove: () -> Unit = {}
) {
    val isIdle = slot?.status == AlchemySlotStatus.IDLE || slot == null
    val isWorking = slot?.status == AlchemySlotStatus.WORKING

    val statusColor = when {
        isWorking -> Color(0xFF2196F3)
        else -> GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "炼丹槽 ${index + 1}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            when {
                isWorking && slot != null && gameData != null -> {
                    val remainingMonths = slot.getRemainingMonths(gameData.gameYear, gameData.gameMonth)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = slot.pillName ?: "",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${remainingMonths}月",
                            fontSize = 10.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                else -> {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        }
        if (!isIdle) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "移除",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun PillSelectionDialog(
    materials: List<Material>,
    herbs: List<Herb>,
    slotIndex: Int,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var clickedRecipe by remember { mutableStateOf<PillRecipeDatabase.PillRecipe?>(null) }
    var showDetail by remember { mutableStateOf(false) }

    CommonDialog(
        title = "选择丹药",
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
        
        Column {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(300.dp)
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

                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
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
                text = "开始炼制",
                onClick = {
                    selectedRecipe?.let { recipe ->
                        viewModel.startAlchemy(slotIndex, recipe)
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
    val rarityColor = try {
        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(recipe.rarity)))
    } catch (e: Exception) {
        Color(0xFF95a5a6)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = recipe.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "品阶: ${recipe.tier}阶",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "时间: ${recipe.duration}月",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }

                Text(
                    text = "所需材料:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                            Text(
                                text = materialName,
                                fontSize = 11.sp,
                                color = if (hasEnough) Color.Black else Color(0xFFE74C3C)
                            )
                            Text(
                                text = "${herb?.quantity ?: 0}/$requiredQuantity",
                                fontSize = 11.sp,
                                color = if (hasEnough) Color(0xFF4CAF50) else Color(0xFFE74C3C)
                            )
                        }
                    }
                }

                Text(
                    text = "效果:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 丹药类型
                    Text(
                        text = "类型: ${recipe.category.displayName}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )

                    // 突破丹药
                    if (recipe.breakthroughChance > 0) {
                        Text(
                            text = "突破成功率 +${String.format("%.1f", recipe.breakthroughChance * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                        if (recipe.targetRealm > 0) {
                            Text(
                                text = "目标境界: ${recipe.targetRealm}阶",
                                fontSize = 11.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }

                    // 修炼速度
                    if (recipe.cultivationSpeed > 1.0) {
                        Text(
                            text = "修炼速度 +${String.format("%.1f", (recipe.cultivationSpeed - 1.0) * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 固定修为值
                    if (recipe.cultivation > 0) {
                        Text(
                            text = "修为 +${recipe.cultivation}",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 修为百分比
                    if (recipe.cultivationPercent > 0) {
                        Text(
                            text = "修为 +${String.format("%.1f", recipe.cultivationPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 战斗属性加成
                    if (recipe.physicalAttackPercent > 0) {
                        Text(
                            text = "物理攻击 +${String.format("%.1f", recipe.physicalAttackPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.magicAttackPercent > 0) {
                        Text(
                            text = "法术攻击 +${String.format("%.1f", recipe.magicAttackPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.physicalDefensePercent > 0) {
                        Text(
                            text = "物理防御 +${String.format("%.1f", recipe.physicalDefensePercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.magicDefensePercent > 0) {
                        Text(
                            text = "法术防御 +${String.format("%.1f", recipe.magicDefensePercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.hpPercent > 0) {
                        Text(
                            text = "生命值 +${String.format("%.1f", recipe.hpPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.mpPercent > 0) {
                        Text(
                            text = "灵力容量 +${String.format("%.1f", recipe.mpPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.speedPercent > 0) {
                        Text(
                            text = "身法 +${String.format("%.1f", recipe.speedPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 治疗效果
                    if (recipe.heal > 0) {
                        Text(
                            text = "恢复生命值 ${recipe.heal}点",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.healPercent > 0) {
                        Text(
                            text = "恢复生命值 ${String.format("%.1f", recipe.healPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.healMaxHpPercent > 0) {
                        Text(
                            text = "恢复 ${String.format("%.1f", recipe.healMaxHpPercent * 100)}%最大生命值",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    if (recipe.mpRecoverMaxMpPercent > 0) {
                        Text(
                            text = "恢复 ${String.format("%.1f", recipe.mpRecoverMaxMpPercent * 100)}%最大灵力",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 功法熟练度
                    if (recipe.skillExpPercent > 0) {
                        Text(
                            text = "功法熟练度 +${String.format("%.1f", recipe.skillExpPercent * 100)}%",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 寿命
                    if (recipe.extendLife > 0) {
                        Text(
                            text = "延长寿命 ${recipe.extendLife}年",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 持续时间
                    if (recipe.effectDuration > 0) {
                        Text(
                            text = "持续: ${recipe.effectDuration}个月",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    // 战斗次数
                    if (recipe.battleCount > 0) {
                        Text(
                            text = "持续: ${recipe.battleCount}场战斗",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }

                Text(
                    text = "描述:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = recipe.description,
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    enableScroll: Boolean = true,
    titleActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    titleActions()
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .then(
                        if (enableScroll) Modifier.verticalScroll(rememberScrollState())
                        else Modifier
                    )
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun AlchemyElderSection(
    elder: Disciple?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit
) {
    val elderBorderColor = if (elder != null) {
        try {
            Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
        } catch (e: Exception) {
            Color(0xFF9C27B0)
        }
    } else {
        GameColors.Border
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "炼丹长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColors.PageBackground)
                    .border(
                        2.dp,
                        elderBorderColor,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onElderClick() },
                contentAlignment = Alignment.Center
            ) {
                if (elder != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = elder.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            maxLines = 1
                        )
                        Text(
                            text = elder.realmName,
                            fontSize = 9.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = "炼丹: ${elder.pillRefining}",
                            fontSize = 9.sp,
                            color = Color(0xFF9C27B0)
                        )
                    }
                } else {
                    Text(
                        text = "点击任命",
                        fontSize = 10.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
            if (elder != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onElderRemove() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "卸任",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun AlchemyDirectDiscipleSection(
    directDisciples: List<DirectDiscipleSlot>,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "亲传弟子",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until 3).forEach { index ->
                val disciple = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                AlchemyDirectDiscipleSlotItem(
                    index = index,
                    disciple = disciple,
                    onClick = { onDirectDiscipleClick(index) },
                    onRemove = { onDirectDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun AlchemyDirectDiscipleSlotItem(
    index: Int,
    disciple: DirectDiscipleSlot,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = if (disciple.isActive) {
        try {
            Color(android.graphics.Color.parseColor(disciple.discipleSpiritRootColor))
        } catch (e: Exception) {
            Color(0xFF9C27B0)
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(55.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(6.dp)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (disciple.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.discipleName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 8.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
                )
            }
        }
        if (disciple.isActive) {
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                    .clickable { onRemove() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 9.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun AlchemyElderSelectionDialog(
    disciples: List<Disciple>,
    currentElderId: String?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            it.realm <= 5 &&
            !elderSlots.isDiscipleInAnyPosition(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareByDescending<Disciple> { it.pillRefining }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择炼丹长老",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 炼丹",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            val disciple = filteredDisciples[index]
                            AlchemyDiscipleSelectionCard(
                                disciple = disciple,
                                onClick = { onSelect(disciple.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun AlchemyDiscipleSelectionCard(
    disciple: Disciple,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(GameColors.PageBackground)
            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = disciple.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1
            )
            Text(
                text = disciple.realmName,
                fontSize = 8.sp,
                color = Color(0xFF666666),
                maxLines = 1
            )
            Text(
                text = "炼丹:${disciple.pillRefining}",
                fontSize = 7.sp,
                color = spiritRootColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AlchemyDirectDiscipleSelectionDialog(
    disciples: List<Disciple>,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val allDirectDiscipleIds = remember(elderSlots) {
        listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    val filteredDisciplesBase = remember(disciples, elderSlots, allDirectDiscipleIds) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !elderSlots.isDiscipleInAnyPosition(it.id) &&
            !allDirectDiscipleIds.contains(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(filteredDisciplesBase) {
        filteredDisciplesBase.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenByDescending { it.pillRefining }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择亲传弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 炼丹",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Border else GameColors.PageBackground)
                                    .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples.size) { index ->
                            val disciple = filteredDisciples[index]
                            AlchemyDiscipleSelectionCard(
                                disciple = disciple,
                                onClick = { onSelect(disciple.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun AlchemyReserveDiscipleDialog(
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    
    val reserveDisciples = viewModel.getAlchemyReserveDisciplesWithInfo()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "储备弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF9C27B0))
                            .clickable { showAddDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "添加",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (reserveDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无储备弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(reserveDisciples.size) { index ->
                            val disciple = reserveDisciples[index]
                            AlchemyReserveDiscipleCard(
                                disciple = disciple,
                                onRemove = { viewModel.removeAlchemyReserveDisciple(disciple.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )

    if (showAddDialog) {
        AlchemyReserveDiscipleAddDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun AlchemyReserveDiscipleCard(
    disciple: Disciple,
    onRemove: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, spiritRootColor, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1
                )
                Text(
                    text = disciple.spiritRootType,
                    fontSize = 7.sp,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 8.sp,
                    color = Color(0xFF666666),
                    maxLines = 1
                )
                Text(
                    text = "炼丹:${disciple.pillRefining}",
                    fontSize = 7.sp,
                    color = Color(0xFF9C27B0),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable { onRemove() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "移除",
                fontSize = 9.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
private fun AlchemyReserveDiscipleAddDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val availableDisciples = viewModel.getAvailableDisciplesForAlchemyReserve()
    val selectedIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "添加储备弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                Text(
                    text = "推荐属性: 炼丹（按炼丹属性排序）",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (availableDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可用弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableDisciples.size) { index ->
                            val disciple = availableDisciples[index]
                            val isSelected = selectedIds.contains(disciple.id)
                            AlchemyReserveDiscipleSelectCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        selectedIds.remove(disciple.id)
                                    } else {
                                        selectedIds.add(disciple.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GameButton(
                    text = "添加${if (selectedIds.isNotEmpty()) "(${selectedIds.size})" else ""}",
                    onClick = {
                        if (selectedIds.isNotEmpty()) {
                            viewModel.addAlchemyReserveDisciples(selectedIds.toList())
                            onDismiss()
                        }
                    }
                )
            }
        }
    )
}

@Composable
private fun AlchemyReserveDiscipleSelectCard(
    disciple: Disciple,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val spiritRootColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFF666666)
    }

    val borderColor = if (isSelected) Color(0xFFFFD700) else spiritRootColor
    val borderWidth = if (isSelected) 3.dp else 1.dp

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
            .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = disciple.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1
            )
            Text(
                text = disciple.spiritRootType,
                fontSize = 7.sp,
                color = spiritRootColor,
                maxLines = 1
            )
            Text(
                text = disciple.realmName,
                fontSize = 8.sp,
                color = Color(0xFF666666),
                maxLines = 1
            )
            Text(
                text = "炼丹:${disciple.pillRefining}",
                fontSize = 7.sp,
                color = Color(0xFF9C27B0),
                maxLines = 1
            )
        }
    }
}
