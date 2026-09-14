# Task Plan: C++ 迁移整改执行（M0-M3）

<!--
  依据：docs/cpp-migration-audit-report.md（独立审计）+ docs/cpp-migration-implementation-plan.md（总方案，2026-09-04）
  性质：长期整改任务，跨多周。本文件是磁盘工作记忆，随阶段更新。
-->

## Goal

按总方案四里程碑（M0止血清残 → M1减税+试点 → M2主轴成型 → M3收敛）完成 Kotlin→C++ 引擎迁移整改：C++ 收敛为唯一模拟真相源、游戏全面使用 ECS（NPC 移动为首个真实负载）、逐批下沉残留执行器、清空空存档残留、清偿 detekt 债务、删双 ABI/无效分包。

## Current Phase

**集成收口（2026-09-11）**：handover §5 剩余工作拆分的 10 个并行批次（detekt 拆分余量 / 协程取消传播 /
dirty 记账摘除 / UI 操作面下沉第一波 06-09 / 真机验证批）**已全部交付并合流**为单一可编译树
（分支 `integration/parallel-batches`，基于 batch/02；含 batch-05/06/07/09 全部内容 + 缺失 C++ 产物
入库 + 集成期两处真实缺陷根治：`executeAutoBuy` 迭代器失效 UB、影子突破路径语义降级）——
**已合入 `main`**（合并树与 integration 提交逐字节相同）。下一轮唯一长期主轴 = **UI 操作面逐域下沉**（剩余域清单见
`docs/ui-read-surface.md` §4.1），全部下沉后才可执行反向通道关闭动作（§4.3）；另剩真机（物理设备）
验证批 + 三项待拍板（WS-4 NPC / P1-5 配对结构级优化 / 地图跨版本冻结）+ WS-1 阶段 3 立项。

## 里程碑地图

| 里程碑 | 时间 | 内容 | 验收标准 |
|---|---|---|---|
| M0 止血清残 | 第1-2周 | WS-0 全部 + WS-6 | 死导出清零；自动存档残留全项清理；对拍CI强制；图集不碰主线程；arm64单架构；文案与实现一致；baseline守卫上线 |
| **M0 现状** | —— | 全部清偿 ✅（仅剩真机验证项） | —— |
| M1 减税+试点 | 第3-6周 | WS-1 + WS-2(S1-S3) + WS-3(E1) | 库存全量导出消失；反向通道gameData段-90%；突破/丹药/自动装备下沉且对拍全绿；E1保序验证通过；detekt≥1个baseline文件清零 |
| **M1 现状** | —— | **全部清偿 ✅**（WS-2 S1-S3 + WS-1 + WS-3 E1 + WS-7，2026-09-05/06） | M1 验收标准全部达成 |
| M2 主轴成型 | 第7-14周 | WS-2(S4-S8) + WS-3(E2-E3) + WS-4 + WS-5 | 月结残留扇出≤3项；NPC在地图行走+可交互；占位真相源在C++；月结O(N²)修复 |
| **M2 现状** | —— | **S4 ✅ §2.11 / S5 ✅ §2.12 / S8 ✅ §2.13（残留扇出 ≤3 达成）/ E2+E3 ✅ §2.14 / P1-5 ✅ §2.15 / E2 残留 ✅ §2.16 / S6 ✅ §2.17 / S7 ✅ §2.18 / WS-5 ✅ §2.19（2026-09-08，占位真相源在 C++ 达成）**。桌面 911/911 + 引擎全绿 0 skip + feature/game 全绿。**剩余：WS-4（NPC 行走，用户玩法设计文档阻塞——唯一未达成验收项）+ 真机验证批** | 剩余：NPC 在地图行走+可交互（WS-4，阻塞于设计文档）；其余验收全部达成 |
| M3 收敛 | 第15周起 | WS-2收尾 + WS-7收尾 | 反向同步通道按域全关；detekt baseline清零；全仓无双实现并行活路径；死代码清单清零 |

