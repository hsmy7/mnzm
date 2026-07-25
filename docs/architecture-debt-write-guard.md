# 写入守卫架构债务（待完成项）

> 本文件记录对抗性审查发现的预存结构性问题，以及当前修复决策。
> 这些项目不属于本次 Bugly 崩溃修复的范围，但已识别并记录。

---

## 1. `store` 底层存储绕过守卫

**文件：** `ComponentTable.kt`

**问题：** `@PublishedApi internal val store` 使 `IntFlatArray`/`DoubleFlatArray`/`SparseArray` 的底层存储暴露为公开字节码。`copyTo()` 方法直接访问 `table.store.put(...)` 绕过 ComponentTable 层的 `requireWrite` 守卫。

**状态：** ⏸️ 暂不修复

**原因：**
- `store` 为 `@PublishedApi internal` 是 Kotlin 内联函数（`forEach`/`forEachValue`/`update`）编译的必要条件
- 同模块内有意绕过守卫的场景不存在（`bindAllOnWrite` 已绑定守卫到全部 ~90 张表）
- 跨模块 Java 字节码攻击超出威胁模型

**修复方案（未来选做）：**
- 给 `IntComponentTable`/`DoubleComponentTable`/`ComponentTable<T>` 添加 `internal fun rawPut(key: Int, value: T)` 供 `copyTo` 专用
- 将 `store` 从 `@PublishedApi internal` 改为 `private`
- 移除 `inline` 标记使 `forEach`/`forEachValue` 不再需要 `@PublishedApi`（轻微性能损失）

---

## 2. `requireWrite` / `onWrite` 为 `@JvmField var` 可被覆盖

**文件：** `ComponentTable.kt`（三种表类型）

**问题：** `@JvmField var requireWrite: (() -> Unit)?` 在字节码中为 public 字段，可被置 `null` 解除单表守卫。`onWrite` 同样可被置 `null` 导致脏检测失效。

**状态：** ⏸️ 暂不修复

**原因：**
- 与 `onWrite` 模式一致，单独改 `requireWrite` 造成不对称
- `bindAllOnWrite()` 通过 `ref.table.xxxWrite = value` 赋值，需要字段为 `var`
- 同模块内有意解除守卫的场景不存在

**修复方案（未来选做）：**
- 改为 `private var` + `fun setWriteGuard(callback: () -> Unit)`
- `fun setMutationCallback(callback: () -> Unit)` 封装两个字段的设值
- 修改 `CopyableTableRef` 接口提供 setter 方法替代直接字段赋值

---

## 3. `writeGuardEnabled` 全局开关可关闭所有守卫

**文件：** `DiscipleTables.kt` companion object

**问题：** `@Volatile var writeGuardEnabled: Boolean = true` 全局共享，任何代码可设 `false` 关闭所有实例的写守卫。测试异常退出可能遗留关闭状态。

**状态：** ⏸️ 暂不修复

**原因：**
- `WriteGuardRule` 测试基础设施依赖此开关；移除需要同时重构全部 ~20 个测试类的守卫策略
- 生产环境中无代码设此开关为 `false`
- 即使关闭写守卫，`deepCopy` 事务隔离机制依然有效（数据不会损坏，只是写入不再被监控）

**修复方案（未来选做）：**
- 移除 `writeGuardEnabled`，改为测试类直接操作 `tables.writeAllowed = true`
- 或改为 `ThreadLocal` 隔离测试线程与游戏线程

---

## 4. `ids` 为 public `MutableList` 可被直接变异

**文件：** `DiscipleTables.kt:56`

**问题：** `val ids: MutableList<Int> = CopyOnWriteArrayList<Int>()` 对外暴露 `add/remove/clear`，不受 `requireWriteAccess()` 保护。

**状态：** ⏸️ 暂不修复

**原因：**
- `ids` 被大量读路径直接遍历（`for (id in tables.ids) { ... }`），改为只读集合需要大规模重构
- `synchronized(ids)` 锁对象依赖此引用；改为只读集合需替换锁策略
- 所有高阶写操作（`insert/remove/replaceAll/clear`）在 `synchronized(ids)` 内修改 `ids`，不通过公开 API 修改

**修复方案（未来选做）：**
- 对外暴露 `val ids: List<Int> get() = _ids.toList()` 只读视图
- `fun addId(id: Int)` / `fun removeId(id: Int)` 封装变异操作
- 所有遍历点适配为 `.toList()` 快照

---

## 5. `deathRecords` 为 public `MutableList` 可被直接变异

**文件：** `DiscipleTables.kt:23`

**问题：** `val deathRecords = mutableListOf<DeathRecord>()` 对外暴露 `add/remove/clear`，不受守卫保护。

**状态：** ✅ 次要风险，当前不修复

**原因：**
- 仅 `cullDeadDisciples` 和 `markDead` 在 `synchronized(ids)` 内写入
- 对外读取仅为统计/墓碑展示
- 意外的 `deathRecords.clear()` 只影响展示，不损害游戏数据一致性

**修复方案（未来选做）：**
- 改为 `private val _deathRecords` + `val deathRecords: List<DeathRecord>`
- `fun addDeathRecord(record: DeathRecord)` 封装

---

## 6. `mergeDiscipleTables` / `createSettlementShadow` 死代码

**文件：** `GameStateStoreImpl.kt`、`DiscipleTables.kt`

**问题：** 影子结算路径（`swapFromShadow`/`createSettlementShadow`）自惰性结算引擎上线后不再被调用。

**状态：** ✅ 已清理（2026-07-26）
- 代码已物理移除（仅剩 KDoc 注释引用，已全部更新）
- `copyRowFrom()` 死代码已移除
- `SettlementStrategy.kt` KDoc 已更新为当前架构描述

---

## 汇总

| # | 项目 | 风险 | 修复成本 | 决策 |
|---|------|------|---------|------|
| 1 | `store` 存储暴露 | 低 | 中（~3 文件） | ⏸️ 架构文档 |
| 2 | `requireWrite`/`onWrite` 为 `@JvmField var` | 低 | 中（~4 文件） | ⏸️ 架构文档 |
| 3 | `writeGuardEnabled` 全局开关 | 低 | 高（~20 测试） | ⏸️ 架构文档 |
| 4 | `ids` public MutableList | 低 | 高（~100 遍历点） | ⏸️ 架构文档 |
| 5 | `deathRecords` public MutableList | 极低 | 低（1 文件） | ⏸️ 架构文档 |
| 6 | 影子结算死代码 | 低 | 低（已修复守卫） | ⏸️ 架构清理 |

以上项目均不会导致游戏崩溃或数据损坏——`deepCopy` 事务隔离 + 字段级守卫已在当前修复中覆盖了关键路径。
