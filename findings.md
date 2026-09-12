# Findings & Decisions：C++ 迁移整改

<!-- 审计与总方案的要点沉淀。外部/网络内容只写这里，不写 task_plan.md。 -->

## Requirements
- 按 docs/cpp-migration-implementation-plan.md 执行迁移整改
- C++ 收敛为唯一模拟真相源（决策1选A）
- 游戏全面使用 ECS（决策3，方案X）
- iOS 暂缓，仅文案收敛 + 可移植性护栏（决策4）
- arm64-v8a 单架构 + 移除纹理分包开关（决策7）
- detekt baseline 只许缩小，逐批清偿（决策8）
- 每批下沉必须"先对拍再删Kotlin"

## 审计核心结论（来自报告）
- 定性：**真实但未完成的迁移**（总评 5.9/10）。时间/旬结算/RNG/战斗/宗门地图渲染已真迁 C++ 且 C++ 为真相源；但 Kotlin 保留每旬残留执行器、全部旧实现作回退、UI/持久化/地图数据模型层，且同步通道昂贵、ECS 为展品、iOS 为零。
- 架构评分：C++ Core 7.5 / Renderer 7.5 / Vulkan 8 / Android 7.5 / ECS 3（列式存储计 7）/ Map 6 / 跨平台 3 / iOS 0.5。

## 决定不放过的关键事实（证据链）
1. **ECS 伪完成**：`ecsWorld_` 全 src/ 仅出现一次；`PhaseCoreBatchSystem::run(ecs::World&)` 形参无名被忽略。生产真相是 DiscipleStore 列式存储（数据导向设计，非 ECS）。
2. **行序=RNG 确定性红线**（disciple_store.h:22-27）：稀疏集 dense 迭代序 ≠ DiscipleStore 行序。E1 保序验证绝对前置。
3. **同步通道 O(全状态)**：StateSyncService.kt:376-377 整段重发；dirty_tracker.cpp:63-64 两次全量序列化+深拷贝；GameEngineNativeOps.kt:81 每次库存操作全状态导出+全表替换。
4. **自动存档残留**（原P0-1勘误）：弹窗文案/autoSave 配置键/autoSaveIntervalMonths Room列（两张表）/flushDirtyState+consumeDirty 死代码/注释。旧档兼容 proto 字段139 保留解析-忽略。
5. **死导出群**：NativeBridge.isRendererReady（Kotlin声明C++无导出，调用即崩）/ nativePollEvents+nativeAdvance 桩。
6. **双 ABI**：armeabi-v7a + arm64-v8a；纹理 split 开关形同虚设（仅一种 KTX）。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 方案X（推荐）：DiscipleStore留存储后端，ECS做系统调度与实体关系层 | 保确定性对拍/脏追踪/存档基建；方案Y风险大一个量级 |
| 决策1选A：C++唯一模拟真相源 | 双真相源是过渡态不是终态 |
| 反向通道只能分域关 | 写入者未全部下沉前该域通道必须存活 |
| WS-4 需先补 NPC 玩法设计文档 | 数量/生成规则/与弟子系统关系未定 |
| **P0-3（2026-09-05）：图集上传留主线程，只把拼装搬后台** | C++ `g_renderer` 无锁裸指针，渲染线程每帧 beginFrame/draw/submit 并发进入；P0-3 消灭的是拼装（CPU 密集 + 3×16MB 内存峰值），不是上传 |
| **P0-3（2026-09-05）：ASTC 读写拆成 reader(后台)/uploader(主线程)** | assets IO 可后台；上传触 `g_renderer` 必须与渲染线程互斥；`compressedAtlasLoader` 保留为同步组合以不破坏既有 WP7 测试注入点 |
| **P1-4（2026-09-05）：断言仅 debug 构建，release 擦除为零开销** | 审计明确警告"写错会生产误 abort"；debug 首犯即崩（LOGE 含入口名/tid）足可归因，生产不因守卫把可恢复异常变成崩溃 |
| **P1-4（2026-09-05）：rng 四入口 + setGameConfig 标 kAnyThread 不守卫** | rng 系有真实主线程/存档线程进入（见 Issues）；setGameConfig 的 Dagger 构造期注入线程不保证——按"守卫只放在可验证契约上"的原则处理，避免首帧误 abort |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| zcode 免费活动端点（zai-coding-plan/zai-start-plan）实测 429/401 | 当前 zcode 实际选中的是 `builtin:zai`（Z.ai - API Key），该通道真实可用（GLM-5.3-flash 返回 200，支持工具调用） |
| **RNG 通道跨线程竞争（2026-09-05 P1-4 分类时发现，真实数据竞争）** | 已按 §2.6 方案②收敛（UI 抽取派生化/引擎化 + initSystemSeed 并入重启 + buildSaveSnapshot 引擎采样）；过渡护栏 debug 警告日志，真机零出现后升级 kEngineOnly 断言 |
| **审计"封顶只护软渲路径"系误读（2026-09-05 P0-3 实施时核实）** | `canvasAtlasScale` 本就对 RGBA/软渲全部路径生效（无 4096 位图路径），审计描述与实现不符；已改名 `atlasBitmapScale` 并修正注释 |
| **`View.post` 陷阱（2026-09-05 守卫测试实测暴露）** | 未 attach 的 View 把 runnable 塞进 run queue，仅下次 traversal 才执行（测试/极端时序下永不执行）。跨线程回主线程一律用显式 `Handler(Looper.getMainLooper())` |
| gradlew 在无 JAVA_HOME 的 shell 下报错 | `local.properties` 的 `org.gradle.java.home` 只作用于 daemon，launcher 脚本仍需环境变量：`JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"`（JDK 21） |
| **M0 协议漂移（2026-09-05 M1 首批重建 desktop-jni 时暴露）** | WS-0.a 从 Kotlin 序列化面删 autoSaveIntervalMonths，但 C++ models/json_codec 仍导出——交接验证跑引擎单测未注入 gamecore.jni.path（对拍全 skip）、CI 未跑故未暴露。已双侧删除。**教训：交接验证必须含"重建 desktop-jni + 注入路径跑全量 Diff"** |
| **Kotlin↔C++ 空串/null 归一化（S1 亲属移植核实）** | DiscipleSerializer `ifEmpty{null}` 双向归一——C++ 空串 == Kotlin null，`!empty() && ==` 与 Kotlin `!= null && ==` 逐位等价；镜像后 ComponentTable 不会出现 ""（兄弟姐妹判定无误触发面） |
| **MIN_BAG_ITEMS_TO_KEEP 语义（relative_gift_test 首轮暴露）** | Kotlin 赠送"袋最少保留"按**条目数**（去重后 size ≤ 1 即 BagTooSmall），与 quantity 无关——单条目堆叠×N 也不可赠送；C++ 移植保真，测试场景需 ≥2 条目 |
| **S-14 口径差实测（偷盗对拍场景）** | 月度重置（theftJudgementsThisMonth 归零）在 Kotlin 基准侧位于被 mock 的 processTheftIfNeeded 后面，C++ runMonthSettlement 真实重置——跨月界对拍场景必须规避或注入真实执法处理器；该口径差已登记 S-14（随月变真相源切换批次消除） |
| **对拍 harness RNG 隔离陷阱（偷盗场景首轮失败）** | 真实 LawEnforcementProcessor 必须与基准侧 CultivationService **共用同一 GameRngManager**——测试里另建实例会让 SYSTEM 抽取落进未导出的分区，表现为"Kotlin 零抽取 vs C++ 全链抽取"的假性分歧 |
| **WS-1 反向锚点不变量（2026-09-05）** | `lastGameDataSentJson` 语义 = "锚点 ⊆ C++ 已知值"。推进点仅三处（import/syncFromNative/发送成功），**前向镜像不推进**——保守过期方向：反向 diff 重发 C++ 自身值=无操作，永不漏发；若在锁内做字段级锚点合并则复杂度与风险不成比例 |
| **WS-1 反向补丁数值格式陷阱** | C++ 导出对整数值 double 输出整数形式（12000），kotlinx 输出 12000.0——反向锚点必须从解码快照**重新 kotlinx 编码**（不能直接缓存 C++ JSON 树），否则产生格式性假差异；C++ 补丁侧 `get_to(patched)` 宽松读缺键保持现值，全量信封逐位等价 |
| **WS-1 DiscipleTables 索引口径** | `ComponentTable` 按 id 走 SparseArray indexOfKey O(log n)，但 `_ids` 是普通 List（contains 线性 O(N)）——镜像按 id 批量应用若走 update() 会 O(k·N) 退化；`upsertMirrorRow` 用 isAlive 键存在性探测路由 update/insert，幽灵行（isAlive 缺键）由 insert 的 `id in _ids` 兜底 |
| **WS-1 验收实测（2026-09-05）** | 反向 gameData 段 -98.7%（5349B/134 键 → 70B/1 键）；diffToJson 全脏 -22~40%（基线 JSON 树缓存，两次全量序列化+深拷贝→单次）。**残留**：每旬弟子全脏的镜像成本受全量实体 JSON 序列化支配（协议形状），列级 delta/二进制通道随阶段 3 DOD |

