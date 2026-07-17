package com.xianxia.sect.core.domain

import com.xianxia.sect.core.config.FavorConfig
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.GiftPreferenceType
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectRelationLevel

/**
 * 好感度领域纯函数集合。
 *
 * 所有不依赖 GameStateStore 的好感度计算逻辑集中在此：
 * 查询、等级判定、价格计算、衰减判定、事件筛选等。
 *
 * 纯函数意味着：
 * - 无副作用，可单元测试
 * - 输入相同 → 输出始终相同
 * - 不访问任何有状态对象
 */
object FavorDomain {

    // ═══════════ 查询 ═══════════

    /**
     * 查询两个宗门之间的好感度。
     *
     * @param sectRelations 全量关系列表
     * @param fromSectId 查询方宗门 ID
     * @param toSectId 目标宗门 ID
     * @return 好感度数值，找不到关系时返回 0
     */
    fun findFavor(
        sectRelations: List<SectRelation>,
        fromSectId: String,
        toSectId: String
    ): Int {
        return findRelation(sectRelations, fromSectId, toSectId)?.favor ?: 0
    }

    /**
     * 查询两个宗门之间的关系记录。
     *
     * @param sectRelations 全量关系列表
     * @param sectIdA 宗门 A ID
     * @param sectIdB 宗门 B ID
     * @return 关系记录，不存在时返回 null
     */
    fun findRelation(
        sectRelations: List<SectRelation>,
        sectIdA: String,
        sectIdB: String
    ): SectRelation? {
        return sectRelations.find {
            (it.sectId1 == sectIdA && it.sectId2 == sectIdB) ||
            (it.sectId1 == sectIdB && it.sectId2 == sectIdA)
        }
    }

    // ═══════════ 相识判定 ═══════════

    /**
     * 判断一条关系记录是否已相识。
     *
     * @param relation 关系记录，可为 null
     * @return 当 relation 非 null 且 acquainted == true 时返回 true
     */
    fun isAcquainted(relation: SectRelation?): Boolean {
        return relation != null && relation.acquainted
    }

    /**
     * 判断两个宗门之间是否已相识。
     *
     * @param relations 全量关系列表
     * @param sectIdA 宗门 A ID
     * @param sectIdB 宗门 B ID
     * @return 当两个宗门之间存在关系且 acquainted == true 时返回 true
     */
    fun isAcquainted(
        relations: List<SectRelation>,
        sectIdA: String,
        sectIdB: String
    ): Boolean {
        return isAcquainted(findRelation(relations, sectIdA, sectIdB))
    }

    /**
     * 根据好感度数值获取关系等级。
     *
     * @param favor 好感度数值
     * @return 对应的关系等级
     */
    fun getLevel(favor: Int): SectRelationLevel {
        return SectRelationLevel.fromFavor(favor)
    }

    /**
     * 获取玩家宗门对所有 AI 宗门的好感度 Map。
     *
     * @param sectRelations 全量关系列表
     * @param playerSectId 玩家宗门 ID
     * @param aiSectIds AI 宗门 ID 列表
     * @return Map<宗门ID, 好感度>
     */
    fun findAllFavorsForPlayer(
        sectRelations: List<SectRelation>,
        playerSectId: String,
        aiSectIds: List<String>
    ): Map<String, Int> {
        return aiSectIds.associateWith { sectId ->
            findFavor(sectRelations, playerSectId, sectId)
        }
    }

    // ═══════════ 计算 ═══════════

