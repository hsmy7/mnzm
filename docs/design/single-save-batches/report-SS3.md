# REPORT-SS3 · 持久化面收口四组件（change_log / WAL / 归档 / Metrics）

> 派工真源：[`TASKBOOK-SS3.md`](TASKBOOK-SS3.md)；协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4.3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 基线：`a4c17acc2`（main，含 SS0/SS1/SS9/SS2 并网结果）；分支 `feat/single-save-SS3`。

---

## 1. 做了什么

### SS3-a `change_log` 赋责

| 变更 | 文件 | 说明 |
|---|---|---|
| 新增变更摘要器 | `core/data/.../incremental/SaveDataChangeSummarizer.kt`（新） | 对比上次成功保存与本次保存的 `SaveData`，产出真实变更摘要行：**表名（tableName）/ 主键（recordId）/ 变化字段集（newValue 载荷 UTF-8 文本 `fields=a,b`）**；`game_data` 主行做字段级对比（排除保存戳 `timestamp`/`saveVersion`），14 张集合表按 `id` 键控三向分类（INSERT/UPDATE/DELETE）；行数据类反射取值（字段表按类缓存，保存为低频操作）；单次上限 200 行，超限按表聚合 `__overflow__` 行 |
| 写入点重接 | `StorageEngineSaveSupport.kt` | `logSaveChanges()` 空 UPDATE 单行（old/new 恒 null）退役；新 `logSaveChanges(previous, current)` 走摘要器 + `logBatchChanges`；对比基准 = 保存前缓存中的上一份数据（`CacheLayer` 既有缓存，**不新增副本**），在 `updateCacheAfterSave` 覆盖前读取；previous 为 null（首次保存/缓存未命中）写一条 `baseline:` 基线行，诊断轨迹从首次保存起连续 |
| 读面接线 | `ChangeLogDao.kt` / `ChangeLogPersistence.kt` | 新增 `getRecent(limit)`（时间倒序，诊断消费）+ `getRecentChanges()`；`getUnsyncedChanges` 一族保留（SS7 云同步消费面，语义不动） |
| 摘要器单测 | `SaveDataChangeSummarizerTest.kt`（新） | 6 例：基线行 / 全等零行 / 字段集与戳字段排除 / 增删改三向 / 不同实例同内容不产行 / 溢出聚合 |

「old/new = null 空行」的消除口径：摘要行 `newValue` 恒为非空摘要文本字节（`added` / `removed` / `fields=…` / `baseline:…` / `overflow:count=N`），`oldValue` 为 null（摘要语义下无信息量，不占空间）——行内必有真实信息，不再是占位空行。

### SS3-b `FunctionalWAL` 摘除

| 变更 | 文件 | 说明 |
|---|---|---|
| DI 解绑 | `di/StorageModule.kt` | `provideWAL` 及 wal 包 import 删除 |
| 持有面收敛 | `StorageCoreFacade.kt` | `wal: WALProvider` 构造参数删除（事务编排归 Room） |
| 调用点摘除 | `StorageEngine.kt` | `startMaintenance` 的 WAL recover 块、`shutdown` 的 `core.wal.shutdown()`、`performFullTransactionSave` 的 begin/commit/abort 记账全删——保存事务边界 = `database.withTransaction` 一处，行为语义逐位不变（§2 对拍）；顺带移除失去消费者的 `scope` 属性与 `launch` import |
| 回滚辅助删除 | `StorageEngineSaveSupport.kt` | `abortWalQuietly` / `abortWalSyncQuietly` 删除 |
| 组件整组删除 | `data/wal/FunctionalWAL.kt`、`WALProvider.kt`、`FunctionalWALEntryCodec.kt`、`test/.../wal/WALDataClassesTest.kt` | 四文件 + 空目录删除 |
| 常量退役 | `StorageConstants.kt` / `StorageConstantsTest.kt` | `WAL_FILE_NAME`/`MAX_WAL_SIZE_BYTES`/`CHECKPOINT_INTERVAL`/`WAL_BUFFER_SIZE_BYTES`/`WAL_FLUSH_INTERVAL_MS`/`WAL_MAX_PENDING_BYTES`/`MAX_SNAPSHOT_SIZE_BYTES`/`MAX_TOTAL_SNAPSHOTS_SIZE_BYTES`/`MAX_SNAPSHOT_AGE_MS`/`MIN_SNAPSHOTS_TO_KEEP` 删除（零消费者）；`WAL_DIR_NAME`/`SNAPSHOT_DIR_NAME` 保留并改注释为孤儿清理定位常量（消费方唯一 = `DataPruningScheduler`，`WalRetirementGuardTest` 锁定）；测试同步删 8 条退役常量断言 |
| 升级残留清理 | `DataPruningScheduler.kt` | 新增 `deleteLegacyWalDirOnce()`（与既有 `deleteLegacySnapshotsOnce` 同构）：升级设备上的 `filesDir/wal_v4/` 孤儿目录首轮修剪时一次性删除——SS2 登记的「WAL 目录随摘除一并消失」含存量残留收口 |
| SS2 白名单随批删除 | `AccountDataIsolationGuardTest.kt` | `DEVICE_SCOPED_FILE_WRITES` 移除 `wal/FunctionalWAL.kt` 及其注释（SS2 派工登记项） |
| 守卫改语义（不放宽） | `WalRetirementGuardTest.kt` | 原"调用点总数锁 + 门控断言"退役；新断言：① 六模块生产源码零 WAL 符号残留（`FunctionalWAL`/`WALProvider`/`WALEntryType`/`WAL_FILE_NAME`/`MAX_WAL_SIZE_BYTES`/`CHECKPOINT_INTERVAL`/`core.wal.`）② `data/wal/` 包目录不得回归 ③ `WAL_DIR_NAME` 唯一消费者 = 孤儿清理 ④ 保留"文件层写侧判据唯一性"（`writesLocalSaveFiles` 判据仍在 `.sav`/`.bak` 链服役） |
| 文档残留 | `CODE_WIKI.md` | 目录树 `data/wal/` 行删除 |

