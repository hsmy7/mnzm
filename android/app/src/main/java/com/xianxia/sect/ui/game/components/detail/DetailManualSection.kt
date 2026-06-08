package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun ManualsSection(
    manuals: List<ManualInstance>,
    maxSlots: Int,
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    discipleId: String = "",
    onSlotClick: () -> Unit,
    onManualClick: (ManualInstance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "功法",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        val manualSlots = mutableListOf<ManualInstance?>()
        manuals.take(maxSlots).forEach { manualSlots.add(it) }
        while (manualSlots.size < maxSlots) manualSlots.add(null)

        val proficiencyMap = remember(manualProficiencies, discipleId) {
            manualProficiencies[discipleId]?.associateBy { it.manualId } ?: emptyMap()
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            manualSlots.chunked(4).forEachIndexed { rowIndex, rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowSlots.forEachIndexed { slotIndex, manual ->
                        val proficiencyData = manual?.id?.let { proficiencyMap[it] }
                        key(manual?.id ?: "empty_${rowIndex}_$slotIndex") {
                            ManualSlot(
                                manual = manual,
                                proficiencyData = proficiencyData,
                                modifier = Modifier.weight(1f),
                                onSlotClick = onSlotClick,
                                onManualClick = onManualClick
                            )
                        }
                    }
                    repeat(4 - rowSlots.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun ManualSlot(
    manual: ManualInstance?,
    modifier: Modifier = Modifier,
    proficiencyData: ManualProficiencyData? = null,
    onSlotClick: () -> Unit,
    onManualClick: (ManualInstance) -> Unit
) {
    val dismissDropdown = LocalDismissDropdown.current
    val masteryLevel = proficiencyData?.masteryLevel ?: 0
    val mastery = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel)
    val masteryText = mastery.displayName

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (manual != null) {
            UnifiedItemCard(
                data = ItemCardData(
                    name = manual.name,
                    rarity = manual.rarity,
                    isManual = true
                ),
                showQuantity = false,
                onClick = { onManualClick(manual) }
            )
            if (proficiencyData != null) {
                Text(
                    text = masteryText,
                    fontSize = 8.sp,
                    color = Color.Black
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(GameColors.PageBackground)
                    .border(1.dp, GameColors.Border, RoundedCornerShape(8.dp))
                    .clickable { dismissDropdown(); onSlotClick() },
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
fun ManualSelectionDialog(
    manualStacks: List<ManualStack>,
    allManuals: List<ManualInstance>,
    currentManualIds: List<String>,
    discipleRealm: Int,
    maxManualSlots: Int,
    selectedManualId: String?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val availableManualStacks = remember(manualStacks, allManuals, currentManualIds, discipleRealm, maxManualSlots) {
        if (currentManualIds.size >= maxManualSlots) {
            emptyList()
        } else {
            val manualMap = allManuals.associateBy { it.id }
            val hasMindManual = currentManualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
            val learnedNames = currentManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
            manualStacks.filter { stack ->
                !(hasMindManual && stack.type == ManualType.MIND) &&
                stack.name !in learnedNames &&
                GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
            }.sortedByDescending { it.rarity }
        }
    }

    var showDetailStack by remember { mutableStateOf<ManualStack?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择功法",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
        },
        text = {
            if (availableManualStacks.isEmpty()) {
                Text(
                    text = "暂无可学习的功法",
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
                    items(availableManualStacks, key = { it.id }) { stack ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = stack.id,
                                name = stack.name,
                                rarity = stack.rarity,
                                quantity = stack.quantity,
                                isLocked = stack.isLocked,
                                isManual = true
                            ),
                            isSelected = selectedManualId == stack.id,
                            showViewButton = true,
                            onClick = {
                                onSelect(stack.id)
                            },
                            onViewDetail = { showDetailStack = stack }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认学习",
                    onClick = onConfirm,
                    enabled = selectedManualId != null
                )
            }
        }
    )

    showDetailStack?.let { stack ->
        ItemDetailDialog(
            item = stack,
            onDismiss = { showDetailStack = null }
        )
    }
}
