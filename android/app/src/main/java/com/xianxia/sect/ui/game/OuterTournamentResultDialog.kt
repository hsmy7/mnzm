package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.model.CompetitionRankResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.GameButton

@Composable
fun OuterTournamentResultDialog(
    competitionResults: List<CompetitionRankResult>,
    allDisciples: List<DiscipleAggregate>,
    gameData: GameData,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val selectedIds = remember { mutableStateListOf<String>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TournamentHeader(
                    onDismiss = onDismiss,
                    onConfirm = {
                        worldMapViewModel.promoteSelectedDisciplesToInner(selectedIds.toSet())
                    }
                )

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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(topDisciples, key = { (d, _) -> d.id }) { (disciple, rank) ->
                            val isSelected by remember { derivedStateOf { disciple.id in selectedIds } }
                            TournamentDiscipleCard(
                                disciple = disciple,
                                rank = rank,
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
            .background(GameColors.PageBackground)
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
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
            GameButton(
                text = "准入内门",
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun TournamentDiscipleCard(
    disciple: DiscipleAggregate,
    rank: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) {
        Brush.linearGradient(colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500)))
    } else {
        Brush.linearGradient(colors = listOf(Color(0xFFE0E0E0), Color(0xFFBDBDBD)))
    }

    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(
                width = borderWidth,
                brush = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(getRankColor(rank)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.TextPrimary
                    )
                    val spiritRootColor = remember(disciple.spiritRoot.countColor, disciple.spiritRootType) {
                        try {
                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                        } catch (e: IllegalArgumentException) {
                            getSpiritRootColor(disciple.spiritRootType)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(spiritRootColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = disciple.spiritRootName,
                            fontSize = 10.sp,
                            color = spiritRootColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 11.sp,
                        color = GameColors.TextSecondary
                    )
                    DiscipleAttrText("悟性", disciple.comprehension, color = GameColors.TextSecondary)
                    DiscipleAttrText("忠诚", disciple.loyalty, color = GameColors.TextSecondary)
                    DiscipleAttrText("道德", disciple.morality, color = GameColors.TextSecondary)
                }
            }

            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700)
                )
            }
        }
    }
}

private fun getRankColor(rank: Int): Color = when (rank) {
    1 -> Color(0xFFFFD700)
    2 -> Color(0xFFC0C0C0)
    3 -> Color(0xFFCD7F32)
    else -> Color(0xFF607D8B)
}