## Resources
- docs/cpp-migration-audit-report.md — 独立审计（2026-09-04，275行）
- docs/cpp-migration-implementation-plan.md — 总方案（2026-09-04，183行）
- 关键源码：disciple_store.h:22-27、phase_settlement.h:1371-1383、StateSyncService.kt:376-377、dirty_tracker.cpp:63-64、GameEngineNativeOps.kt:81、SettingsTab.kt:356、game_config.json:6-7、NativeBridge.kt:215、GameCoreBridge.kt:237/65、build.gradle:60-62/163-167
- **M1 首批新增**：relative_gift.h（亲属赠送移植）、relative_gift_test.cpp（21 用例）、BreakthroughAnalyticsObserver.kt（埋点残留）、phase_settlement.h runPhaseSettlementCore（AUTHORITATIVE 完整七步）
- **M1 第二批新增（WS-1）**：docs/ui-read-surface.md（《UI 读取面清单》）、dirty_tracker_bench_test.cpp、DiscipleTables.upsertMirrorRow；dirty_tracker 基线 JSON 树缓存 + applyReverseDirty 字段级补丁 + StateSyncService 反向 dirty 集锚点

## Visual/Browser Findings
- 无图像/浏览器取证。

## S4 批次发现（2026-09-06，M2 首批）
- **槽位双存储"窗口对齐"模式**：月结管线 settleMonthNative 前 repo 整表写镜像
  （B5 分叉/惰性建槽自愈）、后镜像整表重放 restoreSlots——两处改动替代约 20 个
  槽位写点的逐点双写改造；写回必须绕过 SlotStateMachine（不接受
  WORKING→WORKING 复合变更）。
- **Kotlin data class 默认值 = 快照协议面**：ProductionSlot.outputItemRarity=1/
  completionPhase=1 与 C++ 模型默认 0 的漂移被"C++ 不读写"掩盖至 createIdle
  等价实现首次消费默认构造。移植带默认值构造的模型须逐字段核对。
- **ProductionSlotRepository 读全走内存缓存**：initialize()（getAllSync→缓存）
  在主代码无调用者——测试/工具场景灌入 port 后必须显式 initialize()，
  否则 repo 读恒空（月结结算静默早退、零 RNG 抽取）。
- **自动排班材料口径双轨**（Kotlin 既有）：选配方 name+rarity 精确求和 vs
  start 原子事务 id 聚合——正常数据下后者恒宽松，选配方检查通过则 start
  失败分支不可达；C++ 版只实现前者（行为等价，已登记）。
- **C++ 自动排班置于月结编排末尾**（对齐 Kotlin"事务提交后异步"读月结最终
  状态），并使当月续炼真正生效（Kotlin 原版 launch 排序不定致续炼实际下月
  才发生）——登记为改进基线，黄金用例锁定，对拍场景规避"到期+autoRestart"。

## S5 批次发现（2026-09-06，M2 第二批）
- **applyMissionRewards 稠密 id 守卫缺陷（顺手修复）**：成员循环
  `tid >= tableIds.size` 假定 0-based——DiscipleTables.insert 生成
  max+1（id 从 1 起稠密）时 id==size 的弟子恒被排除（无魂力/状态重置）；
  C++ rowOf 语义正确，Kotlin 回退路径已改 `tid !in tableIds` 对齐。
- **S-19 漏网**：MissionSystem 奖励模板选择（丹/装备/功法）与 EnemyGenerator
  敌人模板选择此前走 kotlin.random.Random.Default（非确定性、读档不可重放）
  ——RngRandomAdapter（nextInt(bound) 重写为 DeterministicRng Lemire 同源，
  非 kotlin 默认 nextBits 取模）接入 MISSION/ENEMY_GEN 分区；C++ 移植按同序。
- **java.util.Random 洗牌复刻**：EnemyGenerator 装备槽
  `list.shuffled(java.util.Random(seed))` 是 LCG（0x5DEECE66D）非 PCG——
  2 的幂特判 + 有符号回绝循环 + Collections.shuffle 降序 Fisher-Yates 逐位
  复刻；黄金值 Random(42).nextInt()==-1170105035 锁定。
- **S4 遗留漂移**：recipe_db finalize 的 BATTLE 丹 minRealm 写死 9（Kotlin 为
  tierMinRealm(tier)）——被"S4 场景仅触发修炼丹产出"掩盖，任务奖励 battle 丹
  首次消费即暴露；镜像"移码不改语义"的模板表须以消费面全覆盖为准。
- **HUMAN 敌人的隐藏抽取**：generateManualsForEnemy 里 `val rarity =
  minRarity + enemyRng.nextInt(...)` 计算后未消费——但该 nextInt 消耗 ENEMY_GEN
  分区状态，移植必须保留（抽取序红线：未使用≠未消耗）。
- **Kotlin 失败臂仍消费任务**：COMBAT_REQUIRED/COMBAT_RANDOM 战败 →
  MissionResult(victory=false) 也进 rewards 收集（空奖励、任务移除、成员 IDLE、
  无幸存者）——C++ 移植不得把战败臂当"保留任务"。
- **ninja 时间戳精度陷阱**：头文件在 1 秒内多次改动时 ninja 可能漏重建
  （桌面 C++ 假绿/假红）——bisect 时须 `touch` 头文件或清理对象目录。

## S8 批次发现（2026-09-06，M2 第三批）
- **AI 修炼速率双口径**：Kotlin AI 调 calculateCultivationPerPhase 对象版
  （无 sectPolicies 入参、grief 传 0）——政策加成与丧亲状态不参与 AI 速率；
  玩家列直读版含 policyCultivationBonus + griefEndYear。移植须按调用方路径
  逐字对齐，不能"共用一个公式"。
- **对象版 manuals 空映射兜底分支**：manualIds 非空但 manuals 空时
  computeCultivationZones 走 ManualDatabase 静态查询（无动态实例属性）——
  AI 修炼速率实际消费静态 cultivationSpeedPercent。
- **包含环断链模式**：phase_settlement.h:23 include month_settlement.h——
  S8 把 ai_sect_ops.h 挂进 month_settlement 后，其依赖的 phase_settlement
  常量（孕养曲线/熟练度）经 pragma once 落空——叶子头（nurture_constants.h）
  承载共用常量断链，双侧包含。
- **热控批量上界 = 平台效应**：ThermalMonitor 读 PowerManager 热状态，
  C++ 批状态机只消费 Kotlin 推送的上界（nativeSetAiThermalBatchSize，
  默认 3=正常档保桌面确定性）——批状态机内存态不入存档协议（S-16 同族）。
- **aiSectBeastDirectTargets 迭代序**：Kotlin Map 插入序（precompute 按妖兽
  id 升序写入、消费期不新增键）≈ C++ std::map 键升序——多目标场景依赖该
  不变量（登记）。

## E2/E3 批次发现（2026-09-06，M2 第四批）
- **E2 迭代域切换零语义差**：syncDiscipleEntities 校验/恢复"View 序 == Store
  行序"后按 View<DiscipleRef> 迭代，与裸行号 0..N 逐位等价——快照后行移除
  （偷盗叛逃）仍由 rowOf 现查兜底（快照建完即弃 View，不跨重建持有）。
- **月结/年结域未迁 View**（E2 残留口径）：month/year_settlement + child_birth +
  disciple_purchase + recruit_settlement 共约 20 处裸行号迭代不在 E2 命名系统
  清单（修炼/HP/MP/装备/丹药/突破/生产），迁移属后续批次。
- **E3 NPC 组件族零协议**：NPC 是首个"纯组件"实体族（无 SoA 权威存储——组件
  即数据本体），纯运行态不入 JSON 协议；渲染经 WS-4 紧凑数组通道
  （collectNpcRenderRows：id,x,y,spriteId,animFrame）。组件插入序即权威
  （无外部行序 → 无需 syncDiscipleEntities 式校验）。

## P1-5 批次发现（2026-09-06，M2 第五批）
- **S4 遗留分歧缺陷（本批顺手清偿）**：C++ 锻造自动排班材料索引为批首快照，
  Kotlin 真实路径（processAutoForgeSlot）为逐槽实时读——同批多槽材料竞争时
  C++ 会以旧余量选配方且 consumeMaterialsForRecipe 无不足守卫 → 少扣材料
  白嫖生产。炼丹/锻造统一改每槽重建索引（与 Kotlin 逐位同语义）。
- **月结 O(N²) 配对的确定性约束**：M×F 配对循环形状 = RNG 消费序（每对通过
  过滤的组合恰一次 SYSTEM nextDouble、male 外层 female 内层），结构级降复杂度
  必然改消费序列 → 必须双端同步改算法（行为基线变化，需拍板）。本批只做
  常系数修复（rng 引用提升 + vector 位图替代 set<string>），25 万对/月的
  每对成本已降为 O(1) 位图判定 + 8 次父 id 串比较。
