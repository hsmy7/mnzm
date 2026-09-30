# 模拟宗门「存档系统」全量摸底勘察报告（只读取证 · 双画像）

> 日期：2026-10-01（**01:13 首稿 / 01:45 重锚**）
> 勘察方式：只读代码取证，除本报告外未修改/新建/删除任何文件；未跑 clean/assemble/lint/detekt，未装机
> 原取证时点：HEAD = `2f1342ac0c53ab411a401755f1b01c56b0ffa1e9`（2026-10-01 00:15 +0800）
> **重锚时点：HEAD = `be1e1f195`（2026-10-01 01:14:53）** —— 首稿锚点在交稿前即已过期，重锚范围与残留时点声明见 §1.1
> 目的：产出 **(A) 风险画像**（玩家何时损失进度）与 **(B) 能力画像**（能力瓶颈与理论上界的差距及根因），支撑「维持现状 / 局部加固 / 彻底重构」三选一判断
> 写作边界：不写改造方案、不写路线图；只给事实、因果链、量化差距与可行性判断
> 证据纪律：文中 file:line 均为本次亲自打开的行号；探查代理初查事实中凡进入承重结论的均已复核原文（复核声明见 §24 末尾）

---

## 0. 一句话结论与决策者最该知道的 3 件事

**这个存档系统已经不是旧审计（`docs/save-system-audit-2026-09-21.md`）所说的「手动快照落库」，而是一套「Room 单事务全量快照为唯一真相源 + 真 `.bak` 轮转应急 + 现实墙钟 10 秒节拍自动存档 + 可选云主路径（默认关闭）」的多层系统；旧审计的四大头条结论（无自动存档 / 假备份 / 加密零调用者 / heavy 表事务外）在当前 HEAD 上已全部被修复性提交推翻。**

决策者最该知道的 3 件事：

1. **玩家丢失进度的稳态窗口已收敛到 ≤ 约 11 秒**（现实节拍 10 秒 + 0.5 秒合并窗），失败对玩家可见（消息栏常驻行）、会自动重试；代价是「每 10 秒一次全量落盘」的持续写成本。
2. **当前最大的真实风险面已不在"丢进度"，而在三处**：① 系统自动备份（`allowBackup="true"` 且无任何排除规则）会把**密钥与存档一起备份**，恢复/换机路径存在未被验证的组合行为（§13）；② 存档校验链里有**会物理删除玩家数据的规则**（弟子/装备/邮件附件/截断类），其中两条有补偿、其余按"下线玩法"设计清除（§9/§16）；③ 存量 slot-0 旧云管线**无仲裁**，可覆盖云端较新档（云侧损失，非本地，§12）。
3. **能力瓶颈的结构性根因是"增量通道止步于内存镜像"**：C++→Kotlin 镜像已是增量（`exportDirty` 变更集），Kotlin→磁盘恒全量；量化后在当前规模（SR-0 实测最大档 ≈0.29MB）下**该差距不构成必须重构的理由**（§23/§25）。

---

## 1. 勘察锚点与取证范围

| 锚点 | 值 | 证据 |
|---|---|---|
| HEAD（重锚后） | `be1e1f195`（perf(bench): 验收整改 (b)） | `git rev-parse HEAD` |
| `@Database(version)` | **65**（= `GameDatabaseConfig.DATABASE_VERSION`，单一常量源） | `GameDatabase.kt:95`；注解 :136、绑定 :241 |
| 迁移链尾 | `MIGRATION_64_65`；链登记 `MIGRATION_2_3 … MIGRATION_64_65` 共 **64** 条 | `GameDatabase.kt:66-81` |
| `saveVersion` | **2**（`SaveVersion.CURRENT`） | `SaveVersion.kt:15`；`SaveDataVersionMigrator.kt:22` |
| 后端模式 | `LEGACY`（默认）/ `CLOUD_TRANSITION` / `CLOUD_ONLY` | `SaveBackendMode.kt:18-22` |
| 槽位模型 | slot 0 = 云会话伪槽；本地 **1..6**（`DEFAULT_MAX_SLOTS = 6`） | `StorageConstants.kt:20,23`；`SlotLockManager.kt:31,49` |

### 1.1 锚点漂移补注（2026-10-01 01:45 重锚）

首稿（01:13:04 落盘）锚定 HEAD `2f1342ac0`（00:15:29），但该锚点**在交稿之前**即已失效：仓库在 00:26–01:14 之间并网 6 个提交，其中 **2 个直接改动存档系统**。

| 时间 | 提交 | 对存档系统的影响 |
|---|---|---|
| 00:35:32 | `adbfe377c` Room v65 双表删 `aiCaveTeams` 死列 | DB 版本 64→**65**；迁移链尾 63_64→**64_65**；`GameData` 字段号 28 转 reserved |
| 01:09:30 | `8b3e07bf7` 主界面退役 | **删除 `SaveSelectScreen.kt`（647 行）与 `SaveSelectMode.kt`**；新增 `AutoEntry.kt`（自动建档 / 自动读档 / 云档自动下载）与 `SaveLoadViewModelSlotManageOps.kt`；`CloudSlotEntryCard`/`SaveMigrationCard` 迁入 `feature/game/.../saveload/`；`SettingsTab.kt` +345 行 |

**重锚范围（本次已基于 `be1e1f195` 重新取证）**：§1 锚点表、§2 表 A、§5 入口集合与 UI 落点、§8 双轴矩阵、§14 三方对账、§17 死代码清单、§18 测试覆盖表、§19 文档漂移、§21 附录 A、§22 问答 2/4/20、§23/§25 量化基线措辞。

**未重取证（仍为 `2f1342ac0` 时点读数，引用前请自查）**：§4 / §6 / §7 / §9 / §10–11 / §12 / §13 / §15 / §16 的行号与"当前值"表述，§24 复核记录。

**行号位移规律**：`GameDatabase.kt` 本次整体下移 **+2 行**（`backupDatabaseForMigration` 483→**485**、`restoreFromBackupIfNeeded` 761→**763**、`fallbackToDestructiveMigrationFrom` 572→**574**），首稿引用该文件的旧行号请按此校正；其余文件的位移未逐一测量。

**两处证据等级修正**（首稿违反"测试名不得支撑承重结论"）：proto 守卫两行与 0.29MB 量化基线，见 §18 与 §23/§25。

**执行过的命令类别**：`git`（只读子命令 + `log -S` 考古）、`rg` 检索、逐文件 Read、一次授权单元测试（下）。

**测试执行取证（授权范围内）**：

- 命令：`cd android && ./gradlew.bat :core:data:testReleaseUnitTest --tests MigrationChainGuardTest --tests SaveValidationRuleRegistryTest --tests SaveDataMailWireRoundtripTest --max-workers=1`
- 结果原文：`BUILD SUCCESSFUL in 1m 1s`；XML 摘要：`MigrationChainGuardTest tests="4" failures="0" errors="0" skipped="0"`；`SaveValidationRuleRegistryTest tests="8" … 全 0`；`SaveDataMailWireRoundtripTest tests="4" … 全 0`。**16 通过 / 0 失败 / 0 跳过，无跑红项。**
- `git status --porcelain`：测试前输出为空；测试后输出为空（两次均为零行，构建产物被 `.gitignore` 覆盖，无意外差异）。

---

## 2. 未覆盖面声明

**表 A：100% 逐条覆盖清单**

| 范围 | 条目数 | 实际核对 | 结论位置 |
|---|---|---|---|
| 会删/改玩家数据的校验规则 | 注册 28 条（`SaveValidationRuleDefaults.kt:6-51`） | 28/28 逐条读实现 | §9 |
| Room 迁移链完整性 | **64** 条（2_3..64_65） | 链登记表逐行（`GameDatabase.kt:66-81`）+ `fallbackToDestructiveMigrationFrom(1)`（:574）+ 守卫测试 + 抽读迁移 KDoc（v50-v65） | §8 |
| 生产可达写入入口 | **6** 类（手动/自动建档/重开/现实节拍/onStop/云档下载落缓存） | 全部读实现与调用点 | §5 |
| 读档降级/回退分支 | 缓存/DB/`.sav`/`.bak`/tombstone/迁移拒绝/Corrupted | 全部读实现 | §6 |
| 云仲裁判定分支 | 4 verdict × U1-U11 例 | arbitrate 全文逐行 | §12 |
| 能力轴 | 13 | 13/13 五问 | §23 |
| 必答问题 | 33 | 33/33 | §22/各章 |

**表 B：抽样与未覆盖清单**

| 范围 | 总量 | 方式 | 未覆盖部分 | 可能掩盖的风险 |
|---|---|---|---|---|
| 各 DAO 列级 SQL ↔ schema JSON 全对账 | 27 实体/数百列 | 抽样（GameData 字段面 + GameHeavyData + SaveSlotMetadata） | 非 GameData 实体的逐列 SQL 比对 | 幽灵列/死列类低危漂移 |
| 28 条规则的 Room 列↔proto 号一致性 | 同上 | 依赖守卫测试（ProtoNumberCoverageTest/UniquenessTest 在册，未逐行读） | 号漂移被测试拦住的概率 | 极低 |
| 读档后 boot 重算全表（旧审计 §12-G 的 9 行） | 9 | 抽查 2 项（自动收割 `GameEngineLoadSlotOps.kt:142-145`、巡视重建 `:64,87-90`） | 其余 7 项未逐行复验 | 个别"读档覆盖存档值"项可能已变化 |
| CHANGELOG 逐条宣称 vs 代码 | 大 | 抽样（未发现矛盾实例） | 全量比对未做 | 文档级漂移 |
| R8/混淆实测 | — | **未授权（`assembleRelease` 被禁）** | 全部 | 见 §20 |
| 换机/系统恢复备份实测 | — | **静态不可判定** | 全部 | 见 §13/§20 |

