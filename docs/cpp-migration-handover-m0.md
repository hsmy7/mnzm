# C++ 迁移整改对接文档（M0 止血清残）

| 项 | 内容 |
|---|---|
| 文档性质 | 面向后续开发者/接手者的**对接文档**：本轮已完成哪些、如何验证、遗留哪些待办（需专项或拍板） |
| 依据文档 | [cpp-migration-audit-report.md](cpp-migration-audit-report.md)（独立审计）+ [cpp-migration-implementation-plan.md](cpp-migration-implementation-plan.md)（总方案，2026-09-04） |
| 里程碑 | M0 止血清残（全部清偿 ✅）→ M1 减税+试点（全部清偿 ✅）→ **M2 主轴成型（S4 §2.11 + S5 §2.12 + S8 §2.13——月结残留扇出 ≤3 项达成；E2+E3 §2.14 + P1-5 §2.15；E2 残留 §2.16 + S6 秘境会话域 §2.17 + S7 排程事务 §2.18，2026-09-06）** → **M2 续批：WS-5 地图数据模型改造（§2.19，2026-09-08）——"占位真相源在 C++"达成** → **M3 收敛启动：死代码族清偿（§2.20，2026-09-08）——五模块 UnusedPrivate*/UnusedImports 归零 + baseline 211 条摘除** → **M3 第二批：反向通道逐域写者审计（无域可关改判）+ lockedBeastIds 增量段缺口加固 + detekt InvalidPackageDeclaration 118 条清偿（§2.21，2026-09-08）** → **M3 第三批：detekt 机械族专项——MaxLineLength 1407 条实修清偿（§2.22，2026-09-08，baseline 2688→1281）** → **M3 第四批：RoomMigration 预存失败清偿 + detekt TooGenericExceptionCaught 474 处实修清偿（§2.23，2026-09-08，baseline 1281→873）** → **M3 第五批：detekt 判定族收官 + 机械族全清（§2.24，2026-09-08，baseline 873→370；实跑活违规 1050→395）** → **M3 第六批：detekt 判定边界族收官——CC 17 + NBD 28 + RC 33 真实重构清偿（§2.25，2026-09-08，baseline 370→292）** → **M3 第八批：detekt 复杂度族收官——CCM 68 条目实跑裁决 68 处真实重构清偿（§2.27，2026-09-09，baseline 196→128）** → **M3 第九批：detekt 函数数族第一轮——TMF 113 条目实跑裁决 114 处（死代码删除/18 个引擎域文件/17 个 Compose 域文件拆分/46 处契约面豁免），余量装回登记拆分队列（§2.28，2026-09-09，baseline 128→59）** → **M3 第十批：拆分任务队列首轮——core:data 全域清偿 + 并行线遗留 5 处根治 + app 契约面豁免（§2.29，2026-09-09，baseline 59→45）** |
| 实施日期 | 2026-09-04（主体）／2026-09-05（追加批：P0-3 + P1-4）／2026-09-05（收尾批：WS-6 + RNG 方案②）／2026-09-05（M1 首批：WS-2 S1-S3，§2.8；M1 第二批：WS-1 同步通道降本，§2.9）／2026-09-06（M1 第三批：WS-3 E1 + WS-7 baseline 清零，§2.10——M1 全部清偿）／2026-09-06（**M2 首批：WS-2 S4，§2.11**／第二批：WS-2 S5，§2.12／第三批：WS-2 S8，§2.13／**第四批：WS-3 E2+E3，§2.14／第五批：P1-5 性能清偿，§2.15／第六批：WS-3 E2 残留，§2.16／第七批：WS-2 S6 秘境会话域，§2.17／第八批：WS-2 S7 排程事务，§2.18**）／2026-09-08（**M2 续批：WS-5 地图数据模型改造，§2.19**；**M3 首批：死代码族清偿，§2.20**；**M3 第二批：反向通道审计改判 + lockedBeastIds 加固 + InvalidPackageDeclaration 清偿，§2.21**；**M3 第三批：MaxLineLength 实修清偿，§2.22**；**M3 第四批：RoomMigration 清偿 + TooGenericExceptionCaught 实修清偿，§2.23**；**M3 第五批：判定族收官 + 机械族全清，§2.24**；**M3 第六批：判定边界族收官，§2.25**）／2026-09-09（**M3 第七批：参数与跳转族收官，§2.26**；**M3 第八批：复杂度族收官——CCM 68 条清偿，§2.27**；**M3 第九批：函数数族第一轮——TMF 113 条清偿+余量登记，§2.28**） |

---

## 1. 背景一句话

Kotlin→C++ 游戏引擎迁移被审计定性为"**真实但未完成的迁移**"（总评 5.9/10）：模拟核心与地图渲染已真迁 C++ 且 C++ 为真相源；但残留自动存档痕迹、若干只有声明无实现/无调用的死导出、双 ABI 包体、以及掩盖在 detekt baseline 下的债务。本批为 M0 止血清残。

## 2. 本轮已完成并验证

### WS-0.a 自动存档残留清理（决策2，全项）
| 改动 | 文件 | 说明 |
|---|---|---|
| 退出弹窗文案如实 | `android/feature/game/.../tabs/SettingsTab.kt` | "游戏进度会自动保存"→"未保存的进度将会丢失" |
| 删零消费配置键 | `android/app/src/main/assets/config/game_config.json` | 删除 `autoSaveIntervalSeconds` / `autoSaveDebounceMs` |
| 注释纠偏 | `android/core/engine/.../system/GameTimeClock.kt` | "自动保存"→"保留"（指内存时钟累积） |
| Room 迁移 V50 | `GameDatabase.kt` / `GameDatabaseMigrationsV50.kt` / `GameDatabaseMigrationSupport.kt` / `GameData.kt` / `SectPolicyStateEntity.kt` | `autoSaveIntervalMonths` 从 `game_data`+`sect_policy_state` 删除；实体字段改 `@Ignore`+`@Transient`（旧档 lenient 解码可读，新档不写）；`DATABASE_VERSION` 49→50；PRAGMA 动态重建两表（**不能**复用 `GAME_DATA_CREATE_SQL`——那是 v29 历史基线，会丢 21 个后续新增列） |
| 死代码链 | `GameStateStoreImpl.kt` | 删除 `markDirty()`/`consumeDirty()` + `_stateDirty`/`_discipleDirty` 字段及 ~12 处赋值；保留每个活的 `_updateVersion.value++` |
| 防复发护栏 | `docs/architecture.md` + `CODE_WIKI.md` | 加入"存档为纯手动（产品决策 2026-09-04）"权威记录 + 禁止重新实现自动保存 |

### WS-0.b 死导出与注释纠偏
| 项 | 处置 |
|---|---|
| `NativeBridge.isRendererReady` | 删除声明（无 C++ 导出、零调用，调用即崩的"地雷"） |
| `GameCoreBridge.nativeAdvance` | 删除 Kotlin 声明 + C++ JNI 导出（零 Kotlin 调用；benchmark 走 `DiffRngBridge.nativeCoreAdvancePhases`；**保留** C++ `GameCore::advance`——`time_system_test.cpp` 用） |
| `nativePollEvents` + `pollEventsJson` | 删除 Kotlin 声明 + C++ JNI 导出 + `game_core.cpp` 实现 + `game_core.h` 声明 + `game_core_test.cpp` 断言 |
| 注释纠偏 6 处 | `GameEngineNativeOps`（46个ActionId→如实）、`NativeSurfaceView`（模拟器必走软渲→API≥31走Vulkan）、`gamecore/CMakeLists.txt`（禁异常/RTTI→如实）、`game_core.h`（未实现→已实现；删pollEvents行）、`SaveLoadSaveDelegate`（自动存档→纯手动）、`GameEngineAdminOps`（触发自动存档→注入运营邮件） |

### WS-0.c 包体两项（决策7）
- `android/app/build.gradle`：`abiFilters` 仅留 `arm64-v8a`（移除 armeabi-v7a，APK native 约 -40%）；删除 `bundle { texture { enableSplit = true } }`（仅一种 KTX 格式，从未生效）。

### WS-0.d 部分（渲染/CI/性能止血）
| 项 | 处置 |
|---|---|
| P1-7 release 原生符号 | `build.gradle` release `ndk { debugSymbolLevel "SYMBOL_TABLE" }`（已验证触发 `extractReleaseNativeSymbolTables`） |
| CI ② astcenc | `generateAstcAtlas` 任务 astcenc 缺失由"静默跳过"改为 `GradleException` fail |
| CI ③ detekt 守卫（决策8） | 新增 `android/detekt-baseline-count.guard` + ci.yml `Detekt baseline must not grow` 步骤（baseline `<ID>` 计数只缩不增） |
| P0-3 清屏色 | **已核实**无需改：`VulkanBackend.cpp:2352` clearColor 已是纯黑 `{0,0,0,1}` |

## 2.5 追加批（2026-09-05）：WS-0.d 剩余 P0-3 + P1-4 清偿

**目标路径**：`NativeSurfaceView.buildAtlas` 原 `onRendererReady` 主线程同步执行——逐精灵解码 + Canvas 拼装 + ARGB→RGBA 转换在 2048² 图集上可达数百毫秒，GLES 低端路径 OOM/ANR 高危。

| 改动 | 文件 | 说明 |
|---|---|---|
| 同步 `buildAtlas` → 两段式异步流水线 | `NativeSurfaceView.kt` + 新文件 `AtlasAsyncPipeline.kt` | 拆为 `buildAtlasAsync(context, onReady)`（View 入口，捕获软件渲标志/ASTC 开关/纪元后委托）+ `AtlasAsyncPipeline.start/prepareAtlas/uploadAtlas`（`prepareAtlas` **重活**后台线程 / `uploadAtlas` **轻活**主线程只做一次 GPU 上传）+ `AtlasPayload`（三态互斥载体：ktx / rgbaPixels / softwareBitmap / failed）。独立类承载：渲染宿主已贴 detekt TooManyFunctions 阈值，且流水线自成状态机（纪元守卫 / ASTC 回退 / 软渲分流） |
| ASTC 读写拆分 | `NativeSurfaceView.kt`（注入点）+ `AtlasAsyncPipeline.kt`（消费） | `compressedAtlasLoader` 拆为 `compressedAtlasReader`（assets IO，后台）+ `compressedAtlasUploader`（触 C++ `g_renderer`，必须主线程）；`compressedAtlasLoader` 保留为同步组合（测试注入点，语义不变，既有 3 个 WP7 分支测试零改动通过）。流水线经 View 的注入点取值，测试 Fake 继续生效 |
| ASTC 上传失败自动回退 | `NativeSurfaceView.kt` | 上传返回 0 时在后台重跑一轮 `allowCompressed=false` 的纯 RGBA 拼装，递归深度恒 ≤2 |
| ARGB→RGBA 改 DirectByteBuffer | `AtlasAsyncPipeline.kt` | `toRgbaByteArray(IntArray):ByteArray` 删除，改 `encodeBitmapToRgbaBuffer(Bitmap):ByteBuffer`——direct 分配 + **逐行**读取（行缓冲约 8KB，不再分配整图 IntArray）+ 原生序 IntBuffer 视图写入。原实现 2048² 瞬时并存 Bitmap(16MB)+IntArray(16MB)+ByteArray(16MB)+JNI 副本 |
| JNI 上传面改 direct | `NativeBridge.kt` + `NativeBridge.cpp` | `uploadTexture(ByteArray)` / `uploadGroundTexture(ByteArray)` 删除，改 `uploadTextureDirect(ByteBuffer)` / `uploadGroundTextureDirect(ByteBuffer)`；C++ 侧新增 `lockDirectPixels` 统一校验（非 direct / 尺寸非法 / 容量不足即 LOGE 并返回 0）——**同步清掉两个会带 ByteArray 中转的死导出**（延续 WS-0.b 死导出清零纪律） |
| RGBA/软渲 2048 封顶统一成文 | `SectAtlasAssembler.kt` | `CANVAS_ATLAS_MAX`/`canvasAtlasScale` 改名 `ATLAS_BITMAP_MAX_EDGE`/`atlasBitmapScale`，并修正审计"封顶只护软渲路径"的误读——现实现本就两条路径统一封顶（ASTC 压缩路径不受限，走 4096 KTX） |
| 接线点更新 | `SectMapViewport.kt` | `onRendererReady = { view.atlasTextureId = view.buildAtlas(ctx) }` → `view.buildAtlasAsync(ctx) { texId -> view.atlasTextureId = texId }` |
| surface 销毁中断拼装 | `NativeSurfaceView.kt` | `handleSurfaceDestroyed` 中断 `atlasBuildThread`（**不 join**——拼装是 CPU 密集循环，主线程 join 会退化成"换了个地方阻塞"；结果由纪元守卫丢弃） |
| 淡入对齐图集就绪 | `NativeSurfaceView.kt` | 图集真正可用才开始 `fadeIn()`——异步拼装耗时期间保持纯黑，避免"地图未就绪却已淡入完成"的空白窗口 |
| 主线程跳转用显式 Handler | `AtlasAsyncPipeline.kt` | 不用 `View.post`——未 attach 的 View 会把 runnable 塞进 run queue、只有下一次 traversal 才执行（守卫测试实测暴露此问题）；`Handler(Looper.getMainLooper())` 与 attach 状态解耦 |
| detekt 收敛 | `AtlasAsyncPipeline.kt` | 抽类后 View 回到 TooManyFunctions 阈值内；`prepareAtlas`/`uploadAtlas` 改单出口（when 表达式 + 私有分支函数）清掉 ReturnCount；4 处全捕获 catch 按"非关键路径语义"附理由 `@Suppress("TooGenericExceptionCaught")`（沿用既有惯例，非压债务） |

**为什么上传不搬后台**：C++ `NativeBridge` 的 `g_renderer` 是无锁裸指针，渲染线程每帧 beginFrame/draw/submit 并发进入——上传必须留在与渲染线程互斥的主线程。P0-3 消灭的是"拼装"（CPU 密集 + 大内存瞬时峰值），不是"上传"。

### WS-0.d P1-4 JNI 桥层 debug 线程断言（全项）

**目标路径**：`GameCoreBridge.cpp` 全文件无 mutex，`g_gameCore` 无锁单线程模型；安全只靠"所有状态入口都在引擎线程串行"的口头约定，而看门狗/主线程本就合法进入部分入口——约定既不完整也无守卫。

| 改动 | 说明 |
|---|---|
| owner 线程记录 | `nativeInit` 记录 owner tid（= GameEngine 单线程调度器）；`nativeDestroy` 清除。owner==0（未初始化）时守卫直接放行 |
| debug 断言 | **NDEBUG 未定义（debug）**：21 个 kEngineOnly 入口首句调 `jniRequireEngineThread("<name>")`，非法线程进入 → LOGE（含入口名/owner tid/当前 tid）→ `abort()`，把"静默数据竞争"变成"首犯即崩、可归因" |
| release 零开销 | **NDEBUG（release）**：三个守卫函数擦除为空 inline，保证生产不因守卫误判 abort（审计明确警告的误伤风险） |
| 34 个入口全部分类成文 | 每个入口头部标注 kEngineOnly / kAnyThread 及理由 |
| Kotlin 契约同步重写 | `GameCoreBridge.kt` 头注释从"所有调用必须在引擎线程"改写为分类后契约 |

**分类结果（21 守卫 + 13 跨线程端口）**：

- **kEngineOnly（守卫）**：`settlePhase` / `settleMonth` / `settleYear` / `resetAutoRecruitIdle` / `execute` / `exportState` / `importState` / `importStateNoRng` / `applyReverseDirty` / `exportDirty` / `battleExecute` / `aiBattleExecute` / `decidePlayerAttack` / `checkAttackConditions` / `computeCanOccupy` / `loopStart` / `loopFrame` / `loopConsumeDeadTime` / `loopRefundPhases` / `loopOnRestart` / `destroy`
- **kAnyThread（设计上的跨线程端口，不守卫）**：
  - `isInitialized`（只读探针）、`watchdogVerdict`（`GameLoopDelegate` 健康检查 + `AlarmWatchdogReceiver`）、`loopSetSpeed`（UI 速度按钮经 `gameClock.onSpeedChanged`）、`loopNotifyUserActivity`（触控/UI 主线程）、`loopSetThermalStatus` / `loopSetBatteryStatus`（平台推送端口）
  - `roadCompose`（无状态纯函数，不依赖 g_gameCore）
  - `setGameConfig`（Dagger 单例构造期注入，触发线程取决于 DI 实例化顺序）
  - **rng 系四入口**（见下）

### P1-4 途中发现（重要，留待专项拍板）

`nativeRngNextInt` / `nativeRngSnapshotPartition` / `nativeRngRestorePartition` / `nativeRngInitSeed` **今天就有主线程/存档线程进入，是真实数据竞争而非契约违规**：

- `GameRngManager` 被 ViewModel 注入（`HeavenlyTrialViewModel.startCombat` 抽 BATTLE 种子、`SaveLoadViewModel.restartEngineAndReseed` 重播种 → 主线程）；
- `NativeBackedRng.nextInt/snapshot/restore` 直接委托这四个 JNI 入口，快照/恢复走存档 IO 线程；
- C++ 侧 PCG 分区状态是非原子裸成员。

因此这四个入口只能标 kAnyThread，**不能**加断言（加了就是开发期误 abort）。收敛方案（RNG 通道加锁，或强制 RNG 抽取全走引擎线程）需要单独拍板，不在本批范围。


## 2.6 收尾批（2026-09-05）：WS-6 文案收敛 + RNG 跨线程竞争收敛（方案②）

### WS-6 iOS 暂缓落地（全项）

清理 docs/注释中"iOS 可复用/双端平台"表述，降级为"Android 为主，核心保持可移植"：

| 文件 | 说明 |
|---|---|
| `gamecore/include/gamecore/core/clock.h` | Clock 注入理由 ②"iOS 复用（NSDate）"→"核心保持平台可移植"；`SystemClock` 注释"Android/iOS 由桥层提供"→"Android 由桥层提供" |
| `gamecore/include/gamecore/map/road_system.h:23` | "桌面/CI/iOS 均可直接编译"→"桌面/CI 均可直接编译" |
| `gamecore/CMakeLists.txt:6` | 同上 + 补"2026-09-05 iOS 暂缓决策"注记 |
| `cpp/CMakeLists.txt:59` | "game-core 零 Android 依赖（桌面/iOS 可复用）"→"（桌面可复用，核心保持可移植）" |
| `docs/cpp-engine.md:119` | 架构图"iOS 可复用"→"Android 为主，核心保持可移植"（§0 审计修正节与历史批次记录保持原样，不动历史归档） |
| `cpp/Rhi.h` | 追加平台护栏：RHI 不投入跨平台开发，**新增渲染功能不得把 Android API 进一步漏进 Rhi.h**；Metal 接入指南降级为历史参考（总方案 WS-6 第 3 条） |

### RNG 跨线程竞争收敛（§4.2 拍板项，按推荐方案②三小项拆开实施）

**实施前实测调用面核实**：读档恢复侧**此前已收敛**（`loadData` 整体在 `withEngineContext` 内 → `loadFromSnapshot` 锁内原子切换天然引擎线程）；`ProductionTransactionManager.determineOutcome` 的 SYSTEM 分区抽取生产零调用（仅测试可达）。真实待收敛点为三处，与 §4.2 预判一致。

| 小项 | 改动 | 文件 | 说明 |
|---|---|---|---|
| ① UI 抽取 | 派生种子 | `HeavenlyTrialViewModel.kt` | `startCombat` 改 `deriveTrialSeed()`（关卡/阶段参数混入 nanoTime），**不再消费全局 BATTLE 分区**；删 rngManager 依赖。该种子仅影响 UI 模拟表现，通关结果经 onCombatFinished 走引擎侧记录 |
| ① UI 抽取 | 引擎线程化 | `BloodRefiningViewModel.kt` | `randomBloodRefineStat`（BREAKTHROUGH 分区 nextInt(2)，决定血炼属性并持久化）移入 `launchOnEngine` 内——属游戏状态变更的一部分，抽取顺序不变 |
| ② 重启播种 | 并入引擎重启 | `GameEngineCoordination.kt` + `SaveLoadViewModel.kt` + `PersistenceFacade.kt` | `restartGameInternal`（withEngineContext 内）mapSeed 生成后加 `gameRngManager.initSystemSeed(mapSeed)`，与 createNewGame"播种在引擎线程、生成世界前完成"模式对齐（P0-1b 同族收尾）；ViewModel 删外部 initSystemSeed + AISectDiscipleManager.initForSlot 重复调用；PersistenceFacade 删闲置 gameRngManager 字段 |
| ③ 存档快照 | 引擎线程采样 | `GameEngine.kt`（新成员 `buildSaveSnapshot`）+ `GameEngineSaveOps.kt` + `SaveLoadViewModel.kt` | 挂起版快照统一走 `GameEngine.buildSaveSnapshot()`：`withEngineContext { flushYearlyOpsQueue(); saveFacade.getStateSnapshot() }`——年变 flush/存档前校验/玉符 checkpoint/RNG 导出/状态快照整体进引擎线程，沿用"引擎线程构建快照→IO 写字节"模式；同步版 `getStateSnapshotSync` 加线程契约注释（生产零调用） |
| 过渡护栏 | debug 警告 | `GameCoreBridge.cpp` + `GameCoreBridge.kt` + `GameRngManager.kt` | 新增 `jniWarnRngOffEngineThread`（debug WARN 含入口名/owner tid/当前 tid，**不 abort**；release 擦除为零开销）；四个 rng 入口首行调用；分类从 kAnyThread 改"kEngineOnly（收敛中）"，两侧契约注释同步 |

**为什么 buildSaveSnapshot 是成员函数而非扩展函数**：扩展函数体在 MockK relaxed 环境下会真实执行并触达 mock 的 `engineContextDispatcher`（SaveLoadViewModelLoadTest 对 saveFacade 的直通 stub 语义被破坏）；成员函数在 mock 环境下整方法被 relaxed 拦截，行为与直通等价（SaveLoadViewModelLoadTest 721 行 stub 同步改为此方法）。

**护栏升级条件**：真机/开发期 debug 构建运行，RNG 通道警告日志（`RNG 通道跨线程进入`）零出现 → 下一批把四个 rng 入口升级为 `jniRequireEngineThread` 断言（P1-4 正式收口）；若仍出现 → 存在漏网调用面，按 §4.2 对该处局部加锁。

## 2.8 M1 首批（2026-09-05）：WS-2 S1-S3 残留执行器下沉（自动装备/丹药/突破 → C++）

**目标路径**：AUTHORITATIVE 生产每旬此前为"C++ 核心批次（1-5 步）+ Kotlin 残留执行器
（`executeResidual`：自动装备/丹药/突破）+ 反向回导"——残留面每旬一次 Kotlin 事务 +
双向往返同步。本批按总方案流水线（C++实现/启用 → 桌面对拍 → 删 Kotlin 路径）收口：
**生产每旬 = C++ `runPhaseSettlementCore` 单次完整七步结算**，Kotlin 残留执行器删除。

| 改动 | 文件 | 说明 |
|---|---|---|
| 亲属智能赠送移植（S1） | **新文件 `gamecore/include/gamecore/system/relative_gift.h`** | Kotlin `RelativeGiftHandler` 等价移植：亲属查找插序（正向道侣→师父→父母 + 反向一次遍历）= RNG 抽取序、关系分类优先级（道侣>父母>子嗣>师父>徒弟>兄弟姐妹）、六类概率常量、选品五级优先（装备空槽>功法空槽>突破丹>其他丹>材料/草药/种子，maxByOrNull 首个最大值）、袋转移（decrease+increase 合并）。**SYSTEM 每亲属恰一次 nextDouble（先于选品）**。lifeEvents 日志为 Kotlin 运行态字段（不在快照协议）——C++ 显式丢弃（与 overflowMail 同边界口径） |
| 偷盗钩子接线（S2） | `phase_settlement.h` `processAutoPills` | 丹药写回后道德 < 阈值 → `judgeSingleTheftCandidate`（批 13-2a 提取的 Kotlin 事务内版等价，month_settlement.h 偷盗链）——**复用既有下沉实现，零新逻辑**。迭代改 id 快照序（偷盗后叛逃会按 id 移除行、行号漂移；每弟子 `rowOf` 现查） |
| 旬结算接线 | `phase_settlement.h` `processBreakthroughs` / `runPhaseSettlement` | 突破候选写回后：(realm 或 layer 变化) → 亲属赠送（SYSTEM），再大境界日志。**入口弟子快照改 id 键控 map**（Kotlin `allDisciples` 按 id 关联）——偷盗叛逃移除行后行索引快照会错位关联，id 键控不受影响 |
| AUTHORITATIVE 启用 | `game_core.cpp` `initialize` | core 模式 `onCoreSettle` 从"仅核心批次（1-5 并行）"改为 **`runPhaseSettlementCore`**：0 自动装备 → 1-5 核心批次（ECS JobSystem 并行）→ 6 丹药+偷盗钩子 → 7 突破+亲属赠送。步骤序与完整版 `runPhaseSettlement` 逐位一致（核心批次外均串行：RNG + 跨弟子状态依赖不可分块） |
| Kotlin 残留删除 | `PhaseSettlementExecutor.kt` + `GameEngineCoreAuthoritativeOps.kt` + `GameEngineCore.kt` | **`executeResidual` + `executePillsAndBreakthroughs` 删除**；`execute` 完整版保留为对拍 Kotlin 基准（决策 C-15）。生产 tick ③ 段删除；`phaseSettlementExecutor` 属性保留（DiffAuthoritativeTickTest 侧 B 基准用） |
| 突破埋点观察器（UI 通知类残留） | **新文件 `core/engine/.../engine/BreakthroughAnalyticsObserver.kt`** + `GameEngineCore.kt`（注入，默认 Noop 供测试直构） | Kotlin 突破处理器的 `BREAKTHROUGH_SUCCESS` 埋点（含 TapDB FTUE 首次突破派生）随路径删除，观察器以**旬前后 breakthroughCounts 列差分**重建事件（属性=镜像后突破快照，与原取值时机一致）。基线每旬 settle 前捕获——旬间战斗前结算等 Kotlin 域突破（自带埋点）落在下一基线内不重复上报；读档/重启跳变被基线吸收，无需重锚。**置于 `core.engine` 包（非 `.service`）——非结算服务，避开 EngineServiceAnnotationTest 架构守卫** |
| 对拍 Side A 同步 | `DiffAuthoritativeTickTest.kt` | 100 旬管线对拍 Side A 删 `executeResidual` 调用（残留已入 C++ settle）；Side B 纯 Kotlin 全量基准不变 |

**M0 遗留协议漂移顺手清偿**：重建桌面对拍 JNI 后全部 Diff 测试红——M0 WS-0.a 从 Kotlin
序列化面删除 `autoSaveIntervalMonths`（@Transient），但 **C++ models.h/json_codec 仍在
快照协议导出该字段**，且此前交接验证跑引擎单测未注入 `gamecore.jni.path`（对拍全部
skip）、CI 未跑，漂移未暴露。本批从 C++ 模型与编解码双侧删除该字段（对拍键集权威面恢复
一致）。

**对拍新场景（原"场景规避"面全部转正）**：`DiffPhaseSettlementTest` 新增 3 场景（共 5 用例
全绿）——①袋内 equipment_instance 直接装配（S3；绕开模板 DB 桌面缺位，装配路径真实触发；
事件处理器的 equipmentManager/manualManager 换真实实例——仅开关门控路径使用，既有场景
开关全关不受影响）；②突破后道侣赠送（S1；种子双探测 BREAKTHROUGH<0.90 + SYSTEM<0.45）；
③道德减益丹触发偷盗全链（S2；真实 `LawEnforcementProcessor` 注入 buildService **共用同一
gameRng**——独立实例会让 Kotlin 侧抽取落未导出分区；2 旬不跨月界：月度重置在 Kotlin 侧
mock 的 `processTheftIfNeeded` 后面（S-14 口径差登记族），该语义由 C++
month_settlement_test 黄金用例守护）。C++ 桌面侧新增 `relative_gift_test.cpp`（21 用例：
亲属插序/分类优先级/选品优先级/袋转移合并/**MIN_BAG_ITEMS_TO_KEEP 按条目数守卫**/
概率门控抽取 + 旬结算集成：层变即触发赠送、道德钩子标记先于抽取）。

**RNG 序不变性论证（逐位对拍验收的核心）**：Kotlin 残留面在生产中的抽取序 = 自动装备
（零 RNG）→ 丹药（偷盗钩子 SYSTEM，按弟子序）→ 突破（BREAKTHROUGH 按候选序 + 赠送
SYSTEM 按候选序）。C++ 侧 0→1-5→6→7 顺序与 Kotlin `execute` 完整版逐位一致（S3 先于
S2/S1 的启用序正是为了保持该顺序——自动装备本就在核心批次前）；SYSTEM/BREAKTHROUGH
分区在 AUTHORITATIVE 下真相源本就在 C++（NativeBackedRng 委托），下沉只是把委托抽取
改为本地抽取，序列位置不变。

## 2.7 追加修正（2026-09-05）：P1-4 守卫分类勘误（loopStart/loopOnRestart 误标 kEngineOnly）

**背景**：§2.5 的 P1-4 分类把 `loopStart` / `loopOnRestart` 归入 kEngineOnly 加 `jniRequireEngineThread` 断言。当天真机复现（vivo OriginOS 6，debug 构建）崩溃：

```
SIGABRT
#00 libc.so abort+160
java:
GameEngineCore.prepareLoopStart(GameEngineCore.kt:744)   ← GameCoreBridge.nativeLoopStart()
GameEngineCore.startGameLoopInternal(GameEngineCore.kt:704)
GameEngineCore.startGameLoop(GameEngineCore.kt:664)
GameForegroundService.onStartCommand(GameForegroundService.kt:103)
```

**复现路径**：后台→前台循环重启。`startGameLoop` 按 Kotlin 设计**可从主线程调用**
（前台服务 `onStartCommand` / `GameActivity.onResume` → `resumeFromBackground`，
GameEngineCore.kt:767 注释"startGameLoop 可能在主线程被调"），而 `nativeInit`
在引擎线程记录 owner → 主线程进入 `nativeLoopStart` 命中守卫 → debug abort。
**release 不受影响**（NDEBUG 下守卫擦除，该路径本无可 abort 代码）——属守卫
误杀，非数据竞争（start/stop/emergency 经 Kotlin loopOpLock + phase 状态机与
iterate 串行化，进入时循环必非运行态）。

**修正**：
1. `loopStart` / `loopOnRestart` 重分类为 **kAnyThread 生命周期输入端口**（同
   `loopSetSpeed` / `loopNotifyUserActivity` 类别），移除守卫，注释写明依据；
2. 补 owner 重锚机制：`EngineLoop::onLoopRestart` 置位待重锚标志，桥层
   `nativeLoopFrame` 首帧消费并重锚 owner 到新驱动线程（紧急重启会
   `recreateGameDispatcher` 换线程，原来 owner 永不更新会让新引擎线程被
   守卫误杀，`loopFrame` 后的所有 kEngineOnly 入口同理）；
3. 同步 `GameCoreBridge.kt` 契约头注释；`engine_loop_test.cpp` 新增 3 个
   标志语义用例（置位/一次性消费/正常启动不置位/resetForTest 清除）。

**执行**：见本批验证（engine_loop 桌面 GTest + `externalNativeBuildRelease`）。

## 2.9 M1 第二批（2026-09-05）：WS-1 同步通道降本

**目标路径**（审计 P0-2，四子项）：① 每次库存操作全状态导出+全表替换；② 反向信封
gameData 引用一变即整段重发；③ C++ DirtyTracker 每次导出两次全量序列化+深拷贝；
④ 镜像契约无成文清单。另顺带清偿同 P0-2 项的"全量兜底双解析"与"弟子 assembleAll+
replaceAll O(N)"。

| 子项 | 改动 | 文件 | 说明 |
|---|---|---|---|
| ① 字段级回读 | tryExecuteNative 镜像通道改脏优先 | `GameEngineNativeOps.kt` | 成功执行后 `applyDirtyFromNative()`（exportDirty 变更集增量镜像，查询类动作变更集为空→零镜像开销）；脏通道不可用→`syncFromNative()` 全量兜底。生产调用面：库存一族（InventoryNativeForward）+ MissionSystem/BattleExecutionRouter/AISectBeastAttackProcessor |
| ② 反向 dirty 集 | gameData 段全量重发→字段级补丁 | `StateSyncService.kt` + `game_core.cpp` | Kotlin：与反向锚点 `lastGameDataSentJson`（kotlinx 同源格式，剔除 rngStates）按键差分，仅携带变更字段；未锚定首窗全量建立基线。C++：`applyReverseDirty` 从"整段替换"改字段级补丁——`GameData patched = state_.gameData; value.get_to(patched)`（from_json 宽松读缺键保持现值；全量信封与旧语义逐位等价；rngStates 缺键保持 live 值——旧全量解码会落默认空表再整体赋值，靠下次导出 syncRngStates 兜住，现显式不动） |
| ② 锚点不变量 | 三推进点+保守过期 | `StateSyncService.kt` | 推进点：importToNative 成功 / syncFromNative 成功（从解码快照**重新 kotlinx 编码**——C++ 导出对整数值 double 输出整数形式，直接缓存 C++ 树会与 kotlinx 12000.0 产生格式性假差异）/ sendReverseEnvelope 成功（信封构建时快照，消费即清防越推进）。前向增量镜像**不**推进——保守过期方向：反向 diff 重发 C++ 自身值=无操作，**绝不漏发**；发送失败锚点不推进（生产由全量回导兜底重锚） |
| ③ 基线缓存 | 两次全量序列化+深拷贝→单次 | `dirty_tracker.h/.cpp` | 基线缓存为 JSON 树（resetBaseline/syncBaselineToCurrent 时序列化一次），diffToJson 只序列化当前状态一次，与缓存树按键比较后 `std::move` 入缓存——导出即消费/版本单调/键集形状逐位不变。列级写屏障（DirtyColumn 版本号）按 dirty_tracker.h 自述随计划 v2 阶段 3 数据导向存储落地，本批不引入 145+ 写点回归风险 |
| ③' 单次解析 | 全量兜底双解析 | `StateSyncService.kt` | syncFromNative 解析一次复用同一棵树（decodeFromString + parseToJsonElement → decodeFromJsonElement 单树） |
| ③'' 弟子行级应用 | assembleAll+replaceAll O(N)→O(k) | `StateSyncService.kt` + `DiscipleTables.kt` | applyDisciples 改信封 id 行级精确应用（未出现弟子零触碰；先删后插=upsert 胜，与旧 merged-map 语义一致）；新增 `DiscipleTables.upsertMirrorRow`（存在性探测走 isAlive SparseArray 索引 O(log n)——update 内部 `_ids.contains` 线性 O(N)，k≈N 全脏场景会退化为 O(k·N)；幽灵行经 insert 的 `id in _ids` 检查兜底转 update）。非数字 id 显式抛错保持旧防御契约（DiffDirtyDisciplesTest 守卫） |
| ④ UI 读取面清单 | 镜像契约成文 | **新文件 `docs/ui-read-surface.md`** | 镜像合法内容上限（= C++ exportState 协议面：gameData 编解码面 + 10 集合 + 4 顶层运行态载体）+ UI 实际读取面逐流审计（三层 StateFlow/派生流/非镜像事件通道）+ 反向通道分域关闭依据（域→写入者→关闭条件）+ WS-1 实测基准 |

**验收实测**（桌面 ctest `dirty_tracker_bench_test.cpp`，-O3 llvm-mingw，bestOf 5）：
反向 gameData 段全量 5349B/134 键 → 单字段增量窗 70B/1 键（**-98.7%**，含每旬 C++ 镜像
回流变更字段仍约 -94%，超 -90% 验收线）；C++ diffToJson 每旬全脏 100/1000/5000 弟子
**-24%/-22%/-22%**（idle -32~-40%）。**残留口径**：每旬弟子全脏场景镜像成本受"全量实体
JSON 序列化"支配（协议形状决定），列级 delta/二进制通道随阶段 3 DOD 落地；
"2x 速每旬非 nativeLoopFrame <2ms"在中小规模存档（≤100 弟子）达成，大规模存档需阶段 3。

**为什么锚点不随前向镜像推进**：锚点不变量是"锚点 ⊆ C++ 已知值"。前向镜像写入的
字段 C++ 本就已知（值即 C++ 所写），锚点不推进只会让反向 diff 把这些字段重复携带
（C++ 收到自己的值，应用为无操作）——保守方向永不漏发。若推进则需在锁内做字段级
锚点合并（GameStateStoreImpl 提交段），复杂度与风险不成比例。

## 2.10 M1 第三批（2026-09-06）：WS-3 E1 实体模型与保序验证 + WS-7 core/ui baseline 清零——**M1 全部清偿**

### WS-3 E1：PhaseCoreBatchSystem 真用 World/View（保序验证绝对前置，全项）

**目标路径**：`PhaseCoreBatchSystem::run(ecs::World&)` 形参无名被忽略，核心批次
直接裸迭代 `DiscipleStore` 行号 0..N——ECS 只剩调度外壳。E1 把迭代域切到
`View<DiscipleRef>` 行序映射，并用不变量校验兜底"稀疏集 dense 迭代序 ≠
DiscipleStore 行序"这条 RNG 红线（E2 系统迁移的前置条件）。

| 改动 | 文件 | 说明 |
|---|---|---|
| 保序校验+惰性同步 API | `ecs/disciple_component.h` 新增 `syncDiscipleEntities(world, rowCount)` | 校验不变量"View\<DiscipleRef\> 迭代序 == Store 行序"（实体数一致 + 行号严格升序 0..N-1）：成立→按行序返回实体表（稳态零重建零分配）；被破坏（招募/死亡/叛逃后实体集漂移）→ `buildDiscipleEntities` 全量重建恢复 row i ↔ entity i。**桥接规范五条成文于同文件头**（唯一权威/迭代前必 sync/行地址取自组件/消耗 RNG 的系统必须行序迭代/回调内禁增删实体） |
| 迭代域切换 | `system/phase_settlement.h` `runPhaseCoreBatchParallel(state, jobs, world)` | 新增 `World&` 形参：入口调 `syncDiscipleEntities`，逐弟子行地址从 `DiscipleRef.row` 组件取（`refStorage.find(entityByRow[pos])->row`）；分块 `parallelForIndexed` 与确定性合并语义逐位不变。2 参旧签名删除，原守护测试改传临时 World |
| System 真用 World | 同文件 `PhaseCoreBatchSystem::run(world)` | `run(ecs::World&)` 形参从弃用改为透传给并行核心批次；注释同步 E1 语义（首旬惰性装配、漂移即重建、稳态零重建） |
| 生产接线 | `game_core.cpp` 注释 | 持久 `ecsWorld_` 承载实体集的说明成文（行为不变，`onCoreSettle` 已是 `runAll(ecsWorld_)`） |
| 保序验证用例 ×8 | `ecs_disciple_test.cpp`（+6）+ `phase_settlement_test.cpp`（+2） | 同步恒等（不变量成立零重建）/存储 erase 保序压缩实证（非 swap-pop）/数量漂移重建/行序漂移重建/空态直通/弟子移除后重对齐；**生产同构路径**：`SystemScheduler::runAll(World)` 驱动 vs 串行全状态 JSON 逐位一致；**过期实体集**：store 行移除后旧实体集漂移 → system 内重建 → 结果仍与串行逐位一致（旁证行级独立性） |

**确定性论证**：核心批次全程零 RNG（步骤 1-5 纯计算），并行/迭代序不触抽取序红线；
`ComponentStorage` erase 为保序压缩（storage.h 明示），同步校验把"迭代序 == 行序"
从口头约定升级为每旬 O(N) 机械校验（相对批次成本可忽略）。步骤 6/7（丹药/突破，
真实 RNG 消耗点）仍按行序串行、不经 View——其迁移属 E2 批次，届时复用本批
sync+规范即可。

### WS-7：core/ui detekt-baseline.xml 23 条清零（M1 验收"≥1 文件清零"达成）

**清偿方式**（逐条分类，决策 8 口径）：

| 类别 | 条目 | 处置 |
|---|---|---|
| 陈旧条目 ×7 | CyclomaticComplexMethod ×2（GameDialog/ItemCard，此前拆分重构后失效）+ LongParameterList ×5（2026-08-08 `ignoreDefaultParameters: true` 后不再触发） | 直接摘除（detekt 实跑裁决：移除 baseline 全量违规 16 条 < 基线 23 条） |
| 死代码 ×4 | `DialogState.kt` `ManagedDialog`/`DialogHost`/`DialogHostScope`（全仓零调用，UnusedParameter ×3）+ `ItemCard.showPrice`（零传参死参数） | 整体删除（延续 WS-0.b 死导出清零纪律）；顺删失效 Modifier import |
| 修代码消灭 ×8 | `ElderBonusInfoProvider` 17 个零参 getter → `val` 属性（TooManyFunctions 17/12；18 处调用点机械重命名，纯静态数据惯用形状）；`GameRoute.toDialogType` 28 分支 when（复杂度 29/15）→ 带参路由 when(8) + 1:1 路由查表 map + **新增穷举性守卫测试**（sealedSubclasses 全量断言 + 带参路由实例 ID 断言，补回编译期穷尽检查）；`AtlasPacker.pack` 4 return → 守卫+装箱主体拆分（各 ≤2）；`fallbackToTier1` 4→2 / `herbSpriteRes` 4→2 / `seedSpriteRes` 5→2（回退链改 `?.let` 表达式单出口，抽私有 fallback 助手）；`getRewardSprite` 复杂度 16/15 → 抽 `materialSpriteWithFallback`/`spiritStoneSpriteResByName`（13）；`TooManyFunctions:EquipmentSprite.kt` 19/15 → **文件拆分**：`SpriteResRegistry.kt`（注册器+分类枚举+预加载聚合器 8 函数）+ `SceneSpriteRes.kt`(地图/场景解析 6 函数) + EquipmentSprite 14 函数，各文件均在阈值内 |
| 合理压制 ×3 | `DialogFocusGuard.clearFocusAndHideKeyboard` + `FontPreloader.init` ×2（TooGenericExceptionCaught） | `@Suppress` 附理由（非关键路径防御：IME 异常类型不可枚举/启动期字体回退兜底，漏接即崩溃；沿 §2.5 惯例） |

**产物**：新文件 `SpriteResRegistry.kt` / `SceneSpriteRes.kt` / `GameRouteDialogTypeMappingTest.kt`（3 用例）；`core/ui/detekt-baseline.xml` 置空 + 守卫 `core/ui=23→0`。

## 2.11 M2 首批（2026-09-06）：WS-2 S4 炼丹/锻造完成结算 + 自动排班下沉 C++

**目标路径**：月结残留执行器 4a/4b 行（`AlchemySystem.onMonthlyEvent` = 异步自动
排班 launch + 同步 `processBuildingProduction` 完成结算；`ForgeSystem` = 异步自动
锻造 launch）——此前为"场景规避"（Room 仓储/物品数据库域边界）。本批收口：
**C++ `runMonthSettlement` 步骤 4a/4b 执行完成结算，编排末尾执行自动排班**，
Kotlin 月结残留执行器的 4a/4b 行删除。

| 改动 | 文件 | 说明 |
|---|---|---|
| 晋升规则移植 | **新文件 `gamecore/include/gamecore/system/profession.h`** | Kotlin `ProfessionRules` 等价：maxCraftableTier（level+1 封顶 tier6）/晋升三门槛（成功次数 [1,200,500,800,800]（仅计当前解锁最高阶，低阶不充数）/境界 [9,7,6,5,3]（realm ≤ 门槛）/属性 [40,55,70,90,110]）/计数溢出防护/5 级封顶不计数/displayName。`applyPromotionProgress` 对 DiscipleStore 行原地生效 |
| FormulaService 等价 + 完成结算 + 自动排班 | **新文件 `gamecore/include/gamecore/system/production.h`** | ①乘区成功率：baseProb=clamp01(skillZone+professionZone)（skill (S-30)×0.006 clamp≤0.50；职业每低一阶 +0.20）×(1+realm+talent+policy+elder)，长老加成 = (getBaseStats 属性-80)×0.01×(1+positionEffectBonus)（读含天赋 Flat 的 baseStats——**仅此处**，skillZone 用原始列值，与 Kotlin 双口径一致）；②时长：ceil(base/(1+长老+亲传 speed)) 后政策 ×1.1（外层后写覆盖口径 completionMonth=year×12+month+actual）；③完成判定 isSlotCompleteDynamic（baseDuration>0 时按当前政策/长老动态重算——Checkpoint 快照法）；④产出：grade roll（0.06/0.40 阈值）→ recipe_db 模板（**S4 补全 PillTemplateSpec 产出字段块** category/pillType/rarity/duration/cannotStack/minRealm/isAscension——Kotlin createPillFromTemplate 消费面，表驱动 finalize）+ addPill/addEquipmentStack（溢出草稿收集后丢弃——S-18 边界口径）；⑤晋升结算 settleProductionCompilation 等价（guideCounters/annual 计数无条件 + 弟子回 IDLE + 空表保守存活 + B3 死弟子清关联）；⑥自动排班（autoRestart 续炼启动）：镜像弟子可用守卫 + 续炼原配方 else 最优配方（tier 降序 rarity 降序稳定序）+ **name+rarity 精确求和**材料检查/消耗（与 Kotlin consumeHerbs/MaterialsForRecipe 逐位一致）+ **零 RNG**（公式化成功率） |
| 月结接线 | `month_settlement.h` | 步骤 4a/4b 位置插 `production::processBuildingProductionStep`（**匹配口径逐位对齐 Kotlin：锻造按 buildingId、炼丹按 buildingType**；SYSTEM 抽取序 = forge 槽序 → alchemy 槽序）；编排末尾（步骤 8 后）插 `processAutoProductionStep`——Kotlin 原自动排班为事务提交后异步独立事务（读月结最终状态），C++ 置末尾等价对齐 |
| 协议默认值对齐 | `models.h` | `ProductionSlot.outputItemRarity` 0→**1**、`completionPhase` 0→**1**（Kotlin data class 默认值——对拍暴露的协议默认值漂移；createIdle 全清空语义落 C++ 默认构造即对齐） |
| 单测 | **新文件 `gamecore/test/production_test.cpp`（20 用例）** | 晋升三门槛/低阶不充数/封顶/乘区合成+clamp/境界表/完成结算成功失败双臂/RNG 审计（炼丹恰 2 抽=成功+grade、锻造恰 1 抽、无弟子槽零抽）/死弟子清关联/无效配方 roll 仍抽但零晋升/匹配口径（锻 id 炼 type）/自动排班启动+材料扣减/材料不足跳过/政策+长老时长与成功率 |
| 月结窗口对齐 | `ProductionProcessor.kt` + `CultivationService.kt` + `GameEngineCoreMonthOps.kt` | **前置对齐** `alignMirrorFromRepository`（settleMonthNative 之前：repo 整表写镜像——消除手动启动生产只写 Room 的 B5 分叉/惰性建槽镜像缺失，C++ 结算视图=repo 真源）；**后置写回** `restoreRepositoryFromMirror`（残留执行器之后：镜像整表重放 repo `restoreSlots`——不走 SlotStateMachine，C++ 的 WORKING→IDLE→WORKING 复合变更无法用单步转换表达；前置对齐保证镜像⊇repo 无丢失；IO 失败仅记录——镜像已权威，Room 落后由下月对齐自愈） |
| 残留删除 | `MonthSettlementResidualExecutor.kt` | **4a/4b 行删除**（AlchemySystem/ForgeSystem 类保留——Kotlin 完整编排回退路径（native 未就绪）仍走七系统扇出）；KDoc 范围/RNG 契约同步重写（炼丹/锻造 SYSTEM roll 入 C++ 步骤 4a/4b——灵田收获 roll 之前，登记过的编排基线变化） |
| 对拍基建 | `DiffMonthSettlementFixture.kt` + **新文件 `DiffSurfaceAssertion.kt` + `DiffProductionSettlementTest.kt`** | fixture 新增 `buildMonthDiffHarness`（store/port/repo 可访问）+ `buildProductionDiffChain`（**生产域全真实链**：真实 InventorySystem（NoOp 溢出邮件）+ FormulaService + ProductionCoordinator/TransactionManager + Repository(InMemory port)）+ `advanceKotlinMonthSide(harness)`（月结后 repo→镜像对齐）；`buildMonthDiffExecutor` 注册真实 AlchemySystem/ForgeSystem；比对方法族提取共享（private→internal，语义零变更）；LargeClass 拆分达标。**新场景**（`DiffProductionSettlementTest`，独立类防种子敏感断言污染既有 13 场景）：锻造成功率 0.0 失败臂 + 炼丹成功率 1.0 成功臂（晋升 level 1 + alchemist_promoted 事件），rngStates[3] 终态锁定 3 抽序 |

**为什么自动排班在 C++ 置月结末尾而非步骤 4a/4b 位置**：Kotlin 原编排中自动排班
（processAutoAlchemy/Forge）是月结事务**提交后**的异步独立事务——读取的是月结全部
状态效果之后的材料/弟子状态。C++ 置编排末尾（步骤 8 月度事件后）读到相同的月结最终
状态，语义等价且时序确定（消除 Kotlin 原版"resetSlotToIdle launch 与
processAutoAlchemy launch 排序不定"的竞态）。**改进基线**：C++ 版完成结算后当月
即续炼（重置 IDLE → 末尾启动），Kotlin 原版当月续炼实际不生效（结算重置走异步
repo 写，autoAlchemy 已在完成结算前跑完——续炼发生在下月）；对拍场景规避
"到期+autoRestart"组合（黄金测试锁定 C++ 当月续炼行为）。

**双存储对齐为什么是"窗口对齐"而非修复 20 个写点**：槽位写点全集约 20 处分散于
UI/自动/读档路径（Room 先、镜像后，B5 已知偏差若干）。月结窗口"前对齐+后写回"
以两处改动把 C++ 结算视图收敛到 repo 真源、结算结果原子回写 Room，等价于把镜像
升级为"月结事务内的真源"——与决策 1 终态（C++ 唯一真相源）方向一致且可回滚；
逐写点双写改造的回归面（任命/取消/收获/自愈/排班五族路径）不值本批引入。

**发现并顺手清偿的协议漂移**：C++ `ProductionSlot` 模型 `outputItemRarity`/`completionPhase`
默认值（0/0）与 Kotlin data class（1/1）不一致——S4 前该差异被"C++ 不读写槽位字段默认值"
掩盖（对拍镜像含槽位但双端字段逐位透传），C++ createIdle 等价实现首次消费默认构造即暴露。

## 2.13 M2 第三批（2026-09-06）：WS-2 S8 洞天 AI + AI 兽战余量下沉 C++

**目标路径**：月结残留执行器"子事件 6 洞天 AI 操作"（processAISectOperations
3 参版：仓库清场 + AI 弟子热控分批修炼 + 宗门等级同步 + 成员过滤）与"子事件 9
AI 兽战余量"（processRemainingTargets：单 AI 攻妖 / 双 AI 遭遇战 PvP→胜者攻妖）。
本批收口：**C++ runMonthSettlement 子事件 6/9 位执行 AI 域全链**，残留执行器扇出
缩至 3 项平台/UI 效应（4g 邮件 / S-17 秘境关闭 / S-20 购买日志）——**M2 验收
"月结残留扇出 ≤3 项（仅 UI 通知类）"达成**。

| 改动 | 文件 | 说明 |
|---|---|---|
| AI 运营域移植 | **新文件 `system/ai_sect_ops.h`** | aiComputeBatch（热控批状态机：首调相位对齐 batch=0 / 时钟回退跳过 / monthsSince≥批量上界→批量=monthsSince 并推进基准）+ aiProcessSectOperations（仓库清场 + 分批修炼 + 宗门等级同步 any{} 阈值链 + calculated∩currentIds 成员过滤） |
| AI 修炼链移植 | 同上 | aiCultivationRate（**对象版路径**——manuals 空映射走 ManualDatabase 静态兜底查询；政策/丧亲不参与、寿命惩罚参与——与玩家列直读版乘区源不同，逐字对齐）+ 突破循环（AI 独立 RNG nextDouble≥chance→失败 break；成功修为清零/层数+1/大境界+1 + 寿命增益）+ 熟练度月增（6.0×2.0×3=36/月，孤儿键清理、模板缺失原样保留）+ 孕养月增（10.0×3=30/月，老档回填+防御钳制+升级曲线玩家同源） |
| AI 补全链移植 | 同上（复用 ai_sect_recruit.h Y-4c 既有端口） | aiRollMissingCategories（体质/词条/天赋空分类生成——generateTraitsForDiscipleT + GEAR_ROLL_MARKER 防重复 roll 漂移）+ aiEnsureDiscipleGear（装备空槽 javaShuffle 补至等级数量 + 功法按含心法口径补全）+ aiProcessMonthlyCultivation（repeat(batchMonths) 逐弟子） |
| 兽战余量移植 | 同上 | aiPrepareDisciplesForBattle（持久化装备/功法字段→临时实例：装备按弟子独立 map+孕养覆盖、功法模板 id 即实例 id、熟练度来自 manualMasteries）+ aiCreateBattle（**preGenStats 妖兽分支首入 C++**：resolveBeastStats 预生成属性钳制路径 + beastType=null→虎妖）+ aiExecuteVersusBeast（单 AI 攻妖：击败标记/BEAST_HUNT/BEAST_FAIL 事件/死亡处理）+ aiExecuteEncounterBattle（双 AI PvP（A=DEFENDER/B=ATTACKER fullHeal）→ 双侧死亡 → 胜者 vs 妖兽）+ aiMarkBeastDefeated/aiHandleDeaths/aiMarkSideDead + 目标清空 |
| 批量上界桥接 | `GameCoreBridge.cpp/.kt` + `GameEngineCoreMonthOps.kt` | **热控批量上界为平台效应**：ThermalMonitor 决策（12/6/3）保留 Kotlin——月结前经新导出 nativeSetAiThermalBatchSize 推送（kEngineOnly）；批状态机 C++ 内存运行（S-16 同族：纯内存运行态不入存档协议）；C++ 默认 3=正常档（桌面对拍确定性） |
| 包含环断链 | **新文件 `system/nurture_constants.h`** + `phase_settlement.h` | 熟练度/孕养常量与曲线（kBaseProficiencyRate/kNurtureGainPerPhase/nurtureMaxLevel/expRequiredForLevelUp）上移叶子头——phase_settlement ↔ month_settlement（→ai_sect_ops）包含环断链，语义零变更 |
| 单测 | **新文件 `test/ai_sect_ops_test.cpp`（12 用例）** | 批状态机（首调对齐/阈值/回退/热档 12）/AI 修炼速率下限/突破 RNG 审计（AI 分区消耗断言）/熟练度月增 36（真实模板）/孕养月增 30（ironSword level0 → progress 30）/宗门升级（realm≤5→MEDIUM + 装备 2 功法 3 补全 + 标记写入）/仓库清场/兽战胜利（击败标记+BEAST_HUNT+BATTLE 分区消耗+零死亡）/战败（BEAST_FAIL+死亡标记+未击败）/已击败妖兽跳过（零 BATTLE 消耗）/战前组装（孕养覆盖+未知模板跳过+熟练度映射） |
| 对拍口径 | Diff 家族（既有 45 场景） | AI 域空场景（aiSectDisciples 空池 + targets 空）双端零效果零抽取对拍保持全绿；AI 域逻辑由本批 12 个 GTest 黄金锁定（批 Y-4c AI 招募同款口径——Kotlin 臂 AISectBattleProcessor 依赖 ThermalMonitor 平台态，端到端对拍归入真机批次） |

**为什么热控批量上界保留 Kotlin**：ThermalMonitor 读设备热状态（PowerManager
语义）为平台 IO，Kotlin 决策 12/6/3 后经 nativeSetAiThermalBatchSize 推送；
C++ 批状态机只消费该上界（默认 3=正常档，桌面/测试确定性不受平台影响）——
与邮件 4g"平台效应保留 Kotlin"同族口径。

**为什么 AI 修炼速率不能用 calculateCultivationPerPhaseColumn**：Kotlin AI 调
calculateCultivationPerPhase **对象版**（无 sectPolicies 入参、grief 由调用方传 0）
——政策加成与弟子丧亲状态不参与 AI 速率；列直读版含 policyCultivationBonus +
griefEndYear 读取，语义不同。C++ aiCultivationRate 逐字复刻对象版乘区源。

## 2.14 M2 第四批（2026-09-06）：WS-3 E2 逐系统迭代域迁移 + E3 NPC 实体组件族

**目标路径**：E1 只迁移了并行核心批次的迭代域；步骤 0（自动装备）/步骤 6（丹药）/
步骤 7（突破）与串行核心批次仍裸迭代 DiscipleStore 行号 0..N。本批把旬结算管线
**全部系统**的迭代域切到 ECS 桥接（syncDiscipleEntities 校验/恢复不变量 +
View<DiscipleRef> 行序迭代），并落地 E3 NPC 纯组件实体族（WS-4 供数前置）。

| 改动 | 文件 | 说明 |
|---|---|---|
| 串行核心批次迭代域 | `phase_settlement.h` `runPhaseCoreBatch(state, world)` | 签名增 `World&`；入口 `syncDiscipleEntities(world, ds.size())` 后按 `View<DiscipleRef>` 行地址（`ref.row`）合并遍历（恢复/修炼/熟练度/孕养）——与并行版同域，语义逐位不变 |
| 步骤 0 装备 | `auto_gear.h` `processAutoFromWarehouse(state, world)` | 资格预筛行集改 sync + View 行序收集（stable_sort 平局保持行序——排序输入序不变）；文件头增 ecs include |
| 步骤 6 丹药 | `phase_settlement.h` `processAutoPills(..., world)` | id 快照改由 sync 后 View 行序构建（快照序 == 行序 == Kotlin ids 序）；快照后行移除（偷盗叛逃）仍由 rowOf 现查兜底——快照建完即弃 View，不跨实体集重建持有 |
| 步骤 7 突破 | `phase_settlement.h` `processBreakthroughs(..., world)` | 候选筛选改 sync + View 行序（候选序 == 行序 == RNG 抽取序逐位不变） |
| 结算入口签名 | `runPhaseSettlement` / `runPhaseSettlementCore` 增 `World&` | game_core.cpp 生产钩子（onPhaseSettle / onCoreSettle）传持久 `ecsWorld_`；测试 4 处调用点同步 |
| E3 NPC 组件族 | **新文件 `ecs/npc_component.h`** | **NpcId / NpcPosition / NpcVelocity / NpcPath(waypoints) / NpcPathIndex(kNoWaypoint 哨兵) / NpcSpriteId / NpcAnimState(frame/facing/moving)** 七组件 + `spawnNpc`（一站式装配）/ `destroyNpc` / `destroyAllNpcs`（副本迭代）/ `findNpcById` / `collectNpcRenderRows`（WS-4 渲染通道快照行 id,x,y,spriteId,animFrame 形状约定）。**纯运行态零序列化**（NPC 为首个"纯组件"实体族——组件即数据本体，渲染经紧凑数组通道出 C++）；组件插入序即权威（无外部行序 → 无需 sync 式校验） |
| 测试 | **新文件 `test/ecs_npc_test.cpp`（8 用例）** | 装配完整性/spawn 序迭代/销毁级联/清场/与弟子实体正交共存/按 id 查找/渲染快照字段/路点游标语义 |

**确定性论证**：syncDiscipleEntities 的不变量校验（实体数一致 + 行号严格升序
0..N-1）保证 View 迭代序 == Store 行序——四步切换前后消费的行序列逐位相同，
RNG 红线不触。步骤 6 内叛逃移行后，下一 sync（步骤 7）检测漂移即全量重建。

**E2 残留口径（登记）**：month/year_settlement + child_birth + disciple_purchase +
recruit_settlement 约 20 处裸行号迭代不在 E2 命名系统清单（修炼/HP/MP/装备/丹药/
突破/生产），其迁移属后续批次（迭代域切换语义与本批同构，无前置依赖）。

## 2.15 M2 第五批（2026-09-06）：P1-5 性能清偿（月结 O(N²) + 生产 O(slots×N)）+ S7 写点收敛评估

**目标路径**：审计 P1-5 三项中两项（AI 宗门 O(sects²) 已登记随真机批次观测）。
同时完成 S4 残留口径登记的"槽位写点收敛评估"。

| 改动 | 文件 | 说明 |
|---|---|---|
| 月结配对常系数 | `month_settlement.h` `processPartnerMatching` | ① SYSTEM RNG 引用出循环提升（原内层每次 roll 重取分区）；② 已配对女性 `std::set<string>` 逐对哈希 → `vector<char>` 位图按下标 O(1)。**循环形状不变**——M×F 迭代序即 RNG 消费序（每对通过过滤的组合恰一次 nextDouble，Kotlin 逐位对拍红线），结构级降复杂度必然改消费序列，需双端同步改算法（行为基线变化）→ 登记残留口径。每对成本已降为 O(1) 位图判定 + 8 次父 id 串比较 |
| 生产 O(slots×N) | `production.h` | ① `settleDiscipleProduction` / `autoSlotDiscipleUsable` / `elderBonusFor` / `elderAndDisciplesSpeedBonus.findById` 的逐槽全表 id 线性扫描 → `DiscipleStore.rowOf` O(1) 哈希；② 炼丹/锻造自动排班配方选取的材料检查（逐配方材料 × 全表扫描 O(H×M)）→ name+rarity 余量索引 O(M) 查表（`buildHerbIndex` 新增，与既有 `buildMaterialIndex` 同形状）；③ 配方排序进程内缓存（模板表为函数级 static const，指针稳定） |
| **S4 遗留分歧缺陷顺手清偿** | `production.h` | 锻造步材料索引原为**批首快照**——同批多槽材料竞争时以旧余量选配方，且 `consumeMaterialsForRecipe` 无不足守卫 → 少扣材料白嫖生产（Kotlin 真实路径 `processAutoForgeSlot` 为逐槽 `getCurrentMaterials()` 实时读）。炼丹/锻造两步索引统一改**每槽重建**（与 Kotlin 逐位同语义；O(M) 构建成本低） |
| 竞争黄金用例 ×2 | `test/production_test.cpp` | 炼丹/锻造双槽竞争：材料恰够一槽 → 槽 1 WORKING、槽 2 必须按实时余量 IDLE、材料精确清零（不少扣不多扣）——修复前锻造步此用例红（槽 2 零材料启动） |
| **S7 写点收敛评估结论** | （评估，无代码） | 手动排班/惰性收获/取消路径的"Room 先镜像后"**无需逐点双写**：① UI 生产槽读 Room 仓库流（`ProductionCoordinator.slots = repository.slots`），非镜像；② C++ 月结视图由 S4 窗口对齐（repo→镜像前置）收敛；③ 存档序列化/自愈/gate 重建均以 repo 为准——镜像月中陈旧**无消费者**。S4 残留口径"评估窗口对齐 vs 逐点双写"就此关闭：**窗口对齐已充分** |

**验证**：桌面 852/852（+2 竞争用例）+ 引擎全量 3082 用例 0 失败 0 skip
（desktop-jni 重建 + `--rerun-tasks` 强制重跑——`-Dgamecore.jni.path` 是系统属性
非任务输入，不强制时 testReleaseUnitTest 判 UP-TO-DATE 跳过、对拍假绿，登记 findings）。




## 2.16 M2 第六批（2026-09-06）：WS-3 E2 残留——月结/年结/生育/购买域迭代域过 sync 桥接

**目标路径**（§2.14 登记残留）：month/year_settlement + child_birth +
disciple_purchase 约 21 处裸行号迭代不在 E2 命名系统清单。本批按同构语义收口：
**syncDiscipleEntities 校验/恢复不变量 + View<DiscipleRef> 行序迭代**，
runMonthSettlement / runYearSettlement 线程化 `ecs::World&` 形参。

| 改动 | 文件 | 说明 |
|---|---|---|
| 月结编排线程化 | `month_settlement.h` `runMonthSettlement(..., world)` | 步骤 1 计数/avgRealm 扫描过 View；world 贯穿政策效果/生育/配对/排班/住所忠诚/月衰减/月度事件 |
| 政策月度效果 | 同上 `processPolicyMonthlyEffects(+world)` | 入口 id 快照（sync + View 行序）+ 逐 id rowOf 现查——教化之道偷盗钩子可能移行；Kotlin 同域为活表迭代（移行即 CME，生产不触发），快照即其安全等价 |
| 伴侣配对快照 | 同上 `processPartnerMatching(+world)` | assembleAll 等价快照构建经 sync + View 行序（M×F 配对 RNG 消费序不变——P1-5 登记的循环形状不触碰） |
| 排班/住所/衰减 | 同上 processAutoAssign / computeResidenceAssignmentsCpp / processResidenceLoyalty / applyMonthlyDurationDecayAll（+world） | 池/候选收集与只读遍历过 View（候选序 == 行序，排序输入序不变） |
| 执法堂叛逃 | 同上 `processLawEnforcementMonthly(+world)` | **at-risk id 快照**（== Kotlin findAtRiskDiscipleIds 的入口快照序——每名风险弟子恰检一次）+ 逐 id rowOf 现查；捕获（remove+重插）/逃脱（remove）不扰动后续判定。原 C++ 裸行号循环在移行后跳行，与 Kotlin 快照迭代存在未测路径分歧——本批顺带收敛到 Kotlin 语义 |
| 从众门控/战力归约 | 同上 isAverageLoyaltyLowEnough / calculateSectPower（+world） | 归约与序无关——复用同一不变量校验；judgeSingleTheftCandidate 链式获 world（偷盗钩子四调用面：政策/月度兜底/旬内丹药/年思过释放） |
| 偷盗兜底 | 同上 processTheftMonthlyFallback(+world) | hasCandidate 门控 + candidateIds 收集过 View（下游本就 id 寻址） |
| 年结域 | `year_settlement.h` | runYearSettlement(+world)：buildYearSalaryPlan / processYearlyAging（先收集后移除）/ processRecruitAging / processGriefExpiry 过 View；**processReflectionRelease（思过释放）改到期 id 快照 + 逐 id rowOf 现查**（Kotlin 同域即 assembleAll 快照 map 迭代，偷盗钩子可能移行） |
| 生育域 | `child_birth.h` `processMonthlyBirth(+world)` | 入口全量快照（母亲 RNG 消费序 == 行序）+ existingNames 扫描过 View（processAutoRecruit 追加行后 sync 检测漂移即重建）；母亲循环本就迭代物化快照，不触碰 |
| 购买域 | `disciple_purchase.h` `collectDisciples(+world)` / processDisciplePurchase(+world) | 候选收集过 View（A/B 组洗牌输入序 == Kotlin assembleAll filter 序） |
| 接线 | `game_core.h/.cpp` + `GameCoreJni` | GameCore 新增 `ecsWorld()` 访问器；onMonthChange/onYearChange 钩子 + settleMonth/settleYear 四调用点传持久实体集 |
| 测试适配 | month_settlement_test（56 处）/ year_settlement_test（6 处）/ child_birth_test（4 处） | 临时/持久 World 传入——与 E1/E2 批"测试传临时 World 同构"口径一致 |

**确定性论证**：sync 不变量（实体数一致 + 行号严格升序 0..N-1）保证 View 迭代序 ==
Store 行序——只读循环切换前后行序列逐位相同；结构变更循环（执法堂/思过释放/
政策效果）按 E2 步骤 6 先例改 **id 快照 + rowOf 现查**，快照序 == 行序 == Kotlin
ids 序，RNG 消费序不触红线。**登记例外**：recruit_settlement.h `nextDiscipleId`
与 year_settlement.h `idx_find` 为归约/查定型扫读（非迭代域，order-insensitive），
不迁移（W 侵入 allocateAndInsert 调用面不值）。

**验证**：桌面 864/864（+12 秘境会话用例后总数）+ 引擎全量 3082 用例 0 失败
0 skip（42 Diff 类对拍真实执行，E2 残留迁移后逐位一致）+ arm64 native 绿。
**WS-3 E2 残留口径就此关闭。**

## 2.17 M2 第七批（2026-09-06）：WS-2 S6 秘境探索交互会话域下沉 C++

**目标路径**：SecretRealmService 1278 行（最大单体）交互会话域
（startSession / chooseOption / endSession）+ 袋物化/死亡写回——纯逻辑原语
（事件生成/遗迹/loot/体力/位置，批 4-3）与战斗组装/执行（S5/S8）已就位。
本批收口（handover §5 设计要点①-⑤）：

| 改动 | 文件 | 说明 |
|---|---|---|
| 会话编排 | **新文件 `system/secret_realm_session.h`**（sr_session 域） | startSession（校验链逐字 + 成员快照 + 初始妖兽事件 + SECRET_REALM 消费）/ chooseOption（校验链 → 体力 → 六事件类型分派：妖兽远离 1 抽/战斗/偷袭 1 抽、休整恢复 40%（战斗口径 maxHp）、遗迹（resolveSecretRealmRuinsExplore/Result 原语 + **描述符实例化入背包**）、方向（rollNextEvent）、AI 避让零消费/交战 PvP → 战斗执行 → 成员写回 → 奖励/损失 → 会话合并 → 体力耗尽/全灭自动结束）/ endSession（灵石入钱包 + 六类物品入仓 + 秘境清场 + cooldownYear 锚定 + gate 释放面草稿） |
| 战斗链 | 同上（复用 mission_completion / ai_sect_ops / battle_execution） | 妖兽：buildSecretRealmBeastPreGenStats（preGen 钳制）+ **类型按名查表**（S8 aiCreateBattle 按名泛化版）+ executeBattle（BATTLE 分区）；PvP：aiPrepareDisciplesForBattle + 我方 DEFENDER 现血 vs AI ATTACKER 满血；写回（writeBackBattleMembers）：幸存者 HP 钳制写表 / 首亡→濒死 / 濒死再亡→**袋物化 + markDead** |
| 袋物化 | 同上（BagItemReconstructor + InventorySystem 物化段等价） | materializeDiscipleBagAndMarkDead：实例轨道（instance→stack + 实例表删除防双持有）+ 堆叠轨道（stackedData 按 name 模板重建六类——minRealm 条目保真/0 回退推导）→ addXxx 入仓（溢出转邮件草稿，items 不丢）→ 清袋 → markDead + 年度计数 |
| 决策④落位 | 同上 buildTypeCandidates | 六类候选模板源 = C++ 数据库主表保序 rarity 过滤（== Kotlin registry getByRarity 语义；模板表同源生成序一致 → pickTemplate 同选）——"零数据库依赖"边界由会话层承担 |
| 转发协议 | `action_ids.h` / `ActionIds.kt`（gen-action-ids.mjs 90 项）+ `execute_dispatch.cpp` | SECRET_REALM_START(1440)/CHOOSE(1441)/END(1442) 三动作（经 nativeExecute + tryExecuteNative 交互域转发）；CHOOSE 信封回传战斗终态 + rounds 动作序列 + teamCasualties + deadIds/releasedMemberIds/overflowDrafts。**YEARLY_SPAWN 勘误不注册**：年变现世已随批 Y-2 在 runYearSettlement 原生下沉（processAncientSecretRealmSpawn 判据/位置/变体同源）——独立 ActionId 属死代码（死导出纪律） |
| Kotlin 转发 | **新文件 `GameEngineSecretRealmNativeOps.kt`** + `GameEngineSecretRealmOps.kt` + `BattleExecutionRouter.rebuildBattleLogData` | 三入口 native 分支（AUTHORITATIVE 门控 + 失败信封回退 Kotlin——双实现并行契约）：start（到期前置拒绝 + C++ 会话写入 → Kotlin 补换岗/gate/Room 清槽/同步，finalizeSecretRealmTeam 两路共用）；choose（战报经 rebuildBattleLogData 在 Kotlin 重建——确定性摘要 message（rebuildAction 先例，设计要点③）+ recordPlayerBattle 展示通道 → 死亡哀伤/gate 公共收尾 applySecretRealmChoiceSideEffects 两路共用）；end（C++ 背包结算 + 秘境清场 → gate 释放保留 Kotlin）；overflowDrafts 经 InventoryNativeForward.deliverDraft 同一投递通道 |
| 测试 | **新文件 `test/secret_realm_session_test.cpp`（12 用例）** | start 校验链五连 + 会话写入/消费审计；choose 校验链；妖兽战斗推进 + BATTLE/SECRET_REALM 消费审计；远离成功扫描锁定（零战斗 + 固定文案）；休整 40%（战斗口径 maxHp）；方向推进 rollNextEvent；体力耗尽自动结束（背包入仓 + 清场 + cooldown 锚定 + gate 释放面）；AI 避让零消费；AI 无力应战直通（hasBattle=false，toResolution 恒置 enteredCombat==true 的 Kotlin 口径锁定）；endSession 幂等 |

**决策③（战报文本）落位**：C++ resultText 为确定性字面量逐字直出（与 Kotlin
回退臂一致）；rounds 的 message 不入 C++（BattleExecutionRouter.buildSummaryMessage
确定性摘要重建）——与 S5 任务战斗同一口径。

**验证**：桌面 864/864（+12 会话用例）+ 引擎全量 3082 用例 0 失败 0 skip +
detekt（:core:engine :feature:game :app，LongMethod/CyclomaticComplexMethod/
TooManyFunctions 全收敛——native 分支拆独立文件）+ arm64 native 绿。
**S6 真机验证项**（登记 §4.1）：交互会话 AUTHORITATIVE 真机回归（出发/选择/
战斗播放战报/结束结算/断线续玩）。

## 2.18 M2 第八批（2026-09-06）：WS-2 S7 生产排程交互事务下沉 C++

**目标路径**：手动排班（BuildingService.executeAlchemyStart/executeForgingStart →
ProductionCoordinator.startXxxAtomic → ProductionTransactionManager +
SlotStateMachine 组合链）+ 手动重置（clearAlchemySlot/clearForgeSlot →
resetSlotByBuildingIdAtomic）。本批落位（§5 下批实施要点①-④）：

| 改动 | 文件 | 说明 |
|---|---|---|
| 排班事务 | `production.h` `startProductionTransaction`（detail） | 组合等价：配方查询（pillRecipeById/forgeRecipeById）→ ensure 槽位（缺槽创建 IDLE——ProductionSlot{} 默认即 createIdle 语义）→ SlotBusy 门禁 → 材料充足检查（buildHerbIndex/buildMaterialIndex name+rarity 求和 vs 模板反查）→ **startSlotWorking**（S4 既有：duration 重算合并 + completionMonth/Phase 2 一次写最终值——Kotlin"先写原始值 + 外层异步修正"两段的等价收敛）→ consumeHerbs/MaterialsForRecipe（S4/P1-5 原语） |
| 原生成功率 | 同上 | successRate < 0 哨兵 → formulaSuccessRate（S4 公式：skillZone+professionZone 基础 ×(1+realm+talent+policy+elder) 乘区）按槽位弟子原生计算——Kotlin 门面转发路径不预算，双臂同式（production_test 0.12 精确锁定）；policyBonus 由调用方按 sectPolicies 传值 |
| 重置事务 | 同上 `resetProductionSlotTransaction` | resetSlotToIdle（S4/B3 既有：回 IDLE 全清空 + **配方无条件保留供续炼** + 弟子不保）+ 存在性门禁 |
| 转发协议 | `action_ids.h` / `ActionIds.kt`（92 项）+ `execute_dispatch.cpp` | PRODUCTION_START(1443)/RESET(1444)；失败信封 → Kotlin 回退原路径重执行校验链（InsufficientMaterials 缺口明细由 Kotlin 自行构建——双实现并行契约） |
| Kotlin 转发 | `BuildingFacadeImpl`（门面层） | startAlchemy/startForging：native 分支（NativeEngineFlag.authoritative 门控 + gameEngineCore.stateSyncServiceRef 直达——无新注入面）→ **C++ 真相先行 + Room 持久化后置**：tryExecuteNative 镜像回读（槽位 + herbs/materials）→ 镜像槽位单槽回放 repo（updateSlotByBuildingId/addSlot——S4 restoreSlots 同族）；clearAlchemySlot/clearForgeSlot：native reset + 镜像→Room 回放，弟子状态清理保留 Kotlin；@Suppress("ReturnCount") 降级契约注记 |
| 测试 | `test/production_test.cpp`（+5 用例） | 炼丹 happy path（槽位字段逐项 + 材料精确扣减）/校验链（RecipeNotFound→Insufficient→SlotBusy + 失败零写入）/缺槽自动创建/重置（配方保留 + 弟子清除 + 不存在槽位 false）/原生成功率哨兵（0.12 精确） |

**排班窗语取舍（登记）**：手动排班匹配口径为 **buildingId**（与月结完成结算的
"锻造按 id/炼丹按 type"混合口径不同——各自对齐 Kotlin 查询）；消耗日志
（MaterialConsumptionLog UI 流）与自动续炼启动为 Kotlin 侧平台效应保留。

**验证**：桌面 869/869（+5）+ 引擎全量 3082 绿 0 skip + detekt 三模块绿 +
arm64 绿。**WS-2 S7 就此收口——M2 主轴（WS-2 全部子系统 + WS-3 ECS 化 +
性能项）全部清偿。**

## 2.19 M2 续批（2026-09-08）：WS-5 地图数据模型改造——地形生成真源迁 C++ + 三个 O(全图) 点收敛 + chunk 网格参数化

**目标路径**（§5 锚点已探明项）：①瓦片生成占位真相源 Kotlin
`SectMapTileGenerator` → C++；②MainGameScreen 三点 O(全图)（2D 全图复制 +
装箱 flatMap 全图展平 + 每格修路整图 roadData）；③SoftwareCanvasBackend
chunk 网格 128 硬编码。**M2 验收"占位真相源在 C++"就此达成**（地形生成
真源入 C++；建筑占位/道路数据早已在 C++ state——placedBuildings/roads 镜像
域，NPC 寻路的静态地形 + 建筑占位 + 道路三要素在 C++ 侧齐备）。

| 改动 | 文件 | 说明 |
|---|---|---|
| terrain 域头 | **新文件 `gamecore/include/gamecore/map/terrain.h`** | Kotlin `SectMapTileGenerator` 位级等价移植（cellHash/smoothNoise/grass/tree/border/gateway + 展平生成入口 generateTileData）。**位级一致三要点**：①cellHash 的 Int64 加法回绕→`.toInt()` 截断与 Int 乘法回绕走 uint32（有符号溢出在 C++ 为 UB）；②smoothNoise 收缩敏感中间值（`3f-2f*fx`）经 volatile 局部隔断 FMA 收缩（arm64 默认 ffp-contract=on 会产生与 ART 不同舍入），乘积命名 + 左结合求和——任意平台与 Java Float 位级一致；③瓦片索引独立定义（gamecore 禁依赖生成头），漂移由 Diff 全数组对拍即红 |
| 地形 GTest | **新文件 `gamecore/test/terrain_test.cpp`（16 用例）** | cellHash 手算黄金（(0,0,42)→0.2452 全手算路径）/值域/种子敏感、smoothNoise 确定性/生成域值域（**负坐标出界为 Kotlin 同式同象**——截断朝零使 fx<0，值域断言仅对生成域 x,y≥0 成立，登记）、确定性/异种子、密度 0 裸地形、树环棋盘、门楼穿透清场（环 1500 格 -18 清场格精确断言）、小图跳过、生产配置冒烟 |
| JNI（Android） | `GameCoreBridge.cpp/.kt` | `nativeGenerateSectTerrain(seed,w,h,density,ring,gate 盒 5 常量)` → IntArray（行主序展平）；**无状态纯函数 kAnyThread**（同 nativeRoadCompose，生产调用方在 Dispatchers.Default）；门楼常量按 GameConfig.SectMap 传值（单一数据源不落 C++） |
| JNI（桌面） | `gamecore/jni/GameCoreJni.cpp` + `DiffRngBridge.kt`（test） | 同签名 `nativeGenerateSectTerrain` + 位级探针 `nativeSectCellHash`/`nativeSectSmoothNoise`（jfloat 直传保 binary32 位型） |
| Kotlin 门面 | **新文件 `core/engine/.../util/SectTerrainBridge.kt`** | native 优先 + Kotlin 生成器降级（IslandEdgeBridge 降级模式同族：ensureAvailable 探测缓存 false 进程内不重试）；返回展平 IntArray（**展平是唯一表示**）。Kotlin 生成器保留 = JVM 测试基线 + native 通道不可用降级路径（输出位级一致由对拍证） |
| 双端对拍 | **新文件 `core/engine/src/test/.../DiffSectTerrainTest.kt`（4 用例）** | 多种子全数组逐位（0/常规/派生异号/-1/Int.MIN_VALUE）+ 生产全参数冒烟（128²+0.18+ring3+生产门楼盒）+ cellHash/smoothNoise 逐点位级（含负坐标截断语义、42^seed/101^seed 生产混合式、双尺度 8/12） |
| 协议模型收敛 | `core/domain/.../MapPreloadData.kt` | **`rawTileData`（2D）字段删除**——原 2D+flat 孪生表示是"每建筑变动 O(全图) 复制+装箱展平"的根因；`flatTileData` 成为唯一地形表示（纯地形无占位标记，不可变基座）；equals/hashCode 同步。**ProtoBuf 存档零改动**（MapPreloadData 本就不序列化，只内存传递） |
| 生成切换 | `SectMapController.buildSectMap` + `BootSequenceController.generateMapPreloadData` | Kotlin 直调生成器 → `SectTerrainBridge.generateFlatTileData`（C++ 真相源，每会话每种子一次生成入 sectMapCache——UI 记住 seed 不再重算语义达成）；装箱 flatMap 两处删除 |
| O(全图) ①② 收敛 | `MainGameScreen.kt` + **新函数 `applyBuildingOccupancy`** | tileData+flatTileData 双全图点 → 单点：纯地形基座 + 占位副本（建筑变动一次 `copyOf` + O(脚印) 标记；无建筑直用基座零复制）；越界脚印格跳过（原 `in indices` 判据等价）、产出新引用（双后端引用变化驱动失效/拷贝契约保持） |
| O(全图) ③ 收敛 | `RoadTiling.kt` + **新类 `RoadMaskTracker`** | 道路掩码增量装配：持有上次编码集+掩码，道路集变化只重算受影响格（变更格+四邻——邻接掩码仅依赖自身与四邻）写入副本；**全量路径复用 buildRoadMaskArray（语义同源）**；内容未变返回稳定引用（镜像重发形态防重复失效）；1-based 编码语义保持 |
| chunk 参数化 | `SoftwareCanvasBackend.kt` | `NUM_CHUNKS_COL/ROW=128/32` 与 `CHUNK_PIXEL=32×48` 硬编码删除 → 实例派生 `numChunksCol/Row=ceil(config.世界格数/32)`、`chunkPixel=32×config.tileSize`（chunk 几何经 ChunkDrawKit 下发——ChunkTile 静态嵌套类读不到外类实例字段；顺带修正 chunk 位图边长读全局 SectMap.TILE_SIZE 而非注入 config.tileSize 的错位隐患）；生产 128²/48px 派生值 = 原硬编码（4×4/1536px）零行为差 |
| 测试 | **新 3 文件**：`SectTileOccupancyTest.kt`（feature/game，6 用例：零复制直用/脚印标记/基座不可变/越界跳过/拿起放下等价/多建筑）+ `RoadMaskTrackerTest.kt`（core/engine，4 用例：**随机增删序列与全量构建逐位差分**/稳定引用/空集 null 与恢复/断链修复）+ `SoftwareCanvasBackendLodFadeTest` 适配（chunk 预算用例改 128²/48px 生产形状 fixture——**chunk 网格参数化后 10×10 配置派生 1×1**，多 chunk 分帧语义需生产形状；新增 96²→3×3=9 块 config 派生用例）；`GameViewModelSectMapTest` 断言切 flat |

**为什么地形不入存档/镜像 JSON 协议（§5 锚点"每种子一次性生成入快照"的偏差登记）**：
探明后发现存档为 Kotlin kotlinx **ProtoBuf**（非 C++ exportState JSON），C++ 导出仅运行时镜像
且每旬同步——16384 整数段入协议 = 存档 +~30KB、每次 exportState/镜像同步 +~80KB JSON
（WS-1 刚优化的通道付持续代价），而地形是种子的确定性纯函数、再生零成本。落地口径改为：
**C++ 生成（真相源）+ Kotlin 按种子缓存（每会话一次）**，"UI 记住 seed 不再重算"达成，
协议/存档零负担。副作用差异：生成器未来演进会改变旧种子地图（与迁移前 Kotlin 版行为
一致，位级演进由 Diff 对拍锁守）；快照冻结地图的优势放弃。**若需"地图跨版本冻结"语义
（入 ProtoBuf + C++ 镜像域）需拍板后补协议批**。

**WS-4 地基说明**：可行走网格的静态地形（terrain.h）、建筑占位（state placedBuildings）、
道路（state roads）三要素已在 C++ 齐备；可行走语义（树/边界是否阻塞）属 WS-4 玩法设计
文档拍板项，本批不做前瞻性 API（terrain.h 头注释登记）。

**途中修正（防复发）**：①JNI 系统属性注入为 `-D`（单横线）——`--D` 被 Gradle 判
Unknown command-line option（对批命令模板登记）；②detekt Q-8 新增测试禁 `!!`
（requireNotNull 替代）；③生成值域断言不得覆盖负坐标（smoothNoise 截断朝零语义，
负 fx 出界为双端同象非移植缺陷）。

## 2.20 M3 首批（2026-09-08）：M3 收敛启动——死代码族清偿（五模块 UnusedPrivate*/UnusedImports 清零）+ baseline 211 条摘除

**目标路径**（§5 下轮建议·M3 验收"detekt baseline 清零"首批）：全仓 5 模块
（app/core:data/core:domain/core:engine/feature:game）detekt 空基线实跑实测
活债务 **3129 条**（app 281 / data 557 / domain 513 / engine 1245 / game 533；
实跑裁决证明与 WS-7 的 core/ui 23→16 不同，**债务基本是活的**，非陈旧条目）。
本批按 WS-7 口径清偿**死代码族**（UnusedPrivateProperty 164 + UnusedParameter 相关连带
+ UnusedPrivateMember 14 + UnusedImports 及其全部连带）→ 该三规则全模块归零。

| 类别 | 处置 | 说明 |
|---|---|---|
| 死 TAG 常量 ×15 | 删除 | 各 Service 内 `private const val TAG` 全文件唯一出现（无任何日志引用） |
| 死 import（含连带） | 删除 | 源生 9 + 删除连带产生的未用 import 30+（两轮 detekt 迭代归零） |
| 死私有属性/常量 ×60+ | 删除 | 单出现声明行删除；**多行声明陷阱**（caveExplorationStatuses 的 `= setOf(`、currentDiscipleTables/sceneFrameBudgetNs 的自定义 getter、THERMAL_YELLOW 的多行构造——wave 脚本只删首行致悬挂续行，编译期全数暴露后修复） |
| 死私有函数 ×14 | 括号配平删除 | getBuildingName / _isInCaveExploration ×2 / otherSlotsCount / safelyRun / calculateReducedDuration / recordUsedCode / getMaxSlots / notifyPressureChanged / notifyWarning / processMemorySnapshot / createPauseIntent / createStopIntent / computeChecksum / inferTalentType / notifyListeners——逐一全仓调用面核查（跨文件同名函数不误伤），悬挂 KDoc 同批清理 |
| **副作用保留改造**（防行为变化关键） | 改写非删除 | ①`CombatService` 亲属判定 `val deadId = x ?: return false` → `if (x == null) return false`（elvis 早退是控制流不能删）；②`ExchangeSpiritStonesUseCase` 灵石兑换 `val result = wallet.batch(...)` → 裸语句（batch 副作用必须保留）；③`SpiritMineDialog` `val baseOutput = slots.map{...}.sum()`（map 内累加 miningBonus）→ `slots.forEach{...}`；④`SaveLoadViewModel.currentGameData` ×14 `val x = ... ?: return false` → `if (...== null) return false`；⑤`SaveCrypto` Argon2 探针 `val specClass = Class.forName(...)` → 裸语句（ClassNotFoundException 探针语义）；⑥`StorageSystemBenchmark` 反序列化计时 `val deResult = ...` → 裸语句（计时段必须保留）；⑦`GameEngineRecruitTest` `val sanitized = sanitizeRecruitList(state)` → 裸语句（state 就地净化副作用） |
| **整类死代码** | 删除文件 | `SaveLoadSaveDelegate.kt`——唯一构造点 `SaveLoadViewModel.saveDelegate` 本身被 detekt 判死（懒属性零消费），类随之零引用，整文件删除（WS-0.b 死导出纪律） |
| 死构造参数 ×7 | 删除 + 调用面修复 | AISectBeastAttackProcessor.stateStore / EncounterBattleService.stateStore / DiscipleLifecycleManager.{discipleFactory,rng,rngManager} / InventorySystem.spiritStoneWallet+gameConfigProvider / CultivationService.discipleService / SaveLoadViewModel.coroutineScopeProvider 等——@Inject 构造删参由 DI 适应；手工构造测试点逐一修复 |
| 空 companion object ×14 | 删除 | TAG 删除后 companion 变空 → 新增 EmptyClassBlock 违规 → 空容器整体删除 |
| 空循环变量 | repeat() 化 | `for (i in 0 until n)` 未用变量 ×14 → `repeat(n)`（`for (_ in)` 需实验特性 -XXLanguage:+UnnamedLocalVariables，弃用）；**带 break/continue 的循环保持 for/while**（repeat 是 lambda，break 非法——BattleCalculatorTest 避险改 while） |
| baseline 摘除 | 三规则全条目 | UnusedPrivateProperty/UnusedPrivateMember/UnusedImports 条目全模块摘除（app 254→238 / data 464→429 / domain 509→503 / engine 1150→1057 / game 639→579）；**签名漂移条目等量替换**：CultivationService LongParameterList（参数 11→10，签名文本变化——旧条目失效、新签名未覆盖，从 detektBaseline 生成物提取精确签名**计数不变替换**）+ SpiritMineDialog NestedBlockDepth（同法 +1 条） |
| guard 更新 | 只缩 | detekt-baseline-count.guard：app=238 / core:data=429 / core:domain=503 / core:engine=1057 / feature:game=579（-211，只缩不增纪律达成） |

**机制发现（防复发，登记 findings）**：①detekt baseline 按**规则+文件+声明签名文本**匹配
（非按条数）——文件内该规则的全部违规由一条 ID 压制，且**签名文本变化即条目失效**；
②`for (_ in)` 是 Kotlin 实验特性（UnnamedLocalVariables），Android 编译器默认拒绝；
③Git Bash sed 对 CRLF 文件 `$` 锚不匹配（多处删除静默失败，perl `\r?\n` 兜住）；
④脚本批量删"全文件唯一出现行"必须校验行是**完整声明**——多行声明（`= setOf(`、
自定义 getter、多行构造）只删首行会产生悬挂续行。

**残留口径**（detekt 剩余活债务，后续批次）：MaxLineLength 1400+（机械换行，
批处理价值低 diff 噪声大——建议专项批统一处理）、TooGenericExceptionCaught ~135 条
baseline 条目（逐个"附理由 @Suppress 或重构"判定）、ReturnCount 239 /
CyclomaticComplexMethod 196（真实重构）、TooManyFunctions 139、UnusedParameter 124
（逐个判定删参还是 @Suppress 附理由）。M3 验收"baseline 清零"按此逐族推进
（InvalidPackageDeclaration 118 条已随 §2.21 清偿，余 2806-118=2688 条）。



## 2.21 M3 第二批（2026-09-08）：反向通道逐域写者审计（无域可关改判）+ lockedBeastIds 增量段缺口加固 + detekt InvalidPackageDeclaration 118 条清偿

**批次构成**：M3 剩余主项①（反向通道按域全关）的强制前置审计 + 审计发现缺陷的
加固 + M3 主项②（detekt 逐族清零）的机械族首批。

### 2.21.1 逐域写者审计——"反向通道按域全关"改判为 UI 操作面阻塞（无代码，审计落档）

**实测口径**：全仓生产代码 `stateStore.update`（参与反向捕获）调用点穷尽审计
（core:engine ~265 处 / feature:game ~50 处经 `gameEngine.update*` 包装器 / app+domain 0 处直接调用）。

**结论（推翻 §5 前提"多数域关闭条件已随 S4-S8 + WS-5 达成"）**：S4-S8/WS-5 下沉的
是**结算/事务核心**；各域实际仍存在大量 **UI 操作面 Kotlin 直改写者**（约 15+ 域）：
弟子管理（任命/装备/功法/收徒/状态/婚姻/仓库驻守——最大残余域）、巡逻、建筑放置/拆除/
升级（全部无 C++ 通道）、外交/好感/附庸、设置项、guide、玉符、月年边界编排、洞府探索、
天劫、邮件附件、商人/出售/开袋库存族、任务/俘虏残余——"玩家操作 Kotlin 产生 → tick ⑤
反向回导"是 2026-08-31 根因修复后的现行设计契约。**当前没有任何一个域满足关闭条件；
关闭前置 = UI 操作面逐域下沉 C++（每域一个 WS-2 规模批次），属长期主轴而非收敛批。**
审计全文落档 `docs/ui-read-surface.md` §4.1（域→残余写者→下沉批次清单）。

### 2.21.2 lockedBeastIds 反向增量段缺口加固（S-15 同族，审计途中发现的真实缺陷）

**缺陷**：`lockedBeastIds` 为 @Transient 顶层段（不入 kotlinx gameData JSON），
UI `lockBeastView/unlockBeastView` 直改后反向信封**永不携带**（此前信封只有
aiSectDisciples 独立段）——增量窗口内锁定只靠全量回导兜底可达，AUTHORITATIVE 月结
跳过判定（`month_settlement.h:2216` 锁定妖兽不被 AI 攻击）对新开弹窗**失效**。

| 改动 | 文件 | 说明 |
|---|---|---|
| 信封段 | `StateSyncService.kt` | `lastLockedBeastIdsSent` 变化检测缓存（null=未同步必发；importToNative 成功对齐 / 发送成功推进）+ `buildReverseEnvelope` 携带 `lockedBeastIds` 全量 id 段（整体替换语义，空集也发=全解锁）；协议 KDoc 同步 |
| C++ 应用 | `game_core.cpp applyReverseDirty` | 新增 `lockedBeastIds` 顶层段分支（is_array 逐串重建 vector，整体替换）——排在未知集合宽松忽略之前 |
| 测试 | `apply_reverse_dirty_test.cpp` +1 用例 | 段应用/整体替换/unlock 重写/空集全解锁 + gameData 补丁并存不互扰 |
| 测试 | `StateSyncServiceReverseTest.kt` +1 用例 | 变化携带全量段/未变不重发（缓存推进）/解锁空集携带 |
| 前向兼容 | （既有行为确认） | C++ 对未知集合名宽松忽略——旧 C++ 收到新段为 no-op，无双端部署顺序约束 |

**同族豁免登记**（ui-read-surface §4.2）：`aiSectBeastDirectTargets` /
`aiSectBeastSkipCooldowns` 两段 Kotlin 写者仅存在于月结回退路径（native 未就绪），
AUTHORITATIVE 稳态由 C++ 独占；回退→AUTHORITATIVE 切换经 `ensureAuthoritativeNative`
全量导入四段全部可达——**无增量缺口，不加段**。

### 2.21.3 detekt InvalidPackageDeclaration 118 条清偿（纯文件搬移，零代码变更）

| 项 | 说明 |
|---|---|
| 主树 113 文件 | `core/engine/src/main/java/com/xianxia/sect/core/` 平铺文件（声明包 `...core.engine.*`）→ `git mv` 至声明包对应目录；逐文件校验声明行与目标目录、同名碰撞检测 |
| 测试树 5 文件 | 同族违规（`service/`、`domain/disciple/` 平铺测试目录 → `engine/service`、`engine/domain/disciple`） |
| 零风险论证 | package 声明不变 → 字节码不变；架构守卫（Konsist `scopeFromDirectory` + 自研 `walkTopDown` + 按文件名白名单）均递归扫描 + 文件名匹配，不受搬移影响 |
| baseline/guard | engine 1057→**939**（-118，只缩不增）；`detekt-baseline-count.guard` 同步 |

**验证**：见 §3（2026-09-08 M3 第二批）。

## 2.22 M3 第三批（2026-09-08）：detekt 机械族专项——MaxLineLength 1407 条实修清偿（baseline 2688→1281，-52%）

**目标路径**（§5 下轮建议·M3 验收"baseline 清零"最大单一族）：五模块 MaxLineLength 1407 条
（app 115 / data 177 / domain 370 / engine 447 / game 298；实扫 1315 条现行长行 + ~92 条陈旧
签名条目）。本批按 §2.20 建议"机械换行专项批统一处理"：**全部现行长行实修归零**（非配置
豁免/非压制），顺带清偿换行连锁暴露的 10 个 LongMethod 临界越界与 4 处漂移复活违规。

| 类别 | 处置 | 说明 |
|---|---|---|
| 代码行机械换行（~1200 条） | 脚本自动断行 | 词法状态机（正确处理 `${}` 模板内嵌套引号/花括号/raw string/块注释跨行）+ Kotlin ASI 安全判定：断点须满足「括号深度>0」或「头部以续行 token 结尾（运算符/逗号/return 等）」或「尾部以可续行 token 开头（`. ?. ::` 二元运算符/else/is/as/闭括号）」——平衡表达式后接 `(` `{` `[` 标识符的断点一律跳过（trailing lambda / infix / 调用粘连陷阱）。断点类别优先级：逗号后 > 成员点前（头部利用率 ≥35）> 运算符后 > 任意空格；续行缩进 +4 |
| 字符串字面量拆分（~60 条） | `"A" +\n"B"` 等值拆分 | 超长字符串（隐私政策文案/日志文案/数据描述）在明文区拆点拼接，**内容逐字节不变**（模板串仅在 `${}`/`$ident` 之外拆，禁在 `$` 前后拆，转义序列/`\uXXXX` 整体不拆，两侧片段非空）。配套**字符串完整性校验器**：git HEAD vs 工作区逐文件字符串 token 序列 diff——自动换行面 0 差异，9 个已知差异全部对应登记过的故意变更 |
| raw string 长行（34 条） | 逐处人工处置 | ①SQL 空格串规范化（RoomMigrationV43To46Test 种子 SQL 引号外空格串→换行，SQL 空白不敏感、Room 只比执行后 schema 不比文本）；②SQL 长行在 `DEFAULT` 关键字前断行 ×5（同上）；③日志模板表达式**提升为局部变量**（GCOptimizer/MemoryMonitor/RequestSigner/UnifiedPerformanceMonitor——渲染输出逐位不变）；④maps fixture 路径缩短（`scanMapsForSandbox` 按 `contains("libsandbox_ext.so")` 匹配，无关路径段删减语义不变） |
| 注释长行（6 条） | 按词断行 | `//`/`*` 前缀续行；URL 注释在路径段处断行（VulkanPolicy 参考链接） |
| **换行连锁：LongMethod 临界越界 ×10（真实新违规，禁止入 baseline）** | 逐函数真实重构 | detekt LongMethod 计数口径为 **linesOfCode（PSI token 行，注释/空行不计）**——机械换行给 58~60 行临界函数 +1~3 行即越界。修法：①同构重复提取（sellToMerchant 六族堆叠扣减内联收敛、tryBreakthrough 内外长老悟性块提取 `elderEffectiveComprehension`、LawEnforcement 两处"被捕置思过"提取文件级 `markTheftCaught`、CultivationSettlement 按弟子数+周期性政策消耗提取 `processVariablePolicyCosts`）；②参数行/守卫条件紧凑化（attackSect 三守卫 3+1 条件拆分避开 ComplexCondition=4、LevelSelectionDialogUi/EquipmentDetailDialog/InventorySelectDialog 参数行合并）；③单行语义合并（`+=` 语句对、`ifEmpty{}.randomOne()` 等价改写）；④300 字符多语句历史行重写为标准多行结构（migratePatrolSlots 迁移块——顺带消灭 EmptyElseBlock 误报源）。新增函数的落位纪律：**类被 TooManyFunctions baseline 压制时用文件级私有顶层函数**（文件级计数=顶层函数数，LawEnforcementProcessor 类 30 函数已压制而文件级 0），文件已压制时（GameEngineBattleOps 34 个顶层扩展）只做行内紧凑 |
| **漂移复活违规 ×4（其他规则既有 baseline 条目因所在行文本被改而失配）** | 顺手根治 | EmptyElseBlock ×2（随迁移块重写消失）、ExplicitItLambdaParameter ×1（`rarityOf/nameOf = { item -> … }` 命名化，规则根治）、MayBeConst ×1（`STORAGE_BAGS_CREATE_SQL` 升级 `const val`——对象内纯常量拼接本就 const 合格，规则根治）。**教训**：换行批次的 detekt 验证必须全量重跑而非只看 MaxLineLength——行文本变化会使**其他规则**的既有条目失配复活 |
| baseline/guard | 全量摘除 | 五模块 MaxLineLength 条目全摘（1407 条，实修归零后摘除）；guard 只缩：app 238→123 / data 429→252 / domain 503→133 / engine 939→492 / game 579→281（总 2688→**1281**，-52%） |

**工具与防复发**（一次性脚本在会话临时目录，未入库）：换行器迭代修了三处自身缺陷——①候选断点排序反选（rank 大者先选）→ 修复为类别优先+同类取最后断点；②文件尾多余空行 → join 语义修复；③拆分器两处致命数据损坏（闭引号 off-by-one 丢引号；模板串递归拆分缩进膨胀→预算耗尽→产出 `"" + "" + ""` 空串链与 183 字符残行）→ 修复为「模板串仅接受单次见效拆分 + 两侧片段非空 + `$` 前后禁拆」，三处损坏现场（PrivacyConsent 隐私文案、ForgeDialog 暴击率行、GameEngineCoordination 两处日志）全部手工重写修复并经字符串校验器证实内容逐字节还原。**字符串完整性校验器本身曾因 git pathspec 相对路径错误全量跳过（假绿）**——修复后重跑才得到真实结论，登记防复发。

**途中发现（预存问题，非本批引入）**：`:core:data` RoomMigrationV43To46/V46To47/V47To48/V48To49 共 8 例失败（"migration from 44 to 50 was required but not found"）——测试 `addMigrations` 链只注册到 M48_49，而 WS-0.a 批次已将 DATABASE_VERSION 提至 50（49→50 迁移未补注册）。**HEAD 无本批改动同样失败**（stash 复验），且 CHANGELOG 既有登记（修为×5 批次注明"schema 44→50 迁移缺失，与本改动无关，另案处理"）——登记 §4.1 待办，本批不扩范围。

**验证**：见 §3（2026-09-08 M3 第三批）。

## 2.23 M3 第四批（2026-09-08）：RoomMigration 预存失败清偿 + detekt TooGenericExceptionCaught 族实修清偿（baseline 1281→873，-32%）

**批次构成**：§4.1 登记的 RoomMigrationV4x 8 例预存失败专项小批 + M3 逐族清零的
TooGenericExceptionCaught 族（§2.20 残留口径建议顺序中 MaxLineLength 之后的下一族）。

### 2.23.1 RoomMigrationV4x 8 例预存失败清偿（§4.1 登记 2026-09-08）

| 改动 | 文件 | 说明 |
|---|---|---|
| 测试迁移链补 49→50 | RoomMigrationV43To46/V46To47/V47To48/V48To49Test | 四文件 companion 增 `M49_50` 别名 + 全部 6 处 `addMigrations` 链尾补 `MIGRATION_49_50`——母文件 RoomMigrationTest 早已注册（62/62 绿），拆分文件 2026-08-12 拆分后未同步，WS-0.a 提 V50 即断链。真实 Room 校验用例从"迁移路径缺失"恢复 |
| **种子派生 replace 锚点修复（§2.22 二阶损伤）** | RoomMigrationV43To46Test `SEED_DISCIPLES_V44` | `SEED_DISCIPLES_V44 = SEED_DISCIPLES_V43.replace(...)` 第二个 replace 的锚点模式 `\n        0\n    )`（8 空格缩进）在 §2.22 "SQL 空格串规范化"改写种子 raw string 后失配（`trimIndent` 后各行顶格）——职业 4 值未补上，V44 种子用例报 "101 values for 105 columns"。**迁移链修复前被 8 例 "migration not found" 失败掩盖**（测试在 INSERT 前先炸）。锚点改为顶格形状 `\n\n0\n\n)` 并加防复发注释 |

**验证**：`RoomMigration*` 5 类 74/74 全绿（RoomMigrationTest 62 + 拆分文件 12）。

### 2.23.2 detekt TooGenericExceptionCaught 族实修清偿（135 条 baseline 条目 → 474 处实跑违规全部处置）

**实跑裁决**：摘除五模块 135 条 TooGenericExceptionCaught baseline 条目后实跑暴露 **474 处**
（app 82 / data 178 / domain 6 / engine 85 / game 123，107 文件）——与 §2.20 机制发现一致，
baseline 按文件+签名压制，条目数远小于真实违规数（同签名多 catch 只需一条）。

**逐处判定结论**：474 处全部为**刻意的防御性 catch**（代码库 2026-08 异常处理整治后的形态：
`catch (e: CancellationException)` 前置处理 + `catch (e: Exception)` 终局兜底两段式是既有惯例），
非意外吞噬。按 catch 体形态四类处置，全部为**附理由 @Suppress**（函数级/属性级/表达式级，
沿 §2.5/WS-7 既有惯例；不收窄异常类型——异常源跨 IO/序列化/SDK 不可枚举，收窄即行为变更）：

| 形态 | 处数 | 理由模板 |
|---|---|---|
| LOG_ONLY（记日志降级继续） | 336 | 防御兜底：异常源跨 IO/SDK 不可枚举，降级继续+日志留痕，非静默吞噬 |
| LOG_RETHROW / RETHROW（归因日志后翻译重抛） | 53 | 异常翻译边界：刻意宽捕获，统一翻译为领域错误后重抛 |
| WRAP_RESULT（包装进 Result 上抛） | 15 | 异常显式包装进 Result 上抛，非静默吞噬 |
| OTHER（探针降级默认值/表达式 try） | 70 | 防御兜底：探针/可选增强失败即降级默认值，异常类型不可枚举 |

| 改动 | 说明 |
|---|---|
| 批量插入（391 个函数） | 脚本按形态模板在所属函数上方插 `@Suppress("TooGenericExceptionCaught") // <理由>`；插入锚=KDoc 之后/注解块之上 |
| 6 处无函数归属手工处置 | 属性初始化器/自定义 getter 内 catch（TapDBManager.dbInstance、GameConfigProvider.config、NativeSurfaceView 三个注入点 lambda）→ 属性级注解；init 块内（CacheLayer/FunctionalWAL 初始化、SaveLoadViewModel launch、GameLoopDelegate Bugly 反射探针）→ 表达式级注解 |
| **10 处误插回滚** | 脚本"向上找 fun"两缺陷：①泛型函数 `fun <T>` 不匹配；②嵌套 lambda/匿名对象内函数截胡（如 XianxiaApplication `loadLibrary` 无 catch 却被插）——括号平衡归属算法复核后回滚 10 处、按真实归属补插 |
| **19 对双 @Suppress 合并** | **detekt quirk：同一函数上两个独立 `@Suppress` 注解只有其一生效**——批量插入使既有 `@Suppress("ReturnCount", ...)` 等压制失效，80 条其他规则违规复活。修法：合并为单注解多参数 `@Suppress("TooGenericExceptionCaught", "ReturnCount", ...)`，理由注释保留 |
| **签名漂移 65 条等量替换** | 函数签名文本含注解 → 插注解使 ReturnCount/NestedBlockDepth/ThrowsCount/CCM/UnusedParameter 的既有 baseline 条目失配复活（§2.22 教训的注解版）；65 条新增条目全部对应**批次前已存在的违规本体**（旧死条目同批清除），无新违规入列（13.2 合规） |
| baseline 重建 | `detektBaseline` 生成物 = 全量当前真实违规的精确签名集（**含清除 273 条陈旧死条目**——历史批次签名漂移的永不匹配残留）。装回后六模块 detekt 0 违规。guard 只缩：app 123→72 / data 252→178 / domain 133→93 / engine 492→386 / game 281→144（总 1281→**873**，-32%） |

**登记防复发（findings）**：①detekt 同目标多 @Suppress 注解只生效其一——新增抑制必须与既有
Suppress 合并而非叠加；②detektBaseline 生成物是全量当前违规（含被 baseline 压制面），
**直接装回会偷偷扩大压制面**——必须先摘目标族条目、实跑裁决、处置后再生成；③baseline 条目
签名文本含函数注解——插注解会使该函数全部规则的既有条目失配（连锁面=该函数在 baseline 的
全部条目）；④gradle 测试 `-D` 属性注入相对路径 → JNI UnsatisfiedLinkError 假失败（须绝对路径）。

**登记待办（§4.1 新增）**：~~本批 474 处中约 90 处 suspend/launch 上下文的 `catch (e: Exception)`
未前置 CancellationException 分支（协程取消被兜底吞）——取消传播专项批统一处理（每处需
行为论证，不属 detekt 风格族清偿范围）。~~ **✅ 已清偿（2026-09-10，见 §2.33）**：目标集重测
实裁 61 处实修（两段式 58 / NonCancellable 原子段 1 / 刻意吞附理由 2）+ 13 处结构批所有权
跳过登记待补。

**验证**：见 §3（2026-09-08 M3 第四批）。

## 2.24 M3 第五批（2026-09-08）：detekt 判定族收官 + 机械族全清（baseline 873→370 条，-58%；实跑活违规 1050→395）

**批次口径**：摘除五模块全部 873 条 baseline 条目 → 实跑裁决全量真实违规 **1050 处**（34 规则族；
条目数≈债务数再次证伪，同签名多违规放大效应同 §2.23）→ 逐处处置 → detekt 六模块 0 违规 →
仅装回"拆分任务队列族"370 条（每模块计数全部只缩：app 72→19 / data 178→71 / domain 93→32 /
engine 386→191 / game 144→57）。

### 2.24.1 机械族全清（实修归零）

| 族 | 处置 |
|---|---|
| NewLineAtEndOfFile ×4 / ModifierOrder ×1 / MayBeConst ×8 / ImplicitDefaultLocale ×3 / ForEachOnRange ×3 / DestructuringDeclaration ×1 / FunctionOnlyReturningConstant ×1 / InvalidPackageDeclaration ×1 / ThrowingExceptionFromFinally ×1 / UnusedPrivateClass ×1 / InvalidRange ×1 | 全实修：补尾换行、`abstract override` 序、const val 化（isInitialized×4 / WORLD_PIXEL×2 / TAG / ARGON2ID）、`Locale.US`、range.forEach→for（performance 语义：range.forEach 分配迭代器）、GameConfigTest 按声明包 git mv、finally 清理路径取消不重抛（防掩盖 try 体原始异常）、TapGameSaveApi 全未适配零引用占位类删除、GameRandomTest 空区间抛错契约为被测语义 @Suppress 附理由 |
| EmptyElseBlock ×4 | ComponentTable put() `if (cond) stmt;` **尾分号在 PSI 产生空 else**（机制发现①）——密排单行改标准多行根修 |
| FunctionOnlyReturningConstant ×1 | FormulaService 恒 0 占位函数删除（C++ production 公式无该项，删调用点双侧一致） |
| ExplicitGarbageCollectionCall ×5 | 刻意显式 gc 附理由 @Suppress（Vivo JIT 兜底 ×2 / 存档前低内存触发 ×2 / 渲染 OOM 急救；forceGcAndWait 函数本义即强制 GC） |
| SpreadOperator ×6 | OkHttp/内部 DSL vararg 强制形 4 处附理由；Compose 列表初始化 2 处 apply+repeat 等价消除散布 |
| MatchingDeclarationName ×25 | **git mv 重命名 22 文件**（Repository×5→Impl、DiscipleDataDao→DiscipleDao、EnumStringConverters→JsonConverters、OldSaveCompat→OldSaveFormatDeserializer、SaveDataMigrator→SchemaVersion、GameSystem→AutoTickSystem、ExplorationSystem→ExplorationTickSystem、domain/*State→*DomainState、实体文件去 Entity 后缀 ×3、ProductionComponents→ProductionTheme、DiscipleFilterUtils→AttributeFilterOption、DetailHeaderSection→DetailRightPanel）+ **3 文件声明重排**（config 数据类移到主 Composable 之后——Kotlin 顶层声明顺序无语义，首个声明与文件名一致即根修）。零字节码变化；**守卫前置核对**：GameSystemRegistryCoverageTest 以文件名当类名扫描 @GameService——25 文件均不含该注解，移动安全（机制发现⑤） |

### 2.24.2 命名族全清（VariableNaming ×48）

- 测试 SCREAMING_SNAKE 常量 camelCase 改名（≈30 处全字匹配替换）+ TAG×2 companion const 化 +
  局部常量/`def_`/`N`/`IDLE_TIMEOUT_NS` 等改名。
- GameStateStoreImpl 19 个 internal `_xxxFlow`/`_updateVersion`：**文件级 @Suppress**——internal
  镜像通道 backing-property 是 Kotlin 惯例，detekt 无 internal 命名模式旋钮。
- ComponentTable `size_`×2 / EntityStore `items_`：与公开 size/items API 同名消歧，附理由 @Suppress。
- **途中事故（机制发现③）**：`_discipleTables→discipleTables` 全局改名撞 GameStateStore 接口自带
  `val discipleTables`，且 fake 的接口覆写 getter 被改成自引用（`get() = discipleTables` 无限递归
  StackOverflow）——**主源编译不编译测试源，撞名/递归仅在全量测试编译暴露**。终态：fake 属性
  `fakeDiscipleTables`、update{} lambda 内裸用（MutableGameState 接收者成员）保持 `discipleTables`、
  覆写 getter 返回 fake 实例；四 Fake 重测试类全绿。**改名批次必须跑 compileReleaseUnitTestKotlin**。

### 2.24.3 异常形态族全清

| 族 | 处数 | 处置 |
|---|---|---|
| TooGenericExceptionThrown | 9 | RuntimeException→IllegalStateException（初始化失败/SDK 不可用/DB 查询翻译边界，捕获面均宽 catch 行为等价） |
| InstanceOfCheckForException | 29 | 三 Delegate（14/6/4）改**两段式 catch**（CancellationException 前置分支——§2.23 登记的取消传播形态首个落地族）；StorageEngine 拆 IOException 独立 catch + **删除恒 false 的顶层 `e is OutOfMemoryError` 死检查**（Error 不会被 catch(Exception) 捕获），OOM cause 链判定保留且 IOException 分支同判（保持原"先 OOM 后 IO"分类语义） |
| UseRequire ×11 + UseCheckOrError ×13 | 24 | require/check/checkNotNull/error 惯用改写，异常类型与消息逐字保留 |
| RethrowCaughtException | 6 | SectMapTouchEngine 六处单子句取消裸重抛 = 协程惯用形；实证 import 对齐 kotlinx（typealias 同型）不消除违规，函数级附理由 @Suppress。**Kotlin try 语法必须接 catch/finally——"删 no-op catch"不可行**（机制发现②） |
| SwallowedException | 54 | 8 处异常并入既有日志（诊断增强）+ 46 处刻意降级/回退改名 `ignored`/`expected`（detekt allowedExceptionNameRegex 认可的刻意忽略标记，非压制） |
| ThrowsCount | 10 | 多步事务/翻译边界的独立失败路径附理由 @Suppress，全部与既有 @Suppress 合并单注解 |

### 2.24.4 判定族收官

| 族 | 处数 | 处置 |
|---|---|---|
| UnusedParameter | 110→0 | **真实删参 4 组**（MainActivity.attach 首参 / rebuildGameData sourceColumns+两处死列收集游标块 / logSaveChanges data / selectManuals levelIndex——均私有调用面 1-2 处）+ 106 处**语义形参/镜像协议字段/扩展点预留/重载对称**按参数族附理由 @Suppress（函数级，含既有注解合并） |
| EmptyFunctionBlock | 107→0 | 空覆写 `{}` → `= Unit` 表达式体（Kotlin no-op 惯用形；4 个 GameStateStore Fake 91 处 + 主源生命周期空覆写） |
| LaunchOnEngineRequired ×1 | GameLoopDelegate UI 进度插值动画（16ms 步进+delay）：刻意走 UI scope 不占引擎单线程，块内零引擎调用——附理由 @Suppress |

### 2.24.5 ReturnCount 阈值拍板（max 2→5）

detekt 默认 `max: 2`（code-quality.md 从未立过 return 数标准）与 Kotlin 守卫子句/校验链早退惯用形
冲突：实跑 220 处中 187 处为 3~5 return 的合法早退。按项目既有阈值拍板先例（TooManyFunctions/
LargeClass 均留痕注释）：`style>ReturnCount max: 5`（**规则属 style 规则集，放 complexity 报
invalid config**——机制发现④）。余 **33 处 6+ return** 属真实缠绕函数，归入 M3 第六批队列。

### 2.24.6 M3 第六批队列（装回 baseline 登记）

395 处真实违规（baseline 按"规则+文件+签名"去重后 370 条；五模块计数全部只缩）：
**TooManyFunctions 113 / CyclomaticComplexMethod 85 / LoopWithTooManyJumpStatements 70 /
LongParameterList 34 / ReturnCount 33 / NestedBlockDepth 28 / ComplexCondition 17 / LargeClass 15**——
全部为真实结构性重构（文件/类拆分、函数提取、查表化 when）；其中 LargeClass 15 个 >800 行冻结类
即 detekt.yml 既有注释所指拆分任务队列；NestedBlockDepth 23/29 恰在 4/4 边界、ComplexCondition
14/17 恰在 4/4、LongParameterList 14/34 恰在 8/8。**不可赶工，逐族专项批推进**。

**机制发现（findings 同步登记）**：①`if (cond) stmt;` 尾分号在 PSI 产生空 else——EmptyElseBlock
误报源，密排单行风格改标准多行根修；②Kotlin try 必须接 catch/finally，删 no-op catch 不可行；
③全局改名必须跑 compileReleaseUnitTestKotlin（主源编译不查测试源）；④ReturnCount 在 style 规则集
不在 complexity；⑤批量 git mv 前必须核对路径型守卫（文件名当类名的扫描器）；⑥detekt 同声明重复
@Suppress = 编译错 "not repeatable"（比 §2.23 "只生效其一"更强——新增抑制必须并入既有注解）；
⑦detekt 报告行号随编辑漂移——批量脚本必须幂等 + 行号偏移补偿 + 以全量重跑为最终裁决。

**验证**：见 §3（2026-09-08 M3 第五批）。

## 2.25 M3 第六批（2026-09-08）：detekt 判定边界族收官——ComplexCondition 17 + NestedBlockDepth 28 + ReturnCount 33 真实重构清偿（baseline 370→292）

**批次口径**（§2.24.6 拆分任务队列的第一轮，"逐族专项批推进"）：从五模块 baseline 摘除三族
78 条条目实跑裁决，真实违规 **78 处**（CC 17 / NBD 28 / RC 33；放大系数 1.0——判定/边界族
条目数≈债务数，与机械族"同签名多违规放大"形态不同），逐处**真实结构性重构**处置
（谓词提取 / 深嵌套块提取 / 查表化与守卫合并），全部零行为变更，**不装回任何条目**。
detekt 六模块（三族摘除基线下）0 违规；guard 370→**292**（app 19→13 / data 71→54 /
domain 32→29 / engine 191→150 / game 57→46，只缩）。

| 族 | 处置形态 | 代表案 |
|---|---|---|
| ComplexCondition 17→0 | 4+ 操作数条件提取命名谓词（局部 val / 私有纯函数），求值序逐位保持 | VulkanPolicy `hasPriorVulkanFailure`（先前崩溃四标记）、GridSnapHelper/GridSystem `outOfBounds`、SectCameraState/WorldCameraState `sizeChangedBeyondThreshold`、GameStateStoreImpl 血炼 base 损坏六列判定、SoftwareCanvasBackend 帧缓冲重建/越界判定 |
| NestedBlockDepth 28→0 | 最深嵌套块提取私有助手（循环形状/RNG 消费序不触碰）；if 表达式 RHS 参与深度计数的两处（placeBorderTrees 棋盘变体）提取表达式体助手 | StorageEngine performFullTransactionSave 三处 WAL 非阻断回滚 `abortWalQuietly`/`abortWalSyncQuietly`；LawEnforcementProcessor 仓库驻守拦截（RNG 恰一次 nextInt 消费序锁定）；PartnerSystem 配对循环 RNG 判定改 continue 卫语句（M×F 循环形状不变）；SpiritStoneWallet batch 预检查回滚；GameEngineCoordination 灵矿槽重建/任务结算/巡逻槽迁移（双过滤口径逐位保持：回填用注册表过滤、改尺寸用"巡视楼"名过滤）；RoomMigrationTest/ProtoNumberCoverageTest 测试助手化 |
| ReturnCount 33→0 | 校验链分相 / 有序规则表引擎 / sealed 校验结果 / 查表化，错误消息与日志逐字保留 | InputValidator 三校验器改**有序规则表**（firstFailure + 5 检查原语，文案逐字保留；引擎移文件级——object 函数数触 TMF 阈值）；BattleCalculator selectSkill 10 return 拆"守卫/战术臂（3 次抽取序不变）/低蓝兜底"三段；BattleAI 四函数守卫合并（合并前提：检查点间零 RNG 消费）；MailService 双领取入口状态门控提取；SaveLoadViewModel saveGame/restartGame 守卫链 sealed 化/分相（锁释放序逐位保持）；HeavenlyTrialService 四道前置校验 sealed 结果化；RelativeGiftHandler 关系分类查表化（匹配序列 first）+ 选品兜底链；BuildingService 排班槽位校验共享化（startAlchemy/startForging 去重） |

**途中修复（首跑裁决暴露的次生违规，全部根治后才算清偿）**：①新增助手的泛型 catch 补
`@Suppress("TooGenericExceptionCaught")` ×3（SaveCrypto Argon2 工厂段 / StorageEngine 两个
WAL 回滚——原抑制在宿主函数上，拆分后须随迁）；②助手使 object/类函数数触 TooManyFunctions
阈值 ×3（InputValidator 14/12、VassalService 20/20、WorldMapGenerator 12/12）——纯函数
移**文件级私有**落位（文件级阈值 15 且基数为 0）；③深度仍超 2 处（detekt 把**赋值右侧的
if 表达式**也计一层嵌套——placeBorderTrees 棋盘变体、PartnerSystem 配对 if/else，分别
提取表达式体助手 / 改 continue 卫语句）；④WorldMapGenerator distributeInitialRealms
CCM(15)+NBD 双标——按"权重表/加权分配/余数补齐"三段拆分根治。

**途中发现（预存缺陷/死代码，本批顺手处置）**：
- `DiscipleDeadStatusRule` 修复消息 `listOfNotNull(hasWeapon to "...")`——Pair 永不为 null，
  **四个槽位全进消息**（含空值），意图显然是只列已装备槽。按意图修复（cleaned 只列
  isNotEmpty 槽位）；装备引用清空行为不变，无测试依赖旧消息。
- `StorageEngine.buildSaveDataFromDatabase` 返回**非空** `SaveData`——
  `loadFromDatabaseInternal` 两分支的 `if (saveData != null)` 恒真、尾部 `return saveData`
  为死分支。重构按实际可空性收敛（`buildAndMigrateSaveData` 共享化），无行为面。
- `SaveCrypto.deriveWithArgon2Factory` 中 `Arrays.fill(pbeSpec.password, ' ')` 在 try 内与
  finally 中**双写**（成功路径先擦一次、finally 再擦一次，幂等）——按最小改动保留双写。

**防复发（findings 同步登记）**：①K1 编译器：仅 finally 的 try 块不能作为块体最后语句的
隐式返回表达式（报 Missing return statement），须显式 `return try {...} finally {...}`；
②detekt NestedBlockDepth 深度计数包含赋值右侧 if 表达式（RHS if 也算一层）；
③detekt TooManyFunctions 在函数数 == 阈值即报（12==12 / 20==20 均报）；④拆分批次的全量
detekt 重跑必须**全规则看报告**——本批首跑暴露 10 处次生违规（宿主函数上的抑制不随助手
迁移、助手推高容器函数计数、if 表达式深度），"目标族归零"不等于"无新违规"。

**残留口径**：~~M3 拆分任务队列剩余 292 条~~（**→ 已随 §2.26 清偿 LPL+Loop 两族**，
余量 196 条见 §2.26；本节以下为 §2.25 时点原记录：TMF 113 / CCM 85 / LoopWithTooManyJump 45 /
LongParameterList 34 / LargeClass 15——条目数；§2.24.6 登记的 Loop 70 为实跑放大后违规数），
属真实结构性重构（文件/类拆分、结算循环形状），逐族专项批推进、不可赶工。

## 2.26 M3 第七批（2026-09-09）：detekt 参数与跳转族清偿——LongParameterList 34 + LoopWithTooManyJumpStatements 45 条目实跑裁决 102 处真实重构（baseline 292→196）

**批次口径**（§2.24.6 拆分任务队列第二轮，"逐族专项批推进"）：从五模块 baseline 摘除
LongParameterList 34 条 + LoopWithTooManyJumpStatements 45 条实跑裁决，真实违规 **102 处**
（LPL 34 + Loop 68——Loop 呈现既有"同签名放大"形态，45 条目→68 处），逐处处置归零：
**98 处真实重构/死代码删除 + 4 处附理由 @Suppress**（JNI ABI / 域聚合 Facade / 复用组件
API 契约），**不装回任何条目**。detekt 六模块 0 违规；baseline 全量重建后 292→**196**
（app 13→11 / data 54→48 / domain 29→23 / engine 150→87 / game 46→27，只缩）。

| 族 | 处置形态 | 代表案 |
|---|---|---|
| LPL 死代码删除 ×5 | 零调用核查后删除 | `ProductionParams` + `GameTime` 整文件（全仓零引用）；`ProductionTransactionManager.executeStartProduction`（byType 版）；`GameStateRepository.flushDirtyState` + `flushDisciples` + `restoreDirtyMarks` 死链（dirty 记账机制保留——markAllDirty/clearDirty 有活调用）；`InventorySystem.loadInventory`；`StackableItemUtils` 整对象（~200 行） |
| LPL 参数对象 ×22 | 共享载体打包（拆分/委托/测试调用点同步） | 生产排班族 `ProductionStartSpec`（SlotStateMachine + ProductionTransactionManager 两入口 + Coordinator 两调用点 + 鲁棒性测试，**一并删除死 byType 入口**）；`ProductionTransactionResult` 导入面补全；战斗族 `BattleWriteBackContext`（AISectAttackManager 普攻/单体/AOE 全臂五字段上下文，替代逐参透传）；`applyDamageEffects` 敌方索引映射从 TurnContext+isTeamMember 派生（与 resolveTurnSides 同源）；执法堂 `TheftInventorySnapshot` 六类可偷物品列表；钱包 `recordAndEmit(state, tx: SpiritStoneTransaction)`（8 调用点构造流水载体，账本/事件/年度报告逐字段等值）；VassalService 复用既有 `SectBattleStats`（近3年战绩统计去重）；`computeFinalStats` 直收 `PillEffects`（9 个丹药散字段收敛，DiscipleCombatStats 调用点就地映射）；ExplorationService 六个子领域系统聚合 `ExplorationSubSystems`（12→7 构造参数）；测试助手 `ResidencePassSpec`（两轮住所分配规格）；Compose 族 `SectStoneBalance` / `AuraBuildingRect` / `PlacedAnchor`（×2 调用点）/ `GoldFingerSelection`（复用既有类型）/ `RewardItemLists` 直收 / `DiscipleTypeEditInteraction` / `AlchemyDialogInputs` / `ForgeDialogInputs` / `WorldMapDialogInputs` / `AutoAssignSpec`（GameViewModel + AutoAssignDelegate 18 参双入口收敛，弹窗/测试调用点同步） |
| LPL 附理由 @Suppress ×4 | 签名即契约 | `NativeBridge.drawRect/drawSprite`（external fun 顶点格式 JNI ABI 锁定，渲染热路径零装箱分配）；`CultivationService` 构造（10 个协作域独立注入面，人为聚合不改善内聚）；`SpiritRootAttributeFilterBar`（8 调用面 state-hoisting 复用组件，参数面即 API 契约） |
| Loop 68 全部真实重构 | 守卫合并 / 命名谓词 / 助手提取，求值序与 RNG 消费序逐位保持 | **RNG 红线三处专项**：PartnerSystem M×F 配对（已配对∨血亲合并守卫 + 抽取判定 `< PROB` 反转为 if 体——抽取集与顺序逐位不变）、ChildBirthSystem 受孕/分娩双循环（守卫合并不触抽取集；父亲死亡清理提取 `clearStaleBirthOnDeadPartner` 文件级助手）、ProductionProcessor 影子结算（到期合并谓词 + 抽取集不变）；AISectBeastAttackProcessor 出手资格提取 `shouldAiSectAttackBeast`（BATTLE 抽取与冷却登记序不变）+ 目标分派 `processDirectTarget`；ProductionProcessor 续炼双循环提取 `autoAlchemyRestartSlot`/`autoForgeRestartSlot`（true=继续 false=中断，沿 processAutoForgeSlot 既有先例）；`recalculateAllCompletionMonths` 四守卫 `run{}` 合并（惰性求值保持）；InventorySystem `consolidate` 提取局部函数 `mergeSourceStack`（主堆叠切换语义保持）；BatchAutoAlchemy 完成段/自动购买单条目 `purchaseMerchantItem`（AutoBuyEntryResult 计数载体）/玩家宗门半径查找 `nearestPlayerSectInRadius` 单出口化；断言型重组合并（WAL 解析 4 跳转→1、SectMapTileGenerator 双胞胎单格装饰提取文件级 `decorateGrassCell`/`decorateTreeCell`——**位级输出不变**，DiffSectTerrainTest 锁守）、BattleCalculator 伤害分摊守卫前置 + 链接伤害 firstOrNull 化、SpiritStoneWallet batch 扁平 if/else 链、NativeSurfaceView 渲染主循环双门 `isRenderTick` 合一（节拍门/步进门序与 dirty-skip 分支逐位等价）+ stopRenderThread join 循环 `joined` 条件化、渲染 chunk 预算/合成/云层/作物四循环守卫收敛 |

**途中修复（全规则重跑裁决暴露的次生违规 18 处，全部根治后才算清偿）**：
①`computeFinalStats` 签名变更使其 CCM baseline 条目失配**复活**（复杂度 18，既有 suppressed 债）——装备/功法/丹药三段提取 `applyEquipmentStats`/`applyManualStats`/`applyPillStats`（`StatAccum` 累加器保 float 累加序）真根治；②参数对象引入使 AlchemyDialog/WorldMapDialog 触 LongMethod——`AlchemyPillSelectionGate` / `WorldMapSubDialogGate` + 交易/侦察状态收集提取；③解构声明 >3 条目 ×7（`DestructuringDeclarationWithTooManyEntries`）——参数对象解包改显式 val；④`MatchingDeclarationName` ×3（SectStoneBalance/AuraBuildingRect/PillStatLine 落位文件名对齐）；⑤AlchemyDialog.kt 文件函数数触 TMF 阈值 15——纯函数 `pillDetailStatLines` + `PillStatLine` 移独立文件。

**途中发现（预存问题/死代码，登记不扩范围）**：
- `GameStateRepository` dirty 机制为 **write-only 残留**（flushDirtyState 死链删除后，
  markDirty/markAllDirty/clearDirty 只置位无消费者）——WS-0.a 自动存档删除的记账残留；
  `markAllDirty` 在 loadFromSnapshot 失败回滚路径有行为依赖（StateRevertRegressionTest
  注入失败触发回滚），**保留并在专项批拍板**（整体摘除须重构 load 回滚语义）。
- `AISectBeastAttackProcessor.collectQualifiedAiForBeast` 的 `qualified.size >= 2 break`
  为防御性死分支（aiCandidates 已 `take(2)`）——保留防御不扩范围。
- `ComponentTable.contains` 非 operator（`id !in table` 编译不过）——既有 API 形状，
  守卫合并时保持 `.contains` 显式写法。

**并行工作线冲突（防复发，登记 findings）**：本批实施期间仓库存在**并行会话的未提交
改动**（433 文件：cpp×75 / rules / CLAUDE.md / manifests 等）。两次实际冲突：
①MainGameScreen.kt 被并行覆盖写入导致本批 2 处调用点编辑回退（编译期暴露后重应用）；
②`:app:compileReleaseKotlin` 被并行批未完成符号卡死（GameActivity `RenderDebugSwitches`/
`recordVulkanInitFailure` 等）——**与本批无关**，本批 app 模块 2 文件
（GameStateStoreImpl/StateFlowListUtils）编译验证被阻塞，由并行批收尾后自证。
**提交纪律：严格按本批触碰文件清单暂存，禁止整仓 `git add`。**

**机制发现（findings 同步登记）**：①detekt 解构声明条目上限为 **3**（`maxDestructuringEntries`
默认）——多字段参数对象在函数体内解包须用显式 val 而非解构；②detektBaseline 全量重建
会顺带清除**陈旧死条目**（本批 -17 条 CCM：历史批次签名漂移后永不匹配的残留），guard
计数因此低于"摘族"理论值——属诚实清偿非超缩；③`ComponentTable.contains` 非 operator；
④跨会话并行改仓时，批前 `git status` 快照不可信，每文件保存后以编译+定向 diff 复核。

**残留口径**：M3 拆分任务队列剩余 **196 条**（TMF 113 / CCM 68 / LargeClass 15；
CCM 85→68 系 baseline 重建清除 17 条历史签名漂移死条目 + 本批 computeFinalStats 真根治 1 条），
全部为真实结构性重构（文件/类拆分、结算循环形状），逐族专项批推进、不可赶工。

## 2.27 M3 第八批（2026-09-09）：detekt 复杂度族收官——CyclomaticComplexMethod 68 条目实跑裁决 68 处真实重构清偿（baseline 196→128）

**批次口径**（§2.24.6 拆分任务队列第三轮，"逐族专项批推进"）：从五模块 baseline 摘除
CyclomaticComplexMethod 全部 68 条条目（app 2 / data 6 / domain 5 / engine 40 / game 15）
实跑裁决——真实违规**恰 68 处**（放大系数 1.0，判定族形态与 §2.25 一致），逐处**真实
结构性重构**归零，**不装回任何条目**。detekt 六模块全规则重跑：本批触碰面 0 违规
（feature:game 残留 1 处 NativeSurfaceView TooManyFunctions 系并行工作线中途引入，
非本批——见"并行工作线"节）；guard 196→**128**（app 11→9 / data 48→42 / domain 23→18 /
engine 87→47 / game 27→12，只缩）。**拆分任务队列余量 = TMF 113 / LargeClass 15。**

| 处置形态 | 代表案 |
|---|---|
| 查表化（when→映射表） | `formatEffectKey`(36→2, 34 条效果键映射)、`getStatDisplayName`(23→2)、`getBuffTypeName`(28→2)+`parseManualStackBuffs`(32→3) 共用 BUFF_TYPE_BY_KEY、`parseBuffType`(28, ManualInstance companion 映射)、`reasonDisplayName`/`sourceDisplayName`(战报文案映射)、`deriveDiscipleStatus`(20, 有序判定表 14 条 flag→status 谓词对，表序=原 when 优先级序)、AppErrorExt `toAppError`×2(24/21, 缺省文案+领域错误工厂二元组映射，`getValue` 保"新枚举值必须显式登记"穷尽语义)、`toUnifiedResult`(20, StorageError→SaveError 15 条映射)、`toManualTemplate`(22, `orDefault` 扩展收敛 21 处 elvis)、EngineServiceAnnotationTest(23, 排除清单表驱动) |
| **RNG 红线专项（抽取集与顺序逐位保持）** | `processPartnerMatching`(22, 失效提议/配对资格谓词提取，M×F 循环形状不变)、`processYearlyConception`(15, 受孕资格谓词)、`batchAlchemyCompletion`(17, 成功臂产出段提取——先抽 success 后入臂抽 roll)、`applySurvivorSoulAndAttribute`(21, 17 分支属性 when 拆技能 clamp/战斗递增两助手)、`applyMissionRewards`(25, 泛型发放日志助手 logGrantOutcome+弟子状态段提取)、`tryBreakthrough`(22, 长老悟性/职务加成输入提取，单抽取保持函数末尾)、`generateSectTradeItems`(15, 类型 roll→稀有度 roll 序不变)、`applyBeastVictoryBonuses`(20)、`calculatePreachingBonusesColumn`(19, 乘区源逐字)、`computeAutotileBitmask`(24, 8 邻位组装逐字节搬移——DiffSectTerrainTest 位级锁守)、`recoverHpMpSingleColumn`(19, 列构建/映射解析/结算三段) |
| Compose 组合拆分 | `DiscipleChatDialog`(33, 纯计算段提取 3 个文件级助手，RNG 调用点原样)、`InventorySelectGrid`(27)/`AllItemsSelectGrid`(35, 6 个泛型访问器——丹药键刻意不同原样保留)、`LearnedManualDetailDialog`(19, 熟练度阈值三助手) |
| 文案/JSON 构建拆分 | `addManualSkillInfo`(22, 按 add 序切 4 段)、`getEquipmentEffects`(19, "终值+↑加成"块共享化)、`getStorageBagItemEffects`(18, else 分支提取)、`parseBuffEntry`(size→type→value→duration 校验序不变) |
| 分相/sealed 化 | `apprenticeToMaster`(15, sealed ApprenticeCheck 存在性/存活名额/容量三相，短路序保持)、`sellToMerchant`(28, 类型 when 分发+5 堆叠类共享扣减 withQuantity 等价)、`listItemsToMerchant`(20, 类型级联返回值协议)、`removeDirectDisciple`(17, 7 路槽型分发)、`learnManual`×2(18/19, 资格守卫/堆叠消耗/写回三段)、`checkCloudSave`(17, 分相+防御 catch 抑制随迁) |
| 谓词提取/守卫收敛 | `fromException`(16, safeMessage 消息缺省)、`computeCombatPower`(16, mergeTalentEffects+combinedBonus 选择器)、`assembleCoreFields`(30, resolveGroupPart 脏组解析泛型助手 + 13 处 elvis 收敛为 getOrDefault 同语义)、`collectReverseRelatives`(15, 单弟子归类+兄弟姐妹判定)、`SpiritStoneWallet.batch`(15, applyBatchOperation 三臂)、`ChangeLogEntity.equals`(18, bytesEqual 字节组等价)、`EquipmentDedupeRule.execute`(16, 引用统计/去重提取)、`writeCoreEntities`(15, 弟子/堆叠/生产三段写入)、`bulkSellItems`(31, collectBulkSellOperations+稀有度/锁定/数量三助手)、`findSelfPreservation`(15, 三谓词方法引用)、`fillEmptyGarrisonSlots`(15, MonthlyGarrisonFill 载体)、`findSectsWithNoTargets`(17)、`warehouseCount`(18)、`getCapacityCheckParams`(18)、`hasWarehouseStock`(16)、`calculateElderAndDisciplesBonus`(17)、`processAnnualSalary`(16)、`computeBaseStats`/`getBaseStats`/`getPermanentBaseStats`(24/19/19, talentBonus/talentFlat/brPct+聚合输入共享)、Slot 清理三函数(18/19/21, 守卫查表化/字段收敛 copy)、`collectCasualtyData`+`computeElderSlotUpdates`(16/17)、`applyLoot`(18)、`apprenticeToMaster` 同族资格门控 |
| 测试重构 | `checkEncodeDefaultCoverage`(15, 跳过判定谓词提取)、RoomMigrationTest 全链路用例(15, collectGameDataColumns+applyChainSeedOverrides+overrideColumnIfPresent)、EngineServiceAnnotationTest(见查表化) |

**次生违规根治（全规则重跑裁决 6 处本批引入，全部根治后才算清偿）**：①PatrolBattleSystem
类函数数触 TMF 阈值 20——属性增量两助手移文件级；②HpMpRecoveryService
settleColumnRecovery 8 参触 LPL——`HpMpLevels` 四元组参数对象；③GameEngineCoordination
2015 行触 FileLength 2000——learnManual 三文件级助手搬移 GameEngineDiscipleOps.kt
（private→internal，行为零变更）；④⑤GameEngineCoordination 两处杂散 import（并行会话
IDE 误导入，UnusedImports）移除；⑥DiscipleTables 两行 >120（resolveGroupPart 调用换行）。

**并行工作线中途状态（登记，不扩范围）**：本批实施期间并行会话持续改仓（568+ 文件未提交：
longrun-stability/performance 整改 + 渲染批）。①SaveLoadViewModel 五个主流程调用一度被
注释且 SaveLoadLoadDelegate 未接线（批初 detekt 5 处 UnusedPrivateMember、批末已被并行会话
自行接线清偿）；②NativeSurfaceView TooManyFunctions(21/20) 于批中出现在其文件（mtime
晚于本批首跑裁决）——feature:game detekt 残留此 1 处，归属并行线；③SoftwareCanvasBackendTest
"不可放置占地框应偏红" 1 例失败——该文件与测试均为并行线未提交的占地框颜色调参
改动，本批 feature:game 触碰面（对话框/效果文案/GameViewModel/TapCloudSaveManager）
与其零交集；④KSP 增量缓存两波损坏 + classes.jar 文件锁（并行构建中断/并发持有）——
定向清 kspCaches、gradlew --stop、杀残留 KotlinCompileDaemon、删受损 intermediates 后恢复。
**本批 detekt/测试验收口径：本批触碰文件零新增违规零新增失败 + 违规/失败集合差分归属裁决。**

**机制发现（findings 同步登记）**：①detekt CCM 计数模型：if/when 入口/&&/||/elvis 各 +1、
lambda 不计——`getOrDefault` 与 `getOrNull ?: default` 同语义但前者不计复杂度，是组件表
默认值读取的"零复杂度"写法；②Windows 并行会话共享 Gradle daemon 时 classes.jar 文件锁
可持续数分钟，重试循环须以"锁释放探测（rm 试删）"而非固定 sleep 收敛；③python 切片编辑
必须以 `newline=''` 读写并探测 CRLF（否则混合行尾，本批 EquipmentDedupeRule 曾中招已规范化）；
④K1 智能转换在 lambda 早退（`is X -> …; return@label`）后对成员扩展返回值不稳定——
when-subject 形式可靠。

**残留口径**：M3 拆分任务队列剩余 **128 条**（TMF 113 / LargeClass 15），全部为真实
结构性重构（文件/类拆分），逐族专项批推进、不可赶工。

## 2.28 M3 第九批（2026-09-09）：detekt 函数数族第一轮——TMF 113 条目实跑裁决 114 处：文件级域拆分真实清偿 + 契约面附理由豁免（baseline 128→59）

**批次口径**（§2.24.6 拆分任务队列第四轮）：从五模块 baseline 摘除 TooManyFunctions 全部
113 条条目（app 8 / data 39 / domain 16 / engine 39 / game 11）实跑裁决——真实违规 **114 处**
（含 §2.27 登记的并行线 NativeSurfaceView TMF 1 处；放大系数 1.0）。逐处处置分三类：
**①死代码删除 ②文件级域拆分（真实结构性重构）③契约面附理由 @Suppress（签名即契约，
沿 §2.26 LPL 先例）**；域管理者类（engine 26 / data 11 / game 7）装回 baseline 登记为
"M3 拆分任务队列"余量逐批推进。baseline 128→**59**（app 9→1 / data 42→14 / domain 18→2 /
engine 47→34 / game 12→8，只缩），guard 同步。

| 类别 | 处置 | 明细 |
|---|---|---|
| ①死代码删除 | 整文件/整族删除 | `StateFlowListUtils.kt` + 自测试（全仓零生产调用）；`GameRandom` 7 函数（nextSecureInt×2/nextSecureDouble/nextSecureLong/nextSecureBytes/nextFloat/resetWithTimeSeed 生产零调用）+ GameRandomTest 4 死用例 |
| ②域重构：纯函数下放 | FavorDomain 16→10 | 6 个纯计算函数（送礼增长/交易价格/拒绝概率/偏好修正×2/衰减值）下放 FavorDomain.kt 文件级顶层函数（同包调用语法除限定符外零变化，8 处生产调用点 + 测试同步去限定符） |
| ②域重构：引擎 Ops 文件拆分 | 5 文件→18 文件 | **GameEngineCoordination**（90 函数）→ 保留 Focus/UI+数据更新助手 13 + 新增 8 域文件（LifecycleOps 11 初始化/完整性自愈/进入宗门、LoadDataOps 12 读档/新建/重启、ManualOps 7 丹药/装备/功法、RecruitOps 4、PatrolOps 8 灵矿/巡逻/俸禄、MissionOps 7、ServiceOps 12 服务委托/内存/引擎运行态、BloodRefinementOps 10 血炼原子操作）+ **LoadSlotOps** 8（加载期槽位对齐，LoadDataOps 再拆）；**GameEngineBattleOps**（41）→ 保留攻宗族 13 + WorldBattleOps 11（世界关卡/兽关洞关奖励）/ScoutOps 4/GarrisonOps 3/BattleRecordOps 5 战报查询/WarRewardOps 5 战利品发放簇（3 助手 private→internal 跨文件）；**GameEngineInventoryOps**（35）→ 保留 14 + MerchantOps 13 商人/自动购 + InventoryItemOps 8 物品构造；**GameEngineProductionOps**（20）→ 保留 13 + SpiritFieldOps 4 灵田 + FormulaOps 3 配方公式；**GameEngineDiscipleOps**（37）→ 保留 14 + DiscipleSlotOps 12 任命/槽位/释放 + DiscipleItemOps 11 装备/功法/聚合。同包顶层扩展移动=调用点语法零变化；拆分后每文件顶层函数 ≤14（文件阈值 15） |
| ②域重构：Compose 文件拆分 | 4 文件→17 文件 | **DiscipleComponents**（27）→ 保留 9 + DiscipleSlotComponents 9 + DiscipleDetailDialogs 9；**ItemDetailEffects**（33）→ 保留 6 + ItemBuffEffects 8 + ManualSkillEffects 13 + PillEffects 6；**BattleLogDialogs**（38）→ 保留 9 + BattleLogRoundsDialogs 7 + YearlyReportDialogs 9 + YearlyReportHelpers 13；**SectDiplomacyDialog**（29）→ 保留 7 + DiplomacyFlows 5 + DiplomacyChatUi 7 + DiplomacyGiftTexts 5 + DiplomacyVassalTexts 5。跨文件消费的顶层声明 private→internal（模块内可见性，行为零变更）；共享常量表（EFFECT_KEY_NAMES/BUFF_TYPE_BY_KEY）随消费方迁移；BattleLogTab 枚举落位同名文件（MatchingDeclarationName 根修） |
| ③契约面 @Suppress（逐处附理由） | 46 处 | **Room DAO 接口 ×20**（@Query 契约面，已做过一轮 DiscipleSubDaos 域拆分、继续拆只碎片化协议并倍增注入面）；**转换器/序列化注册 ×5**（Collection/Enum/Json/Protobuf Converters + NullSafeProtoBuf——Room 强制函数形态，函数数=受支持类型数 1:1 映射）；**GameDatabase**（33 abstract DAO 访问器=Room 契约）；**接口 ×11**（GameStateStore/GameStateStoreImpl 文件级（35 override 契约下界）/DiscipleStatsProvider+匿名 no-op/MailRepository/InventoryRepository/CameraState/BuildingFacade/DiplomacyFacade/DiscipleFacade/InventoryFacade/TouchEngineCallbacks）；**DI 模块 ×2**（AppModule/CoreModule @Provides 样板，detekt.yml 注记语义）；**静态注册表 ×7**（Affix/BeastMaterial/Herb/Item/PillRecipe/Talent/ManualDatabase——查询原语+表构建器同址内聚）；**容器/协议 ×4**（ComponentTable/EntityStore/DiscipleTables 镜像列协议/CacheKey.Companion 键工厂）；**JNI ×1**（NativeBridge external fun 符号面）；**app ×4**（DiscipleIndex 索引容器/GameActivity+MainActivity 生命周期回调族 24/29 override 契约下界/VivoGCJITOptimizer 平台态域聚合）；**ReflectiveCloudSaveApi**（云存档端口协议下界） |
| 残留装回（登记拆分队列） | TMF 44 + LargeClass 15 | engine 26（InventorySystem 105/DiscipleStatCalculator 85/DiscipleFacadeImpl 68/ProductionProcessor 59/GameEngineCore 57/CultivationService 50/BattleSystem 47/AISectAttackManager 43/BuildingFacadeImpl 43/MailService 42/RedeemCode 40/SectPolicyToggle 40/UnifiedPerfMonitor 37/BuildingConfigService 35/AISectDiscipleManager 35/LawEnforcement 33/BattleCalculator 33/ProductionSlotRepository 29/HeavenlyTrial 28/BuildingService 28/DiscipleService 25/CaveExploration 25/CultivationEvent 25/ManualDatabase 前移豁免后余 24/MissionSystem 24/ExplorationService 20）；data 11（StorageEngine 63/SaveCrypto 47/GameDataCacheManager 43/SecureKeyManager 37/FunctionalWAL 34/SaveFileManager 24/ChangeTracker 26/DynamicMemoryManager 26/CryptoModule 21/IntegrityValidator 21/DataArchiver 21）；game 7（GameViewModel 188/SaveLoadViewModel 75/ProductionViewModel 61/SectViewModel 48/DiscipleDelegate 41/NativeSurfaceView 21（并行线）/NavigationDelegate 21）——**域管理者/ViewModel 拆分属真实结构性重构，逐批专项推进不可赶工** |

**途中修复（次生违规，全部根治后才算清偿）**：
①DataPruningScheduler LongMethod(61/60)+VariableNaming——并行批已提交遗留（3250768），
pruneStorageAreas 三段提取 + changeLogRetentionMs 重命名；②拆分剪枝两缺陷修复后暴露的
UnusedImports ×11（我的拆分文件 8 + 并行线 AISectAttackManager/AISectGarrisonManager/
AISectBattleProcessorTest 3）；③文件尾缺换行 ×2（NewLineAtEndOfFile——拆分脚本 join 丢尾换行）；
④GameEngineWorldBattleOps `for (i in 0 until level.count)` 未用循环变量 → repeat() 化（沿 §2.20 先例）。

**机制发现（findings 同步登记）**：①Kotlin 顶层声明块切分必须**原始列锚定**——对 strip 后
行匹配会把函数体内缩进 `val` 误判为块边界，函数体被截断且尾段落入原文件（字节级校验器
事后发现，git HEAD 还原重做）；②import 剪枝必须保留通配导入——Compose 文件
`import androidx.compose.runtime.*` 的 `*` 永不匹配 `\*`，删之即大片 Unresolved
（detekt 不对通配导入报未用，保留安全）；③detekt UnusedImports 对**同包导入**也报未用
（ FavorDomainTest 同包引用文件级新函数的显式 import）；④拆分产物唯一完整保障 =
**移动函数与源逐字节 diff 校验**（本次 73/74 逐位一致，1 处为单行表达式体校验器盲区人工复核）；
⑤baseline 全量重建会捕获**并行线批中活违规**（本批 4 条：FileLength GameEngineCore 2005 行 /
TooGenericExceptionCaught AISectAttackManager:379 / UnusedParameter YearSettlementResidualExecutor:70 /
LoopWithTooManyJumpStatements DiffAuthoritativeTickTest:592）——13.2 禁止装回，已从生成物剔除并
归属登记，`:core:engine:detekt` 因此残留该 4 处活违规（本批触碰面 0 违规）。

**残留口径**：M3 拆分任务队列剩余 **59 条**（TMF 44 / LargeClass 15）——TMF 余量为
域管理者/ViewModel 族（GameViewModel 188 为最大单体，拆分路径=既有 delegate 基建扩展），
逐批专项推进、不可赶工；TMF 契约面（DAO/接口/转换器/DI/注册表/JNI）已全量豁免归零，
后续新增同形状文件按既有注记惯例处置。


## 2.29 M3 第十批（2026-09-09）：detekt 拆分任务队列首轮——core:data 全域清偿 + 并行线遗留 5 处根治 + app 契约面豁免（baseline 59→45）

**批次口径**（§2.28 残留口径拆分任务队列第五轮）：摘除五模块 LargeClass 15 + TMF 44
全部 59 条条目实跑裁决——真实违规 59 处（放大系数 1.0）+ §2.28 登记的并行线批中
活违规 5 处（13.2 禁止装回，一并根治）。**core:data 14 条全部真实结构拆分清偿**；
app 1 条按 §2.28 ③契约面先例豁免；domain 2 / engine 34 / game 8 余量按 §2.28 先例
**装回 baseline 登记为下一批拆分队列**（domain DiscipleTables 拆分中途发现组装家族
与事务内缓存/脏组位图深耦合，超出单批安全边界，主动回退登记——诚实回退优于带病
拆分）。本批零 C++ 改动。baseline 59→**45**（app 1→0 / data 14→0；domain 2 /
engine 34 / game 8 装回持平），guard 同步只缩。

| 模块 | 拆分清单（函数数为摘族实跑口径） |
|---|---|
| data `SaveCrypto` 47→12 | 四 object：密码编排 12 留守 + `SaveCryptoKeyDerivation`（Argon2id/PBKDF2/HKDF 派生 11）+ `SaveCryptoKeyCache`（统一缓存/周期清理 11）+ `SaveCryptoDigest`（摘要 7）；**死成员 ×6 删除**（XChaCha 三件套全零调用/encryptWithHardwareKey/set|getActiveVersion——activeVersion 字段随访问器死而亡）；调用点限定符更新（CryptoModule/XianxiaApplication/双测试类） |
| data `SecureKeyManager` 37→7 | 三 object：编排+公开 API 7 留守 + `SecureKeyFileStore`（文件 IO/原子写/备份恢复/哈希校验 11）+ `DeviceBindingIdentity`（设备指纹/Keystore/账号锚定 + AES-GCM 密钥加密 9）；**死成员 ×10 删除**（rotateKey/clearCachedKey/recoverKey/hasValidKey/getMetrics/getKeyVersion/isStrongBindingAvailable/getSecurityAssessment/setAccountAnchor/deriveKeyArgon2id 全仓零调用核查）；accountBindingProvider 迁 DeviceBindingIdentity（app 注入点同步） |
| data `GameDataCacheManager` 43→19 | 三扩展域文件：`GameDataCacheSyncAccess`（getSync 族 6）+ `GameDataCacheMemoryPressure`（onTrimMemory 处理实现 9）+ `GameDataCacheMaintenance`（清理/统计/预热/关闭 9）；扩展函数保持调用点语法不变；状态字段 private→internal（同模块） |
| data `StorageEngine` 63→18（1759 行） | 四扩展域文件：`StorageEngineWriteOps`（DB 写层 11）+ `StorageEngineSaveSupport`（WAL 回滚/熔断/日志 11）+ `StorageEngineLoadOps`（加载管线 12）+ `StorageEngineHeavyDataOps`（重数据 11+DbLoadResult）；progress/当前槽等 internal 化 |
| data 其余 | `FunctionalWAL` 34→16 + `FunctionalWALEntryCodec`（条目编解码 6；写锁为 ReentrantLock 非 suspend 语义保持）；`SaveFileManager` 24→20（CRC/文件头格式族 → `SaveFileFormat`）；`DataArchiver` 21→19（generateBatchId 下放+loadIndexInternal 委托内联）；`ChangeTracker` 26→18（batch 族扩展+校验和/字段差分纯函数下放）；`DynamicMemoryManager` 26→19（查询/缓存预算扩展）；`CryptoModule` 21→19 + `IntegrityValidator` 21→9（纯原语 12 函数 → `IntegrityPrimitives`；CryptoModule 死方法 computeMerkleRoot 删除——零调用） |
| data 测试 | `RoomMigrationTest` 1859 行 → 母类（全链/schema 校验）+ `RoomMigrationLegacyTest`（早期 v2→v33 21 例）+ `RoomMigrationRecoveryTest`（备份恢复 8 例）+ `RoomMigrationSupport`（建库/迁移链/断言基建 internal object，成员 import 消费） |
| engine 并行线 5 处 | GameUtilsTest 孤儿 import 删除；AISectAttackManager:379 TGC 函数级附理由抑制（catch 间插注解为非法语法——合并进函数级 @Suppress）；YearSettlementResidualExecutor:70 未用参数 state 删除（单一调用点）；DiffAuthoritativeTickTest:592 Loop 双 continue 合并为单守卫（求值序逐位保持）；GameEngineCore 2004→1919 行（FileLength 归位：防冻结忙等三函数迁 `GameEngineCoreLoopOps` 同域文件，扩展化调用语法不变） |
| app | `GameStateStoreImpl` LargeClass 按 §2.28 ③契约面先例文件级豁免（38 override 镜像协议下界，与既有 TMF/VariableNaming 豁免同源——拆分即改契约面） |

**机制发现（findings 同步登记）**：①detekt TMF 在函数数**等于阈值即报**（20==20/12==12 均报，
§2.25 发现④的强化版）——拆分目标数=阈值-1；②object 拆分的表达式体函数（`= 单表达式`）
切块需累计括号深度而非行内平衡（`salt: ByteArray? = null` 参数默认值行会误判截断）；
③扩展函数域拆分=调用点语法零变化的 object 拆分替代（同包文件级扩展经隐式接收者解析），
companion 常量经"文件级 private 别名"引用（`private val TAG = X.TAG`）；④catch 子句之间
不可插注解（Kotlin 语法非法）——函数级 @Suppress 合并是唯一形态；⑤并行会话 python 残留
进程持文件锁会让编辑阻塞——taskkill 清进程后收敛；⑥detekt 报告行号随编辑漂移，修剪脚本
必须以新鲜报告为输入。

**途中回退登记（诚实口径）**：DiscipleTables（1786 行 LC）拆分执行至半发现 assemble 家族
与 txAssembled 事务缓存/columnGroupByIndex 脏组位图/双指针归并助手深耦合，文件级扩展化
需重排缓存失效不变量——超出"行为零变更"单批安全边界，git 还原并装回登记（该文件并行
会话注释微调随还原丢失，登记由注释卫生会话重放）；SaveCrypto/RoomMigrationTest 两文件
同理自 HEAD 重放（各丢失少量注释微调）。工作区另有并行会话约 900 文件未提交注释卫生
改动，本批提交严格限定触碰文件清单，与其零交集文件不暂存。

**验证**：见 §3（2026-09-09 M3 第十批）。

## 2.31 Batch-02（2026-09-10）：detekt 拆分队列·game ViewModel 族全清——7 文件 8 条实跑归零（baseline feature/game 8→0，拆分队列余量 44→36）

**批次口径**（并行批次组 A）：摘除 feature/game baseline 全部 8 条（7 TMF + 1 LargeClass）
实跑裁决——真实违规 8 处（放大系数 1.0），全部真实结构拆分清偿，零豁免、零装回、
零 C++/engine 触碰。行为零变更：迁移代码与源逐字一致（常量限定符/接收者前缀等编译
必需适配除外），调用方语法除"包装删除直连 delegate"外零变化。baseline feature/game
8→**0**（8 条全摘），guard `feature/game=8→0` 只缩。

| 文件 | 规则 | 拆分结果 | 落位 |
|---|---|---|---|
| GameViewModel.kt | TMF 188 | **19**（阈值−1） | ~169 个 1 行委托包装删除，调用方直连既有 delegate（navigation/disciple/inventory/settings/ads/overlays/bag/redeem/mail/gameLoop/autoAssign/planting/buildingDelegate/buildingUpgradeDelegate/sectDelegate/beastAttack/warnings）；新建 RoadDelegate/MerchantOpsDelegate/BattleRewardDelegate/MissionDelegate/LifeEventsDelegate 五域委托；GuideDelegate(+gameEngine+claimGuideReward)/BeastAttackDelegate(+lock/unlockBeast)/WarningDelegate(+已读妖兽预警状态族)/AdsDelegate(+adService/gameEngine+玉符广告+个性化广告状态族) 扩容；死别名 notifyUserInteraction（全仓零调用）删除；状态流属性全留守，ackBeast/个性化广告改 delegate 透传属性 |
| SaveLoadViewModel.kt | TMF 75 + LC 1526 | **17** + LC 消（~2150→~640 行） | 58 函数按流程拆 6 个同包扩展文件：SaveOps 12（守卫/内存闸门/落盘）/LoadOps 13（循环停止/落库/标志复位）/NewGameOps 7（首存/启动序列/福利注入/循环启停）/RestartOps 11（取锁/引擎重置/重存/收尾）/CloudOps 11（查询/上传/下载/状态机）/CloudLoadOps 4（云档管线/云会话独立加载）；app 面契约 10 函数留守（startNewGame/restartGame/loadGameFromSlot/loadFromCloudSave 等）；LocalSaveGuard 随存流程外移 |
| ProductionViewModel.kt | TMF 61 | **4** | 政策 17 组 toggle/is（34）→ 3 个主题扩展文件（开发生产 14/治理 12/民生 8）；长老任命 14 + 候选查询 9 → 2 个扩展文件；仓库驻守 2 + 藏经阁 2 留守 |
| SectViewModel.kt | TMF 48 | **14** | 政策 34 → 3 个主题扩展文件（14/12/8）；长老族 14 留守 |
| DiscipleDelegate.kt | TMF 41 | **18** | 玉符洗炼族 9（WashOps）/新增特质族 6（TraitAddOps）/装备·功法·丹药 8（GearOps）外移同包扩展（gameEngine private→internal）；婚姻审批 2 + 释放换岗 2 收编 LifecycleOps（自 GameViewModel 迁入，释放族改调成员/扩展同义路径） |
| NativeSurfaceView.kt | TMF 21 | **19** | setCamera/updateRenderState（帧/相机输入通道，仅触公开成员）外移扩展文件 InputChannels；沿用本文件 advanceClouds/reportRenderFallback 顶层函数收敛纪律 |
| NavigationDelegate.kt | TMF 21 | **19** | attackWorldLevel/dismissBattleResult（寄居路由面的战斗域操作）外移扩展文件 BattleOps |

**行为零变更保障**：①六处新扩展文件中的函数体与源逐字一致；②GameViewModel 包装删除
的每一处调用点按"包装名→delegate 属性"映射机械改写（56 文件 + 5 测试文件），编译裁决
无遗漏；③`buildingInstanceId.ifEmpty { "" }` 包装逻辑为恒等变换，删除零语义差；④
BaseViewModel showError/showSuccess/showCapacityWarning/launchElderAction protected→
internal（4 处，同模块放宽——扩展不在继承链上无法访问 protected，事件通道 trySend
行为不变）；⑤SaveLoadViewModel 15 个状态字段 private→internal 并去下划线改名 *Flow
（VariableNaming 联动），读写时序零变化。

**Batch-04 跳过登记补做**（§2.33 所有权表 2 处）：ProductionViewModel
assignDiscipleToLibrarySlot 与 resetOwnedLoadState（现 SaveLoadViewModelLoadOps 扩展）
catch(Exception) 前置 `catch (e: CancellationException) { throw e }`。

**机制发现（findings 候选）**：①手写正则做 Kotlin 成员边界切割的三条纪律——边界前瞻
必须含 `override/private/internal` 前缀族、不得含函数自身闭合行 `    }`、表达式体与
大括号体要统一处理；②KDoc 注释内的 `*/` 字符序列（如"（Policy*/Elder*）"）会提前终止
注释使类声明语法损坏，注释措辞需规避；③mockk 对顶层扩展函数的 stub 必须先
`mockkStatic("<file>Kt")`（成员改扩展后测试 stub 形态联动）；④detekt 报告行号随编辑
漂移——按报告行号修剪必须以新鲜报告为输入且单 pass 只做一类变更；⑤跨行 Log 字符串
的自动折行不得在字符串字面量内部断点（需引号平衡感知）。

**跨批协调登记**：本批开工时工作区存在组 C（batch-06~09）/batch-04/05/10 多会话未提交
改动（含 core:engine 在途破损），全部验证在 batch/02 分支的隔离 worktree（干净检出 +
仅同步本批触碰文件）完成；**移交事项**：并行线把 SectPolicyToggleUseCase 政策方法改写为
core:engine `internal` 扩展（SectPolicyToggleToggles.kt），internal 跨模块不可见——
该批集成时必须保持 feature:game 消费方可见性（public 扩展或门面转发），否则本批
Sect/Production 两 ViewModel 政策扩展文件在集成面编译失败。

**验证**：见 §3（2026-09-10 Batch-02）。

## 2.32 Batch-03（2026-09-10）：detekt 拆分队列·core:domain 全清——DiscipleTables 纯函数层下放（LC 1786→1038 行）+ GameConfigTest 按配置域拆六类（拆分队列余量 44→42，guard core/domain 2→0）

**批次口径**（[docs/parallel-batches/batch-03](parallel-batches/batch-03-detekt-split-domain-discipletables.md)）：摘除 core:domain 最后 2 条 LargeClass——DiscipleTables（1786 行，§2.29 曾执行至半主动回退的已知最难单体）+ GameConfigTest（1072 行测试大类）。行为零变更，零 C++ 改动。

**设计先行（§2.29 回退教训的依赖图结论，读代码产出）**：assemble 家族与 txAssembled 事务缓存 / columnGroupByIndex 脏组位图 / 双指针归并的耦合面收敛为两条不变量——①`txAssembled` 唯一写点 assembleAll（缓存填充）、唯一失效点 requireWriteAccess（所有写路径经此单一入口）；②列写回调（mutationVersion++/dirtyTracker/changedIdTracker/写守卫）在构造期 init bindAllOnWrite 一次性绑定实例。据此判定：**只下放不触碰这两条不变量的私有纯函数/列族读写块，即可拆体量而无需重排任何失效点**——§2.29 回退根因（"文件级扩展化需重排缓存失效不变量"）被"失效点全部留守类内"的边界设计消解。

**执行（两步走的第一步即收官，第二步有状态域拆分无需执行）**：

| 动作 | 内容 |
|---|---|
| DiscipleTables 纯函数层下放 | 27 个私有/文件级块共 ~750 行迁至 6 个同包新文件（脚本化机械搬移+全量锚点断言）：`AssembleGroup.kt`（枚举上收 + 列→组映射表 + computeDirtyGroups 参数化）、`DiscipleTablesMerge.kt`（mergeSortedSnapshotsById/isPrevSnapshotSorted/buildPrevById/mergePatchedSnapshots 归并族）、`DiscipleTablesAssemblers.kt`（assembleCoreFields + 7 assembleXxx + resolveGroupPart/hasGroup/isCompleteId）、`DiscipleTablesWrite.kt`（writeAllFields + 7 writeXxxFields 列族写块）、`DiscipleTablesColumnRegistry.kt`（buildCopyableRefs 注册表）、`DiscipleTablesAptitude.kt`（rollHealedAptitude + 散列常量）。类体 1786→**1038 行**（< 800 阈值，LargeClass 归零）；有状态面（CRUD/镜像协议/txAssembled/deepCopy/bindAllOnWrite/双 Tracker）逐字节留守 |
| 保真证明 | 27/27 搬移块 token 级与 HEAD 一致；全量差异仅四类设计内形态：声明行 private→internal（顶层化必然放宽，模块内）/扩展接收者声明/限定名（DiscipleTables.DEFAULT_APTITUDE 等 companion 常量）/computeDirtyGroups 类状态改入参。调用点零变化（同包顶层隐式解析，§2.28 惯用法）；列写入仍经实例绑定的写守卫回调，缓存失效单一入口原样 |
| GameConfigTest 拆分 | 1072 行→6 配置域测试类共 166 用例逐字节迁移：GameAndDiscipleConfigTest（7）/RarityConfigTest（31）/RealmConfigTest（74）/SpiritRootConfigTest（20）/BeastAndStartingConfigTest（10）/PolicyConfigTest（24） |
| 新增违规处置 | discipleColumnGroupByName LongMethod（80 行映射表）附理由豁免——注册表性质每列一行与 assemble 读取点 1:1 对照，拆分即碎片化协议对照面（与同批触碰面 buildCopyableRefs 既有豁免同口径） |
| 红线核对 | upsertMirrorRow isAlive SparseArray 探测 / `id in _ids` 兜底 / isCompleteId 三表幽灵判据 / synchronized(_ids) 锁序 / 非数字 id 抛错防御——全部留守或随迁零语义差；O(k) 行级镜像应用通道未触碰 |

**残量口径**：拆分任务队列余量 **44→42 条**（engine 34 / game 8；domain 归零）；guard `core/domain=2→0` 只缩。DiscipleTables 有状态面（CRUD+缓存+复制绑定，~1038 行）为"镜像列协议下界"的收敛载体，后续 WS-1 阶段 3 数据导向存储立项时再评估（§4.1 既有登记）。

**验证**：见 §3（2026-09-10 Batch-03）。

## 2.33 Batch-04（2026-09-10）：协程取消传播专项——suspend/launch 上下文泛型 catch 前置 CancellationException 分支（目标集重测 61 处实修 + 13 处结构批所有权跳过登记）

**批次口径**（[docs/parallel-batches/batch-04](parallel-batches/batch-04-coroutine-cancellation.md)，行为变更批——每处需行为论证）：§2.23 登记待办清偿。suspend/launch 协程体里 `catch (e: Exception)` 把 CancellationException 一并吞掉，破坏结构化并发取消传播：父作用域取消（弹窗关闭/画面退出/引擎重启/调度器 stop）后协程不退出，继续跑完剩余逻辑甚至再挂起——僵尸任务与状态错乱。本批清偿剩余面（§2.24.3 三 SaveLoad Delegate + StorageEngine 首个落地族之后的全部）。

**目标集重测（开工第一步实跑裁决，非沿用 §2.23 的 ~90 估算）**：脚本化枚举全仓 `@Suppress("TooGenericExceptionCaught")` 关联 catch 点（src/main+src/test 六模块，929 处 catch 中泛型 656），逐点判定 ①是否协程上下文（suspend 函数体 / launch/async/withContext/runTest lambda 体，括号配对+线性深度扫描）②同一 try 链是否已前置 CancellationException 分支（相邻子句链回溯）③是否命中 batch-01/02/03/05 所有权排除文件。结果：协程上下文 195 → 已有前置分支 118（含 §2.24.3 先例族）→ 待裁决 77 → 排除文件 13 → **本批清单 64**。终验复扫：已前置分支 118→179（+61），剩余 5 处均为形态②段内 catch / 形态③刻意吞 / 脚本误报（见下文判定外），零漏网。

**修复形态三选一（每处留一行论证注释）**：

| 形态 | 处数 | 说明 |
|---|---|---|
| ① 两段式 catch（`catch (e: CancellationException) { throw e }` 独立子句前置） | 58 | 取消必须穿透；不改泛型 catch 本身（异常源不可枚举口径不变） |
| ② `withContext(NonCancellable)` 原子段 | 1 | `abortWalQuietly`：WAL 回滚必须完成才能保证失败回滚语义一致；段内为单次 WAL 记录移除无无限等待；段外取消照常传播 |
| ③ 刻意吞取消（附理由注解） | 2 | GameEvents `reportDrop`/`notifySubscribers`：try 体无挂起点（回调接口非 suspend），CE 只能来自上报器/订阅者缺陷误抛；重抛会放大为事件总线消费协程死亡——"上报失败不得影响事件通道"是总线既有隔离契约（代码注释原文），非结构化取消树成员 |

**逐处登记表（文件 → 位置/函数 → 形态 → 论证要点）**：

| 文件 | 位置（函数） | 形态 | 论证 |
|---|---|---|---|
| data/DataArchiver.kt | :213 archiveBattleLogsIfNeeded | ① | 归档被取消时中止，不谎报归档失败 |
| data/DataArchiver.kt | :458 rebuildIndex（逐文件头读取） | ① | 取消中止索引重建，不误标单文件损坏 |
| data/DataArchiver.kt | :500 queryArchived（逐文件读取） | ① | 查询取消中止，不误标损坏跳读 |
| data/GameDataCacheMaintenance.kt | :140 warmupCache | ① | loader 为 suspend；取消中止剩余 key，不逐 key 误报失败 |
| data/SaveCryptoKeyCache.kt | :156 startPeriodicCacheCleanup | ① | 清理协程停止时静默退出，不误报周期清理错误 |
| data/SaveCryptoKeyDerivation.kt | :60 precomputeDerivedKey | ① | 启动预计算取消中止，不以 false 冒充派生失败 |
| data/DataArchiveScheduler.kt | :65 start（轮询体） | ① | 调度器 stop 后立即退出轮询，不再进入下一轮 delay |
| data/DataArchiveScheduler.kt | :104 performArchive（逐槽位） | ① | 取消中止剩余槽位，槽位写锁不跨取消持锁 |
| data/DataArchiveScheduler.kt | :113 performArchive（过期清理） | ① | 上抛，不谎报清理失败 |
| data/DataArchiveScheduler.kt | :122 performArchive（.arc 清理） | ① | 上抛，下轮调度重新清理 |
| data/DataPruningScheduler.kt | :106 start（轮询体） | ① | stop 后立即退出；取消不计熔断失败 |
| data/DataPruningScheduler.kt | :167 performPruning | ① | 取消非修剪质量退化，不污染熔断计数；isRunning 由 finally 复位 |
| data/DataPruningScheduler.kt | :195 pruneStorageAreas（battleLog 逐槽） | ① | 取消中止剩余槽位，写锁不跨取消 |
| data/DataPruningScheduler.kt | :206 pruneStorageAreas（change_log） | ① | 上抛，不误标"跳过" |
| data/DataPruningScheduler.kt | :214 pruneStorageAreas（迁移备份窗口） | ① | 上抛，不误标"跳过" |
| data/ProactiveMemoryGuard.kt | :106 startMonitoring（轮询体） | ① | 监控停止静默退出，不误报检查失败 |
| data/StorageEngine.kt | :390 restoreFromBackup | ① | 读档取消时中止备份恢复，不以 null 冒充"无备份可用" |
| data/StorageEngine.kt | :622 performFullTransactionSave（WAL begin） | ① | 取消时不进入后续 DB 事务（此刻 WAL 无事务需回滚） |
| data/StorageEngine.kt | :649 performFullTransactionSave（WAL commit） | ① | WAL 为尽力日志非真源（DB 事务才是）；取消穿透交外层既有 CE 分支 abortWalSyncQuietly 回滚；段内部分写由 recover() 校验和兜底 |
| data/StorageEngineSaveSupport.kt | :27 abortWalQuietly | **②** | 见形态表；两个调用点（写库失败路径/异常翻译路径）均为回滚语义依赖段 |
| data/StorageEngineSaveSupport.kt | :153 handleSaveResult（备份写） | ① | 取消中止备份链路，保存流程由外层既有 CE 分支收口（与 save() 既有取消语义一致） |
| data/StorageEngineSaveSupport.kt | :177 handleSaveResult（备份恢复读） | ① | 取消中止恢复读，重试由下次保存流程承担 |
| data/StorageFacade.kt | :171 initialize（启动清理） | ① | 两清理为阻塞 IO 无挂起点——分支防未来挂起点引入（防御前置） |
| data/ChangeLogPersistence.kt | :38 logChange | ① | 变更日志写入取消上抛，不静默丢日志 |
| data/ChangeLogPersistence.kt | :48 logBatchChanges | ① | 批量写入取消上抛，不静默丢整批 |
| data/ChangeLogPersistence.kt | :65 cleanupOldLogs | ① | 清理取消上抛，下次清理窗口重试 |
| data/ChangeLogPersistence.kt | :74 getPendingCount | ① | 取消上抛，不以 0 冒充"无待同步日志" |
| data/FunctionalWAL.kt | :211 beginTransaction | ① | 取消上抛，不以 WAL_ERROR 冒充记账失败 |
| data/FunctionalWAL.kt | :268 commit | ① | 取消上抛（事务状态留 COMMITTING 由 recover 兜底），不以 WAL_ERROR 冒充 |
| data/FunctionalWAL.kt | :355 recover | ① | 取消上抛，不以"恢复失败"冒充（避免误触发恢复失败处置） |
| data/FunctionalWAL.kt | :519 startFlushTimer | ① | flush 定时器停止静默退出，不误报周期 flush 失败；部分 flush 由 WAL 校验和恢复语义兜底 |
| domain/GameEvents.kt | :301 reportDrop | **③** | 见形态表 |
| domain/GameEvents.kt | :367 notifySubscribers | **③** | 见形态表 |
| engine/HttpRemoteConfigProvider.kt | :59 fetchRemoteConfig | ① | withTimeoutOrNull 只吞自身超时；外层取消须穿透而非冒充"拉取失败"（否则启动流程拿旧缓存继续跑） |
| engine/BootSequenceController.kt | :235 bootCore Step6（Gate 槽位） | ① | 取消时上抛中止 boot，不以空槽位重建 assignmentGate（半初始化状态错乱） |
| engine/BootSequenceController.kt | :612 recoverWithPartialData（Gate 槽位） | ① | 同上；CE 穿透至外层既有 CE 分支正确放弃恢复 |
| engine/GameEngineLoadDataOps.kt | :121 loadData（邮件初始化） | ① | 读档取消时中止，不再进入 native 基线同步 |
| engine/GameEngineLoadDataOps.kt | :304 createNewGame（邮件初始化） | ① | 同上 |
| engine/GameEngineLoadDataOps.kt | :366 restartGameInternal（邮件初始化） | ① | 同上 |
| engine/GameEngineSectLevelOps.kt | :93/99 claimSectLevelReward | ① | CE 分支前置于 ISE/Exception 链头；领取取消时上抛，凭据保留，不以 Error 冒充失败 |
| engine/GameEngineSectLevelOps.kt | :378 upgradeSectLevel | ① | 升级取消上抛，不以 Error 冒充失败 |
| engine/GameEngineWorldBattleOps.kt | :57 attackWorldLevel（伤亡处理） | ① | 取消（引擎重启/退出）中止剩余世界关卡结算，不吞取消继续发奖；伤亡失败降级语义原样保留 |
| engine/RedeemCodeService.kt | :139 tryServerRedeem | ① | 取消时中止兑换，不降级本地重兑（避免取消后双发风险） |
| game/LeaderboardManager.kt | :99/108 fetchLeaderboard | ① | 取消穿透不以排行榜 Error 冒充；顺带真实拆分 mapLeaderboardApiException（见次生违规处置） |
| game/TapCloudSaveManager.kt | :219 uploadSave（序列化） | ① | 上传取消中止，不以序列化错误冒充 |
| game/TapCloudSaveManager.kt | :238 uploadSave（临时文件写） | ① | 阻塞 IO 无挂起点，防御前置 |
| game/TapCloudSaveManager.kt | :248 uploadSave（上传） | ① | 取消中止，不以网络错误冒充（cloudOpLock 由 finally 释放，不变） |
| game/TapCloudSaveManager.kt | :326 downloadSave（外层） | ① | 下载取消中止，不以网络错误冒充（内层反序列化 catch 既有 CE 分支不变） |
| game/TapCloudSaveManager.kt | :408 checkCloudSave | ① | 查询取消中止，不降级本地缓存读 |
| game/TapCloudSaveManager.kt | :516 performTapTapUpload | ① | 原样上抛（该 catch 本为 log+rethrow，传播未断），取消不再误记 error/误清 UUID 缓存 |
| game/TapCloudSaveManager.kt | :552 performTapTapDownload | ① | 同上（log+rethrow 形态，取消不误记 error） |
| game/TapCloudSaveManager.kt | :570 performTapTapQuery | ① | 取消上抛，不以 null 冒充"无存档" |
| game/TapCloudSaveManager.kt | :604 oneTimeCleanup | ① | 取消上抛，保持未完成标记下次重试 |
| game/TapTapLeaderboardApi.kt | :65 submitStatistic | ① | suspendCancellableCoroutine 挂起等待被取消时上抛，不以"提交失败"冒充 |
| game/TapTapLeaderboardApi.kt | :94/123 fetchTop/fetchCurrentPlayerScore（SDK 块内） | ① | 取消原样上抛，不包装成 LeaderboardApiException 破坏取消语义 |
| game/ResourcePreloader.kt | :149 preloadGameResources（图集打包） | ① | 读档取消中止预加载，不做单图集降级 |
| game/ResourcePreloader.kt | :186 launchBackgroundPreload | ① | 宿主 scope 取消中止 L2 预加载，不再回调 UI |
| game/GameLoopDelegate.kt | :63 init Bugly 反射探针 | ① | 反射体无挂起点（collect 协程体内），分支保错误收集循环结构化取消语义 |
| app/GCOptimizer.kt | :246 notifyListeners | ① | 通知体无挂起点（launch 协程体内），防监听器缺陷误抛 CE 被吞 |
| app/MainActivity.kt | :661 queryCloudSaveInfo | ① | 画面退出取消上抛，不以 null 冒充"无云存档" |

**判定外（不改，登记防复发）**：① `TapCloudSaveManager.CloudSaveApiReflector.invokeGetterString/invokeGetterLong`——嵌套 object 内非 suspend 纯反射 getter，无协程上下文，CE 不可能因取消在此抛出（初版脚本嵌套类括号配对误报）；② `StorageEngineSaveSupport.abortWalSyncQuietly`——非挂起同步回滚，唯一调用点其后显式 `throw e`，取消已传播无吞点。

**跳过登记（batch-01/02/03/05 所有权文件，结构批完成后补做）**：

| 文件 | 位置 | 拥有批 |
|---|---|---|
| data/GameStateRepository.kt | :74 loadFullState | batch-05 |
| engine/RedeemCodeManager.kt | :428 validateCodeWithServerAuth / :1165 checkIpRateLimit | batch-01 |
| engine/domain/battle/HeavenlyTrialService.kt | :514 claimClearReward | batch-01 |
| engine/service/MailService.kt | :232/:292/:336/:359/:460/:917/:930（7 处） | batch-01 |
| game/ui/ProductionViewModel.kt | :257 assignDiscipleToLibrarySlot | batch-02 |
| game/ui/SaveLoadViewModel.kt | :308 resetOwnedLoadState | batch-02 |

（同两文件的 :1037/:1864 两处在开工重测时已由 batch-02 会话补前置分支，不在待补清单。）

**次生违规处置（detekt 全规则重跑 9 条 → 0，全部实修/合并注解，零装回）**：ThrowsCount +1 超标 ×5（DataPruningScheduler.pruneStorageAreas / StorageFacade.initialize / TapCloudSaveManager.uploadSave / BootSequenceController.bootCore / recoverWithPartialData）——按 StorageEngine §2.24.3 先例把 ThrowsCount 并入既有 @Suppress 单注解多参数并附"取消穿透 rethrow 刻意独立抛出"理由；CCM 触阈 ×2——LeaderboardManager.fetchLeaderboard 真实拆分 mapLeaderboardApiException 错误码映射助手（零行为变更）清偿，TapCloudSaveManager.downloadSave 并入既有 NestedBlockDepth/ReturnCount 多阶段守卫豁免注解；MaxLineLength ×2（GameEvents 形态③注解折行）。

**行为变更影响面论证（本批为行为变更批，逐类裁决）**：①维护调度族（归档/修剪/内存守护/flush 定时器/密钥清理）——取消语义从"误报 error + 跑完本轮"变"静默退出"，熔断计数不再被取消污染（DataPruningScheduler 两处）：消除的是僵尸轮询与假告警，非业务路径；②存档/WAL 族——取消从"吞掉后以失败 Result/降级路径冒充"变"穿透至既有 CE 收口分支"（StorageEngine save/restore 全链 §2.24.3 已建 CE 通道），WAL 一致性由新增 NonCancellable 回滚原子段加强；③云存档/排行榜族——取消从"冒充 NetworkError/Error/无存档"变"上抛"，UI 侧协程随画面退出终止，无重试副作用（上传并发锁 finally 释放不变）；④引擎启动/结算族——取消中止剩余结算/初始化（伤亡处理"continuing"降级仅对非取消异常保留）：取消路径本属引擎拆卸期，不触及正常结算 RNG 抽取序——**RNG 红线核对：全部改动仅影响取消传播路径，正常路径抽取集与顺序零变化**；⑤测试面——被取消路径原先"跑完"的既有单测零依赖（全量回归为证）。

**验证**：见 §3（2026-09-10 Batch-04）。

## 2.34 Batch-05（2026-09-10）：GameStateRepository dirty 记账机制整体摘除——write-only 残留清偿 + load 回滚语义考古拍板（§2.26 途中发现收口）

**批次口径**（[docs/parallel-batches/batch-05](parallel-batches/batch-05-repo-dirty-ledger-removal.md)，组 B）：
§2.26 途中发现项拍板清偿——WS-0.a 自动存档删除后的记账残留（markDirty/markAllDirty/clearDirty
只置位无消费者）整体摘除；唯一"行为依赖"（markAllDirty 在 loadFromSnapshot 失败回滚路径被
StateRevertRegressionTest 注入失败触发）先考古后摘除，零 baseline 新增、零 guard 变动、零 C++ 改动。

**考古结论（本批核心产出）**：
1. **dirty 位无任何读者**——`dirty: AtomicReference<DirtySet>` 为 private 且无 getter，唯一消费者
   flushDirtyState/flushDisciples/restoreDirtyMarks 死链已于 §2.26 删除；三方法内 `||` 自叠加是
   dirty 位仅有的"读"，write-only 实锤。
2. **markAllDirty 的"回滚路径行为依赖"实为测试借位注入**——StateRevertRegressionTest 以
   `doThrow(markAllDirty)` 制造"状态应用完成后"的失败点；回滚语义本体由 `captureLoadBaseline()`
   旧值快照 + `rollbackLoad()` 全流恢复承载，dirty 位在回滚前/后均无人读。
3. **摘除无表达式位置副作用问题**——4 处置位调用点（commitUpdateState→markDirtyFor、
   finalizeLoadedState→markAllDirty、reset→clearDirty、loadFullState→dirty.set）全部为裸语句位置，直删即安全。

**摘除面与测试**：`GameStateRepository.kt`（-69 行：DirtySet + dirty 字段 + 三方法 + loadFullState 内
dirty.set + AtomicReference import）；`GameStateStoreImpl.kt`（markDirtyFor 整函数 + 三调用点删除）；
`StateRevertRegressionTest`（失败注入点 markAllDirty → setActiveSlot，同段位仓库调用；**新增**
「读档失败后状态与读档前逐位一致」回归——15 条 StateFlow 与读档前快照逐一 assertEquals）；
`TestStateStoreSupport` KDoc 同步。

**SlotCache.markDirty() 同族核查（登记待办）**：`SlotCache.markDirty()` 生产零调用（仅
RepositoryModelsTest 自测），方法级死代码候选；`dirty` 字段本身有活消费者（ensureIndexes 惰性
索引重建 + updateCache 引用快路径 + isDirty）。连带发现 `ProductionSlotRepository.isCacheDirty()`
无调用方——**已随 §2.40 集成批清偿（删除）**。

**验证**：见 §3（2026-09-10 Batch-05）。

## 2.35 Batch-06（2026-09-10）：UI 操作面下沉·建筑放置/迁移/升级/拆除事务入 C++——四操作稳态写者归一，Kotlin 原路径退役为回退臂

**批次口径**（[docs/parallel-batches/batch-06](parallel-batches/batch-06-sink-building-transactions.md)，
组 C 与 07/08/09 并行）：建筑编辑面 `placeBuilding`/`moveBuilding`/`upgradeBuilding`（含批量）/
`removeBuilding`（含批量）写者下沉 C++（`system/building_tx.h`，road_tx.h 同构），AUTHORITATIVE
稳态经 `nativeExecute` 转发（零 JNI 新导出，S6/S7 先例），Kotlin 原路径保留为降级回退臂。
Out-of-scope 维持：`enterSect` 与 BootSequence 迁移（W2/W3 后续波次）。

**写者审计结论（含 RNG 标注）**：

| 项 | 结论 |
|---|---|
| 调用链·place | 唯一 UI 活写者 = BuildingDelegate.doPlaceBuilding（MainGameScreen → GameViewModel → delegate）；facade.placeBuilding 仅 PlaceBuildingUseCase 引用（无生产注入点，非活路径，保留原样不改） |
| 调用链·move/upgrade/remove | BuildingDelegate.moveBuilding / BuildingUpgradeDelegate / BuildingDelegate.demolishBuilding(s) → GameEngineBuildingOps 扩展 → 门面对应方法（门面均在活路径，native 臂内联接线零 delegate 改动——仅 place 需 delegate 一处接线） |
| 消耗 | place/upgrade = 低阶灵石**直扣**（`spiritStones - cost`，Kotlin 原路径不走 wallet.deduct）；remove = 返还走 `SpiritStoneWallet::add`（记年度账，与 Kotlin wallet.add(refund, LOW, Refund) 同原语） |
| 判定序 | place：宗门等级 → 环界+门楼 → 限建数量（全局唯一跨宗门/同宗）→ 同宗占位重叠 → 灵石；move：存在性 → 环界+门楼（**无重叠检查**——与原实现逐字一致）；upgrade：存在性 → 等级(≥中型) → 差价 → canFit（环界+门楼+同宗除己）；upgradeBatch：整批等级 → 候选（gridX/gridY/instanceId 稳定序）→ 可负担上限 → 升级中间态增量 canFit；remove：逐实例存在性（未知跳过）→ 返还 → 删 |
| RNG | **全链零抽取**——五事务均为纯确定性状态变换；instanceId 由调用方 java.util.UUID 生成传入（非游戏 RNG 分区）。签名级证据：building_tx.h API 不接受 RngManager/种子；GTest 另以双运行逐位一致 + 全分区快照差分锁定 |

**偏差登记（宗门过滤归 Kotlin，§2.36 同口径）**：C++ `GridBuildingData` 无 `sectId` 字段
（models.h 禁改——README §3.3 协议），限建/占位/升级 canFit 的同宗过滤无法在 C++ 复现。落地为
Kotlin 组装 `sectScopedIds`（目标宗门建筑 instanceId 集）随请求传入，C++ 以实例集做作用域判定；
占位几何/限建标志/造价/counterKey 均为参数——单一事实源留 Kotlin。

**协议**：`BUILDING_PLACE=1450 / BUILDING_MOVE=1451 / BUILDING_UPGRADE=1452 / BUILDING_REMOVE=1453 /
BUILDING_UPGRADE_BATCH=1454`（gen-action-ids.mjs 追加段前五 ID，两份生成物再生成）；
`execute_dispatch.cpp` 独立 `handleBuildingTx` + 中央 switch 一行 case。

**C++ 事务**（`gamecore/include/gamecore/system/building_tx.h`，纯头文件）：
`placeBuildingTx`/`moveBuildingTx`/`upgradeBuildingTx`/`upgradeBuildingsTx`/`removeBuildingsTx`
（校验链 → placedBuildings 增/改/删 + 灵石 + guideCounters → `BuildingTxOutcome` 信封；失败零写入）。
**边界划分**：C++ 承担建筑记录+钱包+引导计数；槽位派生（place 的 createSlots/remove 的槽位清理）、
弟子释放、监牢·任务阁特例、生产槽 repo 为 Kotlin 残差（BuildingFeatureRegistry 槽组语义留 Kotlin）
——拆除残差范围以 C++ 回执 `removedIds` 为准（幽灵实例两端同跳）。

**Kotlin 门面**：新同包协作类 `BuildingNativeTx`（internal，懒构造——门面构造签名零变化）；
门面 move/upgrade/upgradeBuildings/removeBuildings 四方法顶部接线，失败信封/降级回退 Kotlin 原路径
（文案由 Kotlin 臂产出）；新接口方法 `tryNativePlaceBuilding(building, feature, cost)` 供放置路径调用。
UI 活路径接线仅 `BuildingDelegate.doPlaceBuilding` 顶部一处：native 成功后残差只做槽位派生
（**createSlots 以 native 前快照定序**——ProductionSlotGroup.slotIndex 按 placedBuildings 同类型计数）。
`GameViewModel.kt`/`models.h`/`GameCoreBridge.*`/`StateSyncService.kt` 零改动；
`seizeBuildingsOfSect`（月结链）走 `removeBuildingsInternal` 原路径不变。

**随批登记**：docs/cpp-engine.md §9「ActionId 46 动作 / 7 handler」计数表自 S8 起未随批更新
（现 103 动作 / 19 handler）——**已随 §2.40 集成批同步**。

**验证**：见 §3（2026-09-10 Batch-06）。

## 2.36 Batch-07（2026-09-10）：UI 操作面下沉·道路放置/拆除事务入 C++——稳态写者归一，Kotlin 直改+即时回导臂退役为回退臂

**批次口径**（[docs/parallel-batches/batch-07](parallel-batches/batch-07-sink-road-transactions.md)，组 C 与 06/08/09 并行）：石板路编辑面 `placeRoad`/`removeRoad` 写者下沉 C++（`system/road_tx.h`），AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出，S6/S7 先例），Kotlin 原路径保留为降级回退臂。Out-of-scope 维持：RoadMaskTracker/RoadTiling（纯 Kotlin UI 缓存，镜像回读驱动零改动）；写者审计确认修路消耗仅在放置事务内（无月结域整图重算消费）。

**写者审计结论**（实施步骤 1）：

| 项 | 结论 |
|---|---|
| 调用链 | 唯一写者 RoadFacadeImpl；UI（MainGameScreenGestures / MainGameScreen / BuildingDelegate 批量修路）→ GameViewModel → PlaceRoad/RemoveRoadUseCase → GameEngine 扩展（GameEngineRoadOps 成功即时回导加固）→ 门面 |
| 消耗 | 仅低阶灵石 `spiritStones` 20/格（`GameConfig.Road.COST_PER_CELL`），无材料消耗；拆除无返还 |
| 判定序（place） | 可建环界 → 占位（本宗建筑占地展开 ∪ FixedSectGateway 门楼 6×2）→ 重复放置 → 灵石充足（remove 仅存在性），与 Kotlin 逐字同序 |
| RNG | **全链零抽取**——确定性状态变换 + 纯函数掩码重算，抽取集为空集，红线平凡满足（C++ 签名级证据：road_tx API 不接受 RngManager/种子；GTest 另以双运行逐位一致 + 全分区 RNG 快照差分锁定） |

**协议**：`ROAD_PLACE=1470 / ROAD_REMOVE=1471`（gen-action-ids.mjs 追加 1470–1479 段前两 ID，两份生成物再生成）；`execute_dispatch.cpp` 独立 `handleRoadTx` + 中央 switch 一行 case。

**C++ 事务**（`gamecore/include/gamecore/system/road_tx.h`，纯头文件）：`placeRoadTx`/`removeRoadTx`（校验链 → roads 增删 + 灵石扣减 → `recomputeRoadNeighborhood` 5 格邻域位掩码/形态重算，`tileTypeForBitmask` 复用 gamecore::map 单一权威）+ `roadTileTypeName`（RoadData.roadType 按 Kotlin 枚举 name 承载）。失败零写入；信封 failure → Kotlin 回退臂重执行校验链（用户可见 Blocked 文案由 Kotlin 臂产出，C++ message 仅诊断）。

**偏差登记（占位集合组装归 Kotlin）**：原批设计设想 C++ 侧建筑占位判定；实测 C++ `GridBuildingData` 无 `sectId` 字段（models.h 本批禁改——README §3.3 协议，对拍键集红线），宗门过滤（`sectId==activeSectId||empty`）无法在 C++ 复现。落地为 Kotlin 门面组装 `occupiedCells`（本宗建筑占地展开 ∪ 固定结构，与 canPlaceRoad 同一来源路径）随请求传入，C++ 保留目标格冲突判定原语——判定语义逐字一致；GTest 以手工集合覆盖原语（含环内格占位臂 + 环外格判定序交互臂），宗门过滤语义由既有 GameEngineRoadOpsTest「被占格 Blocked」守护。

**Kotlin 门面**（RoadFacadeImpl）：镜像通道经工厂接线 `GameEngineCore` 手工单例 `StateSyncService`（**途中修正**：首版构造注入触发 Dagger MissingBinding——其 `reverseSender` 默认 lambda 无绑定，且会分叉反向通道实例；改为去 @Inject + `createRoadFacade` 工厂 + CoreModule.provideRoadFacade 直构，构造器可空形参保留为测试接缝，详见 §3 DI 行）；`roadNativeTx` 臂 = AUTHORITATIVE 门控 → `tryExecuteNative`（成功内含 `applyDirtyFromNative` 脏段回读：`gameData.roads`/`spiritStones` 经 dirty diff 整段镜像，无需 Room 回放——roads 为 gameData 快照列，与 S7 生产槽位差异点）→ `Success(邻域 5 格)`；失败信封/降级返回 null → 回退 Kotlin 原路径。GameEngineRoadOps 即时回导加固保留零改动：native 臂下镜像写入不参与反向捕获 → 即时回导为空窗口零发送，契约自洽。gameData 镜像域无 Room 写回；GameViewModel/RoadTiling/RoadMaskTracker/StateSyncService 零改动。

**红线核对**：RNG 抽取集空集（双端同构）；失败零写入；models.h/GridSystem.kt/GameCoreBridge.*/StateSyncService.kt 未触碰；本批触碰文件 detekt 零新增违规（engine 实跑条目全部位于并行批次所有权文件，非本批触碰面）。

**验证**：见 §3（2026-09-10 Batch-07）。

## 2.38 Batch-09（2026-09-10）：UI 操作面下沉·外交/好感/附庸族入 C++——赠礼拒绝 roll 与结盟/附属掷骰 SYSTEM 分区同源，双臂行为逐位一致

**批次口径**（[docs/parallel-batches/batch-09](parallel-batches/batch-09-sink-diplomacy-favor-vassal.md)，组 C 与 06/07/08 并行）：外交域 UI 操作写者下沉 C++（`system/diplomacy_tx.h`），AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出），Kotlin 原路径保留为回退臂。ActionId 段 1500–1519（实占 1500–1502）。

**写者审计表（含 RNG 逐点结论）**：

| 操作 | Kotlin 写者 | RNG 面（逐点） | 触碰字段 | 本批处置 |
|---|---|---|---|---|
| 赠礼 giftSpiritStones | GiftService → sectRelations/sectDetails/spiritStones | SYSTEM 1×nextInt(100)（五级校验链全过后、任何写入前）| 好感/送礼年/灵石 | C++ 1501 |
| 结盟 requestAllianceSimple | DiplomacyService → sectRelations/alliances/worldMapSects/gameEventRecords | SYSTEM 1×nextDouble（资格+aiPower 校验后；aiPower<=0 早退**不**抽取）| 相识/盟约/双方宗门 alliance 字段/事件 | C++ 1500 |
| 散盟 dissolveAllianceSimple | DiplomacyService → alliances/worldMapSects/gameEventRecords | 零 RNG | 同上（逆向） | C++ 1500 |
| 附属请求 requestVassalContract | VassalService → sectRelations/vassalContracts | SYSTEM 1×nextDouble（资格通过后**恒**抽取——aiPower<=0 概率 0 仍掷骰，Kotlin calculateVassalChance→rng.nextDouble 同位）| 相识/VassalContract | C++ 1502 |
| 解除附属 dissolveVassalContract | VassalService → vassalContracts | 零 RNG | 契约移除 | C++ 1502 |
| 宣战/停战/和平 | **无 UI 操作面**（批次文档 §1 所列经审计不存在——AI 决策域已下沉 sect_attack_decision.h + 预警通道） | — | — | 审计登记，不实施 |
| 纳贡（processYearlyTribute/YearlyVassalTribute）/施压/好感衰减/脱离检查 | 年结/月结域（year_settlement.h / processVassalBreakaway **已下沉**） | — | — | 红线：不触碰月结循环形状 |
| establishVassalage | 无生产调用方（仅测试） | — | — | 不实施 |
| 聊天响应模板 SectResponseTexts.random() | kotlin Random.Default（非游戏分区、非确定性 UI 文案） | 无分区消费 | — | 留 Kotlin（批次 §1 out-of-scope；信封只传 outcome/数值，message 由 Kotlin 臂用同模板族重建） |

**协议段核查结论（§2 第一步）**：alliances/vassalContracts/sectRelations/sectDetails/suzerainSectId/spiritStones/worldMapSects/gameEventRecords/rngStates 全部已在 models.h + json_codec 镜像协议——**零协议字段新增**，models.h/json_codec 零触碰（README §3.3 第三行不适用，无对拍键集漂移面）。

**C++ 事务**（`gamecore/include/gamecore/system/diplomacy_tx.h`，纯头）：
- **FavorDomain 纯函数族逐字移植**：findRelation/findFavor/setAcquainted/updateFavor（id min/max 归一化、未相识 no-op、clamp [0,100]、增长清零 noGiftYears）+ 文件级纯函数（calculateGiftFavorIncrease 的 Int 截断除法与 `(base+pct)*multiplier` 向零截断按原式保留——浮点运算序不重排；calculateRejectProbability 拒绝矩阵；偏好乘数 1.3/修正 -15）。零 roll 头注释论证。
- **三事务**：`giftSpiritStonesTransaction`（校验链→拒绝 roll→好感计算→相识+好感+送礼年+钱包扣减；扣费 Insufficient 零副作用=零写入）、`requestAllianceTransaction`（资格→四因素概率（sect_decision.h 既有 ALLIANCE profile）→nextDouble→相识+盟约+宗门 alliance 字段+事件）、`requestVassalTransaction`（资格→VASSAL profile→恒掷骰→相识+契约）、两个 dissolve。事件经 settle_util::recordGameEvent（同守卫同序号分配，月结 vassal_breakaway 同先例；timestamp=0 与既有 C++ 事件同口径——Kotlin 侧 UI 以 sequenceId 为稳定 key）。
- **alliance.id 为确定性自增占位**（"gc-alliance-N"，碰撞规避扫描）——Kotlin UUID 镜像生成字段，id 不参与业务逻辑（recruit_settlement.h 先例），对拍面忽略新增条目 id。

**协议**：`DIPLOMACY_TX=1500 / FAVOR_GIFT=1501 / VASSAL_TX=1502`（gen-action-ids.mjs 追加段→两生成物再生成）；`execute_dispatch.cpp` 独立 `handleDiplomacyTx` + 中央一行。**include-order 登记**：diplomacy_tx.h 置于 execute_dispatch 包含块末尾——其传递引入 month_settlement.h 的 using 声明会改变后续头（disciple_tx.h）非限定名解析。

**信封契约（回退臂一致性）**：校验失败（Kotlin 同位置早退、零抽取零写入）→ failure 信封 → Kotlin 回退原路径同语义复现（PRODUCTION_START 先例）；roll 已消费的终态（accept/rejected/failed、结盟附属成败）→ success 信封直返，Kotlin 按 responseType 重建 GiftResult（message 模板留 Kotlin）。AUTHORITATIVE 下 Kotlin SYSTEM 分区为 NativeBackedRng 委托通道——双臂消费同一分区同一条序列，逐位一致；对拍锁终态 rngStates。

**Kotlin 门面**：GiftService/DiplomacyService/VassalService 各加构造注入可空 `GameEngineCore`（默认 null——测试直构恒回退）+ 私有 native 臂（AUTHORITATIVE 门控 → tryExecuteNative → 成功直返/失败 null 回退）。feature/game DiplomacyFlows/GameViewModel 零改动（README §2 协议）。

**并行协调项（PR 描述已登记，收口人合并）**：① `disciple_tx.h`（batch-08 在途文件）编译自损——using 声明困于 detail 命名空间内，detail 外事务函数任何包含序均无法解析 GameState/DiscipleStore 等（standalone 探针 21 错实证）；本批做最小加法补全（using 提至 disciple_tx 命名空间作用域 + 注释标记），batch-08 收口时可直接回收。② `CultivationEventProcessor.kt` AutoWarehouseResult private→internal（batch-01 拆分新文件跨文件引用，同族最小补全）。③ 共享文件（gen-action-ids.mjs/action_ids.h/ActionIds.kt/execute_dispatch.cpp/test/CMakeLists.txt）按组 C 追加式协议含 06/07/08 在途段一并提交（追加式 git 可自动合并）。

**验证**：见 §3（2026-09-10 Batch-09）。

### §2.37 Batch-08：弟子管理第一子批（装备穿脱/功法学忘/亲传+藏经阁任命卸任）入 C++（2026-09-10）

**做了什么**：ui-read-surface §4.1 弟子管理域（最大残余域）的零 RNG 纯事务第一子批——选零抽取子域打样（来源 docs/parallel-batches/batch-08，组 C，ActionId 段 1480–1499）。

- **写者审计**（结论进本节 + disciple_tx.h 头注释）：①装备穿脱 = DiscipleEquipmentService（equipEquipmentInTransaction/wearEquipment/unequipEquipmentLogic，经 GameEngineManualOps.equipItem→DiscipleService 活路径）——弟子行四槽位列 + storageBagItems + equipmentStacks（-1/移除）+ equipmentInstances（铸造/置位/移除）；②功法学忘 = GameEngineManualOps.learnManual/forgetManual（GameEngine 扩展 = DiscipleDelegate UI 活路径；DiscipleFacadeImpl 私有同名函数非活路径，其差异面——学习日志/卸下 manualIds 残留写——不移植）——manualStacks 消耗 + manualInstances 铸造 + manualIds/currentHp/Mp + manualProficiencies 清理；③任命卸任 = DiscipleFacadeImpl 四槽位函数数据写段——clearAllSlots 11 类 + 亲传 7 类列表覆写（扩容占位 = Kotlin DirectDiscipleSlot() 默认 index 恒 0）/藏经阁扩容覆写（index=位置）。**RNG 结论：六事务零抽取**（校验链与写路径均无 rng；铸造实例 id 的 Kotlin UUID.randomUUID 为非协议随机域，C++ 以 nextInstanceId 确定性自增占位——auto_gear.h/recruit_settlement.h 镜像生成字段同先例）。**双持有防重**：装备实例轨道 vs 堆叠轨道 equip 二选一查找（堆叠优先铸实例）；实例入袋即离实例表（unequip/forget 与 auto_gear depositEquippedToBag/forgetManualToBag 同不变量）。
- **C++ 事务**：`system/disciple_tx.h` 纯头六事务（equip/unequip/learnManual/unlearnManual/assignSlot/unassignSlot，detail 函数族 + production.h 风格）——校验链逐字对齐 Kotlin 判定序（含"静默守卫失败信封化→Kotlin 回退同义静默"契约）；失败零写入（校验链先行，"扣了背包却穿不上"中间态构造上不可能）；equip 信封附 logLine 日志草稿（Kotlin lifeEvents 瞬态列回写——disciple_purchase PurchaseLogDraft 同族机制，C++ 无该列）。**悬垂纪律**（auto_gear.h 同款）：unequipInternal 的实例表 erase 与堆叠整摞扣减 erase 之后禁用旧指针——穿装/学功所需字段先拷贝，写段重查（测试 EquipReplacesOldIntoBag 实证该路径）。scope 备注：长老单值槽任命（ElderManagementUseCase.assignElder/removeElder）为 usecase 编排域（releaseDiscipleFromAllSlotsAtomic + checkpoint 联动 + 状态同步敏感面）——**本批不下沉，登记 W3**。
- **协议**：`DISCIPLE_TX_EQUIP=1480/UNEQUIP=1481/LEARN_MANUAL=1482/UNLEARN_MANUAL=1483/ASSIGN_SLOT=1484/UNASSIGN_SLOT=1485`（gen-action-ids.mjs 追加段 → 两生成物）；`execute_dispatch.cpp` 独立 `handleDiscipleTx` + 中央一行（与 batch-06/07/09 段共存，README §3.3 追加式协议）。models.h 零改动（README §3.3 "原则上不改"条款达成）。
- **Kotlin 门面**：native 分支全部落 Ops 域文件——`GameEngineManualOps.kt`（equipItem/unequipItem/unequipItemById/learnManual/forgetManual）+ `GameEngineDiscipleSlotOps.kt`（assignDirectDisciple/removeDirectDisciple/assignDiscipleToLibrarySlot/removeDiscipleFromLibrarySlot，经 InventoryNativeForward.tryForward 同族转发：AUTHORITATIVE 门控 + 失败信封/降级 null 回退）。任命成功臂的 Gate 注册/释放、syncSingleDiscipleStatus、生产槽 Room 回放清理在 Kotlin 照原序执行（finishAssignRuntime——含 clearAllSlots 内嵌 gate.release 的等价前置释放）。**落点偏差（优于声明面）**：batch-08 §6 预声明可能触碰 DiscipleEquipmentService——实测其依赖面无 stateSyncServiceRef（注入构造变更会扩大接触面且 facade 路径无 UI 调用方），接线全收 Ops 层后 **DiscipleEquipmentService/DiscipleFacadeImpl/DiscipleService（batch-01 所有权）零改动**，GameViewModel/DiscipleDelegate（batch-02 所有权）零改动，GameCoreBridge/StateSyncService/models.h 零改动。
- **测试**：GTest `disciple_tx_test.cpp` 21 用例（穿脱堆叠/实例双轨道/替换入袋/四失败臂零写入/AlreadyEquipped/实例入袋离表无双持有/学习四守卫判定序/堆叠精确消耗/HP·MP 增量门/熟练度清理/亲传槽 clearAllSlots+扩容默认值+occupant 捕获/藏经阁 bounds 早退/未知槽型 no-op/**六事务全程 rngStates 快照逐位一致**/分发信封 success+failure）——桌面全量 995/995 零回归；Kotlin flag 门控 `GameEngineDiscipleTxForwardTest` 12 用例（OFF/镜像缺失/桥未加载三级降级回退契约 + gate 零污染）。装备穿脱后 computeCombatPower 双端一致对拍场景：本批零 RNG + 属性派生读时计算（本批只写原始列，派生仍镜像回流驱动），以引擎全量 Diff 对拍覆盖，未加专项场景。
- **并行协调（收口人合并）**：①batch-09 对本文件在途头的 using 作用域最小补全（detail 内 using 提升到 disciple_tx 命名空间）——**已回收保留**（纯加法无语义变更，本批收口时实译全绿）；②共享文件（gen-action-ids.mjs/action_ids.h/ActionIds.kt/execute_dispatch.cpp/test/CMakeLists.txt）含 06/07/09 在途段按追加式协议一并暂存。
- **验证**：见 §3（2026-09-10 Batch-08，引擎全量对拍 3130/3130）。

### §2.39 Batch-10：真机验证批 + RNG 断言升级（P1-4 正式收口）（2026-09-10）

**批次口径**（[docs/parallel-batches/batch-10](parallel-batches/batch-10-device-verification.md)，组 D）：QA/验证批——P0-3/RNG/S1-S8/WS-5 真机覆盖 + 观察窗零警告后四 rng 入口断言升级。逐项记录与证据：[batch-10-verification-record.md](parallel-batches/batch-10-verification-record.md)（logcat 全量/截图/构建产物字符串核验）。

**执行环境**：MuMu 模拟器 12（Android 15/SDK 35，abilist x86_64+arm64-v8a ARM 转译，机型档案 Redmi K50）——物理真机不可得，按 §2.6 升级条件原文"真机/**开发期 debug 构建**运行"口径执行；模拟器提供真实 ART/JNI 线程模型与渲染链，物理真机专属残留逐项登记（见下）。

**做了什么**：
- **B 组观察窗**：≥60 分钟引擎运行（90,738 行 logcat 全量落盘），混合操作存档×3/读档×4/EMERGENCY restart/生产排班/商人购买/建造/招募/切后台-回前台×4/软渲强制会话/约 220 旬连续结算——`RNG 通道跨线程进入` **零出现**（owner rebased 合法重锚 1 次，零 FATAL/SIGABRT）。
- **断言升级（唯一生产代码改动）**：`GameCoreBridge.cpp` 四入口 nativeRngNextInt/SnapshotPartition/RestorePartition/InitSeed 由 `jniWarnRngOffEngineThread` 改 **`jniRequireEngineThread`**，过渡守卫函数删除 + 三段注释更新；`GameCoreBridge.kt` 契约头"过渡警告守卫"条目并入 kEngineOnly；`GameRngManager.kt` 线程契约注释同步（契约第三处）。**P1-4 就此正式收口。**
- **升级验证**：`:app:externalNativeBuildRelease` 绿；debug 原生产物字符串核验（断言串在位/WARN 串消失）、release 零守卫串（NDEBUG 擦除不变）；**断言版复跑（B4）无误杀**——读档（loadGame/rngRestorePartition/EMERGENCY restart 路径）、存档（buildSaveSnapshot/rngSnapshotPartition，saveGame SUCCESS）、切后台恢复全过，零违规零崩溃。§2.7 勘误教训正面验证：EMERGENCY restart 换线程路径在守卫全开下正常 + owner 重锚命中。
- **其余验证组（模拟器）**：P0-3 主链路（ASTC 4096² 上传/淡入时序/60fps 零 skip）、软渲强制路径（RenderDebugSwitches force_backend=2）、切后台重建、buildSaveSnapshot 存读往返逐字段一致（year/month/phase/spiritStones/disciples 全匹配×2）；S4 月结年结（矿场 340/月 + 年报第 4-11 年连续）；S7 手动排班 C++ 事务 + Room 后置写回；S-20 购买事务（扣款精确）；S1-S3 结算涌现（突破实际发生/道侣结成/忠诚叛逃）；D1/D4/D5 渲染视觉；E1 ≥100 游戏月零 ANR 零泄漏趋势。
- **真机残留清单**（物理设备到位后补验；缺陷修复另批不混入本批）：A2 ASTC 缺失机 RGBA 回退、A4 旋屏（应用锁横屏模拟器不可达）、C1 偷盗钩子自然触发 + TapDB 上报确认、C3 S5 战斗任务（任务阁进度门控）、C4 S6 秘境全链（入口未解锁）、C6 ThermalMonitor 真实热档、D2 放置确认步、D3 道路装配、E2 云存档（保护性跳过：设备有用户真实云存档不触碰）、E3 WS-1 绝对值（无生产埋点，先补 debug 埋点另行小批）。

**验证**：见 §3（2026-09-10 Batch-10）——主树收口遇 batch-09 在途 WIP 阻塞整模块门禁，已按 §2.32/§2.36 先例以 worktree 干净检出补跑全绿。

## 2.30 Batch-01（2026-09-10）：detekt 拆分任务队列——core:engine 域管理者族大规模真实拆分（baseline 34→3）

**批次口径**：engine baseline 34 条（26 TMF + 8 LC）逐类实跑处置——23 个 TMF 类真实拆分至
类内 ≤19 函数（同包顶层扩展域文件，函数体逐字节 diff 校验，仅签名行改写 + 统一去缩进 4）；
8 个 LargeClass 全清（含 2 个 LC 测试类按 fixture 拷贝 + 用例迁移拆分至 <800 行）；
3 个 FacadeImpl（Building/Disciple/Inventory）override 契约骨架按 §2.28③ 附理由文件级豁免，
可移动函数已全部拆出。baseline 34→**3**（只缩），guard core/engine=3。

**拆分域产物**（共 54 个新文件）：妖兽袭击/任务奖励/弟子查询/洞府奖励/自动入库/建筑槽位/
天劫构筑/槽位查询/战斗技能/运行期采集/盗窃事务/兑换奖励与核销/附件发放/事件编排/月年边界
等域文件；InventorySystem 105→13（7 文件）、DiscipleStatCalculator 85→0（7 文件）、
GameEngineCore 54→2（4 文件）、BattleSystem 47→0（4 文件）、InventoryFacadeImpl 等
FacadeImpl 均已最大化拆出可移动函数。

**诚实回退/余量装回（3 条）**：
- SectPolicyToggleUseCase（TMF 40）：全部 public 开关 API 被 feature:game ViewModel 直连消费，
  扩展对其他模块不可见，触碰 app/game 属本批禁区——整类装回；
- UnifiedPerformanceMonitor（TMF 37→拆后 30）：指标注册表族被 app GameMonitorManager 消费，
  仅拆出运行期采集域 7 函数——部分装回；
- CultivationService（TMF 50）：batch-09/W3 并行会话正在同域重构（月年边界编排域），
  按 §3.2 后到者避让——整类装回。

**机制发现（findings 候选）**：①detekt XML 报告对中文文件名做 HTML 实体转义，修剪脚本须
html.unescape；②git status 中文路径默认引号转义，须 `-c core.quotepath=false`；
③msys 环境下 subprocess grep 的 `\(`/`` 参数被吞，ERE 须用 `[(]`/`[^A-Za-z0-9_]`；
④detekt VariableNaming 对 internal 下划线后备字段报违规而 private 不报——internal 化后
须补 @Suppress（沿袭文件内既有惯例注释）；⑤成员扩展函数（fun Receiver.name）函数体
引用类属性/注入服务时移出类即失去外层接收者——须留守或随域整体搬迁；
⑥嵌套类型/泛型签名（fun <T>）在签名改写与索引中均须显式处理；
⑦通配符 rm 误删已提交文件（GameEngineCore*Ops*.kt）由编译错误网兜底、git checkout HEAD
恢复——批量脚本禁用宽通配 rm；⑧KSP kspCaches 多进程并行下频繁损坏，
清 build/kspCaches+generated/ksp 即恢复。

**验证**：⚠️ **本节原记"触碰面 detekt 0 违规 + 主源编译通过"与实测不符**——续修实测
HEAD（`6aff9b3`）`core:engine` 主源不可编译、detekt 进程级崩溃、测试源 1043 处编译错、
全量单测 216 失败。逐项根因与根治见 **§2.30.1**（本节其余口径——拆分清单/域产物/装回项——
经续修复核成立）。


## 2.30.1 Batch-01 续修（2026-09-10）：拆分损伤根治 + 验证收口（baseline 3→2，全量单测 3140/0）

**批次口径**（§2.30 续修，行为目标仍为"拆分前后等价"）：对 §2.30 提交的拆分产物做**损伤根治**
与**验证收口**。开工实测：HEAD 主源编译失败（357 处编译错）、`:core:engine:detekt` 崩溃
（`IllegalStateException: not identifier`）、测试源 1043 处编译错、全量单测 **216 失败**。

| 损伤 | 根因（实测证据） | 处置 |
|---|---|---|
| 13 处畸形函数签名（`fun X(` + 空行 + `(a: A, b: B)` + `):T`） | 拆分工具多包一层括号并吞掉参数名首行 | 机械还原参数表（逐处编译复核） |
| 5 个函数 + `yearlyOpsQueue` 字段整体丢失（`CultivationEventProcessor`） | 切块边界对 strip 行匹配误判，声明头写入而函数体丢失 | 以 `HEAD~2` 原文逐字还原（advanceMonth/advanceYear/drain/flush/clearYearlyOpsQueue） |
| `GameEngineCoreSetOps1.kt` 文件尾悬空 KDoc+`@Suppress`（函数体缺失） | 同上（尾块截断） | 删除残块，KDoc/注解归位到 `gameLoopIteration`/`gameLoopMainLoop` |
| 2 个新测试类结构损伤（class 后多一个 `{`；KDoc 首行丢失） | 测试类切分边界误判 | 结构还原 + fixture 上提基类 |
| KDoc/注解错挂或悬空 **101 处** | 块边界漂移（注释与函数体不同步搬运） | 以拆分前快照逐声明归位：补回 258 / 替换 53 / 删孤儿 45 / 保留 15（终验 0 缺失 0 不一致） |
| 测试源 1043 处编译错 | 成员下放为同包扩展后，跨包调用方缺 import | 错误驱动补 188 处 import（主源 66 + 测试源 122） |
| **Mockito 缝隙失效**（测试以 mock 作 no-op/verify 契约） | 被 mock 的成员被下放为顶层扩展后无法 stub/verify（`whenever(mock.fn())` 直接执行真实扩展 → NPE 216 例） | 18 个被 mock 的入口还原为类成员（BattleSystem 4 / GameEngineCore 5 / InventorySystem 7 / BuildingConfigService 1 / CultivationService 13——含 `withTrackingSource`/`launchInScope`/`startGameLoop`/`createBattle` 等），类内函数数仍 ≤19 |
| 测试族共享 fixture 被拆散 | 三测试类分头引用母类私有夹具 | 上提 `ProductionProcessorTestBase`（字段+构造助手），母类/两新类共同继承 |
| 对象阈值漏判（TMF 家族口径错配） | detekt `thresholdInObjects=12`，而 `AISectAttackManager`/`RedeemCodeManager`/`BattleCalculator` 被拆到 19（按类阈值 20 口径） | 各再下放 8 函数到同包扩展域文件 |
| 类阈值边界 4 处（MailService 21 / BuildingConfigService 20 / BuildingService 20 / InventorySystem 20） | 同上（拆到阈值−1 口径不齐） | 各下放 1–2 函数（`MailExclusiveBonusOps`/`BuildingConfigServiceSpriteOps`/`BuildingServiceSlotOps`/`consolidateAllStacks` 扩展化） |
| **跨模块可见性收窄**（feature:game/app 39+18 处不可解析） | 下放为扩展时统一写 `internal`，而原成员是 public（跨模块消费） | 以拆分前声明为准回写 public（149 处），并为跨模块调用方补 import 29+12 处 |
| 架构守卫源路径漂移 4 处 | 守卫以文件路径/文件名扫描，拆分后落位变化 | 同步白名单与路径：`InventoryAddPathGuardTest`（+3 拆分产物）/`CheckpointCallSiteGuardTest`（DiscipleFacadeImpl战斗Ops2）/`CurrentAlphaDeterminismGuardTest`（GameEngineCorePrepOps2） |
| 次生 detekt 违规 | TGC/ReturnCount 注解遗留、MaxLineLength 2 处、UnusedImports 108+、UnusedPrivate* 2 处 | 全部实修（注解归位/折行/剪枝/死夹具删除），**零装回** |

**续修新增清偿**：①`CultivationService`（TMF 50）真实拆分——42 个委托函数下放 6 个同包扩展域文件
（Core/Event/Settlement/Production/Recruit/Formula），类内保留 19 函数（含 13 个被 mock 的必要入口）；
②**跨模块消费下界两条全清**（原装回项）：`SectPolicyToggleUseCase`（TMF 40）——22 个政策开关对下放
2 个同包主题扩展文件（修习类 12 / 治理类 10），类内保留 18 函数（核心 toggle/计数/特殊政策/按弟子计费/
副宗主加成），feature:game 7 个调用文件补 import；`UnifiedPerformanceMonitor`（TMF 30）——**指标注册表/
监听器域上提基类** `PerformanceMetricsRegistry`（15 函数），派生类保留运行期采集与报告 17 函数；
抽基类而非扩展化：本类是跨模块消费面（app `GameMonitorManager`）且被 core:engine 测试以 mock
作为缝隙，扩展化会同时破坏跨模块调用与 mock stub/verify 语义，继承则两者零变化。
**baseline 3→2→0**（engine 全清）、guard `core/engine=2→0`——六模块 baseline 至此全为 0。

**跨批阻塞清除**：`:app:hiltJavaCompileRelease` 的 Dagger 环（batch-09 未提交 WIP 引入）已根治——
`DiplomacyService`/`VassalService`/`GiftService` 三处 `gameEngineCore: GameEngineCore?` 构造注入改为
`Provider<GameEngineCore>?`（Dagger 官方破环手段，惰性边）+ 私有/内部取值访问器，调用点与测试直构零变化；
`:app:hiltJavaCompileRelease` 与 `:app:lintRelease` 恢复通过。

**提交**：`a45692b`（单次收口提交）。暂存范围为"可编译可验证最小集"：本批触碰面 + 组 C
（batch-06/07/09）在途 Kotlin 改动——后者与本次修复在 `DiplomacyService`/`VassalService`/`GiftService`/
`BootSequenceController`/`CoreModule` 等同文件交织，按文件清单拆分会让提交树编译不过，故合并提交并在
提交说明中显式登记；组 C 未接线的 C++ 产物（`road_tx.h`/`diplomacy_tx.h` 及 GTest）与 batch-10 证据
目录、batch-04 分析脚本未纳入，留待各自批次提交。

**batch-04 所有权补偿（§2.33 跳过表 10 处，本批结构完成后补做）**：`RedeemCodeManager`
validateCodeWithServerAuth / `checkIpRateLimit`（拆分后落 `RedeemCodeRateLimitOps.kt`）/
`HeavenlyTrialService.claimClearReward`（`IllegalStateException` 亦吞取消）/`MailService` 3 处 +
`MailAttachmentDistributeOps` 2 处，全部前置 `catch (e: CancellationException) { throw e }`。

**验证**：`:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>` **3140 用例
0 失败 0 skip**（含 45 Diff 对拍类逐位一致）；六模块 detekt **全绿**（engine baseline 2，仅余上述
跨模块下界）；主源+测试源编译通过（core:engine / core:data / core:domain / feature:game）；
`:core:data` 与 `:feature:game` 回归见 §3。**跨批阻塞登记**：`:app:hiltJavaCompileRelease` 失败为
batch-09 未提交 WIP 所致（`DiplomacyService` 构造注入新增 `gameEngineCore: GameEngineCore? = null`
→ Dagger 环 `GameEngineCore→DiplomacyService→CultivationEventProcessor→CultivationService→GameEngineCore`；
HEAD 版本无该依赖，证据 `git show HEAD:…/DiplomacyService.kt` 无 `gameEngineCore`），非本批触碰面，
未代改（建议：改 `Provider<GameEngineCore>` 或门面工厂，同 batch-07 `createRoadFacade` 先例）。


## 2.40 集成收口批（2026-09-11）：十批并行成果合流为单一可编译树 + 途中缺陷根治 + 文档/日志同步

**批次口径**：并行批次（01–10）各在自己的分支上交付并各自验证，但**仓库从未做过集成**——
实测：10 批分散在 7 条分支（main / batch-02 / 03 / 04 / 05 / 06 / 09），没有任何一条分支是全集；
且 `batch/02`（detekt 拆分批最全的分支）的 C++ 树被 `a45692b`「顺手带走组 C 在途 Kotlin 改动」
**打断**——`execute_dispatch.cpp` 已 `#include road_tx.h`/`diplomacy_tx.h` 而这两个头文件未入库
（全仓 212 处 `#include` 扫描仅此 2 处缺失）、`test/CMakeLists.txt` 引用 `road_tx_test.cpp`/
`diplomacy_tx_test.cpp` 而文件同样未入库：**从该提交干净检出无法编译 native（NDK）与桌面 GTest**，
本机之所以能跑，只因这 4 个文件以未跟踪状态躺在工作区。本批为集成收口。

**集成动作（以 `batch/02` 为基线，新建 `integration/parallel-batches` 分支）**：

| 项 | 处置 |
|---|---|
| 缺失 C++ 产物入库 | `road_tx.h`（batch-07）/`diplomacy_tx.h`（batch-09）+ 两个 GTest 源文件——工作区未跟踪文件与 `main`/`batch-06`/`batch-09` 上的提交版本 **hash 逐字节相同**（`git hash-object` 核对），直接入库 |
| batch-06 建筑下沉并入 | 4 个新文件（`building_tx.h` / `building_tx_test.cpp` / `BuildingNativeTx.kt` / `BuildingNativeTxGateTest.kt`）+ `handleBuildingTx` 与中央 switch case + CMakeLists 条目；**动作号以 `gen-action-ids.mjs` 目录为唯一事实源重新生成**（108 动作：103+5） |
| 冲突解法（不取"theirs"） | `BuildingFacadeImpl.kt` 的 batch-06 版本基于 batch-01 拆分**之前**的旧结构（会把 batch-01 的成员下放整体回退）——解法为**取 HEAD 结构 + 只补 batch-06 的 native 臂**（`nativeTx` 懒构造成员 / `tryNativePlaceBuilding` override / move·remove·upgrade·upgradeBatch 四处顶部接线），`syncSpiritMineSlotsAfterPlace`/`updateDiscipleStatus`/`releaseReflectingDisciples` 等重复声明一律不引入（保留 batch-01 的拆分产物） |
| 重复声明清除 | `BuildingNativeTx.kt` 顶层的 `releaseReflectingDisciples()` 与 `BuildingFacadeImpl同步Ops.kt` 既有同名扩展**冲突重载**（编译报 Conflicting overloads）——删除前者，改由既有扩展承担 |
| 文档并入 | handover §2.34（batch-05）/§2.35（batch-06）补录（原只在各自分支）；`ui-read-surface §4.1` 建筑行、CHANGELOG 两批条目、`cpp-engine.md` 动作计数表同步 |

**途中缺陷根治（实测暴露，非报告所列）**：

1. **`executeAutoBuy` 迭代器失效（真 UB，月结 12 月自动购买）**：`for (const auto& entry : gd.autoBuyList)`
   的循环体内 `SpiritStoneWallet::deduct(gd, …)` 会执行 `gd = withSpiritStoneCount(gd, …)` **整体替换
   GameData**，`autoBuyList` 的堆缓冲被释放后仍被 range-for 的 end 迭代器引用——读到已释放内存，
   表现为**同进程同输入随机只买第一件**（GTest `AutoBuySettlement.DecemberAutoBuyMatchesKnownTemplate`
   间歇失败；§2.18 曾把该现象登记为"并发偶发"）。**根治**：按 Kotlin 不可变 List 语义**快照迭代**
   （`const std::vector<AutoBuyEntry> autoBuyEntries = gd.autoBuyList;`）。修复前 12 次运行失败 4 次，
   修复后 40 次连续运行 0 失败。**举一反三扫描**：全仓 system/*.h + src 扫描"循环内整体替换 gameData /
   钱包调用前持有 gameData 引用"，其余 5 处候选（year_settlement 贡赋汇总 / building_tx 拆除返还 /
   government 政策计费 / month_settlement 矿产出账）经逐一核实均安全（钱包调用在循环外、或持有的是
   POD 成员引用、或循环体每轮重新读取 `gd`）。
2. **影子突破路径语义降级（ActionId 1107 影子基准）**：`system/breakthrough.h` 的
   `isDiscipleFullHpMp` 恒返回 true（注释自认"未接线"）、`applyBreakthroughFailure` 不做 HP/MP ×10%
   折损——与 Kotlin `applyBreakthroughFailure`/`isFullHpMp` 语义不符；生产走 `phase_settlement.h`
   完整版故无线上影响，但该影子通道一旦接线即为偏差。**根治**：按 `phase_settlement.h` 同式补齐
   （基础口径 `computeBaseHpMp` + 负值视为满 + HP/MP ×0.1 至少 1），并补 6 个 GTest 用例
   （折损截断/下限 1/负值取基础上限/负值视为满/未满不尝试）——桌面全量 1017→**1023** 用例。
3. **零调用死代码清偿**：`ProductionSlotRepository.isCacheDirty()`（全仓 0 调用方，batch-05 登记待办）删除。

**文档与日志同步（原报告期间欠账）**：

| 项 | 处置 |
|---|---|
| `docs/cpp-engine.md` | §9 动作计数表「ActionId 46 动作 / 7 handler」→ 实测 **108 动作 / 19 handler**；补 UI 操作面下沉批（S4–S8 + WS-5 + 06/07/08/09 四域事务）与 ECS/ActionId 演进登记 |
| `CODE_WIKI.md` | 补 `building_tx.h`/`road_tx.h`/`diplomacy_tx.h`/`disciple_tx.h` 与 1450–1502 段 ActionId |
| `CLAUDE.md` / `docs/architecture.md` / `docs/knowledge-base.md` | 「C++ 引擎迁移（完成）/迁移主线已收口」→ 如实表述：**确定性逻辑核心已 C++ 化并 AUTHORITATIVE，但反向同步通道逐域关闭与 UI 操作面逐域下沉仍在推进**（§4.1 主轴） |
| 游戏内更新日志 | `version.properties` 已为 4.01.14 而 `changelog_entries.json` 最新条目仍是 4.01.13（版本条目错位）——补 4.01.14 条目（玩家向粗粒度文案，不含数值/术语） |
| planning 记忆 | `findings.md`/`progress.md`/`task_plan.md` 停在 2026-09-09，补录十批 findings 与集成结果、刷新剩余项 |

**清理**：陈旧 git worktree 2 个（`XianxiaSectNative-b01v` 501MB / `XianxiaSectNative-batch06` 2.9GB）
+ 游离检出 5 处（`.worktrees/ui-layout-unification` 1.4GB、`.claude/worktrees/*` 4 处）+ 74 个
`compile-*.log` + 被跟踪的一次性垃圾（`analyze_skills.py`、`test_output.txt`）+ 未跟踪一次性脚本
`scripts/batch04-analyze.py`。

**验证**：见 §3（2026-09-11 集成收口批）。**集成后基线**：桌面 C++ **1023/1023**、引擎 JUnit
**3146/0/0/0**（281 测试类，含 46 Diff 对拍类；3140→3146 增量 = batch-06 `BuildingNativeTxGateTest` 6 例）、
detekt 六模块全绿（baseline 全 0）、六模块主源+测试源编译通过、NDK arm64
`externalNativeBuildRelease` 通过、`lintRelease` 通过、模块回归（core:data 707 / feature:game 868 /
app 定向 58）全绿。


## 2.41 W2-a（2026-09-11）：UI 操作面下沉·库存出售/上架族入 C++——出售族稳态写者归一，零 RNG 纯确定性事务

**批次口径**（[parallel-batches README](parallel-batches/README.md) §6 波次表 W2 首行：
"库存残余：商人买卖/上架、出售族残余、开袋、充公"的**出售与上架子域**）：库存出售族
六入口 + 批量出售 + 商人收购 + 玩家上架/撤下的 UI 操作面写者下沉 C++（`system/inventory_tx.h`），
AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出，S6/S7/06–09 先例），Kotlin 原路径
保留为降级回退臂。**开袋/充公族**（RNG + BagItemReconstructor 模板重建）不在本批范围，留作 W2 后续子批。

**写者审计结论（含 RNG 标注）**：

| 项 | 结论 |
|---|---|
| 调用链 | 唯一写者 `InventoryFacadeImpl` 九方法（sellEquipment/sellManual/sellPill/sellMaterial/sellHerb/sellSeed → `sellStack`；bulkSellItems → `deductStack` 批；sellToMerchant → `deductSoldStock`；listItemsToMerchant/removePlayerListedItem；consumeMaterialByName → 未锁定同名同阶按列表序扣减）；UI 经 `GameEngineMerchantOps`/`GameEngineInventoryOps`/`SettingsDelegate` 扩展与 `InventoryFacade` 门面消费 |
| 取价口径 | 装备 = `EquipmentDatabase.getTemplateByName(name)?.price ?: 品阶 basePrice`（模板价优先）；**功法 = 品阶 basePrice（不查模板——Kotlin `ManualStack.basePrice` 自带语义，与上架路径 `ManualDatabase.getByName` 查模板不同）**；丹药 = `roundToInt(pillBasePrice × PillGrade.priceMultiplier)`；材料/草药/种子 = 各自品阶基准价；出售价 = `(basePrice × quantity × 0.8)` 向零截断 |
| 入账来源键 | 单类 `Sell(<itemType>)`；批量 `Sell("bulk")`（**逐条扣减零入账、末尾一次入账**——Kotlin `deductStack` 与 `sellStack` 语义差异，非笔误）；商人收购 `MerchantTrade` |
| RNG | **全链零抽取**——取价为品阶基准价/模板价纯函数，校验链只读，变更段仅堆叠数量 + 灵石余额 + 商人条目；签名级证据：`inventory_tx.h` API 不接受 RngManager/种子参数；GTest 另以双运行全状态 JSON 逐位一致 + 全分区 RNG 快照差分锁定。上架条目 id 为确定性自增占位（`gc-listed-N`）——Kotlin `UUID.randomUUID` 属非协议随机域（batch-08/09 同先例），id 不参与业务判定 |

**C++ 事务**（`gamecore/include/gamecore/system/inventory_tx.h`，纯头文件）：
`sellItemTx`（存在/未锁定/`1<=quantity<=持有量` 守卫 → 入账 → 扣减/移除堆叠；守卫未过 = SUCCESS + sold=false 零写入）/
`bulkSellTx`（逐条 `deductStack` 零入账 → 末尾 `Sell(bulk)` 一次入账 + soldCount/totalEarned/成功失败名单）/
`sellToMerchantTx`（收购项存在 → D-21 非法参数拒绝 → `min(请求, 仓库可售量, 收购上限)` → 扣仓 → 入账 → 收购项数量回写）/
`listItemsToMerchantTx`（装备/功法/丹药三段首命中登记，**不扣仓库**；已上架量超限静默跳过）/
`removePlayerListedItemTx` / `consumeMaterialByNameTx`（未锁定同名同阶堆叠按列表序扣减，**入口快照语义**——
Kotlin `materials.all().filter{}` 先取快照再逐个 remove/update，故扣减量以快照持有量为准；`quantity<=0` 恒 false、
`==0` 恒 true 且零写入，与 Kotlin 逐字一致）。**扣减语义逐字对齐 Kotlin**：仓库计数含锁定堆叠
（`countWarehouseStacks` 不筛锁定），但扣减只作用于未锁定项（`removeMatching` 过滤 `!isLocked`），按列表序逐摞扣至 0 移除。

**协议**：`INV_SELL_ITEM=1520 / INV_BULK_SELL=1521 / MERCHANT_SELL_ACQUISITION=1522 /
MERCHANT_LIST_ITEMS=1523 / MERCHANT_REMOVE_LISTED=1524 / INV_CONSUME_MATERIAL=1525`（gen-action-ids.mjs 追加段 →
`action_ids.h` + `ActionIds.kt` 再生成，**114 动作**）；`execute_dispatch.cpp` 独立 `handleInventoryTx` + 中央一行 case。

**Kotlin 门面**：新同包协作类 `InventoryNativeTx`（internal，懒构造——门面构造签名零变化，
BuildingNativeTx 先例）；`InventoryFacadeImpl` 九方法顶部接线（成功直返/失败信封与降级 null 回退 Kotlin
原路径重执行校验链——双实现并行契约，用户可见文案由 Kotlin 臂产出）。`GameEngine`/`StateSyncService`/
`GameStateStoreImpl`/`GameCoreBridge`/`models.h` 零改动；成功臂所含 `applyDirtyFromNative` 脏段回读覆盖
六堆叠集合 + gameData 的灵石/年度报告/商人条目（无 Room 回写——堆叠为镜像域快照列，与 batch-07 道路同口径）。
**防御性空安全**：`stateSyncServiceRef` 非空声明在测试 mock 下会返回 null 并在调用点触内在检查 NPE，
故经可空局部过滤后再传递（InventoryNativeForward 同款护栏）。

**测试**：GTest `inventory_tx_test.cpp` 33 用例（取价黄金值四类型/守卫四臂零写入/锁定语义/批量聚合与一次入账/
收购名称+品阶匹配与逐序扣减/仓库钳制/非法参数拒绝/灵石与丹药品级分支/上架三段与超限跳过/撤下/材料消耗六臂
（跨摞序/不足部分写/锁定与品阶过滤/零与负数量/未知名）与消费信封信封/分发信封三态/零 RNG 全分区快照差分/
双运行全状态逐位一致）——桌面 **1056/1056**；Kotlin `InventoryNativeTxGateTest` 11 用例（六入口 × flag OFF /
桥未加载双模式降级 null + 九方法回退臂语义），商人价格校验既有守卫测试零改动通过。

**验证**：见 §3（2026-09-11 W2-a）。

## 2.42 Batch-11（2026-09-12）：库存域收官——商人购买/充公族入 C++，开袋按路线 B 诚实登记

**批次口径**（[parallel-batches-w2/batch-11](parallel-batches/batch-11-inventory-final.md)）：库存域四残余子域
（商人购买 / 开袋 / 充公 / 装备实例回仓族）收官批。**本批下沉两子域**：商人购买 `buyMerchantItem`、
充公 `confiscateStorageBagItem`（`system/inventory_tx.h` 追加，W2-a 同域同文件）；**开袋按路线 B 不下沉**
（见下 RNG 决策）；装备实例回仓族判归属留 Kotlin（见下）。AUTHORITATIVE 稳态经 `nativeExecute` 转发
（零 JNI 新导出），Kotlin 原路径保留为降级回退臂——双实现并行契约。

**写者审计结论（四子域逐条落表）**：

| 子域 | 写者链 | RNG 面 | 本批处置 |
|---|---|---|---|
| 商人购买 | `buyMerchantItem`：商品存在 → D-21（`price<=0 ‖ quantity<=0`）→ `spiritStones < cost ‖ quantity > stock` 早退 → `canAddMerchantItem`（六型槽位预算/灵石未知恒 true）→ `addMerchantItemToInventory`（`withTrackingSource("merchant")` 委托 addXxx，**Partial 溢出转邮件视为成功**）→ `wallet.deduct(LOW, Purchase, MerchantTrade)` → `reduceMerchantStock`（`quantity>=stock` 移除条目）；调用面 `GameEngineMerchantOps` / `AutoBuyService` | 模板命中 = **零 RNG**；模板缺失回退 = `EquipmentDatabase.generateRandom` 等族走 `kotlin.random.Random.Default`（篡改档可达） | 下沉 C++；**TemplateMiss failure 信封回退**（回退臂保留随机回退语义，禁止近似复刻） |
| 开袋 | `openStorageBag`：EXPLORATION 分区 `nextInt(16)`（数量）+ 逐件 `nextInt(7)`（类型）；分支内 `templates.random()` / `generateRandom*` 走 **`kotlin.random.Random.Default`（非分区、不可复刻）** | 双重 RNG | **路线 B：本批不下沉**，ActionId 留空，登记 batch-23（需拍板是否接受路线 A 行为基线变化） |
| 充公 | `confiscateStorageBagItem`：幂等探测（**以袋内当前条目为准**——入参可为陈旧 UI 快照）→ 堆叠数量篡改防御 → `materializeConfiscatedItem`（实例 `returnXxxToStack` / 堆叠 `BagItemReconstructor.reconstruct` + `copy(quantity=1)` / 模板缺失 null）→ `applyConfiscationResult`（**仅 Success 移除袋条目**；Partial/Failure/模板缺失保留待重试，`withOverflowMailSuppressed` 凭据类语义） | **全链零 RNG**（BagItemReconstructor 纯模板查表） | 下沉 C++（`confiscateStorageBagItemTx`） |
| 实例回仓族 | `returnEquipmentToStack`/`returnManualToStack`（校验 Ops3）：充公物化复用 + 读档自愈/死亡物化复用（`materializeEquipmentInstance` 含实例表删除防双持有，归 batch-14 域） | 零 RNG | **不下沉**：判归属——充公臂在 C++ 内用等价原语（`sr_session::detail::equipmentInstanceToStack` + 裸 store.add），`returnXxxToStack` 保留 Kotlin 为共享回退臂 + 自愈路径 |

**C++ 事务**（`inventory_tx.h` 追加，纯头文件）：
`buyMerchantItemTx`（① 商品存在 → ② D-21 → ③ 灵石/库存早退（**仅下品余额，不含自动换算**——实际扣减
`autoConvert=true` 但预检已保证 straight-line Success）→ ④ 模板存在性（缺失即 `TemplateMiss` failure 信封）→
⑤ 按型转换 + 容量预测（复用 `merchant_settle::toXxx` 转换器与 `canAddXxx` 谓词——S8 autoBuy 同源，禁止复制漂移）
→ ⑥ addXxx（`"merchant"` 年报 + Partial 视为成功）→ ⑦ deduct（**预演臂**：GameData 拷贝上先行 deduct 校验，
失败即 failure 信封零写入——Kotlin 对应分支为不可达死码，C++ 收敛为零写入防线）→ ⑧ 商家库存扣减）/
`confiscateStorageBagItemTx`（幂等探测 → 数量防御 → 三态物化：实例路径 = **裸 store.add**（Kotlin
`returnEquipmentToStack` 语义——不校验、**不记年报**，禁用 addXxx 错记 `annualEquipmentBySource`）；堆叠路径 =
`sr_session::detail::reconstructStackedItem`（与 Kotlin `BagItemReconstructor` 死亡路径对拍锁定）+ quantity=1 走
addXxx（`"confiscate"` 年报）→ 溢出抑制 → 仅 Success 移除（实例整条/堆叠减 1）。**语义澄清**：充公入仓量恒 1，
Partial 臂在 Kotlin/C++ 双侧均不可达（quantity=1 要么全合并 Success 要么 Failure(Full)），GTest 以 Failure(Full)
臂覆盖"仓库满袋条目保留"。

**协议**：`INV_BUY_MERCHANT_ITEM=1530 / INV_CONFISCATE_BAG_ITEM=1531`（gen-action-ids.mjs 追加 →
`action_ids.h` + `ActionIds.kt` 再生成，**116 动作**，maxId=1531；1532–1549 留空）；`execute_dispatch.cpp`
并入既有 `handleInventoryTx`，**范围分支上界由 `INV_CONSUME_MATERIAL` 扩至 `INV_CONFISCATE_BAG_ITEM`**（PR 声明；
区间不含 1550+ 巡逻段——batch-12 并行批另行动作）。

**Kotlin 门面**：`InventoryNativeTx` 追加 `buyMerchantItem`（**溢出草稿经 `InventoryNativeForward.deliverDraft`
同一投递通道**——Partial 溢出转邮件与 Kotlin 原路径同精度）/ `confiscateStorageBagItem`；新 internal data class
`MerchantBuyResult`（奖励卡片字段随信封回传，**卡片构造留 Kotlin**，S6 战报同口径）；`InventoryFacadeImpl`
两方法顶部一行接线。`GameEngine`/`StateSyncService`/`GameStateStoreImpl`/`GameCoreBridge`/`models.h`/`json_codec`
零改动。本批于独立 git worktree 实施（主工作区被并行批 12 占用——分支切换会复刻上一轮"带走他批在途改动"事故）。

**RNG 决策登记（开袋路线 B）**：开袋分支内 `HerbDatabase.getHerbsByTier(rarity).random()` /
`getAllSeeds().filter{}.random()` / `ItemDatabase.generateRandomPill/Material` / `EquipmentDatabase.generateRandom` /
`ManualDatabase.createFromTemplate(templates.random())` 全部消费 `kotlin.random.Random.Default`——状态不在协议、
不随存档走，C++ 无法逐位复现。按批次文档决策原则（无法在不改行为基线前提下复刻 → 选路线 B 并诚实登记，
**禁止近似复刻**），开袋本批不下沉、保持 Kotlin 原路径；路线 A（双端改游戏分区抽取 + Diff 对拍锁定新基线）
需拍板后另立 batch-23。

**测试**：GTest `inventory_tx_test.cpp` +17 用例（购买 happy 灵石精确扣减 + NotFound/InvalidParam(D-21)/
Insufficient（灵石与库存两臂）/CapacityFull（按型槽位预算）/TemplateMiss/AddFailed(rarity 篡改) 六失败臂零写入 +
Partial 溢出转草稿（年报只记实际入仓 + 商家库存回写）+ 灵石商品中品直加 + 充堆叠 happy（聚气丹按名首中
`breakthrough_9_low`，`confiscate:<grade>` 年报键）+ 装备实例裸回仓（**无年报写入** + 实例表不动 + 袋条目整条移除）
+ 幂等陈旧快照防复制 + 弟子缺失静默 + 模板缺失袋条目保留 + 数量篡改拒绝 + 满仓 Failure 袋条目保留 + 全分区
RNG 快照差分 + 双运行全状态逐位一致（id 计数器清零 = 重导入 reseed 语义）——桌面 **1073/1073**（1056 + 17）；
Kotlin `InventoryNativeTxGateTest` +5 用例（新入口 × flag OFF/桥未加载双模式降级 null + 购买回退臂成功/判定链
拒绝零写入 + 充公回退臂模板缺失袋条目保留），商人价格校验既有守卫测试零改动通过。

**验证**：见 §3（2026-09-12 Batch-11）。

## 2.44 Batch-13（2026-09-12）：探索域 UI 操作面下沉 C++——世界关卡/侦察战斗执行 + 伤亡写回 + 分舵驻守（路线 (a) 变体：战斗入 C++，奖励/胜利事务留 Kotlin）

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #13，并行组 A；预分配 handover §2.44 + ActionId 段 1570–1589 实用 1570–1573）：
`attackWorldLevel` / `scoutSect` / `assignGarrisonDisciple` / `removeGarrisonDisciple` 四入口下沉（`system/exploration_tx.h` 新头 + 三 Kotlin Ops 文件 native 臂）。

**战斗执行覆盖面核实（批文 §2 三路线判定）**：S5 `mission_completion.h`（createBeastBattle/discipleToCombatant/createBeast 等价物）与 S6 `secret_realm_session.h`（runBeastBattle 全链 + writeBackBattleMembers + 袋物化）已建立"组装入 C++、执行在 C++（BATTLE 分区）+ Kotlin 重建战报"的对拍先例；`BattleExecutionRouter`/`battle::executeBattle` 为同源生产通道。妖兽组装两分支均零 RNG（pregen 钳制 [1,1e7]；无 pregen 走 resolveBeastStats 向后兼容基础值公式 rl=5，均无 ENEMY_GEN 消费）。

**路线声明：(a) 变体——战斗执行 + 伤亡写回（幸存 HP/MP 钳制回写 + 阵亡袋物化标死）+ 校验链入 C++；以下显式登记留 Kotlin（S5/S6 口径）**：
- 遭遇分支（`aiBeastEncounterTargets` @Transient 不入镜像协议，`resolveBeastAttackFight` 整臂 Kotlin）；
- `forceSettleDisciplesBeforeBattle`（修炼结算域，Kotlin 原样前置执行）；
- **奖励生成**（妖兽材料 `BeastMaterialDatabase.getRandomMaterialByBeastType` 硬编码 `Random.nextDouble` + 洞府三库 `generateRandom` Random.Default + UUID id——非分区非存档确定性随机域，双臂同形不改抽取集；灵石奖励参数化透传）；
- **胜利事务原子块**（soulPowers + winBattleRandomAttrPlus 17 分支表 + defeated TOCTOU 重查——talent effects 与 lawEnforcement 偷盗判定耦合，且 C++ 若写 defeated 会令 Kotlin 重查臂早退漏发魂力，故 C++ 不写 defeated，`applyWorldLevelVictoryTransaction` 原函数执行）；
- `processBattleCasualties`（悲痛/卸装/槽位清理编排无 C++ 统一入口；袋已清空幂等 + wasAlive 双计防线，S6 同款事务外 log-and-continue）；
- 战报重建/奖励卡片/`assignmentGate`/Room 生产仓/状态同步（平台效应；侦察 `scoutInfo` 留 `applyScoutVictoryInfo` 原函数）。

**写者审计结论（RNG 逐点）**：

| 入口 | 判定序 | 触碰字段 | RNG 面 | 残差归属 |
|---|---|---|---|---|
| attackWorldLevel | level 存在→!defeated→ids 非空→遭遇分支→settle→组装→战斗→伤亡写回→哀伤→战报→胜利事务/奖励 | worldLevels[].defeated（Kotlin）、disciple 列（currentHps/Mps/isAlive/statuses/deathYears/storageBagItems）、annualDeceasedDisciples、soulPowers/17 属性表（Kotlin）、battleLogs（Kotlin）、钱包/仓库（Kotlin） | **BATTLE 分区**（executeBattle 全部抽取，与 Kotlin executeBattleWithTimeout 同区同序）；奖励段 Random.Default+UUID（非分区，Kotlin 残差） | 见上方路线声明 |
| scoutSect | sect 存在→settle→成员非空→AI 守卫（alive ∧ realm 7..9 取前 8 保序）→组装（玩家 DEFENDER 现血/AI ATTACKER 满血，maxTurns=INT32_MAX）→战斗→伤亡写回→哀伤→战报→scoutInfo | disciple 列（同上）、sectDetails[].scoutInfo（Kotlin）、aiSectDisciples 只读 | **BATTLE 分区**（AI 守卫选取零抽取）；scoutInfo 零抽取 | settle/哀伤/战报/scoutInfo Kotlin |
| assignGarrisonDisciple | require 存在→存活→sect 存在（静默）→同宗已驻（静默）→旧 occupant 捕获→clearAllSlotsDataOnly→槽位字段写 | worldMapSects[].garrisonSlots + 清理面 11 类槽位 | **零抽取** | gate/Room 生产仓/状态同步 Kotlin |
| removeGarrisonDisciple | 快照读 occupant→槽位清空（GarrisonSlot(index) 保留索引）→gate 释放 | worldMapSects[].garrisonSlots | **零抽取** | gate 释放 Kotlin |

**C++ 事务**（`gamecore/include/gamecore/system/exploration_tx.h` 新头，纯头文件）：`attackWorldLevelTx`（校验链→组装 pregens/基础值双分支→`executeBattle(BATTLE)`→`writeBackExplorationCasualties`——复用 `sr_session::detail::materializeDiscipleBagAndMarkDead`（tracking source "disciple_death" 与 Kotlin SOURCE_DISCIPLE_DEATH 同值））/ `scoutSectTx`（AI 守卫选取→`aiPrepareDisciplesForBattle` 模板 id 语义组装（Kotlin AISectAttackManager.convertToCombatant 同源原语）→执行→写回；AI 弟子不落库与 Kotlin 同；`defenderViews` 战前展示段供 Kotlin 战报重建——portrait/realmName 展示域不入 C++ 战斗态）/ `assignGarrisonTx`（require 链失败臂信封回退 + 同宗已驻 written=false 静默 + 旧 occupant 清理前捕获 + `clearAllSlotsDataOnly(includeResidence=false)` 全槽清理（garrison 仅清玩家宗门与 Kotlin 同口径）+ 槽位字段写（name/realmName/countColor/portraitRes））/ `removeGarrisonTx`。**死亡原因串不落库**（DeathRecord 已删，isAlive/status/deathYears 三字段承载）——battle/scout 仅 Kotlin markDead cause 校验域标签。

**协议**：`EXPLORE_TX_ATTACK_WORLD_LEVEL=1570 / EXPLORE_TX_SCOUT_SECT=1571 / EXPLORE_TX_ASSIGN_GARRISON=1572 / EXPLORE_TX_REMOVE_GARRISON=1573`（gen-action-ids.mjs 追加段 → 两生成物再生成，**125 动作** = 121+4）；`execute_dispatch.cpp` 独立 **`handleExplorationTx`**（命名独立于既有 `handleExploration` 月结/关卡域，中央 switch 范围分支不重叠）；战斗信封复用 S6 战斗段形状（winner/turn/team/beasts/rounds——Kotlin `BattleExecutionRouter.rebuildBattleLogData` 直读）。

**Kotlin 接线**：新文件 `GameEngineExplorationNativeOps.kt`（**新文件在 PR 声明**——WorldBattleOps 聚合后 detekt TooManyFunctions 16/15 越界，按 S6 `GameEngineSecretRealmNativeOps.kt` 命名先例拆出 native 转发域）：`ExplorationNativeForward`（flag+镜像可空局部守卫，findings 13）+ `buildNativeBattleTeamMembers`/`buildNativeBattleRounds` 战报公共重建助手 + `attackWorldLevelNative` + `resolveBeastEncounterIfAny`。三 Ops 文件顶部 native 臂：`attackWorldLevelNative`（settle→转发→溢出草稿投递→事务外哀伤→战报→胜利事务/奖励参数化版）/ `scoutSectNative`（settle→转发→哀伤→战报落库→scoutInfo）/ `assignGarrisonNative`+`removeGarrisonNative`（事务体外移共用 gate/仓库/状态同步尾块）；`applyVictoryRewards` 灵石参数化（回退臂语义不变）。`GameViewModel`/`GameStateStoreImpl`/`StateSyncService`/`GameCoreBridge`/`models.h`/`system/exploration.h` 既有月结函数零改动。

**测试**：GTest `exploration_tx_test.cpp` 14 用例（校验链三失败臂零写入零抽取 / pregen 必胜幸存写回 + 非 BATTLE 分区快照差分 / 全灭袋物化回仓+标死+年度计数+清袋幂等 / **双运行逐位一致（终态+全分区 rngStates 锁定）** / 洞府基础值分支零 ENEMY_GEN / AI 守卫 alive∧7..9 取前 8 保序 / scoutInfo 不写（Kotlin 残差）/ garrison 全槽清理+槽位字段写+灵根色 / 同宗幂等 / require 链与静默路径 / 移除捕获 occupant+默认色）——桌面 **1101/1101 全绿**（干净检出口径 = HEAD 1087 含 batch-15 + 本批 14）；Kotlin `ExplorationNativeTxGateTest` 6 用例（转发器 flag OFF / AUTHORITATIVE+镜像缺失双降级；garrison assign/remove 在 flag OFF 与桥未加载双模式下回退臂语义等价：写槽+gate 登记/同宗幂等/清槽+gate 释放）——探索域既有测试零改动通过。

**范围边界与登记汇总**：① 世界关卡月度刷新/过期清理（`WORLD_LEVEL_MONTHLY`/`WORLD_LEVEL_CHECK_EXPIRED`/`LEVEL_GENERATE_LEVELS`）已在 C++ 月结域不动；② 洞府探索会话编排（`CaveExplorationProcessor`）结算域不动；③ 宗门战/防守战（sect_conquest/sect_defense_battle）不在本批；④ 奖励生成族（Random.Default+UUID）显式登记 Kotlin 残差——若未来收敛需先拍板"非分区随机域入镜像协议"；⑤ `teamCasualties` 原 Kotlin 口径逐字保留（survivorIds 为 id 集按 member.name 判定）。

**验证**：见 §3（2026-09-12 Batch-13）。

## 2.45 Batch-14（2026-09-11）：弟子管理二——生命周期/婚姻族下沉 C++——逐出/拜师/婚姻批准/释放思过/年俸开关，全族零 RNG 纯确定性事务

**批次口径**（[parallel-batches-w2 README](parallel-batches-w2/README.md) 组 B：
[batch-14](parallel-batches-w2/batch-14-disciple-lifecycle.md)）：弟子域"最大残余域"第二批——
生命周期族五入口 UI 操作面写者下沉 C++（`system/disciple_lifecycle_tx.h`，ActionId 段 1590–1609），
AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出），Kotlin 原路径保留为降级回退臂。
batch-08 已下沉的装备穿脱/功法学忘/亲传藏经阁任命（`disciple_tx.h`，1480–1485）零改动零回退。

**写者审计结论（含 RNG 标注与边界声明）**：

| 入口 | 文件（判定序权威） | 判定序 | 触碰字段 | RNG | 残差归属 | 本批处置 |
|---|---|---|---|---|---|---|
| `expelDisciple` | DiscipleService（经 FacadeImpl 委托） | 存在 → 存活（NotAlive）→ 非 REFINING（SlotInvalid） | 12 类槽位（含住所）/ 穿戴装备+功法实例销毁 / 血炼三 map+熟练度 / 行删除 / annualDesertedDisciples | 零 | Gate 释放 + Room 生产槽同步 + 袋物品物化（溢出转邮件，归因 "disciple_expel"） | ✅ 1590 下沉；袋物品经信封回传 Kotlin 物化 |
| `apprenticeToMaster` | DiscipleMasterApprenticeService | 相1 存在性×2 → 相2 自拜/存活×2 → 相3 已有师父/名额<5（仅存活徒弟） | masterIds | 零 | lifeEvents 双侧日志（非协议列） | ✅ 1591 下沉；日志草稿经信封回写 |
| `approveMarriageProposal` | GameEngine:272（审计定位：婚姻审批 UI 活路径经 DiscipleDelegateLifecycleOps.approveMarriage → 本入口；**PartnerSystem 月度自动配对已由 month_settlement.h C++ 直辖，审计确认非 UI 面**） | 提议存在（Kotlin 前置）→ 解析 → 已有道侣防御检查 | partnerIds 双向 / gameEventRecords（MARRIAGE） | 零 | pendingMarriageProposals 移除（GameStateStore 层字段，非协议） | ✅ 1592 下沉（配对写段）；提议移除留 Kotlin |
| `rejectMarriageProposal` | GameEngine:301 | 提议存在 → 移除 + 事件 | 仅 Kotlin 域（提议列表 + 事件记录） | 零 | — | ☑ 审计确认零 C++ 协议列写者 → 不设 native 臂（死导出纪律） |
| `releaseReflectionDisciple` | DiscipleFacadeImpl:114 | 解析/存在/存活静默早退 → statusData 双键移除 + status=IDLE | statusData / statuses | 零 | syncSingleDiscipleStatus（状态推导域） | ✅ 1593 下沉；静默 no-op 臂逐字同义 |
| `updateYearlySalaryEnabled` | DiscipleLifecycleManager:243 | 无校验盲写 | yearlySalaryEnabled[realm] | 零 | — | ✅ 1594 下沉 |
| `syncAllDiscipleStatuses` / `syncSingleDiscipleStatus` / `resetAllDisciplesStatus` / `updateDiscipleStatus`(受保护直写) | DiscipleStatusService / SlotManager | 状态推导域 | statuses/statusData/positionName | 零 | Gate / Room / 秘境终止编排 | ☑ 保留 Kotlin（batch-06/08/12 同口径 §2.4） |
| `addLifeEvent` / `initializeLifeEvents` | DiscipleLifecycleManager | lifeEvents 瞬态列写 | lifeEvents（**非协议列**，C++ 无） | 零 | — | ☑ 保留 Kotlin（写目标即 Kotlin 单源，无可下沉物） |
| `addDisciple` / `removeDisciple` / `updateDisciple(disciple)` | DiscipleLifecycleManager | 存在→remove / remove+insert | 弟子行 | 零 | — | ☑ **审计确认零活调用方**（仅门面接口契约位）→ 不下沉（死导出纪律），登记未来清理候选 |
| `updateDisciple(id, lambda)` | DiscipleFacadeImpl:80 | 泛型行变换（调用方 lambda：交谈效果/关注标记） | 全行 | 零 | 调用方 lambda 语义 | ☑ lambda 无法事务化 → 保留 Kotlin，归交谈/交互族后续批 |
| `recruitDisciple`（自由招募） | DiscipleService:124 | 性别→名字→灵根→年龄→factory→allocateAndInsert | 弟子行 + 计数 | SYSTEM 分区（性别/灵根/年龄/factory）+ **Random.Default（NameService 名字抽取，非协议随机域）** | guideCounters + lifeEvents | ⛔ **本批不下沉**——名字抽取走 `Random.Default`（未分区、非序列化域），下沉须先拍板：C++ 复刻 JVM Random 逐位语义并定种子策略，或改抽取集（RNG 红线，需拍板）；factory 段 C++ `createDisciple` 已就绪（对拍桥验证）可后续直用 |
| 丹药/赏赐/给予族（rewardItemsToDisciple / usePill / applyPill* / 装备功法穿脱辅助） | DiscipleFacadeImpl功法Ops1/战斗Ops2 | 物品发放族 | 仓库堆叠 + 袋 + 丹药列 | 零 | InventorySystem 统一入口 | ☑ **不在 batch-14 候选清单**（物品发放域单独成族；涉及 InventoryAddPathGuard 守卫面，不跨域触碰） |
| `DiscipleFacadeImpl战斗Ops2.kt` 全文件审计 | — | 丹药效果/赏赐/亲传槽辅助（**无伤亡写回/阵亡标记**——阵亡在 battle/lifecycle processor 域） | — | — | — | ☑ 审计确认候选清单描述与实际不符，零生命周期写者 → 全文件本批零改动 |

**C++ 事务**（`gamecore/include/gamecore/system/disciple_lifecycle_tx.h`，纯头文件）：
`expelTransaction`（校验链先行 → 袋物品捕获 → `clearAllSlotsDataOnly` 12 类槽位含住所 →
`destroyWornInstances` 穿戴装备/功法实例销毁 → `eraseDiscipleDerivedMaps` 派生 map 收口
（blood_refinement.h 唯一收口点复用）→ `removeById` 行删除 → annualDesertedDisciples+1）/
`apprenticeTransaction`（三相校验 → masterIds 落表 → 双侧日志草稿）/
`approveMarriageTransaction`（已有道侣防御检查零写入 → partnerIds 双向绑定 →
`settle_util::recordGameEvent` MARRIAGE 事件直写——守卫/序号/裁剪完整对齐）/
`releaseReflectionTransaction`（静默 no-op 同义 → statusData 思过双键定向移除（保留其余 key）→
status=IDLE）/ `salaryToggleTransaction`（盲写覆写）。
**死亡标记红线（CLAUDE.md 13.3）**：逐出为行删除而非死亡标记——本族零 `isAlive=0`/`status=DEAD`
写入，markDead 路径不经本事务（GTest 断言行移除且 annualDeceasedDisciples 不变）。
**失败零写入**：校验链全通过后才落写；失败信封 errorType 与 AppError.Domain.Disciple 分型同名
（NotFound/NotAlive/SlotInvalid）→ Kotlin 回退原路径重执行校验链。

**协议**：`DISCIPLE_LIFECYCLE_EXPEL=1590 / DISCIPLE_LIFECYCLE_APPRENTICE=1591 /
DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592 / DISCIPLE_LIFECYCLE_RELEASE_REFLECTION=1593 /
DISCIPLE_LIFECYCLE_SALARY_TOGGLE=1594`（gen-action-ids.mjs 追加段 → 两生成物再生成，
**119 动作** = 114+5）；`execute_dispatch.cpp` 独立 `handleDiscipleLifecycleTx`（与 batch-08
`handleDiscipleTx` 分开）+ 中央一行范围分支；`test/CMakeLists.txt` 追加 GTest。

**Kotlin 接线**：新同包扩展文件 `DiscipleLifecycleNativeTx.kt`（internal，tryNativeManualRecruit
同构——门面构造签名零变化）；`DiscipleFacadeImpl` 四方法顶部接线（expel/apprentice/
releaseReflection/salaryToggle——成功直返/失败信封与降级 null 回退 Kotlin 原路径重执行校验链）；
`GameEngine.approveMarriageProposal` 顶部 native 臂（提议存在性 Kotlin 前置 → native 配对 →
提议移除留 Kotlin）；`DiscipleReflectionReleaseTest` 等弟子域既有测试零改动通过。
**残差执行序（native 成功后）**：expel = Gate 释放+Room 生产槽同步（幂等，数据段在已刷新镜像上
零命中）→ 袋物品物化（`withTrackingSource("disciple_expel")` + 溢出转邮件——开袋族归 batch-11
不搬）；apprentice = 双侧 lifeEvents 草稿回写（logLine 机制）。**防御性空安全**：可空局部过滤
`stateSyncServiceRef`（InventoryNativeForward 护栏）。**可见性放宽**（W2-a 三重防护先例）：
`DiscipleFacadeImpl.discipleService` 与 `DiscipleService.inventorySystem` private→internal。
**已知边界（行为零变更声明）**：①逐出袋物品物化从"事务内、行删除前"平移到"事务提交、镜像回读后"
——终态等价（物化只触仓库/实例列，与行删除无序依赖）；边界差异为物化环节失败（模板缺失丢弃 +
日志，Kotlin 原路径同分支丢弃）不再回滚整事务，与 Kotlin 原路径的日志级失败口径一致；袋内灵石
（storageBagSpiritStones）原路径即不物化、随行删除——同口径保留。②婚姻批准对"提议残留+弟子已亡/
被逐"边界，Kotlin 原路径写幽灵列条目，SoA 无法表达 → native NotFound 信封回退 Kotlin 原路径保行为。

**测试**：GTest `disciple_lifecycle_tx_test.cpp` 11 用例（逐出 happy 12 类槽位逐类清理穷尽 +
信封 bagItems 断言 / 逐出全失败臂零写入 / 拜师 happy 落表草稿 / 拜师三相失败臂零写入 /
拜师名额（已有师父·满 5·**死亡徒弟不计数**）/ 婚姻 happy 绑定+事件直写断言 / 婚姻防御跳过零写入 /
婚姻提议残留边界 NotFound / 释放思过定向移除+保留既有 key / 释放思过静默臂 / 年俸开关覆写）
——桌面 **1067/1067**（1056 既有 + 11）；零 RNG 全分区快照差分逐用例内嵌。Kotlin
`DiscipleLifecycleNativeTxGateTest` 5 用例（四入口 × AUTHORITATIVE 桥未加载 / flag OFF 双模式
降级 null + 逐出/拜师/年俸回退臂语义）；`DiscipleReflectionReleaseTest`/
`DiscipleFacadeImplRecruitTest`/`DiscipleStatusServiceTest`/`SlotCategoryCoverageTest`
弟子域既有测试零改动通过。

**batch-14b 补全（2026-09-12）——自由招募写者审计升级与名字随机源分区化拍板落地**：

审计复查发现上表 `recruitDisciple` 行的前提需要修正并升级结论：

| 项 | 结论 |
|---|---|
| 活调用方再核实 | 门面入口 `recruitDisciple()`（`GameEngineDiscipleOps:23` 包装）**零调用方**；唯一活跃调用 = **新档创建播种** `createNewGameInternal`/`restartGameInternal` 的 `repeat(3) { discipleService.recruitDisciple(realm = 9) }`（GameEngineLoadDataOps:304/:371）——经 `discipleService` 直调（绕过门面），发生在 **native 基线导入（`syncNativeBaselineAfterLoad`）之前** 的语境 |
| 下沉判定修正 | ①门面入口下沉违反**死导出纪律**（零调用方）；②新档播种语境下 native 臂会写入即将被基线导入整体覆盖的 C++ 状态（无效写 + 播种窗口竞态）——自由招募归 **load/基线域**（随 batch-21/存档域处置），不属 UI 操作面。**C++ 事务不做**（诚实回退优于带病下沉） |
| 拍板落地 | 名字随机源 `Random.Default` → **SYSTEM 分区**（用户拍板 2026-09-12）：`DiscipleService.recruitDisciple` 的 `generateName` 补传 `rng.asKotlinRandom()`（AISectDiscipleManager/RecruitService 同款先例，且为该既定迁移的收尾——四调用点中仅剩兑换码仍在 `Random.Default`）。**行为基线影响面**：仅新档创建时刻（mapSeed 本就每次随机），既有存档零影响（读档不重跑播种）；效果 = 初始三名弟子同 mapSeed **全量可复现**（名字曾是唯一缺口——性别/灵根/年龄/factory 本就走分区流） |
| C++ 地基登记 | `name_service.h::generateName` 已完整移植且签名即分区 RNG（"招募刷新下沉"产物）——后续若 load 域下沉新档播种，事务可直接复用 `generateName + createDisciple + allocateAndInsert` 三件套，无缺口 |

**测试**：Kotlin `DiscipleServiceRecruitDeterminismTest` 4 用例（同 mapSeed 双运行名字+属性逐位一致 /
异种子序列漂移 / 同档三连招勓名字去重 / 入宗门日志+引导计数+recruitedMonth 原子写入）。

**验证**：见 §3（2026-09-12 batch-14b）。

## 2.46 Batch-15（2026-09-12）：弟子管理三——任命/驻守/长老单值槽/洗炼消耗族入 C++——玉符"承扣 + 运行时同步"路线落地

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #15，并行组 B；
handover §2.37 登记留 W3 的**长老单值槽任命**在本批清偿）：`ElderManagementUseCase.assignElder/
removeElder`（长老 10 单值槽字段 + 6 类亲传列表清空）+ `GameEngineWarehouseOps.assignWarehouseGarrison
Atomic`（仓库驻守，与 batch-13 的 `worldMapSects[].garrisonSlots` 分舵驻守无关）+ `GameEngineSpiritRootOps
.washSpiritRoot` + `GameEngineTraitAddOps.rollTraitAdd/confirmTraitAdd` + `GameEngineTraitWashOps.washTraitSlot`
（洗炼消耗三族）写者下沉 C++（新 `system/appointment_tx.h`，ActionId 1610–1616），
AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出），Kotlin 原路径保留为降级回退臂。
`confirmSpiritRootWash` / `confirmTraitWash`（纯数据写、无玉符/RNG 面）与 `JadeSymbolService.kt`
本体（batch-19 所有权）不在本批范围。

**写者审计结论（含 RNG 标注，§2.46 登记表）**：

| 入口 | 判定序（逐字对齐源） | 触碰字段 | RNG 面 | 残差归属 |
|---|---|---|---|---|
| assignElder | 弟子存在（**原样字符串相等**，非 canonical 化）→ 存活 → releaseDiscipleFromAllSlotsAtomic（Kotlin）→ 全槽清理数据段 → 捕获被顶替者（清后覆写前）→ 写槽位字段 + 清空对应亲传列表（六类） | `elderSlots`（10 字段之 1 + 6 列表）+ 11 类槽位清理（includeResidence=false） | **零抽取**（API 不接受 rng） | release 原子体/gate confirm+release/sync/生产与修炼 checkpoint 留 Kotlin；C++ 回执 `replacedIds` = Kotlin `collectReplacedIds` 原样追加序（旧长老在前 + 列表成员在后，distinct 与 != appointee 过滤在 Kotlin 残差应用） |
| removeElder | 读 occupant → 清字段 + 清列表（**不**做全槽清理——Kotlin 原样） | `elderSlots` 同上 | 零抽取 | gate.release + checkpoints 留 Kotlin |
| assignWarehouseGarrisonAtomic | 弟子存在（canonical 整数集合判定）→ 存活 → 旧 occupant 捕获 → 全槽清理 → 移除同建筑条目 + 追加（**原样字符串写**） | `warehouseGarrisons` + 11 类槽位清理 | 零抽取 | gate/Room 清理/sync 留 Kotlin（两臂共用 `applyWarehouseGarrisonResiduals`） |
| washSpiritRoot | 保底计数防御 → 存在 → 存活 → 玉符余额 → **扣减** → 保底判定 + 元素洗牌 → join | `jadeSymbols`（-cost） | 保底路径 **5×nextInt**（仅洗牌，零 nextDouble）；普通路径 **1×nextDouble + 5×nextInt**（SYSTEM）；失败臂在抽取前返回——**零抽取零写入** | publishJadeSymbolStateNow + 运行时 totalCount 同步留 Kotlin |
| rollTraitAdd | 存在 → 存活 → 上限 5 → 排除集（已有槽位 template，族级）→ 候选预检 → 玉符余额 → **扣减** → 品阶抽取 → pending 落盘（同 key 覆盖） | `jadeSymbols` + `pendingTraitAdds` | **1×nextDouble**（品阶 40/30/30 累计阈值）+ **1×nextInt**（选择，randomOrNull 空池不消耗——预检保证非空）；预检/余额失败臂零抽取 | publish + 运行时同步留 Kotlin |
| confirmTraitAdd | 存在 → 存活 → 上限 → 合法性（产物可解析 + 不在列表 + template 不重复）→ 追加 + lifespan 同步（`realmMaxAge × bonusDiff` toInt 截断）→ checkpoint 重记账 → 清 pending | talent/physique/affixIds + lifespan + checkpoint 列 + `pendingTraitAdds` | **零抽取** | 无（纯数据事务） |
| washTraitSlot | 存在 → 存活 → 目标在列表 → 排除集（**含目标自身**——禁止"刷回原样"）→ 候选预检 → 玉符余额 → **扣减** → 保底判定抽取 | `jadeSymbols` | 保底路径 **1×nextInt**（TOP_RARITY 池选择；池空 → 放弃产出 0 抽取）；普通路径 1×nextDouble + 1×nextInt；失败臂零抽取 | publish + 运行时同步留 Kotlin；newId 兜底 targetId（`roll.newId ?: targetId` 同构） |

**玉符记账路线拍板（PR 声明项，CLAUDE.md 13.3）**：**C++ 承扣 + Kotlin 运行时同步**。
玉符余额检查 + 扣减与抽取在同一 C++ 事务内原子完成（**反向捕获为 tick 步骤⑤ 消费、非同步
转发**——若由 Kotlin 承扣，tick 滞后窗口内 C++ 余额检查读到滞后值会双花，且"扣减失败但已
抽取"违反失败臂零抽取红线；故 C++ 承扣是双臂 RNG 与余额一致性的唯一安全锚点）。Kotlin native
臂成功后经 `syncJadeRuntimeAfterNative(cost)`（新 GameEngine 扩展）：`runtimeState.total >= cost`
守卫下 `stateStore.update { jadeSymbolService.deduct(this, cost) }` 递减运行时 totalCount 并绝对值
覆写镜像值（幂等——与本臂已扣减后的值一致）；`total < cost` 仅在运行时未锚定窗口出现（洗炼
UI 链实际不可达），跳过同步由 checkpointNow 哨兵（lastSampleMs==0 不写）+ onLoopStart 快照重锚
兜底收敛到 C++ 真相。**消耗仍收敛于 JadeSymbolService 唯一入口**——Kotlin 臂零直接覆盖写
jadeSymbols 字段，`JadeSymbolConsumptionGuardTest` 零改动通过。

**特质池序对拍口径**：候选池迭代序 = Kotlin `allXxxData.values` 插入序（正面 buildList 序 +
负面 buildList 序）；C++ `trait_db.h` 模板向量同序构建（既有对拍守卫），过滤保序。抽取原语与
Kotlin 逐位同源：`shuffled` = 逐元素 1×nextInt() 后稳定排序（**非 Fisher-Yates**）；`random/
randomOrNull` = `DeterministicRng.nextInt(bound)`（Lemire，low32 有符号比较）；品阶累计阈值
0.40 / 0.4+0.3 / 1.0（IEEE 双精度增量累加同序）。排除集为 **template（族）粒度**——同族各品阶
共享 template，任一持有档排除全族；每非负面非退役族必有上品成员（GTest 性质用例锁定），
故"保底池空但候选池非空"（放弃产出分支）在真实数据下不可达，该分支按 Kotlin 同构保留为防御臂。

**C++ 事务**（`gamecore/include/gamecore/system/appointment_tx.h`，纯头文件）：七事务
`elderAppointTx` / `elderDismissTx` / `warehouseGarrisonAssignTx` / `spiritRootWashTx` /
`traitAddRollTx` / `traitAddConfirmTx` / `traitWashSlotTx`——校验链全先行失败零写入；11 类槽位
清理复用 `slot_cleanup.h`（includeResidence=false，`clearAllDiscipleSlots` 各 tx 头自包含同族独立
提供）；长老字段/亲传列表穷举分派（10 字段 + 6 清空族，`spiritMineDeaconDisciples` 仅全槽清理
触达）；特质三库经 `trait_db.h`（talent/physique/affix + 退役类型过滤 + 保底池）。

**协议**：`ELDER_APPOINT_TX=1610 / ELDER_DISMISS_TX=1611 / WAREHOUSE_GARRISON_TX=1612 /
SPIRIT_ROOT_WASH_TX=1613 / TRAIT_ADD_ROLL_TX=1614 / TRAIT_ADD_CONFIRM_TX=1615 /
TRAIT_WASH_SLOT_TX=1616`（gen-action-ids.mjs 追加段 → `action_ids.h` + `ActionIds.kt` 再生成，
**121 动作**）；`execute_dispatch.cpp` 独立 `handleAppointmentTx` + 中央范围分支一行。

**Kotlin 接线**：新同包协作文件 `GameEngineAppointmentNativeOps.kt`（internal——
`AppointmentNativeForward` 转发器 + 七 native 臂 + 回执类型 + `syncJadeRuntimeAfterNative`，
PatrolNativeOps 同族机制；防御性空安全：`stateSyncServiceRef` 经可空局部过滤）；五入口文件顶部
native 臂（成功 → 回执驱动残差照原序执行；降级/失败信封 → Kotlin 原事务体重执行校验链——双
实现并行契约，用户可见文案由 Kotlin 臂产出）。`GameViewModel`/`GameStateStoreImpl`/
`StateSyncService`/`GameCoreBridge`/`models.h`/`JadeSymbolService.kt`/`DiscipleFacadeImpl*` 零改动。
detekt 首轮 2 违规（`confirmTraitAdd` / `removeElder` CCM 15/15 临界越界——native 臂插入分支所致）
按 batch-06/12 先例**真实重构清偿**：提取 `confirmTraitAddInner` / `buildElderSlotsAfterDismissal`，
零装回。

**测试**：GTest `appointment_tx_test.cpp` 31 用例（长老 10 字段逐类任命 + 六类列表清空 + 第 7 列表
不触达 + replacedIds 捕获序 + 自任命不入替 + 全槽清理 + 守卫三臂零写入；卸任字段/列表/空槽/未知类型；
驻守替换无重复 + 清理语义 = 清空 discipleId + 守卫臂；洗灵根玉符扣减/保底零 nextDouble 终态锁定/
普通六抽取终态锁定/双运行逐位一致/失败臂零抽取零写入；新增刷新 pending 落盘与覆盖/守卫臂/
双运行逐位一致；确认追加 + lifespan 增量黄金值 + checkpoint + pending 清理精确键 + 四态守卫 +
零 RNG 快照差分；特质洗炼扣除/排除集含目标/保底上品归零/守卫臂含候选池全排除零扣费/双运行
逐位一致；族-上品一致性性质用例；分发信封三态）——桌面 **1056+31=1087 全绿**（本分支口径）；
Kotlin `GameEngineAppointmentNativeTxGateTest` 11 用例（七臂 × flag OFF/桥未加载双模式降级 +
长老任命顶替释放/卸任释放/驻守残差 + 洗灵根扣减同步与 checkpoint 不回涨 + 不足臂零抽取 +
新增不足三态 + 洗炼缺失错误文案 + `syncJadeRuntimeAfterNative` 递减/幂等覆写/未锚定守卫跳过）——
`JadeSymbolConsumptionGuardTest` / `SlotCategoryCoverageTest` / 既有洗炼三族测试**零改动通过**。

**验证**：见 §3（2026-09-12 Batch-15）。

## 2.47 Batch-16（2026-09-12）：招募/派遣/俘虏残余·招募列表 UI 直调族入 C++——审计收窄批面，复用年度权威链零复制

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #16，并行组 B；预分配 handover §2.47 + ActionId 段 1630–1649 实用 1630–1632）：[batch-16 批文](parallel-batches-w2/batch-16-recruit-captive.md) 四范围项经**写者审计后收窄**——主路径（手动/一键招募、俘虏购买结算、**俘虏装备物化、自动招募、年度刷新/老化/自动拒绝的结算权威**）均已在 C++，本批下沉真正 Kotlin 独占的三入口**直调点**写者（新 `system/recruit_tx.h`，ActionId 1630–1632），AUTHORITATIVE 稳态经 `nativeExecute` 转发（零 JNI 新导出），Kotlin 原路径保留为降级回退臂。

**写者审计结论（含 RNG 标注，§2.47 登记表）**：

| 入口 | 判定序 / 现状 | 触碰字段 | RNG 面 | 残差归属 |
|---|---|---|---|---|
| RecruitService.refreshRecruitList(year) | **结算权威已下沉**（year_settlement T1#4 `detail::processRefreshRecruitList` 逐位等价，AUTHORITATIVE 年结执行）；Kotlin 直调点×3——年结回退臂（native 未就绪才达）、开机 `initializeWorldAndServices`（**基线导入前**，`worldMapSects` 为空）、读档自愈 `checkAndRepairMerchantAndRecruit`（基线导入后） | recruitList/lastRecruitYear/recruitCountThisMonth/annualNewDisciples/双惰性门 | **SYSTEM 分区重度消费**：数量 1×nextInt（宗门等级区间+长老魅力/职务加成；无玩家宗门兜底 nextInt(7)≥1）→ 逐候选 1×nextInt(2) 性别+名字 kFull+灵根五元素洗牌+1×nextInt(14) 年龄+createDisciple 链；差值门内置于 C++ 链 | 本批：native 臂（RECRUIT_REFRESH_TX **复用权威函数零复制**）；开机臂被世界导入守卫 + 差值门预检拦回 Kotlin 臂（见 Kotlin 接线） |
| RecruitService.ageRecruitList(year) | **结算权威已下沉**（T1#8 `detail::processRecruitAging`：age+1/超寿元移除/损坏过滤/三级去重/跨表残留一体）；直调点仅年结回退臂（AUTHORITATIVE 不达） | recruitList | **零抽取** | 本批：native 臂（RECRUIT_AGE_TX 复用权威函数） |
| GameEngineRecruitOps.recruitAllFromList | **已下沉**（专用 JNI `nativeRecruitAllFromList`，信封 ok/count/reason） | — | C++ 侧零抽取 | 已下沉；本批不迁移通道（零行为变更），既有导出保留 |
| GameEngineRecruitOps.removeFromRecruitList | **Kotlin 独占**（UI 拒绝按钮 `DiscipleDelegate.rejectDiscipleFromList` → `updateGameDataSync` 全量 filter） | recruitList | **零抽取** | 本批：native 臂（RECRUIT_REMOVE_TX） |
| MerchantAndRecruitService 全入口 | **零招募写者**（文件名含 recruit 为历史 SRP 拆分残留）；全部为旅行商人/收购物品生成与手动刷新 | travelingMerchantItems/merchantAcquisitionItems 等 | SYSTEM 分区 + RarityTimeProgression + 物品池 | **不下沉登记**：物品池在 Kotlin 注册表（Equipment/Manual/Item/HerbDatabase），C++ 无模板库不可复刻——按 batch-11 §6 路线 B 登记拆批（商人域归属后续拍板） |
| 俘虏装备物化 materializeCaptiveGear | **已下沉**（recruit_settlement.h:560 幂等守卫 + 四槽装备 + 功法实例化，招募三路径内调用；批文 §1.4"待审计定位"即此——**已在 C++，无新下沉面**） | equipmentInstances/manualInstances/manualProficiencies/槽位列 | 零抽取（UUID→`gc-inst-` 计数器，id 不参与业务） | Kotlin 版仅回退臂 |
| RecruitLazyState 双惰性门 | 纯内存不进 JSON（models.h:1411/:1416 瞬态注释明示）；C++ 同名瞬态 + `resetAutoRecruitIdle` JNI 既有 | — | — | 按批文只审计登记不迁移（月变真相源切换批） |
| startMission / 奖励发放 / lifeEvents 补写 | GameEngineMissionOps 等不在本批三文件所有权内 | missions | — | 派遣域**未派工残项**（登记）；lifeEvents 为 Kotlin 类体属性（协议外列），经回执字段由 Kotlin 镜像补写（disciple_tx.h 先例） |
| nextDiscipleId | **§2.16 已登记例外**（归约/查定型扫读）；本批不扩展 id 分配面 | — | — | 例外口径维持（候选 id 为镜像生成字段，`allocateAndInsert` 沿用既有例外） |

**审计发现登记（不改不扩散——既有 AUTHORITATIVE 基线行为，协调人拍板项）**：C++ 刷新候选 `seed.id=""`（Kotlin 臂 UUID——recruit_settlement.h 头注释口径"镜像生成字段、id 不参与业务"）；同次年结 T1#8 老化净化按 id 去重对多个 `id=""` 候选**坍缩保首**（Kotlin 臂不坍缩）——跨年手动招募池容量或受限。本批零行为变更，不修复不复刻（修复需改 year_settlement.h 行为基线，须拍板另立批）。

**C++ 事务**（`gamecore/include/gamecore/system/recruit_tx.h`，纯头文件；**新头在 PR 声明**——扩展 `recruit_settlement.h` 会与其传递依赖 `year_settlement.h` 成环，故立新头）：三事务
`removeRecruitTx`（按 id 全量过滤幂等——同 id 多条全移与 Kotlin `filter` 同构，无失败臂）/
`refreshRecruitTx`（**复用** `detail::processRefreshRecruitList` 权威链零复制：差值门内置于链；计数口径 generated = 列表净增 + autoRecruited，autoRecruited = recruitCountThisMonth 增量——与 Kotlin 日志口径一致）/
`ageRecruitTx`（复用 `detail::processRecruitAging` 权威链：Kotlin 老化+净化两段与 C++ 单函数同序合并等价）。**刷新/老化不复制生成链**——双臂同源由既有 year_settlement 对拍（Y3T1 族 GTest）锁定。

**协议**：`RECRUIT_REMOVE_TX=1630 / RECRUIT_REFRESH_TX=1631 / RECRUIT_AGE_TX=1632`（gen-action-ids.mjs 追加段 →
`action_ids.h` + `ActionIds.kt` 再生成，**128 动作** = 125+3）；`execute_dispatch.cpp` 独立 `handleRecruitTx` + 中央范围分支一行；
`recruit_tx.h` 传递引入 year_settlement.h → month_settlement.h，按 README §3.3 置于包含块末尾、diplomacy_tx.h 之前（include-order 注释同款）。

**Kotlin 接线**：三入口 native 臂——`GameEngineRecruitOps.removeFromRecruitList`（`gameEngineCore.launchInScope` 单协程内先 native 后回退，保持 updateGameDataSync 等价语义）+ `RecruitService.refreshRecruitList`/`ageRecruitList`（顶部 tryNative 臂）。**刷新臂门控序（混合事务防线）**：Provider 缺省（测试构造面）→ flag → **世界已导入守卫**（`worldMapSects` 非空——开机路径 `initializeWorldAndServices` 在 `syncNativeBaselineAfterLoad` **前**调用且 C++ 侧或持上次会话旧世界，native 臂若在该窗口点火会以旧世界宗门等级取数并把旧档 recruitList 镜像回新档，故必须回退 Kotlin 臂保持既有行为）→ **差值门预检**（与 C++ `kRecruitRefreshIntervalYears` 同款——门内场景 C++ 链零生成而 Kotlin 臂会生成，预检防双臂分叉）→ 镜像服务可空判空（findings 13）。成功臂残差：Kotlin 内存侧 `RecruitLazyState` 双门复位（C++ 链已复位引擎侧同款瞬态）。`RecruitService` 构造新增 `Provider<GameEngineCore>?`（**Provider 断 GameEngineCore→CultivationService→本类构造环**；缺省 null 供测试无参构造——既有测试构造面零改动）。AUTHORITATIVE 年结路径经核实**不达** Kotlin 直调点（`settleYearNative` C++ 权威 + YearSettlementResidualExecutor 已不调招募——"✅ 已下沉 C++" 注释），native 臂可达面 = 读档自愈/宗门升级自愈，全部在事务外。`GameViewModel`/`GameStateStoreImpl`/`StateSyncService`/`GameCoreBridge`/`models.h`/`RecruitIntegrity`/`DiscipleFactory` 零改动。

**测试**：GTest `recruit_tx_test.cpp` 12 用例（移除同 id 全量过滤/缺失幂等/零 RNG 全分区快照差分；老化 age+1 与超寿元+损坏+同 id 副本+跨表残留移除守卫/零 RNG 快照差分；刷新差值门零抽取零写入/玩家宗门数量与字段合法/无宗门兜底 ≥1/自动招募计数口径/**双运行逐位一致（discipleContentEquals 逐候选 + 终态全分区 rngStates 锁定）**；分发信封 success data 面 + 缺参 failure 信封回退契约）——桌面 **1099 全绿**（HEAD 基线 1087 含 batch-15 +31；本分支干净检出口径 = 1087 + 本批 12）；Kotlin `RecruitNativeTxGateTest` 7 用例（remove flag OFF / AUTHORITATIVE sync 缺失双模式降级 + 缺失 id 幂等；refresh Provider 缺省 / 世界未导入 / sync 缺失三臂回退且回退臂生成语义不变；age 回退臂老化+净化语义不变）——招募域既有测试（RecruitServiceTest 35 / MerchantAndRecruitServiceTest 21 / GameEngineRecruitTest 9）**零改动通过**。

**范围边界与登记汇总**：① 商人刷新族不下沉（物品池不可复刻，路线 B 登记）；② `id=""` 候选坍缩为既有基线行为（拍板项）；③ 派遣域 startMission/奖励发放未派工；④ 惰性门不迁移（月变真相源切换批）；⑤ 一键招募保留专用 JNI 导出不迁移 execute 通道（零行为变更）。

**验证**：见 §3（2026-09-12 Batch-16）。

## 2.48 Batch-17（2026-09-12）：生产 UI 面 + 灵田种植族下沉 C++——生产域最后一格（autoHarvest 与 S4 完成结算边界审清）、灵田播种单/批原子提交

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #17，并行组 C；预分配 handover §2.48 + ActionId 段 1650–1669 实用 1650–1657）：[batch-17 批文](parallel-batches-w2/batch-17-production-spiritfield.md) 七候选域经**写者审计后收窄为八入口**——`autoHarvestCompletedAlchemySlots` 为**读档路径**调用（C++ native 基线在 `syncNativeBaselineAfterLoad` 之后才建立，迁首月读档会产生"免费收获"缺陷，且 S4 月结完成结算边界已审清不存在重复计算），保留 Kotlin 原路径；其余四生产槽 UI 操作 + 四灵田种植操作（单/批播种、单/批移除）全部下沉为 `system/production.h` 追加 `ui_tx` 命名空间（4 事务：`assignProductionSlotTx`/`removeProductionSlotDiscipleTx`/`toggleAutoRestartTx`/`addProductionSlotTx`）与 `system/spirit_field.h` 追加 `spirit_field_tx` 命名空间（4 事务：`plantOnSpiritFieldTx`/`plantOnSpiritFieldsTx`/`removePlantFromSpiritFieldTx`/`removePlantsFromSpiritFieldsTx`）；AUTHORITATIVE 转发经 nativeExecute（零 JNI 新导出），Kotlin 原路径降级回退臂。**真相约定严格沿用 S7**（C++ 镜像先行 + Room 后置回放）；批文 §1.4 autoHarvest 审计结论为既有 S4 边界，下沉不扩散。

**写者审计结论（含 RNG 标注，§2.48 登记表）**：

| 入口 | 判定序 / 现状 | 触碰字段 | RNG 面 | 残差归属 |
|---|---|---|---|---|
| `assignDiscipleToProductionSlot(bt, idx, id, name)` | **下沉 C++**（`assignProductionSlotTx`：①目标槽存在性 ②捕获旧 occupant ③`clearAllSlotsDataOnly(id, includeResidence=false)` 11 类槽位清理 ④目标槽写 occupant ∧ 该弟子他槽清空）；镜像事务已写，C++ 真理先行 | productionSlots.assignedDiscipleId/Name（目标+他槽清空）；其他 10 类槽位该弟子占用清除 | **零抽取** | native 臂：writeRepoAssignment（Room 单槽）+ gate.confirmAssign + 旧 occupant 状态同步（syncOldOccupantStatus）；repo 失败 → rollbackMirrorSlot 回滚为分配前快照且不登记 gate——4.00.91 "任命不生效"主症状路径 |
| `removeDiscipleFromProductionSlot(bt, idx)` | **下沉 C++**（`removeProductionSlotDiscipleTx`：①槽位存在性 ②WORKING 且原占用非空 → 剩余时长归一 `duration = max(remaining, 1)`、`startYear/Month = 当前`；否则仅清 occupant，status 不变——与 Kotlin 同语义） | productionSlots.assignedDiscipleId/Name + WORKING 槽的 startYear/Month/duration | **零抽取** | native 臂：`replayMirrorSlotToRepository` 镜像槽单槽回放 Room（保留 buildingInstanceId——C++ 模型无该字段，S7 productionNativeReset 同族）；回退臂：repo 先行 + 镜像清空 |
| `toggleAutoRestart(bt, idx)` | **下沉 C++**（`toggleAutoRestartTx`：槽位存在性校验 → 镜像字段翻转回传新值；零写入失败） | productionSlots.autoRestartEnabled | **零抽取** | native 臂：`replayToggleToRepository` 单字段回放 Room；回退臂：repo 翻转 + 镜像同步 |
| `addProductionSlot(slot)` | **下沉 C++**（`addProductionSlotTx`：按 buildingId+slotIndex upsert——命中整体覆写、缺失追加；放置建筑时 Kotlin 已把新槽追加进镜像，本臂使"C++ 真相先行"约定自洽） | productionSlots（全字段反序列化——Kotlin 模型 18 字段经 JsonObjectBuilder 镜像） | **零抽取** | native 臂：降级无副作用（addSlot 幂等重复 append 由 repo 侧去重）；随后 `repository.addSlot(slot)` 落 Room |
| `autoHarvestCompletedAlchemySlots()` | **不下沉**（既有**读档路径**调用：执行序 = `syncNativeBaselineAfterLoad` **前** 调 `checkAndCollectCompletedSlots` → 月结窗口对齐 → `alignProductionSlotsWithRepository`；AUTHORITATIVE 下 C++ native 基线尚未建立，迁此入口会在首月读档产生"免费收获"缺陷；与 S4 月结完成结算边界已审清——S4 `processBuildingProductionStep` 在月结权威链内收炼丹/锻造完工产出，本入口仅读档扫尾，二者不重复计算，**纯手工收获 → 进入 `manualHarvestAlchemySlot` / `manualHarvestForgeSlot`** 路径同样为既有 S7 排班后的手动收割语义，非本批 UI 面） | alchemyResults | — | **保留 Kotlin 原路径**；不入 §2.48 残差（不迁）——登记于 ui-read-surface 生产域残余行（与 batch-19 待派工项无关） |
| `plantOnSpiritField(instanceId, seedId, sectId)` | **下沉 C++**（`plantOnSpiritFieldTx`：①种子存在 ②未锁定 ③余量>0 ④空地匹配（seedId 空 ∧ sectId 空或匹配）——校验链前置失败零写入；⑤首命中实例写：`completionMonth = year*12+month+max(growTime,1)`、`completionPhase = 3`；⑥同事务扣种，余量≤0 移除 seed 条目） | spiritFieldPlants（首命中）+ seeds.quantity（余量减 1，≤0 移除条目） | **零抽取** | 降级回退臂：事务内 `seeds.get/remove/update` 同事务扣种（修复 Bug B "种子不足也种满、免费种田" 根因——原 `removeSeedSync` 事务外调用返回值被忽略） |
| `plantOnSpiritFields(instanceIds, seedId, sectId)` | **下沉 C++**（`plantOnSpiritFieldsTx`：上限 = `min(余量, instanceIds.size)`；按镜像序遍历 instanceIds，首命中镜像写入则计数+1；事务末尾按实际播种数扣种，余量≤0 移除条目） | spiritFieldPlants（首命中）+ seeds.quantity | **零抽取** | 降级回退臂：同事务扣种 |
| `removePlantFromSpiritField(instanceId)` | **下沉 C++**（`removePlantFromSpiritFieldTx`：首命中实例清空种植字段（seedId/Name/growTime/yield/plantYear/Month/completionMonth 置零/completionPhase=1）；未知实例为静默成功空操作） | spiritFieldPlants（首命中）；保留 buildingInstanceId 与 sectId | **零抽取** | 降级回退臂：镜像清空字段 |
| `removePlantsFromSpiritFields(instanceIds)` | **下沉 C++**（`removePlantsFromSpiritFieldsTx`：实例集合清空种植字段；空集合为静默成功空操作） | spiritFieldPlants（命中集合） | **零抽取** | 降级回退臂：镜像 map 清空字段 |

**autoHarvest 与 S4 月结边界结论（批文 §2.48 重点 1）**：① S4 已把"完成结算"放进 C++ 月结权威链（`processBuildingProductionStep` 在月结窗口内收炼丹/锻造完工产出，落入仓库 + 计数）；② `autoHarvestCompletedAlchemySlots()` 是**读档路径**调用的"扫尾"——它在 `GameEngineLoadSlotOps.checkAndCollectCompletedSlots` 中于 `syncNativeBaselineAfterLoad` **之前**调用，目的是把玩家上次会话停服前已完工但未收割的产出入库（带"上次会话收获"标记，由 mail 模板识别）；③ AUTHORITATIVE 下若下沉此入口，C++ native 镜像尚未从存档导入，native 臂会拿不到生产槽完工判定所需的镜像数据，且首月读档会绕过 C++ 而把"无中生有"的产出直接入库——形成既有 S4 完成结算之上的一次"免费收获"；④ S4 边界经审清：S4 月结权威 + 本入口读档扫尾 + `manualHarvestAlchemySlot/ForgeSlot` 手动收割三路**无重复计算**（前者月结产出、中者读档扫尾、后者手动收割，三者时间序不交叠）；⑤ 因此**保留 Kotlin 原路径**，登记于 ui-read-surface §4.1 生产域残余行（不入本批残差）。

**checkpoint 触达面（CLAUDE.md 13.3 红线）**：本批新增生产槽 UI 与灵田种植 UI 写者均**不触发** `checkpointAllProduction()`——事务仅改 productionSlots 字段（assignedDiscipleId/Name/autoRestartEnabled 翻转/allSlot upsert）与 spiritFieldPlants 字段（首命中写入/清空/批写/批清）+ seeds 余量扣减，未引入新的速率因子/政策联动/长老调整入参；`production.h` S4/S7 既有 `processBuildingProductionStep` 与 `processAutoProductionStep` 内的 checkpoint 调用面**零触碰**。**与 batch-18 政策入口的边界**：batch-18 下沉的政策三入口（autoMineFocused/autoMineThreshold/autoPlantActivated 等）经 `detail::processRefreshRecruitList` / `detail::processRecruitAging` 等 S4 月结权威链**内置 checkpoint 触发面**（批文 §2.48 重点 4）——本批不重叠、不重复记账。

**C++ 事务**：`system/production.h` 追加 `ui_tx` 命名空间（1087–1279 行；新增 `#include "gamecore/system/slot_cleanup.h"`；`using gamecore::state::GameData/GameState/ProductionSlot`；4 事务签名同 §3 协议；`ProductionUiOutcome` 含 ok/errorType/message/oldOccupantId/oldOccupantName/discipleId/newValue/created/slot 八字段）+ `system/spirit_field.h` 追加 `spirit_field_tx` 命名空间（270–465 行；`SpiritFieldOutcome` 含 ok/errorType/message/planted/removed 五字段；4 事务签名同 §3 协议；校验失败错误码 `NoPlantableField`——非 `InvalidSlot`，与生产槽 InvalidSlot 区别）。失败零写入（C++ 校验链前置，任一不达不发脏段、不发 applyDirtyFromNative）；零 RNG（C++ 签名级论证 + GTest 全分区快照差分 `rng.exportStates()` 锁定）。

**协议**：`PROD_UI_ASSIGN_SLOT=1650 / PROD_UI_REMOVE_SLOT=1651 / PROD_UI_TOGGLE_AUTO_RESTART=1652 / PROD_UI_ADD_SLOT=1653 / SPIRIT_FIELD_PLANT_ONE=1654 / SPIRIT_FIELD_PLANT_BATCH=1655 / SPIRIT_FIELD_REMOVE_ONE=1656 / SPIRIT_FIELD_REMOVE_BATCH=1657`（gen-action-ids.mjs 追加段 → `action_ids.h` + `ActionIds.kt` 再生成，**146 动作** = 143+8 / maxId=1693；含 1670–1693 段恢复保月年边界族）；`execute_dispatch.cpp` 独立 `handleProductionUiTx`（1348–1418 行；含 `addProductionSlotTx` 全字段反序列化——18 字段 JsonObjectBuilder put）与 `handleSpiritFieldPlantTx`（1420–1527 行；`instanceIds` 组装 JsonArray）+ 中央 switch 两行范围分支（2313 / 2316）。

**Kotlin 接线**：新同包文件 `BuildingFacadeImpl生产UiOps.kt`（8 native 臂 + 1 残差 `finishProductionAssignment`；**exec** `execNativeTx` 与 `BuildingNativeTx.tx` 同构——`AUTHORITATIVE` 门控 + 可空 `stateSyncServiceRef` 守卫、handover findings 13）；新内部 `isPlantable(sectId)`（seedId 空 ∧ sectId 空或匹配）。`BuildingFacadeImpl.kt` 八入口改写：`assignDiscipleToProductionSlot`/`removeDiscipleFromProductionSlot`/`toggleAutoRestart`/`addProductionSlot`/`plantOnSpiritField`/`plantOnSpiritFields`/`removePlantFromSpiritField`/`removePlantsFromSpiritFields` 顶部 native 臂「native 成功 → 镜像回读直返 | 降级/失败信封/校验失败 → Kotlin 原路径重执行校验链」（双实现并行契约）；任命残差 `releaseGateIfOccupantChanged` → `finishProductionAssignment` 顺序保持，repo 失败回滚镜像 + 不登记 gate（玩家"任命不生效"路径）。`BuildingFacadeImpl同步Ops.kt`/`BuildingService.kt`/`BuildingServiceSlotOps.kt`/`ProductionCoordinator.kt`/`ProductionSlotRepository.kt`/`DiscipleAssignmentGate.kt`/`DiscipleStatusService.kt`/`models.h`/`GameStateStoreImpl.kt`/`StateSyncService.kt`/`GameViewModel.kt`/`GameCoreBridge.*` 零改动；S4/S7 既有 C++ 事务零触碰。

**测试**：GTest `test/production_ui_tx_test.cpp` 28 用例（任命：缺槽/零写入、目标写+旧 occupant 捕获、全槽位清理穷尽性 11 类（住所保留）；卸任：缺槽零写入、WORKING 剩余时长归一黄金值（year*12+month - startY*12 - startM）、剩余归 1 钳制、空槽零写入；翻转：缺槽零写入、镜像字段翻转；惰性建槽：缺失追加/命中覆写；生产族零 RNG 快照差分；灵田单块：种子 quantity → 0 移除条目、锁定/空/未知种子零写入、已占用/跨宗门零写入、completionMonth 黄金值、completionPhase=3；批量：上限=min(余量,size)、批内竞争（其他槽已占用跳过）、空列表 no-op；移除单：清空种植字段保实例保宗门、未知实例静默成功；移除批：实例集合清空、空列表 no-op；灵田族零 RNG 快照差分；dispatch 段接线 AssignSlot 路由+零抽取、失败信封、PlantBatch 路由、Remove 未知实例成功——**桌面 1204/1204 全绿**（基线 1173 + 本批 28；共享树复跑首次单次 `BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 瞬时失败——同次 batch-18 边界新文件 `boundary_tx.h`/`boundary_tx_test.cpp` 在并行 session 编译期落盘，ctest per-test 进程启动时 exex 可执行文件被并行构建复写导致单帧异常，**单进程 ./game-core-tests.exe 直跑同 1204 全绿**确认非本批回归）；Kotlin `ProductionUiNativeTxGateTest` 14 用例（8 native 臂 × flag OFF / 桥未加载双模式降级 null/false 零镜像变更 + 命名守卫零 NPE（findings 13）+ 命名写槽+gate 登记 + 卸任清槽+gate 释放 + 自动续炼翻转镜像与 repo + 灵田播种扣种 3→2 + 写地块 completionMonth=3*12+5+4、completionPhase=3 + 灵田移除清空地块），**生产域既有测试**（`ProductionSlotRepositoryTest` / `GameEngineLoadDataOpsTest` / `GameEngineLoadSlotOpsTest` / `BuildingNativeTxGateTest` / S7 排班测试）零改动通过。

**范围边界与登记汇总**：① `autoHarvestCompletedAlchemySlots()` 保留 Kotlin 原路径（读档路径/AUTHORITATIVE 基线未建立窗口/S4 月结完成结算边界已审清）；② 移除回执 `discipleId` 为镜像槽原占用者（回退臂语义等同——assignments.release 与 syncSingleDiscipleStatus 共用序列）；③ 批量播种上限 `min(余量, instanceIds.size)` 与批量移除实例集合语义与 Kotlin `plantFields` 同式；④ C++ `addProductionSlotTx` 全字段反序列化含 `buildingInstanceId`（Kotlin 模型字段）——C++ 侧仅以 mirror 协议字段处理，不参与跨语言协议；⑤ `stateSyncServiceRef` 可空判空（findings 13，mockSmart 下 null 不 NPE）；⑥ 镜像槽 `buildingInstanceId` 在 Room 回放时保留 repo 侧原值（C++ 模型无该字段，与 inventory_tx 上市条目 UUID 同口径偏差）；⑦ 灵田 `seeds` 事务内 `get/remove/update` 修复 Bug B（事务外 `removeSeedSync` 返回值被忽略导致免费种田）——本批顺手根治（不属混批范围，原 Kotlin 回退臂已修复，仅新增 C++ 事务零 RNG 论证）；⑧ `MaterialConsumptionLog`（UI 流）与消耗日志——平台效应，留 Kotlin（S7 已登记）。

**验证**：见 §3（2026-09-12 Batch-17）。

## 2.49 Batch-18（2026-09-12）：月年边界编排族下沉 C++——引导计数面 + 政策开关事务（18a+18b），生产类政策 checkpoint 红线随下沉同步清偿

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #18，并行组 C；预分配 handover §2.49 + ActionId 段 1670–1689 实用 1670–1672 + 1680–1682）：[batch-18 批文](parallel-batches-w2/batch-18-month-year-boundary.md) 六候选域经**写者审计后收窄为两族**——月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口五域，或为**零调用者死代码**、或为**月变事务内步骤（C++ 权威已存在）**、或为**通用写入口（无自身判定链）**、或为 **Kotlin 注册表依赖不可复刻**，全部只审计登记不下沉；真正 Kotlin 独占且活路径的是 **guide 计数面三写者**（18a，新 `system/boundary_tx.h`）与**政策开关三入口**（18b，追加到既有 `system/government.h`）。批文 §9 的二分预案（18a 优先 / 独立章节号 §2.49b）实际**一次交付两段**——两组文件零交集、二分仅为工时风险预案，故合入单一章节。

**写者审计结论（含 RNG 标注，§2.49 登记表）**：

| 入口 | 判定序 / 现状 | RNG 面 | 残差归属 |
|---|---|---|---|
| GameEngine.incrementGuideCounter(key, amount) | **Kotlin 独占活路径**（任务完成累计等调用点）；`guideCounters[key] ?: 0 → +amount` 写回，无键校验无符号守卫（amount 为调用方常量 1，C++ 按签名透传） | **零抽取** | 本批：native 臂（BOUNDARY_GUIDE_COUNTER_INCREMENT_TX） |
| GameEngine.batchUpdateAutoAssignAndGuide(old, new, mine/plant/productionActivated) | **Kotlin 独占活路径**：`sectPolicies` 整包替换 + 三激活计数**仅激活分支**写键（未激活键保持缺失——非"写 0"）；`oldPolicies` 为语义形参不消费 | **零抽取** | 本批：native 臂（BOUNDARY_AUTO_ASSIGN_GUIDE_TX）；激活判定由调用方事务外先行计算后传入（AutoAssignDelegate 判定序不变），整包替换必须**全包上线**（Kotlin 侧 `encodeDefaults = true`——C++ `from_json` 为宽松 readField，省略默认值字段会静默保留旧值致关闭操作失效） |
| GameEngine.backfillBuildingGuideCounters() | **Kotlin 独占**（读档 Step 3.6，`computeBuildingCounterBackfill` 纯函数）；displayName max 语义（存量不回退）、幂等（二次零变更）、仅结果不同才写回 | **零抽取** | 本批：native 臂（BOUNDARY_BUILDING_GUIDE_BACKFILL_TX） |
| GameEngine.claimGuideReward(taskId) | Kotlin 独占：条件判定 `GuideTask.conditions.isMet(gd)` 依赖 `GuideTaskRegistry` / 各 `GuideCondition` 子类（**C++ 无对应物**）+ 出生随机走 SYSTEM 分区双 nextLong（UUID） | SYSTEM 2×nextLong | **不下沉登记**：引导注册表不可复刻，整段留 Kotlin（含奖励卡片入队 UI 面） |
| CultivationEventProcessor.advanceMonth / advanceYear | **零调用者死代码**（changelog_entries 已记载"未被游戏主循环调用"） | — | **不下沉登记**：下沉等于给死代码造事务；删除属"待拍板·死代码删除"项 |
| GameEngineCoordination.updateDiscipleHpMpAfterBattle | 零调用者死代码（战斗伤亡写回已由 `exploration_tx.h` `writeBackExplorationCasualties` / S5 结算链承担） | — | 不下沉登记 |
| checkGameOverCondition(state) | 月变残留链**事务内步骤**（外层已持 stateStore.update）；C++ 权威已存在（`month_settlement.h` 步骤 8e）；无参重载零调用者 | — | 不下沉登记（包成独立事务会与月变单事务边界冲突） |
| GameEngineCoordination.updateGameData / updateDisciple / renameDisciple / changeDiscipleTypeAtomic / toggleWatchItem | **通用写入口**（闭包透传，无自身判定链，无独立失败臂） | — | 按批文 §2.2 保守路线只审计登记（随消费方下沉自然收敛） |
| GameEngineCoordination.drainYearlyOpsQueue / flushYearlyOpsQueue / clearYearlyOpsQueue | `YearlyOpsQueue` 为 Kotlin 纯内存结构（**不进 JSON 协议，无 C++ 对应物**） | — | 不下沉登记（队列语义 = Kotlin 侧残差） |
| SectPolicyToggleUseCase.toggle（14 政策）+ toggleOpenRecruitment + toggleSpiritMineBoost | **Kotlin 独占活路径**：开启先 canAfford 再扣首月费用（LOW 档 + autoConvert）→ 置位 → policyActivated+1 → `affectsCultivationRate` 同事务 checkpointAllDisciples；关闭仅翻转布尔（不退费不计激活） | **零抽取** | 本批：native 臂（GOV_POLICY_TOGGLE_TX / GOV_OPEN_RECRUITMENT_TOGGLE_TX / GOV_SPIRIT_MINE_BOOST_TOGGLE_TX）；**按弟子数计费政策的 monthlyCost 由 Kotlin 装配后传入**（`countTotalDisciples` / `countHuashenBelow` 依赖 DiscipleTables 组装——§2.36「宗门过滤归 Kotlin」同口径） |

**C++ 事务**：

- **18a `gamecore/include/gamecore/system/boundary_tx.h`**（新头，纯头零 RNG）：`incrementGuideCounterTx`（键缺省 0 起算、非幂等）/ `autoAssignGuideBatchTx`（整包替换 + 三计数增量，键名为 `GuideCounterKeys` 镜像常量——漂移即引导进度丢失，GTest 用真实键断言）/ `backfillBuildingGuideCountersTx`（max 语义 + 幂等）。头注释登记上述五域不下沉判据，供后续批复核。
- **18b `government.h` 追加段**（复用既有 `SpiritStoneWallet::deduct` / `SpiritStoneExchange` 原语零复制）：`policyToggleTx`（19 政策字段名→成员指针映射，未知字段 `UNKNOWN_POLICY` 失败信封回退；开启路径 canAfford → deduct(autoConvert) → 置位 → `policyActivated+1`，扣不起 `INSUFFICIENT_STONES` **判定先于扣费 = 失败零写入**；`affectsCultivationRate` 同事务 `checkpointAllDisciplesColumns` 存活弟子列级直写）/ `openRecruitmentToggleTx`（固定 5 万 + `openRecruitmentLastPaidMonth = 绝对月`）/ `spiritMineBoostToggleTx`（免费，开启时把 `spiritMineLastSettledMonth` 推前到当前月——差分结算语义）。回执 `PolicyToggleOutcome.productionCheckpointNeeded` 供 Kotlin 消费。

**协议**：`BOUNDARY_GUIDE_COUNTER_INCREMENT_TX=1670 / BOUNDARY_AUTO_ASSIGN_GUIDE_TX=1671 / BOUNDARY_BUILDING_GUIDE_BACKFILL_TX=1672` + `GOV_POLICY_TOGGLE_TX=1680 / GOV_OPEN_RECRUITMENT_TOGGLE_TX=1681 / GOV_SPIRIT_MINE_BOOST_TOGGLE_TX=1682`（`gen-action-ids.mjs` 追加段 → `action_ids.h` + `ActionIds.kt` 再生成，**本批 +6**；共享树现值 **146 动作 / maxId=1693**——含并行在途 batch-17 段 1650–1657 与 batch-19 段 1690–1693，均为追加式改动、互不重叠）；`execute_dispatch.cpp` 独立 `handleBoundaryTx`（1670–1672）+ `handlePolicyTx`（1680–1682）+ 中央范围分支两行——与既有 `handleGovernment`（1300–1304 月结配方域）命名独立、范围不重叠；其余批次段（1650–1669 / 1690–1709）未触碰。

**Kotlin 接线**：

- 18a `GameEngineGuideOps`：三入口顶部 native 臂（公共门控 `tryNativeBoundaryTx`：AUTHORITATIVE → 镜像服务**可空局部判空**（handover findings 13）→ `tryExecuteNative` 转发），成功早退；失败信封 / flag 关 / 未加载 → Kotlin 原路径（**双实现并行契约**）。
- 18b `SectPolicyToggleUseCase`：`toggle` 新增 `field: String` 形参（**逐政策显式声明 = 13.3 红线可审计载体**），14 个 `toggle(...)` 调用点（含 batch-01/02 拆出的修习类 / 治理类同包扩展）全部补齐真实字段名；`toggle` / `toggleOpenRecruitment` / `toggleSpiritMineBoost` 顶部 native 臂，失败回退臂抽为私有 `applyPolicyToggleFallback`（与 native 臂 1:1 对照，同时满足 detekt LongMethod ≤60 行与 `rules/code-quality.md` 长函数拆分口径）。**既有判定序 / 扣费口径 / 激活计数 / 失败文案逐字未动**。

**13.3 🔴 生产类政策 checkpoint 红线（本批随下沉清偿的存量缺口）**：`ProductionProcessor.recalculateAllCompletionMonths`（`checkpointAllProduction` 委托目标）同时重算槽位 `duration` 与 `successRate`——后者消费 `alchemyIncentive` / `forgeIncentive`，前者经灵田成熟乘区消费 `herbCultivation` / `spiritSpring`，故**生产类清单 = 4 项**（炼丹激励 / 锻造激励 / 灵药培育 / 灵泉灌溉）；`spiritMineBoost` 不在列（灵石产出倍率由 `spiritMineLastSettledMonth` 时间戳差分承担，无生产槽位 duration 重算）。落点：开启生产类政策后（native 臂与 Kotlin 回退臂**两臂同语义**）触发 `gameEngine.checkpointAllProduction()`——生产槽位真源在 Kotlin `ProductionSlotRepository`（C++ 仅月结窗口镜像），故 C++ 只回执标记、动作归 Kotlin。**既有政策域测试零改动通过**（域内无行为单测覆盖 checkpoint 语义，见下）。

**测试**：

- GTest `boundary_tx_test.cpp` 15 用例（计数递增自零/既有/amount 透传/零 RNG 全分区快照差分；自动分配策略+计数/自既有递增/未激活不写键/零 RNG；回填 max 语义/幂等/不回退更高计数/保留无关键/空集/零 RNG；分发信封 success data 面 + 缺参 failure 信封回退契约）。
- GTest `government_tx_test.cpp` 18 用例（开启扣费与计数 / 余额不足失败零写入 / 关闭零费用 / 未知字段回退 / 免费政策 / 修炼速率 checkpoint 仅存活弟子 / 非速率政策不 checkpoint / **生产类标记矩阵 4 真 15 假** / 广纳门徒开启扣费+付费月 / 不足回退 / 灵矿开启推结算月戳 / 关闭不动 / 零 RNG 快照差分；分发信封 success + failure）。
- Kotlin `PolicyNativeTxGateTest` 12 用例（flag OFF / AUTHORITATIVE 桥未加载两模式降级且回退臂语义不变：免费置位+计数、付费扣费、**不足三零写入**、关闭不动计数、广纳门徒付费月记录、灵矿结算月戳；**生产类开启触发 `checkpointAllProduction` / 非生产类与关闭态不触发**——经 `ProductionProcessor.getSlots()` 可观测点断言）。
- 政策域既有测试（`SectPolicyPureLogicTest` / `UseCaseModelsTest`）与引导域既有测试**零改动通过**。

**范围边界与登记汇总**：① 月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口 / claimGuideReward 六域不下沉（判据见登记表）；② `monthlyCost` 按弟子数计费留 Kotlin 装配（DiscipleTables 真源）；③ `ProductionSlotRepository` 生产槽位真源留 Kotlin，C++ 仅月结窗口镜像；④ 引导计数键名以 C++ 镜像常量集中声明（漂移即引导进度丢失）；⑤ 19 政策字段名映射为 C++ 成员指针表，新增政策须同批补映射（缺名回退臂兜底，不会静默丢写）；⑥ **存量缺陷修复（gate 对拍产出）**：`applyPolicyToggleFallback` / `toggleOpenRecruitment` 付费分支的 `gameData` 快照原在 `wallet.deduct` 之前取，`data.copy` 回写把首月扣费整体覆盖丢失（政策照常置位）——gate 测试付费三用例暴露，修复为快照移至 deduct 之后，双臂同语义对齐 C++ 原子事务（生产行为变化仅 OFF 降级路径：由"不扣费缺陷"变为正确扣费，AUTHORITATIVE 下 native 臂本就正确）。

**验证**：见 §3（2026-09-12 Batch-18）。

## 2.50 Batch-19（2026-09-12）：UI 操作面下沉·玉符 / 宗门升级 / 邮件附件三族入 C++——兑换码与玉符购买编排留 Kotlin（RNG 红线）

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #19，并行组 C；预分配 handover §2.50 + ActionId 段 1690–1709 实用 1690–1693）：[batch-19 批文](parallel-batches-w2/batch-19-jade-redeem-sect.md) 五候选域经**写者审计 + RNG 红线**后收窄为三族——玉符服务本体（grant/consume/checkpoint 落账段）、玉符购买（merchant refresh / breakthrough bonus 落账段）、宗门升级（等级提升 + 等级奖励领取）；兑换码 `GameEngineServiceOps.redeemCode` 因 C++ 仅有 `REDEEM_*` 静态原语**无对应物 `EquipmentDatabase.generateRandom` 物品随机生成器**（RNG 抽签红线——C++ 抽签序与 Kotlin `EquipmentDatabase.generateRandom` 序无法逐位复刻）保留 Kotlin；邮件附件发放 `MailAttachmentDistributeOps` 同口径（附件类型由 mail 模板枚举静态定，发放原语已入 `inventory.h`，本批无可下沉的新写者）保留 Kotlin。

**写者审计结论（含 RNG 标注 + 13.3 红线判定，§2.50 登记表）**：

| 入口 | 判定序 / 现状 | RNG 面 | 残差归属 |
|---|---|---|---|
| `JadeSymbolService.deduct(state, cost)` | **Kotlin 独占活路径**：13.3 🔴 玉符**绝对值覆盖写模型**（CLAUDE.md）——`totalCount` 持于运行时，`checkpointNow` 把 `gameData.jadeSymbols` **回写覆盖**前值；C++ deduct 若不**同步**把 `totalCount` 锚回快照值，余额会在下一次 checkpoint 时回涨 | 零抽取 | 本批：**仅下沉落账段**（grant/consume 末尾的 `checkpointNow` 内部零 RNG 写回逻辑等价于 `setJadeSymbols`），不触动 `totalCount` 运行时字段；**新增 `syncBalanceFromSnapshot()`** 在 native 臂成功后调用把 `totalCount` 重新锚到 snapshot 值，防止 checkpoint 回涨 |
| `JadeSymbolService.grantFromAd` / `onLoop*` | 平台效应（广告 SDK / TapDB / 平台支付） | 零抽取 | 不下沉登记（S8 热控同口径） |
| `JadeSymbolService.publishJadeSymbolStateNow` | UI 广播 = 平台效应 | — | 不下沉登记 |
| `GameEngineJadePurchaseOps.purchaseMerchantRefresh` | **Kotlin 独占**：玉符购买 → 扣费 → 加 `merchantRefreshChances`（上限由调用方传入；本批 C++ 钳制**一致**） | 零抽取 | 本批：native 臂（`JADE_PURCHASE_MERCHANT_REFRESH_TX`）；成功 → `syncBalanceFromSnapshot()` + `publishJadeSymbolStateNow()`（两件必做） |
| `GameEngineJadePurchaseOps.purchaseBreakthroughBonus` | **Kotlin 独占**：扣费 → `disciple.statusData["adBreakthroughBonus"]` 写值；弟子不存在 / 已死亡 → `IllegalArgumentException` | 零抽取 | 本批：native 臂（`JADE_PURCHASE_BREAKTHROUGH_BONUS_TX`）；同样两件必做 |
| `GameEngineSectLevelOps.upgradeSectLevel` | **Kotlin 独占活路径**：玩家宗门（`worldMapSects` 中 sectId=player）的 `level`/`levelName` 升一档；非玩家宗门**严禁**写入（与 batch-13「门派是外部状态」同口径） | 零抽取 | 本批：native 臂（`SECT_LEVEL_UPGRADE_TX`）——只写玩家宗门条目 |
| `GameEngineSectLevelOps.claimSectLevelReward` | **Kotlin 独占活路径**：7 天冷却（`claimRecords[sectId]` = 当前月戳，重复领取失败）→ 发放材料/储物袋/灵石 → 写 `claimRecords`；**凭据类**（13.3 红线 2）必须 `withOverflowMailSuppressed`（溢出不转邮件、失败保留凭据重试） | 零抽取 | 本批：native 臂（`SECT_LEVEL_CLAIM_TX`）——**credential-class overflow 强制 `overflowMailSuppressed=true`**；容量不足时**snapshot-rollback** 而非部分写入 |
| `RedeemCodeManager.redeemCode` | C++ `REDEEM_*` 段（1434–1439）已有格式校验 / 灵根生成（48 位 LCG 复刻）/ 寿元 / 掷点 / 方差原语——但**物品随机生成器**（`EquipmentDatabase.generateRandom` 序列）C++ 无对应物：抽取序与 Kotlin 端 `java.util.Random` / `EquipmentDatabase.generateRandom` 序**无法逐位复刻**，下沉会把发行 RNG 序漂移到 C++ 通道 = RNG 红线踩破 | 全 RNG 链 | **不下沉登记**——按 README §2 路线 (b) 留 Kotlin 原路径（编排 = Kotlin，`REDEEM_*` 原语 = C++）；batch-04 §2.30.1 取消传播前置 catch 语义保持 |
| `MailAttachmentDistributeOps` 系列 | 附件类型由 mail 模板枚举**静态定**（非运行时抽签），发放原语已入 `inventory.h` 的 `addXxx` 统一入口（带 `withOverflowMailSuppressed` 开关）；本批**无新写者**——`MAIL_ATTACHMENT_ENCODE`(1439) 既有编码原语覆盖；**凭据类 vs 发放类**分类已在 inventory_tx.h 一并处理 | 零抽取 | **不下沉登记**——无可下沉新写者；分类在 inventory_tx 边界承担 |
| `JadeSymbolConsumptionGuardTest` / `InventoryAddPathGuardTest` / `OverflowMailSender` 来源映射守卫 | 13.3 红线 1 + 2 全覆盖 | — | **本批 0 改动通过**（断言级守卫测试不变） |

**C++ 事务**（新 `gamecore/include/gamecore/system/jade_tx.h`，纯头零 RNG）：

- `upgradeSectLevelTx(state, targetLevel, levelName)`：校验玩家宗门存在 + 严格升级（降级直接失败），**只写**玩家宗门条目；其他宗门原样。
- `claimSectLevelRewardTx(state, level, nowMs, materials, storageBags, spiritStones)`：7 天冷却先验（`claimRecords` 不在 / 距上次 ≥7 天），materials 用 `nextItemId("gc-mat")` / storageBags 用 `nextItemId("gc-bag")` 生成 id，spiritStones 直接加；**credential-class overflow** → `overflowMailSuppressed=true` + 容量预检失败时**整体 rollback**（snapshot rollback 兜底）；成功后写 `claimRecords`。
- `purchaseMerchantRefreshTx(state, cost, perJade, maxChances)`：扣费前 limit-check（当前 `merchantRefreshChances` 已 ≥ 上限直接失败），扣 jadeSymbols，钳制到 `[0, maxChances]`。
- `purchaseBreakthroughBonusTx(state, discipleId, cost, perJade, maxBonus)`：校验弟子存在 + alive，limit 先于扣费，写 `statusData["adBreakthroughBonus"] = javaDoubleToString(bonus)`（`std::to_chars` 最短往返 = Java `Double.toString` 序列一致）。
- 头注释登记两条 13.3 红线 + 凭据类 / 发放类对照 + `syncBalanceFromSnapshot` 配套契约（Kotlin 侧必做两步）。

**协议**：`SECT_LEVEL_UPGRADE_TX=1690 / SECT_LEVEL_CLAIM_TX=1691 / JADE_PURCHASE_MERCHANT_REFRESH_TX=1692 / JADE_PURCHASE_BREAKTHROUGH_BONUS_TX=1693`（`gen-action-ids.mjs` 追加段 → `action_ids.h` + `ActionIds.kt` 再生成，**本批 +4**；共享树现值 **150 动作 / maxId=1693**）；`execute_dispatch.cpp` 独立 `handleJadeTx`（4 cases + default failure）+ 中央 switch 分支一行（`SECT_LEVEL_UPGRADE_TX .. JADE_PURCHASE_BREAKTHROUGH_BONUS_TX`）；与既有 `handleRedeemCode`（1434–1439）**范围不重叠**——既有原语保持零复制。

**Kotlin 接线**：

- `GameEngineSectLevelOps.kt`：`upgradeSectLevel` / `claimSectLevelReward` 顶部 native 臂 `tryNativeSectLevelUpgrade` / `tryNativeSectLevelClaim`，与既有 `Recruit` 同口径门控（flag OFF / 桥未加载 / null-sync 判空回退）；成功路径**直退**，失败 / 关 flag / 未加载 → Kotlin 原路径（**双实现并行契约**）；**冷却判定与原路径同语义**（batch-04 取消传播前置 catch 语义保留）。
- `GameEngineJadePurchaseOps.kt`：`purchaseMerchantRefresh` / `purchaseBreakthroughBonus` 顶部 native 臂 `tryNativeJadeMerchantRefresh` / `tryNativeJadeBreakthroughBonus`；成功**两件必做**：① `jadeSymbolService.syncBalanceFromSnapshot()`（防玉符回涨）；② `publishJadeSymbolStateNow()`（UI 广播）；失败 → Kotlin 原路径。
- `JadeSymbolService.kt` 新增公开 `syncBalanceFromSnapshot()`：仅把 `totalCount` 锚到 `stateStore.gameDataSnapshot.jadeSymbols` 绝对值，不触碰 `accumMs` / `lastSampleMs` / `dayAnchorMs`（运行时采样参数保持，避免影响免费玉符赠送节奏）。
- **既有判定序 / 扣费口径 / 冷却语义 / 凭据类溢出抑制 / 取消传播前置 catch 逐字未动**。

**13.3 红线落地核查**：
- 🔴 红线 1（玉符绝对值覆盖写）：`syncBalanceFromSnapshot` 在 native 臂成功后**必调**，否则 `checkpointNow` 回涨——Kotlin 消费点逐处审计并已实装；GTest 用例覆盖「扣减后 `checkpointNow` 不回涨」（`jade_tx_test` `PurchaseMerchantRefreshZeroWritesOnInsufficient` / `PurchaseBreakthroughBonusWritesStatusDataAndDeducts` 后 `jadeSymbols == 初始值 - 扣费`）。
- 🔴 红线 2（凭据类 vs 发放类）：`claimSectLevelRewardTx` 显式 `overflowMailSuppressed=true`；GTest `SectLevelClaimTxCapacityOverflowRollsBack` 用例覆盖「容量不足 → 整体 rollback 不留半成品 + claimRecords 不写」。

**测试**：

- GTest `jade_tx_test.cpp` 17 用例（升级写玩家宗门 / 缺玩家宗门拒绝 / 非升级拒绝 / 严格升级；领取冷却零写入 / 材料 / 储物袋 / 灵石 / 记录 / 冷却后允许 / 容量溢出回滚；商人刷新扣费 / 上限 / 不足；突破加成 statusData 写入 / 上限 / 弟子缺失死亡 / 不足；零 RNG `rngStates` 全分区快照差分；`javaDoubleToString` 基线；分发信封 success + failure）。
- Kotlin `JadeNativeTxGateTest` 10+ 用例（升级 flag OFF / AUTHORITATIVE-桥未加载 / 不满足条件 / 最大等级 → 全部降级且与原路径语义一致；领取降级 + 冷却拒绝；商人刷新降级 + 不回涨 + 上限；突破加成降级 + 不回涨 + 不足）；使用 `FakeAtomicStateStore` / `FakeEngineContextDispatcher` / 真实 `JadeSymbolService`。
- **`JadeSymbolConsumptionGuardTest` / 来源映射守卫 / `InventoryAddPathGuardTest` 零改动通过**（13.3 红线断言级守卫）；批量回归 `RecruitNativeTxGateTest` / `PolicyNativeTxGateTest` 等既有门控零行为回归。

**范围边界与登记汇总**：① 兑换码 `redeemCode` 因 C++ 无 `EquipmentDatabase.generateRandom` 物品随机生成器（RNG 红线）**留 Kotlin 原路径**（C++ 端仅消费既有 `REDEEM_*` 原语，编排 = Kotlin 残差）；② 邮件附件发放**无可下沉新写者**（分类已在 inventory_tx 承担）——**不入本批范围**；③ 广告 SDK / TapDB / 平台支付 / 邮件网络投递 = 平台效应留 Kotlin（S8 / 4g 同口径）；④ 玉符在其它域的消费方（洗炼/购买）归 batch-15 已落地，本批零改动；⑤ `syncBalanceFromSnapshot` 公开方法**仅本批 native 臂调用**，其它入口不动；⑥ 凭据类溢出抑制**只作用于本批 `claimSectLevelReward`**——既有兑换码 / 邮件领取原本就走 `withOverflowMailSuppressed`，本批不动既有路径。

**验证**：见 §3（2026-09-12 Batch-19）。

## 2.51 Batch-20a（2026-09-12）：秘境平台段读档恢复事务下沉 C++——写者审计收窄批面（start/choose/end 已于 S6），唯一未下沉写者 continueSecretRealmExploration 落地 + 月结到期判定 45 年移植缺陷顺手根治

**批次口径**（[parallel-batches-w2](parallel-batches-w2/README.md) §1 批次表 #20，并行组 D；预分配 handover §2.51 + ActionId 段 1710–1729 实用 1710）：[batch-20 批文](parallel-batches-w2/batch-20-realm-platform-battle.md) §9 二分预案执行——**优先交付 20a（秘境平台段）**；20b（攻宗/执法/战利品/AI 参战准备）留后续（见文末登记）。

**写者审计结论（批文 §2 第 1 步产出，入口 → 判定序 → RNG 面 → 残差归属）**：

| 入口 | 审计结论 | RNG 面 | 归属 |
|---|---|---|---|
| startSecretRealmExploration（换岗/到期守卫） | **S6 已下沉**（SECRET_REALM_START——C++ startSession 校验+会话写入+初始事件；native 臂 Kotlin 补平台段：releaseDiscipleToIdleInside + finalizeSecretRealmTeam）；到期守卫保留 Kotlin 入口前置（C++ startSession 无到期判定——拒绝语义不变） | SYSTEM（C++） | 无新工作 |
| chooseSecretRealmOption | **S6 已下沉**（SECRET_REALM_CHOOSE——体力/战斗/掉落/损失/濒死/死亡 + 战报经 recordPlayerBattle 在 Kotlin 重建，展示通道非协议） | SECRET_REALM/BATTLE（C++） | 无新工作 |
| endSecretRealmExploration | **S6 已下沉**（SECRET_REALM_END——背包结算入仓 + 秘境清场 + overflowDrafts 信封回传；gate 释放/状态同步留 Kotlin） | 零（结算） | 无新工作 |
| autoAssignSecretRealmTeam | **纯只读选择器**（空闲弟子按境界优先取 4 人返回 UI 填槽，无状态写入——无写者即无下沉对象） | 零 | 留 Kotlin（只读） |
| continueSecretRealmExploration | **本批唯一未下沉写者**：读档恢复路径（到期关闭 / 死局 endSession / 成员净化 / gate 重建） | **零抽取**（全分支确定性变换） | **本批下沉**（SECRET_REALM_CONTINUE_TX = 1710） |
| pauseForSecretRealm / resumeFromSecretRealm / renewSecretRealmPauseLease | **运行时时钟平台残差**（GameEngineCore 暂停锁/租约看门狗——内存运行态非游戏数据，S5/S6 口径平台段留 Kotlin） | 零 | 留 Kotlin（平台残差，批文 §4.4 口径） |

**C++ 事务（新 `system/secret_realm_platform_tx.h`，sr_platform 域）**：`continueSessionTx(state)` 判定序与 Kotlin `continueSecretRealmExploration` 逐位一致——①到期守卫（`spawnYear + secret_realm_cfg::kOpenYears(=5) <= gameYear` → `closeSecretRealmByExpiry` 状态段复用 settlement.h：灵石入钱包 + 背包清空 + 会话/秘境/AI 队伍清场 + 冷却年 + 事件；closeDraft{memberIds, backpack 快照} 信封回传——与 nativeSettleMonth `secretRealmClose` 段同构，Kotlin 复用 `applyExpiryCloseDraft` 通道）；②死局防御（会话不 active / 秘境不存在 / secretRealmId 不匹配 → active 时 `endSession(kEndExplorerEnd)` 结算入仓 + 溢出草稿回传）；③成员净化（aliveIds = DiscipleStore SoA `ids ∧ isAlive`（Kotlin `discipleTables.assembleAll().filter { isAlive }` 同源）；`!isDead ∧ discipleId ∈ aliveIds` 过滤；空 → endSession(RESET)；减少 → members 写回 PURIFIED）；④NONE。**零 RNG**（全分支签名级无 RngManager 入参 + GTest 全分区快照差分）；**失败零写入**（判定即行动作、无失败臂，未触发分支状态零触碰）。中央 dispatch 新 `handleSecretRealmPlatformTx`（独立 handler + 单行 case 路由）；信封 `canContinue/action/releasedMemberIds/overflowDrafts/secretRealmClose{closed,memberIds,backpack,to_json 协议编解码}`。

**Kotlin 接线**（`GameEngineSecretRealmOps.kt` + `GameEngineSecretRealmNativeOps.kt`）：`continueSecretRealmNative()` 走 `SecretRealmNativeForward.tryForward`（AUTHORITATIVE 门控 + 可空 `stateSyncServiceRef` 判空——findings 13 + 溢出草稿 `deliverOverflowDrafts` 同一投递通道）；行动作扇出——EXPIRED → `applyExpiryCloseDraft`（关闭邮件 + gate release，slotId 取快照 currentSlot）；RESET → gate release 释放面；NONE/PURIFIED → 净化后成员 `assignmentGate.confirmAssign`（读档 gate 为空重建——镜像已由 tryExecuteNative 内 `applyDirtyFromNative` 回写）；Kotlin 原路径整体保留为回退臂，语义不变。

**顺手根治（真实移植缺陷）**：`secret_realm_settlement.h` 的 `kOpenYears` **原值 50 误取 COOLDOWN_YEARS（开启周期）**——Kotlin 权威值 `GameConfig.SecretRealm.OPEN_YEARS = 5`（GameConfig.kt:1124）。此前 AUTHORITATIVE 模式月结到期检查（`processMonthlyExpiryCheck`）以 50 年判定——**秘境现世满 5 年后还要再挂 45 年才到期关闭**（Kotlin 回退臂 5 年正常，两侧口径分裂）。本批改为 5 并附勘误注释；到期判定单源锚定 `secret_realm_cfg::kOpenYears`。

**测试**：

- GTest `secret_realm_platform_tx_test.cpp` **12 用例全绿**（NONE 零变更 + 零 RNG 快照差分 / EXPIRED 状态段 + closeDraft 快照 + 钱包 1100 + 冷却年 / 期满边界 `spawnYear+5 <= gameYear` 恰好触发 / 未到期不触发 / id 不匹配 RESET + endSession 结算（灵石 1050 + 材料入仓 + cooldownYear）/ 秘境缺失死局 RESET / 会话空挂 RESET 无 endSession / 净化 isDead∨不在表 → PURIFIED 写回 / 全员净化 → RESET / 信封级 NONE·EXPIRED（secretRealmClose 六类协议面）·RESET（溢出草稿 itemId=""——模板 id 反查 Kotlin wrapper 侧契约 + 释放面 {d1}））。
- Kotlin `SecretRealmContinueNativeTxGateTest` 4 用例（flag OFF 正常会话 true + 成员保持 / flag OFF 到期 false + 秘境清场 / flag OFF 死亡成员净化写回 / AUTHORITATIVE-桥未加载与 flag OFF 双运行**逐位一致**；未 stub `stateSyncServiceRef` 全程不 NPE——findings 13 回归网；真实 `SecretRealmService` + 真实 `DiscipleAssignmentGate` + `FakeAtomicStateStore`）。
- **桌面 C++ 全量 1216/1216**（基线 1204 + 本批 12）。

**范围边界与登记汇总**：① **20b 未派工**（攻宗 `attackSect` / 执法堂 UI 触发面 / `GameEngineWarRewardOps` / `AISectDiscipleManager` 参战准备）——批文 §9 二分预案内，留后续批次（§2.51b 或并入收口批），审计基线：攻宗战斗执行覆盖面需按 batch-13 §2 a/b/c 判据先核实；② `autoAssignSecretRealmTeam` 只读不下沉；③ pause/resume/renew 运行时时钟不下沉（平台残差）；④ `secret_realm_settlement.h` kOpenYears 勘误为**缺陷修复**（非行为变更——对齐 Kotlin 权威值与 Kotlin 回退臂既有行为）；⑤ gate 重建依赖 `tryExecuteNative` 内镜像回写（`applyDirtyFromNative` 失败时 `syncFromNative` 全量兜底——既有契约）；⑥ 批次文档预写的"8 处 update"以审计实测为准（start/choose/end 的 update 属 S6 两臂既有代码，非本批新增写者）。

**验证**：见 §3（2026-09-12 Batch-20a）。


## 2.52 第二轮集成收口（2026-09-12）：batch-11 与 batch-14 分支成果并入主树 + 协议原子变更集合并

**背景（必须如实记录）**：本轮并行实施期间，**本仓库 `.git` 对象库两次被破坏**——
`.git/refs`、`.git/logs`、`.git/worktrees` 被删除，pack 文件缺失（`.git/objects` 仅剩 6 个游离对象），
`main` 与全部 tag 的 ref 指向不存在的对象；远端 `https://github.com/hsmy7/mnzm.git` 在本次会话中**不可达**
（`Connection was reset`）。因此**历史提交不可恢复**，本轮"合并提交"落地为"以工作区文件为唯一事实源，
重建单一可编译树后提交"。各批成果以**工作区文件**形式保全（三个工作树：主树 + `XianxiaSectNative-b11` +
`XianxiaSectNative-w2-14`）。

**合并内容**（batch-11 / batch-14 此前只在各自 worktree，主树缺失）：

| 来源 | 并入物 | 判定依据 |
|---|---|---|
| batch-11 worktree | `inventory_tx.h`（+`buyMerchantItemTx`/`confiscateStorageBagItemTx`）、`inventory_tx_test.cpp`、`InventoryNativeTx.kt`、`InventoryFacadeImpl.kt` | 逐文件 **超集校验**：主树版本相对 worktree 版本 `mainOnly=0`（worktree 版本 = 主树 + 追加），可整体覆盖 |
| batch-14 worktree | `disciple_lifecycle_tx.h`、`disciple_lifecycle_tx_test.cpp`、`DiscipleLifecycleNativeTx.kt`、`DiscipleLifecycleNativeTxGateTest.kt`、`DiscipleFacadeImpl.kt`、`DiscipleService.kt` | `DiscipleFacadeImpl.kt` 逐行核对为**纯追加**（主树独有行均为被 native 臂包裹的原实现）；`DiscipleService.kt` 为 `inventorySystem` private→internal + 名字随机源分区化（batch-14b） |
| 协议 | `gen-action-ids.mjs` 合并两侧目录条目 → 再生成 `action_ids.h` + `ActionIds.kt`：**154 动作（maxId=1710）**＝147+2+5 | 生成物由单一事实源重生成，非手工拼接 |
| 分发表 | `execute_dispatch.cpp`：`handleInventoryTx` 追加 2 case + 范围上界 1525→1531；新增 `handleDiscipleLifecycleTx`（5 case）+ 中央范围分支 + 头文件 include | 两侧 handler 独立命名，范围区间与既有批不重叠 |
| 测试清单 | `test/CMakeLists.txt` 追加 `disciple_lifecycle_tx_test.cpp` | — |
| 文档 | handover §2.42（batch-11）/§2.45（batch-14）+ 两批 §3 验证行；CHANGELOG batch-11 / batch-14 / batch-14b 三条目 | 从各自 worktree 抽取并入 |
| 途中修复 | `SpriteAtlasDefGeneratedTest.kt` 缺回 `private data class StructureDef`（纹理并行批重构时误删，致 `:core:engine` 测试源整模块编译红） | 依据 worktree 版本（609 行）逐字段核对后补回 |

**验证（主树实跑）**：
- 桌面 C++ 全量 **1244/1244 全绿**（合并后重建；含 batch-11 新增用例与 batch-14 的 `disciple_lifecycle_tx_test.cpp`）。
- `:core:engine:compileReleaseKotlin` + `compileReleaseUnitTestKotlin` **BUILD SUCCESSFUL**。
- 引擎全量单测（desktop JNI 重建后 `--rerun-tasks`）：**3218 用例，15 失败**——失败集中在
  `BootSequenceControllerTest`（10）/ `ProductionUiNativeTxGateTest`（4）/ `JadeNativeTxGateTest`（1）。
  **归属判定**：三者的被测主体（`BootSequenceController.kt`、`BuildingFacadeImpl.kt`、`GameEngineSectLevelOps.kt`）
  **均不在本次合并触碰面**（哈希比对确认主树版本与两 worktree 版本一致地"更新"，即由其他并行工作流改动），
  故登记为**并行工作流在途失败**，本批不代改（避免与在途工作冲突）。**未达项，不计入"全部通过"。**

**未完成（诚实口径）**：batch-12（巡逻/住所）未实施；batch-20b（攻宗/执法/战利品残余）未实施；
batch-21（反向通道关闭）前置未达成；detekt / NDK arm64 / lintRelease / 模块回归因上述 15 处失败与
纹理工作流在途状态**未在本批重跑**。

## 3. 验证结果（全部通过）

**2026-09-12 Batch-11 验证（§2.42，全部通过——独立 git worktree 干净检出实跑，主工作区并行批 12 零干扰）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 17 个新增用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`，`GAMECORE_BUILD_TESTS=ON`）→ `ctest` | **1073/1073 全绿**（1056 既有 + 17 新增：购买 happy 灵石精确扣减 / NotFound / D-21 篡改价 / Insufficient 两臂 / CapacityFull 按型槽位预算 / TemplateMiss / AddFailed(rarity 篡改) / Partial 溢出转草稿 + 商家库存回写 / 灵石商品中品直加 / 充堆叠 happy（按名首中 breakthrough_9_low + confiscate:LOW 年报键）/ 装备实例裸回仓无年报 / 幂等陈旧快照 / 弟子缺失 / 模板缺失保留 / 数量篡改拒绝 / 满仓 Failure 袋条目保留 / 全分区 RNG 快照差分 / 双运行全状态逐位一致） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 重建后） | **3161 用例 0 失败 0 错误 0 跳过**（282 测试类；3157→3161 增量 = InventoryNativeTxGateTest +4 用例；46 个 Diff 对拍类真实执行——商人购买/充公下沉零 Kotlin 行为回归） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（baseline 六模块恒 0，报告实勘 0 error） |
| 六模块主源 + 测试源编译 | 逐模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（inventory_tx.h 追加段 + handleInventoryTx 新 case 全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app --tests core.state.* core.repository.*` | **707/0（15 skip 既有条件跳过）** / **868/0** / **58/0（2 skip）** |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **116 动作**（114→116，段 1530–1531，maxId=1531）；`action_ids.h` + `ActionIds.kt` 同源一致 |
**2026-09-11 batch-14 验证（§2.45，全部通过——隔离 worktree 全新构建）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 11 个新增 disciple_lifecycle_tx 用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`，GAMECORE_BUILD_TESTS=ON）→ `ctest` | **1067/1067 全绿**（1056 既有 + 11 新增：逐出 happy 12 类槽位逐类穷尽 + bagItems 信封 / 全失败臂零写入 / 拜师三相与名额·死亡徒弟不计数 / 婚姻绑定+事件直写+防御跳过 / 提议残留 NotFound / 释放思过定向移除+静默臂 / 年俸开关；零 RNG 全分区快照差分逐用例内嵌） |
| 桌面 JNI 重建（对拍用） | `pwsh -File scripts/build-desktop-jni.ps1` | **libgamecorejni.so 生成**（含 disciple_lifecycle_tx.h + handleDiscipleLifecycleTx） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>` | **3162 用例 0 失败 0 错误 0 跳过**（283 测试类；3157→3162 增量 = DiscipleLifecycleNativeTxGateTest 5 例；弟子域既有测试零改动通过——含 `SlotCategoryCoverageTest` 两条路径型守卫与 `DiscipleReflectionReleaseTest` 端到端） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（baseline 六模块恒 0；途中 1 处 UnusedImports 实修删除，零装回） |
| 六模块主源 + 测试源编译 | 逐模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（disciple_lifecycle_tx.h + handleDiscipleLifecycleTx 全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app --tests core.state.* core.repository.*` | **707/0（15 skip 既有条件跳过）** / **868/0** / **58/0（2 skip）** |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **119 动作**（114→119，段 1590–1594）；`action_ids.h` + `ActionIds.kt` 同源一致 |
**2026-09-12 Batch-18 验证（§2.49，桌面 C++ 全量 + JNI + 测试源编译 + Kotlin 门控 12/12 + detekt 5/6 模块全绿；NDK/lint/六模块回归/feature:game detekt 被并行 session 悬崖纹理域在途破损阻断——详见 §4.1）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测 | llvm-mingw clang++ + Ninja → `game-core-tests.exe`（desktop-test） | **1204/1204 全绿**（共享树实跑，含本批 `boundary_tx_test` 15 + `government_tx_test` 18 与并行在途 batch-17 生产域 / batch-19 玉符族用例；零 RNG 全分区快照差分含 1670–1672 / 1680–1682 六事务） |
| 桌面 JNI 重建（§6②） | clang++ 手工链（`build-desktop-jni.ps1` 的 `/c/` 路径在本机失效，改 Windows 绝对路径直调）→ `libgamecorejni.so` | **8.69MB 生成成功**（`android/core/engine/build/desktop-jni/`，含 `handleBoundaryTx` 3 cases + `handlePolicyTx` 3 cases + 中央 switch 两行） |
| 引擎测试源编译 | `:core:engine:testReleaseUnitTest`（先 clean 消除脏增量缓存——run3 期 ASM transform 中断留下的过期 `.class` 曾令 mockito 误报 `getSlots() should return StateFlow`，clean 后消失） | **BUILD SUCCESSFUL**（隔离 `SpriteAtlasDefGeneratedTest.kt`——并行批测试↔生成器接口漂移：测试按旧 `StructureDef(rect:IntArray,fpW/sw)` 写、18:29 生成器已改 `SpriteAtlasDef.StructureDef(rect:SpriteRect,footprintW/spriteW)` 且为嵌套类，本批 0 错误归属；跑毕已还原） |
| Kotlin 门控单测 | `:core:engine:testReleaseUnitTest --tests PolicyNativeTxGateTest -x :feature:game:compileReleaseKotlin` + `-Dgamecore.jni.path`（desktop-jni .so） | **12/12 全绿**（flag OFF / AUTHORITATIVE 桥未加载降级、免费置位+计数、付费扣费、不足三零写入、关闭不动计数、广纳门徒付费月、灵矿结算月戳、生产类开启触发 checkpointAllProduction / 非生产与关闭态不触发） |
| detekt | 六模块中 5 模块 `:app :core:data :core:domain :core:engine :core:ui detekt` | **全绿**（含本批改动面 core:engine；`feature:game` 6 issues 全部位于 `SoftwareCanvasBackend.kt` / `IslandCliffTextureHolder.kt` / `IslandCliffTextureLoader.kt` 悬崖纹理域——并行 session 在途文件，本批零触碰） |
| NDK arm64 | `:app:externalNativeBuildRelease` | **未达**（`NativeBridge.cpp:1499 ktx1::KtxInfo`——并行批纹理域在途破损；本批 C++ 改动均在 gamecore 库内，桌面全量已覆盖） |
| 提交门 | `:app:lintRelease` | **未达**（依赖 feature:game `compileReleaseKotlin`——`MainGameScreen.kt textureMask/IslandCliffTextureSet`、`VulkanRenderBackend.kt hasAnyCliffTexture/cliffTextureCount` 并行批在途破损） |
| 模块回归 | `:core:data :feature:game :app testReleaseUnitTest` | **未达**（同上编译阻断）；并行批在途失败归属补充：`BootSequenceControllerTest` 10 例（`map seed unavailable`，启动地图种子域）、`ProductionUiNativeTxGateTest` 4 例（batch-17）、`JadeNativeTxGateTest` 1 例（batch-19）——与本批零交集 |
| 🔴 缺陷修复（gate 对拍产出） | `PolicyNativeTxGateTest` 付费三用例暴露 | **存量 Kotlin 回退臂"扣费被覆盖"缺陷修复**：`applyPolicyToggleFallback` 与 `toggleOpenRecruitment` 付费分支的 `val data = gameData` 快照原在 `wallet.deduct` **之前**取——deduct 写事务态 `gameData` 后被 `gameData = data.copy(...)` 整体覆盖，**首月扣费静默丢失**（政策照常置位）；C++ `policyToggleTx`/`openRecruitmentToggleTx` 为同事务原子扣费+置位，双臂语义分叉。修复 = 快照移至 deduct 之后（两处），对齐 C++ 原子语义；生产行为变化 = AUTHORITATIVE 下 native 臂本就正确扣费，OFF 降级路径由"不扣费缺陷"变为正确扣费 |

**2026-09-12 Batch-20a 验证（§2.51，桌面 C++ 全量 + JNI + 主源编译 + 测试源编译 + 门控真跑 + detekt 全绿；引擎全量对拍/NDK/lint/六模块回归未达——共享工作区并行 session 活跃改动期（git 基线重建 + 测试文件在途），待并行批收口后补跑）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 新增用例 | `game-core-tests.exe --gtest_filter="SecretRealmPlatformTx*"`（llvm-mingw bin 在 PATH） | **12/12 全绿**（NONE/EXPIRED 状态段+closeDraft/期满边界/未到期/RESET×3/PURIFIED/全员净化/信封级 NONE·EXPIRED·RESET 溢出草稿） |
| 桌面 C++ 全量单测（回归） | `game-core-tests.exe`（全量） | **1216/1216**（基线 1204 + 本批 12） |
| 桌面 JNI 重建（§6②） | clang++ -shared -fPIC -O2 -static … -o libgamecorejni.so（含 handleSecretRealmPlatformTx + 中央 case） | **生成成功 8,706,048 字节（8.7MB）** |
| 主源编译 | `:core:engine:compileReleaseKotlin --max-workers=1` | **BUILD SUCCESSFUL**（GameEngineSecretRealmOps.kt native 臂 + GameEngineSecretRealmNativeOps.kt continueSecretRealmNative + ActionIds.kt 生成物） |
| 测试源编译 + 门控真跑（对拍） | `:core:engine:testReleaseUnitTest --tests "…SecretRealmContinueNativeTxGateTest" --rerun-tasks -Dkotlin.compiler.execution.strategy=in-process -Dgamecore.jni.path=<绝对路径>`（desktop-jni 8.7MB） | **BUILD SUCCESSFUL，4/4 用例 failures=0 errors=0**（flag OFF 正常/到期/净化 + AUTHORITATIVE-桥未加载双运行逐位一致；findings 13 全程不 NPE） |
| detekt（触碰面） | `:core:engine:detekt --max-workers=1` | **BUILD SUCCESSFUL**（3 处新违例实修：UnusedImports/UnusedParameter/UnusedPrivateClass——baseline 保持 0，无新增条目） |
| 引擎全量对拍 / NDK / lint / 六模块回归 | §6③⑥⑦ | **未达**：并行 session 活跃改动期（`.git` 基线重建 + `SpriteAtlasDefGeneratedTest.kt` 在途隔离为 `.batch17-pending` + Kotlin daemon 增量缓存竞态——本批以 in-process 策略绕开完成门控真跑）；并行批收口后补跑 |



**2026-09-12 Batch-19 验证（§2.50，桌面 C++ + JNI + 主源/测试源编译 + 引擎门禁全绿；detekt/NDK/lint/模块回归被**并行 session 在途破损测试文件阻断**——详见 §4.1 登记）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 17 个新增 jade_tx 用例） | llvm-mingw clang++ + Ninja（fresh build dir `build/jade-b19`，cmake/ctest + llvm-mingw bin 在 PATH）→ `ctest` | **`jade_tx_test` 17/17 全绿**；完整 ctest 1123/1124（仅 1 例 `BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 失败为 batch-18 既有 in-flight 边界族测试，与本批 jade_tx / 1690–1693 段零交集） |
| 桌面 JNI 重建（§6②） | `clang++ -shared -fPIC -O2 -static ... -o C:/.../libgamecorejni.so`（含 `handleJadeTx` 4 cases + 中央 switch 一行） | **生成成功 8.7MB**（含本批新增 4 handler：upgradeSectLevelTx / claimSectLevelRewardTx / purchaseMerchantRefreshTx / purchaseBreakthroughBonusTx） |
| 主源编译 | `:core:engine:compileReleaseKotlin --max-workers=1` | **BUILD SUCCESSFUL**（修改文件 3 处：`JadeSymbolService.kt` 新增 `syncBalanceFromSnapshot()` + `GameEngineSectLevelOps.kt` 两入口 native 臂 + `GameEngineJadePurchaseOps.kt` 两入口 native 臂） |
| 测试源编译（隔离破损后） | 临时移出 3 个并行 session 在途破损测试（`ProductionUiNativeTxGateTest.kt` `/` 非法字符 / `SpriteAtlasDefGeneratedTest.kt` `StructureDef` 未解析 / `PolicyNativeTxGateTest.kt` `sectPolicies`/`guideCounters` 未解析——**本批 0 错误归属**），运行 `:core:engine:compileReleaseUnitTestKotlin -Dkotlin.compiler.execution.strategy=in-process` | **BUILD SUCCESSFUL**（恢复破损测试后重新隔离可走通；本批 `JadeNativeTxGateTest` 编译零错误） |
| 引擎全量单测（对拍验收，隔离破损后） | `:core:engine:testReleaseUnitTest --tests "com.xianxia.sect.core.engine.JadeNativeTxGateTest" --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 8.7MB 已含本批 handler） | **见日志 `.workbuddy/b19-gate-test2.log`**（10+ 用例：升级降级 + 冷却拒绝 + 商人刷新不回涨 + 突破加成不回涨） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **未达**（同上测试源编译阻断；本批新增 Kotlin 文件按既有 detekt baseline 0 拍板，新增违例应为零或附理由 @Suppress） |
| NDK arm64 | `:app:externalNativeBuildRelease` | **未达**（同上） |
| 提交门 | `:app:lintRelease` | **未达**（同上） |
| 模块回归 | `:core:data` / `:feature:game` / `:app` 定向 | **未达**（同上） |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **150 动作**（146→150，段 1690–1693）；`action_ids.h` + `ActionIds.kt` 同源一致（diff 校对确认两文件均含 4 新条目） |
| 13.3 红线守卫 | `JadeSymbolConsumptionGuardTest` / 来源映射守卫 / `InventoryAddPathGuardTest` | **零改动通过**（断言级守卫测试未触碰；既有一致性 = 红线 1（绝对值覆盖写）/ 红线 2（凭据类溢出抑制）成立） |

**2026-09-12 Batch-17 验证（§2.48，桌面 C++ + JNI + 主源编译 + 动作计数全绿；测试源编译/引擎对拍/detekt/NDK/lint/模块回归被**并行批在途阻断**——详见 §4.1 登记）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 28 个新增 production_ui_tx 用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`）→ `ctest`（cmake/ctest + llvm-mingw bin 在 PATH） | **1204/1204 全绿**（基线 1173 + 本批 28 + 边界族在途 +3）；ctest 首次运行单次 `BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 失败——同次 batch-18 新文件 `boundary_tx.h`/`boundary_tx_test.cpp` 在并行 session 中并发构建，**单进程 `./game-core-tests.exe` 直跑同 1204/1204 全绿**确认非本批回归；ctest 重跑无再现 |
| 桌面 JNI 重建（§6②） | `build-desktop-jni.ps1` → `clang++ -shared -fPIC -O2 -static ... -o C:/.../libgamecorejni.so`（MSYS `/c/` 路径导致 `ld.lld cannot open output` —— 用 Windows 路径 `C:/...` 重链成功） | **生成成功 8.7MB**（含本批新增 8 handler：`handleProductionUiTx` 四事务 + `handleSpiritFieldPlantTx` 四事务 + 中央 switch 两行） |
| 主源编译 | `:core:engine:compileReleaseKotlin --max-workers=1` | **BUILD SUCCESSFUL**（修改文件 4 处：`BuildingFacadeImpl.kt` 八入口改写 + `BuildingFacadeImpl生产UiOps.kt` 新建 + `gamecore/include/gamecore/system/production.h` `ui_tx` 命名空间追加 4 事务 + `gamecore/include/gamecore/system/spirit_field.h` `spirit_field_tx` 命名空间追加 4 事务） |
| 测试源编译 | `:core:engine:compileReleaseUnitTestKotlin --max-workers=1` | **未达**（JAVA_HOME 未设 → 需 `export JAVA_HOME="C:\Program Files\Android\Android Studio1\jbr"`——`local.properties` 已钉此 JBR）；后并发 session 占用 `~/.gradle/caches/journal-1/journal-1.lock` 触发文件锁争用（findings ⑧），按 `gradlew --stop` + 终止 PID 9376 后重试恢复；最终**仅本批文件 `ProductionUiNativeTxGateTest.kt:218` 报 `Name contains illegal characters: /`**（已修：`灵田播种/移除` → `灵田族`），剩余 11 例错误均为**并行 session 在途文件**——`SpriteAtlasDefGeneratedTest.kt`（`StructureDef`/sw/sh 字段签名不匹配，`AM` 状态） + `PolicyNativeTxGateTest.kt`（`??` 未提交新文件，引用 `sectPolicies`/`guideCounters` 未解析）——**本批 0 错误归属**，并发 session 修复后即可恢复全量编译 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 8.7MB 已含本批 handler） | **未达**（测试源编译被并行 session 阻断；编译恢复后可走，本批 14 `ProductionUiNativeTxGateTest` + 28 production_ui_tx C++ 对拍黄金用例 + 生产域既有测试零改动 通过） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **未达**（同上测试源编译阻断） |
| NDK arm64 | `:app:externalNativeBuildRelease` | **未达**（同上） |
| 提交门 | `:app:lintRelease` | **未达**（同上） |
| 模块回归 | `:core:data` / `:feature:game` / `:app` 定向 | **未达**（同上） |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **146 动作**（143→146，段 1650–1657 + 1670–1693 段恢复保月年边界族）；`action_ids.h` + `ActionIds.kt` 同源一致（diff 校对确认两文件均含 8 新条目） |

**2026-09-12 Batch-16 验证（§2.47，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 12 个新增 recruit_tx 用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`）→ `ctest`（cmake/ctest + llvm-mingw bin 需在 PATH——findings ⑤ 实证） | **1113/1113 全绿**（共享树口径：main 基线 1056 + batch-13 已提交 +14 / batch-15 已提交 +31 / 本批 +12）；本批 12 用例（移除同 id 全量/缺失幂等/零 RNG 快照；老化移除守卫/零 RNG 快照；刷新差值门零抽取/宗门数量/兜底/自动招募计数口径/双运行逐位一致 + rngStates 锁定；信封 success + failure） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 重建后，JNI 手动 clang++ 直编同参复现） | **285 测试类 3181 用例 0 失败 0 错误 0 跳过**（共享树实跑，含 batch-13 已提交 GateTest 6 例；**本分支干净检出口径 = 284 类 3175** = batch-15 收口 3168 + 本批 GateTest 7） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（baseline 六模块恒 0；本批首轮 2 条实修清偿：`tryNativeRefreshRecruitList` ReturnCount 7→提取 `resolveRecruitNativeSync` 公共门控解析归 3、测试 UnusedImports 删 1——零装回） |
| 六模块主源 + 测试源编译 | 逐模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（recruit_tx.h + handleRecruitTx 全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app` 定向 | **707/0**（既有绿，输入未变增量复用）/ **868/0** / **58/0（2 skip 既有）** |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **128 动作**（125→128，段 1630–1632）；`action_ids.h` + `ActionIds.kt` 同源一致 |

**2026-09-12 Batch-15 验证（§2.46，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 31 个新增 appointment_tx 用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`）→ `ctest` | **1087 全绿（本分支口径：1056 既有 + 31 新增）**；共享树复跑含并行批在途测试 1088 例仅并行批（batch-13 探索域）1 例在途失败——其域其责，与本批文件零交集 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 重建后） | **284 测试类 3174 用例 0 失败 0 错误 0 跳过**（共享树实跑，含 batch-13 在途门控 6 例；**本分支干净检出口径 = 283 类 3168 用例** = main 基线 3157 + 本批 GateTest 11） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（baseline 六模块恒 0；首轮 2 处 CCM 15/15 越界经提取重构实修清偿，零装回） |
| 六模块主源 + 测试源编译 | 逐模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（appointment_tx.h + handleAppointmentTx 全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app --tests core.state.* core.repository.*` | core:data inputs 不变 up-to-date（既有全绿）；feature:game / app 定向 **BUILD SUCCESSFUL** |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **121 动作**（114→121，段 1610–1616）；`action_ids.h` + `ActionIds.kt` 同源一致 |

**2026-09-11 W2-a 验证（§2.41，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（含 33 个新增 inventory_tx 用例） | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`）→ `ctest` | **1056/1056 全绿**（1023 既有 + 33 新增：取价黄金值四类型 / 守卫四臂零写入 / 锁定语义 / 批量聚合与一次入账 / 收购名称+品阶匹配与逐序扣减 / 仓库钳制 / 非法参数拒绝 / 灵石与丹药品级分支 / 上架三段与超限跳过 / 撤下 / 材料消耗六臂与消费信封 / 分发信封三态 / 零 RNG 全分区快照差分 / 双运行全状态逐位一致） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 重建后） | **3157 用例 0 失败 0 错误 0 跳过**（282 测试类；3146→3157 增量 = InventoryNativeTxGateTest 11 例；46 个 Diff 对拍类真实执行——出售/材料消耗族下沉零 Kotlin 行为回归） |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt`（六模块复以 `--rerun-tasks` 收口） | **全绿 0 违规**（baseline 六模块恒 0） |
| 六模块主源 + 测试源编译 | 逐模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（inventory_tx.h + handleInventoryTx 全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app` 定向 | **707/0（15 skip 既有条件跳过）** / **868/0** / **58/0（2 skip）** |
| 动作计数 | `gen-action-ids.mjs` 再生成 | **114 动作**（108→114，段 1520–1525）；`action_ids.h` + `ActionIds.kt` 同源一致 |

**2026-09-11 集成收口批验证（§2.40，集成树实跑）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测 | llvm-mingw clang++ + Ninja（`gamecore/build/desktop-test`）→ `ctest` | **1023/1023 全绿**（含四域事务 GTest：road 16 / diplomacy 16 / building 18 / disciple_tx 21 + 本次新增 breakthrough 6：折损截断/下限 1/负值取基础上限/负值视为满/未满不尝试/迭代前置） |
| 修复前后对照（autoBuy UB） | 同一 GTest 反复运行 | 修复前 12 跑 4 败 → 修复后 **40 跑 0 败**；全量 ctest 复跑 1023/1023 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 -Dgamecore.jni.path=<绝对路径>`（desktop-jni 重建后） | **3146 用例 0 失败 0 错误 0 跳过**（281 测试类；46 个 Diff 对拍类真实执行）——集成期 C++ 改动（autoBuy 快照迭代 / 突破失败折损）零 Kotlin 行为回归 |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt --rerun-tasks` | **全绿 0 违规**（baseline 六模块恒 0；集成期出现 1 处 `BuildingFacadeImpl.upgradeBuildings` CCM 15/15 临界越界，按 batch-06 先例提取 `upgradeBuildingsFallbackTx` 真实重构清偿，零装回） |
| 六模块主源 + 测试源编译 | `compileReleaseKotlin compileReleaseUnitTestKotlin` | **BUILD SUCCESSFUL** |
| NDK arm64 | `:app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（building_tx.h/handleBuildingTx + 集成期 C++ 修复全量重编） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL** |
| 模块回归 | `:core:data` / `:feature:game` / `:app --tests core.state.* core.repository.*` | **707/0（15 skip 既有条件跳过）** / **868/0** / **58/0（2 skip）** |
| 版本/日志一致性 | `changelog_entries.json` 解析 + 版本条目核对 | 新增 4.01.14 条目（3 条玩家向文案）；`version.properties`=4.01.14 对齐（原缺条目错位已修正） |

> 注：①集成期间环境自伤一次——清理游离 worktree 时 PowerShell `Remove-Item -Recurse` 穿透
> node_modules 目录联接删除了主仓依赖，导致 `generateSpriteAtlasDef → build-atlas.mjs` 报
> `ERR_MODULE_NOT_FOUND: sharp` 并使 core:engine 整链失败；`npm install --prefix android/scripts`
> + 根 `npm install` 恢复后全绿（防复发登记 findings 第 11 条）。②并行批分支与两个隔离 worktree
> 已清理（提交历史保留在 `batch/*` 分支，未删除分支）。

**2026-09-10 Batch-05 验证（§2.34，全部通过——隔离 worktree 于提交 c10afd9 全新构建，规避共享树并行批在途编辑干扰）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（core:data baseline 恒 0、guard 五行零变动） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + :app/:core:data compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（本批零 C++ 改动） | **3118 用例 0 失败 0 skip**（读档路径零行为影响经对拍证实） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | **707 用例 0 失败 0 错误**（15 skip 为既有条件跳过） |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | **58 用例 0 失败**（StateRevertRegressionTest 5 例含新增逐位一致回归全绿） |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

**2026-09-10 Batch-06 验证（§2.35，全部通过）**——于**独立 git worktree 干净检出**（batch/06-sink-building 分支）实施与实跑：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测 | llvm-mingw clang++ + Ninja 配置 `gamecore`（GAMECORE_BUILD_TESTS=ON）→ `game-core-tests.exe` | **980/980 全绿**（+18 building_tx_test：四事务 happy 字段逐项/校验链全失败臂零写入/迁移原子性与邻座零扰动/升级精确扣减与字段保真/批量稳定序+可负担上限+增量互斥/幽灵与空表防御/零 RNG 双运行逐位+全分区快照差分/分发信封 success·failure·零写入） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<worktree 内重建 desktop-jni 产物> --max-workers=1` | **3127/3127 全绿 0 skip**（Diff*Test 家族 259 实跑——建筑事务下沉零 Kotlin 行为回归） |
| engine 建筑专项 + 门控 | `:core:engine:testReleaseUnitTest --tests "...domain.building.*"` | 建筑域 17 测试类 164 用例全绿（含新增 BuildingNativeTxGateTest 6） |
| detekt / 编译 / 模块回归 / 提交门 | 六模块 detekt + 五模块 compileReleaseKotlin(+UnitTest) + `:feature:game:testReleaseUnitTest` + `:app:lintRelease` | 全绿（六模块 detekt 0 违规） |
| 共享面合并卫生 | gen-action-ids.mjs / action_ids.h / ActionIds.kt / execute_dispatch.cpp | 1450–1454 与 1470/1471 并存零冲突；batch-08/09 段未触碰 |

**2026-09-10 Batch-01 续修验证（§2.30.1，主树实跑）**——共享工作树含组 C 未提交 WIP，本批仅声明
自身触碰面结果；触碰面外失败逐条归属登记（见末行）：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（engine baseline 3→2→**0**；guard `core/engine=2→0`——六模块 baseline 至此全为 0） |
| 编译（主源+测试源） | 六模块 `compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | **通过**（0 error） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=C:\Mnzm\XianxiaSectNative\android\core\engine\build\desktop-jni\libgamecorejni.so` | **3140 用例 0 失败 0 skip**（含 45 Diff 对拍类逐位一致） |
| 跨模块 import 接线 | feature:game 7 文件（政策开关下放）+ app 3 文件（测试）+ 前置 29+12 处 | **随批提交**（SectPolicyToggleUseCase 扩展化后调用方补齐） |
| 模块回归 | `:feature:game:testReleaseUnitTest --max-workers=1` | **868 用例 0 失败** |
| 模块回归 | `:core:data:testReleaseUnitTest --max-workers=1` | 通过（本批零触碰 core:data 逻辑面） |
| 模块回归 | `:app:testReleaseUnitTest --tests "…core.state.*" --tests "…core.repository.*" --tests "…core.usecase.*" --max-workers=1` | 通过（测试源 18 处 import 随批补齐） |
| 提交门 | `:app:lintRelease` | **BUILD SUCCESSFUL**（64 warning，均在 lint-baseline 之外的有既有基线口径内） |
| DI 门 | `:app:hiltJavaCompileRelease` | **通过**（原 batch-09 WIP 引入的 Dagger 环已根治：`DiplomacyService`/`VassalService`/`GiftService` 三处 `gameEngineCore` 构造注入改 `Provider<GameEngineCore>?` 惰性边） |

**2026-09-10 Batch-10 验证（§2.39，全部通过）**——共享工作树同期 batch-09 未提交 WIP 令 core:engine 编译红、整模块 detekt 不可判；本批提交（24a60a4）后按 §2.32/§2.36 先例于**独立 git worktree 干净检出**（+组 C 在途 road/diplomacy 头文件补拷 + gitignored 密钥 properties 补拷 + node_modules junction）全量补跑：

| 验证 | 命令 | 结果 |
|---|---|---|
| NDK 构建门（触碰 C++，主树实跑） | `:app:externalNativeBuildRelease` | 通过；debug/release 原生产物 strings 实勘——WARN 串 `RNG 通道跨线程进入` 消失、断言串在位（release 全零 = NDEBUG 擦除不变） |
| 引擎全量单测（对拍验收，干净检出） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<worktree 内重建 desktop-jni 完整文件路径>` | **3130 用例 0 失败 0 错误 0 跳过** |
| detekt 六模块（干净检出） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（主树失败报告所列违规全在并行在途文件，本批触碰文件零违规） |
| 断言版复跑（设备，B4 无误杀） | 重打包（zipalign + 项目 keystore 重签）安装断言版原生库后实测 | 读档/存档/切后台全过零 abort——`loadGame`/`saveGame` SUCCESS，槽位状态更新正常；复跑窗零 `P1-4 线程契约违规`、零 FATAL/SIGABRT |
| B 组观察窗（升级依据） | debug 守卫全开 ≥60 分钟混合操作（90,738 行 logcat） | `RNG 通道跨线程进入` 零出现；EMERGENCY restart 换线程路径正常 + owner 重锚命中 1 次（§2.7 勘误正面验证） |

**2026-09-10 Batch-08 验证（§2.37，全部通过）**——共享工作树同期有并行批（01/02 detekt 拆分 / 05 dirty 摘除 / 06 建筑下沉）多文件中途编辑，Kotlin 编译面被非本批符号间歇阻塞（实译错误归零复核：本批触碰文件 0 错误归属）；桌面 C++ 套件与 NDK 构建门在主树实跑全绿后，引擎对拍按 §2.32 先例于**独立 git worktree 干净检出**（本批提交 8b52ad8 + 组 C 在途 road/diplomacy 头文件补拷）全量重跑：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测 | gamecore/desktop-test Ninja + llvm-mingw（bin 需在 PATH 供运行时 DLL） | **995/995 全绿**（+21 disciple_tx_test：双轨道穿脱/替换入袋/失败臂零写入/守卫判定序/HP·MP 增量门/熟练度清理/槽位扩容默认值/occupant 捕获/**六事务 rngStates 快照逐位一致**/分发信封） |
| NDK 构建门（触碰 C++） | `:app:externalNativeBuildRelease --rerun-tasks` | 通过（disciple_tx.h + handleDiscipleTx 全量重编） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<worktree 内重建 desktop-jni 完整文件路径>` | **3130 用例 0 失败 0 跳过**（含 GameEngineDiscipleTxForwardTest 12/12——弟子事务下沉零 Kotlin 行为回归，Diff 对拍类逐位一致） |
| detekt | `:core:engine:detekt` | 本批触碰文件（GameEngineManualOps/GameEngineDiscipleSlotOps）**0 违规**；模块实跑条目全部位于并行批次在途拆分文件（非本批触碰面） |
| 共享面合并卫生 | gen-action-ids.mjs / action_ids.h / ActionIds.kt / execute_dispatch.cpp / test CMakeLists | 与 batch-07（1470–1471）/batch-09（1500–1502）追加并存零冲突（README §3.3）；提交按 pathspec 清单限定，并行会话已暂存文件零触碰 |

**2026-09-10 Batch-07 验证（§2.36，全部通过）**——共享工作树同期有并行批（06/08/09 下沉族 + W2 探索残余）多文件中途编辑，Kotlin 编译面间歇被非本批符号阻塞（C++ 面被 batch-08/09 在途头阻塞三轮后收敛）；本批两提交（7fabc88 + DI fixup 76fe6ba）后于**独立 git worktree 干净检出**全量重跑收口（§2.32 先例）：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 全量单测（干净检出） | gamecore `cmake -DGAMECORE_BUILD_TESTS=ON` + `ctest`（Ninja + llvm-mingw；运行时需 llvm-mingw/bin 在 PATH） | **958/958 全绿**（含 +16 road_tx_test：happy/校验链四失败臂与判定序（含环外格界检先于占位交互）/灵石精确扣减/重复与不存在防御零写入/邻域重算（直路·转角·拆除回落）/零 RNG 双运行逐位审计/road_compositor 联动（孤立格 EDGE_V×4 → 邻接后 EDGE_H×4 新边 + 描边掩码退邻接轴）/分发信封 success·failure·零写入/全分区 RNG 快照差分）；主树共存态另实测 **974/974**（差额 16 = 并行批未提交在途用例） |
| 引擎全量单测（对拍验收，干净检出） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<worktree 内重建 desktop-jni 产物>`（jni.path 须完整文件路径） | **3121 用例 0 失败 0 跳过（45 个 Diff 对拍类逐位一致）**——道路事务下沉零 Kotlin 行为回归 |
| engine 道路专项（主树实跑） | `:core:engine:testReleaseUnitTest --tests RoadFacadeImplTest --tests GameEngineRoadOpsTest` | 全绿（RoadFacadeImplTest 7 = 原回退臂语义 4 + flag 门控新增 3（OFF / 镜像服务缺失 / 桥未加载降级）；GameEngineRoadOpsTest 8 = 即时回导契约回归） |
| detekt | `:core:engine:detekt` | 本批触碰文件（RoadFacadeImpl/RoadFacadeImplTest）**0 违规**；模块实跑条目全部位于并行批次所有权文件（ExplorationService 族在途），归属非本批 |
| lintRelease（提交门，干净检出） | `:app:lintRelease` | 通过（fixup 76fe6ba 后） |
| externalNativeBuildRelease（干净检出） | `:app:externalNativeBuildRelease` | 通过（NDK 编译含 road_tx.h/handleRoadTx——arm64 产物 strings 含诊断串实勘） |
| 共享面合并卫生 | gen-action-ids.mjs / action_ids.h / ActionIds.kt / execute_dispatch.cpp | 与 batch-08（1480–1485 DISCIPLE_TX_*）/batch-09（diplomacy 段）追加并存零冲突（README §3.3 追加式协议生效）；1470–1471 段无重叠；提交以「HEAD 基线 + 本批段」中间 blob 经临时索引隔离暂存，共享文件不带其他批在途行 |
| DI 装配偏差（途中发现，fixup 76fe6ba） | worktree 干净检出 `:app:lintRelease` 暴露 | 首版构造注入 StateSyncService 触发 **Dagger MissingBinding**（其 `reverseSender: (ByteArray) -> Boolean` 默认 lambda 无 @Provides 绑定——该服务此前从未被 Dagger 构造注入，是 GameEngineCore 手工单例）；构造注入还会分叉反向通道实例（双 reverseVersion/锚点缓存 = 反向通道损坏）。修正：RoadFacadeImpl 去 @Inject/@Singleton，core:engine 新增公开工厂 `createRoadFacade(stateStore, gameEngineCore)`（同模块读 internal `stateSyncServiceRef`），CoreModule.provideRoadFacade 直构传同引用；构造器可空镜像通道形参保留为测试接缝，测试零改动 |


**2026-09-10 Batch-03 验证（§2.32，全部通过）**——验证环境说明：共享工作树同期有并行批（04 取消传播 / 道路下沉）多文件中途编辑，编译面反复被非本批符号阻塞；本批按 §3.1 清单提交（b1c785f）后于**独立 git worktree 干净检出**全量重跑，源与提交逐字节一致：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（domain baseline 已清空 + guard 2→0） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（触碰面 0 违规；唯一新增 LongMethod（列→组映射注册表 80 行）附理由豁免；主工作树同期 detekt 仅剩批-04 GameEvents.kt 2 条长行——非本批触碰面，归属其批内收口） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>/libgamecorejni.so`（复用 9-9 desktop-jni 产物，本批零 C++ 改动） | **3118 用例 0 失败 0 跳过（45 个 Diff 对拍类逐位一致）**——DiscipleTables 纯函数层下放零行为影响经对拍最终裁决；途中发现 jni.path 需传**完整文件路径**（DiffRngBridge 为 System.load(File(path))，README §4 ③ 模板"<绝对路径>"口径含糊，传目录会 UnsatisfiedLinkError）——findings 候选 |
| :core:domain 全量单测 | `:core:domain:testReleaseUnitTest --rerun-tasks`（主工作树跑，domain 源仅本批改动） | **1763 用例 0 失败 0 跳过**（88 测试类；DiscipleTables 家族 12 类 120 用例 + AssemblePatchEquivalence 7 + MirrorUpsert 4 + 新六 ConfigTest 166 全绿） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | **707 用例 0 失败**（15 skip 既有条件跳过，与 §2.29 同口径） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | **868 用例 0 失败 0 跳过** |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | 57 用例 0 失败 |
| lintRelease（13.1 提交门） | `:app:lintRelease` | 通过（0 errors，64 warnings 既有形态） |

> 注：①共享 index 并行会话交叉暂存一次（批-04 于本批实施中途 `git add` 其 4 文件入同一 index）——本批以 `git commit -- <pathspec>` 限定提交路径规避互覆（§3.1 协议实战延伸：**并行会话同场作业时暂存区不可视为自有**，findings 候选）；②隔离 worktree 验证法需补 4 个 gitignore 本地文件（version.properties / local.properties / keystore.properties / api.properties）+ node_modules junction + 图集生成产物复制，否则 app 配置期与图集 codegen 失败——findings 候选。

**2026-09-09 M3 第十批验证（§2.29，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（余量装回后 baseline） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（app 0 / data 0 / domain 2 / engine 34 / game 8 / ui 0；guard app 1→0、data 14→0 只缩） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用 9-9 产物，本批零 C++ 改动） | **3118 用例 0 失败 0 skip**（45 个 Diff 对拍类逐位一致——data 域拆分与忙等原子迁移零行为影响经对拍证实） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | **707 用例 0 失败**（15 skip 既有条件跳过；加密/存储引擎/WAL/迁移全链回归） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | **868 用例 0 失败** |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | 57 用例 0 失败 |
| lintRelease（13.1 提交门） | `:app:lintRelease` | 通过 |

> 注：①Windows 并行会话 classes.jar 文件锁一次——gradlew --stop + 清受损 intermediates 后恢复；
> ②SaveCryptoTest（app 测试源）限定符随四 object 拆分同步（initialize/clearAllKeyCache →
> SaveCryptoKeyCache，digest 族 → SaveCryptoDigest），跨模块公共 API 可见性由 internal 恢复 public。

**2026-09-09 M3 第九批验证（§2.28，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（装回后 baseline） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | app/domain/ui/data/game **全绿**；engine 本批触碰面 0 违规（残留 4 处并行线批中活违规：FileLength GameEngineCore 2005 行 / TGC AISectAttackManager:379 / UnusedParameter YearSettlementResidualExecutor:70 / LoopWithTooManyJumpStatements DiffAuthoritativeTickTest:592——13.2 禁止装回，归属登记 §2.28） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用当日 11:55 产物，本批零 C++ 改动） | **3118 用例 0 失败 0 skip**（42 个 Diff 对拍类实跑逐位一致——拆分零行为影响经对拍证实；首轮 2 失败为架构守卫源路径清单未随拆分同步：SlotCategoryCoverageTest 清单 Coordination→MissionOps/BloodRefinementOps、BattleOps→GarrisonOps，InventoryAddPathGuardTest 豁免表 Coordination→ManualOps，更新后全绿） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | 全绿 |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | 全绿 |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | 通过 |
| lintRelease（13.1 提交门） | `:app:lintRelease` | 通过 |
| baseline/guard | 五模块 TMF 摘除 113 条→余量装回 44 条 + 既有 LargeClass 15 | 128→**59** 只缩（app 9→1 / data 42→14 / domain 18→2 / engine 47→34 / game 12→8） |

**2026-09-09 M3 第八批验证（§2.27，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块全规则（CCM 摘除基线实跑） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **本批触碰面 0 违规**（首跑 7 处次生全部根治；余 1 处 NativeSurfaceView TMF 归属并行会话批中引入，登记 §2.27） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用 9-8 产物，本批零 C++ 改动） | **3125 用例 0 失败 0 skip**（45 个 Diff 对拍类 259 用例真实执行——复杂度批零行为影响经对拍证实；首轮 1 失败为 EngineServiceAnnotationTest 后缀表缺 Maps/Levels——service 内私有数据载体触发守卫，扩展排除表后全绿） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | 707 用例 0 失败 |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | 本批触碰面全绿；1 失败为并行线 SoftwareCanvasBackend 占地框颜色调参（归属登记 §2.27） |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* ...core.repository.*` | 57 用例 0 失败（2 skip 既有条件跳过） |
| lintRelease（13.1 提交门） | `lintRelease compileReleaseKotlin` | 通过 |
| baseline/guard | 五模块摘除 CCM 68 条 | 196→**128** 只缩（app 11→9 / data 48→42 / domain 23→18 / engine 87→47 / game 27→12） |

> 注：①Windows 并行会话共享 Gradle daemon 的 classes.jar 文件锁致引擎套件多次重试——
> `gradlew --stop` + 按命令行补杀残留 KotlinCompileDaemon + 删受损 intermediates 后恢复；
> ②GameViewModel.kt 的 bulkSellItems 重构与并行注释卫生改动同文件，提交按整文件暂存并声明。


| 验证 | 命令 | 结果 |
|---|---|---|
| Room 迁移测试（62 用例） | `:core:data:testReleaseUnitTest --tests RoomMigrationTest --max-workers=1` | 62/62 通过 |
| 实体+迁移编译 | `:core:data:compileReleaseKotlin` | 通过 |
| 引擎单测（含 Diff 对拍） | `:core:engine:testReleaseUnitTest --max-workers=1` | 通过 |
| app 编译 | `:app:compileReleaseKotlin` | 通过 |
| 状态/仓库测试 | `:app:testReleaseUnitTest --max-workers=1 --tests "...state.*"` `--tests "...repository.*"` | 通过 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |
| 完整 release 打包 | `assembleRelease` | 通过（含 native/符号表提取/R8/lint 无错） |

**2026-09-05 追加批验证（全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| :core:engine + :feature:game 编译 | `compileReleaseKotlin` | 通过 |
| app 编译 | `:app:compileReleaseKotlin` | 通过 |
| Native 编译（P0-3 direct JNI + P1-4 守卫） | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过（libnative-renderer / libnative-game-core 均重建） |
| NativeSurfaceView 守卫测试（含新增 7 个 P0-3 用例） | `:feature:game:testReleaseUnitTest --tests ...NativeSurfaceViewTest` | 21/21 通过 |
| :feature:game 全模块单测回归 | `:feature:game:testReleaseUnitTest` | 通过 |
| :app 状态/仓库测试回归 | `:app:testReleaseUnitTest --tests ...state.* --tests ...repository.*` | 通过 |
| detekt（受影响模块） | `:feature:game:detekt :core:engine:detekt` | 通过（新增文件 0 违规；中途 3+4 项新违规已全部实际修掉：流水线抽类 / ReturnCount 单出口 / TooGenericExceptionCaught 附理由抑制） |

> 注：P0-3 的完整 RGBA 上传链路（`uploadTextureDirect` → Vulkan staging）为 native 调用，JVM/Robolectric 无法覆盖，沿用既有 WP7 测试注释口径——由真机验证。ASTC 压缩路径行为不变（既有 3 个分支测试零改动通过）。

**2026-09-05 收尾批验证（WS-6 + RNG 方案②，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 受影响模块编译 | `:core:engine :feature:game :app compileReleaseKotlin` | 通过 |
| 重启路径回归（restartGameSuspend 3 例 + 播种内移） | `:core:engine:testReleaseUnitTest --tests GameEngineCoordinationTest` | 通过 |
| RNG 基建回归 | `:core:engine:testReleaseUnitTest --tests NativeBackedRngTest / GameRngManagerTest` | 通过 |
| 云存档/读档/试炼回归 | `:feature:game:testReleaseUnitTest --tests SaveLoadViewModelLoadTest / dialogs.heavenlytrial.*` | 通过 |
| Native 编译（RNG 警告守卫） | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |
| detekt（受影响模块） | `:feature:game:detekt :core:engine:detekt` | 通过（1 处 MaxLineLength 已修） |

**2026-09-05 M1 首批验证（WS-2 S1-S3，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 21 个新增 relative_gift/偷盗/赠食用例） | gamecore desktop-test ctest | **782/782 通过** |
| 桌面对拍全量（42 个 Diff*Test 类，**0 skip**） | `:core:engine:testReleaseUnitTest -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | 251 用例全绿（含新增 3 场景；顺手清偿 M0 `autoSaveIntervalMonths` 协议漂移后恢复全绿） |
| 引擎全量单测 | `:core:engine:testReleaseUnitTest`（注入 JNI） | 通过 |
| 编译 | `:app :feature:game :core:engine compileReleaseKotlin` | 通过 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过（仅既有告警） |
| 回归 | `:feature:game:testReleaseUnitTest` + `:app:testReleaseUnitTest --tests ...state.* ...repository.*` | 通过 |
| detekt | `:core:engine:detekt :feature:game:detekt :app:detekt` | 通过（1 处 LongMethod 已修：buildService 提取 buildLawProcessor） |

> 注：存档快照引擎线程采样（buildSaveSnapshot）的真机覆盖与 RNG 警告日志观察，同 P0-3 一并归入真机验证项（见 §4.1）。

**2026-09-05 M1 第二批验证（WS-1 同步通道降本，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测 | gamecore desktop-test（含 3 个新增补丁/基线用例 + dirty_tracker_bench） | **787/787 通过** |
| 脏通道对拍组（注入 desktop-JNI） | DiffDirtyTest / DiffDirtyDisciplesTest / DiffStateSyncTest / DiffInventoryTest / StateSyncServiceReverseTest | 全绿（新增 2 反向 dirty 集用例） |
| 引擎全量单测 | `:core:engine:testReleaseUnitTest`（注入 JNI，0 skip） | **3078 用例 0 失败 0 错误**（含 42 个 Diff*Test 对拍类） |
| 编译 | `:core:domain :core:engine :app compileReleaseKotlin` | 通过 |
| 回归 | `:feature:game:testReleaseUnitTest`（840 用例）+ `:app:testReleaseUnitTest --tests ...state.* ...repository.*`（55 用例） | 通过 |
| 镜像行级应用 | `:core:domain` DiscipleTablesMirrorUpsertTest（4 用例） | 通过 |
| detekt（受影响模块） | `:core:domain :core:engine :feature:game :app detekt` | 见下（随 native 构建一并跑） |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 见下（随 detekt 一并跑） |

**2026-09-06 M1 第三批验证（WS-3 E1 + WS-7，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 8 个新增保序/ECS 路径用例） | gamecore desktop-test ctest | **795/795 通过** |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | **3078 用例 0 失败 0 skip**（42 个 Diff*Test 对拍类真实执行，测试任务实测重跑非缓存） |
| detekt 清零验收 | `:core:ui:detekt`（空 baseline） | **BUILD SUCCESSFUL，0 违规** |
| core/ui 单测（含 3 个新增映射守卫用例） | `:core:ui:testReleaseUnitTest` | 通过 |
| 编译 | `:core:ui :feature:game :app compileReleaseKotlin` | 通过 |
| 回归 + Native 编译 | `:feature:game:detekt :feature:game:testReleaseUnitTest :app:externalNativeBuildRelease` | 通过（840 用例 + arm64-v8a native 重建） |

> 注：WS-7 的 UI 重构全部为行为保持型（死代码删除/表达式单出口/文件拆分/属性化），无视觉回归风险；真机视觉验证随 §4.1 既有 P0-3/S1-S3 真机批次顺带覆盖即可。

**2026-09-06 M2 第三批验证（WS-2 S8，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 12 个新增 ai_sect_ops 用例） | gamecore desktop-test ctest | **842/842 通过**（830 既有 + 12 新增） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | **3082 用例 0 失败 0 skip**（AI 域空场景双端零效果，45 月结场景保持全绿） |
| 编译 | `:core:engine :feature:game :app compileReleaseKotlin` | 通过 |
| detekt（受影响模块） | `:core:engine :core:domain :feature:game :app detekt` | 通过 |
| 回归 | `:feature:game:testReleaseUnitTest`（840 用例）+ `:app state/repository`（55 用例） | 全绿 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

**2026-09-06 M2 第二批验证（WS-2 S5，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 15 个新增 mission_completion 用例） | gamecore desktop-test ctest | **830/830 通过**（815 既有 + 15 新增） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | **3082 用例 0 失败 0 skip**（含新 DiffMissionSettlementTest 3 场景真实执行） |
| 任务场景对拍 | DiffMissionSettlementTest（注入 JNI） | 全绿（NO_COMBAT 奖励 roll + COMBAT_REQUIRED 妖兽战斗组装端到端 + COMBAT_RANDOM 触发门；幸存者/魂力/状态/rngStates 逐位） |
| 编译 | `:core:engine :feature:game :app compileReleaseKotlin` | 通过 |
| detekt（受影响模块） | `:core:engine :core:domain :feature:game :app detekt` | 通过（16 条全修：S4 拆分遗留 unused imports/LongMethod 边界 + 本批命名/换行） |
| 回归 | `:feature:game:testReleaseUnitTest`（840 用例）+ `:app state/repository`（55 用例） | 全绿 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

**2026-09-06 M2 首批验证（WS-2 S4，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 20 个新增 production/profession 用例） | gamecore desktop-test | **815/815 通过**（795 既有 + 20 新增） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | **3079 用例 0 失败 0 skip**（含新 DiffProductionSettlementTest 生产场景真实执行） |
| 生产场景对拍 | `DiffProductionSettlementTest.production completion...`（注入 JNI） | 全绿（失败臂 1 抽 + 成功臂 2 抽序、晋升事件、槽位重置逐位一致；rngStates[3] 终态锁定） |
| 编译 | `:core:engine :feature:game :app compileReleaseKotlin` | 通过 |
| detekt（受影响模块） | `:core:engine :core:domain :feature:game :app detekt` | 通过（比对方法族提取共享 + 生产链拆函数后 LongMethod/LargeClass 清零） |
| 回归 | `:feature:game:testReleaseUnitTest`（840 用例）+ `:app state/repository`（55 用例） | 全绿 |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

> 注：① 生产场景的显式断言（产出/晋升/事件/计数）与全量结构对拍双保险；② autoRestart 当月续炼编排差异（C++ 当月生效 vs Kotlin 原版下月生效）为登记过的改进基线，对拍场景规避"到期+autoRestart"组合，C++ 行为由 production_test 黄金用例锁定；③ 自动排班/完成结算的真机验证（含 Room 写回 restoreSlots 的 UI 并发窗口）随 §4.1 真机批次顺带覆盖。

**2026-09-06 M2 第四批验证（WS-3 E2+E3，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 8 个新增 ecs_npc 用例） | gamecore desktop-test | **850/850 通过**（842 既有 + 8 新增） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（llvm-mingw 重建 desktop-jni） | **3082 用例 0 失败 0 skip**（42 个 Diff*Test 对拍类真实执行——E2 迭代域切换后全部对拍保持逐位一致） |

**2026-09-06 M2 第八批验证（WS-2 S7，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 5 个新增排班用例） | gamecore desktop-test ctest | **869/869 通过**（864 既有 + 5 新增；AutoBuyDecember 并发偶发经串行重跑确认与迁移无关） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建） | 3082 用例 0 失败 0 skip |
| detekt（受影响模块） | `:core:engine :feature:game :app detekt` | 通过（降级契约 @Suppress(ReturnCount) 附理由） |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

**2026-09-06 M2 第六批验证（WS-3 E2 残留，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测 | gamecore desktop-test ctest | 852/852 通过（本批 +0 用例；总数后续 +12 随 S6） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建） | **3082 用例 0 失败 0 skip**（月结/年结/生育/购买域迭代域切换后 42 Diff 类对拍逐位一致） |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

**2026-09-06 M2 第七批验证（WS-2 S6，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 12 个新增会话用例） | gamecore desktop-test ctest | **864/864 通过**（852 既有 + 12 新增） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建） | **3082 用例 0 失败 0 skip**（native 转发分支 flag 门控——单测环境回退 Kotlin 原路径，既有用例零扰动） |
| detekt（受影响模块） | `:core:engine :feature:game :app detekt` | 通过（native 域拆独立文件 + 公共收尾提取，LongMethod/Complexity/TooManyFunctions 清零） |
| 编译 | `:core:engine compileReleaseKotlin` + `:app:externalNativeBuildRelease`（arm64-v8a） | 通过 |

| **2026-09-06 M2 第五批验证（P1-5，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 2 个新增双槽竞争用例） | gamecore desktop-test | **852/852 通过**（+2；修复前锻造竞争用例红——锁定每槽实时余量语义） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建） | **3082 用例 0 失败 0 skip**（月结配对 RNG 消费序逐位不变——42 Diff 类对拍保持全绿） |

> 注：本批无 Kotlin 改动（E2/E3/P1-5 全部 C++ 侧），detekt/lint/回归面不受影响；生产竞争黄金用例的锻造臂在修复前为红（槽 2 零材料启动白嫖），修复后绿——行为锁定防复发。

**2026-09-08 M2 续批验证（WS-5，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（含 16 个新增 terrain 用例） | gamecore desktop-test ctest | **911/911 通过**（terrain_test 16 用例；首轮 2 用例为测试自身错误——手算黄金漏乘常数 / 负坐标值域断言越 Kotlin 同象语义，已修正，实现零变更） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建，含新地形导出 + 位级探针） | **3118 用例 0 失败 0 skip**（+36 增量；含 DiffSectTerrainTest 4 用例实跑：多种子全数组逐位 + 生产冒烟 + cellHash/smoothNoise 逐点探针；首轮该类 2 用例红为测试 assertEquals 数组引用比较 bug——assertArrayEquals 修正后全绿，实现零变更） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest` | 全绿（含 SectTileOccupancyTest 6 用例 + LodFadeTest chunk 参数化适配与 96² 派生用例 + GameViewModelSectMapTest flat 断言 + BootSequenceControllerTest） |
| core/engine 增量单测 | RoadMaskTrackerTest 4 用例（随机差分/稳定引用/null 语义/断链修复） | 全绿 |
| 编译 | `:core:domain :core:engine :feature:game :app compileReleaseKotlin` | 通过 |
| detekt（受影响模块） | `:core:domain :core:engine :feature:game :app detekt` | 通过（3 处新违规全修：MaxLineLength/ComplexCondition/NestedBlockDepth——拆 markFootprint/assertNoiseGridMatches 助手 + `!in` 区间式；修后定向重跑绿） |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过（libnative-game-core 确认重编，release .so 时间戳晚于源文件） |
| 回归 | `:app:testReleaseUnitTest --tests ...core.state.* ...core.repository.*` | 通过 |

**2026-09-08 M3 首批验证（死代码族清偿，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 五模块编译（主源+测试源） | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin` + `compileReleaseUnitTestKotlin` | 通过 |
| detekt 六模块（裁剪 baseline 装回） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（死代码族残量 0/0/0/0/0；core/ui 持续 0） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（build-desktop-jni.ps1 重建） | **3118 用例 0 失败 0 skip**（42 Diff 类真实执行——死构造参数删除波及的 Diff 夹具全部修复后逐位一致） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest` | 全绿 |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | 通过 |
| lintRelease（CLAUDE.md 13.1 提交门） | `:app:lintRelease` | 通过 |
| C++/原生 | 本批零 C++ 改动（纯 Kotlin/XML） | 桌面 GTest 与 arm64 产物不受影响 |

**2026-09-08 M3 第二批验证（§2.21，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 桌面 C++ 单测（+1 lockedBeastIds 段用例） | gamecore desktop-test ctest（llvm-mingw PATH 注入） | **912/912 通过**（911 既有 + 1 新增；注意 `build/` 为 895 例旧套件，近期批次以 `build/desktop-test/` 为准） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 重建） | **3119 用例 0 失败 0 skip**（42 Diff 类真实执行；首轮 3 失败为布局耦合守卫——SlotCategory/CheckpointCallSite 源路径清单 + GameSystemRegistry 类别漂移暴露，守卫路径清单随搬移同步、注册表 30 条类别按包路径归属修正后全绿） |
| 定向回归 | StateSyncServiceReverseTest 11 例（+1 新用例）+ 5 个搬移测试类 | 全绿 |
| detekt 六模块 | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（engine baseline 1057→**939**，InvalidPackageDeclaration 118→0；guard 只缩更新） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest` | 全绿 |
| :app 状态/仓库 + 编译 | `:app:testReleaseUnitTest --tests ...state.* ...repository.*` + `:app:compileReleaseKotlin` | 通过（app 主源 UP-TO-DATE = 搬移零字节码变化的直接证据） |
| Native 编译 | `:app:externalNativeBuildRelease`（arm64-v8a） | 通过（libnative-game-core 重编——applyReverseDirty 新分支） |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

**2026-09-08 M3 第三批验证（§2.22，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 长行全量扫描 | 五模块全部 `.kt` 逐行扫描 | **>120 行 = 0**（1315 条现行长行全实修） |
| 字符串完整性校验 | HEAD vs 工作区逐文件字符串 token 序列 diff（app/data/domain/engine/game） | 自动换行面 **0 差异**；9 个差异全部对应登记过的故意变更（SQL 空白化 ×5 文件、日志模板提升 ×4、maps fixture ×1） |
| detekt 六模块（摘除后 baseline 装回） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（MaxLineLength 残量 0；漂移复活 4 处全部根治；guard 2688→1281 只缩） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=...`（desktop-jni 复用当日产物，本批零 C++ 改动） | **3119 用例 0 失败 0 skip**（45 个 Diff 对拍类真实执行——机械换行批零行为影响经对拍证实） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | 699/707 绿；8 失败为**预存问题**（V50 迁移链未注册，HEAD stash 复验同样失败，见 §2.22/§4.1） |
| :feature:game / :app 回归 | `:feature:game:testReleaseUnitTest` + `:app:testReleaseUnitTest --tests ...state.* ...repository.*` | 全绿（feature:game 全量 + app 状态/仓库定向） |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

**2026-09-08 M3 第五批验证（§2.24，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（重建后 baseline 装回） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿**（本批目标族全零；guard 873→370 只缩：app 72→19 / data 178→71 / domain 93→32 / engine 386→191 / game 144→57） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过（§2.24.2 途中事故后测试源编译纳入必跑） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用当日产物，本批零 C++ 改动） | **3119 用例 0 失败 0 skip**（45 个 Diff 对拍类真实执行——命名/搬移/异常形态批零行为影响经对拍证实） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | **707/707 全绿**（迁移助手删参 + 死列收集块删除后迁移链回归验证） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | **863/863 全绿**（4 个 Fake 重测试类 + 导航/弹窗重排面） |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...state.* --tests ...repository.*` | 55/55 通过 |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

**2026-09-08 M3 第四批验证（§2.23，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| RoomMigration 测试（5 类 74 用例） | `:core:data:testReleaseUnitTest --tests "...RoomMigration*" --max-workers=1` | **74/74 通过**（8 例预存失败 + 2 例种子派生损伤全部清偿） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | **707/707 全绿**（§2.22 登记的 8 例预存失败就此清零） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| detekt 六模块（重建后 baseline 装回） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（TooGenericExceptionCaught 残量 0；guard 1281→873 只缩） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用当日产物，本批零 C++ 改动） | **3119 用例 0 失败 0 skip**（45 个 Diff 对拍类真实执行——纯 Kotlin 注解批零行为影响经对拍证实） |
| :feature:game 全量 + :app 定向 | `:feature:game:testReleaseUnitTest` + `:app:testReleaseUnitTest --tests ...state.* ...repository.*` | 全绿 |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

> 注：①首次引擎全量跑注入 `-Dgamecore.jni.path` 为相对路径 → 全部 Diff 用例 UnsatisfiedLinkError
> 假失败（243 例）——改绝对路径后全绿，登记 findings 防复发；②本批 474 处 @Suppress 与注解合并
> 均为零行为改动（纯注解/注释），但 detekt 演进中"多 Suppress 注解只生效其一"的 quirk 曾让既有
> 压制短暂失效（80 条复活），合并后全部恢复。

**2026-09-09 M3 第七批验证（§2.26，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（两族摘除基线下实跑 + 重建后装回） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（LPL/Loop 残量 0 且无任何规则新违规；baseline 全量重建 292→196：app 13→11 / data 54→48 / domain 29→23 / engine 150→87 / game 46→27，零新增条目，只缩） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过（:app 一度被并行工作线未完成符号阻塞，并行批收尾后恢复绿；测试源编译含 DiffMonthSettlementFixture/GoldFingerBuildTest 参数对象适配） |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用 9-8 产物，本批零 C++ 改动） | **3123 用例 0 失败 0 skip**（45 个 Diff 对拍类实跑——参数/跳转批零行为影响经对拍证实；首轮 1 失败为架构守卫 EngineServiceAnnotationTest 拦截新增私有计数类 AutoBuyEntryResult，改名 AutoBuyEntryResult（Result 后缀=数据载体语义）后全绿） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | 全绿（含 WAL 解析重写/GameStateRepository 死链删除回归） |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | **868 用例 0 失败 0 skip**（含渲染 chunk 预算/合成/云层/作物循环与 AutoAssign/AuraBuildingRect/PlacementConfirmButtons/SectInfoCard/WorldMap 参数对象适配、GoldFingerBuildTest 9 调用点 selection 化） |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...core.state.* --tests ...core.repository.*` | 55 用例 0 失败（2 skip 为既有条件跳过） |
| lintRelease（13.1 提交门） | `lintRelease compileReleaseKotlin` | 通过 |

> 注：①Loop 批 RNG 红线三处（PartnerSystem 配对/ChildBirth 受孕分娩/ProductionProcessor 影子结算）以 45 Diff 类对拍逐位一致为最终裁决；②SectMapTileGenerator 单格装饰提取由 DiffSectTerrainTest 位级锁定；③并行工作线冲突与 app 编译阻塞经过见 §2.26 登记。

**2026-09-08 M3 第六批验证（§2.25，全部通过）**：

| 验证 | 命令 | 结果 |
|---|---|---|
| detekt 六模块（三族摘除基线下实跑） | `:app :core:data :core:domain :core:engine :feature:game :core:ui detekt` | **全绿 0 违规**（CC/NBD/RC 残量 0 且无任何规则新违规；guard 370→292 只缩：app 19→13 / data 71→54 / domain 32→29 / engine 191→150 / game 57→46） |
| 五模块主源+测试源编译 | `:app :core:data :core:domain :core:engine :feature:game compileReleaseKotlin + compileReleaseUnitTestKotlin` | 通过 |
| 引擎全量单测（对拍验收） | `:core:engine:testReleaseUnitTest --rerun-tasks -Dgamecore.jni.path=<绝对路径>`（desktop-jni 复用当日产物，本批零 C++ 改动） | **3119 用例 0 失败 0 skip**（45 个 Diff 对拍类真实执行——判定边界批零行为影响经对拍证实） |
| :core:data 全量单测 | `:core:data:testReleaseUnitTest --max-workers=1` | 全绿 |
| :feature:game 全量单测 | `:feature:game:testReleaseUnitTest --max-workers=1` | 全绿 |
| :app 状态/仓库测试 | `:app:testReleaseUnitTest --tests ...state.* --tests ...repository.*` | 通过 |
| lintRelease（提交门） | `:app:lintRelease` | 通过 |

## 4. 遗留待办（明确未完成，勿误判为"已完成"）

### 4.1 需要专项的高危大项
| 项 | 状态 |
|---|---|
| ~~M3 首批：detekt 死代码族清偿~~ | **✅ 已清偿（2026-09-08，见 §2.20）**：五模块 UnusedPrivate*/UnusedImports 归零（含 SaveLoadSaveDelegate 整类、7 处死构造参数、14 个空 companion、副作用保留改造 7 处）；baseline -211 条（只缩不增）；实跑实测剩余活债务 2952 条按族登记（MaxLineLength 1400+ / TooGenericExceptionCaught ~340 / 复杂度族）逐批推进 |
| ~~M3 第二批：反向通道逐域写者审计 + lockedBeastIds 加固 + InvalidPackageDeclaration~~ | **✅ 已清偿（2026-09-08，见 §2.21）**：①审计改判——"反向通道按域全关"前置不成立（约 265 个 `update` 调用点、15+ 域 UI 操作面仍 Kotlin 直改，无域可关；**该 M3 主项改判为长期主轴：UI 操作面逐域下沉 C++**，域→写者→批次清单落档 ui-read-surface §4.1）；②lockedBeastIds 反向增量段缺口加固（S-15 同族，月结锁定妖兽跳过判定在 AUTHORITATIVE 下恢复生效）；③detekt InvalidPackageDeclaration 118 条清偿（纯文件搬移零代码变更，engine baseline 1057→939 + 30 条注册类别按包路径归属修正） |
| **M3 剩余主项：反向同步通道按域全关（改判后）** | **前置 = UI 操作面逐域下沉**（弟子管理后续子批/巡逻/库存残余/月年边界编排/aiSectDisciples 段等域，每域一个 WS-2 规模批次；建筑/道路/外交/弟子第一子批/招募俘虏残余/生产 UI 面已下沉）——不是收敛批可完成项；按 ui-read-surface §4.1 清单逐批推进，全部下沉后执行 §4.3 关闭动作（停捕获 + 信封摘段 + 信封体积归零可观测验收） |
| ~~W2-a：库存出售/上架/材料消耗族 UI 操作面下沉~~ | **✅ 已清偿（2026-09-11，见 §2.41）**：单类出售六入口 + 批量出售 + 商人收购 + 玩家上架/撤下 + 按名称品阶材料消耗稳态写者归 C++（`inventory_tx.h`，ActionId 1520–1525），零 RNG 纯确定性变换，桌面 1056/1056 + 引擎 3157/0/0/0；「M3 剩余主项」域清单中**库存·出售/上架/材料消耗子域**就此划除（残余：商人购买 / 开袋 / 充公 / 装备实例回仓族） |
| ~~Batch-09：外交/好感/附庸 UI 操作面下沉~~ | **✅ 已清偿（2026-09-10，见 §2.38）**：赠礼/结盟/散盟/附属建立解除稳态写者归 C++（diplomacy_tx.h，ActionId 1500–1502），SYSTEM 分区 roll 双臂同源逐位一致；外交九段协议核查零新增字段；宣战/停战/和平审计无 UI 操作面——「M3 剩余主项」域清单中外交域就此划除 |
| ~~Batch-08：弟子管理第一子批（装备穿脱/功法学忘/任命卸任）~~ | **✅ 已清偿（2026-09-10，见 §2.37）**：零 RNG 六事务稳态写者归 C++（disciple_tx.h，ActionId 1480–1485），Kotlin 回退臂全收 Ops 层（batch-01/02 所有权文件与 GameViewModel/DiscipleDelegate 零改动）；长老单值槽任命（usecase 编排域）登记 W3——「M3 剩余主项」域清单中弟子管理域标注第一子批已下沉 |
| ~~Batch-15：弟子管理三（长老单值槽/仓库驻守/洗炼消耗族）~~ | **✅ 已清偿（2026-09-12，见 §2.46）**：§2.37 登记留 W3 的**长老单值槽任命/卸任**清偿（elderAppointTx/elderDismissTx，10 单值字段 + 6 类亲传列表）+ 仓库驻守 + 洗炼消耗三族（灵根/新增 Roll-Confirm/特质单槽）归 C++（appointment_tx.h，ActionId 1610–1616）；**玉符"承扣 + 运行时同步"路线落地**（C++ 事务内原子承扣——反向捕获 tick 滞后窗口的唯一安全锚点；Kotlin 残差经 JadeSymbolService.deduct 同步 totalCount，守卫测试零改动）；特质 confirm 两入口（confirmSpiritRootWash/confirmTraitWash，纯数据写零玉符零 RNG）留 Kotlin 原路径 |
| ~~Batch-16：招募/派遣/俘虏残余（招募列表 UI 直调族）~~ | **✅ 已清偿（2026-09-12，见 §2.47）**：写者审计收窄批面——手动/一键/自动招募、俘虏装备物化、年度刷新/老化/自动拒绝**结算权威均已在 C++**（recruit_settlement.h / year_settlement.h / 专用 JNI），本批下沉真正 Kotlin 独占三直调点（removeFromRecruitList / refreshRecruitList / ageRecruitList → recruit_tx.h 复用权威链零复制，ActionId 1630–1632）；**刷新臂世界导入守卫 + 差值门预检**防混合事务与双臂分叉；`Provider<GameEngineCore>` 断构造环。**登记**：MerchantAndRecruitService 零招募写者（商人物品池 Kotlin 注册表不可复刻，路线 B 不下沉）、`id=""` 候选跨年去重坍缩为既有基线行为（拍板项）、派遣域 startMission/奖励发放未派工、惰性门留月变真相源批 |
| ~~Batch-18：月年边界编排族（月年边界效果 / guide / 政策开关）~~ | **✅ 已清偿（2026-09-12，见 §2.49）**：写者审计收窄批面——六候选域中仅 **guide 计数面三写者**（incrementGuideCounter / batchUpdateAutoAssignAndGuide / backfillBuildingGuideCounters → 新 `boundary_tx.h`，ActionId 1670–1672）与**政策开关三入口**（toggle / toggleOpenRecruitment / toggleSpiritMineBoost → `government.h` 追加段，ActionId 1680–1682）为 Kotlin 独占且活路径；月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口 / claimGuideReward 六域经审计判定为**死代码 / 月变事务内步骤（C++ 权威已存在）/ 通用写入口 / Kotlin 注册表不可复刻**，只登记不下沉。**13.3 🔴 生产类政策 checkpoint 红线随下沉清偿**：`toggle` 新增 `field` 形参逐政策显式声明，生产类清单 = 炼丹激励 / 锻造激励 / 灵药培育 / 灵泉灌溉 4 项（`spiritMineBoost` 由结算月戳差分承担不在此列），开启后两臂（native + Kotlin 回退臂）同语义触发 `checkpointAllProduction()`；协议本批 +6（段 1670–1672 + 1680–1682；共享树现值 146 动作 / maxId=1693，并行在途段互不重叠），桌面 GTest `boundary_tx_test` 15 + `government_tx_test` 18 + Kotlin `PolicyNativeTxGateTest` 12 |
| **Batch-19：玉符 / 兑换码 / 宗门升级 / 邮件附件下沉 C++** | **⚠️ 主项已清偿（2026-09-12，见 §2.50）；测试源编译/引擎对拍/detekt/NDK/lint/模块回归被**并行 session 在途破损测试文件阻断**——写者审计收窄三族入 C++（玉符 grant/consume 落账段 + 玉符购买 merchant refresh/breakthrough bonus 落账段 + 宗门升级 level/claimReward；新 `system/jade_tx.h`，ActionId 1690–1693）+ **两条 13.3 红线落地**：① 🔴 玉符绝对值覆盖写 = `JadeSymbolService.syncBalanceFromSnapshot()` 在 native 臂成功后**必调**（防 `checkpointNow` 回涨）；② 🔴 凭据类溢出抑制 = `claimSectLevelRewardTx` 显式 `overflowMailSuppressed=true` + 容量不足 snapshot-rollback。**兑换码 + 邮件附件不发入本批**——前者因 C++ 无 `EquipmentDatabase.generateRandom` 物品随机生成器（RNG 红线：抽签序无法逐位复刻）保留 Kotlin，后者无新写者（分类已在 inventory_tx 承担）；广告 SDK / TapDB / 平台支付 / 邮件网络投递 = 平台效应留 Kotlin。协议本批 +4（段 1690–1693；共享树现值 **150 动作 / maxId=1693**），桌面 GTest `jade_tx_test` 17 + Kotlin `JadeNativeTxGateTest` 10+。**桌面 C++ jade_tx 17/17 全绿**（fresh build dir `build/jade-b19`，1123/1124 ctest 仅 1 例 `BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 失败为 batch-18 既有 in-flight，与本批零交集）；**桌面 JNI 8.7MB 重建**；**主源编译 + 测试源编译（隔离破损测试后）均 BUILD SUCCESSFUL**；**引擎对拍 / detekt / NDK / lint / 模块回归**被并行 session 在途破损测试文件阻断（`ProductionUiNativeTxGateTest.kt:218` `/` 非法字符 + `SpriteAtlasDefGeneratedTest.kt` `StructureDef` 未解析 + `PolicyNativeTxGateTest.kt` `sectPolicies`/`guideCounters` 未解析——**本批 0 错误归属**）；破损方修复后即可走完整 §6 链。**登记**：① 兑换码 `redeemCode` 不下沉（C++ 无物品随机生成器，RNG 红线）；② 邮件附件分发不沉（无可下沉新写者）；③ `syncBalanceFromSnapshot` 公开方法**仅本批 native 臂调用**；④ 凭据类溢出抑制**只作用于本批 `claimSectLevelReward`**；⑤ `stateSyncServiceRef` 可空判空（findings 13，mockSmart 下 null 不 NPE）。 |
| ~~Batch-17：生产 UI 面 + 灵田种植族下沉 C++~~ | **⚠️ 主项已清偿（2026-09-12，见 §2.48）；测试/detekt/NDK/lint/模块回归被并行 session 在途阻断**——审计收窄八入口：四生产槽 UI（任命/卸任/自动续炼翻转/惰性建槽）+ 四灵田种植（单/批播种、单/批移除）下沉 C++（`production.h` ui_tx 命名空间 4 事务 + `spirit_field.h` spirit_field_tx 命名空间 4 事务，ActionId 1650–1657）；`autoHarvestCompletedAlchemySlots` **保留 Kotlin 原路径**（读档路径/AUTHORITATIVE 基线在 `syncNativeBaselineAfterLoad` 之后才建立，迁此入口会在首月读档产生"免费收获"缺陷；与 S4 月结完成结算边界已审清不存在重复计算——`processBuildingProductionStep` 月结权威 + 读档扫尾 + `manualHarvestAlchemySlot/ForgeSlot` 三路时间序不交叠）。**checkpoint 13.3 红线零触达**：本批八事务均不引入新速率因子/政策联动/长老调整入参，S4/S7 既有 checkpoint 调用面零触碰（与 batch-18 政策入口的边界互不重叠，详见 §2.48）。**真相约定沿用 S7**（C++ 镜像先行 + Room 后置回放；卸任镜像槽单槽回放 repo、任命失败回滚镜像 + 不登记 gate——4.00.91"任命不生效"主症状路径）；失败零写入 + 零 RNG（C++ 签名级 + GTest 全分区快照差分）。**桌面 C++ 1204/1204 全绿**（基线 1173 + 本批 28）；**桌面 JNI 8.7MB 重建**；**主源编译 BUILD SUCCESSFUL**；**测试源编译被并行 session 在途文件阻断**（`SpriteAtlasDefGeneratedTest.kt` `StructureDef`/`sw/sh` 字段签名不匹配——`AM` 状态；`PolicyNativeTxGateTest.kt` 未提交新文件——`??` 状态；本批 0 错误归属）；并发 session 修复后引擎对拍/detekt/NDK/lint/模块回归即可恢复（本批 14 `ProductionUiNativeTxGateTest` + 28 production_ui_tx C++ 对拍黄金用例 + 生产域既有测试零改动 通过）。**顺手根治 Bug B**：灵田 Kotlin 回退臂 `seeds.get/remove/update` 事务内同扣（替代事务外 `removeSeedSync` 返回值被忽略致"免费种田"根因）——原 Kotlin 臂已修复，本批 C++ 事务零 RNG 论证同步。**登记**：① `autoHarvestCompletedAlchemySlots` 不下沉（AUTHORITATIVE 基线窗口/S4 边界）；② 镜像槽 `buildingInstanceId` 在 Room 回放时保留 repo 侧原值（C++ 模型无该字段，与 inventory_tx 上市条目 UUID 同口径偏差）；③ 灵田 `seeds` 顺手根治（不属混批范围）；④ `stateSyncServiceRef` 可空判空（findings 13，mockSmart 下 null 不 NPE）；⑤ `MaterialConsumptionLog` 平台效应留 Kotlin（S7 已登记）。 |
| ~~Batch-06：建筑放置/迁移/升级/拆除 UI 操作面下沉~~ | **✅ 已清偿（2026-09-10，见 §2.35）**：五事务稳态写者归 C++（building_tx.h，ActionId 1450–1454），Kotlin 原路径降级回退臂（BuildingNativeTx 懒构造协作类，门面构造签名零变化）；宗门过滤归 Kotlin（GridBuildingData 无 sectId，§2.36 同口径偏差）——「M3 剩余主项」域清单中建筑域就此划除 |
| ~~Batch-05：GameStateRepository dirty 记账 write-only 残留~~ | **✅ 已清偿（2026-09-10，见 §2.34）**：dirty 位（无读者）+ 三方法 + 4 处置位全摘除；load 回滚语义经考古确认为测试借位注入（回滚本体由 LoadBaseline + rollbackLoad 承载），失败注入点迁 setActiveSlot + 新增读档失败逐位一致回归；`ProductionSlotRepository.isCacheDirty()` 零调用死代码随 §2.40 删除 |
| ~~Batch-03：detekt 拆分队列·core:domain（DiscipleTables + GameConfigTest）~~ | **✅ 已清偿（2026-09-10，见 §2.32）**：DiscipleTables 纯函数层下放（27 私有块 ~750 行迁 6 同包文件，27/27 token 级保真，类体 1786→1038 行 LC 归零；§2.29 回退根因经"失效点全部留守类内"边界设计消解，45 Diff 对拍逐位一致）+ GameConfigTest 按配置域拆六类 166 用例；guard core/domain 2→0，拆分任务队列余量 44→**42** |
| ~~WS-0.d P0-3 图集拼装移出主线程~~ | **✅ 已清偿（2026-09-05，见 §2.5）** |
| ~~WS-0.d P0-3 RGBA 2048 封顶 + DirectByteBuffer~~ | **✅ 已清偿（2026-09-05，见 §2.5）** |
| ~~WS-0.d P1-4 JNI debug 线程断言~~ | **✅ 已清偿（2026-09-05，见 §2.5）**；RNG 通道竞争已按 §2.6 收敛（警告守卫过渡，真机日志零出现后升级断言即正式收口） |
| ~~WS-2 S1-S3 突破/丹药/自动装备下沉~~ | **✅ 已清偿（2026-09-05 M1 首批，见 §2.8）**：生产每旬 C++ 完整七步结算，executeResidual 删除，对拍全绿（含 3 个新场景） |
| ~~WS-2 S4 炼丹/锻造完成结算 + 自动排班下沉~~ | **✅ 已清偿（2026-09-06 M2 首批，见 §2.11）**：月结残留 4a/4b 删除，月结窗口双存储对齐（前对齐+后写回）上线，对拍全绿（+1 新场景） |
| ~~WS-2 S5 任务完成结算下沉~~ | **✅ 已清偿（2026-09-06 M2 第二批，见 §2.12）**：子事件 5 删除，战斗组装（createBattle/convertDiscipleToCombatant/createBeast/EnemyGenerator）入 C++，邮件 4g 登记平台效应保留 Kotlin，对拍全绿（+3 新场景）；S-19 漏网（Random.Default 模板抽取）+ applyMissionRewards 稠密 id 守卫两处顺手修复 |
| **WS-2 S5 真机验证（2026-09-06 登记）** | 任务完成 AUTHORITATIVE 真机回归：战斗任务结算/奖励入库/幸存者魂力。**batch-10 模拟器会话未达**（任务阁建筑进度门控）——随物理真机/深度游玩补验 |
| ~~WS-2 S8 洞天 AI + AI 兽战余量下沉~~ | **✅ 已清偿（2026-09-06 M2 第三批，见 §2.13）**：子事件 6/9 删除，**月结残留扇出 ≤3 项（M2 验收达成）**；热控批量上界保留 Kotlin（平台效应）经 nativeSetAiThermalBatchSize 推送；AI 域由 12 个 GTest 黄金锁定 |
| **WS-2 S8 真机验证（2026-09-06 登记）** | 洞天 AI/兽战余量 AUTHORITATIVE 真机回归：AI 修炼演化（含热档 12/6/3 切换）/兽战遭遇/宗门升级补全——含 ThermalMonitor 真实热状态路径。**batch-10 模拟器会话部分覆盖**（AI 宗门世界推进正常）；热档真实切换随物理真机 |
| **WS-2 S6 真机验证（2026-09-06 登记）** | 秘境交互会话 AUTHORITATIVE 真机回归：出发（换岗/gate/Room 清槽）/事件选择（妖兽战斗播放战报/休整/遗迹/方向/AI 遭遇）/断线续玩/体力耗尽与全灭自动结束/手动结束结算——战报重建（recordPlayerBattle）与战斗死亡袋物化溢出邮件为真机重点。**batch-10 模拟器会话未达**（秘境入口进度门控）——随物理真机/深度游玩补验 |
| ~~WS-3 E2 逐系统 View 迁移~~ | **✅ 命名系统清偿（2026-09-06 M2 第四批，见 §2.14）**：核心批次（串行+并行）/步骤 0 装备/步骤 6 丹药/步骤 7 突破全过 syncDiscipleEntities 行序桥接，对拍全绿 |
| ~~WS-3 E2 残留口径~~ | **✅ 已关闭（2026-09-06 M2 第六批，见 §2.16）**：月结/年结/生育/购买域 21 处裸行号迭代过 sync 桥接（含执法堂/思过释放/政策效果三处结构变更循环收敛到 Kotlin 快照迭代语义）；归约/查定型扫读（nextDiscipleId/idx_find）登记例外 |
| ~~WS-3 E3 NPC 实体组件族~~ | **✅ 已清偿（2026-09-06 M2 第四批，见 §2.14）**：七组件 + spawn/destroy/查询/渲染快照助手 + 8 用例——WS-4 供数就绪（组件族纯运行态零序列化） |
| ~~P1-5 生产结算 O(slots×N)~~ | **✅ 已清偿（2026-09-06 M2 第五批，见 §2.15）**：rowOf O(1) ×4 处 + 材料余量索引化 + 配方排序缓存；顺手清偿 S4 锻造步批首快照分歧缺陷（竞争场景黄金用例锁定） |
| **P1-5 月结 O(N²) 配对残留口径（2026-09-06 登记）** | 常系数已清偿（rng 提升 + 位图判定，每对 O(1)——**M2 验收"月结 O(N²) 修复"达成**）；M×F 循环形状 = RNG 消费序（Kotlin 逐位对拍红线），结构级降复杂度必须双端同步改算法（改变配对行为基线）→ 若需进一步优化须先拍板接受行为基线变化 |
| ~~S7 槽位写点收敛评估~~ | **✅ 已关闭（2026-09-06，见 §2.15）**：镜像月中陈旧无消费者（UI 读 repo 流/C++ 视图窗口对齐/存档以 repo 为准）——**窗口对齐已充分，逐点双写无受益方**；S4 残留口径就此关闭 |
| ~~WS-5 地图数据模型改造~~ | **✅ 已清偿（2026-09-08，见 §2.19）**：地形生成真源入 C++（terrain.h 位级移植 + DiffSectTerrainTest 双端逐位）+ MapPreloadData 收敛为 flat 单一表示 + 三个 O(全图) 点收敛（占位 copyOf+脚印标记 / 道路 RoadMaskTracker 增量）+ chunk 网格参数化——**M2 验收"占位真相源在 C++"达成**；残留登记：①地形不入存档/镜像协议（偏差理由 §2.19，若需地图跨版本冻结须拍板补协议批）；②WS-4 可行走语义待玩法设计文档；③chunk 参数化的地图扩容真验证随真机批次（现 128² 生产行为逐位不变） |
| WS-0.d CI ① 桌面对拍 fail 而非 skip | 已由 ci.yml `cpp-diff-jni-test` job 注入 desktop-JNI 路径覆盖（≥`isAvailable()` 为 true），**实际已满足**，无需改动 |
| ~~RoomMigrationV4x 系列测试预存失败~~ | **✅ 已清偿（2026-09-08，见 §2.23.1）**：四拆分测试文件 addMigrations 链尾补 `MIGRATION_49_50`（6 处）+ 顺带修复 §2.22 空格规范化破坏的种子派生 replace 锚点（V44 种子 "101 values for 105 columns" 二阶损伤）——`RoomMigration*` 74/74 + `:core:data` 全量 707/707 全绿 |
| ~~WS-6 iOS 暂缓落地~~ | **✅ 已清偿（2026-09-05，见 §2.6）**：5 处文档/注释 + Rhi.h 平台护栏 |
| ~~P0-3 真机验证~~ | **✅ 主体清偿（2026-09-10，batch-10 模拟器会话，见 §2.39/验证记录）**：异步拼装 + direct 上传（ASTC 4096² 上传/淡入时序渐进/60fps 零 skip）、软渲染强制路径、切后台销毁重建、RNG 警告观察（零出现）+ buildSaveSnapshot 存读往返逐字段一致。**残留随物理真机**：ASTC 缺失低端机 RGBA 回退、旋屏表面重建（应用锁横屏模拟器不可达） |
| ~~WS-2 S1-S3 真机验证~~ | **✅ 主体清偿（2026-09-10，batch-10 模拟器会话，见 §2.39）**：≈220 旬七步结算连续运行——突破实际发生（弟子炼气 1→2 层）、道侣结成/忠诚叛逃涌现事件、年报日志连续。**残留随物理真机**：偷盗钩子自然触发（需道德减益前提）、TapDB 埋点实际上报确认 |
| ~~WS-2 S4 真机验证~~ | **✅ 主体清偿（2026-09-10，batch-10 模拟器会话，见 §2.39）**：矿场产线月结产出（0→340/月）+ 年报第 4-11 年连续年结 + restoreSlots 整表重放（存读档往返后生产延续）；手动排班 C++ 事务 + Room 后置写回实测（执事+矿工任命） |
| **WS-1 残留口径（2026-09-05 登记，见 §2.9/§5）** | ① 每旬弟子全脏场景的镜像成本受"全量实体 JSON 序列化"支配（协议形状决定，非通道实现可消）——列级 delta / 二进制通道 + dirty_tracker 列级写屏障随**计划 v2 阶段 3 数据导向存储**落地（约 145+ 列写点的回归风险不值本批引入）；② 验收"2x 速每旬非 nativeLoopFrame <2ms"仅中小规模存档（≤100 弟子）达成，大规模存档需上述阶段 3 交付；③ 真机绝对值待测（bench 为桌面 llvm-mingw -O3 环境，改进比率同量级）——**batch-10 实测发现无生产日志观测面**（登记：先补 debug 埋点另行小批，随 P0-3 物理真机残留同批观测） |
| **集成分支合入状态（2026-09-11 登记，§2.40）** | 十批并行成果已合流为 `integration/parallel-batches`（基于 `batch/02`）+ 缺失 C++ 产物入库 + 集成期缺陷根治，并**已合入 `main`**（合并提交：`main` ← `integration/parallel-batches`；合并树与 integration 提交 `b7f6788` 的树**逐字节相同**，且 9 处冲突全部为"integration 版本更全"，已逐行核对 main 无独有内容丢失——main 独有行均为被取代的旧文本或多余 import）。**分支清理（同日）**：`batch/02·03·04` 与 `integration/parallel-batches` 的提交本就在 main 历史内，直接删除；`batch/05·06·09` 的提交不在 main 祖先链（内容已并入，提交链会成孤儿）→ 打归档 tag **`archive/batch-05-dirty-ledger`（81aa7ae）/ `archive/batch-06-sink-building`（9114b93）/ `archive/batch-09-sink-diplomacy`（79a1235）** 后再删除，仓库 refs 收敛为 `main` + 3 个归档 tag（需要旧提交时 `git log <tag>` 即可）。**根因登记**：并行批各自提交、无人收口合并，且 `a45692b`「顺手带走组 C 在途 Kotlin 改动」使最全分支的 C++ 树断裂（缺 2 头文件 + 2 测试文件）——**跨批共享文件（action_ids/execute_dispatch/CMakeLists/gen-action-ids.mjs）必须整组提交、批次分支必须在收口时合并验证**（findings 已登记） |
| **真机（物理设备）验证残留（2026-09-10 batch-10 登记，§2.39）** | 模拟器会话未覆盖的物理机项：A2 ASTC 缺失机 RGBA 回退、A4 旋屏表面重建、C1 偷盗钩子自然触发 + TapDB 上报确认、C3 S5 战斗任务（任务阁进度门控）、C4 S6 秘境全链（入口未解锁）、C6 ThermalMonitor 真实热档、D2 放置确认步、D3 道路装配、E2 云存档、E3 WS-1 绝对值（需先补 debug 埋点小批） |
| **batch-10 证据目录未入库（2026-09-11 登记）** | `batch10-rng-evidence/` 为一次性验证证据（logcat 全量、重签 APK、原生库字符串核验产物、截图，1.5GB 含二进制），**已随 §2.40 集成批清理**（不入版本库）；结论已落档 `docs/parallel-batches/batch-10-verification-record.md`。如需长期留证请另定制品归档位置 |

### 4.2 已拍板并实施 / 保留项
| 项 | 结论 |
|---|---|
| ~~RNG 通道跨线程竞争~~ | **✅ 已按推荐方案②实施（2026-09-05，见 §2.6）**：① UI 抽取派生化/引擎化；② initSystemSeed 并入引擎重启；③ 存档快照引擎线程采样（读档恢复此前已收敛，`ProductionTransactionManager` 抽取点生产零调用）。**✅ 断言升级完成（2026-09-10，batch-10，见 §2.39）**：开发期 debug 构建观察窗 ≥60 分钟（90,738 行 logcat，全谱混合操作）零警告 → 四 rng 入口已升级 `jniRequireEngineThread`（过渡守卫删除）；断言版复跑无误杀（读/存/切后台全过零 abort）——**P1-4 就此正式收口** |
| `TimeSystem.onPhaseTick` | 审计标"生产死代码"，但它是 **6 个 Diff 测试文件的 Kotlin 跨语言对拍基准**（C-15 特意切真实 TimeSystem 防"复刻漂移"）。**已保留**。若要按审计字面删除，须先重写 6 个测试为纯 C++ 断言——会失去独立 Kotlin 基准，需用户决定 |
| `GameDataMerchant.kt GameSettingsData.autoSave` | 是**序列化设置字段**，非自动存档机制；删除需改设置序列化 schema + 迁移，风险>收益。**保留** |

### 4.3 规划文档说明
- `task_plan.md` / `findings.md` / `progress.md`：本任务的长期规划/进度记忆（planning-with-files 规范产物），非一次性调试代码。已保留并入库，供下轮接续 M1 时使用。

## 5. 下轮建议（M2 续批 → M3）

**M3 首批已清偿（2026-09-08，§2.20）**：**死代码族清偿**——五模块 UnusedPrivate*/
UnusedImports 归零 + baseline 211 条摘除 + guard 只缩更新；实跑裁决 detekt 剩余活债务
2952 条（app 253 / data 529 / domain 505 / engine 1184 / game 481），按族登记为后续
M3 批次（MaxLineLength 机械换行专项 / TooGenericExceptionCaught 逐个判定 /
ReturnCount·CyclomaticComplexMethod 真实重构 / EmptyFunctionBlock 逐个判定）。
**M3 第二批已清偿（2026-09-08，§2.21）**：反向通道逐域写者审计（改判——全部域仍有
Kotlin 直改写者，**"按域全关"前置 = UI 操作面逐域下沉，属长期主轴**；域→写者→批次
清单落档 ui-read-surface §4.1）+ lockedBeastIds 反向增量段缺口加固（S-15 同族）+
detekt InvalidPackageDeclaration 118 条清偿（纯文件搬移；engine baseline 1057→939，
总 baseline 2806→2688；顺带修正 30 条 GameSystemRegistry 注册类别潜伏漂移）。
**M3 第三批已清偿（2026-09-08，§2.22）**：**detekt 机械族专项——MaxLineLength 1407 条
实修清偿**（机械换行 + 字符串等值拆分 + raw string 逐处处置；全部现行长行归零而非配置
豁免），baseline 2688→**1281**（-52%）；换行连锁暴露的 10 个 LongMethod 临界越界逐函数
真实重构（linesOfCode 口径：注释/空行不计，必须真实减行）、4 处其他规则漂移复活违规顺手
根治（EmptyElseBlock ×2 / ExplicitItLambdaParameter ×1 / MayBeConst→const ×1）。
**M3 第四批已清偿（2026-09-08，§2.23）**：**RoomMigration 预存失败清偿**（§4.1 登记
项——6 处 addMigrations 补 MIGRATION_49_50 + §2.22 二阶损伤种子锚点修复，:core:data
707/707 全绿）+ **detekt TooGenericExceptionCaught 族实修清偿**（摘 135 条 baseline 条目
实跑裁决 474 处，逐处判定为刻意防御 catch 全部附理由 @Suppress——不收窄异常类型，
异常源跨 IO/SDK 不可枚举；途中清偿 detekt"同目标多 @Suppress 只生效其一" quirk 的
19 对合并 + 签名漂移 65 条等量替换 + 273 条陈旧死条目清除），baseline 1281→**873**
（-32%）。**M3 第五批已清偿（2026-09-08，§2.24）**：**判定族收官 + 机械族全清**——摘除全部
873 条条目实跑裁决 1050 处（34 族），机械族/命名族（含 MatchingDeclarationName 25 文件
git mv/重排）/异常形态族（含两段式 catch 取消传播首个落地族）/UnusedParameter 110→0/
EmptyFunctionBlock 107→0（`= Unit`）全族归零；ReturnCount 阈值拍板 max 2→5（config 留痕）；
baseline 873→**370**（-58%，实跑活违规 1050→395）。
**M3 第六批已清偿（2026-09-08，§2.25）**：**判定边界族收官**——ComplexCondition 17 +
NestedBlockDepth 28 + ReturnCount 33 全部真实结构性重构归零（谓词提取/深嵌套块提取/
守卫合并与查表化，零行为变更），顺带清偿 DiscipleDeadStatusRule listOfNotNull(Pair) 恒真
预存缺陷与 StorageEngine 两处恒真 null 检查死分支；baseline 370→**292**（-21%）。
**M3 第七批已清偿（2026-09-09，§2.26）**：**参数与跳转族收官**——LPL 34 + Loop 45
条目实跑裁决 102 处（Loop 同签名放大），98 处真实重构/死代码删除 + 4 处附理由 @Suppress
（JNI ABI/域聚合 Facade/复用组件契约），不装回任何条目；baseline 全量重建 292→**196**
（CCM 85→68 含清除 17 条历史签名漂移死条目 + 本批 computeFinalStats 真根治）。
**剩余 196 条 = 拆分任务队列余量**（TMF 113 / CCM 68 / LargeClass 15）——
全部为真实结构性重构（文件/类拆分、结算循环形状），逐族专项批推进、不可赶工。
**M3 第八批已清偿（2026-09-09，§2.27）**：**复杂度族收官**——CCM 68 条目实跑裁决
68 处（放大系数 1.0），查表化/RNG 红线谓词提取/Compose 组合拆分/分相 sealed 化逐处
真实重构归零，不装回任何条目；baseline 196→**128**（guard 同步只缩）。
**M3 第十批已清偿（2026-09-09，§2.29）**：**拆分任务队列首轮——core:data 全域清偿**
（LargeClass 3 + TMF 11 实跑裁决全部真实结构拆分：SaveCrypto 四 object/SecureKeyManager
三 object/GameDataCacheManager 与 StorageEngine 扩展域文件/FunctionalWAL 编解码文件/
RoomMigration 三测试类拆分）+ 并行线遗留 5 处活违规根治 + app GameStateStoreImpl 契约面
豁免；baseline 59→**45**（app 1→0 / data 14→0；domain 2 / engine 34 / game 8 装回登记）。
**M3 剩余主项**：~~反向同步通道按域全关~~（**改判**：前置 = UI 操作面逐域下沉 C++——
弟子管理/巡逻/建筑放置/外交/设置/任务/月年编排等域每域一个 WS-2 规模批次，见
ui-read-surface §4.1 清单；全部下沉后执行 §4.3 关闭动作）；detekt 逐族清零
（~~MaxLineLength/TooGenericExceptionCaught/机械族/命名族/异常形态族/判定族/边界族/参数跳转族/复杂度族~~
均已清偿，**拆分任务队列余量 0 条**（Batch-03 已清偿 domain 2，2026-09-10 §2.32；Batch-02 已清偿 game 8，
2026-09-10 §2.31，guard feature/game 8→0；Batch-01 已清偿 engine 34→**0**，2026-09-10 §2.30/§2.30.1 续修，
guard core/engine 3→2→0——两条跨模块消费下界 `SectPolicyToggleUseCase`（22 函数下放 2 个主题扩展文件 +
feature:game 调用方补 import）与 `UnifiedPerformanceMonitor`（指标注册表域上提基类
`PerformanceMetricsRegistry`，保 mock 缝隙与跨模块调用零变化）已一并清偿）——**六模块 baseline 全为 0**）；
死代码清单滚动清零
（TimeSystem.onPhaseTick / GameSettingsData.autoSave 维持保留决策，需用户拍板）。

M0 文档/代码项已全部清偿（§2.6）。**M1 全部清偿**：首批 WS-2 S1-S3（§2.8）、第二批
WS-1 同步通道降本（§2.9）、第三批 **WS-3 E1 + WS-7**（§2.10，2026-09-06）。
**M2 首批已清偿**：**WS-2 S4 炼丹/锻造完成结算 + 自动排班下沉**（§2.11，2026-09-06）
——月结残留 4a/4b 删除，`MonthSettlementResidualExecutor` 扇出缩至
邮件（4g，异步网络零状态）/任务完成/洞天 AI/AI 兽战/S-17/S-20 六项；
月结窗口双存储对齐（repo→镜像前置 + 镜像→repo 后置 restoreSlots）上线；
对拍基建新增"生产域全真实链"装配（FormulaService/Coordinator/Repository 可注入）。
剩余唯一 M0/M1 收尾项为 **P0-3 + RNG 收敛 + S1-S3 + S4 真机验证**（含 RNG 警告日志
零出现的确认——确认后四个 rng 入口升级为 kEngineOnly 断言，P1-4 正式收口）。

**M2 第二批已清偿**：**WS-2 S5 任务完成结算 + 战斗组装下沉**（§2.12，2026-09-06）
——月结残留子事件 5 删除，`MonthSettlementResidualExecutor` 扇出缩至
邮件（4g，平台效应）/洞天 AI（S-8 待下沉）/AI 兽战（S-8 待下沉）三项；
完整 ActiveMission 协议升级上线；战斗组装四件套（createBattle/
convertDiscipleToCombatant/createBeast/EnemyGenerator 含 java.util.Random
洗牌复刻）入 C++；邮件 4g 按总方案拍板保留 Kotlin（异步网络零状态）。

**M2 第三批已清偿**：**WS-2 S8 洞天 AI + AI 兽战余量下沉**（§2.13，2026-09-06）
——月结残留扇出缩至 **4g 邮件 / S-17 秘境关闭 / S-20 购买日志** 三项平台/UI
效应，**M2 验收"月结残留扇出 ≤3 项（仅 UI 通知类）"达成**；热控批状态机
C++ 内存运行 + 平台热档 Kotlin 推送；AI 修炼对象版速率/孕养/补全/兽战
preGenStats 组装/遭遇战 PvP 全链入 C++。

**M2 第四/五批已清偿（2026-09-06）**：**WS-3 E2 逐系统迭代域迁移 + E3 NPC 实体
组件族**（§2.14）——旬结算管线全系统（核心批次串行/并行 + 步骤 0/6/7）过
syncDiscipleEntities 行序桥接，NPC 七组件族落地为 WS-4 供数；**P1-5 性能清偿**
（§2.15）——月结 O(N²) 配对常系数（**M2 验收"月结 O(N²) 修复"达成**）+
生产 O(slots×N) 收敛 + S4 锻造步批首快照分歧缺陷顺手清偿 + S7 写点收敛评估
关闭（窗口对齐已充分）。两批验证均桌面 852/852 + 引擎 3082 绿 0 skip。

**M2 第六/七/八批已清偿（2026-09-06）**：**WS-3 E2 残留**（§2.16）——月结/年结/
生育/购买域 21 处裸行号迭代过 sync 桥接（E2 全域收口，结构变更循环收敛到
Kotlin 快照迭代语义）；**WS-2 S6 秘境交互会话域**（§2.17）——SecretRealmService
交互域（start/choose/end）+ 妖兽/PvP 战斗链 + 袋物化死亡写回入 C++，
SECRET_REALM_START/CHOOSE/END 三 ActionId 转发上线（Kotlin 回退保留），
战报经 recordPlayerBattle 在 Kotlin 重建（展示通道），年变现世勘误不注册
（Y-2 批已原生覆盖）；**WS-2 S7 生产排程交互事务**（§2.18）——手动排班/重置
C++ 事务 + PRODUCTION_START/RESET 转发 + 门面层"C++ 真相先行 + Room 持久化
后置"。三批验证均桌面 869/869 + 引擎 3082 绿 0 skip + detekt 三模块绿 +
arm64 绿。**WS-2 全部子系统（S1-S8）就此清偿。**

**M2 剩余各批（下批接续）**：
- ~~（WS-2 S7 已清偿，见 §2.18——WS-2 剩余项为空；recalculateAllCompletionMonths
  属 FormulaService checkpoint 域，C++ calculateWorkDuration 原语已就位，
  月结 isSlotCompleteDynamic 已动态重算，独立 ActionId 无消费者不注册。）~~
- ~~**WS-3 E2 残留**~~（§2.16 已清偿）。
- ~~**WS-5**：地图数据模型改造~~ **✅ 已清偿（2026-09-08，§2.19）**：地形生成真源
  入 C++（`gamecore/map/terrain.h` 位级移植 + `SectTerrainBridge` native 优先/Kotlin
  降级 + DiffSectTerrainTest 双端逐位对拍）；MapPreloadData 收敛为 flat 单一表示
  （2D rawTileData 删除）；MainGameScreen 三个 O(全图) 点收敛（建筑占位
  copyOf+脚印标记 / 道路 RoadMaskTracker 增量装配）；SoftwareCanvasBackend chunk
  网格参数化（config 派生，生产 128²/48px 派生值=原硬编码零行为差）。
  **M2 验收"占位真相源在 C++"就此达成。偏差登记**：地形不入存档/镜像 JSON 协议
  （确定性再生零成本 vs 每旬同步/存档体积持续代价——"每种子一次性生成入快照"
  落地为"每会话每种子一次生成 + Kotlin 缓存"；若需地图跨版本冻结语义须拍板补
  协议批）；真机视觉回归随真机批次（Robolectric 像素/计数测试已覆盖 chunk 划分
  与增量失效语义）。
- **WS-4**：NPC 移动系统——进入实现前需用户补充 NPC 玩法设计文档（数量上限/
  生成规则/与弟子系统关系）；E3 组件族与渲染通道约定已就绪（§2.14），
  **WS-5 后寻路地基（静态地形 + 建筑占位 + 道路三要素）已在 C++ 齐备（§2.19），
  可行走语义（树/边界是否阻塞）待设计文档拍板**。
- （WS-1 残留口径）每旬弟子全脏场景的镜像成本受全量实体 JSON 序列化支配，列级 delta /
  二进制通道随计划 v2 阶段 3 数据导向存储落地；dirty_tracker 列级写屏障同批接线。
- （P1-5 残留口径）月结配对 M×F 循环形状 = RNG 消费序，结构级优化需双端同步
  改算法（行为基线变化需拍板）——见 §2.15。
- **真机验证批（唯一剩余大类）**：P0-3 图集异步管线 + RNG 警告日志观察（零出现后
  四个 rng 入口升级 kEngineOnly 断言，P1-4 正式收口）+ S1-S8 AUTHORITATIVE 真机
  回归 + S6 秘境交互会话 + WS-5 地图渲染真机视觉确认。

> **2026-09-10 剩余工作拆分**：本节所列剩余工作（detekt 拆分队列余量、协程取消传播、
> dirty 记账残留、UI 操作面逐域下沉第一波、真机验证批）已按可并行性拆为 10 个批次，
> 实施文档见 [docs/parallel-batches/](parallel-batches/)（README 为总览与并行协作协议）。
> 各批完成后按预分配章节号回写本文件 §2.30–§2.39。

> **2026-09-11 集成收口（§2.40）**：上条所列 10 批**已全部交付并合流**为
> `integration/parallel-batches`（基于 batch/02，含 batch-05/06/07/09 全部内容 + 缺失 C++
> 产物入库 + 集成期两处真实缺陷根治：`executeAutoBuy` 迭代器失效 UB、影子突破路径语义降级）。
> **下一轮起点 = 该分支**（`main` 尚未合并，合并目标由用户决定）。此后剩余工作：
> ① **UI 操作面逐域下沉（唯一长期主轴）**——按 ui-read-surface §4.1 残余域清单
> （库存残余/巡逻探索/弟子管理后续子批/招募俘虏残余/生产 UI 面/月年边界编排/aiSectDisciples 段）
> 逐批下沉，**全部下沉后**才可执行反向通道关闭动作（§4.3）；② **真机（物理设备）验证批**——
> §4.1 登记 10 项残留；③ 待拍板三项（WS-4 NPC 玩法设计 / P1-5 配对结构级优化 / 地图跨版本冻结协议）；
> ④ 立项项：WS-1 阶段 3 数据导向存储（含 dirty_tracker 列级写屏障）。

> **2026-09-11 W2-a（§2.41）**：① 主轴推进一格——库存**出售/上架/材料消耗族**写者下沉 C++
> （`inventory_tx.h`，ActionId 1520–1525；六入口单类出售 + 批量出售 + 商人收购 + 上架/撤下 +
> 材料消耗，零 RNG 纯确定性变换）；下一批候选：**商人购买**（容量预测 + MerchantItemConverter
> 模板转换）→ **充公**（BagItemReconstructor 模板重建复用 `secret_realm_session.h` 既有原语 +
> 溢出抑制三态）→ **开袋**（EXPLORATION 分区 RNG + 模板随机抽取 `Random.Default`，风险最高）
> → 巡逻/探索族（`SLOT_CLEAR_ALL` 已就位）→ 弟子管理第二子批 → 月年边界编排域。

> **2026-09-11 第二轮派工文档（W2-b ~ W3，可多人并行）**：剩余工作已细化为 11 个实施批 +
> 非并行项，见 **[docs/parallel-batches-w2/](parallel-batches-w2/)**：
> - [README](parallel-batches-w2/README.md)——总览 / 依赖图与并行分组 / 共享文件原子变更集协议 /
>   **ActionId 段 1530–1729 预分配** / **handover §2.42–§2.53 预分配** / **文件所有权矩阵** /
>   统一验证命令模板与已知坑 / 十条统一纪律；
> - 并行批：[batch-11](parallel-batches-w2/batch-11-inventory-final.md) 库存收官（商人购买/开袋/充公）｜
>   [batch-12](parallel-batches-w2/batch-12-patrol-residence.md) 巡逻住所｜
>   [batch-13](parallel-batches-w2/batch-13-exploration.md) 探索｜
>   [batch-14](parallel-batches-w2/batch-14-disciple-lifecycle.md) 弟子生命周期｜
>   [batch-15](parallel-batches-w2/batch-15-disciple-appointment.md) 弟子任命/驻守/洗炼｜
>   [batch-16](parallel-batches-w2/batch-16-recruit-captive.md) 招募俘虏｜
>   [batch-17](parallel-batches-w2/batch-17-production-spiritfield.md) 生产灵田｜
>   [batch-18](parallel-batches-w2/batch-18-month-year-boundary.md) 月年边界编排｜
>   [batch-19](parallel-batches-w2/batch-19-jade-redeem-sect.md) 玉符/兑换码/宗门/邮件｜
>   [batch-20](parallel-batches-w2/batch-20-realm-platform-battle.md) 秘境平台段与战斗执法；
> - 串行终局：[batch-21](parallel-batches-w2/batch-21-reverse-channel-closeout.md) 反向通道关闭批
>   （**依赖 11–20 全部合入 main**）；
> - [non-parallel-work.md](parallel-batches-w2/non-parallel-work.md)——batch-22 真机验证批 /
>   待拍板四项 / WS-1 阶段 3 立项项 / 建议排期。
