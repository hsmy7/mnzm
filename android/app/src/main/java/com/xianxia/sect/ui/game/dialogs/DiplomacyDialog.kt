package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

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

    val showGiftDialog by worldMapViewModel.showGiftDialog.collectAsState()
    val showAllianceDialog by worldMapViewModel.showAllianceDialog.collectAsState()
    val showSectTradeDialog by worldMapViewModel.showSectTradeDialog.collectAsState()
    val selectedGiftSectId by worldMapViewModel.selectedGiftSectId.collectAsState()
    val selectedAllianceSectId by worldMapViewModel.selectedAllianceSectId.collectAsState()
    val selectedTradeSectId by worldMapViewModel.selectedTradeSectId.collectAsState()
    val sectTradeItems by worldMapViewModel.sectTradeItems.collectAsState()

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "外交",
        mode = DialogMode.Full,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                if (worldSects.isEmpty()) {
                    Text(
                        text = "暂无其他宗门",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sortedSects, key = { it.id }) { sect ->
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
        }
    }
    }

    if (showGiftedMessage) {
        GiftedMessageToast(
            message = "今年已送过礼品等明年再来吧",
            onDismiss = { showGiftedMessage = false }
        )
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

    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
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
                        Image(
                            painter = painterResource(id = com.xianxia.sect.ui.components.sectIconRes(sect.level)),
                            contentDescription = sect.levelName,
                            modifier = Modifier.size(26.dp)
                        )
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
                        color = Color.Black
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = relationLevel.displayName,
                        fontSize = 10.sp,
                        color = Color.Black
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

            if (!sect.isPlayerOccupied) {
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
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )

                    GameButton(
                        text = if (isAlly) "盟约" else "结盟",
                        onClick = onFormAlliance,
                        enabled = relationLevel == SectRelationLevel.INTIMATE || isAlly,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )

                    GameButton(
                        text = "交易",
                        onClick = onTrade,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
            }
        }
    }
}
