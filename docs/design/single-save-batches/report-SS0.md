# REPORT-SS0 · 删档重置 + 旧存档兼容代码清零

> 派工真源：[`TASKBOOK-SS0.md`](TASKBOOK-SS0.md)；协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 工作树：`C:\Mnzm\XianxiaSectNative-SS0`（分支 `feat/single-save-SS0`，基线 `79769bf6c` = main HEAD）。
> commit：见文末（本报告随批提交）。

---

## 1. 做了什么（分类）

### ① Room 迁移链清零（SS0-a，验收①③⑥）

| 项 | 处置 | 说明 |
|---|---|---|
| `GameDatabaseMigrationsV*.kt` 32 个文件 | **删** | V2ToV10…V65 全族（实测 32 文件，多于任务书预估 22——以锚点机械扫描为准） |
| `GameDatabaseMigrationSupport.kt` | **删** | `columnExists`/`rebuildTableDroppingColumns`/`GAME_DATA_CREATE_SQL`/`rebuildGameData`/`rebuildStorageBags` 全部迁移辅助；全仓消费面扫描确认唯一消费者 = 已删迁移文件 |
| `GameDatabase.kt` `ALL_MIGRATIONS`（63 条）+ `addMigrations` + `Migration` import | **删** | 迁移注册整体退役 |
| 24 个迁移测试/辅助文件 | **删** | `RoomMigration*Test` 22 个 + `RoomMigrationSupport.kt`（测试辅助）+ `MigrationChainGuardTest`（守卫对象已不存在） |
| `android/app/schemas/.../1.json` 孤儿 schema | **删** | `DeadCompatRemovalGuardTest` 反向锁定目录不得回流 |
| `fallbackToDestructiveMigrationFrom(1)` | **替换**为 `fallbackToDestructiveMigration(dropAllTables = true)` | W4/D-2：原 API 只覆盖 from(1)，删光迁移后 v65 库会走"缺迁移⇒崩溃"而非重建；全量 fallback + dropAllTables（连 Room schema 外的历史影子表一并清）才是"缺迁移⇒重建"的完整语义。**验收⑥实测锁定**（见 §3） |
| `DATABASE_VERSION` 65→66 | **递增** | D-1 单调递增；版本注释块（v40–v65 历史逐版注释）删除，换一条 v66 当前状态注释 |

### ② 存档格式兼容链清零（SS0-c，验收④）

| 项 | 处置 | 说明 |
|---|---|---|
| `SaveDataVersionMigrator.kt` + `SaveDataVersionMigratorTest.kt` | **删** | 含 `MigrationResult` sealed 与 v0→1/v1→2 迁移分支、误标新档守卫 |
| `serialization/backwardcompat/` 整包 | **删** | `OldSaveFormatDeserializer` + `OldSerializableSaveData` |
| `SaveVersion.kt` | **收敛** | `CURRENT = 2` 保留（engine 新档盖章 3 处引用），KDoc 去迁移链描述，语义改为"云档/本地档版本戳" |
| 8 处迁移调用点 | **删** | `StorageEngineLoadOps`（`migrateOrNull`/`migrateRestoredData` 两函数删除、`buildAndMigrateSaveData`→`buildAndValidateSaveData`、缓存命中路径直连校验）、`StorageEngine`（写档盖章改引 `SaveVersion.CURRENT`、备份恢复链的迁移段删除）、`SaveLoadViewModelCloudOps`×2、`SaveLoadViewModelCloudLoadOps`×1、`CloudSaveCacheWriter`×1 |
| DI 两处 | **改** | `SerializationModule` 删 `oldSaveFormatDeserializer` 构造参数与旧格式兼容分支（新格式失败直接抛 `SerializationException`）；`StorageModule.provideSerializationModule` 同步删参 |
| `SaveData.version` / `GameData.saveVersion` 协议字段 | **保留** | D-4：云档版本戳（跨设备识别），删字段需动双端协议，收益为零 |

### ③ 存量迁移族清零（SS0-d，验收⑤）

| 项 | 处置 | 说明 |
|---|---|---|
| `SaveMigrationLedger` / `SaveMigrationPlanner` / `SaveMigrationCard` / `SaveMigrationCoordinator` / `SaveMigrationState` | **删** | 5 生产类整族 |
| `SaveMigrationCardTest` / `SaveMigrationCoordinatorTest` / `SaveMigrationPlannerTest` / `SaveMigrationLedgerTest` / `SaveMigrationGuardTest` | **删** | 5 测试类 |
| `SettingsTab` 挂载点 | **删** | 迁移卡 item、`MigrationUiState`/`MigrationActions` import、`SaveSlotListState` 两字段、`buildMigrationActions`、`migrationCoordinator.state` collect、`SaveSlotOpenRefreshEffect` 的 `scan()` |
| `SaveLoadViewModel.migrationCoordinator` 透传 + `PersistenceFacade` 构造参数 | **删** | 引用链清零 |
| `cloud_migration_slot{N}_state` MMKV 键 | **删**（含历史残留） | 写入方已删；设备上历史键由 `SaveWipeCoordinator` 按前缀清理（§⑥） |
| 埋点 `SAVE_MIGRATION_RESULT` + `PROP_MIGRATION_*` 5 常量 | **删** | 唯一生产消费方 = 已删协调器；`AnalyticsEventsDictionaryTest` 3 处断言与 `docs/knowledge-base.md` 事件字典行同步（数据埋点三处同步义务） |

