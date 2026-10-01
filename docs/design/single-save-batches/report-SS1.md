# SS1 实施报告 · 去槽位维度（Kotlin + C++ 同批）

> 批次：SS1 ｜ 派工真源：`TASKBOOK-SS1.md` ｜ 协议：`DISPATCH-ledger.md` §4 + `EXECUTION-PROTOCOL.md`
> 工作区：`C:\Mnzm\XianxiaSectNative-SS1`（`feat/single-save-SS1`，基线 `48f86ef6a` = main 含 SS0）

## 1. 做了什么

### ① 删除（槽位维度整体退役，Kotlin + C++ 同 commit）

| 面 | 内容 |
|---|---|
| Room 实体（26 表） | `slot_id` 列全删；`(id, slot_id)` 复合主键 → 单主键；`slot_id`/`slotId` 索引删除 |
| 五张单行状态表 | `diplomacy_state`/`production_state`/`patrol_state`/`world_map_state`/`sect_policy_state` 主键原为 `slot_id` 本身 → 改恒定单行主键 `id: Int = 1` |
| game_heavy_data | 主键 `(slot_id, data_key)` → `data_key`；`chunk()`/编解码链 `slotId` 参数全删 |
| proto 编号退役 | `GameData.currentSlot`(3)、`MailEntity.slotId`(2)、物品 8 类 `slotId`(100) → 全部 `// reserved N;` 注记禁复用 |
| DAO（18 文件） | 189 个带 slot 参数的方法去参；SQL 全部去 `slot_id = :slotId` 谓词；`getIdsBySlot`/`deleteAllGlobal`（全仓零调用死方法）删除；撒谎命名随去槽更名（`getBySlot→get`、`deleteBySlot→deleteAll`、`getDeadBySlotSync→getDeadSync`、`getAllForSlotSync→getAllSync`、`countBySlot→countAll`、`clearAllSlotTables→clearAllTables` 等） |
| 仓库接口 | `RepoInterfaces.kt` 35 处 `slotId: Int = 0` 归零；`MailRepository` 同型去参 |
| 存储引擎 | `StorageEngine.save/load/delete/hasData` 去 slot 形参与 `isValidSlot` 卫兵；`_currentSlot`/`setCurrentSlot`/`getCurrentSlot` 三件套删除；`skippedHeavyKeysBySlot` Map → 单档 `skippedHeavyKeys` Set |
| SlotLockManager | 按槽锁数组退化为单存档互斥锁（验收⑦；`LockStats`/多槽锁 API/`isValidSlot`/`getMaxSlots` 随维度消亡删除） |
| 文件层（D-3） | `slot_N.sav/.bak/.sav.tmp/.deleted` → `save.sav/save.bak/save.sav.tmp/save.deleted`；`SaveFileManager` 方法去 slot 参并更名（`deleteSlot→deleteFiles`、`markSlotDeleted→markDeleted`、`isSlotDeleted→isDeleted`、`clearSlotDeleted→clearDeleted`、`verifySlot→verify`） |
| WAL 线格式 | 条目头去掉 `slotId(4B)` 字段（27B→23B），codec 读写偏移同步；`TransactionRecord.slot`/`RecoveryResult.recoveredSlots/failedSlots` 集合 → `recoveredCount/failedCount` 计数 |
| 入口透传 | `MainActivity.EXTRA_SLOT`、`GameActivity.KEY_CURRENT_SLOT`、`AutoEntry.LoadLocal(slot)`、`SaveLoadViewModel.loadGameFromSlot(slot)`（拆分 `loadGameFromLocalSlot()` + `downloadCloudSlotToLoad()`）、`startNewGame(sectName, slot)` 全部去槽 |
| 死类退役 | `GameStateRepository`（唯一消费点 = Store 的 `setActiveSlot` 槽激活写，SS1 去槽后归零）整体删除；`TestStateStoreSupport.testGameStateRepository` 助手随删 |
| C++ | `models.h` GameData.`currentSlot` + 物品 7 类 `slotId`、`inventory.h:541`、`month_settlement.h` 关闭草稿归属槽、`secret_realm_settlement/residual_tx` 归属槽、`json_codec.cpp` 15 处 `GC_TO/GC_FROM`、`dispatch_w4c.cpp`/`execute_dispatch.cpp`/`game_core.cpp` 回传字段——**与 Kotlin 同 commit**（验收③） |

