package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import androidx.compose.ui.geometry.Offset
import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.engine.domain.battle.BattleAI
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.DeterministicRng
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 天道试炼战斗动画守卫测试（2026-08-13，EngineTween 批次 1b 决策依据）。
 *
 * ## 背景
 * [playAttackSequence] / [playAoeAttackSequence] 经评估**明确不迁移**到 EngineTween/Timeline
 * （详见 HeavenlyTrialAnimation.kt KDoc 三条结论：结构性结算耦合 / 机制不匹配 /
 * Compose 生命周期适配）。本文件为该决策建立可执行守卫：未来任何改动（含迁移尝试）
 * 不得破坏以下不变量，破坏即失败并给出改动方向。
 *
 * ## 守卫不变量
 * 1. **结算时机**：结算回调（applyResult/applyAoeResult）必须位于视觉相位序列末尾——
 *    100 次重复逐帧回调顺序全等（MOVE→命中+抖动+伤害→返回→结算→IDLE）。
 * 2. **动画路径零 RNG**：动画函数不得消耗战斗 PRNG——逐次快照前后全等（100 次）。
 * 3. **动画 ≈ 直结**：同种子下，动画版敌人回合与直结版最终双方状态 + RNG 消耗序列
 *    全等（100 种子 × 双对局谱：满血 / 残血触发保命-治疗-斩杀分支）。
 * 4. **确定性**：同种子两次动画运行结算序列全等。
 *
 * 迭代数说明（2026-08-13 测试性能修正）：确定性偏差是**系统性差异**——任一次
 * 迭代即可捕获（1000 → 100 不降低守卫强度）；100 种子已覆盖双对局谱全部
 * 分支（保命/治疗/斩杀）。原 1000 迭代在虚拟时钟下逼近 2h 超时且墙钟
 * 数十分钟，全量串行测试被拖垮。
 *
 * 直结版镜像精确复制 HeavenlyTrialCombatScreen.ENEMY_TURN 语义，含三个已知怪癖
 * （改动屏幕逻辑须同步镜像，否则守卫失败）：
 * - `sortedEnemies` 为回合前快照，回合中 buff 导致的属性变化不影响本回合行动顺序
 * - BUFF_ALLY/BUFF_SELF 的 buff 立即生效（不经动画结算），且境界参数走默认值 9-0
 * - team 型动作（SKILL_HEAL_TEAM/SKILL_BUFF_TEAM）target 为 null → 事件被静默丢弃
 */
class HeavenlyTrialAnimationGuardTest {

    // ═══════════════════════════════════════════
    // 不变量 1 + 2：动画回调顺序与零 RNG
    // ═══════════════════════════════════════════

    @Test
    fun `playAttackSequence - 100 次回调顺序与结算时机全等且 RNG 零消耗`() = runTest(timeout = 30.minutes) {
        val rng = DeterministicRng(42L)
        val event = AttackAnimationEvent(
            attackerId = "e1", targetId = "p1",
            damage = 50, isCrit = false, isPhysical = true, isKill = false
        )
        repeat(100) { i ->
            val snapshotBefore = rng.snapshot()
            val trace = traceOf { recorder ->
                playAttackSequence(
                    event = event,
                    cellPositions = CELL_POSITIONS,
                    currentAnimState = { recorder.animState },
                    setAnimState = { recorder.animState = it; recorder.trace += "ANIM:${it.phase}" },
                    setShaking = { recorder.trace += "SHAKE:" + it.sorted().joinToString() },
                    addDamageNumber = { recorder.trace += "DMG:${it.targetId}:${it.damage}" },
                    applyResult = { recorder.trace += "APPLY:${it.targetId}:${it.damage}" }
                )
            }
            assertEquals(
                "i=$i 结算回调必须位于视觉相位序列末尾",
                ATTACK_FULL_TRACE, trace
            )
            assertEquals(
                "i=$i 动画路径不得消耗战斗 RNG",
                snapshotBefore, rng.snapshot()
            )
        }
    }