## Phases

### Phase 0: 资料理解与规划文件建立
- [x] 阅读审计报告 + 总方案（2026-09-04）
- [x] 建立 task_plan/findings/progress 三文件
- **Status:** complete

### Phase 1: 执行路线规划交付
- [ ] 将 M0-M3 + WS-0~7 + S1-S8 拆解为可执行、可中断、可回滚的批次
- [ ] 明确每批验收标准与"先对拍再删Kotlin"红线
- **Status:** in_progress

### Phase 2: M0 止血清残（WS-0 全部）
- [x] WS-0.a 安全项：退出文案 / 删 autoSave 配置键 / 注释改"保留"（已落地+编译过）
- [x] WS-0.a 高危项：autoSaveIntervalMonths 两表 Room 删除（V50 迁移，PRAGMA 动态重建）+ proto 旧档兼容（@Ignore+@Transient；已生成 50.json）
- [x] WS-0.a 死代码链：GameStateStoreImpl markDirty/consumeDirty + _stateDirty/_discipleDirty 整链删除（已核实零读者；保留每个 _updateVersion.value++ 活逻辑；编译+状态/仓库测试通过）
- [x] WS-0.a 防复发护栏：docs/architecture.md + CODE_WIKI.md 加"纯手动存档"权威记录
- [x] WS-0.c 包体两项：arm64-v8a 单架构 + 移除纹理分包开关（已落地+编译过）
- [x] WS-0.b 死导出：isRendererReady / nativeAdvance / nativePollEvents+pollEventsJson（已删 Kotlin+C++双侧+测试断言，externalNativeBuildRelease+compileReleaseKotlin 通过）
- [x] WS-0.b 注释纠偏：GameEngineNativeOps / NativeSurfaceView / gamecore-CMakeLists / game_core.h / SaveLoadSaveDelegate / GameEngineAdminOps（已改）
- [ ] WS-0.b TimeSystem.onPhaseTick（保留——被 6 个 Diff 测试用作 Kotlin 跨语言对拍基准；删它破坏时间验证基线，需用户拍板）
- [x] WS-0.d P1-7：release `debugSymbolLevel "SYMBOL_TABLE"`（build.gradle）
- [x] WS-0.d CI ③：detekt baseline 只缩不增守卫（detekt-baseline-count.guard + ci.yml 对比步骤，本地逻辑已验证 PASS）
- [x] WS-0.d P0-3：图集拼装移出主线程 + RGBA 2048 封顶 + DirectByteBuffer（2026-09-05 落地：buildAtlas→buildAtlasAsync 两段式流水线；toRgbaByteArray→encodeBitmapToRgbaBuffer；uploadTexture/uploadGroundTexture 改 DirectByteBuffer + 删 ByteArray 死导出；NativeSurfaceViewTest +7 用例 21/21 绿）
- [x] WS-0.d P1-4：JNI debug 线程断言（2026-09-05 落地：owner tid 记录 + 21 个 kEngineOnly 入口 debug abort / release 零开销；34 入口全部分类成文，Kotlin 契约同步重写；遗留 RNG 通道跨线程竞争待拍板）
- [x] WS-0.d CI ①（桌面对拍 fail 而非 skip）：已由 ci.yml `cpp-diff-jni-test` 注入路径覆盖，无需改动
- [x] WS-0.d P0-3(VulkanBackend 清屏纯黑)：已核实 VulkanBackend.cpp:2352 已是 {0,0,0,1}
- [ ] WS-6：清理 docs/注释中"iOS 可复用/双端"表述（clock.h:12、road_system.h:23、gamecore/CMakeLists.txt:6、cpp/CMakeLists.txt:59、docs/cpp-engine.md）
- [ ] P0-3 真机验证：Vulkan RGBA 回退 + 软渲染 + surface 旋转重建（Robolectric 无法覆盖 native 上传）
- **Status:** M0 WS-0 全项落地（2026-09-05），剩 WS-6 文案 + 真机验证

