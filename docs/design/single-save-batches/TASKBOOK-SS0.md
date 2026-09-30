# TASKBOOK-SS0 · 删档重置 + 旧存档兼容代码清零

> **本文件是 SS0 的派工真源**。上位方案：[`../single-save-and-persistence-consolidation-plan-2026-10-01.md`](../single-save-and-persistence-consolidation-plan-2026-10-01.md) **§0.5**（前提变更）+ §2.3 作废说明。
> 协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 入口判据：工作树干净；产品公告与渠道强制更新已就绪（W2/W10）。

---

## 1. 目标与验收判据

| 项 | 内容 |
|---|---|
| 业务目标 | 测试期**主动删档**，并借这次机会把项目内**所有为"兼容旧存档"而存在的代码清零**；保证新旧数据不互通。 |
| 验收① | **老档被清且不可恢复**：本地库 + `.sav`/`.bak`/`.deleted` + 归档 + MMKV 台账 + 迁移前备份 + 孤儿 schema 全部清理 |
| 验收② | **云端旧档失联**：云端存档**改命名**（旧命名自然失联）+ 主动删除当前登录账号的旧命名档（双保险，W1） |
| 验收③ | **Room 迁移链清零**：63 条 `MIGRATION_*` + `ALL_MIGRATIONS`（`GameDatabase.kt:66-81`）+ **23 个 `RoomMigration*Test.kt`** + `android/app/schemas/.../1.json` 孤儿 schema 全部删除；`fallbackToDestructiveMigrationFrom(1)`（`:574`）**保留** |
| 验收④ | **存档格式兼容链清零**：`SaveDataVersionMigrator` + `SaveVersion` 迁移语义 + `serialization/backwardcompat/` **整包** + `SaveDataVersionMigratorTest` + 两处 DI（`SerializationModule.kt:5,14`、`StorageModule.kt:67`） |
| 验收⑤ | **存量迁移族清零**：`SaveMigrationCard` / `SaveMigrationState` / `SaveMigrationCoordinator` / `SaveMigrationPlanner` / `SaveMigrationLedger` + 其 5 个测试类 + SettingsTab 挂载点 + `cloud_migration_slot{N}_state` 键 |
| 验收⑥ | **版本号 65→66 保持递增**（W4；归零会因降级直接崩溃）且**删全部迁移注册** ⇒ 老库被 destructive 重建。**必须实测**：造一个 v65 库用新版本打开，结果应为**重建而非崩溃** |
| 验收⑦ | **删档范围**：连本地账号/合规缓存一起清（W5）；**`AdFreeWhitelist` 保留**（W11） |
| 验收⑧ | **删档触发**：新版本首次启动自动清（`wipe_*_done` 标记幂等）+ 保留开发入口可重复触发（W12） |
| 验收⑨ | **`SaveValidator` 28 条逐条判定**，产出处置表：为历史旧档数据写的删、为运行期完整性写的留（W7） |
| 验收⑩ | **CI 守卫**：新增 `@Entity` / 列变更必须同批出现 `MIGRATION_*`，否则判红（铁律 17） |
| 验收⑪ | **邮件清理（M1/M2/M3/M4）**：删 `BuiltinMailConfig` 的 QQ 群邮件（唯一非节日，29→**28** 封，只留节日）；删白名单福利邮件全链（`injectWhitelistBonus` + `sendWhitelistBonus` + **4 个调用点**）；**保留** `injectAdminMail`、`overflow`、`secret_realm` |
| 验收⑫ | 🔴 **锚点全量枚举 + 双向对账**：按 §2.7 的锚点集扫全仓（生产 + 测试），产出**处置表**（每条 `删除` 或 `保留`+理由）；判据 = **命中条目数 == 处置表条目数**（多一条少一条都不算完成）。**禁止以"我列得仔细"代替机械对账** |
| 验收⑬ | 🔴 **编译器背书**：删完 `compileReleaseKotlin` **零错误**（编译错误即"遗漏引用"的权威清单）+ 相关模块 `testReleaseUnitTest` 全绿（兜住"编译得过但已死"的残留） |
| 验收⑭ | 🔴 **反向守卫**：新增 `DeadCompatRemovalGuardTest`——断言处置表"删除"列的每个路径**已不存在**，且全仓生产源码 **零** `MIGRATION_` / `backwardcompat` / `SaveDataVersionMigrator` / `cloud_migration_` 命中（白名单**显式声明**并注明理由）。**未过此守卫 = 未完成** |
| 验收⑮ | 🔴 **独立第二遍差分复核**：由**独立会话/子代理**用同一锚点集重扫，与处置表对**差集**；差集非空即打回。复核结论须写进报告 |
| **不做** | **不删槽位维度**（SS1）；不碰 C++ 协议字段；不删 `SaveValidator` 的运行期完整性规则；**不碰任何与"可恢复性"相关的实现**（见 D-9） |

