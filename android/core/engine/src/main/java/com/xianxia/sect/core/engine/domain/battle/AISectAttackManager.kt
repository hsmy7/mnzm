package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.Disciple

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.domain.diplomacy.IntelligentSectDecisionEngine
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.domain.diplomacy.buildEquipmentMapForDisciple
import com.xianxia.sect.core.engine.domain.diplomacy.buildManualDataForDisciple
import com.xianxia.sect.core.engine.domain.disciple.getPhysiqueEffects
import com.xianxia.sect.core.engine.domain.disciple.getAffixCombatEffects
// top-level fun 提取到 aiattack/ 子目录（同包内可直接访问）

// W4-C 随机源收敛：顶层可变 `aisRngManager` + `internal val aisRng` 已摘除，
// BATTLE 分区抽取改由调用方经形参显式传入（生产单引擎下抽取序逐位不变）。

object AISectAttackManager {
    /**
     * 单用户定向补偿邮件（MailService 扩展，独立文件）。
     *
     * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
     * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
     * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
     */
    internal const val TAG = "AISectAttackManager"

    val MIN_DISCIPLES_FOR_ATTACK get() = GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK
    val TEAM_SIZE get() = GameConfig.AI.TEAM_SIZE

    /**
     * Execute a sect battle given raw disciple lists (no AIBattleTeam needed).
     *
     * 注意：本入口的攻击者按 [convertToCombatant]（AI 模板 id 语义）构建。
     * 仅可用于 AI 弟子（AI 持久化装备/功法模板 id）。
     * 玩家主动进攻 AI 宗门时必须改用 [executeSectBattleWithCombatantAttackers]
     * （玩家装备/功法字段为实例 id，模板表查询必然 miss，否则玩家裸装无技能参战）。
     */
    fun executeSectBattle(
        attackers: List<Disciple>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples,
        bloodRefinementMap: Map<String, BloodRefinementPctTotal> = emptyMap(),
        rngManager: GameRngManager
    ): AIBattleResult {
        val combatAttackers = attackers.map {
            convertToCombatant(it, CombatantSide.ATTACKER, bloodRefinementMap[it.id])
        }
        return executeSectBattleCore(
            combatAttackers = combatAttackers,
            attackerIds = attackers.map { it.id },
            defenderSect = defenderSect,
            defenderDisciples = defenderDisciples,
            allSectDisciples = allSectDisciples,
            rngManager = rngManager
        )
    }

    /**
     * 玩家主动进攻 AI 宗门专用入口：攻击者已按玩家实例语义（真实装备/功法实例）构建为 Combatant。
     *
     * [convertToCombatant] 是 AI 模板 id 语义专用（AI 弟子持久化模板 id）；
     * 玩家弟子的装备/功法字段是实例 id（UUID），经模板表查询必然 miss，
     * 会导致玩家裸装、无功法技能参战（高境界打低境界也必败，2026-XX 回归根因）。
     * 玩家侧 Combatant 必须由 [BattleSystem.convertDiscipleToCombatant]（实例表语义）构建后传入本入口。
     */
    fun executeSectBattleWithCombatantAttackers(
        combatAttackers: List<Combatant>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple> = defenderDisciples,
        rngManager: GameRngManager
    ): AIBattleResult {
        return executeSectBattleCore(
            combatAttackers = combatAttackers,
            attackerIds = combatAttackers.map { it.id },
            defenderSect = defenderSect,
            defenderDisciples = defenderDisciples,
            allSectDisciples = allSectDisciples,
            rngManager = rngManager
        )
    }

