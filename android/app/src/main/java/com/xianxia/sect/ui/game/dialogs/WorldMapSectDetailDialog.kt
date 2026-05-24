package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectScoutInfo
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.DiscipleSlotWithActions
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.tabs.REALM_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun WorldMapSectDetailDialog(
    sect: WorldSect,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    worldMapViewModel: WorldMapViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val isAlly = worldMapViewModel.isAlly(sect.id)
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect.id)?.lastGiftYear ?: 0) == currentYear
    var showGiftedMessage by remember { mutableStateOf(false) }
    var showAttackDialog by remember { mutableStateOf(false) }
    var showGarrisonSelection by remember { mutableStateOf<Int?>(null) }
    var selectedGarrisonDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }

    val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
    val relation = if (playerSect != null) {
        gameData?.sectRelations?.find {
            (it.sectId1 == playerSect.id && it.sectId2 == sect.id) ||
            (it.sectId1 == sect.id && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    } else 0

    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val relationColor = Color(relationLevel.colorHex)

    UnifiedGameDialog(onDismissRequest = onDismiss, title = sect.name, mode = DialogMode.Half, scrollableContent = false) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tags that were in the header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!sect.isPlayerSect) {
                    Text(
                        text = sect.levelName,
                        fontSize = 10.sp,
                        color = Color.Black,
                        modifier = Modifier
                            .background(
                                GameColors.CardBackground,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (sect.isPlayerSect) {
                    Text(
                        text = "本宗",
                        fontSize = 10.sp,
                        color = Color(0xFFFF8C00),
                        modifier = Modifier
                            .background(
                                Color(0xFFFFF3E0),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                } else if (isAlly) {
                    Text(
                        text = "盟友",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier
                            .background(
                                Color(0xFFE8F5E9),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            if (!sect.isPlayerSect) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "关系:",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = relationLevel.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = relationColor
                    )
                    Text(
                        text = "(${relation})",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            if (!sect.isPlayerSect) {
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Text(
                    text = "弟子分布",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                val scoutInfo = gameData?.sectDetails?.get(sect.id)?.scoutInfo ?: SectScoutInfo()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (0..4).forEach { realmIndex ->
                        val realmName = GameConfig.Realm.getName(realmIndex)
                        val count = scoutInfo.disciples[realmIndex] ?: 0
                        val isScouted = scoutInfo.sectId.isNotEmpty()
                        val displayText = if (isScouted) "$count" else "?"
                        val textColor = if (isScouted) {
                            if (count > 0) Color(0xFF4CAF50) else Color.Black
                        } else {
                            Color(0xFFFF9800)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = realmName,
                                fontSize = 10.sp,
                                color = Color.Black
                            )
                            Text(
                                text = displayText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (5..9).forEach { realmIndex ->
                        val realmName = GameConfig.Realm.getName(realmIndex)
                        val count = scoutInfo.disciples[realmIndex] ?: 0
                        val isScouted = scoutInfo.sectId.isNotEmpty()
                        val displayText = if (isScouted) "$count" else "?"
                        val textColor = if (isScouted) {
                            if (count > 0) Color(0xFF4CAF50) else Color.Black
                        } else {
                            Color(0xFFFF9800)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = realmName,
                                fontSize = 10.sp,
                                color = Color.Black
                            )
                            Text(
                                text = displayText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        }
                    }
                }
            }

            if (!sect.isPlayerSect) {
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!sect.isPlayerOccupied) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GameButton(
                            text = "探查",
                            onClick = {
                                worldMapViewModel.openScoutDialog(sect.id)
                                onDismiss()
                            }
                        )

                        GameButton(
                            text = if (hasGiftedThisYear) "已送礼" else "送礼",
                            onClick = {
                                if (hasGiftedThisYear) {
                                    showGiftedMessage = true
                                } else {
                                    worldMapViewModel.openGiftDialog(sect.id)
                                    onDismiss()
                                }
                            }
                        )

                        GameButton(
                            text = if (isAlly) "盟约" else "结盟",
                            onClick = {
                                worldMapViewModel.openAllianceDialog(sect.id)
                                onDismiss()
                            },
                            enabled = relationLevel == SectRelationLevel.INTIMATE || isAlly
                        )
                    }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!sect.isPlayerOccupied) {
                            GameButton(
                                text = "交易",
                                onClick = {
                                    worldMapViewModel.openSectTradeDialog(sect.id)
                                    onDismiss()
                                }
                            )
                        }

                        if (!sect.isPlayerOccupied) {
                            GameButton(
                                text = "进攻",
                                onClick = {
                                    showAttackDialog = true
                                }
                            )
                        }
                    }

                    if (sect.isPlayerOccupied) {
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                        GameButton(
                            text = "进入",
                            onClick = {
                                viewModel.enterSect(sect.id)
                                onDismiss()
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "驻守弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )

                        val latestSect = gameData?.worldMapSects?.find { it.id == sect.id } ?: sect
                        val garrisonSlots = latestSect.garrisonSlots

                        for (row in 0..1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                            ) {
                                for (col in 0..4) {
                                    val slotIndex = row * 5 + col
                                    if (slotIndex < garrisonSlots.size) {
                                        val gSlot = garrisonSlots[slotIndex]
                                        val gDisciple = if (gSlot.isActive) disciples.find { it.id == gSlot.discipleId } else null
                                        GarrisonSlotBox(
                                            disciple = gDisciple,
                                            spiritRootColor = gSlot.discipleSpiritRootColor,
                                            portraitRes = gSlot.portraitRes,
                                            onClick = {
                                                if (gDisciple != null) {
                                                    selectedGarrisonDetail = gDisciple
                                                } else {
                                                    showGarrisonSelection = slotIndex
                                                }
                                            },
                                            onSwap = {
                                                showGarrisonSelection = slotIndex
                                            },
                                            onRemoveClick = {
                                                worldMapViewModel.removeGarrisonDisciple(sect.id, slotIndex)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

                Spacer(modifier = Modifier.height(4.dp))

                GameButton(
                    text = "进入",
                    onClick = {
                        viewModel.enterSect("")
                        onDismiss()
                    }
                )
            }
        }
    }

    if (showGiftedMessage) {
        GiftedMessageToast(
            message = "今年已送过礼品等明年再来吧",
            onDismiss = { showGiftedMessage = false }
        )
    }

    if (showAttackDialog) {
        val idleDisciples = viewModel.discipleAggregates.value.filter {
            it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE
        }
        AttackDiscipleDialog(
            sectName = sect.name,
            disciples = idleDisciples,
            gameData = gameData,
            viewModel = viewModel,
            onAttack = { attackSlots ->
                worldMapViewModel.attackSect(sect.id, attackSlots)
                showAttackDialog = false
                onDismiss()
            },
            onDismiss = { showAttackDialog = false }
        )
    }

    if (showGarrisonSelection != null) {
        val slotIndex = showGarrisonSelection!!
        val latestSect = gameData?.worldMapSects?.find { it.id == sect.id } ?: sect
        val garrisonedIds = latestSect.garrisonSlots.map { it.discipleId }.filter { it.isNotEmpty() }.toSet()
        val idleDisciples = viewModel.discipleAggregates.value.filter {
            it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE && it.id !in garrisonedIds
        }
        GarrisonDiscipleSelectionDialog(
            disciples = idleDisciples,
            onSelect = { disciple ->
                worldMapViewModel.assignGarrisonDisciple(sect.id, slotIndex, disciple.id)
                showGarrisonSelection = null
            },
            onDismiss = { showGarrisonSelection = null }
        )
    }

    if (selectedGarrisonDetail != null) {
        val equipment by viewModel.equipment.collectAsState()
        val manuals by viewModel.manuals.collectAsState()
        val manualStacks by viewModel.manualStacks.collectAsState()
        val equipmentStacks by viewModel.equipmentStacks.collectAsState()
        Dialog(
            onDismissRequest = { selectedGarrisonDetail = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            DiscipleDetailDialog(
                disciple = selectedGarrisonDetail!!,
                allDisciples = disciples,
                allEquipment = equipment,
                allManuals = manuals,
                manualStacks = manualStacks,
                equipmentStacks = equipmentStacks,
                manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
                viewModel = viewModel,
                onDismiss = { selectedGarrisonDetail = null }
            )
        }
    }
}

@Composable
private fun GarrisonSlotBox(
    disciple: DiscipleAggregate?,
    spiritRootColor: String,
    portraitRes: String,
    onClick: () -> Unit,
    onSwap: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val borderColor = if (disciple != null) {
        try { Color(android.graphics.Color.parseColor(spiritRootColor)) }
        catch (e: Exception) { GameColors.Border }
    } else {
        GameColors.Border
    }

    DiscipleSlotWithActions(
        disciple = disciple,
        borderColor = borderColor,
        onSlotClick = { onClick() },
        onEmptySlotClick = { onSwap() },
        onDismiss = { onRemoveClick() },
        onSwap = { onSwap() }
    )
}

@Composable
private fun GarrisonDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val availableDisciples = remember(disciples) {
        disciples.filter { it.isAlive && it.status == com.xianxia.sect.core.model.DiscipleStatus.IDLE && it.realmLayer > 0 }
            .sortedByFollowAndRealm()
    }

    val realmCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(availableDisciples) {
        availableDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(availableDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        availableDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    UnifiedGameDialog(onDismissRequest = onDismiss, title = "选择驻守弟子", mode = DialogMode.Half, scrollableContent = false,
        headerContent = {
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
                onRealmExpandToggle = { realmExpanded = !realmExpanded },
                isCompact = true
            )
        }) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (availableDisciples.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无空闲弟子",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                ) {

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredDisciples, key = { it.id }) { disciple ->
                        PortraitDiscipleCard(
                            disciple = disciple,
                            onClick = { onSelect(disciple) }
                        )
                    }
                }
            }
        }
    }
}

}
