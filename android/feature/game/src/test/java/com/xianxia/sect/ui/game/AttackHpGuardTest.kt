package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AttackHpGuard（进攻前低血量二次确认判定）纯函数测试。
 *
 * 口径与引擎/详情页一致：用含血炼 finalStats 判定；currentHp<0 为满血哨兵；
 * maxHp<=0 视为满血（与 HpMpBars 语义对齐）。
 */
class AttackHpGuardTest {

    private fun makeDisciple(
        id: String,
        currentHp: Int = -1,
        realm: Int = 9,
        realmLayer: Int = 1,
        hpVariance: Int = 0
    ): DiscipleAggregate = DiscipleAggregate.fromDisciple(
        Disciple(
            id = id,
            name = "弟子$id",
            realm = realm,
            realmLayer = realmLayer,
            combat = CombatAttributes(hpVariance = hpVariance, currentHp = currentHp)
        )
    )

    /** 基础（无血炼、无装备功法）maxHp */
    private fun baseMaxHp(realm: Int = 9, realmLayer: Int = 1): Int =
        DiscipleStatCalculator.getFinalStats(
            Disciple(id = "probe", name = "探测", realm = realm, realmLayer = realmLayer),
            emptyMap(),
            emptyMap()
        ).maxHp

    // ==================== discipleHpFraction ====================

    @Test
    fun `discipleHpFraction - 满血哨兵 -1 返回 1f`() {
        val d = makeDisciple("1")
        assertEquals(1f, discipleHpFraction(d, emptyMap(), emptyMap()), 0.0001f)
    }

    @Test
    fun `discipleHpFraction - 当前血量等于 maxHp 返回 1f`() {
        val d = makeDisciple("1", currentHp = baseMaxHp())
        assertEquals(1f, discipleHpFraction(d, emptyMap(), emptyMap()), 0.0001f)
    }

    @Test
    fun `discipleHpFraction - 半血返回约0点5`() {
        val maxHp = baseMaxHp()
        val d = makeDisciple("1", currentHp = maxHp / 2)
        assertEquals(0.5f, discipleHpFraction(d, emptyMap(), emptyMap()), 0.01f)
    }

    @Test
    fun `discipleHpFraction - maxHp 为 0 返回 1f`() {
        // hpVariance=-100 → 方差乘区钳到 0 → maxHp=0（防御分支）
        val d = makeDisciple("1", currentHp = 50, hpVariance = -100)
        assertEquals(1f, discipleHpFraction(d, emptyMap(), emptyMap()), 0.0001f)
    }

    @Test
    fun `discipleHpFraction - 血炼提升 maxHp 后按提升后口径判定`() {
        val d = makeDisciple("1", currentHp = baseMaxHp())
        val br = BloodRefinementPctTotal(discipleId = "1", hpBonusPct = 0.5)
        val brMaxHp = DiscipleStatCalculator.getFinalStats(
            Disciple(id = "1", name = "弟子1", realm = 9, realmLayer = 1),
            emptyMap(),
            emptyMap(),
            bloodRefinementPct = br
        ).maxHp
        val fraction = discipleHpFraction(d, emptyMap(), emptyMap(), bloodRefinementPct = br)
        assertTrue("血炼 +50% maxHp 后基础满血应小于 1f，实际 $fraction", fraction < 1f)
        assertEquals(baseMaxHp().toFloat() / brMaxHp, fraction, 0.001f)
    }

    // ==================== hasLowHpDisciple ====================

    @Test
    fun `hasLowHpDisciple - 空队伍返回 false`() {
        assertFalse(hasLowHpDisciple(emptyList(), emptyMap(), emptyMap()))
    }

    @Test
    fun `hasLowHpDisciple - 全员满血返回 false`() {
        val full = makeDisciple("1", currentHp = baseMaxHp())
        val sentinel = makeDisciple("2") // -1 哨兵视为满血
        assertFalse(hasLowHpDisciple(listOf(full, sentinel), emptyMap(), emptyMap()))
    }

    @Test
    fun `hasLowHpDisciple - 任一弟子未满返回 true`() {
        val maxHp = baseMaxHp()
        val full = makeDisciple("1", currentHp = maxHp)
        val hurt = makeDisciple("2", currentHp = maxHp / 2)
        assertTrue(hasLowHpDisciple(listOf(full, hurt), emptyMap(), emptyMap()))
    }

    @Test
    fun `hasLowHpDisciple - 血炼提升 maxHp 后原血量算未满`() {
        val d = makeDisciple("1", currentHp = baseMaxHp())
        val br = BloodRefinementPctTotal(discipleId = "1", hpBonusPct = 0.5)
        assertTrue(
            hasLowHpDisciple(
                listOf(d), emptyMap(), emptyMap(),
                bloodRefinementPctTotals = mapOf("1" to br)
            )
        )
    }
}
