# SR-7 文件层废除 + Room v53 schema 第二刀 + 收官施工卡（CLOUD_ONLY 终态收口批）

> 立卡：2026-09-22（用户口头「`docs/save-system-refactor-plan-2026-09-21.md` 新开分支完成第 7 批」
> 直接驱动；SR 看护 cron 已于 SR-3 终局删除，本会话 = 实施会话，非派发）。
> 分支：`w5/sr7-file-layer-retirement`，基线 = `w5/sr6-cloud-migration` @ `38a987537`
> （SR-6 C1–C10 十笔之上；SR-7 消费 SR-6 的 `canPromoteToCloudOnly`，故不能从 `main` 开）。
> 权威依据 = `docs/save-system-refactor-plan-2026-09-21.md` §4「SR-7」+ §0 D1/D2/D5/D7 +
> §2 目标架构与模式开关 + §3 IN1–IN8 + §5 门 1–6；前置资产 = SR-2（SaveBackend/UploadQueue/
> UploadLedger/SaveArbiter/三态开关）+ SR-3（云槽位下载落盘 + 冲突弹窗 + 列表）+ SR-4（月变/
> onStop 自动触发 + `SaveOrchestrator`）+ SR-5（`WallClock` 族 + 载荷 HMAC）+ SR-6（迁移矩阵/
> 记账/升档门槛/迁移卡）。
> **方案文档 untracked，直读工作区，不自行提交**。
> 台账 = `dispatch-ledger.md` SR 系列批次总表，SR-7 行由本会话自记（沿用 SR-0..SR-6 口径）；
> **`accepted` 归用户，本批不自登记**。

---

## 0. 前置门核验（开工前实测，不照抄方案、不假定）

| 方案 §4 SR-7 字面前置 | 实测状态 |
|---|---|
| SR-3 真机稳定 | ❌ 未满足。SR-3 报告 §7 的 8 项 pending-device 全未跑（双设备剧本 S1–S10 / 云槽位落盘全链 / 冲突弹窗端到端 / …）；SR-4 §7 六项、SR-5 §7 六项同样挂起 |
| SR-6 迁移完成率达标（阈值 SR-6 定） | ⚠ **门槛代码已就绪、达标与否不可判**。SR-6 落了 `SaveMigrationLedger` + `canPromoteToCloudOnly`（三条件），完成率指标 `#save_migration_result` 已定义但 TapDB「事件管理」录入待运营 ⇒ 无任何真实分子/分母 |

**由此定本批口径（写死，防后来者误读为"已切换"）**：

1. SR-7 的终态代码**一律按 `mode == CLOUD_ONLY` 门控落地**；`LEGACY` / `CLOUD_TRANSITION`
   逐位零变化。`CLOUD_ONLY` 的生产写入点仍为零（SR-6 `SaveMigrationGuardTest` 钉死），
   故**本批不产生任何玩家可见行为变化，也不声称完成切换**。
2. **文件层组件本体的物理删除（`SaveFileManager` / `SaveFileFormat` / `.bak` 轮转 /
   `FunctionalWAL` 组件 / tombstone / quarantine）本批不做**，留到 CLOUD_ONLY 成为机群默认
   之后的清理批。理由不是保守，是方案的 ADR 本身：D5 写明轮转备份"在 CLOUD_TRANSITION
   过渡期继续保护玩家，最终**随文件层一起**退役"，而今天全量设备的模式恒为 LEGACY
   ——现在就删，等于在没有任何云迁移实绩的设备群体上抽掉唯一还在生效的兜底，
   与 D5 的生命周期声明相违。本批交付的是"退役的**代码就位**"，不是"退役**已发生**"。
3. 真机升级路径实测（v52 真档 → v53 + 文件层门控行为）= 方案 §5.5 硬门，本环境不可测，
   逐项登记 pending-device，不虚报。

---

## 1. 勘察结论（本会话逐条 grep/实读取证，非引用注释或前批口径）

1. **6 张表生产侧只有写侧**。全仓（排除 `build/`）grep 六个 DAO 访问器，命中仅三类：
   `deleteAll`（`StorageEngineWriteOps.kt:135-139/157`、`StorageEngineSaveSupport.kt:273-277/299`、
   `GameDataRepositoryImpl.kt:36-40`）、`upsertAll`/`insertAll`（`StorageEngineWriteOps.kt:188-199`）、
   以及 6 个 Hilt provider（`AppModule.kt:168-184`）+ `DiscipleDaos` 字段
   （`GameStateDaos.kt:31-35`）。`DiscipleSubDaos.kt` 里的全部 SELECT 方法
   （`getAllAlive`/`getAll`/`getById`/`getAllAliveSync`/`getAliveByRealm`/`getIdsBySlot`/
   `getByDiscipleId`/`deleteAllGlobal`）**零调用者**；C++ 侧（`app/src/main/cpp`）6 个表名 0 命中。
