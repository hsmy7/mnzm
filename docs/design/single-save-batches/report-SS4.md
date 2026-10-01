# REPORT-SS4 · 单档 UI 终态 + 元数据表清除

> 批次真源：[`TASKBOOK-SS4.md`](TASKBOOK-SS4.md) · 派工册：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §3/§4
> 分支 `feat/single-save-SS4`（基线 `d6a86884e`）· 2026-10-02 实施
> 改动规模：**38 文件**（33 改 + 4 删 + 1 改名入，另 1 文件改名见 §2）· 树净（构建副产物已还原，一次性脚本零）

---

## 1. 验收判据对照（TASKBOOK-SS4 §1）

| # | 判据 | 结果 | 落点 |
|---|---|---|---|
| ① | `save_slot_metadata` 整表删除 + 版本 69→70 | ✅ | §2-A |
| ② | `SaveSlot`/`SlotMetadata` 投影收敛为单档投影 | ✅ | §2-B |
| ③ | UI 零槽位概念（存档语境 grep 零命中） | ✅ | §2-C + §4 |
| ④ | 两条守卫同批更新（改语义不放宽） | ✅ | §2-D |
| ⑤ | 对话框删除四同步 | ✅（核对本批无新增/删除注册表对话框——退役组件均为弹窗内部子组件，未入 `DialogSystemRoutes`/类型枚举注册面） | §2-C |
| ⑥ | 注释语病清零（SS1 §5.4/§6 登记） | ✅ | §2-D |

## 2. 做了什么

### A. 元数据表整表删除（验收①，SS4-a）

| 文件 | 动作 |
|---|---|
| `SaveSlotMetadata.kt`（实体）/ `SaveSlotMetadataDao.kt` | **整文件删除**（7 个 DAO 方法全部零读者或仅写面，B4 拍板） |
| `GameDatabase.kt` | `DATABASE_VERSION` **69→70**；`@Database` 实体注册删 `SaveSlotMetadata::class`；删 `saveSlotMetadataDao()` 访问器；版本注释更新为 v70 当前状态；`MAX_BACKUP_GAME_DATA_ROWS` 注释"每槽一行，正常 ≤ 7"→"game_data 恒单行"（验收⑥）；顺删 companion 内孤儿空注释 `/** 备份文件验证结果 */`（真身 `BackupValidation` 在文件顶层私有区） |
| `StorageEngineSaveSupport.kt` | 删 `syncSlotMetadata`（唯一写入口）；`clearAllTables` 清单删一行（26 表→25 行调用）；文件头注释去"槽位元数据同步" |
| `StorageEngineWriteOps.kt` | `writeCoreEntities` 删 `syncSlotMetadata(data)` 调用行（删表的编译必需伴随；该文件其余面 = SS5 冻结区，未触碰） |
| `MigrationRequiredGuardTest.kt` | `BASELINE_ENTITIES` 基线删 `SaveSlotMetadata::class`（27→26，"SS9 时刻 v68"注记更新为"SS4 时刻 v70"） |

无迁移（SS0–SS2 同口径：destructive 重建，D-5）。

### B. 投影收敛（验收②，SS4-b）

**D-2 实测消费面结论：`SaveSlot` 类整删**（无云侧卡位投影需要——云侧摘要由 `TapCloudSaveManager.CloudSaveInfo`/`CloudSaveEntry` 独立承载，不经 `SaveSlot`）。

| 文件 | 动作 |
|---|---|
| `StorageModels.kt` → **改名 `SaveInfo.kt`** | 删 `SlotMetadata` + `BackupInfo`（后者全仓零消费死类型，顺手清）；新增 **`SaveInfo` 单档投影**（无 slot 字段；三态语义 isEmpty/isLoadError 互斥红线 KDoc 原样保留；派生 `displayTime`/`saveTime`） |
| `SaveData.kt` | 删 `SaveSlot` 数据类 |
| `StorageEngine.kt`（仅读面） | `getSaveSlots()`（含 slot 0 云占位卡）删除 → **`getSaveInfo(): SaveInfo`**（查询异常收敛 isLoadError 态，不伪装空档）；`getMetadata()` → `readMetadata()`（直透 `GameDataDao.getMetadata()` 投影，吞异常行为与原先一致——`StorageFacade.initialize` 完整性探测消费） |
| `StorageEngineHeavyDataOps.kt` | `querySingleSlot()` → **`querySaveInfo()`**（去 slot/name/customName 字段） |
| `StorageFacade.kt` | `getSaveSlotsSuspend(): List<SaveSlot>` → **`getSaveInfoSuspend(): SaveInfo`**（异常 → `SaveInfo(isLoadError=true)`——损坏档伪装空档 = 数据丢失红线，维持）；"槽位管理方法"段落名改"单档摘要方法" |
| `MainActivity.kt` | `queryLocalSlots(): List<SaveSlot>?` → **`queryLocalSave(): SaveInfo?`** |
| `AutoEntry.kt` | **单档签名**：`hasLoadableLocal(save: SaveInfo?)` / `resolve(save, cloudHasSave)`；判定链语义逐条保留（可读→读云→损坏档交读档链→新建） |

