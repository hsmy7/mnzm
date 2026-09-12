# ADR: 渲染路径 / GPU 分级策略决策（render-strategy-decision）

> 状态：**主修复已实施**（2026-09-09：GPU OpenGL ES 中间层已落地，降级链现为 `Vulkan→GPU GLES→CPU Canvas`）+ **量化阈值已实施**（2026-09 续作，见下）+ 含行业对标研究结论。日期：2026-09。
> 背景：用户已拍板调研第三点（GPU 白名单 vs Canvas 软件渲染）。本文档给出代码级问题定性 + 行业对标 + 明确推荐。
> ✅ **GPU GLES 中间层**本体已实施（commit ca4bf15a）；✅ **量化阈值（default Vulkan + 窄 Deny）机制已实施**——`VulkanPolicy` 增量化决策引擎（`evaluateVulkanTier`：按 GPU 厂商 + Vulkan API 版本判定，Unity Device Filtering 规格为阈值）+ C++ 探测上报（apiVersion/vendorId/deviceName → `setVulkanDeviceInfo`）+ `detectTier` 移除整厂商/整机型一刀切拉黑改默认 Vulkan + 窄 Deny；`VulkanPolicyQuantizedThresholdTest` 14 用例。**阈值数值为行业基准默认，真机 Bugly 校准（作阈值数据调整，无需改代码）与 CPU Canvas 优化仍为后续项。**

## 1. 背景与目标

### 1.1 问题定性（代码证据，完全确凿）
- 游戏渲染有**双路径**：Vulkan（真实 GPU）+ Canvas（CPU 软件渲染），经 `RenderBackend` 统一，消费同一 `RenderFrame`。
- **绝大多数中国厂商设备被 `VulkanPolicy` 主动降级到 Canvas 软件渲染**：
  - `VulkanPolicy.detectTier`(VulkanPolicy.kt:605)：MediaTek SoC、非高通国产厂商、已知问题 GPU(Mali-G5x/6x/7x、Adreno 6xx/7xx、PowerVR、Xclipse)、已知问题机型（几十款）、API<31 非 Google 白名单 → `PROBLEMATIC`。
  - `getRenderStrategy`(VulkanPolicy.kt:445)：PROBLEMATIC→SOFTWARE_ONLY；崩溃自愈/云游戏/模拟器/Vulkan 崩溃→SOFTWARE_ONLY。
- 结果：**Vulkan 只在高通系/Google 设备生效；主流中国安卓（性能目标受众）走 CPU Canvas**。逐帧 `drawBitmap`(~16K 瓦片)+每瓦片 `Bitmap.createBitmap`(SoftwareCanvasBackend.kt:328)→ CPU/内存/电量重负载。**"性能提升"卖点在主流目标设备上不成立。**

### 1.2 设计矛盾与目标
- 当前策略 = "黑名单优先、软件兜底" → 以崩溃安全换取性能，但把最需要性能的设备推向软件渲染。
- 需权衡：**扩大 GPU 白名单（性能↑，驱动崩溃风险↑）vs 优化 Canvas（崩溃安全，仍 CPU 重负载）vs 结合**。

## 2. 【调研结论】主流厂商/引擎怎么做（第 9 节展开）

### 2.1 行业标准做法：Unity —— 按"GPU 厂商 + Vulkan API 版本 + 驱动版本"量化阈值，而非整片黑名单（决定性证据，官方原文）
Unity 官方文档《Allow or deny Vulkan API usage》明确：
- Unity **默认阻止已知运行不佳的 Android 设备使用 Vulkan**（一个内置 deny 列表）。
- 用 **`Android Vulkan Allow/Deny Filter List`** 精细调节，匹配参数：**Vendor / Device Name / Brand / Product Name / Android OS version / Vulkan API version / Driver version**，参数间逻辑 AND，支持正则。
- **Allow 列表**识别"Vulkan API + 驱动版本 ≥ 阈值"的设备；**Deny 列表**识别"≤ 阈值"的设备。
- **按 GPU 厂商的默认最低规格**：
  - NVIDIA：Vulkan API ≥ 1.0.13
  - ARM(Mali)：Vulkan API ≥ **1.0.61**
  - Imagination(PowerVR)：Vulkan API ≥ **1.1.170** 且 Driver ≥ 1.473.1397
  - Qualcomm：Driver 最高位(MSB)置位 或 Vulkan API ≥ 1.0.49
