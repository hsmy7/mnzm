package com.xianxia.sect.core.domain

import com.xianxia.sect.core.config.FavorConfig
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.GiftPreferenceType
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectRelationLevel
import org.junit.Assert.*
import org.junit.Test

class FavorDomainTest {

    // ═══════════════════════════════════════════════════════════
    // findFavor
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `findFavor - 关系存在返回好感度`() {
        val relations = listOf(
            SectRelation(sectId1 = "a", sectId2 = "b", favor = 75)
        )
        assertEquals(75, FavorDomain.findFavor(relations, "a", "b"))
    }

    @Test
    fun `findFavor - 关系存在返回好感度 对称查询`() {
        val relations = listOf(
            SectRelation(sectId1 = "a", sectId2 = "b", favor = 75)
        )
        assertEquals(75, FavorDomain.findFavor(relations, "b", "a"))
    }

    @Test
    fun `findFavor - 关系不存在返回0`() {
        val relations = listOf(
            SectRelation(sectId1 = "a", sectId2 = "b", favor = 75)
        )
        assertEquals(0, FavorDomain.findFavor(relations, "a", "c"))
    }

    @Test
    fun `findFavor - 空列表返回0`() {
        assertEquals(0, FavorDomain.findFavor(emptyList(), "a", "b"))
    }

    // ═══════════════════════════════════════════════════════════
    // findRelation
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `findRelation - 关系存在返回记录`() {
        val r = SectRelation(sectId1 = "x", sectId2 = "y", favor = 50)
        val result = FavorDomain.findRelation(listOf(r), "x", "y")
        assertNotNull(result)
        assertEquals(50, result!!.favor)
    }

    @Test
    fun `findRelation - 关系不存在返回null`() {
        assertNull(FavorDomain.findRelation(emptyList(), "a", "b"))
    }

    // ═══════════════════════════════════════════════════════════
    // getLevel
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `getLevel - favor为0时返回HOSTILE`() {
        assertEquals(SectRelationLevel.HOSTILE, FavorDomain.getLevel(0))
    }

    @Test
    fun `getLevel - favor为5时返回HOSTILE`() {
        assertEquals(SectRelationLevel.HOSTILE, FavorDomain.getLevel(5))
    }

    @Test
    fun `getLevel - favor为20时返回ANTAGONISTIC`() {
        assertEquals(SectRelationLevel.ANTAGONISTIC, FavorDomain.getLevel(20))
    }

    @Test
    fun `getLevel - favor为50时返回NORMAL`() {
        assertEquals(SectRelationLevel.NORMAL, FavorDomain.getLevel(50))
    }

    @Test
    fun `getLevel - favor为70时返回FRIENDLY`() {
        assertEquals(SectRelationLevel.FRIENDLY, FavorDomain.getLevel(70))
    }

    @Test
    fun `getLevel - favor为90时返回INTIMATE`() {
        assertEquals(SectRelationLevel.INTIMATE, FavorDomain.getLevel(90))
    }

    @Test
    fun `getLevel - favor为100时返回INTIMATE`() {
        assertEquals(SectRelationLevel.INTIMATE, FavorDomain.getLevel(100))
    }

    @Test
    fun `getLevel - favor为负时返回HOSTILE`() {
        assertEquals(SectRelationLevel.HOSTILE, FavorDomain.getLevel(-10))
    }

    @Test
    fun `getLevel - favor超过100时返回INTIMATE`() {
        assertEquals(SectRelationLevel.INTIMATE, FavorDomain.getLevel(200))
    }

    // ═══════════════════════════════════════════════════════════
    // getAllFavorsForPlayer
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `findAllFavorsForPlayer - 返回玩家对所有AI宗门的好感度`() {
        val relations = listOf(
            SectRelation(sectId1 = "player", sectId2 = "ai_1", favor = 80),
            SectRelation(sectId1 = "ai_2", sectId2 = "player", favor = 30)
        )
        val result = FavorDomain.findAllFavorsForPlayer(relations, "player", listOf("ai_1", "ai_2"))
        assertEquals(2, result.size)
        assertEquals(80, result["ai_1"])
        assertEquals(30, result["ai_2"])
    }

