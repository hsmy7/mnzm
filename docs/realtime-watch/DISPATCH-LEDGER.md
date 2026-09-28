# 实时结算线批次看护台账（DISPATCH-LEDGER）

> **唯一权威状态源**。看护自动化每轮先读本文件再行动；实施会话只读（§9 留言区可写留言）。
> `accepted` 只能由看护亲跑核验后设置；实施会话自设无效（G 线 B11-B15 历史教训）。
> 看护只写本目录与台账；绝不代实施会话改代码。

## 0. 当前状态（事件倒序，最新在上）

- **2026-09-29 01:19 看护轮#14**：**B10 进入收尾期**——footprint 全部落盘 **16 项**（根+core/data+core/engine 三份 AGENTS.md、rules 四件 playbook/economy/ad-cooldown/pr-checklist、docs 六件 architecture/knowledge-base/cpp-engine/platform-abilities/threading-contract/ui-read-surface、CODE_WIKI、**双 changelog 在改**），无报告，**近 12 分钟 409 构建产物 = 全量门在跑**（B10 未触 C++，应为 compile+JVM+lint/detekt 链）。按 §7 本应切 10 分钟轮，🔴 **CronUpdate 连续 5 次误发为 CronList**（通道故障第四起/本轮第二形态，同轮#1 Snapshot×7、轮#3 CronUpdate×4；按「停止重试不硬闯」收手）——**节拍维持 30 分钟轮**，故障与待执行切换已登记，下轮 fire 首件事重试 CronUpdate（id=automation-9496ab85，interval=10，title 每10分钟一轮）。验收质量不受影响（报告落盘到收官本有 25–30 分钟窗口）。下轮：报告落盘/收官笔 ⇒ §5 核验（B10 盯点：规范与代码零冲突的 12 处锚定面、agent-instructions 死链门、双 changelog 收口）。
- **2026-09-29 00:50 看护轮#13（主树键自动化首轮 fire）**：B10 健康转入实施期——派发后 18 分钟勘察即完成（快于 B7/B8 代码批先例，文档批必读面小），任务面板 **3/9**：冲突面扫描完成并锚定 **12 处文件**（grep elapsedGameMs/advanceByGameMs 等死值与口径残留），开始改 rules 四件（playbook/economy/ad-cooldown/pr-checklist，`expansion-playbook.md` 编辑已落盘），后续两份 AGENTS.md（根+engine）+ docs 五件（architecture/knowledge-base/cpp-engine 等）。GUI 判活实证活跃（编辑+思考接连），GLM-5.3-Flash ✓，树面零星落盘属实施起步。无异常不干预，**保持 30 分钟轮**。下轮：预期 rules/AGENTS/docs 面铺开+双 changelog；报告落盘 ⇒ 切 10 分钟轮（主树键下 CronUpdate 已可执行）。
- **2026-09-29 00:2x 看护轮#12（用户指令轮：看护必须可替换 + B9 核验）**：① **看护自动化迁主树键重建**（用户指令「重新调整，我要求必须可替换看护轮」）——新自动化 `automation-9496ab85-0f9e-48d6-bb09-bbf114f5ed3a`（30 分钟/轮，**主树项目键下 CronList 实证可见可改可删**，间隔动态调整规则从此可执行；fire 提示词已刷新：进度锚点重写+装备 Room v62 起+可替换声明）；旧键自动化本侧不可见（疑已被用户删除——轮#11 23:07 由其 fire 而主树侧 CronList 即为空；若自动化页见重复看护请用户手删，或由其 fire 的轮次 CronDelete 自清，勿双跑）。② **B9 交付核验通过 → ✅ accepted（看护亲验）**。三要素全过：收官笔 `17161f5fc`（64 文件 +5917/−245，双 changelog+报告 144 行+61.json+GameDatabaseMigrationsV61+RoomMigrationV60To61Test 在笔内，atlas 未混入）；报告原数字齐无占位符、假红/返工五项诚实归因（bench A/B 对照实验、JVM 七轮迭代、detekt 5 处自修、stash pop 事故 11 文件重建复验）；树净。**看护亲跑四门**：ctest **1494/1494**（49.2s，bench 旗标 ON；首跑 SegmentUnderBudgetAt5000 红→复跑即绿，亲历坐实报告噪声归因）；jni-count **88/88**；agent-instructions 全绿；`DiffAuthoritativeTickTest` 单类亲跑绿（-Dgamecore.jni.path 打 22:51 .so——.so 新鲜度硬证据，stash 重建内容一致性经此验证成立）。③ **stash@{0} 裁决：已 drop**（B9 会话 §9 请示项；取证=创建于 23:08:29 系本会话 bench A/B 实验、含 processReflectionRelease 与 #10 除名、同一交付工作较早 WIP 快照无独有内容；核验通过后按其请求清理，短期可从 2c09ecd3b 找回）。④ **§9 留言处置**：装备 v62 知会与 §8 一致 ✓；途中发现三项（processAutoAlchemy 死代码/overBudgetCount 断言噪声脆弱/discipleAging 口径）已提炼入 B10 附录移交裁决。**B10 已派发成功（00:3x，GUI 六步全过）**：新会话「# 派发件 · B10 文档与规范（实时结算线末批）」挂 XianxiaSectNative·main、GLM-5.3-Flash ✓；派发文本 = batch-B10.md 全文 + B9 附录 8 条（剪贴板回读头尾一致，发送后「工作中」确认、已开始读题思考）。**B10 派发回实施期 ⇒ 30 分钟轮不变**（automation-9496ab85 现节拍即 30 分钟，无翻转）。下轮预期：B10 深读勘察期（方案 §3.6 + 台账必读），零落盘属正常；报告落盘 ⇒ 切 10 分钟轮（主树键下 CronUpdate 现已可执行）。
- **2026-09-28 23:1x 看护轮#11（新看护首轮）**：**B9 中断已自愈，无需干预**——GUI 判活实证 B9 会话活跃运行中（终端命令执行中+思考块接连，模型 chip=GLM-5.3-Flash ✓，未发「继续」）。树 57 项深落盘，footprint 与 B9 对应：A 类缺陷 #10 `cultivationCompletionPhase` 死值退役⇒**Room v60→v61 迁移合法连锁**（rebuild-table create-copy-drop-rename+`.pre_migrate_backup.v60` 备份+C++/Proto/Room/镜像全链除名，`DATABASE_VERSION=61`，读 migration KDoc 确认合规）；**双 changelog 在改**；任务面板 **5/10**（compileReleaseKotlin+build-desktop-jni 已过，余六模块全量/lint+detekt 待跑）。**bench 假红深勘中**：安静环境仍 5/6 红→排除负载归因，正做决定性 A/B 实验（stash 暂回 C++ 主源码改动跑 B8 原版 bench 同环境对照）——方法论正确，不干预。🔴 **装备阶段 Room 版本顺延修正：实时线 B9 已合法占 v61 ⇒ 装备 B1 起 v62/v63/v64 顺延**（§8 已同步改）。**切 10 分钟轮未成**（双 changelog+门禁期信号本应切换，但 CronList 在本会话工作区键=主树 `XianxiaSectNative` 下返回空——本自动化登记在 realtime worktree 项目键下，主树侧不可见不可改；按「停止重试不硬闯」纪律维持原节拍，登记待以 worktree 为工作区的会话或用户调整）。下轮：报告落盘/收官笔 ⇒ 直接走 §5 核验（盯点：bench 假红归因链、v61 迁移测试 RoomMigrationV60To61Test、migration schema 61.json 与 Entity 一致性）。
- **2026-09-28 22:1x** **看护交接**：原看护会话与自动化 `automation-3800a2a7` 已退役（用户指令：移除切换套餐要求、新开会话重建看护）。**B9 现状：约 22:0x 因「exceed quota limit」中断（TraceID: hydrate-trace），中断时树净零落盘（尚处深勘察期，A 类缺陷 #12/#15 已定案、#17 勘察中）**——新看护首轮应：定位 B9 会话（「# 派发件 · B9 测试基准重建 + 遗留清理」）→ 确认模型为 GLM-5.3-Flash → 输入「继续」令其续跑（模型切换动作已按用户指令移除出看护职责）。
- **2026-09-28 21:52 看护轮#10**：**模型锁死规则首次核验通过**——B9 会话右下角 chip = GLM-5.3-Flash ✓（无需切换）。B9 深勘察活跃实证：A 类缺陷清单逐条定案中（#12 后台纯暂停口径定案、#15 补 C++ 侧 GTest 校验面、#17 死值勘察），终端与思考接连执行；树 0 项属勘察期正常（B7 32min/B8 63min 首盘先例）。无异常不干预，保持 30 分钟轮。

