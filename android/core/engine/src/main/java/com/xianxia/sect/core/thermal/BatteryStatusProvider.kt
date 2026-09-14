package com.xianxia.sect.core.thermal

/**
 * 电量状态提供者 — 帧率/热控的电量感知输入（低电量未充电时主动降载）。
 *
 * 行业依据：低电量（<20%）时系统可能自动砍帧；充电/电池双策略是游戏行业通行做法
 * （UE EnergySavingPlugin、Unity BatteryAwareScheduler）。
 *
 * 平台能力接口化：接口与降载策略纯函数留在引擎层，
 * Android 实现（app 层 `platform.BatteryAwareController`）经 sticky 广播读取；
 * **iOS 对等**：`UIDevice.batteryLevel` + `UIDevice.batteryState`（需
 * `UIDevice.current.isBatteryMonitoringEnabled = true`）+
 * `ProcessInfo.isLowPowerModeEnabled`，判定策略共用 [evaluatePowerPolicy] 纯函数，
 * 跨平台一致。
 */
interface BatteryStatusProvider {

    /** 是否低电量（≤20%）且未充电 */
    val isLowBattery: Boolean

    /** 系统是否处于省电模式（`PowerManager.isPowerSaveMode` 监听） */
    val isPowerSaveMode: Boolean

    /** 帧率上限（低电量未充电 45 / 系统省电模式 30 / 正常 60，取 min） */
    val fpsCap: Int

    /** 热控阈值偏移（°C，低电量 -2 提前降载，正常 0） */
    val thermalThresholdOffsetC: Float
}

/** 电量降载策略常量（平台实现与纯函数共用，唯一权威在引擎层） */
object BatteryPolicy {
    /** 低电量判定阈值（%） */
    const val LOW_BATTERY_PERCENT = 20

    /** 低电量帧率上限（未充电时 60→45，防止掉帧式降压同时保留基础流畅） */
    const val LOW_BATTERY_FPS_CAP = 45

    /** 系统省电模式帧率上限（省电模式 30fps——用户主动省电意愿最强，
     *  行业对标 Android 官方 Game Mode BATTERY 档位行为） */
    const val POWER_SAVE_FPS_CAP = 30

    /** 低电量热控阈值提前量（°C） */
    const val LOW_BATTERY_THRESHOLD_OFFSET_C = -2f

    /** 正常帧率上限 */
    const val MAX_FPS_CAP = 60
}

/** 空实现 — 默认值/测试占位（不降载） */
object NoopBatteryStatus : BatteryStatusProvider {
    override val isLowBattery: Boolean get() = false
    override val isPowerSaveMode: Boolean get() = false
    override val fpsCap: Int get() = BatteryPolicy.MAX_FPS_CAP
    override val thermalThresholdOffsetC: Float get() = 0f
}

/**
 * 省电模式/电量/充电状态 → 降载策略纯函数（测试直接覆盖）。
 *
 * fpsCap = min(低电量 45, 省电模式 30, 正常 60)——两级降载各自独立生效，
 * 经 [com.xianxia.sect.core.GameEngineCore.updateRenderFrameRate] 既有 min 链
 * （场景×模式×热控×电量）自动合并，零额外接线。
 *
 * @param powerSaveMode 系统省电模式是否开启
 * @param levelPercent 电量百分比（-1 表示未知，按非低电量安全回退）
 * @param charging 是否充电中
 * @return PowerPolicy(isLowBattery, fpsCap, thermalThresholdOffsetC)
 */
fun evaluatePowerPolicy(
    powerSaveMode: Boolean,
    levelPercent: Int,
    charging: Boolean
): PowerPolicy {
    val lowBattery = levelPercent in 0..BatteryPolicy.LOW_BATTERY_PERCENT && !charging
    val lowBatteryCap = if (lowBattery) BatteryPolicy.LOW_BATTERY_FPS_CAP else BatteryPolicy.MAX_FPS_CAP
    val powerSaveCap = if (powerSaveMode) BatteryPolicy.POWER_SAVE_FPS_CAP else BatteryPolicy.MAX_FPS_CAP
    return PowerPolicy(
        isLowBattery = lowBattery,
        fpsCap = minOf(lowBatteryCap, powerSaveCap),
        thermalThresholdOffsetC = if (lowBattery) BatteryPolicy.LOW_BATTERY_THRESHOLD_OFFSET_C else 0f
    )
}

/** 电量/省电降载策略结果（[evaluatePowerPolicy] 返回值） */
data class PowerPolicy(
    val isLowBattery: Boolean,
    val fpsCap: Int,
    val thermalThresholdOffsetC: Float
)
