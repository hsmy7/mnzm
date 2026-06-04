package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.model.WorldSect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.game.map.MapItemMapper
import com.xianxia.sect.ui.game.map.WorldMapScreen

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorldMapDialog(
    worldSects: List<WorldSect>,
    mapRenderData: WorldMapRenderData,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    var selectedSect by remember { mutableStateOf<WorldSect?>(null) }
    var showSectDetail by remember { mutableStateOf(false) }
    var selectedLevel by remember { mutableStateOf<MapItem.Level?>(null) }
    var showLevelDetail by remember { mutableStateOf(false) }

    // WorldMap sub-dialogs — rendered locally to keep world map as background
    val showSectTradeDialog by worldMapViewModel.showSectTradeDialog.collectAsStateWithLifecycle()
    val selectedTradeSectId by worldMapViewModel.selectedTradeSectId.collectAsStateWithLifecycle()
    val sectTradeItems by worldMapViewModel.sectTradeItems.collectAsStateWithLifecycle()
    val showGiftDialog by worldMapViewModel.showGiftDialog.collectAsStateWithLifecycle()
    val selectedGiftSectId by worldMapViewModel.selectedGiftSectId.collectAsStateWithLifecycle()
    val showAllianceDialog by worldMapViewModel.showAllianceDialog.collectAsStateWithLifecycle()
    val selectedAllianceSectId by worldMapViewModel.selectedAllianceSectId.collectAsStateWithLifecycle()
    val showEnvoyDiscipleSelectDialog by worldMapViewModel.showEnvoyDiscipleSelectDialog.collectAsStateWithLifecycle()
    val showScoutDialog by worldMapViewModel.showScoutDialog.collectAsStateWithLifecycle()
    val selectedScoutSectId by worldMapViewModel.selectedScoutSectId.collectAsStateWithLifecycle()
    val playerSect = mapRenderData.worldMapSects.find { it.isPlayerSect }
    val playerSectX = playerSect?.x ?: 2000f
    val playerSectY = playerSect?.y ?: 1750f
    val sectItems = remember(worldSects) {
        MapItemMapper.fromWorldSects(worldSects, emptySet())
    }

    val levelItems = remember(mapRenderData.worldLevels) {
        MapItemMapper.fromLevels(mapRenderData.worldLevels)
    }

    val mapItems = remember(sectItems, levelItems) {
        sectItems + levelItems
    }

    val paths = remember(worldSects) {
        MapItemMapper.fromPaths(worldSects)
    }

    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
    WorldMapScreen(
        items = mapItems,
        paths = paths,
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
        onLevelClick = { levelItem ->
            selectedLevel = levelItem
            showLevelDetail = true
        }
    )

    if (showSectDetail) {
        selectedSect?.let { sect ->
            WorldMapSectDetailDialog(
                sect = sect,
                gameData = gameData,
                disciples = disciples,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = {
                    showSectDetail = false
                    selectedSect = null
                }
            )
        }
    }

    if (showLevelDetail) {
        selectedLevel?.let { level ->
            LevelDetailDialog(
                level = level,
                disciples = disciples,
                viewModel = viewModel,
                onAttack = { slotIds ->
                    viewModel.attackWorldLevel(level.id, slotIds)
                    showLevelDetail = false
                    selectedLevel = null
                },
                onDismiss = {
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
            worldMapViewModel = worldMapViewModel,
            onDismiss = { worldMapViewModel.closeSectTradeDialog() }
        )
    }

    if (showGiftDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedGiftSectId }
        GiftDialog(
            sect = sect,
            gameData = gameData,
            viewModel = viewModel,
            worldMapViewModel = worldMapViewModel,
            onDismiss = { worldMapViewModel.closeGiftDialog() }
        )
    }

    if (showAllianceDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedAllianceSectId }
        AllianceDialog(
            sect = sect,
            gameData = gameData,
            viewModel = viewModel,
            worldMapViewModel = worldMapViewModel,
            onDismiss = { worldMapViewModel.closeAllianceDialog() }
        )
    }

    if (showEnvoyDiscipleSelectDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedAllianceSectId }
        val eligible = remember(disciples, sect?.level) {
            val req = sect?.level?.let { worldMapViewModel.getEnvoyRealmRequirement(it) } ?: 0
            disciples.filter { it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE && it.realm <= req }
        }
        EnvoyDiscipleSelectDialog(
            sect = sect,
            disciples = eligible,
            viewModel = viewModel,
            worldMapViewModel = worldMapViewModel,
            onDismiss = { worldMapViewModel.closeEnvoyDiscipleSelectDialog() }
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
                worldMapViewModel.startScoutMission(memberIds, selectedScoutSectId ?: "")
            },
            onDismiss = { worldMapViewModel.closeScoutDialog() }
        )
    }

    }

}