- **2026-09-28 21:4x** 用户指示（🔴 收紧，已固化 §3 第 4.5 条 + 看护自动化提示词）：**所有会话一律使用 BigModel 的 GLM-5.3-Flash，禁止使用其余套餐或模型**——派发后与每轮判活核对模型 chip，发现非 GLM-5.3-Flash 即点击 chip → 选择器切 BigModel GLM-5.3-Flash；中断/额度处置不变（停止发「继续」、报错先切模型再「继续」）。

- **2026-09-28 21:36** **B9 已派发成功**：新会话「# 派发件 · B9 测试基准重建 + 遗留清理」（侧栏运行标记确认），挂 XianxiaSectNative · main、GLM-5.3-Flash；派发文本 = batch-B9.md 全文（含 B8 附录 8 条，剪贴板 4265 字符回读首尾一致，输入框尾部逐字核对后发送）。🔴 **通道故障第三形态登记**：Snapshot 语义树因 UI 内 emoji 代理字符报 UnicodeEncodeError 无法序列化——本轮降级 Screenshot 像素路线完成六步（坐标截图直读+每步截图验证，项目此番自动挂上免选）。**B9 派发回实施期 ⇒ 保持 30 分钟轮。** 下轮预期：B9 深读勘察期（A 类缺陷清单 §9.1 必读面大），零落盘属正常；判活先 GUI 后旁证（旁证假阴性教训轮#6 已档）。

