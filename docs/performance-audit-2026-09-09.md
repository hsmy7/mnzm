# Android 性能与稳定性全面审计报告

- **日期**：2026-09-09
- **范围**：CPU / GPU / 渲染管线（Vulkan·GLES）/ CPU↔GPU / 内存 / 多线程 / 帧率 / 生命周期 / 兼容性 / 启动 / 构建配置
- **性质**：**只审计，未修改任何代码**（按任务书要求，修复需另行确认）
- **证据标准**：全部结论基于本次实读代码，逐条标注 `文件:行号`。静态分析无法定论的项明确标注「需真机 Profiling」。未经代码证实的可能性一律标「潜在风险」而非确定问题。
- **局限**：本报告为纯静态审计，未在真机上运行 profile；所有帧耗时/占比类判断均标注置信级别。

---

## 1. 当前架构概览（按实际调用关系梳理）

### 1.1 两条主链路

**逻辑链**（固定步长 100ms）：

```
GameActivity/GameForegroundService (android/app, android/core/util)
  → GameEngineCore.startGameLoop（core/engine/GameEngineCore.kt:796 gameLoopMainLoop）
      · GameEngine-Thread：非守护 + MAX_PRIORITY（:408-415），URGENT_DISPLAY nice -8（:804）
      · 固定步长 accumulator：LOGIC_DT_NS=100ms，单帧最多 5 步（:381-382, :879）
      · AUTHORITATIVE 模式分流到 C++ EngineLoop（:853-855，cpp/gamecore/system/engine_loop.h）
      · ADPF Performance Hint Session（:820, :897）
      · 看门狗 GameEngine-Watchdog 非守护线程（:421-428），tick 停滞→指数退避→紧急换线程
  → C++ game-core（native-game-core.so，纯 C++，JobSystem 并行旬结算）
```

**渲染链**（每帧）：

```
Compose（SectMapViewport 帧率门控写 RenderFrame 快照）
  → NativeSurfaceView.updateRenderState（feature/game/NativeSurfaceView.kt:567-601，
     引用不变零拷贝，:578-594）
  → RenderThread "NativeRenderer"（NativeSurfaceView.kt:1165）
      renderLoop（:1242）→ 帧 pacing computeFramePacing（:1401，vsync 节拍×FrameDropPolicy）
      → 脏帧跳过 FrameSkipPolicy（:1326-1346，8 信号）
      → renderTick（:1459）→ activeBackend.renderFrame
          ├─ VulkanRenderBackend.renderFrame（VulkanRenderBackend.kt:71-168）
          │    beginFrame → drawSky → setFadeAlpha → drawIslandEdges → drawAllTiles
          │    → 高亮/预览/网格 drawRect → submitFrame
          ├─ GlesRenderBackend（同一 JNI 链，C++ 侧为 GlesBackend）
          └─ SoftwareRenderBackend（SoftwareCanvasBackend，CPU Canvas，兜底）
  → JNI（cpp/NativeBridge.cpp，全局 g_renderer:46）
  → VulkanBackend（VulkanBackend.cpp，2745 行）/ GlesBackend（GlesBackend.cpp，507 行）
  → GPU（vkQueueSubmit+vkQueuePresentKHR FIFO / eglSwapBuffers）
```

### 1.2 任务书模块清单逐项对应（实际代码确认）

| 任务书模块 | 实际对应 | 位置 |
|---|---|---|
| 游戏主循环 | `GameEngineCore.gameLoopMainLoop`（Kotlin 回退路径）/ C++ `EngineLoop`（authoritative 路径） | GameEngineCore.kt:796；cpp/gamecore/system/engine_loop.h |
| 渲染循环 | `NativeSurfaceView.RenderThread.renderLoop` | NativeSurfaceView.kt:1242 |
| Renderer Manager | RHI 抽象 `Renderer2D` + JNI 生命周期（`g_renderer` + `g_rendererLifecycleMutex`） | cpp/Rhi.h:80；NativeBridge.cpp:46-55 |
| Vulkan Backend | `VulkanBackend` | VulkanBackend.cpp |
| GLES Backend | `GlesBackend`（2026-09 新增 GPU 中间层） | GlesBackend.cpp |
| GPU Device 选择 | `VulkanBackend::selectPhysicalDevice`（纯能力检测，无 C++ 厂商白名单） | VulkanBackend.cpp:434-515 |
| Surface | `AndroidSurfaceProvider`（SurfaceHolder.Callback 翻译 + 纪元防 stale + 10s 超时安全网） | AndroidSurfaceProvider.kt |
| Swapchain | `createSwapchain`（FIFO，minImageCount=3） | VulkanBackend.cpp:593-714 |
| EGL | `GlesBackend::initEgl`（init 末尾释放上下文，渲染线程接管） | GlesBackend.cpp:134-184, :127 |
| Resource Manager | `AtlasAsyncPipeline`（后台拼装）+ `SectAtlasPrefetch`（21.3MB KTX 预取，取用即清） | AtlasAsyncPipeline.kt；SectAtlasPrefetch.kt |
| Texture Manager | `VulkanBackend::m_textures`（vector，id 只增不减）/ `GlesBackend::m_textures` | VulkanBackend.h:217；GlesBackend.h:96 |
| Shader Manager | 构建期 glslc→SPIR-V 嵌入 `shaders.h`；运行时 `loadShaders` + Pipeline Cache 持久化 | VulkanBackend.cpp:1113-1129, :1517-1589 |
| Asset Loader | `SectAtlasAssembler`（逐精灵解码拼装）+ `SectAtlasPrefetch` | AtlasAsyncPipeline.kt |
| Thread Manager | 无统一管理器；各子系统自建线程（见 §6 线程表） | — |
| UI | Compose（GameActivity/MainActivity/MainGameScreen） | app/feature 模块 |
| Map | `NativeBridge.drawAllTiles`（视口钳制）+ `drawIslandEdges`（gamecore island_edge 单一权威预计算） | NativeBridge.cpp:656-1204 |
| NPC | 地图渲染无独立 NPC 绘制系统；NPC/战斗逻辑在 gamecore（AISectAttackManager 等），不上屏 | core/engine/domain/battle |
| Animation | 云层 `CloudLayerAnimator`（渲染线程驱动）、作物 3 阶段插值（C++ 侧 :947-1043）、淡入 FadeTransition | NativeSurfaceView.kt:474-483 |
| Particle / Effects | **不存在粒子系统**（装饰草/树为静态 quad） | — |
| Audio | `AudioPlayerFacade`（SoundPool 8 流 + MediaPlayer，create 已移出主线程） | core/audio |
| Save / Database | Room（GameDB-Query-N / GameDB-Txn 线程池）+ WAL | core/data/GameDatabase.kt |
| Android Native 层 | 双 .so：`native-renderer`（渲染）/ `native-game-core`（逻辑） | cpp/CMakeLists.txt:30, :62 |

### 1.3 回退链（任务书第二章要求的结构）