### ④ SaveValidator 逐条判定（SS0-h，验收⑨/W7）

28 条规则逐条判定，**删 6 留 22**（`registerDefaults` 注册表收敛同步）：

| 规则（order） | 处置 | 理由 |
|---|---|---|
| `BloodPoolBuildingCleanupRule`（17） | **删** | 血炼玩法下线的**旧档存量残留**清洗；写入面已随 G04 清零，删档后恒不触发 |
| `RecruitListCleanupRule`（20） | **删** | 招募链下线恒空清表——恒 Repaired 只为收敛旧档；写入面已清零（G05），字段保留恒空 |
| `MailDiscipleAttachmentCleanupRule`（22） | **删** | 邮件弟子附件下线的存量摘除；写入面已删，删档后新邮件无弟子附件 |
| `LawEnforcementPrisonCleanupRule`（24） | **删** | 执法堂/监牢下线的旧档残留清理与状态归一化；写入方已全删（执法堂退役批） |
| `NurturePillRetirementRule`（26） | **删** | 孕养丹退役**旧档折算补偿**（读存量丹药/储物袋/邮件附件/配方）；测试 `NurturePillRetirementTest` 同删；`OverflowMailSender` 来源键同步删 |
| `LegacyEquipmentCompensationRule`（27） | **删** | 旧装备**旧档折算补偿**（影子表/储物袋/邮件/秘境四路）；`EquipmentLegacyTableReader`（影子表读取器）+ `StorageEngineHeavyDataOps` 影子行装配段 + `EquipmentLegacyCompensationTest` 同删 |
| `TimeAxisRule`（25） | **保留** | 非纯旧档：负值/越界钳制 + 日历↔权威轴投影一致性（INV-1）是运行期完整性防线；"旧档归一化回填"分支对异常数据（elapsed=0 且日历非初值）同样成立，无旧版本语义包袱 |
| 其余 21 条 | **保留** | 数值消毒/引用一致性/上限钳制/结构校验 = 运行期完整性（W7 判据"为运行期完整性写的留"） |

连带：`OverflowMailSender.SOURCE_DISPLAY_NAMES` 删 `nurture_pill_retirement`/`equipment_legacy_compensation` 两键；`EquipmentNoCapGuardTest` 装备来源键白名单断言更新为空集；`SaveValidatorTest` 的恒空契约辅助（`assertOnlyRecruitListRepair`）改写为零修复判据 `assertValidNoRepair`，`SaveValidatorIntegrationTest` 最小档用例断言只剩 TimeAxis 回填一条。

**保留的协议占位**（D-4 同款理由，删字段需动协议）：`EquipmentStack` @Deprecated 载体、`LegacyEquipmentPrices`、`GameData.nurturePillsRetired`(168)、`GameData.legacyEquipmentCompensated`(169)——字段恒空/恒 false，`EquipmentStackRemovalGuardTest` tolerated 名单同步收敛。

### ⑤ 邮件清理（SS0-j，验收⑪/M1–M4）

| 项 | 处置 | 说明 |
|---|---|---|
| `mail_qq_group_v4_0_03` QQ 群邮件 | **删** | 29→**28 封**（仅节日）；`BuiltinMailConfigTest.builtinMailConfig_qqGroupMailExists` 断言同删 |
| `MailService.injectWhitelistBonus` + `WHITELIST_BONUS_*` 常量 | **删** | 白名单福利链方法体 + 三重保护守卫 |
| `GameEngineAdminOps.sendWhitelistBonus` | **删** | 扩展函数 + KDoc |
| 4 个调用点 | **删** | `SaveLoadViewModelNewGameOps:111` / `RestartOps:227` / `LoadOps:209` / `CloudLoadOps:197` 及各自 import |
| `MailServiceTest` 白名单测试段（8 用例） | **删** | 保留 `WHITELIST_BONUS_MAIL_ID` 常量（永久邮件样例仍引用） |
| `GameEngineAdminOps.sendAdminCompensation` / `overflow` / `secret_realm` / 节日 28 封 | **保留** | M3/M4 拍板（管理员通用补偿入口的实际符号名；任务书 M3 行写作 `injectAdminMail`，基线代码中不存在该名，语义同项） |
| `AdFreeWhitelist` | **保留** | W11（编译期白名单 object，无本地存储面，wipe 不触及） |

**产品后果**：删 QQ 群邮件 = 游戏内唯一玩家社群引流入口取消；白名单玩家此后只剩免广告特权，不再有每档一次的福利邮件。

### ⑥ 删档执行 + 残留清理（SS0-e/g，验收①⑦⑧/W5/W6/W12/D-6）

