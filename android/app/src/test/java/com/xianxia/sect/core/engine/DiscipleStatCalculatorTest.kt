package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.CombatAttributes
import org.junit.Assert.*
import org.junit.Test

class DiscipleStatCalculatorTest {

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
        ).copyWith(
            intelligence = intelligence,
            charm = charm,
            loyalty = loyalty,
            comprehension = comprehension,
            teaching = teaching,
            morality = morality,
            weaponId = weaponId,
            armorId = armorId,
            bootsId = bootsId,
            accessoryId = accessoryId,
            discipleType = discipleType
        )
    }

    @Test
    fun `getBaseStats - 炼气期1层基础属性`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1)
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        assertTrue(stats.maxHp > 0)
        assertTrue(stats.maxMp > 0)
        assertTrue(stats.physicalAttack > 0)
        assertTrue(stats.physicalDefense > 0)
        assertTrue(stats.magicAttack > 0)
        assertTrue(stats.magicDefense > 0)
        assertTrue(stats.speed > 0)
    }

    @Test
    fun `getBaseStats - 更高境界属性更高`() {
        val low = createDisciple(realm = 9, realmLayer = 1)
        val high = createDisciple(realm = 7, realmLayer = 1)
        val lowStats = DiscipleStatCalculator.getBaseStats(low)
        val highStats = DiscipleStatCalculator.getBaseStats(high)
        assertTrue("高境界HP应更高", highStats.maxHp > lowStats.maxHp)
        assertTrue("高境界攻击应更高", highStats.physicalAttack > lowStats.physicalAttack)
    }

    @Test
    fun `getBaseStats - 同境界更高层数属性更高`() {
        val layer1 = createDisciple(realm = 9, realmLayer = 1)
        val layer9 = createDisciple(realm = 9, realmLayer = 9)
        val stats1 = DiscipleStatCalculator.getBaseStats(layer1)
        val stats9 = DiscipleStatCalculator.getBaseStats(layer9)
        assertTrue("高层数HP应更高", stats9.maxHp > stats1.maxHp)
    }

    @Test
    fun `getBaseStats - 丹药加成不影响基础属性`() {
        val noPill = createDisciple()
        val withPill = createDisciple(pillPhysicalAttackBonus = 20)
        val normalStats = DiscipleStatCalculator.getBaseStats(noPill)
        val boostedStats = DiscipleStatCalculator.getBaseStats(withPill)
        assertEquals("丹药加成不影响getBaseStats", normalStats.physicalAttack, boostedStats.physicalAttack)
    }

    @Test
    fun `getFinalStats - 丹药加成在最终属性中生效`() {
        val noPill = createDisciple(pillEffectDuration = 0)
        val withPill = createDisciple(pillPhysicalAttackBonus = 50, pillEffectDuration = 3)
        val normalStats = DiscipleStatCalculator.getFinalStats(noPill, emptyMap(), emptyMap())
        val boostedStats = DiscipleStatCalculator.getFinalStats(withPill, emptyMap(), emptyMap())
        assertTrue("丹药加成应在最终属性中生效", boostedStats.physicalAttack > normalStats.physicalAttack)
    }

    @Test
    fun `getFinalStats - 丹药持续时间为0时不生效`() {
        val noPill = createDisciple(pillEffectDuration = 0)
        val withPillButExpired = createDisciple(pillPhysicalAttackBonus = 50, pillEffectDuration = 0)
        val normalStats = DiscipleStatCalculator.getFinalStats(noPill, emptyMap(), emptyMap())
        val expiredStats = DiscipleStatCalculator.getFinalStats(withPillButExpired, emptyMap(), emptyMap())
        assertEquals("丹药持续时间为0不应生效", normalStats.physicalAttack, expiredStats.physicalAttack)
    }

    @Test
    fun `calculateCultivationSpeed - 基础修炼速度为正`() {
        val disciple = createDisciple()
        val speed = DiscipleStatCalculator.calculateCultivationSpeed(disciple)
        assertTrue("修炼速度应为正数", speed > 0)
    }

    @Test
    fun `calculateCultivationSpeed - 高悟性修炼更快`() {
        val lowComp = createDisciple(comprehension = 30)
        val highComp = createDisciple(comprehension = 90)
        val lowSpeed = DiscipleStatCalculator.calculateCultivationSpeed(lowComp)
        val highSpeed = DiscipleStatCalculator.calculateCultivationSpeed(highComp)
        assertTrue("高悟性应修炼更快", highSpeed > lowSpeed)
    }

    @Test
    fun `calculateCultivationSpeed - 建筑加成`() {
        val disciple = createDisciple()
        val noBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, buildingBonus = 1.0)
        val withBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, buildingBonus = 1.5)
        assertTrue("建筑加成应提高修炼速度", withBonus > noBonus)
    }

    @Test
    fun `calculateCultivationSpeed - 额外加成`() {
        val disciple = createDisciple()
        val noBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, additionalBonus = 0.0)
        val withBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, additionalBonus = 0.5)
        assertTrue("额外加成应提高修炼速度", withBonus > noBonus)
    }

    @Test
    fun `calculateCultivationSpeed - 传道长老加成`() {
        val disciple = createDisciple()
        val noBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, preachingElderBonus = 0.0)
        val withBonus = DiscipleStatCalculator.calculateCultivationSpeed(disciple, preachingElderBonus = 0.3)
        assertTrue("传道长老加成应提高修炼速度", withBonus > noBonus)
    }

    @Test
    fun `calculateCultivationSpeed - 最低为1`() {
        val disciple = createDisciple()
        val speed = DiscipleStatCalculator.calculateCultivationSpeed(disciple)
        assertTrue("修炼速度最低为1", speed >= 1.0)
    }

    @Test
    fun `getBreakthroughChance - 基础突破概率在合理范围`() {
        val disciple = createDisciple(realm = 9, realmLayer = 9)
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertTrue("突破概率应>0", chance > 0)
        assertTrue("突破概率应<=1", chance <= 1.0)
    }

    @Test
    fun `getBreakthroughChance - 高境界突破更难`() {
        val lowRealm = createDisciple(realm = 8, realmLayer = 1)
        val highRealm = createDisciple(realm = 4, realmLayer = 1)
        val lowChance = DiscipleStatCalculator.getBreakthroughChance(lowRealm)
        val highChance = DiscipleStatCalculator.getBreakthroughChance(highRealm)
        assertTrue("高境界突破应更难", lowChance > highChance)
    }

    @Test
    fun `getBreakthroughChance - 不满足神魂要求返回0`() {
        val disciple = createDisciple(realm = 5, realmLayer = 9)
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 满足神魂要求返回基础概率`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1)
        val baseChance = GameConfig.Realm.getBreakthroughChance(9, 1)
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(baseChance, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 内门长老悟性加成`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1)
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(disciple, innerElderComprehension = 90)
        assertTrue("长老加成应增加突破概率", bonusChance > baseChance)
    }

    @Test
    fun `getBreakthroughChance - 内门长老悟性低于80无加成`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1)
        val baseChance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val bonusChance = DiscipleStatCalculator.getBreakthroughChance(disciple, innerElderComprehension = 70)
        assertEquals(baseChance, bonusChance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - realm为0不再返回0`() {
        val disciple = createDisciple(realm = 0, realmLayer = 1)
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertTrue("仙人突破概率应>0", chance > 0)
    }

    @Test
    fun `getBreakthroughChance - 突破概率不超过1`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1)
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple, pillBonus = 10.0)
        assertTrue("突破概率不应超过1", chance <= 1.0)
    }

    @Test
    fun `getBreakthroughChance - 单灵根炼气突破概率为1`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(1.00, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 五灵根炼气突破概率也是1`() {
        val disciple = createDisciple(realm = 9, realmLayer = 1, spiritRootType = "metal,wood,water,fire,earth")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(1.00, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 双灵根筑基突破概率0点9`() {
        val disciple = createDisciple(realm = 8, realmLayer = 1, spiritRootType = "metal,wood")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.90, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 三灵根金丹突破概率0点75`() {
        val disciple = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal,wood,water")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.75, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 单灵根金丹突破概率0点95`() {
        val disciple = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.95, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 单灵根元婴突破概率0点85`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.85, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 单灵根化神突破概率0点75`() {
        val disciple = createDisciple(realm = 5, realmLayer = 1, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.75, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 四灵根元婴突破概率0点25`() {
        val disciple = createDisciple(realm = 6, realmLayer = 1, spiritRootType = "metal,wood,water,fire")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.25, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 五灵根化神突破概率0点08`() {
        val disciple = createDisciple(realm = 5, realmLayer = 1, spiritRootType = "metal,wood,water,fire,earth")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.08, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 灵根越少突破概率越高`() {
        val singleRoot = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal")
        val doubleRoot = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal,wood")
        val tripleRoot = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal,wood,water")
        val quadRoot = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal,wood,water,fire")
        val pentaRoot = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal,wood,water,fire,earth")

        val singleChance = DiscipleStatCalculator.getBreakthroughChance(singleRoot)
        val doubleChance = DiscipleStatCalculator.getBreakthroughChance(doubleRoot)
        val tripleChance = DiscipleStatCalculator.getBreakthroughChance(tripleRoot)
        val quadChance = DiscipleStatCalculator.getBreakthroughChance(quadRoot)
        val pentaChance = DiscipleStatCalculator.getBreakthroughChance(pentaRoot)

        assertTrue("单灵根>双灵根", singleChance > doubleChance)
        assertTrue("双灵根>三灵根", doubleChance > tripleChance)
        assertTrue("三灵根>四灵根", tripleChance > quadChance)
        assertTrue("四灵根>五灵根", quadChance > pentaChance)
    }

    @Test
    fun `getBreakthroughChance - 五灵根合体突破概率为0`() {
        val disciple = createDisciple(realm = 3, realmLayer = 1, spiritRootType = "metal,wood,water,fire,earth")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.0, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 单灵根仙人突破概率0点06`() {
        val disciple = createDisciple(realm = 0, realmLayer = 1, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.06, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 筑基9层突破概率等于金丹1层`() {
        val disciple9 = createDisciple(realm = 8, realmLayer = 9, spiritRootType = "metal")
        val disciple1 = createDisciple(realm = 7, realmLayer = 1, spiritRootType = "metal")
        val chance9 = DiscipleStatCalculator.getBreakthroughChance(disciple9)
        val chance1 = DiscipleStatCalculator.getBreakthroughChance(disciple1)
        assertEquals(chance1, chance9, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 同境界层数越高突破概率越低`() {
        val layer1 = createDisciple(realm = 8, realmLayer = 1, spiritRootType = "metal,wood,water")
        val layer5 = createDisciple(realm = 8, realmLayer = 5, spiritRootType = "metal,wood,water")
        val layer9 = createDisciple(realm = 8, realmLayer = 9, spiritRootType = "metal,wood,water")
        val chance1 = DiscipleStatCalculator.getBreakthroughChance(layer1)
        val chance5 = DiscipleStatCalculator.getBreakthroughChance(layer5)
        val chance9 = DiscipleStatCalculator.getBreakthroughChance(layer9)
        assertTrue("1层概率>=5层", chance1 >= chance5)
        assertTrue("5层概率>=9层", chance5 >= chance9)
    }

    @Test
    fun `getBreakthroughChance - 五灵根筑基9层突破概率0点32`() {
        val disciple = createDisciple(realm = 8, realmLayer = 9, spiritRootType = "metal,wood,water,fire,earth")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.32, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 单灵根炼气9层突破概率为1`() {
        val disciple = createDisciple(realm = 9, realmLayer = 9, spiritRootType = "metal")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(1.00, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 五灵根炼气9层突破概率0点45`() {
        val disciple = createDisciple(realm = 9, realmLayer = 9, spiritRootType = "metal,wood,water,fire,earth")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        assertEquals(0.45, chance, 0.001)
    }

    @Test
    fun `getBreakthroughChance - 双灵根筑基5层突破概率平滑过渡`() {
        val disciple = createDisciple(realm = 8, realmLayer = 5, spiritRootType = "metal,wood")
        val chance = DiscipleStatCalculator.getBreakthroughChance(disciple)
        val baseChance = GameConfig.Realm.getBreakthroughChance(8, 2, 1)
        val nextChance = GameConfig.Realm.getBreakthroughChance(8, 2, 9)
        assertTrue("5层概率应在1层和9层之间", chance <= baseChance && chance >= nextChance)
    }

    @Test
    fun `calculateQingyunPeakBonus - 外门弟子无加成`() {
        val disciple = createDisciple(discipleType = "outer")
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(disciple)
        assertEquals(0.0, bonus, 0.001)
    }

    @Test
    fun `calculateQingyunPeakBonus - 内门弟子有长老加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val elder = createDisciple(discipleType = "inner", realm = 9).copyWith(
            teaching = 90
        )
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingElder = elder
        )
        assertTrue("内门弟子有长老加成, bonus=$bonus", bonus > 0)
    }

    @Test
    fun `calculateQingyunPeakBonus - 长老教学低于80无加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val elder = createDisciple(discipleType = "inner", realm = 9).copyWith(
            teaching = 70
        )
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingElder = elder
        )
        assertEquals(0.0, bonus, 0.001)
    }

    @Test
    fun `calculateQingyunPeakBonus - 死亡长老无加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val elder = createDisciple(discipleType = "inner", realm = 9).copyWith(
            isAlive = false,
            teaching = 90
        )
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingElder = elder
        )
        assertEquals(0.0, bonus, 0.001)
    }

    @Test
    fun `calculateQingyunPeakBonus - 弟子境界高于长老无加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 5)
        val elder = createDisciple(discipleType = "inner", realm = 9).copyWith(
            teaching = 90
        )
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingElder = elder
        )
        assertEquals(0.0, bonus, 0.001)
    }

    @Test
    fun `calculateQingyunPeakBonus - 执事传道加成`() {
        val disciple = createDisciple(discipleType = "inner", realm = 9)
        val master = createDisciple(discipleType = "inner", realm = 9).copyWith(
            teaching = 85
        )
        val bonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
            disciple,
            qingyunPreachingMasters = listOf(master)
        )
        assertTrue("执事传道应有加成", bonus > 0)
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
}