**已验证（2026-09-04）**：
- `:core:data:testReleaseUnitTest --tests RoomMigrationTest --max-workers=1` → 62/62 全绿
- `assembleRelease` → BUILD SUCCESSFUL（含 native 编译/R8/lint lintVitalReportRelease 无错误/打包）

**已验证（2026-09-05 追加批）**：
- `:app:compileReleaseKotlin` + `:app:externalNativeBuildRelease`（arm64-v8a，libnative-renderer/libnative-game-core 重建）→ BUILD SUCCESSFUL
- `:feature:game:testReleaseUnitTest`（含 NativeSurfaceViewTest 21/21，新增 7 个 P0-3 用例）→ 全绿
- `:app:testReleaseUnitTest --tests ...state.* --tests ...repository.*` → 全绿

### Phase 2.6: M0 收尾（WS-6 + RNG 方案② + P1-4 勘误）
- [x] WS-6 iOS 文案收敛（5 处文档/注释 + Rhi.h 平台护栏）
- [x] RNG 跨线程竞争收敛方案②（①UI 抽取派生化/引擎化 ②initSystemSeed 并入引擎重启 ③buildSaveSnapshot 引擎线程采样 + debug 警告守卫过渡）
- [x] P1-4 勘误：loopStart/loopOnRestart 重分类 kAnyThread + owner 重锚机制（§2.7）
- **Status:** complete（2026-09-05；仅剩 P0-3 + RNG 真机验证）

### Phase 3: M1 减税+试点（WS-1 + WS-2 S1-S3 + WS-3 E1）
- [x] **WS-2 S1 突破下沉（2026-09-05）**：relative_gift.h（亲属赠送 SYSTEM RNG 移植）+ 偷盗钩子复用 judgeSingleTheftCandidate + 入口快照改 id 键控 + AUTHORITATIVE core 模式完整七步启用 + executeResidual 删除 + 突破埋点观察器（UI 通知类残留）
- [x] **WS-2 S2 丹药下沉（同批）**：C++ processAutoPills 已有 + 偷盗钩子接线；对拍新场景（道德减益丹全链）
- [x] **WS-2 S3 自动装备下沉（同批）**：C++ auto_gear.h 已有 + core 模式启用；对拍新场景（袋内实例装配）
- [x] 桌面对拍全绿：782/782 C++ gtest（+21 新用例）+ 42 个 Diff*Test 251 用例 0 skip（+3 新场景）；顺手清偿 M0 autoSaveIntervalMonths C++ 导出面协议漂移
- [x] **WS-1 同步通道降本（2026-09-05，handover §2.9）**：① tryExecuteNative 库存操作改 exportDirty 字段级回读（查询类动作零镜像，全量兜底保留）② 反向 gameData 段改字段级 dirty 集（锚点不变量"锚点 ⊆ C++ 已知值"，三推进点 + 前向镜像不推进的保守过期方向；C++ applyReverseDirty 改补丁语义）③ DirtyTracker 基线 JSON 树缓存（两次全量序列化+深拷贝→单次，bench -22~40%；列级写屏障留阶段 3 DOD）+ syncFromNative 单次解析 + applyDisciples 行级精确应用（upsertMirrorRow O(log n) 探测）④《UI 读取面清单》docs/ui-read-surface.md（镜像合法上限 + 分域关闭依据；实测反向段 -98.7%）
- [x] **WS-3 E1 实体模型与保序验证（2026-09-06，handover §2.10）**：`syncDiscipleEntities` 保序校验+惰性重建（不变量"View 序 == Store 行序"）+ 桥接规范五条成文 disciple_component.h；`PhaseCoreBatchSystem::run` 真用 World——核心批次行地址取自 DiscipleRef 组件；8 个新用例（存储 erase 保序压缩实证/漂移重建/生产同构调度路径 vs 串行逐位一致/过期实体集重建）；桌面 795/795 + 引擎 3078（0 失败 0 skip，42 Diff 类）+ NDK arm64 构建全绿
- [x] **WS-7 detekt core/ui baseline 23 条清零（2026-09-06，handover §2.10）**：实跑裁决 7 陈旧摘除 + 4 死代码删除（DialogState 三函数/showPrice）+ 8 修代码（Provider 属性化/GameRoute map+守卫测试/AtlasPacker·EquipmentSprite 单出口/注册器+场景文件拆分）+ 3 合理压制（附理由）；新文件 SpriteResRegistry.kt / SceneSpriteRes.kt / GameRouteDialogTypeMappingTest.kt；core/ui detekt 0 违规，守卫 core/ui=0
- **Status:** complete（M1 全部清偿 2026-09-06；S1-S3 + WS-1 2026-09-05；真机验证项除外）

