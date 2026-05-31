# 垃圾代码审计报告

**项目**: XianxiaSectNative  
**审计日期**: 2026-06-01  
**审计范围**: `android/app/src/main/java/com/xianxia/sect/` 全部源码 + 项目根目录脚本  
**审计方法**: 基于 grep 全局引用搜索，逐类/对象/函数验证外部引用次数

---

## 一、审计摘要

| 类别 | 数量 | 风险等级 |
|------|------|----------|
| 确认死代码（零引用） | 8 个类/对象 | 低 — 可立即删除 |
| 整类废弃代码（@Deprecated） | 2 个类 | 中 — 需确认迁移完成 |
| 废弃方法/字段（@Deprecated） | 25+ 处 | 中 — 需逐步清理 |
| 功能重叠代码 | 3 对 | 中 — 需评估合并 |
| 遗留开发脚本 | 5 个文件 | 低 — 可立即删除 |
| 循环依赖 | 0 | — |

---

## 二、确认的死代码（零外部引用）

以下类/对象在整个 `main` 源码目录中**除自身定义外无任何引用**，属于完全未接入的代码，可安全删除。

### 2.1 引擎层死代码

| # | 文件路径 | 类/对象名 | 功能描述 |
|---|----------|-----------|----------|
| 1 | `core/engine/DiffUpdateSystem.kt` | `DiffUpdateSystem` | 差分更新系统，无任何调用方 |
| 2 | `core/engine/scheduler/GameEventScheduler.kt` | `GameEventScheduler` | 游戏事件调度器，无任何调用方 |
| 3 | `core/engine/transaction/UnitOfWork.kt` | `UnitOfWork` | 工作单元事务模式，无任何调用方 |
| 4 | `core/engine/storage/IndexedSlotStorage.kt` | `IndexedSlotStorage` | 索引槽位存储，无任何调用方 |
| 5 | `core/engine/operations/ProductionOperation.kt` | `ProductionOperation` + `ProductionOperations` | 生产操作类及工厂，无任何外部调用 |
| 6 | `core/engine/system/ServiceInterfaces.kt` | `ServiceInterfaces` | 服务接口定义，无任何引用 |

### 2.2 网络/仓库层死代码

| # | 文件路径 | 类/对象名 | 功能描述 |
|---|----------|-----------|----------|
| 7 | `network/NetworkUtils.kt` | `NetworkUtils` | 网络工具类，无任何调用方 |
| 8 | `core/warehouse/WarehouseModels.kt` | `WarehouseModels` | 仓库模型定义，无任何引用 |

### 2.3 删除影响评估

- 以上 8 个文件均无 DI Module 注册、无 import 引用、无测试覆盖
- 删除后无需修改任何其他文件
- 建议一次性清理

---

## 三、已废弃代码（@Deprecated 标记）

### 3.1 整类废弃

| # | 文件路径 | 废弃类 | 替代方案 | 引用情况 |
|---|----------|--------|----------|----------|
| 1 | `core/util/GameError.kt` | `GameError` | `AppError.Domain` | 仅被 `AppError.toAppError()` 和 `UiError.fromGameError()` 作为迁移桥接引用 |
| 2 | `core/model/production/ProductionError.kt` | `ProductionError` + `ProductionOperationResult` | `AppError.Domain.Production` | 被 `ProductionCoordinator` 和 `ProductionOperation` 内部使用 |

**清理条件**: 确认所有业务代码已迁移到 `AppError` 体系后，删除 `GameError` 及其桥接方法。

### 3.2 序列化兼容性废弃字段

以下字段因 Proto/数据库序列化兼容性保留，**不可立即删除**，需在旧版存档迁移完成后清理。