```
策略层（启动期，GameActivity.onCreate）
  VulkanPolicy.getRenderStrategy（VulkanPolicy.kt:558-585，8 级决策链）
    安全模式/云游戏 → SOFTWARE_ONLY
    模拟器（+先前失败）→ SOFTWARE_ONLY；API<31 非白名单 → GLES_PREFERRED
    持久化失败标记/写前标记残留 → GLES_PREFERRED（★实际不可达，见 P2-1）
    PROBLEMATIC 设备 → GLES_PREFERRED；否则 VULKAN_PREFERRED

运行时（surface 期，NativeSurfaceView）
  VULKAN → initRenderer(VulkanBackend) 失败/异常/10s 超时
         → 先试 GLES（glesTriedAfterVulkan 防循环，:1061-1064, :1125-1128）
         → GLES 再失败 → SoftwareCanvasBackend（:1100-1108）
  软件路径强制渲染缩放 ≤0.5（:448 SOFTWARE_RENDER_SCALE_CAP）
```

---

## 2. CPU 状态：**WARN**

| 检查项 | 结论 | 证据 |
|---|---|---|
| 软件渲染残留（SwiftShader/llvmpipe 等） | **PASS**：无任何软渲库引入；Canvas 软渲仅显式兜底 | 全 cpp 搜索无 SwiftShader/llvmpipe；SoftwareCanvasBackend 仅 SOFTWARE 模式创建 |
| 每帧不必要计算 | 基本健康 | 视口钳制（NativeBridge.cpp:733-736）、稳态帧零拷贝（NativeSurfaceView.kt:578-594）、脏帧跳过（:1326-1346）、静态图缩放 LOD 跳装饰层（NativeBridge.cpp:773-775） |
| 每帧堆分配 | **WARN**：SpriteBatcher 每帧 grow 链（P2-6） | NativeBridge.cpp:695/:1162 栈构造；SpriteBatcher.cpp:68-84 |
| 高复杂度算法 | 一处有界 O(n×m)（P3-3） | 作物帧末裁剪 NativeBridge.cpp:1030-1039（注释自认） |
| 游戏逻辑热点 | 已下沉 C++ 并行化 | 旬结算 JobSystem（phase_settlement.h:1277-1313，经代理勘察确认） |

---

## 3. GPU 状态

### 3.1 Vulkan：**PASS**

- 对象链完整：Instance → PhysicalDevice（能力检测：API≥1.1 `VulkanBackend.cpp:461`、ETC2/ASTC `:471`、swapchain 扩展 `:484`、graphics queue `:501`）→ Device（仅启用实际支持的 feature `:529-544`）→ Queue（Adreno `vkGetDeviceQueue` 3 次重试 `:566-572`）→ Surface/Swapchain（FIFO `:663`，安全格式优先 `:618-641`）→ RenderPass/OffscreenPass（`finalLayout=TRANSFER_SRC` 修复 `:1062-1111`）→ Pipeline（独立描述符集修复 `:1257-1268`）→ 三缓冲持久映射 VBO（VulkanBackend.h:183-192）→ 同步对象。
- 驱动缺陷防护：`vkCreateShaderModule` SIGSEGV 信号捕获（:1148-1185，thread_local jmpbuf，其他线程不受影响）。
- **无 C++ 层厂商白名单**——设备选择纯能力检测，不存在"误排除集显"问题（移动端均为集显，逐个 `vkEnumeratePhysicalDevices` 选首个合格者 `:443-511`）。
- 理论提示（潜在风险，生产低概率）：若设备暴露 SwiftShader 等 CPU ICD（通常仅 debuggable/调试属性开启时），`selectPhysicalDevice` 不区分 DGPU/IGPU/SwiftShader，可能选中 CPU 设备。建议后续在设备名匹配 `swiftshader` 时跳过。

### 3.2 GLES：**WARN（功能性）**

- EGL 链完整（Display→Config→Context→Surface，GLES2 `GlesBackend.cpp:134-184`）；上下文线程模型 2026-09 已修复（init 末尾释放 `:127`，渲染线程首次 submitFrame 接管 `:339-347`）——历史真机黑屏根因已除。
- 使用真实硬件 GPU：EGL 默认 display 在真机即硬件驱动。**但代码从不调用 `glGetString(GL_RENDERER/GL_VENDOR/GL_VERSION)`（全 cpp 零命中），日志无法证明实际落在硬件而非软渲**——这正是任务书 §三.2 要求检查的项，当前不可从日志验证（P2-5）。
- 功能性降档（相对 Vulkan）：无渲染缩放（NativeBridge.cpp:314-318 恒 1.0）、无 ASTC/mip 链（:446-450 回退单级 RGBA）、无各向异性、纹理 NEAREST 过滤（GlesBackend.cpp:355-356，Vulkan 为 LINEAR+mip）——落入 GLES 的高端设备画质/功耗档位低于 Vulkan（P2-4 的影响面）。

### 3.3 Software Renderer：**PASS（仅显式兜底）**

- 三条进入路径全部显式：①策略 SOFTWARE_ONLY（安全模式/云游戏/模拟器+失败）；②降级链末端（Vulkan→GLES 双失败）；③ 10s init 超时后的兜底。
- 历史"全设备落入软渲"事故根因（surfaceCreated 期 `lockCanvas` 把 ANativeWindow 连到 CPU API → GPU 后端 `api_connect` 冲突）已在三处修复并留注释：AndroidSurfaceProvider.kt:139-152（surfaceCreated 不清屏）、NativeSurfaceView.kt:702-704（GPU 路径不提前 lockCanvas）、:1205-1207（RenderThread 启动仅软渲清屏）。当前全部 `lockCanvas` 调用点均被 `renderMode==SOFTWARE` 守卫——**未发现可再次全设备进入软渲的同类路径**。

---

## 4. CPU ↔ GPU 状态：**WARN（一次性上传设计 + 一处并发缺口）**

### 4.1 每帧数据上传

| 项 | 事实 | 评价 |
|---|---|---|
| 顶点 | Vulkan：`draw()` 直接 memcpy 进当前帧持久映射 VBO（VulkanBackend.cpp:2406-2429），无 staging 中转、零分配 | PASS，最优路径 |
| 顶点 | GLES：每帧 `glBufferData` 全量重传（GlesBackend.cpp:448-452，典型 ~400KB/帧） | P3-4（降级路径可接受） |
| Uniform | push constant（投影矩阵 :2573-2574；天空 128B :2547-2549），无每帧 UBO 写入 | PASS |
| 纹理 | 仅初始化/清晰度变化时上传，无每帧纹理上传 | PASS |

### 4.2 一次性大上传（图集）

- Vulkan ASTC：21MB `GetByteArrayElements`（可能整组拷贝）→ staging `memcpy` → `vkCmdCopyBufferToImage` → **fence 等待 UINT64_MAX**（:1795），全程持 `m_gpuMutex`（:1812/:2151）——上传期间渲染线程停 1 帧以上（注释自认可接受）。**fence 等待无超时上限，device lost 时渲染线程将永久阻塞**（P3-9，潜在风险）。
- 多级拷贝检查：无发现无意义拷贝链。RGBA 路径已做 direct ByteBuffer 零拷贝（NativeBridge.cpp:375-378 P0-3 注释）；ASTC 逐级跳 [size4] 前缀紧凑拷贝是为修复 Adreno VUID 00193 错位的必要拷贝（:2247-2266）。

### 4.3 GPU 同步点清单（全量）

