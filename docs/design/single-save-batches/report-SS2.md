# report-SS2 · 账号数据空间分库 + 登出五件套

> 派工真源：[`TASKBOOK-SS2.md`](TASKBOOK-SS2.md)；协议：[`DISPATCH-ledger.md`](DISPATCH-ledger.md) §4.3 + [`../gacha-batches/EXECUTION-PROTOCOL.md`](../gacha-batches/EXECUTION-PROTOCOL.md)。
> 基线：main `7efe2fce0`（含 SS0/SS1/SS9 并网结果）；分支 `feat/single-save-SS2`；实施期间无他批在途改动。

---

## 1. 做了什么

| # | 分类 | 内容 |
|---|---|---|
| 1 | 新增（纯函数） | `:core:domain` `core/util/AccountKey.kt`——账号标识 SHA-256 截断派生 accountKey（16 hex）+ `isValid` 合法性校验；KDoc 附 iOS CryptoKit 对等实现（D-1/D-3）。空白标识 fail-fast（无账号不建空间的派生侧锚点） |
| 2 | 新增（数据层） | `:core:data` `data/account/AccountSpaceManager.kt`——数据空间唯一权威：`activate`（建 `filesDir/accounts/<key>/{saves,archives}` + 写 `.current` + 进程内缓存）、`currentKey/currentRoot`（进程重启后从 `.current` 恢复）、`requireDatabaseFile/requireSavesDir/requireArchivesDir`（无活跃空间抛 `IllegalStateException`，D-5 fail-fast）、`closeCurrent`（只删 `.current` 不删空间，D-4） |
| 3 | 改造（建库链） | `GameDatabase` 全链 File 化：`create(context, dbFile)`（Room builder 收绝对路径）、`snapshotDatabaseBeforeUpgrade(dbFile)`、`restoreFromBackupIfNeeded(dbFile)`、`verifyAndRecoverDatabase(db, dbFile)`——库名/快照/恢复 marker 全随账号目录；`UNIFIED_DB_NAME` 固定设备路径与 `getUnifiedDatabaseFile(context)` 退役 |
| 4 | 改造（注入链） | `AppModule.provideGameDatabase` 经 `accountSpace.requireDatabaseFile()` 解析库路径；`StorageFacade` 注入空间管理器，saves 根经 `requireRoot()`；`DataArchiver` 归档根经 `requireArchivesDir()`；`StorageModule.provideDataArchiver` 同步构造参数 |
| 5 | 改造（启动时序） | `XianxiaApplication` 的 `storageFacade`/`gameDataCacheManager`/`gameEngine` 三条建库链全部 `dagger.Lazy` 化 + `isActive()` 门控（trim 动作与 onTerminate）；`MainActivity.storageFacade` Lazy 化，存储初始化从「隐私同意后加载页」移至登录/验证通过后的 `enterGameAuto`（激活空间 → 初始化 3 重试 → 槽位判定，两条进入路径冷启动已验证/刚验证完均汇聚于此）——**登录前零存储链实例化**（验收⑤） |
| 6 | 改造（登出） | 新增 `app/login/FullLogout.kt`：`performFullLogout` 五件套唯一实现（四件套 + `closeCurrent`）+ `restartToLoginScreen`（进程重启——数据空间绑定进程级单例图，同进程原地切换必串档）。三处入口收敛：`MainActivity.performFullLogout`（状态机 `LogoutRequested` 汇聚：实名认证界面/合规弹窗）、`GameActivity.onLogout`（设置页）、`GameActivity.performComplianceLogout`（游戏内合规弹窗）——全部复用共享函数，禁止手抄（验收③④） |
| 7 | 改造（删档） | `SaveWipeCoordinator` 清单同步分库结构：`accounts/` 整树删除 + 分库前设备级旧位置残留（databases/ 库文件族、filesDir/saves、filesDir/archives）一并清零（增量铁律 18） |
| 8 | 版本 | `DATABASE_VERSION` 68→69（账号数据空间结构变更批次例行递增，防 identity-hash 异常；`@Database` 实体清单不变，`MigrationRequiredGuardTest` 基线无需更新） |
| 9 | 守卫与测试 | 新增 `AccountDataIsolationGuardTest`（6 断言，见 §2）；新增 `AccountSpaceManagerTest`（8 用例 Robolectric 行为面）；新增 `AccountKeyTest`（8 用例）；适配 `DestructiveRebuildBaselineTest`（File 化签名 + 短路径，见 §4 风险①）、`SaveWipeCoordinatorTest`（分库清单断言 + 旧位置残留断言） |
| 10 | 规范 | `rules/sdk-init-lifecycle.md`：删已退役的 `ModeSelectionScreen.onLogout` 行；登出清单升五件套（唯一实现 `login/FullLogout.kt` + 进程重启），四件套表述全部更新；检查清单同步（验收③同批修正义务） |

