package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun TianshuHallDialog(
    gameData: GameData?,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val elderSlots = gameData?.elderSlots
    val viceSectMasterId = elderSlots?.viceSectMaster
    val viceSectMaster = disciples.find { it.id == viceSectMasterId }

    var showViceSectMasterSelectDialog by remember { mutableStateOf(false) }
    var showSectAffairsDialog by remember { mutableStateOf(false) }
    var showSectPoliciesDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "天枢殿",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "副宗主",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                            .clickable { showViceSectMasterSelectDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (viceSectMaster != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = viceSectMaster.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    maxLines = 1
                                )
                                Text(
                                    text = viceSectMaster.realmName,
                                    fontSize = 9.sp,
                                    color = Color(0xFF666666),
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text(
                                text = "+",
                                fontSize = 24.sp,
                                color = Color(0xFF999999)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                            .clickable {
                                if (viceSectMasterId != null) {
                                    viewModel.removeViceSectMaster()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "卸任",
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                    }
                }

                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GameButton(
                        text = "宗门事务",
                        onClick = { showSectAffairsDialog = true },
                        modifier = Modifier.weight(1f)
                    )

                    GameButton(
                        text = "宗门政策",
                        onClick = { showSectPoliciesDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {}
    )

    if (showViceSectMasterSelectDialog) {
        val eligibleDisciples = disciples.filter {
            it.isAlive &&
            it.id != viceSectMasterId &&
            it.status == DiscipleStatus.IDLE &&
            it.realm <= 4 &&
            it.discipleType == "inner" &&
            it.age >= 5 &&
            it.realmLayer > 0 &&
            isDiscipleAnElder(it.id, elderSlots ?: ElderSlots())
        }

        AlertDialog(
            onDismissRequest = { showViceSectMasterSelectDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "选择副宗主",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(eligibleDisciples) { disciple ->
                        val spiritRootColor = try {
                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                        } catch (e: Exception) {
                            Color(0xFF666666)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GameColors.PageBackground)
                                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.setViceSectMaster(disciple.id)
                                    showViceSectMasterSelectDialog = false
                                }
                                .padding(12.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = disciple.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "智力:${disciple.intelligence}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GameButton(
                    text = "取消",
                    onClick = { showViceSectMasterSelectDialog = false }
                )
            }
        )
    }

    if (showSectAffairsDialog) {
        SectAffairsPlaceholderDialog(onDismiss = { showSectAffairsDialog = false })
    }

    if (showSectPoliciesDialog) {
        SectPoliciesDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showSectPoliciesDialog = false }
        )
    }
}

private fun isDiscipleAnElder(discipleId: String, elderSlots: ElderSlots): Boolean {
    val allElderIds = listOf(
        elderSlots.herbGardenElder,
        elderSlots.alchemyElder,
        elderSlots.forgeElder,
        elderSlots.outerElder,
        elderSlots.preachingElder,
        elderSlots.lawEnforcementElder,
        elderSlots.innerElder,
        elderSlots.qingyunPreachingElder
    ).filterNotNull()

    return allElderIds.contains(discipleId)
}

@Composable
private fun SectAffairsPlaceholderDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "宗门事务",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "宗门日常事务管理功能开发中...",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }
        },
        confirmButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun SectPoliciesDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "宗门政策",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sectPolicies = gameData?.sectPolicies
                val viceBonus = viewModel.getViceSectMasterIntelligenceBonus()
                val viceBonusText = if (viceBonus > 0) " (副宗主加成+${(viceBonus * 100).toInt()}%)" else ""

                PolicyItem(
                    title = "灵矿增产",
                    effect = "灵石产出+20%$viceBonusText",
                    cost = "采矿弟子忠诚-1/月",
                    checked = sectPolicies?.spiritMineBoost ?: false,
                    onCheckedChange = { viewModel.toggleSpiritMineBoost() }
                )

                PolicyItem(
                    title = "丹道激励",
                    effect = "炼丹成功率+10%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.alchemyIncentive ?: false,
                    onCheckedChange = { viewModel.toggleAlchemyIncentive() }
                )

                PolicyItem(
                    title = "锻造激励",
                    effect = "锻造成功率+10%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.forgeIncentive ?: false,
                    onCheckedChange = { viewModel.toggleForgeIncentive() }
                )

                PolicyItem(
                    title = "灵药培育",
                    effect = "灵药生长速度+20%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.herbCultivation ?: false,
                    onCheckedChange = { viewModel.toggleHerbCultivation() }
                )

                PolicyItem(
                    title = "修行津贴",
                    effect = "化神境以下弟子修炼速度+15%$viceBonusText",
                    cost = "每月消耗4000灵石",
                    checked = sectPolicies?.cultivationSubsidy ?: false,
                    onCheckedChange = { viewModel.toggleCultivationSubsidy() }
                )

                PolicyItem(
                    title = "功法研习",
                    effect = "功法修炼速度+20%$viceBonusText",
                    cost = "每月消耗4000灵石",
                    checked = sectPolicies?.manualResearch ?: false,
                    onCheckedChange = { viewModel.toggleManualResearch() }
                )

                PolicyItem(
                    title = "增强治安",
                    effect = "执法堂抓捕率+20%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.enhancedSecurity ?: false,
                    onCheckedChange = { viewModel.toggleEnhancedSecurity() }
                )
            }
        },
        confirmButton = {
            GameButton(
                text = "关闭",
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun PolicyItem(
    title: String,
    effect: String,
    cost: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = effect,
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
            Text(
                text = cost,
                fontSize = 10.sp,
                color = Color(0xFF999999)
            )
        }

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = GameColors.Border)
}
