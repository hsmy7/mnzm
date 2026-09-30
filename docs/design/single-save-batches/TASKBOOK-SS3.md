# TASKBOOK-SS3 · 单档语义 + UI 收敛 + 删元数据表

> **本文件是 SS3 的派工真源**。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) §2.1 / §3.3 / §3.6。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS0、SS1 已合入**、工作树干净。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 玩家侧**没有"多存档"概念**：无选槽、无存档管理多卡列表；系统侧**不删 `slot_id` 物理列**（恒为常量），账号隔离由 SS1 的分库承担。 |
| 验收① | `slot_id` 物理列**全部保留**；新增冻结常量 `LOCAL_SLOT_ID = 1`；本地档写入的 `slot_id` 恒为该常量（守卫断言取值集合 ⊆ `{CLOUD_SAVE_SLOT(0), LOCAL_SLOT_ID(1)}`） |
| 验收② | **UI 收敛**：设置页「存档管理」多卡弹窗（`SaveSlotDialog` 一族）改为「**立即备份**」+「**恢复旧档**」入口；槽位选择/多卡列表删除；`CloudSlotEntryCard` 收敛为单张"云端备份状态"卡 |
| 验收③ | **`save_slot_metadata` 整表删除**（B4）：实体注册、`clearAllSlotTables` 清单、`ClearAllSlotTablesCoverageTest`、`CacheWriteAtomicityGuardTest` 同批同步 |
| 验收④ | **`RepoInterfaces` 的 `slotId: Int = 0` 默认值消灭**（铁律 17）：改为 `slotId: Int = LOCAL_SLOT_ID` —— **签名不变、调用方不改**，但漏传不再落到云伪槽 0 |
| 验收⑤ | `AutoEntryResolver` 的"多档取最新"退化为唯一档逻辑；`-it.slot` 并列破环成为死逻辑并显式标注 |
| 验收⑥ | 删档语义升级为"**清空唯一进度**"（更重的二次确认；文案不与"备份"混淆） |
| **不做** | 不做存量多档迁移（SS4）；不做增量落盘（SS5）；不改云端链路与 `slot 0` 云会话语义（SS7）；**不改 `.sav`/`.bak` 文件名**（`slot_1.*` 保持不变，避免触发文件迁移）；**不碰 C++** 与 `currentSlot` 协议字段 |

---

## 2. 实测改造面（本批要动的点）

### 2.1 数据层

| 位置 | 现状 | 处置 |
|---|---|---|
| `StorageEngine.kt:503-546` `getSaveSlots()` | 硬编码插入 slot 0 云卡 + `for (slot in 1..lockManager.getMaxSlots())` 循环 | 收敛：恒返回 ≤2 项（本地 1 + 云 0） |
| `StorageEngine.kt:507-517` | slot 0 云卡构造点 | 保留（云会话语义不变） |
| `StorageEngine.kt:528-541` | 损坏档 `isLoadError` 构造点 | 保留（防"伪装空档被覆盖"） |
| `StorageEngine.kt:137-138` | `_currentSlot = MutableStateFlow(1)` | 收敛为 `LOCAL_SLOT_ID` 常量 |
| `StorageEngine.kt:142-148 / 270-275 / 424-431` | `save/load/delete` 先 `isValidSlot` 再取槽锁 | 保留校验，槽实参来源收敛 |
| `StorageEngine.kt:548-554` | `setCurrentSlot/getCurrentSlot` | 收敛（单档下恒为唯一槽） |
| `StorageEngineHeavyDataOps.kt:431-457` | `querySingleSlot` + `SaveSlot` 构造 | 保留单档查询 |
| `StorageEngineWriteOps.kt:111-114` | `buildLightGameData` 盖章 `slotId = slot; id = "game_data_$slot"` | 盖章值改取常量（**主键 id 形态不变**，避免无谓数据变更） |
| `StorageEngineWriteOps.kt:126-148` | `clearOldSlotEntities` 14 表按槽清空 | 保留（SS5 才改增量） |
| `StorageEngineSaveSupport.kt:273-303` | `clearAllSlotTables` 27 DAO 平铺清单 | 随删表同步（-1 项） |