**消费清单全列**（D-2 义务）：`SaveSlot` 旧消费 = core/data 读面 3（getSaveSlots/querySingleSlot/getSaveSlotsSuspend）+ app 2（MainActivity/AutoEntry）+ feature/game 6（SaveLoadViewModel 主文件、LoadOps/CloudOps/LoadDelegate、SettingsTab、SaveSlotTest）——全部收敛至 `SaveInfo` 或删除，零残留。

**途中发现（顺带修复）**：旧 `getSaveSlots()` 的 slot 0 云占位卡 `isEmpty=false`，会被 `AutoEntryResolver.loadableSlots` 误判为可读本地档——本地无档时自动进入错误判 `LoadLocal`（不查云端兜底）。单档投影天然消除云伪槽，该路径随之修复（新档判定链正确落 `queryCloudHasSave`）。

### C. UI 零槽位（验收③，SS4-c）

| 文件 | 动作 |
|---|---|
| `CloudSlotEntryCard.kt` | **整文件删除**（SR-3 云槽位卡形态退役；云入口由存档弹窗新 `CloudSaveEntryCard`（SettingsTab 私有）承载，数据源 = `cloudSaveInfo` 真实摘要） |
| `SaveLoadViewModelSlotManageOps.kt` | **整文件删除**（69 行槽管理面退役）；`deleteSlot` → **`deleteLocalSave()`** 迁入 `SaveLoadViewModelSaveOps.kt`（删档入口保留，D-3）；`queryCloudSlotEntries`（slot_N 列表查询）随之退役 |
| `SaveLoadViewModel.kt` | `saveSlotsFlow: List<SaveSlot>` → **`saveInfoFlow: SaveInfo?`**；`saveSlots`（mergeCloudSlot combine）删除；`pendingSlotFlow`/`pendingSlot` **死状态整链删除**（生产 UI 零读者）；`loadGameFromLocalSlot()` → **`loadLocalSave()`**；`refreshSaveSlots()` → **`refreshSaveInfo()`**；`downloadCloudSlotToLoad()` → **`downloadCloudSaveToLoad()`**；云摘要 KDoc 改单档语义 |
| `SaveLoadModels.kt` | `SaveLoadState` 删 `pendingSlot` 字段（`pendingAction` 有测试消费，保留） |
| `SaveLoadViewModelLoadOps.kt` | `loadGame(SaveSlot)` 扩展删除（仅测试消费，测试改 `loadLocalSave()`）；`loadGameInternal(saveSlot)` → `(saveInfo: SaveInfo)`；`performLoadToSlot` → **`performLoadGame`**；`loadSaveDataForSlot` → **`loadSaveData`**（去 UnusedParameter）；死代码 `setPendingSave`/`setPendingLoad`/`clearPendingAction` 删除（全仓零消费者） |
| `SaveLoadViewModelSaveOps.kt` | 保存成功/OOM/失败三处刷新收敛 `getSaveInfoSuspend`；`slotMails` → `sessionMails`；`readMails` KDoc 去"当前 slot" |
| `SaveLoadViewModelNewGameOps.kt` / `RestartOps.kt` | pendingSlot 全清 + 刷新收敛 + `needSlotRefresh`→`needInfoRefresh` + 注释去"槽位邮件/currentSlot 回滚" |
| `SaveLoadViewModelCloudOps.kt` | **`saveToCloud()` 删除**（唯一消费者 = 弹窗槽选择分发；云上传入口由 `CloudSaveDialog` 既有路由承载，`uploadToCloudSave` 保留）；**`mergeCloudSlot` 删除**（slot 0 覆盖语义随投影收敛消失）；"云会话槽位 0"注释改单档表述 |
| `SaveLoadViewModelCloudLoadOps.kt` | 注释语病清零（验收⑥主点）：文件头"槽位归一"、"云会话槽位 0 加载/本地 1..6 槽位零影响"、失效的 slotId/currentSlot 修正注释块、**尾部孤儿 KDoc 整块删除**（`[effectiveSlot]` 已随 SS1 消失）；`pendingSlot` 去 |
| `SaveLoadViewModelCloudSlotOps.kt` | `pendingSlot` 去；KDoc 消费面改述（`resolveCloudConflict` 冲突收口重跑下载）；LEGACY 拒绝文案"云存档槽位功能未开启"→"云存档下载未开启"；头注释登记云侧命名 SS7 收口。**云侧链本体（loadCloudSlot/performCloudSlotLoad/downloadIntoCache）保留至 SS7**（`resolveCloudConflict`→GameActivity 冲突弹窗仍在用） |
| `SettingsTab.kt` | 存档弹窗重写单档终态：`SaveSlotDialog` → **`SaveInfoDialog`**；**`SaveSlotSubmitActions(it: Int 槽分发)` → `SaveInfoActions(无参单档语义)`** + `SaveInfoContentState`（分组传参）；多卡列表/选中态/`SaveSlotListState` 删除；本地单卡（三态渲染：有档摘要/空/"读取失败"）+ 云卡；`SaveSlotCard`→`SaveInfoCard`、`DeleteSlotConfirmDialog`→`DeleteSaveConfirmDialog` 组件族全去 slot 化；`onSaveSlotClick`→`onSaveInfoClick` 回调链；SR-3 "云端槽位存档区"退役 |
| `SaveDataTrimmer.kt` | KDoc"槽位全量邮件快照/读当前 slot/replaceMailsForSlot"→ 单档表述 + 修正函数名引用 |
| `SaveLoadViewModelAutoSaveOps.kt` | 死常量 `MIN_AUTO_SAVE_SLOT` 删除；三前置注释"旗标+槽位+引擎"→"旗标+已加载+引擎"（`shouldAutoSave(hasActiveSlot=…)` 参数名为 core/data 签名面，登记 SS6 收口） |
| `GameActivity.kt`（app，编译必需伴随） | boot 失败逃生口 `deleteSlot`→`deleteLocalSave`；初始化分支 `loadGameFromLocalSlot`→`loadLocalSave`；注释"删除读档失败的槽位"→"本地档" |
| `SaveLoadLoadDelegate.kt` | **整文件删除**（`loadGame(SaveSlot)` 链为主界面退役后死代码——生产零消费，测试仅消费其纯函数薄包装；`SaveSlot` 删除后不可编译）。`BuildingOverflowMigrationTest` 改直调 `core/engine computeBuildingOverflowMigration`（原始实现所在），测试语义零变化 |