`ThermalStatusProvider`（engine 层接口）随 WAL 摘除失去唯一外部消费者（原仅 `FunctionalWAL` 注入），但其实现类 `ThermalMonitor` 有大量其他消费者（GameEngineCore/AISectBattleProcessor/GameVmServices）——接口帽子归属不在本批切片授权面（SS3-b 允许改清单不含 `core/engine`），登记 §6 处置。

### SS3-c 归档表读面

| 变更 | 文件 | 说明 |
|---|---|---|
| DAO 查询族 | `ArchiveDaos.kt` | 两个归档 DAO 各加 `listRecent(limit)`（archived_at 倒序）/ `getById(id)` / `countAll()`；写入面（`insertAll`/`deleteArchivedBefore`/`deleteAll`）逐字未动 |
| 唯一读面门面 | `archive/ArchiveReader.kt`（新） | `overview()`（两表行数）/ `listRecentBattleLogs` / `listRecentDisciples`（摘要行）/ `restoreBattleLog(id)` / `restoreDisciple(id)`（按 id 还原全量载荷）；载荷损坏/为空如实降级 null + 日志，不向诊断 UI 抛异常 |
| 消费者 | `StorageDiagnosticsFacade`（SS3-e） | 当前唯一生产消费者 = 存档诊断（列表 + 归档概要）；战报/陨落历史由读面的 `listRecent*` / `restore*` 直接承载（任务书 §6.3 预设成立：删档后读面消费者收窄为诊断，仍保留——它同时修复"只写不读"） |
| 守卫改写 | `ArchiveWriteOnlyGuardTest.kt` | 原「归档零读者」断言（DAO 不得有 SELECT + 全仓禁读）**失效并被替换**：新职责四断言 = ① DAO 必须持读方法（防退回只写不读）② `SELECT…FROM archived_*` 唯一落点 = ArchiveDaos.kt ③ 还原通道单点（ArchiveReader）且禁止归档表→主表回写 SQL ④ 写入面语义不放宽（insertAll + 保留期清理在册） |
| 读面单测 | `ArchiveReaderTest.kt`（新） | 5 例：倒序 + limit / 战报载荷往返等价 / 弟子载荷往返等价 / 损坏与空载荷降级 null / 概览计数（in-memory Room，Robolectric） |

**实测偏差说明**：任务书 §2/§4 把"归档表"落在 `DataArchiver.kt`（文件型 `.arc` 归档，`StorageModule.kt:80-84`/`StorageEngine.kt:77`），但其"行内 `dataBlob` 是可还原全量载荷、查询面零生产调用者、`ArchiveWriteOnlyGuardTest` 把无读者钉死"三条实测事实全部指向 **Room 归档表**（`ArchiveDaos.kt`/`ArchiveEntities.kt`，`archived_battle_logs`/`archived_disciples`）。本批读面按事实落在 Room 归档表侧（守卫守护的对象也是它）。文件型 `DataArchiver` 的查询族（`queryBattleLogs` 等）生产零调用者系**另一处独立现状**，不在本批四组件范围，登记 §6。

### SS3-d `StorageMetrics` getter + 上报

