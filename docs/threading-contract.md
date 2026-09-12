# 线程安全契约（Threading Contract）

> 对标 Godot 官方 [Thread-safe APIs](https://docs.godotengine.org/en/4.0/tutorials/performance/thread_safe_apis.html) 文档。
> 本文档是本项目"哪些 API 从哪条线程可调"的**唯一成文权威**，审查清单 13.3 引用本文。
> 更新日期：2026-08-13。代码事实基线：GameEngineCore.kt / GameStateStoreImpl.kt / NativeSurfaceView.kt / RenderCommandBus.kt / GameEvents.kt / AudioEngine.kt。

---

## 一、线程清单

| 线程 | 调度器/来源 | 优先级 | 职责 |
|------|------------|--------|------|
| UI 主线程 | Android Main | — | Compose 重组、ViewModel、对话框、SurfaceView 触控、AudioEngine 调用（现状） |
| GameEngine-Thread | `GameDispatcher` 单线程 | MAX (-19) | 帧循环 `gameLoopMainLoop`、`stateStore.update` 事务（**唯一合法状态写入口**）、惰性结算全部系统 |
| RenderThread | `NativeSurfaceView` 手写线程 | — | `RenderBackend` 调用（setCamera/renderFrame/release）、VsyncGate 节拍、EWMA、图集上传 |
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

## 四、跨线程通信通道（全部合法通道）

| 通道 | 方向 | 语义 |
|------|------|------|
| `stateStore.update` | 任意入口 → 引擎线程派发 → 事务 | ReentrantLock 串行，唯一写路径 |
| `EventBus` | 引擎事务外 → 各订阅者 | 审计事件，溢出丢弃（批次 5 上报化） |
| `RenderCommandBus` | UI/引擎 → RenderThread | 单槽覆盖式（建筑数据最新值胜） |
| RenderFrame / 相机 @Volatile | Compose → RenderThread | 帧快照原子替换 |
| `StateFlow` 订阅 | GameStateStore → UI | UI 只读，写回必须经 GameEngine |

## 五、新增代码的必查项

新增跨线程交互时，先在本文件登记，再实现：
1. 新线程？→ 登记线程清单表
2. 新共享数据？→ 指定走哪条通道（表四），禁止私设共享 MutableStateFlow/ConcurrentHashMap
3. 新 API 从多线程调用？→ 判定白名单（表二）或禁止区（表三）
4. 新渲染特性 → RenderFrame 数据字段 + 双端消费（renderer-feature-checklist.md）