---

## 2. 实测清理清单

### 2.1 Room 轴（可整链删）

| 目标 | 位置 | 处置 |
|---|---|---|
| 迁移链 | `GameDatabase.kt:66-81` `ALL_MIGRATIONS`（2_3 … 64_65，63 条） | **删** |
| 迁移实现 | `GameDatabaseMigrationsV2ToV10/V11ToV20/.../V65.kt` 一族 | **删** |
| 迁移测试 | **23 个** `RoomMigration*Test.kt`（含 `RoomMigrationLegacyTest` / `RoomMigrationRecoveryTest`） | **删**（保留 1 个"新版本建库 + destructive 行为"的替代测试） |
| destructive fallback | `GameDatabase.kt:574` `fallbackToDestructiveMigrationFrom(1)` | **保留**（W4） |
| 孤儿 schema | `android/app/schemas/.../1.json` | **删** |
| 迁移前备份 | `backupDatabaseForMigration`（`:485`，调用点 `:543`） | **改语义保留**（见 D-3）：迁移没了它就成了死代码，但它是 `restoreFromBackupIfNeeded`（`:763`）的唯一备份源 ⇒ 改为"启动前快照"，去掉 migration 字样 |

### 2.2 存档格式轴（可整链删）

- `SaveDataVersionMigrator` 的 **8 处调用**：`StorageEngineLoadOps.kt:176,258` / `StorageEngine.kt:236,239` / `SaveLoadViewModelCloudOps.kt:128,252` / `SaveLoadViewModelCloudLoadOps.kt:81` / `CloudSaveCacheWriter.kt:147`。
- `core/domain/.../SaveVersion.kt`（迁移链终点语义）。
- `serialization/backwardcompat/`：`OldSerializableSaveData` + `OldSaveFormatDeserializer`。
- ⚠️ **`SaveData.version` 协议字段保留**（云档版本戳仍有价值），只删迁移逻辑（D-4）。

### 2.3 存量迁移族（可整族删）

`SaveMigrationCard` / `SaveMigrationState` / `SaveMigrationCoordinator`（feature/game/.../saveload/）+ `SaveMigrationPlanner` / `SaveMigrationLedger`（core/data/cloud/）+ `SaveMigrationCardTest` / `SaveMigrationCoordinatorTest` / `SaveMigrationPlannerTest` / `SaveMigrationLedgerTest` / `SaveMigrationGuardTest` + SettingsTab 的迁移卡挂载 + `cloud_migration_slot{N}_state` MMKV 键。

### 2.4 残留文件（W6 全清）

`xianxia_sect.db.pre_migrate_backup.v*`、`.restore_attempted`、`saves/slot_*.sav|.bak|.deleted`、`archives/*.arc`、MMKV 全部 `cloud_upload_ledger_slot{N}_*` 与 `cloud_migration_slot{N}_state`、`android/app/schemas/.../1.json`。

### 2.5 云端（W1 双保险）

- **改命名**：`mnzm_cloud_save` → 新命名（带测试期标识/协议版本戳），`slot_N` → 新命名。旧命名在新版本里**不再被读取** ⇒ 旧档自然失联。
- **主动删除**：用 `SaveBackend.delete(slot)` / `listAllArchives()` 删除当前登录账号的旧命名档（只能删当前账号，删除失败不阻断）。
- ⇒ 旧命名常量与 `slotFromArchiveName` 的硬编码 `1..6`（`TapTapSaveBackend.kt:383`）随本批一并处理。

### 2.6 邮件清理（M1–M4）