2. 🔴 **与"删 6 表"字面必须区分：6 个类是活的领域模型。**
   `DiscipleAggregate.kt:8-12` 持有 core/combatStats/equipment/extended/attributes 五字段，
   `:417-421` 由 `Disciple.fromDisciple(...)` 构造（不经 DB），而
   `DiscipleStatCalculator属性Ops3.kt:125/137` 读 `DiscipleCombatStats?`/`DiscipleAttributes?`
   算属性方差与技能输入；`DiscipleAggregate` 本身被 `GameStateStoreImpl`（`:79/444/530/616`）
   与 `XianxiaApplication.kt:218-300`（statsProvider 注册）消费。
   ⇒ **本批删的是表 + DAO + 写入面，5 个类保留为纯领域类型（去 Room 注解）**；
   `DiscipleCompact` 剪掉唯一写入点（`StorageEngineWriteOps.kt:198-199`）后全仓零消费者
   ⇒ 按 IN6 连类一起删。
3. `DiscipleAggregateWithRelations.kt`（Room `@Embedded` 关系类）实测零生产消费者
   （只有自身声明 + `DomainDependencyTest.kt:95` 白名单条目 + `DiscipleModelsTest.kt:495/517`）
   ⇒ IN6 删。
4. `DomainDependencyTest.kt:90-115` 是 `core:domain` Room 注解白名单守卫 ⇒ 本批须同步收缩
   （6 个条目退出），且该测试"只缩不增"意图保留。
5. `ClearAllSlotTablesCoverageTest` 是双向相等守卫（`@Database` DAO 集 − EXEMPT ⇔ 清理清单），
   6 个 DAO 退场后两侧同步 −6 ⇒ 只需下调 `:46-47` 的 `>= 30` 防解析失配阈值，
   新阈值仍须严格大于剩余清单实际规模（剩余 27/33 DAO 访问器）。
6. **发现既有缺陷（非本批引入）**：`StorageEngineWriteOps.kt:175-176`
   `syncSlotMetadata(slot, data)` 连续两次。`git log -L` 追到 `c13d65157`（2026-09-12
   w2 集成合并）即已如此 ⇒ 独立一笔修，不与 schema 面混装（IN6/IN1 相关：单事务内重复
   写同一行元数据）。
7. **crypto 第二刀实测只剩两个死壳**：`SaveCryptoKeyCache`（唯一外部调用者
   `XianxiaApplication.kt:128` 的 `initialize`，其 `getDerivedKey`/加解密零消费者）+
   `StorageConfig.kt:77/134` `DEFAULT_KEY_CACHE_DURATION_MS`（唯一消费者是那死壳）。
   真正的载荷加密码已在 `5a421f3d6`（B 系列死代码第一刀）切掉 ⇒ 本批是**收尾清壳**，
   不是拆链。`.secure_key` 网络签名链（`SecureKeyManager` / `SecureKeyFileStore` /
   `DeviceBindingIdentity` / `RequestSigner` / `SecureHttpClient`）**活的且 SR-5
   `SavePayloadSigner.kt:98` 依赖 ⇒ 零触碰，并加守卫锁死**。
8. **`FunctionalWAL` 调用点实测 6 处，方案写 5 处**：`StorageEngine.kt:543`（recover，
   仅维护日志）、`:567`（shutdown）、`:587`（beginTransaction）、`:617`（commit）、
   `StorageEngineSaveSupport.kt:31`（abort）、`:44`（abortSync）。组件本体
   `data/wal/FunctionalWAL.kt` + `FunctionalWALEntryCodec.kt` + `WALProvider.kt`，
   DI 在 `StorageModule.kt:47-53`。审计"它是活的"结论成立。
9. **文件层全链实测活的**：`SaveFileManager.kt`（`atomicWrite:97`、`rotateCurrentSaveToBackup:167`、
   `readWithFallback:219`、`cleanupOrphanedTmp:286`、`cleanExpiredBackups:307`、`deleteSlot:327`、
   tombstone `markSlotDeleted:346`/`isSlotDeleted:356`/`clearSlotDeleted:362`/`getTombstoneFile:367`）、
   `SaveFileFormat.kt`（XSBK 头 + CRC 族）、quarantine（`StorageEngineHeavyDataOps.kt:386`，
   **实测无任何清理消费者** ⇒ 登记）、`pre_migrate_backup`（`GameDatabase.kt:490/421/841/901`）。
   `SlotLockManager.kt:25` 按方案**保留**（保存/读档/删档/修剪/归档五路同锁，实测活的）。
10. 方案 §4 SR-7 的"旧 `.sav` 转只读应急源保留 N 个版本后清理" ⇒ N 取 **3**
    （与 `pruneMigrationBackups` 现行保留 2 版的量级同阶，向上取整；实现为常量 + 守卫测试，
    不写死在分支里）。

---

## 2. 子项切分（每子项独立 commit，不夹带）

