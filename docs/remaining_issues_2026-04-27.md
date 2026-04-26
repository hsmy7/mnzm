# XianxiaSectNative 代码质量报告 — 剩余问题追踪

**生成日期**: 2026-04-27
**基线版本**: 2.5.66 (versionCode 2134)
**基线报告**: docs/code_quality_report_2026-04-26.md
**已修复问题数**: 32/40 (80%)

---

## 修复进度总览

| 优先级 | 总数 | 已修复 | 部分修复 | 未修复 |
|:------:|:----:|:------:|:--------:|:------:|
| P0 | 7 | 7 | 0 | 0 |
| P1 | 18 | 10 | 3 | 5 |
| P2 | 15 | 10 | 3 | 2 |
| **合计** | **40** | **27** | **6** | **7** |

---

## 一、未修复问题（7项）

### U-01: A2 — Disciple 双模型并存

| 维度 | 数据 |
|------|------|
| 优先级 | P3（长期） |
| 位置 | [DiscipleAggregate.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleAggregate.kt) |
| 当前行数 | 362 行 |
| 委托属性 | 80+ 个 `get()` 委托 |
| 影响 | 双向转换 `toDisciple()`/`fromDisciple()` 仍活跃，维护成本翻倍 |

**现状**: `Disciple`（旧单体模型）和 `DiscipleAggregate`（新聚合模型）并存。Aggregate 通过 80+ 委托属性暴露子模型字段，`toDisciple()` 方法（L244-345）仍在生产环境被调用。

**迁移计划**:

| 阶段 | 目标 | 具体操作 |
|:----:|------|----------|
| 1 | 消除循环依赖 | `DiscipleAggregate.getBaseStats()` 不再调用 `toDisciple()`，改为直接在 Aggregate 上计算 |
| 2 | 收敛写入路径 | 所有写入统一通过 `Disciple` Entity，`DiscipleAggregate` 只做只读映射 |
| 3 | 简化委托属性 | `DiscipleAggregate` 持有 `Disciple` ID + 扩展计算属性，不再逐属性委托 |
| 4 | 最终状态 | `Disciple` 纯 Entity，`DiscipleAggregate` 纯 UI 视图，无循环转换 |

**风险**: 阶段3需要修改大量 UI 调用点，属于破坏性变更。建议在功能冻结期执行。

---

### U-02: A3 — StorageEngine.kt 未拆分

| 维度 | 数据 |
|------|------|
| 优先级 | P1（中期） |
| 位置 | [StorageEngine.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt) |
| 当前行数 | 920 行（从 1768 行降至 920 行，已减少 48%） |
| 构造参数 | 16 个依赖注入 |
| 影响 | 难以维护和测试 |

**现状**: 已从 1768 行降至 920 行（拆分了 CircuitBreaker/MemoryGuard/PruningScheduler/ArchiveScheduler），但核心类仍包含 save/load/delete/export/emergency/backup/integrity/cache 等全部职责。

**拆分方案**:

```
data/engine/
├── StorageEngine.kt          ← 核心读写逻辑（~400行）
├── StorageIntegrity.kt       ← 完整性校验（validateIntegrity、Merkle）
├── StorageBackup.kt          ← 备份/恢复逻辑
├── StorageWal.kt             ← WAL 快照管理
└── StorageMetrics.kt         ← 存储指标收集
```

---

### U-03: A5 — StorageFacade 10 处 runBlocking

| 维度 | 数据 |
|------|------|
| 优先级 | P1（中期） |
| 位置 | [StorageFacade.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt) |
| runBlocking 数量 | 10 处（Facade）+ 1 处（Engine）= 11 处 |
| 影响 | IO 线程池中调用可能死锁，主线程调用可能 ANR |

**现状**: 所有 10 处均已标注 `@WorkerThread`，调用方应在后台线程。但 `runBlocking` 本身在 IO 线程池中仍可能耗尽线程导致死锁。

**方法清单**:

| 方法 | 行号 | 用途 |
|------|------|------|
| `saveSync` | 253 | 同步保存 |
| `saveSyncWithResult` | 266 | 同步保存（带结果） |
| `loadSync` | 278 | 同步加载 |
| `getSaveSlots` | 314 | 获取存档槽列表 |
| `loadEmergencySave` | 350 | 加载紧急存档 |
| `clearEmergencySave` | 362 | 清除紧急存档 |
| `emergencySave` | 373 | 紧急保存 |
| `hasSave` | 388 | 检查存档是否存在 |
| `isSaveCorrupted` | 400 | 检查存档是否损坏 |
| `getStorageUsage` | 506 | 获取存储用量 |