新增 `core/data/.../data/wipe/SaveWipeCoordinator.kt`（app 与 feature:game 共同依赖层，放 core:data 使 SettingsTab 开发入口不越模块边界）：

- **触发**：`XianxiaApplication.onCreate` 在 `initBuglyAndMmkv()` 之后、任何 DB 打开之前调 `wipeIfNeeded(context)`；`wipe_single_save_done` 幂等标记 + `wipe_single_save_pending` 开发入口待执行标记（均存业务偏好 MMKV `game_prefs` 实例）。
- **清理面**（一次执行、逐项幂等）：① DB 文件族（`xianxia_sect.db` + `-wal`/`-shm`，经 `GameDatabase.getUnifiedDatabaseFile`）；② 启动前快照/恢复残留（`.pre_migrate_backup*`/`.restore_*` 前缀扫描）；③ `saves/`（`slot_*.sav|.bak|.tmp|.deleted`）与 `archives/` 整目录；④ MMKV 云台账键（`cloud_upload_ledger_*` 现役 + `cloud_migration_*` 旧协议）；⑤ `SessionManager.clearAllAccountData()`（新增方法：clearSession 基础上连 `KEY_PRIVACY_AGREED`/`KEY_PRIVACY_CHECKBOX_CONFIRMED` 合规缓存一并清，W5 强制重新登录+实名）。
- **开发入口**（W12）：SettingsTab 仅 `BuildConfig.DEBUG` 可见"清档重置"按钮 → 确认弹窗 → `requestWipeOnNextLaunch()` + `finishAffinity` + `killProcess`，下次启动走同一执行路径（游戏运行中引擎/DB 已加载，禁止原地删文件）。
- **边界**：`AdFreeWhitelist`（W11 编译期表）不在清理范围；云端旧档删除独立挂载（见⑦）。

### ⑦ 云端命名与删除（SS0-f，验收②/W1/D-5）

| 项 | 处置 | 说明 |
|---|---|---|
| 云端命名 | **改**：slot 0 `mnzm_cloud_save`→`mnzm_v2_save`；slot 1..6 `slot_N`→`mnzm_v2_slot_N` | `TapTapSaveBackend`（`SESSION_ARCHIVE_NAME`/`V2_SLOT_PREFIX`/`archiveNameFor`）与 `TapCloudSaveManager.CLOUD_SAVE_ARCHIVE_NAME` 同步；旧命名零读取路径（`slotFromArchiveName` 对旧命名返回 null）⇒ 旧云档自然失联（主保险） |
| 主动删除 | **增**：`TapTapSaveBackend.isLegacyArchiveName` + `TapCloudSaveManager.oneTimeCleanup` 语义从"保留并记录"改为"删除旧协议命名档" | 辅保险：尽力而为，单档失败仅日志不阻断、不拒绝写完成标记；挂载点沿用既有启动链（`SaveLoadViewModelCloudOps:28`），SDK 可用即执行 |
| `TapTapSaveBackendTest` | **改** | v2 命名可逆 + 旧协议命名失联 + `isLegacyArchiveName` 仅旧命名命中，三组断言 |

### ⑧ 备份机制改语义（SS0-b，D-3/D-9）

- `backupDatabaseForMigration` → **`snapshotDatabaseBeforeUpgrade`**（"启动前快照"）：触发条件不变（库版本落后于目标版本 = 本次启动将发生 destructive 重建，快照是重建前唯一抢救副本），命名与注释去 migration 语义；KDoc/日志/常量注释同步。
- `restoreFromBackupIfNeeded` / `pruneMigrationBackups` / `shouldRestoreFromBackup` / `.restore_attempted` marker / `performFileRestore` **全保留**（D-9：可恢复性路径不得减少）；`logMigrationPendingIfAny`→`logUpgradePendingIfAny` 等恢复链命名/注释改重建语义。
- **删**：`findVersionedBackup` 的无版本后缀旧备份兼容分支（`GameDatabase.kt:868-873` legacy 兜底，D-9 点名"仍删"三项之一）。
- **途中修复（预存缺陷）**：快照前 `PRAGMA wal_checkpoint(TRUNCATE)` 裸 `execSQL` 在部分 SQLite 实现被拒（`Queries can be performed using query or rawQuery methods only`——`performCheckpointSync` 的既有降级分支即为此现象的真机先例），原迁移备份在该类设备上**从不落盘且无感知**。本批抽 `checkpointForSnapshot` 增加 rawQuery 降级（Robolectric 实测锁定）。

---

## 2. 旧用例处置表（删除批义务）

