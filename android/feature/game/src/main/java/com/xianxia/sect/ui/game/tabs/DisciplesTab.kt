package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getSpiritRootCount

// 其他Tab的占位实现
@Composable
internal fun DisciplesTab(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    equipment: List<EquipmentInstance>,
    manuals: List<ManualInstance>,
    manualStacks: List<ManualStack>,
    equipmentStacks: List<EquipmentStack>,
    viewModel: GameViewModel
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
            .distinctBy { it.id }
    }

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
        SpiritRootAttributeFilterBar(
            selectedSpiritRootFilter = selectedSpiritRootFilter,
            selectedAttributeSort = selectedAttributeSort,
            selectedRealmFilter = selectedRealmFilter,
            realmFilterOptions = REALM_FILTER_OPTIONS,
            realmCounts = realmCounts,
            spiritRootExpanded = spiritRootExpanded,
            attributeExpanded = attributeExpanded,
            realmExpanded = realmExpanded,
            spiritRootCounts = spiritRootCounts,
            onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
            onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
            onAttributeSortSelected = { selectedAttributeSort = it },
            onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
            onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
            onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
            onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
            onRealmExpandToggle = { realmExpanded = !realmExpanded }
        )

        if (filteredDisciples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无弟子",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = filteredDisciples,
                    key = { it.id },
                    contentType = { "disciple" }
                ) { disciple ->
                    DiscipleCard(
                        disciple = disciple,
                        onClick = { viewModel.showDiscipleDetail(DiscipleDetailRequest(disciple, filteredDisciples)) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiscipleCard(
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    PortraitDiscipleCard(disciple = disciple, onClick = onClick)
}

