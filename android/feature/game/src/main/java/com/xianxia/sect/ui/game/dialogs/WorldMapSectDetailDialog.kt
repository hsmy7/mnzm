package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.game.WorldMapGarrisonViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.AppTypography
import com.xianxia.sect.ui.theme.GameColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun WorldMapSectDetailDialog(
    sect: WorldSect,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    onDismiss: () -> Unit
) {
    var showAttackDialog by remember { mutableStateOf(false) }
    var showGarrisonSelection by remember { mutableStateOf<Int?>(null) }
    val state = rememberWorldMapSectState(
        sect = sect, gameData = gameData, disciples = disciples, interactionViewModel = interactionViewModel
    )
    UnifiedGameDialog(onDismissRequest = onDismiss, title = "", mode = DialogMode.Half, scrollableContent = false) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorldMapSectHeader(state = state)
            if (!sect.isPlayerSect && !sect.isPlayerOccupied) {
                WorldMapScoutSection(
                    state = state, interactionViewModel = interactionViewModel,
                    onAttackClick = { showAttackDialog = true }, onDismiss = onDismiss
                )
            }
            if (sect.isPlayerOccupied) {
                WorldMapGarrisonSection(
                    state = state, viewModel = viewModel,
                    garrisonViewModel = garrisonViewModel, onGarrisonSlotClick = { showGarrisonSelection = it }
                )
            }
            if (sect.isPlayerSect) {
                WorldMapPlayerSectSection(viewModel = viewModel)
            }
        }
    }
    if (showAttackDialog) {
        // 状态过滤（空闲/显示所有）委托 AttackDiscipleDialog 内部 filterByDiscipleStatus，
        // 此处不得预过滤 IDLE（回归：预过滤会导致"显示所有弟子"勾选失效）
        AttackDiscipleDialog(
            sectName = sect.name,
            disciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            onAttack = { attackSlots ->
                garrisonViewModel.attackSect(sect.id, attackSlots); showAttackDialog = false; onDismiss()
            },
            onDismiss = { showAttackDialog = false }
        )
    }
    if (showGarrisonSelection != null) {
        WorldMapGarrisonSelectionDialog(
            state = state,
            viewModel = viewModel,
            garrisonViewModel = garrisonViewModel,
            showGarrisonSelection = showGarrisonSelection,
            onDismiss = { showGarrisonSelection = null }
        )
    }
    WorldMapSectDiplomacyDialog(state = state, interactionViewModel = interactionViewModel)
}

/** 宗门详情派生状态（WorldMapSectDetailDialog 拆分） */
private data class WorldMapSectState(
    val sect: WorldSect,
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>,
    val discipleMap: Map<String, DiscipleAggregate>,
    val relation: Int,
    val isAlly: Boolean,
    val isPlayerVassal: Boolean
)

