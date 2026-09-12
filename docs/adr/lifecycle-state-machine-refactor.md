# ADR: 游戏生命周期状态机重构 — BootPhase / RunState 分层设计

## 背景与目标

### 需求要点

当前 `GameLifecycle` 状态机：

```
UNINITIALIZED → DATA_READY → SYSTEMS_READY → MAP_READY → PLAYING
```

设计意图是 "单向 forward-only"（`transitionTo` 校验 `current.ordinal + 1 == state.ordinal`），但实际运行中超过一半的路径需要回退：

| 操作 | 实际路径 | 当前处理方式 |
|------|---------|-------------|
| 新游戏 | UNINITIALIZED → ... → PLAYING | `transitionTo` ✅ |
| 读档(首次) | UNINITIALIZED → ... → PLAYING | `transitionTo` ✅ |
| 读档(游戏中) | PLAYING → **回退** → DATA_READY → ... → PLAYING | `forceLifecycle` ⚠️ |
| 重启 | PLAYING → **回退** → UNINITIALIZED → PLAYING | `forceLifecycle` ⚠️ |
| 错误恢复 | (任意) → PLAYING | `forceLifecycle` ⚠️ |

**已暴露的问题：**

1. **Bug #1 已修复** — `loadGame()`/`loadGameFromSlot()` 在 PLAYING 时直接 `transitionTo(DATA_READY)` 抛 `Illegal lifecycle transition`
2. **两个真相源** — `GameLifecycle`（stateStore）+ `_isGameLoaded`（ViewModel），2026-07-13 的 Bug 直接根因就是二者不同步
3. **错误恢复路径不一致** — catch 块用 `forceLifecycle(PLAYING)` 跳过 MAP_READY，依赖生命周期的 UI 收到不一致的事件序列
4. **耦合** — 生命周期管理散落在 `SaveLoadViewModel(750+)` 的 `startNewGame`/`loadGame`/`loadGameFromSlot`/`restartGame` 四个方法中

### 成功标准

1. ✅ 消除 `forceLifecycle` 的全部调用（当前 4 处），所有路径使用语义清晰的 API
2. ✅ 消除 `_isGameLoaded` 独立标志位，统一为状态机表达
3. ✅ 错误恢复路径与正常路径共享同一套状态推进逻辑
4. ✅ ViewModel 不再直接管理生命周期切换，由专用控制器编排
5. ✅ 保持 Compose UI 层的 `gameLifecycle` 事件驱动不变
6. ✅ 测试覆盖率 ≥ 80%（新代码），零回归

---

## 技术方案

### 核心设计：BootPhase（单向启动序列）+ RunState（可循环运行时状态）

借鉴 Unity/Unreal/Godot 三引擎通用的 **Bootstrap → GameLoop → Dispose** 生命周期模型 [1][3]，以及 Supercell Titan 引擎"按需初始化，状态可重置"的设计哲学 [8]，将当前耦合在一起的两种语义拆分为两个独立维度：

#### 维度 1: `BootPhase` — 游戏启动序列（单向，只走一次）

```
UNINITIALIZED → DATA_READY → SYSTEMS_READY → MAP_READY → BOOT_COMPLETE
```

- 严格 forward-only，只能在 `BootSequenceController` 内部驱动
- `BOOT_COMPLETE` 是终态，启动完成后不再变化
- 供 UI 层观察 —— `mapReady` / `dataReady` 事件驱动过渡动画

#### 维度 2: `RunState` — 运行时状态（可循环）

```
IDLE ⇄ LOADING → PLAYING ⇄ RELOADING → PLAYING
```

- `IDLE` — 未加载任何存档/新游戏
- `LOADING` — 加载中（展示 LoadingScreen）
- `PLAYING` — 游戏中，正常 tick
- `RELOADING` — 重新加载中（从 PLAYING 回退，展示 LoadingScreen）

`RELOADING` 自动将 `BootPhase` 重置为 `UNINITIALIZED`，加载完成后由 `BootSequenceController` 重新推进到 `BOOT_COMPLETE`。

