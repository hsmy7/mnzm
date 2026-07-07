package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun DiplomacyDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel,
    onDismiss: () -> Unit
) {
    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val worldSects = gameData?.worldMapSects?.filter { !it.isPlayerSect } ?: emptyList()
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

    val showSectTradeDialog by interactionViewModel.showSectTradeDialog.collectAsStateWithLifecycle()
    val showSectDiplomacyDialog by interactionViewModel.showSectDiplomacyDialog.collectAsStateWithLifecycle()
    val selectedTradeSectId by interactionViewModel.selectedTradeSectId.collectAsStateWithLifecycle()
    val selectedSectDiplomacySectId by interactionViewModel.selectedSectDiplomacySectId.collectAsStateWithLifecycle()
    val sectTradeItems by interactionViewModel.sectTradeItems.collectAsStateWithLifecycle()

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
                        items(sortedSects, key = { it.id }, contentType = { "sect" }) { sect ->
                            DiplomacySectCard(
                                sect = sect,
                                relation = sectFavors[sect] ?: 0,
                                gameData = gameData,
                                isAlly = interactionViewModel.isAlly(sect.id),
                                onOpenDiplomacyDialogue = {
                                    interactionViewModel.openSectDiplomacyDialog(sect.id)
                                },
                                onTrade = {
                                    interactionViewModel.openSectTradeDialog(sect.id)
                                }
                            )
                        }
                }
            }
        }
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

    if (showSectDiplomacyDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == selectedSectDiplomacySectId }
        if (sect != null) {
            SectDiplomacyDialog(
                sect = sect,
                relation = sectFavors[sect] ?: 0,
                gameData = gameData,
                disciples = emptyList(),
                interactionViewModel = interactionViewModel,
                onDismiss = { interactionViewModel.closeSectDiplomacyDialog() }
            )
        }
    }
}

@Composable
internal fun DiplomacySectCard(
    sect: WorldSect,
    relation: Int,
    gameData: GameData?,
    isAlly: Boolean,
    onOpenDiplomacyDialogue: () -> Unit,
    onTrade: () -> Unit
) {
    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val relationColor = Color(relationLevel.colorHex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            // 左侧：等级图标 + 宗门名称 + 好感度
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val sectIconResId = com.xianxia.sect.ui.components.sectIconRes(sect.level)
                if (sectIconResId != null) {
                    Image(
                        painter = painterResource(id = sectIconResId),
                        contentDescription = sect.levelName,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Text(
                    text = sect.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Text(
                    text = "${relationLevel.displayName}: $relation",
                    fontSize = 10.sp,
                    color = relationColor
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

            // 右侧：操作按钮
            if (!sect.isPlayerOccupied) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GameButton(
                        text = "外交",
                        onClick = onOpenDiplomacyDialogue,
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
