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
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.domain.FavorDomain
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

    val sectFavors = rememberDiplomacyFavors(
        playerSect = playerSect, worldSects = worldSects, sectRelations = sectRelations
    )

    val sortedSects = worldSects.sortedByDescending { sectFavors[it] ?: 0 }

    val diplomacyFlow = rememberDiplomacyFlow(interactionViewModel = interactionViewModel)

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "外交",
        mode = DialogMode.Full,
        scrollableContent = false
    ) {
        DiplomacySectList(
            sortedSects = sortedSects,
            sectFavors = sectFavors,
            gameData = gameData,
            isAlly = { interactionViewModel.isAlly(it) },
            onOpenDiplomacyDialogue = { interactionViewModel.openSectDiplomacyDialog(it) },
            onTrade = { interactionViewModel.openSectTradeDialog(it) }
        )
    }

    DiplomacySubDialogs(
        flow = diplomacyFlow,
        gameData = gameData,
        sectFavors = sectFavors,
        viewModel = viewModel,
        interactionViewModel = interactionViewModel
    )
}

/** 宗门好感度派生（DiplomacyDialog 拆分） */
@Composable
private fun rememberDiplomacyFavors(
    playerSect: WorldSect?,
    worldSects: List<WorldSect>,
    sectRelations: List<SectRelation>?
): Map<WorldSect, Int> {
    return remember(playerSect, worldSects, sectRelations) {
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
}

/** 外交子弹窗状态订阅打包（DiplomacyDialog 拆分，参数 >6 规避 LongParameterList） */
private data class DiplomacyFlowState(
    val showSectTradeDialog: Boolean,
    val showSectDiplomacyDialog: Boolean,
    val selectedTradeSectId: String?,
    val selectedSectDiplomacySectId: String?,
    val sectTradeItems: List<MerchantItem>
)

/** 外交子弹窗状态订阅（DiplomacyDialog 拆分） */
@Composable
private fun rememberDiplomacyFlow(interactionViewModel: WorldMapInteractionViewModel): DiplomacyFlowState {
    val showSectTradeDialog by interactionViewModel.showSectTradeDialog.collectAsStateWithLifecycle()
    val showSectDiplomacyDialog by interactionViewModel.showSectDiplomacyDialog.collectAsStateWithLifecycle()
    val selectedTradeSectId by interactionViewModel.selectedTradeSectId.collectAsStateWithLifecycle()
    val selectedSectDiplomacySectId by interactionViewModel.selectedSectDiplomacySectId.collectAsStateWithLifecycle()
    val sectTradeItems by interactionViewModel.sectTradeItems.collectAsStateWithLifecycle()
    return DiplomacyFlowState(
        showSectTradeDialog = showSectTradeDialog,
        showSectDiplomacyDialog = showSectDiplomacyDialog,
        selectedTradeSectId = selectedTradeSectId,
        selectedSectDiplomacySectId = selectedSectDiplomacySectId,
        sectTradeItems = sectTradeItems
    )
}

/** 外交宗门列表区（DiplomacyDialog 拆分）：空态或 LazyColumn 卡片列表 */
@Composable
private fun DiplomacySectList(
    sortedSects: List<WorldSect>,
    sectFavors: Map<WorldSect, Int>,
    gameData: GameData?,
    isAlly: (String) -> Boolean,
    onOpenDiplomacyDialogue: (String) -> Unit,
    onTrade: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            if (sortedSects.isEmpty()) {
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
                            isAlly = isAlly(sect.id),
                            onOpenDiplomacyDialogue = { onOpenDiplomacyDialogue(sect.id) },
                            onTrade = { onTrade(sect.id) }
                        )
                    }
                }
            }
        }
    }
}

/** 外交交易/对话子弹窗（DiplomacyDialog 拆分） */
@Composable
private fun DiplomacySubDialogs(
    flow: DiplomacyFlowState,
    gameData: GameData?,
    sectFavors: Map<WorldSect, Int>,
    viewModel: GameViewModel,
    interactionViewModel: WorldMapInteractionViewModel
) {
    if (flow.showSectTradeDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == flow.selectedTradeSectId }
        SectTradeDialog(
            sect = sect,
            gameData = gameData,
            tradeItems = flow.sectTradeItems,
            viewModel = viewModel,
            interactionViewModel = interactionViewModel,
            onDismiss = { interactionViewModel.closeSectTradeDialog() }
        )
    }

    if (flow.showSectDiplomacyDialog) {
        val sect = gameData?.worldMapSects?.find { it.id == flow.selectedSectDiplomacySectId }
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
    val relationLevel = FavorDomain.getLevel(relation)
    val relationColor = Color(relationLevel.colorHex)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.CardBackground, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiplomacySectCardInfo(
            sect = sect,
            relationLevel = relationLevel,
            relation = relation,
            relationColor = relationColor,
            isAlly = isAlly
        )
        DiplomacySectCardActions(
            isPlayerOccupied = sect.isPlayerOccupied,
            onOpenDiplomacyDialogue = onOpenDiplomacyDialogue,
            onTrade = onTrade
        )
    }
}

/** 宗门卡片左侧信息（DiplomacySectCard 拆分）：图标 + 名称 + 好感度 + 盟友徽标 */
@Composable
private fun RowScope.DiplomacySectCardInfo(
    sect: WorldSect,
    relationLevel: SectRelationLevel,
    relation: Int,
    relationColor: Color,
    isAlly: Boolean
) {
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

/** 宗门卡片右侧操作按钮（DiplomacySectCard 拆分）：外交/交易 */
@Composable
private fun DiplomacySectCardActions(
    isPlayerOccupied: Boolean,
    onOpenDiplomacyDialogue: () -> Unit,
    onTrade: () -> Unit
) {
    if (!isPlayerOccupied) {
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
