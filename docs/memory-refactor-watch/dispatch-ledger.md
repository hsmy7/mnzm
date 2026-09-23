# 内存重构批次看护台账（MR 系列）

> **唯一权威状态文件**。看护每次状态变更后 git 提交 `docs(memory-watch): ...`。
> 方案 = [memory-refactor-implementation-plan-2026-09-23.md](../memory-refactor-implementation-plan-2026-09-23.md)（任务带 checkbox，Phase 0–4）
> 特性文档 = [compose/spec/memory-management-refactor.md](../compose/spec/memory-management-refactor.md)
> 施工工作树 = `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`，自 main `c9c09f9b3` 分出；`android/local.properties`、`android/keystore.properties` 已手拷且被 .gitignore 覆盖）
> 编排 = 看护 ZCode 会话（cron `*/10` 每十分钟检查一轮）+ 每批一个**新建 ZCode 子会话**（GUI 六步派发），一会话只做一批
> **硬截止 = 2026-09-23 08:50 停工**（用户 04:2x 指令 + 04:3x 澄清：判定条件 **≥ 08:50，等于也停**）——到点看护停派发、停监控、删自动化；已派子会话不受影响照常跑完（SR「暂停」先例）

## 当前状态

（最新在上）

- **2026-09-23 08:2x MR-2 交付核验通过 + MR-3 派发中（看护锁 08:2x，截止前抢派）**：交付三要素齐 = 收官笔 `92a4a8e79`（18 文件：VMA v3.3.0 vendored + GpuAllocator/GpuBudgetMath + 6 站点收口 + staging pool + trim 接线 + 守卫 14 用例 + 报告）+ 完成报告（ctest **1581/1581**=1567+14、Kotlin **8,112/0/17** 零回归、NDK arm64 绿、裸 vkAllocateMemory=0（OFF 轨辅助 3 处 span 白名单锁定）、JNI 门 85/85）+ 工作树回净。**accepted 留用户**。**要点**：① JNI 基线漏更跨批发现（MR1 +2 补登 + MR2 豁免 +1）——**后续批次门禁清单必含 `node scripts/check-jni-count.mjs`**（已入 MR3 附录）；② 双 changelog 豁免有据（默认 OFF 玩家零感知；翻 true 批随该批写）；③ 白纹理 HOST_VISIBLE 落地口径与 P1.2 同类请示项留验收。MR-3 派发文本 = batch-MR3.md + 《前批交付事实》附录 6 条。
- **2026-09-23 07:22 MR-2 已派发（GUI 六步全过，锁解除）**：新 ZCode 子会话《【MR-2 内存管理根治 · Phase 2 GPU 子系统批（P2.1–P2.3）】》开跑实证「工作中 13 秒」，自述先读台账留言与必读文档。派发文本 = batch-MR2.md + 《前批交付事实》附录 6 条（5226 字符剪贴板粘贴，尾部逐字核验）。**注意：当前 07:22，MR-2 为大 C++ 批（VMA vendoring + GpuAllocator + 6 站点收口），大概率跨越 08:50 硬截止——截止到点看护按 §4 停工（台账记在途态、删 cron），MR-2 子会话不受影响照常跑完，交付核验待看护恢复或用户口头驱动。**下轮起【C 监控】MR-2。
- **2026-09-23 07:1x MR-1 交付核验通过**：交付三要素齐 = 收官笔 `c2410bf1c`（37 文件 +1832/-229，七任务+报告+checkbox+双 changelog 单笔，明确文件名 add，副产物已还原）+ 完成报告 257 行（门禁：Kotlin 六模块 **8,112/0/17 skip**、ctest **1567/1567** 含本批 12 新例、check-agent-instructions 过；五轮门禁迭代如实：2 编译错/Robolectric sdk=34/Diff 桥跑法参数/守卫读错文件/detekt 五类风格修复）+ 工作树回净。**accepted 留用户**。**开放项转登记**：① P1.4 GPU 计数 mock 断言弱化（随 MR3 TextureCache 补强）；② P1.2 `releaseBaselines` 显式切换调用点未加（裁量：pushTerrain 覆盖语义已满足，滞留窗 1 帧）——两项均为报告如实请示，非缺陷；③ 工作树 ctest 基线口径 = 1555+12=1567（与 main 差 -6 元数据，文件级零缺失已核）。MR-2 派发文本 = batch-MR2.md + 《前批交付事实》附录（6 条原文摘录）。
- **2026-09-23 05:12 MR-1 已派发（GUI 六步全过，锁解除）**：新 ZCode 子会话《【MR-1 内存管理根治 · Phase 1 止血+压力闭环批（P1.1–P1.7 七任务全做）】》开跑实证「工作中 14 秒」，首动作读台账 dispatch-ledger.md（留言区纪律生效）。派发文本 = batch-MR1.md + 《前批交付事实》附录 5 条（5001 字符剪贴板粘贴，尾部逐字核验）。下轮起【C 监控】MR-1：预期先勘察 trim 消费者全清单（P1.3 最大面），首笔提交预计为施工卡/立卡笔；交付核验口径同 §7。**硬截止 08:50≥（等于也停）不变。**
- **2026-09-23 05:1x MR-0 交付核验通过**：交付三要素齐 = 收官笔 `f80e9fdb8`（功能+报告+checkbox 全勾，6 文件 +286/-24，明确文件名 add）+ 完成报告 `docs/report-MR0-completion-2026-09-23.md`（门禁实证：compileReleaseKotlin+detekt 绿 3m21s、旗标守卫 2/2、check-agent-instructions 过；诚实登记 pending-device/lint 未跑理由/api.properties 自修复）+ 工作树回净（构建副产物已还原）。**accepted 留用户**。核验口径 = 提交+报告双全（§7，不跑验收门）。**携带事实**：① 三旗标（mirrorProtobufTransport/gameViewProjection/dirtyColumnExport）已随 B18 退役且守卫禁回流——P4.2「与 dirtyColumnExport 共存」表述届时须按现状校准；② 工作树 api.properties 已补（gitignored）。MR-1 派发文本 = batch-MR1.md + 《前批交付事实》附录（5 条原文摘录）。
- **2026-09-23 04:3x 看护建立**：三件套就绪（工作树 + 本台账 + 批次文件 batch-MR0..MR4）；看护自动化 id = `automation-4e1892f4-0b67-4431-b8f6-e7bcaebbb964`（*/10）。用户口径「按批次实施 0 至 5」= 方案**全部批次**（Phase 0–4 共五批，编号 MR0–MR4；方案无 Phase 5，已向用户说明，如有异议以用户追加指示为准）+ 全部交付后总收官。
- **2026-09-23 04:40 MR-0 已派发（GUI 六步全过，锁解除）**：新 ZCode 子会话《【MR-0 内存管理根治 · Phase 0 开关与基线批】》开跑实证「工作中 11 秒」，首动作 `cd C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor && git status && git log` 正确落工作树，自述先读台账留言区+方案再逐条实施 P0.0–P0.2。派发文本 = batch-MR0.md 全文（2091 字符剪贴板粘贴，尾部逐字核验）。下轮起【C 监控】MR-0：CLI 三信号（git -C 工作树 log/status/新报告文件），交付核验口径 = 收官笔（完成报告 `docs/report-MR0-completion-2026-09-23.md` 入库 + Phase 0 checkbox 全勾 + 工作树回净），核验通过即派 MR-1（Phase 1 止血+压力闭环，指令 = batch-MR1.md + MR0 交付事实附录）。**硬截止 08:50≥（等于也停）不变。**
- 批次映射：MR0=Phase 0（开关与基线）→ MR1=Phase 1（止血+压力闭环）→ MR2=Phase 2（GPU 子系统）→ MR3=Phase 3（纹理缓存）→ MR4=Phase 4（基线+GLES+收口）。依赖链见方案第四部分依赖摘要（P2.1→P2.2→P3→P4.1；P1.3→P2.3）。