### ② 伪同形名零误伤（验收⑤⑥）

`SpiritMineViewModel.slotId`、`DiscipleDetailScreen.slotId(part)`、`SlotAssignment.slotId`(String)、`CaptiveGearUtils.slotIdOf`、`ProductionSlotRepository` String id、`patrol_tx.h slotIdx`、`execute_dispatch.cpp:312 currentSlots`、`DiscipleEquipment.slotId(part)`、`ProductionSlot.slotIndex`——全部未动（`git diff` 可核）。
**`Disciple.slotId` C++ 侧（models.h:305-306 注释面）未动**（验收⑥，铁律 16）。

### ③ 保留（非本批范围，登记）

- 云侧槽命名（`mnzm_v2_slot_N`、`UploadLedger` 按槽键、`CloudSaveCacheWriter.downloadIntoCache(sourceSlot, targetSlot)` 跨槽迁移链）——归 **SS7**「台账键随槽维度坍缩为单键」收口；本批仅把其本地落盘调用 `storageFacade.save(...)` 去槽。
- `SaveSlot`/`SlotMetadata` 数据类、`SaveSlotMetadata` 表、`StorageFacade.getSaveSlots` 的云卡位投影、存档管理弹窗 UI——归 **SS4**「单档 UI 终态 + 元数据表清除」。

## 2. 验证（门禁实跑数值）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（全模块） | BUILD SUCCESSFUL |
| 桌面 C++（cmake + ctest，llvm-mingw/clang 22.1.8） | **1523/1523 全绿**（本轮工作树重配构建；含去槽后重录的 2 个 secret_realm 用例断言改造） |
| 桌面 JNI 重建（`build-desktop-jni.ps1`） | 259 源文件同源指纹再生成功 |
| JVM 五模块（`--max-workers=1` + `-Dgamecore.jni.path=SS1 worktree .so` + `--rerun-tasks`） | **BUILD SUCCESSFUL**：domain 1594/0 · data 666/0（15 skip）· engine 3022/0（5 skip）· feature:game 972/0 · app 1016/0（2 skip）——合计 **7270 用例零失败** |
| detekt（domain/data/engine/feature:game/app 五模块） | BUILD SUCCESSFUL（新增违规全部实修：unused imports、UnusedParameter/UnusedPrivateProperty、`TooGenericExceptionThrown/UseCheckOrError`、`MaxLineLength`、`ReturnCount/RethrowCaughtException/TooGenericExceptionCaught` 以函数级 `@Suppress` + 理由注释处理） |
| `check-jni-count.mjs` | 87/87 ✓ 双桥无扩散 |
| `gen-action-ids.mjs` 零漂移 | 重生成后 `git diff` 零内容差异（仅行尾噪音，已还原） |
| `check-agent-instructions.mjs` | EXIT=0 全绿 |

### 终轮门禁（提交前）

- 五模块 JVM 全量 `--rerun-tasks`：**BUILD SUCCESSFUL，7270 用例零失败**（上表）；Diff 三件套（`DiffAuthoritativeTickTest`/`DiffInventoryTest`/`DiffSpiritFieldTest`）全绿。
- 演进过程记录：第四轮 app 面 2 例失败（`DiscipleMergeCoverageTest` 分类清单含 slotId / `TransactionRngRollbackTest` 失败注入点断言）与 feature:game 面 3 例失败（mock 链槽参残留）——全部为测试面收尾遗漏，实修后终轮归零；无生产代码返工。

## 3. 旧用例处置表（去槽波及的测试改造）

