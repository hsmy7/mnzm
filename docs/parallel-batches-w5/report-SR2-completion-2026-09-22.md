# SR-2 完成报告（SaveBackend + 上传队列 + 脏标志仲裁 + 重开预存 · core 面）

> 日期：2026-09-22
> 批次：SR-2（施工卡 = `docs/parallel-batches-w5/batch-SR2.md`；方案 = `docs/save-system-refactor-plan-2026-09-21.md` §4 SR-2）
> 权威依据：方案 §2 目标架构 + §0 D3/D4 + 不变量 IN1/IN2/IN3/IN6；SR-0 侦察报告
> （仲裁单测清单 11+10 例 + 剧本 S1-S10 + TapTap 限额）。
> 性质：**基建批**——接口与机制全部落地、默认 LEGACY 全链惰性，切换动作归 SR-3/SR-6。
> 纪律声明：本会话只做 SR-2 一批；迁移链历史基线零触碰（本批无 wire/schema 变更，无迁移器条目）；
> 方案文档保持 untracked 未提交；`accepted` 留待用户。

---

## 1. 交付链（8 笔提交，每子项独立）

| commit | 内容 |
|---|---|
| `521ee9ef5` | C1 施工卡立卡 + 台账 SR-2 行证据补记 + 监控日志开跑条目 |
| `38a8bba2e` | C2 反射桥整体搬移 `CloudSaveApiBridge.kt`（纯代码搬移零逻辑变化，952→623 行） |
| `d33a7007a` | C3 core:data cloud 面：`SaveBackend` 接口 + `SaveBackendMode` 三态 + `UploadLedger` + `SaveArbiter.arbitrate` 纯函数 |
| `a48917a6f` | C4 `TapTapSaveBackend` 门面 + `UploadQueue` 状态机 + app/di `SaveBackendModule` + IN3 konsist 守卫 |
| `ad6401a48` | C5 重开顺序缺陷修正（审计 §2）：重置引擎前保护性预存 |
| `59582fc96` | C6 仲裁去时钟（`resolveCloudSaveInfo` 脏标志重写）+ 云上传触发钩子 + 失败如实呈现 |
| `111abcb1e` | C7a 组合门 detekt 修复（风格级零逻辑变化，SR-1 同口径） |
| 收官笔 | C8 本报告 + 台账 SR-2 行 delivered |

## 2. 交付内容与生产落点

### 2.1 SaveBackend 接口（D4/IN3）

`core/data/src/main/java/com/xianxia/sect/data/cloud/SaveBackend.kt`：
`upload / download / list / delete / currentCloudSaveId` + `conflicts: SharedFlow<SaveConflictEvent>`
（即派发指令的 observeConflict）。类型化错误 `SaveBackendError`（含 CONFLICT），对齐 SR-0 §2.4
错误码归组。**零 TapTap SDK 类型**；接口落 core:data 存储层，feature 面只依赖接口。

- `TapTapSaveBackend`（feature/game）：**包装**既有 CloudSaveApi 反射桥（C2 已整体搬移为
  internal 顶层，SDK 探测/回调桥/15s 超时/错误码映射逐行复用，**不重写**）；slot 语义 =
  slot 0→存量单档 `mnzm_cloud_save`（SR-6 迁移源）、slot 1..6→`slot_N`（SR-0 §3.4）；
  extra JSON 协议在 year/month/sect/disciples/stones/version 上**新增 `saveId`**（云端实际
  保存序号 W 的回带源；存量档无字段 = W 未知 → 仲裁 U11 保守退化）。

### 2.2 SaveBackendMode 三态开关（默认 LEGACY）

