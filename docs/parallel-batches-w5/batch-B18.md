# 批次 B18 — 回滚臂删除批（R2 灰度期满 + 各批遗留清理合并批）

> 来源：`docs/parallel-batches-w5/handover-closing-batch-2026-09-20.md` §4 第 4 项
> （本文件为该条的落地定义）；方案 §7.3 收口批登记。
> 台账批次总表：`docs/parallel-batches-w5/dispatch-ledger.md`。
> 前置 = W5 收口批（组 A–F）已提交 + 组合门绿。

## ⛔ 到期判据（**硬前置，须显式核验；未满足不得开工**）

> **一个完整发版周期经过 + 零回滚事件（RenderMetrics / 崩溃快照可查证）。**

- 五条灰度臂于 **2026-09-18/19** 建立（R2 传输切换臂、R2.3 投影臂、R2.4 eventFeed 臂、
  R2.2 列级导出臂、R3 场景臂），**截至 2026-09-20 尚未经历任何发版** ⇒ **当前不得执行**。
- 开工前须给出**可查证证据**：① 发版周期起止（`version.properties` 版本号变更记录）②
  该周期内零回滚事件（`RenderMetrics` 回滚计数器 / 崩溃快照 / Bugly 记录）。
- **未附证据即开工 = 验收打回**（本批删除的是灰度期间的"退路"，提前删除会丧失回滚能力）。

### 判据缺口核查（2026-09-20 实测，**开工前必须解决**）

| 判据 | 当前状态 | 实测依据 |
|---|---|---|
| ① 发版周期经过 | ❌ **不满足** | `version.properties` 仅 1 次提交（`c13d65157`，W2 集成收口），仍为 `versionName=4.01.14` / `versionCode=4114`；**版本号从未变更** ⇒ 无"周期起止"可查证 |
| ② 零回滚事件可查证 | ⚠️ **证据面缺失** | 仓库内**不存在** `RenderMetrics` 回滚计数器实现。全仓 `rollback` 命中均为业务语义（`FunctionalWAL` 事务回滚 / `TransactionRngRollbackTest` / `GameStateStoreRollbackTest`），**无灰度回滚计数埋点** ⇒ 该证据面须先补埋点，或改用崩溃快照 / Bugly 作替代面 |

**五条臂建立时点（git 实测）**——距核查时刻约 44 小时，未历发版：

| 臂 | 引入提交 | 时间 |
|---|---|---|
| R2.2 镜像通道 protobuf | `6b3354708` | 2026-09-18 17:45 |
| R2.3 字段级 typed 应用 | `4d053b9be` | 2026-09-18 21:10 |
| R2.3 弟子行 typed 直读 | `b7b68b4ba` | 2026-09-18 21:25 |
| R2.3 GameViewStore 投影态 | `6fd9fe03d` | 2026-09-18 21:42 |
| R3.2 drawAllTiles 换轨 | `242440778` | 2026-09-19 08:30 |
| R3.8 spawn + 渲染集成 | `3a7d0e3de` | 2026-09-19 19:06 |

**删除影响面（供开工估量）**——旗标生产分支引用点（不含测试/旗标定义本身）：

| 旗标 | 生产引用点 | 守卫测试文件 |
|---|---|---|
| `mirrorProtobufTransport` | 5 | 1 |
| `gameViewProjection` | 11 | 4 |
| `dirtyColumnExport` | 5 | 2 |

**开工前置动作（建议顺序）**：
1. 补灰度回滚埋点（`RenderMetrics` 回滚计数器或等效），或书面确认改用崩溃快照 / Bugly 作证据面；
2. 发一次版本（`version.properties` 涨号 + 发布），开始累计周期；
3. 周期内零回滚 ⇒ 产出证据 → 方可开工。

## 任务

### 0. 臂级施工进展（2026-09-20 晚用户两次显式指令开工——到期判据缺口知情覆盖）

