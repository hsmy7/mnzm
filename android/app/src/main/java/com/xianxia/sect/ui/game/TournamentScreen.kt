package com.xianxia.sect.ui.game

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.TournamentItemReward
import com.xianxia.sect.core.model.TournamentReward
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.getRealmColor
import com.xianxia.sect.ui.components.GameButton

@Composable
fun TournamentDialog(
    gameData: GameData?,
    disciples: List<com.xianxia.sect.core.model.Disciple> = emptyList(),
    equipment: List<com.xianxia.sect.core.model.Equipment> = emptyList(),
    manuals: List<com.xianxia.sect.core.model.Manual> = emptyList(),
    pills: List<com.xianxia.sect.core.model.Pill> = emptyList(),
    materials: List<com.xianxia.sect.core.model.Material> = emptyList(),
    herbs: List<com.xianxia.sect.core.model.Herb> = emptyList(),
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("大比设置", "奖励配置")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.CardBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TournamentHeader(onDismiss = onDismiss)

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = GameColors.CardBackground,
                    contentColor = GameColors.GoldDark
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> TournamentSettingsTab(
                        gameData = gameData,
                        viewModel = viewModel
                    )
                    1 -> TournamentRewardsTab(
                        gameData = gameData,
                        equipment = equipment,
                        manuals = manuals,
                        pills = pills,
                        materials = materials,
                        herbs = herbs,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun TournamentHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "演武堂",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        GameButton(
            text = "关闭",
            onClick = onDismiss
        )
    }
}

@Composable
private fun TournamentSettingsTab(
    gameData: GameData?,
    viewModel: GameViewModel
) {
    val tournamentAutoHold = gameData?.tournamentAutoHold ?: true
    val tournamentRealmEnabled = gameData?.tournamentRealmEnabled ?: emptyMap()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "自动举办大比（每5年一次）",
                fontSize = 12.sp,
                color = Color.Black
            )
            Checkbox(
                checked = tournamentAutoHold,
                onCheckedChange = { viewModel.setTournamentAutoHold(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "启用境界",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        val realms = listOf(
            9 to "练气期",
            8 to "筑基期",
            7 to "金丹期",
            6 to "元婴期",
            5 to "化神期",
            4 to "炼虚期",
            3 to "合体期",
            2 to "大乘期",
            1 to "渡劫期"
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(realms) { (realm, name) ->
                val enabled = tournamentRealmEnabled[realm] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { viewModel.setTournamentRealmEnabled(realm, it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.holdTournament() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = GameColors.GoldDark
            )
        ) {
            Text("立即举办大比", fontSize = 12.sp)
        }
    }
}

@Composable
private fun TournamentRewardsTab(
    gameData: GameData?,
    equipment: List<com.xianxia.sect.core.model.Equipment> = emptyList(),
    manuals: List<com.xianxia.sect.core.model.Manual> = emptyList(),
    pills: List<com.xianxia.sect.core.model.Pill> = emptyList(),
    materials: List<com.xianxia.sect.core.model.Material> = emptyList(),
    herbs: List<com.xianxia.sect.core.model.Herb> = emptyList(),
    viewModel: GameViewModel
) {
    val rewards = gameData?.tournamentRewards ?: emptyMap()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rewards.entries.sortedBy { it.key }.reversed()) { (realm, reward) ->
            TournamentRewardCard(
                realm = realm,
                reward = reward,
                equipment = equipment,
                manuals = manuals,
                pills = pills,
                materials = materials,
                herbs = herbs,
                viewModel = viewModel,
                onUpdate = { updatedReward ->
                    viewModel.updateTournamentReward(realm, updatedReward)
                }
            )
        }
    }
}

@Composable
private fun TournamentRewardCard(
    realm: Int,
    reward: TournamentReward,
    equipment: List<com.xianxia.sect.core.model.Equipment> = emptyList(),
    manuals: List<com.xianxia.sect.core.model.Manual> = emptyList(),
    pills: List<com.xianxia.sect.core.model.Pill> = emptyList(),
    materials: List<com.xianxia.sect.core.model.Material> = emptyList(),
    herbs: List<com.xianxia.sect.core.model.Herb> = emptyList(),
    viewModel: GameViewModel,
    onUpdate: (TournamentReward) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val realmNames = mapOf(
        9 to "练气期",
        8 to "筑基期",
        7 to "金丹期",
        6 to "元婴期",
        5 to "化神期",
        4 to "炼虚期",
        3 to "合体期",
        2 to "大乘期",
        1 to "渡劫期"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = realmNames[realm] ?: "未知",
                fontSize = 12.sp,
                color = Color.Black
            )
            Text(
                text = if (expanded) "▼" else "▶",
                fontSize = 12.sp,
                color = Color.Black
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RewardRankItem(
                    label = "冠军",
                    spiritStones = reward.championSpiritStones,
                    items = reward.championItems,
                    equipment = equipment,
                    manuals = manuals,
                    pills = pills,
                    materials = materials,
                    herbs = herbs,
                    onSpiritStonesChange = { onUpdate(reward.copy(championSpiritStones = it)) },
                    onItemsChange = { onUpdate(reward.copy(championItems = it)) }
                )
                RewardRankItem(
                    label = "亚军",
                    spiritStones = reward.runnerUpSpiritStones,
                    items = reward.runnerUpItems,
                    equipment = equipment,
                    manuals = manuals,
                    pills = pills,
                    materials = materials,
                    herbs = herbs,
                    onSpiritStonesChange = { onUpdate(reward.copy(runnerUpSpiritStones = it)) },
                    onItemsChange = { onUpdate(reward.copy(runnerUpItems = it)) }
                )
                RewardRankItem(
                    label = "四强",
                    spiritStones = reward.semifinalSpiritStones,
                    items = emptyList(),
                    onSpiritStonesChange = { onUpdate(reward.copy(semifinalSpiritStones = it)) },
                    onItemsChange = {}
                )
                RewardRankItem(
                    label = "参与奖",
                    spiritStones = reward.participantSpiritStones,
                    items = emptyList(),
                    onSpiritStonesChange = { onUpdate(reward.copy(participantSpiritStones = it)) },
                    onItemsChange = {}
                )
            }
        }
    }
}