### Phase 4: M2 主轴成型（WS-2 S4-S8 + WS-3 E2-E3 + WS-4 + WS-5）
- [x] **WS-2 S4 炼丹/锻造完成结算 + 自动排班下沉（2026-09-06，handover §2.11）**：
  C++ profession.h + production.h + 月结接线 + 20 单测；月结窗口双存储对齐
  （alignMirrorFromRepository 前置 + restoreRepositoryFromMirror 后置）；
  残留执行器 4a/4b 删除；ProductionSlot 协议默认值漂移清偿；
  对拍基建生产域全真实链（Harness/InMemory port/共享断言族）+ 新场景
  DiffProductionSettlementTest；桌面 815/815 + 引擎 3079 绿 0 skip +
  回归 840/55 + native/detekt 全绿
- [x] **WS-2 S5 任务完成结算 + 战斗组装下沉（2026-09-06，handover §2.12）**：
  C++ mission_completion.h（三分支结算/奖励生成/战斗组装四件套含 java.util.Random
  洗牌复刻）+ 完整 ActiveMission 协议升级 + beast_config.h + S-19 漏网修正
  （RngRandomAdapter）+ applyMissionRewards 稠密 id 守卫修复 + 残留子事件 5 删除
  （扇出缩至 3 项）；桌面 830/830 + 引擎 3082 绿 0 skip + 3 对拍新场景 + 全绿验证
- [x] ~~WS-2 S5~~（见上行）
- [ ] WS-2 S6：秘境探索模拟下沉 C++（SecretRealmService 1278 行最大单体）
- [ ] WS-2 S7：生产排程余量下沉（手动排班/惰性收获/重算 checkpoint）
  ——**写点收敛评估已完成（2026-09-06，handover §2.15）**：逐点双写无受益方，
  窗口对齐已充分；余量为交互事务 C++ 下沉
- [x] **WS-2 S7 前置：O(slots×N) 修复（P1-5 部分，2026-09-06 handover §2.15）**：
  rowOf O(1) 查找 ×4 处 + 材料余量索引化 + 配方排序缓存；顺手清偿 S4 锻造步
  批首快照分歧缺陷（竞争场景双槽用例锁定）
- [x] **WS-2 S8 洞天 AI + AI 兽战余量下沉（2026-09-06，handover §2.13）**：
  C++ ai_sect_ops.h（热控批状态机 + AI 修炼对象版速率/突破/熟练度/孕养 +
  补全链复用 Y-4c + 兽战 preGenStats 组装/遭遇战 PvP + 宗门等级同步）+
  nurture_constants.h 包含环断链 + 热控批量上界 Kotlin 推送（平台效应）+
  残留子事件 6/9 删除——**月结残留扇出 ≤3 项（M2 验收达成）**；
  桌面 842/842 + 引擎 3082 绿 0 skip + 全绿验证
- [ ] WS-2 S8 残留口径：O(sects²) 攻打决策复杂度（AI 宗门数小，登记随真机批次观测）
- [x] **WS-3 E2 逐系统 View 迁移（2026-09-06，handover §2.14）**：核心批次串行版/
  步骤 0 装备/步骤 6 丹药/步骤 7 突破迭代域全部切 syncDiscipleEntities +
  View<DiscipleRef> 行序；结算入口增 World& 形参；**残留**：月结/年结域约 20 处
  裸行号迭代（不在 E2 命名系统清单）登记后续批次
