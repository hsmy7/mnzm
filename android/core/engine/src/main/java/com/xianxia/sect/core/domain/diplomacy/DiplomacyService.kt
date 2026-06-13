package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.event.BattleCompletedEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.launch
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.math.roundToInt
import java.util.UUID

@Singleton
class DiplomacyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val eventBus: EventBusPort
) : DomainEventSubscriber {
    override val subscribedTypes: Set<String> = setOf("battle_completed")

    private val scope get() = scopeProvider.scope

    init {
        eventBus.subscribe(this)
    }

    override fun onEvent(event: DomainEvent) {
        if (event !is BattleCompletedEvent || !event.result.victory) return
        scope.launch {
            val data = stateStore.gameData.value
            val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return@launch
            val targetSect = data.worldMapSects.find {
                it.isUnderAttack && it.attackerSectId == playerSect.id
            }
            if (targetSect != null) {
                val currentFavor = data.sectRelations.find {
                    (it.sectId1 == playerSect.id && it.sectId2 == targetSect.id) ||
                    (it.sectId1 == targetSect.id && it.sectId2 == playerSect.id)
                }?.favor ?: 0
                val newFavor = (currentFavor - 5).coerceAtLeast(0)
                stateStore.update { gameData = data.copy(
                    sectRelations = updateSectRelationFavor(
                        data.sectRelations, playerSect.id, targetSect.id, newFavor, data.gameYear
                    )
                ) }
            }
        }
    }

    private val discipleTables: DiscipleTables
        get() = stateStore.discipleTables

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
        val data = stateStore.gameData.value
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

        scope.launch { stateStore.update { gameData = data.copy(
            spiritStones = data.spiritStones - tierConfig.spiritStones,
            sectDetails = updatedDetails,
            sectRelations = updatedRelations
        ) } }

        val responseText = SectResponseTexts.getAcceptResponse(sect.level, "spirit_stones", tierConfig.name, favorIncrease)

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

        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
            ?: return Pair(false, "未找到目标宗门")
        val envoyId = envoyDiscipleId.toIntOrNull()
        if (envoyId == null || !discipleTables.ids.contains(envoyId)) return Pair(false, "未找到使者弟子")
        val envoy = discipleTables.assemble(envoyId)

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
                when {
                    s.id == sectId -> s.copy(allianceId = alliance.id, allianceStartYear = data.gameYear)
                    s.isPlayerSect -> s.copy(allianceId = alliance.id, allianceStartYear = data.gameYear)
                    else -> s
                }
            }

            scope.launch { stateStore.update { gameData = data.copy(
                spiritStones = data.spiritStones - cost,
                alliances = data.alliances + alliance,
                worldMapSects = updatedSects
            ) } }

            return Pair(true, "结盟成功！")
        } else {
            scope.launch { stateStore.update { gameData = data.copy(spiritStones = data.spiritStones - cost / 2) } }
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
        val data = stateStore.gameData.value
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
            if (alliance.sectIds.contains(s.id)) s.copy(allianceId = "", allianceStartYear = 0)
            else s
        }

        val updatedAlliances = data.alliances.filter { it.id != alliance.id }

        scope.launch { stateStore.update { gameData = data.copy(
            worldMapSects = updatedSects,
            alliances = updatedAlliances,
            spiritStones = newSpiritStones
        ) } }

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
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
        val envoyId = envoyDiscipleId.toIntOrNull()
        val envoy = if (envoyId != null && discipleTables.ids.contains(envoyId)) discipleTables.assemble(envoyId) else null

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

    // ==================== 宗门交易系统 ====================

    private data class SectTradeValidation(
        val sect: WorldSect,
        val item: MerchantItem,
        val actualQuantity: Int,
        val totalPrice: Long,
        val updatedSectDetails: Map<String, SectDetail>
    )

    private val SECT_TRADE_RARITY_PROBABILITIES = mapOf(
        6 to 0.003,
        5 to 0.027,
        4 to 0.05,
        3 to 0.12,
        2 to 0.40,
        1 to 0.40
    )

    fun generateSectTradeItems(year: Int, sectId: String? = null): List<MerchantItem> {
        val items = mutableListOf<MerchantItem>()
        val random = if (sectId != null) {
            Random(sectId.hashCode().toLong() + year)
        } else {
            Random.Default
        }

        val itemCount = 20
        val generatedNames = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = itemCount * 3

        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            val type = listOf("equipment", "manual", "pill", "material", "herb", "seed").random(random)
            val rarity = selectRarityByMerchantProbabilities(random)

            fun calcStock(t: String, r: Int): Int {
                val isConsumable = t in listOf("herb", "seed", "material")
                return if (isConsumable) {
                    when (r) {
                        6 -> random.nextInt(3, 8)
                        5 -> random.nextInt(3, 8)
                        4 -> random.nextInt(5, 11)
                        3 -> random.nextInt(5, 13)
                        2 -> random.nextInt(5, 16)
                        else -> random.nextInt(7, 16)
                    }
                } else {
                    when (r) {
                        6 -> random.nextInt(1, 4)
                        5 -> random.nextInt(1, 4)
                        4 -> random.nextInt(1, 6)
                        3 -> random.nextInt(1, 6)
                        2 -> random.nextInt(1, 6)
                        else -> random.nextInt(1, 6)
                    }
                }
            }

            val item = when (type) {
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(rarity, rarity)
                    val template = EquipmentDatabase.getTemplateByName(equipment.name)
                    val basePrice = ((template?.price ?: GameConfig.Rarity.get(rarity).basePrice) * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = equipment.name,
                        type = "equipment",
                        itemId = equipment.id,
                        rarity = equipment.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    val template = ManualDatabase.getByName(manual.name)
                    val basePrice = ((template?.price ?: GameConfig.Rarity.get(rarity).basePrice) * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = manual.name,
                        type = "manual",
                        itemId = manual.id,
                        rarity = manual.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "pill" -> {
                    val pillTemplates = ItemDatabase.getPillsByRarity(rarity)
                    if (pillTemplates.isEmpty()) continue
                    val template = pillTemplates.random(random)
                    val pill = ItemDatabase.createPillFromTemplate(template)
                    val basePrice = (template.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = pill.id,
                        rarity = pill.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1,
                        grade = pill.grade.displayName
                    )
                }
                "material" -> {
                    val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                    if (materials.isEmpty()) continue
                    val material = materials.random(random)
                    val basePrice = (material.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = material.name,
                        type = "material",
                        itemId = material.id,
                        rarity = material.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "herb" -> {
                    val herbs = HerbDatabase.getByRarity(rarity)
                    if (herbs.isEmpty()) continue
                    val herb = herbs.random(random)
                    val basePrice = (herb.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = herb.name,
                        type = "herb",
                        itemId = herb.id,
                        rarity = herb.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "seed" -> {
                    val seeds = HerbDatabase.getSeedsByRarity(rarity)
                    if (seeds.isEmpty()) continue
                    val seed = seeds.random(random)
                    val basePrice = (seed.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = seed.name,
                        type = "seed",
                        itemId = seed.id,
                        rarity = seed.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                else -> continue
            }

            if (!generatedNames.contains(item.name)) {
                generatedNames.add(item.name)
                items.add(item)
            }
        }

        items.sortByDescending { it.rarity }
        return items
    }

    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()
        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)

        val currentYear = data.gameYear
        val shouldRefresh = currentYear - sectDetail.tradeLastRefreshYear >= 3 || sectDetail.tradeItems.isEmpty()

        if (shouldRefresh) {
            val newItems = generateSectTradeItems(currentYear, sectId)
            val updatedSectDetails = data.sectDetails.toMutableMap()
            updatedSectDetails[sectId] = sectDetail.copy(
                tradeItems = newItems,
                tradeLastRefreshYear = currentYear
            )
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) {
                ts.gameData = ts.gameData.copy(sectDetails = updatedSectDetails)
            } else {
                scope.launch { stateStore.update { gameData = gameData.copy(sectDetails = updatedSectDetails) } }
            }
            return newItems
        }

        return sectDetail.tradeItems
    }

    private fun validateSectTrade(data: GameData, sectId: String, itemId: String, quantity: Int): SectTradeValidation? {
        val sect = data.worldMapSects.find { it.id == sectId } ?: return null
        val sectDetail = data.sectDetails[sectId]
        val tradeItems = sectDetail?.tradeItems ?: emptyList()
        val item = tradeItems.find { it.id == itemId } ?: return null

        val relation = getSectRelation(data, sectId)
        val relationLevel = GameUtils.getSectRelationLevel(relation)
        if (relationLevel !in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)) {
            return null
        }

        val maxAllowedRarity = relationLevel.maxAllowedRarity
        if (item.rarity > maxAllowedRarity) {
            return null
        }

        val actualQuantity = minOf(quantity, item.quantity)
        val priceMultiplier = calculatePriceMultiplier(data, sectId)
        val totalPrice = (item.price * priceMultiplier).toLong() * actualQuantity

        if (data.spiritStones < totalPrice) {
            return null
        }

        val capacityOk = when (item.type.lowercase()) {
            "equipment" -> inventorySystem.canAddItems(actualQuantity)
            "manual" -> {
                val t = ManualDatabase.getByName(item.name)
                inventorySystem.canAddManual(item.name, item.rarity, t?.type ?: ManualType.SUPPORT)
            }
            "pill" -> {
                val t = PillRecipeDatabase.getRecipeByName(item.name)
                val grade = item.grade?.let { gn -> PillGrade.entries.find { it.displayName == gn } } ?: PillGrade.MEDIUM
                inventorySystem.canAddPill(item.name, item.rarity, t?.category ?: PillCategory.FUNCTIONAL, grade)
            }
            "material" -> {
                val t = BeastMaterialDatabase.getMaterialByName(item.name)
                val cat = t?.category?.let { try { MaterialCategory.valueOf(it) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE } } ?: MaterialCategory.BEAST_HIDE
                inventorySystem.canAddMaterial(item.name, item.rarity, cat)
            }
            "herb" -> {
                val t = HerbDatabase.getHerbByName(item.name)
                inventorySystem.canAddHerb(item.name, item.rarity, t?.category ?: "spirit")
            }
            "seed" -> {
                val t = HerbDatabase.getSeedByName(item.name)
                inventorySystem.canAddSeed(item.name, item.rarity, t?.growTime ?: 12)
            }
            else -> false
        }
        if (!capacityOk) {
            return null
        }

        val updatedTradeItems = if (item.quantity > actualQuantity) {
            tradeItems.map {
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            tradeItems.filter { it.id != itemId }
        }

        val updatedSectDetails = data.sectDetails.toMutableMap()
        if (sectDetail != null) {
            updatedSectDetails[sectId] = sectDetail.copy(tradeItems = updatedTradeItems)
        }

        return SectTradeValidation(sect, item, actualQuantity, totalPrice, updatedSectDetails)
    }

    @Deprecated(
        "Use buyFromSectTradeSync() — scope.launch 在 swapFromShadow 期间存在竞态风险",
        ReplaceWith("buyFromSectTradeSync(sectId, itemId, quantity)")
    )
    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) {
        val data = stateStore.gameData.value
        val v = validateSectTrade(data, sectId, itemId, quantity) ?: return

        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    spiritStones = gameData.spiritStones - v.totalPrice,
                    sectDetails = v.updatedSectDetails
                )
                addSectTradeItemToMutableState(v.item, v.actualQuantity)
            }
        }
    }

    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) {
        val data = stateStore.gameData.value
        val v = validateSectTrade(data, sectId, itemId, quantity) ?: return

        stateStore.update {
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - v.totalPrice,
                sectDetails = v.updatedSectDetails
            )
            addSectTradeItemToMutableState(v.item, v.actualQuantity)
        }
    }

    private fun MutableGameState.addSectTradeItemToMutableState(item: MerchantItem, actualQuantity: Int) {
        when (item.type.lowercase()) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(item).copy(quantity = actualQuantity)
                val existing = equipmentStacks.find { it.name == eq.name && it.rarity == eq.rarity && it.slot == eq.slot }
                if (existing != null) {
                    val newQty = (existing.quantity + eq.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                    equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    equipmentStacks = equipmentStacks + eq
                }
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(item).copy(quantity = actualQuantity)
                val existing = manualStacks.find { it.name == m.name && it.rarity == m.rarity && it.type == m.type }
                if (existing != null) {
                    val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("manual_stack"))
                    manualStacks = manualStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    manualStacks = manualStacks + m
                }
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(item).copy(quantity = actualQuantity)
                val existing = pills.find { it.name == p.name && it.rarity == p.rarity && it.category == p.category && it.grade == p.grade }
                if (existing != null) {
                    val newQty = (existing.quantity + p.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                    pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    pills = pills + p
                }
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(item).copy(quantity = actualQuantity)
                val existing = materials.find { it.name == m.name && it.rarity == m.rarity && it.category == m.category }
                if (existing != null) {
                    val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                    materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    materials = materials + m
                }
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(item).copy(quantity = actualQuantity)
                val existing = herbs.find { it.name == h.name && it.rarity == h.rarity && it.category == h.category }
                if (existing != null) {
                    val newQty = (existing.quantity + h.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                    herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    herbs = herbs + h
                }
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(item).copy(quantity = actualQuantity)
                val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime }
                if (existing != null) {
                    val newQty = (existing.quantity + s.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                    seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    seeds = seeds + s
                }
            }
        }
    }

    private fun calculatePriceMultiplier(data: GameData, sectId: String): Double =
        GameUtils.calculateSectTradePriceMultiplier(data.worldMapSects, data.sectRelations, data.alliances, sectId)

    private fun getSectRelation(data: GameData, sectId: String): Int =
        GameUtils.getSectRelation(data.worldMapSects, data.sectRelations, sectId)

    private fun selectRarityByMerchantProbabilities(random: Random): Int {
        val rand = random.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in SECT_TRADE_RARITY_PROBABILITIES.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1
    }
}
