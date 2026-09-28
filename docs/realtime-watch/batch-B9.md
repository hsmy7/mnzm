# 派发件 · B9 测试基准重建 + 遗留清理（实时结算线）

> 本文件为派发文本主体；文末《前批交付事实附录》由看护派发时从前批报告提取 6–8 条追加后全文粘贴。

## 0. 工作区与纪律（每批相同）

- 唯一工作区：`C:\Mnzm\XianxiaSectNative-realtime`（git worktree），分支 `feat/realtime-settlement`。开工第一件事：`git branch --show-current`（必须 = feat/realtime-settlement）+ `git status`。**禁止在主树 `C:\Mnzm\XianxiaSectNative` 改任何文件。**
- 本会话只实施 **B9 一批**（实时结算方案 §10 对应行），完成即收官提交；不开下一批、不顺手做其他批。
- 实施前先读：方案 `docs/realtime-settlement-plan-2026-09-27.md`（§3.4 结算项穷尽清单、§5.2 对拍口径、§9.1 缺陷清单、§10 B9 行）+ `docs/realtime-watch/DISPATCH-LEDGER.md`（只读；异议写其 §9 留言区）+ 本文件附录。
- 报告：`docs/report-B9.md`，含门禁实测原数字（禁「应该通过」措辞、禁 `{{...}}` 占位符残留），随收官笔入库。
- 收官：单笔提交，格式 `feat(test): B9 测试基准重建+遗留清理——<要点>`；内容含代码+测试+报告+双 changelog（**版本号不自增**）。
- 构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）提交前 `git checkout --` 还原。
- 台账状态 `accepted` 由看护亲验后设置，实施会话不得自设。
- 通用红线（AGENTS.md §5）：禁 `!!`/`runBlocking`/空 catch；跨域新增枚举/配置必写守卫测试；detekt baseline 只缩不增；C++ 优先；RNG 分区红线。

## 1. 批次任务（方案 §10 B9 行原文）

> | 批 | 内容 | 关键文件 | 验收标准 |
> |---|---|---|---|
> | **B9 测试基准重建 + 遗留清理** | 按 §3.4 清单重定基准；清理死值/死代码（`cultivationCompletionPhase`、`RealmConfig.maxAge`）；修复 `reflectionRelease` 缺口与两处时基 | 全仓测试 | 六模块全绿 + GTest 全绿 + detekt/lint 绿；`node scripts/check-agent-instructions.mjs` 绿 |

补充要点（档案）：

- A 类缺陷清偿范围（方案 §9.1 共 17 条）：**1-4/12/15/17 由本批清偿**；5/6/7/8 已随 B5 闭合（勿重做）；10（cultivationCompletionPhase 死值）随本批死值清理一并。
- 已下线系统死常量一并清（`CURFEW_DESERTION_REDUCTION` 等背叛/偷盗/执法残留，方案附录 B 末行指名随 B9 清理）。
- 基准重定按 §3.4 结算项穷尽清单逐项核对；对拍口径分三类（§5.2）——积分轨等价 / 判定轨逐位 / 派生投影容差，勿混用。
- 清理死代码时同步删对应测试与文档引用，`check-agent-instructions` 死链门禁绿为准。

## 2. 门禁命令（收官前全跑；报告记实测原数字）

在 worktree 的 `android/` 下执行；测试一律 `--max-workers=1`：

1. `./gradlew.bat compileReleaseKotlin`
2. `./gradlew.bat testReleaseUnitTest --max-workers=1 -Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`（六模块全量，**必须含 feature:game**；**必须带该参数**——IN8 出厂门要求全量跑也带参，防 45 个 Diff*Test 静默 skip，B7 实证不带必红）
3. 若触碰 C++：先 `pwsh -NoProfile -File scripts/build-desktop-jni.ps1`，再跑 engine Diff 门（`-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`）
4. `./gradlew.bat lintRelease detekt`
5. 桌面 ctest 全量（基线以附录为准）：llvm-mingw PATH 必带
6. worktree 根：`node scripts/check-jni-count.mjs` + `node scripts/check-agent-instructions.mjs`（如报缺依赖，从主树拷 `scripts/node_modules`）

