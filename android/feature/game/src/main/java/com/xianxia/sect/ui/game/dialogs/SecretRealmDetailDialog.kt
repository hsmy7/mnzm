package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.game.filterByDiscipleStatus
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.theme.ButtonSizes

/** 远古秘境详情描述文案 */
private const val SECRET_REALM_DESCRIPTION =
    "上古大能陨落之地，藏有无数机缘与凶险。每逢天地灵气波动之际现世，五十年一遇。"

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
        mutableStateOf(
            if (hasSession) {
                val list = mutableListOf<String?>()
                session?.members?.forEach { list.add(it.discipleId) }
                while (list.size < 4) list.add(null)
                list.toList()
            } else {
                listOf(null, null, null, null)
            }
        )
    }
    var targetSlotIndex by remember { mutableIntStateOf(-1) }
    var showDiscipleSelection by remember { mutableStateOf(false) }

    val discipleMap = disciples.associateBy { it.id }
    val occupiedCount = slots.count { it != null }
    val canStart = occupiedCount == 4 && !hasSession

    // 一键任命：引擎按境界优先选出 4 人，填入空槽
    val autoAppoint: () -> Unit = {
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
                slots = updated
            }
        }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "探索远古秘境",
        mode = DialogMode.Half,
        // 小屏/矮屏设备上内容超高时可滚动，防止底部槽位与出发按钮被截断
        scrollableContent = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
                        text = realm.name,
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
                        text = "出现于第 ${realm.spawnYear} 年",
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
                    text = if (hasSession) "探索一队（探索中）" else "探索一队",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!hasSession) {
                    GameButton(
                        text = "一键任命",
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        onClick = autoAppoint
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 第三行：4 个弟子槽位 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (i in 0 until 4) {
                    val disciple = slots[i]?.let { discipleMap[it] }
                    DiscipleSlot(
                        disciple = disciple,
                        showActions = false,
                        onSlotClick = {
                            if (!hasSession) {
                                targetSlotIndex = i
                                showDiscipleSelection = true
                            }
                        },
                        onEmptySlotClick = {
                            if (!hasSession) {
                                targetSlotIndex = i
                                showDiscipleSelection = true
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== 最下方：出发探索 / 继续探索 =====
            GameButton(
                text = if (hasSession) "继续探索" else "出发探索",
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                enabled = if (hasSession) true else canStart,
                onClick = {
                    if (hasSession) {
                        viewModel.continueExploration { ok -> if (ok) onContinue() }
                    } else {
                        viewModel.startExploration(slots.filterNotNull()) { ok ->
                            if (ok) onStart(slots.filterNotNull())
                        }
                    }
                }
            )
        }
    }

    // ===== 弟子选择弹窗 =====
    if (showDiscipleSelection && targetSlotIndex in 0..3) {
        SecretRealmDiscipleSelectionDialog(
            disciples = disciples,
            gameData = gameData,
            alreadySelectedIds = slots.filterNotNull().toSet(),
            onSelect = { id ->
                if (targetSlotIndex in 0..3) {
                    slots = slots.toMutableList().apply { this[targetSlotIndex] = id }
                }
                showDiscipleSelection = false
                targetSlotIndex = -1
            },
            onDismiss = {
                showDiscipleSelection = false
                targetSlotIndex = -1
            }
        )
    }
}

/** 秘境队伍弟子选择（过滤空闲/未被占用/秘境成员） */
@Composable
private fun SecretRealmDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    alreadySelectedIds: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
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

    val idleDisciples = remember(disciples, alreadySelectedIds, showAllEnabled, battleAndExplorationIds) {
        disciples.filterByDiscipleStatus(showAllEnabled, battleAndExplorationIds) { d ->
            d.realmLayer > 0 && d.age >= 5 && d.id !in alreadySelectedIds
        }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择弟子",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        if (idleDisciples.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "暂无空闲弟子", fontSize = 12.sp, color = Color.Black)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(idleDisciples, key = { it.id }) { disciple ->
                    PortraitDiscipleCard(
                        disciple = disciple,
                        onClick = { onSelect(disciple.id) }
                    )
                }
            }
        }
    }
}
