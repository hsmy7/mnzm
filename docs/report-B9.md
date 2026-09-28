# B9 批交付报告——测试基准重建 + 遗留清理（实时结算线）

> 批次：`docs/realtime-settlement-plan-2026-09-27.md` §10 B9 行；
> 派发件 `docs/realtime-watch/batch-B9.md`（含 B8 附录 8 条）。
> 收官笔：单笔 `feat(test): B9 测试基准重建+遗留清理——…`（版本号不自增）。

## 一、批次任务完成度（派发件 §1 逐项）

| 项 | 结果 |
|---|---|
| §9.1 A 类缺陷清偿（1/2/3/4/12/15/17） | 全部清偿（#2 经核实**此前已闭合**，见 §四.1；其余 6 项本批实施） |
| 缺陷 #10（cultivationCompletionPhase 死值） | 全链退役 + Room v60→v61 |
| 死代码 #11（RealmConfig.maxAge）/ #16（下线系统死常量） | 已清理（含配置面与测试引用） |
| 两套时基修复（§3.4 指名） | 旧累积器退役，shadow/对拍臂与生产臂统一 advanceByGameMs |
| 按 §3.4 清单重定基准 / §5.2 三类口径 | 见 §三 |
| 验收：六模块全绿 + GTest 全绿 + detekt/lint 绿 + agent-instructions 绿 | 全部达成（§二原数字） |

## 二、门禁实测（原数字）

在 worktree `android/` 下执行（ctest 在 `gamecore/build`）：

| 门 | 结果 |
|---|---|
| `compileReleaseKotlin` | BUILD SUCCESSFUL |
| `build-desktop-jni.ps1`（C++ 变更后重跑） | `libgamecorejni.so` 重建成功 |
| 桌面 ctest 全量（llvm-mingw PATH，GAMECORE_BUILD_BENCH=ON） | **1494/1494 全绿**（基线 1483 + 本批新增 11：time_system_test 时基重建净 +1、year_settlement_test 思过 3 例、game_config_parity_test 新文件 7 例），50.7s；bench 三例含 `SegmentUnderBudgetAt5000` 全过 |
| 六模块 JVM 全量（`--max-workers=1` + `-Dgamecore.jni.path`，45 个 Diff*Test 实跑） | **7550/0/2 skipped**：app 1030/0、domain 1587/0、data 821/0、engine 2964/0、ui 155/0、feature:game 993/0；engine+domain 于末轮代码微调后定向复跑仍 2964/0 + 1587/0 |
| `lintRelease detekt` | 六模块全绿；detekt 违规 0（途中新增 5 处已修：GameConfig.kt 折行 5 行 MaxLineLength + GameEngineLoadDataOps TooManyFunctions，见 §五.3）；baseline 未动 |
| `node scripts/check-jni-count.mjs` | 88/88（双桥无扩散） |
| `node scripts/check-agent-instructions.mjs` | 全绿（路由闭包 42 篇 / 444 引用可解析） |

## 三、测试基准重定（§3.4 清单 / §5.2 三类口径）

- B1–B8 已将 §3.4「必失败测试」清单内测试按新轨重建（B8 验收基线 ctest 1483）。
  本批为最终核对 + 增量重建：
- **判定轨（逐位）**：`advancePhases`/钩子序/年变 Diff 保持逐位断言不动；
  `DiffYearSettlementTest` 对拍快照按「判定轨逐位」扩思过双弟子场景
  （endYear=2 释放 / =3 未到期；status/statusData/morality 列全字段对拍 + 显式断言）。
- **积分轨/投影（容差与闭式）**：`time_system_test.cpp` 旧 phaseCap 丢弃语义 4 用例
  按 INV-2/INV-3 重建为 `advanceByGameMs` 语义基准（cap 只裁判定执行、时间零丢失、
  负增量钳制、跨段凑旬）——两套时基统一后 shadow 臂语义基准的正式落点。
- **月/年判定 RNG 序**：年变新增 `processReflectionRelease` 零 RNG（测试断言分区
  快照不变面未受影响，ctest 1494 全绿佐证）。

## 四、缺陷/死值实施纪要（详细改动面见外部 CHANGELOG.md B9 段）

1. **#2（墙钟双源）经核实已闭合，未重做**：`GameEngineSectLevelOps.claimSectLevelReward`
   Kotlin 侧经 SR-5 注入墙钟取时，native 臂 `nowMs` 为参数传入（`put("nowMs", nowMs)`），
   `jade_tx.h` 消费传入值——方案写作时的「native 侧裸读 System.currentTimeMillis」已不存在。
2. **#1 reflectionRelease**：C++ `year_settlement.h` 新增 `detail::processReflectionRelease`
   （T1 序 = yearlyAging 之后、年报快照之前，对齐 Kotlin；道德 +5 clamp kSkillMax=200；
   零 RNG）。C++ GTest 3 例 + Diff 对拍双弟子。
