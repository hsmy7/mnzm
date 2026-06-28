package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar

data class DiscipleSelectorConfig(
    val title: String,
    val emptyMessage: String = "没有符合条件的弟子",
    val headerColor: Color? = null,
    val defaultSortAttribute: String? = null,
    val currentId: String? = null,
    val extraAttributesProvider: ((DiscipleAggregate) -> List<Pair<String, Int>>)? = null
)

@Composable
fun DiscipleSelectorDialog(
    config: DiscipleSelectorConfig,
    disciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    viewModel: GameViewModel? = null
) {
    DisposableEffect(Unit) {
        viewModel?.activateSubDialogDomain("DiscipleSelector")
        onDispose {
            viewModel?.deactivateSubDialogDomain("DiscipleSelector")
        }
    }

    val filterState = rememberDiscipleFilterState(config.defaultSortAttribute)
    val realmCounts = remember(disciples) { filterState.realmCounts(disciples) }
    val spiritRootCounts = remember(disciples) { filterState.spiritRootCounts(disciples) }

    val filtered = remember(disciples, filterState.realmFilter, filterState.spiritRootFilter, filterState.attributeSort) {
        filterState.filtered(disciples)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = config.title,
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = {
            SpiritRootAttributeFilterBar(
                selectedSpiritRootFilter = filterState.spiritRootFilter,
                selectedAttributeSort = filterState.attributeSort,
                selectedRealmFilter = filterState.realmFilter,
                realmFilterOptions = REALM_FILTER_OPTIONS,
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
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = config.emptyMessage, fontSize = 14.sp, color = Color.Black)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { disciple ->
                        PortraitDiscipleCard(
                            disciple = disciple,
                            isCurrent = disciple.id == config.currentId,
                            isSelected = false,
                            extraAttributes = config.extraAttributesProvider?.invoke(disciple) ?: emptyList(),
                            onClick = {
                                onConfirm(listOf(disciple))
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}
