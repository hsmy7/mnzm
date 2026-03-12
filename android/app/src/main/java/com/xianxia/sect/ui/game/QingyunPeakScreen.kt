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

@Composable
fun QingyunPeakDialog(
    disciples: List<Disciple>,
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showInnerElderSelection by remember { mutableStateOf(false) }
    var showPreachingElderSelection by remember { mutableStateOf(false) }
    var showPreachingMasterSelection by remember { mutableStateOf<Int?>(null) }

    val innerElder = viewModel.getInnerElder()
    val preachingElder = viewModel.getQingyunPreachingElder()
    val preachingMasters = viewModel.getQingyunPreachingMasters()
    val innerDisciples = viewModel.getInnerDisciples()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "青云峰",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                Text(
                    text = "管理内门弟子与传道修行",
                    fontSize = 10.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                ElderSection(
                    innerElder = innerElder,
                    preachingElder = preachingElder,
                    onInnerElderClick = { showInnerElderSelection = true },
                    onInnerElderRemove = { viewModel.removeElder("innerElder") },
                    onPreachingElderClick = { showPreachingElderSelection = true },
                    onPreachingElderRemove = { viewModel.removeElder("qingyunPreachingElder") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                PreachingMastersSection(
                    preachingMasters = preachingMasters,
                    onPreachingMasterClick = { index -> showPreachingMasterSelection = index },
                    onPreachingMasterRemove = { index -> viewModel.removeDirectDisciple("qingyunPreachingMasters", index) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                InnerDisciplesSection(
                    innerDisciples = innerDisciples
                )
            }
        },
        confirmButton = {}
    )

    if (showInnerElderSelection) {
        val availableDisciples = viewModel.getAvailableDisciplesForInnerElder()
        QingyunDiscipleSelectionDialog(
            title = "选择内门长老",
            disciples = availableDisciples,
            currentDiscipleId = innerElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                viewModel.assignElder("innerElder", disciple.id)
                showInnerElderSelection = false
            },
            onDismiss = { showInnerElderSelection = false }
        )
    }

    if (showPreachingElderSelection) {
        val availableDisciples = viewModel.getAvailableDisciplesForQingyunPreachingElder()
        QingyunDiscipleSelectionDialog(
            title = "选择传道长老",
            disciples = availableDisciples,
            currentDiscipleId = preachingElder?.id,
            requirementText = "需要元婴及以上境界",
            onSelect = { disciple ->
                viewModel.assignElder("qingyunPreachingElder", disciple.id)
                showPreachingElderSelection = false
            },
            onDismiss = { showPreachingElderSelection = false }
        )
    }

    showPreachingMasterSelection?.let { slotIndex ->
        val availableDisciples = viewModel.getAvailableDisciplesForQingyunPreachingMaster()
        val currentMaster = preachingMasters.find { it.index == slotIndex }
        QingyunDiscipleSelectionDialog(
            title = "选择传道师",
            disciples = availableDisciples,
            currentDiscipleId = currentMaster?.discipleId,
            requirementText = "需要金丹及以上境界",
            onSelect = { disciple ->
                viewModel.assignDirectDisciple("qingyunPreachingMasters", slotIndex, disciple.id)
                showPreachingMasterSelection = null
            },
            onDismiss = { showPreachingMasterSelection = null }
        )
    }
}

@Composable
private fun ElderSection(
    innerElder: Disciple?,
    preachingElder: Disciple?,
    onInnerElderClick: () -> Unit,
    onInnerElderRemove: () -> Unit,
    onPreachingElderClick: () -> Unit,
    onPreachingElderRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QingyunElderSlotItem(
                title = "内门长老",
                elder = innerElder,
                onClick = onInnerElderClick,
                onRemove = onInnerElderRemove
            )

            QingyunElderSlotItem(
                title = "传道长老",
                elder = preachingElder,
                onClick = onPreachingElderClick,
                onRemove = onPreachingElderRemove
            )
        }
    }
}

@Composable
private fun QingyunElderSlotItem(
    title: String,
    elder: Disciple?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))

        val borderColor = if (elder != null) {
            try {
                Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
            } catch (e: Exception) {
                Color(0xFFE0E0E0)
            }
        } else {
            Color(0xFFE0E0E0)
        }

        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (elder != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = elder.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = elder.realmName,
                        fontSize = 8.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        if (elder != null) {
            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun PreachingMastersSection(
    preachingMasters: List<DirectDiscipleSlot>,
    onPreachingMasterClick: (Int) -> Unit,
    onPreachingMasterRemove: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "传道师",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (0..3).forEach { index ->
                val master = preachingMasters.find { it.index == index }
                QingyunPreachingMasterSlotItem(
                    master = master,
                    onClick = { onPreachingMasterClick(index) },
                    onRemove = { onPreachingMasterRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun QingyunPreachingMasterSlotItem(
    master: DirectDiscipleSlot?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "传道师",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF666666)
        )
        Spacer(modifier = Modifier.height(2.dp))

        val borderColor = if (master != null && master.isActive) {
            try {
                Color(android.graphics.Color.parseColor(master.discipleSpiritRootColor))
            } catch (e: Exception) {
                Color(0xFF9C27B0)
            }
        } else {
            Color(0xFFE0E0E0)
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (master != null && master.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = master.discipleName,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = master.discipleRealm,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        if (master != null && master.isActive) {
            Spacer(modifier = Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(3.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "卸任",
                    fontSize = 8.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun InnerDisciplesSection(
    innerDisciples: List<Disciple>
) {
    val sortedDisciples = remember(innerDisciples) {
        innerDisciples.sortedBy { it.spiritRoot.types.size }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F5F5))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "内门弟子",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "共${innerDisciples.size}人",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (sortedDisciples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无内门弟子",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sortedDisciples) { disciple ->
                    QingyunInnerDiscipleItem(disciple = disciple)
                }
            }
        }
    }
}

@Composable
private fun QingyunInnerDiscipleItem(
    disciple: Disciple
) {
    val borderColor = try {
        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
    } catch (e: Exception) {
        Color(0xFFE0E0E0)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = disciple.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 10.sp,
                    color = Color(0xFF666666)
                )
            }
        }
    }
}

@Composable
private fun QingyunDiscipleSelectionDialog(
    title: String,
    disciples: List<Disciple>,
    currentDiscipleId: String?,
    requirementText: String,
    onSelect: (Disciple) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹"
    )

    val realmCounts = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(disciples) {
        disciples.filter { it.realmLayer > 0 }.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(Color(0xFFF5F5F5)),
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
            if (disciples.isEmpty()) {
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
                        text = requirementText,
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
                        text = requirementText,
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.take(4).forEach { (realm, name) ->
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            realmFilters.drop(4).forEach { (realm, name) ->
                                val isSelected = selectedRealmFilter == realm
                                val count = realmCounts[realm] ?: 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                        .clickable { selectedRealmFilter = if (isSelected) null else realm }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$name $count",
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDisciples) { disciple ->
                            val isCurrent = disciple.id == currentDiscipleId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isCurrent) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                                    .clickable { onSelect(disciple) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val spiritRootColor = try {
                                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Text(
                                            text = disciple.spiritRootName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = spiritRootColor
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = "当前",
                                                fontSize = 12.sp,
                                                color = Color(0xFF4CAF50)
                                            )
                                        } else {
                                            Text(
                                                text = disciple.realmName,
                                                fontSize = 12.sp,
                                                color = Color(0xFF666666)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
