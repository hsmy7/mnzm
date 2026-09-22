# SR-7 完成报告 —— 文件层退役代码就位 + Room v53 schema 第二刀 + 守卫全家桶

> 日期：2026-09-22　批次：SR-7（方案 §4 末批，用户「`docs/save-system-refactor-plan-2026-09-21.md` 新开分支完成第 7 批」直接驱动）
> 施工卡：[batch-SR7.md](batch-SR7.md)　权威依据：
> [docs/save-system-refactor-plan-2026-09-21.md](../save-system-refactor-plan-2026-09-21.md) §0 D1/D2/D5/D7、
> §2 模式开关、§3 IN1–IN8、§4 SR-7、§5 门 1–6
> 分支：`w5/sr7-file-layer-retirement`（基线 = SR-6 头 `38a987537`）　状态：**delivered（`accepted` 归用户）**
> ⚠ **本批不声称"已切换 CLOUD_ONLY"**——切换的前置门未满足（§2），本批交付的是终态**代码就位**。

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| Room v52→v53 | ✅ 6 张零读者镜像表删除；`53.json` 与 `52.json` 表集差集**恰为该 6 张、零其它漂移** |
| 领域类保/删判定 | ✅ 5 类保留为纯领域类型（`DiscipleAggregate`/`DiscipleStatCalculator` 实测在消费）；`DiscipleCompact` + `DiscipleAggregateWithRelations` 零消费者 ⇒ 连类删（IN6） |
| crypto 第二刀 | ✅ 实测只剩 2 个死壳并删除；`.secure_key` 网络签名链一环未动 + 新守卫三面锁 |
| 文件层退役 | ✅ **按 `CLOUD_ONLY` 门控落地**：停写 `.sav`/`.bak`/tombstone，旧 `.sav` 转**只读**应急源；组件本体物理删除留给清理批（理由 §2） |
| `FunctionalWAL` | ✅ 两个"产生文件写"的入口按同一判据摘除；其余三处靠既有 `txnId != null` 短路；组件保留 |
| 归档判归（C6） | ✅ 判归完成（§6），零代码改动 + 一条证据锁守卫 |
| 守卫全家桶（C7） | ⚠ IN1/IN5/IN8 三处**实测为缺口**已补；IN7 与 IN4 扫描面缺口**未补**，登记于 §8（不假装有） |
| 前置门 | ❌ **未满足**（SR-3 真机 8 项 / SR-6 完成率无分子分母），本批因此在模式门控下交付 |
| 真机 | ⏸ §7 pending-device 逐项登记，未声称任何真机达标 |

规模：9 笔提交（含 1 笔跨会话补偿，§9）；`android/` 面净增测试 4 个守卫类 + 1 个迁移测试类。

---

## 1. 交付链

| # | 子项 | commit |
|---|---|---|
| C1 | 施工卡 + 台账 SR-7 in_progress | `cba8ae6cb` |
| C1b | `syncSlotMetadata` 重复调用自纠（既有缺陷，非本批引入） | `3902a8f73` |
| C2 | Room v52→v53 schema 第二刀（6 表 + DAO + 写侧 + 领域类收口 + 迁移测试） | `e9bbeaa5a` |
| C3 | crypto 第二刀收尾 + `SecureKeyChainGuardTest` | `87f1aeeb8` |
| C4 | 文件层退役判据（停写 + 只读应急源 + 三态门控测试） | `4c4fa3090` |
| C5 | `FunctionalWAL` 按模式摘除 + `WalRetirementGuardTest` | `46972aeaa` |
| C7 | IN1/IN5/IN8 守卫补口 + 归档零读者证据锁 | `029d827bd`（其中 4 文件被并发会话纳入其 wip 笔）+ `9b982fb5f` ⇒ 见 §9 |
| C8 | 门禁实测 | 本报告 §5 |
| C9 | 文档三件套 + 本报告 | 本笔 |

---

## 2. 前置门核验与本批口径（为什么"退役"不等于"已删"）

方案 §4 SR-7 自述切换前置门 = **SR-3 真机稳定 + SR-6 迁移完成率达标（阈值 SR-6 定）**。开工时实测：

