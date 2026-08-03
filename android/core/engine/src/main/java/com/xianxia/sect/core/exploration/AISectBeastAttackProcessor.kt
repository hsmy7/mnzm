package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.domain.battle.EncounterBattleService
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI 宗门妖兽攻击处理器。
 *
 * 设计为两阶段：
 * 1. [precomputeTargets] — 月度结算前段，只计算 AI 进攻目标并写入 [aiSectBeastDirectTargets]（无战斗）
 *    巡视楼运行时会读取该字段，当巡逻队和 AI 同时瞄准同一妖兽时触发遭遇战。
 * 2. [processRemainingTargets] — 月度结算后段，处理巡视楼未处理的 AI 目标 → AI 直接进攻妖兽。
 *
 * 此处理器在 [stateStore.update] 事务内调用，直接修改 [MutableGameState]。
 * 不涉及玩家宗门——玩家宗门的妖兽攻击处理由 [BeastAttackDetector] + [PendingBeastAttack] 系统负责。
 */
@Singleton
class AISectBeastAttackProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val battleSystem: BattleSystem,
    private val rngManager: GameRngManager,
    private val encounterBattleService: EncounterBattleService
) {
    /**
     * Phase 1: 预计算 AI 进攻目标。
     *
     * 遍历所有活跃妖兽，取最近的 2 个 AI 宗门，检查最近的 1 个是否满足进攻条件，
     * 通过则写入 [GameData.aiSectBeastDirectTargets]。
     * 巡视楼运行时会读取此字段处理冲突。
     *
     * @param state  可变状态
     * @param year   当前游戏年
     * @param month  当前游戏月
     */
    fun precomputeTargets(state: MutableGameState, year: Int, month: Int) {
        if (!ManualDatabase.isInitialized) return

        val gd = state.gameData
        // 跳过已被玩家锁定（弹窗打开中）和已击败/过期的妖兽
        val activeBeasts = gd.worldLevels.filter { level ->
            level.type == LevelType.BEAST && !level.defeated
                && !level.checkExpired(year, month)
                && level.id !in gd.lockedBeastIds
        }.sortedBy { it.id }
        if (activeBeasts.isEmpty()) return

        val rng = rngManager.getRng(RngPartition.EXPLORATION)
        val absoluteMonth = year * 12 + month

        for (beast in activeBeasts) {
            val qualifiedAiIds = collectQualifiedAiForBeast(state, gd, beast, rng, absoluteMonth)
            if (qualifiedAiIds.isNotEmpty()) {
                val existing = state.gameData.aiSectBeastDirectTargets.toMutableMap()
                existing[beast.id] = qualifiedAiIds
                state.gameData = state.gameData.copy(aiSectBeastDirectTargets = existing)
            }
        }
        cleanExpiredSkipCooldowns(state, absoluteMonth)
    }

    /**
     * 收集对指定妖兽有进攻资格的 AI 宗门 ID 列表（最多 2 个）。
     * 取最近 2 个 AI 宗门，逐个检查冷却/弟子/战力/概率。
     */
    private fun collectQualifiedAiForBeast(
        state: MutableGameState, gd: com.xianxia.sect.core.model.GameData,
        beast: WorldLevel, rng: com.xianxia.sect.core.util.DeterministicRng,
        absoluteMonth: Int
    ): List<String> {
        val aiCandidates = gd.worldMapSects
            .filter { !it.isPlayerSect && !it.isPlayerOccupied }
            .mapNotNull { sect ->
                val dx = beast.x - sect.x
                val dy = beast.y - sect.y
                val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (dist.isNaN() || dist.isInfinite()) null else sect to dist
            }
            .sortedBy { it.second }
            .take(2)

        val qualified = mutableListOf<String>()
        for ((sect, _) in aiCandidates) {
            if (qualified.size >= 2) break

            val cooldown = gd.aiSectBeastSkipCooldowns[sect.id] ?: 0
            if (cooldown >= absoluteMonth) continue

            val aiDisciples = gd.aiSectDisciples[sect.id] ?: continue
            val aliveDisciples = aiDisciples.filter { it.isAlive }
            if (aliveDisciples.size < GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK) continue

            val aiPower = SectCombatPowerCalculator.calculateSectPower(aliveDisciples)
            val beastPower = SectCombatPowerCalculator.calculateBeastCombatPower(
                maxHp = beast.beastMaxHp, physicalAttack = beast.beastPhysicalAttack,
                magicAttack = beast.beastMagicAttack, physicalDefense = beast.beastPhysicalDefense,
                magicDefense = beast.beastMagicDefense, speed = beast.beastSpeed
            )

            val canAttack = if (beastPower <= 0) true
            else if (aiPower <= beastPower) { recordSkipCooldown(state, sect.id, absoluteMonth); false }
            else {
                val prob = min((aiPower.toDouble() / beastPower.toDouble() - 1.0) * 0.3 + 0.3, 0.9)
                if (rng.nextDouble() < prob) true
                else { recordSkipCooldown(state, sect.id, absoluteMonth); false }
            }
            if (canAttack && sect.id !in qualified) qualified.add(sect.id)
        }
        return qualified
    }

    /**
     * 清理超过 12 个月的冷却记录。
     */
    private fun cleanExpiredSkipCooldowns(state: MutableGameState, absoluteMonth: Int) {
        val cleaned = state.gameData.aiSectBeastSkipCooldowns.filter { (_, value) ->
            value >= absoluteMonth - 12
        }
        if (cleaned.size < state.gameData.aiSectBeastSkipCooldowns.size) {
            state.gameData = state.gameData.copy(aiSectBeastSkipCooldowns = cleaned)
        }
    }

    /**
     * Phase 2: 处理巡视楼未处理的 AI 进攻目标。
     *
     * 巡视楼运行后，[aiSectBeastDirectTargets] 中剩余的目标表示巡视楼未攻击这些妖兽。
     * - 2 个 AI → AI vs AI 遭遇战 → 胜者 vs 妖兽
     * - 1 个 AI → 单 AI 直接进攻妖兽
     */
    fun processRemainingTargets(state: MutableGameState) {
        val targets = state.gameData.aiSectBeastDirectTargets
        if (targets.isEmpty()) return

        val year = state.gameData.gameYear

        for ((beastId, aiSectIds) in targets) {
            val beast = state.gameData.worldLevels.find {
                it.id == beastId && !it.defeated
            } ?: continue

            when (aiSectIds.size) {
                0 -> continue
                1 -> {
                    val aiSect = state.gameData.worldMapSects.find { it.id == aiSectIds[0] } ?: continue
                    executeAIVersusBeast(state, aiSect, beast, year)
                }
                else -> {
                    // 2 个 AI → AI vs AI 遭遇战 → 胜者 vs 妖兽
                    val sectA = state.gameData.worldMapSects.find { it.id == aiSectIds[0] } ?: continue
                    val sectB = state.gameData.worldMapSects.find { it.id == aiSectIds[1] } ?: continue
                    executeAIEncounterBattle(state, sectA, sectB, beast, year)
                }
            }
        }

        // 清理目标列表
        state.gameData = state.gameData.copy(aiSectBeastDirectTargets = emptyMap())
    }

    /**
     * 两个 AI 宗门遭遇战：AI vs AI PvP → 胜者 vs 妖兽。
     * 在巡视楼未处理的妖兽目标上调用。
     */
    private fun executeAIEncounterBattle(
        state: MutableGameState,
        sectA: WorldSect,
        sectB: WorldSect,
        beast: WorldLevel,
        year: Int
    ) {
        val teamA = state.gameData.aiSectDisciples[sectA.id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return
        val teamB = state.gameData.aiSectDisciples[sectB.id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return
        if (teamA.isEmpty() || teamB.isEmpty()) return

        // Phase 1: AI vs AI PvP
        val preparedA = AISectDiscipleManager.prepareDisciplesForBattle(teamA)
        val preparedB = AISectDiscipleManager.prepareDisciplesForBattle(teamB)
        val teamACombatants = preparedA.disciples.map { disciple ->
            battleSystem.convertDiscipleToCombatant(
                disciple = disciple,
                equipmentMap = preparedA.equipmentMap,
                manualMap = preparedA.manualMap,
                manualProficiencies = preparedA.proficiencies,
                side = CombatantSide.DEFENDER,
                fullHeal = true
            )
        }
        val teamBCombatants = preparedB.disciples.map { disciple ->
            battleSystem.convertDiscipleToCombatant(
                disciple = disciple,
                equipmentMap = preparedB.equipmentMap,
                manualMap = preparedB.manualMap,
                manualProficiencies = preparedB.proficiencies,
                side = CombatantSide.ATTACKER,
                fullHeal = true
            )
        }
        val pvpBattle = Battle(
            team = teamACombatants,
            beasts = teamBCombatants,
            maxTurns = GameConfig.Battle.MAX_TURNS
        )
        val pvpResult = battleSystem.executeBattle(pvpBattle)

        // 处理双方死亡
        val teamADead = pvpResult.battle.team.filter { it.isDead }.map { it.id }.toSet()
        val teamBDead = pvpResult.battle.beasts.filter { it.isDead }.map { it.id }.toSet()
        handleAIDeaths(state, sectA.id, pvpResult, year)
        if (teamBDead.isNotEmpty()) {
            val updatedB = state.gameData.aiSectDisciples[sectB.id]?.map { d ->
                if (d.id in teamBDead) d.copy(isAlive = false, status = DiscipleStatus.DEAD) else d
            } ?: return
            state.gameData = state.gameData.copy(
                aiSectDisciples = state.gameData.aiSectDisciples + (sectB.id to updatedB)
            )
        }

        // Phase 2: 胜者 vs 妖兽
        val winnerDisciples = if (pvpResult.victory) {
            teamA.filter { it.id !in teamADead }
        } else {
            teamB.filter { it.id !in teamBDead }
        }
        if (winnerDisciples.isEmpty()) return

        val beastBattle = createAIBattle(winnerDisciples, beast)
        val beastResult = battleSystem.executeBattle(beastBattle)

        if (beastResult.victory) {
            markBeastDefeated(state, beast.id)
            state.recordGameEvent(GameEventCategory.WORLD, GameEventType.BEAST_HUNT,
                "${if (pvpResult.victory) sectA.name else sectB.name}击败了妖兽「${beast.beastName}」"
            )
        }
        handleAIDeaths(state, if (pvpResult.victory) sectA.id else sectB.id, beastResult, year)
    }

    // ── 内部方法 ───────────────────────────────────────────────

    /**
     * 单个 AI 宗门 vs 妖兽战斗。
     *
     * 创建战斗、执行、标记击败（无奖励）、处理死亡。
     */
    private fun executeAIVersusBeast(
        state: MutableGameState,
        aiSect: WorldSect,
        beast: WorldLevel,
        year: Int
    ) {
        val disciples = state.gameData.aiSectDisciples[aiSect.id]
            ?.filter { it.isAlive }
            ?.take(GameConfig.AI.TEAM_SIZE) ?: return
        if (disciples.isEmpty()) return

        val battle = createAIBattle(disciples, beast)
        val result = battleSystem.executeBattle(battle)

        if (result.victory) {
            markBeastDefeated(state, beast.id)
            state.recordGameEvent(
                GameEventCategory.WORLD, GameEventType.BEAST_HUNT,
                "${aiSect.name}击败了妖兽「${beast.beastName}」"
            )
        } else {
            state.recordGameEvent(
                GameEventCategory.WORLD, GameEventType.BEAST_FAIL,
                "${aiSect.name}讨伐妖兽「${beast.beastName}」失败"
            )
        }

        handleAIDeaths(state, aiSect.id, result, year)
    }

    /**
     * 处理 AI 宗门弟子死亡。
     *
     * 注意：AI 弟子存储在 [GameData.aiSectDisciples] 中，不是 [DiscipleTables]。
     * 不要调用 [DiscipleDeathHandler.markDead] （那是对玩家弟子表的操作）。
     */
    private fun handleAIDeaths(
        state: MutableGameState,
        aiSectId: String,
        result: BattleSystemResult,
        year: Int
    ) {
        val deadIds = result.battle.team
            .filter { it.isDead }
            .map { it.id }
            .toSet()
        if (deadIds.isEmpty()) return

        val updatedDisciples = state.gameData.aiSectDisciples[aiSectId]?.map { d ->
            if (d.id in deadIds) d.copy(
                isAlive = false,
                status = DiscipleStatus.DEAD
            ) else d
        } ?: return

        state.gameData = state.gameData.copy(
            aiSectDisciples = state.gameData.aiSectDisciples + (aiSectId to updatedDisciples)
        )
    }

    /**
     * 记录 AI 宗门跳过冷却到当前绝对月份（年×12+月）。
     * 冷却粒度从年份改为月份，防止一次失败全年免疫。
     */
    private fun recordSkipCooldown(state: MutableGameState, sectId: String, absoluteMonth: Int) {
        state.gameData = state.gameData.copy(
            aiSectBeastSkipCooldowns = state.gameData.aiSectBeastSkipCooldowns + (sectId to absoluteMonth)
        )
    }

    /**
     * 标记妖兽关卡为已击败。
     */
    private fun markBeastDefeated(state: MutableGameState, beastId: String) {
        val updatedLevels = state.gameData.worldLevels.map {
            if (it.id == beastId) it.copy(defeated = true) else it
        }
        state.gameData = state.gameData.copy(worldLevels = updatedLevels)
    }

    /**
     * 为 AI 弟子创建战斗。
     *
     * AI 弟子不含真实装备/功法数据，使用 [AISectDiscipleManager.prepareDisciplesForBattle]
     * 按境界生成模拟装备和功法，确保战斗有合理的数值表现。
     */
    private fun createAIBattle(disciples: List<Disciple>, beast: WorldLevel): Battle {
        if (!ManualDatabase.isInitialized) {
            throw IllegalStateException(
                "ManualDatabase not initialized when creating AI vs beast battle"
            )
        }

        val prepared = AISectDiscipleManager.prepareDisciplesForBattle(disciples)
        return battleSystem.createBattle(
            disciples = prepared.disciples,
            equipmentMap = prepared.equipmentMap,
            manualMap = prepared.manualMap,
            beastLevel = beast.realm,
            beastCount = beast.count,
            beastPreGenStats = BattleSystem.BeastPreGenStats(
                maxHp = beast.beastMaxHp, maxMp = beast.beastMaxMp,
                physicalAttack = beast.beastPhysicalAttack, magicAttack = beast.beastMagicAttack,
                physicalDefense = beast.beastPhysicalDefense, magicDefense = beast.beastMagicDefense,
                speed = beast.beastSpeed, realmLayer = beast.realmLayer
            ),
            manualProficiencies = prepared.proficiencies
        )
    }
}
