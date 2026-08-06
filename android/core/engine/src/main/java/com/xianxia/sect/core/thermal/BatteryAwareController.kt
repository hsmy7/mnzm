package com.xianxia.sect.core.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.xianxia.sect.core.util.DomainLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 电池状态提供者 — 帧率/热控的电量感知输入（低电量未充电时主动降载）。
 *
 * 行业依据：低电量（<20%）时系统可能自动砍帧；充电/电池双策略是游戏行业通行做法
 * （UE EnergySavingPlugin、Unity BatteryAwareScheduler）。
 *
 * 电量读取无需任何权限（`ACTION_BATTERY_CHANGED` 为受保护系统广播，
 * sticky 值可经 `registerReceiver(null, filter)` 直接读取）。
 */
interface BatteryStatusProvider {

    /** 是否低电量（≤20%）且未充电 */
    val isLowBattery: Boolean

    /** 帧率上限（低电量未充电 45fps，正常 60fps） */
    val fpsCap: Int

    /** 热控阈值偏移（°C，低电量 -2 提前降载，正常 0） */
    val thermalThresholdOffsetC: Float
}

/** 空实现 — 默认值/测试占位（不降载） */
object NoopBatteryStatus : BatteryStatusProvider {
    override val isLowBattery: Boolean get() = false
    override val fpsCap: Int get() = BatteryAwareController.MAX_FPS_CAP
    override val thermalThresholdOffsetC: Float get() = 0f
}

/**
 * BatteryAwareController — Android 平台电量/充电状态读取实现。
 *
 * 读取走 sticky 广播缓存，**10s 内不重复 binder 调用**（fpsCap 在游戏循环
 * 每迭代被查询，必须避免每帧 registerReceiver）。
 *
 * **iOS 对等**：`UIDevice.batteryLevel` + `UIDevice.batteryState`（需
 * `UIDevice.current.isBatteryMonitoringEnabled = true`）+ `ProcessInfo.isLowPowerModeEnabled`；
 * 判定策略（≤20% 未充电 → 降载）共用 [evaluateBatteryPolicy] 纯函数，跨平台一致。
 *
 * @param context Application context
 */
@Singleton
class BatteryAwareController @Inject constructor(
    @ApplicationContext private val context: Context
) : BatteryStatusProvider {

    companion object {
        private const val TAG = "BatteryAwareController"
        /** 低电量判定阈值（%） */
        const val LOW_BATTERY_PERCENT = 20
        /** 低电量帧率上限（未充电时 60→45，防止掉帧式降压同时保留基础流畅） */
        const val LOW_BATTERY_FPS_CAP = 45
        /** 低电量热控阈值提前量（°C） */
        const val LOW_BATTERY_THRESHOLD_OFFSET_C = -2f
        /** 正常帧率上限 */
        const val MAX_FPS_CAP = 60
        /** 电量/充电状态读取缓存间隔（ms） */
        private const val READ_INTERVAL_MS = 10_000L
    }

    @Volatile
    private var cachedLevelPercent = -1

    @Volatile
    private var cachedCharging = false

    @Volatile
    private var lastReadMs = 0L

    private val lock = Any()

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
            return cachedLevelPercent in 0..LOW_BATTERY_PERCENT && !cachedCharging
        }

    override val fpsCap: Int
        get() = if (isLowBattery) LOW_BATTERY_FPS_CAP else MAX_FPS_CAP

    override val thermalThresholdOffsetC: Float
        get() = if (isLowBattery) LOW_BATTERY_THRESHOLD_OFFSET_C else 0f

    /**
     * 电量/充电状态 → 降载策略纯函数（测试直接覆盖）。
     *
     * @param levelPercent 电量百分比（-1 表示未知）
     * @param charging 是否充电中
     * @return Triple(isLowBattery, fpsCap, thermalThresholdOffsetC)
     */
    internal fun evaluateBatteryPolicy(
        levelPercent: Int,
        charging: Boolean
    ): Triple<Boolean, Int, Float> {
        val low = levelPercent in 0..LOW_BATTERY_PERCENT && !charging
        return Triple(
            low,
            if (low) LOW_BATTERY_FPS_CAP else MAX_FPS_CAP,
            if (low) LOW_BATTERY_THRESHOLD_OFFSET_C else 0f
        )
    }
}
