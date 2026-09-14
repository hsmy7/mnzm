package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
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
import com.xianxia.sect.ui.game.delegate.attackWorldLevel

/** 世界地图子对话框 UI 状态 */
private class WorldMapDialogUiState {
    var selectedSect by mutableStateOf<WorldSect?>(null)
    var showSectDetail by mutableStateOf(false)
    var selectedLevel by mutableStateOf<MapItem.Level?>(null)
    var showLevelDetail by mutableStateOf(false)
    var selectedSecretRealm by mutableStateOf<MapItem.SecretRealm?>(null)
    var showSecretRealmDetail by mutableStateOf(false)
    var showSecretRealmExploration by mutableStateOf(false)
}

/** 世界地图子对话框上下文 */
/** 世界地图弹窗显示输入（WorldMapDialog 参数分组）：宗门列表 + 渲染数据 + 档案 + 弟子 */
data class WorldMapDialogInputs(
    val worldSects: List<WorldSect>,
    val mapRenderData: WorldMapRenderData,
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>
)

private data class WorldMapDialogContext(
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>,
    val viewModel: GameViewModel,
    val interactionViewModel: WorldMapInteractionViewModel,
    val garrisonViewModel: WorldMapGarrisonViewModel,
    val secretRealmViewModel: SecretRealmViewModel
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WorldMapDialog(
    inputs: WorldMapDialogInputs,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    onDismiss: () -> Unit
) {
    val worldSects = inputs.worldSects
    val mapRenderData = inputs.mapRenderData
    val gameData = inputs.gameData
    val disciples = inputs.disciples
    val uiState = remember { WorldMapDialogUiState() }
    // 秘境探索全屏宿主（会话存在时暂停游戏时间，退出恢复）
    val secretRealmViewModel: SecretRealmViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val context = WorldMapDialogContext(
        gameData = gameData,
        disciples = disciples,
        viewModel = viewModel,
        interactionViewModel = interactionViewModel,
        garrisonViewModel = garrisonViewModel,
        secretRealmViewModel = secretRealmViewModel
    )
    val (playerSectX, playerSectY) = playerSectCenter(mapRenderData)
    val mapItems = buildWorldMapItems(worldSects = worldSects, mapRenderData = mapRenderData)
    val (showSectTradeDialog, selectedTradeSectId, sectTradeItems) =
        collectTradeDialogState(interactionViewModel)
    val (showScoutDialog, selectedScoutSectId) =
        collectScoutDialogState(interactionViewModel)

    BackHandler(onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        WorldMapScreenHost(
            mapItems = mapItems,
            focusWorld = Offset(playerSectX, playerSectY),
            onBack = onDismiss,
            onUserInteraction = viewModel::onUserInteraction,
            onSectClick = { item ->
                val sect = worldSects.find { it.id == item.id }
                if (sect != null) {
                    uiState.selectedSect = sect
                    uiState.showSectDetail = true
                }
            },
            onLevelClick = { item ->
                uiState.selectedLevel = item
                uiState.showLevelDetail = true
            },
            onSecretRealmClick = { item ->
                uiState.selectedSecretRealm = item
                uiState.showSecretRealmDetail = true
            }
        )
        WorldMapSubDialogGate(
            context = context, uiState = uiState,
            showSectTradeDialog = showSectTradeDialog,
            selectedTradeSectId = selectedTradeSectId,
            sectTradeItems = sectTradeItems,
            showScoutDialog = showScoutDialog,
            selectedScoutSectId = selectedScoutSectId
        )
    }
}

/** 世界地图子弹窗集：秘境探索/宗门详情/秘境详情/关卡详情/交易/侦察 */
@Composable
private fun WorldMapSubDialogGate(
    context: WorldMapDialogContext,
    uiState: WorldMapDialogUiState,
    showSectTradeDialog: Boolean,
    selectedTradeSectId: String?,
    sectTradeItems: List<MerchantItem>,
    showScoutDialog: Boolean,
    selectedScoutSectId: String?
) {
    WorldMapSecretRealmExplorationGate(context = context, uiState = uiState)
    WorldMapSectDetailGate(context = context, uiState = uiState)
    WorldMapSecretRealmDetailGate(context = context, uiState = uiState)
    WorldMapLevelDetailGate(context = context, uiState = uiState)
    WorldMapSectTradeGate(
        context = context,
        showSectTradeDialog = showSectTradeDialog,
        selectedTradeSectId = selectedTradeSectId,
        sectTradeItems = sectTradeItems
    )
    WorldMapScoutGate(
        context = context,
        showScoutDialog = showScoutDialog,
        selectedScoutSectId = selectedScoutSectId
    )
}

/** 玩家宗门中心坐标 */
private fun playerSectCenter(mapRenderData: WorldMapRenderData): Pair<Float, Float> {
    val playerSect = mapRenderData.worldMapSects.find { it.isPlayerSect }
    return (playerSect?.x ?: MapCoordinateSystem.WORLD_WIDTH / 2f) to
        (playerSect?.y ?: MapCoordinateSystem.WORLD_HEIGHT / 2f)
}

/** 地图渲染项构建：宗门 + 关卡 + 秘境 */
@Composable
private fun buildWorldMapItems(
    worldSects: List<WorldSect>,
    mapRenderData: WorldMapRenderData
): List<MapItem> {
    val sectItems = remember(worldSects) {
        MapItemMapper.fromWorldSects(worldSects, emptySet())
    }
    val levelItems = remember(mapRenderData.worldLevels) {
        MapItemMapper.fromLevels(mapRenderData.worldLevels)
    }
    val realmItem = remember(mapRenderData.secretRealm) {
        mapRenderData.secretRealm?.let { MapItemMapper.fromSecretRealm(it) }
    }
    return remember(sectItems, levelItems, realmItem) {
        sectItems + levelItems + listOfNotNull(realmItem)
    }
}

/** 世界地图屏幕宿主：地图 + 点击分派 */
@Composable
private fun WorldMapScreenHost(
    mapItems: List<MapItem>,
    focusWorld: Offset,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit,
    onSectClick: (MapItem.Sect) -> Unit,
    onLevelClick: (MapItem.Level) -> Unit,
    onSecretRealmClick: (MapItem.SecretRealm) -> Unit
) {
    WorldMapScreen(
        items = mapItems,
        focusWorld = focusWorld,
        onBack = onBack,
        onUserInteraction = onUserInteraction,
        onItemClick = { item ->
            when (item) {
                is MapItem.Sect -> onSectClick(item)
                is MapItem.Level -> onLevelClick(item)
                is MapItem.SecretRealm -> onSecretRealmClick(item)
            }
        }
    )
}

/** 远古秘境探索全屏：覆盖地图，返回 = 暂存退出 */
@Composable
private fun WorldMapSecretRealmExplorationGate(
    context: WorldMapDialogContext,
    uiState: WorldMapDialogUiState
) {
    if (uiState.showSecretRealmExploration) {
        SecretRealmExplorationScreen(
            viewModel = context.secretRealmViewModel,
            onExit = {
                uiState.showSecretRealmExploration = false
                context.viewModel.onUserInteraction()
            },
            onFinished = {
                uiState.showSecretRealmExploration = false
                uiState.showSecretRealmDetail = false
                uiState.selectedSecretRealm = null
                context.viewModel.onUserInteraction()
            }
        )
    }
}

/** 宗门详情弹窗 */
@Composable
private fun WorldMapSectDetailGate(
    context: WorldMapDialogContext,
    uiState: WorldMapDialogUiState
) {
    if (uiState.showSectDetail) {
        uiState.selectedSect?.let { sect ->
            WorldMapSectDetailDialog(
                sect = sect,
                gameData = context.gameData,
                disciples = context.disciples,
                viewModel = context.viewModel,
                interactionViewModel = context.interactionViewModel,
                garrisonViewModel = context.garrisonViewModel,
                onDismiss = {
                    uiState.showSectDetail = false
                    uiState.selectedSect = null
                }
            )
        }
    }
}

/** 远古秘境详情弹窗 */
@Composable
private fun WorldMapSecretRealmDetailGate(
    context: WorldMapDialogContext,
    uiState: WorldMapDialogUiState
) {
    if (uiState.showSecretRealmDetail && !uiState.showSecretRealmExploration) {
        val realm = uiState.selectedSecretRealm
        if (realm != null) {
            SecretRealmDetailDialog(
                realm = realm,
                gameData = context.gameData,
                viewModel = context.secretRealmViewModel,
                onStart = {
                    uiState.showSecretRealmDetail = false
                    uiState.showSecretRealmExploration = true
                },
                onContinue = {
                    uiState.showSecretRealmDetail = false
                    uiState.showSecretRealmExploration = true
                },
                onDismiss = {
                    uiState.showSecretRealmDetail = false
                    uiState.selectedSecretRealm = null
                }
            )
        }
    }
}

/** 关卡详情弹窗：BEAST 类型打开时锁定妖兽 */
@Composable
private fun WorldMapLevelDetailGate(
    context: WorldMapDialogContext,
    uiState: WorldMapDialogUiState
) {
    if (uiState.showLevelDetail) {
        // 打开 BEAST 类型关卡详情时锁定该妖兽（防止月度结算被 AI 攻击）
        LaunchedEffect(uiState.showLevelDetail, uiState.selectedLevel) {
            val lvl = uiState.selectedLevel
            if (lvl != null && lvl.levelType == LevelType.BEAST) {
                context.viewModel.beastAttack.lockBeast(lvl.id)
            }
        }

        uiState.selectedLevel?.let { level ->
            LevelDetailDialog(
                level = level,
                disciples = context.disciples,
                viewModel = context.viewModel,
                onAttack = { slotIds ->
                    context.viewModel.navigation.attackWorldLevel(level.id, slotIds)
                    context.viewModel.beastAttack.unlockBeast(level.id)
                    uiState.showLevelDetail = false
                    uiState.selectedLevel = null
                },
                onDismiss = {
                    context.viewModel.beastAttack.unlockBeast(uiState.selectedLevel?.id ?: "")
                    uiState.showLevelDetail = false
                    uiState.selectedLevel = null
                }
            )
        }
    }
}

/** 宗门交易弹窗 */
@Composable
private fun WorldMapSectTradeGate(
    context: WorldMapDialogContext,
    showSectTradeDialog: Boolean,
    selectedTradeSectId: String?,
    sectTradeItems: List<MerchantItem>
) {
    if (showSectTradeDialog) {
        val sect = context.gameData?.worldMapSects?.find { it.id == selectedTradeSectId }
        SectTradeDialog(
            sect = sect,
            gameData = context.gameData,
            tradeItems = sectTradeItems,
            viewModel = context.viewModel,
            interactionViewModel = context.interactionViewModel,
            onDismiss = { context.interactionViewModel.closeSectTradeDialog() }
        )
    }
}

/** 探查弹窗 */
@Composable
private fun WorldMapScoutGate(
    context: WorldMapDialogContext,
    showScoutDialog: Boolean,
    selectedScoutSectId: String?
) {
    if (showScoutDialog) {
        val sect = context.gameData?.worldMapSects?.find { it.id == selectedScoutSectId }
        // 状态过滤（空闲/显示所有）委托 ScoutDialog 内部 DiscipleSelectorDialog，
        // 此处不得预过滤 IDLE（回归：预过滤会导致"显示所有弟子"勾选失效）
        ScoutDialog(
            sectName = sect?.name ?: "未知",
            disciples = context.disciples,
            viewModel = context.viewModel,
            onScout = { memberIds ->
                context.interactionViewModel.startScoutMission(memberIds, selectedScoutSectId ?: "")
            },
            onDismiss = { context.interactionViewModel.closeScoutDialog() }
        )
    }
}

/** 宗门交易弹窗三态收集 */
@Composable
private fun collectTradeDialogState(
    vm: WorldMapInteractionViewModel
): Triple<Boolean, String?, List<MerchantItem>> {
    val show by vm.showSectTradeDialog.collectAsStateWithLifecycle()
    val selectedId by vm.selectedTradeSectId.collectAsStateWithLifecycle()
    val items by vm.sectTradeItems.collectAsStateWithLifecycle()
    return Triple(show, selectedId, items)
}

/** 侦察弹窗两态收集 */
@Composable
private fun collectScoutDialogState(
    vm: WorldMapInteractionViewModel
): Pair<Boolean, String?> {
    val show by vm.showScoutDialog.collectAsStateWithLifecycle()
    val selectedId by vm.selectedScoutSectId.collectAsStateWithLifecycle()
    return Pair(show, selectedId)
}