    /** executeSectBattle / executeSectBattleWithCombatantAttackers 共享核心：战斗执行 + 结果组装 */
    @Suppress("UnusedParameter") // defenderSect: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
    private fun executeSectBattleCore(
        combatAttackers: List<Combatant>,
        attackerIds: List<String>,
        defenderSect: WorldSect,
        defenderDisciples: List<Disciple>,
        allSectDisciples: List<Disciple>,
        rngManager: GameRngManager
    ): AIBattleResult {
        val defenseTeam = createDefenseTeam(defenderDisciples)
        val combatDefenders = defenseTeam.map { convertToCombatant(it, CombatantSide.DEFENDER) }

        // AUTHORITATIVE 下经 C++ 第三战斗引擎执行（降级回退 Kotlin；
        // Kotlin 臂的 BATTLE 分区抽取经形参传入——W4-C 随机源收敛）
        val result = tryExecuteUnifiedNative(combatAttackers, combatDefenders)
            ?: executeUnifiedAIBattle(combatAttackers, combatDefenders, rngManager.getRng(RngPartition.BATTLE))

        val survivorAttackerIds = result.attackers.map { it.id }.toSet()
        val survivorDefenderIds = result.defenders.map { it.id }.toSet()

        val deadAttackerIds = attackerIds
            .filter { it !in survivorAttackerIds }

        val deadDefenderIds = defenseTeam
            .filter { it.id !in survivorDefenderIds }
            .map { it.id }

        // 占领判定（winner==ATTACKER && 高阶全灭）经 C++
        // computeCanOccupy 计算（sect_attack_decision.h，纯确定性零 RNG）；
        // native 未加载/异常时回退 Kotlin 原判定（保障行为一致）。
        val canOccupy = tryNativeComputeCanOccupy(
            winnerIsAttacker = result.winner == AIBattleWinner.ATTACKER,
            allSectDisciples = allSectDisciples,
            deadDefenderIds = deadDefenderIds
        ) ?: run {
            val allDefenderDisciples = allSectDisciples.filter { it.isAlive && it.id !in deadDefenderIds }
            result.winner == AIBattleWinner.ATTACKER && allDefenderDisciples.none { it.realm <= 5 }
        }

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
        playerGarrisonMap: Map<String, List<Disciple>> = emptyMap(),
        rngManager: GameRngManager
    ): Boolean {
        // AUTHORITATIVE 下经 C++ 判定（sect_attack_decision.h checkAttackConditions——
        // 消费 BATTLE 分区；原生失败/未加载回退 Kotlin）
        tryNativeCheckAttackConditions(attacker, defender, playerGarrisonMap)?.let { return it }

        val attackerDisciples = (aiDisciplesMap[attacker.id] ?: emptyList())
            .filter { it.isAlive }
        if (failsAttackHardConstraints(attacker, defender, attackerDisciples)) return false

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

        return rngManager.getRng(RngPartition.BATTLE).nextDouble() < chance
    }

    /**
     * AUTHORITATIVE 下经 C++ 判定 AI vs AI 逐目标攻击（sect_attack_decision.h
     * checkAttackConditions）。降级：flag 关/native 未加载/异常 → null（调用方回退 Kotlin）。
     * 本函数与 checkAttackConditions 一并保留为跨语言对拍 Kotlin 基准
     * （DiffSectAttackDecisionTest 侧 Kotlin 基准使用）。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun tryNativeCheckAttackConditions(
        attacker: WorldSect,
        defender: WorldSect,
        playerGarrisonMap: Map<String, List<Disciple>>
    ): Boolean? {
        if (!NativeEngineFlag.authoritative || !GameCoreBridge.isLoaded) return null
        val garrisonJson = buildJsonObject {
            for ((sectId, disciples) in playerGarrisonMap) {
                putJsonArray(sectId) {
                    disciples.forEach { add(Json.encodeToJsonElement(Disciple.serializer(), it)) }
                }
            }
        }
        return try {
            GameCoreBridge.nativeCheckAttackConditions(
                attacker.id, defender.id, garrisonJson.toString().encodeToByteArray()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w(TAG, "nativeCheckAttackConditions degraded to Kotlin: $e")
            null
        }
    }

    /** 攻击硬约束：不能攻击自己 + 最低弟子数 + 同联盟不攻击 */
    private fun failsAttackHardConstraints(
        attacker: WorldSect,
        defender: WorldSect,
        attackerDisciples: List<Disciple>
    ): Boolean {
        if (attacker.id == defender.id) return true
        if (attackerDisciples.size < MIN_DISCIPLES_FOR_ATTACK) return true
        // 同联盟不攻击（硬约束）
        return attacker.allianceId.isNotEmpty() && attacker.allianceId == defender.allianceId
    }

    // ── AI 攻玩家决策链（P2-18 Stage 1 下沉 C++ sect_attack_decision.h
    //    decidePlayerAttack；生产调用方 PlayerDefenseProcessor 已删——本段
    //    保留为跨语言对拍 Kotlin 基准，DiffSectAttackDecisionTest 侧 B 使用，
    //    与 PhaseSettlementExecutor 对拍基线先例同型。改语义须双端同步）──

