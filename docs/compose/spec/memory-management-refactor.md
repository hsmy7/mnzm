---
feature: memory-management-refactor
status: delivered
updated: 2026-09-23
branch: docs/memory-refactor-plan
commits: 2fd4fe6a6..c7643ae00
---

# 内存管理差距分析与重构级方案

## Report

**What was built** — 面向决策的内存管理差距分析与架构级重构方案（纯文档）：六维行业对照、P0/P1/P2 缺陷分层、GPU/CPU/状态/资产/平台五轨根治设计、完整 CLAUDE 方案章节与两选项决策。**尚未实施任何产品代码。**

**Verification** — 结构自检 + design-plan-review §八；独立子代理审查无 critical；major 三项已回写修复。

**Journey log** — ① 行业外链不稳，对标配额改债表触发（§2.1/§11）；② destroyTexture 契约已补但仍零调用，方案补调用方+cache；③ 升级 fps P3.3 预算管理器决策。

## [S1] Problem

### 业务语言问题陈述

玩家侧可能感知为：长时间挂机/反复切后台后卡顿或闪退、低端机更易被系统杀进程、进入宗门地图或读大存档时瞬时内存尖峰。技术侧根因不是"某处漏了 free"，而是：

**项目没有任何自研内存管理系统**（审计 A 级结论，`docs/memory-audit-2026-09-22.md` §0/§9）：

1. CPU 侧 100% 依赖 STL + NDK 默认分配器（无 Arena/Pool/PMR/`operator new` 重载/`mallopt`）；
2. GPU 侧 100% 依赖裸 `vkAllocateMemory`（无 VMA、无子分配、无缓存、无引用计数、无预算）；
3. 资产/状态/场景缺少统一的生命周期与回收契约（纹理运行期零调用方释放、常驻 JSON 第二份全量状态、场景切换几乎不还内存）；
4. 平台层压力响应近乎空转（`GameActivity.onLowMemory` 为空、多数 `onTrimMemory` 档只打日志）。

同模式问题 ≥3 处、触及未来 6 个月 C++ 主轴与 iOS 预留、跨 Vulkan/GLES/软渲三路径 → 按 `rules/design-plan-review.md` §六 **决策分级 = 架构级重构**。

### 需求要点

1. 指出与主流游戏厂商/主流引擎在内存管理上的**系统性差距**（不是逐条 bug 列表）；
2. 归纳当前**不足与缺陷**（以审计 A 级证据为准，B/C 级单独标注）；
3. 给出**重构级建议**（可照单实施的完整技术方案，符合 CLAUDE.md 设计方案规范）。

### 成功标准

| # | 标准 | 可验证方式 |
|---|------|-----------|
| 1 | 差距结论逐条有"行业做法 vs 本项目现状"对照，且现状侧引用审计证据 | 对照表可回指 `memory-audit` 章节/file:line |
| 2 | 缺陷按危害分层（P0/P1/P2），与既有审计台账不矛盾 | 与 longrun/performance/memory 三份审计交叉核对 |
| 3 | 重构方案覆盖 GPU/CPU/资产/状态/平台五层，含影响范围、兼容、测试、风险、未来推演、技术债、盲区自查 | CLAUDE.md 方案 9 章齐全 + design-plan-review §八勾选 |
| 4 | 明确"只修眼前 vs 彻底重构"两选项及选择条件 | 文末决策节 |
| 5 | 方案独立可读，不依赖口头补充 | 冷读者可执行 |

### 决策分级

**架构级重构**（完整方案 + 对抗性审查；行业对标为建议项，本方案已尽力对标）。

---

## [S2] Design

### 1. 背景与目标

#### 1.1 背景

| 输入 | 说明 |
|------|------|
| `docs/memory-audit-2026-09-22.md` | 主证据（A/B/C/D 分级取证，基线 `main@a7c459a90`） |
| `docs/longrun-stability-audit-report.md` + remediation | 生命周期/P0 GPU 泄漏通道（与本方案交叉） |
| `docs/performance-audit-2026-09-09.md` + remediation | `destroyTexture` 契约已补实现但**生产仍零调用**（P3-5） |
| `docs/fps-optimization-plan.md` P3.3 | **决策不修**：不**新建**独立预算管理器（触发=OOM 事故）；但 `DynamicMemoryManager` **已存在**（设备/Canvas 分档）——本方案复用并扩展 trim 分发，不造第二套 |
| `docs/platform-abilities.md` | `RenderBackend` 已抽象；Metal 未建 → GPU 内存层必须可移植 |

#### 1.2 目标（重构后应达到的性状）

1. **GPU**：单一分配入口 + 子分配 + 统计/预算；消灭 6 份手抄内存类型选择；纹理有键缓存与引用计数，运行期可释放。
2. **CPU**：按生命周期分层（帧/纪元/进程）；消灭 O(N²) 位图扩容；状态同步不再依赖常驻全量 JSON DOM。
3. **资产**：上传峰值可收缩；CPU 副本上传后可丢；磁盘重复编码治理入口。
4. **平台**：`onTrimMemory`/`onLowMemory` 形成统一压力协议，贯通 native 释放。
5. **可观测**：内存预算、水位、分类统计可导出（debug 开关），供真机验收。

#### 1.3 非目标（本方案文档交付边界）

- 本交付**只出方案不改代码**（实施另立项，见文末两选项）。
- 不重做渲染特性本身（网格/Mesh/着色器语义不变）。
- 不改变存档二进制格式的业务语义（序列化实现可换，对外兼容）。

---

### 2. 与主流的差距分析

#### 2.1 对标说明

- 行业对标属 CLAUDE.md 🟡 建议项（非强制前置）。本方案按用户要求做**机制对照**：回答"主流引擎有没有这层能力、我们缺什么"，**不宣称**已满足">=20 条带 URL+日期的 S/A 级来源"硬性配额——该配额的补齐触发条件登记在 §11 技术债表。
- **已在线核实**的来源带 URL；未能核实发布日期的不计入配额，单列"机制常识/引擎文档路径"。
- 本项目体量是 2D 放置宗门 + 有限 GPU 资产，**不能也不需要**照搬开放世界流送全案；对标的是**机制是否存在**，不是功能堆料。

#### 2.2 六维差距总表

