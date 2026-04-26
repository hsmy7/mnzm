package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.ui.game.AlchemyDialog
import com.xianxia.sect.ui.game.ForgeDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenDialog
import com.xianxia.sect.ui.game.LawEnforcementHallDialog
import com.xianxia.sect.ui.game.LibraryDialog
import com.xianxia.sect.ui.game.MissionHallDialog
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.QingyunPeakDialog
import com.xianxia.sect.ui.game.ReflectionCliffDialog
import com.xianxia.sect.ui.game.SpiritMineDialog
import com.xianxia.sect.ui.game.TianshuHallDialog
import com.xianxia.sect.ui.game.WenDaoPeakDialog
import com.xianxia.sect.ui.state.DialogStateManager.DialogType
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme

@Composable
internal fun BuildingsTab(
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel
) {
    val gameData by viewModel.gameData.collectAsState()
    val alchemySlots by viewModel.alchemySlots.collectAsState()
    val forgeSlots by viewModel.forgeSlots.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val disciples by viewModel.discipleAggregates.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val productionSlots by viewModel.productionSlots.collectAsState()
    
    val currentDialog by viewModel.dialogStateManager.currentDialog.collectAsState()

    val showAlchemyDialog = currentDialog?.type == DialogType.Alchemy
    val showForgeDialog = currentDialog?.type == DialogType.Forge
    val showHerbGardenDialog = currentDialog?.type == DialogType.HerbGarden
    val showSpiritMineDialog = currentDialog?.type == DialogType.SpiritMine
    val showLibraryDialog = currentDialog?.type == DialogType.Library
    val showWenDaoPeakDialog = currentDialog?.type == DialogType.WenDaoPeak
    val showQingyunPeakDialog = currentDialog?.type == DialogType.QingyunPeak
    val showTianshuHallDialog = currentDialog?.type == DialogType.TianshuHall
    val showLawEnforcementHallDialog = currentDialog?.type == DialogType.LawEnforcementHall
    val showMissionHallDialog = currentDialog?.type == DialogType.MissionHall

    val buildings: List<Triple<String, String, () -> Unit>> = listOf(
        Triple("灵矿场", "开采灵石资源") { viewModel.openSpiritMineDialog() },
        Triple("灵药宛", "种植灵药材料") { viewModel.openHerbGardenDialog() },
        Triple("丹鼎殿", "炼制丹药") { viewModel.openAlchemyDialog() },
        Triple("天工峰", "锻造装备") { viewModel.openForgeDialog() },
        Triple("藏经阁", "功法管理") { viewModel.openLibraryDialog() },
        Triple("问道峰", "管理外门弟子") { viewModel.openWenDaoPeakDialog() },
        Triple("青云峰", "管理内门弟子") { viewModel.openQingyunPeakDialog() },
        Triple("天枢殿", "处理宗门事务") { viewModel.openTianshuHallDialog() },
        Triple("执法堂", "维护宗门纪律") { viewModel.openLawEnforcementHallDialog() },
        Triple("任务阁", "派遣弟子执行任务") { viewModel.openMissionHallDialog() },
        Triple("思过崖", "悔过自新之地") { viewModel.openReflectionCliffDialog() }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.PageBackground)
            .padding(12.dp)
    ) {
        Text(
            text = "建筑管理",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buildings.chunked(2).forEach { rowBuildings ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowBuildings.forEach { building ->
                        val name = building.first
                        val desc = building.second
                        val onClick = building.third
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GameColors.PageBackground)
                                .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
                                .clickable { onClick() }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    fontSize = 12.sp,
                                    color = Color(0xFF666666)
                                )
                            }
                        }
                    }
                    if (rowBuildings.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    if (showSpiritMineDialog) {
        SpiritMineDialog(
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showHerbGardenDialog) {
        HerbGardenDialog(
            plantSlots = productionSlots.filter {
                it.buildingType == com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN
            }.map { slot ->
                com.xianxia.sect.core.model.PlantSlotData(
                    index = slot.slotIndex,
                    status = when (slot.status) {
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE -> "idle"
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING -> "growing"
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED -> "mature"
                    },
                    seedId = slot.recipeId ?: "",
                    seedName = slot.recipeName,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    growTime = slot.duration,
                    expectedYield = slot.expectedYield
                )
            },
            seeds = seeds,
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showAlchemyDialog) {
        AlchemyDialog(
            alchemySlots = alchemySlots,
            materials = materials,
            herbs = herbs,
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            colors = XianxiaColorScheme(),
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showForgeDialog) {
        ForgeDialog(
            forgeSlots = forgeSlots,
            materials = materials,
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            colors = XianxiaColorScheme(),
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showLibraryDialog) {
        LibraryDialog(
            manuals = manuals,
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showWenDaoPeakDialog) {
        WenDaoPeakDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showQingyunPeakDialog) {
        QingyunPeakDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showTianshuHallDialog) {
        TianshuHallDialog(
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showLawEnforcementHallDialog) {
        LawEnforcementHallDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    if (showMissionHallDialog) {
        MissionHallDialog(
            gameData = gameData,
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            onDismiss = { viewModel.closeCurrentDialog() }
        )
    }

    val showReflectionCliffDialog = currentDialog?.type == DialogType.ReflectionCliff
    if (showReflectionCliffDialog) {
        ReflectionCliffDialog(
            disciples = disciples.filter { it.isAlive },
            gameData = gameData,
            onDismiss = { viewModel.closeCurrentDialog() },
            onExpelDisciple = { discipleId -> viewModel.expelDisciple(discipleId) }
        )
    }
}