| 前置 | 实测 |
|---|---|
| SR-3 真机稳定 | ❌ SR-3 报告 §7 的 8 项 pending-device 全未跑；SR-4 六项、SR-5 六项、SR-6 八项同样挂起 |
| SR-6 迁移门槛 | ⚠ 门槛**代码**已就绪（SR-6 的 `SaveMigrationLedger` + `canPromoteToCloudOnly` + `SaveMigrationGuardTest` 钉住"CLOUD_ONLY 生产零写入"），但"完成率达标"是运营量：`#save_migration_result` 的 TapDB 事件录入仍待运营 ⇒ 无分子无分母 |

由此定本批口径（三条，写死在施工卡 §0）：

1. 终态代码一律按 `mode == CLOUD_ONLY` 门控落地；`LEGACY`/`CLOUD_TRANSITION` 逐位零变化。
   `CLOUD_ONLY` 生产写入点仍为零 ⇒ **本批不产生任何玩家可见变化**。
2. **文件层组件本体不物理删除**（`SaveFileManager`/`SaveFileFormat`/`.bak` 轮转/`FunctionalWAL`/
   tombstone/quarantine）。理由不是保守，是方案自己的 ADR：D5 写明轮转备份"在 CLOUD_TRANSITION
   过渡期继续保护玩家，最终**随文件层一起**退役"，而今天全部设备模式恒 LEGACY——现在删就是在
   零云迁移实绩的设备群体上抽掉唯一还在生效的兜底。本批交付"退役的代码就位"，不是"退役已发生"。
3. 真机升级路径实测（v52 真档 → v53）= 方案 §5.5 硬门，本环境不可测 ⇒ 逐项 pending-device（§7）。

**判据实现**：`shouldWriteLocalSaveFile(mode) = mode != CLOUD_ONLY`（纯函数，与 SR-2
`shouldEnqueueCloudUpload` 同族）；`StorageEngine.writesLocalSaveFiles` 每次现读不缓存
（模式由玩家在迁移卡确认后才升，缓存会把"已升档但本会话仍写文件"变成静默不一致）。

**刻意不门控的三处**（防"顺手删干净"造成新缺陷）：
- `delete` 路径的 `deleteSlot` / `clearSlotDeleted`：它们是**删除**动作，负责清掉升档前遗留的
  `.sav`/`.bak`/`.deleted`。遗留文件不删，日后 DB 损坏时会被 `restoreFromBackup` 当应急源复活
  ⇒ "删掉的档又回来"。只门控 `markSlotDeleted`（新建 tombstone 才是文件层写）。
- `core.wal.shutdown()`：拆除路径无条件跑，否则会话中途升档会漏掉句柄释放。
- `cleanupOrphanedTmp` / `cleanExpiredBackups`：N 版保留期后的收敛正是靠它们。

---

## 3. 勘察对方案字面的三条纠正（均已入施工卡与台账）

1. **"删 6 表"≠删 6 类。** `DiscipleAggregate.kt:8-12` 持有五字段、`:417-421` 由
   `Disciple.fromDisciple` 构造（不经 DB），`DiscipleStatCalculator属性Ops3.kt:125/137` 读
   `combatStats`/`attributes` 算方差与技能输入 ⇒ 5 个类保留、只去 Room 注解；
   仅 `DiscipleCompact`（剪掉唯一写入点后全仓零消费者）与 `@Embedded` 关系类
   `DiscipleAggregateWithRelations` 连类删除。`DiscipleDaos` 分组随之失去意义，
   `GameStateRepository` 改直注 `DiscipleDao`，`AppModule` 删 6 个 provider + 1 个聚合 provider。
2. **`FunctionalWAL` 调用点实测 6 处，方案写 5 处。**
3. **crypto 第二刀实测只剩两个死壳**：`SaveCryptoKeyCache`（唯一外部调用者是
   `XianxiaApplication:128` 的 `initialize`）+ `StorageConfig` 的 `cache_derived_key`/
   `key_cache_duration_ms` 两面。真正的存档载荷加密码早在 `5a421f3d6` 切掉了 ⇒ 本批是收尾清壳。

表零读者的取证（本会话自己 grep，非引用前批结论）：六个 DAO 访问器全仓命中仅
`deleteAll`（三处清槽清单）/`upsertAll`·`insertAll`（唯一生产者 `writeDisciples`）/DI provider；
SELECT 方法零调用者；C++ 侧六个表名 0 命中。