```
时序示例：游戏中读档

  RunState:    PLAYING ──→ RELOADING ───────→ LOADING ──→ PLAYING
                             │                  │
  BootPhase:  BOOT_COMPLETE  UNINITIALIZED ──→ DATA_READY ──→ ... ──→ BOOT_COMPLETE
```

### 与行业实践对标

| 来源 | 模式 | 与本方案的对标 |
|------|------|--------------|
| Unreal Engine `AGameMode` 内置 Match State [3] | EnteringMap → WaitingToStart → InProgress → WaitingPostMatch → LeavingMap (循环) | RunState 的 RELOADING → PLAYING 循环对标 LeavingMap → EnteringMap |
| Unity Modular Game Template FSM [5] | Bootstrap → Loading → GameHub → Gameplay (子 FSM) | BootPhase 对标 Bootstrap/Loading，RunState 对标 GameHub/Gameplay |
| iOS `UIApplication` + Scene Phase [7][22] | NotRunning → Active/Inactive/Background/Suspended | `pauseForBackground`/`resumeFromBackground` 映射到 iOS Background/Active |
| Godot Scene Tree State Management [1] | Bootstrap → GameLoop → Dispose 三阶段 | 直接对标 — BootPhase(Bootstrap) + RunState(GameLoop) |
| Tinder StateMachine DSL [14] | sealed class State/Event/SideEffect 类型安全 FSM | 状态/事件/副作用全部 sealed class，借鉴其类型安全设计 |
| Robert Nystrom State Pattern [15] | 每个状态一个类，Enter/Exit/Tick 方法 | BootPhase/RunState 专属控制器实现 Enter/Exit 钩子 |
| Genshin Impact 自定义文件加载系统 [18] | 定制化加载管线 + 空间网格卸载 | BootSequenceController 封装定制化加载序列 |
| Supercell Titan Engine [8] | 按需初始化，无多余服务启动 | lazy init + deferred init：仅当前所需服务 |
| MVS (Model View State Machine) [11] | 纯状态机替换 boolean flags | 用 RunState + BootPhase 替换 `_isGameLoaded` + `forceLifecycle` |
| lite-states FSM [21] | 严格转换 + force() 兜底 + 生命周期钩子 | `transitionTo` = 严格转换，`forceLifecycle` 仅在 BootSequenceController 内部 |
| 层次化 FSM + Stack [20] | 状态栈 + 持久层分离 | RunState(栈) + BootPhase(持久帧) 分离 |

### 关键类/接口