## 看护运行手册

### §1 轮次状态机（每轮 fire 按序执行）

0. **先读完本轮 fire 全文**——用户可能把追加指令缀在末尾，追加指令优先级最高。
1. **硬截止检查**：取当前本地时间，若 **≥ 2026-09-23 08:50（等于也停）** → 走 §4「硬截止停工」，本轮结束。
2. **防重入**：`CronList` 核实全机仅一个本看护自动化（出现第二个 = 立即报告用户并只保留一个）；台账《当前状态》最新条的看护锁时间戳在 15 分钟内 = 看护活跃 → 本轮只观察不动作。
3. **状态分派**（读《批次总表》）：
   - 某批 `dispatched` → §3 监控轮；
   - 某批交付核验通过（判据见 §7）→ 台账核验登记 → 按 §5 组装下一批指令 → §2 GUI 六步派发 → 台账记 `dispatched` + 锁 → 提交台账；
   - 全部五批核验通过 → §4「总收官」；
   - 无在途批且尚有未派批（首轮即此态）→ 直接派下一批。
4. **降噪纪律**：无实质变化不写监控日志、不提交（07:13 等待期先例）。
5. 台账 Edit 报 "modified since read" 先重读再追加；追加锚点必须是当前最新条目，绝不复用旧行文本当 old_string。