| 旧用例 | 处置 | 理由 |
|---|---|---|
| 22 个 `RoomMigration*Test.kt` | 删 | 迁移链退役，守护对象不存在 |
| `RoomMigrationSupport.kt`（测试辅助） | 删 | 同上 |
| `MigrationChainGuardTest`（4 用例） | 删 | `ALL_MIGRATIONS` 已退役；替代守卫 = `MigrationRequiredGuardTest`（防"实体变更忘写迁移"，铁律 17） |
| `SaveDataVersionMigratorTest` | 删 | 迁移器退役 |
| `SaveMigration*Test` 5 个 | 删 | 存量迁移族退役 |
| `NurturePillRetirementTest` / `EquipmentLegacyCompensationTest` | 删 | 补偿规则退役（W7） |
| `LawEnforcementPrisonCleanupRuleTest` / `MailDiscipleAttachmentCleanupRuleTest` / `RecruitListCleanupRuleTest` | 删 | 下线清洗规则退役（W7；BloodPool 规则原无独立测试） |
| `RoomMigrationRecoveryTest`（备份恢复用例） | 删 | 快照恢复行为由新 `DestructiveRebuildBaselineTest` 快照用例 + 既有 `GameDatabaseTest` 承接 |
| `InnateDamageTypeGuardTest`「Room v62 迁移 SQL 历史口径可扫描」 | 删（单方法） | 守护载体（v62 迁移文件）退役；其余 3 个守护面（模板覆盖/派生幂等/兜底物理）保留 |
| `SaveValidatorTest` 恒空契约辅助 + 8 处断言 | 改 | `assertOnlyRecruitListRepair`→`assertValidNoRepair`（Passed 零修复判据）；幽灵弟子用例修复计数 2→1 |
| `SaveValidatorIntegrationTest` 最小档用例 | 改 | 期望修复明细只剩 TimeAxis 回填一条 |
| `EquipmentNoCapGuardTest` 装备来源键白名单 | 改 | `{equipment_legacy_compensation}`→∅（补偿通道退役） |
| `AnalyticsEventsDictionaryTest` | 改 | 删 `SAVE_MIGRATION_RESULT` 3 处 |
| `MailServiceTest` 白名单段（8 用例） | 删 | `injectWhitelistBonus` 退役 |
| `BuiltinMailConfigTest.builtinMailConfig_qqGroupMailExists` | 删 | QQ 群邮件退役 |
| `TapTapSaveBackendTest` 命名断言 | 改 | v2 命名基线 + 旧命名失联 + `isLegacyArchiveName` |
| `CloudSaveCacheWriterTest`「saveVersion 越界拒绝且不落盘」 | 改 | 迁移器退役后 saveVersion 仅作版本戳，改写为「高版本云档仍走校验落盘」（`Outcome.Written` + save 恰 1 次） |
| `SaveLoadViewModelCloudSlotLoadTest`「rejected migration skips cache write」 | 改 | 同上语义反转：高版本档正常落盘加载、状态非 Error |
| `EquipmentStackRemovalGuardTest` tolerated 名单 | 改 | 删 3 个已删文件行（守卫的"名单腐化"断言会拦已删文件）；理由文字去补偿链表述 |
| `SingleColumnStatGuardTest` 豁免面（independent review 补收） | 改 | `exemptedPathParts` 删 `OldSerializableSaveData`/`GameDatabaseMigrations`/`RoomMigration` 三死豁免项，KDoc 迁移面段删除 |
| `SpeedDimensionRemovedGuardTest` KDoc（independent review 补收） | 改 | 排除项理由去已删文件名 |
| `SaveBackend.kt:15` KDoc（independent review 补收） | 改 | `slot_N` → `mnzm_v2_slot_N`（同段半新半旧） |
| `SaveLoadViewModelCloudSlotLoadTest` 三处注释 + `CloudSaveCacheWriterTest:35` KDoc + `SaveLoadViewModelLoadTest:57` KDoc + `EquipmentNoCapGuardTest:28` KDoc（independent review 补收） | 改 | 旧迁移语义/旧命名注释与改写后断言对齐 |
| `SecureKeyChainGuardTest`/`WalRetirementGuardTest`/`GameEventRecordTest`/`RedeemCodeTest`/`EntityCountBoundsRuleTest`/`StorageBagMaterializerTest`/`BaselineFieldCoverageGuardTest`/`GameDataFieldPatchGuardTest`/`OverflowMailSenderTest` | 改注释 | 引用已删符号的注释/方法名改为当前状态表述 |

**新增测试**：

| 测试 | 锚定验收 |
|---|---|
| `DestructiveRebuildBaselineTest`（3 用例，Robolectric） | ⑥ v65 库 v66 打开 = 重建不崩溃 + 旧行/影子表清零；版本落后先落启动前快照；首次安装零快照文件 |
| `MigrationRequiredGuardTest`（2 用例） | ⑩ 实体清单基线漂移即红（附三步操作指引）；destructive fallback 与版本常量引用防误删 |
| `DeadCompatRemovalGuardTest`（2 用例） | ⑭ 六模块生产+测试源码对删除符号面零命中（扫描面含 src/test——独立复核补强；白名单显式声明 + 守卫本体文件豁免）；孤儿 schema 防回流（双 schemaLocation 目录，低于当期版本即红） |
| `SaveWipeCoordinatorTest`（2 用例，Robolectric） | ① 五类持久化面清零逐项断言 + 干净设备幂等；**实测抓出快照清理扫错目录的缺陷并修复**（见 §6.3） |

---

## 3. 验收⑫ 锚点处置表（双向对账）