---

## 3. 存档系统真实架构图

```
C++ gamecore（AUTHORITATIVE 真相源，零存档 I/O——fopen/ofstream/fstream 全仓 0 命中）
   │ 每旬 nativeSettlePhase → exportDirty 变更集（增量，仅内存）
   ▼
StateSyncService.applyDirty / importState（C++→Kotlin 镜像，引擎线程单事务 updateMirror）
   StateSyncService.kt:77-106（通道定义）、:289 importToNative、:310 增量镜像
   ▼
GameStateStore（Kotlin 内存镜像；11 条独立 MutableStateFlow，GameStateStoreImpl.kt:218-228）
   │ buildSaveSnapshot（引擎线程采样，GameEngine.kt:208-218）
   ▼
SaveFacadeImpl.getStateSnapshot（12 次逐流读取 + RNG 导出 + 玉符 checkpoint）
   SaveFacadeImpl.kt:102-124；GameStateStoreImpl.kt:277-287（无锁逐流读）
   ▼
SaveDataTrimmer（battleLogs 截 1000）+ mails 表注入（SaveLoadViewModelSaveOps.kt:284-288）
   ▼
StorageFacade.save → StorageEngine.save（IO 线程，槽位排他锁 + 熔断器）
   StorageEngine.kt:142-198
   ├─ validateAndPrepareData：SaveValidator 28 规则（损坏拒存/修复替换）+ saveVersion 盖章（:209-246）
   ├─ performFullTransactionSave（:600-664）
   │    ├─ 低内存守卫 100MB（:602-605）
   │    ├─ FunctionalWAL begin（仅 LEGACY/CLOUD_TRANSITION，:611-621）
   │    ├─ Room withTransaction { writeAllDataToDatabase }（:624；★含 heavy 表，内层嵌套同事务）
   │    │    WriteOps.kt:26-55：heavy 先清(保护跳过)→增量写 + 轻量行/实体族/域表/邮件
   │    ├─ post-save PASSIVE checkpoint（:634-640）+ WAL commit（:643-651）
   ├─ handleSaveResult（SaveSupport.kt:140-180）
   │    ├─ 成功：.sav 原子写 + .bak 轮转（非阻断，降级原因回传 UI）→ 缓存 → change_log 一行
   │    └─ 失败：读 .sav/.bak 仅验证可读并留日志（不恢复——DB 事务回滚即旧档完好）
   ▼
读取：缓存 → Room → .sav/.bak（tombstone 守卫 + 迁移 + 校验 + 隔离 + 回写）
   StorageEngine.kt:270-322、StorageEngineLoadOps.kt:32-139、HeavyDataOps.kt:57-231
```

---

## 4. 落盘工件清单表

| 工件 | 真实路径 | 写入者 | 读取者 | 读档真相源？ | 级别 |
|---|---|---|---|---|---|
| `xianxia_sect.db`(+`-wal`/`-shm`) | `databases/` | `performFullTransactionSave`（单事务） | `loadFromDatabaseInternal` | **是（第一真相源）** | A |
| `slot_N.sav` | `filesDir/saves/` | `SaveFileManager.atomicWrite:97-152`（tmp→fsync→rename） | `readWithFallback:223-256` | 否，DB 判空/损坏时应急源 | A |
| `slot_N.bak` | 同上 | `rotateCurrentSaveToBackup:167-204`（**轮转=上一成功存档**） | 同上（RECOVERED 分支 `:240-251`） | 否，二级应急 | A |
| `slot_N.deleted` | 同上 | `markSlotDeleted:356-363` | `isSlotDeleted:366-369`（load/save 双守卫） | 删除语义裁决 | A |
| `xianxia_sect.db.pre_migrate_backup.v{N}` | `databases/` | `backupDatabaseForMigration:485-534` | `restoreFromBackupIfNeeded:763-784` | 迁移崩溃/降级时是 | A（重锚校正 +2） |
| `*.quarantine.{ts}` | `databases/` | `quarantineCurrentDatabase`（HeavyDataOps.kt:409-428） | 人工排查 | 否 | A |
| `wal_v4/transactions.wal` | `filesDir/` | `FunctionalWAL`（LEGACY/TRANSITION 模式） | `recover()` **仅记日志**（StorageEngine.kt:559-578） | 否（无可重放数据，见 §7） | A |
| `archives/*.arc` + index | `filesDir/` | `DataArchiver`（保存链 + 600s 调度） | **生产零调用**（query/restoreBattleLogs 全仓仅 DataArchiver 自身） | 否 | A |
| 归档 DB 表 `archived_battle_logs`/`archived_disciples` | Room | `DataArchiveScheduler:183-223` | **生产零调用**（ArchiveWriteOnlyGuardTest 钉死读面为空） | 否 | A |
| `save_slot_metadata` | Room | `syncSlotMetadata`（SaveSupport.kt:51-66，**与真档同事务**） | 槽位 UI 实际读 `game_data` 行（`querySingleSlot` HeavyDataOps.kt:431-446）；本表读面未逐查 | 否 | A |
| `game_prefs`（MMKV） | `filesDir/mmkv/` | 配置/云摘要/上传台账 | 各配置类 | 否（零进度数据，UploadLedger 三个序号除外） | A |
| `cloud_upload_ledger_*`（MMKV） | 同上 | `UploadLedger:33-83` | `SaveArbiter` 消费方 | 云仲裁账本 | A |
| `.secure_key`/`.secure_key.bak`/`secure_key_prefs` | `filesDir/` + SP | `SecureKeyFileStore` | `RequestSigner:172`、`SecureHttpClient:407`、`SavePayloadSigner:106` | 否（**仅云签名+网络**，本地档不加密不受影响） | A |
| `cloud_save_temp.dat` | `cacheDir/` | TapTap 上传中转 | 上传后删除 | 否 | B（代理初查） |

**C++ 侧存档 I/O：零。** 检索命令 `rg "fopen|ofstream|ifstream|fstream|fwrite|fread" android/app/src/main/cpp/gamecore/src android/app/src/main/cpp/gamecore/include GameCoreBridge.cpp GameCoreJni.cpp` → 0 命中。

**快照拷贝语义（必答 7）**：`SaveFacadeImpl.getStateSnapshot` 返回的 `GameStateSnapshot` 持有各 flow `.value` 的**引用而非深拷贝**（GameStateStoreImpl.kt:277-287 逐条 `get() = _xxxFlow.value`；GameData 为 `var` 字段的可变 data class，GameData.kt:86-120）。`takeAtomicSnapshot()`（事务锁下的一致性快照，GameStateStoreImpl.kt:289-303）**存在但保存链未使用**。撕裂评估：快照构建被收敛到引擎线程（`buildSaveSnapshot → withEngineContext`，GameEngine.kt:214-217），镜像写同在引擎线程（StateSyncService.kt:92 线程契约）→ 同线程串行使 12 次逐流读不会与镜像写交错【推断，依据线程契约+两侧代码；未做运行时证明】。

---

## 5. 写入路径全表

| 入口 | 触发 | 门控链 | 节流/合并 | 全量/增量 | 事务边界 | 失败表现 | 线程/锁 |
|---|---|---|---|---|---|---|---|
| 手动保存 | 设置页「存档管理」弹窗（SettingsTab.kt:895；保存键 :1208） | `checkLocalSaveGuards`（已加载/云忙/重开中/isSaving/isLoading/内存 GC 闸，SaveOps.kt:153-201） | 手动即作废自动窗（SaveOps.kt:51） | 全量 | Room 单事务（Engine.kt:624） | snackbar 如实报错 + 回滚 currentSlot（SaveOps.kt:247-253） | IO 协程 + `saveLock` CAS 5s + 槽位排他锁 |
| 自动建档首存（原「新游戏首存」） | 登录后自动进入：本地无档→`CreateNew`（AutoEntry.kt:52-61；固定落 1 号槽 :18） | boot/云锁/重开/加载/保存互斥（SaveLoadViewModel.kt:316-347） | 失败重试 1 次，两败中止开局（NewGameOps.kt:77-91） | 全量 | 同上 | 首存失败=中止开局（:85-89） | 同上 |
| 重开 | 设置页"重置" | `restartBlockedBeforeLocks`+`acquireRestartLocks`（RestartOps.kt:19-76） | **保护性预存先于引擎重置**（:139-146，预存失败即中止重置） | 全量×2 | 同上 | "重置中止：预存失败，原存档已保留"（:101-110） | saveLock→loadLock 序 |
| 现实节拍自动存 | 每 10 现实秒（MainGameScreen.kt:1296 启动循环） | 三前置：旗标+slot≥1+已加载（AutoSaveOps.kt:74-93） | `SaveOrchestrator` 500ms 合并窗，BACKGROUND 立即冲刷（SaveOrchestrator.kt:69-93） | 全量 | 同上 | 消息栏常驻行"自动存档失败：…"（AutoSaveOps.kt:155-162） | UI 协程轮询 1s（AutoSaveOps.kt:21-28） |
| onStop 后台保存 | `GameActivity.onStop` :857 起，保存触发 :882-894 | 同三前置（:882-890） | 编排器立即冲刷（SaveOrchestrator.kt:73-77） | 全量 | 同上 | Silent 口径：成功零提示、降级仅日志（AutoSaveOps.kt:141-143；后台不可见是已登记限制） | 主线程触发→IO 执行 |
| 云上传 | 本地保存成功后入队（SaveOps.kt:307） | `shouldEnqueueCloudUpload`（非 LEGACY 才入队） | 队列 2s 去抖 + 同槽留最新 + 60s 限频 + 30s→10min 指数退避 + 5 连败熔断 5min | 全量 payload | 上传在 DB 事务之后 | 终态失败 `UploadFailed` → `showError`（SaveLoadViewModel.kt:236-251） | 单飞 worker 协程 |
| 云档下载落缓存（重锚新增） | 云仲裁放行后写本地槽缓存（CloudSaveCacheWriter.kt:100 `storageFacade.save(targetSlot, processed)`） | 仲裁 verdict 分流（CloudSaveCacheWriter.kt:116-120） | 同槽留最新 | 全量 | 走同一 `storageFacade.save` 链 | 失败进 `UploadFailed`/UI 报错 | 云下载锁 |