### §2 GUI 派发六步（焦点红线，禁止盲发）

windows-mcp：App switch 到 ZCode → 截屏确认前台窗口身份 → 新建任务（Ctrl+N）→ 截屏确认输入页（模型芯片 / 完全访问 / 工作区 XianxiaSectNative）→ **剪贴板粘贴**批次指令全文（防中文 IME）→ 截图核验输入框内容落位（**输入框有用户草稿 = 立即退手，用户在场优先**）→ 发送 → 截屏确认开跑（「工作中 N 秒」）。任一步特征不符即中止重试；报 "No active window found" 空窗态以截图内容为准。

### §3 监控轮

CLI 三信号 = `git -C <工作树> log --oneline` / `status --short` / 新报告文件；GUI 快查用于判活（**先点侧栏子会话条目再截屏直读**，cron 送达会把看护自己刷成「最后活跃」）。停滞判据（继承 B/SR 系列教训）：派发后 9–19 分钟无文件产物 = 勘察期正常；测试 JVM 内存冻结 ≠ 挂起（Robolectric 慢跑，须 GUI 核会话内容判活）；ZCode 应用全进程重启会清后台 shell（判活以 CLI 进程/日志/新提交为准，UI「工作中 N 秒」可能是冻结渲染不可信；重启后批次会话需用户或看护重新唤醒）。

### §4 停工收官（两种）

- **硬截止**（本地时间 ≥ 2026-09-23 08:50）：台账《当前状态》记 `stopped`（deadline 到；在途批状态照实登记：已交付哪些/在途会话与最后可见进度），提交台账 → `CronDelete` 删除本自动化 → 向用户简报（在途子会话照常跑完不受影响；恢复方式 = 用户说「继续」后按 §6 重建）。
- **总收官**（MR0–MR4 全部核验通过）：台账批次总表终态登记 + 状态 `ended` + 简报（含 pending-device 项汇总、accepted 留用户清单）→ `CronDelete` 删除本自动化。

### §5 派发指令组装

批次文件 = 本目录 `batch-MRn.md`；派发时在文末追加《前批交付事实》2–4 条（**原文摘录**上一批完成报告「关键实施事实」节，不改写），再整体进剪贴板。

### §6 恢复路径

用户说「继续」：先核对工作树 git log 与台账在途态（子会话可能已自行推进）→ `CronCreate` 重建 `*/10` 看护（prompt 取本台账最后次提交中的看护 fire 模板）→ 按 §1 继续。恢复后硬截止语义以用户新指示为准。

### §7 交付核验口径（继承 SR 看护口径：不跑验收门）

判据 = **收官笔落库 + 完成报告入库 + 工作树回净** 三者双全（`git -C <工作树> log/status` 核实）。验收门（组合门/ctest 复核）与 `accepted` 归用户或后续验收会话亲跑，看护不代跑、子会话不得自登记 `accepted`。

### §8 铁律

看护**绝不代子会话改代码**；不跑 Gradle/ctest 长跑（与子会话构建错峰）；台账为看护专属写域（子会话只读，《给实施会话的留言》区是唯一治理通道）；每轮派发动作前必先重读《当前状态》+ 防重入。

## 批次总表

