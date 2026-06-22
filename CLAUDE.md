# CLAUDE.md

## 用户公约（产品经理思维）

本用户不懂技术，需求描述未必清晰、未必使用专业术语。

### AI 行为规范
1. **产品经理思维优先** — 收到需求后，先用业务语言复述确认理解，再转化为技术方案落地
2. **听不懂必须问** — 理解不了的地方立即中断，向用户提问确认，不得猜测需求
3. **提问要精准** — 简洁、直接、给出选项，不要让用户解释技术细节
4. **翻译是 AI 的工作** — 不要期待用户提供字段名、接口文档、技术规范；听完业务描述后 AI 自己查
5. **不要闷头干** — 不确定时先问，别等做完了才发现方向错了
6. **因果链确凿** — 定位问题时，必须从症状追溯到根因，每一步因果关系都要能说清楚。禁止仅凭相关性就下结论，必须有直接证据链
7. **举一反三排查** — 定位到问题后、动手修复前，先搜索代码库中是否存在同类模式的其他问题，一并纳入修复方案，再统一实施
8. **默认使用中文** — 所有回复、注释、commit message、文档均使用中文，除非涉及代码标识符或技术术语无合适翻译

---

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / Test / Lint

All commands run from the `android/` directory using the Gradle wrapper:

```bash
# Compile check (fast feedback — do this after every change)
cd android && ./gradlew.bat compileReleaseKotlin

# Build release APK
cd android && ./gradlew.bat assembleRelease

# Build debug APK
cd android && ./gradlew.bat assembleDebug

# Run all unit tests (Robolectric + JUnit)
cd android && ./gradlew.bat test

# Run a single test class
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest"

# Lint
cd android && ./gradlew.bat lintRelease

# Clean build (when KSP incremental cache breaks with NoSuchFileException *_Impl.java)
cd android && ./gradlew.bat clean
```

Tests live in `android/app/src/test/`. They use JUnit 4, Mockito, Robolectric, and `kotlinx-coroutines-test`. Robolectric tests need `includeAndroidResources = true`.

## Tech Stack

- **Language**: Kotlin 2.0.21, JVM target 17
- **UI**: Jetpack Compose with Material3 (BOM 2025.02.00), no XML layouts
- **DI**: Hilt 2.56 (`@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`)
- **Database**: Room 2.6.1 with KSP annotation processing; single shared DB file (`xianxia_sect.db`) for all save slots
- **Serialization**: Kotlinx Serialization (JSON + Protobuf + CBOR)
- **Storage**: MMKV (fast K-V), DataStore (preferences), LZ4/Zstd (compression)
- **Network**: Retrofit + OkHttp with Gson
- **Auth**: TapTap SDK (login, compliance, analytics)
- **Build**: AGP 8.8.0, Gradle with Aliyun mirrors for China

## Architecture: Two-Layer State Model

```
┌──────────────────────────────────────────────────┐
│ Layer 2: UI (ViewModel + Compose)                │
│   - Subscribes to GameStateStore.unifiedState    │
│   - Dialogs managed by DialogStateManager        │
├──────────────────────────────────────────────────┤
│ Layer 1: GameEngineCore + GameEngine             │
│   - EngineCore: game loop (200ms tick)           │
│   - Engine: business logic (cultivation, battle, │
│     production, diplomacy, exploration, etc.)    │
│   - Writes to GameStateStore._state MutableFlow  │
└──────────────────────────────────────────────────┘
```

### Data Flow

```
User Action (Compose UI)
  → ViewModel calls GameEngine method
    → GameEngine delegates to Service (e.g., CombatService)
      → Service reads from / writes to GameStateStore._state
        → StateFlow emits new UnifiedGameState
          → ViewModel.collectAsState() triggers recomposition
            → Compose UI renders updated state
```

- **GameEngine** is the single entry point for all state mutations from the UI layer. ViewModels never write to `GameStateStore` directly.
- **GameEngineCore** runs on a 200ms tick via `SystemManager`, driving autonomous systems (TimeSystem, BuildingSubsystem, etc.) that also write to `GameStateStore`.
- **GameStateStore** is the single source of truth — one `MutableStateFlow<UnifiedGameState>` containing all game state. Individual `StateFlow` projections are derived via `.map {}`.