| 调用 | 位置 | 频率 | 评价 |
|---|---|---|---|
| `vkWaitForFences` | submitFrame :2466 | 每帧 | 三缓冲下通常立即返回，标准模式 |
| `vkWaitForFences`(UINT64_MAX) | 一次性上传 :1795 / 白纹创建 :176 | 一次性 | 持锁跨等待，见上 |
| `vkDeviceWaitIdle` | shutdown :328 / resize :963 / setRenderScale :902 | 事件驱动 | 正常 |
| `glFinish`/`glReadPixels`/`vkQueueWaitIdle` | **全库不存在** | — | PASS |

### 4.4 Frame Pacing

- 目标帧率体系：场景档 10/30/60 × 热控 × 电量取 min（GameEngineCore.kt:289-302）；渲染线程 EWMA 能力帧率 min 叠加（NativeSurfaceView.kt:1308-1313，不回写 targetFps 防钉死）。
- 节拍：Vulkan FIFO 天然 vsync 对齐 + sleep；Canvas 全速档走 VsyncGate（Choreographer，:1218-1222）；降帧档整体 sleep。`Surface.setFrameRate` 声明（:1537-1604，高刷升档 2s 防抖）。
- 60→30→10 的降档是**场景化设计**（闲置 5s/30s 降档 GameEngineCore.kt:341-354），非异常跳变。
- 2026-09 已修复 step×interval 双重计数（:1413-1417 注释，30fps→15fps 的历史 bug）。

---

## 5. 内存状态：**PASS（含受控峰值）**

| 区域 | 预算/行为 | 证据 |
|---|---|---|
| VBO | 3×MAX_VERTICES×32B ≈ 11.3MB 常驻持久映射 | VulkanBackend.h:192, :1600 |
| 图集 GPU | ASTC ~21MB（首选）或 RGBA mip 链 ~22MB | AtlasAsyncPipeline.kt:368 注释 |
| 预取缓存 | 21.3MB KTX：预取→取用即清（consume() :61）→ 非 ASTC 分流/TrimMemory 丢弃（NativeSurfaceView.kt:420-424；GameActivity.kt:1034） | SectAtlasPrefetch.kt |
| RGBA 拼装峰值 | 拼装 Bitmap(16MB)+mip 链 direct(22MB) 后台一次性；已弃 ByteArray 中转（P0-3 修复，NativeBridge.cpp:375-378） | AtlasAsyncPipeline.kt:376-415 |
| staging buffer | 持久复用按需扩容（ensureStagingBuffer :1710-1713） | — |
| 泄漏检查 | `destroyTexture` Vulkan 空实现（:2394-2396）且无 JNI 出口→运行期无单纹理释放；但纹理仅图集/地面/白纹等 <10 个，不构成累积泄漏；resize 描述符池泄漏已修（:1484-1487）；shutdown 全量释放（:326-399）；gamecore 纯 unique_ptr 无循环引用 | P3-5 |
| 高频分配 | 见 P2-6（SpriteBatcher）与 §7 Kotlin 侧（稳态零拷贝，仅预览/云快照分配） | — |
| 内存压力响应 | Application/GameActivity onTrimMemory 分级释放 + MemoryPressureListener 注册成对 | XianxiaApplication.kt:374-400；GameActivity.kt:1022-1045 |

---

## 6. 多线程状态：**WARN（1 处确定数据竞争 + 2 处时序依赖）**

### 6.1 线程表（实码确认）

| 线程 | 创建点 | 职责 |
|---|---|---|
| 主线程 | 系统 | Compose 重组、生命周期、图集最终上传调用（JNI 入队） |
| **NativeRenderer** | NativeSurfaceView.kt:1165 | 渲染帧循环（JNI 进入 C++ 全部帧路径） |
| VulkanInit | :951 | Vulkan/GLES initRenderer 链 |
| AtlasBuild(daemon) | AtlasAsyncPipeline.kt:59 | 图集拼装重活（不触碰 C++ renderer） |
| VsyncGate(HandlerThread) | VsyncGate.kt:38 | 仅 Canvas 全速档 vsync 节拍 |
| GameEngine-Thread | GameEngineCore.kt:408-415 | 100ms 逻辑 tick + nativeLoopFrame |
| GameEngine-Watchdog | :421-428 | tick 停滞检测→紧急换线程 |
| AppStartup-Init | XianxiaApplication.kt:330-331 | MMKV/Bugly/Changelog 后台初始化 |
| GameDB-Query-N/Txn | GameDatabase.kt:461-467 | Room |
| C++ JobSystem workers | gamecore ecs/job_system.h | 旬结算并行 |

### 6.2 锁与同步

- `g_rendererLifecycleMutex`（NativeBridge.cpp:55）：序列化 prewarm/initRenderer/shutdown/resize 四个 delete g_renderer 的入口。每帧路径不持此锁，依赖 Kotlin「渲染线程先停再 shutdown」时序（NativeSurfaceView.kt:813-837 有 2s 截止 + join 失败跳过 release 防 use-after-free）。
- `m_gpuMutex`（VulkanBackend.h:231）：序列化纹理表读写与 VkQueue 双线程提交——历史「Tile 短暂纯色」根因修复，设计正确。
- **确定问题 P1-1：GLES `m_pendingUploads` 无锁跨线程**（详见问题清单）。Vulkan 有 m_gpuMutex，GLES 同构的队列却无任何同步：主线程 `uploadTexture` push_back（GlesBackend.cpp:368-384）vs 渲染线程 `drainUploads` 遍历+clear（:349-366，submitFrame :440 调用）。
- 无锁通道纪律良好：相机/热控/淡入/后端类型均为 @Volatile + std::atomic 单写单读（NativeBridge.cpp:92-128）；Surface 纪元守卫防 stale 回调（AndroidSurfaceProvider.kt:57, :162, :181）。
- EGL Context 线程归属：已修复（见 §3.2），无绑错线程。
- GameEngine-Thread 反冻结忙等（GameEngineCore.kt:1930-1969）：自适应启用（检测 tick 异常间隔），vivo 等反挂起场景刻意设计，非 bug；常态纯 delay。

### 6.3 主线程负担

- 游戏 tick、渲染提交、图集拼装均**不在**主线程；主线程仅承担：Compose 重组、帧数据门控写入、图集最终上传 JNI（设计使然：C++ g_renderer 无锁，注释 AtlasAsyncPipeline.kt:17-20）、生命周期。
- 常驻微开销（P3-7）：`Looper.setMessageLogging` 每条主线程消息一次 startsWith（XianxiaApplication.kt:143-152）；FrameMetrics 回调仅 GameActivity 前后台窗口内（GameActivity.kt:796）。

---

## 7. 地图渲染状态