| 变更 | 文件 | 说明 |
|---|---|---|
| 读面快照（getter） | `StorageMetrics.kt` | 读面收敛为 `snapshot(): StorageMetricsSnapshot`（10 字段 = 8 既有计数器 + 账本漂移计数及其最近操作名），诊断/上报统一消费；逐字段 getter 经 detekt `TooManyFunctions` 判定后收敛进快照（读数语义不变，消费者单一入口） |
| 新计数器（SS9 消费） | 同上 | `jadeLedgerDriftCount` + `lastJadeLedgerDriftOp`；实现 domain 新端口 `PersistenceTelemetryPort` |
| 跨模块端口 | `core/domain/.../core/util/PersistenceTelemetryPort.kt`（新） | engine 不可见 data，drift 计数经 domain 端口反向接线（依赖方向 2.1 合规）；Hilt 绑定在 `StorageModule`（`StorageMetrics` 即实现） |
| drift 接线（SS9 报告 §5.1 登记） | `JadeSymbolService.kt`（core/engine） | `logNativeDrift` 在既有 `DomainLog.w` 通道上加 `persistenceTelemetry?.recordJadeLedgerDrift(op)`；构造参数默认 null（测试直构零改动） |
| 上报器 | `StorageMetricsReporter.kt`（新） | 聚合 metrics 快照 + `change_log` 行数（`getPendingCount`）+ 归档概览，经既有 `AnalyticsTracker`（TapDB）出报；挂接 `StorageMaintenanceFacade` 周期任务（30 分钟节拍，非阻塞；失败静默降级） |
| 三处同步 | `AnalyticsEvents.kt` / `docs/knowledge-base.md#事件字典` / `AnalyticsEventsDictionaryTest.kt` | 新事件 `#storage_metrics_report` + 11 个属性键（白名单，无自由键）；知识库字典同步登记；守卫测试三集合同步 |
| Metrics 单测 | `StorageMetricsTest.kt`（新） | 3 例：计数器 getter 一致 / 快照字段齐全 / drift 计数与最近操作名 |

SS5/SS7 的新计数器（脏集回退、路径分布、云一致性差额）按任务书 §6.1 归各自批追加——本批只交付 getter 与上报框架，未预置。

### SS3-e 存档诊断入口

| 变更 | 文件 | 说明 |
|---|---|---|
| 诊断聚合面 | `StorageDiagnosticsFacade.kt`（新，core/data） | 只读聚合三路：最近 50 条保存变更（`change_log` 读面）+ 指标快照 + 归档概要/最近归档明细；零写路径 |
| 诊断 ViewModel | `StorageDiagnosticsViewModel.kt`（新，feature/game） | 继承 `BaseViewModel`；打开对话框时单次拉取；失败走统一 `showError` 通道；只读不写 Store（4.4 合规） |
| 诊断对话框 | `StorageDiagnosticsDialog.kt`（新） | `UnifiedGameDialog(Auto)`（自带遮罩/系统栏守卫）；三段：关键计数器 / 最近保存变更（时间+表+主键+操作+摘要）/ 归档概要（战报 vs 防守、陨落弟子） |
| 设置页接线 | `SettingsTab.kt` | 「其他设置」入口行加「存档诊断」按钮（三按钮形态抽 `OtherSettingsActionButton` 复用）；对话框为 SettingsTab 内联 overlay（`dialog-scrim-standard.md` 例外条款，同 ChangelogDialog 先例） |

## 2. WAL 摘除三条链对拍证据（任务书登记项 2）

摘除前基线（worktree `a4c17acc2`，改动前实测）与摘除后终树同面对比：

