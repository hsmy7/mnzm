# 项目优化改进总结

## 一、需求背景

项目存在以下技术债影响可维护性与性能：

- 死代码与历史遗留文件堆积
- `@Deprecated` 阻塞 API 未清理
- 构建配置含未启用功能注释代码与 `StackableItem.class` 重复类 hack
- `core:domain` 模块对 `room.common` 存在架构争议依赖
- 关键模块（GameViewModel/CultivationCore/SettlementCoordinator）public API 缺少 KDoc
- `CLAUDE.md` 文档与代码实际值偏差
- 网格布局代码重复、长函数、超长参数列表
- 核心业务模块零测试覆盖
- detekt 基线豁免堆积
- 高频路径存在冗余调用

目标：在保持行为不变的前提下，从模块化、边界清晰、注释、死代码清理、简洁度、测试覆盖、性能七个维度系统优化，达到"无后续优化"的最终状态。

## 二、设计思路

### 核心原则
1. **行为不变优先**：所有重构必须保持现有游戏逻辑行为，通过测试验证
2. **最小改动**：只做直接必要的改动，不过度工程化
3. **决策文档化**：对无法彻底解决的架构争议（如 room.common），用守护测试 + 决策注释关闭问题
4. **分阶段验证**：每阶段完成后编译 + 测试验证，避免错误累积

### 架构决策

**Task 6（room.common 依赖）采用方案 B**：
- 调研发现 domain 层 22 个实体类同时承担领域模型与持久化实体双重职责，data 层 `@Database` 直接引用 32 个 domain 类作为表实体
- 方案 A（引入 DTO 层）需新建 32 个 DTO 类 + 32 套双向映射 + 修改 400+ Dao 方法签名，Disciple 的 6 个 @Embedded 映射极易出错，新增样板代码本身成为新的维护负担
- 方案 B（接受现状 + 文档化 + 守护测试）：零回归风险，通过两个守护测试确保注解使用范围不扩大

**Task 15（openXxxDialog 转发方法群）保持现状**：
- 24 个转发方法为门面模式，统一 API 入口
- GameViewModel 对 4 个 delegate 采用一致的转发模式，单独简化 navigation 会破坏一致性
- 迁移成本（9 文件 25 处调用）远高于收益（删 ~48 行 trivial 代码）

## 三、具体改动

### 阶段一：死代码与遗留清理