---

## 4. 各子项实施要点

### C2 schema 第二刀

- `MIGRATION_52_53`：`DROP TABLE IF EXISTS` ×6（幂等，索引随表消失；删表场景无需
  create-copy-drop-rename）。表名清单 `V53_DROPPED_TABLES` 提为文件级 `internal val`。
- **删表不丢玩家数据的论证是结构性的**：六表唯一生产者逐行走 `X.fromDisciple(d)`
  （零外部输入）⇒ 纯派生副本；读侧一直走 `disciples`（保留表）。
- `RoomMigrationV52To53Test` 5 例：真实 Room 校验（v52 → `ALL_MIGRATIONS` → v53）、
  **精确删表集**（迁移后表集合 == 迁移前 − 6，多删少删都红）、真相表 `disciples`/`game_data`
  逐字段等价（行数 + 列清单 + 每值含 NULL 语义）、幂等重放、`53.json` 静态防回流。
- **判别力自证（本批最重要的一次自纠）**：初版的期望值引用生产常量 `V53_DROPPED_TABLES`。
  把迁移改成"少删 `disciple_compact`"后实测：**5 例里 3 例判红，但
  `passes real Room schema validation` 与 `is idempotent` 两例仍绿**
  ⇒ Room 的 schema 校验对"库里多出未声明的表"只告警不判错。既然共用常量会让守卫空转，
  期望面改为测试侧独立字面清单 `EXPECTED_DROPPED_TABLES`，并加
  `migrationListMatchesIndependentExpectation` 钉"生产清单 == 独立期望"。
- 历史链零触碰：`V2ToV10`…`V52` 一字未改（其中 `ALTER TABLE disciples_*` 在 v53 之前执行）。
  `RoomMigrationSupport` 里两条"镜像表列存在"断言的宿主链**止于 v40**
  （`RoomMigrationTest.kt:534-543` 的清单末项是 `M39_40`），故保留原样并在 KDoc 注明适用版本——
  我一度误把它改成 v53 终态断言，撤销后重新按版本口径写注释。
- `ClearAllSlotTablesCoverageTest` 双向相等守卫自动收敛（26 清 / 27 声明），
  防解析失配的 `>= 30` 阈值下调为 `>= 25` 并注明理由。
- `DomainDependencyTest` 的 `core:domain` Room 白名单收缩 7 项（只缩不增）。

### C3 crypto 收尾 + 签名链守卫

`SecureKeyChainGuardTest` 三面锁：四件套源文件在位 + `.secure_key` 文件名常量未变；
`SavePayloadSigner` 仍以 `SecureKeyManager.getOrCreateKey` 为 master 源（**这条依赖断了不会
编译失败，只会静默签不上云档**，只能靠守卫）；5 个死壳 token 不得回流生产源码。
该守卫首跑即抓到本会话漏删的 `StorageConfig.setCacheDerivedKey`（零调用者写入器）⇒ 非空转。

### C4/C5 文件层与 WAL 门控

- `readWithFallback` 新增 `readOnly` 参数：CLOUD_ONLY 下跳过"用 `.bak` 覆盖 `.sav`"的修复性
  写回（该方法内唯一的写点），读/校验/回退语义逐字不变 ⇒ "旧 `.sav` 转只读应急源"字面落地。
- WAL 只门控两个入口（`beginTransaction` / `recover`）：`txnId` 保持 null 即让既有 null 守卫
  把 commit/abort×2 自动短路，不必四点各判一次。`recover` 一并门控是因为扫描会创建 `wal_v4` 目录。
- `SaveFileManagerTest` 的只读用例带**非只读对照面**（默认路径必须真的写回），
  否则"未写回"断言可能在空转。
- 自曝：只读用例首跑判红，原因是夹具取了 `slot = 7` 而 `DEFAULT_MAX_SLOTS = 6`
  （`isValidSlot = 0..6` 直接短路成 CORRUPTED）——夹具取号错，非产品缺陷。

---

## 5. 门禁实测

（判绿口径 = XML `executed` 计数 + 时间戳，不看 `BUILD SUCCESSFUL`；见 §5.2 的树指纹）

### 5.1 分模块（施工期逐笔实跑）

