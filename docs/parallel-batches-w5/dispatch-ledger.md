# 重构方案跨会话派工台账（native-engine-refactor-plan-2026-09-17）

> 本文件是跨会话编排的**唯一权威状态**。定时看护每 10 分钟触发一次，触发轮
> **不依赖任何对话上下文**，只凭本文件 + 方案文件 + 仓库现状决策。
> 方案文件：`docs/native-engine-refactor-plan-2026-09-17.md`（下称"方案"）。
> 仓库根：`C:\Mnzm\XianxiaSectNative`。

---

## 看护运行手册（每轮必读必照做）

1. **读状态**：读本文件"当前状态"一节。
2. **防重入**：任何写操作（验收、派工、改台账状态）前，先把"看护锁"写成本轮时间戳。
   若发现锁已有值且距今 < 8 分钟：本轮**只做截屏 + 监控日志追加**，然后结束。
3. **状态 = in_progress（子会话施工中）**：
   - 用 windows-mcp `Screenshot` 截屏，观察 ZCode 窗口中子会话的进展；
   - 仍在工作（输出在增长/工具调用滚动）→ 监控日志追加一行（时间 + 一句话），结束本轮；
   - 异常（权限确认弹窗 → 点击允许；报错弹窗 → 记录；**连续 3 轮截屏无任何输出变化** → 判定停滞）→ 按第 5 条处置；
   - 子会话已给出**最终完成报告**（输入框空闲、报告含提交号与测试数字）→ 把状态改为 `verifying`，进入第 4 条。
4. **状态 = verifying（验收）**：验收人 = 看护自己，**不信任子会话自述**：
   a. `git log --oneline -15` 核对本批要求的提交全部存在且逐子项独立；
   b. 亲自复跑批次文件"验收门"的全部命令（长命令可用 run_in_background）；
   c. 核对方案 §7 已新增本批登记、CHANGELOG.md 已更新、`git status` 干净（无未提交遗留）；
   d. 全过 → 本批状态改 `accepted`，证据列填提交号 + 测试数字，状态改回 `dispatch`（待派下批）；
      任一不过 → 状态改 `fix_needed`，缺陷清单写进"当前状态"。
5. **状态 = dispatch 或 fix_needed（派工）**：
   - `dispatch`：依据方案 §3 对应条目 + "经验教训"，写下一批批次文件 `docs/parallel-batches-w5/batch-XXX.md`
     （模板照 `batch-R1A.md`：任务 / 红线 / 验收门 / 完成报告格式；范围严格限本批）；
   - `fix_needed`：批次文件沿用原批 + 顶部追加缺陷清单，指令改为仅修复所列缺陷；
   - **GUI 开新会话**：截屏确认桌面 → 聚焦 ZCode 窗口 → 点侧栏"新建任务"（或聚焦后 Ctrl+N）→
     截屏确认进入新会话输入页、右下模型为 **GLM-5.3-Flash**（不是则点模型选择器改选）→
     点输入框 → 输入短指令（内容见下）→ 回车 → 截屏确认子会话已开跑；
   - 新会话指令模板：`读取 docs/parallel-batches-w5/batch-XXX.md，严格按该文件实施批次 XXX。完成后按文件内验收门自检，并按完成报告格式给出报告。`
   - 台账"当前状态"更新：状态 `in_progress`、当前批、派发时间；提交台账变更。
6. **终局**：批次总表全部 `accepted` → 状态改 `completed`，在台账写收官总结
   （各批提交号、G1–G7 目标达成核对、范围外项：R5 为 iOS 条件项、R4.1 删臂待灰度一个版本周期），
   然后 `CronList` 找到本定时任务并 `CronDelete` 删除，向用户报告收官。

---

## 批次总表（顺序即依赖序，依据方案 §3/§4）

