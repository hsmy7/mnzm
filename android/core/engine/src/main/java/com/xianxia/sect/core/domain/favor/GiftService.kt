package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.config.SectResponseTexts
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GiftPreferenceType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.PresentationRandom
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton
import javax.inject.Provider
import com.xianxia.sect.core.domain.calculateGiftFavorIncrease
import com.xianxia.sect.core.domain.calculatePreferenceRejectModifier
import com.xianxia.sect.core.domain.calculateRejectProbability
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.long
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.put

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
    private val stateStore: GameStateStore,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val rngManager: GameRngManager,
    /**
     * C++ 引擎核心（FAVOR_GIFT native 转发通道；batch-09）。默认 null
     * 仅供测试直构——null 或 flag 关闭时恒走 Kotlin 原路径。
     *
     * 经 [Provider] 注入：GameEngineCore 构造链上经 CultivationService →
     * CultivationEventProcessor 反向依赖本服务，直接注入会成 Dagger 环；
     * Provider 为惰性边（Dagger 官方破环手段）。
     */
    private val gameEngineCoreProvider: Provider<GameEngineCore>? = null,
    /**
     * 表现随机源（ADR R3）——送礼反馈文案（接受/拒绝措辞）是纯表现，
     * 走独立表现流：原先 `SectResponseTexts` 内部用 `responses.random()`
     *（`Random.Default`，进程启动随机、不入档）属未受治理的第二类入口。
     */
    private val presentationRandom: PresentationRandom
) {
    /** native 转发用引擎核心（无 Provider 时为 null → 调用点走 Kotlin 原路径） */
    private val gameEngineCore: GameEngineCore? get() = gameEngineCoreProvider?.get()

    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
    companion object {
        private const val TAG = "GiftService"
    }

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
        // AUTHORITATIVE 转发（FAVOR_GIFT，batch-09 下沉）：C++ 校验链 +
        // 拒绝 roll（SYSTEM 分区）+ 好感/送礼年/扣费事务单一真相；失败
        // 信封（校验失败——Kotlin 同位置早退零抽取）回退 Kotlin 原路径。
        giftNative(sectId, tier, bypassYearLimit)?.let { return it }

        val data = stateStore.gameData.value
        val ready = when (val prep = prepareGift(
            data = data,
            sectId = sectId,
            tier = tier,
            bypassYearLimit = bypassYearLimit
        )) {
            is GiftPreparation.Rejected -> return prep.result
            is GiftPreparation.Ready -> prep
        }

        val isRejected = rng.nextInt(100) < ready.rejectProbability

        if (isRejected) {
            return GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = SectResponseTexts.getRejectResponse(
                    ready.sect.level, "spirit_stones", ready.tierConfig.name,
                    presentationRandom.asKotlinRandom()
                )
            )
        }

        // 送礼成功：计算好感度增量
        val favor = computeGiftFavorIncrease(
            data = data,
            sectId = sectId,
            sectLevel = ready.sect.level,
            tier = tier
        )

        // 缓和关系绕过年度限制时不更新 lastGiftYear
        val shouldUpdateGiftYear = !bypassYearLimit

        var deductSucceeded = false
        stateStore.update {
            deductSucceeded = applyGiftSpiritStones(
                sectId = sectId,
                tier = tier,
                tierConfig = ready.tierConfig,
                newFavor = favor.newFavor,
                shouldUpdateGiftYear = shouldUpdateGiftYear
            )
        }

        if (!deductSucceeded) {
            return giftFailure(
                responseType = "failed",
                message = "灵石不足，需要${ready.tierConfig.spiritStones}灵石"
            )
        }

        return GiftResult(
            success = true,
            rejected = false,
            favorChange = favor.favorIncrease,
            newFavor = favor.newFavor,
            responseType = "accept",
            message = SectResponseTexts.getAcceptResponse(
                ready.sect.level, "spirit_stones", ready.tierConfig.name, favor.favorIncrease,
                presentationRandom.asKotlinRandom()
            )
        )
    }

    /** 送礼前置准备结果 */
    private sealed interface GiftPreparation {
        /** 校验通过：可继续送礼流程 */
        data class Ready(
            val sect: WorldSect,
            val tierConfig: GiftConfig.SpiritStoneGiftTier,
            val rejectProbability: Int
        ) : GiftPreparation

        /** 校验失败：直接返回该结果 */
        data class Rejected(val result: GiftResult) : GiftPreparation
    }

    /** 好感度计算结果 */
    private data class GiftFavorResult(
        val favorIncrease: Int,
        val newFavor: Int
    )

    /** 送礼失败结果构造 */
    private fun giftFailure(responseType: String, message: String, rejected: Boolean = false): GiftResult =
        GiftResult(
            success = false,
            rejected = rejected,
            responseType = responseType,
            message = message
        )

    /** 送礼前置校验与拒绝概率：目标/玩家/年度/档位/灵石校验 */
    @Suppress("ReturnCount")
    private fun prepareGift(
        data: GameData,
        sectId: String,
        tier: Int,
        bypassYearLimit: Boolean
    ): GiftPreparation {
        // 查找目标宗门
        val sect = data.worldMapSects.find { it.id == sectId }
        if (sect == null) {
            return GiftPreparation.Rejected(
                giftFailure(responseType = "sect_not_found", message = "未找到目标宗门")
            )
        }

        // 检查是否为玩家宗门
        if (sect.isPlayerSect) {
            return GiftPreparation.Rejected(
                giftFailure(responseType = "invalid_target", message = "不能向自己的宗门送礼")
            )
        }

        // 检查每年一次限制（缓和关系可绕过）
        if (!bypassYearLimit && (data.sectDetails[sect.id]?.lastGiftYear ?: 0) == data.gameYear) {
            return GiftPreparation.Rejected(
                giftFailure(
                    responseType = "already_gifted",
                    message = "今年已经向${sect.name}送过礼了，请明年再来"
                )
            )
        }

        // 获取档位配置
        val tierConfig = GiftConfig.SpiritStoneGiftConfig.getTier(tier)
        if (tierConfig == null) {
            return GiftPreparation.Rejected(
                giftFailure(responseType = "invalid_tier", message = "无效的送礼档位")
            )
        }

        // 检查灵石是否足够
        if (data.spiritStones < tierConfig.spiritStones) {
            return GiftPreparation.Rejected(
                giftFailure(
                    responseType = "insufficient_resources",
                    message = "灵石不足，需要${tierConfig.spiritStones}灵石"
                )
            )
        }

        // 计算拒绝概率（灵石送礼使用档位对应的虚拟稀有度）
        val rejectProbability = computeRejectProbability(
            sectLevel = sect.level,
            giftPreference = data.sectDetails[sect.id]?.giftPreference ?: GiftPreferenceType.NONE,
            tier = tier
        )
        return GiftPreparation.Ready(
            sect = sect,
            tierConfig = tierConfig,
            rejectProbability = rejectProbability
        )
    }

    /** 拒绝概率计算：档位虚拟稀有度 + 偏好修正，钳制 0..100 */
    private fun computeRejectProbability(
        sectLevel: Int,
        giftPreference: GiftPreferenceType,
        tier: Int
    ): Int {
        // 计算拒绝概率（灵石送礼使用档位对应的虚拟稀有度）
        val virtualRarity = (tier + 1).coerceIn(2, 5)
        val baseRejectProbability = calculateRejectProbability(sectLevel, virtualRarity)
        val preferenceRejectModifier = calculatePreferenceRejectModifier(
            giftPreference,
            isSpiritStone = true
        )
        return (baseRejectProbability + preferenceRejectModifier).coerceIn(0, 100)
    }

    /** 好感度增量计算：当前好感 + 档位/境界/偏好 → 新好感 */
    private fun computeGiftFavorIncrease(
        data: GameData,
        sectId: String,
        sectLevel: Int,
        tier: Int
    ): GiftFavorResult {
        // 送礼成功：计算好感度增量
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val currentFavor = if (playerSect != null) {
            FavorDomain.findFavor(data.sectRelations, playerSect.id, sectId)
        } else 0

        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)
        val favorIncrease = calculateGiftFavorIncrease(
            currentFavor, tier, sectLevel, sectDetail.giftPreference
        )
        val newFavor = (currentFavor + favorIncrease).coerceIn(0, com.xianxia.sect.core.config.FavorConfig.MAX_FAVOR)
        return GiftFavorResult(
            favorIncrease = favorIncrease,
            newFavor = newFavor
        )
    }

    /** 送礼事务写入：扣灵石 + 更新好感度/送礼年份，返回是否成功 */
    @Suppress("ReturnCount")
    private fun MutableGameState.applyGiftSpiritStones(
        sectId: String,
        tier: Int,
        tierConfig: GiftConfig.SpiritStoneGiftTier,
        newFavor: Int,
        shouldUpdateGiftYear: Boolean
    ): Boolean {
        val livePlayerSect = gameData.worldMapSects.find { it.isPlayerSect }
        if (livePlayerSect == null) return false

        val acquaintedRelations = FavorDomain.setAcquainted(
            gameData.sectRelations, livePlayerSect.id, sectId, gameData.gameYear
        )
        val liveUpdatedRelations = FavorDomain.updateFavor(
            acquaintedRelations, livePlayerSect.id, sectId, newFavor, gameData.gameYear
        )

        val liveUpdatedDetails = gameData.sectDetails.toMutableMap()
        if (shouldUpdateGiftYear) {
            liveUpdatedDetails[sectId] = (liveUpdatedDetails[sectId]
                ?: SectDetail(sectId = sectId))
                .copy(lastGiftYear = gameData.gameYear)
        }

        val deductResult = spiritStoneWallet.deduct(this, tierConfig.spiritStones.toLong(), SpiritStoneGrade.LOW,
            SpiritStoneReason.Gift, SpiritStoneSource.Internal)
        if (deductResult !is DeductResult.Success) {
            DomainLog.w(TAG, "送礼失败：灵石不足(tier=$tier, need=${tierConfig.spiritStones})")
            return false
        }
        gameData = gameData.copy(
            sectDetails = liveUpdatedDetails,
            sectRelations = liveUpdatedRelations
        )
        return true
    }

    /**
     * 赠礼 native 转发（C++ diplomacy_tx.h giftSpiritStonesTransaction）。
     * 返回 null = 降级/校验失败信封（调用方回退 Kotlin 原路径——校验失败
     * 臂 Kotlin 零抽取零写入，双臂行为一致）；非 null = roll 已消费的终态，
     * 按 responseType 重建 GiftResult（message 响应模板留 Kotlin——
     * SectResponseTexts.random() 消费 kotlin Random.Default 非游戏分区，
     * 不在 C++ 协议面）。
     */
    @Suppress("ReturnCount")  // 降级契约：engineCore 缺失/flag 关/链路失败/未知 outcome 逐级返回 null
    private fun giftNative(sectId: String, tier: Int, bypassYearLimit: Boolean): GiftResult? {
        val engineCore = gameEngineCore ?: return null
        if (!NativeEngineFlag.authoritative) return null
        val data = GameEngineNativeOps.tryExecuteNative(
            stateSyncService = engineCore.stateSyncServiceRef,
            actionId = ActionIds.FAVOR_GIFT,
            paramsJson = params {
                put("sectId", sectId)
                put("tier", tier)
                put("bypassYearLimit", bypassYearLimit)
            }
        ) ?: return null
        val tierName = GiftConfig.SpiritStoneGiftConfig.getTier(tier)?.name ?: ""
        return when (val outcome = data.str("outcome")) {
            "accept" -> {
                val favorChange = data.long("favorChange")?.toInt() ?: 0
                GiftResult(
                    success = true,
                    rejected = false,
                    favorChange = favorChange,
                    newFavor = data.long("newFavor")?.toInt() ?: 0,
                    responseType = "accept",
                    message = SectResponseTexts.getAcceptResponse(
                        data.long("sectLevel")?.toInt() ?: 0,
                        "spirit_stones", tierName, favorChange,
                        presentationRandom.asKotlinRandom()
                    )
                )
            }
            "rejected" -> GiftResult(
                success = false,
                rejected = true,
                responseType = "rejected",
                message = SectResponseTexts.getRejectResponse(
                    data.long("sectLevel")?.toInt() ?: 0,
                    "spirit_stones", tierName,
                    presentationRandom.asKotlinRandom()
                )
            )
            "failed" -> GiftResult(
                success = false,
                responseType = "failed",
                message = "灵石不足，需要${data.long("neededStones")}灵石"
            )
            else -> {
                DomainLog.w(TAG, "giftNative: unknown outcome=$outcome")
                null
            }
        }
    }
}
