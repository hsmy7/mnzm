【MR-4 内存管理根治 · Phase 4 状态基线 + GLES + 收口批（P4.1–P4.6，根治合入门槛批）】

■ 会话纪律：本会话只实施本批；完成后出完成报告并停止；**不得自登记 accepted**（本批即根治合入门槛，P4.5/P4.6 全绿后由用户/验收会话亲跑复核）；如实登记失败、未完成与 pending 项，禁止虚报。

■ 施工面（唯一）：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）。**禁止改动主树** `C:\Mnzm\XianxiaSectNative` 下任何文件。开工前先读看护台账《给实施会话的留言》区（只读）：`C:\Mnzm\XianxiaSectNative\docs\memory-refactor-watch\dispatch-ledger.md`。提交一律明确文件名 `git add <file>`，禁止 `git add -A`（`android/local.properties`、`android/keystore.properties` 绝不入库）。

■ 必读（按序）：
1. 工作树根 `AGENTS.md` + §0 路由表本批涉及行（写设计方案原则不适用——照单实施；文档同步义务见 AGENTS §12.4 双 changelog）；
2. `docs/memory-refactor-implementation-plan-2026-09-23.md`：全局约束 1–15 + **D5/D7 设计节全文** + 第九部分交叉（**禁止双改**清单）+ Phase 4 全部任务；
3. MR0–MR3 完成报告「关键实施事实」节；`docs/native-engine-refactor-plan-2026-09-17.md`/`docs/cpp-engine.md` 的 R1/R2/R2.4 落点（D5 强制复用其符号）；`android/docs/renderer-feature-checklist.md`。

■ 本批任务（照方案第四部分 Phase 4 逐条）：
- **P4.1**（C++，本方案最难项）`DirtyTracker` **rest 域**基线去全量 DOM：弟子列维持 R1.4/R2.4 列级 dirty+列基线（只做与 MR1 P1.1 几何扩容共存的容量/reserve 整理，不重写协议）；非弟子 rest 域改**字段/块级基线**（定长字段直接存 POD/小 blob；集合类按既有 `kEntityCollections` 逐实体序列化块）替代整树 `stateToJson` 常驻；与 `diffTreeSegments` **共享循环体**保证等价；diff 比较树仅导出瞬时存在（禁 baselineJson_ 与 cur 两棵全量树常驻）；dump 复用 `std::string` buffer + `reserve`；import = 解析到临时 GameState → 校验 → C++ 内切 `state_` 指针并释放旧树 → 既有 `importToNative`/`importStateInternal` 归一化族 → 失败回滚旧树（禁半程双树常驻）；镜像对齐仍由 import 成功后 `reprojectAll`/基线建立点收敛。**实施前先 diff R2.4 现状确认复用面**。【强制复用】`diffTreeSegments`/`stateWithoutDisciplesToJson`/`diffToTree`/`exportDirtyTree`/`GameDataFieldPatch` 消费面/`NativeEngineFlag` 旗标族；【禁止】重做列级导出、改 protobuf schema 语义、并行字段应用协议、第四种信封、反向增量通道。验收：`DiffAuthoritativeTickTest` 100 旬绿 + 存档往返对拍绿 + `MirrorReadOnlyGuardTest` 绿 + 新增 `BaselineMemoryTest`（稳态无双全量树断言）+ `BaselineFieldCoverageGuardTest`（`GameData.serializer` 元素名 ↔ 基线字段表双射，缺字段即红并点名，复用 R2.3 fail-fast 模式）
- **P4.2**（C++）每旬 rest 域导出减载：在既有 `diffTreeSegments`/`stateWithoutDisciplesToJson` 上做块/dirty 包减载（**不**另造信封；与 `dirtyColumnExport` 混合分发共存、正交）。验收：Diff tick 绿；JNI 载荷 metric 下降
- **P4.3**（C++ GLES）D7：顶点初始化时 `glBufferData`/`glBufferStorage` 一次按 `MAX_VERTICES` 预分配，每帧 `glBufferSubData` 只传脏范围（能力不足机型保留整批路径+记录 metric）；`draw()` clamp 到 `MAX_VERTICES`；`PendingUpload.pixels` **vector 池**复用（锁外拷贝纪律保持）；软渲 Bitmap 预算并入 D3 收敛面（`onTrim(SOFT+)` 按既有 `DisposableEffect` 模式放可重建位图，**不得另开第四条 trim 监听**）。验收：能力允许时无每帧整批 `glBufferData`；池化后连续上传峰值不线性涨；`SoftwareCanvasBackend` 相关测试不回退
- **P4.4**（Kotlin）`MemoryBudgetView` 只读 stats 接 UI Debug 页：合并 `GpuAllocator.stats()`（Kotlin 侧只读不写第二套分级）。验收：Debug 可见分类 MB
- **P4.5** Guard 测试总装 + 全量组合门：`GpuAllocatorGuard`/`TextureUploadPathGuardTest`/`TrimConsumerCountGuardTest`/`TrimPreserveFieldsGuardTest`（进度字段白名单枚举锁死：`cultivationCheckpoints`、`lastSettled*`、生产 `completionMonth`、卡池碎片/星级/保底/寻访历史，新增进度字段必须同步登记）/`BaselineFieldCoverageGuardTest` 总装核对；**全量 `testReleaseUnitTest --max-workers=1` + detekt + `compileReleaseKotlin` + lintRelease + ctest + 性能非回归对照**（`DirtyTrackerBench`/`MirrorSegmentProjectionBenchTest` 方法论，每旬结算与稳态帧耗时不得劣化超噪声带，超带宽必须说明并回写方案）。验收：BUILD SUCCESSFUL / 测试全绿 / bench 不超噪声带
- **P4.6** 文档收口：**双 changelog**（`android/app/src/main/assets/changelog_entries.json` 玩家文案——通俗易懂无专业术语不泄露数值，追加到当前版本条目 changes 末尾；根 `CHANGELOG.md` 开发者视角技术细节——注意 AGENTS §12.4 与方案全局约束 13：**核对 MR0–MR3 各批玩家可感知修复是否已按批写 changelog，漏则本批补齐**）+ `CODE_WIKI.md` 性能基础设施 + `docs/architecture.md` 内存小节 + `docs/platform-abilities.md`（GpuAllocator/Metal 登记）+ `android/docs/renderer-feature-checklist.md` 双路径勾选 + 改 docs 后 `node scripts/check-agent-instructions.mjs` 绿