| 批 | 内容 | 方案条目 | 状态 | 证据（提交/测试） |
|---|---|---|---|---|
| B01 | map 重建提升到步骤入口 + getMaxHpMp 临时 map 消除 | R1.1 + R1.5 | **accepted**（2026-09-18 00:25） | 01849d8b4 / ad5ca62ee / 文档 90db91f6b；看护亲跑：GTest 1419/1419（33s）+ JUnit 六模块强制实跑 229 任务全 executed 绿（engine 3296/0skip/0fail，XML 实证）+ detekt/lint/compile 绿；改动面仅 gamecore 3 头文件+3 文档，JNI/协议面零变更 |
| B02 | committedDisciples 去物化 + 字符串键 → dense 索引 | R1.2 + R1.3 | **accepted**（2026-09-18 02:35） | 96636ec95 / d4e25dac1 / dd2b4e0e9 / 补遗 f7e9b3251 / 文档 c656dce3f；看护亲跑：GTest 1425/1425（36.7s，含 +6 新守卫）+ 组合门 346 任务全 executed 21m33s（JUnit 强制实跑+detekt+compile+lint；engine XML 3296/0skip/0fail，对拍桥携 B02 C++ 重建）+ 改动面仅 gamecore C++（新增 instance_buckets.h 与 99 行守卫测试），JNI 面零变更 |
| B03 | DirtyTracker 列级写屏障 + destroyDiscipleEntities 去 O(D²) + R1 收官 bench（证明 G1） | R1.4 + R1.6 | **accepted**（2026-09-18 05:20）**R1 阶段收官** | eb9812c0a / 7e3bb87a9 / c97d7a4b0 / 文档 144dcb238；看护亲跑：GTest 1443/1443（35s）+ 组合门 339 任务全 executed 22m55s（engine XML 3296/0skip/0fail）；**G1 实证：结算 core 17 次 malloc / ~1.8ms @5000 弟子（目标 <1 万、基线 ~15 万），门禁断言 <10000 接线 ci.yml**；改动面全 gamecore C++（新增 column_dirty.h 698 行 + bench 229 行 + 守卫 346 行），CI 面仅 ci.yml 门禁开关 |
| B04 | 秘境战斗切 native（会话/邮件/暂停租约留 Kotlin） | R4.2 | **accepted**（2026-09-18 07:05） | fa8fa5833（SecretRealmService 17 行战斗段切换 + 246 行路由回归测试）/ 文档 29a033abc；看护亲跑：GTest 1443/1443（34.6s）+ 组合门 339 任务全 executed 22m13s（engine XML 3299/0skip/0fail）；改动面极小而精准（Kotlin 仅服务 17 行），回退臂按规保留 |
| B05 | 探索/巡逻生产结果下沉 | R4.3 | pending | — |
| B06 | GameView proto 定义 + nativeExportDirty 信封 + StateSyncService 解码 | R2.1 + R2.2 | pending | — |
| B07 | UI 消费面第一波：只换传输（镜像仍全量、二进制） | R2.3(一) | pending | — |
| B08 | 第二波：replaceAll 退役 + GameViewStore 投影态 + ViewModel 逐块迁移 | R2.3(二) | pending | — |
| B09 | 月/年信封并入 eventFeed + 残留执行器退化为平台效应适配器；G2/WS-1 验收 | R2.4 | pending | — |
| B10 | C++ SceneStore 新模块 + JNI 面重构（drawAllTiles 17 参数退役） | R3.1 + R3.2 | pending | — |
| B11 | overlay 几何 C++ 生成（消 258 drawRect）+ 脏更新协议 | R3.3 + R3.4 | pending | — |
| B12 | 远景观看容量路径（带黑名单验证）+ GLES 后端同构改造（Canvas 兜底不动） | R3.5 + R3.6 | pending | — |
| B13 | 原生浮层与文本通道 Tier1（浮字池/事件驱动 spawn）；G3/G4 + 截图回归验收 | R3.8 | pending | — |
| B14 | RNG 分区独立（消 per-roll JNI，对拍重定基线，存档版本说明） | R4.4 | pending | — |
| B15 | 图集离线化（build-atlas.mjs 直出 KTX/ASTC，消运行时 Canvas 拼图） | R6.1 | pending | — |
| B16 | 数值外置（C++ 头文件 DB → 数据文件加载，nativeSetGameConfig 扩展） | R6.2 | pending | — |
| B17 | CI 与度量执法：bench 门禁 + JNI 面计数静态门禁 + 平台纯度 gate；方案收官核对 | §CI | pending | — |

**范围外（不派工）**：R5 循环归属 C++（iOS 立项才启动）；R4.1 删臂 BattleSystem 转 golden
夹具（按 R4 统一流程，需灰度一个版本周期，产品节奏决定，非工程可单方面完成）。

