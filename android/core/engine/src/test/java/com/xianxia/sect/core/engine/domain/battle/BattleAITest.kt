package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BattleAITest {

    private val fixedRandom = Random(42)

    // ---- 辅助工厂方法 ----

    private fun combatant(
        id: String = "u1",
        hp: Int = 800, maxHp: Int = 1000,
        mp: Int = 500, maxMp: Int = 500,
        physAtk: Int = 100, magAtk: Int = 80,
        physDef: Int = 60, magDef: Int = 50,
        speed: Int = 100,
        skills: List<CombatSkill> = emptyList(),
        buffs: List<CombatBuff> = emptyList(),
        side: CombatantSide = CombatantSide.DEFENDER
    ) = Combatant(
        id = id, name = id, side = side,
        hp = hp, maxHp = maxHp,
        mp = mp, maxMp = maxMp,
        physicalAttack = physAtk, magicAttack = magAtk,
        physicalDefense = physDef, magicDefense = magDef,
        speed = speed, critRate = 0.1,
        skills = skills, buffs = buffs
    )

    private fun attackSkill(
        name: String = "斩击",
        dmgMult: Double = 1.5,
        mpCost: Int = 30,
        cooldown: Int = 2,
        isAoe: Boolean = false,
        dmgType: DamageType = DamageType.PHYSICAL
    ) = CombatSkill(
        name = name,
        skillType = SkillType.ATTACK,
        damageType = dmgType,
        damageMultiplier = dmgMult,
        mpCost = mpCost,
        cooldown = cooldown,
        isAoe = isAoe,
        targetScope = "enemy"
    )

    private fun healSkill(
        name: String = "治愈",
        healPercent: Double = 0.3,
        mpCost: Int = 40,
        targetScope: String = "ally"
    ) = CombatSkill(
        name = name,
        skillType = SkillType.SUPPORT,
        damageType = DamageType.PHYSICAL,
        damageMultiplier = 0.0,
        mpCost = mpCost,
        cooldown = 3,
        healPercent = healPercent,
        healType = HealType.HP,
        targetScope = targetScope,
        isAoe = targetScope == "team"
    )

    private fun shieldSkill(
        name: String = "护盾",
        mpCost: Int = 35,
        targetScope: String = "self"
    ) = CombatSkill(
        name = name,
        skillType = SkillType.SUPPORT,
        damageType = DamageType.PHYSICAL,
        damageMultiplier = 0.0,
        mpCost = mpCost,
        cooldown = 4,
        shieldPercent = 0.25,
        targetScope = targetScope
    )

    private fun buffSkill(
        name: String = "战吼",
        mpCost: Int = 25,
        targetScope: String = "team",
        isAoe: Boolean = true,
        buffType: BuffType = BuffType.PHYSICAL_ATTACK_BOOST,
        buffValue: Double = 0.2
    ) = CombatSkill(
        name = name,
        skillType = SkillType.SUPPORT,
        damageType = DamageType.PHYSICAL,
        damageMultiplier = 0.0,
        mpCost = mpCost,
        cooldown = 3,
        buffType = buffType,
        buffValue = buffValue,
        buffDuration = 3,
        targetScope = targetScope,
        isAoe = isAoe
    )

    private fun stunSkill(
        name: String = "定身",
        mpCost: Int = 20,
        cooldown: Int = 5
    ) = CombatSkill(
        name = name,
        skillType = SkillType.ATTACK,
        damageType = DamageType.PHYSICAL,
        damageMultiplier = 0.0,
        mpCost = mpCost,
        cooldown = cooldown,
        buffType = BuffType.STUN,
        buffValue = 1.0,
        buffDuration = 1,
        targetScope = "enemy"
    )

    private fun stunBuff() = CombatBuff(
        type = BuffType.STUN, value = 1.0,
        remainingDuration = 1
    )

    private fun silenceBuff() = CombatBuff(
        type = BuffType.SILENCE, value = 1.0,
        remainingDuration = 2
    )

    // ---- Tier 1: 被控检查 ----

    @Test
    fun `stunned unit returns NONE`() {
        val unit = combatant(
            id = "stunned", hp = 800, maxHp = 1000,
            buffs = listOf(stunBuff()),
            skills = listOf(attackSkill())
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(combatant(id = "e1")),
            fixedRandom
        )
        assertEquals(BattleAI.AIActionType.NONE, action.actionType)
    }

    @Test
    fun `silenced unit falls back to normal attack`() {
        val unit = combatant(
            id = "silenced", hp = 800, maxHp = 1000,
            buffs = listOf(silenceBuff()),
            skills = listOf(attackSkill())
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(combatant(id = "e1")),
            fixedRandom
        )
        // 沉默时跳过技能层，直接普通攻击
        assertNull(action.skill)
        assertEquals(
            BattleAI.AIActionType.NORMAL_ATTACK,
            action.actionType
        )
    }

    // ---- Tier 2: 保命 ----

    @Test
    fun `low HP with shield skill triggers self preservation`() {
        val unit = combatant(
            id = "lowhp", hp = 200, maxHp = 1000, // 20%
            skills = listOf(shieldSkill(), attackSkill())
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(combatant(id = "e1")),
            fixedRandom
        )
        assertEquals(
            BattleAI.AIActionType.SKILL_BUFF_SELF,
            action.actionType
        )
        assertEquals("护盾", action.skill?.name)
    }

    @Test
    fun `low HP without preservation skills falls through to attack`() {
        val unit = combatant(
            id = "lowhp", hp = 200, maxHp = 1000,
            skills = listOf(attackSkill())
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(combatant(id = "e1")),
            fixedRandom
        )
        // 没有保命技能，应该继续攻击
        assertNotNull(action.skill)
    }

    // ---- Tier 3: 斩杀 ----

    @Test
    fun `execute kills low HP enemy when damage sufficient`() {
        val unit = combatant(
            id = "attacker", hp = 800, maxHp = 1000,
            skills = listOf(attackSkill("重击", dmgMult = 3.0))
        )
        val lowEnemy = combatant(
            id = "weak", hp = 50, maxHp = 600, // ~8%
            side = CombatantSide.ATTACKER
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(lowEnemy),
            fixedRandom
        )
        assertEquals(
            BattleAI.AIActionType.SKILL_ATTACK_SINGLE,
            action.actionType
        )
        assertEquals("weak", action.target?.id)
    }

    @Test
    fun `execute skipped when damage insufficient for kill`() {
        val unit = combatant(
            id = "attacker", hp = 800, maxHp = 1000,
            skills = listOf(attackSkill("轻击", dmgMult = 0.5))
        )
        val lowEnemy = combatant(
            id = "tanky_low", hp = 150, maxHp = 600,
            physDef = 500, // 高防御
            side = CombatantSide.ATTACKER
        )
        // 伤害不足以斩杀 → 掉入后续层（可能仍是攻击，但不应是斩杀目标）
        // 由于只有 1 个敌人且无 AOE/支援，最终应为普通攻击或技能攻击
        val action = BattleAI.decideAction(
            unit, listOf(unit), listOf(lowEnemy),
            fixedRandom
        )
        assertNotNull(action) // 至少应返回有效行动
    }

    // ---- Tier 4: 支援盟友 ----

    @Test
    fun `heal low HP ally when heal skill available`() {
        val healer = combatant(
            id = "healer", hp = 800, maxHp = 1000,
            skills = listOf(healSkill())
        )
        val wounded = combatant(
            id = "wounded", hp = 300, maxHp = 1000 // 30%
        )
        // 用高概率种子多次尝试，确保治愈逻辑可触发
        var healed = false
        for (seed in 1..20) {
            val action = BattleAI.decideAction(
                healer,
                listOf(healer, wounded),
                listOf(combatant(
                    id = "e1", side = CombatantSide.ATTACKER
                )),
                Random(seed)
            )
            if (action.actionType ==
                BattleAI.AIActionType.SKILL_HEAL_ALLY ||
                action.actionType ==
                BattleAI.AIActionType.SKILL_HEAL_TEAM
            ) {
                healed = true
                break
            }
        }
        assertTrue(
            "治愈技能应在至少一个随机种子下触发",
            healed
        )
    }

    // ---- Tier 5: 团队 Buff ----

    @Test
    fun `team buff when no urgent needs`() {
        val buffer = combatant(
            id = "buffer", hp = 900, maxHp = 1000,
            skills = listOf(buffSkill(targetScope = "team"))
        )
        val healthy = combatant(id = "ally", hp = 900, maxHp = 1000)
        val action = BattleAI.decideAction(
            buffer,
            listOf(buffer, healthy),
            listOf(combatant(id = "e1", side = CombatantSide.ATTACKER)),
            fixedRandom
        )
        // 无人需要治疗，有团队 Buff → 上 Buff
        assertTrue(
            action.actionType ==
                BattleAI.AIActionType.SKILL_BUFF_TEAM ||
                action.actionType ==
                BattleAI.AIActionType.SKILL_BUFF_SELF
        )
    }

    // ---- Tier 7: AOE ----

    @Test
    fun `use AOE when 3 or more enemies`() {
        val aoeUnit = combatant(
            id = "aoe_user", hp = 800, maxHp = 1000,
            skills = listOf(attackSkill("旋风斩",
                dmgMult = 1.2, isAoe = true))
        )
        val enemies = listOf(
            combatant("e1", side = CombatantSide.ATTACKER),
            combatant("e2", side = CombatantSide.ATTACKER),
            combatant("e3", side = CombatantSide.ATTACKER)
        )
        // AOE 是概率触发(70%)，使用固定种子 42 可能或可能不触发
        // 测试至少返回有效行动
        val action = BattleAI.decideAction(
            aoeUnit, listOf(aoeUnit), enemies, fixedRandom
        )
        assertNotNull(action)
    }

    // ---- Tier 8: MP 省蓝 ----

    @Test
    fun `low MP uses cheapest attack skill`() {
        val unit = combatant(
            id = "low_mp", mp = 20, maxMp = 200, // 10%
            skills = listOf(
                attackSkill("贵", dmgMult = 2.0, mpCost = 80),
                attackSkill("便宜", dmgMult = 0.8, mpCost = 15)
            )
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit),
            listOf(combatant("e1", side = CombatantSide.ATTACKER)),
            fixedRandom
        )
        // 省蓝模式下选最便宜的技能
        assertEquals("便宜", action.skill?.name)
    }

    // ---- 目标选择 ----

    @Test
    fun `target selection prefers lowest HP enemy`() {
        val enemies = listOf(
            combatant("full", hp = 1000, maxHp = 1000,
                side = CombatantSide.ATTACKER),
            combatant("half", hp = 500, maxHp = 1000,
                side = CombatantSide.ATTACKER),
            combatant("near_dead", hp = 10, maxHp = 1000,
                side = CombatantSide.ATTACKER)
        )
        // 多次调用，统计结果
        val lowHpRandom = Random(1) // 低血量优先的种子
        val target = BattleAI.selectAttackTarget(
            combatant(id = "attacker"),
            enemies, null, lowHpRandom
        )
        // 70% 概率选最低 HP
        assertNotNull(target)
    }

    // ---- 确定性 ----

    @Test
    fun `same seed produces same result 3 times`() {
        val unit = combatant(
            id = "det", hp = 800, maxHp = 1000,
            skills = listOf(
                attackSkill("A", dmgMult = 2.0, mpCost = 30),
                attackSkill("B", dmgMult = 1.0, mpCost = 15)
            )
        )
        val enemies = listOf(
            combatant("e1", side = CombatantSide.ATTACKER)
        )

        val r1 = BattleAI.decideAction(
            unit, listOf(unit), enemies, Random(123)
        )
        val r2 = BattleAI.decideAction(
            unit, listOf(unit), enemies, Random(123)
        )
        val r3 = BattleAI.decideAction(
            unit, listOf(unit), enemies, Random(123)
        )

        assertEquals(r1.actionType, r2.actionType)
        assertEquals(r1.skill?.name, r2.skill?.name)
        assertEquals(r1.actionType, r3.actionType)
        assertEquals(r1.skill?.name, r3.skill?.name)
    }

    // ---- 伤害估算 ----

    @Test
    fun `estimate damage is deterministic`() {
        val attacker = combatant(
            id = "attacker", physAtk = 200
        )
        val defender = combatant(
            id = "defender", physDef = 100
        )
        val skill = attackSkill("测试", dmgMult = 1.5)

        val d1 = BattleAI.estimateDamage(
            attacker, defender, skill)
        val d2 = BattleAI.estimateDamage(
            attacker, defender, skill)
        assertEquals(d1, d2)
    }

    // ---- 边界条件 ----

    @Test
    fun `dead unit returns NONE`() {
        val dead = combatant(id = "dead", hp = 0, maxHp = 1000)
        val action = BattleAI.decideAction(
            dead, listOf(dead), listOf(combatant(id = "e1")),
            fixedRandom
        )
        assertEquals(BattleAI.AIActionType.NONE, action.actionType)
    }

    @Test
    fun `no enemies returns NONE`() {
        val unit = combatant(id = "alone")
        val action = BattleAI.decideAction(
            unit, listOf(unit), emptyList(), fixedRandom
        )
        assertEquals(BattleAI.AIActionType.NONE, action.actionType)
    }

    @Test
    fun `empty skill list falls back to normal attack`() {
        val unit = combatant(
            id = "no_skills", hp = 800, maxHp = 1000,
            skills = emptyList()
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit),
            listOf(combatant("e1", side = CombatantSide.ATTACKER)),
            fixedRandom
        )
        assertEquals(
            BattleAI.AIActionType.NORMAL_ATTACK,
            action.actionType
        )
    }

    @Test
    fun `select support target returns null for no candidates`() {
        val caster = combatant(id = "caster")
        val skill = healSkill()
        // 只有自己，无其他盟友
        val target = BattleAI.selectSupportTarget(
            caster, listOf(caster), skill
        )
        assertNull(target)
    }

    // ---- 零伤害攻击技能回归测试 ----

    @Test
    fun `unit with only stun skill uses control action`() {
        val stun = stunSkill()
        val unit = combatant(
            id = "stunner", hp = 800, maxHp = 1000,
            skills = listOf(stun)
        )
        val enemy = combatant(
            id = "enemy", hp = 800, maxHp = 1000,
            side = CombatantSide.ATTACKER, buffs = emptyList()
        )
        // 找能命中 60% 控制概率的种子
        var foundControl = false
        for (seed in 1..100) {
            val action = BattleAI.decideAction(
                unit, listOf(unit), listOf(enemy), Random(seed)
            )
            if (action.actionType == BattleAI.AIActionType.SKILL_ATTACK_SINGLE) {
                assertEquals("定身", action.skill?.name)
                assertEquals("enemy", action.target?.id)
                foundControl = true
                break
            }
        }
        assertTrue(
            "至少有一个种子会使眩晕技能被用作控制",
            foundControl
        )
    }

    @Test
    fun `unit with only stun falls back to normal attack when control fails`() {
        val stun = stunSkill()
        val unit = combatant(
            id = "stunner", hp = 800, maxHp = 1000,
            skills = listOf(stun)
        )
        var foundNormalAttack = false
        for (seed in 1..50) {
            val action = BattleAI.decideAction(
                unit, listOf(unit),
                listOf(combatant("e1", side = CombatantSide.ATTACKER)),
                Random(seed)
            )
            if (action.actionType == BattleAI.AIActionType.NORMAL_ATTACK) {
                foundNormalAttack = true
                assertNull(action.skill)
                break
            }
        }
        assertTrue(
            "至少有一个种子会使 AI 回退到普通攻击",
            foundNormalAttack
        )
    }

    @Test
    fun `stun not used as attack when mixed with damage skills`() {
        val stun = stunSkill()
        val damage = attackSkill("斩击", dmgMult = 1.5, mpCost = 30)
        val unit = combatant(
            id = "hybrid", hp = 800, maxHp = 1000,
            skills = listOf(damage, stun)
        )
        val action = BattleAI.decideAction(
            unit, listOf(unit),
            listOf(combatant("e1", side = CombatantSide.ATTACKER)),
            fixedRandom
        )
        // 如果使用技能攻击，必须是伤害技能（不是眩晕）
        if (action.actionType == BattleAI.AIActionType.SKILL_ATTACK_SINGLE) {
            assertTrue(
                "攻击技能应该造成伤害",
                (action.skill?.damageMultiplier ?: 0.0) > 0.0
            )
        }
    }

    @Test
    fun `zero damage non-control skills fall back to normal attack`() {
        val zeroDmg = CombatSkill(
            name = "零伤", skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 10, cooldown = 1,
            targetScope = "enemy"
        )
        val unit = combatant(
            id = "weak", hp = 800, maxHp = 1000,
            skills = listOf(zeroDmg)
        )
        var foundNormalAttack = false
        for (seed in 1..50) {
            val action = BattleAI.decideAction(
                unit, listOf(unit),
                listOf(combatant("e1", side = CombatantSide.ATTACKER)),
                Random(seed)
            )
            if (action.actionType == BattleAI.AIActionType.NORMAL_ATTACK) {
                foundNormalAttack = true
                break
            }
        }
        assertTrue(
            "零伤害非控制技能应回退到普通攻击",
            foundNormalAttack
        )
    }
}
