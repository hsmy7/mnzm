【MR-2 内存管理根治 · Phase 2 GPU 子系统批（P2.1–P2.3）】

■ 会话纪律：本会话只实施本批；完成后出完成报告并停止，不开始 Phase 3；**不得自登记 accepted**；如实登记失败、未完成与 pending 项，禁止虚报。

■ 施工面（唯一）：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）。**禁止改动主树** `C:\Mnzm\XianxiaSectNative` 下任何文件。开工前先读看护台账《给实施会话的留言》区（只读）：`C:\Mnzm\XianxiaSectNative\docs\memory-refactor-watch\dispatch-ledger.md`。提交一律明确文件名 `git add <file>`，禁止 `git add -A`（`android/local.properties`、`android/keystore.properties` 绝不入库）。

■ 必读（按序）：
1. 工作树根 `AGENTS.md` + `android/AGENTS.md`（如引擎目录有模块级 AGENTS.md 一并读）；
2. `docs/memory-refactor-implementation-plan-2026-09-23.md`：全局约束 1–15 + **D1 设计节全文** + D3（P2.3 接 CRITICAL）+ Phase 2 全部任务；
3. `docs/threading-contract.md` MR0 登记条目（分配器渲染线程独占）；`android/docs/renderer-feature-checklist.md`（双路径同步义务）。

■ 本批任务（照方案第四部分 Phase 2 逐条；行号漂移以符号名+注释锚点二次定位，方案 §0 已列 6 站点符号）：
- **P2.1** Vendoring **VMA 固定版本**（建议 v3.3.0 或实施时最新稳定 tag，**钉死 tag/commit 写入 third_party 头注释**）至 `android/app/src/main/cpp/third_party/vma/vk_mem_alloc.h`（MIT 单头）；`VMA_IMPLEMENTATION` 落唯一 TU（`GpuAllocator.cpp`），其余包含点只声明；与既有 CMake `find_package(Vulkan)` 链接；头文件不得引入超出 NDK r27 Vulkan 1.1 面的符号。新增 `android/app/src/main/cpp/gpu/GpuAllocator.h/.cpp`（零 Android 依赖，契约照方案 D1 示意代码）：createBuffer/createImage/destroyBuffer/destroyImage/trimHostPool/stats；生命周期 = initDevice 成功后 create，`destroySurfaceGeneration`/shutdown 必须 destroy（与 TextureCache.clearEpoch 同入口顺序——cache 属 MR3，本批先落顺序注释与挂点）。`VK_EXT_memory_budget` 可用即用，不可用 heap size 估算写入 `GpuStats`。验收：编译过；stats 单测自洽
- **P2.2** 收口 6 站点 `vkAllocateMemory`（`:131` 白纹理→createImage DEVICE_LOCAL 优先 AUTO；`:921` offscreen；`:1777` VBO×3→createBuffer HOST_VISIBLE|COHERENT + **持久映射** `VMA_ALLOCATION_CREATE_MAPPED_BIT`；`:1905` staging；`:2046`/`:2396` 纹理 ASTC/RGBA 同一入口）全部改走 allocator，删除 6 份手抄 memory type 循环与**假回退**（不检查 property flag 的循环）；统一 `usage=AUTO` + 显式 `preferredFlags`；**大图 dedicated allocation**（`VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT`，阈值命名常量，对齐审计 A-5 `bg_horizontal` 4096×2300 量级）。验收：渲染 cpp 裸 `vkAllocateMemory` 调用点=0（注释/测试除外）；**`GpuAllocatorGuard` 守卫**（C++/CI 门禁形态——Kotlin 单测扫不到 native，用 gtest 静态断言或 CI ripgrep 红线，同 RNG 红线思路）
- **P2.3** staging 专用 host pool（linear/pool）+ `trimHostPool` 接 D3 CRITICAL（MR1 已交 TrimMemoryBridge）。验收：trim 后高水位可降（单测 + 真机清单项 pending-device）

■ 门禁：
- C++ 面为主：`pwsh scripts/build-desktop-jni.ps1` 重建桥 → PATH 前置 `C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` 及其 `x86_64-w64-mingw32\bin`、`%LOCALAPPDATA%/Android/Sdk/cmake/3.22.1/bin` → cmake build + ctest（基线见 MR1 完成报告登记数，全绿后 +本批新测试）
- NDK/arm64 编译必须过（本批动 native 构建图）；Kotlin 面如有接触：`compileReleaseKotlin testReleaseUnitTest detekt` 串行 `--max-workers=1`
- 开关纪律：P2 新路径受 `NativeEngineFlag.memorySubsystem` 门控（**OFF = 旧裸分配路径保留可用**，双轨回退；P1.* 止血不受控）；`MirrorReadOnlyGuardTest`/`DiffAuthoritativeTickTest` 保持绿；门禁失败修复走独立 commit
- 真机项（VMA 驱动/validation 抽检、trim 后水位）如实登记 pending-device

■ 交付：完成报告 `docs/report-MR2-completion-2026-09-23.md`（门禁实证数字 / **关键实施事实**——GpuAllocator 契约终态、destroySurfaceGeneration 挂点顺序、6 站点收口映射表，供 MR3（TextureCache 建其上）派发引用 / 诚实残余 / pending-device 清单）；收官笔 = 报告入库 + Phase 2 checkbox 全勾 + 工作区回净。提交前缀建议 `feat(memory)` / `perf(memory)` / `docs(memory)`。

■ 红线重申：降级链 Vulkan→GLES→Canvas 不可变，每帧绘制不持 `g_rendererLifecycleMutex`；GPU API 仅渲染线程；禁止顺手重构 drawFrame 既有结构（与地图线已并网代码共存）；VMA 不兼容兜底 = 开关关断回旧路径；禁魔法数字；同步 `android/docs/renderer-feature-checklist.md`。