**必答 4/5（自动存档真实性）**：真实存在且默认开启。节拍常量 `REALTIME_AUTO_SAVE_INTERVAL_MS = 10_000`（AutoSaveOps.kt:21）；三前置 `shouldAutoSave`（SaveTriggerFlag.kt:59-60）；旗标 `realtimeTick=true`/`saveOnBackground=true`（SaveTriggerFlag.kt:39,50）。**旗标是纯内存 object、无任何 UI/持久化开关**（SaveTriggerFlag.kt:23-51；检索无写入点）→ 玩家无法关闭，也不存在"被配置关掉"的路径。

**丢失窗口推算**：稳态最坏 = 节拍间隔 10s + 合并窗 0.5s + 落盘期间被杀（事务回滚，回退上一落盘点）≈ **≤11 秒进度**。叠加因素：保存本身有 30s 超时（SaveOps.kt:290）、重试 2 次×100ms（StorageConfig.kt:119-120）、熔断 5 连败 30s（StorageCircuitBreaker.kt:179-184）——持续存储故障时窗口随故障时长延长，但每 10s 自动重试且失败对玩家可见（消息栏）。进程被杀于事务提交后、`.sav` 写完前：DB 已新、`.sav` 旧，读档走 DB（真相源）不受影响。

---

## 6. 读取路径全表（含降级与回滚）

| 层 | 行为 | 证据 |
|---|---|---|
| ① 内存缓存 | 命中也要过迁移+基础校验+28 规则；Corrupted → 视为未命中回落 DB | LoadOps.kt:53-101 |
| ② Room | 轻量行 + heavy 7 key 分块解码 + 20+ 实体表并行读；heavy 缺 key/超 CursorWindow → 域表按 key 回填 | LoadOps.kt:185-218；HeavyDataOps.kt:57-91,197-231；Backfill.kt:27-67 |
| ③ Corrupted | `restoreFromBackup`：tombstone 守卫 → `.sav`(CRC32C)→`.bak` 回退 → 反序列化 → 版本迁移 → 二次校验 → reconcileStacks → **隔离当前 DB** → 整体回写 DB | Engine.kt:339-419 |
| ④ 都失败 | `SLOT_EMPTY`/`SLOT_CORRUPTED`（UI 报"存档为空或已损坏"） | Engine.kt:304-305；LoadOps VM.kt:154-159 |
| tombstone | DB 有数据但 tombstone 在 → 判"删除中断"清为已删（不复活） | LoadOps.kt:36-42 |
| Repaired | 修复后**仅入缓存不写库**（下次保存时持久化），日志明示 | LoadOps.kt:117-124；SaveValidatorFixes.kt:43-50 |
| 槽位查询异常 | `isLoadError` 态（不得伪装空槽）——防"显示空槽→点新游戏→覆盖真档" | Engine.kt:519-543；SaveData.kt:27-32 |
| 超时 | 读档 60s 超时（VM 层） | LoadOps VM.kt:143-158 |
| OOM | 读档 OOM 不尝试备份恢复（备份同尺寸必再 OOM），如实报失败 | Engine.kt:313-320 |

**"存了但读回丢弃"排查**：未发现结构性丢弃。`SaveData.alliances` 在读侧经 `loadData(alliances=…)` 传入（LoadOps VM.kt:187），函数体内是否消费未逐行核（旧审计称未消费、但 `game_data.alliances` 列单独走通，玩家无感）【部分未复核】。`recruitList` 被规则恒空（§9，属设计性清除）。

---

## 7. 崩溃安全与原子性

- **单事务边界**：外层 `withTransaction { writeAllDataToDatabase }`（Engine.kt:624）包裹全部写（heavy 前缀删除/增量写、实体族、域表、邮件、槽位元数据）。内层再开 `withTransaction`（WriteOps.kt:48）按 Room 2.7 嵌套语义并入同一事务（room-runtime 2.7 SAVEPOINT 机制；依赖 `libs.versions.toml` room 版本与 SR-0 实测记录，本次未重跑实测，列为 B 级）。
- **事务外写入**：`.sav`/`.bak`（提交后，SaveSupport.kt:148）、change_log 一行（:152）、缓存更新（:150）。崩溃窗口后果均为"镜像比 DB 旧"，无一致性问题。
- **`.bak` 是真备份**：轮转语义 = `.bak` 恒为**上一次成功保存**（SaveFileManager.kt:154-204）；超 100MB 跳过轮转并如实回报 `Skipped`（:174-177）；恢复路径真读过 `.bak`（`readWithFallback:240-251` RECOVERED + 修复 `.sav`）。
- **FunctionalWAL 裁定**：它确实**包住 save 事务**（begin 在 Room 事务前、commit 在后，Engine.kt:611-651），但**条目不承载数据字节、`recover()` 仅记日志**（Engine.kt:559-578 注释"仅记录日志供监控"）→ 耐久性完全由 Room 事务承担，WAL 是观测面不是恢复面。CLOUD_ONLY 下整体停开（:612）。
- **杀进程/断电时间线**：Room 事务中死 → 回滚到上次提交（最多丢一个节拍周期）；`.sav` tmp 写入中死 → 残留 `.tmp`（>5 分钟被启动清理，SaveFileManager.kt:296-309），`.sav` 完好；rename 前死 → `.sav` 仍是旧版，`.bak` 已是上上版（轮转先于 rename，:114-117）——两级应急源都在。
- **磁盘满/资源异常**：低 JVM 内存 <100MB 拒绝保存（Engine.kt:602-605）；OOM 短路重试（:258）；备份超限 `Skipped` 不谎报；磁盘 IO 异常 → `IO_ERROR` + UI 可见。**大档主线程写 ANR 风险：不存在**——保存全程 IO 协程（SaveOps.kt:77）+ Room 事务执行器为专用单线程（GameDatabase.kt:554-558）。
- **删档原子性**：tombstone 先行 + 全表清理与 tombstone 路径共用同一 27 表清单（SaveSupport.kt:248-304）+ 成功保存清 tombstone（:122-132）→ 两个崩溃窗口都收敛为"已删"。

---

## 8. 双轴版本兼容矩阵 + 迁移链完整性裁定

| 轴 | 当前值 | 机制 | 降级/高危裁定 |
|---|---|---|---|
| 格式轴 saveVersion | 2 | `SaveDataVersionMigrator`：v0→1 修为÷10（含"误标新档"守卫 :70-76,130-133）、v1→2 acquainted（:96-108）；顺序迁移天然支持跳版；`<0`/`>CURRENT` → **Rejected 拒载**（:49-57） | **高版本档被低版本 App 读 = 拒绝加载**（本地走备份恢复分支，云端报"版本异常"）——玩家回滚版本不能读新档，但不损坏数据；格式轴与 DB 轴**无联动校验代码** |
| 数据库轴 Room | **65** | **64** 条链式迁移单点登记（GameDatabase.kt:66-81，链尾 `MIGRATION_64_65`）+ `MigrationChainGuardTest`（断言：链尾 endVersion == DATABASE_VERSION :46-55、升序无重复 :57-61、首条 MIGRATION_2_3 :63-66） | **破坏性 fallback 仅 `fallbackToDestructiveMigrationFrom(1)`**（GameDatabase.kt:574）——v1 库毁灭重建（首版残留，v1 早于迁移前备份机制，无数据可保）；**v2..v65 无任何 destructive fallback** |
| 迁移前防御 | 3 层真实存在 | ①迁移前文件级备份（:485-534，先 TRUNCATE checkpoint）②恢复判定纯函数（:106-122）+ 文件恢复 + `.restore_attempted` marker 防死循环（:763-859）③版本达标裁剪保留 2 份（:445,448-467，未重验） | 验证通过（逐行读） |
| 版本号 5 处不联动 | `.sav` 头 0x0101（读时校验）/ 帧头 3（旧审计称从不比较，本次未复核消费点）/ saveVersion 2（真数据版本，写读均校验）/ `SaveData.version` 串（云闸用）/ DB **65** | — | 两轴错配风险=历史遗留观察项，无实害路径被发现 |

