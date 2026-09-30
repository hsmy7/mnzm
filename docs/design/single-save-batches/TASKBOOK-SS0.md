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
| **不做** | **不删槽位维度**（SS1）；不碰 C++ 协议字段；不删 `SaveValidator` 的运行期完整性规则；不删 `SaveFileManager` 的原子写/`.bak` 轮转（那是崩溃应急，不是版本兼容） |

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