| 门 | 数字 | 取证 |
|---|---|---|
| `:core:data:testReleaseUnitTest` | **827 例 / 0 失败 / 0 错误 / 15 既有跳过** | XML 11:30:25Z 与 11:25:44Z 两轮窗口，本批累计 +12 例 |
| `:core:engine:testReleaseUnitTest` | **3415 例 / 0 失败 / 0 错误 / 0 skip** | 含 `Diff*` 家族 **274 例 / 0 skip**（IN8 实证） |
| `:core:domain:testReleaseUnitTest` | **1743 例 / 0 / 0 / 0** | XML 11:59:02Z 窗口 |
| `:app:compileReleaseKotlin`（Hilt 图） | 绿 | 6 个 provider 删除 + 1 个新注入参数 |

### 5.2 六模块组合门 + ctest + 存档回归

**（2026-09-23 凌晨由验收会话补跑回填——C8 收口，三轮实录见 §10）**

| 门 | 结果 |
|---|---|
| 六模块组合门（`testReleaseUnitTest detekt compileReleaseKotlin lintRelease`） | ✅ **隔离树判绿**：六模块 **8,089 executed / 0 失败 / 0 错误 / 17 skip**（skip = 基线 17 零新增：app 2 + core:data 15；分模块 app 1005 / core:data 827 / core:domain 1743 / core:engine 3408 / core:ui 146 / feature:game 960）；709 个测试 XML，判绿窗口 01:01–01:06（2026-09-23）；detekt 六模块绿（R1 判红 9 处经 `863fbc4cc` 独立笔修复后）；`lintRelease` 六模块绿；`BUILD SUCCESSFUL` / `GATE_EXIT=0` 收官（346 任务，18 executed + 328 up-to-date） |
| **`Diff*`（IN8）** | ✅ **52 类 278 例 / 0 skip / 0 失败**（`DiffBridgeGateTest` 在门内；对拍桥为判绿轮前 fresh 重建） |
| 桌面 `ctest` | ✅ **1560/1560，0 失败，93.71s**（C++ 测试树 `ninja: no work to do` = 二进制即当前源码构建；C++ 面自地图 S6/S7 后零变化，本批零 C++ 改动） |
| 存档回归逐字段等价 | ✅ `RoomMigrationV52To53Test` 5 例在组合门内实跑：v52 → `ALL_MIGRATIONS` → v53 真实 Room 校验、精确删表集（测试侧独立字面清单）、真相表 `disciples`/`game_data` 逐字段等价（含 NULL 语义）、幂等重放、`53.json` 防回流 |
| 树指纹（判绿轮所测内容） | 判绿轮 = 隔离 worktree `C:/Mnzm/XianxiaSectNative-accept-gate` @ `863fbc4cc`（`TREE=e2c0e63012016716fe8fc822da155ec6f7ccad3b`，含本批 C1–C7 全部 + SR-6 收官笔 `e26840387` 合并 + 地图 S1–S7 线）；19:52:30 旧指纹（`e62f4c55…` = `9b982fb5f`）为实施会话起跑未落盘之轮，作废经过见 §10 |

**与 §5.1 的关系**：§5.1 分模块数字（827/3415/1743）为实施会话逐笔自检，未含 detekt/lint 面；本表为覆盖全部分门的收官实证。引擎 3415→3408 的 −7 为地图 S6 崖壁测试退役净差，非本批回归。

---

## 6. C6 归档任务判归重审（方案 §4 SR-7 第三条）

**判归：`DataArchiveScheduler` 的归档产物在云唯一终态下不可达，判"随文件层一起退役"；
`DataPruningScheduler` 的三项本地清理仍必要，保留。本批零代码改动，只加一条证据锁守卫。**

取证（本会话直接核实）：

| 事实 | 证据 |
|---|---|
| `archived_battle_logs` / `archived_disciples` **零读者** | `core/data/.../archive/ArchiveDaos.kt` 全文只有 `@Insert` 与 `@Query("DELETE …")`，零 `SELECT`；主源码 grep `FROM archived_*` 0 命中 |
| 归档内容**不进云档 payload** | 快照构造 `SaveFacadeImpl` 全部取自 `stateStore.*Snapshot`（内存），上传面 `SaveMigrationCoordinator` = `storageFacade.load(slot)` + `getMailsForSlot`；两处都不读 `archived_*` 与 `archives/` |
| 文件归档还原路径零调用者 | `DataArchiver.restoreBattleLogs` / `getArchiveStats` / `getTotalArchiveSize` 全仓零调用者；`queryBattleLogs` 唯一调用者是 `restoreBattleLogs` 自己 |
| 无 UI 入口 | `feature/`+`core/ui` 的 `归档`/`Archive` 命中全是 TapTap 云存档 API 名与注释 |
| 修剪仍必要 | `change_log` 7d、`*.pre_migrate_backup.v{N}` 保留 2、`snapshots/` 一次性清理——三者都与"谁是存档真相"无关 |

