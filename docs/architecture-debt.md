# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 角色状态系统：纯推导式迁移（✅ 已完成 — 2026-07-26）

**对标：** RimWorld（状态从当前执行任务推导，不手动设置）、MineColonies（三层状态机推导）
**现状：** 已完成全量纯推导迁移（3 阶段）：
- Phase 1：`deriveDiscipleStatus` 纯函数 + `SlotFlags` data class，`syncAllDiscipleStatuses` 重构调用纯函数
- Phase 2：废除 26 处直接 `discipleTables.statuses[id] = X` 写入（保留 REFLECTING/ON_MISSION/REFINING 受保护写入），11 个文件修改
- Phase 3：`DiscipleLifecycleManager.getDiscipleStatus` 统一调用 `deriveDiscipleStatus`，消除 60 行重复推导逻辑

**增量升级：** 新增 `syncSingleDiscipleStatus(discipleId)` 事件驱动增量推导，O(1) 更新替代 O(n) 批量扫描

**守卫：** `StatusDerivationCoverageTest` — 新增 DiscipleStatus 值时自动检测 SlotFlags / deriveDiscipleStatus / buildSlotFlagsFor 三处同步

## 写入守卫架构债务（⏸️ 其余暂不修复）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：

- ✅ **writeGuardEnabled ThreadLocal 隔离**（已完成 — 2026-07-26）
- ✅ **`ids` 封装为 `List<Int>` 只读视图**（已完成 — 2026-07-26）— `private val _ids` + `addId()`/`removeId()` 守卫方法
- ✅ **`deathRecords` 封装为 `List<DeathRecord>` 只读视图**（已完成 — 2026-07-26）— `private val _deathRecords` + `addDeathRecord()` 守卫方法；`DiscipleLifecycleProcessor` 直接 deathRecords.add 生产 Bug 修复
- ✅ **影子结算死代码清理**（已完成 — 2026-07-26）— 残余 KDoc 引用更新；`copyRowFrom()` 死代码移除
- ⏸️ `store` 底层存储绕过守卫
- ⏸️ `requireWrite` / `onWrite` 为 `@JvmField var`

## 邮件/兑换码 RNG 未接入分区 PRNG（✅ 已完成 — 2026-07-26）

已全量接入 `GameRngManager.getRng(RngPartition.MAIL)`：

- ✅ 新增 `RngPartition.MAIL(5)` 专用分区
- ✅ `EquipmentDatabase`/`HerbDatabase`/`ItemDatabase`/`ManualDatabase` 的 `generateRandom*` 方法增加可选 `random: kotlin.random.Random` 参数
- ✅ `RedeemCodeManager` 所有随机路径（`generateDisciple`/`generateRandomTalents`/`generateReward`）通过传入的 `random` 参数走 MAIL 分区
- ✅ `MailService.distributeAttachmentsInline()` 注入 `GameRngManager`，传 `mailRng` 到所有 `generateRandom*` 调用
- ✅ `RedeemCodeService` 同理注入并传参
- ✅ 守卫测试 `GameRngManagerTest.exportStates` 已更新为动态分区数量

## 引擎 suspend API 线程安全自动化（✅ 已完成 — 2026-07-25）