    @Test
    fun `playAoeAttackSequence - 100 次回调顺序与结算时机全等且 RNG 零消耗`() = runTest(timeout = 30.minutes) {
        val rng = DeterministicRng(42L)
        val event = AoeAnimationEvent(
            attackerId = "e1",
            targetIds = listOf("p1", "p2", "p3"),
            damages = mapOf("p1" to 50, "p2" to 30, "p3" to 20),
            crits = mapOf("p1" to false, "p2" to false, "p3" to false),
            isPhysical = true
        )
        repeat(100) { i ->
            val snapshotBefore = rng.snapshot()
            val trace = traceOf { recorder ->
                playAoeAttackSequence(
                    event = event,
                    cellPositions = CELL_POSITIONS,
                    currentAnimState = { recorder.animState },
                    setAnimState = { recorder.animState = it; recorder.trace += "ANIM:${it.phase}" },
                    setShaking = { recorder.trace += "SHAKE:" + it.sorted().joinToString() },
                    addDamageNumber = { recorder.trace += "DMG:${it.targetId}:${it.damage}" },
                    applyAoeResult = { recorder.trace += "APPLY:${it.targetIds.size}" }
                )
            }
            assertEquals(
                "i=$i 结算回调必须位于全体视觉相位之后",
                AOE_FULL_TRACE, trace
            )
            assertEquals(
                "i=$i 动画路径不得消耗战斗 RNG",
                snapshotBefore, rng.snapshot()
            )
        }
    }

    @Test
    fun `playAttackSequence - 治疗与无位移兜底分支按既有时序结算`() = runTest {
        // 治疗分支：无位移，仅绿色数字 → 结算 → IDLE
        val healEvent = AttackAnimationEvent(
            attackerId = "e1", targetId = "p1",
            damage = 25, isCrit = false, isPhysical = false, isHeal = true
        )
        assertEquals(
            listOf("DMG:p1:25", "APPLY:p1:25", "ANIM:IDLE"),
            traceOf { recorder ->
                playAttackSequence(
                    event = healEvent, cellPositions = CELL_POSITIONS,
                    currentAnimState = { recorder.animState },
                    setAnimState = { recorder.animState = it; recorder.trace += "ANIM:${it.phase}" },
                    setShaking = { recorder.trace += "SHAKE:" + it.sorted().joinToString() },
                    addDamageNumber = { recorder.trace += "DMG:${it.targetId}:${it.damage}" },
                    applyResult = { recorder.trace += "APPLY:${it.targetId}:${it.damage}" }
                )
            }
        )

        // 无位移兜底：立即结算，无动画相位
        val noPositionEvent = AttackAnimationEvent(
            attackerId = "e1", targetId = "p1",
            damage = 50, isCrit = false, isPhysical = true
        )
        assertEquals(
            listOf("APPLY:p1:50"),
            traceOf { recorder ->
                playAttackSequence(
                    event = noPositionEvent, cellPositions = emptyMap(),
                    currentAnimState = { recorder.animState },
                    setAnimState = { recorder.animState = it; recorder.trace += "ANIM:${it.phase}" },
                    setShaking = { recorder.trace += "SHAKE:" + it.sorted().joinToString() },
                    addDamageNumber = { recorder.trace += "DMG:${it.targetId}:${it.damage}" },
                    applyResult = { recorder.trace += "APPLY:${it.targetId}:${it.damage}" }
                )
            }
        )

        // AoE 无位移兜底：一次性全体结算
        val aoeNoPosition = AoeAnimationEvent(
            attackerId = "e1",
            targetIds = listOf("p1", "p2", "p3"),
            damages = mapOf("p1" to 50, "p2" to 30, "p3" to 20),
            crits = mapOf("p1" to false, "p2" to false, "p3" to false),
            isPhysical = true
        )
        assertEquals(
            listOf("APPLY:3"),
            traceOf { recorder ->
                playAoeAttackSequence(
                    event = aoeNoPosition, cellPositions = emptyMap(),
                    currentAnimState = { recorder.animState },
                    setAnimState = { recorder.animState = it; recorder.trace += "ANIM:${it.phase}" },
                    setShaking = { recorder.trace += "SHAKE:" + it.sorted().joinToString() },
                    addDamageNumber = { recorder.trace += "DMG:${it.targetId}:${it.damage}" },
                    applyAoeResult = { recorder.trace += "APPLY:${it.targetIds.size}" }
                )
            }
        )
    }

    // ═══════════════════════════════════════════
    // 不变量 3：动画路径 ≈ 直结路径（改动前后 1000 次结算序列全等）
    // ═══════════════════════════════════════════

