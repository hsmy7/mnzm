# 项目知识库

> 本文档包含了项目的技术栈、关键类说明、核心子系统的设计参考。
> 架构设计详见 [architecture.md](architecture.md)，编码规范详见 [CLAUDE.md](../CLAUDE.md)。

---

## 目录

- [技术栈](#tech-stack)
- [关键类说明](#key-classes)
- [弟子分配门卫系统](#弟子分配门卫系统)
- [存档槽位隔离](#存档槽位隔离)
- [探索系统](#探索系统)
- [确定性 RNG 系统](#确定性-rng-系统)
- [弟子属性生成](#弟子属性生成)
- [偷盗系统年上限](#偷盗系统年上限-2026-07-24)
- [Component Table 架构](#component-table-architecture)
- [修炼 Checkpoint 模式](#修炼-checkpoint)
- [EntityStore 增量更新](#entitystore-增量更新)
- [生产系统 Checkpoint](#生产系统-checkpoint)
- [SaveValidator 规则引擎](#savevalidator-规则引擎)
- [热控与温度读取](#热控与温度读取)
- [Hot Path 规则](#hot-path-rules)
- [Mail & Reward System](#mail--reward-system)
- [Navigation Pattern](#navigation-pattern)
- [Android SDK / Encoding](#android-sdk--encoding)

---

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

---

## Key Classes

- **`GameEngineCore`** — 游戏循环控制器（惰性结算引擎），仅推进时间 + 每旬 5 项最小检查 + 月变/年变事件
- **`GameEngine`** — 业务逻辑 Facade，注入到 ViewModel，写入 GameStateStore
- **`GameStateStore`** — 单一 MutableStateFlow<UnifiedGameState>，各字段通过 .map{} 派生。写操作由 `ReentrantLock` 串行化（非 `Mutex`，挂起时不释放锁），`_discipleTables` 进入 `deepCopy()` 提供快照隔离。生命周期状态采用 **BootPhase/RunState 双层设计**（详见 [architecture.md](architecture.md#lifecycle-architecture-bootphase--runstate-双层状态机)）。新增 `resetForSlot(slotId)` 方法，在创建新游戏/重启时同步 `GameStateRepository` 的 `currentSlotId` 和 `dirty` 集
- **`BootSequenceController`** — 启动序列控制器：统一编排新游戏/读档/重启的 BootPhase 推进、RunState 切换、资源预加载(回调)、游戏循环启停、地图生成、错误恢复。`boot()` 为统一入口
- **`GameViewModel`** — 主 ViewModel (Hilt)，通过 9 个 Delegate 拆分领域逻辑
- **`MainGameScreen`** — Tab 布局 (OVERVIEW/DISCIPLES/BUILDINGS/WAREHOUSE/SETTINGS)，无 NavHost
- **`GameData`** — Room @Entity，主键 (id, slot_id)
- **`CultivationService`** — 修炼 Checkpoint 快照法入口：`checkpointDisciple()` / `accumulateCultivationPerPhase()` / `checkpointAllProduction()`
- **`DiscipleAssignmentGate`** — 弟子分配门卫（v4.0.58），统一管理 11 个槽位系统的分配/释放/查询/读档重建

---

## 弟子分配门卫系统

v4.0.58 引入 `DiscipleAssignmentGate` + `DiscipleAssignmentRegistry` 集中管理所有槽位分配：

| 组件 | 文件 | 职责 |
|------|------|------|
| `DiscipleAssignmentGate` | `domain/disciple/DiscipleAssignmentGate.kt` | 门卫 Facade：`confirmAssign` / `release` / `rebuildFromGameData` / `filterAvailableDisciples` |
| `DiscipleAssignmentRegistry` | `domain/disciple/DiscipleAssignmentRegistry.kt` | Identity Map：`discipleId → SlotAssignment` |
| `DiscipleSlotCleanup` | `domain/disciple/DiscipleSlotCleanup.kt` | 死亡/释放时清理所有槽位，自动调用 `gate.release()` |
| `SlotCategory` | `model/SlotAssignment.kt` | 11 种槽位类别枚举 |
| `SlotAssignment` | `model/SlotAssignment.kt` | 分配记录数据模型 |
| `SlotCategoryCoverageTest` | `.../disciple/SlotCategoryCoverageTest.kt` | **守卫测试**：新增 `SlotCategory` 值时自动失败 |

**分配流程：** `releaseDiscipleFromAllSlotsAtomic(discipleId)` → `stateStore.update{}` → `gate.confirmAssign(discipleId, slotRef)`

> **编码注意事项：** 新增 `SlotCategory` 枚举值后需更新 4 处（`SlotCategoryCoverageTest` 会失败并列出具体指引）：`scanAndRegister` + `DiscipleSlotCleanup.clearAllSlots` + 分配入口 `releaseDiscipleFromAllSlotsAtomic` + `confirmAssign`。

---

## 存档槽位隔离

所有存档共享单 SQLite DB，通过 `slot_id` 列 + 复合主键 `(id, slot_id)` 隔离：

- **所有实体必须使用 `primaryKeys = ["id", "slot_id"]`** — 例外会导致跨槽位 REPLACE 覆盖（StorageBag 在 v4.0.60 修复前就是唯一例外）
- **`GameStateRepository`** — 维护 `currentSlotId`（@Volatile），跟踪当前操作的槽位。`flushDirtyState()` 写入时用此值
- **`stateStore.resetForSlot(slotId)`** — 清空内存状态 + 清除仓库脏标记 + 设置当前槽位。`createNewGame` 和 `restartGameInternal` 中调用
- **`writeAllDataToDatabase` 统一强制 slotId** — 所有实体写入 DB 前必须 `.copy(slotId = slot)`，确保内存中的默认值 0 不会写入错误的槽位

---

## 探索系统

探索系统从 `ExplorationService`（Facade）拆分为 6 个独立职责的子系统：

| 子系统 | 文件 | 职责 |
|--------|------|------|
| `WorldLevelManager` | `exploration/WorldLevelManager.kt` | 关卡惰性管理：刷新/过期清理/妖兽移动（纯函数，分区 RNG） |
| `BeastAttackDetector` | `exploration/BeastAttackDetector.kt` | 妖兽攻击检测（纯函数，返回预警列表） |
| `PatrolBattleSystem` | `exploration/PatrolBattleSystem.kt` | 巡视塔战斗（拆 4 步：组队→索敌→战斗→结算） |
| `LootCalculator` | `exploration/LootCalculator.kt` | 掠夺计算（纯函数 + 副作用分离，修复双重扣除） |
| `DiscipleDeathHandler` | `exploration/DiscipleDeathHandler.kt` | 死亡标记 + deathYears + 装备断言守卫 |
| `ExplorationTeamManager` | `exploration/ExplorationTeamManager.kt` | 探索队伍管理（单事务内完成，竞态安全） |
| `ExplorationService` | `domain/exploration/ExplorationService.kt` | Facade：月度事件编排 + 保留玩家主动操作接口 |

---

## 确定性 RNG 系统

所有随机操作使用分区 PRNG 确保存档/读档后随机序列一致：

| 组件 | 文件 | 说明 |
|------|------|------|
| `DeterministicRng` | `util/DeterministicRng.kt` | PCG-XSH-RR 算法，16 字节状态，可序列化 |
| `GameRngManager` | `util/GameRngManager.kt` | 4 分区管理器（BATTLE / BREAKTHROUGH / EXPLORATION / SYSTEM） |
| `RngPartition` | `util/RngPartition.kt` | 分区枚举 |

**规则：** 新增任何使用随机数的逻辑，必须通过 `GameRngManager.getRng(RngPartition.xxx)` 调用，禁止直接使用 `kotlin.random.Random`。保存时 `exportStates()` 写入 `GameData.rngStates`，加载时 `restoreStates()` 恢复。

**扩展：`nextGaussian()`** — `DeterministicRng` 新增 Box-Muller 变换实现的 `nextGaussian(mean, stddev)`，消耗 2 次 `nextDouble()` 调用生成 1 个标准正态偏差。不缓存配对值以保持 `snapshot`/`restore` 确定性。用于弟子属性生成（见下文）。

---

## 弟子属性生成

弟子创建时属性通过两个入口生成：

### 入口 1：`DiscipleFactory.create()`
- 路径：`domain/disciple/DiscipleFactory.kt`
- 使用 `DiscipleSeed.nextInt` Lambda（兼容 `GameRngManager` / `GameRandom` / `kotlin.random.Random`）
- 用于玩家招募、招募列表刷新、子嗣出生（3 站点统一）

### 入口 2：`AISectDiscipleManager.generateRandomDisciple()`
- 路径：`domain/diplomacy/AISectDiscipleManager.kt`
- 使用 `DeterministicRng` 实例（PCG-XSH-RR，`System.nanoTime()` 种子）
- 用于 AI 宗门弟子生成

### 属性分布规则（2026-07-24 优化）

| 属性类别 | 属性 | 分布 | 参数 |
|---------|------|------|------|
| 悟性 | `comprehension` | 灵根驱动均匀 | 1根[80,100] / 2根[60,100] / 3根[40,100] / 4根[20,100] / 5根[1,100] |
| 技能(9) | intelligence, charm, loyalty, morality, artifactRefining, pillRefining, spiritPlanting, mining, teaching | **正态分布** | N(50.5, 16.5²)，截断[1,100] |
| 方差(7) | hpVariance ~ speedVariance | **正态分布** | N(0, 16.667²)，截断[-50,50] |

悟性保持不变（灵根数量决定范围），其余所有技能和方差属性使用 `gaussianInt()`（Box-Muller 变换生成的 int 值，越接近中间概率越高）。

---

## 偷盗系统年上限（2026-07-24）

宗门级偷盗控制：

- **配置**：`GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR = 3`
- **计数器**：`GameData.annualTheftCount`（Room 持久化，MIGRATION_29_30）
- **检查点**：`LawEnforcementProcessor.canDiscipleAttemptTheft()` + 月度扫荡 `processTheftMonthly()` / `processTheftIfNeeded()`
- **递增**：`executeSuccessfulTheft` 两版本（事务/非事务）偷盗成功后 +1
- **归零**：`CultivationEventProcessor` 年变重置块
- **移除**：原单弟子 12 月冷却检查（`THEFT_COOLDOWN_MONTHS` + `lastTheftMonths` 冷却判定）
- `UsageTracking.lastTheftMonth` 字段仍写入但不再用于冷却判断（仅保留兼容）

---

## Component Table Architecture

Disciple entities are stored in `DiscipleTables` — ~90 narrow `ComponentTable`/`IntComponentTable`/`DoubleComponentTable` columns. 底层使用 `IntPackedArray`（dense IntArray + idToIndex）和 `DoublePackedArray`（零装箱），查询 O(1)，删除 O(1) swap-on-remove。所有 CRUD 通过 `buildCopyableRefs()` 声明式列表驱动，新增列只需在列表加一行。

---

## 修炼 Checkpoint

`cultivationCheckpoints: DoubleComponentTable` + `cultivationCheckpointGameMonths: IntComponentTable`。修炼值 = checkpoint + rate × (currentMonth - cpMonth) × 3。Checkpoint 在每旬累积时更新，在速率变化时通过 `checkpointDisciple()` 同步。

**运行时投影：** `tables.cultivationCheckpoints[id]` + `tables.cultivationCheckpointGameMonths[id]` — 每旬 `accumulateCultivationPerPhase()` 更新，`getEffectiveCultivation()` 实时投影。

---

## EntityStore 增量更新

其他实体类型用 `EntityStore<T : HasId>`，MutableList 原地修改 + `freeze()` 快照 + `isDirty` 标记检测。GC 分配降低 80%+。

**注意事项：** `plus(item)` 必须通过 `EntityStore(newItems)` 构造新实例，不可 `EntityStore()` + `items_.addAll()`，否则 `frozenSnapshot` 未正确初始化。

### EntityStore 操作模式

| 操作 | 复杂度 | 说明 |
|------|--------|------|
| `entityStore.get(id)` | O(1) | HashMap index |
| `entityStore.update(id) { transform }` | O(n) indexOfFirst + O(1) HashMap | 零分配 |
| `entityStore.freeze()` | — | 仅在 dirty 时分配新 List，before StateFlow emission |
| `MutableGameState` 字段 | — | `discipleTables`, `equipmentStacks: EntityStore<EquipmentStack>`, `productionSlots: List<ProductionSlot>`, `spiritMineSlots: List<SpiritMineSlot>` |

---

## 生产系统 Checkpoint

`ProductionSlot.baseDuration` 存储配方基础持续时间。政策/长老变化时 `checkpointAllProduction()` 遍历所有活跃槽位，重算 duration 和 completionMonth，保留已完成进度比例。灵田/灵植使用 `calculateSpiritFieldMaturityBonus` 动态重算 effectiveGrowTime。

### 灵矿场结算

`spiritMineLastSettledMonth` 时间戳差分，`产出 = rate × (currentMonth - lastSettled)`。

### 炼丹/锻造 Checkpoint

`ProductionSlot.baseDuration` 存储配方基础值，`recalculateAllCompletionMonths()` 按当前政策/长老重算 duration + successRate。

### 政策/长老变更触发

`SectPolicyToggleUseCase` 和 `ElderManagementUseCase` 在变更后调用 `checkpointAllProduction()`。

---

## SaveValidator 规则引擎

存档完整性校验器（`data/integrity/SaveValidator.kt`）从 8 项硬编码检查重构为可扩展规则引擎（v4.0.67）。

### 规则接口

```kotlin
interface SaveValidationRule {
    val id: String                          // 唯一标识
    val order: Int                          // 执行顺序（小→大）
    fun execute(data: SaveData, context: RuleContext): RuleOutcome
}
```

### 注册表

`SaveValidationRuleRegistry` object（`CopyOnWriteArrayList` 存储，`order` 排序）。新规则只需在 `registerDefaults()` 加一行注册。

### 规则上下文

`RuleContext` 在遍历规则前一次性预计算：`allEquipmentIds`（equipmentStacks + instances 的 ID 并集）、`buildingInstanceIds`（placedBuildings 非空 instanceId）。中间状态 `removedDiscipleIds` 由 GhostDiscipleCleanupRule 写入、GhostRefCleanupRule 消费。

### 当前已注册规则（14 条）

| 规则 | 现有/新增 | 功能 |
|------|-----------|------|
| SectNameRule | 现有 | sectName 空→默认名 |
| GameDateRule | 现有 | year/month 范围 |
| DiscipleAgePositiveRule | **新增** | age >= 0 |
| GamePhaseRangeRule | **新增** | phase 范围 [0,2] |
| CultivationCapRule | 现有 | 修为上限截断 |
| EquipmentRefRule | 现有 | 装备引用存在性 |
| AgeLifespanRule | 现有 | 年龄 vs 寿命 |
| BuildingRefRule | 现有 | 建筑引用存在性 |
| DuplicateDiscipleIdRule | **新增** | 重复弟子 ID 去重 |
| GhostDiscipleCleanupRule | 现有 | 幽灵弟子清理 |
| GhostRefCleanupRule | 现有 | 幽灵引用清理 |
| SpiritStoneNonNegativeRule | **新增** | 灵石负值截断 |
| DiscipleRealmConsistencyRule | **新增** | realm/layer 合法性 |
| DiscipleDeadStatusRule | **新增** | 死亡弟子装备清理 |

### 规则文件位置

- 规则实现：`data/src/main/java/.../integrity/rules/*.kt`
- 规则测试：`data/src/test/java/.../integrity/rules/*Test.kt`
- 入口 facade：`data/src/main/java/.../integrity/SaveValidator.kt`
- IntegrityResult 密封接口保持兼容不变

### 已知待完成

见 [架构债务文档](architecture-debt.md) #21（对抗性审查 4 项未覆盖）、#22（补充修复文件）、#23（EntityCountBoundsRule）。

## 热控与温度读取

`ThermalReader` 接口定义三通道温度获取策略，`AndroidThermalReader` 实现（v4.0.41）：

1. `PowerManager.getThermalHeadroom(10)` (API 30+) — 主动预测
2. `PowerManager.currentThermalStatus` (API 29+) — 被动状态
3. sysfs + BatteryManager — 降级回退

`ThermalController` 消费 `ThermalReader` 温度数据驱动四档降级阶梯（GREEN/YELLOW/ORANGE/RED），联动渲染质量、目标帧率。

---

## Hot Path Rules

Component Table 模式下的关键性能规则：

- **Disciple updates**: Write directly to `tables.loyalty[id] = 90` — O(1) via IntPackedArray (was O(log n) SparseArray)
- **Disciple reads**: `tables.names[id]`, `tables.realms[id]` — O(1) via IntPackedArray (was O(log n))
- **Disciple assembly**: `tables.assemble(id)` creates a full `Disciple` data class (~200 fields, 5 nested layers) — ONLY for UI/Serialization, NEVER in hot path
- **Hot path column reads**: `cultivation` 热路径用列直读替代 `assemble()`。父母加成仅读 `isAlive` + `spiritRootTypes` 两列，讲道加成仅读 `isAlive` + `realms` + `teachings` 三列。300 弟子时 `assemble` 调用从 1500+ 次/100ms 降至 300 次/100ms（降低 80%）

---

## Mail & Reward System

Mail reward claims use Saga compensation: `stateStore.update {}` 原子写入物品+claim记录，若 `distributeAttachmentsInline` 抛出则 `mailRecords` 不写入，邮件保持未领取。

- **Stable IDs**: 内置邮件用 BuiltinMailConfig 确定性 ID，在线邮件用 `"online_${remoteMailId}"`
- **GameData 存储**: `mailRecords: List<MailClaimRecord>`（含 mailId/claimedAt/source），非邮件内容
- **初始化**: `mailService.resetAndInitSlot()` 在世界初始化后调用
- **清理**: `StorageEngine.delete()` 清理已删档位的 mails 表

---

## Navigation Pattern

No `NavHost` is used for the main game. `MainGameScreen` switches content via `MainTab` enum. Feature screens (Alchemy, Forge, HerbGarden, etc.) are dialogs opened via `DialogStateManager.openDialog(DialogType, params)`. The two actual Activity transitions are:

1. `MainActivity` → `GameActivity` (in-game)
2. `MainActivity` → `SaveSelectScreen` (save select)

---

## 引导系统（Guide System）

### 入口
- **按钮位置**：`GameActionButtons.kt` 右上角第一行，外交按钮左侧
- **DialogType**：`Guide`（全屏模式 `DialogMode.Full`）
- **路由**：`GameOverlayHost` → `GuideDialog`

### 数据模型
- **GuideTask**（`core/domain/model/guide/GuideTask.kt`）：任务定义，含名称、描述、条件列表、奖励
- **GuideCondition**（sealed interface）：12 种条件类型（BuildingCount、ElderAppointed、CumulativeCounter、PlantCropOnce 等）
- **GuideCounterKeys**（常量类）：所有计数器 Key 编译期安全，消除拼写错误
- **GameData 字段**：`guideClaimedRewardIds: Set<Int>` + `guideCounters: Map<String, Long>`

### 计数器接入点
计数器在对应子系统的事件完成处递增：`CultivationSettlement`（灵矿）、`ProductionProcessor`（炼药/锻造）、`DiscipleBreakthroughHandler`（突破）、`PatrolBattleSystem`（巡逻击败妖兽）、`LawEnforcementProcessor`（监禁）等 12 处。

### 奖励发放
- **入口**：`GameEngineGuideOps.claimGuideReward(taskId)`
- **读取任务定义的 `rewardItemQuantity`**，非硬编码
- **使用 GameRngManager**（SYSTEM 分区）替代 UUID.randomUUID()
- **条件检查和发放位于同一次 `stateStore.update`**，消除 TOCTOU 竞态

### 已知限制
- `DiscipleReachRealm` 条件类型标记 `@Deprecated(ERROR)`，需 DiscipleTables 参数才能正确实现
- 月度事件管线（`CultivationEventProcessor.processMonthlyEvents`）存在多事务提交问题，已记入架构文档待完成项

---

## Android SDK / Encoding

- `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`
- All Java/Kotlin compilation is forced to UTF-8 to prevent Chinese character corruption
- Uses Aliyun Maven mirrors for Gradle plugin and dependency resolution

---

## 仓库堆叠系统（Inventory Stacking）

### 存储模型

8 种独立实体存储在 `MutableGameState` 中：

| 类型 | 字段 | 存储 | 堆叠上限 | 合并键 |
|------|------|------|---------|--------|
| EquipmentStack | `equipmentStacks` | `EntityStore` | 999 | `name + rarity + slot.name` |
| ManualStack | `manualStacks` | `EntityStore` | 999 | `name + rarity + type.name` |
| Pill | `pills` | `EntityStore` | 999 | `name + rarity + category.name + grade.name` |
| Material | `materials` | `EntityStore` | 9999 | `name + rarity + category.name` |
| Herb | `herbs` | `EntityStore` | 9999 | `name + rarity + category` |
| Seed | `seeds` | `EntityStore` | 9999 | `name + rarity + growTime` |

### 统一合并入口

所有仓库物品的添加操作通过 `StackableItemStore<T>` 统一路由（`core/domain/.../state/StackableItemStore.kt`），替代原来分散在 `InventorySystem` 中的 6 组内联合并。

核心数据结构：
- `keyIndex: HashMap<StackKey, MutableList<String>>` — 支持同种物品多个堆叠
- 合并策略：遍历所有匹配堆叠，逐个填充剩余空间 → 所有堆叠填满后创建新堆叠 → 容量满时返回 `DomainResult.Partial`

### 溢出处理

2026-07-23 重构后：
- 溢出时优先创建新堆叠（不再静默丢失物品）
- `DomainResult.Partial` 仅在仓库真正满仓时返回
- 所有 20+ 调用方通过 `when(result)` 穷尽分支处理三种结果

### 整理功能

- `consolidateStacks()` — 合并分散堆叠，在 `sortWarehouse()` 和 `BootSequenceController` 读档时自动执行

### ⚠️ 待完成项目

#### 一、合并入口未统一

| # | 路径 | 文件 | 风险 |
|---|------|------|------|
| 4 | `sellItem` / `bulkSellItems` 直接 `EntityStore.get/remove/update` | `InventoryFacadeImpl.kt` | 绕过索引（`bulkSellItems` 已提取 `deductStack` 简化，但未改到 StackableItemStore） |

#### 二、事务完整性

（本次已全部修复：buyMerchantItem 灵石顺序、sortWarehouse 原子化、openStorageBag 单事务）

#### 三、防御性加固

| # | 问题 | 文件 | 当前状态 |
|---|------|------|---------|
| 11 | `StackableItemStore.replaceAll()` 不验证 items 是否超 maxStack | `StackableItemStore.kt` | 低概率触发 |
| 13 | `confiscateStorageBagItem` 中 `stackKeyOf` 匿名 lambda 与 `InventorySystem` 重复定义 | `InventoryFacadeImpl.kt` | 需复用 `::equipmentStackKey` |

#### 四、代码可维护性

| # | 问题 | 建议 |
|---|------|------|
| 15 | 两套堆叠逻辑并存（`StackableItemStore` / 直接 EntityStore） | 本次大幅缩减差距，`sellItem` 仍直接操作 EntityStore |
| 17 | `StackKey.parts` 使用 `List<Any>`（弱类型安全） | 未来用内联类加固（当前因泛型约束无法直接替换） |

#### 已完成（2026-07-24 架构债务批量处理）

| # | 问题 | 修复方式 |
|---|------|---------|
| 1 | StorageBagUtils 绕过统一入口 | `mergeEquipmentStackToWarehouse` 改为 `DiscipleEquipmentService` 直接使用 `StackableItemStore` |
| 2 | buyMerchantItem 非装备路径绕过容量守卫 | 改为 `StackableItemStore` |
| 3 | openStorageBag 直接 `EntityStore.add()` | 单事务 + `StackableItemStore` 统一入口 |
| 5 | AutoBuyService 直接 `EntityStore.add` | `addToWarehouse` 改为 `StackableItemStore` |
| 6 | DiscipleFacadeImpl equipItem/returnToWarehouse 绕过 | `unequipEquipmentLogic` 改为 `StackableItemStore` |
| 7 | confiscate 无事务回滚 | 先入库后移除弟子物品 |
| 8 | buyMerchantItem 先扣灵石后加物品 | 先加物品后扣灵石，Partial 时取消交易 |
| 9 | sortWarehouse 两次独立 update | consolidate+sort 合并为单事务 |
| 12 | merge=false + 仓满 Partial 语义歧义 | merge=false 时直接返回 Failure |
| 14 | canAddXxx 读 StateFlow | `canAddItemInTransaction(state)` 方法 |
| 16 | otherTypes 手动计算 | `otherSlotsCount(excludeType)` 辅助函数 |
| — | consolidate 合并不彻底（单次循环） | 改为 `while(changed)` 迭代合并 |
