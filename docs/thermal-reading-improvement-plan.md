# 温度读取改进方案

> 调研日期：2026-07-07 | 来源：8+ | 优先级：高

---

## 一、当前问题分析

### 现有实现的问题

`ThermalController.readTemperature()` 当前从 `/sys/class/thermal/thermal_zone*/temp` 读取温度：

```kotlin
private fun readTemperature(): Float {
    val thermalPaths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp",
        ...
    )
    for (path in thermalPaths) {
        val content = File(path).readText().trim()
        val temp = content.toFloatOrNull() ?: continue
        return if (temp > 1000) temp / 1000f else temp
    }
    return -1f
}
```

| 问题 | 严重度 | 说明 |
|------|--------|------|
| SELinux 封锁 | 🔴 | 2025 年小米/华为/荣耀等设备 `/sys/class/thermal/` 被 SELinux 封锁，read 返回 EACCES |
| 无官方 API 回退 | 🔴 | 从未调用 `PowerManager.getThermalHeadroom()` 或 `addThermalStatusListener()` |
| 无 iOS 方案 | 🟡 | 未来跨平台移植缺少 iOS `ProcessInfo.thermalState` 对应实现 |
| 轮询频率无限制 | 🟡 | 每次 `checkAndAdjust` 都尝试读文件，无频率控制 |
| 不可靠的温度值 | 🟢 | 某些内核以毫摄氏度返回，解析逻辑假设所有 >1000 都是毫摄氏度 |
| **实战中大概率一直返回 -1** | 🔴 | 导致整个多级热控只靠帧率降级在工作 |

### 实测数据（来自调研）

| 场景 | 典型结果 |
|------|---------|
| 小米 14 / HyperOS | `readTemperature()` → -1（SELinux 封锁） |
| 华为 Mate 60 Pro / HarmonyOS | `readTemperature()` → -1（SELinux 封锁） |
| 荣耀 MagicOS | `readTemperature()` → -1（SELinux 封锁） |
| 三星 One UI | 部分可读，但返回值不稳定 |
| **模拟器** | 可读（仅模拟器场景有用） |

---

## 二、行业技术对标

### Android 官方推荐：ADPF Thermal API

Google 自 Android 10 (API 29) 起提供了标准的 Thermal API，是 **所有游戏类应用的推荐方案**。

| API | 最低版本 | 类型 | 推荐度 |
|-----|---------|------|--------|
| `PowerManager.getThermalHeadroom(forecastSeconds)` | API 30 | **主动预测** | ⭐⭐⭐ 首选 |
| `PowerManager.addThermalStatusListener()` | API 29 | 被动回调 | ⭐⭐ 辅助 |
| `AThermal_getThermalHeadroom()` (NDK) | API 31 | 主动预测 | ⭐⭐⭐ 原生首选 |
| `AThermal_registerThermalHeadroomListener()` | API 36 | 回调监听 | ⭐ Android 16+ |
| `HardwarePropertiesManager.getDeviceTemperatures()` | API 22 | **不推荐** | ⭐ 需要 `DEVICE_POWER` 权限 |