| 链 | 摘除前路径（基线代码） | 摘除后路径 | 行为等价性证据 |
|---|---|---|---|
| **启动** | `startMaintenance()` → `maintenanceFacade.startMaintenance()`（注册 4 调度）→ `scope.launch { core.wal.recover() }`（`writesLocalSaveFiles` 门控的日志扫描，无恢复语义——`recover()` 仅登记未完成事务记日志） | `startMaintenance()` → `maintenanceFacade.startMaintenance()`（注册 5 调度，新增 StorageMetricsReport）——WAL 扫描块整体消失 | 摘除的是**纯观测块**：`recover()` 的 D-2 决策已确认条目不承载数据字节、恢复依赖 Room 事务原子性 + `.sav` 备份（组件 KDoc 与任务书 §2 同证）；启动链的真实恢复路径（`GameDatabase.verifyAndRecoverDatabase` 启动前快照 + `restoreFromBackup`）零改动。测试：`:core:data` 全量绿（含 `GameDatabase` 恢复链、`StorageEngine` 链路测试） |
| **保存** | `save()` → `performFullTransactionSave()`：内存守卫 → `core.wal.beginTransaction`（门控）→ `database.withTransaction { writeAllDataToDatabase }` → post-save checkpoint（Room `wal_checkpoint` PRAGMA）→ `core.wal.commit`（门控/非阻断）→ 失败路径 `abortWalQuietly` | 同链：内存守卫 → `database.withTransaction { writeAllDataToDatabase }` → post-save checkpoint → 返回；WAL 记账消失，**DB 事务边界与写入内容逐位不变** | `writeAllDataToDatabase`/`clearOldEntities`/heavy 清理零改动；`withTransaction` 提交/回滚语义即原子性真源（D-2）。测试：`:core:data` 全量绿（`StorageEngine` 保存重试/OOM 短路、`writeAllDataToDatabase` 面测试、`ArchivePayloadRoundTripTest`）+ `:app` 全量绿（含存档编排链） |
| **读档** | `load()` → 熔断 → 缓存 → `loadFromDatabase()` → `restoreFromBackup()`（.sav/.bak 应急源）——**无任何 WAL 调用** | 逐位相同（本批未触碰 load 链任何文件行） | 读档链与 FunctionalWAL 本就零交集（摘除前 `core.wal` 引用面实测仅 6 处，全在启动/保存两链）。测试：`:core:data` + `:app` 全量绿（读档/备份恢复/损坏档测试全数通过） |

**对拍数字**：摘除前基线 `:core:data` 681 tests / 0 failures / 0 errors（15 skipped）、`:app` 1016 / 0 / 0（2 skipped）；摘除后终树复跑见 §3（新增用例后总数上移，失败均为 0）。**不接受"应该无影响"的佐证即此**：同测试面前后两轮全绿 + 三链路径逐行对照。

## 3. 门禁实跑（终树）

> 全部命令实跑于 worktree 终树（`C:\Mnzm\XianxiaSectNative-SS3ndroid`）。

```text
compileReleaseKotlin              ✅ BUILD SUCCESSFUL（零新增警告）
:core:data:testReleaseUnitTest    ✅ 680 tests / 0 failures / 0 errors（15 skipped）
:app:testReleaseUnitTest          ✅ 1016 tests / 0 failures / 0 errors（2 skipped）
:core:data:detekt :app:detekt     ✅ BUILD SUCCESSFUL（3 处新违规实修：TooManyFunctions 收敛快照读面 /
                                     TooGenericExceptionCaught 补防御注释 / LoopWithTooManyJumpStatements
                                     重构为分类函数；baseline 零新增）
定向守卫两测试                     ✅ --tests "*ArchiveWriteOnlyGuardTest*" "*WalRetirementGuardTest*" 绿
check-agent-instructions          ✅ EXIT=0（规则①–⑤全过：预算闸 25295/32768、引用 484+ 条全解析）
```

对账（`:core:data` 681 → 680，按类实测）：−11（WALDataClassesTest 整删）
−6（StorageConstantsTest 34→28：退役常量断言删 7、孤儿目录常量补 1）
+1（WalRetirementGuardTest 3→4）+1（ArchiveWriteOnlyGuardTest 2→3）
+14（新增 SaveDataChangeSummarizerTest 6 / ArchiveReaderTest 5 / StorageMetricsTest 3）
= 681 − 11 − 6 + 2 + 14 = 680 ✓；`:app` 1016 = 基线 1016 + AnalyticsEventsDictionaryTest 集合扩 3 事件（用例数不变）

## 4. 旧用例处置表

| 用例 | 处置 | 理由 |
|---|---|---|
| `WALDataClassesTest`（10 例） | **删** | 被测类型（TransactionStatus/WALEntryType/RecoveryResult/EnhancedWALStats/TransactionRecord）随 `WALProvider.kt` 整组退役，测试对象不存在 |
| `WalRetirementGuardTest`（3 例） | **改语义重写**（3→4 例） | 原"调用点总数锁/门控断言"守护的对象已摘除；新断言锁"退役面零残留"，原"判据唯一性"断言保留（判据仍在服役）——改语义不放宽（D-6） |
| `ArchiveWriteOnlyGuardTest`（2 例） | **改语义重写**（2→3 例） | 原"归档零读者"判归前提随 SS3-c 失效，断言反转 + 职责边界四断言；守卫未删除（验收⑤） |
| `StorageConstantsTest`（9 条 WAL/快照常量断言） | **删 8 条 + 增 1 条** | 退役常量断言删除；`SNAPSHOT_DIR_NAME`（孤儿清理消费）补断言；`WAL_DIR_NAME` 断言保留 |
| `StorageSystemBenchmark`（WAL 文案） | **改** | 纯 println 报告文本随组件退役更新（架构概览/持久化评估两段），无断言语义变化 |
| `AccountDataIsolationGuardTest` | **改** | SS2 白名单删除 `wal/FunctionalWAL.kt` 排除项（SS2 派工登记项随批执行） |
| `JadeSymbolService` 既有测试 | **零改动** | `persistenceTelemetry` 构造参数默认 null，测试直构签名兼容 |

