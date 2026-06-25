package com.xianxia.sect.ui.game.components.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.dialogs.shared.rememberDiscipleFilterState

/**
 * 拜师选择界面：半屏 + 筛选栏 + 弟子卡片网格。
 *
 * 候选过滤：存活 + 非自己 + 徒弟数未满（< 5）。
 * 点击卡片不立即确认，回调 [onMasterSelected] 交由父级弹确认框。
 */
@Composable
fun MasterApprenticeSelectDialog(
    currentDisciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onMasterSelected: (DiscipleAggregate) -> Unit
) {
    // 候选：存活 + 非自己 + 徒弟数 < 5
    val candidates = remember(currentDisciple.id, allDisciples) {
        allDisciples.filter { other ->
            other.isAlive &&
                other.id != currentDisciple.id &&
                countApprentices(other.id, allDisciples) < MAX_APPRENTICES_PER_MASTER
        }
    }

    val filterState = rememberDiscipleFilterState()
    val realmCounts = remember(candidates) { filterState.realmCounts(candidates) }
    val spiritRootCounts = remember(candidates) { filterState.spiritRootCounts(candidates) }

    val filtered = remember(candidates, filterState.realmFilter, filterState.spiritRootFilter, filterState.attributeSort) {
        filterState.filtered(candidates)
    }

    val realmFilterOptions = remember {
        listOf(
            1 to "炼气", 2 to "筑基", 3 to "金丹", 4 to "元婴",
            5 to "化神", 6 to "合体", 7 to "大乘", 8 to "渡劫"
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择师父",
        mode = DialogMode.Half,
        scrollableContent = false,
        headerContent = {
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
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "没有可拜师的弟子", fontSize = 14.sp, color = Color.Black)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { master ->
                        PortraitDiscipleCard(
                            disciple = master,
                            isSelected = false,
                            onClick = {
                                onMasterSelected(master)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 统计某弟子当前存活的徒弟数量 */
private fun countApprentices(masterId: String, allDisciples: List<DiscipleAggregate>): Int =
    allDisciples.count { it.isAlive && it.masterId == masterId }

private val MAX_APPRENTICES_PER_MASTER
    get() = DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER
