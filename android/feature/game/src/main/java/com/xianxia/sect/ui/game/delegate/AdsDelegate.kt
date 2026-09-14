package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.engine.service.AdPurpose
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.service.AdService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import com.xianxia.sect.core.engine.grantJadeSymbolsFromAd

/**
 * 广告播放委托。
 *
 * 每日观看次数限制为**设备/账号维度**，非每个存档独立计算。
 * 使用 companion object 静态存储，同一进程内所有 GameActivity 实例共享计数。
 *
 * 线程安全设计：
 * - [dailyCount] 使用 AtomicInteger 避免 TOCTOU 竞争（检查与标记原子化）
 * - [lastResetDay] 使用双重检查锁定（double-checked locking）确保跨天重置安全
 *
 * @param clock 时钟注入（测试确定性）；默认取系统墙钟
 */
class AdsDelegate(
    private val adService: AdService,
    private val gameEngine: GameEngine,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        private const val AD_COOLDOWN_MS = 60_000L
        /** 观看单次广告获得的玉符数量 */
        private const val JADE_AD_REWARD = 3
        /** 非白名单用户每日最大广告观看次数（设备/账号维度） */
        private const val DAILY_AD_LIMIT = 15

        /** 当日广告观看计数（原子化，跨实例共享） */
        private val dailyCount = AtomicInteger(0)
        /** 上次重置的天（getTodayStartMs 的值，跨实例共享） */
        @Volatile private var lastResetDay: Long = 0L

        /** 测试专用：重置跨实例共享计数（避免测试间相互污染） */
        internal fun resetForTest() {
            dailyCount.set(0)
            lastResetDay = 0L
        }
    }

    @Volatile private var adCooldownUntilMs: Long = 0L

    // ── 冷却检查 ──

    fun isAdOnCooldown(): Boolean {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return false
        return clock() < adCooldownUntilMs
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

        val now = clock()
        adCooldownUntilMs = now + AD_COOLDOWN_MS

        ensureDayReset()
        val afterIncrement = dailyCount.incrementAndGet()
        if (afterIncrement > DAILY_AD_LIMIT) {
            dailyCount.decrementAndGet()
            return false
        }
        return true
    }

    /** 获取今日剩余广告观看次数 */
    fun getRemainingDailyAds(): Int {
        if (AdFreeWhitelist.isCurrentUserPrivileged()) return Int.MAX_VALUE
        ensureDayReset()
        return (DAILY_AD_LIMIT - dailyCount.get()).coerceAtLeast(0)
    }

    // ── 个性化广告开关（合规要求：App 内提供退出个性化广告能力） ──

    private val _personalizedAdsEnabled = MutableStateFlow(adService.isPersonalizedAdsEnabled())
    val personalizedAdsEnabled: StateFlow<Boolean> = _personalizedAdsEnabled.asStateFlow()

    /** 切换个性化广告开关（持久化 + 同步 SDK）。 */
    fun setPersonalizedAdsEnabled(enabled: Boolean) {
        _personalizedAdsEnabled.value = enabled
        adService.setPersonalizedAdsEnabled(enabled)
    }

    // ── 玉符奖励广告 ──

    /**
     * 播放玉符奖励广告（观看完成发放 [JADE_AD_REWARD] 玉符）。
     * 免广告特权用户在 [AdService] 实现层直接发放奖励。
     *
     * 发放经 launchOnEngine 派发到引擎线程：SDK 回调线程不保证主线程，
     * 而 JadeSymbolService.grantFromAd 内含 stateStore.update（主线程运行时守卫）。
     */
    fun watchAdForJadeSymbols() {
        if (isDailyAdLimitReached()) return
        adService.watchAd(AdPurpose.JADE_SYMBOL_BONUS) {
            if (tryMarkAdWatched()) {
                gameEngine.launchOnEngine { gameEngine.grantJadeSymbolsFromAd(JADE_AD_REWARD) }
            }
        }
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
        val calendar = Calendar.getInstance().apply { timeInMillis = clock() }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
