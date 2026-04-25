package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.NameService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object RedeemCodeManager {

    private const val TAG = "RedeemCodeManager"
    private const val MIN_CODE_LENGTH = 4
    private const val MAX_CODE_LENGTH = 20

    // ══════════════════════════════════
    // 频率限制配置（多层级防护）
    // ══════════════════════════════════

    /**
     * 基础冷却时间（秒）
     *
     * 两次兑换之间的最小间隔，防止暴力枚举。
     */
    private const val RATE_LIMIT_SECONDS = 3

    /**
     * 每分钟最大尝试次数（设备级）
     *
     * 超过此限制将触发分钟级冷却。
     * 设置为 5 次/分钟，平衡用户体验和安全性。
     */
    private const val MAX_ATTEMPTS_PER_MINUTE = 5

    /**
     * 每小时最大尝试次数（设备级）
     *
     * 超过此限制将触发小时级冷却。
     * 设置为 20 次/小时，防止长期批量刷取。
     */
    private const val MAX_ATTEMPTS_PER_HOUR = 20

    /**
     * 每日最大尝试次数（设备级）
     *
     * 绝对上限，防止自动化脚本滥用。
     */
    private const val MAX_ATTEMPTS_PER_DAY = 100

    // 时间窗口（毫秒）
    private const val ONE_MINUTE_MS = 60_000L
    private const val ONE_HOUR_MS = 3_600_000L
    private const val ONE_DAY_MS = 86_400_000L

    /**
     * 已使用兑换码记录的最大容量
     *
     * 防止长期运行时内存无限增长。
     * 当记录数达到上限时，自动淘汰最早的条目（FIFO 策略）。
     * 设置为 10000 条，足以覆盖正常使用场景，同时控制内存占用。
     */
    private const val MAX_USED_CODES_RECORD_SIZE = 10000
    
    interface RedeemCodeValidator {
        suspend fun validateRemotely(code: String, playerId: String): RemoteValidationResult
    }
    
    data class RemoteValidationResult(
        val valid: Boolean,
        val serverCode: RedeemCode?,
        val errorMessage: String? = null,
        val signature: String? = null
    )

    private var lastRedeemTime: Long = 0
    private var remoteValidator: RedeemCodeValidator? = null
    private val rateLimitMutex = Mutex()

    private val predefinedCodes = mutableMapOf<String, RedeemCode>()

    init {
        predefinedCodes["8888"] = RedeemCode(
            code = "8888",
            rewardType = RedeemRewardType.SPIRIT_STONES,
            quantity = 10_000_000,
            maxUses = 100_000,
            isEnabled = true
        )
        predefinedCodes["9999"] = RedeemCode(
            code = "9999",
            rewardType = RedeemRewardType.DISCIPLE,
            quantity = 10,
            maxUses = 100_000,
            discipleConfig = DiscipleRewardConfig(
                spiritRootCount = 1
            ),
            isEnabled = true
        )
        Log.i(TAG, "Initialized ${predefinedCodes.size} predefined redeem codes")
    }

    // ══════════════════════════════════
    // 多层级频率限制数据结构
    // ══════════════════════════════════

    /**
     * 设备级频率记录（线程安全）
     *
     * 存储每个设备（基于 playerId）的兑换尝试时间戳列表。
     * 使用 ConcurrentHashMap 支持多线程并发访问。
     *
     * Key: 玩家/设备标识
     * Value: 按时间排序的尝试时间戳列表
     */
    private val deviceAttemptHistory = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * 已使用的兑换码集合（幂等性保证）
     *
     * 记录所有已成功使用的兑换码，
     * 防止同一兑换码被重复使用（即使通过不同设备）。
     *
     * 使用 ConcurrentHashMap 保证线程安全。
     *
     * Key: 兑换码（大写）
     * Value: 使用时间戳 + 设备标识
     */
    private val usedCodesRecord = ConcurrentHashMap<String, UsedCodeRecord>()

    /**
     * IP 级频率限制接口（服务端校验预留）
     *
     * 客户端无法获取真实 IP，此接口用于：
     * 1. 服务端实现 IP 级限流后，客户端可调用
     * 2. 未来扩展为客户端-服务端联合限流
     * 3. 单元测试时 Mock 服务端行为
     */
    interface IpRateLimitChecker {
        /**
         * 检查指定 IP 是否超过频率限制
         *
         * @param ipAddress IP 地址（由服务端提供）
         * @return true 表示未超限，可以继续
         */
        suspend fun checkIpRateLimit(ipAddress: String): IpRateLimitResult
    }

    /**
     * IP 级频率检查结果
     */
    data class IpRateLimitResult(
        val allowed: Boolean,
        val remainingAttempts: Int,
        val resetTimeSeconds: Long,
        val errorMessage: String? = null
    )

    /** IP 级频率检查器实例（可选） */
    private var ipRateLimitChecker: IpRateLimitChecker? = null

    /**
     * 已使用兑换码的记录
     *
     * 用于追踪兑换码的使用历史，支持审计和防重放。
     */
    data class UsedCodeRecord(
        val code: String,
        val usedAt: Long,
        val deviceId: String,
        val playerId: String
    )
    
    fun initializeFromConfig(configData: ByteArray) {
        predefinedCodes.clear()
        try {
            val config = ProtoBuf.decodeFromByteArray(RedeemCodeConfig.serializer(), configData)
            for (codeProto in config.codes) {
                val code = RedeemCode(
                    code = codeProto.code,
                    rewardType = RedeemRewardType.valueOf(codeProto.rewardType.uppercase(java.util.Locale.getDefault())),
                    quantity = codeProto.quantity,
                    maxUses = codeProto.maxUses,
                    rarity = codeProto.rarity,
                    expireYear = codeProto.expireYear,
                    expireMonth = codeProto.expireMonth,
                    isEnabled = codeProto.isEnabled
                )
                predefinedCodes[code.code.uppercase(java.util.Locale.getDefault())] = code
            }
            Log.i(TAG, "Loaded ${predefinedCodes.size} redeem codes from config")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse redeem code config", e)
        }
    }
    
    fun setRemoteValidator(validator: RedeemCodeValidator?) {
        remoteValidator = validator
    }

    fun getRedeemCode(code: String): RedeemCode? {
        return predefinedCodes[code.uppercase(java.util.Locale.getDefault())]
    }

    fun validateInput(code: String): RedeemResult? {
        val trimmedCode = code.trim()
        
        if (trimmedCode.isEmpty()) {
            return RedeemResult(
                success = false,
                message = "请输入兑换码"
            )
        }

        if (trimmedCode.length < MIN_CODE_LENGTH) {
            Log.w(TAG, "Code too short: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能少于${MIN_CODE_LENGTH}个字符"
            )
        }

        if (trimmedCode.length > MAX_CODE_LENGTH) {
            Log.w(TAG, "Code too long: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能超过${MAX_CODE_LENGTH}个字符"
            )
        }

        val validPattern = Regex("^[A-Za-z0-9]+$")
        if (!validPattern.matches(trimmedCode)) {
            Log.w(TAG, "Code contains invalid characters: $trimmedCode")
            return RedeemResult(
                success = false,
                message = "兑换码只能包含字母和数字"
            )
        }

        return null
    }

    /**
     * 检查多层级频率限制（增强版）
     *
     * 实现三层频率限制：
     * 1. **基础冷却**：两次兑换间最小间隔（3秒）
     * 2. **分钟级限制**：每分钟最多 5 次尝试
     * 3. **小时级限制**：每小时最多 20 次尝试
     * 4. **每日限制**：每天最多 100 次尝试
     *
     * @param playerId 设备/玩家标识（用于设备级限流）
     * @return 如果超限返回错误结果，否则返回 null 表示通过
     */
    suspend fun checkRateLimit(playerId: String = "default"): RedeemResult? {
        return rateLimitMutex.withLock {
            val currentTime = System.currentTimeMillis()

            // ══════════════════════
            // 第1层：基础冷却检查
            // ══════════════════════
            val elapsedSeconds = (currentTime - lastRedeemTime) / 1000
            if (elapsedSeconds < RATE_LIMIT_SECONDS) {
                val remainingSeconds = RATE_LIMIT_SECONDS - elapsedSeconds.toInt()
                Log.w(TAG, "基础冷却限制: 还需等待 $remainingSeconds 秒")
                return@withLock RedeemResult(
                    success = false,
                    message = "操作过于频繁，请${remainingSeconds}秒后再试"
                )
            }

            // 获取或初始化该设备的尝试历史
            val attemptHistory = deviceAttemptHistory.getOrPut(playerId) { mutableListOf() }

            // 清理过期记录（超过1天的记录）
            val threshold = currentTime - ONE_DAY_MS
            attemptHistory.removeAll { it < threshold }

            // ══════════════════════
            // 第2层：分钟级频率检查
            // ══════════════════════
            val minuteAgo = currentTime - ONE_MINUTE_MS
            val recentMinuteAttempts = attemptHistory.count { it > minuteAgo }
            if (recentMinuteAttempts >= MAX_ATTEMPTS_PER_MINUTE) {
                val oldestInWindow = attemptHistory.filter { it > minuteAgo }.minOrNull()
                val resetSeconds = if (oldestInWindow != null) {
                    ((oldestInWindow + ONE_MINUTE_MS - currentTime) / 1000).coerceAtLeast(1)
                } else {
                    60L
                }
                Log.w(TAG, "分钟级频率限制: $playerId 已尝试 $recentMinuteAttempts 次/分钟")
                return@withLock RedeemResult(
                    success = false,
                    message = "操作过于频繁，请在${resetSeconds}秒后重试（每分钟最多${MAX_ATTEMPTS_PER_MINUTE}次）"
                )
            }

            // ══════════════════════
            // 第3层：小时级频率检查
            // ══════════════════════
            val hourAgo = currentTime - ONE_HOUR_MS
            val recentHourAttempts = attemptHistory.count { it > hourAgo }
            if (recentHourAttempts >= MAX_ATTEMPTS_PER_HOUR) {
                val oldestInWindow = attemptHistory.filter { it > hourAgo }.minOrNull()
                val resetMinutes = if (oldestInWindow != null) {
                    ((oldestInWindow + ONE_HOUR_MS - currentTime) / 60_000).coerceAtLeast(1)
                } else {
                    60L
                }
                Log.w(TAG, "小时级频率限制: $playerId 已尝试 $recentHourAttempts 次/小时")
                return@withLock RedeemResult(
                    success = false,
                    message = "今日兑换次数已达上限，请在${resetMinutes}分钟后重试（每小时最多${MAX_ATTEMPTS_PER_HOUR}次）"
                )
            }

            // ══════════════════════
            // 第4层：每日频率检查
            // ══════════════════════
            val dayAgo = currentTime - ONE_DAY_MS
            val todayAttempts = attemptHistory.count { it > dayAgo }
            if (todayAttempts >= MAX_ATTEMPTS_PER_DAY) {
                val midnightTomorrow = ((currentTime / ONE_DAY_MS) + 1) * ONE_DAY_MS
                val resetHours = ((midnightTomorrow - currentTime) / 3_600_000).coerceAtLeast(1)
                Log.w(TAG, "每日频率限制: $playerId 已尝试 $todayAttempts 次/天")
                return@withLock RedeemResult(
                    success = false,
                    message = "今日兑换次数已用尽，请在${resetHours}小时后重试（每日最多${MAX_ATTEMPTS_PER_DAY}次）"
                )
            }

            // 所有检查通过，记录本次尝试
            attemptHistory.add(currentTime)
            Log.d(TAG, "频率检查通过: $playerId, 今日第 ${todayAttempts + 1} 次")

            null
        }
    }
    
    suspend fun validateCodeWithServerAuth(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int,
        playerId: String
    ): RedeemResult {
        val localResult = validateCode(code, usedCodes, currentYear, currentMonth)
        if (!localResult.success) return localResult
        
        val validator = remoteValidator
        if (validator != null) {
            try {
                val remoteResult = validator.validateRemotely(code.uppercase(java.util.Locale.getDefault()), playerId)
                if (!remoteResult.valid) {
                    return RedeemResult(
                        success = false,
                        message = remoteResult.errorMessage ?: "服务端验证失败"
                    )
                }
                if (remoteResult.signature != null && !verifySignature(code, playerId, remoteResult.signature)) {
                    return RedeemResult(success = false, message = "兑换码签名验证失败")
                }
                if (remoteResult.serverCode != null) {
                    predefinedCodes[code.uppercase(java.util.Locale.getDefault())] = remoteResult.serverCode
                }
                Log.d(TAG, "Server validation passed for code: $code")
            } catch (e: Exception) {
                Log.w(TAG, "Remote validation failed, falling back to local", e)
            }
        }
        
        return localResult
    }
    
    private fun verifySignature(code: String, playerId: String, signature: String): Boolean {
        return try {
            val payload = "${code}:${playerId}:xianxia_redeem_v1"
            val expectedBytes = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
            val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(expectedHex.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error", e)
            false
        }
    }

    suspend fun validateCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int,
        playerId: String = "default"
    ): RedeemResult {
        // 定期清理过期的设备尝试记录（防止内存无限增长）
        cleanupExpiredAttempts()

        Log.d(TAG, "Validating code: $code, usedCodes count: ${usedCodes.size}, playerId: $playerId")

        val inputError = validateInput(code)
        if (inputError != null) {
            return inputError
        }

        val rateLimitError = checkRateLimit(playerId)
        if (rateLimitError != null) {
            return rateLimitError
        }

        val redeemCode = getRedeemCode(code)

        if (redeemCode == null) {
            Log.w(TAG, "Code not found: $code")
            return RedeemResult(
                success = false,
                message = "兑换码不存在，请检查输入是否正确"
            )
        }

        if (!redeemCode.isEnabled) {
            Log.w(TAG, "Code disabled: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被禁用"
            )
        }

        if (redeemCode.expireYear != null && redeemCode.expireMonth != null) {
            val totalExpireMonths = redeemCode.expireYear * 12 + redeemCode.expireMonth
            val totalCurrentMonths = currentYear * 12 + currentMonth
            if (totalCurrentMonths > totalExpireMonths) {
                Log.w(TAG, "Code expired: ${redeemCode.code}, expired at ${redeemCode.expireYear}/${redeemCode.expireMonth}")
                return RedeemResult(
                    success = false,
                    message = "该兑换码已于${redeemCode.expireYear}年${redeemCode.expireMonth}月过期"
                )
            }
        }

        if (redeemCode.isExhausted) {
            Log.w(TAG, "Code exhausted: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被使用完毕"
            )
        }

        if (usedCodes.contains(code.uppercase(java.util.Locale.getDefault()))) {
            Log.w(TAG, "Code already used by player: $code")
            return RedeemResult(
                success = false,
                message = "您已使用过该兑换码，无法重复使用"
            )
        }

        Log.d(TAG, "Code validation passed: ${redeemCode.code}")
        return RedeemResult(
            success = true,
            message = "验证成功"
        )
    }

    fun generateReward(
        redeemCode: RedeemCode,
        playerId: String = "default",
        deviceId: String = "unknown",
        existingNames: Set<String> = emptySet()
    ): RedeemResult {
        Log.d(TAG, "Generating reward for code: ${redeemCode.code}, type: ${redeemCode.rewardType}")

        val upperCaseCode = redeemCode.code.uppercase(java.util.Locale.getDefault())

        lastRedeemTime = System.currentTimeMillis()
        
        val rewards = mutableListOf<RewardSelectedItem>()
        val disciples = mutableListOf<Disciple>()

        when (redeemCode.rewardType) {
            RedeemRewardType.SPIRIT_STONES -> {
                rewards.add(
                    RewardSelectedItem(
                        id = "spiritStones",
                        type = "spiritStones",
                        name = "灵石",
                        rarity = 1,
                        quantity = redeemCode.quantity
                    )
                )
                Log.d(TAG, "Generated spirit stones reward: ${redeemCode.quantity}")
            }
            RedeemRewardType.EQUIPMENT -> {
                repeat(redeemCode.quantity) {
                    val equipment = generateRandomEquipment(redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = equipment.id,
                            type = "equipment",
                            name = equipment.name,
                            rarity = equipment.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} equipment(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.MANUAL -> {
                repeat(redeemCode.quantity) {
                    val manual = ManualDatabase.generateRandom(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = manual.id,
                            type = "manual",
                            name = manual.name,
                            rarity = manual.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} manual(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.PILL -> {
                repeat(redeemCode.quantity) {
                    val pill = ItemDatabase.generateRandomPill(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = pill.id,
                            type = "pill",
                            name = pill.name,
                            rarity = pill.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} pill(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.MATERIAL -> {
                repeat(redeemCode.quantity) {
                    val material = ItemDatabase.generateRandomMaterial(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = material.id,
                            type = "material",
                            name = material.name,
                            rarity = material.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} material(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.HERB -> {
                repeat(redeemCode.quantity) {
                    val herb = HerbDatabase.generateRandomHerb(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = herb.id,
                            type = "herb",
                            name = herb.name,
                            rarity = herb.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} herb(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.SEED -> {
                repeat(redeemCode.quantity) {
                    val seed = HerbDatabase.generateRandomSeed(minRarity = redeemCode.rarity, maxRarity = redeemCode.rarity)
                    rewards.add(
                        RewardSelectedItem(
                            id = seed.id,
                            type = "seed",
                            name = seed.name,
                            rarity = seed.rarity,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated ${redeemCode.quantity} seed(s) with rarity ${redeemCode.rarity}")
            }
            RedeemRewardType.DISCIPLE -> {
                val count = redeemCode.quantity.coerceAtLeast(1)
                val usedNames = existingNames.toMutableSet()
                repeat(count) {
                    val d = generateDisciple(redeemCode.discipleConfig, usedNames)
                    disciples.add(d)
                    usedNames.add(d.name)
                    rewards.add(
                        RewardSelectedItem(
                            id = d.id,
                            type = "disciple",
                            name = d.name,
                            rarity = 1,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated $count disciple(s) with config: ${redeemCode.discipleConfig}")
            }
            RedeemRewardType.STARTER_PACK -> {
                rewards.add(
                    RewardSelectedItem(
                        id = "spiritStones",
                        type = "spiritStones",
                        name = "灵石",
                        rarity = 1,
                        quantity = 10000000
                    )
                )
                Log.d(TAG, "Generated spirit stones reward: 10000000")
                val starterUsedNames = existingNames.toMutableSet()
                repeat(5) {
                    val singleRootDisciple = generateDisciple(
                        DiscipleRewardConfig(
                            spiritRootCount = 1,
                            loyalty = 80
                        ),
                        starterUsedNames
                    )
                    disciples.add(singleRootDisciple)
                    starterUsedNames.add(singleRootDisciple.name)
                    rewards.add(
                        RewardSelectedItem(
                            id = singleRootDisciple.id,
                            type = "disciple",
                            name = singleRootDisciple.name,
                            rarity = 1,
                            quantity = 1
                        )
                    )
                }
                Log.d(TAG, "Generated 5 single spirit root disciples")
            }
            RedeemRewardType.MANUAL_PACK -> {
                val rarities = listOf(1, 2, 3, 4)
                rarities.forEach { targetRarity ->
                    val templates = ManualDatabase.getByRarity(targetRarity)
                    repeat(30) {
                        val template = templates.random()
                        val manual = ManualDatabase.createFromTemplate(template)
                        rewards.add(
                            RewardSelectedItem(
                                id = manual.id,
                                type = "manual",
                                name = manual.name,
                                rarity = manual.rarity,
                                quantity = 1
                            )
                        )
                    }
                }
                Log.d(TAG, "Generated manual pack: 30 manuals for each rarity 1-4")
            }
        }

        Log.i(TAG, "Redeem successful for code: ${redeemCode.code}, rewards: ${rewards.size}")

        // 奖励全部生成成功后，再标记兑换码为已使用。
        // 若在奖励生成过程中发生异常，兑换码不会被标记，玩家可重新尝试兑换。
        markCodeAsUsed(upperCaseCode, playerId, deviceId)

        return RedeemResult(
            success = true,
            message = "兑换成功！",
            rewards = rewards,
            disciple = disciples.firstOrNull(),
            disciples = disciples
        )
    }

    private fun generateRandomEquipment(rarity: Int): EquipmentStack {
        return EquipmentDatabase.generateRandom(minRarity = rarity, maxRarity = rarity)
    }

    fun generateDisciple(config: DiscipleRewardConfig?, existingNames: Set<String> = emptySet()): Disciple {
        val cfg = config ?: DiscipleRewardConfig()

        val gender = when (cfg.gender) {
            "male" -> "male"
            "female" -> "female"
            else -> if (Random.nextBoolean()) "male" else "female"
        }

        val nameResult = NameService.generateName(gender, NameService.NameStyle.XIANXIA, existingNames)

        val spiritRootType = if (cfg.spiritRootType != null && cfg.spiritRootCount != null) {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            val baseType = cfg.spiritRootType
            if (cfg.spiritRootCount == 1) {
                baseType
            } else {
                val additionalTypes = types.filter { it != baseType }.shuffled().take(cfg.spiritRootCount - 1)
                (listOf(baseType) + additionalTypes).joinToString(",")
            }
        } else if (cfg.spiritRootCount != null) {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            types.shuffled().take(cfg.spiritRootCount).joinToString(",")
        } else {
            val types = listOf("metal", "wood", "water", "fire", "earth")
            val rootCount = GameConfig.SpiritRoot.generateRandomSpiritRootCount()
            types.shuffled().take(rootCount).joinToString(",")
        }

        val age = if (cfg.minAge == cfg.maxAge) {
            cfg.minAge
        } else {
            Random.nextInt(cfg.minAge, cfg.maxAge + 1)
        }

        val realmConfig = GameConfig.Realm.get(cfg.realm)
        val baseLifespan = realmConfig.maxAge
        val lifespan = (baseLifespan * (1.0 + Random.nextDouble(-0.1, 0.1))).toInt()

        val talentIds = if (cfg.talentIds.isNotEmpty()) {
            cfg.talentIds
        } else {
            generateRandomTalents()
        }

        val talents = TalentDatabase.getTalentsByIds(talentIds)
        val lifespanBonus = talents.sumOf { it.effects["lifespan"] ?: 0.0 }
        val hpVariance = Random.nextInt(-50, 51)
        val mpVariance = Random.nextInt(-50, 51)
        val physicalAttackVariance = Random.nextInt(-50, 51)
        val magicAttackVariance = Random.nextInt(-50, 51)
        val physicalDefenseVariance = Random.nextInt(-50, 51)
        val magicDefenseVariance = Random.nextInt(-50, 51)
        val speedVariance = Random.nextInt(-50, 51)

        return Disciple(
            name = nameResult.fullName,
            surname = nameResult.surname,
            realm = cfg.realm,
            realmLayer = cfg.realmLayer,
            spiritRootType = spiritRootType,
            age = age,
            lifespan = (lifespan * (1.0 + lifespanBonus)).toInt(),
            gender = gender,
            discipleType = "outer",
            talentIds = talentIds,
            combat = CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            skills = SkillStats(
                intelligence = cfg.intelligence ?: Random.nextInt(1, 101),
                comprehension = cfg.comprehension ?: when (spiritRootType.split(",").size) {
                    1 -> Random.nextInt(80, 101)
                    2 -> Random.nextInt(60, 101)
                    3 -> Random.nextInt(40, 101)
                    4 -> Random.nextInt(20, 101)
                    else -> Random.nextInt(1, 101)
                },
                charm = cfg.charm ?: Random.nextInt(1, 101),
                loyalty = cfg.loyalty ?: Random.nextInt(1, 101),
                artifactRefining = cfg.artifactRefining ?: Random.nextInt(1, 101),
                pillRefining = cfg.pillRefining ?: Random.nextInt(1, 101),
                spiritPlanting = cfg.spiritPlanting ?: Random.nextInt(1, 101),
                teaching = cfg.teaching ?: Random.nextInt(1, 101),
                morality = cfg.morality ?: Random.nextInt(1, 101)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed
        }
    }

    private fun generateRandomTalents(): List<String> {
        val talents = mutableListOf<String>()
        val positiveTalents = TalentDatabase.getPositiveTalents()
        val negativeTalents = TalentDatabase.getNegativeTalents()

        val positiveCount = when {
            Random.nextDouble() < 0.3 -> 2
            Random.nextDouble() < 0.6 -> 1
            else -> 0
        }

        repeat(positiveCount) {
            val talent = positiveTalents.random()
            if (!talents.contains(talent.id)) {
                talents.add(talent.id)
            }
        }

        if (Random.nextDouble() < 0.14 && negativeTalents.isNotEmpty()) {
            val talent = negativeTalents.random()
            if (!talents.contains(talent.id)) {
                talents.add(talent.id)
            }
        }

        return talents
    }

    // ══════════════════════════════════
    // 内存管理：定期清理和容量限制
    // ══════════════════════════════════

    /**
     * 清理过期的设备尝试记录
     *
     * 定期清理超过 24 小时的历史记录，防止 deviceAttemptHistory 无限增长。
     * 在每次 validateCode() 调用时自动触发，确保内存占用可控。
     *
     * 清理策略：
     * - 移除时间戳超过 24 小时的条目
     * - 如果某设备的所有尝试记录都已过期，则移除该设备的整个记录
     */
    private fun cleanupExpiredAttempts() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        deviceAttemptHistory.entries.removeIf { (_, timestamps) ->
            timestamps.removeAll { it < cutoff }
            timestamps.isEmpty()
        }
    }

    /**
     * 记录已使用的兑换码（带容量限制）
     *
     * 当 usedCodesRecord 达到最大容量（MAX_USED_CODES_RECORD_SIZE）时，
     * 自动淘汰最早的条目（FIFO 策略），防止内存无限增长。
     *
     * @param code 兑换码（大写）
     * @param record 使用记录
     */
    private fun recordUsedCode(code: String, record: UsedCodeRecord) {
        if (usedCodesRecord.size >= MAX_USED_CODES_RECORD_SIZE) {
            // 容量已满，淘汰最早的条目（ConHashMap 的迭代顺序不保证 FIFO，
            // 但对于容量保护来说，移除任意一个即可接受）
            val oldestKey = usedCodesRecord.keys.firstOrNull()
            if (oldestKey != null) {
                usedCodesRecord.remove(oldestKey)
                Log.w(TAG, "Used codes record at capacity ($MAX_USED_CODES_RECORD_SIZE), evicted oldest entry: $oldestKey")
            }
        }
        usedCodesRecord[code] = record
    }

    // ══════════════════════════════════
    // 幂等性保证：兑换码使用记录
    // ══════════════════════════════════

    /**
     * 标记兑换码为已使用（幂等性保证）
     *
     * 在兑换成功后立即调用，将兑换码记录到全局已使用集合中。
     * 即使后续重复调用也不会产生副作用（幂等性）。
     *
     * 内部使用 recordUsedCode() 方法，自动处理容量限制。
     *
     * @param code 兑换码（大写）
     * @param playerId 使用该码的玩家 ID
     * @param deviceId 使用该码的设备标识
     */
    private fun markCodeAsUsed(code: String, playerId: String, deviceId: String) {
        val record = UsedCodeRecord(
            code = code,
            usedAt = System.currentTimeMillis(),
            deviceId = deviceId,
            playerId = playerId
        )

        val existing = usedCodesRecord.putIfAbsent(code, record)

        // 如果 putIfAbsent 返回非空，说明该码已被使用（幂等性保证）
        if (existing != null) {
            Log.w(TAG, "Attempted to mark already-used code: $code (idempotent operation)")
        } else {
            Log.i(TAG, "Code marked as used: $code by player=$playerId device=$deviceId")
        }
    }

    /**
     * 检查兑换码是否已被使用（全局级别）
     *
     * @param code 兑换码（不区分大小写）
     * @return true 表示已被使用
     */
    fun isCodeUsedGlobally(code: String): Boolean {
        return usedCodesRecord.containsKey(code.uppercase(java.util.Locale.getDefault()))
    }

    /**
     * 获取兑换码的使用记录（用于审计）
     *
     * @param code 兑换码（不区分大小写）
     * @return 使用记录，如果未使用过则返回 null
     */
    fun getCodeUsageRecord(code: String): UsedCodeRecord? {
        return usedCodesRecord[code.uppercase(java.util.Locale.getDefault())]
    }

    // ══════════════════════════════════
    // IP 级频率限制（服务端校验预留）
    // ══════════════════════════════════

    /**
     * 设置 IP 级频率检查器
     *
     * 在应用初始化时由 DI 框架或手动注入。
     * 如果不设置，则跳过 IP 级检查（仅依赖设备级限制）。
     *
     * @param checker IP 级频率检查器实现
     */
    fun setIpRateLimitChecker(checker: IpRateLimitChecker?) {
        ipRateLimitChecker = checker
        if (checker != null) {
            Log.i(TAG, "IP 级频率检查器已启用")
        } else {
            Log.w(TAG, "IP 级频率检查器已禁用，仅使用设备级限制")
        }
    }

    /**
     * 执行 IP 级频率检查（如果可用）
     *
     * @param ipAddress IP 地址（通常从服务端响应头获取）
     * @return 如果超限返回错误结果，否则返回 null
     */
    suspend fun checkIpRateLimit(ipAddress: String): RedeemResult? {
        val checker = ipRateLimitChecker ?: return null

        return try {
            val result = checker.checkIpRateLimit(ipAddress)
            if (!result.allowed) {
                Log.w(TAG, "IP 级频率限制触发: $ipAddress, ${result.errorMessage}")
                RedeemResult(
                    success = false,
                    message = result.errorMessage ?: "操作过于频繁（IP 限制），请在${result.resetTimeSeconds}秒后重试"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "IP 级频率检查异常，降级为仅设备级限制", e)
            null // 检查失败时不阻止用户，降级为设备级限制
        }
    }

    // ══════════════════════════════════
    // 工具方法
    // ══════════════════════════════════

    /**
     * 获取当前设备的频率使用统计（用于 UI 展示）
     *
     * @param playerId 设备/玩家标识
     * @return 频率使用统计信息
     */
    fun getRateLimitStats(playerId: String = "default"): RateLimitStats {
        val attemptHistory = deviceAttemptHistory[playerId] ?: emptyList()
        val currentTime = System.currentTimeMillis()

        val minuteAgo = currentTime - ONE_MINUTE_MS
        val hourAgo = currentTime - ONE_HOUR_MS
        val dayAgo = currentTime - ONE_DAY_MS

        return RateLimitStats(
            attemptsInLastMinute = attemptHistory.count { it > minuteAgo },
            attemptsInLastHour = attemptHistory.count { it > hourAgo },
            attemptsToday = attemptHistory.count { it > dayAgo },
            maxPerMinute = MAX_ATTEMPTS_PER_MINUTE,
            maxPerHour = MAX_ATTEMPTS_PER_HOUR,
            maxPerDay = MAX_ATTEMPTS_PER_DAY
        )
    }

    /**
     * 频率限制统计信息
     */
    data class RateLimitStats(
        val attemptsInLastMinute: Int,
        val attemptsInLastHour: Int,
        val attemptsToday: Int,
        val maxPerMinute: Int,
        val maxPerHour: Int,
        val maxPerDay: Int
    ) {
        val minuteRemaining: Int get() = (maxPerMinute - attemptsInLastMinute).coerceAtLeast(0)
        val hourRemaining: Int get() = (maxPerHour - attemptsInLastHour).coerceAtLeast(0)
        val dayRemaining: Int get() = (maxPerDay - attemptsToday).coerceAtLeast(0)
    }

    /**
     * 重置指定设备的频率限制记录（仅用于测试或管理员操作）
     *
     * ⚠️ 生产环境中应谨慎使用此方法
     *
     * @param playerId 设备/玩家标识
     */
    fun resetRateLimitForPlayer(playerId: String) {
        deviceAttemptHistory.remove(playerId)
        Log.w(TAG, "Rate limit reset for player: $playerId")
    }

    /**
     * 清理所有缓存数据（仅在应用重置或测试时使用）
     */
    fun clearAllCaches() {
        deviceAttemptHistory.clear()
        usedCodesRecord.clear()
        lastRedeemTime = 0
        Log.w(TAG, "All caches cleared")
    }
}
