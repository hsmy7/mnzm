# FPS 帧率提升方案：全面性能优化计划

> 生成日期：2026-07-07 | 来源数量：25+ | 置信度：高

---

## 一、执行摘要

本方案针对《修仙宗门》放置游戏的帧率/性能瓶颈，基于 2025-2026 年头部手游（原神、星铁、王者荣耀、恋与深空等）的最新优化实践，提出**从渲染管线 → 游戏循环 → 内存管理 → 资源分级 → 线程调度**五个维度的系统性优化方案。

**核心目标：**
| 设备档次 | 当前目标 | 优化后目标 |
|---------|---------|-----------|
| 高端机（≥8核/6GB） | 稳定 60fps | 稳定 60fps，波动≤±2fps |
| 中端机（≥6核/4GB） | 30-45fps 波动 | 稳定 45fps |
| 低端机（≤4核/3GB） | 20-30fps 波动 | 稳定 30fps，波动≤±1fps |

**核心思路：** 不追求暴力提帧，而是**稳定帧率优先于高帧率**、**动态适配优先于一刀切**、**数据和 Profiler 驱动优化优先于拍脑袋修修补补**。

---

## 二、当前项目性能状态分析

### 2.1 现有的性能基础设施（值得肯定的架构设计）

| 组件 | 功能 | 状态 |
|------|------|------|
| `UnifiedPerformanceMonitor` | 帧率/帧时间/GC/内存监控 | ✅ 已实现 |
| `ThermalController` | 热控降级 + 低帧率自动降并行度 | ✅ 已实现 |
| `DeviceCapabilityProfiler` | 设备分级（核数/内存）+ 推荐并行度 | ✅ 已实现 |
| `SettlementScheduler` | 帧预算驱动（激进 12ms / 保守 1.5ms） | ✅ 已实现 |
| `FrameQuality` 追踪 | SMOOTH/ACCEPTABLE/JANKY/FREEZE 四级 | ✅ 已实现 |
| `loadReductionRequested` | 连续 3 帧 jank 触发降级请求 | ✅ 已实现 |
| `frame-driven accumulator loop` | 帧驱动累加器模式 + deltaTime | ✅ 已实现 |
| 弹性批量间隔 5-10s | Tab 切换加速 + 稳定态恢复 | ✅ 已实现 |
| `CompositingStrategy` 支持 | `Offscreen` / `ModulateAlpha` / `Auto` | ⚠️ 未系统化使用 |
| `parallelDispatcher` 并行结算 | 弟子 50+ 自动启用 | ✅ 已实现 |

### 2.2 当前存在的瓶颈（基于代码分析）

| # | 瓶颈 | 代码位置 | 影响 |
|---|------|---------|------|
| 1 | `_fps` 仅在引擎侧计算，UI 未展示 | `GameEngineCore.kt:231` | 难以实时调试帧率 |
| 2 | 引擎循环 `delay(50)` 固定睡眠 | `GameEngineCore.kt:347` | 空闲时功耗浪费，繁忙时延迟响应 |
| 3 | Canvas 地图渲染复用 `SoftwareCanvasBackend`，无 LOD 分级 | `SoftwareCanvasBackend.kt` | 放大/缩小效率相同 |
| 4 | `CompositingStrategy` 未统一使用，BlendMode 可能触发隐式离屏 | 各 UI 组件 | 不必要的离屏缓冲开销 |
| 5 | 热控降级仅控制并行度，未联动渲染质量 | `ThermalController.kt` | 帧率崩了才降级，不够主动 |
| 6 | `batchIntervalMs` 最低 5s，无 AI 预测 | `GameEngineCore.kt:184` | 批量结算可能撞帧 |
| 7 | 无 Compose `strongSkippingMode` | `build.gradle.kt` | 不必要的重组浪费 |
| 8 | 无 Baseline Profile | `app/` | 首次启动/AOT 编译优化缺失 |
| 9 | 渲染线程仅有一个 `targetFps` 限速 | `NativeSurfaceView.kt` | 无 GPU 帧节奏对齐 |

---

## 三、行业对标数据

### 3.1 UWA《2024-2025 Unity手游性能蓝皮书》核心数据