| 文件路径 | 废弃字段 | 保留原因 |
|----------|----------|----------|
| `core/model/GameData.kt` | `herbGardenYield` | 序列化兼容 |
| `core/model/GameData.kt` | `herbId` | 序列化兼容（用 `seedId` 推导） |
| `core/model/production/ProductionSlot.kt` | `herbGardenYield` | 数据库兼容 |
| `core/model/DiscipleAggregate.kt` | `hp` | 用 `maxHp` 替代 |
| `core/model/DiscipleAggregate.kt` | `mp` | 用 `maxMp` 替代 |
| `data/serialization/unified/SerializableSaveData.kt` | `herbGardenPlantSlots` | Proto 兼容（已迁移至 `productionSlots`） |
| `data/serialization/unified/SerializableSaveData.kt` | `forgeSlots` | Proto 兼容（已迁移至 `productionSlots`） |
| `data/serialization/unified/SerializableSaveData.kt` | `alchemySlots` | Proto 兼容（已迁移至 `productionSlots`） |
| `data/serialization/unified/SerializableSaveData.kt` | `effects`（旧 ProtoNumber 14） | Proto 兼容（v4 存档） |
| `data/serialization/unified/SerializableSaveData.kt` | `herbGardenYield` | Proto 序列化兼容 |
| `data/serialization/unified/SerializableSaveData.kt` | `herbId` | Proto 序列化兼容 |
| `data/serialization/unified/SerializableSaveData.kt` | 2 个 "No longer in business model" 字段 | Proto 兼容 |

### 3.3 API 废弃方法

| 文件路径 | 废弃方法 | 替代方案 | 数量 |
|----------|----------|----------|------|
| `data/facade/StorageFacade.kt` | 阻塞式 save/load/getSaveSlots 等方法 | 对应的 suspend 版本 | 9 个 |
| `core/util/GameMonitorManager.kt` | `getPerformanceMonitor()` | `getUnifiedPerformanceMonitor()` | 1 个 |
| `core/engine/service/BuildingService.kt` | 3 个 BuildingSlot 相关方法 | `ProductionSlot` 直接使用 | 3 个 |
| `data/crypto/CryptoModule.kt` | `validateIntegrity()` | `StorageEngine.validateIntegrity()` | 1 个 |
| `data/crypto/CryptoModule.kt` | `StorageError` | `AppError.Domain.Storage` | 1 个 |
| `core/util/AppError.kt` | 3 个工厂方法 | `AppError.Domain.*` | 3 个 |
| `ui/components/GameDialog.kt` | 2 个 Composable | 新版对话框组件 | 2 个 |
| `ui/game/TipDialog.kt` | 整个 Composable | 新版提示组件 | 1 个 |

---

## 四、功能重叠代码

以下代码对存在职责重叠，但**两者均有外部引用**，属于架构演进中的中间态，需评估是否合并。

### 4.1 SectWarehouseManager → OptimizedWarehouseManager（纯委托层）

| 属性 | SectWarehouseManager | OptimizedWarehouseManager |
|------|---------------------|--------------------------|
| 路径 | `core/engine/SectWarehouseManager.kt` | `core/warehouse/OptimizedWarehouseManager.kt` |
| 类型 | `object`（静态） | `object`（静态） |
| 引用数 | 2（CultivationService） | 24（被 SectWarehouseManager 委托调用） |
| 实际逻辑 | **无**，所有方法均一行委托给 OptimizedWarehouseManager | 包含完整仓库管理逻辑 |

**建议**: `SectWarehouseManager` 是纯委托层，调用方可直接引用 `OptimizedWarehouseManager`，删除中间层。

### 4.2 BuildingSubsystem ↔ BuildingService（功能重叠）

| 属性 | BuildingSubsystem | BuildingService |
|------|-------------------|-----------------|
| 路径 | `core/engine/system/BuildingSubsystem.kt` | `core/engine/service/BuildingService.kt` |
| 类型 | `@Inject class`（GameSystem） | `@Inject class`（GameSystem） |
| DI 注册 | CoreModule 中注入 | CoreModule 中注入 |
| 引用数 | 1（CoreModule） | 多处业务调用 |
| 实际逻辑 | 实现 GameSystem 接口 | 包含完整建筑业务逻辑 |

**建议**: `BuildingSubsystem` 作为 GameSystem 封装层与 `BuildingService` 职责重叠，需确认是否可合并为一个类。

### 4.3 StorageWal ↔ FunctionalWAL（WAL 双实现）

| 属性 | StorageWal | FunctionalWAL |
|------|------------|---------------|
| 路径 | `data/engine/StorageWal.kt` | `data/wal/FunctionalWAL.kt` |
| 类型 | `@Inject class` | `@Inject class`（实现 WALProvider 接口） |
| 引用数 | 2（StorageEngine、StorageModule） | 7（StorageModule、自身） |
| 关系 | 独立实现 | WALProvider 接口的实现类 |

