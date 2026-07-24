package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.AdFreeWhitelist
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * 广告播放委托。
 *
 * 每日观看次数限制为**设备/账号维度**，非每个存档独立计算。
 * 使用 companion object 静态存储，同一进程内所有 GameActivity 实例共享计数。
 *
 * 线程安全设计：
 * - [dailyCount] 使用 AtomicInteger 避免 TOCTOU 竞争（检查与标记原子化）
 * - [lastResetDay] 使用双重检查锁定（double-checked locking）确保跨天重置安全
 */
class AdsDelegate {

    companion object {
        private const val TAG = "AdsDelegate"
        private const val AD_COOLDOWN_MS = 60_000L
        /** 非白名单用户每日最大广告观看次数（设备/账号维度） */
        private const val DAILY_AD_LIMIT = 20

        /** 当日广告观看计数（原子化，跨实例共享） */
        private val dailyCount = AtomicInteger(0)
        /** 上次重置的天（getTodayStartMs 的值，跨实例共享） */
        @Volatile private var lastResetDay: Long = 0L
    }

    @Volatile private var adCooldownUntilMs: Long = 0L

    // ── 冷却检查 ──

    fun isAdOnCooldown(): Boolean {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return false
        return System.currentTimeMillis() < adCooldownUntilMs
    }

    // ── 每日次数检查 ──

    /**
     * 检查今日是否已达到广告观看上限。
     * 非白名单用户每日最多 [DAILY_AD_LIMIT] 次（设备/账号维度），
     * 白名单用户不受限制。
     *
     * 如需并发安全的"检查+标记"原子操作，请使用 [tryMarkAdWatched]。
     */
    fun isDailyAdLimitReached(): Boolean {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return false
        ensureDayReset()
        return dailyCount.get() >= DAILY_AD_LIMIT
    }

    // ── 标记广告观看（原子操作） ──

    /**
     * 原子化尝试标记一次广告观看。
     *
     * 整合了检查与递增，消除 TOCTOU 竞争窗口：
     * 1. 白名单用户始终返回 true 且不计数
     * 2. 非白名单用户若超限则递减回滚并返回 false
     * 3. 更新冷却时间
     *
     * @return true 表示标记成功（可发放奖励），false 表示已达上限
     */
    fun tryMarkAdWatched(): Boolean {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return true

        val now = System.currentTimeMillis()
        adCooldownUntilMs = now + AD_COOLDOWN_MS

        ensureDayReset()
        val afterIncrement = dailyCount.incrementAndGet()
        if (afterIncrement > DAILY_AD_LIMIT) {
            dailyCount.decrementAndGet()
            return false
        }
        return true
    }

    /**
     * 旧版标记方法，仅更新冷却时间。
     *
     * @deprecated 请使用 [tryMarkAdWatched] 替代，以获得原子化的上限检查。
     *   此方法仅更新冷却，不进行每日计数（需上游自行保证）。
     */
    @Deprecated("Use tryMarkAdWatched() for atomic daily limit check")
    fun markAdWatched() {
        adCooldownUntilMs = System.currentTimeMillis() + AD_COOLDOWN_MS
        ensureDayReset()
        dailyCount.incrementAndGet()
    }

    /** 获取今日剩余广告观看次数 */
    fun getRemainingDailyAds(): Int {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return Int.MAX_VALUE
        ensureDayReset()
        return (DAILY_AD_LIMIT - dailyCount.get()).coerceAtLeast(0)
    }

    // ── 内部工具 ──

    /**
     * 确保 [dailyCount] 和 [lastResetDay] 对应今天。
     * 使用双重检查锁定，避免不必要的同步开销。
     */
    private fun ensureDayReset() {
        val today = getTodayStartMs()
        if (today != lastResetDay) {
            synchronized(dailyCount) {
                if (today != lastResetDay) {
                    dailyCount.set(0)
                    lastResetDay = today
                }
            }
        }
    }

    private fun getTodayStartMs(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
