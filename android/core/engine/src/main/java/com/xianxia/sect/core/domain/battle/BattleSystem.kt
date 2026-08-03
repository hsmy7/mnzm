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

    /** 临时伤害倍率（由外部在 executeBattle 前设置，如严苛训练+5%等政策加成） */
    @Volatile
    var playerDamageModifier: Double = 1.0

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

    fun executeBattle(battle: Battle): BattleSystemResult {
        return executeBattleWithTimeout(battle, GameConfig.Battle.MAX_BATTLE_DURATION_MS)
    }

    fun executeBattleWithTimeout(battle: Battle, timeoutMs: Long = GameConfig.Battle.MAX_BATTLE_DURATION_MS): BattleSystemResult {
        val startTime = System.currentTimeMillis()
        var currentBattle = battle
        val rounds = mutableListOf<BattleRoundData>()
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

            val turnResult = executeTurnWithLog(currentBattle)
            currentBattle = turnResult.first
            if (turnResult.second.actions.isNotEmpty()) {
                rounds.add(turnResult.second)
            }

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

        val aliveTeam = currentBattle.team.count { !it.isDead }
        val aliveBeasts = currentBattle.beasts.count { !it.isDead }

        val winner = when {
            timedOut -> {
                DomainLog.w("BattleSystem", "Battle timed out after ${System.currentTimeMillis() - startTime}ms")
                if (aliveTeam > aliveBeasts) BattleWinner.TEAM else if (aliveBeasts > aliveTeam) BattleWinner.BEASTS else BattleWinner.DRAW
            }
            aliveTeam == 0 -> BattleWinner.BEASTS
            aliveBeasts == 0 -> BattleWinner.TEAM
            else -> BattleWinner.DRAW
        }

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

    private fun executeTurnWithLog(battle: Battle): Pair<Battle, BattleRoundData> {
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
            val outcome = executeCombatantTurn(ctx, combatant)
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
    private fun executeCombatantTurn(ctx: TurnContext, combatant: Combatant): TurnOutcome {

            if (combatant.isDead) return TurnOutcome.Continue

            val isTeamMember = combatant.side == CombatantSide.DEFENDER
            val allies = if (isTeamMember) ctx.team else ctx.beasts
            val enemies = if (isTeamMember) ctx.beasts else ctx.team
            val alliesIndexMap = if (isTeamMember) ctx.teamIndexMap else ctx.beastsIndexMap
            val enemiesIndexMap = if (isTeamMember) ctx.beastsIndexMap else ctx.teamIndexMap

            val aliveEnemies = enemies.filter { !it.isDead }
            if (aliveEnemies.isEmpty()) {
                return TurnOutcome.EndBattle
            }

            val currentCombatant = allies.find { it.id == combatant.id } ?: combatant

            if (currentCombatant.hasControlEffect) {
                val stunBuff = currentCombatant.buffs.find { it.type == BuffType.STUN || it.type == BuffType.FREEZE }
                if (stunBuff != null) {
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
            }

            val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
            val availableSkill = selectSkill(currentCombatant, aliveEnemies, allies, silenceBuff != null)

            val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
            val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

            val results = if (availableSkill != null) {
                if (isSupportSkill) {
                    // Handle "ally" scope: select a random alive ally (not caster)
                    if (availableSkill.targetScope == "ally") {
                        val validAllies = allies.filter { !it.isDead && it.id != currentCombatant.id }
                        if (validAllies.isNotEmpty()) {
                            val selectedAlly = validAllies.random()
                            val supResult = executeSupportSkill(currentCombatant, listOf(selectedAlly), availableSkill)
                            // Mark the single ally as the target for turn advance
                            listOf(supResult.copy(
                                healedIds = if (supResult.healAmount > 0) listOf(selectedAlly.id) else supResult.healedIds,
                                teamBuffs = if (supResult.teamBuffs.isNotEmpty()) mapOf(selectedAlly.id to (supResult.teamBuffs.values.firstOrNull() ?: emptyList())) else emptyMap()
                            ))
                        } else {
                            listOf(executeSupportSkill(currentCombatant, listOf(currentCombatant), availableSkill))
                        }
                    } else {
                        listOf(executeSupportSkill(currentCombatant, allies.filter { !it.isDead }, availableSkill))
                    }
                } else if (isAoeSkill) {
                    val dmgMod = if (isTeamMember) playerDamageModifier else 1.0
                    aliveEnemies.map { target -> executeSkill(currentCombatant, target, availableSkill, dmgMod) }
                } else {
                    val target = selectTarget(currentCombatant, aliveEnemies)
                    val dmgMod = if (isTeamMember) playerDamageModifier else 1.0
                    listOf(executeSkill(currentCombatant, target, availableSkill, dmgMod))
                }
            } else {
                val target = selectTarget(currentCombatant, aliveEnemies)
                val dmgMod = if (isTeamMember) playerDamageModifier else 1.0
                listOf(executeAttack(currentCombatant, target, dmgMod))
            }

            val result = results.first()
            var isKill = false
            var isInstantKill = results.any { it.isInstantKill }
            var totalDamage = 0

            val message: String = if (isInstantKill) {
                isKill = true
                "境界碾压，${result.target.name}被一击必杀！"
            } else if (result.isSupport && availableSkill != null) {
                val buffs = availableSkill.buffs
                BattleDescriptionGenerator.generateSupportSkillDescription(
                    caster = currentCombatant,
                    skill = availableSkill,
                    healAmount = result.healAmount,
                    healType = result.healType,
                    buffs = buffs
                )
            } else if (availableSkill != null) {
                totalDamage = results.sumOf { it.damage }
                isKill = results.any { r -> r.target.hp - r.damage <= 0 }
                if (isAoeSkill) {
                    BattleDescriptionGenerator.generateAoeSkillDescription(
                        attacker = currentCombatant,
                        skill = availableSkill,
                        results = results,
                        isKill = isKill
                    )
                } else {
                    val singleTarget = result.target
                    isKill = singleTarget.hp - result.damage <= 0
                    BattleDescriptionGenerator.generateSkillDescription(
                        attacker = currentCombatant,
                        target = singleTarget,
                        skill = availableSkill,
                        result = result,
                        isKill = isKill
                    )
                }
            } else {
                isKill = result.target.hp - result.damage <= 0
                BattleDescriptionGenerator.generateAttackDescription(
                    attacker = currentCombatant,
                    target = result.target,
                    result = result,
                    isKill = isKill
                )
            }

            ctx.actions.add(BattleActionData(
                type = when {
                    isSupportSkill -> "support"
                    availableSkill != null -> "skill"
                    else -> "attack"
                },
                attacker = currentCombatant.name,
                attackerType = if (isTeamMember) "disciple" else if (currentCombatant.isBeast) "beast" else "disciple",
                target = if (isSupportSkill) "ctx.team" else if (isAoeSkill) "全体敌人" else result.target.name,
                damage = if (isAoeSkill) totalDamage else result.damage,
                damageType = if (isInstantKill) "必杀" else if (result.isSupport) "support" else if (result.isDodged) "闪避" else if (result.isPhysical) "物理" else "法术",
                isCrit = results.any { it.isCrit },
                isKill = isKill,
                isInstantKill = isInstantKill,
                message = message,
                skillName = result.skillName
            ))

            if (!result.isSupport) {
                results.forEach { r ->
                    if (r.isDodged) return@forEach
                    val targetIndex = enemiesIndexMap[r.target.id] ?: return@forEach

                    // Shield absorption
                    val shieldResult = BattleCalculator.calculateShieldAbsorption(r.target, r.damage)
                    val damageAfterShield = shieldResult.remainingDamage
                    val newHp = maxOf(0, r.target.hp - damageAfterShield)
                    if (isTeamMember) {
                        ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(hp = newHp)
                        // Update shield buff if partially consumed
                        if (shieldResult.shieldBuff != null) {
                            val shieldBuffs = ctx.beasts[targetIndex].buffs.map { b ->
                                if (b.type == BuffType.SHIELD && b.remainingDuration == shieldResult.shieldBuff.remainingDuration)
                                    b.copy(value = shieldResult.remainingShield.toDouble() / ctx.beasts[targetIndex].maxHp.coerceAtLeast(1))
                                else b
                            }
                            ctx.beasts[targetIndex] = ctx.beasts[targetIndex].copy(buffs = shieldBuffs)
                        }
                    } else {
                        ctx.team[targetIndex] = ctx.team[targetIndex].copy(hp = newHp)
                        if (shieldResult.shieldBuff != null) {
                            val shieldBuffs = ctx.team[targetIndex].buffs.map { b ->
                                if (b.type == BuffType.SHIELD && b.remainingDuration == shieldResult.shieldBuff.remainingDuration)
                                    b.copy(value = shieldResult.remainingShield.toDouble() / ctx.team[targetIndex].maxHp.coerceAtLeast(1))
                                else b
                            }
                            ctx.team[targetIndex] = ctx.team[targetIndex].copy(buffs = shieldBuffs)
                        }
                    }

                    // Damage link: apply linked damage to the linked enemy
                    val linkedDmg = BattleCalculator.calculateLinkedDamage(
                        currentCombatant, r.target, r.damage, ctx.beasts, ctx.team
                    )
                    linkedDmg.forEach { (linkedId, linkDmg) ->
                        val linkedInBeasts = ctx.beasts.indexOfFirst { it.id == linkedId }
                        val linkedInTeam = ctx.team.indexOfFirst { it.id == linkedId }
                        if (linkedInBeasts >= 0) {
                            ctx.beasts[linkedInBeasts] = ctx.beasts[linkedInBeasts].copy(
                                hp = maxOf(0, ctx.beasts[linkedInBeasts].hp - linkDmg)
                            )
                        } else if (linkedInTeam >= 0) {
                            ctx.team[linkedInTeam] = ctx.team[linkedInTeam].copy(
                                hp = maxOf(0, ctx.team[linkedInTeam].hp - linkDmg)
                            )
                        }
                    }

                    // Damage share: redistribute damage to sharers
                    val shareDmg = BattleCalculator.calculateDamageShare(
                        r.target.id, r.target.side, r.damage, ctx.team, ctx.beasts
                    )
                    shareDmg.forEach { (sharerId, shareDamage) ->
                        val sharerInTeam = ctx.team.indexOfFirst { it.id == sharerId }
                        val sharerInBeasts = ctx.beasts.indexOfFirst { it.id == sharerId }
                        val shareAfterShield = BattleCalculator.calculateShieldAbsorption(
                            if (sharerInTeam >= 0) ctx.team[sharerInTeam] else ctx.beasts[sharerInBeasts],
                            shareDamage
                        )
                        if (sharerInTeam >= 0) {
                            ctx.team[sharerInTeam] = ctx.team[sharerInTeam].copy(
                                hp = maxOf(0, ctx.team[sharerInTeam].hp - shareAfterShield.remainingDamage)
                            )
                        } else if (sharerInBeasts >= 0) {
                            ctx.beasts[sharerInBeasts] = ctx.beasts[sharerInBeasts].copy(
                                hp = maxOf(0, ctx.beasts[sharerInBeasts].hp - shareAfterShield.remainingDamage)
                            )
                        }
                    }

                    val localBuffType = availableSkill?.buffType
                    if (localBuffType != null && availableSkill.buffDuration > 0 && !isAoeSkill) {
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

                    // Damage link buff application (debuff on enemy)
                    if (availableSkill?.damageLinkPercent != null && availableSkill.damageLinkPercent > 0 && availableSkill.buffDuration > 0) {
                        val linkDebuff = CombatBuff(
                            type = BuffType.DAMAGE_LINK,
                            value = availableSkill.damageLinkPercent,
                            remainingDuration = availableSkill.buffDuration,
                            sourceRealm = currentCombatant.realm
                        )
                        // Remove existing link from any other enemy (only one link at a time)
                        val enemies = if (isTeamMember) ctx.beasts else ctx.team
                        enemies.forEachIndexed { idx, enemy ->
                            val hasLink = enemy.buffs.any { it.type == BuffType.DAMAGE_LINK }
                            if (hasLink) {
                                val cleaned = enemy.buffs.filter { it.type != BuffType.DAMAGE_LINK }
                                if (isTeamMember && idx < ctx.beasts.size) ctx.beasts[idx] = ctx.beasts[idx].copy(buffs = cleaned)
                                else if (idx < ctx.team.size) ctx.team[idx] = ctx.team[idx].copy(buffs = cleaned)
                            }
                        }
                        // Apply new link to target
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
                }

                if (isAoeSkill && availableSkill?.buffType != null && availableSkill.buffDuration > 0) {
                    val aoeBuffType = availableSkill.buffType
                    if (aoeBuffType != null) {
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
                }

                // AoE shield absorption for all targets
                if (isAoeSkill) {
                    results.forEach { r ->
                        if (r.isDodged) return@forEach
                        val tIdx = enemiesIndexMap[r.target.id] ?: return@forEach
                        val sr = BattleCalculator.calculateShieldAbsorption(r.target, r.damage)
                        if (sr.shieldBuff != null) {
                            val updatedHp = maxOf(0, r.target.hp - sr.remainingDamage)
                            if (isTeamMember && tIdx < ctx.beasts.size) {
                                ctx.beasts[tIdx] = ctx.beasts[tIdx].copy(hp = updatedHp)
                            } else if (tIdx < ctx.team.size) {
                                ctx.team[tIdx] = ctx.team[tIdx].copy(hp = updatedHp)
                            }
                        }
                    }
                }

            }

            if (availableSkill != null) {
                val combatantIndex = alliesIndexMap[currentCombatant.id] ?: -1
                if (combatantIndex >= 0) {
                    val updatedCombatant = BattleCalculator.updateCombatantCooldowns(currentCombatant, availableSkill)

                    if (isTeamMember) {
                        ctx.team[combatantIndex] = updatedCombatant
                    } else {
                        ctx.beasts[combatantIndex] = updatedCombatant
                    }
                }

                if (isSupportSkill) {
                    if (result.healedIds.isNotEmpty()) {
                        result.healedIds.forEach { healedId ->
                            val healedIndex = ctx.team.indexOfFirst { it.id == healedId }
                            if (healedIndex >= 0) {
                                val healed = ctx.team[healedIndex]
                                if (result.healType == HealType.MP) {
                                    ctx.team[healedIndex] = healed.copy(
                                        mp = minOf(healed.mp + result.healAmount, healed.maxMp)
                                    )
                                } else {
                                    ctx.team[healedIndex] = healed.copy(
                                        hp = minOf(healed.hp + result.healAmount, healed.maxHp)
                                    )
                                }
                            }
                        }
                    }

                    if (result.teamBuffs.isNotEmpty()) {
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

                    // Turn advance: target ally acts immediately after current combatant
                    if (result.turnAdvancePercent > 0) {
                        val advancedId = result.healedIds.firstOrNull()
                            ?: result.teamBuffs.keys.firstOrNull()
                        if (advancedId != null) {
                            val advancedAlly = allies.find { it.id == advancedId && !it.isDead }
                            if (advancedAlly != null && advancedAlly.id != currentCombatant.id) {
                                // Process the advanced ally's turn immediately
                                val advAllies = if (isTeamMember) ctx.team else ctx.beasts
                                val advEnemies = if (isTeamMember) ctx.beasts else ctx.team
                                val advAliveEnemies = advEnemies.filter { !it.isDead }
                                val advIdx = advAllies.indexOfFirst { it.id == advancedId }
                                if (advIdx >= 0 && advAliveEnemies.isNotEmpty()) {
                                    val advSkill = BattleCalculator.selectSkill(advancedAlly, advAliveEnemies, advAllies.filter { !it.isDead }, false, rng)
                                    val advResult = if (advSkill != null) {
                                        val advTarget = BattleCalculator.selectTarget(advancedAlly, advAliveEnemies, rng)
                                        val advDmgMod = if (advancedAlly.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
                                        executeSkill(advancedAlly, advTarget, advSkill, advDmgMod)
                                    } else {
                                        val advTarget = BattleCalculator.selectTarget(advancedAlly, advAliveEnemies, rng)
                                        val advDmgMod = if (advancedAlly.side == CombatantSide.DEFENDER) playerDamageModifier else 1.0
                                        executeAttack(advancedAlly, advTarget, advDmgMod)
                                    }
                                    val advDmg = if (advResult.isSupport) 0 else advResult.damage
                                    ctx.actions.add(BattleActionData(
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
                                    ))
                                    if (!advResult.isSupport && !advResult.isDodged) {
                                        val advTargetIdx = enemiesIndexMap[advResult.target.id]
                                        if (advTargetIdx != null) {
                                            val shieldR = BattleCalculator.calculateShieldAbsorption(advResult.target, advDmg)
                                            if (isTeamMember && advTargetIdx < ctx.beasts.size) {
                                                ctx.beasts[advTargetIdx] = ctx.beasts[advTargetIdx].copy(hp = maxOf(0, ctx.beasts[advTargetIdx].hp - shieldR.remainingDamage))
                                            } else if (advTargetIdx < ctx.team.size) {
                                                ctx.team[advTargetIdx] = ctx.team[advTargetIdx].copy(hp = maxOf(0, ctx.team[advTargetIdx].hp - shieldR.remainingDamage))
                                            }
                                        }
                                    }
                                    // Update cooldowns for advanced ally
                                    if (advSkill != null && advIdx >= 0) {
                                        val updatedAdv = BattleCalculator.updateCombatantCooldowns(advancedAlly, advSkill)
                                        if (isTeamMember) ctx.team[advIdx] = updatedAdv else ctx.beasts[advIdx] = updatedAdv
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                updateCombatantBuffs(currentCombatant, allies, alliesIndexMap)
            }
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

    // 临时保存 BattleAI 决策结果，供 selectSkill → selectTarget 配对使用
    private var pendingAiAction: BattleAI.AIAction? = null

    private fun selectSkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        if (isSilenced) {
            pendingAiAction = null
            return null
        }
        val action = BattleAI.decideAction(combatant, allies, enemies, rng)
        pendingAiAction = action
        return action.skill
    }

    private fun selectTarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val action = pendingAiAction
        if (action != null && action.target != null) {
            pendingAiAction = null
            return action.target
        }
        pendingAiAction = null
        return BattleAI.selectAttackTarget(attacker, targets, null, rng)
            ?: targets.first()
    }

    private fun generateRewards(beastCount: Int): Map<String, Int> {
        val rewards = mutableMapOf<String, Int>()
        rewards["spiritStones"] = 100 * beastCount
        return rewards
    }
}

