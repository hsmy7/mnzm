# 内存管理根治实施方案

> **对应方案**：[compose/memory-management-refactor](compose/spec/memory-management-refactor.md)（差距分析 + 五轨根治设计，选项 2「彻底重构」的执行设计）
> **对应审计**：[memory-audit-2026-09-22.md](memory-audit-2026-09-22.md)；交叉：[longrun-stability-remediation-plan.md](longrun-stability-remediation-plan.md)、[performance-remediation-plan-2026-09-09.md](performance-remediation-plan-2026-09-09.md)
> **执行方式**：任务带 checkbox，按 Phase 分批交给执行会话逐任务实施；每任务自带验证门槛；Phase 结束跑全量检查。
> **文档性质**：**具体实施方案（可照单实施）**——本文件即最终态，不含「后续优化」尾巴；实施另开代码分支，不与本 docs 分支混提。

**目标**：在**不改变**存档磁盘格式、RNG 分区语义、AUTHORITATIVE 镜像只读契约、Vulkan→GLES→Canvas 降级链的前提下，落地一层横跨 RHI / gamecore / 资产 / 平台的内存子系统，使 P0×5 / 关键 P1 结构性问题在**不变量层面**不可复现。

**架构总览**：七项全局决策（D1–D7）承载全部修复——GPU 单一分配入口 + VMA 子分配（D1）；纹理键控缓存 + 真实释放调用方 + 纪元失效（D2）；统一 Trim 压力协议**并收敛既有 trim 多路径**（D3）；位图几何扩容 + 加载 reserve（D4）；状态基线去双 DOM **且复用 R2 列级导出/protobuf 信封**（D5）；场景/缓存有界驱逐（D6）；GLES 每帧重分配消除（D7）。

**技术栈**：Kotlin 2.2.20 / C++20（NDK r27，arm64-v8a）、Vulkan 1.1 / GLES2、Compose UI、VMA（vendoring 单头）、nlohmann::json（逐步退出运行时基线）、MMKV/既有 `DynamicMemoryManager`。

**决策分级**：**架构级重构**（本文件为选项 2 全案实施设计；选项 1 止血 = Phase 0–1 全量（含 P1.3），与 compose §14 一致；可单独合入但不替代本全案）。

---


## 背景与目标

**背景**：[memory-audit-2026-09-22](memory-audit-2026-09-22.md) 证明项目无自研内存管理系统（GPU 裸分配、纹理零调用方释放、常驻 JSON 第二状态、trim 空转）；compose 特性文档已选定选项 2「彻底重构」为根治路径。本文件将该路径落成**可按 Phase 执行**的实施设计。

**成功标准**：

| # | 标准 | 验收 |
|---|------|------|
| 1 | 裸 vkAllocateMemory 生产调用点归零 | GpuAllocatorGuard（C++/CI 门禁） |
| 2 | 同 key 纹理不双传；refCount==0 必达 destroy；纪元不悬垂 | TextureUploadPathGuard + 单测 + clearEpoch 测试 |
| 3 | 位图加载 O(N²) 消失 | ColumnResizeGrowthTest |
| 4 | 稳态无双全量 nlohmann 业务树 | BaselineMemoryTest + BaselineFieldCoverageGuard + 对拍 |
| 5 | CRITICAL trim 后 CPU/GPU 水位可回落 | TrimDispatchTest + TrimConsumerCountGuard + 附录 A 真机 |
| 6 | 镜像只读/存档格式/RNG/降级链零回归；与 R2 列级导出等价 | 双门禁 + ColumnExportEquivalence 不回退 + 既有全量测试 |
| 7 | 方案含 CLAUDE 九章 + design-plan-review 自检 | 附录 C |
| 8 | 内存改动不劣化 tick/帧耗时 | 性能非回归对照（全局约束 15） |

## 本分册明确不含（范围声明）

| 缺陷 ID | 处置 |
|---------|------|
| **M-P1-8** 磁盘 437MB webp / 双编码 edge | **轨 F 资产磁盘治理另立项**（compose §4.3 轨 F）；不阻塞本根治合入 |
| **M-P1-10** VkInstance initDevice 失败泄漏 | 归 **longrun/render init 路径**（与 initSurface 幂等同族）；实施前按 §九 diff，本册不重复设计 |
| **M-P2-1/2/5/6** 每帧 Kotlin 短命对象 / JobSystem `std::function` / CacheLayer 自驱逐 / Room 无 LIMIT | 廉价项 P2-7/8 已进 P1.6、P2-3/4 轻量半边进 P1.7；其余进第八部分债表，根治合入后清偿 |

## §0 基线核对（编写时二次实码）

| 项 | 现状（本方案基线） | 证据 |
|---|---|---|
| 裸 `vkAllocateMemory` | **6 站点仍存在**：`:131` 白纹理、`:921` offscreen、`:1777` VBO、`:1905` staging、`:2046` RGBA、`:2396` ASTC | `VulkanBackend.cpp` |
| `destroyTexture` | 实现已入队 `m_retiredTextures`（性能方案 Task 3.4），**生产仍零调用方** | audit A-2；JNI 已有导出 |
| 位图扩容 | `ensureRowCapacity` 精确 `new[]`，无几何增长、全仓加载路径 0 `reserve` | `column_dirty.h:657` |
| 基线 DOM | `baselineJson_` 常驻；`restBaseline_` 常驻 | `dirty_tracker.h:92` 等 |
| `sectMapCache` | 仅声明 + `getOrPut`，无驱逐 | `SectMapController.kt` |
| `onLowMemory` | GameActivity 空实现 | `GameActivity.kt:1114` |
| 预算设施 | `DynamicMemoryManager` **已存在**（设备/Canvas 分档）；`GCOptimizer` 有 SOFT/HARD/CRITICAL | CODE_WIKI；`core/data/memory/` |
| 镜像契约 | 反向通道已删；`importToNative` / `updateMirror`；`MirrorReadOnlyGuardTest` | CODE_WIKI AUTHORITATIVE |

行号可能漂移，执行时以**符号名 + 注释锚点**二次定位（同 longrun 方案 §0 纪律）。

---

## 全局约束（每个任务隐含遵守）

