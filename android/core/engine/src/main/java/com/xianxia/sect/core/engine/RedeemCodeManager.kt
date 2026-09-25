package com.xianxia.sect.core.engine


import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.model.RedeemRewardType
import com.xianxia.sect.core.model.RewardSelectedItem
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException



object RedeemCodeManager {
    /** 兑换码域日志标签：本 object 与拆出的 `RedeemCode*Ops` 扩展共用。 */
    internal const val TAG = "RedeemCodeManager"
    private const val MIN_CODE_LENGTH = 3
    private const val MAX_CODE_LENGTH = 20

    // ══════════════════════════════════
    // 频率限制配置（多层级防护）
    // ══════════════════════════════════

    /**
     * 基础冷却时间（秒）
     *
     * 两次兑换之间的最小间隔，防止暴力枚举。
     */
    internal const val RATE_LIMIT_SECONDS = 3

    /**
     * 每分钟最大尝试次数（设备级）
     *
     * 超过此限制将触发分钟级冷却。
     * 设置为 5 次/分钟，平衡用户体验和安全性。
     */
    internal const val MAX_ATTEMPTS_PER_MINUTE = 5

    /**
     * 每小时最大尝试次数（设备级）
     *
     * 超过此限制将触发小时级冷却。
     * 设置为 20 次/小时，防止长期批量刷取。
     */
    internal const val MAX_ATTEMPTS_PER_HOUR = 20

    /**
     * 每日最大尝试次数（设备级）
     *
     * 绝对上限，防止自动化脚本滥用。
     */
    internal const val MAX_ATTEMPTS_PER_DAY = 100

    // 时间窗口（毫秒）
    internal const val ONE_MINUTE_MS = 60_000L
    internal const val ONE_HOUR_MS = 3_600_000L
    internal const val ONE_DAY_MS = 86_400_000L

    /**
     * 远端兑换码校验器（服务端下发码表与签名时接入）。
     *
     * 未注入时 [validateCodeWithServerAuth] 只走本地校验。
     */
    interface RedeemCodeValidator {
        suspend fun validateRemotely(code: String, playerId: String): RemoteValidationResult
    }
    
    data class RemoteValidationResult(
        val valid: Boolean,
        val serverCode: RedeemCode?,
        val errorMessage: String? = null,
        val signature: String? = null
    )

    internal var lastRedeemTime: Long = 0
    private var remoteValidator: RedeemCodeValidator? = null
    internal val rateLimitMutex = Mutex()