- 备注：**Unity 默认连 Google Pixel 6 都禁 Vulkan**（置于 Deny 白名单中，需手动移除以测试）。

> **启示**：项目当前的 `VulkanPolicy` 是"按厂商/品牌/机型/GPU 家族"整片拉黑（`KNOWN_PROBLEM_MANUFACTURERS`/`KNOWN_PROBLEM_MODELS`/`KNOWN_PROBLEM_GPU_PATTERNS`），比 Unity 粒度粗。**应改为按"GPU 厂商 + Vulkan API 版本 + 驱动版本"的量化阈值**，而非一刀切厂商。

### 2.2 驱动缺陷规避的标准算法：Chromium —— 按"驱动版本区段"，非按厂商
Chromium 的 `gpu_driver_bug_list.json`（官方）采用 **driver version 区段** + feature 白名单，而非按 vendor/model 整片禁用；配合 `VkPhysicalDeviceProperties::apiVersion/driverVersion`/`deviceName`。**按"驱动版本范围"精准圈定坏驱动，是行业认可的更细做法。**

### 2.3 主流托管框架的例子：Flutter Impeller —— 双后端 + 对 Mali/MTK 异常谨慎
- Flutter Impeller 在 Android 采用 **OpenGLES 与 Vulkan 双后端**；对 MediaTek/Mali 有 MTK 自动回退策略（业界公开）。
- **真实教训**：Flutter issue #190195 反映 Impeller 在 **Mali GPU(MediaTek/Samsung) 上**生产环境崩溃(SIGABRT/SIGSEGV)，且 `EnableImpeller=false` 已废弃，**"无可用替代"**——说明 **Mali/MTK 上的 Vulkan 驱动缺陷是真实且严重的**，不能无脑放开白名单。

### 2.4 GPU 能力探测工具：Android GPU Inspector（官方）
Android GPU Inspector（官方) 用于按 driver version/feature 探测 GPU 能力，是"能力探测而非厂商黑名单"的落地工具。

### 2.5 软件渲染（CPU rasterization）在市场中的定位
- SwiftShader（Google 开源软件光栅化器）说明：软件渲染用于**保底/兼容**，不作为高性能主路径。
- 结论：**软件渲染只能是"崩溃兜底"，不能是"主流设备主路径"。** 项目当前恰恰把它当主流设备主路径，这是方向性问题。

## 3. 明确推荐（结合以上 + 补充深调 Android 调研）

> 补充：本目另有专项调研《docs/research-android-graphics-api-vulkan-gles-software.md》（39 条来源，S 级 25/A 级 8/B 级 6；含 原神/星铁/王者荣耀/Unity/Unreal/Flutter/Godot/Chromium 对标 + 诚实声明），其核心洞察对本节推荐如下：

**关键结构性结论**：整个行业（Unity/Unreal/Chromium/Flutter/Godot/头部手游）的降级链都是 **`Vulkan → GPU OpenGL ES → 软件渲染(仅最终兜底)`**，**没有任何一家把 CPU 软渲染当低端主路径**。本项目当前是 **`Vulkan → CPU Canvas`** 且**额外关闭系统硬件加速**(→真·CPU 逐像素)——**缺失了行业标配的 GPU GLES 中间层**，这是性能/电量风险的最主要根因，也是本项目与行业最大的结构性差异。

**推荐 = 主 + 辅（不是二选一）**：

**主（结构性修复）**：
1. **补一个 GPU OpenGL ES 中间层**（第三个 `RenderBackend`），降级链变为 **`Vulkan → GPU GLES → CPU Canvas`**。可复用 `Rhi.h`接口 + 同一 `RenderFrame` 契约；GLES 3.1 即可（2D 场景）。**这是结构性最优，对齐行业，救回"Vulkan 有缺陷但 GPU 可用"的设备。**
2. 若短期内不做 GLES 中间层，**至少保持系统硬件加速 ON**（让 Canvas 走 GPU Skia，不要关 HWUI 变真 CPU）——当前 `VulkanPolicy.shouldDisableHardwareAcceleration` 关闭 HWUI 把本可 GPU 的 Canvas 变成真 CPU，放大了风险。