### Key Source Directories

**Core — Game logic, state, and static data**
| Directory | Purpose |
|-----------|---------|
| `core/engine/` | Game loop (200ms tick), services, systems, production, scheduling |
| `core/engine/service/` | Per-domain services (Disciple, Combat, Cultivation, Diplomacy, Building, Event, Exploration, etc.) |
| `core/engine/system/` | ECS-like systems: Inventory, Building, Time, SystemManager |
| `core/model/` | Data classes: GameData (Room Entity), Disciple, Items, Equipment, etc. |
| `core/state/` | GameStateStore (central state), UnifiedGameState, UnifiedGameStateManager |
| `core/registry/` | Static game data: Equipment, Manuals, Herbs, ForgeRecipes, Items |
| `core/config/` | JSON-driven config: buildings, gifts, diplomatic events, inventory |

**Data — Storage, serialization, and persistence**
| Directory | Purpose |
|-----------|---------|
| `data/` | Storage layer: Room DB, serialization, compression, encryption, WAL, backup |
| `data/facade/` | StorageFacade — single external API for save/load/delete |
| `data/engine/` | StorageEngine — internal storage orchestration |
| `data/local/` | Room database, DAOs, migrations, type converters |

**UI — Compose screens, components, and theming**
| Directory | Purpose |
|-----------|---------|
| `ui/game/` | Game screens, ViewModels (one per feature), dialogs |
| `ui/game/tabs/` | Tab content: Disciples, Buildings, Warehouse, Settings |
| `ui/game/map/` | World map (Compose Canvas), markers, camera |
| `ui/components/` | Shared Compose components (GameButton, ItemCard, DialogManager) |
| `ui/theme/` | Colors, typography, shapes, button sizes |

**Infrastructure — DI, networking, and third-party SDKs**
| Directory | Purpose |
|-----------|---------|
| `di/` | Hilt modules: AppModule, CoreModule, RepositoryModule, StorageModule |
| `network/` | Retrofit API interfaces |
| `taptap/` | TapTap SDK wrappers (auth, compliance) |

### Key Classes

- **`GameEngineCore`** — Game loop controller. `start()`/`stop()`/`tick()` at 200ms intervals. Delegates to `SystemManager` which runs registered systems (TimeSystem, BuildingSubsystem, etc.) in priority order.
- **`GameEngine`** — Facade over all game logic. Injected into ViewModels. Orchestrates services and writes results to `GameStateStore`.
- **`GameStateStore`** — Single `MutableStateFlow<UnifiedGameState>`. All game state (disciples, items, events, etc.) lives in one `UnifiedGameState` object. Individual `StateFlow` projections derived via `.map {}`.
- **`GameViewModel`** — Primary ViewModel (Hilt). Bridges UI to engine. Owns `DialogStateManager`.
- **`MainGameScreen`** — Tab-based layout: OVERVIEW, DISCIPLES, BUILDINGS, WAREHOUSE, SETTINGS. No Jetpack Navigation — everything is in one screen with dialog overlays.
- **`GameData`** — Room `@Entity` for the core save row. Primary keys: `(id, slot_id)`.

### Component Table Architecture (v4.0.01)

Disciple entities are stored in `DiscipleTables` — a collection of ~90 narrow `ComponentTable`/`IntComponentTable`/`DoubleComponentTable` columns. Each column is a `SparseArray` keyed by disciple ID (Int). Other entity types (Equipment, Pills, Manuals, Herbs, etc.) use `EntityStore<T : HasId>` which wraps `List<T>` with a `HashMap<String, T>` index.

