package com.xianxia.sect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.xianxia.sect.data.model.SaveSlot
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SaveSelectScreen(
    saveSlots: List<SaveSlot>,
    onLoadSlot: (Int) -> Unit,
    onNewGame: (Int, String) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    onLogout: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf<Int?>(null) }
    var showSectNameDialog by remember { mutableStateOf<Int?>(null) }
    var sectNameInput by remember { mutableStateOf("") }
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(
            text = "选择存档",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            saveSlots.forEach { slot ->
                SaveSlotCard(
                    slot = slot,
                    dateFormat = dateFormat,
                    onLoad = { onLoadSlot(slot.slot) },
                    onNewGame = { showSectNameDialog = slot.slot; sectNameInput = "" },
                    onDelete = { showDeleteConfirm = slot.slot }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                .clickable { onLogout() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "退出登录",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF666666)
            )
        }
    }

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除存档 ${showDeleteConfirm} 吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = null
                    }
                ) {
                    Text("取消")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm?.let { onDeleteSlot(it) }
                        showDeleteConfirm = null
                    }
                ) {
                    Text("删除", color = Color.Red)
                }
            }
        )
    }
    
    if (showSectNameDialog != null) {
        AlertDialog(
            onDismissRequest = { showSectNameDialog = null },
            title = { Text("创建宗门") },
            text = {
                Column {
                    Text("请输入宗门名称：", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sectNameInput,
                        onValueChange = { sectNameInput = it },
                        placeholder = { Text("青云宗") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = sectNameInput.trim().ifEmpty { "青云宗" }
                        showSectNameDialog?.let { onNewGame(it, name) }
                        showSectNameDialog = null
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSectNameDialog = null }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SaveSlotCard(
    slot: SaveSlot,
    dateFormat: SimpleDateFormat,
    onLoad: () -> Unit,
    onNewGame: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (slot.isEmpty) Color(0xFFDDDDDD) else Color(0xFF4A90E2)
    val bgColor = if (slot.isEmpty) Color(0xFFF5F5F5) else Color(0xFFF0F7FF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { if (!slot.isEmpty) onLoad() else onNewGame() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (slot.isEmpty) Color(0xFFCCCCCC) else Color(0xFF4A90E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = slot.slot.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (!slot.isEmpty) {
                    Column {
                        Text(
                            text = slot.sectName.ifEmpty { "存档 ${slot.slot}" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "第${slot.gameYear}年 ${slot.gameMonth}月",
                            fontSize = 13.sp,
                            color = Color(0xFF666666)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "弟子: ${slot.discipleCount}  灵石: ${slot.spiritStones}",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                        if (slot.timestamp > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = dateFormat.format(Date(slot.timestamp)),
                                fontSize = 11.sp,
                                color = Color(0xFF999999)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "空槽位 - 点击创建新游戏",
                        fontSize = 16.sp,
                        color = Color(0xFF999999)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!slot.isEmpty) {
                    // 删除按钮
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFEBEE))
                            .border(1.dp, Color(0xFFEF5350), RoundedCornerShape(4.dp))
                            .clickable { onDelete() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "删除",
                            fontSize = 12.sp,
                            color = Color(0xFFEF5350),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "读取",
                        fontSize = 12.sp,
                        color = Color(0xFF27AE60),
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "创建",
                        fontSize = 12.sp,
                        color = Color(0xFF27AE60),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