- **2026-09-28 21:21 看护轮#9：B8 交付核验通过 → ✅ accepted（看护亲验）**。三要素全过：①收官笔 `2f3bef8d0 feat(ui): B8 UI 与遥测——旬进度→时间进度投影（phaseFraction/3f 退役）+ GameViewStore 块① HUD 迁移 + 积分段遥测与 bench 门禁 <1ms@5000 + 孕养 O(I) 扫描缺陷修复（167ms→5.9ms）`（21 文件 +854/−43，含双 changelog+报告 164 行）；②报告原数字齐无占位符、假红三项诚实归因（ctest 采样不足加固/detekt LongMethod 收敛/JVM 2x 折算自纠）、五项口径决定显式声明；③树净（atlas 已还原）。**看护亲跑抽验三门全绿**：ctest **1483/1483**（GAMECORE_BUILD_BENCH=ON 确认，bench 10 项在列，47.2s）、jni-count **88/88**、agent-instructions 全绿；.so mtime 20:49 实证重建。**重大交付亮点**：孕养 O(I) 扫描缺陷根因修复（InstanceBuckets.findMutable，全实例 167ms→6.0ms 28×）+ bench 门禁 659.4µs@5000 绿 + D1 债桌面数据点落地（残余 6.0ms 登记 §7.2 方向独立批）。**B9 随后派发**（附录 8 条已从 B8 报告提取）。

- **2026-09-28 20:51 看护轮#8**：**B8 全面铺开**——树 7→19 项：Kotlin UI 面到位（§10 指定关键文件 `ProductionTheme.kt`/`SectInfoCard.kt` ✓ + 炼丹/锻造对话框进度显示 + 新投影工具 `TimeProgressUtil.kt` + GameTimeClock/GameEngine + 对应测试扩展）+ C++ bench 面延续。构建在跑（45 产物/12min）；`atlas-rgba-manifest.json` 构建副作用再现——**收官还原盯点在册**。无报告无收官笔 → 实施期，不干预，保持 30 分钟轮。下轮：报告落盘 ⇒ 切 10 分钟轮；bench 数字（积分段 @5000）届时为核验重点。

- **2026-09-28 20:22 看护轮#7**：**B8 首批落盘**（派发后 63 分钟，含勘察+设计期比 B7 慢属正常）——树 7 项 C++ 面先行：`game_core.h/.cpp`/`phase_settlement.h`/`instance_buckets.h` + `game_core_test.cpp` + **新 `test/bench/accrual_segment_bench_test.cpp`**（bench 门禁底座 = 验收「积分段 < 1ms@5000」的落点）+ bench CMakeLists 同步。实施顺序合理（先立 bench 门禁再改 UI 投影）。无报告无收官笔 → 实施期，不干预，保持 30 分钟轮。下轮：预期 Kotlin UI 面（ProductionTheme/SectInfoCard）与 GameViewStore 镜像面加入；报告落盘 ⇒ 切 10 分钟轮。

- **2026-09-28 19:51 看护轮#6**：三重静默三要素表面齐（派发 32 分钟树 0 项/无报告/build 0 + agent 流 19:3x-19:4x 为零）⇒ GUI 判活，**活性实证推翻判停**——B8 会话视图中：双长思考块进行中（79s/59s）、「2/8 项已完成」任务清单推进、勘察面 = accrue 积分段现状/GameViewStore 镜像面/GameData 时间进度投影（恰为 B8 任务面）、正核对派发件原文与 B7 报告格式、**实施设计已定随即开工**。判定：实施期深勘察尾部，不干预，保持 30 分钟轮。**旁证法修正入档：zcode-agent.respond 计数在长思考/长命令勘察期会假阴性（B8 19:3x-19:4x 实证）——停滞判读必须先 GUI 判活再定，日志旁证仅辅助。**下轮：B8 预期已落盘（首批文件随时出现）；若 GUI 仍活性而树持续 0 到派发后 60 分钟，再评估。