- [x] **WS-3 E3 NPC 实体组件族（2026-09-06，handover §2.14）**：ecs/npc_component.h
  七组件（NpcId/Position/Velocity/Path/PathIndex/SpriteId/AnimState）+ 助手 +
  渲染通道快照行约定 + 8 用例——WS-4 供数就绪
- [x] **P1-5 月结 O(N²) 常系数修复（2026-09-06，handover §2.15）**：伴侣配对
  rng 提升 + 位图判定（循环形状=RNG 消费序不变；结构级修复需双端同步改算法，
  登记残留口径）——**M2 验收"月结 O(N²) 修复"达成（常系数 + 逐对 O(1)）**
- [ ] WS-4 NPC移动系统（**被阻塞**：需用户补 NPC 玩法设计文档；WS-5 后寻路地基
  （静态地形+建筑占位+道路三要素）已在 C++ 齐备，可行走语义待设计文档拍板）
- [x] **WS-5 地图数据模型改造（2026-09-08，handover §2.19）**：terrain.h 位级移植 +
  SectTerrainBridge 门面（native 优先/Kotlin 降级）+ DiffSectTerrainTest 双端逐位 +
  MapPreloadData flat 单一表示（2D rawTileData 删除）+ 三个 O(全图) 点收敛
  （applyBuildingOccupancy copyOf+脚印 / RoadMaskTracker 增量装配）+ chunk 网格
  参数化（config 派生，生产 128²/48px 零行为差）——**M2 验收"占位真相源在 C++"
  达成**。偏差登记：地形不入存档/镜像 JSON 协议（确定性再生零成本 vs 每旬同步
  持续代价；地图跨版本冻结语义如需须拍板补协议批）
- **Status:** in_progress（M2 引擎侧 + WS-5 全部清偿 2026-09-08；剩余 WS-4（用户阻塞）+ 真机验证批）

### Phase 5: M3 收敛（WS-2 收尾 + WS-7 收尾）
- [x] ~~反向同步通道按域全关~~ → **2026-09-08 审计改判**：逐域写者审计证明全部域仍有
  Kotlin 直改写者（约 265 个 update 调用点、15+ 域 UI 操作面）——**前置 = UI 操作面
  逐域下沉 C++（长期主轴，非收敛批可完成）**；域→写者→批次清单落档 ui-read-surface §4.1；
  审计途中发现并加固 lockedBeastIds 反向增量段缺口（S-15 同族，见 handover §2.21.2）
- [x] detekt baseline 清零——死代码族 ✅（§2.20）+ InvalidPackageDeclaration 118 条 ✅（§2.21）+ MaxLineLength 1407 条实修 ✅（§2.22）+ TooGenericExceptionCaught 474 处实修 ✅（§2.23）+ **判定族收官+机械族全清 ✅（§2.24：实跑 1050 处裁决，机械/命名/异常形态/UnusedParameter/EmptyFunctionBlock 全族归零 + ReturnCount max 2→5 拍板）**——baseline 2688→**370**（-87%）；剩余 395 处全部为真实结构性重构族（TooManyFunctions 113 / CCM 85 / Loop 70 / LongParameterList 34 / ReturnCount 33 / NestedBlockDepth 28 / ComplexCondition 17 / LargeClass 15）= M3 第六批拆分任务队列
- [x] M3 第六~八批（拆分任务队列）逐族收官 ✅：判定边界族 CC 17+NBD 28+RC 33（§2.25，370→292）→ 参数与跳转族 LPL 34+Loop 45（§2.26，292→196）→ **复杂度族 CCM 68（§2.27，2026-09-09，196→128）**；余 TMF 113 / LargeClass 15（逐族专项批，不可赶工）
- [x] ~~RoomMigrationV4x 8 例预存失败~~（§2.23.1 ✅：addMigrations 补 M49_50 + §2.22 二阶损伤种子锚点修复，:core:data 707/707 全绿）
- [ ] CancellationException 传播专项（§2.23 登记）：~90 处 suspend/launch 上下文 catch(Exception) 未前置取消分支
- [ ] 死代码滚动清单清零（TimeSystem.onPhaseTick / GameSettingsData.autoSave 维持保留决策，需用户拍板才能动）
- **Status:** in_progress（M3 第一~八批完成 2026-09-09：死代码族 / 审计改判+加固+搬移 / MaxLineLength / RoomMigration+TooGeneric / 判定族收官+机械族全清 / 判定边界族 / 参数与跳转族 / **复杂度族 CCM 68**——baseline 总量 3075→**128**（-96%）；剩余 = TMF 113 + LargeClass 15 结构性重构 + CancellationException 专项 + 反向通道关闭的 UI 操作面下沉主轴）