@Composable
private fun RewardRankItem(
    label: String,
    spiritStones: Int,
    items: List<TournamentItemReward>,
    equipment: List<com.xianxia.sect.core.model.Equipment> = emptyList(),
    manuals: List<com.xianxia.sect.core.model.Manual> = emptyList(),
    pills: List<com.xianxia.sect.core.model.Pill> = emptyList(),
    materials: List<com.xianxia.sect.core.model.Material> = emptyList(),
    herbs: List<com.xianxia.sect.core.model.Herb> = emptyList(),
    onSpiritStonesChange: (Int) -> Unit,
    onItemsChange: (List<TournamentItemReward>) -> Unit
) {
    var showSpiritInput by remember { mutableStateOf(false) }
    var showItemDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf(spiritStones.toString()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black,
            modifier = Modifier.width(50.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                .background(Color(0xFFF5F5F5))
                .clickable { showSpiritInput = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "灵石: $spiritStones",
                fontSize = 11.sp,
                color = Color.Black
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                .background(Color(0xFFF5F5F5))
                .clickable { showItemDialog = true }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = if (items.isEmpty()) "道具: 无" else "道具: ${items.size}件",
                fontSize = 11.sp,
                color = Color.Black
            )
        }
    }

    if (showItemDialog) {
        ItemSelectionDialog(
            label = label,
            currentItems = items,
            equipment = equipment,
            manuals = manuals,
            pills = pills,
            materials = materials,
            herbs = herbs,
            onDismiss = { showItemDialog = false },
            onConfirm = { newItems ->
                onItemsChange(newItems)
                showItemDialog = false
            }
        )
    }

    if (showSpiritInput) {
        AlertDialog(
            onDismissRequest = { showSpiritInput = false },
            title = { Text("设置${label}灵石奖励") },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                    label = { Text("灵石数量") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                GameButton(
                    text = "确认",
                    onClick = {
                        onSpiritStonesChange(inputText.toIntOrNull() ?: 0)
                        showSpiritInput = false
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showSpiritInput = false }
                )
            }
        )
    }
}