    @Test
    fun `enemyTurn - 动画路径与直结路径 100 种子结算序列与 RNG 消耗序列全等`() = runTest(timeout = 30.minutes) {
        val fixtures = listOf(
            PLAYER_TEAM to ENEMY_TEAM,
            PLAYER_TEAM_WOUNDED to ENEMY_TEAM_WOUNDED
        )
        repeat(100) { i ->
            val (players, enemies) = fixtures[i % fixtures.size]
            val seed = 1000L + i
            val animated = runEnemyTurnAnimated(players, enemies, DeterministicRng(seed))
            val direct = runEnemyTurnDirect(players, enemies, DeterministicRng(seed))

            assertEquals("i=$i 最终玩家队伍全等", direct.players, animated.players)
            assertEquals("i=$i 最终敌方队伍全等", direct.enemies, animated.enemies)
            assertEquals("i=$i RNG 消耗序列全等", direct.rngSnapshots, animated.rngSnapshots)
        }
    }

    // ═══════════════════════════════════════════
    // 不变量 4：确定性（同种子两次运行）
    // ═══════════════════════════════════════════

    @Test
    fun `enemyTurn - 同种子两次动画运行结算序列全等（确定性守卫）`() = runTest(timeout = 30.minutes) {
        repeat(100) { i ->
            val seed = 2000L + i
            val first = runEnemyTurnAnimated(PLAYER_TEAM, ENEMY_TEAM, DeterministicRng(seed))
            val second = runEnemyTurnAnimated(PLAYER_TEAM, ENEMY_TEAM, DeterministicRng(seed))

            assertEquals("i=$i 玩家队伍确定性", first.players, second.players)
            assertEquals("i=$i 敌方队伍确定性", first.enemies, second.enemies)
            assertEquals("i=$i RNG 消耗序列确定性", first.rngSnapshots, second.rngSnapshots)
        }
    }

    // ═══════════════════════════════════════════
    // 敌人回合模拟（动画版 / 直结版镜像）
    // ═══════════════════════════════════════════

    /**
     * 动画版敌人回合：与 HeavenlyTrialCombatScreen.ENEMY_TURN 逐行镜像，
     * 结算通过 playAttackSequence/playAoeAttackSequence 的 applyResult 回调注入。
     */
    private suspend fun runEnemyTurnAnimated(
        players: List<Combatant>,
        enemies: List<Combatant>,
        rng: DeterministicRng
    ): EnemyTurnTrace {
        var playerTeam = players
        var enemyTeam = enemies
        var animState = AttackAnimState()
        val snapshots = mutableListOf<Long>()
        val sortedEnemies = enemies.filter { !it.isDead }.sortedByDescending { it.speed }
        for (enemy in sortedEnemies) {
            if (playerTeam.all { it.isDead }) break
            val action = BattleAI.decideAction(
                enemy, enemyTeam.filter { it.id != enemy.id }, playerTeam, rng
            )
            val (animEvent, updatedEnemies) = buildEnemyAnimEvent(action, enemy, playerTeam, enemyTeam, rng)
            enemyTeam = updatedEnemies
            when (animEvent) {
                is AnimEvent.Aoe -> playAoeAttackSequence(
                    event = animEvent.event,
                    cellPositions = CELL_POSITIONS,
                    currentAnimState = { animState },
                    setAnimState = { animState = it },
                    setShaking = {},
                    addDamageNumber = {},
                    applyAoeResult = { e ->
                        val (p, en) = applyAoeResult(playerTeam, enemyTeam, e)
                        playerTeam = p
                        enemyTeam = en
                    }
                )
                is AnimEvent.Single -> playAttackSequence(
                    event = animEvent.event,
                    cellPositions = CELL_POSITIONS,
                    currentAnimState = { animState },
                    setAnimState = { animState = it },
                    setShaking = {},
                    addDamageNumber = {},
                    applyResult = { e ->
                        val (p, en) = applySingleResult(playerTeam, enemyTeam, e)
                        playerTeam = p
                        enemyTeam = en
                    }
                )
                null -> Unit
            }
            snapshots += rng.snapshot()
        }
        return EnemyTurnTrace(playerTeam, enemyTeam, snapshots)
    }

