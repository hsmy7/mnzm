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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.filterByDiscipleStatus

@Composable
fun DiscipleSelectorDialog(
    config: DiscipleSelectorConfig,
    disciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    viewModel: GameViewModel? = null,
    showAllEnabled: Boolean = false,
    battleAndExplorationIds: Set<String> = emptySet(),
    scrimEnabled: Boolean = true
) {
    DisposableEffect(Unit) {
        viewModel?.activateSubDialogDomain("DiscipleSelector")
        onDispose {
            viewModel?.deactivateSubDialogDomain("DiscipleSelector")
        }
    }

    val filterState = rememberDiscipleFilterState(config.defaultSortAttribute)

    val statusFiltered = remember(
        disciples, showAllEnabled, battleAndExplorationIds,
        config.additionalCheck, config.currentId, config.alwaysIncludeCurrentId
    ) {
        config.statusFilteredDisciples(
            disciples = disciples, showAllEnabled = showAllEnabled,
            battleAndExplorationIds = battleAndExplorationIds
        )
    }

    val realmCounts = remember(statusFiltered) { filterState.realmCounts(statusFiltered) }
    val spiritRootCounts = remember(statusFiltered) { filterState.spiritRootCounts(statusFiltered) }

    val filtered = remember(
        statusFiltered, filterState.realmFilter, filterState.spiritRootFilter, filterState.attributeSort
    ) {
        statusFiltered.let { filterState.filtered(it) }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = config.title,
        mode = DialogMode.Half,
        scrollableContent = false,
        scrimEnabled = scrimEnabled,
        headerContent = {
            DiscipleSelectorFilterHeader(
                filterState = filterState,
                realmCounts = realmCounts,
                spiritRootCounts = spiritRootCounts,
                viewModel = viewModel,
                showAllEnabled = showAllEnabled
            )
        }
    ) {
        DiscipleSelectorContent(
            filtered = filtered,
            emptyMessage = config.emptyMessage,
            currentId = config.currentId,
            extraAttributesProvider = config.extraAttributesProvider,
            onDiscipleClick = { disciple ->
                onConfirm(listOf(disciple))
                onDismiss()
            }
        )
    }
}

/** 状态过滤弟子列表：状态过滤 + 当前弟子强制包含 */
private fun DiscipleSelectorConfig.statusFilteredDisciples(
    disciples: List<DiscipleAggregate>,
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String>
): List<DiscipleAggregate> {
    val base = disciples.filterByDiscipleStatus(
        showAllEnabled, battleAndExplorationIds,
        additionalCheck = additionalCheck ?: { true }
    )
    val needCurrentInclusion = alwaysIncludeCurrentId && currentId != null
    val currentIsAlive = currentId?.let { id -> disciples.any { it.id == id && it.isAlive } } == true
    if (needCurrentInclusion && currentIsAlive) {
        val current = disciples.filter { it.id == currentId && it.isAlive }
        return (base + current).distinctBy { it.id }
    }
    return base
}

/** 过滤条：灵根/属性/境界过滤 + 显示全部勾选 */
@Composable
private fun DiscipleSelectorFilterHeader(
    filterState: DiscipleFilterState,
    realmCounts: Map<Int, Int>,
    spiritRootCounts: Map<Int, Int>,
    viewModel: GameViewModel?,
    showAllEnabled: Boolean
) {
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
        onRealmExpandToggle = { filterState.realmExpanded = !filterState.realmExpanded },
        // viewModel 为空时切换无意义（setShowAllAvailableDisciples 需 GameViewModel），隐藏复选框
        showAllCheckboxVisible = viewModel != null,
        showAllEnabled = showAllEnabled,
        onShowAllToggle = { viewModel?.settings?.setShowAllAvailableDisciples(!showAllEnabled) }
    )
}

/** 弟子选择内容区：空态提示或两列弟子卡片网格 */
@Composable
private fun DiscipleSelectorContent(
    filtered: List<DiscipleAggregate>,
    emptyMessage: String,
    currentId: String?,
    extraAttributesProvider: ((DiscipleAggregate) -> List<Pair<String, Int>>)?,
    onDiscipleClick: (DiscipleAggregate) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emptyMessage, fontSize = 14.sp, color = Color.Black)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.id }, contentType = { "disciple" }) { disciple ->
                    PortraitDiscipleCard(
                        disciple = disciple,
                        isCurrent = disciple.id == currentId,
                        isSelected = false,
                        extraAttributes = extraAttributesProvider?.invoke(disciple) ?: emptyList(),
                        onClick = { onDiscipleClick(disciple) }
                    )
                }
            }
        }
    }
}

/** 选择器配置（声明置于 [DiscipleSelectorDialog] 之后，使首个顶层声明与文件名一致） */
data class DiscipleSelectorConfig(
    val title: String,
    val emptyMessage: String = "没有符合条件的弟子",
    val headerColor: Color? = null,
    val defaultSortAttribute: String? = null,
    val currentId: String? = null,
    val extraAttributesProvider: ((DiscipleAggregate) -> List<Pair<String, Int>>)? = null,
    /** 状态过滤之外的附加条件（如 realmLayer/age/已选 ID 排除） */
    val additionalCheck: ((DiscipleAggregate) -> Boolean)? = null,
    /** 当前已分配弟子强制包含在筛选中（无论状态过滤结果） */
    val alwaysIncludeCurrentId: Boolean = false
)