**Key rules:**
- **Disciple updates**: Write directly to `tables.loyalty[id] = 90` — O(log n), no allocation
- **Disciple reads**: `tables.names[id]`, `tables.realms[id]` — O(log n)
- **Disciple assembly**: `tables.assemble(id)` creates a full `Disciple` data class — ONLY for UI/Serialization, NEVER in hot path
- **Non-Disciple lookup**: `entityStore.get(id)` — O(1)
- **Non-Disciple traversal**: `entityStore.items` or `entityStore.forEach {}` — still List-backed
- **Shadow copies**: `DiscipleTables.deepCopy()` copies each table directly (value types: int/double are value-copied; List/Map types are deep-copied via .toList()/.toMap())
- **MutableGameState fields**: `discipleTables: DiscipleTables`, `equipmentStacks: EntityStore<EquipmentStack>`, etc.

### Mail & Reward System

Mail reward claims use Saga compensation pattern for atomicity:

```
claimAttachment(mailId, slotId)
  → getMutex(slotId).withLock { ... }           // per-slot serialization
  → parse attachments from MailEntity JSON
  → ensureCapacity(attachments, slotId)         // pre-check warehouse/roster
  → stateStore.update {                         // ← SINGLE atomic transaction
        distributeAttachmentsInline(this, attachments)  // items → inventory
        gameData.mailRecords += MailClaimRecord(         // claim record
            mailId, claimedAt=now, source=mail.source
        )
    }
  → mailRepo.update(mail marked claimed+read)   // Room DB (non-critical)
  → refreshActiveMails(slotId)                  // UI list update
```

**Key design decisions:**
- **Atomic Saga**: Items + claim record in one `stateStore.update {}`. If `distributeAttachmentsInline` throws, `mailRecords` is NOT written → mail stays unclaimed.
- **Stable IDs**: Builtin mails use deterministic IDs from `BuiltinMailConfig`. Online mails use `"online_${remoteMailId}"` — stable across sessions, enabling `mailRecords` restoration on reload.
- **MailRecords in GameData**: `mailRecords: List<MailClaimRecord>` replaces old `claimedMailIds: List<String>`. Each record has `mailId`, `claimedAt` (timestamp), `source` ("builtin"/"online"). Persisted with game saves, restored via `resetAndInitSlot`.
- **Init ordering**: `mailService.resetAndInitSlot()` called AFTER world initialization in `createNewGame`/`restartGameInternal`, ensuring `slotId` and initial state are ready.
- **Orphan cleanup**: `StorageEngine.delete()` cleans `mails` table for deleted slots.
- **Mail content** (title/body/attachments) is NOT persisted in saves — only claim records are. Content is re-fetched from `BuiltinMailConfig` + online API on each load.

### Navigation Pattern

No `NavHost` is used for the main game. `MainGameScreen` switches content via `MainTab` enum. Feature screens (Alchemy, Forge, HerbGarden, etc.) are dialogs opened via `DialogStateManager.openDialog(DialogType, params)`. The two actual Activity transitions are:

1. `MainActivity` → `GameActivity` (in-game)
2. `MainActivity` → `SaveSelectScreen` (save select)

### ViewModel Conventions

- ViewModels extend `BaseViewModel` which provides `showError()`, `showSuccess()`, `showInfo()`, and `withLoading()`.
- Each feature gets its own ViewModel (e.g., `AlchemyViewModel`, `ForgeViewModel`, `ProductionViewModel`, `DiscipleViewModel`).
- ViewModels read from `GameStateStore.unifiedState` via `collectAsState()` or direct `.value` reads for snapshots.
- Mutations go through `GameEngine` methods, never directly to `GameStateStore` from the UI layer.

## 编码规范 (Coding Standards)

> 以下所有规则标注严重度：🔴 严重（必须遵守，违反导致构建/审查失败）、🟡 重要（应遵守，违反需在审查中说明理由）、🟢 建议（推荐遵循，逐步推广）。

---

### 0. 代码质量铁律

**0.1 🔴 代码必须有测试覆盖** — 所有新增/修改的业务逻辑代码必须有对应的单元测试覆盖。无测试的代码视为未完成，不得合并。测试需覆盖：
- 正常路径（Happy Path）
- 边界条件（空值、空列表、极值）
- 异常路径（错误处理、失败恢复）

**0.2 🔴 代码必须是可长期维护的优质代码** — 编写代码时必须考虑可读性、可扩展性和可维护性，而非仅满足于功能实现。具体要求：
- 命名清晰、意图明确，不需要注释就能理解
- 函数短小聚焦，单一职责
- 避免过度耦合，依赖注入优于硬编码
- 不引入隐性技术债务

