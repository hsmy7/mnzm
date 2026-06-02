# 方案 B（架构级性能优化）评估报告

> 评估日期：2026-06-02 | 评估人：Claude | 来源：~25 次 WebSearch + 3 个 Explore Agent 代码扫描

---

## 一、行业调研背景

本评估基于对以下领域的深度调研：

| 调研方向 | 来源数 | 关键来源 |
|----------|--------|----------|
| 腾讯手游性能优化体系 | 5 | TGPA 技术方案、手游客户端性能优化实战、NanoMesh GDC |
| 米哈游技术分享 | 5 | Unite 2018/2020、GDC 2025、《崩坏3》渲染、《原神》主机版 |
| Compose 重组优化 | 8 | Google 官方、ProAndroidDev、CSDN 深度剖析、dev.to |
| Baseline Profile 实战 | 7 | Duolingo、Reddit、NordVPN、Meta、Trello、GetYourGuide、STRV |
| Canvas/图形渲染 | 4 | Google 官方 Compose 图形优化、Kotlinlang Slack（romainguy）、Stack Overflow |
| Android GC 机制 | 3 | ART GC 调试指南、Android 新 GC 分析、Game Developer |
| 游戏架构模式 | 4 | ECS 设计模式、2D 模拟游戏架构、LibGDX 指南 |
| StateFlow/Flow 性能 | 3 | RevenueCat Flow 内幕、StateFlow 正确使用、production 案例 |
| 内存与 low-end 优化 | 2 | Android largeHeap 讨论、低配设备优化 |

---

## 二、行业对标：头部产品的性能标准

### 2.1 腾讯游戏性能分级标准（TGPA / PerfDog）

这是行业最成熟的量化标准体系，覆盖王者荣耀、和平精英、CODM 等 30+ 款产品：

| 指标 | 高端机 (8GB+) | 中端机 (6GB) | 低端机 (4GB) |
|------|-------------|-------------|------------|
| **帧率** | 稳定 60fps | 稳定 30fps | 锁定 30fps |
| **内存** | ≤1.8GB | ≤1.2GB | **≤800MB** |
| **单场景 DrawCall** | ≤2000 | ≤1500 | ≤1000 |
| **1h 温度** | ≤42°C | ≤45°C | ≤48°C |
| **闪退率** | ≤0.1% | ≤0.3% | ≤0.5% |
| **Overdraw** | ≤2.5 层 | ≤3 层 | ≤4 层 |

对照本方案的目标：

| 指标 | 本方案低配目标 | 腾讯低配标准 | 差距分析 |
|------|-------------|-------------|---------|
| 帧时间 P95 | < 20ms | 稳定 30fps (~33ms) | 本方案更严格，合理但激进 |
| 内存 | < 150MB | **≤800MB** | 本方案远低于行业标准——可能是起点不同（本游戏复杂度远低于王者荣耀） |
| GC 停顿 | < 10ms | 未单独设标 | 合理，但应将 GC 停顿纳入帧时间预算 |

**关键发现**：本游戏是一个模拟经营类 2D 游戏，复杂度远低于 3D MMO/MOBA，所以 < 150MB 内存目标是合理的。但方案应补充设备分级表。

### 2.2 米哈游的渲染优化策略（Unite 2018/2020）

虽然米哈游使用 Unity 3D 渲染管线，但几个通用原则适用于本 2D 项目：

| 米哈游手段 | 本方案对应 | 是否已有 |
|-----------|-----------|---------|
| **分帧渲染**（Bloom 等后处理分帧执行） | Canvas 离屏缓冲分层 | ✅ 模块 4 |
| **对象池复用**（特效、子弹） | 模块 3 对象池 | ✅ 部分有 |
| **1/3 分辨率 Planar Reflection** | 低配关闭离屏缓冲 | ✅ 方案提到 |
| **Texture Atlas 合并** | 建筑 Bitmap 预渲染 | ✅ 已有（GameActivity 地形预渲染） |
| **Shader 变体剥离**（中低端关闭全局实时光照） | 未涉及 | N/A (2D 无 Shader) |

**关键发现**：米哈游的核心思路是"能不渲染就不渲染、能分帧就分帧"——这与方案模块 4 的离屏缓冲策略一致。

### 2.3 Baseline Profile 行业数据

七大公开案例的真实数据：

