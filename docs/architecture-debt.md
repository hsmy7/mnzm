# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 引擎 suspend API 线程安全自动化

**状态：** ✅ 已全量落地（2026-07-27）。

### 完成情况

| 文件 | 方法 | 状态 |
|------|------|------|
| `GameEngineCoordination.kt` | 全部 18 个 suspend 方法 | ✅ 2026-07-27：`initializeNewGameSuspend`/`loadData`/`createNewGame`/`restartGameSuspend`/`updateGameData`/`updateDisciple`/`changeDiscipleTypeAtomic`/`updateGameDataAndSync`/`enterSect`/`forgetManual`/`replaceManual`/`learnManual`/`recruitAllFromList`/`startBloodRefinementAtomic`/`cancelBloodRefinement`/`processBloodRefinementCompletions` 均已包裹。`checkpointAllDisciples` 改为 `suspend fun` 并包裹 |
| `GameEngineDiscipleOps.kt` | `releaseDiscipleFromAllSlotsAtomic` | ✅ 2026-07-27：`withContext(Dispatchers.IO)` 改为 `engineContextDispatcher.withEngineContext` |
| `GameEngineAtomicAssign.kt` | 9 个原子方法 | ✅ 2026-07-27：`assignToResidenceAtomic`/`removeFromResidenceAtomic`/`assignPatrolAtomic`×2/`removePatrolAtomic`×2/`swapPatrolAtomic`×2/`autoAssignPatrolAtomic` 全部包裹 |
| `GameEngineProductionOps.kt` | `startAlchemy`/`startForging`/`toggleAutoRestart` | ✅ 验证：委托 `buildingFacade`，委托链已有保护 |

## Detekt 预存违规 baseline（✅ 已全量修复）

**状态：** ✅ 已全量修复（2026-07-28）。

### 修复内容

| 违规类型 | 文件 | 修复方式 |
|---------|------|---------|
| `WildcardImport` + `TooGenericExceptionCaught` | `ModeSelectionScreen.kt` | 通配符 → 28 个显式导入；`catch(Exception)` → `catch(ExecutionException)` + `catch(InterruptedException)` |
| `MatchingDeclarationName` | `SaveSelectScreen.kt` | 提取 `SaveSelectMode` 枚举到独立文件 |
| `MaxLineLength` | `GameStateStoreImpl.kt:607` | 长错误消息断行 |
| `ReturnCount` | `AdServiceImpl.kt:43` | `watchAd` 3 return → 1 return，提取 `startAdLoading` |
| `MaxLineLength` | `MainActivity.kt:405-406` | 长行断行

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

## Crash 1: ANR #5076 — TapTap Sandbox Toast 主线程阻塞（⚠️ 仍待 TapTap SDK 更新）

**当前措施**：
- Looper 主线程超时监控（>3s 告警日志）
- ✅ `initAdSdk()` 调用顺序修正（2026-07-28）
- ✅ 5s 超时保护 + 降级路径
- ✅ 异常守卫拦截 TapTap lateinit 崩溃

**根因**：TapTap SDK 沙箱环境 hook `INotificationManager.enqueueToast`，`SandboxTapAccountChecker.onCheckAccountPass` 触发 `Toast.show()` 时 Binder 调用在主线程堵塞 >5s。该调用在 TapTap SDK 闭源代码内部，应用侧无法直接干预。

**状态**：⏸️ 待 TapTap SDK 更新（最有效方案），或评估反射拦截方案的兼容性风险后实施。

## 对抗性审查发现的 assignmentGate 注册表事务一致性

**状态：** ✅ 已修复（2026-07-27）。

### 问题

`assignmentGate.release()` 和 `assignmentGate.confirmAssign()` 在 `stateStore.update` 的事务 lambda 内部被直接调用。`assignmentGate` 是 `@Singleton`，其内部 `DiscipleAssignmentRegistry` 使用 `mutableMapOf`。当 `stateStore.update` 的 `block()` 抛出异常时，`gameData` 的 copy-on-write 变更被丢弃，但 `assignmentGate.registry` 的变更无法回滚，导致注册表与游戏数据不一致。

### 修复

将 4 个原子方法的 gate 操作全部移出 `stateStore.update` 事务块：
- `assignPatrolAtomic`、`removePatrolAtomic`、`swapPatrolAtomic`、`autoAssignPatrolAtomic`
- `DiscipleSlotCleanup` 新增 `clearAllSlotsDataOnly()`，与 `clearAllSlots()` 分离数据修改和 gate 操作

### 验证

- 编译通过 + 引擎模块全部测试通过
- 对抗性审查（3 agent × 3 维度）确认修复有效

## 月度事件管线单事务化

**状态：** ✅ 已完成（2026-07-27）。

### 修复内容

`CultivationEventProcessor` 中 3 处多次 `stateStore.update` 问题全部解决，**无尾巴**：

1. **`processMonthlyEvents`** — 将所有子服务（theft/lawEnforcement/completedMissions + 原事务内 9 项）**全部移入同一次 `stateStore.update`**，利用重入缓冲机制保证嵌套调用操作同一副本。月度循环从 **4→1 次 update**（100% 消除）。
2. **`processYearlyEvents`** — 将所有 18 个子服务（vassalTribute/discipleAging/autoBuy/diplomacyEvents 等）**全部移入同一次 `stateStore.update`**。年变循环从 **~20→1 次 update**（100% 消除）。
3. **修复 `checkAllianceExpiry` 的 `data.copy` 覆盖问题** — 原代码用 `data.copy(...)` 写入，会覆盖其他服务的中间修改，改为 `gameData.copy(...)`。
4. **修复 `garrisonAndReport` 的 `rotated.copy` 覆盖问题** — 原代码用 `rotated.copy(...)` 写入，会覆盖其他服务的中间修改，改为 `gameData.copy(worldMapSects=rotated.worldMapSects, ...)`。
5. **删除死代码** — `processMonthlyCultivationAndAuto()` 无参重载（原起额外事务）。