| 臂 | 内容 | 状态 |
|---|---|---|
| 臂 1 | 传输臂退役：`mirrorProtobufTransport` 旗标 + JSON 分发分支 + `nativeSetDirtyExportProtobuf` 端口删除；`exportDirtyJson` 保留作桌面对拍 golden | ✅ 已提交 `df6b70d5a`（ctest 1556/1556 + 单进程 1553/1553 exit=0 + SurfaceGuard 6/6） |
| 臂 2 | 投影臂退役：`gameViewProjection` 旗标删除 + `GameEngine` 三块 UI 消费恒投影 + 旧全量往返臂转测试 golden | ✅ 已提交 `efba3ee72` |
| 臂 3 | 列级臂退役：`dirtyColumnExport` 旗标 + `nativeSetDirtyExportColumn` 端口删除，恒列级导出（**异构锁存 `columnExportBlocked_` 保留**）；`exportDirtyColumnJson` 保留作 golden；解码侧 `decodeView` 缺省值即生产形态（恒列级补丁） | ✅ 本轮实施 |
| 臂 β | 场景臂（`sceneStoreRender`）退役 | ✅ 已提交 `4ed1d7c24`（组合门 20m58s/319 任务 + ctest 1553/1553 + 单进程 1553/1553 exit=0 + NDK arm64 8m51s + JNI 87/87） |
| 臂 γ / 吸收项 | `upsertsJson` typed 化 / G5 / b03 遗留 / Room 死列 / 注释收口 | ⬜ 未动 |

**臂 2 实施要点**（施工卡见 `docs/parallel-batches-w5/handover-b18-wip-2026-09-20.md` §2）：
- 生产侧：`GameEngine.resourcesHeader / configEcho / eventLog` 三块删 if/else 回滚分支，恒
  `gameViewStore.*`；`StateSyncService` 恒字段级应用 + 恒馈送投影；KDoc 残留全清。
- 守卫侧：`MirrorConsumerSurfaceGuardTest` 旗标断言转**反向断言**（不得回流）；
  `GameDataFieldPatchGuardTest` 两臂对照 → **测试侧 golden 夹具**（`goldenRoundTrip`，
  即"删臂后守卫不得失去对照面"纪律的落地）；`GameViewStoreGuardTest` 删旗标关闭用例、
  补"镜像馈送恒推进投影"正向用例；`MirrorSegmentProjectionBenchTest` 单臂化（去对照臂计时，
  保留全等断言 + 趋势数字打印）。
- 门禁：JNI 基线 `91 → 90`（臂 1 删端口后实测值，随臂 2 提交同步下调）。

**臂 3 实施要点**：
- 生产侧（C++）：`setDirtyExportColumn` / `dirtyExportColumn()` / `columnLevelDirtyExport_`
  成员删除；`exportDirtyProto` 恒列级（`columnExportBlocked_` 异构锁存**保留**——它是
  月/年/旬边界外写入路径的防漏报正确性机制，**不是回滚臂**）。
- 生产侧（Kotlin）：`GameCoreBridge.nativeSetDirtyExportColumn` decl 删、
  `GameEngineCoreAuthoritativeOps` 推送调用点删、`NativeEngineFlag.dirtyColumnExport` 旗标删、
  JNI 实现删（`GameCoreBridge.cpp`）。
- 解码侧重构：`GameViewMirrorCodec.decodeView` 缺省值翻转为**生产形态**
  （`includeDiscipleJson = false` + `discipleRowsAsPatches = true`）；
  `StateSyncService.applyDirtyProto` 改为单参重载 + `internal applyDirtyProtoWith(decode)`
  注入点（golden 对照臂用）。
- 守卫侧：`DiffColumnExportMergeConvergenceTest` 的"全量信封→全行应用"臂**转 golden**
  （`goldenApplyFullRows` 显式传 `discipleRowsAsPatches = false`）；
  `MirrorConsumerSurfaceGuardTest` 补 `dirtyColumnExport` 反向断言 +
  "不得由旗标决定 `discipleRowsAsPatches`"断言；
  `GameViewDiscipleProjectionTest.jsonViaTree` 显式传参（原依赖旧缺省值）；
  `MirrorSegmentProjectionBenchTest.measureColumn` 去旗标 try/finally。
- 门禁：JNI 基线 `90 → 89`（臂 3 删端口后实测值）。
- **踩坑登记**：`decodeDiscipleDelta` 的 `when` 分支顺序为「补丁 > JSON 树 > typed 全行」，
  故**只翻 `includeDiscipleJson` 缺省值不足以表达形态**——`discipleRowsAsPatches = true`
  会屏蔽 JSON 树分支，使对照臂静默失效（3 个用例红）。凡依赖旧缺省值的对照面**必须显式传
  `discipleRowsAsPatches = false`**。另：`StateSyncService` **不得 import `proto.gameview.*`**
  （该 import 面被 `MirrorConsumerSurfaceGuardTest` 锁死在 codec + 行投影两处），
  故 `applyDirtyProtoWith` 的解码器形参用**全限定名**声明。

