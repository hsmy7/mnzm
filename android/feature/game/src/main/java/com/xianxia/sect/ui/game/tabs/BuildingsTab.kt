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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.xianxia.sect.feature.game.R
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
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.SpiritMineViewModel

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
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
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()
    val alchemySlots by viewModel.alchemySlots.collectAsStateWithLifecycle()
    val forgeSlots by viewModel.forgeSlots.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()
    val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    val equipment by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val productionSlots by viewModel.productionSlots.collectAsStateWithLifecycle()

    val buildingDescriptions = mapOf(
        "spirit_mine" to "开采灵石资源",
        "herb_garden" to "种植灵药材料",
        "alchemy" to "炼制丹药",
        "forge" to "锻造装备",
        "library" to "功法管理",
        "wen_dao_peak" to "管理外门弟子",
        "qingyun_peak" to "管理内门弟子",
        "tianshu_hall" to "处理宗门事务",
        "law_enforcement_hall" to "维护宗门纪律",
        "mission_hall" to "派遣弟子执行任务",
        "reflection_cliff" to "悔过自新之地",
        "patrol_tower" to "驻守弟子自动巡视攻击妖兽",
        "blood_refining_pool" to "消耗兽血材料淬炼弟子肉身",
        "warehouse" to "储存宗门物资，每座+50格容量",
    )

    val buildings: List<Triple<String, String, () -> Unit>> = remember(buildingDescriptions) {
        BuildingFeatureRegistry.constructible.filter { def ->
            !def.isResidence && def.key != "spirit_field"
        }.map { def ->
            val desc = buildingDescriptions[def.key] ?: ""
            val onClick: () -> Unit = {
                when (def.key) {
                    "spirit_mine" -> viewModel.openSpiritMineDialog()
                    "herb_garden" -> viewModel.openHerbGardenDialog()
                    "alchemy" -> viewModel.openAlchemyDialog()
                    "forge" -> viewModel.openForgeDialog()
                    "library" -> viewModel.openLibraryDialog()
                    "wen_dao_peak" -> viewModel.openWenDaoPeakDialog()
                    "qingyun_peak" -> viewModel.openQingyunPeakDialog()
                    "tianshu_hall" -> viewModel.openTianshuHallDialog()
                    "law_enforcement_hall" -> viewModel.openLawEnforcementHallDialog()
                    "mission_hall" -> viewModel.openMissionHallDialog()
                    "reflection_cliff" -> viewModel.openReflectionCliffDialog()
                    "patrol_tower" -> viewModel.openPatrolTowerDialog()
                    "blood_refining_pool" -> viewModel.openBloodRefiningPoolDialog()
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