- **规模**：128×128 格、tile 48px、世界 6144²px（GameConfig.kt:997-1002）；图集 4096²（ASTC）或 2048²（RGBA 回退）。
- **可见性**：默认视口 ~1700 格；`drawAllTiles` 按相机钳制行列区间 + 逐格 `isRectVisible`（NativeBridge.cpp:733-736, :140-144）——**不存在整图每帧全绘**（历史 16384 格遍历已优化，注释 :729-732）。
- **极小缩放（整岛视图）**：16384 格全部可见 → 地面 quad 98304 顶点，接近 `MAX_VERTICES=122880` 上限（Rhi.h:52）。超限丢弃策略=后加者优先（SpriteBatcher.cpp:23-27），即**建筑/道路/阴影先于地面被静默丢弃**——极端缩放下视觉降级，无日志。装饰层已由 LOD（scale<0.6）跳过不占批。
- **每帧顶点重建**：GPU 路径无持久化顶点/烘焙缓存（对比：软渲路径反而有 32×32 chunk 缓存，SoftwareCanvasBackend，经代理勘察）。当前规模（~2100 精灵/12600 顶点）CPU 发射为 ms 级以下，属可接受权衡；若未来地图元素数量级增长需重评。
- **缩放/移动**：不重建任何纹理/烘焙资源，仅重发可见区顶点。浮空岛边缘层为一次性预计算（gamecore island_edge 单一权威，MainGameScreen remember 级缓存）。
- **地面**：整图 REPEAT quad 因 Adreno 740 采样黑屏被禁用（`GROUND_QUAD_ENABLED=false`，NativeBridge.cpp:708，真机注释 :704-707）→ 恒走逐格地面（图集 GROUND 精灵）。这是当前顶点量的主要来源，属已知驱动问题的规避，待驱动问题定位后可恢复。
- **合批**：全部地图内容（地面/装饰/道路/建筑阴影/建筑/作物/云）合并进**单个** SpriteBatcher → **单次 draw**（:1100-1104）；天空 1 次、边缘层 1 次——常态 3-5 draw call。**不存在"一张图片一个 Draw Call"**。例外：放置/移动模式的网格线逐条 drawRect（VulkanRenderBackend.kt:423-430，缩小视图可达 ~100-240 条，每条 6 顶点，P3-2）。

---

## 8. 性能瓶颈与风险排序（按严重程度）

1. **GLES 纹理上传队列数据竞争**（P1-1，稳定性，确定）
2. **Vulkan 失败持久化机制整体失效**（P2-1，每次冷启动重复支付 Vulkan 失败代价 + SIGSEGV 检测死机制）
3. **安全模式粘性**（P2-2，误判进入后永久软件渲染直到手动解除）
4. **旗舰机型静态黑名单过宽 + GLES 功能降档**（P2-4，高端设备被剥夺 Vulkan 档位）
5. **Renderer 健康状态上报缺口**（P2-5，GLES 零 GPU 信息、initRenderer 无错误码、回退原因不持久——直接削弱下次事故的定位能力）
6. **SpriteBatcher 每帧堆分配链**（P2-6，渲染线程每帧 5-6 次 new/memcpy/delete ×2）
7. 图集上传持锁跨 fence 无超时（P3-9，一次性 + device-lost 阻塞风险）
8. resize 与渲染线程的窄 UB 窗口（P2-7，潜在，旋转/分屏触发）
9. prewarm 超时×生命周期锁×10s 安全网竞态（P2-8，潜在，慢设备误降级）
10. drawAllTiles 每帧全图数组过 JNI（P3-1，潜在，需实测 ART pinned 行为）
11. 放置模式网格线逐条 draw（P3-2）
12. GLES 每帧全量 VBO 重传 + NEAREST（P3-4）
13. 作物帧末 O(n×m)（P3-3）、destroyTexture 空（P3-5）、Looper 监控常驻（P3-7）、CMake 无显式优化/LTO（P3-8）、GPU 正则防线输入错位（P3-6）

---

## 9. 兼容性风险

| 维度 | 现状 | 风险 |
|---|---|---|
| GPU 厂商 | C++ 层无厂商判断；Kotlin 策略层：机型黑名单 40（VulkanPolicy.kt:379-421）+ 厂商表（:425-439，仅作日志信号）+ Unity 式 API 版本量化阈值（:94-99）+ GPU 型号正则（:473-495） | 机型黑名单覆盖 2023-2024 全部 Adreno 740/750 旗舰 → GLES_PREFERRED，与 :748-753「窄 Deny」策略转向矛盾（P2-4）；GPU 正则匹配输入是 SOC_MODEL/board（不含 adreno/mali 字样）基本永不命中，防线 4 形同虚设（P3-6） |
| 量化阈值闭环 | C++ 上报 vendorId/apiVersion/driver（selectPhysicalDevice :452-455 → setVulkanDeviceInfo :362-374）→ 低于阈值记录失败 | 记录的失败标记被 onCleanLaunch 每次清除（P2-1），「下次启动走 GLES」的闭环实际不存在 |
| 驱动版本 | 已知坏 Adreno 驱动区间检测（:331-343）→ recordVulkanInitFailure | 同上，持久化被清 |
| Android 版本 | API<31：非白名单厂商整机禁 HW 加速（:878-884）+ GLES_PREFERRED | API<31 上 HWUI 本用 OpenGL ES，禁 HW 加速属保守过度，低端旧机 UI 合成走 CPU（潜在性能损耗，设计权衡）；API 35+ 按 tier+risky vendor 禁 SkiaVK |
| 模拟器/云游戏 | 4 信号模拟器检测（:155-212，信号 5 已移除防误伤真机）+ TapTap 沙箱 maps 扫描（:285-300） | 合理；检测失败时降级链仍保证 GPU 优先 |
| ABI | 仅 arm64-v8a（app/build.gradle:75-77，决策 7） | 32 位老设备直接不安装，属产品决策非隐患 |
| NDK/CMake | NDK r27（:44）、CMake 3.22+、C++20；未显式设置 -O/LTO/STL（CMakeLists.txt:1-78） | Release 依赖 AGP 默认（Release 优化 + SYMBOL_TABLE :120-122），Debug/Release 渲染路径**无 #ifdef 分叉**——两构建类型渲染行为一致（任务书 §十八 关注项 PASS） |

---

## 10. P0/P1/P2/P3 问题清单

> 每项含：严重等级 / 文件 / 类·函数 / 代码位置 / 当前逻辑 / 问题原因 / 触发条件 / 可能影响 / 推荐方案。

### P0（全设备 GPU 失效级）：**无**

未发现可再次导致全设备 GPU 渲染失效的代码路径。历史根因（lockCanvas CPU API 占用）三处修复点均有效且新代码路径遵守约束（见 §3.3）。

---

### P1-1【确定问题】GLES 纹理上传队列无锁跨线程访问

- **等级**：P1（潜在崩溃/UB；影响 GLES 中间层会话）
- **文件**：`android/app/src/main/cpp/GlesBackend.h` / `GlesBackend.cpp`
- **类/函数**：`GlesBackend::uploadTexture` / `GlesBackend::drainUploads`
- **代码位置**：GlesBackend.h:107（`std::vector<PendingUpload> m_pendingUploads;` 无互斥保护）；GlesBackend.cpp:368-384（主线程 push_back）；GlesBackend.cpp:349-366 + :440（渲染线程 submitFrame 内遍历后 clear）
- **当前逻辑**：图集上传被拆为「主线程入队（拷贝像素）→ 渲染线程 drainUploads 真实 GL 上传」（2026-09 线程模型修复的正确设计），但两侧访问同一 `std::vector` 无任何 mutex/atomic。
- **问题原因**：与 VulkanBackend 的 `m_gpuMutex`（VulkanBackend.h:219-231 注释明确记载了同构问题的事故史）不同构——GLES 版本漏配了同款保护。
- **触发条件**：GLES 会话中，AtlasBuild 后台拼装完成 post 到主线程执行 `uploadTexture` 入队时，RenderThread 恰在 submitFrame→drainUploads 遍历/清空。图集拼装耗时秒级，期间渲染线程已在全速循环，窗口真实存在（每 surface 一次主窗口）。
- **可能影响**：vector 并发读写 UB → 低概率 native 崩溃；或上传丢失/撕裂 → 图集黑屏（下一 surface 重建自愈）。
- **推荐方案**：给 `m_pendingUploads` 加 mutex（与 Vulkan m_gpuMutex 同构）；或改用 mutex+cond/双缓冲队列。顺带把 `drainUploads` 的 NEAREST 过滤对齐 Vulkan 的 LINEAR+mip 语义（见 P3-4）。

