【MR-3 内存管理根治 · Phase 3 纹理缓存批（P3.1–P3.3；依赖 MR2 已交的 GpuAllocator）】

■ 会话纪律：本会话只实施本批；完成后出完成报告并停止，不开始 Phase 4；**不得自登记 accepted**；如实登记失败、未完成与 pending 项，禁止虚报。

■ 施工面（唯一）：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）。**禁止改动主树** `C:\Mnzm\XianxiaSectNative` 下任何文件。开工前先读看护台账《给实施会话的留言》区（只读）：`C:\Mnzm\XianxiaSectNative\docs\memory-refactor-watch\dispatch-ledger.md`。提交一律明确文件名 `git add <file>`，禁止 `git add -A`（`android/local.properties`、`android/keystore.properties` 绝不入库）。

■ 必读（按序）：
1. 工作树根 `AGENTS.md` + 相关模块级 AGENTS.md；
2. `docs/memory-refactor-implementation-plan-2026-09-23.md`：全局约束 1–15 + **D2 设计节全文**（TextureKey 位段 schema/生命周期与竞态/Kotlin 接线/Pinned 集/上传峰值）+ Phase 3 全部任务；
3. `docs/threading-contract.md` MR0 登记条目（textureAcquire/textureRelease 通道）；MR1/MR2 完成报告（`docs/report-MR1-completion-2026-09-23.md`、`docs/report-MR2-completion-2026-09-23.md`）的「关键实施事实」节；`android/docs/renderer-feature-checklist.md`。

■ 本批任务（照方案第四部分 Phase 3 逐条）：
- **P3.1**（C++）`TextureCache`（renderer 内，**渲染线程独占**）：
  - `TextureKey` 位段 schema（禁止裸 uint64 自造键）：`[63:48] format | [47:32] variant | [31:0] assetId`；`pack()`；构造期断言 assetId≠0 且 format 合法；同一资产不同压缩臂必须不同键
  - `TextureEntry { handle, refCount, pinned, pendingDestroy }`；`acquire(TextureKey, UploadFn)`（miss→upload→insert）/ `release(TextureKey)`（--ref；==0 && !pinned → RHI.destroyTexture 入队）/ `trim(TrimLevel)`（先 evictable）/ `clearEpoch()`（整表失效）
  - 生命周期与竞态（方案 D2.2 逐条）：`destroySurfaceGeneration()` **必须**调 `TextureCache.clearEpoch()` + `GpuAllocator.destroy()`（顺序：先 cache 后 allocator——MR2 已留挂点）；纪元后 acquire 不得命中旧 handle；`release` 后物理销毁完成前同 key 再 acquire **禁止命中 pendingDestroy 条目**——miss 重传替换 entry 或阻塞至退役完成，**二选一定死并写单测**；trim 批量 release 沿用帧边界延迟释放（`MAX_FRAMES_IN_FLIGHT`），退役队列长度可观测
  - 验收：单测 refCount/pin/trim/纪元失效（clearEpoch 后 acquire 不得命中旧 handle）/同 key 双 acquire
- **P3.2** Kotlin 全上传路径接入：`AtlasAsyncPipeline`（ASTC/RGBA、失败重试 `allowCompressed=false` → **先 release 旧 key 再 acquire 新 key**，两臂 format 位段不同）、崖壁 `IslandCliffTextureLoader`、地面纹理、预取，全部走同一 cache；JNI `textureAcquire`/`textureRelease`（优先挂现有 bridge 模式；若走 ActionId 则只加 `gen-action-ids.mjs` 条目，**不新增散落导出**）；**替换 MR1 P1.4 过渡直调 destroyTexture**；Pinned 集 = 主图集 KEY_ATLAS、当前 surface 必需白纹理/地面（预取/非当前 edge = evictable）；**切宗门/surface 时 pinned 迁移交 `SceneUpdateChannel` 切换路径**（旧宗门 pinned 降 evictable 或 release，新宗门上传成功后提升——防漏 unpin 致 trim 腾不掉）。验收：`TextureUploadPathGuardTest`——生产 upload 调用点必须经 cache
- **P3.3** 上传峰值：完成 JNI 后立刻断 Kotlin `ByteArray` **与 Direct ByteBuffer** 引用（direct 缓冲用完置 null/复用池归还，禁止长期持有 `AllocateDirect` 句柄）；staging 走 MR2 D1 host pool；目标稳态 ≤2 份、峰值受控 1 份额外。验收：consume/断引用后单测或静态断言 + 附录 A 峰值项（真机部分 pending-device）

■ 门禁：
- C++ 面：`pwsh scripts/build-desktop-jni.ps1` 重建桥 → PATH 前置 `C:/Users/cp050/llvm-mingw/llvm-mingw-20260616-ucrt-x86_64/bin` 及其 `x86_64-w64-mingw32\bin`、`%LOCALAPPDATA%/Android/Sdk/cmake/3.22.1/bin` → cmake build + ctest（基线见 MR2 完成报告登记数）
- Kotlin 面：`compileReleaseKotlin testReleaseUnitTest detekt` 串行 `--max-workers=1`；GLES 路径回归测试绿（cache 只调 RHI 接口，不直碰 GL；`destroyTexture` 保持性能方案队列语义）
- 双路径纪律（全局约束 12）：Vulkan + GLES +（软渲 Bitmap 预算）三面验收，同步 `android/docs/renderer-feature-checklist.md`；`MirrorReadOnlyGuardTest`/`DiffAuthoritativeTickTest` 保持绿；门禁失败修复走独立 commit
- 真机项（纪元反复重建 ×20 条目不累积、强制 ASTC 失败回退纹理计数不双倍、trim 不重传风暴）如实登记 pending-device

■ 交付：完成报告 `docs/report-MR3-completion-2026-09-23.md`（门禁实证数字 / **关键实施事实**——TextureCache 契约终态、竞态二选一决策、JNI 导出面、pinned 迁移挂点，供 MR4 派发引用 / 诚实残余 / pending-device 清单）；收官笔 = 报告入库 + Phase 3 checkbox 全勾 + 工作区回净。提交前缀建议 `feat(memory)` / `perf(memory)` / `docs(memory)`。

■ 红线重申：降级链不可变；GPU API 仅渲染线程、Kotlin 只投递 acquire/release/trim 命令不直触 cache 表；trim 路径禁止重传风暴（upload 计数断言）；禁魔法数字；存档/RNG/镜像契约零触碰。