1. **降级链** `Vulkan → GPU GLES → CPU Canvas` 不可变；每帧绘制不持 `g_rendererLifecycleMutex`。
2. **C++ 优先**：分配器/纹理 cache 策略/位图/基线存储在 gamecore 或 renderer C++；Kotlin 只做 Trim 桥、上传编排、UI stats。禁止把 Android API 写进 `gamecore/**`。
3. **AUTHORITATIVE 镜像只读**：禁止复活 Kotlin→C++ 增量回导；导入仅 `importToNative` 全量；导出/投影仅 `updateMirror` 路径。`MirrorReadOnlyGuardTest` + `DiffAuthoritativeTickTest` 必须保持绿。
4. **存档兼容**：磁盘 `.sav` 格式与 Proto 契约**零变更**；无 Room Entity/Migration。
5. **惰性结算不变量**：Trim/驱逐**禁止**清除 `cultivationCheckpoints`、`lastSettled*`、生产 `completionMonth`、账本业务字段，以及**角色卡池进度**（碎片 map / 星级 map / 保底 pity / 寻访历史，若已由角色重构 G01 落地）——只释放**资源**不释放**进度语义**。白名单由 `TrimPreserveFieldsGuardTest` 枚举锁死（新增进度字段必须同步登记，否则测试打红）。角色卡池方案文档若未入仓，实施前按 main 分支 `docs/design/character-gacha-implementation.md` 对齐；文件缺失时以本约束清单为权威并登记债项。
6. **线程**：GPU API 仅渲染线程；引擎线程只投递 trim 请求；`stateStore.update` 锁纪律不变。**`TextureCache` 表与 `GpuAllocator` 由渲染线程独占**；Kotlin 上传编排只投递 acquire/release/trim 命令，不直触 cache 表（与 `g_renderer` 无锁裸指针纪律一致）。gamecore 容器收缩钩子仅允许在**引擎线程 tick 边界**执行，禁止进入 JobSystem 并行段或结算中途。
7. **线程契约登记**：`nativeMemoryTrim` / `textureAcquire` / `textureRelease` / stats 读均为新增跨线程面——**实现前必须先登记** `docs/threading-contract.md`（表一线程职责 + 表四通道），再写代码；未登记不得合并。
8. **配置开关**：并入既有 **`NativeEngineFlag` 族**（与 `mirrorProtobufTransport` / `gameViewProjection` / `dirtyColumnExport` 同构，不另造第二套开关体系）。新增 `memorySubsystem`（BuildConfig/本地默认；RemoteConfig 键预留 `memory.*`）——OFF = 本方案新路径关闭、旧路径可用，直至债表触发删除。**P1.1–P1.5 止血修复不进开关**（无双轨语义）；仅 P2–P4 新子系统受控。RemoteConfig 未绑定期间开关为编译期/本地——线上事故无法热关断，事故预案 = 发紧急版本切默认 false（债表登记热关断缺口）。
9. **Room mmap 不变量**：`GameDatabase` 已设 `mmap_size = 0`（防 `onTrimMemory` 时内核解除 mmap 导致 SIGSEGV）——内存方案**禁止**为省内存重新开启 mmap。
10. **命名常量**：禁魔法数字；新上限/阈值全部 `const`/`named`。
11. **测试串行** `--max-workers=1`；detekt baseline 只缩不增；既有测试不回退。
12. **双路径**：凡上传/trim/顶点缓冲变更，Vulkan + GLES +（软渲 Bitmap 预算）三面验收；同步 `android/docs/renderer-feature-checklist.md`。
13. **Changelog**：**每次合入**玩家可感知修复（含选项 1 的 Phase 0–1 单独合入）均按 CLAUDE 12.4 **双 changelog** 一起写，不得只挂在 P4.6。
14. **Wiki 同步**：合并前更新 `CODE_WIKI.md` 性能基础设施 + `docs/architecture.md` 内存相关小节。
15. **性能非回归**：内存验收通过的同时，每旬结算耗时与稳态帧耗时不得劣化超过既有 bench 噪声带（对照 `DirtyTrackerBench` / `MirrorSegmentProjectionBenchTest` 方法论；超带宽必须说明并回写）。

---

## 根治判据（什么算「根治」）

| # | 判据 | 反例（补丁） | 正例（根治） |
|---|---|---|---|
| R1 | 新代码路径无法复现同类问题 | 只在某次上传后手动 free | 一切 Device 内存经 `GpuAllocator`，散落 `vkAllocateMemory` 被守卫测试打红 |
| R2 | 修复完整生命周期 | 给一张图补 destroy | `TextureCache` acquire/release + refCount==0 必达 `destroyTexture` |
| R3 | 修复成本结构而非调参 | 加大位图一次 capacity | 几何增长 + 加载 `reserve`，O(N²) 在算法层消失 |
| R4 | 压力响应可闭环 | onLowMemory 再打一行 Log | Trim 枚举贯通 Java→JNI→GPU/CPU/Asset，档位单测锁死 |
| R5 | 不破坏 AUTHORITATIVE/确定性 | 为省内存恢复反向增量 | 基线换存储实现，协议边界不动，对拍全绿 |

---

## 第一部分：全局架构决策（D1–D7）

> **编号说明**：本册 D1–D7 与 [longrun-stability-remediation-plan](longrun-stability-remediation-plan.md) 的 D1–D7 **不是同一编号空间**；跨文档引用请带文档名。

### D1. GpuAllocator + VMA 单一分配入口（承载 M-P0-2/M-P0-3，轨 A）

**结构缺陷**：6 处手抄 memory type 且回退不查 flag；一对象一 `VkDeviceMemory`；无统计/预算；staging 棘轮无收缩。

**设计**：

1. Vendoring **VMA 固定版本**（建议 v3.3.0 或实施时最新稳定 tag，**钉死 commit/tag 写入 third_party 头注释**）至 `android/app/src/main/cpp/third_party/vma/vk_mem_alloc.h`（MIT，单头）。`VMA_IMPLEMENTATION` 落在唯一 TU（`GpuAllocator.cpp`），其余包含点只 `#include` 声明。与现有 CMake `find_package(Vulkan)` 链接；头文件不得引入超出 NDK r27 Vulkan 1.1 面的符号。
2. 新增 **`android/app/src/main/cpp/gpu/GpuAllocator.h/.cpp`**（renderer 内，零 Android 依赖）：

```cpp
// 契约（示意，实现以此为准）
struct GpuBufferDesc { VkDeviceSize size; VkBufferUsageFlags usage; const char* tag; };
struct GpuImageDesc  { /* width/height/format/mips/usage/tag */ };
struct GpuStats { VkDeviceSize budget, usedBytes; uint32_t blockCount, allocCount; };

class GpuAllocator {
public:
  // 生命周期：initDevice 成功后 create，destroySurfaceGeneration/shutdown 必须 destroy
  // （与 TextureCache.clearEpoch 同一入口顺序，禁止跨 surface 纪元复用 VmaAllocator）
  static GpuAllocator& get();
  Result createBuffer(const GpuBufferDesc&, VkBuffer&, VmaAllocation&);
  Result createImage (const GpuImageDesc&,  VkImage&,  VmaAllocation&);
  void   destroyBuffer(VkBuffer&, VmaAllocation&);
  void   destroyImage (VkImage&,  VmaAllocation&);
  void   trimHostPool();           // staging/host pool 收缩
  GpuStats stats() const;
};
```