---

### P2-1【确定问题】Vulkan 失败持久化机制整体失效（含死标记）

- **等级**：P2（回退机制失效 + 每次冷启动重复失败代价；非崩溃级）
- **文件**：`android/app/src/main/java/com/xianxia/sect/core/CrashRecoveryEngine.kt`、`GameActivity.kt`、`VulkanPolicy.kt`、`CrashHandler.kt`
- **类/函数**：`CrashRecoveryEngine.onCleanLaunch` / `GameActivity.setupWindowAndDiagnostics` / `VulkanPolicy.getRenderStrategy(2/4/5/6 号策略)` / `CrashHandler（Java 层）`
- **代码位置**：
  - GameActivity.kt:660（`onCleanLaunch()`）先于 :664（`getRenderStrategy(this)`）；
  - CrashRecoveryEngine.kt:111-126（onCleanLaunch 清除 `KEY_VULKAN_INIT_FAILED/PREWARM_STARTED/SURFACE_INIT_STARTED`）；
  - VulkanPolicy.kt:610-616（vulkanCrashStrategy）/ :644-650（persistentVulkanFailureStrategy）/ :653-660（prewarmKilledStrategy）/ :663-670（surfaceInitKilledStrategy）——四个分支读的键刚被清除，**同一次启动内永不可达**；
  - CrashRecoveryEngine.kt:47/:121/:263-265：`KEY_VULKAN_CRASH_DETECTED` **全代码库无任何写入方**（已全局 grep 验证），`vulkanCrashStrategy`/detectTier:731/shouldDisableHardwareAcceleration:867 三个消费点永不触发；
  - CrashHandler.kt:79-83：仅注册 Java `Thread.UncaughtExceptionHandler`，native SIGSEGV 不经 `recordCrash`。
- **当前逻辑**：2026-09 刻意设计「干净启动重试 Vulkan」（CrashRecoveryEngine.kt:115-119 注释：防旧标记误判死锁，失败会在本次运行经降级链自愈并重新记录）。
- **问题原因**：设计解决了「临时失败被永久缓存」的问题，但同时使三套防护变为死代码：①写前标记 SIGSEGV 检测（CrashRecoveryEngine.kt:202-256 整段，标记写后必被下轮清除）；②持久化 GLES_PREFERRED 降级；③「一次 Vulkan SIGSEGV 即降级」策略（注释宣称 :46，机制不存在）。且 Vulkan-broken 设备每次冷启动都要重新支付：prewarm ≤5s + initRenderer 失败 + Vulkan→GLES 二次降级的时延。
- **触发条件**：任何 Vulkan 不可用设备（驱动崩溃/初始化失败）的每一次冷启动。
- **可能影响**：受影响设备每次进游戏多付出秒级首帧延迟；真发生 Vulkan SIGSEGV 的设备反复进程死亡（用户视角=连续闪退）直到偶然进入 GLES。
- **推荐方案**（需产品决策，存在权衡）：保留「重试」语义但加退避——如失败标记改存「连续失败次数」，≥3 次才在本应用生命周期内停用 Vulkan（重启 App 再重试）；或给 `KEY_VULKAN_CRASH_DETECTED` 补上写入方（在 native 崩溃归因处，如 Bugly native crash 回调/Tombstone 检测后标记）。

---

### P2-2【确定问题】安全模式（SAFE_MODE）粘性 + 计数语义混乱

- **等级**：P2（一旦误触发，永久软件渲染）
- **文件**：`CrashRecoveryEngine.kt`
- **类/函数**：`recordCrash` / `enterSafeMode` / `onCleanLaunch`
- **代码位置**：:50（`SAFE_MODE_THRESHOLD=2`）、:80-97（任意 Java 未捕获异常 +1）、:94-96（≥2 进安全模式）、:111-126（onCleanLaunch **不清** `KEY_RENDER_SAFE_MODE`，只清计数器与 Vulkan 标记）、:155-169（进入后持久化）
- **当前逻辑**：连续崩溃 2 次 → 安全模式 → 禁 HW 加速 + SOFTWARE_ONLY，持久直到用户手动解除（leaveSafeMode :138-144）或重装。
- **问题原因**：①计数来源是**任意**未捕获异常（OOM/SDK 崩溃都算），并非 GPU 专属——非渲染问题的两次崩溃也会被定性为「GPU 渲染问题」（enterSafeMode 日志 :159-168 如此宣称）；②`KEY_RENDER_SAFE_MODE` 永不被清理，这是全项目唯一真实存在的「临时失败被永久缓存」实例（任务书 §四 关注项的反例）；③由于 onCleanLaunch 每次启动清零计数器，实际达到 2 次需要「同进程双崩溃」或「崩溃发生在 GameActivity.onCleanLaunch 之前两次」，触发语义与其注释（:49「Vulkan 崩溃可预判，2 次即可触发」）不符。
- **触发条件**：两次未捕获异常（含非 GPU 原因）落入上述窗口。
- **可能影响**：被误判设备永久运行 CPU 软渲（骁龙 8 Gen 2 软渲实测 77-990ms/帧，NativeSurfaceView.kt:866-868 注释），性能断崖且用户难以自救。
- **推荐方案**：安全模式加入自动衰减（如 N 天后自动重试 GPU）；计数只归因渲染线程/GPU 相关崩溃；或至少在 UI 提示中给出解除入口。

---

### P2-3【潜在风险】VulkanBackend::resize 与渲染线程的窄 UB 窗口

- **等级**：P2（潜在，低概率 native 崩溃）
- **文件**：`android/app/src/main/cpp/VulkanBackend.cpp`
- **类/函数**：`resize` vs `submitFrame`
- **代码位置**：resize :955-1010（m_ready=false → vkDeviceWaitIdle → 持 m_gpuMutex 销毁并重建 swapchain）与 submitFrame :2462-2495（入口 :2463 查 m_ready → vkWaitForFences → vkAcquireNextImageKHR :2473 → :2477 再查 m_ready）
- **当前逻辑**：resize 置 `m_ready=false` 挡住**新**帧（注释 :958-962 自认 WaitIdle 只等 GPU 不等渲染线程）。
- **问题原因**：渲染线程若已通过 :2463 检查并阻塞在 `vkAcquireNextImageKHR`，resize 在主线程销毁 swapchain 时，acquire 仍持已失效句柄——`vkDestroySwapchainKHR` 与在途 acquire 并发属 UB（:2477 的二次检查发生在 acquire 返回之后，保护不了 acquire 自身）。
- **触发条件**：旋转/分屏等真实 resize 恰逢渲染线程阻塞在 acquire（同尺寸事件已被去抖 :749-756 过滤，窗口极窄）。
- **可能影响**：低概率驱动层崩溃。
- **推荐方案**：resize 前与渲染线程做一次握手（如渲染线程 inFrame 标志 + 自旋等待），或将 resize 任务投递到渲染线程串行执行（与 setRenderScale 同线程化思路）。

