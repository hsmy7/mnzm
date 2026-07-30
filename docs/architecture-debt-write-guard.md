# 写入守卫架构债务（已治理）

> 本文件记录对抗性审查发现的预存结构性问题。
>
> **更新于 2026-07-30**：写入守卫已全面加固。

---

## ✅ 已治理：`store` 底层存储绕过守卫 + `requireWrite`/`onWrite` 暴露

**治理日期：** 2026-07-30

### 修改内容

| 文件 | 变更 |
|------|------|
| `ComponentTable.kt` — `ComponentTable<T>` | `requireWrite`/`onWrite` 改为 `private var` + `setWriteGuard()`/`setMutationCallback()`；新增 `putTo()` 守卫方法 |
| `ComponentTable.kt` — `IntComponentTable` | 同上 |
| `ComponentTable.kt` — `DoubleComponentTable` | 同上 |
| `ComponentTable.kt` — `IntTableRef` | `copyTo()`/`copySelfTo()` 调用 `dst.putTo()` 替代 `dst.store.put()` |
| `ComponentTable.kt` — `DoubleTableRef` | 同上 |
| `ComponentTable.kt` — `RefTableRef` | 同上 |
| `ComponentTable.kt` — `MutableTableRef` | 同上 |
| `DiscipleTables.kt` — `bindAllOnWrite()` | `ref.table.onWrite = dirtyCb` → `ref.table.setMutationCallback(dirtyCb)`；`ref.table.requireWrite = guard` → `ref.table.setWriteGuard(guard)` |
| `ComponentTableTest.kt` | `table.onWrite = { ... }` → `table.setMutationCallback { ... }` |

### 剩余风险（保留不修）

以下为低级 `@PublishedApi internal` 字段仍在 `IntFlatArray`/`DoubleFlatArray` 中保留：

| # | 项目 | 风险 | 不修原因 |
|---|------|------|---------|
| 1 | `IntFlatArray.values`/`idToSlot`/`keys`/`size_` 为 `@PublishedApi internal` | 极低 | `inline` `ensureCapacity`/`growKeys` 函数需要；`IntFlatArray` 层无守卫（守卫在 `IntComponentTable` 层）；已通过 `putTo()` 封堵 `IntComponentTable` 层暴露 |
| 2 | `DoubleFlatArray` 同上 | 极低 | 同上 |

---

## 遗留：`IntFlatArray`/`DoubleFlatArray` 级别守卫

`IntFlatArray` 和 `DoubleFlatArray` 不直接暴露为 `CopyableTableRef.store` 字段（现已被 `putTo()` 封堵），但可以通过 `IntComponentTable.store` 间接访问。当前的威胁模型：
- 同模块内不会绕过（`bindAllOnWrite` 覆盖全部 ~90 张表）
- 跨模块 Java 字节码攻击超出范围

**不在此轮治理范围内。**