| 产品 | 冷启动改善 | 其他收益 |
|------|----------|---------|
| **Reddit** | **51%（中位数）** | 冻结帧 -36%，ANR -30% |
| **Duolingo** | **~30%** | JIT 线程占用 25%→3% |
| **Meta** | **最高 40%** | 滚动/导航/图片渲染全面改善 |
| **NordVPN** | **24%** | 登录流程快 60% |
| **Trello** | **25%** | 主流程改善 |
| **GetYourGuide** | **>20%** | 冻结帧 -60% |
| **STRV** | **2.4%**（TTID） | frameDurationCpuMs P50 -46% |

**结论**：方案声称的 "冷启动快 30%" 目标完全可行。但需要注意：
1. Baseline Profile 需要**真机生成**（方案已识别此限制）
2. 效果在**低端设备**上更显著（Reddit 在低端设备上改善达 40%）
3. **运行时收益**（帧时间、JIT 线程）往往比冷启动改善更大

### 2.4 Compose 稳定性系统：Kotlin 2.0+ 的重大变化

搜索确认了一个**方案未提及的关键信息**：

> **Strong Skipping Mode**（Compose Compiler 2.0+，Kotlin 2.0 默认启用）中，**不稳定参数不再污染整个 Composable**。运行时会比较所有参数（使用 `equals()`），无论稳定性标记。

这意味着：
- 方案模块 5 的 `SectInfoCard` 细粒度参数化，在 Kotlin 2.0 + Strong Skipping 下**效果可能不如预期大**
- 已有的 `@Immutable`/`@Stable` 的边际收益降低
- 真正有价值的是 `derivedStateOf` 的延迟 `.value` 读取——防止父级重组

**修正建议**：方案应将优化重心从"参数细粒度化"转向"**延迟 State 读取到最深 Composable**"。

---

## 三、与代码库现状的核验（3 个 Explore Agent 共发现）

### 3.1 ❌ tick 频率假设错误

| 来源 | 声称 | 实际 |
|------|------|------|
| CLAUDE.md | 200ms tick | — |
| 方案文档 | "每 tick 创建新 UnifiedGameState" | — |
| `GameEngineCore.kt:60` | — | **`TICK_INTERVAL_MS = 1000L`** |
| `GameEngineCore.kt:65` | — | 自适应上限 `2000ms` |
| Changelog v3.1.76 | — | "tick frequency reduction" 确认了频率下调 |

**影响分析**：
- 方案声称"重组次数/秒 ~25-100"——但 1 秒 1 tick 不可能产生 25+ 次 tick 驱动的重组
- 每个 tick 的 `.map{}` 链开销从"每秒 5 次"降为"每秒 1 次"，优化紧迫性大幅降低
- 脏标记系统（模块 2）的投入产出比需要重新计算——保存 1 次 `.map{}` / 秒，维护 12 个 `@Volatile` 字段

### 3.2 ❌ `android:largeHeap` 已存在且不应推荐

- `AndroidManifest.xml:49` 已设置 `android:largeHeap="true"`
- 行业调研明确反对低配设备使用：GC 停顿更长、LMK 优先杀掉、Google 官方建议避免
- 低配 4GB 设备上 `largeHeap` 甚至**不保证增加堆上限**

**修正**：删除此优化项。替代方案是在 `DynamicMemoryManager`（已存在）中实现设备分级内存预算（参考腾讯标准）。

### 3.3 ⚠️ Canvas 现状与方案定位偏差

| 方案假设 | 实际情况 |
|----------|---------|
| Path 分配在 SectGroundCanvas | `SectGroundCanvas` **零 Path 分配**（只用 drawImage/drawRect/drawLine/drawCircle） |
| 需要 Path 缓存 | 真正需要缓存的是 **世界地图 `MapCanvas`**（120-200 Path/帧） |
| 地形每帧绘制 | **已有完整 Bitmap 预渲染**（`GameActivity:184-214`），`SectGroundCanvas` 只调 1 次 `drawImage` |

### 3.4 ⚠️ `derivedStateOf` Bug 已确认

`MainGameScreen.kt:137`：
```kotlin
val aliveDisciples = remember(disciples) {  // ← key 每次都是新引用
    derivedStateOf { disciples.filter { it.isAlive } }
}
```
此 Bug 导致 `derivedStateOf` 对象每次重组都被重建。

但需要注意：去 key 后的 `derivedStateOf` 能否正常工作，取决于 `disciples` 是否为 Compose `State` 对象。Explore Agent 报告显示 `aliveDisciples` 在 ViewModel 中已被定义为 StateFlow，Composable 中读取的是 `collectAsState()` 的结果（Compose State）。

### 3.5 ⚠️ 代码库已有大量性能优化

从 Explore Agent 扫描结果和 Changelog 历史来看：

