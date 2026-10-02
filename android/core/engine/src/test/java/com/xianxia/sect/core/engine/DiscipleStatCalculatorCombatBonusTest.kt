package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Test
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.calculateQingyunPeakCultivationSpeedBonus
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughBonusDetail
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment

/** DiscipleStatCalculatorTest 拆分（LC>800 行）：传道/装备/突破乘区加成用例。 */
class DiscipleStatCalculatorCombatBonusTest {

    /**
     * 临时绑定真实 statsProvider（自身悟性经 Disciple.getBaseStats() 晚绑定读取）。
     * finally 还原，避免污染其他测试类共享的静态状态。
     */
    private fun <T> withRealStatsProvider(block: () -> T): T {
        val original = DiscipleAggregate.statsProvider
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: Disciple,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(
                disciple, equipments, manuals, manualProficiencies
            )
            override fun getFinalStats(
                aggregate: DiscipleAggregate,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(
                aggregate, equipments, manuals, manualProficiencies
            )
            override fun calculateCultivationSpeed(
                disciple: Disciple,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus
            )
            override fun getBreakthroughChance(
                disciple: Disciple,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension, pillBonus,
                adBonus
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension, pillBonus,
                adBonus
            )
        }
        try {
            return block()
        } finally {
            DiscipleAggregate.statsProvider = original
        }
    }

    private fun createDisciple(
        realm: Int = 9,
        realmLayer: Int = 1,
        baseHp: Int = 100,
        baseMp: Int = 50,
        baseAttack: Int = 20,
        baseDefense: Int = 10,
        baseSpeed: Int = 30,
        intelligence: Int = 50,
        charm: Int = 50,
        comprehension: Int = 50,
        teaching: Int = 50,
        morality: Int = 50,
        manualIds: List<String> = emptyList(),

        bodyId: String = "",
        feetId: String = "",
        handsId: String = "",
        pillEffectDuration: Int = 0,
        pillHpBonus: Int = 0,
        pillMpBonus: Int = 0,
        pillAttackBonus: Int = 0,
        pillDefenseBonus: Int = 0,
        pillSpeedBonus: Int = 0,
        discipleType: String = "inner",
        statusData: Map<String, String> = emptyMap(),
        spiritRootType: String = "metal"
    ): Disciple {
        return Disciple(
            realm = realm,
            realmLayer = realmLayer,
            manualIds = manualIds,
            spiritRootType = spiritRootType,
            combat = CombatAttributes(
                baseHp = baseHp,
                baseMp = baseMp,
                baseAttack = baseAttack,
                baseDefense = baseDefense,
                baseSpeed = baseSpeed
            ),
            pillEffects = PillEffects(
                pillHpBonus = pillHpBonus,
                pillMpBonus = pillMpBonus,
                pillAttackBonus = pillAttackBonus,
                pillDefenseBonus = pillDefenseBonus,
                pillSpeedBonus = pillSpeedBonus,
                pillEffectDuration = pillEffectDuration
            ),
            statusData = statusData
        ).copy(
            skills = SkillStats(
                intelligence = intelligence,
                charm = charm,
                comprehension = comprehension,
                teaching = teaching,
                morality = morality
            ),
            equipment = EquipmentSet(
                bodyId = bodyId,
                feetId = feetId,
                handsId = handsId
            ),
            discipleType = discipleType
        )
    }

