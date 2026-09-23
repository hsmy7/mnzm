# MR2 完成报告——内存管理根治 · Phase 2（GPU 子系统批）

> **批次**：MR2（P2.1–P2.3 全部三任务）
> **施工面**：工作树 `C:\Mnzm\XianxiaSectNative\.worktrees\memory-refactor`（分支 `w5/memory-refactor`）
> **日期**：2026-09-23 · **性质**：GPU 单一分配入口（VMA/GpuAllocator）+ staging 收缩闭环（D1 全量）
> **验收状态**：实施完成、门禁全绿；**未经看护/用户验收，不自登记 accepted**
> **方案**：[memory-refactor-implementation-plan-2026-09-23.md](memory-refactor-implementation-plan-2026-09-23.md) 第四部分 Phase 2
> **范围纪律**：本批只实施 Phase 2；Phase 3（TextureCache）未开工

---

## 一、任务逐条交付

### P2.1 VMA vendoring + GpuAllocator（D1）✅

- **Vendoring 钉死**：`android/app/src/main/cpp/third_party/vma/vk_mem_alloc.h`（MIT 单头，
  19,538 行）——上游 **tag `v3.3.0` = commit `1d8f600fd424278486eade7ed3e877c99f0846b1`**，
  原样复制、仅文件顶部插入 7 行 provenance 注释（本仓更新流程见 `third_party/vma/README.md`，
  守卫测试锁定 tag 字符串与 commit SHA）。VMA 3.x 最低 Vulkan 1.1，在 NDK r27 头面内。
- **`gpu/GpuAllocator.h/.cpp`**（零 Android 依赖——日志走 `#ifdef __ANDROID__` shim）：
  createImage / createBuffer / destroyImage / destroyBuffer / createStagingBuffer /
  trimHostPool / stats 契约面。**`VMA_IMPLEMENTATION` 唯一 TU = GpuAllocator.cpp**
  （守卫锁定其余渲染文件零 `#define VMA_IMPLEMENTATION`）。
  渲染层（VulkanBackend）不直触任何 `vma*` 调用，全部经 GpuAllocator 接口。
- **`gpu/GpuBudgetMath.h`**：预算估算兜底 + 大图 dedicated 阈值提取为**零 Vulkan 依赖
  纯函数**（uint64_t 原语参数）——desktop ctest 无 Vulkan 头/无设备，纯逻辑在此行为锁定。
- **`VK_EXT_memory_budget`**：`createLogicalDevice` 按设备支持条件启用（新增
  `deviceExtensionSupported` 探测）；不支持时 stats 预算走 `estimateBudgetFromHeaps`
  （DEVICE_LOCAL 堆容量和；无 DEVICE_LOCAL 标志回退全堆和，防零预算误判 OOM）。
- **VMA-on-Android 链接面修正**（实施中发现）：libvulkan.so（minSdk 24）只静态导出
  Vulkan 1.0 核心，VMA 默认 `VMA_STATIC_VULKAN_FUNCTIONS=1` 把 `vkBindBufferMemory2`
  等 1.1 符号直连成**链接期未定义**（NDK 构建实证）→ 改 `STATIC=0 + DYNAMIC=1`，
  仅 `vkGetInstanceProcAddr`/`vkGetDeviceProcAddr` 两个 1.0 入口静态链接，其余由 VMA
  在 `vmaCreateAllocator` 时经 proc-addr 运行时解析（设备过 MIN_VULKAN_API_VERSION=1.1
  闸后必然可取）。
- **双轨开关门**：`GpuAllocator::s_gateEnabled`（静态原子，默认 **false**）=
  `NativeEngineFlag.memorySubsystem` 的 native 生效真值。Kotlin 侧经
  `NativeBridge.nativeSetMemorySubsystem`（新导出，**唯一投递点 =
  `NativeBridge.ensureLoaded()` 库加载后同步**，业务代码禁止直调）投递 BuildConfig
  注入值。**GpuAllocator init 失败时门自动落回 OFF**（方案风险表首行「VMA 不兼容
  兜底」：关断回旧路径，渲染器继续工作）——native 生效门与 Kotlin 旗标可因此短暂
  不一致（本进程内降级），真值以 `isGateEnabled()` 为准。
