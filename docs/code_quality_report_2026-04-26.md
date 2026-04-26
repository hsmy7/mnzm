# XianxiaSectNative 代码质量全面审查报告

**审查日期**: 2026-04-26  
**项目版本**: 2.5.44 (versionCode 2112)  
**审查范围**: android/app/src/main/java/com/xianxia/sect  
**技术栈**: Kotlin, Jetpack Compose, Room, Hilt, Coroutines, Protobuf

---

## 目录

1. [项目概况](#项目概况)
2. [综合质量评分](#综合质量评分)
3. [严重问题 (P0)](#严重问题-p0)
4. [主要问题 (P1)](#主要问题-p1)
5. [次要问题 (P2)](#次要问题-p2)
6. [系统性问题总结](#系统性问题总结)
7. [优先行动建议](#优先行动建议)
8. [优化改进方案](#优化改进方案)
9. [附录：问题明细表](#附录问题明细表)

---

## 项目概况

| 维度 | 数据 |
|------|------|
| 项目类型 | Android/Kotlin 修仙门派模拟游戏 |
| 架构风格 | 单模块 monolith，包内三层分离 |
| 最低SDK / 目标SDK | 24 / 35 |
| DI框架 | Hilt (Dagger) |
| 数据库 | Room (单DB多slot) |
| 序列化 | kotlinx-serialization + Protobuf |
| Kotlin 文件数 | 200+ |
| 核心代码行数 | ~15,000+ 行（审查范围） |

---

## 综合质量评分

| 维度 | 评分 (1-10) | 说明 |
|------|:-----------:|------|
| 架构设计 | **6** | 三层分离意图清晰，但边界执行不一致，双存档路径是最大风险 |
| 代码规范 | **5** | 基本遵守 Kotlin 规范，但存在巨型文件、通配符 import、空安全处理不一致 |
| 可维护性 | **3** | MainGameScreen 8709 行、ViewModel 间大量重复代码严重阻碍维护 |
| 类型安全 | **5** | 采用了密封类+Result 模式，但多套错误体系并存、runBlocking 泛滥 |
| 并发安全 | **4** | Mutex 与 synchronized 混用、ObjectPool 线程不安全、CAS 重试逻辑脆弱 |
| 性能 | **5** | 建立了完整监控体系，但 GameStateStore 高频创建大对象、缓存策略名不副实 |
| 安全性 | **4** | 加密设计专业，但完整性校验形同虚设、签名比较非时序安全 |
| **综合** | **4.6** | |

---

## 严重问题 (P0)

> 需立即修复，否则可能导致数据丢失、崩溃或安全漏洞

### P0-01: 双存档写入路径导致数据不一致风险

**位置**: [GameRepository.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/GameRepository.kt), [StorageEngine.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt)

**描述**: 项目存在两条并行的存档路径：
- **路径A**: ViewModel -> GameEngine -> GameRepository -> Room DAO（无 WAL/备份/加密）
- **路径B**: ViewModel -> StorageFacade -> StorageEngine -> Room DAO + Cache + WAL + Backup + Crypto

GameRepository 自身的 init 块也在警告此问题。调用者可能混淆走哪条路径，导致数据不一致。

**影响**: 存档数据可能在不知不觉中损坏或丢失，路径A缺少完整性校验和备份保障。

**修复建议**: 明确单一存档入口。GameRepository 应退化为只读查询层，所有写操作统一走 StorageFacade -> StorageEngine。

---

### P0-02: 完整性校验形同虚设（三重缺陷）

**位置**: [StorageEngine.kt:488-524](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt#L488-L524), [CryptoModule.kt:138](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/crypto/CryptoModule.kt#L138), [CryptoModule.kt:188-191](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/crypto/CryptoModule.kt#L188-L191)

**描述**:
1. `validateIntegrity()` 只对 `gameData` 签名，弟子/物品等核心数据全部传空列表
2. `merkleValid` 硬编码为 `true`，Merkle 树验证从未执行
3. `verifyFullDataSignature()` 使用 `==` 比较签名，存在时序攻击风险（同文件 `verifySignedPayload()` 正确使用了 `constantTimeEquals()`）

**影响**: 三重缺陷叠加，存档完整性保护几乎失效。数据被篡改或损坏时无法检测。

**修复建议**:
1. `validateIntegrity()` 传入完整的 SaveData（含所有集合）
2. 实现真正的 Merkle 根验证
3. `verifyFullDataSignature()` 改为 `constantTimeEquals()` 比较

---

### P0-03: MainGameScreen.kt 严重臃肿 — 8709 行

**位置**: [MainGameScreen.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/MainGameScreen.kt)

**描述**: 单文件包含几乎所有游戏 UI 渲染逻辑：主界面布局、多个 Tab 页面、20+ 个 Dialog、战斗日志、世界地图、背包等。

**影响**: 任何 UI 修改都需要在 8709 行中定位代码；编译时间极长；无法有效进行代码审查；极易引入回归问题。

**修复建议**: 按功能拆分为独立 Composable 文件，每个 Dialog 独立一个文件，Tab 页面也拆分为独立文件。

---

### P0-04: GCOptimizer 协程泄漏

**位置**: [GCOptimizer.kt:73](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GCOptimizer.kt#L73), [第265行](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GCOptimizer.kt#L265)

**描述**:
- 第73行: `optimizerJob = CoroutineScope(Dispatchers.Default).launch { ... }` — 每次 `startOptimization()` 创建新 scope，从未取消
- 第265行: `CoroutineScope(Dispatchers.Main).launch { ... }` — 每次通知监听器创建新 scope，从未取消

**影响**: 协程泄漏，长期运行后可能导致 OOM。

**修复建议**: 使用类级别的 `SupervisorJob()` 创建 scope，在 `cleanup()` 中统一取消。

---

### P0-05: ObjectPool.Pool 线程不安全

**位置**: [ObjectPool.kt:61](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/ObjectPool.kt#L61)

**描述**: `available` 使用 `ArrayDeque`（非线程安全），`borrow()` 和 `release()` 可从不同线程并发调用，但没有同步保护。

**影响**: 并发场景下可能发生 `NoSuchElementException` 或数据竞争，导致崩溃。

**修复建议**: 使用 `ConcurrentLinkedQueue` 替代 `ArrayDeque`，或为 `borrow`/`release` 添加同步。

---

### P0-06: AtomicStateFlowUpdates 混合 Mutex 和 synchronized

**位置**: [AtomicStateFlowUpdates.kt:15-48](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/AtomicStateFlowUpdates.kt#L15-L48)

**描述**: 对同一个 `MutableStateFlow`：
- `atomicUpdate()` / `atomicUpdateWithResult()` 使用协程 `Mutex`
- `atomicUpdateSync()` / `atomicRead()` 使用 `synchronized(flow)`

**影响**: 两种锁机制互不感知。如果协程调用 `atomicUpdate()` 同时普通线程调用 `atomicUpdateSync()`，无法保证原子性，可能导致并发更新丢失。

**修复建议**: 统一使用一种锁机制。如果必须支持同步和异步两种模式，应使用同一个底层锁（如 `ReentrantLock`）。

---

### P0-07: SecureKeyManager 使用 java.util.Base64 (API 26+)

**位置**: [SecureKeyManager.kt:979,996](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/crypto/SecureKeyManager.kt#L979)

**描述**: 项目 `minSdk=24`，但使用了 API 26+ 才可用的 `java.util.Base64`。

**影响**: 在 Android 7.0 以下设备运行时会抛出 `NoClassDefFoundError`，导致崩溃。

**修复建议**: 替换为 `android.util.Base64`（API 1+ 可用）。

---

## 主要问题 (P1)

> 应尽快修复，影响代码质量、性能或可维护性

### 架构层面

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| A1 | GameRepository 构造参数爆炸（24个DAO） | [AppModule.kt:122-176](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/di/AppModule.kt#L122-L176) | 违反单一职责，每增实体需改3处 |
| A2 | Disciple 双模型并存（旧 Disciple + 新 DiscipleAggregate） | [Disciple.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/Disciple.kt), [DiscipleAggregate.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/DiscipleAggregate.kt) | 200行委托属性+200行copyWith，维护成本翻倍 |
| A3 | StorageEngine.kt 1768行，包含5个内部类 | [StorageEngine.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt) | 难以维护和测试 |
| A4 | Hilt 版本不一致（Plugin 2.53 vs Runtime 2.56） | [build.gradle](file:///c:/Mnzm/XianxiaSectNative/android/build.gradle) | 编译期与运行期行为不匹配 |
| A5 | StorageFacade 大量 runBlocking（11处） | [StorageFacade.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt) | 协程上下文中可能死锁 |

### 代码质量层面

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| C1 | ProductionViewModel 1548行，职责严重过载 | [ProductionViewModel.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/ProductionViewModel.kt) | 长老/弟子/政策逻辑与 SectViewModel 重复 |
| C2 | 大量跨 ViewModel 重复代码（20+方法） | 多个 ViewModel | isReserveDisciple/assignElder/toggleXxx 等重复 |
| C3 | errorMessage/successMessage 样板代码在7个 ViewModel 中重复 | 多个 ViewModel | 应提取 BaseViewModel |
| C4 | 魔法数字散布（境界值5/7、槽位数2/8/12等） | BattleViewModel, ProductionViewModel 等 | 可读性差，修改易遗漏 |
| C5 | SaveLoadViewModel 中 runBlocking 使用 | [SaveLoadViewModel.kt:888,1111,1138](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/SaveLoadViewModel.kt#L888) | 主线程可能 ANR |

### 性能层面

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P1 | GameStateStore 每次更新创建完整 UnifiedGameState | [GameStateStore.kt:219](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L219) | GC 压力大 |
| P2 | 缓存无 LRU 语义，evictColdData 实为随机淘汰 | [CacheLayer.kt:482](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/cache/CacheLayer.kt#L482) | 缓存效率低 |
| P3 | CacheKey TTL 字段定义但从未使用 | [CacheKey.kt:10](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/cache/CacheKey.kt#L10) | 功能缺失 |
| P4 | WarehouseItemPool.acquire 始终返回新对象，违背池化初衷 | [WarehouseItemPool.kt:39](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/warehouse/WarehouseItemPool.kt#L39) | 对象池退化为缓存 |
| P5 | 15+ 派生 StateFlow 使用 SharingStarted.Eagerly | [GameStateStore.kt:56](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt#L56) | 每次 _state 变化触发15+ map 操作 |
| P6 | shiftIndicesAfter 每次删除创建新 ConcurrentHashMap | [OptimizedWarehouseManager.kt:219](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/warehouse/OptimizedWarehouseManager.kt#L219) | O(n) 内存分配和复制 |
| P7 | 6+ 个独立 CoroutineScope 无统一取消机制 | 多文件 | 生命周期失控 |

### 安全层面

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| S1 | StorageFacade.delete() 返回 Unit，吞掉错误 | [StorageFacade.kt:286](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt#L286) | 删除失败不可感知 |
| S2 | isSaveCorrupted() 异常时默认返回 true | [StorageFacade.kt:406](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt#L406) | 误报存档损坏 |
| S3 | ProductionTransactionManager 使用 getOrThrow() 反模式 | [ProductionTransactionManager.kt:118](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/transaction/ProductionTransactionManager.kt#L118) | Result->异常->再捕获 |
| S4 | GameLoopError.kt 为空文件 | [GameLoopError.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GameLoopError.kt) | 游戏循环缺少错误类型 |
| S5 | ErrorHandler.handleException() 吞掉 CancellationException | [GameResult.kt:84](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GameResult.kt#L84) | 协程取消被静默忽略 |
| S6 | ChangeTracker.computeChecksum() 降级为 hashCode() | [ChangeTracker.kt:302](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/incremental/ChangeTracker.kt#L302) | 校验和失去密码学意义 |

---

## 次要问题 (P2)

> 建议优化，不影响核心功能但增加维护成本

| # | 问题 | 位置 |
|---|------|------|
| 1 | GameUtils 中 clamp/isEmpty/padLeft 等是 Kotlin 标准库的简单包装 | [GameUtils.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/GameUtils.kt) |
| 2 | BattleCalculator 的 calculatePhysicalDamage/calculateMagicDamage 与 calculateDamage 逻辑重复 | [BattleCalculator.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/BattleCalculator.kt) |
| 3 | AlchemyRecipe/ForgeRecipe 的 hasEnoughMaterials/getMissingMaterials 完全相同 | [AlchemySystem.kt:88-106,197-215](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/AlchemySystem.kt#L88) |
| 4 | AlchemySlot/ForgeSlot/ActiveMission 的时间进度计算方法重复 | 多文件 |
| 5 | GameViewModel 中 10+ 个 closeXxxDialog 全部是 closeCurrentDialog 的委托 | [GameViewModel.kt:126-164](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt#L126) |
| 6 | GameViewModel 中 showBuildingDetailDialog 与 openBuildingDetailDialog 完全重复 | [GameViewModel.kt:375-383](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt#L375) |
| 7 | SectViewModel 中 assignElder 的 slotType 使用字符串而非枚举 | [SectViewModel.kt:117](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/ui/game/SectViewModel.kt#L117) |
| 8 | Pill 和 PillEffect 字段完全重复 | [Items.kt:553-649](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/model/Items.kt#L553) |
| 9 | getSaveSlots 与 getSaveSlotsFresh 实现完全相同 | [StorageFacade.kt:302-330](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt#L302) |
| 10 | core.data 包名与 data 包名语义冲突 | 包结构 |
| 11 | formatMemory 在3个文件中重复实现 | GCOptimizer, VivoGCJITOptimizer, MemoryMonitor |
| 12 | generateKey 在4个文件中重复实现 | 仓库相关文件 |
| 13 | 三个性能监控类功能高度重叠 | GamePerformanceMonitor, UnifiedPerformanceMonitor, PerformanceMonitor |
| 14 | PerformanceModule 为空 Hilt Module | [PerformanceModule.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/di/PerformanceModule.kt) |
| 15 | LazySlotCache 与 SlotQueryCache 功能重复 | 两个文件 |

---

## 系统性问题总结

### 1. 过度工程化与实际效果脱节

配置声明了分级缓存（hot/warm/cold）、ADAPTIVE 淘汰策略，但实现是简单的 `ConcurrentHashMap` + 随机淘汰。`CacheKey.ttl` 定义了但从未使用。`IntegrityReport.merkleValid` 硬编码 `true`。这些"设计"存在但"实现"缺失的模式在项目中反复出现。

### 2. 线程安全不一致

部分组件正确使用了 `ConcurrentHashMap`/`CopyOnWriteArrayList`，部分使用普通集合 + `synchronized`，部分（如 `ObjectPool.Pool`、`PerformanceMonitor.listeners`）完全没有保护。`Mutex` 与 `synchronized` 混用导致原子性无法保证。

### 3. 对象池名不副实

`WarehouseItemPool.acquire()` 始终通过 `copy()` 返回新对象，完全违背池化初衷。`DiscipleObjectPool.reset()` 每次生成 UUID 产生不必要开销。

### 4. GC 压力源未有效控制

游戏主循环路径上存在多处高频对象创建：`UnifiedGameState` 复制、列表 filter/map、字符串拼接。监控组件本身也在产生额外分配（`MetricCollector.getStats()` 每次排序 1000 样本）。

### 5. 协程生命周期管理分散

6+ 个独立的 `CoroutineScope`，没有统一的取消机制。`GCOptimizer` 每次创建新 scope 且从不取消。

### 6. 错误处理体系碎片化

8 套独立的错误/结果类型体系（`GameError`/`ProductionError`/`StorageError`/`SaveError`/`KeyError`/`VerificationResult`/`ValidationResult`/`ProductionTransactionError`），缺乏统一映射，`StorageError` 新增枚举值可能静默降级为 `UNKNOWN`。

---

## 优先行动建议

### 立即行动 (P0)

| 优先级 | 行动项 | 工作量 | 风险降低 |
|:------:|--------|:------:|:--------:|
| P0 | 统一存档写入路径，明确 GameRepository 只读或废弃 | 大 | 高 |
| P0 | 修复完整性校验（覆盖全部数据、实现 Merkle 验证、时序安全比较） | 中 | 高 |
| P0 | 拆分 MainGameScreen.kt（8709行 -> 多个独立文件） | 大 | 中 |
| P0 | 修复 GCOptimizer 协程泄漏 | 小 | 中 |
| P0 | 修复 ObjectPool.Pool 线程安全（ArrayDeque -> ConcurrentLinkedQueue） | 小 | 高 |
| P0 | 修复 AtomicStateFlowUpdates 混锁问题 | 小 | 高 |
| P0 | SecureKeyManager 的 java.util.Base64 替换为 android.util.Base64 | 小 | 高 |
| P0 | 统一 Hilt 版本（2.53 -> 2.56） | 小 | 中 |

### 近期行动 (P1)

| 优先级 | 行动项 | 工作量 |
|:------:|--------|:------:|
| P1 | 消除 SectViewModel 和 ProductionViewModel 间的重复代码 | 中 |
| P1 | 提取 BaseViewModel 统一 errorMessage/successMessage | 小 |
| P1 | StorageFacade 的 runBlocking 方法添加 @WorkerThread 注解 | 小 |
| P1 | 拆分 StorageEngine.kt 为 4-5 个独立文件 | 中 |
| P1 | 修复 GameStateStore 高频对象创建（拆分 StateFlow 或缓存计算结果） | 中 |
| P1 | 修复缓存策略（实现 LRU、启用 TTL、合并 LazySlotCache/SlotQueryCache） | 中 |
| P1 | 统一 6+ 个独立 CoroutineScope 到 ApplicationScopeProvider | 中 |

### 中期行动 (P2)

| 优先级 | 行动项 | 工作量 |
|:------:|--------|:------:|
| P2 | 提取魔法数字为常量 | 小 |
| P2 | 统一空安全处理策略 | 小 |
| P2 | 清理废弃方法（formBattleTeam、showBuildingDetailDialog 等） | 小 |
| P2 | slotType 字符串参数改为枚举 | 小 |
| P2 | 重命名 core/data 为 core/registry | 中 |

### 长期行动 (P3)

| 优先级 | 行动项 | 工作量 |
|:------:|--------|:------:|
| P3 | 制定 Disciple 双模型迁移计划 | 大 |
| P3 | 拆分 GameData 为多 Entity | 大 |
| P3 | 统一错误类型体系 | 大 |

---

## 优化改进方案

> 基于源码验证的逐项修复方案，含根因分析、改造步骤和代码示例

### P0 严重问题修复方案

#### P0-01: 双存档写入路径 — 统一为 StorageFacade 单一入口

**根因分析**: `GameRepository` 和 `StorageEngine` 都直接操作 `GameDatabase`，但 `GameRepository.saveAll()` 绕过了缓存层、WAL 快照和备份机制。通过 `GameRepository` 保存的数据不会更新缓存，后续通过 `StorageEngine.load()` 可能读到旧缓存数据。

**改造步骤**:

1. **GameRepository 退化为只读查询层** — 所有写方法标记 `@Deprecated`，内部委托到 `StorageFacade`
2. **逐步迁移调用方** — 将 ViewModel 中直接调用 `GameRepository.saveAll()` 的地方改为 `StorageFacade.save()`
3. **最终删除 GameRepository 的写方法** — 确认无调用方后移除

```kotlin
// GameRepository.kt — 改造后
class GameRepository @Inject constructor(
    private val database: GameDatabase,
    private val storageFacade: StorageFacade,
    // DAO 保留用于只读查询
    private val gameDataDao: GameDataDao,
    // ... 其他 DAO
) {
    @Deprecated("Use StorageFacade.save() instead", ReplaceWith("storageFacade.save(slot, data)"))
    suspend fun saveAll(slot: Int, data: SaveData) {
        storageFacade.save(slot, data)
    }

    // 只读方法保留
    suspend fun getGameData(slot: Int): GameData? = gameDataDao.getBySlot(slot)
    // ...
}
```

**迁移风险**: 低。`StorageFacade.save()` 内部已调用 `StorageEngine.save()`，后者包含完整的缓存更新、WAL 记录和备份逻辑。

---

#### P0-02: 完整性校验三重缺陷 — 逐项修复

**根因分析**:
- 缺陷1: `validateIntegrity()` 为避免额外数据库查询而传空列表，导致签名只覆盖 `gameData`
- 缺陷2: `merkleValid` 硬编码 `true`，Merkle 树验证尚未实现
- 缺陷3: `verifyFullDataSignature()` 使用 `==` 比较，而同文件 `verifySignedPayload()` 已正确使用 `constantTimeEquals()`

**改造步骤**:

**缺陷1 — 传入完整 SaveData**:

```kotlin
// StorageEngine.kt validateIntegrity() — 改造后
suspend fun validateIntegrity(slot: Int): StorageResult<IntegrityReport> {
    // 加载完整数据而非只加载 gameData
    val saveData = loadSaveData(slot)  // 新方法：加载全部集合
        ?: return StorageResult.failure(StorageError.SLOT_NOT_FOUND)

    val key = keyManager.getOrCreateSlotKey(slot)
        ?: return StorageResult.failure(StorageError.KEY_ERROR)

    val dataHash = crypto.computeFullDataSignature(saveData, key)
    // ... 后续校验逻辑
}
```

**缺陷2 — 实现 Merkle 根验证**（复用已有 `IntegrityValidator.computeMerkleRoot()`）:

> **注意**: `CryptoModule.kt` 中 `IntegrityValidator` 已有基于 JSON 序列化的确定性 Merkle 根实现（使用 `dataToJsonString()` -> `json.encodeToString()` -> 按 key 排序递归计算），应直接复用，不要使用 `toString()` 生成叶子节点（`toString()` 输出非确定性，不同 JVM 版本/实例可能产生不同字符串）。

```kotlin
// StorageEngine.kt validateIntegrity() 中 — 复用已有 IntegrityValidator
val computedMerkleRoot = integrityValidator.computeMerkleRoot(saveData)
val storedMerkleRoot = loadStoredMerkleRoot(slot)
val merkleValid = constantTimeEquals(computedMerkleRoot, storedMerkleRoot)  // 时序安全比较
```

**关键改动**:
- 复用 `IntegrityValidator.computeMerkleRoot()` 而非重新实现
- Merkle 根比较使用 `constantTimeEquals()` 而非 `==`（与缺陷3保持一致，避免引入相同的时序攻击漏洞）

**缺陷3 — 时序安全比较**:

```kotlin
// CryptoModule.kt verifyFullDataSignature() — 改造后
fun verifyFullDataSignature(data: Any, expectedSignature: String, key: ByteArray): Boolean {
    val actualSignature = computeFullDataSignature(data, key)
    return constantTimeEquals(actualSignature, expectedSignature)  // 使用已有的 constantTimeEquals
}
```

---

#### P0-03: MainGameScreen.kt 拆分方案

**根因分析**: 8709 行单文件包含所有游戏 UI，是典型的"上帝 Composable"反模式。

**改造步骤** — 按功能域拆分为独立文件：

```
ui/game/
├── MainGameScreen.kt          ← 主框架（~200行）：Scaffold + Tab 导航
├── tabs/
│   ├── SectTab.kt             ← 宗门 Tab
│   ├── ProductionTab.kt       ← 生产 Tab
│   ├── BattleTab.kt           ← 战斗 Tab
│   ├── WorldMapTab.kt         ← 世界地图 Tab
│   └── InventoryTab.kt        ← 背包 Tab
├── dialogs/
│   ├── WorldMapDialog.kt      ← 世界地图弹窗
│   ├── SecretRealmDialog.kt   ← 秘境弹窗
│   ├── BattleLogDialog.kt     ← 战斗日志弹窗
│   ├── RecruitDialog.kt       ← 招募弹窗
│   ├── DiplomacyDialog.kt     ← 外交弹窗
│   ├── MerchantDialog.kt      ← 商人弹窗
│   ├── BuildingDetailDialog.kt← 建筑详情弹窗
│   └── ...                    ← 其他 Dialog
└── components/
    ├── BattleLogItem.kt       ← 战斗日志条目组件
    ├── DiscipleCard.kt        ← 弟子卡片组件
    └── ResourceBar.kt         ← 资源条组件
```

**拆分原则**:
- 每个 Dialog 独立一个文件，接收 ViewModel 回调作为 lambda 参数
- Tab 页面独立文件，通过 `@Composable` 函数暴露
- `MainGameScreen.kt` 只保留 Scaffold 框架和 Tab 切换逻辑
- 共享 UI 组件提取到 `components/` 目录

**Dialog 状态管理**: 当前单文件中 Dialog 状态通过 ViewModel 的 `DialogStateManager` 统一管理（同时只显示一个 Dialog）。拆分后需保持此约束，建议使用 `DialogState` 密封类 + 单一 `MutableStateFlow<DialogState?>` 管理所有 Dialog 的显示/隐藏状态，各 Dialog 文件通过 lambda 回调触发状态变更：

```kotlin
sealed class DialogState {
    data class WorldMap(val regionId: String? = null) : DialogState()
    data class SecretRealm(val realmId: String) : DialogState()
    data class BattleLog(val filter: BattleLogFilter?) : DialogState()
    data class BuildingDetail(val buildingId: String) : DialogState()
    // ... 其他 Dialog
}

// MainGameScreen.kt 中统一消费
val dialogState by viewModel.dialogState.collectAsState()
when (val state = dialogState) {
    is DialogState.WorldMap -> WorldMapDialog(state, onDismiss = viewModel::closeDialog, ...)
    is DialogState.SecretRealm -> SecretRealmDialog(state, onDismiss = viewModel::closeDialog, ...)
    // ...
}
```

---

#### P0-04: GCOptimizer 协程泄漏 — 统一 CoroutineScope

**根因分析**: `startOptimization()` 每次创建 `CoroutineScope(Dispatchers.Default)` 但只保存 `Job`，scope 本身泄漏。`notifyListeners()` 每次创建匿名 scope 且 `Job` 未保存。

**改造方案**:

```kotlin
class GCOptimizer @Inject constructor(...) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var optimizerJob: Job? = null

    fun startOptimization() {
        stopOptimization()
        optimizerJob = scope.launch {  // 使用类级别 scope
            // ... 原有逻辑
        }
    }

    private fun notifyListeners(action: (GCEventListener) -> Unit) {
        mainScope.launch {  // 使用类级别 mainScope
            listeners.forEach { listener ->
                try { action(listener) } catch (e: Exception) { Log.e(TAG, "Error", e) }
            }
        }
    }

    fun cleanup() {
        optimizerJob?.cancel()
        scope.cancel()    // 取消所有子协程
        mainScope.cancel()
    }
}
```

---

#### P0-05: ObjectPool.Pool 线程安全 — ConcurrentLinkedQueue

**根因分析**: `ArrayDeque` 非线程安全，`borrow()` 和 `release()` 可从不同线程并发调用，`@Singleton` 注解意味着全局共享。

**改造方案**:

```kotlin
class Pool<T : Any>(private val factory: () -> T, private val maxSize: Int) {

    private val available = ConcurrentLinkedQueue<T>()
    private val currentSize = AtomicInteger(0)

    fun borrow(): T {
        return available.poll() ?: createNew()
    }

    fun release(instance: T) {
        while (true) {
            val current = currentSize.get()
            if (current >= maxSize) return  // 池满，丢弃
            if (currentSize.compareAndSet(current, current + 1)) {
                available.offer(instance)
                return
            }
        }
    }

    private fun createNew(): T {
        while (true) {
            val current = currentSize.get()
            if (current >= maxSize) {
                return factory()  // 超出池管理范围，直接创建
            }
            if (currentSize.compareAndSet(current, current + 1)) {
                return factory()
            }
        }
    }
}
```

**注意**: `ConcurrentLinkedQueue` 的 `size` 是 O(n) 操作，因此用 `AtomicInteger` 单独追踪大小。`createNew()` 和 `release()` 使用 CAS 循环确保精确的 `maxSize` 限制，避免竞态条件导致池大小超过上限。

---

#### P0-06: AtomicStateFlowUpdates 混锁 — 分离协程/同步路径锁

**根因分析**: `Mutex`（协程锁）和 `synchronized`（JVM 监视器锁）互不感知，同一 `MutableStateFlow` 上两种锁无法互斥。此外 `flow.hashCode()` 作为 Mutex key 不可靠（哈希碰撞导致误同步或漏同步）。

**改造方案** — 协程路径保留 `Mutex`（非阻塞挂起），同步路径使用 `ReentrantLock`，两者通过文档约束禁止混用：

```kotlin
private val flowMutexes = ConcurrentHashMap<MutableStateFlow<*>, Mutex>()
private val flowLocks = java.util.Collections.synchronizedMap(
    WeakHashMap<MutableStateFlow<*>, ReentrantLock>()
)

private fun getMutex(flow: MutableStateFlow<*>): Mutex {
    return flowMutexes.getOrPut(flow) { Mutex() }
}

private fun getLock(flow: MutableStateFlow<*>): ReentrantLock {
    return flowLocks.getOrPut(flow) { ReentrantLock() }
}

suspend inline fun <T> atomicUpdate(
    flow: MutableStateFlow<T>,
    crossinline transform: (T) -> T
): T {
    val mutex = getMutex(flow)
    return mutex.withLock {  // 非阻塞挂起，不占用线程
        val newValue = transform(flow.value)
        flow.value = newValue
        newValue
    }
}

inline fun <T> atomicUpdateSync(
    flow: MutableStateFlow<T>,
    transform: (T) -> T
): T {
    val lock = getLock(flow)
    return lock.withLock {
        val newValue = transform(flow.value)
        flow.value = newValue
        newValue
    }
}
```

**关键改动**:
- 用 `flow` 对象本身（而非 `hashCode`）作为锁 map 的 key，避免哈希碰撞
- 协程路径使用 `Mutex`（非阻塞挂起），避免 `ReentrantLock` 阻塞 Default dispatcher 线程池导致线程饥饿
- 同步路径使用 `ReentrantLock`，支持 JVM 线程间互斥
- `flowLocks` 使用 `WeakHashMap` 避免内存泄漏（StateFlow 被废弃后锁对象可被 GC 回收）

**约束**: 同一个 `MutableStateFlow` 不得混用 `atomicUpdate` 和 `atomicUpdateSync`，否则两种锁仍无法互斥。应在代码审查中强制检查此约束。

---

#### P0-07: SecureKeyManager Base64 兼容性修复

**根因分析**: `java.util.Base64` 在 API 26+ 才可用，项目 `minSdk=24`。`@Suppress("NewApi")` 压制了 lint 警告但未解决兼容性问题。

**改造方案**:

```kotlin
// SecureKeyManager.kt — 改造后
// 第979行 — 原代码使用 Base64.getEncoder().encodeToString()（有填充，有换行）
val result = android.util.Base64.encodeToString(
    tokenIv + encryptedKey,
    android.util.Base64.NO_WRAP  // 无换行，保留填充（与原行为一致）
)

// 第996行
val tokenBytes = android.util.Base64.decode(token, android.util.Base64.NO_WRAP)
```

**注意**: 原代码第979行使用 `Base64.getEncoder().encodeToString()`（非 `withoutPadding()`），产生有填充的 Base64。`android.util.Base64.NO_WRAP` 对应"无换行、有填充"行为，与原代码一致。不要使用 `NO_PADDING`，否则会导致恢复令牌解析失败。

---

### P1 主要问题修复方案

#### A1: GameRepository 构造参数爆炸 — 按领域拆分 Repository

**改造方案**（与 P0-01 保持一致：子 Repository 写操作委托 StorageFacade）:

```kotlin
// 拆分为领域 Repository — 只读查询层，写操作委托 StorageFacade
class DiscipleRepository @Inject constructor(
    private val discipleDao: DiscipleDao,
    private val discipleCoreDao: DiscipleCoreDao,
    private val discipleCombatStatsDao: DiscipleCombatStatsDao,
    private val discipleEquipmentDao: DiscipleEquipmentDao,
    private val discipleExtendedDao: DiscipleExtendedDao,
    private val discipleAttributesDao: DiscipleAttributesDao,
    private val storageFacade: StorageFacade,  // 写操作委托
) {
    // 只读查询
    suspend fun getDisciple(id: String): Disciple? = discipleDao.getById(id)
    suspend fun getDisciplesBySlot(slot: Int): List<Disciple> = discipleDao.getBySlot(slot)

    // 写操作委托 StorageFacade（保证统一写入路径，不绕过缓存/WAL/备份）
    suspend fun saveDisciples(slot: Int, data: SaveData) = storageFacade.save(slot, data)
}

class EquipmentRepository @Inject constructor(
    private val equipmentStackDao: EquipmentStackDao,
    private val equipmentInstanceDao: EquipmentInstanceDao,
    private val storageFacade: StorageFacade,
)

class ProductionRepository @Inject constructor(
    private val forgeSlotDao: ForgeSlotDao,
    private val alchemySlotDao: AlchemySlotDao,
    private val productionSlotDao: ProductionSlotDao,
    private val recipeDao: RecipeDao,
    private val storageFacade: StorageFacade,
)

class GameRepository @Inject constructor(
    private val database: GameDatabase,
    private val gameDataDao: GameDataDao,
    private val discipleRepository: DiscipleRepository,
    private val equipmentRepository: EquipmentRepository,
    private val productionRepository: ProductionRepository,
    // ... 其他领域 Repository
)
```

**迁移策略**: 采用"替换"而非"追加"策略——每创建一个子 Repository，立即从 `GameRepository` 构造参数中移除对应的 DAO，保持参数总数不增。子 Repository 的写操作统一委托 `StorageFacade`，与 P0-01 的"统一存档写入路径"原则保持一致，避免通过 DAO 直接写入绕过缓存/WAL/备份机制。

---

#### A2: Disciple 双模型 — 渐进迁移计划

**当前状态**: `Disciple`（856行）是 Room Entity + 业务模型混合体，`DiscipleAggregate`（357行）是 UI 视图模型，两者通过 `toAggregate()`/`toDisciple()` 循环转换。

**迁移计划**:

| 阶段 | 目标 | 具体操作 |
|:----:|------|----------|
| 1 | 消除循环依赖 | `DiscipleAggregate.getBaseStats()` 不再调用 `toDisciple()`，改为直接在 Aggregate 上计算 |
| 2 | 收敛写入路径 | 所有写入操作统一通过 `Disciple` Entity，`DiscipleAggregate` 只做只读映射 |
| 3 | 简化委托属性 | `DiscipleAggregate` 不再逐属性委托，改为持有 `Disciple` ID + 扩展计算属性 |
| 4 | 最终状态 | `Disciple` 纯 Entity，`DiscipleAggregate` 纯 UI 视图，无循环转换 |

```kotlin
// 阶段3 — DiscipleAggregate 简化（持有 ID 而非引用，避免不可变性破坏）
data class DiscipleAggregate(
    val discipleId: String,  // 仅持有 ID，需要时从 Store 查询最新数据
    val name: String,
    val realm: Int,
    val computedStats: ComputedDiscipleStats,
) {
    // 从 Disciple 构建，但不持有引用
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleAggregate = DiscipleAggregate(
            discipleId = disciple.id,
            name = disciple.name,
            realm = disciple.realm,
            computedStats = ComputedDiscipleStats.from(disciple),
        )
    }
}
```

**注意**: `DiscipleAggregate` 持有 `Disciple` 的 ID 而非引用，因为 `Disciple` 是 data class（不可变），在 StateFlow 中作为值传递。如果 `DiscipleAggregate` 持有 `Disciple` 引用，当 `GameStateStore` 更新弟子数据时，`DiscipleAggregate` 仍持有旧引用，导致状态不一致。

---

#### A3: StorageEngine.kt 拆分方案

**当前状态**: 1768行，包含5个内部类。

**拆分方案**:

```
data/engine/
├── StorageEngine.kt          ← 核心读写逻辑（~400行）
├── StorageIntegrity.kt       ← 完整性校验（validateIntegrity、Merkle）
├── StorageBackup.kt          ← 备份/恢复逻辑
├── StorageWal.kt             ← WAL 快照管理
├── StorageMigration.kt       ← 数据迁移逻辑
└── StorageMetrics.kt         ← 存储指标收集
```

---

#### A4: Hilt 版本统一

**改造方案**:

```groovy
// build.gradle (project-level) — 第12行
classpath 'com.google.dagger:hilt-android-gradle-plugin:2.56'  // 2.53 -> 2.56
```

**验证**: 修改后执行 `./gradlew assembleDebug` 确认编译通过。

---

#### A5 + C5: runBlocking 消除方案

**根因分析**: `StorageFacade` 中 11 处 `runBlocking` 和 `SaveLoadViewModel` 中 3 处 `runBlocking` 在主线程调用时可能 ANR。

**改造方案**:

**StorageFacade** — 同步方法标记 `@WorkerThread`，异步方法优先提供：

```kotlin
// StorageFacade.kt — 改造后
@WorkerThread  // 明确标记：不可在主线程调用
fun saveSync(slot: Int, data: SaveData): Boolean {
    return runBlocking(Dispatchers.IO) { save(slot, data).isSuccess }
}

// 推荐使用的异步版本（已有）
suspend fun save(slot: Int, data: SaveData): StorageResult<SaveData>
```

**SaveLoadViewModel** — `onCleared()` 中使用 `runBlocking` 是不可避免的（ViewModel 销毁时必须同步等待），但应缩短超时：

```kotlin
// SaveLoadViewModel.kt onCleared() — 改造后
override fun onCleared() {
    runBlocking {
        withTimeoutOrNull(2_000L) {  // 5s -> 2s
            gameEngineCore.stopGameLoopAndWait(2000)  // 3s -> 2s
        }
    }
    runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(3_000L) {  // 5s -> 3s
            storageFacade.save(autoSaveSlot, saveData)
        }
    }
}
```

**`pauseAndSaveForBackground()`** — 改为非阻塞，但使用 `ApplicationScopeProvider` 确保保存协程不被 ViewModel 生命周期取消：

```kotlin
fun pauseAndSaveForBackground() {
    val saveData = buildSaveData()  // 先同步构建保存数据快照
    applicationScopeProvider.scope.launch(Dispatchers.IO) {  // 不使用 viewModelScope
        withTimeoutOrNull(5_000L) {
            storageFacade.save(autoSaveSlot, saveData)
        }
    }
}
```

**关键改动**: 后台保存不能依赖 `viewModelScope`（在 `onCleared()` 时会取消所有子协程），应使用 `ApplicationScopeProvider` 确保保存协程完成。保存数据的构建（`buildSaveData()`）必须在协程启动前同步完成，确保数据快照在协程启动前已捕获。

---

#### C1 + C2: ViewModel 重复代码消除

**改造方案** — 提取共享 UseCase 层：

```kotlin
// 长老管理 UseCase
class ElderManagementUseCase @Inject constructor(
    private val gameEngine: GameEngineCore,
) {
    fun assignElder(slotType: ElderSlotType, discipleId: String): GameResult<Unit> { ... }
    fun removeElder(slotType: ElderSlotType): GameResult<Unit> { ... }
    fun isReserveDisciple(discipleId: String, slotType: ElderSlotType): Boolean { ... }
}

// SectViewModel 和 ProductionViewModel 共享同一 UseCase
@HiltViewModel
class SectViewModel @Inject constructor(
    private val elderUseCase: ElderManagementUseCase,
    // ...
)

@HiltViewModel
class ProductionViewModel @Inject constructor(
    private val elderUseCase: ElderManagementUseCase,
    // ...
)
```

---

#### C3: BaseViewModel 提取

```kotlin
abstract class BaseViewModel : ViewModel() {
    private val _errorEvents = Channel<String>(Channel.BUFFERED)
    val errorEvents = _errorEvents.receiveAsFlow()

    private val _successEvents = Channel<String>(Channel.BUFFERED)
    val successEvents = _successEvents.receiveAsFlow()

    protected fun showError(message: String) {
        _errorEvents.trySend(message)
    }

    protected fun showSuccess(message: String) {
        _successEvents.trySend(message)
    }
}
```

**注意**: 使用 `Channel` 而非 `MutableStateFlow` 避免快速连续错误覆盖问题。`StateFlow` 的 `value =` 赋值会覆盖前一条消息，导致 UI 只显示最后一条错误。`Channel` 保证每条消息都被消费。

---

#### P1: GameStateStore 高频对象创建

**改造方案** — 保留 `UnifiedGameState` 保证原子性更新，优化派生 Flow 计算策略：

> **重要**: 不能将 `UnifiedGameState` 拆分为独立 StateFlow。游戏主循环更新时 `gameData` 和 `disciples` 通常需要一起更新（如弟子升级同时影响弟子属性和宗门资源），拆分后两个 Flow 的更新无法原子完成，UI 可能观察到中间状态（新弟子数据 + 旧资源数据），导致显示不一致甚至计算错误。此外，`StorageFacade.save()` 需要获取完整的、一致的 SaveData 快照，拆分后无法保证快照一致性。

```kotlin
class GameStateStore @Inject constructor(...) {
    private val _state = MutableStateFlow(UnifiedGameState())

    // 事务性更新：保证原子性
    suspend fun updateState(transform: (UnifiedGameState) -> UnifiedGameState) {
        transactionMutex.withLock {
            _state.value = transform(_state.value)
        }
    }

    // 派生 Flow 使用 WhileSubscribed + distinctUntilChanged 减少不必要的计算
    val gameData = _state.map { it.gameData }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), GameData())

    val disciples = _state.map { it.disciples }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())

    // ... 其他派生 Flow
}
```

**优化重点**:
1. `Eagerly` → `WhileSubscribed(5_000, replayExpirationMillis = 30_000)`：无订阅者时停止计算，5 秒超时避免配置变更闪烁，30 秒缓存过期避免内存浪费
2. 优化 `UnifiedGameState` 的复制开销：利用已有的 `MutableGameState` 事务模式，在事务内修改可变状态，提交时才创建不可变快照
3. `distinctUntilChanged` 确保只有实际变化的字段触发下游重组

---

#### P2 + P3: 缓存策略修复

**CacheLayer LRU 实现**（含复合操作保护）:

```kotlin
class CacheLayer(...) {
    private val memoryCache = object : LinkedHashMap<CacheKey, CacheEntry>(
        16, 0.75f, true  // accessOrder = true → LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>): Boolean {
            return size > maxEntryCount
        }
    }

    @Synchronized
    fun get(key: CacheKey): CacheEntry? {
        val entry = memoryCache[key] ?: return null
        if (entry.isExpired) {
            memoryCache.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: CacheKey, entry: CacheEntry) { memoryCache[key] = entry }

    // 复合操作：先检查再写入，在同一个锁内完成
    @Synchronized
    fun getOrPut(key: CacheKey, defaultValue: () -> CacheEntry): CacheEntry {
        val existing = memoryCache[key]
        if (existing != null && !existing.isExpired) return existing
        val newEntry = defaultValue()
        memoryCache[key] = newEntry
        return newEntry
    }
}
```

**CacheKey TTL 启用**:

```kotlin
data class CacheEntry(
    val data: Any,
    val createdAt: Long = System.currentTimeMillis(),
    val ttl: Long = CacheKey.DEFAULT_TTL,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() - createdAt > ttl
}

// CacheLayer.get() 中检查过期
@Synchronized
fun get(key: CacheKey): CacheEntry? {
    val entry = memoryCache[key] ?: return null
    if (entry.isExpired) {
        memoryCache.remove(key)
        return null
    }
    return entry
}
```

---

#### P4: WarehouseItemPool 修复

**改造方案** — 删除伪池化层，调用方直接构造 `WarehouseItem`：

> **注意**: 当前 `WarehouseItemPool.acquire()` 始终通过 `copy()` 返回新对象，池化效果为零。方案A（删除伪池化）是推荐方案，因为 `WarehouseItem` 是 data class，真正池化需要将其改为 mutable class，会破坏不可变性约定（影响 `copy()`/`equals()`/`hashCode()` 自动生成，以及 StateFlow 的 `distinctUntilChanged` 比较）。

```kotlin
// 删除 WarehouseItemPool 类，调用方直接构造
// 改造前: val item = warehouseItemPool.acquire(itemId, itemName, itemType, rarity, quantity)
// 改造后: val item = WarehouseItem(itemId, itemName, itemType, rarity, quantity)
```

如果未来确实需要池化（高频创建/销毁场景），应使用组合模式——池内部使用 mutable holder，对外仍暴露 immutable data class：

```kotlin
private class MutableWarehouseItemHolder(
    var itemId: String, var itemName: String, var itemType: String,
    var rarity: Int, var quantity: Int
) {
    fun toImmutable() = WarehouseItem(itemId, itemName, itemType, rarity, quantity)
    fun reset(itemId: String, itemName: String, itemType: String, rarity: Int, quantity: Int) {
        this.itemId = itemId; this.itemName = itemName; this.itemType = itemType
        this.rarity = rarity; this.quantity = quantity
    }
}
```

---

#### P5: StateFlow SharingStarted.Eagerly → WhileSubscribed

```kotlin
// GameStateStore.kt — 改造后
val gameData = _state.map { it.gameData }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), null)

val disciples = _state.map { it.disciples }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 30_000), emptyList())
```

`stopTimeoutMillis = 5000` 保证：当最后一个订阅者消失后，Flow 保持活跃 5 秒，避免配置变更（如旋转屏幕）导致的短暂断开重连。`replayExpirationMillis = 30000` 保证：缓存值在 30 秒后过期，避免长时间无订阅者时内存浪费。

---

#### P6: shiftIndicesAfter 原地更新

```kotlin
// OptimizedWarehouseManager.kt — 改造后
private fun shiftIndicesAfter(removedIndex: Int) {
    val iterator = itemIndex.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.value > removedIndex) {
            entry.setValue(entry.value - 1)  // 原地修改，不创建新 Map
        }
    }
}
```

**注意**: `ConcurrentHashMap` 的迭代器是弱一致性的——迭代过程中其他线程的修改可能可见也可能不可见。如果另一个线程在迭代期间向 `itemIndex` 插入新条目，该条目可能被遗漏（索引未减1）。当前代码（创建新 Map 再替换）虽然分配开销大，但保证了强一致性。如果强一致性是硬性要求，应在此方法外加 `synchronized` 保护。

---

#### S1: StorageFacade.delete() 返回结果

```kotlin
suspend fun delete(slot: Int): StorageResult<Unit> {
    return try {
        val result = engine.delete(slot)
        if (result.isSuccess) {
            deleteCount.incrementAndGet()
            StorageResult.success(Unit)
        } else {
            StorageResult.failure(StorageError.DELETE_FAILED, "Delete failed for slot $slot")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e  // 必须重新抛出，与 S5 保持一致
    } catch (e: Exception) {
        StorageResult.failure(StorageError.DELETE_FAILED, e.message ?: "Unknown error")
    }
}
```

---

#### S2: isSaveCorrupted() 异常处理修正

```kotlin
fun isSaveCorrupted(slot: Int): Boolean {
    return try {
        runBlocking(Dispatchers.IO) {
            val result = engine.validateIntegrity(slot)
            result.isFailure
        }
    } catch (e: Exception) {
        Log.e(TAG, "isSaveCorrupted check failed for slot $slot", e)
        false  // 异常时默认"未损坏"，避免误触发恢复流程
    }
}
```

**关键改动**: `true` → `false`。校验过程异常不等于数据损坏，不应触发备份恢复。

---

#### S3: ProductionTransactionManager 消除 getOrThrow 反模式

```kotlin
// 改造前
val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
    SlotStateMachine.startProduction(currentSlot, ...).getOrThrow()  // Result -> Exception
}

// 改造后
val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
    SlotStateMachine.startProduction(currentSlot, ...)
        .getOrElse { return@updateSlotAtomic ProductionTransactionResult(success = false, error = it.message) }
}
```

---

#### S5: CancellationException 正确传播

```kotlin
// GameResult.kt — 改造后

// handleException 在协程上下文中使用时，必须传播 CancellationException
fun handleException(e: Throwable): String {
    if (e is kotlinx.coroutines.CancellationException) throw e  // 必须重新抛出
    return when (e) {
        is java.net.UnknownHostException -> "网络连接失败，请检查网络设置"
        is java.net.SocketTimeoutException -> "网络请求超时，请稍后重试"
        is java.io.IOException -> "网络错误，请检查网络连接"
        else -> "未知错误：${e.message}"
    }
}

// 同步版本：CancellationException 包装为 GameResult.Failure（同步调用方不期望异常）
inline fun <T> safeCall(block: () -> T): GameResult<T> {
    return try {
        GameResult.Success(block())
    } catch (e: Exception) {
        GameResult.Failure(GameError.fromException(e))
    }
}

// 协程版本：CancellationException 必须传播（破坏协程取消机制会导致 withTimeout 等失效）
suspend inline fun <T> safeCallSuspend(block: suspend () -> T): GameResult<T> {
    return try {
        GameResult.Success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        GameResult.Failure(GameError.fromException(e))
    }
}
```

**注意**: `safeCall`（同步版本）保持原有行为，将所有异常包装为 `GameResult.Failure`，因为同步调用方不期望收到异常。`safeCallSuspend`（协程版本）必须重新抛出 `CancellationException`，否则会破坏结构化并发。此行为变更需审计所有 `safeCallSuspend` 的调用方，确保它们在协程上下文中使用。

---

#### S6: ChangeTracker.computeChecksum 修复

**当前问题**: `computeChecksum()` 使用 `toString()` + SHA-256，`toString()` 输出非确定性（依赖 Kotlin data class 实现，不同版本可能变化）。降级路径使用 `hashCode()` + `System.currentTimeMillis()`，同一对象两次计算结果不同，完全丧失幂等性。

**序列化格式选择**: 项目存在三套序列化路径：
- 存档存储：kotlinx.serialization **ProtoBuf** + LZ4（SerializationModule）
- Room 数据库字段：kotlinx.serialization **ProtoBuf** + Base64（ProtobufConverters）
- 完整性校验/签名：kotlinx.serialization **JSON**（CryptoModule/IntegrityValidator 已有实现）

推荐使用 **ProtoBuf**（与存档存储格式一致），而非 JSON。理由：
1. 与项目主序列化路径一致，减少序列化格式种类
2. ProtoBuf 编码是确定性的（同一对象同一 schema 编码结果相同）
3. 二进制编码比 JSON 更紧凑，checksum 计算更快
4. 所有 `@Serializable` 类已标注 `@ProtoNumber`，无需额外配置

**方案A（推荐）: 使用 ProtoBuf 序列化**:

```kotlin
// ChangeTracker.kt — 改造后
private val protoBuf = NullSafeProtoBuf.protoBuf  // 复用项目已有的 ProtoBuf 实例

private fun computeChecksum(data: Any): String {
    return try {
        val bytes = when (data) {
            is String -> data.toByteArray(Charsets.UTF_8)
            else -> {
                @Suppress("UNCHECKED_CAST")
                val serializer = data::class.serializer() as KSerializer<Any>
                protoBuf.encodeToByteArray(serializer, data)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "ProtoBuf serialization failed for ${data::class.simpleName}, using reflective hash", e)
        computeReflectiveChecksum(data)
    }
}

private fun computeReflectiveChecksum(data: Any): String {
    val digest = MessageDigest.getInstance("SHA-256")
    data::class.memberProperties.sortedBy { it.name }.forEach { prop ->
        try {
            val value = prop.call(data) ?: return@forEach
            digest.update(value.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { /* 跳过不可访问属性 */ }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
```

**方案B: 使用 JSON 序列化**（与 CryptoModule/IntegrityValidator 保持一致）:

```kotlin
// 如果需要与 CryptoModule 的签名路径保持一致（JSON 规范化 + HMAC-SHA256）
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

private fun computeChecksum(data: Any): String {
    return try {
        val jsonString = when (data) {
            is String -> data
            else -> {
                @Suppress("UNCHECKED_CAST")
                val serializer = data::class.serializer() as KSerializer<Any>
                json.encodeToString(serializer, data)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(jsonString.toByteArray(Charsets.UTF_8))
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "JSON serialization failed for ${data::class.simpleName}, using reflective hash", e)
        computeReflectiveChecksum(data)
    }
}
```

**关键改动**:
1. 使用 `kotlinx-serialization`（ProtoBuf 或 JSON）替代 `toString()`，保证序列化确定性
2. 非序列化类型降级为反射字段遍历（而非 `hashCode()`），保证确定性且不崩溃
3. `hashCode()` + `System.currentTimeMillis()` 的降级路径被移除

---

### P2 次要问题修复方案

| # | 问题 | 修复方案 |
|---|------|----------|
| 1 | GameUtils 标准库包装 | 删除 `clamp`/`isEmpty`/`padLeft`/`padRight`，全局替换为 `coerceIn`/`isNullOrEmpty`/`padStart`/`padEnd` |
| 2 | BattleCalculator 重复计算 | 删除 `calculatePhysicalDamage`/`calculateMagicDamage`，调用方改用 `calculateDamage(isPhysicalAttack = true/false)` |
| 3 | AlchemyRecipe/ForgeRecipe 重复 | 提取 `MaterialChecker` 接口：`interface MaterialChecker { val materials: Map<String, Int>; fun hasEnoughMaterials(...); fun getMissingMaterials(...) }`，两个 Recipe 实现该接口 |
| 4 | 时间进度计算重复 | 提取 `fun calculateProgress(startTime: Long, duration: Long): Float` 到 `TimeProgressUtil.kt` |
| 5 | closeXxxDialog 委托 | 删除所有 `closeXxxDialog`，调用方改用 `closeCurrentDialog()` |
| 6 | showBuildingDetailDialog 重复 | 删除 `showBuildingDetailDialog`，保留 `openBuildingDetailDialog`，全局替换调用 |
| 7 | slotType 字符串 | 定义 `enum class ElderSlotType { VICE_SECT_MASTER, HERB_GARDEN, ALCHEMY, FORGE, OUTER_ELDER, PREACHING, LAW_ENFORCEMENT, INNER_ELDER, CLOUD_PREACHING }`，`assignElder`/`removeElder` 参数改为枚举。注意：`QINGYUN_PREACHING` 改为 `CLOUD_PREACHING`，统一英文意译风格 |
| 8 | Pill/PillEffect 字段重复 | `Pill` 使用 `@Embedded val effects: PillEffect` 替代平铺字段，删除 `Pill.effect` getter。**需提供 Room Migration**：字段名从 `effectXxx` 变为 `effects_effectXxx`，属于破坏性 schema 变更 |
| 9 | getSaveSlots/getSaveSlotsFresh 重复 | 删除 `getSaveSlotsFresh`，调用方改用 `getSaveSlots` |
| 10 | core.data 与 data 包名冲突 | 重命名 `core.data` 为 `core.registry` |
| 11 | formatMemory 重复 | 提取到 `MemoryFormatUtil.formatMemory()`，三处调用统一引用 |
| 12 | generateKey 重复 | 提取到 `StorageKeyUtil.generateKey()`，四处调用统一引用 |
| 13 | 三个性能监控类重叠 | 保留 `UnifiedPerformanceMonitor`，删除 `GamePerformanceMonitor` 和 `PerformanceMonitor`，迁移功能到统一类 |
| 14 | PerformanceModule 空 | 删除文件 |
| 15 | LazySlotCache/SlotQueryCache 重复 | 合并为 `SlotCache`，统一 API |

---

### 系统性问题改进策略

#### 1. 过度工程化治理

**原则**: 未实现的功能不声明接口，已声明的接口必须实现。

**检查清单**:
- [ ] `CacheKey.ttl` → 启用或删除字段
- [ ] `IntegrityReport.merkleValid` → 实现 Merkle 验证或删除字段
- [ ] `CacheLayer` 分级缓存 → 实现 LRU 或删除 hot/warm/cold 概念
- [ ] `WarehouseItemPool` → 真正池化或删除池化层
- [ ] 所有 `TODO`/`FIXME` 标记 → 逐一处理

#### 2. 线程安全一致性

**统一规范**:

| 场景 | 推荐方案 |
|------|----------|
| 高并发读、低并发写 | `ConcurrentHashMap` |
| 高并发读写 | `ConcurrentHashMap` + 细粒度锁 |
| 迭代时修改 | `CopyOnWriteArrayList` |
| 协程间互斥 | `Mutex`（不与 `synchronized` 混用，不与 `ReentrantLock` 混用于同一资源） |
| 同步+异步混合 | 分离锁：协程用 `Mutex`，同步用 `ReentrantLock`，同一资源禁止混用 |
| 对象池 | `ConcurrentLinkedQueue` + `AtomicInteger` |

#### 3. 协程生命周期管理

**统一方案**: 创建 `ApplicationScopeProvider`，所有需要 `CoroutineScope` 的组件通过 DI 注入：

```kotlin
@Singleton
class ApplicationScopeProvider @Inject constructor(
    @ApplicationContext private val context: Application
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// 使用方
class GCOptimizer @Inject constructor(
    private val scopeProvider: ApplicationScopeProvider
) {
    private val scope = scopeProvider.scope  // 统一 scope
}
```

#### 4. 错误处理统一

**目标**: 将 8 套错误类型收敛为 3 套：

| 层级 | 错误类型 | 用途 |
|------|----------|------|
| 基础层 | `AppError`（密封类） | 全局错误基类，包含 Network/Storage/Auth/Unknown |
| 领域层 | `DomainError`（密封类） | 业务逻辑错误，继承自 `AppError` |
| 展示层 | `UiError`（data class） | UI 展示用，包含用户友好消息和错误码 |

```kotlin
sealed class AppError {
    abstract val code: String
    abstract val message: String

    sealed class Storage : AppError() {
        data class SlotNotFound(override val code: String = "STORAGE_001", override val message: String) : Storage()
        data class IntegrityCheckFailed(override val code: String = "STORAGE_002", override val message: String) : Storage()
        data class WriteFailed(override val code: String = "STORAGE_003", override val message: String) : Storage()
    }

    sealed class Network : AppError() {
        data class Timeout(override val code: String = "NET_001", override val message: String) : Network()
        data class NoConnection(override val code: String = "NET_002", override val message: String) : Network()
    }
}
```

---

### 修复优先级路线图

```
第1周 ─── P0 快速修复（小工作量）
  ├── P0-04: GCOptimizer 协程泄漏
  ├── P0-05: ObjectPool 线程安全
  ├── P0-06: AtomicStateFlowUpdates 混锁
  ├── P0-07: SecureKeyManager Base64
  └── A4: Hilt 版本统一

第2周 ─── P0 核心修复（中工作量）
  ├── P0-01: 双存档路径统一
  └── P0-02: 完整性校验修复

第3-4周 ── P0 大改造
  └── P0-03: MainGameScreen 拆分

第5-6周 ── P1 修复
  ├── C3: BaseViewModel 提取
  ├── S1-S6: 安全层面修复
  ├── P1-P6: 性能层面修复
  └── A5/C5: runBlocking 消除

第7-8周 ── P1 架构优化
  ├── A1: GameRepository 拆分
  ├── A3: StorageEngine 拆分
  ├── C1/C2: ViewModel 重复代码消除
  └── P7: CoroutineScope 统一

第9-12周 ── P2 清理
  └── P2 次要问题逐项修复

长期 ────── P3 架构演进
  ├── Disciple 双模型迁移
  ├── GameData 多 Entity 拆分
  └── 错误类型体系统一
```

---

## 附录：问题明细表

### 按文件汇总

| 文件 | 严重 | 主要 | 次要 |
|------|:----:|:----:|:----:|
| MainGameScreen.kt | 1 | - | - |
| GameRepository.kt | 1 | 1 | - |
| StorageEngine.kt | 1 | 3 | - |
| StorageFacade.kt | - | 2 | 1 |
| CryptoModule.kt | 1 | - | - |
| SecureKeyManager.kt | 1 | - | - |
| GCOptimizer.kt | 1 | - | - |
| ObjectPool.kt | 1 | - | - |
| AtomicStateFlowUpdates.kt | 1 | - | - |
| ProductionViewModel.kt | - | 2 | - |
| SectViewModel.kt | - | 2 | 1 |
| GameViewModel.kt | - | 1 | 2 |
| BattleViewModel.kt | - | 1 | - |
| SaveLoadViewModel.kt | - | 1 | - |
| GameStateStore.kt | - | 1 | - |
| CacheLayer.kt | - | 1 | - |
| WarehouseItemPool.kt | - | 1 | - |
| OptimizedWarehouseManager.kt | - | 1 | - |
| ChangeTracker.kt | - | 1 | - |
| GameResult.kt | - | 1 | - |
| ProductionTransactionManager.kt | - | 1 | - |
| GameLoopError.kt | - | 1 | - |
| GameUtils.kt | - | - | 1 |
| BattleCalculator.kt | - | - | 1 |
| AlchemySystem.kt | - | - | 1 |
| Items.kt | - | - | 1 |
| PerformanceModule.kt | - | - | 1 |
| LazySlotCache / SlotQueryCache | - | - | 1 |
| build.gradle | - | 1 | - |
| AppModule.kt | - | 1 | - |
| Disciple.kt / DiscipleAggregate.kt | - | 1 | - |
| 其他 | - | - | 6 |

### 按维度汇总

| 维度 | 严重 | 主要 | 次要 | 合计 |
|------|:----:|:----:|:----:|:----:|
| 架构设计 | 1 | 5 | 2 | 8 |
| 代码规范 | 1 | 5 | 6 | 12 |
| 并发安全 | 2 | 0 | 1 | 3 |
| 性能优化 | 0 | 7 | 3 | 10 |
| 安全性 | 1 | 6 | 2 | 9 |
| 错误处理 | 1 | 1 | 1 | 3 |
| **合计** | **7** | **18** | **15** | **40** |

---

*报告生成时间: 2026-04-26*  
*审查工具: 静态代码分析 + 多维度专家审查*  
*建议复查周期: 每完成一轮 P0/P1 修复后进行一次复查*