| # | 子项 | 面 | 编译依赖 |
|---|---|---|---|
| C1 | 本卡 + 台账 SR-7 行 in_progress | docs | — |
| C1b | `syncSlotMetadata` 重复调用自纠（§1 条 6） | `:core:data` | 独立小笔，先于 C2 |
| C2 | Room **v52→v53 schema 第二刀**：`DATABASE_VERSION` 52→53 + `MIGRATION_52_53`（DROP 6 表，幂等）+ `@Database` entities/DAO 访问器收缩 + 六 DAO 接口与写入面收口 + 5 类去 Room 注解 + `DiscipleCompact`/`DiscipleAggregateWithRelations` 删 + `DiscipleDaos` 收缩 + provider/DI 收口 + 受影响测试更新 + 新 `RoomMigrationV52To53Test`（逐字段等价）+ `53.json`（KSP 生成） | schema/wire 相邻面，**单独走批内一笔** | 必须同笔（跨模块编译原子性） |
| C3 | crypto 第二刀收尾（删 `SaveCryptoKeyCache` + `StorageConfig` 键缓存常量族）+ `.secure_key` 保留守卫测试 | `:core:data`/`:app` | 独立 |
| C4 | 文件层按 `CLOUD_ONLY` 门控收口：CLOUD_ONLY 下不写 `.sav`/`.bak`/`.tmp`、不建 tombstone、`.sav` 仅作**只读应急源**（N=3 版清理）；LEGACY/CLOUD_TRANSITION 逐位不变 | `:core:data` | 独立 |
| C5 | `FunctionalWAL` 6 处调用点摘除（CLOUD_ONLY 下不 begin/commit/abort/recover，组件本体保留待清理批）+ 守卫 | `:core:data` | 依赖 C4 的模式判定入口 |
| C6 | 归档任务判归重审（`DataArchiveScheduler` 600s / `DataPruningScheduler` 300s 在云唯一终态下是否仍必要）→ 报告 §6 给判归与证据，**默认零代码改动**，如需改判独立笔 | 判归 | — |
| C7 | IN1–IN8 守卫全家桶固化：逐条盘点现有守卫缺口，缺者补测试（IN5 payload 红线断言、IN6 零调用者扫描、IN7 boot 只读不写） | test | 依赖 C2–C5 落地 |
| C8 | 门禁：`:core:data` 定向 → 六模块组合门 → `Diff*` 0 skip → ctest → 存档回归逐字段等价实跑 | — | 全部 |
| C9 | 文档三件套：方案 §4 SR-7 补记 + `CHANGELOG.md` + 台账 delivered + 完成报告 `report-SR7-completion-2026-09-22.md` | docs | 依赖 C8 实测数字 |

---

## 3. 红线（违一条即停批回报）

- **迁移链历史基线零触碰**：`GameDatabaseMigrationsV2ToV10/V11ToV20/V21ToV30/V31ToV37/V38..V52`
  一字不改，含其中 `ALTER TABLE disciples_*` 的历史 SQL（它们在 v53 之前执行，删表不影响链可跑）；
- **LEGACY / CLOUD_TRANSITION 逐位零变化**（本批全部新行为只在 CLOUD_ONLY 分支上，
  而 CLOUD_ONLY 生产零写入）；
- **IN4 wire 唯一性**：本批零 proto 改动；**IN8**：`Diff*` 273 例 0 skip 是出厂门；
- **D7 保值资产**：差分管线（`nativeSettlePhase → exportDirty → mergeGameDataChanges`）零触碰，
  `GameStateStore`/`DiscipleAggregate` 语义零变化（保类、只去 Room 注解）；
- **`.secure_key` 网络签名链一环不删**（SR-5 依赖）；
- `SlotLockManager` 保留；
- 不动 `dispatch-ledger.md` 他批行；**不代提交 SR-6 会话留在工作区的 CHANGELOG 在制品**
  （开工时 `CHANGELOG.md` 即为 SR-6 未提交的收官内容）；不碰
  `android/app/src/main/assets/atlas/atlas-rgba-manifest.json`（构建副产物，SR-4 `c25f507ed` 先例）；
- 多行源码改动只用 Edit/Write（B08 实事故：脚本改 CRLF 源码会静默损坏）。

---

## 4. 门禁复跑口径

```
cd android && export JAVA_HOME=C:/Users/cp050/.jdks/jdk-21.0.12.1+1
./gradlew.bat :core:data:testReleaseUnitTest --max-workers=1        # 门 1 定向
./gradlew.bat testReleaseUnitTest --max-workers=1 \
  "-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative/android/core/engine/build/desktop-jni/libgamecorejni.so" \
  detekt compileReleaseKotlin lintRelease                            # 门 2 六模块组合门
cd app/src/main/cpp/gamecore && ninja -C build/desktop-test && ctest --test-dir build/desktop-test   # 门 4
```

判绿看 **XML executed 计数 + 时间戳**，不看 `BUILD SUCCESSFUL`；基线以本批开工时最新台账为准
（SR-4 收官 = 7,986/0/17，SR-6 在制品另加 69 例 ⇒ 实际基线由门 1/2 首跑确定并登记）。
