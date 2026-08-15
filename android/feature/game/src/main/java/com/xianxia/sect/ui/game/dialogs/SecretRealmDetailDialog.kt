package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorConfig
import com.xianxia.sect.ui.game.dialogs.shared.DiscipleSelectorDialog
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.theme.ButtonSizes

/** 远古秘境详情描述文案 */
private const val SECRET_REALM_DESCRIPTION =
    "上古大能陨落之地，藏有无数机缘与凶险。每逢天地灵气波动之际现世，五十年一遇。"

/** 秘境详情内容数据（SecretRealmDetailDialog 拆分） */
private data class SecretRealmContentData(
    val realm: MapItem.SecretRealm,
    val gameData: GameData?,
    val hasSession: Boolean,
    val slots: List<String?>,
    val discipleMap: Map<String, DiscipleAggregate>,
    val canStart: Boolean
)

/**
 * 远古秘境详情半屏界面：精灵图 + 描述 / 探索一队 + 一键任命 / 4 槽位 / 出发探索（继续探索）。
 */
@Composable
fun SecretRealmDetailDialog(
    realm: MapItem.SecretRealm,
    gameData: GameData?,
    viewModel: SecretRealmViewModel,
    onStart: (memberIds: List<String>) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val disciples by viewModel.disciples.collectAsStateWithLifecycle()
    val hasSession = session != null && session?.secretRealmId == realm.id
    // 继续探索模式：槽位由会话成员快照填充（只读）
    var slots by remember(hasSession, session) {
        mutableStateOf(initialSecretRealmSlots(hasSession = hasSession, session = session))
    }
    var targetSlotIndex by remember { mutableIntStateOf(-1) }
    var showDiscipleSelection by remember { mutableStateOf(false) }

    val discipleMap = disciples.associateBy { it.id }
    val occupiedCount = slots.count { it != null }
    val canStart = occupiedCount == 4 && !hasSession
    val contentData = SecretRealmContentData(
        realm = realm,
        gameData = gameData,
        hasSession = hasSession,
        slots = slots,
        discipleMap = discipleMap,
        canStart = canStart
    )
    SecretRealmDetailFrame(onDismiss = onDismiss) {
        SecretRealmTopSection(
            data = contentData,
            onAutoAppoint = {
                autoAppointTeam(
                    hasSession = hasSession,
                    slots = slots,
                    viewModel = viewModel,
                    onSlotsUpdated = { slots = it }
                )
            }
        )
        SecretRealmSlotAndActions(
            data = contentData,
            viewModel = viewModel,
            onSlotClick = { i -> targetSlotIndex = i; showDiscipleSelection = true },
            onStart = onStart,
            onContinue = onContinue
        )
    }
    if (showDiscipleSelection && targetSlotIndex in 0..3) {
        SecretRealmDiscipleSelector(
            slots = slots,
            disciples = disciples,
            gameData = gameData,
            viewModel = viewModel,
            targetSlotIndex = targetSlotIndex,
            onSlotFilled = { index, id ->
                slots = slots.toMutableList().apply { this[index] = id }
            },
            onDismiss = {
                showDiscipleSelection = false
                targetSlotIndex = -1
            }
        )
    }
}

/** 槽位初始值（SecretRealmDetailDialog 拆分）：继续探索时由会话成员快照填充，否则 4 空槽 */
private fun initialSecretRealmSlots(
    hasSession: Boolean,
    session: SecretRealmExplorationSession?
): List<String?> =
    if (hasSession) {
        val list = mutableListOf<String?>()
        session?.members?.forEach { list.add(it.discipleId) }
        while (list.size < 4) list.add(null)
        list.toList()
    } else {
        listOf(null, null, null, null)
    }

/** 一键任命（SecretRealmDetailDialog 拆分）：引擎按境界优先选出 4 人，填入空槽 */
private fun autoAppointTeam(
    hasSession: Boolean,
    slots: List<String?>,
    viewModel: SecretRealmViewModel,
    onSlotsUpdated: (List<String?>) -> Unit
) {
    if (!hasSession) {
        viewModel.autoAssignTeam { ids ->
            val assigned = slots.filterNotNull().toSet()
            val updated = slots.toMutableList()
            var idx = 0
            for (i in updated.indices) {
                if (updated[i] == null && idx < ids.size) {
                    val id = ids[idx++]
                    if (id !in assigned) updated[i] = id
                }
            }
            onSlotsUpdated(updated)
        }
    }
}

