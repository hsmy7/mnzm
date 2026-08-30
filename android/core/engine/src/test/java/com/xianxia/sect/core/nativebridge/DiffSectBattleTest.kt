package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.aisRngManager
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
 * DiffSectBattleTest — AI 宗门战第三引擎跨语言差分对拍（战斗批次 D-3）。
 *
 * 守护目标：Kotlin `AISectAttackManager.executeUnifiedAIBattle`（AI vs AI
 * 宗门战/洞天 AI 操作共用；回合内逐行动 filter 死亡列表压缩语义）与 C++
 * `gamecore::battle::sect_battle.h` 在相同种子下产出**逐字段一致**的战斗
 * 终态（turns/winner + 逐 Combatant hp/mp/buffs/skills 冷却 + rounds
 * 动作序列）。
 *
 * 确定性基础：Kotlin 侧 `aisRngManager` 注入固定种子 GameRngManager →
 * BATTLE 分区 = DeterministicRng.fromSeed(seed + 0)；C++ 侧
 * `nativeFromSeed(seed)` 独立同种子实例按相同消费序驱动。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSectBattleTest {

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
        put("isBeast", c.isBeast)
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

    /** 单个 Combatant 终态对比（id/hp/mp/buffs/skills.currentCooldown）。 */
    private fun assertCombatantMatches(
        tag: String,
        k: Combatant,
        c: kotlinx.serialization.json.JsonObject
    ) {
        assertEquals("$tag id", k.id, c["id"]!!.jsonPrimitive.content)
        assertEquals("$tag ${k.id} hp", k.hp, c["hp"]!!.jsonPrimitive.content.toInt())
        assertEquals("$tag ${k.id} mp", k.mp, c["mp"]!!.jsonPrimitive.content.toInt())
        val kBuffs = k.buffs.map { Triple(it.type.name, it.value, it.remainingDuration) }
        val cBuffs = c["buffs"]!!.jsonArray.map {
            val o = it.jsonObject
            Triple(o["type"]!!.jsonPrimitive.content,
                o["value"]!!.jsonPrimitive.content.toDouble(),
                o["remainingDuration"]!!.jsonPrimitive.content.toInt())
        }
        assertEquals("$tag ${k.id} buffs", kBuffs, cBuffs)
        val kSkills = k.skills.map { it.name to it.currentCooldown }
        val cSkills = c["skills"]!!.jsonArray.map {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to
                o["currentCooldown"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals("$tag ${k.id} skills", kSkills, cSkills)
    }

    /**
     * 单场景对拍：Kotlin AISectAttackManager.executeUnifiedAIBattle vs
     * C++ executeAiBattle op。断言 turns/winner + 逐 Combatant 终态 + rounds。
     */
    private fun runAiBattleDiff(
        seed: Long,
        attackers: List<Combatant>,
        defenders: List<Combatant>
    ) {
        // ── Kotlin 臂：aisRngManager 注入固定种子（BATTLE 分区 = fromSeed(seed)） ──
        val rngManager = GameRngManager().apply { initSystemSeed(seed) }
        aisRngManager = rngManager
        try {
            val kResult = AISectAttackManager.executeUnifiedAIBattle(attackers, defenders)

            // ── C++ 臂：g_rng 同种子 ──
            DiffRngBridge.nativeFromSeed(seed)
            val op = buildJsonObject {
                put("op", "executeAiBattle")
                putJsonArray("attackers") { attackers.forEach { add(combatantJson(it)) } }
                putJsonArray("defenders") { defenders.forEach { add(combatantJson(it)) } }
            }
            val c = json.parseToJsonElement(
                DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
            ).jsonObject

            val tag = "seed=$seed"
            assertEquals("$tag turns", kResult.turns, c["turns"]!!.jsonPrimitive.content.toInt())
            assertEquals("$tag winner", kResult.winner.name, c["winner"]!!.jsonPrimitive.content)
            val cAttackers = c["attackers"]!!.jsonArray.map { it.jsonObject }
            val cDefenders = c["defenders"]!!.jsonArray.map { it.jsonObject }
            // 终态列表（Kotlin 列表压缩语义——终态即存活者）
            assertEquals("$tag attackers size", kResult.attackers.size, cAttackers.size)
            assertEquals("$tag defenders size", kResult.defenders.size, cDefenders.size)
            kResult.attackers.forEachIndexed { i, kc ->
                assertCombatantMatches("$tag attackers[$i]", kc, cAttackers[i])
            }
            kResult.defenders.forEachIndexed { i, kc ->
                assertCombatantMatches("$tag defenders[$i]", kc, cDefenders[i])
            }
            // rounds 动作序列（确定性字段）
            val cRounds = c["rounds"]!!.jsonArray
            assertEquals("$tag rounds count", kResult.rounds.size, cRounds.size)
            kResult.rounds.zip(cRounds).forEachIndexed { i, (kr, cr) ->
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
                    assertEquals("$aTag isCrit", ka.isCrit,
                        co["isCrit"]!!.jsonPrimitive.content.toBoolean())
                    assertEquals("$aTag isKill", ka.isKill,
                        co["isKill"]!!.jsonPrimitive.content.toBoolean())
                    assertEquals("$aTag skillName", ka.skillName,
                        co["skillName"]?.jsonPrimitive?.content)
                }
            }
        } finally {
            aisRngManager = null
        }
    }

    // ── 测试场景 ──────────────────────────────────────────────────

    @Test
    fun `basic ai battle matches Kotlin across seeds`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val attacker1 = baseCombatant("a1", "攻一").copy(
            side = CombatantSide.ATTACKER,
            skills = listOf(
                attackSkill("破军斩", 2.0, 15, 2),
                attackSkill("烈焰斩", 2.4, 20, 3)
            )
        )
        val attacker2 = baseCombatant("a2", "攻二").copy(
            side = CombatantSide.ATTACKER, physicalAttack = 180,
            physicalDefense = 90, speed = 60,
            skills = listOf(attackSkill("碎岩击", 1.7, 10, 2))
        )
        val defender1 = baseCombatant("d1", "守一").copy(
            skills = listOf(attackSkill("重斩", 1.8, 10, 2))
        )
        val defender2 = baseCombatant("d2", "守二").copy(
            magicAttack = 140, magicDefense = 70, speed = 75,
            skills = listOf(attackSkill("冰锥", 1.9, 12, 2))
        )
        for (seed in longArrayOf(42, 20260901, 987654321)) {
            runAiBattleDiff(seed, listOf(attacker1, attacker2), listOf(defender1, defender2))
        }
    }

    @Test
    fun `ai battle with support and control matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val healer = baseCombatant("a1", "医师").copy(
            side = CombatantSide.ATTACKER,
            skills = listOf(
                healSkill("回春术", 0.5, 15, 2, "ally"),
                teamBuffSkill("疾风阵", 0.2, 20, 4)
            )
        )
        val controller = baseCombatant("a2", "控场").copy(
            side = CombatantSide.ATTACKER,
            skills = listOf(stunSkill("定身咒", 15, 3))
        )
        val defender1 = baseCombatant("d1", "守一").copy(
            hp = 500, physicalAttack = 160
        )
        val defender2 = baseCombatant("d2", "守二").copy(
            physicalAttack = 150
        )
        for (seed in longArrayOf(7, 99, 2024)) {
            runAiBattleDiff(seed, listOf(healer, controller), listOf(defender1, defender2))
        }
    }

    @Test
    fun `ai battle with aoe and shield matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val aoeUser = baseCombatant("a1", "群攻").copy(
            side = CombatantSide.ATTACKER,
            skills = listOf(
                aoeSkill("裂地崩", 1.5, 25, 4),
                selfShieldSkill("护体灵光", 0.3, 10, 3)
            )
        )
        val defenders = (1..3).map { i ->
            baseCombatant("d$i", "守$i").copy(
                hp = 600, maxHp = 600, physicalAttack = 100 + i * 20
            )
        }
        for (seed in longArrayOf(42, 555)) {
            runAiBattleDiff(seed, listOf(aoeUser), defenders)
        }
    }

    @Test
    fun `ai battle with link and share matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val linker = baseCombatant("a1", "链接师").copy(
            side = CombatantSide.ATTACKER,
            skills = listOf(linkSkill("同生共死", 0.5, 20, 3))
        )
        val sharer = baseCombatant("a2", "分摊者").copy(
            side = CombatantSide.ATTACKER,
            buffs = listOf(CombatBuff(BuffType.DAMAGE_SHARE, 0.4, 5))
        )
        val beast = baseCombatant("d1", "守兽").copy(
            buffs = listOf(CombatBuff(BuffType.DAMAGE_LINK, 0.3, 5))
        )
        for (seed in longArrayOf(42, 777)) {
            runAiBattleDiff(seed, listOf(linker, sharer), listOf(beast))
        }
    }

    @Test
    fun `ai battle ends when side wiped with winner`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 攻击方境界压制 → 快速全灭防御方 → ATTACKER 胜利
        val overPowered = baseCombatant("a1", "高境界").copy(
            side = CombatantSide.ATTACKER, realm = 5, realmLayer = 9,
            physicalAttack = 500,
            skills = listOf(attackSkill("破军斩", 3.0, 20, 3))
        )
        val weakDefender = baseCombatant("d1", "弱守").copy(
            realm = 8, hp = 300, maxHp = 300, physicalDefense = 20
        )
        for (seed in longArrayOf(1, 42)) {
            runAiBattleDiff(seed, listOf(overPowered), listOf(weakDefender))
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

    private fun linkSkill(name: String, linkPercent: Double, mpCost: Int, cooldown: Int) =
        CombatSkill(
            name = name, skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = 1.0,
            mpCost = mpCost, cooldown = cooldown, damageLinkPercent = linkPercent,
            buffDuration = 2
        )
}