**重锚新增**：`MIGRATION_64_65`（`GameDatabaseMigrationsV65.kt:25-50`）为 AI 洞府探索队伍链退役——`game_data` 与 `world_map_state` 双表经 PRAGMA 重建删 `aiCaveTeams` 死列（create-copy-drop-rename，幂等，列已不存在时直接返回）。生产迁移 KDoc 自陈该列"零构造、零读写、C++ 零镜像"；`StorageEngineWriteOps.kt` 现无 `aiCaveTeams` 命中（本次独立复核）。行为面由 `RoomMigrationV64To65Test`（168 行）证四件事，见 §18。

---

## 9. 校验/修复规则表（28 条，注册序 SaveValidationRuleDefaults.kt:6-51）

**框架语义**：任一规则抛异常 = 计入 Corrupted（SaveValidator.kt:63-69）→ 保存路径**拒绝保存**（Engine.kt:216-219）、读档路径走备份恢复。三态 Passed/Repaired/Corrupted（RuleOutcome.kt:13-39）。

**判损坏（拒绝加载/拒存）——仅 2 条**：

| 规则 | order | 条件 | 后果 |
|---|---|---|---|
| DiscipleIdBoundsRule | 1 | 弟子数字 id `<0` 或 `>200_000` | Corrupted（防 crafted 大 id 扩容 OOM 循环，:18-44） |
| EntityCountBoundsRule（部分） | 19 | 弟子数 >100,000 | Corrupted（:42-46） |

**会物理删除/改写玩家数据的修复规则（重点）**：

| 规则 | order | 删什么 | 补偿？ | 幂等？ | 误判可逆性 |
|---|---|---|---|---|---|
| GhostDiscipleCleanupRule | 10 | **删除 name 为空的弟子**（:17-35） | 无 | 是（空集跳过） | 不可逆；触发条件=弟子名为空（正常数据不产生） |
| DuplicateDiscipleIdRule | 9 | 重复 id 弟子保第一个 | 无 | 是 | 不可逆 |
| EntityCountBoundsRule | 19 | battleLogs>5000 按时间截断；manualStacks>50000 截断+清悬空引用（:51-67） | 无 | 是 | 不可逆（超限才触发） |
| RecruitListCleanupRule | 20 | recruitList **恒空**（下线玩法，:18-22；恒 Repaired 触发落盘） | 不适用 | — | 设计性清除 |
| MailDiscipleAttachmentCleanupRule | 22 | 邮件内 `type=disciple` 附件摘除（下线玩法，:35-72） | 无 | 是 | 设计性清除 |
| BloodPoolBuildingCleanupRule | 17 | 血炼池建筑+关联槽位移除（:22-51） | 无 | 是 | 设计性清除 |
| LawEnforcementPrisonCleanupRule | 24 | 执法堂/监牢建筑+六类槽位+REFLECTING/LAW_ENFORCING 弟子状态归一化（:30-65） | 状态归一化（防永久卡死） | 是 | 设计性清除 |
| **NurturePillRetirementRule** | 26 | 孕养丹全量移除（仓库/储物袋/邮件附件/配方） | **折算灵石补偿邮件，上限 2000 万**，幂等标记同快照（:76-110） | 标记守卫 | 补偿后不可逆 |
| **LegacyEquipmentCompensationRule** | 27 | 旧装备四路移除（影子表/储物袋/秘境背包/邮件附件） | **折算灵石补偿邮件，上限 1 亿**（:77-158） | 标记守卫 | 补偿后不可逆 |
| BattleLogRefRule | 21 | 结构非法战斗日志条目清除（:32-53） | 无 | 是 | 不可逆（非法条目） |
| 数值类（NumericSanitize=0/CultivationCap=5/DiscipleRealm=13/JadeSymbol=23/SpiritStone=12/EquipmentValueSanitize=28/TimeAxis=25/GameDate=2/GamePhase=4/SectName=1） | — | NaN/负值/越界**重置 0 或钳制**；TimeAxis 以权威轴重算日历 | 无 | 是 | 数值级损失（如 cultivation 超上限截断），正常档不触发 |
| 引用清理类（EquipmentRef=6/BuildingRef=8/DiscipleDeadStatus=14/EquipmentDedupe=15/SlotRef=16/GhostRef=11/ItemRef=18） | — | 清**引用**不删物品本体（DiscipleDeadStatusRule.kt:13-14 明示） | 不适用 | 是 | 装备/弟子本体无损，仅解除关联；BuildingRefRule 在 `placedBuildings` 为空而槽位有引用时整槽移除（:23-30）——数据不一致时的保护性行为 |

**校验时机全集**：保存前（Engine.kt:212-228）、DB 读档后（LoadOps.kt:109-139）、缓存命中后（:58-62）、备份恢复二次（:239-250）、云档下载管线（CloudLoadOps.kt:88-100）。**绕过校验路径**：未发现——四个写源（手动/自动/重开/云恢复）均走 `storageFacade.save` → `validateAndPrepareData`；四个读源均过 `SaveValidator`。

**默认值陷阱**：ProtoBuf 全局 `encodeDefaults = false`（NullSafeProtoBuf.kt:55-57）→ "字段缺失"与"显式为默认值"**不可区分**（proto3 语义）。实害评估：SaveData 顶层仅 version/timestamp/stacksSerialized 带 `@EncodeDefault(ALWAYS)`（SaveData.kt:56-77）；GameData 业务字段缺省语义与零值语义一致（灵石 0=0），真正的陷阱是**未来把某字段默认值改掉会改变旧档解读**——属契约纪律问题，当前无实害路径。

---

## 10-11. 加密 / 签名 / 防篡改现状

**旧审计"全套加密子系统零调用者"已被推翻。** 当前调用者图谱（全部亲自复核）：

| 组件 | 生产调用者 | 证据 |
|---|---|---|
| SavePayloadSigner（HMAC-SHA256，云载荷签名） | `TapTapSaveBackend`（上传签 :154、extra 带签名 :361） | TapTapSaveBackend.kt:62,154,361 |
| SecureKeyManager | `RequestSigner:172`、`SecureHttpClient:407`（网络）、`SavePayloadSigner:106`（派生）、GameActivity 注册 `UiKeyRecoveryCallback`（:248） | 各 :行 |
| DeviceBindingIdentity | XianxiaApplication.kt:217 注入账号绑定因子 | 同 |

- **本地 `.sav`/`.bak`/Room：明文落盘**（ProtoBuf+LZ4+双重校验和，无加密调用——写入链全文无 cipher）。类内自陈的诚实局限：验签在客户端做、密钥同机，"不是防作弊能力"（SavePayloadSigner.kt:28-37）；MISMATCH 仍放行（P4 拍板降级，:152-158）。
- **反作弊面**：明文可改档 → 校验链是主要防线（数值消毒/上限/ID 越界/玉符防刷钳制 JadeSymbolNonNegativeRule.kt:19-45、时间轴防手改 TimeAxisRule.kt:9-14）；改档改数值在上限内不被发现（无签名）。密钥丢失/换机**不影响本地档可读性**（档未加密）；影响的是云签名降级与网络签名链。

---

## 12. 云存档管线与冲突仲裁分支全集

- **模式**：`LEGACY`（默认，云上传队列零活动）/`CLOUD_TRANSITION`（双写）/`CLOUD_ONLY`（本地文件层全关：`.sav`/`.bak`/tombstone/WAL 停写，DB 降级为"可丢弃会话缓存"）。唯一翻转点=迁移卡玩家确认（SaveMigrationCoordinator.kt:220-229）；`CLOUD_ONLY` 生产零写入点。
- **仲裁（SR-3 重写后唯一"谁新"入口）**：`SaveArbiter.arbitrate(L,C,W)`（SaveArbiter.kt:43-56），只看保存序号不比时钟：`L==C 且 W>C → LOCAL_BEHIND`（可下载）；`L>C 且 W<=C 或 W==L → UPLOAD_PENDING`（**下载被拒**："本机此槽位有未上传的新进度，已停止从云端覆盖"）；`L>C 且 W>C 且 W≠L → CONFLICT`（弹窗二选一，禁止静默覆盖）；`W` 未知按 `W==C` 保守重算。**"旧云档覆盖较新本地"在 SR-3 链路上不可静默发生**（下载侧 verdict 分流 CloudSaveCacheWriter.kt:116-120）。
- **残留面 1（边界，[推断]）**：`IN_SYNC` 分支含 `W<C`/`W 未知` 时放行下载覆盖本地缓存槽——仅当 `L==C`（本地无未确认保存）才可达，危害取决于 MMKV 账本记账正确性；迁移语境有 F12 守卫（Planner:163-165），VM 下载路径没有等价守卫。
- **残留面 2（生产可达，存量管线）**：游戏内 slot-0 旧云链（`loadFromCloudSave`/`saveToCloudViaSlot`，CloudLoadOps.kt:22-110）**完全不经过 SaveArbiter/UploadLedger**（仅 App 版本闸 TapCloudSaveManager `arbitrateCloudVersion`）。下载进内存会话（slot 0，不落本地槽，本地 1..6 零影响）；但该会话中手动"保存到云"= **无条件覆盖云端**——同账号另一设备若有更新的云档，会被覆盖（云侧损失；本地无损；需玩家显式操作）。
- **上传队列**：2s 去抖、同槽留最新、60s 共享限频（TapTap 1 次/分钟）、指数退避 30s→10min、5 连败熔断 5min、非重试族失败移除并保持账本脏标志（下次保存重新入队）；冲突上传挂起 `ConflictHeld` → 弹窗。
- **槽位迁移**：本机存量档逐槽上云 + 存量单档 `mnzm_cloud_save` 选槽收编；六步判定矩阵含 F12 守卫（云档无 saveId 或账本 C=0 → 玩家裁决，不静默）；断点续传靠 `pendingSaveId`；升 CLOUD_ONLY 三门槛（无损坏槽+有档槽全收编+无未确认待传）。
- **账号维度（易漏项）**：**本地档按设备归属，与账号零绑定**——登出/换账号不清档、不隔离（登出链 MainActivity.kt:1047-1054 仅清会话；全仓无登出触发清档）；换 TapTap 账号后本地槽原样可见，云侧由 SDK 按登录账号隔离。未登录：本地保存不受影响；云上传/下载报"请先登录"。云档隔离键=TapTap SDK 内部账号（档名为常量/`slot_N`，未拼 openId）。多设备：SR-3 链有仲裁；存量 slot-0 链无仲裁（上述残留面 2）。
- **隐私**：extra 键全集 = `year, month, sect, disciples, stones, version, saveId, sig, sigVer`（TapTapSaveBackend.kt:351-363）——**无设备标识/个人信息**；payload=完整游戏进度（ProtoBuf+LZ4，非明文但无加密）。
- **容量**：单档上传 ≤10MB（超限 FileTooLarge）；CI 红线 2MB（StorageConstants.kt:82）；下载防御上限 50MB。

