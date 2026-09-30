package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipLevelCurve
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备实例数值消毒守卫（装备重构 B3，方案 §3.6/§6.1——order=28）。
 *
 * 修复口径（coerce 截断，非归一）：level 1..30；exp 0..expRequired(level)-1
 * （满级收敛 0）；副词条恒 3 条互不重复（重复保首条）、subRolls 等长 1..11；
 * 修复幂等（二次执行恒 Passed）；无损坏实例恒 Passed 不落盘（B2 先例）。
 */
class EquipmentValueSanitizeRuleTest {

    private fun affix(
        subs: List<EquipStatValue> = listOf(
            EquipStatValue(EquipStat.DEFENSE, 3.0),
            EquipStatValue(EquipStat.HP, 30.0),
            EquipStatValue(EquipStat.CRIT_RATE, 0.01)
        ),
        rolls: List<Int> = listOf(1, 1, 1)
    ) = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 10.0), subStats = subs, subRolls = rolls)

    private fun instance(
        id: String = "e1",
        level: Int = 1,
        exp: Int = 0,
        affix: EquipAffixSet = affix()
    ) = EquipmentInstance(
        id = id,
        name = "测试装备",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(level = level, exp = exp, affix = affix)
    )

    private fun saveData(instances: List<EquipmentInstance>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = emptyList(),
        equipmentInstances = instances,
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    private fun single(data: SaveData) = data.equipmentInstances.single()

    // ── 无损恒 Passed ────────────────────────────────────────

    @Test
    fun `无损实例恒Passed不落盘`() {
        val result = EquipmentValueSanitizeRule.execute(
            saveData(listOf(instance(), instance(id = "e2", level = 30))),
            RuleContext(saveData(emptyList()))
        )
        assertEquals("无损坏应恒 Passed（B2「无存量恒 Passed」先例）", RuleOutcome.Passed, result)
    }

    // ── 等级/经验 coerce ─────────────────────────────────────

    @Test
    fun `等级越界收敛到1到30`() {
        val data = saveData(listOf(instance(id = "low", level = 0), instance(id = "high", level = 99)))
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals(1, repaired.equipmentInstances.first { it.id == "low" }.level)
        assertEquals(30, repaired.equipmentInstances.first { it.id == "high" }.level)
    }

    @Test
    fun `经验越界收敛到该级上限`() {
        val expMax = EquipLevelCurve.expRequired(5, 1) - 1
        val data = saveData(
            listOf(
                instance(id = "neg", level = 5, exp = -10),
                instance(id = "over", level = 5, exp = expMax + 999)
            )
        )
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals(0, repaired.equipmentInstances.first { it.id == "neg" }.exp)
        assertEquals(expMax, repaired.equipmentInstances.first { it.id == "over" }.exp)
    }

    @Test
    fun `满级经验收敛为零`() {
        val data = saveData(listOf(instance(id = "max", level = 30, exp = 5)))
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data.equipmentInstances.single()
        assertEquals(0, repaired.exp)
    }

    // ── 副词条与强化次数 ─────────────────────────────────────

    @Test
    fun `重复副词条保首条`() {
        val duplicated = affix(
            subs = listOf(
                EquipStatValue(EquipStat.DEFENSE, 3.0),
                EquipStatValue(EquipStat.DEFENSE, 9.0),
                EquipStatValue(EquipStat.HP, 30.0)
            ),
            rolls = listOf(1, 1, 1)
        )
        val data = saveData(listOf(instance(affix = duplicated)))
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        val affixed = (result as RuleOutcome.Repaired).data.equipmentInstances.single().growth.affix
        assertEquals("重复词条去重后剩 2 条", 2, affixed.subStats.size)
        assertEquals(EquipStat.DEFENSE, affixed.subStats[0].stat)
        assertEquals("保首条（低值 3 而非 9）", 3.0, affixed.subStats[0].value, 1e-12)
        assertEquals(EquipStat.HP, affixed.subStats[1].stat)
    }

    @Test
    fun `超量副词条截断到3条`() {
        val four = affix(
            subs = listOf(
                EquipStatValue(EquipStat.DEFENSE, 3.0),
                EquipStatValue(EquipStat.HP, 30.0),
                EquipStatValue(EquipStat.CRIT_RATE, 0.01),
                EquipStatValue(EquipStat.CRIT_DAMAGE, 0.02)
            ),
            rolls = listOf(1, 1, 1, 1)
        )
        val data = saveData(listOf(instance(affix = four)))
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        assertEquals(3, (result as RuleOutcome.Repaired).data.equipmentInstances.single().growth.affix.subStats.size)
    }

    @Test
    fun `强化次数越界收敛到1到11且等长`() {
        val data = saveData(
            listOf(
                instance(affix = affix(rolls = listOf(0, 99, 5))),
                instance(
                    id = "short",
                    affix = affix(
                        subs = listOf(
                            EquipStatValue(EquipStat.DEFENSE, 3.0),
                            EquipStatValue(EquipStat.HP, 30.0)
                        ),
                        rolls = listOf(7)
                    )
                )
            )
        )
        val result = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        val first = repaired.equipmentInstances.first { it.id == "e1" }.growth.affix
        assertEquals(listOf(1, 11, 5), first.subRolls)
        val second = repaired.equipmentInstances.first { it.id == "short" }.growth.affix
        assertEquals("缺省强化次数补 1", listOf(7, 1), second.subRolls)
    }

    // ── 幂等 ─────────────────────────────────────────────────

    @Test
    fun `修复后二次执行恒Passed`() {
        val data = saveData(listOf(instance(level = 99, exp = -5)))
        val first = EquipmentValueSanitizeRule.execute(data, RuleContext(data))
        assertTrue(first is RuleOutcome.Repaired)
        val repaired = (first as RuleOutcome.Repaired).data
        val second = EquipmentValueSanitizeRule.execute(repaired, RuleContext(repaired))
        assertEquals("修复幂等", RuleOutcome.Passed, second)
    }

    // ── 部位合法性助手 ───────────────────────────────────────

    @Test
    fun `部位合法性助手只认六部位`() {
        EquipmentSlot.entries.forEach { slot ->
            assertTrue(slot.name, EquipmentValueSanitizeRule.isValidPart(slot.name))
        }
        assertFalse(EquipmentValueSanitizeRule.isValidPart("ARMOR"))
        assertFalse(EquipmentValueSanitizeRule.isValidPart("WEAPON_OLD"))
        assertFalse(EquipmentValueSanitizeRule.isValidPart(""))
    }
}