- **2026-09-28 19:19** **B8 已派发成功（看护桌面 GUI 六步全过，同轮内完成）**：新会话「# 派发件 · B8 UI 与遥测（实时结算线）」，挂 XianxiaSectNative 项目（主树 main 不动）、GLM-5.3-Flash；派发文本 = batch-B8.md 全文（含 B7 附录 8 条 + 门禁第 2 条 jni.path 补正，剪贴板 4291 字符回读首尾一致），发送后「工作中」确认。**B8 派发回实施期 ⇒ 保持 30 分钟轮（间隔无翻转，未动自动化）**。下轮预期：B8 深读规划期（树零落盘属正常，参照 B7 节奏 30+ 分钟才首批落盘）；判活用日志旁证（GUI 通道轮#4 已恢复但仍须防误发，控制调用密度）。

- **2026-09-28 19:13 看护轮#4：B7 交付核验通过 → ✅ accepted（看护亲验）**。三要素全过：①收官笔 `568c01921 feat(engine): B7 离线语义——12h 全额+50% 至 24h 硬顶折算 + GameCore::injectOfflineGameMs 注入路径 + 云游归来 UI 提示`（29 文件 +1270/−7，单笔含双 changelog+报告 175 行+threading-contract 登记+jni-count 基线同步）；②报告 `docs/report-B7.md` 门禁原数字齐、无占位符、假红诚实归因（IN8 跑法/detekt 4 自引违规/C++ 测试 2 断言自纠）；③树净——atlas 副产物未混入收官笔。**看护亲跑抽验三门全绿**：ctest **1473/1473**（=1465+8，47.6s）、check-jni-count **88/88**、check-agent-instructions 全绿；.so mtime 17:59 实证 build-desktop-jni.ps1 已重跑。预载盯点双销：jni-count 豁免理由在报告 §1.4；边界决定遵守（未顺手实施未采纳项、schema 零变更、旗标未翻、版本号未动）。§9 留言建议已采纳：batch-B8/9/10 门禁第 2 条补 `-Dgamecore.jni.path` 参数（本轮改）。遗留移交：D8 真机项 pending-device。**B8 随后派发（回实施期，保持 30 分钟轮）。**
- **2026-09-28 18:21 看护轮#3**：**B7 进入收尾组装期**——树 23→28 项：**双 changelog 同轮在改**（CHANGELOG.md + changelog_entries.json，G/MR 线实证的收官材料准备期信号）+ 测试面铺齐（新增 engine 侧 `GameEngineCoreOfflineOpsTest`/`GameTimeClockOfflineInjectTest` + UI 件 `OfflineReturnFormatter.kt`）+ `docs/threading-contract.md` 跨线程登记义务履行 + `models.h`/`GameData.kt` 加入。无报告无收官笔，构建 28 产物/12min 在跑；agent 流走低（18:1x=7/18:2x=5）与门禁执行期吻合。⇒ 判收尾期，**应切 10 分钟轮但未成**：CronUpdate 连续 4 次误发为 CronList（🔴 工具通道故障第二起，轮#1 曾 Snapshot×7 误发；处置同轮#1=停止重试不硬闯），本轮保持 30 分钟轮，下轮 fire（~18:51）仍处收尾期则重试切换。下轮预期：收官笔落 ⇒ 直接走 §5 核验（盯点：jni-count baseline 豁免理由 + atlas 副产物还原 + 报告原数字）；若报告落盘未收官 ⇒ 重试切 10 分钟轮。

- **2026-09-28 17:52 看护轮#2**：**B7 实施期健康铺开**——树 23 项与批次任务面精确对应：C++ 离线注入面（`GameCoreBridge.cpp`/`game_core.h`/`engine_loop.h`/`settlement.h`/`game_core.cpp` + 新 `test/offline_injection_test.cpp`）+ Kotlin 面（`GameTimeClock.kt`/新 `GameEngineCoreOfflineOps.kt`/`GameEngineCoreAuthoritativeOps.kt`/`GameConfig.kt`/GameEngine 双件 + UI 面 GameViewModel/MainGameScreen + 新 `OfflineProgressPolicyTest.kt`）。**核验盯点预载**：①`scripts/jni-count.baseline.json` 已被改——核验时必须见报告豁免理由；②`atlas-rgba-manifest.json` 在树——收官前必须还原。构建在跑（近 12 分钟 48 产物）、agent 流 17:2x–17:5x 持续（46/34/37 次）。无报告无收官笔 → 实施期，不干预，保持 30 分钟轮。下轮：报告落盘 ⇒ 切 10 分钟轮准备核验。

