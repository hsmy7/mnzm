【MR-1 内存管理根治 · Phase 1 止血+压力闭环批（P1.1–P1.7 七任务全做）】

■ 会话纪律：本会话只实施本批；完成后出完成报告并停止，不开始 Phase 2；**不得自登记 accepted**；如实登记失败、未完成与 pending 项，禁止虚报。七项任务全部交付，禁止留尾巴（方案无「后续优化」）。

■ 施工面（唯一）：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）。**禁止改动主树** `C:\Mnzm\XianxiaSectNative` 下任何文件。开工前先读看护台账《给实施会话的留言》区（只读）：`C:\Mnzm\XianxiaSectNative\docs\memory-refactor-watch\dispatch-ledger.md`。提交一律明确文件名 `git add <file>`，禁止 `git add -A`（`android/local.properties`、`android/keystore.properties` 绝不入库）。

■ 必读（按序）：
1. 工作树根 `AGENTS.md` + §0 路由表本批涉及行（跨线程交互以 MR0 已登记的 `docs/threading-contract.md` 为准；新增/改精灵图不涉及；文档改动后跑 `node scripts/check-agent-instructions.mjs`）；
2. `docs/memory-refactor-implementation-plan-2026-09-23.md`：全局约束 1–15 + **D3/D4/D6 设计节全文** + Phase 1 全部任务 + 根治判据 R1–R5；
3. `docs/compose/spec/memory-management-refactor.md` 对应轨（轨 C 位图 / 轨 E trim 收敛）按需查阅。

■ 本批任务（照方案第四部分 Phase 1 逐条，行号漂移以符号名+注释锚点二次定位）：
- **P1.1**（C++）`column_dirty.h` `ensureRowCapacity` 改几何增长：`need = max(rows, capacity * kGrowthFactor, kMinRows)` 向上取整到字；`kGrowthFactor=2`、`kMinRows=1024` 命名常量；kMinRows **仅作用于预期大表/加载路径**（DiscipleStore 列、5000 弟子档），小集合用 `kMinRowsSmall` 或按 need 几何增长，禁止全局抬 1024 浪费；`loadFromVector`/批量入口先 `columnTracker.reserve(rows)` 一次到位；同步审查 DiscipleStore 存档加载路径主要 vector 列 `reserve(n)`。不改列布局/确定性语义/R1.4 写屏障协议。验收：`ColumnResizeGrowthTest`——N 次 append 分配次数 ≤ `2*ceil(log2(N/min))+O(1)`
- **P1.2**（Kotlin）`SectMapController.sectMapCache` 改 `LinkedHashMap(accessOrder=true)` + `removeEldestEntry` 上限 `kSectMapCacheMaxEntries=8`（命名常量，可再绑 budget）；`SceneUpdateChannel.pushedTerrain` 切换时释放旧引用。验收：上限单测
- **P1.3**（Kotlin，本批最大面）`TrimMemoryBridge.kt` = **收敛入口不是新增旁路**：动手前先全仓 grep 既有 trim 消费者清单（方案 D3 已列 4 处：`XianxiaApplication.notifyMemoryPressure` 游戏侧分发、`CacheLayer`/`GameDataCacheMemoryPressure` 自注册 `ComponentCallbacks2`、`GameActivity.onTrimMemory`、`GameLoopDelegate`），迁移后生产 `onTrimMemory`/`onLowMemory` 的**游戏内存消费者数量 = 1（Bridge）**；`MemoryTrimLevel { NONE, SOFT, AGGRESSIVE, CRITICAL }` 四档映射照方案 D3 表；Application 与 GameActivity 双发去抖（同 level 短窗合并，命名常量）；回调 → JNI `nativeMemoryTrim(level)` → 渲染线程队列消费（**trim 回调里禁止纹理重上传**）→ gamecore 只读 stats / tick 边界容器收缩钩子（禁 JobSystem 并行段/结算中途）；复用 `DynamicMemoryManager`/`GpuTierDetector`/`GCOptimizer` 阈值轴，只读 `MemoryBudgetView`，**不写第二套分级**。验收：`TrimDispatchTest`（Robolectric 分档）+ `TrimConsumerCountGuardTest`（消费者=Bridge 一处）+ CRITICAL 不空转
- **P1.4**（Kotlin）`AtlasAsyncPipeline` 失败/重试路径（`allowCompressed=false` 回退）**手动** release 旧纹理 id——过渡方案直调既有 JNI `destroyTexture`（MR3 建缓存后替换为 cache release）。验收：同纪元重试 GPU 纹理数不增（单测/mock）
- **P1.5**（C++）账本 tick/settle 路径补与 import 同语义 cap（B-6）——调用既有 `normalizeLedgers` 或等价裁剪，双端同常量（禁第二处字面量）；裁剪后压力路径可 `shrink_to_fit`（禁帧/tick 热路径调用）。验收：import 与 tick 裁剪同一常量守卫
- **P1.6**（C++）`jbytesToString` 空指针检查 + 大 JNI release RAII 化（M-P2-7/8）。验收：空入参单测；无裸大缓冲泄漏路径
- **P1.7**（C++）`m_pendingDraws` reserve + `m_textures` 查找收窄（M-P2-3/4 轻量半边）。验收：单测/结构断言，不改语义

■ 门禁：
- Kotlin 面：`cd android && ./gradlew.bat compileReleaseKotlin testReleaseUnitTest detekt`（串行 `--max-workers=1`；模块限定跑法见 AGENTS §2）
- C++ 面（P1.1/P1.5/P1.6/P1.7）：`pwsh scripts/build-desktop-jni.ps1` 重建桥 → PATH 前置 `C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` 及其 `x86_64-w64-mingw32\bin`、`%LOCALAPPDATA%/Android/Sdk/cmake/3.22.1/bin` → cmake build + ctest（当前基线 **1561** 全绿，新增测试后计数如实登记）
- `MirrorReadOnlyGuardTest` / `DiffAuthoritativeTickTest` 必须保持绿；detekt baseline 只缩不增；门禁失败修复走独立 commit（系列先例）
- 真机项（CRITICAL trim 后水位回落实测）如实登记 pending-device

■ 交付：完成报告 `docs/report-MR1-completion-2026-09-23.md`（门禁实证数字 / **关键实施事实**——TrimMemoryBridge 收敛了哪些消费者、常量落点、符号名，供 MR2（P2.3 接 CRITICAL）派发引用 / 诚实残余 / pending-device 清单）；收官笔 = 报告入库 + Phase 1 checkbox 全勾 + 工作区回净。提交前缀建议 `perf(memory)` / `feat(memory)` / `docs(memory)`。

■ 红线重申：Trim/驱逐**禁止**清除 `cultivationCheckpoints`、`lastSettled*`、生产 `completionMonth`、卡池进度等进度语义，只放资源（全局约束 5）；GPU API 仅渲染线程；存档格式零变更；禁魔法数字；本批不含 TextureCache/GpuAllocator（属 MR2/MR3），P1.4 只做过渡直调。
