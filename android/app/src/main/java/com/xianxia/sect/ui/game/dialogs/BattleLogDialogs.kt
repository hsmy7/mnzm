package com.xianxia.sect.ui.game.dialogs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.ui.theme.GameColors

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
                    text = "第${log.year}年${log.month}月",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
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
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "战斗详情",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                
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
                                color = Color(0xFF666666)
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
                            color = Color(0xFF333333)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "我方弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    items(log.teamMembers.chunked(4)) { rowMembers ->
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
                                    isAlive = member.isAlive
                                )
                            }
                            repeat(4 - rowMembers.size) {
                                Spacer(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "敌方妖兽",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    items(log.enemies.chunked(4)) { rowEnemies ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowEnemies.forEach { enemy ->
                                BattleParticipantSlot(
                                    name = enemy.name,
                                    realmName = enemy.realmName,
                                    hp = enemy.hp,
                                    maxHp = enemy.maxHp,
                                    isAlive = enemy.isAlive
                                )
                            }
                            repeat(4 - rowEnemies.size) {
                                Spacer(modifier = Modifier.size(40.dp))
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
                                color = Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        items(log.rounds) { round ->
                            BattleRoundItem(round = round)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BattleParticipantSlot(
    name: String,
    realmName: String,
    hp: Int,
    maxHp: Int,
    isAlive: Boolean
) {
    val hpPercent = maxHp.takeIf { it > 0 }?.let {
        (hp.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    val hpColor = when {
        hpPercent > 0.6f -> Color(0xFF4CAF50)
        hpPercent > 0.3f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(hpPercent)
                    .background(hpColor)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isAlive) Color.White else Color(0xFFEEEEEE))
                .border(1.dp, if (isAlive) Color(0xFFE0E0E0) else Color(0xFFCCCCCC), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isAlive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = name,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = realmName,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "死亡",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336),
                    maxLines = 1
                )
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
            color = Color(0xFF333333)
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
        else -> Color(0xFF666666)
    }
    
    val typeIcon = when (action.type) {
        "skill" -> "✦"
        "support" -> "♡"
        else -> "⚔"
    }
    
    val typeColor = when (action.type) {
        "skill" -> Color(0xFF9C27B0)
        "support" -> Color(0xFF4CAF50)
        else -> Color(0xFF666666)
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "战斗日志",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }

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
                            color = Color(0xFF999999)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentLogs) { log ->
                            BattleLogListItem(
                                log = log,
                                onClick = { selectedBattleLog = log }
                            )
                        }
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

    val typeText = log.type.displayName

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
                        color = Color(0xFF333333)
                    )
                    Text(
                        text = "第${log.year}年${log.month}月",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                }
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
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