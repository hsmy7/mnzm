package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import kotlinx.coroutines.launch
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.StateAccessorFactory
import com.xianxia.sect.core.engine.system.SystemPriority
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@SystemPriority(order = 250)
@Singleton
class DiplomacyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventService: EventService,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem {
    override val systemName: String = "DiplomacyService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "DiplomacyService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "DiplomacyService released")
    }

    override suspend fun clearForSlot(slotId: Int) {}
    private val state = StateAccessorFactory(stateStore, scope, null)

    private var currentGameData: GameData
        get() = state.gameData().current
        set(value) { state.gameData().current = value }

    private var currentDisciples: List<Disciple>
        get() = state.disciples().current
        set(value) { state.disciples().current = value }

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
        val data = currentGameData
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
        if (data.sectDetails[sect.id]?.lastGiftYear ?: 0 == currentYear) {
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
            data.sectDetails[sect.id]?.giftPreference ?: GiftPreferenceType.NONE,
            isSpiritStone = true
        )
        val rejectProbability = (baseRejectProbability + preferenceRejectModifier).coerceIn(0, 100)

        val isRejected = Random.nextInt(100) < rejectProbability

        if (isRejected) {
            val responseText = SectResponseTexts.getRejectResponse(sect.level, "spirit_stones", tierConfig.name)
            eventService.addGameEvent("向${sect.name}送礼${tierConfig.name}被拒绝：$responseText", EventType.WARNING)

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

        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)
        val percentage = GiftConfig.FavorPercentageConfig.getFavorPercentage(sect.level, tier)
        val preferenceMultiplier = calculatePreferenceMultiplier(
            sectDetail.giftPreference,
            isSpiritStone = true
        )
        val baseFavor = tierConfig.baseFavor
        val favorIncrease = if (percentage != null) {
            val percentageIncrease = currentFavor * percentage / 100
            val adjustedIncrease = ((baseFavor + percentageIncrease) * preferenceMultiplier).toInt()
            if (adjustedIncrease == 0) 1 else adjustedIncrease
        } else {
            (baseFavor * preferenceMultiplier).toInt().coerceAtLeast(1)
        }
        val newFavor = (currentFavor + favorIncrease).coerceAtMost(100)

        val updatedDetails = data.sectDetails.toMutableMap()
        updatedDetails[sectId] = (updatedDetails[sectId] ?: SectDetail(sectId = sectId)).copy(lastGiftYear = currentYear)

        val updatedRelations = if (playerSect != null) {
            updateSectRelationFavor(data.sectRelations, playerSect.id, sectId, newFavor, currentYear)
        } else {
            data.sectRelations
        }

        currentGameData = data.copy(
            spiritStones = data.spiritStones - tierConfig.spiritStones,
            sectDetails = updatedDetails,
            sectRelations = updatedRelations
        )

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, "spirit_stones", tierConfig.name, favorIncrease)
        eventService.addGameEvent("向${sect.name}送礼${tierConfig.name}成功，关系+${favorIncrease}", EventType.SUCCESS)

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

        val data = currentGameData
        val sect = data.worldMapSects.find { it.id == sectId }
            ?: return Pair(false, "未找到目标宗门")
        val envoy = currentDisciples.find { it.id == envoyDiscipleId }
            ?: return Pair(false, "未找到使者弟子")

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            data.sectRelations.find {
                (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                (it.sectId1 == sectId && it.sectId2 == playerSect.id)
            }?.favor ?: 0
        } else 0

        val envoyStats = envoy.getBaseStats()
        val successRate = calculatePersuasionSuccessRate(favor, envoyStats.intelligence, envoyStats.charm)
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

            currentGameData = data.copy(
                spiritStones = data.spiritStones - cost,
                alliances = data.alliances + alliance,
                worldMapSects = updatedSects
            )

            eventService.addGameEvent("与${sect.name}成功结盟！", EventType.SUCCESS)
            return Pair(true, "结盟成功！")
        } else {
            currentGameData = data.copy(spiritStones = data.spiritStones - cost / 2)
            eventService.addGameEvent("游说${sect.name}失败，结盟未成", EventType.WARNING)
            return Pair(false, "游说失败，关系不足以达成结盟")
        }
    }

    /**
     * 解除结盟
     *
     * @param sectId 目标宗门ID
     * @return 解除结果（是否成功，消息）
     */
    fun dissolveAlliance(sectId: String): Pair<Boolean, String> {
        val data = currentGameData
        val sect = data.worldMapSects.find { it.id == sectId }

        if (sect == null) {
            return Pair(false, "未找到目标宗门")
        }

        if (sect.allianceId.isEmpty()) {
            return Pair(false, "该宗门未与您结盟")
        }

        val alliance = data.alliances.find { it.id == sect.allianceId }
        if (alliance == null) {
            return Pair(false, "未找到结盟记录")
        }

        val allianceCost = getAllianceCost(sect.level)
        val spiritStonePenalty = (allianceCost * GameConfig.Diplomacy.BreakPenalty.SPIRIT_STONE_PENALTY_RATIO).toLong()
        val newSpiritStones = (data.spiritStones - spiritStonePenalty).coerceAtLeast(0L)

        val updatedSects = data.worldMapSects.map { s ->
            if (s.id == sectId) s.copy(allianceId = "", allianceStartYear = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        currentGameData = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances,
            spiritStones = newSpiritStones
        )

        eventService.addGameEvent("与${sect.name}解除结盟，灵石-${spiritStonePenalty}", EventType.WARNING)
        return Pair(true, "已解除结盟，消耗灵石${spiritStonePenalty}")
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
        val data = currentGameData
        val sect = data.worldMapSects.find { it.id == sectId }
        val envoy = currentDisciples.find { it.id == envoyDiscipleId }

        if (sect == null) {
            return Triple(false, "未找到目标宗门", 0)
        }

        if (sect.isPlayerSect) {
            return Triple(false, "不能与自己的宗门结盟", 0)
        }

        if (sect.allianceId.isNotEmpty()) {
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
            return Triple(false, "关系需达到至交(${GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR}以上)", 0)
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
     * 计算偏好乘数（用于关系增长）
     *
     * @param giftPreference 礼物偏好类型
     * @param isSpiritStone 是否为灵石
     * @return 偏好乘数
     */
    private fun calculatePreferenceMultiplier(
        giftPreference: GiftPreferenceType,
        isSpiritStone: Boolean = false
    ): Double {
        if (giftPreference == GiftPreferenceType.NONE) return 1.0
        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> 1.3
            else -> 1.0
        }
    }

    private fun calculatePreferenceRejectModifier(
        giftPreference: GiftPreferenceType,
        isSpiritStone: Boolean = false
    ): Int {
        if (giftPreference == GiftPreferenceType.NONE) return 0
        return when {
            isSpiritStone && giftPreference == GiftPreferenceType.SPIRIT_STONE -> -15
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

    // ==================== 内部依赖（由 GameEngine 注入）====================

}