### D. 守卫与注释（验收④⑥，SS4-d）

| 文件 | 动作 |
|---|---|
| `ClearAllSlotTablesCoverageTest` | **改语义不放宽**：删表后清单/声明面 24/25，防假绿阈值 25→20（双向相等断言原样）；"SR-7 v53 26/27"历史注记改"SS4 24/25"当前状态 |
| `CacheWriteAtomicityGuardTest` | **锚点核验，断言零改动即绿**：两断言（withTransaction 先于写/第一条删表在事务内）不锚定具体表，删一行 DAO 清理不影响 |
| `MailSnapshotWritePathGuardTest` | **不涉同锚点**（锚 WriteOps `clearOldEntities`/`writeCoreEntities` 的邮件段与 `MailOps`），未改 |
| `MigrationRequiredGuardTest` | 基线随批更新（见 §2-A） |
| `SaveLoadViewModelCloudLoadOps.kt` 等注释 | 见 §2-C（SS1 报告 §5.4 登记项全部消费） |

**途中发现并实修（SS3 遗留 detekt 违规 2 处，非本批引起）**：`StorageDiagnosticsViewModel.refresh()` 的 `catch (e: Exception)` 缺 `@Suppress`+防御注释（`TooGenericExceptionCaught`）；`StorageDiagnosticsDialog.kt` 孤儿 `width` import（`UnusedImports`）。worktree 首跑 `--rerun-tasks` 复现实修，报告登记。

