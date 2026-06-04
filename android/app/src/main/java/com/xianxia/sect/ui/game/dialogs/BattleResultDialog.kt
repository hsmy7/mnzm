package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard

@Composable
internal fun BattleResultDialog(
    resultData: BattleResultUIData,
    battleLog: BattleLog?,
    onConfirm: () -> Unit,
    onViewDetail: (BattleLog) -> Unit,
    onDismiss: () -> Unit
) {
    val resultColor = if (resultData.victory) Color(0xFF4CAF50) else Color(0xFFF44336)
    val title = if (resultData.victory) "战斗胜利" else "战斗失败"

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        titleColor = resultColor,
        titleFontSize = 22.sp,
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = false,
        dismissOnClickOutside = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp)
            ) {
                // 出战弟子
                item {
                    Text(
                        text = "出战弟子",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val teamRows = resultData.teamMembers.chunked(4)
                item {
                    Column {
                        teamRows.forEach { rowMembers ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp,
                                    Alignment.CenterHorizontally
                                )
                            ) {
                                rowMembers.forEach { member ->
                                    BattleParticipantSlot(
                                        name = member.name,
                                        realmName = member.realmName,
                                        hp = member.hp,
                                        maxHp = member.maxHp,
                                        isAlive = member.isAlive,
                                        portraitRes = member.portraitRes
                                    )
                                }
                                repeat(4 - rowMembers.size) {
                                    Spacer(
                                        Modifier.width(52.dp).height(88.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 战利品
                if (resultData.rewards.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "战利品",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(resultData.rewards, key = { it.itemId }) { reward ->
                                UnifiedItemCard(
                                    data = ItemCardData(
                                        id = reward.itemId,
                                        name = reward.name,
                                        rarity = reward.rarity,
                                        quantity = reward.quantity,
                                        type = reward.type,
                                        isPill = reward.type == "pill",
                                        isManual = reward.type == "manual",
                                        isMaterial = reward.type == "material",
                                        isSpiritStone = reward.type == "spiritStones"
                                    )
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "无战利品",
                            fontSize = 11.sp,
                            color = Color.Black
                        )
                    }
                }
            }

            // 底部按钮
            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = "战斗详情",
                    onClick = {
                        battleLog?.let { onViewDetail(it) }
                    }
                )
                Spacer(modifier = Modifier.width(16.dp))
                GameButton(
                    text = "确定",
                    onClick = onConfirm
                )
            }
        }
    }
}
