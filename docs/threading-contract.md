# 线程安全契约（Threading Contract）

> 对标 Godot 官方 [Thread-safe APIs](https://docs.godotengine.org/en/4.0/tutorials/performance/thread_safe_apis.html) 文档。
> 本文档是本项目"哪些 API 从哪条线程可调"的**唯一成文权威**，审查清单 13.3 引用本文。
> 更新日期：2026-09-30——**装备系统重构线（EQ-B0–B5）核对声明：本批零新增线程、零新增跨线程通道**。装备升级/分解走既有 `tryExecuteNative`（ActionId 1486/1487，引擎线程 → C++ `equipment_tx.h` → `applyDirtyFromNative` 回镜像，表四既有行）；穿卸/自动装配走旬结既有路径；`RngPartition.EQUIPMENT(13)` 为既有 RNG 分区通道的数据面追加（非新通道）。前次 2026-09-28——**表四新增「离线收益」通道行**（实时结算线 B7：boot 折算 staging → ensure 尾部 consume → `nativeInjectOfflineGameMs`，报告流 `offlineReturnReport`；先登记再实现纪律履行）。更早 2026-09-23：**新增内存子系统四通道预登记**（`nativeMemoryTrim` / `textureAcquire` / `textureRelease` / MemoryStats 读通道，表一/表二/表三/表四标注「内存子系统」的条目；实现随 [memory-refactor 实施方案](memory-refactor-implementation-plan-2026-09-23.md) MR1–MR3，**登记先于实现**）。既有代码事实基线（2026-08-13）：GameEngineCore.kt / GameStateStoreImpl.kt / NativeSurfaceView.kt / RenderCommandBus.kt / GameEvents.kt / AudioEngine.kt。

---

## 一、线程清单

| 线程 | 调度器/来源 | 优先级 | 职责 |
|------|------------|--------|------|
| UI 主线程 | Android Main | — | Compose 重组、ViewModel、对话框、SurfaceView 触控、AudioEngine 调用（现状）；**内存子系统**：Android trim 回调接收（`onTrimMemory`/`onLowMemory` → Bridge 投递，只投递命令）+ 纹理上传编排 acquire/release 命令投递（现状 `AtlasAsyncPipeline` 上传段在主线程） |
| GameEngine-Thread | `GameDispatcher` 单线程 | MAX (-19) | 帧循环 `gameLoopMainLoop`、`stateStore.update` 事务（**唯一合法状态写入口**）、惰性结算全部系统；**内存子系统**：tick 边界 gamecore 容器收缩钩子（由 trim 水位驱动，禁止进入 JobSystem 并行段或结算中途） |
| RenderThread | `NativeSurfaceView` 手写线程 | — | `RenderBackend` 调用（setCamera/renderFrame/release）、VsyncGate 节拍、EWMA、图集上传；**内存子系统 GPU 面独占**：`TextureCache` 表、`GpuAllocator`、`nativeMemoryTrim`/`textureAcquire`/`textureRelease` 命令消费执行（帧边界） |
| backgroundDispatcher | 2 线程 | MIN+1 | 存档 IO（StorageEngine）、后台 Job、邮件 |
| Watchdog 线程 | 1 线程 | NORM | GameTimeProgressMonitor 采样、`emergencyRestartGameLoop` |
| assembleDispatcher | 专用单线程 | — | 锁外弟子组装 `dispatchAssemble`（增量组装防交错丢弟子） |
| 系统音频线程 | Android 内部 | — | SoundPool mixer / MediaPlayer 播放（Android 系统管理，见 audio-thread-audit.md） |

## 二、线程安全 API 白名单（安全区）

以下 API 可从**任意线程**调用（对应 Godot "Global Scope 单例全部线程安全"）：

| API | 说明 | 证据 |
|-----|------|------|
| `TimeSource` / `GameTimeClock` 读 | 纯函数时间查询 | `system/GameTimeClock.kt` |
| 状态快照读 | UI 持有的 `deepCopy` 旧快照引用，事务永不原地修改源存储 | 列级 COW（ComponentTable.adopt/ensureOwned） |
| `RenderCommandBus` 覆盖槽写 | SPSC 单槽覆盖式，@Volatile + AtomicBoolean，单写单读 | `feature/game/.../sect/RenderCommandBus.kt`（93 行） |
| `GameEngineCore.currentAlpha` 读 | @Volatile 引擎线程写 → Compose 帧内 UI 线程读（alphaProvider 快照），纯渲染契约、零状态回写（CurrentAlphaDeterminismGuardTest 锁定；对抗性审查 2026-08-13 逆向#5 登记） | `GameEngineCore.kt` / `MainGameScreen.kt` |
| RenderFrame / 相机 @Volatile 通道写 | 原子替换式帧快照通道 | `NativeSurfaceView.updateRenderState` |
| `EventBus` 发布 | Channel(256)，**必须在 stateStore.update 事务外 emit**（flushPendingEvents 模式） | `core/domain/.../event/GameEvents.kt` |
| `DomainLog` | 可注入日志抽象 | `core/domain` |
| `AudioEngine` | 全部调用限定主线程（现状）；SoundPool/MediaPlayer 内部线程安全 | `core/audio/AudioEngine.kt` |
| `GameRngManager.getRng` 快照读取 | 分区状态仅引擎线程推进，读快照安全 | `util/GameRngManager.kt` |
| **内存子系统**命令投递（`textureAcquire` / `textureRelease` / `nativeMemoryTrim` JNI） | 任意 Kotlin 线程投递命令即合法；cache 表变更与真实 GPU 操作仅渲染线程在帧边界执行 | 本文档表四（2026-09-23 登记，实现随 memory-refactor MR1–MR3） |
| **内存子系统**MemoryStats 快照读 | 渲染线程发布的不可变快照（原子引用替换），任意线程只读；禁止同步回读渲染后端（表三） | 本文档表四（2026-09-23 登记） |

## 三、线程安全禁止区（不安全）

对应 Godot "场景树不线程安全，跨线程用 call_deferred"：

| 禁止行为 | 理由 | 现状守卫 |
|---------|------|---------|
| 非引擎线程调用 `stateStore.update` | 单一写锁 + 确定性事务语义 | Debug 抛错 / Release 静默丢弃（批次 5 上报化） |
| EventBus 订阅回调内写状态 | 回调持有的是事务内快照，写状态必须回引擎线程 | flushPendingEvents 模式 |
| RenderThread 读 Compose 状态 | 渲染线程禁止触碰 Compose 对象（Compose 非线程安全） | 渲染数据全部经 RenderFrame/总线快照 |
| 引擎线程执行挂起 IO/网络 | 全链路非挂起原则（ReentrantLock 挂起不释放，会冻结世界） | EngineContextDispatcher |
| 绕过 COW 原地修改 `_discipleTables` | 原地修改绕过 set 不触发列私有化，污染共享存储破坏快照隔离 | knowledge-base Component Table 注意事项 |
| 向渲染后端请求数据回读 | 对标 Godot"回读会 stall 渲染线程"——渲染是单向推数据 | RenderBackend 接口无读接口 |
| **内存子系统**Kotlin 侧直触 `TextureCache` 表 / `GpuAllocator` / 直接调 GL·VK 分配释放 | cache 表与 GPU 面渲染线程独占（memory-refactor 全局约束 6）；Kotlin 只投递命令 | 本文档表四（2026-09-23 登记）；守卫随 MR2/MR3 落地 |
| **内存子系统**trim 回调线程做 GPU 操作或纹理重传 | trim 回调在主线程，重操作卡 UI 且与渲染线程竞争 cache 表 | memory-refactor D3 设计（收敛单入口 + 帧边界消费） |
| **内存子系统**gamecore 容器收缩进入 JobSystem 并行段或结算中途 | 破坏结算确定性与并行段数据安全；收缩仅限引擎线程 tick 边界 | memory-refactor 全局约束 6（tick 边界唯一） |

## 四、跨线程通信通道（全部合法通道）

| 通道 | 方向 | 语义 |
|------|------|------|
| `stateStore.update` | 任意入口 → 引擎线程派发 → 事务 | ReentrantLock 串行，唯一写路径 |
| `EventBus` | 引擎事务外 → 各订阅者 | 审计事件，溢出丢弃（批次 5 上报化） |
| `RenderCommandBus` | UI/引擎 → RenderThread | 单槽覆盖式（建筑数据最新值胜） |
| RenderFrame / 相机 @Volatile | Compose → RenderThread | 帧快照原子替换 |
| `StateFlow` 订阅 | GameStateStore → UI | UI 只读，写回必须经 GameEngine |
| **内存子系统**`nativeMemoryTrim(level)` | UI 主线程（Android trim 回调，经 Bridge 收敛单入口 + 双发去抖）→ JNI 投递 → RenderThread 帧边界消费；引擎线程 tick 边界读 trim 水位 | 命令投递式：Kotlin 侧只传档位枚举，回调线程禁止任何 GPU/纹理操作与重上传；C++ 渲染线程出队消费 GPU/CPU 资源面（`TextureCache.trim` / `trimHostPool`）；gamecore 容器收缩仅引擎线程 tick 边界（2026-09-23 登记，实现随 MR1/MR2） |
| **内存子系统**`textureAcquire(key, payload)` | Kotlin 上传编排线程（现状主线程）→ JNI 投递 → RenderThread 独占执行 | 命令投递式：miss → upload → insert 全在渲染线程完成，同 key 幂等（refCount++）；Kotlin 上传峰值后即时断开字节缓冲引用（2026-09-23 登记，实现随 MR3） |
| **内存子系统**`textureRelease(key)` | 同 `textureAcquire` | refCount--；==0 且非 pinned 入退役队列，帧边界物理销毁（沿 `m_retiredTextures` 延迟释放）；物理销毁完成前同 key 再 acquire 按 miss 重传（`pendingDestroy` 不命中）（2026-09-23 登记，实现随 MR3） |
| `tryExecuteNative`（ActionId 事务） | 引擎线程 → C++ `GameCore::execute` → 回执脏段 `applyDirtyFromNative` 回镜像 | 唯一稳态写入路径。**2026-09-25 新增在册动作**：`GACHA_FRAGMENT_GRANT_TX = 1870`（角色碎片入账 + 满 100 升星，零 RNG、零校验盲写；落点 `gamecore/src/dispatch_gacha.cpp` → `system/gacha_fragment.h::addFragment`）。Kotlin 侧 `GachaNativeTx` 走 `NativeEngineFlag.authoritative` 门控，门控关闭/桥未加载/信封失败时回退逐字同式的 `GachaFragmentLedger`（双实现契约，由 `DiffGachaFragmentTest` 与 C++ `gacha_fragment_test` 双向看护） |
| **内存子系统**MemoryStats 读通道（`GpuAllocator.stats` / cache 条目数） | RenderThread 帧边界发布 → 任意线程只读 | 渲染线程发布不可变快照（原子引用替换）；读方（Debug UI / 引擎线程 / gamecore）只读快照，禁止同步回读渲染后端（表三红线不变）、禁止持活引用跨帧（2026-09-23 登记，实现随 MR2/MR4） |
| **离线收益**`pendingOfflineGameMs`/`pendingOfflineWallMs`（结算改造 B7） | boot 编排（引擎线程 withEngineContext，`BootSequenceController` Step 6.5 `stageOfflineProgress` 折算写入）→ 引擎线程 `ensureAuthoritativeNative` 尾部 `consumePendingOfflineProgress` 消费（消费即清零，幂等） | 生产者与消费者实际同在引擎线程（boot 与游戏循环均引擎上下文串行）；`@Volatile` 仅为安全发布兜底。消费点调 `nativeInjectOfflineGameMs`（引擎线程，`jniRequireEngineThread` 守卫同 `nativeAccrue`）→ `applyDirtyFromNative` 镜像同步 + `accruedElapsedGameMs` 差分基准重锚。报告流 `offlineReturnReport`（StateFlow）引擎线程发布 → UI 收集展示后 ack 清空（2026-09-28 登记，实现随实时结算线 B7） |

## 五、新增代码的必查项

新增跨线程交互时，先在本文件登记，再实现：
1. 新线程？→ 登记线程清单表
2. 新共享数据？→ 指定走哪条通道（表四），禁止私设共享 MutableStateFlow/ConcurrentHashMap
3. 新 API 从多线程调用？→ 判定白名单（表二）或禁止区（表三）
4. 新渲染特性 → RenderFrame 数据字段 + 双端消费（renderer-feature-checklist.md）
