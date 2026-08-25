package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDiscipleTest — 弟子属性计算跨语言差分对拍（批次 5 验收核心）。
 *
 * 守护目标：C++ gamecore::disciple（基础属性乘区法/修炼乘区/突破乘区/寿命惩罚/
 * 师徒/父母加成）与 Kotlin DiscipleStatCalculator 公式**逐位一致**。
 *
 * Kotlin 基准：真实 DiscipleStatCalculator（生产代码同一实现）。
 * 流程：同一参数 JSON 分别在 Kotlin 真实计算与 C++（JNI discipleOp）上执行，
 * 比较结果逐字段一致。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffDiscipleTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreDiscipleOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun d(v: Double) = JsonPrimitive(v)

    // ── 基础属性乘区法 ─────────────────────────────────────────────

    private fun baseStatsOp(
        realm: Int = 9, realmLayer: Int = 1, hpVariance: Int = 0, speedVariance: Int = 0,
        effects: Map<String, Double> = emptyMap(), bloodHpBonusPct: Double = 0.0,
    ) = buildJsonObject {
        put("op", "baseStats"); put("realm", realm); put("realmLayer", realmLayer)
        put("hpVariance", hpVariance); put("speedVariance", speedVariance)
        put("effects", buildJsonObject { effects.forEach { (k, v) -> put(k, v) } })
        put("bloodHpBonusPct", bloodHpBonusPct)
    }

    @Test
    fun `base stats lianqi matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = baseStatsOp()
        val cpp = cppOp(op)
        val rc = GameConfig.Realm.get(9)
        assertEquals(rc.baseHp, cpp["maxHp"]!!.toString().toInt())
        assertEquals(rc.baseMp, cpp["maxMp"]!!.toString().toInt())
        assertEquals(rc.baseSpeed, cpp["speed"]!!.toString().toInt())
        assertEquals(0.05, cpp["critRate"]!!.toString().toDouble(), 1e-12)
    }

    @Test
    fun `base stats with effects matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = baseStatsOp(
            realm = 7, realmLayer = 3,
            effects = mapOf("maxHp" to 0.5, "physicalAttack" to 1.0, "critRate" to 0.10),
            bloodHpBonusPct = 0.3,
        )
        val cpp = cppOp(op)
        val rc = GameConfig.Realm.get(7)
        val layerMult = 1.0 + (3 - 1) * 0.1
        assertEquals(kotlin.math.round(rc.baseHp * layerMult * 1.8).toInt(), cpp["maxHp"]!!.toString().toInt())
        assertEquals(kotlin.math.round(rc.basePhysicalAttack * layerMult * 2.0).toInt(), cpp["physicalAttack"]!!.toString().toInt())
        assertEquals(0.15, cpp["critRate"]!!.toString().toDouble(), 1e-12)
    }

    @Test
    fun `base stats variance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = baseStatsOp(hpVariance = 100, speedVariance = -50)
        val cpp = cppOp(op)
        val rc = GameConfig.Realm.get(9)
        assertEquals(kotlin.math.round(rc.baseHp * 2.0).toInt(), cpp["maxHp"]!!.toString().toInt())
        assertEquals(kotlin.math.round(rc.baseSpeed * 0.5).toInt(), cpp["speed"]!!.toString().toInt())
    }

    // ── 修炼速度乘区 ───────────────────────────────────────────────

    private fun cultOp(
        realm: Int = 9, rootCount: Int = 1, aptitudeBonus: Double = 0.0,
        resourceBonus: Double = 0.0, socialBonus: Double = 0.0,
        statusBonus: Double = 0.0, temporaryBonus: Double = 0.0,
    ) = buildJsonObject {
        put("op", "cultivationPerPhase"); put("realm", realm); put("rootCount", rootCount)
        put("aptitudeBonus", aptitudeBonus); put("resourceBonus", resourceBonus)
        put("socialBonus", socialBonus); put("statusBonus", statusBonus)
        put("temporaryBonus", temporaryBonus)
    }

    @Test
    fun `cultivation per phase matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = cultOp(
            realm = 8, rootCount = 2, aptitudeBonus = 0.2, resourceBonus = 0.5,
            socialBonus = 0.1, statusBonus = -0.1, temporaryBonus = 0.3,
        )
        val cpp = cppOp(op)
        val zones = com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.CultivationSpeedZones(
            aptitudeBonus = 0.2, resourceBonus = 0.5, socialBonus = 0.1,
            statusBonus = -0.1, temporaryBonus = 0.3,
        )
        val expected = DiscipleStatCalculator.calculateCultivationPerPhase(8, 2, zones)
        assertEquals(expected, cpp["value"]!!.toString().toDouble(), 1e-12)
    }

    @Test
    fun `cultivation min clamp matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = cultOp(resourceBonus = -0.99)
        val cpp = cppOp(op)
        assertEquals(1.0, cpp["value"]!!.toString().toDouble(), 1e-12)
    }

    // ── 突破概率 ───────────────────────────────────────────────────

    @Test
    fun `breakthrough chance table matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "breakthroughChance"); put("realm", 9); put("rootCount", 5); put("realmLayer", 1)
        }
        val cpp = cppOp(op)
        assertEquals(
            GameConfig.Realm.getBreakthroughChance(9, 5, 1),
            cpp["value"]!!.toString().toDouble(), 1e-12
        )
    }

    @Test
    fun `breakthrough chance layer interpolation matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "breakthroughChance"); put("realm", 9); put("rootCount", 1); put("realmLayer", 5)
        }
        val cpp = cppOp(op)
        assertEquals(
            GameConfig.Realm.getBreakthroughChance(9, 1, 5),
            cpp["value"]!!.toString().toDouble(), 1e-12
        )
    }

    @Test
    fun `breakthrough chance zones matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "breakthroughChanceZones"); put("baseZone", 0.5)
            put("elderGuidance", 0.1); put("selfBonus", 0.05)
            put("statusPenalty", 0.1); put("adFlatBonus", 0.0)
        }
        val cpp = cppOp(op)
        val zones = DiscipleStatCalculator.BreakthroughZones(
            baseZone = 0.5, elderGuidance = 0.1, selfBonus = 0.05,
            statusPenalty = 0.1, adFlatBonus = 0.0,
        )
        assertEquals(
            DiscipleStatCalculator.calculateBreakthroughChance(zones),
            cpp["value"]!!.toString().toDouble(), 1e-12
        )
    }

    // ── 寿命/师徒/父母/魂力/资质 ───────────────────────────────────

    @Test
    fun `lifespan penalties match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (age in listOf(40, 60, 72, 76, 80)) {
            val remaining = cppOp(buildJsonObject {
                put("op", "lifespanRemainingPercent"); put("age", age); put("lifespan", 80)
            })
            assertEquals(
                DiscipleStatCalculator.calculateLifespanRemainingPercent(age, 80),
                remaining["value"]!!.toString().toDouble(), 1e-12
            )
            val cultPenalty = cppOp(buildJsonObject {
                put("op", "lifespanCultivationPenalty"); put("age", age); put("lifespan", 80)
            })
            assertEquals(
                DiscipleStatCalculator.calculateLifespanCultivationPenalty(age, 80),
                cultPenalty["value"]!!.toString().toDouble(), 1e-12
            )
            val breakPenalty = cppOp(buildJsonObject {
                put("op", "lifespanBreakthroughPenalty"); put("age", age); put("lifespan", 80)
            })
            assertEquals(
                DiscipleStatCalculator.calculateLifespanBreakthroughPenalty(age, 80),
                breakPenalty["value"]!!.toString().toDouble(), 1e-12
            )
        }
    }

    @Test
    fun `master disciple bonuses match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (pair in listOf(9 to 7, 9 to 0, 9 to 9, 7 to 9)) {
            val op = buildJsonObject {
                put("op", "masterDiscipleBonus")
                put("discipleRealm", pair.first); put("masterRealm", pair.second)
            }
            val cpp = cppOp(op)
            assertEquals(
                DiscipleStatCalculator.getMasterDiscipleRealmGap(pair.first, pair.second),
                cpp["gap"]!!.toString().toInt()
            )
            assertEquals(
                DiscipleStatCalculator.getMasterDiscipleCultivationBonus(pair.first, pair.second),
                cpp["cultivationBonus"]!!.toString().toDouble(), 1e-12
            )
            assertEquals(
                DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(pair.first, pair.second),
                cpp["breakthroughBonus"]!!.toString().toDouble(), 1e-12
            )
        }
    }

    @Test
    fun `parent spirit root bonus matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (rootCount in 1..5) {
            val op = buildJsonObject {
                put("op", "parentSpiritRootBonus"); put("rootCount", rootCount)
            }
            val cpp = cppOp(op)
            assertEquals(
                DiscipleStatCalculator.getParentSpiritRootBonus(rootCount),
                cpp["value"]!!.toString().toDouble(), 1e-12
            )
        }
    }

    @Test
    fun `soul power bonus matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (soulPower in listOf(0, 20, 40, 100, 500)) {
            val op = buildJsonObject {
                put("op", "soulPowerBreakthroughBonus"); put("soulPower", soulPower)
            }
            val cpp = cppOp(op)
            assertEquals(
                DiscipleStatCalculator.getSoulPowerBreakthroughBonus(soulPower),
                cpp["value"]!!.toString().toDouble(), 1e-12
            )
        }
    }

    @Test
    fun `aptitude bonus matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (aptitude in listOf(50, 80, 90, 120, 10000)) {
            val op = buildJsonObject {
                put("op", "aptitudeCultivationBonus"); put("aptitude", aptitude)
            }
            val cpp = cppOp(op)
            // aptitudeCultivationBonus 是 private——用公开等价验证：资质 80 基准每点 +1% 上限 40%
            val expected = ((aptitude - 80).coerceAtLeast(0) * 0.01).coerceAtMost(0.40)
            assertEquals(expected, cpp["value"]!!.toString().toDouble(), 1e-12)
        }
    }
}