    /**
     * 直结版敌人回合：同一事件计算与结算函数，跳过动画层直接应用——
     * 代表"改动前"的纯结算路径，与动画版仅差动画包装。
     */
    private suspend fun runEnemyTurnDirect(
        players: List<Combatant>,
        enemies: List<Combatant>,
        rng: DeterministicRng
    ): EnemyTurnTrace {
        var playerTeam = players
        var enemyTeam = enemies
        val snapshots = mutableListOf<Long>()
        val sortedEnemies = enemies.filter { !it.isDead }.sortedByDescending { it.speed }
        for (enemy in sortedEnemies) {
            if (playerTeam.all { it.isDead }) break
            val action = BattleAI.decideAction(
                enemy, enemyTeam.filter { it.id != enemy.id }, playerTeam, rng
            )
            val (animEvent, updatedEnemies) = buildEnemyAnimEvent(action, enemy, playerTeam, enemyTeam, rng)
            enemyTeam = updatedEnemies
            when (animEvent) {
                is AnimEvent.Aoe -> {
                    val (p, en) = applyAoeResult(playerTeam, enemyTeam, animEvent.event)
                    playerTeam = p
                    enemyTeam = en
                }
                is AnimEvent.Single -> {
                    val (p, en) = applySingleResult(playerTeam, enemyTeam, animEvent.event)
                    playerTeam = p
                    enemyTeam = en
                }
                null -> Unit
            }
            snapshots += rng.snapshot()
        }
        return EnemyTurnTrace(playerTeam, enemyTeam, snapshots)
    }

