@file:Suppress("DEPRECATION")
package com.xianxia.sect.core.engine.service

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.core.util.ValidationResult
import com.xianxia.sect.network.SecureHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

@SystemPriority(order = 950)
@Singleton
class RedeemCodeService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val eventService: EventService,
    private val secureClient: SecureHttpClient,
    @ApplicationContext private val appContext: Context
) : GameSystem {
    override val systemName: String = "RedeemCodeService"

    override fun initialize() {
        Log.d(TAG, "RedeemCodeService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "RedeemCodeService released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    companion object {
        private const val TAG = "RedeemCodeService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    suspend fun redeemCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult {
        val trimmedCode = code.trim().takeIf {
            it.length in 4..32 && it.all { c -> c.isLetterOrDigit() || c == '-' }
        } ?: return RedeemResult(success = false, message = "兑换码格式无效")

        val inputValidation = InputValidator.validateRedeemCode(trimmedCode)
        if (inputValidation is ValidationResult.Error) {
            return RedeemResult(success = false, message = inputValidation.message)
        }

        if (trimmedCode in usedCodes) {
            return RedeemResult(success = false, message = "该兑换码已使用")
        }

        val serverResult = tryServerRedeem(trimmedCode)
        if (serverResult != null) return serverResult

        Log.w(TAG, "Server redeem unavailable, falling back to local validation with APK signature check")
        if (!verifyApkSignature()) {
            return RedeemResult(success = false, message = "应用签名校验失败，无法使用离线兑换")
        }

        return localRedeem(trimmedCode, usedCodes, currentYear, currentMonth)
    }

    private suspend fun tryServerRedeem(code: String): RedeemResult? {
        return try {
            val requestBody = """{"code":"$code"}"""
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}redeem/verify")
                .post(requestBody)
                .build()

            val response = secureClient.execute(request)
            val body = response.body?.string() ?: return RedeemResult(
                success = false, message = "服务器响应异常"
            )

            val apiResult = json.decodeFromString<RedeemApiResponse>(body)

            if (apiResult.success) {
                applyApiRewardsAndMarkUsed(code, apiResult.rewards)
                eventService.addGameEvent("成功兑换码：$code", EventType.SUCCESS)
                RedeemResult(success = true, message = apiResult.message)
            } else {
                RedeemResult(success = false, message = apiResult.message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server redeem failed, will fallback to local", e)
            null
        }
    }

    private suspend fun applyApiRewardsAndMarkUsed(code: String, rewards: List<RedeemApiReward>) {
        stateStore.update {
            gameData = gameData.copy(
                usedRedeemCodes = (gameData.usedRedeemCodes + code.uppercase(java.util.Locale.getDefault()))
                    .distinct()
                    .takeLast(GameData.MAX_REDEEM_CODES)
            )
            rewards.forEach { reward ->
                when (reward.type) {
                    "spiritStones" -> {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + reward.quantity
                        )
                    }
                    "equipment" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val newEquipment = EquipmentDatabase.generateRandom(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
                        ).copy(quantity = qty)
                        val existing = equipmentStacks.find { it.name == newEquipment.name && it.rarity == newEquipment.rarity && it.slot == newEquipment.slot }
                        if (existing != null) {
                            val newQty = (existing.quantity + newEquipment.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                            equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            equipmentStacks = equipmentStacks + newEquipment
                        }
                    }
                    "manual" -> {
                        val template = ManualDatabase.getByNameAndRarity(reward.name, reward.rarity)
                        if (template != null) {
                            val qty = reward.quantity.coerceAtLeast(1)
                            val manual = ManualDatabase.createFromTemplate(template).copy(quantity = qty)
                            val existing = manualStacks.find {
                                it.name == manual.name && it.rarity == manual.rarity && it.type == manual.type
                            }
                            if (existing != null) {
                                val newQty = (existing.quantity + manual.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("manual_stack"))
                                manualStacks = manualStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                            } else {
                                manualStacks = manualStacks + manual
                            }
                        }
                    }
                    "pill" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val pill = ItemDatabase.generateRandomPill(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
                        ).copy(quantity = qty)
                        val existing = pills.find { it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + pill.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            pills = pills + pill
                        }
                    }
                    "material" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val material = ItemDatabase.generateRandomMaterial(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
                        ).copy(quantity = qty)
                        val existing = materials.find { it.name == material.name && it.rarity == material.rarity && it.category == material.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + material.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                            materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            materials = materials + material
                        }
                    }
                    "herb" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val herbTemplate = HerbDatabase.generateRandomHerb(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
                        )
                        val herb = Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbTemplate.name,
                            rarity = herbTemplate.rarity,
                            description = herbTemplate.description,
                            category = herbTemplate.category,
                            quantity = qty
                        )
                        val existing = herbs.find { it.name == herb.name && it.rarity == herb.rarity && it.category == herb.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + herb.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                            herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            herbs = herbs + herb
                        }
                    }
                    "seed" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val seedTemplate = HerbDatabase.generateRandomSeed(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
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
                        val existing = seeds.find { it.name == seed.name && it.rarity == seed.rarity && it.growTime == seed.growTime }
                        if (existing != null) {
                            val newQty = (existing.quantity + seed.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                            seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            seeds = seeds + seed
                        }
                    }
                    "disciple" -> {
                        val currentMonthValue = gameData.gameYear * 12 + gameData.gameMonth
                        val usedNames = disciples.map { it.name }.toMutableSet()
                        repeat(reward.quantity.coerceAtLeast(1)) {
                            val disciple = RedeemCodeManager.generateDisciple(null, usedNames)
                            disciple.usage.recruitedMonth = currentMonthValue
                            disciples = disciples + disciple
                            usedNames.add(disciple.name)
                        }
                    }
                }
            }
        }
    }

    private fun verifyApkSignature(): Boolean {
        if (BuildConfig.APK_SIGNATURE_HASH.isEmpty()) {
            Log.w(TAG, "APK_SIGNATURE_HASH not configured, skipping signature verification")
            return true
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
                Log.w(TAG, "No APK signatures found")
                return false
            }

            val certDigest = MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray())
                .joinToString("") { "%02x".format(it) }

            val isValid = certDigest == BuildConfig.APK_SIGNATURE_HASH
            if (!isValid) {
                Log.w(TAG, "APK signature mismatch: expected=${BuildConfig.APK_SIGNATURE_HASH}, got=$certDigest")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "APK signature verification failed", e)
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

        val existingNames = stateStore.disciples.value.map { it.name }.toSet()
        val result = RedeemCodeManager.generateReward(redeemCodeData, existingNames = existingNames)

        if (!result.success) {
            return result
        }

        val data = stateStore.gameData.value

        stateStore.update {
            result.rewards.forEach { reward ->
                when (reward.type) {
                    "spiritStones" -> {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + reward.quantity
                        )
                    }
                    "equipment" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val newEquipment = EquipmentDatabase.generateRandom(
                            minRarity = redeemCodeData.rarity,
                            maxRarity = redeemCodeData.rarity
                        ).copy(quantity = qty)
                        val existing = equipmentStacks.find { it.name == newEquipment.name && it.rarity == newEquipment.rarity && it.slot == newEquipment.slot }
                        if (existing != null) {
                            val newQty = (existing.quantity + newEquipment.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                            equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            equipmentStacks = equipmentStacks + newEquipment
                        }
                    }
                    "manual" -> {
                        val template = ManualDatabase.getByNameAndRarity(reward.name, reward.rarity)
                        if (template != null) {
                            val manual = ManualStack(
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
                            val existing = manualStacks.find {
                                it.name == manual.name && it.rarity == manual.rarity && it.type == manual.type
                            }
                            if (existing != null) {
                                val newQty = (existing.quantity + manual.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("manual_stack"))
                                manualStacks = manualStacks.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            } else {
                                manualStacks = manualStacks + manual
                            }
                        } else {
                            Log.w(TAG, "无法找到功法模板: ${reward.name}, rarity: ${reward.rarity}")
                        }
                    }
                    "pill" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val pill = ItemDatabase.generateRandomPill(
                            minRarity = redeemCodeData.rarity,
                            maxRarity = redeemCodeData.rarity
                        ).copy(quantity = qty)
                        val existing = pills.find { it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + pill.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            pills = pills + pill
                        }
                    }
                    "material" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val material = ItemDatabase.generateRandomMaterial(
                            minRarity = redeemCodeData.rarity,
                            maxRarity = redeemCodeData.rarity
                        ).copy(quantity = qty)
                        val existing = materials.find { it.name == material.name && it.rarity == material.rarity && it.category == material.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + material.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                            materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            materials = materials + material
                        }
                    }
                    "herb" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
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
                            quantity = qty
                        )
                        val existing = herbs.find { it.name == herb.name && it.rarity == herb.rarity && it.category == herb.category }
                        if (existing != null) {
                            val newQty = (existing.quantity + herb.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                            herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            herbs = herbs + herb
                        }
                    }
                    "seed" -> {
                        val qty = reward.quantity.coerceAtLeast(1)
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
                            quantity = qty
                        )
                        val existing = seeds.find { it.name == seed.name && it.rarity == seed.rarity && it.growTime == seed.growTime }
                        if (existing != null) {
                            val newQty = (existing.quantity + seed.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                            seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        } else {
                            seeds = seeds + seed
                        }
                    }
                }
            }

            result.disciples.forEach { disciple ->
                val currentMonthValue = data.gameYear * 12 + data.gameMonth
                val discipleWithRecruitTime = disciple.copy()
                discipleWithRecruitTime.usage.recruitedMonth = currentMonthValue
                disciples = disciples + discipleWithRecruitTime
            }

            gameData = gameData.copy(
                usedRedeemCodes = (gameData.usedRedeemCodes + code.uppercase(java.util.Locale.getDefault()))
                    .distinct()
                    .takeLast(GameData.MAX_REDEEM_CODES)
            )
        }

        val rewardDescription = result.rewards.joinToString("、") { reward ->
            when (reward.type) {
                "spiritStones" -> "${reward.quantity}灵石"
                "disciple" -> "弟子${reward.name}"
                else -> "${reward.name}x${reward.quantity}"
            }
        }

        eventService.addGameEvent("成功兑换码：$code，获得：$rewardDescription", EventType.SUCCESS)

        return RedeemResult(
            success = true,
            message = "兑换成功！获得：$rewardDescription",
            rewards = result.rewards,
            disciples = result.disciples
        )
    }
}
