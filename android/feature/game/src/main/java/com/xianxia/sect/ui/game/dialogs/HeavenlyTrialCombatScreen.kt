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

// AoE 全体命中事件：一次飞行 + 所有目标同时受击
private data class AoeAnimationEvent(
    val attackerId: String,
    val targetIds: List<String>,
    val damages: Map<String, Int>,    // 每个目标独立 roll 的伤害
    val crits: Map<String, Boolean>,  // 每个目标独立暴击
    val isPhysical: Boolean,
    val isHeal: Boolean = false,
    val skillName: String? = null
)

// 动画事件统一分发接口
private sealed interface AnimEvent {
    data class Single(val event: AttackAnimationEvent) : AnimEvent
    data class Aoe(val event: AoeAnimationEvent) : AnimEvent
}

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
    val phase: AnimPhase = AnimPhase.IDLE,
    // AoE 时覆盖飞行终点为敌群中心坐标；单体攻击保持 null
    val overrideEnd: Offset? = null
)

// 传给每个格子的飞行动画信息：是否本格在飞、当前阶段、到目标的像素位移
private data class FlightAnimState(
    val isActive: Boolean = false,
    val phase: AnimPhase = AnimPhase.IDLE,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f
)

// endregion

private suspend fun playAttackSequence(
    event: AttackAnimationEvent,
    cellPositions: Map<String, Offset>,
    currentAnimState: () -> AttackAnimState,
    setAnimState: (AttackAnimState) -> Unit,
    setShaking: (Set<String>) -> Unit,
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
        setShaking(setOf(event.targetId))

        addDamageNumber(DamageNumberState(
            damage = event.damage,
            isCrit = event.isCrit,
            isPhysical = event.isPhysical,
            isHeal = event.isHeal,
            targetId = event.targetId
        ))

        delay(250)
        setShaking(emptySet())

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

// AoE 全体命中：飞向敌群中心一次 → 全体目标同时抖动+伤害数字 → 飞回 → 一次性结算
private suspend fun playAoeAttackSequence(
    event: AoeAnimationEvent,
    cellPositions: Map<String, Offset>,
    currentAnimState: () -> AttackAnimState,
    setAnimState: (AttackAnimState) -> Unit,
    setShaking: (Set<String>) -> Unit,
    addDamageNumber: (DamageNumberState) -> Unit,
    applyAoeResult: (AoeAnimationEvent) -> Unit
) {
    val aPos = cellPositions[event.attackerId]
    // 计算敌群中心点（所有目标位置的平均坐标）
    val targetPositions = event.targetIds.mapNotNull { cellPositions[it] }
    if (aPos == null || targetPositions.isEmpty()) {
        // 位置缺失时直接结算
        applyAoeResult(event)
        delay(100)
        return
    }
    val centerX = targetPositions.map { it.x }.average().toFloat()
    val centerY = targetPositions.map { it.y }.average().toFloat()
    val centerOffset = Offset(centerX, centerY)

    // 阶段 1: 攻击者飞向敌群中心（overrideEnd 覆盖飞行终点）
    setAnimState(AttackAnimState(
        attackerId = event.attackerId,
        targetId = event.targetIds.first(),
        phase = AnimPhase.MOVE_TO_TARGET,
        overrideEnd = centerOffset
    ))
    delay(300)

    // 阶段 2: 命中 —— 全体目标同时抖动 + 同时弹出伤害数字
    setAnimState(currentAnimState().copy(
        phase = AnimPhase.IMPACT,
        overrideEnd = centerOffset
    ))
    setShaking(event.targetIds.toSet())

    event.targetIds.forEach { tid ->
        val dmg = event.damages[tid] ?: 0
        val crit = event.crits[tid] ?: false
        addDamageNumber(DamageNumberState(
            damage = dmg,
            isCrit = crit,
            isPhysical = event.isPhysical,
            isHeal = event.isHeal,
            targetId = tid
        ))
    }

    delay(300)
    setShaking(emptySet())

    // 阶段 3: 返回原位
    setAnimState(currentAnimState().copy(
        phase = AnimPhase.RETURN_TO_START,
        overrideEnd = centerOffset
    ))
    delay(300)

    // 阶段 4: 一次性结算全体伤害
    applyAoeResult(event)
    setAnimState(AttackAnimState())
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
                                val damages = targets.associate { p ->
                                    p.id to computeSkillDamage(
                                        enemy, p, skill,
                                        isDefending.contains(p.id)
                                    )
                                }
                                val crits = targets.associate {
                                    it.id to (Random.nextDouble() < enemy.critRate)
                                }
                                AnimEvent.Aoe(AoeAnimationEvent(
                                    attackerId = enemy.id,
                                    targetIds = targets.map { it.id },
                                    damages = damages,
                                    crits = crits,
                                    isPhysical = skill.damageType == DamageType.PHYSICAL,
                                    skillName = skill.name
                                ))
                            }
                        } else if (skill != null && target != null) {
                            val dmg = computeSkillDamage(
                                enemy, target, skill,
                                isDefending.contains(target.id)
                            )
                            val isCrit = Random.nextDouble() < enemy.critRate
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = target.id,
                                damage = dmg,
                                isCrit = isCrit,
                                isPhysical = skill.damageType == DamageType.PHYSICAL,
                                skillName = skill.name,
                                isKill = target.hp - dmg <= 0
                            ))
                        } else null
                    }
                    ActionType.NORMAL_ATTACK -> {
                        if (target != null) {
                            val dmg = computeNormalAttackDamage(
                                enemy, target,
                                isDefending.contains(target.id)
                            )
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = target.id,
                                damage = dmg,
                                isCrit = false,
                                isPhysical = true,
                                isKill = target.hp - dmg <= 0
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
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = target.id,
                                targetId = target.id,
                                damage = (target.maxHp * skill.healPercent).toInt(),
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
                            AnimEvent.Single(AttackAnimationEvent(
                                attackerId = enemy.id,
                                targetId = enemy.id,
                                damage = (enemy.maxHp * skill.healPercent).toInt(),
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
                            .clickable {
                                coroutineScope.launch {
                                    isAnimating = true
                                    val (finalPlayers, finalEnemies) =
                                        simulateInstantResolve(
                                            playerTeam, enemyTeam,
                                            viewModel.trialService
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
                                                    if (targets.isNotEmpty()) {
                                                        // AoE：一次飞行 + 全体同时受击
                                                        val damages = targets.associate { t ->
                                                            t.id to computeSkillDamage(
                                                                currentCombatant, t,
                                                                skill, false
                                                            )
                                                        }
                                                        val crits = targets.associate {
                                                            it.id to (Random.nextDouble() <
                                                                currentCombatant.critRate)
                                                        }
                                                        playAoeAttackSequence(
                                                            AoeAnimationEvent(
                                                                attackerId = currentCombatant.id,
                                                                targetIds = targets.map { it.id },
                                                                damages = damages,
                                                                crits = crits,
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
    // 飞行动画：本格是否为正在飞行的攻击者；若是，按 animState 平移本体
    flightAnim: FlightAnimState = FlightAnimState(),
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

    // 本体飞行进度：0=原位，1=目标位。仅攻击者自身参与。
    val flightProgress = remember { Animatable(0f) }
    LaunchedEffect(flightAnim.phase, flightAnim.isActive) {
        if (flightAnim.isActive) {
            when (flightAnim.phase) {
                AnimPhase.MOVE_TO_TARGET -> {
                    flightProgress.snapTo(0f)
                    flightProgress.animateTo(1f, tween(250, easing = LinearEasing))
                }
                AnimPhase.IMPACT -> flightProgress.snapTo(1f)
                AnimPhase.RETURN_TO_START -> {
                    flightProgress.snapTo(1f)
                    flightProgress.animateTo(0f, tween(250, easing = LinearEasing))
                }
                else -> flightProgress.snapTo(0f)
            }
        } else {
            flightProgress.snapTo(0f)
        }
    }

    // 平移量（屏幕像素）：从原位插值到目标位
    val transX = flightAnim.deltaX * flightProgress.value
    val transY = flightAnim.deltaY * flightProgress.value

    // 外层 Box：固定在格子原位，承载背景色/点击/zIndex；不参与平移
    Box(
        modifier = modifier
            .zIndex(if (flightAnim.isActive) 10f else 0f)
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // 内层 Box：承载立绘内容，做飞行平移 + 受击抖动
        if (combatant != null && !combatant.isDead) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    translationX = shakeOffset.value + transX
                    translationY = transY
                }
            ) {
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

                // 肖像图标（本体直接飞行，无需额外覆盖层）
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
    // 字号：普通 18，暴击 24（带描边已足够清晰）
    val fontSize = if (isCrit) 24 else 18

    LaunchedEffect(Unit) {
        launch { damageScale.animateTo(1.3f, tween(150)) }
        launch { floatOffset.animateTo(-120f, tween(1200, easing = LinearEasing)) }
        delay(150)
        launch { damageScale.animateTo(1.0f, tween(200)) }
        delay(500)
        damageAlpha.animateTo(0f, tween(400, easing = LinearEasing))
        onFadeComplete()
    }

    val displayText = if (isCrit) "暴击!$damage"
        else if (isHeal) "+$damage"
        else "$damage"
    val fontWeight = if (isCrit) FontWeight.Bold else FontWeight.ExtraBold

    // 用 Box 叠加多层 Text 实现黑描边：8 方向黑色偏移 + 中心彩色填充
    Box(
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
    ) {
        // 底层：8 方向黑色偏移构成描边
        val strokeDirs = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -1, 0 to 1,
            1 to -1, 1 to 0, 1 to 1
        )
        strokeDirs.forEach { (dx, dy) ->
            Text(
                text = displayText,
                fontSize = fontSize.sp,
                fontWeight = fontWeight,
                color = Color.Black,
                modifier = Modifier.offset { IntOffset(dx, dy) }
            )
        }
        // 顶层：彩色填充
        Text(
            text = displayText,
            fontSize = fontSize.sp,
            fontWeight = fontWeight,
            color = textColor
        )
    }
}

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

// region Instant Resolve（即时结算）

/**
 * 即时结算：模拟整场战斗，跳过所有动画，直接返回最终双方状态。
 * 玩家方自动选择最优技能/普攻，敌方复用 AI。
 *
 * @return Pair(最终玩家队伍, 最终敌方队伍)
 */
private fun simulateInstantResolve(
    playerTeam: List<Combatant>,
    enemyTeam: List<Combatant>,
    trialService: com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService
): Pair<List<Combatant>, List<Combatant>> {
    var players = playerTeam.map { p ->
        p.copy(skills = p.skills.map { it.copy() })
    }
    var enemies = enemyTeam.map { e ->
        e.copy(skills = e.skills.map { it.copy() })
    }

    val maxRounds = 100
    var round = 0

    while (round < maxRounds) {
        if (enemies.all { it.isDead } || players.all { it.isDead }) break

        // 回合开始：所有技能冷却 -1
        players = players.map { p ->
            p.copy(skills = p.skills.map { s ->
                s.copy(currentCooldown =
                    (s.currentCooldown - 1).coerceAtLeast(0))
            })
        }
        enemies = enemies.map { e ->
            e.copy(skills = e.skills.map { s ->
                s.copy(currentCooldown =
                    (s.currentCooldown - 1).coerceAtLeast(0))
            })
        }

        // 按速度降序排列行动顺序
        val turnOrder = (players.filter { !it.isDead } +
            enemies.filter { !it.isDead })
            .sortedByDescending { it.effectiveSpeed }

        for (unit in turnOrder) {
            if (enemies.all { it.isDead }) break
            if (players.all { it.isDead }) break

            val isPlayer = players.any { it.id == unit.id }
            if (isPlayer) {
                val result = autoResolvePlayerAction(
                    unit, players, enemies)
                players = result.first
                enemies = result.second
            } else {
                val action = trialService.executeEnemyAction(
                    unit, players,
                    enemies.filter { it.id != unit.id && !it.isDead }
                )
                val result = resolveEnemyAction(unit, action,
                    players, enemies)
                players = result.first
                enemies = result.second
            }
        }
        round++
    }

    return players to enemies
}

/**
 * 玩家自动行动：选择最优攻击技能或普攻，
 * 始终攻击 HP 最低的存活敌人。
 */
private fun autoResolvePlayerAction(
    player: Combatant,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    val aliveEnemies = enemies.filter { !it.isDead }
    if (aliveEnemies.isEmpty()) return players to enemies

    val attackSkills = player.skills.filter {
        it.currentCooldown <= 0 &&
            player.mp >= it.mpCost &&
            (it.skillType == com.xianxia.sect.core.SkillType.ATTACK ||
                it.damageMultiplier > 0)
    }

    val playerIdx = players.indexOfFirst { it.id == player.id }

    if (attackSkills.isNotEmpty()) {
        // AoE 按存活敌人数加权，选综合收益最高的技能
        val bestSkill = attackSkills.maxByOrNull {
            it.damageMultiplier *
                (if (it.isAoe)
                    aliveEnemies.size.coerceAtMost(3).toDouble()
                else 1.0)
        } ?: return players to enemies

        // 扣除 MP + 设置冷却
        var updatedPlayers = players
        if (playerIdx >= 0) {
            val drained = updatedPlayers[playerIdx].copy(
                mp = (updatedPlayers[playerIdx].mp - bestSkill.mpCost)
                    .coerceAtLeast(0),
                skills = updatedPlayers[playerIdx].skills.map { s ->
                    if (s.name == bestSkill.name)
                        s.copy(currentCooldown = s.cooldown)
                    else s
                }
            )
            updatedPlayers = updatedPlayers.toMutableList()
                .apply { this[playerIdx] = drained }
        }

        val updatedEnemies = if (bestSkill.isAoe) {
            enemies.map { e ->
                if (!e.isDead) {
                    val dmg = computeSkillDamage(
                        player, e, bestSkill, false)
                    e.copy(hp = (e.hp - dmg).coerceAtLeast(0))
                } else e
            }
        } else {
            val target = aliveEnemies.minByOrNull { it.hp }
                ?: return players to enemies
            val dmg = computeSkillDamage(
                player, target, bestSkill, false)
            enemies.map {
                if (it.id == target.id)
                    it.copy(hp = (it.hp - dmg).coerceAtLeast(0))
                else it
            }
        }
        return updatedPlayers to updatedEnemies
    }

    // 无可用技能 → 普攻最弱敌人
    val target = aliveEnemies.minByOrNull { it.hp }
        ?: return players to enemies
    val dmg = computeNormalAttackDamage(player, target, false)
    val updatedEnemies = enemies.map {
        if (it.id == target.id)
            it.copy(hp = (it.hp - dmg).coerceAtLeast(0))
        else it
    }
    return players to updatedEnemies
}

/**
 * 结算单次敌方行动，更新双方队伍状态。
 */
private fun resolveEnemyAction(
    enemy: Combatant,
    action: com.xianxia.sect.core.engine.domain.battle.EnemyAction,
    players: List<Combatant>,
    enemies: List<Combatant>
): Pair<List<Combatant>, List<Combatant>> {
    var updatedPlayers = players
    var updatedEnemies = enemies

    val skill = action.skill
    val target = action.target

    when (action.actionType) {
        ActionType.NONE -> {}
        ActionType.ATTACK -> {
            if (skill != null && skill.isAoe) {
                updatedPlayers = updatedPlayers.map { p ->
                    if (!p.isDead) {
                        val dmg = computeSkillDamage(
                            enemy, p, skill, false)
                        p.copy(hp = (p.hp - dmg).coerceAtLeast(0))
                    } else p
                }
            } else if (skill != null && target != null) {
                val dmg = computeSkillDamage(
                    enemy, target, skill, false)
                updatedPlayers = updatedPlayers.map {
                    if (it.id == target.id)
                        it.copy(hp = (it.hp - dmg).coerceAtLeast(0))
                    else it
                }
            }
        }
        ActionType.NORMAL_ATTACK -> {
            if (target != null) {
                val dmg = computeNormalAttackDamage(
                    enemy, target, false)
                updatedPlayers = updatedPlayers.map {
                    if (it.id == target.id)
                        it.copy(hp = (it.hp - dmg).coerceAtLeast(0))
                    else it
                }
            }
        }
        ActionType.BUFF_ALLY -> {
            if (skill != null && target != null) {
                val buffed = applyBuffToTarget(target, skill)
                updatedEnemies = updatedEnemies.map {
                    if (it.id == target.id) buffed else it
                }
            }
        }
        ActionType.BUFF_SELF -> {
            if (skill != null) {
                val buffed = applyBuffToTarget(enemy, skill)
                updatedEnemies = updatedEnemies.map {
                    if (it.id == enemy.id) buffed else it
                }
            }
        }
    }

    // 敌方技能消耗：扣除 MP + 设置冷却
    if (skill != null &&
        action.actionType != ActionType.NONE &&
        action.actionType != ActionType.NORMAL_ATTACK
    ) {
        val enemyIdx = updatedEnemies.indexOfFirst {
            it.id == enemy.id
        }
        if (enemyIdx >= 0) {
            updatedEnemies = updatedEnemies.toMutableList().apply {
                this[enemyIdx] = this[enemyIdx].copy(
                    mp = (this[enemyIdx].mp - skill.mpCost)
                        .coerceAtLeast(0),
                    skills = this[enemyIdx].skills.map { s ->
                        if (s.name == skill.name)
                            s.copy(currentCooldown = s.cooldown)
                        else s
                    }
                )
            }
        }
    }

    return updatedPlayers to updatedEnemies
}

// endregion