    /**
     * 已配置的兑换码表（编译期条目）。
     *
     * 条目只发放资源/物品/角色碎片（[RedeemRewardType.FRAGMENT] + `templateId`），
     * 不存在发放弟子实例的码——弟子只能由角色模板实例化产生。
     * 远端校验通过时，服务端下发的码会写入本表（见 [validateCodeWithServerAuth]）。
     */
    private val predefinedCodes = mutableMapOf<String, RedeemCode>()

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
    internal val deviceAttemptHistory = ConcurrentHashMap<String, MutableList<Long>>()

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
    internal val usedCodesRecord = ConcurrentHashMap<String, UsedCodeRecord>()

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
    internal var ipRateLimitChecker: IpRateLimitChecker? = null

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
            DomainLog.w(TAG, "Code too short: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能少于${MIN_CODE_LENGTH}个字符"
            )
        }

        if (trimmedCode.length > MAX_CODE_LENGTH) {
            DomainLog.w(TAG, "Code too long: ${trimmedCode.length} chars")
            return RedeemResult(
                success = false,
                message = "兑换码长度不能超过${MAX_CODE_LENGTH}个字符"
            )
        }

        val validPattern = Regex("^[\\u4e00-\\u9fa5A-Za-z0-9]+$")
        if (!validPattern.matches(trimmedCode)) {
            DomainLog.w(TAG, "Code contains invalid characters: $trimmedCode")
            return RedeemResult(
                success = false,
                message = "兑换码只能包含字母和数字"
            )
        }

        return null
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    suspend fun validateCodeWithServerAuth(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int,
        playerId: String,
        /** 起算时刻（SR-5：与本地校验同一次采样，透传 [validateCode]） */
        nowMs: Long
    ): RedeemResult {
        val localResult = validateCode(
            code = code,
            usedCodes = usedCodes,
            currentYear = currentYear,
            currentMonth = currentMonth,
            nowMs = nowMs
        )
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
                DomainLog.d(TAG, "Server validation passed for code: $code")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainLog.w(TAG, "Remote validation failed, falling back to local", e)
            }
        }
        
        return localResult
    }
    
    suspend fun validateCode(
        code: String,
        usedCodes: List<String>,
        currentYear: Int,
        currentMonth: Int,
        playerId: String = "default",
        /** 本次校验的统一起算时刻（SR-5：调用方经注入 WallClock 采样一次后下传） */
        nowMs: Long
    ): RedeemResult {
        // 定期清理过期的设备尝试记录（防止内存无限增长）
        cleanupExpiredAttempts(nowMs)

        DomainLog.d(TAG, "Validating code: $code, usedCodes count: ${usedCodes.size}, playerId: $playerId")

        val inputError = validateInput(code)
        if (inputError != null) {
            return inputError
        }

        val rateLimitError = checkRateLimit(playerId, nowMs)
        if (rateLimitError != null) {
            return rateLimitError
        }

        val redeemCode = getRedeemCode(code)
        return validateCodeState(code, redeemCode, usedCodes, currentYear, currentMonth)
    }

    /**
     * 兑换码生命周期校验：存在 → 启用 → 未过期 → 未耗尽。
     *
     * @return 任一不满足时返回失败结果；全部通过返回 null
     */
    internal fun validateCodeLifecycle(
        code: String,
        redeemCode: RedeemCode?,
        currentYear: Int,
        currentMonth: Int
    ): RedeemResult? {
        if (redeemCode == null) {
            DomainLog.w(TAG, "Code not found: $code")
            return RedeemResult(
                success = false,
                message = "兑换码不存在，请检查输入是否正确"
            )
        }

        if (!redeemCode.isEnabled) {
            DomainLog.w(TAG, "Code disabled: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被禁用"
            )
        }

        val expireYear = redeemCode.expireYear
        val expireMonth = redeemCode.expireMonth
        if (expireYear != null && expireMonth != null &&
            currentYear * 12 + currentMonth > expireYear * 12 + expireMonth
        ) {
            DomainLog.w(TAG, "Code expired: ${redeemCode.code}, expired at $expireYear/$expireMonth")
            return RedeemResult(
                success = false,
                message = "该兑换码已于${expireYear}年${expireMonth}月过期"
            )
        }

        if (redeemCode.isExhausted) {
            DomainLog.w(TAG, "Code exhausted: ${redeemCode.code}")
            return RedeemResult(
                success = false,
                message = "该兑换码已被使用完毕"
            )
        }
        return null
    }

    /**
     * 生成兑换码奖励条目：按奖励类型分派生成器，产物只进 `RedeemResult.rewards`。
     *
     * 角色类奖励（[RedeemRewardType.FRAGMENT]）只产出碎片条目，账本入账由
     * `RedeemCodeService` 在兑换码消耗后交寻访域碎片门面完成；本函数不写任何
     * 游戏状态（弟子、碎片、仓库均不触碰）。
     *
     * 模板配置异常（角色模板不存在）时返回失败结果且不标记兑换码已用，玩家可重试。
     */
    fun generateReward(
        redeemCode: RedeemCode,
        playerId: String = "default",
        deviceId: String = "unknown",
        random: kotlin.random.Random = kotlin.random.Random,
        /** 兑换成功时刻（SR-5：调用方经注入 WallClock 采样后下传，写入基础冷却与使用记录） */
        nowMs: Long
    ): RedeemResult {
        DomainLog.d(TAG, "Generating reward for code: ${redeemCode.code}, type: ${redeemCode.rewardType}")

        val upperCaseCode = redeemCode.code.uppercase(java.util.Locale.getDefault())

        lastRedeemTime = nowMs
        
        val rewards = mutableListOf<RewardSelectedItem>()
        var rewardRejected = false

        when (redeemCode.rewardType) {
            RedeemRewardType.SPIRIT_STONES -> addSpiritStonesReward(
                quantity = redeemCode.quantity,
                rewards = rewards
            )
            // 6 种物品类奖励统一为生成器循环（RNG 调用序与原逐分支完全一致）
            RedeemRewardType.EQUIPMENT,
            RedeemRewardType.MANUAL,
            RedeemRewardType.PILL,
            RedeemRewardType.MATERIAL,
            RedeemRewardType.HERB,
            RedeemRewardType.SEED -> addItemRewards(
                type = redeemCode.rewardType,
                rarity = redeemCode.rarity,
                quantity = redeemCode.quantity,
                random = random,
                rewards = rewards
            )
            RedeemRewardType.FRAGMENT -> rewardRejected = !addFragmentRewards(
                templateId = redeemCode.templateId,
                quantity = redeemCode.quantity,
                rewards = rewards
            )
            RedeemRewardType.MANUAL_PACK -> addManualPackRewards(
                random = random,
                rewards = rewards
            )
        }

        // 配置异常（角色模板不存在）时不消耗兑换码：玩家侧无损失，
        // 运营侧由上面的告警日志定位错配的码条目
        if (rewardRejected) {
            DomainLog.w(TAG, "Reward generation rejected for code: ${redeemCode.code}, code not consumed")
            return RedeemResult(
                success = false,
                message = "兑换码奖励配置异常，本次未扣除兑换码"
            )
        }

        DomainLog.i(TAG, "Redeem successful for code: ${redeemCode.code}, rewards: ${rewards.size}")

        // 奖励全部生成成功后，再标记兑换码为已使用。
        // 若在奖励生成过程中发生异常，兑换码不会被标记，玩家可重新尝试兑换。
        markCodeAsUsed(upperCaseCode, playerId, deviceId, nowMs)

        return RedeemResult(
            success = true,
            message = "兑换成功！",
            rewards = rewards
        )
    }

    // ══════════════════════════════════
    // 内存管理：定期清理和容量限制
    // ══════════════════════════════════

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

    // ══════════════════════════════════
    // 工具方法
    // ══════════════════════════════════

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

}