---

## 当前状态

- **看护锁**：—
- **状态**：`in_progress`（B05 施工中）
- **当前批**：B05（R4.3 探索/巡逻生产下沉，批次文件 `batch-R4B.md`）
- **派发时间**：2026-09-18 06:48（GUI 新建任务派发，模型 GLM-5.3-Flash，完全访问，项目 XianxiaSectNative/main）
- **缺陷清单**：—
- **监控日志**：
  - 2026-09-17 22:55 编排建立：台账 + B01 批次文件提交（e36f5102f）；B01 经 GUI 派发，截屏确认子会话已读取批次文件并锁定 phase_settlement.h 开工。
  - 2026-09-17 23:07 截屏：B01 施工健康——上下文就绪（CLAUDE.md/CHANGELOG/桌面对拍构建入口已读，build/desktop-test/ 缓存在），核对 breakthrough_test.cpp 的 performBreakthrough 版本后即动手 R1.1；无弹窗。
  - 2026-09-17 23:16 截屏：R1.1+R1.5 代码改造完成，桌面 GTest 全量 1419/1419 全绿（与 §7.1 基线一致）；正按"每子项独立 commit"红线做拆分验证（stash R1.5 单验 R1.1）。
  - 2026-09-17 23:26 截屏：R1.1、R1.5 两笔独立提交均已落库（各自 1419/1419 全绿验证）；子会话转入全量 JUnit（预估 ~10 分钟）+ detekt + 文档三件套。
  - 2026-09-17 23:37 截屏：方案 §7.2 登记小节与 cpp-engine.md 进展行已写；JUnit exit 0 但子会话自查发现多数任务 UP-TO-DATE（测试可能未真正重跑），正核查任务实际执行情况与 gamecore.jni.path 注入——防"假绿"，严谨合格。
  - 2026-09-17 23:47 截屏：UP-TO-DATE 疑点已澄清——core:engine 3296/0skip/0失败 Diff 对拍全实跑，六模块合计 7777 用例 0 失败（17 既有 skip）；detekt+compileReleaseKotlin+lintRelease 全绿；CHANGELOG 4.01.15 + cpp-engine.md 已写，正在提交文档批次。下一轮预期转 verifying。
  - 2026-09-17 23:57 截屏：B01 最终完成报告已出（含假绿排除/版本口径/台账代改边界三条说明），会话空闲。看护占锁转 verifying，开始亲自复跑验收门。
  - 2026-09-18 00:25 **B01 验收通过**：六项门全绿（详见批次总表证据列）。看护亲跑 GTest 1419/1419 + JUnit --rerun-tasks 全实跑（229 任务 executed，engine XML 3296/0skip/0fail）+ detekt/lint/compile；提交谱系与改动面核对无越界。转入派发 B02。
  - 2026-09-18 00:22 B02 经 GUI 派发（batch-R1B.md），截屏确认子会话已读取批次文件开工。
  - 2026-09-18 00:23 截屏：B02 施工健康——已读批次文件/CLAUDE.md/方案 §3.R1+§7.2/B01 两笔提交，正定位 committedDisciples 物化点与 DiscipleStore string 键结构；无弹窗。
  - 2026-09-18 00:24 截屏：B02 上下文收集进行中，正读 phase_settlement.h 物化点；输出持续增长，无停滞。
  - 2026-09-18 00:27 截屏：B02 深度分析中——读 disciple_store.h（R1.3 核心）与 disciple_stats.h（R1.2 依赖），梳理 equipmentInstances/manualInstances 引用面，自建任务清单；无停滞。
  - 2026-09-18 00:37 截屏：B02 改码前语义验证——elderSlots 清除语义已验证（R1.2 前提成立）；R1.3 改动面确认为步骤入口派生映射（GameState 存储与事务文件零改动），正枚举 eqMap/mnMap 消费函数定签名迁移面。
  - 2026-09-18 00:47 截屏：B02 进入实施——构建环境确认（Ninja+llvm-mingw 缓存可用），正编辑 phase_settlement.h 实施 R1.2（+41-5、+3-3）。
  - 2026-09-18 00:57 截屏：B02 R1.3 第一步实施中——disciple_store.cpp numeric id 列与 eraseAt/swapRows 维护完成，settlement_detail.h indexById 已改（免逐字符串重解析），正逐点替换 phase_settlement.h。
  - 2026-09-18 01:07 截屏：B02 R1.3 第一步完成并自增守卫——disciple_store_test.cpp +98 行（6 条新守卫），GTest 1425/1425 全绿（1419+6），提交中；R1.2 battle_residual_tx.h 收尾已见。验收基线此后按 1425。
  - 2026-09-18 01:17 截屏：B02 R1.3 第二步改造中——disciple_stats.h 速查表改完（+39-38），phase_settlement.h helper 区重写净删 34 行、processManualProficiency 死代码删 52 行，负增量符合 R1.3 目标形态。
  - 2026-09-18 01:27 截屏：B02 R1.3 第二步 GTest 1425/1425 全绿，编译期发现命名空间别名问题已即时修复，正在提交；battle_residual_tx.h 亦已改（+12-10）。接近收尾。
  - 2026-09-18 01:37 截屏：B02 三笔代码提交已落（9663ec95/d4e25da/dd2b4ca），验收门重建 JNI 桥时构建失败（疑似新类型未覆盖编译单元），子会话正抓报错排查中——主动调试非停滞；若 3 轮无进展再处置。
  - 2026-09-18 01:47 截屏：JNI 桥失败已自愈——遗漏调用点补修复并提交（7fe9b325），GTest 复跑 1425/1425 全绿；JUnit 强制实跑后台进行（~11 分钟），并行起草文档三件套。
  - 2026-09-18 01:57 截屏：B02 验收门自检全过——JUnit 全量实跑 11m17s（222 任务全 executed），各模块全绿 0 失败（engine 3296 含对拍 0 skip）；正在做模块校准与文档收尾。下轮预期转 verifying。
  - 2026-09-18 02:35 **B02 验收通过**：六门全绿（详见批次总表证据列）。看护亲跑 GTest 1425/1425 + 组合门 346 任务全 executed（engine XML 3296/0skip/0fail，桥携 B02 改动重建）；提交谱系 4 笔代码 + 1 笔文档核对无越界，JNI 面零变更。转入派发 B03。
  - 2026-09-18 02:37 B03 经 GUI 派发（batch-R1C.md），截屏确认子会话已读批次文件并开始续接 B01/B02 改造模式。
  - 2026-09-18 02:38 截屏：B03 上下文收集——已读 CLAUDE.md/方案/B01-B02 提交，正定位 R1.6 目标函数与 DirtyTracker 现状；无弹窗。
  - 2026-09-18 02:39 截屏：B03 调研深化——ECS 销毁路径/clearAll 已查，自建任务清单，继续摸 bench 惯例与结算入口/exportDirty；无停滞。
  - 2026-09-18 02:40 截屏：B03 正读 phase_settlement.h 结算入口与核心批次，确定 bench 的 e2e 范围；无停滞。
  - 2026-09-18 02:47 截屏：B03 深读 DiscipleStore 序列化/列写入分布（R1.4 粘点）与 json_codec 列名映射；R1.6 精细甄别——单删已是 O(1)，swap-and-pop 仅限真正需要的重建场景，避免过度改造。
  - 2026-09-18 02:57 截屏：B03 设计定稿——工具链/1425 基线确认，查 CMakeLists 选项结构（bench 开关），规划 ecs_storage_test 守卫；即将开始实施。
  - 2026-09-18 03:07 截屏：B03 R1.4 实施中——disciple_store.cpp 四个写入粘点埋 dirty 位，新建 column_dirty_test.cpp 守卫（+339 行）并接入 CMake 构建。
  - 2026-09-18 03:17 截屏：B03 R1.4 打磨——导出签名收紧（依赖更窄），守卫测试随语义细化多轮调整（行删除保守标记=设计行为、行移位保留旧标脏）。
  - 2026-09-18 03:26 截屏：B03 R1.6 接近完成——swap-and-pop 变体已实现，ECS 守卫全绿（ecs_storage_test +51、ecs_disciple_test +27），跑全量确认零回归后提交。
  - 2026-09-18 03:36 截屏：B03 bench 实施中——CMake 接 GAMECORE_BUILD_BENCH 开关（kover 式），phase_settlement_bench_test.cpp +221 行写入；R1.6 已提交。
  - 2026-09-18 03:46 截屏：**G1 达成**——bench 实测 core 17 次 malloc / 1826µs @5000 弟子（目标 <1 万、基线 ~15 万；3 轮确定性一致）；GTest 1443/1443 绿（基线上移），CI 门禁接线中；三子项全部提交，验收门自检开始。
  - 2026-09-18 03:56 截屏：B03 文档三件套落笔（§7.2/CHANGELOG/cpp-engine），JUnit 强制实跑编译中；等待出最终报告。
  - 2026-09-18 04:06 截屏：B03 JUnit 实跑遇 1 失败——GameEngineCoreLifecycleInterleavingTest（并发时序，与 C++ 改动无因果，Diff 全过）；单类重跑绿确认为负载抖动，正重跑全量满足"全绿实跑"门。看护验收时将把该用例列为已知抖动、必要时复跑一次。
  - 2026-09-18 04:16 截屏：抖动用例第二轮全量仍失败——子会话深挖因果：读测试实现（5s await 并发时序）与历史，发现 progress.md:912 既有间歇失败前例（W2 期旧文件，本批未触碰 Kotlin/JNI，Diff 全过）；已在无并行负载环境重跑全量。若第三轮仍红，看护验收时按既有前例口径裁定或单列处置。
  - 2026-09-18 04:27 截屏：独立环境全量重跑通过——7777/0 失败，Diff 对拍 47 类 268 用例 0 skip，六模块全 executed；抖动判定成立（负载相关）。剩余门禁 detekt/lint/compile 跑完即出最终报告。
  - 2026-09-18 05:20 **B03 验收通过，R1 阶段收官**：六门全绿（证据见批次总表）。G1 实证落账。转入派发 B04（R4.2）。
  - 2026-09-18 05:08 B04 经 GUI 派发（batch-R4A.md），截屏确认子会话开跑。
  - 2026-09-18 05:10 截屏：B04 上下文准备——批次文件/CLAUDE.md/方案/ADR 已读，正研究 R4.1 BattleExecutionRouter 既有接线模式；无弹窗。
  - 2026-09-18 05:11 截屏：B04 目标定位——SecretRealmService 两处战斗段（L879/L603）锁定，调研路由器秘境信封 rebuildBattleLogData 能否复用于 native 通道。
  - 2026-09-18 05:12 截屏：B04 R4.1 模式吃透（tryExecuteNative ?: battleSystem.executeBattle），正读 BattleSystem/GameCoreBridge 确认 RNG 委托与超时语义。
  - 2026-09-18 05:16 截屏：B04 异常语义闭环确认（error 信封→null→回退），旗标体系与路由器测试模式明确；排查秘境端其他战斗执行点与服务测试覆盖。
  - 2026-09-18 05:26 截屏：B04 路由回归测试编写中——SecretRealmServiceRouteTest +234 行（仿 R4.1 同款），按 WriteGuardRule 修正导入/作用域/空安全。
  - 2026-09-18 05:36 截屏：B04 子项 1 已提交（fa8fa5833，秘境路由+测试 3/3 绿），GTest 门 1 自过 1443/1443；重建对拍桥准备 JUnit 门。
  - 2026-09-18 05:46 截屏：B04 JUnit 门遇确定性构建失败——:core:domain bundleLibCompileToJarRelease 同位置三次同刻失败；已排除并发 Gradle（并正确甄别看护 10 分钟提交仅为文档），正探测文件锁/守护进程。非停滞，连续 3 轮无进展再介入。
  - 2026-09-18 05:56 截屏：B04 构建失败根因解决——锁源为 Kotlin 编译守护进程持有 classes.jar，终止释放清理后重跑全量 JUnit，已越过失败点正常推进。
  - 2026-09-18 06:06 截屏：B04 验收门全过（detekt 绿），文档三件套落笔提交中；下轮预期最终报告。
  - 2026-09-18 07:05 **B04 验收通过**：六门全绿（证据见批次总表）。转入派发 B05（R4.3）。
  - 2026-09-18 06:48 B05 经 GUI 派发（batch-R4B.md），截屏确认子会话正确复述任务理解并开跑。
  - 2026-09-18 06:50 截屏：B05 调研按序推进——CLAUDE.md/方案/ADR/B04 范例已读，ExplorationService 主体已过，正调研 BeastRaidOps native 优先模式参照。
  - 2026-09-18 06:51 截屏：B05 调研扩展——发现 C++ 侧已有 exploration/patrol 头文件，正搜索 AI 兽战决策（子事件 9）既有实现与 PatrolBattleSystem/CaveExplorationSystem 生产路径。
  - 2026-09-18 06:53 截屏：B05 切点锁定——createBeastBattle 仍直调 Kotlin executeBattle（参照 AiSectBeastAttackProcessor 的 router 模式应为改造点）；继续排查 PatrolBattleSystem/CaveExplorationSystem。
  - 2026-09-18 06:56 截屏：B05 范围甄别——UI 事务 native 臂不动、关卡刷新段防误切/漏切核对中；参照 B04 守卫测试模式查 Diff 对拍覆盖现状。
  - 2026-09-18 07:06 截屏：B05 关键风险确认——JVM 测试中 GameCoreBridge.isLoaded 恒 false，路由器测试恒走 Kotlin mock 臂（与 B04 同前提），据此设计守卫测试。
  - 2026-09-18 07:16 截屏：B05 守卫测试调试中——单类先行验证遇断言失败（mock 泛型方法未 stub 等），正读报告逐项修正；正常迭代非停滞。
  - 2026-09-18 07:26 截屏：B05 守卫 5/5 绿，验收门 1 自过（GTest 1443/1443 与基线持平，正确判定纯 Kotlin 改动无需重建二进制，且复用了台账 SOP）；JUnit 门后台实跑中。
  - 2026-09-18 07:36 截屏：B05 文档三件套初稿完成（§7.2/CHANGELOG/cpp-engine），JUnit 全链重建编译中，轮询等待。

