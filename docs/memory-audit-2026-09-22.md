# XianxiaSectNative 内存分配与生命周期摸底审计

- 审计日期：2026-09-22
- 审计基线：`main` @ `a7c459a90`
- 审计方式：只读勘察（**本次未修改任何源码**）
- **行号时效告警**：审计期间检测到**另一路并发工作流**在改动邮件链（`GameViewModel.kt`、`MailDelegate.kt`、`MailDialog.kt` 于本次勘察过程中变为 modified，非本审计所为）。该改动使 `GameViewModel.kt` 行号整体漂移约 −6 行，本报告涉及该文件的引用已按当前工作树复核更正。**本报告引用的全部 C++ / native 文件与 `SectMapController.kt`、`NativeSurfaceView.kt` 等未被并发改动，行号仍成立。** 复核任何 Kotlin 引用前请先 `grep` 符号名而非直接信任行号。
- 证据纪律：注释 / README / 设计文档 / 类名 / 函数名 **一律不作事实依据**，只作线索；结论以代码实际行为为准，并逐条标注证据等级
  - **A** = 直接代码 `file:line` 可证
  - **B** = 调用链闭合或有强间接证据，但未实测/未 100% 闭合
  - **C** = 间接、生命周期未闭合，存疑
  - **D** = 仅推测（本报告 D 级一律不写成事实结论，改列为"未发现实际证据"）
