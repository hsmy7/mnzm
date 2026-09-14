package com.xianxia.sect.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.xianxia.sect.core.thermal.BatteryPolicy
import com.xianxia.sect.core.thermal.BatteryStatusProvider
import com.xianxia.sect.core.thermal.evaluatePowerPolicy
import com.xianxia.sect.core.util.DomainLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryAwareController — Android 平台电量/充电状态读取实现：策略纯函数与接口
 * 留引擎层 `core.thermal.BatteryStatusProvider`，本类只做 Android 平台读取。
 *
 * 读取走 sticky 广播缓存，**10s 内不重复 binder 调用**（fpsCap 在游戏循环
 * 每迭代被查询，必须避免每帧 registerReceiver）。
 *
 * **iOS 对等**：`UIDevice.batteryLevel` + `UIDevice.batteryState`（需
 * `UIDevice.current.isBatteryMonitoringEnabled = true`）+ `ProcessInfo.isLowPowerModeEnabled`；
 * 判定策略（≤20% 未充电 → 降载）共用引擎层 [evaluatePowerPolicy] 纯函数，跨平台一致。
 *
 * @param context Application context
 */
@Singleton
class BatteryAwareController @Inject constructor(
    @ApplicationContext private val context: Context
) : BatteryStatusProvider {

    companion object {
        private const val TAG = "BatteryAwareController"

        /** 电量/充电状态读取缓存间隔（ms） */
        private const val READ_INTERVAL_MS = 10_000L
    }

    @Volatile
    private var cachedLevelPercent = -1

    @Volatile
    private var cachedCharging = false

    @Volatile
    private var cachedPowerSaveMode = false

    @Volatile
    private var lastReadMs = 0L

    private val lock = Any()

    /** PowerManager（isPowerSaveMode 读取） */
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** 省电模式变化广播接收器（系统保护广播，无需权限） */
    private val powerSaveReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            cachedPowerSaveMode = powerManager?.isPowerSaveMode ?: false
        }
    }

    init {
        // 初始值 + 动态注册省电模式监听（Singleton = 进程生命周期，无需显式反注册）
        cachedPowerSaveMode = powerManager?.isPowerSaveMode ?: false
        registerPowerSaveReceiver()
    }

    /** 注册省电模式广播（API 33+ RECEIVER_NOT_EXPORTED 守卫；失败按非省电安全回退） */
    private fun registerPowerSaveReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag", "Deprecation")
                context.registerReceiver(powerSaveReceiver, filter)
            }
        } catch (e: SecurityException) {
            DomainLog.w(TAG, "register power save receiver denied: ${e.message}", e)
        }
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastReadMs < READ_INTERVAL_MS) return
        synchronized(lock) {
            if (now - lastReadMs < READ_INTERVAL_MS) return
            lastReadMs = now
            val intent = readBatteryIntent()
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                cachedLevelPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cachedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                cachedLevelPercent = -1
                cachedCharging = false
            }
        }
    }

    /** 读取 sticky 电池广播（无需权限；API 33+ 带 flag 兼容） */
    private fun readBatteryIntent(): Intent? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag", "Deprecation")
                context.registerReceiver(null, filter)
            }
        } catch (e: SecurityException) {
            // OEM 上读取粘性广播可能被拒；记录后降级为"未知电量"
            //（非低电量安全回退），不冒泡到游戏循环
            DomainLog.w(TAG, "read battery intent denied: ${e.message}", e)
            null
        }
    }

    override val isLowBattery: Boolean
        get() {
            refreshIfStale()
            return cachedLevelPercent in 0..BatteryPolicy.LOW_BATTERY_PERCENT && !cachedCharging
        }

    override val isPowerSaveMode: Boolean
        get() = cachedPowerSaveMode

    override val fpsCap: Int
        get() {
            refreshIfStale()
            return evaluatePowerPolicy(cachedPowerSaveMode, cachedLevelPercent, cachedCharging).fpsCap
        }

    override val thermalThresholdOffsetC: Float
        get() = if (isLowBattery) BatteryPolicy.LOW_BATTERY_THRESHOLD_OFFSET_C else 0f
}