- **验收**：编译过（desktop + NDK arm64 双绿）；stats 纯逻辑单测 6 用例（ctest）——
  阈值常量=16MiB、边界（2048² 恰达 → dedicated / 差 1 字节 → 不判）、
  DEVICE_LOCAL 求和、全堆回退、null 防御。设备路径 stats 实测归 pending-device。

### P2.2 收口 6 站点 vkAllocateMemory（D1）✅

**收口映射表（实施终态；行号为收口前原行号，供与方案 §0 对照）**：

| 原站点 | 函数 | 新路径 | 语义保留与差异说明 |
|---|---|---|---|
| `:131` 白纹理 | `createWhiteTexture` | `createImageWithMemory`（ON=VMA usage=AUTO + **HOST_ACCESS_RANDOM\|MAPPED**，required=HOST_VISIBLE）| **LINEAR tiling + CPU 直写保留**（1×1 纯色写入面；派发表中「DEVICE_LOCAL 优先 AUTO」按可映射正确性落地为 preferred=HOST_VISIBLE\|COHERENT——UMA 下落位无差，直写语义不可破） |
| `:921` offscreen ×3 | `createOffscreenTargets` | `createImageWithMemory`（preferred=DEVICE_LOCAL，required=0） | 渲染目标 GPU 常驻，无 CPU 访问面 |
| `:1777` VBO ×3 | `createVertexBuffer` | `createBufferWithMemory`（**persistentMap=true**：ON=VMA `VMA_ALLOCATION_CREATE_MAPPED_BIT`+`HOST_ACCESS_SEQUENTIAL_WRITE` 回 `pMappedData`；OFF=旧 vkMapMemory） | 每帧 memcpy 写入面逐字不变 |
| `:1905` staging | `ensureStagingBuffer` | **P2.3 双轨**（ON=staging pool 每上传自建自毁；OFF=旧单缓冲棘轮保留） | 见 P2.3 |
| `:2046` RGBA 纹理 | `uploadTextureImpl` | `createImageWithMemory`（preferred=DEVICE_LOCAL，required=0，mip 链解码字节计阈值） | 与 ASTC 同一入口 |
| `:2396` ASTC 纹理 | `uploadCompressedTexture` | `createImageWithMemory`（同上，ASTC 块计解码字节） | 同上 |

- **假回退删除**：6 份手抄 memory type 循环与「不查 property flag 直接取首个兼容
  类型」的静默回退（uploadTextureImpl/uploadCompressedTexture 两处）全部删除。
  统一为 `usage=AUTO + 显式 preferredFlags/requiredFlags`（ON 轨 VMA 选型）与
  **显式两段判定**（OFF 轨：pass 0 = preferred 全满足，pass 1 = 仅 required 的
  **有意识**降级——语义与 VMA AUTO 一致，UMA 设备 GPU 资源落 host 侧合法且显式）。
- **大图 dedicated**：`VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT`，阈值 =
  `GpuBudgetMath::kDedicatedImageThresholdBytes = 16 MiB`（命名常量；对齐审计 A-5：
  bg_horizontal 4096×2300 ≈ 37.7MiB、ui_button 3828×1384 ≈ 21.2MiB、主图集 2048²
  解码 16.0MiB 均判 dedicated；判定走 `shouldUseDedicatedAllocation` 单源，守卫锁定
  GpuAllocator 不得内联第二份判定）。
- **判轨销毁**：`VmaAllocation` 句柄随对象存储（`Texture.vmaAlloc` /
  `m_vertexAllocs[3]` / `m_offscreenAllocs[3]`），销毁按**句柄判轨**不重读全局开关
  （`destroyImageWithMemory` / `destroyBufferWithMemory`，OFF 轨保持先毁对象再
  free memory 的 VUID 顺序）。
- **验收**：**渲染 cpp 裸 `vkAllocateMemory` 调用点=0**（VulkanBackend.cpp 中仅存
  3 处，全部锁定在 OFF 轨辅助体内——createImageWithMemory/createBufferWithMemory/
  ensureStagingBuffer 各 1；`vkFreeMemory` 同样锁定在 OFF 轨辅助 + shutdown 终清）；
  `GpuAllocatorGuard` 守卫（ctest，RNG 红线同思路：**span 白名单**——裸分配/释放
  只允许出现在 `legacyTrackFunctions()` 函数体内，越界即红并给操作指引；`vkAllocateMemory`
  总数==3 断言防无感增长）。GLES 路径零接触（`GlesBackend final : Renderer2D`
  独立实现，默认 no-op）。

