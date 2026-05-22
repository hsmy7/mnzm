package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class GameUtilsTest {

    // ========== formatNumber 测试 ==========

    @Test
    fun `formatNumber - 亿级数字`() {
        val result = GameUtils.formatNumber(1_500_000_000L)
        assertTrue(result.contains("亿"))
    }

    @Test
    fun `formatNumber - 万级数字`() {
        val result = GameUtils.formatNumber(50_000L)
        assertTrue(result.contains("万"))
    }

    @Test
    fun `formatNumber - 普通数字`() {
        val result = GameUtils.formatNumber(999L)
        assertEquals("999", result)
    }

    @Test
    fun `formatNumber - 零`() {
        val result = GameUtils.formatNumber(0L)
        assertEquals("0", result)
    }

    // ========== formatPercent 测试 ==========

    @Test
    fun `formatPercent - 正常百分比`() {
        val result = GameUtils.formatPercent(0.5)
        assertTrue(result.contains("50"))
        assertTrue(result.contains("%"))
    }

    @Test
    fun `formatPercent - 零值`() {
        val result = GameUtils.formatPercent(0.0)
        assertTrue(result.contains("0"))
    }

    @Test
    fun `formatPercent - 满值`() {
        val result = GameUtils.formatPercent(1.0)
        assertTrue(result.contains("100"))
    }

    // ========== calculateTeamAverageRealm 测试 ==========

    @Test
    fun `calculateTeamAverageRealm - 空列表返回9`() {
        assertEquals(9.0, GameUtils.calculateTeamAverageRealm(
            emptyList<DiscipleStub>(),
            { it.realm },
            { it.layer }
        ), 0.01)
    }

    @Test
    fun `calculateTeamAverageRealm - 正常计算`() {
        val disciples = listOf(
            DiscipleStub(9, 1),
            DiscipleStub(7, 5)
        )
        val result = GameUtils.calculateTeamAverageRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        val expected = (8.9 + 6.5) / 2.0
        assertEquals(expected, result, 0.01)
    }

    // ========== calculateBeastRealm 测试 ==========

    @Test
    fun `calculateBeastRealm - 空列表返回9`() {
        assertEquals(9, GameUtils.calculateBeastRealm(
            emptyList<DiscipleStub>(),
            { it.realm },
            { it.layer }
        ))
    }

    @Test
    fun `calculateBeastRealm - 全练气弟子应返回9而非8`() {
        val disciples = listOf(
            DiscipleStub(9, 9),
            DiscipleStub(9, 5),
            DiscipleStub(9, 1)
        )
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(9, result)
    }

    @Test
    fun `calculateBeastRealm - 练气9层单弟子不应映射到筑基`() {
        val disciples = listOf(DiscipleStub(9, 9))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(9, result)
    }

    @Test
    fun `calculateBeastRealm - 筑基弟子应返回8`() {
        val disciples = listOf(DiscipleStub(8, 5))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertEquals(8, result)
    }

    @Test
    fun `calculateBeastRealm - 混合境界不超过队伍最低境界`() {
        val disciples = listOf(
            DiscipleStub(9, 5),
            DiscipleStub(8, 5)
        )
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { it.layer }
        )
        assertTrue(result <= 9)
        assertTrue(result >= 8)
    }

    @Test
    fun `calculateBeastRealm - null层级视为1`() {
        val disciples = listOf(DiscipleStub(9, 1))
        val result = GameUtils.calculateBeastRealm(
            disciples,
            { it.realm },
            { _ -> null }
        )
        assertEquals(9, result)
    }

    private data class DiscipleStub(val realm: Int, val layer: Int)

    // ========== applyPriceFluctuation 测试 ==========

    @Test
    fun `applyPriceFluctuation - 价格在正负20百分比范围内`() {
        val basePrice = 100
        for (i in 1..100) {
            val result = GameUtils.applyPriceFluctuation(basePrice)
            assertTrue("价格波动应在80-120之间: $result", result >= 80 && result <= 120)
        }
    }

    @Test
    fun `applyPriceFluctuation - 最低价格为1`() {
        val result = GameUtils.applyPriceFluctuation(1)
        assertTrue(result >= 1)
    }
}