    @Test
    fun `calculateQingyunPeakBonus - 传道师teaching110上限5percent`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val master = createDisciple(discipleType = "inner",
            realm = 9).let { it.copy(skills = it.skills.copy(teaching = 110)) }
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingMasters = listOf(master)
        )
        assertEquals(0.05, bonus, 0.001)
    }
    @Test
    fun `calculateQingyunPeakBonus - 传道师teaching70每10点1percent`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val master = createDisciple(discipleType = "inner",
            realm = 9).let { it.copy(skills = it.skills.copy(teaching = 70)) }
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingMasters = listOf(master)
        )
        assertEquals(0.01, bonus, 0.001)
    }
    @Test
    fun `calculateQingyunPeakBonus - 传道师teaching60基线无加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val master = createDisciple(discipleType = "inner",
            realm = 9).let { it.copy(skills = it.skills.copy(teaching = 60)) }
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingMasters = listOf(master)
        )
        assertEquals(0.0, bonus, 0.001)
    }
    @Test
    fun `calculateQingyunPeakBonus - 传道师teaching50低于基线无加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val master = createDisciple(discipleType = "inner",
            realm = 9).let { it.copy(skills = it.skills.copy(teaching = 50)) }
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingMasters = listOf(master)
        )
        assertEquals(0.0, bonus, 0.001)
    }
    @Test
    fun `getStatsWithEquipment - 无装备时与基础属性一致`() {
        val disciple = createDisciple()
        val baseStats = DiscipleStatCalculator.getBaseStats(disciple)
        val equippedStats = DiscipleStatCalculator.getStatsWithEquipment(disciple, emptyMap())
        assertEquals(baseStats.attack, equippedStats.attack)
        assertEquals(baseStats.defense, equippedStats.defense)
    }

    // ── 内门/外门长老加成计算验证 ──
    @Test
    fun `getBreakthroughChance - 内门长老加成正确计算`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        // 悟性90 → (90-80)/4*0.01 = 0.02（公式：每高4点+1%）
        // 乘区法公式：base * (1 + elderBonus)，差值 = 0.42 * 0.02 = 0.0084
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, innerElderComprehension = 90
        )
        assertEquals(0.0084, bonusChance - baseChance, 0.001)
    }
    @Test
    fun `getBreakthroughChance - 外门长老加成正确计算`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        // 外门长老加成已预计算为Double，直接传入
        // 悟性95 → (95-80)/4*0.01 = 0.03
        // 乘区法公式：base * (1 + elderBonus)，差值 = 0.42 * 0.03 = 0.0126
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, outerElderComprehension = 95
        )
        assertEquals(0.0126, bonusChance - baseChance, 0.001)
    }
    @Test
    fun `getBreakthroughChance - 内门和外门长老加成可叠加`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        // 内门长老悟性100 → (100-80)/4*0.01 = 0.05
        // 外门长老加成直接传入0.03
        // 乘区法：elderGuidance = 0.08，差值 = 0.42 * 0.08 = 0.0336
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bothChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple,
            innerElderComprehension = 100,
            outerElderComprehension = 95
        )
        assertEquals(0.0336, bothChance - baseChance, 0.001)
    }
    @Test
    fun `getBreakthroughChance - 内门长老悟性低于80无加成`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, innerElderComprehension = 70
        )
        assertEquals(baseChance, bonusChance, 0.001)
    }
    @Test
    fun `getBreakthroughChance - 内门长老悟性加成上限为10`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        // 悟性105 → (105-80)/4 = 6步 → +6% (未触及上限)
        val chance105 = DiscipleStatCalculator.getBreakthroughChance(
            disciple, innerElderComprehension = 105
        )
        // 悟性130 → (130-80)/4 = 12步 → 上限10步 → +10%
        val chance130 = DiscipleStatCalculator.getBreakthroughChance(
            disciple, innerElderComprehension = 130
        )
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        // 乘区法：差值 = 0.42 * 0.06 = 0.0252 / 0.42 * 0.10 = 0.042
        assertEquals(0.0252, chance105 - baseChance, 0.001)
        assertEquals(0.042, chance130 - baseChance, 0.001)
    }
    @Test
    fun `getBreakthroughBonusDetail - 内门长老加成详情正确`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        val detail = DiscipleStatCalculator.getBreakthroughBonusDetail(
            DiscipleAggregate.fromDisciple(disciple),
            innerElderComprehension = 90
        )
        // 悟性90 → (90-80)/4*0.01 = 0.02（整数除法）
        assertEquals(0.02, detail.innerElderBonus, 0.001)
        assertEquals(0.0, detail.outerElderBonus, 0.001)
    }
    @Test
    fun `getBreakthroughBonusDetail - 外门长老加成详情正确`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        val detail = DiscipleStatCalculator.getBreakthroughBonusDetail(
            DiscipleAggregate.fromDisciple(disciple),
            outerElderComprehension = 95
        )
        assertEquals(0.0, detail.innerElderBonus, 0.001)
        assertEquals(0.03, detail.outerElderBonus, 0.001)
    }
    @Test
    fun `getBreakthroughBonusDetail - 双执事加成均在total中体现`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        val baseDetail = DiscipleStatCalculator.getBreakthroughBonusDetail(
            DiscipleAggregate.fromDisciple(disciple)
        )
        val bothDetail = DiscipleStatCalculator.getBreakthroughBonusDetail(
            DiscipleAggregate.fromDisciple(disciple),
            innerElderComprehension = 100,
            outerElderComprehension = 95
        )
        // 内门长老悟性100 → 0.05 + 外门长老 0.03 = 0.08
        // 乘区法：base(0.42) * (1 + 0.08) - 0.42 = 0.0336
        assertEquals(0.0336, bothDetail.total - baseDetail.total, 0.001)
        assertEquals(0.05, bothDetail.innerElderBonus, 0.001)
        assertEquals(0.03, bothDetail.outerElderBonus, 0.001)
    }

    @Test
    fun `DiscipleStats plus - 生产字段叠加不清零`() {
        // 装备/功法/丹药叠加走 plus（total + it）；漏加新字段会把对应值清零（回归守卫）
        val base = DiscipleStats(intelligence = 50)
        val bonus = DiscipleStats(spiritPlanting = 12, artifactRefining = 9, pillRefining = 7)
        val sum = base + bonus
        assertEquals("plus 漏加 spiritPlanting 会清零", 12, sum.spiritPlanting)
        assertEquals("plus 漏加 artifactRefining 会清零", 9, sum.artifactRefining)
        assertEquals("plus 漏加 pillRefining 会清零", 7, sum.pillRefining)
        assertEquals("既有字段不受影响", 50, sum.intelligence)
    }

    // ── 自身悟性 → 突破率 selfBonus（与长老同一公式，乘区内加算）──
    // 自身悟性经 Disciple.getBaseStats() 晚绑定 statsProvider 读取 → withRealStatsProvider 包裹
    @Test
    fun `getBreakthroughChance - 弟子自身悟性加成与长老同公式`() {
        withRealStatsProvider {
            val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 120)
            val base = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 50)
            val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
            val baseChance = DiscipleStatCalculator.getBreakthroughChance(base)
            // 悟性120 → (120-80)/4 = 10步 → +10%（整数除法，与长老公式一致）
            assertEquals(0.042, chance - baseChance, 0.001) // 0.42 * 0.10
        }
    }
    @Test
    fun `getBreakthroughChance - 自身悟性加成上限10percent`() {
        withRealStatsProvider {
            val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 130)
            val base = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 50)
            val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
            val baseChance = DiscipleStatCalculator.getBreakthroughChance(base)
            assertEquals(0.042, chance - baseChance, 0.001) // (130-80)/4=12步→上限10步→+10%
        }
    }
    @Test
    fun `getBreakthroughChance - 自身悟性低于80无加成`() {
        withRealStatsProvider {
            val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 79)
            val base = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 50)
            assertEquals(
                DiscipleStatCalculator.getBreakthroughChance(base),
                DiscipleStatCalculator.getBreakthroughChance(disciple),
                0.001
            )
        }
    }
    @Test
    fun `getBreakthroughChance - 自身悟性与长老加成乘区内加算`() {
        withRealStatsProvider {
            val self = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 120)
            val selfWithElder = DiscipleStatCalculator.getBreakthroughChance(
                self, innerElderComprehension = 120
            )
            val base = DiscipleStatCalculator.getBreakthroughChance(
                createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 50)
            )
            // 自身+10% 与 长老+10% 同属 selfBonus/elderGuidance 两个乘区：
            // base * (1 + 0.10 + 0.10) = base * 1.20，非乘算 1.21
            assertEquals(base * 1.20, selfWithElder, 0.001)
        }
    }
    @Test
    fun `getBreakthroughBonusDetail - 悟性加成行selfComprehensionBonus正确`() {
        withRealStatsProvider {
            val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal", comprehension = 100)
            val detail = DiscipleStatCalculator.getBreakthroughBonusDetail(
                DiscipleAggregate.fromDisciple(disciple)
            )
            // 悟性100 → (100-80)/4 = 5步 → +5%
            assertEquals(0.05, detail.selfComprehensionBonus, 0.001)
            assertEquals(
                "明细合计应与总突破率一致",
                detail.total,
                detail.baseChance * (1 + detail.selfComprehensionBonus),
                0.001
            )
        }
    }
}
