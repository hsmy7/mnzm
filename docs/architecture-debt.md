# 架构债务：待重构项

> 本文档记录当前架构中已知的设计缺陷和待重构项。
> 修复前请先阅读对应设计文档。

## 1. `allocateNextId()` 两步模式与批量原子 API 缺失

**状态：** 🔴 待重构 — 幽灵弟子修复后残余架构问题

**问题描述：**
- `allocateNextId()` 先 `ids.add(id)` 再要求调用方自行 `insert()`，中间存在异常窗口导致 ID 悬空
- 12+ 处代码使用 `clear() + forEach { insert() }` 裸模式，无原子批量替换方法
- 虽然已新增 `allocateAndInsert()` 修复入口，但 `allocateNextId()` 仍可被调用

**目标：**
- `allocateNextId()` 标记 `@Deprecated` 并逐步移除
- `DiscipleTables` 新增 `replaceAll(list: List<Disciple>)` 原子批量方法，替代 `clear() + forEach { insert() }` 模式
- 所有批量更新路径统一使用 `replaceAll`

**参考：** Unity DOTS `EntityManager.CreateEntity()` / Flecs `world.entity()` — ID 分配即数据写入完成

## 2. `stateStore.update{}` 隔离语义不统一

**状态：** 🟡 待设计

**问题描述：**
`update{}` 块内不同数据类型的隔离语义不同：
- `gameData`：直接引用（写时复制靠 data class `copy()`，引用变化自动检测）
- `discipleTables`：deepCopy（2026-07-14 修复已标准化）
- EntityStore：`EntityStore(curItems)` 新包装（freeze + 引用比较）

三种语义混在一起，调用方不清楚操作的是副本还是原件。

**目标：**
- 统一为 deepCopy 语义或写时复制
- `update{}` 块内所有修改在深拷贝上进行，异常时丢弃
- 编写开发者指南文档明确 `update{}` 内各字段的隔离行为

## 3. `wallet.deduct()` 返回值强制检查

**状态：** 🟡 待设计

**问题描述：**
- `DeductResult` 是 sealed class，但编译器不强制检查（非 `Result<T>` 类型）
- 5 处忽略返回值的 bug 已在 2026-07-14 修复，但架构层面仍可绕开

**目标：**
- 评估将 `deduct()` 返回类型改为 `DeductResult` 并利用 Kotlin 编译器插件的 `@CheckResult` 或自定义 lint 规则
- 或提供 `deductOrThrow()` 变体，失败时抛异常避免忽略
