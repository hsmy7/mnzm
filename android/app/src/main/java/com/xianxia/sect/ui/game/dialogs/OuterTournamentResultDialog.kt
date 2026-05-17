package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.core.model.CompetitionRankResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.WorldMapViewModel

@Composable
fun OuterTournamentResultDialog(
    competitionResults: List<CompetitionRankResult>,
    allDisciples: List<DiscipleAggregate>,
    gameData: GameData,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val selectedIds = remember { mutableStateListOf<String>() }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "外门大比结果",
        mode = DialogMode.Full,
        scrollableContent = false,
        headerActions = {
            GameButton(
                text = "准入内门",
                onClick = {
                    worldMapViewModel.promoteSelectedDisciplesToInner(selectedIds.toSet())
                }
            )
        }
    ) {
        val topDisciples = remember(competitionResults, allDisciples) {
            competitionResults.mapNotNull { result ->
                allDisciples.find { it.id == result.discipleId }?.to(result.rank)
            }
        }

        if (topDisciples.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无大比结果",
                    fontSize = 14.sp,
                    color = GameColors.TextSecondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(topDisciples, key = { (d, _) -> d.id }) { (disciple, rank) ->
                    val isSelected by remember { derivedStateOf { disciple.id in selectedIds } }
                    PortraitDiscipleCard(
                        disciple = disciple,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelected) {
                                selectedIds.remove(disciple.id)
                            } else {
                                selectedIds.add(disciple.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

private data class RankedDisciple(
    val disciple: DiscipleAggregate,
    val rank: Int
)

private fun DiscipleAggregate.to(rank: Int) = RankedDisciple(this, rank)

@Composable
private fun TournamentHeader(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "外门大比结果",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloseButton(onClick = onDismiss)
            GameButton(
                text = "准入内门",
                onClick = onConfirm
            )
        }
    }
}