**臂 β 实施要点**（`sceneStoreRender` 场景臂退役）：
- 侦察结论：该臂的删除面**比旗标本身宽**——`setFadeAlpha` 与 `drawAllTiles`（Kotlin JNI
  + C++ 实现）**仅**由旧臂 `renderLegacyDrawAllTilesPath` 调用（新路径的淡入经
  `drawFrame` 的 `fadeAlpha` 参数、崖壁经 `sceneSetCliffLayout`），故删旧臂后这三个端口
  一并成为死面。反之 `drawIslandCliffs`/`drawRect`/`drawSprite` 仍有独立 ABI 价值
  （场景测试驱动 + 叠加层手绘），**保留**。
- 生产侧（Kotlin）：`NativeEngineFlag.sceneStoreRender` 旗标删；
  `VulkanRenderBackend.renderFrame` 双路分支收敛为单路 `renderSceneStorePath`；
  `renderLegacyDrawAllTilesPath` / `renderLegacyOverlayPath` 两函数整体删除，
  **其四个逐 rect 辅助函数一并删除**（`drawSelectionHighlight` / `drawDemolishMarker` /
  `drawPreviewHighlight` / `drawGridOverlay`）——原拟"保留作对照实现"，但 detekt
  `UnusedPrivateMember` 判其确无消费者 ⇒ 诚实删除；连带清理 `SELECTED_DATA_STRIDE` /
  `MIN_SCALE` / 叠加层全套视觉常量（`SpriteAtlasDef` / `DemolishHighlightMark` import 随之
  成孤儿，一并删）。**网格线公式守卫并未失去对照面**：Canvas 与 C++ 两处仍在（原为三处）。
- 生产侧（JNI/Kotlin）：`NativeBridge.drawAllTiles`（17 参数）与 `setFadeAlpha` 端口删。
- 生产侧（C++）：`NativeBridge.cpp` 两个 JNI 实现删（`drawAllTiles` 约 5.2KB 大块 +
  `setFadeAlpha`）；`g_fadeAlpha` 保留（`drawFrame` 写、崖壁/地图层读）；`g_mapBatcher` /
  `submitMapBatchCommon` / `decorSkipActive` / `drawCliffLayerInternal` 保留（单路仍用）。
- 守卫侧（**关键纪律：删臂后守卫不得失去对照面**）：`SceneOverlayProtocolGuardTest`
  的"逐 rect 调用点只在回滚臂内"改**单向 + 反向断言** ——
  ① 新路径体内不得逐 rect；② 六个别名函数（两旧臂 + 四辅助）**不得回流**
  （`functionBody(...).isEmpty()`）；③ `sceneStoreRender` 字符串不得出现在后端源码。
  网格线行范围守卫从"Vulkan/Canvas/C++ 三路"缩为"**Canvas/C++ 两路**"并更名
  `...on remaining arms`（Vulkan 侧实现已删，强行保留断言只会锁住死码）。
  `SceneUpdateChannelTest` 的旧路径计数从"活体调用点算术"转**冻结对照基线**
  （`LEGACY_FIXED_PORTS_FROZEN`，值不变，注释标明已删除），"数百量级 vs <10"的
  对比陈述因此仍可复现。
- 门禁：JNI 基线 `89 → 87`（`NativeBridge.kt` 49 → 47）。桌面 GTest 无新增用例改动
  （`scene_equivalence_test` 的"旧路径臂"是**非 JNI 等价驱动**，不依赖已删端口）。
- 语义边界复核：`farViewGroundQuad` 与 `sceneStoreRender` 原为**正交**旗标
  （前者决定地面层绘制形态，后者决定数据通道），删后者后前者语义不变——
  `NativeEngineFlag` 中对二者的交叉引用已改写为单向说明。

### 1. 回滚臂删除（本批主体）
- `drawAllTiles` 旧臂退役；
- `sceneStoreRender` / `gameViewProjection` / `mirror`（JSON 回退分支）退役；
- 灰度旗标（`NativeEngineFlag.mirrorProtobufTransport` / `dirtyColumnExport` /
  `gameViewProjection` 等）与**回滚臂生产分支**一并删除（守卫测试对照臂保留为 golden 夹具）；
- **每删一条臂前**：确认其对拍测试（`Diff*`）已改为"对照臂转 golden 夹具"形态，
  否则删臂后守卫失去对照面。

### 2. 吸收清单（各批遗留，逐项登记来源）