3. **收口映射**（全部改走 allocator，删除 6 份手抄 memory type 循环）：

| 现站点 | 新路径 |
|--------|--------|
| `:131` 白纹理 | `createImage` DEVICE_LOCAL 优先 AUTO |
| `:921` offscreen | `createImage`（`m_renderScale!=1` 才存在） |
| `:1777` VBO ×3 | `createBuffer` HOST_VISIBLE\|COHERENT + **持久映射**（VMA `VMA_ALLOCATION_CREATE_MAPPED_BIT`） |
| `:1905` staging | **专用 host pool**（linear/pool），`trimHostPool` 可收缩 |
| `:2046`/`:2396` 纹理 | `createImage` ASTC/RGBA 同一入口 |

4. 删除假回退（不检查 property flag 的循环）；统一用 VMA `usage= AUTO` + 显式 `preferredFlags`。
5. **大图 dedicated allocation**：单张解码后 ≥ 大图阈值（命名常量，对齐审计 A-5 `bg_horizontal` 4096×2300 / `ui_button` 3828×1384 量级）的 `createImage` 使用 `VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT`，避免子分配池碎片。
6. `VK_EXT_memory_budget`：VMA 启用即用；不可用则 heap size 估算写入 `GpuStats`。
7. **iOS 对等**：`GpuAllocator` 接口保留；Metal 侧后续 `MTLHeap`（platform-abilities 登记，不在本任务实现 Metal）。

**验证门槛**：渲染 cpp 中裸 `vkAllocateMemory` 调用点 = 0（注释/测试除外）；`GpuAllocatorGuard` 守卫；stats 单测自洽。

---

### D2. TextureCache 键控 refCount + 真实释放调用方（承载 M-P0-1、M-P1-7，轨 B）

**结构缺陷**：`destroyTexture` 零调用；无 key/refCount；同纪元重复上传累积；失败重试可双份。

**设计**：

1. C++ **`TextureCache`**（renderer 内，**渲染线程独占**）：

```cpp
// TextureKey 位段 schema（禁止裸 uint64 自造键）：
//   [63:48] format（ASTC/RGBA/…） | [47:32] variant（mip 链/edge 变体） | [31:0] assetId
// 同一资产的不同压缩臂必须不同键；碰撞策略 = 构造期断言 assetId 非 0 且 format 合法。
struct TextureKey {
  uint32_t assetId;
  uint16_t format;
  uint16_t variant;
  uint64_t pack() const;
};
struct TextureEntry {
  uint32_t handle;
  uint32_t refCount;
  bool pinned;
  bool pendingDestroy; // 已逻辑释放、物理销毁未完成（m_retiredTextures 未排空）
};
uint32_t acquire(TextureKey, UploadFn&&); // miss → upload → insert
void     release(TextureKey);             // --ref；==0 && !pinned → RHI.destroyTexture（入队）
void     trim(TrimLevel);                 // 先 evictable（pinned=false）
void     clearEpoch();                    // surface 纪元死亡：整表失效，禁止跨纪元命中旧 handle
```

2. **生命周期与竞态**：
   - `VulkanBackend::destroySurfaceGeneration()` **必须**调用 `TextureCache.clearEpoch()` + `GpuAllocator.destroy()`（顺序：先 cache 后 allocator）；纪元切换后任何 `acquire` 不得命中旧 handle。
   - `release` 后物理销毁完成前同 key 再 `acquire`：**禁止**命中 `pendingDestroy` 条目——按 miss 重传并替换 entry（旧 handle 继续走 `m_retiredTextures` 帧边界排空），或阻塞至退役完成（二选一，实施时定死并写单测）。
   - trim 批量 release 沿用帧边界延迟释放（`MAX_FRAMES_IN_FLIGHT`），单帧退役队列长度可观测。

3. **Kotlin 接线**（全部 upload 路径）：
   - `AtlasAsyncPipeline`：ASTC/RGBA、失败重试 `allowCompressed=false` → **先 release 旧 key 再 acquire 新 key**（两臂 format 位段不同）；
   - 崖壁 `IslandCliffTextureLoader`、地面纹理、预取：同一 cache；
   - JNI：`textureAcquire` / `textureRelease`（优先挂现有 bridge 模式；若走 ActionId 则只加 `gen-action-ids.mjs` 条目，**不新增散落导出**）；线程契约先登记 `docs/threading-contract.md`。

4. **Pinned 集**：主图集 KEY_ATLAS、当前 surface 必需白纹理/地面 = pinned；预取/非当前 edge = evictable。**切宗门/surface 时**：旧宗门 pinned 降为 evictable 或 release，新宗门 pinned 在上传成功后提升——pinned 迁移写进 `SceneUpdateChannel` 切换路径，防漏 unpin 导致 trim 永远腾不掉。
5. **上传峰值**：完成 JNI 后立刻断 Kotlin `ByteArray` **与 Direct ByteBuffer** 引用（direct 缓冲用完置 null / 复用池归还，禁止长期持有 `AllocateDirect` 句柄）；staging 走 D1 host pool；目标稳态 ≤2 份、峰值受控 1 份额外（缓解 audit B-1/B-2）。
6. GLES：`destroyTexture` 保持性能方案队列语义；cache 只调 RHI 接口，不直碰 GL。

**验证门槛**：`TextureUploadPathGuardTest`——生产 upload 调用点必须经 cache；同 key 双 acquire 单测；ASTC 失败回退单测（无双份、无泄漏）；纪元失效单测（`clearEpoch` 后 acquire 不得命中旧 handle）。

---

### D3. TrimMemoryBridge：统一压力协议 + 复用既有分级（承载 M-P1-2/M-P1-3，轨 E；升级 fps-P3.3）

**结构缺陷**：`onLowMemory` 空；多档 trim 只 Log；**且已存在多路并行 trim 消费者**（`XianxiaApplication.notifyMemoryPressure` 广播、`CacheLayer`/`GameDataCacheMemoryPressure` 自注册 `ComponentCallbacks2`、`GameActivity.onTrimMemory` 已清 map/`SectAtlasPrefetch`、`GameLoopDelegate`）——若再「新增」Bridge 而不收敛，会长成第 4～5 套压力路径，与 §4.0.2「复用不重造」自相矛盾。

**设计**：

