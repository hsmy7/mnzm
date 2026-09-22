【MR-0 内存管理根治 · Phase 0 开关与基线批】

■ 会话纪律：本会话只实施本批（Phase 0 三个任务）；完成后出完成报告并停止，不开始 Phase 1；**不得自登记 accepted**（验收由看护/用户另行裁决）；如实登记失败、未完成与 pending 项，禁止虚报。

■ 施工面（唯一）：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）。所有命令先 cd 到该目录或用绝对路径；**禁止改动主树 `C:\Mnzm\XianxiaSectNative` 下任何文件**（只读参考除外）。开工前先读一次看护台账的《给实施会话的留言》区（只读）：`C:\Mnzm\XianxiaSectNative\docs\memory-refactor-watch\dispatch-ledger.md`，有针对本批的条目则遵循。提交一律明确文件名 `git add <file>`，禁止 `git add -A` / `git add .`（工作树 `android/local.properties`、`android/keystore.properties` 是本地配置件，绝不入库）。

■ 必读（按序）：
1. 工作树根 `AGENTS.md`（规范真源）+ §0 路由表本批涉及行（本批涉及：新增跨线程交互 → `docs/threading-contract.md` **先登记再实现**）；
2. `docs/memory-refactor-implementation-plan-2026-09-23.md`：①全局约束 1–15（全程红线）②Phase 0 全部任务逐条实施、完成即勾选 checkbox ③第九部分交叉方案（禁止双改）；
3. `docs/compose/spec/memory-management-refactor.md` 按需查阅。

■ 本批任务（照方案第四部分 Phase 0 逐条）：
- **P0.0** 在 `docs/threading-contract.md` 登记 `nativeMemoryTrim` / `textureAcquire` / `textureRelease` / MemoryStats 读通道与线程归属（表一线程职责 + 表四通道；要点：TextureCache 表与 GpuAllocator 渲染线程独占，Kotlin 只投递命令，引擎线程只在 tick 边界做容器收缩）——acceptance: 契约表可审，后续实现分支引用该登记
- **P0.1** 新增 `NativeEngineFlag.memorySubsystem`（并入既有旗标族，找到 core/engine 既有 NativeEngineFlag 定义处同构添加，不另造开关体系）BuildConfig/本地默认 **false**（预发）+ 运行时读取入口；OFF 时 P2–P4 新路径 no-op（P1.* 止血修复不受开关控制）——acceptance: 开关存在且默认值经评审（方案建议先 false，根治验收后 true）
- **P0.2** 真机/模拟器内存基线采集清单落成**可复制执行**的命令清单写入方案附录 A（`dumpsys meminfo` + Native Heap 分项 + heapprofd 抽样 + VMA stats 将来对照位；附录 A 已有命令骨架，核实补全）——acceptance: 清单可复制执行

■ 门禁（本批零 C++ 生产码）：
- `cd android && ./gradlew.bat compileReleaseKotlin detekt`（测试串行 `--max-workers=1`；如旗标带单测则按 AGENTS §2 模块限定跑法补跑）
- 改了 docs/ 之后跑 `node scripts/check-agent-instructions.mjs` 必须绿
- detekt 阈值先例：LongMethod 60、文件函数数 15、行宽 120；baseline 只缩不增

■ 交付：完成报告 `docs/report-MR0-completion-2026-09-23.md`（门禁实证数字 / **关键实施事实**——旗标定义落点、契约登记条目名，供后续批次派发引用 / 诚实残余 / pending-device 项如实登记）；收官笔 = 报告入库 + 方案 Phase 0 checkbox 全勾 + 工作区回净。提交前缀建议 `docs(memory)` / `feat(memory)`。

■ 红线重申：本批只做 Phase 0；不动任何生产 C++/渲染代码；`docs/threading-contract.md` 登记质量直接决定后续批次合法性（未登记的跨线程面不得实现——全局约束 7）。
