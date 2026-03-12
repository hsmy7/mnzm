package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
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
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun SalaryConfigDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val monthlySalary = gameData?.monthlySalary ?: emptyMap()
    val monthlySalaryEnabled = gameData?.monthlySalaryEnabled ?: emptyMap()

    val realms = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "练气"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SalaryConfigHeader(onDismiss = onDismiss)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(realms) { (realm, name) ->
                        val salary = monthlySalary[realm] ?: 0
                        val enabled = monthlySalaryEnabled[realm] ?: true

                        SalaryRealmCard(
                            realmName = name,
                            salary = salary,
                            enabled = enabled,
                            onEnabledChange = { viewModel.setMonthlySalaryEnabled(realm, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SalaryConfigHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "月俸配置",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        TextButton(onClick = onDismiss) {
            Text("关闭", color = Color.Black, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SalaryRealmCard(
    realmName: String,
    salary: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = realmName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$salary 灵石",
                    fontSize = 12.sp,
                    color = Color.Black
                )

                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }
}
