# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 引擎 suspend API 线程安全自动化

**状态：** ⏸️ 基础设施已完成，方法本体改造未全量落地。

### 已完成基础设施

`EngineContextDispatcher` 接口 + `withEngineContext` 已提取为 `core/engine/EngineContextDispatcher.kt`，
`GameEngineCore` 实现，`GameEngine.engineContextDispatcher` 注入。测试用 `FakeEngineContextDispatcher` 绕过 mockito suspend 限制。

### 当前问题

`GameStateStoreImpl.update()` (598-610 行) 包含主线程守卫，Release 构建中检测到主线程调用时**静默跳过更新**。
当前已知违规已全部手工修复（`launchOnEngine` 或 `withContext(Default)`），但以下引擎 `suspend` 方法
直接调用 `stateStore.update{}` 而未在内部切换到引擎线程——调用方必须手工确保在引擎线程上调用，
这是一个容易遗漏的隐式契约，新代码仍可能引入同类违规。

### 待改造方法

- `GameEngineCoordination.kt` — `updateGameData`, `updateDisciple`, `updateGameDataAndSync`, `enterSect`, `changeDiscipleTypeAtomic`, `forgetManual`, `replaceManual`, `learnManual`, `recruitAllFromList`, `startBloodRefinementAtomic`, `cancelBloodRefinement`, `processBloodRefinementCompletions`
- `GameEngineDiscipleOps.kt` — `updateDiscipleStatus`, `releaseDiscipleFromAllSlotsAtomic`, `dismissDisciple`, `expelDisciple`, `syncAllDiscipleStatuses`, `resetAllDisciplesStatus`
- `GameEngineAtomicAssign.kt` — 6 个原子方法 (`assignToResidenceAtomic`, `removeFromResidenceAtomic`, `assignPatrolAtomic`, `removePatrolAtomic`, `swapPatrolAtomic`, `autoAssignPatrolAtomic`)
- `GameEngineProductionOps.kt` — `startAlchemy`, `startForging`, `toggleAutoRestart`

### 改造方案

在所有上述方法内部添加 `withContext(gameDispatcher)` 包裹，使这些 API 从任意线程调用时自动切换到引擎线程。
`withContext(gameDispatcher)` 在已处于引擎线程时是 no-op（Kotlin 协程运行时自动优化跳过调度）。
对 `DomainResult.catching` 返回模式的原子方法需将 `withContext` 包裹在返回值外部。

## Detekt 预存违规 baseline（⏸️ 低优先级）

detekt 配置 `baseline` 只缩不增（CLAUDE.md 13.2），当前未启用 baseline，以下为预存违规，
未被 baseline 记录但已存在于基线中，新代码不允许新增同类违规：

| 违规类型 | 文件 | 说明 |
|---------|------|------|
| `TooGenericExceptionCaught` | `ModeSelectionScreen.kt:226` | catch(Exception) 应缩小范围 |
| `MatchingDeclarationName` | `SaveSelectScreen.kt` | 文件名与顶层声明名不匹配 |
| `MaxLineLength` | `GameStateStoreImpl.kt:607` | 错误消息超 80 字符 |
| `ReturnCount` | `AdServiceImpl.kt:43` | watchAd 3 个 return 超限 2 |
| `MaxLineLength` | `MainActivity.kt:405-406` | 2 行超 80 字符 |
| `WildcardImport` | `ModeSelectionScreen.kt:8,11` | 2 处通配符 import |

治理策略：后续重构逐步修复，不纳入当前 PR。

## 生命周期 ⏸️ 项（低优先级）

以下为 `BootPhase/RunState` 双层状态机的低优先级待优化项，当前行为正确，无用户可见问题：

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

## 写入守卫架构债务（⏸️ 低优先级）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：
- `store` 底层存储绕过守卫
- `requireWrite` / `onWrite` 为 `@JvmField var`

## 云存档序列化双路径同步债务（✅ 已落地守卫 — 远期可选优化）

### 已完成
- ✅ **守卫测试已落地** — `SerializationCoverageTest` 等 6 个测试文件覆盖 GameData + 全部嵌套类型。
  新增 GameData 字段时，守卫测试自动失败并提示同步 3 处（SerializableGameData / convertGameData / convertBackGameData）。

### 远期优化（⏸️ 可选）
- **`SerializableBattleLogMember` 字段命名对齐域模型** — `discipleId` vs `id`、`remainingHp` vs `hp`、`remainingMp` vs `mp`
- **方案 C：KSP 代码生成** — 从 GameData 声明自动生成 SerializableGameData 和 converter，消除手动同步。已非必需。