@Composable
private fun ItemSelectionDialog(
    label: String,
    currentItems: List<TournamentItemReward>,
    equipment: List<com.xianxia.sect.core.model.Equipment>,
    manuals: List<com.xianxia.sect.core.model.Manual>,
    pills: List<com.xianxia.sect.core.model.Pill>,
    materials: List<com.xianxia.sect.core.model.Material>,
    herbs: List<com.xianxia.sect.core.model.Herb>,
    onDismiss: () -> Unit,
    onConfirm: (List<TournamentItemReward>) -> Unit
) {
    var selectedItems by remember { mutableStateOf(currentItems.toMutableList()) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("装备", "功法", "丹药", "材料", "草药")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择${label}道具奖励") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    contentColor = Color.Black
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.height(200.dp)) {
                    when (selectedTab) {
                        0 -> ItemGrid(
                            items = equipment.map { TournamentItemReward(it.id, it.name, "equipment") },
                            selectedItems = selectedItems,
                            onItemToggle = { item ->
                                selectedItems = if (selectedItems.any { it.id == item.id && it.type == item.type }) {
                                    selectedItems.filterNot { it.id == item.id && it.type == item.type }.toMutableList()
                                } else {
                                    (selectedItems + item).toMutableList()
                                }
                            }
                        )
                        1 -> ItemGrid(
                            items = manuals.map { TournamentItemReward(it.id, it.name, "manual") },
                            selectedItems = selectedItems,
                            onItemToggle = { item ->
                                selectedItems = if (selectedItems.any { it.id == item.id && it.type == item.type }) {
                                    selectedItems.filterNot { it.id == item.id && it.type == item.type }.toMutableList()
                                } else {
                                    (selectedItems + item).toMutableList()
                                }
                            }
                        )
                        2 -> ItemGrid(
                            items = pills.map { TournamentItemReward(it.id, it.name, "pill") },
                            selectedItems = selectedItems,
                            onItemToggle = { item ->
                                selectedItems = if (selectedItems.any { it.id == item.id && it.type == item.type }) {
                                    selectedItems.filterNot { it.id == item.id && it.type == item.type }.toMutableList()
                                } else {
                                    (selectedItems + item).toMutableList()
                                }
                            }
                        )
                        3 -> ItemGrid(
                            items = materials.map { TournamentItemReward(it.id, it.name, "material") },
                            selectedItems = selectedItems,
                            onItemToggle = { item ->
                                selectedItems = if (selectedItems.any { it.id == item.id && it.type == item.type }) {
                                    selectedItems.filterNot { it.id == item.id && it.type == item.type }.toMutableList()
                                } else {
                                    (selectedItems + item).toMutableList()
                                }
                            }
                        )
                        4 -> ItemGrid(
                            items = herbs.map { TournamentItemReward(it.id, it.name, "herb") },
                            selectedItems = selectedItems,
                            onItemToggle = { item ->
                                selectedItems = if (selectedItems.any { it.id == item.id && it.type == item.type }) {
                                    selectedItems.filterNot { it.id == item.id && it.type == item.type }.toMutableList()
                                } else {
                                    (selectedItems + item).toMutableList()
                                }
                            }
                        )
                    }
                }

                if (selectedItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已选: ${selectedItems.joinToString(", ") { it.name }}",
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        confirmButton = {
            GameButton(
                text = "确认",
                onClick = { onConfirm(selectedItems) }
            )
        },
        dismissButton = {
            GameButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun ItemGrid(
    items: List<TournamentItemReward>,
    selectedItems: List<TournamentItemReward>,
    onItemToggle: (TournamentItemReward) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            val isSelected = selectedItems.any { it.id == item.id && it.type == item.type }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isSelected) GameColors.GoldDark else Color(0xFFE0E0E0),
                        RoundedCornerShape(4.dp)
                    )
                    .background(if (isSelected) Color(0xFFFFF8E1) else Color.White)
                    .clickable { onItemToggle(item) }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    fontSize = 11.sp,
                    color = Color.Black
                )
                if (isSelected) {
                    Text(
                        text = "✓",
                        fontSize = 12.sp,
                        color = GameColors.GoldDark
                    )
                }
            }
        }
    }
}
