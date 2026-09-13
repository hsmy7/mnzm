@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.engine.domain.battle.ActionType
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.EnemyAction
import com.xianxia.sect.core.util.PresentationRandom
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.AnimEvent
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.AnimPhase
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.AoeAnimationEvent
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.AttackAnimState
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.AttackAnimationEvent
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.BattlePhase
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.CombatUnitCell
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.DamageNumberState
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.FlightAnimState
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.FloatingDamageNumber
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.advanceTurn
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.applyBuffToTarget
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.computeNormalAttackDamage
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.computeSkillDamage
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.currentCombatRng
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.executePlayerSkill
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.playAoeAttackSequence
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.playAttackSequence
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.randomOrNull
import com.xianxia.sect.ui.game.dialogs.heavenlytrial.simulateInstantResolve
import com.xianxia.sect.ui.components.clickableWithSound



/** 天劫试炼战斗界面状态：队伍/回合/动画状态 + 结算方法 */
private class HeavenlyTrialCombatState(
    viewModel: HeavenlyTrialViewModel
) {
    var phase by mutableStateOf(BattlePhase.PLAYER_TURN)
    var currentPlayerIdx by mutableIntStateOf(0)
    var selectedTargetId by mutableStateOf<String?>(null)
    var selectedIsAlly by mutableStateOf(false)
    var playerTeam by mutableStateOf(viewModel.playerCombatants)
    var enemyTeam by mutableStateOf(viewModel.enemyCombatants)
    // 不可变集合 + 赋值更新（既有更新路径均为 copy 风格，无原地 mutate）
    var isDefending by mutableStateOf(emptySet<String>())
    var showExitConfirm by mutableStateOf(false)
    var currentRound by mutableIntStateOf(1)
    val battleStartTime = System.currentTimeMillis()

    // Animation state
    var isAnimating by mutableStateOf(false)
    var currentAnimState by mutableStateOf(AttackAnimState())
    var shakingTargetIds by mutableStateOf<Set<String>>(emptySet())
    var activeDamageNumbers by mutableStateOf<List<DamageNumberState>>(emptyList())
    val cellPositions = mutableStateMapOf<String, Offset>()

    val alivePlayers: List<Combatant> get() = playerTeam.filter { !it.isDead }
    val aliveEnemies: List<Combatant> get() = enemyTeam.filter { !it.isDead }
    val currentCombatant: Combatant? get() = alivePlayers.getOrNull(currentPlayerIdx)

    /** 单体动画结果结算（原 applyAnimationResult 局部函数）：治疗已预应用，此处只应用伤害 */
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

    /** AoE 一次性结算（原 applyAoeResult 局部函数）：对所有目标同步应用伤害/治疗 */
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

    /** 网格格点击（原 CombatUnitCell onClick）：选中/取消选中目标 */
    fun onCellClick(
        cellCombatant: Combatant,
        isPlayer: Boolean,
        allySelected: Boolean,
        enemySelected: Boolean
    ) {
        if (phase == BattlePhase.PLAYER_TURN && !cellCombatant.isDead && !isAnimating) {
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
}

@Composable
fun HeavenlyTrialCombatScreen(
    viewModel: HeavenlyTrialViewModel,
    onFinished: (won: Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val state = remember { HeavenlyTrialCombatState(viewModel) }
    HeavenlyTrialCombatEffects(state = state, viewModel = viewModel)
    Box(modifier = Modifier.fillMaxSize()) {
        HeavenlyTrialBattleBackdrop()
        HeavenlyTrialBattleGrid(
            state = state,
            currentCombatant = state.currentCombatant,
            random = viewModel.presentationRandom
        )
        HeavenlyTrialDamageOverlay(state = state)
        HeavenlyTrialTopBar(currentRound = state.currentRound, onClose = { state.showExitConfirm = true })
        HeavenlyTrialSkipButton(state = state, coroutineScope = coroutineScope)
        state.currentCombatant?.let {
            HeavenlyTrialBattleBar(state = state, currentCombatant = it, coroutineScope = coroutineScope)
        }
        // 战斗结算
        BattleResultPanel(
            showResult = viewModel.showResult, won = viewModel.resultWon,
            durationSeconds = viewModel.resultDuration, totalRounds = state.currentRound,
            onDismiss = {
                viewModel.dismissResult()
                onFinished(viewModel.resultWon)
            }
        )
    }
    // 退出确认提示框
    if (state.showExitConfirm) {
        StandardPromptDialog(
            onDismissRequest = { state.showExitConfirm = false },
            title = "退出战斗",
            text = "确定要退出战斗吗？退出将视为战斗失败。",
            confirmLabel = "确定退出",
            onConfirm = {
                state.showExitConfirm = false
                state.phase = BattlePhase.LOST
            },
            dismissLabel = "取消",
            onDismiss = { state.showExitConfirm = false }
        )
    }
}

/** 战斗副作用：胜负判定 + 敌方回合 + 结算展示 */
@Composable
private fun HeavenlyTrialCombatEffects(
    state: HeavenlyTrialCombatState,
    viewModel: HeavenlyTrialViewModel
) {
    LaunchedEffect(state.playerTeam, state.enemyTeam) {
        if (state.playerTeam.all { it.isDead }) { state.phase = BattlePhase.LOST }
        else if (state.enemyTeam.all { it.isDead }) { state.phase = BattlePhase.WON }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == BattlePhase.ENEMY_TURN && !state.isAnimating) {
            runEnemyTurnSequence(state = state, viewModel = viewModel)
        }
    }

    LaunchedEffect(state.phase) {
        if (state.phase == BattlePhase.WON || state.phase == BattlePhase.LOST) {
            val durationSeconds = (System.currentTimeMillis() - state.battleStartTime) / 1000
            viewModel.showBattleResult(state.phase == BattlePhase.WON, durationSeconds)
        }
    }
}

/** 敌方回合逐个行动：边算边播确保血量实时 */
private suspend fun runEnemyTurnSequence(
    state: HeavenlyTrialCombatState,
    viewModel: HeavenlyTrialViewModel
) {
    state.isAnimating = true
    delay(600L)

    // 敌人逐个行动：边算边播，确保 executeEnemyAction 始终看到
    // 上一只敌人攻击后的真实血量（修复陈旧血量 bug）
    val sortedEnemies = state.enemyTeam.filter { !it.isDead }
        .sortedByDescending { it.speed }

    for (enemy in sortedEnemies) {
        if (state.playerTeam.all { it.isDead }) break

        val action = viewModel.trialService.executeEnemyAction(
            attacker = enemy,
            playerTeam = state.playerTeam,   // 最新血量
            allyTeam = state.enemyTeam.filter { it.id != enemy.id },
            // 敌方 AI 决策必须走当前战斗的本地 PRNG——在 UI 线程消费
            // 全局 BATTLE 分区会使引擎侧战斗序列不可重放
            rng = currentCombatRng()
        )
        val (animEvent, updatedEnemyTeam) = buildEnemyAnimEvent(
            enemy = enemy,
            action = action,
            enemyTeam = state.enemyTeam,
            playerTeam = state.playerTeam,
            isDefending = state.isDefending
        )
        state.enemyTeam = updatedEnemyTeam
        playEnemyAnimEvent(animEvent, state)
    }

    state.isDefending = mutableSetOf()
    state.currentPlayerIdx = 0
    state.isAnimating = false
    state.currentRound++
    if (state.playerTeam.any { !it.isDead }) {
        state.phase = BattlePhase.PLAYER_TURN
    }
}

/** 敌方行动 → 动画事件：buff 分支同步返回更新后的敌方队伍 */
private fun buildEnemyAnimEvent(
    enemy: Combatant,
    action: EnemyAction,
    enemyTeam: List<Combatant>,
    playerTeam: List<Combatant>,
    isDefending: Set<String>
): Pair<AnimEvent?, List<Combatant>> {
    val skill = action.skill
    val target = action.target

    val buffResult = when (action.actionType) {
        ActionType.BUFF_ALLY -> {
            if (skill != null && target != null) {
                buildEnemyBuffAllyEvent(enemy, skill, target, enemyTeam)
            } else null
        }
        ActionType.BUFF_SELF -> {
            if (skill != null) {
                buildEnemyBuffSelfEvent(enemy, skill, enemyTeam)
            } else null
        }
        else -> null
    }
    if (buffResult != null) return buffResult

    val event = when (action.actionType) {
        ActionType.NONE -> null
        ActionType.ATTACK -> {
            if (skill != null) {
                buildEnemyAttackEvent(enemy, skill, target, playerTeam, isDefending)
            } else null
        }
        ActionType.NORMAL_ATTACK -> {
            if (target != null) {
                buildEnemyNormalAttackEvent(enemy, target, isDefending)
            } else null
        }
        ActionType.BUFF_ALLY, ActionType.BUFF_SELF -> null
    }
    return event to enemyTeam
}

/** 敌方技能攻击事件：AoE 或单体 */
@Suppress("ReturnCount")
private fun buildEnemyAttackEvent(
    enemy: Combatant,
    skill: CombatSkill,
    target: Combatant?,
    playerTeam: List<Combatant>,
    isDefending: Set<String>
): AnimEvent? {
    if (skill.isAoe) {
        // AoE：一次飞行，每目标独立伤害
        val targets = playerTeam.filter { !it.isDead }
        if (targets.isEmpty()) return null
        val results = targets.associate { p ->
            p.id to computeSkillDamage(
                enemy, p, skill,
                isDefending.contains(p.id)
            )
        }
        return AnimEvent.Aoe(AoeAnimationEvent(
            attackerId = enemy.id,
            targetIds = targets.map { it.id },
            damages = results.mapValues { it.value.damage },
            crits = results.mapValues { it.value.isCrit },
            isPhysical = skill.damageType == DamageType.PHYSICAL,
            skillName = skill.name
        ))
    }
    if (target == null) return null
    val result = computeSkillDamage(
        enemy, target, skill,
        isDefending.contains(target.id)
    )
    return AnimEvent.Single(AttackAnimationEvent(
        attackerId = enemy.id,
        targetId = target.id,
        damage = result.damage,
        isCrit = result.isCrit,
        isPhysical = skill.damageType == DamageType.PHYSICAL,
        skillName = skill.name,
        isKill = target.hp - result.damage <= 0
    ))
}

/** 敌方普攻事件 */
private fun buildEnemyNormalAttackEvent(
    enemy: Combatant,
    target: Combatant,
    isDefending: Set<String>
): AnimEvent? {
    val result = computeNormalAttackDamage(
        enemy, target,
        isDefending.contains(target.id)
    )
    return AnimEvent.Single(AttackAnimationEvent(
        attackerId = enemy.id,
        targetId = target.id,
        damage = result.damage,
        isCrit = result.isCrit,
        isPhysical = true,
        isKill = target.hp - result.damage <= 0
    ))
}

/** 敌方单体 Buff/治疗事件：立即应用并返回更新后的敌方队伍 */
@Suppress("UnusedParameter")
private fun buildEnemyBuffAllyEvent(
    enemy: Combatant,
    skill: CombatSkill,
    target: Combatant,
    enemyTeam: List<Combatant>
): Pair<AnimEvent?, List<Combatant>> {
    // Buff 效果立即应用到敌方队伍（不经过动画结算）
    val buffed = applyBuffToTarget(target, skill)
    val updatedTeam = enemyTeam.map {
        if (it.id == target.id) buffed else it
    }
    val healDisplay = (target.maxHp * skill.healPercent).toInt() + skill.healFixed
    return AnimEvent.Single(AttackAnimationEvent(
        attackerId = target.id,
        targetId = target.id,
        damage = healDisplay,
        isCrit = false,
        isPhysical = false,
        isHeal = true,
        skillName = skill.name
    )) to updatedTeam
}

/** 敌方自身 Buff/治疗事件：立即应用并返回更新后的敌方队伍 */
private fun buildEnemyBuffSelfEvent(
    enemy: Combatant,
    skill: CombatSkill,
    enemyTeam: List<Combatant>
): Pair<AnimEvent?, List<Combatant>> {
    val buffed = applyBuffToTarget(enemy, skill)
    val updatedTeam = enemyTeam.map {
        if (it.id == enemy.id) buffed else it
    }
    val healDisplay = (enemy.maxHp * skill.healPercent).toInt() + skill.healFixed
    return AnimEvent.Single(AttackAnimationEvent(
        attackerId = enemy.id,
        targetId = enemy.id,
        damage = healDisplay,
        isCrit = false,
        isPhysical = false,
        isHeal = true,
        skillName = skill.name
    )) to updatedTeam
}

/** 播放敌方动画事件并结算 */
private suspend fun playEnemyAnimEvent(
    animEvent: AnimEvent?,
    state: HeavenlyTrialCombatState
) {
    // 即时播放并结算（更新 playerTeam / enemyTeam）
    when (animEvent) {
        is AnimEvent.Aoe -> {
            playAoeAttackSequence(
                event = animEvent.event,
                cellPositions = state.cellPositions,
                currentAnimState = { state.currentAnimState },
                setAnimState = { state.currentAnimState = it },
                setShaking = { state.shakingTargetIds = it },
                addDamageNumber = {
                    state.activeDamageNumbers = state.activeDamageNumbers + it
                },
                applyAoeResult = { e -> state.applyAoeResult(e) }
            )
        }
        is AnimEvent.Single -> {
            playAttackSequence(
                event = animEvent.event,
                cellPositions = state.cellPositions,
                currentAnimState = { state.currentAnimState },
                setAnimState = { state.currentAnimState = it },
                setShaking = { state.shakingTargetIds = it },
                addDamageNumber = {
                    state.activeDamageNumbers = state.activeDamageNumbers + it
                },
                applyResult = { e -> state.applyAnimationResult(e) }
            )
        }
        null -> { /* 被控或无目标，跳过 */ }
    }
}

/** 战斗背景 + 网格线 */
@Composable
private fun BoxScope.HeavenlyTrialBattleBackdrop() {
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
}

/** 6×6 战斗网格 */
@Composable
private fun HeavenlyTrialBattleGrid(
    state: HeavenlyTrialCombatState,
    currentCombatant: Combatant?,
    random: PresentationRandom
) {
    // 6×6 战斗网格（36格）
    // 单位布局: 己方 col=1(第二列), 敌方 col=4(第五列), rows=1-3
    val gridPositions = remember {
        val map = mutableMapOf<String, Pair<Int, Int>>()
        for (i in 0 until 3) {
            state.playerTeam.getOrNull(i)?.let { map[it.id] = Pair(1, i + 1) }
            state.enemyTeam.getOrNull(i)?.let { map[it.id] = Pair(4, i + 1) }
        }
        map
    }
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 0 until 6) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 6) {
                    HeavenlyTrialBattleGridCell(
                        state = state,
                        gridPositions = gridPositions,
                        currentCombatant = currentCombatant,
                        random = random,
                        row = row,
                        col = col
                    )
                }
            }
        }
    }
}