```kotlin
// ====== 1. 核心状态定义 (core/domain) ======

/** 游戏启动序列 — 单向 forward-only，启动完成后凝固 */
enum class BootPhase {
    UNINITIALIZED,
    DATA_READY,
    SYSTEMS_READY,
    MAP_READY,
    BOOT_COMPLETE  // 新：终态，语义明确
}

/** 运行时状态 — 可循环，有明确的 reload 语义 */
enum class RunState {
    IDLE,
    LOADING,
    PLAYING,
    RELOADING
}

// ====== 2. GameStateStore 新增接口 (core/engine -> state) ======

interface GameStateStore {
    val bootPhase: StateFlow<BootPhase>       // 替换 gameLifecycle 的 forward-only 部分
    val runState: StateFlow<RunState>          // 新增：运行时状态

    /** 由 BootSequenceController 调用的 boot phase 推进 */
    fun advanceBootPhase()

    /** 重置 boot phase（仅在 RELOADING 入口调用一次） */
    fun resetBootPhase()

    // 旧 API 兼容（委托给 runState）：
    val gameLifecycle: StateFlow<GameLifecycle>  // 保留，映射 runState
    fun transitionTo(state: GameLifecycle)       // 改为委托 advanceBootPhase
    fun forceLifecycle(state: GameLifecycle)     // 移除 ⛔
}

// ====== 3. BootSequenceController (new) ======

/**
 * 启动序列控制器 — 统一编排加载流程。
 *
 * 职责：
 * 1. 接受 "boot from slot" / "boot new game" 指令
 * 2. 推进 BootPhase: UNINITIALIZED → ... → BOOT_COMPLETE
 * 3. 管理 RunState 切换（IDLE → LOADING → PLAYING）
 * 4. 统一错误恢复
 *
 * ┌─ 正常路径 ─────────────────────────┐
 *  loadGame() → BootSequenceController.boot()
 *    ├─ RunState: IDLE → LOADING
 *    ├─ BootPhase: UNINITIALIZED → DATA_READY → ... → BOOT_COMPLETE
 *    └─ RunState: LOADING → PLAYING
 *
 * ┌─ 游戏中读档 ───────────────────────┐
 *  loadGame() → BootSequenceController.boot()
 *    ├─ RunState: PLAYING → RELOADING
 *    ├─ resetBootPhase() (→ UNINITIALIZED)
 *    ├─ RunState: RELOADING → LOADING
 *    ├─ BootPhase: UNINITIALIZED → ... → BOOT_COMPLETE
 *    └─ RunState: LOADING → PLAYING
 */
class BootSequenceController @Inject constructor(
    private val stateStore: GameStateStore,
    private val storageFacade: StorageFacade,
    private val resourcePreloader: ResourcePreloader,
    private val gameEngineCore: GameEngineCore,
    private val gameEngine: GameEngine,
    private val buildingConfigService: BuildingConfigService,
    private val gameRngManager: GameRngManager,
    private val discipleSnapshotCache: DiscipleSnapshotCache
) {
    /**
     * 统一启动入口。无论是首次加载还是 reload，都走此路径。
     *
     * @param slot 存档槽位
     * @param loadSource 加载来源（NEW_GAME / LOAD_GAME / RESTART）
     * @param onProgress 进度回调
     * @param onPhase 阶段标签回调（UI 用）
     * @return Result<Unit> 加载结果
     */
    suspend fun boot(
        slot: Int,
        loadSource: LoadSource,
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit
    ): Result<Unit>

    /** 游戏循环启动（抽取为独立方法便于测试） */
    fun startGameLoop()

    /** 游戏循环停止 */
    fun stopGameLoop()

    /** 地图预加载数据生成 */
    suspend fun generateMapPreloadData(): MapPreloadData

    /** 加载来源分类 */
    enum class LoadSource { NEW_GAME, LOAD_GAME, RESTART }
}

// ====== 4. SaveLoadViewModel 重构后 ======

/**
 * 重构后职责：用户交互编排 + 进度展示。
 * 不再管理生命周期，不再维护 _isGameLoaded。
 * 生命周期切换全部委托 BootSequenceController。
 */
@HiltViewModel
class SaveLoadViewModel @Inject constructor(
    private val bootController: BootSequenceController,
    private val saveDelegate: SaveLoadSaveDelegate,
    private val loadDelegate: SaveLoadLoadDelegate,
    private val pauseDelegate: SaveLoadPauseDelegate,
    private val stateDelegate: SaveLoadStateDelegate,
    ...
) : BaseViewModel() {

    val runState: StateFlow<RunState> = stateStore.runState
    val bootPhase: StateFlow<BootPhase> = stateStore.bootPhase
    val isLoading: StateFlow<Boolean> = runState.map {
        it == RunState.LOADING || it == RunState.RELOADING
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun loadGame(slot: SaveSlot) {
        viewModelScope.launch(Dispatchers.IO) {
            bootController.boot(
                slot = slot.slot,
                loadSource = BootSequenceController.LoadSource.LOAD_GAME,
                onProgress = { _loadingProgress.value = it },
                onPhase = { _preloadPhase.value = it }
            ).onFailure { error ->
                showError("加载游戏失败: ${error.message}")
            }
        }
    }

    fun startNewGame(sectName: String, slot: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            gameEngine.createNewGame(sectName, slot)
            bootController.boot(...)
        }
    }

    // 不再有 _isGameLoaded，不再有 forceLifecycle
    // → 约 200 行净减少
}
```

### 数据流

