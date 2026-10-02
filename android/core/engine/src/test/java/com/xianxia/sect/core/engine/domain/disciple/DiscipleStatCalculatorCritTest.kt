package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.PillEffects
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 暴击系统口径守卫：
 * ① 基础暴击率全员 0%（C++ kBaseCritRate 同值，DiffDiscipleTest 对拍）；
 * ② 面板最终暴击伤害加成 = 装备（含套装）critDamage + 丹药暴击效果，
 *    战斗接线后暴击倍率 = 1 + CRIT_BASE_MULTIPLIER + 该值（面板显示与战斗一致）。
 */
class DiscipleStatCalculatorCritTest {

    private fun baseStats() = DiscipleStatCalculator.computeBaseStats(
        realm = 9,
        realmLayer = 1,
        variances = DiscipleStatCalculator.VarianceInputs(0, 0, 0, 0, 0),
        skills = DiscipleStatCalculator.SkillInputs(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    /** 构造一件最小装备实例（EquipStatResolverTest 同款） */
    private fun instance(
        id: String,
        mainStat: EquipStatValue,
        subStats: List<EquipStatValue> = emptyList()
    ) = EquipmentInstance(
        id = id,
        name = "测试装备$id",
        setId = "",
        part = EquipmentSlot.HANDS,
        growth = EquipGrowth(
            level = 1,
            affix = EquipAffixSet(mainStat = mainStat, subStats = subStats)
        ),
        meta = EquipInstanceMeta(rarity = 1)
    )

    @Test
    fun `基础暴击率为 0 且无暴伤加成`() {
        val stats = baseStats()
        assertEquals(0.0, stats.critRate, 1e-12)
        assertEquals(0.0, stats.critDamageBonus, 1e-12)
    }

    @Test
    fun `最终暴击伤害加成 = 装备 critDamage + 丹药暴击效果`() {
        val weapon = instance(
            id = "w1",
            mainStat = EquipStatValue(EquipStat.ATTACK, 10.0),
            subStats = listOf(EquipStatValue(EquipStat.CRIT_DAMAGE, 0.2))
        )
        val stats = DiscipleStatCalculator.computeFinalStats(
            baseStats = baseStats(),
            equipmentIds = listOf("w1"),
            manualIds = emptyList(),
            equipments = mapOf("w1" to weapon),
            manuals = emptyMap(),
            manualProficiencies = emptyMap(),
            pillEffects = PillEffects(
                pillCritRateBonus = 0.03,
                pillCritEffectBonus = 0.1,
                pillEffectDuration = 3
            )
        )
        // 装备先丹药后（加法序与 C++ discipleToCombatant 装配一致）
        assertEquals(0.3, stats.critDamageBonus, 1e-12)
        // 基础 0 + 丹药暴击率（本例装备无暴击率词条）
        assertEquals(0.03, stats.critRate, 1e-12)
    }

    @Test
    fun `装备暴击率词条累加进最终暴击率`() {
        val weapon = instance(
            id = "w2",
            mainStat = EquipStatValue(EquipStat.CRIT_RATE, 0.08)
        )
        val stats = DiscipleStatCalculator.computeFinalStats(
            baseStats = baseStats(),
            equipmentIds = listOf("w2"),
            manualIds = emptyList(),
            equipments = mapOf("w2" to weapon),
            manuals = emptyMap(),
            manualProficiencies = emptyMap(),
            pillEffects = PillEffects()
        )
        assertEquals(0.08, stats.critRate, 1e-12)
        assertEquals(0.0, stats.critDamageBonus, 1e-12)
    }

    @Test
    fun `丹药失效期不贡献暴击与暴伤加成`() {
        val stats = DiscipleStatCalculator.computeFinalStats(
            baseStats = baseStats(),
            equipmentIds = emptyList(),
            manualIds = emptyList(),
            equipments = emptyMap(),
            manuals = emptyMap(),
            manualProficiencies = emptyMap(),
            pillEffects = PillEffects(
                pillCritRateBonus = 0.05,
                pillCritEffectBonus = 0.2,
                pillEffectDuration = 0
            )
        )
        assertEquals(0.0, stats.critRate, 1e-12)
        assertEquals(0.0, stats.critDamageBonus, 1e-12)
    }
}