■ 门禁：
- C++ 面：`pwsh scripts/build-desktop-jni.ps1` 重建桥 → PATH 前置 `C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` 及其 `x86_64-w64-mingw32\bin`、`%LOCALAPPDATA%/Android/Sdk/cmake/3.22.1/bin` → cmake build + ctest（基线见 MR3 完成报告登记数）
- 全量组合门收口（P4.5）：串行 `--max-workers=1`；detekt baseline 只缩不增；`MirrorReadOnlyGuardTest`/`DiffAuthoritativeTickTest`/`ColumnExportEquivalence` 不回退；门禁失败修复走独立 commit
- 开关终态核对：`memorySubsystem` 默认值（方案：预发 false → 根治验收后 true——**本批不改默认值**，切 true 留验收后用户拍板，报告里登记建议）；真机项（附录 A 全清单）如实登记 pending-device

■ 交付：完成报告 `docs/report-MR4-completion-2026-09-23.md`（门禁实证数字 / **关键实施事实**——基线存储终态、复用符号清单落实情况、性能非回归对照数据 / 诚实残余 / pending-device 清单 / 收尾核对表：全局约束 1–15 逐条自评）；收官笔 = 报告入库 + Phase 0–4 checkbox 全勾复核 + 工作区回净。提交前缀建议 `feat(memory)` / `perf(memory)` / `docs(memory)`。

■ 红线重申：AUTHORITATIVE 镜像只读（禁止复活反向增量）；存档磁盘格式与 Proto 契约零变更；惰性结算不变量（Trim 禁清进度语义）；降级链不可变；Room mmap 禁重开（全局约束 9）；禁魔法数字；本批是根治合入门槛——P4.5/P4.6 未全绿不得宣称完成。