    /**
     * 计算送礼的好感度增长量。
     *
     * @param currentFavor 当前好感度
     * @param tier 送礼档位 (1-4)
     * @param sectLevel 宗门等级 (0-3)
     * @param preference 宗门礼物偏好
     * @return 好感度增长量（未 clamp）
     */
    fun calculateGiftFavorIncrease(
        currentFavor: Int,
        tier: Int,
        sectLevel: Int,
        preference: GiftPreferenceType
    ): Int {
        val tierConfig = GiftConfig.SpiritStoneGiftConfig.getTier(tier) ?: return 0
        val percentage = GiftConfig.FavorPercentageConfig.getFavorPercentage(sectLevel, tier)
        val preferenceMultiplier = calculatePreferenceMultiplier(preference, isSpiritStone = true)
        val baseFavor = tierConfig.baseFavor

        return if (percentage != null) {
            val percentageIncrease = currentFavor * percentage / 100
            val adjustedIncrease = ((baseFavor + percentageIncrease) * preferenceMultiplier).toInt()
            if (adjustedIncrease == 0) 1 else adjustedIncrease
        } else {
            (baseFavor * preferenceMultiplier).toInt().coerceAtLeast(1)
        }
    }

    /**
     * 计算结盟成功率。
     *
     * @param favor 当前好感度
     * @return 成功概率 (0.0 - 1.0)
     */
    fun calculateAllianceSuccessChance(favor: Int): Double {
        return when {
            favor >= 90 -> 0.90
            favor >= 80 -> 0.75
            favor >= 60 -> 0.60
            favor >= 40 -> 0.45
            favor >= 20 -> 0.25
            else -> 0.10
        }
    }

    /**
     * 计算宗门交易价格倍率。
     *
     * @param sectRelations 全量关系列表
     * @param alliances 全量联盟列表
     * @param sectId 目标宗门 ID
     * @param playerSectId 玩家宗门 ID
     * @return 价格倍率（1.0 = 原价，越小折扣越大）
     */
    fun calculateTradePriceMultiplier(
        sectRelations: List<SectRelation>,
        alliances: List<Alliance>,
        sectId: String,
        playerSectId: String
    ): Double {
        val isAlly = alliances.any { it.sectIds.contains(playerSectId) && it.sectIds.contains(sectId) }
        val relation = findFavor(sectRelations, playerSectId, sectId)
        return when {
            isAlly -> (0.9 * (1.0 - maxOf(0, relation - 70) * 0.01))
                .coerceAtLeast(FavorConfig.ALLY_PRICE_MIN)
            relation >= FavorConfig.FAVOR_DISCOUNT_THRESHOLD -> (1.0 - (relation - 70) * 0.01)
                .coerceAtLeast(FavorConfig.FAVOR_PRICE_MIN)
            else -> 1.0
        }
    }

    /**
     * 计算宗门拒绝接收礼物的概率。
     *
     * @param sectLevel 宗门等级 (0-3)
     * @param rarity 物品稀有度 (1-6)
     * @return 拒绝概率 (0-100)
     */
    fun calculateRejectProbability(sectLevel: Int, rarity: Int): Int {
        return GiftConfig.SectRejectConfig.getRejectProbability(sectLevel, rarity)
    }