| 设备档次 | 平均帧率 | ≥30帧达标率 | 60帧占比 | 代表机型 |
|---------|---------|-----------|---------|---------|
| 顶级 | ~40帧 | 64% | 4% | 小米14/13, Mate 60 Pro |
| 高端 | ~32帧 | 53% | 2% | 红米K40, P40 Pro |
| 中端 | ~29帧 | 40% | 0% | 小米8 SE, 红米Note9 |
| **中低端** | **~24帧** | **25%** | **0%** | OPPO R17 |

**关键发现：**
- **38%** 的项目帧率在 20-30 帧之间
- 中低端设备 **75% 未达到 30 帧**、**33% 未达到 20 帧**
- 顶级设备从 37→40 帧有改善，但中低端仍卡在 24 帧
- **Jank 卡顿约 6 次/分钟**
- **52% 项目每 300-600 帧触发 GC**，平均耗时 8ms/次
- **24% 项目 Mono 堆内存超过 200MB**

> 来源：[UWA《2024-2025年度Unity手游性能蓝皮书》](https://blog.uwa4d.com/archives/BlueBook_2025.html)

### 3.2 头部手游优化对标

| 游戏 | 优化策略 | 可借鉴点 |
|------|---------|---------|
| **原神** | 设备分级画质 + 动态分辨率缩放 + 场景分块加载 | 中低端机锁定 30fps + 降分辨率到 0.8x |
| **王者荣耀** | 多线程渲染 + 高帧率模式 + 低配屏蔽特效 | 低端机关闭法线贴图/阴影/粒子 |
| **恋与深空** | GPU Driven Rendering + PSO 优化 + Bindless | DrawCall CPU 耗时从 34.79ms→11.8ms（降至 1/3） |
| **星穹铁道** | Unity URP 管线定制 + 烘焙光照 + 远景 LOD | 按距离降级渲染精度 |
| **剑与远征** | Alibaba DCDN 动态加速 + 资源预加载 | 使用精灵图集 + 离屏缓存背景 |

> 来源：[叠纸游戏 Unite 2025 分享](http://www.gamelook.com.cn/2025/11/580940/)、[Android Developers Blog](https://android-developers.googleblog.com/2025/03/building-excellent-games-with-better-graphics-and-performance.html)

---

## 四、优化方案：五大维度

---

### 维度 1：渲染管线优化（最高收益）

#### 1.1 Canvas 地图渲染分层 LOD

**现状：** `SoftwareCanvasBackend` 对所有地块同等精度渲染，无远近分级。

**方案：**
```
根据相机缩放倍数/视口范围分层：
- Layer 0（1x~2x）: 完整精度，绘制建筑+装饰+地面纹理
- Layer 1（2x~4x）: 简化装饰，合并地面色块，建筑保持细节
- Layer 2（4x+）: 仅绘制建筑主体+地面色块，跳过装饰
```

**实测预期：** 远距离缩放时渲染量减少 40%-60%。

#### 1.2 Canvas 离屏缓冲系统化

**现状：** 部分 UI 组件使用 `BlendMode` 可能触发隐式离屏。

**方案：**
- 静态 UI 背景层 → `Modifier.drawBehind {}` + `CompositingStrategy.ModulateAlpha`
- 动态地图层 → 预绘制到 `ImageBitmap`，每帧仅 `drawImage`（参考 Flappy Bird Compose 方案）
- 静态装饰层 → 离屏缓存到 Bitmap，每帧快速重绘

**关键实现：**
```kotlin
// 后台线程预绘制地图背景
val mapBitmap = remember { Bitmap.createBitmap(w, h, Config.ARGB_8888) }
val mapCanvas = remember { android.graphics.Canvas(mapBitmap) }

LaunchedEffect(mapConfig) {
    withContext(Dispatchers.Default) {
        // 一次性绘制静态层
        drawTerrainLayer(mapCanvas)
        drawDecorationLayer(mapCanvas)
    }
}

// 每帧仅绘制动态元素 + 快速贴背景
Canvas(modifier) {
    drawImage(mapBitmap.asImageBitmap())  // 背景用缓存
    drawDynamicElements()                 // 动态层实时绘制
}
```

#### 1.3 Compose Strong Skipping Mode + 稳定性注解

**现状：** 未启用 `strongSkippingMode`，游戏状态 data class 可能被标记为 Unstable。

**方案：**
```kotlin
// build.gradle.kts
composeCompiler {
    strongSkippingMode = true
}
```

对所有游戏状态 data class 添加 `@Immutable`：
```kotlin
@Immutable
data class GameViewState(
    val fps: Float,
    val tickCount: Long,
    val disciples: ImmutableList<DiscipleSummary>,
    // ...
)
```

#### 1.4 Vulkan 渲染路径优化

**现状：** 已有 Vulkan + Software 双后端，但 Vulkan 端未做专业优化。

**方案：**
- 使用 `VK_GOOGLE_display_timing` 对齐帧节奏（替代固定 sleep）
- 使用 `ANDROID_FRAMEBUFFER_RESOLUTION` 动态降分辨率（发热时 1.0→0.8→0.6）
- 减少 Pipeline State Object 切换（按渲染批次排序）
- 合并 DescriptorSet 设置（参考叠纸《恋与深空》：DrawCall CPU 降至 1/3）

#### 1.5 Sprite 图集 + 纹理压缩

**现状：** 精灵图通过 `SpriteResRegistry` 注册，格式可能未统一优化。

**方案：**
- 所有精灵图打包为 **Sprite Atlas**（至少 2048x2048 图集）
- 按设备分级加载纹理压缩：
  - 高端：ASTC 6x6
  - 中端：ETC2 或 ASTC 8x8
  - 低端：ETC1 + Alpha 拆图
- WebP 纹理全部检查是否为 `lossless: true, effort: 6`

---

### 维度 2：游戏循环帧率控制（中等收益）

#### 2.1 自适应帧率切换

**现状：** 引擎循环固定 `delay(50)` + 帧驱动累加器模式。

**方案：**
```kotlin
// 按场景动态切换目标帧率
enum class TargetFrameRate(val fps: Int, val frameBudgetMs: Long) {
    IDLE(10, 100),     // 后台/无操作
    MAP_SCROLL(30, 33),// 地图滚动静止
    GAMEPLAY(60, 16),  // 正常游戏
    BATTLE(60, 16)     // 战斗场景
}
```

| 场景 | 当前 | 优化后 | 省电 |
|------|------|--------|------|
| 后台/息屏 | 50ms 循环 | **100ms (10fps)** | 省电 50%+ |
| 地图静止 | 50ms 循环 | **33ms (30fps)** | 省电 40% |
| 游戏操作 | 50ms 循环 | **16ms (60fps)** | — |
| 战斗动画 | 50ms 循环 | **16ms (60fps)** | — |

#### 2.2 Android Frame Pacing 集成

**现状：** 无 Swappy/Frame Pacing，全靠手动 delay。

**方案：**
- 集成 AGDK Swappy 库，使用 `SwappyGL_setSwapInterval()` 自动对齐 VSYNC
- 消除 `buffer stuffing` 导致的帧时间不一致（Android 官方推荐首选方案）
- 利用 `Choreographer` 获取精确 VSYNC 时间代替 `System.nanoTime()`
- 对高刷屏（90/120Hz）自动选择最合适的 swap interval

**预期效果：** 帧时间波动从 ±8ms 降至 ±2ms。

#### 2.3 帧预算执行监控 + 分帧

**现状：** `SettlementScheduler` 已有 12ms/1.5ms 预算控制。

**方案（增强）：**
- 执行前检测：`unifiedPerformanceMonitor.loadReductionRequested` 为 true 时，将批量结算拆到**后续 3-5 帧**执行
- 加入 `FrameBudgetMonitor`：每帧开始采样，剩余预算不足时推迟非关键更新
- 年度结算（老化/死亡/招募）分 5-8 帧完成，每帧不超过 3ms
- 月度事件分 2-3 帧完成，每帧不超过 5ms

```kotlin
// 已有骨架，增强预算感知
val budgetLeft = getRemainingFrameBudget()  // 当前帧剩余预算
if (budgetLeft < MIN_BUDGET_THRESHOLD_NS) {
    deferRemainingWorkToNextFrame()
    return
}
```

---

### 维度 3：内存与 GC 优化（高收益）

#### 3.1 对象池系统化

**现状：** 项目已使用 `IntPackedArray` 和 `ObjectPool`，但覆盖范围待扩展。

**方案：**
- **Entity 对象池：** 频繁创建/删除的实体（弟子、物品、建筑）使用 `ObjectPool` 复用
- **Bitmap 池：** 地图瓦片 Bitmap 使用 LRU 缓存 + 池化，避免频繁 GC
- **Paint/Path 池：** Canvas 绘制中每帧复用的 `Paint`/`Path`/`RectF` 实例统一池化管理
- **消息池：** 事件总线消息体使用对象池，不允许每帧 new

**预期效果：** GC 触发频率降低 60%，GC 暂停时间减少 50%。

#### 3.2 内存分级预算

**现状：** `DeviceCapabilityProfiler` 已经有设备分级。

**方案（扩展）：**
| 设备等级 | RAM 总预算 | 纹理预算 | Mono 堆预算 |
|---------|-----------|---------|------------|
| 高端 | ≤1.5GB | ≤400MB | ≤128MB |
| 中端 | ≤800MB | ≤200MB | ≤80MB |
| 低端 | ≤400MB | ≤100MB | ≤48MB |

- 超限时自动释放非关键资源（缓存地图、远景纹理、历史日志）
- 使用 `MemoryMonitorProvider` + `DynamicMemoryManager` 主动检测，不等到 OOM

#### 3.3 减少对象分配热路径

**现状：** 需分析，但以下模式常见。

**方案：**
- 游戏循环的 `stateStore.update {}` 中使用 `copy()` 时避免重复创建中间对象
- `IntPackedArray` / `DoublePackedArray` 已零装箱，确保不会被包装类型污染
- Canvas 绘制回调中使用 `drawBehind {}` 而非 `Canvas(){}`，避免 Composition 开销

---

### 维度 4：线程调度与并行策略（中等收益）

#### 4.1 ADPF Performance Hint API 集成

**现状：** 使用手动 `Thread.MAX_PRIORITY` 优先级提升，未使用系统提示。

**方案：**
- 集成 Android Dynamic Performance Framework (ADPF)：
  - `PerformanceHintManager.createHintSession()` 指定游戏线程 ID
  - `updateTargetWorkDuration()` 动态设置目标帧时间
  - 系统自动将游戏线程调度到大核 + 提升频率
- 优势：比手动绑核更跨设备兼容，系统自动处理功耗平衡

#### 4.2 热点检测 + 自适应线程数

**现状：** `ThermalController` 仅在温度/帧率超阈值时降为单线程。

**方案（增强）：**
- 加入更细致的降级阶梯：
  - **绿色**（温度 < 40°C）：全并行（4 线程）
  - **黄色**（温度 40-42°C 或帧率 25-30）：降为 2 线程
  - **橙色**（温度 42-45°C 或帧率 20-25）：降为 1 线程 + 关闭后处理
  - **红色**（温度 > 45°C 或帧率 < 20）：单线程 + 锁定 30fps + 关闭粒子特效
- 降温恢复时逐步升档，防止反复跳变

#### 4.3 游戏线程 + 渲染线程分离

**现状：** 游戏逻辑在 `GameEngine-Thread`，渲染在 Compose 主线程 / RenderThread。

**方案：**
- 地图渲染在 `NativeSurfaceView` 的独立渲染线程中执行
- 使用 `lockCanvas()` / `unlockCanvasAndPost()` 的双缓冲机制，不阻塞主线程
- 渲染线程帧率与游戏逻辑线程帧率解耦：渲染 60fps + 逻辑 10Hz

---

### 维度 5：资源加载与编译优化（长期收益）

#### 5.1 Baseline Profile 生成

**现状：** 未使用。

**方案：**
- 使用 Macrobenchmark 库为核心用户路径生成 Baseline Profile：
  1. 启动游戏 → 加载存档 → 进入宗门地图
  2. 遍历全部 Tab（弟子/建筑/仓库/设置）
  3. 打开各功能对话框（炼丹/铸器/灵田等）
  4. 进行 30 秒基础操作
- 预期收益：首次启动提 25-30%，帧时间中位数降低 15%

#### 5.2 R8 完整模式 + ProGuard 规则

**现状：** 已有 ProGuard 配置，但未确认是否启用 R8 完整模式。

**方案：**
```kotlin
// build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

确保 `gradle.properties` 中：
```properties
android.enableR8.fullMode=true
```

#### 5.3 游戏服务懒加载 + 按需初始化

**现状：** 所有 Service/System 在启动时注入。

**方案：**
- 将非核心系统标记为 `@LazyInit`，在首次访问时初始化
- 将 `Hilt` 作用域从 `@Singleton` 调整为首次访问时初始化
- 启动时仅初始化：`GameStateStore` → `GameEngineCore` → `GameEngine`
- 延迟初始化：探索/外交/任务等系统

---

## 五、实施路线图

### Phase 1：快速见效（1-2 天开发）

| 序号 | 任务 | 预期收益 | 风险 |
|------|------|---------|------|
| P1.1 | 启用 Compose `strongSkippingMode` | 减少 5-15% 重组开销 | 需测试兼容性 |
| P1.2 | 对游戏状态 data class 加 `@Immutable` | 减少 10-20% 不必要的重组 | 低 |
| P1.3 | 场景自适应帧率（Idle 10fps/操作 60fps） | 空地省电 50%+ | 低 |
| P1.4 | ThermalController 增加多级降级阶梯 | 发热时帧率更稳定 | 低 |
| P1.5 | 加入 `CancellationException` 防护（已有规范） | 修复协程泄漏 | 低 |
| P1.6 | 热路径 Paint/Path 对象池 | 减少 GC 暂停 | 中 |

### Phase 2：核心优化（3-5 天开发）

| 序号 | 任务 | 预期收益 | 风险 |
|------|------|---------|------|
| P2.1 | Canvas 地图渲染分层 LOD | 远距离渲染量减 40-60% | 中（需重构渲染管线） |
| P2.2 | 静态场景层离屏缓存 | 每帧减少 20-30% Canvas 绘制 | 中 |
| P2.3 | 集成 ADPF Performance Hint API | 系统自动调度线程到大核 | 中（需 API >= 30） |
| P2.4 | 纹理分级压缩（ASTC/ETC2/ETC1） | 每场景减少 30-50% 纹理内存 | 低 |
| P2.5 | SettlementScheduler 增强帧预算感知 | 防止结算单帧超预算 | 中 |

### Phase 3：深度优化（5-7 天开发）

| 序号 | 任务 | 预期收益 | 风险 |
|------|------|---------|------|
| P3.1 | Baseline Profile 生成 + 集成 | 启动提 25%，帧时间降 15% | 低（但需真机生成） |
| P3.2 | R8 完整模式 + 优化混淆规则 | APK 缩小 10-20%，运行性能提升 | 中（需测试 Release） |
| P3.3 | 内存分级预算 + 超限释放 | OOM 降低 80% | 中 |
| P3.4 | 服务懒加载 | 启动速度提升 20% | 中（需 Hilt 重构） |
| P3.5 | 年度结算分 5-8 帧执行 | 消除"年变卡顿" | 中（需改结算逻辑） |

### Phase 4：持续优化（长期）

| 序号 | 任务 | 预期收益 |
|------|------|---------|
| P4.1 | Macrobenchmark CI 集成 | 性能回归自动检测 |
| P4.2 | Unity URP 对比研究（如未来迁移） | 更先进的渲染管线方案 |
| P4.3 | 自动生成 Baseline Profile 的 CI 流程 | 每次发布自动优化 |
| P4.4 | Vulkan 专业调优（叠纸《恋与深空》方案迁移） | CPU DrawCall 开销降至 1/3 |

---

## 六、关键监控指标

优化前后统一用以下指标衡量：

| 指标 | 工具 | 目标值 |
|------|------|--------|
| 平均帧率 (FPS) | `UnifiedPerformanceMonitor` | 高端 60, 中端 45, 低端 30 |
| 帧时间波动 (Jitter) | `FrameMetricsMonitor` | ±2ms 以内 |
| Jank 率 | `FrameMetricsStats.jankRate` | < 3% |
| GC 暂停时间 | `GCOptimizerProvider` | 单次 < 3ms |
| 内存占用 | `MemoryMonitorProvider` | 按预算表 |
| 温度爬升 | `ThermalController` | 1小时 ≤ 42°C |
| Overdraw | Compose Layout Inspector | ≤ 2.5 层 |

---

## 七、参考来源清单

| # | 标题 | 来源 | 类型 | 等级 |
|---|------|------|------|------|
| 1 | [Building excellent games with better graphics and performance](https://android-developers.googleblog.com/2025/03/building-excellent-games-with-better-graphics-and-performance.html) | Google GDC 2025 | 官方博客 | S |
| 2 | [Learn about rendering in game loops](https://developer.android.google.cn/games/develop/gameloops) | Android Developers | 官方文档 | S |
| 3 | [Frame Pacing library](https://developer.android.google.cn/games/sdk/frame-pacing) | Android Developers | 官方文档 | S |
| 4 | [Android Vulkan Profiles](https://android-dot-devsite-v2-prod.appspot.com/ndk/guides/graphics/android-vulkan-profile?hl=zh-tw) | Android Developers | 官方文档 | S |
| 5 | [Unity Manual: CPU and GPU performance control](https://docs.unity3d.com/Manual/adaptive-performance/cpu-gpu-performance-control.html) | Unity Docs | 官方文档 | S |
| 6 | [Optimization for Android](https://docs.unity3d.com/Manual/android-optimization.html) | Unity Docs | 官方文档 | S |
| 7 | [Compose 的阶段和性能](https://android-dot-devsite-v2-prod.appspot.com/develop/ui/compose/performance/phases?hl=zh-cn) | Android Developers | 官方文档 | S |
| 8 | [CompositingStrategy API](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/layer/CompositingStrategy#Offscreen()) | Android Developers | 官方文档 | S |
| 9 | [Unity Profiler eBook (collab with Arm)](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/launching-the-unity-profiler-ebook-in-collaboration-with-arm) | Arm + Unity | 行业报告 | A |
| 10 | [UWA发布 | Unity手游性能年度蓝皮书](https://blog.uwa4d.com/archives/BlueBook_2025.html) | UWA | 行业报告 | A |
| 11 | [《2024-2025年度Unity手游性能蓝皮书》解析](http://mp.weixin.qq.com/s?__biz=MzUzOTYyNDk4OQ==&mid=2247483766&idx=1&sn=83956b3b9b889809b81c470e58744103) | UWA | 行业报告 | A |
| 12 | [叠纸游戏《恋与深空》影视级渲染管线](http://www.gamelook.com.cn/2025/11/580940/) | 游戏客栈（Unite大会分享） | 技术分享 | A |
| 13 | [深入理解 MTK FPSGO：Android 游戏帧率治理框架](https://blog.csdn.net/qq_38801607/article/details/160210392) | CSDN | 技术文章 | A |
| 14 | [Optimizing Performance for Android XR with Unity](https://android-developers.googleblog.com/2025/10/optimizing-performance-for-android-xr.html) | Android Developers Blog | 官方博客 | S |
| 15 | [Android ADPF and QAPE](https://docs.qualcomm.com/doc/80-PK177-134/topic/google_adpf_and_qape.html) | Qualcomm | 厂商文档 | A |
| 16 | [手游性能优化实战：从帧率稳定到内存可控的全链路方案](https://cloud.tencent.cn/developer/article/2661258) | 腾讯云 | 技术文章 | A |
| 17 | [手游客户端性能优化实战：从渲染、内存到卡顿治理](https://cloud.tencent.cn/developer/article/2661903) | 腾讯云 | 技术文章 | A |
| 18 | [Android Compose 离屏缓冲](https://blog.csdn.net/EthanCo/article/details/161332683) | CSDN | 技术文章 | B |
| 19 | [Unity移动平台优化全攻略](https://blog.csdn.net/qq_52190890/article/details/148433598) | CSDN | 技术文章 | B |
| 20 | [Unity移动端性能优化实战：从贴图压缩到GPU Instancing](https://blog.csdn.net/carrot/article/details/154782041) | CSDN | 技术文章 | B |
| 21 | [低端机硬件适配的非表层方案](https://developer.aliyun.com/article/1691228) | 阿里云 | 技术文章 | A |
| 22 | [《风格锚点+动态适配：Unity跨设备渲染的核心逻辑》](https://developer.aliyun.com/article/1687039) | 阿里云 | 技术文章 | A |
| 23 | [UWA Gears：轻量游戏跨设备性能与优化思路](https://blog.uwa4d.com/archives/UWA_Gears30.html) | UWA | 技术文章 | A |
| 24 | [TextureManager（团结引擎）](https://docs.unity.cn/cn/tuanjiemanual/1.7/Manual/TextureManager.html) | Unity China | 官方文档 | S |
| 25 | [Boosting Android Performance with Baseline Profiles](https://www.strv.com/blog/boosting-android-performance-with-baseline-profiles) | STRV | 技术文章 | A |
| 26 | [We Cut Android Startup Time by 30% with Baseline Profiles](https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles/) | Duolingo | 技术文章 | A |
| 27 | [Launching the Unity Profiler eBook – in collaboration with Arm](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/launching-the-unity-profiler-ebook-in-collaboration-with-arm) | Arm | 行业报告 | S |
| 28 | [Android 图形渲染优化实战：从 Surface 到 Choreographer](https://blog.csdn.net/xxx12/article/details/149534059) | CSDN | 技术文章 | B |
| 29 | [Jetpack Compose Performance – System Trace, Recomposer, and the Truth About Frames](https://dev.to/vio_di_code/jetpack-compose-performance-system-trace-recomposer-and-the-truth-about-frames-4b5f) | Dev.to | 技术文章 | A |
| 30 | [掌握 Jetpack Compose 稳定性：优化应用性能的全面指南](https://blog.csdn.net/vitaviva/article/details/149861280) | CSDN | 技术文章 | B |
| 31 | [《低端机硬件适配的非表层方案》](https://developer.unity.cn/projects/693041f1edbc2a0023c185aa) | Unity China | 官方技术分享 | A |
| 32 | [Google 宣布 Android 全面拥抱 Vulkan](https://m.ithome.com/html/837747.htm) | IT之家 | 新闻 | B |

---

## 八、方案特性一览

| 特性 | 实施难度 | 性能收益 | 代码改动量 | 适用性 |
|------|---------|---------|-----------|--------|
| Compose Strong Skipping | ★☆☆☆☆ | 中 | 极小（改 build.gradle） | 全场景 |
| `@Immutable` 注解 | ★☆☆☆☆ | 中 | 小（加注解） | 全场景 |
| 场景自适应帧率 | ★★☆☆☆ | 大 | 中（改 GameEngineCore） | 全场景 |
| Thermal 多级降级 | ★★☆☆☆ | 中 | 小（改配置参数） | 全场景 |
| ObjectPool 扩展 | ★★☆☆☆ | 大 | 中 | 全场景 |
| Canvas LOD 分层 | ★★★☆☆ | 很大 | 大（重构渲染管线） | 地图渲染 |
| 静态层离屏缓存 | ★★★☆☆ | 大 | 中 | 地图渲染 |
| ADPF 集成 | ★★★☆☆ | 中 | 中（新增依赖） | Android 12+ |
| Baseline Profile | ★★★☆☆ | 大 | 中（增测试模块） | 启动+运行时 |
| 纹理分级压缩 | ★★☆☆☆ | 大 | 小（改资源配置） | 全场景 |
| 帧预算感知型结算 | ★★★☆☆ | 中 | 中（改 SettlementScheduler） | 结算逻辑 |
| R8 全量模式 | ★☆☆☆☆ | 中 | 极小（改 gradle.properties） | Release |
| 服务懒加载 | ★★★☆☆ | 中 | 大（重构 DI） | 启动 |

---

> **用户确认后开始 Phase 1 实施。** 每个 Phase 实施前后执行：
> 1. `./gradlew.bat compileReleaseKotlin` — 编译检查
> 2. `./gradlew.bat test` — 单元测试
> 3. `./gradlew.bat lintRelease` — 静态分析
> 4. 真机 Profiler 对比帧率数据