## 2. 验收判据逐条对照

| 验收 | 结果 | 证据 |
|---|---|---|
| ① 数据空间按 accountKey 落盘 | ✅ | `filesDir/accounts/<key>/{xianxia_sect.db(+wal/shm+快照+marker), saves/, archives/, .current}`；`AccountSpaceManagerTest` 行为面逐项断言。**登记**：验收①原文提及的 `legacy_import/` 目录全仓零引用（SS0 后已无消费方），本批不创建 |
| ② 换账号不串档（隔离守卫） | ✅ | `AccountDataIsolationGuardTest` 以 `@Database` 实体清单为锚点遍历：单库唯一入口（`Room.databaseBuilder` 全仓唯一）、库路径经空间管理器、saves/archives 根经空间管理器、wipe 清单含整树、登出五件套收敛、filesDir 直写全仓扫描（`intentionallyExcluded` 六项逐一注明理由：加密密钥/崩溃日志/孤儿清理/FunctionalWAL（SS3 摘除在即）/MMKV（SS7 评估）/空间权威与 wipe 自身） |
| ③ 登出五件套三入口一致 | ✅ | 共享实现 `performFullLogout`；守卫断言清单只在 `FullLogout.kt` 出现一次、两 Activity 均复用同一调用形态（逐字一致） |
| ④ 登出只清 `.current` 不删空间 | ✅ | `closeCurrent` 语义 + `AccountSpaceManagerTest`（`closeCurrent clears marker but keeps space directory`：目录与存档文件保留断言） |
| ⑤ 无账号不建库 | ✅ | `require*` fail-fast + Application/MainActivity 三条建库链 Lazy 化 + `enterGameAuto` 无 unionId 回登录页（不建匿名空间）。**实证**：本批 fail-fast 在 :app 测试面拦下约 40 个用例的隐性建库链（`XianxiaApplication.onCreate` Hilt 注入 `gameEngine` 即触达 `mailDao`）——`gameEngine` Lazy 化后全绿，**该问题同时是生产行为修正**（修正前未登录冷启动也会建库） |
| ⑥ 派生逻辑下沉 :core/domain 纯函数 | ✅ | `AccountKey` 零 Android 依赖（`java.security.MessageDigest`），KDoc 含 iOS 对等实现；`AccountKeyTest` 含多字节 UTF-8 与跨端字节序列锚定用例 |

## 3. 门禁实跑数值（主线程终树）

| 门禁 | 结果 |
|---|---|
| `compileReleaseKotlin`（全模块终跑） | BUILD SUCCESSFUL |
| `:core:domain:testReleaseUnitTest` | 全绿 |
| `:core:data:testReleaseUnitTest` | **681 tests, 0 failed**, 15 skipped（skip 与本批无关，为既有环境跳过） |
| `:app:testReleaseUnitTest` | 全绿（BUILD SUCCESSFUL；含广告收入/崩溃链路/分析面全量） |
| 登录守卫组（LoginFlowStateMachine/ComplianceManagerSelfHeal/SafeRunAfterSdkInit/SdkInitGuard/TapDBManagerInitGuard） | 全绿 |
| `:core:domain:detekt :core:data:detekt :app:detekt` | 全绿（baseline 零新增） |
| `node scripts/check-agent-instructions.mjs` | EXIT=0（路由闭包 42 篇、485 条引用全解析） |
| 生成器零漂移 | 本批未触碰 proto/game-data/action-catalog，不适用 |

