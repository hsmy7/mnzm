@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.PatrolTowerViewModel
import com.xianxia.sect.ui.game.components.NumberInputPanel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.theme.ButtonSizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch



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

    var showAttackRangeDialog by remember { mutableStateOf(false) }
    var requireFullStatus by remember(patrolConfig) { mutableStateOf(patrolConfig.requireFullStatus) }
    var selectingSlotIndex by remember { mutableIntStateOf(-1) }
    var isSwapMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PatrolTowerDialogFrame(
        requireFullStatus = requireFullStatus,
        gameData = gd,
        towerIndex = towerIndex,
        patrolTowerViewModel = patrolTowerViewModel,
        disciples = disciples,
        frameCallbacks = PatrolFrameCallbacks(
            onDismiss = onDismiss,
            onToggleRequireFullStatus = {
                requireFullStatus = !requireFullStatus
                patrolTowerViewModel.updateRequireFullStatus(towerIndex, requireFullStatus)
            },
            onOpenAttackRangeDialog = { showAttackRangeDialog = true },
            onDiscipleClicked = { d -> viewModel.showDiscipleDetail(DiscipleDetailRequest(d, disciples)) },
            onSelectSlot = { index, swapMode -> isSwapMode = swapMode; selectingSlotIndex = index },
            onSlotDismissed = { index -> patrolTowerViewModel.removeDiscipleAsync(towerIndex, index) }
        )
    )

    if (selectingSlotIndex >= 0) {
        PatrolDiscipleSelector(
            data = PatrolSelectorData(
                gameData = gd,
                towerIndex = towerIndex,
                slotsPerTower = patrolTowerViewModel.slotsPerTower,
                disciples = disciples,
                isSwapMode = isSwapMode
            ),
            viewModel = viewModel,
            onConfirm = buildConfirmAction(
                scope, patrolTowerViewModel, towerIndex,
                { selectingSlotIndex }, { isSwapMode }
            ) { selectingSlotIndex = -1 },
            onDismiss = { selectingSlotIndex = -1 }
        )
    }

    if (showAttackRangeDialog) {
        PatrolAttackRangeDialog(
            config = patrolConfig,
            towerIndex = towerIndex,
            patrolTowerViewModel = patrolTowerViewModel,
            onDismiss = { showAttackRangeDialog = false }
        )
    }
}

/** 巡视楼框架回调（PatrolTowerDialog 拆分） */
private data class PatrolFrameCallbacks(
    val onDismiss: () -> Unit,
    val onToggleRequireFullStatus: () -> Unit,
    val onOpenAttackRangeDialog: () -> Unit,
    val onDiscipleClicked: (DiscipleAggregate) -> Unit,
    val onSelectSlot: (index: Int, swapMode: Boolean) -> Unit,
    val onSlotDismissed: (Int) -> Unit
)

/** 主对话框框架（PatrolTowerDialog 拆分）：UnifiedGameDialog + 顶部栏 + 槽位网格 */
@Composable
private fun PatrolTowerDialogFrame(
    requireFullStatus: Boolean,
    gameData: GameData,
    towerIndex: Int,
    patrolTowerViewModel: PatrolTowerViewModel,
    disciples: List<DiscipleAggregate>,
    frameCallbacks: PatrolFrameCallbacks
) {
    val slots = remember(gameData.patrolSlots, towerIndex) {
        val start = towerIndex * patrolTowerViewModel.slotsPerTower
        val end = start + patrolTowerViewModel.slotsPerTower
        val list = gameData.patrolSlots.toMutableList()
        while (list.size < end) list.add(PatrolSlot(index = list.size))
        (start until end).map { list.getOrElse(it) { PatrolSlot(index = it) } }
    }
    val discipleMap = disciples.associateBy { it.id }

    UnifiedGameDialog(
        onDismissRequest = frameCallbacks.onDismiss,
        title = "巡视楼",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            PatrolTowerHeader(
                requireFullStatus = requireFullStatus,
                onToggleRequireFullStatus = frameCallbacks.onToggleRequireFullStatus,
                onOpenAttackRangeDialog = frameCallbacks.onOpenAttackRangeDialog,
                onAutoAssign = { patrolTowerViewModel.autoAssign(towerIndex) }
            )

            Spacer(Modifier.height(8.dp))

            PatrolSlotsGrid(
                slots = slots,
                discipleMap = discipleMap,
                onDiscipleClick = frameCallbacks.onDiscipleClicked,
                onEmptySlotClick = { index -> frameCallbacks.onSelectSlot(index, false) },
                onSlotDismiss = frameCallbacks.onSlotDismissed,
                onSlotSwap = { index -> frameCallbacks.onSelectSlot(index, true) }
            )
        }
    }
}

