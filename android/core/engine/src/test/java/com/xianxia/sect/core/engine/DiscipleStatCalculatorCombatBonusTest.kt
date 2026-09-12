package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
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
import com.xianxia.sect.core.engine.domain.disciple.calculateLifespanBreakthroughPenalty
import com.xianxia.sect.core.engine.domain.disciple.calculateLifespanCultivationPenalty
import com.xianxia.sect.core.engine.domain.disciple.calculateLifespanRemainingPercent
import com.xianxia.sect.core.engine.domain.disciple.calculateQingyunPeakCultivationSpeedBonus
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughBonusDetail
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getMasterDiscipleBreakthroughBonus
import com.xianxia.sect.core.engine.domain.disciple.getMasterDiscipleCultivationBonus
import com.xianxia.sect.core.engine.domain.disciple.getMasterDiscipleRealmGap
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment
import com.xianxia.sect.core.engine.domain.disciple.getTalentEffects

/** DiscipleStatCalculatorTest 拆分（LC>800 行）：60 个用例随 fixture 迁出，行为零变更。 */
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
            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
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
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                disciple, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun getFinalStats(
                aggregate: DiscipleAggregate,
                equipments: Map<String, EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(
                aggregate, equipments, manuals, manualProficiencies, bloodRefinementPct
            )
            override fun calculateCultivationSpeed(
                disciple: Disciple,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double,
                parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty, masterDiscipleBonus
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>,
                buildingBonus: Double,
                additionalBonus: Double,
                preachingElderBonus: Double,
                preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double,
                parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty, masterDiscipleBonus
            )
            override fun getBreakthroughChance(
                disciple: Disciple,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double,
                griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension, pillBonus,
                adBonus, griefBreakthroughPenalty, masterDiscipleBonus
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate,
                innerElderComprehension: Int,
                outerElderComprehension: Int,
                pillBonus: Double,
                adBonus: Double,
                griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension, pillBonus,
                adBonus, griefBreakthroughPenalty, masterDiscipleBonus
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
        basePhysicalAttack: Int = 20,
        baseMagicAttack: Int = 15,
        basePhysicalDefense: Int = 10,
        baseMagicDefense: Int = 8,
        baseSpeed: Int = 30,
        intelligence: Int = 50,
        charm: Int = 50,
        loyalty: Int = 50,
        comprehension: Int = 50,
        aptitude: Int = 50,
        teaching: Int = 50,
        morality: Int = 50,
        talentIds: List<String> = emptyList(),
        manualIds: List<String> = emptyList(),
        weaponId: String = "",
        armorId: String = "",
        bootsId: String = "",
        accessoryId: String = "",
        pillEffectDuration: Int = 0,
        pillHpBonus: Int = 0,
        pillMpBonus: Int = 0,
        pillPhysicalAttackBonus: Int = 0,
        pillMagicAttackBonus: Int = 0,
        pillPhysicalDefenseBonus: Int = 0,
        pillMagicDefenseBonus: Int = 0,
        pillSpeedBonus: Int = 0,
        discipleType: String = "inner",
        statusData: Map<String, String> = emptyMap(),
        spiritRootType: String = "metal"
    ): Disciple {
        return Disciple(
            realm = realm,
            realmLayer = realmLayer,
            talentIds = talentIds,
            manualIds = manualIds,
            spiritRootType = spiritRootType,
            combat = CombatAttributes(
                baseHp = baseHp,
                baseMp = baseMp,
                basePhysicalAttack = basePhysicalAttack,
                baseMagicAttack = baseMagicAttack,
                basePhysicalDefense = basePhysicalDefense,
                baseMagicDefense = baseMagicDefense,
                baseSpeed = baseSpeed
            ),
            pillEffects = PillEffects(
                pillHpBonus = pillHpBonus,
                pillMpBonus = pillMpBonus,
                pillPhysicalAttackBonus = pillPhysicalAttackBonus,
                pillMagicAttackBonus = pillMagicAttackBonus,
                pillPhysicalDefenseBonus = pillPhysicalDefenseBonus,
                pillMagicDefenseBonus = pillMagicDefenseBonus,
                pillSpeedBonus = pillSpeedBonus,
                pillEffectDuration = pillEffectDuration
            ),
            statusData = statusData
        ).copy(
            skills = SkillStats(
                intelligence = intelligence,
                charm = charm,
                loyalty = loyalty,
                comprehension = comprehension,
                aptitude = aptitude,
                teaching = teaching,
                morality = morality
            ),
            equipment = EquipmentSet(
                weaponId = weaponId,
                armorId = armorId,
                bootsId = bootsId,
                accessoryId = accessoryId
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
    fun `getTalentEffects - 无天赋返回空map`() {
        val disciple = createDisciple(talentIds = emptyList())
        val effects = DiscipleStatCalculator.getTalentEffects(disciple)
        assertNotNull(effects)
    }
    @Test
    fun `getStatsWithEquipment - 无装备时与基础属性一致`() {
        val disciple = createDisciple()
        val baseStats = DiscipleStatCalculator.getBaseStats(disciple)
        val equippedStats = DiscipleStatCalculator.getStatsWithEquipment(disciple, emptyMap())
        assertEquals(baseStats.physicalAttack, equippedStats.physicalAttack)
        assertEquals(baseStats.physicalDefense, equippedStats.physicalDefense)
    }

    // ==================== 寿命惩罚测试 ====================
    @Test
    fun `calculateLifespanRemainingPercent - 正常情况返回正确比例`() {
        val result = DiscipleStatCalculator.calculateLifespanRemainingPercent(age = 40, lifespan = 80)
        assertEquals(0.5, result, 0.0001)
    }
    @Test
    fun `calculateLifespanRemainingPercent - 寿命为0返回1无惩罚`() {
        val result = DiscipleStatCalculator.calculateLifespanRemainingPercent(age = 0, lifespan = 0)
        assertEquals(1.0, result, 0.0001)
    }
    @Test
    fun `calculateLifespanRemainingPercent - 已死亡age大于lifespan返回0`() {
        val result = DiscipleStatCalculator.calculateLifespanRemainingPercent(age = 100, lifespan = 80)
        assertEquals(0.0, result, 0.0001)
    }
    @Test
    fun `calculateLifespanCultivationPenalty - 高于阈值返回0`() {
        // 剩余50%，高于20%阈值
        val result = DiscipleStatCalculator.calculateLifespanCultivationPenalty(age = 40, lifespan = 80)
        assertEquals(0.0, result, 0.0001)
    }
    @Test
    fun `calculateLifespanCultivationPenalty - 正好在阈值返回0`() {
        // 剩余20%，等于阈值
        val result = DiscipleStatCalculator.calculateLifespanCultivationPenalty(age = 64, lifespan = 80)
        assertEquals(0.0, result, 0.0001)
    }
    @Test
    fun `calculateLifespanCultivationPenalty - 15%剩余返回0点25`() {
        // 剩余15%，低于阈值5个百分点，5*0.05=0.25
        val result = DiscipleStatCalculator.calculateLifespanCultivationPenalty(age = 68, lifespan = 80)
        assertEquals(0.25, result, 0.0001)
    }
    @Test
    fun `calculateLifespanCultivationPenalty - 0%剩余返回1点0`() {
        // 剩余0%，低于阈值20个百分点，20*0.05=1.0
        val result = DiscipleStatCalculator.calculateLifespanCultivationPenalty(age = 80, lifespan = 80)
        assertEquals(1.0, result, 0.0001)
    }
    @Test
    fun `calculateLifespanBreakthroughPenalty - 10%剩余返回0点20`() {
        // 剩余10%，低于阈值10个百分点，10*0.02=0.20
        val result = DiscipleStatCalculator.calculateLifespanBreakthroughPenalty(age = 72, lifespan = 80)
        assertEquals(0.20, result, 0.0001)
    }
    @Test
    fun `calculateLifespanBreakthroughPenalty - 高于阈值返回0`() {
        val result = DiscipleStatCalculator.calculateLifespanBreakthroughPenalty(age = 16, lifespan = 80)
        assertEquals(0.0, result, 0.0001)
    }

    // ==================== 师徒加成测试 ====================
    @Test
    fun `getMasterDiscipleRealmGap - 金丹师父加练气徒弟返回1`() {
        // 金丹=7, 练气=9, gap = max(0, 9-7-1) = 1
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 9, masterRealm = 7
        )
        assertEquals(1, gap)
    }
    @Test
    fun `getMasterDiscipleRealmGap - 金丹师父加筑基徒弟返回0`() {
        // 筑基=8, 金丹=7, gap = max(0, 8-7-1) = 0
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 8, masterRealm = 7
        )
        assertEquals(0, gap)
    }
    @Test
    fun `getMasterDiscipleRealmGap - 同境界返回0`() {
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 7, masterRealm = 7
        )
        assertEquals(0, gap)
    }
    @Test
    fun `getMasterDiscipleRealmGap - 徒弟境界高于师父返回0`() {
        // 元婴(6)徒弟 + 金丹(7)师父 → 6-7-1=-2 → 0
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 6, masterRealm = 7
        )
        assertEquals(0, gap)
    }
    @Test
    fun `getMasterDiscipleRealmGap - 元婴师父加练气徒弟返回2`() {
        // 元婴=6, 练气=9, gap = max(0, 9-6-1) = 2
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 9, masterRealm = 6
        )
        assertEquals(2, gap)
    }
    @Test
    fun `getMasterDiscipleRealmGap - 化神师父加练气徒弟返回3`() {
        // 化神=5, 练气=9, gap = max(0, 9-5-1) = 3
        val gap = DiscipleStatCalculator.getMasterDiscipleRealmGap(
            discipleRealm = 9, masterRealm = 5
        )
        assertEquals(3, gap)
    }
    @Test
    fun `getMasterDiscipleCultivationBonus - gap为1返回0点05`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleCultivationBonus(
            discipleRealm = 9, masterRealm = 7
        )
        assertEquals(0.05, bonus, 0.0001)
    }
    @Test
    fun `getMasterDiscipleCultivationBonus - gap为0返回0`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleCultivationBonus(
            discipleRealm = 8, masterRealm = 7
        )
        assertEquals(0.0, bonus, 0.0001)
    }
    @Test
    fun `getMasterDiscipleCultivationBonus - gap为2返回0点10`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleCultivationBonus(
            discipleRealm = 9, masterRealm = 6
        )
        assertEquals(0.10, bonus, 0.0001)
    }
    @Test
    fun `getMasterDiscipleBreakthroughBonus - gap为1返回0点03`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(
            discipleRealm = 9, masterRealm = 7
        )
        assertEquals(0.03, bonus, 0.0001)
    }
    @Test
    fun `getMasterDiscipleBreakthroughBonus - gap为0返回0`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(
            discipleRealm = 8, masterRealm = 7
        )
        assertEquals(0.0, bonus, 0.0001)
    }
    @Test
    fun `getMasterDiscipleBreakthroughBonus - gap为3返回0点09`() {
        val bonus = DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(
            discipleRealm = 9, masterRealm = 5
        )
        assertEquals(0.09, bonus, 0.0001)
    }
    @Test
    fun `calculateCultivationPerPhase - 师徒加成生效`() {
        val disciple = createDisciple()
        val noBonus = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple, masterDiscipleBonus = 0.0
        )
        val withBonus = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple, masterDiscipleBonus = 0.05
        )
        assertTrue("师徒加成应提高修炼速度", withBonus > noBonus)
    }
    @Test
    fun `calculateCultivationPerPhase - 师徒加成为0不影响基础值`() {
        val disciple = createDisciple()
        val speed = DiscipleStatCalculator.calculateCultivationPerPhase(
            disciple, masterDiscipleBonus = 0.0
        )
        // 默认参数即0，验证与不传参一致
        assertEquals(
            speed,
            DiscipleStatCalculator.calculateCultivationPerPhase(disciple),
            0.001
        )
    }
    @Test
    fun `getBreakthroughChance - 师徒加成增加突破率`() {
        val disciple = createDisciple(
            realm = 9, realmLayer = 1, spiritRootType = "metal"
        )
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, masterDiscipleBonus = 0.0
        )
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, masterDiscipleBonus = 0.03
        )
        assertTrue("师徒加成应增加突破率", bonusChance > baseChance)
    }
    @Test
    fun `getBreakthroughChance - 师徒加成不超过1`() {
        val disciple = createDisciple(
            realm = 9, realmLayer = 1, spiritRootType = "metal"
        )
        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple, masterDiscipleBonus = 1.0
        )
        assertTrue("突破率不应超过1, actual=$chance", chance <= 1.0)
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

    // ── 血炼加成进入战斗属性 ──
    // 血炼百分比经 getFinalStats→getBaseStats 的 bloodRefinementPct 参数计入战斗属性。
    @Test
    fun `getFinalStats - 血炼加成计入战斗属性`() {
        val disciple = createDisciple()
        val bloodRefinement = BloodRefinementPctTotal(
            discipleId = "1",
            hpBonusPct = 0.30,
            physicalAttackBonusPct = 0.50,
            physicalDefenseBonusPct = 0.40,
            speedBonusPct = 0.20
        )
        val base = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap())
        val withBr = DiscipleStatCalculator.getFinalStats(
            disciple, emptyMap(), emptyMap(), bloodRefinementPct = bloodRefinement
        )
        // realm 9 基础（无天赋/方差/层数加成）：物攻 16、物防 13、速度 15、气血 203
        assertEquals(16, base.physicalAttack)
        assertEquals(24, withBr.physicalAttack) // 16 × (1 + 0.50)
        assertEquals(13, base.physicalDefense)
        assertEquals(18, withBr.physicalDefense) // 13 × 1.4 = 18.2 → 18
        assertEquals(15, base.speed)
        assertEquals(18, withBr.speed) // 15 × 1.2
        assertEquals(203, base.maxHp)
        assertEquals(264, withBr.maxHp) // 203 × 1.3 = 263.9 → 264
    }
    @Test
    fun `getFinalStats - 血炼加成与天赋同乘区加算`() {
        val disciple = createDisciple(talentIds = listOf("r1_bat_hp"))
        // r1_bat_hp 天赋（体健）提供 maxHp +10%（与血炼 hpBonusPct 同乘区加算）
        val bloodRefinement = BloodRefinementPctTotal(discipleId = "1", hpBonusPct = 0.30)
        val withTalent = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap())
        val withTalentAndBr = DiscipleStatCalculator.getFinalStats(
            disciple, emptyMap(), emptyMap(), bloodRefinementPct = bloodRefinement
        )
        // 203 × (1 + 0.10) = 223.3 → 223（仅天赋）
        assertEquals(223, withTalent.maxHp)
        // 203 × (1 + 0.10 + 0.30) = 284.2 → 284（天赋+血炼同乘区加算，而非乘算 203×1.1×1.3=290）
        assertEquals(284, withTalentAndBr.maxHp)
    }
    @Test
    fun `getFinalStats - 无血炼时行为不变`() {
        val disciple = createDisciple(realm = 7, realmLayer = 3)
        val withNull = DiscipleStatCalculator.getFinalStats(
            disciple, emptyMap(), emptyMap(), bloodRefinementPct = null
        )
        val withDefault = DiscipleStatCalculator.getFinalStats(disciple, emptyMap(), emptyMap())
        assertEquals("null 与默认参数应完全一致", withDefault, withNull)
    }

    // ==================== 生产 Flat 天赋加成（3 个生产 key 接线） ====================
    // computeBaseStats 消费 spiritPlantingFlat/artifactRefiningFlat/pillRefiningFlat，
    // 洗出"青帝(灵植+18)"等生产 Flat 天赋后面板对应属性立即生效。
    @Test
    fun `getBaseStats - 青帝灵植flat加18`() {
        val raw = DiscipleStatCalculator.getBaseStats(createDisciple()).spiritPlanting
        val stats = DiscipleStatCalculator.getBaseStats(
            createDisciple(talentIds = listOf("r3_base_plant"))
        )
        assertEquals("青帝(灵植+18)应计入基础属性", raw + 18, stats.spiritPlanting)
    }
    @Test
    fun `getBaseStats - 天工炼器flat加18`() {
        val raw = DiscipleStatCalculator.getBaseStats(createDisciple()).artifactRefining
        val stats = DiscipleStatCalculator.getBaseStats(
            createDisciple(talentIds = listOf("r3_base_arti"))
        )
        assertEquals("天工(炼器+18)应计入基础属性", raw + 18, stats.artifactRefining)
    }
    @Test
    fun `getBaseStats - 天丹炼丹flat加18`() {
        val raw = DiscipleStatCalculator.getBaseStats(createDisciple()).pillRefining
        val stats = DiscipleStatCalculator.getBaseStats(
            createDisciple(talentIds = listOf("r3_base_pill"))
        )
        assertEquals("天丹(炼丹+18)应计入基础属性", raw + 18, stats.pillRefining)
    }
    @Test
    fun `getBaseStats - 负面flat减6`() {
        val rawSp = DiscipleStatCalculator.getBaseStats(createDisciple()).spiritPlanting
        val rawAr = DiscipleStatCalculator.getBaseStats(createDisciple()).artifactRefining
        val rawPi = DiscipleStatCalculator.getBaseStats(createDisciple()).pillRefining
        val stats = DiscipleStatCalculator.getBaseStats(
            createDisciple(talentIds = listOf("neg_base_craft"))
        )
        assertEquals("百艺生疏(炼器/炼丹/种植-6)应计入基础属性",
            rawAr - 6, stats.artifactRefining)
        assertEquals("百艺生疏(炼器/炼丹/种植-6)应计入基础属性",
            rawPi - 6, stats.pillRefining)
        assertEquals("百艺生疏(炼器/炼丹/种植-6)应计入基础属性",
            rawSp - 6, stats.spiritPlanting)
    }
    @Test
    fun `getBaseStats - 三生产flat同生效互不覆盖`() {
        val raw = DiscipleStatCalculator.getBaseStats(createDisciple())
        val stats = DiscipleStatCalculator.getBaseStats(
            createDisciple(talentIds = listOf("r2_base_arti", "r2_base_pill", "r2_base_plant"))
        )
        assertEquals(raw.spiritPlanting + 10, stats.spiritPlanting)
        assertEquals(raw.artifactRefining + 10, stats.artifactRefining)
        assertEquals(raw.pillRefining + 10, stats.pillRefining)
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

    // ── 资质 → 修炼速度乘区（80 基准每点+1% 最多+40%）──
    @Test
    fun `calculateCultivationPerPhase - 资质80无加成`() {
        val disciple = createDisciple(aptitude = 80)
        val speed = DiscipleStatCalculator.calculateCultivationPerPhase(disciple)
        val base = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 50))
        assertEquals(base, speed, 0.001)
    }
    @Test
    fun `calculateCultivationPerPhase - 资质81加成1percent`() {
        val disciple = createDisciple(aptitude = 81)
        val speed = DiscipleStatCalculator.calculateCultivationPerPhase(disciple)
        val base = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 50))
        assertEquals(base * 1.01, speed, 0.001)
    }
    @Test
    fun `calculateCultivationPerPhase - 资质120加成40percent封顶`() {
        val disciple = createDisciple(aptitude = 120)
        val speed = DiscipleStatCalculator.calculateCultivationPerPhase(disciple)
        val base = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 50))
        assertEquals(base * 1.40, speed, 0.001)
    }
    @Test
    fun `calculateCultivationPerPhase - 资质200与10000均封顶40percent`() {
        val speed200 = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 200))
        val speed10000 = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 10000))
        val base = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 50))
        assertEquals(base * 1.40, speed200, 0.001)
        assertEquals("篡改防御：超大值钳 0.40", base * 1.40, speed10000, 0.001)
    }
    @Test
    fun `calculateCultivationPerPhase - 资质低于80与负值无加成`() {
        val low = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 79))
        val negative = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = -100))
        val base = DiscipleStatCalculator.calculateCultivationPerPhase(createDisciple(aptitude = 50))
        assertEquals("资质79无加成", base, low, 0.001)
        assertEquals("篡改防御：负值归零", base, negative, 0.001)
    }
    @Test
    fun `getBaseStats - 资质进入DiscipleStats`() {
        val stats = DiscipleStatCalculator.getBaseStats(createDisciple(aptitude = 120))
        assertEquals(120, stats.aptitude)
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
    @Test
    fun `DiscipleStats plus - 资质字段叠加不清零`() {
        val base = DiscipleStats(aptitude = 120)
        val bonus = DiscipleStats(intelligence = 10)
        val sum = base + bonus
        assertEquals("plus 漏加 aptitude 会清零", 120, sum.aptitude)
    }
}