/** 秘境详情对话框骨架（SecretRealmDetailDialog 拆分） */
@Composable
private fun SecretRealmDetailFrame(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "探索远古秘境",
        mode = DialogMode.Half,
        // 小屏/矮屏设备上内容超高时可滚动，防止底部槽位与出发按钮被截断
        scrollableContent = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

/** 顶部区（SecretRealmDetailDialog 拆分）：秘境精灵图 + 描述 / 探索一队 + 一键任命 */
@Composable
private fun SecretRealmTopSection(
    data: SecretRealmContentData,
    onAutoAppoint: () -> Unit
) {
    // ===== 第一行：秘境精灵图 + 详情描述 =====
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpriteImage(
            name = "secret_realm",
            contentDescription = null,
            modifier = Modifier.size(110.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = data.realm.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = SECRET_REALM_DESCRIPTION,
                fontSize = 12.sp,
                color = Color.Black,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "出现于第 ${data.realm.spawnYear} 年，" +
                    "将于第 ${data.realm.spawnYear + GameConfig.SecretRealm.OPEN_YEARS} 年关闭",
                fontSize = 11.sp,
                color = Color(0xFF757575)
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ===== 第二行：探索一队 + 一键任命 =====
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (data.hasSession) "探索一队（探索中）" else "探索一队",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!data.hasSession) {
            GameButton(
                text = "一键任命",
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                onClick = onAutoAppoint
            )
        }
    }
}

/** 槽位与出发区（SecretRealmDetailDialog 拆分）：4 槽位 + 倒计时 + 出发探索按钮 */
@Composable
private fun SecretRealmSlotAndActions(
    data: SecretRealmContentData,
    viewModel: SecretRealmViewModel,
    onSlotClick: (Int) -> Unit,
    onStart: (List<String>) -> Unit,
    onContinue: () -> Unit
) {
    // ===== 第三行：4 个弟子槽位 =====
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (i in 0 until 4) {
            val disciple = data.slots[i]?.let { data.discipleMap[it] }
            DiscipleSlot(
                disciple = disciple,
                showActions = false,
                onSlotClick = { if (!data.hasSession) onSlotClick(i) },
                onEmptySlotClick = { if (!data.hasSession) onSlotClick(i) }
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // ===== 倒计时：距秘境关闭剩余时间（红色；秘境已关闭/已到期时隐藏） =====
    val remainingMonths = data.gameData?.let {
        GameConfig.SecretRealm.remainingMonthsUntilClose(
            it.gameYear, it.gameMonth, data.realm.spawnYear
        )
    } ?: 0
    val countdownText = GameConfig.SecretRealm.formatRemainingMonths(remainingMonths)
    if (data.gameData?.secretRealmState?.exists == true && countdownText != null) {
        Text(
            text = "距离秘境关闭$countdownText",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD32F2F)
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    // ===== 最下方：出发探索 / 继续探索 =====
    GameButton(
        text = if (data.hasSession) "继续探索" else "出发探索",
        width = ButtonSizes.StandardWidth,
        height = ButtonSizes.StandardHeight,
        enabled = if (data.hasSession) true else data.canStart,
        onClick = {
            if (data.hasSession) {
                viewModel.continueExploration { ok -> if (ok) onContinue() }
            } else {
                viewModel.startExploration(data.slots.filterNotNull()) { ok ->
                    if (ok) onStart(data.slots.filterNotNull())
                }
            }
        }
    )
}

/** 秘境队伍弟子选择（SecretRealmDetailDialog 拆分）：过滤空闲/未被占用/秘境成员 */
// 拆分搬移:参数保留原签名语义
@Suppress("UnusedParameter")
@Composable
private fun SecretRealmDiscipleSelector(
    slots: List<String?>,
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: SecretRealmViewModel,
    targetSlotIndex: Int,
    onSlotFilled: (Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    val alreadySelectedIds = slots.filterNotNull().toSet()
    val showAllEnabled = gameData?.showAllAvailableDisciples == true
    val battleAndExplorationIds = remember(gameData) {
        val battleIds = gameData?.battleTeams?.flatMap { it.slots.map { slot -> slot.discipleId } }
            ?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val explorationIds = gameData?.caveExplorationTeams?.flatMap { it.memberIds }
            ?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val secretRealmIds = gameData?.secretRealmSession?.members
            ?.map { it.discipleId }?.toSet() ?: emptySet()
        battleIds + explorationIds + secretRealmIds
    }
    DiscipleSelectorDialog(
        config = DiscipleSelectorConfig(
            title = "选择弟子",
            emptyMessage = "暂无空闲弟子",
            additionalCheck = { d ->
                d.realmLayer > 0 && d.age >= 5 && d.id !in alreadySelectedIds
            }
        ),
        disciples = disciples,
        showAllEnabled = showAllEnabled,
        battleAndExplorationIds = battleAndExplorationIds,
        onDismiss = onDismiss,
        onConfirm = { selected ->
            selected.firstOrNull()?.let { disciple ->
                if (targetSlotIndex in 0..3) {
                    onSlotFilled(targetSlotIndex, disciple.id)
                }
                onDismiss()
            }
        }
    )
}
