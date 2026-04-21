package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton

@Suppress("UNUSED_PARAMETER")
@Composable
fun InventoryDialog(
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    viewModel: com.xianxia.sect.ui.game.GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("装备", "功法", "丹药", "材料", "灵草")

    val filteredEquipment = remember(equipment) { equipment }
    val filteredManuals = remember(manuals) { manuals }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("背包") },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    when (selectedTab) {
                        0 -> items(filteredEquipment) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("稀有度: ${item.rarity}") }
                            )
                        }
                        1 -> items(filteredManuals) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("稀有度: ${item.rarity}") }
                            )
                        }
                        2 -> items(pills) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("稀有度: ${item.rarity} x${item.quantity}") }
                            )
                        }
                        3 -> items(materials) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("稀有度: ${item.rarity} x${item.quantity}") }
                            )
                        }
                        4 -> items(herbs) { item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text("稀有度: ${item.rarity} x${item.quantity}") }
                            )
                        }
                    }
                }
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
