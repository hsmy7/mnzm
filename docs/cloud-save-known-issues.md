# 云存档模块 — 已知待修复问题

> 创建日期：2026-07-25
> 来源：对抗性审查 + 编码规范审查
> 状态：待修复（不影响功能，但需在后续迭代中处理）

---

## 优先级分类

### P0（数据安全）

- **并发锁缺失** — `downloadFromCloudSave()` / `uploadToCloudSave()` 无互斥锁，连续点击可触发多协程并行操作同一临时文件，导致数据损坏。应参考 `saveGame()` 的 `saveLock` 模式新增 `cloudOpLock`（`AtomicBoolean`），upload/download 共享同一锁
- **下载覆盖无备份** — `onCloudSaveLoad` 调用 `storageFacade.save()` 覆盖本地 slot 前不备份。应在覆盖前将原存档备份为 `.bak`，加载失败时可回滚

### P1（正确性）

- **`invokeGetterString` 静默降级** — 反射 getter 异常时返回 `""`，导致 `getName()` 永远不匹配、UUID 查找失败。应改为返回 `String?`，调用方检查 null 并 log 详细错误（异常类型、方法名、archive 类名）
- **SDK 不可用时上传误报 Success** — `performTapTapUpload` 在 `CloudSaveApiReflector.resolve()` 返回 null 时吞掉异常不抛，外层 map 为 `Success`。已修复为抛 RuntimeException
- **跨版本存档兼容性** — `downloadFromCloudSave()` / `onCloudSaveLoad` 均未检查 `saveData.version` 与当前 `GameConfig.Game.VERSION` 的兼容性

### P2（质量提升）

- **将 `shuffled()` 调用迁移至分区 PRNG** — `AISectDiscipleManager` / `DisciplePurchaseService` / `LootCalculator` 中共 6 处 `shuffled()` 使用 `kotlin.random.Random` 而非游戏分区 PRNG，影响确定性
- **SharedPreferences `apply()` → `commit()`** — UUID 缓存使用 `apply()` 异步写盘，进程被杀时丢失。关键数据应使用 `commit()` 同步写盘

### 编码规范（预存）

- **`Dispatchers.IO` 硬编码** — SaveLoadViewModel 中 10 处 `Dispatchers.IO` 应改为 Hilt `@Dispatcher(IO)` 注入（预存，贯穿全项目）
- **`GameStateStore` 直接访问** — SaveLoadViewModel 约 15 处直接调 `stateStore.update()/stateStore.runState`，应封装到 GameEngine（预存，贯穿全项目）
- **构造参数超限** — SaveLoadViewModel 15 个构造参数（上限 7），应分组为 Facade（预存，贯穿全项目）
- **Text 颜色非黑色** — SaveSelectScreen 中 8 处 Text 颜色非 `Color.Black`，当前 UI 设计需要（预存，全项目风格）