3. **两套时基**：`SettlementEngine::advance(wallDeltaMs)`（phaseCap 丢弃式累积器）删除
   （JNI 零暴露，仅 C++ 测试消费）；`GameCore::advance` 内部改走 `advanceByGameMs`；
   `accumulatedGameMs_` 成员与 reset 复位同步清除。shadow/对拍臂与生产臂（PhaseClock +
   EngineLoop.iterate）同语义。
4. **#3 附庸年贡**：双端 `processYearlyTribute` 改读 `annualTotalIncome` 年度流水
   （T1 首位执行时上年收入尚未被年报快照清零——时序天然正确）；`recordYearlyIncome()`
   死方法删除。字段退役：C++ models/json_codec 双向/GameDataFieldPatch 行删除；
   Kotlin 字段与 Room 列/Proto 95 位**保留**（规范 7.1 不删列 + 旧包回滚兼容），
   `@Deprecated` 标注；存储面物理退役登记 D2 同批。守卫同步：
   `GameDataFieldPatchGuardTest` 引入 `intentionallyUnmirrored` 排除集（§9.5 三要素）、
   `BaselineFieldCoverageGuardTest` 排除表登记。
5. **#4 读档锚定**：`WorldLevelManager.anchorLastRefreshMonthForLegacySave` 纯函数 +
   读档链注册（importToNative 之前——锚定值随导入进 C++ 双臂单点覆盖）。
   **口径修正（途中发现）**：绝对月口径 `year*12+month` 下 1 年 1 月 = 13，首版
   `<= 1` 判据永不生效且会误锚新档推迟关卡首刷——修为 `<= NEW_GAME_ABSOLUTE_MONTH(13)`
   （进度未推进的新档保留 `== 0` 首刷语义），测试补 1 年 2 月边界例。
6. **#12 START_STICKY**：`GameForegroundService` 拆 null 分支（系统重建只恢复外壳
   不启循环；恢复 = Activity onResume 显式 ACTION_START / resumeFromBackground、
   看门狗兜底同样显式 ACTION_START 不受影响）；决策表 `shouldAutoStartLoop` 纯函数 +
   4 例锁定。B7 遗留 D8 真机项由代码口径保证收口。
7. **#15 常量双端锚点**：C++ 腿 `game_config_parity_test.cpp`（7 值 + kElderSkillBaseline
   三头互等）；Kotlin 腿 `ConfigCppConstantsParityTest`（读 C++ 头文本正则抽值 ↔
   Kotlin 可达常量直断 + private 常量源码文本抽取）——改任一侧另一侧即红。
8. **#17 文档漂移**：月变双臂口径（离散臂八步/连续臂五判定步）、子事件 15 项实数
  （编号 1/5/6/6b/6c/7/8/9/10/11/12/13/14/15/16，2/3/4 空洞）、autoRecruit 引用清除、
   七系统→四系统、MonthSettlementExecutor「七步/十六」自相矛盾统一、年变「11+11」→
   Kotlin T1 8 项/T2 8 项（C++ T1 七项：discipleAging 为 Kotlin 状态重推导幂等纯派生
   非行为缺口；T2 七项：aiAlliances 场景规避）；尾注三条过期记录回改（审计 P2-16
   已修标注、AuthoritativeOps KDoc 时钟语义、年变 11+11 两处）。
9. **#10 全链退役 + v61**：C++（字段/列枚举/列存储六处/脏列双 switch/GameView 协议行
   28 号退役不重排/json_codec 双向/phase_settlement 两处）+ Kotlin（Entity 字段/Proto
   surrogate/DiscipleTables 四件/BreakthroughHandler/GameViewDiscipleRows/MirrorCodec）
   + Room **v61**（`rebuildTableDroppingColumns` 删 disciples 单列，v55 先例模式；
   `RoomMigrationV60To61Test` 三例：真实 Room schema 校验/数据逐格完整+索引主键重建/
   幂等）；历史迁移 SQL、OldSerializableSaveData、旧版 schema 种子测试原样保留。
   `RoomMigrationV58To59Test` 全链断言随链尾 61 合法波及更新（90→89 列，注释说明）。
10. **#11/#16**：`RealmConfig.maxAge` 属性 + 十处实参 + RealmConfigTest 断言 +
    `GameConfigData.DiscipleSection.maxAge` + `game_config.json` disciple.maxAge +
    ConfigLoaderTest/GameConfigConsistencyTest 引用；`ENHANCED_SECURITY_EFFECT`/
    `CURFEW_EVENT_REDUCTION`/`CURFEW_DESERTION_REDUCTION`/`REWARD_PUNISH_EFFECT`
    四死常量 + 两处测试引用（政策本体月费/忠诚链保留不动）。

