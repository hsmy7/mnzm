package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.GiftPreferenceType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.state.GameStateStore
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

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

/**
 * 宗门送礼服务。
 *
 * 从 [com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService] 中提取的送礼相关逻辑。
 * 委托 [FavorDomain] 做好感度计算，通过 [GameStateStore] 原子写入状态。
 */
@Singleton
class GiftService @Inject constructor(
    private val stateStore: GameStateStore
) {
    /**
     * 向宗门赠送灵石
     *
     * @param sectId 目标宗门ID
     * @param tier 送礼档位 (1-4)
     * @param bypassYearLimit 是否绕过每年一次送礼限制（用于缓和关系等紧急外交场合）
     * @return 送礼结果
     */
    suspend fun giftSpiritStones(
        sectId: String,
        tier: Int,
        bypassYearLimit: Boolean = false
    ): GiftResult {
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

        // 检查每年一次限制（缓和关系可绕过）
        if (!bypassYearLimit && (data.sectDetails[sect.id]?.lastGiftYear ?: 0) == currentYear) {
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
        val baseRejectProbability = FavorDomain.calculateRejectProbability(sect.level, virtualRarity)
        val preferenceRejectModifier = FavorDomain.calculatePreferenceRejectModifier(
            data.sectDetails[sect.id]?.giftPreference ?: GiftPreferenceType.NONE,
            isSpiritStone = true
        )
        val rejectProbability = (baseRejectProbability + preferenceRejectModifier).coerceIn(0, 100)

        val isRejected = Random.nextInt(100) < rejectProbability

        if (isRejected) {
            val responseText = SectResponseTexts.getRejectResponse(
                sect.level, "spirit_stones", tierConfig.name
            )

            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = responseText
            )
        }

        // 送礼成功：计算好感度增量
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val currentFavor = if (playerSect != null) {
            FavorDomain.findFavor(data.sectRelations, playerSect.id, sectId)
        } else 0

        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)
        val favorIncrease = FavorDomain.calculateGiftFavorIncrease(
            currentFavor, tier, sect.level, sectDetail.giftPreference
        )
        val newFavor = (currentFavor + favorIncrease).coerceIn(0, GameConfig.Diplomacy.MAX_FAVOR)

        // 缓和关系绕过年度限制时不更新 lastGiftYear
        val shouldUpdateGiftYear = !bypassYearLimit

        stateStore.update {
            val livePlayerSect = gameData.worldMapSects.find { it.isPlayerSect }
            if (livePlayerSect == null) return@update

            val liveUpdatedRelations = FavorDomain.updateFavor(
                gameData.sectRelations, livePlayerSect.id, sectId, newFavor, currentYear
            )

            val liveUpdatedDetails = gameData.sectDetails.toMutableMap()
            if (shouldUpdateGiftYear) {
                liveUpdatedDetails[sectId] = (liveUpdatedDetails[sectId]
                    ?: SectDetail(sectId = sectId))
                    .copy(lastGiftYear = currentYear)
            }

            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - tierConfig.spiritStones,
                sectDetails = liveUpdatedDetails,
                sectRelations = liveUpdatedRelations
            )
        }

        val responseText = SectResponseTexts.getAcceptResponse(
            sect.level, "spirit_stones", tierConfig.name, favorIncrease
        )

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = favorIncrease,
            newFavor = newFavor,
            responseType = "accept",
            message = responseText
        )
    }
}