| source | 位置 | 性质 | 处置 |
|---|---|---|---|
| `builtin`（QQ 群） | `BuiltinMailConfig.kt:26-36` `mail_qq_group_v4_0_03`（群号 1085248982，10 枚宝品储物袋） | 运营邮件 | **删**（29 → 28 封）；同批删 `BuiltinMailConfigTest.kt:135-141` 的 `builtinMailConfig_qqGroupMailExists` 断言 |
| `builtin`（节日） | `BuiltinMailConfig.kt` 其余 **28 封**（2026×14 + 2027×14） | 运营邮件 | **保留**（本次唯一保留的运营邮件） |
| `admin`（白名单福利） | `MailService.injectWhitelistBonus` + `GameEngineAdminOps.sendWhitelistBonus`（`:78`）+ **4 个调用点**：`SaveLoadViewModelNewGameOps.kt:111` / `RestartOps.kt:227` / `LoadOps.kt:209` / `CloudLoadOps.kt:197` | 白名单福利 | **删全链**（W11 只保留免广告特权，不再发福利邮件） |
| `admin`（手动补偿） | `GameEngineAdminOps.kt:48-51` `injectAdminMail` | 运营工具 | **保留**（M3） |
| `overflow` | `OverflowMailSender.kt:420` | 🔴 系统功能（仓库满转邮件） | **保留**（本质是防丢，不是运营内容） |
| `secret_realm` | `SecretRealmService.kt:1137` | 🔴 系统功能（秘境到期送达） | **保留** |
| `nurture_pill_retirement` | `NurturePillRetirementRule.kt:202` | 历史兼容补偿 | 随 W7 判定 |
| `equipment_legacy_compensation` | `LegacyEquipmentCompensationRule.kt:226` | 历史兼容补偿 | 随 W7 判定 |

**产品后果（须写进报告）**：删 QQ 群邮件等于取消游戏内**唯一的玩家社群引流入口**；白名单福利邮件删除后，白名单玩家只剩免广告特权、不再有每档一次的福利邮件。

### 2.7 旧存档兼容代码的锚点集（验收⑫ 的检索面，**不靠记忆**）

> 本节的锚点集是"不遗漏"的**唯一机械依据**。任何一条锚点扫出的命中都必须进处置表。

| 锚点 | 检索模式 | 已知命中规模（2026-10-01 实测） |
|---|---|---|
| A1 Room 迁移 | `MIGRATION_` / `ALL_MIGRATIONS` / `fallbackToDestructive` / `safeDropColumns` / `rebuildTableDroppingColumns` / `Migration(` / `addMigrations` | **生产 180 处**（22 个 `GameDatabaseMigrations*.kt` + `GameDatabaseMigrationSupport.kt` + `GameDatabase.kt`）；**测试 23 个 `RoomMigration*Test.kt`** + `MigrationChainGuardTest` + `RoomMigrationSupport` + `RoomMigrationRecoveryTest` |
| A2 格式兼容 | `SaveDataVersionMigrator` / `CURRENT_SAVE_VERSION` / `SaveVersion` / `saveVersion` / `backwardcompat` / `OldSaveFormatDeserializer` / `OldSerializableSaveData` | **生产 8 处调用点**（`StorageEngineLoadOps:176,258`、`StorageEngine:236,239`、`SaveLoadViewModelCloudOps:128,252`、`SaveLoadViewModelCloudLoadOps:81`、`CloudSaveCacheWriter:147`）+ 定义与 DI（`SerializationModule:5,14`、`StorageModule:67`）+ `SaveVersion.kt` + `backwardcompat/` 整包；测试 `SaveDataVersionMigratorTest` |
| A3 存量迁移族 | `SaveMigration` / `migratableSlots` / `cloud_migration_` / `MigrationSlotState` / `SlotMigrationAction` | **生产 4 个类**（`SaveMigration{Planner,Ledger}`、`SaveMigration{State,Coordinator}`）；测试 4 个类 + `SaveMigrationGuardTest` |
| A4 迁移备份 | `pre_migrate_backup` / `backupDatabaseForMigration` / `restoreFromBackupIfNeeded` / `pruneMigrationBackups` / `MIGRATION_BACKUP_RETENTION` / `.restore_attempted` | `GameDatabase.kt` 多处 + `AppModule.kt:67` + `RoomMigrationRecoveryTest` |
| A5 **易漏面（必查）** | `Legacy` / `legacy` / `Compat` / `Old` / `@Deprecated` / `@Ignore` / `deprecated` | 🔴 **`core/engine/.../di/LegacyObjectModule.kt`**、`EquipmentLegacyTableReader.kt`、`LegacyEquipmentCompensationRule.kt`、`JsonConverters.kt:53`（旧行兜底分支）、`EquipmentInstanceDao.kt:14`（注释引用 `MIGRATION_63_64`）——**这几个是主线程手写清单曾漏掉的，A5 扫出来才补上** |
| A6 槽位维度 | `slot_id` / `slotId` / `DEFAULT_MAX_SLOTS` / `resetForSlot` / `CLOUD_SAVE_SLOT` | 归 SS1（本批不动），但**必须同批登记**以保证两批合起来无残留 |
| A7 文件与资源面 | `*.pre_migrate_backup*` / `.restore_attempted` / `slot_*.sav|.bak|.deleted` / `archives/*.arc` / `android/app/schemas/**/*.json` / MMKV `cloud_*` 键 | 见 §2.4 |
| A8 文档/判据面 | `rules/` / `docs/` / `AGENTS.md` 中引用上述符号的行 | **不回改历史**（`docs/AGENTS.md` 批次档案），但须在报告里列出"已知过期引用清单" |

