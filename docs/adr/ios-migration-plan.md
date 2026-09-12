# ADR: iOS 立项迁移方案（ios-migration-plan）

> 状态：草稿（待行业对标研究集成后定稿）。日期：2026-09。
> 背景：用户已拍板"iOS 立项"。本文档给出 iOS 迁移的可落地路线。
> 【待集成】行业研究（Metal-cpp / Swift-C++ interop / Compose Multiplatform / SQLDelight 现状）正在调研中，第 2.7 节"关键决策点"待研究返回后定稿。

## 1. 背景与目标

### 1.1 现状（代码证据）
- **完全不支持 iOS**：全仓库无 `.xcodeproj`/`.pbxproj`/`.swift`/`.metal`（glob=0）。
- `app/src/main/cpp/CMakeLists.txt` **纯 Android**：`find_package(Vulkan REQUIRED)` + `target_link_libraries(... android log z)`。**无 iOS 编译目标。**
- 分层：`game-core` 静态库 **纯净可复用**（零 Android 泄漏）；`native-renderer`(Vulkan，Android-only) 与 `native-game-core`(JNI，Android-only)。
- `Rhi.h`+`SpriteBatcher` 无图形 API 头，**可复用**；但 `NativeBridge.cpp`（JNI/ANativeWindow/AAssetManager）是 Android 专属。
- `core/platform.h` 端口注入模式（MonotonicClock/TelemetrySink/ThermalStatusProvider/BatteryStatusProvider）**对 iOS 解耦友好**，iOS 只需注入 Metal/ObjC 实现。
- **UI/存档/图集/输入/音频全 Android/Kotlin**：Compose、kotlinx-proto(Room+云)、Kotlin Bitmap、TouchEngine、AndroidAudioPlayer。

### 1.2 目标
1. 在 **C++ 逻辑核心**（已可复用）之上，打通 iOS 的 5 条工程线：**渲染(Metal) / UI / 存档 / 输入 / 音频 + 合规**。
2. 明确"哪些能复用、哪些必须重做"，给出分阶段路线与客观风险评估。
3. 不破坏 Android 现状；逻辑核心零改动。

### 1.3 成功标准
- 能编译出 iOS 可运行的 Xcode 工程（哪怕先从"纯逻辑核心 headless 跑通"开始）。
- Metal 渲染能绘制地图 RenderFrame（复用 Rhi 接口思路）。
- 文档明确"当前 iOS 尚未完成，此项为立项路线"。

## 2. 技术方案

### 2.1 架构目标（复用 vs 重做）
```
[可复用] game-core 静态库（纯 C++20，逻辑/确定性/SoA/ECS 未来）
[可复用] gamecore/core/platform.h 端口接口 + Rhi.h + RenderFrame(纯 Kotlin 数据类，零 Android)
[可复用] 逻辑层 JNI 桥的"平台端口"抽象（Clock/Telemetry/热控/电量）

[需重做] 渲染 MetalBackend（实现 Rhi）；iOS 桥（Swift/ObjC++，替换 NativeBridge.cpp）
[需重做] UI：Compose Multiplatform(iOS) 或原生 SwiftUI
[需重做] 存档：SQLDelight / 原生 SQLite / Realm（替代 kotlinx-proto Room）
[需重做] 图集：Metal 纹理上传（替代 Kotlin Bitmap/AAssetManager）
[需重做] 输入：Touch → iOS 手势 → 现有 TouchEngine 语义
[需重做] 音频：AVFoundation（替代 AndroidAudioPlayer）
```

### 2.2 阶段一：纯逻辑核心 headless 跑通（最高确定性收益）
- 只编译 game-core（无渲染/无 UI），用 Swift/ObjC++ 壳调用 C++ GameCore（`nativeInit/nativeLoopFrame/nativeSettle*` 等经 C ABI 或 ObjC++ 桥）。
- 复用现有 **桌面对拍桥**（gamecore/jni/GameCoreJni.cpp）作为 iOS 上逻辑验证路径。
- **优先做，因为 0 平台风险、确定性最高。**

### 2.3 阶段二：Metal 渲染
- 实现 `MetalBackend`（实现 Rhi 纯虚接口）：MTLDevice/MTLCommandQueue/MTLCommandBuffer/MTLRenderPassDescriptor/MTLRenderPipelineState/MTLTexture/vertex buffer/`CAMetalLayer`/`CADisplayLink`(vsync)/`MTKTextureLoader`(图集)。
- **坐标差异**：Metal NDC z∈[0,1]、Y 轴翻转——由 `cameraProjMatrix` 层适配（Rhi.h 注释已有指南）。正交投影矩阵按 Metal 约定生成。
- 顶点格式复用 `SpriteVertex`（Rhi.h:35，位置+UV+颜色 8 floats）。单一管线/单图集，批处理复用 `SpriteBatcher`（无图形 API 头）。
- **用 Metal-cpp**（Apple 官方 `Metal-cpp.h`）以 C++ 写 Metal，避免大段 ObjC 代码。

### 2.4 阶段三：UI（关键决策点）
- 【需人工决策】`Compose Multiplatform`(iOS) 现状与风险 —— **最大不确定性**。若你现有全部 Compose UI 要复用，需评估 KMP/CM iOS 稳定性。
- 替代：原生 **SwiftUI**（界面重写，工作量大但稳定流行）+ 少量 Compose 复用。
- 建议：先调研 Compose Multiplatform iOS 生产可用性再决。

### 2.5 阶段四：存档（关键决策点）
- kotlinx-proto(Room) → 【需人工决策】`SQLDelight`（KMP 友好）、或原 SQLite(Native, 用现有 Room 表结构重建)、或 Realm。
- 确定性 RNG 与存档一致性在 iOS 上的保持（复用现有 rngStates 协议）。

