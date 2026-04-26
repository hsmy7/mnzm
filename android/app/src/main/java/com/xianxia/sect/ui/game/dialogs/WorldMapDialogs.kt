package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.CaveStatus
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.BattleViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.map.BattleTeamPathData
import com.xianxia.sect.ui.game.map.CaveExplorationPathData
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapItemMapper
import com.xianxia.sect.ui.game.map.WorldMapScreen
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorldMapDialog(
    worldSects: List<WorldSect>,
    scoutTeams: List<ExplorationTeam> = emptyList(),
    mapRenderData: WorldMapRenderData,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    battleViewModel: BattleViewModel,
    battleTeamMoveMode: Boolean = false,
    onDismiss: () -> Unit
) {
    var selectedSect by remember { mutableStateOf<WorldSect?>(null) }
    var showSectDetail by remember { mutableStateOf(false) }
    var selectedCave by remember { mutableStateOf<CultivatorCave?>(null) }
    var showCaveDetail by remember { mutableStateOf(false) }

    val caves: List<CultivatorCave> = mapRenderData.cultivatorCaves.filter { it.status != CaveStatus.EXPIRED && it.status != CaveStatus.EXPLORED }
    val playerSect = mapRenderData.worldMapSects.find { it.isPlayerSect }
    val playerSectX = playerSect?.x ?: 2000f
    val playerSectY = playerSect?.y ?: 1750f
    val caveExplorationTeams: List<CaveExplorationTeam> = mapRenderData.caveExplorationTeams
    val movableTargetIds = if (battleTeamMoveMode) worldMapViewModel.getMovableTargetSectIds().toSet() else emptySet()

    val sectItems = remember(worldSects, movableTargetIds) {
        MapItemMapper.fromWorldSects(worldSects, movableTargetIds)
    }

    val dynamicItems = remember(scoutTeams, caves, caveExplorationTeams, mapRenderData.battleTeam, mapRenderData.aiBattleTeams, playerSect) {
        val items = mutableListOf<MapItem>()
        items.addAll(MapItemMapper.fromScoutTeams(scoutTeams))
        items.addAll(MapItemMapper.fromCaves(caves))
        items.addAll(MapItemMapper.fromCaveExplorationTeams(caveExplorationTeams))
        items.addAll(MapItemMapper.fromBattleTeam(mapRenderData.battleTeam, playerSect, worldSects))
        val (aiTeams, battleIndicators) = MapItemMapper.fromAIBattleTeams(
            mapRenderData.aiBattleTeams, worldSects
        )
        items.addAll(aiTeams)
        items.addAll(battleIndicators)
        items
    }

    val mapItems = remember(sectItems, dynamicItems) {
        sectItems + dynamicItems
    }

    val paths = remember(worldSects) {
        MapItemMapper.fromPaths(worldSects)
    }

    val caveExplorationPaths = remember(caveExplorationTeams, caves) {
        caveExplorationTeams.filter { it.isMoving }.mapNotNull { team ->
            val cave = caves.find { it.id == team.caveId }
            if (cave != null) {
                CaveExplorationPathData(
                    startWorldX = team.startX,
                    startWorldY = team.startY,
                    endWorldX = team.targetX,
                    endWorldY = team.targetY
                )
            } else null
        }
    }

    val battleTeamPaths = remember(mapRenderData.battleTeam, mapRenderData.aiBattleTeams, worldSects) {
        val pathsList = mutableListOf<BattleTeamPathData>()
        mapRenderData.battleTeam?.let { bt ->
            if (bt.status == "moving") {
                val targetSect = worldSects.find { it.id == bt.targetSectId }
                if (targetSect != null && playerSect != null) {
                    pathsList.add(
                        BattleTeamPathData(
                            startWorldX = playerSect.x,
                            startWorldY = playerSect.y,
                            targetWorldX = targetSect.x,
                            targetWorldY = targetSect.y,
                            isRighteous = true
                        )
                    )
                }
            } else if (bt.status == "returning" && playerSect != null) {
                val targetSect = worldSects.find { it.id == bt.targetSectId }
                if (targetSect != null) {
                    pathsList.add(
                        BattleTeamPathData(
                            startWorldX = targetSect.x,
                            startWorldY = targetSect.y,
                            targetWorldX = playerSect.x,
                            targetWorldY = playerSect.y,
                            isRighteous = true
                        )
                    )
                }
            }
        }
        mapRenderData.aiBattleTeams.filter { it.status == "moving" || it.status == "returning" }.forEach { aiTeam ->
            val attackerSect = worldSects.find { it.id == aiTeam.attackerSectId }
            val defenderSect = worldSects.find { it.id == aiTeam.defenderSectId }
            if (attackerSect != null && defenderSect != null) {
                pathsList.add(
                    BattleTeamPathData(
                        startWorldX = attackerSect.x,
                        startWorldY = attackerSect.y,
                        targetWorldX = defenderSect.x,
                        targetWorldY = defenderSect.y,
                        isRighteous = attackerSect.isRighteous
                    )
                )
            }
        }
        pathsList
    }

    val window = LocalContext.current.let {
        (it as? android.app.Activity)?.window
    }

    LaunchedEffect(Unit) {
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                controller.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    BackHandler(onBack = onDismiss)

    WorldMapScreen(
        items = mapItems,
        paths = paths,
        caveExplorationPaths = caveExplorationPaths,
        battleTeamPaths = battleTeamPaths,
        hasBattleTeam = mapRenderData.battleTeam != null,
        isBattleTeamAtSect = mapRenderData.battleTeam?.isAtSect == true,
        focusWorldX = playerSectX,
        focusWorldY = playerSectY,
        onBack = onDismiss,
        onSectClick = { sectItem ->
            val sect = worldSects.find { it.id == sectItem.id }
            if (sect != null) {
                selectedSect = sect
                showSectDetail = true
            }
        },
        onCaveClick = { caveItem ->
            val cave = caves.find { it.id == caveItem.id }
            if (cave != null) {
                selectedCave = cave
                showCaveDetail = true
            }
        },
        onBattleTeamClick = {
            battleViewModel.openBattleTeamDialog()
        },
        onMovableTargetClick = { targetSectId ->
            battleViewModel.selectBattleTeamTarget(targetSectId)
        },
        onCreateTeamClick = { battleViewModel.openBattleTeamDialog() },
        onManageTeamClick = { battleViewModel.openBattleTeamDialog() }
    )

    if (showSectDetail) {
        selectedSect?.let { sect ->
            WorldMapSectDetailDialog(
                sect = sect,
                gameData = gameData,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = {
                    showSectDetail = false
                    selectedSect = null
                }
            )
        }
    }

    if (showCaveDetail) {
        selectedCave?.let { cave ->
            CaveDetailDialog(
                cave = cave,
                gameData = gameData,
                disciples = disciples,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = {
                    showCaveDetail = false
                    selectedCave = null
                }
            )
        }
    }
}

@Composable
internal fun WorldMapSectDetailDialog(
    sect: WorldSect,
    gameData: GameData?,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val isAlly = worldMapViewModel.isAlly(sect.id)
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect.id)?.lastGiftYear ?: 0) == currentYear
    var showGiftedMessage by remember { mutableStateOf(false) }
    
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null) {
        gameData?.sectRelations?.find { 
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0
    
    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val relationColor = Color(relationLevel.colorHex)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = sect.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (!sect.isPlayerSect) {
                        Text(
                            text = sect.levelName,
                            fontSize = 10.sp,
                            color = Color(0xFF666666),
                            modifier = Modifier
                                .background(
                                    GameColors.CardBackground,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (sect.isPlayerSect) {
                        Text(
                            text = "本宗",
                            fontSize = 10.sp,
                            color = Color(0xFFFF8C00),
                            modifier = Modifier
                                .background(
                                    Color(0xFFFFF3E0),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    } else if (isAlly) {
                        Text(
                            text = "盟友",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier
                                .background(
                                    Color(0xFFE8F5E9),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
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
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!sect.isPlayerSect) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "关系:",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = relationLevel.displayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = relationColor
                        )
                        Text(
                            text = "(${relation})",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                
                if (!sect.isPlayerSect) {
                    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                    
                    Text(
                        text = "弟子分布",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF333333)
                    )
                    
                    val scoutInfo = gameData?.sectDetails?.get(sect.id)?.scoutInfo ?: SectScoutInfo()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (0..4).forEach { realmIndex ->
                            val realmName = GameConfig.Realm.getName(realmIndex)
                            val count = scoutInfo.disciples.get(realmIndex) ?: 0
                            val displayText = if (scoutInfo.sectId.isNotEmpty() && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) "$count" else "0"
                            } else {
                                "?"
                            }
                            val textColor = if (scoutInfo.sectId.isNotEmpty() && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) Color(0xFF4CAF50) else Color(0xFF999999)
                            } else {
                                Color(0xFFFF9800)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = realmName,
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = displayText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (5..9).forEach { realmIndex ->
                            val realmName = GameConfig.Realm.getName(realmIndex)
                            val count = scoutInfo.disciples.get(realmIndex) ?: 0
                            val displayText = if (scoutInfo.sectId.isNotEmpty() && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) "$count" else "0"
                            } else {
                                "?"
                            }
                            val textColor = if (scoutInfo.sectId.isNotEmpty() && scoutInfo.disciples.containsKey(realmIndex)) {
                                if (count > 0) Color(0xFF4CAF50) else Color(0xFF999999)
                            } else {
                                Color(0xFFFF9800)
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = realmName,
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = displayText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
                
                if (!sect.isPlayerSect) {
                    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GameButton(
                                text = "探查",
                                onClick = {
                                    worldMapViewModel.openScoutDialog(sect.id)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            GameButton(
                                text = if (hasGiftedThisYear) "已送礼" else "送礼",
                                onClick = {
                                    if (hasGiftedThisYear) {
                                        showGiftedMessage = true
                                    } else {
                                        worldMapViewModel.openGiftDialog(sect.id)
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            GameButton(
                                text = if (isAlly) "盟约" else "结盟",
                                onClick = {
                                    worldMapViewModel.openAllianceDialog(sect.id)
                                    onDismiss()
                                },
                                enabled = relationLevel == SectRelationLevel.INTIMATE || isAlly,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GameButton(
                                text = "交易",
                                onClick = {
                                    worldMapViewModel.openSectTradeDialog(sect.id)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            Spacer(modifier = Modifier.weight(1f))
                            
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF8E1), RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "这是您的宗门",
                            fontSize = 12.sp,
                            color = Color(0xFF8B7355)
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
    
    if (showGiftedMessage) {
        GiftedMessageToast(
            message = "今年已送过礼品等明年再来吧",
            onDismiss = { showGiftedMessage = false }
        )
    }
}

@Composable
fun DiplomacyDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val worldSects = gameData?.worldMapSects?.filter { !it.isPlayerSect } ?: emptyList()
    val currentYear = gameData?.gameYear ?: 1
    val sectRelations = gameData?.sectRelations
    
    val sectFavors = remember(playerSect, worldSects, sectRelations) {
        if (playerSect == null) {
            emptyMap()
        } else {
            val relations = sectRelations ?: emptyList()
            worldSects.associateWith { sect ->
                relations.find { relation ->
                    (relation.sectId1 == playerSect.id && relation.sectId2 == sect.id) ||
                    (relation.sectId1 == sect.id && relation.sectId2 == playerSect.id)
                }?.favor ?: 0
            }
        }
    }
    
    val sortedSects = worldSects.sortedByDescending { sectFavors[it] ?: 0 }
    
    var showGiftedMessage by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxSize()) {
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
                        text = "宗门外交",
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
                if (worldSects.isEmpty()) {
                    Text(
                        text = "暂无其他宗门",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sortedSects.size) { index ->
                            val sect = sortedSects[index]
                            DiplomacySectCard(
                                sect = sect,
                                relation = sectFavors[sect] ?: 0,
                                currentYear = currentYear,
                                gameData = gameData,
                                isAlly = worldMapViewModel.isAlly(sect.id),
                                onGift = {
                                    worldMapViewModel.openGiftDialog(sect.id)
                                },
                                onFormAlliance = {
                                    worldMapViewModel.openAllianceDialog(sect.id)
                                },
                                onTrade = {
                                    worldMapViewModel.openSectTradeDialog(sect.id)
                                },
                                onShowGiftedMessage = {
                                    showGiftedMessage = true
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
        
        if (showGiftedMessage) {
            GiftedMessageToast(
                message = "今年已送过礼品等明年再来吧",
                onDismiss = { showGiftedMessage = false }
            )
        }
    }
}

@Composable
internal fun CaveDetailDialog(
    cave: CultivatorCave,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val currentMonth = gameData?.gameMonth ?: 1
    val remainingMonths = cave.getRemainingMonths(currentYear, currentMonth)
    
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDisciples by remember { mutableStateOf<List<DiscipleAggregate>>(emptyList()) }
    
    val statusColor = when (cave.status) {
        CaveStatus.AVAILABLE -> Color(0xFF9C27B0)
        CaveStatus.EXPLORING -> Color(0xFFFF9800)
        CaveStatus.EXPLORED -> Color(0xFF4CAF50)
        CaveStatus.EXPIRED -> Color(0xFF9E9E9E)
    }
    
    val statusText = when (cave.status) {
        CaveStatus.AVAILABLE -> "可探索"
        CaveStatus.EXPLORING -> "探索中"
        CaveStatus.EXPLORED -> "已探索"
        CaveStatus.EXPIRED -> "已消失"
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = cave.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(statusColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
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
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "洞府境界",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                        Text(
                            text = cave.ownerRealmName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                    }
                    
                    if (cave.status != CaveStatus.EXPIRED) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "剩余时间",
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                            Text(
                                text = "${remainingMonths}月",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (remainingMonths <= 3) Color(0xFFF44336) else Color(0xFF333333)
                            )
                        }
                    }
                }
                
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                
                when (cave.status) {
                    CaveStatus.AVAILABLE -> {
                        Text(
                            text = "此洞府尚未被探索，派遣弟子前往探索可获得丰厚奖励。",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                        if (selectedDisciples.isNotEmpty()) {
                            Text(
                                text = "已选择 ${selectedDisciples.size}/10 人: ${selectedDisciples.joinToString("、") { it.name }}",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    CaveStatus.EXPLORING -> {
                        val exploringTeam = gameData?.caveExplorationTeams?.find { it.caveId == cave.id }
                        if (exploringTeam != null) {
                            val progress = exploringTeam.getProgressPercent(currentYear, currentMonth)
                            Column {
                                Text(
                                    text = "探索队伍: ${exploringTeam.memberNames.joinToString("、")}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF333333)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = Color(0xFF4CAF50),
                                        trackColor = GameColors.Border
                                    )
                                    Text(
                                        text = "$progress%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                    }
                    CaveStatus.EXPLORED -> {
                        Text(
                            text = "此洞府已被探索完毕。",
                            fontSize = 12.sp,
                            color = Color(0xFF666666)
                        )
                    }
                    CaveStatus.EXPIRED -> {
                        Text(
                            text = "此洞府已经消失，无法再进行探索。",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cave.status == CaveStatus.AVAILABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedDisciples.isNotEmpty()) {
                        GameButton(
                            text = "确认派遣",
                            onClick = {
                                worldMapViewModel.startCaveExploration(cave, selectedDisciples)
                                onDismiss()
                            }
                        )
                    }
                    GameButton(
                        text = if (selectedDisciples.isEmpty()) "选择弟子" else "修改选择",
                        onClick = { showDiscipleSelection = true }
                    )
                }
            }
        },
        dismissButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    )
    
    if (showDiscipleSelection) {
        CaveDiscipleSelectionDialog(
            disciples = disciples,
            selectedDisciples = selectedDisciples,
            maxSelection = 10,
            caveRealm = cave.ownerRealm,
            onConfirm = { 
                selectedDisciples = it
                showDiscipleSelection = false
            },
            onDismiss = { showDiscipleSelection = false }
        )
    }
}

@Composable
internal fun CaveDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    selectedDisciples: List<DiscipleAggregate>,
    maxSelection: Int,
    caveRealm: Int,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var currentSelected by remember(selectedDisciples) { @Suppress("MutableCollectionMutableState") mutableStateOf(selectedDisciples.toMutableList()) }

    val allRealmFilters = listOf(
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
    
    val realmFilters = remember(allRealmFilters, caveRealm) {
        allRealmFilters.filter { it.first <= caveRealm }
    }

    val availableDisciples = remember(disciples, caveRealm) {
        disciples.filter { disciple ->
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0 &&
            disciple.age >= 5 &&
            disciple.realm <= caveRealm
        }.sortedByFollowAndRealm()
    }

    val realmCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(availableDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        availableDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
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
                    text = "选择探索弟子 (${currentSelected.size}/$maxSelection)",
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
            if (availableDisciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无空闲弟子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                ) {
                    SpiritRootAttributeFilterBar(
                        selectedSpiritRootFilter = selectedSpiritRootFilter,
                        selectedAttributeSort = selectedAttributeSort,
                        spiritRootExpanded = spiritRootExpanded,
                        attributeExpanded = attributeExpanded,
                        spiritRootCounts = spiritRootCounts,
                        onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                        onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                        onAttributeSortSelected = { selectedAttributeSort = it },
                        onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                        onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                        isCompact = true
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.take(5).forEach { (realm, name) ->
                                val isSelected = realm in selectedRealmFilter
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                        .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GameColors.GoldDark else Color.Black
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.drop(5).forEach { (realm, name) ->
                                val isSelected = realm in selectedRealmFilter
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                        .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GameColors.GoldDark else Color.Black
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples) { disciple ->
                            val isSelected = disciple.id in currentSelected.map { it.id }
                            val canSelect = isSelected || currentSelected.size < maxSelection
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFFE8F5E9) else GameColors.PageBackground)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF4CAF50) else GameColors.Border,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) {
                                            currentSelected = currentSelected.filter { it.id != disciple.id }.toMutableList()
                                        } else if (currentSelected.size < maxSelection) {
                                            currentSelected = (currentSelected + disciple).toMutableList()
                                        }
                                    }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = disciple.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (canSelect) Color.Black else Color(0xFF999999)
                                        )
                                        if (disciple.isFollowed) {
                                            FollowedTag()
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val spiritRootColor = try {
                                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Text(
                                            text = disciple.spiritRootName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = spiritRootColor
                                        )
                                        Text(
                                            text = disciple.realmName,
                                            fontSize = 12.sp,
                                            color = if (canSelect) Color(0xFF666666) else Color(0xFF999999)
                                        )
                                        if (isSelected) {
                                            Text(
                                                text = "✓",
                                                fontSize = 12.sp,
                                                color = Color(0xFF4CAF50),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameButton(
                    text = "清空",
                    onClick = { currentSelected = mutableListOf() }
                )
                GameButton(
                    text = "确认",
                    onClick = { onConfirm(currentSelected) }
                )
            }
        }
    )
}

@Composable
internal fun DiplomacySectCard(
    sect: WorldSect,
    relation: Int,
    currentYear: Int,
    gameData: GameData?,
    isAlly: Boolean,
    onGift: () -> Unit,
    onFormAlliance: () -> Unit,
    onTrade: () -> Unit,
    onShowGiftedMessage: () -> Unit
) {
    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val relationColor = Color(relationLevel.colorHex)
    
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect.id)?.lastGiftYear ?: 0) == currentYear
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = GameColors.PageBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = sect.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        if (isAlly) {
                            Text(
                                text = "盟友",
                                fontSize = 10.sp,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier
                                    .background(
                                        Color(0xFFE8F5E9),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = sect.levelName,
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = relationLevel.displayName,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "$relation",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = relationColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GameButton(
                    text = "送礼",
                    onClick = {
                        if (hasGiftedThisYear) {
                            onShowGiftedMessage()
                        } else {
                            onGift()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                
                GameButton(
                    text = if (isAlly) "盟约" else "结盟",
                    onClick = onFormAlliance,
                    enabled = relationLevel == SectRelationLevel.INTIMATE || isAlly,
                    modifier = Modifier.weight(1f)
                )
                
                GameButton(
                    text = "交易",
                    onClick = onTrade,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun GiftedMessageToast(
    message: String,
    onDismiss: () -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var alpha by remember { mutableFloatStateOf(1f) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        repeat(6) {
            offsetY -= 3f
            alpha -= 1f / 6f
            kotlinx.coroutines.delay(150)
        }
        onDismiss()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(x = 0, y = offsetY.dp.roundToPx()) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666).copy(alpha = alpha.coerceIn(0f, 1f)),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectTradeDialog(
    sect: WorldSect?,
    gameData: GameData?,
    tradeItems: List<MerchantItem>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<MerchantItem?>(null) }
    var buyQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showRelationWarning by remember { mutableStateOf(false) }

    LaunchedEffect(tradeItems) {
        val currentId = selectedItem?.id
        if (currentId != null) {
            val updated = tradeItems.find { it.id == currentId }
            selectedItem = updated
        }
    }
    
    val relation = if (gameData != null && sect != null) {
        GameUtils.getSectRelation(gameData.worldMapSects, gameData.sectRelations, sect.id)
    } else 0
    val isAlly = sect?.let { worldMapViewModel.isAlly(it.id) } ?: false
    
    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val maxAllowedRarity = relationLevel.maxAllowedRarity
    
    val priceMultiplier = if (gameData != null && sect != null) {
        GameUtils.calculateSectTradePriceMultiplier(gameData.worldMapSects, gameData.sectRelations, gameData.alliances, sect.id)
    } else 1.0
    
    val relationColor = Color(relationLevel.colorHex)
    
    val canTrade = relationLevel in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)

    LaunchedEffect(showRelationWarning) {
        if (showRelationWarning) {
            delay(1000)
            showRelationWarning = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GameColors.PageBackground)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${sect?.name ?: "宗门"}交易",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "关系:",
                                    fontSize = 11.sp,
                                    color = GameColors.TextSecondary
                                )
                                Text(
                                    text = relationLevel.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = relationColor
                                )
                                Text(
                                    text = "(${relation})",
                                    fontSize = 11.sp,
                                    color = GameColors.TextSecondary
                                )
                                if (isAlly) {
                                    Text(
                                        text = "(盟友)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "(${String.format(Locale.getDefault(), "%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50)
                                    )
                                } else if (relation >= 70) {
                                    Text(
                                        text = "(${String.format(Locale.getDefault(), "%.1f%%", (1 - priceMultiplier) * 100)}折扣)",
                                        fontSize = 10.sp,
                                        color = Color(0xFF4CAF50)
                                    )
                                } else if (!canTrade) {
                                    Text(
                                        text = "(关系不足，无法交易)",
                                        fontSize = 10.sp,
                                        color = Color(0xFFF44336)
                                    )
                                }
                            }
                            Text(
                                text = "灵石: ${GameUtils.formatNumber(gameData?.spiritStones ?: 0)}",
                                fontSize = 11.sp,
                                color = GameColors.TextSecondary
                            )
                        }

                        GameButton(
                            text = "关闭",
                            onClick = onDismiss
                        )
                    }

                    if (tradeItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无商品\n请稍后再来",
                                fontSize = 12.sp,
                                color = GameColors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(tradeItems.size) { index ->
                                val item = tradeItems[index]
                                val canBuyThisItem = canTrade && item.rarity <= maxAllowedRarity
                                val rarityColor = when (item.rarity) {
                                    1 -> GameColors.RarityCommon
                                    2 -> GameColors.RaritySpirit
                                    3 -> GameColors.RarityTreasure
                                    4 -> GameColors.RarityMystic
                                    5 -> GameColors.RarityEarth
                                    6 -> GameColors.RarityHeaven
                                    else -> GameColors.RarityCommon
                                }
                                
                                val adjustedPrice = (item.price * priceMultiplier).toLong()
                                
                                Box(
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (canBuyThisItem) GameColors.PageBackground else GameColors.Border)
                                            .border(
                                                width = if (selectedItem?.id == item.id) 3.dp else 2.dp,
                                                color = if (!canBuyThisItem) Color(0xFFBDBDBD) 
                                                    else if (selectedItem?.id == item.id) Color(0xFFFFD700) 
                                                    else rarityColor,
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .clickable {
                                                if (!canBuyThisItem) {
                                                    showRelationWarning = true
                                                } else {
                                                    if (selectedItem?.id == item.id) {
                                                        selectedItem = null
                                                        buyQuantity = 1
                                                    } else {
                                                        selectedItem = item
                                                        buyQuantity = 1
                                                    }
                                                }
                                            }
                                            .padding(4.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = item.name,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (canBuyThisItem) GameColors.TextPrimary else Color(0xFF9E9E9E),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                            
                                            Spacer(modifier = Modifier.height(2.dp))
                                            
                                            Text(
                                                text = "${adjustedPrice}灵石",
                                                fontSize = 9.sp,
                                                color = if (canBuyThisItem) GameColors.GoldDark else Color(0xFF9E9E9E),
                                                maxLines = 1
                                            )
                                        }
                                        
                                        Text(
                                            text = "x${item.quantity}",
                                            fontSize = 9.sp,
                                            color = if (canBuyThisItem) GameColors.TextSecondary else Color(0xFF9E9E9E),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(2.dp)
                                        )
                                    }
                                    
                                    if (selectedItem?.id == item.id && canBuyThisItem) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 6.dp, y = (-6).dp)
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFFFD700))
                                                .clickable {
                                                    selectedItem = item
                                                    showDetailDialog = true
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "查看",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            selectedItem?.let { item ->
                                val adjustedPrice = (item.price * priceMultiplier).toLong()
                                val totalPrice = adjustedPrice * buyQuantity
                                val canAfford = (gameData?.spiritStones ?: 0L) >= totalPrice
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GameColors.TextPrimary
                                        )
                                        Text(
                                            text = "单价: $adjustedPrice 灵石",
                                            fontSize = 10.sp,
                                            color = GameColors.TextSecondary
                                        )
                                    }
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "购买数量:",
                                            fontSize = 11.sp,
                                            color = GameColors.TextSecondary
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GameColors.Background)
                                                .clickable { buyQuantity = (buyQuantity - 1).coerceAtLeast(1) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                        }
                                        
                                        Text(
                                            text = "$buyQuantity",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GameColors.TextPrimary,
                                            modifier = Modifier.widthIn(min = 24.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GameColors.Background)
                                                .clickable { buyQuantity = (buyQuantity + 1).coerceAtMost(item.quantity) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GameColors.TextPrimary)
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "总价: $totalPrice 灵石",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (canAfford) GameColors.GoldDark else Color.Red
                                    )
                                    
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GameButton(
                                            text = "取消",
                                            onClick = {
                                                selectedItem = null
                                                buyQuantity = 1
                                            }
                                        )
                                        
                                        GameButton(
                                            text = "确认购买",
                                            onClick = {
                                                worldMapViewModel.buyFromSectTrade(item.id, buyQuantity)
                                                buyQuantity = 1
                                            },
                                            enabled = canAfford && buyQuantity > 0
                                        )
                                    }
                                }
                            } ?: run {
                                Text(
                                    text = "请选择要购买的商品",
                                    fontSize = 12.sp,
                                    color = GameColors.TextSecondary
                                )
                            }
                        }
                    }
                }
                
                if (showRelationWarning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .animateContentSize()
                                .alpha(if (showRelationWarning) 1f else 0f),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xCC000000)
                        ) {
                            Text(
                                text = "关系不足，无法交易该物品",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDetailDialog) {
        selectedItem?.let { item ->
            ItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
    }
}