- **2026-09-28 17:22 看护轮#1（自动化 fire）**：CLI 探测 = 收官笔未落、树 0 项、无报告、build 近 12 分钟 0（派发起累计零落盘 32 分钟）。🔴 **本轮 GUI 快照通道故障**（连续 7 次工具误选，遵守纪律未盲试未盲发）——改用 ZCode 日志旁证（`~/.zcode/v2/logs/2026-09-28.log`，注意 captcha 噪音须用 `zcode-agent.respond` 精确标签）：17:0x/17:1x（看护会话空窗期）agent 响应 19/11 次、**17:19:27 trace 515e389b 模型流式交互在途** ⇒ 判定 B7 会话活性有实证、处于深读/规划期（B 线方案 1200+ 行 + 台账必读），未达判停，**不干预不发「继续」**；保持 30 分钟轮。下轮规则：若仍零落盘且 17:2x 后 agent 流停止 ⇒ 恢复 GUI 通道判活后按 §3.4.5 发「继续」；GUI 仍故障则继续日志旁证并顺延干预；连续 2 轮零落盘+零 agent 流 ⇒ 台账登记后重派。

- **2026-09-28 16:5x** 用户指示（已固化 §3 第 4.5 条 + 看护自动化提示词 4.5）：桌面渠道当前用**体验套餐**——实施会话模型中断 ⇒ 输入「继续」令其续跑；体验套餐**额度归零** ⇒ 点击会话右下角模型名称按钮选 **BigModel 的 GLM-5.3-Flash** 继续实施。
- **2026-09-28 16:50** **B7 已派发成功（看护桌面 GUI 六步全过）**：新会话「# 派发件 · B7 离线语义（实时结算线）」，挂 XianxiaSectNative 项目（主树 main 不动）、GLM-5.3-Flash；派发文本 = batch-B7.md 全文（含 B6 附录 8 条，剪贴板 4075 字符回读首尾一致），发送后「工作中」确认、会话已正确复述 B7 任务面。**看护自动化已建：`automation-3800a2a7-f3c6-45e0-b6af-f90f0257a3ab`（30 分钟/轮，收尾期自动切 10 分钟/轮，间隔调整规则见 §6）。**
- **2026-09-28 16:35** 看护自动化建立（节奏 30 分钟/轮，收尾期自动切 10 分钟/轮）+ 本台账与 B7–B10 派发件入库（`80177c520`）。**用户已停止全部在途会话**（含此前直派的「在对应工作区继续实施realtime-settlement-plan-2026-09-27.md」实施会话——该会话仅勘察未落盘，看护派发前核实树净）。看护接管派发。
- 锚点：B1–B6 已交付（最新收官笔 `d01fdfb91` B6；ctest 1465/1465 + 六模块 JVM + detekt/lint + jni-count 87/87 全绿，工作树净）。

## 1. 固定事实

| 项 | 值 |
|---|---|
| 方案真源 | `docs/realtime-settlement-plan-2026-09-27.md`（批次编排 = §10，B1–B10） |
| 工作区 | `C:\Mnzm\XianxiaSectNative-realtime`（git worktree），分支 `feat/realtime-settlement`（基线 175bf2ff1） |
| 报告路径约定 | `docs/report-B7.md` … `docs/report-B10.md`（实施会话写，随收官笔入库） |
| 派发件 | 本目录 `batch-B7.md` … `batch-B10.md`；派发时若附录为占位，则由看护从前批报告提取 6–8 条《前批交付事实附录》追加于文末后全文粘贴 |
| 桌面渠道 | ZCode 桌面 app；**模型锁死 BigModel GLM-5.3-Flash（用户 2026-09-28 指示，禁用其余套餐/模型，判活时核对 chip）**；无头 `--prompt` 不可用（选路写死，勿走） |
| 门禁基线（B9 后） | ctest **1494**/1494；jni-count **88/88**；六模块 JVM 全量含 feature:game（**B 批门禁必须含 feature:game**——B5 教训）；detekt 六模块 0/0；lintRelease 绿；engine Diff 门必带 `-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so` |

## 2. 批次状态表