/** 战斗网格单元格：单位定位 + 选中/飞行/抖动状态 */
@Suppress("CyclomaticComplexMethod")
@Composable
private fun RowScope.HeavenlyTrialBattleGridCell(
    state: HeavenlyTrialCombatState,
    gridPositions: Map<String, Pair<Int, Int>>,
    currentCombatant: Combatant?,
    random: PresentationRandom,
    row: Int,
    col: Int
) {
    val cellCombatant = gridPositions.entries
        .firstOrNull { it.value == Pair(col, row) }
        ?.let { entry ->
            (state.playerTeam + state.enemyTeam).find { it.id == entry.key }
        }
    val isPlayer = cellCombatant?.let { state.playerTeam.any { p -> p.id == it.id } } == true
    val isCurrent = cellCombatant != null &&
        currentCombatant?.id == cellCombatant.id &&
        state.phase == BattlePhase.PLAYER_TURN
    val allySelected = state.selectedTargetId != null &&
        isPlayer &&
        state.selectedIsAlly &&
        state.selectedTargetId == cellCombatant?.id
    val enemySelected = state.selectedTargetId != null &&
        !isPlayer &&
        !state.selectedIsAlly &&
        state.selectedTargetId == cellCombatant?.id
    // 计算本格的飞行动画：仅当本格是当前飞行攻击者时激活，
    // delta = 目标位置 - 本格位置（屏幕像素）
    val flightAnim = if (cellCombatant != null) {
        cellFlightAnim(state = state, cellCombatant = cellCombatant)
    } else {
        FlightAnimState()
    }

    CombatUnitCell(
        combatant = cellCombatant,
        random = random,
        isCurrent = isCurrent,
        isAllySelected = allySelected,
        isEnemySelected = enemySelected,
        isShaking = cellCombatant != null &&
            state.shakingTargetIds.contains(cellCombatant.id),
        flightAnim = flightAnim,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(
                if (cellCombatant != null)
                    Modifier.onGloballyPositioned { coords ->
                        state.cellPositions[cellCombatant.id] =
                            coords.positionInWindow()
                    }
                else Modifier
            ),
        onClick = {
            cellCombatant?.let {
                state.onCellClick(
                    cellCombatant = it,
                    isPlayer = isPlayer,
                    allySelected = allySelected,
                    enemySelected = enemySelected
                )
            }
        }
    )
}