1. 枚举 **`MemoryTrimLevel { NONE, SOFT, AGGRESSIVE, CRITICAL }`** 映射：

| Android | Level | 动作 |
|---------|-------|------|
| `TRIM_MEMORY_UI_HIDDEN` | SOFT | 丢 UI 位图可驱逐项；`sectMapCache` 非当前 |
| `RUNNING_LOW` / `MODERATE` | SOFT | + `TextureCache.trim(SOFT)`；对齐 `GCOptimizer` SOFT |
| `RUNNING_CRITICAL` / `BACKGROUND` | AGGRESSIVE | + `trimHostPool`；CacheLayer 已有压力轴并入 |
| `COMPLETE` / `onLowMemory` | CRITICAL | + 强制 evictable 全清；可选 `mallopt(M_PURGE)`（API 守卫） |

2. **`TrimMemoryBridge.kt`（app/feature）= 收敛入口，不是新增旁路**：
   - **吸收/替换** `XianxiaApplication.notifyMemoryPressure` 对游戏侧的分发、`CacheLayer.onTrimMemory` 中与 GPU/纹理相关的档位动作、`GameActivity.onTrimMemory` 现有分支、`GameLoopDelegate` 的 trim 反应——迁移后生产 `onTrimMemory`/`onLowMemory` 的**游戏内存消费者数量 = 1（Bridge）**（守卫断言）；
   - Application 与 GameActivity **双发去重/去抖**（同 level 短窗合并，命名常量）；
   - 回调 → JNI `nativeMemoryTrim(level)` → renderer 消费（渲染线程队列，**禁止**在 trim 回调里做重上传）→ gamecore 只读 stats / **tick 边界**容器收缩钩子（禁止 JobSystem 并行段/结算中途）。
3. **复用不新建**：设备档读 `DynamicMemoryManager`/`GpuTierDetector`；阈值轴与 `GCOptimizer` 对齐；只读 **`MemoryBudgetView`** 合并 `GpuAllocator.stats()`（Kotlin 侧只读，不写第二套分级）。
4. RemoteConfig 预留键：`memory.budget.lowMb` 等（本地默认兜底；未绑定不崩溃）。

**验证门槛**：`TrimDispatchTest`（Robolectric 分档）；`TrimConsumerCountGuardTest`（生产 trim 消费者 = Bridge 一处）；CRITICAL 后 staging 高水位下降或回基线（真机清单）；trim 路径无纹理重传风暴（单测 mock upload 计数）。

---

### D4. 位图几何扩容 + 加载 reserve（承载 M-P0-5，轨 C；选项 1 亦含）

**结构缺陷**：`ensureRowCapacity` 按精确 16B 步进 → 加载 N 弟子 O(N²) memcpy。

**设计**：

```cpp
// column_dirty.h ensureRowCapacity 语义改为：
// need = max(rows, capacity * kGrowthFactor, kMinRows) 向上取整到字
// kGrowthFactor = 2; kMinRows = 1024; 命名常量
// kMinRows 仅作用于**预期大表/加载路径**（DiscipleStore 列、5000 弟子档）；
// 小集合用 kMinRowsSmall（更小）或按 need 几何增长，禁止全局抬到 1024 浪费
// loadFromVector / 批量入口：先 columnTracker.reserve(rows) 一次到位
```

1. 同步审查 `DiscipleStore` 列 `reserve`：存档加载路径对主要 vector 列 `reserve(n)`（不改布局/确定性语义）。
2. **禁止**在本任务做列布局紧凑化（债表：WS-1 数据导向存储）。与 R1.4 `ColumnDirtyTracker` 写屏障挂点共存：只改 `ensureRowCapacity` 成本结构，不动列枚举/标脏协议。

**验证门槛**：`ColumnResizeGrowthTest`——N 次 append 分配次数 ≤ `2*ceil(log2(N/min))+O(1)`；5000 弟子加载路径有 `reserve` 调用（结构断言或计数 mock）。

---

### D5. 状态基线去双 DOM（承载 M-P0-4、M-P1-6，轨 D；AUTHORITATIVE 硬约束）

**结构缺陷**：`baselineJson_` + diff 双全量树 + `dump` 字符串；import 三峰。

**与 native-engine-refactor R1/R2 的关系（强制对齐，禁止双改）**：R1.4 `ColumnDirtyTracker` 列级写屏障、R2.2 protobuf 镜像传输（`exportDirtyProto`）、R2.3 `GameDataFieldPatch` + `GameViewStore` 投影、R2.4 列级导出接生产（`NativeEngineFlag.dirtyColumnExport` 默认 true）**已落地**。本轨是 R2.4 登记残余的收口（「C++ rest 域每封序列化仍在」「全量序列化基线」），**不是**从零设计第二套基线/信封协议：

- **必须复用**：`diffTreeSegments` / `stateWithoutDisciplesToJson` / `diffToTree` / `exportDirtyTree` / `GameDataFieldPatch` 消费面 / `NativeEngineFlag` 旗标族（新开关 `memorySubsystem` 与 `dirtyColumnExport`/`mirrorProtobufTransport`/`gameViewProjection` 正交，禁止第四种信封）。
- **禁止**：重做列级导出；改 protobuf schema 语义；在本轨引入与 GameDataFieldPatch 并行的字段应用协议。

**设计（合法形态——见 compose §4.0.5）**：

1. **存储替换**：`DirtyTracker` 基线从「整棵 nlohmann GameState DOM」改为：
   - 弟子列：**已是 R1.4/R2.4 列级 dirty + 列基线**——只做与几何扩容共存的容量/`reserve` 整理，不重写协议；
   - 非弟子 rest 域：**字段/块级基线**（定长字段直接存 POD/小 blob；集合类按现有 `kEntityCollections` 逐实体序列化块），替代整树 `stateToJson` 常驻；与 `diffTreeSegments` 共享循环体，保证与全量 diff 构造等价。
2. **diff 路径**：结构比较生成 changed/removed 树**仅在导出瞬时**；禁止同时持有 `baselineJson_` 与 `cur` 两棵全量业务树常驻。
3. **dump**：复用 `std::string` buffer + `reserve`，避免临时巨型字符串叠加。
4. **import**：解析到独立 `GameState` 临时对象 → 校验 → **C++ 内**切换 `state_` 指针并释放旧树 → 再走既有 `importToNative`/`importStateInternal` 归一化族（与 `normalizeLedgers`/`resetBaseline` 同位）；失败回滚到旧树，**禁止**半程双树常驻。镜像对齐仍由 import 成功后的 `reprojectAll`/基线建立点收敛（R2.3 既有馈送点），不另开反向通道。
5. **协议边界不变**：落盘仍是既有出口；Kotlin 消费仍走 R2.3 `GameViewStore`/`GameDataFieldPatch` + `updateMirror` 投影语义；**禁止**反向增量通道。
6. 每旬 rest 域导出：在既有 `diffTreeSegments`/`stateWithoutDisciplesToJson` 上做块/dirty 包减载（**不**另造信封）；与 `dirtyColumnExport` 混合分发共存，JNI 载荷 metric 下降。

