package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.system.SystemWallClock
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.BuildConfig
import com.xianxia.sect.core.engine.REWARD_TYPE_FRAGMENT
import com.xianxia.sect.core.platform.ApkSigningCertificateSource
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton






/** 服务端遗留的弟子奖励类型字面量：只可能来自旧版服务端下发，客户端不再直造弟子 */
private const val API_REWARD_TYPE_DISCIPLE = "disciple"

@Serializable
data class RedeemApiResponse(
    val success: Boolean = false,
    val message: String = "",
    val rewards: List<RedeemApiReward> = emptyList()
)

@Serializable
data class RedeemApiReward(
    val type: String = "",
    val name: String = "",
    val quantity: Int = 0,
    val rarity: Int = 1,
    /** [REWARD_TYPE_FRAGMENT] 奖励的角色模板 id（取值域见 [CharacterTemplateDb]）；其余类型为 null */
    val templateId: String? = null
)

@GameService("RedeemCodeService")
@Singleton
class RedeemCodeService @Inject constructor(
    private val stateStore: GameStateStore,
    private val httpClient: HttpClientProvider,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val gameRngManager: com.xianxia.sect.core.util.GameRngManager,
    private val signingCertificates: ApkSigningCertificateSource,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
    /**
     * 寻访域门面：兑换码的角色碎片**唯一**入账口（[GachaFacade.grantFragments]）。
     *
     * 本服务不直接读写 `gachaFragmentCounts` / `gachaStarMap`——碎片与升星是寻访域账本，
     * 抽卡/兑换码/邮件/活动共用同一个入口才能保证升星口径一致。
     * internal 供 [RedeemCodeFragmentOps]（碎片入账域）读取。
     */
    internal val gachaFacade: GachaFacade,
    /**
     * 游戏语义墙钟（SR-5）：兑换码限流/使用记录的唯一取时点——一次兑换只采样一次，
     * 4 层限流与清理起算共用同一时刻（收敛前分散 5 处裸读 `System.currentTimeMillis`）。
     * 默认 [SystemWallClock] 供测试直构，生产由 Hilt 注入 CalibratedWallClock。
     */
    private val wallClock: WallClock = SystemWallClock
) {
    companion object {
        /** internal 供 [RedeemCodeFragmentOps]（碎片入账域）复用同一日志标签。 */
        internal const val TAG = "RedeemCodeService"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        val (guardFailure, trimmedCode) = validateAndTrimRedeemCode(code, usedCodes)
        if (guardFailure != null || trimmedCode == null) {
            return guardFailure ?: RedeemResult(success = false, message = "兑换码格式无效")
        }

        val serverResult = tryServerRedeem(trimmedCode)
        if (serverResult != null) return serverResult

        DomainLog.w(TAG, "Server redeem unavailable, falling back to local validation with APK signature check")
        if (!verifyApkSignature()) {
            return RedeemResult(success = false, message = "应用签名校验失败，无法使用离线兑换")
        }

        return localRedeem(trimmedCode, usedCodes, currentYear, currentMonth)
    }

    /**
     * 本地格式与重复使用校验：3-32 位字母数字/连字符 →
     * 输入校验 → 未使用检查。
     *
     * @return first = 校验失败结果（null = 通过）；second = 修剪后的兑换码
     */
    private fun validateAndTrimRedeemCode(code: String, usedCodes: List<String>): Pair<RedeemResult?, String?> {
        val trimmedCode = code.trim().takeIf {
            it.length in 3..32 && it.all { c -> c.isLetterOrDigit() || c == '-' }
        } ?: return RedeemResult(success = false, message = "兑换码格式无效") to null

        val errorMsg = InputValidator.validateRedeemCode(trimmedCode)
        if (errorMsg != null) {
            return RedeemResult(success = false, message = errorMsg) to null
        }

        if (trimmedCode in usedCodes) {
            return RedeemResult(success = false, message = "该兑换码已使用") to null
        }
        return null to trimmedCode
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private suspend fun tryServerRedeem(code: String): RedeemResult? {
        return try {
            val url = "${BuildConfig.API_BASE_URL}redeem/verify"
            val requestBody = """{"code":"$code"}"""
            val body = httpClient.post(url, requestBody)

            val apiResult = json.decodeFromString<RedeemApiResponse>(body)

            if (apiResult.success) {
                // 模板合法性先于任何写入校验：错配的碎片奖励整单拒发且不消耗兑换码，
                // 避免"码已核销、碎片未到账"的静默损失（与本地臂 generateReward 同口径）
                val invalidFragment = apiResult.rewards.firstOrNull {
                    it.type == REWARD_TYPE_FRAGMENT && !isKnownCharacterTemplate(it.templateId)
                }
                if (invalidFragment != null) {
                    DomainLog.w(
                        TAG,
                        "Server fragment reward rejected: unknown templateId=${invalidFragment.templateId}, code=$code"
                    )
                    return RedeemResult(
                        success = false,
                        message = "兑换码奖励配置异常，本次未扣除兑换码"
                    )
                }
                val allSucceeded = applyApiRewardsAndMarkUsed(code, apiResult.rewards)
                val result = if (allSucceeded) {
                    // 兑换码已在事务内标记已用 → 碎片此刻才入账，失败重试不会双增
                    grantApiFragmentRewards(apiResult.rewards)
                    RedeemResult(success = true, message = apiResult.message)
                } else {
                    RedeemResult(
                        success = false,
                        capacityInsufficient = true,
                        message = "仓库容量不足，兑换码未使用，清理仓库后可重新兑换"
                    )
                }
                enqueueRewardCardsFromApiRewards(apiResult.rewards)
                result
            } else {
                RedeemResult(success = false, message = apiResult.message)
            }
        } catch (e: CancellationException) {
            throw e // 取消穿透: 取消时中止兑换, 不降级本地重兑(双发风险)
        } catch (e: Exception) {
            DomainLog.w(TAG, "Server redeem failed, will fallback to local", e)
            null
        }
    }

    /**
     * 服务端奖励落地：物品发放 + 灵石 + 标记已用，单事务原子写入。
     *
     * 角色碎片条目**不在此处入账**——碎片是寻访域账本，须走
     * [com.xianxia.sect.core.engine.domain.gacha.GachaFacade.grantFragments]，而本函数运行在
     * `stateStore.update` 事务内；调用方在事务成功返回后经
     * [grantApiFragmentRewards] 统一发放。
     *
     * @return true=全部成功；false=任一物品发放失败/溢出（兑换码不标记已用，可清理后重试）
     */
    private suspend fun applyApiRewardsAndMarkUsed(code: String, rewards: List<RedeemApiReward>): Boolean {
        // 灵石发放须在物品全部成功之后（失败时灵石不入账，
        // 避免"灵石已入账 + 兑换码保留"重试时灵石双发）
        val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
        var allSucceeded = true
        stateStore.update {
            inventorySystem.withOverflowMailSuppressed {
            inventorySystem.withTrackingSource("redeem") {
                // 任一物品发放失败/溢出（仓库满）时不标记兑换码已用，
                // 玩家清理仓库后可重新兑换，奖励不丢失。
                // 灵石与角色碎片都不入仓库、都不在本事务内发放（各自有独立账本）
                allSucceeded = rewards.filter {
                    it.type != "spiritStones" && it.type != REWARD_TYPE_FRAGMENT
                }.all { reward ->
                    applyRedeemReward(reward.type, reward.name, reward.quantity, reward.rarity, reward.rarity, mailRng)
                }
                if (allSucceeded) {
                    // 物品全部成功 → 发放灵石 + 标记已用（灵石发放独立事务，成功路径才执行）
                    rewards.filter { it.type == "spiritStones" }.forEach { reward ->
                        spiritStoneWallet.add(this, reward.quantity.toLong(), SpiritStoneGrade.LOW,
                            SpiritStoneSource.RedeemCode)
                    }
                    gameData = gameData.copy(
                        usedRedeemCodes = (gameData.usedRedeemCodes + code.uppercase(java.util.Locale.getDefault()))
                            .distinct()
                            .takeLast(GameData.MAX_REDEEM_CODES)
                    )
                }
            }
        }
        }
        return allSucceeded
    }

    /**
     * 记录 addXxx 三态结果。
     *
     * @return true=全部成功；false=失败/溢出（调用方
     * 据此不标记兑换码已用，玩家清理仓库后可重新兑换，奖励不丢失）
     */
    private fun handleRedeemResult(result: DomainResult<*>, label: String): Boolean {
        return when (result) {
            is DomainResult.Success -> true
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "$label 仓库已满，溢出 ${result.overflow} 个")
                false
            }
            is DomainResult.Failure -> {
                DomainLog.w(TAG, "$label 发放失败: ${result.error}")
                false
            }
        }
    }

    /**
     * 单类兑换奖励发放——统一委托 [InventorySystem.addXxx]（走 StackableItemStore 合并），
     * 消除手写"找第一个堆叠 + 追加"导致同种物品分裂为多个堆叠的问题。
     *
     * 本函数只覆盖**入仓库的物品类**奖励：灵石走钱包、角色碎片走
     * [GachaFacade.grantFragments]，两者都在本函数之外处理；
     * 旧版服务端可能下发的弟子类型在此被拒发（见 [API_REWARD_TYPE_DISCIPLE] 分支）。
     *
     * @param type 奖励类型（equipment/manual/pill/material/herb/seed）
     * @param name 奖励名称（功法模板查找用）
     * @param quantity 数量
     * @param rarity 稀有度（功法模板查找用，历史取值与 defaultRarity 不同）
     * @param defaultRarity 随机物品的稀有度来源（本地兑换与服务器兑换的历史取值不同）
     * @return true=该类型奖励全部发放成功；false=仓库满/失败（兑换码不应标记已用）
     */
    private fun MutableGameState.applyRedeemReward(
        type: String,
        name: String,
        quantity: Int,
        rarity: Int,
        defaultRarity: Int,
        mailRng: kotlin.random.Random
    ): Boolean {
        return when (type) {
            "equipment" -> applyEquipmentRedeemReward(quantity, defaultRarity, mailRng)
            "manual" -> applyManualRedeemReward(name, quantity, rarity, mailRng)
            "pill" -> applyPillRedeemReward(quantity, defaultRarity, mailRng)
            "material" -> applyMaterialRedeemReward(quantity, defaultRarity, mailRng)
            "herb" -> applyHerbRedeemReward(quantity, defaultRarity, mailRng)
            "seed" -> applySeedRedeemReward(quantity, defaultRarity, mailRng)
            API_REWARD_TYPE_DISCIPLE -> {
                // 弟子实例只能由角色模板实例化产生：服务端残留的弟子奖励一律拒发，
                // 既不直造弟子也不当作仓库失败（否则该码永远兑换不了），留告警供运营改发碎片
                DomainLog.w(TAG, "Refused server disciple reward: name=$name, quantity=$quantity")
                true
            }
            else -> true
        }
    }

    private fun MutableGameState.applyEquipmentRedeemReward(
        quantity: Int, defaultRarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val qty = quantity.coerceAtLeast(1)
        val newEquipment = EquipmentDatabase.generateRandom(
            minRarity = defaultRarity,
            maxRarity = defaultRarity,
            random = mailRng
        ).copy(quantity = qty)
        return handleRedeemResult(inventorySystem.addEquipmentStack(newEquipment), "装备 ${newEquipment.name}")
    }

    @Suppress("UnusedParameter") // mailRng: 奖励抽取 RNG 形参：模板缺失分支不消费（路径级 RNG 审计面一致）
    private fun MutableGameState.applyManualRedeemReward(
        name: String, quantity: Int, rarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val template = ManualDatabase.getByNameAndRarity(name, rarity)
        if (template == null) return true
        val qty = quantity.coerceAtLeast(1)
        val manual = ManualDatabase.createFromTemplate(template).copy(quantity = qty)
        return handleRedeemResult(inventorySystem.addManualStack(manual), "功法 ${manual.name}")
    }

    private fun MutableGameState.applyPillRedeemReward(
        quantity: Int, defaultRarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val qty = quantity.coerceAtLeast(1)
        val pill = ItemDatabase.generateRandomPill(
            minRarity = defaultRarity,
            maxRarity = defaultRarity,
            random = mailRng
        ).copy(quantity = qty)
        return handleRedeemResult(inventorySystem.addPill(pill), "丹药 ${pill.name}")
    }

    private fun MutableGameState.applyMaterialRedeemReward(
        quantity: Int, defaultRarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val qty = quantity.coerceAtLeast(1)
        val material = ItemDatabase.generateRandomMaterial(
            minRarity = defaultRarity,
            maxRarity = defaultRarity,
            random = mailRng
        ).copy(quantity = qty)
        return handleRedeemResult(inventorySystem.addMaterial(material), "材料 ${material.name}")
    }

    private fun MutableGameState.applyHerbRedeemReward(
        quantity: Int, defaultRarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val qty = quantity.coerceAtLeast(1)
        val herbTemplate = HerbDatabase.generateRandomHerb(
            minRarity = defaultRarity,
            maxRarity = defaultRarity,
            random = mailRng
        )
        val herb = Herb(
            id = java.util.UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = qty
        )
        return handleRedeemResult(inventorySystem.addHerb(herb), "草药 ${herb.name}")
    }

    private fun MutableGameState.applySeedRedeemReward(
        quantity: Int, defaultRarity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val qty = quantity.coerceAtLeast(1)
        val seedTemplate = HerbDatabase.generateRandomSeed(
            minRarity = defaultRarity,
            maxRarity = defaultRarity,
            random = mailRng
        )
        val seed = Seed(
            id = java.util.UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = qty
        )
        return handleRedeemResult(inventorySystem.addSeed(seed), "种子 ${seed.name}")
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    private fun verifyApkSignature(): Boolean {
        if (BuildConfig.APK_SIGNATURE_HASH.isEmpty()) {
            // Debug 构建允许跳过签名校验；Release 构建空 hash 拒绝（安全优先）
            if (BuildConfig.DEBUG) {
                DomainLog.w(TAG, "APK_SIGNATURE_HASH not configured, skipping (debug build)")
                return true
            }
            DomainLog.w(TAG, "APK_SIGNATURE_HASH not configured, rejecting (release build)")
            return false
        }

        return try {
            val certificate = signingCertificates.primarySigningCertificate()
            if (certificate == null) {
                DomainLog.w(TAG, "No APK signatures found")
                return false
            }

            val certDigest = MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .joinToString("") { "%02x".format(it) }

            val isValid = certDigest == BuildConfig.APK_SIGNATURE_HASH
            if (!isValid) {
                DomainLog.w(TAG, "APK signature mismatch: expected=${BuildConfig.APK_SIGNATURE_HASH}, got=$certDigest")
            }
            isValid
        } catch (e: Exception) {
            DomainLog.e(TAG, "APK signature verification failed", e)
            false
        }
    }

    private suspend fun localRedeem(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        // SR-5：一次兑换只采样一次墙钟，校验（4 层限流 + 过期清理）与奖励落账共用该时刻
        val nowMs = wallClock.currentTimeMillis()
        val validationResult = RedeemCodeManager.validateCode(
            code = code,
            usedCodes = usedCodes,
            currentYear = currentYear,
            currentMonth = currentMonth,
            nowMs = nowMs
        )

        if (!validationResult.success) {
            return validationResult
        }

        val redeemCodeData = RedeemCodeManager.getRedeemCode(code) ?: return RedeemResult(
            success = false,
            message = "兑换码不存在"
        )

        val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
        val result = RedeemCodeManager.generateReward(
            redeemCodeData,
            random = mailRng,
            nowMs = nowMs
        )

        if (!result.success) {
            return result
        }


        var allSucceeded = true
        stateStore.update {
            allSucceeded = applyLocalRedeemState(
                state = this,
                result = result,
                code = code,
                defaultRarity = redeemCodeData.rarity,
                mailRng = mailRng
            )
        }

        // 物品全部入账、兑换码已记入 usedRedeemCodes 之后才发碎片：
        // 仓库失败时码未消耗，玩家重试只补发物品，不会双增碎片
        if (allSucceeded) {
            grantFragmentRewards(result.rewards)
        }

        enqueueRewardCardsFromSelectedItems(result.rewards)

        if (!allSucceeded) {
            return RedeemResult(
                success = false,
                capacityInsufficient = true,
                message = "仓库容量不足，兑换码未使用，清理仓库后可重新兑换"
            )
        }
        return RedeemResult(
            success = true,
            message = "兑换成功！获得：${buildRewardDescription(result.rewards)}",
            rewards = result.rewards
        )
    }

    /**
     * 兑换成功文案：把奖励条目拼成人读的「N灵石、周明碎片60、聚气丹3」清单。
     *
     * @param rewards 已生成的奖励条目
     */
    private fun buildRewardDescription(rewards: List<RewardSelectedItem>): String =
        rewards.joinToString("、") { reward ->
            when (reward.type) {
                "spiritStones" -> "${reward.quantity}灵石"
                REWARD_TYPE_FRAGMENT -> "${reward.name}碎片${reward.quantity}"
                else -> "${reward.name}${reward.quantity}"
            }
        }

    /**
     * 本地兑换奖励落地：物品发放 + 灵石 + 标记已用，单事务原子写入。
     *
     * 角色碎片条目**不在此处入账**——碎片是寻访域账本，须走 [GachaFacade.grantFragments]，
     * 而本函数运行在 `stateStore.update` 事务内；调用方在事务成功返回后统一发放。
     * 碎片条目保留在 `result.rewards` 中，同时用于成功文案与结果卡片。
     *
     * @param state 事务中的 [MutableGameState]（调用方 stateStore.update 块内传入）
     * @return true=全部成功；false=任一物品发放失败/溢出（兑换码不标记已用，可清理后重试）
     */
    internal fun applyLocalRedeemState(
        state: MutableGameState,
        result: RedeemResult,
        code: String,
        defaultRarity: Int,
        mailRng: kotlin.random.Random
    ): Boolean = state.run {
        // 任一物品发放失败/溢出（仓库满）时不标记兑换码已用，
        // 玩家清理仓库后可重新兑换，奖励不丢失。
        // 灵石与角色碎片各有独立账本，不进仓库发放循环
        val allSucceeded = inventorySystem.withOverflowMailSuppressed {
            inventorySystem.withTrackingSource("redeem") {
                result.rewards.filter {
                    it.type != "spiritStones" && it.type != REWARD_TYPE_FRAGMENT
                }.all { reward ->
                    applyRedeemReward(reward.type, reward.name, reward.quantity, reward.rarity, defaultRarity, mailRng)
                }
            }
        }

        if (!allSucceeded) return@run false

        // 物品全部成功后才发放灵石（
        // 失败时灵石不入账，避免"已入账 + 凭据保留"重试时双发）
        result.rewards.filter { it.type == "spiritStones" }.forEach { reward ->
            spiritStoneWallet.add(this, reward.quantity.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.RedeemCode)
        }

        gameData = gameData.copy(
            usedRedeemCodes = (gameData.usedRedeemCodes + code.uppercase(java.util.Locale.getDefault()))
                .distinct()
                .takeLast(GameData.MAX_REDEEM_CODES)
        )
        true
    }

    private fun enqueueRewardCardsFromApiRewards(rewards: List<RedeemApiReward>) {
        val cards = rewards.mapNotNull { reward ->
            if (reward.type == API_REWARD_TYPE_DISCIPLE) return@mapNotNull null
            RewardCardItem(
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity.coerceIn(1, 6),
                quantity = reward.quantity
            )
        }
        if (cards.isNotEmpty()) {
            stateStore.enqueueRewardCards(cards)
        }
    }

    private fun enqueueRewardCardsFromSelectedItems(rewards: List<RewardSelectedItem>) {
        val cards = rewards.map { reward ->
            RewardCardItem(
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity.coerceIn(1, 6),
                quantity = reward.quantity
            )
        }
        if (cards.isNotEmpty()) {
            stateStore.enqueueRewardCards(cards)
        }
    }
}