| 批 | 内容（§10） | 状态 | 收官笔 | 报告 | 看护核验 |
|---|---|---|---|---|---|
| §2.6 | 自动存档现实时间化 | ✅ 已交付 | e555b9964 | 随笔 | 随线自证（开关方案；并回时与主线显式启动案冲突→§7） |
| B1 | 时间语义基座 | ✅ 已交付 | f939c6063 | —（早期批） | 随线自证 |
| B2 | 时间推进层 | ✅ 已交付 | 06f2e7be1 | — | 随线自证 |
| B3 | 存储底座 v60 | ✅ 已交付 | 9eeca9df9 | — | 随线自证 |
| B4 | L1 连续积分轨 | ✅ 已交付 | 9a00b2ded | — | 随线自证 |
| B5 | L2 差分轨 | ✅ 已交付 | 3762b14ab | — | 随线自证 |
| B6 | L3+L4 边界层拆分 | ✅ 已交付 | d01fdfb91 | — | 随线自证（ctest 1465 全绿） |
| B7 | 离线语义 | ✅ accepted（19:13 看护亲验） | 568c01921 | docs/report-B7.md | 通过：ctest 1473/1473+jni-count 88/88+agent-instructions 亲跑全绿；atlas 未混入；豁免理由在 §1.4 |
| B8 | UI 与遥测 | ✅ accepted（21:21 看护亲验） | 2f3bef8d0 | docs/report-B8.md | 通过：ctest 1483/1483（bench 翻开）+jni-count 88/88+agent-instructions 亲跑全绿；孕养 O(I) 缺陷修复 28×；D1 债桌面数据点落地 |
| B9 | 测试基准重建+遗留清理 | ✅ accepted（00:2x 看护亲验） | 17161f5fc | docs/report-B9.md | 通过：ctest 1494/1494 亲跑（首跑 bench 噪声红复跑绿）+jni-count 88/88+agent-instructions 绿+DiffAuthoritativeTickTest 单类打 .so 绿；stash@{0} 裁决 drop |
| B10 | 文档与规范 | 🔄 在途（00:3x 看护派发） | — | — | 待交付 |

> B1–B6 交付于自动化建立之前，门禁数字见 git log 各笔提交说明；本表只回溯登记。

## 3. 看护轮操作规程（每轮 fire 按此执行）

1. **先读本台账全文** + **完整读 fire 文本**（末尾可能有用户追加指令，以最新指示为准；说「暂停」= 删看护自动化、完全停手，子会话不受影响照常跑）。
2. **轻量 CLI 探测**（Git Bash 原生命令，勿 pwsh 启动开销）：`git -C <worktree> log --oneline -5`、`git status --porcelain | wc -l`、报告文件存在性与 mtime、构建产物计数 `find android/app android/core/domain android/core/data android/core/engine android/core/ui android/feature/game -maxdepth 4 -path "*build*" -newermt "-12 minutes" -type f 2>/dev/null | wc -l`（**全树扫描禁用**——曾超 2 分钟不可用）。
3. **GUI 只读判活**：`Snapshot` 看 ZCode 窗口（会话「工作中」计时/思考/工具详情）；**禁止盲发**；写操作只在派发与记账时做。
4. **状态分类**：
   - **实施期**：无报告文件、代码面渐增、构建偶发 → 只观察，不记账不提交。
   - **收尾期**：报告已落盘未收官 / 近 20 分钟构建产物数百（全量门在跑）/ 双 changelog 在改 → 高频观察（切 10 分钟轮，§6），准备核验。**文件面静默 ≠ 停滞：先跑 build 计数再考虑截图**（G 线两次实证：报告落盘到收官间隔 25–30 分钟，中间必有第二波全量门）。
   - **交付**：收官笔落 + 报告在 + 树净（§5）→ 走 §5 核验。
   - **停滞**：三重静默判停（报告缺 + build 近 20 分钟 0 + 改动面不增长）⇒ 被动截图核活性；连续 2 轮无进展 ⇒ 台账登记 + 决定重派（重派前先保全现场：未提交成果勿动勿删，续作指令写 §9 留言区）。
4.5. **🔴 模型约束与中断/额度处置**（用户 2026-09-28 两次指示合并，后条收紧前条）：
   - **模型锁死**：所有会话一律使用 **BigModel 的 GLM-5.3-Flash**，**禁止使用其余套餐或模型**（含体验套餐自动分配的其他模型）。派发新会话后与每轮判活时核对会话右下角模型名称 chip；发现非 GLM-5.3-Flash ⇒ 点击该 chip → 弹出选择器选 **BigModel 的 GLM-5.3-Flash**。
   - 实施会话**已停止**（无「工作中」计时）但批次未交付（无收官笔/报告不全/树未净）⇒ 定位该会话输入框输入「**继续**」发送令其续跑。
   - **额度/余额/套餐报错征兆** ⇒ 先切 **BigModel 的 GLM-5.3-Flash** 再输入「继续」。
   - 处置均记台账（时间 + 处置方式）；处置后下轮验证会话恢复运行；连续 2 轮「继续」无进展按上条重派。