| 来源 | 项 | 说明 |
|---|---|---|
| b02 发现 8③④ | `upsertsJson` 族 typed 化 | **族 = 三字段**（`CollectionChange.upsertsJson` + `JsonFieldChange.valueJson` + `DiscipleRow.storageBagItemsJson`；2026-09-20 看护复核补全）；proto **只增字段**；codec 单点切读 + 等价守卫 + 桥重建；设计/分期（A1 标量面 / A2 嵌套面）见 `b18-solution-2026-09-20.md` §3.1 |
| 方案 R2.3 残余①② | `upsertMirrorRow` 列级收窄、store 侧 `assembleAll` | G2 分档基线后的残余成本中心（§7.3 已登） |
| G5 | `BattleSystem` → golden 夹具退场 | **口径勘误（2026-09-20 看护实测）**：生产回退点 = **6 文件 11 调用点**（遭遇/秘境/妖兽防守/任务双分支/巡逻 3 段/AI 宗门 3 段）；「3 执行器」= Phase/Month/Year **结算执行器**（方案 :169，非 MissionSystem 分支，属远期程序）。**判归建议 A = 零删除**（`authoritative` 门 = OFF kill-switch 投影非灰度臂；`?:` = 容错+测试兜底）——**待拍板**，见 `b18-solution-2026-09-20.md` §2 |
| 方案 B09 残余③④ | C++ rest 域标脏细粒度化、弟子列表块迁投影 | — |
| b03 遗留 | `month_settlement.h` `indexById` 收口 | **口径勘误（2026-09-20 看护实测）**：10 行命中（1 注释 + 1 using + 8 调用），**待收口 2 处**——`:1842` 同表达式两次重建、`:1358-1375` 循环体 3→1 次；`:1727`/`:1773`/`:2304` 本就正常**勿动**（A 组 UAF 根治后的同族收口；跨迭代重建是正确性机制，不得提循环外） |
| 收口批 C 组判归 | `battleTeam` / `aiBattleTeams` **Room 死列清理** | 删列需 **schema migration**，须**存档回归单独走批**（不得与回滚臂删除混装） |
| 收口批 F 项 | `build-atlas.mjs` / `scene_uv_tables.h` 历史注释措辞收口 | — |
| 收口批 G2 | `MirrorSegmentProjectionBenchTest` 残余①② 断言口径 | 随分档基线一并重定 |
| 收口批 §4⑤ | `aiBeastEncounterTargets` 死臂 | **已确证死臂（2026-09-20 核查）**：全仓（Kotlin + C++）该表**零插入者**——仅 `GameEngineExplorationNativeOps.kt:178` 门判 + `ExplorationServiceBeastRaidOps.kt:128` 读取 + `:179-180` 删除，**无任何 `+`/`put`/`copy(aiBeastEncounterTargets = ...含新增)` 写入** ⇒ 表恒空 ⇒ 两处门判恒 false ⇒ 遭遇战路径（`resolveEncounterPath` / `resolveBeastEncounterIfAny`）整条死代码。按 B18 口径清理（**勿顺手删**：修复后该表保留现值，一旦有人接上插入者即恢复工作——见收口批 C 组"值保留"判归） |

### 3. 必须遵守的纪律

- **分组独立 commit**：回滚臂删除 / `upsertsJson` typed 化 / Room 死列（含 migration）/
  注释收口 **各自独立**，勿混装（对拍归因红线）。
- **Room 死列删除须附存档回归**（旧档 → 新 schema migration → 读写往返逐字段等价）。
- **删臂后守卫不得失去对照面**：对照臂 = golden 夹具（非删除）。
- 删臂每步后：受影响测试套全绿 + 组合门（`testReleaseUnitTest + detekt +
  compileReleaseKotlin + lintRelease`）。

## 红线（违者验收打回）

- **存档 schema 变更（Room 死列）必须单独走批 + 存档回归**，不得夹带回滚臂删除；
- **协议 JSON 面**：typed 化只允许 **proto 追加字段**，既有字段号与语义零变更；
- 删臂前必须**有发版周期零回滚**的可查证证据（见上"到期判据"）；
- 每子项独立 commit；`CHANGELOG` 若产生玩家可见变更须记（本批预计零玩家可见变更）。

## 验收门

1. 桌面对拍 `Diff*` 全绿（对照臂 = golden 夹具）；
2. 组合门绿；受影响 Kotlin 套绿；C++ 有改动则桌面 GTest 全绿 + NDK arm64 构建通过；
3. 存档回归（Room migration）逐字段等价；
4. 到期判据证据附于完成报告（发版周期 + 零回滚）。

## 参考

- `docs/parallel-batches-w5/handover-closing-batch-2026-09-20.md` §4（剩余工作清单）
- `docs/parallel-batches-w5/b02-findings.md`（发现 8③④ / 11 附带）
- `docs/parallel-batches-w5/b03-findings.md`（`month_settlement.h` 遗留）
- `docs/native-engine-refactor-plan-2026-09-17.md` §7.3（收口批登记 + G2 拍板）