---

## 13. 系统层风险：自动备份 / 冷启动 / 换机

- **`android:allowBackup="true"`（AndroidManifest.xml:76）且全仓不存在 `dataExtractionRules`/`fullBackupContent`（检索 0 命中）→ Auto Backup 默认全量范围**：`filesDir/`（含 `saves/`、`.secure_key`/`.secure_key.bak`、`mmkv/`（含上传台账与云摘要））、`databases/`（全部档+迁移前备份）。**密钥在备份范围内**（且密钥绑定 DeviceBindingIdentity 设备因子，换机恢复后密钥文件在、设备因子变 → 云签名链派生行为未验证）。
- 冷启动三路径差异（首次安装/覆盖升级/系统恢复备份）：静态可判定部分——覆盖升级走迁移链+迁移前备份；系统恢复备份后的行为组合（DB+密钥+MMKV 台账跨设备恢复、密钥设备绑定失配、云仲裁账本与云侧实际序号错位）**静态不可判定，[未证实]**（需真机 D2D 恢复实验）。
- 卸载重装/清数据：本地全没（含密钥）；云档在（LEGACY 模式下可通过旧管线下载）。换机：SR-3 链按仲裁下载；密钥不迁移不影响档可读（未加密）。

---

## 14. 逆向清单法产出：未持久化字段 + 三方对账异常项

**未持久化清单**——快照面 = `GameData` 全部 proto 字段（150 个 `@ProtoNumber`，GameData.kt）+ 11 条实体列表 + productionSlots + mails；以下**不持久化**（全部亲自定位）：

| 项 | 持有者 | 后果 |
|---|---|---|
| 待处理战斗结果/奖励卡队列/妖兽来袭 | GameStateStoreImpl.kt:229-232 三条 Flow | 丢"响应入口"，后果已在结算中发生 |
| 弟子聚合/宗门战力 | GameStateStoreImpl.kt:78-88 | 派生态，重算 |
| `activeTab/activeDialog` | GameStateStoreImpl.kt:121-128 | UI 态，重启回首页 |
| `GameData.slotId` | `@kotlinx.serialization.Transient`（GameData.kt:91-93） | 设计性：云档侧恒 0，读档/云恢复显式重钉（CloudLoadOps.kt:224-230） |
| 自动存档旗标 | SaveTriggerFlag 纯内存 | 重启回默认 true |
| RNG 墙钟基线 `systemSeed` | 旧档缺分区按挂钟重播（旧审计 §11#8，本次未重验） | 仅命中远古档 |

**三方对账异常项**：① `android/app/schemas/.../1.json` 孤儿 schema（模块迁移遗留，检索确认存在）；② `recipes` 表=由 `unlockedRecipes` 派生的**只写镜像**（WriteOps.kt:213-215 写、`loadAllEntities` 不读）；③ `save_slot_metadata` 表每存必写但槽位 UI 读的是 `game_data` 行（HeavyDataOps.kt:431-446）——摘要表读面未逐查（可能零读者）；④ 域表五张（diplomacy/production/patrol/worldmap/policy）与 heavy 表**双写同一批字段**（WriteOps.kt:219-268 + :80-108）——heavy 为主、域表为回填兜底（HeavyDataOps.kt:61-90），是**刻意冗余**但构成约 2× 写放大；⑤ 旧审计的 6 张弟子分片冗余表/battleTeam 死列/`@ProtoNumber(162)` 冲突均已随 v52/v53/v58 迁移与守卫测试清零（WriteOps.kt:163-168 注释 + `ProtoNumberUniquenessTest` 断言本次已读，见 §18）；**⑥【重锚新增】`GameData.aiCaveTeams` / `WorldMapStateEntity.aiCaveTeams` 死列已随 `MIGRATION_64_65` 删除**（`GameDatabaseMigrationsV65.kt:25-50`，PRAGMA 重建双表），`StorageEngineWriteOps.kt` 对应写入已移除（该文件现无 `aiCaveTeams` 命中，本次独立复核），字段号 28 双侧 reserved 禁复用。**⑦【重锚新增】**`recipes` 表仍为只写镜像（`StorageEngineWriteOps.kt:213-214` 写、`loadAllEntities` 不读），性质未变。

---

## 15. 后台周期任务与存档的冲突面

| 任务 | 周期 | 动作 | 与保存的竞态 | 删玩家数据？ |
|---|---|---|---|---|
| DataPruningScheduler | 300s | battle_logs 删 7 天前（**墙钟**）跨槽 0..6；change_log 留 7 天；迁移备份裁剪；legacy snapshots 一次性删 | **持槽位写锁**（:195-199），与保存互斥 | **是**：战斗日志超 7 现实天即删（低频玩家回访即丢历史日志，属设计） |
| DataArchiveScheduler | 600s | battleLogs 溢出搬归档表 + **死亡弟子移出主表**（:223 deleteDeadBySlot）；归档行 `dataBlob`=全量序列化**可还原载荷**（:159,200 注释 + 提交 `0f6215981`）；180 天到期删空壳 | 持槽位写锁（:107） | 死弟子出主表（有可还原归档行、但无读回调用者）；**ArchiveConfig.slotIds=(1..6)**（:31，slot 0 不归档——云会话槽，合理） |
| ProactiveMemoryGuard | 10s | 内存压力响应（经 BackgroundTaskScheduler，StorageMaintenanceFacade.kt:23-25） | 不删数据 | 否 |
| StorageMetrics | — | 8 个计数器**无任何 getter**（检索 0 命中）→ 只写不可读 | — | 可观测性缺口 |

---

## 16. 风险清单（按玩家进度损失严重度排序；均已对抗复核，见 §24）

| # | 现象 | 因果链（file:line） | 触发条件 | 最坏后果 | 可逆 | 现有缓解 |
|---|---|---|---|---|---|---|
| R1 | **系统备份把密钥+档全量带走** | AndroidManifest.xml:76 → 无 dataExtractionRules（全仓 0 命中）→ `.secure_key`/`saves/`/`databases/`/`mmkv/` 全在范围 | 用户开启云备份/换机恢复 | 恢复组合行为未验证：账本/密钥/DB 跨设备错位 | 部分 | 无显式缓解；密钥有设备因子派生（行为未验证）【未证实：需真机实验】 |
| R2 | **校验规则物理删数据** | SaveValidator.kt:63-85 → 各清理规则（§9 表） | 下线玩法存量档/异常数据 | 弟子/装备/邮件附件被清除 | 两条有补偿，其余不可逆 | 逐规则单测 + 幂等 + 补偿邮件 + 零抛异常纪律 |
| R3 | **战斗日志 7 天墙钟删除** | DataPruningScheduler.kt:21,189-199 | 8 天未回访 | 历史战报消失 | 不可逆（无归档兜底——归档仅在超 1000 条保存链触发） | 设计行为；槽锁互斥防撕裂 |
| R4 | **存量 slot-0 云链无仲裁覆盖云端** | CloudLoadOps.kt:22-110（下载不经 arbiter）+ 手动上传无条件覆盖 | 双设备同账号+走旧云入口 | 云端较新档被旧档覆盖（云侧） | 若本地仍有新档可回传 | SR-3 新链已修；旧入口仍在生产可达 |
| R5 | **读档 Corrupted 时旧 `.sav` 回滚进度** | Engine.kt:294-303 → restoreFromBackup :339-419（整体覆写 DB） | DB 被误判损坏且 `.sav`/`.bak` 较旧 | 退回上次文件镜像（至多旧一版+） | `.quarantine.{ts}` 保留被覆盖 DB（HeavyDataOps.kt:409-428）供人工恢复 | 隔离+二次校验+迁移；误判本身需 28 规则全红，概率低 |
| R6 | **App 初始化 3 连败静默放行**（旧审计 §12-J，本次未复验现状） | MainActivity.kt:388-417（旧审计行号） | 存储初始化连续失败 | 带病进主菜单 | — | `ensureInitialized` 每次操作重试（StorageFacade.kt:311-316）【未复核最新行号】 |
| R7 | **明文可改档** | §10-11 | 玩家改二进制 | 上限内数值作弊不可检 | — | 28 规则消毒+玉符防刷+签名（仅云、不判篡改） |
| R8 | **文档/UI 漂移** | AGENTS.md:113"5 槽位" vs `DEFAULT_MAX_SLOTS=6`（StorageConstants.kt:20）；退出弹窗"未保存的进度将会丢失"（SettingsTab.kt:351）vs onStop 已自动后台保存（GameActivity.kt:847-885）；SaveOps.kt:56 注释"自动存档每 6 秒一次" vs 实际 10s；GameActivity.kt:855 注释"旗标默认关" vs 默认 true | — | 误导维护者/玩家预期 | — | 无 |

