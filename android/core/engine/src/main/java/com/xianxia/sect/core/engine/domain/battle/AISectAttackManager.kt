package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.Disciple

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.domain.diplomacy.IntelligentSectDecisionEngine
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.BattleCalculator.SupportResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
// top-level fun 提取到 aiattack/ 子目录（同包内可直接访问）

/** AI 宗门攻击系统的 RNG 管理器（由 GameEngine 初始化时注入） */
var aisRngManager: GameRngManager? = null
private val aisRng get() = (aisRngManager ?: error("AISectAttackManager RNG not initialized")).getRng(RngPartition.BATTLE)

object AISectAttackManager {
    private const val TAG = "AISectAttackManager"

    val MIN_DISCIPLES_FOR_ATTACK get() = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    val TEAM_SIZE get() = GameConfig.AI.TEAM_SIZE

    // PlayerOccupiedDefenseInfo 和 AIAttackResult 保留在 object 内（外部有引用）
    data class PlayerOccupiedDefenseInfo(
        val disciples: List<Disciple>,
        val combatants: List<Combatant>
    )

    data class AIAttackResult(
        val attackerSectId: String,
        val defenderSectId: String,
        val attackerSectName: String,
        val defenderSectName: String,
        val winner: AIBattleWinner,
        val deadAttackerIds: List<String>,
        val deadDefenderIds: List<String>,
        val canOccupy: Boolean,
        val survivingAttackers: List<Disciple>,
        val defenderSurvivorHpMap: Map<String, Int> = emptyMap(),
        val defenderSurvivorMpMap: Map<String, Int> = emptyMap(),
        val rounds: List<BattleLogRound> = emptyList(),
        val teamMembers: List<BattleLogMember> = emptyList(),
        val enemies: List<BattleLogEnemy> = emptyList()
    )

    fun decideAttacks(
        gameData: GameData,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo> = emptyMap()
    ): List<AIAttackResult> {
        val results = mutableListOf<AIAttackResult>()
        val aiDisciplesMap = gameData.aiSectDisciples

        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }

        for (attacker in aiSects) {
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            val availableAttackers = attackerDisciples.filter { it.isAlive }
            if (availableAttackers.size < MIN_DISCIPLES_FOR_ATTACK) continue

            val allTargets = gameData.worldMapSects.filter { sect ->
                sect.id != attacker.id && sect.occupierSectId != attacker.id
            }

            // 每个攻击者每月至多一次攻击：跳过已处理目标，首个可攻击目标即停
            var attack: AIAttackResult? = null
            val targets = allTargets.iterator()
            while (attack == null && targets.hasNext()) {
                val defender = targets.next()
                if (results.any { it.defenderSectId == defender.id || it.attackerSectId == attacker.id }) continue
                attack = tryDecideAttack(
                    attacker, defender, gameData, aiDisciplesMap, availableAttackers, playerOccupiedDefendersMap
                )
            }
            attack?.let { results.add(it) }
        }

