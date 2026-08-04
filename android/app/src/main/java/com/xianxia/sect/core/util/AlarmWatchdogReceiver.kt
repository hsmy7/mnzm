package com.xianxia.sect.core.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.monitor.StallVerdict
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * ## AlarmWatchdogReceiver - AlarmManager 精确闹钟兜底看门狗
 *
 * 基于 [AlarmManager.setExactAndAllowWhileIdle] 的链式精确闹钟，
 * 在游戏循环被 OEM 省电策略（华为 PowerGenie / 小米神隐模式 /
 * 荣耀 MagicOS / vivo OriginOS 等）冻结时兜底唤醒并恢复游戏循环。
 *
 * ## 工作机制
 * - [scheduleAlarm] 调度下一次精确闹钟（[ALARM_INTERVAL_MS] 后）
 * - 闹钟触发 → [onReceive] 通过 Hilt EntryPoint 获取 [GameEngineCore] 实例，
 *   检查 [GameEngineCore.progressVerdict]（tickCount + 世界时间 + 暂停租约统一判据）
 * - 非健康/非豁免判定（LoopStalled / FakeRunDetected / StalePauseDetected）→
 *   调 [GameEngineCore.handleWatchdogVerdict] 自愈（限频每 60 秒一次，
 *   防止 Doze 退出时雪崩式恢复；用户主动暂停 = PausedByOwner 永不恢复）
 * - 自愈失败时降级为启动 [GameForegroundService] 兜底
 * - 无论是否触发恢复，都重新调度下一次闹钟（链式调度）
 *
 * ## 为何不用 setRepeating
 * [AlarmManager.setRepeating] 在 Doze 模式下会被系统批量延迟，无法保证
 * 精确唤醒。[AlarmManager.setExactAndAllowWhileIdle] 可在 Doze 下精确触发
 * （每个应用每天有宽限窗口，后续触发会消耗配额，但比 setRepeating 更可靠）。
 *
 * ## 获取 GameEngineCore 的方式
 * BroadcastReceiver 生命周期短，`@AndroidEntryPoint` 注入的 `lateinit` 字段
 * 在 Android 12+ 的 onReceive 中可能未初始化。改用
 * [EntryPointAccessors.fromApplication] 通过 [GameEngineEntryPoint]
 * 从 Application 的 SingletonComponent 获取 @Singleton 实例，绕过该限制。
 *
 * 通过 [GameEngineCore.handleWatchdogVerdict]（公有方法、线程安全）直调
 * 引擎核心自愈（含换新线程重启），绕开 Service 生命周期。
 * 调取失败时降级为启动 [GameForegroundService] 兜底。
 *
 * 参考：
 * - https://developer.android.google.cn/training/scheduling/alarms
 * - https://developer.android.google.cn/reference/android/app/AlarmManager#canScheduleExactAlarms()
 */
class AlarmWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmWatchdogReceiver"

        /** 闹钟触发 action */
        const val ACTION_ALARM_WATCHDOG = "com.xianxia.sect.action.ALARM_WATCHDOG"

        /** 闹钟间隔（15 秒） */
        const val ALARM_INTERVAL_MS = 15_000L

        /** PendingIntent 请求码（0x7E02 = Watchdog 看门狗编号） */
        const val REQUEST_CODE = 0x7E02

        /** 恢复动作最小间隔（ms）：60 秒内不重复恢复，防止 Doze 退出时雪崩式恢复 */
        private const val MIN_RECOVERY_INTERVAL_MS = 60_000L

        /** 上次恢复执行时间戳（ms） */
        @Volatile
        private var lastRecoveryTimeMs: Long = 0L

        /**
         * 调度下一次精确闹钟（链式调度）。
         *
         * Android 12+ (API 31, S) 需检查 [AlarmManager.canScheduleExactAlarms]，
         * 若无权限仅记录警告并返回（不抛异常），由 UI 层引导用户在系统设置中授权。
         *
         * 使用 [AlarmManager.setExactAndAllowWhileIdle] 而非
         * [AlarmManager.setRepeating]，因为后者在 Doze 模式下会被批量延迟，
         * 无法保证精确唤醒。
         */
        fun scheduleAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: run {
                    Log.w(TAG, "AlarmManager not available, cannot schedule alarm")
                    return
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                Log.w(TAG, "Cannot schedule exact alarms (SCHEDULE_EXACT_ALARM permission not granted)")
                return
            }

            val pendingIntent = buildPendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
            Log.d(TAG, "Scheduled next watchdog alarm at +${ALARM_INTERVAL_MS}ms")
        }

        /**
         * 取消已调度的闹钟。
         *
         * 在游戏循环主动停止时调用，避免无意义的唤醒。
         */
        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pendingIntent = buildPendingIntent(context)
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Cancelled watchdog alarm")
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
                .setAction(ACTION_ALARM_WATCHDOG)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                flags
            )
        }
    }

    /**
     * Hilt EntryPoint，用于在 BroadcastReceiver 中获取 [GameEngineCore] 单例。
     *
     * BroadcastReceiver 生命周期短，`@AndroidEntryPoint` 注入的 lateinit 字段
     * 在 Android 12+ 可能未初始化；EntryPoint 方式直接从 Application 的
     * SingletonComponent 解析，绕过该限制。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GameEngineEntryPoint {
        fun gameEngineCore(): GameEngineCore
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ALARM_WATCHDOG) {
            return
        }

        val appContext = context.applicationContext

        // 获取 GameEngineCore 实例（不 throw CancellationException——onReceive 非协程上下文）
        val gameEngineCore = try {
            EntryPointAccessors.fromApplication(
                appContext,
                GameEngineEntryPoint::class.java
            ).gameEngineCore()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot obtain GameEngineCore, scheduling next alarm", e)
            scheduleAlarm(context)
            return
        }

        val now = System.currentTimeMillis()
        val verdict = gameEngineCore.progressVerdict()

        // 非健康/非豁免判定 → 尝试自愈（限频 60s，防止 Doze 退出时频繁调用）
        // 用户主动暂停 = PausedByOwner（永不自动恢复，a63338f3 教训固化）
        val needsRecovery = verdict != StallVerdict.Healthy &&
            verdict != StallVerdict.PausedByOwner
        if (needsRecovery) {
            if ((now - lastRecoveryTimeMs) >= MIN_RECOVERY_INTERVAL_MS) {
                lastRecoveryTimeMs = now
                Log.w(TAG, "Watchdog verdict=$verdict, self-healing (engine core)")
                try {
                    gameEngineCore.handleWatchdogVerdict(verdict)
                } catch (e: Exception) {
                    Log.e(TAG, "handleWatchdogVerdict failed, falling back to startForegroundService", e)
                    startFallbackService(appContext)
                }
            } else {
                Log.d(TAG, "Watchdog verdict=$verdict but recovery throttled (interval=${MIN_RECOVERY_INTERVAL_MS}ms)")
            }
        }

        // 链式调度下一次闹钟
        scheduleAlarm(context)
    }

    /**
     * 降级兜底：启动 [GameForegroundService]（handleWatchdogVerdict 直调失败时）。
     */
    private fun startFallbackService(context: Context) {
        val intent = Intent(context, GameForegroundService::class.java)
            .setAction(GameForegroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
