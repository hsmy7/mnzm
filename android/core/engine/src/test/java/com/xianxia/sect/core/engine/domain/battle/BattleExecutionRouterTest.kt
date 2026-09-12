package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BattleExecutionRouterTest — 战斗执行路由守卫。
 *
 * 守护契约：
 * - 降级契约：flag 非 AUTHORITATIVE → null（调用方回退 Kotlin）；生产桥未
 *   加载（JVM 测试环境无 .so）→ null——两条回退路径零副作用
 * - JSON 编解码：Combatant ↔ JSON 往返无损 + 字段键与 C++ battle_json.h
 *   对齐（生产通道协议稳定性——字段漂移会致跨语言解析错位）
 *
 * 等价性：AUTHORITATIVE + 生产桥已加载路径的 C++ 执行语义由
 * DiffBattleExecutionTest（桌面对拍桥，同源 battle_execution.h）逐位守护。
 */
class BattleExecutionRouterTest {

    private val json = Json

    @Test
    fun `tryExecuteNative returns null when flag is OFF`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val battle = Battle(team = emptyList(), beasts = emptyList())
            assertNull(BattleExecutionRouter.tryExecuteNative(battle))
        }
    }

    @Test
    fun `tryExecuteNative returns null when native bridge not loaded`() {
        // JVM 测试环境未加载生产 .so（GameCoreBridge.isLoaded=false）→ 回退
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val battle = Battle(team = emptyList(), beasts = emptyList())
            assertNull(BattleExecutionRouter.tryExecuteNative(battle))
        }
    }

    @Test
    fun `combatant JSON roundtrip preserves all fields`() {
        val original = baseCombatant("d1", "剑修").copy(
            side = CombatantSide.DEFENDER,
            skills = listOf(
                CombatSkill(
                    name = "烈焰斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 2.2,
                    mpCost = 18, cooldown = 3, hits = 2,
                    buffType = BuffType.BURN, buffValue = 0.1, buffDuration = 2,
                    buffs = listOf(Triple(BuffType.SPEED_REDUCE, 0.2, 2))
                )
            ),
            buffs = listOf(
                CombatBuff(BuffType.PHYSICAL_ATTACK_BOOST, 0.3, 3),
                CombatBuff(BuffType.SHIELD, 0.25, 2)
            )
        )
        val restored = roundtrip(original)
        assertCombatantScalars(original, restored)
        assertBuffsEqual(original.buffs, restored.buffs)
        assertSkillsEqual(original.skills, restored.skills)
    }

    private fun roundtrip(c: Combatant): Combatant {
        val j = BattleJsonCodec.combatantJson(c)
        return BattleJsonCodec.combatantFromJson(
            json.parseToJsonElement(j.toString()).jsonObject
        )
    }

    private fun assertCombatantScalars(a: Combatant, b: Combatant) {
        assertEquals(a.id, b.id)
        assertEquals(a.name, b.name)
        assertEquals(a.side, b.side)
        assertEquals(a.hp, b.hp)
        assertEquals(a.maxHp, b.maxHp)
        assertEquals(a.mp, b.mp)
        assertEquals(a.maxMp, b.maxMp)
        assertEquals(a.physicalAttack, b.physicalAttack)
        assertEquals(a.magicAttack, b.magicAttack)
        assertEquals(a.physicalDefense, b.physicalDefense)
        assertEquals(a.magicDefense, b.magicDefense)
        assertEquals(a.speed, b.speed)
        assertEquals(a.critRate, b.critRate, 0.0)
        assertEquals(a.realm, b.realm)
        assertEquals(a.realmLayer, b.realmLayer)
        assertEquals(a.element, b.element)
    }

    private fun assertBuffsEqual(a: List<CombatBuff>, b: List<CombatBuff>) {
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.type, y.type)
            assertEquals(x.value, y.value, 0.0)
            assertEquals(x.remainingDuration, y.remainingDuration)
        }
    }

    private fun assertSkillsEqual(a: List<CombatSkill>, b: List<CombatSkill>) {
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.name, y.name)
            assertEquals(x.skillType, y.skillType)
            assertEquals(x.damageType, y.damageType)
            assertEquals(x.damageMultiplier, y.damageMultiplier, 0.0)
            assertEquals(x.mpCost, y.mpCost)
            assertEquals(x.cooldown, y.cooldown)
            assertEquals(x.hits, y.hits)
            assertEquals(x.buffType, y.buffType)
            assertEquals(x.buffValue, y.buffValue, 0.0)
            assertEquals(x.buffDuration, y.buffDuration)
            assertEquals(x.currentCooldown, y.currentCooldown)
            assertEquals(x.isAoe, y.isAoe)
            assertEquals(x.targetScope, y.targetScope)
            assertEquals(x.buffs, y.buffs)
        }
    }

    @Test
    fun `combatant JSON keys align with battle_json protocol`() {
        // 协议稳定性：生产通道序列化必须输出 C++ battle_json.h 解析所需的全部键
        val c = baseCombatant("d1", "剑修").copy(
            skills = listOf(
                CombatSkill(
                    name = "重斩", skillType = SkillType.ATTACK,
                    damageType = DamageType.PHYSICAL, damageMultiplier = 1.8,
                    mpCost = 10, cooldown = 2
                )
            )
        )
        val j = BattleJsonCodec.combatantJson(c)
        val keys = j.keys
        val required = setOf(
            "id", "name", "side", "hp", "maxHp", "mp", "maxMp",
            "physicalAttack", "magicAttack", "physicalDefense", "magicDefense",
            "speed", "critRate", "skills", "buffs", "realm", "realmLayer",
            "element", "physique", "affix"
        )
        assertTrue("缺少协议键: ${required - keys}", required.all { it in keys })
        // skills/buffs 子键（buffType 为可选键——null 不输出，C++ 解析 contains 检查）
        val skillKeys = (j["skills"] as kotlinx.serialization.json.JsonArray)
            .first().jsonObject.keys
        val skillRequired = setOf(
            "name", "skillType", "damageType", "damageMultiplier", "mpCost",
            "cooldown", "hits", "healPercent", "healFixed", "healType",
            "buffValue", "buffDuration", "buffs", "currentCooldown",
            "isAoe", "targetScope", "shieldPercent", "turnAdvancePercent",
            "damageSharePercent", "damageLinkPercent"
        )
        assertTrue("缺少技能协议键: ${skillRequired - skillKeys}", skillRequired.all { it in skillKeys })
    }

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
}
