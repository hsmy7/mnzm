package com.xianxia.sect.core.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xianxia.sect.ui.game.GameActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台服务通知构建器。
 *
 * 集中创建前台游戏服务所需的通知渠道与 Notification 对象。通知携带
 * "暂停 / 退出"操作按钮，通过 PendingIntent 发送 [PAUSE_ACTION] /
 * [STOP_ACTION]，由前台 Service 接收处理。
 *
 * ## 使用
 * - [createChannel] 在 Service.onCreate 中调用（API 26+ 必需）
 * - [buildForegroundNotification] 在 Service.startForeground 中调用
 *
 * ## 渠道
 * - ID: [CHANNEL_ID] ("game_foreground")
 * - 重要性: LOW（无声、无横幅弹窗，仅状态栏图标）
 * - 名称: "游戏运行中"
 *
 * 参考：https://developer.android.google.cn/develop/background-work/services/foreground-services
 */
@Singleton
class GameNotificationHelper @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "GameNotificationHelper"

        /** 前台服务通知渠道 ID */
        const val CHANNEL_ID = "game_foreground"

        /** 前台服务通知 ID */
        const val NOTIFICATION_ID = 0x7E01

        /** 暂停游戏 action（由通知按钮 PendingIntent 发送，前台 Service 接收） */
        const val PAUSE_ACTION = "com.xianxia.sect.action.PAUSE"

        /** 退出游戏 action（由通知按钮 PendingIntent 发送，前台 Service 接收） */
        const val STOP_ACTION = "com.xianxia.sect.action.STOP"

        private const val REQUEST_CODE_CONTENT = 1000
        private const val REQUEST_CODE_PAUSE = 1001
        private const val REQUEST_CODE_STOP = 1002
    }

    /**
     * 创建前台服务通知渠道。
     *
     * - 渠道 ID: [CHANNEL_ID]
     * - 重要性: [NotificationManager.IMPORTANCE_LOW]（无声、无横幅弹窗）
     * - 名称: "游戏运行中"
     *
     * 幂等：重复调用不会报错（系统按渠道 ID 去重）。
     * API 26+ 必需，低于 26 自动跳过（旧版本无需渠道）。
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "游戏运行中",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // LOW 重要性默认无声，显式禁用振动与角标以避免 OEM 差异
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        nm.createNotificationChannel(channel)
    }

    /**
     * 构建前台服务通知（运行中状态）。
     *
     * 等价于 [buildForegroundNotification]`(..., paused = false)`。
     */
    fun buildForegroundNotification(context: Context): Notification =
        buildForegroundNotification(context, paused = false)

    /**
     * 构建前台服务通知。
     *
     * @param context 用于构建 PendingIntent 与 NotificationCompat.Builder
     * @param paused true 时内容显示"已暂停"，false 时显示"游戏运行中"
     *
     * ## 通知属性
     * - 渠道: [CHANNEL_ID]
     * - 小图标: android.R.drawable.ic_media_play
     * - 标题: "修仙门派"
     * - 内容: "游戏运行中" 或 "已暂停"
     * - 点击: 跳转 [GameActivity]
     * - 操作按钮: "暂停"([PAUSE_ACTION]) / "退出"([STOP_ACTION])
     * - flags: FLAG_ONGOING_EVENT | FLAG_NO_CLEAR（常驻、不可滑动清除）
     *
     * 所有 PendingIntent 均使用 FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT。
     */
    fun buildForegroundNotification(context: Context, paused: Boolean): Notification {
        val contentText = if (paused) "已暂停" else "游戏运行中"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("模拟宗门")
            .setContentText(contentText)
            .setContentIntent(createContentIntent(context))
            .setOngoing(true)
            .build()
            .apply {
                // FLAG_NO_CLEAR 确保通知在滑动时不会被清除
                flags = flags or Notification.FLAG_NO_CLEAR
            }
    }

    /**
     * 创建点击通知内容时的 PendingIntent — 跳转 [GameActivity]。
     * SINGLE_TOP | CLEAR_TOP 避免重复入栈。
     */
    private fun createContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, GameActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, REQUEST_CODE_CONTENT, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 创建暂停按钮 PendingIntent — 发送 [PAUSE_ACTION] 到
     * [GameForegroundService]，由 Service.onStartCommand 处理。
     */
    private fun createPauseIntent(context: Context): PendingIntent {
        val intent = Intent(context, GameForegroundService::class.java).apply {
            action = GameForegroundService.ACTION_PAUSE
            setPackage(context.packageName)
        }
        return PendingIntent.getService(
            context, REQUEST_CODE_PAUSE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 创建退出按钮 PendingIntent — 发送 [STOP_ACTION] 到
     * [GameForegroundService]，由 Service.onStartCommand 处理。
     */
    private fun createStopIntent(context: Context): PendingIntent {
        val intent = Intent(context, GameForegroundService::class.java).apply {
            action = GameForegroundService.ACTION_STOP
            setPackage(context.packageName)
        }
        return PendingIntent.getService(
            context, REQUEST_CODE_STOP, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