- **配方排序进程内缓存前提**：pillRecipes()/forgeRecipes() 为函数级 static
  const 向量（进程生命周期），缓存模板指针安全；若未来模板表改为可重载，
  该缓存须随之失效。
- **gradle 任务 UP-TO-DATE 陷阱**：`-Dgamecore.jni.path` 是系统属性非任务输入，
  .so 重换后 testReleaseUnitTest 判 UP-TO-DATE 直接跳过（对拍假绿）——
  引擎验证必须 `--rerun-tasks` 强制重跑。


## WS-5 批次发现（2026-09-08，M2 续批）
- **存档 = Kotlin kotlinx ProtoBuf，C++ exportState 仅运行时镜像**——"入快照"
  类设计先探明序列化归属：地形瓦片入协议 = 存档 +~30KB + 每旬镜像 JSON +~80KB
  持续代价；确定性纯函数再生零成本 → WS-5 落地为"C++ 生成真相源 + Kotlin 按种子
  缓存"，协议/存档零负担。地图"跨版本冻结"（快照固化）是唯一放弃的优势，如需
  须拍板补协议批。
- **Java Float 无 FMA 收缩，C++ 有**——arm64/clang 默认 ffp-contract=on 会把
  `3f - 2f*fx` 收缩为 fnmadd，与 ART 舍入不同位；volatile 局部隔断 + 乘积命名 +
  左结合求和 = 任意平台与 Kotlin 位级一致（跨语言浮点移植的通用手法）。
- **有符号溢出在 C++ 是 UB**——Kotlin Int 乘法回绕语义必须以 uint32 乘实现后
  再 reinterpret；Int64 加法截断 int32 同理（cellHash 移植要点）。
- **JUnit assertEquals(IntArray, IntArray) 是引用比较**——数组断言一律
  assertArrayEquals / contentEquals（首轮全图对拍红为测试 bug，探针全绿已证实现一致）。
- **JNI 系统属性注入 `-D` 单横线**（`--D` = Unknown command-line option）；
  **双 gradle 构建禁止并行**（共享模块 jar 撞车）。
- **smoothNoise 负坐标出界为双端同象**——`toInt()` 截断朝零 → fx<0 → smoothstep
  出 [0,1]；生成域恒非负不触发。值域不变量断言只写生成域。
- **渲染端"产出新引用"是失效契约**——SoftwareCanvasBackend/NativeSurfaceView 以
  引用变化驱动 chunk 失效/拷贝，增量结构原地写入会静默失效该机制（RoadMaskTracker/
  applyBuildingOccupancy 均在变化时产出副本，未变返回稳定引用——两方向都要保）。
- **静态嵌套类读不到外类实例字段**——SoftwareCanvasBackend.ChunkTile 的 chunk 几何
  经 ChunkDrawKit 参数下发（Kotlin 嵌套类 ≠ inner class）。

## M3 首批发现（2026-09-08，死代码族清偿）
- **detekt baseline 按"规则+文件+声明签名文本"匹配**（非按条数）——一个 `Rule:File.kt$签名`
  条目压制该文件该规则全部违规；**签名文本变化即条目失效**（删一个构造参数会让同文件
  LongParameterList 旧条目失配、新签名未覆盖而裸奔）。裁剪条目前必须重跑 detekt 验证。
- **批量删"全文件唯一出现行"必须校验该行是完整声明**——多行声明（`= setOf(`、
  自定义 getter `private val x: T\n get()`、多行构造参数）只删首行产生悬挂续行
  （本轮 3 处，编译期全数暴露）。
- **Git Bash sed 对 CRLF 文件的 `$` 锚不匹配**——`/pattern$/d` 静默不删；perl
  `\r?\n` 模式或无 `$` 锚兜底。本轮 aiSectName 等多处首次删除实际未生效。
- **`for (_ in x)` 是 Kotlin 实验特性**（UnnamedLocalVariables，需 `-XXLanguage:+`，
  Android 编译器默认报错）——未用循环变量用 `repeat(n) {}` 化；但 **repeat 是 lambda，
  体内 break/continue 非法**（含 break 的循环改 while）。
- **sed 替换串中的 `&` 是"整个匹配"**——往 baseline 插入含 `&lt;` 的 detekt ID 时被
  展开污染；perl s{}{} 插值无此问题。
- **死变量 ≠ 可删行**：`val x = expr ?: return false`（elvis 早退）、`val r = wallet.batch(...)`
  （副作用调用）、benchmark 计时段内的反序列化调用、`sanitizeRecruitList(state)`（就地
  净化）——绑定可去、表达式必须保留为裸语句或 if 判空。
- **detekt 活债务实测（2026-09-08）**：全仓 3129→（死代码族清偿后）2952；最大族
  MaxLineLength 1400+、TooGenericExceptionCaught ~340、ReturnCount 129、
  CyclomaticComplexMethod 88、EmptyFunctionBlock 107（engine 为主，接口 no-op 占位需
  逐个判定）。baseline 清零按族逐批推进，勿一次性重写。

## M3 第二批发现（2026-09-08，反向通道审计 + lockedBeastIds 加固 + 搬移族）

- **反向通道"按域全关"的前置不成立（审计改判）**：S4-S8/WS-5 下沉的是结算/事务核心；
  全仓生产 `stateStore.update`（参与反向捕获）穷尽审计=约 265 处、15+ 域——弟子管理
  （最大残余域）/巡逻/建筑放置（全无 C++ 通道）/外交/设置/任务/邮件附件/月年编排等
  仍 Kotlin 直改。"玩家操作 Kotlin 产生 → tick ⑤ 反向回导"是 2026-08-31 根因修复后
  的现行设计契约。**在任何域下沉前执行"停捕获"= 该域 Kotlin 写入永达 C++ 的数据丢失
  缺陷**。域→写者→批次清单见 ui-read-surface §4.1。
- **@Transient 顶层段的增量回导缺口模式**：不入 kotlinx gameData JSON 的字段
  （aiSectDisciples/lockedBeastIds/aiSectBeastDirectTargets/aiSectBeastSkipCooldowns）
  反向信封必须单独成段（S-15 变化检测模式），否则 Kotlin 写入只能靠全量回导兜底可达，
  AUTHORITATIVE 稳态下功能静默失效。lockedBeastIds 已补段（月结锁定妖兽跳过判定恢复）；
  后两段 Kotlin 写者仅在月结回退路径、AUTHORITATIVE 稳态 C++ 独占，回退→AUTHORITATIVE
  切换经 ensureAuthoritativeNative 全量导入可达——**判"无缺口、不加段"也要落档依据**。
- **反向信封顶层段前向兼容**：C++ applyReverseDirty 对未知集合名宽松忽略——新段
  （lockedBeastIds）对旧 C++ 为 no-op，无双端部署顺序约束。
- **InvalidPackageDeclaration 搬移批的三个耦合面**（纯文件搬移 ≠ 零风险）：
  ①源路径清单型守卫测试（SlotCategoryCoverageTest/CheckpointCallSiteGuardTest 硬编码
  `com/xianxia/sect/core/...` 相对路径）需同步；
  ②GameSystemRegistryCoverageTest 扫描根=core/engine 目录——搬移把原本在扫描盲区的
  30 个 @GameService 类纳入，暴露**注册类别长期漂移**（registry"service/domain" vs
  包路径"engine.service/engine.domain"）；category=包路径归属是注册表自定义语义，
  修数据不修守卫；
  ③detekt baseline 条目按文件名匹配，搬移不影响其他规则条目。
- **desktop C++ 测试双构建目录**：`gamecore/build/`（895 例旧套件）与
  `gamecore/build/desktop-test/`（912 例近期套件）并存——验收以后者为准；ctest 运行
  需 llvm-mingw bin 在 PATH（否则测试发现步骤 0xc0000135 DLL 缺失失败，且失败的
  发现步骤会让新增用例不进 ctest 清单）。
- **后台命令 `| tail` 吞退出码**：gradle BUILD FAILED 经管道后 exit=0——验收判定必须
  读日志尾行或 `${PIPESTATUS[0]}`，不能信管道退出码。

## M3 第三批发现（2026-09-08，MaxLineLength 机械族清偿）
- **detekt LongMethod 计数口径 = linesOfCode（PSI token 行）**：注释行、空行**不计**；
  断行一条语句必然 +1 SLOC。机械换行批给 58~60 行临界函数 +1~3 行即越界，且
  删注释/空行无济于事——只能真实减行（同构提取/参数行紧凑化/多语句行重写）。
  字节码证据：LongMethod 调 io.github.detekt.metrics.linesOfCode（tokenSequence
  跳过 PsiComment 子树，distinct 行计数）。