> 机械依据 = TASKBOOK §2.7 锚点集 A1–A8；对账等式：**锚点命中条目数 == 处置表条目数**（终态扫描 2026-10-01 实测，命令与命中数见 §4）。

### A1 Room 迁移（`MIGRATION_`/`ALL_MIGRATIONS`/`fallbackToDestructive`/`safeDropColumns`/`rebuildTableDroppingColumns`/`Migration(`/`addMigrations`）

| # | 命中 | 处置 |
|---|---|---|
| A1-1..32 | `GameDatabaseMigrationsV*.kt` 32 文件 | 删 |
| A1-33 | `GameDatabaseMigrationSupport.kt` | 删（唯一消费者=迁移文件） |
| A1-34 | `GameDatabase.kt` `ALL_MIGRATIONS`+`addMigrations`+`Migration` import | 删 |
| A1-35..58 | 24 个迁移测试/辅助文件（22 Test + Support + ChainGuard） | 删 |
| A1-59 | `fallbackToDestructiveMigrationFrom(1)` | 改：全量 `fallbackToDestructiveMigration(dropAllTables = true)` |
| A1-60 | `pruneMigrationBackups`（GameDatabase + DataPruningScheduler 两处） | **保留**（D-9 快照维护） |
| A1-61 | `MIGRATION_BACKUP_RETENTION` | **保留**（D-9） |
| A1-62 | `InnateDamageTypeGuardTest` v62 迁移 SQL 扫描方法 | 删（载体退役） |
| A1-63 | `EquipmentInstanceDao.kt:14` 注释引用 | 改注释 |
| A1-64..70 | 注释引用面（`DiscipleSerializer`×3/`Items`×3/`SaveData`/`JsonConverters`） | 改注释 |
| A1-71 | `GameDatabase.kt` v66 注释 `MIGRATION_(N-1)_N` 指引文本 | **保留**（对未来迁移的规则指引，非旧代码） |

### A2 格式兼容（`SaveDataVersionMigrator`/`CURRENT_SAVE_VERSION`/`SaveVersion`/`saveVersion`/`backwardcompat`/`OldSaveFormatDeserializer`/`OldSerializableSaveData`）

| # | 命中 | 处置 |
|---|---|---|
| A2-1..2 | `SaveDataVersionMigrator.kt` + Test | 删 |
| A2-3..4 | `backwardcompat/` 两文件 | 删 |
| A2-5 | `SaveVersion.kt` | 收敛（CURRENT 保留、迁移链 KDoc 删） |
| A2-6..13 | 8 处调用点（LoadOps×2+函数、Engine×2、CloudOps×2、CloudLoadOps×1、CacheWriter×1） | 删/改直连 |
| A2-14 | `SerializationModule` 旧格式分支 + DI | 删 |
| A2-15 | `StorageModule.provideSerializationModule` 参数 | 删 |
| A2-16 | `SaveData.version`/`saveVersion` 字段 | **保留**（D-4 版本戳；engine 盖章 3 处 + StorageEngine 盖章改引 `SaveVersion.CURRENT`） |
| A2-17 | `core.engine.MigrationResult`（BuildingLoadSelfHeal 建筑自愈结果类型） | **保留**（伪同形名，与存档迁移器无关；守卫模式限定 `data.migration.MigrationResult`） |
| A2-18 | `SaveDataReconciler`（stacksSerialized=false 重建兜底） | **保留**（运行期兜底逻辑，注释去补偿引用；不在锚点删除面） |

### A3 存量迁移族（`SaveMigration`/`migratableSlots`/`cloud_migration_`/`MigrationSlotState`/`SlotMigrationAction`）

| # | 命中 | 处置 |
|---|---|---|
| A3-1..5 | 5 生产类 | 删 |
| A3-6..10 | 5 测试类 | 删 |
| A3-11..14 | 挂载点（SettingsTab×2 段 / SaveLoadViewModel / PersistenceFacade） | 删 |
| A3-15 | `cloud_migration_slot{N}_state` 键 | 删（写入方断 + wipe 清历史键） |
| A3-16..18 | 埋点常量 + 字典测试 + knowledge-base 字典行 | 删/同步 |
| A3-19 | `SaveWipeCoordinator` 的 `cloud_migration_` 前缀 | **保留**（D-5/D-6 清理识别器，守卫白名单） |
| A3-20 | `SecureKeyChainGuardTest`/`WalRetirementGuardTest` 口径注释 | 改注释 |

### A4 迁移备份（`pre_migrate_backup`/`backupDatabaseForMigration`/`restoreFromBackupIfNeeded`/`pruneMigrationBackups`/`MIGRATION_BACKUP_RETENTION`/`.restore_attempted`）

