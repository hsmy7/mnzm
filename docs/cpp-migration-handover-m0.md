# C++ 迁移整改对接文档（M0–M3 已交付；当前主轴 = 反向通道逐域收尾与删除）

| 项 | 内容 |
|---|---|
| 文档性质 | 面向后续开发者/接手者的**对接文档**：本轮已完成哪些、如何验证、遗留哪些待办（需专项或拍板） |
| **阅读顺序（省时）** | ① §4 遗留待办 + §5 下轮建议 + **§6 主轴剩余（随机源治理）** ← 只有这三节是"接下来要做什么"；② §3 当前门禁基线；③ §2 是已完成批次的压缩存档（每节 3–5 行的硬规格/红线/登记），**按追加顺序排列（批号非严格递增，如 §2.53 之后为 §2.43/§2.51b）**，仅在追溯某批结论时按编号查阅 |
| 依据文档 | [cpp-migration-audit-report.md](cpp-migration-audit-report.md)（独立审计）+ [cpp-migration-implementation-plan.md](cpp-migration-implementation-plan.md)（总方案，2026-09-04） |
| **活跃方案** | [ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md)（随机源治理，2026-09-14 已拍板选项 2 根治；阶段 1 = batch-21 前置） |
| 里程碑 | M0 止血清残 ✅ → M1 减税+试点 ✅ → M2 主轴成型 ✅（WS-2 S1–S8 全部子系统 + WS-3 ECS 化 + P1-5 性能 + WS-5 地图真源入 C++）→ M3 收敛 ✅（detekt 逐族清零、baseline 2688→**0**、死代码族清偿、反向通道逐域关闭机制落地）｜**当前主轴**：反向通道逐域收尾（[w3 十三批](parallel-batches-w3/README.md) → 通道删除）；另余真机（物理设备）验证批、WS-4 NPC 移动（待玩法设计文档）、WS-1 阶段 3 数据导向存储（待立项） |
| 实施日期 | 2026-09-04 ～ 2026-09-15（M0 → M3，共 60 余批）；**各批日期与批号见 §2 小节标题**，门禁数值见 §3 |

---

## 1. 背景一句话

Kotlin→C++ 游戏引擎迁移被审计定性为"**真实但未完成的迁移**"（总评 5.9/10）：模拟核心与地图渲染已真迁 C++ 且 C++ 为真相源；但残留自动存档痕迹、若干只有声明无实现/无调用的死导出、双 ABI 包体、以及掩盖在 detekt baseline 下的债务。本批为 M0 止血清残。

## 2. 本轮已完成并验证
### WS-0.a 自动存档残留清理（决策2，全项）
批次: M0 主体 | ActionId: 无 | 产物: GameDatabaseMigrationsV50.kt + 迁移支持、GameData.kt/SectPolicyStateEntity.kt（字段 @Ignore + @Transient）、GameStateStoreImpl.kt（markDirty/consumeDirty 死链删除）、SettingsTab.kt 文案
硬规格: Room V50 用 PRAGMA 动态重建两表（**不能**复用 GAME_DATA_CREATE_SQL——那是 v29 历史基线，会丢 21 个后续新增列）；config 键 autoSaveIntervalSeconds/autoSaveDebounceMs 删除；退出文案改"未保存的进度将会丢失"
红线: @Transient 段语义——旧档 lenient 解码可读、新档不写
登记: 存档为纯手动（产品决策 2026-09-04）写入 architecture.md + CODE_WIKI 作防复发护栏
### WS-0.b 死导出与注释纠偏
批次: M0 主体 | ActionId: 无 | 产物: 删除 NativeBridge.isRendererReady / GameCoreBridge.nativeAdvance+JNI 导出 / nativePollEvents+pollEventsJson（Kotlin 声明 + C++ 导出 + 实现 + 测试断言）；注释纠偏 6 处（保留 C++ GameCore::advance——time_system_test.cpp 用）
### WS-0.c 包体两项（决策7）
批次: M0 主体 | 硬规格: abiFilters 仅留 arm64-v8a（native 约 -40%）；删除从未生效的 bundle.texture.enableSplit
### WS-0.d 部分（渲染/CI/性能止血）
批次: M0 主体 | 硬规格: release ndk debugSymbolLevel SYMBOL_TABLE；astcenc 缺失由静默跳过改 GradleException fail；新增 detekt-baseline-count.guard + ci.yml "Detekt baseline must not grow"；P0-3 清屏色已核实无需改（VulkanBackend.cpp:2352 已纯黑）
## 2.5 追加批（2026-09-05）：WS-0.d 剩余 P0-3 + P1-4 清偿
批次: M0 追加批 | ActionId: 无 | 产物: 新 AtlasAsyncPipeline.kt（+AtlasPayload 三态载体）、NativeSurfaceView.kt 改造、NativeBridge.kt/.cpp（uploadTextureDirect/uploadGroundTextureDirect + lockDirectPixels 统一校验）、SectAtlasAssembler.kt 改名（ATLAS_BITMAP_MAX_EDGE/atlasBitmapScale）、SectMapViewport.kt 接线
硬规格: buildAtlasAsync 两段式（prepareAtlas 后台重活 / uploadAtlas 主线程仅一次 GPU 上传）；ASTC 上传失败自动回退 allowCompressed=false 纯 RGBA 拼装（递归深度恒 ≤2）；ARGB→RGBA 改 DirectByteBuffer 逐行读取（行缓冲约 8KB，不再整图 IntArray + 3×16MB 峰值）；RGBA/软渲统一 2048 封顶（ASTC 走 4096 KTX 不受限）；图集真正可用才开始 fadeIn；compressedAtlasLoader 保留为同步组合（测试注入点语义不变）
取舍: 上传不搬后台——C++ NativeBridge 的 g_renderer 是无锁裸指针，渲染线程每帧 beginFrame/draw/submit 并发进入，上传必须留在与渲染互斥的主线程；P0-3 消灭的是"拼装"（CPU 密集 + 大内存峰值）不是"上传"
坑: 主线程跳转用显式 `Handler(Looper.getMainLooper())` 而非 `View.post`（未 attach 的 View 会把 runnable 排到下次 traversal，守卫测试实测暴露）；`handleSurfaceDestroyed` 中断拼装线程但**不 join**（CPU 密集循环，join 等于换地方阻塞，结果由纪元守卫丢弃）
### WS-0.d P1-4 JNI 桥层 debug 线程断言（全项）
红线: JNI 桥线程契约——nativeInit 记录 owner tid（= GameEngine 单线程调度器），debug（NDEBUG 未定义）下 21 个 kEngineOnly 入口首句 jniRequireEngineThread（非法线程 LOGE 含入口名/owner/当前 tid → abort，把静默数据竞争变成"首犯即崩、可归因"），release（NDEBUG）守卫擦除为空 inline 保证零开销；34 个入口逐条分类 kEngineOnly（21 守卫）/ kAnyThread（13 跨线程端口）成文，Kotlin 契约头同步重写
### P1-4 途中发现（重要，留待专项拍板）
登记: rng 四入口（nativeRngNextInt / SnapshotPartition / RestorePartition / InitSeed）**今天就有主线程/存档线程进入，是真实数据竞争而非契约违规**（GameRngManager 被 ViewModel 注入 + NativeBackedRng 委托 + C++ PCG 分区态非原子裸成员）⇒ 只能标 kAnyThread 不能加断言（加了就是开发期误 abort）；收敛方案（RNG 通道加锁，或强制 RNG 抽取全走引擎线程）需单独拍板，不在本批范围（后由 §2.6 方案②收敛、§2.39 断言升级收口）
## 2.6 收尾批（2026-09-05）：WS-6 文案收敛 + RNG 跨线程竞争收敛（方案②）
批次: M0 收尾批 | ActionId: 无 | 产物: 文档/注释 6 处（两处 CMakeLists / `cpp-engine.md` / `Rhi.h` 平台护栏 等）；改 9 文件——UI 抽取派生化 3 处（两个 ViewModel + `GameEngineCoordination`）、存档快照引擎线程化 4 处（`SaveLoadViewModel` / `PersistenceFacade` / `GameEngine.buildSaveSnapshot` / `GameEngineSaveOps`）、桥层与 RNG 线程契约 2 处（`GameCoreBridge.cpp+kt` / `GameRngManager` 过渡警告）
硬规格: 方案②三小项——① UI 抽取派生化（deriveTrialSeed 不再消费全局 BATTLE 分区）+ 血炼抽取移入 launchOnEngine；② initSystemSeed 并入引擎重启（restartGameInternal 内 mapSeed 生成后播种，对齐 createNewGame"播种在引擎线程、生成世界前完成"）；③ 挂起版存档快照统一走 GameEngine.buildSaveSnapshot()（withEngineContext 内 flushYearlyOpsQueue + getStateSnapshot，年变 flush/存档前校验/玉符 checkpoint/RNG 导出/状态快照整体进引擎线程）
取舍: buildSaveSnapshot 用**成员函数而非扩展函数**——扩展函数体在 MockK relaxed 环境下会真实执行并触达 mock 的 engineContextDispatcher（SaveLoadViewModelLoadTest 对 saveFacade 的直通 stub 语义被破坏），成员函数在 mock 环境下整方法被 relaxed 拦截
### WS-6 iOS 暂缓落地（全项）
红线: 文档/注释统一降级为"Android 为主，核心保持可移植"（清理"iOS 可复用/双端平台"表述；§0 审计修正节与历史批次记录保持原样不动历史归档）；**Rhi.h 追加平台护栏——RHI 不投入跨平台开发，新增渲染功能不得把 Android API 进一步漏进 Rhi.h**，Metal 接入指南降级为历史参考（总方案 WS-6 第 3 条）
### RNG 跨线程竞争收敛（§4.2 拍板项，按推荐方案②三小项拆开实施）
登记: 实施前实测调用面核实——读档恢复侧此前已收敛（loadData 整体在 withEngineContext 内）、ProductionTransactionManager.determineOutcome 的 SYSTEM 抽取生产零调用；真实待收敛点三处与 §4.2 预判一致；过渡护栏 = 新增 jniWarnRngOffEngineThread（debug WARN 含入口名/owner tid/当前 tid，**不 abort**；release 擦除），分类从 kAnyThread 改"kEngineOnly（收敛中）"；**护栏升级条件**——真机/开发期 debug 构建零警告出现 → 下一批把四入口升级为 jniRequireEngineThread 断言（P1-4 正式收口，已由 §2.39 完成）；若仍出现 → 存在漏网调用面，按 §4.2 对该处局部加锁
## 2.8 M1 首批（2026-09-05）：WS-2 S1-S3 残留执行器下沉（自动装备/丹药/突破 → C++）
批次: M1 首批 | ActionId: 无（生产内嵌）| 产物: 新 system/relative_gift.h、新 engine/BreakthroughAnalyticsObserver.kt；改 phase_settlement.h、game_core.cpp（onCoreSettle 改 runPhaseSettlementCore）、PhaseSettlementExecutor.kt + GameEngineCoreAuthoritativeOps.kt + GameEngineCore.kt（executeResidual/executePillsAndBreakthroughs 删除）、DiffAuthoritativeTickTest.kt
硬规格: AUTHORITATIVE 生产每旬 = C++ runPhaseSettlementCore 单次完整七步（0 自动装备 → 1-5 核心批次 ECS JobSystem 并行 → 6 丹药+偷盗钩子 → 7 突破+亲属赠送），步骤序与 Kotlin 完整版逐位一致（核心批次外均串行：RNG + 跨弟子状态依赖不可分块）；偷盗钩子复用既有 judgeSingleTheftCandidate 零新逻辑；入口弟子快照改 id 键控 map（偷盗叛逃移除行后行索引会错位）
红线: 零 RNG 序不变性——SYSTEM 每亲属恰一次 nextDouble（先于选品）、亲属查找插序即抽取序、关系分类优先级与选品五级优先逐字复刻；突破埋点经旬前后 breakthroughCounts 列差分重建事件（基线每旬 settle 前捕获，读档/重启跳变被基线吸收无需重锚）
登记: 亲属赠送 lifeEvents 为 Kotlin 运行态字段（不在快照协议）→ C++ 显式丢弃（与 overflowMail 同边界口径）；execute 完整版保留为对拍 Kotlin 基准（决策 C-15）；观察器置 core.engine 包（非 .service）以避开 EngineServiceAnnotationTest 架构守卫
测试: relative_gift_test.cpp 21 用例 + DiffPhaseSettlementTest 新增 3 场景（共 5 用例：袋内实例直接装配 / 突破后道侣赠送 / 道德减益丹触发偷盗全链）
坑: M0 遗留协议漂移顺手清偿——Kotlin 已删 `autoSaveIntervalMonths`（@Transient）而 C++ `models.h`/`json_codec` 仍导出，重建桌面对拍 JNI 后 Diff 全红；此前跑引擎单测未注入 `gamecore.jni.path`（对拍全 skip）+ CI 未跑，漂移未暴露
## 2.7 追加修正（2026-09-05）：P1-4 守卫分类勘误（loopStart/loopOnRestart 误标 kEngineOnly）
批次: M0 追加修正 | ActionId: 无 | 产物: GameCoreBridge.cpp/.kt（分类修正 + owner 重锚）、engine_loop_test.cpp +3 标志语义用例
硬规格: loopStart / loopOnRestart 重分类为 **kAnyThread 生命周期输入端口**（同 loopSetSpeed），移除守卫并注释依据；补 owner 重锚机制——EngineLoop::onLoopRestart 置位待重锚标志，桥层 nativeLoopFrame 首帧消费并重锚 owner 到新驱动线程（紧急重启 recreateGameDispatcher 会换线程，原 owner 永不更新会让新引擎线程被守卫误杀，loopFrame 后所有 kEngineOnly 入口同理）
红线: 守卫只放在可验证的契约上；release（NDEBUG 擦除）该路径本无可 abort 代码
登记: 真机复现证据（vivo OriginOS 6，debug，SIGABRT：GameEngineCore.prepareLoopStart ← nativeLoopStart ← startGameLoop ← GameForegroundService.onStartCommand）——startGameLoop 按 Kotlin 设计**可从主线程调用**（前台服务 onStartCommand / GameActivity.onResume），属守卫误杀非数据竞争
## 2.9 M1 第二批（2026-09-05）：WS-1 同步通道降本
批次: M1 第二批 | ActionId: 无 | 产物: 新 docs/ui-read-surface.md；改 GameEngineNativeOps.kt、StateSyncService.kt、dirty_tracker.h/.cpp、DiscipleTables.kt（+upsertMirrorRow）、game_core.cpp
硬规格: ① tryExecuteNative 成功后走 applyDirtyFromNative 脏优先镜像（查询类动作变更集为空=零镜像开销），脏通道不可用才 syncFromNative 全量兜底；② 反向信封按键差分（锚点 lastGameDataSentJson，剔除 rngStates），C++ applyReverseDirty 从整段替换改字段级补丁 `value.get_to(patched)`（缺键保持现值）；③ dirty_tracker 基线缓存为 JSON 树（diffToJson 只序列化当前态一次后 std::move 入缓存）；④ applyDisciples 改信封 id 行级精确应用（upsertMirrorRow 走 isAlive SparseArray O(log n)，避开 update 内 `_ids.contains` 线性导致 k≈N 全脏退化为 O(k·N)；幽灵行经 insert 的 `id in _ids` 兜底；非数字 id 显式抛错契约保留）
红线: 反向锚点不变量 = "锚点 ⊆ C++ 已知值"——推进点仅三处（importToNative 成功 / syncFromNative 成功（从解码快照**重新 kotlinx 编码**，防 C++ 整数 double 输出形式造成格式性假差异）/ sendReverseEnvelope 成功）；**前向镜像不推进**（保守过期方向：反向 diff 重发 C++ 自身值=无操作，绝不漏发）；发送失败不推进
登记: 每旬弟子全脏场景的镜像成本受"全量实体 JSON 序列化"支配（协议形状决定），列级 delta/二进制通道 + dirty_tracker 列级写屏障随计划 v2 阶段 3 DOD 落地（145+ 写点回归风险不值本批引入）；"2x 速每旬非 nativeLoopFrame <2ms"仅中小规模存档（≤100 弟子）达成
验收: 反向 gameData 段 5349B/134 键 → 70B/1 键（-98.7%）；diffToJson 每旬全脏 100/1000/5000 弟子 -24%/-22%/-22%（idle -32~-40%）
## 2.10 M1 第三批（2026-09-06）：WS-3 E1 实体模型与保序验证 + WS-7 core/ui baseline 清零——**M1 全部清偿**
### WS-3 E1：PhaseCoreBatchSystem 真用 World/View（保序验证绝对前置，全项）
批次: M1 第三批 | ActionId: 无 | 产物: ecs/disciple_component.h（+syncDiscipleEntities + 桥接规范五条成文）、phase_settlement.h（runPhaseCoreBatchParallel/PhaseCoreBatchSystem 增 World&）、game_core.cpp 注释；ecs_disciple_test.cpp +6、phase_settlement_test.cpp +2
硬规格: 不变量校验"View\<DiscipleRef\> 迭代序 == DiscipleStore 行序"（实体数一致 + 行号严格升序 0..N-1）——成立按行序返回实体表（稳态零重建零分配），被破坏（招募/死亡/叛逃后实体集漂移）则 buildDiscipleEntities 全量重建恢复 row i ↔ entity i；桥接规范五条 = 唯一权威 / 迭代前必 sync / 行地址取自组件 / 消耗 RNG 的系统必须行序迭代 / 回调内禁增删实体
红线: 核心批次全程零 RNG（步骤 1-5 纯计算），并行/迭代序不触抽取序红线；步骤 6/7（真实 RNG 消耗点）仍按行序串行不经 View；`ComponentStorage` erase 为**保序压缩**（storage.h 明示，非 swap-pop）
### WS-7：core/ui detekt-baseline.xml 23 条清零（M1 验收"≥1 文件清零"达成）
产物: 新 SpriteResRegistry.kt / SceneSpriteRes.kt / GameRouteDialogTypeMappingTest.kt（3 用例）；core/ui baseline 置空 + 守卫 core/ui=23→0
硬规格: 逐条分类清偿——陈旧条目 7 直接摘除（含 2026-08-08 `ignoreDefaultParameters: true` 后失效的 LPL ×5）/ 死代码 4 整体删除（DialogState 三件套 + ItemCard.showPrice）/ 8 类修代码消灭（ElderBonusInfoProvider 17 getter 改 val、GameRoute.toDialogType 28 分支改带参路由 when(8)+1:1 查表+**新增穷举性守卫测试**、AtlasPacker 与三条回退链单出口化、EquipmentSprite 文件拆分）/ 3 处附理由 @Suppress（非关键路径防御，沿 §2.5 惯例）
## 2.11 M2 首批（2026-09-06）：WS-2 S4 炼丹/锻造完成结算 + 自动排班下沉 C++
批次: M2 首批 | ActionId: 无 | 产物: 新 system/profession.h、新 system/production.h、新 test/production_test.cpp（20 用例）、新 DiffSurfaceAssertion.kt、新 DiffProductionSettlementTest.kt；改 `month_settlement.h` / `models.h`、`MonthSettlementResidualExecutor`（4a/4b 行删除）、生产与月结编排三文件（`ProductionProcessor` / `CultivationService` / `GameEngineCoreMonthOps`）、`DiffMonthSettlementFixture`
硬规格: 月结步骤 4a/4b 由 C++ 执行完成结算、编排末尾（步骤 8 后）执行自动排班；匹配口径逐位对齐 Kotlin（**锻造按 buildingId、炼丹按 buildingType**；SYSTEM 抽取序 = forge 槽序 → alchemy 槽序）；成功率乘区 baseProb=clamp01(skillZone+professionZone)×(1+realm+talent+policy+elder)（skillZone 用原始列值、长老加成读含天赋 Flat 的 baseStats——**仅此处**双口径）；时长 ceil(base/(1+长老+亲传)) 后政策 ×1.1（外层后写覆盖 completionMonth）；完成判定 isSlotCompleteDynamic（Checkpoint 快照法按当前政策/长老动态重算）
红线: 自动排班**零 RNG**（公式化成功率）+ name+rarity 精确求和材料检查/消耗（与 Kotlin consumeHerbs/MaterialsForRecipe 逐位一致）；溢出草稿收集后丢弃（S-18 边界）；失败零写入（校验链先行）
登记: 协议默认值漂移顺手对齐（ProductionSlot.outputItemRarity 0→1、completionPhase 0→1——被"C++ 不读写槽位默认值"掩盖至 createIdle 首次消费默认构造即暴露）；双存储对齐取"**窗口对齐**"而非修 20 个写点——前置 alignMirrorFromRepository（repo 整表写镜像）+ 后置 restoreRepositoryFromMirror（镜像整表重放，**不走 SlotStateMachine**——C++ WORKING→IDLE→WORKING 复合变更无法用单步转换表达；IO 失败仅记录，镜像已权威）；逐写点双写改造（任命/取消/收获/自愈/排班五族）回归面不值本批引入
取舍: 自动排班置月结末尾而非 4a/4b 位置——Kotlin 原版是月结事务**提交后**的异步独立事务（读月结最终状态），置末尾语义等价且消除 launch 排序不定竞态；**改进基线**：C++ 版当月即续炼（Kotlin 原版续炼实际下月生效），对拍场景规避"到期+autoRestart"组合，黄金用例锁定 C++ 行为
## 2.12 M2 第二批（2026-09-06）：WS-2 S5 任务完成结算 + 战斗组装下沉 C++
批次: M2 第二批 | ActionId: 无 | 产物: 月结残留子事件 5 删除（`MonthSettlementResidualExecutor` 扇出缩至**邮件 4g / 洞天 AI / AI 兽战**三项）；完整 ActiveMission 协议升级上线；战斗组装四件套入 C++（`createBattle` / `convertDiscipleToCombatant` / `createBeast` / `EnemyGenerator`，含 `java.util.Random` 洗牌复刻）
红线: 洗牌复刻与 Kotlin `java.util.Random` 版降序 Fisher-Yates 逐位对齐（与 `DeterministicRng` 原语是**两套不同原语**，选错即抽取序红线破裂）
登记: 邮件 4g 按总方案拍板保留 Kotlin（异步网络零状态，平台效应）；顺手修复 S-19 漏网（`Random.Default` 模板抽取）+ `applyMissionRewards` 稠密 id 守卫
验收: 桌面 830/830；引擎 3082 用例 0 失败 0 跳过；对拍 +3 场景全绿
> 说明：本节记录原缺（§2 直接从 §2.11 跳至 §2.13，外部文档仍引用 §2.12），2026-09-15 依既有 §4.1/§5 条目**补录**，内容不新增结论。

## 2.13 M2 第三批（2026-09-06）：WS-2 S8 洞天 AI + AI 兽战余量下沉 C++
批次: M2 第三批 | ActionId: 无 | 产物: 新 system/ai_sect_ops.h、新 system/nurture_constants.h、新 test/ai_sect_ops_test.cpp（12 用例）；改 month_settlement.h、GameCoreBridge.cpp/.kt、GameEngineCoreMonthOps.kt
硬规格: C++ runMonthSettlement 子事件 6/9 位执行 AI 域全链——aiComputeBatch 热控批状态机（首调相位对齐 / 时钟回退跳过 / monthsSince≥上界→批量=monthsSince）、aiProcessSectOperations（仓库清场 + 分批修炼 + 宗门等级同步 any{} 阈值链 + 成员过滤）、aiCultivationRate、突破循环、熟练度月增 36 / 孕养月增 30、aiRollMissingCategories + aiEnsureDiscipleGear + aiProcessMonthlyCultivation、兽战余量（aiPrepareDisciplesForBattle / aiCreateBattle preGenStats 妖兽分支首入 C++ / 单 AI 攻妖 / 双 AI 遭遇 PvP→胜者攻妖 / 死亡处理）
红线: AI 独立 RNG 分区（突破 nextDouble≥chance→失败 break）；熟练度/孕养常量上移叶子头断 `phase_settlement ↔ month_settlement` 包含环（nurture_constants.h），语义零变更
登记: 热控批量上界保留 Kotlin——ThermalMonitor 读 PowerManager 热状态为平台 IO，决策 12/6/3 后经新导出 nativeSetAiThermalBatchSize（kEngineOnly）推送，C++ 默认 3=正常档保桌面/测试确定性；批状态机纯内存运行态不入存档协议（S-16 同族）
取舍: AI 修炼速率必须复刻 Kotlin **对象版**（无 sectPolicies 入参、grief 由调用方传 0、manuals 空映射走 ManualDatabase 静态兜底）——政策加成与丧亲不参与 AI 速率；列直读版含 policyCultivationBonus + griefEndYear，语义不同，不能"共用一个公式"
结果: 区**M2 验收"月结残留扇出 ≤3 项（仅 UI 通知类）"达成**（残留仅 4g 邮件 / S-17 秘境关闭 / S-20 购买日志）
## 2.14 M2 第四批（2026-09-06）：WS-3 E2 逐系统迭代域迁移 + E3 NPC 实体组件族
批次: M2 第四批 | ActionId: 无 | 产物: 新 ecs/npc_component.h、新 test/ecs_npc_test.cpp（8 用例）；改 phase_settlement.h（runPhaseCoreBatch/processAutoPills/processBreakthroughs/runPhaseSettlement 增 World&）、auto_gear.h（processAutoFromWarehouse 增 World&）
硬规格: 旬结算管线**全部系统**迭代域切到 ECS 桥接（syncDiscipleEntities 校验/恢复不变量 + View\<DiscipleRef\> 行序迭代）——步骤 0 自动装备（stable_sort 平局保持行序）/ 步骤 6 丹药（快照序 == 行序 == Kotlin ids 序）/ 步骤 7 突破（候选序 == 行序 == RNG 抽取序）/ 串行核心批次全覆盖；E3 NPC 七组件族（NpcId / NpcPosition / NpcVelocity / NpcPath / NpcPathIndex(kNoWaypoint 哨兵) / NpcSpriteId / NpcAnimState）+ spawnNpc/destroyNpc/destroyAllNpcs/findNpcById/collectNpcRenderRows
红线: sync 不变量保证 View 迭代序 == Store 行序，四步切换前后消费的行序列逐位相同；快照建完即弃 View，不跨实体集重建持有（步骤 6 内叛逃移行由 rowOf 现查兜底）；NPC 为**纯运行态零序列化**（组件即数据本体，无外部行序 → 组件插入序即权威，无需 sync 式校验）
登记: E2 残留口径——month/year_settlement + child_birth + disciple_purchase + recruit_settlement 约 20 处裸行号迭代不在 E2 命名系统清单，迁移属后续批次（§2.16 已关闭）
## 2.15 M2 第五批（2026-09-06）：P1-5 性能清偿（月结 O(N²) + 生产 O(slots×N)）+ S7 写点收敛评估
批次: M2 第五批 | ActionId: 无 | 产物: month_settlement.h（processPartnerMatching 常系数）、production.h（O(1) rowOf ×4 / buildHerbIndex 材料余量索引 / 配方排序进程内缓存 / 每槽重建索引）、test/production_test.cpp +2 双槽竞争用例
硬规格: 月结配对常系数优化——SYSTEM RNG 引用出循环 + 已配对女性 `std::set<string>` 改 `vector<char>` 位图按下标 O(1)；每对成本降为 O(1) 位图判定 + 8 次父 id 串比较；生产域逐槽全表 id 线性扫描改 DiscipleStore.rowOf O(1)
红线: **M×F 循环形状即 RNG 消费序**（每对通过过滤的组合恰一次 nextDouble，Kotlin 逐位对拍红线）——结构级降复杂度必然改消费序列，须双端同步改算法（行为基线变化需拍板），本批只降常系数
登记: S4 遗留分歧缺陷顺手清偿——锻造步材料索引原为**批首快照**，同批多槽材料竞争时以旧余量选配方且 consumeMaterialsForRecipe 无不足守卫 → 少扣材料白嫖生产（Kotlin 真实路径为逐槽实时读）；炼丹/锻造统一改每槽重建索引（与 Kotlin 逐位同语义），竞争黄金用例 ×2 锁定（修复前锻造步此用例红）
登记: S7 写点收敛评估结论 = **窗口对齐已充分**——UI 生产槽读 Room 仓库流（非镜像）、C++ 月结视图由 S4 窗口对齐收敛、存档序列化/自愈/gate 重建均以 repo 为准，镜像月中陈旧**无消费者**；逐点双写无受益方，S4 残留口径就此关闭
## 2.16 M2 第六批（2026-09-06）：WS-3 E2 残留——月结/年结/生育/购买域迭代域过 sync 桥接
批次: M2 第六批 | ActionId: 无 | 产物: month_settlement.h / year_settlement.h / child_birth.h / disciple_purchase.h（+World&）、game_core.h/.cpp（ecsWorld() 访问器 + 四调用点）；测试适配 month_settlement_test 56 处 / year_settlement_test 6 处 / child_birth_test 4 处
硬规格: 月结/年结/生育/购买域约 21 处裸行号迭代过 sync 桥接；结构变更循环（执法堂叛逃 / 思过释放 / 政策效果）改 **id 快照 + 逐 id rowOf 现查**（快照序 == 行序 == Kotlin ids 序，每名风险弟子恰检一次）
红线: 只读循环切换前后行序列逐位相同；生育域母亲 RNG 消费序 == 行序；M×F 配对循环形状不触碰（P1-5 登记口径）
登记: recruit_settlement.h `nextDiscipleId` 与 year_settlement.h `idx_find` 为归约/查定型扫读（order-insensitive）不迁移（W 侵入 allocateAndInsert 调用面不值）；**C++ 裸行号循环在 remove 后跳行，与 Kotlin 快照迭代存在未测路径分歧——本批顺带收敛到 Kotlin 语义**
结果: WS-3 E2 残留口径就此关闭
## 2.17 M2 第七批（2026-09-06）：WS-2 S6 秘境探索交互会话域下沉 C++
批次: M2 第七批 | ActionId: SECRET_REALM_START=1440 / CHOOSE=1441 / END=1442（gen-action-ids.mjs 90 项）| 产物: 新 system/secret_realm_session.h（sr_session 域）、新 test/secret_realm_session_test.cpp（12 用例）、新 GameEngineSecretRealmNativeOps.kt；改 action_ids.h / ActionIds.kt / execute_dispatch.cpp、GameEngineSecretRealmOps.kt、BattleExecutionRouter.rebuildBattleLogData
硬规格: startSession（校验链逐字 + 成员快照 + 初始妖兽事件 + SECRET_REALM 消费）；chooseOption（校验链 → 体力 → 六事件类型分派：妖兽远离 1 抽 / 战斗 / 偷袭 1 抽、休整恢复 40%（战斗口径 maxHp）、遗迹 resolveSecretRealmRuinsExplore + 描述符实例化入背包、方向 rollNextEvent、AI 避让零消费、交战 PvP → 战斗执行 → 成员写回 → 奖励/损失 → 会话合并 → 体力耗尽/全灭自动结束）；endSession（灵石入钱包 + 六类物品入仓 + 秘境清场 + cooldownYear 锚定 + gate 释放面草稿）；袋物化 materializeDiscipleBagAndMarkDead（实例轨道防双持有 + 堆叠轨道 minRealm 保真 → addXxx 入仓（溢出转邮件草稿，items 不丢）→ 清袋 → markDead + 年度计数）
红线: RNG 分区 SECRET_REALM / BATTLE 双端同源；writeBackBattleMembers 幸存者 HP 钳制 / 首亡→濒死 / 濒死再亡→袋物化+markDead；决策③战报——C++ resultText 为确定性字面量逐字直出，rounds 的 message **不入 C++**（Kotlin BattleExecutionRouter.buildSummaryMessage 确定性摘要重建，展示通道非协议）
登记: YEARLY_SPAWN 勘误**不注册**（年变现世已随批 Y-2 在 runYearSettlement 原生下沉 processAncientSecretRealmSpawn，独立 ActionId 属死代码——死导出纪律）；决策④"零数据库依赖"边界由会话层承担（六类候选模板源 = C++ 数据库主表保序 rarity 过滤）；S6 真机验证项登记 §4.1
接线: 三入口 native 分支（AUTHORITATIVE 门控 + 失败信封回退 Kotlin——**双实现并行契约**）；战报经 recordPlayerBattle 在 Kotlin 重建；overflowDrafts 经 InventoryNativeForward.deliverDraft 同一投递通道；AI 无力应战时 toResolution 恒置 enteredCombat==true 的 Kotlin 口径锁定
## 2.18 M2 第八批（2026-09-06）：WS-2 S7 生产排程交互事务下沉 C++
批次: M2 第八批 | ActionId: PRODUCTION_START=1443 / RESET=1444（92 项）| 产物: production.h `startProductionTransaction` / `resetProductionSlotTransaction`（detail）、execute_dispatch.cpp、BuildingFacadeImpl（门面层 native 臂）；test/production_test.cpp +5 用例
硬规格: 排班事务组合等价——配方查询（pillRecipeById/forgeRecipeById）→ ensure 槽位（缺槽创建 IDLE，ProductionSlot{} 默认即 createIdle 语义）→ SlotBusy 门禁 → 材料充足检查（name+rarity 求和 vs 模板反查）→ startSlotWorking（duration 重算合并 + completionMonth/Phase 一次写最终值，Kotlin"先写原始值 + 外层异步修正"两段的等价收敛）→ consumeHerbs/MaterialsForRecipe；重置事务 = resetSlotToIdle（回 IDLE 全清空 + **配方无条件保留供续炼** + 弟子不保）+ 存在性门禁；successRate<0 哨兵 → 按槽位弟子原生公式计算（production_test 0.12 精确锁定）
红线: 手动排班匹配口径为 **buildingId**（与月结完成结算"锻造按 id/炼丹按 type"混合口径不同——各自对齐 Kotlin 查询）；失败信封 → Kotlin 回退原路径重执行校验链（InsufficientMaterials 缺口明细由 Kotlin 构建——双实现并行契约）
登记: 消耗日志 MaterialConsumptionLog（UI 流）与自动续炼启动为 Kotlin 侧平台效应保留；**C++ 真相先行 + Room 持久化后置**（镜像槽位单槽回放 repo）
结果: WS-2 S7 就此收口——M2 主轴（WS-2 全部子系统 + WS-3 ECS 化 + 性能项）全部清偿
## 2.19 M2 续批（2026-09-08）：WS-5 地图数据模型改造——地形生成真源迁 C++ + 三个 O(全图) 点收敛 + chunk 网格参数化
批次: M2 续批 | ActionId: 无 | 产物: 新 map/terrain.h、新 test/terrain_test.cpp（16 用例）、新 util/SectTerrainBridge.kt、新 DiffSectTerrainTest.kt（4 用例）、新 SectTileOccupancyTest.kt（6）/ RoadMaskTrackerTest.kt（4）；改 GameCoreBridge.cpp/.kt（nativeGenerateSectTerrain）、GameCoreJni.cpp + DiffRngBridge.kt（位级探针）、MapPreloadData.kt（**rawTileData 2D 字段删除**）、SectMapController / BootSequenceController / MainGameScreen.kt（applyBuildingOccupancy）/ RoadTiling.kt（RoadMaskTracker）/ SoftwareCanvasBackend.kt
硬规格: 地形**位级一致三要点**——① cellHash 的 Int64 加法回绕→`.toInt()` 截断、Int 乘法回绕走 uint32（**有符号溢出在 C++ 为 UB**）；② smoothNoise 收缩敏感中间值经 volatile 局部隔断 FMA（arm64 默认 ffp-contract=on 会产生与 ART 不同舍入）+ 乘积命名 + 左结合求和；③ 瓦片索引独立定义（gamecore 禁依赖生成头）；门楼常量按 GameConfig.SectMap 传值（单一数据源不落 C++）
红线: **展平 IntArray 是唯一表示**（flatTileData，不可变基座，纯地形无占位标记）；O(全图) 三点收敛 = 建筑占位 `copyOf` + O(脚印) 标记（越界脚印格跳过、产出新引用）、道路 RoadMaskTracker 增量（变更格 + 四邻，全量路径复用 buildRoadMaskArray 同源）、chunk 网格参数化（numChunksCol/Row=ceil(世界格数/32)、chunkPixel=32×tileSize，生产 128²/48px 派生值 = 原硬编码零行为差）；**内容未变返回稳定引用**（引用变化是失效/拷贝契约，两方向都要保）；JNI 无状态纯函数 kAnyThread（同 roadCompose）
登记: **地形不入存档/镜像 JSON 协议**——存档为 Kotlin kotlinx ProtoBuf（非 C++ exportState JSON），16384 整数段入协议 = 存档 +~30KB + 每次 exportState/镜像同步 +~80KB 持续代价，而地形是种子的确定性纯函数、再生零成本 ⇒ 落地为"C++ 生成真相源 + Kotlin 按种子缓存（每会话一次）"，协议/存档零负担；副作用 = 生成器演进会改旧种子地图（与迁移前 Kotlin 行为一致，位级演进由 Diff 对拍锁守），**"地图跨版本冻结"优势放弃，如需须拍板补协议批**；WS-4 可行走语义（树/边界是否阻塞）待玩法设计文档拍板，本批不做前瞻 API（terrain.h 头注释登记）
坑: 值域断言不得覆盖负坐标（smoothNoise 截断朝零 → fx<0 出界，为双端同象非移植缺陷）；JNI 系统属性注入为 `-D` 单横线（`--D` 被 Gradle 判未知选项）
## 2.20 M3 首批（2026-09-08）：M3 收敛启动——死代码族清偿（五模块 UnusedPrivate*/UnusedImports 清零）+ baseline 211 条摘除
批次: M3 首批 | ActionId: 无 | 产物: 五模块 UnusedPrivateProperty/UnusedPrivateMember/UnusedImports 全条目归零（死 TAG 15、死 import 9+30、死私有属性/常量 60+、死私有函数 14、死构造参数 7、空 companion 14、`SaveLoadSaveDelegate.kt` 整文件删除）+ guard 只缩（app 238 / data 429 / domain 503 / engine 1057 / game 579，-211）
硬规格: 全仓空基线实跑实测活债务 **3129 条**（app 281 / data 557 / domain 513 / engine 1245 / game 533）——实跑裁决证明债务基本是活的（非陈旧条目）；`for (i in 0 until n)` 未用变量 → `repeat(n)` 化（**带 break/continue 的循环保持 for/while**，repeat 是 lambda）；**签名漂移条目等量替换**（CultivationService LPL 参数 11→10 + SpiritMineDialog NBD，从生成物提取精确签名，计数不变）
红线: 副作用保留改造 7 处（`val x = expr ?: return false` elvis 早退 / `wallet.batch(...)` 副作用 / 计时段反序列化 / `sanitizeRecruitList(state)` 就地净化——**绑定可去、表达式必须保留为裸语句**）；baseline 只缩不增（13.2）
登记: 残留口径按族登记后续批次——MaxLineLength 1400+ / TooGenericExceptionCaught ~135 条 / ReturnCount 239 / CCM 196 / TMF 139 / UnusedParameter 124
坑: 机制发现四条（登记 findings）——① detekt baseline 按**规则+文件+声明签名文本**匹配（非条数），签名文本变化即条目失效；② `for (_ in)` 是 Kotlin 实验特性（UnnamedLocalVariables，Android 编译器默认拒绝）；③ Git Bash sed 对 CRLF 文件 `$` 锚不匹配（删除静默失败，perl `\r?\n` 兜住）；④ 脚本批量删"全文件唯一出现行"必须校验是**完整声明**（多行声明只删首行产生悬挂续行）
## 2.21 M3 第二批（2026-09-08）：反向通道逐域写者审计（无域可关改判）+ lockedBeastIds 增量段缺口加固 + detekt InvalidPackageDeclaration 118 条清偿
### 2.21.1 逐域写者审计——"反向通道按域全关"改判为 UI 操作面阻塞（无代码，审计落档）
批次: M3 第二批 | ActionId: 无 | 产物: 审计全文落档 docs/ui-read-surface.md §4.1（域→残余写者→下沉批次清单）
硬规格: 全仓生产代码 `stateStore.update`（参与反向捕获）穷尽审计 = core:engine ~265 处 / feature:game ~50 处经 `gameEngine.update*` 包装器 / app+domain 0 处直接调用；S4-S8/WS-5 下沉的是**结算/事务核心**，各域仍有 15+ 域 UI 操作面 Kotlin 直改写者（弟子管理最大残余域 / 巡逻 / 建筑放置 / 外交 / 设置 / guide / 玉符 / 月年边界编排 / 洞府探索 / 天劫 / 邮件附件 / 商人出售开袋 / 任务俘虏）
红线: **推翻 §5 前提"多数域关闭条件已达成"——当前没有任何一个域满足关闭条件；关闭前置 = UI 操作面逐域下沉 C++（每域一个 WS-2 规模批次），属长期主轴而非收敛批**；"玩家操作 Kotlin 产生 → tick ⑤ 反向回导"是 2026-08-31 根因修复后的现行设计契约
### 2.21.2 lockedBeastIds 反向增量段缺口加固（S-15 同族，审计途中发现的真实缺陷）
> **⚠️ 现状更新（2026-09-15，§2.53 batch-21）**：本节所述**增量段已被关闭**——batch-23 已把 UI 锁定/解锁操作面下沉 C++（`BEAST_VIEW_LOCK_TX=1730`，事务幂等无失败面），Kotlin 侧仅剩回退臂写者，故 `lockedBeastIds` 顶层段列入 batch-21 关闭清单并停止随信封回导（`ReverseChannelPolicy` `Kind.TOP_LEVEL_SECTION`；逐域回滚 `reopenDomain(BATTLE)` 可恢复）。本节其余内容为**历史记录**，勿据以认为该段仍在传输。
批次: M3 第二批 | ActionId: 无 | 产物: StateSyncService.kt（lastLockedBeastIdsSent 变化检测缓存 + buildReverseEnvelope 携带段）、game_core.cpp applyReverseDirty 新增顶层段分支（排在未知集合宽松忽略之前）；apply_reverse_dirty_test.cpp +1、StateSyncServiceReverseTest.kt +1
硬规格: @Transient 顶层段（不入 kotlinx gameData JSON）反向信封**必须单独成段**——缺陷为 UI lockBeastView/unlockBeastView 直改后信封永不携带，增量窗口内锁定只靠全量回导兜底可达，AUTHORITATIVE 月结"锁定妖兽不被 AI 攻击"判定对新开弹窗失效；修法 = 变化检测缓存（null=未同步必发）+ 整体替换语义（空集也发=全解锁）
红线: 反向信封前向兼容——C++ 对未知集合名宽松忽略（旧 C++ 收到新段为 no-op，**无双端部署顺序约束**）
登记: aiSectBeastDirectTargets / aiSectBeastSkipCooldowns 两段 Kotlin 写者仅存在于月结回退路径（native 未就绪），AUTHORITATIVE 稳态由 C++ 独占，回退→AUTHORITATIVE 切换经 ensureAuthoritativeNative 全量导入四段全部可达——**无增量缺口，不加段**
### 2.21.3 detekt InvalidPackageDeclaration 118 条清偿（纯文件搬移，零代码变更）
批次: M3 第二批 | ActionId: 无 | 产物: 主树 113 文件 + 测试树 5 文件 `git mv` 至声明包对应目录；engine baseline 1057→**939**（-118，只缩不增）+ guard 同步
硬规格: 零风险论证——package 声明不变 ⇒ 字节码不变；架构守卫（Konsist `scopeFromDirectory` + 自研 `walkTopDown` + 按文件名白名单）均递归扫描 + 文件名匹配，不受搬移影响；逐文件校验声明行与目标目录 + 同名碰撞检测
## 2.22 M3 第三批（2026-09-08）：detekt 机械族专项——MaxLineLength 1407 条实修清偿（baseline 2688→1281，-52%）
批次: M3 第三批 | ActionId: 无 | 产物: 五模块全部现行长行**实修归零**（~1200 代码行机械换行 + ~60 字符串等值拆分 + 34 raw string 逐处人工 + 6 注释行），顺带清偿换行连锁暴露的 10 个 LongMethod 临界越界与 4 处漂移复活违规
硬规格: 换行器 = 词法状态机（正确处理 `${}` 模板内嵌套引号/花括号/raw string/跨行块注释）+ Kotlin ASI 安全判定（断点须满足 括号深度>0 ∨ 头部以续行 token 结尾 ∨ 尾部以可续行 token 开头；**平衡表达式后接 `(`/`{`/`[`/标识符的断点一律跳过**——trailing lambda / infix / 调用粘连陷阱）；字符串拆分**内容逐字节不变**（模板串仅在 `${}`/$ident 之外拆，禁在 `$` 前后拆，转义/\uXXXX 整体不拆，两侧片段非空）
红线: **LongMethod 计数口径 = linesOfCode（PSI token 行，注释/空行不计）**——机械换行给 58~60 行临界函数 +1~3 行即越界，只能真实减行；类被 TMF baseline 压制时新助手落**文件级私有顶层函数**（文件级计数=顶层函数数）；换行批次的 detekt 验证必须**全量重跑**（行文本变化会让其他规则的既有条目失配复活）
登记: 8 例 RoomMigration 预存失败（V50 迁移链未注册，HEAD stash 复验同样失败）登记 §4.1 另案；baseline 2688→1281（app 123 / data 252 / domain 133 / engine 492 / game 281）
坑: 字符串完整性校验器曾因 git pathspec 相对路径错误**全量跳过（假绿）**，修复后重跑才得真实结论；换行器自身三处缺陷（断点排序反选 / 文件尾多余空行 / 拆分器闭引号 off-by-one 致 `"" + "" + ""` 空串链）⇒ 损坏现场全部手工重写并逐字节校验还原
## 2.23 M3 第四批（2026-09-08）：RoomMigration 预存失败清偿 + detekt TooGenericExceptionCaught 族实修清偿（baseline 1281→873，-32%）
### 2.23.1 RoomMigrationV4x 8 例预存失败清偿（§4.1 登记 2026-09-08）
批次: M3 第四批 | ActionId: 无 | 产物: RoomMigrationV43To46/V46To47/V47To48/V48To49Test 四文件 companion 增 M49_50 别名 + 全部 6 处 addMigrations 链尾补 MIGRATION_49_50
硬规格: 母文件 RoomMigrationTest 早已注册（62/62 绿），拆分文件 2026-08-12 拆分后未同步，WS-0.a 提 V50 即断链——真实 Room 校验用例从"迁移路径缺失"恢复
红线: 种子派生 replace 锚点修复（§2.22"SQL 空格串规范化"后 `\n        0\n    )` 失配 → V44 种子报 "101 values for 105 columns"；**迁移链修复前被 8 例 "migration not found" 掩盖**）；锚点改顶格形状 + 防复发注释
验收: `RoomMigration*` 5 类 74/74 全绿（RoomMigrationTest 62 + 拆分文件 12）
### 2.23.2 detekt TooGenericExceptionCaught 族实修清偿（135 条 baseline 条目 → 474 处实跑违规全部处置）
批次: M3 第四批 | ActionId: 无 | 产物: 391 个函数批量插 `@Suppress("TooGenericExceptionCaught") // <理由>`（锚 = KDoc 之后/注解块之上）+ 6 处无函数归属手工处置（属性级/表达式级）+ 19 对双 @Suppress 合并；baseline 重建后 1281→**873**
硬规格: 474 处全部为**刻意的防御性 catch**（CancellationException 前置 + Exception 终局兜底两段式是 2026-08 异常整治后的既有惯例），按形态附理由 @Suppress（LOG_ONLY 336 / RETHROW 53 / WRAP_RESULT 15 / OTHER 70）——**不收窄异常类型**（异常源跨 IO/序列化/SDK 不可枚举，收窄即行为变更）
红线: detekt 同目标多 @Suppress **只生效其一**（且重复注解为编译错）→ 新增抑制必须与既有注解合并为单注解多参数；baseline 条目签名文本含函数注解——插注解会使该函数全部规则的既有条目失配复活（65 条等量替换）
登记: **detektBaseline 生成物 = 全量当前真实违规（含被 baseline 压制面），直接装回会偷偷扩大压制面**——必须先摘目标族条目、实跑裁决、处置后再生成（本批顺带清除 273 条陈旧死条目）
坑: 脚本"向上找 fun"两缺陷（泛型 `fun <T>` 不匹配 / 嵌套 lambda 与匿名对象内函数截胡）→ 10 处误插括号平衡复核后回滚；gradle 测试 `-D` 属性注入相对路径 → JNI UnsatisfiedLinkError 假失败（须绝对路径）
## 2.24 M3 第五批（2026-09-08）：detekt 判定族收官 + 机械族全清（baseline 873→370 条，-58%；实跑活违规 1050→395）
批次: M3 第五批 | ActionId: 无 | 产物: 摘除五模块全部 873 条条目 → 实跑裁决全量真实违规 **1050 处**（34 规则族）→ 逐处处置 → 仅装回"拆分任务队列族"370 条（五模块计数全部只缩）
### 2.24.1 机械族全清（实修归零）
硬规格: 机械族（NewLineAtEndOfFile / ModifierOrder / MayBeConst / ImplicitDefaultLocale / ForEachOnRange / DestructuringDeclaration / InvalidRange 等）全实修；`EmptyElseBlock ×4` 根因 = `if (cond) stmt;` **尾分号在 PSI 产生空 else**（密排单行改标准多行）；`MatchingDeclarationName ×25` = git mv 重命名 22 文件 + 3 文件声明重排（**批量 git mv 前必须核对路径型守卫**：GameSystemRegistryCoverageTest 以文件名当类名扫描 @GameService）
### 2.24.2 命名族全清（VariableNaming ×48）
红线: `_xxxFlow`/`_updateVersion` 等 internal 镜像通道 backing property 用**文件级 @Suppress**（detekt 无 internal 命名旋钮）；`size_`/`items_` 与公开 API 同名消歧附理由 @Suppress
坑: `_discipleTables→discipleTables` 全局改名撞 `GameStateStore.discipleTables`，fake 覆写 getter 被改成自引用 → **无限递归 StackOverflow**——**主源编译不编译测试源**，改名批次必须跑 `compileReleaseUnitTestKotlin`
### 2.24.3 异常形态族全清
硬规格: TooGenericExceptionThrown 9（RuntimeException→IllegalStateException）/ InstanceOfCheckForException 29（三 Delegate 改**两段式 catch**，取消传播首个落地族；StorageEngine 拆 IOException 独立 catch + 删除恒 false 的 `e is OutOfMemoryError` 死检查）/ UseRequire+UseCheckOrError 24 / RethrowCaughtException 6（函数级附理由）/ SwallowedException 54（改名 `ignored`/`expected` 用 detekt 认可标记）/ ThrowsCount 10
红线: **Kotlin try 语法必须接 catch/finally——"删 no-op catch"不可行**
### 2.24.4 判定族收官
硬规格: UnusedParameter 110→0（真实删参 4 组 + 106 处语义形参/镜像协议字段/扩展点预留按参数族附理由 @Suppress）；EmptyFunctionBlock 107→0（空覆写 `{}` → `= Unit` 表达式体）；LaunchOnEngineRequired 1（UI 进度插值动画刻意走 UI scope 附理由）
### 2.24.5 ReturnCount 阈值拍板（max 2→5）
登记: detekt 默认 `max: 2` 与 Kotlin 守卫子句/校验链早退惯用形冲突（实跑 220 处中 187 处为 3~5 return 的合法早退）→ 拍板 `style>ReturnCount max: 5`（**规则属 style 规则集，放 complexity 报 invalid config**）；余 33 处 6+ return 归 M3 第六批
### 2.24.6 M3 第六批队列（装回 baseline 登记）
登记: 395 处真实违规（baseline 按"规则+文件+签名"去重后 370 条）——**TooManyFunctions 113 / CCM 85 / Loop 70（实跑放大后违规数）/ LongParameterList 34 / ReturnCount 33 / NestedBlockDepth 28 / ComplexCondition 17 / LargeClass 15**，全部真实结构性重构（文件/类拆分、函数提取、查表化 when），**不可赶工，逐族专项批推进**；NestedBlockDepth 23/29 恰在 4/4 边界、ComplexCondition 14/17 恰在 4/4、LPL 14/34 恰在 8/8
坑: 机制发现七条（登记 findings）——`if (cond) stmt;` 空 else 误报源；Kotlin try 必须接 catch/finally；全局改名必须跑测试源编译；ReturnCount 在 style 规则集；批量 git mv 前核对路径型守卫；同声明重复 @Suppress = 编译错 "not repeatable"；**detekt 报告行号随编辑漂移——批量脚本必须幂等 + 行号偏移补偿 + 以全量重跑为最终裁决**
## 2.25 M3 第六批（2026-09-08）：detekt 判定边界族收官——ComplexCondition 17 + NestedBlockDepth 28 + ReturnCount 33 真实重构清偿（baseline 370→292）
批次: M3 第六批 | ActionId: 无 | 产物: 三族 78 条条目实跑裁决**恰 78 处**（放大系数 1.0——判定/边界族条目数≈债务数），逐处真实结构性重构（谓词提取 / 深嵌套块提取 / 查表化与守卫合并），**不装回任何条目**；guard 370→**292**
硬规格: CC 17→0 提取命名谓词（求值序逐位保持：VulkanPolicy hasPriorVulkanFailure / GridSnapHelper outOfBounds / GameStateStoreImpl 血炼 base 六列判定等）；NBD 28→0 最深嵌套块提取私有助手（循环形状/RNG 消费序不触碰：LawEnforcementProcessor 仓库驻守拦截 nextInt 恰一次、PartnerSystem 配对改 continue 卫语句保持 M×F 形状、GameEngineCoordination 双过滤口径逐位保持）；RC 33→0 校验链分相 / 有序规则表引擎 / sealed 校验结果 / 查表化（错误消息与日志逐字保留；BattleCalculator selectSkill 10 return 拆三段、BattleAI 四函数守卫合并前提 = 检查点间零 RNG 消费）
红线: 全部零行为变更、零装回；拆分后首跑暴露的 10 处次生违规（宿主函数上的抑制不随助手迁移 / 助手推高容器函数计数 / **detekt 把赋值右侧的 if 表达式也计一层嵌套**）全部根治后才算清偿
登记: 顺手处置三处预存缺陷/死代码——`DiscipleDeadStatusRule` 消息 `listOfNotNull(hasWeapon to "...")` Pair 永不为 null（四槽位全进消息，按意图修为只列非空槽）；`StorageEngine.buildSaveDataFromDatabase` 返回非空致 `if (saveData != null)` 恒真 + 尾部死分支；`SaveCrypto` 密码擦除双写按最小改动保留
坑: K1 编译器——**仅 finally 的 try 块不能作为块体最后语句的隐式返回表达式**（报 Missing return statement，须显式 `return try {…} finally {…}`）；**"目标族归零"不等于"无新违规"**，拆分批必须全规则看报告
## 2.26 M3 第七批（2026-09-09）：detekt 参数与跳转族清偿——LongParameterList 34 + LoopWithTooManyJumpStatements 45 条目实跑裁决 102 处真实重构（baseline 292→196）
批次: M3 第七批 | ActionId: 无 | 产物: LPL 34 + Loop 45 条目摘除实跑裁决 **102 处**（LPL 34 + Loop 68，Loop 呈"同签名放大"形态）→ **98 处真实重构/死代码删除 + 4 处附理由 @Suppress**，不装回任何条目；baseline 292→**196**
硬规格: LPL 死代码删除 ×5（ProductionParams / GameTime 整文件、executeStartProduction byType 版、dirty 死链三函数、loadInventory、StackableItemUtils 整对象）；LPL 参数对象 ×22（ProductionStartSpec / BattleWriteBackContext / TheftInventorySnapshot / SpiritStoneTransaction / ExploitationSubSystems 12→7 构造参数 / 各类 Compose Inputs 载体）；LPL 附理由 @Suppress ×4（NativeBridge.drawRect/drawSprite JNI ABI 顶点格式锁定、CultivationService 10 协作域独立注入面、SpiritRootAttributeFilterBar 8 调用面 state-hoisting 参数面即契约）
红线: Loop 68 全部真实重构且**求值序与 RNG 消费序逐位保持**——RNG 红线三处专项：PartnerSystem M×F 配对（守卫合并 + 抽取判定 `< PROB` 反转为 if 体，抽取集与顺序不变）、ChildBirthSystem 受孕/分娩双循环、ProductionProcessor 影子结算；SectMapTileGenerator 单格装饰提取**位级输出不变**（DiffSectTerrainTest 锁守）
登记: `GameStateRepository` dirty 机制确认为 **write-only 残留**（markDirty/markAllDirty/clearDirty 只置位无消费者），但 `markAllDirty` 在 loadFromSnapshot 失败回滚路径有行为依赖（StateRevertRegressionTest 注入失败触发）→ 保留待专项拍板（后由 §2.34 整体摘除）
坑: **并行会话未提交改动下批前 `git status` 快照不可信**（本批两次实际冲突：MainGameScreen 被并行覆盖写入致 2 处调用点回退、`:app:compileReleaseKotlin` 被并行批未完成符号卡死）——**提交严格按本批触碰文件清单暂存，禁止整仓 `git add`**；机制发现四条（解构声明上限 3 / baseline 重建顺带清除陈旧死条目 / `ComponentTable.contains` 非 operator / 每文件保存后以编译+定向 diff 复核）
## 2.27 M3 第八批（2026-09-09）：detekt 复杂度族收官——CyclomaticComplexMethod 68 条目实跑裁决 68 处真实重构清偿（baseline 196→128）
批次: M3 第八批 | ActionId: 无 | 产物: CCM 全 68 条条目（app 2 / data 6 / domain 5 / engine 40 / game 15）实跑裁决**恰 68 处**（放大系数 1.0），逐处真实结构性重构归零，不装回任何条目；guard 196→**128**（拆分队列余量 = TMF 113 / LargeClass 15）
硬规格: 处置形态四类——查表化（formatEffectKey 36→2 / getStatDisplayName 23→2 / getBuffTypeName 28→2 + parseManualStackBuffs 32→3 共用表 / parseBuffType / deriveDiscipleStatus 20 用**有序判定表（表序=原 when 优先级序）** / AppErrorExt toAppError ×2 用 `getValue` 保"新枚举值必须显式登记"穷尽语义）；RNG 红线专项（processPartnerMatching 22 / processYearlyConception 15 / batchAlchemyCompletion 17 / applySurvivorSoulAndAttribute 21 / applyMissionRewards 25 / tryBreakthrough 22 / generateSectTradeItems 15 / computeAutotileBitmask 24 逐字节搬移位级锁守）；Compose 组合拆分；分相/sealed 化；谓词提取与守卫收敛
红线: 抽取集与顺序逐位保持（守 M×F 循环形状、先抽 success 后入臂抽 roll、类型 roll→稀有度 roll 序不变）；不装回任何条目
登记: 并行工作线中途状态按"**本批触碰文件零新增违规零新增失败 + 违规/失败集合差分归属裁决**"验收（NativeSurfaceView TMF 1 处 / SoftwareCanvasBackendTest 占地框颜色 1 例 / KSP 增量缓存两波损坏均归属并行线）
坑: detekt CCM 计数模型（if/when 入口/&&/||/elvis 各 +1、lambda 不计）；Windows 并行会话 `classes.jar` 文件锁可持续数分钟（重试须以"锁释放探测"收敛而非固定 sleep）；python 切片编辑须 `newline=''` 读写并探测 CRLF；K1 智能转换在 lambda 早退后对成员扩展返回值不稳定（改 when-subject）
## 2.28 M3 第九批（2026-09-09）：detekt 函数数族第一轮——TMF 113 条目实跑裁决 114 处：文件级域拆分真实清偿 + 契约面附理由豁免（baseline 128→59）
批次: M3 第九批 | ActionId: 无 | 产物: TMF 全 113 条条目实跑裁决 **114 处**——①死代码删除（StateFlowListUtils.kt 整文件、GameRandom 7 函数）②文件级域拆分（GameEngineCoordination 90 函数 → 保留 13 + 8 域文件 + LoadSlotOps 8；GameEngineBattleOps 41 → 保留 13 + 5 域文件；GameEngineInventoryOps 35 → 保留 14 + 2；GameEngineProductionOps 20 → 保留 13 + 2；GameEngineDiscipleOps 37 → 保留 14 + 2；Compose 4 文件 → 17 文件）③契约面附理由 @Suppress 46 处（Room DAO ×20 / 转换器 ×5 / GameDatabase / 接口 ×11 / DI ×2 / 静态注册表 ×7 / 容器协议 ×4 / JNI ×1 / app ×4）；baseline 128→**59**
硬规格: 同包顶层扩展移动 = 调用点语法零变化；拆分后每文件顶层函数 ≤14（文件阈值 15）；跨文件消费的顶层声明 private→internal；共享常量表随消费方迁移；BattleLogTab 枚举落位同名文件（MatchingDeclarationName 根修）
红线: 拆分产物唯一完整保障 = **移动函数与源逐字节 diff 校验**（73/74 逐位一致，1 处单行表达式体为校验器盲区人工复核）；baseline 全量重建捕获的 4 处并行线活违规按 13.2 **禁止装回**，从生成物剔除并归属登记
登记: 残留 44 TMF + 15 LargeClass 装回 baseline 登记为"拆分任务队列"余量（域管理者/ViewModel 族，GameViewModel 188 为最大单体）；契约面（DAO/接口/转换器/DI/注册表/JNI）已全量豁免归零，后续新增同形状文件按既有注记惯例处置
坑: Kotlin 顶层声明块切分必须**原始列锚定**（strip 后行匹配会把函数体内缩进 `val` 误判为块边界，函数体被截断且尾段落入原文件——字节级校验器事后发现、git HEAD 还原重做）；import 剪枝必须**保留通配导入**；detekt UnusedImports 对**同包导入**也报未用
## 2.29 M3 第十批（2026-09-09）：detekt 拆分任务队列首轮——core:data 全域清偿 + 并行线遗留 5 处根治 + app 契约面豁免（baseline 59→45）
批次: M3 第十批 | ActionId: 无 | 产物: LargeClass 15 + TMF 44 共 59 条实跑裁决——**core:data 14 条全部真实结构拆分**（SaveCrypto 47→12 四 object + 死成员 ×6；SecureKeyManager 37→7 三 object + 死成员 ×10；GameDataCacheManager 43→19 三扩展域文件；StorageEngine 63→18 四扩展域文件；FunctionalWAL / SaveFileManager / DataArchiver / ChangeTracker / DynamicMemoryManager / CryptoModule / IntegrityValidator 逐个拆分；RoomMigrationTest 1859 行拆母类 + 三辅助类）；app 1 条按契约面先例豁免；并行线遗留 5 处根治；baseline 59→**45**（app 1→0 / data 14→0，其余装回持平）
硬规格: 扩展函数域拆分 = 调用点语法零变化的 object 拆分替代（同包文件级扩展经隐式接收者解析）；companion 常量经"文件级 private 别名"引用；`GameEngineCore` 2004→1919 行（防冻结忙等三函数迁 GameEngineCoreLoopOps 同域文件），FileLength 归位
红线: **object 拆分的表达式体函数切块需累计括号深度而非行内平衡**（`salt: ByteArray? = null` 参数默认值行会误判截断）；catch 子句之间**不可插注解**（Kotlin 语法非法）——函数级 @Suppress 合并是唯一形态
登记: **诚实回退**——DiscipleTables（1786 行 LC）拆至半主动回退：assemble 家族与 txAssembled 事务缓存 / columnGroupByIndex 脏组位图 / 双指针归并助手深耦合，文件级扩展化需重排缓存失效不变量，超出"行为零变更"单批安全边界 → git 还原并装回登记（§2.32 以"失效点全部留守类内"边界设计消解）
坑: 机制发现六条——TMF 在函数数**等于阈值即报**（拆分目标数 = 阈值-1）；object 表达式体切块须累计括号深度；扩展函数域拆分的 companion 常量引用法；catch 子句间不可插注解；并行会话 python 残留进程持文件锁致编辑阻塞（taskkill 清进程）；detekt 报告行号随编辑漂移（修剪脚本以新鲜报告为输入）
## 2.31 Batch-02（2026-09-10）：detekt 拆分队列·game ViewModel 族全清——7 文件 8 条实跑归零（baseline feature/game 8→0，拆分队列余量 44→36）
批次: batch-02 | ActionId: 无 | 产物: feature/game baseline 8 条（7 TMF + 1 LC）实跑裁决**恰 8 处**，全部真实结构拆分，零豁免零装回零 C++/engine 触碰；GameViewModel TMF 188→19（删 ~169 个 1 行委托包装，调用方直连既有 delegate，新建 RoadDelegate / MerchantOpsDelegate / BattleRewardDelegate / MissionDelegate / LifeEventsDelegate 五域委托）；SaveLoadViewModel TMF 75 + LC 1526 → 17 + ~640 行（6 个同包扩展文件：SaveOps 12 / LoadOps 13 / NewGameOps 7 / RestartOps 11 / CloudOps 11 / CloudLoadOps 4）；ProductionViewModel 61→4、SectViewModel 48→14、DiscipleDelegate 41→18、NativeSurfaceView 21→19、NavigationDelegate 21→19；guard feature/game=8→0
硬规格: 行为零变更保障——扩展文件函数体与源逐字一致；包装删除的每处调用点按"包装名→delegate 属性"机械改写（56 文件 + 5 测试）；`buildingInstanceId.ifEmpty { "" }` 为恒等变换；BaseViewModel 四方法 protected→internal（扩展不在继承链上）；SaveLoadViewModel 15 状态字段 private→internal 并去下划线改名 *Flow
登记: Batch-04 跳过登记补做 2 处（ProductionViewModel.assignDiscipleToLibrarySlot / SaveLoadViewModel.resetOwnedLoadState 的 catch 前置 CancellationException 分支）；移交事项——并行线把 SectPolicyToggleUseCase 政策方法改写为 core:engine `internal` 扩展，跨模块不可见，集成时必须保持 feature:game 消费方可见性
坑: 手写正则做 Kotlin 成员边界切割三纪律（边界前瞻含 override/private/internal 前缀族、不含函数自身闭合行、表达式体与大括号体统一处理）；**KDoc 内的 `*/` 序列会提前终止注释**使类声明语法损坏；mockk 对顶层扩展 stub 必须先 `mockkStatic("<file>Kt")`
## 2.32 Batch-03（2026-09-10）：detekt 拆分队列·core:domain 全清——DiscipleTables 纯函数层下放（LC 1786→1038 行）+ GameConfigTest 按配置域拆六类（拆分队列余量 44→42，guard core/domain 2→0）
批次: batch-03 | ActionId: 无 | 产物: DiscipleTables 27 个私有/文件级块共 ~750 行迁至 6 个同包新文件（AssembleGroup / DiscipleTablesMerge / DiscipleTablesAssemblers / DiscipleTablesWrite / DiscipleTablesColumnRegistry / DiscipleTablesAptitude），类体 1786→**1038 行**；GameConfigTest 1072 行 → 六个配置域测试类共 166 用例（RarityConfigTest 31 / RealmConfigTest 74 / SpiritRootConfigTest 20 / PolicyConfigTest 24 / BeastAndStartingConfigTest 10 / GameAndDiscipleConfigTest 7）；guard core/domain=2→0
硬规格: **设计先行（§2.29 回退教训的依赖图结论）**——耦合面收敛为两条不变量：① `txAssembled` 唯一写点 assembleAll、唯一失效点 requireWriteAccess；② 列写回调在构造期 init bindAllOnWrite 一次性绑定 ⇒ 只下放不触碰这两条不变量的私有纯函数/列族读写块，即可拆体量而无需重排失效点；保真证明 = 27/27 搬移块 token 级与 HEAD 一致，差异仅四类设计内形态（声明行 private→internal / 扩展接收者 / 限定名 / computeDirtyGroups 类状态改入参）
红线: upsertMirrorRow isAlive SparseArray 探测 / `id in _ids` 兜底 / isCompleteId 三表幽灵判据 / `synchronized(_ids)` 锁序 / 非数字 id 抛错防御——全部留守或随迁零语义差；O(k) 行级镜像应用通道未触碰
登记: DiscipleTables 有状态面（CRUD + 缓存 + 复制绑定，~1038 行）为"镜像列协议下界"收敛载体，后续 WS-1 阶段 3 数据导向存储立项时再评估（§4.1 既有登记）；新增 LongMethod（列→组映射注册表 80 行）附理由豁免（与 buildCopyableRefs 既有豁免同口径）
## 2.33 Batch-04（2026-09-10）：协程取消传播专项——suspend/launch 上下文泛型 catch 前置 CancellationException 分支（目标集重测 61 处实修 + 13 处结构批所有权跳过登记）
批次: batch-04 | ActionId: 无 | 产物: 61 处修复（① 两段式 catch 58 / ② `withContext(NonCancellable)` 原子段 1（StorageEngineSaveSupport.abortWalQuietly——WAL 回滚必须完成才保证失败回滚语义一致）/ ③ 刻意吞取消附理由 2（GameEvents reportDrop/notifySubscribers——try 体无挂起点，"上报失败不得影响事件通道"是总线既有隔离契约）），每处留一行论证注释
硬规格: 目标集由脚本化枚举开工首步实跑裁决（非沿用估算）：全仓 929 处 catch（泛型 656）→ 协程上下文 195 → 已有前置分支 118 → 待裁决 77 → 排除文件 13 → **本批清单 64**；终验复扫已前置分支 118→179（+61），剩余 5 处均为形态②段内 catch / 形态③刻意吞 / 脚本误报，零漏网
红线: 取消必须穿透（不改泛型 catch 本身——异常源不可枚举口径不变）；**行为变更影响面逐类裁决——全部改动仅影响取消传播路径，正常路径抽取集与顺序零变化（RNG 红线核对）**；随批清偿 detekt 次生 9 条（ThrowsCount 并入既有 @Suppress 单注解多参数 / CCM 真实拆分 / MaxLineLength 折行），零装回
登记: batch-01/02/03/05 所有权文件 13 处跳过登记待其结构批完成后补做（后由 §2.30.1 补做 10 处 + §2.31 补做 2 处）
## 2.34 Batch-05（2026-09-10）：GameStateRepository dirty 记账机制整体摘除——write-only 残留清偿 + load 回滚语义考古拍板（§2.26 途中发现收口）
批次: batch-05 | ActionId: 无 | 产物: `GameStateRepository.kt`（-69 行：DirtySet + dirty 字段 + 三方法 + `loadFullState` 内 `dirty.set` + AtomicReference import 全摘除）、`GameStateStoreImpl.kt`（`markDirtyFor` 整函数 + 三调用点删除）、`StateRevertRegressionTest`（失败注入点 `markAllDirty` → `setActiveSlot` + **新增**"读档失败后状态与读档前逐位一致"回归）、`TestStateStoreSupport` KDoc
硬规格: **考古三条结论**——① dirty 位无任何读者（private 且无 getter，唯一消费者死链已于 §2.26 删除，三方法内 `||` 自叠加是唯一"读"）；② markAllDirty 的"回滚路径行为依赖"实为**测试借位注入**（回滚语义本体由 captureLoadBaseline 旧值快照 + rollbackLoad 全流恢复承载）；③ 4 处置位调用点全为裸语句位置，直删即安全
红线: 零 baseline 新增、零 guard 变动、零 C++ 改动；读档路径零行为影响经对拍证实
登记: SlotCache.markDirty() 生产零调用（方法级死代码候选）；`ProductionSlotRepository.isCacheDirty()` 无调用方（已随 §2.40 集成批删除）
## 2.35 Batch-06（2026-09-10）：UI 操作面下沉·建筑放置/迁移/升级/拆除事务入 C++——四操作稳态写者归一，Kotlin 原路径退役为回退臂
批次: batch-06 | ActionId: BUILDING_PLACE=1450 / MOVE=1451 / UPGRADE=1452 / REMOVE=1453 / UPGRADE_BATCH=1454 | 产物: 新 system/building_tx.h（纯头，五事务）、新 BuildingNativeTx.kt（internal 懒构造协作类）、新 building_tx_test.cpp（18 用例）、新 BuildingNativeTxGateTest.kt（6 用例）；改 execute_dispatch.cpp（handleBuildingTx）、BuildingFacadeImpl（四方法接线 + tryNativePlaceBuilding 接口方法）、BuildingDelegate.doPlaceBuilding
硬规格: 判定序逐字——place：宗门等级 → 环界+门楼 → 限建数量（全局唯一跨宗门/同宗）→ 同宗占位重叠 → 灵石；move：存在性 → 环界+门楼（**无重叠检查**，与原实现逐字一致）；upgrade：存在性 → 等级(≥中型) → 差价 → canFit（同宗除己）；upgradeBatch：整批等级 → 稳定序候选 → 可负担上限 → 升级中间态增量 canFit；remove：逐实例存在性（未知跳过）→ 返还 → 删；消耗 = place/upgrade 低阶灵石直扣、remove 返还走 SpiritStoneWallet::add（记年度账）
红线: **全链零抽取**（签名级证据：building_tx.h API 不接受 RngManager/种子；GTest 双运行逐位一致 + 全分区快照差分锁定）；失败零写入；instanceId 由调用方 java.util.UUID 生成传入（非游戏 RNG 分区）
登记: **宗门过滤归 Kotlin**——C++ GridBuildingData 无 sectId 字段（models.h 禁改，README §3.3 协议），限建/占位/canFit 的同宗过滤无法在 C++ 复现 ⇒ Kotlin 组装 sectScopedIds（目标宗门建筑 instanceId 集）随请求传入，占位几何/限建标志/造价/counterKey 均为参数（单一事实源留 Kotlin）；边界划分——C++ 承担建筑记录+钱包+引导计数，槽位派生/弟子释放/监牢任务阁特例/生产槽 repo 为 Kotlin 残差（createSlots 以 native 前快照定序）；Out-of-scope：enterSect 与 BootSequence 迁移
## 2.36 Batch-07（2026-09-10）：UI 操作面下沉·道路放置/拆除事务入 C++——稳态写者归一，Kotlin 直改+即时回导臂退役为回退臂
批次: batch-07 | ActionId: ROAD_PLACE=1470 / ROAD_REMOVE=1471 | 产物: 新 system/road_tx.h（纯头）、新 road_tx_test.cpp（16 用例）；改 execute_dispatch.cpp（handleRoadTx）、RoadFacadeImpl（native 臂）、core/engine 新公开工厂 `createRoadFacade` + CoreModule.provideRoadFacade 直构
硬规格: 判定序逐字——place：可建环界 → 占位（本宗建筑占地展开 ∪ FixedSectGateway 门楼 6×2）→ 重复放置 → 灵石充足（remove 仅存在性）；消耗仅低阶灵石 20/格（GameConfig.Road.COST_PER_CELL），拆除无返还；`recomputeRoadNeighborhood` 5 格邻域位掩码/形态重算复用 `tileTypeForBitmask`（gamecore::map 单一权威）
红线: **全链零抽取**（确定性状态变换 + 纯函数掩码重算，抽取集为空集；签名级证据 + GTest 双运行逐位一致 + 全分区快照差分）；失败零写入；models.h / GridSystem.kt / GameCoreBridge.* / StateSyncService.kt 未触碰
登记: **占位集合组装归 Kotlin**（C++ GridBuildingData 无 sectId，宗门过滤无法复现）——Kotlin 门面组装 occupiedCells（与 canPlaceRoad 同一来源路径）随请求传入，C++ 保留目标格冲突判定原语；即时回导加固保留零改动（native 臂下镜像写入不参与反向捕获 → 即时回导为空窗口零发送，契约自洽）；GameEngineRoadOps 成功即时回导臂退役为回退臂
坑: 首版构造注入 `StateSyncService` 触发 **Dagger MissingBinding**（其 `reverseSender` 默认 lambda 无绑定）且会**分叉反向通道实例** → 改为去 @Inject + 工厂直构传同引用（构造器可空形参保留为测试接缝）；诊断入口 = 干净检出跑 `:app:lintRelease`
## 2.38 Batch-09（2026-09-10）：UI 操作面下沉·外交/好感/附庸族入 C++——赠礼拒绝 roll 与结盟/附属掷骰 SYSTEM 分区同源，双臂行为逐位一致
批次: batch-09 | ActionId: DIPLOMACY_TX=1500 / FAVOR_GIFT=1501 / VASSAL_TX=1502 | 产物: 新 system/diplomacy_tx.h（纯头，六事务 + FavorDomain 纯函数族逐字移植）、新 diplomacy_tx_test.cpp（16 用例）；改 execute_dispatch.cpp（handleDiplomacyTx）、GiftService / DiplomacyService / VassalService（各加可空 GameEngineCore 构造注入 + 私有 native 臂）
硬规格: RNG 逐点——赠礼 SYSTEM 1×nextInt(100)（五级校验链全过后、任何写入前）；结盟 SYSTEM 1×nextDouble（资格+aiPower 校验后，aiPower<=0 早退**不**抽取）；附属请求 SYSTEM 1×nextDouble（资格通过后**恒**抽取——aiPower<=0 概率 0 仍掷骰，与 Kotlin calculateVassalChance→rng.nextDouble 同位）；散盟/解除附属零 RNG；FavorDomain 移植保留 Int 截断除法与 `(base+pct)*multiplier` 向零截断（浮点运算序不重排）+ clamp[0,100] + 未相识 no-op
红线: 校验失败（Kotlin 同位置早退、零抽取零写入）→ failure 信封 → Kotlin 回退原路径同语义复现（**双实现并行契约**）；roll 已消费的终态 → success 信封直返（message 模板留 Kotlin）；AUTHORITATIVE 下 Kotlin SYSTEM 分区为 NativeBackedRng 委托通道——双臂消费同一分区同一条序列，对拍锁终态 rngStates
登记: 协议段核查结论——alliances / vassalContracts / sectRelations / sectDetails / suzerainSectId / spiritStones / worldMapSects / gameEventRecords / rngStates 全部已在镜像协议，**零协议字段新增**（models.h/json_codec 零触碰）；宣战/停战/和平审计确认**无 UI 操作面**（AI 决策域已下沉）；纳贡/施压/好感衰减/脱离检查属年结月结域（红线：不触碰月结循环形状）；alliance.id 为确定性自增占位（不参与业务逻辑，对拍忽略）；聊天响应模板 SectResponseTexts.random() 走 kotlin Random.Default → 留 Kotlin
坑: **include-order 登记**——`diplomacy_tx.h` 置于 `execute_dispatch` 包含块末尾（其传递引入 `month_settlement.h` 的 using 声明会改变后续头 `disciple_tx.h` 的非限定名解析）；顺带为 batch-08 在途文件做最小加法补全（using 提至命名空间作用域）
### §2.37 Batch-08：弟子管理第一子批（装备穿脱/功法学忘/亲传+藏经阁任命卸任）入 C++（2026-09-10）
批次: batch-08 | ActionId: DISCIPLE_TX_EQUIP=1480 / UNEQUIP=1481 / LEARN_MANUAL=1482 / UNLEARN_MANUAL=1483 / ASSIGN_SLOT=1484 / UNASSIGN_SLOT=1485 | 产物: 新 system/disciple_tx.h（纯头六事务 detail 族）、新 disciple_tx_test.cpp（21 用例）、新 GameEngineDiscipleTxForwardTest.kt（12 用例）；改 execute_dispatch.cpp（handleDiscipleTx）、GameEngineManualOps.kt + GameEngineDiscipleSlotOps.kt（native 分支全落 Ops 域文件）
硬规格: 校验链逐字对齐 Kotlin 判定序（含"静默守卫失败信封化→Kotlin 回退同义静默"契约）；装备双轨道——弟子行四槽位列 + storageBagItems + equipmentStacks（-1/移除）+ equipmentInstances（铸造/置位/移除），**双持有防重**（实例轨道 vs 堆叠轨道 equip 二选一查找，堆叠优先铸实例；实例入袋即离实例表）；悬垂纪律（auto_gear.h 同款）：实例表 erase 与堆叠整摞扣减 erase 之后**禁用旧指针**（先拷贝所需字段、写段重查）
红线: **六事务零抽取**（铸造实例 id 为 nextInstanceId 确定性自增占位——Kotlin UUID.randomUUID 属非协议随机域）；失败零写入（校验链先行，"扣了背包却穿不上"中间态构造上不可能）；models.h 零改动
登记: 长老单值槽任命（ElderManagementUseCase.assignElder/removeElder，usecase 编排域）**本批不下沉，登记 W3**（后由 §2.46 batch-15 清偿）；落点偏差（优于声明面）——接线全收 Ops 层，DiscipleEquipmentService / DiscipleFacadeImpl / DiscipleService / GameViewModel / DiscipleDelegate（batch-01/02 所有权）零改动
坑: using 声明困于 detail 命名空间内会让 detail 外事务函数任何包含序都无法解析（batch-09 以最小加法补全）
### §2.39 Batch-10：真机验证批 + RNG 断言升级（P1-4 正式收口）（2026-09-10）
批次: batch-10 | ActionId: 无 | 产物: GameCoreBridge.cpp 四入口由 jniWarnRngOffEngineThread 改 **jniRequireEngineThread**（过渡守卫函数删除 + 三段注释更新）、GameCoreBridge.kt 契约头并入 kEngineOnly、GameRngManager.kt 线程契约注释同步；证据落档 docs/parallel-batches/batch-10-verification-record.md
硬规格: **执行环境**——物理真机不可得，按 §2.6 升级条件原文"真机/**开发期 debug 构建**运行"口径用 MuMu 模拟器 12（Android 15/SDK 35，x86_64+arm64-v8a ARM 转译，机型档案 Redmi K50）执行，模拟器提供真实 ART/JNI 线程模型与渲染链，物理真机专属残留逐项登记
红线: B 组观察窗 ≥60 分钟混合操作（90,738 行 logcat 全量落盘）——`RNG 通道跨线程进入` **零出现**（owner rebased 合法重锚 1 次，零 FATAL/SIGABRT）→ 断言升级唯一生产代码改动；断言版复跑（B4）无误杀（读档/存档/切后台全过零 abort）；debug 原生产物字符串核验（断言串在位/WARN 串消失）、release 零守卫串（NDEBUG 擦除不变）
登记: 真机残留清单（物理设备到位后补验，缺陷修复另批）——A2 ASTC 缺失机 RGBA 回退、A4 旋屏、C1 偷盗钩子自然触发 + TapDB 上报、C3 S5 战斗任务、C4 S6 秘境全链、C6 ThermalMonitor 真实热档、D2 放置确认步、D3 道路装配、E2 云存档（保护性跳过）、E3 WS-1 绝对值（需先补 debug 埋点小批）；其余验证组（P0-3 主链路/软渲强制/切后台重建/buildSaveSnapshot 往返/S4 月结年结/S7 排班/S-20 购买/S1-S3 涌现/D1/D4/D5 渲染/E1 ≥100 月零 ANR）均通过
## 2.30 Batch-01（2026-09-10）：detekt 拆分任务队列——core:engine 域管理者族大规模真实拆分（baseline 34→3）
批次: batch-01 | ActionId: 无 | 产物: engine baseline 34 条（26 TMF + 8 LC）逐类处置——23 个 TMF 类真实拆分至类内 ≤19 函数（**共 54 个新域文件**，函数体逐字节 diff 校验，仅签名行改写 + 统一去缩进 4）、8 个 LargeClass 全清（含 2 个 LC 测试类按 fixture 拷贝 + 用例迁移）、3 个 FacadeImpl override 契约骨架文件级豁免；InventorySystem 105→13（7 文件）/ DiscipleStatCalculator 85→0（7）/ GameEngineCore 54→2（4）/ BattleSystem 47→0（4）；baseline 34→**3**，guard core/engine=3
登记: **诚实回退/余量装回 3 条**——SectPolicyToggleUseCase TMF 40 与 CultivationService TMF 50 整类装回（前者全部 public 开关 API 被 feature:game 直连消费、扩展对其他模块不可见，触碰 app/game 属本批禁区；后者 batch-09/W3 并行会话同域重构，按"后到者避让"）、UnifiedPerformanceMonitor TMF 37 部分装回（仅拆出运行期采集域 7 函数）——三条后由 §2.30.1 全部清偿
坑: 机制发现八条——detekt XML 报告对**中文文件名**做 HTML 实体转义（修剪脚本须 html.unescape）；`git status` 中文路径须 `-c core.quotepath=false`；msys subprocess grep 的 `\(`/`\b` 被吞（ERE 用 `[(]`）；detekt VariableNaming 对 internal 下划线后备字段报违规而 private 不报；成员扩展函数引用类属性/注入服务时移出类即失去外层接收者；嵌套类型/泛型签名须显式处理；**通配符 rm 误删已提交文件**（禁用宽通配，靠编译网兜底 + git checkout 恢复）；KSP kspCaches 多进程并行下频繁损坏（清 build/kspCaches + generated/ksp）
预警: 本节原记"触碰面 detekt 0 违规 + 主源编译通过"**与实测不符**（实测清单见 §2.30.1 预警）；本节其余口径（拆分清单 / 域产物 / 装回项）经续修复核成立
## 2.30.1 Batch-01 续修（2026-09-10）：拆分损伤根治 + 验证收口（baseline 3→2，全量单测 3140/0）
批次: batch-01 续修 | ActionId: 无 | 产物: 损伤根治 11 类（13 处畸形函数签名机械还原 / 5 个函数 + yearlyOpsQueue 字段以 HEAD~2 原文逐字还原 / 悬空 KDoc+@Suppress 残块清理 / 2 个新测试类结构损伤还原 + fixture 上提基类 / **KDoc 与注解错挂或悬空 101 处逐声明归位（补回 258 / 替换 53 / 删孤儿 45 / 保留 15）** / 测试源 188 处 import 补齐 / 18 个被 mock 入口还原为类成员 / 测试族共享 fixture 上提 ProductionProcessorTestBase / 对象阈值与类阈值边界再下放 / **跨模块可见性 149 处回写 public + import 补 29+12** / 架构守卫源路径 4 处同步 / 次生 detekt 违规全实修零装回）+ 续修新增清偿（CultivationService 42 委托函数下放 6 域文件；SectPolicyToggleUseCase 22 开关下放 2 主题扩展文件；UnifiedPerformanceMonitor 指标注册表上提基类 PerformanceMetricsRegistry）；**baseline 3→2→0（engine 全清）——六模块 baseline 至此全为 0**
硬规格: 根因（实测证据）——① 拆分工具多包一层括号并吞掉参数名首行；② 切块边界对 strip 行匹配误判（声明头写入而函数体丢失）；③ 尾块截断；④ **成员下放为同包扩展后跨包调用方缺 import**；⑤ **被 mock 的成员下放为顶层扩展后无法 stub/verify（`whenever(mock.fn())` 直接执行真实扩展 → NPE 216 例）**；⑥ 内联化统一写 `internal` 而原成员是 public（跨模块 39+18 处不可解析）；⑦ 对象阈值 thresholdInObjects=12 与类阈值口径错配
红线: 跨模块消费下界两条全清——SectPolicyToggleUseCase（22 政策开关下放 2 个同包主题扩展文件，类内保留 18 函数）；UnifiedPerformanceMonitor（**指标注册表/监听器域上提基类**——本类是跨模块消费面且被 core:engine 测试以 mock 作为缝隙，扩展化会同时破坏跨模块调用与 mock stub/verify 语义，继承则两者零变化）
登记: 跨批阻塞清除——`:app:hiltJavaCompileRelease` 的 Dagger 环（batch-09 未提交 WIP 引入 `DiplomacyService`/`VassalService`/`GiftService` 构造注入 `GameEngineCore?`）根治为 `Provider<GameEngineCore>?` 惰性边 + 私有取值访问器，调用点与测试直构零变化；提交 `a45692b` 为"可编译可验证最小集"（本批触碰面 + 组 C 在途 Kotlin 改动合并提交并在说明中显式登记）；batch-04 所有权补偿 10 处补做
预警: **结构批的自评结论必须独立实跑复核**——本批开工实测 HEAD 主源 357 处编译错、detekt 崩溃（IllegalStateException: not identifier）、测试源 1043 处编译错、全量单测 216 失败
## 2.40 集成收口批（2026-09-11）：十批并行成果合流为单一可编译树 + 途中缺陷根治 + 文档/日志同步
批次: 集成收口（以 batch/02 为基线新建 integration/parallel-batches）| ActionId: 无 | 产物: 缺失 C++ 产物入库（road_tx.h / diplomacy_tx.h + 两 GTest，hash 逐字节相同）、batch-06 建筑下沉并入（4 文件 + handleBuildingTx + CMakeLists，动作号以 gen-action-ids.mjs 目录为唯一事实源重新生成）、文档并入（§2.34/§2.35 补录、ui-read-surface 建筑行、CHANGELOG、cpp-engine.md 动作计数表）
硬规格: **仓库从未做过集成**——10 批分散在 7 条分支，且 batch/02 的 C++ 树被 `a45692b`「顺手带走组 C 在途 Kotlin 改动」打断：`execute_dispatch.cpp` 已 include road_tx.h/diplomacy_tx.h 而两头文件未入库（全仓 212 处 include 扫描仅此 2 处缺失）、test/CMakeLists.txt 引用 2 个不存在测试源 ⇒ **从该提交干净检出无法编译 native（NDK）与桌面 GTest**，本机可跑只因 4 文件以未跟踪状态躺在工作区
红线: **冲突不取"theirs"**——BuildingFacadeImpl.kt 的 batch-06 版本基于 batch-01 拆分**之前**的旧结构，取 theirs 会把成员下放整体回退 ⇒ 解法 = 取 HEAD 结构 + 只补 batch-06 的 native 臂，重复声明（releaseReflectingDisciples 冲突重载）一律不引入；集成后基线：桌面 1023/1023、引擎 3146 用例 0/0/0（281 类，46 Diff 对拍类）、detekt 六模块 baseline 全 0、NDK arm64 / lintRelease / 模块回归（data 707 / game 868 / app 58）全绿
登记: 途中缺陷根治三处——① **`executeAutoBuy` 迭代器失效（真 UB）**：`SpiritStoneWallet::deduct` 内部 `gd = withSpiritStoneCount(gd, …)` 整体替换 GameData，`for (const auto& entry : gd.autoBuyList)` 的堆缓冲被释放后仍被 range-for 的 end 迭代器引用 → 同进程同输入**随机只买第一件**（曾被 §2.18 登记为"并发偶发"）；根治 = 按 Kotlin 不可变 List 语义快照迭代（修复前 12 次运行失败 4 次，修复后 40 次连续 0 失败），并举一反三扫描其余 5 处候选（均安全）；② **影子突破路径语义降级**：system/breakthrough.h 的 isDiscipleFullHpMp 恒 true + applyBreakthroughFailure 不折损 HP/MP（生产走 phase_settlement.h 完整版故无线上影响，但影子通道一旦接线即偏差）→ 按同式补齐 + 6 个 GTest 用例；③ `ProductionSlotRepository.isCacheDirty()` 零调用死代码删除
登记: 清理项——陈旧 git worktree 2 个 + 游离检出 5 处 + 74 个 `compile-*.log` + 被跟踪的一次性垃圾 + 未跟踪一次性脚本；**环境自伤一次**（`Remove-Item -Recurse` 穿透 node_modules junction 删除主仓依赖 → `ERR_MODULE_NOT_FOUND: sharp`，重装恢复）
## 2.41 W2-a（2026-09-11）：UI 操作面下沉·库存出售/上架族入 C++——出售族稳态写者归一，零 RNG 纯确定性事务
批次: W2-a | ActionId: INV_SELL_ITEM=1520 / INV_BULK_SELL=1521 / MERCHANT_SELL_ACQUISITION=1522 / MERCHANT_LIST_ITEMS=1523 / MERCHANT_REMOVE_LISTED=1524 / INV_CONSUME_MATERIAL=1525 | 产物: 新 system/inventory_tx.h（纯头）、新 inventory_tx_test.cpp（33 用例）、新 InventoryNativeTx.kt（internal 懒构造）、新 InventoryNativeTxGateTest.kt（11 用例）；改 execute_dispatch.cpp（handleInventoryTx）、InventoryFacadeImpl 九方法顶部接线
硬规格: 取价口径逐字——装备 = 模板价优先（`getTemplateByName(name)?.price ?: 品阶 basePrice`）；**功法 = 品阶 basePrice（不查模板——与上架路径 ManualDatabase.getByName 查模板不同，两条路径口径不可统一）**；丹药 = roundToInt(pillBasePrice × PillGrade.priceMultiplier)；出售价 = `(basePrice × quantity × 0.8)` 向零截断；入账来源键单类 `Sell(<itemType>)`、批量 `Sell("bulk")`（**逐条扣减零入账、末尾一次入账**——Kotlin deductStack 与 sellStack 的语义差异非笔误）、商人收购 `MerchantTrade`；consumeMaterialByName 为**入口快照语义**（Kotlin 先取快照再逐个 remove/update，扣减量以快照持有量为准；quantity<=0 恒 false、==0 恒 true 且零写入）；仓库计数含锁定堆叠但扣减只作用未锁定项（按列表序逐摞扣至 0 移除）
红线: **全链零抽取**（取价为纯函数、校验链只读、变更段仅堆叠数量+灵石余额+商人条目；签名级证据 = API 不接受 RngManager/种子；GTest 双运行全状态 JSON 逐位一致 + 全分区 RNG 快照差分）；失败信封与降级 null → Kotlin 回退原路径重执行校验链（双实现并行契约，用户可见文案由 Kotlin 臂产出）；上架条目 id 为确定性自增占位（gc-listed-N，非协议随机域）
登记: 开袋/充公族（RNG + BagItemReconstructor 模板重建）不在本批范围，留 W2 后续子批；成功臂 `applyDirtyFromNative` 脏段回读覆盖六堆叠集合 + gameData 的灵石/年度报告/商人条目（无 Room 回写——堆叠为镜像域快照列，与 batch-07 道路同口径）；防御性空安全（stateSyncServiceRef 可空局部过滤后再传递）；GameEngine / StateSyncService / GameStateStoreImpl / GameCoreBridge / models.h 零改动
## 2.42 Batch-11（2026-09-12）：库存域收官——商人购买/充公族入 C++，开袋按路线 B 诚实登记
批次: batch-11 | ActionId: INV_BUY_MERCHANT_ITEM=1530 / INV_CONFISCATE_BAG_ITEM=1531（maxId=1531，1532–1549 留空）| 产物: inventory_tx.h 追加 `buyMerchantItemTx` / `confiscateStorageBagItemTx`、inventory_tx_test.cpp +17 用例、InventoryNativeTx.kt 追加两入口 + MerchantBuyResult、InventoryFacadeImpl 两方法接线、InventoryNativeTxGateTest.kt +5 用例
硬规格: 商人购买判定序——① 商品存在 → ② D-21（price<=0 ‖ quantity<=0）→ ③ 灵石/库存早退（**仅下品余额，不含自动换算**）→ ④ 模板存在性（缺失即 TemplateMiss failure 信封）→ ⑤ 按型转换 + 容量预测（复用 merchant_settle::toXxx 与 canAddXxx 谓词——S8 autoBuy 同源，禁止复制漂移）→ ⑥ addXxx（"merchant" 年报 + **Partial 溢出转邮件视为成功**）→ ⑦ deduct **预演臂**（GameData 拷贝上先行校验，失败即 failure 信封零写入——Kotlin 对应分支为不可达死码，C++ 收敛为零写入防线）→ ⑧ 商家库存扣减；充公判定序——幂等探测（**以袋内当前条目为准**，入参可为陈旧 UI 快照）→ 数量篡改防御 → 三态物化（实例路径 = **裸 store.add**，不校验不记年报——禁用 addXxx 错记 annualEquipmentBySource；堆叠路径 = sr_session::detail::reconstructStackedItem）+ quantity=1 走 addXxx（"confiscate" 年报）→ 溢出抑制 → **仅 Success 移除袋条目**（Partial/Failure/模板缺失保留待重试）
红线: 凭据类 vs 发放类溢出语义——充公走 `withOverflowMailSuppressed`（**凭据类**：溢出不转邮件、失败保留凭据重试补齐）；商人购买 Partial 溢出转邮件视为成功（**发放类**）；开袋**路线 B 不下沉**（双重 RNG：EXPLORATION 分区 nextInt(16)/nextInt(7) + 分支内 templates.random()/generateRandom* 走 `kotlin.random.Random.Default`——非分区、不随存档走、C++ 无法逐位复现；**禁止近似复刻**，ActionId 留空登记 batch-23 待拍板路线 A）
登记: 装备实例回仓族**判归属不下沉**——充公臂在 C++ 内用等价原语（sr_session::detail::equipmentInstanceToStack + 裸 store.add），returnEquipmentToStack / returnManualToStack 保留 Kotlin 为共享回退臂 + 自愈路径复用（materializeEquipmentInstance 含实例表删除防双持有，归 batch-14 域）；充公入仓量恒 1，Partial 臂在双侧均不可达（quantity=1 要么全合并 Success 要么 Failure(Full)），GTest 以 Failure(Full) 覆盖"仓库满袋条目保留"；本批于独立 git worktree 实施（主工作区被并行批 12 占用，分支切换会复刻"带走他批在途改动"事故）
坑: 协议段并入既有 handleInventoryTx 时**范围分支上界由 INV_CONSUME_MATERIAL 扩至 INV_CONFISCATE_BAG_ITEM**（区间不含 1550+ 巡逻段——batch-12 并行批另行动作）
## 2.44 Batch-13（2026-09-12）：探索域 UI 操作面下沉 C++——世界关卡/侦察战斗执行 + 伤亡写回 + 分舵驻守（路线 (a) 变体：战斗入 C++，奖励/胜利事务留 Kotlin）
批次: batch-13 | ActionId: EXPLORE_TX_ATTACK_WORLD_LEVEL=1570 / SCOUT_SECT=1571 / ASSIGN_GARRISON=1572 / REMOVE_GARRISON=1573 | 产物: 新 system/exploration_tx.h（纯头四事务）、新 exploration_tx_test.cpp（14 用例）、新 GameEngineExplorationNativeOps.kt（ExplorationNativeForward + 战报公共重建助手）、新 ExplorationNativeTxGateTest.kt（6 用例）；改三 Ops 文件顶部 native 臂、execute_dispatch.cpp（handleExplorationTx）
硬规格: 战斗执行覆盖面已核实（S5 mission_completion.h 组装 + S6 secret_realm_session.h 执行 + Kotlin 重建战报的对拍先例）；妖兽组装两分支**均零 RNG**（pregen 钳制 [1,1e7]；无 pregen 走 resolveBeastStats 向后兼容基础值公式）；scoutSect AI 守卫 = alive ∧ realm 7..9 取前 8 保序；garrison 槽 = GarrisonSlot(index) 保留索引 + clearAllSlotsDataOnly(includeResidence=false) 全槽清理；**死亡原因串不落库**（DeathRecord 已删，isAlive/status/deathYears 三字段承载，cause 为 Kotlin markDead 校验域标签）
红线: **路线 (a) 变体声明的红线**——C++ 不写 `defeated`：胜利事务有 soulPowers + 17 分支属性表 + defeated TOCTOU 重查（与 talent effects / lawEnforcement 偷盗判定耦合），**若 C++ 写 defeated 会令 Kotlin 重查臂早退漏发魂力** ⇒ `applyWorldLevelVictoryTransaction` 原函数执行留 Kotlin；奖励生成族（BeastMaterialDatabase.getRandomMaterialByBeastType 硬编码 Random.nextDouble + 洞府三库 generateRandom + UUID id）显式登记 Kotlin 残差（非分区非存档确定性随机域）；战斗 BATTLE 分区抽取与 Kotlin executeBattleWithTimeout 同区同序
登记: 留 Kotlin 显式清单——遭遇分支（aiBeastEncounterTargets @Transient 不入镜像协议）、forceSettleDisciplesBeforeBattle（修炼结算域）、奖励生成族与胜利事务原子块、processBattleCasualties（悲痛/卸装/槽位清理编排）、战报重建/奖励卡片/assignmentGate/Room 生产仓/状态同步（平台效应）；范围边界——世界关卡月度刷新/过期清理与洞府探索会话编排已在 C++ 月结域不动，宗门战/防守战不在本批；teamCasualties 原 Kotlin 口径逐字保留
## 2.45 Batch-14（2026-09-11）：弟子管理二——生命周期/婚姻族下沉 C++——逐出/拜师/婚姻批准/释放思过/年俸开关，全族零 RNG 纯确定性事务
批次: batch-14 | ActionId: DISCIPLE_LIFECYCLE_EXPEL=1590 / APPRENTICE=1591 / MARRY_APPROVE=1592 / RELEASE_REFLECTION=1593 / SALARY_TOGGLE=1594 | 产物: 新 system/disciple_lifecycle_tx.h（纯头五事务）、新 disciple_lifecycle_tx_test.cpp（11 用例）、新 DiscipleLifecycleNativeTx.kt（internal 扩展）、新 DiscipleLifecycleNativeTxGateTest.kt（5 用例）；改 execute_dispatch.cpp（handleDiscipleLifecycleTx）、DiscipleFacadeImpl 四方法 + ~~GameEngine.approveMarriageProposal 顶部 native 臂~~
硬规格: 判定序逐字——expel（存在 → 存活(NotAlive) → 非 REFINING(SlotInvalid) → 袋物品捕获 → clearAllSlotsDataOnly 12 类槽位含住所 → destroyWornInstances → eraseDiscipleDerivedMaps（blood_refinement.h 唯一收口点复用）→ removeById + annualDesertedDisciples+1）；apprentice 三相（存在性×2 → 自拜/存活×2 → 已有师父/名额<5（仅存活徒弟））；approveMarriage（已有道侣防御检查零写入 → partnerIds 双向绑定 → recordGameEvent MARRIAGE 直写）；releaseReflection（静默 no-op 同义 → statusData 思过双键定向移除（保留其余 key）→ status=IDLE）；salaryToggle 盲写覆写
红线: **死亡标记红线（CLAUDE.md 13.3）**——逐出为**行删除**而非死亡标记：本族零 `isAlive=0`/`status=DEAD` 写入，markDead 路径不经本事务（GTest 断言行移除且 annualDeceasedDisciples 不变）；失败零写入（校验链全通过后才落写，失败信封 errorType 与 AppError.Domain.Disciple 分型同名 → Kotlin 回退重执行校验链）；**全族零 RNG**，全分区快照差分逐用例内嵌
登记: 婚姻幽灵列边界——Kotlin 原路径对"提议残留 + 弟子已亡/被逐"会写幽灵列条目，SoA 无法表达 ⇒ native NotFound 信封回退 Kotlin 原路径保行为；逐出袋物品物化从"事务内、行删除前"平移至"事务提交、镜像回读后"（终态等价；物化失败不再回滚整事务，与 Kotlin 原路径日志级失败口径一致）；袋内灵石原路径即不物化随行删除（同口径保留）；rejectMarriageProposal / 状态推导域 / lifeEvents 写 / updateDisciple(id, lambda) / 零活调用方的 add-remove-update / 丹药赏赐族（InventoryAddPathGuard 守卫面）**均审计确认不下沉**；batch-14b 补全——自由招募**归 load/基线域**（门面入口零调用方 + 新档播种语境下 native 臂写入会被基线导入整体覆盖 ⇒ 不为死 API 扩协议、不做 C++ 事务），名字随机源 `Random.Default` → **SYSTEM 分区**（用户拍板 2026-09-12；效果 = 初始三名弟子同 mapSeed 全量可复现，既有存档零影响；C++ name_service.h::generateName 已完整移植且签名即分区 RNG）
**🔴 勘误（2026-09-15 事实核查）**：上文"`GameEngine.approveMarriageProposal` 顶部 native 臂"**与代码不符**——实测 `GameEngine.kt:276` 为纯 `stateStore.update`（:277-299），**无任何 native 调用**；`DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` Kotlin 侧零引用（仅常量声明），C++ 侧 handler + 3 个 GTest 齐备 ⇒ **"有实现无接线"的死导出**，玩家审批路径仍为 Kotlin 直写 ⇒ **弟子生命周期域"稳态写者归 C++"不成立**（已登记 `ui-read-surface §4.4` 与 w3-02 批"低成本起手项"）。
## 2.46 Batch-15（2026-09-12）：弟子管理三——任命/驻守/长老单值槽/洗炼消耗族入 C++——玉符"承扣 + 运行时同步"路线落地
批次: batch-15 | ActionId: ELDER_APPOINT_TX=1610 / ELDER_DISMISS_TX=1611 / WAREHOUSE_GARRISON_TX=1612 / SPIRIT_ROOT_WASH_TX=1613 / TRAIT_ADD_ROLL_TX=1614 / TRAIT_ADD_CONFIRM_TX=1615 / TRAIT_WASH_SLOT_TX=1616 | 产物: 新 system/appointment_tx.h（纯头七事务）、新 appointment_tx_test.cpp（31 用例）、新 GameEngineAppointmentNativeOps.kt（AppointmentNativeForward + 七 native 臂 + syncJadeRuntimeAfterNative）、新 GameEngineAppointmentNativeTxGateTest.kt（11 用例）；改 execute_dispatch.cpp（handleAppointmentTx）+ 五入口文件顶部接线
硬规格: 判定序与 RNG 逐点——assignElder（弟子存在用**原样字符串相等**非 canonical 化 → 存活 → releaseDiscipleFromAllSlotsAtomic（Kotlin）→ 全槽清理数据段 → 捕获被顶替者（清后覆写前）→ 写槽位字段 + 清空六类亲传列表；回执 replacedIds = Kotlin collectReplacedIds 原样追加序）；removeElder（读 occupant → 清字段+清列表，**不**做全槽清理）；washSpiritRoot（保底计数防御 → 存在 → 存活 → 玉符余额 → 扣减 → 保底判定 + 元素洗牌 → join；保底路径 5×nextInt 零 nextDouble、普通路径 1×nextDouble + 5×nextInt）；rollTraitAdd（排除集为 **template（族）粒度** + 候选预检 → 玉符余额 → 扣减 → 品阶 40/30/30 累计阈值 1×nextDouble + 选择 1×nextInt（randomOrNull 空池不消耗，预检保证非空）→ pending 落盘同 key 覆盖）；confirmTraitAdd（**零抽取**：合法性（产物可解析 + 不在列表 + template 不重复）→ 追加 + lifespan 同步（realmMaxAge × bonusDiff toInt 截断）→ checkpoint 重记账 → 清 pending）；washTraitSlot（排除集**含目标自身**（禁止刷回原样）→ 保底路径 1×nextInt（TOP_RARITY 池，池空放弃产出 0 抽取）/ 普通路径 1×nextDouble + 1×nextInt；newId 兜底 targetId）
红线: **玉符记账路线拍板 = C++ 承扣 + Kotlin 运行时同步**——玉符余额检查 + 扣减与抽取在同一 C++ 事务内原子完成（**反向捕获为 tick 步骤⑤ 消费、非同步转发**：若由 Kotlin 承扣，tick 滞后窗口内 C++ 余额检查读到滞后值会**双花**，且"扣减失败但已抽取"违反失败臂零抽取红线）；Kotlin native 臂成功后经 `syncJadeRuntimeAfterNative(cost)` 递减运行时 totalCount 并绝对值覆写镜像值（幂等），`total < cost` 仅在运行时未锚定窗口出现（洗炼 UI 链不可达），跳过同步由 checkpointNow 哨兵 + onLoopStart 快照重锚兜底收敛到 C++ 真相；**消耗仍收敛于 JadeSymbolService 唯一入口**（Kotlin 臂零直接覆盖写 jadeSymbols 字段，JadeSymbolConsumptionGuardTest 零改动通过）；失败零写入（校验链全先行）
登记: 特质池序对拍口径——候选池迭代序 = Kotlin allXxxData.values 插入序；抽取原语逐位同源：`shuffled` = **逐元素 1×nextInt() 后稳定排序（非 Fisher-Yates）**，`random/randomOrNull` = DeterministicRng.nextInt(bound)（Lemire，low32 有符号比较）；"保底池空但候选池非空"分支（放弃产出）在真实数据下不可达（每非负面非退役族必有上品成员，GTest 性质用例锁定），按 Kotlin 同构保留为防御臂；`confirmSpiritRootWash` / `confirmTraitWash`（纯数据写、无玉符/RNG 面）与 JadeSymbolService.kt 本体（batch-19 所有权）不在本批范围（后由 §2.56 收口）
## 2.47 Batch-16（2026-09-12）：招募/派遣/俘虏残余·招募列表 UI 直调族入 C++——审计收窄批面，复用年度权威链零复制
批次: batch-16 | ActionId: RECRUIT_REMOVE_TX=1630 / RECRUIT_REFRESH_TX=1631 / RECRUIT_AGE_TX=1632 | 产物: 新 system/recruit_tx.h（纯头三事务）、新 recruit_tx_test.cpp（12 用例）、新 RecruitNativeTxGateTest.kt（7 用例）；改 execute_dispatch.cpp（handleRecruitTx）、GameEngineRecruitOps.removeFromRecruitList、RecruitService.refreshRecruitList/ageRecruitList
硬规格: 写者审计收窄——主路径（手动/一键招募、俘虏购买结算、俘虏装备物化、自动招募、年度刷新/老化/自动拒绝的结算权威）**均已在 C++**；本批只下沉真正 Kotlin 独占的三入口直调点，且**复用年度权威链零复制**（removeRecruitTx 按 id 全量过滤幂等 / refreshRecruitTx 复用 detail::processRefreshRecruitList（差值门内置于链；计数口径 generated = 列表净增 + autoRecruited）/ ageRecruitTx 复用 detail::processRecruitAging）；Kotlin 老化+净化两段与 C++ 单函数同序合并等价，双臂同源由既有 year_settlement 对拍锁定
红线: **刷新臂门控序（混合事务防线）**——Provider 缺省（测试构造面）→ flag → **世界已导入守卫**（worldMapSects 非空：开机路径 initializeWorldAndServices 在 syncNativeBaselineAfterLoad **之前**调用且 C++ 侧或持上次会话旧世界，native 臂若在该窗口点火会以旧世界宗门等级取数并把旧档 recruitList 镜像回新档）→ **差值门预检**（与 C++ kRecruitRefreshIntervalYears 同款——门内场景 C++ 链零生成而 Kotlin 臂会生成，预检防双臂分叉）→ 镜像服务可空判空；成功臂残差 = Kotlin 内存侧 RecruitLazyState 双门复位；`RecruitService` 构造新增 `Provider<GameEngineCore>?`（断 GameEngineCore→CultivationService→本类构造环，缺省 null 供测试无参构造）
登记: 商人刷新族不下沉（物品池在 Kotlin 注册表 Equipment/Manual/Item/HerbDatabase，C++ 无模板库不可复刻——路线 B 登记拆批）；**`id=""` 候选坍缩为既有 AUTHORITATIVE 基线行为**（C++ 刷新候选 seed.id=""，同次年结按 id 去重对多个空 id 候选坍缩保首，Kotlin 臂不坍缩 → 跨年手动招募池容量或受限；本批零行为变更不修复，修复须拍板另立批）；派遣域 startMission/奖励发放未派工；惰性门不迁移（月变真相源切换批）；一键招募保留专用 JNI 导出不迁移 execute 通道（零行为变更）；`nextDiscipleId` 维持 §2.16 例外口径
## 2.48 Batch-17（2026-09-12）：生产 UI 面 + 灵田种植族下沉 C++——生产域最后一格（autoHarvest 与 S4 完成结算边界审清）、灵田播种单/批原子提交
批次: batch-17 | ActionId: PROD_UI_ASSIGN_SLOT=1650 / REMOVE_SLOT=1651 / TOGGLE_AUTO_RESTART=1652 / ADD_SLOT=1653 / SPIRIT_FIELD_PLANT_ONE=1654 / PLANT_BATCH=1655 / REMOVE_ONE=1656 / REMOVE_BATCH=1657 | 产物: production.h 追加 `ui_tx` 命名空间（4 事务 + ProductionUiOutcome 八字段）、spirit_field.h 追加 `spirit_field_tx` 命名空间（4 事务 + SpiritFieldOutcome 五字段）、新 production_ui_tx_test.cpp（28 用例）、新 BuildingFacadeImpl生产UiOps.kt（8 native 臂 + finishProductionAssignment 残差 + isPlantable）、新 ProductionUiNativeTxGateTest.kt（14 用例）；改 execute_dispatch（两 handler）
硬规格: 判定序逐字——assignProductionSlot（① 目标槽存在性 ② 捕获旧 occupant ③ clearAllSlotsDataOnly(includeResidence=false) 11 类槽位清理 ④ 目标槽写 occupant ∧ 该弟子他槽清空）；removeDiscipleFromProductionSlot（槽位存在性 → WORKING 且原占用非空则剩余时长归一 `duration = max(remaining,1)`、startYear/Month = 当前；否则仅清 occupant，status 不变）；toggleAutoRestart（槽位存在性校验 → 字段翻转回传新值，零写入失败）；addProductionSlot（按 buildingId+slotIndex upsert，命中整体覆写/缺失追加）；灵田播种（① 种子存在 ② 未锁定 ③ 余量>0 ④ 空地匹配 → 首命中实例写 completionMonth=year*12+month+max(growTime,1)、completionPhase=3 → **同事务扣种**）与批量（上限 = min(余量, instanceIds.size)）；灵田移除（首命中实例清空种植字段，未知实例静默成功空操作）；校验失败错误码 `NoPlantableField`（非 InvalidSlot）
红线: **失败零写入**（C++ 校验链前置，任一不达不发脏段、不发 applyDirtyFromNative）；**零 RNG**（签名级论证 + GTest 全分区快照差分 rng.exportStates() 锁定）；**checkpoint 触达面（CLAUDE.md 13.3）**——本批八事务均**不触发** checkpointAllProduction()（仅改 productionSlots 字段与 spiritFieldPlants/seeds 余量，未引入新速率因子/政策联动/长老调整入参；production.h S4/S7 既有 checkpoint 调用面零触碰）；真相约定严格沿用 S7（C++ 镜像先行 + Room 后置回放；卸任镜像槽单槽回放 repo 并保留 buildingInstanceId——C++ 模型无该字段；任命 repo 失败回滚镜像为分配前快照且不登记 gate——4.00.91"任命不生效"主症状路径）
登记: **`autoHarvestCompletedAlchemySlots()` 保留 Kotlin 原路径不下沉**——既有读档路径调用，执行序在 syncNativeBaselineAfterLoad **之前**（C++ native 基线尚未建立，迁此入口会在首月读档产生"免费收获"缺陷）；与 S4 月结完成结算边界已审清**不存在重复计算**（S4 processBuildingProductionStep 月结权威链产出入库 / 本入口读档扫尾带"上次会话收获"标记 / manualHarvestAlchemySlot/ForgeSlot 手动收割——三路时间序不交叠），登记 ui-read-surface 生产域残余行；顺手根治 **Bug B**——灵田 Kotlin 回退臂扣种原在事务外调 `removeSeedSync` 且返回值被忽略（"种子不足也种满、免费种田"根因），改为事务内 seeds.get/remove/update；MaterialConsumptionLog（UI 流）留 Kotlin；`stateSyncServiceRef` 可空判空
坑: 共享树 `ctest` 首跑单次 `BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 失败——同次 batch-18 新文件在并行 session 编译期落盘，**per-test 进程启动时 exe 被并行构建复写**致单帧异常；**单进程 `./game-core-tests.exe` 直跑同 1204 全绿**确认非本批回归
## 2.49 Batch-18（2026-09-12）：月年边界编排族下沉 C++——引导计数面 + 政策开关事务（18a+18b），生产类政策 checkpoint 红线随下沉同步清偿
批次: batch-18 | ActionId: BOUNDARY_GUIDE_COUNTER_INCREMENT_TX=1670 / BOUNDARY_AUTO_ASSIGN_GUIDE_TX=1671 / BOUNDARY_BUILDING_GUIDE_BACKFILL_TX=1672 / GOV_POLICY_TOGGLE_TX=1680 / GOV_OPEN_RECRUITMENT_TOGGLE_TX=1681 / GOV_SPIRIT_MINE_BOOST_TOGGLE_TX=1682 | 产物: 新 system/boundary_tx.h（纯头三事务）、government.h 追加段（三事务）、新 boundary_tx_test.cpp（15 用例）+ government_tx_test.cpp（18 用例）、新 PolicyNativeTxGateTest.kt（12 用例）；改 execute_dispatch.cpp（handleBoundaryTx + handlePolicyTx）、GameEngineGuideOps 三入口、SectPolicyToggleUseCase
硬规格: 18a——incrementGuideCounterTx（键缺省 0 起算、非幂等）；autoAssignGuideBatchTx（sectPolicies 整包替换 + 三激活计数**仅激活分支写键**（未激活键保持缺失，非"写 0"）；oldPolicies 为语义形参不消费）；backfillBuildingGuideCountersTx（displayName max 语义（存量不回退）+ 幂等 + 仅结果不同才写回）；键名为 GuideCounterKeys 镜像常量（**漂移即引导进度丢失**，GTest 用真实键断言）。18b——policyToggleTx（19 政策字段名→成员指针映射，未知字段 UNKNOWN_POLICY 失败信封回退；开启 canAfford → deduct(autoConvert) → 置位 → policyActivated+1，扣不起 INSUFFICIENT_STONES **判定先于扣费 = 失败零写入**；affectsCultivationRate 同事务 checkpointAllDisciplesColumns 存活弟子列级直写）；openRecruitmentToggleTx（固定 5 万 + openRecruitmentLastPaidMonth = 绝对月）；spiritMineBoostToggleTx（免费，开启时把 spiritMineLastSettledMonth 推前到当前月——差分结算语义）；回执 `PolicyToggleOutcome.productionCheckpointNeeded` 供 Kotlin 消费
红线: **整包替换必须全包上线**（Kotlin 侧 `encodeDefaults = true`——C++ from_json 为宽松 readField，省略默认值字段会静默保留旧值致"关闭操作失效"）；**13.3 生产类政策 checkpoint 红线随下沉清偿**——生产类清单 = **炼丹激励 / 锻造激励 / 灵药培育 / 灵泉灌溉 4 项**（spiritMineBoost 不在列：灵石产出倍率由 spiritMineLastSettledMonth 时间戳差分承担，无生产槽位 duration 重算）；落点 = 开启生产类政策后触发 gameEngine.checkpointAllProduction()（native 臂与 Kotlin 回退臂**两臂同语义**；生产槽位真源在 Kotlin ProductionSlotRepository，C++ 仅月结窗口镜像 ⇒ **C++ 只回执标记、动作归 Kotlin**）；`toggle` 新增 `field: String` 形参（**逐政策显式声明 = 13.3 红线可审计载体**），14 个调用点全部补齐真实字段名；**既有判定序/扣费口径/激活计数/失败文案逐字未动**
登记: 六候选域中仅 guide 计数面三写者与政策开关三入口为 Kotlin 独占且活路径，其余六域（月年边界效果 / 战后 HP-MP / 游戏结束判定 / YearlyOpsQueue / 通用写入口 / claimGuideReward）经审计判定为死代码 / 月变事务内步骤（C++ 权威已存在）/ 通用写入口 / Kotlin 注册表不可复刻 ⇒ **只登记不下沉**；`monthlyCost` 按弟子数计费留 Kotlin 装配（DiscipleTables 真源）；新增政策须同批补字段名映射（缺名回退臂兜底，不会静默丢写）
坑: **存量缺陷修复（gate 对拍产出）**——`applyPolicyToggleFallback` / `toggleOpenRecruitment` 付费分支的 `val data = gameData` 快照原在 `wallet.deduct` **之前**取，`data.copy` 回写把首月扣费整体覆盖丢失（政策照常置位）；修为快照移至 deduct 之后，双臂同语义对齐 C++ 原子事务
## 2.50 Batch-19（2026-09-12）：UI 操作面下沉·玉符 / 宗门升级 / 邮件附件三族入 C++——兑换码与玉符购买编排留 Kotlin（RNG 红线）
批次: batch-19 | ActionId: SECT_LEVEL_UPGRADE_TX=1690 / SECT_LEVEL_CLAIM_TX=1691 / JADE_PURCHASE_MERCHANT_REFRESH_TX=1692 / JADE_PURCHASE_BREAKTHROUGH_BONUS_TX=1693 | 产物: 新 system/jade_tx.h（纯头四事务）、新 jade_tx_test.cpp（17 用例）、新 JadeNativeTxGateTest.kt（10+ 用例）；改 execute_dispatch.cpp（handleJadeTx）、GameEngineSectLevelOps.kt、GameEngineJadePurchaseOps.kt、JadeSymbolService.kt（新增 `syncBalanceFromSnapshot()`）
硬规格: upgradeSectLevelTx（校验玩家宗门存在 + 严格升级（降级直接失败），**只写玩家宗门条目**，其他宗门原样——与 batch-13"门派是外部状态"同口径）；claimSectLevelRewardTx（7 天冷却先验，materials 用 nextItemId("gc-mat") / storageBags 用 nextItemId("gc-bag") 生成 id，spiritStones 直接加）；purchaseMerchantRefreshTx（limit-check 先于扣费，扣 jadeSymbols，钳制到 [0, maxChances]）；purchaseBreakthroughBonusTx（校验弟子存在 + alive，limit 先于扣费，写 statusData["adBreakthroughBonus"] = javaDoubleToString(bonus)——**`std::to_chars` 最短往返 = Java Double.toString 序列一致**）
红线: **13.3 红线 1（玉符绝对值覆盖写）**——`syncBalanceFromSnapshot()` 在 native 臂成功后**必调**（仅把 totalCount 锚到 `stateStore.gameDataSnapshot.jadeSymbols` 绝对值，不触碰 accumMs/lastSampleMs/dayAnchorMs 运行时采样参数；否则 checkpointNow 回涨），且成功后**两件必做** = ① syncBalanceFromSnapshot ② publishJadeSymbolStateNow（UI 广播）；**13.3 红线 2（凭据类溢出抑制）**——claimSectLevelRewardTx 显式 `overflowMailSuppressed=true` + 容量预检失败时**整体 snapshot-rollback**（不留半成品 + claimRecords 不写）；零 RNG（纯头签名级 + GTest 全分区快照差分）
登记: **兑换码 redeemCode 不下沉**（C++ 只有 REDEEM_* 静态原语，**无 `EquipmentDatabase.generateRandom` 物品随机生成器对应物**——抽取序无法逐位复刻，下沉会把发行 RNG 序漂移到 C++ 通道 = RNG 红线踩破 ⇒ 按路线 (b) 留 Kotlin 原路径，batch-04 取消传播前置 catch 语义保持）；**邮件附件发放不下沉**（附件类型由 mail 模板枚举静态定，发放原语已入 inventory.h 的 addXxx 统一入口，本批无新写者；凭据类/发放类分类已在 inventory_tx 边界承担）；广告 SDK / TapDB / 平台支付 / 邮件网络投递 = 平台效应留 Kotlin；`syncBalanceFromSnapshot` 公开方法**仅本批 native 臂调用**，其它入口不动；冷却判定与原路径同语义
坑: 测试源编译被并行 session 在途破损测试文件阻断（本批 0 错误归属）；`JadeNativeTxGateTest` 暴露 `claimSectLevelReward` 首领后**凭据未持久化**（`FakeAtomicStateStore` 事务缓冲与字段交互的环境缺陷，用例改"直接播种冷却凭据"绕开），并已按根因修复生产侧静默失败（`writeSectLevelRewards` 返回 Boolean + 调用方明确失败文案）
## 2.51 Batch-20a（2026-09-12）：秘境平台段读档恢复事务下沉 C++——写者审计收窄批面（start/choose/end 已于 S6），唯一未下沉写者 continueSecretRealmExploration 落地 + 月结到期判定 45 年移植缺陷顺手根治
批次: batch-20a | ActionId: SECRET_REALM_CONTINUE_TX=1710 | 产物: 新 system/secret_realm_platform_tx.h（sr_platform 域）、新 secret_realm_platform_tx_test.cpp（12 用例）、新 SecretRealmContinueNativeTxGateTest.kt（4 用例）；改 `GameEngineSecretRealmOps` + `GameEngineSecretRealmNativeOps`（`continueSecretRealmNative` 走 `SecretRealmNativeForward.tryForward`）、execute_dispatch（handleSecretRealmPlatformTx）
硬规格: continueSessionTx 判定序与 Kotlin 逐位一致——① 到期守卫（`spawnYear + kOpenYears(=5) <= gameYear` → closeSecretRealmByExpiry 状态段复用 settlement.h：灵石入钱包 + 背包清空 + 会话/秘境/AI 队伍清场 + 冷却年 + 事件；closeDraft 信封回传，Kotlin 复用 applyExpiryCloseDraft 通道）；② 死局防御（会话不 active / 秘境不存在 / secretRealmId 不匹配 → active 时 endSession(kEndExplorerEnd) 结算入仓 + 溢出草稿回传）；③ 成员净化（aliveIds = DiscipleStore SoA `ids ∧ isAlive`（与 Kotlin assembleAll().filter{isAlive} 同源）；`!isDead ∧ discipleId ∈ aliveIds` 过滤；空 → endSession(RESET)；减少 → members 写回 PURIFIED）；④ NONE
红线: **零 RNG**（全分支签名级无 RngManager 入参 + GTest 全分区快照差分）；**失败零写入**（判定即行动作、无失败臂，未触发分支状态零触碰）；行动作扇出——EXPIRED → applyExpiryCloseDraft（关闭邮件 + gate release，slotId 取快照 currentSlot）；RESET → gate release 释放面；NONE/PURIFIED → 净化后成员 assignmentGate.confirmAssign（读档 gate 为空重建，镜像已由 tryExecuteNative 内 applyDirtyFromNative 回写）；溢出草稿经 deliverOverflowDrafts 同一投递通道
登记: **20b 未派工**（攻宗/执法/战利品/AI 参战准备留后续，见 §2.51b）；`autoAssignSecretRealmTeam` 纯只读选择器不下沉；pause/resume/renew 为运行时时钟平台残差（GameEngineCore 暂停锁/租约看门狗，内存运行态非游戏数据）不下沉；批次文档预写的"8 处 update"以审计实测为准（start/choose/end 的 update 属 S6 两臂既有代码，非本批新增写者）
坑: **顺手根治真实移植缺陷**——`secret_realm_settlement.h` 的 `kOpenYears` **原值 50 误取 `COOLDOWN_YEARS`**，Kotlin 权威值 `GameConfig.SecretRealm.OPEN_YEARS = 5` ⇒ AUTHORITATIVE 月结到期判定"秘境现世满 5 年后还要再挂 45 年"，与 Kotlin 回退臂口径分裂；改为 5 并附勘误注释，到期判定单源锚定 `secret_realm_cfg::kOpenYears`
## 2.52 第二轮集成收口（2026-09-12）：batch-11 与 batch-14 分支成果并入主树 + 协议原子变更集合并
批次: 集成收口（第二轮）| ActionId: 无 | 产物: 并入 batch-11（inventory_tx.h + buyMerchant/confiscate + 测试 + InventoryNativeTx.kt + InventoryFacadeImpl.kt）与 batch-14（disciple_lifecycle_tx.h + 测试 + DiscipleLifecycleNativeTx.kt + GateTest + DiscipleFacadeImpl.kt + DiscipleService.kt）；协议由 gen-action-ids.mjs 再生成（**154 动作，maxId=1710** = 147+2+5）；execute_dispatch.cpp（handleInventoryTx 追加 2 case + 范围上界 1525→1531；新增 handleDiscipleLifecycleTx 5 case）；test/CMakeLists.txt 追加；handover §2.42/§2.45 + §3 验证行 + CHANGELOG 三条目补录
硬规格: **背景必须如实记录**——本轮 `.git` 对象库两次被破坏（refs/logs/worktrees 被删除，pack 缺失，main 与全部 tag 的 ref 指向不存在对象），远端 GitHub 在会话中不可达 ⇒ **历史提交不可恢复**，"合并提交"落地为"以工作区文件为唯一事实源重建单一可编译树后提交"；各批成果以工作区文件形式保全（三工作树）；并入判定 = batch-11 逐文件**超集校验**（mainOnly=0）+ batch-14 逐行核对为纯追加；顺带修复 SpriteAtlasDefGeneratedTest.kt 缺回 `private data class StructureDef`（纹理并行批重构误删，致 core:engine 测试源整模块编译红）
红线: 协议生成物由单一事实源重生成（非手工拼接）；分发表两侧 handler 独立命名、范围区间与既有批不重叠
登记: **未达项诚实口径**——引擎全量 3218 用例 **15 失败**（BootSequenceControllerTest 10 / ProductionUiNativeTxGateTest 4 / JadeNativeTxGateTest 1），三者被测主体均不在本次合并触碰面（哈希比对为其他并行工作流改动）⇒ 登记为**并行工作流在途失败，本批不代改**，不计入"全部通过"；batch-12 已清偿（§2.43）、batch-20b 已改判清偿（§2.51b）、batch-21 前置仍未达成；detekt / NDK arm64 / lintRelease / 模块回归因 15 处既有失败与纹理工作流在途状态未在本批重跑
## 2.53 Batch-21（2026-09-15）：反向通道逐域关闭批——写者穷尽审计（288 站点）改判"全关不可行" + 逐域关闭机制落地（67 gameData 字段 + 1 顶层段关闭）
批次: Batch-21 | ActionId: 无（纯 Kotlin 面 + 协议键集裁剪，无新事务、无 C++ 改动）| 产物: 新 `ReverseChannelPolicy.kt`（逐域审计结论表 + 关闭清单 + 在册保留清单 + 逐域回滚 + 关闭域写入检测）、新 `ReverseChannelPolicyGuardTest.kt`（6 用例，含穷尽分类守卫）、新 `ReverseChannelCloseoutTest.kt`（7 用例）、新 `ReverseChannelVolumeProfileTest.kt`（4 用例，体积构成实测）；改 `StateSyncService.kt`（信封过滤 + 关闭字段检测 + C++ 已知值基线）、`GameStateStoreImpl.kt`（捕获侧逐域闸门）、`FakeGameStateStore.kt`（测试替身同源）、两处反向/Gate 测试（+2 用例）；文档 `ui-read-surface.md` §4.4（审计全文）+ §4.1/§4.3 + 本文件 §2.53/§3/§4.1/§5/§6
硬规格: **关闭前置复核 = 全仓反向捕获写入点穷尽审计**——`stateStore.update {}`（非镜像事务）+ `updateGameData{}`/`updateGameDataSync{}` 包装器共 **288 站点 / 100 文件**逐条判定六类（`MIRROR` 不参与捕获 / `FALLBACK_ONLY` 仅 native 臂未执行时可达 / `NATIVE_ARM_RESIDUAL` native 成功后仍执行的 Kotlin 残余 / `STEADY_KOTLIN` 稳态无 native 臂 / `LOAD_BOOT` 新档读档重启 boot / `UNKNOWN`）；**结论：14 个域无一可整体关闭**——弟子表稳态写者 46 站点、9 类实体集合全部有稳态写者（82 站点：equipmentStacks 9 / equipmentInstances 13 / manualStacks 10 / manualInstances 15 / pills 8 / materials 11 / herbs 9 / seeds 9 / storageBags 1）⇒ **弟子通道与 9 类集合段全部保留传输**；第二轮**字段级深审**（97 候选字段逐点分类 `STORE_STEADY`/`STORE_FALLBACK`/`STORE_LOAD_BOOT`/`STORE_DEAD`/`NOT_A_WRITE`）裁决 71 可关 / 64 必须保留，最终**落地关闭 67 字段 + 1 顶层段**
关闭机制: **单点策略 + 双端闸门**——`ReverseChannelPolicy` 为唯一真相源：捕获侧（`captureReverseDirty`：弟子通道 + 9 类集合引用变化即停载荷构造，省 O(n) 差集与全实体序列化）/ 信封侧（`buildReverseEnvelope`：gameData 字段级 dirty 集过滤 + 顶层段 + 集合段）双端同源门控；**逐域回滚** `reopenDomain(domain)` 一键恢复该域全部单元；**关闭域写入检测**——关闭后若仍有 Kotlin 写者触碰该单元即为回导缺口（集合/弟子通道在捕获侧引用变化即命中，gameData 字段在信封构建时与"C++ 已知值基线"比较命中，命中即 `DomainLog.e` + 诊断计数 ⇒ 漏域从静默丢数据变为可归因缺陷）
关闭清单: **顶层段 1**（`lockedBeastIds`——batch-23 已把 UI 锁定/解锁操作面下沉 C++，C++ 事务 `BEAST_VIEW_LOCK_TX` 幂等无失败面，Kotlin 仅剩回退臂写者）+ **gameData 字段 67**（按域：月年编排面 27 = 政策开关/引导计数/设置项族 17/年度报告与宗门等级 6/`isGameOver`/`openRecruitmentLastPaidMonth`；存档面 9 = 时间三件 + `currentSlot`/`saveVersion`/`mapSeed`/`id`/`lastSaveTime`/`rngStates`（信封本已显式剔除）；外交面 6；库存面 4；巡逻面 4（含**死 API** `patrolConfig`）；道路 1（`roads`）；弟子面 5（血炼完成链字段与 `pendingTraitAdds`/`battleTeamsInitialized`）；秘境 1（`cultivatorCaves`——洞府探索整族实为死链）；生产 2（`unlockedManuals`/`unlockedRecipes`）；招募 4；AI 1（`aiSectPersonalities`）；战斗 6）
红线: **关闭动作零行为变更**——关闭前后同一窗口的存档/镜像逐字段一致（捕获侧只影响"是否进入反向信封"，不改状态本身）；**回退臂语义不依赖反向通道**（native 不可用时整条 native 通道本就不存在）；**失败信封回退是已接受的缺口**（回退臂在业务失败信封时也会执行，双实现逐位一致契约由各域 GateTest 守卫；关闭域写入检测对其可观测）；**diff 对拍全绿为关闭的验收门禁**（关闭使对拍红 ⇒ 该字段必须保留传输，不得改对拍迁就关闭）
登记: ① **对拍 harness 覆写 4 字段**（`spiritMineLastSettledMonth`/`annualAlchemyCount`/`availableMissions`/`yearlyReports`）——`DiffAuthoritativeTickTest` 把 **Kotlin 月/年完整编排**纳入 AUTHORITATIVE 稳态，而生产月结走 C++ `runMonthSettlement`（`GameEngineCoreMonthOps.kt:68`）⇒ **该 harness 是生产超集**，其覆盖字段一律保留传输（可选清偿 = harness 对齐生产后重评）；② **弟子通道与集合段不可关的根因**（46 + 82 站点稳态写者）= "UI 操作面逐域下沉"剩余工程量，按 `ui-read-surface.md` §4.4 残余清单推进；③ **审计途中发现的既有缺陷**（未在本批修改）：`GameEngine.updatePatrolConfig` 死 API、洞府探索整族死链（唯一入口 `CultivationService:178` 零调用，与 `ui-read-surface.md:144` 记载不符）、`InventorySystem.materializeDiscipleBagAndMarkDead` 成员/扩展同签名遮蔽、18+11+5 处零调用者死代码站点、`GameData` 四个零调用方辅助函数
验收: 反向信封体积构成实测（单元 harness，`ReverseChannelVolumeProfileTest`）——稳态窗口（40 弟子 / 20 丹药 / 3 gameData 字段 / 1 弟子 / 1 集合变更）**合计 20121B，其中 gameData 段 38B、弟子段 2462B、pills 段 17549B** ⇒ **体积主因是实体通道的全实体 upsert**（gameData 字段级 dirty 集已非瓶颈），与"集合/弟子通道保留传输"的审计结论互为印证；`lockedBeastIds` 段关闭实测省 39B/窗口（该段 21B + 包装）
结果: **M3 剩余主项"反向同步通道按域全关"改判为长期主轴**——前置"各域稳态写者归 C++"经 288 站点穷尽审计**不成立**（推翻 §6 曾记的"关闭前置全部达成，可开"）；本批交付**可证关闭面**（67 字段 + 1 段）+ **逐域关闭机制**（策略单点 + 双端闸门 + 逐域回滚 + 关闭域写入检测 + 穷尽分类守卫），使后续每完成一个域的下沉即可一行关闭并自带可观测护栏
## 2.43 Batch-12（2026-09-13）：巡逻/住所/矿场/年俸 UI 操作面事务下沉 C++——六入口 + 写者审计实裁的四个活写者，全链零 RNG
批次: batch-12 | ActionId: PATROL_ASSIGN_RESIDENCE=1550 / REMOVE_RESIDENCE=1551 / ASSIGN=1552 / REMOVE=1553 / SWAP=1554 / AUTO_ASSIGN=1555 / UPDATE_CONFIG=1556 / UPDATE_SPIRIT_MINE_SLOTS=1557 / FIX_SPIRIT_MINE=1558 / UPDATE_YEARLY_SALARY=1559 | 产物: 新 system/patrol_tx.h（patrol_tx 域十条）、新 patrol_tx_test.cpp（31 用例）、新 GameEnginePatrolNativeOps.kt（PatrolNativeForward + 九 native 臂 + 回执解析助手）、新 GameEnginePatrolNativeTxGateTest.kt（19 用例）；改 `GameEngineAtomicAssign` 六入口 + `GameEnginePatrolOps` 四入口、execute_dispatch（handlePatrolTx）
硬规格: 复用既有地基零重写——slot_cleanup.h 的 SlotCleanupInput + clearAllSlotsDataOnly（12 类槽位，`includeResidence=false` 变体：**住所与工作共存是有意设计**）+ toMissionLiteList/mergeMissionLiteList；展示字段（name/realmName/portraitRes）**从 DiscipleStore 直读**（少一次跨语言字段搬运），Disciple.realmName 语义经 disciple::realmConfig 复刻（含 `age<5 || realmLayer==0 → "无境界"` 与 realm==0 特例）；autoAssignPatrolAtomic 的 `releasedIds` **不去重**（Kotlin pendingReleases 原始序，distinct() 在 Kotlin 侧）
红线: **全部失败零写入**（判定链先于写段）；**零 RNG 签名级**（无 `rng::RngManager&` 入参）+ GTest 全分区快照差分（含全部失败臂）+ 双运行全状态 JSON 逐位一致；Kotlin 原路径整体保留为降级回退臂；PatrolTowerViewModel / SpiritMineViewModel / SettingsDelegate 零改动（接线在 Ops 层）
登记: 写者审计**实测推翻批文"PatrolOps 只审计不实施"口径**——四个"待审计"入口实为活写者（updatePatrolConfigs 经 PatrolTowerDialog 为唯一活者、validateAndFixSpiritMineData、updateYearlySalary、updateSpiritMineSlots）本批下沉；`updatePatrolSlots` 实测生产零调用（死 API）⇒ **不为死 API 扩协议**，登记不下沉；顺手清偿两处协议/模型漂移——**① `GridBuildingData.sectId` C++ 侧缺失**（Kotlin @ProtoNumber(8) 存在且 validateAndFixSpiritMineData 以 building.sectId 对齐矿场槽位；C++ 模型无该字段 ⇒ 反向信封每次携带该键而被**静默忽略**，镜像不完整）补齐 models.h + json_codec 双向编解码；② ResidenceSlot/PatrolSlot/SpiritMineSlot/PatrolConfig 补字段序 operator==（事务"是否发生变更"判定与 GTest 逐位断言需要，纯加法零行为变更）；SlotCategoryCoverageTest 清单**不需加**新文件（槽位清理仍留守 GameEngineAtomicAssign.kt 回退臂）
## 2.51b Batch-20b（2026-09-13）：攻宗确定性写回下沉 C++——写者审计改判「大部分不可下沉」，实用两段 + 三条显式登记
批次: batch-20b | ActionId: SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX=1711 / SECT_ATTACK_GRANT_SOUL_POWERS_TX=1712 | 产物: 新 system/sect_attack_tx.h（sect_attack_tx 域两条）、新 sect_attack_tx_test.cpp（9 用例）、新 GameEngineSectAttackNativeOps.kt（SectAttackNativeForward + 两 native 臂）、改 GameEngineBattleOps.kt 两入口首行 native 臂、execute_dispatch.cpp（handleSectAttackTx）
硬规格: removeDeadDefendersTx——**仅目标池**过滤（其余池 mapValues identity 分支原样，含同 id 跨池不误删）+ **仅目标宗门**驻军槽清空（GarrisonSlot(index) 保留索引、展示字段全清）；grantWarSoulPowersTx——行序 + 存活性 + 表内三重过滤 + 逐行 +1（**逐行独立自增，与遍历序无关**）；空输入集零变更直通（客户端空集直接返回 false 免无收益跨语言往返）；`aiSectDisciples` 为 NativeGameState **顶层段**（不入 kotlinx gameData JSON——S-15 独立全量段）
红线: **三条显式登记不下沉**——① **战利品生成族**（generateWarRewards + 六类 addWar*）：RNG 红线——模板抽取 `templates.random(random)` 六个调用点**均未传 random** ⇒ 实际序列为 `kotlin.random.Random`（Random.Default）非游戏分区，C++ 无法逐位复刻；而 sectBattleRewardCount 的 BATTLE 分区 nextInt(7) 被夹在该非分区域中间，拆分即"半吊子混合态" ⇒ **整族留 Kotlin**；② **occupySectRewards / crushSectRewards**：与 grantWarRewardsInside **同一 stateStore.update 原子事务**（结构性写段：占领标记/俘虏/vassalContracts 清理/sectDetails.isOwned），奖励段不可复刻 ⇒ 原子性不可拆，整段留 Kotlin；③ **recordSectBattleRecord**：与 battleLogs（Kotlin **显示域**，不入 C++ 状态）同一事务 ⇒ 拆出会产生**撕裂事务**，不下沉
登记: 写者审计实测改判——攻宗战斗执行**已在 C++**（executeSectBattleCore 经 tryExecuteUnifiedNative、computeCanOccupy 经 sect_attack_decision.h、玩家 Combatant 组装经 mission_completion.h discipleToCombatant）；AI 弟子参战准备**非写者**（prepareDisciplesForBattle 是纯只读变换零 state 写入，13.3 红线已由 C++ 等价物 aiPrepareDisciplesForBattle 满足）；执法堂偷盗 UI 触发面**全部为结算域钩子**（月结/旬结偷盗链已下沉）⇒ 三条均登记不下沉；段内 1713–1729 未注册（前向兼容：未注册动作走 NOT_IMPLEMENTED）
## 2.55 Batch-23（2026-09-14）：残余域补齐——妖兽视图锁定 + 设置项域 17 字段下沉 C++
批次: batch-23 | ActionId: BEAST_VIEW_LOCK_TX=1730 / SETTINGS_PATCH_TX=1731 | 产物: 新 system/lock_beast_tx.h（两事务）、新 lock_beast_tx_test.cpp（17 用例）、新 GameEngineResidualNativeOps.kt（ResidualNativeForward + SettingPatchValue sealed 载体 + buildPatchArray）、新 GameEngineSettingsOps.kt（15 域入口）、新 GameEngineResidualNativeTxGateTest.kt（12 用例）；改 `GameEngine.kt` / `SettingsDelegate` / `AutoAssignDelegate` / `DiscipleDelegate` 接线、execute_dispatch（handleLockBeastTx）
硬规格: `lockedBeastIds` 落点 = **`state.lockedBeastIds`（NativeGameState 顶层段，非 gameData）**——与 Kotlin `GameData.lockedBeastIds` 的 @Transient 语义一一对应；lockBeastViewTx（Set 语义插入/剔除 + 保序 + 幂等 + lockedCount 回执）；updateSettingsTx（**字段名 → 值通用补丁**，单 ActionId 覆盖 17 字段：bool 开关 ×10（soundEnabled / musicEnabled / patrolBattleResultPopup / autoSellMidGradeForPurchase / autoSellHighGradeForPurchase / showAllAvailableDisciples / daoCompanionConsentRequired / breakthroughAutoPillFocused / autoEquipFromWarehouseFocused / autoLearnFromWarehouseFocused）+ Int 集 ×7（autoRecruitSpiritRootFilter / autoRejectSpiritRootFilter / prisonerSpiritRootFilter / breakthroughAutoPillRootCounts / autoEquipFromWarehouseRootCounts / autoLearnFromWarehouseRootCounts / daoCompanionBannedRootCounts））；**唯一归一化点 `normalizePatch`**——Int 集字段在解析阶段去重 + 保序，**比较与写入共用同一份归一化值**
红线: 全链零 RNG（签名级）；未知字段 / 类型不符 → **失败零写入**；Int 集集合语义（顺序无关 + 重复去重）；事务外残差（AudioConfig / 招募惰性门重置 RecruitResetIdle / 待清理提议 clearPendingMarriageProposals）保留 Kotlin 照原序执行
登记: **这两项是 batch-21 的硬前置**——① lockedBeastIds 是 AUTHORITATIVE 月结"锁定妖兽不被 AI 攻击"判据的输入，UI 直改期间只能靠反向通道回导才生效（增量窗口内失效——§2.21.2 曾因此专门补段），反向通道关闭后必须由 C++ 承接；② 设置项字段属 gameData 序列化面同理
## 2.56 Batch-24（2026-09-14）：弟子管理残差——灵根/特质 confirm 两入口下沉 C++
批次: batch-24 | ActionId: SPIRIT_ROOT_WASH_CONFIRM_TX=1732 / TRAIT_WASH_CONFIRM_TX=1733（maxId=1733）| 产物: appointment_tx.h 追加事务 8/9、appointment_tx_test.cpp +8 用例、GameEngineNativeOps.kt 追加 `executeRaw` + NativeRefusal/RawResult、GameEngineResidualNativeOps.kt 追加 ConfirmNativeOutcome（Applied/Refused/Unavailable）+ 两 native 臂；改 `GameEngineSpiritRootOps` / `GameEngineTraitWashOps` 首行三态分派、execute_dispatch（归入既有 handleAppointmentTx 独立范围分支，不动 1610–1616 段）
硬规格: `spiritRootWashConfirmTx`（`isValidWashedRootType` 等价：逗号切分 1~2 元素 / 无重复 / 全在 washElementKeys；空串 → 空元素 → 拒 → 覆写 spiritRootTypes + checkpoint）；`traitWashConfirmTx`（判定序 存在 → 存活 → **`isValidSlotWash` 等价**（目标在列表 + 产物可解析 + 替换后每条目可解析 + **template 无重复**）→ 目标槽位替换 + **`syncLifespanForTraitChange` 等价**（天赋/词条 lifespan 加成差 × 境界基准寿命，toInt 截断，delta==0 跳过，下限 1）→ checkpoint）；复用 batch-15 既有地基（traitKindOf / resolveOne / traitIdsOf / lifespanBonusOf / realmMaxAge / washElementKeys）零重写
红线: **失败信封保留（新能力）**——`tryExecuteNative` 把失败信封统一降级为 null（回退契约），但 confirm 两入口的**用户可见文案由 C++ 判定链产出**（弟子不存在 / 弟子已死亡 / 该特质已不存在）⇒ 新增 `executeRaw` 保留 failure 信封的 `code` + `message`（镜像回读契约与 tryExecuteNative 同源）；三态分派 Applied（直接返回 Success，C++ 内含 checkpoint 与 lifespan 同步）/ Refused（用 C++ message 作玩家可见错误）/ Unavailable（走原 Kotlin 事务体——**完整保留为回退臂**）
登记: 实测边界——① 同 template 冲突臂为**数据依赖**（真实天赋表内"同 template 兄弟条目"是否存在由数据集决定，无法稳定构造；该臂由 traitAddConfirmTx 同款校验覆盖同一逻辑形状）；② 灵根串预检在 Kotlin 侧先行（纯函数与 C++ 同源），native 臂只在预检通过后进入（零差异往返）；③ 特质表无 lifespan 加成差异时该用例 GTEST_SKIP（当前数据集有差异，实跑未跳过）
## 2.57 残余域审计结论（2026-09-14）：aiSectDisciples 自愈改判 + 月年编排残余收口
### aiSectDisciples 段：load/save 自愈写者**改判为下沉**（附证据链 + 口径勘误）
批次: 审计结论（无代码）| 审计对象: `GameEngineLifecycleOps.checkAndRepairAiSectDisciples`（`ensureGameDataIntegrity` 第二步；调用点 = boot Step 5 与 upgradeSectLevel 修复路径）——AUTHORITATIVE 稳态下仍会按需重写整个 `aiSectDisciples` 段
硬规格: 🔴 **口径勘误（初稿判错，此处为更正后结论）**——初稿断言"AI RNG 分区**不在快照协议 `rngStates` 段内**、复刻需先做 AI 域 RNG 通道统一（大立项）"，**该断言错误**（真源 `getRng(AI_SECT)` 在 AUTHORITATIVE 下已委托到 C++ `kAiSect` 分区，`exportStates()` 遍历全部分区；键 6 不写入导出面、AI 流态载体是 9 号镜像键——见 §7.1/§7.2 项 1）。真正的问题是 `AISectDiscipleManager._rng` 为**真源的影子拷贝**（`initForSlot(mapSeed)` 以 `mapSeed + 6×31337` 播种，与真源 `systemSeed + k partition` 不同源），只在 `createNewGame`/`loadData` 两处重播，之后与真源**各自漂移**；自愈路径生成链（initializeSectDisciples → fillDisciplesToTarget → ensureDiscipleGear）消费这条影子流
红线: ⇒ **下沉路径 = 先归一（摘除影子，`rng` 访问器取真源分区）+ 再下沉自愈**，规模远小于初判；缺的 C++ 原语仅**编排三件**（`initializeSectDisciples` / `fillDisciplesToTarget` / `isGearCompleteForLevel`）——生成/装备/截断原语已在 `ai_sect_recruit.h`（`generateRandomAiDisciple` / `applyGearToAiDisciple` / `truncateToAiLimit` / `aiEnsureDiscipleGear` / `aiRollMissingCategories` / `nextAiDiscipleId`）
登记: **风险与兜底**——该路径为损坏/老档自愈（正常档前置 isEmpty 判定零开销、canSkip 命中直接返回）；AUTHORITATIVE 稳态下重写会经反向通道回导生效（通道未关，语义正确）；**batch-21 关闭该域捕获前必须先处置此写者 ⇒ 本项构成 batch-21 剩余前置之一**（与 §2.55/§2.56 并列），实施计划见 [ADR rng-determinism-remediation](adr/rng-determinism-remediation.md) 阶段 1②
### 🔴 同族发现（2026-09-14，ADR 阶段 0 审计扩面）：第三类未治理随机流 `GameRandom`
登记: ~~**`GameRandom`（自建 object，XorShift128Plus）生产调用 8 处，无一为纯表现**~~ → **✅ 已由 ADR 阶段 1③ 清偿（对象已物理删除，源码零命中）**。原文规模口径**不准确**（2026-09-15 事实核查）：8 处中 **6 处为死代码**，真实生产调用 **4 处**，去向为 ① `mapSeed` 生成 → **`EngineEntropy.nextWorldSeed()` 会话熵源**（`GameEngineLoadDataOps.kt:265/336`，显式非分区、不伪装可复现）；② 天劫立绘 → `PresentationRandom`；③④ `GameConfig:421` / 弟子方差 → 形参化纯函数。原记录保留如下以便追溯：~~种子 = 挂钟时间（`AtomicReference(System.currentTimeMillis())`），`setSeed()` 生产零调用（仅测试调），且 `@ThreadLocal` 每线程独立流——其 KDoc 自称"支持种子设置以实现确定性存档"，该承诺从未实现，属死抽象~~；**删除后残留调用编译期报错 = 编译即守卫**已达成（现仅 `RngSourceGuardTest.kt:123` 守卫文本引用该类型名）。
### 月年编排残余：天劫 / 洞府探索 / 设置项三域逐个定界
登记: 设置项 **✅ 已下沉（§2.55）**（17 字段经 SETTINGS_PATCH_TX，覆盖 SettingsDelegate 6 / AutoAssignDelegate 7 / DiscipleDelegate 2 三处 writer 面）；天劫 `HeavenlyTrialSaveData.claimedRewardLevels` **登记不下沉（RNG 红线 + 原子性）**——claimClearReward 与 generateRandomPill/Equipment/Manual（templates.random()，NonDeterministic 域）同一 `stateStore.update` 且溢出抑制（凭据类）语义不可拆，recordPhaseClear 虽零 RNG 但与领奖共用同一 heavenlyTrialState 段，拆分会产生**撕裂事务**；洞府探索 `CaveExplorationProcessor` **登记不下沉（非稳态写者）**——processSectDisciplesAging / processCaveLifecycle 只在月结 Kotlin 完整编排内调用（AUTHORITATIVE 月结走 C++ runMonthSettlement + 三平台效应残留执行器，不触达本路径），属**回退臂**；`ensureGameDataIntegrity` 其余三步（checkAndRepairWorldMapSects（世界重生：C++ WorldMapGenerator 等价物不在协议面）/ checkAndRepairMerchantAndRecruit（商人物品池 Kotlin 注册表不可复刻）/ checkAndRepairWatchedItemIds（显示关注列表非权威状态））**均登记不下沉**
登记: **batch-21 关闭前置的最新口径（2026-09-14 拍板后）**——① 库存**开袋**：由"待拍板"改**已拍板走路线 A**（开袋"抽到哪一件"走 `Random.Default`，不入档不随档走 ⇒ "会破坏行为基线"前提不成立：同档两次开袋结果**今天就已不同**，改造只是把"每次不同"变为"可复现"）；② **`aiSectDisciples` load/save 自愈**：**已拍板**，路径 = AI RNG 归一（摘影子）+ 自愈下沉（原判"需大立项"经勘误后不成立）；③ `lockedBeastIds` UI 操作面 **✅ 已下沉（§2.55）**；④ 弟子管理残余 **✅ 已下沉（§2.56）**；⑤ 月年编排残余（设置项 ✅ / 天劫登记 / 洞府登记）⇒ **未关闭项收敛为 ①②，二者同属 [ADR rng-determinism-remediation](adr/rng-determinism-remediation.md) 阶段 1，交付后 batch-21 即可开（不再有"待拍板"阻塞项）**
## 2.58 随机源治理收口批（2026-09-14）：AI 播种态根因修复 + `MissionSystem` 全局解除 + 阶段 0/2/4 收口 + 两道新守卫
批次: M0 收口批 | ActionId: 无（零新增动作）| 产物: 新 `DiffAiRngSeedingTest`（3 用例）/ 新 `RngEngineIsolationGuardTest`（1 用例）；改 `AISectDiscipleManager.kt`（混种态）、`MissionSystem.kt` + `MissionSystemRewardOps.kt` + `CultivationEventMissionOps.kt` + `CultivationEventProcessor.kt` + `GameEngineMissionOps.kt`（全局解除）、`MissionSystemTest.kt` / `DiffMonthSettlementFixture.kt`（夹具适配）、`DiffAuthoritativeTickTest.kt`（镜像字段排除）、`RngSourceGuardTest.kt`（登记上限下调）、`NativeBenchmarkTest.kt`（+10k 分区基准）、`SectResponseTexts.kt`+`SectResponseTextsTest.kt`、`LoadingTips.kt`+`LoadingScreen.kt`、`CloudLayerAnimator.kt`+`NativeSurfaceView.kt`、`GiftService.kt`、`PresentationRandom.kt`（+asKotlinRandom）、`.github/workflows/ci.yml`（+RNG 红线 step）

### 2.58.1 🔴 根因修复一：`AISectDiscipleManager.initForSlot` 写裸种子（阶段 1② 遗留缺陷）
批次: 本批主体 | 症状: 引擎全量 4 处失败中的 3 处——`AISectDiscipleManagerTest` 2 例（突破失败 HP/MP 未打一折、装备孕养经验满未升级）+ `DiffYearSettlementTest` AI 招募弟子条数 `expected:3 but was:4`
硬规格: **`snapshot()` 是 PRNG 状态，不是种子**——C++ `GameCore::aiRng_`（`game_core.cpp` initialize / rngInitSystemSeed / importStateInternal 三处）与旧影子流**都经 `DeterministicRng.fromSeed(aiSeed)`** 走过一轮混种（`state = (seed shl 1) or 1` 后丢弃一次 `nextLong()`）；上一批把 `initForSlot` 写成 `getRng(AI_SECT).restore(aiSeed)`（裸种子）⇒ **同一 `aiSeed` 在两侧得到两条不同序列**
红线: 逐位实测铁证（临时探针，已删）——`aiSeed=188022`：修前 `kotlin snapshot=188022` ≠ `cpp mirror(9)=-5182850315112888150`；修后两侧**相等**且前 8 次抽取逐位一致。修法 = `val mixed = DeterministicRng.fromSeed(aiSeed); fallbackRng = mixed; getRng(AI_SECT).restore(mixed.snapshot())`
测试: 新增 `DiffAiRngSeedingTest` **3 用例**（混种态非裸种子 × 3 档 seed / 前 8 抽与 C++ 镜像分区逐位一致 / 同 seed 幂等）——把"快照是状态不是种子"这条语义**锁成可执行断言**，防下一批再踩

### 2.58.2 🔴 根因修复二：`MissionSystem` 进程级 `object` 持有可变 `rngManager`（双引擎串流）
批次: 本批主体 | 症状: `DiffAuthoritativeTickTest` 第 9 旬 `$.gameData.availableMissions size expected:1 but was:4`
硬规格: `MissionSystem` 是**进程级 object**，其 `@Volatile private var rngManager` 由 `CultivationEventProcessor.init` 注入——双引擎同进程（跨语言对拍夹具）下**后构造者覆写前者**：侧 A 的 `buildHarness` 最后执行 `MissionSystem.initialize(A的gameRng)`，于是**侧 B 的月变经 A 的委托通道消费了 C++ 的 MISSION 分区**。实测分区快照：`tick=4 cpp==kotlin`；`tick=9 cpp=-8111253402343785484 / kotlin=-1718366676291560851`
红线: 修法 = **形参必传**（消除 object 级可变状态，隔离性由构造期依赖保证，不再依赖"初始化顺序恰好正确"）——`MissionSystem` 摘除 `rngManager` 字段与 `initialize()`，`processMonthlyRefresh(existing, year, month, rngManager)` + `processMissionCompletion(..., rng)` 显式透传；`MissionSystemRewardOps` 六个扩展函数（`rollSpiritStones` / `generateMaterials` / `generateBaseMaterials` / `generatePills` / `generateEquipment` / `generateManuals` / `generateMaterialBatch` / `weightedRandom`）同步改形参
登记: **生产单引擎下行为不变**（全局本就唯一），改变的是双引擎同进程场景——从"串流"变"隔离"；`CultivationEventProcessor` 本已持有 `rngManager: GameRngManager`（构造参数），透传零新增依赖
测试: `MissionSystemTest` 41 用例全绿（夹具改持实例级 `gameRng` 逐调用点显式传参）；`DiffAuthoritativeTickTest` 100 旬全量结构对拍**全绿**
登记: 顺手处置——`DiffAuthoritativeTickTest` 的镜像字段排除集补 `availableMissions[*].id`（Kotlin `Mission.id` 为 `UUID.randomUUID()`，C++ `createMission` 为确定性自增 `gc-mission-N`；语义等价仅保证唯一，与 `sectDetails.tradeItems[].id` 同口径）
### 2.58.3 🟡 `RngEngineIsolationGuardTest`：新增工程量守卫（防下一批再引入同类全局）
批次: 本批主体 | 硬规格: 扫描 `core:engine` 主源，"`object`/单例内声明可变 `GameRngManager` 字段"即红（注释感知 + 枚举驱动 + `intentionallyExcluded` 白名单只缩不增）；**首跑即抓出 4 处同族遗留**：`AISectDiscipleManager`（豁免：AI 随机源**解析器**，状态归宿主侧）+ `EnemyGenerator` / `AISectAttackManager` / `AISectTeamComposer`（三处 `var xxxRngManager` 顶层全局 + 解析器，**同族待偿还**）
红线: 白名单每条写明豁免理由；三处遗留的偿还触发条件 = "该域出现双引擎同进程的第三个消费场景，或该域 UI 操作面下沉时顺手收敛"（登记进 §4.1）
### 2.58.4 🟡 阶段 2 收口：表现类随机迁 `PresentationRandom`（3 文件）
批次: 本批主体 | 硬规格: ① `SectResponseTexts.getAccept/RejectResponse` 内部 `responses.random()` → **形参必传** `random.nextInt(size)`（`core:domain` 不能依赖 `:core:engine` 的 `PresentationRandom`，故只去默认值陷阱；调用方 `GiftService` 四处传入 `presentationRandom.asKotlinRandom()`）；② `LoadingTips.randomTip(random: PresentationRandom)`（原 `tips.random()`）；③ `CloudLayerAnimator` 的 `random: Random = Random.Default` **默认值摘除**（R5 默认值陷阱），`NativeSurfaceView` 传 `Random(cloudLayerSeed(宽,高))` 固定种子
红线: 判定口径（ADR §8）——"该随机结果是否写入 GameData / 实体表 / 影响数值"；**`BattleDescriptionGenerator`（12 处）与 `DiscipleChatDialog`（3 处）明确不下沉本批**：前者文本入 Room `battle_logs` 实体、后者经 `DiscipleDelegate.applyConversationEffects` 写弟子 `skills`/`cultivation` ⇒ **决策类**，`R3` 明令表现流不得被决策路径调用；且 `DiscipleChatDialog` 在 UI 层消费随机属架构违规 ⇒ 归阶段 3（需 ActionId + C++ 事务）
新增 API: `PresentationRandom.asKotlinRandom()`（供 `:core:domain` 中仍以 `kotlin.random.Random` 为形参的表现类 API 消费——**有当前生产消费者**，非 YAGNI）
验收: `RngSourceGuardTest` 登记上限**只缩不增**——`core/domain` ② `7→5`、`feature/game` ② `2→1`、`feature/game` ④ `1→0`（守卫测试内以表格登记每条下调的处置依据）
### 2.58.5 🟡 阶段 0 CI 红线：以守卫测试为闸门（**不用 grep**）
批次: 本批主体 | 硬规格: `ci.yml` 的 `cpp-diff-jni-test` job 新增 step **`RNG source red-line (four entry classes)`**，显式点名跑 `RngSourceGuardTest` + `RngEngineIsolationGuardTest`；step 注释写明**为何不写 grep**（ADR §1 三条失效守卫：`.random()` 是 stdlib 扩展、`GameRandom` 是自建 object，**两者都不带 `import kotlin.random.Random`，永远匹配不到**；该 grep 断言事实上已从 CI 消失；正则无法区分注释引用与真实调用）
### 2.58.6 🟡 阶段 4 收口：10k 抽取 JNI 成本基准（阶段 3 开工前置）
批次: 本批主体 | 硬规格: `NativeBenchmarkTest` 新增 `rng partition draw 10k`——Kotlin 本地 PCG vs native JNI 标量往返（预热 5 + 采样 5 取最小值，同文件既有正确方法论；不设阈值断言）
**实测结论（桌面 JVM，BATTLE 分区，10k×10 轮）**: `kotlin(local PCG)=14ns/op` / `native(JNI scalar roundtrip)=11ns/op` / **ratio=0.8**
登记: ⇒ **ADR §8 首行"JNI 跨语言开销可能迫使阶段 3 改粒度"的风险不成立**（JNI 标量往返与本地 PCG 同量级），阶段 3 可按"逐域按调用点下沉"原方案推进；**余量提示**——桌面 JVM ≠ Android ART，真机 JNI 开销通常更高，阶段 3 每批仍须留观测面
### 2.58.7 ⚠️ 未根治项（诚实口径，勿误判为已完成）
登记: ~~`DiffYearSettlementTest` AI 招募逐字段分歧（1 例）~~ → **✅ 已清偿（§2.59.1）**——当时口径：分歧窗口实测收窄到"第二名 AI 弟子的装备/功法段"、根因未钉死（该收窄结论后被 §2.59.1 实证推翻为**探针偏置**，真因 = 夹具快照 9 号通道键垃圾值触发 C++ 存档续接覆盖，见 §2.59.1）
登记: ~~`:feature:game` 两族 10 处预存失败未清偿~~ → **✅ 已清偿（§2.59.2）**——① `GameViewModelTest` 5 处：batch-23 后设置项写路径走 `updateSettingsOrFallback`，测试捕获点在回退臂且夹具 `stateStore` 为空引用（`internal` 不可 stub）⇒ 捕获块永不落位；② `SectCameraStateTest` 5 处：相机 `clampPosition`/`minScaleBound` 契约考古（禁止直接把期望改成实现值）。**处置详见 §2.59.2**

### 2.58.8 🔴 途中发现（预存债务，本批实测暴露——`detekt` 实际在 `main` 上是红的）
登记: **§3 原写"六模块 baseline 全 0 / detekt 绿"与实测不符**——`main`（HEAD `e6707a2`）上 `./gradlew.bat detekt` **报 25 处活违规**（`core:engine` 15 + `:feature:game` 10；baseline 表确为全 0 ⇒ 属**未被 baseline 覆盖的活违规**）。
本批**顺手实修 15 处**（`InventoryFacadeImpl` 死 import ×10 / `RngSourceGuardTest` 与两个 `Diff*` 对拍的循环跳转与嵌套深度 / `AISectDiscipleManager` TMF 12-12 按"纯函数层外移"拆 `AITruncateOps.kt`）。
**余 10 处**（`:feature:game`，全部归属纹理/浮空岛渲染批次）→ **✅ 已由 §2.59.3 全部清偿**。
另两处预存债务（本批实测暴露、按最小修复处置）：`AtlasLayoutSyncTest.kt` 2 处语法错误 + `SpiritRootConfigTest.kt` 1 处非法函数名 ⇒ 三处**阻断 `compileReleaseUnitTestKotlin` 全量测试源编译**（根因 = 破在 HEAD 却无门禁覆盖——主源编译不编译测试源）。`:app` 单测 1 处预存失败 `SpriteCodegenSyncTest`（`MAP_SPRITES` 78 vs 41，生成物链路，归属纹理批）。


## 2.59 收敛清偿批（2026-09-13）：§2.58.7 三未根治项全清 + detekt `:feature:game` 10 处清偿——**引擎全量 0 失败**

批次: 收敛清偿批 | ActionId: 无（零 C++ 改动，全部 Kotlin 主源/测试源）| 产物: 改 10 文件——`DiffYearSettlementTest.kt`（快照 9 号键摘除）、`GameEngineCoordination.kt`（`updateGameDataSync` internal→public）、`GameViewModelTest.kt`（5 处捕获点切换）、`SectCameraState.kt` + `SectCameraStateTest.kt`（居中阈值回归 + 常量更新）、`NativeSurfaceView.kt`（`forceGlesForEpoch` 外移同文件顶层扩展）、`SoftwareCanvasBackend.kt`（循环零跳转 + 谓词拆分 + 参数对象化）、`IslandCliffTextureHolder.kt` / `IslandCliffTextureLoader.kt`（异常族）、`SectDiplomacyDialogTest.kt`（重命名）、`DiplomacyFlows.kt`（换行）

### 2.59.1 ✅ 根因钉死：`DiffYearSettlementTest` AI 招募逐字段分歧（§2.58.7 项一，**测试夹具缺陷**）

症状: 全量结构对拍首分歧 = `$.aiSectDisciples.ai-1[1].name` 期望="风阵玄"（Kotlin）实际="太史寒山"（C++）——**分歧从首名新招募弟子的名字即开始**（§2.58.7 "分歧窗口在第二名弟子装备/功法段"的收窄结论为探针偏置，实证推翻）。
根因: `buildAiSectSnapshot` 的 `rngStates` 只摘除了 6 号键（AI_SECT 退役），**把 `initialRngStates` 盲扫写入的 9 号键（AI_SECT_MIRROR，`fromSeed(SEED+9)` 预抽 3 次的无关状态）留在了快照里**。C++ `importStateInternal`（restoreRng=true）的"存档续接"语义 = **快照带非 0 键 9 → 以其覆盖 mapSeed 重播态**（game_core.cpp）⇒ C++ `aiRng_` 从垃圾态起步，AI 流首抽即与 Kotlin 侧 `initForSlot(0)` 的 `fromSeed(0+6×31337)` 混种态分叉。Kotlin 侧 `restoreStates`/`exportStates` 只处理 `inSnapshot=true` 分区（9 号被忽略）⇒ 单侧污染。
修法: AI 场景快照把 9 号键一并摘除（`- RngPartition.AI_SECT.id - RngPartition.AI_SECT_MIRROR.id`），两侧 AI 流种子只经 mapSeed 公式对齐（与测试注释声明的播种路径意图一致）；注释登记 9 号键"存档续接"语义陷阱。
红线: **快照协议面写 9 号键必须写 `aiRng_` 真态**（生产 AUTHORITATIVE 存档即此语义）；测试夹具盲扫填充 = 本例根因模式，后续对拍夹具一律不得整表填充通道型分区键。
验收: `DiffYearSettlementTest` 3/3 全绿——**引擎全量最后一例失败清偿，batch-21 关闭前置（`DiffYearSettlementTest` 收敛）达成**。

### 2.59.2 ✅ `:feature:game` 两族 10 处预存失败全清（§2.58.7 项二）

① **`GameViewModelTest` 5 处**（`IllegalStateException: Value not yet captured`）——batch-23 后设置项写路径 = `updateSettingsOrFallback`（native 臂在单测环境因 `NativeEngineFlag.authoritative=false` 优雅降级）→ 回退臂 **`updateGameDataSync`**（launchInScope 同步变体，internal）——测试捕获点（suspend 版 `updateGameData`）永不落位，且回退臂 lambda 经 `gameEngineCore.launchInScope` 在 relaxed mock 上不执行故零 NPE。**修法**：`updateGameDataSync` 可见性与同文件 `updateGameData`/`updateGameDataAndSync` 对齐 **internal→public**（batch-23 新增时误标 internal，非有意的契约面设计）；5 测试捕获点切至 `every { gameEngine.updateGameDataSync(capture(lambdaSlot)) } just runs`，字段映射断言零变化。（§2.58.7 预判的 testFixtures 基建经最小可见性对齐后不再需要——捕获式断言模式与该文件 40+ 既有测试一致。）
② **`SectCameraStateTest` 5 处**（相机契约考古完成，**两向漂移并存**）：
- **测试漂移 ×2**（`clampPosition - 视口小于世界时允许进入边缘带` / `clamp - cannot exceed world plus outset`）：测试常量 `edgeOutset=400f` 引用的旧 `ISLAND_EDGE_VISIBLE_OUTSET` 已不存在——2026-09 素材换代（37 张薄切片 island_edge → 7 张整块崖壁 island_cliff，最大 1180×3552，C++ `island_cliff.h` 锚定崖壁带绘制于世界矩形外侧）后实现常量 `ISLAND_CLIFF_VISIBLE_OUTSET=2500f` 有物理锚点且 KDoc 明言"修改须同步 SectCameraStateTest 期望"⇒ 更新测试常量至 2500 并登记换代史。
- **实现漂移 ×3**（`clampPosition - 视口与整岛加边缘带同宽时仍居中悬浮` / `minScaleBound - 极限缩小时整座岛完整可见且居中（横屏/竖屏）`）：崖壁批把 `clampPosition` 居中阈值从"视口≥世界"抬到"**世界+2×outset**"，而最小缩放视口（整岛适配×0.75 ≈ 1.33×世界）在 outset=2500 下**永远低于阈值**（需世界+5000）⇒ 居中分支在常见机型不可达，缩到最小后岛滞留崖壁钳制带一侧——违背 `minScaleBound` KDoc 自身承诺（"岛占视口短边 75%、**两侧各约 12.5% 天空**"= 对称居中悬浮）与崖壁批之前既有行为。**修法**：`clampPosition` 居中阈值回归"该轴视口 ≥ 该轴世界"（崖壁带钳制分支仅在视口 < 世界时生效，"拖到地图边缘完整看见崖壁"可达性契约保留在游玩缩放域）；KDoc 同步。
验收: `SectCameraStateTest` 42/42 全绿。

### 2.59.3 ✅ detekt `:feature:game` 10 处活违规清偿（§2.58.8 登记面，裸 `detekt` 门禁口径实测 9 条目）

处置 9 条目（编号见 §2.58.8 登记面）：`NativeSurfaceView` TMF 20-20 → `forceGlesForEpoch` 外移同文件顶层扩展（§2.58 `cloudLayerSeed` 同款惯例，类内 20→19）｜`SoftwareCanvasBackend.isInvalidCliffEntry` LPL 8-8 → 参数对象化 `(data: FloatArray, base: Int)`（热路径零分配不变，`drawSingleCliffPiece` 的 11 形参 @Suppress **顺带摘除**）｜同函数 CCM 18-15 → 拆两段谓词 `cliffGeometryInvalid`（CC 7）/ `cliffUvInvalid`（CC 13），求值序"先几何后 UV"逐位保持｜同文件 L1353 Loop 跳转 → 崖壁遍历循环守卫合并单出口｜`IslandCliffTextureHolder:92` TooGenericExceptionCaught → 附理由 @Suppress（解码失败源跨 IO/素材/SDK 不可枚举，§2.23.2 惯例）｜`IslandCliffTextureLoader:165` SwallowedException → catch 形参改 `ignored`（KTX 缺失走 RGBA 回退，非错误）｜同文件 `uploadOne` ReturnCount 6-5 → 拆 `uploadOne`（1 return）+ `uploadCliffTexture`（4 return）逐位等价｜`SectDiplomacyDialogTest:12` VariableNaming → `RANDOM`→`presentationRandom`（21 处引用）｜`DiplomacyFlows:122` MaxLineLength → 换行。

**验收**: `:feature:game` 裸 `detekt` **0 违规**（裸任务 = 无类型解析的门禁口径；`detektRelease` 等类型解析变体会暴露数百处 UnnecessarySafeCall/UseOrEmpty/RedundantSuspendModifier 等**从未属门禁面**的条目——勿混淆）。六模块 `./gradlew.bat detekt` 全绿。


## 2.60 拍板记录（2026-09-14）：待拍板两项裁决——② 不采纳 + ③ 完整冻结（业界完整版）
依据：业界实践对照（Paradox 模拟序契约 / 暴雪 replay 跨补丁作废先例 / Factorio·Minecraft·Terraria 生成即持久化模式），决策记录同步 `non-parallel-work.md` P2/P3。

### 2.60.1 ✅ 拍板②：P1-5 月结配对结构级优化——**不采纳（终局）**

维持 §2.15 常系数口径（rng 提升 + 位图判定，每对 O(1)）。理由：**RNG 消费序 = 行为契约**（M×F 循环形状即姻缘结果），业界对长线运营模拟的铁律是"补丁不改模拟随机序"；常数级优化已完成且**无 profiler 实证热点**（百人档月结配对 ≈ 万次 O(1) 判定，亚毫秒级，真实瓶颈在 WS-1 序列化而非配对）；结构级改动的代价（行为基线漂移 + 双端同步改算法 + 黄金用例全量重录）与零实测收益倒挂。**重开条件 = profiler 实测证明配对为月结热点**（届时按"行为基线变化拍板"流程重议）。

### 2.60.2 ✅ 拍板③：地图跨版本冻结——**采纳，完整业界方案（Minecraft/Factorio "生成即数据"模式），不做轻量变体**

登记实施批次 **WS-5b 地图冻结批**（待派工），范围（§2.19 偏差登记就此反转清偿）：

| # | 项 | 规格 |
|---|---|---|
| ① | **生成即数据** | 新档 `initialize`/`newGame`（mapSeed 确定后）由 C++ 生成地形一次，随即落为 `GameState` 权威数据——此后地形不再重算，**读档 = 读数据**（业界"区块生成后持久化"模式） |
| ② | **入权威状态协议全链** | `models.h` 增地形段 + `mapGenVersion` 版本戳；`exportState`/`importState`/`buildSaveSnapshot`/save-load 往返全链携带——**副产收益：Diff 对拍 harness 获得地形段的持续逐位校验**（生成器漂移由既有 46 场景对拍免费拦截） |
| ③ | **压缩仅存储编码** | 瓦片段 RLE（run-length）落盘（Terraria 世界文件同法，16384 整数段预期压至 KB 级）；**内存面与协议结构面保持 flat IntArray 单一表示契约不变**（§2.19 红线不破，RLE 仅为存储编码层） |
| ④ | **版本语义** | 存的地形数据**恒优先**于重算（跨版本冻结）；生成器演进只影响新档（老档老地图新档新地图——Minecraft"区块边境"模式，主动接受） |
| ⑤ | **老档迁移** | 无地形段的存量档按种子以当前版本生成（§2.19 现行为），并在下次存档回填为数据——**零回归** |
| ⑥ | **Kotlin 侧接线** | `SectTerrainBridge` 由"每会话生成 + Kotlin 缓存"改为"读权威态"（缺数据时才走生成路径并回填）；`GameConfig.SectMap` 传值口径与 `flatTileData` 不可变基座契约不变 |
| ⑦ | **测试面** | 存读往返地形逐位一致 + RLE 编解码 roundtrip + 老档缺段再生回填 + `DiffSectTerrainTest` 保持（生成器本身的双端位级锁不动） |

**🔴 前置红线**：**任何改地形输出的批次（含 WS-4 可行走语义若改瓦片输出）必须等 WS-5b 落地之后**——否则产生老档地图漂移窗口。业界成本对照（供后验）：Minecraft 老区块原样保留 + 新区块新算法 = 区块边境现象；Factorio 地图区块入档、生成器演进只影响未生成区；Terraria 全图瓦片压缩入档（数十 MB，无玩家投诉）。

## 2.61 W4-00（2026-09-15）：并行前置批——共享面结构性切分 + 分派覆盖守卫（首跑抓出 2 处死导出）

批次: W4-00 | ActionId: **-2**（删除 `INV_ADD_EQUIPMENT_INSTANCE=1011` / `INV_ADD_MANUAL_INSTANCE=1013` 两处死导出；**171 → 169 动作 / maxId=1734 不变**）| 产物: 新 `scripts/action-catalog/{core,w4a,w4b,w4c}.mjs` + `README.md`、新 `include/gamecore/dispatch_w4.h` + `src/dispatch_w4{a,b,c}.cpp`、新 `test/w4{a,b,c}_tests.cmake` + `test/dispatch_guard_test.cpp`、新 `core/domain/.../state/reversechannel/{ChannelClosureEntries,W4AChannelClosures,W4BChannelClosures,W4CChannelClosures,W4DChannelClosures}.kt`、新 `scripts/w4/{build-token,setup-worktrees,remove-worktrees}.ps1`、新 `docs/parallel-batches-w4/{README,batch-W4A,batch-W4B,batch-W4C,protocol-lease}.md`、新 `.github/workflows/ci.yml` step；改 `scripts/gen-action-ids.mjs`、`scripts/build-desktop-jni.ps1`、`scripts/build-desktop-jni-linux.sh`、`execute_dispatch.cpp`、`gamecore/CMakeLists.txt`、`test/CMakeLists.txt`、`ReverseChannelPolicy.kt`

**背景（为什么需要这一批）**: [w3 十三批](parallel-batches-w3/README.md)是长期主轴（UI 操作面收尾 → 通道删除），但**三个批次并行的前提在当前仓库形态下不成立**——逐行实测出 6 处**必然冲突面**：`execute_dispatch.cpp`（2778 行，分发表是**唯一一段连续区间 `else if` 链**，三批都只能插在最终 `else` 之前）、`test/CMakeLists.txt`（`add_executable` 的显式逐文件列举）、`scripts/gen-action-ids.mjs`（单一 `ACTION_CATALOG` 数组）+ 两份生成物（整文件重写）、`ReverseChannelPolicy.kt`（三处**单一列表字面量**，且 `verdicts` 的 BOUNDARY 块被 w3-04/05/11 三方争用、BATTLE 块被 w3-06/07 两方争用）、双更新日志（同版本条目追加到同一数组末尾）。本批把这些面上的"追加到同一位置"改造成**各批独立文件**。

**硬规格（11 项，逐项可验证）**: ① ActionId 目录切四份 + 生成器加**四条清单自检**（id 唯一 / 段区间不相交 / 条目须落自分片段内 / `core.mjs` 不得落批次段）；② 生成物合并规则成文（**冲突一律重生成**，禁手工解）；③ 分发表切三个端口 `dispatchW4{A,B,C}(GameCore&, int32_t, const nlohmann::json&) -> std::optional<nlohmann::json>`（`nullopt` = 不认领，继续后续端口 / `NOT_IMPLEMENTED` 兜底），`execute_dispatch.cpp` 只被本批改一次；④ 测试源清单切三份 `.cmake`（`include` 必须在 `add_executable` 之前）；⑤ `ReverseChannelPolicy` 三处审计数据按批切四个 closures 文件 + 共用条目助手（本体只留类型/常量/聚合/开关 API；聚合用 `groupBy` 而非 `Map.plus`——后者会在同域证据被写进两个分片时**静默丢弃**一份）；⑥ 构建串行令牌（`C:\Mnzm\.w4-build.lock` 独占句柄 + 持有者/批次/PID/时间戳，超时退出码 2）；⑦ worktree 建立/清理脚本；⑧ 协议面租约表；⑨ handover 小节预分配 §2.61–§2.65 + 共享文档收口人独占；⑩ `w4-base` tag + `git bundle`；⑪ **分派覆盖守卫**。

**🔴 项 ⑪ 首跑战绩（本批最有价值的产出）**: `action_ids.h` 新增由生成器产出的升序枚举数组 `action::kAllActionIds` / `kAllActionIdsCount`；`dispatch_guard_test.cpp` 对**每一个**已注册动作号断言"分派可达且落到本域 handler"（失败码既不可是 `NOT_IMPLEMENTED` 也不可是 `UNKNOWN_ACTION`）。**首跑即抓出 2 处真实死导出**：`INV_ADD_EQUIPMENT_INSTANCE(1011)` / `INV_ADD_MANUAL_INSTANCE(1013)` —— 两者一直**无 handler**（落 `handleInventory` 的 `default:` → `UNKNOWN_ACTION`）且全仓**零调用方**（实例轨新增实际走 Kotlin `InventorySystem.addEquipmentInstance` / `addManualInstance` 直调）。`docs/cpp-engine.md:320` 早在批 8-4 已记录"判定确认无生产调用点，无需补 handler"，但**未删除** ⇒ 留下两处死协议面。按既有**死导出纪律**（WS-0.b 先例）删除 ⇒ 本批登记为**净减 2 个 ActionId**。

**红线**: **零行为变更**——三端口骨架恒返回 `nullopt` ⇒ 所有 W4 段内动作号仍走最终 `NOT_IMPLEMENTED` 兜底（新增用例 `W4UnaffiliatedActionIdStillReportsNotImplemented` 以 1735 锁定该契约）；`ReverseChannelPolicy` 切分前后**同一存档/同一输入的状态与信封面逐位一致**（由 `ReverseChannelPolicyGuardTest` 的穷尽分类守卫证明：`closed ∪ transported == GameData 序列化面全字段`，**6 用例零改动全绿**）；`ActionIds.kt` 除删两常量外**逐字节不变**；生成器**幂等**（连跑两次产物哈希相同，实测 True）。

**顺带清偿**: `scripts/build-desktop-jni.ps1` 与 `build-desktop-jni-linux.sh` 各自**重复维护**一份 C++ 源清单（与 `gamecore/CMakeLists.txt` 合共三处独立登记点）——新增 `.cpp` 漏登记即链接期 `undefined symbol`（本批实测撞上）。已补齐两处并成文；**同时明确：三批的新事务实现在 `system/<domain>_tx.h`（头文件）+ 既有 `dispatch_w4X.cpp` 内，不需要新增 `.cpp` ⇒ 不再触发这三处登记点**。

**验收（实跑）**: 桌面 C++ **1326/1326 全绿**（基线 1322 + 4 新守卫用例；单进程直跑）；`:core:engine` **3281 用例 / 296 类 / 0 失败 / 0 跳过**（含 47 个 `Diff*` 对拍全绿，与 §3 基线逐项一致）；`:core:domain` **1758 / 0**；`:core:data` **707 / 0 / 15 跳过（既有）**；六模块 `detekt` 全绿且 baseline **全 0**（未新增任何条目）；`node scripts/gen-action-ids.mjs` 幂等 + 产物零漂移。CI 新增 `ActionId dispatch coverage red-line` step（`ctest -R DispatchGuard`，与全量 ctest 互为冗余）。

**登记**: 后续三批与汇流波的实施边界见 [parallel-batches-w4/README.md](parallel-batches-w4/README.md)——**W4-A 弟子与建设 / W4-B 内政与经济运营 / W4-C 战斗与世界协议** 三个并行批 + **W4-D 汇流波**（w3-11 是 B×C 的 join 点、w3-13 必须最后、batch-22a 与 w3-13 争用 `StateSyncService`）。冻结清单（出现在三批 diff 中即打回）：`gen-action-ids.mjs` / `action-catalog/core.mjs` / `action_ids.h` / `ActionIds.kt` / `execute_dispatch.cpp` / `gamecore/CMakeLists.txt` / `test/CMakeLists.txt` / `build-desktop-jni*.{ps1,sh}` / `ReverseChannelPolicy.kt` / `reversechannel/ChannelClosureEntries.kt` / `GameStateStoreImpl.kt` / `StateSyncService.kt` / `GameViewModel.kt` + **宿主文件族**（`CultivationEventMonthlyOps.kt` / `MonthSettlementResidualExecutor.kt` / `YearSettlementResidualExecutor.kt` / `GameEngineCoreMonthOps.kt` / `GameEngineCoreYearOps.kt` / `GameEngineCore.kt`）。

**已就地更正**: ① `GameSettingsData.autoSave` 的"保留"判定 → **用户拍板 2026-09-15「按清理执行」**（连同悬空 TypeConverter `EnumConverters.kt:30/35` 与零调用方法 `AudioConfig.updateFromSettings:21`），实施落点 W4-D/D5；② `docs/cpp-engine.md:320` 关于 1011/1013 的记载同步更新为"已删除"。

**⚠️ 途中发现（需拍板，本批未处置）**: 仓库存在 **3 个悬空 `archive/*` tag**——`archive/batch-05-dirty-ledger` / `archive/batch-06-sink-building` / `archive/batch-09-sink-diplomacy`，其 object 已不在对象库中（`git fsck` 报 `invalid sha1 pointer 62655722… / a9c905f3… / 1c3b58d8…`）。**影响**：`git bundle create --all` 直接 `fatal: bad object` ⇒ §3.1 计划的"备份纪律"若按 `--all` 字面执行会失效；本批已改为**只传可解析 ref**（实测可解析 ref 仅 `refs/heads/main` + `refs/tags/w4-base`，bundle 346MB、`git bundle verify` 通过）。这 3 个 tag 是 §2.40 记录的"`.git` 对象库两次被破坏"的残留，**已无法恢复** ⇒ 建议删除该死 tag（`git tag -d`），但**属历史元数据，本批不擅自删除**，待用户拍板。

**基线打点**: `git tag w4-base` → `d4cad20`；`git bundle` 落盘 `C:\Mnzm\backups\XianxiaSectNative-w4base-<时间戳>.bundle`（346MB，`verify` = "records a complete history / is okay"）。三个并行批次即从该点派生工作树。

## 2.62 W4-A 第一子批 A1+A6（2026-09-15，tag `w4a/01`）：w3-01 弟子操作面九事务下沉 C++ + 血炼死包装删除

批次: W4-A（worktree `XianxiaSectNative-w4a`，分支 `w4/a-disciple-building`） | ActionId: **+9**（1740–1748 `DISCIPLE_OP_*`；169 → 178 动作 / maxId=1748） | 产物: 改 `disciple_tx.h`（+970 行 W4-A 段）、`dispatch_w4a.cpp`（填充）、`w4a.mjs`、两生成物、`W4AChannelClosures.kt`（证据更新）、新 `test/disciple_ops_tx_test.cpp`（20 用例）；Kotlin 改 `GameEngineCoordination` / `DiscipleFacadeImpl战斗Ops2` / `GameEngineManualOps` / `GameEngineBloodRefinementOps` / `DiscipleFacadeImpl`（lawEnforcementProcessor private→internal）/ `DiscipleStatusService` / `DiscipleDelegate`；新 `GameEngineDiscipleOpsNativeTx.kt` / `DiscipleOpsNativeTxForward.kt` / `GameEngineDiscipleOpsNativeTxGateTest`

**下沉面（九事务，全部"校验链先行 + 失败零写入 + 回退臂保留"）**: ① 改名（names 行写 + 招募列表 `isSamePerson` 同人净化——按**改名前**身份）；② 类型直改；③ 关注切换（`statusData["followed"]` 翻转，`toggleFollowDisciple` 事务化收口原 `updateDisciple` lambda 直改面）；④ 赏赐四路合一（pill/material/herb/seed——先校验弟子存在再扣仓库；pill 走 facade 丹药链可服生效/不可服入袋同一事务）；⑤ 服药（`canUsePill` 资格链 + 日志草稿）；⑥ 功法替换（2026-09-15 核查新增写者 `replaceManual`——七链校验 + 熟练度清理 + 旧实例入袋防双持有）；⑦ 血炼启动（灵石/材料/双排他校验链 + 11 类槽位清理 + REFINING + 进度写入；Gate 释放 + Room 清理留 Kotlin 运行态残差）；⑧⑨ 状态派生单/全量（14 flag 优先级序 + positionName 定向写删 + `fixInvalidMiningSlots` 自愈——**派生列唯一计算方 = C++**，ADR 盲区 2）。

**口径差异（facade 丹药链 ≠ auto-use pill_system 链，逐字采用 facade）**: 修为直加**无上限 clamp**；治疗 maxHp 取 **baseHps 基列**（非 getBaseStats）；无 MP 恢复分支；速率生效清零旧 `cultivationSpeedBonus` 组件列 + checkpoint 同步。**偷盗判定钩子不下沉**（执法域，phase_settlement.h 同边界）：信封回传 `theftCandidate`+`moralityAfter`，Kotlin 分支以镜像刷新后的状态原序执行 `processSingleDiscipleTheft` 事务内版本（RNG 抽取序不变）。

**A6**: 删除 `GameEngine` 层 `processBloodRefinementCompletions` 死 suspend 包装（零生产调用方，仅 `GameEngineDualSlotGuardTest:370` 引用——测试改走 `MonthSettlementExecutor` 同款 `MutableGameState` 事务扩展）；`:167` 扩展保留（回退臂活路）。

**连带修复**: `month_settlement_test.cpp` `gc-inst-1` 字面断言 → "进程级计数器执行前值 +1"（TU 注册序跨工具链不可靠，新增 minting 测试先行会合法消耗计数器；孤立运行两者等价）。

**验收（实跑）**: 桌面 C++ **1346/1346**（基线 1326 + 本批 20；单进程直跑）；`:core:engine` 全量 `--rerun-tasks` + 本工作树 JNI 对拍全绿（含 47 `Diff*`）；`:core:domain`/`:core:data`/`:feature:game`/`:app` 回归绿；六模块 detekt 绿（baseline 0 增长）；`gen-action-ids.mjs` 幂等（产物哈希两次一致）；NDK `externalNativeBuildRelease` + `lintRelease` 绿；冻结清单机检：`git diff w4-base` 无冻结文件。

## 2.62.1 W4-A 第二子批 A5（2026-09-15，tag `w4a/02`）：RNG 阶段 3·弟子侧——CHAT 分区 + 守卫扩面

批次: W4-A | ActionId: **0**（**1850–1854 条件段退段空置**——ADR §8 并未要求 ActionId + C++ 事务，落地形态 = 按调用点粒度下沉/形参化，零协议改动） | 产物: 改 `RngPartition.kt`（+`CHAT(10)`）、C++ `rng_manager.h`（+`kChat` 枚举 + 播种）、`rng_test.cpp`（快照分区计数 9→10）、`RngSourceGuardTest.kt`（② 正则补 `Random.nextXxx` 裸抽取 + 分区登记 10 + core/domain ② 上限 5→13 扩面登记）、新 `GameEngineConversationDraw.kt`；改 `DiscipleChatDialog.kt`、`docs/rng-source-inventory.md` §6、`W4AChannelClosures.kt`

**治理内容**: `DiscipleChatDialog` 的 5 个裸 `Random.nextInt/nextDouble` 决策抽取点（交谈树/结果分支/效果增量——结果写弟子 `cultivation`/`skills`，R3 违规点）改为 **`RngPartition.CHAT` 引擎侧签发**（`chatDraw/chatDrawDouble` 挂起原语在引擎上下文内 `getRng(CHAT)`——GameRngManager 线程契约禁止 UI 线程直取分区句柄，故签发必须引擎侧）；文本变体（问候/回复/结束语）走 `PresentationRandom`（不落盘，LoadingScreen 同款 UI 位实例化）。CHAT 为**用户时序独立流**（id=10，参与 rngStates 快照；旧档缺失按 systemSeed+10 播种）——不与任何结算分区共用，既有分区抽取序零扰动（红线 1）。

**守卫扩面（先扩面再治理）**: ② 类正则补 `(?<![A-Za-z0-9_])Random\.(nextInt|nextDouble|nextLong|nextFloat|nextBoolean)`（负向断言排除 `presentationRandom.nextInt`/`asKotlinRandom`/`GameRandom.nextInt` 适配器误报）。扩面后 core/domain **显形 8 处存量裸抽取**（`AISectPersonality:65/:72`、`Items:875`、`BaseTemplateRegistry:143/:165/:189`、`BeastMaterialDatabase:336/:351`——逐个核实均死代码/仅测试调用方），上限 5→13 **一次性扩面登记**（显形非新增；偿还触发 = W4-D/D5 死代码清零，登记 `rng-source-inventory.md` §6.2）。扩面有效性经**负向对照**验证（上限临时 12 ⇒ 守卫红；13 ⇒ 绿）。

**弟子通道关闭口径（红线 13）**: 交谈效果**写入**仍为 Kotlin（`updateDisciple`）——决策写不上沉，写入面留 W4-D 弟子通道关闭决策；随机源已达规。通道关闭动作不在本批。

**验收（实跑）**: 桌面 C++ **1346/1346**（含 rng_test 分区计数更新）；`:core:engine` 全量 `--rerun-tasks` + 重建 JNI 对拍全绿（**rngStates 10 键跨语言对拍一致**）；守卫 6 用例绿；六模块 detekt 绿；`:core:domain`/`:core:data`/`:feature:game`/`:app` 回归绿。

## 2.62.2 W4-A 第三子批 A2（2026-09-15，tag `w4a/03`）：w3-02 弟子生命周期第二波——婚姻批准接线 + 拒绝事务下沉 + 槽位清理双路核对 + lifeEvent 分类登记

批次: W4-A | ActionId: **1750**（`DISCIPLE_LIFECYCLE_MARRY_REJECT`；婚姻批准走 batch-14 就绪地基 **1592** 接线不占新号；179 动作 / maxId=1750） | 产物: 改 `disciple_lifecycle_tx.h`（+`rejectMarriageTransaction`）、`dispatch_w4a.cpp`（+1750 handler）、`GameEngine.kt`（批准/拒绝 native 臂——**租约文件**，§5.4 第一顺位已登记）、`disciple_lifecycle_tx_test.cpp`（+2 拒绝用例）、`w4a.mjs` + 两生成物；新 `GameEngineMarriageNativeTxGateTest.kt`；改 `W4AChannelClosures.kt`（LIFE_CYCLE 证据改写）

**婚姻批准（低成本起手项落地）**: C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592`（batch-14 起 handler + 3 GTest 一直在位，Kotlin 侧零引用）本批接线 native 臂——提议存在性前置 + 名字捕获（StateFlow 读，生产 `GameStateStoreImpl` reusableMutableState 事务间持久化 + `proposalsChanged` 发射）→ C++ 配对 + MARRIAGE 事件直写 → Kotlin 仅移除提议（运行态字段）。**NotFound 幽灵列边界**（提议残留 + 弟子已亡/被逐：Kotlin 原路径写幽灵列条目、SoA 行式无法表达）→ 失败信封回退 Kotlin 原路径保行为（batch-14 口径，零语义变更）。

**婚姻拒绝（新事务 1750）**: `rejectMarriageTransaction` = MARRIAGE 拒绝事件直写（`settle_util::recordGameEvent` 完整守卫对齐），**零弟子表写入 / 零 RNG / 无失败臂**——弟子行不存在边界不产生幽灵列（与批准事务对照：批准需写行故 NotFound 回退，拒绝无行写可直达）；提议移除留 Kotlin。GTest 2 例：事件字段逐项断言 + 零弟子表写入（partnerIds/行集合/rngStates 快照差分）。

**槽位清理双路核对（计划 A2 第 3 行，核对型交付）**: `DiscipleLifecycleProcessor.clearDiscipleFromAllSlots` 的偷盗叛逃路——**结算链已由 C++ 直辖**（`month_settlement.h:1257` 叛逃段含 11 类槽位清理 + 实例销毁 + 行删除 + 年报计数，先前批次交付），Kotlin 月结链为回退臂；UI 丹药偷盗钩子按 A1 口径不下沉（执法域，信封 theftCandidate 回传 Kotlin 原序执行）；永久属性丹路经 `applyBaseAttrEffects → processSingleDiscipleTheft` 同域同口径。双重清槽防御 = 行删除后 assemble 幽灵行跳过 + 残差幂等（`tryNativeExpelDisciple` 残差在已刷新镜像上零命中）——无新增代码，核对结论登记 `W4AChannelClosures.kt`。

**lifeEvent 补写分类（计划 A2 第 4 行，登记型交付）**: `addLifeEvent`（月变自动装备）与 `initializeLifeEvents`（UI 查看补写）写目标 `lifeEvents` 为 **`@Ignore` 非序列化列**（`DiscipleSerializer.kt:28`，非协议字段）——分类 **② 纯表现**，与 batch-14 审计结论一致（"Kotlin 单源域，零协议列写者"）：不进 C++、不需回导、不设关闭单元。月变自动装备链本体在 AUTHORITATIVE 下已由 C++ 旬结直辖（`phase_settlement.h:1397`），Kotlin 链为回退臂。

**验收（实跑）**: 桌面 C++ **1348/1348**（基线 1346 + 拒绝 2 例；单进程直跑复核）；`:core:engine` 全量 `--rerun-tasks` + 重建 JNI 对拍全绿（含 47 个 `Diff*` 类）；六模块 detekt 绿（baseline 0 增长）；`:app:externalNativeBuildRelease` + `:app:lintRelease` 绿；`node scripts/gen-action-ids.mjs` 幂等（连跑两次产物零漂移）；冻结清单机检：`git diff w4-base` 无任何冻结/宿主/协议面文件（`GameEngine.kt` 为租约文件、租约表已登记）。

## 2.62.3 W4-A 第四子批 A3（2026-09-15，tag `w4a/04`）：w3-09 建筑/道路残差——槽位清扫/派生双事务下沉 + 没收臂复用 + 道路核对收口

批次: W4-A | ActionId: **1810**（`BUILDING_RESIDUAL_CLEAR`）/ **1811**（`BUILDING_PLACE_SLOTS`）（181 动作 / maxId=1811） | 产物: 新 `building_residual_tx.h` + `building_residual_tx_test.cpp`（5 例）；改 `dispatch_w4a.cpp`、`BuildingNativeTx.kt`（残差清扫臂 + 放置派生臂）、`BuildingFacade.kt`/`BuildingFacadeImpl.kt`（接口 + 实现 + 没收臂）、`BuildingDelegate.kt`（放置残差臂）、`w4a.mjs` + 两生成物；`W4AChannelClosures.kt`（BUILDING/ROAD 证据改写）

**根因修正（ADR 备选路线落地）**: w3-09 计划的"C++ 侧槽位表承载"落地为 `building_residual_tx.h` 双事务——ADR 原判 "GridBuildingData 无槽位字段" 经实测精确化：槽位本就是 gameData 协议集合（C++ 全量持有），真正的缺字段是 **C++ `ProductionSlot` 行无 `buildingInstanceId`**（Kotlin `@ProtoNumber(21)` 为 Kotlin 侧单边协议字段，`json_codec` 生产行无该键）⇒ 实例级清扫在生产行上不可表达。

**1810 清扫（拆除/没收共臂）**: 输入 = Kotlin 组装的目标投影（instanceId + C++ 可清扫组名单 + 监牢/任务阁特例标志 + 关联弟子 id——含生产 repo 侧来源）；C++ 扫 **矿场/巡逻/住所/灵田/仓库/藏经阁** 六实例键控集合 + `activeBloodRefinements` 键删除 + 监牢全量释放 REFLECTING（思过双键移除）+ 任务阁清 activeMissions/存活 ON_MISSION 回 IDLE + REFINING 破除（statusData 定向移除 buildingId）。**偏差登记（清扫范围边界）**：① productionSlots 留 Kotlin（缺字段实证如上——A4 生产域处置）；② ElderPositions 八变体留 Kotlin（clearSpec 为注册表内 lambda 单一事实源，C++ 复制即双算漂移）。patrolConfigs 不清 = 两臂 towerIdx=-1 的 bug-for-bug 兼容（Kotlin 原路径同序）。

**1811 派生（放置）**: `createSlots` 写段等价——六组建槽（基数 = 执行时现状，与 beforeNative 基数一致）+ 每塔一份 PatrolConfig；生产/长老组留 Kotlin（同偏差）。Kotlin 臂序：`tryNativePlaceSlotsResidual` 成功 → 仅承担生产槽 gameData 写 + Room 回流；降级回退原路径全量。

**没收臂复用（月变没收，调用点零改动）**: `seizeBuildingsOfSect` 首行接入 `nativeTx.removeBuildings`（与玩家拆除同一入口：1453 + 1810 + Gate/Room/sync）；`removeBuildingsInternal` 降级为回退臂。宿主文件族调用点（`GameEngineCoreMonthOps.kt:95`）零触碰。

**道路核对收口（计划第 4 行，核对型交付）**: `RoadFacadeImpl.kt:67/:85` 为 batch-07 native 臂就位后的**回退臂**（红线 3 保留）；`RoadMaskTracker`/`RoadTiling` 为 ② 类 UI 缓存；`roads` 单元已由 A1 关闭——无稳态写者，证据改写登记。

**验收（实跑）**: 桌面 C++ **1353/1353**（基线 1348 + 残差 5 例；单进程直跑复核）；`:core:engine` 全量 `--rerun-tasks` + 重建 JNI 对拍全绿（含 47 个 `Diff*` 类）；六模块 detekt 绿；`:app:externalNativeBuildRelease` + `:app:lintRelease` 绿；生成器幂等零漂移；冻结清单机检零命中。

## 2.62.4 W4-A 第五子批 A4（2026-09-15，tag `w4a/05`）：w3-10 生产残差——核对收口（零代码改动）

批次: W4-A | ActionId: **0**（1820–1829 空置） | 产物: `W4AChannelClosures.kt`（PRODUCTION 证据改写）+ `w4a.mjs`（段注释）+ 本小节——**零源码/零行为改动**

**核对结论一：自动续炼链已由 C++ 直辖。** 计划 A4 行"自动续炼槽位写者（:250/:341/:405）→ 复用 production.h 事务族"经实测**为已完成态**：`production.h:542`（自动排班）与 `:718`（自动炼丹续炼启动，头注释自证"Kotlin processAutoAlchemy 事务段等价"）即 batch-17/18 交付的 S7 地基；Kotlin `processAutoAlchemy` 链（`AlchemySystem.kt:28` 调入）仅在 OFF/SHADOW/对拍路径执行 = **回退臂**（红线 3 保留）。`validateAutoSlot` 判定链已随 S7 移入 C++。⇒ 无新增事务必要，1820–1829 空置。

**核对结论二：对齐窗口为幂等兜底，设施删除挂 W4-D/S4。** `alignMirrorFromRepository`（repo→镜像整表）与 `restoreRepositoryFromMirror`（镜像→repo 整表重放）均为幂等全表操作（读档后镜像本已对齐，对齐为兜底——实现头注释自证）；其**唯一调用点** `GameEngineCoreMonthOps.kt:72/:99` 属**宿主文件族（W4-D 独占，README §2.3）**⇒ 按本方案冻结纪律（A4 警告框："调用点的清理/删除统一在 W4-D 执行"），"C++ 权威 + Kotlin 只读"替换与设施删除在 W4-D/S4 落地（逐批强删会产生半迁移态——产线的惰性建槽等 Room 先行写者仍需窗口兜底）。登记为 W4-D 债务项（非本批遗留缺陷）。

**③ 类保留项（既有结论复核一致）**: `MaterialConsumptionLog`（UI 流/平台效应）与 `autoHarvestCompletedAlchemySlots`（读档路径 + AUTHORITATIVE 基线窗口，迁移会产生"首月读档免费收获"）保留 Kotlin——batch-17/18 结论复核一致，零改动。

**验收**: 零源码/零行为改动 ⇒ 测试面零影响（`:core:domain` 编译 + detekt 复跑绿作等价证据）；证据改写登记 `W4AChannelClosures.kt`（PRODUCTION 域）；生成器幂等（清单零变化）。

## 2.63 W4-B（2026-09-15 起批）：内政与经济运营轴——平台效应回执化

> 本小节为 W4-B **预分配小节**（README §4.6）；批文档见 [batch-W4B-court-economy](parallel-batches-w4/batch-W4B-court-economy.md)。
> 落点说明：为避免与并行批（§2.62 / §2.64）在同一文本锚点插入，本小节固定追加在 §2.67 之后、§3 之前（**数字序不代表文中位置与时序**）。
> 覆盖：B0 Jade 环境缺陷专项（无 ActionId）→ B1 w3-03（1760–1765）→ B2 w3-04（1766–1769）→ B3 w3-05（1770–1779）→ B4 w3-12（1840–1849）。

### 2.63.B0 Jade 凭据持久化"环境缺陷"清偿——契约考古推翻 §2.50 归因（2026-09-15）

**批次**: W4-B/B0（无 ActionId） | **产物**: 改 `JadeNativeTxGateTest.kt`（根因修复 + 复现守卫）、新 `FakeAtomicStateStoreContractTest.kt`（契约守卫 4 用例）、改 `GameEngineSectLevelOps.kt`（输入侧随机分区化）

**根因考古（🔴 推翻 §2.50 的"FakeAtomicStateStore 事务缓冲缺陷"归因——fake 无缺陷，生产代码亦无缺陷）**：

- **真根因是测试替身链**。`withOverflowMailSuppressed` / `withTrackingSource` 是 `InventorySystem` 的**成员函数**（`system/InventorySystem.kt:222/:234`，非扩展函数）。`JadeNativeTxGateTest` 以 plain mock 替换 `InventorySystem` 时，Mockito 把成员方法整个 stub ⇒ **传入的 block 从不执行** ⇒ `writeSectLevelRewards` 的发放体（含 `sectLevelClaimRecords` 凭据写入）被静默跳过，而 `allSucceeded` 初值 `true` 未被触碰 ⇒ 返回 `true` ⇒ 上层报 `Success`。"首领后凭据未持久化、冷却判定失效"由此而来。
- **缺陷级联的第二臂**：作用域包装器改为直通后发放体真正执行，下一站 `economyFacade.spiritStoneWallet` 未 stub ⇒ null ⇒ NPE 被 `claimSectLevelReward` 的 catch-all 吞成 `Error(...)`（当年"两臂同现 Error/空凭据的空洞等价"的另一半）。修复 = 接入**真实** `SpiritStoneWallet`（真实 `SpiritStoneLedger` + mock 事件总线——`add` 路径不触达事件发射）。
- **为什么 §2.50 误判为 fake 缺陷**：当年证据（凭据为空 + 两臂结果一致）与"事务缓冲不落盘"假说兼容；且 mockSmart 的 SmartNull 在 `when(r)` 三分支全不命中时同样跳过发放体——多条路径症状同形。本次以"把 result 塞进断言消息"的逐站取证取得直接证据（NPE 消息精确指向 wallet 调用位）。
- **连带修正**：该用例的灵石断言从"绝对余额相等"改为**增量口径**（`after - beforeOff` vs 首臂增量）——旧断言只在"两臂都是零写入"时才成立（空洞等价的又一处）；`offResult::class` 弱比较同步升为密封类全等。

**守卫固化（≥2 用例交付标准达成）**：
1. **复现用例转绿**：`claimSectLevelReward falls back identically and records claim once` 新增"首领后凭据必须持久化"硬断言（`[SMALL]` 非空——两条臂比较不得是"双双为空"的空洞等价）；
2. **契约守卫**：`FakeAtomicStateStoreContractTest` 4 用例把 fake 的事务缓冲契约落成可执行断言（顶层提交跨事务可见 / 嵌套事务同缓冲合并提交 / 事务内异常 ⇒ gameData 回滚 / **事务内快照读到事务前已提交值**——"事务内读写必须同源"契约）。任何一条变红 ⇒ fake 语义偏离真实 store ⇒ 须根因修复 fake（禁止特判绕过）。

**输入侧随机分区化（README §12 债务行清偿）**：`GameEngineSectLevelOps.buildSectLevelRewardCards` 的 `bloodMaterials.random()`（Kotlin 全局 `Random.Default`，抽取不可复现）改 `gameRngManager.getRng(RngPartition.MAIL).asKotlinRandom()`——与 `MailAttachmentDistributeOps:260` / `RedeemCodeService:151` 同分区（奖励随机生成分区）。⇒ 传入 `SECT_LEVEL_CLAIM_TX` 的兽血模板选取**可复现**，"native 臂零 RNG"表述的输入侧盲区闭合；`JadeNativeTxGateTest` 相应补 `getRng(MAIL)` stub。

**验证**: `:core:engine:testReleaseUnitTest`（`JadeNativeTxGateTest` **9/9** + `FakeAtomicStateStoreContractTest` **4/4**）；`:core:engine:detekt` 绿。

### 2.63.B1 w3-03 巡逻/住所/矿场自愈——UI 直改改走统一 native 面 + 死 API 清除（2026-09-15，ActionId **零新增**）

**批次**: W4-B/B1（w3-03） | **产物**: 改 `SpiritMineViewModel.kt`（4 处 UI 直改消除）、改 `GameEnginePatrolOps.kt`（删 2 死 API）、改 `PatrolTowerViewModel.kt`（删 1 行残留 import）、改 `W4BChannelClosures.kt`（PATROL 证据回写）、`w4b.mjs`（段内留空说明）

**实施口径（与批文档的偏差声明）**：批文档 B1 预估"经 patrol_tx.h 新增事务"；实测 **batch-12 已把十事务全部就位**（`patrol_tx.h` 事务 7 `updatePatrolConfigsTx` / 事务 8 `updateSpiritMineSlotsTx` / 事务 9 `validateAndFixSpiritMineDataTx`，native 臂 `PATROL_UPDATE_SPIRIT_MINE_SLOTS` / `PATROL_FIX_SPIRIT_MINE` / `PATROL_UPDATE_CONFIG` 全部在位）⇒ **本子批零新增 ActionId、零 C++ 改动**，工作 = 把残余 UI 直改调用方接到既有统一面上（README §12.1 YAGNI 口径：无消费者的新抽象不造）。预分配段 1760–1765 **整段留空**（禁止跨批复用纪律不变）。

**UI 直改消除（4 处）**：
1. `removeSpiritMineDeacon`（原 `:89`）：`updateGameData { copy(elderSlots = ...) }` + 手工 release/IDLE → `removeDirectDisciple(SLOT_TYPE_SPIRIT_MINE_DEACON, slotIndex)`（native 臂 `DISCIPLE_TX_UNASSIGN_SLOT` family=elderDirect + 回退臂；gate 释放与状态同步由统一路径完成。语义细化：状态由"无条件置 IDLE"变为"按在册槽位派生"——弟子兼任他槽时旧写法会把活岗打成 IDLE，统一路径修复了该边角）；
2. `removeDiscipleFromSpiritMineSlot`（原 `:147`）/ `swapSpiritMineDisciple`（原 `:183`）/ `assignDisciplesToEmptyMineSlotsInternal`（原 `:252`）：`updateGameData { copy(spiritMineSlots = ...) }` → `updateSpiritMineSlots(slots)`（同步写语义保持——原 suspend 写与现同步写在单线程引擎调度下等价）。

**死 API 清除（双证后删）**：`GameEnginePatrolOps.updatePatrolConfig`（单参版，零生产调用方；`PatrolTowerViewModel.kt:161` 是同名不同函数——其实为 VM 自身两参方法，且该文件 `:10` 的 engine 扩展 import 为**残留死 import**，一并删除）与 `GameEnginePatrolOps.updatePatrolSlots`（零调用方）。⇒ 在册关闭项 `PATROL/patrolConfig` 自此**无任何生产写者**（关闭一致性恢复）；编译全绿证明无隐式调用方。

**关闭判定（诚实口径）**：`spiritMineSlots` **保持 in-flight（不关闭）**——跨批残余写者实测在位：`CombatService.kt:106`（W4-A/W4-C 域）、`BuildingFacadeImpl同步Ops.kt:65`（W4-A 域）、`GameEngineSelfHealOps.kt:161` / `GameEngineServiceOps.kt:198`（本批 B4 面）、LOAD_BOOT 族。PATROL 域级结论留 B4 后重评，证据已回写 `W4BChannelClosures.kt`。

**验证**: `:core:engine` + `:feature:game` + `:app` 主源/测试源编译绿；`--tests "*Patrol*" --tests "*SpiritMine*"` 全绿；三模块 detekt 绿；`node scripts/gen-action-ids.mjs` 输出 `169 actions (maxId=1734)` 零漂移。

### 2.63.B2 w3-04 玉符运行时——墙钟读数参数化 + GameData 四字段稳态写下沉（2026-09-15，ActionId **1766–1769**）

**批次**: W4-B/B2（w3-04） | **产物**: 改 `jade_tx.h`（新增事务 5–8 + W4-B 勘误标注）、填 `dispatch_w4b.cpp` 端口、改 `JadeSymbolService.kt`（native 臂 + `Provider<GameEngineCore>` 惰性边）、新 `jade_runtime_tx_test.cpp`（**18 用例**）、新 `JadeRuntimeNativeTxGateTest.kt`（**4 用例**）、`w4b.mjs` + 两生成物（**169 → 173 动作，maxId=1769**）

**事务面（与 Kotlin 逐字对齐；零 RNG——签名级不收 RngManager）**：

| ActionId | 事务 | 承接的 Kotlin 写者 |
|---|---|---|
| 1766 `JADE_RUNTIME_SETTLE_TX` | `settleJadeGrantsTx`：整除发放/余量保留/headroom 钳制/拿满冻结；grants≤0 零写入回声 | `JadeSymbolService.settleGrants`（:338 写段） |
| 1767 `JADE_RUNTIME_DAY_RESET_TX` | `jadeDayResetTx`：首锚只锚定/真跨天归零 today+accum/同日与回拨零写入/**同 todayMidnight 重复调用幂等** | `maybeDayReset`（:374/:383 写段） |
| 1768 `JADE_RUNTIME_CHECKPOINT_TX` | `jadeCheckpointTx`：四字段绝对值覆盖写（拿满冻结复用 accum=0 等价形） | `checkpointNow`（:220）+ `onLoopTick` 冻结臂（:192） |
| 1769 `JADE_RUNTIME_GRANT_AD_TX` | `grantJadeFromAdTx`：jadeSymbols = totalBefore + amount（C++ 承做加法，回执权威）；不动 todayCount | `grantFromAd`（:279） |

**平台效应回执化（批文档 §2.2 四步模板落地）**：
- **读数留宿主**：单调差分/10s 裁剪/1s 墙钟节流/**Calendar 本地午夜计算**仍在 Kotlin 运行时（volatile 域）；C++ 不取时、不复刻时区规则（`rules/cpp-priority.md` §3.3）。午夜锚点 `todayMidnightMs` 作为参数推入事务 1767；
- **回执回写运行时**：native 臂成功后 Kotlin volatile（totalCount/todayCount/accumMs/dayAnchorMs）以回执重锚——与 batch-19 购买事务 `syncBalanceFromSnapshot` 重锚同方向，绝对值覆盖写模型（CLAUDE.md 13.3）不变式两端同守；
- **幂等红线测试**：事务 1767 以同一 `todayMidnightMs` 重复调用 ⇒ 第二次 `changed=false` 零写入；墙钟回退（midnight 更小）零写入（GTest `DayResetSameCallWithinTickIsIdempotent` / `DayResetSameDayAndRollbackAreZeroWrite`）。

**Dagger 破环（批文档 §5.4 第 8 条）**：`JadeSymbolService` 新增 `gameEngineCoreProvider: Provider<GameEngineCore>? = null`——GameEngineCore 构造链持有本服务，Provider 为惰性边（与 `DiplomacyService.gameEngineCoreProvider` 同构）；null（既有测试直构）⇒ 恒走回退臂，**零破坏既有用例**（`JadeSymbolServiceTest` 29/29 原样绿）。

**途中修正（事务语义取证）**：1769 首版以"发放后 total"为参 ⇒ C++ 再加一次 amount 的双加缺陷被 GTest `GrantAdWritesAbsoluteTotalNotToday` 首跑抓出（16 = 13+3）⇒ 改为 `totalBefore`（发放前运行时绝对值），C++ 承做加法、回执权威、运行时跟随重锚——与"校验+扣减+抽取原子在 C++"口径一致。

**玉符消耗唯一入口（不动项登记）**：`JadeSymbolService.deduct`（事务内 jadeSymbols 写）保留——洗炼/灵根改/突破加成等消耗面的操作事务属 W4-A 域；待其随宿主操作下沉后 `deduct` 降级为回退臂-only，四字段届时方可关闭（`W4BChannelClosures.kt` 注释已更新为该判定）。`JadeSymbolConsumptionGuardTest` 3/3 绿（扫描面零新增写者）。

**验证**: 桌面 C++ 全量 **1344/1344**（基线 1326 + 本批 18，含单进程直跑复核）；`:core:engine` Jade 族 64 用例全绿（含新 GateTest 4/4 + 既有 `JadeSymbolServiceTest` 29/29 证明回退臂零漂移）；`:core:engine:detekt` 绿；生成器 `173 actions (maxId=1769)` + 生成物同组提交零漂移。


### 2.63.B3 w3-05 行商刷新族下沉 + 邮件附件登记不下沉（2026-09-15，ActionId **1770–1773**）

**批次**: W4-B/B3（w3-05） | **产物**: 新 `merchant_tx.h`（四事务）、填 `dispatch_w4b.cpp` 行商分派区、改 `MerchantAndRecruitService.kt`（native 臂 + `Provider<GameEngineCore>` 惰性边 + **通道预检**）、新 `merchant_tx_test.cpp`（**13 用例**）、新 `MerchantNativeTxGateTest.kt`（**5 用例**）、`w4b.mjs` + 两生成物（**173 → 177 动作，maxId=1773**）、`W4BChannelClosures.kt`（INVENTORY 四字段转入关闭）

**事务面**：

| ActionId | 事务 | 承接的 Kotlin 写者 |
|---|---|---|
| 1770 `MERCHANT_CHANCE_GRANT_TX` | 年度凭据发放（达上限/未到 30 年间隔零写入） | `giveMerchantRefreshChanceIfDue`（:344 写段） |
| 1771 `MERCHANT_ACQUISITION_REFRESH_TX` | 收购池整表覆写 + 年份 | `refreshMerchantAcquisition`（:377 写段；该写面为**在册已关闭字段**——本事务消除 AUTHORITATIVE 下关闭域 Kotlin 写者残留） |
| 1772 `MERCHANT_TRAVELING_REFRESH_TX` | 旅行商人池 + 年份 + 刷新计数（保底相位 Kotlin 以镜像 count 预计算） | `refreshTravelingMerchant`（:95 写段） |
| 1773 `MERCHANT_MANUAL_REFRESH_TX` | chances 校验先行 + 扣凭据 + 池覆写**单事务原子** | `refreshTravelingMerchantManual`（:319 写段；Kotlin 两段式 → C++ 单事务，可观测结局一致） |

**池生成留 Kotlin（落账/生成切分口径）**：buildMerchantItemPools 40 件池选取/保底/价格波动（SYSTEM 分区）留 Kotlin——C++ data 层无物品生成器（§2.50 同证）；整表经 MerchantItem json_codec 键（id/name/type/itemId/rarity/price/quantity/description/obtainedYear/obtainedMonth/grade）序列化传入。四事务零 RNG（签名级不收 RngManager）。

**🔴 通道预检（途中修正，RNG 红线纵深）**：native 臂首版"先生成池再调 native"，JVM 降级场景会先消耗一轮 SYSTEM 分区抽取再由回退臂重生成 ⇒ 抽取序相对 flag OFF 臂漂移（被 `MerchantNativeTxGateTest` 池规模断言首跑抓出：34≠33）⇒ 增加 `nativeMerchantAvailable()` 预检（flag/Provider/镜像/**GameCoreBridge.isLoaded** 四级）**先于池生成**——降级路径不消耗抽取序，两臂抽取序逐位一致。

**🔴 邮件附件领取登记不下沉（w3-05 余项，与 RedeemCodeService 同先例）**：`MailAttachmentDistributeOps` 的领取链 = 12 类附件分发表，其中 equipment/manual/pill/material/herb/seed 7 类含 MAIL 分区**随机生成**（itemId 未命中模板时 `generateRandom`——C++ data 层无生成器，抽取序不可逐位复刻 = RNG 红线）；且「发放体 + mailRecords 凭据」**同生共死**（任一 Partial/Failure 整体回滚）——拆"账本入 C++ + 发放留 Kotlin"即破坏凭据类原子性（失败零写入红线）。"预生成全部附件实体再入 C++"需在 Kotlin 复刻整张分发表为解析层（双维护面 ~150 行）。⇒ `mailRecords` 保持 in-flight，触发条件 = C++ 侧具备物品随机生成器（模板 codegen 下沉）后重议（与 §12 兑换码债项同轨）。

**关闭动作（w3 §2 第 5 步）**：`travelingMerchantItems` / `merchantLastRefreshYear` / `merchantRefreshCount` / `merchantRefreshChances` 四字段由 retained 转入 closedUnits（写者已归 C++，Kotlin 残余回退臂-only——与 merchantAcquisitionItems 既有先例同口径）；`ReverseChannelPolicyGuardTest` 6 用例绿。

**连带修正**：B1 提交的 PATROL 证据条目缺 `file:line` 形（`SpiritMineViewModel.kt` 后无 `:89`），`ReverseChannelPolicyGuardTest.verdict evidence matches status` 捕获 ⇒ 本批补齐（B1 时未跑 :core:domain 测试套的流程缺口，已在批内闭环）。

**验证**: 桌面 C++ 全量 **1357/1357**（基线 1344 + 本批 13，含单进程直跑复核）；`:core:engine` Merchant 族全绿（新 GateTest 5/5 + 既有 `MerchantAndRecruitServiceTest` 回归）；`:core:domain` **1758/1758**（守卫套件含）；`:core:engine:detekt` + `:core:domain:detekt` 绿；生成器 `177 actions (maxId=1773)` 零漂移。


### 2.63.B4 w3-12 外交/自愈/运行态——实裁 1843 + 洞府死链删除 + 六项逐点判定（2026-09-15，ActionId **1843**）

**批次**: W4-B/B4（w3-12） | **产物**: 新 `diplomacy_selfheal_tx.h`、填 `dispatch_w4b.cpp`（段内未实裁号不认领语义）、改 `GameEngineDiplomacyOps.kt`（native 臂）、新 `diplomacy_selfheal_tx_test.cpp`（**4 用例**）、新 `GameEngineDiplomacyNativeGateTest.kt`（1 用例）、`w4b.mjs` + 两生成物（**177 → 178 动作，maxId=1843**）、**洞府探索死链删除**（`CaveExplorationProcessor.kt` 570→118 行 + 删除 `CaveExplorationRewardOps.kt` 全文件 + `CultivationService` 死委托）

**实裁事务（1843 `DIPLOMACY_WARNING_STAGE_TX`）**：`markWarningStageShownTx`——shownWarningStageIds 参与存档 ⇒ 按 **① 保守处置**（批文档盲区 #2 判定项闭环）；List 追加**不去重**与回退臂逐位一致；空 key 校验失败零写入；零 RNG（签名级）。

**洞府死链删除（双证后删）**：入口 `CultivationService.processCaveLifecycle` 生产 0 调用 + 主源 `CaveExplorationTeam(` 构造 0 处 ⇒ 删除**独占调用面 17 成员**（processCaveLifecycle / executeCaveExploration / executeBattleForTeam / findNearbySects / resetCaveExplorationTeamMembersStatus / resetExpiredCaveTeams / processSingleTeamCompletion / handleExplorationErrors / assembleTeamMembers / handleEmptyTeam / processBattleCasualties / awardVictorySoulPower / buildAndStoreBattleLog / trackBattleAnalytics / cleanupAfterCaveExploration / CaveCompletionState / BATTLE_LOG_DISPLAY_LIMIT，可达性分析逐成员核实"仅死链可达"）+ 独占扩展文件 `CaveExplorationRewardOps.kt`（grantBattleRewards 唯一调用方在死链内）+ 构造面收敛（battleSystem/eventProcessor/analyticsTracker/deathHandler 仅被死链消费，随链移除，两处测试构造同步收窄）。**同族活路保留**（processAISectOperations / processSectDisciplesAging / processSectDisciplesYearlyRecruitment / currentAiThermalBatchSize——禁删清单逐条核对）。回归网 = 全量编译 + 引擎全量测试绿。

**登记不下沉（逐点判定成文，详见 `diplomacy_selfheal_tx.h` 头注）**：

| # | 写者 | 判定 | 归属 |
|---|---|---|---|
| 1 | VassalService 年贡/附属年贡（:99/:310） | 🔴 实测 C++ **逻辑已在位**（year_settlement.h processYearlyTribute/processYearlyVassalTribute，随 AUTHORITATIVE 年结管线执行）⇒ 再开 ActionId 臂即**双重扣贡**；调用点在冻结宿主（w3-11 面） | **W4-D/D2**（批文档 §8 技术债首行同结论） |
| 2 | VassalService 月度脱离（:324） | 脱离概率链（战力/好感/近 3 年战绩 + SYSTEM 抽取）跨臂抽取序对拍风险 | **W4-D/D2**（同上） |
| 3 | GameEngineServiceOps :77 内存裁剪 | ③类平台决策面；裁剪清单语义与 DiscipleSlotCleanup/死亡处理交叉 | **W4-D** |
| 4 | SaveFacadeImpl :56 存档前自愈 | WorldMapGenerator 世界生成面，与 W4-C WS-5b「生成即数据」同域 | **W4-D 评估** |
| 5 | GameEngineServiceOps :40 修炼检查点重锚 | 写 DiscipleTables 检查点列（弟子域核心表，语义归 W4-A w3-01）；C++ disciple 模型无检查点列 ⇒ 需 models.h 扩列（W4-C 租约） | **W4-D** |
| 6 | DiplomacyService 结盟/散盟（:145/:261） | **batch-09 native 臂已在位**（DIPLOMACY_TX=1500 + diplomacy_tx.h 双事务）⇒ 本批零改动（核实结论，非新增工作） | 已闭环 |

**验证**: 桌面 C++ 全量 **1361/1361**（基线 1326 + B2 18 + B3 13 + B4 4，含单进程直跑复核）；`:core:domain` **1758/1758**（守卫套件含）；三模块 detekt 绿；生成器 `178 actions (maxId=1843)` 零漂移；引擎全量 **3295/3295**（基线 3281 + 本波 W4-B 新增 14；含 47 个 Diff* 对拍类，`-Dgamecore.jni.path` 指向本工作树 desktop-jni，`--rerun-tasks` 防假绿）。



## 2.64 W4-C（2026-09-15，进行中→主体落地）：战斗与世界协议轴——C-③ 随机源收敛 + WS-5b 地图冻结全链落地

批次: W4-C（worktree `C:\Mnzm\XianxiaSectNative-w4c`，分支 `w4/c-battle-world`，基线 `w4-base`）| ActionId: **1780–1809 本批未启用 / 1855–1859 维持空置**（C-③ 战斗侧治理走确定性化路线，无 C++ 事务需求，退段登记见下）| 提交: `w4c/01`（C-③ 随机源收敛，bb5fb2549）→ `w4c/02`（WS-5b 地图冻结，fadb66f32）→ `w4c/03`（遮蔽根治，ed63b4203）→ `w4c/04`（detekt 清零，f7db90256）；每子批 `git bundle` 落盘 `C:\Mnzm\backups\w4c-0{1,2,3,4}.bundle`

### 2.64.1 ✅ C-③（C7+C8）随机源收敛（w4c/01）

- **C8 · 三处顶层可变 `xxxRngManager` 形参必传**（与已修复 MissionSystem 同形态同修法）：摘除 `EnemyGenerator.enemyGenRngManager:22` / `AISectAttackManager.aisRngManager:40(+aisRng:41)` / `AISectTeamComposer.teamComposerRngManager:9(+teamComposerRng:10)`；BATTLE/ENEMY_GEN 分区经 `GameRngManager` 形参显式传入。生产链透传：`GameEngineBattleOps:78/:238`（战利品与 `sectBattleRewardCount` 同一 BATTLE 分区实例，抽取序逐位不变）、`MissionSystem.processMissionCompletion` 链（ENEMY_GEN 透传至 `generateHumanEnemies`）。`GameEngine.kt:170-172` 三处赋值点随之移除——**租约已按 protocol-lease.md 登记取得**（W4-A 未开工顺延；改动仅 init 块 3 行，与 W4-A 的 `:276-306` hunks 零重叠）。
- **C7 · `BattleDescriptionGenerator` 14 处 `.random()` 归零（口径修正登记）**：批次方案预留两路线均不可行——① 插 BATTLE 分区会平移后续战斗结果抽取序（跨语言对拍逐位锁定）；② 新增分区需扩 `RngSourceGuardTest.registeredPartitionIds`（0..9），该文件 W4-A 独占。故对齐 `applyDeterministicWinAttr` 既有口径（ID 散列代替随机）：以（攻击者 id × 目标 id × 回合号）散列确定性选词——零抽取、零分区影响、存档重放可复现；回合号经 `TurnContext.turn` 线程化。`battleLogs` 为 Kotlin 显示域（batch-20b 登记），措辞文案不在对拍面内。**1855–1859 条件段退段：维持空置，无 ActionId/事务需求。**
- **守卫面**：`RngSourceGuardTest`/`RngEngineIsolationGuardTest` 零改动全绿（白名单残留 3 条无害——白名单=跳过集；收缩 + 计数断言统一 W4-D/D5，与 README §5.1 口径一致）。**实证**：`DiffSectBattleTest`/`DiffSectAttackDecisionTest` 实跑桌面 JNI 全绿 ⇒ BATTLE 分区抽取序跨语言逐位一致，参数化零行为漂移。

### 2.64.2 ✅ C-② WS-5b 地图冻结（w4c/02，本批最大工作块全链落地）

- **口径**：生成即数据、**存的地形恒优先**——`terrainTiles` 非空即采用（跨版本冻结不重算）；仅无段按 `mapSeed` + `MAP_GEN_VERSION=1` 生成回填，新档与老档**同一条路径**。生成器演进 ⇒ 递增版本戳，老档老地图永久冻结，无需发版。
- **C++ 协议面（租约第一顺位，protocol-lease.md 登记）**：`models.h` GameData 增 `mapGenVersion`+`terrainTiles`（flat 单一表示，§2.19 红线不破）；`json_codec` 双向编解码（"非空/非零才导出键"先例）；`game_core.{h,cpp}` `ensureTerrainGenerated` 落 `importStateInternal` **归一化族**（先于 `resetBaseline` ⇒ 生成段计入导入基线，前向/反向镜像零载荷，稳态每旬零增量）；生成参数由 Kotlin `GameConfig.SectMap` 经 `nativeInit` 传值（单一数据源不落 C++）；未配置地形（桌面测试面）/`mapSeed==0` 防御跳过。
- **Kotlin 侧**：`GameData` 增 `mapGenVersion(@ProtoNumber 1000)`+`terrainTiles(@ProtoNumber 1001)`；Room `DATABASE_VERSION 50→51` + `MIGRATION_50_51`（ADD COLUMN ×2 带 DEFAULT）+ `51.json`；`SectMap` 提 `MAP_GEN_VERSION`/`DECORATION_DENSITY` 常量。
- **实施定界修正（3 处，与批次方案的差异已登记）**：① R5"改 `SectTerrainBridge` 读权威态"→ 桥保持纯生成通道（native 优先 + Kotlin 位级降级臂不破），读权威态落在调用面（BootSequence 权威段优先/无段回填、SectMapController 主宗图读权威段、`GameEngineSaveOps.ensureSectTerrainBackfilled` 幂等回填）；② R7"登记 CLOSED"→ **登记在册保留（照常传输）**：boot 回填是合法一次性 Kotlin 写者，CLOSED 会触发 `detectClosedFieldWrites` 误报（gate#7 红）；代价 = 回填一次 ≈64KB 一次性信封，C++ 同源生成幂等覆盖，不承载地形存续（符合 R7 实质）；③ 域归属 SAVE_LOAD 族（mapSeed 同族），域级证据留 W4-B 文件（聚合 `toMap` 后写会静默覆盖 W4-B 结论）。
- **RLE 顺延（债务登记）**：Room `List<Int>` 转换器注册位已被 `intList` 占用，独立转换器需值类/`IntArray` 类型（data-class equals 与 proto codegen 破坏性风险）。现存储 = base64 proto（≈34KB TEXT 一次性读）+ 云路径 LZ4/ZSTD 已压缩。触发条件 = 存档体积实测超预算。
- **测试**：新增 `terrain_freeze_test.cpp` 6 用例（生成即数据+确定性+零 RNG 差分/存的地形恒优先/回填幂等/段存在性协议/无种子跳过/ensure 与 generateTileData 位级一致）；`RoomMigrationV50To51Test` 2 用例（真实 schema 校验 + 旧行默认值与数据零丢失）；`SaveDataTerrainFreezeTest` 3 用例（云档 proto 往返不丢地形，含 128² 规模逐位）——R9 已同步扩充 `rules/database-migration.md`。

### 2.64.3 ✅ C3（部分）遮蔽根治（w4c/03）+ w4c/04 detekt 清零

- `InventorySystem.materializeDiscipleBagAndMarkDead` 成员/扩展同签名遮蔽：删除 `InventorySystem校验Ops3.kt:183` 死扩展（成员恒胜出 ⇒ 扩展自始死代码），收敛为成员单一实现，零行为变更。
- detekt 六模块 baseline 全 0 保持：MissionSystem rng 双形参合并为单一 `rngManager`（内部 `rngOf` 自取，抽取序不变）；`executeCombatantTurn` 合并豁免注解；4 处死 import 清理。

### 2.64.4 ✅ C-① 战斗族残差下沉（C1+C2+C3，w4c/07——本批代码面收尾）

**交付面**：新 `battle_residual_tx.h`（①伤亡残差 ②关卡胜利 ③战前结算）+ 新
`secret_realm_residual_tx.h`（①出发换岗 ②到期兜底）五事务 + `dispatch_w4c.cpp`
实裁 + `w4c.mjs` 五条目（1780/1781/1782/1800/1801）+ Kotlin 五处 native 臂接线
+ 双 GTest（13 用例）+ `BattleResidualNativeTxGateTest`（findings 13 回归网 +
三臂降级等价）+ `W4CChannelClosures` 证据改写。**1790–1799 段零占用**（w3-07
三处登记不下沉，`w4c.mjs` 段注 + closures 证据双登记）。

**实施口径差异（与批次方案 §2.2 的差异，逐条核实战真值）**：

- ① 伤亡残差（1780）：死亡计数按 Kotlin `InventorySystem.materialize
  DiscipleBagAndMarkDead` 的 **wasAlive 守卫**计数——`sr_session` 包装为无条件
  计数（洞府预标记语义），1570→1780 重入路径会双计；故事务内 wasAlive=true 走
  包装函数（袋物化+列写+计数一次）、false 走三列幂等重写。宗门外死亡**不回收
  穿戴**（Kotlin isOutsideSect 分支同口径）；DeathEvent 广播/丧亲日志/
  Room 生产槽清槽为 Kotlin 平台残差。
- ② 胜利事务（1781）：r = |(id×527+31)%17| 因 527≡0 (mod 17) **恒收敛分支 14**
  （basePhysicalDefenses+1）——与 Kotlin 同式同常量，GTest 以实证常量锁定；
  TOCTOU 重查在 C++（defeated → 成功零写入 applied=false）；🔴 **C++ 不写
  defeated**（batch-13 口径）——defeated + battleLogs 残差经
  `applyWorldLevelVictoryTransaction(skipNativeDomainWrites = true)` 留 Kotlin 臂；
  偷盗钩子**随域下沉**（原表"随域判定"→判为下沉，复用
  `month_settlement::judgeSingleTheftCandidate`，SYSTEM 分区同区同序）。
- ③ 战前结算（1782）：复用 `phase_settlement::performBreakthrough` 已对拍锁定
  管线（自动嗑丹/引导计数/检查点/完成预估/精准写回），**候选 = 传入队伍 id 集**
  （月结为全量排除秘境成员）——非队伍满修为弟子**零抽取**（抽取集不变红线，
  GTest rngStates 快照差分锁定）；行序 == `_ids` 追加序；w3-07 `:66` 与 w3-06
  `:134` 同一事务界面（单一 ActionId）。
- 秘境 1801：native 权威态判未到期（快照竞态）→ 放行（以权威态为准）；
  backpack 解析失败按空背包兜底（`continueSecretRealmNative` EXPIRED 同构）。
- lifeEvents 契约：C++ 无该列（`disciple_lifecycle_tx.h` 口径）⇒ 丧亲/突破日志
  以信封 `lifeEventDrafts` 回写 Kotlin 瞬态列；战斗丧亲日志**每新入悲痛者至多
  一条**（Kotlin `firstOrNull` 语义、死者按行序取首）——与年结"逐死者逐亲属出
  草稿"口径不同。

**验证数值（w4c/07 实跑，2026-09-15）**：桌面 C++ **1345/1345**（基线 1332 +
新 13；`dispatch_guard` 对 5 个新动作号的可达校验含于全量）；`:core:engine`
全量 **3282/0**（`--rerun-tasks` + 本工作树 desktop-jni 对拍路径）；六模块
detekt 全绿 baseline 全 0（Complexity 19/15、Loop 跳转、MaxLine 三处实修零
豁免）；`:core:data` / `:feature:game` / `:app` 回归绿；
`:app:externalNativeBuildRelease` + `:app:lintRelease` 绿；生成器重跑 **174
动作 / maxId=1801** 与提交清单逐文件一致；冻结纪律机器判据通过（`git diff
w4-base..` 不含 execute_dispatch.cpp / test:CMakeLists / ReverseChannelPolicy /
GameViewModel / GameEngineCore / 宿主族）。

**CombatService 注入形态**：构造新增 `Provider<GameEngineCore>?`（W4-B
`MerchantAndRecruitService` 同款——Dagger 破环 + 测试直构默认 null 走回退臂）。

**本批遗留（非代码面，归收口人）**：干净检出复验（README §4.3 第 5 条）、
A→B→C→D 合并序执行、`ui-read-surface.md` §4.4 残余清单判定（门禁第 9 条，
随 PR 描述附）、共享文档（CHANGELOG 双文件 / handover §3 验证表 / §4.1 / §5 /
§6）收口人独占更新。


## 2.66 仓库对象库整理批（2026-09-15）：3 个死 tag 清除 + 半打包损坏态根治

批次: 仓库基建批（非代码批；**零源码改动**） | 触发: §2.61 执行"备份纪律"时 `git bundle create --all` 报 `fatal: bad object`，顺藤查出对象库处于**半打包损坏态** | 产物: 文档（本小节 + `docs/parallel-batches-w4/README.md` + `CHANGELOG.md`）

**症状（整理前实测）**: `git fsck` 报 **6 条 error + 2 条 warning**——3 个悬空 tag（`archive/batch-05-dirty-ledger` / `batch-06-sink-building` / `batch-09-sink-diplomacy`，object 已丢失）、`HEAD` 的 2 条垃圾 reflog 条目（指向不存在的 `c882c3ab…`）、1 个过期 commit-graph 引用丢失提交；另有 2 条 warning：**孤儿 pack 索引**（`pack-ca6fbef3….idx` 无对应 `.pack`）与**截断的临时 pack**（`tmp_pack_1XjjdM`，10MB，`git index-pack` 报 `fatal: early EOF`）。`git count-objects -vH` 显示 **4590 个松散对象 / 377MB，`in-pack: 0`、`packs: 0`** ——**全部历史只有松散对象、一个 pack 都没有**，外加 10.07MB 垃圾。⇒ 一次被中断的 `git repack` 残留 + 两次对象库破坏（§2.40）的叠加态。

**这 3 个死 tag 是什么（根因追到底）**: 来自 w2 协作协议（`parallel-batches-w2/README.md:232`、`findings.md:428`）——"收口后立即清理分支；**非祖先提交（内容已并入但提交链不在主支）必须先打 `archive/*` tag 再删**"，本次即 `batch/05·06·09`（batch-05 = §2.34 dirty 记账摘除；batch-06 = §2.35 建筑事务下沉；batch-09 = §2.38 外交族下沉）。**tag 是那三条提交链在全宇宙唯一的落脚点**，对象在 §2.40 的破坏中丢失后，tag 就成了悬空引用。

**🔴 可恢复性结论 = 不可恢复（逐条排查，非推测）**: ① 不在松散对象里、不在任何 pack 里（`cat-file` + `verify-pack` 双证）；② 从截断临时 pack 中用 `git unpack-objects` 解出 892 个对象，**不含**这 3 个；③ 同级目录其它 clone/备份（`XianxiaSectNative-b11`、`-w2-14`、`git-repo-backup/…20260905`）都不是可用对象库；④ 远端 `origin`（github.com/hsmy7/mnzm）可连通但**零 tag**，`git fetch <sha>` 取不回。**丢失的只是那三条分支的逐提交历史（commit 对象 / message / 逐笔 diff 粒度）——代码内容全部在 `main` 里（已独立核实：batch-06 `building_tx.h` + `building_tx_test.cpp` + ActionId 1450–1454 + `BuildingNativeTx.kt` 在位；batch-09 `diplomacy_tx.h` + `diplomacy_tx_test.cpp` + ActionId 1500–1502 在位；batch-05 的 `markDirty/markAllDirty/clearDirty` 确已摘除）。**

**执行（七步，写操作前先证明可恢复）**:
1. **可恢复性实证**（先于任何写操作）：`git bundle create` 出新还原点 → **`git clone -b main <bundle>` 到临时目录** → 校验 `HEAD` 相同（`6fed0e8`）、`HEAD tree` 相同（`3b8207c8`）、追踪文件数相同（3484 = 3484）、**克隆内 `fsck` 零错误**。
2. 备份待清理项到 `C:\Mnzm\backups\git-junk-<时间戳>\`（`logs-HEAD.bak` + `orphan.idx` + `truncated-tmp.pack` + `commit-graph.bak`）。
3. `git tag -d` 删除 3 个死 tag（确认其 object 不可恢复后）。
4. **外科式**清 reflog：只删 `.git/logs/HEAD` 中 old/new 不可解析的 2 行（30 → 28 行；删后首行的 `old` 恰为全 0，reflog 语义合法）——**不用 `reflog expire --all`**，避免连带丢弃有效历史。
5. 删除孤儿 `.idx`、截断 `tmp_pack`、过期 `commit-graph`（均为派生缓存/垃圾，可重建）。
6. `git repack -a -d`（**刻意不用 `-A`、不跑 `gc`**）：把所有**可达**对象收进单一 pack，同时**不卷入也不删除 6 个悬空对象**——`-A`/`git prune` 会永久删掉它们，而本仓已被毁两次，宁可多留。
7. 逐项复验（见下）。

**整理后实测**: `git fsck` **0 error / 0 warning**（原 6 + 2）；`count-objects -vH`：**松散 4590 → 70**（336KB，即保留的悬空对象）、**in-pack 4520**、**packs 1**、`size-pack 346.55 MiB`、**`garbage: 0`**（原 10.07MB）；对象总数 **4520 + 70 = 4590 不变**（无丢失）；`HEAD` / `HEAD tree` / 提交数 25 / 追踪文件数 3484 **逐项与整理前一致**；工作区 `git status` 干净；**`git bundle create --all` 从 `fatal: bad object` 变为可用（exit 0）**——原始动机已修复。

**红线**: **写操作前必须证明还原点可恢复**（第 1 步不是形式主义——本仓对象库已被毁两次）；**不 prune 悬空对象**；**不用 `reflog expire --all`**（会连带丢弃有效 reflog）；rm 类操作一律显式路径、先备份；本批**零源码改动**，故未重跑全量门禁，改以"`HEAD tree` 位级不变 + 生成物零漂移 + `DispatchGuard*` 4/4 绿"作等价证据。

**登记**: ① 保留的 6 个悬空对象可追溯：`dangling commit 2f0a1e0b / 4414c471 / 27d9f184`（2f0a1e0 曾作 `lastTickMs` 相关调试提交）+ 3 个 dangling tree——**均不 prune**，留待将来需要时用 `git show` 翻查；② **远端 `origin` 长期未同步**（远端 `main` = `ad6ff6c9`，与本地 `6fed0e8` 属不同血缘；远端另有 `master` = `ddb9395f`，零 tag）——本仓与远端的关系需用户确认识别（是否仍以该仓为发布源）；③ 建议把"`git bundle` 只传可解析 ref"的写法固化进 `scripts/w4/`（本批已实测 `--all` 现在可用，但脚本保留兼容写法更稳）。

## 2.67 远端仓库关系核查 + 线上隐私政策缺口（2026-09-15，核查批，零代码改动）

批次: 文档/核查批 | 产物: 新 `scripts/publish-privacy-policy.ps1`（隐私政策发布器）+ 本小节 + 本文件 §4.1 登记

**背景**: §2.61 W4-00 收尾时发现 `origin`（github.com/hsmy7/mnzm）的 `main` 与本地 `main` **属不同血缘**，用户要求查清关系。

**核查结论一：`hsmy7/mnzm` 是本项目自己的远端仓库，且确实推送过。**
- **不是网页仓**：网页仓是另一个 `hsmy7/index.html`（本地 `C:\Mnzm\index.html-repo` 即其克隆），其末次提交（2026-04-17）写着"**将隐私政策重定向到新地址 mnzm 仓库**"——所以政策页搬进了 `mnzm`。
- 远端元数据：`language=Kotlin`、`size=901MB`、`has_pages=true`、`default_branch=master`。
- **两条互不相关的分支**：① `master`（默认 + Pages 源）= 旧 **1.4.x 线**，作者 `Backup <backup@xianxia.com>` / `hsmy7`，内容停 **2026-06-28**（末次提交是"恢复被误删的隐私政策页面 docs/index.html"）；② `main` = **本项目线**，tip `ad6ff6c9`（2026-09-04 18:45Z「feat(render): 程序化天空渐变背景系统」，提交信息为本项目批级中文风格、含"compileReleaseKotlin + lintRelease 通过"），`version.properties` = **4.01.12**，`android/` 结构（`app`/`core`/`feature`/`build-logic`/`detekt-rules`/`detekt-baseline-count.guard`/`stability_config.conf`）与本地**同构**。
- **推送证据**：远端 `pushed_at = 2026-09-04T18:49:45Z`，比该 tip 提交的 author 时间（18:45:17Z）晚 4 分钟 ⇒ 就是这次 push。

**核查结论二：内容连续，但提交血缘断裂——且必然如此。**
- **内容连续（内容级对撞，非时间戳推断）**：远端 `main` 的 `docs/index.html` blob = `566e84fa…`，与本地 `HEAD:docs/index.html` **完全相同**；远端 `main` tip 那批"程序化天空"的 7 个文件（`SkyBackground.cpp/.h`、`sky.vert/frag(+.spv)`、`SkyBackgroundConfig.kt`）本地**全部在位**；抽检 10 个文件做 blob 对撞（git blob 是内容寻址）——**5 个完全一致**（`API_DOCUMENTATION.md` / `clean_release.bat` / `gradlew.bat` / `keystore.properties.example` / `stability_config.conf`），另 5 个为本地后续演进过的（`build.gradle` / `gradle.properties` / `settings.gradle` / `detekt-baseline-count.guard` / `api.properties.example`）。
- **血缘断裂**：本地 26 个提交**全部**为 2026-09-12～09-14、作者统一 `mnzm-dev <dev@local.mnzm>`（最早 `c13d651` "第二轮集成收口"）；远端 main/master 的提交 sha 在本地对象库中**一个都不存在**；本地 `.git` **无 `refs/remotes`、无 `logs/refs/remotes`**，`FETCH_HEAD` 为 **0 字节**（曾于 09-07 创建过、09-12 被截断）。
- **`origin` 不是从当前这份副本推的**：本地提交身份（`mnzm-dev`）与远端提交身份（`hsmy7` / `Backup`）不同 ⇒ **09-04 那次 push 来自另一份（已被销毁的）工作副本或另一台机器**。与 §2.40 记录的".git 两次被毁 → 以工作区文件为唯一事实源重建单一可编译树"完全吻合。
- **⇒ 两侧无共同祖先**，`merge` / `fast-forward` 都不可能；**2026-09-04 之后（M0–M3 + W4-00 共 26 个提交）的内容只存在于本地，远端一个都没有**。

**核查结论三（🟡 隐私合规缺口，本批的主要发现）：线上隐私政策落后于实际集成。**

| 面 | 版本 | 声明的广告/统计 SDK |
|---|---|---|
| **线上** https://hsmy7.github.io/mnzm/ （Pages ← `master`） | **2026-06-04** | 仅 TapTap + MMKV + Dirichlet Ad SDK |
| 仓库 `main` 与本地 `docs/index.html` | **2026-08-13** | TapTap（含 **tap-db / TapDB 数据分析**）+ MMKV + **TapADN 聚合广告 SDK**（Dirichlet 自有 + **穿山甲 / 优量汇 / 爱奇艺 / 百青藤**）+ **GAID** + **个性化广告开关** |
| 应用内 `PrivacyConsentScreen.kt` | **2026-08-13** | 同上（与仓库网页版一致，已核实 `:808` 日期行与 SDK 链接常量） |

- **这些 SDK 确实已集成**（`git grep` 实证）：`android/app/build.gradle` 命中 `pangle` / `Pangle` / `穿山甲` / `GDT` / `优量汇` / `iQiyi` / `爱奇艺` / `baidu` / `百青藤` / `TapDB`；`proguard-rules.pro` 同步；libs 下存在 `DirichletAD_GDT_Adapter_5.1.2.3.aar`。
- ⇒ **线上页面未声明实际在用的 SDK**，违反 CLAUDE.md 设计方案规则第 5 条"隐私政策必须双入口同步更新"。

**处置（✅ 已于 2026-09-15 执行完毕）**:
- **① 两步走：先把两条历史线统一，再谈发布。** 远端 `main` 的 **1834 个提交（2026-04-03 ～ 09-05，作者 hsmy7）才是项目真实历史**；本地 26 个提交是重建线。执行（**全程未使用 force-push**）：
  1. `git fetch origin main` —— 把远端真实历史拉到本地（此前本地零命中，等于给不可再生的历史做了备份）；
  2. `git commit-tree <本地当前树> -p origin/main -p main` —— 构造**双亲合并提交 `ba69918`**：第一父 = 远端真实历史，第二父 = 本地重建线，**树取本地当前树（逐字节相同）**；
  3. 校验：合并树 ≡ 本地树；`git log -1 --format=%P` 双亲正确；文档中引用过的本地 sha（`24c429d` / `d4cad20` / `6fed0e8` / `87ea746` / `8052418`）**全部仍是合并提交的祖先**（可解析）；可达提交数 **1862 = 1834 + 27 + 1**；
  4. `git push origin ba69918:refs/heads/main` —— **fast-forward**（第一父即原远端 main），只上传 929 个对象；远端**不丢任何对象**；
  5. 本地 `git reset --hard ba69918`（树相同 ⇒ 工作区零变化）+ 设置 upstream，本地与远端对齐。
  - **为什么不做 rebase**：两条线**无共同祖先**，把 26 个"全树快照"式提交 rebase 到 1834 提交之上会产生数千处无意义冲突且不增加信息量；双亲合并是唯一「零损失 + 零强推」的统一方式。
- **② 隐私政策（根因修复）**：`PUT /repos/hsmy7/mnzm/pages` 把 Pages 发布源由 `master/docs` **切到 `main/docs`** —— `main` 上该文件与本地逐字节相同（blob `566e84fa…`）⇒ **零内容变更即生效**，且**从根上消除"政策更新写在 main、发布源在 master"的脱节**（`CLAUDE.md` 设计方案规则第 5 条要求政策双入口同步，源指向 main 后即自动同步）。**线上实测**：https://hsmy7.github.io/mnzm/ 现为「更新日期：2026年8月13日」、26256 字节，穿山甲/优量汇/爱奇艺/百青藤/TapADN/TapDB **均已声明** ✓
- **③ 仓库门面与设置**：补 `README.md`（项目介绍 / 技术栈 / 架构要点 / 目录结构 / 构建测试命令 / 文档索引 / 分支说明）；`PATCH /repos/hsmy7/mnzm` 设 `default_branch = main`（原为 `master`）、补 `description` 与 `homepage`；`PUT /topics` 设 12 个主题标签。
- **④ 工具**：`scripts/publish-privacy-policy.ps1` 保留（三项前置校验 + `-DryRun` 默认开 + 两种模式 + 手工兜底），作为**将来政策再更新时的发布器**；由于 Pages 源已切到 `main`，日常只需把政策改动推到 `main` 即自动发布，`-Mode SourceBranch` 仅在需要回写历史分支时使用。

**途中发现（小项）**: `C:\Mnzm\XianxiaSectNative-b11` 与 `-w2-14` 目录下的 `.git` **是文件**（worktree 指针），指向 `C:/Mnzm/XianxiaSectNative/.git/worktrees/<name>`，但 `.git/worktrees` 目录**已不存在**（§2.40 记录的"worktrees 被删除"残留）⇒ 这两个目录的 `.git` 是**失效残留**，`git worktree list` 也不列它们。清理它们用 `cmd /c rmdir`（若含 node_modules junction）或直接删除该 `.git` 文件；**本次未动**（非本次任务范围，登记备查）。

**顺手更正（文档漂移）**: `CLAUDE.md`「知识库」章原写"**4 分区 PRNG**（BATTLE/BREAKTHROUGH/EXPLORATION/SYSTEM）"，实测 `RngPartition.kt` 已有 **10 个取值**（上述 4 个 + `ENEMY_GEN(4)` / `MAIL(5)` / `AI_SECT(6)` / `SECRET_REALM(7)` / `MISSION(8)` 入快照 + `AI_SECT_MIRROR(9, inSnapshot=false)` 通道型镜像键不入快照）⇒ 已就地更正为 10 分区并写明快照口径。

## 2.68 W4 三批次集成验收（2026-09-15，收口批）：独立复验抓出 3 处真实缺陷并根因修复

批次: W4 集成收口（A/B/C 三批汇流） | 产物: 合并提交 `7c89deb19`（+ 本批 3 处根因修复提交）、新 `MigrationChainGuardTest.kt`（4 用例）、`GameDatabase.kt` 新增 `ALL_MIGRATIONS` 单点登记表、本文档 §2.68/§3/§4.1 更新、`ui-read-surface.md` §4.4 滚动更新、双更新日志

**合入事实（`--no-ff`，逐批独立复验后合入）**:

| 批 | 分支 / tag | 提交 | 文件 | 合并冲突 |
|---|---|---|---|---|
| W4-A | `w4/a-disciple-building`（`w4a/01`–`w4a/05`） | 5 | 39 | 无 |
| W4-B | `w4/b-court-economy`（`w4b/01`–`w4b/05`） | 5 | 30 | 仅两份**生成物**（按方案 §4.3 重生成解决，未手工解冲突） |
| W4-C | `w4/c-battle-world`（`w4c/01`–`w4c/08`） | 8 | 64 | 生成物 + `handover`（§2.62/63/64 各自小节，顺序无损）+ `protocol-lease.md`（终态行）；`GameEngine.kt` 自动合并 |

- **三批同源于当前 `main`**（`git merge-base --is-ancestor` 逐批核验）；合入前逐批 `git merge-tree --write-tree main <branch>` 均为 exit 0（相对 main 无冲突）；生成物在合并后的树上重生成 ⇒ **195 动作 / maxId=1843**（批内增量：A 12 / B 9 / C 5），生成器幂等、`git diff --exit-code` 空。
- **冻结纪律机器判据零违规**：硬冻结 6 项（`execute_dispatch.cpp` / `gamecore/CMakeLists.txt` / `test/CMakeLists.txt` / `gen-action-ids.mjs` / `action-catalog/core.mjs` / `ReverseChannelPolicy.kt`）+ 跨批共引宿主 6 项（§2.3 冻结给 W4-D 的文件族）**三批 diff 中一处未现**。

**🔴 独立复验的三处发现（"批次自报完成"两次为假——正是方案 §7.1 要求独立复跑的理由）**:

1. **W4-A：测试源编译不过（自报"门禁④ 主源+测试源编译绿"为假）**。
   - **症状**: `:feature:game:compileReleaseUnitTestKotlin` 失败 10 条错误。
   - **根因链**: A5（tag `w4a/02`，RNG 阶段 3·弟子侧）把 UI 层 `DiscipleChatDialog.randomizeEffect` 由「非 suspend、单参、测试可见」改为「`private suspend`、双参（增 `engine`）」，**但从未更新同包测试** `DiscipleChatDialogTest.kt`（该文件 blob 在 `main` / 批 A / 集成树三处**完全相同**，即批 A 零触碰）⇒ 5 个调用点全部失效（suspend 上下文 + 文件私有各一条）。
   - **根因修复（非打补丁）**: 把随机化的**算术骨架**抽为纯函数 `randomizeEffectWith(drawInt, drawDouble, effect)`（抽取源形参化，`internal`），生产侧保留一个 `private suspend` 的引擎绑定包装（`chatDraw`/`chatDrawDouble` → CHAT 分区）。测试改为**确定性桩驱动骨架**：新增「抽取区间契约」用例（断言整型恒 `[1,6)`、浮点恒 `[0.01,0.06)`）+ 上下界取负/零进零出逐字断言——**比原"50 次随机 + 区间断言"更强且不再依赖引擎实例**。5 用例 → 6 用例。
   - **为什么不是"改回 public/internal 让测试能调"**: 那会把可见性放宽以迁就测试；骨架已可用纯函数覆盖，生产面保持最小（A5 的引擎侧签发设计不变）。
2. **W4-C：`:core:data` 回归 12 处失败（自报未跑该模块回归）**。
   - **症状**: `:core:data:testReleaseUnitTest` **712 用例 / 12 失败**（基线 707/0/15 跳过）。
   - **根因链**: C-②（WS-5b 地图冻结）把 `@Database(version)` 50→51 并新增 `MIGRATION_50_51`，**只**登记进 `GameDatabase.build()`；5 个既有迁移测试文件各自维护**链尾**（`RoomMigrationTest` 6 处 + `RoomMigrationV43To46Test` 3 处 + `V46To47`/`V47To48`/`V48To49` 各 1 处 = **12 处**，全部止于 `M49_50`）⇒ Room 报 `A migration from 38 to 51 was required but not found`（失败数与站点数**一一对应**）。
   - **根因修复（收敛单点，而非逐处补）**: 新增 `ALL_MIGRATIONS` 单点登记表（`GameDatabase.kt`，`build()` 与全部迁移测试共用），12 处测试链改为 `.addMigrations(*ALL_MIGRATIONS)` 并删除因此失效的 18 个私有别名 ⇒ **"递增版本 = 只改一处"**。同时按 CLAUDE.md 9.5 补**守卫测试** `MigrationChainGuardTest`（4 用例：链自 v3 连续覆盖到 `DATABASE_VERSION` / 每条恰好前进一版 / 链尾 == `DATABASE_VERSION` / 严格升序）——覆盖"升版本漏登记"这一缺陷类。detekt `SpreadOperator` 按本仓既有 4 处同款先例（vararg API）以带理由的定向 `@Suppress` 处置。
   - **验证**: `:core:data` **716 用例 / 0 失败 / 0 错误 / 15 跳过**（= 基线 707 + C 新增 5 + 守卫 4）；12 处原失败用例全绿。
3. **W4-B：`dispatch_w4b.cpp` 用裸 ActionId 数字而非生成常量（违反 CLAUDE.md 0.4，且与 A/C 两批风格背离）**。
   - **根因**: 该文件**未 include** `gamecore/action_ids.h`（A/C 两批的 `dispatch_w4X.cpp` 都 include 了）⇒ 拿不到 `action::` 常量，只能写死 8 个 `case 1766:` 式标签 + 1 处 `!= 1843` 比较。
   - **处置**: 机械**可证等价**归一（值必须与生成常量逐条相等，否则脚本抛错）——补 include + 8 个 case 标签与 1 处比较改为 `action::*`；段范围守卫（`>= 1766 && <= 1769` 等）按 A/C 同款保留字面量（表示"预分配段"而非动作）。差异仅 11 行增 / 9 行删，`dispatch_guard_test` 复跑通过。

**门禁实跑（集成树上，逐项命令见本文件 §7；"全绿"均以用例计数为准，不以 `BUILD SUCCESSFUL` 为唯一证据）**:

| # | 门禁 | 实测 |
|---|---|---|
| ① | 桌面 C++ 全量（含单进程直跑复核） | **1407/1407**（ctest 与 `game-core-tests.exe` 直跑双绿；基线 1326 → 三批净增 81；`llvm-mingw-*-ucrt-x86_64\bin` 在 PATH） |
| ② | `:core:engine` 全量（`--rerun-tasks` + 本工作树 JNI） | **3306 用例 / 303 类 / 0 失败 / 0 错误 / 0 跳过**；`Diff*` 对拍 **47** 类全绿 |
| ③ | 桌面 JNI 重建（对拍用） | 成功（9.5 MB，产物时间戳新） |
| ④ | 生成物零漂移 | 生成器**幂等**（连跑两次哈希相同）+ `git diff --exit-code` 空；**195 动作 / maxId=1843** |
| ⑤ | 六模块 detekt | 全绿；**baseline 全 0**（未新增条目） |
| ⑥ | 主源 + 测试源编译（六模块） | 全绿（含 `:feature:game` / `:app` 测试源——**修复 #1 前为红**） |
| ⑦ | `:app:externalNativeBuildRelease`（NDK arm64）+ `:app:lintRelease` | 均成功（lint **42 warnings** 非阻断，3 条走 `lint-baseline.xml` 过滤） |
| ⑧ | 模块回归 | `:core:domain` **1758/0**（`ReverseChannelPolicyGuardTest` **6/6**）；`:core:data` **716/0/0（15 跳过）**；`:feature:game` **872/0/0**；`:app` **1020/0/0（2 跳过）** |
| ⑨ | 冻结/独占纪律 | 三批**零冻结违规**；各批本批独占文件均在位（`dispatch_w4X.cpp` / `w4X.mjs` / `W4XChannelClosures.kt` / `w4X_tests.cmake`） |
| ⑩ | 子批 tag | 三批 19 个子批 tag 齐备（`w4a/01`–`w4a/05`、`w4b/01`–`w4b/05`、`w4c/01`–`w4c/08`）+ `w4-base` |

**独立内容抽验（不采信批次自报，逐条带证据）**: ① 弟子操作面**九事务**逐条接线实证（RENAME/CHANGE_TYPE/TOGGLE_FOLLOW → `GameEngineCoordination.kt:123/:149/:173`；REWARD_ITEM/USE_PILL → `DiscipleFacadeImpl战斗Ops2.kt:99/:296`；REPLACE_MANUAL → `GameEngineManualOps.kt:141`；START_BLOOD_REFINEMENT → `GameEngineBloodRefinementOps.kt:66`；SYNC_STATUS/SYNC_ALL_STATUSES → `DiscipleStatusService.kt:409/:296`）；② 婚姻审批接线实证（`GameEngine.kt:288` 1592 / `:339` 1750）；③ WS-5b **协议全链**实证（域字段 `GameData.kt:880/:892` → Room 列 + `GameDatabaseConfig.DATABASE_VERSION=51` + `MIGRATION_50_51` 注册于 `GameDatabase.kt` → 迁移测试 `RoomMigrationV50To51Test` → 存档往返 `SaveDataTerrainFreezeTest` → C++ `GameCoreBridge` 参数 → 引擎回填 `GameEngineSaveOps.kt:23-27`）；④ 随机源收敛实证（顶层可变 `xxxRngManager` 全仓 **0 处**）；⑤ 洞府死链实证（`CaveExplorationRewardOps.kt` 整文件已删、主源 `CaveExplorationTeam(` 构造 0 处，`processSectDisciplesAging` 等活路保留）。

**🔴 复验中记录的对拍口径空白（2026-09-15 已拍板并入 W4-D/D3）**: `DiffSurfaceAssertion.kt:76-77` 把 WS-5b 的 `terrainTiles` / `mapGenVersion` 列入**对拍排除面**（遍历时 `continue` ⇒ 该两字段既不校验"值一致"也不校验"Kotlin 侧存在"）。**准确口径**：① 地形**生成算法**的跨语言一致性**已被保障**——`DiffSectTerrainTest` 对 C++ 与 Kotlin 生成器做**全数组逐位**对拍（多种子含负数/极值 + 生产 128² 配置 + `cellHash`/`smoothNoise` 逐点探针）；② 冻结链路各环节**各有单点用例**（C++ `terrain_freeze_test.cpp` 6 用例含"存段优先/回填幂等/无种子不生成/ensure==generate 逐位"、Kotlin `SaveDataTerrainFreezeTest` 存档往返、`RoomMigrationV50To51Test`）；③ **空白 = "跑完整业务周期后两端全状态逐字段一致"这条整体性保证对它们不适用**——两端写值若在某条路径上分叉（`mapGenVersion` 有 C++ 侧 JNI 参数与 Kotlin 首次落段两个写入点，且 **C++ 侧无自己的常量**）、或某条路径漏写/清零，47 个 `Diff*` **不会报红**。**处置（用户拍板 2026-09-15）**：并入 **W4-D/D3**（`DiffAuthoritativeTickTest` harness 对齐生产）——先让 harness 的 Kotlin 侧跑与生产同款的 boot 回填，再从排除面移除该两字段，使它们按普通字段参与对拍。**在 D3 落地前**，任何改地形生成器 / 改 `MAP_GEN_VERSION` / 改回填路径的批次**必须**同步两端并跑 `DiffSectTerrainTest` + C++ 冻结 6 用例（否则对拍不报红）。

**遗留（不在本批范围）**: W4-D 汇流波（D1 `batch-22a` 埋点 → D2 `w3-11` 月年编排残差 + §2.3 宿主文件族调用点清理 → D3 `DiffAuthoritativeTickTest` harness 对齐 → D4 `w3-13` 反向通道删除 → D5 死代码清零 → D6 文档收口，见 `docs/parallel-batches-w4/README.md` §8）；非并行轨（真机验证批 / WS-4 需设计文档 / WS-1 阶段 3 **经实测已决策不做，见下** / `TimeSystem.onPhaseTick` 删除待拍板 / `PresentationRandom` 一致性口径待拍板）。

**性能口径决策（2026-09-15 续，用户拍板"按桌面 Release 数据决策"）**: WS-1 阶段 3（协议 v2）**经实测决定不做**——`dirty_tracker_bench_test.cpp` 实跑得每旬全脏 `diffToJson` **7.9ms@100 弟子**（~0.10 ms/弟子/旬），而**玩家实际规模 ≈100 弟子**（非配置上限 `maxDisciples=1000`——该配置**引擎零引用**，属死配置，见 §4.2）；2x 速（1s/旬）下占旬间隔 **0.8%**，且每旬镜像跑在引擎后台协程（`GameEngineCoreAuthoritativeOps.kt:63`）**不占渲染线程** ⇒ 不构成瓶颈。**再评估阈值（数值化、可机测）**：① 实际弟子规模 **>400**（0.10 ms/弟子/旬 × 中端机 2.5× 系数 ≈ 旬间隔 10%）；② 或 W4-D/D1 埋点真机实测每旬镜像 **>100ms**——观测手段 = D1 既有埋点，零额外成本。**🔴 口径纪律**：性能规划一律以"**实际规模**"为输入，禁止用配置上限外推（该项差点导致本波误判为"满编每秒 101ms 卡顿"）。

## 2.69 W4 ②③ 双项（2026-09-15）：`PresentationRandom` 按场景派生 + `TimeSystem.onPhaseTick` 迁测试源集

批次: W4 剩余工作实施文档 ②③（"交他人实施"两项，按其 §2.B/§2.C 照单执行） | 产物: `PresentationRandom.scene()/fnv1a64()` + `BootSequenceController` 播种接线、`TimeAdvanceBaseline.kt`（测试源集）、`PresentationRandomSceneTest`（7 用例）、场景键登记表（[rng-source-inventory §7](rng-source-inventory.md)）；**零 C++ 改动、零协议面、零 ActionId 变更**

**② `TimeSystem.onPhaseTick` 迁测试源集（§2.C 全项）**:
- 新增 `core/engine/src/test/.../system/TimeAdvanceBaseline.kt`——`onPhaseTick` **逐字搬运**为 `internal fun MutableGameState.advancePhaseBaseline(phasesToSettle: Int = 1)`（常量仍引 `TimeSystem.PHASES_PER_MONTH/MONTHS_PER_YEAR` 单源）；KDoc 写明"冻结基准，禁止优化/改语义"；生产类删除该方法 + 闲置 import。
- 6 个测试文件 9 个调用点改调基准并清理 `TimeSystem(store)` 局部构造（`DiffTimeTest` / `DiffPhaseSettlementTest`×3 / `DiffMonthSettlementFixture` / `DiffYearSettlementTest` / `DiffAuthoritativeTickTest`×2 / `SettlementTransactionMergeTest`——后者 `settlePhase(timeSystem)` 签名去参）；`TimeSystemPureLogicTest` 内联复刻改为驱动基准（消除第二份复刻），常量断言改引 `TimeSystem` 单源。
- **反向验证（必做项，已过）**：故意把基准 `>=` 改 `>` ⇒ `DiffTimeTest` **4 用例即红**（月进位/旬进位/长推进/年进位）——证明基准仍被真实消费；随后还原。
- **语义零变更得证**：引擎全量用例数与搬运前**完全一致**（3306/303 类/0 失败/0 跳过），47 个 `Diff*` 全绿。
- `PhaseSettlementExecutor` 按方案**本项不处理**（有生产注册，触及 W4-D 冻结宿主族 ⇒ 登记 D5 复议）。

**③ `PresentationRandom` 按场景派生（§2.B 全项）**:
- 根因修复"文档与实现不符"：`seedFromWorld(mapSeed)` 全仓零调用 ⇒ 种子恒为 `DEFAULT_SEED`。现 **`BootSequenceController.generateMapPreloadData()` 接线播种**（构造 5→6 参；boot 为新档/读档唯一汇合点 ⇒ 一处接线覆盖两端；`mapSeed` Int→`toLong()`）。
- `PresentationRandom` 增补（API 向后兼容，既有 6 公开方法签名不变）：`@Volatile worldSeed` + 私有构造 + **`scene(key)`**（独立实例，序列 = `(worldSeed, FNV-1a64(key))` 唯一决定）+ 文件级 `internal fun fnv1a64`（**FNV-1a 64**，禁 `String.hashCode()` 的跨平台理由写入 KDoc）。
- 场景键表逐点接线（详表见 [rng-source-inventory §7.2](rng-source-inventory.md)）：天劫立绘 `trial.portrait.<id>`（`CombatantPortrait` 无立绘分支——同参战者恒同立绘）；弟子交谈 `chat.<id>.<gameYear>`（`remember(disciple.id, gameYear)`；决策类抽取仍走 CHAT 分区**不变**）；外交 8 抽取点 `diplomacy.<sectId>.<种类>`（`DiplomacyFlows.diplomacyScene` 助手，**builder 签名未动**）；送礼反馈 `favor.<sectId>.gift.<accept|rejected>`（`GiftService` 4 处，**native 臂与 Kotlin 臂同键 ⇒ flag 两侧文案一致**）；加载提示 `loading.tip`（常量键=刻意的固定轮播）。云层/装饰保持"持续变化"语义不改。
- **B.8 盲区逐条核销**：#3 重跑 `git grep`——新增消费面 `HeavenlyTrialCombatScreen`（仅传参不消费）/ `HeavenlyTrialViewModel`/`WorldMapInteractionViewModel`（DI 根暴露）/ `PortraitPool`/`RandomWithExt`（KDoc 引用）/ `W4AChannelClosures`（注释），无需改键；#5 **`SectDiplomacyDialogTest` 多样性断言零改动**——键化发生在 `DiplomacyFlows` 调用处而非 builder 内部，测试自持实例语义不变（实测 872/0 持平）；#2 文档估"9 个构建函数"实为 **8 个**（GiftTexts 4 + VassalTexts 4），已全键化无遗漏。
- **新测试 `PresentationRandomSceneTest`（7 用例）**：同键逐位相同 / 异键不同 / 世界种子参与派生 / 与根流互不影响 / 零状态写入 / **FNV-1a 字面量跨平台锚点**（iOS 侧须复现同常量）/ **`seedFromWorld` 生产调用点源码扫描守卫**（防"定义了但从不调用"复发）。

**门禁实跑（零 C++ 改动 ⇒ 桌面 C++ 套件与 NDK/lint 按 §4 门禁 1/6 豁免；生成物未触碰）**: `:core:engine` **3313 用例 / 304 类 / 0 失败 / 0 跳过**（= 基线 3306 + 新增 7；47 个 `Diff*` 全绿）；`:feature:game` **872/0/0**（基线持平）；六模块 detekt **全绿 baseline 全 0**（途中 5 处 `MaxLineLength` 新违规全部**实修**拆行，未入 baseline）；六模块主源 + 测试源编译全绿。

**遗留**: 手工验证项（同一天劫重进 3 次立绘一致 / 同年交谈开场白一致——需真机或模拟器人工确认，自动化已覆盖序列恒定语义）；表现面变更（文案/立绘选择序列改变）属预期口径修正，已登记双更新日志；`version.properties` 递增由用户决定。


## 2.70 邮件系统清理（2026-09-15）：仅保留节日 + 白名单特权邮件，在线拉取与单用户定向注入整体下线

批次: 需求「清理游戏内的邮件，仅保留节日邮件和白名单特权邮件」 | 拍板口径: 溢出/秘境关闭/天枢殿迁移补偿等功能性邮件**保留**（资产保护机制）；存量邮件**不清理**（仅停新产生，自然过期）；代码**彻底删除** | 产物: 3 文件删除 + 14 文件修改；**零 C++ 逻辑改动（`month_settlement.h` 仅 3 处过时注释修正）、零协议面、零 ActionId 变更、零 Room 迁移**

**删除面**:
- **在线邮件月度拉取**：`MailService.fetchOnlineMails` / `processMonthlyMails` / `MailListApiResponse` / `MailApiData` 删除，`resetAndInitSlot` 不再拉 `mail/list`；构造参数 `httpClient` 移除。**`MailSystem` 整文件删除**（唯一存在意义 = 月结触发在线拉取 + 转发 no-op 生命周期）——`CoreModule.provideSystemManager` 集合 9 → 8；`MonthSettlementResidualExecutor` 删步骤 4g + `systemManager` 参数（月变残留扇出 3 → 2 项），`GameEngineCore` 构造点同步。
- **单用户定向邮件**：`MailExclusiveBonusOps.kt`（`exclusive_bonus_20260904`，已过 2026-09-04 截止日 = 守卫永假死代码）与 `MailCompensationOps.kt`（`compensation_storage_bag_v1`）整文件删除；`injectExclusiveBonus` + `EXCLUSIVE_BONUS_*` 常量、`GameEngine.sendExclusiveBonus` / `sendStorageBagCompensation`、4 个 boot 注入点（Load/NewGame/Restart/CloudLoad）同步清除。**`injectWhitelistBonus` / `sendWhitelistBonus` 全数保留**。
- **顺带清偿（本批删除造成的孤儿链）**：`MailService.clearForSlot` / `initialize()` / `release()`（唯一调用者 MailSystem 已删；档位删除实际走 `StorageEngine:436` 直调 `MailDao`，本就旁路）+ `MailRepository.deleteAllForSlot` 接口方法与 `MailRepositoryImpl` 实现；`TianshuHallCompensationOps` KDoc 对已删文件的引用更正。

**保留面（逐项核验未动）**: 节日/内置发放 `loadBuiltinMails`、白名单福利三重防护、溢出邮件全链路（`OverflowMailSender`/`handleOverflowResult`/C++ 草稿桥接）、秘境到期关闭邮件、天枢殿重建补偿、GM 通用补偿入口 `sendAdminCompensation`；存量 `online_*`/定向邮件的领取/过期/"删除已读"路径（`mailRecords` 对账 + `remoteMailId` 索引）原样保留。

**测试**: `MailServiceTest` 删专属福利 9 用例 + 储物袋补偿 8 用例（含共用 `installInMemoryMailDb` 辅助与 `httpClient` 桩），白名单/领取/`resetAndInitSlot` 用例保留；`:app` 用例数 **1020 → 1003（−17，精确对账）**。

**门禁实跑（桌面 C++ 套件豁免——仅注释改动；生成物未触碰）**: `:core:engine` **3313 / 304 类 / 0 失败**（47 `Diff*` 全绿，基线持平）｜`:core:domain` **1758/0**｜`:feature:game` **872/0/0**｜`:app` **1003/0/0（2 既有跳过）**｜六模块 detekt 全绿｜四模块主源 + 测试源编译全绿。

**遗留**: `MailRepository.existsByRemoteId` 为**本批之前即存在的零调用死代码**（旧在线邮件路径遗留），登记待 D5 死代码清偿统一处置，本批未越界处理。


## 3. 验证结果（当前门禁基线 + 各批数值；未达项与归属见本节末）
**当前门禁基线（2026-09-15，§2.70 邮件清理后）**：

| 验证 | 结果 |
|---|---|
| 桌面 C++ 全量单测 | **1407/1407 全绿**（§2.68 实跑；§2.69 零 C++ 改动未复跑）；运行需 `llvm-mingw-*-ucrt-x86_64\bin` 在 PATH |
| 引擎全量单测 `:core:engine` | **3313 用例 / 304 类 / 0 失败 / 0 错误 / 0 跳过**（§2.69 复跑，`--rerun-tasks` + 本工作树 desktop-jni；含 47 个 `Diff*` 对拍全绿；= 3306 + `PresentationRandomSceneTest` 7） |
| `:core:domain` 单测 | **1758 用例 / 0 失败**（含 `ReverseChannelPolicyGuardTest` 6 用例：穷尽分类 / 域结论完整 / 证据格式 / 协议名校验 / 审计红线 / 逐域回滚） |
| `:core:data` 单测 | **716 用例 / 0 失败 / 0 错误 / 15 跳过（既有）**（§2.68 复跑；= W4-00 基线 707 + C-② 新增 5 + 新 `MigrationChainGuardTest` 4） |
| `:core:ui` 单测 | **146 用例 / 0 失败**（W4-00 实测值；三批零触碰 `:core:ui`，本波未复跑） |
| `:feature:game` 单测 | **872 用例 / 0 失败 / 0 错误**（§2.68 复跑；W4-00 表所列 2 例 `EdgeKtxSyncTest` 失败已随并行渲染批落地消除） |
| `:app` 单测 | **1003 用例 / 0 失败 / 0 错误 / 2 跳过（既有）**（§2.70 复跑；= §2.68 基线 1020 − §2.70 删除的定向邮件用例 17，精确对账） |
| detekt | ✅ **六模块 `detekt` 全绿，baseline 全 0**（§2.68 复跑；新增唯一违规 `SpreadOperator` 按本仓既有 4 处同款先例以带理由 `@Suppress` 处置，未进 baseline） |
| 动作计数 | **195 动作，maxId=1843**（`gen-action-ids.mjs` 实跑口径；W4-00 后 169 → 三批 +26 = A 12 + B 9 + C 5）。**1843 = `DIPLOMACY_WARNING_STAGE_TX`** |
| 生成物 | 生成器**幂等**（连跑两次哈希相同）+ `git diff --exit-code` 空 |
| **NDK arm64** | ✅ **`:app:externalNativeBuildRelease` BUILD SUCCESSFUL（§2.68 实跑，3m03s）** |
| **lintRelease** | ✅ **`:app:lintRelease` BUILD SUCCESSFUL（§2.68 实跑，6m15s；42 warnings 非阻断 + 3 条走 `lint-baseline.xml` 过滤）** |
| 编译 | 主源 + 测试源（`:core:domain`/`:core:engine`/`:core:data`/`:core:ui`/`:feature:game`/`:app`）全绿；桌面 JNI 重建成功 |
| **反向信封体积构成（本批新增验收面）** | 单元 harness 稳态窗口（40 弟子 / 20 丹药 / 3 gameData 字段 / 1 弟子 / 1 集合变更）**合计 20121B = gameData 38B + 弟子 2462B + pills 17549B**；`lockedBeastIds` 段关闭省 39B/窗口 |
| 未收敛登记 | **当前为空**——W4-00 表所列两项（`:feature:game` `EdgeKtxSyncTest` 2 例 / `:app` `SpriteCodegenSyncTest` 1 例）已随并行渲染批落地消除，§2.68 复跑均为 0 失败 |

> **历史时点基线（2026-09-13，§2.59 后）已被上表取代，仅备追溯**：引擎 3260/0/0（293 类）、桌面 1309/1309、`:feature:game` 868/0、动作 171/maxId=1734、detekt 六绿、阶段 4 JNI 基准 ratio 0.8、NDK 2m46s ✅ / lint 14m20s ✅。原表所记「纹理重构族在途破损 ⇒ NDK/lint 不可走」经实测**证伪**。


**各批验证数字**（只留数值；各批**备注与未达项归因**见 §2 对应小节；命令模板见 [parallel-batches-w2/README](parallel-batches-w2/README.md) §6）：

| 批号（日期） | 桌面 C++ | 引擎 `:core:engine` |
|---|---|---|
| **§2.70 邮件系统清理（09-15）** | —（仅注释改动） | **3313 / 304 类 / 0 / 0**（47 `Diff*`；`:app` 1020→1003） |
| **§2.69 W4 ②③ 双项（09-15）** | —（零 C++ 改动） | **3313 / 304 类 / 0 / 0**（47 `Diff*`） |
| **§2.68 W4 三批集成收口（09-15）** | **1407/1407** | **3306 / 0 / 0**（303 类，47 `Diff*`） |
| **§2.62～§2.64 W4-A/B/C 三批（09-15，并行）** | 1361→1407（批内逐步） | 3295→3306（批内逐步） |
| §2.61 W4-00 并行前置批（09-14） | 1326/1326 | 3281 / 0 / 0（296 类） |
| §2.53 Batch-21 反向通道逐域关闭（09-15） | 1322/1322 | 3281 / 0 失败 / 0 跳过 |
| §2.58 随机源治理收口（09-14） | 1322/1322 | 3260 / **1 失败**（§2.59.1 已清） |
| batch-23 + batch-24 + 存量清偿（09-14） | 1309/1309 | 3249 / 0 / 0 |
| batch-12 + batch-20b（09-13） | 1284/1284 | 3237；**27 失败**（0 归属本批，09-14 全清） |
| batch-11（09-12） | 1073/1073 | 3161 / 0 / 0 |
| batch-14（09-11） | 1067/1067 | 3162 / 0 / 0 |
| batch-18（09-12） | 1204/1204 | 门控 12/12 |
| batch-20a（09-12） | 1216/1216 | 门控 4/4 |
| batch-19（09-12） | 1123/1124（1 例 batch-18 在途） | 门控 10+ |
| batch-17（09-12） | 1204/1204 | 未达（并行 session 阻断） |
| batch-16（09-12） | 1113/1113 | 3181 / 0 / 0 |
| batch-15（09-12） | 1087（分支口径 1056+31） | 3174 / 0 / 0 |
| W2-a（09-11） | 1056/1056 | 3157 / 0 / 0 |
| 集成收口批（09-11） | 1023/1023 | 3146 / 0 / 0 |
| batch-01 续修（09-10） | —（engine 拆分） | 3140 / 0 / 0 |
| batch-10 / batch-08（09-10） | NDK 门通过 / 995/995 | 3130 / 0 / 0 |
| batch-07 / batch-06（09-10） | 958（主树共存 974）/ 980 | 3121 / 3127 全绿 |
| batch-05 / batch-03（09-10） | —（零 C++ 改动） | 3118 / 0 / 0 |
| M3 第四～十批（09-08～09-09） | —（零 C++ 改动） | 3118～3125 / 0 / 0 |
| M3 首批～第三批（09-08） | 912/912（第二批） | 3118～3119 / 0 / 0 |
| M1 首批～第三批（09-05～09-06） | 782 / 787 / 795 | 3078 / 0 / 0（对拍 251 用例） |
| M2 首批～第八批（09-06） | 815 → 869 | 3079 → 3082 / 0 / 0 |
| M2 续批 WS-5（09-08） | 911/911 | 3118 / 0 / 0 |
| M0 追加/收尾批（09-05） | NativeSurfaceView 21/21 | 模块级回归（无引擎全量） |
| M0 主体（09-04） | — | 模块级验证（assembleRelease 全通过） |


**未达项与归属（诚实口径，不计入"全部通过"）**：
1. **真机（物理设备）验证残留 10 项**——batch-10 模拟器会话未覆盖（A2/A4/C1/C3/C4/C6/D2/D3/E2/E3），见 §4.1；真机不可得时的替代验证口径见 §2.39。
2. **WS-2 S5 / S8 / S6 三项真机回归**——batch-10 会话因进度门控未达（任务阁/秘境入口未解锁），随物理真机或深度游玩补验。
3. **WS-1 残留口径**（非失败，属已登记欠账）——每旬弟子全脏镜像成本受"全量实体 JSON 序列化"支配、验收"2x 速每旬 <2ms"仅中小规模存档达成 ⇒ 随计划 v2 阶段 3 数据导向存储交付；真机绝对值待补 debug 埋点小批。
4. **两项已拍板反转**（原登记作废）：~~P1-5 月结配对结构级优化~~ → **不采纳（终局，§2.60.1）**；~~地形不入存档协议~~ → **采纳完整业界方案（§2.60.2）**，实施 = WS-5b 待派工（🔴 前置红线：改地形输出的批次须等其落地）。
5. **历史批次的并行阻塞项均已消解**——"纹理重构族在途破损 ⇒ lint/NDK/模块回归三关不可走"经 2026-09-15 实测**证伪**（NDK 35s / lint 12m09s 均 SUCCESSFUL）；batch-17/18/19/20a 的测试源编译与引擎对拍阻断均随并行批收口解除。
6. **`Jade` 凭据持久化环境缺陷**（§2.50 坑）——`FakeAtomicStateStore` 事务缓冲与 `sectLevelClaimRecords` 交互，生产侧静默失败已根因修复，环境缺陷待专项。


## 4. 遗留待办（明确未完成，勿误判为"已完成"）

### 4.1 需要专项的高危大项
| 项 | 状态 |
|---|---|
| **三处顶层可变 `xxxRngManager` 同族遗留**（`EnemyGenerator` / `AISectAttackManager` / `AISectTeamComposer`） | **✅ 已清偿（2026-09-15，§2.64.1 W4-C C-③）**——三处均改为形参必传，`GameEngine.kt` init 块三行赋值随之移除；实测全仓顶层可变 `*RngManager` **0 处**。`RngEngineIsolationGuardTest` 白名单未新增条目 |
| **反向同步通道逐域收尾 → 通道删除（长期主轴）** | **⚠️ 方向已定（§2.53）**——"按域全关"经 288 站点穷尽审计实测**前置不成立**（14 域无一可整体关闭）；已交付可证关闭面 68 单元（67 gameData 字段 + 顶层段 `lockedBeastIds`）+ 逐域关闭机制（策略单点 / 双端闸门 / 逐域回滚 / 关闭域写入检测 / 穷尽分类守卫）。**2026-09-15 W4-A/B/C 三批再消除一批稳态写者**（弟子管理九事务 / 婚姻审批拒绝 / 巡逻·矿场 UI 直改 / 战斗伤亡与战前结算 / 秘境换岗兜底 / 洞府死链，逐条 `file:line` 见 [ui-read-surface §4.4](ui-read-surface.md) 滚动更新表），但**域级"可整体关闭"仍无一成立**。**后续按 [ADR reverse-channel-elimination](adr/reverse-channel-elimination.md) + [parallel-batches-w3](parallel-batches-w3/README.md) 十三批推进**，终局删除 = `docs/parallel-batches-w4/README.md` §8 的 W4-D/D4 |
| **WS-5b 地图冻结批** | **✅ 已落地（2026-09-15，§2.64.2 W4-C C-②）**——"生成即数据 + `mapGenVersion` 协议全链 + 老档按种子再生回填"全链交付（C++ 状态模型 + `json_codec` 双向 + Kotlin `@ProtoNumber`/`@ColumnInfo` + Room `@Database` 50→51 + `MIGRATION_50_51` + 迁移测试 + 存档往返测试）。**遗留口径风险**: 对拍面把 `terrainTiles`/`mapGenVersion` 列为镜像生成字段排除比对（§2.68），跨实现等价由 `terrain_freeze_test.cpp` + `DiffSectTerrainTest` 承担 |
| **WS-4 NPC 移动系统（待玩法设计文档）** | 实现前需用户补充玩法设计文档（数量上限 / 生成规则 / 与弟子系统关系）；E3 组件族（§2.14）与寻路地基（静态地形 + 建筑占位 + 道路，§2.19）已就绪，可行走语义（树/边界是否阻塞）待拍板 |
| **WS-1 残留口径 / 阶段 3 立项** | **✅ 已决策（2026-09-15，用户拍板"按桌面 Release 数据决策"）：不立项协议 v2**。**决策依据（桌面 Release 实跑 `dirty_tracker_bench_test.cpp`）**：每旬全脏 `diffToJson` = **7.9ms@100 弟子**（实测口径 ~0.10 ms/弟子/旬：100→7.9ms、1000→100.9ms）；**玩家实际规模 ≈100 弟子**（用户口径）⇒ 2x 速（1s/旬）下导出仅占**旬间隔 0.8%**；且每旬镜像跑在**引擎后台协程**（`GameEngineCoreAuthoritativeOps.kt:63` → `engineScope`/`gameDispatcher`），**不占渲染线程** ⇒ 不直接掉帧。按中端机 2.5× 系数估算 ≈20ms/旬 = 间隔 2%，**约 5 倍余量**。⇒ **协议 v2（145+ 列写点 + 47 对拍 + 存档格式）的风险远大于收益，不做**；同时**不做**非协议微优化（空闲窗口 3.8ms@100，且任何"跳过序列化"的优化都必须引入写屏障——正是 §2.34 已摘除的风险面）。**再评估阈值（数值化，可机测）**：① 实际弟子规模 **>400**（= 0.10 ms/弟子/旬 × 2.5 设备系数 ≈ 旬间隔 10%）；② 或 D1 埋点真机实测每旬镜像 **>100ms**。**观测手段 = W4-D/D1 既有埋点（零额外成本）** |
| **真机（物理设备）验证残留** | 模拟器会话未覆盖 10 项：A2 ASTC 缺失机 RGBA 回退 / A4 旋屏 / C1 偷盗钩子自然触发 + TapDB 上报 / C3 S5 战斗任务 / C4 S6 秘境全链 / C6 ThermalMonitor 真实热档 / D2 放置确认步 / D3 道路装配 / E2 云存档 / E3 WS-1 绝对值（需先补 debug 埋点小批） |
| **`Jade` 凭据持久化环境缺陷** | **✅ 已清偿（2026-09-15，§2.63.B0 W4-B）**——契约考古推翻 §2.50 的 batch-19 归因，环境缺陷随凭据持久化契约修正一并消除 |
| **`TimeSystem.onPhaseTick` / `GameSettingsData.autoSave` 删除** | **`autoSave` 已拍板按"清理执行"（2026-09-15）** → 实施落点 W4-D/D5；**`onPhaseTick` 已按 §2.C 实施迁入测试源集（2026-09-15，§2.69）**——`TimeAdvanceBaseline.advancePhaseBaseline` 冻结基准 + 6 测试改调用 + 反向验证过；`PhaseSettlementExecutor` 同类项登记 D5 复议 |
| **线上隐私政策落后于实际集成**（§2.67） | **✅ 已修复（2026-09-15）**——Pages 发布源由 `master/docs` **切到 `main/docs`**（根因修复：政策更新写在 main，源指向 main 即自动同步），线上实测已是「2026年8月13日」版并声明全部聚合广告 SDK 与 TapDB |
| **远端 `hsmy7/mnzm` 与本地的关系**（§2.67） | **已查清**：它是**本项目自己的远端仓库**（非网页仓——网页仓是另一个 `hsmy7/index.html`，其末次提交写着"将隐私政策重定向到新地址 mnzm 仓库"）。内含两条分支：`master`（默认 + Pages 源，旧 1.4.x 线，内容停 2026-06-28）与 `main`（**本项目线**，tip `ad6ff6c9` = 2026-09-04「程序化天空」批，`version.properties=4.01.12`）。**本地与远端内容连续但提交血缘断裂**：本地 26 个提交全为 2026-09-12～09-14、作者 `mnzm-dev`，远端提交在本地对象库中**零命中**，本地 `.git` 无任何 remote-tracking ref（从未 fetch）⇒ **2026-09-04 之后（M0–M3 + W4-00）的内容只存在于本地，远端一个都没有**；历史重建见 §2.40 |

**已清偿项索引**（只列批号与结论，明细见 §2 对应小节）：
`§2.5/§2.6` M0 追加·收尾批（P0-3 / P1-4 / WS-6 / RNG 方案②）｜`§2.7` P1-4 守卫分类勘误｜`§2.8`–`§2.10` M1 三批（S1-S3 / WS-1 降本 / E1+WS-7，M1 全清）｜`§2.11`–`§2.18` M2 八批（S4 / S5 / S8 / E2+E3 / P1-5 / E2 残留 / S6 / S7）｜`§2.19` WS-5 地图真源入 C++｜`§2.20`–`§2.29` M3 收敛（死代码族 / 反向通道审计 / 机械族 / RoomMigration+异常族 / 判定族 / 边界族 / 参数跳转族 / 复杂度族 / TMF 第一轮 / 拆分队列首轮）｜`§2.30`–`§2.32` 拆分队列收尾（core:engine 34→0 / game 8→0 / domain 2→0，**六模块 baseline 全 0**）｜`§2.33` 协程取消传播专项（61 处）｜`§2.34` dirty 记账摘除｜`§2.35`–`§2.51b` UI 操作面逐域下沉（建筑 / 道路 / 外交 / 弟子三子批 / 库存 / 巡逻住所 / 探索 / 生产灵田 / 月年边界 / 玉符宗门 / 秘境平台段 / 攻宗）｜`§2.40`/`§2.52` 两次集成收口｜`§2.55`/`§2.56` 残余域 + 弟子管理残差｜`§2.58`/`§2.59` 随机源治理收口 + 收敛清偿（引擎全量 0 失败）｜`§2.23.1` RoomMigration 8 例预存失败｜`§2.60.1` P1-5 配对优化**不采纳（终局）**｜`§2.39` 真机替代验证口径（模拟器）已交付主体项｜`§2.61` W4-00 并行前置批｜`§2.62`/`§2.63`/`§2.64` **W4-A / W4-B / W4-C 三批次并行交付**｜`§2.66` 仓库对象库整理｜`§2.67` 远端关系核查 + 隐私政策根因修复｜`§2.68` **W4 三批次集成收口**｜`§2.65` **预留 W4-D 汇流波（未使用）**


### 4.2 已拍板并实施 / 保留项
| 项 | 结论 |
|---|---|
| ~~RNG 通道跨线程竞争~~ | **✅ 已收口（§2.6 方案② + §2.39 断言升级）**——① UI 抽取派生化/引擎化；② `initSystemSeed` 并入引擎重启；③ 存档快照走引擎线程（读档恢复此前已收敛，`ProductionTransactionManager` 抽取点生产零调用）。开发期 debug 观察窗 ≥60 分钟（90,738 行 logcat）零警告 ⇒ 四 rng 入口升级 `jniRequireEngineThread`（过渡守卫删除），断言版复跑零误杀 ⇒ **P1-4 正式收口** |
| `TimeSystem.onPhaseTick` | 审计标"生产死代码"，但它是 **6 个 Diff 测试文件的 Kotlin 跨语言对拍基准**（C-15 特意切真实 TimeSystem 防"复刻漂移"）⇒ **保留**。若要按审计字面删除，须先把 6 个测试重写为纯 C++ 断言——会失去独立 Kotlin 基准，**需用户拍板**。**2026-09-15 补充实测（§2.68 复验）**：① `onPhaseTick` 全仓**零生产调用**（唯一非测试引用是 `SystemManager.kt:22` 的注释）；② 它**不是孤例**——`PhaseSettlementExecutor.execute()`（`PhaseSettlementExecutor.kt:61`，KDoc 自述"纯对拍基准，生产 AUTHORITATIVE 每旬走 C++ `runPhaseSettlementCore`"）是同一类"Kotlin 基准路径"构造 ⇒ 二者应同进同退；③ 若目的只是"去掉看起来像生产 API 的死方法"，**更省的替代** = 把基准实现移入测试源集（6 个测试仅改 import）——同样消除生产面死方法，且**不损失**对拍基准。**✅ 已按③实施（2026-09-15，§2.69）**：`TimeAdvanceBaseline`（逐字搬运 + 冻结基准 KDoc）+ 6 测试改调用 + 反向验证（改坏即红）+ 引擎全量 3306 零漂移；`PhaseSettlementExecutor` 登记 D5 复议 |
| `StorageConfig.maxDisciples` / `GameConfigData.DiscipleSection.maxDisciples` | **2026-09-15 实测：死配置 + 性能评估陷阱**——① `GameConfigData.kt:50 val maxDisciples = 1000` **引擎零引用**（无任何招募/容量判定使用它；实际名额由长老槽"招贤"/特质加成与 `GameConfig.kt:730` 每月招募上限等玩法机制决定，玩家实际规模 ≈100 弟子）；② `StorageConfig.kt:72 val maxDisciples: Int` 接口属性**无实现读取**，全仓唯一读取点是 `ConfigLoaderTest.kt:47` 断言默认值 1000。⇒ **按它做性能/容量评估会得出错误结论**（本波 §2.68 性能决策过程中即差点如此）。**处置**：登记 W4-D/D5 死配置清理；**性能规划口径纪律** = 一律以"实际规模"而非"配置上限"为输入（已回写 §2.68 与 §4.1 WS-1 行） |
| `PresentationRandom` 跨会话一致性 | **2026-09-15 实测（§2.68 复验）发现文档与实现不符**：`seedFromWorld(mapSeed)`（`PresentationRandom.kt:49`）**全仓零调用**（生产与测试均无；`git grep` 该提交引入时即无调用者）⇒ 表现流种子**恒为编译期常量 `DEFAULT_SEED = -7046029254386353131`**（`:112`），KDoc 所述"种子由 `mapSeed` 派生（同会话内可复现）"**不成立**——实际是"进程启动起的一条固定序列，谁先抽谁拿到"，连"同会话可复现"都取决于玩家点过哪些界面（90 处消费点共享同一流）。**候选处置（需拍板）**：(a) **按场景派生**（推荐）——调用方改为 `seed = mapSeed xor SALT xor hash(场景键)` 每次重新派生 ⇒ **同一存档同一界面文案恒定、跨会话一致**，且**零协议面、不入档、不污染决策流**；(b) 回到文档承诺——在新档/读档处调用 `seedFromWorld(mapSeed)`（按存档播种，但同一存档两次进入仍可能不同，因为流位置取决于会话内抽取历史）；(c) 彻底入档（写进 `rngStates` 或新字段）——**不推荐**：新增协议面 + 存档迁移 + 47 对拍面 + iOS 侧同步，收益仅"第 N 次进入看到第 N 套文案"。**✅ 已按(a)实施（2026-09-15，§2.69）**：boot 接线播种（`seedFromWorld` 守卫锁死）+ `scene(key)` 场景派生（FNV-1a 64 跨平台锚点）+ 场景键表全接线 + `PresentationRandomSceneTest` 7 用例；跨会话一致性达成 |
| `GameSettingsData.autoSave` | 原判"风险>收益"**缺证据支撑**（2026-09-15 核查）：`GameSettingsData` 为**零消费者孤儿模型**（全仓无属性/列/DAO 以该类型声明，`autoSave` 无人读写）⇒ 保留的真实效果只是"多一份死模型 + 一份序列化面"，删除成本远低于原判；建议后续清理批单独处置（已登记 [w3 §1.1](parallel-batches-w3/README.md)） |


### 4.3 规划文档说明
- `task_plan.md` / `findings.md` / `progress.md`：本任务的长期规划/进度记忆（planning-with-files 规范产物），非一次性调试代码，已保留并入库，供后续批次接续使用（**坑与教训的权威落档处 = `findings.md`**，§2 各批只留一句摘要）。

## 5. 下轮建议（当前主轴与待办）

> 本节只保留"接下来做什么"。M0–M3 各批的历史派工与清偿叙述已删除（逐批结论见 §2，不在本节复述）。

**① 主轴：反向通道逐域收尾 → 通道删除**（§2.53 / [ADR](adr/reverse-channel-elimination.md) / [w3 十三批](parallel-batches-w3/README.md)）
288 站点穷尽审计实测 **14 个域无一可整体关闭**（弟子通道 46 稳态站点 / 9 类实体集合 82 站点 / 64 个 gameData 字段仍有稳态写者）⇒ "按域全关"改判为**长期主轴**：按 `ui-read-surface §4.4` 域级残余清单逐域下沉，**每完成一域即可一行关闭**（机制已就位）。
验收门禁 = **Diff 对拍全绿**（4 字段被 `DiffAuthoritativeTickTest` 覆写保留传输——该 harness 把 Kotlin 月/年完整编排纳入 AUTHORITATIVE 稳态，为生产超集；**可选清偿**：把 harness 对齐生产（C++ 月结 + Kotlin 残差），届时这 4 字段可重评）。

**② 真机（物理设备）验证批**——§4.1 登记 10 项残留；真机不可得时的替代口径见 §2.39（模拟器 + 产物字符串核验）。

**③ 待拍板 / 待立项**——WS-4 NPC 移动（需玩法设计文档）；~~`TimeSystem.onPhaseTick`（§2.C）与 `PresentationRandom` 一致性口径（§2.B）~~ **两项已实施（2026-09-15，§2.69）**；`GameSettingsData.autoSave` 删除（§4.2，已拍板待实施）。**已决策不做** = WS-1 阶段 3 数据导向存储（2026-09-15 按桌面 Release 实测决策：真实规模 ≈100 弟子下占旬间隔 0.8% 且不占渲染线程 ⇒ 无瓶颈；再评估阈值 >400 弟子或真机每旬镜像 >100ms，见 §4.1）。**已拍板待实施** = W4-D 汇流波（`docs/parallel-batches-w4/README.md` §8：D1 埋点 → D2 `w3-11` → D3 harness 对齐（**含地形 2 字段退出对拍排除面**）→ D4 反向通道删除 → D5 死代码清零 → D6 文档收口）；WS-5b 地图冻结批 **✅ 已随 W4-C 落地（§2.64.2）**。

> **🔴 派工入口**：上述全部剩余工作已整理为可逐项照单执行的 **[W4 剩余工作实施文档](parallel-batches-w4/remaining-work-implementation.md)**——含执行顺序与冲突矩阵、逐项影响范围清单（`文件:行号`）、测试方案、验收判据、风险兜底与盲区自查。派工时连同该文档一起交给实施人员。

**④ 死代码滚动清零**（§2.53 审计登记，w3 各批顺手删除或单列清理批）——`GameEngine.updatePatrolConfig` 死 API、洞府探索整族死链、`InventorySystem.materializeDiscipleBagAndMarkDead` 成员/扩展同签名遮蔽、18+11+5 处零调用者站点、`GameData` 四个零调用方辅助函数。

> **批次文档索引**：[parallel-batches-w2/](parallel-batches-w2/README.md)（batch-11～21，**已全部交付**；除 batch-12/20 保留为设计记录外实施文档已删）｜[parallel-batches-w3/](parallel-batches-w3/README.md)（**当前生效**：w3-01～w3-13 + ActionId 段 1740–1849 预分配）｜[non-parallel-work.md](parallel-batches-w2/non-parallel-work.md)（真机验证批 / 待拍板 / 立项项）。
> **工具链两条**（2026-09-13 实测）：桌面 JNI 脚本在**仓库根 `scripts/`**（验证模板工作目录为 `android/`，须写 `../scripts/...`）；桌面 GTest 运行时需 **`llvm-mingw-<版本>-ucrt-x86_64\bin`** 在 PATH（仅加外层 wrapper 目录会缺 `libc++.dll` → `STATUS_DLL_NOT_FOUND`）。


## 6. 主轴剩余：随机源治理（已拍板选项 2：根治）

> ✅ **收口批已完成（2026-09-14，见 §2.58）+ 收敛清偿批（2026-09-13，见 §2.59）**：ADR **阶段 0/1（三项全）/2（可归表现类者全）/4** 均已交付——AI 播种态根因修复（3 例转绿）、`MissionSystem` 全局解除（1 例转绿）、阶段 0 CI 红线、阶段 2 三文件收口（`BattleDescriptionGenerator`/`DiscipleChatDialog` 判为**决策类**归阶段 3）、阶段 4 10k JNI 基准（**ratio 0.8 ⇒ 无成本约束**）、两道新守卫（`DiffAiRngSeedingTest` + `RngEngineIsolationGuardTest`）。
> **剩余**：① ~~`DiffYearSettlementTest` 1 例~~（**✅ §2.59.1 清偿**——夹具快照 9 号键垃圾值触发 C++ 存档续接覆盖，测试侧修复）；② **阶段 3**（决策类逐域下沉，未开工——10k 基准已证明无 JNI 成本障碍）；③ ~~`:feature:game` 两族预存夹具失败~~（**✅ §2.59.2 清偿**）。
> **batch-21 关闭前置**：阶段 1 三项已全部交付且**播种态跨语言等价性已被 `DiffAiRngSeedingTest` 锁死**；库存开袋（路线 A）与 AI RNG 归一均达成；**`DiffYearSettlementTest` 已收敛（§2.59.1）**——⇒ ADR 侧前置全部达成。
> **🔴 但 `batch-21` 的"关闭前置"经 2026-09-15 穷尽审计（§2.53 / `ui-read-surface.md` §4.4）实测为「不成立」**：
> 该前置不是 ADR 阶段 1，而是"**各域稳态写者归 C++**"——288 站点审计显示 14 个域**无一可整体关闭**
> （弟子通道 46 站点稳态写者、9 类实体集合 82 站点、64 个 gameData 字段仍有稳态写者）。
> **batch-21 已执行（§2.53）**：交付可证关闭面（67 字段 + 1 顶层段）+ 逐域关闭机制（策略单点/双端闸门/
> 逐域回滚/关闭域写入检测/穷尽分类守卫）；**"反向通道按域全关"就此改判为长期主轴**——
> 按 `ui-read-surface.md` §4.4 残余清单逐域下沉，每完成一域即可一行关闭。

> 完整方案见 **[ADR rng-determinism-remediation.md](adr/rng-determinism-remediation.md)**（本文件只留交接必需的指针与不变量，避免文档再次膨胀）。

| 项 | 状态 |
|---|---|
| 决策 | ✅ **2026-09-14 已拍板选项 2（根治）**：一次性补齐随机源治理并把守卫落为可执行约束 |
| 问题定性 | **架构级**，但*不是*分区设计错误——分区设计（10 分区 + `rngStates` 落盘）与行业惯例一致；漏洞在于**「随机流」与「该流是否可复现/可归档」未绑死**，任何新代码随手用 `Random.Default`、自建 RNG 都无机制拦截 |
| 实测规模（**2026-09-14 阶段 2 收口后**） | **五类入口，四类未受治理**：`getRng(RngPartition.*)`（✅ 唯一合法，不在下表）/ ②`.random()`·`Random.Default`·`Math.random` / ③`GameRandom` / ④对象自持 RNG（挂钟种子）/ ⑤**默认值陷阱**（形参默认回落 `Random.Default`——ADR §5 认定的真正入口）。**注释剔除后逐规则命中**：② **21**（阶段 0 为 24；阶段 2 迁 3 处）/ ③ **0（已摘除）** / ④ **2**（阶段 0 为 3；`CloudLayerAnimator` 摘默认值）/ ⑤ **27**。逐模块（②/③/④/⑤）：core:domain 5/0/0/19、core:engine 14/0/2/7、core:data 1/0/0/0、feature:game 1/0/0/1、core:ui 与 app 全 0。**权威计数以 `RngSourceGuardTest` 的登记上限为准**（该守卫自己报数，见 `docs/rng-source-inventory.md`） |
| 旧口径说明（勿再引用） | ADR/本文旧写的「`Random.Default` **114 处**」与「`GameRandom` **8 处**」两个数字**都不准确**：前者统计**含注释里的字面量**（注释剔除口径后为 24 处 ②类）；后者 8 处中 **6 处是死代码**（全仓零调用），**真实生产调用 4 处**，已随阶段 1③ 全部处置 |
| 最大发现 | `GameRandom` 种子 = **挂钟时间**、`setSeed()` 生产零调用、`@ThreadLocal` 每线程独立流；被误用于 **`mapSeed` 生成**等决策路径。其 KDoc 自称"确定性存档"——**未实现，死抽象**。**已物理删除**（残留调用变编译期报错） |
| 实施（**进度已更新 2026-09-14，§2.58**） | **阶段 0 ✅**（分类表 + `RngSourceGuardTest` + **CI 红线 step 已落 `ci.yml`**）→ **阶段 1 ✅（① 开袋 ② AI RNG 归一 + **播种态混种修复** ③ `GameRandom` 摘除）** → **阶段 2 ✅（可归表现类者全迁）**：外交文案 / 天劫立绘 / `SectResponseTexts` / `LoadingTips` / `CloudLayerAnimator` 已迁；**`BattleDescriptionGenerator` 12 + `DiscipleChatDialog` 3 判为决策类**（文本入 `battle_logs` 实体 / 写弟子 skills+cultivation）⇒ 归阶段 3 → **阶段 3（决策类按域分批下沉，未开工；10k JNI 基准 ratio 0.8 已排除成本障碍）** → **阶段 4 ✅**（R2/R4 断言 + **CI 红线 step** + **10k JNI 基准**） |
| **当前状态（接手必读）** | 阶段 0/1/2/4 全部交付；**原记的失败项均已清偿**（`DiffYearSettlementTest` 见 §2.59.1——夹具快照 9 号键垃圾值；`:feature:game` 两族见 §2.59.2——可见性对齐 + 相机契约回归）。**现门禁**：引擎全量 **3281 用例 / 0 失败 / 0 跳过**（§3 基线表）、`:core:domain` 1758/0、`:core:data` 707/0、`:core:ui` 146/0、桌面 C++ 1322/1322、六模块 detekt 全绿。两道守卫（`DiffAiRngSeedingTest` / `RngEngineIsolationGuardTest`）已把"播种态跨语言等价"与"禁止 object 全局随机上下文"落成可执行断言 |
| **与 batch-21 的关系** | **勘误（2026-09-15，§2.53）**：ADR 阶段 1 只是**任务侧前置**；反向通道真正的关闭前置是"**各域稳态写者归 C++**"，经 288 站点穷尽审计实测**不成立**（14 域无一可整体关闭）⇒ **batch-21 的"直接关闭"路线作废**，改由 [ADR reverse-channel-elimination](adr/reverse-channel-elimination.md) + [parallel-batches-w3](parallel-batches-w3/README.md) 承接（UI 操作面收尾 → 通道删除） |


**五项验收不变量（R1–R5，batch-21 与后续批次引用此节）**

| 编号 | 不变量 |
|---|---|
| **R1** | 任何**影响游戏状态**的随机数必须取自 `GameRngManager.getRng(RngPartition.*)`（禁止 `Random.Default` / `.random()` / `Math.random()` / `GameRandom`） |
| **R2** | 每个分区的状态必须可导出/恢复（现状已成立，补守卫测试锁死） |
| **R3** | 表现类随机（战斗描述文案、外交对话文本等**不影响状态**的）与决策类随机物理隔离，走独立 `PresentationRandom`；该类**不落盘**且不得被决策路径调用 |
| **R4** | 新增影响状态的随机调用点必须注册进分区表（清单式守卫：未注册即测试红并列出待办——与 `SlotCategoryCoverageTest` 同款题型） |
| **R5** | **禁止自建随机源**：不得新增 `object`/单例自持 RNG（`GameRandom` 是反面教材：自称支持确定性存档、`setSeed` 生产零调用）。**守卫必须从源头禁止新建，而非事后审计** |

**开工前必读**：ADR §11 盲区自查 9 条（含"114 处"口径不完整的自我勘误、`@ThreadLocal` 语义依赖、
JNI 成本未实测、`PresentationRandom` 是否需跨会话同构待产品口径）。
> **④ 开工前必读**：本文件 §2.55/§2.56/§2.57 + [ui-read-surface §4.3 前置现状块](ui-read-surface.md)
> + [parallel-batches-w2/non-parallel-work.md §四 排期（第三波）](parallel-batches-w2/non-parallel-work.md)。

---

## 7. 文档事实核查与勘误（2026-09-15）

> 背景：batch-21 审计暴露"文档口径与代码现状漂移"已成系统性风险（ActionId 计数即为例）。
> 本批对 **handover 全篇 + ui-read-surface + w2 批次文档 + cpp-engine / architecture / knowledge-base + 两个 ADR + CLAUDE.md**
> 做了逐条事实核查：**共核查 293 条断言 + 27 项数字/引用抽查，实测 TRUE 157 / FALSE 31 / STALE 82 / UNVERIFIABLE 23**；
> 另发现 **20 组跨文档矛盾**与 **4 处 file:line 漂移**。核查以**代码/产物为唯一裁判**（现码 grep/glob、
> `action_ids.h` 实跑、六模块 baseline、测试结果 XML、C++ GTest 源计数），核查结论即本节 §7.1–§7.4。
>
> **处置原则**：① 影响"下一步派工/验收"的**当前状态类**断言**就地更正**（已完成的更正见各行）；
> ② 历史批次记录**保留原文**并加"勘误/现状更新"标注（不重写历史）；③ 系统性同类问题**落守卫**
> （ActionId 计数改为从 `gen-action-ids.mjs` 实跑取值、文档禁止手抄；RNG 红线由守卫测试而非 grep 承担）。

### 7.1 已就地更正（FALSE / STALE，影响当前判断）

均已就地改写（原断言 → 实测结论）：**① `approveMarriageProposal`「顶部 native 臂」不存在**（`GameEngine.kt:276` 纯 `stateStore.update`；`DISCIPLE_LIFECYCLE_MARRY_APPROVE=1592` Kotlin 零引用 = 死导出）⇒ §2.45 加勘误、域结论改判（`ui-read-surface §4.4`）；**② 动作计数 170/1733 → 171/1734**（漏计 `STORAGE_BAG_OPEN_TX`，随 `85498c4` 入库）；**③ `lockedBeastIds` 增量段已随 batch-21 关闭**（§2.21.2 加现状更新）；**④ `GameRandom`「生产 8 处」→ 6 处死代码 / 真实 4 处**且对象已物理删除（§2.57）；**⑤ `GameSettingsData.autoSave`「删除风险>收益」不成立**（零消费者孤儿模型，§4.2）；**⑥ 反向通道「关闭前置全部达成」→ 14 域无一可整体关闭**（§2.53/§5/§6 + `ui-read-surface §4.3/§4.4`）；**⑦「纹理重构族在途破损 ⇒ lint/NDK/模块回归不可走」证伪**（NDK 35s / lint 12m09s ✅）。

> 另一条 §2.57 的 AI RNG 论据（"键 6 在协议面"）结论不受影响但**理由需换**（实为 9 号镜像分区），见 §7.2 项 1。


### 7.2 待更正的存量口径（不阻塞当前工作，逐条登记）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | §2.57 上文 | AI RNG 归一论据用的是"键 6 在协议面"（**错**，实为 9 号镜像个键） | 改为"影子流摘除 + 经 9 号镜像分区与 C++ 对齐" |
| 2 | §2.51 红线 | `kOpenYears` "单源锚定"**不彻底**：`secret_realm_settlement.h:53` 与 `secret_realm.h:81` 双常量并存，`:199` 仍消费自持常量 | 收敛为单一常量（`secret_realm.h` 为准） |
| 3 | §2.58.2 | "六个扩展函数"实为 **8 个** | 改数字 |
| 4 | §2.53 正文 | 46/82 站点数字记于 `ui-read-surface §4.3` 滚动块（§4.4 列的是域级表） | 指针精确化 |
| 5 | §2.5 / §2.59.3 | 两处 file:line 漂移（`VulkanBackend.cpp:2352 → :2736`；`IslandCliffTextureLoader.kt:165 → :177`） | 改引用 |
| 6 | §2.57 | `rng_manager.h:61` → 实为 `:86`（`exportStates`） | 改引用 |

### 7.3 本次核查新增的**未登记稳态写者**（已并入 ui-read-surface §4.4 与 w3 计划）

| 站点 | 事实 | 影响 |
|---|---|---|
| `GameEngineManualOps.kt:137 replaceManual` | 活 UI（`DiscipleDetailScreen.kt:954`）**无 native 臂**，且 §2.37 未登记 | **弟子管理域"稳态写者归 C++"不成立** ⇒ w3-01 批范围 +1 |
| `GameEngine.kt:276 approveMarriageProposal` | 同上（婚姻审批 native 事务存在但未接线） | w3-02 批"低成本起手项"（接线即可） |
| `GameEngineBattleOps.kt:66 forceSettleDisciplesBeforeBattle` | 宗门战战前结算，§2.51b 未显式登记 | w3-07 批范围明确化 |

### 7.4 核查未能判定（UNVERIFIABLE，需专项或运行时证据）

① `ui-read-surface §3` UI 读取面清单（未重跑审计）；② 反向信封体积的**真机**绝对值（仅单元 harness 实测）；
③ "Kotlin 侧零引用 ActionId 42 个"（未逐条复核）；④ `architecture.md` boot 8 步与 SaveValidator 注册规则 20 条的完整性；
⑤ `DiffAuthoritativeTickTest` 4 字段覆写的"harness 为生产超集"论证（需 harness 对齐生产后重评）；
⑥ **洞府探索"死链 vs 回退臂"两种说法并存**（handover §2.53 判"死链"、`ui-read-surface` 判"月结回退编排内"）⇒ 需一次专项定论。

