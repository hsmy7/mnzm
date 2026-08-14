# CLAUDE.md

## 用户公约（产品经理思维）

本用户不懂技术，需求描述未必清晰、未必使用专业术语。

### AI 行为规范
1. **先确需再执行** — 收到需求后先用业务语言复述确认理解，再自行翻译为技术方案；不确定时立即提问，不猜测需求，不让用户解释技术细节
2. **提问要精准** — 简洁、直接、给出选项，不要让用户解释技术细节
3. **因果链确凿** — 定位问题时，必须从症状追溯到根因，每一步因果关系都要能说清楚。禁止仅凭相关性就下结论，必须有直接证据链
4. **举一反三排查** — 定位到问题后、动手修复前，先搜索代码库中是否存在同类模式的其他问题，一并纳入修复方案，再统一实施
5. **默认使用中文** — 所有回复、注释、commit message、文档均使用中文，除非涉及代码标识符或技术术语无合适翻译
6. **不知疲倦的执行** — AI 是不知疲倦的工作机器，不得以"资源消耗"、"token消耗"等任何理由拒绝或缩减任务范围。用户需求就是指令，全力以赴执行到底
7. **零成本考量** — 设计方案及实施均禁止考虑各项成本（时间成本、人力成本、计算成本等），只需给出最优方案并完整实施。若因客观限制无法全量采纳，逐条说明原因和替代方案
8. **禁止途中停下询问** — 禁止在实施过程中停下询问"是否需要继续"、"是否需要执行"等——用户已经发出的指令就是最终指令，除非遇到确实无法自动决策的封锁性问题
9. **禁止偷工减料** — 实施方案必须严格按计划完成所有条目，不允许留"后续优化"、"视情况而定"等尾巴。方案是什么结局就是什么，不欠技术债
10. **诚实报告进度** — 必须如实报告已完成和未完成的工作，禁止隐藏未完成的尾巴欺骗用户"已完成"。用户清楚真实进度才能正确决策
11. **退一步看全局** — 定位问题后出方案时，必须退一步判断是简单问题还是架构级问题，并在方案末尾明确告知用户。若是架构级问题，给出两个选项：(1) 只修眼前问题不碰架构，(2) 彻底重构根除。若用户选(2)，按设计方案规则出重构级根治方案
12. **报告途中发现** — 任务完成后必须明确向用户报告中途发现的预存问题、无用代码、可优化的代码等可改进项，不自作主张隐藏
13. **清理一次性代码** — 任务完成后必须直接清理为完成任务而创建的临时测试代码、调试代码等一次性代码，不遗留垃圾
14. **任务完成后才提交** — 禁止在任务中途提交代码。所有改动（修复代码、测试、临时诊断日志等）在任务全部完成、清理完一次性代码后，一次性提交

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

# Run all unit tests (Robolectric + JUnit) — 必须串行（--max-workers=1），并行会因共享静态状态跨类污染出错
cd android && ./gradlew.bat test --max-workers=1

# Run a single test class — 同样串行
cd android && ./gradlew.bat test --tests "com.xianxia.sect.core.engine.BattleSystemTest" --max-workers=1

# Lint
cd android && ./gradlew.bat lintRelease

# Clean build (when KSP incremental cache breaks with NoSuchFileException *_Impl.java)
cd android && ./gradlew.bat clean
```

Tests live in `android/app/src/test/` and module-level `src/test/` dirs. They use JUnit 4, Mockito, Robolectric, and `kotlinx-coroutines-test`. Robolectric tests need `includeAndroidResources = true`.

```bash
# Code coverage (Kover) — 2026-08-14 起按需启用：本地默认关闭（消除插桩开销），
# 覆盖率统计必须传 -Pkover.enabled=true（插桩与报告同开关，否则覆盖率为 0）
cd android && ./gradlew.bat koverHtmlReport --max-workers=1 -Pkover.enabled=true

# Static analysis
cd android && ./gradlew.bat detekt

