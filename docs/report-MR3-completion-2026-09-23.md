# MR3 完成报告——内存管理根治 · Phase 3（纹理缓存批）

> **批次**：MR3（P3.1–P3.3 全部三任务）
> **施工面**：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）
> **日期**：2026-09-23 · **性质**：TextureCache 键控 refCount + 全上传路径接入 + 上传峰值断引用（D2 全量）
> **验收状态**：实施完成、门禁全绿；**未经看护/用户验收，不自登记 accepted**
> **方案**：[memory-refactor-implementation-plan-2026-09-23.md](memory-refactor-implementation-plan-2026-09-23.md) 第四部分 Phase 3
> **范围纪律**：本批只实施 Phase 3；Phase 4 未开工

---

## 一、任务逐条交付

### P3.1 C++ TextureCache + clearEpoch + trim（D2）✅

- **`TextureCache.h/.cpp`**（renderer 内 `android/app/src/main/cpp/`，零图形 API/零 Android 依赖，桌面可编译）：
  - **TextureKey 位段 schema**：`[63:48] format | [47:32] variant | [31:0] assetId`；`pack()`/`unpack()` 恒等往返；`isValid()` 拒 assetId=0 与未登记 format（acquire 入口 `assert` + 生产拒绝）。同一资产 ASTC/RGBA **format 位段不同 → 键不同**（单测 `PackBitFieldsDoNotBleed` 锁定）。
  - **TextureEntry** `{ handle, refCount, pinned, pendingDestroy }`。
  - **acquire(key, pinned, UploadFn)**：miss/pendingDestroy → 锁外 upload → insert/替换；命中 → refCount++（零重传）。**uploadFn 返回 0 不插表、不计 uploads**（textureAcquire miss 探针不污染计数）。
  - **release(key, DestroyFn)**：--ref；==0 && !pinned → DestroyFn 入队 + pendingDestroy；pinned 归零保留；未知键/重复释放 no-op。
  - **trim(level)**：SOFT 无动作；AGGRESSIVE 驱逐空闲 evictable；CRITICAL 加码空闲 pinned；**refCount>0 恒不动**。**返回值 = 驱逐条目数**（含仅移表的 pendingDestroy——禁二次 destroyFn）。DestroyFn 锁外批量调用。
  - **clearEpoch()**：整表失效，**不调 DestroyFn**（物理销毁由纪元析构既有逐条销毁承担）。
  - **unpinAll()**：全部 pinned 降 evictable（D2.4）。
  - **stats()**：entries/pinned/pendingDestroy/uploads/hits/reuploads/trimEvictions（退役队列长度可观测）。
- **竞态二选一（D2.2 定死）**：release/trim 后物理销毁完成前同 key 再 acquire → **按 miss 重传替换 entry**（禁命中 pendingDestroy 旧 handle）；单测 `PendingDestroyThenAcquireReuploadsReplacingEntry` 锁定。
- **纪元挂点**：`VulkanBackend::destroySurfaceGeneration` 与 `GlesBackend::shutdown` 均调 `TextureCache.clearEpoch()`（**先 cache 后资源销毁**；MR2 留言位已落实）。
- **trim 挂点（双路径）**：
  - Vulkan：`VulkanBackend::onMemoryTrim` — AGGRESSIVE+ **先** `TextureCache.trim`（destroyTexture 自取 m_gpuMutex，故 trim 不得持锁防死锁）**后** gate 开时 `trimHostPool`。
  - GLES：新增 `GlesBackend::onMemoryTrim` override — AGGRESSIVE+ `TextureCache.trim`（destroyTexture 入待删队列）。
  - 消费点仍为 `NativeBridge.beginFrame` → `Rhi::onMemoryTrim`（MR2 通道）。
- **验收**：`texture_cache_test.cpp` **20/20 绿**——位段往返/串扰/非法键、miss 插表、同 key 双 acquire 零重传、失败不插表、非法键拒绝、归零 evictable 入队+pending、归零 pinned 保留、double-free 防御、pendingDestroy 重传替换、trim 三档、trim 零重传风暴、驱逐后重取新 handle、clearEpoch 纪元失效（旧 handle 不命中）/幂等、unpinAll 可回收、观测面 hits/uploads/pendingDestroy。