        return results
    }

    /**
     * 单次 AI 攻击决策：攻击条件检查、队伍构建、防守者解析、战斗执行与结果构造。
     * 条件不满足（无可用攻击者/防守者）时返回 null。
     */
    private fun tryDecideAttack(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>>,
        availableAttackers: List<Disciple>,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo>
    ): AIAttackResult? {
        if (!checkAttackConditions(
                attacker, defender, gameData, aiDisciplesMap,
                playerGarrisonMap = playerOccupiedDefendersMap
                    .mapValues { it.value.disciples }
            )) return null

        // Build attack team
        val selectedAttackers = availableAttackers
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
        if (selectedAttackers.size < MIN_DISCIPLES_FOR_ATTACK) return null

        return resolveDefendersAndBattle(
            attacker, defender, gameData, aiDisciplesMap, selectedAttackers, playerOccupiedDefendersMap
        )
    }

    /**
     * 单个攻击方宗门对单个防御方宗门尝试攻击。
     * 构建攻防队伍、执行战斗、返回结果。
     * @return 战斗结果，条件不满足时返回 null
     */
    fun tryAttackTarget(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>>,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo> = emptyMap()
    ): AIAttackResult? {
        val attackerDisciples = aiDisciplesMap[attacker.id] ?: return null
        val availableAttackers = attackerDisciples.filter { it.isAlive }
        if (availableAttackers.size < MIN_DISCIPLES_FOR_ATTACK) return null

        val selectedAttackers = availableAttackers
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
        if (selectedAttackers.size < MIN_DISCIPLES_FOR_ATTACK) return null

        return resolveDefendersAndBattle(
            attacker, defender, gameData, aiDisciplesMap, selectedAttackers, playerOccupiedDefendersMap
        )
    }

    /**
     * 共享的防守者解析 + 战斗执行 + 结果构造。
     * 被 [tryDecideAttack] 与 [tryAttackTarget] 复用（两者 90% 重复收敛）。
     * 防守者为空时返回 null。
     */
    private fun resolveDefendersAndBattle(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>>,
        selectedAttackers: List<Disciple>,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo>
    ): AIAttackResult? {
        val setup = resolveDefenderSetup(
            gameData, defender, attacker, aiDisciplesMap, playerOccupiedDefendersMap
        ) ?: return null

        val battleResult = if (setup.isPlayerOccupied &&
            setup.garrisonDisciples.isNotEmpty()
        ) {
            val garrisonCombatants = playerOccupiedDefendersMap[
                defender.id]?.combatants ?: emptyList()
            executePlayerSectBattle(
                selectedAttackers, garrisonCombatants)
        } else {
            executeSectBattle(selectedAttackers,
                setup.defenderSect ?: defender,
                setup.defenderDisciples, setup.allDefenderPool)
        }

        return buildAIAttackResult(attacker, defender, battleResult, selectedAttackers)
    }

    /** 防守者解析打包（resolveDefendersAndBattle 提取） */
    private data class DefenderSetup(
        val defenderSect: WorldSect?,
        val garrisonDisciples: List<Disciple>,
        val defenderDisciples: List<Disciple>,
        val allDefenderPool: List<Disciple>,
        val isPlayerOccupied: Boolean
    )

    /** 防守者解析（resolveDefendersAndBattle 提取）：占领判定 + 守军构建 + 全守军池；防守者为空返回 null */
    private fun resolveDefenderSetup(
        gameData: GameData,
        defender: WorldSect,
        attacker: WorldSect,
        aiDisciplesMap: Map<String, List<Disciple>>,
        playerOccupiedDefendersMap: Map<String, PlayerOccupiedDefenseInfo>
    ): DefenderSetup? {
        val defenderSect = gameData.worldMapSects.find {
            it.id == defender.id
        }
        val isAiOccupied = defenderSect?.occupierSectId
            ?.isNotEmpty() == true &&
            defenderSect.occupierSectId != attacker.id
        val isPlayerOccupied = defenderSect?.isPlayerOccupied == true
        val garrisonDisciples = if (isAiOccupied) {
            if (isPlayerOccupied) {
                playerOccupiedDefendersMap[defender.id]
                    ?.disciples ?: emptyList()
            } else {
                val occupierDisciples = aiDisciplesMap[
                    defenderSect.occupierSectId] ?: emptyList()
                defenderSect.garrisonSlots
                    .filter { it.discipleId.isNotEmpty() }
                    .mapNotNull { slot ->
                        occupierDisciples.find { d ->
                            d.id == slot.discipleId && d.isAlive
                        }
                    }
            }
        } else {
            emptyList()
        }

        val defenderPool = aiDisciplesMap[defender.id] ?: emptyList()
        val defenderDisciples = if (garrisonDisciples.isNotEmpty()) {
            garrisonDisciples
        } else {
            defenderPool.filter { it.isAlive }
                .sortedBy { it.realm }.take(TEAM_SIZE)
        }

        if (defenderDisciples.isEmpty()) return null

        val allDefenderPool = if (garrisonDisciples.isNotEmpty()) {
            if (isPlayerOccupied) {
                garrisonDisciples
            } else {
                aiDisciplesMap[
                    defenderSect?.occupierSectId ?: ""]
                    ?: emptyList()
            }
        } else {
            defenderPool
        }

        return DefenderSetup(
            defenderSect, garrisonDisciples, defenderDisciples, allDefenderPool, isPlayerOccupied
        )
    }

    /** 攻击结果组装（resolveDefendersAndBattle 提取） */
    private fun buildAIAttackResult(
        attacker: WorldSect,
        defender: WorldSect,
        battleResult: AIBattleResult,
        selectedAttackers: List<Disciple>
    ): AIAttackResult {
        val survivingAttackers = selectedAttackers.filter {
            it.id !in battleResult.deadAttackerIds
        }

        return AIAttackResult(
            attackerSectId = attacker.id,
            defenderSectId = defender.id,
            attackerSectName = attacker.name,
            defenderSectName = defender.name,
            winner = battleResult.winner,
            deadAttackerIds = battleResult.deadAttackerIds,
            deadDefenderIds = battleResult.deadDefenderIds,
            canOccupy = battleResult.canOccupy,
            survivingAttackers = survivingAttackers
        )
    }

    /**
     * Execute a sect battle given raw disciple lists (no AIBattleTeam needed).
     */
    fun executeSectBattle(
        attackers: List<Disciple>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples
    ): AIBattleResult {
        val defenseTeam = createDefenseTeam(defenderDisciples)
        val combatAttackers = attackers.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val combatDefenders = defenseTeam.map { convertToCombatant(it, CombatantSide.DEFENDER) }

        val result = executeUnifiedAIBattle(combatAttackers, combatDefenders)

        val survivorAttackerIds = result.attackers.map { it.id }.toSet()
        val survivorDefenderIds = result.defenders.map { it.id }.toSet()

        val deadAttackerIds = attackers
            .filter { it.id !in survivorAttackerIds }
            .map { it.id }

        val deadDefenderIds = defenseTeam
            .filter { it.id !in survivorDefenderIds }
            .map { it.id }

        val allDefenderDisciples = allSectDisciples.filter { it.isAlive && it.id !in deadDefenderIds }
        val highRealmAllDead = allDefenderDisciples.filter { it.realm <= 5 }.isEmpty()

        val canOccupy = result.winner == AIBattleWinner.ATTACKER && highRealmAllDead

        val survivorHpMap = result.attackers.associate { it.id to it.hp }
        val survivorMpMap = result.attackers.associate { it.id to it.mp }
        val defenderSurvivorHpMap = result.defenders.associate { it.id to it.hp }
        val defenderSurvivorMpMap = result.defenders.associate { it.id to it.mp }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = canOccupy,
            turns = result.turns,
            survivorHpMap = survivorHpMap,
            survivorMpMap = survivorMpMap,
            defenderSurvivorHpMap = defenderSurvivorHpMap,
            defenderSurvivorMpMap = defenderSurvivorMpMap,
            rounds = result.rounds
        )
    }

    /**
     * 检查攻击条件 — 使用多因素加权智能判定。
     *
     * 保留的二进制硬约束：
     * - 不能攻击自己
     * - 最低弟子数
     * - 同联盟不攻击
     *
     * 综合评估委托 [IntelligentSectDecisionEngine] 的四因素加权模型：
     * - 战力差 (40%) — 攻击方实力与防御方的比值
     * - 占领丢失 (20%) — 征服次数与总占领/丢失比例
     * - 胜负 (25%) — 胜率反映实战能力
     * - 好感度 (15%) — 正值好感不攻击（硬门槛 0）
     * - AI 个性 — 好战型进攻性更强
     */
    fun checkAttackConditions(
        attacker: WorldSect,
        defender: WorldSect,
        gameData: GameData,
        aiDisciplesMap: Map<String, List<Disciple>> = emptyMap(),
        playerGarrisonMap: Map<String, List<Disciple>> = emptyMap()
    ): Boolean {
        if (attacker.id == defender.id) return false

        val attackerDisciples = (aiDisciplesMap[attacker.id] ?: emptyList())
            .filter { it.isAlive }
        if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return false

        // 同联盟不攻击（硬约束）
        if (attacker.allianceId.isNotEmpty() &&
            attacker.allianceId == defender.allianceId) return false

        // 计算战力比（永久基础属性统一公式，无装备/功法估算项）
        val attackerPower = SectCombatPowerCalculator.calculateSectPower(attackerDisciples)
        val defenderDisciples = if (defender.isPlayerOccupied) {
            playerGarrisonMap[defender.id] ?: emptyList()
        } else {
            (aiDisciplesMap[defender.id] ?: emptyList()).filter { it.isAlive }
        }
        val defenderPower = SectCombatPowerCalculator.calculateSectPower(defenderDisciples)
        if (defenderPower <= 0) return false
        val powerRatio = attackerPower.toDouble() / defenderPower.toDouble()

        // 收集历史数据
        val favor = FavorDomain.findFavor(gameData.sectRelations, attacker.id, defender.id)
        val favorLevel = SectRelationLevel.fromFavor(favor)
        val personality = gameData.aiSectPersonalities[attacker.id] ?: AISectPersonality.BALANCED
        val recentRecords = gameData.sectBattleRecords.filter {
            it.year >= gameData.gameYear - 3
        }
        val conquestCount = recentRecords.count { it.type == SectBattleType.CONQUEST }
        val lostSectCount = recentRecords.count { it.type == SectBattleType.LOST_SECT }
        val battleWinCount = recentRecords.count { it.type == SectBattleType.BATTLE_WIN }
        val battleLossCount = recentRecords.count { it.type == SectBattleType.BATTLE_LOSS }

        // 使用共享引擎进行多因素综合评估
        val chance = IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = powerRatio,
            conquestCount = conquestCount,
            lostSectCount = lostSectCount,
            battleWinCount = battleWinCount,
            battleLossCount = battleLossCount,
            favorLevel = favorLevel,
            personality = personality
        )

        return aisRng.nextDouble() < chance
    }

    fun createAttackTeam(
        attackerDisciples: List<Disciple>,
        existingBusyIds: Set<String> = emptySet()
    ): List<Disciple> {
        val minCount = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
        val teamSize = GameConfig.AI.TEAM_SIZE
        val availableDisciples = attackerDisciples
            .filter { it.isAlive && it.id !in existingBusyIds }
            .sortedBy { it.realm }
        if (availableDisciples.size < minCount) return emptyList()
        return availableDisciples.take(teamSize)
    }

    fun createDefenseTeam(defenderDisciples: List<Disciple>): List<Disciple> {
        val teamSize = GameConfig.AI.TEAM_SIZE
        return defenderDisciples.filter { it.isAlive }.sortedBy { it.realm }.take(teamSize)
    }

    /**
     * AI决定攻击玩家的结果——不再是立即执行战斗，
     * 而是返回【是否应生成预警】或【是否应跳过】。
     *
     * 预警生成后进入谴责→战书二级生命周期，
     * 到期后才执行实际战斗。
     */
    sealed interface PlayerAttackDecision {
        /** 不攻击（保护期/附庸/冷却/好感度>0 等） */
        data object Skip : PlayerAttackDecision
        /** 生成预警，进入谴责阶段 */
        data class GenerateWarning(
            val attackerSectId: String,
            val attackerSectName: String
        ) : PlayerAttackDecision
    }

    /**
     * 决定AI宗门是否应攻击玩家。
     *
     * 保留的二进制硬约束（按序检查）：
     * 1. 保护期
     * 2. 附庸关系（主宗不攻击附庸）
     * 3. 已有活跃预警
     * 4. 攻击冷却期
     * 5. 最低弟子数
     * 6. 同联盟不攻击
     *
     * 综合评估委托 [IntelligentSectDecisionEngine] 的四因素加权模型：
     * - 战力差 (40%) — 通过个性偏移因子体现好战/保守差异
     * - 占领丢失 (20%)
     * - 胜负 (25%)
     * - 好感度 (15%) — 正值好感不攻击（ATTACK_PROFILE 的 hard limit 0）
     * - AI 个性 — 作为最终概率的修正因子
     */
    fun decidePlayerAttack(gameData: GameData): PlayerAttackDecision {
        if (gameData.isPlayerProtected) return PlayerAttackDecision.Skip

        val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
            ?: return PlayerAttackDecision.Skip
        val playerSectId = playerSect.id
        val nowMonth = gameData.gameYear * 12 + gameData.gameMonth

        val aiDisciplesMap = gameData.aiSectDisciples

        for (attacker in gameData.worldMapSects.filter { !it.isPlayerSect }) {
            val aliveAttackers = passesAttackerGates(gameData, attacker, nowMonth, aiDisciplesMap)
            val powerRatio = aliveAttackers?.let { computePowerRatio(it, aiDisciplesMap, playerSectId) }
            if (aliveAttackers == null || powerRatio == null) continue
            val attackChance = computeAttackChance(gameData, attacker, playerSectId, powerRatio)

            if (aisRng.nextDouble() < attackChance) {
                return PlayerAttackDecision.GenerateWarning(
                    attackerSectId = attacker.id,
                    attackerSectName = attacker.name
                )
            }
        }

        return PlayerAttackDecision.Skip
    }

    /** 攻击前置六道闸（decidePlayerAttack 提取）：附庸/预警/冷却/弟子数/联盟；未通过返回 null */
    private fun passesAttackerGates(
        gameData: GameData,
        attacker: WorldSect,
        nowMonth: Int,
        aiDisciplesMap: Map<String, List<Disciple>>
    ): List<Disciple>? {
        // ---- 最低弟子数 ----
        val aliveAttackers = (aiDisciplesMap[attacker.id] ?: emptyList()).filter { it.isAlive }
        val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
        val cooldownUntil = gameData.sectAttackCooldowns[attacker.id]

        // ---- 六道闸：附庸 / 活跃预警 / 冷却期 / 最低弟子数 / 联盟 ----
        val passes = gameData.suzerainSectId != attacker.id &&
            gameData.activeAttackWarnings.none { it.attackerSectId == attacker.id } &&
            (cooldownUntil == null || nowMonth >= cooldownUntil) &&
            aliveAttackers.size >= MIN_DISCIPLES_FOR_ATTACK &&
            (attacker.allianceId.isEmpty() || playerSect?.allianceId != attacker.allianceId)
        return if (passes) aliveAttackers else null
    }

    /** 战力比计算（decidePlayerAttack 提取）；防守战力 <=0 返回 null 跳过 */
    private fun computePowerRatio(
        aliveAttackers: List<Disciple>,
        aiDisciplesMap: Map<String, List<Disciple>>,
        playerSectId: String
    ): Double? {
        // ---- 战力计算 ----
        val attackerPower = SectCombatPowerCalculator.calculateSectPower(aliveAttackers)
        val defenderDisciples = aiDisciplesMap[playerSectId] ?: emptyList()
        val defenderPower = SectCombatPowerCalculator.calculateSectPower(
            defenderDisciples.filter { it.isAlive }
        )
        if (defenderPower <= 0) return null
        return attackerPower.toDouble() / defenderPower.toDouble()
    }

    /** 多因素智能综合评估（decidePlayerAttack 提取）：好感/战绩统计 + 个性修正 */
    private fun computeAttackChance(
        gameData: GameData,
        attacker: WorldSect,
        playerSectId: String,
        powerRatio: Double
    ): Double {
        // ---- 个性参数 ----
        val personality = gameData.aiSectPersonalities[attacker.id]
            ?: AISectPersonality.BALANCED

        // ---- 多因素智能综合评估 ----
        val favor = FavorDomain.findFavor(gameData.sectRelations, attacker.id, playerSectId)
        val favorLevel = SectRelationLevel.fromFavor(favor)
        val recentRecords = gameData.sectBattleRecords.filter {
            it.year >= gameData.gameYear - 3
        }
        val conquestCount = recentRecords.count { it.type == SectBattleType.CONQUEST }
        val lostSectCount = recentRecords.count { it.type == SectBattleType.LOST_SECT }
        val battleWinCount = recentRecords.count { it.type == SectBattleType.BATTLE_WIN }
        val battleLossCount = recentRecords.count { it.type == SectBattleType.BATTLE_LOSS }

        return IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
            powerRatio = powerRatio,
            conquestCount = conquestCount,
            lostSectCount = lostSectCount,
            battleWinCount = battleWinCount,
            battleLossCount = battleLossCount,
            favorLevel = favorLevel,
            personality = personality
        )
    }

    /**
     * 执行AI宗门对玩家的实际战斗（预警到期后调用）。
     *
     * @param playerDefenseTeam 玩家方出战弟子（已转换为 Combatant，使用真实装备/功法）
     */
    fun executePlayerAttack(
        gameData: GameData,
        attackerSectId: String,
        playerDefenseTeam: List<Combatant>
    ): AIAttackResult? {
        val aiDisciplesMap = gameData.aiSectDisciples
        val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return null
        val playerSectId = playerSect.id
        val attacker = gameData.worldMapSects.find { it.id == attackerSectId } ?: return null

        val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
        val selectedAttackers = attackerDisciples.filter { it.isAlive }
            .sortedBy { it.realm }
            .take(TEAM_SIZE)
        if (selectedAttackers.size < MIN_DISCIPLES_FOR_ATTACK) return null

        val battleResult = executePlayerSectBattle(
            selectedAttackers, playerDefenseTeam
        )
        val survivingAttackers = selectedAttackers.filter {
            it.id !in battleResult.deadAttackerIds
        }

        return AIAttackResult(
            attackerSectId = attacker.id,
            defenderSectId = playerSectId,
            attackerSectName = attacker.name,
            defenderSectName = playerSect.name,
            winner = battleResult.winner,
            deadAttackerIds = battleResult.deadAttackerIds,
            deadDefenderIds = battleResult.deadDefenderIds,
            canOccupy = battleResult.canOccupy,
            survivingAttackers = survivingAttackers,
            defenderSurvivorHpMap = battleResult.survivorHpMap,
            defenderSurvivorMpMap = battleResult.survivorMpMap,
            rounds = battleResult.rounds
        )
    }

    /**
     * 查找没有任何可攻击目标的 AI 宗门。
     *
     * 为避免概率抖动影响兽潮路由判定，此处只检查硬约束（弟子数、联盟、好感度、战力门槛），
     * 不执行 RNG 概率判定。
     */
    fun findSectsWithNoTargets(gameData: GameData): Set<String> {
        val aiSects = gameData.worldMapSects.filter { !it.isPlayerSect }
        val aiDisciplesMap = gameData.aiSectDisciples
        val sectsWithNoTargets = mutableSetOf<String>()

        for (sect in aiSects) {
            val sectDisciples = aiDisciplesMap[sect.id] ?: emptyList()
            val aliveSectDisciples = sectDisciples.filter { it.isAlive }
            if (aliveSectDisciples.size < MIN_DISCIPLES_FOR_ATTACK) {
                sectsWithNoTargets.add(sect.id)
                continue
            }

            val sectPower = SectCombatPowerCalculator.calculateSectPower(aliveSectDisciples)
            val personality = gameData.aiSectPersonalities[sect.id] ?: AISectPersonality.BALANCED

            val hasTarget = gameData.worldMapSects.any { target ->
                if (target.id == sect.id || target.occupierSectId == sect.id) return@any false
                if (sect.allianceId.isNotEmpty() && sect.allianceId == target.allianceId) return@any false

                val targetDisciples = if (target.isPlayerOccupied) {
                    emptyList() // 玩家占领的宗门可能有驻军，不确定时视为有目标
                } else {
                    (aiDisciplesMap[target.id] ?: emptyList()).filter { it.isAlive }
                }
                if (targetDisciples.isEmpty() && !target.isPlayerSect && !target.isPlayerOccupied) return@any false

                val targetPower = SectCombatPowerCalculator.calculateSectPower(targetDisciples)
                if (targetPower <= 0) return@any false
                val powerRatio = sectPower.toDouble() / targetPower.toDouble()

                // 使用引擎计算概率但不执行 RNG，只要 chance > 0 就算有目标
                val favor = FavorDomain.findFavor(gameData.sectRelations, sect.id, target.id)
                val favorLevel = SectRelationLevel.fromFavor(favor)
                val chance = IntelligentSectDecisionEngine.calculateChance(
                    profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
                    powerRatio = powerRatio,
                    conquestCount = 0, lostSectCount = 0, battleWinCount = 0, battleLossCount = 0,
                    favorLevel = favorLevel,
                    personality = personality
                )
                chance > 0.0
            }

            if (!hasTarget) {
                sectsWithNoTargets.add(sect.id)
            }
        }

        return sectsWithNoTargets
    }

    internal fun convertToCombatant(disciple: Disciple, side: CombatantSide): Combatant {
        // 读取持久化的装备/功法字段（模板 id → 临时实例映射），不再战前随机生成。
        // registry 未初始化时降级为裸装战斗（与 AISectDiscipleManager 各路径的降级语义一致）
        val equipmentMap = if (ManualDatabase.isInitialized) {
            AISectDiscipleManager.buildEquipmentMapForDisciple(disciple)
        } else {
            emptyMap()
        }
        val (manualMap, manualProficiencies) = if (ManualDatabase.isInitialized) {
            AISectDiscipleManager.buildManualDataForDisciple(disciple)
        } else {
            emptyMap<String, ManualInstance>() to emptyMap<String, ManualProficiencyData>()
        }

        val stats = disciple.getFinalStats(equipmentMap, manualMap, manualProficiencies)

        val skills = buildCombatSkills(manualMap, manualProficiencies)

        val spiritRootTypes = disciple.spiritRoot.types
        val primaryElement = spiritRootTypes.firstOrNull()?.trim() ?: "metal"
        val weaponName = disciple.equipment.weaponId
            .takeIf { it.isNotEmpty() }
            ?.let { equipmentMap[it]?.name }

        // 体质独立乘算因子：从 DiscipleStatCalculator 注入到 Combatant
        val physiqueEffects = DiscipleStatCalculator.getPhysiqueEffects(disciple)
        // 词条独立乘算因子：从 DiscipleStatCalculator 注入到 Combatant
        val affixCombat = DiscipleStatCalculator.getAffixCombatEffects(disciple)

        return Combatant(
            id = disciple.id,
            name = disciple.name,
            side = side,
            hp = stats.maxHp,
            maxHp = stats.maxHp,
            mp = stats.maxMp,
            maxMp = stats.maxMp,
            physicalAttack = stats.physicalAttack,
            magicAttack = stats.magicAttack,
            physicalDefense = stats.physicalDefense,
            magicDefense = stats.magicDefense,
            speed = stats.speed,
            critRate = stats.critRate,
            realm = disciple.realm,
            realmName = disciple.realmName,
            realmLayer = disciple.realmLayer,
            skills = skills,
            buffs = emptyList(),
            element = primaryElement,
            weaponName = weaponName,
            portraitRes = disciple.portraitRes,
            physique = PhysiqueCombatFactors(
                damageAmplification = physiqueEffects.damageAmplification,
                critDamageBonus = physiqueEffects.critDamageBonus,
                damageReduction = physiqueEffects.damageReduction,
                defenseBonus = physiqueEffects.defenseBonus
            ),
            affix = affixCombat
        )
    }

    /**
     * P-2：构建战斗技能列表（熟练度加成调整伤害倍率）。
     *
     * 2026-08-04 修复：原手写 CombatSkill 仅传 7 个字段，丢失 skillType（默认 ATTACK，
     * 支援功法变普攻）、isAoe、buff/heal/shield/控制/拉条等全部属性——AI 宗门弟子
     * 功法技能退化为弱普攻。改走 [ManualInstance.toCombatSkill] 全字段保留。
     */
    internal fun buildCombatSkills(
        manualMap: Map<String, ManualInstance>,
        manualProficiencies: Map<String, ManualProficiencyData>
    ): List<CombatSkill> = manualMap.keys.mapNotNull { mId ->
        val manual = manualMap[mId] ?: return@mapNotNull null
        val skill = manual.skill ?: return@mapNotNull null
        val proficiencyData = manualProficiencies[mId]
        val masteryLevel = proficiencyData?.masteryLevel ?: 0
        val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
            skill.damageMultiplier,
            masteryLevel
        )
        skill.copy(damageMultiplier = adjustedMultiplier).toCombatSkill(manualName = manual.name)
    }

    fun executeAISectBattle(
        attackers: List<Disciple>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples
    ): AIBattleResult {
        return executeSectBattle(attackers, defenderSect, defenderDisciples, allSectDisciples)
    }

    fun executePlayerSectBattle(
        attackers: List<Disciple>,
        playerDefenseTeam: List<Combatant>
    ): AIBattleResult {
        val combatAttackers = attackers.map { convertToCombatant(it, CombatantSide.ATTACKER) }
        val combatDefenders = playerDefenseTeam
            .filter { it.side == CombatantSide.DEFENDER }
            .take(TEAM_SIZE)

        val result = executeUnifiedAIBattle(combatAttackers, combatDefenders)

        val deadAttackerIds = attackers
            .filter { disciple ->
                result.attackers.find { it.id == disciple.id } == null
            }
            .map { it.id }

        val survivorDefenderIds = result.defenders.map { it.id }.toSet()
        val deadDefenderIds = combatDefenders
            .filter { it.id !in survivorDefenderIds }
            .map { it.id }

        val survivorHpMap = result.defenders.associate { it.id to it.hp }
        val survivorMpMap = result.defenders.associate { it.id to it.mp }

        return AIBattleResult(
            winner = result.winner,
            deadAttackerIds = deadAttackerIds,
            deadDefenderIds = deadDefenderIds,
            canOccupy = result.winner == AIBattleWinner.ATTACKER,
            turns = result.turns,
            survivorHpMap = survivorHpMap,
            survivorMpMap = survivorMpMap,
            rounds = result.rounds
        )
    }

    private data class UnifiedAIBattleResult(
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val winner: AIBattleWinner,
        val turns: Int,
        val rounds: List<BattleLogRound> = emptyList()
    )

    /**
     * 单个参战者回合行动：控制效果跳过 / 支援 / AOE / 单体技能 / 普攻四分支。
     * 原地修改 [currentAttackers]/[currentDefenders] 中对应 combatant。
     */
    private fun executeAiCombatantTurn(
        currentAttackers: MutableList<Combatant>,
        currentDefenders: MutableList<Combatant>,
        combatant: Combatant,
        roundActions: MutableList<BattleLogAction>
    ) {
        val isAttacker = combatant.side == CombatantSide.ATTACKER
        val allies = if (isAttacker) currentAttackers else currentDefenders
        val enemies = if (isAttacker) currentDefenders else currentAttackers
        val alliesIndexMap = allies.withIndex().associate { it.value.id to it.index }
        val enemiesIndexMap = enemies.withIndex().associate { it.value.id to it.index }

        val aliveEnemies = enemies.filter { !it.isDead }
        if (aliveEnemies.isEmpty()) return

        val combatantIdx = alliesIndexMap[combatant.id] ?: return
        val currentCombatant = allies[combatantIdx]

        if (currentCombatant.hasControlEffect) {
            allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(currentCombatant)
            return
        }

        val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
        val skillDecision = selectAISkill(
            currentCombatant, aliveEnemies, allies.filter { !it.isDead }, silenceBuff != null
        )
        val availableSkill = skillDecision.skill

        val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
        val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

        if (availableSkill != null && isSupportSkill) {
            executeSupportAction(currentCombatant, allies.filter { !it.isDead }, availableSkill, allies, alliesIndexMap, roundActions)
        } else if (availableSkill != null && isAoeSkill) {
            executeAoeAttackAction(currentCombatant, aliveEnemies, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
        } else if (availableSkill != null) {
            val target = selectAITarget(currentCombatant, aliveEnemies, skillDecision.action)
            executeSingleAttackAction(currentCombatant, target, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
        } else {
            val target = selectAITarget(currentCombatant, aliveEnemies, skillDecision.action)
            executeNormalAttackAction(currentCombatant, target, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
        }
    }

    private fun executeUnifiedAIBattle(
        attackers: List<Combatant>,
        defenders: List<Combatant>
    ): UnifiedAIBattleResult {
        var currentAttackers = attackers.toMutableList()
        var currentDefenders = defenders.toMutableList()
        var turn = 0
        var timedOut = false
        var ended = false
        val rounds = mutableListOf<BattleLogRound>()
        val startTime = System.currentTimeMillis()

        while (turn < GameConfig.AI.MAX_BATTLE_TURNS && !timedOut && !ended) {
            val outcome = executeAiRound(currentAttackers, currentDefenders, startTime, turn + 1)
            if (outcome.timedOut) {
                timedOut = true
            } else {
                currentAttackers = outcome.attackers.toMutableList()
                currentDefenders = outcome.defenders.toMutableList()
                rounds.add(outcome.round)
                turn++
                ended = outcome.ended
            }
        }

        return UnifiedAIBattleResult(
            attackers = currentAttackers,
            defenders = currentDefenders,
            winner = resolveAiWinner(currentAttackers, currentDefenders, timedOut),
            turns = turn,
            rounds = rounds
        )
    }

    private data class AiBattleRoundOutcome(
        val timedOut: Boolean,
        val ended: Boolean,
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val round: BattleLogRound
    )

    /** AI 宗门战单回合（executeUnifiedAIBattle 提取）：保留快照后击杀的 isDead 运行时守卫 */
    private fun executeAiRound(
        currentAttackers: MutableList<Combatant>,
        currentDefenders: MutableList<Combatant>,
        startTime: Long,
        roundNumber: Int
    ): AiBattleRoundOutcome {
        // 超时保护（对齐 BattleSystem 5000ms）：每旬大量 AI 宗门战在游戏线程执行，
        // 拉锯战（高防低攻）不得无限占用主线程
        if (System.currentTimeMillis() - startTime > GameConfig.AI.MAX_AI_BATTLE_DURATION_MS) {
            return AiBattleRoundOutcome(
                timedOut = true, ended = false, attackers = currentAttackers,
                defenders = currentDefenders, round = BattleLogRound(roundNumber, emptyList())
            )
        }
        var attackers = currentAttackers
        var defenders = currentDefenders
        val roundActions = mutableListOf<BattleLogAction>()
        val allCombatants = (attackers + defenders)
            .filter { !it.isDead }
            .sortedByDescending { it.effectiveSpeed }

        for (combatant in allCombatants) {
            if (combatant.isDead) continue
            executeAiCombatantTurn(
                attackers, defenders, combatant, roundActions
            )
            attackers = attackers.filter { !it.isDead }.toMutableList()
            defenders = defenders.filter { !it.isDead }.toMutableList()
        }

        processDotEffects(attackers, defenders)

        return AiBattleRoundOutcome(
            timedOut = false,
            ended = attackers.isEmpty() || defenders.isEmpty(),
            attackers = attackers,
            defenders = defenders,
            round = BattleLogRound(roundNumber = roundNumber, actions = roundActions.toList())
        )
    }

    /** AI 宗门战胜者判定（executeUnifiedAIBattle 提取） */
    private fun resolveAiWinner(
        currentAttackers: List<Combatant>,
        currentDefenders: List<Combatant>,
        timedOut: Boolean
    ): AIBattleWinner = when {
        currentDefenders.isEmpty() -> AIBattleWinner.ATTACKER
        currentAttackers.isEmpty() -> AIBattleWinner.DEFENDER
        // 对抗性审查：超时后按存活数多者胜（与 BattleSystem 超时语义对齐），
        // 避免僵局战一律 DRAW 使攻击方无损失（玩家高防驻军=免伤屏障）
        timedOut && currentAttackers.size != currentDefenders.size ->
            if (currentAttackers.size > currentDefenders.size) AIBattleWinner.ATTACKER
            else AIBattleWinner.DEFENDER
        else -> AIBattleWinner.DRAW
    }

    private fun executeNormalAttackAction(
        attacker: Combatant,
        target: Combatant,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, target, null, rng = aisRng, enableInstantKill = true
        )
        if (result.isInstantKill) {
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
            }
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
            }
            roundActions.add(BattleLogAction(
                type = "normal", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = target.maxHp,
                isKill = true, message = "${attacker.name} 境界压制斩杀 ${target.name}"
            ))
            return
        }

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
            }
            roundActions.add(BattleLogAction(
                type = "normal", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = 0,
                message = "${target.name} 闪避了 ${attacker.name} 的攻击"
            ))
            return
        }

        applyNormalAttackDamage(
            attacker, target, result, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions
        )
    }

    /**
     * 普攻伤害应用：扣除目标 HP、刷新攻击者 BUFF、记录行动日志。
     * 从 executeNormalAttackAction 提取（正常伤害分支）。
     */
    private fun applyNormalAttackDamage(
        attacker: Combatant,
        target: Combatant,
        result: BattleCalculator.DamageResult,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        var newHp = target.hp
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            // 护盾吸收 + 扣血（共享应用层，与主战斗引擎一致）
            val updated = BattleDamageApplier.applyDamageToTarget(enemies[targetIdx], result.damage)
            enemies[targetIdx] = updated
            newHp = updated.hp
            // 伤害分摊/链接（AI 弟子技能可能带 damageShare/damageLink）
            applyShareAndLink(attacker, updated, result.damage, allies, enemies)
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantBuffsOnly(attacker)
        }
        roundActions.add(BattleLogAction(
            type = "normal", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = result.damage,
            isCrit = result.isCrit, isKill = newHp == 0
        ))
    }

    private fun executeSingleAttackAction(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, target, skill, rng = aisRng, enableInstantKill = true
        )
        if (result.isInstantKill) {
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
            }
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = target.maxHp, skillName = skill.name,
                isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
            ))
            return
        }

        if (result.isDodged) {
            val combatantIdx = alliesIndexMap[attacker.id]
            if (combatantIdx != null && combatantIdx < allies.size) {
                allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name,
                attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
                target = target.name, damage = 0, skillName = skill.name,
                message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
            ))
            return
        }

        applySingleSkillDamage(
            attacker, target, skill, result, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions
        )
    }

    /**
     * 单体技能伤害应用：扣除目标 HP、附加技能 debuff、刷新攻击者冷却、记录日志。
     * 从 executeSingleAttackAction 提取（正常伤害分支）。
     */
    private fun applySingleSkillDamage(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        result: BattleCalculator.DamageResult,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        var newHp = target.hp
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            // 护盾吸收 + 扣血（共享应用层）
            var updatedTarget = BattleDamageApplier.applyDamageToTarget(enemies[targetIdx], result.damage)
            newHp = updatedTarget.hp

            val localBuffType = skill.buffType
            if (localBuffType != null && skill.buffDuration > 0) {
                val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
            }
            // 伤害链接 debuff（对抗性审查：G4 全字段保留后 AI 战需与主引擎一致——
            // 清旧链接再附加，否则链接效果在宗门战恒为零）
            updatedTarget = applyLinkDebuff(attacker, updatedTarget, skill)

            enemies[targetIdx] = updatedTarget
            // 伤害分摊/链接
            applyShareAndLink(attacker, updatedTarget, result.damage, allies, enemies)
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
        roundActions.add(BattleLogAction(
            type = "skill", attacker = attacker.name,
            attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = target.name, damage = result.damage, skillName = skill.name,
            isCrit = result.isCrit, isKill = newHp == 0
        ))
    }

    private fun executeAoeAttackAction(
        attacker: Combatant,
        targets: List<Combatant>,
        skill: CombatSkill,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        enemiesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender"
        for (target in targets) {
            if (target.isDead) continue
            applyAoeSingleTarget(
                attacker, target, skill,
                AoeWriteBackContext(allies, enemies, enemiesIndexMap, roundActions)
            )
        }
        // 攻击者冷却/MP 结算：每次技能执行一次（无论目标走必杀/闪避/正常分支），
        // 修复 P3C-3 拆分时冷却结算移入单目标分支导致全目标必杀/闪避时结算丢失
        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
    }

    /**
     * AOE 单目标伤害应用：必杀/闪避/正常三分支（含技能 debuff 附加）。
     * 从 executeAoeAttackAction 循环体提取。
     */
    private data class AoeWriteBackContext(
        val allies: MutableList<Combatant>,
        val enemies: MutableList<Combatant>,
        val enemiesIndexMap: Map<String, Int>,
        val roundActions: MutableList<BattleLogAction>
    )

    private fun applyAoeSingleTarget(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        ctx: AoeWriteBackContext
    ) {
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, target, skill, rng = aisRng, enableInstantKill = true
        )
        val attackerType = if (attacker.side == CombatantSide.ATTACKER) "attacker" else "defender"
        if (result.isInstantKill) {
                val targetIdx = ctx.enemiesIndexMap[target.id]
                if (targetIdx != null && targetIdx < ctx.enemies.size) {
                    ctx.enemies[targetIdx] = ctx.enemies[targetIdx].copy(hp = 0)
                }
                ctx.roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = target.maxHp, skillName = skill.name,
                    isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
                ))
                return
            }

            if (result.isDodged) {
                ctx.roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = 0, skillName = skill.name,
                    message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
                ))
                return
            }

            var newHp = target.hp
            val targetIdx = ctx.enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < ctx.enemies.size) {
                // 护盾吸收 + 扣血（共享应用层）
                var updatedTarget = BattleDamageApplier.applyDamageToTarget(ctx.enemies[targetIdx], result.damage)
                newHp = updatedTarget.hp

                val localBuffType = skill.buffType
                if (localBuffType != null && skill.buffDuration > 0) {
                    val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                    updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
                }
                // 伤害链接 debuff（与主引擎一致）
                updatedTarget = applyLinkDebuff(attacker, updatedTarget, skill)

                ctx.enemies[targetIdx] = updatedTarget
                // 伤害分摊/链接
                applyShareAndLink(attacker, updatedTarget, result.damage, ctx.allies, ctx.enemies)
            }
            ctx.roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name, attackerType = attackerType,
                target = target.name, damage = result.damage, skillName = skill.name,
                isCrit = result.isCrit, isKill = newHp == 0
            ))
    }

    private fun executeSupportAction(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val supportAllies = resolveSupportTargets(caster, allies, skill)
        val supportResult = BattleCalculator.executeSupportSkill(caster, supportAllies, skill)
        applySupportHealing(supportResult, alliesList, alliesIndexMap, skill)
        applySupportTeamBuffs(supportResult, alliesList, alliesIndexMap)
        updateSupportCooldown(caster, alliesList, alliesIndexMap, skill)
        roundActions.add(buildSupportActionLog(caster, allies, supportResult, skill))
    }

    /** 支援目标解析（executeSupportAction 提取）：保留 aisRng 抽数位置 */
    private fun resolveSupportTargets(caster: Combatant, allies: List<Combatant>, skill: CombatSkill): List<Combatant> {
        // 对抗性审查修复：ally 作用域由调用方解析（BattleCalculator 对 "ally" 返回空列表）——
        // 此前传全部存活盟友导致 ally 技能对所有人生效/或对空列表空放
        if (skill.targetScope != "ally") return allies
        val valid = allies.filter { !it.isDead && it.id != caster.id }
        return if (valid.isNotEmpty()) listOf(valid[aisRng.nextInt(valid.size)]) else emptyList()
    }

    /** 支援治疗写回（executeSupportAction 提取） */
    private fun applySupportHealing(
        supportResult: SupportResult,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        skill: CombatSkill
    ) {
        if (supportResult.healAmount <= 0) return
        supportResult.healedIds.forEach { healedId ->
            val idx = alliesIndexMap[healedId]
            if (idx != null && idx < alliesList.size) {
                if (skill.healType == HealType.MP) {
                    alliesList[idx] = alliesList[idx].copy(mp = minOf(alliesList[idx].mp + supportResult.healAmount, alliesList[idx].maxMp))
                } else {
                    alliesList[idx] = alliesList[idx].copy(hp = minOf(alliesList[idx].hp + supportResult.healAmount, alliesList[idx].maxHp))
                }
            }
        }
    }

    /** 支援团队 BUFF 写回（executeSupportAction 提取） */
    private fun applySupportTeamBuffs(
        supportResult: SupportResult,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>
    ) {
        supportResult.teamBuffs.forEach { (memberId, buffs) ->
            val idx = alliesIndexMap[memberId]
            if (idx != null && idx < alliesList.size) {
                alliesList[idx] = alliesList[idx].copy(buffs = alliesList[idx].buffs + buffs)
            }
        }
    }

    /** 支援施放者冷却更新（executeSupportAction 提取） */
    private fun updateSupportCooldown(
        caster: Combatant,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        skill: CombatSkill
    ) {
        val combatantIdx = alliesIndexMap[caster.id]
        if (combatantIdx != null && combatantIdx < alliesList.size) {
            alliesList[combatantIdx] = BattleCalculator.updateCombatantCooldowns(caster, skill)
        }
    }

    /** 支援行动日志（executeSupportAction 提取） */
    private fun buildSupportActionLog(
        caster: Combatant,
        allies: List<Combatant>,
        supportResult: SupportResult,
        skill: CombatSkill
    ): BattleLogAction = BattleLogAction(
        type = "support", attacker = caster.name,
        attackerType = if (caster.side == CombatantSide.ATTACKER) "attacker" else "defender",
        target = allies.joinToString("、") { it.name }, damage = supportResult.healAmount,
        skillName = skill.name,
        message = "${caster.name} 施展 ${skill.name}" +
            if (supportResult.healAmount > 0) "，恢复 ${supportResult.healedIds.size} 名友方 ${supportResult.healAmount}"
            else if (supportResult.teamBuffs.isNotEmpty()) "，强化 ${supportResult.teamBuffs.size} 名友方"
            else ""
    )

    /**
     * 伤害链接 debuff 附加（与主引擎 applyDamageLinkDebuff 语义一致）：
     * 清掉旧的链接标记再附加新链接（同时仅一个链接）。
     */
    private fun applyLinkDebuff(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill
    ): Combatant {
        val linkPercent = skill.damageLinkPercent
        if (linkPercent == null || linkPercent <= 0 || skill.buffDuration <= 0) return target
        val cleaned = target.buffs.filter { it.type != BuffType.DAMAGE_LINK }
        return cleaned.let { buffs ->
            target.copy(
                buffs = buffs + CombatBuff(
                    type = BuffType.DAMAGE_LINK,
                    value = linkPercent,
                    remainingDuration = skill.buffDuration,
                    sourceRealm = attacker.realm
                )
            )
        }
    }

    /**
     * 伤害分摊/链接应用（共享应用层 [BattleDamageApplier]）。
     * attackers/defenders 映射为 BattleDamageApplier 的 team(DEFENDER)/beasts(ATTACKER) 语义。
     */
    private fun applyShareAndLink(
        attacker: Combatant,
        target: Combatant,
        damage: Int,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>
    ) {
        val team = if (attacker.side == CombatantSide.DEFENDER) allies else enemies
        val beasts = if (attacker.side == CombatantSide.DEFENDER) enemies else allies
        BattleDamageApplier.applySharedDamage(target, damage, team, beasts)
            .forEach { (id, updated) -> writeBackToLists(id, updated, allies, enemies) }
        BattleDamageApplier.applyLinkedDamage(attacker, target, damage, team, beasts)
            .forEach { (id, updated) -> writeBackToLists(id, updated, allies, enemies) }
    }

    private fun writeBackToLists(
        id: String,
        updated: Combatant,
        allies: MutableList<Combatant>,
        enemies: MutableList<Combatant>
    ) {
        val idxA = allies.indexOfFirst { it.id == id }
        if (idxA >= 0) {
            allies[idxA] = updated
        } else {
            val idxE = enemies.indexOfFirst { it.id == id }
            if (idxE >= 0) enemies[idxE] = updated
        }
    }

    private fun processDotEffects(attackers: MutableList<Combatant>, defenders: MutableList<Combatant>) {
        val allCombatants = (attackers + defenders).filter { !it.isDead }
        val dotResults = BattleCalculator.processDotEffects(allCombatants)
        for (result in dotResults) {
            val isAttacker = result.combatant.side == CombatantSide.ATTACKER
            val list = if (isAttacker) attackers else defenders
            val idx = list.indexOfFirst { it.id == result.combatant.id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(hp = result.newHp)
            }
        }
    }

    /** 技能决策结果（局部传递，替代原类级 pendingAiAction——与 BattleSystem G7 收敛） */
    private data class AiSkillDecision(
        val skill: CombatSkill?,
        val action: BattleAI.AIAction?
    )

    private fun selectAISkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): AiSkillDecision {
        if (isSilenced) return AiSkillDecision(null, null)
        val action = BattleAI.decideAction(combatant, allies, enemies, aisRng)
        return AiSkillDecision(action.skill, action)
    }

    private fun selectAITarget(
        attacker: Combatant,
        targets: List<Combatant>,
        aiAction: BattleAI.AIAction?
    ): Combatant {
        val aliveTargets = targets.filter { !it.isDead }
        if (aliveTargets.isEmpty()) return targets.first()
        return aiAction?.target
            ?: BattleAI.selectAttackTarget(attacker, aliveTargets, null, aisRng)
            ?: aliveTargets.first()
    }

    fun getGarrisonDisciples(sect: WorldSect, allDisciples: List<Disciple>): List<Disciple> {
        return sect.garrisonSlots
            .filter { it.discipleId.isNotEmpty() }
            .mapNotNull { slot -> allDisciples.find { it.id == slot.discipleId } }
            .filter { it.isAlive }
    }

    fun supplementDisciples(
        coreDisciples: List<Disciple>,
        availableDisciples: List<Disciple>
    ): List<Disciple> {
        val core = coreDisciples.take(TEAM_SIZE)
        if (core.size >= TEAM_SIZE) return core
        val coreIds = core.map { it.id }.toSet()
        val supplements = availableDisciples
            .filter { it.isAlive && it.id !in coreIds }
            .sortedBy { it.realm }
            .take(TEAM_SIZE - core.size)
        return core + supplements
    }

    // 以下已提取为同包 top-level fun：
    // supplementDisciples, createPlayerDefenseTeam, getGarrisonDisciples,
    // getSectWarRewardConfig, generateWarRewards
}