---

## 17. 死代码 / 未接线子系统清单

| 项 | 判定 | 证据 |
|---|---|---|
| DataArchiver.queryBattleLogs/restoreBattleLogs/getArchiveStats | **死代码（查询面）** | 全仓生产检索仅 `archiveBattleLogsIfNeeded` 一个调用点（SaveSupport.kt:97）；ArchiveWriteOnlyGuardTest:56 钉死读面为空 |
| ChangeLogPersistence 读面（getUnsyncedChanges） | **只写不读的半截** | 唯一生产写点 logSaveChanges（SaveSupport.kt:69-81，每次保存 1 行 UPDATE、old/new=null）；读方法无生产调用（检索）；7 天剪除（PruningScheduler:210-214） |
| FunctionalWAL | **接线但非耐久件**：包住事务、不承载数据、recover 只记日志（§7）；CLOUD_ONLY 整体摘除（提交 `46972aeaa`） | Engine.kt:559-578,611-651；WalRetirementGuardTest"调用点总数锁定" |
| StorageMetrics | 只写不可读（无 getter） | StorageMetrics.kt 检索 0 getter |
| `SavePriority` 参数 | 形参保留无人传 | Engine.kt:140-141 `@Suppress("UnusedParameter")` |
| SaveLimitsConfig 配额/分片族 | 旧审计称零外部调用；本次确认 `maxBattleLogs` 被保存链消费（SaveSupport.kt:94），其余 setter/getter 未逐一复验【部分未复核】 | — |
| 云签名链 | **不死**（已接线，§10-11） | TapTapSaveBackend.kt:62 |
| `GameData` / `world_map_state` 的 `aiCaveTeams` 死存储【重锚新增】 | **已删除**（不再是死存储） | `GameDatabaseMigrationsV65.kt:12-23` 自陈"零构造、零读写、C++ 零镜像"；`StorageEngineWriteOps.kt` 现无命中 |

---

## 18. 测试覆盖对照表 + round-trip 缺口

| 风险项 | 测试 | 实际验证内容（读断言判定） | 结论 |
|---|---|---|---|
| 迁移链断裂 | MigrationChainGuardTest（4 用例） | **已读断言**：①链尾 `endVersion == DATABASE_VERSION`（:46-55，版本递增未登记迁移即红）②`ALL_MIGRATIONS` 升序且无重复（:57-61）③首条恒为 `MIGRATION_2_3`（:63-66） | 真覆盖（结构面，**比首稿描述更强**）；行为面由 `RoomMigrationV*_To_*` 系列覆盖，其中 **`RoomMigrationV64To65Test`（重锚新增，168 行）证四件事**：删列精确（多删少删都红）、种子行逐列保留、真实 `Room.databaseBuilder` 升 v65 过 `onValidateSchema`、幂等（:19-24）。其余迁移测试类未逐类读 |
| 规则注册完整性 | SaveValidationRuleRegistryTest（8 用例） | 注册表行为 | 真覆盖 |
| 邮件 wire 完整性 | SaveDataMailWireRoundtripTest（4 用例，逐字段 assertEquals :118-127） | **写→读→全字段比对**（mails 面） | 真覆盖 |
| proto 号唯一性/覆盖 | ProtoNumberUniquenessTest / ProtoNumberCoverageTest | **已读断言**（修正首稿的"凭测试名"）：Uniqueness 扫描 `core/domain` + `core/data` 模型源码，按最内层类作用域统计 `@ProtoNumber(n)` 重复并断言为零，含 reserved 号禁复用用例（机制 :42-43，用例 :46-47）；Coverage 用反射遍历 `GameData`/`SaveData` 全部 `memberProperties`，断言每字段有号或 `@Transient`（:52-55，逃生门表 :40-50） | 真覆盖（**依据已从测试名升级为实读断言**）；**残余缺口**：Coverage 的 `excludedFields` 是逃生门——未来新增字段可被静默登记进该表而测试不红（当前 8 项均为 computed getter，无实害） |
| 归档只写 | ArchiveWriteOnlyGuardTest（"归档表被读到了⇒失效"：56） | 钉死归档读面为空 | 真覆盖（锁定的是"无读者"现状） |
| WAL 调用面 | WalRetirementGuardTest（"调用点总数与分布锁定"） | 防退役残留 | 真覆盖（静态面） |
| 镜像只读 | MirrorReadOnlyGuardTest（在册，符号面）+ DiffAuthoritativeTickTest（行为面） | C++ 权威契约 | 在册（本次未逐行读） |
| 入口决策 / 槽位分发【重锚新增】 | `AutoEntryResolverTest`（新增，141 行）锚定自动进入决策；`SaveMigrationCardTest` 随文件迁入 `feature/game/.../saveload/`；**`SaveSlotDispatchTest`（94 行）与 `SaveSelectCloudSlotsTest`（53 行）已随主界面退役删除** | 自动建档/自动读档/云档优先级的四档判定（`AutoEntry.kt:22-31` 自陈由 `AutoEntryResolverTest` 锚定） | 新增面**真覆盖**（未逐行读断言，依据=测试文件与决策函数同批入库）；**覆盖缺口**：原槽位点击分发守卫随文件删除消失，槽位交互现由 `SaveSlotDialog`（SettingsTab.kt:895+）承担，是否存在等价守卫未逐类核 |
| **round-trip 缺口** | — | 全档级"写→读→全字段比对"测试**未发现**（仅 mails 单面 + SaveDataDirectSerializationTest/ReconcilerTest 局部面）。**新增 GameData 字段若漏 @ProtoNumber 或读侧丢弃，无测试会红** | 缺口确证 |

---

## 19. 文档漂移清单（旧审计逐条裁定）

`docs/save-system-audit-2026-09-21.md`（时点 Room v52）核心条目裁定：

| 旧审计结论 | 裁定 | 当前事实 |
|---|---|---|
| §0"没有存档系统，只有纯手动快照" | **已被推翻** | 现实节拍 10s+onStop+编排器（SaveTriggerFlag.kt:49-50；SaveOrchestrator；GameActivity.kt:847-885；根因提交 `e555b9964`、`4e1c97e53`） |
| §12-B".bak≡.sav 假备份" | **已被推翻** | 轮转真备份（SaveFileManager.kt:154-204；提交 `b35f1d9fb`） |
| §12-C"UI 谎报保存成功" | **已被推翻** | postSaveWarning 全链回传 UI（SaveSupport.kt:146-159；AutoSaveOps.kt:117-145） |
| §12-D"恢复函数是空的" | **已被推翻** | 真恢复（StorageFacade.kt:285-307；同一提交 `b35f1d9fb`） |
| §12-A"heavy 先删后写可永久丢失 exploredSects" | **已被推翻** | skippedHeavyKeysBySlot 保护 + 域表按 key 回填（HeavyDataOps.kt:49-55,74-91；Backfill.kt） |
| §12-F"归档 dataBlob 空壳不可还原" | **已漂移** | dataBlob=全量可还原载荷（DataArchiveScheduler.kt:159-223；提交 `0f6215981`）；但读回调用者仍为零（性质未变） |
| §12-K"clearSlotDataQuietly 只删 2 表" | **已被推翻** | 27 表单实现共用（SaveSupport.kt:248-304，注释自引审计 §12-K） |
| §9"heavy 表在事务外" | **已被推翻** | 外层事务包裹全部写（Engine.kt:624；SR-0 补注实跑收口嵌套语义） |
| §14"加密零调用者" | **已被推翻（云签名链）**/部分仍成立（本地明文） | §10-11 |
| §12-E"@ProtoNumber(162) 重复" | **已被推翻** | 字段随 v58 删除 + UniquenessTest 在册 |
| §12-G"读档 boot 覆盖存档值"（9 行） | **部分仍成立** | 自动收割（LoadSlotOps.kt:142-145）、巡视重建（:64,87-90）仍在；其余 7 行未逐行复验 |
| §12-H"墙钟冷却可前调重复领奖"（宗门周奖励/邮件 30 天删） | 未逐条复验【未复核】；邮件 30 天删除仍在（StorageFacade.kt:192 注释"30 天删除逻辑归 SR-5"） | — |
| §6"退出游戏不保存" | **已漂移** | onStop 触发后台保存（GameActivity.kt:856）；但弹窗文案未更新（SettingsTab.kt:351） |
| §14"SaveValidatorFixes.shouldPersistRepair 零调用" | **仍成立**（仅 logRepairStatus 被调，LoadOps.kt:121） | — |
| §14"StorageMetrics 无 getter" | **仍成立** | §17 |
| §15 存疑 1（嵌套事务） | **已收口**（审计自带 SR-0 补注：room 2.7 SAVEPOINT 合并，测试 4/4 绿） | — |
| §16 修复优先级 1-10 | 1/2/3/4/7/8 已修复（各有对应提交）；5 已根治；6 已以"现实节拍"形态回摆；9 部分仍在；10 部分仍在 | `git log -S` 逐项对上 |

