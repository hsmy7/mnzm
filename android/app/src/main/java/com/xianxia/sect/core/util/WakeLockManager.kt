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
 * - [acquire] 在游戏循环启动时调用（GameActivity.onResume）
 * - [release] 在游戏循环停止时调用（GameActivity.onPause）
 * - 无超时限制：WakeLock 持续持有直到 App 进入后台，防止
 *   荣耀 MagicOS 等激进 OEM 在游戏中途挂起 CPU
 *
 * ## 安全性
 * 生命周期由 Activity 的 onResume/onPause 管理。Android 系统在
 * App 进程被杀死时自动释放 WakeLock，不会持久泄漏。
 *
 * 参考：https://developer.android.google.cn/training/scheduling/wakelock
 */
@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeLockManager"

        /**
         * WakeLock tag。
         *
         * 华为 EMUI/HarmonyOS 的 HwPFWService 会检查 WakeLock tag 白名单，
         * 仅放行以下 6 个 tag 对应的进程/线程不被杀掉：
         *   "AudioMix", "AudioIn", "AudioDup", "AudioDirectOut",
         *   "AudioOffload", "LocationManagerService"
         *
         * 华为/荣耀设备使用 "AudioMix" 绕过 HwPFWService 的进程终止检测，
         * 其他厂商使用标准 tag。
         * 来源: https://dontkillmyapp.com/huawei
         */
        /**
         * WakeLock tag。
         *
         * 华为 EMUI/HarmonyOS 的 HwPFWService 会检查 WakeLock tag 白名单，
         * 仅放行以下 6 个 tag 对应的进程/线程不被杀掉：
         *   "AudioMix", "AudioIn", "AudioDup", "AudioDirectOut",
         *   "AudioOffload", "LocationManagerService"
         *
         * 华为设备使用 "AudioMix" 绕过 HwPFWService 的进程终止检测。
         *
         * 荣耀 MagicOS 无 HwPFWService（Honor 2020年脱离华为后自研
         * MagicOS，未继承此白名单机制），使用标准 tag 确保 MagicOS
         * 电源管理正确识别 WakeLock 来源。
         */
        private val WAKE_LOCK_TAG: String
            get() = when (ManufacturerAdapter.current) {
                ManufacturerAdapter.Manufacturer.HUAWEI -> "AudioMix"
                else -> "XianxiaSect::GameLoop"
            }
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

        // 无超时 acquire()：游戏期间需持续持有 WakeLock 防止 OEM 挂起 CPU。
        // 荣耀 MagicOS 等激进电源管理在 WakeLock 缺失时会将 CPU 挂起，
        // 即使 App 在前台。生命周期由 GameActivity.onPause() → release() 管理。
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.d(TAG, "WakeLock acquired (no timeout, released in onPause)")
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