- 复核声明：所有承重型论断（尤其否定式结论与具体行号）由主审亲自 `grep`/`Read` 复核；子代理报告的修正与否决见 [§10](#10-取证纪律自证我亲自复核了什么--子代理矛盾裁决)

---

## 目录

- [0. 一句话结论](#0-一句话结论先给答案)
- [1. 实际技术栈](#1-实际技术栈全部来自源码与构建脚本)
- [2. 真实内存架构图](#2-当前项目真实内存架构图)
- [3. 实际分配表](#3-实际分配表所有--已填)
- [4. 实际分配路径](#4-实际分配路径三条链闭合到最底层)
- [5. A. 已确认事实](#5-a-已确认事实源码直接可证-a-级)
- [6. B. 高概率推断](#6-b-高概率推断明确标注以下是推断不是已确认事实)
- [7. C. 无法确认的部分](#7-c-无法从当前实现确认明确留白未自行补全)
- [8. D. 风险点](#8-d-风险点只列已勘察到的不给方案)
- [9. 最终十问](#9-最终十问)
- [10. 取证纪律自证](#10-取证纪律自证我亲自复核了什么--子代理矛盾裁决)
- [附录 A. 复现命令](#附录-a-复现命令本次实际使用的取证命令)

---

## 0. 一句话结论（先给答案）

这个项目**没有任何自研内存管理系统**：CPU 侧 100% 依赖 STL + 平台 malloc，GPU 侧 100% 依赖裸 `vkAllocateMemory`（无 VMA、无子分配、无缓存、无引用计数）。

最主要的内存不是"对象"，而是**四类体积型资产**：

1. **96 MB 自管 `VkDeviceMemory`**（其中 1/4 是永不收缩的 staging 高水位）；
2. **`nlohmann::json` 全状态基线树**——明文常驻的第二份完整游戏状态；
3. **437 MB 磁盘 webp 的按需解码位图**（最大单张解码 37.68 MB）；
4. **5000 弟子 × 112 个独立 `std::vector` 列**的堆块。

最硬的缺陷是 **`destroyTexture` 在生产代码里零调用方**——纹理只能等整个 surface 纪元死亡时一次性释放。

---

## 1. 实际技术栈（全部来自源码与构建脚本）

| 项 | 实测结论 | 证据 | 等级 |
|---|---|---|---|
| 语言比例 | Kotlin 1913 文件为绝对主体；C++ 131 `.cpp` + 77 `.cc` + 175 `.h`（gamecore 本体约 68,129 行）；Java 仅 3 个；Python 59（构建脚本）；JS/MJS 26（图集生成） | `git ls-files \| sed 's/.*\.//' \| sort \| uniq -c` | A |
| 平台 | **仅 Android**，`abiFilters 'arm64-v8a'` 单 ABI。iOS/Metal **在源码中不存在**：`git ls-files` 匹配 `\.mm$\|\.m$\|\.metal$` → 0 命中，只有 `docs/adr/ios-migration-plan.md`；所有 `Metal` 字样都在注释里（`Rhi.h:15-25`） | 亲自执行 | A |
| 图形 API | **Vulkan（主）+ OpenGL ES 2.0（回退）双后端**，同一 `Renderer2D` 抽象（`Rhi.h:96`）。CMake 硬依赖 `find_package(Vulkan REQUIRED)`，同时链 `GLESv2 + EGL` | `cpp/CMakeLists.txt:8,48-55` | A |
| 后端选择 | **纯 Kotlin 策略决定，无 system property、无环境变量**。`VulkanPolicy.getRenderStrategy` → `useRenderMode` → `NativeBridge.setRenderBackend` → C++ `g_backendType != 0 ? new GlesBackend : new VulkanBackend`。判据含 SDK_INT<31、模拟器、云游戏、厂商白名单、失败账本 kill≥3/soft≥3 | `NativeBridge.cpp:436-486`、`VulkanPolicy.kt:510-629`、`NativeSurfaceView.kt:1007-1009` | A |
| 第三个"后端" | `SoftwareRenderBackend`（Canvas/Bitmap 软渲）**存在**，是 GPU 失败后的最终兜底；该路径**不加载 `libnative-renderer.so`** | `NativeSurfaceView.kt:998-1000,1200-1209` | A |
| 产物 | 2 个 `.so`：`libnative-renderer.so`（Vulkan+GLES+JNI 桥）、`libnative-game-core.so`（game-core 静态库 + `GameCoreBridge.cpp`）。两者由不同 Kotlin 入口**独立加载**，非"总是都加载" | `cpp/CMakeLists.txt:30-74`、`NativeBridge.kt:17-22`、`GameCoreBridge.kt:45-50` | A |
| 第三方（native） | `nlohmann/json`（header-only）、`googletest`（**test 专用，不编译进 `.so`**：`GAMECORE_BUILD_TESTS` 默认 OFF）、zlib(`z`)、`android`、`log`。SPIR-V 以 C 数组内嵌于 `.rodata`，共 8,572 B | `gamecore/CMakeLists.txt:26-45,75-87`、`shaders/shaders.h:14,109,163,260` | A |
| 第三方（Java） | Room 2.7.0（34 entity）、MMKV 2.4.1、Glide 4.9.0（**仅头像一处** `ModeSelectionScreen.kt:341`）、okhttp、kotlinx-serialization、protobuf-javalite、Bugly、Umeng、TapTap ×6、Dirichlet 聚合广告 + 穿山甲/优量汇/百度。**Firebase、AGC、Coil、Jetpack Paging、DataStore 均未引入**（依赖声明被注释） | `app/build.gradle`、`libs.versions.toml`、`XianxiaApplication.kt:105-147` | A |
| **测试代码是否污染生产** | **不会**。`game-core` 静态库源列表只有 12 个 `.cpp`；`gamecore/jni/GameCoreJni.cpp` 与 `test/` **均不在其中**——"某机制永不释放"的担忧已被否决，那是桌面对拍桥专用 TU | `gamecore/CMakeLists.txt:26-45` | A |

### 1.1 关键 C++ 文件体量

```
android/app/src/main/cpp/
  VulkanBackend.cpp     135,921 B   ← GPU 内存全部在此自管
  NativeBridge.cpp       75,807 B   ← 渲染侧 JNI + 场景装配 + static 全局
  GameCoreBridge.cpp     54,967 B   ← 模拟侧 JNI
  GlesBackend.cpp        24,794 B   ← GLES 回退
  VulkanBackend.h        20,312 B
  KtxLoader.cpp           5,678 B   ← 零拷贝 KTX1 解析
  SkyBackground.cpp       6,417 B
  SpriteBatcher.cpp       2,830 B
  TextureAtlas.cpp          836 B
  gamecore/include|src    ~68,129 行 ← 纯 C++ 引擎核心，零 Android 依赖
```

---

## 2. 当前项目真实内存架构图

```
                            Android 单进程（largeHeap=true，无 android:process 隔离）
                                                │
          ┌─────────────────────────────────────┼─────────────────────────────────────┐
          ↓                                     ↓                                     ↓
   ① Java/Kotlin 堆                      ② Native 堆（arm64, Scudo）             ③ GPU / 驱动侧
   (ART GC 管辖)                         (malloc 管辖，不受 GC 约束)             (vkAllocateMemory + 驱动私有)
          │                                     │                                     │
  ┌───────┼────────┬─────────┐         ┌───────┼────────┬──────────┐          ┌───────┼────────┬─────────┐
  ↓       ↓        ↓         ↓         ↓       ↓        ↓          ↓          ↓       ↓        ↓         ↓
Atlas   game-    Room     Compose   STL   固定容量   文件作用域  JSON      DEVICE_  HOST_    swapchain  HWUI
22.4MB  data     游标→    painter   容器  内联缓冲   static     字符串    LOCAL     VISIBLE  images     (skiagl)
Byte-   String   List     ~437MB    倍增  512vert    对象       拷贝      纹理      staging
array   2.26MB   (无分页)  webp     无    ×4 批处             (112列)   22.4MB   22.4MB   ≥3×视口  Bitmap
[1]     [2]      [3]      [4]             理器                        +39.5MB  (棘轮)    [8]      [9]
                                        │       │        │            [6]      [7]
  ↓ 一次性大对象                        ↓       ↓        ↓
AtomicReference 缓存 22.4MB        GameCore  Vulkan  GlesBackend
SectAtlasPrefetch:33               │       Backend    │
LinkedHashMap LRU                  │       │          │
  removeEldestEntry=false    112列DiscipleStore  3×VBO   m_vertexBuffer
  CacheLayer.kt:215          columnTracker_     11.25MB  (每帧整批
  → 预算 20/50/100MB          dirtyTracker_        3×3.93MB  glBufferData)
                              .baselineJson_       staging  m_pendingUploads
⑤ 线程栈：约 15 个 Java/Kotlin 线程 + JobSystem     【全量DOM】    (整份像素副本)
   hw_concurrency() 个 C++ worker。              (常驻第二份状态)
   stackSize 从未设置 → 全部 ART 默认（代码自己按 ~1MB 假设
   把内联缓冲压到 512 顶点：SpriteBatcher.h:19-20）
```

**继续向下的实际归属**：

- `libnative-renderer.so` 内部只有一个 `static Renderer2D* g_renderer`（`NativeBridge.cpp:44`）和 8 个文件作用域 static 对象：`g_atlas:75`、`g_sky:79`、`g_projMatrix:76`、`g_scene:201`、`g_cropSmooth:202`、`g_floatPool:223`、`g_mapBatcher:187`、`g_edgeBatcher:188`、`g_overlayBatcher:208`、`g_floatBatcher:225`、`g_cliffTexIds[16]:101`。
- `libnative-game-core.so` 内部只有一个 `static GameCore* g_gameCore`（`GameCoreBridge.cpp:80`）。
- **所有权模式 = 裸 `new`/`delete` + 文件级 static，零智能指针**：`shared_ptr`/`weak_ptr`/`enable_shared_from_this` 在生产 cpp 中仅 1 处命中，且是注释（`index_snapshot.h:40`）。

---

## 3. 实际分配表（所有 `?` 已填）

| 模块 | 数据 | 分配方式 | 分配位置 | 生命周期 | 谁持有 | 谁释放 | CPU/GPU | 是否缓存 |
|---|---|---|---|---|---|---|---|---|
| 渲染器 | `VulkanBackend` | `new` | `NativeBridge.cpp:333,474` | 一个 surface 纪元 | `static g_renderer:44` | `shutdownRenderer` `delete` `:519` | CPU+持 GPU 句柄 | 否（单例） |
| 渲染器 | `GlesBackend` | `new` | `NativeBridge.cpp:438` | 同上 | 同上 | 同上 | CPU+GL 对象 | 否 |
| 顶点 | 3×VBO `VkBuffer`+`VkDeviceMemory` | `vkAllocateMemory` ×3 | `VulkanBackend.cpp:1777`（循环 `:1744`） | 代表面纪元 | `m_vertexMemories[3]` `.h:215` | `destroySurfaceGeneration:388-389` | **GPU(HOST_VISIBLE 映射)** | 固定预分配 |
| 顶点上传 | `m_vertexMapped[i]` | `vkMapMemory` 一次 | `:1784` | 纪元内**常驻映射** | 同上 | `:385` | CPU 侧映射指针 | 是（免 per-frame map） |
| 上传中转 | staging `VkDeviceMemory` | `vkAllocateMemory` | `:1905` | **进程内棘轮，只增不减** | `m_stagingMemory:358` | 下次扩容 `:1860` 或 `shutdown:441` | GPU(HOST_VISIBLE) | 单一全局复用 |
| 图集纹理 | 4096² ASTC 11 mip | 独立 `vkAllocateMemory`/纹理 | `:2396` | **直到代表面析构** | `m_textures` vector | **生产无调用方**（见 A-8） | **GPU DEVICE_LOCAL 22,369,616 B** | **无缓存、无去重、无引用计数** |
| 崖壁纹理 | 7×ASTC | 同上 ×7 | `:2396` | 同上 | 同上 | 同上 | GPU 39,500,752 B | 无 |
| 地面纹理 | 64² RGBA REPEAT | `uploadTextureImpl:2046` | | 同上 | `g_groundTexId` | 同上 | GPU 16,384 B | 无 |
| 离屏 RT | 3×`VkDeviceMemory` | `:921` | **默认不存在**（`m_renderScale=1.0` 时 `:863` 直接 return） | 纪元 | `m_offscreenMemories[3]` | `:848-855` | GPU DEVICE_LOCAL | — |
| Uniform | 不存在 | push constant 64/128 B | `:1310-1313,1330-1333` | — | — | — | — | — |
| Index Buffer | **不存在** | 无 `vkCmdDrawIndexed` | — | — | — | — | — | — |
| Depth/MSAA | **不存在** | `depthTestEnable=VK_FALSE:1446` | — | — | — | — | — | — |
| GL 顶点 | 1×VBO | `glGenBuffers:283` + **每帧 `glBufferData:508`** | 整个顶点流重新上传 | shutdown `:344` | `m_vbo` | `destroyPipeline` | GPU（驱动每帧重分配） | 否 |
| GL 纹理 | `glTexImage2D:416` RGBA8 单级 | 每上传一次 | 纪元 | `m_textures` | `glDeleteTextures:400`（须先 `destroyTexture`，**同样无调用方**） | GPU ≈16.7 MB | 无 |
| 精灵批 | `SpriteBatcher` ×4 | 512 顶点内联 + 超限 `new SpriteVertex[]` 倍增封顶 `MAX_VERTICES` | `SpriteBatcher.cpp:75` | **跨帧保留，不 `delete[]`**（`:63-66`，仅析构释放） | 文件级 static ×4 | `~SpriteBatcher` | CPU，最坏 4×3,932,160 B ≈ **15.7 MB 常驻** | 是（容量复用） |
| 场景 | `scene::SceneStore g_scene` | **无分配**（进程加载即存在） | `NativeBridge.cpp:201` | **永不销毁** | 文件级 static | `reset()` 只 `clear()` → 容量保留（`scene_store.h:250-264`） | CPU ≈140 KB | 是 |
| 场景地形 | `terrain_`/`roads_` int32×16384 | `vector::assign` | `scene_store.h:93` | 纪元 | `g_scene` | `clear()`（不还容量） | CPU 65,536 B ×2 | 是 |
| 弟子（真相源） | `DiscipleStore` **112 个 `std::vector` 列** | 各自独立倍增 `push_back`，**全仓 0 处 `reserve`** | `disciple_store.h:161-295` | 游戏存档周期 | `GameState::disciples` | `clear()` 保留容量 | CPU | 否 |
| 弟子 id 索引 | `map<string,size_t>` + `map<int32,size_t>` ×2 | 红黑树逐节点 `new` | `disciple_store.h:301,306` | 同上 | 同上 | `clear()` | CPU | 否 |
| 脏位图 | `unique_ptr<atomic<u64>[]>` | **唯一裸 `new[]`** | `column_dirty.h:663` | 进程内**只增不减** | `columnTracker_` | 扩容时隐式 `delete[]` | CPU | 是 |
| 状态基线 | `nlohmann::json baselineJson_`（**含全部弟子**） | STL 深层树 | `dirty_tracker.h:92` ← `stateToJson(s)` `dirty_tracker.cpp:34` | **init/import 后常驻** | `GameCore::dirtyTracker_` | 下一次 `resetBaseline` 替换 | CPU | 是（diff 基线） |
| 非弟子基线 | `nlohmann::json restBaseline_` | 同上 | `column_dirty.h:700` | 常驻 | `columnTracker_` | 每次导出移动替换 | CPU | 是 |
| 内容 DB | ~20 个 Meyers `static std::vector`（manual/equipment/herb/recipe/trait/beast/name/level） | 首次触碰分配 | `manual_db.h:70` 等 | **进程终身，无清除点** | 函数局部 static | **永不** | CPU | 是（永久） |
| ECS | `ComponentStorage<T>` 3 个并行 vector | `emplace_back`，无 reserve | `storage.h:154-156` | 纪元 | `ComponentRegistry` `unordered_map<TypeId,unique_ptr<IStorage>>:82` | `clearAll()` 保留容量、**不销毁 storage** | CPU | — |
| 存档导出 | 全量 `std::string` | `j.dump()` | `json_codec.cpp:1505-1508` | 瞬时（返回即由上层持有） | JNI 调用方 | string 析构 | CPU | 否 |
| JNI 入 | `jbyteArray`→native 副本 | `GetByteArrayElements` | `NativeBridge.cpp:725` | 函数体内 | JNI | `ReleaseByteArrayElements(…, JNI_ABORT):743` | CPU 22.4 MB 瞬时 | 否 |
| JNI 出 | `NewByteArray`+`SetByteArrayRegion` | `stringToJbytes` | `GameCoreBridge.cpp:256-259` | 每次调用 | JVM local ref | JVM | CPU | 否 |
| 零拷贝通道 | direct `ByteBuffer` 地址 | `GetDirectBufferAddress` | `NativeBridge.cpp:616` | Kotlin 持有 | Kotlin | `allocateDirect` 的 Cleaner | 堆外 native | — |
| Bitmap 软渲 | `createBitmap(2048,2048,ARGB_8888)` | 平台 | `AtlasAsyncPipeline.kt:196` | **显式不 `recycle()`**（`:336`，理由：OEM `NativeAllocationRegistry` 双重释放） | `view.atlasBitmap` | GC finalizer | **API26+ 在 native 堆** | 是 |
| UI 图片 | webp → 位图 | AAPT/Compose `painterResource` | `core/ui/.../GameBackground.kt:18` 等 | Compose 生命周期 | 框架资源池（无 AppGlideModule 配置） | 框架 | 解码后 ARGB_8888 native 堆 | 框架默认 |
| 音频 | `SoundPool`(≤8) + `MediaPlayer` | 平台 | `AndroidAudioPlayer.kt:44-45` | 有 release | 平台 | `release()` | 平台 AudioFlinger | 平台 |
| 线程 | `JobSystem` worker ×hw_concurrency | `std::thread` | `job_system.h:32-39` ← **`game_core.cpp:201 make_unique<JobSystem>()` 已确认生产实例化** | GameCore 终身 | `GameCore::jobs_:286` | `~GameCore` | CPU 栈 | — |

---

## 4. 实际分配路径（三条链闭合到最底层）

### 4.1 普通对象（弟子列）——最终落到 C 运行时

```
业务写点（结算 / AI）
 ↓ gamecore/src/disciple_store.cpp:170-290  DiscipleStore::appendDisciple
 ↓   const std::size_t row = ids.size();                        // :160
 ↓   ids.push_back(...) … ×112 列                                // 每列一条独立堆链
 ↓ libc++ std::vector::push_back → 容量倍增分配
 ↓ operator new(std::size_t)                                     ← 全项目 0 处重载
 ↓ Scudo / jemalloc（NDK 默认分配器）                             ← 0 处 mallopt/arena 配置
 ↓ malloc → mmap/brk                                             ← C Runtime
```

**关键实证**：`gamecore/include/**` + `src/**` 全量 grep `new|malloc|calloc|realloc|operator new|posix_memalign|aligned_alloc` → **3 处命中，其中 2 处是假阳性**（`game_core.h:94` 的 `= delete`；`appointment_tx.h:1046` 的标识符 `newId`）。真实分配点**只有 1 个**：`column_dirty.h:663`。

→ **gamecore 的 CPU 内存 100% 由 STL 容器代持**，项目层不存在任何封装。

### 4.2 Texture（ASTC 主路径）——磁盘到 GPU 的完整 6 跳

```
① 磁盘  assets/atlas/atlas_astc.ktx                    22,369,724 B（实测 ls -la）
        KTX1 头实测：4096×4096、ASTC_4x4_LDR、11 mip（atlas-manifest.json 同值）
 ↓ ② 读入 Java 堆 ByteArray（整文件一次性 slurp）
        SectAtlasPrefetch.kt:49   assets.open(...).readBytes()  → 22,369,724 B
        :33  AtomicReference cached  ← 消费前一直挂在这里（不被 GC）
 ↓ ③ JNI 跨语言（此处极可能产生第二份 native 副本 — 见 B-1）
        NativeBridge.cpp:725  env->GetByteArrayElements(ktxData, nullptr)
        :726  if (!bytes) return 0;                                  ← null 检查在取得所有权之前，顺序正确
        :743  env->ReleaseByteArrayElements(ktxData, bytes, JNI_ABORT)  ← 不回写
 ↓ ④ 容器解析：零拷贝、零解码
        KtxLoader.cpp:121   info.data = fileData + dataRegionStart;  // 指回 ③ 的缓冲，不分配
        KtxLoader.cpp:41    非 GL_COMPRESSED_RGBA_ASTC_4x4_KHR 直接拒绝
        → 项目 native 侧【不存在任何图片解码器】：
          stbi_load / libjpeg / turbojpeg / libwebp / WebPDecode  全 0 命中
        → "解码 → RGBA8 → 格式转换" 三跳在 ASTC 路径上不存在
 ↓ ⑤ staging 中转（第三份副本，HOST_VISIBLE|HOST_COHERENT）
        VulkanBackend.cpp:2404  ensureStagingBuffer(dataSize)
        :1855  if (m_stagingBufferSize >= requiredSize) return true;   ← 只增不减
        :2407  vkMapMemory → :2427 逐级 memcpy → :2432 vkUnmapMemory
 ↓ ⑥ GPU DEVICE_LOCAL 纹理（第四份，独立 VkDeviceMemory）
        :2396  vkAllocateMemory(&tex.memory)                ← 一张纹理一次分配，无子分块
        :2399  vkBindImageMemory(..., 0)
        :2352  imgInfo.mipLevels = mipCount(11)             ← mipmap 真实存在，4/3 系数生效
        :2441ff vkCmdCopyBufferToImage + 布局转换 + 一次性 fence(创建/销毁) + 释放 cmd
 ↓ 持有
        :2542  tex.id = s_nextTextureId++     ← 单调、永不复用、文件级 static(:1852)
        :2544  m_textures.push_back(tex)
 ↓ 释放
        路径 A（期望）: destroyTexture → m_retiredTextures → submitFrame 排空
                        → freeTextureResources :2582 vkFreeMemory ✔ 代码存在且正确
        路径 B（实际）: 只有 shutdown()/destroySurfaceGeneration():372-378 批量释放
```

→ **一张图集峰值同时存在 4 份、合计 ≈ 89.5 MB**：

| 份 | 归属 | 字节 | 证据等级 |
|---|---|---|---|
| (a) | Java 堆 `byte[]` | 22,369,724 | A |
| (b) | JNI 侧 native 副本 | 22,369,724 | **B**（见 B-1） |
| (c) | Vulkan staging（HOST_VISIBLE） | 22,369,660 | A |
| (d) | DEVICE_LOCAL `VkImage` | 22,369,616 | A |
| | **峰值合计** | **≈ 89,475,724 B ≈ 89.5 MB** | |

### 4.3 场景（进入宗门地图）——没有"加载"，只有"数据替换"

```
点"进入宗门"
 ↓ WorldMapSectDetailDialog.kt:429  viewModel.enterSect(sectId)
 ↓ GameViewModel.kt:642-647          gameEngine.launchOnEngine { gameEngine.enterSect(sectId) }
 ↓ GameEngineLifecycleOps.kt:230-266 gameData.copy(activeSectId = purified)
        ★ 此处无任何 unload / evict 调用
 ↓ SectMapController.kt:38-44        StateFlow(Eagerly) 反应 activeSectId
 ↓ SectMapController.kt:53           sectMapCache.getOrPut(seed) { buildSectMap(...) }
        → MapPreloadData(IntArray(128*128)) = 65,536 B
          ※ 旧 seed 条目永不移除（全仓仅 2 处引用：声明 + getOrPut）
 ↓ SceneUpdateChannel.kt:105-118     frame.tileData !== pushedTerrain → 推
 ↓ NativeBridge.cpp:1014-1030        std::vector<int32_t> tmp(n) + GetIntArrayRegion
                                     （两份拷贝：临时 vector + JNI 数组）
 ↓ scene_store.h:93                  g_scene.terrain_.assign(...)   ← 复用容量，不还内存
 ↓ GPU：本次切换 0 次纹理上传
        （图集上传挂在 onRendererReady，属 surface 纪元而非场景）
```

---

## 5. A. 已确认事实（源码直接可证，A 级）

### A-1 谁负责分配

1. **不存在自定义 Allocator / MemoryManager / Arena / Pool / Slab / Frame / Ring / 内存池。** `Allocator|MemoryManager|Arena|Slab|ObjectPool|std::pmr|alignas|custom operator new|mallopt|M_TRIM` 在 `android/app/src/main/cpp`（排除 build/third_party）→ **0 实质命中**。命中的 `Pool`/`Chunk`/`Allocate`/`Ring`/`Buffer` 全是命名巧合：
   - `Pool` = RNG 候选局部 vector（`appointment_tx.h:315`、`merchant_settlement.h:117`、`mission_settlement.h:434`、`month_settlement.h:1572`、`disciple_factory.h:238`）
   - `Chunk` = 并行工作分区，不持字节（`ecs/job_system.h:69-93`）
   - `Allocate` = **id 计数器**推进，非内存（`inventory.h:37 reseedItemIdAllocatorsFromJson`）
   - `Ring` = 地图边界树环宽（`terrain.h:187`）；`Buffer` = 仅出现在注释（`diplomacy_tx.h:304`）
2. **不存在 Vulkan Memory Allocator。** `VmaAllocator|vmaCreate` → 0 命中；`vkAllocateMemory` 共 6 个真实调用点：`:131, 921, 1777, 1905, 2046, 2396`（`:922` 是 LOGE 字符串，非调用）。
3. GPU 内存**完全自管**：一个 Image/Buffer = 一次 `vkAllocateMemory`，绑定 offset 0。**无一分配放多对象**、无 `VkMemoryDedicatedAllocateInfo`、无 `bufferImageGranularity`/`minMemoryMapAlignment`/`nonCoherentAtomSize` 处理（三项均 0 命中）、无 `VK_EXT_memory_budget`、无 AHardwareBuffer 导入。全项目 `vkGetPhysicalDeviceMemoryProperties` 被重复查询 6 处（`:113, 874, 1741, 1879, 1993, 2343`）而非缓存。
4. 六段内存类型选择代码是**同一逻辑的六份手抄副本**（`:117-124`、`:902-914`、`:1755-1770`、`:1883-1898`、`:2023-2039`、`:2373-2389`），且**行为不一致**：

   | 站点 | 请求属性 | 无匹配时 |
   |---|---|---|
   | `117-124` 白纹理 | 仅 HOST_VISIBLE（不含 COHERENT） | 无回退 → `return false` |
   | `902-914` 离屏 | 仅 DEVICE_LOCAL | 无回退 → 降级为直渲 |
   | `1755-1770` VBO | HOST_VISIBLE **且** HOST_COHERENT（精确） | 无回退 → 硬失败 `:1767-1770` |
   | `1883-1898` staging | HOST_VISIBLE + HOST_COHERENT（精确） | 无回退 → `return false` |
   | `2023-2039` RGBA 纹理 | DEVICE_LOCAL | **有回退**（见第 5 条） |
   | `2373-2389` ASTC 纹理 | DEVICE_LOCAL | **有回退**（与上者字节相同） |

   另：位掩码字面量不一致——`:118/903` 用 `1u << i`，`:1756, 1884, 2024, 2033, 2374, 2383` 用有符号 `1 << i`。

5. **注释与代码矛盾（承重）**：`VulkanBackend.cpp:2032` 与 `:2382` 的注释都写"回退到 HOST_VISIBLE（部分 Mali GPU 无纯 DEVICE_LOCAL 可选）"，但回退循环体是：

   ```cpp
   for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
       if (memReq.memoryTypeBits & (1 << i)) { memType = i; break; }
   }
   ```

   **完全不检查任何 property flag** → 实际会选中 `memoryTypeBits` 里编号最小的兼容类型，可能既非 HOST_VISIBLE 也非 DEVICE_LOCAL。两处字节相同的重复。

6. CPU 侧分层为：`调用者 → （无项目封装层）→ STL 容器 / std::vector::push_back → operator new → NDK 分配器`。`std::vector` 是唯一实质抽象。
7. `VulkanBackend.h:258` 的注释标题写"纹理延迟释放（destroyTexture **空实现** → 契约补齐）"——当前实现确实非空（`:2595` 入队）。这是"注释滞后于代码"的实例。

### A-2 纹理：一张图集到底几份

8. **`destroyTexture` 在生产代码中零调用方。** 全仓 `git grep destroyTexture` 命中 16 行，逐条定性：

   | 位置 | 性质 |
   |---|---|
   | `Rhi.h:115` | 纯虚声明 |
   | `VulkanBackend.cpp:2589` / `GlesBackend.cpp:447` | 定义（延迟退役 / GL 删除入队） |
   | `NativeBridge.cpp:651-655` | JNI 导出，守卫 `if (!g_renderer \|\| id <= 0) return;` |
   | **`NativeBridge.kt:140`** | **唯一 Kotlin 引用，且是 `external fun` 声明本身** |
   | 4 处 `gamecore/test/scene_*_test.cpp` | 测试桩空实现 |

   `freeTextureResources`（`:2577-2585`，正确调用 `vkFreeMemory`）**只被 `submitFrame:2683-2689` 的退役排空触发**，而退役队列唯一写入者是 `destroyTexture` → **单张纹理在运行期永不释放**。

9. **无纹理缓存、无去重、无引用计数。** `m_textures` 是裸 `std::vector<Texture>`（`.h:242`），无 key；`grep 'map<|unordered_map'` 在 `VulkanBackend.cpp` → 0 命中；`#include <set>`（第 10 行）对纹理未使用。`Texture` 结构（`.h:227-241`）无 refCount 字段。同一路径上传两次 = 两份 22.37 MB GPU 分配。
10. **纹理 ID 单调且不跨纪元重置**：`static uint32_t s_nextTextureId = 1`（`:1852`，文件作用域 static，**不是成员**）→ 后端销毁重建后 ID 从上次高水位继续，而 `m_textures` 从空开始。
11. **native 侧不存在图片解码链路**：`AAsset|AAssetManager|AAsset_read|stbi_load|libjpeg|turbojpeg|libwebp|WebPDecode|decompress` 在生产 cpp → **0 命中**；`VulkanBackend.cpp:4-5` 的 `asset_manager.h`/`asset_manager_jni.h` 是死 include。全部像素经 JNI 从 Kotlin 进入。
12. **mipmap 真实存在**：`:2352 imgInfo.mipLevels = mipCount`，ASTC 图集 mipCount=11（KTX 头实测），`:2419-2431` 逐级拷贝 → 1.33× 系数成立。GLES 路径**从不 `glGenerateMipmap`**（0 命中）→ 只有单级。
13. **场景卸载不释放纹理**：`SectMapViewport` 无条件常驻 `MainGameScreenContent` 的 `Box`（`MainGameScreen.kt:974-979`），世界地图/战斗都是 `GameOverlayHost` 覆盖层（`:1010-1016`）或 Dialog → 切场景不卸载地图、不卸载图集、渲染器继续 60fps 绘制。
14. **代表面析构才是唯一的纹理释放点**：`initSurface:277-281` 检测到 stale generation 时调 `destroySurfaceGeneration()`，该函数 `:372-378` 遍历释放全部纹理、`:383-390` 释放 3 个 VBO、`:380` 清空退役队列。`shutdown():421` 亦走同一路径。
15. **`resize()` 不释放也不重建纹理与 VBO**（`:1041-1096`）：只 destroy swapchain/offscreen/graphics objects，纹理仅重建 `descSet`（`:1091-1092`）→ 旋转/尺寸变化不产生双份 VBO 或双份图集。
16. **上传失败路径是干净的**：`uploadTextureImpl` 与 `uploadCompressedTexture` 的 `fail:` 标签（`:2182-2189`、`:2555-2562`）逐个 `vkDestroyImageView / vkDestroyImage / vkFreeMemory / vkDestroySampler` 后返回 0 → **Kotlin 侧的 ASTC 失败回退（`AtlasAsyncPipeline.kt:78-84` 递归 `start(allowCompressed=false)`）不会泄漏已失败的那份**。但 **staging 若已在 `:2404` 扩到 22.37 MB 则永久保留该高水位**。

### A-3 GPU 与每帧

17. **VBO 实际尺寸与表达式自相抵消**：`.h:217 m_vertexBufferSize = MAX_VERTICES * sizeof(SpriteVertex) * 2`（122880 × 32 × 2 = 7,864,320），`:1737 bufInfo.size = m_vertexBufferSize / 2` → 每 buffer **3,932,160 B**，共 **11,796,480 B（11.25 MiB）** HOST_VISIBLE\|COHERENT，且 `:1784` **init 时一次映射、整个纪元保持映射**（帧路径无 `vkUnmapMemory`），写入直接 `memcpy`（`:2620`）。溢出守卫用同一个 `/2` 值（`:2614, 2639`），自洽。
18. **staging 是只增不减的棘轮**：`ensureStagingBuffer:1855` 早退，`:1859-1863` 只在**需要更大**时销毁重建，`:1902 allocationSize = memReq.size`。全项目无收缩路径。
19. **Vulkan 稳态帧循环是零分配的**：
    - `beginFrame:2651-2656`：只有 `m_pendingDraws.clear()`（保留容量）+ `m_vboOffset = 0` + `m_backgroundVertexCount = 0`
    - `endFrame:2658-2660`：**空函数**（`:2659` 仅 `// 空实现`）
    - `drawBackground:2631-2649`：只有 memcpy 进 VBO 头部 + 15 个标量赋值
    - `submitFrame:2662-2973`：**无任何 `vkCreate*` / `vkAllocate*`**，全部栈对象（`SkyPushConstants skyPC{}` 128 B `:2764`、两个栈数组 `:2783-2784/2803-2804`、`VkImageMemoryBarrier barriers[2] :2893`）
20. **但 `m_pendingDraws` 是帧路径唯一的堆增长面**：`DrawCommand` 12 B（`.h:346-350`），`:2623 push_back`，`:2652 clear()` 保留容量，**全文件仅 `:2106 regions.reserve` 一处 reserve，`m_pendingDraws` 从未 reserve**（其全部 6 处引用：`.h:351`、`:417, 2623, 2652, 2796, 2814`）→ 高水位容量永久驻留；上限由 VBO 溢出守卫 `:2614` 间接约束（丢弃 + 日志）。
21. **GLES 后端每帧重新分配 GPU 顶点存储**：`GlesBackend.cpp:508-510` 每次 `submitFrame` 对整个 `m_vertexBuffer` 调 **`glBufferData`（不是 `glBufferSubData`，后者 0 命中）**，且 `:467-468` 的 `draw()` **不 clamp 到 `MAX_VERTICES`**（CPU vector 无上限）。
22. **GLES 上传额外多一份完整像素堆副本**：`GlesBackend.cpp:431-437` `PendingUpload up; up.pixels.resize(w*h*4); memcpy(...)`，排在 `m_pendingUploads` 里等渲染线程 `drainUploads:395` 消费；`GlesBackend.h:117` 定义 `std::vector<uint8_t> pixels`。2048² → **16,777,216 B native 堆瞬时副本**。像素拷贝刻意放在锁外（`:430` 注释"21MB 级数据不占锁"）。
23. **GLES 无 VAO/FBO/RBO/Sampler/Pbuffer**（`glGenVertexArrays|glGenFramebuffers|glGenRenderbuffers|glGenSamplers|eglCreatePbufferSurface` 全 0 命中）；shader 对象生命周期干净（`:225-226, 254-255` link 后即 delete，失败路径 `:89, 212-213, 275-276`）。
24. **ASTC / REPEAT / 地面环绕纹理是 Vulkan 独占**：`NativeBridge.cpp:678-682, 700-704, 731-738` 用 `dynamic_cast<VulkanBackend*>` 判定，GLES 上返回 0 走回退 → **GLES 机型付全 uncompressed RGBA 单级显存**。
25. **`VK_ERROR_OUT_OF_DATE_KHR` 没有重建路径**：唯一处理点 `:2706-2712` 只记日志并 return，`recreateSwapchain` → 0 命中；恢复完全依赖 Kotlin 主动调 `resize()`。
26. **`shutdown()` 与 `initDevice` 组合会泄漏 `VkInstance`**：`initDevice:248` 先 `createInstance()`（`vkCreateInstance` 在 `:476`），`:249-251` 任一步失败即 `return false` 且 `m_device` 仍为 `VK_NULL_HANDLE`；调用方随后 `delete` 后端 → `~VulkanBackend`（`.h:30`）→ `shutdown()` → **`:421 if (m_device == VK_NULL_HANDLE) return;` 早退** → `:445 vkDestroyInstance` 永不可达。每次失败重试泄一个 instance。`createWhiteTexture` 另有 5 条不释放已建对象的失败早退（`:107, 124, 133, 206, 220`），且 `:307-309` 把白纹理失败标为 non-fatal 继续跑。
27. **descriptor pool 用 `capacity()` 而非 `size()` 开容量**（`:1353`、`:1592`），且**从无 `vkResetDescriptorPool`**（0 命中），耗尽走 `rebuildDescriptorPool:1577-1610`，内部 `:1581-1582` `m_ready.exchange(false)` + `vkDeviceWaitIdle` → **上传线程首次耗尽池时全 GPU 排空一次**。
28. **每帧 3 次 `std::lock_guard`，其中一次在逐 draw 循环内**：`:2682`（退役排空）、`:2829`（**每次切纹理一次锁 + 一次 O(n) `m_textures` 线性扫描** `:2830-2835`）、`:2956`。
29. **渲染线程不是 native 线程**：`NativeBridge.cpp`/`GameCoreBridge.cpp`/`VulkanBackend.cpp`/`GlesBackend.cpp` 内 `std::thread|pthread_create|pthread_attr_setstacksize|AttachCurrentThread|ALooper` → **0 命中**。循环是 Kotlin `RenderThread : Thread("NativeRenderer")`（`NativeSurfaceView.kt:1267`）驱动、`while (running && isReady)`（`:1354`）；Vulkan/GLES 靠 `Thread.sleep` 节流（`:1563, 1567-1575`），`Choreographer` 只作为**软渲路径**的 tick 源（`:1320-1324`，`VsyncGate.kt:75-95`）。
30. **`m_atlasTextureId`（`VulkanBackend.h:275`，注释"主图集纹理"）是死字段**：只出现在头文件，`VulkanBackend.cpp` 内零读零写。
31. **帧内同步对象零创建**：6 个 semaphore + 3 个 fence 全部在 `createSynchronization:1825-1839` 建，尺寸 = `MAX_FRAMES_IN_FLIGHT`(3)；`submitFrame` 内只 `vkResetFences:2722`。每次**上传**另建 1 fence + 1 command buffer（`:1944/1959/2076/1960`、`:184/193/153/190`）。
32. **`MAX_FRAMES_IN_FLIGHT` 硬编码 3**（`.h:208`），并同时被用作 `minImageCount`（`:731`）→ 无法独立调低。

### A-4 CPU 侧

33. **`column_dirty.h` 扩容不是均摊的**：

    ```cpp
    // :657-672
    void ensureRowCapacity(std::size_t rows) {
        const std::size_t need = rows * kWordsPerRow;
        if (rowBitsSize_ >= need) return;
        std::unique_ptr<std::atomic<uint64_t>[]> grown(new std::atomic<uint64_t>[need]);  // :663
        for (std::size_t i = 0; i < need; ++i) { grown[i].store(...); }                   // :664-669
        rowBits_ = std::move(grown); rowBitsSize_ = need;
    }
    ```

    按**精确请求行数**分配，无余量、无倍增因子。`setBit(:674-675)` 调 `ensureRowCapacity(row+1)`；`markRowAllColumns(:422-423)` 同理。`disciple_store.cpp:160` 定义 `const std::size_t row = ids.size()`，`:290` 在**每次 append 末尾**标脏 → **顺序加载 N 个弟子时逐行精确扩容 2 个字（16 B），共 N 次 `new[]`+`delete[]`，累计搬运 ≈ 8N² 字节**（N=5000 → **约 200 MB memcpy、5000 次堆 churn**）。`loadFromVector`（`:293-297`）正是 clear + 逐个 append 的存档加载路径。
34. **该位图容量永不下降**：`resetBaseline():480-489` 注释明确"清零但**保留已分配容量**"（理由是并行批次扩容会 use-after-free）；`clearRowBits():501-505` 同样只清位。高水位 = 进程内历史最大行数 × 16 B。
35. **`baselineJson_` 是一份完整游戏状态的 JSON DOM，常驻。** `dirty_tracker.h:92` 声明成员；`resetBaseline(s):133` / `syncBaselineToCurrent:137` 赋 `stateToJson(s)`，而 `stateToJson` 实现是 `dirty_tracker.cpp:33-36 json j = s;`（整棵 `GameState`，`kEntityCollections:20-30` 首项即 `"disciples"`）。生产调用点：`game_core.cpp:217`（init）、`:566`、`:635`（import）→ **init 之后即常驻，直到下一次全量重置**。
36. **diff 期两棵全量 DOM 并存**：`diffToTree:143-150` 以 `const json& base = baselineJson_; json cur = stateToJson(current);` 同时持有新旧两棵全量树，再叠 `changed`/`removed` 两棵子树，`diffToJson:141` 再 `.dump()` 出一个完整字符串。
37. **全量导出这条臂在生产可达**：`:707-715 fullExport = columnExportBlocked_` → 走 `dirtyTracker_.diffToTree(state_)`；锁存由 `noteNonSettlementMutation()`（`game_core.h:201` ← `execute_dispatch.cpp:2651`、`game_core.cpp:757, 779`）设置。`nativeExportState` 恒走 `exportStateJson()`（`GameCoreBridge.cpp:479`）= 全量 DOM + 全量 dump 字符串。
38. **`shrink_to_fit` 全仓 0 次** → gamecore 内没有任何容器在存活期归还容量；`make_shared` 0 次 → 无引用计数控制块。
39. **`DiscipleStore` 是 112 个独立 `std::vector`**（`disciple_store.h:161-295` 计数确认），含：
    - **18 个 `std::vector<std::string>` 列**：`ids:161`、`names:169`、`surnames:170`、`genders:171`、`portraitRes:172`、`discipleTypes:173`、`spiritRootTypes:174`、`statuses:198`、`activePillCategories:244`、`weaponIds:247`–`accessoryIds:250`、`partnerIds:260`、`partnerSectIds:261`、`parentId1s:262`、`parentId2s:263`、`masterIds:267`
    - **9 个 `std::vector<std::vector<std::string>>` 列**：`manualIds:193`–`affixIds:196`、`activePillTypes:243`、`usedPermanentPillKeys:289`–`usedExtendLifePillIds:292`
    - **2 个 per-row `std::map` 列**：`manualMasteries:197`（`map<string,int32_t>`）、`statusData:199`（`map<string,string>`）
    - **1 个 per-row `std::vector<StorageBagItem>` 列**：`storageBagItems:255`
40. **`std::map`/`set` 被系统性用作首选容器**（确定性要求，`column_dirty.h:66-67`、`disciple_store.h:25`）→ 每个"索引"逐节点分配 + 约 32 B 树节点开销。
41. **账本上限只在导入侧生效**：`normalizeLedgers`（`game_core.cpp:74`）**唯一调用点是 `:629`（import 内部）**，tick/settle 路径 0 调用。C++ 侧对 `mailRecords`（`models.h:1412`）的全部引用只有：`models.h` 声明、`game_core.cpp:76-78` 裁剪、`json_codec.cpp:1310,1396` 序列化——**`push_back` 不发生在 C++**。
42. **内容 DB 是 ~80 个 Meyers 函数局部 static（29 个文件）**，无 `instance()`/`getInstance()`、gamecore 内无 `g_` 前缀，**代码里不存在任何清除点** → 首次触碰后进程终身占用，含带中文描述的 `std::string`（如 `equipment_db.h:162, 176`）。清单：`manual_db.h:70`、`equipment_db.h:44`、`herb_db.h:50`、`beast_material_db.h:34`(192 行)、`recipe_db.h`(5 个 static ~207 行)、`trait_db.h`(3 个 ~66 行)、`beast_config.h:119,135,152,171,189,206,225,243,264`(**9 个**)、`name_service.h`(**9 个**)、`level_generator.h`(7)、`disciple_factory.h`(5)、`disciple.h`(3)、`redeem_code.h`(3)、`appointment_tx.h:93,102`、`PhaseClock::mono()` 的 `static SteadyMonotonicClock fallback`（`engine_loop.h:189`）。
43. **`GameCore` 生命周期**：`g_gameCore` 只在 `GameCoreBridge.cpp:322` `new`，由 `:288-291` 的 `if (g_gameCore) { LOGW; return JNI_FALSE; }` 保证不可重复分配；`:332-333`、`:349-350` `delete`。同文件另有 6 个文件作用域 static 对象（`g_androidLogger:169`、`g_systemClock:170`、`g_androidMonoClock:171`、`g_androidTelemetry:172`、`g_thermalProvider:173`、`g_batteryProvider:174`），静态期，不堆分配。
44. **`g_renderer` / `g_atlas` 的重建路径都先 `delete`**：`:320-321`（prewarm）、`:437`（GLES 分支）、`:471-473`（Vulkan 全量分支）；`g_atlas` 由 `:299 if (!g_atlas)` 幂等保护，`:522-525` delete。**未发现渲染器双 `new` 泄漏**。
45. **`shared_ptr` 环不可能存在**：生产 cpp 内 shared_ptr/weak_ptr/enable_shared_from_this 仅 1 处注释命中。所有权是裸指针 + 值语义 static。
46. **ECS 不是生产弟子存储**：`ComponentStorage<T>` 是 dense SoA + sparse set 三并行 vector（`storage.h:154-156`），`sparse_` 按**历史最大实体索引**而非存活数 resize（`:150-152`），`clearAll()` 保留容量（`:134-138`），**无最大实体常量、无任何上限**。真正的 per-entity 生产存储是 `DiscipleStore`。
47. **`GameState::aiSectDisciples` 是残留的 AoS**（`models.h:1466` `map<string, vector<Disciple>>`），每 sect 上限 `kAiDisciplesPerSectLimit = 1000`（`ai_sect_recruit.h:55-56`，由 `truncateToAiLimit:324` 在 `:399,403` 强制），但**sect 数量本身在代码中无界**。
48. **地形数据很小且单层**：唯一持久 tile 数组是 `GameData::terrainTiles`（`models.h:1360` `vector<int32_t>`），由 `generateTileData` 填（`terrain.h:227`）、`game_core.cpp:676-678` 赋值 → 128×128 ⇒ **65,536 B**。**gamecore 内不存在多层 tile 存储**。
49. **`std::function` 长持有者只有两处**：`SettlementHooks` 的 4 个成员（`settlement.h:57-62`，固定 4 个不增长）与 `inventory.h:513 maxSlots_`（每库存实例 1 个）。其余均为 `const&` 参数，不持所有权。
50. **`nlohmann::json` 生产解析点只有 3 个**：`data_store.cpp:30`（非抛 `parse(json, nullptr, false)`，局部变量，注入后随作用域销毁——不长期持有）；`game_core.cpp:587`（存档 load，**会抛**，未加 `false` 标志，`j` 横跨整个 `importStateInternal`，`:588` 先物化新 `GameState` 后才覆盖旧 `state_` → **2× 峰值**）；`execute_dispatch.cpp:2658`（每命令参数，局部）。

### A-5 平台层

51. **JNI 副本与释放配对完整**：3 处 `GetByteArrayElements` / `GetStringUTFChars` / `GetFloatArrayElements` 全部有配对 `Release`（`NativeBridge.cpp:725/743`、`:1446/1451`、`:331/337`；`GameCoreBridge.cpp:248/250`、`:266/269`、`:302/305`、`:948/951`），且 null 检查都在取得所有权**之前**。**`GetPrimitiveArrayCritical` 0 命中** → 不存在 critical-section 泄漏类。
52. **`GameCoreBridge.cpp:246-250` 存在未检查 null 的构造**：`jbytesToString` 检了 `!array` 与 `len<=0`，但**没检 `bytes == nullptr`** 就 `std::string out(reinterpret_cast<const char*>(bytes), len)`。同一缺陷在 `GameCoreJni.cpp:77-79`（测试 TU）。
53. **两个 release 点无 RAII 守护**：`NativeBridge.cpp:725→743`、`:1446→:1451` 之间若抛出 C++ 异常即跳过 `Release`。编译选项**未加 `-fno-exceptions`**——`gamecore/CMakeLists.txt:56-58` 的注释自证："历史注释原称禁异常/RTTI，实际未加 `-fno-exceptions/-fno-rtti` 旗标"。
54. **`AndroidManifest.xml` 实测**：`android:largeHeap="true"`（`:86`）、`hardwareAccelerated="true"`（`:88`）、`extractNativeLibs="true"`（`:87`）、`usesCleartextTraffic="true"`（`:84`）、`android:graphics.renderer="skiagl"`（`:96-99` 强制 HWUI 走 SkiaGL）、Game Mode 元数据（`:101-106`）、**`android:process` 全仓 0 命中 → 单进程承载 App + 全部 SDK + 两个 .so + `GameForegroundService`**（`:217-224`）。
55. **`CacheLayer.kt:215` 的 LRU 自驱逐被显式关掉**：`override fun removeEldestEntry(...) = false`，驱逐全依赖手动/压力路径（`GameDataCacheMaintenance.kt:16-34`）。预算按机型档位 20/50/100 MB，`maxEntryCount` 500/1500/2000/3000（`CacheConfig.kt:55,81,108,123`），仅当 30 s 维护轮跑到才生效。
56. **`sectMapCache` 无任何移除路径**：`git grep sectMapCache` 全仓 **2 行命中**——声明（`SectMapController.kt:36 ConcurrentHashMap<Int, MapPreloadData>`）与 `getOrPut`（`:53`）。无 `remove`、无 `clear`。同时 `SceneUpdateChannel.kt:59 pushedTerrain` 还会额外钉住上一份数组。
57. **`onLowMemory()` 是空的**（`GameActivity.kt:1114-1116` 只有 `super`）；`TRIM_MEMORY_UI_HIDDEN` 分支**只有注释**（`:1122-1124`）；`RUNNING_LOW/MODERATE/BACKGROUND/MODERATE` 分支**只有 `Log.w`**（`:1133-1140`）。真正做事的只有 `RUNNING_CRITICAL`/`COMPLETE` 两行（`:1128` 置空 `mapPreloadDataRef`、`:1131` `SectAtlasPrefetch.clear()`）。
58. **`external fun` 共 145 个，生产 88 个**（48 renderer + 40 gamecore；57 个属测试对拍桥 `DiffRngBridge.kt`）。每帧无条件 JNI 穿越 5 次（`VulkanRenderBackend.kt:82, 86` + `drawSkyBackground` + `renderSceneStorePath→drawFrame` + `:120 submitFrame`），全标量、零数组编组。引擎循环每 tick 另加 `NewLongArray(17)`（`GameCoreBridge.cpp:238`）+ Kotlin 侧 2×`IntArray(5)`（`GameCoreBridge.kt:550-551`）+ `NativeLoopPlan` + `LoopIterationState`。
59. **最大 JNI 载荷**：`uploadCompressedAtlas(ByteArray)` 22,369,724 B（`NativeBridge.kt:186`）；`uploadIslandCliffKtx(ByteArray)` ≤5,865,448 B（`:495`）；`sceneSetTerrain(IntArray)` 65,536 B（`:288`，128×128，`GameConfig.kt:1012-1014`）；`nativeSetGameData(String)` 源文件 1,130,054 B（`:424`）。
60. **`res/drawable-nodpi` 磁盘体量实测**：`app/src/main/res/drawable-nodpi` **205,836 KB**、`feature/game/src/main/res/drawable-nodpi` **241,076 KB**（合计 ≈ 437 MB，708 个 webp 文件），323 个文件名两侧重名，抽查 `bg_horizontal.webp` / `ui_button.webp` **md5 完全相同** → 打包级重复。最大单张 `bg_horizontal.webp` 4096×2300 → 解码 **37.68 MB RGBA8**；`ui_button.webp` 3828×1384 → **21.19 MB**，且它被用作**按钮背景**（`GameButton.kt:45`、`CloudConflictDialog.kt:53,59`、`GameActivity.kt:579,586`）。
61. **`assets/` 实测 102,792,516 B（≈98 MiB）**，其中 `atlas/` 60 MB（`atlas_astc.ktx` 22,369,724 + `atlas-rgba-mips.bin` 22,369,616 + `atlas-rgba-raw.bin` 16,777,216）+ `edge/` 7 个 ktx 共 39,501,308 B。
62. **同一批 7 张地图边缘图在磁盘上以两种编码各存一份**：`assets/atlas/edge/*.ktx`（ASTC）与 `res/drawable-nodpi/map_edge_*.webp`。
63. **字体零位图成本**：无 FreeType / stb_truetype / fontcodecs（全 0 命中）；字形预烘焙进图集（`build-atlas.mjs:278`，"40 字形 × 88"），UV 表 `kFloatUv[192]` = 768 B `.rodata`（`scene_uv_tables.h:205`）。`FloatTextPool` 固定 `slots_[256]`（`float_text.h:38, 321`）→ 无池外分配。
64. **scene 头文件里的静态表极小**：`kTileUv` 44×4=176 B、`kBuildingUv` 80×4=320 B、`kFloatUv` 768 B、`kCropUv/kCloudUv/kRoadUv` 176 B、尺寸表 384 B、`kFloatStyles` <1 KB → 合计约 2.7 KB `.rodata`（`inline constexpr`，一份）。`TextureAtlas.cpp:7-19` 建 41 条 `unordered_map<string, AtlasRegion>` ≈ 4 KB。生成的 `android/app/build/generated/sprite/TextureAtlas.h`（153 行 / 6,195 B）**只有类型，无像素表**，`ATLAS_W/ATLAS_H = 4096`（`:60-61`）。
65. **日志不留内存**：C++ 侧 `logger.h:21-37` 只有 `ConsoleLogger`/`NullLogger`，Android sink `GameCoreBridge.cpp:164-178` 直落 `__android_log_print`，**无缓冲、无容器、无 cap 字段**；遥测 `:207-213` 同样无累加。Kotlin 侧 351 处 `Log.*` 全 fire-and-forget（`XianxiaApplication.kt:356-366` 的 `DomainLog.Logger` 是纯直通）；崩溃日志写文件（`CrashHandler.kt:261`）。有界环形缓冲只用于 FPS/延迟统计（`CircularBuffer.kt:12` 硬容量覆写、`CustomVelocityTracker.kt:25 ArrayDeque(historySize)`、`StorageCircuitBreaker.kt:54`）。
66. **Room**：`GameDatabase.kt:140-176` 34 个 `@Entity`，WAL（`:521`），`fallbackToDestructiveMigrationFrom(1)`（`:545`），执行器 `newFixedThreadPool(2)` + 单事务线程（`:523-530`）。**未引入 Paging** → ≥10 个无 `LIMIT` 的 `List<T>` 查询：`DiscipleDao.kt:32,35,38,49,53`、`ChangeLogDao.kt:24,50,53`、`BattleLogDao.kt:43`、`GameDataDao.kt:31,43`（`BattleLogDao.kt:16,19` 有 `LIMIT 200`）。无 `inMemoryDatabaseBuilder`、无 `allowMainThreadQueries`。
67. **KV 存储**：MMKV 为主（`GamePreferences.kt:47`，索引在 native mmap 不在 Java 堆）；**7 处 `getSharedPreferences` 仍存活**，每个在 RAM 缓存全量键集（`CrashHandler.kt:77`、`CrashRecoveryEngine.kt:108`、`RenderDebugSwitches.kt:42`、`TapDBManager.kt:54`、`DeviceBindingIdentity.kt:54,156,173`、`SecureKeyManager.kt:150,313,390`、`SessionManager.kt:169`）。无 Jetpack DataStore。
68. **线程清点**：`RenderThread("NativeRenderer")`（`NativeSurfaceView.kt:1267`）、`GameEngine-Thread`（`GameEngineCore.kt:340-346` MAX_PRIORITY）、`GameEngine-Watchdog`（`:360-366`）、`AppStartup-Init`（`XianxiaApplication.kt:353-355`）、`GameDB-Query-1..2` + `GameDB-Txn`（`GameDatabase.kt:523-530`）、`DeviceCapabilityProfiler` 2 个固定池（`:56,61`）、图集装配线程（`NativeSurfaceView.kt:822-823`、`AtlasAsyncPipeline.kt:68 "AtlasBuild"`）、`Dispatchers.Default/.IO`（`ApplicationScopeProvider.kt:51,54`）+ `UploadQueue` scope（`SaveBackendModule.kt:37`）。**`stackSize` 从未设置（0 命中）**——`SpriteBatcher.h:19-20` 把内联缓冲压到 512 顶点，理由正是"Android 背景线程默认栈 ≈1MB 会溢出"，等于代码自己承认按 1 MB 假设、从不配置。`GameEngineCoreHandOps3.kt:121-127 recreateGameDispatcher()` 有 `leakedGameThreadCount`（`GameEngineCore.kt:352`）计数器，**说明旧 `GameEngine-Thread` 可能不退出**，每个滞留者持 1 MB 栈 + 协程 job 图。
69. **`GameCoreJni.cpp` 不编入生产 `.so`**（`gamecore/CMakeLists.txt:26-45` 源列表 + `:75` 测试门）→ 该 TU 里永不释放的 `g_rng:63`/`g_rngManager:64`/`g_core:67`/`g_monitor:564` **不构成生产风险**。全仓三个桥文件 `JNI_OnUnload` 0 命中。
70. **音频**：`SoundPool`(≤8 流) + `MediaPlayer`(BGM)（`AndroidAudioPlayer.kt:44-45, 82`），仅 2 个文件 `bg_main.mp3` 1,590,770 B + `sfx_button.mp3` 8,690 B。解码缓冲由平台 AudioFlinger/SoundPool 持有，非 App 堆。`SectTransitionOverlay.kt:5` 用 `MediaPlayer` + `TextureView` 放过场视频（surface 归系统），且 `:61-64 onDispose { controller.release() }` 是全项目**唯一真正的 enter/leave 释放**。

### A-6 场景与生命周期

71. **本项目没有"场景"抽象**：三件不相干的东西共用这个词——`scene::SceneStore`（`scene_store.h:80-296`，只是 6 个 vector + 1 个 PreviewState 的缓冲区持有者）、`GameEngineCore.GameScene` 枚举（`{IDLE, MAP_SCROLL, GAMEPLAY, GAMEPLAY_IDLE, BATTLE}`，**唯一用途是选一个 FPS 数字**，`GameEngineCore.kt:213-224`、`GameEngineCoreSetOps1.kt:52-59`）、以及 Compose 屏幕。**没有 NavHost**（`NavHost|composable(|navigate(` → 0 命中），`MainActivity.kt:695-714` 直接 `setContent {}` 换。
72. **不存在 leave-scene 路径**：`onLeave|onEnter|unload|Unload` 在生产 cpp/.h → **1 命中，且是注释**（`GameCoreJni.cpp:1812`）。`g_scene` 的变更点只有 `sceneSet*/sceneUpdate*`（插入）与 `shutdownRenderer`（`:552`）。
73. **切换场景是 realloc-free 但也 release-free**：所有宗门地图同为 128×128（`GameConfig.kt:1013-1014`），`vector::assign` 同尺寸即 memcpy 进已有块（`scene_store.h:93,118,136,153,171,189`）。
74. **插入型缓存清单**（只有 `cropState.lastProgress` 一处真驱逐）：

    | 缓存 | 插入点 | 驱逐 |
    |---|---|---|
    | `SectMapController.sectMapCache` | `:53 getOrPut` | **无** |
    | `SaveLoadViewModel._l2Sprites` | `:65 现有值 + sprites`（写时合并），`launchL2Preload:63-67` 无幂等守卫 | **无** |
    | `scene_draw.h` `static std::vector<ObjectDecorDrawItem> decorItems`（+4 个同类） | `:295-299`，`push_back/resize :360,461,471,475,478` | `clear() :300-304` → **容量保留**；封顶 `kMaxObjectDecorItems=20000` × 36 B ≈ 720 KB |
    | `CropSmoothingState::lastProgress` | `scene_draw.h:557` | `erase :591` → **唯一真驱逐** |

75. **渲染器只随 surface 死，不随 Activity 死**：创建于 `NativeBridge.cpp:333/438/474`，销毁**仅**经 `Java_..._shutdownRenderer`（`:511-566`，`delete g_renderer :519`）← `VulkanRenderBackend.release()`（`:220-222`）← `stopRenderThread()`（`NativeSurfaceView.kt:874`）← `handleSurfaceDestroyed()`（`:828`）。`GameActivity.onDestroy`（`:1076-1112`）**没有任何渲染器调用**，并在 `:1108-1111` 明确不关 `GameEngineCore`。
76. **`shutdownRenderer` 在 Kotlin 主源码里只有一个调用点**（`VulkanRenderBackend.kt:221`，GPU 适配器专用）→ **软渲路径与 Activity onDestroy 都不做 native teardown**。
77. **`resize()` 失败路径会让后端"半死"**：`:1063-1069` 六个 `if (!...) return false;` 在 `m_ready=false`（`:1048`）下早退，且 `destroyGraphicsObjects` 已把 `m_descriptorPool` 置空、所有 `descSet` 置 null（`:1632-1633`）→ 永不恢复。不泄漏，但 11.25 MB VBO + 全部纹理 + staging 会在一个永久死掉的渲染器后面继续占着。
78. **`createSwapchain` 中途失败会留残**：`:770-774` 一个 `vkCreateImageView` 失败即 return，`m_swapchain` 已建、`m_swapchainViews` 半填。
79. **帧索引与 imageIndex 混用**：`:2724 m_commandBuffers[imageIndex]`、`:2744 m_framebuffers[imageIndex]`，同一行却用 `m_offscreenFramebuffers[m_currentFrame]` / `m_vertexBuffers[m_currentFrame]`。仅当 `imageIndex == m_currentFrame` 时等价。
80. **每帧全量重建顶点流**：`drawFrame → scene::buildMapBatch`（`NativeBridge.cpp:1377`）从零遍历整个可见网格（`scene_draw.h:307` 行 × `:310` 列），逐格出地面 quad（`:325`）、装饰（`:352/360`）、道路再扫同一矩形（`:377-406`，每格 ≤6 quad）、建筑（`:418`）、作物（`:525`）、云（`:601`）、叠加层网格线（`:883-890`）、浮字（`NativeBridge.cpp:1409`）。上界 128×128 = **16,384 地面 quad**，与 `Rhi.h:48-49` 的常量自述一致（`20480 = 16384 地面 + 余量`）。`scale < 0.6` 时跳过装饰/云（`NativeBridge.cpp:884-889`）。
81. **脏追踪对渲染无用**：`ColumnDirtyTracker` 是**状态同步**机制（C++ 真相 → Kotlin 镜像），从不触碰 `SceneStore`/`SpriteBatcher`/`buildMapBatch`。消费侧证据：`NativeBridge.cpp:1336-1383` 的守卫只有 `hasTerrain() && atlasTexId != 0`，然后无条件全量 `buildMapBatch`——**无脏行集、无变更格列表、无增量路径**。渲染层唯一的工作量削减是 Kotlin 侧粗粒度的整帧跳过（`FrameSkipPolicy.kt:40-48` 全 8 个信号 false → 不调 `renderTick()`，`NativeSurfaceView.kt:1401-1406`）；一旦有东西脏，重建就是 100%。
82. **`SpriteBatcher` 溢出是静默丢弃**：`add()` 在 `grow()` 封顶后再检一次，超限则 `droppedSprites++` 并 return（`SpriteBatcher.cpp:28-31`）；丢弃序 = 后添加者（阴影/作物/建筑），地面永存（`:26-27`）。溢出遥驱动一帧装饰降级，30 干净帧后清除（`NativeBridge.cpp:266-273, 276-282, 884-889`）。
83. **`g_sky` / `SkyBackground`**：纯参数持有者，每帧把 15 个 float 交给 `drawBackground`，天空渐变由片元着色器逐像素算（`Rhi.h:79-94`）→ **无背景纹理，零 GPU 贴图成本**。

---

## 6. B. 高概率推断

> **以下每一条都是推断，不是已确认事实。**

| # | 推断 | 依据 | 为何不敢升 A |
|---|---|---|---|
| B-1 | `GetByteArrayElements` 在 ART 上对 22.37 MB 的 `byte[]` **返回 malloc 副本而非直指针**，故 ASTC 上传峰值 ≈ 89.5 MB | JNI 规范允许两种实现；ART 对非 pinned 数组通常拷贝 | 需真机 `/proc/<pid>/status` VmSize 采样 |
| B-2 | 图集上传的 `Release(…, JNI_ABORT)` 之后 Kotlin `ByteArray` 才可回收，因此**"上传完成即释放 CPU 副本"这件事由 GC 时机决定，不由代码决定**（无 `System.gc()`） | `SectAtlasPrefetch.kt:60 consume() getAndSet(null)` 只断引用 | 峰值存活期需 profiler |
| B-3 | 单个 4096² ASTC mip0 的 `vkAllocateMemory` 实际 `memReq.size` **会大于** 16,777,216（optimal tiling 对齐/块填充） | `:2370 vkGetImageMemoryRequirements` 由驱动报告 | 无设备测量 |
| B-4 | `baselineJson_` 在 5000 弟子档位的量级为 **数十 MB**（nlohmann 相对紧凑文本约 4–8× 膨胀，每 object 项 = 16 B json 节点 + map 节点 + key string） | 结构可从 `dirty_tracker.cpp:34` 直接读出 | 5000 这个数字只出现在注释（`job_system.h:16`、`game_core.h:282`、`dirty_tracker.h:55`），非实测；膨胀系数是 nlohmann 常识而非本仓证据 |
| B-5 | 每个弟子行的堆开销显著高于有效载荷（112 列独立倍增尾 + 每行约 30 个可分配单元 + Scudo 16 B 粒度 + libc++ string SSO 溢出） | 容器结构 A 级可证（`disciple_store.h:161-295`） | `sizeof(Disciple)` 未编译期测量；Scudo/SSO 阈值是工具链常识非本仓证据 |
| B-6 | 生产路径上 `GameData::mailRecords`/`gameEventRecords`/`sectBattleRecords` **可跨会话无界增长**（因 cap 只在 import 生效） | `normalizeLedgers` 唯一调用点 `:629`（A 级） | 增长发生在 Kotlin，未读完 Kotlin 生成侧是否自带 cap |
| B-7 | GLES 机型上 `uploadTextureDirect` 的 16.7 MB `vector` 副本与 `m_vertexBuffer` 无上限增长同时存在时是内存压力峰值 | `GlesBackend.cpp:435-437`（A 级）+ `:467` 无 clamp（A 级） | 未验证 GLES 上图集是否回退到更小的 buffer |
| B-8 | 渲染线程 2 s join 超时 + 5 s 延迟释放失败 → 新一代 `GlesBackend` 与仍存活的 `VulkanBackend` **同时持有各自 GPU 资源**（双份 swapchain/图集） | `NativeSurfaceView.kt:847-859, 860-870, 886-912` 与 `NativeBridge.cpp:437` 的 delete-then-new | 需真机复现卡顿/后台恢复才能确认频率 |
| B-9 | `dynamic_cast`（`NativeBridge.cpp:679, 701, 732` 等）依赖 RTTI；若 NDK 构建关闭 RTTI 这些回退判定会编译失败——说明 RTTI 是开的，但也意味着每纹理切换一次跨库 `dynamic_cast` 开销 | 代码可编译这一事实 | 没读 NDK toolchain 的 rtti 设置 |
| B-10 | 软渲路径的 Bitmap 像素按 API≥26 归 native 堆（`NativeAllocationRegistry`），Java 堆只有壳 | Android 平台行为 | minSdk/targetSdk 未读 |

---

## 7. C. 无法从当前实现确认（明确留白，未自行补全）

1. **实际运行时尺寸**：弟子数、建筑数、存档字节数、Room 各表行数、`RenderFrame` 各 FloatArray 长度——全部来自有界常量推导，**无一条来自实测**。`GameConfig.kt:1012-1014`（128×128、tile 48）是唯一硬数。
2. **一次真实会话里 `onRendererReady` 触发几次**（3 个可达 `invoke()` 点：`NativeSurfaceView.kt:983, 1116, 1206`）→ 决定"图集被上传几份"，静态不可判定。
3. **`m_vertexBuffer` / `m_pendingDraws` / 4 个 batcher 的实际高水位**——只知上界（3.93 MB/个），不知实到值。
4. **Compose `painterResource` 解出的 webp 位图落在 Java 堆还是 graphics native 池、池上限多少、437 MB 资源里实际有多少被解码**——116 处调用点逐点未证；`SpriteResRegistry.resolve(...)` 的动态查找路径未闭合。
5. **`AtomicStateFlowUpdates.kt:24` 的 `ConcurrentHashMap<MutableStateFlow<*>, Mutex>` 是否被清理**——未找到移除路径，但也没穷举所有清理入口。
6. **`GameViewStore.kt:91 pendingEvents ArrayDeque`（无 capacity 实参）是否有消费上界**。
7. **Bugly native crash reporter / MMKV / 4 家广告 SDK 的实际 native 堆占用**（广告 SDK 已确认在登录成功后才 init，见 `AdServiceImpl.kt:149-155`、`SdkInitGuard.kt:6-10`；`BUGLY_APP_ID`/`UMENG_APP_KEY` 若为空可能整体 no-op）。
8. **`ecs::World`（`ecs/world.h:14`）在生产路径是否被实例化**——只找到定义与 `clearAll`/`destroyEntity`，未找到场景级构造点。若否，ECS 那三并行 vector 就是死代码占位。
9. **`NativeBridge.cpp:1437 drawIslandCliffs` JNI 端口是否已被生产调用**（`VulkanRenderBackend.kt:259-271` 定义了但 `renderFrame` 内未找到调用；崖壁实际走 `drawFrame → drawCliffLayerInternal` `:1331-1333`）。
10. **`atlas-rgba-raw.bin` / `atlas-rgba-mips.bin` 是否为发布 APK 的活跃资产**（工作树里 `atlas-rgba-manifest.json` 处于 modified 状态，三者时间戳 Sep 22 13:17，比 ASTC 资产新）。
11. **切换场景后 SceneStore 里"上一个宗门的建筑/作物数据是否残留"**：`SceneUpdateChannel` 是变化驱动整体替换（`assign`），逻辑上应无残留，但我没找到任何"清空上一宗门"的显式端口，因此**只能证"容量不换"，不能证"内容不残留"**。
12. **跨模块重名 webp 在资源合并后哪一份胜出**（决定了运行时是否真的只解码一份）。
13. **`kDiscipleColumnCount` 的机器计数**：`column_dirty.h:34` 与 `disciple_store.h:153 kCount` 的 109 是目视计数，未脚本校验；`kWordsPerRow = (109+63)/64 = 2` 随之成立。
14. **`EquipmentNurtureData` / `StorageBagItem` / `Combatant` / `ViewEventDraft` 的精确布局与大小**。
15. **`exportDirtyJson`（`game_core.cpp:681` 全量 DOM 臂）是否被生产 JNI 触达**——只读到 `GameCoreBridge.cpp:545-546` 的注释声称"列级导出恒开"，未逐行读完该函数体。

---

## 8. D. 风险点（只列已勘察到的，不给方案）

### D-1 明确泄漏 / 单调增长

| # | 风险 | 触发场景 | 现有缓解 | 等级 |
|---|---|---|---|---|
| R1 | **纹理 GPU 内存 append-only**：每次上传 = 1 份 22.37 MB（图集）/ 5.5 MB（崖壁）`VkDeviceMemory`，运行期无释放路径（`destroyTexture` 零调用） | **同一纪元内**重复上传：ASTC 校验失败重跑（`AtlasAsyncPipeline.kt:78-84`）、崖壁 holder 随 view 重建、`onRendererReady` 多点触发 | 仅**代表面析构**兜底（`:372-378`）；`resize()` 不触发重传 | A |
| R2 | **staging 高水位棘轮**：一次 2048² mip 链上传即永久钉住 22.37 MB HOST_COHERENT | 首次上传最大纹理 | 无收缩路径；`onTrimMemory` 不响应 | A |
| R3 | **`VkInstance` 在 initDevice 失败路径泄漏** | 驱动/物理设备挑选失败后重试 | 无 | A |
| R4 | **`sectMapCache` 只增**：每个访问过的宗门永久保留 65 KB IntArray | 跑图 | 无 | A |
| R5 | **`baselineJson_` + `restBaseline_` 两棵常驻 JSON DOM**；diff 期 2 棵全量 + 2 棵子树 + 1 个 dump 字符串并存 | 每次全量导出（`columnExportBlocked_` 锁存后） | `columnTracker_.exportDirtyTree` 只窄化弟子列，非弟子段仍全量序列化（`column_dirty.h:596-598`） | A（量级 B-4） |
| R6 | **账本 vector 跨会话无界**（cap 仅 import 生效） | 长时游玩 | `normalizeLedgers` 只在 import | B-6 |
| R7 | 延迟退役队列 `m_retiredTextures` **与"是否在出帧"耦合**：`m_ready==false` 或无 surface 时排空逻辑不跑 | 理论风险（因 R1 该队列实际恒空） | 代表面析构 `:380` 兜底 | A |
| R8 | `createWhiteTexture` 5 条不释放已建对象的失败早退，且被标为 non-fatal | 内存/驱动异常 | 无 | A |

### D-2 CPU/GPU 双份、多份

| # | 风险 | 量化 | 等级 |
|---|---|---|---|
| R9 | ASTC 图集 4 份并存（Java ByteArray / JNI 副本 / staging / DEVICE_LOCAL image） | 峰值 **≈89.5 MB 一张图集** | B-1 |
| R10 | 崖壁 7 张的 Kotlin 侧同时存活：`IslandCliffTextureLoader.kt:66-70,127` 一个 `List<Prepared?>` 持有 7 份 ByteArray | **≈37.7 MB 同时** | A |
| R11 | 非 ASTC 机型崖壁走 direct ByteBuffer 解码路径（`IslandCliffTextureLoader.kt:221`） | 理论 **150.6 MB 同时**（157,908,800 B RGBA8） | B |
| R12 | **同一批边缘图在 APK 里两份编码**（ktx + webp）；323 个 nodpi 文件名跨模块重名且抽查 md5 相同 | 磁盘 ~437 MB 的重复面 | A |
| R13 | GLES 路径：Kotlin direct ByteBuffer + `PendingUpload.pixels` 堆副本 + GL texture 三份 | 2048² → 3×16.7 MB | A |
| R14 | 软渲路径 atlasBitmap `recycle()` 被**显式禁用**（`AtlasAsyncPipeline.kt:336`、`SoftwareCanvasBackend.kt:1841-1843`）→ 像素回收与 Java 堆压力解耦，可瞬时超调 | 16.7 MB + 视口帧缓冲 | A |
| R15 | `importStateInternal`：`json j = parse(...)` 与 `state_ = j.get<GameState>()` 并存，旧 `state_` 在新状态**完全构造之后**才被覆盖 | 2× 峰值 | A |

### D-3 高频动态分配

| # | 风险 | 证据 | 等级 |
|---|---|---|---|
| R16 | **GLES 每帧整批 `glBufferData`** = 驱动侧每帧重分配顶点存储（至 3.93 MB） | `GlesBackend.cpp:508-510` | A |
| R17 | Vulkan 每帧 per-draw-call 一次 mutex + 一次 `m_textures` O(n) 线性扫描 | `:2829-2835` | A |
| R18 | 每帧 Kotlin 侧固定若干短命对象：`SceneUpdateInputs`、`BuildingDataSnapshot`、`FramePacing`、`FrameSkipInputs`、`RenderFrame`(≈44 字段)、`LoopIterationState`、`runCatching` 的 `Result` → **稳定的 ART 垃圾生成率**（不是泄漏，是 GC 抖动源） | `VulkanRenderBackend.kt:145-156`、`RenderCommandBus.kt:76-79`、`NativeSurfaceView.kt:1358,1458` | A |
| R19 | 引擎 tick（10 Hz）每次 `NewLongArray(17)` + 2×`IntArray(5)` + 若干对象 | `GameCoreBridge.cpp:238`、`GameCoreBridge.kt:542,550-551` | A |
| R20 | 每旬（1× 速度约 2 s）：并行批次 `JobSystem::submit(std::function)` 每 chunk 一次 `deque` 节点 + 一次 `std::function` 堆分配（捕获 32 B > libc++ 16 B SSO 缓冲 → **必定堆分配**），8 核约 16 次/旬 | `job_system.h:56, 90, 120` | A（SSO 阈值为工具链假设） |
| R21 | 每旬：`stateWithoutDisciplesToJson` 全量序列化 + `dump()` + `jbyteArray` 跨 JNI | `column_dirty.h:596-598, 647` | A |
| R22 | 每帧全量重建 16,384+ quad 顶点流（无增量路径） | `scene_draw.h:307-636`、`NativeBridge.cpp:1377` | A |
| R23 | 帧速率实证（1× 速度）：逻辑 tick 100 ms（`engine_loop.h:39,41,43`）、一旬 2000 ms（`settlement.h:31`）、补帧上限 3 旬/tick（`:33,38-40`）、3 旬/月（`time_system.h:20`）→ 月结算约 6 s 一次、年约 72 s 一次。**结算不是每帧** | — | A |

### D-4 容器扩容 / 碎片

| # | 风险 | 为什么 | 等级 |
|---|---|---|---|
| R24 | **`column_dirty.h` 精确尺寸扩容 → 顺序 append 时 O(N²) 搬运 + N 次 new[]/delete[]**，缓冲区只在 `16·n` 字节附近来回 | 触发：**加载任何一份弟子较多的存档**（`loadFromVector` 走 clear+append）。缓解：**无**（0 处 reserve） | A |
| R25 | **112 个独立 `std::vector` 列各自倍增** → 同一逻辑行的 112 块碎片散布在堆各处；最后一次倍增留下最多 64% 的容量尾；全仓 `shrink_to_fit` = 0 → **永不归还** | 触发：弟子数增长 + 存档切换。缓解：容量跨纪元复用（对 churn 有利，对 RSS 不利） | A |
| R26 | **4 个 static `SpriteBatcher` 各自独立 `new SpriteVertex[]` 封顶到 3.93 MB 且跨帧保留** → 最坏 15.7 MB 常驻大块，且增长期是 512→1024→…→122880 的**倍增链 churn（每链约 6 次 new+delete+memcpy）** | `NativeBridge.cpp:185-186` 的注释把它记成"≤2×16384×32B=1MB"——**实际是 4 个 batcher，上限是 122880 顶点**，注释低估约 15 倍 | A |
| R27 | **生命周期混置**：进程终身 static（内容 DB、`g_scene`、batcher、`s_nextTextureId`）+ 纪元级（renderer/纹理/VBO，可达数十 MB）+ 帧级/旬级（临时 vector、`std::function`、JSON DOM）在**同一个分配器堆里交替** | 这是本项目碎片的真实机制：无 arena 分层 ⇒ 大块纪元对象无法从混合 arena 中还给 OS | A |
| R28 | `m_pendingDraws` 无 reserve 且 clear 保容量 → 放置模式（网格线 + 全岛 16384 格叠加层）会推高到数千项 × 12 B 并常驻 | `:2623`、`:2652` | A |
| R29 | 全项目 **0 处 `mallopt` / 0 处 arena 配置 / 0 处 `M_PURGE`** → 无把已 free 的堆页交还 OS 的机制；`onTrimMemory` 里也不做 | 见 A-1、A-57 | A |
| R30 | 105 个函数按值返回 STL 容器，其中复制的是**元素**而非缓冲：`ai_sect_recruit.h:324/344`（返回 `vector<Disciple>`，29 个 string 整体拷贝）、`ai_sect_ops.h:471`（**每月**拷贝每个 AI 弟子）、`ai_sect_ops.h:505`（每弟子一棵 map）、`appointment_tx.h:286/314`（每次调用从 static DB 重建 trait 池）、`battle_calculator.h:628`（每战斗步） | 结构性拷贝，非 NRVO 可省 | A |
| R31 | `sparse_.resize(index + 1u, kInvalid)`（`storage.h:151`）在每次新实体索引出现时重入；按历史最大索引而非存活数定尺 | ECS 特有 | A |

### D-5 生命周期过长 / 场景卸载不完整

| # | 风险 | 证据 | 等级 |
|---|---|---|---|
| R32 | **本项目没有"场景卸载"这件事**：`SectMapViewport` 常驻 `Box`，其他屏是覆盖层/Dialog；切宗门只改 `activeSectId` 并替换地形数组，地图继续在底层 60fps 绘制 | `MainGameScreen.kt:974-979,1010-1016`；`GameEngineLifecycleOps.kt:230-266` | A |
| R33 | `g_scene`、4 个 batcher、`decorItems` 等**函数级 `static std::vector`（`scene_draw.h:295-299`）跨纪元保容量** | `scene_draw.h:234,295-304` | A |
| R34 | `SceneStore::reset()` 用 `clear()` → **纪元复位也不还内存**（`scene_store.h:250-264`） | `std::vector::clear` 语义 | A |
| R35 | `GameCore` 及其全部容器只在 `nativeDestroy` 释放；`GameActivity.onDestroy`（`:1076-1112`）**不调渲染器 teardown 也明确拒绝关引擎** | `GameCoreBridge.cpp:332,349`；`GameActivity.kt:1108-1111` | A |
| R36 | 265 个 Kotlin `object` 单例 + 永久缓存（`ChangelogData.kt:26 cachedEntries`、`ManualDatabase.kt:578 _allManuals`） | 已证两处；其余未穷举 | A（部分） |
| R37 | **`clearAll()` 销毁 storage 内容但不销毁 storage 本身**（`registry.h:74-79`）→ `unordered_map<TypeId, unique_ptr<IStorage>>` 的桶与每个 storage 的容量都留着 | `registry.h:82` | A |
| R38 | `GameEngineCoreHandOps3.kt:121-127` 的 `leakedGameThreadCount`（`GameEngineCore.kt:352`）存在，即官方承认旧引擎线程可滞留，每个 1 MB 栈 + job 图 | 已读 | A |

### D-6 平台层复制

| # | 风险 | 证据 | 等级 |
|---|---|---|---|
| R39 | **`NativeBridge.cpp:596-599` 的注释立下规矩**："jbyteArray 通道被禁用，`GetByteArrayElements` 在 2048² 图集上会再产 16MB 副本 → 低端机 OOM"；而 `:725` 恰恰用 `GetByteArrayElements` 走**全项目最大的一份 22.37 MB** 载荷。规矩与实现相互矛盾（同一文件内） | A-51、B-1 | A（矛盾本身）/B（副本存在） |
| R40 | `sceneSet*/sceneUpdate*` 每个导入端口都是 `std::vector<T> tmp(n)` + `GetIntArrayRegion` + `assign` = **两次完整拷贝** | `:1027, 1052, 1077, 1097, 1122, 1147, 1216` | A |
| R41 | `nativeSetGameData`：`String`(UTF-16 2.26 MB) → `GetStringUTFChars`(1.13 MB) → `std::string payload`(1.13 MB) → 再注入进 static DB 容器 | `GameDataNativeBridge.kt:72`、`GameCoreBridge.cpp:948-951` | A |
| R42 | `jbytesToString` 未检查 null（OOM 时 UB） | `GameCoreBridge.cpp:246-250` | A |
| R43 | 两个大载荷 release 点无 RAII 守护（异常即跳 Release → 副本泄漏 + 数组持续 pinned） | `NativeBridge.cpp:725/743`、`:1446/1451`；`gamecore/CMakeLists.txt:56-58` 证明异常未禁用 | B（可否抛出未证） |
| R44 | Room 侧 ≥10 个无 `LIMIT` 的 `List<T>` 查询，**未引入 Paging**；游标→对象图物化 | `DiscipleDao.kt:32,35,38,49`、`ChangeLogDao.kt:24,50,53` | A（查询）/未证（行数） |
| R45 | **`largeHeap="true"` + `CacheLayer` 预算 100 MB + 22 MB ByteArray 常驻**三者叠加，等于主动抬高 ART 堆上限来容纳体积型资产 | `AndroidManifest.xml:86`、`CacheConfig.kt:123` | A |

### D-7 已勘察但**属正常常驻**（不是缺陷）

| 项 | 量级 | 为什么是设计上常驻 |
|---|---|---|
| static 内容 DB（~80 个 Meyers static） | 由表行数定，不可增长 | 只读模板表 |
| `g_scene` SceneStore | ≈140 KB | 场景真相源，设计上单份 |
| SPIR-V `.rodata` | 8,572 B | 编译期内嵌 |
| UV / sprite 常量表 | ≈2.7 KB | `inline constexpr` |
| `FloatTextPool` | 256 槽固定 | 设计上封顶 |
| `g_gameCore` | 一个对象 | 单例，`nativeDestroy` 释放 |
| 日志 | 0 | 直通 `__android_log_print`，无缓冲 |
| 天空背景 | 0 纹理 | 片元解析式渐变（`Rhi.h:79-94`） |

---

## 9. 最终十问

### 1｜当前项目到底有没有自己的内存管理系统？

**没有。**

CPU 侧无 allocator、无 arena、无 pool、无 PMR、无 `operator new` 重载、无 `mallopt`；GPU 侧无 VMA、无子分配器、无 block 管理。唯一"像系统"的东西是三个**容量复用纪律**：

- `SpriteBatcher` 跨帧保留堆缓冲（`SpriteBatcher.cpp:63-66`）
- Vulkan 三缓冲持久映射 VBO（`VulkanBackend.cpp:1744-1787`）
- staging 单例复用（`:1855`）

它们是**缓冲复用**，不是内存管理。

**目前主要依赖 = STL + NDK 默认分配器 + 裸 `vkAllocateMemory`。**

### 2｜当前项目主要使用哪些分配方式？

按体量排序：

1. `vkAllocateMemory`（一对象一分配，14 次）
2. `std::vector::push_back / assign` 倍增
3. `nlohmann::json` DOM 节点（`std::map<string,json>` + key string）
4. 平台：ART 堆、`allocateDirect`、`Bitmap`、`glBufferData` 驱动侧
5. JNI 数组/字符串编组（`NewByteArray` / `GetByteArrayElements`）
6. 函数级 `static` 容器（Meyers，~80 个）
7. 唯一裸 `new[]`（`column_dirty.h:663`）
8. 少量 `new`（backend ×3 站点、`g_atlas`、`g_gameCore`、`JobSystem`）

**Object Pool / Arena / Slab / Frame Allocator 均不存在。**

### 3｜当前项目最主要的内存来源是什么？

| 排名 | 来源 | 体量（实测/推导） | 归属 |
|---|---|---|---|
| ① | 自管 `VkDeviceMemory`：VBO 11.8 + staging 22.4 + 图集 22.4 + 崖壁 39.5 MB | **≈96 MB**（14 次分配，不含驱动私有的 swapchain/pipeline cache） | GPU/host-visible |
| ② | 常驻 JSON 全状态树 `baselineJson_`（+ `restBaseline_`） | **数十 MB 量级**（B-4 估算） | native heap |
| ③ | UI webp 按需解码位图 | 磁盘 **437 MB / 708 文件**，最大单张解码 **37.68 MB** | graphics native 池（B-10） |
| ④ | `DiscipleStore` 112 列 + 每行约 30 个可分配单元 | **B-5 估算 10–20 MB @ 5000 弟子** | native heap |
| ⑤ | 4 个 `static SpriteBatcher` + SceneStore + 内容 DB | ~15.7 MB + 0.14 MB + 5–9 MB | native heap |

### 4｜CPU 内存和 GPU 内存分别由谁管理？

**CPU**：**NDK 默认分配器**（Scudo）独占，Kotlin 堆那一份由 ART 管，Bitmap / `allocateDirect` 那一份由 `NativeAllocationRegistry` 管——**后者既不受 Java 堆上限约束，也不受本项目控制**。

**GPU**：**`VulkanBackend` 自己逐对象 `vkAllocateMemory`**（14 个分配：3 VBO + 1 staging + 1 白纹理 + 1 图集 + 7 崖壁 + 1 地面，默认配置下 offscreen 为 0），**无池化、无子分配、无引用计数**。swapchain 图像与 pipeline cache 由驱动管；GLES 机型上全部 GL 对象生命周期由驱动管，本项目只持 `GLuint`。

### 5｜场景切换时内存如何变化？

**几乎不变。**

切宗门 = Kotlin 侧 `ConcurrentHashMap` 里多一条 65 KB 条目 + JNI 侧一次 65 KB 双拷贝 `assign`（复用旧容量）+ **零次 GPU 操作**。

旧宗门的地图、图集、渲染器、`g_scene` 容量、4 个 batcher 缓冲**全部原样驻留**，且**旧宗门数据没有任何移除路径**。

真正改变内存的是：

- **surface 纪元更替**（`initSurface:277-281` 检测 stale → `destroySurfaceGeneration` 一次性释放全部纹理 + VBO + 重分配）
- **resize**（`:1041-1096` 重建 swapchain/offscreen/pipeline，**纹理与 VBO 不动**）

**Scene A → Scene B 之后**：Scene A 的 CPU 内存 = 全部保留（`sectMapCache` 里那条 65 KB + `pushedTerrain` 的额外引用）；Scene A 的 GPU 内存 = **无变化**（因为 GPU 上根本没有 per-scene 资源）；Scene A 的资源**仍被 `sectMapCache` 强引用**。

### 6｜纹理加载后到底存在几份数据？

ASTC 主路径峰值 **4 份 / 稳态 2 份**：

- **稳态**：GPU DEVICE_LOCAL（22,369,616 B）+ HOST_VISIBLE staging 高水位（22,369,660 B，跨纪元不还）
- **峰值**：再加 Java ByteArray 22,369,724 B 与 JNI 侧副本 22,369,724 B

**不存在解码后的 RGBA8 中间态**（native 无解码器，KTX 已是 GPU-native 块布局，`KtxLoader.cpp:121` 返回指回调用缓冲的指针）。

图片加载完成后 **CPU 侧数据不主动释放**：Kotlin ByteArray 靠 GC（无 `System.gc()`），staging 永久保留高水位。

GLES / 软渲回退路径分别多一份 `vector<uint8_t>` 堆副本（16.7 MB）和一份不 `recycle()` 的 16.7 MB Bitmap。

### 7｜每帧是否发生动态内存分配？

| 侧 | 结论 |
|---|---|
| **Vulkan C++** | **基本没有**。`endFrame` 是空函数，`submitFrame` 零 `vkCreate*`；唯一增长面是 `m_pendingDraws.push_back`，且 `clear()` 保容量 → 稳态零分配 |
| **GLES** | **有，且很大**——每帧整批 `glBufferData` 令驱动重分配顶点存储（至 3.93 MB） |
| **Kotlin** | **有稳定的短命对象流**（`SceneUpdateInputs` / `BuildingDataSnapshot` / `FramePacing` / `RenderFrame` 等，每帧数个）→ 是垃圾生成率而非泄漏 |
| **引擎 tick (10 Hz)** | `NewLongArray(17)` + 2×`IntArray(5)` + `NativeLoopPlan` + `LoopIterationState` |
| **每旬** | `JobSystem` 的 `std::function`/`deque` 分配 + `stateWithoutDisciplesToJson` 全量 DOM + `dump()` + `jbyteArray` |

**没有 frame arena、没有临时对象池、没有 ring buffer。** 有的只是：GPU 侧真三缓冲（`MAX_FRAMES_IN_FLIGHT=3`）+ `SpriteBatcher` 512 顶点内联缓冲 + 4 个跨帧复用的 static 批处理器。

### 8｜是否存在明显的内存泄漏或异常增长点？

**异常增长点**（按危害排序）：

1. **R1 纹理 append-only**（因 `destroyTexture` 零调用方）
2. **R5 常驻 JSON 基线树 + diff 期双份**
3. **R2 / R26 / R33 / R34 一系列"保容量不还"的高水位**
4. **R4 `sectMapCache`**
5. **R6 账本无界**
6. **R3 `VkInstance` 失败路径**

**明确缺陷**：R24 的 O(N²) 扩容（存档加载即触发）、R42 的 null 未检查、R32 的场景不卸载、R39 的注释规矩被自己最大的载荷违反。

**不算泄漏、属设计常驻**：static 内容 DB、`g_scene`、SPIR-V `.rodata`、UV 表、`FloatTextPool`、`g_gameCore`。

### 9｜当前项目是否存在明显的内存碎片风险？

**有实证（不是"可能有"）：**

- **同生命周期对象被人为打散**：一个弟子行 = 112 个独立倍增堆块 + 每行约 30 个可分配单元（18 `string` 列 + 9 `vector<string>` 列 + 2 个 `map` 列 + 1 个 `vector<StorageBagItem>` 列）
- **三种生命周期在同一堆交错**（进程终身 static / 纪元级数十 MB / 帧-旬级临时）
- **唯一的裸 `new[]` 恰好在 16 B 粒度上反复申请释放**（R24，加载存档即触发）
- **4 个 3.93 MB 级 batcher 大块与临时 JSON DOM 同堆**
- **全仓 0 处 `shrink_to_fit` + 0 处 `mallopt`** ⇒ 碎片一旦形成，既无内部收缩手段，也无把空闲页交还 OS 的手段

**当前无缓解机制。**

### 10｜当前项目真实内存架构图

见 [§2](#2-当前项目真实内存架构图)。文字概括本项目内存的**唯一真实形状**：

> **进程内存 = 一个 ART 管辖的 Java 堆（里面睡着一张 22 MB 的图集 ByteArray）+ 一个 NDK 分配器管辖的 native 堆（112 个 vector 列 + 两棵常驻 JSON DOM + 4 个 3.9 MB 批缓冲 + 若干进程终身 static）+ 一个 `VulkanBackend` 用 14 次 `vkAllocateMemory` 逐对象圈出的 96 MB 设备内存（其中一张纹理一旦上传就只能等整个 surface 死去）**，三层之间靠 `GetByteArrayElements` / `GetStringUTFChars` 的整份拷贝和 direct ByteBuffer 的裸地址缝合，**没有任何一层拥有跨层的所有权或回收契约**。

---

## 10. 取证纪律自证：我亲自复核了什么 / 子代理矛盾裁决

### 10.1 我独立 grep / Read 复核过（未采信子代理原文）

`destroyTexture` 全仓 16 处引用逐条定性、`uploadTexture*` 全部 Kotlin 调用点、atlas 与 edge 磁盘字节数 + KTX 实测维度、`ensureStagingBuffer` 全体（`:1845-1915`）、VBO 尺寸表达式 `*2` / `/2`、`freeTextureResources` + `destroyTexture` + `submitFrame` 排空段、两处纹理内存类型回退循环、`createVertexBuffer` 全部调用点 + `resize` 全体（`:1041-1096`）、`m_pendingDraws` 全部 6 处引用、descriptor pool `capacity()`、`shutdown` 早退 + `initDevice` + `vkCreateInstance` 位置 + 析构、`initSurface` stale-generation 守卫（`:264-320`）、`destroySurfaceGeneration` 全体（`:352-420`）、纹理上传 `fail:` 清理标签（两处）、`KtxLoader.cpp:121` 零拷贝、`uploadCompressedAtlas` 全体 + `lockDirectPixels` + 596-599 注释、`SpriteBatcher.cpp` 全文 + `.h:19-20`、`NativeBridge` 全部文件级 static、`VulkanBackend.h` Texture/Retired/DrawCommand/staging 成员、`ensureRowCapacity`+`setBit`+`resetBaseline`+`clearRowBits`+`markRowAllColumns`+全部标脏调用点、`disciple_store.cpp:160,290`、`gamecore/CMakeLists.txt` 源码列表与 test 门、`JobSystem` 实例化、`normalizeLedgers` 唯一调用点 + `mailRecords` 在 C++ 的全部引用、`dirty_tracker` `baselineJson_` / `stateToJson` / `diffToTree` / `kEntityCollections`、`GameCoreBridge` `jbytesToString` / `stringToJbytes`、`SectMapController` 2 处引用、`GameActivity` onTrimMemory/onLowMemory 全文（`:1114-1142`）、`CacheLayer.kt:215`、`AndroidManifest` 关键属性、`GlesBackend` `uploadTexture`/`destroyTexture`/`drainUploads`/`submitFrame`、`AtlasAsyncPipeline.start` 重试体（`:60-90`）、`VulkanRenderBackend.renderFrame` 全体（`:75-160`）、`createOffscreenTargets` / `createSwapchain` / `m_renderScale` 默认值、WebP 头解析（自写脚本）与 md5 查重、`atlas-manifest.json` 与 `atlas-rgba-manifest.json` 字段、生成的 `TextureAtlas.h` 的 `ATLAS_W/ATLAS_H`。

### 10.2 子代理结论被我修正 / 否决

| # | 子代理原论断 | 我的裁决 | 依据 |
|---|---|---|---|
| 1 | "每次重传都泄漏，因为 `freeTextureResources` 不可达" | **只对一半**。修正为：**跨纪元不泄漏**（新代表面前 `destroySurfaceGeneration:372-378` 一次性批量释放），**只有同一纪元内的重复上传才真的累积** | 亲自读 `initSurface:277-281` + `destroySurfaceGeneration:352-420` |
| 2 | `column_dirty` 扩容累计 memcpy ≈ 400 MB（N=5000） | 偏高约 2×。按 `16·i` 逐次搬运重算为 **≈200 MB**（`16 × N(N+1)/2`），并核对 `disciple_store.cpp:160` 的 `row = ids.size()` 确认单调递增前提成立 | 算术 + 源码 |
| 3 | "`GameCoreJni.cpp` 的 `g_core`/`g_rng`/`g_monitor` 永不释放是生产风险"（并自标 NOT PROVEN） | **否决**。`game-core` 静态库源列表只含 12 个 `.cpp`，`jni/` 与 `test/` 都不在内（`GAMECORE_BUILD_TESTS` 默认 OFF）→ 桌面对拍桥 TU，不编进 `.so` | `gamecore/CMakeLists.txt:26-45,75-87` |
| 4 | "`android:graphics.renderer=skiagl` 影响 GPU 分配形状" | **降为 C**。属性本身 A 级可见（`:96-99`），但"影响分配形状"是解释不是证据 | 无代码支撑 |
| 5 | offscreen 3 个 `VkDeviceMemory` 计入常驻成本 | **修正**。`m_renderScale` 默认 `1.0`（`.h:373`）时 `createOffscreenTargets:863-867` 直接 `return true` **不分配**；仅在降采样开启时存在 | 亲自读 `:860-882` |
| 6 | `atlas-rgba-mips.bin` 的 mmap 视图"~0 堆" | **接受其"不占进程堆"，但 RSS 归属未证**，归入 C（page cache 可被回收，也可在压力下常驻） | `FileChannel.map` 语义 |
| 7 | "ASTC 失败回退会再上传一份、旧的还挂着" | **否决**。两处 `fail:` 标签完整释放已建对象后返回 0 → 回退不泄漏纹理；**但 staging 若已扩容则永久保留高水位**（升为 R2 的一部分） | 亲自读 `:2182-2189`、`:2555-2562` |
| 8 | "`NativeBridge.cpp:1446 drawIslandCliffs` 端口可能已死" | **保留为 C**（见 §7 第 9 条），未升为事实也未否决 | 未在 `renderFrame` 内找到调用，但也未穷举 |

### 10.3 降为 B / C 存疑（未升为事实）

所有 nlohmann 膨胀系数、per-disciple 字节、`sizeof(Disciple)`、弟子真实数量、`GetByteArrayElements` 是否拷贝、驱动 `memReq.size` 实际值、Room 查询行数、Compose webp 解码归属、GLES 双份存活频率、跨模块重名 webp 的资源合并胜出者。

---

## 附录 A. 复现命令（本次实际使用的取证命令）

```bash
# 技术栈
git ls-files | sed 's/.*\.//' | sort | uniq -c | sort -rn | head -40
git ls-files '*.cpp' '*.cc' '*.h' '*.cmake' '*.vert' '*.frag' '*.spv' '*.ktx' \
  | sed 's|/[^/]*$||' | sort | uniq -c | sort -rn
git ls-files | grep -iE '\.(mm|m|metal)$|metal|ios|apple'

# 分配原语
grep -rn --include=*.cpp --include=*.h --include=*.cc \
  -E '\bnew\b|\bmalloc\b|\bcalloc\b|\brealloc\b|operator new|posix_memalign|aligned_alloc' \
  android/app/src/main/cpp/gamecore/include android/app/src/main/cpp/gamecore/src

# 自定义分配器（否定式结论的取证）
grep -rnE 'Allocator|MemoryManager|Arena|Slab|ObjectPool|std::pmr|alignas|mallopt|M_TRIM|M_PURGE' \
  android/app/src/main/cpp --include=*.cpp --include=*.h \
  | grep -vE 'build/|third_party/googletest'

# GPU 内存
grep -n 'vkAllocateMemory\|VkDeviceMemory\|VmaAllocator\|vmaCreate\|vkMapMemory\|vkUnmapMemory\|allocationSize' \
  android/app/src/main/cpp/VulkanBackend.cpp
grep -nE 'glGen[A-Z]\w+|glTexImage2D|glTexStorage2D|glBuffer(Data|Storage)|glGenerateMipmap|eglCreate\w+' \
  android/app/src/main/cpp/GlesBackend.cpp

# 承重否定式结论
git grep -n destroyTexture -- '*.kt' '*.java' '*.cpp' '*.h'
git grep -n sectMapCache
grep -rn 'shared_ptr\|weak_ptr\|enable_shared_from_this' android/app/src/main/cpp \
  --include=*.cpp --include=*.h | grep -vE 'build/|third_party|test'
grep -rn 'shrink_to_fit\|make_shared' android/app/src/main/cpp/gamecore/{include,src}
grep -rn 'AAsset\|stbi_load\|libwebp\|WebPDecode\|libjpeg' android/app/src/main/cpp
grep -rn 'GetPrimitiveArrayCritical\|JNI_OnUnload\|coil\|PagingSource\|DataStore' android/
grep -rn 'android:process' android/app/src/main/AndroidManifest.xml

# 资产实测
ls -la android/app/src/main/assets/atlas/ android/app/src/main/assets/atlas/edge/
du -sk android/app/src/main/assets/ android/app/src/main/res/drawable-nodpi/ \
       android/feature/game/src/main/res/drawable-nodpi
md5sum android/app/src/main/res/drawable-nodpi/bg_horizontal.webp \
       android/feature/game/src/main/res/drawable-nodpi/bg_horizontal.webp
head -c 900 android/app/src/main/assets/atlas/atlas-manifest.json
grep -n 'ATLAS_W\|ATLAS_H' android/app/build/generated/sprite/TextureAtlas.h
```

**WebP 解码尺寸计算**（自写脚本，读 RIFF/VP8X/VP8L/VP8 头，708 文件全覆盖）：VP8L 691 个、VP8X 15 个、VP8-lossy 2 个；最大解码体量为 `bg_horizontal.webp` 4096×2300 → 37.68 MB RGBA8；72 个文件解码后 >8 MB。

---

## 附录 B. 关键常量实测值

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_SPRITES_PER_FRAME` | 20480 | `Rhi.h:51` |
| `VERTICES_PER_SPRITE` | 6 | `Rhi.h:52` |
| `MAX_VERTICES` | 122,880 | `Rhi.h:53` |
| `sizeof(SpriteVertex)` | 32 B（8 float，`alignas(4)`） | `Rhi.h:39-43` |
| `MAX_FRAMES_IN_FLIGHT` | 3（硬编码） | `VulkanBackend.h:208` |
| 每 VBO 实际尺寸 | 3,932,160 B | `:1737` = `.h:217 / 2` |
| `SpriteBatcher::STACK_CAPACITY` | 512 顶点 = 16 KB | `SpriteBatcher.h:19-20` |
| `kMaxCliffTextures` | 16 | `NativeBridge.cpp:101` |
| `kFloatPoolCapacity` | 256 | `float_text.h:38` |
| `kMaxObjectDecorItems` | 20000（× 36 B ≈ 720 KB） | `scene_draw.h:234` |
| `MAIL_RECORD_RETENTION` | 500 | `game_core.cpp:64` |
| 逻辑 tick 间隔 | 100 ms，≤5 步/帧 | `engine_loop.h:39,41,43` |
| 一旬时长 | 2000 ms @1×，补帧上限 3 旬/tick | `settlement.h:31,33,38-40` |
| 旬/月 | 3 | `time_system.h:20` |
| `kDiscipleColumnCount` / `kCount` | 109（目视计数，见 §7 第 13 条） | `column_dirty.h:34`、`disciple_store.h:153` |
| `kWordsPerRow` | 2 → 16 B/行 | `column_dirty.h:654-655` |
| `kBuildingStride / kCliffStride / kPreviewStride` | 5 / 10 / 16 | `scene_store.h:43-62` |
| 世界尺寸 | 128×128 格，tile 48 px | `GameConfig.kt:1012-1014` |
| 图集 | 4096×4096 ASTC_4x4_LDR，11 mip，42 sprite | `atlas-manifest.json` + KTX 头实测 |
| RGBA 备用图集 | 2048×2048 RGBA8888（源 4096，scale 0.5），11 mip，41 sprite | `atlas-rgba-manifest.json` |