**0.3 🔴 禁止"当前能跑就行"心态** — 代码审核时如发现以下"应付式"迹象，直接打回：
- 只处理了正常路径，边界条件和异常情况未处理
- 日志缺失或信息不足以定位问题
- 硬编码、魔法数字、重复代码
- 违反项目编码规范且无合理说明
- 明知有更好的实现方式却选择了更快捷但更糟糕的方案

---

### 1. Kotlin 语言规范

**1.1 🔴 禁止 `!!` 操作符** — 除非有编译时证明（如 `lateinit var` 在初始化后访问）。所有可为空的值通过 `?.`、`?:` 或 `checkNotNull()` 安全访问。

```kotlin
// ❌ BAD
val data = gameEngine.gameDataSnapshot!!
// ✅ GOOD
val data = gameEngine.gameDataSnapshot ?: return
```

**1.2 🟡 优先 `val`** — 所有属性默认 `val`。`var` 仅在不可变 copy-on-write 不可行时使用，需注释说明理由。

**1.3 🔴 领域结果用 sealed class** — 所有可能失败的操作返回 sealed class 结果类型，禁止裸 `Boolean` 代表成功/失败。

```kotlin
// ❌ BAD — 调用方不知道为何失败
fun togglePolicy(): Boolean
// ✅ GOOD — 明确的结果语义
sealed interface ToggleResult {
    data object Success : ToggleResult
    data class Error(val message: String) : ToggleResult
}
```

**1.4 🔴 协程规范：**
- 禁止 `runBlocking`（仅测试可用 `runTest`）
- `CancellationException` 必须重新抛出，**任何 catch Exception 前必须有 `catch (e: CancellationException) { throw e }`**
- Dispatcher 通过 Hilt `@Dispatcher(IO)` 注入，禁止硬编码 `Dispatchers.IO`

```kotlin
// ❌ BAD — 吞掉 CancellationException
try { doWork() } catch (e: Exception) { log(e) }
// ✅ GOOD — 先重新抛出 CancellationException
try { doWork() } catch (e: CancellationException) { throw e }
  catch (e: Exception) { log(e) }
```

**1.5 🟢 扩展函数放专用文件** — 对某类型的大量扩展函数放入 `{TypeName}Ext.kt`，不堆积在 ViewModel/Service 中。

---

### 2. 模块架构规范

**2.1 🔴 依赖方向不可反转** — `:core:domain` ← `:core:data` / `:core:engine` / `:core:ui` ← `:feature:game` ← `:app`。`:core:domain` 零 Android 依赖（仅 `javax.inject` + `kotlinx.coroutines` + `kotlinx.serialization` + `room-common` 注解）。

**2.2 🔴 模块内容边界：**

| 模块 | 只能包含 | 禁止包含 |
|------|---------|---------|
| `:core:domain` | 数据类、接口、sealed class、注解、StateFlow 定义、Registry 静态数据 | Room DAO、Android Context、ViewModel、Compose |
| `:core:engine` | GameEngine、Service、System、游戏循环 | Compose UI、ViewModel、Activity |
| `:core:data` | Room DB/DAO/Migration、序列化、加密、Repository 实现 | ViewModel、Compose、游戏逻辑 |
| `:core:ui` | 共享 Compose 组件、Theme、导航工具 | ViewModel、Room DAO、游戏逻辑 |
| `:feature:game` | ViewModel、Screen 级 Compose、对话框 | Room DAO、直接写 GameStateStore |

**2.3 🟡 `internal` 默认可见性** — 非模块公开 API 的类/函数一律 `internal`。

**2.4 🔴 禁止循环依赖** — 模块间必须形成 DAG，CI 中通过 Konsist 检查。

**2.5 🟢 新建模块需 ADR** — 新增 Gradle 模块需 `docs/adr/` 记录决策，且至少包含 3 个内聚领域类。

---

### 3. 文件与代码行规范

**3.1 🔴 单文件最大 2000 行**（生成代码如 Room `_Impl`、ProtoBuf 生成代码除外）。