### 2.2 单档语义的关键决策点

- **`getSaveSlots` 返回类型保持不变**（仍 `List<SaveSlot>`，恒 ≤2 项）⇒ **16 个消费文件不必全改**，UI 层只收敛展示。这是本批**降低改造面**的核心决定。
- **`SaveSlotMetadata` 删除的连锁**：`SaveSlotMetadata.kt` 实体 + `SaveSlotMetadataDao.kt`（8 方法，其中 5 个读方法**生产零消费者**，已 grep 全仓验证）+ `GameDatabase` 实体注册 + `StorageEngineSaveSupport.kt:65`（upsert）/`:289`（deleteBySlotId）调用点 + `ClearAllSlotTablesCoverageTest`（清单 = 实体数）。
- **`RepoInterfaces.kt` 有 35 处 `slotId: Int = 0`** —— `0` = 云会话伪槽且 `isValidSlot(0)` 为真 ⇒ 漏传静默写错槽。改默认值为 `LOCAL_SLOT_ID` 是**最小且正确**的修法（不用改 35 个签名与全部调用方）。
- **`.sav` 文件名不改**：`SaveFileManager.kt:388-390` 仍按 `slot_1.sav/.bak/.tmp`；改名会触发文件层迁移，属 SS4。

### 2.3 UI 层

| 位置 | 现状 | 处置 |
|---|---|---|
| `SettingsTab.kt:893-949` | `SaveSlotDialog` 一族（多卡 + 迁移卡 + 云槽区） | 收敛为「立即备份」+「恢复旧档」 |
| `SettingsTab.kt:1064-1080` | `DeleteSlotConfirmDialog` | 改为"清空进度"确认（更重） |
| `SettingsTab.kt:1119-1130 / 1192-1220` | 槽位卡列表遍历 + 按槽查空判定 | 删除选择语义 |
| `SettingsTab.kt:1270-1334` | `SaveSlotCard`（单槽卡片） | 保留展示字段，去掉"选择"交互 |
| `saveload/CloudSlotEntryCard.kt` | 云端槽位卡片（按槽渲染） | 收敛为单张状态卡 |
| `saveload/SaveMigrationCard.kt` | 存量迁移卡（按槽行 + 落点选择） | 保留骨架，落点选择在 SS4 改为归档恢复 |
| `SaveLoadViewModelSlotManageOps.kt:21-44` | `deleteSlot` | 语义升级为清空唯一进度 |
| `AutoEntry.kt:52-61 / 64-65` | `resolve` 取 `timestamp` 最新、`-it.slot` 破并列 | 标注死逻辑，保持行为正确 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **保留 `slot_id` 物理列，新增 `LOCAL_SLOT_ID = 1` 冻结常量** | 删列要动 27 实体 / 189 个 DAO 方法 / 64 条迁移链，且违反 `rules/database-migration.md`"永远不要删列"；钉常量则**零 schema 变更** |
| **D-2** | **不改 C++ 协议槽字段**（`currentSlot` / 物品 `slotId`） | 改它们会打破 `DiffAuthoritativeTickTest` / `DiffInventoryTest` / `DiffSpiritFieldTest` 并**强制重录对拍基线**（撞 G 批 R3 唯一窗口） |
| **D-3** | **`getSaveSlots` 返回类型不变**（恒 ≤2 项），UI 层收敛展示 | 把"数据层重构"降级为"数据层收敛 + UI 重排"，避免 16 个消费文件连锁 |
| **D-4** | **删 `save_slot_metadata` 整表**（B4） | 5 个读方法生产零消费者；单档下退化为 1 行常量表 |
| **D-5** | **`RepoInterfaces` 默认值改 `LOCAL_SLOT_ID`**（不改签名） | 满足铁律 17 且不产生 35 处签名连锁 |
| **D-6** | **不改 `.sav`/`.bak` 文件名** | 改名会触发文件迁移，与 SS4 的迁移逻辑耦合成一处风险 |
| **D-7** | **删表与守卫同批**：`clearAllSlotTables`、`ClearAllSlotTablesCoverageTest`、`CacheWriteAtomicityGuardTest` | 只改一处必红（守卫把"清单 = 实体数"钉死） |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS3-a** 常量与数据层收敛 | `StorageConstants.kt`（只加常量）、`StorageEngine.kt`、`StorageEngineHeavyDataOps.kt`、`StorageEngineWriteOps.kt` | 写路径**行为**（SS5 才改增量）、迁移链 | `slot_id` 取值 ⊆ `{0,1}` 守卫绿 |
| **SS3-b** 删元数据表 | `SaveSlotMetadata.kt`（删/退役）、`SaveSlotMetadataDao.kt`、`GameDatabase.kt`、`StorageEngineSaveSupport.kt` | 其它实体、迁移链顺序 | 实体注册与 schema 一致；`clearAllSlotTables` 清单同步 |
| **SS3-c** 仓库接口默认值 | `RepoInterfaces.kt`、`MailRepository.kt`（如有同型默认值） | 方法签名与参数顺序 | 全仓 `slotId: Int = 0` 归零（或改为常量）；调用方零改动即绿 |
| **SS3-d** UI 收敛 | `SettingsTab.kt`、`saveload/CloudSlotEntryCard.kt`、`SaveLoadViewModelSlotManageOps.kt` | 存档主流程语义 | UI 不直写 Store；对话框注册四同步（`DialogType` / `OverlayDialogRouter` / `DialogSystemRoutes` / `DialogTypeRenderCoverageTest`） |
| **SS3-e** 入口决策与测试 | `AutoEntry.kt`、`SaveSlotTest`、新增单档守卫 | 云端链路（SS7） | 死逻辑显式标注；守卫断言带操作指引 |

