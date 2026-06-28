package com.xianxia.sect.core.util

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xianxia.sect.core.engine.GameEngineCore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * ## GameForegroundService - 游戏循环前台服务
 *
 * 承载 [GameEngineCore] 游戏循环的 Foreground Service，确保游戏在后台时
 * 仍能持续运行（受 OEM 省电策略影响最小）。
 *
 * ## 生命周期
 * - [onCreate]: 创建通知渠道、获取 WakeLock（不启动游戏循环，等 [ACTION_START]）
 * - [onStartCommand] [ACTION_START] / null: 调用 startForeground 后启动游戏循环
 * - [onStartCommand] [ACTION_PAUSE] / [ACTION_RESUME]: 切换暂停状态并更新通知
 * - [onStartCommand] [ACTION_STOP]: 停止游戏循环并 stopSelf
 * - [onDestroy]: 停止游戏循环、释放 WakeLock
 *
 * ## 通知
 * 由 [GameNotificationHelper] 构建，携带"暂停 / 退出"操作按钮，
 * 按钮通过 PendingIntent 发送 [ACTION_PAUSE] / [ACTION_STOP]，
 * 由本 Service 的 [onStartCommand] 接收处理。
 *
 * ## 绑定
 * [onBind] 返回 [GameEngineBinder]，允许 Activity 获取 [GameEngineCore] 实例，
 * 替代 GameActivity 直接 @Inject 持有 GameEngineCore 的旧模式。
 *
 * ## suspend 调用
 * [GameEngineCore.pause] / [GameEngineCore.resume] 为 suspend 函数，
 * 通过 [GameEngineCore.launchInScope] 在 engineScope 中调用。
 *
 * 参考：https://developer.android.google.cn/develop/background-work/services/foreground-services
 */
@AndroidEntryPoint
class GameForegroundService : Service() {

    companion object {
        private const val TAG = "GameForegroundService"

        /** 启动游戏循环 action */
        const val ACTION_START = "com.xianxia.sect.action.START"

        /** 停止游戏循环 action（与 [GameNotificationHelper.STOP_ACTION] 一致） */
        const val ACTION_STOP = "com.xianxia.sect.action.STOP"

        /** 暂停游戏 action（与 [GameNotificationHelper.PAUSE_ACTION] 一致） */
        const val ACTION_PAUSE = "com.xianxia.sect.action.PAUSE"

        /** 恢复游戏 action */
        const val ACTION_RESUME = "com.xianxia.sect.action.RESUME"

        /** 前台服务通知 ID（与 [GameNotificationHelper.NOTIFICATION_ID] 一致） */
        const val NOTIFICATION_ID = 0x7E01
    }

    @Inject
    lateinit var gameEngineCore: GameEngineCore

    @Inject
    lateinit var wakeLockManager: WakeLockManager

    @Inject
    lateinit var gameNotificationHelper: GameNotificationHelper

    private val binder = GameEngineBinder()

    override fun onCreate() {
        super.onCreate()
        // Hilt 字段注入在 super.onCreate() 中完成
        gameNotificationHelper.createChannel(this)
        wakeLockManager.acquire()
        // 初始化引擎（幂等）：原由 GameActivity.onCreate 调用，
        // 迁移到 Service 后由 Service 负责，确保 startGameLoop 前系统已就绪
        gameEngineCore.initialize()
        // 注册 AlarmManager 精确闹钟兜底唤醒（链式调度，每 15s 一次）
        AlarmWatchdogReceiver.scheduleAlarm(this)
        // 不在此启动游戏循环，等 onStartCommand 收到 ACTION_START
        Log.d(TAG, "onCreate: channel created, wakeLock acquired, engine initialized, alarm scheduled")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5s 内必须调用 startForeground，否则触发 ANR + 崩溃。
        // 包装为安全方法：即使前台通知启动失败（如 POST_NOTIFICATIONS 权限
        // 被拒绝导致 SecurityException），游戏循环仍需正常启动。
        safeStartForeground()

        when (intent?.action) {
            ACTION_START, null -> {
                if (!gameEngineCore.isGameLoopRunning) {
                    gameEngineCore.startGameLoop()
                    Log.d(TAG, "ACTION_START: game loop started")
                } else {
                    Log.d(TAG, "ACTION_START: game loop already running, skip")
                }
            }
            ACTION_PAUSE -> {
                // pause() 为 suspend，通过 engineScope 启动协程调用
                gameEngineCore.launchInScope {
                    gameEngineCore.pause()
                }
                updateNotification(paused = true)
                Log.d(TAG, "ACTION_PAUSE: game paused")
            }
            ACTION_RESUME -> {
                // resume() 为 suspend，通过 engineScope 启动协程调用
                gameEngineCore.launchInScope {
                    gameEngineCore.resume()
                }
                updateNotification(paused = false)
                Log.d(TAG, "ACTION_RESUME: game resumed")
            }
            ACTION_STOP -> {
                gameEngineCore.stopGameLoop()
                Log.d(TAG, "ACTION_STOP: game loop stopped, stopSelf")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 调用 shutdown() 而非 stopGameLoop()，确保完整释放：
        // - systemManager.releaseAll() 释放所有系统
        // - isInitialized = false 允许下次启动重新初始化
        // - engineScope / engineJob 重建，防止跨 session 状态污染
        // （stopGameLoop 仅取消 gameLoopJob，不会重置上述状态）
        gameEngineCore.shutdown()
        wakeLockManager.release()
        // 取消 AlarmManager 精确闹钟
        AlarmWatchdogReceiver.cancelAlarm(this)
        Log.d(TAG, "onDestroy: engine shutdown, wakeLock released, alarm cancelled")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 更新前台通知为暂停 / 运行状态。
     *
     * @param paused true 显示"已暂停"，false 显示"游戏运行中"
     */
    private fun updateNotification(paused: Boolean) {
        val notification = gameNotificationHelper.buildForegroundNotification(this, paused)
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 安全启动前台通知。
     *
     * 将 [startForeground] 包装在 try-catch 中，防止 POST_NOTIFICATIONS 权限
     * 被拒绝等场景下 [SecurityException] 导致整条 [onStartCommand] 崩溃。
     * 即使前台通知启动失败，游戏循环仍能正常运行——通知不是游戏循环的前置条件。
     *
     * @return true 前台通知启动成功，false 失败（已记录日志）
     */
    private fun safeStartForeground(): Boolean {
        return try {
            startForeground(
                NOTIFICATION_ID,
                gameNotificationHelper.buildForegroundNotification(this)
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground failed: POST_NOTIFICATIONS likely denied. " +
                "Game loop will continue without foreground notification. " +
                "Error: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed with unexpected exception. " +
                "Game loop will continue. Error: ${e.message}", e)
            false
        }
    }

    /**
     * Service Binder，暴露 [GameForegroundService] 与 [GameEngineCore] 实例供绑定方使用。
     *
     * 替代 GameActivity 直接 @Inject 持有 GameEngineCore 的旧模式：
     * 绑定方通过 [getGameEngineCore] 获取引擎实例，与 Service 共享同一 @Singleton 实例。
     */
    inner class GameEngineBinder : Binder() {
        /** 获取当前 Service 实例 */
        fun getService(): GameForegroundService = this@GameForegroundService

        /** 获取游戏核心引擎实例（@Singleton，与 Service 内注入的是同一实例） */
        fun getGameEngineCore(): GameEngineCore = gameEngineCore
    }
}