### P3.2 Kotlin 全上传路径接入 + JNI + pinned 迁移（D2.3/D2.4）✅

- **JNI 三导出**（挂既有 NativeBridge 模式，**不走 ActionId**）：
  - `textureAcquire(packedKey, pinned)`：命中 ref++/可升 pin；miss 探针恒 0 不插表不上传（生产上传走 upload* 内带 acquire）。
  - `textureRelease(packedKey)`：键控 --ref / 归零退役。
  - `textureUnpinAll()`：D2.4 pinned 迁移。
- **生产五上传口全部经 `TextureCache::get().acquire`**（C++ 侧内嵌，Kotlin 调用面签名不变）：
  | JNI 导出 | 键 | pinned |
  |---|---|---|
  | `uploadTextureDirect` | atlas/RGBA/single | true |
  | `uploadTextureMipChainDirect` | atlas/RGBA/mip | true |
  | `uploadGroundTextureDirect` | ground/RGBA/repeat | true |
  | `uploadRockTextureDirect` | rock/RGBA/repeat | true |
  | `uploadCompressedAtlas` | atlas/ASTC/mip | true |
  （ASTC 失败路径 acquire 返回 0 不插表；RGBA 回退走不同 format 键。）
- **`RendererTextureKeys`**（core:engine，与 C++ `texture_key` 同值同语义）：`KEY_ATLAS_ASTC` / `KEY_ATLAS_RGBA_MIP` / `KEY_ATLAS_RGBA_SINGLE` / `KEY_GROUND` / `KEY_ROCK` + `ALL_PRODUCTION_KEYS`。
- **替换 MR1 P1.4 过渡直调**：`AtlasAsyncPipeline.previousRoundTextureIds` → `previousRoundKeys`；`start()` 入口 `textureRelease` 逐键释放 +（有弃置键时）`textureUnpinAll()`；`cancel()` 清键登记。**生产源码零 `NativeBridge.destroyTexture` 调用**（导出保留给测试注入）。
- **ASTC→RGBA 失败重试**：失败不插表 → 无脏 ASTC 键；`start()` 重跑经不同键 acquire（方案「先 release 旧 key 再 acquire 新 key」由轮次入口释放 + 异键 acquire 覆盖）。
- **Pinned 集**：主图集三键 + 地面 + 岩石 = pinned（当前 surface 必需）；预取 `SectAtlasPrefetch` 为 **CPU 字节缓存**，不经 GPU cache（上传时才 acquire）。
- **崖壁 `IslandCliffTextureLoader`**：**已随 S6 退役**（JNI 崖壁 7 口已删，ground_boundary 替代）——本批无接入面，如实登记 N/A。
- **Pinned 迁移挂 SceneUpdateChannel**：`releaseBaselines()` 构造尾调 `onPinnedMigrate`；生产 `VulkanRenderBackend` 注入 `NativeBridge.textureUnpinAll()`；单测注入计数替身（默认 no-op，JVM 无 native）。`AtlasAsyncPipeline.start` 弃置轮亦 unpin，防 ASTC→RGBA 换臂后旧键 ref=0 仍 pinned 滞留。
- **验收**：`TextureUploadPathGuardTest` **5/5 绿**——五上传口均含 acquire、Atlas 无 destroyTexture 过渡直调且有 textureRelease、键常量只经 RendererTextureKeys 禁裸 pack 字面量、三 JNI 导出在位、P3.3 断引用调用点/方法体静态断言；`SceneUpdateChannelTest` 增 pinned 迁移用例。

### P3.3 上传峰值断引用（M-P1-7 / 附录 A）✅

