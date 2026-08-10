package com.xianxia.sect.core.profession

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 炼丹师/锻造师职业规则纯逻辑测试。
 *
 * 覆盖：等级→品阶映射、三重晋升门槛（次数/境界/属性）、低阶不充数、封顶、职业显示名。
 */
class ProfessionRulesTest {

    private fun disciple(
        realm: Int = 9,
        skills: SkillStats = SkillStats()
    ) = Disciple(name = "测试弟子", realm = realm, skills = skills)

    // ---- 等级→品阶映射 ----

    @Test
    fun `maxCraftableTier - level N maps to tier N+1`() {
        assertEquals(1, ProfessionRules.maxCraftableTier(0))
        assertEquals(2, ProfessionRules.maxCraftableTier(1))
        assertEquals(3, ProfessionRules.maxCraftableTier(2))
        assertEquals(4, ProfessionRules.maxCraftableTier(3))
        assertEquals(5, ProfessionRules.maxCraftableTier(4))
        assertEquals(6, ProfessionRules.maxCraftableTier(5))
    }

    @Test
    fun `maxCraftableTier - out of range clamps to valid range`() {
        assertEquals(1, ProfessionRules.maxCraftableTier(-5))
        assertEquals(6, ProfessionRules.maxCraftableTier(99))
    }

    @Test
    fun `canCraftTier - no profession crafts tier1 only`() {
        assertTrue(ProfessionRules.canCraftTier(0, 1))
        assertFalse(ProfessionRules.canCraftTier(0, 2))
        assertFalse(ProfessionRules.canCraftTier(0, 6))
    }

    @Test
    fun `canCraftTier - master level crafts up to its tier`() {
        assertTrue(ProfessionRules.canCraftTier(3, 4))
        assertFalse(ProfessionRules.canCraftTier(3, 5))
    }

    @Test
    fun `canCraftTier - grand master crafts tier6`() {
        assertTrue(ProfessionRules.canCraftTier(5, 6))
        assertTrue(ProfessionRules.canCraftTier(5, 1))
    }

    // ---- 晋升门槛表 ----

    @Test
    fun `promotionSuccessRequirement - level0 is 1 first success`() {
        assertEquals(1, ProfessionRules.promotionSuccessRequirement(0))
        assertEquals(200, ProfessionRules.promotionSuccessRequirement(1))
        assertEquals(500, ProfessionRules.promotionSuccessRequirement(2))
        assertEquals(800, ProfessionRules.promotionSuccessRequirement(3))
        assertEquals(800, ProfessionRules.promotionSuccessRequirement(4))
    }

    @Test
    fun `promotionSuccessRequirement - max level is not promotable`() {
        assertEquals(Int.MAX_VALUE, ProfessionRules.promotionSuccessRequirement(5))
    }

    @Test
    fun `promotionRealmRequirement - level4 to 5 requires heti realm 3`() {
        assertEquals(9, ProfessionRules.promotionRealmRequirement(0))
        assertEquals(7, ProfessionRules.promotionRealmRequirement(1))
        assertEquals(6, ProfessionRules.promotionRealmRequirement(2))
        assertEquals(5, ProfessionRules.promotionRealmRequirement(3))
        assertEquals(3, ProfessionRules.promotionRealmRequirement(4))
    }

    @Test
    fun `promotionSkillRequirement - cap at 110`() {
        assertEquals(40, ProfessionRules.promotionSkillRequirement(0))
        assertEquals(55, ProfessionRules.promotionSkillRequirement(1))
        assertEquals(70, ProfessionRules.promotionSkillRequirement(2))
        assertEquals(90, ProfessionRules.promotionSkillRequirement(3))
        assertEquals(110, ProfessionRules.promotionSkillRequirement(4))
    }

    // ---- 职业显示名 ----

    @Test
    fun `displayName - alchemy profession names`() {
        assertEquals("无职业", ProfessionRules.displayName(0, true))
        assertEquals("炼丹师", ProfessionRules.displayName(1, true))
        assertEquals("炼丹大师", ProfessionRules.displayName(2, true))
        assertEquals("炼丹宗师", ProfessionRules.displayName(3, true))
        assertEquals("炼丹大宗师", ProfessionRules.displayName(4, true))
        assertEquals("丹圣", ProfessionRules.displayName(5, true))
    }

    @Test
    fun `displayName - forge profession names`() {
        assertEquals("无职业", ProfessionRules.displayName(0, false))
        assertEquals("炼器师", ProfessionRules.displayName(1, false))
        assertEquals("炼器大师", ProfessionRules.displayName(2, false))
        assertEquals("炼器宗师", ProfessionRules.displayName(3, false))
        assertEquals("炼器大宗师", ProfessionRules.displayName(4, false))
        assertEquals("器圣", ProfessionRules.displayName(5, false))
    }

    // ---- 晋升进度结算（applyPromotionProgress）----

