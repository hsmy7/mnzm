# 内存重构批次看护台账（MR 系列）

> **唯一权威状态文件**。看护每次状态变更后 git 提交 `docs(memory-watch): ...`。
> 方案 = [memory-refactor-implementation-plan-2026-09-23.md](../memory-refactor-implementation-plan-2026-09-23.md)（任务带 checkbox，Phase 0–4）
> 特性文档 = [compose/spec/memory-management-refactor.md](../compose/spec/memory-management-refactor.md)
> 施工工作树 = `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`，自 main `c9c09f9b3` 分出；`android/local.properties`、`android/keystore.properties` 已手拷且被 .gitignore 覆盖）
> 编排 = 看护 ZCode 会话（cron `*/10` 每十分钟检查一轮）+ 每批一个**新建 ZCode 子会话**（GUI 六步派发），一会话只做一批
> **硬截止 = 2026-09-23 08:50 停工**（用户 04:2x 指令 + 04:3x 澄清：判定条件 **≥ 08:50，等于也停**）——到点看护停派发、停监控、删自动化；已派子会话不受影响照常跑完（SR「暂停」先例）

## 当前状态

（最新在上）

- **2026-09-23 05:1x MR-0 交付核验通过 + MR-1 派发中（看护锁 05:1x，GUI 六步进行中）**：交付三要素齐 = 收官笔 `f80e9fdb8`（功能+报告+checkbox 全勾，6 文件 +286/-24，明确文件名 add）+ 完成报告 `docs/report-MR0-completion-2026-09-23.md`（门禁实证：compileReleaseKotlin+detekt 绿 3m21s、旗标守卫 2/2、check-agent-instructions 过；诚实登记 pending-device/lint 未跑理由/api.properties 自修复）+ 工作树回净（构建副产物已还原）。**accepted 留用户**。核验口径 = 提交+报告双全（§7，不跑验收门）。**携带事实**：① 三旗标（mirrorProtobufTransport/gameViewProjection/dirtyColumnExport）已随 B18 退役且守卫禁回流——P4.2「与 dirtyColumnExport 共存」表述届时须按现状校准；② 工作树 api.properties 已补（gitignored）。MR-1 派发文本 = batch-MR1.md + 《前批交付事实》附录（5 条原文摘录）。
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
| MR1 | Phase 1（P1.1–P1.7 止血+压力闭环） | **dispatched（派发中，锁 05:1x）** | 05:1x | — | — | — |
| MR2 | Phase 2（P2.1–P2.3 GPU 子系统 VMA/GpuAllocator） | queued | — | — | — | — |
| MR3 | Phase 3（P3.1–P3.3 纹理缓存） | queued | — | — | — | — |
| MR4 | Phase 4（P4.1–P4.6 基线+GLES+收口） | queued | — | — | — | — |

## 给实施会话的留言

（子会话只读区；看护纠错/环境事件写这里，子会话开工前须先读本区）

- 2026-09-23 04:58（看护）：工作树出现构建/codegen 副产物连带改动——`android/app/src/main/assets/atlas/atlas-rgba-manifest.json`、`android/scripts/sprite-uid-map.json`、`android/app/src/main/cpp/scene/scene_uv_tables.h`（跑 Gradle/codegen 被再生成，B18 已知陷阱）——**这三个文件与本批无关，不要提交**（既定纪律：明确文件名 add；收官前若仍残留请还原）。

- 2026-09-23 04:3x（看护）：工作树 `android/local.properties`、`android/keystore.properties` 为本地配置件已手拷，**绝不提交**；提交一律明确文件名 `git add <file>`。

## 监控日志

（追加式，最新在上；降噪纪律：无实质变化不记）

- **2026-09-23 04:58 监控轮（MR-0 进行态）**：三任务面齐头展开且全在预期面——P0.1 = `NativeEngineFlag.kt` + 新测试 `NativeEngineFlagMemorySubsystemTest.kt` + `core/engine/build.gradle`（疑 BuildConfig 接线）；P0.2 = 方案文档附录 A 在改；P0.0 = `docs/threading-contract.md` 持续成形；仍无提交。附带：三个构建/codegen 副产物被连带改写（已留言区提示勿混提交）。下轮盯首笔提交切分纯度（契约/旗标/附录应各自独立）。

- **2026-09-23 04:48 监控轮（MR-0 进行态）**：派发后 8 分钟首改动落盘 = `M docs/threading-contract.md`，恰为 P0.0 目标面（线程契约登记），无越界文件；尚无提交（勘察期收尾转落笔，正常节奏）。防重入核对：全机仅 automation-4e1892f4 一个看护自动化。下轮盯 P0.0 登记条目成形与 P0.1 旗标面。

- 2026-09-23 04:3x 看护初始化完成：方案修订入库 `c9c09f9b3` → 工作树建立 + 本地配置件拷贝 → 台账/批次文件入库（待提交）→ 建 cron → GUI 派发 MR0（结果见下条）。
