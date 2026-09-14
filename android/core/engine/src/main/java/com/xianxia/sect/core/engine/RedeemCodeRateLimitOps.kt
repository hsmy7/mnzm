package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.RedeemCodeManager.RateLimitStats
import com.xianxia.sect.core.engine.RedeemCodeManager.IpRateLimitChecker
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
// ── 兑换码限流/校验域（自 RedeemCodeManager 拆出，行为零变更） ──

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
suspend fun RedeemCodeManager.checkRateLimit(playerId: String = "default"): RedeemResult? {
    return rateLimitMutex.withLock {
        val currentTime = System.currentTimeMillis()

        // ══════════════════════
        // 第1层：基础冷却检查
        // ══════════════════════
        checkBasicCooldown(currentTime = currentTime)?.let { return@withLock it }

        // 获取或初始化该设备的尝试历史
        val attemptHistory = deviceAttemptHistory.getOrPut(playerId) { mutableListOf() }

        // 清理过期记录（超过1天的记录）
        val threshold = currentTime - ONE_DAY_MS
        attemptHistory.removeAll { it < threshold }

        // ══════════════════════
        // 第2层：分钟级频率检查
        // ══════════════════════
        checkMinuteRateLimit(
            playerId = playerId,
            attemptHistory = attemptHistory,
            currentTime = currentTime
        )?.let { return@withLock it }

        // ══════════════════════
        // 第3层：小时级频率检查
        // ══════════════════════
        checkHourRateLimit(
            playerId = playerId,
            attemptHistory = attemptHistory,
            currentTime = currentTime
        )?.let { return@withLock it }

        // ══════════════════════
        // 第4层：每日频率检查
        // ══════════════════════
        checkDayRateLimit(
            playerId = playerId,
            attemptHistory = attemptHistory,
            currentTime = currentTime
        )?.let { return@withLock it }

        // 所有检查通过，记录本次尝试
        val todayAttempts = attemptHistory.count { it > currentTime - ONE_DAY_MS }
        attemptHistory.add(currentTime)
        DomainLog.d(TAG, "频率检查通过: $playerId, 今日第 ${todayAttempts + 1} 次")

        null
    }
}

/**
 * 设置 IP 级频率检查器
 *
 * 在应用初始化时由 DI 框架或手动注入。
 * 如果不设置，则跳过 IP 级检查（仅依赖设备级限制）。
 *
 * @param checker IP 级频率检查器实现
 */
fun RedeemCodeManager.setIpRateLimitChecker(checker: IpRateLimitChecker?) {
    ipRateLimitChecker = checker
    if (checker != null) {
        DomainLog.i(TAG, "IP 级频率检查器已启用")
    } else {
        DomainLog.w(TAG, "IP 级频率检查器已禁用，仅使用设备级限制")
    }
}

/**
 * 执行 IP 级频率检查（如果可用）
 *
 * @param ipAddress IP 地址（通常从服务端响应头获取）
 * @return 如果超限返回错误结果，否则返回 null
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
suspend fun RedeemCodeManager.checkIpRateLimit(ipAddress: String): RedeemResult? {
    val checker = ipRateLimitChecker ?: return null

    return try {
        val result = checker.checkIpRateLimit(ipAddress)
        if (!result.allowed) {
            DomainLog.w(TAG, "IP 级频率限制触发: $ipAddress, ${result.errorMessage}")
            RedeemResult(
                success = false,
                message = result.errorMessage ?: "操作过于频繁（IP 限制），请在${result.resetTimeSeconds}秒后重试"
            )
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(TAG, "IP 级频率检查异常，降级为仅设备级限制", e)
        null // 检查失败时不阻止用户，降级为设备级限制
    }
}

/**
 * 获取当前设备的频率使用统计（用于 UI 展示）
 *
 * @param playerId 设备/玩家标识
 * @return 频率使用统计信息
 */
fun RedeemCodeManager.getRateLimitStats(playerId: String = "default"): RateLimitStats {
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
 * 重置指定设备的频率限制记录（仅用于测试或管理员操作）
 *
 * ⚠️ 生产环境中应谨慎使用此方法
 *
 * @param playerId 设备/玩家标识
 */
fun RedeemCodeManager.resetRateLimitForPlayer(playerId: String) {
    deviceAttemptHistory.remove(playerId)
    DomainLog.w(TAG, "Rate limit reset for player: $playerId")
}

/**
 * 清理所有缓存数据（仅在应用重置或测试时使用）
 */
fun RedeemCodeManager.clearAllCaches() {
    deviceAttemptHistory.clear()
    usedCodesRecord.clear()
    lastRedeemTime = 0
    DomainLog.w(TAG, "All caches cleared")
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun RedeemCodeManager.verifySignature(code: String, playerId: String, signature: String): Boolean {
    return try {
        val payload = "${code}:${playerId}:xianxia_redeem_v1"
        val expectedBytes = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }
        MessageDigest.isEqual(expectedHex.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(TAG, "Signature verification error", e)
        false
    }
}

/**
 * 兑换码状态校验：生命周期校验 → 未被本人使用。
 */
internal fun RedeemCodeManager.validateCodeState(
    code: String,
    redeemCode: RedeemCode?,
    usedCodes: List<String>,
    currentYear: Int,
    currentMonth: Int
): RedeemResult {
    val lifecycleFailure = validateCodeLifecycle(code, redeemCode, currentYear, currentMonth)
    if (lifecycleFailure != null) return lifecycleFailure

    if (usedCodes.contains(code.uppercase(java.util.Locale.getDefault()))) {
        DomainLog.w(TAG, "Code already used by player: $code")
        return RedeemResult(
            success = false,
            message = "您已使用过该兑换码，无法重复使用"
        )
    }

    val activeCode = requireNotNull(redeemCode) { "lifecycle 校验通过则兑换码必存在" }
    DomainLog.d(TAG, "Code validation passed: ${activeCode.code}")
    return RedeemResult(
        success = true,
        message = "验证成功"
    )
}