```
User Action (点击读档/新游戏/重启)
    │
    ▼
SaveLoadViewModel
    │ 委托
    ▼
BootSequenceController.boot(slot, loadSource, onProgress)
    │
    ├── 1. RunState: IDLE/PLAYING → RELOADING (若需要)
    ├── 2. stateStore.resetBootPhase() → BootPhase = UNINITIALIZED
    ├── 3. RunState: RELOADING → LOADING
    │
    ├── 4. Load data from StorageFacade (或 createNewGame)
    ├── 5. BootPhase: UNINITIALIZED → DATA_READY
    ├── 6. Preload resources
    ├── 7. BootPhase: DATA_READY → SYSTEMS_READY
    ├── 8. Start game loop
    ├── 9. BootPhase: SYSTEMS_READY → MAP_READY
    ├── 10. Generate map preload data
    ├── 11. BootPhase: MAP_READY → BOOT_COMPLETE
    │
    └── 12. RunState: LOADING → PLAYING

UI 层观察：
    - bootPhase: StateFlow → 过渡动画/LoadingScreen 阶段展示
    - runState: StateFlow → MainGameScreen / LoadingScreen 切换
```

### 过渡方案（原有 GameLifecycle 兼容）

为确保已有代码逐步迁移，`GameLifecycle` 在过渡期内保留为映射层：

```kotlin
// GameStateStoreImpl 兼容层
val gameLifecycle: StateFlow<GameLifecycle> = combine(
    _bootPhase, _runState
) { boot, run ->
    when {
        run == RunState.PLAYING && boot == BootPhase.BOOT_COMPLETE -> GameLifecycle.PLAYING
        boot.ordinal >= BootPhase.MAP_READY.ordinal -> GameLifecycle.MAP_READY
        boot.ordinal >= BootPhase.SYSTEMS_READY.ordinal -> GameLifecycle.SYSTEMS_READY
        boot.ordinal >= BootPhase.DATA_READY.ordinal -> GameLifecycle.DATA_READY
        else -> GameLifecycle.UNINITIALIZED
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, GameLifecycle.UNINITIALIZED)

// @Deprecated — 由 BootSequenceController 统一管理
@Deprecated("Use BootSequenceController.boot() instead")
override fun transitionTo(state: GameLifecycle) { ... }

// 移除
@Deprecated("No longer needed — BootSequenceController handles reset")
override fun forceLifecycle(state: GameLifecycle) {
    DomainLog.w(TAG, "forceLifecycle deprecated call: → $state")
    // 实际行为委托给 resetBootPhase + advanceBootPhase
}
```

### iOS 跨平台兼容性分析

| 设计元素 | Android 实现 | iOS 对等方案 | 兼容性 |
|---------|-------------|-------------|--------|
| `BootPhase` | enum class | enum (Swift) | ✅ 一致 |
| `RunState` | enum class | enum (Swift) | ✅ 一致 |
| `BootSequenceController` | Kotlin class + coroutines | Swift actor + async/await | ✅ 等效 |
| `StateFlow<BootPhase>` | StateFlow (kotlinx) | `@Published var` + Combine | ✅ 需桥接 |
| 生命周期回调 | `UIApplication`/`SceneDelegate` → `pauseForBackground`/`resumeFromBackground` | `UIApplicationDelegate` → `sceneWillResignActive`/`sceneDidBecomeActive` [7][22] | ✅ 语义一致 |

BootPhase/RunState 枚举定义可抽到 `:core:domain` 模块（纯 Kotlin，零 Android 依赖），iOS 通过 KMP 共享枚举定义。

---