**AGENTS.md / 子规范**：`AGENTS.md:113` 与 `android/core/data/AGENTS.md:28` 的"手动存档（**5 槽位**）"与代码 6 槽不符（StorageConstants.kt:20、SlotLockManager.kt:31）；"现实墙钟节拍自动存档"表述与代码一致。

**【重锚追加】**首稿锚点之后并网的两个提交又新增了两处文档漂移面，需纳入待办：① 存档系统当前值类表述（DB 64、链尾 63_64、五类入口）已随 `adbfe377c`/`8b3e07bf7` 全部过期——本报告已就地重锚，但仓库内其他文档是否同步未知；② `SaveSelectScreen.kt` 已删除，任何仍引用该文件或"主菜单选槽"流程的文档/注释均已失效（本次未全仓普查引用面，属残留未覆盖面）。AGENTS.md 的"5 槽位"错误在重锚时**仍未修复**。

---

## 20. 未证实与盲区（需运行时/装机才能判定）

1. **R8/混淆 × 序列化**：keep 规则静态覆盖面完整（kotlinx.serialization 全保留 :96、`com.xianxia.sect.**$$serializer`/Companion/serializer() 针对性保留 :105-114、枚举 values/valueOf :222-226、GeneratedMessageLite :137-144），但**规则存在 ≠ 规则不漏**——release 实测被本次授权边界禁止，全部结论标 `[未证实]`。
2. **系统备份恢复组合行为**（R1）：需真机 D2D/云恢复实验。
3. **单次保存实测耗时/内存峰值/字节数**：静态推算见 §23；`StorageSystemBenchmark`（test 目录在册）未授权运行完整验证，实测值缺。
4. **引擎线程外是否存在绕过契约的镜像写**（撕裂窗口的最终排除）：需运行时断点/守护验证。
5. 云侧 TapTap 配额具体数值、SDK 内部账号隔离实现：仓外。

---

## 21. 附录 A：关键 file:line 索引（承重结论速查）

GameDatabase.kt:66-81(链，64 条至 MIGRATION_64_65)/95(v65)/136(@Database)/241(绑定)/485-534(迁移前备份)/574(destructive 仅 v1)/763-784(恢复)；GameDatabaseMigrationsV65.kt:25-50；SaveVersion.kt:15；SaveFacadeImpl.kt:78-124；GameStateStoreImpl.kt:218-228/277-287/289-303；GameEngine.kt:208-218；StorageEngine.kt:128-129/142-198/209-246/600-664/339-419/424-461/519-546/559-578；WriteOps.kt:26-55/80-108/111-123/126-148/170-174/219-268；SaveSupport.kt:51-66/93-107/140-180/190-231/248-304；LoadOps.kt:32-49/53-101/109-139/185-218；HeavyDataOps.kt:49-55/57-91/147-177/197-231/299-311/409-428；Backfill.kt:27-67；SaveFileManager.kt:97-152/154-204/223-256/296-334/356-377；StorageCircuitBreaker.kt:174-221；SlotLockManager.kt:25-55/74-104；SaveTriggerFlag.kt:23-60；AutoSaveOps.kt:21-28/58-93/117-162；SaveOrchestrator.kt:55-113；SaveOps.kt:38-88/153-201/209-262/264-334；NewGameOps.kt:19-91/127-187；RestartOps.kt:94-180/255-352；LoadOps VM.kt:81-138/142-161/173-190；CloudLoadOps.kt:22-110/124-230；GameActivity.kt:785-815/825-860/857-894(onStop+后台保存)；MainGameScreen.kt:1296；SaveSelectScreen.kt【**已随主界面退役删除**，原 :128-158/323-353 不可再复核，需 `git show 2f1342ac0:<path>`】；AutoEntry.kt:11-66；SettingsTab.kt:895-1340(SaveSlotDialog)/1063-1074(删档确认)/1208(保存键)；SaveValidator.kt:56-98；Defaults.kt:6-51；28 规则文件（§9 表内行号）；SaveDataVersionMigrator.kt:22-58/64-108/130-133；SaveData.kt:53-96；GameData.kt:86-120；GameHeavyData.kt:46-59；SerializationModule.kt:22-41/45-68；NullSafeProtoBuf.kt:55-65；UploadLedger.kt:33-83；SaveArbiter.kt:43-56；DataPruningScheduler.kt:18-26/132-233；DataArchiveScheduler.kt:23-31/96-119/157-254；StorageMaintenanceFacade.kt:22-28；AndroidManifest.xml:76-77；proguard-rules.pro:83-114/137-144/222-226。

## 22. 附录 B：必答 33 问答案索引