    /**
     * 计算礼物偏好好感度乘数。
     *
     * @param giftPreference 宗门礼物偏好
     * @param isSpiritStone 是否为灵石
     * @return 偏好乘数
     */
    fun calculatePreferenceMultiplier(
        giftPreference: GiftPreferenceType,
        isSpiritStone: Boolean = false
    ): Double {
        if (giftPreference == GiftPreferenceType.NONE) return 1.0
        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> 1.3
            else -> 1.0
        }
    }

    /**
     * 计算礼物偏好对拒绝概率的修正值。
     *
     * @param giftPreference 宗门礼物偏好
     * @param isSpiritStone 是否为灵石
     * @return 拒绝概率修正值（负值 = 降低拒绝概率）
     */
    fun calculatePreferenceRejectModifier(
        giftPreference: GiftPreferenceType,
        isSpiritStone: Boolean = false
    ): Int {
        if (giftPreference == GiftPreferenceType.NONE) return 0
        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> -15
            else -> 0
        }
    }

    // ═══════════ 更新 ═══════════

    /**
     * 更新两个宗门之间的好感度（设定绝对值）。
     *
     * 如果关系列表中已有记录则更新，否则新建。
     *
     * @param relations 全量关系列表
     * @param sectId1 宗门1 ID
     * @param sectId2 宗门2 ID
     * @param newFavor 新的好感度值（自动 clamp 到 [0, 100]）
     * @param year 当前年份
     * @return 更新后的关系列表
     */
    fun updateFavor(
        relations: List<SectRelation>,
        sectId1: String,
        sectId2: String,
        newFavor: Int,
        year: Int = 0
    ): List<SectRelation> {
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)
        val existingRelation = findRelation(relations, sectId1, sectId2)
        if (existingRelation != null && !existingRelation.acquainted) return relations
        val clampedFavor = newFavor.coerceIn(FavorConfig.MIN_FAVOR, FavorConfig.MAX_FAVOR)
        val index = relations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }

        return if (index >= 0) {
            relations.mapIndexed { i, relation ->
                if (i == index) {
                    relation.copy(
                        favor = clampedFavor,
                        lastInteractionYear = year,
                        noGiftYears = 0
                    )
                } else {
                    relation
                }
            }
        } else {
            relations + SectRelation(
                sectId1 = id1,
                sectId2 = id2,
                favor = clampedFavor,
                lastInteractionYear = year,
                noGiftYears = 0
            )
        }
    }

    /**
     * 对两个宗门之间的好感度进行增量修改。
     *
     * @param relations 全量关系列表
     * @param sectId1 宗门1 ID
     * @param sectId2 宗门2 ID
     * @param delta 好感度变化量（正值增加，负值减少）
     * @param year 当前年份
     * @return 更新后的关系列表
     */
    fun modifyFavor(
        relations: List<SectRelation>,
        sectId1: String,
        sectId2: String,
        delta: Int,
        year: Int = 0
    ): List<SectRelation> {
        val current = findFavor(relations, sectId1, sectId2)
        return updateFavor(relations, sectId1, sectId2, current + delta, year)
    }

    // ═══════════ 设置相识 ═══════════

    /**
     * 设置两个宗门之间为已相识状态（幂等操作）。
     *
     * - 如果关系已存在且已相识：不做任何修改，直接返回原列表
     * - 如果关系不存在：创建新的关系记录，设置 acquainted = true
     * - 如果关系存在但未相识：更新为 acquainted = true
     *
     * @param relations 全量关系列表
     * @param sectId1 宗门1 ID
     * @param sectId2 宗门2 ID
     * @param year 当前年份（用于设置 lastInteractionYear）
     * @return 更新后的关系列表
     */
    fun setAcquainted(
        relations: List<SectRelation>,
        sectId1: String,
        sectId2: String,
        year: Int = 0
    ): List<SectRelation> {
        val existingRelation = findRelation(relations, sectId1, sectId2)
        if (existingRelation != null) {
            if (existingRelation.acquainted) return relations
            val id1 = minOf(sectId1, sectId2)
            val id2 = maxOf(sectId1, sectId2)
            val index = relations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }
            return relations.mapIndexed { i, relation ->
                if (i == index) relation.copy(acquainted = true, lastInteractionYear = year) else relation
            }
        }
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)
        return relations + SectRelation(
            sectId1 = id1,
            sectId2 = id2,
            acquainted = true,
            lastInteractionYear = year
        )
    }

    // ═══════════ 衰减判定 ═══════════

    /**
     * 判断一条关系是否应该触发好感度衰减。
     *
     * @param relation 关系记录
     * @param currentYear 当前年份
     * @return true 表示应衰减
     */
    fun shouldDecay(relation: SectRelation, currentYear: Int): Boolean {
        if (relation.favor <= FavorConfig.DECAY_THRESHOLD) return false
        val yearsSinceGift = currentYear - relation.lastInteractionYear
        return yearsSinceGift >= FavorConfig.DECAY_NO_GIFT_YEARS
    }

    /**
     * 计算衰减后的好感度值。
     *
     * @param relation 关系记录
     * @return 衰减后的好感度（不低于 DECAY_THRESHOLD）
     */
    fun calculateDecayedFavor(relation: SectRelation): Int {
        return (relation.favor - FavorConfig.DECAY_AMOUNT)
            .coerceAtLeast(FavorConfig.DECAY_THRESHOLD)
    }

}