| 用例/夹具 | 处置 | 说明 |
|---|---|---|
| `SlotLockManagerTest` | **重写** | 按槽 API 消亡 → 单锁契约（排他性/读写同锁互斥/全局锁独立）四用例 |
| `SaveFileManagerTest`/`Sdk33Test` | 改造 | 夹具文件名 `slot_N.*`→`save.*`；调用去 slot 参；`isValidSlot` 卫兵用例删除 |
| `MailSnapshotStoreTest` | 重写 | 槽位隔离语义消亡 → 全量快照 + 整对象替换 + 空表三用例 |
| `GameDataDao`/`GameDataRepositoryImpl` 面 | 改造 | `getMetadataBySlot→getMetadata`、`existsBySlot→existsAny`、删档清单去参 |
| `CacheKeyTest` | 改造 | `forGameData()` 无参 + slot 恒 0 |
| `OverflowMailSenderTest` | 改造 | 分组键 `(slotId, source)` → `source` 单键；确定性 mail id 种子去槽；`buildOverflowMail` 去槽参；`PersistedOverflowDraft/PersistedDirectMailDraft` 构造去槽位 |
| `ArchivePayloadRoundTripTest` | 改造 | Disciple/BattleLog 夹具去 `slotId`；**根因修复**：BattleLog.`timestamp` 上错挂的 `@ColumnInfo("slot_id")`+`@Transient`（脚本迁移孤注）摘除，时间戳恢复入 proto 序列化——往返等价从此含时间戳 |
| `EquipmentStackRemovalGuardTest` | 改造 | 白名单移除 `GameStateRepository.kt`（本批退役，守卫 KDoc 明示"随实际清理进度重写本守卫"） |
| `ClearAllSlotTablesCoverageTest`/`CacheWriteAtomicityGuardTest`/`MailSnapshotWritePathGuardTest` | 改造 | 静态锚点随 `clearAllTables`/`clearOldEntities`/`replaceMails` 更名与去参同步；**守卫宽严不变**（双向相等 + 单事务 + 不吞异常断言原样） |
| `MigrationRequiredGuardTest` | 注记更新 | 实体清单不变（27 实体全保留）；基线注释 → v67 |
| `WALDataClassesTest` | 改造 | `RecoveryResult` 集合 → 计数；`TransactionRecord` 去 slot |
| `BootSequenceControllerTest`/`GameEngineCoordinationTest`/`GameEngineLoadTest` 等 | 改造 | `boot(slot=1)`/`createNewGame(x, 1)`/`restartGameSuspend(x, 1)`/`loadGameFromSlot(0)`/`saveGame("1")` 调用面去槽；云下载入口改名对齐 |
| `AutoEntryResolverTest` | 重写 | `LoadLocal` 无槽号载荷；多槽选取用例收敛为"有本地档即读" |
| `DiffExecuteTest` | 改造 | 夹具 JSON 去 `"slotId"` 键（Kotlin 反序列化面已无该字段） |
| `StateRevertRegressionTest`/`GameStateStoreRollbackTest`/`TransactionRngRollbackTest` | **注入点迁移** | 失败注入从 `repository.setActiveSlot/loadFullState`（已退役）迁移至 `RngSnapshotPort.restore` 抛异常（finalizeLoadedState 段）；回滚断言（逐位一致/RNG 对齐/弟子表恢复）全保留 |
| `DiffInventoryTest`/`DiffAuthoritativeTickTest`/`DiffSpiritFieldTest`（验收④） | **全绿**（行为面对拍，两侧同批删键后快照协议对称；无录制基线红点） |

### B 类红登记（§4.2 安排①）

- **本轮无 B 类红**。对拍三件套（`DiffAuthoritativeTickTest`/`DiffInventoryTest`/`DiffSpiritFieldTest`）终轮全绿：导出形状变更在 Kotlin 镜像与 C++ 真源两侧同 commit 对称落地，运行时行为面无录制基线可漂移。跨语言录制的 `DiffBridgeSourceSyncGuardTest`（.so 指纹 ↔ 工作树逐字节）随 JNI 重建刷新指纹后绿。
- 录制型金样的正式重录窗口按 §4.2 设在 **SS9 完成时**（与 SS9 账本字段变更共用唯一窗口），本批不占用。

