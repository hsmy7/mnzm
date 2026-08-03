package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.util.NameService
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 DiscipleFactory 统一构造 — 三处构造站点（recruitDisciple /
 * refreshRecruitList / createChild）通过不同 DiscipleSeed 得到一致的
 * 六段逻辑（variance / comprehension / skills / baseStats / lifespan /
 * talentIds）。
 */
class DiscipleFactoryTest {

    private val factory = DiscipleFactory()

    // ---- 辅助函数 ----

    private fun newSeed(
        id: String = "test-001",
        gender: String = "男",
        spiritRootType: String = "火",
        age: Int = 18,
        realmLayer: Int = 1
    ): DiscipleFactory.DiscipleSeed {
        return DiscipleFactory.DiscipleSeed(
            id = id,
            gender = gender,
            nameResult = NameService.NameResult("测试", "弟子"),
            spiritRootType = spiritRootType,
            age = age,
            realmLayer = realmLayer,
            social = SocialData(),
            nextInt = { from, _ -> from } // 确定性：总是取最小值
        )
    }

    // ---- 方差 / 悟性 / 技能 ----

    @Test
    fun `create - variance is deterministic given nextInt`() {
        val d = factory.create(newSeed())
        // nextInt 固定返回 from → Box-Muller z≈4.291 → 截断至 50
        assertEquals(50, d.combat.hpVariance)
        assertEquals(50, d.combat.mpVariance)
        assertEquals(50, d.combat.physicalAttackVariance)
        assertEquals(50, d.combat.magicAttackVariance)
        assertEquals(50, d.combat.physicalDefenseVariance)
        assertEquals(50, d.combat.magicDefenseVariance)
        assertEquals(50, d.combat.speedVariance)
    }

    @Test
    fun `create - skills are deterministic given nextInt`() {
        val d = factory.create(newSeed())
        // 同 gaussianInt 逻辑：u1=0.0001, u2=0.0, z≈4.291
        // skill = round(4.291*16.5 + 50.5) = round(121.3) = 121 -> 截断至 100
        assertEquals(100, d.skills.intelligence)
        assertEquals(100, d.skills.charm)
        assertEquals(100, d.skills.loyalty)
        assertEquals(100, d.skills.morality)
        assertEquals(100, d.skills.artifactRefining)
        assertEquals(100, d.skills.pillRefining)
        assertEquals(100, d.skills.spiritPlanting)
        assertEquals(100, d.skills.mining)
        assertEquals(100, d.skills.teaching)
    }

    @Test
    fun `create - single spirit root yields high comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火"))
        // nextInt 固定 from=80，单灵根 from=80 → comprehension=80
        assertEquals(80, d.skills.comprehension)
    }

    @Test
    fun `create - two spirit roots yield mid comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水"))
        // nextInt 固定 from=60，双灵根 from=60 → comprehension=60
        assertEquals(60, d.skills.comprehension)
    }

    @Test
    fun `create - three spirit roots yield lower comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木"))
        assertEquals(40, d.skills.comprehension)
    }

    @Test
    fun `create - four spirit roots yield minimal comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木,金"))
        assertEquals(20, d.skills.comprehension)
    }

    @Test
    fun `create - five spirit roots yield worst comprehension`() {
        val d = factory.create(newSeed(spiritRootType = "火,水,木,金,土"))
        assertEquals(1, d.skills.comprehension)
    }

    // ---- 基础属性（calculateBaseStatsWithVariance） ----

    @Test
    fun `create - baseStats are populated`() {
        val d = factory.create(newSeed())
        assertTrue("baseHp should be > 0", d.combat.baseHp > 0)
        assertTrue("baseMp should be > 0", d.combat.baseMp > 0)
        assertTrue("basePhysicalAttack should be > 0", d.combat.basePhysicalAttack > 0)
        assertTrue("baseMagicAttack should be > 0", d.combat.baseMagicAttack > 0)
        assertTrue("basePhysicalDefense should be > 0", d.combat.basePhysicalDefense > 0)
        assertTrue("baseMagicDefense should be > 0", d.combat.baseMagicDefense > 0)
        assertTrue("baseSpeed should be > 0", d.combat.baseSpeed > 0)
    }

    // ---- 寿命 ----

    @Test
    fun `create - lifespan is positive`() {
        val d = factory.create(newSeed())
        assertTrue("lifespan should be > 0", d.lifespan > 0)
    }

    // ---- 天赋 ----

    @Test
    fun `create - disciple has valid id`() {
        val d = factory.create(newSeed())
        assertTrue("id should not be blank", d.id.isNotBlank())
        assertEquals("男", d.gender)
        assertEquals(18, d.age)
    }

    // ---- 三站一致性 ----

    @Test
    fun `create - deterministic fields match given seed`() {
        val seed = newSeed()
        val d1 = factory.create(seed)
        val d2 = factory.create(seed)
        // nextInt 确定性字段
        assertEquals(d1.gender, d2.gender)
        assertEquals(d1.age, d2.age)
        assertEquals(d1.realm, d2.realm)
        assertEquals(d1.realmLayer, d2.realmLayer)
        assertEquals(d1.spiritRootType, d2.spiritRootType)
        assertEquals(d1.skills.comprehension, d2.skills.comprehension)
        assertEquals(d1.combat.hpVariance, d2.combat.hpVariance)
    }

    @Test
    fun `create - different seeds produce different disciples`() {
        val d1 = factory.create(newSeed(id = "a", spiritRootType = "火"))
        val d2 = factory.create(newSeed(id = "b", spiritRootType = "火,水"))
        assertNotEquals(d1.skills.comprehension, d2.skills.comprehension)
    }

    // ---- 边界 ----

    @Test
    fun `create - female gender`() {
        val d = factory.create(newSeed(gender = "女"))
        assertEquals("女", d.gender)
    }

    @Test
    fun `create - age is preserved`() {
        val d = factory.create(newSeed(age = 25))
        assertEquals(25, d.age)
    }

    @Test
    fun `create - realmLayer is preserved`() {
        val d = factory.create(newSeed(realmLayer = 3))
        assertEquals(3, d.realmLayer)
    }

    // ---- 分布验证 ----

    @Test
    fun `create - variance center near zero with real random`() {
        val values = mutableListOf<Int>()
        val kotlinRng = kotlin.random.Random
        repeat(1000) {
            val seed = DiscipleFactory.DiscipleSeed(
                id = "dist-test-$it",
                gender = "男",
                nameResult = NameService.NameResult("测试", "弟子"),
                spiritRootType = "火",
                age = 18,
                realmLayer = 1,
                social = SocialData(),
                nextInt = { from, until -> from + kotlinRng.nextInt(until - from) }
            )
            values.add(factory.create(seed).combat.hpVariance)
        }
        val mean = values.average()
        // 均值应接近0（方差[-50,50]的正态分布）
        assertTrue("Variance mean should be near 0: $mean", mean in -10.0..10.0)
        // 至少70%的值落在[-30,30]范围内（约2-sigma）
        val withinMid = values.count { it in -30..30 }
        assertTrue("Less than 70% in [-30,30]: ${withinMid}/1000", withinMid >= 700)
    }
}
