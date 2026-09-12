package com.xianxia.sect.ui.game.dialogs.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.game.applyFilters

class DiscipleFilterState(defaultSortAttribute: String? = null) {
    var realmFilter by mutableStateOf<Set<Int>>(emptySet())
    var spiritRootFilter by mutableStateOf<Set<Int>>(emptySet())
    var attributeSort by mutableStateOf<String?>(null)
    var realmExpanded by mutableStateOf(false)
    var spiritRootExpanded by mutableStateOf(false)
    var attributeExpanded by mutableStateOf(false)

    private val defaultAttr: String? = defaultSortAttribute

    val filtered: (List<DiscipleAggregate>) -> List<DiscipleAggregate> = { disciples ->
        disciples.applyFilters(realmFilter, spiritRootFilter, attributeSort, defaultAttr)
    }

    fun realmCounts(disciples: List<DiscipleAggregate>): Map<Int, Int> =
        disciples.groupingBy { it.realm }.eachCount()

    fun spiritRootCounts(disciples: List<DiscipleAggregate>): Map<Int, Int> =
        disciples.groupingBy { it.spiritRoot.types.size }.eachCount()
}

@Composable
fun rememberDiscipleFilterState(defaultSortAttribute: String? = null): DiscipleFilterState {
    return remember(defaultSortAttribute) { DiscipleFilterState(defaultSortAttribute) }
}
