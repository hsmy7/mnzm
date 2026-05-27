package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
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
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.SpiritMineViewModel

import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingRegistry
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.theme.Spacing

@Composable
internal fun BuildingsTab(
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    forgeViewModel: ForgeViewModel,
    herbGardenViewModel: HerbGardenViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    onDismiss: () -> Unit
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

    val buildings: List<Triple<String, String, () -> Unit>> = remember {
        BuildingRegistry.ALL.filter { def ->
            def != BuildingDef.SINGLE_RESIDENCE_UPGRADED && !def.isResidence && def != BuildingDef.SPIRIT_FIELD
        }.map { def ->
            val desc = when (def) {
                BuildingDef.SPIRIT_MINE -> "开采灵石资源"
                BuildingDef.HERB_GARDEN -> "种植灵药材料"
                BuildingDef.ALCHEMY -> "炼制丹药"
                BuildingDef.FORGE -> "锻造装备"
                BuildingDef.LIBRARY -> "功法管理"
                BuildingDef.WEN_DAO_PEAK -> "管理外门弟子"
                BuildingDef.QINGYUN_PEAK -> "管理内门弟子"
                BuildingDef.TIANSHU_HALL -> "处理宗门事务"
                BuildingDef.LAW_ENFORCEMENT -> "维护宗门纪律"
                BuildingDef.MISSION_HALL -> "派遣弟子执行任务"
                BuildingDef.REFLECTION_CLIFF -> "悔过自新之地"
                BuildingDef.PATROL_TOWER -> "驻守弟子自动巡视攻击妖兽"
                else -> ""
            }
            val onClick = {
                when (def) {
                    BuildingDef.SPIRIT_MINE -> viewModel.openSpiritMineDialog()
                    BuildingDef.HERB_GARDEN -> viewModel.openHerbGardenDialog()
                    BuildingDef.ALCHEMY -> viewModel.openAlchemyDialog()
                    BuildingDef.FORGE -> viewModel.openForgeDialog()
                    BuildingDef.LIBRARY -> viewModel.openLibraryDialog()
                    BuildingDef.WEN_DAO_PEAK -> viewModel.openWenDaoPeakDialog()
                    BuildingDef.QINGYUN_PEAK -> viewModel.openQingyunPeakDialog()
                    BuildingDef.TIANSHU_HALL -> viewModel.openTianshuHallDialog()
                    BuildingDef.LAW_ENFORCEMENT -> viewModel.openLawEnforcementHallDialog()
                    BuildingDef.MISSION_HALL -> viewModel.openMissionHallDialog()
                    BuildingDef.REFLECTION_CLIFF -> viewModel.openReflectionCliffDialog()
                    BuildingDef.PATROL_TOWER -> viewModel.openPatrolTowerDialog()
                    else -> { }
                }
            }
            Triple(def.displayName, desc, onClick)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.SM)
        ) {
            buildings.chunked(2).forEach { rowBuildings ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.SM)
                ) {
                            rowBuildings.forEach { building ->
                                val name = building.first
                                val desc = building.second
                                val onClick = building.third
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
                                        .clickable { onClick() }
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.bg_horizontal),
                                        contentDescription = null,
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.FillBounds
                                    )
                                    Column(modifier = Modifier.padding(12.dp)) {
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
                                            color = Color.Black
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

}