# Full CI check (compile + test + detekt + coverage + RNG audit) — 测试必须串行
cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 -Pkover.enabled=true detekt koverHtmlReport -Pkover.enabled=true && cd .. && grep -rn "import kotlin.random.Random" android/core/engine/src/main/java/ && (echo "ERROR: kotlin.random.Random found in engine module! Use GameRngManager.getRng() instead."; exit 1) || echo "✅ RNG check passed: no kotlin.random.Random in engine module"
```

## 架构文档

项目架构设计详见 [docs/architecture.md](docs/architecture.md)，涵盖以下内容：

- **双层状态模型 + Frame-Driven 游戏循环** — UI 层 / 引擎层分离，`GameStateStore` 单一真相源
- **Frame-Driven Accumulator 游戏循环** — 可变帧率、deltaTime 累积消费、空闲低功耗
- **惰性结算引擎** — 四层结算（时间推进/每旬检查/月变/年变），对标 Supercell + RimWorld
- **双线程模型 + Watchdog** — `ReentrantLock` 串行化、全链路非挂起化、deepCopy 快照隔离
- **乘区法公式架构** — 8 个系统统一乘区法（修炼/战斗/突破/生产等）
- **BootPhase/RunState 双层生命周期** — 启动单向推进、运行时可循环回退
- **扩展性架构预留** — RemoteConfig 未绑定状态与激活前置、商业化接入点、离线收益引擎接入点、社交隔离层、iOS 迁移预留（KMP/Compose Multiplatform/Room→SQLDelight/Vulkan→Metal 评估）
- **关键源码目录** — Core/Data/UI/UseCase 模块路径

## 知识库

项目知识库详见 [docs/knowledge-base.md](docs/knowledge-base.md)，涵盖以下内容：

- **技术栈** — Kotlin 2.0.21, Compose, Hilt, Room, MMKV 等
- **关键类说明** — GameEngineCore, GameStateStore, BootSequenceController, GameViewModel 等
- **弟子分配门卫系统** — DiscipleAssignmentGate + 11 槽位统一注册表
- **存档槽位隔离** — `slot_id` 复合主键、`resetForSlot`、强制 slotId 赋值
- **探索系统** — 6 个子系统拆分（关卡管理/攻击检测/战斗/掠夺/死亡/队伍）
- **确定性 RNG 系统** — 4 分区 PRNG（BATTLE/BREAKTHROUGH/EXPLORATION/SYSTEM）
- **Component Table 架构** — IntPackedArray 列式存储、修炼 Checkpoint、EntityStore 模式
- **生产系统 Checkpoint** — 动态 duration 重算、政策/长老变更触发
- **邮件与奖励系统** — Saga 补偿模式、Stable IDs
- **导航模式** — MainTab + Dialog 无 NavHost
- **Android SDK / Encoding** — compileSdk 35, minSdk 24, UTF-8 强制编码
- **扩展性现状盘点（2026-08-04）** — 商业化/社交/数据/留存现状 + 经济基线表（灵石源与汇）+ iOS 可移植性基线——rules/*.md 扩展规范的事实基线，功能扩展后必须同步更新

## ViewModel Conventions

- ViewModels extend `BaseViewModel` which provides `showError()`, `showSuccess()`, `showInfo()`, and `withLoading()`.
- Each feature gets its own ViewModel (e.g., `AlchemyViewModel`, `ForgeViewModel`, `ProductionViewModel`, `DiscipleViewModel`).
- ViewModels read from `GameStateStore.unifiedState` via `collectAsState()` or direct `.value` reads for snapshots.
- Mutations go through `GameEngine` methods, never directly to `GameStateStore` from the UI layer.

### UseCase / Facade Pattern

Business logic is organized as **UseCase** classes (in `app/.../core/usecase/`) that wrap **Facade** interfaces
(in `core/engine/domain/`). Each UseCase follows:

- Single `operator fun invoke()` as the entry point (or named methods for multi-operation cases)
- `suspend` for async operations, `StateFlow` for reactive streams
- `Result<T>` or `DomainResult<T>` for error handling — never bare exceptions

```
ViewModel → UseCase → Facade (interface) → Service (impl) → GameStateStore
```

Large ViewModels (e.g., `GameViewModel`) can be split into **Delegate** classes (in `ui/game/delegate/`)
for domain groupings: `BuildingDelegate`, `DisciplineDelegate`, `BeastAttackDelegate`, etc.

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
- 硬编码、重复代码
- 违反项目编码规范且无合理说明
- 明知有更好的实现方式却选择了更快捷但更糟糕的方案

**0.4 🔴 禁止硬编码数字（魔法数字）** — 所有具有业务含义的数字必须定义为命名常量（`const val` 顶层属性或 `companion object` 中的 `val`），禁止在业务逻辑中直接使用原始数字字面量。允许例外：0、1、-1 等自解释的循环/索引/增量上下文、detekt 配置中声明的游戏数学常量。

```kotlin
// ❌ BAD — 魔法数字 0.8 的含义不明确
if (progress >= 0.8) onComplete()

