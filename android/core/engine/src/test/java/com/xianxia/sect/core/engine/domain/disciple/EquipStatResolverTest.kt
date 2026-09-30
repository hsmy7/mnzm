package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipLevelCurve
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 装备加成汇合点守卫（装备重构 B3，方案 §3.10/§6.1）。
 *
 * [EquipStatResolver.resolve] 是装备加成**唯一结算入口**（Kotlin 消费点 =
 * StatCalculator Ops3/4；C++ 对偶 disciple_stats.h 逐位一致）。本测试钉死：
 * flat 求和、ATTACK_PCT 分项、类型加成/暴击伤害分项通道、等级成长折算、
 * 空装备/未知 setId/损坏词条的健壮性。乘区应用口径见 StatCalculator
 * （`atk=(base+flat)×(1+pct)`，本解析器只产出分项）。
 */
class EquipStatResolverTest {

    /** 构造一件最小装备实例 */
    private fun instance(
        id: String = "e1",
        setId: String = "",
        part: EquipmentSlot = EquipmentSlot.WEAPON,
        level: Int = 1,
        affix: EquipAffixSet = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 10.0))
    ) = EquipmentInstance(
        id = id,
        name = "测试装备$id",
        setId = setId,
        part = part,
        growth = EquipGrowth(level = level, affix = affix),
        meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 1)
    )

    @Test
    fun `空装备解析为零加成`() {
        assertEquals(EquipBonus(), EquipStatResolver.resolve(emptyList()))
        assertEquals(EquipBonus(), EquipStatResolver.resolveSetBonus(emptyList()))
    }

    @Test
    fun `flat三维度求和`() {
        val a = instance(
            id = "a", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                subStats = listOf(
                    EquipStatValue(EquipStat.DEFENSE, 5.0),
                    EquipStatValue(EquipStat.HP, 100.0),
                    EquipStatValue(EquipStat.ATTACK, 2.0)
                ),
                subRolls = listOf(1, 1, 1)
            )
        )
        val b = instance(id = "b", affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.HP, 50.0)))
        val bonus = EquipStatResolver.resolve(listOf(a, b))
        assertEquals(12.0, bonus.flatAttack, 1e-12)
        assertEquals(5.0, bonus.flatDefense, 1e-12)
        assertEquals(150.0, bonus.flatHp, 1e-12)
    }

    @Test
    fun `暴击与类型加成分项通道互不串扰`() {
        val a = instance(
            id = "a", setId = "lietian", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                subStats = listOf(
                    EquipStatValue(EquipStat.CRIT_RATE, 0.05),
                    EquipStatValue(EquipStat.CRIT_DAMAGE, 0.10),
                    EquipStatValue(EquipStat.PHYSICAL_DAMAGE_PCT, 0.15)
                ),
                subRolls = listOf(1, 1, 1)
            )
        )
        val b = instance(
            id = "b", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                subStats = listOf(
                    EquipStatValue(EquipStat.MAGIC_DAMAGE_PCT, 0.20),
                    EquipStatValue(EquipStat.ATTACK_PCT, 0.08),
                    EquipStatValue(EquipStat.CRIT_RATE, 0.03)
                ),
                subRolls = listOf(1, 1, 1)
            )
        )
        val bonus = EquipStatResolver.resolve(listOf(a, b))
        assertEquals(0.08, bonus.critRate, 1e-12)
        assertEquals(0.10, bonus.critDamage, 1e-12)
        assertEquals(0.15, bonus.physicalDamageBonus, 1e-12)
        assertEquals(0.20, bonus.magicDamageBonus, 1e-12)
        assertEquals(0.08, bonus.pctAttack, 1e-12)
    }

    @Test
    fun `主词条按等级成长折算`() {
        // Lv10 → 乘数 1 + 0.10×9 = 1.9
        val a = instance(level = 10, affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 100.0)))
        val bonus = EquipStatResolver.resolve(listOf(a))
        assertEquals(100.0 * EquipLevelCurve.mainLevelMultiplier(10), bonus.flatAttack, 1e-12)
    }

    @Test
    fun `副词条按强化次数放大`() {
        val a = instance(
            id = "a", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                subStats = listOf(EquipStatValue(EquipStat.DEFENSE, 4.0)),
                subRolls = listOf(6)
            )
        )
        val bonus = EquipStatResolver.resolve(listOf(a))
        assertEquals(24.0, bonus.flatDefense, 1e-12)
    }

    @Test
    fun `损坏词条安全收敛不崩溃`() {
        // 空 subStats、subRolls 与 subStats 失配、零值词条均不得抛异常
        val damaged = instance(
            id = "d", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 0.0),
                subStats = listOf(EquipStatValue(EquipStat.DEFENSE, 3.0)),
                subRolls = emptyList()
            )
        )
        val bonus = EquipStatResolver.resolve(listOf(damaged))
        assertEquals(0.0, bonus.flatAttack, 1e-12)
        assertEquals("缺省强化次数按 1 计", 3.0, bonus.flatDefense, 1e-12)
    }

    @Test
    fun `EquipBonus加法算子逐维相加`() {
        val x = EquipBonus(flatAttack = 1.0, critRate = 0.1)
        val y = EquipBonus(flatAttack = 2.0, critRate = 0.2, magicDamageBonus = 0.3)
        val sum = x + y
        assertEquals(3.0, sum.flatAttack, 1e-12)
        assertEquals(0.3, sum.critRate, 1e-12)
        assertEquals(0.3, sum.magicDamageBonus, 1e-12)
        assertEquals(0.0, sum.flatHp, 1e-12)
    }

    @Test
    fun `乘区口径只放大装备自身贡献的注释口径不受解析器影响`() {
        // 解析器只产出 pctAttack 分项；乘法应用在 StatCalculator——
        // 此处钉死分项语义：套装/词条产出的 ATTACK_PCT 全部进 pctAttack 通道
        val a = instance(
            id = "a", setId = "lietian", affix = EquipAffixSet(
                mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
                subStats = listOf(EquipStatValue(EquipStat.ATTACK_PCT, 0.5)),
                subRolls = listOf(1)
            )
        )
        val bonus = EquipStatResolver.resolve(listOf(a))
        assertEquals(10.0, bonus.flatAttack, 1e-12)
        assertEquals(0.5, bonus.pctAttack, 1e-12)
    }
}