| 已有优化 | 版本 | 说明 |
|----------|------|------|
| `reusableMutableState` 单例复用 | 早期 | 每次 `update()` 避免分配新 `MutableGameState` |
| 地形完整 Bitmap 预渲染 | 早期 | `GameActivity` Android Canvas 管线 |
| 自适应 tick 间隔 | v3.1.52 | 连续超时 1.5x 扩大，正常后 0.8x 缩小 |
| StateFlow 优化（移除冗余 stateIn） | v3.1.70 | ViewModel 19 个 passthrough Flow 改为 get() 委托 |
| 移除 replayExpirationMillis | v3.1.72 | 修后台白屏问题 |
| tick 频率下调 | v3.1.76 | 200ms → 1000ms |
| `@Immutable` 11 个核心类 | v3.1.80 | GameData, DiscipleAggregate, GridBuildingData 等 |
| `derivedStateOf` for alive disciples | v3.1.87 | 减少存活弟子计算 |
| Background task scheduler 精简 | v3.1.81 | 13 个协程 → 4 个 |
| ProtoBuf `encodeDefaults=false` | v3.1.65 | 序列化体积优化 |
| `@Stable` on MapCameraState | — | 避免相机状态触发不必要重组 |

**关键发现**：代码库已经历了多轮优化，低垂的果实可能已被摘走。方案应明确标注哪些优化项已有基础、哪些是新增。

---

## 四、逐模块深度评估（补充行业数据后）

### 模块 1：状态分层

| 维度 | 评估 |
|------|------|
| 行业对标 | ✅ ECS 天然分层（Unity DOTS），MVVM 通过 LiveData/StateFlow 分层（王者荣耀），方向正确 |
| 实际收益 | ⚠️ 1 秒 1 tick 场景下，15 个 `.map{}` 的开销可能远小于估计。建议先 Profile 确认 |
| 风险 | ❌ `combine` 派生 `unifiedState` 的新对象引用会破坏下游 `===` 身份检查 |

**建议**：降低优先级到 Phase 3（等 Phase 1-2 完成后评估效果再决定）。

### 模块 2：脏标记系统

| 维度 | 评估 |
|------|------|
| 行业对标 | ✅ 类似 Unity ECS Archetype Chunk 变更追踪 |
| 实际收益 | ❌ 1 秒 1 tick 场景下，维护 12 个 `@Volatile` 字段的开销可能 > 1 次 `.map{}` 重算 |
| 复杂度 | ⚠️ 与现有 `transactionMutex` + `currentTransactionState` 的交互需要仔细设计 |

**建议**：**暂缓**。先测实际 `.map{}` 链耗时，再决定是否需要。

### 模块 3：对象池

| 维度 | 评估 |
|------|------|
| 行业对标 | ✅ 腾讯标准做法：特效、怪物、子弹全部池化。Unity ObjectPool API |
| 现状 | ✅ `reusableMutableState` 已有；❌ Path 缓存在错的文件 |
| 修正 | Path 缓存应针对 `MapCanvas`（世界地图 120-200 Path/帧），不是 `SectGroundCanvas` |

**建议**：`DiscipleAggregate` 缓存先测必要性；Path 缓存调整为 `MapCanvas`。

### 模块 4：Canvas 离屏缓冲

| 维度 | 评估 |
|------|------|
| 行业对标 | ✅ 2D 游戏标准做法（Terraria/星露谷瓦片→单张 Bitmap） |
| 现状 | ✅ 已有地形 Bitmap 预渲染，方案应明确扩展现有机制 |
| 补充建议 | **使用 `graphicsLayer(CompositingStrategy.Offscreen)`**——Google 官方推荐，比手动 Bitmap 管理更简单 |
| 内存 | ⚠️ ARGB_8888 36MB/层 → 建议评估 `RGB_565`（18MB/层）|

**建议**：在现有 `fullMapBmp` 基础上 bake 建筑静态层。补充 `graphicsLayer(Offscreen)` 作为替代/补充方案。

### 模块 5：Compose 重组优化

| 维度 | 评估 |
|------|------|
| Strong Skipping | ⚠️ Kotlin 2.0+ Strong Skipping Mode 使得参数细粒度化的边际收益降低 |
| 真正高收益的 | **延迟 `.value` 读取**到最深 Composable（行业共识 #1 优化）|
| 缺失 | `graphicsLayer` 处理地图平移/缩放动画（零重组）|
| 缺失 | `collectAsStateWithLifecycle()`——全代码库 0 处使用 |

**建议**：
1. `derivedStateOf` key 修正 ✅ 立即修
2. 参数细粒度化 ⚠️ 延后（Strong Skipping 下收益降低）
3. 补充 `graphicsLayer` 和 `collectAsStateWithLifecycle`