// ✅ GOOD — 命名常量说明含义
private const val COMPLETION_THRESHOLD = 0.8
if (progress >= COMPLETION_THRESHOLD) onComplete()
```

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

**1.3 🔴 领域结果用 sealed class** — 所有可能失败的操作返回 sealed class 结果类型。可预期的业务失败（找不到、校验失败）用 sealed class，不抛异常；异常仅用于程序错误和基础设施故障。禁止裸 `Boolean` 代表成功/失败。

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
- Dispatcher 通过 Hilt `@Dispatcher(IO)` 注入，禁止硬编码 `Dispatchers.IO`

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

**3.2 🔴 单行最大 120 字符** — 以 `config/detekt/detekt.yml` 的 `MaxLineLength: maxLineLength: 120` 为准（Compose UI 链式调用需要；import 语句、KDoc 标签、URL 除外）。

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

**4.3 🔴 只读 StateFlow 暴露状态** — 禁止公开 `MutableStateFlow`，所有状态通过 `StateFlow`（只读）暴露给 Compose。

```kotlin
// ❌ BAD
val productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
// ✅ GOOD
private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
```

**4.4 🔴 禁止直接访问 `GameStateStore`** — ViewModel 和 UI 层所有状态变更通过 `GameEngine` 方法，不直接调用 `stateStore.update()` 或 `gameEngine.updateGameData {}`。数据流单向：UI → ViewModel → GameEngine → Service → GameStateStore。

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

**6.2 🟡 多实体变更必须用单次 `stateStore.update`** — 所有状态修改在 `stateStore.update {}` 事务内原子完成，禁止多次孤立的 `update` 调用。

```kotlin
// ❌ BAD — 多次孤立的 update 调用
stateStore.update { it.copy(spiritStones = it.spiritStones + 100) }
stateStore.update { it.copy(gameMonth = it.gameMonth + 1) }