**验证门槛**：`DiffAuthoritativeTickTest` 100 旬绿；存档往返对拍绿；`MirrorReadOnlyGuardTest` 绿；新增 `BaselineMemoryTest`——稳态不同时存在两棵全量 nlohmann 业务树（断言封装 API 使用方式/计数）；新增 `BaselineFieldCoverageGuardTest`——`GameData.serializer` 元素名 ↔ 基线字段表双射（缺字段即红并点名，复用 R2.3 fail-fast 模式）。

---

### D6. 场景与缓存有界驱逐（承载 M-P1-1/M-P1-2，轨 E + 半资产）

**设计**：

1. `SectMapController.sectMapCache`：`LinkedHashMap(accessOrder=true)` + `removeEldestEntry` 上限（命名常量，如 8 宗门）或与 `MemoryBudgetView` 联动；`SceneUpdateChannel.pushedTerrain` 切换时释放旧引用。
2. `SaveLoadViewModel._l2Sprites` 等插入型缓存：补上限或 LRU（与 CacheLayer 预算档一致）。
3. 场景切换：**不**强上完整 unload 架构（避免动渲染常驻 Box）；至少保证 **CPU 侧旧宗门 map 条目可驱逐** + trim 时清非当前（P1-2 的资源级缓解；完整 per-scene GPU 卸载登记债表，因当前无 per-scene GPU 资源）。
4. 账本：tick/settle 路径补与 import 同语义的 cap（B-6）——调用既有 `normalizeLedgers` 或等价裁剪，双端一致（R5）；裁剪后压力路径可 `shrink_to_fit`（禁止帧/tick 热路径调用）。

**验证门槛**：cache 上限单测；trim 后条目数下降；账本 cap 守卫（import 与 tick 裁剪同一常量）。

---

### D7. GLES 每帧分配消除（承载 M-P1-9，轨 B）

**设计**：

1. 顶点：初始化时 `glBufferData`/`glBufferStorage` 一次按 `MAX_VERTICES` 预分配；每帧 **`glBufferSubData` 只传脏范围**（能力不足机型保留整批路径但记录 metric）。
2. `draw()` clamp 到 `MAX_VERTICES`（与 Vulkan 溢出守卫对齐）。
3. `PendingUpload.pixels`：**vector 池**复用，避免 16.7MB 级反复 alloc；锁外拷贝纪律保持。
4. 同步 `android/docs/renderer-feature-checklist.md` + `SoftwareCanvasBackend` 相关测试不回退。
5. **软渲 Bitmap 预算**（compose §4.0.2 对齐）：`SoftwareCanvasBackend`/Compose 侧 Bitmap 缓存在 `onTrim(SOFT+)` 时按既有 `DisposableEffect` 模式释放可重建位图——并入 D3 收敛面，**不得**另开第四条 trim 监听。

**验证门槛**：稳态帧无整批 `glBufferData`（能力允许时）；池化后连续上传峰值不线性涨；GLES 路径回归测试绿。

---

## 第二部分：影响范围清单（文件 — 变更 — 说明）

| 文件路径 | 变更类型 | 说明 |
|----------|----------|------|
| `android/app/src/main/cpp/third_party/vma/vk_mem_alloc.h` | **新增** | VMA 单头 vendoring（钉死 tag/commit） |
| `android/app/src/main/cpp/CMakeLists.txt` / `cpp/CMakeLists.txt` | 修改 | 链 VMA、新 TU（`VMA_IMPLEMENTATION` 仅 `GpuAllocator.cpp`） |
| `.../cpp/gpu/GpuAllocator.h/.cpp` | **新增** | D1 |
| `.../cpp/VulkanBackend.cpp/.h` | 修改 | 6 站点收口、staging pool、假回退删除、stats；`destroySurfaceGeneration` 接 `clearEpoch` |
| `.../cpp/GlesBackend.cpp/.h` | 修改 | D7 顶点/上传池、trim 钩子 |
| `.../cpp/TextureCache`（renderer） | **新增** | D2（渲染线程独占） |
| `docs/threading-contract.md` | 修改 | **先登记** trim/texture/stats 跨线程通道，再实现 |
| `core/engine/.../NativeEngineFlag.kt`（或既有旗标文件） | 修改 | 新增 `memorySubsystem`，与 R2 三旗标正交 |
| `.../cpp/NativeBridge.cpp` | 修改 | texture acquire/release、nativeMemoryTrim、stats JNI |
| `.../cpp/gamecore/include/.../column_dirty.h` | 修改 | D4 几何扩容 + reserve |
| `.../cpp/gamecore/include/.../dirty_tracker.h` + `src/dirty_tracker.cpp` | 修改 | D5 基线存储 |
| `.../cpp/gamecore/src/game_core.cpp` | 修改 | import 峰值顺序；账本 tick cap |
| `.../TextureCache`（renderer） | **新增** | D2 |
| `.../AtlasAsyncPipeline.kt` | 修改 | acquire/release |
| `.../IslandCliffTextureLoader.kt` 等上传 | 修改 | 经 cache |
| `.../SectMapController.kt` / `SceneUpdateChannel.kt` | 修改 | D6 LRU/引用 |
| `.../GameActivity.kt` / `XianxiaApplication.kt` | 修改 | onLowMemory/trim 接桥 |
| `.../TrimMemoryBridge.kt` | **新增** | D3 **收敛** Application/CacheLayer/GameActivity/GameLoopDelegate 现有 trim 分发 |
| `core/data/memory/DynamicMemoryManager.kt` | 修改 | **只读扩展**预算视图/分发，不重写 |
| `core/data/cache/CacheLayer.kt` / `GameDataCacheMemoryPressure.kt` | 修改 | GPU/纹理档位动作迁入 Bridge，避免双消费 |
| `XianxiaApplication.kt` | 修改 | `notifyMemoryPressure` 游戏侧分发收敛到 Bridge |
| 守卫/单测 | **新增** | 见测试方案（含 `TrimPreserveFieldsGuardTest` / `BaselineFieldCoverageGuardTest` / `TrimConsumerCountGuardTest`） |
| `android/docs/renderer-feature-checklist.md` | 修改 | 双路径勾选 |
| `CODE_WIKI.md` / `docs/architecture.md` | 修改 | 合并前同步 |
| `changelog_entries.json` + `CHANGELOG.md` | 修改 | **每次合入**玩家可感知修复均写（含 Phase 1 单独合入） |
| `docs/platform-abilities.md` | 修改 | GpuAllocator/Metal 登记 |

