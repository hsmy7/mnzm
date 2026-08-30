package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.BattleAI
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.model.CombatSkill
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
 * DiffBattleAITest — 统一战斗 AI 决策层跨语言差分对拍（战斗批次 B）。
 *
 * 守护目标：Kotlin `BattleAI.decideAction`（8 层级联优先级 + 概率衰减：
 * 被控检查 → Tier2 保命 → Tier3 斩杀 → Tier4 支援 → Tier5 团队 Buff →
 * Tier6 控制 → Tier7 AOE → Tier8-10 攻击决策）与 C++
 * `gamecore::battle::battle_ai.h` 在相同种子下产出**逐字段一致**的
 * AIAction（actionType/skillName/targetId）且 **RNG 消费序逐位一致**
 * （决策终态快照 == Kotlin 同种子同消费序终态快照）。
 *
 * 确定性基础：Kotlin 侧 `DeterministicRng.fromSeed(seed)`；C++ 侧
 * `nativeFromSeed(seed)` 独立同种子实例按相同短路消费序驱动。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffBattleAITest {

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

    /**
     * 单场景对拍：Kotlin BattleAI.decideAction vs C++ decideAction op。
     * 断言 actionType/skillName/targetId 一致 + RNG 决策终态快照一致
     * （消费序逐位验证）。
     */
    private fun runDecideDiff(
        seed: Long,
        unit: Combatant,
        allies: List<Combatant>,
        enemies: List<Combatant>,
        playerDamageModifier: Double = 1.0
    ) {
        val kRng = DeterministicRng.fromSeed(seed)
        DiffRngBridge.nativeFromSeed(seed)

        val kAction = BattleAI.decideAction(
            unit, allies, enemies, kRng, playerDamageModifier
        )
        val kSnapshot = kRng.snapshot()

        val op = buildJsonObject {
            put("op", "decideAction")
            put("unit", combatantJson(unit))
            putJsonArray("allies") { allies.forEach { add(combatantJson(it)) } }
            putJsonArray("enemies") { enemies.forEach { add(combatantJson(it)) } }
            put("playerDamageModifier", playerDamageModifier)
        }
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCoreBattleOp(op.toString().encodeToByteArray()).decodeToString()
        ).jsonObject
        val cSnapshot = DiffRngBridge.nativeSnapshot()

        val tag = "seed=$seed ${unit.name} -> ${kAction.actionType.name}"
        assertEquals("$tag actionType", kAction.actionType.name,
            c["actionType"]!!.jsonPrimitive.content)
        assertEquals("$tag skillName", kAction.skill?.name,
            c["skillName"]?.jsonPrimitive?.content)
        assertEquals("$tag targetId", kAction.target?.id,
            c["targetId"]?.jsonPrimitive?.content)
        assertEquals("$tag RNG 消费序终态", kSnapshot, cSnapshot)
    }

    // ── 测试场景（覆盖全部决策层级分支） ────────────────────────────

    @Test
    fun `decideAction dead unit returns NONE with zero rng consumption`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val dead = baseCombatant("a1", "亡者").copy(hp = 0)
        val enemy = baseCombatant("e1", "敌人")
        for (seed in longArrayOf(1, 42, 20260901)) {
            runDecideDiff(seed, dead, listOf(dead), listOf(enemy))
        }
    }

    @Test
    fun `decideAction no alive enemies returns NONE`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "攻一")
        val deadEnemy = baseCombatant("e1", "亡敌").copy(hp = 0)
        runDecideDiff(42, unit, listOf(unit), listOf(deadEnemy))
    }

    @Test
    fun `decideAction controlled unit returns NONE at tier1`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "被控者").copy(
            buffs = listOf(CombatBuff(BuffType.STUN, 1.0, 2))
        )
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit), listOf(enemy))
    }

    @Test
    fun `decideAction silenced unit falls through to normal attack`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "沉默者").copy(
            hp = 600,
            skills = listOf(
                CombatSkill(
                    name = "烈焰斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
                    mpCost = 10, cooldown = 2
                )
            ),
            buffs = listOf(CombatBuff(BuffType.SILENCE, 1.0, 2))
        )
        val enemy = baseCombatant("e1", "敌人")
        val enemy2 = baseCombatant("e2", "敌人二")
        runDecideDiff(42, unit, listOf(unit), listOf(enemy, enemy2))
    }

    @Test
    fun `decideAction self preserve picks shield skill at tier2`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "残血者").copy(
            hp = 200,  // 20% < 25% 触发保命
            skills = listOf(
                CombatSkill(
                    name = "护体灵光", skillType = SkillType.SUPPORT,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
                    mpCost = 10, cooldown = 3, targetScope = "self",
                    shieldPercent = 0.3, buffDuration = 3
                ),
                CombatSkill(
                    name = "烈焰斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
                    mpCost = 10, cooldown = 2
                )
            )
        )
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit), listOf(enemy))
    }

    @Test
    fun `decideAction execute kills low hp enemy at tier3`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "攻一").copy(
            skills = listOf(
                CombatSkill(
                    name = "破军斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 3.0,
                    mpCost = 20, cooldown = 3
                )
            )
        )
        val enemy = baseCombatant("e1", "残血敌").copy(hp = 200)  // 20% < 30%
        for (seed in longArrayOf(7, 42, 20260901)) {
            runDecideDiff(seed, unit, listOf(unit), listOf(enemy))
        }
    }

    @Test
    fun `decideAction ally support heals wounded ally at tier4`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "医师").copy(
            skills = listOf(
                CombatSkill(
                    name = "回春术", skillType = SkillType.SUPPORT,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
                    mpCost = 15, cooldown = 2, healPercent = 0.5,
                    healType = HealType.HP, targetScope = "ally"
                )
            )
        )
        val ally = baseCombatant("a2", "伤者").copy(hp = 300)  // 30% < 40%
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit, ally), listOf(enemy))
    }

    @Test
    fun `decideAction team buff at tier5`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "辅助").copy(
            skills = listOf(
                CombatSkill(
                    name = "疾风阵", skillType = SkillType.SUPPORT,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 0.0,
                    mpCost = 20, cooldown = 4, targetScope = "team",
                    buffType = BuffType.SPEED_BOOST, buffValue = 0.2, buffDuration = 3
                )
            )
        )
        val ally = baseCombatant("a2", "队友").copy(hp = 900)
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit, ally), listOf(enemy))
    }

    @Test
    fun `decideAction control high threat enemy at tier6`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "控场").copy(
            skills = listOf(
                CombatSkill(
                    name = "定身咒", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 0.5,
                    mpCost = 15, cooldown = 3, buffType = BuffType.STUN,
                    buffValue = 1.0, buffDuration = 2
                )
            )
        )
        val enemy1 = baseCombatant("e1", "敌人一").copy(physicalAttack = 200)
        val enemy2 = baseCombatant("e2", "敌人二").copy(physicalAttack = 100)
        runDecideDiff(42, unit, listOf(unit), listOf(enemy1, enemy2))
    }

    @Test
    fun `decideAction aoe with 3 enemies at tier7`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "群攻").copy(
            skills = listOf(
                CombatSkill(
                    name = "裂地崩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.5,
                    mpCost = 25, cooldown = 4, isAoe = true
                )
            )
        )
        val enemies = listOf(
            baseCombatant("e1", "敌人一"),
            baseCombatant("e2", "敌人二"),
            baseCombatant("e3", "敌人三")
        )
        for (seed in longArrayOf(42, 555)) {
            runDecideDiff(seed, unit, listOf(unit), enemies)
        }
    }

    @Test
    fun `decideAction mp save mode picks cheapest skill at tier8`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "省蓝者").copy(
            mp = 20,  // 20% < 30% 省蓝模式
            skills = listOf(
                CombatSkill(
                    name = "重斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.2,
                    mpCost = 10, cooldown = 2
                ),
                CombatSkill(
                    name = "烈焰斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
                    mpCost = 30, cooldown = 3
                )
            )
        )
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit), listOf(enemy))
    }

    @Test
    fun `decideAction best single attack at tier9`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "攻一").copy(
            skills = listOf(
                CombatSkill(
                    name = "重斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
                    mpCost = 10, cooldown = 2
                ),
                CombatSkill(
                    name = "烈焰斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 2.0,
                    mpCost = 20, cooldown = 3
                )
            )
        )
        val enemy = baseCombatant("e1", "敌人")
        runDecideDiff(42, unit, listOf(unit), listOf(enemy))
    }

    @Test
    fun `decideAction normal attack fallback with no skills`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val unit = baseCombatant("a1", "白板")
        val enemy = baseCombatant("e1", "敌人")
        val enemy2 = baseCombatant("e2", "敌人二")
        for (seed in longArrayOf(3, 42, 987654321)) {
            runDecideDiff(seed, unit, listOf(unit), listOf(enemy, enemy2))
        }
    }

    @Test
    fun `decideAction target selection branches match across seeds`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 两个敌人（攻高但血多 / 血少但攻低）——多种子扫描覆盖
        // 低血量/高威胁/低防御/兜底四个目标选择分支
        val unit = baseCombatant("a1", "攻一").copy(
            skills = listOf(
                CombatSkill(
                    name = "重斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.5,
                    mpCost = 10, cooldown = 2
                )
            )
        )
        val enemy1 = baseCombatant("e1", "高攻").copy(
            hp = 800, physicalAttack = 300, physicalDefense = 20
        )
        val enemy2 = baseCombatant("e2", "血少").copy(
            hp = 500, physicalAttack = 50, physicalDefense = 90
        )
        for (seed in longArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 42, 99, 555, 2024, 20260901)) {
            runDecideDiff(seed, unit, listOf(unit), listOf(enemy1, enemy2))
        }
    }

    @Test
    fun `decideAction playerDamageModifier affects execute decision`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 玩家伤害修正 > 1 时 estimateDamage 提升——同一场景下可能由
        // "不可斩杀"变为"可斩杀"，双端须一致
        val unit = baseCombatant("a1", "攻一").copy(
            skills = listOf(
                CombatSkill(
                    name = "破军斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 2.0,
                    mpCost = 20, cooldown = 3
                )
            )
        )
        val enemy = baseCombatant("e1", "残血敌").copy(hp = 300)
        for (modifier in doubleArrayOf(1.0, 1.5, 2.0)) {
            for (seed in longArrayOf(7, 42)) {
                runDecideDiff(seed, unit, listOf(unit), listOf(enemy), modifier)
            }
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