| 批 | Phase / 任务 | 状态 | 派发时刻 | 交付提交 | 核验 | accepted |
|---|---|---|---|---|---|---|
| MR0 | Phase 0（P0.0 线程契约登记 / P0.1 memorySubsystem 旗标 / P0.2 基线采集清单） | **delivered · 核验通过 05:1x** | 04:40 GUI 六步 | `f80e9fdb8` | ✅ 看护（提交+报告双全，§7） | 留用户 |
| MR1 | Phase 1（P1.1–P1.7 止血+压力闭环） | **delivered · 核验通过 07:1x** | 05:12 GUI 六步 | `c2410bf1c` | ✅ 看护（提交+报告双全，§7） | 留用户 |
| MR2 | Phase 2（P2.1–P2.3 GPU 子系统 VMA/GpuAllocator） | **delivered · 核验通过 08:2x** | 07:22 GUI 六步 | `92a4a8e79` | ✅ 看护（提交+报告双全，§7） | 留用户 |
| MR3 | Phase 3（P3.1–P3.3 纹理缓存） | **dispatched（派发中，锁 08:2x）** | 08:3x | — | — | — |
| MR4 | Phase 4（P4.1–P4.6 基线+GLES+收口） | queued | — | — | — | — |

## 给实施会话的留言

（子会话只读区；看护纠错/环境事件写这里，子会话开工前须先读本区）

- 2026-09-23 04:58（看护）：工作树出现构建/codegen 副产物连带改动——`android/app/src/main/assets/atlas/atlas-rgba-manifest.json`、`android/scripts/sprite-uid-map.json`、`android/app/src/main/cpp/scene/scene_uv_tables.h`（跑 Gradle/codegen 被再生成，B18 已知陷阱）——**这三个文件与本批无关，不要提交**（既定纪律：明确文件名 add；收官前若仍残留请还原）。

- 2026-09-23 04:3x（看护）：工作树 `android/local.properties`、`android/keystore.properties` 为本地配置件已手拷，**绝不提交**；提交一律明确文件名 `git add <file>`。

## 监控日志

（追加式，最新在上；降噪纪律：无实质变化不记）

- **2026-09-23 07:58 监控轮（MR-2 进行态）**：守卫与 JNI 面现身——新增 `gpu_allocator_guard_test.cpp`（GpuAllocatorGuard）+ `gpu_budget_math_test.cpp`；NativeBridge.cpp/.kt 在改；**jni-count.baseline.json 被改 = JNI 计数门基线变更（B17 门），核验时须报告附豁免理由**；renderer-feature-checklist.md 同步（约束 12）。尚无提交；下轮盯 stats 单测/6 站点收口完成与切分提交。

- **2026-09-23 07:38 监控轮（MR-2 进行态）**：P2.1 面落盘——新目录 `cpp/gpu/`（GpuAllocator）+ `cpp/third_party/`（VMA）现身，恰为批次预期落点；尚无既有文件改动与提交。下轮盯 VulkanBackend 6 站点收口展开。

- **2026-09-23 06:48 监控轮（MR-1 收官在即）**：完成报告 `docs/report-MR1-completion-2026-09-23.md` 落盘（未提交）+ 方案 checkbox 勾选中 + atlas-rgba-manifest.json 副产物已还原剔除（留言区纪律生效）。就差收官笔落库——下轮核交付三要素并备 MR-2 派发。

- **2026-09-23 06:28 监控轮（MR-1 进行态）**：**双 changelog 出现**（CHANGELOG.md + changelog_entries.json）= 收官材料准备期，门禁自检大概率已过/接近过（全局约束 13 每次合入即写，P1.1/P1.3 玩家可感知）；工作树 31 改 + 6 新增；atlas-rgba-manifest.json 副产物仍在待剔除。下轮预期切分提交连发 + 完成报告。

- **2026-09-23 06:08 监控轮（MR-1 进行态）**：进入测试面阶段——`column_dirty_test.cpp`（P1.1 增长上界）+ `memory_trim_test.cpp`（P1.5/tick 挂点）+ `TrimConsumerCountGuardTest.kt`（P1.3 守卫）+ `SectMapCacheBoundTest.kt`（P1.2）+ `SceneUpdateChannelTest` 扩展 + test CMakeLists 同步，验收测试清单与 batch-MR1 对应齐。实现面趋完整；下轮预期切分提交 + 门禁（桥重建+ctest 1561 基线+JVM 串行+detekt）。