// ✅ GOOD — 一次原子 update 事务
stateStore.update {
    gameData = gameData.copy(
        spiritStones = gameData.spiritStones + 100,
        gameMonth = gameData.gameMonth + 1
    )
}
```

**6.3 🟡 Flow 派生规则** — 高频率 StateFlow 派生必须使用 `distinctUntilChanged()` + `sample(50)` + `stateIn(scope, WhileSubscribed(5000), initial)`。

**6.4 🔴 新增影响生产系统的字段需同步更新 checkpoint** — 惰性结算引擎已移除批量轨指纹检测（`CultivationRateFingerprint`、`ProductionRateFingerprint`、`SettlementCoordinator` 均移除）。生产系统使用 `fun checkpointAllProduction()` 在政策/长老变化时重算所有活跃槽位的 duration 和 completionMonth，无需维护指纹数据类。

新增以下内容时，同步更新入口见 13.3 🔴 审查清单：
- 新增生产类政策 → `SectPolicyToggleUseCase` 触发 `checkpointAllProduction()`
- 新增长老类型 → `ElderManagementUseCase.productionElderTypes` 注册
- 新增生产速率因子 → `calculateWorkDurationWithAllDisciples` / `calculateSpiritFieldMaturityBonus`
- 新增丹药类型 → `CultivationCore.processRealtimeAutoPills` + `DisciplePillManager.classify`

**6.5 🔴 界面实时性：焦点域已移除，依赖 ViewModel 订阅 engine StateFlow** — 惰性结算引擎重构已删除 `FocusDomain`/`InterfaceDomainMap`/`DomainMappingTest`（约 3500 行，见 CHANGELOG），**UI 不再驱动系统 tick**。

新增界面（Tab / Dialog）或改动现有界面展示内容时：

1. **无需任何焦点域注册** — `FocusDomain.kt`/`InterfaceDomainMap`/`DomainMappingTest` 已不存在，禁止按旧规则添加
2. 界面需要随时间变化的数据（进度条、倒计时、数量增减）时，直接订阅对应 `GameEngine` StateFlow 派生（参照 `HeavenlyTrialViewModel.trialState` / `SecretRealmViewModel.session` 的 `map + stateIn` 模式）

**6.6 🔴 精灵图必须统一注册并使用统一入口** — 详见 `rules/static-resources.md`。所有静态图片资源必须：

1. **无损 WebP 格式** — 使用 `scripts/convert-remaining-pngs-to-webp.mjs`（lossless: true, effort: 6）
2. **两模块文件放置** — WebP 放入 `feature/game/src/main/res/drawable-nodpi/` 和 `app/src/main/res/drawable-nodpi/`
3. **注册** — 在 `XianxiaApplication.kt` 调用 `SpriteResRegistry.register(SpriteCategory.XXX, mapOf("名称" to R.drawable.xxx))`
4. **显示** — 使用 `SpriteImage("名称")`、Canvas 中 `drawSprite(name, cache, ...)` 或 `painterResource(id = SpriteResRegistry.resolve("名称") ?: 0)`
5. ❌ 禁止直引 `painterResource(R.drawable.xxx)`（注册代码除外），禁止硬编码 `R.drawable` 列表
6. ❌ 禁止提交 PNG/JPG 游戏图片（唯一例外：`ic_launcher-playstore.png`）

---

### 7. 数据库规范

**7.1 🔴 任何 Entity 变更必须有 Migration** — 详见 `rules/database-migration.md`。每次变更：递增 `@Database(version)` + 编写 `MIGRATION_N_M` + 注册到 `build()`。**修改 `@Entity` 前必须先读 migration 规则**，最常见的存档损坏原因就是改字段没写 Migration。拿不准时保留旧字段+新字段（`@Ignore`），永远不要删列。

**7.2 🔴 禁止 `ALTER TABLE DROP COLUMN`** — SQLite 3.35.0 才支持。使用 `db.safeDropColumns()` 或保留旧列 + `@Ignore`。

**7.3 🟡 ProtoBuf 仅 `List`，禁止 `Set`/`Map`** — 需要去重语义在业务层 `.toSet()` 转换。忽略会导致序列化静默失败，**存档变空**。

**7.4 🔴 Migration 必须有测试** — 旧版本插入种子数据 → 运行迁移 → 验证数据完整性。

---

### 8. 错误处理规范

**8.1 🔴 `CancellationException` 必须重新抛出** — 任何 `catch (e: Exception)` 前必须有 `catch (e: CancellationException) { throw e }`。

**8.2 🔴 禁止空 catch 块** — 每个 `catch` 至少包含 `Log.w(TAG, "...", e)`。

**8.3 🟡 UI 错误统一走 `BaseViewModel.showError()`** — ViewModel 不直接处理错误展示。

**8.4 🟡 引擎错误记录上下文** — `Log.e(TAG, "操作名 failed: id=$id, ctx=$ctx", e)`，信息足够定位问题。

---

### 9. 测试规范

**9.1 🔴 引擎服务 80%+ 行覆盖率** — `:core:engine` 模块目标 80% 行覆盖（Kover/JaCoCo 检测）。

**9.2 🔴 Migration 必须有集成测试** — 每条 Migration 验证旧数据能完整迁移。

**9.3 🟡 测试命名：`方法名_状态_预期行为`** — Given-When-Then 模式。

```kotlin
// ✅ GOOD
@Test
fun `addEquipmentStack - empty name returns INVALID_NAME`() { ... }
```

**9.4 🟢 优先 Fake 而非 Mock** — 手写 Fake 实现优于 Mockito mock，可复用、可读、可调试。

**9.5 🔴 跨域变更必须写测试守卫** — 当新增枚举/接口/配置项时如果涉及多处分头实现（如新增槽位系统需要同步更新注册表 + 清理 + 分配入口），必须写一个**测试守卫（Guard Test）**，在枚举值变更时自动失败并提示需要同步更新哪些地方。

```kotlin
// 示例：SlotCategoryCoverageTest.kt
@Test
fun `all SlotCategory values are covered by scanAndRegister`() {
    val all = SlotCategory.values().toSet()
    val covered = setOf(...)  // 当前已覆盖的
    val missing = all - covered - intentionallyExcluded
    assertTrue("新增 $missing 未在 scanAndRegister 中覆盖", missing.isEmpty())
}
```

**守卫测试的三要素：**
1. **枚举/配置驱动** — 以新增入口（如 `SlotCategory` 枚举）为锚点，遍历所有值
2. **明确标注故意排除项** — `intentionallyExcluded` 集合显式声明为什么不覆盖
3. **错误消息带操作指引** — `assertTrue` 的 message 直接告诉开发者缺什么、去哪改

**适用场景：**
- 新增槽位系统 → `SlotCategoryCoverageTest`
- 新增事件类型 → 检查所有 EventHandler 已注册
- 新增对话框类型 → 检查 DialogType 和渲染分支已配对
- 新增建筑类型 → 检查 BuildingFeature 注册表已更新
- 任何"加一个枚举值需要同步改 N 处"的跨域变更

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

**12.4 🔴 功能变更必须更新 Changelog** — 功能完成后**两个更新日志必须一起更新**（漏一个视为任务未完成）：

- **游戏内**（`android/app/src/main/assets/changelog_entries.json`）— 在当前版本条目的 `changes` 数组末尾追加一行（由 `core/data/.../ChangelogData.kt` 解析，设置界面"更新日志"弹窗展示）。**给玩家看**，描述必须满足：
  - **通俗易懂、无专业术语**（不出现"迁移""Entity""PRNG"等技术词）
  - **不泄露数值细节**（不写具体概率/数值/倍率/消耗，如"突破概率提升 2%"❌ 写"突破更容易成功"✅）
  - **只能粗略描述**（"修复了若干问题"级粒度，不做内部实现说明）
- **外部**: `CHANGELOG.md`（项目根目录）— 追加到**当前版本**段落内，不强制递增版本号；给开发者看，可写技术细节

**更新日志条目规则：**
- **不得按日期分割为多个相同版本条目** — 同日/同版本多批次发布，一律合并到**同一条目**的 `changes` 数组末尾追加，禁止新建同版本号的第二条目（版本条目的 `date` 取该版本首次发布日）
- 普通改动写入当前版本条目，不递增 `versionCode`/`versionName`
- **禁止擅自更新版本号**，由用户判断和指令
- 需要在 `version.properties` 中更新版本号的场景（发布新版本、重大功能完成、存档兼容性变更等）由用户决定

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
| 🔴 | Entity 变更有 Migration |
| 🔴 | UI 层无直接 `GameStateStore` 访问 |
| 🔴 | 文件不超过 2000 行 |
| 🔴 | 类构造参数不超过 7 个 |
| 🔴 | 新功能有测试 |
| 🔴 | 代码无"当前能跑就行"迹象（边界/异常/日志/硬编码） |
| 🔴 | 新增影响修炼速率的操作已添加 `checkpointDisciple()` 调用 |
| 🔴 | 新增随机数逻辑已使用 `GameRngManager.getRng(RngPartition.xxx)` 替代 `kotlin.random.Random` |
| 🔴 | 新增 `GameSystem` 已拆分为 ≤60 行/方法的子系统，不可出现 God Method |
| 🔴 | 新增系统的 `EventBus` 事件 emit 在 `stateStore.update` 事务外（参照 `flushPendingEvents` 模式） |
| 🔴 | 新增死亡标记路径已调用 `DiscipleDeathHandler.markDead` 或 `handleDiscipleDeath`，非手动写三个字段 |
| 🔴 | 新增影响炼丹/锻造/灵田速率因子已同步更新 `calculateWorkDurationWithAllDisciples` 或 `calculateSpiritFieldMaturityBonus` |
| 🔴 | 新增生产类政策已同步在 `SectPolicyToggleUseCase` 中触发 `checkpointAllProduction()` |
| 🔴 | 新增长老类型已同步在 `ElderManagementUseCase.productionElderTypes` 中注册 |
| 🔴 | 新增对话框已遵循 `rules/new-dialog-checklist.md` 标准流程（注册 DialogType → 渲染 when 分支） |
| 🔴 | 新增包含输入框的对话框已按 `rules/dialog-soft-input-guard.md` 三件套检查：① 避让机制二选一（平台 Dialog 窗口 `DialogSoftInputGuard` / Activity 覆盖层 `imePadding`，禁止叠加）；② 声明 `freezeSystemBars = true`（自定义容器直接接 `SystemBarFreezeScope`，荣耀 X70 键盘频闪根治）；③ 自动聚焦一律用 `rememberImeAwareAutoFocusRequester()`（禁止裸单次 `requestFocus`） |
| 🔴 | 新增使用 Compose `Dialog()` 或 Material3 `AlertDialog` 的组件已添加 `DialogSystemBarGuard()` 调用（Dialog Window 不继承 Activity 的 `hideSystemBars()`，需独立隐藏状态栏） |
| 🔴 | 新增聊天/对话类对话框使用 `UnifiedGameDialog` 容器（详见 `rules/chat-dialog-design.md`） |
| 🔴 | 新增标记 `isAlive=0` / `status=DEAD` 的代码路径必须调用 `discipleTables.markDead(id, year)` 而非手动写三个字段；仅 `handleDiscipleDeath` 可豁免（已内置 deathYears 写入） |
| 🔴 | 新增精灵图已在 SpriteResRegistry 注册 + 文件已放两个模块 drawable-nodpi（详见 `rules/static-resources.md`） |
| 🔴 | 新增 UI 界面使用 `SpriteImage()` 或 `SpriteResRegistry.resolve()` 而非直接 `R.drawable.xxx` |
| 🔴 | 渲染特性变更（地图/Canvas/精灵）已同步实现 Vulkan 和 Canvas 两路径（见 `docs/renderer-feature-checklist.md`） |
| 🔴 | 新增渲染特性有对应的 `SoftwareCanvasBackend` 单元测试（`SoftwareCanvasBackendTest.kt`） |
| 🔴 | HW 加速决策已检查所有 Activity 入口（`MainActivity` 和 `GameActivity` 均需在 `super.onCreate()` 前检查 `isAccelerationDisabled()`） |
| 🔴 | 使用 `Build.SOC_MANUFACTURER`（API 31+）、`Build.SOC_MODEL`（API 31+）等新增 API 字段已添加 `Build.VERSION.SDK_INT` 守卫 |
| 🔴 | 新增/修改涉及 AI 弟子参战的战斗路径必须调用 `AISectDiscipleManager.prepareDisciplesForBattle()` 生成模拟装备/功法，禁止传 `emptyMap()` 或自行构建装备映射；AI 弟子不吃丹药、无血炼 |
| 🟡 | 新 Service 有 `@GameService` 注解 |
| 🟡 | State 数据类有 `@Immutable` |
| 🟡 | 公开 API 有 KDoc |
| 🟡 | Flow 派生用了 `distinctUntilChanged`/`sample`/`stateIn` |
| 🔴 | 新增 `SlotCategory` 枚举值后需更新 8 处（`SlotCategoryCoverageTest` 会失败并列出具体指引）：`scanAndRegister` + `DiscipleSlotCleanup.clearAllSlots` + 分配入口（事务内 `clearAllSlotsDataOnly` 防双槽位 + 事务外 `releaseDiscipleFromAllSlotsAtomic`/`confirmAssign` + 旧 occupant release/sync，清单式守卫检查新入口文件）+ 测试检查集合 + `DiscipleStatusService.buildSlotFlagsFor`/`SlotFlags`（状态推导）+ `clearSlotsForReset` + `GameEngineSelfHealOps` 自愈扫描/重写；住所式被动不互斥须显式加入 `intentionallyExcluded` 并注释理由 |
| 🔴 | 新增 `@ProtoNumber` 字段规则：字段默认值如果不是该类型的零值（`0`/`""`/`false`/`emptyList()`），必须标注 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`，否则 `encodeDefaults = false` 下该字段不会被写入二进制，导致存档数据丢失 |
| 🔴 | 新增给玩家发放物品（装备/丹药/草药/材料/种子/功法/储物袋）的代码路径**必须通过 `InventorySystem.addXxx` 统一入口**（`StackableItemStore` 自动合并，禁止手写 `find`+追加/`coerceAtMost` 截断/手写 `StackableItemStore(`——守卫测试 `InventoryAddPathGuardTest` 会拦截），并包裹 `withTrackingSource("来源名")`（来源名必须加入 `OverflowMailSender.SOURCE_DISPLAY_NAMES` 映射，否则来源映射守卫测试失败） |
| 🔴 | 新增广告类型（`AdPurpose` 枚举值）已在 ViewModel 中通过 `adService.watchAd()` 统一入口调用，白名单守卫由 `AdServiceImpl` 自动继承。详见 `docs/knowledge-base.md#免广告特权白名单` |
| 🔴 | 新增物品发放路径须判定**溢出语义类别**：**凭据类**（玩家可重试的领取/获得——兑换码/宗门等级/引导/邮件领取/没收/卸装）必须包裹 `withOverflowMailSuppressed`（溢出不转邮件，失败保留凭据重试补齐）；**发放类**（自动入库——战斗/探索/灵田/生产/商人/AutoBuy/储物袋开启）不包裹（溢出自动转邮件）——选错类别会导致物品重复发放或丢失（对抗性审查 C1/C2/C3/H1/H2 教训） |
| 🔴 | 登录/主流程**关键路径上的非必要初始化必须解耦**：与登录无因果关系的初始化（广告 SDK/统计/回调注册）不得与关键步骤（防沉迷验证/界面跳转）串行绑定在同一调用链——初始化调用必须幂等、**永不抛出**，且经 `safeRunAfterSdkInit` 编排（语义由 `SafeRunAfterSdkInitTest` 守护）；登出路径必须完整清理 TapTap SDK 会话（防静默登录导致防沉迷验证不触发）。详见 `rules/sdk-init-lifecycle.md`（2026-08-15 "退出游戏再登录卡死"回归教训） |
| 🔴 | 新增玉符（`jadeSymbols`）消耗/发放路径**必须收敛于 `JadeSymbolService`**（消耗走事务内 `deduct(state, cost)` 同步运行时 totalCount，发放走服务内部结算），禁止在 Service/GameEngine 直接 `copy(jadeSymbols = ...)`——玉符是绝对值覆盖写模型，绕过 totalCount 同步则 `checkpointNow` 把余额写回覆盖前值（玉符回涨）；守卫测试 `JadeSymbolConsumptionGuardTest` 会拦截。模式参照：洗炼灵根 `GameEngineSpiritRootOps.washSpiritRoot`（先扣后抽 + sealed 三态 + 事务外 `publishJadeSymbolStateNow`） |