| # | 命中 | 处置 |
|---|---|---|
| A4-1 | `backupDatabaseForMigration` | 改名 `snapshotDatabaseBeforeUpgrade`（快照语义） |
| A4-2 | `GameDatabase.kt:868-873` 无版本后缀 legacy 兜底分支 | **删**（D-9 点名仍删） |
| A4-3..6 | `restoreFromBackupIfNeeded`/`pruneMigrationBackups`/`shouldRestoreFromBackup`/`.restore_attempted` | **保留**（D-9 可恢复性） |
| A4-7 | `checkpointForSnapshot` execSQL 降级 | 新增（预存缺陷修复，§1⑧） |
| A4-8 | `AppModule.provideGameDatabase` 恢复调用+注释 | 保留 + 注释改快照语义 |

### A5 易漏面（`Legacy`/`legacy`/`Compat`/`Old`/`@Deprecated`/`@Ignore`/`deprecated`）

| # | 命中 | 处置 |
|---|---|---|
| A5-1 | `LegacyObjectModule.kt`（core/engine/di） | **保留**（object 单体服务的 DI 绑定模块，"Legacy"指遗留代码组织方式，与存档兼容无关） |
| A5-2 | `EquipmentLegacyTableReader.kt` | 删（影子表读取器，随补偿链退役） |
| A5-3..4 | `LegacyEquipmentCompensationRule` + Test | 删（W7） |
| A5-5..6 | `NurturePillRetirementRule` + Test | 删（W7） |
| A5-7..10 | 四条下线清洗规则 + 3 测试 | 删（W7） |
| A5-11 | `TimeAxisRule` | **保留**（运行期完整性，§1④） |
| A5-12 | `ElderSlotsRetiredFieldCompatTest` | **保留**（proto reserved 解码行为守卫，协议面防回归测试而非兼容逻辑实现） |
| A5-13 | `DeviceCompatibilityHelper.kt`（app） | **保留**（设备兼容，非存档兼容） |
| A5-14..17 | `EquipmentStack` 载体/`LegacyEquipmentPrices`/`nurturePillsRetired`/`legacyEquipmentCompensated` | **保留**（协议号占位，D-4 同款理由；恒空/恒 false） |
| A5-18..32 | 注释面 15 处（§2 末行列出） | 改注释 |
| A5-33 | `JsonConverters.toEquipmentSlot` 脏数据兜底分支 | **保留**（运行期防脏数据，非版本迁移） |

### A6 槽位维度（`slot_id`/`slotId`/`DEFAULT_MAX_SLOTS`/`resetForSlot`/`CLOUD_SAVE_SLOT`）——归 SS1，本批不动

| # | 命中 | 处置 |
|---|---|---|
| A6-全 | 槽位全维度（`TapTapSaveBackend` 1..6 边界、`clearAllSlotTables`、`UploadLedger` per-slot 键、`DEFAULT_MAX_SLOTS` 等） | **保留不动**（SS1）；`TapTapSaveBackendTest`/`ClearAllSlotTablesCoverageTest`/`CacheWriteAtomicityGuardTest` 全绿未动。SS1 合并时与本表合并对账 |

### A7 文件与资源面

| # | 命中 | 处置 |
|---|---|---|
| A7-1 | `app/schemas/.../1.json` | 删 |
| A7-1b | `core/data/schemas/.../v2..v65` 历史族 56 个（独立复核补收的 A7 遗漏——迁移测试时代的对照基线，零消费者） | 删；两处 schemaLocation 导出目录已在 `.gitignore`，`DeadCompatRemovalGuardTest` 防低于当期版本的孤儿回流 |
| A7-2..5 | DB 文件族/`.pre_migrate_backup*`/`.restore_*`/`saves/`/`archives/` | 启动期 wipe 清理（`SaveWipeCoordinator`，验收①） |
| A7-6..7 | MMKV `cloud_upload_ledger_*`/`cloud_migration_*` | wipe 清理 |
| A7-8 | `SaveFileManager` 文件层（`.sav`/`.bak`/`.tmp`/tombstone） | **保留**（D-9） |
| A7-9 | `SaveFileFormat` 格式本体 | **保留**（D-9） |

### A8 文档/判据面