---

### P2-4【确定问题】旗舰机型静态黑名单与「窄 Deny」策略矛盾 + GLES 功能降档

- **等级**：P2（高端设备性能/画质档位损失）
- **文件**：`VulkanPolicy.kt`、`NativeBridge.cpp`、`GlesBackend.cpp`
- **类/函数**：`KNOWN_PROBLEM_MODELS` / `detectTier` / `NativeBridge.setRenderScale` / `GlesBackend`
- **代码位置**：VulkanPolicy.kt:379-421（机型表：小米 14/14 Pro/13 Ultra/K70 全系/Find X7/OnePlus 12/Magic6/Mate60 等约 40 款）；:743-746（命中→PROBLEMATIC）；:695-699（PROBLEMATIC→GLES_PREFERRED）；:748-753（注释宣称 2026-09 已转向「默认 Vulkan+窄 Deny，移除整厂商一刀切」——但机型表仍在且先于该逻辑）；NativeBridge.cpp:314-318（GLES 恒返回 renderScale=1.0）；:446-450（GLES 不支持 mip 链）；GlesBackend.cpp:355-356（NEAREST）
- **当前逻辑**：机型命中黑名单即放弃 Vulkan。
- **问题原因**：表内 2023-2024 旗舰（Adreno 740/750）正是 Vulkan 收益最大的设备（GLES 路径无渲染缩放、无 mip、无 ASTC、无各向异性、NEAREST 采样），与注释声明的策略转向直接矛盾；名单无自动退出机制（注释 :377 说「基于 Bugly 数据维护」，实际无校准闭环——量化阈值闭环也被 P2-1 破坏）。
- **触发条件**：黑名单内设备（当前市面主力旗舰）。
- **可能影响**：这些设备渲染分辨率恒为原生 1.0（高功耗）、纹理采样质量降档、无 ASTC 内存优势；与同款 GPU 的非名单设备表现不一致。
- **推荐方案**：结合 Bugly 数据复核名单（尤其 #3088/#9045 注释指向的 vkGetDeviceQueue/RenderThread join 类崩溃——后者已在 VulkanBackend.cpp:566-572 加重试防御）；或将名单命中改为 WARNING（VULKAN_PREFERRED+运行时失败再降级），复用已验证的运行时回退链。

---

### P2-5【确定问题】Renderer 健康状态上报缺口（任务书 §二十 项）

- **等级**：P2（可观测性；直接削弱「下次 GPU 事故」的定位能力）
- **文件**：`GlesBackend.cpp`、`NativeBridge.cpp`、`NativeSurfaceView.kt`、`VulkanPolicy.kt`
- **类/函数**：`GlesBackend::init` / `NativeBridge.initRenderer` / `NativeSurfaceView.handleVulkanInitFailure` / `fallbackToSoftwareRenderer`
- **代码位置**：全 cpp 无 `glGetString`（grep 零命中）；GlesBackend.cpp:130（init 成功日志只有分辨率）；NativeBridge.cpp:242-304（initRenderer 只返回 boolean，C++ 内部失败阶段——createInstance/selectPhysicalDevice/createLogicalDevice/createSwapchain/initEgl/initPipeline——不回传错误码）；NativeSurfaceView.kt:1042-1044（唯一失败日志："Vulkan init failed after Xms — falling back..."，无阶段/无原因）；:1100-1109（fallbackToSoftwareRenderer 无独立日志，只在跳帧健康日志的 mode 字段间接可见）；CrashRecoveryEngine 无「回退原因」持久化。
- **当前逻辑**：Vulkan 路径有 GPU 名/驱动日志（VulkanBackend.cpp:456-459）与每秒 RenderHealth（NativeSurfaceView.kt:1355-1365，含 mode），但 GLES 路径零 GPU 信息、所有失败归并为一个布尔。
- **问题原因**：任务书明令「任何进入 Software Renderer 的情况都必须有明确原因，禁止 Renderer initialization failed 式日志」——现状正是如此。
- **触发条件**：任何 GLES/软件会话、任何 Vulkan 失败。
- **可能影响**：真机排查只能靠 logcat 现场抓取；无法从版本数据统计「多少设备/为什么」落在非 Vulkan 路径。
- **推荐方案**：①initRenderer 返回错误码（枚举失败阶段），Kotlin 侧记录结构化日志；②GLES init 时 `glGetString(GL_RENDERER/GL_VERSION)` 入日志；③进入 SOFTWARE 时打独立「FallbackReason」日志并持久化（与 P2-1 修复联动）；④后续可接 TapDB/Bugly 自定义事件上报（backend_type, gpu_name, driver, fallback_reason）。

---

### P2-6【确定问题】SpriteBatcher 每帧堆分配链

- **等级**：P2（渲染线程每帧稳定分配，GC/分配器压力）
- **文件**：`android/app/src/main/cpp/NativeBridge.cpp`、`SpriteBatcher.cpp/h`
- **类/函数**：`drawAllTiles` / `drawIslandEdges` / `SpriteBatcher::grow`
- **代码位置**：NativeBridge.cpp:695 与 :1162（每帧栈上构造 `SpriteBatcher batcher;`）；SpriteBatcher.cpp:68-84（grow：new[]+memcpy+delete[]）；SpriteBatcher.cpp:6-7 注释自证设计意图（「若每帧重建 = 5 次 new/memcpy/delete 流失」）——但该注释的「跨帧复用」只在同一 batcher 实例存续期成立，而实例正是每帧重建的。
- **当前逻辑**：典型帧 ~2100 精灵 = 12600 顶点 > 512 栈容量 → grow 链 512→1024→…→16384（5 次 new/memcpy/delete）×2 个 batcher（地图层+边缘层）/帧。
- **触发条件**：每个渲染帧（必然超 512 顶点）。
- **可能影响**：每帧 ~10 次分配 + ~90KB 冗余 memcpy；在低端机上叠加分配器压力（量级中等，非主要瓶颈——置信度：静态分析，需 profile 确认占比）。
- **推荐方案**：batcher 提升为文件级 static（复用堆缓冲，匹配原设计意图；渲染线程单消费者，无并发问题），或由 NativeBridge 持有 thread_local 实例。

---

### P2-7【潜在风险】prewarm 超时不可取消 × 生命周期锁 × 10s 安全网竞态

