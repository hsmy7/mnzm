package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.util.BuildingNames
import org.junit.Assert.*
import org.junit.Test

class FormulaServicePureLogicTest {

    // ==================== 境界成功率加成 ====================

    private fun getRealmSuccessRateBonus(realm: Int): Double = when (realm) {
        0 -> 0.30; 1 -> 0.25; 2 -> 0.22; 3 -> 0.19
        4 -> 0.16; 5 -> 0.13; 6 -> 0.10; 7 -> 0.07
        8 -> 0.04; else -> 0.0
    }

    @Test fun realm0_仙人_30percent() = assertEquals(0.30, getRealmSuccessRateBonus(0), 0.001)
    @Test fun realm1_渡劫_25percent() = assertEquals(0.25, getRealmSuccessRateBonus(1), 0.001)
    @Test fun realm2_大乘_22percent() = assertEquals(0.22, getRealmSuccessRateBonus(2), 0.001)
    @Test fun realm3_合体_19percent() = assertEquals(0.19, getRealmSuccessRateBonus(3), 0.001)
    @Test fun realm4_炼虚_16percent() = assertEquals(0.16, getRealmSuccessRateBonus(4), 0.001)
    @Test fun realm5_化神_13percent() = assertEquals(0.13, getRealmSuccessRateBonus(5), 0.001)
    @Test fun realm6_元婴_10percent() = assertEquals(0.10, getRealmSuccessRateBonus(6), 0.001)
    @Test fun realm7_金丹_7percent() = assertEquals(0.07, getRealmSuccessRateBonus(7), 0.001)
    @Test fun realm8_筑基_4percent() = assertEquals(0.04, getRealmSuccessRateBonus(8), 0.001)
    @Test fun realm9_炼气_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(9), 0.001)
    @Test fun realmNeg1_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(-1), 0.001)
    @Test fun realm100_0percent() = assertEquals(0.0, getRealmSuccessRateBonus(100), 0.001)

    // ==================== 持续时间缩减公式 ====================

    private fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        val reductionPercent = speedBonus / 4.0
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }

    @Test fun reducedDuration_zeroBonus_returnsBase() = assertEquals(10, calculateReducedDuration(10, 0.0))
    @Test fun reducedDuration_negativeBonus_returnsBase() = assertEquals(10, calculateReducedDuration(10, -1.0))
    @Test fun reducedDuration_smallBonus() = assertEquals(9, calculateReducedDuration(10, 0.4))
    @Test fun reducedDuration_largeBonus_minimum1() = assertEquals(1, calculateReducedDuration(10, 100.0))
    @Test fun reducedDuration_base1_always1() = assertEquals(1, calculateReducedDuration(1, 0.5))
    @Test fun reducedDuration_exactCalculation() {
        // baseDuration=12, speedBonus=2.0 -> reductionPercent=0.5 -> reducedMonths=6 -> result=6
        assertEquals(6, calculateReducedDuration(12, 2.0))
    }

    // ==================== ElderBonusData ====================

    @Test fun elderBonusData_equality() {
        val a = FormulaService.ElderBonusData(0.1, 0.2, 0.0)
        val b = FormulaService.ElderBonusData(0.1, 0.2, 0.0)
        assertEquals(a, b)
    }
    @Test fun elderBonusData_defaultYieldZero() {
        val data = FormulaService.ElderBonusData(0.5, 0.3, 0.0)
        assertEquals(0.0, data.yieldBonus, 0.001)
    }

    // ==================== SuccessRateZones.calculate() 乘区法合成（职业系统重构后真实实现） ====================

    @Test fun successRateZones_noBonus_initialZero() {
        // 无属性/职业/乘区加成 → 初始成功率 0
        val zones = FormulaService.SuccessRateZones()
        assertEquals(0.0, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_skillZoneOnly_defaultSkill50() {
        // 默认属性 50：skillZone = (50-30)×0.006 = 0.12 → 12%
        val zones = FormulaService.SuccessRateZones(skillZone = 0.12)
        assertEquals(0.12, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_skillZoneOverHalfPassesThrough() {
        // skillZone 的 0.5 上限由 buildSuccessRateZones 计算侧 clamp，calculate() 仅 clamp 合成值到 [0,1]
        val zones = FormulaService.SuccessRateZones(skillZone = 0.55)
        assertEquals(0.55, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_professionZoneOnly_grandMasterCraftsTier1() {
        // 五品（可炼 tier 6）炼凡品：professionZone = 5×0.20 = 1.0 → clamp 1.0 → 100%
        val zones = FormulaService.SuccessRateZones(professionZone = 1.0)
        assertEquals(1.0, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_baseProbIsSumOfZones() {
        // baseProb = baseRate + skillZone + professionZone = 0 + 0.12 + 0.40 = 0.52
        val zones = FormulaService.SuccessRateZones(skillZone = 0.12, professionZone = 0.40)
        assertEquals(0.52, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_multiplicativeAmplification() {
        // baseProb 0.5 × (1 + 境界 0.13 + 天赋 0.1 + 政策 0.1) = 0.5 × 1.33 = 0.665
        val zones = FormulaService.SuccessRateZones(
            skillZone = 0.5,
            realmZone = 0.13,
            talentZone = 0.1,
            policyZone = 0.1
        )
        assertEquals(0.665, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_clampedTo1() {
        // 光职业即接近 100%：0.8 × (1 + 0.3) = 1.04 → clamp 1.0
        val zones = FormulaService.SuccessRateZones(
            professionZone = 0.8,
            realmZone = 0.3
        )
        assertEquals(1.0, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_negativeBaseClampedTo0() {
        val zones = FormulaService.SuccessRateZones(baseRate = -0.5, skillZone = -0.1)
        assertEquals(0.0, zones.calculate(), 0.001)
    }

    @Test fun successRateZones_highSkillNoRealmSameTier() {
        // 一品炼同阶（灵品，无职业加成）、技能 80、金丹、长老 80 技能：
        // baseProb = 0.30，乘区 = (1 + 0.07 + 0 + 0 + 0) = 1.07 → 0.321
        val zones = FormulaService.SuccessRateZones(
            skillZone = 0.30,
            realmZone = 0.07
        )
        assertEquals(0.321, zones.calculate(), 0.001)
    }

    // ==================== buildSuccessRateZones() 真实实现极值测试（对抗性审查回归） ====================

    private fun newFormulaService(): FormulaService =
        FormulaService(FakeAtomicStateStore(), com.xianxia.sect.core.engine.testProductionSlotRepository())

    private fun disciple(
        pillRefining: Int = 50,
        artifactRefining: Int = 50,
        alchemyLevel: Int = 0,
        forgeLevel: Int = 0,
        realm: Int = 9
    ) = Disciple(
        name = "测试弟子",
        realm = realm,
        skills = SkillStats(
            pillRefining = pillRefining,
            artifactRefining = artifactRefining,
            alchemyLevel = alchemyLevel,
            forgeLevel = forgeLevel
        )
    )

    @Test fun buildSuccessRateZones_intMinSkill_yieldsZeroZone() {
        // 对抗性审查：Int 减法溢出——skill=Int.MIN_VALUE 时 (skill-30) Int 运算溢出为正，
        // 修复前会错误获得满属性加成；Long 运算修复后应为 0
        val zones = newFormulaService().buildSuccessRateZones(
            disciple(pillRefining = Int.MIN_VALUE), BuildingNames.ALCHEMY, recipeTier = 1
        )
        assertEquals(0.0, zones.skillZone, 0.001)
        assertEquals(0.0, zones.calculate(), 0.001)
    }

    @Test fun buildSuccessRateZones_intMaxSkill_clampsToHalf() {
        val zones = newFormulaService().buildSuccessRateZones(
            disciple(pillRefining = Int.MAX_VALUE), BuildingNames.ALCHEMY, recipeTier = 1
        )
        assertEquals(0.50, zones.skillZone, 0.001)
    }

    @Test fun buildSuccessRateZones_nullDisciple_noSkillOrProfessionZone() {
        val zones = newFormulaService().buildSuccessRateZones(
            null, BuildingNames.ALCHEMY, recipeTier = 1
        )
        assertEquals(0.0, zones.skillZone, 0.001)
        assertEquals(0.0, zones.professionZone, 0.001)
        assertEquals(0.0, zones.calculate(), 0.001)
    }

    @Test fun buildSuccessRateZones_defaultSkill50_yields12Percent() {
        val zones = newFormulaService().buildSuccessRateZones(
            disciple(), BuildingNames.ALCHEMY, recipeTier = 1
        )
        assertEquals(0.12, zones.skillZone, 0.001)
        assertEquals(0.12, zones.calculate(), 0.001)
    }

    @Test fun buildSuccessRateZones_grandMasterCraftsTier1_professionAlone100Percent() {
        // 五品（level 5 可炼 tier 6）炼凡品：职业加成 = (6-1)×0.20 = 1.0 → clamp 1.0 → 100%
        val zones = newFormulaService().buildSuccessRateZones(
            disciple(pillRefining = 30, alchemyLevel = 5), BuildingNames.ALCHEMY, recipeTier = 1
        )
        assertEquals(1.0, zones.professionZone, 0.001)
        assertEquals(1.0, zones.calculate(), 0.001)
    }

    @Test fun buildSuccessRateZones_forgeBuilding_usesArtifactRefiningAndForgeLevel() {
        val zones = newFormulaService().buildSuccessRateZones(
            disciple(artifactRefining = 80, forgeLevel = 2), BuildingNames.FORGE, recipeTier = 2
        )
        // 属性 80 → (80-30)×0.006 = 0.30；level 2 可炼 tier 3，炼 tier 2 → 1×0.20 = 0.20
        assertEquals(0.30, zones.skillZone, 0.001)
        assertEquals(0.20, zones.professionZone, 0.001)
        assertEquals(0.50, zones.calculate(), 0.001)
    }

    // ==================== 长老加成读含 Flat 天赋属性（2026-08-12 Bug 2 修复） ====================
    // 修复前 getElderPositionBonus/calculateElderAndDisciplesBonus 读原始 skills，
    // "天丹(炼丹+18)"只对成功率生效，长老加成恒为 0。

    /** 带炼丹长老（78 + 天丹 r3 18 = 96）的 store */
    private fun storeWithAlchemyElder(
        pillRefining: Int = 78,
        talentIds: List<String> = emptyList()
    ): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        store.disciples.value = listOf(
            Disciple(id = "e1", name = "炼丹长老", realm = 9, skills = SkillStats(pillRefining = pillRefining),
                talentIds = talentIds)
        )
        store.setGameData(GameData(elderSlots = ElderSlots(alchemyElder = "e1")))
        return store
    }

    @Test fun calculateElderAndDisciplesBonus_flatTalentElder_bonusUsesMergedSkill() {
        val service = FormulaService(storeWithAlchemyElder(talentIds = listOf("r3_base_pill")),
            com.xianxia.sect.core.engine.testProductionSlotRepository())
        val bonus = service.calculateElderAndDisciplesBonus(BuildingNames.ALCHEMY)
        // 天丹 +18 → 有效炼丹 = 78+18 = 96 → (96-80)×0.01 = 0.16
        assertEquals("带天丹天赋长老成功率加成应含 flat", 0.16, bonus.successBonus, 0.001)
    }

    @Test fun calculateElderAndDisciplesBonus_plainElder_belowBaselineZero() {
        val service = FormulaService(storeWithAlchemyElder(pillRefining = 78),
            com.xianxia.sect.core.engine.testProductionSlotRepository())
        val bonus = service.calculateElderAndDisciplesBonus(BuildingNames.ALCHEMY)
        // 无天赋 78 < 80 → 0.0
        assertEquals(0.0, bonus.successBonus, 0.001)
    }

    @Test fun buildSuccessRateZones_flatTalentElder_elderZoneApplied() {
        val service = FormulaService(storeWithAlchemyElder(talentIds = listOf("r3_base_pill")),
            com.xianxia.sect.core.engine.testProductionSlotRepository())
        val zones = service.buildSuccessRateZones(
            disciple(pillRefining = 50), BuildingNames.ALCHEMY, recipeTier = 1
        )
        // elderZone = (96-80)×0.01×(1+0) = 0.16
        assertEquals("炼丹长老带天丹天赋的 elderZone 应含 flat", 0.16, zones.elderZone, 0.001)
    }
}