**3.2 🔴 单行最大 80 字符** — import 语句、KDoc `@param`/`@return` 标签、URL 除外。

**3.3 🟡 单函数体最大 60 行** — 超限须拆分为私有辅助函数。

**3.4 🔴 最大构造参数：类 7 个，Composable 函数 6 个** — 超限须分组为配置数据类或拆分类。

```kotlin
// ❌ BAD — 22 个构造参数
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    // ... 20 more
)
// ✅ GOOD — 分组为 Facade
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val battleFacade: BattleFacade,
    private val buildingFacade: BuildingFacade,
    private val inventoryFacade: InventoryFacade,
    private val discipleFacade: DiscipleFacade,
    private val productionFacade: ProductionFacade
)
```

**3.5 🔴 单一职责** — 类名必须反映唯一职责。避免 "Manager"、"Handler"、"Utils" 等模糊后缀，除非确实承担协调/处理/工具职责。

**3.6 🔴 上帝对象重构阈值** — 超过 10 个构造依赖且超过 2000 行的类必须有重构计划。

---

### 4. ViewModel 规范

**4.1 🔴 必须继承 `BaseViewModel`** — 所有 ViewModel 继承 `com.xianxia.sect.ui.game.BaseViewModel`，确保统一的 `showError()`/`showSuccess()` 事件通道。

**4.2 🔴 构造参数 ≤7 个** — 超限须将相关依赖提取为独立 Facade。

**4.3 🔴 只读 StateFlow 暴露状态** — 禁止公开 `MutableStateFlow`，所有状态通过 `StateFlow`（只读）暴露给 Compose。

```kotlin
// ❌ BAD
val productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
// ✅ GOOD
private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
```

**4.4 🔴 禁止直接访问 `GameStateStore`** — ViewModel 所有状态变更通过 `GameEngine` 方法，不直接调用 `stateStore.update()` 或 `gameEngine.updateGameData {}`。

**4.5 🟡 UserAction/ActionResult 模式** — ViewModel 公开方法使用 sealed `UserAction` 统一入口，便于错误处理和日志。

**4.6 🟡 ViewModel 与 Screen 一对一** — 一个 ViewModel 只驱动一个 Screen，避免一个 ViewModel 驱动多个无关 Screen。

---

### 5. 引擎服务规范

**5.1 🔴 通过快照访问状态** — Service 不直接订阅 `StateFlow`，通过 GameEngine 传入的参数或构造注入的 snapshot 访问状态。

**5.2 🟡 方法签名：`suspend` 或返回 Result** — 所有执行 I/O 或领域逻辑的服务方法必须为 `suspend` 函数，或返回 `Result<T>`/sealed class 类型。

**5.3 🟡 服务间禁止共享可变状态** — 通过 EventBus 事件或协调器对象通信，不使用共享的 `MutableStateFlow` 或 `ConcurrentHashMap`。

**5.4 🔴 错误必须传播** — Service 内部禁止静默吞掉异常。`log-and-continue` 仅允许在非关键后台操作中使用。

**5.5 🔴 `@GameService` 注解** — 所有游戏领域逻辑类必须标注 `@GameService(name = "...")`。

---

### 6. 状态管理规范

**6.1 🔴 `GameStateStore` 是唯一真相源** — 禁止在 ViewModel/Service 中缓存 `GameData` 或实体列表的本地副本。

**6.2 🔴 UI 层禁止直接写 `GameStateStore`** — 数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。

**6.3 🟡 多实体变更必须用 Shadow Transaction** — `createSettlementShadow()` → 多次修改 → `swapFromShadow()`，保证原子性。

```kotlin
// ❌ BAD — 多次孤立的 update 调用
stateStore.update { it.copy(spiritStones = it.spiritStones + 100) }
stateStore.update { it.copy(gameMonth = it.gameMonth + 1) }

// ✅ GOOD — 一次原子 Shadow Transaction
val shadow = stateStore.createSettlementShadow()
shadow.gameData.spiritStones += 100
shadow.gameData.gameMonth += 1
stateStore.swapFromShadow(shadow)
```