⇒ **结论**：换设备后本地 `.arc`/`archived_*` 不存在，被归档的战斗日志与死亡弟子**无从还原**。
这带来一个**必须登记但不在本批处置**的缺陷：`StorageEngine.kt:232`
`cleanSaveDataWithArchive` 在落盘**之前**就把溢出战斗日志裁进归档 ⇒ 被裁的日志只活在零读者的
归档里，玩家视角等于静默消失（D2「云档 = 唯一玩家可见存档」被这条本地修剪破坏）。
判归是"随文件层退役"，但**修剪语义要不要改成"保留最近 N 条 + 如实告知"**属产品决策，
本批不夹带（同 §12-G 其余子项的处置口径）。

`ArchiveWriteOnlyGuardTest` 把"零读者"这条判归前提钉成守卫：将来任何人给归档表加读方法，
本守卫即响 ⇒ 判归前提失效要重做，而不是靠人回忆这段推理。

**顺带发现（登记，未改）**：`DataArchiveScheduler.start()` / `DataPruningScheduler.start()`
实测**零调用者**（活路径是 `StorageMaintenanceFacade.kt:24-25` 的 `register(…, 300/600)`），
且 `BackgroundTaskScheduler.kt:39-41` 用 `tick % interval == 0` 而 `tick` 从 0 起 ⇒
两个任务在**启动即各跑一次**，不是方案假设的"300s/600s 之后"。IN6 议题 + 时序事实，
留待用户判归是否单独立项。

---

## 7. pending-device（真机硬门，本环境不可测，逐项不声称达标）

方案 §5.5 对 SR-3 起的云链路批次把真机列为硬门，§4 SR-7 更写明"本批**必须**真机，无可豁免"。
以下六项必须在设备上跑完才算 SR-7 真正交付；本批全部为**未验**状态：

1. **Room v52 → v53 真档升级路径**：真实老档（含六表数据、含死弟子/长战斗日志的长线档）
   升级后能正常进主菜单并 boot；`backupDatabaseForMigration` 是否落了
   `.pre_migrate_backup.v52`；升级耗时与卡顿感（DROP TABLE 六表在真机 SQLite 上的时长）。
2. **降级/回滚真机验证**：v53 数据被低版本 App 打开时的既有恢复链是否仍按预期阻断
   （`shouldRestoreFromBackup` 分支），因为本批删表后旧版本对 v53 库的读会直接崩。
3. **`CLOUD_ONLY` 真实升档后的文件层行为**：目前生产**无法**进入该模式（SR-6 守卫钉住零写入），
   故 C4/C5 的停写路径在真机上尚未走过一次——需要临时 debug 入口把设备推到 CLOUD_ONLY，
   验证：不再产生 `.sav`/`.bak`、DB 损坏时只读应急源仍能救档且**不回写**、删档确实清掉遗留文件。
4. **删档复活剧本**：升档前遗留 `.sav` 的槽位，升档后删档 ⇒ 重启后不得从遗留文件复活。
5. **归档不可达的真实后果**（§6 登记的修剪缺陷）：长玩家（战斗日志 >1000 条）在真机上
   确认"被裁日志在云档里读不回"，量出玩家实际丢了多少条历史。
6. **月月必存 + 停写文件层的组合成本**：SR-4 实测游戏月 = 6 秒真实时间，
   CLOUD_ONLY 下每 6 秒一次全量快照 + Room 事务但无文件写 ⇒ 性能/发热对比数字仍缺。

---

## 8. 遗留登记（本批未做，交用户裁决；不夹带）

**IN 守卫缺口（盘点后确认仍未补，勿当已闭环）**

