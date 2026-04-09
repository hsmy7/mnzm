@file:Suppress("DEPRECATION")
package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getSpiritRootColor
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.getTalentRarityColor

@Composable
fun RecruitDialog(
    recruitList: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                RecruitHeader(
                    gameData = gameData,
                    onDismiss = onDismiss
                )

                if (recruitList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无可招募弟子\n请等待每年一月刷新",
                            fontSize = 12.sp,
                            color = GameColors.TextSecondary
                        )
                    }
                } else {
                    val sortedRecruitList = remember(recruitList) {
                        recruitList.sortedBy { it.spiritRoot.types.size }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sortedRecruitList, key = { it.id }) { disciple ->
                            val onAccept = remember { { viewModel.recruitDisciple(disciple) } }
                            val onReject = remember { { viewModel.rejectDiscipleFromList(disciple.id) } }
                            RecruitDiscipleCard(
                                disciple = disciple,
                                onAccept = onAccept,
                                onReject = onReject
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecruitHeader(
    gameData: GameData?,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "弟子招募",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "当前灵石: ${gameData?.spiritStones ?: 0}",
                fontSize = 12.sp,
                color = GameColors.TextSecondary
            )
        }

        GameButton(
            text = "关闭",
            onClick = onDismiss
        )
    }
}

@Composable
private fun RecruitDiscipleCard(
    disciple: DiscipleAggregate,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .discipleCardBorder()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 12.sp,
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
                        fontSize = 11.sp,
                        color = spiritRootColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DiscipleAttrText("悟性", disciple.comprehension)
                    DiscipleAttrText("忠诚", disciple.loyalty)
                    DiscipleAttrText("道德", disciple.morality)
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GameButton(
                        text = "拒绝",
                        onClick = onReject
                    )
                    GameButton(
                        text = "同意",
                        onClick = onAccept
                    )
                }
            }

            val talents = remember(disciple.talentIds) {
                TalentDatabase.getTalentsByIds(disciple.talentIds)
            }
            if (talents.isNotEmpty()) {
                var selectedTalent by remember { mutableStateOf<Talent?>(null) }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    talents.forEach { talent ->
                        val rarityColor = getTalentRarityColor(talent.rarity)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(rarityColor.copy(alpha = 0.15f))
                                .clickable { selectedTalent = talent }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = talent.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = rarityColor,
                                maxLines = 1
                            )
                        }
                    }
                }
                selectedTalent?.let { talent ->
                    TalentDetailDialog(
                        talent = talent,
                        onDismiss = { selectedTalent = null }
                    )
                }
            }
        }
    }
}
