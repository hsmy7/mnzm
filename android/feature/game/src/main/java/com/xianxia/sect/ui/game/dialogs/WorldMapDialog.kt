package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.model.WorldSect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.game.WorldMapGarrisonViewModel
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapItemMapper
import com.xianxia.sect.core.model.MapCoordinateSystem
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.game.map.WorldMapScreen

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorldMapDialog(
    worldSects: List<WorldSect>,
    mapRenderData: WorldMapRenderData,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    onDismiss: () -> Unit
) {
    var selectedSect by remember { mutableStateOf<WorldSect?>(null) }
    var showSectDetail by remember { mutableStateOf(false) }
    var selectedLevel by remember { mutableStateOf<MapItem.Level?>(null) }
    var showLevelDetail by remember { mutableStateOf(false) }
    var selectedSecretRealm by remember { mutableStateOf<MapItem.SecretRealm?>(null) }
    var showSecretRealmDetail by remember { mutableStateOf(false) }
    var showSecretRealmExploration by remember { mutableStateOf(false) }

    // WorldMap sub-dialogs — rendered locally to keep world map as background
    val showSectTradeDialog by interactionViewModel.showSectTradeDialog.collectAsStateWithLifecycle()
    val selectedTradeSectId by interactionViewModel.selectedTradeSectId.collectAsStateWithLifecycle()
    val sectTradeItems by interactionViewModel.sectTradeItems.collectAsStateWithLifecycle()
    val showScoutDialog by interactionViewModel.showScoutDialog.collectAsStateWithLifecycle()
    val selectedScoutSectId by interactionViewModel.selectedScoutSectId.collectAsStateWithLifecycle()
    val playerSect = mapRenderData.worldMapSects.find { it.isPlayerSect }
    val playerSectX = playerSect?.x
        ?: MapCoordinateSystem.WORLD_WIDTH / 2f
    val playerSectY = playerSect?.y
        ?: MapCoordinateSystem.WORLD_HEIGHT / 2f
    val sectItems = remember(worldSects) {
        MapItemMapper.fromWorldSects(worldSects, emptySet())
    }

    val levelItems = remember(mapRenderData.worldLevels) {
        MapItemMapper.fromLevels(mapRenderData.worldLevels)
    }

    val realmItem = remember(mapRenderData.secretRealm) {
        mapRenderData.secretRealm?.let { MapItemMapper.fromSecretRealm(it) }
    }

    val mapItems = remember(sectItems, levelItems, realmItem) {
        sectItems + levelItems + listOfNotNull(realmItem)
    }

    // 秘境探索全屏宿主（会话存在时暂停游戏时间，退出恢复）
    val secretRealmViewModel: SecretRealmViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()

    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
    WorldMapScreen(
        items = mapItems,
        focusWorld = Offset(playerSectX, playerSectY),
        onBack = onDismiss,
        onUserInteraction = viewModel::onUserInteraction,
        onItemClick = { item ->
            when (item) {
                is MapItem.Sect -> {
                    val sect = worldSects.find { it.id == item.id }
                    if (sect != null) {
                        selectedSect = sect
                        showSectDetail = true
                    }
                }
                is MapItem.Level -> {
                    selectedLevel = item
                    showLevelDetail = true
                }
                is MapItem.SecretRealm -> {
                    selectedSecretRealm = item
                    showSecretRealmDetail = true
                }
            }
        }
    )

    // 远古秘境探索全屏（覆盖地图；返回 = 暂存退出，会话保留）
    if (showSecretRealmExploration) {
        SecretRealmExplorationScreen(
            viewModel = secretRealmViewModel,
            onExit = {
                showSecretRealmExploration = false
                viewModel.onUserInteraction()
            },
            onFinished = {
                showSecretRealmExploration = false
                showSecretRealmDetail = false
                selectedSecretRealm = null
                viewModel.onUserInteraction()
            }
        )
    }

    if (showSectDetail) {
        selectedSect?.let { sect ->
            WorldMapSectDetailDialog(
                sect = sect,
                gameData = gameData,
                disciples = disciples,
                viewModel = viewModel,
                interactionViewModel = interactionViewModel,
                garrisonViewModel = garrisonViewModel,
                onDismiss = {
                    showSectDetail = false
                    selectedSect = null
                }
            )
        }
    }

    if (showSecretRealmDetail && !showSecretRealmExploration) {
        val realm = selectedSecretRealm
        if (realm != null) {
            SecretRealmDetailDialog(
                realm = realm,
                gameData = gameData,
                viewModel = secretRealmViewModel,
                onStart = { memberIds ->
                    showSecretRealmDetail = false
                    showSecretRealmExploration = true
                },
                onContinue = {
                    showSecretRealmDetail = false
                    showSecretRealmExploration = true
                },
                onDismiss = {
                    showSecretRealmDetail = false
                    selectedSecretRealm = null
                }
            )
        }
    }

    if (showLevelDetail) {
        // 打开 BEAST 类型关卡详情时锁定该妖兽（防止月度结算被 AI 攻击）
        LaunchedEffect(showLevelDetail, selectedLevel) {
            val lvl = selectedLevel
            if (lvl != null && lvl.levelType == LevelType.BEAST) {
                viewModel.lockBeast(lvl.id)
            }
        }

        selectedLevel?.let { level ->
            LevelDetailDialog(
                level = level,
                disciples = disciples,
                viewModel = viewModel,
                onAttack = { slotIds ->
                    viewModel.attackWorldLevel(level.id, slotIds)
                    viewModel.unlockBeast(level.id)
                    showLevelDetail = false
                    selectedLevel = null
                },
                onDismiss = {
                    viewModel.unlockBeast(selectedLevel?.id ?: "")
                    showLevelDetail = false
                    selectedLevel = null
                }
            )
        }
    }

    if (showSectTradeDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedTradeSectId }
        SectTradeDialog(
            sect = sect,
            gameData = gameData,
            tradeItems = sectTradeItems,
            viewModel = viewModel,
            interactionViewModel = interactionViewModel,
            onDismiss = { interactionViewModel.closeSectTradeDialog() }
        )
    }

    if (showScoutDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedScoutSectId }
        val eligible = remember(disciples) {
            disciples.filter { it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE }
        }
        ScoutDialog(
            sectName = sect?.name ?: "未知",
            disciples = eligible,
            viewModel = viewModel,
            onScout = { memberIds ->
                interactionViewModel.startScoutMission(memberIds, selectedScoutSectId ?: "")
            },
            onDismiss = { interactionViewModel.closeScoutDialog() }
        )
    }

    }

}