**建议**: 需确认 `StorageWal` 与 `FunctionalWAL` 是否提供不同的 WAL 能力。若功能等价，保留 `FunctionalWAL`（实现标准接口），移除 `StorageWal`。

---

## 五、遗留开发脚本

项目根目录下存在 5 个开发/迁移辅助脚本，不属于项目运行时代码：

| # | 文件路径 | 用途 | 状态 |
|---|----------|------|------|
| 1 | `apply_session.js` | 解析 JSON 日志执行文件写入/编辑 | 一次性工具 |
| 2 | `convert_dialogs.py` | HalfScreenDialog → 全屏对话框转换 | 一次性迁移脚本 |
| 3 | `fix_all_build_errors.js` | 自动修复构建错误 | 一次性修复脚本 |
| 4 | `fix_viewmodels_final.js` | 清除 ViewModel 错误消息声明 | 一次性修复脚本 |
| 5 | `show_edits.js` | 调试日志查看工具 | 调试辅助 |

**建议**: 如已无使用需求，可安全删除。`scripts/optimize-building-images.mjs` 为构建脚本，建议保留。

---

## 六、循环依赖检查

对以下关键模块对进行了 import 循环检查：

| 检查对 | 结果 |
|--------|------|
| `GameEngine` ↔ `GameEngineCore` | 无循环 |
| `UnifiedGameStateManager` ↔ `GameEngine` | 无循环 |
| `StorageEngine` ↔ `StorageFacade` | 单向（Facade → Engine） |
| 各 Service ↔ Manager | 均为 Service → Manager 单向 |
| `data/` ↔ `core/` | 单向（core → data） |

**结论**: 项目无循环依赖问题。

---

## 七、清理优先级建议

### P0 — 立即可删除（零风险）

1. `core/engine/DiffUpdateSystem.kt`
2. `core/engine/scheduler/GameEventScheduler.kt`
3. `core/engine/transaction/UnitOfWork.kt`
4. `core/engine/storage/IndexedSlotStorage.kt`
5. `core/engine/operations/ProductionOperation.kt`
6. `core/engine/system/ServiceInterfaces.kt`
7. `network/NetworkUtils.kt`
8. `core/warehouse/WarehouseModels.kt`
9. 根目录 5 个遗留脚本（`apply_session.js`, `convert_dialogs.py`, `fix_all_build_errors.js`, `fix_viewmodels_final.js`, `show_edits.js`）

### P1 — 评估后删除（低风险）

1. `GameError.kt` — 确认 AppError 迁移完成后删除
2. `ProductionError.kt` + `ProductionOperationResult` — 同上
3. `SectWarehouseManager.kt` — 调用方改为直接引用 `OptimizedWarehouseManager`
4. `StorageWal.kt` — 确认与 `FunctionalWAL` 功能等价后删除

### P2 — 架构评估后合并（中风险）

1. `BuildingSubsystem` ↔ `BuildingService` — 合并为单一类
2. `StorageFacade` 中 9 个阻塞式废弃方法 — 确认调用方全部迁移到 suspend 后删除

### P3 — 序列化兼容性清理（需版本规划）

1. `SerializableSaveData.kt` 中的废弃 Proto 字段 — 需规划存档版本迁移
2. `GameData.kt` / `ProductionSlot.kt` 中的废弃字段 — 同上
3. `DiscipleAggregate.kt` 中的 `hp`/`mp` — 确认无直接访问后删除

---

## 八、附录：引用验证方法

本审计基于以下方法验证代码引用：

1. 对每个疑似死代码的类/对象名，在整个 `android/app/src/main/` 目录执行 `grep -rn "ClassName"`
2. 排除自身定义行（`class ClassName`、`object ClassName`、`import ...ClassName`）
3. 剩余匹配数即为外部引用次数
4. 引用次数为 0 的标记为死代码

**局限性**: 
- 字符串引用（如日志中的类名）可能产生误报
- 反射调用无法通过静态分析检测
- Hilt DI 的运行时注入链需人工确认
