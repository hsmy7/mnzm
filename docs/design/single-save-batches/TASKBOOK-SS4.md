# TASKBOOK-SS4 · 单档 UI 终态 + 元数据表清除

> **本文件是 SS4 的派工真源**（开工补卡，对齐 `DISPATCH-ledger.md` §5 在册理由：SS4 的 UI 收敛面取决于 SS1 去槽后的数据模型）。上位方案 §2.7。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：**SS1（去槽位维度）与 SS2（账号数据空间）已合入**（`580fab8fb`/`a4c17acc2`）；`StorageEngine.kt` 事务编排段已被 SS3 冻结给 SS5——本批**不得**触碰该段（只动 getSaveSlots 读面与删表清单）。
> 行号证据：2026-10-01 23:5x 主树实测（SS3 合并后）。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 单档语义的 UI 终态：玩家可见面**零槽位概念**（无选槽、无多卡列表、无槽号文案），数据面把为多档而生的元数据表整表删除。 |
| 验收① | **`save_slot_metadata` 整表删除**：`SaveSlotMetadata`（实体+`SlotMetadata` 数据类，`core/data/local/SaveSlotMetadata.kt`）+ `SaveSlotMetadataDao.kt` + `GameDatabase.kt` `@Database` 实体注册同步移除；`DATABASE_VERSION` **69→70** 例行递增（实体清单变更；destructive 重建口径与 SS0–SS2 一致，不写迁移） |
| 验收② | **`SaveSlot`/`SlotMetadata` 投影收敛**：`StorageModels.kt:10` `SlotMetadata` 删除；`StorageEngine.getSaveSlots`（`:503` 附近）与 `StorageFacade.getSaveSlotsSuspend` 收敛为**单档投影**（无 `slot` 字段，或返回单对象/空集合语义——以最贴 UI 终态为准并在报告说明）；本地行 `SaveSlot(slot=1)` 过渡投影删除（SS1 报告 §5.5 登记） |
| 验收③ | **UI 零槽位概念**：存档管理弹窗收敛为单档卡片（删 `CloudSlotEntryCard.kt` 槽位卡形态、`SaveLoadViewModelSlotManageOps.kt` 69 行槽管理面退役）；`SettingsTab` 的 `SaveSlotSubmitActions` 以 `it`（SaveSlot）分发改为单档语义；全 `feature/game` 生产源码 grep `slot`（存档语境）零命中——**伪同形名九处除外**（铁律 15 清单） |
| 验收④ | **两条守卫同批更新（改语义不放宽）**：`ClearAllSlotTablesCoverageTest`（SS1 已随 `clearAllTables` 更名改造过——本批删表后清单再收敛）与 `CacheWriteAtomicityGuardTest`；`MailSnapshotWritePathGuardTest` 若涉同锚点同步 |
| 验收⑤ | **对话框注册同步**：新增/删除对话框走四同步（注册表/类型枚举/守卫测试/文档——`rules/new-dialog-checklist.md`）；本批删槽位弹窗组件时逐项核对 |
| 验收⑥ | **注释语病清零**：`SaveLoadViewModelCloudLoadOps` 等 KDoc 的"槽位 0 云镜像/本地 1..6 零影响"退役语义残留（SS1 报告 §5.4 登记）；`GameDatabase.kt` `MAX_BACKUP_GAME_DATA_ROWS` 注释"每槽一行，正常 ≤ 7"（SS1 报告 §6 登记）——随批改为当前状态表述 |
| **不做** | 不做增量落盘（SS5）；不接云端（SS7）——**云侧槽命名（`mnzm_v2_slot_N`/`UploadLedger` 按槽键/`downloadIntoCache(sourceSlot, targetSlot)` 跨槽语义）保留至 SS7 坍缩**（SS1 报告 §5.3）；不碰 C++；不做引擎装配深挖（SS2 报告 §6.4 合规回调链仅顺手定位登记） |

---

## 2. 实测现状（2026-10-01 主树）