`LEGACY`（默认）/ `CLOUD_TRANSITION` / `CLOUD_ONLY`；`SaveBackendModeProvider`（MMKV 持久化，
未写入/**非法值一律回落 LEGACY**——失败封闭）；`shouldEnqueueCloudUpload(mode)` 纯函数门控
（`shouldSaveOnBackground` 同先例）。生产代码本批零处写入模式——切换归 SR-3/SR-6。

### 2.3 UploadQueue（D3 双步保存第二步）

单飞 worker + **惰性启动**（首次 enqueue 才拉起协程；LEGACY 全链零入队 ⇒ 零协程活动）
+ 窗口合并（出队前 debounce，同 slot 最新覆盖）+ 指数退避（TOKEN_EXPIRED/CONCURRENT/网络族，
30s×2^n 封顶 10min）+ 共享冷却（TapTap 创建/更新共享 1 次/分钟、按用户多档共用——SR-0 §2.3
保守口径，`sharedUploadCooldownMs` 参数化）+ 熔断半开（连续 5 失败→CircuitOpened 事件如实告警
→静默 5min 后重试）+ 冲突挂起（上传前仲裁 CONFLICT ⇒ 不自动上传，移挂起区发事件，
`resolveConflict(slot, keepLocal)` 收口）+ 幂等重传（同 saveId 覆盖；确认丢失恢复配方 =
重启后以账本待传指针同 id 重入队）。

### 2.4 脏标志仲裁（IN2 仲裁无时钟）

- **新仲裁唯一入口** `SaveArbiter.arbitrate(L, C, W?)` 纯函数：SR-0 §4.3 A 清单 U1-U11 逐例
  锚定（U5 云落后视为已确认旧态 / U9 W==L 确认回填竞态 / U10 非法态自愈归 IN_SYNC /
  U11 W 未知保守退化**失败封闭**——不升级冲突也不误判"云无档"静默上传）；
- **真冲突显式暴露**：下载路径（`TapTapSaveBackend.arbitrateAgainstCloud` 发 `SaveConflictEvent`
  + 返回 CONFLICT Failure）与上传路径（Queue conflictGate 挂起 + 事件）双面暴露给 UI 层；
  SR-3 冲突弹窗直接消费 `SaveBackend.conflicts` 流与 `resolveConflict`；
- **落点修正**："mtime-对-挂钟"确切机制 = `performCloudUpload` 成功分支以
  `System.currentTimeMillis()` 伪造 `lastModifiedTime` 写入本地缓存，此后
  `resolveCloudSaveInfo` 规则 5 用它与云端 mtime 比大小——时钟偏移即长期误判（审计 §12-I）。
  本批 `resolveCloudSaveInfo` 重写为 `(cached, api, localDirty)`，规则 5 = **脏标志裁决**
  （本地脏→缓存 / 净→API），`lastModifiedTime` 降级为纯展示字段，**零时钟比较**；
- **UploadLedger**（MMKV per-slot）：`lastLocalSaveId`（L）/`lastConfirmedCloudId`（C）/
  待传指针；确认单调幂等；U10 非法态 `normalizeIfNeeded` 自愈；`adoptCloudState` 承载
  冲突"玩家选云档"的基线收敛（序号语义，IN2 合规）。

### 2.5 重开顺序缺陷修正（审计 §2）

`performRestartGame` 在 `restartEngineAndReseed` 前插入 `protectivePreSaveBeforeRestart`：
复用 `performRestartSave` 全链（当前态快照→槽位邮件→落盘→超时→损坏自愈→槽位回滚），
**零新保存逻辑**。**预存失败 = 中止重置**（此时旧档仅存在于盘上，继续重置将以重置态覆写
唯一副本；中止点引擎未动，如实提示可重试）。顺序测试实证：`[save:year=12（当前态）,
reset, save:year=1（新档）]`——旧实现为 `[reset, save:year=1]`。

### 2.6 上传失败如实呈现（复用 b35f1d9fb postSaveWarning 基建纪律）

- 触发钩子 `SaveLoadViewModelCloudUploadHooks.maybeEnqueueCloudUploadAfterLocalSave`：
  本地保存成功后经 `shouldEnqueueCloudUpload` 门控入队；入队失败 `showError` 如实提示
  （本地已保存，不谎报不静默）；
- 队列事件收集（SaveLoadViewModel init）：**永久失败/熔断** → `showError` 如实上浮；
  瞬时失败（自动退避重试中）不打扰用户；LEGACY 模式队列零活动 ⇒ 收不到任何事件；
- 既有手动云上传（slot 0 路径）行为零触碰。

## 3. 门禁自检实录（结果如实）

### 3.1 六模块组合门 ✓（第二轮全绿，第一轮 detekt 失败如实修复）

- 命令照派发口径：`testReleaseUnitTest --max-workers=1 --rerun-tasks -Dgamecore.jni.path=…
  detekt compileReleaseKotlin lintRelease`；
- **第一轮**：`GATE_EXIT=1`——`:feature:game:detekt` 18 weighted issues（全部为本批新文件
  风格级：download LongMethod/CC、classify CC、PersistenceFacade LongParameterList、
  TooGenericExceptionCaught ×9、SwallowedException ×1、MaxLineLength ×3、UnusedImports ×1），
  `111abcb1e` 独立 commit 修复（download 拆 `arbitrateAgainstCloud`/`fetchAndDeserialize`
  两私有段 + classify 改错误码映射表，行为等价；聚合门面/防御兜底 @Suppress 带注记）；
- **第二轮**：`BUILD SUCCESSFUL in 26m 5s`，339 tasks 全 executed，`GATE_EXIT=0`；
- **XML executed 计数 + 时间戳判绿**：

| 模块 | tests | failures | errors | skipped |
|---|---|---|---|---|
| app | 991 | 0 | 0 | 2 |
| core/domain | 1743 | 0 | 0 | 0 |
| core/data | 764 | 0 | 0 | 15 |
| core/engine | 3396 | 0 | 0 | 0 |
| core/ui | 146 | 0 | 0 | 0 |
| feature/game | 902 | 0 | 0 | 0 |
| **TOTAL** | **7942** | **0** | **0** | **17** |

  （skip 17 与基线完全一致零新增：StorageSystemBenchmark 15（core:data）+
  DiscipleTablesIntegrationTest 2（app））
  XML mtime 全部落在 05:28:38–05:34:20（本轮门窗口内）✓。总数 7893 → 7942 的
  **+49 = 本批新测试 49 例**（见 §3.4），failures/skip 分布与基线同构。

### 3.2 Diff* 对拍 ✓（IN8）

**50 类 / 273 例 / 0 failures / 0 skip**——与基线完全一致，差分管线信封语义零破坏（D7）。

### 3.3 桌面 ctest ✓（桥重建豁免理由）

- 本批**零 C++/JNI 面**（git 实证：`521ee9ef5..111abcb1e` 对 `android/app/src/main/cpp/`
  零提交）⇒ 桥重建可免（batch-SR1 §4 同预案）；
- **ctest 照跑：`100% tests passed, 0 tests failed out of 1561`（60.99s）**，与基线 1561
  完全一致。运维注记：ctest 须以 llvm-mingw 工具链目录入 PATH 运行（SOP 补充，见 §5.4）。

### 3.4 本批新增测试清单（49 例 + 既有回归全绿）

| 测试类 | 例数 | 锚定 |
|---|---|---|
| `SaveArbiterTest`（core:data） | 12 | **SR-0 U1-U11** 逐例 + 纯函数幂等 |
| `UploadQueueTest`（core:data） | 12 | **SR-0 Q1-Q10** 逐例 + Q10b 选云档 + 幂等重放跳过 |
| `UploadLedgerTest`（core:data） | 8 | 记账/确认单调幂等/Q6 持久化/U10 自愈/槽位隔离 |
| `SaveBackendModeTest`（core:data） | 4 | 默认 LEGACY + 非法值回落 + 三态 roundtrip + **IN3 classpath 负向断言** |
| `TapTapSaveBackendTest`（feature） | 6 | 槽位命名可逆 + saveId extra + 错误码归组 |
| `StorageLayerSdkIsolationGuardTest`（feature） | 3 | **IN3 konsist 静态守卫**（三层扫描零 SDK import） |
| `SaveLoadViewModelLoadTest` 增 4 例 | 4 | 重开顺序实证 ×2 + **LEGACY 零入队守卫** + CLOUD_TRANSITION 入队 |
| `TapCloudSaveManagerTest` 改写段 | 21 | resolveCloudSaveInfo 5 例按脏标志规则重写（旧 mtime 3 例退役），其余保持 |

### 3.5 定向门（各 commit 内实跑）

| 轮次 | 范围 | 结果 |
|---|---|---|
| C2 后 | `TapCloudSaveManagerTest` | 21/21（搬移零行为实证） |
| C3 后 | core:data cloud 族 | 24/24 |
| C4 后 | cloud 族（+队列）+ app DI 聚合编译 | 36/36 + 编译过 |
| C5 后 | `SaveLoadViewModelLoadTest` | 36/36（34 既有 + 2 新增） |
| C6 后 | VM 38/38 + manager 21/21 | 全绿 |
| C7a 后 | feature/game 定向 | 94/94 + detekt 0 issues |

## 4. 设计决策与拍板点（验收轮关注点）

1. **`compareVersions`/`arbitrateCloudVersion` 拍板保留为"兼容闸"**（派发指令为"删除版本串
   比较"；SR-0 §4.3C 预授权 SR-2 拍板"删除或转兼容闸"）。**转兼容闸**，理由：
   (a) 该闸只拒"云端 App 版本 > 当前"的不可加载档，判据是 **App 版本不是进度新旧**——
   与 IN2 相容（进度"谁新"唯一入口已改为 `SaveArbiter.arbitrate`）；(b) 删除即 LEGACY
   下载路径行为变化（违反本批硬红线：高版本档会退化成含混的"存档数据异常"而非"版本
   不兼容"如实提示）；(c) 保留则 U1-U11 新仲裁器是唯一进度判定，版本串在"谁新"语义中
   **已整体退役**。KDoc 与单测段均已改注兼容闸身份。**此拍板待用户复核，如坚持物理删除
   可在 SR-3 起的 CLOUD_TRANSITION 切换批再议（届时 LEGACY 红线不再约束新路径）。**
2. **`resolveCloudSaveInfo` 的 LEGACY 显示面边缘差异（如实登记）**：去时钟后，"缓存与 API
   摘要都有真实值"时的取舍由 mtime 比较改脏标志——本地净时**一律取 API**（旧规则下若
   缓存 mtime 较新会保留缓存）。影响面 = 云存档摘要卡片**显示文案**的最终一致性瞬态窗口
   （自愈于下次查询/上传确认），存档数据本身零影响。旧 mtime 规则在"挂钟伪造缓存"前
   提下本就系统性偏向错误侧（审计 §12-I 定性）。
3. **重开预存失败 = 中止重置**（而非降级继续）：预存的目的就是保护旧档；失败继续重置
   会以重置态覆写唯一副本。中止点引擎未动、盘上旧档完好、错误如实提示。这是本批
   显式行为修正（审计 §2 缺口修复本身即行为变更），非 LEGACY 红线违反。
4. **队列熔断不复用 `StorageEngine.recordSaveCircuitResult`**：该函数是**本地保存**熔断
   （语义 = 连续失败暂停本地写）；把上传失败计入会错误阻断本地保存，违反 IN1"上传失败
   只降级不回滚本地"。改为 UploadQueue 内建 `UploadCircuit`（同构模式：计数→开→半开），
   事件经 show* 通道如实呈现——"复用链"以同纪律同模式落实，Q9 单测锚定。
5. **LEGACY 红线的机制面保障**（三层）：`shouldEnqueueCloudUpload` 纯函数短路（VM 守卫
   测试实证 LEGACY 下 `enqueue` 零调用）+ 队列惰性启动（无入队=无协程）+ 模式存储非法值
   回落 LEGACY（失败封闭）。组合门全量回归（7942/0/17）兜底既有行为面。
6. **IN3 守卫白名单登记（需用户/后续批拍板）**：konsist 三层扫描发现既有文件
   `TapTapLeaderboardApi.kt` **直接 import tap-leaderboard SDK 类型**（先于本批存在，
   排行榜域非云存档存储层）。本批**不夹带修复**，在守卫测试白名单登记豁免并注明待拍板；
   新增任何 SDK import 立即红灯。IN3 对云存档链的约束已闭环（classpath 负向断言 +
   konsist 扫描双层）。

## 5. 如实登记（坑与基建发现）

1. **组合门第一轮 detekt 失败**（18 weighted issues，全部本批新文件风格级）——独立 commit
   `111abcb1e` 修复，测试语义零变化（定向 94/94 实证）。与 SR-1 两轮 detekt 教训同因，
   派发预警有效。
2. **coroutines-test 1.9 的 backgroundScope 不被 `advanceTimeBy`/`advanceUntilIdle` 推进**
   （仅测试体挂起时推进）：UploadQueueTest 初版 worker 全程饿死 + 事件无订阅者丢弃 +
   runTest 收尾推进调度器时把耗尽脚本响应的 worker 无限重试循环跑成真实时间爆炸
   （一次 OOM、一次 777MB 测试输出）。终版方案 = 队列作用域取「共享 TestScope 调度器 +
   独立 Job」+ `queueTest` 包装器 finally 取消全部作用域。**SR-4 去抖/月变钩子的协程
   测试建议沿用此模式**（已在测试 KDoc 注记）。
3. **MockK 拓展函数**：`restartGameSuspend` 须 `mockkStatic("…GameEngineLoadDataOpsKt")`
   + 显式 import 方可桩；`SaveResult<Unit>`（非 `SaveOperationStats`）为 facade.save 实际
   返回类型。
4. **ctest 运行环境**：需 llvm-mingw 工具链 bin 目录入 PATH
   （`C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin`），否则 bench exe 缺
   运行时 DLL 秒退（Exception ×N）。建议纳入验收 SOP 口径。
5. **-room 2.7.0 嵌套事务警示遵守**：本批零新增嵌套事务、零吞异常部分提交（SR-0 §5 警示）。

## 6. 红线合规声明

- **IN1 原子性** ✓：本地事务在前必成；触发钩子在 `storageFacade.save` 成功后才入队；
  队列/上传失败只降级（事件+账本保持脏标志），零回滚本地路径。
- **IN2 仲裁无时钟** ✓：进度"谁新"唯一入口 = `SaveArbiter.arbitrate`（纯序号）；
  `resolveCloudSaveInfo` mtime 规则退役；UploadQueue 全类零时钟比较（退避/冷却 delay 是
  节奏不是判定）；U1-U11 单测锚定。
- **IN3 接口隔离** ✓：core:data 零 SDK 类型（接口 + 队列 + 账本 + 仲裁全在存储层且
  classpath 结构性不可见 SDK）；feature 面云存档链零 SDK import（konsist 守卫，
  TapTapLeaderboardApi 既有豁免已登记待拍板）。
- **IN6 机制要有调用者+测试** ✓：SaveBackend 消费者 = TapTapSaveBackend/UploadQueue；
  UploadQueue 生产调用点 = 非 LEGACY 保存钩子（本批落地）+ 12 例状态机单测；
  `SaveBackendModeProvider.set` 为 SR-3/SR-6 接线面（KDoc 注明，守卫测试消费）。
- **LEGACY 硬红线** ✓：三层机制保障（§4.5）+ 组合门全量回归 7942/0/17 + VM 守卫测试。
- **IN8 Diff\* 0 skip** ✓：50 类 273 例 0 skip。
- **迁移链** ✓：零 wire/schema 变更（本批无 proto/Room 触碰），迁移器零条目。
- **台账纪律** ✓：只动 SR 系列行与监控日志；方案文档保持 untracked；`accepted` 未自登。
- **CHANGELOG**：SR-0/SR-1 先例均未追加 CHANGELOG 条目（中间工程批口径），沿用；
  版本条目归用户发布轮决定。

## 7. 待用户拍板 / 移交后续批

| # | 事项 | 归属 |
|---|---|---|
| 1 | §4.1 兼容闸拍板复核（保留 compareVersions 为 App 版本兼容闸 vs 物理删除） | 用户复核；如删归 SR-3+ |
| 2 | §4.6 `TapTapLeaderboardApi` 既有直接 SDK 引用（排行榜域）是否收编接口隔离 | 用户/后续批（非存储层，不阻塞 SR-3） |
| 3 | SaveBackendMode 升档时机（CLOUD_TRANSITION 逐设备推进） | SR-3/SR-6 批内 |
| 4 | UploadQueue 冷却参数真机校准（1/min 保守口径 vs 60/min） | SR-3 真机硬门 |
| 5 | aiSectDisciples @Transient 拍板（SR-0 遗留，SR-3 派发前置） | 用户（看护已登记） |

## 8. SR-3 派发前置提示（给看护/用户）

云主路径批将消费本批交付：`SaveBackend.download` 的 verdict/`conflicts` 流（冲突弹窗）、
`list()`（云槽位列表）、`currentCloudSaveId`（迁移矩阵）；`resolveConflict(slot, keepLocal)`
为冲突二选一的收口入口。`SaveLoadViewModelCloudLoadOps:280` 只进内存不落盘的修复面未动
（归 SR-3）。**真机硬门自 SR-3 起算**；aiSectDisciples 拍板项仍悬置。
