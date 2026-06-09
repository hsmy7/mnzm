package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HERB_GARDEN_THEME
import com.xianxia.sect.ui.game.ProductionDirectDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.ProductionSlotItem
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.game.components.ItemDetailDialog

@Composable
fun HerbGardenDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showDirectDiscipleSelection by remember { mutableStateOf<Int?>(null) }

    val elderSlots = gameData?.elderSlots ?: ElderSlots()
    val activeSectId = gameData?.activeSectId ?: ""
    val herbGardenDisciples = elderSlots.herbGardenDisciples.filter { it.sectId == activeSectId }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "灵植阁",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HerbGardenDirectDiscipleSection(
                    directDisciples = herbGardenDisciples,
                    disciples = disciples,
                    onDirectDiscipleClick = { index ->
                        val slot = herbGardenDisciples.getOrNull(index)
                        val d = if (slot?.isActive == true) disciples.find { it.id == slot.discipleId } else null
                        d?.let { viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
                    },
                    onDirectDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("herbGarden", index) },
                    onDirectDiscipleSwap = { index -> showDirectDiscipleSelection = index }
                )

            }
        }
    }

    showDirectDiscipleSelection?.let { slotIndex ->
        ProductionDirectDiscipleSelectionDialog(
            theme = HERB_GARDEN_THEME,
            disciples = disciples.filter { it.isAlive },
            elderSlots = elderSlots,
            onDismiss = { showDirectDiscipleSelection = null },
            onSelect = { discipleId ->
                productionViewModel.assignDirectDisciple("herbGarden", slotIndex, discipleId)
                showDirectDiscipleSelection = null
            }
        )
    }

}

@Composable
private fun HerbGardenDirectDiscipleSection(
    directDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit,
    onDirectDiscipleSwap: (Int) -> Unit = {}
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "灵植弟子",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.getHerbGardenDiscipleInfo())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (0 until 1).forEach { index ->
                val slot = directDisciples.getOrNull(index) ?: DirectDiscipleSlot(index = index)
                val agg = if (slot.isActive) disciples.find { it.id == slot.discipleId } else null
                val borderColor = if (slot.isActive) {
                    try { Color(android.graphics.Color.parseColor(agg?.spiritRoot?.countColor)) }
                    catch (e: Exception) { Color(0xFF4CAF50) }
                } else {
                    GameColors.Border
                }
                HerbGardenDirectDiscipleSlotItem(
                    disciple = agg,
                    borderColor = borderColor,
                    onSlotClick = { onDirectDiscipleClick(index) },
                    onDismiss = { onDirectDiscipleRemove(index) },
                    onSwap = { onDirectDiscipleSwap(index) }
                )
            }
        }
    }
}

@Composable
private fun HerbGardenDirectDiscipleSlotItem(
    disciple: DiscipleAggregate?,
    borderColor: Color,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    DiscipleSlot(
        disciple = disciple,
        borderColor = borderColor,
        showActions = true,
        onSlotClick = { onSlotClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onDismiss() },
        onSwap = { onSwap() }
    )
}