## 5. 验收对照（任务书 §1）

| # | 验收 | 结果 |
|---|---|---|
| ① | change_log 写真实变更摘要 + 读面有真实生产消费者 | ✅ 摘要器（表名/主键/字段集，非 null 占位）；读面消费者 = 存档诊断（`StorageDiagnosticsFacade`）+ `StorageMetricsReporter` 上报（`getPendingCount`），非"为将来预留" |
| ② | FunctionalWAL 摘除：DI + 全调用点；事务编排归 Room；守卫改语义 | ✅ DI 解绑、6 调用点摘除、组件四文件删除；`withTransaction` 为唯一事务边界；`WalRetirementGuardTest` 改"零残留"断言 |
| ③ | 归档表最小读面（列表 + 按 id 还原），消费者 = 诊断 + 战报/陨落历史 | ✅ ArchiveReader 两能力齐备；诊断消费落地；战报/陨落历史由读面承载（§6.3 收窄场景成立） |
| ④ | StorageMetrics 补 getter + 上报（三处同步） | ✅ 读面快照 `snapshot()`（10 字段）+ `#storage_metrics_report` 事件（常量/字典/守卫测试三处同步）+ 30 分钟周期上报器 |
| ⑤ | ArchiveWriteOnlyGuardTest 按新职责改写（不得删守卫） | ✅ 守卫在册，断言按新职责反转替换（2→3 例） |

## 6. 未完成 / 登记

1. **登记（跨批 SS5/SS7）**：`StorageMetrics` 的脏集回退次数、增量/全量路径分布、云一致性差额计数器归 SS5/SS7 各自批追加；本批交付的 getter + `snapshot()` + 上报框架即其挂点。
2. **登记（跨批 SS7）**：`ChangeLogPersistence.cleanupOldLogs`（`deleteSyncedOlderThan`）现仍零调用——`synced` 恒 false 使其语义空转，等 SS7 云同步接线 `synced`/`sync_version` 面时一并接活；`getUnsyncedChanges` 一族同属 SS7 消费面，本批未动（写入侧 `synced=false` 语义保持）。
3. **登记（跨批 SS9 收口已闭环，SS10 复核）**：SS9 报告 §5.1 的 drift 计数登记已在本批落地（端口 + 计数 + 上报）；`docs/knowledge-base.md` 玉符节相应句已更新。
4. **登记（ThermalStatusProvider 帽子归属）**：`FunctionalWAL` 摘除后 `core/engine` 的 `ThermalStatusProvider` 接口失去唯一外部消费者（实现类 `ThermalMonitor` 活跃如常）。接口/实现的帽子收敛不在本批切片授权面（SS3-b 允许改清单不含 `core/engine`），留 engine 域后续批处置；当前为无害孤儿接口。
5. **登记（文件型 DataArchiver 查询族零调用）**：`DataArchiver.queryBattleLogs` / `restoreBattleLogs` / `getArchiveStats` / `getTotalArchiveSize`（`.arc` 文件归档，与 Room 归档表两套体系）生产零调用者——SS9 报告 §5.6 同款 YAGNI 悬置；任务书本批范围是 Room 归档表读面，文件型查询族处置（接消费或退役）登记待批。
6. **登记（SS10 changelog 并入）**：玩家可见变更 = 设置页新增「存档诊断」入口；按 V4 拍板并入 4.2.00 唯一条目，本批不写 changelog、不动版本号。
7. **风险（已核实）**：`change_log` 摘要 diff 在保存后置段执行（IO 协程），反射字段表按类缓存后为直接字段读，万级字段读属亚毫秒级；对比基准用 `CacheLayer` 既有缓存（`getOrNull`），不新增 GameData 副本（6.1 合规）。
8. **风险（推测，待真机）**：`#storage_metrics_report` 的 TapDB 出报与属性白名单录入（TapDB 后台「事件管理」）需随下批真机验证点一并核对；上报失败已静默降级，不影响玩家。

## 7. 工作树卫生

- 一次性脚本：无（全程未创建临时脚本）。
- 构建副产物：提交前 `git status` 复核，非本批改动一律 `git checkout --` 还原。
- 树内只留本批改动 + `report-SS3.md`。