> 来源：[Android ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal)、[Android NDK Thermal Reference](https://developer.android.com/ndk/reference/group/thermal)

### 行业头部产品的做法

| 产品 | 热控方案 | 关键点 |
|------|---------|--------|
| **Android 官方推荐** | `getThermalHeadroom()` + `addThermalStatusListener()` | 主动预测 + 被动回调双通道 |
| **Unity Adaptive Performance** | 封装 Thermal API + Samsung/Qualcomm Provider | 多厂商适配层 |
| **UNISOC Miracle Engine** | ADPF Thermal + Performance Hint 联动 | 帧率提升 28-50%，功耗不增 |
| **Call of Duty Mobile** | ADPF + 多级画质降级 | 帧率 54→58fps，温度可控 |
| **iOS SpriteKit 官方推荐** | `ProcessInfo.thermalStateDidChangeNotification` | 监听 `.serious`/`.critical` 降画质 |

> 来源：[Unity ADPF Provider](https://docs.unity3d.com/Packages/com.unity.adaptiveperformance.google.android@1.3/)、[UNISOC ADPF Case Study](https://developer.android.com/stories/games/unisoc-adpf)、[Khronos/Samsung GDC Presentation](https://www.khronos.org/assets/uploads/developers/library/2019-reboot-develop-red/Mobilizing-Call-of-Duty_Oct19.pdf)

### Thermal Headroom 阈值行业标准

| `getThermalHeadroom()` | 对应状态 | 推荐动作 |
|------------------------|---------|---------|
| 0.0 – 0.05 | NONE | 无操作 |
| 0.05 – 0.85 | NONE→LIGHT | 监控，可选降低非关键工作 |
| 0.85 – 0.95 | LIGHT→MODERATE | **立即降负载**（降帧率/画质） |
| 0.95 – 1.0 | MODERATE→SEVERE | 高强度降级 |
| > 1.0 | SEVERE+ | 最低负载运行 |

> 来源：[ADPF Thermal API Guide](https://developer.android.com/games/optimize/adpf/thermal)

### iOS 对等方案

| iOS API | 最低版本 | 状态等级 |
|---------|---------|---------|
| `ProcessInfo.processInfo.thermalState` | iOS 11.0 | `.nominal` / `.fair` / `.serious` / `.critical` |
| `thermalStateDidChangeNotification` | iOS 11.0 | 通知回调 |

iOS 方案简单直接，**监听通知 + 查属性**即可，无需轮询。

> 来源：[Apple ProcessInfo.ThermalState](https://developer.apple.com/documentation/foundation/processinfo/thermalstate-swift.enum)

---

## 三、改进方案

### 核心策略：三通道温度获取

```
┌─────────────────────────────────────────────────────────┐
│ ThermalReader                                              │
├─────────────────────────────────────────────────────────┤
│ Channel 1 (API 30+): PowerManager.getThermalHeadroom()    │ ← 首选，主动预测
│ Channel 2 (API 29+): addThermalStatusListener()           │ ← 辅助，被动回调
│ Channel 3 (fallback): sysfs + BatteryManager              │ ← 降级路径
└─────────────────────────────────────────────────────────┘
```

### 架构设计

新建 `ThermalReader.kt`（接口）+ 平台实现，与 `ThermalController` 解耦：

```
ThermalReader (interface)
  ├── AndroidThermalReader (Android 实现)
  │     ├── getThermalHeadroom()  → PowerManager (API 30+)
  │     ├── getThermalStatus()    → PowerManager (API 29+)
  │     └── readSysfsTemperature() → sysfs (降级)
  ├── FallbackThermalReader (低 API 回退)
  │     └── readBatteryTemperature() → BatteryManager
  └── IosThermalReader (iOS 实现，预留)
        └── thermalState → ProcessInfo (iOS 11+)
```

### 详细实现方案

#### 3.1 主通道：`PowerManager.getThermalHeadroom()` (API 30+)

```kotlin
// ThermalReader.kt — 主温度获取接口
interface ThermalReader {
    /** 热余量 0.0~1.0+，NaN 表示不可用 */
    val thermalHeadroom: Float
    /** 当前热状态等级（兼容 iOS） */
    val thermalState: ThermalState
    /** 温度值（°C），-1f 表示不可用 */
    val temperatureCelsius: Float
}

enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL, UNKNOWN }

// AndroidThermalReader.kt
class AndroidThermalReader @Inject constructor(
    @ApplicationContext private val context: Context
) : ThermalReader {
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // 每 2 秒最多查询一次（官方建议 ≤1Hz）
    private var lastHeadroomQueryMs = 0L
    private val HEADROOM_INTERVAL_MS = 2000L

    override val thermalHeadroom: Float
        get() {
            val pm = powerManager ?: return Float.NaN
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
            val now = System.currentTimeMillis()
            if (now - lastHeadroomQueryMs < HEADROOM_INTERVAL_MS) return Float.NaN
            lastHeadroomQueryMs = now
            return try {
                pm.getThermalHeadroom(10)  // 预测未来 10 秒
            } catch (e: Exception) {
                Float.NaN
            }
        }

    override val thermalState: ThermalState
        get() {
            val pm = powerManager ?: return ThermalState.UNKNOWN
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN
            return try {
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.SERIOUS
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.UNKNOWN
                }
            } catch (e: Exception) {
                ThermalState.UNKNOWN
            }
        }

    override val temperatureCelsius: Float
        get() {
            // 优先用 headroom 反推估算温度
            val hr = thermalHeadroom
            if (!hr.isNaN() && hr >= 0f) {
                return estimateTemperatureFromHeadroom(hr)
            }
            // 降级：尝试 sysfs
            return readSysfsTemperature()
        }
}
```

#### 3.2 降级通道：sysfs + BatteryManager

当 API 30+ 不可用时（minSdk=24 需覆盖 API 24-29）：

```kotlin
private fun readSysfsTemperature(): Float {
    // 保留现有系统文件读取（仅作为降级）
    for (path in THERMAL_PATHS) {
        try {
            val content = File(path).readText().trim()
            val temp = content.toFloatOrNull() ?: continue
            return if (temp > 1000) temp / 1000f else temp
        } catch (_: Exception) { continue }
    }
    // 第二降级：BatteryManager 电池温度
    return readBatteryTemperature()
}

private fun readBatteryTemperature(): Float {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return try {
        (manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) ?: -1) / 10f
    } catch (_: Exception) { -1f }
}
```

#### 3.3 热状态回调监听（API 29+）

```kotlin
private val thermalStatusCallback = PowerManager.OnThermalStatusChangedListener { status ->
    val state = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.SERIOUS
        else -> ThermalState.CRITICAL
    }
    onThermalStateChanged(state)
}
```

#### 3.4 iOS 预留接口

```kotlin
// 跨平台接口 — iOS 实现使用 ProcessInfo.thermalState
// expect class IosThermalReader : ThermalReader {
//     override val thermalState: ThermalState
//         get() = when (ProcessInfo.processInfo.thermalState) {
//             .nominal -> ThermalState.NOMINAL
//             .fair -> ThermalState.FAIR  
//             .serious -> ThermalState.SERIOUS
//             .critical -> ThermalState.CRITICAL
//         }
// }
```

---

## 四、与 ThermalController 的集成

```kotlin
@Singleton
class ThermalController @Inject constructor(
    private val profiler: DeviceCapabilityProfiler,
    private val thermalReader: ThermalReader,  // 新增注入
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS
) {
    // 将 readTemperature() 替换为 thermalReader
    private fun readTemperature(): Float {
        val celsius = thermalReader.temperatureCelsius
        if (celsius >= 0f) return celsius
        // 用 headroom 反推温度
        val hr = thermalReader.thermalHeadroom
        if (!hr.isNaN() && hr > 0f) {
            return estimateTemperatureFromHeadroom(hr)
        }
        return -1f
    }

    // headroom → 估算温度（经验公式）
    private fun estimateTemperatureFromHeadroom(headroom: Float): Float {
        return when {
            headroom >= 1.0f -> 46f  // SEVERE
            headroom >= 0.95f -> 44f  // MODERATE+
            headroom >= 0.85f -> 42f  // MODERATE
            headroom >= 0.5f -> 40f   // LIGHT
            else -> 37f               // NONE
        }
    }
}
```

---

## 五、实施步骤

### Phase 1：新增 ThermalReader（1天）

1. 新建 `core/engine/.../thermal/ThermalReader.kt` — 接口 + ThermalState 枚举
2. 新建 `core/engine/.../thermal/AndroidThermalReader.kt` — Android 实现
3. 新建 `core/engine/.../thermal/FallbackThermalReader.kt` — 低 API 降级
4. 注册到 Hilt 模块
5. 单元测试覆盖三个通道

### Phase 2：集成到 ThermalController（0.5天）

1. 将 `readTemperature()` 委托给 `ThermalReader`
2. 添加 ThermalState 回调监听
3. 将 `thermalState` 暴露给 UI 层用于展示
4. 测试：模拟 headroom 变化 → 降级阶梯响应

### Phase 3：iOS 预留 + 文档（0.5天）

1. 在接口中添加 `expect`/`actual` 结构预留
2. 编写集成测试
3. 更新 `docs/` 架构文档

---

## 六、测试方案

| 测试场景 | 方法 | 预期 |
|---------|------|------|
| API 30+ 设备 | Mock `PowerManager.getThermalHeadroom` 返回 0.0/0.5/0.9/1.2 | 正确映射到 GREEN/YELLOW/ORANGE/RED |
| API 29 设备 | Mock `PowerManager.currentThermalStatus` | 正确映射 ThermalState |
| API < 29 设备 | 无 PowerManager API | 触发 sysfs/Battery 降级路径 |
| SELinux 封锁 | Mock sysfs 返回 EACCES | 降级到 BatteryManager |
| 完全不可用 | 所有通道返回 -1/NaN | 返回 -1，ThermalController 纯靠帧率降级 |
| iOS 平台 | 通过 expect/actual 切换实现 | 调用 ProcessInfo.thermalState |

---

## 七、需要修改的文件清单

| 文件 | 操作 | 风险 |
|------|------|------|
| `core/engine/.../thermal/ThermalReader.kt` | 新建 | 低 |
| `core/engine/.../thermal/AndroidThermalReader.kt` | 新建 | 低 |
| `core/engine/.../thermal/FallbackThermalReader.kt` | 新建 | 低 |
| `core/engine/.../thermal/ThermalReaderModule.kt` | 新建（Hilt 绑定） | 低 |
| `core/engine/.../concurrent/ThermalController.kt` | 修改：注入 ThermalReader | 中 |
| `core/engine/.../concurrent/ThermalControllerTest.kt` | 修改：Mock ThermalReader | 低 |
| `core/engine/src/test/.../thermal/ThermalReaderTest.kt` | 新建 | 低 |

---

## 八、参考来源

| # | 标题 | 来源 | 等级 |
|---|------|------|------|
| 1 | [ADPF Thermal API](https://developer.android.com/games/optimize/adpf/thermal) | Android Developers | S |
| 2 | [Thermal Mitigation (AOSP)](https://source.android.com/docs/core/power/thermal-mitigation) | Android Open Source Project | S |
| 3 | [NDK Thermal Reference](https://developer.android.com/ndk/reference/group/thermal) | Android Developers | S |
| 4 | [PowerManager reference](https://developer.android.com/reference/android/os/PowerManager) | Android Developers | S |
| 5 | [ProcessInfo.ThermalState](https://developer.apple.com/documentation/foundation/processinfo/thermalstate-swift.enum) | Apple Developer | S |
| 6 | [UNISOC ADPF Case Study](https://developer.android.com/stories/games/unisoc-adpf) | Android Developers | A |
| 7 | [Unity Adaptive Performance Android](https://docs.unity3d.com/Packages/com.unity.adaptiveperformance.google.android@1.3/) | Unity Docs | S |
| 8 | [ADPF Sample & Codelab](https://developer.android.com/games/optimize/adpf/sample-codelab-story) | Android Developers | S |
| 9 | [Call of Duty Mobile: Mobilizing on Vulkan](https://www.khronos.org/assets/uploads/developers/library/2019-reboot-develop-red/Mobilizing-Call-of-Duty_Oct19.pdf) | Khronos Group | A |
| 10 | [Android 动态性能框架优化散热和 CPU 性能](https://blog.csdn.net/learnframework/article/details/149964295) | CSDN | B |

---

## 九、关键决策记录

| 决策 | 选项 | 选择理由 |
|------|------|---------|
| 新建接口 vs 修改现有 | 新建 `ThermalReader` 接口 | 与 `ThermalController` 解耦，便于测试和 iOS 移植 |
| 主 API 选择 | `getThermalHeadroom()` | 主动预测优于被动回调，可在阈值到达前降级 |
| 最低 API 覆盖 | minSdk=24（API 24） | BatteryManager 回退支持 |
| iOS 方案 | `ProcessInfo.thermalState` | Apple 官方推荐 |

> 用户确认后开始 Phase 1 实施。