### 模块 6：构建优化

| 优化项 | 评估 |
|--------|------|
| Compose Compiler 对齐 | ✅ **强烈推荐立即执行**——移除 `kotlinCompilerExtensionVersion = '1.5.8'`，启用 Kotlin 2.0 原生 Compose 编译器 |
| Baseline Profile | ✅ **强烈推荐**——行业 7 个案例均 20-51% 冷启动改善 |
| R8 规则 | ⚠️ 现有规则已较完善，Compose 专用规则收益有限 |
| largeHeap | ❌ **删除此项**——已存在且对低配设备有害 |

### 模块 7：GCOptimizer 改造

| 维度 | 评估 |
|------|------|
| 方向 | ✅ `System.gc()` 在 ART 上行为不确定，减少调用正确 |
| 行业对标 | ✅ 腾讯做法：对象池 + 逻辑分帧 + TGPA 大小核绑定，而非依赖 `System.gc()` |
| 现状 | ⚠️ `CacheLayer`、`ObjectPoolRegistry` 在代码库中不存在，需从零构建 |

---

## 五、方案缺失的关键优化项

根据行业对标，以下为应当补充的优化：

| 缺失项 | 重要性 | 腾讯/米哈游对标 | 说明 |
|--------|--------|----------------|------|
| `graphicsLayer(Offscreen)` | 🔴 高 | Google 官方推荐 | 比手动 Bitmap 缓存更简单，GPU 纹理层缓存 |
| `snapshotFlow` for 高频状态 | 🔴 高 | 王者荣耀：高频状态走 Channel | 修炼进度条等逐帧动画不应触发 `collectAsState` |
| **设备分级目标** | 🔴 高 | 腾讯 4 级设备标准 | 方案只给了"低配 < 150MB"，应补充完整分级表 |
| `collectAsStateWithLifecycle()` | 🟡 中 | Google 官方最佳实践 | 139 个 `collectAsState` 无生命周期感知 |
| `FrameMetricsAggregator` | 🟡 中 | 腾讯 PerfDog 标准 | 需要三阶段帧时间数据（重组/布局/绘制） |
| `graphicsLayer` for 动画 | 🟡 中 | Compose 官方：动画用 graphicsLayer | 地图平移/缩放、进度条动画零重组 |
| Compose Compiler Metrics | 🟢 低 | Google 官方工具 | 生成 `*-composables.txt` 找出非 skippable Composable |

---

## 六、修正后的优先级排序

### Phase 1：立即执行（0 风险，确定性收益）

| # | 任务 | 说明 |
|---|------|------|
| 1 | 移除 `kotlinCompilerExtensionVersion = '1.5.8'` | 消除编译器冲突，启用 Strong Skipping Mode |
| 2 | 修正 `derivedStateOf` key Bug | `MainGameScreen.kt:137` 去掉 `remember(disciples)` 的 key |
| 3 | 网格线视口裁剪 | 当前裁剪了行列但线跨全图（3072px） |
| 4 | 删除方案中的 `largeHeap` 新增项 | 已存在且不建议低配使用 |

### Phase 2：低风险（需验证）

| # | 任务 | 说明 |
|---|------|------|
| 5 | Baseline Profile 生成 | 行业 7 案例均 20-51% 改善，低端效果更显著 |
| 6 | `MapCanvas` Path 缓存 | 120-200 Path/帧 → 缓存复用（注意是**世界地图**） |
| 7 | `collectAsStateWithLifecycle()` 迁移 | 切后台零收集开销 |
| 8 | GCOptimizer 减少 `System.gc()` | SOFT/HARD 级别移除 |

### Phase 3：中等风险（需 Profiling 确认）

| # | 任务 | 说明 |
|---|------|------|
| 9 | Canvas 建筑静态层 | 扩展现有 `fullMapBmp`，`graphicsLayer(Offscreen)` 优先 |
| 10 | `SectInfoCard` 参数优化 | 但 Strong Skipping 下收益需验证 |
| 11 | Dialog 惰性订阅 | 已有部分实现 |
| 12 | 测量 `.map{}` 链实际耗时 | 决定是否需要模块 1/2 |

### Phase 4：待评估

| # | 任务 | 说明 |
|---|------|------|
| 13 | 状态分层（3 StateFlow） | 等 Phase 1-3 完成后评估效果再决定 |
| 14 | 脏标记系统 | 极大可能不需要（1 秒 1 tick） |

---

## 七、总评