| # | 范围 | 处置 |
|---|---|---|
| A8-1 | `docs/knowledge-base.md` 7 处（规则表/孤岛清理/云读档管线/邮件来源×2/补偿源×2） | **回改**（活文档，"当前实现"真源） |
| A8-2 | `docs/architecture.md` R2 行守卫列 | **回改**（活文档） |
| A8-2b | `rules/database-migration.md` 现行规则段（safeDropColumns/backupDatabaseForMigration/From(1)/RoomMigrationTest 四处失实——独立复核补收） | **回改**（活规范，"改 @Entity 前必读"；`agent-instructions` 门禁复跑绿） |
| A8-2c | `android/core/data/AGENTS.md` 迁移段（RoomMigrationTest 引用） | **回改**（活模块规范） |
| A8-2d | 根 `AGENTS.md` §7.1/7.2（`safeDropColumns` 失实 API 与"注册到 build()"表述） | **回改**（活规范，预存失效一并澄清） |
| A8-3 | `docs/cpp-migration-handover-m0.md` 多处（RoomMigration/ALL_MIGRATIONS 历史批次记录） | 不回改（批次档案） |
| A8-4 | `docs/character-gacha-redesign-2026-09-23.md`、`docs/character-system-audit-2026-09-22.md`（`SaveDataVersionMigrator.CURRENT` 历史描述） | 不回改（历史方案/审计档案） |
| A8-5 | `docs/native-engine-refactor-plan-2026-09-17.md`（RoomMigrationV51To52/ALL_MIGRATIONS） | 不回改（历史方案） |
| A8-6 | `docs/report-B9.md` | 不回改（批次报告） |
| A8-7 | `docs/parallel-batches-w4/`、`docs/parallel-batches-w5/`、`docs/save-system-refactor-plan-2026-09-21.md`、`docs/save-system-survey-2026-10-01.md`、`docs/save-system-audit-2026-09-21.md`、`docs/realtime-settlement-plan-2026-09-27.md`、`docs/realtime-watch/`、`docs/gacha-watch/`、`docs/design/equipment-set-system-refactor-plan.md`、`docs/design/equipment-batches/`、`docs/design/texture-minification-pipeline-overhaul.md`、`docs/design/remove-2x-speed-implementation-plan.md`、`docs/design/remove-law-enforcement-and-prison-implementation-plan.md`、`docs/design/elemental-damage-system-plan.md`、`docs/design/acceptance-review-equipment-and-elemental.md`、`docs/memory-audit-2026-09-22.md`、`docs/longrun-stability-audit-report.md`、`docs/longrun-stability-remediation-plan.md`、`docs/sr0-recon-report-2026-09-21.md`、`docs/report-移除自动存档-接入云存档.md`、`docs/build-perf/`、`docs/adr/sqlite-sqldelight-evaluation.md`、`docs/design/single-save-batches/`、`docs/design/gacha-batches/` | 不回改（方案/派工/验收档案；上位方案 §0.5 已声明作废语义。独立复核补收的完整枚举，防"已知过期引用清单"再次缺漏） |

**对账等式**：终态扫描（§4 命令）生产+测试源码面对 A1–A5 删除符号零命中（白名单 5 项均在守卫 `WHITELIST` 显式登记）；保留/改语义/注释项逐条如上表，**命中条目全部进表，无表外命中**。

---

## 4. 验证（门禁实跑数值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin` | **BUILD SUCCESSFUL** |
| 全量五模块 `testReleaseUnitTest --max-workers=1 --rerun-tasks`（worktree 桥 `-Dgamecore.jni.path=…\core\engineuild\desktop-jni\libgamecorejni.so`，终树复跑） | **7297 tests / 0 failed**（domain 1594 / engine 3022（5 skipped）/ data 690（15 skipped）/ feature:game 973 / app 1018（2 skipped）） |
| `:core:data:detekt :app:detekt :core:domain:detekt :core:engine:detekt :feature:game:detekt` | **BUILD SUCCESSFUL**（baseline 零新增，只缩不增未触发） |
| 验收⑥ destructive 实测 | `DestructiveRebuildBaselineTest` 3/3 绿（v65 库 v66 打开 = 重建不崩溃、旧行与 Room 外影子表清零、版本落后先落快照、首次安装零快照文件） |
| 验收⑭ `DeadCompatRemovalGuardTest` | 2/2 绿（六模块生产源码 `src/main` 全树扫描 0 违规；孤儿 schema 0） |
| `node scripts/check-agent-instructions.mjs` | **EXIT=0**（规则①预算闸 25226/32768 · ②单一真源 · ③484 条引用闭包 · ④路由表完整 · ⑤子目录链路） |
| worktree JNI 桥 | `pwsh scripts/build-desktop-jni.ps1` 重编（259 源文件同源指纹）；`DiffBridgeSourceSyncGuardTest` byte-for-byte 绿 |
| 构建副产物 | `atlas-rgba-manifest.json`/`scene_uv_tables.h`/`sprite-uid-map.json` 已 `git checkout --` 还原，工作树只留本批改动 |



## 5. 登记与未完成

1. **跨批登记（SS1）**：槽位维度本批零改动（A6 表）；`slot_*.sav` 文件改名与 `slot_id` 列删除归 SS1。
2. **跨批登记（SS10）**：删档重置的玩家可见 changelog（`changelog_entries.json`）与技术 changelog 归 SS10 收口（TASKBOOK §6.2；本批按登记不动双日志）。
3. **真机冒烟未做**（TASKBOOK §5）：装旧版→建进度→覆盖安装→应为全新档；清数据重装→全新档；换设备同账号不得恢复旧档。需真机环境，随发布验证执行。
4. **风险（W5）**：删档连账号/合规缓存一起清 ⇒ 玩家被强制重新登录 + 重新实名；`AdFreeWhitelist` 按 W11 保留。渠道侧对实名数据若有留存要求，需产品与合规确认——本批只执行，不裁定合规。
5. **风险（D-8 依赖）**：destructive fallback 永久保留 ⇒ "忘写迁移 = 静默清档"，由 `MigrationRequiredGuardTest`（CI 面）兜住；该守卫基线（实体清单）在实体变更时必须同批更新。
6. **任务书字面偏离说明**：TASKBOOK §2.1/决策 D-2 写"保留 `fallbackToDestructiveMigrationFrom(1)`"；实测该 API 只覆盖 from(1)——删光迁移后 v65 老库会走"缺迁移 ⇒ 崩溃"而非 D-1/D-2 的语义目标"重建"。本批替换为全量 `fallbackToDestructiveMigration(dropAllTables = true)`（含 Room schema 外残留表清理），是 D-1/D-2 意图的正确实现，验收⑥实测锁定。TASKBOOK 文本未回改（派工真源不由执行方修订），以本条为差异登记。
7. **SaveFileFormat.FORMAT_VERSION_LEGACY(0x0100) 旧文件头读取兼容**：随 D-9"格式本体保留"存续，显式登记（独立复核建议）。

