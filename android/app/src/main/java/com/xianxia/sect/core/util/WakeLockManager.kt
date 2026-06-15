package com.xianxia.sect.core.util

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前台游戏循环 CPU 休眠防护。
 *
 * Android 官方文档明确：Foreground Service 不保证 CPU 不休眠，
 * 必须额外持有 PARTIAL_WAKE_LOCK 才能防止系统在游戏运行期间挂起 CPU。
 *
 * ## 使用
 * - [acquire] 在游戏循环启动时调用
 * - [release] 在游戏循环停止时调用
 *
 * ## Google Play 政策
 * 2026 年 3 月起，24h 内持有非豁免 WakeLock >2h 的应用会展示警告。
 * 本 WakeLock 仅在游戏循环活跃（前台）时持有，正常使用远低于阈值。
 *
 * 参考：https://developer.android.google.cn/training/scheduling/wakelock
 */
@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKE_LOCK_TAG = "XianxiaSect::GameLoop"
        /** 超时自动释放，防止意外泄漏耗尽电池 */
        private const val ACQUIRE_TIMEOUT_MS = 10 * 60 * 1000L // 10 分钟
    }

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /** 获取 WakeLock，保持 CPU 不休眠。幂等。 */
    fun acquire() {
        if (wakeLock?.isHeld == true) {
            Log.d(TAG, "WakeLock already held, skipping acquire")
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.w(TAG, "PowerManager not available, cannot acquire WakeLock")
            return
        }

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire(ACQUIRE_TIMEOUT_MS)
        }

        Log.d(TAG, "WakeLock acquired (timeout=${ACQUIRE_TIMEOUT_MS}ms)")
    }

    /** 释放 WakeLock。幂等。 */
    fun release() {
        val wl = wakeLock
        if (wl != null && wl.isHeld) {
            wl.release()
            Log.d(TAG, "WakeLock released")
        }
        wakeLock = null
    }
}