## Key Questions
1. ~~ECS 迭代序保序验证（dense迭代序 ≠ DiscipleStore 行序）~~（**已解除 2026-09-06**：syncDiscipleEntities 每旬机械校验 + 保序压缩实证 + 对拍全绿，E2 迁移前置红线解除）
2. NPC 玩法设计文档（数量上限/生成规则/与弟子系统关系）——WS-4 实现前需用户补充
3. Room 迁移旧档升级路径测试覆盖
4. ~~自动存档 markDirty 链是否存在隐藏读者~~（已清查：零读者，整链已删）
5. ~~RNG 通道跨线程竞争收敛方向~~（2026-09-05 方案②落地；真机日志零出现后升级断言）

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 决策1 选A：C++收敛为唯一模拟真相源 | 终态每旬只走 nativeSettlePhase + 前向diff；反向同步通道终态删除（先通道降本再逐批下沉） |
| 决策3：游戏全面使用ECS（方案X：DiscipleStore留存储后端，ECS做系统调度与实体关系层） | 更纯粹的方案Y破坏确定性对拍/脏追踪/存档三套基建，风险大一个量级 |
| 决策4：iOS暂缓 | 仅做"Android为主，核心可移植"文案收敛 + gamecore零Android依赖CI护栏 |
| 决策7：arm64-v8a 单架构 + 移除纹理分包开关 | APK native 约-40%；纹理分包从未生效 |
| 决策8：detekt baseline 只许缩小 3075 行逐批清偿 | 债务必须实际解决，不得压制 |
| P0-3：图集上传留在主线程，只把拼装搬后台 | C++ g_renderer 无锁裸指针，渲染线程每帧并发进入；消灭的是拼装（CPU+内存峰值）而非上传 |
| P1-4：断言仅 debug 构建（NDEBUG 未定义），release 擦除为零开销 | 审计明确警告"写错会生产误 abort"；debug 首犯即崩足可归因 |
| P1-4：rng 四入口标 kAnyThread 不加断言 | 实测 GameRngManager 被 ViewModel 注入、NativeBackedRng 委托主线程/存档线程进入——加断言即开发期误 abort；竞争收敛待拍板 |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Robolectric 下 View.post 不执行（未 attach 的 View 进 run queue 永不消费） | 1 | 图集流水线上传段改显式 `Handler(Looper.getMainLooper())`（与 attach 状态解耦，生产语义不变） |
| Kotlin 编译错：internal 函数暴露 private 嵌套类返回/参数类型 | 1 | `AtlasPayload` 改 internal |

## Notes
- 每批下沉固定流水线：C++实现/启用 → ActionId接线 → 桌面对拍验证 → 删Kotlin路径与回退 → CI绿。
- 禁止在 Kotlin 路径删除前对拍未全绿时下手。
- 无测试 / 无 Migrateion 的改动视为未完成（见 CLAUDE.md 13.3）。
- 所有 commit 引用中文信息（CLAUDE.md 约定），任务完成后一次性提交。