| 维度 | 主流做法（行业） | 本项目现状（审计） | 差距性质 |
|------|------------------|-------------------|----------|
| **GPU 分配** | 子分配 + 块管理 + 内存类型优选 + 统计/预算（VMA 为 Vulkan 事实标准；Unity/Unreal 自研同类；Filament/Godot/Khronos Samples 等采用 VMA） | 14 次裸 `vkAllocateMemory`，一对象一 `VkDeviceMemory`；6 处内存类型手抄且行为不一致；无 `VK_EXT_memory_budget` | **机制缺失** |
| **纹理生命周期** | 引用计数 + 键控缓存 + 逐资源释放 + 流送/mip 预算 | `destroyTexture` 生产零调用；无 key/无 refCount；仅 surface 纪元死亡批量释放；staging 棘轮只增不减 | **机制有壳无魂** |
| **CPU 分配策略** | 分层 allocator（frame/linear/pool/slab）、按域预算、泄漏/水位跟踪（Unreal LLM、Unity Memory Profiler、移动端 budget） | 无自定义分配器；112 列独立 vector + 精确 16B 位图扩容；0×`shrink_to_fit`/`mallopt` | **机制缺失** |
| **状态基线/同步** | 二进制快照、列/字段级脏、避免双份全量 DOM | `baselineJson_` 常驻全量 nlohmann DOM；diff 期双 DOM + dump 字符串；每旬非弟子段仍全量序列化 | **架构选型过时** |
| **帧/旬临时分配** | Frame arena / ring / 持久映射三缓冲；帧路径零 heap | Vulkan 稳态帧近零分配（优）；GLES 每帧 `glBufferData`；Kotlin 每帧短命对象流；每旬 `std::function`+JSON | **GLES/状态层拖后腿** |
| **压力响应与预算** | 分级 trim、按 tier 预算、后台降载、可观测仪表 | `GameActivity.onLowMemory` 空；多数 trim 档仅 Log；CacheLayer 自驱逐关掉；P3.3 预算管理器**主动不建** | **策略未立** |

#### 2.3 差距根因（因果链）

```
无内存子系统（无接口、无预算、无分层）
    ├─→ GPU：每资源独立 VkDeviceMemory + 手抄 memory type
    │      └─→ 碎片/类型选错/无法统计回收
    ├─→ 纹理：上传有路径、销毁无调用方（契约空转）
    │      └─→ 同纪元重复上传累积；staging 高水位钉死
    ├─→ 状态：用文本 DOM 当同步基线
    │      └─→ 常驻第二份状态 + diff 峰值翻倍
    ├─→ 容器：无分层、无上限、无归还
    │      └─→ O(N²) 位图、112 列碎片、cache 只进不出
    └─→ 平台：trim 回调未接到 native 释放面
           └─→ 系统压力时 App 无可降载动作
```

**结论**：差距不是"少调了几个 API"，而是**缺少一层横跨 RHI/游戏核/资产/Kotlin 的内存子系统契约**——与 C++ 引擎迁移主轴（`docs/adr/cpp-engine-migration.md`）同属架构级。

#### 2.4 行业参考（已核实 + 机制对照）

