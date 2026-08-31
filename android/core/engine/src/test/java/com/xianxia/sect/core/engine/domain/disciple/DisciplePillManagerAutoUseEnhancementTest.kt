package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.StorageBagItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 2026-08-31 增强：自动服用丹药的服用门槛与效果（C1/C2/C3/A2）。
 *
 * - C1：满血/满蓝不自动服用治疗/回蓝丹（canUsePill 门槛）
 * - C2：战斗临时丹不自动服用（保留手动/战前结算）
 * - C3：满修为不浪费修为丹、全功法满级不浪费功法经验丹
 * - A2：孕养度丹（nurtureAdd）经回调均分到已装备装备实例
 */
class DisciplePillManagerAutoUseEnhancementTest {

    private lateinit var pillManager: DisciplePillManager

    @Before
    fun setUp() {
        pillManager = DisciplePillManager(PillEffectApplier())
        // canUsePill 的治疗丹满血判定经 disciple.maxHp（getBaseStats）——
        // 纯 JUnit 需手动绑定晚绑定属性计算器（对齐 Diff 对拍测试模式）
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                d: Disciple, e: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
            override fun getStatsWithEquipment(
                a: DiscipleAggregate, e: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
            override fun getFinalStats(
                d: Disciple,
                e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, bloodRefinementPct)
            override fun getFinalStats(
                a: DiscipleAggregate,
                e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, bloodRefinementPct)
            override fun calculateCultivationSpeed(
                d: Disciple,
                manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                d, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )
            override fun calculateCultivationSpeed(
                a: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                a, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )
            override fun getBreakthroughChance(
                d: Disciple, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
            override fun getBreakthroughChance(
                a: DiscipleAggregate, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }
    }

    // ── 测试辅助 ──────────────────────────────────────────────────

    private fun disciple(
        bag: List<StorageBagItem>,
        currentHp: Int = -1,
        cultivation: Double = 10.0,
        masteries: Map<String, Int> = emptyMap()
    ) = Disciple(
        id = "1",
        name = "测试弟子",
        realm = 9,
        cultivation = cultivation,
        manualMasteries = masteries,
        combat = CombatAttributes(currentHp = currentHp),
        equipment = EquipmentSet(storageBagItems = bag)
    )

    private fun healPill(healPercent: Double = 30.0, itemId: String = "h1") = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "疗伤丹", rarity = 2, quantity = 1,
        effect = ItemEffect(healMaxHpPercent = healPercent)
    )

    private fun battlePill(itemId: String = "b1") = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "狂暴丹", rarity = 2, quantity = 1,
        effect = ItemEffect(physicalAttackAdd = 20)
    )

    private fun cultivationPill(add: Int = 30, itemId: String = "p1") = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "修为丹", rarity = 1, quantity = 1,
        effect = ItemEffect(pillType = "cultivationAdd", cultivationAdd = add)
    )

    private fun skillExpPill(add: Int = 30, itemId: String = "s1") = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "功法经验丹", rarity = 1, quantity = 1,
        effect = ItemEffect(pillType = "skillExpAdd", skillExpAdd = add)
    )

    private fun nurturePill(add: Int = 100, itemId: String = "n1") = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "蕴器丹", rarity = 3, quantity = 1,
        effect = ItemEffect(pillType = "nurtureAdd", nurtureAdd = add)
    )

    // ── C1：治疗丹按需服用 ────────────────────────────────────────

    @Test
    fun `C1 - 满血治疗丹 canUsePill 拒绝 受伤后允许`() {
        val full = disciple(bag = listOf(healPill()))
        assertFalse("满血（-1 哨兵）治疗丹不可用",
            pillManager.canUsePill(full, healPill()).canUse)

        val injured = disciple(bag = listOf(healPill()), currentHp = 50)
        assertTrue("受伤治疗丹可用",
            pillManager.canUsePill(injured, healPill()).canUse)
    }

    @Test
    fun `C1 - 自动服用满血治疗丹跳过`() {
        val d = disciple(bag = listOf(healPill()))
        val result = pillManager.processAutoUsePills(d)
        assertEquals("满血治疗丹应保留", 1, result.disciple.equipment.storageBagItems.size)
    }

    // ── C2：战斗临时丹不自动服用 ──────────────────────────────────

    @Test
    fun `C2 - 战斗临时丹不被自动服用`() {
        val d = disciple(bag = listOf(battlePill()))
        val result = pillManager.processAutoUsePills(d)
        assertEquals("战斗临时丹应保留袋内", 1, result.disciple.equipment.storageBagItems.size)
        assertEquals("b1", result.disciple.equipment.storageBagItems[0].itemId)
    }

    // ── C3：满修为/全功法满级不浪费 ───────────────────────────────

    @Test
    fun `C3 - 满修为修为丹跳过 不满则服用`() {
        val full = disciple(bag = listOf(cultivationPill()), cultivation = 98.0)
        val fullResult = pillManager.processAutoUsePills(full)
        assertEquals("满修为修为丹应跳过", 1, fullResult.disciple.equipment.storageBagItems.size)

        val notFull = disciple(bag = listOf(cultivationPill()), cultivation = 10.0)
        val result = pillManager.processAutoUsePills(notFull)
        assertTrue("不满修为修为丹应服用", result.disciple.equipment.storageBagItems.isEmpty())
        assertEquals("修为应增加", 40.0, result.disciple.cultivation, 0.001)
    }

    @Test
    fun `C3 - 全功法满级功法经验丹跳过`() {
        val allMaxed = disciple(
            bag = listOf(skillExpPill()),
            masteries = mapOf("m1" to 10000, "m2" to 10000)
        )
        val result = pillManager.processAutoUsePills(allMaxed)
        assertEquals("全功法满级功法经验丹应跳过", 1, result.disciple.equipment.storageBagItems.size)

        val notMaxed = disciple(
            bag = listOf(skillExpPill()),
            masteries = mapOf("m1" to 10000, "m2" to 5000)
        )
        val consumed = pillManager.processAutoUsePills(notMaxed)
        assertTrue("未全满功法经验丹应服用",
            consumed.disciple.equipment.storageBagItems.isEmpty())
    }

    // ── A2：孕养度丹经回调生效 ────────────────────────────────────

    @Test
    fun `A2 - 孕养度丹触发回调且扣袋`() {
        var nurtureAmount = 0
        val d = disciple(bag = listOf(nurturePill(add = 100)))
        val result = pillManager.processAutoUsePills(
            d, nurtureEffect = { amount -> nurtureAmount = amount }
        )
        assertEquals("孕养度回调应收到 100", 100, nurtureAmount)
        assertTrue("孕养度丹应被消费", result.disciple.equipment.storageBagItems.isEmpty())
    }

    @Test
    fun `A2 - 无回调时孕养度丹照常消费`() {
        val d = disciple(bag = listOf(nurturePill(add = 100)))
        val result = pillManager.processAutoUsePills(d)
        assertTrue("无回调时孕养度丹仍消费", result.disciple.equipment.storageBagItems.isEmpty())
    }
}