### P2.3 staging host pool + trimHostPool 接 D3 ✅

- **staging 专用 host pool**（ON 轨）：VMA custom pool——HOST_VISIBLE 类型
  （`vmaFindMemoryTypeIndexForBufferInfo` 探测定型，preferred HOST_CACHED），
  block = 32MiB（覆盖主图集 mip 链 ≈21.3MB 单块容纳）、max 4 blocks（**池水位硬顶
  128MiB**）、min 0。**每次上传自建自毁**（池内子分配）——旧「单缓冲棘轮只增不缩」
  的结构根因在算法层消除（D1 结构缺陷清单第 5 项）。
- **`trimHostPool`**：VMA 3.x **移除了 vmaTrimPool/vmaClearFreeMemory**（2.x API，
  实施中核实）——池级收缩唯一确定性手段 = **整池销毁重建**。安全性前提（锁纪律）：
  staging 分配的全部生存在 `m_gpuMutex` 上传临界区内（建→map→memcpy→submit+fence
  →毁同临界区），trim 在**同一互斥域**调用 ⇒ 调用时池内必然零存活分配。
- **消费点接线**（复用 MR1 底座，无新注册面）：`NativeBridge.beginFrame` 帧首
  `g_pendingRenderTrim.exchange(0)` → **新增 `Rhi::onMemoryTrim(int)` 虚接口**
  （默认 no-op，档位值 = `MemoryTrimLevel` 序数 0–3，`Rhi.h` 内
  `RenderTrimLevel` 枚举注释锁定与 Kotlin 枚举同值）→ `VulkanBackend::onMemoryTrim`
  override：**level ≥ AGGRESSIVE（含 CRITICAL）** → 持 `m_gpuMutex` →
  `trimHostPool()`。**帧首不持 `g_rendererLifecycleMutex`**（红线不涉——用的是
  submitFrame 每帧同款的 `m_gpuMutex` 既有互斥域）。GLES/软件后端继承默认 no-op，
  自身动作面随 P4.3。MR3 `TextureCache.trim` 同点接入（注释已留）。
- **验收（单测腿）**：守卫锁定消费点接线（NativeBridge `g_renderer->onMemoryTrim`
  + VulkanBackend `trimHostPool()` 挂点）+ 池创建/销毁路径在位。
  **验收（水位腿）**：trim 后高水位可降的设备实测 = pending-device（附录 A.4/A.5）。
- **OFF 轨 staging 行为逐字保留**（单缓冲棘轮 + 每上传 map/unmap），唯一结构差异 =
  OFF 轨 unmap 时点从「memcpy 后立即」统一为「fence 等待后」（unmap 只解除 CPU
  映射不影响已写入内容与设备可见性，同一上传临界区内无行为差异——报告如实登记）。

---

## 二、门禁实证（判绿数字）

| 门禁 | 命令 | 结果 |
|---|---|---|
| C++ 测试构建 | 工作树 desktop-test（既有配置：llvm-mingw + Ninja + `-DGAMECORE_BUILD_TESTS=ON`）+ `cmake . && cmake --build .` | 构建成功（新测试源入图） |
| C++ 全量 ctest | `ctest`（完整 llvm-mingw PATH） | **1581/1581 全绿，0 failed（70.5s）** = 分支基线 1567 + 本批新增 **14** |
| C++ 定向复核 | `ctest -R "GpuBudgetMath\|GpuAllocatorGuard"` | **14/14 绿**（GpuAllocatorGuardTest 8 + GpuBudgetMathTest 6，逐用例实证在跑） |
| NDK arm64 | `./gradlew.bat :app:externalNativeBuildRelease` | **BUILD SUCCESSFUL**（`libnative-renderer.so` 重建，`GpuAllocator.cpp.o` 在图；VMA-on-Android 链接修正后过——见 §四 过程记录） |
| Kotlin 编译+全量测试+detekt | `./gradlew.bat compileReleaseKotlin testReleaseUnitTest detekt --max-workers=1 "-Dgamecore.jni.path=<工作树>/libgamecorejni.so"` | **BUILD SUCCESSFUL in 6m 8s**——六模块 **8,112 / 0 失败 / 17 skip**（skip 与 MR1 基线一致零新增；XML 实测汇总：app 1017 / core:domain 1743 / core:data 827 / core:engine 3412 / core:ui 146 / feature:game 967） |
| 镜像契约门禁 | `MirrorReadOnlyGuardTest` / `DiffAuthoritativeTickTest`（含上项全量） | 全绿（无镜像面改动；IN8 Diff 桥带参实跑——gamecore C++ 零改动，MR1 桥产物复用合法） |
| JNI 计数门禁 | `node scripts/check-jni-count.mjs` | **total 85/85 通过**（基线补正 +1 豁免 +2 补登——见 §四 发现） |
| 规范分发门禁 | `node scripts/check-agent-instructions.mjs` | **全部通过**（2 条预存告警与 MR0/MR1 同族，非本批引入） |
| 副产物甄别 | `git status` + `git checkout --` | `atlas-rgba-manifest.json`（仅 generatedAt 时间戳再生成，留言区提示件）已还原；`sprite-uid-map.json` / `scene_uv_tables.h` 本批未被触碰 |