| # | 来源 | 日期 | 与本方案关系 | 级 |
|---|------|------|--------------|----|
| 1 | [Vulkan Memory Allocator · GitHub README](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator) | 持续维护（页面含 2025 发行说明） | 子分配/内存类型/统计/线性池/预算扩展；用户含 BG3、Cyberpunk、Godot、Filament | S（官方库） |
| 2 | [AMD GPUOpen · Vulkan Memory Allocator](https://gpuopen.com/vulkan-memory-allocator/) | 页面载明 v3.3.0（May 2025）等版本史 | "industry-leading"；Ubisoft/RenderDoc 作者引用；线性分配器与 JSON 统计 | S（厂商官方） |
| 3 | VMA README · Software using this library | 同上 | Blender、Qt、Godot、Filament、LunarG Vulkan Samples 等 | S |
| 4 | 本项目 `docs/memory-audit-2026-09-22.md` | 2026-09-22 | 现状 A 级证据 | A（项目） |
| 5 | 本项目 `docs/longrun-stability-audit-report.md` | 2026-09-09 | P0 GPU 泄漏通道与 destroyTexture 历史 | A |
| 6 | 本项目 `docs/performance-remediation-plan-2026-09-09.md` P3-5 | 2026-09-09 | destroyTexture 延迟释放已实现仍无调用方 | A |
| 7 | 本项目 `docs/fps-optimization-plan.md` P3.3 | 既有 | 预算管理器"决策不修"的触发条件 | A |
| 8 | 本项目 `docs/platform-abilities.md` | 2026-08 | RenderBackend 抽象 / Metal 未建 | A |

**未能在本环境稳定打开、故不计入配额的常见路径**（机制仍按引擎公开文档理解，实施前建议补链）：Unity Manual（Memory / Texture Streaming）、Unreal（LLM）、Android Developers（Memory overview / onTrimMemory）、ARM Vulkan Best Practices（GPUOpen README 交叉引用）。

**差距判断（相对主流）**：GPU 分配与纹理生命周期为**硬缺口**；CPU 分层与状态基线为**软但贵**的架构债；压力响应为**策略空洞**；帧路径 Vulkan 侧已达主流下限（应保持）。

---

### 3. 当前不足与缺陷（分层清单）

> 证据等级沿用审计：A=代码直证，B=强推断，C=未闭合。完整风险编号见审计 §8（R1–R45）。

#### 3.1 P0 — 结构性 / 高危增长

| ID | 缺陷 | 证据 | 影响 |
|----|------|------|------|
| M-P0-1 | **纹理 GPU append-only（运行期）** | 现行：memory-audit A-2/R1（零调用方）。longrun P1-2 为历史通道（空实现已由性能 Task 3.4 修复） | 同纪元重复上传累积；图集级 22MB×N |
| M-P0-2 | **staging 高水位棘轮 22.4MB 永不收缩** | `ensureStagingBuffer` 只增不减（A-3/R2） | HOST_VISIBLE 钉死 |
| M-P0-3 | **无 GPU 子分配/预算/统一 memory type** | 6 站点手抄且回退不查 flag（A-1） | 碎片、选错类型、无可观测 |
| M-P0-4 | **常驻全量 JSON 基线 + diff 双 DOM** | `baselineJson_`/`restBaseline_`（A-4/R5） | 疑似数十 MB 常驻 + 导出峰值翻倍（量级 B-4） |
| M-P0-5 | **位图 O(N²) 精确扩容** | `ensureRowCapacity` 16B/步，N=5000≈200MB memcpy（A-4/R24） | 大存档加载卡顿+堆 churn |

#### 3.2 P1 — 明确增长 / 双份 / 生命周期洞

| ID | 缺陷 | 证据 |
|----|------|------|
| M-P1-1 | `sectMapCache` 只增不减 + `pushedTerrain` 钉住 | A-5/R4 |
| M-P1-2 | 场景无 unload；切换不还 CPU/GPU | A-6/R32 |
| M-P1-3 | `onLowMemory` 空、多数 trim 档无动作 | A-5 事实 #57（**非** R57；风险编号仅 R1–R45） |
| M-P1-4 | 账本 cap 仅 import 生效（B-6） | 审计 B-6/R6 |
| M-P1-5 | 112 列独立 vector + 0 shrink + 生命周期混堆 | A-4/R25/R27 |
| M-P1-6 | `importStateInternal` 新旧状态 2× 峰值 | A-4/R15 |
| M-P1-7 | ASTC 峰值≈4 份 ≈89.5MB（含 B-1 JNI 副本） | A-2/§4.2 |
| M-P1-8 | 磁盘 437MB webp + 双编码 edge + 323 重名 | A-5/R12 |
| M-P1-9 | GLES 每帧 `glBufferData` + 无 clamp 的顶点 vector | A-3/R16 |
| M-P1-10 | `VkInstance` 失败路径泄漏（initDevice 早退） | A-3/R3 |

#### 3.3 P2 — GC 抖动 / 次要泄漏 / 卫生

| ID | 缺陷 | 证据 |
|----|------|------|
| M-P2-1 | 每帧 Kotlin 短命对象流（RenderFrame 等） | R18 |
| M-P2-2 | 每旬 JobSystem `std::function` + 非弟子全量序列化 | R20/R21 |
| M-P2-3 | `m_pendingDraws` 无 reserve | R28 |
| M-P2-4 | 每 draw 锁 + `m_textures` O(n) 扫描 | R17 |
| M-P2-5 | `CacheLayer.removeEldestEntry=false` | A-5 |
| M-P2-6 | Room ≥10 无 LIMIT 查询、无 Paging | A-5/R44 |
| M-P2-7 | `jbytesToString` 空指针未检 | R42 |
| M-P2-8 | 大 JNI release 无 RAII | R43 |

#### 3.4 既有"不修"决策与本方案关系

| 既有决策 | 出处 | 本方案态度 |
|----------|------|-----------|
| 不**新建**独立预算管理器（等 OOM 事故） | fps-plan P3.3 | **升级**：复用已存在 `DynamicMemoryManager` + 扩展 Trim/GPU stats 只读视图；仍不引入第二套分级类；开关默认观测-only |
| `destroyTexture` 契约补齐即可 | performance remediation P3-5 | **仍不够**：缺的是调用方与纹理键缓存/refCount，不是入队实现 |

---

### 4. 技术方案（重构级根治）

### 4.0 架构基线对齐（architecture.md + CODE_WIKI.md）

> 本节在方案审查后补写：明确本方案**读过并遵守**的项目架构契约，以及此前遗漏的扩展性/可维护性约束。

#### 4.0.1 已读架构文档与采用的契约

| 架构文档 | 与内存方案的契约 |
|----------|------------------|
| docs/architecture.md 双层状态 + Frame-Driven | 内存操作**不得**在 UI 层直写 GameStateStore；trim/统计经 GameEngine/平台桥，不绕开 update/updateMirror |
| 惰性结算四层 + Checkpoint | **trim 禁止**清除 cultivationCheckpoints / lastSettled* / 生产 completionMonth；资源驱逐≠状态驱逐 |
| 双线程 + stateStore.update ReentrantLock | C++ 分配器/纹理 cache 的**渲染线程 vs 引擎线程**分界：GPU 操作仅渲染线程；引擎 tick 只投递 trim 事件 |
| 列级 COW（DiscipleTables.deepCopy） | Kotlin 侧快照隔离与 native 列存并行存在；位图/列 
eserve 只动 **gamecore DiscipleStore**，不动 Kotlin ComponentTable COW 语义 |
| docs/architecture.md 扩展性预留 | 预算配置走 **RemoteConfig 未绑定模式**（本地默认 + 键 memory.配置名）；离线收益/商业化**不**扩内存 API；iOS 见 §7 |
| CODE_WIKI.md AUTHORITATIVE 镜像只读 | 反向通道已删：Kotlin→C++ **仅** importToNative 全量；C++→Kotlin 仅 updateMirror。轨 D 改基线协议时**禁止**复活增量反向通道；门禁 MirrorReadOnlyGuardTest + DiffAuthoritativeTickTest 必须保持绿 |
| CODE_WIKI.md ActionId 协议 | 新增内存类 JNI 若必须走 ActionId，只加 gen-action-ids.mjs 条目 + dispatch case，**不新增散落 JNI 导出**（与现有 UI 事务同构）；优先**零新 ActionId**（trim 用已有桥/回调） |

#### 4.0.2 既有性能设施：扩展而非重造（可维护性）

项目**已有**内存相关设施，方案必须**接入**，禁止平行再造：

| 既有组件 | 位置（CODE_WIKI） | 方案动作 |
|----------|-------------------|----------|
| DynamicMemoryManager | core/data/memory/DynamicMemoryManager.kt | **扩展**为预算真相源之一（设备 tier / heap 分档）；轨 E「新建预算」改为「注册 MemoryBudget 源 + 分发 trim」，**不**新建第二套分级 |
| GCOptimizer | CODE_WIKI 性能基础设施 | SOFT75%/HARD85%/CRITICAL92% 与 TrimMemoryBridge **同一压力轴**对齐，避免两套阈值 |
| GpuTierDetector / DeviceCapabilityProfiler | GPU 分级 | 预算表按 tier 读取，禁止另写 RAM 判定 |
| CacheLayer / GameDataCacheMemoryPressure | 压力归一化 0~1 | sectMap/UI 位图驱逐挂到已有 pressure，不新开压力通道 |
| SoftwareCanvasBackend / Canvas 烘焙 RGB_565 | 软渲路径 | 软渲 Bitmap 预算沿用设备分档策略，补 onTrim 时 
ecycle 已有 DisposableEffect 模式 |

**fps-plan P3.3 的准确含义**（更正）：不是「项目从未有内存管理」，而是「**不新建**独立 DynamicMemoryManager 预算管理器（等 OOM 事故）」——但 DynamicMemoryManager **已存在**（Canvas/设备分级用途）。本方案升级为：**复用该类 + 扩展 trim 分发**，仍不引入第二套预算类；若需 GPU 预算，在 GpuAllocator.stats 上挂只读导出，Kotlin 侧只读。

#### 4.0.3 扩展性预留（6 个月+）

| 扩展方向 | 内存方案如何预留 | 明确不做 |
|----------|------------------|----------|
| RemoteConfig 激活 | memory.budget.* 键 + 本地默认（对齐 commercialization Key 规范）；未绑定时 BuildConfig | 不把预算写死进 C++ 常量导致改数发版 |
| 商业化/活动 | 广告 SDK 等三方 native 堆列入 MemoryStats 分类；不进游戏核 | 不为广告 SDK 写进 gamecore |
| 离线收益 | **零耦合**——收益仍挂 L0 时间推进；内存 trim 不碰结算时间戳 | 不把 trim 挂进结算钩子 |
| 社交/排行 | 无关 | — |
| 数据埋点 | MemoryStats 可选导出走独立 Analytics 接口预留（未实现则只 debug） | 不复用 GameEventBus 发内存事件 |
| iOS | GpuAllocator/TextureCache/TrimLevel 枚举纯 C++/Kotlin Multiplatform 友好；Metal 对等 §7 | 不在 core 调 Android API |

#### 4.0.4 可长期维护性（本方案自带）

1. **单一入口**：上传只经 TextureCache，分配只经 GpuAllocator，trim 只经 TrimMemoryBridge——守卫测试钉死旁路。  
2. **模块边界**：C++ 游戏核零 Android；Kotlin 只做桥与 UI；与 :core:domain/:core:engine 分层一致。  
3. **双路径同步**：Vulkan/GLES/软渲 checklist + SoftwareCanvasBackend 测试（CLAUDE 渲染铁律）。  
4. **文档义务**：实施合并时同步 CODE_WIKI.md（性能基础设施节）与 docs/architecture.md 扩展性预留中的内存子系统一行——**不可只改代码不改 Wiki**。  
5. **确定性**：RNG 分区/对拍门禁不因内存改动放松；基线协议变更必须过 Diff 对拍。  
6. **回滚开关**：memory_subsystem.enabled 保持可关，避免深度重构变成一次性赌注（design-plan-review §一 兼容回退）。

#### 4.0.5 与轨 D 的强制修订（AUTHORITATIVE）

原轨 D「每旬非弟子全量 JSON→增量」在镜像只读契约下的**合法形态**：

- **允许**：C++ 内部换掉 aselineJson_ 存储实现（列/字节块），**导出**仍经既有 export → updateMirror/快照路径；**导入**仍 importToNative 全量。  
- **禁止**：恢复 captureReverseDirty 式 Kotlin→C++ 增量回导；禁止绕过 MirrorReadOnlyGuardTest。  
- **对拍**：改基线后 DiffAuthoritativeTickTest + 存档往返必须绿。

#### 4.1 目标架构

```
                    +-------------------------------------+
                    |     MemoryBudget / MemoryStats      |  （观测+预算，可开关）
                    |  （分类：GPU/CPU-Java/CPU-Native/Asset）|
                    +------------------+------------------+
           +---------------------------+---------------------------+
           v                           v                           v
   +---------------+           +---------------+           +---------------+
   | GpuMemory     |           | CpuHeap域     |           | AssetLifecycle|
   | (VMA 或等价)  |           | frame/epoch/  |           | TextureCache  |
   | 子分配+统计    |           | permanent     |           | refCount+键   |
   +-------+-------+           +-------+-------+           +-------+-------+
           |                           |                           |
     VulkanBackend                gamecore 容器                Kotlin/Java
     GLES 对照路径                dirty/状态同步                onTrim 胶水
```

**分层原则（C++ 优先 + iOS 对等）**：

1. **RHI 层（C++）**：`GpuAllocator` 接口；Vulkan 实现优先 vendoring **VMA 单头文件**（MIT）；GLES 实现对接现有 `GLuint` 生命周期（无 VkDeviceMemory，但共用统计与 trim 入口）；软渲 Bitmap 路径只挂 Java/Native bitmap 预算。
2. **游戏核（C++ gamecore）**：生命周期域分配策略 + 状态基线去 DOM 化 + 位图几何扩容；**不**引入 Android API（`rules/cpp-priority.md`、platform-abilities）。
3. **平台胶水（Kotlin）**：`TrimMemoryBridge` 把 ComponentCallbacks2 映射到 Gpu/Cpu/Asset 释放优先级；不得在 core 埋 Android API。
4. **观测（C++ + 薄 JNI）**：`MemoryStats` 只读快照；Debug 菜单可读，Release 可编译剥离或采样。

#### 4.2 关键接口（示意契约，落地时以实现为准）

```cpp
// gpu_memory.h —— 仅 C++，零 Android 依赖
struct GpuAllocDesc { VkDeviceSize size; VkBufferUsageFlags usage; const char* tag; };
struct GpuAllocation { VkBuffer buffer; VkDeviceMemory memory; VkDeviceSize offset; /* or Vma */ };

class GpuAllocator {
public:
  virtual ~GpuAllocator() = default;
  virtual Result<GpuAllocation> allocateBuffer(const GpuAllocDesc&) = 0;
  virtual void free(GpuAllocation&) = 0;
  virtual GpuStats stats() const = 0;   // budget / used / blocks
};

// texture_cache.h
class TextureCache {
public:
  // 键 = 内容 hash 或资产 id；命中则 refCount++，不重复上传
  uint32_t acquire(const TextureKey&, const UploadSource&);
  void release(const TextureKey&);          // refCount==0 → 调 RHI destroyTexture
  void trim(TrimLevel);                     // 平台压力：丢非必须驻留
};
```

```kotlin
// TrimMemoryBridge.kt —— app/feature 层
// TRIM_MEMORY_UI_HIDDEN      → 丢 UI 位图缓存 / sectMapCache 非当前
// TRIM_MEMORY_RUNNING_LOW    → TextureCache.trim(SOFT) + staging 收缩
// TRIM_MEMORY_RUNNING_CRITICAL/COMPLETE → 强制 trim + 尝试 mallopt/purge 等价
```

**YAGNI 约束**：上列类型在实施任务中**必须**由既有调用点改造消费（VulkanBackend 6 站点、AtlasAsyncPipeline、SectMapController、GameActivity trim）——无消费者的抽象不得合并。

#### 4.3 分轨设计（按层）

##### 轨 A — GPU 内存（P0-3）

| 项 | 做法 |
|----|------|
| 引入 | Vendoring `vk_mem_alloc.h`（或等价自研块分配器，**不推荐自研全功能**） |
| 迁移 | 6 处 memory type 手抄 → VMA `VMA_MEMORY_USAGE_AUTO` + usage flags；删除回退不查 flag 的假回退 |
| 释放 | 所有 `vkAllocateMemory` 站点（`:131,921,1777,1905,2046,2396`）收口到 allocator |
| staging | 单独 VMA pool / 或可 shrink 的 host pool：trim 时 `vmaTrimPool` 等价 |
| 预算 | 启用 `VK_EXT_memory_budget`（有则用，无则堆大小估算）；超预算记账 |
| iOS | 接口层保留；Metal 对等为 `MTLHeap` + 池化 buffer（platform-abilities Metal 未建时只留接口） |

##### 轨 B — 纹理生命周期（P0-1/2，P1-7）

| 项 | 做法 |
|----|------|
| 键控缓存 | `TextureCache`：path/id → handle + refCount；重复 acquire 不上传 |
| 真实释放 | Kotlin/Java 所有 upload 路径改为 acquire/release；ASTC 失败重试**先 release 旧键** |
| 驻留策略 | 主图集、当前宗门 edge = pinned；预取非当前 = evictable；`onTrim` 先 evictable |
| 上传峰值 | 改为 direct ByteBuffer / `AllocateDirect` + 分块 staging；上传完成立刻断 Java ByteArray 引用（缓解 B-1/B-2）；目标：峰值从 4 份降到 ≤2 份稳态 + 1 份受控峰值 |
| GLES | `PendingUpload` 池化复用 vector；`glBufferData` → 预分配 + `glBufferSubData`/`glBufferStorage` 能力探测 |

##### 轨 C — CPU 分层与容器（P0-5，P1-5）

| 项 | 做法 |
|----|------|
| 域 | `FrameArena`（旬/帧临时，双向 ring）、`EpochArena`（renderer/scene 纪元）、`Permanent`（内容 DB，只读） |
| 位图 | `ensureRowCapacity` 改为**几何增长**（`max(need, cap*2, kMin)`），加载路径 `reserve(rows)` 一次到位 |
| DiscipleStore | 保持列式（确定性已依赖）；**补** `reserve`/策略性 `shrink_to_fit` 于存档切换；string 列评估 SSO 友好打包（不在本方案强改布局以免动确定性 ABI——见技术债） |
| mallopt | 压力路径 `M_PURGE`/`M_TRIM_THRESHOLD`（API 守卫）；禁止在帧路径调用 |
| 碎片 | 大块（batcher、VBO 等价物）从 frame 临时堆分离，保证可整块还 OS |

##### 轨 D — 状态同步去 DOM 化（P0-4，P1-6）

| 项 | 做法 |
|----|------|
| 根因 | nlohmann 全量 `GameState` 树当 diff 基线 = 第二份状态 |
| 方案 | 列级二进制/紧凑基线：非弟子段也走与列 dirty 对称的**字段/字节块基线**；或 C++ 侧结构 diff 替代 JSON 树 diff |
| dump | 导出路径流式写入（`std::string` 复用 buffer + `reserve`），避免 `j.dump()` 临时大字符串叠加 |
| import | 流式/分步导入：先构建新状态于独立缓冲，切换指针，**避免** parse 树 + 旧 `state_` + 新 `GameState` 三峰（P1-6） |
| 兼容 | 存档对外格式不变；仅运行时同步协议变——Kotlin 镜像消费点需一次性适配（影响范围清单） |
| JNI | 每旬非弟子全量 JSON → 改为列 dirty 增量或二进制块（与 reverse-channel 消除方向一致） |

##### 轨 E — 平台压力与预算（P1-3，P3.3 决策升级）

| 项 | 做法 |
|----|------|
| GameActivity | 实现 `onLowMemory` = COMPLETE 级强 trim；RUNNING_LOW/MODERATE 不再只 Log |
| Application | 对齐已有 `GameDataCacheMemoryPressure` 压力值，广播到 TextureCache/sectMapCache/Gpu trim |
| 预算 | **复用** `DynamicMemoryManager`/`GpuTierDetector` 分档 + `GCOptimizer` 阈值轴；新增只读 `MemoryBudget` 视图合并 `GpuAllocator.stats`；默认观测-only；RemoteConfig 键 `memory.budget.*` 预留 |
| largeHeap | 重构后复测：若 Java 大对象（22MB ByteArray）下降，评估移除 `largeHeap`（单独开关验证，不盲删） |

##### 轨 F — 资产磁盘治理（P1-8）— 可并行轨

| 项 | 做法 |
|----|------|
| 重复 | 323 重名 md5 相同项合并 source-mapping；edge ktx+webp 双编码定一源 |
| 大图 | 最大解码图 `bg_horizontal` 4096×2300（≈37.68MB RGBA8）；按钮背景 `ui_button` 3828×1384（≈21.19MB）——见审计 A-5 #60 |
| 策略 | 运行时**不**在本方案强上全量流送（2D 图集已有 ASTC）；磁盘/解码面用资源管线任务独立关闭 |

#### 4.4 数据流（纹理 acquire 示例）

```
AtlasAsyncPipeline.start
  → TextureCache.acquire(KEY_ATLAS, compressedSource)
      → miss: JNI upload → RHI uploadTexture → insert {handle,ref=1}
      → hit:  ref++  （禁止第二次 22MB 路径）
  → 失败回退 allowCompressed=false
      → release(KEY_ATLAS_ASTC) → ref=0 → RHI.destroyTexture → 帧边界 free
  → onTrim(SOFT)
      → 释放 evictable 键；vmaTrim(hostPool)
```

#### 4.5 错误行为

- 分配失败：返回 `Result`/`sealed` 风格错误，**禁止**静默 null 裸奔；上层已有 ASTC→RGBA 回退的保持。
- Trim 中分配：只读 stats 允许；禁止 trim 回调里做重上传。
- 纹理 release 竞态：沿用性能方案帧边界延迟释放（`MAX_FRAMES_IN_FLIGHT`），GLES 走渲染线程队列（已有方向）。

#### 4.6 测试边界

| 边界 | 策略 |
|------|------|
| GpuAllocator | 桌面/单元：mock 或 buffer-only 子集；统计自洽 |
| TextureCache | 单测 acquire/release/refCount/trim；守卫：upload 路径必须经 cache（Guard Test） |
| 位图扩容 | 单测几何增长次数上界（N 次 append ≤ O(log N) 次分配） |
| 状态基线 | 与现网 JSON diff 对拍（迁移期双跑）；RNG/确定性回归 |
| Trim | Robolectric 分发 ComponentCallbacks2 档位 → 断言 trim 计数 |
| 双路径 | Vulkan + GLES（+ 软渲 Bitmap 预算）同一 trim 协议 |

---

### 5. 影响范围清单

| 文件路径（代表） | 变更类型 | 变更说明 |
|------------------|----------|----------|
| `android/app/src/main/cpp/VulkanBackend.cpp/.h` | 修改 | 分配收口 GpuAllocator/VMA；staging pool；memory type 删除手抄 |
| `android/app/src/main/cpp/GlesBackend.cpp/.h` | 修改 | 顶点缓冲预分配；PendingUpload 池；trim 钩子 |
| `android/app/src/main/cpp/NativeBridge.cpp` | 修改 | destroyTexture/upload JNI 语义对齐 cache；trim 入口 |
| `android/app/src/main/cpp/gamecore/include/**/column_dirty.h` | 修改 | 几何扩容 + 加载 reserve |
| `android/app/src/main/cpp/gamecore/**/dirty_tracker.*` | 修改 | 基线去 DOM（或分阶段：先停用 rest 全量） |
| `android/app/src/main/cpp/gamecore/**/game_core.cpp` | 修改 | import 峰值；trim/预算查询导出 |
| `cpp/CMakeLists.txt` 或 third_party | 新增 | vendoring VMA 单头 |
| `.../AtlasAsyncPipeline.kt` | 修改 | acquire/release；失败路径 release |
| `.../SectMapController.kt` | 修改 | LRU/预算驱逐 sectMapCache |
| `.../NativeSurfaceView.kt` | 修改 | trim → backend |
| `.../GameActivity.kt` / `XianxiaApplication.kt` | 修改 | onLowMemory/onTrimMemory 实装 |
| `.../TrimMemoryBridge.kt` | **新增** | 统一压力协议 |
| `.../memory/GpuAllocator` debug 展示 | 新增 | 可选 stats 展示 |
| `rules/static-resources.md` 等 | 修改 | 若轨 F 改双模块资源放置 |
| `docs/renderer-feature-checklist.md` | 修改 | 双路径勾选新增内存项 |
| 守卫测试 | **新增** | `TextureUploadPathGuardTest`、`ColumnResizeGrowthTest`、`TrimDispatchTest` 等 |
| `android/app/src/main/assets/changelog_entries.json` + `CHANGELOG.md` | 修改 | 若合入玩家可感知稳定性改进 |
| `docs/platform-abilities.md` | 修改 | 登记 GpuAllocator/Metal 对等缺口 |
| `docs/architecture.md` | 修改 | 内存子系统小节 |

**触碰的 `rules/*.md` 交叉核对：**

| 规则文件 | 关系 | 冲突？ |
|----------|------|--------|
| `rules/cpp-priority.md` | 分配/基线/位图落 C++ | 无（同向） |
| `rules/design-plan-review.md` | 本方案按其 §八自检 | 无 |
| `rules/static-resources.md` | 轨 F 若改双模块资源放置需同步 | 待轨 F 启动时核对 |
| `rules/code-quality.md` 跨平台 | iOS 对等见 §7 | 无 |
| `rules/database-migration.md` | 无 Entity 变更 | 不适用 |
| `rules/economy-design.md` | 无货币 | 不适用 |
| `docs/renderer-feature-checklist.md` | 双路径内存项 | 实施时勾选 |

**经济影响**：无货币源汇变更 — **不适用**。  
**iOS 影响**：GpuAllocator/TextureCache/Trim 协议为跨平台面；Android 专用仅在 app 层实现 — 见 §7。

---

### 6. 兼容性分析

| 面 | 结论 |
|----|------|
| 存档格式 | **不变**（导出字节格式不变）。运行时基线协议变，需 Kotlin/C++ 镜像对拍测试 |
| Migration | 无 Room Entity 变更 → **无 DB migration** |
| 旧版本回读 | 同存档格式，兼容 |
| 向后兼容开关 | `memory_subsystem.enabled`（RemoteConfig 未绑定前用 BuildConfig/本地）：关=现状路径，开=新路径；纹理 cache 可独立开关 |
| 序列化 | 若基线改二进制，**仅同步通道**；落盘仍走现有 json/protobuf 出口直至另行立 ADR |
| 混布版本 | 无跨进程协议变更（单进程） |

---

### 7. 平台与 iOS / 双渲染路径

| 项 | Android | iOS 对等 |
|----|---------|----------|
| GPU | VMA + Vulkan | `MTLHeap` + 池；Metal 未建则接口空转登记 platform-abilities |
| GLES/软渲 | 共用 trim/预算/TextureCache 策略 | 无 GLES；软渲等价 CPU bitmap 预算 |
| Trim | ComponentCallbacks2 | `didReceiveMemoryWarning` → 同一 Trim 级别枚举 |
| 分配器 | Scudo + 可选 mallopt | 默认系统分配器 + 分层 arena 同 C++ |
| 观测 | dumpsys / 自研 stats | Instruments 对等（后期） |

**渲染双路径铁律**：凡 trim/上传/顶点缓冲变更，必须 Vulkan + Canvas(GLES/软渲) 双实现（`renderer-feature-checklist`），并补 `SoftwareCanvasBackend` 单测。

---

### 8. 测试方案

| 类型 | 内容 | 墙钟预算 |
|------|------|----------|
| 单元 | TextureCache refCount/trim；位图增长上界；GpuStats 自洽；Trim 档位映射 | 每测 <2s，合计 <30s |
| 守卫 | 禁止绕过 TextureCache 直接 upload；禁止新 `vkAllocateMemory` 散落 | <1s |
| 对拍 | 状态基线 JSON vs 新协议（固定种子用例 ×N） | <20s |
| 回归 | `:core:engine` 既有测试串行全量；渲染相关 Robolectric | 既有 CI 预算内 |
| 真机 | 低端 GLES + 中端 Vulkan：切宗门×50、读 5000 弟子档、前后台×50，看 `dumpsys meminfo` / GPU 计数 | 手动清单，不进 PR 墙钟 |
| 对抗性审查要点 | 是否仍有绕过路径；trim 是否在帧路径做重活；VMA 失败回退；双 DOM 是否仍可达 | 审查阶段 |

**确定性**：位图/缓存改动不得引入 `kotlin.random` / 非分区 RNG；状态对拍用固定夹具。

---

### 9. 风险评估与兜底

| 风险 | 可能性 | 影响 | 兜底 |
|------|--------|------|------|
| VMA 与现有提交/fence 时序不兼容 | 中 | 黑屏/校验错误 | 功能开关回退裸分配；validation layer CI 抽检 |
| trim 过度导致图集重传尖峰 | 中 | 卡顿 | pinned 主图集；SOFT/CLEAR 分级；重传限速 |
| 基线协议改错导致同步丢失 | 中 | UI 与真相源不一致 | 双跑对拍期；失败自动回 JSON 路径 |
| GLES 机型回归 | 中 | 回退机型闪退 | 双路径测试矩阵 + 既有 VulkanPolicy 账本 |
| 与进行中 w5/bottom-mesh、反向通道批次冲突 | 高 | 合并冲突 | 功能分支隔离；按域收口顺序合入 |
| 性能回退（cache 锁） | 低 | 掉帧 | 锁粒度：上传队列已有；acquire 热路径分读写 |

**回滚**：配置开关关断新路径；不删旧代码直至稳定 2 个版本（债表登记删除时机）。

---

### 10. 未来场景推演

| 维度 | 6 个月 | 1 年+ |
|------|--------|-------|
| 规模增长 | 弟子 5000→更多：位图 reserve + 几何增长仍 O(n) 加载；JSON DOM 若未轨 D 则仍爆 | 轨 D 完成前禁止再引入全量 DOM 基线 |
| 生命周期 | 反复 surface 纪元 + trim 循环不得累积（cache 键释放） | 纪元池化后可支持更长会话 |
| 平台扩张 | iOS：接口已在 C++；Metal 实现成为 G 系列缺口显式项 | platform-abilities 登记 |
| 运营演进 | 预算表可 RemoteConfig 化（绑定后）；无需为内存策略发版 | 遥测超限自动降级 |
| 兼容回退 | 开关关断 = 旧行为 | 删除旧路径触发条件：稳定 2 版 + 无线上归因 |

---

### 11. 技术债与偿还计划

| 债项 | 产生原因 | 偿还时机（触发条件） |
|------|----------|----------------------|
| 旧裸分配路径双轨暂存 | 回滚开关需要 | 新路径稳定 2 个版本号后删除 |
| DiscipleStore 列布局未做紧凑化 | 动 ABI/确定性风险高，先做 reserve/位图 | 轨 D 稳定后单独立项 `data-oriented` 存储（对齐 WS-1 阶段 3） |
| 全量纹理流送/mip 流送 | 2D 单图集场景收益有限 | 出现第二张 4096 图集或内存预算频繁击穿 |
| Room Paging | 非本方案根因 | 列表出现 >1k 行实测卡顿（触发即做） |
| 自研 allocator 替代 VMA | YAGNI | VMA 无法满足的定制池需求出现 |
| largeHeap 移除 | 需 A/B 实测 Java 堆 | 轨 B 后 22MB ByteArray 消失且低端机无 OOM 回升 |
| 补齐行业 URL 20 条配额（本方案仅机制对照，🟡） | 本环境外链不稳；对标为建议项 | **本方案提交用户前**尽量补链；仍未齐则维持 §2.1 定位并在 architecture D 系列登记，实施启动前闭合 |

**本方案无其他隐性债**；上表均含触发条件。

---

### 12. 实施任务（若选择"彻底重构"路径）

> 本文档交付时 **status=designed**，任务未勾选=未实施。每任务可独立验收。

- [ ] **T1**: 引入 `GpuAllocator`+VMA，收口 `VulkanBackend` 全部 `vkAllocateMemory` — acceptance: 全仓渲染 cpp 中裸 `vkAllocateMemory` 调用点=0（测试/注释除外），统计接口可返回 used/budget — covers: S2 §4.3 轨A
- [ ] **T2**: `TextureCache` 键控+refCount，改造 Atlas/崖壁/地面上传与失败重试 — acceptance: 同 key 重复 acquire 不产生第二次 upload；Guard Test 阻止旁路 — covers: 轨B; depends: T1
- [ ] **T3**: staging/host pool 可 trim；接 `TrimMemoryBridge` 到 Vulkan/GLES — acceptance: 后台 COMPLETE 后 staging 高水位下降或回落基线；单测档位映射 — covers: 轨A/E; depends: T1
- [ ] **T4**: `column_dirty` 几何扩容 + 存档加载 `reserve` — acceptance: `ColumnResizeGrowthTest` 分配次数 ≤ 2⌈log₂N⌉+O(1) — covers: 轨C
- [ ] **T5**: 状态基线去全量 DOM（列/字段基线）+ import 峰值治理 — acceptance: 对拍测试通过；diff 路径不再同时持有两棵 nlohmann 全量树 — covers: 轨D
- [ ] **T6**: `sectMapCache` LRU + GameActivity/Application onLowMemory 实装 — acceptance: trim 下缓存条目数下降；Robolectric TrimDispatchTest — covers: 轨E
- [ ] **T7**: GLES 顶点缓冲预分配 + PendingUpload 池化 — acceptance: 无每帧 `glBufferData` 全量重传（能力允许时）；双路径 checklist 更新 — covers: 轨B; depends: T2
- [ ] **T8**: MemoryStats Debug 页 + 真机验收清单 — acceptance: 桌面/模拟器可读分类 MB；真机清单文档化 — covers: S2 §1.2-5; depends: T1–T7
- [ ] **T9**: 文档/守卫/changelog/platform-abilities 同步 — acceptance: CLAUDE.md 12.4 双 changelog；platform-abilities 登记 GpuAllocator — covers: 影响范围; depends: T8

依赖：T1→{T2,T3}→T7；T4/T5/T6 可与 T2 并行（文件集不相交时）；T8 收口；T9 最终。**机读权威以文末 `## Tasks` 为准，本节为同构摘要。**

---

### 13. 与既有方案的交叉

| 方案 | 关系 |
|------|------|
| longrun remediation P0/P1 | **不重复修** initSurface 幂等等已列项；本方案在资源分配层给 P0 通道提供可释放性；实施时核对 P0-1 是否已修以免双改 |
| performance remediation P3-5 | 已实现入队；本方案补**调用方与 cache** |
| fps P3.3 | **升级**：不新建第二套预算类；复用 `DynamicMemoryManager` + 扩展 trim/GPU stats（见 §4.0.2） |
| reverse-channel-elimination | 轨 D 减少每旬全量 JSON 跨 JNI，同向 |
| cpp-engine-migration | 分配/基线/位图均落 C++，符合优先方向 |
| save-system-refactor | 存档字节格式不动；仅运行时同步 |

---

### 14. 两选项（CLAUDE.md 第 11 条 — 架构级声明）

**判定：架构级问题**（同模式 ≥3、6 个月内主轴、跨平台路径）。

| 选项 | 内容 | 适用 |
|------|------|------|
| **(1) 只修眼前（外科手术）** | 仅做：位图几何扩容+reserve（T4）、`sectMapCache` 驱逐（T6 缓存半边）、`onLowMemory` 实装、纹理失败路径 release、账本 tick 侧 cap | 只求先消 P0 卡顿与最明显增长，**不建**内存子系统；碎片/JSON DOM/无 VMA 仍在 |
| **(2) 彻底重构（推荐根治）** | 按 §4 全轨 A–E 实施（轨 F 资产磁盘可并行） | 认同"要一层内存子系统契约"，与 C++ 主轴一致；**本方案正文即选项 2 的完整设计** |

**推荐**：若资源只够做一件事，优先 **T4+T6+onLowMemory+纹理 release**（选项 1 的最小集）作为止血，但 **立项基线应按选项 2 全案**——否则 6 个月内 JSON DOM 与无 VMA 会在规模增长下再次击穿。

---

### 15. 盲区自查与完善建议

| 维度 | 盲点 | 潜在影响 | 完善建议 |
|------|------|----------|----------|
| 需求理解 | 用户要的"建议"可能只要对照表+清单，不要 9 章设计案 | 文档过长 | 已用业务摘要+S1 先给答案；实施前可只读 §14 |
| 边界与极端 | VMA 在个别 Mali 驱动的已知问题未在本环境实测 | 低端机回归 | T1 增加设备矩阵；失败开关回退 |
| 系统耦合 | 与 w5 bottom-mesh、SR 批次并行修改 VulkanBackend | 合并冲突 | 按文件锁批次；先合渲染主干再上 VMA |
| 假设有效性 | B-4 JSON 量级、B-1 JNI 副本未真机采样 | 优先级误判 | 实施前真机 `meminfo`/heapprofd 抽 1 档，回写量级 |
| 数据与兼容 | 基线协议变更若中断在半程 | 同步脏数据 | 双跑对拍+开关回退（已回写 §6） |
| 非功能 | 功耗（trim 导致重传）未建模 | 后台耗电 | pinned 集最小化+重传限速（已回写 §9） |
| 生命周期 | RemoteConfig 未绑定，预算配置入口未定 | 运营不可调 | 先 BuildConfig/本地，绑定后迁移（债表） |
| 流程 | 真机 GPU 计数采集无标准脚本 | 验收扯皮 | T8 出采集命令清单（dumpsys / kgsl 路径） |
| 对标配额 | 外链 20 条未齐 | 偏离 CLAUDE 建议级 | 债表已登记补链；核心 VMA 已核实 |
| 需求第二种解读 | 或许只想要 bug 修复列表 | 缺架构 | 已给两选项，选项 1 即止血清单 |

**实质性结论已回写**：双跑对拍→§6；重传限速→§9；补链→§11。

---

## 实施分册（选项 2）

具体可执行任务与全局决策 D1–D7 见：**[docs/memory-refactor-implementation-plan-2026-09-23.md](../../memory-refactor-implementation-plan-2026-09-23.md)**（根治实施方案，checkbox 按 Phase 执行）。

## [S3] Out of Scope

1. 本交付不修改任何产品代码（只产方案）。
2. 不实现完整纹理流送/mip 虚拟化。
3. 不改存档磁盘格式、不动 RNG 分区语义、不扩玩法数值。
4. 不在本方案内删除 `largeHeap`（仅预留复测触发）。
5. 不替代 longrun/performance 方案中已立项的独立缺陷修复。

## Tasks

- [ ] T1: 引入 GpuAllocator+VMA 收口 vkAllocateMemory — acceptance: 渲染 cpp 裸分配点归零 + stats 可读 (covers: S2 §4.3 轨A)
- [ ] T2: TextureCache 键控 refCount 改造上传/失败/trim — acceptance: 同 key 不双传；Guard Test 挡旁路 (covers: 轨B; depends: T1)
- [ ] T3: staging trim + TrimMemoryBridge 双路径 — acceptance: COMPLETE 后高水位回落；档位单测 (covers: 轨A/E; depends: T1)
- [ ] T4: column_dirty 几何扩容+加载 reserve — acceptance: GrowthTest 分配次数上界 (covers: 轨C)
- [ ] T5: 状态基线去双 DOM + import 峰值 — acceptance: 对拍过；无双全量树 (covers: 轨D)
- [ ] T6: sectMapCache LRU + onLowMemory 实装 — acceptance: trim 后条目下降；TrimDispatchTest (covers: 轨E)
- [ ] T7: GLES 顶点预分配+上传池化 — acceptance: 无每帧整批 glBufferData（能则）；checklist 更新 (covers: 轨B; depends: T2)
- [ ] T8: MemoryStats 验收页+真机清单 — acceptance: 分类 MB 可读；清单文档 (covers: S2 §1.2; depends: T1,T2,T3,T4,T5,T6,T7)
- [ ] T9: 双 changelog+platform-abilities+守卫文档 — acceptance: 12.4 双更新；能力表登记 (covers: 影响范围; depends: T8)