5. **记账纪律**：无事件不写不提交；有事件才倒序追加 §0 + 更新 §2；台账提交 `docs(realtime-watch): ...` 笔，只 `git add docs/realtime-watch`（绝不代实施会话 add 其文件）。
6. **间隔调整**见 §6；**饱和自保**：git 等命令 4.5 分钟不归 = 机器被他线构建饱和，本轮弃权观察、不带病下验收结论。

## 4. 派发操作法（桌面 GUI，全流程截屏验证）

1. `Snapshot` 确认前台窗口身份（ZCode 主窗）。
2. 点侧栏「新建任务」→ `Snapshot` 确认进入新会话输入页（**首点偶尔不生效仅激活窗口**：若粘贴落进旧会话输入框，Ctrl+A+Delete 清空——未发送零副作用——重开新任务重试）。
3. 派发文本 = `batch-Bx.md` 全文（含附录），经剪贴板粘贴（大文本折叠成「粘贴文本 · N 行」芯片属正常）。
4. **发送前回读**：核对输入区开头/结尾与派发文本一致。
5. 点发送 → `Snapshot` 确认开跑（「工作中」计时出现）。
6. 台账登记派发时间与会话名。
- 焦点红线：用户正在用键鼠时暂停写操作等其停下；任一步特征不符即中止重试，禁止盲发。
- 派发文本内已含工作区自检（分支/树况），实施会话开工先自证。

## 5. 交付核验口径

**三要素**：① `git log` 出现本批收官笔（含代码+测试+双 changelog+报告；feat/refactor/docs 前缀皆可，偏差登记）；② `docs/report-Bx.md` 有门禁实测原数字（禁「应该通过」措辞、禁 `{{...}}` 占位符残留——G10 先例判 needs-fix）；③ 树净——实施 footprint 全入库，构建副产物（`atlas-rgba-manifest.json`/`sprite-uid-map.json`/`scene_uv_tables.h` 等 codegen 幽灵 diff）已还原；他线产物不计违规不碰。
**看护抽验**（亲跑）：收官笔 diff 面与批次 footprint 对应；报告关键数字抽 2–3 项复跑（ctest 全量或定向、detekt、check-jni-count 87/87）；触碰 C++ 的批验证 build-desktop-jni.ps1 已重跑（否则 Diff 门假红）；feature:game 全量证据在报告。
**通过 ⇒ §2 置 accepted（看护亲设）+ §0 登记**；不通过 ⇒ needs-fix（派发修复轮或在 §9 留言区通知实施会话自修）。
**连交多批处置**：逐笔收官笔逐批核验；若单笔混多批，按方案 §10 各批验收标准全量核验后整线登记（偏差入 §0）。

## 6. 看护间隔动态调整（用户规则）

- 常态 **30 分钟/轮**。
- 进入**收尾期**（跑测试/门禁/报告落盘未收官/收官组装）→ 切 **10 分钟/轮**：CronUpdate 本自动化 `intervalUnit=minute, interval=10, cron='* * * * *'`，title 同步改「…每10分钟一轮」。
- 新批派发回到**实施期** → 调回 30 分钟（同法，title「…每30分钟一轮」）。
- 只在状态翻转时调整一次，不每轮重复调用。

## 7. 合并手术计划（B10 accepted 后，看护亲自操刀）

1. 前置：主树 `C:\Mnzm\XianxiaSectNative` 在 main 且干净（`git status` 核实；他线会话在途则等下一轮重试）。
2. `git -C C:/Mnzm/XianxiaSectNative merge --no-ff feat/realtime-settlement`。
3. **预期冲突三文件**（§2.6 两案同题不同解）：`SaveLoadViewModel.kt` / `SaveLoadViewModelAutoSaveOps.kt` / `SaveLoadViewModelAutoSaveTest.kt`——**以主线显式启动案为基**（`startRealtimeAutoSaveTicker` + MainGameScreen LaunchedEffect + 60 虚拟秒守卫测试，无全局可变状态），核对 B 线「现实墙钟每 10 秒一存」语义已被主线案覆盖；`realtimeAutoSaveTickLoopEnabled` 开关方案弃用并清残留引用。
4. 并后全门禁：ctest 全量（llvm-mingw PATH）+ build-desktop-jni.ps1（若 C++ 有变）+ engine Diff 门（-Dgamecore.jni.path 指主树桥）+ 六模块 JVM（含 feature:game）+ detekt + lint + check-agent-instructions + check-jni-count。
5. push：`git -c http.proxy=http://127.0.0.1:7897 push origin main`（直连会 reset/超时；失效先 `netstat -ano | findstr 7897` 查 Clash 端口）。
6. 台账记终态笔；`git worktree remove C:/Mnzm/XianxiaSectNative-realtime` + `git branch -d feat/realtime-settlement`（台账已随合并笔在主树同路径）。
7. §0 登记「实时结算线 completed」→ 转 §8 装备阶段。