**ctest 计数口径**：1581 = 1567（MR1 收官口径）+ 14（GpuBudgetMathTest 6 +
GpuAllocatorGuardTest 8）。Kotlin 面零新增用例（本批测试面全在 C++/ctest，
8,112 与 MR1 完全一致 = 零回归）。

---

## 三、关键实施事实（供 MR3 派发引用）

1. **GpuAllocator 契约终态**（`android/app/src/main/cpp/gpu/GpuAllocator.h`）：
   - 单例 `GpuAllocator::get()`；**渲染线程独占**（VulkanBackend 上传路径的历史
     主线程执行构成本类文档化的「互斥执行域」——全程持 `m_gpuMutex`）。
   - `init(device, physDevice, VK_API_VERSION_1_1, budgetExtEnabled)` 幂等；
     `destroy()` 幂等（先毁 staging pool 再 vmaDestroyAllocator）。
   - `createImage(imageInfo, tag, decodedBytes, preferredFlags, requiredFlags,
     outImage, outAlloc, cpuWriteMapped=nullptr)`——decodedBytes ≥ 16MiB 自动
     dedicated；`cpuWriteMapped` 非空 = 加 RANDOM|MAPPED 位回常驻映射指针
     （白纹理专用面）。
   - `createBuffer(imageInfo…, persistentMap, …, outMappedData)`——persistentMap
     = MAPPED 位（VBO 用）；staging 走独立的 `createStagingBuffer`（池内子分配，
     恒 MAPPED）。
   - `stats()` 返回 `GpuStats{budget, usedBytes, blockCount, allocCount}`（uint64_t
     口径）；budget 扩展开启时 usedBytes = 驱动全局 usage（含 VMA 外占用），关闭时
     = VMA blockBytes、budget = GpuBudgetMath 堆容量估算。
   - **双轨门**：`GpuAllocator::setGateEnabled/isGateEnabled`（静态原子，默认
     false）= memorySubsystem 的 native 生效真值；initDevice 中 init 失败自动
     关断（本进程降级回旧路径，Kotlin 旗标不回写——诊断看 logcat
     `GpuAllocator` tag）。
2. **destroySurfaceGeneration 挂点顺序**（MR3 接线处）：
   `m_ready=false + vkDeviceWaitIdle` → offscreen/pipeline/swapchain 析构 →
   **白纹理 + m_textures 表逐条按轨销毁（= MR3 `TextureCache.clearEpoch()` 挂点，
   方案 D2.2「先 cache 后 allocator」）→ m_textures.clear() + descSet 版本递增
   （现有）** → VBO 按轨销毁 → 同步对象/commandPool/surface → …；
   `GpuAllocator::destroy()` 在 **`shutdown()`**（device 级终局，`vkDestroyDevice`
   之前），不在 destroySurfaceGeneration（allocator 跨 surface 纪元存活，与旧
   staging buffer 同级——纪元内只毁它分配的对象，不毁 allocator 本体）。
   **注意**：本批 destroySurfaceGeneration 纹理清空处已留 MR3 挂点注释。
3. **6 站点收口映射**：见 §一 P2.2 表（含每站点 preferred/required 终值与语义
   差异说明）。MR3 接 TextureCache 后，uploadTextureImpl/uploadCompressedTexture
   的 `createImageWithMemory(..., "texture-rgba"/"texture-astc", ...)` 两个调用点
   即为 cache 的 uploadFn 内核。
