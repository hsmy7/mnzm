package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.SpiritRoot
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.DeterministicRng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffElementalDamageTest — 五行属性伤害跨语言端到端对拍（方案 E9）。
 *
 * 守护目标：同一 Combatant 场景下，Kotlin `BattleCalculator.calculateCombatantDamage`
 * 与 C++ `gamecore::battle` 在**六值伤害类型 + 12 桶类型通道**上产出逐位一致的
 * DamageResult。覆盖维度：
 * - 类型判定：普攻六值（attacker.innateDamageType，角色配置驱动）+ 技能六值
 *   （skill.damageType）
 * - 六路类型加成：攻方桶同类型生效 / 异类型隔离
 * - 灵根 gate 折算：经 `SpiritRoot.elementGate`（唯一实现入口）折算后的攻方桶
 * - 六桶类型减伤：守方同类型减伤 + 6×6 攻防全矩阵
 * - 物理路径不回退：物理攻击不受五行减伤桶影响（E11）
 *
 * 桥面零扩展：复用 `nativeCoreBattleOp` 的 combatantDamage op 与 battle_json.h
 * 既有协议键（JSON 字段面与 [DiffBattleCalculatorTest] 同源，改协议键须两处同步）。
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffElementalDamageTest {

    private val json = Json

    // ── Combatant/Skill → JSON（与 C++ combatantFromJson/skillFromJson 字段对应；
    //    与 DiffBattleCalculatorTest 同一协议面，两处必须同步维护）──

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
        putJsonArray("buffs") { }
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
        put("attack", c.attack)
        put("defense", c.defense)
        put("innateDamageType", c.innateDamageType.name)
        put("physicalDamageBonus", c.physicalDamageBonus)
        put("metalDamageBonus", c.metalDamageBonus)
        put("woodDamageBonus", c.woodDamageBonus)
        put("waterDamageBonus", c.waterDamageBonus)
        put("fireDamageBonus", c.fireDamageBonus)
        put("earthDamageBonus", c.earthDamageBonus)
        put("physicalDamageReduction", c.physicalDamageReduction)
        put("metalDamageReduction", c.metalDamageReduction)
        put("woodDamageReduction", c.woodDamageReduction)
        put("waterDamageReduction", c.waterDamageReduction)
        put("fireDamageReduction", c.fireDamageReduction)
        put("earthDamageReduction", c.earthDamageReduction)
        put("speed", c.speed)
        put("critRate", c.critRate)
        putJsonArray("skills") { c.skills.forEach { add(skillJson(it)) } }
        putJsonArray("buffs") { }
        put("realm", c.realm)
        put("realmLayer", c.realmLayer)
        put("element", c.element)
    }

    private fun runDamageDiff(
        seed: Long,
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill?,
        scenario: String
    ) {
        val kRng = DeterministicRng.fromSeed(seed)
        DiffRngBridge.nativeFromSeed(seed)

        val kResult = BattleCalculator.withRng(kRng).calculateCombatantDamage(
            attacker, defender, skill, 1.0, null, false
        )

        val op = buildJsonObject {
            put("op", "combatantDamage")
            put("attacker", combatantJson(attacker))
            put("defender", combatantJson(defender))
            if (skill != null) put("skill", skillJson(skill))
            put("damageModifier", 1.0)
            put("enableInstantKill", false)
        }
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
        ).jsonObject

        assertEquals("$scenario damage", kResult.damage, c["damage"]!!.jsonPrimitive.content.toInt())
        assertEquals("$scenario isCrit", kResult.isCrit, c["isCrit"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$scenario isPhysical", kResult.isPhysical,
            c["isPhysical"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$scenario isDodged", kResult.isDodged,
            c["isDodged"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$scenario isInstantKill", kResult.isInstantKill,
            c["isInstantKill"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("$scenario hits", kResult.hits, c["hits"]!!.jsonPrimitive.content.toInt())
    }

    // ── 场景构造 ──────────────────────────────────────────────────

    private fun combatant(
        innateType: DamageType = DamageType.PHYSICAL,
        typeBonuses: Map<DamageType, Double> = emptyMap(),
        typeReductions: Map<DamageType, Double> = emptyMap(),
        element: String = ""
    ): Combatant = Combatant(
        id = "c", name = "对拍体", side = com.xianxia.sect.core.CombatantSide.ATTACKER,
        hp = 1000, maxHp = 1000, mp = 100, maxMp = 100,
        attack = 120, defense = 60,
        innateDamageType = innateType,
        physicalDamageBonus = typeBonuses[DamageType.PHYSICAL] ?: 0.0,
        metalDamageBonus = typeBonuses[DamageType.METAL] ?: 0.0,
        woodDamageBonus = typeBonuses[DamageType.WOOD] ?: 0.0,
        waterDamageBonus = typeBonuses[DamageType.WATER] ?: 0.0,
        fireDamageBonus = typeBonuses[DamageType.FIRE] ?: 0.0,
        earthDamageBonus = typeBonuses[DamageType.EARTH] ?: 0.0,
        physicalDamageReduction = typeReductions[DamageType.PHYSICAL] ?: 0.0,
        metalDamageReduction = typeReductions[DamageType.METAL] ?: 0.0,
        woodDamageReduction = typeReductions[DamageType.WOOD] ?: 0.0,
        waterDamageReduction = typeReductions[DamageType.WATER] ?: 0.0,
        fireDamageReduction = typeReductions[DamageType.FIRE] ?: 0.0,
        earthDamageReduction = typeReductions[DamageType.EARTH] ?: 0.0,
        speed = 80, critRate = 0.15,
        skills = emptyList(),
        element = element
    )

    private fun elementalSkill(type: DamageType) = CombatSkill(
        name = "对拍技", skillType = SkillType.ATTACK, damageType = type,
        damageMultiplier = 1.5, mpCost = 0, cooldown = 0
    )

    // ── 对拍场景 ──────────────────────────────────────────────────

    @Test
    fun `普攻类型跟随 innateDamageType 六值逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (type in DamageType.ACTIVE) {
            for (seed in longArrayOf(42, 20260901, 987654321)) {
                runDamageDiff(
                    seed,
                    attacker = combatant(innateType = type),
                    defender = combatant(),
                    skill = null,
                    scenario = "普攻${type.name} seed=$seed"
                )
            }
        }
    }

    @Test
    fun `技能类型跟随 damageType 六值逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (type in DamageType.ACTIVE) {
            for (seed in longArrayOf(7, 555)) {
                runDamageDiff(
                    seed,
                    attacker = combatant(),
                    defender = combatant(),
                    skill = elementalSkill(type),
                    scenario = "技能${type.name} seed=$seed"
                )
            }
        }
    }

    @Test
    fun `攻方类型加成同类型生效六路逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (type in DamageType.ACTIVE) {
            runDamageDiff(
                42,
                attacker = combatant(typeBonuses = mapOf(type to 0.25)),
                defender = combatant(),
                skill = elementalSkill(type),
                scenario = "加成${type.name}"
            )
        }
    }

    @Test
    fun `攻方类型加成异类型隔离逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 火加成 × 金攻击：加成不得串扰进金通道
        runDamageDiff(
            42,
            attacker = combatant(typeBonuses = mapOf(DamageType.FIRE to 0.25)),
            defender = combatant(),
            skill = elementalSkill(DamageType.METAL),
            scenario = "火加成金攻击"
        )
        // 金加成 × 物理攻击：元素加成不得串扰进物理通道
        runDamageDiff(
            42,
            attacker = combatant(typeBonuses = mapOf(DamageType.METAL to 0.25)),
            defender = combatant(),
            skill = elementalSkill(DamageType.PHYSICAL),
            scenario = "金加成物理攻击"
        )
    }

    @Test
    fun `守方六桶类型减伤同类型逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (type in DamageType.ACTIVE) {
            runDamageDiff(
                42,
                attacker = combatant(),
                defender = combatant(typeReductions = mapOf(type to 0.30)),
                skill = elementalSkill(type),
                scenario = "减伤${type.name}"
            )
        }
    }

    @Test
    fun `攻防类型六乘六全矩阵逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (attackType in DamageType.ACTIVE) {
            for (defenseType in DamageType.ACTIVE) {
                runDamageDiff(
                    42,
                    attacker = combatant(typeBonuses = mapOf(attackType to 0.20)),
                    defender = combatant(typeReductions = mapOf(defenseType to 0.30)),
                    skill = elementalSkill(attackType),
                    scenario = "攻${attackType.name}防${defenseType.name}"
                )
            }
        }
    }

    @Test
    fun `灵根 gate 折算后的攻方桶逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 生产折算路径：元素词条原始加成 × SpiritRoot.elementGate（含→全额/不含→0；
        // 物理桶不受 gate）。Combatant 携带折算后桶，双端就同一桶值对拍。
        val rawElementBonus = 0.4
        val physicalBonus = 0.2
        for (rootKey in listOf("metal", "wood", "water", "fire", "earth")) {
            val root = SpiritRoot(rootKey)
            val folded = DamageType.ACTIVE.associateWith { type ->
                when (type) {
                    DamageType.PHYSICAL -> physicalBonus
                    else -> rawElementBonus * root.elementGate(type.element)
                }
            }
            for (attackType in DamageType.ACTIVE) {
                runDamageDiff(
                    42,
                    attacker = combatant(typeBonuses = folded, element = rootKey),
                    defender = combatant(),
                    skill = elementalSkill(attackType),
                    scenario = "灵根${rootKey}攻${attackType.name}"
                )
            }
        }
    }

    @Test
    fun `物理攻击不受五行减伤桶影响逐位一致`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val elementalDefender = combatant(
            typeReductions = mapOf(DamageType.FIRE to 0.30, DamageType.WOOD to 0.30)
        )
        for (seed in longArrayOf(42, 20260901)) {
            // 普攻恒物理配置 + 物理技能双形态，均不得吃火/木减伤（E11）
            runDamageDiff(
                seed,
                attacker = combatant(innateType = DamageType.PHYSICAL),
                defender = elementalDefender,
                skill = null,
                scenario = "普攻碰元素减伤 seed=$seed"
            )
            runDamageDiff(
                seed,
                attacker = combatant(),
                defender = elementalDefender,
                skill = elementalSkill(DamageType.PHYSICAL),
                scenario = "物理技碰元素减伤 seed=$seed"
            )
        }
    }
}
