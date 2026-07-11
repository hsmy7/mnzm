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
import com.xianxia.sect.core.engine.domain.battle.BattleAI
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.beastSpriteRes
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.*

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
    var currentRound by remember { mutableStateOf(1) }
    val battleStartTime = remember { System.currentTimeMillis() }

    // Animation state
    var isAnimating by remember { mutableStateOf(false) }
    var currentAnimState by remember { mutableStateOf(AttackAnimState()) }
    var shakingTargetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeDamageNumbers by remember {
        mutableStateOf<List<DamageNumberState>>(emptyList())
    }
    val cellPositions = remember { mutableStateMapOf<String, Offset>() }

    val alivePlayers = playerTeam.filter { !it.isDead }
    val aliveEnemies = enemyTeam.filter { !it.isDead }
    val currentCombatant = alivePlayers.getOrNull(currentPlayerIdx)

    // Animation helpers defined inside composable for state capture

    fun applyAnimationResult(event: AttackAnimationEvent) {
        // 治疗已在 BUFF_ALLY / BUFF_SELF 中预应用，此处只应用伤害
        if (event.isHeal) return
        val isTargetPlayer = playerTeam.any { it.id == event.targetId }
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

    // AoE 一次性结算：对所有目标同步应用伤害
    fun applyAoeResult(event: AoeAnimationEvent) {
        val damages = event.damages
        if (event.isHeal) {
            // 治疗型 AoE（暂未使用，预留）
            val isTargetPlayer = playerTeam.any { it.id in event.targetIds }
            if (isTargetPlayer) {
                playerTeam = playerTeam.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp + d).coerceAtMost(c.maxHp))
                }
            } else {
                enemyTeam = enemyTeam.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp + d).coerceAtMost(c.maxHp))
                }
            }
        } else {
            // 判定目标阵营
            val damageOnPlayers = event.targetIds.any { id -> playerTeam.any { it.id == id } }
            if (damageOnPlayers) {
                playerTeam = playerTeam.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp - d).coerceAtLeast(0))
                }
            }
            val damageOnEnemies = event.targetIds.any { id -> enemyTeam.any { it.id == id } }
            if (damageOnEnemies) {
                enemyTeam = enemyTeam.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp - d).coerceAtLeast(0))
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

            // 敌人逐个行动：边算边播，确保 executeEnemyAction 始终看到
            // 上一只敌人攻击后的真实血量（修复陈旧血量 bug）
            val sortedEnemies = enemyTeam.filter { !it.isDead }
                .sortedByDescending { it.speed }

            for (enemy in sortedEnemies) {
                if (playerTeam.all { it.isDead }) break

                val action = viewModel.trialService.executeEnemyAction(
                    attacker = enemy,
                    playerTeam = playerTeam,   // 最新血量
                    allyTeam = enemyTeam.filter { it.id != enemy.id }
                )
                val skill = action.skill
                val target = action.target

                // 把这次行动组装成 AnimEvent 并即时播放结算
                val animEvent: AnimEvent? = when (action.actionType) {
                    ActionType.NONE -> null
                    ActionType.ATTACK -> {
                        if (skill != null && skill.isAoe) {
                            // AoE：一次飞行，每目标独立伤害
                            val targets = playerTeam.filter { !it.isDead }
                            if (targets.isEmpty()) null
                            else {
                                val results = targets.associate { p ->
                                    p.id to computeSkillDamage(
                                        enemy, p, skill,
                                        isDefending.contains(p.id)
                                    )
                                }
                                AnimEvent.Aoe(AoeAnimationEvent(
                                    attackerId = enemy.id,
                                    targetIds = targets.map { it.id },
                                    damages = results.mapValues { it.value.damage },
                                    crits = results.mapValues { it.value.isCrit },
                                    isPhysical = skill.damageType == DamageType.PHYSICAL,
                                    skillName = skill.name
                                ))
                            }
                        } else if (skill != null && target != null) {
                            val result = computeSkillDamage(
                                enemy, target, skill,
                                isDefending.contains(target.id)
                            )
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = target.id,
                                damage = result.damage,
                                isCrit = result.isCrit,
                                isPhysical = skill.damageType == DamageType.PHYSICAL,
                                skillName = skill.name,
                                isKill = target.hp - result.damage <= 0
                            ))
                        } else null
                    }
                    ActionType.NORMAL_ATTACK -> {
                        if (target != null) {
                            val result = computeNormalAttackDamage(
                                enemy, target,
                                isDefending.contains(target.id)
                            )
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = target.id,
                                damage = result.damage,
                                isCrit = result.isCrit,
                                isPhysical = true,
                                isKill = target.hp - result.damage <= 0
                            ))
                        } else null
                    }
                    ActionType.BUFF_ALLY -> {
                        if (skill != null && target != null) {
                            // Buff 效果立即应用到敌方队伍（不经过动画结算）
                            val buffed = applyBuffToTarget(target, skill)
                            enemyTeam = enemyTeam.map {
                                if (it.id == target.id) buffed else it
                            }
                            val healDisplay = (target.maxHp * skill.healPercent).toInt() + skill.healFixed
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = target.id,
                                targetId = target.id,
                                damage = healDisplay,
                                isCrit = false,
                                isPhysical = false,
                                isHeal = true,
                                skillName = skill.name
                            ))
                        } else null
                    }
                    ActionType.BUFF_SELF -> {
                        if (skill != null) {
                            val buffed = applyBuffToTarget(enemy, skill)
                            enemyTeam = enemyTeam.map {
                                if (it.id == enemy.id) buffed else it
                            }
                            val healDisplay = (enemy.maxHp * skill.healPercent).toInt() + skill.healFixed
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = enemy.id,
                                damage = healDisplay,
                                isCrit = false,
                                isPhysical = false,
                                isHeal = true,
                                skillName = skill.name
                            ))
                        } else null
                    }
                }

                // 即时播放并结算（更新 playerTeam / enemyTeam）
                when (animEvent) {
                    is AnimEvent.Aoe -> {
                        playAoeAttackSequence(
                            event = animEvent.event,
                            cellPositions = cellPositions,
                            currentAnimState = { currentAnimState },
                            setAnimState = { currentAnimState = it },
                            setShaking = { shakingTargetIds = it },
                            addDamageNumber = {
                                activeDamageNumbers = activeDamageNumbers + it
                            },
                            applyAoeResult = { e -> applyAoeResult(e) }
                        )
                    }
                    is AnimEvent.Single -> {
                        playAttackSequence(
                            event = animEvent.event,
                            cellPositions = cellPositions,
                            currentAnimState = { currentAnimState },
                            setAnimState = { currentAnimState = it },
                            setShaking = { shakingTargetIds = it },
                            addDamageNumber = {
                                activeDamageNumbers = activeDamageNumbers + it
                            },
                            applyResult = { e -> applyAnimationResult(e) }
                        )
                    }
                    null -> { /* 被控或无目标，跳过 */ }
                }
            }

            isDefending = mutableSetOf()
            currentPlayerIdx = 0
            isAnimating = false
            currentRound++
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
            painter = painterResource(id = SpriteResRegistry.resolve("heavenly_trial_battle_scene") ?: 0),
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
                        // 计算本格的飞行动画：仅当本格是当前飞行攻击者时激活，
                        // delta = 目标位置 - 本格位置（屏幕像素）
                        val flightAnim = if (cellCombatant != null &&
                            currentAnimState.phase != AnimPhase.IDLE &&
                            currentAnimState.attackerId == cellCombatant.id
                        ) {
                            val selfPos = cellPositions[cellCombatant.id]
                            val targetPos = currentAnimState.overrideEnd
                                ?: currentAnimState.targetId?.let { cellPositions[it] }
                            if (selfPos != null && targetPos != null) {
                                FlightAnimState(
                                    isActive = true,
                                    phase = currentAnimState.phase,
                                    deltaX = targetPos.x - selfPos.x,
                                    deltaY = targetPos.y - selfPos.y
                                )
                            } else FlightAnimState()
                        } else FlightAnimState()

                        CombatUnitCell(
                            combatant = cellCombatant,
                            isCurrent = isCurrent,
                            isAllySelected = allySelected,
                            isEnemySelected = enemySelected,
                            isShaking = cellCombatant != null &&
                                shakingTargetIds.contains(cellCombatant.id),
                            flightAnim = flightAnim,
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

        // 动画覆盖层（仅伤害数字；本体飞行由网格格子自身的位移实现）
        if (activeDamageNumbers.isNotEmpty()) {
            Box(modifier = Modifier.matchParentSize().zIndex(15f)) {
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
                                screenX = pos.x + 8f,
                                screenY = pos.y - 38f,
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

        // 回合数显示
        Text(
            text = "第${currentRound}回",
            color = Color.Black,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )

        // 右上角关闭按钮（必须在网格之后，确保 z-order 在最上层）
        CloseButton(
            onClick = { showExitConfirm = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
        )

        // 跳过按钮（战斗栏外部右侧，随时可点击即时结算）
        if (phase != BattlePhase.WON && phase != BattlePhase.LOST) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 16.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                2.dp, GameColors.Gold,
                                RoundedCornerShape(4.dp)
                            )
                            .background(Color.White)
                            .clickable(enabled = !isAnimating) {
                                coroutineScope.launch {
                                    isAnimating = true
                                    val (finalPlayers, finalEnemies) =
                                        simulateInstantResolve(
                                            playerTeam, enemyTeam
                                        )
                                    playerTeam = finalPlayers
                                    enemyTeam = finalEnemies
                                    isAnimating = false
                                    if (finalPlayers.all { it.isDead }) {
                                        phase = BattlePhase.LOST
                                    } else if (finalEnemies.all {
                                            it.isDead
                                    }) {
                                        phase = BattlePhase.WON
                                    } else {
                                        // 超轮上限未分胜负：按血量比判定
                                        val pHp = finalPlayers
                                            .sumOf { it.hp }
                                        val pMax = finalPlayers
                                            .sumOf { it.maxHp }
                                        val eHp = finalEnemies
                                            .sumOf { it.hp }
                                        val eMax = finalEnemies
                                            .sumOf { it.maxHp }
                                        phase = if (pMax > 0 && eMax > 0 &&
                                            pHp.toDouble() / pMax >=
                                            eHp.toDouble() / eMax
                                        )
                                            BattlePhase.WON
                                        else BattlePhase.LOST
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "跳过",
                            fontSize = 10.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "跳过",
                        fontSize = 9.sp,
                        color = Color.Black
                    )
                }
            }
        }

        // 战斗栏（左右留空隙）
        if (phase == BattlePhase.PLAYER_TURN && currentCombatant != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 8.dp)
            ) {
                Image(
                    painter = painterResource(id = SpriteResRegistry.resolve("heavenly_trial_battle_bar") ?: 0),
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 防御（左侧）
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
                                            playerTeam.filter { !it.isDead }, enemyTeam.filter { !it.isDead },
                                            currentPlayerIdx, isDefending
                                        ) { ni, np, nd ->
                                            currentPlayerIdx = ni
                                            phase = np
                                            isDefending = nd
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(painterResource(id = SpriteResRegistry.resolve("heavenly_trial_defend") ?: 0), "防御",
                                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            }
                            Text("防御", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        // 技能图标（居中）
                        Row(
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
                                                        if (targets.isNotEmpty()) {
                                                            // AoE：一次飞行 + 全体同时受击
                                                            val results = targets.associate { t ->
                                                                t.id to computeSkillDamage(
                                                                    currentCombatant, t,
                                                                    skill, false
                                                                )
                                                            }
                                                            playAoeAttackSequence(
                                                                AoeAnimationEvent(
                                                                    attackerId = currentCombatant.id,
                                                                    targetIds = targets.map { it.id },
                                                                    damages = results.mapValues { it.value.damage },
                                                                    crits = results.mapValues { it.value.isCrit },
                                                                    isPhysical = skill.damageType ==
                                                                        DamageType.PHYSICAL,
                                                                    skillName = skill.name
                                                                ),
                                                                cellPositions,
                                                                { currentAnimState },
                                                                { currentAnimState = it },
                                                                { shakingTargetIds = it },
                                                                { activeDamageNumbers =
                                                                    activeDamageNumbers + it },
                                                                { e -> applyAoeResult(e) }
                                                            )
                                                        }
                                                    } else {
                                                    // AoE 辅助/治疗：立即应用
                                                    val result = executePlayerSkill(
                                                        currentCombatant, skill,
                                                        selectedTargetId, selectedIsAlly,
                                                        playerTeam, enemyTeam, isDefending
                                                    )
                                                    playerTeam = result.first
                                                    enemyTeam = result.second
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
                                                            val result = computeSkillDamage(
                                                                currentCombatant, target,
                                                                skill, false
                                                            )
                                                            playAttackSequence(
                                                                AttackAnimationEvent(
                                                                    attackerId = currentCombatant.id,
                                                                    targetId = target.id,
                                                                    damage = result.damage,
                                                                    isCrit = result.isCrit,
                                                                    isPhysical = skill.damageType ==
                                                                        DamageType.PHYSICAL,
                                                                    skillName = skill.name,
                                                                    isKill = target.hp - result.damage <= 0
                                                                ),
                                                                cellPositions,
                                                                { currentAnimState },
                                                                { currentAnimState = it },
                                                                { shakingTargetIds = it },
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
                                                    playerTeam.filter { !it.isDead }, enemyTeam.filter { !it.isDead },
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

                        // 普攻（右侧）
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
                                                val result = computeNormalAttackDamage(
                                                    currentCombatant, target,
                                                    isDefending.contains(target.id)
                                                )
                                                playAttackSequence(
                                                    AttackAnimationEvent(
                                                        attackerId = currentCombatant.id,
                                                        targetId = target.id,
                                                        damage = result.damage,
                                                        isCrit = result.isCrit,
                                                        isPhysical = true,
                                                        isKill = target.hp - result.damage <= 0
                                                    ),
                                                    cellPositions,
                                                    { currentAnimState },
                                                    { currentAnimState = it },
                                                    { shakingTargetIds = it },
                                                    { activeDamageNumbers =
                                                        activeDamageNumbers + it },
                                                    { e -> applyAnimationResult(e) }
                                                )
                                            }
                                            selectedTargetId = null
                                            selectedIsAlly = false
                                            isAnimating = false
                                            advanceTurn(
                                                playerTeam.filter { !it.isDead }, enemyTeam.filter { !it.isDead },
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
                                Image(painterResource(id = SpriteResRegistry.resolve("heavenly_trial_atk_normal") ?: 0), "普攻",
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
                totalRounds = currentRound,
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


// 提取到 heavenlytrial/ 子目录的函数和类：
// - BattlePhase           → HeavenlyTrialModels.kt
// - AttackAnimationEvent  → HeavenlyTrialModels.kt
// - AoeAnimationEvent     → HeavenlyTrialModels.kt
// - AnimEvent             → HeavenlyTrialModels.kt
// - DamageNumberState     → HeavenlyTrialModels.kt
// - AnimPhase             → HeavenlyTrialModels.kt
// - AttackAnimState       → HeavenlyTrialModels.kt
// - FlightAnimState       → HeavenlyTrialModels.kt
// - playAttackSequence    → HeavenlyTrialAnimation.kt
// - playAoeAttackSequence → HeavenlyTrialAnimation.kt
// - CombatUnitCell        → HeavenlyTrialComponents.kt
// - CombatantPortrait     → HeavenlyTrialComponents.kt
// - FloatingDamageNumber  → HeavenlyTrialComponents.kt
// - computeNormalAttackDamage   → HeavenlyTrialCombatLogic.kt
// - computeSkillDamage          → HeavenlyTrialCombatLogic.kt
// - applyNormalAttack           → HeavenlyTrialCombatLogic.kt
// - applySkillDamage            → HeavenlyTrialCombatLogic.kt
// - executePlayerSkill          → HeavenlyTrialCombatLogic.kt
// - applyBuffToTarget           → HeavenlyTrialCombatLogic.kt
// - advanceTurn                 → HeavenlyTrialCombatLogic.kt
// - simulateInstantResolve      → HeavenlyTrialCombatLogic.kt
// - resolveAIAction             → HeavenlyTrialCombatLogic.kt
