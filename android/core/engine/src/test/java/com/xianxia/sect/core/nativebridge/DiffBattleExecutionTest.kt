package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffBattleExecutionTest — 战斗回合编排跨语言差分对拍。
 *
 * 守护目标：Kotlin `BattleSystem.executeBattle`（回合编排全链：速度序 →
 * 逐参战者行动（决策/技能四分支/伤害应用/冷却/治疗/拉条/控制/DoT）→
 * 胜负判定 + 奖励）与 C++ `gamecore::battle::battle_execution.h` 在相同
 * 种子下产出**逐字段一致**的战斗终态（turn/winner/rewards + 逐 Combatant
 * 的 hp/mp/buffs/技能冷却）。战斗日志/描述（BattleDescriptionGenerator）
 * 保持 Kotlin，diff 面排除 message。
 *
 * 确定性基础：Kotlin 侧 `GameRngManager.initSystemSeed(seed)` → BATTLE 分区
 * = DeterministicRng.fromSeed(seed + 0)（BATTLE.id=0）；C++ 侧
 * `nativeFromSeed(seed)` 独立同种子实例按相同消费序驱动。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffBattleExecutionTest {

    private val json = Json

    // ── Combatant → JSON（与 C++ combatantFromJson 字段对应） ──

    private fun buffJson(buff: CombatBuff) = buildJsonObject {
        put("type", buff.type.name)
        put("value", buff.value)
        put("remainingDuration", buff.remainingDuration)
        put("sourceRealm", buff.sourceRealm)
        put("sourceRealmLayer", buff.sourceRealmLayer)
    }

    private fun skillJson(skill: CombatSkill) = buildJsonObject {
        put("name", skill.name)
        put("skillType", skill.skillType.name)
        put("damageType", skill.damageType.name)
        put("damageMultiplier", skill.damageMultiplier)
        put("mpCost", skill.mpCost)
        put("cooldown", skill.cooldown)
        put("hits", skill.hits)
        put("healPercent", skill.healPercent)
        put("healFixed", skill.healFixed)
        put("healType", skill.healType.name)
        if (skill.buffType != null) put("buffType", skill.buffType!!.name)
        put("buffValue", skill.buffValue)
        put("buffDuration", skill.buffDuration)
        putJsonArray("buffs") {
            skill.buffs.forEach { (t, v, d) ->
                add(buildJsonObject {
                    put("type", t.name); put("value", v); put("duration", d)
                })
            }
        }
        put("currentCooldown", skill.currentCooldown)
        put("isAoe", skill.isAoe)
        put("targetScope", skill.targetScope)
        put("shieldPercent", skill.shieldPercent)
        put("turnAdvancePercent", skill.turnAdvancePercent)
        put("damageSharePercent", skill.damageSharePercent)
        put("damageLinkPercent", skill.damageLinkPercent)
    }

    private fun combatantJson(c: Combatant) = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("side", c.side.name)
        put("hp", c.hp)
        put("maxHp", c.maxHp)
        put("mp", c.mp)
        put("maxMp", c.maxMp)
        put("physicalAttack", c.physicalAttack)
        put("magicAttack", c.magicAttack)
        put("physicalDefense", c.physicalDefense)
        put("magicDefense", c.magicDefense)
        put("speed", c.speed)
        put("critRate", c.critRate)
        putJsonArray("skills") { c.skills.forEach { add(skillJson(it)) } }
        putJsonArray("buffs") { c.buffs.forEach { add(buffJson(it)) } }
        put("realm", c.realm)
        put("realmLayer", c.realmLayer)
        put("element", c.element)
        putJsonObject("physique") {
            put("damageAmplification", c.physique.damageAmplification)
            put("critDamageBonus", c.physique.critDamageBonus)
            put("damageReduction", c.physique.damageReduction)
            put("defenseBonus", c.physique.defenseBonus)
        }
        putJsonObject("affix") {
            put("damageAmplification", c.affix.damageAmplification)
            put("critDamageBonus", c.affix.critDamageBonus)
            put("damageReduction", c.affix.damageReduction)
            put("defenseBonus", c.affix.defenseBonus)
        }
    }

    /** 单个 Combatant 终态对比（id/hp/mp/buffs/skills.currentCooldown） */
    private fun assertCombatantMatches(
        tag: String,
        k: Combatant,
        c: kotlinx.serialization.json.JsonObject
    ) {
        assertEquals("$tag id", k.id, c["id"]!!.jsonPrimitive.content)
        assertEquals("$tag ${k.id} hp", k.hp, c["hp"]!!.jsonPrimitive.content.toInt())
        assertEquals("$tag ${k.id} mp", k.mp, c["mp"]!!.jsonPrimitive.content.toInt())
        // buffs：type/value/remainingDuration 逐条一致（含顺序）
        val kBuffs = k.buffs.map { Triple(it.type.name, it.value, it.remainingDuration) }
        val cBuffs = c["buffs"]!!.jsonArray.map {
            val o = it.jsonObject
            Triple(o["type"]!!.jsonPrimitive.content, o["value"]!!.jsonPrimitive.content.toDouble(),
                o["remainingDuration"]!!.jsonPrimitive.content.toInt())
        }
        assertEquals("$tag ${k.id} buffs", kBuffs, cBuffs)
        // skills：name/currentCooldown 逐条一致（含顺序）
        val kSkills = k.skills.map { it.name to it.currentCooldown }
        val cSkills = c["skills"]!!.jsonArray.map {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to
                o["currentCooldown"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals("$tag ${k.id} skills", kSkills, cSkills)
    }

    /**
     * 单场景对拍：Kotlin BattleSystem.executeBattle vs C++ executeBattle op。
     * 断言 turn/winner/rewards + 逐 Combatant 终态一致。
     */
    private fun runBattleDiff(
        seed: Long,
        team: List<Combatant>,
        beasts: List<Combatant>,
        playerDamageModifier: Double = 1.0
    ) {
        // ── Kotlin 臂：BATTLE 分区 = fromSeed(seed + 0) ──
        val rngManager = GameRngManager().apply { initSystemSeed(seed) }
        val system = BattleSystem(rngManager)
        val kResult = system.executeBattle(Battle(team, beasts), playerDamageModifier)
        val kBattle = kResult.battle

        // ── C++ 臂：g_rng 同种子 ──
        DiffRngBridge.nativeFromSeed(seed)
        val op = buildJsonObject {
            put("op", "executeBattle")
            putJsonArray("team") { team.forEach { add(combatantJson(it)) } }
            putJsonArray("beasts") { beasts.forEach { add(combatantJson(it)) } }
            put("playerDamageModifier", playerDamageModifier)
        }
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
        ).jsonObject

        val tag = "seed=$seed"
        // 回合数与胜负
        assertEquals("$tag turn", kResult.turnCount, c["turn"]!!.jsonPrimitive.content.toInt())
        assertEquals("$tag winner", kBattle.winner?.name,
            c["winner"]!!.jsonPrimitive.content)
        // 奖励（TEAM 胜利时非空）
        val kRewards = kResult.rewards
        val cRewards = c["rewards"]!!.jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }
        assertEquals("$tag rewards", kRewards, cRewards)
        // 逐 Combatant 终态（按 id 匹配）
        val cTeam = c["team"]!!.jsonArray.map { it.jsonObject }
        val cBeasts = c["beasts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("$tag team size", team.size, cTeam.size)
        assertEquals("$tag beasts size", beasts.size, cBeasts.size)
        kBattle.team.forEachIndexed { i, kc ->
            assertCombatantMatches("$tag team[$i]", kc, cTeam[i])
        }
        kBattle.beasts.forEachIndexed { i, kc ->
            assertCombatantMatches("$tag beasts[$i]", kc, cBeasts[i])
        }
        // 回合动作序列（rounds 确定性字段；message 随机措辞排除）
        assertRoundsMatch("$tag", kResult.log.rounds, c["rounds"]!!.jsonArray)
    }

    /** C++ rounds JSON vs Kotlin log.rounds（确定性字段逐条一致，排除 message）。 */
    private fun assertRoundsMatch(
        tag: String,
        kRounds: List<com.xianxia.sect.core.engine.domain.battle.BattleRoundData>,
        cRounds: kotlinx.serialization.json.JsonArray
    ) {
        assertEquals("$tag rounds count", kRounds.size, cRounds.size)
        kRounds.zip(cRounds).forEachIndexed { i, (kr, cr) ->
            val cRound = cr.jsonObject
            assertEquals("$tag round[$i] number", kr.roundNumber,
                cRound["roundNumber"]!!.jsonPrimitive.content.toInt())
            val cActions = cRound["actions"]!!.jsonArray
            assertEquals("$tag round[$i] actions count", kr.actions.size, cActions.size)
            kr.actions.zip(cActions).forEachIndexed { j, (ka, ca) ->
                val co = ca.jsonObject
                val aTag = "$tag round[$i] action[$j]"
                assertEquals("$aTag type", ka.type, co["type"]!!.jsonPrimitive.content)
                assertEquals("$aTag attacker", ka.attacker, co["attacker"]!!.jsonPrimitive.content)
                assertEquals("$aTag attackerType", ka.attackerType,
                    co["attackerType"]!!.jsonPrimitive.content)
                assertEquals("$aTag target", ka.target, co["target"]!!.jsonPrimitive.content)
                assertEquals("$aTag damage", ka.damage, co["damage"]!!.jsonPrimitive.content.toInt())
                assertEquals("$aTag damageType", ka.damageType,
                    co["damageType"]!!.jsonPrimitive.content)
                assertEquals("$aTag isCrit", ka.isCrit,
                    co["isCrit"]!!.jsonPrimitive.content.toBoolean())
                assertEquals("$aTag isKill", ka.isKill,
                    co["isKill"]!!.jsonPrimitive.content.toBoolean())
                assertEquals("$aTag isInstantKill", ka.isInstantKill,
                    co["isInstantKill"]!!.jsonPrimitive.content.toBoolean())
                assertEquals("$aTag skillName", ka.skillName,
                    co["skillName"]?.jsonPrimitive?.content)
            }
        }
    }

    // ── 测试场景 ──────────────────────────────────────────────────

    @Test
    fun `basic combat with attacks and skills matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val disciple1 = baseCombatant("d1", "剑修").copy(
            skills = listOf(
                attackSkill("重斩", 1.8, 10, 2),
                attackSkill("烈焰斩", 2.2, 18, 3)
            )
        )
        val disciple2 = baseCombatant("d2", "体修").copy(
            physicalAttack = 180, physicalDefense = 90, speed = 60,
            skills = listOf(attackSkill("碎岩击", 1.6, 8, 2))
        )
        val beast1 = baseCombatant("beast_1", "烈焰狼").copy(
            side = CombatantSide.ATTACKER, hp = 800, maxHp = 800,
            physicalAttack = 150, speed = 90,
            skills = listOf(attackSkill("撕咬", 1.5, 5, 1))
        )
        val beast2 = baseCombatant("beast_2", "冰霜狼").copy(
            side = CombatantSide.ATTACKER, hp = 700, maxHp = 700,
            magicAttack = 140, speed = 75,
            skills = listOf(attackSkill("冰锥", 1.7, 8, 2))
        )
        for (seed in longArrayOf(42, 20260901, 987654321)) {
            runBattleDiff(seed, listOf(disciple1, disciple2), listOf(beast1, beast2))
        }
    }

    @Test
    fun `support heal and team buff matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val healer = baseCombatant("d1", "医师").copy(
            skills = listOf(
                healSkill("回春术", 0.5, 15, 2, "ally"),
                teamBuffSkill("疾风阵", 0.2, 20, 4)
            )
        )
        val tank = baseCombatant("d2", "肉盾").copy(hp = 400, maxHp = 1000)
        val beast = baseCombatant("beast_1", "妖兽").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 200, speed = 70
        )
        for (seed in longArrayOf(7, 99)) {
            runBattleDiff(seed, listOf(healer, tank), listOf(beast))
        }
    }

    @Test
    fun `control and silence matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val controller = baseCombatant("d1", "控场").copy(
            skills = listOf(stunSkill("定身咒", 15, 3))
        )
        val beast1 = baseCombatant("beast_1", "妖兽一").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 160
        )
        val beast2 = baseCombatant("beast_2", "妖兽二").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 130
        )
        for (seed in longArrayOf(42, 555)) {
            runBattleDiff(seed, listOf(controller), listOf(beast1, beast2))
        }
    }

    @Test
    fun `aoe and shield matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val aoeUser = baseCombatant("d1", "群攻").copy(
            skills = listOf(
                aoeSkill("裂地崩", 1.5, 25, 4),
                selfShieldSkill("护体灵光", 0.3, 10, 3)
            )
        )
        val beasts = (1..3).map { i ->
            baseCombatant("beast_$i", "妖兽$i").copy(
                side = CombatantSide.ATTACKER, hp = 600, maxHp = 600,
                physicalAttack = 120 + i * 20
            )
        }
        for (seed in longArrayOf(42, 123)) {
            runBattleDiff(seed, listOf(aoeUser), beasts)
        }
    }

    @Test
    fun `turn advance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val buffer = baseCombatant("d1", "辅助").copy(
            skills = listOf(
                turnAdvanceSkill("迅捷加持", 15, 4)
            )
        )
        val dps = baseCombatant("d2", "输出").copy(
            physicalAttack = 200,
            skills = listOf(attackSkill("重斩", 1.8, 10, 2))
        )
        val beast = baseCombatant("beast_1", "妖兽").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 150
        )
        for (seed in longArrayOf(42, 2024)) {
            runBattleDiff(seed, listOf(buffer, dps), listOf(beast))
        }
    }

    @Test
    fun `damage link and share matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val linker = baseCombatant("d1", "链接师").copy(
            skills = listOf(linkSkill("同生共死", 0.5, 20, 3))
        )
        val sharer = baseCombatant("d2", "分摊者").copy(
            buffs = listOf(CombatBuff(BuffType.DAMAGE_SHARE, 0.4, 5))
        )
        val beast = baseCombatant("beast_1", "妖兽").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 220,
            buffs = listOf(CombatBuff(BuffType.DAMAGE_LINK, 0.3, 5))
        )
        for (seed in longArrayOf(42, 777)) {
            runBattleDiff(seed, listOf(linker, sharer), listOf(beast))
        }
    }

    @Test
    fun `battle ends when all enemies die with rewards`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 玩家境界压制 + 高伤 → 快速全灭，验证提前结束 + 奖励
        val overPowered = baseCombatant("d1", "高境界").copy(
            realm = 5, realmLayer = 9, physicalAttack = 500,
            skills = listOf(attackSkill("破军斩", 3.0, 20, 3))
        )
        val weakBeast = baseCombatant("beast_1", "弱兽").copy(
            side = CombatantSide.ATTACKER, realm = 8, hp = 300, maxHp = 300,
            physicalDefense = 20
        )
        for (seed in longArrayOf(1, 42)) {
            runBattleDiff(seed, listOf(overPowered), listOf(weakBeast))
        }
    }

    @Test
    fun `player damage modifier affects battle outcome`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val disciple = baseCombatant("d1", "弟子").copy(
            skills = listOf(attackSkill("重斩", 1.8, 10, 2))
        )
        val beast = baseCombatant("beast_1", "妖兽").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 150,
            physicalDefense = 100
        )
        for (modifier in doubleArrayOf(1.0, 1.5)) {
            for (seed in longArrayOf(42, 7)) {
                runBattleDiff(seed, listOf(disciple), listOf(beast), modifier)
            }
        }
    }

    // ── 构造辅助 ──────────────────────────────────────────────────

    private fun baseCombatant(id: String, name: String): Combatant = Combatant(
        id = id, name = name,
        side = CombatantSide.DEFENDER,
        hp = 1000, maxHp = 1000, mp = 100, maxMp = 100,
        physicalAttack = 120, magicAttack = 100,
        physicalDefense = 60, magicDefense = 50,
        speed = 80, critRate = 0.15,
        skills = emptyList(),
        realm = 9, realmLayer = 1
    )

    private fun attackSkill(name: String, multiplier: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = multiplier,
            mpCost = mpCost, cooldown = cooldown
        )

    private fun healSkill(name: String, healPercent: Double, mpCost: Int, cooldown: Int, scope: String) =
        CombatSkill(
            name = name, skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = mpCost, cooldown = cooldown, healPercent = healPercent,
            healType = HealType.HP, targetScope = scope
        )

    private fun teamBuffSkill(name: String, buffValue: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = mpCost, cooldown = cooldown, targetScope = "team",
            buffType = BuffType.SPEED_BOOST, buffValue = buffValue, buffDuration = 3
        )

    private fun stunSkill(name: String, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.6,
            mpCost = mpCost, cooldown = cooldown, buffType = BuffType.STUN,
            buffValue = 1.0, buffDuration = 2
        )

    private fun aoeSkill(name: String, multiplier: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = multiplier,
            mpCost = mpCost, cooldown = cooldown, isAoe = true
        )

    private fun selfShieldSkill(name: String, shieldPercent: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = mpCost, cooldown = cooldown, targetScope = "self",
            shieldPercent = shieldPercent, buffDuration = 3
        )

    private fun turnAdvanceSkill(name: String, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.SUPPORT,
            damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
            mpCost = mpCost, cooldown = cooldown, targetScope = "ally",
            healPercent = 0.1, healType = HealType.HP, turnAdvancePercent = 1.0
        )

    private fun linkSkill(name: String, linkPercent: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = 1.0,
            mpCost = mpCost, cooldown = cooldown, damageLinkPercent = linkPercent,
            buffDuration = 2
        )
}
