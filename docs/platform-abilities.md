# 平台能力登记表（Platform Abilities Registry）

> 对标 Godot `platform/` 目录 + DisplayServer 抽象（OS/窗口/渲染/音频/输入按平台实现，引擎核心不感知）。
> 本文档是 **iOS 跨平台可移植性**的唯一事实基线，替代 knowledge-base.md「iOS 跨平台可移植性基线」一节并扩展接口缺口分析。
> 更新日期：2026-08-13。

---

## 一、能力盘点总表

| 能力 | 现状实现 | Android 耦合点 | iOS 对等方案 | 接口抽象状态 |
|------|---------|---------------|-------------|-------------|
| 时间源 | `TimeSource` 接口（可注入时钟） | 无（纯 JVM） | 直接复用 | ✅ 已抽象 |
| 游戏渲染 | `RenderBackend` 接口 + RenderFrame 契约（零 Android 依赖） | Vulkan C++（JNI）/ SoftwareCanvasBackend | Metal 实现 RenderBackend / 软件渲染直接复用 | ✅ 已抽象（Metal 实现未建） |
| 触控手势 | `SectMapTouchEngine` 纯 Kotlin 状态机（core/engine/touch/） | `SurfaceView.onTouchEvent` 适配（TouchData 转换） | UITouch → TouchData 转换 | ✅ 已抽象 |
| 相机数学 | BaseCameraState/SectCameraState/WorldCameraState | 无（纯 JVM） | 直接复用 | ✅ 已抽象 |
| 动画时钟 | TimeSource + FadeTransition 纯函数 | 无（纯 JVM） | 直接复用（EngineTween 批次 1b 落地后统一） | ✅ 已抽象 |
| 广告 | `AdService` 接口（core/engine）→ `AdServiceImpl`（app 层） | TapTap SDK | TapTap iOS SDK 实现同一接口 | ✅ 已抽象 |
| 远程配置 | `RemoteConfigProvider` 接口（core/domain）→ `HttpRemoteConfigProvider`（core/engine，未绑定） | 无 | 直接复用 | ✅ 已抽象（未激活） |
| 崩溃上报 | CrashHandler 自研兜底 + Bugly | Bugly SDK | iOS 对等崩溃上报 SDK | ⚠️ 半抽象（app 层直引） |
| **音频** | `AudioEngine`（SoundPool + MediaPlayer） | **core/engine 直接 `import android.media.*`** | 需接口抽象（AVAudioEngine/AVAudioPlayer） | ❌ 未抽象（audio-thread-audit.md 发现项 A1） |
| 本地存储 | Room 2.7 + MMKV + DataStore + LZ4/Zstd | Room/DataStore Android 独占；MMKV 跨平台 | SQLDelight/原生 SQLite + MMKV | ⚠️ 部分（MMKV 跨平台，Room 未抽象） |
| 存档序列化 | ProtoBuf + kotlinx.serialization + CBOR | 无 | 直接复用 | ✅ 已抽象 |
| 网络 | Retrofit + OkHttp + Gson | Android 生态（Gson 遗留，项目其余处用 kotlinx.serialization） | Ktor 或接口抽象 | ⚠️ 部分 |
| 登录/云存档 | TapTap SDK（反射桥接 ReflectiveCloudSaveApi） | TapTap Android | TapTap iOS SDK + 同接口 | ⚠️ 半抽象（app 层） |
| DI | Hilt | Android 独占 | Koin/手写 DI | ❌ 未抽象 |
| UI 框架 | Jetpack Compose | Android 独占 | Compose Multiplatform 或重写 | ❌ 未抽象（最大迁移风险点） |
| 屏幕参数 | DeviceCapabilityProfiler/GpuTierDetector | Android API（Build/ActivityManager） | iOS 对等探测 | ❌ 未抽象（app 层） |
| 系统条/输入法 | DialogSoftInputGuard / DialogSystemBarGuard | Android WindowInsets | iOS 对等 | ❌ 未抽象（UI 层） |

## 二、接口缺口清单（按优先级）

| # | 缺口 | 方案 | 关联批次 |
|---|------|------|---------|
| G1 | 音频无接口抽象（AudioEngine 破坏 :core:engine 零 Android 依赖自我声明） | `AudioPlayerFacade` 接口（core/engine）+ `AndroidAudioPlayer`（app 层注入），参照 `AdService` 模式 | 登记待办（audio-thread-audit.md A1） |
| G2 | 渲染 surface 生命周期耦合 NativeSurfaceView（1107 行，Android 专属） | `SurfaceProvider` 接口（core/engine/.../platform/）+ AndroidSurfaceProvider 实现 | 批次 1c |
| G3 | 崩溃上报直引 Bugly | 抽象 `CrashReporter` 接口（core/domain）+ app 层实现 | 登记待办 |
| G4 | Room 无接口层 | 新数据层组件优先跨平台选型（SQLDelight 评估中），存量不动 | 登记待办 |
| G5 | DI 无抽象 | Koin/手写 DI 评估，迁移前置条件之一 | 登记待办 |
| G6 | UI 框架 Compose 独占 | Compose Multiplatform 评估（最高风险点，需专项 ADR） | 登记待办 |
| G7 | **网络层 Gson 遗留**（2026-08-13 登记） | 项目其余处统一用 kotlinx.serialization，仅网络层用 Gson——两套序列化并存易错；统一为 kotlinx.serialization（Retrofit converter 替换），iOS 迁移前无需先决 | 登记待办 |
| G8 | **DataStore/MMKV 双存储并存**（2026-08-13 登记） | 两套本地 K-V 干一件事；MMKV 已跨平台、DataStore Android 独占——偏好设置逐步迁入 MMKV，移除 DataStore 依赖（iOS 迁移前置项之一） | 登记待办 |

## 三、既有接口清单（新代码必须复用，禁止另起炉灶）

| 接口 | 位置 | 模式 |
|------|------|------|
| `RenderBackend` | core/engine/.../core/render/RenderBackend.kt | 平台能力接口 + 多实现 |
| `AdService` | core/engine/.../service/AdService.kt | 接口 + app 层实现注入 |
| `RemoteConfigProvider` | core/domain/.../config/RemoteConfigProvider.kt | 接口 + 未绑定实现 |
| `TimeSource` | core/engine/.../animation/TimeSource.kt | 可注入时钟 |
| `DomainLog` | core/domain | 可注入日志 |
| `ThermalReader` | core/engine（三通道温度读取） | 接口 + AndroidThermalReader |

**规则**（延续 code-quality.md 跨平台章节）：新增平台能力一律"core 层接口 + 平台实现注入"，禁止 core 层直接使用 Android 独占 API；本登记表随每次能力变更同步更新。
