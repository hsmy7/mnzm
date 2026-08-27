package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.domain.diplomacy.IntelligentSectDecisionEngine
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.RarityTimeProgression
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffSectDiplomacyTest — 外交/宗门决策跨语言差分对拍（计划 v2 阶段 4 / 批 4-4）。
 *
 * 守护目标：C++ gamecore::system::sect_decision / sect_power / rarity_progression /
 * sect_trade 与 Kotlin IntelligentSectDecisionEngine / SectCombatPowerCalculator /
 * RarityTimeProgression / GameUtils 语义逐位一致。
 *
 * Kotlin 基准：真实 Kotlin 类（无 RNG 的纯函数直接调用；RNG 相关走
 * SYSTEM 分区或本地种子，与 C++ 同种子对齐）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSectDiplomacyTest {

    private val json = Json { encodeDefaults = true }

    private fun freshCore(seed: Long) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        DiffRngBridge.nativeCoreRngInitSeed(seed)
    }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun assertSuccess(result: JsonObject) {
        assertEquals("success", result["status"]!!.jsonPrimitive.content)
    }

    private fun dbl(el: kotlinx.serialization.json.JsonElement): Double =
        el.jsonPrimitive.content.toDouble()

    private fun int(el: kotlinx.serialization.json.JsonElement): Int =
        el.jsonPrimitive.content.toInt()

    @Test
    fun `decision chance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        data class Case(
            val profile: String,
            val kotlinProfile: com.xianxia.sect.core.engine.domain.diplomacy.DecisionProfile,
            val powerRatio: Double, val conquest: Int, val lost: Int,
            val win: Int, val loss: Int,
            val favor: SectRelationLevel, val personality: AISectPersonality?,
        )
        val cases = listOf(
            Case("attack", IntelligentSectDecisionEngine.ATTACK_PROFILE, 5.0, 2, 0, 3, 0,
                SectRelationLevel.HOSTILE, AISectPersonality.AGGRESSIVE),
            Case("attack", IntelligentSectDecisionEngine.ATTACK_PROFILE, 1.0, 0, 0, 1, 1,
                SectRelationLevel.NORMAL, null),
            Case("attack", IntelligentSectDecisionEngine.ATTACK_PROFILE, 2.5, 1, 2, 4, 2,
                SectRelationLevel.FRIENDLY, AISectPersonality.RECLUSIVE),
            Case("alliance", IntelligentSectDecisionEngine.ALLIANCE_PROFILE, 1.8, 0, 1, 2, 0,
                SectRelationLevel.INTIMATE, AISectPersonality.CONSERVATIVE),
            Case("alliance", IntelligentSectDecisionEngine.ALLIANCE_PROFILE, 5.0, 0, 0, 0, 0,
                SectRelationLevel.HOSTILE, null),
            Case("vassal", IntelligentSectDecisionEngine.VASSAL_PROFILE, 1.2, 3, 1, 5, 4,
                SectRelationLevel.NORMAL, null),
        )
        for (c in cases) {
            val kotlinChance = IntelligentSectDecisionEngine.calculateChance(
                c.kotlinProfile, c.powerRatio, c.conquest, c.lost, c.win, c.loss,
                c.favor, c.personality)
            val r = cppExec(ActionIds.SECT_DECISION_CHANCE, buildJsonObject {
                put("profile", c.profile); put("powerRatio", c.powerRatio)
                put("conquestCount", c.conquest); put("lostSectCount", c.lost)
                put("battleWinCount", c.win); put("battleLossCount", c.loss)
                put("favorLevel", c.favor.ordinal)
                put("personality", c.personality?.ordinal ?: -1)
            })
            assertSuccess(r)
            val cppChance = dbl(r["data"]!!.jsonObject.getValue("chance"))
            assertEquals("profile=${c.profile} power=${c.powerRatio}", kotlinChance, cppChance, 1e-9)
        }
    }

    @Test
    fun `breakaway chance matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        data class Case(
            val powerRatio: Double, val conquest: Int, val lost: Int,
            val win: Int, val loss: Int, val favor: SectRelationLevel,
        )
        val cases = listOf(
            Case(5.0, 0, 0, 0, 0, SectRelationLevel.INTIMATE),
            Case(2.0, 2, 3, 4, 5, SectRelationLevel.ANTAGONISTIC),
            Case(1.0, 1, 0, 0, 3, SectRelationLevel.HOSTILE),
            Case(3.0, 0, 2, 1, 0, SectRelationLevel.NORMAL),
        )
        for (c in cases) {
            val kotlinChance = IntelligentSectDecisionEngine.calculateBreakawayChance(
                c.powerRatio, c.conquest, c.lost, c.win, c.loss, c.favor)
            val r = cppExec(ActionIds.SECT_DECISION_BREAKAWAY, buildJsonObject {
                put("powerRatio", c.powerRatio)
                put("conquestCount", c.conquest); put("lostSectCount", c.lost)
                put("battleWinCount", c.win); put("battleLossCount", c.loss)
                put("favorLevel", c.favor.ordinal)
            })
            assertSuccess(r)
            assertEquals("power=${c.powerRatio}", kotlinChance,
                dbl(r["data"]!!.jsonObject.getValue("chance")), 1e-9)
        }
    }

    @Test
    fun `beast power matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        data class Case(val maxHp: Int, val patk: Int, val matk: Int, val pdef: Int, val mdef: Int, val speed: Int)
        for (c in listOf(
            Case(5000, 300, 300, 200, 200, 80),
            Case(0, 0, 0, 0, 0, 0),
            Case(-10, 50, 50, 30, 30, 5),
        )) {
            val kotlinPower = SectCombatPowerCalculator.calculateBeastCombatPower(
                c.maxHp, c.patk, c.matk, c.pdef, c.mdef, c.speed)
            val r = cppExec(ActionIds.SECT_POWER_BEAST, buildJsonObject {
                put("maxHp", c.maxHp); put("physicalAttack", c.patk); put("magicAttack", c.matk)
                put("physicalDefense", c.pdef); put("magicDefense", c.mdef); put("speed", c.speed)
            })
            assertSuccess(r)
            assertEquals("hp=${c.maxHp}", kotlinPower,
                r["data"]!!.jsonObject.getValue("power").jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun `disciple power matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val stats = DiscipleStats(
            maxHp = 1200, physicalAttack = 150, magicAttack = 90,
            physicalDefense = 80, magicDefense = 60, speed = 45)
        val kotlinPower = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        val r = cppExec(ActionIds.SECT_POWER_DISCIPLE, buildJsonObject {
            put("physicalAttack", 150); put("magicAttack", 90)
            put("maxHp", 1200); put("physicalDefense", 80)
            put("magicDefense", 60); put("speed", 45)
        })
        assertSuccess(r)
        assertEquals(kotlinPower, r["data"]!!.jsonObject.getValue("power").jsonPrimitive.content.toLong())
    }

    @Test
    fun `fingerprint matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val disciple = Disciple(
            id = "1", name = "张三", realm = 5, realmLayer = 3,
            talentIds = listOf("t1", "t2"),
            combat = CombatAttributes(
                hpVariance = 1, physicalAttackVariance = 2, magicAttackVariance = 3,
                physicalDefenseVariance = 4, magicDefenseVariance = 5, speedVariance = 6,
            ),
        )
        val aggregate = DiscipleAggregate.fromDisciple(disciple)
        val blood = BloodRefinementPctTotal(
            discipleId = "1", hpBonusPct = 0.1, physicalAttackBonusPct = 0.2,
            magicAttackBonusPct = 0.3, physicalDefenseBonusPct = 0.4,
            magicDefenseBonusPct = 0.5, speedBonusPct = 0.6)
        val kotlinFp = SectCombatPowerCalculator.computeFingerprint(aggregate, blood)
        val r = cppExec(ActionIds.SECT_POWER_FINGERPRINT, buildJsonObject {
            put("realm", 5); put("realmLayer", 3)
            put("hpVariance", 1); put("physicalAttackVariance", 2); put("magicAttackVariance", 3)
            put("physicalDefenseVariance", 4); put("magicDefenseVariance", 5); put("speedVariance", 6)
            put("talentIds", buildJsonArray { add("t1"); add("t2") })
            put("bloodPct", buildJsonObject {
                put("hpBonusPct", 0.1); put("physicalAttackBonusPct", 0.2)
                put("magicAttackBonusPct", 0.3); put("physicalDefenseBonusPct", 0.4)
                put("magicDefenseBonusPct", 0.5); put("speedBonusPct", 0.6)
            })
        })
        assertSuccess(r)
        assertEquals(kotlinFp, int(r["data"]!!.jsonObject.getValue("fingerprint")))
        // 无血炼
        val kotlinNoBlood = SectCombatPowerCalculator.computeFingerprint(aggregate, null)
        val r2 = cppExec(ActionIds.SECT_POWER_FINGERPRINT, buildJsonObject {
            put("realm", 5); put("realmLayer", 3)
            put("hpVariance", 1); put("physicalAttackVariance", 2); put("magicAttackVariance", 3)
            put("physicalDefenseVariance", 4); put("magicDefenseVariance", 5); put("speedVariance", 6)
            put("talentIds", buildJsonArray { add("t1"); add("t2") })
        })
        assertSuccess(r2)
        assertEquals(kotlinNoBlood, int(r2["data"]!!.jsonObject.getValue("fingerprint")))
    }

    @Test
    fun `rarity max pity weights roll match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for (year in listOf(1, 19, 20, 60, 79, 80, 300, 500, 1500, 3000, 3500, 4000)) {
            freshCore(42)
            assertEquals("max $year", RarityTimeProgression.maxRarityForYear(year),
                int(cppExec(ActionIds.SECT_RARITY_MAX, buildJsonObject { put("year", year) })
                    ["data"]!!.jsonObject.getValue("max")))
            assertEquals("pity $year", RarityTimeProgression.pityRarityForYear(year),
                int(cppExec(ActionIds.SECT_RARITY_PITY, buildJsonObject { put("year", year) })
                    ["data"]!!.jsonObject.getValue("pity")))
            // 权重表
            val kotlinWeights = RarityTimeProgression.rarityWeightsForYear(year)
            val rw = cppExec(ActionIds.SECT_RARITY_WEIGHTS, buildJsonObject { put("year", year) })
            assertSuccess(rw)
            val cw = rw["data"]!!.jsonObject.getValue("weights").jsonObject
            assertEquals("weights.size $year", kotlinWeights.size, cw.size)
            for ((rarity, prob) in kotlinWeights) {
                assertEquals("w[$year][$rarity]", prob,
                    dbl(cw.getValue(rarity.toString())), 1e-9)
            }
        }
        // rollRarity：同种子 SYSTEM 分区逐位一致
        for ((seed, year) in listOf(42L to 50, 7L to 200, 99L to 1000)) {
            freshCore(seed)
            val kotlinRoll = RarityTimeProgression.rollRarity(
                GameRngManager().also { it.initSystemSeed(seed) }.getRng(RngPartition.SYSTEM), year)
            val rr = cppExec(ActionIds.SECT_RARITY_ROLL, buildJsonObject { put("year", year) })
            assertSuccess(rr)
            assertEquals("roll seed=$seed year=$year", kotlinRoll,
                int(rr["data"]!!.jsonObject.getValue("rarity")))
        }
    }

    @Test
    fun `trade seed and price match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        // 确定性种子：sectId.hashCode() + year
        for ((sectId, year) in listOf("ai-1" to 50, "万剑宗" to 100, "sect-xyz" to 3)) {
            val kotlinSeed = sectId.hashCode().toLong() + year
            val rs = cppExec(ActionIds.SECT_TRADE_SEED, buildJsonObject {
                put("sectId", sectId); put("year", year)
            })
            assertSuccess(rs)
            assertEquals("seed $sectId/$year", kotlinSeed,
                rs["data"]!!.jsonObject.getValue("seed").jsonPrimitive.content.toLong())
        }
        // 价格波动：同本地种子 RNG 逐位一致
        for ((seed, basePrice) in listOf(12345L to 10000L, 999L to 500L, 7L to 100000L)) {
            val kotlinPrice = GameUtils.applyPriceFluctuation(
                basePrice, DeterministicRng.fromSeed(seed).asKotlinRandom())
            val rp = cppExec(ActionIds.SECT_TRADE_PRICE, buildJsonObject {
                put("seed", seed); put("basePrice", basePrice)
            })
            assertSuccess(rp)
            assertEquals("price seed=$seed", kotlinPrice,
                rp["data"]!!.jsonObject.getValue("price").jsonPrimitive.content.toLong())
        }
    }
}