**改动规模**：18 文件（12 修改 + 6 新增），+677/−223（修改面 diff + 新文件 692 行）；构建副产物（atlas-rgba-manifest/scene_uv_tables/sprite-uid-map）已 `git checkout --` 还原，一次性诊断代码与探针测试已清零。

## 4. 风险与登记

**已核实**：

1. **`DestructiveRebuildBaselineTest` 测试库路径改至模块 `build/` 短路径**——Windows 上 SQLite（Robolectric LEGACY sqlite4java）受 MAX_PATH(260) 约束，Robolectric 沙箱目录名含完整测试方法名（约 100 字符）叠加 `files/accounts/...` 深度路径超限，报 `SQLITE_CANTOPEN`。与本批生产行为无关（真机 Linux 无此约束），类 KDoc 已注记。该测试同时补强了一处断言（`create` 返回时库已打开且完成重建——此前靠快照副产物间接覆盖）。
2. **登出 = 进程重启**（`restartToLoginScreen`：投递 MainActivity Intent 后 `exit(0)`）——数据空间绑定进程级单例图（Room 连接池/存储引擎/GameEngine/内存缓存均为账号态），同进程原地切换必然串档；本批不碰 C++（任务书边界），引擎态归零只能靠进程生命周期。登出前不做显式 `StorageFacade.shutdown`：WAL 已提交事务由 SQLite 下次打开自动恢复（崩溃恢复语义既有测试覆盖），`.sav` 文件层兜底不变。真机冒烟（任务书 §5）需验证重启观感。
3. **启动加载页语义微调**：启动阶段加载页只承担 Umeng init + 动画（`isLoadComplete` 直接置位），存储初始化（含 SR-3 三重试/全败阻断）移至 `enterGameAuto` 的进入加载页——`storageInitError` 阻断屏与重试入口保留（重试 = 重新 `enterGameAuto`）。
4. **`MainActivity` 无 unionId 兜底**：`enterGameAuto` 发现 unionId 空时回登录页（日志留痕），不建匿名空间——正常路径由状态机保证（`ColdStart` 空 unionId 已被状态机拦截），此为防御兜底。

**登记（跨批）**：

1. **SS7**：MMKV 本批仍为全局（`game_prefs`/云端台账键）；是否随账号隔离由 SS7 评估（TASKBOOK §6 原文登记，守卫白名单已注记）。
2. **SS8**：离线宽限（B1）与隐私政策双入口归 SS8；本批「无账号 ⇒ 不建库 + 引导登录」已就位（`enterGameAuto` 防御兜底 + D-5 fail-fast）。
3. **SS3**：`FunctionalWAL` 目录（`filesDir/wal_v4`）仍为设备级——SS3 摘除 FunctionalWAL 时一并消失（守卫白名单已注记，摘除后该排除项应随批删除）。
4. **合规回调宿主链**：`injectXianxiaApplication` 触达 `mailDao` 的具体节点（GameEngine 依赖树内）本批未深挖——Lazy 化后注入推迟至首次 trim/get，行为正确；SS4/后续动引擎装配时可顺手定位登记。

## 5. 真机冒烟（待验收侧执行）

A 账号建进度 → 登出（进程重启回登录页）→ B 账号登录（**不得看到 A 的进度**）→ 登出 → A 账号再登录（**进度仍在**）；附加：未登录杀进程重进（不建库、直达登录页）、启动加载页 → 登录 → 进入加载页（存储初始化承载）。