**经济影响**：不适用（无货币源汇）。  
**iOS 影响**：D1/D2/D3 接口为跨平台面；Android 实现仅 app/renderer；Metal 见 platform-abilities。  
**隐私合规**：不新增 SDK/权限/网络端点——不适用。

---

## 第三部分：兼容性分析

| 面 | 结论 |
|----|------|
| 存档格式 | **不变** |
| Room Migration | **无** |
| 镜像契约 | 不变；双门禁必须绿 |
| RNG/确定性 | 不引入新随机；对拍夹具固定 |
| 开关回退 | `NativeEngineFlag.memorySubsystem=false` → 走旧分配/旧基线路径（双轨期间；P1.* 止血不受控） |
| 混布版本 | 单进程无协议；跨版本读档 = 格式未变 |

---

## 第四部分：实施 Phase 与任务（执行顺序）

> Phase 是**执行编排**，方案本身仍是全量最终态（CLAUDE：禁止拆成残缺方案）。  
> **选项 1（唯一权威口径）= Phase 0–1 全量（P0.* + P1.*，含 P1.3 onLowMemory/Trim）**，与 compose §14「T4+T6+onLowMemory+纹理 release」对齐；**根治合入以 Phase 0–4 全部勾选为准**。

### Phase 0 — 开关与基线（0.5d 量级，先合）

- [ ] **P0.0** 在 `docs/threading-contract.md` 登记 `nativeMemoryTrim` / `textureAcquire` / `textureRelease` / MemoryStats 读通道与线程归属（渲染线程独占 cache/allocator；Kotlin 只投递） — acceptance: 契约表可审；实现分支引用该登记 (covers: 全局约束 6/7)
- [ ] **P0.1** 增加 `NativeEngineFlag.memorySubsystem`（并入既有旗标族，不另造开关体系）BuildConfig/本地 + 运行时读取入口；OFF 时 P2–P4 新路径 no-op（**P1.* 止血不受控**） — acceptance: 开关存在且默认值经评审（建议先 `false` 预发、根治验收后 `true`）(covers: 全局约束 8)
- [ ] **P0.2** 落真机/模拟器内存基线采集脚本或清单命令（`dumpsys meminfo`、`dumpsys meminfo` Native Heap 分项、heapprofd 抽样、VMA stats 将来对照）写入本方案附录 A — acceptance: 清单可复制执行 (covers: 验收)

### Phase 1 — 止血 + 压力闭环（可先交付）

- [ ] **P1.1** `ensureRowCapacity` 几何增长 + 加载 `reserve`（D4） — acceptance: `ColumnResizeGrowthTest` 过 (covers: D4)
- [ ] **P1.2** `sectMapCache` LRU/上限 + `pushedTerrain` 旧引用释放（D6 上半） — acceptance: 上限单测过 (covers: D6)
- [ ] **P1.3** `TrimMemoryBridge` **收敛** GameActivity/Application/CacheLayer/GameLoopDelegate 现有 trim + `onLowMemory` 实装 + 档位映射 + 双发去抖（D3） — acceptance: `TrimDispatchTest` 过；`TrimConsumerCountGuardTest` 过；CRITICAL 不空转 (covers: D3)
- [ ] **P1.4** `AtlasAsyncPipeline` 失败/重试路径 **手动** release 旧纹理 id（在 TextureCache 合入前的过渡：直接调既有 JNI `destroyTexture`） — acceptance: 同纪元重试 GPU 纹理数不增（单测/mock） (covers: M-P0-1 过渡; depends: 无)
- [ ] **P1.5** 账本 tick/settle cap 与 import 同源（D6 下半） — acceptance: 双端同常量守卫 (covers: M-P1-4)
- [ ] **P1.6** `jbytesToString` 空指针检查 + 大 JNI release RAII 化（M-P2-7/M-P2-8，廉价根因项） — acceptance: 空入参单测；无裸大缓冲泄漏路径 (covers: M-P2-7/8)
- [ ] **P1.7** `m_pendingDraws` reserve + `m_textures` 查找收窄（M-P2-3/M-P2-4 轻量半边） — acceptance: 单测/结构断言；不改语义 (covers: M-P2-3/4)

### Phase 2 — GPU 子系统（D1 + staging trim）

- [ ] **P2.1** Vendoring VMA + `GpuAllocator` + CMake — acceptance: 编译过；stats 单测 (covers: D1)
- [ ] **P2.2** 收口 6 站点 `vkAllocateMemory`；持久映射 VBO；删除假 memory type 回退；大图 dedicated — acceptance: 裸调用点=0；`GpuAllocatorGuard` 绿 (covers: D1; depends: P2.1)
- [ ] **P2.3** staging host pool + `trimHostPool` 接 D3 CRITICAL — acceptance: trim 后高水位可降（单测+真机清单项） (covers: D1+D3; depends: P2.1, P1.3)

### Phase 3 — 纹理缓存（D2）

- [ ] **P3.1** C++ `TextureCache` + JNI acquire/release + `clearEpoch` — acceptance: 单测 refCount/pin/trim/纪元失效 (covers: D2; depends: P2.2)
- [ ] **P3.2** Atlas/崖壁/地面/预取全路径接入；替换 P1.4 过渡直调；pinned 迁移交 `SceneUpdateChannel` — acceptance: `TextureUploadPathGuardTest` 绿 (covers: D2; depends: P3.1)
- [ ] **P3.3** 上传后 ByteArray **与 Direct ByteBuffer** 引用及时断开/归还 — acceptance: consume/断引用后单测或静态断言 + 附录 A 峰值项 (covers: M-P1-7; depends: P3.2)

### Phase 4 — 状态基线 + GLES + 收口