    /**
     * 把 BattleAI 决策组装为 AnimEvent（屏幕 when 分支的精确镜像）。
     *
     * 镜像细节（改动屏幕逻辑须同步）：BUFF_ALLY/BUFF_SELF 的 buff 立即生效且境界
     * 走默认值 9-0；team 型动作 target 为 null → 事件丢弃（buff 不生效）。
     */
    private fun buildEnemyAnimEvent(
        action: BattleAI.AIAction,
        enemy: Combatant,
        playerTeam: List<Combatant>,
        enemyTeam: List<Combatant>,
        rng: DeterministicRng
    ): Pair<AnimEvent?, List<Combatant>> {
        var updatedEnemies = enemyTeam
        val event: AnimEvent? = when (action.actionType) {
            // ── 屏幕 ActionType.ATTACK：AoE 技能 ──
            BattleAI.AIActionType.SKILL_ATTACK_AOE -> {
                val skill = action.skill
                val targets = playerTeam.filter { !it.isDead }
                if (skill == null || targets.isEmpty()) null
                else {
                    val results = targets.associate { p ->
                        p.id to computeSkillDamage(enemy, p, skill, isDefending = false, rng = rng)
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
            }
            // ── 屏幕 ActionType.ATTACK：单体技能 ──
            BattleAI.AIActionType.SKILL_ATTACK_SINGLE -> buildSingleAttack(action, enemy, rng)
            // ── 屏幕 ActionType.NORMAL_ATTACK ──
            BattleAI.AIActionType.NORMAL_ATTACK -> buildNormalAttack(action, enemy, rng)
            // ── 屏幕 ActionType.BUFF_ALLY（含 team 型：target 为 null 被丢弃）──
            BattleAI.AIActionType.SKILL_HEAL_ALLY,
            BattleAI.AIActionType.SKILL_BUFF_ALLY,
            BattleAI.AIActionType.SKILL_HEAL_TEAM,
            BattleAI.AIActionType.SKILL_BUFF_TEAM -> {
                val skill = action.skill
                val target = action.target
                if (skill != null && target != null) {
                    val buffed = applyBuffToTarget(target, skill)
                    updatedEnemies = updatedEnemies.map { if (it.id == target.id) buffed else it }
                    healDisplayEvent(target, skill, displayId = target.id)
                } else null
            }
            // ── 屏幕 ActionType.BUFF_SELF ──
            BattleAI.AIActionType.SKILL_HEAL_SELF,
            BattleAI.AIActionType.SKILL_BUFF_SELF -> {
                val skill = action.skill
                if (skill != null) {
                    val buffed = applyBuffToTarget(enemy, skill)
                    updatedEnemies = updatedEnemies.map { if (it.id == enemy.id) buffed else it }
                    healDisplayEvent(enemy, skill, displayId = enemy.id)
                } else null
            }
            BattleAI.AIActionType.NONE -> null
        }
        return event to updatedEnemies
    }

    private fun buildSingleAttack(
        action: BattleAI.AIAction,
        enemy: Combatant,
        rng: DeterministicRng
    ): AnimEvent? {
        val skill = action.skill
        val target = action.target
        if (skill == null || target == null) return null
        val result = computeSkillDamage(enemy, target, skill, isDefending = false, rng = rng)
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

    private fun buildNormalAttack(
        action: BattleAI.AIAction,
        enemy: Combatant,
        rng: DeterministicRng
    ): AnimEvent? {
        val target = action.target ?: return null
        val result = computeNormalAttackDamage(enemy, target, isDefending = false, rng = rng)
        return AnimEvent.Single(AttackAnimationEvent(
            attackerId = enemy.id,
            targetId = target.id,
            damage = result.damage,
            isCrit = result.isCrit,
            isPhysical = true,
            isKill = target.hp - result.damage <= 0
        ))
    }

    /** 屏幕 BUFF 分支的绿色治疗数字（HP 已在 buff 预应用中生效，结算时 isHeal 跳过） */
    private fun healDisplayEvent(
        combatant: Combatant,
        skill: CombatSkill,
        displayId: String
    ): AnimEvent.Single {
        val healDisplay = (combatant.maxHp * skill.healPercent).toInt() + skill.healFixed
        return AnimEvent.Single(AttackAnimationEvent(
            attackerId = displayId,
            targetId = displayId,
            damage = healDisplay,
            isCrit = false,
            isPhysical = false,
            isHeal = true,
            skillName = skill.name
        ))
    }

    /** 单体事件结算（镜像屏幕 applyAnimationResult：治疗跳过） */
    private fun applySingleResult(
        players: List<Combatant>,
        enemies: List<Combatant>,
        event: AttackAnimationEvent
    ): Pair<List<Combatant>, List<Combatant>> {
        if (event.isHeal) return players to enemies
        val isTargetPlayer = players.any { it.id == event.targetId }
        return if (isTargetPlayer) {
            players.map { c ->
                if (c.id == event.targetId) c.copy(hp = (c.hp - event.damage).coerceAtLeast(0)) else c
            } to enemies
        } else {
            players to enemies.map { c ->
                if (c.id == event.targetId) c.copy(hp = (c.hp - event.damage).coerceAtLeast(0)) else c
            }
        }
    }

    /** AoE 事件结算（镜像屏幕 applyAoeResult：治疗型预留给目标所在队伍） */
    private fun applyAoeResult(
        players: List<Combatant>,
        enemies: List<Combatant>,
        event: AoeAnimationEvent
    ): Pair<List<Combatant>, List<Combatant>> {
        var newPlayers = players
        var newEnemies = enemies
        val damages = event.damages
        if (event.isHeal) {
            val isTargetPlayer = newPlayers.any { it.id in event.targetIds }
            if (isTargetPlayer) {
                newPlayers = newPlayers.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp + d).coerceAtMost(c.maxHp))
                }
            } else {
                newEnemies = newEnemies.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp + d).coerceAtMost(c.maxHp))
                }
            }
        } else {
            val damageOnPlayers = event.targetIds.any { id -> newPlayers.any { it.id == id } }
            if (damageOnPlayers) {
                newPlayers = newPlayers.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp - d).coerceAtLeast(0))
                }
            }
            val damageOnEnemies = event.targetIds.any { id -> newEnemies.any { it.id == id } }
            if (damageOnEnemies) {
                newEnemies = newEnemies.map { c ->
                    val d = damages[c.id] ?: return@map c
                    c.copy(hp = (c.hp - d).coerceAtLeast(0))
                }
            }
        }
        return newPlayers to newEnemies
    }

    /** 单次敌人回合的结果轨迹：最终双方状态 + RNG 消耗序列 */
    private data class EnemyTurnTrace(
        val players: List<Combatant>,
        val enemies: List<Combatant>,
        val rngSnapshots: List<Long>
    )

    // ═══════════════════════════════════════════
    // 工具与固定装置
    // ═══════════════════════════════════════════

    /** 回调轨迹记录器 */
    private class TraceRecorder {
        val trace = mutableListOf<String>()
        var animState = AttackAnimState()
    }

    /** 运行一段动画并返回回调轨迹 */
    private suspend fun traceOf(block: suspend (TraceRecorder) -> Unit): List<String> {
        val recorder = TraceRecorder()
        block(recorder)
        return recorder.trace
    }

    private companion object {
        /** 玩家角色构造（玩家无技能） */
        private fun player(id: String, hp: Int, maxHp: Int, speed: Int): Combatant = Combatant(
            id = id, name = "试炼弟子",
            hp = hp, maxHp = maxHp, mp = 200, maxMp = 200,
            physicalAttack = 120, magicAttack = 80,
            physicalDefense = 60, magicDefense = 50,
            speed = speed, critRate = 0.1,
            skills = emptyList(),
            realm = 9, realmName = "炼虚"
        )

        /** 敌方角色构造（固定技能组） */
        private fun enemy(id: String, hp: Int, speed: Int): Combatant = Combatant(
            id = id, name = "试炼魔将",
            hp = hp, maxHp = 600, mp = 200, maxMp = 200,
            physicalAttack = 150, magicAttack = 100,
            physicalDefense = 70, magicDefense = 60,
            speed = speed, critRate = 0.1,
            skills = ENEMY_SKILLS,
            realm = 9, realmName = "炼虚", realmLayer = 0
        )

        /** 六名角色占位（让动画走完整飞行相位） */
        val CELL_POSITIONS: Map<String, Offset> = mapOf(
            "p1" to Offset(1f, 1f), "p2" to Offset(2f, 1f), "p3" to Offset(3f, 1f),
            "e1" to Offset(5f, 1f), "e2" to Offset(6f, 1f), "e3" to Offset(7f, 1f)
        )

        /** 敌方技能组：单体攻击 / AoE 攻击 / 治疗盟友 / 自身增益 */
        val ENEMY_SKILLS = listOf(
            CombatSkill(
                name = "烈阳掌", damageType = DamageType.PHYSICAL,
                damageMultiplier = 1.5, mpCost = 0, cooldown = 0
            ),
            CombatSkill(
                name = "焚天裂地", damageType = DamageType.PHYSICAL,
                damageMultiplier = 1.2, mpCost = 0, cooldown = 0, isAoe = true
            ),
            CombatSkill(
                name = "回春术", damageType = DamageType.PHYSICAL,
                damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
                healPercent = 0.4, healType = HealType.HP, targetScope = "ally"
            ),
            CombatSkill(
                name = "疾风符", damageType = DamageType.PHYSICAL,
                damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
                buffType = BuffType.SPEED_BOOST, buffValue = 0.5, buffDuration = 3,
                targetScope = "self"
            )
        )

        /** 满血对局：覆盖 单体攻击 / AoE / 普攻 / 团队增益 分支 */
        val PLAYER_TEAM = listOf(
            player("p1", hp = 500, maxHp = 500, speed = 100),
            player("p2", hp = 400, maxHp = 500, speed = 90),
            player("p3", hp = 300, maxHp = 500, speed = 80)
        )
        val ENEMY_TEAM = listOf(
            enemy("e1", hp = 600, speed = 120),
            enemy("e2", hp = 600, speed = 100),
            enemy("e3", hp = 600, speed = 60)
        )

        /** 残血对局：敌方残血触发保命/治疗，玩家残血触发斩杀 */
        val PLAYER_TEAM_WOUNDED = listOf(
            player("p1", hp = 500, maxHp = 500, speed = 100),
            player("p2", hp = 400, maxHp = 500, speed = 90),
            player("p3", hp = 100, maxHp = 500, speed = 80)
        )
        val ENEMY_TEAM_WOUNDED = listOf(
            enemy("e1", hp = 600, speed = 120),
            enemy("e2", hp = 100, speed = 100),
            enemy("e3", hp = 600, speed = 60)
        )

        /** 单体完整路径期望轨迹（结算必须位于视觉相位末尾） */
        val ATTACK_FULL_TRACE = listOf(
            "ANIM:MOVE_TO_TARGET", "ANIM:IMPACT", "SHAKE:p1", "DMG:p1:50",
            "SHAKE:", "ANIM:RETURN_TO_START", "APPLY:p1:50", "ANIM:IDLE"
        )

        /** AoE 完整路径期望轨迹（全体抖动+全体伤害 → 返回 → 一次性结算；
         *  SHAKE 记录格式 = "SHAKE:" + joinToString()（默认分隔符 ", "）） */
        val AOE_FULL_TRACE = listOf(
            "ANIM:MOVE_TO_TARGET", "ANIM:IMPACT", "SHAKE:p1, p2, p3",
            "DMG:p1:50", "DMG:p2:30", "DMG:p3:20",
            "SHAKE:", "ANIM:RETURN_TO_START", "APPLY:3", "ANIM:IDLE"
        )
    }
}