    /**
     * AI决定攻击玩家的结果——不再是立即执行战斗，
     * 而是返回【是否应生成预警】或【是否应跳过】。
     *
     * 预警生成后进入单级"即将进攻"生命周期（下月直接进攻），
     * 到期后才执行实际战斗。
     */
    sealed interface PlayerAttackDecision {
        /** 不攻击（保护期/附庸/冷却/好感度>0 等） */
        data object Skip : PlayerAttackDecision
        /** 生成"即将进攻"预警 */
        data class GenerateWarning(
            val attackerSectId: String,
            val attackerSectName: String
        ) : PlayerAttackDecision
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
                hasViableAttackTarget(sect, target, sectPower, personality, gameData, aiDisciplesMap)
            }

            if (!hasTarget) {
                sectsWithNoTargets.add(sect.id)
            }
        }

        return sectsWithNoTargets
    }

    internal fun convertToCombatant(
        disciple: Disciple,
        side: CombatantSide,
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): Combatant {
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

        val stats = disciple.getFinalStats(equipmentMap, manualMap, manualProficiencies, bloodRefinementPct)

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
     * 构建战斗技能列表（熟练度加成调整伤害倍率）。
     *
     * 经 [ManualInstance.toCombatSkill] 全字段保留（skillType/isAoe/buff/heal/
     * shield/控制/拉条等），AI 宗门弟子功法技能与主引擎语义一致。
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

    /** AI 宗门战结果（[executeUnifiedAIBattle] 返回；internal 供对拍测试） */
    internal data class UnifiedAIBattleResult(
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val winner: AIBattleWinner,
        val turns: Int,
        val rounds: List<BattleLogRound> = emptyList()
    )

    /**
     * AI 宗门战核心（第三战斗引擎；internal 供
     * DiffSectBattleTest 同模块对拍，生产私有路由经 executeSectBattleCore）。
     */
    internal fun executeUnifiedAIBattle(
        attackers: List<Combatant>,
        defenders: List<Combatant>,
        rng: DeterministicRng
    ): UnifiedAIBattleResult {
        var currentAttackers = attackers.toMutableList()
        var currentDefenders = defenders.toMutableList()
        var turn = 0
        var timedOut = false
        var ended = false
        val rounds = mutableListOf<BattleLogRound>()
        val startTime = System.currentTimeMillis()

        while (turn < GameConfig.AI.MAX_BATTLE_TURNS && !timedOut && !ended) {
            val outcome = executeAiRound(currentAttackers, currentDefenders, startTime, turn + 1, rng)
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

    internal data class AiBattleRoundOutcome(
        val timedOut: Boolean,
        val ended: Boolean,
        val attackers: List<Combatant>,
        val defenders: List<Combatant>,
        val round: BattleLogRound
    )

    /**
     * AI 战斗单回合写回上下文：双方实时列表 + 双方索引映射 + 行动日志
     * （executeAiCombatantTurn 构建，普攻/单体/AOE 全臂共享——替代逐参数透传）。
     */
    internal data class BattleWriteBackContext(
        val allies: MutableList<Combatant>,
        val enemies: MutableList<Combatant>,
        val alliesIndexMap: Map<String, Int>,
        val enemiesIndexMap: Map<String, Int>,
        val roundActions: MutableList<BattleLogAction>
    )

    /** 技能决策结果（局部传递，与 BattleSystem 同构） */
    internal data class AiSkillDecision(
        val skill: CombatSkill?,
        val action: BattleAI.AIAction?
    )

    /**
     * 补充队伍到满编 — 用后备弟子填充。
     */
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

/** 单个攻击目标可行性判定：硬约束 + 战力门槛 + chance>0（不执行 RNG） */
private fun hasViableAttackTarget(
    sect: WorldSect,
    target: WorldSect,
    sectPower: Long,
    personality: AISectPersonality,
    gameData: GameData,
    aiDisciplesMap: Map<String, List<Disciple>>
): Boolean {
    if (target.id == sect.id || target.occupierSectId == sect.id) return false
    if (sect.allianceId.isNotEmpty() && sect.allianceId == target.allianceId) return false

    val targetDisciples = if (target.isPlayerOccupied) {
        emptyList() // 玩家占领的宗门可能有驻军，不确定时视为有目标
    } else {
        (aiDisciplesMap[target.id] ?: emptyList()).filter { it.isAlive }
    }
    if (targetDisciples.isEmpty() && !target.isPlayerSect && !target.isPlayerOccupied) return false

    val targetPower = SectCombatPowerCalculator.calculateSectPower(targetDisciples)
    if (targetPower <= 0) return false
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
    return chance > 0.0
}