## 五、假红/返工诚实归因

1. **bench `SegmentUnderBudgetAt5000` 三轮红 → A/B 对照实验归因环境噪声（非代码回归）**：
   首轮全量 ctest 红（overBudget 1-3、best 665-669µs 远低于 1ms 门）。停 daemon 安静环境
   6 连跑仍红 → 布局假设排除（stash 三布局文件回退仍红）→ **B8 原版代码同环境 5 连跑
   4/5 红（max 884-1353µs）**——`overBudget==0` 断言对单样本调度尖峰敏感，本机当前
   环境噪声水平较 B8 交付时显著升高，两版 best 持平（656-666µs vs B8 659.4µs）证明
   热路径零变化。安静窗口全量 ctest **1494/1494 绿**（含该 bench）。
2. **JVM 全量七轮迭代**（每轮红均归因修复后重跑）：①新测试缺 import → 补；
   ②`ELDER_SKILL_BASELINE` 归属误记（PolicyConfig 非 Disciple）→ 修正；
   ③parity 测试相对路径（app 工作目录读 engine 源需 `../core/`）→ 修正；
   ④`VassalServiceTest` 四处未跟上改读流水 → 同步；⑤`DiscipleMergeCoverageTest`
   守卫集合含退役字段 → 按守卫三要素移出并注记；⑥`V61_DISCIPLES_DROPPED_COLUMNS`
   可见性 private → internal；⑦v60To61 种子期望笔误 [1,2,0]→[0,1,2] +
   v58To59 全链断言合法波及 → 修正。终轮全量绿 + engine/domain 定向复跑绿。
3. **detekt 返工**：新增 5 处违规（GameConfig.kt 折行 MaxLineLength ×5、
   GameEngineLoadDataOps TooManyFunctions 15）——前者折行修复、后者将两个同族读档
   锚定补丁合并为 `initLegacySaveMonthAnchors()` 单入口（语义聚拢非打补丁）。
4. **🔴 stash pop 部分落盘事故与完整重建**：bench 归因 A/B 实验中的两次 `git stash
   push`/`pop` 往返后，第二次 pop 实际未把 include/src 11 文件改动落盘（命令尾部
   `tail -2` 截断掩盖了报错），构建报 `processReflectionRelease` 缺失暴露。处置：
   以本会话完整实施记录**逐文件重建全部 11 文件**（内容与丢失前一致），重建后
   ctest 1494/1494 全绿复验。**工作树已核对无缺漏**（57 修改 + 6 新文件与实施
   footprint 一致）。

## 六、途中发现（公约 §12 登记，不在派发清单未动）

1. **前会话遗留 stash@{0}**（`WIP on feat/realtime-settlement: 831780839`，含
   include/src 11 文件一版未提交改动）——系本会话之前因 exceed quota limit 中断的
   B9 会话所留（看护轮#11 台账在案）。本会话独立重做全批工作，未动该 stash；
   **请看护/用户裁决**：确认与已交付本批重复后 `git stash drop`，或保留至 B10 后清理。
2. **`ProductionProcessor.processAutoAlchemy` / `CultivationService.processAutoAlchemy`
   生产零调用**（仅 ProductionSlotDualWriteGuardTest 消费）——autoRestart 续炼启动
   仅 C++ 离散臂承担，Kotlin 侧为预留死代码。建议 B10 或装备阶段裁决（删或接）。
3. **`overBudgetCount == 0` 断言噪声脆弱**（B8 门禁面）：min-of-15 只保护 bestUs 断言，
   遥测逐样本计数断言在任何单次调度尖峰即红（本批实证）。建议（不在本批动）：
   阈值化（如 overBudget ≤ samples/9）或 CI 隔离环境专跑。
4. **C++ 年变 T1 缺 `discipleAging` 非行为缺口**：Kotlin 侧该子项 =
   `syncAllDiscipleStatuses()` 状态重推导（幂等纯派生）；C++ 列存储权威维护状态，
   无需重推导。已在 year_settlement.h 头注释登记口径。

## 七、边界遵守声明

- 版本号未动（`version.properties` 未改）；双 changelog 均为条目内追加/版本段内插入。
- 灰度旗标 `NativeEngineFlag.realtimeAccrual` 未动；存档 schema 变更仅 v60→v61 删列
  （migration + 集成测试齐备，备份恢复链不变）。
- detekt baseline 未动（新增违规全部修复未入 baseline）。
- 构建副产物（atlas-rgba-manifest.json）已 `git checkout --` 还原，未混入收官笔。
- Room 版本 v61 被实时线占用 → **装备阶段 Room 版本顺延基线为 v62 起**
  （台账 §8「以合入时刻为基准」规则天然消化，知会看护）。