## 8. 装备系统阶段（实时线并网删支后启动，同看护模型）

- 方案：`docs/design/equipment-set-system-refactor-plan.md`；批次真源：`docs/design/equipment-batches/IMPLEMENTATION-BATCHES.md`（§3 总览、§4 批次详述 TASKBOOK 级、§8 开工检查清单）。
- 批次：**B0 存档编号规划与冻结守卫 → B1 属性机制重构（大，Room 迁移）→ B2 孕养丹退役+补偿 → B3 装备体系原子替换（最大，原子、合并且仅合一次）→ B4 数值对齐与验收 → B5 文档/ADR/双日志/债登记**，**全串行**（§3.3 共享写入面必须串行，禁止并行）。
- 🔴 **Room 版本号**：方案表 v59→v60 系**计划映射**；实际**以合入时刻 `GameDatabaseConfig.DATABASE_VERSION` 为基准 +1**（实时线已占 v60，**B9 死值退役又合法占 v61** ⇒ 装备 B1 起应为 **v62/v63/v64** 顺延，不得预占）。派发件必须写明此规则。
- 工作区：新建 worktree `C:\Mnzm\XianxiaSectNative-equipment`（分支 `feat/equipment-set` 自最新 main）；拷 gitignored 本机件（`android/local.properties`、`keystore.properties`、`api.properties`、`scripts/node_modules`、desktop-jni `.so`）。
- 报告路径：`docs/design/equipment-batches/reports/report-Bx.md`（方案既定）。
- **开工前置（§3.4）**：每批开工第一件事查共享文件是否被他线占用（models.h、CHANGELOG.md、knowledge-base、architecture、存档管线等），命中即**停手报告**。
- 派发件：现场从 IMPLEMENTATION-BATCHES.md §4 对应节装配（纪律前言复用本目录 batch 模板 + §2 门禁命令 + 前批事实附录），存本目录 `batch-EQ-Bx.md`。
- 收官/核验/间隔调整/最终合并删支：同 §4–§7 模型。

## 9. 留言区（实施会话可写；看护会读）

（实施会话如对本批安排有异议或需用户拍板事项，写在此处并遵守：不改台账其他节、不自设 accepted。）

- **2026-09-28（B9 实施会话）**：① **B9 已收官提交 `17161f5fc`**（64 文件 +5917/−245，
  含代码+测试+双 changelog+报告 `docs/report-B9.md`；树净，atlas 已还原），待看护核验。
  门禁实测原数字在报告 §二：ctest **1494/1494**（基线 1483+11）、六模块 JVM **7550/0**、
  detekt/lint 六模块绿、jni-count 88/88、agent-instructions 绿。bench `SegmentUnderBudgetAt5000`
  曾三轮红，A/B 对照实验归因**本机环境噪声非代码回归**（B8 原版同环境 5 连跑 4/5 红、
  两版 best 持平 656-666µs），安静窗口全量绿——归因链在报告 §五.1。② **装备阶段 Room
  版本顺延知会**：实时线 B9 已占 v61（cultivationCompletionPhase 删列）⇒ 装备 B1 起
  应为 **v62** 顺延（台账 §8 规则「以合入时刻为准」天然消化）。③ 🔴 **前会话遗留
  stash@{0} 待裁决**（`WIP on feat/realtime-settlement: 831780839`，include/src 11 文件
  一版未提交改动——本会话之前 exceed quota 中断的 B9 会话所留，看护轮#11 在案）。
  本会话独立重做全批并与该 stash 无依赖，请核验通过后 `git stash drop` 或留 B10 后清理。
  ④ 途中发现三项登记（报告 §六）：`processAutoAlchemy` 生产零调用、bench
  `overBudget==0` 断言噪声脆弱、C++ 年变缺 discipleAging 非行为缺口。
- **2026-09-28（B7 实施会话）**：① **门禁命令补正建议**——派发件 §2 第 2 条「六模块全量
  `testReleaseUnitTest --max-workers=1`」实测会触发 `DiffBridgeGateTest` IN8 出厂门红
  （IN8 要求全量跑也必须带 `-Dgamecore.jni.path=`，防 45 个 Diff*Test 静默 skip）。
  B7 实测：不带参必红（跑法问题非代码问题），带参后全量绿。建议 B8–B10 派发件在该条
  命令补写 `-Dgamecore.jni.path=C:/Mnzm/XianxiaSectNative-realtime/android/core/engine/build/desktop-jni/libgamecorejni.so`
  （改 C++ 的批仍须先重跑 build-desktop-jni.ps1）。② B7 已收官提交（报告
  `docs/report-B7.md`，门禁实测原数字在库），待核验。
