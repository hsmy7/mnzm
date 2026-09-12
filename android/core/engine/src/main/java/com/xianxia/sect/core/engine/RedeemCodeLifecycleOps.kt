package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.engine.RedeemCodeManager.UsedCodeRecord

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 兑换码限流与核销域（自 RedeemCodeManager 拆出，行为零变更） ───────────────

private val TAG = RedeemCodeManager.TAG
/** 第1层：基础冷却检查——两次兑换间最小间隔（3秒） */
internal fun RedeemCodeManager.checkBasicCooldown(currentTime: Long): RedeemResult? {
    val elapsedSeconds = (currentTime - lastRedeemTime) / 1000
    if (elapsedSeconds < RATE_LIMIT_SECONDS) {
        val remainingSeconds = RATE_LIMIT_SECONDS - elapsedSeconds.toInt()
        DomainLog.w(TAG, "基础冷却限制: 还需等待 $remainingSeconds 秒")
        return RedeemResult(
            success = false,
            message = "操作过于频繁，请${remainingSeconds}秒后再试"
        )
    }
    return null
}

/** 第2层：分钟级频率检查——每分钟最多 [MAX_ATTEMPTS_PER_MINUTE] 次 */

internal fun RedeemCodeManager.checkMinuteRateLimit(
    playerId: String,
    attemptHistory: MutableList<Long>,
    currentTime: Long
): RedeemResult? {
    val minuteAgo = currentTime - ONE_MINUTE_MS
    val recentMinuteAttempts = attemptHistory.count { it > minuteAgo }
    if (recentMinuteAttempts >= MAX_ATTEMPTS_PER_MINUTE) {
        val oldestInWindow = attemptHistory.filter { it > minuteAgo }.minOrNull()
        val resetSeconds = if (oldestInWindow != null) {
            ((oldestInWindow + ONE_MINUTE_MS - currentTime) / 1000).coerceAtLeast(1)
        } else {
            60L
        }
        DomainLog.w(TAG, "分钟级频率限制: $playerId 已尝试 $recentMinuteAttempts 次/分钟")
        return RedeemResult(
            success = false,
            message = "操作过于频繁，请在${resetSeconds}秒后重试（每分钟最多${MAX_ATTEMPTS_PER_MINUTE}次）"
        )
    }
    return null
}

/** 第3层：小时级频率检查——每小时最多 [MAX_ATTEMPTS_PER_HOUR] 次 */

internal fun RedeemCodeManager.checkHourRateLimit(
    playerId: String,
    attemptHistory: MutableList<Long>,
    currentTime: Long
): RedeemResult? {
    val hourAgo = currentTime - ONE_HOUR_MS
    val recentHourAttempts = attemptHistory.count { it > hourAgo }
    if (recentHourAttempts >= MAX_ATTEMPTS_PER_HOUR) {
        val oldestInWindow = attemptHistory.filter { it > hourAgo }.minOrNull()
        val resetMinutes = if (oldestInWindow != null) {
            ((oldestInWindow + ONE_HOUR_MS - currentTime) / 60_000).coerceAtLeast(1)
        } else {
            60L
        }
        DomainLog.w(TAG, "小时级频率限制: $playerId 已尝试 $recentHourAttempts 次/小时")
        return RedeemResult(
            success = false,
            message = "今日兑换次数已达上限，请在${resetMinutes}分钟后重试（每小时最多${MAX_ATTEMPTS_PER_HOUR}次）"
        )
    }
    return null
}

/** 第4层：每日频率检查——每天最多 [MAX_ATTEMPTS_PER_DAY] 次 */

internal fun RedeemCodeManager.checkDayRateLimit(
    playerId: String,
    attemptHistory: MutableList<Long>,
    currentTime: Long
): RedeemResult? {
    val dayAgo = currentTime - ONE_DAY_MS
    val todayAttempts = attemptHistory.count { it > dayAgo }
    if (todayAttempts >= MAX_ATTEMPTS_PER_DAY) {
        val midnightTomorrow = ((currentTime / ONE_DAY_MS) + 1) * ONE_DAY_MS
        val resetHours = ((midnightTomorrow - currentTime) / 3_600_000).coerceAtLeast(1)
        DomainLog.w(TAG, "每日频率限制: $playerId 已尝试 $todayAttempts 次/天")
        return RedeemResult(
            success = false,
            message = "今日兑换次数已用尽，请在${resetHours}小时后重试（每日最多${MAX_ATTEMPTS_PER_DAY}次）"
        )
    }
    return null
}

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
internal fun RedeemCodeManager.cleanupExpiredAttempts() {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    deviceAttemptHistory.entries.removeIf { (_, timestamps) ->
        timestamps.removeAll { it < cutoff }
        timestamps.isEmpty()
    }
}

// ══════════════════════════════════
// 幂等性保证：兑换码使用记录
// ══════════════════════════════════

/**
 * 标记兑换码为已使用（幂等性保证）
 *
 * 在兑换成功后立即调用，将兑换码记录到全局已使用集合中。
 * 即使后续重复调用也不会产生副作用（幂等性）。
 * 容量已达上限时自动淘汰最早条目（FIFO），防止内存无限增长。
 *
 * @param code 兑换码（大写）
 * @param playerId 使用该码的玩家 ID
 * @param deviceId 使用该码的设备标识
 */

internal fun RedeemCodeManager.markCodeAsUsed(code: String, playerId: String, deviceId: String) {
    val record = UsedCodeRecord(
        code = code,
        usedAt = System.currentTimeMillis(),
        deviceId = deviceId,
        playerId = playerId
    )

    val existing = usedCodesRecord.putIfAbsent(code, record)

    // 如果 putIfAbsent 返回非空，说明该码已被使用（幂等性保证）
    if (existing != null) {
        DomainLog.w(TAG, "Attempted to mark already-used code: $code (idempotent operation)")
    } else {
        DomainLog.i(TAG, "Code marked as used: $code by player=$playerId device=$deviceId")
    }
}

/** 灵石奖励生成：SPIRIT_STONES 分支 */

internal fun RedeemCodeManager.addSpiritStonesReward(
    quantity: Int,
    rewards: MutableList<RewardSelectedItem>
) {
    rewards.add(
        RewardSelectedItem(
            id = "spiritStones",
            type = "spiritStones",
            name = ItemNames.SPIRIT_STONE,
            rarity = 1,
            quantity = quantity
        )
    )
    DomainLog.d(TAG, "Generated spirit stones reward: $quantity")
}

/** 物品类奖励生成：EQUIPMENT/MANUAL/PILL/MATERIAL/HERB/SEED 分支 */
