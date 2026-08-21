package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.ui.game.GameViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.game.LocalDismissDropdown
import com.xianxia.sect.ui.theme.GameColors


/** 槽位网格统一列数（玩家/敌方详情的功法与装备区均 4 列） */
internal const val SLOT_GRID_COLUMNS = 4

/** 槽位网格统一间距（横向 = 纵向，等距规格） */
internal val SLOT_GRID_SPACING = 6.dp


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
            horizontalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
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

/**
 * 装备更换/选择界面（EquipmentSelectionDialog 拆分）：全屏 7:3 双栏，
 * 左侧仓库装备列表（关注优先→品阶降序、单选高亮），右侧选中装备详情（四区域 + 底部"更换"按钮）。
 * 进入默认选中列表第一个装备；[onConfirm] 接收最终选中装备 id。
 */
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: GameViewModel? = null
) {
    val slotTypeText = equipmentSelectionSlotText(slotType = slotType)
    val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value ?: emptySet()
    val slotEnum = runCatching {
        EquipmentSlot.valueOf(slotType.uppercase(LocalLocale.current.platformLocale))
    }.getOrDefault(EquipmentSlot.WEAPON)

    val items = remember(
        allEquipment, equipmentStacks, slotEnum, currentEquipmentId, currentDiscipleId, discipleRealm, watchedKeys
    ) {
        buildEquipmentReplaceItems(
            stacks = equipmentStacks,
            instances = allEquipment,
            slot = slotEnum,
            currentEquipmentId = currentEquipmentId,
            currentDiscipleId = currentDiscipleId,
            discipleRealm = discipleRealm,
            watchedKeys = watchedKeys
        )
    }

    ReplaceSelectionScreen(
        config = ReplaceSelectionConfig(
            title = "更换$slotTypeText",
            emptyText = "暂无可用的$slotTypeText",
            items = items,
            selectedId = selectedEquipmentId,
            confirmLabel = "更换",
            actions = ReplaceSelectionActions(
                onSelect = onSelect,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        )
    )
}

/** 装备槽位中文名（EquipmentSelectionDialog 拆分） */
private fun equipmentSelectionSlotText(slotType: String): String = when (slotType) {
    "weapon" -> "武器"
    "armor" -> "护甲"
    "boots" -> "靴子"
    "accessory" -> "饰品"
    else -> "装备"
}