**次主（扩白名单 + 保 Vulkan）**：
3. 把"只留 Adreno 的宽黑名单"反转成 **默认 Vulkan + 窄 Deny 列表**：按 **SoC/GPU 厂商 + Vulkan API 版本 + 驱动版本** 设阈值（参考 Unity 规格：Mali≥1.0.61、PowerVR≥1.1.170+驱动、Qualcomm 驱动 MSB 或≥1.0.49；星铁官方 SoC 档位表；UE `_No_Vulkan` 按"家族+版本"组合），而非整厂商整族禁用。用 `VkPhysicalDeviceProperties.apiVersion/driverVersion/deviceName` 探测。
4. **保留/强化**：能力探测（API 门槛 + Vulkan 版本 + 必需扩展，真建上下文 `IsValid()` 为准）+ **崩溃"计数+宽限期"自愈**（偶发不降级、短时反复才降级）+ **逐缺陷 workaround**（关坏特性而非禁用整条 API）+ RemoteConfig 做 SoC 级 kill switch + Bugly 数据校准。
5. **辅**：优化 CPU Canvas（预渲染地面层、瓦片缓存、LOD/装饰跳过、renderScale 降采样）作为**灾难兜底保险**，不作低端主路径。

**取舍**：扩白名单/默认 Vulkan 的收益（显著改善华为/小米/vivo/荣耀/MediaTek 等设备的性能与功耗）远大于风险（少数真·坏驱动设备的崩溃，可被"能力探测 + 崩溃自愈 + 远程开关"吸收，行业普遍接受）。**加 GPU GLES 中间层是结构性最优；若不补，次优 = 保持默认 Vulkan + 崩溃自愈 + 保持硬件加速 ON。绝不要把"关 HW 加速走真 CPU"作为常规低端路径。**

> **诚实声明（调研无法确认项）**：① 米哈游/腾讯**逐机型完整黑/白名单未公开**（仅有官方 SoC 档位表/游戏内设置/厂商适配名单/引擎通用机制）；② 项目规范中"对齐 Chromium（Mali-G57 全面禁用 Vulkan）"**未查到官方原文**（实为"按 GPU+驱动版本选择性禁用"机制）；③ "Android<15 定制 ROM 必回传 SkiaVK/Vulkan HWUI"**无权威来源**（属工程经验向，但"关 Vulkan 需同步关 Activity 级 HWUI、覆盖所有 Activity 入口"方向正确）。这些依据在后续决策与 `rules/*` 更新需打折扣/标明来源。

## 4. 影响范围清单
| 文件 | 变更类型 | 说明 |
|---|---|---|
| `VulkanPolicy.kt` | 修改 | 量化阈值(厂商+Vulkan API版本+驱动版本)替代整片厂商/机型黑名单；驱动版本黑名单。|
| `VulkanBackend.cpp` | 修改/复用 | 暴露 apiVersion/driverVersion/deviceName 探测（已有 getVulkanDriverVersion 雏形）。|
| `SoftwareCanvasBackend.kt` | 修改 | 预渲染地面层、瓦片缓存、LOD 优化。|
| `RenderScalePolicy.kt` | 修改 | 渐进式降级决策。|
| `renderer-feature-checklist.md` | 修改 | 记录新降级规则双端同步。|
| `rules/*` | 修改 | 更新 GPU 黑名单行业对齐说明。|

## 5. 兼容性分析
- Android 各 API/GPU 兼容性是核心；**保留崩溃自愈兜底**（大量 Bugly 崩溃证据，真实风险）。
- 不破坏现有 Canvas/Vulkan 双路径行为。

## 6. 测试方案
- `SoftwareCanvasBackendTest`(22+ 用例) 回归；新增预渲染地面层测试。
- 真机验证：低/中/高端 Android GPU 上 Vulkan 启用率、帧率、电量。
- `RenderScalePolicyTest`/`FrameSkipPolicyTest` 回归。
- 新增 `VulkanPolicyQuantizedThresholdTest`（按模拟 deviceName/apiVersion/driverVersion 判定）。

## 7. 风险评估与兜底
| 风险 | 兜底 |
|---|---|
| 扩白名单→驱动崩溃 | 崩溃自愈(CrashRecoveryEngine) + 驱动版本黑名单 + 隔离单次崩溃降级。|
| Mali/MTK 实际上不可靠 | 未达 Vulkan 阈值(Mali≥1.0.61 等) → 仍走优化后软件渲染。|
| CPU Canvas 白名单不足 | 优先预渲染/LOD 优化软件兜底。|

