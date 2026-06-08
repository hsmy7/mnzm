package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
fun EquipmentSection(
    weapon: EquipmentInstance?,
    armor: EquipmentInstance?,
    boots: EquipmentInstance?,
    accessory: EquipmentInstance?,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (EquipmentInstance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "装备",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EquipmentSlot("武器", weapon, Modifier.weight(1f), onSlotClick, onEquipmentClick, "weapon")
            EquipmentSlot("护甲", armor, Modifier.weight(1f), onSlotClick, onEquipmentClick, "armor")
            EquipmentSlot("靴子", boots, Modifier.weight(1f), onSlotClick, onEquipmentClick, "boots")
            EquipmentSlot("饰品", accessory, Modifier.weight(1f), onSlotClick, onEquipmentClick, "accessory")
        }
    }
}

@Composable
fun EquipmentSlot(
    slotName: String,
    equipment: EquipmentInstance?,
    modifier: Modifier = Modifier,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (EquipmentInstance) -> Unit,
    slotType: String
) {
    val dismissDropdown = LocalDismissDropdown.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = slotName,
            fontSize = 10.sp,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (equipment != null) {
            UnifiedItemCard(
                data = ItemCardData(
                    name = equipment.name,
                    rarity = equipment.rarity
                ),
                showQuantity = false,
                onClick = { onEquipmentClick(equipment) }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
                    .clickable { dismissDropdown(); onSlotClick(slotType) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
fun EquipmentSelectionDialog(
    slotType: String,
    allEquipment: List<EquipmentInstance>,
    equipmentStacks: List<EquipmentStack>,
    currentEquipmentId: String?,
    currentDiscipleId: String,
    discipleRealm: Int,
    selectedEquipmentId: String?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val slotTypeText = when (slotType) {
        "weapon" -> "武器"
        "armor" -> "护甲"
        "boots" -> "靴子"
        "accessory" -> "饰品"
        else -> "装备"
    }

    val availableItems = remember(allEquipment, equipmentStacks, slotType, currentEquipmentId, currentDiscipleId, discipleRealm) {
        val slotEnum = try {
            EquipmentSlot.valueOf(slotType.uppercase(Locale.getDefault()))
        } catch (_: Exception) {
            EquipmentSlot.WEAPON
        }

        val stacks = equipmentStacks.filter { stack ->
            stack.slot == slotEnum &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
        }.map { stack -> EquipmentSelectionItem(stack.id, stack.name, stack.rarity, stack.quantity, stack.isLocked, true) }

        val instances = allEquipment.filter {
            it.slot == slotEnum &&
            it.id != currentEquipmentId &&
            (it.ownerId == null || it.ownerId == currentDiscipleId) &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, it.minRealm)
        }.map { inst -> EquipmentSelectionItem(inst.id, inst.name, inst.rarity, 1, false, false) }

        (stacks + instances).sortedByDescending { it.rarity }
    }

    var showDetailItem by remember { mutableStateOf<Any?>(null) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择$slotTypeText",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (availableItems.isEmpty()) {
                    Text(
                        text = "暂无可用的$slotTypeText",
                        fontSize = 12.sp,
                        color = Color.Black,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(60.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(availableItems, key = { it.id }) { item ->
                            UnifiedItemCard(
                                data = ItemCardData(
                                    id = item.id,
                                    name = item.name,
                                    rarity = item.rarity,
                                    quantity = item.quantity,
                                    isLocked = item.isLocked
                                ),
                                isSelected = selectedEquipmentId == item.id,
                                showViewButton = true,
                                onClick = {
                                    onSelect(item.id)
                                },
                                onViewDetail = {
                                    if (item.isStack) {
                                        equipmentStacks.find { it.id == item.id }?.let { showDetailItem = it }
                                    } else {
                                        allEquipment.find { it.id == item.id }?.let { showDetailItem = it }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认装备",
                    onClick = onConfirm,
                    enabled = selectedEquipmentId != null
                )
            }
        }
    }

    showDetailItem?.let { item ->
        ItemDetailDialog(
            item = item,
            onDismiss = { showDetailItem = null }
        )
    }
}

internal data class EquipmentSelectionItem(
    val id: String,
    val name: String,
    val rarity: Int,
    val quantity: Int,
    val isLocked: Boolean,
    val isStack: Boolean
)