## 影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| **新增文件** | | |
| `core/engine/.../BootSequenceController.kt` | ✨ 新增 | 启动序列控制器，封装加载编排逻辑 |
| `core/engine/.../BootSequenceControllerTest.kt` | ✨ 新增 | 单元测试 |
| | | |
| **核心修改** | | |
| `core/domain/.../GameLifecycle.kt` | ✏️ 重构 | 拆分为 `BootPhase` + `RunState` + 保留 `GameLifecycle` 映射枚举 |
| `core/engine/.../state/GameStateStore.kt` | ✏️ 修改 | 接口新增 `bootPhase`/`runState`/`advanceBootPhase`/`resetBootPhase`；标记 `forceLifecycle` 废弃 |
| `core/engine/.../state/GameStateStoreImpl.kt` | ✏️ 修改 | 新增 `_bootPhase`/`_runState` MutableStateFlow；`gameLifecycle` 改为二者组合映射；`forceLifecycle` 实现改为委托 |
| | | |
| **ViewModel** | | |
| `feature/game/.../SaveLoadViewModel.kt` | ✏️ 重构 | 移除 `_isGameLoaded`（-1 字段 -10+ 引用）；`loadGame`/`loadGameFromSlot`/`startNewGame`/`restartGame` 委托 `BootSequenceController.boot()`；catch 块统一使用 `BootSequenceController` 的 recover 机制 |
| `feature/game/.../GameViewModel.kt` | ✏️ 修改 | `gameLifecycle` 引用兼容 `runState` 映射 |
| | | |
| **UI 层** | | |
| `feature/game/.../GameActivity.kt` | ✏️ 修改 | `gameLifecycle.collectAsState()` → `runState.collectAsState()` 决定 LoadingScreen/MainGameScreen |
| `feature/game/.../MainGameScreen.kt` | 🔍 检查 | `gameLifecycle` 引用改用 `bootPhase` |
| `ui/game/.../LoadingScreen.kt` | 🔍 检查 | `bootPhase` 阶段标签映射 |
| | | |
| **Delegate** | | |
| `SaveLoadLoadDelegate.kt` | 🔍 检查 | 确认不需要生命周期操作，保持纯数据功能 |
| `SaveLoadRestartDelegate.kt` | 🔍 检查 | 同上 |
| `SaveLoadStateDelegate.kt` | 🔍 检查 | 同上 |
| | | |
| **测试** | | |
| `GameStateStoreLifecycleTest.kt` | ✏️ 修改 | 适配 RunState/BootPhase 新状态；为 `forceLifecycle` 废弃调用添加 suppress |
| `GameLifecycleTransitionTest.kt` | ✏️ 修改 | 添加 RunState 转换测试、RELOADING 全路径测试 |
| `SaveLoadViewModelLoadTest.kt` | ✏️ 修改 | 注入 `FakeBootSequenceController`，验证 reload 路径不抛异常 |
| `GameEngineCoordinationTest.kt` | ✏️ 修改 | 更新生命周期断言使用新 API |
| `BootSequenceControllerTest.kt` | ✨ 新增 | 覆盖：首次加载成功、reload 成功、加载失败 recover、map 预加载失败、取消路径 |
| | | |
| **兼容层（迁移用）** | | |
| 所有访问 `gameLifecycle`/`transitionTo`/`forceLifecycle` 的现有代码 | 🔍 保留 | 兼容层确保零改动，标注 @Deprecated 逐步迁移 |

### 变更统计

- **新增**: 2 文件（BootSequenceController + Test），约 350 行
- **修改**: 12+ 文件，净减少约 200 行（主要来自 SaveLoadViewModel）
- **删除**: 0 文件（兼容层保留 @Deprecated API）

---

## 兼容性分析

### Migration

- 🟢 **无缝** — 纯运行时状态重构，不涉及数据库 Entity/ProtoBuf 格式变更
- `GameLifecycle` 枚举保留，仅新增 `BootPhase`/`RunState` 枚举
- `gameLifecycle` StateFlow 通过组合保持输出兼容，所有现有 UI 层 `collectAsState()` 不受影响

### 序列化

- 🟢 **无变更** — 生命周期是纯运行时状态，不随存档保存

### 存档兼容性

- 🟢 **无变更** — 不涉及存档格式

### 旧代码兼容

- 🟢 `transitionTo(state: GameLifecycle)` 保留，实现改为委托 `advanceBootPhase`
- 🟡 `forceLifecycle` 保留但标记 `@Deprecated`，当前调用方（catch 块、restartGame）迁移到 `BootSequenceController` 后移除

---

## 测试方案

### 单元测试

