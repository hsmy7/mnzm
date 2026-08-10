package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import android.content.Context
import android.content.pm.PackageManager
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.engine.BuildConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.recruitedMonth
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton






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
    val rarity: Int = 1
)

@GameService("RedeemCodeService")
@Singleton
class RedeemCodeService @Inject constructor(
    private val stateStore: GameStateStore,
    private val httpClient: HttpClientProvider,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val gameRngManager: com.xianxia.sect.core.util.GameRngManager,
    @ApplicationContext private val appContext: Context,
    private val inventorySystem: com.xianxia.sect.core.engine.system.InventorySystem,
) {
    companion object {
        private const val TAG = "RedeemCodeService"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        val trimmedCode = code.trim().takeIf {
            it.length in 3..32 && it.all { c -> c.isLetterOrDigit() || c == '-' }
        } ?: return RedeemResult(success = false, message = "兑换码格式无效")

        val errorMsg = InputValidator.validateRedeemCode(trimmedCode)
        if (errorMsg != null) {
            return RedeemResult(success = false, message = errorMsg)
        }

        if (trimmedCode in usedCodes) {
            return RedeemResult(success = false, message = "该兑换码已使用")
        }

        val serverResult = tryServerRedeem(trimmedCode)
        if (serverResult != null) return serverResult

        DomainLog.w(TAG, "Server redeem unavailable, falling back to local validation with APK signature check")
        if (!verifyApkSignature()) {
            return RedeemResult(success = false, message = "应用签名校验失败，无法使用离线兑换")
        }

        return localRedeem(trimmedCode, usedCodes, currentYear, currentMonth)
    }

    private suspend fun tryServerRedeem(code: String): RedeemResult? {
        return try {
            val url = "${BuildConfig.API_BASE_URL}redeem/verify"
            val requestBody = """{"code":"$code"}"""
            val body = httpClient.post(url, requestBody)

            val apiResult = json.decodeFromString<RedeemApiResponse>(body)

            if (apiResult.success) {
                val allSucceeded = applyApiRewardsAndMarkUsed(code, apiResult.rewards)
                enqueueRewardCardsFromApiRewards(apiResult.rewards)
                if (allSucceeded) {
                    RedeemResult(success = true, message = apiResult.message)
                } else {
                    RedeemResult(
                        success = false,
                        capacityInsufficient = true,
                        message = "仓库容量不足，兑换码未使用，清理仓库后可重新兑换"
                    )
                }
            } else {
                RedeemResult(success = false, message = apiResult.message)
            }
        } catch (e: Exception) {
            DomainLog.w(TAG, "Server redeem failed, will fallback to local", e)
            null
        }
    }

    private suspend fun applyApiRewardsAndMarkUsed(code: String, rewards: List<RedeemApiReward>): Boolean {
        // 灵石发放移到物品全部成功之后（对抗性审查 C3 修复：失败时灵石不入账，
        // 避免"灵石已入账 + 兑换码保留"重试时灵石双发）
        val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
        var allSucceeded = true
        stateStore.update {
            inventorySystem.withOverflowMailSuppressed {
            inventorySystem.withTrackingSource("redeem") {
                // 对抗性审查修复：任一物品发放失败/溢出（仓库满）时不标记兑换码已用，
                // 玩家清理仓库后可重新兑换，奖励不丢失
                allSucceeded = rewards.filter { it.type != "spiritStones" }.all { reward ->
                    applyRedeemReward(reward.type, reward.name, reward.quantity, reward.rarity, reward.rarity, mailRng)
                }
                if (allSucceeded) {
                    // 物品全部成功 → 发放灵石 + 标记已用（灵石发放独立事务，成功路径才执行）
                    rewards.filter { it.type == "spiritStones" }.forEach { reward ->
                        spiritStoneWallet.add(this, reward.quantity.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.RedeemCode)
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
     * @return true=全部成功；false=失败/溢出（对抗性审查修复：调用方
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
     * @param type 奖励类型（equipment/manual/pill/material/herb/seed/disciple）
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
            "disciple" -> applyDiscipleRedeemReward(quantity, mailRng)
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

    private fun MutableGameState.applyDiscipleRedeemReward(
        quantity: Int, mailRng: kotlin.random.Random
    ): Boolean {
        val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
        val usedNames = discipleTables.assembleAll().map { it.name }.toMutableSet()
        repeat(quantity.coerceAtLeast(1)) {
            val disciple = RedeemCodeManager.generateDisciple(null, usedNames, random = mailRng)
            disciple.usage.recruitedMonth = currentMonthValue
            discipleTables.allocateAndInsert(disciple)
            usedNames.add(disciple.name)
        }
        // 年报新增弟子计数（2026-08-11 修复：兑换码赠弟子漏计）
        gameData = gameData.copy(
            annualNewDisciples = gameData.annualNewDisciples + quantity.coerceAtLeast(1)
        )
        return true
    }

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
            val packageInfo = appContext.packageManager.getPackageInfo(
                appContext.packageName,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    PackageManager.GET_SIGNING_CERTIFICATES
                else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            )

            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures == null || signatures.isEmpty()) {
                DomainLog.w(TAG, "No APK signatures found")
                return false
            }

            val certDigest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray())
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

        val mailRng = gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()
        val existingNames = stateStore.disciples.value.map { it.name }.toSet()
        val result = RedeemCodeManager.generateReward(redeemCodeData, existingNames = existingNames, random = mailRng)

        if (!result.success) {
            return result
        }

        val data = stateStore.gameData.value

        var allSucceeded = true
        stateStore.update {
            // 对抗性审查修复：任一物品发放失败/溢出（仓库满）时不标记兑换码已用，
            // 玩家清理仓库后可重新兑换，奖励不丢失
            allSucceeded = inventorySystem.withOverflowMailSuppressed {
            inventorySystem.withTrackingSource("redeem") {
                result.rewards.filter { it.type != "spiritStones" }.all { reward ->
                    applyRedeemReward(reward.type, reward.name, reward.quantity, reward.rarity, redeemCodeData.rarity, mailRng)
                }
            }
            }

            if (!allSucceeded) return@update

            // 物品全部成功后才发放灵石与弟子（对抗性审查 C3 修复：
            // 失败时灵石/弟子不入账，避免"已入账 + 凭据保留"重试时双发）
            result.rewards.filter { it.type == "spiritStones" }.forEach { reward ->
                spiritStoneWallet.add(this, reward.quantity.toLong(), SpiritStoneGrade.LOW, SpiritStoneSource.RedeemCode)
            }
            result.disciples.forEach { disciple ->
                val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
                disciple.usage.recruitedMonth = currentMonthValue
                discipleTables.allocateAndInsert(disciple)
            }

            gameData = gameData.copy(
                usedRedeemCodes = (gameData.usedRedeemCodes + code.uppercase(java.util.Locale.getDefault()))
                    .distinct()
                    .takeLast(GameData.MAX_REDEEM_CODES),
                // 年报新增弟子计数（2026-08-11 修复：本地兑换码赠弟子漏计）
                annualNewDisciples = gameData.annualNewDisciples + result.disciples.size
            )
        }

        enqueueRewardCardsFromSelectedItems(result.rewards)

        val rewardDescription = result.rewards.joinToString("、") { reward ->
            when (reward.type) {
                "spiritStones" -> "${reward.quantity}灵石"
                "disciple" -> "弟子${reward.name}"
                else -> "${reward.name}${reward.quantity}"
            }
        }

        if (!allSucceeded) {
            return RedeemResult(
                success = false,
                capacityInsufficient = true,
                message = "仓库容量不足，兑换码未使用，清理仓库后可重新兑换"
            )
        }
        return RedeemResult(
            success = true,
            message = "兑换成功！获得：$rewardDescription",
            rewards = result.rewards,
            disciples = result.disciples
        )
    }

    private fun enqueueRewardCardsFromApiRewards(rewards: List<RedeemApiReward>) {
        val cards = rewards.mapNotNull { reward ->
            if (reward.type == "disciple") return@mapNotNull null
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
        val cards = rewards.mapNotNull { reward ->
            if (reward.type == "disciple") return@mapNotNull null
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