| 面 | 位置 |
|---|---|
| 元数据表 | `SaveSlotMetadata.kt`（实体 + `SlotMetadata` 数据类）、`SaveSlotMetadataDao.kt`、`GameDatabase.kt` 实体注册、`StorageEngineSaveSupport.kt`（删表清单引用） |
| 投影 | `StorageModels.kt:10` `SlotMetadata`；`StorageEngine.kt` `getSaveSlots`；`StorageFacade.kt` `getSaveSlotsSuspend` |
| UI 面 | `SaveLoadViewModel` 五个 Ops（NewGame/Save/Cloud/Restart/SlotManage）+ `CloudSlotEntryCard.kt`（134 行）+ `SettingsTab.kt`（`SaveSlotSubmitActions` 分发 + 存档管理弹窗） |
| 守卫 | `ClearAllSlotTablesCoverageTest` / `CacheWriteAtomicityGuardTest` / `MailSnapshotWritePathGuardTest`（三测试引用 `clearAllTables` 锚点） |
| 云侧（不动） | `TapCloudSaveManager.kt` 的 `mnzm_v2_slot_N` 命名族 → SS7 |

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **`save_slot_metadata` 整表删除**（用户 B4 拍板；SS1 报告 §5.2 登记） | 单档下每槽元数据失去存在意义；实体清单变更随 destructive 重建，无迁移负担 |
| **D-2** | **投影收敛以 UI 终态为准**：`SaveSlot` 类若仍被云侧卡位投影需要则保留最小形态，否则随 `SlotMetadata` 一并删——**由实测消费面决定**，报告列全消费清单 | 云侧归 SS7，本批只动本地读面 |
| **D-3** | **UI 收敛 = 删多卡列表，不做新设计**：单档卡片 + 删档/云下载/诊断等既有入口保留（主界面退役批已收敛过一轮） | 本批是"删概念"不是"做新功能"；存档诊断入口（SS3）不动 |
| **D-4** | **守卫改语义不放宽**：删表后 `ClearAllSlotTablesCoverageTest` 的 DAO 清单断言收敛，原子性断言原样 | D-6 同款纪律 |
| **D-5** | **版本 69→70 例行递增**，无迁移 | SS0 删档口径一致 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS4-a** 元数据表删除 | `SaveSlotMetadata.kt`、`SaveSlotMetadataDao.kt`、`GameDatabase.kt`、`StorageEngineSaveSupport.kt`、`MigrationRequiredGuardTest` 基线注记 | 其他表 | 实体清单与守卫基线一致；版本 69→70 |
| **SS4-b** 投影收敛 | `StorageModels.kt`、`StorageEngine.kt`（仅 getSaveSlots 读面）、`StorageFacade.kt` | `StorageEngine.kt` 事务编排段（SS5 冻结）、写路径 | 消费清单 enumerated；无 slot 字段残留 |
| **SS4-c** UI 收敛 | `SaveLoadViewModel*Ops` 五文件、`CloudSlotEntryCard.kt`、`SettingsTab.kt`、相关对话框组件 | 登录状态机、存档诊断（SS3） | grep 存档语境 slot 零命中（伪同形名除外） |
| **SS4-d** 守卫与注释 | 三守卫测试、注释语病两处（验收⑥） | 断言放宽 | 注释只描述当前状态 |
| **主线程** | `report-SS4.md`、门禁复跑 | — | §5 清单 |

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :feature:game:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so" --console=plain
.\gradlew.bat :core:data:detekt :feature:game:detekt :app:detekt --console=plain
node scripts/check-agent-instructions.mjs   # 改 rules/docs 后必跑
```

**提交前**：`git status` 只留本批改动；构建副产物 `git checkout --` 还原；一次性脚本清零。**注意：文档面编辑一律在 worktree 内完成并随批 commit（SS3 曾漏提交在主树，由看护补笔转移）。**

---

## 6. 登记项

1. **跨批登记（SS7）**：云侧槽键坍缩（`mnzm_v2_slot_N`/`UploadLedger`/`downloadIntoCache`）+ MMKV 台账键随槽维度收口为单键——全部归 SS7。
2. **跨批登记（SS10）**：玩家可见变更（存档管理弹窗单档化）并入 4.2.00 唯一条目。
3. **风险**：`SettingsTab` 是 SS4（本批）与 SS3（已并网）共享面——SS3 已并网无冲突；SS5（增量落盘）不动 UI，无冲突。
4. **顺手项（可选，不强制）**：SS2 报告 §6.4 合规回调宿主链（`injectXianxiaApplication` 触达 `mailDao` 节点）定位登记。

---

## 7. 一句话给执行者

**删掉 `save_slot_metadata` 整表与 `SlotMetadata` 投影，把存档弹窗收敛成单档卡片、全 UI 无槽号，两条守卫同批改语义——云侧槽命名留给 SS7，本批只清本地概念。**