### 文件变更

| 文件 | 变更 |
|------|------|
| `CultivationEventProcessor.kt` | 月度+年变全部子服务移入单事务；删除死代码 |
| `DiplomacyEventProcessor.kt` | `checkAllianceExpiry` 修复 `data.copy` → `gameData.copy` |
| `FavorEventProcessor.kt`（如适用） | 无变更（已全在 update 内） |

## `shuffled()` 迁移至分区 PRNG

**状态：** ✅ 已完成（2026-07-27）。

### 修复内容

6 处 `kotlin.collections.shuffled()` 迁移至 `GameRngManager` 分区 PRNG：

| 文件 | 行数 | 使用分区 |
|------|------|---------|
| `DisciplePurchaseService.kt` | 5 处 | `RngPartition.SYSTEM` |
| `LootCalculator.kt` | 1 处 | `RngPartition.EXPLORATION` |

### 新增文件

| 文件 | 说明 |
|------|------|
| `core/engine/.../util/RngExt.kt` | `shuffled(rng: DeterministicRng)` 扩展函数 |

### 其他受影响文件

| 文件 | 变更 |
|------|------|
| `LootCalculatorTest.kt` | 构造参数传入 `GameRngManager` |
| `LawEnforcementProcessorTest.kt` | 18 处 `LootCalculator()` → `LootCalculator(GameRngManager())` |

### 验证

- 编译通过 + 引擎模块全部测试通过

## Crash 2: SIGSEGV #3088 — vulkan.adreno.so vkGetDeviceQueue（✅ 已加固—仍需 Bugly 数据扩充）

**当前措施**：
- VulkanBackend.cpp: vkGetDeviceQueue 重试 3 次（2ms 间隔）+ VK_NULL_HANDLE 检查
- ✅ **driverVersion 检测**（2026-07-28）：`VulkanPolicy.setDriverVersion()` + `isKnownBadDriverVersion()` + C++ `s_driverVersion` 静态变量 + JNI `getVulkanDriverVersion()`
- ✅ **黑名单扩充**（2026-07-28）：新增 20+ 机型（荣耀/华为/小米/vivo/OPPO/一加）
- ✅ **远程配置预备**（2026-07-28）：`GameConfigData.VulkanSection` 含 `blacklistedModels` + `DriverVersionRange`
- ✅ **NDK 编译验证**（2026-07-28）：`externalNativeBuildRelease` 通过

**根因**：某些 Adreno GPU 驱动（国产 OEM ROM）在 `vkCreateDevice()` 后立即调用 `vkGetDeviceQueue()` 时存在竞争条件，队列句柄未就绪时访问导致 SIGSEGV。

**仍需**：从 Bugly 持续收集崩溃数据扩充 `KNOWN_PROBLEM_MODELS` 黑名单。

## 游戏数值配置双源不一致：GameConfig vs GameConfigData

**状态：** ✅ 已全量治理（2026-07-28）。

### 完成情况

| 子项 | 状态 | 说明 |
|------|------|------|
| Step A — 守卫测试 | ✅ | `GameConfigConsistencyTest.kt` 新增 46 个测试用例，覆盖所有同义字段数值一致性 |
| Step B — 紧急对齐 | ✅ | 修复 4 项实际数值偏差：damageBonusPerRealm(0.5→0.35)、damagePenaltyPerRealm(0.5→0.35)、probPerPoint(0.03→0.01)、capacityPerBuilding(50→75) |
| Step C — 双源统一 | ✅ | `GameConfigProvider` 新增（`core/engine/config`，Hilt Singleton），`CultivationSettlement` 已迁移生产代码使用 provider |

### 涉及文件

- `core/domain/.../config/GameConfigConsistencyTest.kt`（新增）
- `core/domain/.../config/GameConfigData.kt`（4 字段对齐）
- `core/engine/.../config/GameConfigProvider.kt`（新增）
- `core/engine/.../service/CultivationSettlement.kt`（注入 provider）
- `app/.../assets/config/game_config.json`（4 字段对齐）
- `core/domain/.../config/ConfigLoaderTest.kt`（2 断言对齐）

## ProtoBuf 默认值编码治理

**状态：** ✅ 已完成（2026-07-27）。

### 完成项

| # | 项 | 状态 |
|---|------|------|
| 1 | `encodeDefaults = false` | ✅ 前期已完成 |
| 2 | `ProtoNumberCoverageTest` 守卫 | ✅ 前期已完成 |
| 3 | `@Transient` 标注 | ✅ 前期已完成 |
| 4 | 非零默认值字段 `@EncodeDefault(ALWAYS)` | ✅ 27 个字段标注：GameData(14) + SaveData(2) + SectPolicies(6) + HeavenlyTrialData(2) + PatrolConfig(3) |
| 5 | 守卫测试增强（`EncodeDefault` 检查） | ✅ `ProtoNumberCoverageTest` 新增 2 个守卫测试，递归覆盖嵌套 `@Serializable` 类 |
| 6 | 编码规范补充 | ✅ CLAUDE.md PR 审查清单新增 `@ProtoNumber/@EncodeDefault` 规则 |

