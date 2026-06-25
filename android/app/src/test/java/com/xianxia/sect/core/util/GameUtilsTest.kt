package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GameUtilsTest {

    // ============================================================
    // SectRelationLevel.fromFavor
    // ============================================================

    @Test
    fun fromFavor_0_returnsHOSTILE() {
        assertEquals(SectRelationLevel.HOSTILE, SectRelationLevel.fromFavor(0))
    }

    @Test
    fun fromFavor_5_returnsHOSTILE() {
        assertEquals(SectRelationLevel.HOSTILE, SectRelationLevel.fromFavor(5))
    }

    @Test
    fun fromFavor_9_returnsHOSTILE() {
        assertEquals(SectRelationLevel.HOSTILE, SectRelationLevel.fromFavor(9))
    }

    @Test
    fun fromFavor_10_returnsANTAGONISTIC() {
        assertEquals(SectRelationLevel.ANTAGONISTIC, SectRelationLevel.fromFavor(10))
    }

    @Test
    fun fromFavor_25_returnsANTAGONISTIC() {
        assertEquals(SectRelationLevel.ANTAGONISTIC, SectRelationLevel.fromFavor(25))
    }

    @Test
    fun fromFavor_39_returnsANTAGONISTIC() {
        assertEquals(SectRelationLevel.ANTAGONISTIC, SectRelationLevel.fromFavor(39))
    }

    @Test
    fun fromFavor_40_returnsNORMAL() {
        assertEquals(SectRelationLevel.NORMAL, SectRelationLevel.fromFavor(40))
    }

    @Test
    fun fromFavor_50_returnsNORMAL() {
        assertEquals(SectRelationLevel.NORMAL, SectRelationLevel.fromFavor(50))
    }

    @Test
    fun fromFavor_59_returnsNORMAL() {
        assertEquals(SectRelationLevel.NORMAL, SectRelationLevel.fromFavor(59))
    }

    @Test
    fun fromFavor_60_returnsFRIENDLY() {
        assertEquals(SectRelationLevel.FRIENDLY, SectRelationLevel.fromFavor(60))
    }

    @Test
    fun fromFavor_70_returnsFRIENDLY() {
        assertEquals(SectRelationLevel.FRIENDLY, SectRelationLevel.fromFavor(70))
    }

    @Test
    fun fromFavor_79_returnsFRIENDLY() {
        assertEquals(SectRelationLevel.FRIENDLY, SectRelationLevel.fromFavor(79))
    }

    @Test
    fun fromFavor_80_returnsINTIMATE() {
        assertEquals(SectRelationLevel.INTIMATE, SectRelationLevel.fromFavor(80))
    }

    @Test
    fun fromFavor_100_returnsINTIMATE() {
        assertEquals(SectRelationLevel.INTIMATE, SectRelationLevel.fromFavor(100))
    }

    @Test
    fun fromFavor_negative1_returnsHOSTILE() {
        assertEquals(SectRelationLevel.HOSTILE, SectRelationLevel.fromFavor(-1))
    }

    @Test
    fun fromFavor_101_returnsHOSTILE() {
        assertEquals(SectRelationLevel.HOSTILE, SectRelationLevel.fromFavor(101))
    }

    // ============================================================
    // SectRelationLevel.maxAllowedRarity
    // ============================================================

    @Test
    fun hostile_maxAllowedRarity_is0() {
        assertEquals(0, SectRelationLevel.HOSTILE.maxAllowedRarity)
    }

    @Test
    fun antagonistic_maxAllowedRarity_is0() {
        assertEquals(0, SectRelationLevel.ANTAGONISTIC.maxAllowedRarity)
    }

    @Test
    fun normal_maxAllowedRarity_is2() {
        assertEquals(2, SectRelationLevel.NORMAL.maxAllowedRarity)
    }

    @Test
    fun friendly_maxAllowedRarity_is4() {
        assertEquals(4, SectRelationLevel.FRIENDLY.maxAllowedRarity)
    }

    @Test
    fun intimate_maxAllowedRarity_is6() {
        assertEquals(6, SectRelationLevel.INTIMATE.maxAllowedRarity)
    }

    // ============================================================
    // calculateTeamAverageRealm
    // ============================================================

    @Test
    fun calculateTeamAverageRealm_emptyList_returns9() {
        val result = GameUtils.calculateTeamAverageRealm(
            emptyList<Any>(),
            { 0 },
            { null }
        )
        assertEquals(9.0, result, 0.001)
    }

    @Test
    fun calculateTeamAverageRealm_singleDisciple_realm5_layer1() {
        data class Disc(val realm: Int, val layer: Int?)

        val disciples = listOf(Disc(5, 1))
        val result = GameUtils.calculateTeamAverageRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        // realm - layer/10.0 = 5 - 0.1 = 4.9
        assertEquals(4.9, result, 0.001)
    }

    @Test
    fun calculateTeamAverageRealm_multipleDisciples_returnsCorrectAverage() {
        data class Disc(val realm: Int, val layer: Int?)

        val disciples = listOf(
            Disc(5, 1),  // 5 - 0.1 = 4.9
            Disc(3, 5)   // 3 - 0.5 = 2.5
        )
        val result = GameUtils.calculateTeamAverageRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        // (4.9 + 2.5) / 2 = 3.7
        assertEquals(3.7, result, 0.001)
    }

    @Test
    fun calculateTeamAverageRealm_nullLayer_defaultsTo1() {
        data class Disc(val realm: Int, val layer: Int?)

        val disciples = listOf(Disc(5, null))
        val result = GameUtils.calculateTeamAverageRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        // null layer -> 1, so 5 - 1/10.0 = 4.9
        assertEquals(4.9, result, 0.001)
    }

    // ============================================================
    // calculateBeastRealm
    // ============================================================

    @Test
    fun calculateBeastRealm_emptyList_returns9() {
        val result = GameUtils.calculateBeastRealm(
            emptyList<Any>(),
            { 0 },
            { null }
        )
        assertEquals(9, result)
    }

    @Test
    fun calculateBeastRealm_singleDisciple_realm5() {
        data class Disc(val realm: Int, val layer: Int?)

        val disciples = listOf(Disc(5, 1))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        // avg = 4.9, ceil = 5, maxRealm = 5, min(5,5) = 5
        assertEquals(5, result)
    }

    @Test
    fun calculateBeastRealm_doesNotExceedTeamMaxRealm() {
        data class Disc(val realm: Int, val layer: Int?)

        // Low realm disciples, so avg is low, but test that coerceAtMost works
        val disciples = listOf(
            Disc(3, 1),  // 3 - 0.1 = 2.9
            Disc(3, 1)   // 3 - 0.1 = 2.9
        )
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        // avg = 2.9, ceil = 3, maxRealm = 3, min(3,3) = 3
        assertEquals(3, result)
    }

    // ============================================================
    // formatNumber
    // ============================================================

    @Test
    fun formatNumber_999_returns999() {
        assertEquals("999", GameUtils.formatNumber(999))
    }

    @Test
    fun formatNumber_9999_returns9999() {
        assertEquals("9999", GameUtils.formatNumber(9999L))
    }

    @Test
    fun formatNumber_10000_returns1wan() {
        assertEquals("1万", GameUtils.formatNumber(10000L))
    }

    @Test
    fun formatNumber_10001_returns1wan() {
        assertEquals("1万", GameUtils.formatNumber(10001L))
    }

    @Test
    fun formatNumber_10999_returns1wan() {
        assertEquals("1万", GameUtils.formatNumber(10999L))
    }

    @Test
    fun formatNumber_11999_returns1_1wan() {
        assertEquals("1.1万", GameUtils.formatNumber(11999L))
    }

    @Test
    fun formatNumber_19999_returns1_9wan() {
        assertEquals("1.9万", GameUtils.formatNumber(19999L))
    }

    @Test
    fun formatNumber_19000_returns1_9wan() {
        assertEquals("1.9万", GameUtils.formatNumber(19000L))
    }

    @Test
    fun formatNumber_99999_returns9_9wan() {
        assertEquals("9.9万", GameUtils.formatNumber(99999L))
    }

    @Test
    fun formatNumber_int_overload() {
        assertEquals("1万", GameUtils.formatNumber(10001))
    }

    @Test
    fun formatNumber_1Billion_returns10yi() {
        assertEquals("10亿", GameUtils.formatNumber(1_000_000_000L))
    }

    @Test
    fun formatNumber_1_0000_0001_returns1yi() {
        assertEquals("1亿", GameUtils.formatNumber(1_0000_0001L))
    }

    @Test
    fun formatNumber_1_9999_9999_returns1_9yi() {
        assertEquals("1.9亿", GameUtils.formatNumber(1_9999_9999L))
    }

    // ============================================================
    // formatPercent
    // ============================================================

    @Test
    fun formatPercent_0_5_contains50() {
        val result = GameUtils.formatPercent(0.5)
        assertTrue("Expected 50 in result: $result", result.contains("50"))
    }

    // ============================================================
    // applyPriceFluctuation (Int)
    // ============================================================

    @Test
    fun applyPriceFluctuation_int_resultAtLeast1() {
        val random = Random(42)
        val result = GameUtils.applyPriceFluctuation(0, random)
        assertTrue("Expected result >= 1, got $result", result >= 1)
    }

    @Test
    fun applyPriceFluctuation_long_resultAtLeast1L() {
        val random = Random(42)
        val result = GameUtils.applyPriceFluctuation(0L, random)
        assertTrue("Expected result >= 1L, got $result", result >= 1L)
    }

    @Test
    fun applyPriceFluctuation_fixedSeed_deterministic() {
        val result1 = GameUtils.applyPriceFluctuation(100, Random(123))
        val result2 = GameUtils.applyPriceFluctuation(100, Random(123))
        assertEquals(result1, result2)
    }

    // ============================================================
    // getSectRelation
    // ============================================================

    @Test
    fun getSectRelation_noPlayerSect_returns0() {
        val sects = listOf(WorldSect(id = "s1", isPlayerSect = false))
        val relations = listOf(SectRelation(sectId1 = "s1", sectId2 = "s2", favor = 50))
        assertEquals(0, GameUtils.getSectRelation(sects, relations, "s2"))
    }

    @Test
    fun getSectRelation_relationExists_returnsFavor() {
        val sects = listOf(WorldSect(id = "player", isPlayerSect = true))
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 75))
        assertEquals(75, GameUtils.getSectRelation(sects, relations, "target"))
    }

    @Test
    fun getSectRelation_noRelationFound_returns0() {
        val sects = listOf(WorldSect(id = "player", isPlayerSect = true))
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "other", favor = 50))
        assertEquals(0, GameUtils.getSectRelation(sects, relations, "target"))
    }

    // ============================================================
    // calculateSectTradePriceMultiplier
    // ============================================================

    @Test
    fun calculateSectTradePriceMultiplier_neutralRelation_returns1() {
        val sects = listOf(WorldSect(id = "player", isPlayerSect = true))
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 50))
        val alliances = emptyList<Alliance>()
        assertEquals(1.0, GameUtils.calculateSectTradePriceMultiplier(sects, relations, alliances, "target"), 0.001)
    }

    @Test
    fun calculateSectTradePriceMultiplier_highRelation_returnsDiscount() {
        val sects = listOf(WorldSect(id = "player", isPlayerSect = true))
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 80))
        val alliances = emptyList<Alliance>()
        val multiplier = GameUtils.calculateSectTradePriceMultiplier(sects, relations, alliances, "target")
        // favor=80, relation >= 70: 1.0 - (80-70)*0.01 = 0.9
        assertTrue("Expected multiplier < 1.0 for high relation, got $multiplier", multiplier < 1.0)
    }

    @Test
    fun calculateSectTradePriceMultiplier_ally_returnsBiggerDiscount() {
        val sects = listOf(WorldSect(id = "player", isPlayerSect = true))
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 80))
        val alliances = listOf(Alliance(sectIds = listOf("player", "target")))
        val multiplier = GameUtils.calculateSectTradePriceMultiplier(sects, relations, alliances, "target")
        // isAlly, favor=80: 0.9 * (1.0 - max(0, 80-70)*0.01) = 0.9 * 0.9 = 0.81
        assertTrue("Expected bigger discount for ally, got $multiplier", multiplier < 0.9)
    }
}