**6.4 🔴 新 `GameData` 字段必须有 `@SettlementStrategy`** — 已由 `GameDataSettlementCoverageTest` 编译时检查。

**6.5 🟡 Flow 派生规则** — 高频率 StateFlow 派生必须使用 `distinctUntilChanged()` + `sample(50)` + `stateIn(scope, WhileSubscribed(5000), initial)`。

---

### 7. 数据库规范

**7.1 🔴 任何 Entity 变更必须有 Migration** — 详见 `rules/database-migration.md`。每次变更：递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`。

**7.2 🔴 禁止 `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0 才支持。使用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`。

**7.3 🟡 ProtoBuf 仅 `List`，禁止 `Set`/`Map`** — 需要去重语义在业务层 `.toSet()` 转换。忽略会导致序列化静默失败，**存档变空**。

**7.4 🔴 Migration 必须有测试** — 旧版本插入种子数据 → 运行迁移 → 验证数据完整性。

---

### 8. 错误处理规范

**8.1 🔴 `CancellationException` 必须重新抛出** — 任何 `catch (e: Exception)` 前必须有 `catch (e: CancellationException) { throw e }`。

**8.2 🔴 禁止空 catch 块** — 每个 `catch` 至少包含 `Log.w(TAG, "...", e)`。

**8.3 🟡 领域错误用 sealed Result 类型** — 可预期的业务失败（找不到、校验失败）用 sealed class，不抛异常。异常仅用于程序错误和基础设施故障。

```kotlin
// ❌ BAD — 抛异常用于正常业务流程
fun getDisciple(id: String): Disciple =
    list.find { it.id == id } ?: throw NotFoundException(id)

