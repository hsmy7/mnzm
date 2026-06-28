package com.xianxia.sect.ui.game.dialogs

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.beastSpriteRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogDefaults
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.core.GameConfig

private fun resolveBeastImageRes(enemyName: String): Int? {
    val idx = GameConfig.Beast.TYPES.indexOfFirst { enemyName.endsWith(it.name) }
    return if (idx >= 0) beastSpriteRes(idx) else null
}

/**
 * 推断战斗日志的具体战斗名称。
 * PVE 被妖兽战和任务战复用，需结合 details 区分。
 */
private fun resolveBattleTypeName(log: BattleLog): String = when (log.type) {
    BattleType.SECT_WAR ->
        if (log.attackerName == "玩家队伍") "宗门战" else "宗门防守战"
    BattleType.SCOUT -> "探查战"
    BattleType.CAVE_EXPLORATION -> "洞府战"
    BattleType.PVE ->
        if (log.details.contains("任务")) "任务战" else "妖兽战"
    BattleType.PVP -> "PVP战斗"
}

@Composable
internal fun BattleLogItem(
    log: BattleLog,
    onClick: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> Color(0xFF4CAF50)
        BattleResult.LOSE -> Color(0xFFF44336)
        BattleResult.DRAW -> Color(0xFFFF9800)
    }

    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${resolveBattleTypeName(log)} · 第${log.year}年${log.month}月",
                    fontSize = 11.sp,
                    color = Color.Black
                )
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(resultColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = resultText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
internal fun BattleLogDetailDialog(
    log: BattleLog,
    onDismiss: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> Color(0xFF4CAF50)
        BattleResult.LOSE -> Color(0xFFF44336)
        BattleResult.DRAW -> Color(0xFFFF9800)
    }
    
    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }
    
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "战斗详情",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "第${log.year}年${log.month}月",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(resultColor)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = resultText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "战斗回合: ${log.turns}",
                            fontSize = 11.sp,
                            color = Color.Black
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "我方弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    itemsIndexed(log.teamMembers.chunked(4), key = { index, _ -> "team_$index" }) { index, rowMembers ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowMembers.forEach { member ->
                                BattleParticipantSlot(
                                    name = member.name,
                                    realmName = member.realmName,
                                    hp = member.hp,
                                    maxHp = member.maxHp,
                                    isAlive = member.isAlive,
                                    portraitRes = member.portraitRes
                                )
                            }
                            repeat(4 - rowMembers.size) {
                                Spacer(modifier = Modifier.width(52.dp).height(84.dp))
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when (log.type) {
                                BattleType.PVE -> "敌方妖兽"
                                BattleType.SECT_WAR, BattleType.SCOUT -> "敌方宗门弟子"
                                BattleType.CAVE_EXPLORATION -> "敌方守护兽"
                                else -> "敌方"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    itemsIndexed(log.enemies.chunked(4), key = { index, _ -> "enemy_$index" }) { index, rowEnemies ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowEnemies.forEach { enemy ->
                                val portraitRes = enemy.portraitRes.ifEmpty {
                                    val beastResId = resolveBeastImageRes(enemy.name)
                                    if (beastResId != null) "beast_$beastResId" else ""
                                }
                                BattleParticipantSlot(
                                    name = enemy.name,
                                    realmName = enemy.realmName,
                                    hp = enemy.hp,
                                    maxHp = enemy.maxHp,
                                    isAlive = enemy.isAlive,
                                    portraitRes = portraitRes
                                )
                            }
                            repeat(4 - rowEnemies.size) {
                                Spacer(modifier = Modifier.width(52.dp).height(84.dp))
                            }
                        }
                    }

                    // 战利品/被掠夺物品（敌方槽位区域下方）
                    if (log.drops.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (log.result == BattleResult.LOSE) "被掠夺物品" else "战利品",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            log.drops.forEach { drop ->
                                Text(
                                    text = "· $drop",
                                    fontSize = 11.sp,
                                    color = Color(0xFF555555)
                                )
                            }
                        }
                    }
                    
                    if (log.rounds.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "战斗过程",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        itemsIndexed(log.rounds, key = { index, round -> "round_${round.roundNumber}_$index" }) { _, round ->
                            BattleRoundItem(round = round)
                        }
                    }
                }
            }
    }
}

@Composable
internal fun BattleRoundItem(
    round: BattleLogRound
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "第${round.roundNumber}回合",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        round.actions.forEach { action ->
            BattleActionItem(action = action)
        }
    }
}

@Composable
internal fun BattleActionItem(
    action: BattleLogAction
) {
    val actionColor = when {
        action.isKill -> Color(0xFFF44336)
        action.isCrit -> Color(0xFFFF9800)
        else -> Color.Black
    }
    
    val typeIcon = when (action.type) {
        "skill" -> "✦"
        "support" -> "♡"
        else -> "⚔"
    }
    
    val typeColor = when (action.type) {
        "skill" -> Color(0xFF9C27B0)
        "support" -> Color(0xFF4CAF50)
        else -> Color.Black
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp)
    ) {
        if (action.message.isNotEmpty()) {
            Text(
                text = "$typeIcon ${action.message}",
                fontSize = 10.sp,
                color = actionColor
            )
        } else {
            val critText = if (action.isCrit) " [暴击]" else ""
            val killText = if (action.isKill) " [击杀]" else ""
            val skillText = action.skillName?.let { " [$it]" } ?: ""
            Text(
                text = "$typeIcon ${action.attacker} → ${action.target}: ${action.damage}${skillText}${critText}${killText}",
                fontSize = 10.sp,
                color = actionColor
            )
        }
    }
}

@Composable
internal fun BattleLogListDialog(
    battleLogs: List<BattleLog>,
    onDismiss: () -> Unit
) {
    var selectedBattleLog by remember { mutableStateOf<BattleLog?>(null) }
    val recentLogs = remember(battleLogs) {
        battleLogs.sortedByDescending { it.timestamp }.take(30)
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "战斗日志",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                if (recentLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无战斗记录",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentLogs, key = { it.id }) { log ->
                            BattleLogListItem(
                                log = log,
                                onClick = { selectedBattleLog = log }
                            )
                        }
                    }
                }
            }
    }

    selectedBattleLog?.let { log ->
        BattleLogDetailDialog(
            log = log,
            onDismiss = { selectedBattleLog = null }
        )
    }
}

@Composable
internal fun BattleLogListItem(
    log: BattleLog,
    onClick: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> Color(0xFF4CAF50)
        BattleResult.LOSE -> Color(0xFFF44336)
        BattleResult.DRAW -> Color(0xFFFF9800)
    }

    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }

    val typeText = resolveBattleTypeName(log)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = typeText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Text(
                        text = "第${log.year}年${log.month}月",
                        fontSize = 11.sp,
                        color = Color.Black
                    )
                }
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color.Black
                )
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(resultColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = resultText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}