**改进方向**: 为每个同步方法提供异步 `suspend` 替代（大部分已存在），逐步迁移调用方，最终将同步方法标记 `@Deprecated`。

---

### U-04: C1 — ProductionViewModel 1205 行

| 维度 | 数据 |
|------|------|
| 优先级 | P1（中期） |
| 位置 | [ProductionViewModel.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/ProductionViewModel.kt) |
| 当前行数 | 1205 行（从 1548 行降至 1205 行，已减少 22%） |
| 公开方法 | 60+ |
| 影响 | 炼丹/锻造/种植/灵矿/长老管理/执法堂等职责严重过载 |

**现状**: 已通过 `launchElderAction` 消除了部分重复代码，但 ViewModel 仍承担过多职责。

**拆分方向**: 按生产领域拆分为独立 ViewModel：
- `AlchemyViewModel` — 炼丹相关
- `ForgeViewModel` — 锻造相关
- `HerbGardenViewModel` — 药园/种植相关
- `SpiritMineViewModel` — 灵矿相关

---

### U-05: P2-8 — Pill 和 PillEffect 字段完全重复

| 维度 | 数据 |
|------|------|
| 优先级 | P2（次要） |
| 位置 | [Items.kt:553-732](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/Items.kt#L553-L732) |
| 影响 | `Pill` 包含所有效果字段，`PillEffect` 包含完全相同的字段，`Pill.effect` getter 只是逐个复制 |

**修复方案**: `Pill` 使用 `@Embedded val effects: PillEffect` 替代平铺字段，删除 `Pill.effect` getter。

**注意**: 需要提供 Room Migration，字段名从 `effectXxx` 变为 `effects_effectXxx`，属于破坏性 schema 变更。

---

### U-06: P2-13 — 三个性能监控类标记废弃但未删除

| 维度 | 数据 |
|------|------|
| 优先级 | P2（次要） |
| 位置 | [GamePerformanceMonitor.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/performance/GamePerformanceMonitor.kt), [PerformanceMonitor.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/PerformanceMonitor.kt), [UnifiedPerformanceMonitor.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/performance/UnifiedPerformanceMonitor.kt) |
| 影响 | `GameMonitorManager` 仍同时依赖三个类，`@Suppress("DEPRECATION")` 压制了废弃警告 |

**修复方案**: 将 `GamePerformanceMonitor` 和 `PerformanceMonitor` 的独有功能合并到 `UnifiedPerformanceMonitor`，更新 `GameMonitorManager` 的依赖，然后删除两个废弃类。

---

### U-07: 错误处理体系碎片化

| 维度 | 数据 |
|------|------|
| 优先级 | P3（长期） |
| 影响 | 8 套独立错误/结果类型，缺乏统一映射 |

**当前错误类型清单**:

| 错误类型 | 位置 | 子类数 |
|----------|------|:------:|
| `GameError` | [GameError.kt:3](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GameError.kt#L3) | — |
| `ProductionError`（顶层） | [ProductionError.kt:59](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/production/ProductionError.kt#L59) | 5 |
| `ProductionError`（嵌套） | [ProductionParams.kt:90](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/production/ProductionParams.kt#L90) | 5（与顶层重复） |
| `ProductionTransactionError` | [ProductionTransactionManager.kt:27](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/transaction/ProductionTransactionManager.kt#L27) | — |
| `VerificationResult` | [CryptoModule.kt:455](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/crypto/CryptoModule.kt#L455) | — |
| `ValidationResult`（InputValidator） | [InputValidator.kt:164](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/InputValidator.kt#L164) | 4 |
| `ValidationResult`（BuildingConfig） | [BuildingConfigService.kt:354](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/config/BuildingConfigService.kt#L354) | 2 |

**关键问题**:
- `ProductionError` 存在两份几乎完全重复的定义
- `ProductionTransactionError` 与 `ProductionError` 子类高度重叠
- `ValidationResult` 存在两份结构不同的定义
- 各错误类型之间没有继承或转换关系

**统一目标**: 收敛为 3 层体系（`AppError` → `DomainError` → `UiError`），详见原报告"系统性问题改进策略"第4节。

---

## 二、部分修复问题（6项）

### PF-01: P1 — GameStateStore 高频对象创建

| 维度 | 数据 |
|------|------|
| 位置 | [GameStateStore.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt) |
| 已修复 | `update()` 方法改用 `MutableGameState` 可变状态复用，避免事务内 `.copy()` |
| 未修复 | `setPausedDirect`/`setLoadingDirect`/`setSavingDirect`（L154-185）仍用 `.copy()` |

**残余影响**: 三个 `setXxxDirect` 方法在 CAS 循环中每次迭代创建完整 `UnifiedGameState` 副本。但调用频率较低（仅在暂停/加载/保存状态变更时），实际 GC 压力有限。

**可选优化**: 将三个 Boolean 字段拆分为独立 `MutableStateFlow<Boolean>`，但需确保与 `UnifiedGameState` 的一致性。

---

### PF-02: P7 — 独立 CoroutineScope

| 维度 | 数据 |
|------|------|
| 已修复 | 9 个组件已迁移到 `ApplicationScopeProvider` |
| 未修复 | [GameEngineCore.kt:69](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/engine/GameEngineCore.kt#L69) 自行创建 `engineScope`，未使用 `ApplicationScopeProvider` |

**残余影响**: Engine 的协程生命周期不受 `ApplicationScopeProvider` 统一管理，shutdown 时需单独处理。

**修复方案**: `GameEngineCore` 注入 `ApplicationScopeProvider`，将 `engineScope` 替换为 `applicationScopeProvider.scope`。需注意 Engine 的 scope 重建逻辑（L196）需要重新设计。

---

### PF-03: P2-1 — GameUtils 标准库包装

| 维度 | 数据 |
|------|------|
| 位置 | [GameUtils.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GameUtils.kt) |
| 已修复 | 标记 `@Deprecated` + `ReplaceWith` |
| 未修复 | 方法本身未删除，仍有调用方 |

**待办**: 全局替换调用方后删除废弃方法（`clamp` → `coerceIn`，`isEmpty` → `isNullOrEmpty`，`padLeft` → `padStart`，`padRight` → `padEnd`）。

---

### PF-04: P2-2 — BattleCalculator 重复计算方法

| 维度 | 数据 |
|------|------|
| 位置 | [BattleCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt) |
| 已修复 | `calculatePhysicalDamage`/`calculateMagicDamage` 标记 `@Deprecated` |
| 未修复 | 方法本身未删除 |

**待办**: 全局替换调用方为 `calculateDamage(isPhysicalAttack = true/false)`，然后删除废弃方法。

---

### PF-05: P2-13 — 性能监控类重叠

（详见 U-06，标记废弃但未删除合并）

---

### PF-06: DiscipleObjectPool.reset() 每次生成 UUID

| 维度 | 数据 |
|------|------|
| 位置 | [DiscipleObjectPool.kt:222-223](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/DiscipleObjectPool.kt#L222-L223) |
| 影响 | `reset()` 中 `id = UUID.randomUUID().toString()` 每次释放对象时产生不必要的分配开销 |

**修复方案**: `reset()` 中将 `id` 设为空字符串占位值，实际 ID 在 `copyFrom()` 或业务逻辑中赋值。

---

## 三、已修复问题确认清单（27项）

### P0 严重问题（7/7 全部修复）

| # | 问题 | 修复版本 | 关键变更 |
|---|------|----------|----------|
| P0-01 | 双存档写入路径 | 2.5.65-66 | GameRepository 删除，所有写入统一走 StorageFacade |
| P0-02 | 完整性校验三重缺陷 | 2.5.65 | 传完整数据、实现 Merkle 验证、constantTimeEquals |
| P0-03 | MainGameScreen 8709行 | 2.5.65 | 拆分为 tabs/dialogs/components，785行 |
| P0-04 | GCOptimizer 协程泄漏 | 2.5.65 | 使用 ApplicationScopeProvider |
| P0-05 | ObjectPool 线程不安全 | 2.5.65-66 | ConcurrentLinkedQueue + 清理残留 import |
| P0-06 | AtomicStateFlowUpdates 混锁 | 2.5.65 | synchronized → ReentrantLock，文档化约束 |
| P0-07 | SecureKeyManager Base64 | 2.5.65 | java.util.Base64 → android.util.Base64 |

### P1 主要问题（10/18 已修复）

| # | 问题 | 修复版本 |
|---|------|----------|
| A1 | GameRepository 构造参数爆炸 | 2.5.65-66 |
| A4 | Hilt 版本不一致 | 2.5.65 |
| C2 | 跨 ViewModel 重复代码 | 2.5.65-66 |
| C3 | errorMessage/successMessage 样板代码 | 2.5.65 |
| C5 | SaveLoadViewModel runBlocking | 2.5.66 |
| P2 | CacheLayer LRU | 2.5.65 |
| P3 | CacheKey TTL | 2.5.65 |
| P4 | WarehouseItemPool 伪池化 | 2.5.65 |
| P5 | StateFlow Eagerly | 2.5.65 |
| P6 | shiftIndicesAfter 创建新 Map | 2.5.65 |
| S1 | StorageFacade.delete() 返回值 | 2.5.65 |
| S2 | isSaveCorrupted() 异常默认值 | 2.5.65 |
| S3 | ProductionTransactionManager getOrThrow | 2.5.65 |
| S4 | GameLoopError.kt 空文件 | 2.5.65 |
| S5 | CancellationException 传播 | 2.5.65 |
| S6 | ChangeTracker.computeChecksum | 2.5.65 |

### P2 次要问题（10/15 已修复）

| # | 问题 | 修复版本 |
|---|------|----------|
| P2-3 | AlchemyRecipe/ForgeRecipe 材料检查重复 | 2.5.65 |
| P2-4 | 时间进度计算重复 | 2.5.65 |
| P2-5 | closeXxxDialog 委托方法 | 2.5.65 |
| P2-6 | showBuildingDetailDialog 重复 | 2.5.65 |
| P2-7 | slotType 字符串 → 枚举 | 2.5.65 |
| P2-9 | getSaveSlots/getSaveSlotsFresh 重复 | 2.5.65 |
| P2-10 | core.data 包名冲突 | 2.5.65 |
| P2-11 | formatMemory 重复 | 2.5.65 |
| P2-12 | generateKey 重复 | 2.5.65 |
| P2-14 | PerformanceModule 空 Module | 2.5.65 |
| P2-15 | LazySlotCache/SlotQueryCache 重复 | 2.5.65 |

---

## 四、优先行动路线图

### 近期（1-2周）— 清理部分修复项

| 行动项 | 工作量 | 风险 |
|--------|:------:|:----:|
| PF-03: 删除 GameUtils 废弃方法（全局替换后） | 小 | 低 |
| PF-04: 删除 BattleCalculator 废弃方法（全局替换后） | 小 | 低 |
| PF-06: DiscipleObjectPool.reset() 去除 UUID 生成 | 小 | 低 |
| U-06: 合并三个性能监控类，删除废弃类 | 中 | 低 |

### 中期（3-6周）— P1 架构优化

| 行动项 | 工作量 | 风险 |
|--------|:------:|:----:|
| U-02: StorageEngine 拆分（920行 → 4-5 文件） | 中 | 中 |
| U-03: StorageFacade runBlocking 消除 | 中 | 中 |
| U-04: ProductionViewModel 拆分（1205行 → 4 ViewModel） | 大 | 中 |
| PF-02: GameEngineCore 迁移到 ApplicationScopeProvider | 小 | 中 |

### 长期（7-12周）— P3 架构演进

| 行动项 | 工作量 | 风险 |
|--------|:------:|:----:|
| U-01: Disciple 双模型迁移 | 大 | 高 |
| U-05: Pill/PillEffect 合并（需 Room Migration） | 中 | 高 |
| U-07: 错误类型体系统一 | 大 | 中 |
| PF-01: GameStateStore setXxxDirect 优化 | 小 | 低 |

---

## 五、代码质量评分更新

| 维度 | 原评分 | 当前评分 | 变化 | 说明 |
|------|:------:|:--------:|:----:|------|
| 架构设计 | 6 | **7** | +1 | GameRepository 删除，存档路径统一 |
| 代码规范 | 5 | **7** | +2 | 魔法数字提取，重复代码消除 |
| 可维护性 | 3 | **5** | +2 | MainGameScreen 拆分，ViewModel 重复消除 |
| 类型安全 | 5 | **6** | +1 | CancellationException 正确传播 |
| 并发安全 | 4 | **7** | +3 | ObjectPool/AtomicStateFlow/GCOptimizer 修复 |
| 性能 | 5 | **6** | +1 | LRU/TTL/WhileSubscribed/原地更新 |
| 安全性 | 4 | **7** | +3 | 完整性校验/Base64/CancellationException |
| **综合** | **4.6** | **6.4** | **+1.8** | |

---

*文档生成时间: 2026-04-27*
*下次复查建议: 完成近期清理项后*