## 8. 未来场景推演
- 中高端安卓：Vulkan 加速（收益兑现）。
- 低端安卓：优化后软件渲染可用。
- iOS：Metal（见 ios-migration-plan）复用同一 Rhi/RenderFrame 抽象。

## 9. 行业对标研究（来源清单，≥20 条主力）

### GPU / 渲染（第 1-3 点）
| # | 来源 | 等级 | URL |
|---|---|---|---|
| 1 | Unity《Allow or deny Vulkan API usage》(Unity 6.0 官方) | **S** | https://docs.unity3d.com/6/Documentation/Manual/allow-deny-vulkan-usage.html |
| 2 | Android Developers《Game engine support (Vulkan)》 | **S** | https://developer.android.com/games/develop/vulkan/game-engine-support |
| 3 | Unity《Graphics API hardware profile settings》 | **S** | https://docs.unity3d.com/6000.7/Documentation/Manual/vulkan-hardware-profiles-graphics-api.html |
| 4 | Chromium `gpu_driver_bug_list.json`（驱动版本区段黑名单） | **S** | https://chromium.googlesource.com/chromium/src/+/744d24753ecd56e1c49d0da52cc5f5e5a4f3aeb4%5E%21/gpu/config/gpu_driver_bug_list.json |
| 5 | Android GPU Inspector（官方） | **S** | https://developer.android.com/agi/start |
| 6 | Flutter Impeller issue #190195（Mali/MTK 崩溃、opt-out 已废弃） | **A** | https://github.com/flutter/flutter/issues/190195 |
| 7 | Flutter Impeller docs/android.md（OpenGLES/Vulkan 双后端） | **A** | https://dart.googlesource.com/external/github.com/flutter/engine/+show/…/impeller/docs/android.md |
| 8 | SwiftShader（Google 开源软件光栅化器） | **A** | https://www.phoronix.com/news/Google-Opens-SwiftShader |

### ECS
| # | 来源 | 等级 | URL |
|---|---|---|---|
| 9 | Unity DOTS《ECS concepts | Entities》(官方) | **S** | https://docs.unity3d.com/Packages/com.unity.entities@0.5/manual/ecs_core.html |
| 10 | Overwatch GDC17 EntityComponent 架构 | **A** | https://zhuanlan.zhihu.com/p/27555356 |
| 11 | EnTT（现代 C++ ECS 库，官方） | **S** | https://github.com/skypjack/entt |
| 12 | Flecs（C/C++ ECS 库，官方） | **S** | https://github.com/SanderMertens/flecs |
| 13 | Bevy ECS（Rust 数据导向，官方） | **S** | https://docs.rs/bevy_ecs/0.9.0 |
| 14 | Andrew Kelley《Practical Data-Oriented Design》 | **A** | https://alessandrominali.github.io/data_oriented_design_canonical_example.html |
| 15 | Habr：archetypes 存储（Unity DOTS/Flecs/Bevy 两分支） | **B** | https://habr.com/ru/hubs/gamedev/ |

### iOS
| # | 来源 | 等级 | URL |
|---|---|---|---|
| 16 | JetBrains《Compose Multiplatform 1.8.0 发布：iOS 稳定且生产可用》 | **S** | https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/ |
| 17 | Swift C++ interoperability（Swift Forums） | **A** | https://forums.swift.org/t/refine-swift-api-for-c/83856/4 |
| 18 | Kotlin Multiplatform《Ktor + SQLDelight》 | **S** | https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-ktor-sqldelight.html |
| 19 | Khronos《MoltenVK 1.3 发布》(Vulkan on Metal) | **S** | https://www.khronos.org/news/permalink/moltenvk-1.3-released-for-vulkan-1.3-support-on-apple-devices |
| 20 | Apple《Metal C++ (Metal-cpp)》 | **S** | https://developer.apple.com/documentation/metal/metal_cpp |

> C 级（NGA 原神 Vulkan 帖等）不计配额，仅作补充观察：国内头部已实测在部分高端机型开 Vulkan。

## 附：最终结论
- 问题确凿：主流中国设备走 CPU Canvas，性能/电量是最大风险。
- **推荐 = 方向 C**：以 Unity/Chromium 式**量化阈值**扩 GPU 白名单（厂商 + Vulkan API 版本 + 驱动版本），辅以**驱动版本黑名单 + 崩溃自愈**兜底风险；同时**优化软件渲染**让兜底路径可用；**渐进式降级**而非启动即软件。