/** 宗门详情派生状态计算（WorldMapSectDetailDialog 拆分） */
@Composable
private fun rememberWorldMapSectState(
    sect: WorldSect,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    interactionViewModel: WorldMapInteractionViewModel
): WorldMapSectState {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val discipleMap = disciples.associateBy { it.id }
    val relation = if (playerSect != null) {
        gameData?.sectRelations?.find {
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0
    return WorldMapSectState(
        sect = sect,
        gameData = gameData,
        disciples = disciples,
        discipleMap = discipleMap,
        relation = relation,
        isAlly = interactionViewModel.isAlly(sect.id),
        isPlayerVassal = interactionViewModel.isPlayerVassal(sect.id)
    )
}

/** 宗门详情头部（WorldMapSectDetailDialog 拆分）：标题行 + 标签行 + 所属势力/关系行 */
@Composable
private fun WorldMapSectHeader(state: WorldMapSectState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        val titleIconResId = com.xianxia.sect.ui.components.sectIconRes(state.sect.level)
        if (titleIconResId != null) {
            Image(
                painter = painterResource(id = titleIconResId),
                contentDescription = state.sect.levelName,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = state.sect.name,
            fontSize = AppTypography.Title,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }

    WorldMapSectTagRow(
        isAlly = state.isAlly,
        isPlayerSect = state.sect.isPlayerSect
    )

    if (!state.sect.isPlayerSect) {
        WorldMapSectAffiliationRow(state = state)
    }
}

/** 宗门标签行（WorldMapSectDetailDialog 拆分）：本宗/盟友 */
@Composable
private fun WorldMapSectTagRow(
    isAlly: Boolean,
    isPlayerSect: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isPlayerSect) {
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
                color = GameColors.Success,
                modifier = Modifier
                    .background(
                        Color(0xFFE8F5E9),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/** 所属势力/关系行（WorldMapSectDetailDialog 拆分） */
@Composable
private fun WorldMapSectAffiliationRow(state: WorldMapSectState) {
    val ownerSect = state.gameData?.worldMapSects?.find { it.id == state.sect.occupierSectId }
    val affiliationName = if (state.sect.occupierSectId.isNotEmpty() && ownerSect != null) {
        ownerSect.name
    } else {
        state.sect.name
    }
    val relationLevel = FavorDomain.getLevel(state.relation)
    val relationColor = Color(relationLevel.colorHex)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "所属势力:",
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = affiliationName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "关系:",
            fontSize = 12.sp,
            color = Color.Black
        )
        Text(
            text = relationLevel.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = relationColor
        )
        Text(
            text = "(${state.relation})",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/** 探查/操作区（WorldMapSectDetailDialog 拆分）：弟子分布 + 操作按钮 */
@Composable
private fun WorldMapScoutSection(
    state: WorldMapSectState,
    interactionViewModel: WorldMapInteractionViewModel,
    onAttackClick: () -> Unit,
    onDismiss: () -> Unit
) {
    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

    Text(
        text = "弟子分布",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )

    val scoutInfo = state.gameData?.sectDetails?.get(state.sect.id)?.scoutInfo ?: SectScoutInfo()
    val isScouted = scoutInfo.sectId.isNotEmpty()

    WorldMapRealmRow(
        realmIndexes = 0..4,
        isScouted = isScouted,
        scoutInfo = scoutInfo
    )

    Spacer(modifier = Modifier.height(4.dp))

    WorldMapRealmRow(
        realmIndexes = 5..9,
        isScouted = isScouted,
        scoutInfo = scoutInfo
    )

    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

    WorldMapSectActionRow(
        state = state,
        interactionViewModel = interactionViewModel,
        onAttackClick = onAttackClick,
        onDismiss = onDismiss
    )
}

/** 单行境界分布（WorldMapSectDetailDialog 拆分）：5 个境界 + 数量 */
@Composable
private fun WorldMapRealmRow(
    realmIndexes: IntRange,
    isScouted: Boolean,
    scoutInfo: SectScoutInfo
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        realmIndexes.forEach { realmIndex ->
            val realmName = GameConfig.Realm.getName(realmIndex)
            val count = if (isScouted) scoutInfo.disciples[realmIndex] ?: 0 else 0
            val displayText = if (isScouted) "$count" else "?"
            val textColor = if (isScouted) {
                if (count > 0) GameColors.Success else Color.Black
            } else {
                GameColors.Warning
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = realmName,
                    fontSize = 10.sp,
                    color = Color.Black
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

/** 宗门操作按钮行（WorldMapSectDetailDialog 拆分）：探查/外交/交易/进攻 */
@Composable
private fun WorldMapSectActionRow(
    state: WorldMapSectState,
    interactionViewModel: WorldMapInteractionViewModel,
    onAttackClick: () -> Unit,
    onDismiss: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameButton(
            text = "探查",
            onClick = {
                interactionViewModel.openScoutDialog(state.sect.id)
            }
        )

        GameButton(
            text = "外交",
            onClick = {
                interactionViewModel.openSectDiplomacyDialog(state.sect.id)
                onDismiss()
            }
        )

        GameButton(
            text = "交易",
            onClick = {
                interactionViewModel.openSectTradeDialog(state.sect.id)
                onDismiss()
            }
        )

        GameButton(
            text = if (state.isPlayerVassal) "附属宗门" else "进攻",
            onClick = {
                onAttackClick()
            },
            enabled = !state.isPlayerVassal
        )
    }
}

/** 驻守弟子区（WorldMapSectDetailDialog 拆分）：槽位网格 + 进入按钮 */
@Composable
private fun WorldMapGarrisonSection(
    state: WorldMapSectState,
    viewModel: GameViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    onGarrisonSlotClick: (Int) -> Unit
) {
    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "驻守弟子",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )

    val latestSect = state.gameData?.worldMapSects?.find { it.id == state.sect.id } ?: state.sect
    val garrisonSlots = latestSect.garrisonSlots

    WorldMapGarrisonGrid(
        garrisonSlots = garrisonSlots,
        sectId = state.sect.id,
        disciples = state.disciples,
        discipleMap = state.discipleMap,
        viewModel = viewModel,
        garrisonViewModel = garrisonViewModel,
        onGarrisonSlotClick = onGarrisonSlotClick
    )

    Spacer(modifier = Modifier.height(8.dp))

    GameButton(
        text = "进入",
        onClick = {
            viewModel.enterSect(state.sect.id)
            viewModel.dismissDialog()
        }
    )
}

/** 驻守弟子槽位网格（WorldMapSectDetailDialog 拆分）：2 行 × 5 列 */
@Composable
private fun WorldMapGarrisonGrid(
    garrisonSlots: List<GarrisonSlot>,
    sectId: String,
    disciples: List<DiscipleAggregate>,
    discipleMap: Map<String, DiscipleAggregate>,
    viewModel: GameViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    onGarrisonSlotClick: (Int) -> Unit
) {
    for (row in 0..1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            for (col in 0..4) {
                val slotIndex = row * 5 + col
                if (slotIndex < garrisonSlots.size) {
                    val gSlot = garrisonSlots[slotIndex]
                    val gDisciple = if (gSlot.isActive) discipleMap[gSlot.discipleId] else null
                    GarrisonSlotBox(
                        disciple = gDisciple,
                        spiritRootColor = gSlot.discipleSpiritRootColor,
                        portraitRes = gSlot.portraitRes,
                        onClick = {
                            if (gDisciple != null) {
                                viewModel.showDiscipleDetail(DiscipleDetailRequest(gDisciple, disciples))
                            } else {
                                onGarrisonSlotClick(slotIndex)
                            }
                        },
                        onSwap = {
                            onGarrisonSlotClick(slotIndex)
                        },
                        onRemoveClick = {
                            garrisonViewModel.removeGarrisonDisciple(sectId, slotIndex)
                        }
                    )
                }
            }
        }
    }
}

/** 本宗详情区（WorldMapSectDetailDialog 拆分）：进入按钮 */
@Composable
private fun WorldMapPlayerSectSection(viewModel: GameViewModel) {
    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

    Spacer(modifier = Modifier.height(4.dp))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GameButton(
            text = "进入",
            onClick = {
                viewModel.enterSect("")
                viewModel.dismissDialog()
            }
        )
    }
}

/** 驻守弟子选择弹窗（WorldMapSectDetailDialog 拆分） */
@Composable
private fun WorldMapGarrisonSelectionDialog(
    state: WorldMapSectState,
    viewModel: GameViewModel,
    garrisonViewModel: WorldMapGarrisonViewModel,
    showGarrisonSelection: Int?,
    onDismiss: () -> Unit
) {
    val slotIndex = showGarrisonSelection ?: return
    val latestSect = state.gameData?.worldMapSects?.find { it.id == state.sect.id } ?: state.sect
    val garrisonedIds = latestSect.garrisonSlots.map { it.discipleId }.filter { it.isNotEmpty() }.toSet()
    val showAllEnabled = state.gameData?.showAllAvailableDisciples == true
    val battleAndExplorationIds = remember(state.gameData) {
        val battleIds = state.gameData?.battleTeams.orEmpty()
            .flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
        val explorationIds = state.gameData?.caveExplorationTeams.orEmpty()
            .flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
        battleIds + explorationIds
    }
    DiscipleSelectorDialog(
        config = DiscipleSelectorConfig(
            title = "选择驻守弟子",
            emptyMessage = "暂无空闲弟子",
            additionalCheck = { d -> d.realmLayer > 0 && d.id !in garrisonedIds }
        ),
        disciples = state.disciples,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds,
        onDismiss = onDismiss,
        onConfirm = { selected ->
            selected.firstOrNull()?.let { disciple ->
                if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                    viewModel.releaseDiscipleForReassignment(disciple.id)
                }
                garrisonViewModel.assignGarrisonDisciple(state.sect.id, slotIndex, disciple.id)
                onDismiss()
            }
        }
    )
}

/** 外交对话子弹窗（WorldMapSectDetailDialog 拆分） */
@Composable
private fun WorldMapSectDiplomacyDialog(
    state: WorldMapSectState,
    interactionViewModel: WorldMapInteractionViewModel
) {
    val showSectDiplomacyDialog by interactionViewModel.showSectDiplomacyDialog.collectAsStateWithLifecycle()
    val selectedSectDiplomacySectId by interactionViewModel.selectedSectDiplomacySectId.collectAsStateWithLifecycle()

    if (showSectDiplomacyDialog && selectedSectDiplomacySectId == state.sect.id) {
        SectDiplomacyDialog(
            sect = state.sect,
            relation = state.relation,
            gameData = state.gameData,
            disciples = state.disciples,
            interactionViewModel = interactionViewModel,
            onDismiss = { interactionViewModel.closeSectDiplomacyDialog() }
        )
    }
}

@Composable
private fun GarrisonSlotBox(
    disciple: DiscipleAggregate?,
    spiritRootColor: String,
    portraitRes: String,
    onClick: () -> Unit,
    onSwap: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val borderColor = if (disciple != null) {
        try { Color(android.graphics.Color.parseColor(spiritRootColor)) }
        catch (e: Exception) { GameColors.Border }
    } else {
        GameColors.Border
    }

    DiscipleSlot(
        disciple = disciple,
        borderColor = borderColor,
        showActions = true,
        onSlotClick = { onClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onRemoveClick() },
        onSwap = { onSwap() }
    )
}