4. **trim 通道终态**（P2.3 后）：Kotlin `TrimMemoryBridge`（不动）→
   `NativeBridge.nativeMemoryTrim(level)` → `g_pendingRenderTrim` →
   `beginFrame` 帧首 `exchange(0)` → `g_renderer->onMemoryTrim(level)`
   （**新增 Rhi 虚接口**，GLES 继承 no-op）→ VulkanBackend：≥AGGRESSIVE 持
   `m_gpuMutex` 调 `GpuAllocator::trimHostPool()`（整池销毁重建，MR3
   `TextureCache.trim` 同点同锁接入）。gamecore 面（`GameCore::postMemoryTrim` →
   结算边界消费）MR1 已交，本批零改动。
5. **JNI 面**：+1 = `NativeBridge.nativeSetMemorySubsystem(Boolean)`（唯一投递点
   `ensureLoaded()`；计数豁免已随本批登记 `scripts/jni-count.baseline.json`）。
6. **测试基建**：新增 ctest 源 = `gamecore/test/gpu_allocator_guard_test.cpp`（8
   用例，span 白名单源码守卫——`legacyTrackFunctions()` 白名单在测试文件内，
   扩 OFF 轨辅助面须显式更新）+ `gpu_budget_math_test.cpp`（6 用例）；已登记
   `gamecore/test/CMakeLists.txt`。`MR1_CPP_ROOT` 宏复用（= cpp 根）。
7. **VMA 消费纪律**：渲染层只经 `GpuAllocator` 接口，不直触 `vma*`；VMA 3.x 无
   `vmaTrimPool`（收缩=池销毁重建，锁前提见上）；VMA-on-Android 必须
   `VMA_STATIC_VULKAN_FUNCTIONS=0 + VMA_DYNAMIC_VULKAN_FUNCTIONS=1` +
   `pVulkanFunctions` 传两个 1.0 proc-addr（GpuAllocator.cpp 注释固化）。

## 四、诚实残余与过程记录

| 项 | 状态 |
|----|------|
| NDK 构建第 1 轮 2 处编译错 | `vmaGetMemoryProperties` 二级指针签名误用（VMA 3.x API 与 2.x 记忆差）——修正后过 |
| NDK 构建第 2 轮 7 处链接错 | **VMA-on-Android 静态链接 1.1 符号不可用**（libvulkan minSdk 24 只导出 1.0）——`STATIC=0 + DYNAMIC=1` 动态解析修复，第 3 轮 BUILD SUCCESSFUL（§三.7 固化） |
| **check-jni-count 基线漏更（跨批发现）** | MR1（c2410bf1c）加了 `GameCoreBridge.nativeMemoryTrim` + `NativeBridge.nativeMemoryTrim` 两个导出但**未更新 jni-count.baseline.json**（MR1 门禁清单未含此脚本）——本批跑门禁红出，按提交溯源逐笔核实后**补登 +2**（82→84，文档注明漏更补登），MR2 自身新豁免 +1（84→85）。建议看护留档：后续批次门禁清单应含 `check-jni-count.mjs` |
| 白纹理「DEVICE_LOCAL 优先 AUTO」落地口径 | 派发表面语义与「CPU 直写」冲突（LINEAR 直写必须 HOST_VISIBLE）——按正确性落地为 preferred=HOST_VISIBLE\|COHERENT + required=HOST_VISIBLE + VMA MAPPED（UMA 下与 DEVICE_LOCAL 无位差）；如验收要求改 OPTIMAL+staging 臂请指示 |
| OFF 轨 staging unmap 时点 | 从「memcpy 后立即」统一为「fence 后」（两轨同一归还点）——语义无损（unmap 不影响缓冲内容与设备可见性），如实登记 |
| Changelog 双写 | **本批未写**——`memorySubsystem` 预发默认 OFF，玩家零感知（无玩家可感知修复，全局约束 13 条件不满足）；翻默认 true 的批次随该批写双 changelog（登记进翻默认前置清单） |
| GpuStats 的 JNI 发布 / MemoryBudgetView | 未实现（属 P4.4 Debug 页只读面；线程契约表四标注「实现随 MR2/MR4」中 MR2 只承诺 allocator 本体+stats 接口——stats() 接口已就绪待发布通道） |
| lintRelease | 未跑（派发门禁清单未含，与 MR1 口径一致；如需补跑请指示） |
| 性能非回归（全局约束 15） | 本批 OFF=默认 → 稳态帧/每旬结算零路径变化；ON 轨性能对照随翻默认批做 bench（附录 A.6 同表） |
| detekt baseline | 未触碰；detekt 全绿 |
| 存档格式 / RNG / 降级链 | 零变更（无 Proto/Entity/序列化改动；无新随机源；Vulkan→GLES→Canvas 链不动——GlesBackend 零接触） |
| 每帧绘制锁红线 | 未触 `g_rendererLifecycleMutex`；trim 走 `m_gpuMutex`（submitFrame 每帧同款互斥域，帧首短暂持锁——与既有锁纪律一致，非新增锁类别） |
| drawFrame 既有结构 | 零重构（本批改动全部在资源分配/销毁与 trim 消费点，未触碰 drawFrame/submitFrame 绘制路径与地图线已并网代码） |