- **2026-09-23 05:58 监控轮（MR-1 进行态）**：七任务面全部现身，工作树 24 改 + 2 新目录——新增 `VulkanBackend.cpp/.h`+`GameCoreJni.cpp`（P1.7/P1.6）、`GameActivity.kt`+`GameLoopDelegate.kt`（P1.3 四消费者收敛面齐）、`AtlasAsyncPipeline.kt`（P1.4）；`GameViewModel.kt` 新现待核（疑 GameLoopDelegate 挂点）。无越界；**24 文件未提交 = 切分纯度将是核验重点**（P1.3 与 P1.1/P1.6 共享面易混笔）。

- **2026-09-23 05:48 监控轮（MR-1 进行态）**：工作树扩至 17 改 + 2 新目录（`app/.../core/memory/`、`core/domain/.../memory/`——Bridge 与 MemoryTrimLevel 落点），**P1.3 收敛面现身**（XianxiaApplication/CacheLayer/GameDataCacheMemoryPressure/GameDataCacheMaintenance/GameMonitorManager 在改 = 既有消费者正被吸收），P1.6/P1.4 JNI 面（NativeBridge.cpp/.kt + GameCoreBridge.cpp/.kt）同轮展开；无越界文件，尚未提交。下轮盯首笔切分提交（七任务面多，切分纯度重点核 P1.3 与 P1.1 不混笔）。

- **2026-09-23 05:38 监控轮（MR-1 进行态）**：代码面展开，8 文件在写全在预期面——C++ P1.1（`column_dirty.h` 几何扩容 + `disciple_store.h/.cpp` reserve）+ P1.5（`game_core.h/.cpp` 账本 cap/tick 挂点）；Kotlin P1.2（`SectMapController.kt` + `SceneUpdateChannel.kt`）+ P1.7 面（`VulkanRenderBackend.kt`）。无越界文件、无副产物混入；P1.3（TrimMemoryBridge.kt）与 P1.4/P1.6 面未现，尚未提交。下轮盯切分提交纯度与 P1.3 收敛面。

- **2026-09-23 05:28 监控轮（MR-1 进行态）**：派发后 16 分钟零文件产物 → GUI 直读判活 = 深度勘察期非停滞：P1.3/P1.5 接口关系已清（正设计 `CacheLayer.onMemoryTrimBridge(level)` 收敛入口）、核对 memory-audit B-6 原文（账本增长在 Kotlin 侧，C++ cap 仅 import 生效——找 GameCore 每旬结算入口作 tick 边界挂点）、P1.2 面清楚，正补 SectMapController 全文/ctest 测试样式/GLES 挂点最后几个探查点。下轮预期立卡笔+代码面展开。

- **2026-09-23 04:58 监控轮（MR-0 进行态）**：三任务面齐头展开且全在预期面——P0.1 = `NativeEngineFlag.kt` + 新测试 `NativeEngineFlagMemorySubsystemTest.kt` + `core/engine/build.gradle`（疑 BuildConfig 接线）；P0.2 = 方案文档附录 A 在改；P0.0 = `docs/threading-contract.md` 持续成形；仍无提交。附带：三个构建/codegen 副产物被连带改写（已留言区提示勿混提交）。下轮盯首笔提交切分纯度（契约/旗标/附录应各自独立）。

- **2026-09-23 04:48 监控轮（MR-0 进行态）**：派发后 8 分钟首改动落盘 = `M docs/threading-contract.md`，恰为 P0.0 目标面（线程契约登记），无越界文件；尚无提交（勘察期收尾转落笔，正常节奏）。防重入核对：全机仅 automation-4e1892f4 一个看护自动化。下轮盯 P0.0 登记条目成形与 P0.1 旗标面。

- 2026-09-23 04:3x 看护初始化完成：方案修订入库 `c9c09f9b3` → 工作树建立 + 本地配置件拷贝 → 台账/批次文件入库（待提交）→ 建 cron → GUI 派发 MR0（结果见下条）。