```kotlin
@RunWith(RobolectricTestRunner::class)
class BootSequenceControllerTest {

    @Test
    fun `boot - first time - goes through full boot phase sequence`() = runTest {
        // Given: RunState = IDLE, BootPhase = UNINITIALIZED
        // When: boot(LoadSource.LOAD_GAME)
        // Then: BootPhase: UNINITIALIZED → DATA_READY → SYSTEMS_READY → MAP_READY → BOOT_COMPLETE
        // Then: RunState: IDLE → LOADING → PLAYING
    }

    @Test
    fun `boot - from PLAYING - resets boot phase and reloads`() = runTest {
        // Given: RunState = PLAYING, BootPhase = BOOT_COMPLETE
        // When: boot(LoadSource.LOAD_GAME)
        // Then: RunState: PLAYING → RELOADING
        // Then: BootPhase: BOOT_COMPLETE → UNINITIALIZED
        // Then: RunState: RELOADING → LOADING
        // Then: BootPhase: UNINITIALIZED → ... → BOOT_COMPLETE
        // Then: RunState: LOADING → PLAYING
    }

    @Test
    fun `boot - game engine init fails - recovers via RunState fallback`() = runTest {
        // Given: loadData throws IOException
        // When: boot()
        // Then: BootSequenceController calls onFailure with IOException
        // Then: RunState reverts to previous state (IDLE or PLAYING)
    }

    @Test
    fun `boot - cancelled mid-load - RunState remains LOADING`() = runTest {
        // Given: CancellationException thrown during preloadGameResources
        // When: boot()
        // Then: CancellationException propagated, RunState stays in LOADING
    }

    @Test
    fun `boot - ERROR path with partial data - starts game loop`() = runTest {
        // Given: loadData throws but gameEngine has partial data (sectName + disciples)
        // When: boot()
        // Then: BootSequenceController.recoverWithPartialData() called
        // Then: forceLifecycle(PLAYING) is NOT called — replaced by explicit
        //      runState = PLAYING + bootPhase = BOOT_COMPLETE
    }

    @Test
    fun `generateMapPreloadData - returns valid MapPreloadData`() = runTest {
        // Given: gameEngine with valid mapSeed
        // When: generateMapPreloadData()
        // Then: returns MapPreloadData with populated tileData
    }
}
```

### 对抗性审查要点

| 角色 | 审查维度 | 关注点 |
|------|---------|--------|
| 边界狂魔 | 状态转换边界 | RunState/BootPhase 所有组合 + 非法转换拒绝 |
| 状态破坏者 | 并发 | 快速连续读档/重启/新游戏 3 次，确保 RunState 序列不乱 |
| 状态破坏者 | 中断恢复 | LOADING 中取消协程，重新发起 boot，确保状态一致性 |
| 数据篡改者 | 错误恢复 | 存储损坏、资源预加载失败、地图生成失败等错误路径 recover |
| 逆向工程师 | 兼容层绕过 | 旧代码通过 `transitionTo` 绕过 `BootSequenceController` 直接推进 boot phase |

---

## 风险评估与兜底

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| UI 层引用的 `gameLifecycle` 在新映射下行为偏移 | 中 | 中 | 综合映射 + 测试全覆盖 + 灰度对比 |
| 旧代码通过 `transitionTo` 直接推进 BootPhase（绕过 Controller） | 低 | 低 | `transitionTo` 保留为委托调用，标记 @Deprecated，无行为变化 |
| catch 块中 `forceLifecycle(PLAYING)` 替换后行为不一致 | 中 | 中 | BootSequenceController 提供显式的 `recoverWithPartialData()` 方法，行为与旧 catch 块一致 |
| `_isGameLoaded` 移除后逻辑遗漏 | 低 | 高 | 逐一审计每个引用点（~10 处），替换为 `runState == PLAYING` 或 `bootPhase == BOOT_COMPLETE` |
| 新增 BootSequenceController 排错成本 | 低 | 低 | 充分的日志 + 每个步骤标注阶段名，错误信息包含 slot/phase/elapsed |

### 兜底方案

如果 `BootPhase`/`RunState` 分层引入问题，可在过渡期维持兼容层输出不变的前提下，逐步迁移调用方：