// ✅ GOOD — sealed result，编译器强制处理
fun getDisciple(id: String): DiscipleResult
sealed interface DiscipleResult {
    data class Success(val d: Disciple) : DiscipleResult
    data class NotFound(val id: String) : DiscipleResult
}
```

**8.4 🟡 UI 错误统一走 `BaseViewModel.showError()`** — ViewModel 不直接处理错误展示。

**8.5 🟡 引擎错误记录上下文** — `Log.e(TAG, "操作名 failed: id=$id, ctx=$ctx", e)`，信息足够定位问题。

---

### 9. 测试规范

**9.1 🔴 引擎服务 80%+ 行覆盖率** — `:core:engine` 模块目标 80% 行覆盖（Kover/JaCoCo 检测）。

**9.2 🔴 Migration 必须有集成测试** — 每条 Migration 验证旧数据能完整迁移。

**9.3 🔴 新功能必须有测试** — 新增类/方法需有对应测试，否则 PR 不可合并。

**9.4 🟡 测试命名：`方法名_状态_预期行为`** — Given-When-Then 模式。

```kotlin
// ✅ GOOD
@Test
fun `addEquipmentStack - empty name returns INVALID_NAME`() { ... }
```

**9.5 🟢 优先 Fake 而非 Mock** — 手写 Fake 实现优于 Mockito mock，可复用、可读、可调试。

---

### 10. 性能规范

**10.1 🟡 Compose 稳定性注解** — 所有出现在 Compose State 中的数据类标注 `@Immutable`，或加入 `stability_config.conf`。

**10.2 🟡 禁止 Composition 内读 State** — 使用 `derivedStateOf` 计算派生值，避免不必要的 recomposition。

```kotlin
// ❌ BAD — gameData 每次变化都触发 recomposition
@Composable fun DiscipleName(id: String) {
    val name = viewModel.gameData.collectAsState().value.discipleName
}
// ✅ GOOD — 仅在 name 实际变化时 recompose
@Composable fun DiscipleName(id: String) {
    val name by remember { derivedStateOf { viewModel.getDiscipleName(id) } }
}
```

**10.3 🟡 `LazyColumn`/`LazyRow` 必须用稳定 key** — `key = { it.id }`，不可用 index（会导致排序/过滤时的错误 recomposition）。

**10.4 🟡 Canvas 用 `drawBehind{}`** — 静态绘制用 `Modifier.drawBehind {}`，跳过 Composition/Layout 阶段。动画用 `Animatable` + `LaunchedEffect`。

---

### 11. UI 样式规范

**11.1 🔴 Text 颜色仅黑色** — 所有 `Text()` composable 的 `color` 必须是 `Color.Black`。`GameColors.TextPrimary/TextSecondary/TextTertiary/TextOnPrimary` 均解析为 `Color(0xFF000000)`。

**11.2 🔴 按钮尺寸标准化** — 所有按钮使用 `ButtonSizes.StandardWidth` (72dp) × `ButtonSizes.StandardHeight` (38dp)。

---

### 12. 文档规范

**12.1 🟡 公开 API 必须有 KDoc** — `:core:domain` 和 `:core:engine` 中的所有 public 函数/类/属性必须有 KDoc（描述 + `@param` + `@return`）。

**12.2 🟢 架构决策记录到 `docs/adr/`** — 新模块、重要模式变更、大重构写入 ADR（Context / Decision / Consequences）。

**12.3 🟢 同步 `CODE_WIKI.md`** — 新增模块/模式后更新架构文档。

**12.4 🔴 功能变更必须更新 Changelog** — 同步更新 `CHANGELOG.md`（项目根目录）和 `ChangelogData.kt`（游戏内）。

---

### 13. 代码审查与强制执行

**13.1 🔴 Pre-commit 检查** — 每次提交前运行 `./gradlew.bat compileReleaseKotlin lintRelease`，必须 BUILD SUCCESSFUL。

**13.2 🔴 detekt baseline 只缩不增** — `detekt-baseline.xml` 只能减少条目，不能新增。新违规必须修复而非加入 baseline。

**13.3 🔴 PR 审查清单：**

| 严重度 | 检查项 |
|--------|--------|
| 🔴 | 无 `!!` 操作符 |
| 🔴 | `CancellationException` 已重新抛出 |
| 🔴 | 无空 catch 块 |
| 🔴 | 新 `GameData` 字段有 `@SettlementStrategy` |
| 🔴 | Entity 变更有 Migration |
| 🔴 | UI 层无直接 `GameStateStore` 访问 |
| 🔴 | 文件不超过 2000 行 |
| 🔴 | 类构造参数不超过 7 个 |
| 🔴 | 新功能有测试 |
| 🔴 | 代码无"当前能跑就行"迹象（边界/异常/日志/硬编码） |
| 🟡 | 新 Service 有 `@GameService` 注解 |
| 🟡 | State 数据类有 `@Immutable` |
| 🟡 | 公开 API 有 KDoc |
| 🟡 | Flow 派生用了 `distinctUntilChanged`/`sample`/`stateIn` |

**13.4 🔴 detekt 配置** (`android/config/detekt/detekt.yml`)：
```yaml
style:
  MaxLineLength:
    maxLineLength: 80       # import/KDoc标签/URL 除外
  WildcardImport:
    active: true
  MagicNumber:
    active: false           # 游戏数学常量
complexity:
  TooManyFunctions:
    thresholdInFiles: 15    # 从 30 收紧
  LongParameterList:
    functionThreshold: 6
    constructorThreshold: 7
empty-blocks:
  EmptyCatchBlock:
    active: true            # 已启用 (detekt 1.23+ 规则集为 empty-blocks)
