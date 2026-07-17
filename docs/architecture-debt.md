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
- `DeductResult` 是 sealed class，但编译器不强制检查（非 `Result<T>` 类型）
- 5 处忽略返回值的 bug 已在 2026-07-14 修复，但架构层面仍可绕开

**目标：**
- 评估将 `deduct()` 返回类型改为 `DeductResult` 并利用 Kotlin 编译器插件的 `@CheckResult` 或自定义 lint 规则
- 或提供 `deductOrThrow()` 变体，失败时抛异常避免忽略

## 4. 游戏渲染状态依赖 Compose 状态系统

**状态：** 🟡 待设计

**问题描述：**
宗门地图的游戏渲染数据（相机、预览位置、建筑列表）通过 Compose 的 `mutableFloatStateOf` / `mutableStateOf` 存储，
再经 `AndroidView.update` lambda 桥接到 `NativeSurfaceView` 渲染线程。这条路径存在两个架构级缺陷：

1. **Compose 依赖追踪劫持** — 渲染状态是高频更新的游戏数据，却被 Compose 的 snapshot 系统管理。
   帧率门控导致依赖追踪丢失，需要手动确保所有状态在门控前读取，脆弱且容易遗漏。
2. **帧率门控污染** — 渲染提交受 Compose 重组调度影响，不得不引入帧率门控和 force-push 机制。

**解决方案：** 游戏渲染状态独立于 Compose，使用原子快照模式：

```
新增 MapRenderState(原子状态持有者) + MapRenderSnapshot(不可变快照)
                        ↓
NativeSurfaceView 渲染线程: 无锁 readSnapshot()
Compose Overlay: StateFlow 订阅只读
```

**文件影响：**
- 新增 `core/engine/map/MapRenderState.kt`（~40 行）
- 新增 `core/engine/map/MapRenderSnapshot.kt`（~50 行）
- `MainGameScreen.kt`：删除 ~70 行预览/建筑状态读取，touch callback 改为写 `MapRenderState`
- `NativeSurfaceView.kt`：渲染线程改用 `readSnapshot()`，删除 `RenderFrame` data class
- `SoftwareCanvasBackend.kt`：参数改为 `MapRenderSnapshot`
- `PlacementConfirmButtons.kt`：改为订阅 `mapRenderState.snapshots`
- `SectUIState.kt`：可删除 `PlacementModeState`/`MoveModeState`

**行业参考：**
- Unity：`Update()` 读输入 → `CommandBuffer` 提交渲染 → `FixedUpdate()` 固定步长
- Supercell (CoC)：主线程组装不可变快照 → 队列提交 → 渲染线程消费
- 米哈游（原神）：Game Thread 持有完整状态 → Render Thread 只读快照

**优先级评估：**
- 修复 `c6348d43` 时已发现 Compose 依赖追踪问题，当时只修了 camera 没修预览
- 2026-07-16 完整修复了依赖追踪问题，但架构层面仍未根治
- 当前方案（门控外读状态 + force-push）已稳定，重构优先度中

## 5. 种植系统预存问题

**状态：** 🟡 待修复

**问题描述：**
2026-07-17 对抗性审查发现的 3 个种植系统预存问题（均不在本次修复范围内）：

1. **`plantOnSpiritFields` 两阶段写入非原子** — `BuildingFacadeImpl.plantOnSpiritFields` 分两步写状态：先 `stateStore.update` 写入 `spiritFieldPlants`，再 `removeSeedSync` 扣除库存。两步不在同一事务内，存在状态窗口——`spiritFieldPlants` 已更新但库存未扣，游戏中若在此窗口触发操作可能导致不一致。
   - 对标：Supercell Clash of Clans — 资源消耗和生产产出在同一事务中完成
   - 修复建议：将两步合并为单次 `stateStore.update {}` 或使用 Saga 补偿模式

2. **`seeds` 列表引用不变性不可靠** — `PlantingDialog.fieldGroups` 使用 `remember(seeds)` 推导，当 `EntityStore.items` 返回同一 List 引用时（内容已变但引用不变），Compose 不触发重组。依赖父级 StateFlow 实现是否创建新 List 引用。
   - 对标：React 不可变状态 + `useMemo` deps 数组
   - 修复建议：`EntityStore.items` 每次修改返回新 List 引用，或 `fieldGroups` 改用 `derivedStateOf`+深度快照

3. **tier4-6 种子精灵图 `fallbackToTier1` 回退限制** — `EquipmentSprite.kt` 的 `fallbackToTier1` 对 herbId 末尾数字 > 9 返回 null，导致 tier4+ 种子无兜底精灵图。
   - 影响：所有 tier4+ 种子在库存耗尽后右侧卡片显示"敬请期待"
   - 修复建议：扩展 `fallbackToTier1` 支持 tier4-6，或注册 tier4-6 专属精灵图

**优先级评估：**
- 问题1 风险最高（数据一致性），优先修复
- 问题2 偶发（依赖特定 StateFlow 实现），可随下一次 StateFlow 重构统一修复
- 问题3 纯视觉，优先度最低

**状态：** 🟡 待设计

**问题描述：**
- `DeductResult` 是 sealed class，但编译器不强制检查（非 `Result<T>` 类型）
- 5 处忽略返回值的 bug 已在 2026-07-14 修复，但架构层面仍可绕开

**目标：**
- 评估将 `deduct()` 返回类型改为 `DeductResult` 并利用 Kotlin 编译器插件的 `@CheckResult` 或自定义 lint 规则
- 或提供 `deductOrThrow()` 变体，失败时抛异常避免忽略