- **detekt baseline 签名漂移的连锁面**：条目按“规则+文件+签名文本”匹配——本批改行文本
  会让**其他规则**的既有条目失配复活（本批 4 处：MayBeConst / EmptyElseBlock ×2 /
  ExplicitItLambdaParameter）。换行/格式化批次的 detekt 验证必须**六模块全量重跑**，
  不能只看目标规则。
- **TooManyFunctions 计数双层**：文件级=顶层函数数（阈值 15）、类级=类函数数（阈值 20）。
  类已 30 函数被 baseline 压制时，新增成员函数=新签名复活；解法=文件级私有顶层函数
  （该文件顶层计数 0）。反向（文件已压制、类有余量）同理。
- **Kotlin 字符串等值拆分安全点**："A" + "B" 任意合法拆点内容不变；模板串只在
  ${}/$ident 之外的明文区拆；$ 前后禁拆（孤立 $ 字面量兼容性风险）；转义序列
  （含 \uXXXX）整体不拆；两侧片段非空（禁产出 "" + 空串链）。模板串内换行
  语法合法但绝不可用——字符串内容即变。
- **换行器自身缺陷三连（脚本批的元风险）**：①排序反选（reverse=True 把优先级取反）；
  ②EOF 多余空行（split 末尾空元素 + join 后再加 eol）；③拆分器闭引号 off-by-one
  丢引号 + 递归缩进膨胀→预算耗尽→空串链——三处损坏全部先落盘后被编译/审查逮住。
  **配套校验器也曾假绿**：git pathspec 相对路径错误（CWD=android/ 而路径按仓库根写）
  导致全量跳过、0 mismatch 虚报。教训：批量改码批次必须有“内容级”第二道校验
  （本次=字符串 token 序列 diff + 编译 + 全量测试），且校验器先自证覆盖面。
