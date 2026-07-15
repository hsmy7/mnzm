# 架构债务：待重构项

> 本文档记录当前架构中已知的设计缺陷和待重构项。
> 修复前请先阅读对应设计文档。

## ~~1. `allocateNextId()` 两步模式与批量原子 API 缺失~~ ✅ 已完成

**状态：** ✅ 已完成（2026-07-15）

**完成内容：**
- `allocateNextId()` / `rollbackAllocation()` 标记 `@Deprecated`
- `DiscipleTables` 新增 `replaceAll(list: List<Disciple>)` 原子批量方法（含 `check` 重复 ID 守卫）
- 28 处 `clear() + forEach { insert() }` 全部迁移为 `replaceAll()`
- 12+ 生产文件修改，覆盖所有批量更新路径

**参考：** Unity DOTS `EntityManager.CreateEntity()` / Flecs `world.entity()` — ID 分配即数据写入完成

## 2. `stateStore.update{}` 隔离语义不统一 ✅ 部分完成

**状态：** ✅ 2026-07-15 新增 WriteGuard 运行时守卫

**完成内容：**
- `DiscipleTables` 新增 `writeAllowed` + `requireWriteAccess()` — 绕过 `update{}` 的直接写在运行时立即抛异常（对标 Android StrictMode）
- `assertAllTablesConsistent()` — `insert/remove/replaceAll` 后 Debug 校验 90+ 表 id 一致性（对标 Bevy UnsafeWorldCell）
- `consistencyCheckEnabled` Release 开关
- `discipleTables` 的 deepCopy 已标准化（所有 `update{}` 内统一使用）

**待完成：**
- `gameData` 和 `EntityStore` 的隔离语义仍不一致（直接引用 vs frozen 引用）
- 单 `data class TransactionState` 统一所有字段的写时复制

## 3. `wallet.deduct()` 返回值强制检查

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
