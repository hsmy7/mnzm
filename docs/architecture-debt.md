# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 写入守卫架构债务（⏸️ 暂不修复）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)，6 项低风险守卫设计限制记录在案：
1. `store` 底层存储绕过守卫
2. `requireWrite` / `onWrite` 为 `@JvmField var`
3. `writeGuardEnabled` 全局开关
4. `ids` public MutableList
5. `deathRecords` public MutableList
6. 影子结算死代码（已移除守卫兼容代码）

## 邮件/兑换码 RNG 未接入分区 PRNG（⏸️ 低优先级）

`RedeemCodeManager.kt` 顶层使用 `DeterministicRng.fromSeed(System.nanoTime())` 作为随机数源，
以下路径未走 `GameRngManager` 分区 PRNG：

- `generateDiscipient()` — 弟子属性/灵根/天赋随机生成
- `generateRandomEquipment()` — 装备随机生成（间接调用 `EquipmentDatabase.generateRandom`）

影响范围：
- 运营补偿邮件中通过 `MailAttachment(type="disciple")` 发放弟子时，弟子属性随机使用顶层 RNG
- 兑换码系统中所有随机物品/弟子的生成

建议：迁移至 `GameRngManager.getRng(RngPartition.SYSTEM)`，与邮件系统随机操作统一。
当前风险低（邮件/兑换码操作不涉及战斗或突破，RNG 不一致不影响核心玩法）。

## 引擎 suspend API 线程安全自动化（🔴 待实施）

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