- [ ] **P4.1** `DirtyTracker` **rest 域**基线去全量 DOM（分块/字段基线，复用 R2 `diffTreeSegments`）+ import 峰值顺序 — acceptance: 对拍全绿；无双全量树断言；`BaselineFieldCoverageGuardTest` 绿 (covers: D5)
- [ ] **P4.2** 每旬 rest 域导出减载（块/dirty 包，**不**另造信封；与 `dirtyColumnExport` 正交） — acceptance: Diff tick 绿；JNI 载荷 metric 下降 (covers: D5; depends: P4.1)
- [ ] **P4.3** GLES D7 顶点预分配 + 上传池 + clamp + 软渲 Bitmap trim — acceptance: 能力允许时无每帧整批 `glBufferData`；checklist 更新 (covers: D7)
- [ ] **P4.4** `MemoryBudgetView` 只读 stats 接 UI Debug 页 — acceptance: Debug 可见分类 MB (covers: D3 可观测)
- [ ] **P4.5** Guard 测试总装 + 全量 `testReleaseUnitTest` 串行 + detekt + `compileReleaseKotlin` + 性能非回归对照 — acceptance: BUILD SUCCESSFUL / 测试全绿 / bench 不超噪声带 (covers: 全局)
- [ ] **P4.6** 双 changelog + CODE_WIKI + architecture + platform-abilities + renderer checklist — acceptance: 文档同步完成（Phase 1 若单独合入，其 changelog **当时**写，不推迟到本任务） (covers: 影响范围; depends: P4.5)

**依赖摘要**：`P0 → P1`（可并行部分）；`P2.1 → P2.2 → P3 → P4.1`；`P1.3 → P2.3`；`P4.5` 收口；`P4.6` 最终。无环。P1.6/P1.7 可与 P1.1–P1.5 并行。

---

## 第五部分：测试方案

| 类型 | 内容 | 墙钟预算 |
|------|------|----------|
| 单元 | GpuAllocator stats；TextureCache refCount/pin/纪元失效；Trim 档位；位图增长上界；账本 cap | 单测 <2s，合计 <40s |
| 守卫 | `GpuAllocatorGuard`（禁散落 vkAllocateMemory，**C++/CI 门禁形态**——Kotlin 单测扫不到 native，用 gtest 静态断言或 CI ripgrep 红线，同 RNG 红线思路）；`TextureUploadPathGuardTest`；`TrimConsumerCountGuardTest`；`TrimPreserveFieldsGuardTest`；`BaselineFieldCoverageGuardTest`；`MirrorReadOnlyGuardTest` 不回退 | <5s |
| 对拍 | `DiffAuthoritativeTickTest`、存档往返、`DiffMonthSettlementTest` 既有；列级↔全量与 R2.4 `ColumnExportEquivalenceTest` 不回退 | 既有预算内 |
| 回归 | `:app` + `:core:engine` 串行全量；detekt；compileReleaseKotlin | CI 既有 |
| 性能非回归 | 每旬结算 / mirror 段 bench 对照（`DirtyTrackerBench` / `MirrorSegmentProjectionBenchTest` 方法论）；不超噪声带 | 既有 bench |
| 渲染 | Vulkan + GLES 双路径；软渲 Bitmap trim 不崩溃 | Robolectric/清单 |
| 真机 | 附录 A：切宗门×50、5000 弟子档、前后台×50、CRITICAL trim 后 meminfo + Native Heap 分项/heapprofd | 手动，不进 PR 墙钟 |
| 对抗性审查 | 绕过 cache/allocator；trim 内重传；双 DOM 回潮；反向通道复活；**第四条 trim 旁路**；**第四种镜像信封**；TextureCache 跨纪元悬垂句柄 | 审查阶段 |

**确定性**：禁非分区 RNG；固定夹具。

---

## 第六部分：风险评估与兜底

| 风险 | 缓解 |
|------|------|
| VMA 与驱动/时序不兼容 | `NativeEngineFlag.memorySubsystem=false` 回退裸分配；validation 抽检 |
| 基线协议错误导致同步脏 | 双跑对拍期；开关回滚旧 JSON 基线；**复用 R2 `diffTreeSegments` 构造等价** |
| 与 R1/R2/R2.4 同文件双改 | D5 明确复用符号清单；按域锁批次；先合已落地 R2 主干再 P4 |
| trim 长成第四条旁路 | D3 收敛 + `TrimConsumerCountGuardTest` |
| TextureCache 跨纪元悬垂 | `clearEpoch` 进 `destroySurfaceGeneration`；纪元单测 |
| trim 过度重传卡顿 | pinned 主图集；SOFT/CRITICAL 分级；upload 计数断言 |
| 与 w5/bottom-mesh、渲染批次冲突 | 按文件锁批次；先合渲染主干再 D1 |
| GLES 中端回归 | D7 能力探测双路径；既有 VulkanPolicy 账本 |
| 测试拖垮 CI | 守卫单次迭代；墙钟表上限 |
| 性能非回归被内存收益掩盖 | bench 对照门槛（全局约束 15） |

**回滚**：开关关断；债表规定双轨删除时机（稳定 2 版）。

---

## 第七部分：未来场景推演（≥6 个月）

| 维度 | 6 个月 | 1 年+ |
|------|--------|-------|
| 规模 | 弟子增长：D4 仍 O(n) 加载；D5 完成前禁止再引入全量 DOM | 数据导向存储债表触发 |
| 生命周期 | 纪元+trim 循环靠 cache key 释放不累积 | 纹理流送债表触发条件 |
| 平台 | iOS：D1–D3 接口在 C++/Kotlin 桥 | Metal 实现 G 系列 |
| 运营 | 预算键 RemoteConfig 化 | 遥测自动降级 |
| 回退 | 开关关断=旧行为 | 删双轨：2 版稳定+无线上归因 |

---

## 第八部分：技术债与偿还计划

| 债项 | 为何现在不全做 | 偿还触发 |
|------|----------------|----------|
| 旧裸分配双轨 | 回滚需要 | 新路径 2 个版本稳定后删除 |
| DiscipleStore 列布局紧凑化 | 动确定性 ABI | WS-1 数据导向存储立项 |
| 全量纹理/mip 流送 | 单图集收益有限 | 第二张 4096 图集或预算频繁击穿 |
| per-scene GPU 卸载 | 当前无 per-scene GPU 资源 | 出现场景级 GPU 资源时 |
| Room Paging | 非本根因 | >1k 行列表实测卡顿 |
| largeHeap 移除 | 需 A/B | D2 后 22MB 堆对象消失且无 OOM 回升 |
| 行业 URL 20 条配额 | 外链不稳 | 实施前补链（compose §2.1） |
| Metal GpuAllocator | iOS 未立项 | iOS 立项 |
| `memorySubsystem` 热关断（RemoteConfig 绑定前无法线上关断） | RemoteConfig 未绑定 | RemoteConfig 激活或首次线上内存事故 |
| M-P2-1/2/5/6（Kotlin 每帧短命对象、JobSystem std::function、CacheLayer removeEldestEntry、Room 无 LIMIT） | 非 P0/P1 结构根因；P2-7/8 已进 P1.6 | Phase 根治合入后首个稳定版本窗统一清偿，或实测掉帧/GC 抖动触发 |
| 账本 cap 后 `shrink_to_fit` 全路径 | 热路径禁止 shrink | 压力路径已接 trim 后评估补全 |