- **等级**：P2（潜在，慢设备误降级）
- **文件**：`GameActivity.kt`、`NativeBridge.cpp`、`AndroidSurfaceProvider.kt`
- **类/函数**：`startVulkanPrewarmAndAtlasPrefetch` / `prewarmDevice` / `initRenderer` / `startInitTimeout`
- **代码位置**：GameActivity.kt:324-347（withTimeout(5s) 只放弃**等待**，无法取消 JNI 内的 native 调用）；NativeBridge.cpp:175-205（prewarmDevice 全程持 `g_rendererLifecycleMutex`）；:252（initRenderer 阻塞于同一锁）；AndroidSurfaceProvider.kt:200（INIT_TIMEOUT_MS=10s）
- **当前逻辑**：prewarm 在 onCreate 发起；若 boot 数据阶段先于 prewarm 完成（慢设备 initDevice+shader+pipeline cache 加载 >5s），surface 期 initRenderer 在生命周期锁上阻塞。
- **问题原因**：prewarm(≤5s+) + initRenderer 剩余耗时可能越过 10s 安全网 → `handleSurfaceInitTimeout` 触发 → 降级链启动（GLES→软件）——**而 Vulkan 本身是健康的**，只是慢。
- **触发条件**：低端/慢存储设备首次启动（无 pipeline cache）且 boot 快于 prewarm。
- **可能影响**：健康设备被误降 GLES 一整个会话（下次启动重试）。
- **推荐方案**：initRenderer 阻塞获取锁时对超时安全网「续期」或先 `try_lock` 报告「prewarm in-flight」状态让 Kotlin 侧延时重试而非降级；安全网触发前查询 native 侧 prewarm 是否仍在进行。

---

### P3 清单（工程量级）

| # | 问题 | 位置 | 说明与建议 |
|---|---|---|---|
| P3-1 | drawAllTiles 每帧传全图数组过 JNI | NativeBridge.cpp:691/:816（tileData+roadData 各 16384 int=64KB）；消费端仅可见窗口 :733-736 | `GetIntArrayElements` 在 ART 大数组上可能整组拷贝（非 pinned 时）。潜在风险，需实测；可改可见窗口切片或持久 direct ByteBuffer |
| P3-2 | 网格线/高亮逐条 drawRect | VulkanRenderBackend.kt:404-431（每线 1 draw，放置模式 ~100-240 draw）；:273-277/:336-359（选中/拆除每建筑 1-5 draw） | 每条 6 顶点、同白纹理（描述符只绑一次 :2594-2595），CPU 记录成本小；可合批为单个白纹 draw。非紧急 |
| P3-3 | 作物插值帧末裁剪 O(n×m) | NativeBridge.cpp:1030-1039（注释自认 >500 作物需改） | 有界小集合；维持现状可接受 |
| P3-4 | GLES 每帧全量 glBufferData + NEAREST | GlesBackend.cpp:448-452 / :355-356 | 降级路径 ~400KB/帧可接受；过滤语义与 Vulkan 不一致属画质问题，随 P1-1 一并处理 |
| P3-5 | destroyTexture 空实现且无 JNI 出口 | VulkanBackend.cpp:2394-2396 | 当前纹理数 <10 无累积；若未来加动态纹理需先补齐（GLES 版实现是完整的 :386-394） |
| P3-6 | GPU 型号正则防线输入错位 | VulkanPolicy.kt:775-795（匹配 SOC_MODEL/board，不含 GPU 名）；:787 注释假设「mt6893 含 Mali 信息」不成立 | 防线 4 基本永不命中（当前客观上避免了对 Adreno 75x 的误杀）。应改为匹配 C++ 上报的 deviceName（selectPhysicalDevice 已有） |
| P3-7 | 主线程常驻监控微开销 | XianxiaApplication.kt:143-152（Looper logging 每消息 startsWith）；GameActivity.kt:796（FrameMetrics 窗口内） | 量级极小；可在 Release 构建关闭 Looper 监控 |
| P3-8 | CMake 未显式声明优化/LTO/STL | cpp/CMakeLists.txt:1-78 | 依赖 AGP/NDK 默认（Release 优化生效）；建议显式声明 Release flags 与（可选）LTO，锁定构建行为 |
| P3-9 | 上传 fence 等待无超时 | VulkanBackend.cpp:1795/:176（UINT64_MAX），且持 m_gpuMutex :1812 | device lost 时渲染线程永久阻塞。建议加有限超时（如 100ms×N）后放弃上传并标记 device lost |
| P3-10 | selectPhysicalDevice 注释与实现不符 | VulkanBackend.cpp:442（「优先选独立 GPU」）vs :443-511（实际选首个合格设备） | 移动端无独立 GPU，纯注释债务；顺带可在匹配 `swiftshader` 设备名时跳过（见 §3.3） |
| P3-11 | RenderThread 初始 targetFps=10 窗口 | NativeSurfaceView.kt:131（默认 10）vs GameEngineCore.kt:256（StateFlow 默认 60） | StateFlow collect 前若已开渲，首段按 10fps 节拍；EWMA/淡入遮蔽下影响有限 |

---

## 11. 最终结论

### 11.1 是否可能再次出现「所有设备 GPU 失效」？

**结论：当前代码中未发现可再次导致全设备 GPU 渲染失效的路径（低风险）。** 依据：

1. 历史事故的唯一系统性根因（Canvas API 提前占用 ANativeWindow）已在全部三个 `lockCanvas` 调用点加 `renderMode==SOFTWARE` 守卫（AndroidSurfaceProvider.kt:139-152、NativeSurfaceView.kt:702-704、:1205-1207），新代码路径（GLES 中间层）也遵守该约束；
2. 运行时回退链严格为 Vulkan→GLES→Software（NativeSurfaceView.kt:1053-1135），不存在「Vulkan 探测失败直接软件」的路径；
3. C++ 设备选择纯能力检测，无版本/厂商一刀切。

但存在 **3 个局部结构性风险**（均不会同时打倒所有设备，但会让特定设备长期停留在非 Vulkan 路径）：P2-1（回退持久化失效 + native SIGSEGV 无持久保护，受损设备反复重试乃至反复崩溃）、P2-2（安全模式误判后永久软渲）、P2-3/P2-7（两处窄竞态窗口）。这三类是本轮审计建议优先修复的对象。

### 11.2 当前项目属于哪种 Bound？

**静态分析无法定论，需要运行时 Profiling 才能确定**（按任务书要求明确声明）。基于代码的判断：

- **Software Rendering**：仅存在于显式兜底路径（安全模式/双后端失败/模拟器+失败），非默认状态。
- **常态负载（静止/挂机为主，10-30fps 档）**：多重脏帧跳过 + 低帧率档下，CPU/GPU 余量大，**大概率无明显瓶颈**。
- **动态峰值（拖拽地图 + 放置模式 + 60fps 档，中低端 GPU）**：主要候选瓶颈依次为——①渲染线程 CPU（每帧顶点发射 + SpriteBatcher 分配链 + JNI 数组传递，P2-6/P3-1）；②放置模式 draw call 放大（P3-2）；③GLES 会话的每帧全量 VBO 重传（P3-4）。均未达到「确定瓶颈」的证据标准。
- **Sync Bound**：无证据。同步点清单干净（§4.3），唯一持锁跨 fence 是一次性图集上传。

**建议 profiling 验证项**（真机，骁龙 8 Gen 2 + 一台中端 Mali 各一台）：`drawAllTiles` JNI 段耗时（含 GetArrayElements 是否拷贝）、renderTick 总时长在拖拽态的分布、放置模式帧的 vkCmdDraw 数与 CPU 提交耗时、GLES vs Vulkan 会话的帧耗时对比。

### 11.3 待确认修复清单（本轮不执行，等待确认）

