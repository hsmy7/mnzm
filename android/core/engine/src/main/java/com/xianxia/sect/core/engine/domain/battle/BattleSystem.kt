package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BattleSystem @Inject constructor(
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.BATTLE)

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
        val realmLayer: Int = 1
    )

    fun createBattle(
        disciples: List<Disciple>,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        beastLevel: Int,
        beastCount: Int? = null,
        beastType: String? = null,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap(),
        beastPreGenStats: BeastPreGenStats? = null
    ): Battle {
        val combatants = disciples.map { disciple ->
            convertDiscipleToCombatant(disciple, equipmentMap, manualMap, manualProficiencies, CombatantSide.DEFENDER)
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

    fun convertDiscipleToCombatant(
        disciple: Disciple,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        side: CombatantSide = CombatantSide.DEFENDER,
        fullHeal: Boolean = false
    ): Combatant {
        val discipleProficiencies = manualProficiencies[disciple.id] ?: emptyMap()
        val stats = disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies)
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

    private fun createBeast(
        beastRealm: Int,
        index: Int,
        beastType: String? = null,
        preGenStats: BeastPreGenStats? = null
    ): Combatant {
        val realmIndex = beastRealm.coerceIn(0, 9)

        val type = if (beastType != null) {
            GameConfig.Beast.TYPES.find { it.name == beastType } ?: GameConfig.Beast.getType(0)
        } else {
            GameConfig.Beast.getType(0)
        }

        val hp: Int
        val mp: Int
        val physicalAttack: Int
        val magicAttack: Int
        val physicalDefense: Int
        val magicDefense: Int
        val speed: Int
        val realmLayer: Int

        if (preGenStats != null) {
            // 使用预计算属性（生成时已含随机方差，地图显示战力 = 战斗实际战力）
            val s = preGenStats
            // 钳制防止存档篡改或数据损坏导致异常值
            hp = s.maxHp.coerceIn(1, 10_000_000)
            mp = s.maxMp.coerceAtLeast(0)
            physicalAttack = s.physicalAttack.coerceAtLeast(0)
            magicAttack = s.magicAttack.coerceAtLeast(0)
            physicalDefense = s.physicalDefense.coerceAtLeast(0)
            magicDefense = s.magicDefense.coerceAtLeast(0)
            speed = s.speed.coerceAtLeast(0)
            realmLayer = s.realmLayer
        } else {
            // 向后兼容：旧存档妖兽无预计算属性时，用基础值（不含随机方差）确保战斗不崩溃
            val rl = 5 // 默认中层
            val layerMult = 1.0 + (rl - 1) * 0.1
            val stats = GameConfig.Beast.getRealmStats(realmIndex)

            hp = (stats.hp * layerMult * type.hpMod).toInt()
            mp = (stats.mp * layerMult * type.hpMod).toInt()
            physicalAttack = (stats.attack * layerMult * type.atkMod).toInt()
            magicAttack = (stats.attack * layerMult * type.atkMod).toInt()
            physicalDefense = (stats.defense * layerMult * type.defMod).toInt()
            magicDefense = (stats.defense * layerMult * type.defMod).toInt()
            speed = (stats.speed * layerMult * type.speedMod).toInt()
            realmLayer = rl
        }

        val beastSkills = type.skills.map { skillConfig ->
            CombatSkill(
                name = skillConfig.name,
                skillType = skillConfig.skillType,
                damageType = skillConfig.damageType,
                damageMultiplier = skillConfig.damageMultiplier,
                mpCost = skillConfig.mpCost,
                cooldown = skillConfig.cooldown,
                hits = skillConfig.hits,
                healPercent = skillConfig.healPercent,
                healFixed = skillConfig.healFixed,
                healType = skillConfig.healType,
                buffType = skillConfig.buffType,
                buffValue = skillConfig.buffValue,
                buffDuration = skillConfig.buffDuration,
                buffs = skillConfig.buffs,
                isAoe = skillConfig.isAoe,
                targetScope = skillConfig.targetScope,
                shieldPercent = skillConfig.shieldPercent,
                turnAdvancePercent = skillConfig.turnAdvancePercent,
                damageSharePercent = skillConfig.damageSharePercent,
                damageLinkPercent = skillConfig.damageLinkPercent,
                skillDescription = skillConfig.skillDescription
            )
        }

        val typeIndex = GameConfig.Beast.TYPES.indexOf(type)

        return Combatant(
            id = "beast_$index",
            name = "${type.prefix}${type.name}",
            side = CombatantSide.ATTACKER,
            hp = hp,
            maxHp = hp,
            mp = mp,
            maxMp = mp,
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = physicalDefense,
            magicDefense = magicDefense,
            speed = speed,
            critRate = 0.05 + realmIndex * 0.01,
            skills = beastSkills,
            realm = realmIndex,
            realmName = GameConfig.Realm.getName(realmIndex),
            realmLayer = realmLayer,
            element = type.element,
            portraitRes = "beast_$typeIndex",
            isBeast = true
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
                DomainLog.w("BattleSystem", "Battle taking long: ${elapsed}ms, turn ${currentBattle.turn}/${currentBattle.maxTurns}")
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

    /** 战斗初始快照构建（executeBattleWithTimeout 提取） */
    private fun buildBattleSnapshots(battle: Battle): Pair<MutableList<BattleMemberData>, MutableList<BattleEnemyData>> {
        val teamMembers = battle.team.map { combatant ->
            BattleMemberData(
                id = combatant.id,
                name = combatant.name,
                realm = combatant.realm,
                realmName = combatant.realmName,
                hp = combatant.hp,
                maxHp = combatant.maxHp,
                mp = combatant.mp,
                maxMp = combatant.maxMp,
                isAlive = true,
                portraitRes = combatant.portraitRes
            )
        }.toMutableList()
        val enemies = battle.beasts.map { combatant ->
            BattleEnemyData(
                id = combatant.id,
                name = combatant.name,
                realm = combatant.realm,
                realmName = combatant.realmName,
                realmLayer = combatant.realmLayer,
                hp = combatant.hp,
                maxHp = combatant.maxHp,
                isAlive = true,
                portraitRes = combatant.portraitRes
            )
        }.toMutableList()
        return teamMembers to enemies
    }

    /** 战斗快照逐回合刷新（executeBattleWithTimeout 提取） */
    private fun updateBattleSnapshots(
        teamMembers: MutableList<BattleMemberData>,
        enemies: MutableList<BattleEnemyData>,
        currentBattle: Battle
    ) {
        teamMembers.forEachIndexed { index, member ->
            val combatant = currentBattle.team.find { it.id == member.id }
            if (combatant != null) {
                teamMembers[index] = member.copy(
                    isAlive = !combatant.isDead,
                    hp = combatant.hp,
                    mp = combatant.mp
                )
            } else {
                teamMembers[index] = member.copy(
                    isAlive = false,
                    hp = 0,
                    mp = 0
                )
            }
        }

        enemies.forEachIndexed { index, enemy ->
            val beast = currentBattle.beasts.find { it.id == enemy.id }
            if (beast != null) {
                enemies[index] = enemy.copy(
                    isAlive = !beast.isDead,
                    hp = beast.hp
                )
            } else {
                enemies[index] = enemy.copy(
                    isAlive = false,
                    hp = 0
                )
            }
        }
    }

    /** 战斗胜者判定（executeBattleWithTimeout 提取） */
    private fun resolveBattleWinner(timedOut: Boolean, aliveTeam: Int, aliveBeasts: Int): BattleWinner = when {
        timedOut -> if (aliveTeam > aliveBeasts) BattleWinner.TEAM
        else if (aliveBeasts > aliveTeam) BattleWinner.BEASTS else BattleWinner.DRAW
        aliveTeam == 0 -> BattleWinner.BEASTS
        aliveBeasts == 0 -> BattleWinner.TEAM
        else -> BattleWinner.DRAW
    }

    private fun executeTurnWithLog(battle: Battle, playerDamageModifier: Double): Pair<Battle, BattleRoundData> {
        val allCombatants = (battle.team + battle.beasts)
            .filter { !it.isDead }
            .sortedByDescending { it.effectiveSpeed }

        val ctx = TurnContext(
            team = battle.team.toMutableList(),
            beasts = battle.beasts.toMutableList(),
            teamIndexMap = battle.team.withIndex().associate { it.value.id to it.index },
            beastsIndexMap = battle.beasts.withIndex().associate { it.value.id to it.index },
            actions = mutableListOf()
        )

        for (combatant in allCombatants) {
            if (combatant.isDead) continue
            val outcome = executeCombatantTurn(ctx, combatant, playerDamageModifier)
            if (outcome is TurnOutcome.EndBattle) {
                return Pair(
                    battle.copy(team = ctx.team, beasts = ctx.beasts, isFinished = true),
                    BattleRoundData(battle.turn + 1, ctx.actions)
                )
            }
        }

        processDotEffects(ctx.team, ctx.beasts, ctx.actions)

        return Pair(
            battle.copy(
                team = ctx.team,
                beasts = ctx.beasts,
                turn = battle.turn + 1
            ),
            BattleRoundData(battle.turn + 1, ctx.actions)
        )
    }

    /** 回合执行上下文（C-6 拆分——随回合变化的可变战斗状态容器） */
    private data class TurnContext(
        val team: MutableList<Combatant>,
        val beasts: MutableList<Combatant>,
        val teamIndexMap: Map<String, Int>,
        val beastsIndexMap: Map<String, Int>,
        val actions: MutableList<BattleActionData>
    )

    /** 单参战者回合执行结果（C-6：早退/跳过信号） */
    private sealed interface TurnOutcome {
        data object Continue : TurnOutcome
        data object EndBattle : TurnOutcome
    }

    /**
     * 执行单参战者回合（C-6 从 executeTurnWithLog 循环体提取）。
     *
     * 控制效果检查 → 技能/攻击选择执行 → 行动记录 → 伤害结算 → 冷却/治疗/拉条。
     * 逐行搬移（2026-08-02），RNG 调用顺序与内联时完全一致
     * （rng 经类属性访问，GameRngManager 分区调用序不变）。
     *
     * @param ctx 回合上下文（team/beasts 原地修改）
     * @param combatant 当前行动的参战者（按速度排序遍历）
     * @return Continue 继续回合；EndBattle 敌方全灭提前结束
     */
    @Suppress("ReturnCount") // 卫语句密集的回合控制函数（判死/全灭/控制效果 4 处提前退出）
    private fun executeCombatantTurn(
        ctx: TurnContext,
        combatant: Combatant,
        playerDamageModifier: Double
    ): TurnOutcome {
        if (combatant.isDead) return TurnOutcome.Continue

        val isTeamMember = combatant.side == CombatantSide.DEFENDER
        val sides = resolveTurnSides(ctx, isTeamMember)
        val allies = sides.allies
        val enemies = sides.enemies

        val aliveEnemies = enemies.filter { !it.isDead }
        if (aliveEnemies.isEmpty()) return TurnOutcome.EndBattle

        // 以 ctx 当前状态判死：回合内被击杀的单位（快照仍存活）不得继续出手
        val currentCombatant = allies.firstOrNull { it.id == combatant.id } ?: combatant
        if (currentCombatant.isDead) return TurnOutcome.Continue

        // 控制效果（眩晕/冰冻）：跳过行动并结算 BUFF，回合提前结束
        applyControlEffects(ctx, currentCombatant, isTeamMember, allies, sides.alliesIndexMap)
            ?.let { return it }

        val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
        val skillDecision = selectSkill(
            currentCombatant, aliveEnemies, allies, silenceBuff != null, playerDamageModifier
        )
        val availableSkill = skillDecision.skill

        val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
        val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill
        val results = executeSkillAction(
            SkillActionContext(currentCombatant, aliveEnemies, allies, isTeamMember, playerDamageModifier),
            availableSkill, isSupportSkill, isAoeSkill, skillDecision.action
        )

        val result = results.first(); val isInstantKill = results.any { it.isInstantKill }

        val turnMessage = buildTurnMessage(
            isInstantKill = isInstantKill,
            result = result,
            availableSkill = availableSkill,
            isAoeSkill = isAoeSkill,
            results = results,
            currentCombatant = currentCombatant
        )

        recordTurnAction(ctx, ActionRecordData(
            availableSkill = availableSkill,
            isSupportSkill = isSupportSkill,
            isAoeSkill = isAoeSkill,
            isInstantKill = isInstantKill,
            isTeamMember = isTeamMember,
            result = result,
            turnMessage = turnMessage,
            currentCombatant = currentCombatant,
            isCrit = results.any { it.isCrit }
        ))

        if (!result.isSupport) {
            applyDamageEffects(
                ctx, results, sides.enemiesIndexMap, isTeamMember,
                currentCombatant, availableSkill, isAoeSkill, aliveEnemies
            )
        }

        if (availableSkill != null) {
            applyCooldownUpdate(ctx, sides.alliesIndexMap, currentCombatant, availableSkill, isTeamMember)
            if (isSupportSkill) {
                applySupportEffects(ctx, result, allies, isTeamMember, currentCombatant, playerDamageModifier)
            }
        } else {
            updateCombatantBuffs(currentCombatant, allies, sides.alliesIndexMap)
        }
        return TurnOutcome.Continue
    }

    /** 回合双方阵营解析打包（executeCombatantTurn 提取，消除 4 个三元 if） */
    private data class TurnSides(
        val allies: MutableList<Combatant>,
        val enemies: MutableList<Combatant>,
        val alliesIndexMap: Map<String, Int>,
        val enemiesIndexMap: Map<String, Int>
    )

    /** 回合双方阵营解析（executeCombatantTurn 提取） */
    private fun resolveTurnSides(ctx: TurnContext, isTeamMember: Boolean): TurnSides {
        return if (isTeamMember) {
            TurnSides(ctx.team, ctx.beasts, ctx.teamIndexMap, ctx.beastsIndexMap)
        } else {
            TurnSides(ctx.beasts, ctx.team, ctx.beastsIndexMap, ctx.teamIndexMap)
        }
    }

    /**
     * 回合行动记录打包（C-6 拆分 executeCombatantTurn 用，8 参超限打包）。
     */
    private data class ActionRecordData(
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
     * 记录参战者本次行动到战斗动作列表（C-6 从 executeCombatantTurn 提取）。
     */
    private fun recordTurnAction(ctx: TurnContext, data: ActionRecordData) {
        val isKill = data.turnMessage.isKill
        ctx.actions.add(BattleActionData(
            type = when {
                data.isSupportSkill -> "support"
                data.availableSkill != null -> "skill"
                else -> "attack"
            },
            attacker = data.currentCombatant.name,
            attackerType = if (data.isTeamMember) "disciple" else
                if (data.currentCombatant.isBeast) "beast" else "disciple",
            target = if (data.isSupportSkill) "ctx.team" else
                if (data.isAoeSkill) "全体敌人" else data.result.target.name,
            damage = if (data.isAoeSkill) data.turnMessage.totalDamage else data.result.damage,
            damageType = if (data.isInstantKill) "必杀" else if (data.result.isSupport) "support" else
                if (data.result.isDodged) "闪避" else if (data.result.isPhysical) "物理" else "法术",
            isCrit = data.isCrit,
            isKill = isKill,
            isInstantKill = data.isInstantKill,
            message = data.turnMessage.text,
            skillName = data.result.skillName
        ))
    }

    /**
     * 技能冷却更新写回（C-6 从 executeCombatantTurn 提取）。
     */
    private fun applyCooldownUpdate(
        ctx: TurnContext,
        alliesIndexMap: Map<String, Int>,
        currentCombatant: Combatant,
        availableSkill: CombatSkill,
        isTeamMember: Boolean
    ) {
        val combatantIndex = alliesIndexMap[currentCombatant.id] ?: -1
        if (combatantIndex >= 0) {
            val updatedCombatant = BattleCalculator.updateCombatantCooldowns(currentCombatant, availableSkill)
            if (isTeamMember) {
                ctx.team[combatantIndex] = updatedCombatant
            } else {
                ctx.beasts[combatantIndex] = updatedCombatant
            }
        }
    }

    /**
     * 支援技能效果应用：治疗写回 + 团队 BUFF + 拉条（C-6 从 executeCombatantTurn 提取）。
     */
    private fun applySupportEffects(
        ctx: TurnContext,
        result: AttackResult,
        allies: MutableList<Combatant>,
        isTeamMember: Boolean,
        currentCombatant: Combatant,
        playerDamageModifier: Double
    ) {
        applySupportHealing(ctx, result, allies, isTeamMember)
        applySupportTeamBuffs(ctx, result, allies, isTeamMember)

        // Turn advance: target ally acts immediately after current combatant
        if (result.turnAdvancePercent > 0) {
            val enemiesIndexMap = if (isTeamMember) ctx.beastsIndexMap else ctx.teamIndexMap
            processTurnAdvance(
                ctx, result, allies, currentCombatant, isTeamMember, enemiesIndexMap,
                playerDamageModifier
            )
        }
    }

    /** 支援治疗写回（applySupportEffects 提取，按 allies 定位、按 isTeamMember 分写） */
    private fun applySupportHealing(
        ctx: TurnContext,
        result: AttackResult,
        allies: MutableList<Combatant>,
        isTeamMember: Boolean
    ) {
        if (result.healedIds.isEmpty()) return
        // 治疗按 allies 定位、按 isTeamMember 分写（修复：原硬编码 ctx.team 致敌方治疗无效）
        result.healedIds.forEach { healedId ->
            val healedIndex = allies.indexOfFirst { it.id == healedId }
            if (healedIndex >= 0) {
                val healed = allies[healedIndex]
                val updated = if (result.healType == HealType.MP) {
                    healed.copy(mp = minOf(healed.mp + result.healAmount, healed.maxMp))
                } else {
                    healed.copy(hp = minOf(healed.hp + result.healAmount, healed.maxHp))
                }
                if (isTeamMember) {
                    ctx.team[healedIndex] = updated
                } else {
                    ctx.beasts[healedIndex] = updated
                }
            }
        }
    }

    /** 团队 BUFF 写回（applySupportEffects 提取） */
    private fun applySupportTeamBuffs(
        ctx: TurnContext,
        result: AttackResult,
        allies: MutableList<Combatant>,
        isTeamMember: Boolean
    ) {
        if (result.teamBuffs.isEmpty()) return
        result.teamBuffs.forEach { (memberId, buffs) ->
            val memberIndex = allies.indexOfFirst { it.id == memberId }
            if (memberIndex >= 0) {
                val member = allies[memberIndex]
                val existingBuffs = member.buffs.filter { it.remainingDuration > 0 }
                val updated = member.copy(buffs = existingBuffs + buffs)
                if (isTeamMember) {
                    ctx.team[memberIndex] = updated
                } else {
                    ctx.beasts[memberIndex] = updated
                }
            }
        }
    }

    /**
     * 执行当前参战者的行动：支援（单目标/全体）/ AOE 技能 / 单体技能 / 普攻四分支。
     *
     * @return 行动结果列表（AOE 为多目标，其余单元素）
     */
    private data class SkillActionContext(
        val currentCombatant: Combatant,
        val aliveEnemies: List<Combatant>,
        val allies: MutableList<Combatant>,
        val isTeamMember: Boolean,
        val playerDamageModifier: Double
    )

    private fun executeSkillAction(
        ctx: SkillActionContext,
        availableSkill: CombatSkill?,
        isSupportSkill: Boolean,
        isAoeSkill: Boolean,
        aiAction: BattleAI.AIAction?
    ): List<AttackResult> = when {
        availableSkill == null -> executeBasicAttackAction(ctx, aiAction)
        isSupportSkill -> if (availableSkill.targetScope == "ally") executeAllyScopeSupport(ctx, availableSkill)
        else executeTeamSupport(ctx, availableSkill)
        isAoeSkill -> executeAoeSkillAction(ctx, availableSkill)
        else -> executeSingleSkillAction(ctx, availableSkill, aiAction)
    }

    /** 友方单体支援（executeSkillAction 提取）：随机选一名存活友方施放，保留 RNG 抽数位置 */
    private fun executeAllyScopeSupport(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> {
        val validAllies = ctx.allies.filter { !it.isDead && it.id != ctx.currentCombatant.id }
        if (validAllies.isNotEmpty()) {
            val selectedAlly = validAllies[rng.nextInt(validAllies.size)]
            val supResult = executeSupportSkill(ctx.currentCombatant, listOf(selectedAlly), skill)
            // Mark the single ally as the target for turn advance
            return listOf(supResult.copy(
                healedIds = if (supResult.healAmount > 0) listOf(selectedAlly.id) else supResult.healedIds,
                teamBuffs = if (supResult.teamBuffs.isNotEmpty()) {
                    mapOf(selectedAlly.id to (supResult.teamBuffs.values.firstOrNull() ?: emptyList()))
                } else {
                    emptyMap()
                }
            ))
        }
        return listOf(executeSupportSkill(ctx.currentCombatant, listOf(ctx.currentCombatant), skill))
    }

    /** 团队支援（executeSkillAction 提取） */
    private fun executeTeamSupport(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> =
        listOf(executeSupportSkill(ctx.currentCombatant, ctx.allies.filter { !it.isDead }, skill))

    /** AOE 技能（executeSkillAction 提取） */
    private fun executeAoeSkillAction(ctx: SkillActionContext, skill: CombatSkill): List<AttackResult> {
        val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
        return ctx.aliveEnemies.map { target -> executeSkill(ctx.currentCombatant, target, skill, dmgMod) }
    }

    /** 单体技能（executeSkillAction 提取） */
    private fun executeSingleSkillAction(
        ctx: SkillActionContext,
        skill: CombatSkill,
        aiAction: BattleAI.AIAction?
    ): List<AttackResult> {
        val target = selectTarget(ctx.currentCombatant, ctx.aliveEnemies, aiAction)
        val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
        return listOf(executeSkill(ctx.currentCombatant, target, skill, dmgMod))
    }

    /** 普攻（executeSkillAction 提取） */
    private fun executeBasicAttackAction(ctx: SkillActionContext, aiAction: BattleAI.AIAction?): List<AttackResult> {
        val target = selectTarget(ctx.currentCombatant, ctx.aliveEnemies, aiAction)
        val dmgMod = if (ctx.isTeamMember) ctx.playerDamageModifier else 1.0
        return listOf(executeAttack(ctx.currentCombatant, target, dmgMod))
    }

    /**
     * 应用非支援行动的全部伤害效果：护盾吸收、伤害链接、伤害分摊、
     * 单体/AOE debuff 附加。原地修改 ctx 中 combatant 的 hp/buffs。
     *
     * 护盾/链接/分摊经共享应用层 [BattleDamageApplier]（与宗门战引擎语义一致）。
     */
    private fun applyDamageEffects(
        ctx: TurnContext,
        results: List<AttackResult>,
        enemiesIndexMap: Map<String, Int>,
        isTeamMember: Boolean,
        currentCombatant: Combatant,
        availableSkill: CombatSkill?,
        isAoeSkill: Boolean,
        aliveEnemies: List<Combatant>
    ) {
        results.forEach { r ->
            if (r.isDodged) return@forEach
            val targetIndex = enemiesIndexMap[r.target.id] ?: return@forEach
            val currentTarget = if (isTeamMember) ctx.beasts[targetIndex] else ctx.team[targetIndex]

            if (r.isInstantKill) {
                // 对抗性审查修复：斩杀（境界压制必杀）无视护盾直接击杀——
                // 与 AI 引擎（AISectAttackManager 斩杀分支直接 hp=0）语义一致，
                // 避免"战报显示必杀、实际护盾吸收后残血存活"的谎报矛盾
                if (isTeamMember) {
                    ctx.beasts[targetIndex] = currentTarget.copy(hp = 0)
                } else {
                    ctx.team[targetIndex] = currentTarget.copy(hp = 0)
                }
                return@forEach
            }

            // 护盾吸收 + 扣血 + 护盾余量写回
            if (isTeamMember) {
                ctx.beasts[targetIndex] = BattleDamageApplier.applyDamageToTarget(currentTarget, r.damage)
            } else {
                ctx.team[targetIndex] = BattleDamageApplier.applyDamageToTarget(currentTarget, r.damage)
            }
            // 伤害链接 / 伤害分摊（按更新映射写回）
            BattleDamageApplier.applyLinkedDamage(currentCombatant, currentTarget, r.damage, ctx.team, ctx.beasts)
                .forEach { (id, updated) -> writeBack(ctx, id, updated) }
            BattleDamageApplier.applySharedDamage(currentTarget, r.damage, ctx.team, ctx.beasts)
                .forEach { (id, updated) -> writeBack(ctx, id, updated) }

            applySkillDebuff(ctx, targetIndex, isTeamMember, currentCombatant, availableSkill, isAoeSkill)
            applyDamageLinkDebuff(ctx, targetIndex, isTeamMember, currentCombatant, availableSkill)
        }

        applyAoeDebuff(ctx, aliveEnemies, enemiesIndexMap, isTeamMember, currentCombatant, availableSkill, isAoeSkill)
    }

    /** 按 id 将更新后的 Combatant 写回 ctx 列表 */
    private fun writeBack(ctx: TurnContext, id: String, updated: Combatant) {
        val idxInTeam = ctx.team.indexOfFirst { it.id == id }
        if (idxInTeam >= 0) {
            ctx.team[idxInTeam] = updated
        } else {
            val idxInBeasts = ctx.beasts.indexOfFirst { it.id == id }
            if (idxInBeasts >= 0) ctx.beasts[idxInBeasts] = updated
        }
    }

    /** 技能 debuff 附加（非 AOE 时对目标施加技能自带的减益 BUFF） */
    private fun applySkillDebuff(
        ctx: TurnContext,
        targetIndex: Int,
        isTeamMember: Boolean,
        currentCombatant: Combatant,
        availableSkill: CombatSkill?,
        isAoeSkill: Boolean
    ) {
        val localBuffType = availableSkill?.buffType
        if (localBuffType == null || availableSkill.buffDuration <= 0 || isAoeSkill) return
        val debuff = CombatBuff(
            type = localBuffType,
            value = availableSkill.buffValue,
            remainingDuration = availableSkill.buffDuration,
            sourceRealm = currentCombatant.realm
        )
        if (isTeamMember && targetIndex < ctx.beasts.size) {
            ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(
                buffs = ctx.beasts[targetIndex].buffs + debuff
            )
        } else if (targetIndex < ctx.team.size) {
            ctx.team[targetIndex] = ctx.team[targetIndex].copy(
                buffs = ctx.team[targetIndex].buffs + debuff
            )
        }
    }

    /** 伤害链接 debuff：清除旧的链接标记并给目标附加新链接（同时仅一个） */
    private fun applyDamageLinkDebuff(
        ctx: TurnContext,
        targetIndex: Int,
        isTeamMember: Boolean,
        currentCombatant: Combatant,
        availableSkill: CombatSkill?
    ) {
        if (availableSkill?.damageLinkPercent == null || availableSkill.damageLinkPercent <= 0 ||
            availableSkill.buffDuration <= 0
        ) return
        val linkDebuff = CombatBuff(
            type = BuffType.DAMAGE_LINK,
            value = availableSkill.damageLinkPercent,
            remainingDuration = availableSkill.buffDuration,
            sourceRealm = currentCombatant.realm
        )
        val enemies = if (isTeamMember) ctx.beasts else ctx.team
        enemies.forEachIndexed { idx, enemy ->
            val hasLink = enemy.buffs.any { it.type == BuffType.DAMAGE_LINK }
            if (hasLink) {
                val cleaned = enemy.buffs.filter { it.type != BuffType.DAMAGE_LINK }
                if (isTeamMember && idx < ctx.beasts.size) ctx.beasts[idx] = ctx.beasts[idx].copy(buffs = cleaned)
                else if (idx < ctx.team.size) ctx.team[idx] = ctx.team[idx].copy(buffs = cleaned)
            }
        }
        if (isTeamMember && targetIndex < ctx.beasts.size) {
            ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(
                buffs = ctx.beasts[targetIndex].buffs + linkDebuff
            )
        } else if (targetIndex < ctx.team.size) {
            ctx.team[targetIndex] = ctx.team[targetIndex].copy(
                buffs = ctx.team[targetIndex].buffs + linkDebuff
            )
        }
    }

    /** AOE debuff：对全部存活敌人施加技能减益 BUFF */
    private fun applyAoeDebuff(
        ctx: TurnContext,
        aliveEnemies: List<Combatant>,
        enemiesIndexMap: Map<String, Int>,
        isTeamMember: Boolean,
        currentCombatant: Combatant,
        availableSkill: CombatSkill?,
        isAoeSkill: Boolean
    ) {
        if (!isAoeSkill || availableSkill?.buffType == null || availableSkill.buffDuration <= 0) return
        val aoeBuffType = availableSkill.buffType ?: return
        val debuff = CombatBuff(
            type = aoeBuffType,
            value = availableSkill.buffValue,
            remainingDuration = availableSkill.buffDuration,
            sourceRealm = currentCombatant.realm
        )
        aliveEnemies.filter { !it.isDead }.forEach { enemy ->
            val idx = enemiesIndexMap[enemy.id] ?: return@forEach
            if (isTeamMember && idx < ctx.beasts.size) {
                ctx.beasts[idx] = ctx.beasts[idx].copy(buffs = ctx.beasts[idx].buffs + debuff)
            } else if (idx < ctx.team.size) {
                ctx.team[idx] = ctx.team[idx].copy(buffs = ctx.team[idx].buffs + debuff)
            }
        }
    }

    private data class TurnMessage(
        val text: String,
        val isKill: Boolean,
        val totalDamage: Int
    )

    /**
     * 生成回合行动描述消息：必杀/支援/技能（AOE 与单体）/普攻四分支。
     * 纯函数：仅依赖入参生成消息与击杀判定，不修改战斗状态。
     */
    private fun buildTurnMessage(
        isInstantKill: Boolean,
        result: AttackResult,
        availableSkill: CombatSkill?,
        isAoeSkill: Boolean,
        results: List<AttackResult>,
        currentCombatant: Combatant
    ): TurnMessage {
        return when {
            isInstantKill -> TurnMessage(
                "境界碾压，${result.target.name}被一击必杀！", isKill = true, totalDamage = 0
            )
            result.isSupport && availableSkill != null -> TurnMessage(
                BattleDescriptionGenerator.generateSupportSkillDescription(
                    caster = currentCombatant,
                    skill = availableSkill,
                    healAmount = result.healAmount,
                    healType = result.healType,
                    buffs = availableSkill.buffs
                ),
                isKill = false,
                totalDamage = 0
            )
            availableSkill != null -> {
                // Long 求和防多段×多目标溢出为负（对抗性审查）
                val totalDamage = results.sumOf { it.damage.toLong() }
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                val isKill = results.any { r -> r.target.hp - r.damage <= 0 }
                val text = if (isAoeSkill) {
                    BattleDescriptionGenerator.generateAoeSkillDescription(
                        attacker = currentCombatant,
                        skill = availableSkill,
                        results = results,
                        isKill = isKill
                    )
                } else {
                    val singleTarget = result.target
                    BattleDescriptionGenerator.generateSkillDescription(
                        attacker = currentCombatant,
                        target = singleTarget,
                        skill = availableSkill,
                        result = result,
                        isKill = singleTarget.hp - result.damage <= 0
                    )
                }
                TurnMessage(text, isKill = isKill, totalDamage = totalDamage)
            }
            else -> TurnMessage(
                BattleDescriptionGenerator.generateAttackDescription(
                    attacker = currentCombatant,
                    target = result.target,
                    result = result,
                    isKill = result.target.hp - result.damage <= 0
                ),
                isKill = result.target.hp - result.damage <= 0,
                totalDamage = 0
            )
        }
    }

    /**
     * 拉条立即行动：被拉条的友方跳过等待立即执行一次行动（技能或普攻）。
     * 含伤害结算（护盾吸收）与冷却更新。
     */
    private fun processTurnAdvance(
        ctx: TurnContext,
        result: AttackResult,
        allies: MutableList<Combatant>,
        currentCombatant: Combatant,
        isTeamMember: Boolean,
        enemiesIndexMap: Map<String, Int>,
        playerDamageModifier: Double
    ) {
        val advancedAlly = resolveAdvancedAlly(result, allies, currentCombatant) ?: return
        val advAllies = if (isTeamMember) ctx.team else ctx.beasts
        val advEnemies = if (isTeamMember) ctx.beasts else ctx.team
        val advAliveEnemies = advEnemies.filter { !it.isDead }
        val advIdx = advAllies.indexOfFirst { it.id == advancedAlly.id }
        if (advIdx < 0 || advAliveEnemies.isEmpty()) return

        val (advSkill, advTarget) = decideAdvancedAction(advancedAlly, advAllies, advAliveEnemies)
        val advDmgMod = if (advancedAlly.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
        val advResult = executeAdvancedAction(advancedAlly, advTarget, advSkill, advDmgMod)
        val advDmg = if (advResult.isSupport) 0 else advResult.damage
        ctx.actions.add(buildAdvancedActionLog(advSkill, advResult, advancedAlly, isTeamMember, advDmg))
        if (!advResult.isSupport && !advResult.isDodged) {
            applyAdvancedDamage(ctx, advResult, advDmg, isTeamMember, enemiesIndexMap)
        }
        if (advSkill != null && advIdx >= 0) {
            updateAdvancedCooldown(ctx, advSkill, advancedAlly, isTeamMember, advIdx)
        }
    }

    /** 拉条目标解析（processTurnAdvance 提取）：3 个守卫收敛为单点返回 */
    private fun resolveAdvancedAlly(
        result: AttackResult,
        allies: MutableList<Combatant>,
        currentCombatant: Combatant
    ): Combatant? {
        val advancedId = result.healedIds.firstOrNull()
            ?: result.teamBuffs.keys.firstOrNull() ?: return null
        return allies.find { it.id == advancedId && !it.isDead && it.id != currentCombatant.id }
    }

    /** 拉条行动决策（processTurnAdvance 提取）：RNG 顺序保持 selectSkill → selectTarget */
    private fun decideAdvancedAction(
        advancedAlly: Combatant,
        advAllies: MutableList<Combatant>,
        advAliveEnemies: List<Combatant>
    ): Pair<CombatSkill?, Combatant> {
        val advSkill = BattleCalculator.selectSkill(
            advancedAlly, advAliveEnemies, advAllies.filter { !it.isDead }, false, rng
        )
        val advTarget = BattleCalculator.selectTarget(advancedAlly, advAliveEnemies, rng)
        return advSkill to advTarget
    }

    /** 拉条行动执行（processTurnAdvance 提取） */
    private fun executeAdvancedAction(
        advancedAlly: Combatant,
        advTarget: Combatant,
        advSkill: CombatSkill?,
        advDmgMod: Double
    ): AttackResult = if (advSkill != null) {
        executeSkill(advancedAlly, advTarget, advSkill, advDmgMod)
    } else {
        executeAttack(advancedAlly, advTarget, advDmgMod)
    }

    /** 拉条行动日志（processTurnAdvance 提取） */
    private fun buildAdvancedActionLog(
        advSkill: CombatSkill?,
        advResult: AttackResult,
        advancedAlly: Combatant,
        isTeamMember: Boolean,
        advDmg: Int
    ): BattleActionData = BattleActionData(
        type = if (advSkill != null) "skill" else "attack",
        attacker = advancedAlly.name,
        attackerType = if (isTeamMember) "disciple" else "beast",
        target = advResult.target.name,
        damage = advDmg,
        damageType = if (advResult.isPhysical) "物理" else "法术",
        isCrit = advResult.isCrit,
        isKill = advResult.target.hp - advDmg <= 0,
        message = "${advancedAlly.name}被拉条立即行动！",
        skillName = advResult.skillName
    )

    /** 拉条伤害结算（processTurnAdvance 提取）：护盾吸收经共享 BattleDamageApplier */
    private fun applyAdvancedDamage(
        ctx: TurnContext,
        advResult: AttackResult,
        advDmg: Int,
        isTeamMember: Boolean,
        enemiesIndexMap: Map<String, Int>
    ) {
        val advTargetIdx = enemiesIndexMap[advResult.target.id] ?: return
        val currentTarget = if (isTeamMember) ctx.beasts[advTargetIdx] else ctx.team[advTargetIdx]
        val updated = BattleDamageApplier.applyDamageToTarget(currentTarget, advDmg)
        if (isTeamMember && advTargetIdx < ctx.beasts.size) {
            ctx.beasts[advTargetIdx] = updated
        } else if (advTargetIdx < ctx.team.size) {
            ctx.team[advTargetIdx] = updated
        }
    }

    /** 拉条行动冷却更新（processTurnAdvance 提取） */
    private fun updateAdvancedCooldown(
        ctx: TurnContext,
        advSkill: CombatSkill,
        advancedAlly: Combatant,
        isTeamMember: Boolean,
        advIdx: Int
    ) {
        val updatedAdv = BattleCalculator.updateCombatantCooldowns(advancedAlly, advSkill)
        if (isTeamMember) ctx.team[advIdx] = updatedAdv else ctx.beasts[advIdx] = updatedAdv
    }

    /**
     * 控制效果处理：眩晕/冰冻时记录控制日志、结算 BUFF，回合提前结束。
     *
     * @return 被控制时返回 Continue（本回合结束）；否则 null 表示继续正常行动
     */
    private fun applyControlEffects(
        ctx: TurnContext,
        currentCombatant: Combatant,
        isTeamMember: Boolean,
        allies: MutableList<Combatant>,
        alliesIndexMap: Map<String, Int>
    ): TurnOutcome? {
        if (!currentCombatant.hasControlEffect) return null
        val stunBuff = currentCombatant.buffs.find { it.type == BuffType.STUN || it.type == BuffType.FREEZE }
            ?: return null
        ctx.actions.add(BattleActionData(
            type = "control",
            attacker = currentCombatant.name,
            attackerType = if (isTeamMember) "disciple" else if (currentCombatant.isBeast) "beast" else "disciple",
            target = currentCombatant.name,
            damage = 0,
            damageType = if (stunBuff.type == BuffType.STUN) "眩晕" else "冰冻",
            message = "${currentCombatant.name}因${stunBuff.type.displayName}无法行动！"
        ))
        updateCombatantBuffs(currentCombatant, allies, alliesIndexMap)
        return TurnOutcome.Continue
    }

    private fun updateCombatantBuffs(combatant: Combatant, list: MutableList<Combatant>, indexMap: Map<String, Int>) {
        val idx = indexMap[combatant.id] ?: return
        if (idx >= list.size) return
        val updated = BattleCalculator.updateCombatantBuffsOnly(combatant)
        list[idx] = updated
    }

    private fun processDotEffects(team: MutableList<Combatant>, beasts: MutableList<Combatant>, actions: MutableList<BattleActionData>) {
        val allCombatants = (team + beasts).filter { !it.isDead }
        val dotResults = BattleCalculator.processDotEffects(allCombatants)
        for (result in dotResults) {
            val isTeamMember = result.combatant.side == CombatantSide.DEFENDER
            if (isTeamMember) {
                val idx = team.indexOfFirst { it.id == result.combatant.id }
                if (idx >= 0) team[idx] = team[idx].copy(hp = result.newHp)
            } else {
                val idx = beasts.indexOfFirst { it.id == result.combatant.id }
                if (idx >= 0) beasts[idx] = beasts[idx].copy(hp = result.newHp)
            }
            actions.add(BattleActionData(
                type = "dot",
                attacker = "",
                attackerType = "",
                target = result.combatant.name,
                damage = result.damage,
                damageType = "持续伤害",
                isKill = result.newHp <= 0,
                message = "${result.combatant.name}受到${result.damage}点持续伤害"
            ))
        }
    }

    private fun executeAttack(attacker: Combatant, defender: Combatant, damageModifier: Double = 1.0): AttackResult {
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, defender, null, damageModifier = damageModifier, rng = rng, enableInstantKill = true
        )
        return AttackResult(
            attacker = attacker,
            target = defender,
            damage = result.damage,
            isCrit = result.isCrit,
            isPhysical = result.isPhysical,
            isDodged = result.isDodged,
            isInstantKill = result.isInstantKill
        )
    }

    private fun executeSkill(attacker: Combatant, defender: Combatant, skill: CombatSkill, damageModifier: Double = 1.0): AttackResult {
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill, damageModifier = damageModifier, rng = rng, enableInstantKill = true
        )
        return AttackResult(
            attacker = attacker,
            target = defender,
            damage = result.damage,
            isCrit = result.isCrit,
            isPhysical = result.isPhysical,
            isDodged = result.isDodged,
            skillName = result.skillName,
            hits = result.hits
        )
    }

    private fun executeSupportSkill(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill
    ): AttackResult {
        val supportResult = BattleCalculator.executeSupportSkill(caster, allies, skill)
        return AttackResult(
            attacker = caster,
            target = caster,
            damage = 0,
            isCrit = false,
            isPhysical = false,
            isDodged = false,
            skillName = skill.name,
            hits = 1,
            isSupport = true,
            message = "",
            healPercent = skill.healPercent,
            healType = skill.healType,
            healAmount = supportResult.healAmount,
            healedIds = supportResult.healedIds,
            newBuffs = emptyList(),
            teamBuffs = supportResult.teamBuffs,
            turnAdvancePercent = supportResult.turnAdvancePercent
        )
    }

    fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
        return BattleCalculator.calculateRealmGapMultiplier(attackerRealm, defenderRealm)
    }

    /** 技能决策结果：技能 + 配对的 AI 目标（局部传递，替代原类级 pendingAiAction） */
    private data class SkillDecision(
        val skill: CombatSkill?,
        val action: BattleAI.AIAction?
    )

    private fun selectSkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean,
        playerDamageModifier: Double
    ): SkillDecision {
        if (isSilenced) return SkillDecision(null, null)
        // T-C1（2026-08-05）：玩家侧 AI 决策透传伤害倍率（严苛训练 +5% 估算一致）
        val dmgMod = if (combatant.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
        val action = BattleAI.decideAction(combatant, allies, enemies, rng, dmgMod)
        return SkillDecision(action.skill, action)
    }

    private fun selectTarget(attacker: Combatant, targets: List<Combatant>, aiAction: BattleAI.AIAction?): Combatant {
        if (aiAction?.target != null) return aiAction.target
        return BattleAI.selectAttackTarget(attacker, targets, null, rng)
            ?: targets.first()
    }

    private fun generateRewards(beastCount: Int): Map<String, Int> {
        val rewards = mutableMapOf<String, Int>()
        rewards["spiritStones"] = 100 * beastCount
        return rewards
    }
}