## 3. 环境教训（必读，前车之鉴）

- ctest 缺 llvm-mingw PATH = 0xc0000135 假红；改 C++ 后忘重跑 `build-desktop-jni.ps1` → Diff 全系假红。
- feature:game 全量 Robolectric 多 daemon 并存会 OOM：跑前清别线 java 进程；daemon 卡死 → 杀本工作区相关 java 重跑。
- lintRelease/构建触碰 `atlas-rgba-manifest.json` 属构建副作用，checkout 还原勿混入提交。
- 不新增结算循环/新线程 tick；诚实纪律：门禁失败须归因入报告。

## 4. 前批交付事实附录（B8，看护填）

1. B8 收官笔 `2f3bef8d0 feat(ui): B8 UI 与遥测——旬进度→时间进度投影（phaseFraction/3f 退役）+ GameViewStore 块① HUD 迁移 + 积分段遥测与 bench 门禁 <1ms@5000 + 孕养 O(I) 扫描缺陷修复（167ms→5.9ms）`（21 文件 +854/−43）；工作树当前净。
2. **门禁基线更新**：ctest **1483**/1483（GAMECORE_BUILD_BENCH 本树 CMakeCache=ON 翻开，bench 目标 10 项在列；仓库 option 默认不动、CI 同 ON）；六模块 JVM **7536/0/18**（app 1019/0/2、data 818/0/15、domain 1590/0/0、engine 2961/0/1、ui 155/0/0、feature:game 993/0/0；45 个 Diff*Test 全实跑）；jni-count **88/88**；detekt 六模块 0（baseline 全 0 守卫）；lint 存量警告（app 37/data 5/domain 1/engine 1/ui 3/feature:game 52）。
3. **投影族与判据族勿混**：`TimeProgressUtil`（monthProgressFraction/slotProgressFraction）为 INV-1 派生投影族；`GameTimeClock.phaseProgressFlow` 为旬内进度单一真源（AUTHORITATIVE 每帧镜像刷新/OFF 臂 tick 刷新/暂停恒 0）——B9 重定基准按 §3.4 清单逐项归类，投影族对拍口径用容差类，判定轨仍逐位。
4. **月界收获判据不变**：槽位剩余月仍按月界收割（B5 口径），进度只是投影——B9 基准重写时判定轨口径严禁顺手连续化（方案未采纳项）。
5. **孕养扫描缺陷已修**（`InstanceBuckets.findMutable`，全实例 167ms→6.0ms）；**残余 D1 债登记不修**：全实例形态积分段 6.0ms@5000 > 1ms（桶按 tick 重建+全速率链重算），偿付方向 = 方案 §7.2（只积分活跃实体+列级脏导出+速率缓存）独立批——B9 勿顺手清偿。
6. **bench 门禁已立且机器余载敏感**：`SegmentUnderBudgetAt5000` 硬断言 <1000µs（3 预热+min-of-15）；B8 曾遇构建余载下一次性假红后加固——B9 跑 ctest 遇 bench 假红先看采样深度与负载再归因。GAMECORE_BUILD_BENCH 本树已 ON 勿关。
7. **AccrualTelemetry 遥测面已建**（lastSegmentUs/maxSegmentUs/samples/overBudgetCount；超预算经 TelemetrySink 节流上报 `engine_accrual_over_budget`）；Dev 构建轴-日历锁步断言在 accrue 内（Release 门由 `GameCoreTest.AccrualTelemetryAndAxisCalendarLockstep` 外部复断）——B9 清理死值时此断言与遥测面属新增活性面勿删。
8. **D2 债既有勿混入**：AlchemySlot/ForgeSlot/CultivatorCave/灵田年月整数模型为 D2 登记项（方案明确不动）；真机 pending-device 仅 D8（B7 遗留），B8 零新增。