## 4. 关键决策落地

- **D-1（无迁移）依据**：SS0 删档重置已使全部存量库失效（v65 及更早无保护价值，SS0 报告验收⑥实测 wiping 生效），本批不存在"需要迁移的旧库"⇒ 不写迁移 SQL；`DATABASE_VERSION` **66→67** 保证同版本 schema 漂移不触发 Room identity-hash 崩溃，v66 库由 `fallbackToDestructiveMigration(dropAllTables=true)` 整体重建（与 SS0 语义一致）。铁律 17 的"列变更必须带迁移"由任务书 D-1 显式豁免本批；此后批次恢复迁移纪律。
- **D-2/D-3/D-4/D-6** 按任务书落地（单主键 / 文件名去槽 / 双侧同 commit / Disciple C++ 侧不动）。
- **D-5**：伪同形名守卫落点 = 既有 `EquipmentStackRemovalGuardTest` 白名单机制 + 本报告 §1② 清单；机械替换全程以"排除清单先行"执行，未发现误伤。
- 版本号：`DATABASE_VERSION` 66→67；`version.properties` 未动（`4.2.00` 由 SS10 统一收口，本批不越权）。

## 5. 未完成 / 登记

1. **SS9 登记**：玉符账本字段设计不再需要槽维度；正式重录窗口与 SS9 共用（§4.2 安排①），本批已占形状变更位。
2. **SS4 登记**：`SaveSlot`/`SlotMetadata` 数据类、`save_slot_metadata` 表、`getSaveSlots()` 云卡投影、存档管理弹窗槽位 UI、`StorageFacade.getSaveSlotsSuspend` 收敛——全部归 SS4。
3. **SS7 登记**：云侧槽键（`mnzm_v2_slot_N`/`UploadLedger` 按槽键/`downloadIntoCache(sourceSlot, targetSlot)` 跨槽语义/`stubDownload` 测试面）保留至 SS7 坍缩为单键；本批仅去其本地落盘槽参。
4. **云读档链注释语病**：`SaveLoadViewModelCloudLoadOps` 等 KDoc 中"槽位 0 云镜像/本地 1..6 零影响"表述为退役语义残留（行为已单档化），SS4 UI 终态批随文档统一收敛；本批未逐条重写以控制爆炸半径。
5. **`StorageFacade.getSaveSlots()` 投影**：本地行 `SaveSlot(slot=1)` 为 UI 列表过渡投影，SS4 收敛为单档卡片时删除 `slot` 字段。
6. **风险（已核实）**：`BattleLog.timestamp` 恢复 proto 序列化（摘除错挂 `@Transient`）——归档载荷从此含时间戳；对已归档旧 blob 的读取兼容（旧 blob 无该字段 → 解码取 `currentTimeMillis` 默认值）与既有"归档不可还原载荷"历史缺陷同源，B3 批已有 dataBlob 全量序列化修复，本批不另处理。
7. **风险（已核实）**：WAL 线格式 27B→23B——WAL 为会话内功能日志（SR-7 下 CLOUD_ONLY 不启用），无跨版本持久兼容面；`FunctionalWALEntryCodec` 读写偏移对称改移，ctest 全绿。

## 6. 途中发现（不自作主张处置）

- `GameDatabase.kt` 顶部 `MAX_BACKUP_GAME_DATA_ROWS` 注释仍写"每槽一行，正常 ≤ 7"——语义随单档失效，SS4 元数据清理时一并修正。
- `EquipmentStackRemovalGuardTest` 白名单中 `AlchemySystem.kt`（旧 ForgeRecipe DTO 装备字段）等条目仍按 B3/B4 节奏走，与本批无冲突。
- `SaveSlotSubmitActions`（SettingsTab）仍以 `it`（SaveSlot）分发云/本地——SS4 弹窗收敛时的现成入口。
