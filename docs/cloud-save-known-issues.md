# 云存档模块 — 已知待修复问题

> 创建日期：2026-07-25
> 最后更新：2026-07-25

---

## 状态

| # | 问题 | 优先级 | 状态 |
|---|------|--------|------|
| 1 | 并发锁缺失 | P0 | ✅ 2026-07-25 新增 `cloudOpLock` AtomicBoolean |
| 2 | 下载覆盖无备份 | P0 | ✅ 2026-07-25 下载前备份原存档 |
| 3 | `invokeGetterString` 静默降级 | P1 | ✅ 2026-07-25 返回 `String?` + 异常 log |
| 4 | 跨版本存档兼容性 | P1 | ✅ 2026-07-25 version 元数据 + VersionMismatch 类型 |
| 5 | `shuffled()` 迁移至分区 PRNG (6处) | P2 | ✅ DisciplePurchaseService(5)+LootCalculator(1) 已迁移至 RngPartition.SYSTEM/EXPLORATION，2026-07-27 |
| 6 | `SharedPreferences apply() → commit()` | P2 | ⏸️ |
| 7 | `Dispatchers.IO` 硬编码 (10处) | 编码规范 | ⏸️ 贯穿全项目 |
| 8 | `GameStateStore` 直接访问 (15处) | 编码规范 | ⏸️ 贯穿全项目 |
| 9 | 构造参数超限 (15个) | 编码规范 | ✅ 2026-07-25 提取 `PersistenceFacade`，7个参数 |
| 10 | Text 颜色非黑色 | 编码规范 | ⏸️ UI 设计需要 |

## 未完成项

### P2（质量提升）

- **将 `shuffled()` 调用迁移至分区 PRNG** — `AISectDiscipleManager` / `DisciplePurchaseService` / `LootCalculator` 中共 6 处 `shuffled()` 使用 `kotlin.random.Random` 而非游戏分区 PRNG，影响确定性
- **SharedPreferences `apply()` → `commit()`** — UUID 缓存使用 `apply()` 异步写盘，进程被杀时丢失。关键数据应使用 `commit()` 同步写盘

### 编码规范（预存）

- **`Dispatchers.IO` 硬编码** — SaveLoadViewModel 中 10 处 `Dispatchers.IO` 应改为 Hilt `@Dispatcher(IO)` 注入（预存，贯穿全项目）
- **`GameStateStore` 直接访问** — SaveLoadViewModel 约 15 处直接调 `stateStore.update()/stateStore.runState`，应封装到 GameEngine（预存，贯穿全项目）
- **Text 颜色非黑色** — SaveSelectScreen 中 8 处 Text 颜色非 `Color.Black`，当前 UI 设计需要（预存，全项目风格）