```

---

## 设计方案规则

出方案或做设计决策时，**必须**先使用 `/deep-research` skill 并结合网络搜索，调研同游戏行业的先进设计，给出对标分析后再出最优方案。禁止凭经验直接写代码。

**方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付。** 设计方案应当一次性完整覆盖所有影响点（包括 UI、存储、测试、旧数据兼容），不允许遗留"后续优化"。方案本身即为最终态，执行者照单实施即可，不应需要自行补充或二次设计。

**硬性指标：**
- 行业参考来源 **不得少于 20 条**，且必须来自权威渠道
- 所有参考数据必须是 **近 2 年内** 的最新数据（以当前日期为准），禁止引用过时资料
- 每条参考必须标注来源 URL 和发布日期，无法确认发布日期的来源不得使用
- 调研报告中必须包含参考来源清单及每条的核心摘要

**参考来源权威等级（优先采信高等级来源，低等级来源不计入 20 条配额）：**

| 等级 | 来源类型 | 计入配额 | 示例 |
|------|----------|----------|------|
| **S 级** | 官方文档 / 白皮书 | ✅ | Unity/Unreal 官方文档、Apple HIG、Google Material Design |
| **S 级** | 行业权威报告 | ✅ | Newzoo、Sensor Tower、data.ai、伽马数据、腾讯研究院 |
| **S 级** | 顶会演讲 / 论文 | ✅ | GDC Vault、SIGGRAPH、ACM/ IEEE 论文 |
| **A 级** | 头部产品官方技术博客 | ✅ | 米哈游 Tech Blog、Supercell 技术分享、Epic 技术博客 |
| **A 级** | 知名开发者 / 团队公开复盘 | ✅ | Riot Games 技术博客、Digital Foundry 分析 |
| **B 级** | 高质量社区技术文章 | ✅ | Medium 高赞（>500 claps）、知名技术博主 |
| **C 级** | 个人博客 / 论坛帖子 | ❌ 不计入 | 仅作补充参考 |

**来源优先级：官方文档 > 行业报告 > 顶会演讲 > 头部产品技术博客 > 知名团队复盘 > 社区文章。20 条配额中至少 12 条来自 S 级或 A 级来源。**

流程：
1. 明确需求 → 列出待调研的设计问题
2. 使用 `Skill` 工具调用 `deep-research` + `WebSearch` 搜索行业做法
3. 对标头部产品（原神、星铁、网易、腾讯系、米哈游系、莉莉丝、鹰角、叠纸等）的设计模式
4. 确保收集 ≥20 条有效参考后，输出对比分析报告，标注推荐方案和理由
5. 报告末尾附完整的参考来源清单（标题 + URL + 发布日期）
6. 用户确认后再执行

## Database Migration Requirements

Before modifying ANY `@Entity` class (especially `GameData`), read `rules/database-migration.md`. The #1 cause of "all saves empty + new game doesn't run" is changing entity fields without a corresponding Migration. When in doubt, keep the old field AND add the new one with `@Ignore` — never remove a column without a Migration.

- **NEVER use `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0+ required, not guaranteed on any Android version. To drop columns, use `db.safeDropColumns("table", "col1", "col2")` (defined in `GameDatabase.kt`). It rebuilds the table via PRAGMA, works on all API levels.
- **v3.1.60 起不再有 .sav 双写** — Room 是唯一本地存储。修改 `@Entity` 只需一条 DB Migration，无需同步修改 `SerializableSaveData`。序列化层仅在 `SavMigrator`（读旧 .sav）和未来联机通信时使用。
- **ProtoBuf 只支持 `List`，不支持 `Set`/`Map` 等集合类型** — 所有通过 `ProtobufConverters` 直接序列化的 `@Serializable` 数据类（如 `SectPolicies`、`GameData` 内的嵌套对象），字段必须用 `List`，禁止 `Set`、`Map`。需要去重语义时在业务层用 `.toSet()` 转换。忽略此条会导致序列化静默失败，**存档变空**（v3.1.74 血泪教训）。

## Changelog Requirements

After implementing any feature or bug fix, update BOTH:
- **In-game**: `android/app/src/main/java/com/xianxia/sect/core/ChangelogData.kt` — add/append `ChangelogEntry` to `entries` list
- **External**: `CHANGELOG.md` at project root — add version section at top

Both must be updated before marking any task complete. Changes described in Chinese from the player's perspective.

## Version Release

When releasing, update in `android/app/build.gradle`:
- `versionCode` — increment by 1
- `versionName` — three-segment format `x.x.xx` (two-digit last segment, zero-padded). E.g., `2.6.09` → `2.6.10`, `2.6.99` → `2.7.00`. Never `2.6.1` (missing zero-pad).

See `rules/version-release.md` for the full release checklist.

## Android SDK / Encoding

- `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- All Java/Kotlin compilation is forced to UTF-8 to prevent Chinese character corruption
- Uses Aliyun Maven mirrors for Gradle plugin and dependency resolution