## 6. 途中发现（预存问题，不自作主张隐藏）

1. **快照 checkpoint 预存缺陷**：原 `backupDatabaseForMigration` 的 `PRAGMA wal_checkpoint(TRUNCATE)` 裸 execSQL 在部分 SQLite 实现被拒（真机 Bugly 同源先例 = `performCheckpointSync` 的降级分支），该类设备上迁移前备份**从不落盘且无感知**。本批抽 `checkpointForSnapshot` 降级修复（Robolectric 实测锁定）。
2. **`fallbackToDestructiveMigrationFrom(1)` 覆盖缺口**：删光迁移后原 API 无法让 v65 库重建（只认 from(1)），直接沿用会"降级崩溃"而非"删档重建"——按 D-1/D-2 语义换全量 fallback，验收⑥实测锁定。
3. `EquipmentNoCapGuardTest` 装备来源键白名单断言（`{equipment_legacy_compensation}`）随补偿链退役更新为空集——该守卫的"装备容量溢出分支不得复活"语义保持。
4. **SaveWipeCoordinator 快照清理目录缺陷（新增测试实测抓出）**：初版快照/恢复残留清理扫描 `context.filesDir` 根，而 DB 文件族实际在 `filesDir/databases/` 子目录——真删档时快照残留会漏清（验收①口径的清零缺口）。`SaveWipeCoordinatorTest` 红灯锁定后改为扫描 `dbFile.parentFile`，绿灯复核。
5. **core/data/schemas 历史族 56 个被跟踪 json（独立复核补收）**：侦察期 `find` 只查了 `app/schemas`，遗漏 `core/data/schemas`（另一处 `room.schemaLocation`）下 v2..v65 迁移测试基线族——随 A7 一并删除，守卫扩双目录防回流。

## 7. 验收⑮ 独立第二遍差分复核

**执行者**：独立子代理（与实施会话完全隔离，全只读机械扫描，不采信实施方清单；共三轮）。

**终态结论：PASS**（差集清零，无新差集产生）。

| 轮次 | 结论 | 差集 | 处置 |
|---|---|---|---|
| 第一轮（全量重扫） | FAIL | 2 组约 30 处：A8 活规范/模块规范漏条目 + 历史档案不回改清单不完整；测试源码注释面 6 处残留（SingleColumnStatGuardTest 豁免面、LoadTest/CloudSlotLoadTest/CacheWriterTest/EquipmentNoCapGuardTest/SaveBackend KDoc） | 全部修复：三份活规范回改（rules/database-migration.md、core/data/AGENTS.md、根 AGENTS.md §7，agent-instructions 复跑 EXIT=0）；组二注释清零；**core/data/schemas v2..v65 历史族 56 个被跟踪 json 补删**（首轮双方侦察共同遗漏的另一处 room.schemaLocation）；DeadCompatRemovalGuardTest 扫描面扩 src/main+src/test、孤儿检查扩双目录 |
| 第二轮（全量重扫） | FAIL（收尾级） | 4 条尾差：.gitignore 实际无 schemas 条目（首轮"已忽略"系误判）、rules 建表规范段 schema 提交/columnExists 两行、core/data/AGENTS.md 云读档管线"版本迁移"步漏改、报告 A1 行计数 23→24 未同步 | 全部修复：.gitignore 补两目录条目（check-ignore 实测双命中）、rules 两行回改、AGENTS.md 一行回改、报告计数同步 |
| 第三轮（定点复核） | **PASS** | 空（无新差集） | — |

复核员对实施方的正面确认（原样保留）：D-9 可恢复链逐项在位（SaveFileManager/.sav/.bak/tombstone、快照/恢复/裁剪/覆盖、findVersionedBackup 的无版本 legacy 兜底确认删净、SaveFileFormat）；三大守卫断言真实有效非"存在即合格"（MigrationRequiredGuardTest 的"绊线而非存在性证明"上限已由报告 §5 自述一致）；白名单 5 项理由全部成立；SaveWipeCoordinator 清理面五段齐全无越界（AdFreeWhitelist 未触及）；真机冒烟缺口已如实登记（§5.3）。

---

## 8. commit

本报告随批提交；commit sha 见派工台账回填与分支 `feat/single-save-SS0` 顶端提交（`feat(wipe): SS0 删档重置 + 旧存档兼容代码清零`）。