1. **IN7 boot 只读不写：无守卫**。取证落点：`GameEngineLifecycleOps.kt:60-225`
   （`ensureGameDataIntegrity` 会重生成 `worldMapSects`/`aiSectDisciples:157`/
   `travelingMerchantItems:203,210`/`recruitList:218`，`:225 rebaselineNativeMirror`，
   由 `BootSequenceController.kt:216/570` 在读档后调用）与
   `StorageEngineLoadOps.kt:239-250` `revalidateRestoredData`。既有三个相关测试
   （`SaveDataReconcilerTest.kt:38`、`GameEngineCoordinationTest.kt:73`、`SaveValidatorTest` 数例）
   断言的是"修复会改值"或"跳过分支"，**都不构成"有效档不被改"的判据**。
   本批不补的理由：能补的浅层断言（`reconcileStacks` 二次幂等）在其 `stacksSerialized=true`
   分支上是同义反复，写出来是安全剧场（IN6）；要做对需要 Robolectric 起真实引擎 +
   逐字段对照，量级另立一批。
2. **IN4 wire 唯一性：扫描面有洞**。`ProtoNumberUniquenessTest.MODEL_ROOTS`（:26-31）只覆盖
   `core/domain/…/model` 与 `core/data/…/model`；而 `@ProtoNumber` 还存在于
   `core/domain/.../state/BattleResultUIData.kt` 与
   `core/data/.../serialization/backwardcompat/OldSerializableSaveData.kt` ⇒ 这两处的
   tag 目前绕过唯一性守卫。扩面须先量一次现存冲突数（可能一片红），属独立议题。
3. **IN1 的"后置步骤失败不得回滚本地"半边**仍无守卫（本批补的是"DB 写待在单事务内"那半边）。
4. **IN6 无通用"零调用者"扫描**，目前只有逐案清单（本批新增 `SecureKeyChainGuardTest`
   与 `ArchiveWriteOnlyGuardTest` 两处）。`RedeemCodeManager.validateCodeWithServerAuth` +
   `remoteValidator` 注册链（SR-5 §8 条 3）仍开放。

**其它登记**

5. `.quarantine.*` 文件**无任何清理消费者**（`StorageEngineHeavyDataOps.kt:386` 造，
   `DataPruningScheduler` 不清）⇒ 无限增长风险，`StorageEngine.kt:396` 的注释说"维护任务按
   保留期清理"与实测不符（注释不作证据的又一例）。
6. 方案 §4 SR-7 的"旧 `.sav` 转只读应急源保留 **N** 个版本后清理"：N 本批定 3 并在施工卡
   §1 条 10 登记理由，但**物理清理动作随组件本体一起留给清理批**，今天没有执行者。
7. SR-5 §8 条 1（`WallClock` 落点 ⇒ 存档/模型时间戳族未收敛）与本批无交集，仍开放。
8. `SaveMigrationGuardTest` 的"迁移判定族零时钟"清单为 5 文件，**不含 `SaveArbiter.kt` 本体**；
   IN2 的仲裁核心因此靠 `SaveArbiterTest` 的"相同三元组判定与时序无关"例兜，
   而该例在同进程重放 ⇒ 抓不到墙钟读取。建议把 `SaveArbiter.kt` 并入那份清单（一行改动，
   属 SR-2/SR-6 家族，本批不越界改）。

---

## 9. 跨会话并发事件（如实登记，与本批代码无关但影响提交归属）

同一工作树内存在多路实施会话，本批施工期发生三次 HEAD/提交权碰撞，全部如实记录：

1. **18:13** SR-6 会话在 `w5/sr6-cloud-migration` rebase 收口，期间本会话首次读到的 HEAD 是
   `122f43dea`（rebase 中途基态）。本会话据过期快照向用户提问被驳回 ⇒ 已固化为记忆纪律：
   **环境状态须同轮直查**。
2. **18:51** 本会话分支被切走至 `bianyuan`（与本会话头同哈希），脏改动随 checkout 保留；
   本会话切回 `w5/sr7-file-layer-retirement` 后继续。