    @Test
    fun `promotion - first successful tier1 craft promotes level0 to 1`() {
        val d = disciple(realm = 9, skills = SkillStats(pillRefining = 50))
        val result = d.applyPromotionProgress(recipeTier = 1, isAlchemy = true)

        assertTrue(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(1, result.disciple.skills.alchemyLevel)
        assertEquals(0, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - lower tier success does not count toward promotion`() {
        // level 1 最高阶为 tier 2，炼 tier 1 不计数
        val d = disciple(
            realm = 9,
            skills = SkillStats(pillRefining = 50, alchemyLevel = 1)
        )
        val result = d.applyPromotionProgress(recipeTier = 1, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(0, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - success on current highest tier accumulates count`() {
        // level 1 炼 tier 2（当前解锁最高阶），需 200 次才晋升
        val d = disciple(
            realm = 7,
            skills = SkillStats(pillRefining = 55, alchemyLevel = 1, alchemyPromotionCount = 198)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(199, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - realm gate blocks promotion but count continues`() {
        // 次数已达标（200 次）但境界不够（炼气 realm 9 > 金丹 7）
        val d = disciple(
            realm = 9,
            skills = SkillStats(pillRefining = 55, alchemyLevel = 1, alchemyPromotionCount = 199)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(200, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - skill gate blocks promotion but count continues`() {
        // 次数与境界都够但炼丹属性不足（55 门槛，实际 40）
        val d = disciple(
            realm = 7,
            skills = SkillStats(pillRefining = 40, alchemyLevel = 1, alchemyPromotionCount = 199)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(200, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - all gates met promotes and resets count`() {
        val d = disciple(
            realm = 7,
            skills = SkillStats(pillRefining = 55, alchemyLevel = 1, alchemyPromotionCount = 199)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = true)

        assertTrue(result.promoted)
        assertEquals(2, result.newLevel)
        assertEquals(2, result.disciple.skills.alchemyLevel)
        assertEquals(0, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - max level is capped and stops counting`() {
        val d = disciple(
            realm = 3,
            skills = SkillStats(pillRefining = 110, alchemyLevel = 5, alchemyPromotionCount = 0)
        )
        val result = d.applyPromotionProgress(recipeTier = 6, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(5, result.newLevel)
        assertEquals(0, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - forge path mirrors alchemy with forgeLevel`() {
        val d = disciple(
            realm = 9,
            skills = SkillStats(artifactRefining = 50)
        )
        val result = d.applyPromotionProgress(recipeTier = 1, isAlchemy = false)

        assertTrue(result.promoted)
        assertEquals(1, result.newLevel)
        assertEquals(1, result.disciple.skills.forgeLevel)
        assertEquals(0, result.disciple.skills.alchemyLevel)
    }

    @Test
    fun `promotion - alchemy count does not leak into forge count`() {
        val d = disciple(
            realm = 7,
            skills = SkillStats(pillRefining = 55, artifactRefining = 40, alchemyLevel = 1, alchemyPromotionCount = 199)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = false)

        // 炼器侧 level 0：tier 2 超出最高阶 tier 1，不计数
        assertFalse(result.promoted)
        assertEquals(0, result.disciple.skills.forgeLevel)
        assertEquals(199, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - high tier on no profession never counts`() {
        val d = disciple(realm = 9, skills = SkillStats(pillRefining = 50))
        val result = d.applyPromotionProgress(recipeTier = 6, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(0, result.disciple.skills.alchemyLevel)
        assertEquals(0, result.disciple.skills.alchemyPromotionCount)
    }

    @Test
    fun `promotion - count at Int MAX_VALUE does not overflow`() {
        // 对抗性审查：存档篡改/异常累计使计数达 Int.MAX_VALUE 时，
        // +1 不得溢出为负数（旧实现直接 +1 溢出成负，晋升判定被永久绕过）
        val d = disciple(
            realm = 9, // 境界不满足晋升 → 只累加不晋升，走溢出路径
            skills = SkillStats(pillRefining = 55, alchemyLevel = 1, alchemyPromotionCount = Int.MAX_VALUE)
        )
        val result = d.applyPromotionProgress(recipeTier = 2, isAlchemy = true)

        assertFalse(result.promoted)
        assertEquals(Int.MAX_VALUE, result.disciple.skills.alchemyPromotionCount)
        assertEquals(1, result.disciple.skills.alchemyLevel)
    }

    // ---- 职业等级详情列表（等级详情弹窗数据源）----

    @Test
    fun `professionLevelInfos - 炼丹系 6 级名称与可炼品阶正确`() {
        val infos = professionLevelInfos(isAlchemy = true)
        assertEquals(6, infos.size)
        assertEquals(
            listOf("无职业", "炼丹师", "炼丹大师", "炼丹宗师", "炼丹大宗师", "丹圣"),
            infos.map { it.name }
        )
        assertEquals(
            listOf("凡品", "灵品", "宝品", "玄品", "地品", "天品"),
            infos.map { it.maxTierName }
        )
    }

    @Test
    fun `professionLevelInfos - 炼器系名称正确`() {
        assertEquals(
            listOf("无职业", "炼器师", "炼器大师", "炼器宗师", "炼器大宗师", "器圣"),
            professionLevelInfos(isAlchemy = false).map { it.name }
        )
    }

    @Test
    fun `professionLevelInfos - 晋升要求含次数境界属性三重门槛`() {
        val infos = professionLevelInfos(isAlchemy = true)
        // 等级 0→1：凡品成功 1 次、境界不低于炼气（realm 9）、炼丹不低于 40
        assertEquals(
            "成功炼制 1 次（最高阶）；境界不低于炼气；炼丹 不低于 40",
            infos[0].promotionRequirement
        )
        // 等级 1→2：200 次、金丹（realm 7）、炼丹 55
        val lvl1 = infos[1].promotionRequirement!!
        assertTrue(lvl1.contains("200"))
        assertTrue(lvl1.contains("金丹"))
        assertTrue(lvl1.contains("55"))
    }

    @Test
    fun `professionLevelInfos - 顶级已满级不显示晋升要求`() {
        assertNull(professionLevelInfos(isAlchemy = true)[5].promotionRequirement)
        assertNull(professionLevelInfos(isAlchemy = false)[5].promotionRequirement)
    }
}