## 经验教训（随批追加）

- 子会话（GLM-5.3-Flash）质量好：会自查 UP-TO-DATE 假绿并以 XML 时间戳证明实跑；版本口径主动对齐仓库先例（纯内部批不动 version.properties/changelog_entries.json）；不代改看护台账。
- 看护复跑方法（固化为验收 SOP）：① GTest：llvm-mingw + SDK cmake 3.22.1 入 PATH，`ctest` 于 `android/app/src/main/cpp/gamecore/build/desktop-test`（约 33s）；② JUnit：先 `pwsh -File scripts/build-desktop-jni.ps1` 重建 .so，再 `testReleaseUnitTest --max-workers=1 --rerun-tasks "-Dgamecore.jni.path=<so 绝对路径>"`（约 11 分钟，必须 --rerun-tasks 否则 UP-TO-DATE 不实跑）；③ detekt/compileReleaseKotlin/lintRelease 可并入一道 gradle 调用。
- 派发指令引用批次文件（而非塞满 prompt）效果良好；批次文件内写明"续接上一批提交号与 §7.2 登记"有助连贯。
- B02 期间一次 JNI 桥构建失败（迁移面遗漏调用点）子会话 10 分钟内自愈并补提交（f7e9b3251）；子会话会主动新增守卫测试（B02 +6 条），验收基线随批上移（1419→1425），看护复跑前须读当批报告确认基线。
- 组合门一道跑（JUnit --rerun-tasks + detekt + compile + lint）约 21 分钟，比分次跑省 daemon 争用；对拍桥必须先于 JUnit 重建（build-desktop-jni.ps1）。
- **已知抖动用例**：GameEngineCoreLifecycleInterleavingTest（W2 期并发时序，5s await 窗口，全量负载下偶发超时；progress.md:912 前例）。单点失败且 Diff 全过 → 单类重跑绿即记录放行，勿改测试；全量负载重跑建议避开并行构建。B03 已三次实证该模式。
- GTest 基线随守卫增长：1419（B01 前）→ 1425（B02）→ 1443（B03，含 bench 3 用例；GAMECORE_BUILD_BENCH 本地默认关时 1440）。验收前先读当批报告确认基线。
- **环境隐患（B04 实证）**：Kotlin 编译守护进程可能在增量编译后持续持有 classes.jar 句柄，导致 --rerun-tasks 全量在同一任务确定性失败（FileSystemException）；处置 = 终止 Kotlin 编译守护进程 + 清理后重跑。看护验收组合门与子会话构建不要同时跑（先后错峰）。
