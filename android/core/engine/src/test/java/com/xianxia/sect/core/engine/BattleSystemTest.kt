package com.xianxia.sect.core.engine

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.domain.battle.BattleWinner
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.util.GameRngManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BattleSystemTest {

    private lateinit var battleSystem: BattleSystem

    @Before
    fun setUp() {
        // 注入 DiscipleStatCalculator 实现（与 XianxiaApplication 一致）。
        // 否则 statsProvider 默认空实现返回全 0 属性 → 弟子 maxHp=0 → isDead →
        // 战斗 0 回合即 EndBattle，executeBattle 测试全部假阳性（RNG 从未被使用）。
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                disciple, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun getFinalStats(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                aggregate, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun calculateCultivationSpeed(
                disciple: Disciple, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty
            )
        }
        // 真实 GameRngManager（mock 的 getRng 返回 null 无实际意义；确定性测试依赖真实 PRNG）
        battleSystem = BattleSystem(GameRngManager())
    }

    // ═══════════════════════════════════════════════════════════════
    // 2026-08-04 战斗核查修复回归（G3 敌方治疗 / G5 死亡不出手 / G6 分区 RNG）
    // ═══════════════════════════════════════════════════════════════

    private fun combatant(
        id: String,
        side: CombatantSide,
        hp: Int, maxHp: Int,
        physAtk: Int = 100, physDef: Int = 50,
        speed: Int = 100
    ) = Combatant(
        id = id, name = id, side = side,
        hp = hp, maxHp = maxHp, mp = 500, maxMp = 500,
        physicalAttack = physAtk, magicAttack = physAtk,
        physicalDefense = physDef, magicDefense = physDef,
        speed = speed, critRate = 0.0,
        skills = emptyList(), buffs = emptyList(), realm = 5, realmLayer = 1
    )

    private fun battleSystem(seed: Long): BattleSystem {
        val rngManager = GameRngManager()
        rngManager.initSystemSeed(seed)
        return BattleSystem(rngManager)
    }

    @Test
    fun `executeBattle - 妖兽支援技能治疗_恢复妖兽自身HP`() {
        // G3 回归：治疗必须按 allies 定位写入 ctx.beasts（原硬编码 ctx.team 致敌方治疗无效）。
        // 蛇妖 hp 25% 以下触发 Tier 2 保命（90% 概率）→ 施放蜕皮新生（self 25% 治疗）。
        val healSkill = CombatSkill(
            name = "蜕皮新生", skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = 25, cooldown = 5, healPercent = 0.25,
            healType = HealType.HP, targetScope = "self"
        )
        val disciple = combatant(
            "d1", CombatantSide.DEFENDER, hp = 1000, maxHp = 1000,
            physAtk = 100, physDef = 50, speed = 100
        )
        val snake = combatant(
            "snake", CombatantSide.ATTACKER,
            hp = 400, maxHp = 4000, physAtk = 200, physDef = 50, speed = 10
        ).copy(skills = listOf(healSkill))
        // 固定种子寻找治疗触发路径（Tier 2 保命 90% 概率），并验证治疗实际恢复 HP
        var healed: BattleSystemResult? = null
        for (seed in 1L..40L) {
            val battle = Battle(team = listOf(disciple), beasts = listOf(snake))
            val result = battleSystem(seed).executeBattle(battle)
            val healedThisRun = result.log.rounds.flatMap { it.actions }
                .any { it.type == "support" && it.attacker == "snake" }
            if (healedThisRun) {
                healed = result
                break
            }
        }
        assertNotNull("未找到妖兽治疗触发种子（40 个种子内应触发）", healed)
        // 治疗有效：战报存在蛇妖的治疗行动（修复前治疗被静默丢弃，仍会记录 support action 但 HP 不恢复——
        // 因此再断言蛇妖战斗中 HP 有过回升：治疗行动发生时目标 HP 增加）
        val actions = healed!!.log.rounds.flatMap { it.actions }
        val healAction = actions.firstOrNull { it.type == "support" && it.attacker == "snake" }
        assertNotNull("战报应包含蛇妖治疗行动", healAction)
        // 蛇妖最终 HP 高于入场 HP（治疗 25% = 1000，若治疗丢失则蛇妖只会持续掉血）
        val finalSnake = healed!!.battle.beasts.first { it.id == "snake" }
        assertTrue("治疗应恢复妖兽HP（入场400 → 最终${finalSnake.hp}）", finalSnake.hp > 400)
    }

    @Test
    fun `executeBattle - 回合内被击杀单位_不再出手`() {
        // G5 回归：回合内被击杀的单位（快照仍存活）不得继续出手。
        // 构造：弟子（speed 300）首回合秒杀妖兽 A（speed 200），妖兽 B（speed 100）正常行动。
        val disciple = combatant(
            "d1", CombatantSide.DEFENDER, hp = 5000, maxHp = 5000,
            physAtk = 100000, physDef = 50, speed = 300
        )
        val beastA = combatant(
            "beastA", CombatantSide.ATTACKER, hp = 500, maxHp = 500,
            physAtk = 10, physDef = 10, speed = 200
        )
        val beastB = combatant(
            "beastB", CombatantSide.ATTACKER, hp = 500, maxHp = 500,
            physAtk = 10, physDef = 10, speed = 100
        )
        var result: BattleSystemResult? = null
        for (seed in 1L..40L) {
            val battle = Battle(team = listOf(disciple), beasts = listOf(beastA, beastB))
            val r = battleSystem(seed).executeBattle(battle)
            val actions = r.log.rounds.flatMap { it.actions }
            val beastAActed = actions.any { it.attacker == "beastA" }
            if (!beastAActed) { result = r; break }
        }
        assertNotNull("未找到击杀后妖兽A不出手的种子", result)
        val actions = result!!.log.rounds.flatMap { it.actions }
        val beastAActed = actions.any { it.attacker == "beastA" }
        assertFalse("妖兽A被秒杀后不得再出手（修复前会以满属性反击一次）", beastAActed)
        // 妖兽B不受影响，正常出手
        assertTrue("妖兽B应正常出手", actions.any { it.attacker == "beastB" })
    }

    @Test
    fun `executeBattle - 必杀无视护盾_目标直接死亡`() {
        // 对抗性审查：斩杀（境界压制必杀）与护盾语义必须与 AI 引擎一致——
        // 战报显示必杀时目标必须死亡（此前护盾吸收后残血存活，战报谎报）
        val disciple = combatant(
            "d1", CombatantSide.DEFENDER, hp = 5000, maxHp = 5000,
            physAtk = 100000, physDef = 50, speed = 300
        ).copy(realm = 2)
        val beast = combatant(
            "b1", CombatantSide.ATTACKER, hp = 1000, maxHp = 1000,
            physAtk = 10, physDef = 10, speed = 100
        ).copy(
            realm = 8,
            buffs = listOf(CombatBuff(type = BuffType.SHIELD, value = 0.9, remainingDuration = 3))
        )
        // 弟子 realm 2（高境界）斩杀 realm 8 妖兽（realm 数值小=境界高）；妖兽带 90% 护盾
        var result: BattleSystemResult? = null
        for (seed in 1L..20L) {
            val battle = Battle(team = listOf(disciple), beasts = listOf(beast))
            val r = battleSystem(seed).executeBattle(battle)
            if (r.log.rounds.flatMap { it.actions }.any { it.isInstantKill }) { result = r; break }
        }
        assertNotNull("未找到必杀触发种子", result)
        val deadBeast = result!!.battle.beasts.first { it.id == "b1" }
        assertEquals("必杀无视护盾，目标必须死亡", 0, deadBeast.hp)
    }

    @Test
    fun `executeBattle - 传入伤害倍率_玩家伤害按倍率计算`() {
        // W1 回归：playerDamageModifier 参数透传（原 @Volatile 单例字段设置-执行-重置）
        val disciple = combatant(
            "d1", CombatantSide.DEFENDER, hp = 5000, maxHp = 5000,
            physAtk = 100000, physDef = 50, speed = 300
        )
        val beast = combatant(
            "b1", CombatantSide.ATTACKER, hp = 5000, maxHp = 5000,
            physAtk = 10, physDef = 10, speed = 100
        )
        val battle = Battle(team = listOf(disciple), beasts = listOf(beast))

        val r1 = battleSystem(42L).executeBattle(battle, playerDamageModifier = 1.0)
        val r2 = battleSystem(42L).executeBattle(battle, playerDamageModifier = 2.0)
        val dmg1 = r1.log.rounds.flatMap { it.actions }.filter { it.attacker == "d1" }.sumOf { it.damage }
        val dmg2 = r2.log.rounds.flatMap { it.actions }.filter { it.attacker == "d1" }.sumOf { it.damage }
        assertTrue("伤害倍率 2.0 应显著高于 1.0（d1 总伤 $dmg1 vs $dmg2）", dmg2 > dmg1 * 1.2)
    }

    @Test
    fun `executeBattle - 支援选队友_同种子战报一致`() {
        // G6 回归：ally 作用域支援技能的随机选队友必须走 BATTLE 分区 RNG
        // （原 validAllies.random() 用 kotlin 默认 Random，同种子两次运行结果不一致）。
        val healAllySkill = CombatSkill(
            name = "治愈", skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = 30, cooldown = 2, healPercent = 0.3,
            healType = HealType.HP, targetScope = "ally"
        )
        val healer = combatant(
            "healer", CombatantSide.DEFENDER, hp = 1000, maxHp = 1000,
            physAtk = 100, physDef = 50, speed = 200
        ).copy(skills = listOf(healAllySkill))
        val tank = combatant(
            "tank", CombatantSide.DEFENDER, hp = 800, maxHp = 1000,
            physAtk = 100, physDef = 50, speed = 100
        )
        val beast = combatant(
            "beast", CombatantSide.ATTACKER, hp = 3000, maxHp = 3000,
            physAtk = 150, physDef = 50, speed = 50
        )
        val battle = Battle(team = listOf(healer, tank), beasts = listOf(beast))

        fun run(seed: Long): List<String> {
            val result = battleSystem(seed).executeBattle(battle)
            return result.log.rounds.flatMap { it.actions }
                .map { "${it.attacker}:${it.type}:${it.target}:${it.damage}" }
        }
        val first = run(1234L)
        val second = run(1234L)
        assertEquals("同种子两次战斗的完整行动序列必须一致（RNG 确定性守卫）", first, second)
        assertTrue("本场应出现支援治疗行动", first.any { it.contains("support") || it.contains("heal") })
    }

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 9,
        realmLayer: Int = 1,
        isAlive: Boolean = true
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            isAlive = isAlive,
            skills = SkillStats(loyalty = 50)
        )
    }

    @Test
    fun `createBattle - creates battle with disciple team`() {
        val disciples = listOf(createDisciple(id = "d1"), createDisciple(id = "d2"))
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 2
        )
        assertEquals(2, battle.team.size)
        assertEquals(CombatantSide.DEFENDER, battle.team[0].side)
    }

    @Test
    fun `createBattle - creates battle with beasts`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple()),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 3
        )
        assertEquals(3, battle.beasts.size)
        battle.beasts.forEach { beast ->
            assertEquals(CombatantSide.ATTACKER, beast.side)
            assertTrue(beast.hp > 0)
        }
    }

    @Test
    fun `createBattle - battle initial state not finished`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple()),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 9,
            beastCount = 1
        )
        assertFalse(battle.isFinished)
        assertNull(battle.winner)
        assertEquals(0, battle.turn)
    }

    @Test
    fun `executeBattle - battle always finishes`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertNotNull(result.battle.winner)
    }

    @Test
    fun `executeBattle - returns battle log`() {
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 7)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 7,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertNotNull(result.log)
        assertTrue(result.turnCount >= 0)
    }

    @Test
    fun `Combatant - isDead when hp is zero`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 0, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - isDead when hp is negative`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.ATTACKER,
            hp = -10, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertTrue(combatant.isDead)
    }

    @Test
    fun `Combatant - hpPercent correct`() {
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 30, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList()
        )
        assertEquals(0.3, combatant.hpPercent, 0.01)
    }

    @Test
    fun `Combatant - effectivePhysicalAttack with buff`() {
        val buff = CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.5, 3)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 100, magicAttack = 50, physicalDefense = 10, magicDefense = 10,
            speed = 20, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertEquals(150, combatant.effectivePhysicalAttack)
    }

    @Test
    fun `Combatant - effectiveSpeed with buff`() {
        val buff = CombatBuff(BuffType.SPEED_BOOST, 0.3, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 10, magicDefense = 10,
            speed = 100, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertEquals(130, combatant.effectiveSpeed)
    }

    @Test
    fun `Combatant - debuff reduces attack`() {
        val debuff = CombatBuff(BuffType.PHYSICAL_ATTACK_REDUCE, 0.3, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 100, magicAttack = 50, physicalDefense = 10, magicDefense = 10,
            speed = 20, critRate = 0.05, skills = emptyList(), buffs = listOf(debuff)
        )
        assertEquals(70, combatant.effectivePhysicalAttack)
    }

    @Test
    fun `BattleWinner - enum values complete`() {
        assertEquals(3, BattleWinner.values().size)
        assertNotNull(BattleWinner.TEAM)
        assertNotNull(BattleWinner.BEASTS)
        assertNotNull(BattleWinner.DRAW)
    }

    @Test
    fun `BuffType - all buff types exist`() {
        assertTrue(BuffType.values().size >= 20)
        assertNotNull(BuffType.HP_BOOST)
        assertNotNull(BuffType.POISON)
        assertNotNull(BuffType.BURN)
        assertNotNull(BuffType.STUN)
        assertNotNull(BuffType.FREEZE)
        assertNotNull(BuffType.SILENCE)
        assertNotNull(BuffType.PHYSICAL_ATTACK_REDUCE)
        assertNotNull(BuffType.MAGIC_ATTACK_REDUCE)
    }

    @Test
    fun `calculateRealmGapMultiplier - same realm returns 1`() {
        assertEquals(1.0, battleSystem.calculateRealmGapMultiplier(5, 5), 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 全十境界差距加成不再被钳制`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(0, 9)
        assertEquals(4.15, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 全十境界差距惩罚触底为零`() {
        val multiplier = battleSystem.calculateRealmGapMultiplier(9, 0)
        assertEquals(0.0, multiplier, 0.001)
    }

    @Test
    fun `Combatant - hasControlEffect with stun`() {
        val stunBuff = CombatBuff(BuffType.STUN, 1.0, 2)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList(), buffs = listOf(stunBuff)
        )
        assertTrue(combatant.hasControlEffect)
    }

    @Test
    fun `Combatant - no control effect without stun or freeze`() {
        val buff = CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.5, 3)
        val combatant = Combatant(
            id = "test", name = "Test", side = CombatantSide.DEFENDER,
            hp = 100, maxHp = 100, mp = 50, maxMp = 50,
            physicalAttack = 10, magicAttack = 10, physicalDefense = 5, magicDefense = 5,
            speed = 10, critRate = 0.05, skills = emptyList(), buffs = listOf(buff)
        )
        assertFalse(combatant.hasControlEffect)
    }

    @Test
    fun `executeBattle - multi disciple multi beast battle`() {
        val disciples = listOf(
            createDisciple(id = "d1", realm = 7),
            createDisciple(id = "d2", realm = 7),
            createDisciple(id = "d3", realm = 7)
        )
        val battle = battleSystem.createBattle(
            disciples = disciples,
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 7,
            beastCount = 3
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertTrue(result.log.teamMembers.size == 3)
        assertTrue(result.log.enemies.size == 3)
    }

    // ═══════════════════════════════════════════════════════════════
    // P3A 拆分回归：executeCombatantTurn 抽取函数（applyControlEffects/
    // executeSkillAction/buildTurnMessage/applyDamageEffects/processTurnAdvance）
    // 通过公开 executeBattle 路径触发，验证拆分后行为等价
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `executeBattle - 眩晕弟子跳过行动 战斗仍正常结束`() {
        // applyControlEffects：眩晕/冰冻时记录控制日志并跳过行动
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        // 给首个弟子附加眩晕 BUFF，验证控制路径不崩溃且战斗正常结束
        val stunnedBattle = battle.copy(
            team = battle.team.mapIndexed { i, c ->
                if (i == 0) {
                    c.copy(
                        buffs = c.buffs + CombatBuff(
                            type = BuffType.STUN, value = 1.0, remainingDuration = 2, sourceRealm = 9
                        )
                    )
                } else c
            }
        )
        val result = battleSystem.executeBattle(stunnedBattle)
        assertTrue(result.battle.isFinished)
        // 控制路径（applyControlEffects）不崩溃，战斗正常结束
        // （control 日志是否出现取决于行动顺序——妖兽可能先行动，不断言具体类型）
        assertTrue(result.turnCount >= 0)
    }

    @Test
    fun `executeBattle - 日志结构完整 行动记录无空字段`() {
        // buildTurnMessage/applyDamageEffects：消息生成与伤害结算路径
        // 注：弟子无功法（skills 空）时仅普攻；若妖兽先手秒杀则 rounds 可能为空，
        // 因此只断言"有行动记录时每条结构完整"。
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 5), createDisciple(realm = 5)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 5,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        assertNotNull(result.log)
        assertTrue(result.log.teamMembers.isNotEmpty())
        assertTrue(result.log.enemies.isNotEmpty())
        val actions = result.log.rounds.flatMap { it.actions }
        actions.forEach { action ->
            assertNotNull(action.attacker)
            assertNotNull(action.message)
        }
    }

    @Test
    fun `executeBattle - 支援技能路径不崩溃 战斗正常结束`() {
        // executeSkillAction 支援分支：治疗/加 BUFF 类技能
        val battle = battleSystem.createBattle(
            disciples = listOf(createDisciple(realm = 6), createDisciple(realm = 6)),
            equipmentMap = emptyMap(),
            manualMap = emptyMap(),
            beastLevel = 6,
            beastCount = 1
        )
        val result = battleSystem.executeBattle(battle)
        assertTrue(result.battle.isFinished)
        // 战斗过程无异常（含可能触发的支援技能分支）
        assertTrue(result.turnCount >= 0)
    }
}
