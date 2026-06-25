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
 * - 闹钟触发 → [onReceive] 检查 [GameEngineCore.tickCount] 是否停滞
 * - 若停滞且 [GameEngineCore.isGameLoopRunning] 为 true（循环应运行但未推进），
 *   启动 [GameForegroundService] 并发送 [GameForegroundService.ACTION_START]，
 *   由 Service 内部看门狗处理 [GameEngineCore] 重启
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
 * 不直接调用 [GameEngineCore.restartGameLoopInternal]（该方法为 private），
 * 而是启动 [GameForegroundService]，由 Service 的 onStartCommand 处理
 * startGameLoop，Service 内部看门狗会进一步恢复卡死的游戏循环。
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

        /** PendingIntent 请求码 */
        const val REQUEST_CODE = 0x7E02

        /** 上次检查时的 tickCount，用于检测停滞（-1L 表示尚未采样过） */
        @Volatile
        private var lastTickCount: Long = -1L

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
        val tickStalled = checkTickStalled(appContext)
        if (tickStalled) {
            Log.w(TAG, "Tick stalled while game loop should be running, starting foreground service")
            startForegroundService(appContext)
        }

        // 链式调度下一次闹钟
        scheduleAlarm(context)
    }

    /**
     * 检查 [GameEngineCore.tickCount] 是否停滞。
     *
     * 仅当游戏循环声明为活跃（[GameEngineCore.isGameLoopRunning] == true）
     * 但 tickCount 与上次采样相同时，判定为停滞。
     *
     * @return true 表示 tick 停滞且循环应运行
     */
    private fun checkTickStalled(context: Context): Boolean {
        val gameEngineCore = try {
            EntryPointAccessors.fromApplication(
                context,
                GameEngineEntryPoint::class.java
            ).gameEngineCore()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot obtain GameEngineCore via EntryPoint: ${e.message}")
            return false
        }

        val currentTickCount = gameEngineCore.tickCount.value
        val stalled = currentTickCount == lastTickCount && gameEngineCore.isGameLoopRunning
        lastTickCount = currentTickCount
        return stalled
    }

    /**
     * 启动 [GameForegroundService] 并发送 [GameForegroundService.ACTION_START]。
     *
     * 由 Service 的 onStartCommand 处理 startGameLoop，Service 内部看门狗
     * 会进一步恢复卡死的游戏循环（restartGameLoopInternal 为 private，
     * 不在此处直接调用）。
     */
    private fun startForegroundService(context: Context) {
        val intent = Intent(context, GameForegroundService::class.java)
            .setAction(GameForegroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
