package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun TianshuHallDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
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
                                    productionViewModel.removeViceSectMaster()
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
        var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
        var spiritRootExpanded by remember { mutableStateOf(false) }
        var attributeExpanded by remember { mutableStateOf(false) }

        val filteredDisciplesBase = remember(disciples, elderSlots) {
            disciples.filter {
                it.isAlive &&
                it.id != viceSectMasterId &&
                it.status == DiscipleStatus.IDLE &&
                it.realm <= 4 &&
                it.discipleType == "inner" &&
                it.age >= 5 &&
                it.realmLayer > 0
            }
        }

        val realmFilters = listOf(
            0 to "仙人",
            1 to "渡劫",
            2 to "大乘",
            3 to "合体",
            4 to "炼虚",
            5 to "化神",
            6 to "元婴",
            7 to "金丹",
            8 to "筑基"
        )

        val realmCounts = remember(filteredDisciplesBase) {
            filteredDisciplesBase.filter { it.realmLayer > 0 }.groupingBy { it.realm }.eachCount()
        }

        val spiritRootCounts = remember(filteredDisciplesBase) {
            filteredDisciplesBase.filter { it.realmLayer > 0 }.groupingBy { it.getSpiritRootCount() }.eachCount()
        }

        val sortedDisciples = remember(filteredDisciplesBase) {
            filteredDisciplesBase.filter { it.realmLayer > 0 }.sortedByFollowAndRealm()
        }

        val filteredDisciples = remember(sortedDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
            sortedDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
        }

        AlertDialog(
            onDismissRequest = { showViceSectMasterSelectDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择副宗主",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { showViceSectMasterSelectDialog = false }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                if (filteredDisciplesBase.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "暂无符合条件的弟子",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "需要炼虚及以上境界",
                            fontSize = 10.sp,
                            color = Color(0xFF666666)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp)
                    ) {
                        Text(
                            text = "需要炼虚及以上境界",
                            fontSize = 10.sp,
                            color = Color(0xFFE74C3C),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SpiritRootAttributeFilterBar(
                            selectedSpiritRootFilter = selectedSpiritRootFilter,
                            selectedAttributeSort = selectedAttributeSort,
                            spiritRootExpanded = spiritRootExpanded,
                            attributeExpanded = attributeExpanded,
                            spiritRootCounts = spiritRootCounts,
                            onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                            onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                            onAttributeSortSelected = { selectedAttributeSort = it },
                            onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                            onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                            isCompact = true
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.chunked(4).forEach { chunk ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    chunk.forEach { (realm, name) ->
                                        val isSelected = realm in selectedRealmFilter
                                        val count = realmCounts[realm] ?: 0
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                                .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                                .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                                .padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$name $count",
                                                fontSize = 8.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) GameColors.GoldDark else Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredDisciples, key = { it.id }) { disciple ->
                                HorizontalDiscipleCard(
                                    disciple = disciple,
                                    extraAttributes = listOf("智力" to disciple.intelligence),
                                    onClick = {
                                        productionViewModel.setViceSectMaster(disciple.id)
                                        showViceSectMasterSelectDialog = false
                                    }
                                )
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
            productionViewModel = productionViewModel,
            onDismiss = { showSectPoliciesDialog = false }
        )
    }
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
    productionViewModel: ProductionViewModel,
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
                val viceBonus = productionViewModel.getViceSectMasterIntelligenceBonus()
                val viceBonusText = if (viceBonus > 0) " (副宗主加成+${(viceBonus * 100).toInt()}%)" else ""

                PolicyItem(
                    title = "灵矿增产",
                    effect = "灵石产出+20%$viceBonusText",
                    cost = "采矿弟子忠诚-1/月",
                    checked = sectPolicies?.spiritMineBoost ?: false,
                    onCheckedChange = { productionViewModel.toggleSpiritMineBoost() }
                )

                PolicyItem(
                    title = "丹道激励",
                    effect = "炼丹成功率+10%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.alchemyIncentive ?: false,
                    onCheckedChange = { productionViewModel.toggleAlchemyIncentive() }
                )

                PolicyItem(
                    title = "锻造激励",
                    effect = "锻造成功率+10%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.forgeIncentive ?: false,
                    onCheckedChange = { productionViewModel.toggleForgeIncentive() }
                )

                PolicyItem(
                    title = "灵药培育",
                    effect = "灵药生长速度+20%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.herbCultivation ?: false,
                    onCheckedChange = { productionViewModel.toggleHerbCultivation() }
                )

                PolicyItem(
                    title = "修行津贴",
                    effect = "化神境以下弟子修炼速度+15%$viceBonusText",
                    cost = "每月消耗4000灵石",
                    checked = sectPolicies?.cultivationSubsidy ?: false,
                    onCheckedChange = { productionViewModel.toggleCultivationSubsidy() }
                )

                PolicyItem(
                    title = "功法研习",
                    effect = "功法修炼速度+20%$viceBonusText",
                    cost = "每月消耗4000灵石",
                    checked = sectPolicies?.manualResearch ?: false,
                    onCheckedChange = { productionViewModel.toggleManualResearch() }
                )

                PolicyItem(
                    title = "增强治安",
                    effect = "执法堂抓捕率+20%$viceBonusText",
                    cost = "每月消耗3000灵石",
                    checked = sectPolicies?.enhancedSecurity ?: false,
                    onCheckedChange = { productionViewModel.toggleEnhancedSecurity() }
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
    onCheckedChange: (Boolean) -> Boolean
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
            onCheckedChange = { newChecked ->
                if (!onCheckedChange(newChecked)) {
                    return@Checkbox
                }
            }
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = GameColors.Border)
}