**无其他隐性债。**

---

## 第九部分：与既有方案交叉（禁止双改）

| 方案 | 关系 |
|------|------|
| **native-engine-refactor R1/R2/R2.4**（`docs/native-engine-refactor-plan-2026-09-17.md`、`docs/cpp-engine.md`） | **强制前置对齐**：R1.4 列级写屏障、R2.2 protobuf 镜像、R2.3 `GameDataFieldPatch`+`GameViewStore`、R2.4 列级导出接生产已落地；D5/P4 只收口 rest 域全量基线残余，**复用** `diffTreeSegments`/`exportDirtyTree`/`NativeEngineFlag`，禁止第二套信封/第四种开关族 |
| longrun D1 initSurface 先析构 | **不重复**；本方案分配层增强可释放性，实施前 diff 确认 P0-1 状态 |
| performance P3-5 destroyTexture 入队 | **已完成**；本方案只加调用方与 cache |
| fps P3.3 | **已升级含义**：复用 `DynamicMemoryManager`，不新建第二套 |
| reverse-channel-elimination | D5 同向；**禁止**复活反向增量 |
| cpp-engine-migration | 分配/基线/位图落 C++ |
| character-gacha-implementation（角色卡池） | **逻辑状态串行**：`column_dirty` 删列与 D4、D5 基线与卡池新字段对拍按对方文档窗口串行；Trim 白名单见全局约束 5（`TrimPreserveFieldsGuardTest`）。**引用路径**：优先 `docs/design/character-gacha-implementation.md`；文件未入仓时以本册约束 5 清单为权威并登记债项，禁止空链依赖 |

---

## 第十部分：两选项（实施视角）

| 选项 | 实施范围 | 何时用 |
|------|----------|--------|
| **(1) 止血（唯一口径）** | **仅 Phase 0–1 全量**（P0.* + P1.*，必含 P1.3） | 紧急稳定窗口；**不**宣称根治完成；与 compose §14 一致 |
| **(2) 根治（本方案默认）** | **Phase 0–4 全部** | 正式立项；合入门槛 = P4.5/P4.6 |

**推荐**：立项按 (2)；若发布车紧张可先合 (1)【= Phase 0–1 全量】但债表登记「根治未完成」并在下个版本窗执行 Phase 2–4。

---

## 附录 A — 真机验收清单（命令级）

```
# 进入宗门前/后、CRITICAL trim 后各采一次
adb shell dumpsys meminfo <pkg> | sed -n '1,40p'
# 关注：Native Heap / Graphics / TOTAL PSS；前后台与 trim 后 Graphics 应可回落

# Native 堆泄漏取证（dumpsys 不够时）
adb shell dumpsys meminfo <pkg>   # 详表 Native Heap Alloc/Freed
# heapprofd（需 userdebug/eng 或 profileable）：
#   python -m perfetto.heapprofd --serial <device> -n com.xianxia.sect -d 10000
# 对照 P0.2 基线：切宗门×50 前后 Native Heap 差值应接近 0

# 游戏内 Debug 页（P4.4）：GpuStats.used / budget；TextureCache 条目与 pinned 数

# 场景：切宗门 ×50 → sectMapCache 条目 ≤ 上限
# 存档：5000 弟子 load → 无 ANR；ColumnResize 断言已在单测
# 重试：强制 ASTC 失败回退 → GPU 纹理计数不双倍
# 纪元：反复前后台/surface 重建 ×20 → TextureCache 条目不跨纪元累积
```

## 附录 B — 关键常量（实施时落入代码）

| 常量 | 建议值 | 说明 |
|------|--------|------|
| `kBitmapGrowthFactor` | 2 | 位图几何增长 |
| `kBitmapMinRows` | 1024 | 最小行容量 |
| `kSectMapCacheMaxEntries` | 8 | 与机型可再绑 budget |
| `NativeEngineFlag.memorySubsystem` | 预发 false → 验收 true | 总开关（并入既有旗标族） |
| `MAIL`/账本 cap | 与 `normalizeLedgers` 现源同值 | 禁止第二处字面量 |

---

## 附录 C — 自检（design-plan-review §八）

- [x] 未来场景推演 ≥6 个月  
- [x] 技术债三列表含触发条件  
- [x] 盲区自查见下  
- [x] 新抽象有生产消费者（allocator/cache/trim/bridge 均挂既有调用点）  
- [x] 测试墙钟已核算  
- [x] rules/ 交叉：cpp-priority / design-plan-review / static-resources（轨 F 若动）/ database-migration（不适用）/ renderer-feature-checklist  
- [x] 决策分级 = 架构级  
- [x] 影响范围含经济（不适用）/ iOS 标签  

### 盲区自查与完善建议

| 维度 | 盲点 | 影响 | 建议 |
|------|------|------|------|
| 需求 | 或许只要 Phase 1 | 范围误解 | §两选项写清；根治=全 Phase |
| 边界 | VMA 在个别 Mali 问题未真机穷尽 | 低端回归 | 开关回退 + 设备矩阵 |
| 耦合 | 与 bottom-mesh 并行改 VulkanBackend | 冲突 | 批次锁文件 |
| 耦合 | **与 R1/R2 同文件双改**（column_dirty/dirty_tracker/export） | 合并冲突/双协议 | D5 复用符号清单；实施前 diff R2.4 |
| 假设 | B-1/B-4 量级未实采 | 优先级 | Phase 0 基线采集回写 |
| 兼容 | 基线半迁移 | 同步脏 | 对拍+开关（已回写 §三） |
| 非功能 | trim 重传功耗 | 后台电 | pinned+限速（已回写 §六） |
| 非功能 | 内存改动拖 tick/帧 | 性能回退 | 全局约束 15 + P4.5 bench |
| 生命周期 | RemoteConfig 未绑定 | 预算不可远调；**开关无法热关断** | 本地默认（已回写全局约束 8）；热关断进债表 |
| 流程 | 真机命令未脚本化 | 验收争议 | 附录 A；可选后续脚本债表 |
| 流程 | 本 docs 分支未含代码 | 执行分叉 | 实施另开代码分支，本文件为唯一任务源 |
| 流程 | character-gacha 引用文件可能未入仓 | 白名单漂移 | 约束 5 以清单为权威 + 守卫测试 |
| 架构 | 既有 trim 多路径若未收敛 | 第四条旁路 | D3 收敛 + TrimConsumerCountGuard |

实质性项已回写正文对应章节。
