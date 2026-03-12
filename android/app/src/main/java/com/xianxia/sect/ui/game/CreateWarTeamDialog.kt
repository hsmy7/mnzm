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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus

@Composable
fun CreateWarTeamDialog(
    disciples: List<Disciple>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var teamName by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<String>()) }
    var showError by remember { mutableStateOf(false) }
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    // 只显示空闲且有境界的弟子
    val idleDisciples = remember(disciples) {
        disciples.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE && 
            it.realmLayer > 0 && 
            it.age >= 5 
        }
    }

    val realmCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.realm }.eachCount()
    }

    val sortedDisciples = remember(idleDisciples) {
        idleDisciples.sortedWith(
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

    CommonDialog(
        title = "创建战堂队伍",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
        ) {
            OutlinedTextField(
                value = teamName,
                onValueChange = { teamName = it },
                label = { Text("队伍名称", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    realmFilters.take(5).forEach { (realm, name) ->
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
                    realmFilters.drop(5).forEach { (realm, name) ->
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

            Text("选择队员:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredDisciples) { disciple ->
                    val isSelected = selectedMembers.contains(disciple.id)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { checked ->
                                selectedMembers = if (checked) {
                                    selectedMembers + disciple.id
                                } else {
                                    selectedMembers - disciple.id
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color.Transparent,
                                uncheckedColor = Color(0xFF999999),
                                checkmarkColor = Color.Black
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${disciple.name} (${disciple.realmName})", fontSize = 12.sp, color = Color.Black)
                            val spiritRootColor = try {
                                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                            } catch (e: Exception) {
                                Color(0xFF666666)
                            }
                            Text(
                                "灵根: ${disciple.spiritRootName}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = spiritRootColor
                            )
                        }
                    }
                }
            }

            if (showError) {
                Text(
                    "请输入队伍名称并选择至少一名队员",
                    color = Color.Red,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .clickable {
                            if (teamName.isBlank() || selectedMembers.isEmpty()) {
                                showError = true
                            } else {
                                onCreate(teamName, selectedMembers.toList())
                                onDismiss()
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "创建",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
            Column(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}
