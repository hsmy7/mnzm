package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.GameConfig
import android.util.Log

/**
 * 兑换码服务 - 负责兑换码系统
 *
 * 职责域：
 * - 兑换码验证和执行 (redeemCode)
 * - 奖励发放（灵石、装备、功法、丹药、材料、灵草、种子、弟子）
 */
class RedeemCodeService(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val inventoryService: InventoryService,
    private val addEvent: (String, EventType) -> Unit
) {
    companion object {
        private const val TAG = "RedeemCodeService"
    }

    /**
     * 执行兑换码兑换
     *
     * @param code 兑换码
     * @param usedCodes 已使用的兑换码列表
     * @param currentYear 当前年份
     * @param currentMonth 当前月份
     * @return 兑换结果
     */
    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        val validationResult = RedeemCodeManager.validateCode(
            code = code,
            usedCodes = usedCodes,
            currentYear = currentYear,
            currentMonth = currentMonth
        )

        if (!validationResult.success) {
            return validationResult
        }

        val redeemCodeData = RedeemCodeManager.getRedeemCode(code) ?: return RedeemResult(
            success = false,
            message = "兑换码不存在"
        )

        val result = RedeemCodeManager.generateReward(redeemCodeData)

        if (!result.success) {
            return result
        }

        val data = _gameData.value

        result.rewards.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    _gameData.value = _gameData.value.copy(
                        spiritStones = _gameData.value.spiritStones + reward.quantity
                    )
                }
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(
                        minRarity = redeemCodeData.rarity,
                        maxRarity = redeemCodeData.rarity
                    )
                    inventoryService.addEquipment(equipment)
                }
                "manual" -> {
                    val template = ManualDatabase.getByNameAndRarity(reward.name, reward.rarity)
                    if (template != null) {
                        val manual = Manual(
                            id = java.util.UUID.randomUUID().toString(),
                            name = template.name,
                            rarity = reward.rarity,
                            description = template.description,
                            type = template.type,
                            stats = template.stats,
                            skillName = template.skillName,
                            skillDescription = template.skillDescription,
                            skillType = template.skillType,
                            skillDamageType = template.skillDamageType,
                            skillHits = template.skillHits,
                            skillDamageMultiplier = template.skillDamageMultiplier,
                            skillCooldown = template.skillCooldown,
                            skillMpCost = template.skillMpCost,
                            skillHealPercent = template.skillHealPercent,
                            skillHealType = template.skillHealType,
                            skillBuffType = template.skillBuffType,
                            skillBuffValue = template.skillBuffValue,
                            skillBuffDuration = template.skillBuffDuration,
                            minRealm = GameConfig.Realm.getMinRealmForRarity(reward.rarity),
                            quantity = reward.quantity
                        )
                        inventoryService.addManualToWarehouse(manual)
                    } else {
                        Log.w(TAG, "无法找到功法模板: ${reward.name}, rarity: ${reward.rarity}")
                    }
                }
                "pill" -> {
                    val pill = ItemDatabase.generateRandomPill(
                        minRarity = redeemCodeData.rarity,
                        maxRarity = redeemCodeData.rarity
                    )
                    inventoryService.addPillToWarehouse(pill)
                }
                "material" -> {
                    val material = ItemDatabase.generateRandomMaterial(
                        minRarity = redeemCodeData.rarity,
                        maxRarity = redeemCodeData.rarity
                    )
                    inventoryService.addMaterialToWarehouse(material)
                }
                "herb" -> {
                    val herbTemplate = HerbDatabase.generateRandomHerb(
                        minRarity = redeemCodeData.rarity,
                        maxRarity = redeemCodeData.rarity
                    )
                    val herb = Herb(
                        id = java.util.UUID.randomUUID().toString(),
                        name = herbTemplate.name,
                        rarity = herbTemplate.rarity,
                        description = herbTemplate.description,
                        category = herbTemplate.category,
                        quantity = 1
                    )
                    inventoryService.addHerbToWarehouse(herb)
                }
                "seed" -> {
                    val seedTemplate = HerbDatabase.generateRandomSeed(
                        minRarity = redeemCodeData.rarity,
                        maxRarity = redeemCodeData.rarity
                    )
                    val seed = Seed(
                        id = java.util.UUID.randomUUID().toString(),
                        name = seedTemplate.name,
                        rarity = seedTemplate.rarity,
                        description = seedTemplate.description,
                        growTime = seedTemplate.growTime,
                        yield = seedTemplate.yield,
                        quantity = 1
                    )
                    inventoryService.addSeedToWarehouse(seed)
                }
                "disciple" -> {
                    result.disciple?.let { disciple ->
                        val currentMonthValue = data.gameYear * 12 + data.gameMonth
                        val discipleWithRecruitTime = disciple.copy()
                        discipleWithRecruitTime.recruitedMonth = currentMonthValue
                        _disciples.value = _disciples.value + discipleWithRecruitTime
                    }
                }
            }
        }

        result.disciples.forEach { disciple ->
            val currentMonthValue = data.gameYear * 12 + data.gameMonth
            val discipleWithRecruitTime = disciple.copy()
            discipleWithRecruitTime.recruitedMonth = currentMonthValue
            _disciples.value = _disciples.value + discipleWithRecruitTime
        }

        _gameData.value = _gameData.value.copy(
            usedRedeemCodes = _gameData.value.usedRedeemCodes + code.uppercase()
        )

        val rewardDescription = result.rewards.joinToString("、") { reward ->
            when (reward.type) {
                "spiritStones" -> "${reward.quantity}灵石"
                "disciple" -> "弟子${reward.name}"
                else -> "${reward.name}x${reward.quantity}"
            }
        }

        addEvent("成功兑换码：$code，获得：$rewardDescription", EventType.SUCCESS)

        return RedeemResult(
            success = true,
            message = "兑换成功！获得：$rewardDescription",
            rewards = result.rewards,
            disciples = result.disciples
        )
    }
}
