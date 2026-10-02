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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
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

/** 装备六宫格每行列数（3×2：上行 头·身·手，下行 脚·武·腿） */
private const val EQUIP_GRID_ROW_SIZE = 3


@Composable
fun EquipmentSection(
    equippedByPart: Map<EquipmentSlot, EquipmentInstance?>,
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

        // 六部位宫格（显示序单一真源 = EquipmentSlot.displayOrder）
        EquipmentSlot.displayOrder.chunked(EQUIP_GRID_ROW_SIZE).forEach { rowParts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
            ) {
                rowParts.forEach { part ->
                    EquipmentSlot(
                        slotName = part.displayName,
                        equipment = equippedByPart[part],
                        modifier = Modifier.weight(1f),
                        onSlotClick = onSlotClick,
                        onEquipmentClick = onEquipmentClick,
                        slotType = part.name
                    )
                }
            }
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

/** 装备选择弹窗参数分组（EquipmentSelectionDialog LongParameterList 收敛）。 */
data class EquipmentSelectionParams(
    val slotType: String,
    val allEquipment: List<EquipmentInstance>,
    val currentEquipmentId: String?,
    val currentDiscipleId: String,
    val discipleRealm: Int,
    val selectedEquipmentId: String?,
    val viewModel: GameViewModel? = null
)

/**
 * 装备更换/选择界面：全屏 7:3 双栏，
 * 左侧仓库装备列表（关注优先→品阶降序、单选高亮），右侧选中装备详情（四区域 + 底部"更换"按钮）。
 * 进入默认选中列表第一个装备；[onConfirm] 接收最终选中装备 id。
 */
@Composable
fun EquipmentSelectionDialog(
    params: EquipmentSelectionParams,
    onSelect: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val slotTypeText = equipmentSelectionSlotText(slotType = params.slotType)
    val watchedKeys = params.viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value ?: emptySet()
    val slotEnum = EquipmentSlot.entries
        .find { it.name.equals(params.slotType, ignoreCase = true) }
        ?: EquipmentSlot.HEAD

    val items = remember(
        params.allEquipment, slotEnum,
        params.currentEquipmentId, params.currentDiscipleId, params.discipleRealm, watchedKeys
    ) {
        buildEquipmentReplaceItems(
            instances = params.allEquipment,
            slot = slotEnum,
            currentEquipmentId = params.currentEquipmentId,
            currentDiscipleId = params.currentDiscipleId,
            discipleRealm = params.discipleRealm,
            watchedKeys = watchedKeys
        )
    }

    ReplaceSelectionScreen(
        config = ReplaceSelectionConfig(
            title = "更换$slotTypeText",
            emptyText = "暂无可用的$slotTypeText",
            items = items,
            selectedId = params.selectedEquipmentId,
            confirmLabel = "更换",
            actions = ReplaceSelectionActions(
                onSelect = onSelect,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        )
    )
}

/** 装备槽位中文名（六部位 displayName 单一真源） */
private fun equipmentSelectionSlotText(slotType: String): String =
    EquipmentSlot.entries
        .find { it.name.equals(slotType, ignoreCase = true) }
        ?.displayName ?: "装备"