**共享面**：`StorageEngine.kt`（SS2/SS5 也要改）、`StorageEngineSaveSupport.kt`（SS4 要改）、`SettingsTab.kt`（SS4 要加恢复入口）⇒ 按 §3.3 串行。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :feature:game:testReleaseUnitTest :app:testReleaseUnitTest `
  --max-workers=1 --console=plain
.\gradlew.bat :core:data:detekt :feature:game:detekt :app:detekt --console=plain
# 本批关键守卫
.\gradlew.bat :core:data:testReleaseUnitTest --tests "*ClearAllSlotTablesCoverageTest*" --tests "*CacheWriteAtomicityGuardTest*" `
  --tests "*RoomMigration*" --max-workers=1 --console=plain
node -e "JSON.parse(require('fs').readFileSync('app/src/main/assets/changelog_entries.json','utf8'))"  # 改 changelog 后
```

**真机冒烟**：进入游戏 → 设置页只剩「立即备份」/「恢复旧档」→ 保存 → 读档 → 清空进度（二次确认）→ 重进。

---

## 6. 登记项

1. **跨批登记（SS4）**：「恢复旧档」入口本批只落**占位与接线**，实际列出归档并恢复由 SS4 实现（依赖 SS2 的归档读面）。
2. **跨批登记（SS5）**：本批的写路径仍是"全删全写"，`slot_id` 收敛只改**盖章值来源**，不改清空策略。
3. **风险（必须在报告里写明）**：本批与 SS4 之间，"多档玩家只剩下唯一档"——**SS3 与 SS4 必须同版本发布**（与 SS1/SS4 的约束同一条），否则玩家会看到"档少了"而没有恢复入口。
4. **文案纪律**：删档文案不得与"备份/恢复"混淆（单档下"删除"= 清空账号进度）。

---

## 7. 一句话给执行者

**把"多存档"从玩家可见语义里去掉：`slot_id` 列保留但恒为常量（不改 C++ 协议字段）、UI 收敛为「立即备份 + 恢复旧档」、删掉零消费者的 `save_slot_metadata` 表、并把 `RepoInterfaces` 的 35 处 `slotId = 0` 默认值改成唯一本地槽——不改 `.sav` 文件名、不做迁移（SS4）。**