/** 单元格飞行动画计算 */
@Suppress("ReturnCount")
private fun cellFlightAnim(
    state: HeavenlyTrialCombatState,
    cellCombatant: Combatant
): FlightAnimState {
    if (state.currentAnimState.phase == AnimPhase.IDLE ||
        state.currentAnimState.attackerId != cellCombatant.id
    ) {
        return FlightAnimState()
    }
    val selfPos = state.cellPositions[cellCombatant.id]
    val targetPos = state.currentAnimState.overrideEnd
        ?: state.currentAnimState.targetId?.let { state.cellPositions[it] }
    if (selfPos != null && targetPos != null) {
        return FlightAnimState(
            isActive = true,
            phase = state.currentAnimState.phase,
            deltaX = targetPos.x - selfPos.x,
            deltaY = targetPos.y - selfPos.y
        )
    }
    return FlightAnimState()
}

/** 伤害数字覆盖层 */
@Composable
private fun BoxScope.HeavenlyTrialDamageOverlay(state: HeavenlyTrialCombatState) {
    // 动画覆盖层（仅伤害数字；本体飞行由网格格子自身的位移实现）
    if (state.activeDamageNumbers.isNotEmpty()) {
        Box(modifier = Modifier.matchParentSize().zIndex(15f)) {
            // 浮动伤害数字
            state.activeDamageNumbers.forEach { dn ->
                val pos = state.cellPositions[dn.targetId]
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
                                state.activeDamageNumbers =
                                    state.activeDamageNumbers.filter {
                                        it.id != dn.id
                                    }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 回合数 + 关闭按钮 */
@Composable
private fun BoxScope.HeavenlyTrialTopBar(
    currentRound: Int,
    onClose: () -> Unit
) {
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
        onClick = onClose,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .size(32.dp)
    )
}

/** 跳过按钮：随时可点击即时结算 */
@Composable
private fun BoxScope.HeavenlyTrialSkipButton(
    state: HeavenlyTrialCombatState,
    coroutineScope: CoroutineScope
) {
    // 跳过按钮（战斗栏外部右侧，随时可点击即时结算）
    if (state.phase != BattlePhase.WON && state.phase != BattlePhase.LOST) {
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
                        .clickableWithSound {
                            coroutineScope.launch {
                                skipBattle(state)
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
}

/** 跳过战斗即时结算：超轮上限按血量比判定 */
private fun skipBattle(state: HeavenlyTrialCombatState) {
    state.isAnimating = true
    val (finalPlayers, finalEnemies) = simulateInstantResolve(
        state.playerTeam, state.enemyTeam
    )
    state.playerTeam = finalPlayers
    state.enemyTeam = finalEnemies
    state.isAnimating = false
    if (finalPlayers.all { it.isDead }) {
        state.phase = BattlePhase.LOST
    } else if (finalEnemies.all { it.isDead }) {
        state.phase = BattlePhase.WON
    } else {
        // 超轮上限未分胜负：按血量比判定
        val pHp = finalPlayers.sumOf { it.hp }
        val pMax = finalPlayers.sumOf { it.maxHp }
        val eHp = finalEnemies.sumOf { it.hp }
        val eMax = finalEnemies.sumOf { it.maxHp }
        state.phase = if (pMax > 0 && eMax > 0 &&
            pHp.toDouble() / pMax >=
            eHp.toDouble() / eMax
        )
            BattlePhase.WON
        else BattlePhase.LOST
    }
}

/** 战斗栏：防御 + 技能 + 普攻 */
@Composable
private fun BoxScope.HeavenlyTrialBattleBar(
    state: HeavenlyTrialCombatState,
    currentCombatant: Combatant,
    coroutineScope: CoroutineScope
) {
    // 战斗栏（左右留空隙）
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
                "${currentCombatant.name}  HP:${currentCombatant.hp}/${currentCombatant.maxHp}  " +
                    "MP:${currentCombatant.mp}/${currentCombatant.maxMp}",
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
                HeavenlyTrialDefendButton(state = state, currentCombatant = currentCombatant)
                // 技能图标（居中）
                Row(
                    horizontalArrangement = Arrangement.Center
                ) {
                    currentCombatant.skills.forEach { skill ->
                        HeavenlyTrialSkillButton(
                            state = state,
                            currentCombatant = currentCombatant,
                            skill = skill,
                            coroutineScope = coroutineScope
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                // 普攻（右侧）
                HeavenlyTrialNormalAttackButton(
                    state = state,
                    currentCombatant = currentCombatant,
                    coroutineScope = coroutineScope
                )
            }
        }
    }
}

/** 防御按钮 */
@Composable
private fun HeavenlyTrialDefendButton(
    state: HeavenlyTrialCombatState,
    currentCombatant: Combatant
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(start = 15.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickableWithSound(enabled = !state.isAnimating) {
                    state.isDefending = state.isDefending
                        .toMutableSet()
                        .apply { add(currentCombatant.id) }
                    advanceTurn(
                        state.playerTeam.filter { !it.isDead }, state.enemyTeam.filter { !it.isDead },
                        state.currentPlayerIdx, state.isDefending
                    ) { ni, np, nd ->
                        state.currentPlayerIdx = ni
                        state.phase = np
                        state.isDefending = nd
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(id = SpriteResRegistry.resolve("heavenly_trial_defend") ?: 0), "防御",
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        }
        Text("防御", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

/** 技能按钮 */
@Composable
private fun HeavenlyTrialSkillButton(
    state: HeavenlyTrialCombatState,
    currentCombatant: Combatant,
    skill: CombatSkill,
    coroutineScope: CoroutineScope
) {
    val canUse = currentCombatant.mp >= skill.mpCost && skill.currentCooldown <= 0
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(2.dp, if (canUse) GameColors.Gold else GameColors.Border, CircleShape)
            .background(Color.White.copy(alpha = if (canUse) 1f else 0.5f))
            .clickableWithSound(enabled = canUse &&
                state.phase == BattlePhase.PLAYER_TURN &&
                !state.isAnimating
            ) {
                coroutineScope.launch {
                    executePlayerSkillAction(state, currentCombatant, skill)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(skill.name.take(2), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text("${skill.mpCost}灵力", fontSize = 6.sp, color = Color.Black)
        }
    }
}

/** 玩家技能执行：扣 MP → 分支播放/结算 → 回合推进 */
private suspend fun executePlayerSkillAction(
    state: HeavenlyTrialCombatState,
    attacker: Combatant,
    skill: CombatSkill
) {
    state.isAnimating = true
    // 先扣 MP
    val attackerIdx = state.playerTeam
        .indexOfFirst {
            it.id == attacker.id
        }
    if (attackerIdx >= 0) {
        state.playerTeam = state.playerTeam.mapIndexed { i, c ->
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
            playPlayerAoeAttack(state, attacker, skill)
        } else {
            // AoE 辅助/治疗：立即应用
            val result = executePlayerSkill(
                attacker, skill,
                state.selectedTargetId, state.selectedIsAlly,
                state.playerTeam, state.enemyTeam, state.isDefending
            )
            state.playerTeam = result.first
            state.enemyTeam = result.second
        }
    } else {
        if (isAttackSkill) {
            playPlayerSingleAttack(state, attacker, skill)
        } else {
            // Buff/Heal 技能：立即应用
            val result = executePlayerSkill(
                attacker, skill,
                state.selectedTargetId, state.selectedIsAlly,
                state.playerTeam, state.enemyTeam, state.isDefending
            )
            state.playerTeam = result.first
            state.enemyTeam = result.second
        }
    }
    state.selectedTargetId = null
    state.selectedIsAlly = false
    state.isAnimating = false
    advanceTurn(
        state.playerTeam.filter { !it.isDead }, state.enemyTeam.filter { !it.isDead },
        state.currentPlayerIdx, state.isDefending
    ) { ni, np, nd ->
        state.currentPlayerIdx = ni
        state.phase = np
        state.isDefending = nd
    }
}

/** 玩家 AoE 技能攻击：一次飞行 + 全体同时受击 */
private suspend fun playPlayerAoeAttack(
    state: HeavenlyTrialCombatState,
    attacker: Combatant,
    skill: CombatSkill
) {
    val targets = state.enemyTeam.filter { !it.isDead }
    if (targets.isNotEmpty()) {
        // AoE：一次飞行 + 全体同时受击
        val results = targets.associate { t ->
            t.id to computeSkillDamage(
                attacker, t,
                skill, false
            )
        }
        playAoeAttackSequence(
            AoeAnimationEvent(
                attackerId = attacker.id,
                targetIds = targets.map { it.id },
                damages = results.mapValues { it.value.damage },
                crits = results.mapValues { it.value.isCrit },
                isPhysical = skill.damageType ==
                    DamageType.PHYSICAL,
                skillName = skill.name
            ),
            state.cellPositions,
            { state.currentAnimState },
            { state.currentAnimState = it },
            { state.shakingTargetIds = it },
            { state.activeDamageNumbers =
                state.activeDamageNumbers + it },
            { e -> state.applyAoeResult(e) }
        )
    }
}

/** 玩家单体技能攻击：飞行动画 + 命中结算 */
private suspend fun playPlayerSingleAttack(
    state: HeavenlyTrialCombatState,
    attacker: Combatant,
    skill: CombatSkill
) {
    val target = if (
        !state.selectedIsAlly &&
        state.selectedTargetId != null
    )
        state.enemyTeam.find {
            it.id == state.selectedTargetId
        }
    else state.enemyTeam
        .filter { !it.isDead }
        .randomOrNull(currentCombatRng())
    if (target != null) {
        val result = computeSkillDamage(
            attacker, target,
            skill, false
        )
        playAttackSequence(
            AttackAnimationEvent(
                attackerId = attacker.id,
                targetId = target.id,
                damage = result.damage,
                isCrit = result.isCrit,
                isPhysical = skill.damageType ==
                    DamageType.PHYSICAL,
                skillName = skill.name,
                isKill = target.hp - result.damage <= 0
            ),
            state.cellPositions,
            { state.currentAnimState },
            { state.currentAnimState = it },
            { state.shakingTargetIds = it },
            { state.activeDamageNumbers =
                state.activeDamageNumbers + it },
            { e -> state.applyAnimationResult(e) }
        )
    }
}

/** 普攻按钮 */
@Composable
private fun HeavenlyTrialNormalAttackButton(
    state: HeavenlyTrialCombatState,
    currentCombatant: Combatant,
    coroutineScope: CoroutineScope
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(end = 15.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickableWithSound(enabled = !state.isAnimating) {
                    coroutineScope.launch {
                        playPlayerNormalAttack(state, currentCombatant)
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

/** 玩家普攻：目标选取 + 飞行动画 + 回合推进 */
private suspend fun playPlayerNormalAttack(
    state: HeavenlyTrialCombatState,
    attacker: Combatant
) {
    state.isAnimating = true
    val target = if (
        !state.selectedIsAlly &&
        state.selectedTargetId != null
    )
        state.enemyTeam.find {
            it.id == state.selectedTargetId
        }
    else state.enemyTeam
        .filter { !it.isDead }
        .randomOrNull(currentCombatRng())
    if (target != null) {
        val result = computeNormalAttackDamage(
            attacker, target,
            state.isDefending.contains(target.id)
        )
        playAttackSequence(
            AttackAnimationEvent(
                attackerId = attacker.id,
                targetId = target.id,
                damage = result.damage,
                isCrit = result.isCrit,
                isPhysical = true,
                isKill = target.hp - result.damage <= 0
            ),
            state.cellPositions,
            { state.currentAnimState },
            { state.currentAnimState = it },
            { state.shakingTargetIds = it },
            { state.activeDamageNumbers =
                state.activeDamageNumbers + it },
            { e -> state.applyAnimationResult(e) }
        )
    }
    state.selectedTargetId = null
    state.selectedIsAlly = false
    state.isAnimating = false
    advanceTurn(
        state.playerTeam.filter { !it.isDead }, state.enemyTeam.filter { !it.isDead },
        state.currentPlayerIdx, state.isDefending
    ) { ni, np, nd ->
        state.currentPlayerIdx = ni
        state.phase = np
        state.isDefending = nd
    }
}

/** P-2：天道试炼战斗结算面板。 */
@Composable
private fun BattleResultPanel(
    showResult: Boolean,
    won: Boolean,
    durationSeconds: Long,
    totalRounds: Int,
    onDismiss: () -> Unit
) {
    if (showResult) {
        HeavenlyTrialBattleResultDialog(
            won = won,
            durationSeconds = durationSeconds,
            totalRounds = totalRounds,
            onDismiss = onDismiss
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