## 五、pending-device 清单（真机项，如实登记）

1. **VMA 路径 validation 抽检**（方案风险表首行）：本地 api.properties 置
   `MEMORY_SUBSYSTEM_DEFAULT=true` 构建 → 开启 GPU debugging（validation layers
   可用时）跑主流程 + 图集/崖壁/地面全上传链，确认无 VMA 断言/validation 报错；
2. **CRITICAL trim 后 staging 水位回落实测**（P2.3 验收水位腿）：`am send-trim-memory
   com.xianxia.sect COMPLETE` → logcat `GpuAllocator: trimHostPool: released …`
   + 附录 A.4 VMA stats 对照位（onTrim 前后 `dumpsys meminfo` Graphics/Native Heap
   分项）；
3. **budget 扩展口径核对**：真机 logcat/stats 确认 `budget ext on` 与
   `VmaBudget` 数值合理（估算兜底臂在无 budget 设备上抽检一台）；
4. **双轨切换回归**：OFF（默认出包）与 ON 各跑一遍图集上传→切宗门×20→surface
   旋转重建，确认两轨渲染输出一致、无泄漏抬升（VmHWM 对照）。

## 六、提交

单笔收官提交（明确文件名 add，未用 `git add -A`；副产物已还原剔除）：

```
android/app/src/main/cpp/CMakeLists.txt                      （GpuAllocator.cpp 入构建图）
android/app/src/main/cpp/NativeBridge.cpp                    （nativeSetMemorySubsystem + beginFrame 消费点）
android/app/src/main/cpp/Rhi.h                               （RenderTrimLevel + onMemoryTrim 虚接口）
android/app/src/main/cpp/VulkanBackend.cpp                   （6 站点收口 + 双轨辅助 + trim/staging）
android/app/src/main/cpp/VulkanBackend.h                     （VMA 句柄成员 + 双轨声明 + budget 扩展）
android/app/src/main/cpp/gpu/GpuAllocator.h                  （新增，D1 分配器接口+双轨门）
android/app/src/main/cpp/gpu/GpuAllocator.cpp                （新增，VMA_IMPLEMENTATION 唯一 TU）
android/app/src/main/cpp/gpu/GpuBudgetMath.h                 （新增，零 Vulkan 依赖纯逻辑）
android/app/src/main/cpp/third_party/vma/vk_mem_alloc.h      （新增，VMA v3.3.0 vendored + provenance）
android/app/src/main/cpp/third_party/vma/README.md           （新增，钉死版本/更新流程）
android/app/src/main/cpp/gamecore/test/CMakeLists.txt        （新测试源登记）
android/app/src/main/cpp/gamecore/test/gpu_allocator_guard_test.cpp  （新增守卫 8 用例）
android/app/src/main/cpp/gamecore/test/gpu_budget_math_test.cpp      （新增单测 6 用例）
android/core/engine/src/main/java/com/xianxia/sect/core/nativebridge/NativeBridge.kt  （+1 导出 + ensureLoaded 投递）
android/docs/renderer-feature-checklist.md                   （gpu_allocator_vma 行）
scripts/jni-count.baseline.json                              （85 口径：MR1 补登 +2 / MR2 豁免 +1）
docs/memory-refactor-implementation-plan-2026-09-23.md       （Phase 2 checkbox 勾选）
docs/report-MR2-completion-2026-09-23.md                     （本报告）
```
