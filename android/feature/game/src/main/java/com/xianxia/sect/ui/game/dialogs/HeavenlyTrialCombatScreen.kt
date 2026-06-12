package com.xianxia.sect.ui.game.dialogs

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.domain.battle.ActionType
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.launch
import kotlin.random.Random

// 复用 DiscipleComponents 中已有的妖兽立绘列表
private val beastDrawables = listOf(
    R.drawable.tiger_beast,
    R.drawable.wolf_beast,
    R.drawable.snake_beast,
    R.drawable.bear_beast,
    R.drawable.eagle_beast,
    R.drawable.fox_beast,
    R.drawable.dragon_beast,
    R.drawable.turtle_beast
)

enum class BattlePhase { PLAYER_TURN, ENEMY_TURN, WON, LOST }

@Composable
fun HeavenlyTrialCombatScreen(
    viewModel: HeavenlyTrialViewModel,
    onFinished: (won: Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(BattlePhase.PLAYER_TURN) }
    var currentPlayerIdx by remember { mutableStateOf(0) }
    var selectedTargetId by remember { mutableStateOf<String?>(null) }
    var selectedIsAlly by remember { mutableStateOf(false) }
    var playerTeam by remember { mutableStateOf(viewModel.playerCombatants) }
    var enemyTeam by remember { mutableStateOf(viewModel.enemyCombatants) }
    var isDefending by remember { mutableStateOf(mutableSetOf<String>()) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val battleStartTime = remember { System.currentTimeMillis() }

    val alivePlayers = playerTeam.filter { !it.isDead }
    val aliveEnemies = enemyTeam.filter { !it.isDead }
    val currentCombatant = alivePlayers.getOrNull(currentPlayerIdx)

    LaunchedEffect(playerTeam, enemyTeam) {
        if (playerTeam.all { it.isDead }) { phase = BattlePhase.LOST }
        else if (enemyTeam.all { it.isDead }) { phase = BattlePhase.WON }
    }

    LaunchedEffect(phase) {
        if (phase == BattlePhase.ENEMY_TURN) {
            kotlinx.coroutines.delay(600L)
            val sortedEnemies = enemyTeam.filter { !it.isDead }.sortedByDescending { it.speed }
            for (enemy in sortedEnemies) {
                if (playerTeam.all { it.isDead }) break
                val action = viewModel.trialService.executeEnemyAction(
                    attacker = enemy,
                    playerTeam = playerTeam,
                    allyTeam = enemyTeam.filter { it.id != enemy.id }
                )
                val skill = action.skill
                val target = action.target
                when (action.actionType) {
                    ActionType.NONE -> { /* 被控跳过 */ }
                    ActionType.ATTACK -> {
                        if (skill != null) {
                            if (skill.isAoe) {
                                // AOE: 攻击所有玩家
                                playerTeam = playerTeam.map { p ->
                                    if (!p.isDead) applySkillDamage(enemy, p, skill, isDefending.contains(p.id)) else p
                                }
                            } else if (target != null) {
                                val updated = applySkillDamage(enemy, target, skill, isDefending.contains(target.id))
                                playerTeam = playerTeam.map { if (it.id == target.id) updated else it }
                            }
                        }
                    }
                    ActionType.NORMAL_ATTACK -> {
                        if (target != null) {
                            val updated = applyNormalAttack(enemy, target, isDefending.contains(target.id))
                            playerTeam = playerTeam.map { if (it.id == target.id) updated else it }
                        }
                    }
                    ActionType.BUFF_ALLY -> {
                        if (skill != null && target != null) {
                            val buffed = applyBuffToTarget(target, skill)
                            enemyTeam = enemyTeam.map { if (it.id == target.id) buffed else it }
                        }
                    }
                    ActionType.BUFF_SELF -> {
                        if (skill != null) {
                            val buffed = applyBuffToTarget(enemy, skill)
                            enemyTeam = enemyTeam.map { if (it.id == enemy.id) buffed else it }
                        }
                    }
                }
                kotlinx.coroutines.delay(800L)
            }
            isDefending = mutableSetOf()
            currentPlayerIdx = 0
            if (playerTeam.any { !it.isDead }) { phase = BattlePhase.PLAYER_TURN }
        }
    }

    LaunchedEffect(phase) {
        if (phase == BattlePhase.WON || phase == BattlePhase.LOST) {
            val durationSeconds = (System.currentTimeMillis() - battleStartTime) / 1000
            viewModel.showBattleResult(phase == BattlePhase.WON, durationSeconds)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景
        Image(
            painter = painterResource(R.drawable.heavenly_trial_battle_scene),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )

        // 6×6 网格叠加
        Canvas(modifier = Modifier.matchParentSize()) {
            val colWidth = size.width / 6
            val rowHeight = size.height / 6
            val gridColor = Color.Gray.copy(alpha = 0.3f)
            for (i in 1 until 6) {
                drawLine(gridColor, Offset(i * colWidth, 0f), Offset(i * colWidth, size.height), strokeWidth = 1f)
            }
            for (i in 1 until 6) {
                drawLine(gridColor, Offset(0f, i * rowHeight), Offset(size.width, i * rowHeight), strokeWidth = 1f)
            }
        }

        // 6×6 战斗网格（36格）
        // 单位布局: 己方 col=1(第二列), 敌方 col=4(第五列), rows=1-3
        val gridPositions = remember {
            val map = mutableMapOf<String, Pair<Int, Int>>()
            for (i in 0 until 3) {
                playerTeam.getOrNull(i)?.let { map[it.id] = Pair(1, i + 1) }
                enemyTeam.getOrNull(i)?.let { map[it.id] = Pair(4, i + 1) }
            }
            map
        }
        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until 6) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until 6) {
                        val cellCombatant = gridPositions.entries
                            .firstOrNull { it.value == Pair(col, row) }
                            ?.let { entry ->
                                (playerTeam + enemyTeam).find { it.id == entry.key }
                            }
                        val isPlayer = cellCombatant?.let { playerTeam.any { p -> p.id == it.id } } == true
                        val isCurrent = cellCombatant != null &&
                            currentCombatant?.id == cellCombatant.id &&
                            phase == BattlePhase.PLAYER_TURN
                        val allySelected = selectedTargetId != null && isPlayer && selectedIsAlly && selectedTargetId == cellCombatant?.id
                        val enemySelected = selectedTargetId != null && !isPlayer && !selectedIsAlly && selectedTargetId == cellCombatant?.id

                        CombatUnitCell(
                            combatant = cellCombatant,
                            isCurrent = isCurrent,
                            isAllySelected = allySelected,
                            isEnemySelected = enemySelected,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                if (phase == BattlePhase.PLAYER_TURN && cellCombatant != null && !cellCombatant.isDead) {
                                    if (isPlayer) {
                                        if (allySelected) {
                                            selectedTargetId = null; selectedIsAlly = false
                                        } else {
                                            selectedTargetId = cellCombatant.id; selectedIsAlly = true
                                        }
                                    } else {
                                        if (enemySelected) {
                                            selectedTargetId = null; selectedIsAlly = false
                                        } else {
                                            selectedTargetId = cellCombatant.id; selectedIsAlly = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // 右上角关闭按钮（必须在网格之后，确保 z-order 在最上层）
        CloseButton(
            onClick = { showExitConfirm = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
        )

        // 战斗栏（左右留空隙）
        if (phase == BattlePhase.PLAYER_TURN && currentCombatant != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 8.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.heavenly_trial_battle_bar),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${currentCombatant.name}  HP:${currentCombatant.hp}/${currentCombatant.maxHp}  MP:${currentCombatant.mp}/${currentCombatant.maxMp}",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        currentCombatant.skills.forEach { skill ->
                            val canUse = currentCombatant.mp >= skill.mpCost && skill.currentCooldown <= 0
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, if (canUse) GameColors.Gold else GameColors.Border, CircleShape)
                                    .background(Color.White.copy(alpha = if (canUse) 1f else 0.5f))
                                    .clickable(enabled = canUse && phase == BattlePhase.PLAYER_TURN) {
                                        coroutineScope.launch {
                                            val result = executePlayerSkill(
                                                currentCombatant, skill, selectedTargetId, selectedIsAlly,
                                                playerTeam, enemyTeam, isDefending
                                            )
                                            playerTeam = result.first; enemyTeam = result.second
                                            selectedTargetId = null; selectedIsAlly = false
                                            advanceTurn(alivePlayers, aliveEnemies, currentPlayerIdx, isDefending) { ni, np, nd ->
                                                currentPlayerIdx = ni; phase = np; isDefending = nd
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(skill.name.take(2), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    Text("${skill.mpCost}灵力", fontSize = 6.sp, color = Color.Black)
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // 防御（左侧，向内 5dp）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(start = 15.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).clickable {
                                    isDefending = isDefending.toMutableSet().apply { add(currentCombatant.id) }
                                    advanceTurn(alivePlayers, aliveEnemies, currentPlayerIdx, isDefending) { ni, np, nd ->
                                        currentPlayerIdx = ni; phase = np; isDefending = nd
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(painterResource(R.drawable.heavenly_trial_defend), "防御",
                                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            }
                            Text("防御", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        // 普攻（右侧，向内 5dp）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 15.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).clickable {
                                    coroutineScope.launch {
                                        val target = if (!selectedIsAlly && selectedTargetId != null)
                                            enemyTeam.find { it.id == selectedTargetId }
                                        else enemyTeam.filter { !it.isDead }.randomOrNull()
                                        if (target != null) {
                                            val updated = applyNormalAttack(currentCombatant, target, isDefending.contains(target.id))
                                            enemyTeam = enemyTeam.map { if (it.id == target.id) updated else it }
                                        }
                                        selectedTargetId = null; selectedIsAlly = false
                                        advanceTurn(alivePlayers, aliveEnemies, currentPlayerIdx, isDefending) { ni, np, nd ->
                                            currentPlayerIdx = ni; phase = np; isDefending = nd
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(painterResource(R.drawable.heavenly_trial_atk_normal), "普攻",
                                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            }
                            Text("普攻", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 战斗结算
        if (viewModel.showResult) {
            HeavenlyTrialBattleResultDialog(
                won = viewModel.resultWon,
                durationSeconds = viewModel.resultDuration,
                onDismiss = {
                    viewModel.dismissResult()
                    onFinished(viewModel.resultWon)
                }
            )
        }
    }

    // 退出确认提示框
    if (showExitConfirm) {
        StandardPromptDialog(
            onDismissRequest = { showExitConfirm = false },
            title = "退出战斗",
            text = "确定要退出战斗吗？退出将视为战斗失败。",
            confirmLabel = "确定退出",
            onConfirm = {
                showExitConfirm = false
                phase = BattlePhase.LOST
            },
            dismissLabel = "取消",
            onDismiss = { showExitConfirm = false }
        )
    }
}

@Composable
private fun CombatUnitCell(
    combatant: Combatant?,
    isCurrent: Boolean = false,
    isAllySelected: Boolean = false,
    isEnemySelected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = when {
        isCurrent -> GameColors.Gold.copy(alpha = 0.3f)
        isAllySelected -> Color.Green.copy(alpha = 0.3f)
        isEnemySelected -> Color.Red.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier.background(bgColor).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (combatant != null && !combatant.isDead) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 气血文字 / 晕眩状态
                if (combatant.hasControlEffect) {
                    Text("晕眩", fontSize = 9.sp, color = Color.Red)
                } else {
                    Text(
                        "${combatant.hp}/${combatant.maxHp}",
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(2.dp))

                // 血条（宽度与图标一致）
                val hpPercent = (combatant.hp.toFloat() / combatant.maxHp).coerceIn(0f, 1f)
                val barColor = when {
                    hpPercent > 0.5f -> Color(0xFF4CAF50)
                    hpPercent > 0.25f -> Color(0xFFFFEB3B)
                    else -> Color(0xFFF44336)
                }
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.DarkGray)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = hpPercent)
                            .background(barColor, RoundedCornerShape(2.dp))
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 肖像图标
                CombatantPortrait(combatant = combatant, size = 44)
            }
        }
    }
}

@Composable
private fun CombatantPortrait(combatant: Combatant, size: Int = 44) {
    val context = LocalContext.current
    val portraitResId = remember(combatant.id, combatant.portraitRes, combatant.isBeast) {
        when {
            combatant.isBeast -> {
                val index = combatant.portraitRes.removePrefix("beast_").toIntOrNull() ?: 0
                beastDrawables.getOrNull(index) ?: R.drawable.tiger_beast
            }
            combatant.portraitRes.isNotBlank() -> {
                PortraitPool.getResourceId(context, combatant.portraitRes).takeIf { it != 0 }
                    ?: R.drawable.disciple_portrait
            }
            else -> {
                // 试炼弟子无 portrait，随机分配一个弟子肖像
                val randomPortrait = PortraitPool.getRandomPortrait(
                    if (Random.nextBoolean()) "male" else "female"
                )
                PortraitPool.getResourceId(context, randomPortrait).takeIf { it != 0 }
                    ?: R.drawable.disciple_portrait
            }
        }
    }

    val sizeDp = size.dp
    if (combatant.isBeast) {
        // 妖兽无图标框
        Image(
            painter = painterResource(id = portraitResId),
            contentDescription = null,
            modifier = Modifier.size(sizeDp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape)
                .border(2.dp, Color.Gray, CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = portraitResId),
                contentDescription = null,
                modifier = Modifier.size((size - 4).dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// region Battle Logic (unchanged)

private fun applyNormalAttack(attacker: Combatant, defender: Combatant, isDefending: Boolean): Combatant {
    val baseAtk = attacker.effectivePhysicalAttack
    val baseDef = defender.effectivePhysicalDefense
    val rawDmg = baseAtk * 1.0 * (1.0 - baseDef / (baseDef + 500.0))
    val variance = 0.9 + Random.nextDouble() * 0.2
    val dmg = (rawDmg * variance).toInt().coerceAtLeast(1)
    val finalDmg = if (isDefending) (dmg * 0.75).toInt() else dmg
    return defender.copy(hp = (defender.hp - finalDmg).coerceAtLeast(0))
}

private fun applySkillDamage(attacker: Combatant, defender: Combatant, skill: com.xianxia.sect.core.model.CombatSkill, isDefending: Boolean): Combatant {
    val baseAtk = if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
    val baseDef = if (skill.damageType == com.xianxia.sect.core.DamageType.PHYSICAL) defender.effectivePhysicalDefense else defender.effectiveMagicDefense
    val rawDmg = baseAtk * skill.damageMultiplier * (1.0 - baseDef / (baseDef + 500.0))
    val variance = 0.9 + Random.nextDouble() * 0.2
    val dmg = (rawDmg * variance).toInt().coerceAtLeast(1)
    val finalDmg = if (isDefending) (dmg * 0.75).toInt() else dmg
    return defender.copy(hp = (defender.hp - finalDmg).coerceAtLeast(0))
}

private fun executePlayerSkill(
    attacker: Combatant,
    skill: com.xianxia.sect.core.model.CombatSkill,
    selectedTargetId: String?,
    selectedIsAlly: Boolean,
    playerTeam: List<Combatant>,
    enemyTeam: List<Combatant>,
    isDefending: Set<String>
): Pair<List<Combatant>, List<Combatant>> {
    var updatedPlayers = playerTeam.toMutableList()
    var updatedEnemies = enemyTeam.toMutableList()

    val attackerIdx = updatedPlayers.indexOfFirst { it.id == attacker.id }
    if (attackerIdx >= 0) {
        updatedPlayers[attackerIdx] = updatedPlayers[attackerIdx].copy(
            mp = (updatedPlayers[attackerIdx].mp - skill.mpCost).coerceAtLeast(0)
        )
    }

    val isAttackSkill = skill.skillType == com.xianxia.sect.core.SkillType.ATTACK || skill.damageMultiplier > 0

    if (skill.isAoe) {
        if (isAttackSkill) {
            updatedEnemies = updatedEnemies.map { e ->
                if (!e.isDead) applySkillDamage(attacker, e, skill, isDefending.contains(e.id)) else e
            }.toMutableList()
        } else {
            updatedPlayers = updatedPlayers.map { a ->
                if (!a.isDead) applyBuffToTarget(a, skill) else a
            }.toMutableList()
        }
    } else {
        if (isAttackSkill) {
            val target = if (!selectedIsAlly && selectedTargetId != null)
                updatedEnemies.find { it.id == selectedTargetId }
            else updatedEnemies.filter { !it.isDead }.randomOrNull()
            if (target != null) {
                val updated = applySkillDamage(attacker, target, skill, false)
                updatedEnemies = updatedEnemies.map { if (it.id == target.id) updated else it }.toMutableList()
            }
        } else {
            if (skill.targetScope == "self") {
                val updated = applyBuffToTarget(attacker, skill)
                if (attackerIdx >= 0) updatedPlayers[attackerIdx] = updated
            } else {
                val target = if (selectedIsAlly && selectedTargetId != null)
                    updatedPlayers.find { it.id == selectedTargetId }
                else updatedPlayers.filter { !it.isDead }.randomOrNull()
                if (target != null) {
                    val updated = applyBuffToTarget(target, skill)
                    updatedPlayers = updatedPlayers.map { if (it.id == target.id) updated else it }.toMutableList()
                }
            }
        }
    }

    return updatedPlayers.toList() to updatedEnemies.toList()
}

private fun applyBuffToTarget(target: Combatant, skill: com.xianxia.sect.core.model.CombatSkill): Combatant {
    var newHp = target.hp; var newMp = target.mp
    if (skill.healPercent > 0) {
        if (skill.healType == com.xianxia.sect.core.HealType.HP)
            newHp = (target.hp + (target.maxHp * skill.healPercent).toInt()).coerceAtMost(target.maxHp)
        else newMp = (target.mp + (target.maxMp * skill.healPercent).toInt()).coerceAtMost(target.maxMp)
    }
    val newBuffs = skill.buffType?.let { bt ->
        target.buffs + com.xianxia.sect.core.engine.domain.battle.CombatBuff(bt, skill.buffValue, skill.buffDuration)
    } ?: target.buffs
    return target.copy(hp = newHp, mp = newMp, buffs = newBuffs)
}

private fun advanceTurn(
    alivePlayers: List<Combatant>, aliveEnemies: List<Combatant>,
    currentIdx: Int, isDefending: MutableSet<String>,
    onResult: (Int, BattlePhase, MutableSet<String>) -> Unit
) {
    if (aliveEnemies.all { it.isDead }) { onResult(currentIdx, BattlePhase.WON, isDefending); return }
    if (alivePlayers.all { it.isDead }) { onResult(currentIdx, BattlePhase.LOST, isDefending); return }
    val nextIdx = currentIdx + 1
    if (nextIdx >= alivePlayers.size) onResult(0, BattlePhase.ENEMY_TURN, isDefending)
    else onResult(nextIdx, BattlePhase.PLAYER_TURN, isDefending)
}

// endregion