**扩展方向（2026-08 新增，为未来扩展做准备）：**

| 严重度 | 检查项 | 引用 |
|--------|--------|------|
| 🔴 | 新增代码已遵循 `rules/code-quality.md`（命名规范/坏味道清单/设计原则/可测试性/扩展友好性/量化指标） | code-quality.md |
| 🔴 | 新增代码已检查 iOS 跨平台可移植性（core 层无 Android 独占 API、平台能力走接口抽象、新平台依赖有 iOS 对等方案——游戏未来做 iOS 端） | code-quality.md 跨平台章节 |
| 🔴 | 新增玩法系统已遵循 `rules/expansion-playbook.md` 全流程（引擎注册/惰性结算层级/EventBus/RNG 分区/DialogType/Migration/存档兼容/进度锚定游戏时间/引导接入/配置开关/守卫测试） | expansion-playbook.md |
| 🔴 | 新增玩法 UI 已优先复用现有组件（`GameButton`/`UnifiedGameDialog`/`ItemCard`/`SpriteImage`/`CircularCheckbox` 等，组件清单见 `rules/expansion-playbook.md` UI 组件复用优先），禁止自建重复组件；确需新建的通用组件放 core/ui 并登记回清单 | expansion-playbook.md UI 组件复用优先 |
| 🔴 | 新增货币/经济资源已遵循 `rules/economy-design.md`（必要性论证/持有上限/源汇闭环/通胀防控/奖励价值审计） | economy-design.md |
| 🔴 | 新增付费点位（广告/IAP/月卡/战令/活动）已遵循 `rules/commercialization.md`（冷却或领取窗口/慷慨原则/隐私合规双入口） | commercialization.md |
| 🔴 | 新增运营活动（历战卡片/运营邮件/RemoteConfig 配置）已遵循 `rules/commercialization.md`（配置化/时间窗三态/本地默认值兜底） | commercialization.md |
| 🔴 | 新增排行榜/社交功能已遵循 `rules/social-system.md`（异步社交/好友榜优先/分层奖励/数据合规/不污染 AI 外交路径） | social-system.md |
| 🔴 | 新增埋点事件已遵循 `rules/data-analytics.md`（无 PII/非阻塞/事件字典登记） | data-analytics.md |
| 🟡 | 新增功能模块具备配置化启停开关 | 设计方案原则 2 |