- **`AtlasPayload.releaseHostBuffers()`**：置空 `ktx`（ByteArray ~22MB）、`rgbaMipPixels`/`rgbaPixels`/`groundPixels`/`rockPixels`（direct ByteBuffer）及对应宽高；**不清 `softwareBitmap`**（Canvas 仍需）。
- **调用点**：`AtlasAsyncPipeline.start` 在 `onReady(texId)` **之前**调用一次——JNI 返回后立刻断宿主引用，GC 可回收；staging 走 MR2 D1 host pool（C++ 侧 `ScopedByteArrayElements` 在 acquire 的 uploadFn 返回即析构 Release）。
- **C++ 侧峰值配合**：`uploadCompressedAtlas` 的 GetByteArrayElements **移入 acquire 的 uploadFn 内**——cache 命中时零 pin 零拷贝。
- **验收**：GuardTest 静态断言（releaseHostBuffers 方法体覆盖 ktx/mip/ground 置空 + start 调用点）；**真机 VmSize 峰值/纪元×20/ASTC 回退计数/trim 重传风暴** → **pending-device**（附录 A）。

---

## 二、门禁实证（判绿数字）

| 门禁 | 命令/口径 | 结果 |
|---|---|---|
| C++ 桌面测试构建 | desktop-test（llvm-mingw + Ninja + `-DGAMECORE_BUILD_TESTS=ON`）`cmake . && cmake --build .` | 构建成功（texture_cache_test 入图） |
| C++ 全量 ctest | 完整 llvm-mingw PATH `ctest` | **1601/1601 全绿，0 failed（65.13s）** = 分支基线 1581 + 本批 TextureCache **20** |
| TextureCache 定向 | `ctest -R TextureCacheTest` / gtest filter | **20/20 绿** |
| 桌面 JNI 桥 | `pwsh scripts/build-desktop-jni.ps1` 等价 clang 直调 | **重建成功**（2026-09-23 12:53，libgamecorejni.so 9,670,144 B） |
| NDK arm64 | `./gradlew.bat :app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（TextureCache.cpp / NativeBridge / Vulkan / GLES 入图） |
| Kotlin 编译 | `compileReleaseKotlin --max-workers=1 -Dgamecore.jni.path=…`（强制 rerun） | **BUILD SUCCESSFUL** |
| Kotlin 全量测试+detekt | `testReleaseUnitTest detekt --max-workers=1 -Dgamecore.jni.path=…` | **BUILD SUCCESSFUL in 2m22s**——六模块 XML 汇总 **8,118 / 0 失败 / 0 错误 / 17 skip**（基线 8,112 + 本批 **6** = Guard 5 + SceneUpdateChannel pinned 1；skip 与 MR1/MR2 一致零新增） |
| 镜像契约 | `MirrorReadOnlyGuardTest` / `DiffAuthoritativeTickTest`（含上项） | 全绿（零镜像/存档/RNG 改动） |
| GLES 路径 | 双路径：GlesBackend clearEpoch + onMemoryTrim→TextureCache.trim；cache 只调 RHI | 回归随全量 Kotlin 绿 + NDK 双面编译绿 |
| JNI 计数门 | `node scripts/check-jni-count.mjs` | **total 88/88 通过**（基线 85→88，+3 豁免已写入 `scripts/jni-count.baseline.json` `_doc`） |
| 规范分发门禁 | `node scripts/check-agent-instructions.mjs` | **全部通过**（2 条预存告警与 MR0–MR2 同族：atlas-manifest basename 警告 + feature/game 链路超长警告，非本批引入） |
| 副产物甄别 | `git checkout --` | `atlas-rgba-manifest.json` / `sprite-uid-map.json` 已还原；`scene_uv_tables.h` 未触碰；`local.properties`/`keystore.properties` gitignored 未入库 |

**ctest 计数口径**：1601 = 1581（MR2 收官口径）+ 20（TextureCacheTest）。
**Kotlin 计数口径**：8,118 = 8,112（MR2）+ 6（本批新测试）。

---

## 三、关键实施事实（供 MR4 派发引用）

1. **TextureCache 契约终态**（`android/app/src/main/cpp/TextureCache.h/.cpp`）：
   - 单例 `TextureCache::get()`；调用方注入 UploadFn/DestroyFn（零图形 API）；内部 mutex 防御 stats 跨线程读，**uploadFn 在锁外执行**（防与 `m_gpuMutex` 嵌套死锁）。
   - 位段：`format∈{1=RGBA8, 2=ASTC4x4}`；`variant∈{0=single, 1=mip, 2=repeat, 3=repeatMip}`；`assetId∈{1=atlas, 2=ground, 3=rock}`。
   - `acquire` 返回 0 = 键非法/上传失败；成功必非 0。`release`/`trim` 的 DestroyFn 必须锁外调用（实现已保证）。
   - **uploads 仅计成功上传**（failed/探针不计）——trim 风暴断言与附录 A 观测依赖此语义。
   - `trim` 返回**驱逐条目数**（含 pendingDestroy 仅移表项），非 DestroyFn 调用数。
2. **竞态二选一决策**：**miss 重传替换**（非阻塞等待退役）。pendingDestroy 条目永不作为命中返回；旧 handle 只由后端延迟队列排空。
3. **JNI 导出面（+3，基线 85→88）**：`NativeBridge.textureAcquire(Long, Boolean): Int` / `textureRelease(Long)` / `textureUnpinAll()`。豁免理由已写入 `scripts/jni-count.baseline.json`。**upload\* 五口签名不变**（acquire 内嵌 C++）。`destroyTexture(Int)` 导出保留（测试/非键控），生产 Atlas 路径零调用。
4. **pinned 迁移挂点**：
   - `SceneUpdateChannel.releaseBaselines()` → `onPinnedMigrate()`；
   - 生产注入点 = `VulkanRenderBackend` 构造 `SceneUpdateChannel(..., onPinnedMigrate = { NativeBridge.textureUnpinAll() })`；
   - `AtlasAsyncPipeline.start()` 在释放上一轮键后若有弃置键亦 `textureUnpinAll()`；
   - 新场景上传经 upload\* 的 `acquire(pinned=true)` 重提升。
   - **releaseBaselines 生产常态调用点仍随 MR1 口径**（宗门切换 terrain 引用覆盖语义；trim 面由 uiResource + GPU trim 承担）——若 MR4 要求 enterSect 显式调用 `releaseSceneBaselines`，属可选接线。
5. **trim 消费点**：`beginFrame` → `Rhi::onMemoryTrim` → Vulkan：**TextureCache.trim（不持锁）→ trimHostPool（持 m_gpuMutex）**；GLES：TextureCache.trim。SOFT 无 GPU/纹理动作。
6. **纪元顺序**：`destroySurfaceGeneration`/`GlesBackend::shutdown`：`clearEpoch` → 既有逐条物理销毁 → `m_textures.clear()`；`GpuAllocator::destroy` 仍在 `shutdown()`（跨纪元存活）——「先 cache 后 allocator」已满足。
7. **Kotlin 键镜像**：`RendererTextureKeys`（core:engine）与 C++ 同值；GuardTest 锁生产 trackAcquiredKey 只引用该对象常量。

---

## 四、诚实残余与过程记录

| 项 | 状态 |
|----|------|
| 崖壁 IslandCliffTextureLoader 接入 | **N/A**——S6 已删崖壁 JNI 与加载器（ground_boundary 替代）；清单 `island_cliff_edge` 行仍存但上传面不存在 |
| 预取 vs GPU cache | `SectAtlasPrefetch` 为 CPU ByteArray 缓存，**不直接 acquire**；消费时走 upload\* 内 acquire。若验收要求「预取阶段也建 GPU 键」属新需求 |
| releaseBaselines 生产接线 | 显式入口在（backend 观测面）；enterSect 未新增强制调用（沿 MR1「引用覆盖已满足」裁量）。pinned 迁移已挂该入口 + Atlas 轮次 unpin |
| 白纹理 | **不经 TextureCache**（`createWhiteTexture` 内部创建、纪元析构销毁，无 Kotlin 上传面）——D2.4「白纹理 pinned」按 surface 纪元生命周期实现，非键控 pinned |
| CountingUploader 拷贝坑 | 单测必须 `bindUpload(up)` 包装（std::function 按值拷贝 callable，直传写副本致 calls 恒 0）——已修并成文注释 |
| StatsDelta 基准点 | 断言用绝对计数（SetUp clearEpoch 后净态）或**操作前**捕获 delta；事后构造 delta 恒 0——测试已按此改写 |
| 反引号方法名 | Kotlin backtick 禁 `.`——`D2.4`→`D2-4`、`P3.3`→`P3-3` 已改 |
| build-desktop-jni.ps1 包装 | 直跑 pwsh 包装器曾超时；**等价 clang 直调成功重建**（脚本内单次 clang 命令逐字复现）。产物时间戳 12:53 本批 |
| Changelog 双写 | **本批未写**——内部缓存重构 + `memorySubsystem` 默认 OFF，**无玩家可感知变化**（全局约束 13 条件不满足）；沿 MR2 豁免先例。翻默认 true 批随该批写双 changelog |
| lintRelease | 未跑（派发门禁清单未含，与 MR1/MR2 口径一致） |
| 性能非回归（全局约束 15） | 本批 OFF 默认 → 稳态路径经 cache 查表为哈希查找；ON 对照随翻默认批 bench |
| GLES 回归 | NDK 编译过 + Kotlin 全量绿；cache 只调 RHI（GLES `onMemoryTrim`/`destroyTexture`/`upload*`），无直碰 GL |
| detekt baseline | 未触碰；detekt 全绿 |
| 存档/RNG/镜像契约 | **零触碰**（无 Proto/Entity；无新随机源；`MirrorReadOnlyGuardTest`/`DiffAuthoritativeTickTest` 含于全量 8,118 绿） |
| 降级链 | 不可变（Vulkan→GLES→Canvas）；GLES 与 Vulkan 双面接 trim/clearEpoch/acquire |
| 每帧绘制锁红线 | 未触 `g_rendererLifecycleMutex`；trim 走既有 `m_gpuMutex`/队列 |

---

## 五、pending-device 清单（真机项，如实登记）

| # | 项 | 来源 |
|---|---|---|
| 1 | 纪元反复重建 ×20 条目不累积（clearEpoch 后 stats.entries 归零、新纪元 acquire 不复用旧 handle） | 批次门禁 |
| 2 | ASTC 失败回退后 GPU 纹理计数不双倍（RGBA 臂 acquire 与 ASTC 键分离，无脏条目） | 批次门禁 / D2 验证门槛 |
| 3 | trim 不重传风暴（AGGRESSIVE 后继续渲染 upload 计数不涨；pinned 主图集保持） | 批次门禁 / 方案红线 |
| 4 | 上传峰值 VmSize：ASTC 22MB 断引用后回落；稳态 ≤2 份、峰值受控 1 份额外（附录 A.1/A.2） | P3.3 / 附录 A |
| 5 | （承 MR2）GpuAllocator stats/trim 水位设备实测 | 附录 A.4/A.5 |
| 6 | （承 MR1）GPU 计数 mock 断言面——现由 TextureCache 单测 + GuardTest 源码守卫覆盖 JVM 面；真机计数仍 pending | MR1 开放项转登记 |

**累计 pending-device**：MR0 附录 A 采集 + MR1 三项 + MR2 四项 + MR3 六项（见各报告 §五；真机批次建议合并跑）。

---

## 六、收官笔材料清单

- [x] 本完成报告 `docs/report-MR3-completion-2026-09-23.md`
- [x] 方案 Phase 3 checkbox P3.1/P3.2/P3.3 全勾
- [x] `android/docs/renderer-feature-checklist.md` 增 `texture_cache` 行（Vulkan/GLES/测试三面 ✅）
- [x] `scripts/jni-count.baseline.json` 85→88 + 豁免理由
- [x] 工作区副产物还原（manifest / sprite-uid-map）
- [ ] 收官 commit（明确文件名 add，前缀 `feat(memory)` / `docs(memory)`）——见提交笔