| 改动 | 位置 | 说明 |
|------|------|------|
| 删除历史文档 | `.trae/documents/heavenly_trial_map_plan.md` | 全仓库无引用 |
| 移除 10 处 @Deprecated 阻塞 API | [StorageFacade.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/data/src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt) | `saveSync`/`loadSync`/`getSaveSlots` 等，全部无调用方，文件从 730 行缩减至 549 行 |
| 移除 3 处 @Deprecated 方法 | GameEngineCore.kt、GameMonitorManager.kt、CryptoModule.kt | `pollWithShortDelay`、`getPerformanceMonitor()`、`validateIntegrity(slot)` 无调用方 |
| 保留 @Deprecated 方法 | BuildingService.kt、DiplomacyService.kt、CryptoModule.kt | 迁移涉及公共 API 返回类型变更或类型重写，保留理由明确 |
| 构建配置决策注释 | [build.gradle](file:///c:/Mnzm/XianxiaSectNative/android/app/build.gradle)、[settings.gradle](file:///c:/Mnzm/XianxiaSectNative/android/settings.gradle) | 华为 AGC/Firebase/Remote Build Cache 保留并添加决策说明 |
| 根治 StackableItem 重复类 | [StateFlowListUtils.kt](file:///c:/Mnzm/XianxiaSectNative/android/app/src/main/java/com/xianxia/sect/core/util/StateFlowListUtils.kt)、[build.gradle](file:///c:/Mnzm/XianxiaSectNative/android/app/build.gradle) | 删除 `:app` 模块重复定义，移除 `doFirst` hack |

### 阶段二：模块边界纯化

| 改动 | 位置 | 说明 |
|------|------|------|
| 架构决策注释 | [core/domain/build.gradle](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/build.gradle) | 说明 room.common 保留理由与约束 |
| Room 运行时依赖守护测试 | [DomainDependencyTest.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/domain/src/test/java/com/xianxia/sect/core/architecture/DomainDependencyTest.kt) | 禁止引入 `RoomDatabase`/`RoomEngine`/`migration`/`util` |
| @Entity 白名单守护测试 | 同上 | 22 个文件登记，新增需显式登记 |

### 阶段三：注释补全与文档同步

| 改动 | 位置 | 说明 |
|------|------|------|
| GameViewModel KDoc 补全 | [GameViewModel.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/main/java/com/xianxia/sect/ui/game/GameViewModel.kt) | 90+ 个 public 方法，含 `openXxxDialog` 转发方法群 |
| CultivationCore KDoc 补全 | [CultivationCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt) | 10 个方法，含魔法数字 `1.40/1.20/1.10` 含义说明（建筑品质/专属度系数） |
| SettlementCoordinator KDoc 补全 | [SettlementCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt) | 类级 + 关键方法，含项目硬约束说明 |
| CLAUDE.md 偏差修正 | [CLAUDE.md](file:///c:/Mnzm/XianxiaSectNative/CLAUDE.md) | 4 处 200ms → 100ms（与 `TICK_INTERVAL_MS = 100L` 一致） |

### 阶段四：代码简洁度

| 改动 | 位置 | 说明 |
|------|------|------|
| 抽取 GridRow 泛型 Composable | [GridRow.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/ui/src/main/java/com/xianxia/sect/ui/components/GridRow.kt) | 消除 MaterialSection/PillSection/WarehouseBulkSellDialog 的网格布局重复 |
| 拆分长函数 | [SettlementCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt) | `processFocusedDiscipleImmediate` 从 70 行拆为协调器 + 5 个私有方法 |
| 参数对象重构 | [CultivationCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationCore.kt) | `processDiscipleTick` 19 参数 → `DiscipleTickParams`（6 个分组上下文对象：`TickTimeContext`/`TickEquipContext`/`TickManualContext`/`TickSharedContext`），满足编码规范 §3.4 构造参数 ≤7 |
| 上下文对象复用 | [CultivationEventProcessor.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/service/CultivationEventProcessor.kt) | 循环常量上下文对象在循环外构造一次，所有弟子共享引用，避免每迭代分配 5 个对象 |
| 转发方法群评估 | GameViewModel.kt | 24 个 `openXxxDialog` 保持现状（门面模式一致性） |

### 阶段五：测试覆盖

| 改动 | 位置 | 说明 |
|------|------|------|
| CultivationCoreTest | [CultivationCoreTest.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/test/java/com/xianxia/sect/core/engine/service/CultivationCoreTest.kt) | 48 个测试（修炼计算/HP/MP 恢复/突破条件/建筑加成/寿命增益） |
| SettlementCoordinatorTest | [SettlementCoordinatorTest.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/test/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinatorTest.kt) | 54 个测试（月度结算/焦点弟子/HFD 重置/Cache 重建/突破条件） |
| GameViewModelTest | [GameViewModelTest.kt](file:///c:/Mnzm/XianxiaSectNative/android/feature/game/src/test/java/com/xianxia/sect/ui/game/GameViewModelTest.kt) | 30 个测试（建筑放置/弟子选择/对话框导航） |
| 测试基础设施 | core/engine/build.gradle、feature/game/build.gradle、libs.versions.toml | 新增 Robolectric、MockK 依赖 |

测试覆盖项目硬约束：
- 突破条件为 `cultivation >= maxCultivation && full health/mana`，不依赖 BREAKTHROUGH flag
- HFD 必须在每次月度结算后重置
- SettlementCache 必须每月从零重建

### 阶段六：性能优化

| 改动 | 位置 | 说明 |
|------|------|------|
| HFD 快照缓存 | [SettlementCoordinator.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt) | 消除 4 次冗余 `getHighFrequencyData().value` 访问 |
| SettlementCache 单次组装 | [SettlementCache.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCache.kt) | 原 5 次全量 `assemble()` 合并为 1 次共享，消除约 80% 的 Cache 构造开销 |
| 移除死代码 | [GameEngineCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/GameEngineCore.kt) | `getActiveDomains()` 调用结果从未读取 |
| 热状态缓存化 | [ThermalMonitor.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/perf/ThermalMonitor.kt) | `shouldEmergencySave`/`shouldReduceWorkload`/`isLightThrottle` 改用缓存 StateFlow，消除每 tick 5 次 binder IPC |
| updateTargetDuration 去重 | 同上 | 仅在目标值变化时调用 |
| 看门狗降级模式 | [GameEngineCore.kt](file:///c:/Mnzm/XianxiaSectNative/android/core/engine/src/main/java/com/xianxia/sect/core/GameEngineCore.kt) | 连续 10 次恢复失败后进入降级模式（≥30s 间隔）+ 日志节流（每 10 次记录），防止 OEM 永久挂起时频繁重启 |

**保留现状（附发现）**：`recoverHpMpForAllDisciples` 存在双重 HP/MP 恢复问题（DISCIPLES 域活跃时 `processDiscipleTick` 与 `recoverHpMpForAllDisciples` 各恢复一次），属正确性问题而非缓存问题，跨方法缓存不安全，未修改。

**指纹缓存移除说明**：`CultivationRateFingerprint` 仅检测结构变化（布局/政策/长老/弟子ID），不检测弟子属性变化（修炼值增长改变修炼速率计算），导致缓存返回过期数据。移除是 bug 修复。月度 Cache 重建成本（~100弟子 × 1次assemble）在月度批次中 <<1ms，不影响帧率。详见 `SettlementCache.kt` 注释。

**突破检查修复说明**：`DiscipleDirtyFlag` 枚举从未包含 `BREAKTHROUGH` 值，旧 dirty batch 中的 `BREAKTHROUGH in dirtyFlags` 条件永远为 false，导致非焦点弟子突破从未触发。修复后统一使用 `cultivation >= maxCultivation && fullHpMp` 直接判断，与 clean batch 一致。检查本身为 O(1) 两次比较，性能影响为零。

### 阶段七：detekt 基线清理

| 改动 | 位置 | 说明 |
|------|------|------|
| 移除 1 条 LongParameterList | [detekt-baseline.xml](file:///c:/Mnzm/XianxiaSectNative/android/app/detekt-baseline.xml) | `CultivationCore.processDiscipleTick` 已重构为参数对象 |
| 移除 4 条 UnusedPrivateProperty | 同上 | `consecutiveOverruns`/`lastTickTime`/`isIdle`/`IDLE_TICK_INTERVAL_MS` 已删除 |

## 四、验证结果

| 验证项 | 结果 |
|--------|------|
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| `:core:engine:testDebugUnitTest` | 806 个测试通过（含 CultivationCoreTest 48 + SettlementCoordinatorTest 54） |
| `:feature:game:testDebugUnitTest` | 30 个测试通过（GameViewModelTest） |
| `:core:domain:testDebugUnitTest` | 5 个架构守护测试通过（DomainDependencyTest） |
| `:app:detekt` | 无新增违规（预先存在的未基线化违规不阻塞） |

## 五、未处理项与已知限制

| 项目 | 原因 |
|------|------|
| `recoverHpMpForAllDisciples` 双重恢复 | 正确性问题，非缓存优化范畴，需独立决策 |
| BuildingService 3 处 @Deprecated | 迁移涉及公共 API 返回类型变更（`BuildingSlot` → `ProductionSlot`），级联影响大 |
| DiplomacyService `buyFromSectTrade` | 推荐替代为 suspend，接口迁移涉及 4+ 文件 |
| CryptoModule `VerificationResult` | 被 8+ 处广泛使用，类型重写成本高 |
| detekt 预先存在的未基线化违规 | RequestSigner/PlaceBuildingUseCase/BuildingSpatialIndexTest，本次优化未涉及 |
