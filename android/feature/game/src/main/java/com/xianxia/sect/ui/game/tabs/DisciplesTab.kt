package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.DiscipleCardStyles
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.DropdownFilterButton
import com.xianxia.sect.ui.game.components.FilterChip
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors

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

