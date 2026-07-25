# 写入守卫架构债务（待完成项）

> 本文件记录对抗性审查发现的预存结构性问题，以及当前修复决策。

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

## 汇总

| # | 项目 | 风险 | 修复成本 | 决策 |
|---|------|------|---------|------|
| 1 | `store` 存储暴露 | 低 | 中（~3 文件） | ⏸️ 暂不修复 |
| 2 | `requireWrite`/`onWrite` 为 `@JvmField var` | 低 | 中（~4 文件） | ⏸️ 暂不修复 |

以上项目均不会导致游戏崩溃或数据损坏——`deepCopy` 事务隔离 + 字段级守卫已在当前架构中覆盖了关键路径。