3. **19:42 / 19:50** 另一路会话在**本会话分支上**提交 `029d827bd wip(sr7)`，把本会话
   C7 的四个文件连同三样非本批内容一起入库：`docs/save-system-refactor-plan-2026-09-21.md`
   （SR-0..SR-6 一致口径为**方案文档 untracked 不提交**）、`docs/memory-audit-2026-09-22.md`
   （第三会话产物）、`android/.../atlas-rgba-manifest.json`（构建副产物，SR-4 有
   `c25f507ed` 专门回退过的先例）。随后该会话新建并切到 `w5/ground-boundary-refactor`，
   本会话的 C7 提交 `66dc3a62c` 因此落在**它的**分支上，且其消息描述了六个文件而实际只含
   两文件（其余已被 `029d827bd` 带走）——**该消息与内容不符，责任在本会话未在提交前复核对
   staged 集合的归属**，在此更正。
   本会话处置：`git cherry-pick 66dc3a62c` → 本分支 `9b982fb5f`（纯增量，不改写任何已有提交），
   并把 HEAD 还给对方分支；`git diff 9b982fb5f 66dc3a62c` 为空 ⇒ 两分支同树
   `e62f4c55`，§5.2 的门禁数字对两者同样成立。
   **未处置、需用户定夺**：`029d827bd` 里那三样不该入库/不属该批的文件是否回退（本会话不代改
   他批产物，同 SR-6 会话"不动"口径）。

---

## 10. 验收收官（2026-09-23 凌晨，验收会话补记；用户「按你推荐的做，完成后合并主分支并推送远程」驱动）

**C8 组合门三轮实录（全部亲跑，XML executed 计数 + 时间戳判绿口径）：**

1. **R1（主工作树 in-place，`--rerun-tasks`，23:46 起）**：15m07s 后 `:core:data:detekt` 判红
   **9 处风格级**（`SaveFileManager.readWithFallback` NestedBlockDepth、迁移测试夹具嵌套+长行、
   5 处 MayBeConst、1 未用 import）——**实证本批 §5.1 分模块自检从未覆盖 detekt 面**。按仓库纪律
   以独立笔 `863fbc4cc` 修复（零逻辑变化：修复写回抽 `repairSavFromBak` 小函数、夹具种子构造
   抽两 helper、5 处 const、删 import），` :core:data:detekt`+单测编译定向验证绿。
2. **R2（主工作树 in-place，00:06–00:36，`BUILD SUCCESSFUL in 30m20s` / 339 任务全 executed）**：
   数字全绿（8,089/0/17 + Diff 278/0 skip），**但判作废**——共享工作树内的地图并行会话在门窗口内
   写入在制品（atlas 清单 00:06:36、`NativeBridge.cpp` 00:21:24、`SoftwareCanvasBackend.kt`
   00:24:57、`ground_boundary.h` 00:36:45），编译窗口（00:06:30–00:13）无法证明纯净 ⇒ 按 B20a
   先例（混合态废证）主动作废，未采信。
3. **R3（隔离 worktree `C:/Mnzm/XianxiaSectNative-accept-gate` @ `863fbc4cc`，判绿轮）**：
   彻底排除共享树干扰。测试轮 709 XML（01:01–01:06）全绿后，`lintRelease` 曾被本 worktree 的
   环境件 `local.properties`（不入库）触发 `PropertyEscape`，且 lint 增量缓存在文件改写/删除后
   复活旧结果——清 lint 中间态并按 lint 自证写法（`sdk.dir=C\:/…`）转义后全绿；最终整轮
   **`BUILD SUCCESSFUL` / `GATE_EXIT=0`**。R3 与 R2 的测试数字逐位一致（8,089/0/17、Diff 278/0），
   互为旁证：R2 的废证污染未实际影响测试结论，但流程上仍以 R3 为唯一判绿轮。

**ctest**：1560/1560（0 失败，93.71s）+ 桥 fresh 重建，见 §5.2。

**收官登记**：① SR-7 台账行 → **accepted**（本表 + §5.2 为凭）；② SR-6 收官笔 `e26840387`
（其分支未并网致报告/CHANGELOG/台账 delivered 三件套一度"缺席"）已于收官合并 `d60aff2e1` 并网，
SR-6 一并 **accepted**（整轮门覆盖其全部代码，用户授权同源）；③ SR-4/SR-5 的 `accepted` 仍归用户
（本验收不代登，整轮门数字可作其登记凭据）；④ 方案文档 §4 SR-7 实施补记随 C9 一并入库；
⑤ pending-device 各批真机项维持挂起，不因代码收官而视为闭环。

