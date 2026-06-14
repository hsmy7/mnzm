package com.xianxia.sect.ui.game.dialogs

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.engine.domain.battle.ActionType
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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

// region Animation Data Classes

private data class AttackAnimationEvent(
    val attackerId: String,
    val targetId: String,
    val damage: Int,
    val isCrit: Boolean,
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val skillName: String? = null,
    val isKill: Boolean = false
)

private data class DamageNumberState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val damage: Int,
    val isCrit: Boolean,
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val targetId: String
)

private enum class AnimPhase {
    IDLE, MOVE_TO_TARGET, IMPACT, RETURN_TO_START
}

private data class AttackAnimState(
    val attackerId: String? = null,
    val targetId: String? = null,
    val phase: AnimPhase = AnimPhase.IDLE
)

// endregion

private suspend fun playAttackSequence(
    event: AttackAnimationEvent,
    cellPositions: Map<String, Offset>,
    currentAnimState: () -> AttackAnimState,
    setAnimState: (AttackAnimState) -> Unit,
    setShaking: (String?) -> Unit,
    addDamageNumber: (DamageNumberState) -> Unit,
    applyResult: (AttackAnimationEvent) -> Unit
) {
    val aPos = cellPositions[event.attackerId]
    val tPos = cellPositions[event.targetId]

    if (aPos != null && tPos != null && !event.isHeal) {
        // 阶段 1: 攻击者移动至目标
        setAnimState(AttackAnimState(
            attackerId = event.attackerId,
            targetId = event.targetId,
            phase = AnimPhase.MOVE_TO_TARGET
        ))
        delay(300)

        // 阶段 2: 命中 + 抖动 + 伤害数字
        setAnimState(currentAnimState().copy(
            phase = AnimPhase.IMPACT
        ))
        setShaking(event.targetId)

        addDamageNumber(DamageNumberState(
            damage = event.damage,
            isCrit = event.isCrit,
            isPhysical = event.isPhysical,
            isHeal = event.isHeal,
            targetId = event.targetId
        ))

        delay(250)
        setShaking(null)

        // 阶段 3: 返回原位
        setAnimState(currentAnimState().copy(
            phase = AnimPhase.RETURN_TO_START
        ))
        delay(300)

        // 阶段 4: 应用伤害
        applyResult(event)
        setAnimState(AttackAnimState())
    } else if (event.isHeal) {
        // Buff/治疗: 无位移，仅绿色数字
        addDamageNumber(DamageNumberState(
            damage = event.damage,
            isCrit = false,
            isPhysical = false,
            isHeal = true,
            targetId = event.targetId
        ))
        delay(300)
        applyResult(event)
        setAnimState(AttackAnimState())
    } else {
        // 位置缺失时直接应用伤害
        applyResult(event)
        delay(100)
    }
}

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

    // Animation state
    var isAnimating by remember { mutableStateOf(false) }
    var currentAnimState by remember { mutableStateOf(AttackAnimState()) }
    var shakingTargetId by remember { mutableStateOf<String?>(null) }
    var activeDamageNumbers by remember {
        mutableStateOf<List<DamageNumberState>>(emptyList())
    }
    val cellPositions = remember { mutableStateMapOf<String, Offset>() }

    val alivePlayers = playerTeam.filter { !it.isDead }
    val aliveEnemies = enemyTeam.filter { !it.isDead }
    val currentCombatant = alivePlayers.getOrNull(currentPlayerIdx)

    // Animation helpers defined inside composable for state capture

    fun applyAnimationResult(event: AttackAnimationEvent) {
        val isTargetPlayer = playerTeam.any { it.id == event.targetId }
        if (event.isHeal) {
            if (isTargetPlayer) {
                playerTeam = playerTeam.map { c ->
                    if (c.id == event.targetId) c.copy(
                        hp = (c.hp + event.damage).coerceAtMost(c.maxHp)
                    ) else c
                }
            } else {
                enemyTeam = enemyTeam.map { c ->
                    if (c.id == event.targetId) c.copy(
                        hp = (c.hp + event.damage).coerceAtMost(c.maxHp)
                    ) else c
                }
            }
        } else {
            if (isTargetPlayer) {
                playerTeam = playerTeam.map { c ->
                    if (c.id == event.targetId) c.copy(
                        hp = (c.hp - event.damage).coerceAtLeast(0)
                    ) else c
                }
            } else {
                enemyTeam = enemyTeam.map { c ->
                    if (c.id == event.targetId) c.copy(
                        hp = (c.hp - event.damage).coerceAtLeast(0)
                    ) else c
                }
            }
        }
    }

    LaunchedEffect(playerTeam, enemyTeam) {
        if (playerTeam.all { it.isDead }) { phase = BattlePhase.LOST }
        else if (enemyTeam.all { it.isDead }) { phase = BattlePhase.WON }
    }

    LaunchedEffect(phase) {
        if (phase == BattlePhase.ENEMY_TURN && !isAnimating) {
            isAnimating = true
            delay(600L)

            // 预计算所有敌人行动 → 动画事件列表
            val sortedEnemies = enemyTeam.filter { !it.isDead }
                .sortedByDescending { it.speed }
            val events = mutableListOf<AttackAnimationEvent>()

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
                                // AOE: 逐个目标生成事件
                                for (p in playerTeam.filter { !it.isDead }) {
                                    val dmg = computeSkillDamage(
                                        enemy, p, skill,
                                        isDefending.contains(p.id)
                                    )
                                    val isCrit = Random.nextDouble() < enemy.critRate
                                    events.add(AttackAnimationEvent(
                                        attackerId = enemy.id,
                                        targetId = p.id,
                                        damage = dmg,
                                        isCrit = isCrit,
                                        isPhysical = skill.damageType ==
                                            DamageType.PHYSICAL,
                                        skillName = skill.name,
                                        isKill = p.hp - dmg <= 0
                                    ))
                                }
                            } else if (target != null) {
                                val dmg = computeSkillDamage(
                                    enemy, target, skill,
                                    isDefending.contains(target.id)
                                )
                                val isCrit = Random.nextDouble() < enemy.critRate
                                events.add(AttackAnimationEvent(
                                    attackerId = enemy.id,
                                    targetId = target.id,
                                    damage = dmg,
                                    isCrit = isCrit,
                                    isPhysical = skill.damageType ==
                                        DamageType.PHYSICAL,
                                    skillName = skill.name,
                                    isKill = target.hp - dmg <= 0
                                ))
                            }
                        }
                    }
                    ActionType.NORMAL_ATTACK -> {
                        if (target != null) {
                            val dmg = computeNormalAttackDamage(
                                enemy, target,
                                isDefending.contains(target.id)
                            )
                            events.add(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = target.id,
                                damage = dmg,
                                isCrit = false,
                                isPhysical = true,
                                isKill = target.hp - dmg <= 0
                            ))
                        }
                    }
                    ActionType.BUFF_ALLY -> {
                        if (skill != null && target != null) {
                            events.add(AttackAnimationEvent(
                                attackerId = target.id,
                                targetId = target.id,
                                damage = (target.maxHp *
                                    skill.healPercent).toInt(),
                                isCrit = false,
                                isPhysical = false,
                                isHeal = true,
                                skillName = skill.name
                            ))
                            // Buff 效果立即应用
                            val buffed = applyBuffToTarget(target, skill)
                            enemyTeam = enemyTeam.map {
                                if (it.id == target.id) buffed else it
                            }
                        }
                    }
                    ActionType.BUFF_SELF -> {
                        if (skill != null) {
                            events.add(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = enemy.id,
                                damage = (enemy.maxHp *
                                    skill.healPercent).toInt(),
                                isCrit = false,
                                isPhysical = false,
                                isHeal = true,
                                skillName = skill.name
                            ))
                            val buffed = applyBuffToTarget(enemy, skill)
                            enemyTeam = enemyTeam.map {
                                if (it.id == enemy.id) buffed else it
                            }
                        }
                    }
                }
            }

            // 依次播放攻击动画
            for (event in events) {
                playAttackSequence(event,
                    cellPositions = cellPositions,
                    currentAnimState = { currentAnimState },
                    setAnimState = { currentAnimState = it },
                    setShaking = { shakingTargetId = it },
                    addDamageNumber = { activeDamageNumbers =
                        activeDamageNumbers + it },
                    applyResult = { e -> applyAnimationResult(e) }
                )
            }

            isDefending = mutableSetOf()
            currentPlayerIdx = 0
            isAnimating = false
            if (playerTeam.any { !it.isDead }) {
                phase = BattlePhase.PLAYER_TURN
            }
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
                            isShaking = cellCombatant != null &&
                                shakingTargetId == cellCombatant.id,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (cellCombatant != null)
                                        Modifier.onGloballyPositioned { coords ->
                                            cellPositions[cellCombatant.id] =
                                                coords.positionInWindow()
                                        }
                                    else Modifier
                                ),
                            onClick = {
                                if (phase == BattlePhase.PLAYER_TURN &&
                                    cellCombatant != null &&
                                    !cellCombatant.isDead &&
                                    !isAnimating
                                ) {
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

        // 动画覆盖层（攻击飞行 + 伤害数字）
        if (activeDamageNumbers.isNotEmpty() ||
            currentAnimState.phase != AnimPhase.IDLE
        ) {
            Box(modifier = Modifier.matchParentSize().zIndex(5f)) {
                // 攻击者飞行精灵
                val animState = currentAnimState
                if (animState.phase != AnimPhase.IDLE &&
                    animState.attackerId != null &&
                    animState.targetId != null
                ) {
                    val aPos = cellPositions[animState.attackerId]
                    val tPos = cellPositions[animState.targetId]
                    if (aPos != null && tPos != null) {
                        val attacker = (playerTeam + enemyTeam)
                            .find { it.id == animState.attackerId }
                        if (attacker != null) {
                            val targetPos = when (animState.phase) {
                                AnimPhase.MOVE_TO_TARGET, AnimPhase.IMPACT ->
                                    tPos
                                else -> aPos
                            }
                            val startPos = when (animState.phase) {
                                AnimPhase.MOVE_TO_TARGET -> aPos
                                AnimPhase.IMPACT -> tPos
                                AnimPhase.RETURN_TO_START -> tPos
                                else -> aPos
                            }
                            AttackerFlightOverlay(
                                attacker = attacker,
                                startX = startPos.x,
                                startY = startPos.y,
                                endX = targetPos.x,
                                endY = targetPos.y,
                                phase = animState.phase
                            )
                        }
                    }
                }

                // 浮动伤害数字
                activeDamageNumbers.forEach { dn ->
                    val pos = cellPositions[dn.targetId]
                    if (pos != null) {
                        key(dn.id) {
                            FloatingDamageNumber(
                                damage = dn.damage,
                                isCrit = dn.isCrit,
                                isPhysical = dn.isPhysical,
                                isHeal = dn.isHeal,
                                screenX = pos.x + 20f,
                                screenY = pos.y - 10f,
                                onFadeComplete = {
                                    activeDamageNumbers =
                                        activeDamageNumbers.filter {
                                            it.id != dn.id
                                        }
                                }
                            )
                        }
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
                                    .clickable(enabled = canUse &&
                                        phase == BattlePhase.PLAYER_TURN &&
                                        !isAnimating
                                    ) {
                                        coroutineScope.launch {
                                            isAnimating = true
                                            // 先扣 MP
                                            val attackerIdx = playerTeam
                                                .indexOfFirst {
                                                    it.id == currentCombatant.id
                                                }
                                            if (attackerIdx >= 0) {
                                                playerTeam = playerTeam.mapIndexed { i, c ->
                                                    if (i == attackerIdx) c.copy(
                                                        mp = (c.mp - skill.mpCost)
                                                            .coerceAtLeast(0)
                                                    ) else c
                                                }
                                            }
                                            val isAttackSkill = skill.skillType ==
                                                com.xianxia.sect.core.SkillType.ATTACK ||
                                                skill.damageMultiplier > 0
                                            if (skill.isAoe) {
                                                if (isAttackSkill) {
                                                    val targets = enemyTeam
                                                        .filter { !it.isDead }
                                                    for (t in targets) {
                                                        val dmg = computeSkillDamage(
                                                            currentCombatant, t,
                                                            skill, false
                                                        )
                                                        val isCrit = Random.nextDouble() <
                                                            currentCombatant.critRate
                                                        playAttackSequence(
                                                            AttackAnimationEvent(
                                                                attackerId = currentCombatant.id,
                                                                targetId = t.id,
                                                                damage = dmg,
                                                                isCrit = isCrit,
                                                                isPhysical = skill.damageType ==
                                                                    DamageType.PHYSICAL,
                                                                skillName = skill.name,
                                                                isKill = t.hp - dmg <= 0
                                                            ),
                                                            cellPositions,
                                                            { currentAnimState },
                                                            { currentAnimState = it },
                                                            { shakingTargetId = it },
                                                            { activeDamageNumbers =
                                                                activeDamageNumbers + it },
                                                            { e -> applyAnimationResult(e) }
                                                        )
                                                    }
                                                }
                                            } else {
                                                if (isAttackSkill) {
                                                    val target = if (
                                                        !selectedIsAlly &&
                                                        selectedTargetId != null
                                                    )
                                                        enemyTeam.find {
                                                            it.id == selectedTargetId
                                                        }
                                                    else enemyTeam
                                                        .filter { !it.isDead }
                                                        .randomOrNull()
                                                    if (target != null) {
                                                        val dmg = computeSkillDamage(
                                                            currentCombatant, target,
                                                            skill, false
                                                        )
                                                        val isCrit = Random.nextDouble() <
                                                            currentCombatant.critRate
                                                        playAttackSequence(
                                                            AttackAnimationEvent(
                                                                attackerId = currentCombatant.id,
                                                                targetId = target.id,
                                                                damage = dmg,
                                                                isCrit = isCrit,
                                                                isPhysical = skill.damageType ==
                                                                    DamageType.PHYSICAL,
                                                                skillName = skill.name,
                                                                isKill = target.hp - dmg <= 0
                                                            ),
                                                            cellPositions,
                                                            { currentAnimState },
                                                            { currentAnimState = it },
                                                            { shakingTargetId = it },
                                                            { activeDamageNumbers =
                                                                activeDamageNumbers + it },
                                                            { e -> applyAnimationResult(e) }
                                                        )
                                                    }
                                                } else {
                                                    // Buff/Heal 技能：立即应用
                                                    val result = executePlayerSkill(
                                                        currentCombatant, skill,
                                                        selectedTargetId, selectedIsAlly,
                                                        playerTeam, enemyTeam, isDefending
                                                    )
                                                    playerTeam = result.first
                                                    enemyTeam = result.second
                                                }
                                            }
                                            selectedTargetId = null
                                            selectedIsAlly = false
                                            isAnimating = false
                                            advanceTurn(
                                                alivePlayers, aliveEnemies,
                                                currentPlayerIdx, isDefending
                                            ) { ni, np, nd ->
                                                currentPlayerIdx = ni
                                                phase = np
                                                isDefending = nd
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
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = !isAnimating) {
                                        isDefending = isDefending
                                            .toMutableSet()
                                            .apply { add(currentCombatant.id) }
                                        advanceTurn(
                                            alivePlayers, aliveEnemies,
                                            currentPlayerIdx, isDefending
                                        ) { ni, np, nd ->
                                            currentPlayerIdx = ni
                                            phase = np
                                            isDefending = nd
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
                                modifier = Modifier.size(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = !isAnimating) {
                                        coroutineScope.launch {
                                            isAnimating = true
                                            val target = if (
                                                !selectedIsAlly &&
                                                selectedTargetId != null
                                            )
                                                enemyTeam.find {
                                                    it.id == selectedTargetId
                                                }
                                            else enemyTeam
                                                .filter { !it.isDead }
                                                .randomOrNull()
                                            if (target != null) {
                                                val dmg = computeNormalAttackDamage(
                                                    currentCombatant, target,
                                                    isDefending.contains(target.id)
                                                )
                                                playAttackSequence(
                                                    AttackAnimationEvent(
                                                        attackerId = currentCombatant.id,
                                                        targetId = target.id,
                                                        damage = dmg,
                                                        isCrit = false,
                                                        isPhysical = true,
                                                        isKill = target.hp - dmg <= 0
                                                    ),
                                                    cellPositions,
                                                    { currentAnimState },
                                                    { currentAnimState = it },
                                                    { shakingTargetId = it },
                                                    { activeDamageNumbers =
                                                        activeDamageNumbers + it },
                                                    { e -> applyAnimationResult(e) }
                                                )
                                            }
                                            selectedTargetId = null
                                            selectedIsAlly = false
                                            isAnimating = false
                                            advanceTurn(
                                                alivePlayers, aliveEnemies,
                                                currentPlayerIdx, isDefending
                                            ) { ni, np, nd ->
                                                currentPlayerIdx = ni
                                                phase = np
                                                isDefending = nd
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
    isShaking: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = when {
        isCurrent -> GameColors.Gold.copy(alpha = 0.3f)
        isAllySelected -> Color.Green.copy(alpha = 0.3f)
        isEnemySelected -> Color.Red.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(isShaking) {
        if (isShaking) {
            shakeOffset.animateTo(5f, tween(40))
            shakeOffset.animateTo(-4f, tween(40))
            shakeOffset.animateTo(3f, tween(40))
            shakeOffset.animateTo(-2f, tween(40))
            shakeOffset.animateTo(1f, tween(40))
            shakeOffset.animateTo(0f, tween(40))
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer { translationX = shakeOffset.value }
            .background(bgColor)
            .clickable { onClick() },
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

// region Animation Components

@Composable
private fun FloatingDamageNumber(
    damage: Int,
    isCrit: Boolean,
    isPhysical: Boolean,
    isHeal: Boolean = false,
    screenX: Float,
    screenY: Float,
    onFadeComplete: () -> Unit
) {
    val floatOffset = remember { Animatable(0f) }
    val damageScale = remember { Animatable(1f) }
    val damageAlpha = remember { Animatable(1f) }

    val textColor = when {
        isHeal -> GameColors.DamageHeal
        isCrit -> GameColors.DamageCrit
        isPhysical -> GameColors.DamagePhysical
        else -> GameColors.DamageMagic
    }
    val fontSize = if (isCrit) 22 else 16

    LaunchedEffect(Unit) {
        launch { damageScale.animateTo(1.3f, tween(150)) }
        launch { floatOffset.animateTo(-90f, tween(1200, easing = LinearEasing)) }
        delay(150)
        launch { damageScale.animateTo(1.0f, tween(200)) }
        delay(500)
        damageAlpha.animateTo(0f, tween(400, easing = LinearEasing))
        onFadeComplete()
    }

    val displayText = if (isCrit) "暴击!$damage"
        else if (isHeal) "+$damage"
        else "$damage"
    val fontWeight = if (isCrit) FontWeight.Bold else FontWeight.Normal

    Text(
        text = displayText,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        color = textColor,
        modifier = Modifier
            .offset {
                IntOffset(
                    screenX.roundToInt(),
                    (screenY + floatOffset.value).roundToInt()
                )
            }
            .graphicsLayer {
                scaleX = damageScale.value
                scaleY = damageScale.value
                this.alpha = damageAlpha.value
            }
    )
}

// region Attacker Flight Overlay

@Composable
private fun AttackerFlightOverlay(
    attacker: Combatant,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    phase: AnimPhase
) {
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }

    LaunchedEffect(phase) {
        when (phase) {
            AnimPhase.MOVE_TO_TARGET -> {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(250, easing = LinearEasing))
            }
            AnimPhase.IMPACT -> {
                progress.snapTo(1f)
            }
            AnimPhase.RETURN_TO_START -> {
                progress.snapTo(1f)
                progress.animateTo(0f, tween(250, easing = LinearEasing))
            }
            else -> progress.snapTo(0f)
        }
    }

    val curX = startX + (endX - startX) * progress.value
    val curY = startY + (endY - startY) * progress.value
    val size = 44

    val portraitResId = remember(attacker.id, attacker.portraitRes,
        attacker.isBeast
    ) {
        when {
            attacker.isBeast -> {
                val index = attacker.portraitRes
                    .removePrefix("beast_").toIntOrNull() ?: 0
                beastDrawables.getOrNull(index) ?: R.drawable.tiger_beast
            }
            attacker.portraitRes.isNotBlank() -> {
                PortraitPool.getResourceId(context, attacker.portraitRes)
                    .takeIf { it != 0 } ?: R.drawable.disciple_portrait
            }
            else -> {
                val randomPortrait = PortraitPool.getRandomPortrait(
                    if (Random.nextBoolean()) "male" else "female"
                )
                PortraitPool.getResourceId(context, randomPortrait)
                    .takeIf { it != 0 } ?: R.drawable.disciple_portrait
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
            .zIndex(10f)
    ) {
        if (attacker.isBeast) {
            Image(
                painter = painterResource(id = portraitResId),
                contentDescription = null,
                modifier = Modifier.size(size.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size.dp)
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
}

// endregion

// region Battle Logic

private fun computeNormalAttackDamage(
    attacker: Combatant, defender: Combatant, isDefending: Boolean
): Int {
    val baseAtk = attacker.effectivePhysicalAttack
    val baseDef = defender.effectivePhysicalDefense
    val rawDmg = baseAtk * 1.0 * (1.0 - baseDef / (baseDef + 500.0))
    val variance = 0.9 + Random.nextDouble() * 0.2
    val dmg = (rawDmg * variance).toInt().coerceAtLeast(1)
    return if (isDefending) (dmg * 0.75).toInt() else dmg
}

private fun computeSkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: com.xianxia.sect.core.model.CombatSkill, isDefending: Boolean
): Int {
    val baseAtk = if (skill.damageType == DamageType.PHYSICAL)
        attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
    val baseDef = if (skill.damageType == DamageType.PHYSICAL)
        defender.effectivePhysicalDefense else defender.effectiveMagicDefense
    val rawDmg = baseAtk * skill.damageMultiplier *
        (1.0 - baseDef / (baseDef + 500.0))
    val variance = 0.9 + Random.nextDouble() * 0.2
    val dmg = (rawDmg * variance).toInt().coerceAtLeast(1)
    return if (isDefending) (dmg * 0.75).toInt() else dmg
}

private fun applyNormalAttack(
    attacker: Combatant, defender: Combatant, isDefending: Boolean
): Combatant {
    val finalDmg = computeNormalAttackDamage(attacker, defender, isDefending)
    return defender.copy(hp = (defender.hp - finalDmg).coerceAtLeast(0))
}

private fun applySkillDamage(
    attacker: Combatant, defender: Combatant,
    skill: com.xianxia.sect.core.model.CombatSkill, isDefending: Boolean
): Combatant {
    val finalDmg = computeSkillDamage(attacker, defender, skill, isDefending)
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
            mp = (updatedPlayers[attackerIdx].mp - skill.mpCost)
                .coerceAtLeast(0)
        )
    }

    val isAttackSkill = skill.skillType ==
        com.xianxia.sect.core.SkillType.ATTACK || skill.damageMultiplier > 0

    if (skill.isAoe) {
        if (isAttackSkill) {
            updatedEnemies = updatedEnemies.map { e ->
                if (!e.isDead) applySkillDamage(
                    attacker, e, skill, isDefending.contains(e.id)
                ) else e
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
                updatedEnemies = updatedEnemies.map {
                    if (it.id == target.id) updated else it
                }.toMutableList()
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
                    updatedPlayers = updatedPlayers.map {
                        if (it.id == target.id) updated else it
                    }.toMutableList()
                }
            }
        }
    }

    return updatedPlayers.toList() to updatedEnemies.toList()
}

private fun applyBuffToTarget(
    target: Combatant, skill: com.xianxia.sect.core.model.CombatSkill
): Combatant {
    var newHp = target.hp; var newMp = target.mp
    if (skill.healPercent > 0) {
        if (skill.healType == com.xianxia.sect.core.HealType.HP)
            newHp = (target.hp + (target.maxHp * skill.healPercent).toInt())
                .coerceAtMost(target.maxHp)
        else newMp = (target.mp + (target.maxMp * skill.healPercent).toInt())
            .coerceAtMost(target.maxMp)
    }
    val newBuffs = skill.buffType?.let { bt ->
        target.buffs + com.xianxia.sect.core.engine.domain.battle.CombatBuff(
            bt, skill.buffValue, skill.buffDuration
        )
    } ?: target.buffs
    return target.copy(hp = newHp, mp = newMp, buffs = newBuffs)
}

private fun advanceTurn(
    alivePlayers: List<Combatant>,
    aliveEnemies: List<Combatant>,
    currentIdx: Int,
    isDefending: MutableSet<String>,
    onResult: (Int, BattlePhase, MutableSet<String>) -> Unit
) {
    if (aliveEnemies.all { it.isDead }) {
        onResult(currentIdx, BattlePhase.WON, isDefending); return
    }
    if (alivePlayers.all { it.isDead }) {
        onResult(currentIdx, BattlePhase.LOST, isDefending); return
    }
    val nextIdx = currentIdx + 1
    if (nextIdx >= alivePlayers.size)
        onResult(0, BattlePhase.ENEMY_TURN, isDefending)
    else onResult(nextIdx, BattlePhase.PLAYER_TURN, isDefending)
}

// endregion