> 归并说明：以上扩展方向条目为**设计级**（全流程遵循）；现有广告 watchAd 统一入口（代码级）、渲染双路径/Vulkan 降级/Build.SOC_API 守卫（代码级）等条目保留不动，两者层级不同不重复。

**13.4 🔴 detekt 配置** (`android/config/detekt/detekt.yml`)：
```yaml
style:
  MaxLineLength:
    maxLineLength: 120      # Compose UI 链式调用需要；import/KDoc标签/URL 除外
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

### 设计方案基本原则

设计方案必须遵循以下基本原则（第1~5条），作为方案评审的核心标准：

**1. 🔴 方案符合编写规范** — 设计方案必须使用统一结构编写，包含：**背景与目标**（需求要点+成功标准）、**技术方案**（架构变化+关键类/接口+数据流）、**影响范围清单**（所有受影响的文件/模块及其变更方式）、**兼容性分析**（Migration/序列化/存档）、**测试方案**（单元测试+对抗性审查要点）、**风险评估与兜底**、**未来场景推演**（≥6 个月档：规模增长/生命周期/平台扩张/运营演进/兼容回退，防"只看眼前"）、**技术债与偿还计划**（每个"现在不全做"的决定必须有明确偿还触发条件；无债也需显式声明）。禁止使用非结构化的段落描述替代规范方案文档。方案文档必须独立可读，不依赖口头补充。**提交用户确认前必须逐项过一遍 `rules/design-plan-review.md` 方案自检清单**（YAGNI 反向检查/测试墙钟核算/规则交叉核对/决策分级——每一条来自真实教训）。

**2. 🔴 功能模块化设计** — 新增功能必须设计为可独立发布、可单独测试、可通过配置开关控制启停的模块。模块之间通过接口通信，内部实现变更不影响外部调用方。禁止将新功能硬编码嵌入既有类或函数中形成面条式代码。新增前必须检查是否可通过扩展已有模块（`:core:engine/domain/` 或 `:core:engine/system/`）实现，避免重复造轮。

**3. 🔴 全局视角** — 设计方案必须覆盖变更波及的所有子系统：UI（跨屏适配+输入法避让+对话框栈）、存储（Migration+向前兼容+向后兼容）、渲染（Vulkan + Canvas 双路径验证）、测试（单元测试+集成测试+对抗性审查）、后续平台扩展（iOS 接入点预留）。方案中必须包含**"影响范围清单"**（格式：`文件路径 — 变更类型 — 变更说明`），遗漏视为方案不完整。同时需检查变更是否与进行中或规划中的其他功能存在冲突，必要时协调优先级。

影响范围清单追加两个检查项：
- **经济影响**（`经济` 标签）：涉及货币/奖励发放的方案必须列"源与汇"分析（产出源 + 消耗汇闭环，见 `rules/economy-design.md`）
- **iOS 影响**（`iOS` 标签）：涉及平台能力（时间/存储/网络/加密/通知/支付/广告/分享）的方案必须列 Android/iOS 对等实现分析（见 `rules/code-quality.md` 跨平台章节）

**4. 🔴 低高端设备兼容设计** — 所有涉及渲染、UI 框架、原生库加载的功能变更，必须预先评估在低端/老旧设备上的兼容性。具体要求：

- **渲染管线双路径** — 任何渲染特性变更必须同时验证 Vulkan 和 Canvas（软件渲染）两套路径，确保低端 GPU（Mali-G5x/6x、PowerVR、Adreno 6xx 等）在 Vulkan 驱动缺陷下可用软件渲染降级
- **系统 HWUI 层同步降级** — 关闭 Vulkan 渲染决策必须同步关闭 Activity 级硬件加速（`android:hardwareAccelerated="false"`），因为定制 ROM（Magic UI、澎湃OS、OriginOS 等）可能在 Android < 15 上回传 SkiaVK/Vulkan HWUI，仅关闭游戏渲染层不够
- **所有 Activity 入口统一检查** — `MainActivity` 和 `GameActivity` 等所有 Activity 的 `onCreate()` 必须在 `super.onCreate()` 前检查 `VulkanPolicy.isAccelerationDisabled()` 并切换主题。仅保护一个 Activity 会导致启动阶段崩溃
- **API 级别守卫** — 所有 `Build.SOC_MANUFACTURER`（API 31+）、`Build.SOC_MODEL`（API 31+）等新增 API 字段必须在访问前用 `Build.VERSION.SDK_INT >= 31` 守卫，禁止在 `Application.onCreate()` 等启动阶段无保护访问，否则低 API 设备触发 `NoSuchFieldError` 直接闪退
- **GPU 黑名单对齐行业标准** — 参考 Unity Vulkan Device Filtering（Mali GPU Vulkan API<1.0.61 自动降级）、Flutter Impeller（自动检测 MTK SoC 回退 OpenGL ES）、Chromium（Mali-G57 全面禁用 Vulkan）等行业标杆，持续更新已知问题 GPU/SoC 列表。新增黑名单条目需附带 Bugly 崩溃数据或行业报告引用
- **iOS 侧对等要求（2026-08-04 起，游戏未来做 iOS 端）** — 渲染特性变更必须给出 iOS 侧对等实现方案（Metal 或软件渲染等价路径，参照 `SoftwareCanvasBackend`）；iOS 无 Vulkan/HWUI 问题，但需评估 Metal 驱动兼容性与 A8 及以下老机型。跨平台可移植性约束详见 `rules/code-quality.md` 第 1.5 节

**5. 🔴 隐私合规与隐私政策同步更新** — 设计方案涉及以下变更时，必须同步评估和更新隐私政策：
- 新增或变更第三方 SDK（含 SDK 版本升级、初始化时机变化、数据收集范围变化）
- 新增或变更个人信息收集的类型、目的、方式
- 新增或变更权限申请（Android 权限或 iOS 隐私权限）
- 新增网络请求或变更数据传输方式（新增服务器端点、新增请求头信息等）
- 新增或变更数据共享第三方
- 新增或变更广告/分析/推送模块

隐私政策更新必须覆盖两个入口：
1. **游戏内隐私政策**（`PrivacyConsentScreen.kt`）— 更新展示内容或三方 SDK 链接
2. **网站版隐私政策**（`docs/index.html`，发布在 `https://hsmy7.github.io/mnzm/`）— 更新网页内容

