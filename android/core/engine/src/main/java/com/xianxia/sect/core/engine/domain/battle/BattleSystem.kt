package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition

import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.getAffixCombatEffects
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getPhysiqueEffects
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameUtils



@Singleton
class BattleSystem @Inject constructor(
    private val rngManager: GameRngManager
) {
    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    internal val rng get() = rngManager.getRng(RngPartition.BATTLE)

    /**
     * 预计算妖兽属性。
     * 在 LevelGenerator 生成妖兽时已完成含随机方差的属性计算，
     * 战斗时直接使用此数据，不再重新随机。
     */
    data class BeastPreGenStats(
        val maxHp: Int,
        val maxMp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int = 1
    )

    internal data class BeastCombatStats(
        val hp: Int,
        val mp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int
    )

    /** 回合执行上下文（随回合变化的可变战斗状态容器） */
    internal data class TurnContext(
        val team: MutableList<Combatant>,
        val beasts: MutableList<Combatant>,
        val teamIndexMap: Map<String, Int>,
        val beastsIndexMap: Map<String, Int>,
        val actions: MutableList<BattleActionData>,
        // 本回合号（battle.turn + 1）：战斗措辞确定性选词的回合盐
        //（W4-C 随机源治理·战斗侧：措辞抽取不入任何分区，见 BattleDescriptionGenerator KDoc）
        val turn: Int
    )

    /** 单参战者回合执行结果（早退/跳过信号） */
    internal sealed interface TurnOutcome {
        data object Continue : TurnOutcome
        data object EndBattle : TurnOutcome
    }

    /** 回合双方阵营解析打包（executeCombatantTurn 提取，消除 4 个三元 if） */
    internal data class TurnSides(
        val allies: MutableList<Combatant>,
        val enemies: MutableList<Combatant>,
        val alliesIndexMap: Map<String, Int>,
        val enemiesIndexMap: Map<String, Int>
    )

    /**
     * 回合行动记录打包（executeCombatantTurn 用，8 参超限打包）。
     */
    internal data class ActionRecordData(
        val availableSkill: CombatSkill?,
        val isSupportSkill: Boolean,
        val isAoeSkill: Boolean,
        val isInstantKill: Boolean,
        val isTeamMember: Boolean,
        val result: AttackResult,
        val turnMessage: TurnMessage,
        val currentCombatant: Combatant,
        val isCrit: Boolean
    )

    /**
     * 执行当前参战者的行动：支援（单目标/全体）/ AOE 技能 / 单体技能 / 普攻四分支。
     *
     * @return 行动结果列表（AOE 为多目标，其余单元素）
     */
    internal data class SkillActionContext(
        val currentCombatant: Combatant,
        val aliveEnemies: List<Combatant>,
        val allies: MutableList<Combatant>,
        val isTeamMember: Boolean,
        val playerDamageModifier: Double
    )

    internal data class TurnMessage(
        val text: String,
        val isKill: Boolean,
        val totalDamage: Int
    )

    /** 技能决策结果：技能 + 配对的 AI 目标（局部传递） */
    internal data class SkillDecision(
        val skill: CombatSkill?,
        val action: BattleAI.AIAction?
    )


    @Suppress("LongParameterList") // 战斗组装入参聚合（10 参数，纯组装无逻辑，含血炼映射）
    fun createBattle(
        disciples: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        beastLevel: Int,
        beastCount: Int? = null,
        beastType: String? = null,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap(),
        beastPreGenStats: BeastPreGenStats? = null,
        bloodRefinementMap: Map<String, BloodRefinementPctTotal> = emptyMap(),
        equipmentMapByDisciple: Map<String, Map<String, EquipmentInstance>> = emptyMap()
    ): Battle {
        val combatants = disciples.map { disciple ->
            val discipleEquipmentMap = equipmentMapByDisciple[disciple.id] ?: equipmentMap
            convertDiscipleToCombatant(
                disciple, discipleEquipmentMap, manualMap, manualProficiencies,
                CombatantSide.DEFENDER, bloodRefinementPct = bloodRefinementMap[disciple.id]
            )
        }

        val beastRealm = if (beastLevel in 0..9) {
            beastLevel
        } else {
            GameUtils.calculateBeastRealm(
                disciples,
                realmExtractor = { it.realm },
                layerExtractor = { it.realmLayer }
            )
        }

        val actualBeastCount = (beastCount ?: GameConfig.Battle.MIN_BEAST_COUNT).coerceAtLeast(1)

        val beasts = (1..actualBeastCount).map { index ->
            createBeast(beastRealm, index, beastType, beastPreGenStats)
        }

        return Battle(
            team = combatants,
            beasts = beasts,
            turn = 0,
            isFinished = false,
            winner = null
        )
    }

    /**
     * @param playerDamageModifier 玩家阵营伤害倍率（如严苛训练政策 +5%；默认 1.0）。
     * 参数透传替代原 @Volatile 单例字段（设置-执行-重置模式在异常中断时会污染后续战斗）。
     */
    fun executeBattle(battle: Battle, playerDamageModifier: Double = 1.0): BattleSystemResult {
        return executeBattleWithTimeout(battle, GameConfig.Battle.MAX_BATTLE_DURATION_MS, playerDamageModifier)
    }

    fun executeBattleWithTimeout(
        battle: Battle,
        timeoutMs: Long = GameConfig.Battle.MAX_BATTLE_DURATION_MS,
        playerDamageModifier: Double = 1.0
    ): BattleSystemResult {
        val startTime = System.currentTimeMillis()
        var currentBattle = battle
        val rounds = mutableListOf<BattleRoundData>()
        val (teamMembers, enemies) = buildBattleSnapshots(battle)

        var timedOut = false

        while (!currentBattle.isFinished && currentBattle.turn < currentBattle.maxTurns) {
            val elapsed = System.currentTimeMillis() - startTime

            if (elapsed > timeoutMs) {
                timedOut = true
                break
            }

            if (elapsed > GameConfig.Battle.BATTLE_TIMEOUT_WARNING_MS && currentBattle.turn % 5 == 0) {
                DomainLog.w("BattleSystem",
                    "Battle taking long: ${elapsed}ms, turn ${currentBattle.turn}/${currentBattle.maxTurns}")
            }

            val turnResult = executeTurnWithLog(currentBattle, playerDamageModifier)
            currentBattle = turnResult.first
            if (turnResult.second.actions.isNotEmpty()) {
                rounds.add(turnResult.second)
            }

            updateBattleSnapshots(teamMembers, enemies, currentBattle)
        }

        val aliveTeam = currentBattle.team.count { !it.isDead }
        val aliveBeasts = currentBattle.beasts.count { !it.isDead }

        if (timedOut) {
            DomainLog.w("BattleSystem", "Battle timed out after ${System.currentTimeMillis() - startTime}ms")
        }
        val winner = resolveBattleWinner(timedOut, aliveTeam, aliveBeasts)

        val finalBattle = currentBattle.copy(
            isFinished = true,
            winner = winner
        )

        return BattleSystemResult(
            battle = finalBattle,
            victory = winner == BattleWinner.TEAM,
            rewards = if (winner == BattleWinner.TEAM) generateRewards(battle.beasts.size) else emptyMap(),
            log = BattleLogData(
                rounds = rounds,
                teamMembers = teamMembers,
                enemies = enemies
            ),
            timedOut = timedOut,
            durationMs = System.currentTimeMillis() - startTime,
            turnCount = currentBattle.turn
        )
    }

    fun convertDiscipleToCombatant(
        disciple: Disciple,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        side: CombatantSide = CombatantSide.DEFENDER,
        fullHeal: Boolean = false,
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): Combatant {
        val discipleProficiencies = manualProficiencies[disciple.id] ?: emptyMap()
        val stats = disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies, bloodRefinementPct)
        val skills = disciple.manualIds.mapNotNull { manualId ->
            val manual = manualMap[manualId] ?: return@mapNotNull null
            val proficiencyData = discipleProficiencies[manualId]
            val masteryLevel = proficiencyData?.masteryLevel ?: 0
            val baseSkill = manual.skill ?: return@mapNotNull null
            val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                baseSkill.damageMultiplier,
                masteryLevel
            )
            baseSkill.copy(
                damageMultiplier = adjustedMultiplier
            ).toCombatSkill(manualName = manual.name)
        }

        val effectiveHp = if (fullHeal) stats.maxHp
            else if (disciple.combat.currentHp < 0) stats.maxHp
            else disciple.combat.currentHp.coerceAtMost(stats.maxHp)
        val effectiveMp = if (fullHeal) stats.maxMp
            else if (disciple.combat.currentMp < 0) stats.maxMp
            else disciple.combat.currentMp.coerceAtMost(stats.maxMp)

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
            hp = effectiveHp,
            maxHp = stats.maxHp,
            mp = effectiveMp,
            maxMp = stats.maxMp,
            physicalAttack = stats.physicalAttack,
            magicAttack = stats.magicAttack,
            physicalDefense = stats.physicalDefense,
            magicDefense = stats.magicDefense,
            speed = stats.speed,
            critRate = stats.critRate,
            skills = skills,
            realm = disciple.realm,
            realmName = GameConfig.Realm.getName(disciple.realm),
            realmLayer = disciple.realmLayer,
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

}

