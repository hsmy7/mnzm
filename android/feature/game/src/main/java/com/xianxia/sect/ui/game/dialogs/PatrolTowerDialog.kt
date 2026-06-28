package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.ui.components.*
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.PatrolTowerViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
fun PatrolTowerDialog(
    buildingInstanceId: String = "",
    viewModel: GameViewModel,
    patrolTowerViewModel: PatrolTowerViewModel,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    onDismiss: () -> Unit
) {
    val gd = gameData ?: return
    val towerIndex = remember(buildingInstanceId) { patrolTowerViewModel.getTowerIndex(buildingInstanceId) }
    val patrolConfig = remember(gd.patrolConfigs, towerIndex) {
        gd.patrolConfigs.getOrElse(towerIndex) { PatrolConfig() }
    }
    val allSlots = gd.patrolSlots
    val slotsPerTower = patrolTowerViewModel.slotsPerTower
    val range = (towerIndex * slotsPerTower) until (towerIndex * slotsPerTower + slotsPerTower)

    var showAttackRangeDialog by remember { mutableStateOf(false) }
    var requireFullStatus by remember(patrolConfig) { mutableStateOf(patrolConfig.requireFullStatus) }
    var selectingSlotIndex by remember { mutableStateOf(-1) }
    var isSwapMode by remember { mutableStateOf(false) }

    val slots = remember(allSlots, towerIndex) {
        val list = allSlots.toMutableList()
        while (list.size < range.last + 1) list.add(PatrolSlot(index = list.size))
        range.map { list.getOrElse(it) { PatrolSlot(index = it) } }
    }

    val assignedIds = slots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()
    val discipleMap = disciples.associateBy { it.id }
    val availableDisciples = remember(allSlots, disciples, towerIndex) {
        disciples.filter { it.isAlive && it.status == DiscipleStatus.IDLE && it.id !in assignedIds }
            .sortedWith(compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer })
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "巡视楼",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("巡视弟子", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(Modifier.width(12.dp))
                    Text("弟子满状态才可进攻", fontSize = 10.sp, color = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape)
                            .border(1.5.dp, Color.Black, CircleShape)
                            .background(Color.Transparent, CircleShape)
                            .clickable {
                                requireFullStatus = !requireFullStatus
                                patrolTowerViewModel.updateRequireFullStatus(towerIndex, requireFullStatus)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (requireFullStatus) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GameButton(
                        text = "进攻范围",
                        onClick = { showAttackRangeDialog = true },
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        fontSize = 10.sp
                    )
                    GameButton(
                        text = "一键任命",
                        onClick = { patrolTowerViewModel.autoAssign(towerIndex) },
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val colsPerRow = slotsPerTower / 2
            for (row in 0 until 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (col in 0 until colsPerRow) {
                        val index = row * colsPerRow + col
                        val slot = slots.getOrNull(index) ?: PatrolSlot(index = index)
                        val assignedDisciple = discipleMap[slot.discipleId]
                        DiscipleSlot(
                            disciple = assignedDisciple,
                            showActions = true,
                            onSlotClick = {
                                assignedDisciple?.let {
                                    viewModel.showDiscipleDetail(DiscipleDetailRequest(it, disciples))
                                }
                            },
                            onEmptySlotClick = {
                                isSwapMode = false
                                selectingSlotIndex = index
                            },
                            onDismiss = { patrolTowerViewModel.removeDisciple(towerIndex, index) },
                            onSwap = {
                                isSwapMode = true
                                selectingSlotIndex = index
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    if (selectingSlotIndex >= 0) {
        DiscipleSelectorDialog(
            config = DiscipleSelectorConfig(
                title = if (isSwapMode) "更换巡视弟子" else "选择巡视弟子"
            ),
            disciples = availableDisciples,
            onDismiss = { selectingSlotIndex = -1 },
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    val id = selected.first().id
                    if (isSwapMode) patrolTowerViewModel.swapDisciple(towerIndex, selectingSlotIndex, id)
                    else patrolTowerViewModel.assignDisciple(towerIndex, selectingSlotIndex, id)
                }
                selectingSlotIndex = -1
            },
            viewModel = viewModel
        )
    }

    if (showAttackRangeDialog) {
        AttackRangeDialog(
            config = patrolConfig,
            onSave = { newConfig ->
                patrolTowerViewModel.updatePatrolConfig(towerIndex, newConfig)
                showAttackRangeDialog = false
            },
            onDismiss = { showAttackRangeDialog = false }
        )
    }
}

@Composable
private fun AttackRangeDialog(
    config: PatrolConfig,
    onSave: (PatrolConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val realmOptions = listOf(
        0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
        4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹",
        8 to "筑基", 9 to "炼气"
    )

    var selectedRealms by remember { mutableStateOf(config.targetRealms) }
    var maxCount by remember { mutableStateOf(config.maxBeastCount.toString()) }
    val hasChanges = selectedRealms != config.targetRealms || maxCount.toIntOrNull() != config.maxBeastCount

    var showUnsavedPrompt by remember { mutableStateOf(false) }

    UnifiedGameDialog(
        onDismissRequest = {
            if (hasChanges) showUnsavedPrompt = true
            else onDismiss()
        },
        title = "进攻范围",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("选择目标境界", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(realmOptions, key = { it.first }) { (realm, name) ->
                    val checked = realm in selectedRealms
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            selectedRealms = if (checked) selectedRealms - realm else selectedRealms + realm
                        }
                    ) {
                        Text(name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier.size(16.dp).clip(CircleShape)
                                .border(1.5.dp, Color.Black, CircleShape)
                                .background(Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (checked) Text("✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("进攻的妖兽数量范围为 1 - ", fontSize = 12.sp, color = Color.Black)
                BasicTextField(
                    value = maxCount.ifEmpty { "1" },
                    onValueChange = { v ->
                        val filtered = v.filter { it.isDigit() }
                        val num = filtered.toIntOrNull()
                        maxCount = when {
                            num == null -> "1"
                            num < 1 -> "1"
                            num > 13 -> "13"
                            else -> filtered
                        }
                    },
                    modifier = Modifier.width(40.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = Color.Black, textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = "取消",
                    onClick = { if (hasChanges) showUnsavedPrompt = true else onDismiss() },
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(16.dp))
                GameButton(
                    text = "保存",
                    onClick = {
                        onSave(PatrolConfig(
                            targetRealms = selectedRealms,
                            maxBeastCount = maxCount.toIntOrNull() ?: config.maxBeastCount,
                            requireFullStatus = config.requireFullStatus
                        ))
                    },
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight,
                    fontSize = 11.sp
                )
            }
        }
    }

    if (showUnsavedPrompt) {
        StandardPromptDialog(
            onDismissRequest = { showUnsavedPrompt = false },
            title = "提示",
            text = "有未保存的更改，是否保存？",
            confirmLabel = "保存",
            onConfirm = {
                showUnsavedPrompt = false
                onSave(PatrolConfig(
                    targetRealms = selectedRealms,
                    maxBeastCount = maxCount.toIntOrNull() ?: config.maxBeastCount,
                    requireFullStatus = config.requireFullStatus
                ))
            },
            dismissLabel = "不保存",
            onDismiss = {
                showUnsavedPrompt = false
                onDismiss()
            }
        )
    }
}