| 维度 | 评分（修正前） | 评分（修正后） | 说明 |
|------|------------|------------|------|
| 问题定位准确度 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 根因链正确，tick 频率假设需修正 |
| 行业对标深度 | ⭐⭐ | ⭐⭐⭐⭐ | 本报告补充了腾讯/米哈游/Baseline Profile 完整对标 |
| 方案可行性 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 修正后可在 4 个 Phase 中逐步推进 |
| 风险意识 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 补充了 Strong Skipping 带来的过度优化风险和 largeHeap 逆共识风险 |
| 实施可操作性 | ⭐⭐⭐ | ⭐⭐⭐⭐ | 重排后的 Phase 划分更清晰，验证方式更具体 |

**总体结论**：方案 B 的架构方向正确，但存在 3 处需要修正的关键问题（tick 频率假设、largeHeap 建议、Canvas 优化定位），以及 4 处行业标准做法缺失（graphicsLayer、snapshotFlow、设备分级、生命周期感知收集）。修正后可作为实施基础。**强烈建议 Phase 1 的 4 项立即执行**。

---

## 附录 A：调研来源清单

1. [腾讯 TGPA：手游性能优化技术方案](https://cloud.tencent.com/developer/article/1969649)
2. [腾讯手游客户端性能优化实战（渲染、内存、卡顿）](https://cloud.tencent.com/developer/article/2661903)
3. [腾讯手游性能优化实战：卡顿+发热+功耗](https://cloud.tencent.com/developer/article/2655874)
4. [腾讯 NanoMesh GDC 2024（Arm 联合）](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/nanomesh-on-mobile)
5. [腾讯手游内存优化全链路方案](https://cloud.tencent.cn/developer/article/2536660)
6. [米哈游 Unite 2018：《崩坏3》高品质卡通渲染（贺甲）](https://www.gameres.com/807189.html)
7. [米哈游 Unity 2020：《原神》主机版渲染技术（弋振中）](https://www.gameres.com/876921.html)
8. [GDC 2025 中国厂商集体亮相报道](https://finance.sina.cn/tech/2025-03-25/detail-ineqvvkq7010514.d.html)
9. [Google: Deeper Performance Considerations (2025)](https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html)
10. [Duolingo: 30% Startup Time Reduction with Baseline Profiles](https://blog.duolingo.com/slashed-android-startup-time-baseline-profiles/)
11. [Reddit: 51% Startup Speed Improvement with Baseline Profiles + R8](https://android-developers.googleblog.com/2024/12/reddit-improved-app-startup-speed-using-baseline-profiles-r8.html)
12. [STRV: Baseline Profile Frame Duration 46% Reduction](https://www.strv.com/blog/boosting-android-performance-with-baseline-profiles)
13. [GetYourGuide: 20%+ Cold Start Improvement, 60% Fewer Frozen Frames](https://hellosagar.hashnode.dev/boosting-app-performance-with-baseline-profile-at-getyourguide)
14. [ProAndroidDev: Recomposition All-in-One (2025)](https://proandroiddev.com/recomposition-all-in-one-5bd1f4aedf8b)
15. [CSDN: Compose 重组机制深度剖析与性能优化 (2025)](https://blog.csdn.net/shang_an_1/article/details/156131241)
16. [CSDN: derivedStateOf 优化高频状态下的 UI 重组](https://blog.csdn.net/qq_26296197/article/details/160252827)
17. [Google: Compose Stability 官方文档](https://developer.android.com/develop/ui/compose/performance/stability)
18. [Compose Stability Contracts, Strong Skipping Mode](https://dev.to/software_mvp-factory/compose-stability-contracts-strong-skipping-mode-and-non-restartable-functions-4em6)
19. [RevenueCat: Understanding StateFlow Internals](https://www.revenuecat.com/blog/engineering/flow-internals/)
20. [Google: Optimizing Bitmap Images in Compose](https://developer.android.google.cn/develop/ui/compose/graphics/images/optimization)
21. [Google: Debug ART Garbage Collection](https://source.android.google.cn/docs/core/runtime/gc-debug)
22. [Android's New GC Analysis (2025)](https://www.javacodegeeks.com/2025/04/androids-new-gc-will-it-finally-beat-ios-in-memory-management.html)
23. [LibGDX Architecture Design Guidelines](https://stackoverflow.com/questions/53136080/libgdx-architecture-design-guidelines/69131759)
24. [Game Developer: Boosting FPS — GC Part 1](https://www.gamedeveloper.com/programming/boosting-the-fps-gc-part-1-)
25. [Stack Overflow: Downsides of android:largeHeap](https://stackoverflow.com/questions/26189619/what-are-the-downsides-of-using-androidlargeheap-true)
