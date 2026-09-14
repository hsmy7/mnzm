package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.PhysiqueCombatFactors
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.registry.AffixCombatEffects
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.DeterministicRng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffBattleCalculatorTest — 战斗计算管线跨语言差分对拍。
 *
 * 守护目标：Kotlin `BattleCalculator` 的**计算管线**（calculateCombatantDamage
 * 全链：斩杀前置 → 闪避 → 暴击 → 波动 → 分桶注入 → 段数钳制；estimateDamage
 * 确定性估算）与 C++ `gamecore::battle::battle_calculator.h` 在相同种子下
 * 产出**逐字段一致**的 DamageResult。
 *
 * 确定性基础：Kotlin 侧 `BattleCalculator.withRng(kRng)`（BATTLE 分区；
 * DeterministicRng.fromSeed(seed)——BATTLE.id=0 → 种子 = systemSeed + 0）；
 * C++ 侧 `nativeFromSeed(seed)` 独立同种子实例按相同消费序驱动
 * （RNG 消费序：闪避 1 + 暴击 1 + 波动 1 次 nextDouble）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffBattleCalculatorTest {

    private val json = Json

    // ── Combatant/Skill → JSON（与 C++ combatantFromJson/skillFromJson 字段对应） ──

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

    private fun runCombatantDamageDiff(
        seed: Long,
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill? = null,
        damageModifier: Double = 1.0,
        enableInstantKill: Boolean = false
    ) {
        val kRng = DeterministicRng.fromSeed(seed)
        DiffRngBridge.nativeFromSeed(seed)

        val kResult = BattleCalculator.withRng(kRng).calculateCombatantDamage(
            attacker, defender, skill, damageModifier, null, enableInstantKill
        )

        val op = buildJsonObject {
            put("op", "combatantDamage")
            put("attacker", combatantJson(attacker))
            put("defender", combatantJson(defender))
            if (skill != null) put("skill", skillJson(skill))
            put("damageModifier", damageModifier)
            put("enableInstantKill", enableInstantKill)
        }
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
        ).jsonObject

        val tag = "seed=$seed ${attacker.name} vs ${defender.name}" +
            (skill?.let { " skill=${it.name}" } ?: "")
        assertEquals("$tag damage", kResult.damage, c["damage"]!!.jsonPrimitive.content.toInt())
        assertEquals("$tag isCrit", kResult.isCrit, c["isCrit"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$tag isPhysical", kResult.isPhysical, c["isPhysical"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$tag isDodged", kResult.isDodged,
            c["isDodged"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$tag isInstantKill", kResult.isInstantKill,
            c["isInstantKill"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$tag hits", kResult.hits, c["hits"]!!.jsonPrimitive.content.toInt())
    }

    // ── 测试场景 ──────────────────────────────────────────────────

    @Test
    fun `combatantDamage matches Kotlin across seeds`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (seed in longArrayOf(42, 20260901, 987654321)) {
            runCombatantDamageDiff(
                seed,
                attacker = baseCombatant("a1", "攻一"),
                defender = baseCombatant("d1", "守一")
            )
        }
    }

    @Test
    fun `combatantDamage with skill matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val skill = CombatSkill(
            name = "烈焰斩", skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
            mpCost = 10, cooldown = 2, hits = 2
        )
        for (seed in longArrayOf(7, 555)) {
            runCombatantDamageDiff(
                seed,
                attacker = baseCombatant("a1", "攻一"),
                defender = baseCombatant("d1", "守一"),
                skill = skill
            )
        }
    }

    @Test
    fun `combatantDamage instant kill matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 攻击方高 2 个大境界（realm 5 vs 7）+ 层数优势 → 必斩（无 RNG 消耗）
        runCombatantDamageDiff(
            42,
            attacker = baseCombatant("a1", "攻一").copy(realm = 5, realmLayer = 9),
            defender = baseCombatant("d1", "守一").copy(realm = 7, realmLayer = 1, maxHp = 999, hp = 999),
            enableInstantKill = true
        )
        // 同境界不斩（走正常管线）
        runCombatantDamageDiff(
            42,
            attacker = baseCombatant("a1", "攻一"),
            defender = baseCombatant("d1", "守一"),
            enableInstantKill = true
        )
    }

    @Test
    fun `combatantDamage with buffs and physique matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val attacker = baseCombatant("a1", "攻一").copy(
            buffs = listOf(
                CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.3, 3),
                CombatBuff(BuffType.CRIT_RATE_BOOST, 0.4, 3)
            ),
            physique = PhysiqueCombatFactors(damageAmplification = 0.2, critDamageBonus = 0.5)
        )
        val defender = baseCombatant("d1", "守一").copy(
            buffs = listOf(
                CombatBuff(BuffType.DAMAGE_REDUCTION, 0.1, 3),
                CombatBuff(BuffType.PHYSICAL_DEFENSE_BOOST, 0.2, 3)
            ),
            affix = AffixCombatEffects(damageReduction = 0.05, defenseBonus = 0.1)
        )
        for (seed in longArrayOf(99, 2024)) {
            runCombatantDamageDiff(seed, attacker, defender)
        }
    }

    @Test
    fun `estimateDamage matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val skill = CombatSkill(
            name = "烈焰斩", skillType = SkillType.ATTACK,
            damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
            mpCost = 10, cooldown = 2, hits = 2
        )
        for (seed in longArrayOf(1, 2, 3)) {
            DiffRngBridge.nativeFromSeed(seed)  // estimateDamage 无 RNG，种子无关
            val attacker = baseCombatant("a1", "攻一")
            val defender = baseCombatant("d1", "守一")
            val kEstimate = BattleCalculator.estimateDamage(attacker, defender, skill)
            val op = buildJsonObject {
                put("op", "estimateDamage")
                put("attacker", combatantJson(attacker))
                put("defender", combatantJson(defender))
                put("skill", skillJson(skill))
            }
            val c = json.parseToJsonElement(
                DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
            ).jsonObject
            assertEquals("estimateDamage 不一致", kEstimate, c["value"]!!.jsonPrimitive.content.toInt())
        }
    }

    private fun baseCombatant(id: String, name: String): Combatant = Combatant(
        id = id, name = name,
        hp = 1000, maxHp = 1000, mp = 100, maxMp = 100,
        physicalAttack = 120, magicAttack = 100,
        physicalDefense = 60, magicDefense = 50,
        speed = 80, critRate = 0.15,
        skills = emptyList(),
        realm = 9, realmLayer = 1
    )
}