**对账规则**：A1–A8 每个锚点的命中 → 逐条判定 `删除`（附去向：删文件 / 删调用点 / 改语义保留）或 `保留`（**必须附理由**，如"运行期数据完整性，非版本兼容"）。**总数必须相等。**

---

## 3. 决策

| # | 决策 | 依据 / 代价 |
|---|---|---|
| **D-1** | **版本号 65→66 单调递增**，不归零 | 老库（65）在新版（1）上打开会走**降级**路径，Room 默认**直接崩溃**而非清库；递增才走"缺迁移 ⇒ destructive"的正确路径 |
| **D-2** | **删全部迁移注册**，依赖现有 `fallbackToDestructiveMigrationFrom(1)` | 语义上"v2..v65 缺迁移即毁灭重建"；**实测确认后才算完成**（见验收⑥） |
| **D-3** | **迁移前备份改语义保留**（改名为"启动前快照"） | 它的消费者 `restoreFromBackupIfNeeded` 是**DB 损坏恢复**，与版本兼容无关；一并删掉会失去损坏自愈能力。历史备份文件按 W6 清理 |
| **D-4** | **`SaveData.version` 字段保留**，只删迁移逻辑 | 该字段是云档版本戳（跨设备识别用），删字段要动双端协议，收益为零 |
| **D-5** | **云端双保险**：改命名（主）+ 删除（辅） | 删除失败无法察觉，命名变更才是可靠手段 |
| **D-6** | **删档触发 = 首次启动自动清（幂等标记）+ 开发入口** | 玩家无感完成；测试期可能反复重置，故保留可重复入口 |
| **D-7** | **`SaveValidator` 逐条判定并出处置表** | 混删会丢掉运行期防异常数据的防线（如数值消毒、引用一致性、上限钳制） |
| **D-8** | **加 CI 守卫防将来静默删档** | 删光迁移后，忘写迁移 = 玩家档被静默重建。守卫是本批的**必要配套**，不是可选项 |
| **D-9** | 🔴 **冗余相关的实现一律保留**（用户 2026-10-01 第三次拍板：「有风险就不删了」）。<br>**保留**：`SaveFileManager` 文件层（`.sav`/`.bak`/`.tmp`/tombstone）、`backupDatabaseForMigration` 启动前快照、`restoreFromBackupIfNeeded` 启动恢复、`pruneMigrationBackups`、`SaveFileFormat` 格式本体。<br>**仍删**：**只做兼容、不提供冗余**的部分——`OldSaveFormatDeserializer` + `OldSerializableSaveData` 整包、`SaveDataVersionMigrator` 的旧版本迁移分支、无版本后缀旧备份的兼容分支（`GameDatabase.kt:868-873` 的 `legacy` 分支）。 | 删档解决的是"旧数据留不留"，不解决"新数据会不会坏"。`destructive fallback` + 本地零冗余 = 一次忘写迁移就静默清档且不可恢复。**判据：删完不得降低任何一条可恢复路径**——提供冗余的一律不碰，只删纯读旧数据的分支 |

---

## 4. 文件面与切片（≤10 文件/片）