## 3. 验证（门禁实跑，终树终值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（全模块） | ✅ BUILD SUCCESSFUL |
| JVM 三模块（`--max-workers=1` + jni.path，`--rerun-tasks` 强制） | ✅ **core:data 678/0（15 skipped 为既有基线）· feature:game 972/0 · app 1015/0**（合计 2665/0） |
| detekt（data/game/app 三模块） | ✅ 全绿（9 处违规全部实修，零新增 baseline 条目——feature/game baseline 本为空表） |
| 退役符号全仓扫描 | ✅ `SaveSlot`/`saveSlotsFlow`/`getSaveSlots`/`SlotMetadata`/`save_slot_metadata` 代码符号零残留（仅 3 处守卫/版本注释内的合法当前状态引用） |
| 存档语境 slot grep（验收③） | ✅ 见 §4 |
| 本批不涉 ctest / JNI 重建 / 生成器 | 不碰 C++（铁律 16）；无 proto/game-data/catalog 改动 |

data 模块 680→678 对账：−9（SaveSlotTest 退役）+7（SaveInfoTest 新语义）= 678 ✅。

## 4. 验收③ grep 分拣结论（feature/game 生产源码）

- **本地存档语境 slot 命中：清零**（SaveLoadViewModel 全族、SettingsTab、SaveDataTrimmer、AutoSaveOps、LoadDelegate、SlotManageOps、CloudSlotEntryCard——改名/删除/注释去槽化）。
- **云侧槽命名保留（SS7 范围，派工册 §1 SS4 "不做" 明示）**：`TapTapSaveBackend.kt`（mnzm_v2_slot_N 映射）、`TapCloudSaveManager.kt`（3 处 CLOUD_SAVE_SLOT）、`CloudUploadHooks.kt`（UploadQueue 按槽入队）、`SaveLoadViewModelCloudSlotOps.kt`（slot_N 下载链，消费面=冲突收口）、`saveload/CloudSaveCacheWriter.kt`（`downloadIntoCache(sourceSlot, targetSlot)`，任务书点名保留）。
- **伪同形名（非存档语境，铁律 15 精神扩大集合）**：SpiritMineViewModel/SpiritMineDialog（矿脉格）、AlchemyDialog/AlchemyViewModel/ForgeDialog/ForgeViewModel（丹炉/锻造位）、PatrolTowerDialog（巡逻位）、LibraryDialog（书架格，`LibrarySlot` 领域模型）、MissionHallDialog/AttackDiscipleDialog/ScoutDialog/SecretRealmDetailDialog/WorldMapSectDetailDialog/ResidenceDialog/LevelDetailDialog/TianshuHallDialog 等（弟子派遣栏位/探索格）、DetailEquipmentSection/DiscipleDetailScreen（装备部位）、DiscipleSlotComponents（弟子栏位组件）、BuildingDelegate/BuildingFeatureBoot/BuildingsTab（建筑格）、ProductionViewModel 族/ProductionTheme（生产槽，铁律 15 点名）、SectViewModel/GameViewModel/SettingsDelegate（长老位/生产槽 import）。

## 5. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `SaveSlotTest`（core/data，9 例） | **改名 `SaveInfoTest` + 新语义改写（7 例）** | 构造字段/三态派生随 `SaveInfo` 重写；customName/displayName 断言随字段删除退役 |
| `SaveLoadViewModelLoadTest` · 3 例 `saveSlots - …slot 0…` | **改写为 `cloudSaveInfo - …` 3 例** | mergeCloudSlot 合并面删除；断言目标改为 `cloudSaveInfo` 流（被测行为=checkCloudSave/上传后摘要更新，等价迁移） |
| `SaveLoadViewModelLoadTest` · 6 处 `loadGame(SaveSlot(...))` | **改 `loadLocalSave()`** | 读档入口单档化；stub 同步 `getSaveInfoSuspend` |
| `SaveLoadViewModelCloudSlotLoadTest` stub | **改 `getSaveInfoSuspend` 返回空态** | 该测试测云侧 slot_N 链（SS7 范围），本地摘要 stub 仅喂 init |
| `SaveLoadViewModelAutoSaveTest` stub | 同上 | 同上 |
| `AutoEntryResolverTest`（app，9 例→8 例） | **单档签名改写** | `有可读档时损坏档不参与选取`（双档比较）在单档语义下不存在，退役；其余 8 例判定链语义逐条保留 |
| `BuildingOverflowMigrationTest` | **去 delegate 化**（直调 core/engine 原始实现） | `SaveLoadLoadDelegate` 死类退役；断言零变化 |
| `ClearAllSlotTablesCoverageTest` | **改语义不放宽**（阈值随清单收敛） | §2-D |