变更说明需记录在方案文档的"影响范围清单"中，标注 `隐私合规` 标签。

**6. 🔴 扩展方向对标（2026-08-04 起）** — 设计玩法/商业化/社交/数据类新功能时，deep-research 调研必须覆盖对应方向主题（专项调研每方向 ≥5 条 S/A 级来源），不得只做通用玩法调研：

| 功能方向 | 必调研主题 | 参考规则文件 |
|---------|-----------|-------------|
| 新玩法系统 | 叙事事件设计（RimWorld/鬼谷八荒）、放置经济、FTUE、多进度系统错峰 | rules/expansion-playbook.md |
| 商业化 | 货币化/ARPU/LTV 对标、战令/月卡设计、LiveOps 活动运营、慷慨原则 | rules/commercialization.md |
| 社交/排行 | 异步社交、排行榜分层奖励、赛季重置、社交合规 | rules/social-system.md |
| 数据运营 | 留存漏斗基准（D1/D7/D30）、埋点规范、A/B 测试 | rules/data-analytics.md |
| 经济扩展 | 源与汇、通胀防控、新货币引入、奖励价值审计 | rules/economy-design.md |

**方案必须是可长期维护的成熟方案，禁止分阶段/渐进式交付。** 设计方案应当一次性完整覆盖所有影响点（包括 UI、存储、测试、旧数据兼容），不允许遗留"后续优化"。方案本身即为最终态，执行者照单实施即可，不应需要自行补充或二次设计。