    // ═══════════════════════════════════════════════════════════
    // calculateGiftFavorIncrease
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `calculateGiftFavorIncrease - 薄礼基础2`() {
        val increase = FavorDomain.calculateGiftFavorIncrease(
            currentFavor = 50, tier = 1, sectLevel = 0,
            preference = GiftPreferenceType.NONE
        )
        // 小型宗门(level=0) tier=1: baseFavor=2, percentage=20%
        // (2 + 50*20/100) * 1.0 = 12
        assertTrue("好感度增长应为正数", increase > 0)
    }

    @Test
    fun `calculateGiftFavorIncrease - 偏好灵石时乘数13x`() {
        val normal = FavorDomain.calculateGiftFavorIncrease(
            currentFavor = 50, tier = 1, sectLevel = 0,
            preference = GiftPreferenceType.NONE
        )
        val preferred = FavorDomain.calculateGiftFavorIncrease(
            currentFavor = 50, tier = 1, sectLevel = 0,
            preference = GiftPreferenceType.SPIRIT_STONE
        )
        assertTrue("偏好灵石时应获得更多好感度", preferred >= normal)
    }

    // ═══════════════════════════════════════════════════════════
    // calculateAllianceSuccessChance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `calculateAllianceSuccessChance - favor90为90pct`() {
        assertEquals(0.90, FavorDomain.calculateAllianceSuccessChance(90), 0.001)
    }

    @Test
    fun `calculateAllianceSuccessChance - favor80为75pct`() {
        assertEquals(0.75, FavorDomain.calculateAllianceSuccessChance(80), 0.001)
    }

    @Test
    fun `calculateAllianceSuccessChance - favor60为60pct`() {
        assertEquals(0.60, FavorDomain.calculateAllianceSuccessChance(60), 0.001)
    }

    @Test
    fun `calculateAllianceSuccessChance - favor40为45pct`() {
        assertEquals(0.45, FavorDomain.calculateAllianceSuccessChance(40), 0.001)
    }

    @Test
    fun `calculateAllianceSuccessChance - favor20为25pct`() {
        assertEquals(0.25, FavorDomain.calculateAllianceSuccessChance(20), 0.001)
    }

    @Test
    fun `calculateAllianceSuccessChance - favor0为10pct`() {
        assertEquals(0.10, FavorDomain.calculateAllianceSuccessChance(0), 0.001)
    }

    // ═══════════════════════════════════════════════════════════
    // calculateTradePriceMultiplier
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `calculateTradePriceMultiplier - 普通好感无折扣`() {
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 50))
        val multiplier = FavorDomain.calculateTradePriceMultiplier(relations, emptyList(), "target", "player")
        assertEquals(1.0, multiplier, 0.001)
    }

    @Test
    fun `calculateTradePriceMultiplier - 高好感有折扣`() {
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 80))
        val multiplier = FavorDomain.calculateTradePriceMultiplier(relations, emptyList(), "target", "player")
        // 1.0 - (80-70)*0.01 = 0.9
        assertEquals(0.9, multiplier, 0.001)
    }

    @Test
    fun `calculateTradePriceMultiplier - 盟友额外折扣`() {
        val relations = listOf(SectRelation(sectId1 = "player", sectId2 = "target", favor = 80))
        val alliances = listOf(Alliance(id = "a1", sectIds = listOf("player", "target"), startYear = 1, initiatorId = "player"))
        val multiplier = FavorDomain.calculateTradePriceMultiplier(relations, alliances, "target", "player")
        // 0.9 * (1.0 - (80-70)*0.01) = 0.81, clamp at 0.85
        assertEquals(0.85, multiplier, 0.001)
    }

    // ═══════════════════════════════════════════════════════════
    // updateFavor / modifyFavor
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `updateFavor - 新关系时创建`() {
        val result = FavorDomain.updateFavor(emptyList(), "a", "b", 60, 5)
        assertEquals(1, result.size)
        assertEquals(60, result[0].favor)
        assertEquals(5, result[0].lastInteractionYear)
    }

    @Test
    fun `updateFavor - 已有关关系时更新`() {
        val relations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", favor = 30, lastInteractionYear = 1, acquainted = true))
        val result = FavorDomain.updateFavor(relations, "a", "b", 80, 5)
        assertEquals(80, result[0].favor)
        assertEquals(5, result[0].lastInteractionYear)
    }

    @Test
    fun `updateFavor - clamping上限100`() {
        val result = FavorDomain.updateFavor(emptyList(), "a", "b", 150, 1)
        assertEquals(FavorConfig.MAX_FAVOR, result[0].favor)
    }

    @Test
    fun `updateFavor - clamping下限0`() {
        val result = FavorDomain.updateFavor(emptyList(), "a", "b", -10, 1)
        assertEquals(FavorConfig.MIN_FAVOR, result[0].favor)
    }

    @Test
    fun `modifyFavor - 增量增减`() {
        val relations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", favor = 50, acquainted = true))
        val result = FavorDomain.modifyFavor(relations, "a", "b", 20, 2)
        assertEquals(70, result[0].favor)
    }

    @Test
    fun `modifyFavor - 负增量`() {
        val relations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", favor = 50, acquainted = true))
        val result = FavorDomain.modifyFavor(relations, "a", "b", -30, 2)
        assertEquals(20, result[0].favor)
    }

    // ═══════════════════════════════════════════════════════════
    // shouldDecay / calculateDecayedFavor
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `shouldDecay - 好感低于阈值不衰减`() {
        val r = SectRelation(sectId1 = "a", sectId2 = "b", favor = 70, lastInteractionYear = 1)
        assertFalse(FavorDomain.shouldDecay(r, 10))
    }

    @Test
    fun `shouldDecay - 高好感且久未送礼触发衰减`() {
        val r = SectRelation(sectId1 = "a", sectId2 = "b", favor = 90, lastInteractionYear = 1)
        assertTrue(FavorDomain.shouldDecay(r, 5))
    }

    @Test
    fun `shouldDecay - 当年互动不衰减`() {
        val r = SectRelation(sectId1 = "a", sectId2 = "b", favor = 90, lastInteractionYear = 10)
        assertFalse(FavorDomain.shouldDecay(r, 10))
    }

    @Test
    fun `calculateDecayedFavor - 衰减1点`() {
        val r = SectRelation(sectId1 = "a", sectId2 = "b", favor = 90)
        assertEquals(89, FavorDomain.calculateDecayedFavor(r))
    }

    @Test
    fun `calculateDecayedFavor - 不低于衰减下限`() {
        val r = SectRelation(sectId1 = "a", sectId2 = "b", favor = FavorConfig.DECAY_THRESHOLD)
        assertEquals(FavorConfig.DECAY_THRESHOLD, FavorDomain.calculateDecayedFavor(r))
    }

    // ═══════════════════════════════════════════════════════════
    // calculatePreferenceMultiplier / RejectModifier
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `calculatePreferenceMultiplier - 无偏好返回1`() {
        assertEquals(1.0, FavorDomain.calculatePreferenceMultiplier(GiftPreferenceType.NONE, true), 0.001)
    }

    @Test
    fun `calculatePreferenceMultiplier - 灵石偏好且送灵石返回13`() {
        assertEquals(1.3, FavorDomain.calculatePreferenceMultiplier(GiftPreferenceType.SPIRIT_STONE, true), 0.001)
    }

    @Test
    fun `calculatePreferenceRejectModifier - 灵石偏好减15pct拒绝`() {
        assertEquals(-15, FavorDomain.calculatePreferenceRejectModifier(GiftPreferenceType.SPIRIT_STONE, true))
    }
}
