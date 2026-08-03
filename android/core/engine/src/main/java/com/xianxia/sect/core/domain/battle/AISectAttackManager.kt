package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
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

            for (defender in allTargets) {
                if (results.any { it.defenderSectId == defender.id || it.attackerSectId == attacker.id }) continue
                val attack = tryDecideAttack(
                    attacker, defender, gameData, aiDisciplesMap, availableAttackers, playerOccupiedDefendersMap
                ) ?: continue
                results.add(attack)
                // One attack per attacker per month
                break
            }
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

        val battleResult = if (isPlayerOccupied &&
            garrisonDisciples.isNotEmpty()
        ) {
            val garrisonCombatants = playerOccupiedDefendersMap[
                defender.id]?.combatants ?: emptyList()
            executePlayerSectBattle(
                selectedAttackers, garrisonCombatants)
        } else {
            executeSectBattle(selectedAttackers,
                defenderSect ?: defender,
                defenderDisciples, allDefenderPool)
        }

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
            // ---- 附庸关系：主宗不攻击附庸 ----
            if (gameData.suzerainSectId == attacker.id) continue

            // ---- 该宗门已有活跃预警 → 跳过 ----
            if (gameData.activeAttackWarnings.any {
                it.attackerSectId == attacker.id
            }) continue

            // ---- 冷却期检查 ----
            val cooldownUntil = gameData.sectAttackCooldowns[attacker.id]
            if (cooldownUntil != null && nowMonth < cooldownUntil) continue

            // ---- 个性参数 ----
            val personality = gameData.aiSectPersonalities[attacker.id]
                ?: AISectPersonality.BALANCED

            // ---- 最低弟子数 ----
            val attackerDisciples = aiDisciplesMap[attacker.id] ?: emptyList()
            val aliveAttackers = attackerDisciples.filter { it.isAlive }
            if (aliveAttackers.size < MIN_DISCIPLES_FOR_ATTACK) continue

            // ---- 联盟 ----
            if (attacker.allianceId.isNotEmpty() &&
                playerSect.allianceId == attacker.allianceId) continue

            // ---- 战力计算 ----
            val attackerPower = SectCombatPowerCalculator.calculateSectPower(aliveAttackers)
            val defenderDisciples = aiDisciplesMap[playerSectId] ?: emptyList()
            val defenderPower = SectCombatPowerCalculator.calculateSectPower(
                defenderDisciples.filter { it.isAlive }
            )
            if (defenderPower <= 0) continue
            val powerRatio = attackerPower.toDouble() / defenderPower.toDouble()

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

            val attackChance = IntelligentSectDecisionEngine.calculateChance(
                profile = IntelligentSectDecisionEngine.ATTACK_PROFILE,
                powerRatio = powerRatio,
                conquestCount = conquestCount,
                lostSectCount = lostSectCount,
                battleWinCount = battleWinCount,
                battleLossCount = battleLossCount,
                favorLevel = favorLevel,
                personality = personality
            )

            if (aisRng.nextDouble() < attackChance) {
                return PlayerAttackDecision.GenerateWarning(
                    attackerSectId = attacker.id,
                    attackerSectName = attacker.name
                )
            }
        }

        return PlayerAttackDecision.Skip
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

    /** P-2：构建战斗技能列表（熟练度加成调整伤害倍率）。 */
    private fun buildCombatSkills(
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
        CombatSkill(
            name = skill.name,
            damageType = if (skill.damageType == DamageType.PHYSICAL) DamageType.PHYSICAL else DamageType.MAGIC,
            damageMultiplier = adjustedMultiplier,
            mpCost = skill.mpCost,
            cooldown = skill.cooldown,
            currentCooldown = 0,
            hits = skill.hits
        )
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
        val availableSkill = selectAISkill(currentCombatant, aliveEnemies, allies.filter { !it.isDead }, silenceBuff != null)

        val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
        val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

        if (availableSkill != null && isSupportSkill) {
            executeSupportAction(currentCombatant, allies.filter { !it.isDead }, availableSkill, allies, alliesIndexMap, roundActions)
        } else if (availableSkill != null && isAoeSkill) {
            executeAoeAttackAction(currentCombatant, aliveEnemies, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
        } else if (availableSkill != null) {
            val target = selectAITarget(currentCombatant, aliveEnemies)
            executeSingleAttackAction(currentCombatant, target, availableSkill, allies, enemies, alliesIndexMap, enemiesIndexMap, roundActions)
        } else {
            val target = selectAITarget(currentCombatant, aliveEnemies)
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
        val rounds = mutableListOf<BattleLogRound>()

        while (turn < GameConfig.AI.MAX_BATTLE_TURNS) {
            val roundActions = mutableListOf<BattleLogAction>()
            val allCombatants = (currentAttackers + currentDefenders)
                .filter { !it.isDead }
                .sortedByDescending { it.effectiveSpeed }

            for (combatant in allCombatants) {
                if (combatant.isDead) continue
                executeAiCombatantTurn(
                    currentAttackers, currentDefenders, combatant, roundActions
                )
                currentAttackers = currentAttackers.filter { !it.isDead }.toMutableList()
                currentDefenders = currentDefenders.filter { !it.isDead }.toMutableList()
            }

            processDotEffects(currentAttackers, currentDefenders)

            rounds.add(BattleLogRound(roundNumber = turn + 1, actions = roundActions.toList()))
            turn++

            if (currentAttackers.isEmpty() || currentDefenders.isEmpty()) break
        }

        val winner = when {
            currentDefenders.isEmpty() -> AIBattleWinner.ATTACKER
            currentAttackers.isEmpty() -> AIBattleWinner.DEFENDER
            else -> AIBattleWinner.DRAW
        }

        return UnifiedAIBattleResult(
            attackers = currentAttackers,
            defenders = currentDefenders,
            winner = winner,
            turns = turn,
            rounds = rounds
        )
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

        val newHp = maxOf(0, target.hp - result.damage)
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            enemies[targetIdx] = enemies[targetIdx].copy(hp = newHp)
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

        val newHp = maxOf(0, target.hp - result.damage)
        val targetIdx = enemiesIndexMap[target.id]
        if (targetIdx != null && targetIdx < enemies.size) {
            var updatedTarget = enemies[targetIdx].copy(hp = newHp)

            val localBuffType = skill.buffType
            if (localBuffType != null && skill.buffDuration > 0) {
                val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
            }

            enemies[targetIdx] = updatedTarget
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

            val result = BattleCalculator.calculateCombatantDamage(
                attacker, target, skill, rng = aisRng, enableInstantKill = true
            )
            if (result.isInstantKill) {
                val targetIdx = enemiesIndexMap[target.id]
                if (targetIdx != null && targetIdx < enemies.size) {
                    enemies[targetIdx] = enemies[targetIdx].copy(hp = 0)
                }
                roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = target.maxHp, skillName = skill.name,
                    isKill = true, message = "${attacker.name} 以 ${skill.name} 境界压制斩杀 ${target.name}"
                ))
                continue
            }

            if (result.isDodged) {
                roundActions.add(BattleLogAction(
                    type = "skill", attacker = attacker.name, attackerType = attackerType,
                    target = target.name, damage = 0, skillName = skill.name,
                    message = "${target.name} 闪避了 ${attacker.name} 的 ${skill.name}"
                ))
                continue
            }

            val newHp = maxOf(0, target.hp - result.damage)
            val targetIdx = enemiesIndexMap[target.id]
            if (targetIdx != null && targetIdx < enemies.size) {
                var updatedTarget = enemies[targetIdx].copy(hp = newHp)

                val localBuffType = skill.buffType
                if (localBuffType != null && skill.buffDuration > 0) {
                    val debuff = CombatBuff(type = localBuffType, value = skill.buffValue, remainingDuration = skill.buffDuration, sourceRealm = attacker.realm)
                    updatedTarget = updatedTarget.copy(buffs = updatedTarget.buffs + debuff)
                }

                enemies[targetIdx] = updatedTarget
            }
            roundActions.add(BattleLogAction(
                type = "skill", attacker = attacker.name, attackerType = attackerType,
                target = target.name, damage = result.damage, skillName = skill.name,
                isCrit = result.isCrit, isKill = newHp == 0
            ))
        }

        val combatantIdx = alliesIndexMap[attacker.id]
        if (combatantIdx != null && combatantIdx < allies.size) {
            allies[combatantIdx] = BattleCalculator.updateCombatantCooldowns(attacker, skill)
        }
    }

    private fun executeSupportAction(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill,
        alliesList: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>,
        roundActions: MutableList<BattleLogAction>
    ) {
        val supportResult = BattleCalculator.executeSupportSkill(caster, allies, skill)

        if (supportResult.healAmount > 0) {
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

        supportResult.teamBuffs.forEach { (memberId, buffs) ->
            val idx = alliesIndexMap[memberId]
            if (idx != null && idx < alliesList.size) {
                alliesList[idx] = alliesList[idx].copy(buffs = alliesList[idx].buffs + buffs)
            }
        }

        val combatantIdx = alliesIndexMap[caster.id]
        if (combatantIdx != null && combatantIdx < alliesList.size) {
            alliesList[combatantIdx] = BattleCalculator.updateCombatantCooldowns(caster, skill)
        }
        roundActions.add(BattleLogAction(
            type = "support", attacker = caster.name,
            attackerType = if (caster.side == CombatantSide.ATTACKER) "attacker" else "defender",
            target = allies.joinToString("、") { it.name }, damage = supportResult.healAmount,
            skillName = skill.name,
            message = "${caster.name} 施展 ${skill.name}" +
                if (supportResult.healAmount > 0) "，恢复 ${supportResult.healedIds.size} 名友方 ${supportResult.healAmount}"
                else if (supportResult.teamBuffs.isNotEmpty()) "，强化 ${supportResult.teamBuffs.size} 名友方"
                else ""
        ))
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

    // 临时保存 BattleAI 决策结果，供 selectAISkill → selectAITarget 配对使用
    private var pendingAiAction: BattleAI.AIAction? = null

    private fun selectAISkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        if (isSilenced) {
            pendingAiAction = null
            return null
        }
        val action = BattleAI.decideAction(combatant, allies, enemies, aisRng)
        pendingAiAction = action
        return action.skill
    }

    private fun selectAITarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val aliveTargets = targets.filter { !it.isDead }
        if (aliveTargets.isEmpty()) return targets.first()
        val action = pendingAiAction
        if (action != null && action.target != null) {
            pendingAiAction = null
            return action.target
        }
        pendingAiAction = null
        return BattleAI.selectAttackTarget(attacker, aliveTargets, null, aisRng)
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
