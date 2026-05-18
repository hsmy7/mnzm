package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

data class DiscipleSelectorConfig(
    val title: String,
    val emptyMessage: String = "没有符合条件的弟子",
    val allowMultiSelect: Boolean = false,
    val maxSelection: Int = Int.MAX_VALUE,
    val confirmText: String = "确定",
    val cancelText: String = "取消",
    val headerColor: Color? = null
)

@Composable
fun DiscipleSelectorDialog(
    config: DiscipleSelectorConfig,
    disciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onConfirm: (List<DiscipleAggregate>) -> Unit
) {
    val filterState = rememberDiscipleFilterState()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val realmCounts = remember(disciples) { filterState.realmCounts(disciples) }
    val spiritRootCounts = remember(disciples) { filterState.spiritRootCounts(disciples) }

    val filtered = remember(disciples, filterState.realmFilter, filterState.spiritRootFilter, filterState.attributeSort) {
        filterState.filtered(disciples)
    }

    val realmFilterOptions = remember {
        listOf(
            1 to "炼气", 2 to "筑基", 3 to "金丹", 4 to "元婴",
            5 to "化神", 6 to "合体", 7 to "大乘", 8 to "渡劫"
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = config.title,
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpiritRootAttributeFilterBar(
                selectedSpiritRootFilter = filterState.spiritRootFilter,
                selectedAttributeSort = filterState.attributeSort,
                selectedRealmFilter = filterState.realmFilter,
                realmFilterOptions = realmFilterOptions,
                realmCounts = realmCounts,
                spiritRootExpanded = filterState.spiritRootExpanded,
                attributeExpanded = filterState.attributeExpanded,
                realmExpanded = filterState.realmExpanded,
                spiritRootCounts = spiritRootCounts,
                onSpiritRootFilterSelected = { filterState.spiritRootFilter += it },
                onSpiritRootFilterRemoved = { filterState.spiritRootFilter -= it },
                onAttributeSortSelected = { filterState.attributeSort = it },
                onRealmFilterSelected = { filterState.realmFilter += it },
                onRealmFilterRemoved = { filterState.realmFilter -= it },
                onSpiritRootExpandToggle = { filterState.spiritRootExpanded = !filterState.spiritRootExpanded },
                onAttributeExpandToggle = { filterState.attributeExpanded = !filterState.attributeExpanded },
                onRealmExpandToggle = { filterState.realmExpanded = !filterState.realmExpanded }
            )

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = config.emptyMessage, fontSize = 14.sp, color = Color.Black)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { disciple ->
                        val isSelected = disciple.id in selected
                        PortraitDiscipleCard(
                            disciple = disciple,
                            isSelected = isSelected,
                            onClick = {
                                if (config.allowMultiSelect) {
                                    selected = if (isSelected) selected - disciple.id
                                    else if (selected.size < config.maxSelection) selected + disciple.id
                                    else selected
                                } else {
                                    selected = if (isSelected) emptySet() else setOf(disciple.id)
                                }
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                GameButton(text = config.cancelText, onClick = onDismiss, fontSize = 12.sp)
                GameButton(
                    text = "${config.confirmText}${if (config.allowMultiSelect) "(${selected.size})" else ""}",
                    onClick = {
                        val chosen = filtered.filter { it.id in selected }
                        onConfirm(chosen)
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}