**最优方案不计成本且需跨 iOS 平台：** 不考虑时间成本、人力成本等任何实施成本，要求**全量采纳**同类型热门游戏（原神、星铁、米哈游系、网易系、腾讯系、莉莉丝、鹰角、叠纸等）的先进设计。若因产品定位、技术栈限制等客观原因无法全量使用，必须逐条说明无法采纳的原因和替代方案。

**方案必须考虑 iOS 跨平台兼容性：** 所有设计方案（UI 组件、渲染管线、手势系统、存储格式、网络协议等）必须确保 Android 与 iOS 两套平台均可落地，优先选择跨平台一致的方案。若技术方案依赖 Android 独占 API 或平台特定特性，须在方案中给出 iOS 侧的对等实现方案。

**硬性指标：**
- 行业参考来源 **不得少于 20 条**，且必须来自权威渠道
- 所有参考数据必须是 **本年加前两年（当前年份和前两年）** 的最新数据（以当前日期为准），禁止引用过时资料
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

## Version Release

When releasing, update `version.properties` (project root, single source of truth):
- `versionCode` — increment by 1
- `versionName` — format `X.XX.XX` (one-digit major + two-digit minor + two-digit build, zero-padded). E.g., `4.00.86` → `4.00.87`, `4.00.99` → `4.01.00`, `4.99.99` → `5.00.00`. Never `4.0.86` (missing zero-pad in minor segment).

Tests must run serially: `./gradlew.bat test --max-workers=1` (parallel runs fail due to shared static state across classes).

See `rules/version-release.md` for the full release checklist.