/** 顶部栏（PatrolTowerDialog 拆分）：满状态勾选 + 进攻范围/一键任命 */
@Composable
private fun PatrolTowerHeader(
    requireFullStatus: Boolean,
    onToggleRequireFullStatus: () -> Unit,
    onOpenAttackRangeDialog: () -> Unit,
    onAutoAssign: () -> Unit
) {
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
                    .clickable(onClick = onToggleRequireFullStatus),
                contentAlignment = Alignment.Center
            ) {
                if (requireFullStatus) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GameButton(
                text = "进攻范围",
                onClick = onOpenAttackRangeDialog,
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                fontSize = 10.sp
            )
            GameButton(
                text = "一键任命",
                onClick = onAutoAssign,
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                fontSize = 10.sp
            )
        }
    }
}

/** 巡视弟子槽位网格（PatrolTowerDialog 拆分） */
@Composable
private fun PatrolSlotsGrid(
    slots: List<PatrolSlot>,
    discipleMap: Map<String, DiscipleAggregate>,
    onDiscipleClick: (DiscipleAggregate) -> Unit,
    onEmptySlotClick: (Int) -> Unit,
    onSlotDismiss: (Int) -> Unit,
    onSlotSwap: (Int) -> Unit
) {
    val colsPerRow = slots.size / 2
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
                        assignedDisciple?.let { onDiscipleClick(it) }
                    },
                    onEmptySlotClick = { onEmptySlotClick(index) },
                    onDismiss = { onSlotDismiss(index) },
                    onSwap = { onSlotSwap(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 巡视弟子选择弹窗数据（PatrolTowerDialog 拆分） */
private data class PatrolSelectorData(
    val gameData: GameData,
    val towerIndex: Int,
    val slotsPerTower: Int,
    val disciples: List<DiscipleAggregate>,
    val isSwapMode: Boolean
)

/** 巡视弟子选择弹窗装配（PatrolTowerDialog 拆分） */
@Composable
private fun PatrolDiscipleSelector(
    data: PatrolSelectorData,
    viewModel: GameViewModel,
    onConfirm: (List<DiscipleAggregate>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember(data.gameData.patrolSlots, data.towerIndex) {
        val start = data.towerIndex * data.slotsPerTower
        val end = start + data.slotsPerTower
        val list = data.gameData.patrolSlots.toMutableList()
        while (list.size < end) list.add(PatrolSlot(index = list.size))
        (start until end).map { list.getOrElse(it) { PatrolSlot(index = it) } }
    }
    val assignedIds = slots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()
    val availableDisciples = remember(slots, data.disciples) {
        data.disciples.filter { it.isAlive && it.id !in assignedIds }
            .sortedWith(compareBy<DiscipleAggregate> { it.realm }.thenByDescending { it.realmLayer })
    }
    val showAllEnabled = data.gameData.showAllAvailableDisciples
    val battleAndExplorationIds = remember(data.gameData) {
        val allBattleIds = data.gameData.battleTeams.flatMap {
            it.slots.mapNotNull { s -> s.discipleId.takeIf(String::isNotEmpty) }
        }
        val allCaveExplorationIds = data.gameData.caveExplorationTeams.flatMap { it.memberIds }
        (allBattleIds + allCaveExplorationIds).toSet()
    }

    DiscipleSelectorDialog(
        config = DiscipleSelectorConfig(
            title = if (data.isSwapMode) "更换巡视弟子" else "选择巡视弟子"
        ),
        disciples = availableDisciples,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        viewModel = viewModel
    )
}

/** 槽位指派/交换执行（PatrolTowerDialog 拆分） */
private suspend fun performSlotAction(
    patrolTowerViewModel: PatrolTowerViewModel,
    towerIndex: Int,
    slotIndex: Int,
    isSwap: Boolean,
    discipleId: String
) {
    if (isSwap) patrolTowerViewModel.swapDisciple(towerIndex, slotIndex, discipleId)
    else patrolTowerViewModel.assignDiscipleAsync(towerIndex, slotIndex, discipleId)
}

/** 选择确认动作（PatrolTowerDialog 拆分）：点击时读取当前槽位/模式并指派 */
private fun buildConfirmAction(
    scope: CoroutineScope,
    patrolTowerViewModel: PatrolTowerViewModel,
    towerIndex: Int,
    readSlotIndex: () -> Int,
    readSwapMode: () -> Boolean,
    onDone: () -> Unit
): (List<DiscipleAggregate>) -> Unit = { selected ->
    if (selected.isNotEmpty()) {
        val id = selected.first().id
        val slotIndex = readSlotIndex()
        val swapMode = readSwapMode()
        scope.launch { performSlotAction(patrolTowerViewModel, towerIndex, slotIndex, swapMode, id) }
    }
    onDone()
}

/** 进攻范围弹窗装配（PatrolTowerDialog 拆分） */
@Composable
private fun PatrolAttackRangeDialog(
    config: PatrolConfig,
    towerIndex: Int,
    patrolTowerViewModel: PatrolTowerViewModel,
    onDismiss: () -> Unit
) {
    AttackRangeDialog(
        config = config,
        onSave = { newConfig ->
            patrolTowerViewModel.updatePatrolConfig(towerIndex, newConfig)
            onDismiss()
        },
        onDismiss = onDismiss
    )
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
        scrollableContent = false,
        // 含数量输入框：冻结宿主窗口系统栏操作（荣耀X70键盘频闪根治）
        freezeSystemBars = true
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("选择目标境界", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Spacer(Modifier.height(8.dp))

            RealmOptionGrid(
                realmOptions = realmOptions,
                selectedRealms = selectedRealms,
                onRealmToggle = { realm ->
                    selectedRealms = if (realm in selectedRealms) selectedRealms - realm else selectedRealms + realm
                }
            )

            Spacer(Modifier.height(16.dp))

            MaxCountInput(
                maxCount = maxCount,
                onMaxCountChange = { maxCount = sanitizeBeastCount(it) }
            )

            Spacer(Modifier.height(16.dp))

            AttackRangeActionButtons(
                hasChanges = hasChanges,
                onCancel = { if (hasChanges) showUnsavedPrompt = true else onDismiss() },
                onSave = { onSave(buildPatrolConfig(config, selectedRealms, maxCount)) }
            )
        }
    }

    if (showUnsavedPrompt) {
        UnsavedPromptDialog(
            onConfirm = { showUnsavedPrompt = false; onSave(buildPatrolConfig(config, selectedRealms, maxCount)) },
            onDismiss = { showUnsavedPrompt = false; onDismiss() }
        )
    }
}

/** 境界多选网格（AttackRangeDialog 拆分） */
@Composable
private fun RealmOptionGrid(
    realmOptions: List<Pair<Int, String>>,
    selectedRealms: Set<Int>,
    onRealmToggle: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(realmOptions, key = { it.first }, contentType = { "realm_option" }) { (realm, name) ->
            val checked = realm in selectedRealms
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onRealmToggle(realm) }
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
}

/**
 * 妖兽数量输入行（AttackRangeDialog 拆分）：点击数量框弹出自绘数字面板
 * （NumberInputPanel，2026-09 IME 状态机根治：数字输入绕开系统 IME）。
 */
@Composable
private fun MaxCountInput(
    maxCount: String,
    onMaxCountChange: (String) -> Unit
) {
    var showPanel by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("进攻的妖兽数量范围为 1 - ", fontSize = 12.sp, color = Color.Black)
        Box(
            modifier = Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                .clickableWithSound(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showPanel = true }
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = maxCount.ifEmpty { "1" },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
    }
    if (showPanel) {
        NumberInputPanel(
            initialValue = maxCount.toIntOrNull()?.coerceIn(1, PATROL_MAX_BEAST_COUNT) ?: 1,
            maxQuantity = PATROL_MAX_BEAST_COUNT,
            onConfirm = { value ->
                onMaxCountChange(value.toString())
                showPanel = false
            },
            onDismiss = { showPanel = false }
        )
    }
}

/** 取消/保存按钮行（AttackRangeDialog 拆分） */
// 拆分搬移:参数保留原签名语义
@Suppress("UnusedParameter")
@Composable
private fun AttackRangeActionButtons(
    hasChanges: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GameButton(
            text = "取消",
            onClick = onCancel,
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(16.dp))
        GameButton(
            text = "保存",
            onClick = onSave,
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            fontSize = 11.sp
        )
    }
}

/** 进攻妖兽数量上限（数量输入钳制目标，命名常量防魔法数字） */
private const val PATROL_MAX_BEAST_COUNT = 13

/** 数量输入规范化（AttackRangeDialog 拆分）：1~13 数字限制 */
private fun sanitizeBeastCount(raw: String): String {
    val filtered = raw.filter { it.isDigit() }
    val num = filtered.toIntOrNull()
    return when {
        num == null -> "1"
        num < 1 -> "1"
        num > PATROL_MAX_BEAST_COUNT -> PATROL_MAX_BEAST_COUNT.toString()
        else -> filtered
    }
}

/** 未保存更改确认弹窗（AttackRangeDialog 拆分） */
@Composable
private fun UnsavedPromptDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "提示",
        text = "有未保存的更改，是否保存？",
        confirmLabel = "保存",
        onConfirm = onConfirm,
        dismissLabel = "不保存",
        onDismiss = onDismiss
    )
}

/** 保存配置构造（AttackRangeDialog 拆分） */
private fun buildPatrolConfig(
    config: PatrolConfig,
    selectedRealms: Set<Int>,
    maxCount: String
): PatrolConfig = PatrolConfig(
    targetRealms = selectedRealms,
    maxBeastCount = maxCount.toIntOrNull() ?: config.maxBeastCount,
    requireFullStatus = config.requireFullStatus
)
