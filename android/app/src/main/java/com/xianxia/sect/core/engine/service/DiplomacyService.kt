package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.WorldMapGenerator
import java.security.SecureRandom
import kotlin.random.Random

/**
 * 外交服务 - 负责宗门外交系统
 *
 * 职责域：
 * - 灵石送礼 (giftSpiritStones)
 * - 物品送礼 (giftItem)
 * - 结盟请求 (requestAlliance)
 * - 解除结盟 (dissolveAlliance)
 * - 外交相关计算（拒绝概率、偏好乘数、好感度更新等）
 */
class DiplomacyService(
    private val _gameData: MutableStateFlow<GameData>,
    private val _manuals: MutableStateFlow<List<Manual>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "DiplomacyService"
    }

    // ==================== 数据类定义 ====================

    /**
     * 送礼结果数据类
     */
    data class GiftResult(
        val success: Boolean,
        val rejected: Boolean = false,
        val favorChange: Int = 0,
        val newFavor: Int = 0,
        val message: String = "",
        val responseType: String = ""
    )

    // ==================== 公开方法 ====================

    /**
     * 向宗门赠送灵石
     *
     * @param sectId 目标宗门ID
     * @param tier 送礼档位
     * @return 送礼结果
     */
    fun giftSpiritStones(sectId: String, tier: Int): GiftResult {
        val data = _gameData.value
        val currentYear = data.gameYear

        // 查找目标宗门
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null) {
            return GiftResult(
                success = false,
                responseType = "sect_not_found",
                message = "未找到目标宗门"
            )
        }

        // 检查是否为玩家宗门
        if (sect.isPlayerSect) {
            return GiftResult(
                success = false,
                responseType = "invalid_target",
                message = "不能向自己的宗门送礼"
            )
        }

        // 检查每年一次限制
        if (sect.lastGiftYear == currentYear) {
            return GiftResult(
                success = false,
                rejected = false,
                responseType = "already_gifted",
                message = "今年已经向${sect.name}送过礼了，请明年再来"
            )
        }

        // 获取档位配置
        val tierConfig = GiftConfig.SpiritStoneGiftConfig.getTier(tier)
        if (tierConfig == null) {
            return GiftResult(
                success = false,
                responseType = "invalid_tier",
                message = "无效的送礼档位"
            )
        }

        // 检查灵石是否足够
        if (data.spiritStones < tierConfig.spiritStones) {
            return GiftResult(
                success = false,
                responseType = "insufficient_resources",
                message = "灵石不足，需要${tierConfig.spiritStones}灵石"
            )
        }

        // 计算拒绝概率（灵石送礼使用档位对应的虚拟稀有度）
        val virtualRarity = (tier + 1).coerceIn(2, 5)
        val baseRejectProbability = getRejectProbability(sect.level, virtualRarity)
        val preferenceRejectModifier = calculatePreferenceRejectModifier(
            sect.giftPreference,
            "",
            isSpiritStone = true
        )
        val rejectProbability = (baseRejectProbability + preferenceRejectModifier).coerceIn(0, 100)

        val isRejected = SecureRandom().nextInt(100) < rejectProbability

        if (isRejected) {
            val responseText = SectResponseTexts.getRejectResponse(sect.level, "spirit_stones", tierConfig.name)
            addEvent("向${sect.name}送礼${tierConfig.name}被拒绝：$responseText", EventType.WARNING)

            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = responseText
            )
        }

        // 送礼成功：扣除灵石、标记已送礼、增加好感度
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val currentFavor = if (playerSect != null) {
            data.sectRelations.find {
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        val percentage = GiftConfig.FavorPercentageConfig.getFavorPercentage(sect.level, tier)
        val preferenceMultiplier = calculatePreferenceMultiplier(
            sect.giftPreference,
            "",
            isSpiritStone = true
        )
        val favorIncrease = if (percentage != null) {
            val baseIncrease = currentFavor * percentage / 100
            val adjustedIncrease = (baseIncrease * preferenceMultiplier).toInt()
            if (adjustedIncrease == 0) 1 else adjustedIncrease
        } else {
            1
        }
        val newFavor = (currentFavor + favorIncrease).coerceAtMost(100)

        val updatedSects = data.worldMapSects.map { currentSect ->
            if (currentSect.id == sectId) {
                currentSect.copy(lastGiftYear = currentYear)
            } else {
                currentSect
            }
        }

        val updatedRelations = if (playerSect != null) {
            updateSectRelationFavor(data.sectRelations, playerSect.id, sectId, newFavor, currentYear)
        } else {
            data.sectRelations
        }

        _gameData.value = data.copy(
            spiritStones = data.spiritStones - tierConfig.spiritStones,
            worldMapSects = updatedSects,
            sectRelations = updatedRelations
        )

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, "spirit_stones", tierConfig.name, favorIncrease)
        addEvent("向${sect.name}送礼${tierConfig.name}成功，好感度+${favorIncrease}", EventType.SUCCESS)

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = favorIncrease,
            newFavor = newFavor,
            responseType = "accept",
            message = responseText
        )
    }

    /**
     * 向宗门赠送物品
     *
     * @param sectId 目标宗门ID
     * @param itemId 物品ID
     * @param itemType 物品类型
     * @param quantity 数量
     * @return 送礼结果
     */
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int): GiftResult {
        val data = _gameData.value
        val currentYear = data.gameYear

        // 查找目标宗门
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null) {
            return GiftResult(
                success = false,
                responseType = "sect_not_found",
                message = "未找到目标宗门"
            )
        }

        // 检查是否为玩家宗门
        if (sect.isPlayerSect) {
            return GiftResult(
                success = false,
                responseType = "invalid_target",
                message = "不能向自己的宗门送礼"
            )
        }

        // 检查每年一次限制
        if (sect.lastGiftYear == currentYear) {
            return GiftResult(
                success = false,
                rejected = false,
                responseType = "already_gifted",
                message = "今年已经向${sect.name}送过礼了，请明年再来"
            )
        }

        // 查找物品并获取稀有度
        val (itemRarity, itemName, actualQuantity) = when (itemType) {
            GiftConfig.ItemType.MANUAL -> {
                val manual = _manuals.value.find { it.id == itemId }
                if (manual == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该功法"
                    )
                }
                Triple(manual.rarity, manual.name, 1)
            }
            GiftConfig.ItemType.EQUIPMENT -> {
                val equipment = _equipment.value.find { it.id == itemId }
                if (equipment == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该装备"
                    )
                }
                if (equipment.isEquipped) {
                    return GiftResult(
                        success = false,
                        responseType = "item_equipped",
                        message = "该装备已被装备，无法送礼"
                    )
                }
                Triple(equipment.rarity, equipment.name, 1)
            }
            GiftConfig.ItemType.PILL -> {
                val pill = _pills.value.find { it.id == itemId }
                if (pill == null) {
                    return GiftResult(
                        success = false,
                        responseType = "item_not_found",
                        message = "未找到该丹药"
                    )
                }
                if (pill.quantity < quantity) {
                    return GiftResult(
                        success = false,
                        responseType = "insufficient_quantity",
                        message = "丹药数量不足，当前有${pill.quantity}个"
                    )
                }
                Triple(pill.rarity, pill.name, quantity.coerceAtLeast(1))
            }
            else -> {
                return GiftResult(
                    success = false,
                    responseType = "invalid_item_type",
                    message = "不支持的物品类型"
                )
            }
        }

        // 计算好感度（使用百分比增长）
        val favorPercentage = GiftConfig.ItemFavorPercentageConfig.getFavorPercentage(sect.level, itemRarity)

        val playerSectForFavor = _gameData.value.worldMapSects.find { it.isPlayerSect }
        val currentFavorValue = if (playerSectForFavor != null) {
            _gameData.value.sectRelations.find {
                (it.sectId1 == playerSectForFavor.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSectForFavor.id)
            }?.favor ?: 0
        } else 0

        val baseFavor = if (favorPercentage == null) {
            1
        } else {
            if (currentFavorValue == 0) {
                1
            } else {
                (currentFavorValue * favorPercentage / 100).coerceAtLeast(1)
            }
        }

        val preferenceMultiplier = calculatePreferenceMultiplier(
            sect.giftPreference,
            itemType,
            isSpiritStone = false
        )
        val adjustedFavor = (baseFavor * preferenceMultiplier).toInt().coerceAtLeast(1)
        val totalFavor = adjustedFavor * actualQuantity

        // 计算拒绝概率
        val baseRejectProbability = getRejectProbability(sect.level, itemRarity)
        val preferenceRejectModifier = calculatePreferenceRejectModifier(
            sect.giftPreference,
            itemType,
            isSpiritStone = false
        )
        val rejectProbability = (baseRejectProbability + preferenceRejectModifier).coerceIn(0, 100)

        // 判断是否被拒绝
        val isRejected = Random.nextInt(100) < rejectProbability

        if (isRejected) {
            val responseText = SectResponseTexts.getRejectResponse(sect.level, itemType, itemName)
            addEvent("向${sect.name}送礼${itemName}被拒绝：$responseText", EventType.WARNING)

            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = responseText
            )
        }

        // 送礼成功：移除物品、标记已送礼、增加好感度
        removeGiftItem(itemType, itemId, actualQuantity)

        val newFavor = (currentFavorValue + totalFavor).coerceAtMost(100)

        val finalSects = _gameData.value.worldMapSects.map {
            if (it.id == sectId) {
                it.copy(lastGiftYear = currentYear)
            } else it
        }

        val updatedRelations = if (playerSectForFavor != null) {
            updateSectRelationFavor(_gameData.value.sectRelations, playerSectForFavor.id, sectId, newFavor, currentYear)
        } else {
            _gameData.value.sectRelations
        }

        _gameData.value = _gameData.value.copy(worldMapSects = finalSects, sectRelations = updatedRelations)

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, itemType, itemName, totalFavor)
        val quantityText = if (actualQuantity > 1) "x$actualQuantity " else ""
        addEvent("向${sect.name}送礼${quantityText}${itemName}成功，好感度+$totalFavor", EventType.SUCCESS)

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = totalFavor,
            newFavor = newFavor,
            responseType = "accept",
            message = responseText
        )
    }

    /**
     * 请求结盟
     *
     * @param sectId 目标宗门ID
     * @param envoyDiscipleId 使者弟子ID
     * @return 结盟结果（是否成功，消息）
     */
    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> {
        val (canAlliance, message, cost) = checkAllianceConditions(sectId, envoyDiscipleId)

        if (!canAlliance) {
            return Pair(false, message)
        }

        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
            ?: return Pair(false, "未找到目标宗门")
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }
            ?: return Pair(false, "未找到使者弟子")

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            data.sectRelations.find {
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        val successRate = calculatePersuasionSuccessRate(favor, envoy.intelligence, envoy.charm)
        val roll = Random.nextDouble()

        if (roll < successRate) {
            val alliance = Alliance(
                sectIds = listOf("player", sectId),
                startYear = data.gameYear,
                initiatorId = "player",
                envoyDiscipleId = envoyDiscipleId
            )

            val updatedSects = data.worldMapSects.map { s ->
                if (s.id == sectId) s.copy(allianceId = alliance.id, allianceStartYear = data.gameYear)
                else s
            }

            _gameData.value = data.copy(
                spiritStones = data.spiritStones - cost,
                alliances = data.alliances + alliance,
                worldMapSects = updatedSects
            )

            addEvent("与${sect.name}成功结盟！", EventType.SUCCESS)
            return Pair(true, "结盟成功！")
        } else {
            _gameData.value = data.copy(spiritStones = data.spiritStones - cost / 2)
            addEvent("游说${sect.name}失败，结盟未成", EventType.WARNING)
            return Pair(false, "游说失败，好感度不足以达成结盟")
        }
    }

    /**
     * 解除结盟
     *
     * @param sectId 目标宗门ID
     * @return 解除结果（是否成功，消息）
     */
    fun dissolveAlliance(sectId: String): Pair<Boolean, String> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }

        if (sect == null) {
            return Pair(false, "未找到目标宗门")
        }

        if (sect.allianceId == null) {
            return Pair(false, "该宗门未与您结盟")
        }

        val alliance = data.alliances.find { it.id == sect.allianceId }
        if (alliance == null) {
            return Pair(false, "未找到结盟记录")
        }

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val relation = if (playerSect != null) {
            data.sectRelations.find {
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }
        } else null

        val favorPenalty = GameConfig.Diplomacy.BreakPenalty.FAVOR_PENALTY
        val currentFavor = relation?.favor ?: WorldMapGenerator.INITIAL_SECT_FAVOR
        val newFavor = (currentFavor - favorPenalty).coerceAtLeast(GameConfig.Diplomacy.MIN_FAVOR.toInt())

        val allianceCost = getAllianceCost(sect.level)
        val spiritStonePenalty = (allianceCost * GameConfig.Diplomacy.BreakPenalty.SPIRIT_STONE_PENALTY_RATIO).toLong()
        val newSpiritStones = (data.spiritStones - spiritStonePenalty).coerceAtLeast(0L)

        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sectId) s.copy(allianceId = null, allianceStartYear = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        val updatedRelations = if (relation != null && playerSect != null) {
            data.sectRelations.map { r ->
                if ((r.sectId1 == playerSect.id && r.sectId2 == sectId) ||
                    (r.sectId1 == sectId && r.sectId2 == playerSect.id)) {
                    r.copy(favor = newFavor)
                } else r
            }
        } else data.sectRelations

        _gameData.value = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances,
            sectRelations = updatedRelations,
            spiritStones = newSpiritStones
        )

        addEvent("与${sect.name}解除结盟，好感度-${favorPenalty}，灵石-${spiritStonePenalty}", EventType.WARNING)
        return Pair(true, "已解除结盟，好感度下降${favorPenalty}点，消耗灵石${spiritStonePenalty}")
    }

    // ==================== 公开查询方法 ====================

    /**
     * 获取拒绝概率
     *
     * @param sectLevel 宗门等级
     * @param rarity 物品稀有度
     * @return 拒绝概率（0-100）
     */
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int {
        return GiftConfig.SectRejectConfig.getRejectProbability(sectLevel, rarity)
    }

    /**
     * 检查结盟条件
     *
     * @param sectId 目标宗门ID
     * @param envoyDiscipleId 使者弟子ID
     * @return 三元组（是否满足条件，消息，费用）
     */
    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
        val envoy = _disciples.value.find { it.id == envoyDiscipleId }

        if (sect == null) {
            return Triple(false, "未找到目标宗门", 0)
        }

        if (sect.isPlayerSect) {
            return Triple(false, "不能与自己的宗门结盟", 0)
        }

        if (sect.allianceId != null) {
            return Triple(false, "该宗门已有结盟", 0)
        }

        if (envoy == null) {
            return Triple(false, "未找到游说弟子", 0)
        }

        if (!envoy.isAlive || envoy.status != DiscipleStatus.IDLE) {
            return Triple(false, "游说弟子必须处于空闲状态", 0)
        }

        val requiredRealm = getEnvoyRealmRequirement(sect.level)
        if (envoy.realm > requiredRealm) {
            val realmName = GameConfig.Realm.get(requiredRealm)?.name ?: "未知"
            return Triple(false, "游说弟子境界需要达到${realmName}及以上", 0)
        }

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            data.sectRelations.find {
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        if (favor < GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR) {
            return Triple(false, "好感度需要达到${GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR}以上", 0)
        }

        // 检查玩家是否已有结盟
        val existingAlliance = data.alliances.any { it.sectIds.contains("player") }
        if (existingAlliance) {
            return Triple(false, "您已有其他结盟，请先解除现有结盟", 0)
        }

        val cost = getAllianceCost(sect.level)
        if (data.spiritStones < cost) {
            return Triple(false, "灵石不足，需要${cost}灵石", 0)
        }

        return Triple(true, "", cost.toInt())
    }

    /**
     * 计算游说成功率
     *
     * @param favorability 好感度
     * @param intelligence 智力
     * @param charm 魅力
     * @return 成功率（0.0-1.0）
     */
    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double {
        val favorBonus = if (favorability >= GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR) (favorability - GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR) * 0.05 else 0.0
        val intBonus = (intelligence - 50) / 5.0 * 0.01
        val charmBonus = (charm - 50) / 5.0 * 0.01
        return (favorBonus + intBonus + charmBonus).coerceIn(0.0, 1.0)
    }

    /**
     * 获取游说弟子境界要求
     *
     * @param sectLevel 宗门等级
     * @return 所需境界等级
     */
    fun getEnvoyRealmRequirement(sectLevel: Int): Int {
        return when (sectLevel) {
            0 -> 7  // 金丹
            1 -> 5  // 化神
            2 -> 4  // 炼虚
            3 -> 3  // 合体
            else -> 7
        }
    }

    /**
     * 获取结盟费用
     *
     * @param sectLevel 宗门等级
     * @return 结盟所需灵石
     */
    fun getAllianceCost(sectLevel: Int): Long {
        return when (sectLevel) {
            0 -> 50_000L
            1 -> 200_000L
            2 -> 800_000L
            3 -> 2_000_000L
            else -> 50_000L
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 计算偏好乘数（用于好感度增长）
     *
     * @param giftPreference 礼物偏好类型
     * @param itemType 物品类型
     * @param isSpiritStone 是否为灵石
     * @return 偏好乘数
     */
    private fun calculatePreferenceMultiplier(
        giftPreference: GiftPreferenceType,
        itemType: String,
        isSpiritStone: Boolean = false
    ): Double {
        if (giftPreference == GiftPreferenceType.NONE) return 1.0

        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> 1.3
            itemType == GiftConfig.ItemType.EQUIPMENT && giftPreference == GiftPreferenceType.EQUIPMENT -> 1.5
            itemType == GiftConfig.ItemType.MANUAL && giftPreference == GiftPreferenceType.MANUAL -> 1.5
            itemType == GiftConfig.ItemType.PILL && giftPreference == GiftPreferenceType.PILL -> 1.5
            !isSpiritStone && itemType.isNotEmpty() -> 0.8
            else -> 1.0
        }
    }

    /**
     * 计算偏好拒绝修正值（用于拒绝概率）
     *
     * @param giftPreference 礼物偏好类型
     * @param itemType 物品类型
     * @param isSpiritStone 是否为灵石
     * @return 拒绝修正值
     */
    private fun calculatePreferenceRejectModifier(
        giftPreference: GiftPreferenceType,
        itemType: String,
        isSpiritStone: Boolean = false
    ): Int {
        if (giftPreference == GiftPreferenceType.NONE) return 0

        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> -15
            itemType == GiftConfig.ItemType.EQUIPMENT && giftPreference == GiftPreferenceType.EQUIPMENT -> -20
            itemType == GiftConfig.ItemType.MANUAL && giftPreference == GiftPreferenceType.MANUAL -> -20
            itemType == GiftConfig.ItemType.PILL && giftPreference == GiftPreferenceType.PILL -> -20
            !isSpiritStone && itemType.isNotEmpty() -> 10
            else -> 0
        }
    }

    /**
     * 更新宗门关系好感度
     *
     * @param relations 关系列表
     * @param sectId1 宗门1 ID
     * @param sectId2 宗门2 ID
     * @param newFavor 新好感度
     * @param year 当前年份
     * @return 更新后的关系列表
     */
    private fun updateSectRelationFavor(
        relations: List<SectRelation>,
        sectId1: String,
        sectId2: String,
        newFavor: Int,
        year: Int = 0
    ): List<SectRelation> {
        val id1 = minOf(sectId1, sectId2)
        val id2 = maxOf(sectId1, sectId2)

        val index = relations.indexOfFirst { it.sectId1 == id1 && it.sectId2 == id2 }

        return if (index >= 0) {
            relations.mapIndexed { i, relation ->
                if (i == index) {
                    relation.copy(favor = newFavor.coerceIn(0, 100), lastInteractionYear = year, noGiftYears = 0)
                } else {
                    relation
                }
            }
        } else {
            relations + SectRelation(
                sectId1 = id1,
                sectId2 = id2,
                favor = newFavor.coerceIn(0, 100),
                lastInteractionYear = year,
                noGiftYears = 0
            )
        }
    }

    /**
     * 移除送礼物品
     *
     * @param itemType 物品类型
     * @param itemId 物品ID
     * @param quantity 数量
     */
    private fun removeGiftItem(itemType: String, itemId: String, quantity: Int) {
        when (itemType) {
            GiftConfig.ItemType.MANUAL -> {
                _manuals.value = _manuals.value.map { manual ->
                    if (manual.id == itemId) {
                        manual.copy(quantity = (manual.quantity - quantity).coerceAtLeast(0))
                    } else manual
                }.filter { it.quantity > 0 }
            }
            GiftConfig.ItemType.EQUIPMENT -> {
                _equipment.value = _equipment.value.filter { it.id != itemId }
            }
            GiftConfig.ItemType.PILL -> {
                _pills.value = _pills.value.map { pill ->
                    if (pill.id == itemId) {
                        pill.copy(quantity = (pill.quantity - quantity).coerceAtLeast(0))
                    } else pill
                }.filter { it.quantity > 0 }
            }
        }
    }

    // ==================== 内部依赖（由 GameEngine 注入）====================

    /**
     * 弟子列表（由 GameEngine 注入）
     * 注意：requestAlliance 和 checkAllianceConditions 需要访问弟子数据
     */
    lateinit var _disciples: MutableStateFlow<List<Disciple>>
        internal set
}
