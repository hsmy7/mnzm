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
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.ui.game.GameViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.components.ItemCardData
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
            verticalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
        ) {
            // 槽位网格固定 4 列（与装备区/敌方详情等距规格一致），
            // 不足 4 个的末行左对齐、尾部 weight 占位补齐
            manualSlots.chunked(SLOT_GRID_COLUMNS).forEachIndexed { rowIndex, rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SLOT_GRID_SPACING)
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
                    repeat(SLOT_GRID_COLUMNS - rowSlots.size) {
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

/** 功法选择弹窗参数分组（ManualSelectionDialog LongParameterList 收敛）。 */
data class ManualSelectionParams(
    val manualStacks: List<ManualStack>,
    val allManuals: List<ManualInstance>,
    val currentManualIds: List<String>,
    val discipleRealm: Int,
    val maxManualSlots: Int,
    val selectedManualId: String?,
    val viewModel: GameViewModel? = null
)

/**
 * 功法学习/选择界面（ManualSelectionDialog 拆分）：全屏 7:3 双栏，
 * 左侧仓库功法列表（关注优先→品阶降序、单选高亮、心法规则置底置灰），右侧选中功法详情（四区域 + 底部"更换"按钮）。
 * 进入默认选中列表第一个功法；[onConfirm] 接收最终选中功法 id。
 *
 * 心法规则：弟子已有心法时仓库心法置底置灰不可点击；弟子无心法时正常显示。
 */
@Composable
fun ManualSelectionDialog(
    params: ManualSelectionParams,
    onSelect: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val watchedKeys = params.viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value ?: emptySet()

    val items = remember(
        params.manualStacks, params.allManuals, params.currentManualIds,
        params.discipleRealm, params.maxManualSlots, watchedKeys
    ) {
        if (params.currentManualIds.size >= params.maxManualSlots) {
            emptyList()
        } else {
            val manualMap = params.allManuals.associateBy { it.id }
            val discipleHasMind = params.currentManualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
            val learnedNames = params.currentManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
            buildManualReplaceItems(
                stacks = params.manualStacks,
                learnedNames = learnedNames,
                discipleRealm = params.discipleRealm,
                mindItemsDisabled = discipleHasMind,
                watchedKeys = watchedKeys
            )
        }
    }

    ReplaceSelectionScreen(
        config = ReplaceSelectionConfig(
            title = "选择功法",
            emptyText = "暂无可学习的功法",
            items = items,
            selectedId = params.selectedManualId,
            confirmLabel = "更换",
            actions = ReplaceSelectionActions(
                onSelect = onSelect,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        )
    )
}
