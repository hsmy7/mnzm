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
    val currentYear = gameData?.gameYear ?: 1
    val isAlly = interactionViewModel.isAlly(sect.id)
    val isPlayerVassal = interactionViewModel.isPlayerVassal(sect.id)
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect.id)?.lastGiftYear ?: 0) == currentYear
    var showAttackDialog by remember { mutableStateOf(false) }
    var showGarrisonSelection by remember { mutableStateOf<Int?>(null) }

    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val discipleMap = disciples.associateBy { it.id }
    val relation = if (playerSect != null) {
        gameData?.sectRelations?.find {
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0

    val relationLevel = FavorDomain.getLevel(relation)
    val relationColor = Color(relationLevel.colorHex)

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "", mode = DialogMode.Half, scrollableContent = false) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                val titleIconResId = com.xianxia.sect.ui.components.sectIconRes(sect.level)
                if (titleIconResId != null) {
                    Image(
                        painter = painterResource(id = titleIconResId),
                        contentDescription = sect.levelName,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text(
                    text = sect.name,
                    fontSize = AppTypography.Title,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            // Tags that were in the header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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

            if (!sect.isPlayerSect) {
                val ownerSect = gameData?.worldMapSects?.find { it.id == sect.occupierSectId }
                val affiliationName = if (sect.occupierSectId.isNotEmpty() && ownerSect != null) {
                    ownerSect.name
                } else {
                    sect.name
                }
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
                        text = "(${relation})",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            if (!sect.isPlayerSect && !sect.isPlayerOccupied) {
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Text(
                    text = "弟子分布",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                val scoutInfo = gameData?.sectDetails?.get(sect.id)?.scoutInfo ?: SectScoutInfo()
                val isScouted = scoutInfo.sectId.isNotEmpty()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (0..4).forEach { realmIndex ->
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

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (5..9).forEach { realmIndex ->
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

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameButton(
                        text = "探查",
                        onClick = {
                            interactionViewModel.openScoutDialog(sect.id)
                        }
                    )

                    GameButton(
                        text = "外交",
                        onClick = {
                            interactionViewModel.openSectDiplomacyDialog(sect.id)
                            onDismiss()
                        }
                    )

                    GameButton(
                        text = "交易",
                        onClick = {
                            interactionViewModel.openSectTradeDialog(sect.id)
                            onDismiss()
                        }
                    )

                    GameButton(
                        text = if (isPlayerVassal) "附属宗门" else "进攻",
                        onClick = {
                            showAttackDialog = true
                        },
                        enabled = !isPlayerVassal
                    )
                }
            }

            if (sect.isPlayerOccupied) {
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "驻守弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                val latestSect = gameData?.worldMapSects?.find { it.id == sect.id } ?: sect
                val garrisonSlots = latestSect.garrisonSlots

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
                                            showGarrisonSelection = slotIndex
                                        }
                                    },
                                    onSwap = {
                                        showGarrisonSelection = slotIndex
                                    },
                                    onRemoveClick = {
                                        garrisonViewModel.removeGarrisonDisciple(sect.id, slotIndex)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                GameButton(
                    text = "进入",
                    onClick = {
                        viewModel.enterSect(sect.id)
                        viewModel.dismissDialog()
                    }
                )
            }
            if (sect.isPlayerSect) {
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
                garrisonViewModel.attackSect(sect.id, attackSlots)
                showAttackDialog = false
                onDismiss()
            },
            onDismiss = { showAttackDialog = false }
        )
    }

    if (showGarrisonSelection != null) {
        val slotIndex = showGarrisonSelection ?: return
        val latestSect = gameData?.worldMapSects?.find { it.id == sect.id } ?: sect
        val garrisonedIds = latestSect.garrisonSlots.map { it.discipleId }.filter { it.isNotEmpty() }.toSet()
        val showAllEnabled = gameData?.showAllAvailableDisciples == true
        val battleAndExplorationIds = remember(gameData) {
            val battleIds = gameData?.battleTeams.orEmpty()
                .flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }.toSet()
            val explorationIds = gameData?.caveExplorationTeams.orEmpty()
                .flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
            battleIds + explorationIds
        }
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = "选择驻守弟子",
                emptyMessage = "暂无空闲弟子",
                additionalCheck = { d -> d.realmLayer > 0 && d.id !in garrisonedIds }
            ),
            disciples = disciples,
            showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds,
            onDismiss = { showGarrisonSelection = null },
            onConfirm = { selected ->
                selected.firstOrNull()?.let { disciple ->
                    if (showAllEnabled && disciple.status != DiscipleStatus.IDLE) {
                        viewModel.releaseDiscipleForReassignment(disciple.id)
                    }
                    garrisonViewModel.assignGarrisonDisciple(sect.id, slotIndex, disciple.id)
                    showGarrisonSelection = null
                }
            }
        )
    }

    // 外交对话界面
    val showSectDiplomacyDialog by interactionViewModel.showSectDiplomacyDialog.collectAsStateWithLifecycle()
    val selectedSectDiplomacySectId by interactionViewModel.selectedSectDiplomacySectId.collectAsStateWithLifecycle()

    if (showSectDiplomacyDialog && selectedSectDiplomacySectId == sect.id) {
        SectDiplomacyDialog(
            sect = sect,
            relation = relation,
            gameData = gameData,
            disciples = disciples,
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