| 优先级 | 修复项 | 对应问题 |
|---|---|---|
| 1 | GLES m_pendingUploads 加锁 | P1-1 |
| 2 | Vulkan 失败退避重试策略 + 补 KEY_VULKAN_CRASH_DETECTED 写入方 | P2-1 |
| 3 | 安全模式自动衰减 + 归因收窄 | P2-2 |
| 4 | Renderer 健康状态结构化上报（initRenderer 错误码 + glGetString + FallbackReason） | P2-5 |
| 5 | SpriteBatcher 跨帧复用 | P2-6 |
| 6 | 黑名单复核 / 策略统一 | P2-4 |
| 7 | resize 线程握手、prewarm-超时协同、fence 超时 | P2-3 / P2-7 / P3-9 |
| 8 | 其余 P3 按迭代节奏处理 | P3-1~P3-11 |

---

## 附录 A：任务书 25 问逐答

**GPU**

1. **Vulkan 应否第一优先级？** 应。能力检测完备、驱动缺陷防护到位（SIGSEGV 捕获/队列重试/安全格式/FIFO）、三缓冲 VBO、Pipeline Cache。现状即是首选（VulkanPolicy.kt:701-708）。
2. **GLES 是否正确作为第二优先级？** 是，且是 2026-09 新补齐的正确中间层（真实硬件 GPU，非软渲）。但功能档位低于 Vulkan（无缩放/mip/ASTC，§3.2）且存在 P1-1 数据竞争。
3. **Vulkan→GLES fallback 是否正确？** 运行时正确（NativeSurfaceView.kt:1061-1064 失败先试 GLES，防循环）。**持久化层面失效**（P2-1：失败标记被 onCleanLaunch 先行清除，四策略分支死代码）。
4. **是否存在错误进入 Software 的路径？** 常规路径不存在（GLES 失败才软件）。两个边界：①10s 超时安全网在「prewarm 阻塞 initRenderer」的慢设备场景可能误降级（P2-7，且超时路径也先试 GLES）；②API<31 非白名单模拟器直接 GLES_PREFERRED（合理设计）。
5. **是否可能再次全设备 GPU 失效？** 未发现此类路径（§11.1）；历史根因三处修复有效。局部风险见 P2-1/P2-2/P2-3。

**CPU**

6. **最大潜在 CPU 瓶颈？** 渲染线程每帧顶点发射段的堆分配链（P2-6）+ 全图数组 JNI 传递（P3-1）。逻辑侧已下沉 C++ 且固定步长健康。需 profile 定占比。
7. **每帧不必要计算？** 未发现成规模者；视口钳制/脏帧跳过/LOD/稳态零拷贝均已做。遗留为小项（P3-2 网格线逐条、P3-3 作物裁剪）。
8. **大量 CPU 内存分配？** 每帧级：SpriteBatcher grow 链（确定，P2-6）、预览合并 copy/云快照（低频）；一次性：图集拼装（已后台化）。无每帧大对象分配。
9. **主线程阻塞？** 游戏逻辑/tick/渲染提交/图集拼装均不在主线程。主线程仅一次图集上传 JNI 入队（Vulkan 侧含 memcpy+fence 等待，持 m_gpuMutex——上传期间主线程会阻塞至上传完成，一次性、毫秒~十毫秒级，属已知设计取舍，AtlasAsyncPipeline.kt:17-20 注释自述）。
10. **CPU 承担 GPU 工作？** 仅显式兜底的 SoftwareCanvasBackend（含 chunk 缓存与 0.5 缩放上限），正常设备不进入。

**CPU↔GPU**

11. **大量数据上传？** 每帧仅顶点一次 memcpy（Vulkan，最优）；大额上传仅图集一次性（21/22MB，持锁跨 fence——渲染停一帧以上，可接受但有 device-lost 阻塞隐患 P3-9）。
12. **GPU 同步等待？** 无 glFinish/glReadPixels/vkQueueWaitIdle；vkDeviceWaitIdle 仅事件驱动（shutdown/resize/setRenderScale）。
13. **CPU 等 GPU？** 每帧 vkWaitForFences（标准三缓冲模型，通常立即返回）；图集上传的 fence 等待为一次性。
14. **GPU 等 CPU？** FIFO present 下 GPU 受 CPU 提交节奏约束，属帧 pacing 设计而非病态等待；低帧率档为主动省电。
15. **严重 Frame Pacing 问题？** 无。 pacing 体系完整（场景档×EWMA×VsyncGate/FIFO×ADPF×setFrameRate 声明）；历史 step×interval 双重计数已修（NativeSurfaceView.kt:1413-1417）。

**Renderer**

16. **Draw Call 合理？** 常态 3-5 次（全地图单批），非常合理。放置模式网格线放大到 ~100-240 次（P3-2），可优化非必须。
17. **地图过度绘制？** 无屏幕外绘制（钳制+精确剔除）；透明区域主要为精灵自身 alpha（图集批量绘制，开销可控）。整岛极小缩放时接近顶点上限并静默丢弃后加图层（§7，建议补日志）。
18. **Texture 重复上传？** 未发现运行时重复创建路径（图集单次/清晰度变更重建；无 destroyTexture 出口反而杜绝了反复上传）。surface 重建会全量重建（设计使然）。
19. **Shader/Pipeline 运行时重复创建？** 无。SPIR-V 构建期嵌入、Phase1 预创建、Pipeline Cache 跨会话持久化；resize 重建走缓存（毫秒级）。GLES program 在 init 时编译一次（无二进制缓存，2 个小程序，可忽略）。
20. **GPU 内存问题？** 预算明确（§5）：VBO 11.3MB + 图集 ~21MB + 离屏目标；无泄漏累积路径；resize 描述符池泄漏已修。

**工程**

21. **Debug/Release 一致？** 渲染路径一致：C++ 无 #ifdef 分叉，差异仅构建优化级别与 Kotlin DEBUG_MODE（§9）。CMake 未显式声明优化/LTO（P3-8，建议锁定）。
22. **生命周期安全？** 总体安全：纪元守卫/超时安全网/2s join 截止/不清 bitmap recycle/上下文线程修复。残留两处窄窗口（P2-3 resize vs 渲染线程、stopRenderThread join 失败跳过 release 的悬垂窗口——后者已有防御注释且有下一轮 initRenderer 重建兜底）。
23. **GPU 厂商兼容性风险？** 主要风险在策略层而非驱动层：旗舰黑名单过宽（P2-4）、量化阈值闭环失效（P2-1）、GPU 正则防线错位（P3-6）。C++ 层驱动适配记录充分（Adreno 队列重试/ASTC 对齐修复/Mali 内存回退 :1868-1875/Adreno 740 地面 quad 禁用）。
24. **错误吞异常？** 纪律良好：全捕获点均带日志与注释（`防御兜底` 体例）。少数静默点：clearSurface 吞异常（非关键，AndroidSurfaceProvider.kt:101）、GLES init 失败仅 LOGE 无持久化（并入 P2-5 修复）。
25. **缺乏日志无法定位？** 是——当前最大可观测性缺口：initRenderer 无错误码、GLES 无 GL_RENDERER、软件回退无独立日志/持久化（P2-5，任务书 §二十 的直接差距）。RenderHealth 每秒日志是好基础。

---

*报告结束。本轮未修改任何代码；修复项经确认后另行排期执行。*
