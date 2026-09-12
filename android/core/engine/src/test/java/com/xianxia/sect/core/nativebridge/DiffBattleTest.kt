package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.BattleCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.xianxia.sect.core.util.calculateRealmGapFactors

/**
 * DiffBattleTest — 战斗计算跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::battle（乘区法最终伤害/境界压制/斩杀/闪避/护盾）与
 * Kotlin BattleCalculator 公式**逐位一致**。
 *
 * Kotlin 基准：真实 BattleCalculator（生产代码同一实现）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffBattleTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreBattleOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun zonesJson(
        damageAmplification: Double = 0.0, damageReduction: Double = 0.0,
        physiqueDamageAmplification: Double = 0.0, physiqueCritDamageBonus: Double = 0.0,
        physiqueDamageReduction: Double = 0.0, physiqueDefenseBonus: Double = 0.0,
        affixDamageAmplification: Double = 0.0, affixCritDamageBonus: Double = 0.0,
        affixDamageReduction: Double = 0.0, affixDefenseBonus: Double = 0.0,
        realmGapDamageAmplification: Double = 0.0, realmGapDamageReduction: Double = 0.0,
        majorRealmDamageAmplification: Double = 0.0, attackBuffs: Double = 0.0,
    ) = buildJsonObject {
        put("attackBuffs", attackBuffs)
        put("damageAmplification", damageAmplification)
        put("damageReduction", damageReduction)
        put("physiqueDamageAmplification", physiqueDamageAmplification)
        put("physiqueCritDamageBonus", physiqueCritDamageBonus)
        put("physiqueDamageReduction", physiqueDamageReduction)
        put("physiqueDefenseBonus", physiqueDefenseBonus)
        put("affixDamageAmplification", affixDamageAmplification)
        put("affixCritDamageBonus", affixCritDamageBonus)
        put("affixDamageReduction", affixDamageReduction)
        put("affixDefenseBonus", affixDefenseBonus)
        put("realmGapDamageAmplification", realmGapDamageAmplification)
        put("realmGapDamageReduction", realmGapDamageReduction)
        put("majorRealmDamageAmplification", majorRealmDamageAmplification)
    }

    private fun finalDamageOp(
        rawAttack: Int, defense: Int, skillMultiplier: Double = 1.0,
        zones: JsonObject = zonesJson(), isCrit: Boolean = false, variance: Double = 1.0,
    ) = buildJsonObject {
        put("op", "finalDamage"); put("rawAttack", rawAttack); put("defense", defense)
        put("skillMultiplier", skillMultiplier); put("zones", zones)
        put("isCrit", isCrit); put("variance", variance)
    }

    /** Kotlin 基准：复刻 calculateFinalDamage（无 Combatant 依赖） */
    private fun kotlinFinalDamage(
        rawAttack: Int, defense: Int, skillMultiplier: Double,
        zones: com.xianxia.sect.core.util.DamageZones, isCrit: Boolean, variance: Double,
    ): Int {
        val effectiveAttack = rawAttack * (1.0 + zones.attackBuffs)
        val effectiveDefense = defense *
            (1.0 - zones.physiqueDefenseBonus).coerceAtLeast(0.0) *
            (1.0 - zones.affixDefenseBonus).coerceAtLeast(0.0)
        val reduction = effectiveDefense / (effectiveDefense + GameConfig.Battle.DEFENSE_CONSTANT)
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction)
        val critMult = if (isCrit) 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER else 1.0
        val physiqueCritMult = if (isCrit) 1.0 + zones.physiqueCritDamageBonus else 1.0
        val affixCritMult = if (isCrit) 1.0 + zones.affixCritDamageBonus else 1.0
        return (preCritDamage * critMult * physiqueCritMult * affixCritMult
            * (1.0 + zones.damageAmplification)
            * (1.0 + zones.physiqueDamageAmplification)
            * (1.0 + zones.affixDamageAmplification)
            * (1.0 + zones.realmGapDamageAmplification)
            * (1.0 + zones.majorRealmDamageAmplification)
            * (1.0 - zones.damageReduction)
            * (1.0 - zones.physiqueDamageReduction)
            * (1.0 - zones.affixDamageReduction)
            * (1.0 - zones.realmGapDamageReduction)
            * variance
        ).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)
    }

    private fun kotlinZones(
        damageAmplification: Double = 0.0, damageReduction: Double = 0.0,
        physiqueDamageAmplification: Double = 0.0, physiqueCritDamageBonus: Double = 0.0,
        physiqueDamageReduction: Double = 0.0, physiqueDefenseBonus: Double = 0.0,
        affixDamageAmplification: Double = 0.0, affixCritDamageBonus: Double = 0.0,
        affixDamageReduction: Double = 0.0, affixDefenseBonus: Double = 0.0,
        realmGapDamageAmplification: Double = 0.0, realmGapDamageReduction: Double = 0.0,
        majorRealmDamageAmplification: Double = 0.0, attackBuffs: Double = 0.0,
    ) = com.xianxia.sect.core.util.DamageZones(
        attackBuffs = attackBuffs,
        damageAmplification = damageAmplification, damageReduction = damageReduction,
        physiqueDamageAmplification = physiqueDamageAmplification,
        physiqueCritDamageBonus = physiqueCritDamageBonus,
        physiqueDamageReduction = physiqueDamageReduction,
        physiqueDefenseBonus = physiqueDefenseBonus,
        affixDamageAmplification = affixDamageAmplification,
        affixCritDamageBonus = affixCritDamageBonus,
        affixDamageReduction = affixDamageReduction,
        affixDefenseBonus = affixDefenseBonus,
        realmGapDamageAmplification = realmGapDamageAmplification,
        realmGapDamageReduction = realmGapDamageReduction,
        majorRealmDamageAmplification = majorRealmDamageAmplification,
    )

    // ── 测试用例 ─────────────────────────────────────────────────────

    @Test
    fun `final damage no zones matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for ((atk, def) in listOf(100 to 0, 100 to 500, 100 to 1500, 50 to 50)) {
            val op = finalDamageOp(atk, def)
            val cpp = cppOp(op)
            assertEquals(
                kotlinFinalDamage(atk, def, 1.0, kotlinZones(), false, 1.0),
                cpp["value"]!!.toString().toInt()
            )
        }
    }

    @Test
    fun `final damage crit matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = finalDamageOp(100, 0, isCrit = true)
        val cpp = cppOp(op)
        assertEquals(
            kotlinFinalDamage(100, 0, 1.0, kotlinZones(), true, 1.0),
            cpp["value"]!!.toString().toInt()
        )
    }

    @Test
    fun `final damage all multipliers matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val z = zonesJson(
            damageAmplification = 0.5, damageReduction = 0.2,
            physiqueDamageAmplification = 0.1, physiqueCritDamageBonus = 0.3,
            physiqueDamageReduction = 0.1, physiqueDefenseBonus = 0.4,
            affixDamageAmplification = 0.2, affixCritDamageBonus = 0.25,
            affixDamageReduction = 0.05, affixDefenseBonus = 0.3,
            realmGapDamageAmplification = 0.3, realmGapDamageReduction = 0.15,
            majorRealmDamageAmplification = 0.4, attackBuffs = 0.2,
        )
        val op = finalDamageOp(200, 800, skillMultiplier = 1.5, zones = z, isCrit = true, variance = 1.1)
        val cpp = cppOp(op)
        assertEquals(
            kotlinFinalDamage(
                200, 800, 1.5,
                kotlinZones(
                    damageAmplification = 0.5, damageReduction = 0.2,
                    physiqueDamageAmplification = 0.1, physiqueCritDamageBonus = 0.3,
                    physiqueDamageReduction = 0.1, physiqueDefenseBonus = 0.4,
                    affixDamageAmplification = 0.2, affixCritDamageBonus = 0.25,
                    affixDamageReduction = 0.05, affixDefenseBonus = 0.3,
                    realmGapDamageAmplification = 0.3, realmGapDamageReduction = 0.15,
                    majorRealmDamageAmplification = 0.4, attackBuffs = 0.2,
                ),
                true, 1.1
            ),
            cpp["value"]!!.toString().toInt()
        )
    }

    @Test
    fun `realm gap factors match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            intArrayOf(9, 5, 9, 1),   // 攻击方高 4 层
            intArrayOf(9, 1, 9, 5),   // 防守方高 4 层
            intArrayOf(8, 1, 9, 1),   // 攻击方高 1 大境界
            intArrayOf(9, 1, 8, 1),   // 防守方高 1 大境界
            intArrayOf(0, 1, 9, 1),   // 仙人 vs 炼气
            intArrayOf(99, 1, 9, 1),  // 篡改 realm
        )
        for (c in cases) {
            val op = buildJsonObject {
                put("op", "realmGapFactors")
                put("attackerRealm", c[0]); put("attackerLayer", c[1])
                put("defenderRealm", c[2]); put("defenderLayer", c[3])
            }
            val cpp = cppOp(op)
            val kotlin = BattleCalculator.calculateRealmGapFactors(c[0], c[1], c[2], c[3])
            assertEquals("amp 不一致", kotlin.damageAmplification,
                cpp["damageAmplification"]!!.toString().toDouble(), 1e-12)
            assertEquals("reduce 不一致", kotlin.damageReduction,
                cpp["damageReduction"]!!.toString().toDouble(), 1e-12)
            assertEquals("major 不一致", kotlin.majorRealmDamageAmplification,
                cpp["majorRealmDamageAmplification"]!!.toString().toDouble(), 1e-12)
        }
    }

    @Test
    fun `instant kill matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            intArrayOf(0, 9, 1, 1),  // 仙人 vs 炼气 → 斩杀
            intArrayOf(8, 9, 1, 1),  // 筑基 vs 炼气 → 否
            intArrayOf(9, 9, 5, 1),  // 同境界 → 否
        )
        for (c in cases) {
            val op = buildJsonObject {
                put("op", "checkInstantKill")
                put("attackerRealm", c[0]); put("defenderRealm", c[1])
                put("attackerLayer", c[2]); put("defenderLayer", c[3])
            }
            val cpp = cppOp(op)
            assertEquals(
                BattleCalculator.checkInstantKill(c[0], c[1], c[2], c[3]),
                cpp["value"]!!.toString().toBoolean()
            )
        }
    }

    @Test
    fun `dodge chance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for ((a, d) in listOf(200 to 100, 300 to 100, 100 to 300, 1000 to 0)) {
            val op = buildJsonObject {
                put("op", "dodgeChance"); put("attackerSpeed", a)
                put("defenderSpeed", d); put("modifier", 0.5)
            }
            val cpp = cppOp(op)
            val expected = ((a - d).toDouble() / (a + d).coerceAtLeast(1) * 0.5)
                .coerceIn(0.0, GameConfig.Battle.MAX_DODGE_CHANCE)
            assertEquals("speed=$a/$d", expected, cpp["value"]!!.toString().toDouble(), 1e-12)
        }
    }

    @Test
    fun `shield absorption matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            intArrayOf(1000, 0, 0, 300),   // 无护盾
            intArrayOf(1000, 1, 30, 500),  // 护盾 30% vs 500 伤害
            intArrayOf(1000, 1, 30, 100),  // 护盾 30% vs 100 伤害
            intArrayOf(1000, 1, 500, 100), // 篡改 value=5.0 → 钳制
        )
        for (c in cases) {
            val op = buildJsonObject {
                put("op", "shieldAbsorption"); put("maxHp", c[0])
                put("shieldActive", c[1] == 1)
                put("shieldValue", c[2] / 100.0); put("damage", c[3])
            }
            val cpp = cppOp(op)
            val shieldActive = c[1] == 1
            val safeValue = if (shieldActive) (c[2] / 100.0).coerceIn(0.0, 1.0) else 0.0
            val shieldValue = if (shieldActive) (c[0] * safeValue).toInt().coerceAtLeast(0) else 0
            val absorbed = minOf(shieldValue, c[3])
            assertEquals("absorbed 不一致", absorbed, cpp["absorbed"]!!.toString().toInt())
            assertEquals("remainingDamage 不一致", c[3] - absorbed, cpp["remainingDamage"]!!.toString().toInt())
            assertEquals("remainingShield 不一致", (shieldValue - absorbed).coerceAtLeast(0),
                cpp["remainingShield"]!!.toString().toDouble().toInt())
        }
    }
}