详见 [architecture.md](architecture.md#threading-architecture-two-game-threads) 的线程安全约定。
所有直接调 `stateStore.update{}` 的 `suspend` 引擎方法已通过 `EngineContextDispatcher` 接口 + `withEngineContext` 自动派发到引擎线程。
测试通过 `FakeEngineContextDispatcher` 注入绕过 mockito suspend 限制。

### 问题

`GameStateStoreImpl.update()` (第 598-610 行) 包含主线程守卫，Release 构建中检测到主线程调用时**静默跳过更新**。
当前已有约 70 处 ViewModel/Dialog 调用路径通过手工修复（`launchOnEngine` 或 `withContext(Default)`）规避了此问题，
但所有直接调用 `stateStore.update{}` 的 `suspend` 引擎方法**没有编译器保障**，新代码仍可能引入同类违规。

### 根因

引擎层的 `suspend` 方法（如 `updateGameData`、`updateDiscipleStatus`、`releaseDiscipleFromAllSlotsAtomic` 等）
直接调用 `stateStore.update{}`，但未在内部切换到引擎线程。调用方必须手工确保在引擎线程上调用，
这是一个容易遗漏的隐式契约。

### 根治方案

在所有直接调用 `stateStore.update{}` 的 `suspend` 引擎方法内部添加 `withContext(gameDispatcher)` 包裹，
使这些 API 从任意线程调用时自动切换到引擎线程执行 `stateStore.update{}`。
`GameEngineCore.withEngineContext` 方法已新增，可直接使用。

### 影响文件

- `GameEngineCoordination.kt` — `updateGameData`, `updateDisciple`, `updateGameDataAndSync`, `enterSect`, `changeDiscipleTypeAtomic`, `forgetManual`, `replaceManual`, `learnManual`, `recruitAllFromList`, `startBloodRefinementAtomic`, `cancelBloodRefinement`, `processBloodRefinementCompletions`
- `GameEngineDiscipleOps.kt` — `updateDiscipleStatus`, `releaseDiscipleFromAllSlotsAtomic`, `dismissDisciple`, `expelDisciple`, `syncAllDiscipleStatuses`, `resetAllDisciplesStatus`
- `GameEngineAtomicAssign.kt` — 6 个原子方法 (`assignToResidenceAtomic`, `removeFromResidenceAtomic`, `assignPatrolAtomic`, `removePatrolAtomic`, `swapPatrolAtomic`, `autoAssignPatrolAtomic`)
- `GameEngineProductionOps.kt` — `startAlchemy`, `startForging`, `toggleAutoRestart`

### 优先级

🔴 高 — 防止未来新代码引入同类 Bug。当前已知违规已全部修复，此改造是长效预防措施。

### 注意事项

- `withContext(gameDispatcher)` 在已处于引擎线程时是 no-op（Kotlin 协程运行时自动优化跳过调度）
- 对 `DomainResult.catching` 返回模式的原子方法需调整结构：将 `withContext` 包裹在返回值外部

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

## 生命周期 ⏸️ 项（低优先级，已从架构文档移入）

以下为 `BootPhase/RunState` 双层状态机的低优先级待优化项，当前行为正确，无用户可见问题：

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

## 云存档序列化双路径同步债务（✅ 已完成守卫 — 2026-07-26）

**问题等级：** 🔴 架构级 — 每新增一个 GameData 字段都可能造成云存档静默丢失

### 现象

本次修复前，`GameData` 有 66 个字段（含 `placedBuildings`、`portraitRes`、`worldLevels`、`rngStates` 等核心数据）未通过云存档序列化路径保存，云存档≈本地存档的快照版本，长期无人发现。

### 根因

存档序列化存在两条路径，但没有任何机制保证它们同步：

```
本地存档: GameData（Room @Entity）→ Room DB（自动覆盖全部 @ColumnInfo 字段）
云存档:   GameData → SaveDataConverter.convertGameData() → SerializableGameData → Protobuf
```

每新增一个 GameData 字段，需要手动同步 3 处：
1. `SerializableGameData.kt` — 定义 `@ProtoNumber(n)` 字段
2. `SaveDataConverter.convertGameData()` — 正向映射
3. `SaveDataConverter.convertBackGameData()` — 反向映射

没有编译期检查或守卫测试。开发者加字段时没有云存档序列化意识，导致自云存档上线以来新字段全部漏加。

### 关联的预存问题

1. **`SerializableBattleLogMember` 字段命名不匹配域模型** — `discipleId` vs `id`、`remainingHp` vs `hp`、`remainingMp` vs `mp`，与 `com.xianxia.sect.core.model.BattleLogMember` 字段名不一致，转换时需要手动映射，易出错

2. **序列化层零守卫** — 没有编译期检查或测试守卫（Guard Test）来检测新的 `GameData` 字段是否已被序列化路径覆盖

### 建议根治方案

**方案 A：守卫测试（低成本）**
新增 `SerializationCoverageTest`，用反射对比 `GameData` 的所有属性名与 `SerializableGameData` 的属性名（或 `convertGameData` 中映射的字段），新增字段未覆盖时测试失败并提示需要同步三处。详见 `docs/architecture-debt-write-guard.md` 的守卫测试模式。

**方案 B：消除中间层（高成本）**
让云存档直接序列化 `GameData` 本身（它已有 `@Serializable` 注解），消除 `SerializableGameData` 中间层。但需解决：
- Room `@Ignore` 字段与序列化的冲突
- `@ProtoNumber` 与 kotlinx.serialization 默认编码的兼容性
- 对现有云存档的反向兼容

**方案 C：自动生成/代码生成**
利用 Kotlin Symbol Processing (KSP) 在编译期从 `GameData` 属性声明自动生成 `SerializableGameData` 和 converter 代码，消除手动维护。

### 完成情况

✅ **守卫测试已落地** — `SerializationCoverageTest` 等 6 个测试文件覆盖 GameData + 全部嵌套类型。
新增 GameData 字段时，守卫测试自动失败并提示同步 3 处（SerializableGameData / convertGameData / convertBackGameData）。

远期方案 C（KSP 代码生成）仍为可选优化路径，已非必需。