| 片 | 允许改 | 禁止改 | 自检项 |
|---|---|---|---|
| **SS0-a** 迁移链清零 | `GameDatabase.kt`、`GameDatabaseMigrationsV*.kt`（删）、23 个迁移测试（删）、新 destructive 基线测试（**新**） | `@Database` 实体清单、`DATABASE_VERSION` 递增语义 | 全仓零 `MIGRATION_` 生产引用；新基线测试断言 v65 库被重建 |
| **SS0-b** 备份机制改语义 | `GameDatabase.kt`（`backupDatabaseForMigration` → 快照语义、`:763` 恢复链）、相关测试 | 恢复判定纯函数逻辑 | 恢复能力保持；命名不再含 `migration` |
| **SS0-c** 格式兼容链清零 | `SaveDataVersionMigrator`（删）、`SaveVersion`（收敛）、`serialization/backwardcompat/`（整包删）、`SerializationModule.kt`、`StorageModule.kt` 及 8 处调用点 | `SaveData.version` 字段 | 编译绿；云档路径不依赖迁移器 |
| **SS0-d** 存量迁移族清零 | `SaveMigration*.kt`（删 ×5）、其 5 个测试（删）、`SettingsTab.kt` 挂载点、`SaveMigrationLedger` 的 MMKV 键 | 云上传队列核心（`UploadQueue`） | 全仓零 `SaveMigration*` 生产引用 |
| **SS0-e** 删档执行 | 首次启动清档（幂等标记）、开发入口、`clearAllSlotTables` 复用 | 槽位维度（SS1） | 幂等；重复触发无副作用；开发入口不暴露给玩家 |
| **SS0-f** 云端命名与删除 | `TapTapSaveBackend`（命名 + `slotFromArchiveName`）、`TapCloudSaveManager`（同名常量）、删除调用 | 仲裁/队列逻辑 | 旧命名零读取路径；删除失败不阻断启动 |
| **SS0-g** 残留清理 | 启动期残留扫描（备份/归档/台账/孤儿 schema） | 玩家档本体 | 清理清单写在报告里；幂等 |
| **SS0-h** `SaveValidator` 判定 | 28 条规则逐条处置（删/留）+ 处置表 | 留用规则的语义 | 处置表逐条给理由 |
| **SS0-i** CI 守卫 | 新增守卫：实体/列变更必须同批出现 `MIGRATION_*` | 其他 CI 步骤 | 守卫在干净检出下可运行 |
| **SS0-j** 邮件清理 | `BuiltinMailConfig.kt`（删 QQ 群条目）、`BuiltinMailConfigTest.kt`（删对应断言）、`MailService.injectWhitelistBonus`（删方法）、`GameEngineAdminOps.kt`（删 `sendWhitelistBonus`）、**4 个调用点**（`SaveLoadViewModelNewGameOps` / `RestartOps` / `LoadOps` / `CloudLoadOps`）、相关测试 | `injectAdminMail`、`overflow`、`secret_realm`、节日邮件 28 封 | 删后 `mails.size == 28`；全仓零 `sendWhitelistBonus` / `injectWhitelistBonus` 引用 |

**共享面**：`GameDatabase.kt` 与 `StorageModule.kt` 后续被 SS1/SS2/SS3 继续改 ⇒ 本批改完后**冻结**其"版本与建库"段。

---

## 5. 门禁清单

```powershell
# 工作目录 = C:\Mnzm\XianxiaSectNative\android
.\gradlew.bat compileReleaseKotlin --console=plain
.\gradlew.bat :core:data:testReleaseUnitTest :app:testReleaseUnitTest --max-workers=1 --console=plain
.\gradlew.bat :core:data:detekt :app:detekt --console=plain
# 破坏性重建实测（验收⑥，必须真跑）
.\gradlew.bat :core:data:testReleaseUnitTest --tests "*DestructiveRebuild*" --max-workers=1 --console=plain
# 仓库根
node scripts/check-agent-instructions.mjs
```

**真机冒烟（删档验证）**：装旧版本 → 建进度 → 覆盖安装新版本 → 启动 → **应为全新档**；清数据重装 → 启动 → 应为全新档；换设备登录同账号 → **不得从云端恢复旧档**。

---

## 6. 登记项

1. **跨批登记（SS1）**：本批不动槽维度；`slot_*.sav` 文件改名与 `slot_id` 列删除归 SS1。
2. **跨批登记（SS10）**：**删档重置必须写进玩家可见 changelog**（`changelog_entries.json`）+ 技术 changelog。
3. **风险（必须写进报告）**：W5"连账号/合规缓存一起清"会**强制玩家重新登录 + 重新实名**；`AdFreeWhitelist` 按 W11 保留。若渠道侧对实名数据有留存要求，需产品与合规确认——**本批只执行，不裁定合规**。
4. **风险**：destructive fallback 永久保留 ⇒ 依赖 D-8 的 CI 守卫兜住"忘写迁移"。

---

## 7. 一句话给执行者

**用"版本号 65→66 + 删光迁移注册 + 保留现有 destructive fallback"让老玩家的库被自动重建，同时把两条兼容链（Room 迁移 / 存档格式）、存量迁移族、以及设备与云端的全部旧档残留一起清零；再加一条 CI 守卫，防止将来"忘写迁移"变成静默删档。**