- **Kotlin ASI 断行三陷阱**（行尾平衡表达式后换行）：接 ( → 粘连为调用
  （val a = b\n(c) 解析为 b(c)）；接 { → trailing lambda 粘连；接标识符 → infix 函数
  断裂（0 until n）。安全判定：括号深度>0 / 头部续行 token 结尾 / 尾部可续行 token
  （. ?. :: 二元运算符、else、is、as、闭括号）开头，三选一。
- **RoomMigrationV4x 预存失败登记**：8 例（V43To46×5 / V46To47 / V47To48 / V48To49）
  “44→50 路径缺失”——WS-0.a 提 V50 未补测试注册链；HEAD stash 复验确认非本批引入；
  修法=各测试 addMigrations 补 MIGRATION_49_50（专项小批）。

## M3 第四批发现（2026-09-08，RoomMigration + TooGenericExceptionCaught 族清偿）
- **detekt 同目标多 @Suppress 注解只生效其一**：函数已有 `@Suppress("ReturnCount",...)` 时
  再叠加 `@Suppress("TooGenericExceptionCaught")` → 既有压制失效、违规复活（本批 80 条）。
  新增抑制必须与既有 Suppress **合并为单注解多参数**，理由注释放注解上方行。
- **detekt baseline 条目签名文本含函数注解**：给函数插 @Suppress 会使该函数在 baseline 的
  **全部规则条目**失配（§2.22"行文本漂移"的注解版，连锁面更广）。插注解批次的 detekt
  验证必须六模块全量重跑。
- **detektBaseline 生成物 = 全量当前违规（含被 baseline 压制面）**：直接装回会偷偷扩大
  压制面（本次实测五模块 888 条 vs 摘除后 1146 条——生成物顺带清除了 273 条历史签名漂移
  死条目，但纪律上必须先摘目标族→实跑裁决→处置→再生成，不能拿生成物跳过处置）。
- **baseline 摘除后实跑违规数远大于条目数**：TooGeneric 135 条条目摘除 → 474 处违规
  （同签名多 catch 一条压制）——"条目数≈债务数"是错觉，摘族批次的处置工作量要按
  实跑裁决预估。
- **批量插注解的"向上找 fun"两陷阱**：①泛型函数 `fun <T>` 正则漏配；②嵌套 lambda/
  匿名对象内函数截胡（内层 `override fun loadLibrary` 物理位置比外层所属函数更近）——
  归属判定必须括号平衡（本次 10 处误插回滚后按真实归属补插）。
- **无函数归属的 catch**：属性初始化器/自定义 getter（属性级注解）、init 块（表达式级
  `@Suppress(...)` + try）、顶层泛型函数（注解放 KDoc 后）三形态。
- **gradle 测试 -D 属性注入相对路径 → JNI UnsatisfiedLinkError 假失败**（243 例全红）：
  测试 JVM 工作目录非仓库根，`-Dgamecore.jni.path` 必须绝对路径。
- **RoomMigrationV43To46Test 种子派生 replace 锚点**：`SEED_DISCIPLES_V44` 依赖
  `SEED_DISCIPLES_V43.replace("
        0
    )", ...)`——§2.22 空格规范化改写种子
  raw string 后 trimIndent 顶格形状使锚失配（职业 4 值未补，"101 values for 105 columns"）。
  派生种子的 replace 锚不要依赖缩进；失配症状会被"迁移链缺失"失败掩盖（INSERT 前先炸）。

## M3 第五批发现（2026-09-08）

1. **PSI 尾分号空 else**：`if (cond) stmt;`（单行密排 + 分号续语句）被 Kotlin PSI 解析出空
   else 分支——detekt EmptyElseBlock 由此触发。密排单行风格改标准多行即根修
   （ComponentTable.put ×2）。
2. **Kotlin try 语法强制接 catch/finally**：`try {}` 单独出现 = "Expecting 'catch' or
   'finally'" 编译错。删 no-op catch（catch-only-cancellation）不可行——要么整体拆 try 包裹，
   要么函数级 @Suppress（本批取后者，SectMapTouchEngine ×6）。
3. **全局改名必须跑 compileReleaseUnitTestKotlin**：`_discipleTables→discipleTables` 全局
   改名撞 GameStateStore 接口自带 `val discipleTables`，且 fake 的接口覆写 getter 变成
   自引用（`get() = discipleTables` 无限递归 StackOverflow）——compileReleaseKotlin 不编译
   测试源，事故被掩盖到全量测试编译才暴露。改名批次验证门 = 主源+测试源编译 + 相关测试类。
4. **detekt 规则集归属**：ReturnCount 在 `style` 规则集——放 `complexity` 下报
   "invalid config property"（且 --rerun-tasks 下 5 任务全灭）。配阈值前先查规则集。
5. **批量 git mv 前置守卫核对**：GameSystemRegistryCoverageTest 用 `file.nameWithoutExtension`
   当类名扫描 `@GameService` 文件——重命名含该注解的文件会改守卫语义。本批 25 个
   MatchingDeclarationName 目标文件均不含该注解（前置核对通过后移动）。
6. **detekt 同声明重复 @Suppress = 编译错 "This annotation is not repeatable"**——比 §2.23
   记录的"只生效其一"更强（彼场景疑为跨层级目标）。批量插抑制必须与既有 @Suppress 合并，
   且插入位置不得隔断 `@Composable` 等其他注解与既有 @Suppress 的相邻关系。
7. **detekt 行号随编辑漂移**：批量脚本以"报告行号"定位时，同批前序插入会使后续定位错行——
   脚本必须幂等（HAS/MERGE 分支）+ 行号偏移补偿 + 最终以全量 detekt 重跑为唯一裁决。
   另：detekt txt 报告 `X/Y` 字段给出实测值/阈值，是阈值族（NestedBlockDepth/ComplexCondition/
   LongParameterList）分布分析的直接数据源。
8. **并行 Gradle 测试互相踩**：同工程并行发起多个 testReleaseUnitTest 会出现"multiple
   Kotlin daemon sessions"与增量编译状态错乱（Cannot access class 假错）——全量套件必须串行。

## M3 第六批发现（2026-09-08）

1. **K1 编译器：仅 finally 的 try 不能作块体隐式返回表达式**——`fun f(): T { try { x } finally {} }`
   报 Missing return statement（即使 try 块尾是表达式）；须写 `return try { x } finally {}`。
   与 §2.24 机制发现②（try 必须接 catch/finally）同族的语法面坑。
2. **detekt NestedBlockDepth 深度计数包含赋值右侧的 if 表达式**——`data[y][x] = if (c) a else b`
   在 for-for-if 链内即计 5 层。处置：RHS if 提取为表达式体私有函数。
3. **detekt TooManyFunctions 在函数数 == 阈值即报**（object 12/12、class 20/20 均报），
   非"超过"。拆分批次新增助手时：纯函数优先落**文件级私有**（thresholdInFiles=15 且
   各文件基数低），宿主类/object 计数不动。
4. **宿主函数上的 @Suppress 不随代码拆分自动迁移**——把带抑制的 catch 段提取为助手后，
   助手自身的 TooGenericExceptionCaught 等会以"新违规"身份暴露。拆分批次摘族后必须
   **全规则看 detekt 报告**（不能只验目标族），本批首跑即暴露 10 处次生违规。
5. **预存缺陷（已修）**：`DiscipleDeadStatusRule` 修复消息用 `listOfNotNull(hasWeapon to "...")`
   ——Pair 永不为 null，四个槽位值（含空串）全进文案；意图为只列已装备槽。已按意图修复
   （`if (isNotEmpty) "..." else null`），装备引用清空行为不变，无测试依赖旧文案。
6. **预存死代码（已收敛）**：`StorageEngine.buildSaveDataFromDatabase` 声明返回非空
   `SaveData`——`loadFromDatabaseInternal` 两分支 `if (saveData != null)` 恒真、
   尾部 `return saveData` 不可达。重构（buildAndMigrateSaveData 共享化）按实际可空性
   收敛；顺带保留"日志打印迁移前 DB 行字段"的原语义（logValidationFailure 传 source）。
7. **摘族批的实跑放大系数可为 1.0**——判定/边界族（CC/NBD/RC）条目数=违规数
   （78 条=78 处），与机械/异常族"同签名多违规放大"（§2.23 474/135、§2.24 1050/873）
   形态不同；族别处置工作量可直接按条目数估算。

## M3 第七批（2026-09-09）机制发现

1. **detekt 解构声明条目上限为 3**（DestructuringDeclarationWithTooManyEntries 默认）——
   多字段参数对象在函数体内解包须写显式 val（每字段一行），不能用 `val (a, b, c, d) = obj`。
2. **detektBaseline 全量重建顺带清除陈旧死条目**（本批 -17 条 CCM：历史批次签名漂移后
   永不匹配的残留）——guard 计数因此低于"摘族理论值"，属诚实清偿非超缩；摘族批收尾
   统一"重建 baseline"优于"手工对齐残留条目"。
3. **跨会话并行改仓三坑**：①并行会话覆盖写入同一文件会静默回退本批编辑（MainGameScreen
   两处调用点）——逐文件编辑后以编译/定向 diff 复核；②并行批的未完成符号可卡死共享模块
   编译（:app 的 GameActivity RenderDebugSwitches）——验证面按模块拆分，受阻项如实登记；
   ③提交必须按本批触碰文件清单暂存，禁止整仓 `git add`（否则混入 433 个并行改动文件）。
4. **ComponentTable.contains 非 operator**——`id !in table` 编译不过，守卫合并保持
   `!table.contains(id)` 显式写法（EntityStore/DiscipleTables 等列式容器的既有 API 形状）。
5. **预存 write-only 残留（已登记待拍板）**：GameStateRepository dirty 记账机制
   （markDirty/markAllDirty/clearDirty/DirtySet）在 flushDirtyState 死链删除后无消费者——
   WS-0.a 自动存档删除的记账残留；markAllDirty 在 loadFromSnapshot 失败回滚路径有行为依赖
   （StateRevertRegressionTest 注入失败触发回滚），整体摘除须重构 load 回滚语义，专项批处理。
6. **AISectBeastAttackProcessor.collectQualifiedAiForBeast 的 `qualified.size >= 2 break`
   为防御性死分支**（aiCandidates 已 take(2)）——保留防御，登记。
7. **K2 编译器不跨 boolean val 传播解构**——参数对象解包用显式 val 顺带规避
   DestructuringDeclaration 规则；命名谓词 val（`val isEligible = a && b && c`）不受
   ComplexCondition 约束（非条件上下文），是 4+ 操作数守卫合并的标准落位。


## M3 第八批（2026-09-09）机制发现

1. **detekt CCM 计数模型实测**：if/when 入口/&&/||/elvis 各 +1、lambda 体不单独计（计入
   宿主）；`table.getOrDefault(id, default)` 与 `table.getOrNull(id) ?: default` 行为
   逐位一致但前者不产生 elvis 复杂度——组件表默认值读取的"零复杂度"标准写法。
2. **windows 并行会话共享 Gradle daemon 的 classes.jar 文件锁可持续数分钟**——重试循环
   以"rm 试删锁目标"探测锁释放优于固定 sleep；残留 KotlinCompileDaemon（gradlew --stop
   不杀）须按命令行匹配补杀；受损 intermediates 直接删目录让 gradle 重建。
3. **python 切片编辑 CRLF 文件产生混合行尾**——必须 `newline=''` 读写 + 先探测 `\r\n`；
   本批 EquipmentDedupeRule 中招后统一规范化为 LF（git autocrlf 下提交均为 LF）。
4. **K1 智能转换在 lambda 早退后对成员扩展返回值不稳定**——`val ids = ext(…)` 后
   `if (ids is Invalid) return@label` 再取 `ids.did` 可能报 unresolved；改 when-subject
   （`when (val ids = …) { is Valid -> … }`）可靠。
5. **并行会话批中活体改仓**（§2.26 第三坑的进行时形态）：本批期间并行会话接线了
   SaveLoadViewModel 五主流程（批初 5 处 UnusedPrivateMember 批末自愈）、新引入
   NativeSurfaceView TMF(21/20) 与 SoftwareCanvasBackendTest 占地框颜色断言失败——
   均非本批触碰面，验收按"触碰文件零新增 + 集合差分归属"裁决并登记。
6. **判定族 CC 摘族可直接派生子代理并行**——文件零交集时 4 路并行（engine 三组 +
   game 一组），主线只做重叠文件（GameViewModel）与跨模块收尾；每代理禁跑 gradle/git，
   由主线统一编译+detekt 收敛。

## M3 第九批（2026-09-09）机制发现

1. **Kotlin 顶层声明块切分必须原始列锚定**——对 `strip()` 后的行做 `^fun/^val` 匹配会把
   函数体内的缩进 `val` 误判为块边界：移动函数被拦腰截断、尾段落入原文件（detekt TMF
   计数"恰好对上"掩盖了损坏）。根修 = 原始列锚定 + 块终点取下一块回溯起点 + 花括号配平
   校验 + **移动函数与源逐字节 diff**（本批 73/74 逐位一致，1 处为单行表达式体校验器
   盲区人工复核）。
2. **import 剪枝必须保留通配导入**——Compose 文件的 `import androidx.compose.runtime.*`
   在"simple name 出现在 body"剪枝规则下永不匹配（`*` 无词边界），删除即大片 Unresolved。
   detekt UnusedImports 不检查通配导入，保留无成本。
3. **detekt UnusedImports 对同包显式导入也报未用**——顶层函数下放文件级后，同包测试对
   其显式 import 是冗余的（解析不需要）；测试调用点去限定符时同步删 import。
4. **baseline 全量重建（detektBaseline）会捕获并行线批中活违规**——本批 4 条
   （FileLength GameEngineCore 2005 / TGC AISectAttackManager:379 / UnusedParameter
   YearSettlementResidualExecutor:70 / Loop DiffAuthoritativeTickTest:592）。
   13.2 禁止装回：从生成物剔除、归属登记、留作活违规。§2.23"重建=偷偷扩大压制面"教训
   的逆操作面：**重建物必须按条目白名单（本批残留族 + 既有 LargeClass）过滤后装回**。
5. **文件级拆分的跨文件引用收敛路径**：private 顶层声明（函数/常量表）被跨文件消费时
   ——常量表随主消费方迁移、函数 private→internal（模块内可见性，行为零变更，编译驱动
   迭代收敛）；`internal fun` 暴露 private-in-file 参数/返回类型需连带提升该类型。
6. **同包顶层扩展函数移动 = 调用点零改动**——GameEngine*Ops 域拆分全部同包（com.xianxia
   .sect.core.engine）移动，调用侧 import 通配/显式均不感知文件归属；跨包（FavorDomain
   计算函数族）则需逐调用点去限定符 + 加 import。
7. **MatchingDeclarationName 根修 = 同名单文件**——文件内唯一类/枚举声明与文件名不一致
   即报；BattleLogTab 枚举落位 `BattleLogTab.kt` 一举根修（比 internal 化/改名零争议）。

## 并行批次（01–10）与集成收口（2026-09-10/11）发现

> 来源：`docs/parallel-batches/*` 各批 PR 候选 + `docs/cpp-migration-handover-m0.md` §2.30–§2.40。
> 由集成收口人（§2.40）统一合并；各批原始记录见其分支提交说明。

1. **【最高优先·交付纪律】跨批共享文件必须整组提交，批次分支必须在收口时合并验证**——
   `a45692b`（batch-01 续修）为"可编译最小集"顺手带走了组 C 在途 Kotlin 改动
   （`execute_dispatch.cpp` 的 `handleRoadTx`/`handleDiplomacyTx`、`ActionIds.kt`、
   `gen-action-ids.mjs`、三个 Service 门面），**但未纳入被它们引用的 C++ 产物**
   （`road_tx.h`/`diplomacy_tx.h` + 两个 GTest）→ 该提交的 C++ 树断裂：全仓 212 处
   `#include` 扫描仅此 2 处缺失、`test/CMakeLists.txt` 另引用 2 个不存在的测试源，
   **干净检出无法编译 NDK native 与桌面 GTest**（本机可跑只因文件以未跟踪状态在工作区）。
   教训：`gen-action-ids.mjs`/`action_ids.h`/`ActionIds.kt`/`execute_dispatch.cpp`/
   `test/CMakeLists.txt` 是**一个原子变更集**；跨分支交付必须有"集成分支 + 合并后全量门禁"这一步；
   收口后立即清理分支——**非祖先提交（内容已并入但提交链不在主支）必须先打 `archive/*` tag 再删**
   （本次 `batch/05·06·09` 即如此，其余批与集成分支因提交已在 main 历史内直接删除）。
2. **【确定性红线·真 UB】循环内整体替换 `GameData` 会让绑定其容器的迭代器/引用失效**——
   `SpiritStoneWallet::add/deduct` 内部执行 `gd = withSpiritStoneCount(gd, …)`（GameData
   copy-assign，释放并重建内部容器缓冲）。`executeAutoBuy` 的
   `for (const auto& entry : gd.autoBuyList)` 因此读已释放内存，表现为**同进程同输入随机
   只处理第一条**（GTest 间歇失败；曾被 §2.18 误判为"并发偶发"）。修复 = 快照迭代
   （Kotlin 不可变 List 语义）。**安全判据（举一反三扫描结论）**：①迭代容器取自 `gd`/
   `state.gameData` 且循环体内触达钱包/经济写入 → 必须快照；②引用绑定的是 `gd` 的 POD
   成员（如 `sectPolicies`）→ 安全；③钱包调用在循环外（先聚合后一次入账）→ 安全；
   ④循环体每轮重新读 `gd`（不持有跨调用指针）→ 安全。
3. **影子（对拍基准）通道必须与生产实现同源维护**——`system/breakthrough.h` 的
   `isDiscipleFullHpMp`（恒 true）与 `applyBreakthroughFailure`（不折损 HP/MP）相对 Kotlin
   语义降级，生产走 `phase_settlement.h` 完整版故无线上影响，但该 ActionId（1107）一旦接线
   即偏差。影子头文件应有"与生产同式"的强制注释 + 行为用例（本次补齐 6 例）。
4. **detekt baseline 全量重建/装回的连锁面**（各拆分批共同教训）：baseline 按
   **规则+文件+声明签名文本**匹配——签名/注解文本一变即条目失配复活；同目标多 `@Suppress`
   只生效其一（且重复注解为编译错）；`if (cond) stmt;` 尾分号在 PSI 产生空 else；
   TMF 在函数数**等于**阈值即报（对象阈值 12 / 类 20 / 文件 15）。
5. **批量代码搬移的三条硬纪律**：①顶层声明切分必须**原始列锚定**（strip 行匹配会把函数体
   内缩进 `val` 误判为块边界，§2.28 batch-01 因此产生 13 处畸形签名 + 5 函数丢失 + 101 处
   KDoc 错挂）；②搬移后必须跑 `compileReleaseUnitTestKotlin`（主源编译不查测试源）；③成员
   下放为同包顶层扩展会让 Mockito/MockK stub 失效（被 mock 入口必须留守类内）。
6. **多批并行共享工作树时**：`git status` 快照不可信、暂存区不可视为自有（并行会话曾交叉
   `git add`）——提交一律 `git commit -- <pathspec>` 限定；验证以**隔离 git worktree 干净检出**
   为准（需补 gitignore 本地文件：version.properties/local.properties/keystore.properties/
   api.properties + node_modules junction + 图集产物）。
7. **JNI 对拍注入模板**：`-Dgamecore.jni.path` 必须是**完整文件路径**（`System.load(File(path))`，
   传目录即 UnsatisfiedLinkError 假失败）；`-D` 单横线（`--D` 被 Gradle 判未知选项）；
   系统属性非任务输入，必须 `--rerun-tasks` 否则判 UP-TO-DATE 假绿。
8. **C++ include 顺序依赖**：`diplomacy_tx.h` 传递引入 `month_settlement.h` 的 `using` 声明会
   改变后续头文件的非限定名解析——新增事务头必须置于 `execute_dispatch.cpp` 包含块末尾并注释
   （batch-09 登记，集成时保持）。
9. **原子写指针纪律**（batch-08）：实例表 `erase`/堆叠整摞扣减后禁用旧指针（先拷贝所需字段、
   写段重查），否则悬垂。
10. **配置/版本一致性**（集成期核查项）：`docs/cpp-engine.md` 动作计数表自 S8 起未随批更新
    （46→108 动作 / 7→19 handler）；`version.properties` 已升 4.01.14 而游戏内更新日志最新
    仍是 4.01.13（版本条目错位）；`CLAUDE.md`/`architecture.md`/`knowledge-base.md` 的
    "迁移已收口"表述与实际剩余主轴（反向通道逐域关闭）冲突——**功能批次收口必须同批核对
    文档计数表、双更新日志与"完成度"表述**。
11. **【环境·高危】PowerShell `Remove-Item -Recurse` 会穿透目录联接（junction）删除目标内容**——
    清理游离 worktree（其 `node_modules` 为指回主仓的 junction）时，`Remove-Item -Recurse -Force`
    沿联接删掉了**主仓真实的 `node_modules` 内容**（`cmd /c rmdir /s /q` 只删联接本身，安全）。
    症状：`:core:engine:*` 因 `generateSpriteAtlasDef → build-atlas.mjs` 报
    `ERR_MODULE_NOT_FOUND: sharp` 而整链失败（`sharp` 在 `android/scripts/node_modules`，
    `android/scripts/package.json` 声明）。恢复：`npm install --prefix android/scripts` +
    根目录 `npm install`。**防复发**：删目录前先探测 reparse point（`Get-Item -Force` 的
    `LinkType`/`Target`），联接/符号链接一律用 `cmd rmdir` 或先删联接本身。
12. **游戏内更新日志历史数据异常（预存，未改历史）**：`changelog_entries.json` 存在 9 条
    `4.0.66` 重复条目、`4.0.76`/`4.0.65` 各 2 条，以及 4 条缺 `version` 字段的旧条目
    （2026-07 期间）——现规则禁止同版本多条目；历史条目按"不重写已发布内容"原则保留，后续如需
    整理需用户拍板。
13. **`stateSyncServiceRef` 非空声明 + 测试 mock = 调用点 NPE**（W2-a）：`GameEngineCore` 的
    `stateSyncServiceRef` 声明为非空，但 Mockito mock 默认返回 null；Kotlin 在该调用点的内在
    非空检查直接抛 NPE（早于 `tryExecuteNative` 内部的 `isLoaded` 降级早退）。**新 native 协作类
    必须照搬护栏**：先赋给可空局部（`val sync: StateSyncService? = …`）判空后再传递
    （InventoryNativeForward 同款；batch-06 因用 `mockSmart` 未暴露）。
14. **`GameData` 默认值即"开局值"**（W2-a）：`spiritStones = 1000`（非 0）——凡是断言灵石**绝对值**
    的黄金用例必须显式清零，否则"守卫臂零写入"断言会与开局余额混淆（本批首跑 7 例失败即此）。
    同类：`midGradeSpiritStones`/`highGradeSpiritStones` 默认 0。
15. **仓库匹配键是"名称 + 品阶"（+丹药 `grade` 显示名），不是 id**（W2-a）：`MerchantItem.rarity`
    缺失会让 `warehouseCount` 恒 0（实现无缺陷，夹具缺陷）——商人收购/上架测试夹具必须与仓库堆叠
    的 rarity/grade 对齐。
16. **取价口径存在"同实体两条路径不同"的既有语义**（W2-a）：`ManualStack.basePrice` 走品阶基准价
    （不查模板），而**上架**路径走 `ManualDatabase.getByName(name)?.price`（查模板）；装备两条路径
    都查模板。下沉时不可"统一口径"，否则与 Kotlin 回退臂产生金额分歧。
17. **`FakeAtomicStateStore.materials` 是持久化 EntityStore，不经 StateFlow 回灌**（W2-a）：
    `newMutable()` 用 `materials = persistentMaterials`（而非 `EntityStore(materials.value)`，其余
    九类物品是从 flow 值初始化）。因此测试里 `store.materials.value = listOf(...)` **不会**进入
    `stateStore.update{}` 的事务缓冲——必须 `store.update { materials.replaceAll(...) }` 播种
    （首跑 1 例失败即此）。同类：若后续为 materials 加 flow 回灌，须同步核对全部材料类用例。

## handover §2 归集：坑与教训（2026-09-14 从交接文档压缩中抽出）

> 来源：`docs/cpp-migration-handover-m0.md` §2 各小节正文（压缩前）。§2.5–§2.40 的坑已在
> 本文件前面各批次章节落档，本节只收 W2 波次（§2.41–§2.57）与前述章节未覆盖的条目。

- **非分区随机域（`kotlin.random.Random.Default` / `UUID.randomUUID`）不可逐位复刻，是"能否下沉"的红线判据**（来源：handover §2.42/§2.44/§2.47/§2.50/§2.51b，batch-11/13/16/19/20b）：开袋（EXPLORATION 分区 nextInt(16)/nextInt(7) + 模板 `templates.random()`）、世界关卡奖励生成、洞府三库、兑换码物品生成器（C++ 无 `EquipmentDatabase.generateRandom` 对应物）、战利品生成族都消费非分区随机源，其状态不入协议、不随存档走 → 按路线 B 保留 Kotlin 并**诚实登记**（**禁止近似复刻**），或先拍板"改双端游戏分区抽取 + Diff 对拍锁新基线"（路线 A）另立批。
- **参数化的随机源被漏传 → 实际消费全局 `Random.Default`**（来源：handover §2.51b，batch-20b）：`templates.random(random)` 六个调用点（AISectTeamComposer.kt:143/158/171/182/194/208）均未传 `random`，实际序列是 `kotlin.random.Random`；而 `sectBattleRewardCount` 的 BATTLE 分区 `nextInt(7)` 夹在该非分区域中间，拆出即"半吊子混合态"。判定"是否分区抽取"必须看**实参**，不能信形参名。
- **`GridBuildingData` C++ 侧缺 `sectId` → 反向信封该键被静默忽略（镜像不完整）**（来源：handover §2.43，batch-12）：Kotlin `@ProtoNumber(8) sectId` 存在且 `validateAndFixSpiritMineData` 以 `building.sectId` 对齐矿场槽位，C++ 模型无该字段 ⇒ `applyReverseDirty` 宽松忽略该键。修法 = 补齐 models.h + json_codec 双向编解码（顺带补 `ResidenceSlot`/`PatrolSlot`/`SpiritMineSlot`/`PatrolConfig` 的字段序 `operator==`）。同族登记：宗门过滤无法在 C++ 复现时，由 Kotlin 组装实例集/占位集合随请求传入（§2.35/§2.36）。
- **事务外调用的返回值被忽略 → "免费种田"（Bug B）**（来源：handover §2.48，batch-17）：`plantOnSpiritField` 的扣种走事务外 `removeSeedSync` 且返回值被忽略 → 种子不足也种满。根修 = 扣种与写地块放在同一 `seeds.get/remove/update` 事务内。
- **整包替换必须"全包上线"：C++ `from_json` 是宽松 readField，省略默认值字段会静默保留旧值**（来源：handover §2.49，batch-18）：`sectPolicies` 整包替换依赖 Kotlin 侧 `encodeDefaults = true`，否则"关闭政策"因缺键被忽略而失效——跨语言整包覆写必须核对两端的默认值编码策略。
- **事务内快照取在扣费之前 → `data.copy` 回写把扣费整体覆盖（"扣费静默丢失"）**（来源：handover §2.49，batch-18 gate 测试暴露）：`applyPolicyToggleFallback`/`toggleOpenRecruitment` 付费分支的 `val data = gameData` 原在 `wallet.deduct` 之前取，末尾 `gameData = data.copy(...)` 把刚写入事务态的扣费整体覆盖（政策照常置位）。修法 = 快照移至 deduct 之后。凡"先取快照 → 走副作用 → 末尾 copy 回写"的编排都要核对该顺序。
- **`std::to_chars` 最短往返 = Java `Double.toString` 序列一致**（来源：handover §2.50，batch-19）：`javaDoubleToString` 用于 `statusData["adBreakthroughBonus"]` 等 double→字符串镜像字段，保证双端字符串逐字节相同——不能用 `std::to_string`/`%g`（尾零与精度表现不同）。
- **`FakeAtomicStateStore` 的事务缓冲与部分字段不互通**（来源：handover §2.50 + §3 batch-19 登记）：`claimSectLevelReward` 首领后 `writeSectLevelRewards` 已完成（`allSucceeded=true` 分支已执行）但 `stateStore.gameDataSnapshot.sectLevelClaimRecords` 仍为 0 条。用例改"直接播种冷却凭据"绕开环境缺陷，并按根因修复生产侧静默失败（`writeSectLevelRewards` 返回 Boolean + 调用方明确失败文案）。同族见本文件 `FakeAtomicStateStore.materials` 条。
- **移植常量必须逐值对照 Kotlin 权威常量，不能取同名近似值**（来源：handover §2.51，batch-20a）：`secret_realm_settlement.h` 的 `kOpenYears` 误取 `COOLDOWN_YEARS`（50），Kotlin 权威 `GameConfig.SecretRealm.OPEN_YEARS = 5` → AUTHORITATIVE 月结到期判定变成"秘境现世满 5 年后还要再挂 45 年"，与 Kotlin 回退臂口径分裂。勘误后到期判定单源锚定 `secret_realm_cfg::kOpenYears`。
- **跨语言 TOCTOU：C++ 若写了某个标记，会让 Kotlin 的重查臂早退**（来源：handover §2.44，batch-13）：世界关卡胜利事务在 Kotlin 侧有 `defeated` TOCTOU 重查，C++ 下沉时若顺手写 `defeated`，Kotlin 重查臂会早退**漏发魂力** → C++ 明确不写 `defeated`，胜利事务整体留 Kotlin。判定字段所有权必须连同下游重查/早退逻辑一起看。
- **列式/SoA 模型无法表达 Kotlin 的"幽灵列条目"**（来源：handover §2.45，batch-14）：婚姻批准对"提议残留 + 弟子已亡/被逐"边界，Kotlin 原路径会写幽灵列条目，SoA 无法表达 → native 返回 NotFound 信封回退 Kotlin 原路径保行为（诚实回退优于带病下沉）。同族：逐出是**行删除**而非死亡标记（本族零 `isAlive=0`/`status=DEAD` 写入，markDead 路径不经该事务）。
- **`shuffled` 语义 = 逐元素 1×nextInt() 后稳定排序，不是 Fisher-Yates**（来源：handover §2.46，batch-15）：特质池洗牌按 Kotlin `shuffled` 原语复刻；`random/randomOrNull` = `DeterministicRng.nextInt(bound)`（Lemire，low32 有符号比较）。与 §S5 的 `java.util.Random` 版降序 Fisher-Yates 是**两套不同原语**，选错即抽取序红线破裂。
- **玉符（绝对值覆盖写模型）的扣减必须在 C++ 事务内承扣**（来源：handover §2.46/§2.50，batch-15/19）：若由 Kotlin 承扣，tick 滞后窗口内 C++ 余额检查读到滞后值会**双花**；"扣减失败但已抽取"又违反失败臂零抽取红线 ⇒ C++ 事务内原子完成"校验 + 扣减 + 抽取"。Kotlin native 臂成功后必须经 `syncJadeRuntimeAfterNative(cost)` / `syncBalanceFromSnapshot()` 把运行时 `totalCount` 锚回快照绝对值（否则 `checkpointNow` 把余额回涨），且消耗仍收敛于 `JadeSymbolService` 唯一入口（`JadeSymbolConsumptionGuardTest` 拦截）。
- **开机/读档窗口的 native 臂会以旧状态取数并污染新档**（来源：handover §2.47，batch-16）：`refreshRecruitList` 的开机路径 `initializeWorldAndServices` 在 `syncNativeBaselineAfterLoad` **之前**调用（C++ 侧或持上次会话旧世界）→ native 臂会以旧世界宗门等级取数，并把旧档 recruitList 镜像回新档。门控序 = Provider 缺省 → flag → **世界已导入守卫（`worldMapSects` 非空）** → **差值门预检**（与 C++ `kRecruitRefreshIntervalYears` 同款，防双臂分叉）→ 镜像服务判空。同类：读档路径入口 `autoHarvestCompletedAlchemySlots` 不下沉（迁 native 会在首月读档产生"免费收获"）。
- **`id=""` 的"镜像生成字段"会在按 id 去重时坍缩**（来源：handover §2.47，batch-16）：C++ 刷新候选 `seed.id=""`（Kotlin 臂用 UUID，镜像生成字段、id 不参与业务），同次年结 T1#8 老化净化按 id 去重时多个空 id 候选**坍缩保首**（Kotlin 臂不坍缩）→ 跨年手动招募池容量或受限。本批零行为变更不修复，登记为既有 AUTHORITATIVE 基线行为（拍板项）。
- **`ctest` 的 per-test 进程会在 exe 被并行构建复写时给出单帧假失败**（来源：handover §2.48，batch-17）：`BoundaryTxFixture.AutoAssignBatchWritesPoliciesAndCounters` 单次失败，实为并行 session 新文件编译期落盘复写可执行文件 → 用**单进程 `./game-core-tests.exe` 直跑**复核（同 1204 全绿）、ctest 重跑无再现。并发构建期验收应加"单进程直跑"复核步骤。
- **Int 集合字段的唯一归一化点**（来源：handover §2.55，batch-23）：设置项补丁的 Int 集字段必须在解析阶段去重 + 保序**一次**，比较与写入共用同一份归一化值——初版"比较用原始输入、写入用去重值"分叉，导致重复元素输入下 `changed=false` 却不写。GTest 首跑即暴露。
- **失败信封被 `tryExecuteNative` 统一降级为 null 时，用户可见文案会丢失**（来源：handover §2.56，batch-24）：灵根/特质 confirm 两入口的玩家可见错误（弟子不存在/已死亡/特质已不存在）由 C++ 判定链产出 → 新增 `executeRaw` + `NativeRefusal`/`RawResult` 保留 failure 信封的 `code` + `message`（镜像回读契约与 `tryExecuteNative` 同源），入口做 Applied/Refused/Unavailable 三态分派。
- **🔴 "AI RNG 分区不在协议面"是错误断言（本审计初稿判错，勘误）**（来源：handover §2.57，2026-09-14）：初稿称 `AISectDiscipleManager` 的私有 `_rng` 状态"不在快照协议 `rngStates` 段内、C++ 无镜像状态，故下沉需先做 AI RNG 通道统一（大立项）"。**实测推翻**：真源 `GameRngManager.getRng(AI_SECT)` 在 AUTHORITATIVE 下**已委托到 C++ `kAiSect` 分区**，且 `GameRngManager::exportStates()`（`rng_manager.h:61`）**遍历全部分区**（含 `kAiSect=6`）——该分区本就在协议面内。真问题是 `_rng` 为**真源的影子拷贝**（`initForSlot(mapSeed)` 以 `mapSeed + 6×31337` 播种 ≠ 真源 `systemSeed + k`），只在 `createNewGame`/`loadData` 重播后各自漂移。**教训：判断"某状态是否在协议面内"必须读 exportStates 的实现，不能凭"某某在协议面内"的既有结论推断；错误结论会把小改误判为大立项。**
- **🔴 第三个未受治理的随机流 `GameRandom`——自称确定性却从未播种**（来源：handover §2.57 + ADR rng-determinism-remediation，2026-09-14）：`GameRandom` 是自建 `object`（XorShift128Plus），**生产调用 8 处且无一为纯表现**：`GameEngineLoadDataOps:262/332` **`mapSeed` 生成**（世界生成根种子）、`Disciple:228-234` 弟子七项属性方差、`SpiritRootGenerator:33` 灵根洗牌、`GameConfig:421`、`HeavenlyTrialComponents:188/189` 天劫对手（**且在 UI 层调用随机**）。三重缺陷：① 种子 = `AtomicReference(System.currentTimeMillis())`（挂钟时间）；② **`setSeed()` 生产零调用**（仅测试调）；③ `@ThreadLocal` **每线程独立流**，且 `setSeed` 只 `threadLocalRng.remove()`——已存在实例不会立即更新。其 KDoc 自称"支持种子设置以实现确定性存档"，**该承诺从未实现 = 死抽象**。**教训：审计"随机流覆盖面"不能用单一正则**——本项目实为四类入口（`GameRngManager` 分区 / `Random.Default` / `GameRandom` / 对象自持 RNG），只统计 `.random()`+`Random.Default` 会**漏掉整类**（本 ADR 初稿即如此，114 处漏掉了这 8 处）。守卫必须从源头禁止新建随机源（不变量 R5）。
- **Dagger 环用 `Provider<T>` 惰性边破环**（来源：handover §2.30.1，batch-01 续修）：`DiplomacyService`/`VassalService`/`GiftService` 构造注入 `gameEngineCore: GameEngineCore?` 形成 `GameEngineCore→DiplomacyService→CultivationEventProcessor→CultivationService→GameEngineCore` 环，`:app:hiltJavaCompileRelease` 失败 → 改 `Provider<GameEngineCore>?` + 私有/内部取值访问器，调用点与测试直构零变化。
- **手工单例服务不可构造注入**（来源：handover §2.36，batch-07）：`StateSyncService` 是 GameEngineCore 手工单例，构造注入会（a）触发 Dagger MissingBinding（其 `reverseSender` 默认 lambda 无 `@Provides` 绑定）、（b）**分叉反向通道实例（双 reverseVersion/锚点缓存 = 反向通道损坏）**。修正 = 去 `@Inject`/`@Singleton` + 公开工厂 `createRoadFacade` 直构传同引用，构造器可空形参保留为测试接缝。诊断入口：干净检出跑 `:app:lintRelease` 才暴露（主树不报）。
- **集成合并禁止无脑取"theirs"**（来源：handover §2.40，集成收口批）：`BuildingFacadeImpl.kt` 的 batch-06 版本基于 batch-01 拆分**之前**的旧结构，取 theirs 会把 batch-01 的成员下放整体回退 → 正确解法 = 取 HEAD 结构 + 只补本批 native 臂。合并前必须先判断"分支基线是否旧结构"。
- **`using` 声明被困在 `detail` 命名空间内 → 该头在 detail 外任何包含序都不可用**（来源：handover §2.38，batch-09 为 batch-08 在途文件做的补全）：`disciple_tx.h` 的 using 写在 detail 内，detail 外事务函数无法解析 `GameState`/`DiscipleStore`（standalone 探针 21 错实证）；修法 = using 提升至命名空间作用域。
- **批量脚本的环境坑三连**（来源：handover §2.30，batch-01）：①detekt XML 报告对**中文文件名**做 HTML 实体转义，修剪脚本须 `html.unescape`；②`git status` 中文路径默认引号转义，须 `git -c core.quotepath=false`；③msys 环境 subprocess grep 的 `\(`/`\b` 参数被吞，ERE 须写 `[(]`/`[^A-Za-z0-9_]`。
- **detekt VariableNaming 对 internal 下划线后备字段报违规而 private 不报**（来源：handover §2.30，batch-01）：成员 private→internal 后须补 `@Suppress`（沿文件内既有惯例注释），`_xxxFlow`/`ids_` 型 backing property 会在 internal 化时成批暴露。
- **批量脚本禁用宽通配 `rm`**（来源：handover §2.30，batch-01）：`rm GameEngineCore*Ops*.kt` 误删已提交文件，靠编译错误网兜底 + `git checkout HEAD` 恢复——删除必须按显式清单。
- **KSP 增量缓存多进程并行下频繁损坏**（来源：handover §2.30，batch-01）：清 `build/kspCaches` + `generated/ksp` 即恢复；同族环境故障：Gradle `classes.jar` 文件锁、`journal-1.lock` 争用、受损 intermediates、KotlinCompileDaemon 残留（`gradlew --stop` 不杀，须按命令行补杀）。
- **"自评通过"不可信，结构批交付结论必须由独立实跑复核**（来源：handover §2.30/§2.30.1，batch-01 续修）：batch-01 原记"触碰面 detekt 0 违规 + 主源编译通过"，续修实测 HEAD `core:engine` 主源不可编译（357 错）、detekt 进程级崩溃、测试源 1043 编译错、全量单测 216 失败（拆分工具损伤：13 处畸形签名 / 5 函数丢失 / 1 处悬空 KDoc / 101 处 KDoc 与注解错挂）。**结构批交付前必须独立复跑门禁。**
- **跨模块可见性收窄陷阱**（来源：handover §2.30.1，batch-01 续修）：把成员下放为同包扩展时统一写 `internal`，而原成员是 `public` 且被 feature:game/app 消费 → 39+18 处不可解析；修法 = 以**拆分前声明**为准回写 public（149 处）+ 为跨模块调用方补 import。拆分脚本的可见性必须从拆分前声明取，不能统一写死。
- **owner 线程记录会因紧急重启换线程而失效 → 守卫误杀新引擎线程**（来源：handover §2.7，P1-4 勘误）：`nativeInit` 在引擎线程记录 owner，但 EMERGENCY restart 会 `recreateGameDispatcher` 换线程，原 owner 永不更新 ⇒ 新引擎线程进入任何 kEngineOnly 入口即 abort。修法 = `EngineLoop::onLoopRestart` 置位待重锚标志，桥层 `nativeLoopFrame` 首帧消费并重锚。同批教训：`startGameLoop` 按 Kotlin 设计**可从主线程调用**（前台服务 `onStartCommand`/`GameActivity.onResume`），误标 kEngineOnly 在真机 debug 直接 SIGABRT——**守卫只放在可验证的契约上**。
- **真机不可得时的替代验证口径**（来源：handover §2.39，batch-10）：物理真机不可得 → 模拟器（MuMu 12 / Android 15 / x86_64+arm64-v8a ARM 转译）按"开发期 debug 构建运行"原文口径执行，物理机专属残留逐项登记；原生库用**产物字符串核验**（debug 断言串在位、WARN 串消失、release 零守卫串 = NDEBUG 擦除）＋ 断言版重签安装复跑验证"无误杀"。该口径可作为真机批的前置替代。