1. **Phase 1** — 只做内部拆分（新增 `_bootPhase`/`_runState`），`gameLifecycle` 映射不变，对外无感知
2. **Phase 2** — 提取 `BootSequenceController`，`SaveLoadViewModel` 逐步委托
3. **Phase 3** — 移除 `_isGameLoaded` 和 `forceLifecycle`（4 处调用全部迁移后）

当前方案直接输出 Phase 3 的最终态，但保留了 Phase 1 的兼容层作为回退点。

---

## 行业参考来源清单

| # | 等级 | 来源 | 核心摘要 | URL / 出处 | 日期 |
|---|------|-----|---------|-----------|------|
| 1 | S | Bezditnyi & Chebanyuk 2024 — 三引擎通用生命周期模型 | Bootstrap → GameLoop → Dispose 三阶段，State + Service Locator + DI | [ceur-ws.org](https://ceur-ws.org/Vol-3806/S_46_Bezditnyi_Chebanyuk.pdf) | 2024 |
| 2 | S | Robert Nystrom — Game Programming Patterns | Game Loop (固定步长/可变步长) + State Pattern (每个状态一个类, Enter/Exit/Tick) | [gameprogrammingpatterns.com](https://gameprogrammingpatterns.com/) | 2014 |
| 3 | S | Unreal Engine 5.6 — Game Mode & Game State | 内置 Match State FSM: EnteringMap → WaitingToStart → InProgress → WaitingPostMatch → LeavingMap (循环) | [dev.epicgames.com](https://dev.epicgames.com/documentation/unreal-engine/game-mode-and-game-state-in-unreal-engine?application_version=5.6) | 2025 |
| 4 | S | Apple — Managing Your App's Life Cycle | iOS 5 状态: NotRunning/Active/Inactive/Background/Suspended; Scene Phase (iOS 13+) | [developer.apple.com](https://developer.apple.com/documentation/uikit/managing-your-app-s-life-cycle) | 2025 |
| 5 | S | NVIDIA — Android Lifecycle Recommendations for Games | surfaceCreated/surfaceChanged/onResume+Focus → 渲染; EGLContext 存活策略 | [developer.nvidia.com](https://developer.download.nvidia.com/assets/mobile/files/AndroidLifecycleAppNote_v100.pdf) | 2023 |
| 6 | S | Google Android — App Startup Time | Cold/Warm/Hot Start 三态; TTID/TTFD 指标; reportFullyDrawn() | [developer.android.com](https://developer.android.com/topic/performance/vitals/launch-time) | 2025 |
| 7 | A | Supercell — The Engine Behind Every Supercell Game (Titan Engine) | 自研引擎, 按需初始化, 全覆盖低端设备 | [supercell.com](https://supercell.com/en/news/game-engine-called-titan/) | 2024 |
| 8 | A | Supercell — Real-Time Persisted Events with ScyllaDB | 状态分层 (APIs → Proxies → Event Routing → DB); 去中心化状态管理 | [scylladb.com](https://www.scylladb.com/2025/01/14/how-supercell-handles-real-time-persisted-events-with-scylladb/) | 2025-01 |
| 9 | A | Supercell — GDC Vault: Modernizing Rendering at Supercell | 渲染管线现代化; 双后端渲染 (Vulkan + Canvas) | [gdcvault.com](https://gdcvault.com/play/1028820/) | 2024 |
| 10 | A | Tinder — StateMachine (Kotlin/Swift DSL) | sealed class State/Event/SideEffect 类型安全 FSM; 生产验证 | [github.com/Tinder/StateMachine](https://github.com/Tinder/StateMachine) | 2023 |
| 11 | A | Gamespot — Genshin Impact PS5 Custom File-Loading | 自建文件加载系统 + 图形库; 针对 SSD 优化加载管线 | [gamespot.com](https://www.gamespot.com/articles/mihoyo-reveals-more-details-about-genshin-impact-on-ps5/1100-6490456/) | 2021 |
| 12 | A | Genshin Impact UGC — FOV Grid-Based Loading | 空间网格 (40m²) + FOV 检测; 本地销毁 vs 实体销毁分层 | [act.hoyoverse.com](https://act.hoyoverse.com/ys/ugc/tutorial/detail/mhlb1vivioys) | 2024 |
| 13 | A | Modular Unity Game Template — FSM-Driven Loading | Zenject + Addressables + UniTask; Bootstrap → Loading → GameHub → Gameplay | [github.com/NintendaDev/modular-unity-game-template](https://github.com/NintendaDev/modular-unity-game-template) | 2025 |
| 14 | A | codecentric — iOS App State Machine | 7 状态 (拆分 Inactive 为 ResignActive + WakeUp); State Pattern + UIApplicationDelegate | [codecentric.de](https://www.codecentric.de/en/knowledge-hub/blog/handling-ios-app-states-state-machine) | 2024 |
| 15 | B | ProAndroidDev — Model View State Machine (MVS) | 纯 FSM 替代 MVVM boolean flags; 副作用一致性; strict transitions | [proandroiddev.com](https://proandroiddev.com/model-view-state-machine-mvs-7dc371275b60) | 2024 |
| 16 | B | Thoughtbot — Finite State Machines + Android + Kotlin | Kotlin sealed class FSM 实现; side effect 抽象 | [thoughtbot.com](https://thoughtbot.com/blog/finite-state-machines-android-kotlin-good-times) | 2024 |
| 17 | B | Game Code School — Kotlin Game FSM | Star Defender 实例; Update() 方法 + 状态驱动 | [gamecodeschool.com](https://gamecodeschool.com/learning-kotlin/chapter-28.html) | 2024 |
| 18 | B | Snap.Hutao — Genshin Impact Game Launch System | MVVM + handler pipeline; FPS 解锁 + 路径管理 | [deepwiki.com](https://deepwiki.com/DGP-Studio/Snap.Hutao/2.1-game-launch-system) | 2025 |
| 19 | B | BetterGenshinImpact — State Machine Framework | StateMachineBase<TState,TContext>; StateDetector/StateHandler attributes; retry/timeout | [deepwiki.com](https://deepwiki.com/babalae/better-genshin-impact/3.9-state-machine-framework-and-stygian-onslaught) | 2025 |
| 20 | B | NWPU GameDev — Hierarchical FSM with Stack | 双栈 (Regular + Persistent); 5 阶段生命周期; 推迟转换 | [deepwiki.com](https://deepwiki.com/konakona418/nwpu-gamedev-2025/7-game-state-system) | 2025 |
| 21 | B | lite-states — <1KB FSM Library | strict transitions + force() + onEnter/onLeave hooks | [npmjs.com/package/lite-states](https://www.npmjs.com/package/lite-states) | 2024 |
| 22 | B | Supercell Reverse Engineering — Core Instrumentation | TCP/HTTP2 persistent sockets; PepperCrypto; topic-based state subscriptions | [deepwiki.com](https://deepwiki.com/soufgameyt/Supercell-Reverse-Engineering/2-core-instrumentation-architecture) | 2025 |

**来源等级分布：** S 级 6 条 / A 级 8 条 / B 级 8 条，总计 22 条（S+A 占 64%，超 12 条下限）。

---

## 实施步骤

```
Phase 1: 内部拆分（不影响外部行为）
  1. 新增 BootPhase / RunState 枚举
  2. GameStateStoreImpl 新增 _bootPhase/_runState StateFlow
  3. gameLifecycle 改为组合映射
  4. transitionTo/forceLifecycle 改为委托调用
  5. ✅ 验证：全测试通过，编译通过

Phase 2: 提取 BootSequenceController
  1. 新建 BootSequenceController（接管加载编排）
  2. SaveLoadViewModel 注入 BootSequenceController
  3. 迁移 loadGame/loadGameFromSlot → bootController.boot()
  4. 迁移 startNewGame/restartGame → bootController.boot()
  5. ✅ 验证：全路径测试通过，功能一致

Phase 3: 清理
  1. 移除 _isGameLoaded（替换为 runState 查询）
  2. 移除 forceLifecycle 调用（4 处全部迁移）
  3. 移除 SaveLoadViewModel 中不再需要的 catch 块恢复逻辑
  4. 标记/移除 @Deprecated API（根据使用情况）
  5. ✅ 验证：全测试通过，覆盖每旬回归测试
```