## 6. 未完成 / 登记

1. **跨批（SS7）**：云侧槽键坍缩（`mnzm_v2_slot_N`/`UploadLedger` 按槽键/`downloadIntoCache` 跨槽/`UploadQueue.enqueue(slot, …)`）+ `StorageConstants.CLOUD_SAVE_SLOT` + `SlotLockManager` 槽锁命名——全部按约保留至 SS7；`CloudSlotOps`/`CloudSaveCacheWriter` 两文件的注释历史表述（SR-3/SR-6 批注）随 SS7 整链重构一并清。
2. **跨批（SS6）**：`shouldAutoSave(flagOn, hasActiveSlot, engineLoaded)`（core/data `SaveTriggerFlag.kt:59`）参数名含 slot——core/data 签名面不在本批切片授权，登记 SS6（事件触发自动存档）顺手收口。
3. **跨批（SS5）**：`StorageEngine.kt`/`StorageEngineSaveSupport.kt` 的 `clearCacheForSlot` 函数名含 Slot（缓存清理语义，非存档概念渲染面）——写路径命名随 SS5 增量落盘一并收口。
4. **跨批（SS10）**：玩家可见变更（存档管理弹窗单档化：无选档步骤、保存/读取直动作、云卡摘要）并入 4.2.00 唯一条目；本批**未动** `changelog_entries.json`/`CHANGELOG.md`（V4 义务归 SS10）。
5. **行为修复（已核实）**：AutoEntry 云伪槽误判（§2-B 末）——本地无档时自动进入现在正确发起云端存在性查询（此前云占位卡 `isEmpty=false` 使判定恒 `LoadLocal`，云端兜底永不触发）。
6. **顺手实修（SS3 遗留）**：`StorageDiagnosticsViewModel`/`StorageDiagnosticsDialog` 两处 detekt 违规（§2-D 末），已核实非本批引起。
7. **保留裁决**：`pendingAction` 状态流生产 UI 零读者（仅测试消费）——不含 slot 语义、不越本批边界，保留并在此登记；如需清理归后续 UX 面批次。
8. **版本**：`DATABASE_VERSION` 69→70 例行递增（D-5）；`version.properties` 未动（4.2.00/4200，V1 拍板不变）。

## 7. 风险

| # | 风险 | 定性 |
|---|---|---|
| R1 | 版本 69→70 升级触发老库 destructive 重建——与 SS0–SS2 同口径，启动前快照（`snapshotDatabaseBeforeUpgrade`）为唯一抢救副本 | 已核实（口径与 D-5/任务书一致） |
| R2 | `StorageFacade.getSaveInfoSuspend` 异常收敛 `isLoadError=true` 比原先"空列表"更保守——异常时读取按钮禁用而非显示空档 | 已核实（红线方向：宁可拒绝"新建覆盖"入口） |
| R3 | `readMetadata` 直透投影不再补 `discipleCount`——唯一消费者 `StorageFacade.initialize` 仅读 `sectName` 打日志 | 已核实（消费面窄化无损） |
| R4 | 云上传入口从"弹窗选云槽+保存"改为既有 `CloudSaveDialog` 路由承载——`uploadToCloudSave` 及其 `cloudSaveInfo` 更新链保留，LEGACY 短路语义零变化（守卫测试在 `SaveLoadViewModelLoadTest` LEGACY 段） | 已核实（入口迁移而非退役） |
| R5 | `ClearAllSlotTablesCoverageTest` 阈值 20 与实际 24 之间若未来新增表解析失配，兜底余量充足；双向相等断言为主判据 | 已核实 |
| R6 | 存档弹窗"读取失败"态文案引导走云端备份——若损坏档无云备份，玩家需走设置页"清档重置"或 boot 失败逃生口 | 推测（真实设备损坏路径未实测，语义与 AutoEntry 损坏档处置链一致） |