### 2.6 阶段五：输入 / 音频 / 合规
- 输入：iOS UIGestureRecognizer / SwiftUI touch → 复用现有 `TouchEngine`(SectMapTouchEngine) 语义，平移/缩放/点按一致。
- 音频：AVFoundation 替换 AndroidAudioPlayer。
- 合规：Apple 审核（Metal 要求）、隐私政策、StoreKit。低端 A8 及以下性能考量。

### 2.7 关键决策点调研（已集成）
| 决策点 | 结论(来源) | 建议 |
|---|---|---|
| **Compose Multiplatform iOS** | **JetBrains 1.8.0(2025-05)：iOS 已稳定且生产可用**（S级官方博客） | ✅ 可作首选，复用现有 Compose UI；需评估打包体积/性能。 |
| **Metal 实现** | **Metal-cpp**（Apple 官方 C++ Metal 头，`Metal-cpp.h`）｜或 **MoltenVK**（Khronos：Vulkan-on-Metal，兼容现有 Vulkan 代码，S级官方） | ① 若求快：用 **MoltenVK** 复用现有 Vulkan 后端（但引入兼容层开销）；② 若求原生：用 **Metal-cpp** 手写 `MetalBackend` 实现 Rhi。**建议：Metal-cpp 原生实现**，MoltenVK 作备选。 |
| **Swift ↔ C++** | C++ interop（Swift Forums，A级）｜ObjC++ `.mm` 桥 | Swift 5.9+ 可直接调 C++（有限）；稳妥用 ObjC++ 薄桥 + C ABI 包裹 game-core。 |
| **存档** | **SQLDelight**（KMP 官方，S级，Ktor+SQLDelight 多平台模板）| 用 SQLDelight 重建 Room 表结构，KMP 双端共用；保 rngStates 确定性。 |

### 2.8 来源（iOS 相关，详见 render-strategy-decision.md 第 9 节清单 #16–#20）
JetBrains Compose Multiplatform 1.8.0、Swift C++ interop、KMP Ktor+SQLDelight、Khronos MoltenVK 1.3、Apple Metal-cpp。

## 3. 影响范围清单
| 文件/工程 | 变更类型 | 说明 |
|---|---|---|
| `game-core`（静态库）| 复用 | 零改动（若需 export 到 iOS，检查命名空间/无平台宏）。|
| 新增 Xcode 工程 | 新增 | `.xcodeproj`/Swift/ObjC++。|
| `core/platform.h` | 复用 | iOS 注入 Metal/AVFoundation 实现。|
| `Rhi.h`+`SpriteBatcher.h` | 复用 | 无图形 API 头；Metal 实现。|
| `NativeBridge.cpp`（Android）| 保留 | Android 专用；iOS 用新桥。|
| iOS MetalBackend | 新增 | 实现 Rhi 接口。|
| 新 iOS 桥（Swift/ObjC++）| 新增 | 替换 Java/JNI 桥，调用 game-core。|
| UI 方案 | 重做/复用 | Compose Multiplatform 或 SwiftUI（人工决策）。|
| 存档方案 | 重做 | SQLDelight / SQLite / Realm（人工决策）。|
| KTX 图集 | 重做 | Metal 纹理上传（替代 Kotlin Bitmap/AAssetManager）。|

## 4. 兼容性分析
- **Android**：不破坏；Android JNI/渲染保持现状。
- **存档**：iOS 存档方案需与 Android 存档兼容（若跨平台读取），否则需迁移工具。**这是关键兼容性风险。**
- **确定性**：game-core 纯 C++，iOS 上逻辑一致性由同一份 C++ 代码保证。

## 5. 测试方案
- 逻辑：iOS 上跑 game-core GTest（复用桌面对拍桥）。
- 渲染：MetalBackend 绘制 RenderFrame 的单元/集成测试（对照 Android Vulkan 输出）。
- 存档：iOS 读写与 Android 存档结构一致性测试。

## 6. 风险评估与兜底
- **风险 1：Compose Multiplatform iOS 不成熟** → 兜底 SwiftUI 重写 UI。
- **风险 2：存档跨端不一致** → 兜底统一 SQLite schema 或迁移工具。
- **风险 3：Metal 实现工作量大** → 兜底：先 headless 逻辑跑通（阶段一），渲染后置。
- **不破坏 Android**：所有 iOS 改动独立工程线。

## 7. 未来场景推演
- 双端统一 deterministic 逻辑 → 跨端一致性/对拍验证。
- 渲染 Rhi 抽象成熟 → 未来第三平台（PC/Web 后端）可复用。
- ECS（见 ecs-foundation-design）在 iOS 上直接复用。

## 8. 技术债与偿还计划
| 债项 | 偿还触发 |
|---|---|
| UI 双端不一致（Compose vs SwiftUI）| 若 Compose Multiplatform 成熟，收敛回 CM |
| 存档双端维护 | 跨端存档读取需求时统一 schema |

## 9. 【待集成】行业对标研究
> 研究返回后填充：Metal-cpp / Swift-C++ interop / Compose Multiplatform / SQLDelight 做法与来源（≥20 条总清单汇总到最终报告）。

### 附：当前 iOS 客观状态
- 无任何 iOS 工程/Metal/Swift → **【iOS 尚未完成】**
- 可复用：game-core（纯 C++）+ platform.h 端口 + Rhi/SpriteBatcher + RenderFrame。
- 必须重做：Metal/UI/存档/图集/输入/音频 + 合规。