1 DB（§3/§4）｜2 v65/链尾 64_65（共 64 条）/无断点/仅 v1 destructive（§8）｜3 saveVersion=2；高版本档拒载走备份（§8）｜4 六类入口（含云档下载落缓存）；无保存窗口≤11s（§5）｜5 真实存在，10s/1s 轮询/三前置/**无法被关闭**（§5）｜6 恒全量（§23 轴 1）｜7 引用非深拷贝，引擎线程串行缓解（§4）｜8 不会：postSaveWarning 三口径如实反馈（§5/§19）｜9 轮转真备份且真读（§7）｜10 本地明文无签名；云载荷 HMAC 已接线；密钥丢失不影响本地档（§10-11）｜11 全量备份含密钥，恢复行为未证实（§13）｜12 同事务（syncSlotMetadata 在 withTransaction 内，SaveSupport.kt:51-66+WriteOps.kt:159）+ isLoadError 防伪空槽（§6）｜13 §9 表｜14 未发现绕过路径（§9）｜15 IO 协程+Room 专用事务线程，不阻塞主线程（§7）｜16 槽位排他锁（读写同名）+saveLock/loadLock/cloudDownloadLock+boot 守卫；失效场景=进程级锁无跨进程（§5）｜17 SR-3 链不能；存量 slot-0 链能（云侧）（§12）｜18 设备归属、不清档不隔离；多设备 SR-3 有仲裁（§12）｜19 会删 7 天前战斗日志；死弟子出主表；持槽锁互斥（§15）｜20 **最短丢档路径**：重开游戏（保护性预存成功后旧档被新档覆盖——语义即"重置"；预存失败会中止，RestartOps.kt:141-150）；**原「次短=新游戏覆盖确认后覆盖旧槽」路径已随主界面退役消失**（SaveSelectScreen.kt 已删除），自动进入改由 `AutoEntryResolver` 决策，其 KDoc 明确"任何情况下都不允许在本地数据状态可疑时静默新建覆盖"（AutoEntry.kt:28-31，本次已读）；非授权破坏路径=设置页「存档管理」删档确认（SettingsTab.kt:1063-1074 二次确认，不可撤销）｜21 §17｜22 §18 表+round-trip 缺口｜23 recruitList（设计性恒空）外未发现（§6）｜24 mails 面有、全档面无（§18）｜25 §20｜26 增量止步镜像层=**架构决策+历史未接线**（增量基建 SR 期已建、落盘通道从未接；无"曾接后被删"痕迹，`git -S exportDirty` 命中均为镜像侧）（§23/§24）｜27 WAL 罩事务但不承载数据；ChangeLog 只写不读（§7/§17）｜28 归档读面零调用者，成本换来 180 天可还原储备但无人取用（§15/§17）｜29 静态推算：SR-0 实测最大档 0.29MB（StorageConstants.kt:74-79 注释），单次保存 IO ≈ DB 全量重写 + .sav + .bak ≈ 2-3×payload + heavy 双写冗余（§23）｜30 新增一个存档字段 ≈ 6 处：GameData 字段+@ProtoNumber、Room 实体列+MIGRATION+版本递增、（如跨域）守护测试、（如需 UI）ui-read-surface 登记；有守卫测试兜底（GameDatabase.kt:90-93 纪律 + Defaults.kt 注册纪律）｜31 `StorageSystemBenchmark` 在册（core/data test），未授权跑全量；CloudPayloadSizeBenchTest 为 CI 守卫（§20）｜32 形状 b/c 最高性价比轴=**轴 7 崩溃恢复粒度**（WAL 已有壳、只差承载数据）与**轴 1 增量落盘**（dirty 通道已在）——均为"接线"而非"新建"（§23）｜33 §25。

## 23. 能力轴 × 五问矩阵（13 轴全）与瓶颈↔风险双向映射

| # | 轴 | Q1 现状（证据） | Q2 已有基建 | Q3 更优可行性 | Q4 缺口形状 | Q5 量化对照（当前 vs 理论 vs 后期推算） |
|---|---|---|---|---|---|---|
| 1 | 写入粒度 | 恒全量快照（Engine.kt:600-664） | **有**：`dirty_tracker.h`/`column_dirty.h`/`exportDirtyProto`（game_core.cpp:910-953）已服务内存镜像 | 有条件可行：行级 diff 需 Kotlin 侧对账基线，Room 主键结构支持 | b（下层具备、上层没接） | 当前≈全量重写 27 表+heavy；理论上界=脏行增量；后期（千弟子级）全量≈1-3MB/次×6 次/分（基线注①） |
| 2 | 触发与节流 | 10s 节拍+500ms 合并+队列 2s 去抖（§5） | 有（编排器/队列） | 可行（调参级） | — | 6 次/分×(0.3-3MB)；理论下限=事件驱动脏时才存 |
| 3 | IO 放大与体积 | heavy 双写域表（~2×）+.sav+.bak（再 2×） | 有（分块 900KB 上限防 CursorWindow） | 可行（去冗余即减半） | c（具备但冗余） | 0.29MB（历史基线，注①）→ 单存全链 ≈1-2MB 写；后期 2MB 档 → ≈8-12MB/次全链 |
| 4 | 序列化开销 | proto+LZ4 一次编码+一次压缩+SHA-256（§1 复核） | 有 | — | — | 单次 1 份缓冲链 ≈2-3× 序列化体积内存 |
| 5 | 内存峰值 | 引用快照（无深拷贝）+GC 闸（SaveOps.kt:91-123） | 有 takeAtomicSnapshot | — | — | 峰值≈payload×3（<3MB @0.3MB 档） |
| 6 | 并发串行化 | 槽位排他锁、读写互斥、快照引擎线程化 | 有 | 可行（真读写分离需 MVCC，收益低） | — | 保存期间游戏照常跑（引擎线程不受 IO 阻塞） |
| 7 | 崩溃恢复粒度 | 只能回滚到最近全量点（Room 事务）+文件二级 | **WAL 壳已在**（不承载数据） | 有条件可行（WAL 承载增量即可回放） | c（壳在、芯不在） | 当前丢失≤11s；理论上界=0（回放至秒级） |
| 8 | 冷热分离 | 地形冻结（SaveDataTerrainFreezeTest 在册、v51 两列）、配置不进档 | 有 | — | — | terrainTiles 只存一次（v51 注释 GameDatabase.kt:187-189） |
| 9 | 槽位/云去重 | 多槽各存整份；云整档上传 | 有（分块/限频） | 可行 | — | 6 槽 ×0.3MB ≈ 2MB 级 DB，可接受 |
| 10 | 读档成本 | 并行 async 装载+事务内 heavy 合并（LoadOps.kt:196-200,316-343） | 有 | — | — | 线性于档大小；60s 超时上限 |
| 11 | 迁移边际成本 | 新字段≈6 处（附录 B-30） | 守卫测试齐 | — | — | 与头部产品（自动 schema diff）相比中等 |
| 12 | 可测试性/观测性 | JVM 直测纯函数层+守卫测试网；**指标只写不可读** | 有（StorageMetrics 壳） | 可行（补 getter+上报即接） | d（只写不读） | 失败率/耗时无线上可见性 |
| 13 | 扩展性 | 新玩法=实体+迁移+规则+清理清单（守卫钉住 ClearAllSlotTablesCoverageTest 在册） | 有 | — | — | 每系统一次性成本，纪律已自动化 |

**瓶颈→风险**：轴 1+3（全量+双写）→ 写放大/耗时 → 低端机 IO 压力 → （已被 IO 线程+超时缓解）→ 不构成丢档风险；轴 7 → R5 回滚幅度。**风险→瓶颈**：R5 的根治点=轴 7 增量持久化；R3 与轴 1 无关（墙钟语义）。

## 24. 对抗性复核记录（"如果这条错了，最可能错在哪"）

1. **丢档窗口 ≤11s**：最可能错在①节拍循环未运行的场景（MainGameScreen 未组合但游戏在跑——未找到此路径；组合即入口）②旗标被运行时置 false（无任何写入点，检索证实）③保存线程池饥饿（IO 共享池，理论）。排除不了 ③ → 窗口结论保留为"稳态"，故障态窗口=故障时长（已如实写）。**已对抗复核**。
2. **删数据规则清单**：攻击点是"有规则我没读到"——注册表 28 条与 `rules/` 目录 26 个规则文件逐一对应（Defaults.kt:6-51 ↔ 目录清单），外加 RuleContext/Outcome/接口 3 个非规则文件，数目闭合。**已对抗复核**。
3. **云覆盖本地**：攻击点=存量 slot-0 链（找到了，R4）与 IN_SYNC W 未知边界（找到了，§12 残留面 1，降级为 [推断]）。**已对抗复核**。
4. **死代码判定**：`queryBattleLogs` 若被反射/跨模块调用？检索限定 `*.kt`，反射调用需字符串名——再以类名检索全仓（含 xml/assets）无第二命中。判定保留，附检索口径。**已对抗复核**。
5. **瓶颈量化**：全量落盘在 0.29MB 档下"差距显著"不成立 → 按强制前提写入 §25 清单；"增量可省"只在大档（>2MB 红线）才有量级意义。**已对抗复核并反向修正**。⚠️ 该 0.29MB 基线系历史值而非本次实测，证据等级与适用边界见 §25 注①，本条结论受同一限制。

**证据纪律声明**：两个探查代理（Explore）初查范围=§12 云管线与序列化/系统层面；其中进入承重结论的行（Manifest:76、SaveArbiter 全文、Migrator 全文、SerializationModule 全文、NullSafeProtoBuf:55-65、UploadLedger:33-83、proguard:83-114、GameHeavyData keys、OldSaveFormatDeserializer 调用链、SaveBackendMode.shouldWriteLocalSaveFile 消费面）已由主勘者亲自重开复核；未复核的代理事实在文中标"B（代理初查）"或"[未复核]"。测试执行与全部 `git log -S` 考古均为主勘者亲跑。

## 25. "非最优但合理"清单（明确不建议改动）

1. **全量快照落盘**：0.29MB（**注①：非本次实测**）× 6 次/分 ≈ 2-9MB/min 写入，对现代闪存可忽略；增量化的隐性代价（基线漂移/部分写不一致/版本兼容复杂度）在当前规模下大于收益。**结论强度声明**："不建议改动"只在 0.29MB 这一档规模上成立。
2. **heavy 字段域表双写**：冗余 2× 是"heavy 表损坏时零成本回填"的保险（HeavyDataOps.kt:61-64 明示动机），保费与保额匹配。
3. **读写同一把槽位锁**：单写者模型下排他锁正确且简单（SlotLockManager.kt:62-68 自陈语义）。
4. **`.sav`/`.bak` 文件层保留（LEGACY 默认双轨）**：DB 损坏时的独立恢复源，CRC32C+轮转成本每存一次 ≈2×payload，可控。
5. **游戏内时间与墙钟隔离**（无离线收益、生产槽用游戏绝对年月）：改时区/改时钟零影响，是刻意设计不是缺失。
6. **旗标不可关闭**：回滚臂语义（关=回到纯手动），作为部署期开关存在合理；若要开放给玩家则是产品决策而非缺陷。
7. **战斗日志 7 天墙钟修剪**：日志是活动 feed 不是账本，保留期语义明确（只是 UI 未向玩家声明）。
8. **saveVersion 与 DB 版本不联动**：两轴变化原因不同（数据语义 vs 存储结构），强行联动反而制造虚假耦合。

> **注①（量化基线的证据等级声明）**：§23 与 §25 反复使用的 0.29MB 最大档基线**不是本次实测**——出自 `StorageConstants.kt:73-82` 的注释，而该注释引用的是历史文档 `docs/sr0-recon-report-2026-09-21.md`（SR-0 时点）。首稿以"0.29MB 实测档"措辞使用它，属 C 级证据支撑承重结论，违反提示词零信任纪律；此处登记修正：**由它支撑的"瓶颈不显著、不建议改动"结论仅在 0.29MB 这一档规模上成立**；若后期档接近 CI 红线 2MB（`StorageConstants.kt:82`），必须用一次实测重新评估（`StorageSystemBenchmark` 在册，本次未授权运行）。

---

*报告结束。**重锚时点 HEAD = `be1e1f195`**（首稿锚点 `2f1342ac0` 在交稿前即已过期，漂移清单与残留时点声明见 §1.1）；仅修改本报告文件，未触碰任何源码/测试/配置；仅执行授权的三类单元测试（16/16 绿，无跑红项）；未跑 clean/assemble/lint/detekt，未装机，未新增任何 `-P` 开关；本报告不包含改造方案、路线图或"建议实施 X"。*

*工作区状态说明：重锚期间 `git status` 另有一条**与本任务无关**的并行改动——`android/app/src/main/cpp/gamecore/test/bench/accrual_segment_bench_test.cpp`（积分段门禁判据改为同机归一化比值，对应提交 `be1e1f195`）。该文件 mtime = 01:13:08，晚于首稿落盘时刻 01:13:04，故首稿"`git status` 前后均为空"在其自身时点为真；此改动非本次摸底所致，本报告未触碰它（重锚收尾时该改动已从工作区消失，`git status` 恢复为仅本报告一项）。*
