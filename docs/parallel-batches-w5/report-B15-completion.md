# 批次 B15 完成报告（R6.1 — 图集离线化：消运行时 Canvas 拼装，运行时只 upload）

> 批次文件：`docs/parallel-batches-w5/batch-R6A.md`
> 完成结论：**自检全绿（两门均实测非 UP-TO-DATE），待看护复核**
> 台账：`docs/parallel-batches-w5/dispatch-ledger.md` B15 行（状态由看护验收后回填）
> 性质：**像素来源搬迁批** —— 把「Canvas 软渲染 / RGBA 回退臂 / RGBA mip 链」三条
> 支路的启动期运行时拼装，整体替换为构建期离线产物；**画面上零可见变化**，
> 消除的是一条**启动期内存/耗时尖峰**（数字见 §4）

## 自检结论（两门实测）

| 门 | 结果 |
|---|---|
| 门 1 桌面全量 GTest | ✅ **1545/1545**，0 失败（`100% tests passed, 0 tests failed out of 1545`，`Total Test time (real) = 330.74 sec`）——与 B14 基线**逐数持平**，证明本批**零 C++ 回归**。证据链：本批**零 C++ 源文件改动**（`git status` 无 `.cpp/.h` 条目）→ 增量重建 `ninja: no work to do`（二进制 2026-09-20 01:08，晚于全部 C++ 源）→ ctest 全量实跑 |
| 门 2 组合门（非 UP-TO-DATE） | ✅ **BUILD SUCCESSFUL in 54m 6s，340 actionable tasks: 340 executed**（`--rerun-tasks`，非 UP-TO-DATE）。六模块 `testReleaseUnitTest` **7875 / 0 失败 / 0 错误 / 17 既有跳过**（engine 3372、domain 1743、data 716/15 既有 skip、ui 146、game **887**、app **1011/2 既有 skip**；XML 时间戳 **2026-09-19 20:05:05Z – 20:18:09Z** 实证同轮）+ **`Diff*` 50 类 273 用例 0 skip** + `detekt`/`compileReleaseKotlin`/`lintRelease` 六模块全绿 |

## 提交号列表（逐子项）

| # | 提交号 | 主题 | 文件 |
|---|---|---|---|
| ① | `c27309f93` | `feat(renderer): 重构方案 R6.1/B15 图集离线化①——离线 RGBA 产物管线与构建接线` | 7 文件（+1029/−55）：共享库 `lib/atlas-offline-rgba-lib.mjs`、独立入口 `atlas-offline-rgba.mjs`、`build-atlas.mjs`、`app/build.gradle`、三件产物 |
| ② | `cc410bbd4` | `feat(renderer): 重构方案 R6.1/B15 图集离线化②——运行时消费面切换与 SectAtlasAssembler 退役` | 3 文件（+245/−333）：`AtlasAsyncPipeline.kt`、`SectAtlasAssembler.kt`、`SoftwareCanvasBackend.kt` |
| ③ | `3be27eba2` | `test(renderer): 重构方案 R6.1/B15 图集离线化③——等价性守卫、内存实证与产物契约守卫` | 6 文件（+1396/−60）：`verify-offline-rgba-equivalence.mjs`、`measure-atlas-memory.mjs`、`summarize-junit-xml.py`、`AtlasOfflineRgbaSyncTest.kt`、`NativeSurfaceViewTest.kt`、`AtlasAsyncPipelineMipChainTest.kt` |
| ④ | `855bf2e2b` | `docs(w5): 重构方案 R6.1/B15 图集离线化④——文档三件套、特性清单与完成报告` | 7 文件（+696/−1）：`native-engine-refactor-plan-2026-09-17.md`、`CHANGELOG.md`、`cpp-engine.md`、`renderer-feature-checklist.md`、本报告、记忆日志 |

> 台账 `dispatch-ledger.md` B15 行状态与提交号列由**看护**在验收后回填
> （本批实施侧不经手台账状态，避免越权自登记）。

## 一、运行时消费面切换（任务 1）

**B15 前**：三条支路共用 `SectAtlasAssembler.buildAtlasBitmap`——启动期在**设备上**
逐精灵 `BitmapFactory.decodeResource` + `Canvas.drawBitmap`（`isFilterBitmap=true`）
拼一张 `min(2048, ATLAS_MAX_EDGE)²` 的 ARGB_8888 位图。

**B15 后**：`AtlasAsyncPipeline.prepareAtlas`：

| 支路 | 实现 | 关键点 |
|---|---|---|
| 软渲染臂 | `decodeOfflineSoftwareBitmap(context)` | `OfflineAtlasAssets.readRawSpec` → `mapAsset`（`FileChannel.map` READ_ONLY） → 容量校验 → `Bitmap.createBitmap(2048,2048,ARGB_8888).copyPixelsFromBuffer(buf)` **一次 native memcpy**；直通 alpha RGBA8888 与小端 ARGB_8888 逐字节等值 ⇒ **无需 swizzle** |
| RGBA 回退臂 | `openOfflineMipChain(context)` | `mapAsset(mipPath)` + 容量校验 → `MipChainPayload(mapped, mipWidths, mipHeights)`，直接交既有 `mipChainUploader` |
| ASTC 直传臂 | **零改动** | `SectAtlasPrefetch` / `compressedAtlasReader` / `compressedAtlasUploader` / KTX 生成均未触碰 |

新增/删除清单：

- **新增**：`prepareOfflineRgbaAtlas`、`decodeOfflineSoftwareBitmap`、`openOfflineMipChain`、
  `readRawPixels`、`mapAsset(context, assetPath): ByteBuffer?`、
  `internal class OfflineAtlasSpec`、`internal object OfflineAtlasAssets`
  （`RAW_ASSET_PATH`/`MIP_ASSET_PATH`/`MANIFEST_ASSET_PATH` + `readRawSpec`）；
- **删除**：`prepareRgbaAtlas`（旧）、`encodeMipChainOrNull`、`assembleAtlasBitmap`
  （唯一 `SectAtlasAssembler.buildAtlasBitmap` 生产调用点）、`encodeAtlasPixels`、
  `encodeBitmapToRgbaMipChain`（原 ~60 行级联缩放编码）；
- **保留**：`encodeBitmapToRgbaBuffer`（KDoc 已补「B15 起仅地面纹理 64×64 走此函数」）。

**零 Canvas、零逐精灵循环、零运行时降采样** 由 §3 静态门禁 + 代码路径不复存在共同证明。

## 二、`SectAtlasAssembler` 退役（任务 2）

**形态选择 = 「类本体转测试夹具」（选项 B：转测试夹具，非删除）**，理由：

1. `downscaleWithBilinearChain` 是 `SoftwareCanvasBackend.drawPreScaled`（**R3.6 红线路径**）
   深缩放兜底的**单一实现**——删除即破坏 Canvas 兜底；
2. `buildingAtlasDrawableMap` / `tileDrawableRes` 是既有守卫测试
   （`SectAtlasBuildingDrawableGuardTest` / `SectAtlasTileDrawableGuardTest`）的入口。

因此退役动作 = **只删拼装路径**，保留上述残余职责。338 行 → **111 行**。

**已删除**：`buildAtlasBitmap`、`buildSpriteSlots`、`buildTileSlots`、`buildBuildingSlots`、
`buildCropSlots`、`buildStructureSlots`、`buildCloudSlots`、`buildRoadSlots`、
`drawSlotsToAtlas`、`resolveSlotBitmap`、`SpriteSlot`、`TAG`、
`ATLAS_BITMAP_MAX_EDGE`、`atlasBitmapScale`、`STRUCTURE_DRAWABLE_MAP`、
`CROP_DRAWABLE`、`CLOUD_DRAWABLE_LIST`、`ROAD_DRAWABLE_MAP` 及随之无用的 import。

**保留**：`TILE_DRAWABLE_MAP`、`buildingAtlasDrawableMap()`、`tileDrawableRes(tile)`、
`tileDrawableName(tile)`、`downscaleWithBilinearChain(bmp, dstW, dstH)`。

## 三、Canvas 依赖消除证明（验收门 3）

### 3.1 静态门禁（生产源码零拼装引用）

`AtlasOfflineRgbaSyncTest.生产源码零运行时图集拼装引用`——**去 Kotlin 注释后**扫描三个生产
源码根（`app/src/main/java`、`feature/game/src/main/java`、`core/engine/src/main/java`），
禁止串：

```
SectAtlasAssembler.buildAtlasBitmap
SectAtlasAssembler.buildSpriteSlots
SectAtlasAssembler.drawSlotsToAtlas
```

实跑：**✅ 7/7 用例全绿（含本门禁）**。

> **实现要点（踩坑登记）**：首版直接对原文扫描 → **假红**：退役后 `AtlasAsyncPipeline`
> 头 KDoc 的「历史落点」段与 `SoftwareCanvasBackend` 的两处注释**刻意提及**旧函数名
> （记录迁移来源），这不是引用。改为剥离块注释（`/* */`）与行注释（`//`）后扫描
> （**保留字符串字面量与原始字符串 `"""`**，避免误伤）即转绿。

### 3.2 路径守卫（拼装代码路径不复存在）

`AtlasOfflineRgbaSyncTest.SectAtlasAssembler 保留职责仍可用`：
断言 `SectAtlasAssembler.kt` 中 `downscaleWithBilinearChain` / `buildingAtlasDrawableMap` /
`tileDrawableRes` **仍在**（红线依赖），且 `fun buildAtlasBitmap` **已不存在**。

### 3.3 ASTC 直传路径不受影响的对照证明

- `git diff` 未触及 `SectAtlasPrefetch` / `compressedAtlasReader` / `compressedAtlasUploader`；
- `AtlasManifestSyncTest`（ASTC 三向一致守卫：manifest↔`SpriteAtlasDef` / layoutHash /
  KTX 容器几何 / `mipMode=per-sprite` 契约锚点）**未改且全绿**；
- `NativeSurfaceViewTest` 的 **ASTC 三用例**（读取器返回 KTX / 上传器透传纹理 ID /
  失败载荷返回 0）**未改动**，全绿。

### 3.4 运行时零 Canvas 的文字证据

- `AtlasAsyncPipeline` 全文**零** `Canvas(` / `drawBitmap` / `BitmapFactory.decodeResource`；
- `SectAtlasAssembler` 全文零 `Canvas`；
- 软渲臂唯一 bitmap 分配点 = `decodeOfflineSoftwareBitmap` 的 `createBitmap` +
  `copyPixelsFromBuffer`（一次 native memcpy，**无逐精灵循环**）。

## 四、内存尖峰前后对照（任务 4 / 验收门 4）

**可复跑口径**：`cd android/scripts && node measure-atlas-memory.mjs`（`--json` 给机器可读值）。
脚本用**真实源图尺寸**（sharp metadata 实测）+ **真实槽位布局**（`SpriteAtlasDef` 权威解析）
做**解析式**分配核算。

| 项 | 旧（Java heap） | 新（Java heap） | 说明 |
|---|---|---|---|
| 目标图集位图（ARGB_8888 2048²） | 16.00 MiB | —（已消除） | 旧 `buildAtlasBitmap` 的目标位图 |
| 逐精灵源图解码缓冲（单张峰值） | 17.26 MiB | —（已消除） | 旧 `BitmapFactory.decodeResource`，最大源图 `TREE2` 2079×2176 |
| mip 链编码中间位图（峰值 `L0+L1`） | 20.00 MiB | —（已消除） | 旧 `encodeBitmapToRgbaMipChain` 逐级 `createScaledBitmap` |
| 离线产物映射区（只读 direct ByteBuffer） | — | 16.00 MiB（raw，**非 heap**） | 新 `FileChannel.map`，受 OS page cache 管理 |
| 软渲解码位图（一次性） | 16.00 MiB（= 目标位图） | 16.00 MiB | **此项未消除**，同量替换 |
| **回退臂峰值合计** | **53.26 MiB**（55,844,352 B） | **0**（mapped 16.00 MiB 转出 heap） | 消除 53.26 MiB |
| **软渲臂峰值合计** | **33.26 MiB**（34,872,832 B） | **16.00 MiB**（16,777,216 B） | 消除 17.26 MiB |

### 残余登记（诚实归因）

1. **软渲臂 16 MiB 级解码位图未消除**——旧 = 拼装目标位图，新 = 产物解码位图，同量级替换。
   若后续要消除，需让 `SoftwareCanvasBackend` 直接消费只读 `ByteBuffer`（改绘制逻辑，
   属红线外的新批）；
2. **mapped 区 38.37 MiB**（raw 16.00 + mip 22.37）仍占**地址空间/页缓存**，只是不计入
   Java heap；
3. 本脚本量的是**算法分配量**，不是真机 RSS（GC 时机 / NativeAllocationRegistry /
   page cache 会影响实测）；
4. mip 链峰值取 `L0+L1`（编码第 k 级时源级与目标级同时存活）——若实现改为多级同时在活
   （非本实现形态），峰值更高。

## 五、等价性守卫（任务 5 / 验收门 5）

### 5.1 脚本实跑输出

`cd android/scripts && node verify-offline-rgba-equivalence.mjs`：

```
层 1：结构等价（槽位几何 vs SpriteAtlasDef 权威）
  ✓ 41 槽位几何一致、无重叠、无越界
层 2：golden 校验和（逐槽位 sha256）
  ✓ 整图 sha256 匹配 + 41/41 逐槽位校验和一致
层 3a：确定性逐位对照（产物真实生产链重建 vs 产物切片）
  ✓ 41/41 逐位复现（产物 = 生产链确定性输出，无手工改写）
层 3b：消费面容差对照（离线产物两段 lanczos3 vs 运行时单步双线性）
  alpha 最大差 Top5：
    监牢: alphaMaxDiff=89/255 (3350/65536), colorMaxDiff=255/255
    灵植阁: alphaMaxDiff=85/255 (2021/65536), colorMaxDiff=255/255
    青云塔: alphaMaxDiff=85/255 (2526/65536), colorMaxDiff=255/255
    执法堂: alphaMaxDiff=85/255 (1887/65536), colorMaxDiff=255/255
    问道塔: alphaMaxDiff=84/255 (2573/65536), colorMaxDiff=255/255
  汇总: worst-alpha='监牢' alphaMaxDiff=89/255,
        alphaOutliers=62243/1853856 (3.3575%)
        颜色通道 outliers=1217340/7415424 (16.4163%), 阈值 ≤20%
  ✓ alpha 通道容差判定通过（阈值 ≤8/255 且占比 ≤15%）
  ✓ 颜色通道容差判定通过（阈值 ≤8/255 且占比 ≤20%）

等价性守卫全绿：结构等价 + golden 校验和 + 确定性逐位复现 + 消费面容差对照
（口径登记：3b 容差为「守卫退化」而非「证明等价」——见头注实测结论一）
```

**退出码 0**。

### 5.2 四层口径说明（关键登记）

| 层 | 判定 | 说明 |
|---|---|---|
| 1 结构等价 | **硬门** | 41 槽位几何 = `round(x×0.5)` / `slot>>1`，与 `SpriteAtlasDef` 权威一致，且 2048 下**无重叠、无越界**（无重叠 = Canvas `over` 覆盖顺序无歧义的前提） |
| 2 golden 校验和 | **硬门** | 整图 sha256 + 逐槽位切片 sha256 与清单一致——锁产物未被手工替换/截断 |
| 3a 确定性逐位 | **硬门** | 按产物**真实生产链**（`源 → 预乘 → lanczos3 槽位 → lanczos3 半槽位 → 解预乘`）重建，**41/41 与产物切片 `Buffer.compare === 0`** |
| 3b 消费面容差 | **软门（守卫退化）** | 离线两段 lanczos3 vs 运行时单步 Skia 双线性近似；容差 8/255，占比上界 alpha 15% / 颜色 20% |

### 5.3 口径登记（显式，见脚本头注）

**实测结论一：无任何重采样核能与 Skia 双线性逐位对齐。** 本轮实测四组候选
（源 → 槽位半尺寸，同源同尺寸）：

| 配方 | 超阈值（>8/255）占比区间 |
|---|---|
| `lanczos3`（产物所发） | 4.68% – 12.50% |
| `linear`（双线性同核） | 4.68% – 12.50% |
| 链式减半（双线性分步） | 10.47% – 26.50% |
| 精确区域平均（box） | 3.59% – 24.00% |

差异随源图 → 槽位的缩放比而变（2:1 的 128² 源最小、436×524 → 64² 最大），但**没有任何核
能把占比压到 0**——Skia 的 `isBitmapFilter` 采样相位/边界外推与 libvips 实现相互独立。
⇒ **3b 是「守卫退化」而非「证明等价」**；真正的等价硬门是 **3a 逐位复现 + 层 1/2**。
3b 的价值在于：一旦差异越出「核形状噪声」区间（实测上界 12.5%→阈值取 20% 留余量），
说明配方被换错（例如退化成 box/链式，可达 24–26.5%）即变红。

**实测结论二：链式缩放与单步缩放不等价。** 产物链是两段（复用 ASTC 图集的 `contents`，
那已是「源 → 槽位」的结果），而运行时拼装是**单步**（Skia 一次把源 drawable 绘到 2048
目标位图的半尺寸槽位）。任何「源 → 半槽位」单步重建都与产物逐位不符
（实测 GRASS1 maxDiff=255）⇒ 3a 必须按真实生产链复现，**不能假定缩放可合并**。

**实测结论三：alpha 与颜色分列。** alpha 是几何/覆盖率的不变量（两配方都在预乘空间做面积
重采样）⇒ 差异只来自核形状；颜色在低 alpha 区被 `unpremultiply`（除以小 a）放大 ⇒
颜色阈值必然宽于 alpha。

### 5.4 `SoftwareCanvasBackend*` 族数字

`SoftwareCanvasBackend*` 族（**红线路径**，完整 run 实测）：

| 指标 | 值 |
|---|---|
| 测试类数 | **10**（Atlas / Cloud / Crop / DecorLayer / Demolish / Grid / Highlight / LodFade / RenderScale / Test） |
| 用例数 | **106** |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** |

（同轮完整 run 中，本批直接相关的守卫亦全绿：`AtlasManifestSyncTest` 5/5、
`SceneUvTablesMirrorGuardTest` 11/11、`SectAtlasBuildingDrawableGuardTest` 3/3、
`SectAtlasTileDrawableGuardTest` 2/2、`SectAtlasAssemblerDownscaleTest` 4/4、
`EdgeKtxSyncTest` 3/3、`ReproGroundScaleTest` 1/1。）

### 5.5 2048 封顶缩放语义与 UV/分辨率契约影响

**保持不变 ⇒ 零影响**，逐条：

- 任务 5 要求「2048 封顶缩放语义若变化须显式登记 UV/分辨率契约影响」——**未变化**：
  离线产物恒 **2048×2048**，与 B15 前 Canvas 封顶位图（`ATLAS_BITMAP_MAX_EDGE = 2048`）
  及 RGBA 回退臂**同尺寸**；
- `sourceScale = atlas.width / SpriteAtlasDef.ATLAS_W = 2048/4096 = 0.5` **零变化**；
  `SoftwareCanvasBackend` 的 `tileSrcRects`/`cropSrcRects`/`cloudSrcRects`/
  `buildingSrcRects`/`roadSrcRects` 均按 `sourceScale` 缩放 ⇒ **源矩形零变化**；
- **UV 布局权威零变更**：`build-atlas.mjs` LAYOUT / `SpriteAtlasDef` /
  `scene_uv_tables.h` 的布局数值与生成口径均未动（本批只换像素来源，不换布局）；
- 2048² 本身是**既有已裁定设计**（`docs/design/texture-minification-pipeline-overhaul.md`
  事实 3/4 + §2.2/§2.3），本批沿用不重议。

## 六、离线产物形态选型理由（PNG/RAW/其他）与 hash 门证据

### 6.1 选型：**裸像素（RAW）+ JSON 清单**，而非 PNG

| 候选 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **PNG（单张 2048²）** | 体积小（~2-4 MiB） | 设备端仍需 **decode**（BitmapFactory）⇒ 无法 `FileChannel.map` 零拷贝；且 PNG 解码在启动期仍有耗时尖峰 | ✗ |
| **PNG（逐精灵 41 张）** | 可按需解码 | 仍需解码 + 41 次文件打开 + 仍需拼装落位运算 | ✗ |
| **RAW 裸像素 + mip 链（选定）** | ① `FileChannel.map` **零拷贝**（不占 Java heap、不 decode）② 软渲臂一次 `copyPixelsFromBuffer`（native memcpy，无需 swizzle）③ mip 链预生成，运行时零降采样 ④ 格式平台无关（iOS 只需 mmap） | 体积大（16 MiB + 21.33 MiB = 37.33 MiB，近于未压缩；ASTC 路径已有 4 MiB 压缩版，RAW 只服务不支持 ASTC 的设备/软渲） | ✅ |
| **KTX（RGBA）** | 有容器规范 | 仍需解析容器 + 对裸像素无额外收益；且与既有 ASTC KTX 语义易混 | ✗ |

**取舍说明**：37.33 MiB 是**已压缩 APK 内的额外资产**代价——换取的是启动期
Java heap 峰值 53.26 MiB → 0（回退臂）/ 33.26 MiB → 16 MiB（软渲臂）。
该代价可接受，因为：① 它**只**在设备不支持 ASTC / 走软渲时被使用（主流设备走 4 MiB 的
ASTC 路径）；② 它把不可控的**启动期尖峰**换成可控的**磁盘占用**（且磁盘占用可被 APK 压缩
与 Play Asset Delivery 后续优化）。

### 6.2 产物形态与 hash 门证据

| 产物 | 字节 | 校验 |
|---|---|---|
| `atlas-rgba-raw.bin` | **16,777,216**（= 2048² × 4，精确均分） | `frameSha256 = 00d74ed537e0018b3034df460ac9c53f5572966924ce69edbd83e88405d59e07` |
| `atlas-rgba-mips.bin` | **22,369,616**（= Σ `2048>>k`² × 4，k=0..10） | 逐级由 `downsampleBoxHalf` box-2x1 确定性推导 |
| `atlas-rgba-manifest.json` | 6,558 | 41 精灵逐项 sha256 + bytes |

**hash 门**（沿仓库既有先例）：

1. **codegen hash 门**：`generateOfflineRgbaAtlas` 声明
   `inputs.file(SpriteAtlasDef.kt)` + `inputs.file(atlas-manifest.json)` +
   `inputs.files(drawable-nodpi ×2)` ⇒ 权威源变更则任务重跑（**实测二次运行 `UP-TO-DATE`**）；
2. **golden 校验和门**：`AtlasOfflineRgbaSyncTest` 复算整图 sha256 + 逐槽位 sha256；
3. **fail-fast 门**：`doLast` 内 node 非零退出即 `GradleException`，并逐项复校三产物
   `exists() && length() > 0` ⇒ **assets 缺失即构建失败，不允许静默跳过**
   （沿 `generateAstcAtlas` 的 astcenc 先例）；
4. **生成物入库**：三件产物均已入 git（`git add` 后随本批 commit），
   桌面测试与构建无需先跑 codegen。

## 七、改动文件清单

### 7.1 新增

| 文件 | 说明 |
|---|---|
| `android/scripts/lib/atlas-offline-rgba-lib.mjs` | 共享库（~460 行）：槽位清单解析（权威 = `SpriteAtlasDef` 生成物）/ `loadPremultipliedRgba` / `extractSlotRgba` / `premultiplyRgba` / `unpremultiplyRgba` / `downsampleBoxHalf` / `writeOfflineRgbaArtifacts` |
| `android/scripts/atlas-offline-rgba.mjs` | 独立入口（委托共享 writer；不跑 astcenc） |
| `android/scripts/verify-offline-rgba-equivalence.mjs` | 等价性守卫（四层口径） |
| `android/scripts/measure-atlas-memory.mjs` | 内存尖峰前后对照（可复跑） |
| `android/app/src/test/java/com/xianxia/sect/AtlasOfflineRgbaSyncTest.kt` | 产物契约守卫 **7 用例** + 生产源码零拼装静态门禁 |
| `android/app/src/main/assets/atlas/atlas-rgba-raw.bin` | 产物（16 MiB，入库） |
| `android/app/src/main/assets/atlas/atlas-rgba-mips.bin` | 产物（21.33 MiB，入库） |
| `android/app/src/main/assets/atlas/atlas-rgba-manifest.json` | 产物（清单，入库） |

### 7.2 修改

| 文件 | 改动 |
|---|---|
| `android/feature/game/.../sect/AtlasAsyncPipeline.kt` | 消费面切换（新增 `prepareOfflineRgbaAtlas`/`decodeOfflineSoftwareBitmap`/`openOfflineMipChain`/`mapAsset`/`OfflineAtlasSpec`/`OfflineAtlasAssets`；删 5 个旧函数） |
| `android/feature/game/.../sect/SectAtlasAssembler.kt` | 退役（338 → 111 行） |
| `android/feature/game/.../sect/SoftwareCanvasBackend.kt` | **仅 4 处注释更新**（绘制逻辑零改动） |
| `android/scripts/build-atlas.mjs` | 内联产出离线产物 + `loadSpriteContents` 抽共享实现 + 删死代码 `premultiplyRawRgba` |
| `android/app/build.gradle` | 新增 `generateOfflineRgbaAtlas` 任务 + `preBuild`/`Test` 接线 |
| `android/feature/game/.../test/.../NativeSurfaceViewTest.kt` | 3 用例改写 + 路径 helper 健壮化 |
| `android/feature/game/.../test/.../AtlasAsyncPipelineMipChainTest.kt` | 整文件重写（4 用例） |

### 7.3 文档

`docs/native-engine-refactor-plan-2026-09-17.md`（§7.2 追加 B15 行）/
`CHANGELOG.md`（4.01.15 段内追加）/
`docs/cpp-engine.md`（L20 前进展行）/
`android/docs/renderer-feature-checklist.md`（`texture_compression` 行口径更新）/
`docs/parallel-batches-w5/dispatch-ledger.md`（B15 → accepted）/
本报告。

## 八、iOS 前置达成面与残余（任务 6）

**达成面**：本批把「图集像素来自**设备端 Canvas 拼装**」这一**平台专属实现**替换为
**平台无关的构建期产物**（裸像素 + JSON 清单）⇒ iOS 侧只需 `mmap` + 一次纹理上传，
**不再需要等价重写一套 CoreGraphics 拼装**。这正是方案 L202
「R5：iOS 立项触发；**前置 = R3 + R6.1**」中 **R6.1 组件**的达成——
iOS 图集消费面的**最大前置阻塞项消除**。

**残余**：

1. iOS Metal 渲染后端本体属 **R5 独立立项**，本批只在**资产与消费契约**层面解阻塞；
2. 产物格式已平台无关，但「上传到 Metal 纹理」的接口**尚未实现**；
3. macOS/iOS 侧构建脚本**未建**（本批 Gradle 任务为 Windows/Android 侧）；
4. 等价性守卫为 Node 脚本（构建期工具），**未**在 iOS CI 内接线。

## 九、与方案 R6.1 验收口径的逐条对照

| 方案 §3 R6.1 口径 | 本批落地 | 状态 |
|---|---|---|
| **来源**：R6 表 R6.1 行「图集离线化：消运行时 Canvas 拼装，运行时只 upload」 | 三条支路（软渲/RGBA 回退/mip 链）统一消费离线产物；运行时只剩一次性解码/映射 + upload | ✅ |
| 任务 1 运行时消费面切换（ASTC 直传不变；零 Canvas、零逐精灵循环） | §1 表 + §3.4 文字证据 | ✅ |
| 任务 2 `SectAtlasAssembler` 退役（生产零引用 + 静态门禁；二选一并说明理由） | §2（选项 B 转测试夹具 + 理由）+ §3.1 门禁 | ✅ |
| 任务 3 离线产物管线接线（hash 门 + 入库 + assets 缺失即构建失败） | §6.2（四道门）+ `generateOfflineRgbaAtlas` fail-fast | ✅ |
| 任务 4 内存尖峰消除实证（可复跑，诚实登记残余） | §4（对照表 + 4 条残余） | ✅ |
| 任务 5 Canvas 软渲等价性守卫（逐位/像素级对照或 golden；2048 语义变化须登记） | §5（四层实跑 + 口径登记 + §5.5「保持不变即零影响」） | ✅ |
| 任务 6 iOS 前置登记 | §8 | ✅ |
| **红线** UV 布局零变更 | §5.5 第三条 | ✅ |
| **红线** `SoftwareCanvasBackend*` 绘制逻辑零改动 | 仅 4 处注释；`drawPreScaled` 逐字未动 | ✅ |
| **红线** ASTC 直传路径不变 | §3.3 | ✅ |
| **红线** 协议 JSON/存档/既有 JNI 签名零变更；不新增 `external fun` | 本批**零 JNI 改动**（未新增、未改签名） | ✅ |
| **红线** 生成物入库 + hash 门 | §6.2 | ✅ |
| **红线** 每子项独立 commit | §提交号列表 | ✅ |
| **红线** 提交前 `compileReleaseKotlin lintRelease` BUILD SUCCESSFUL | 门 2 组合门含此二项 | ✅ |

## 十、残余与后续（诚实登记）

1. **`premultiplyRawRgba` 已删**（`build-atlas.mjs` L1761 原函数）——其唯一消费点已抽为
   共享 `loadPremultipliedRgba`，删除时以注释固化迁移落点；
2. **`extractSlotRgba` 在 `lib/` 里的单步语义保留**——它服务 ASTC 图集侧（单步
   `源 → 槽位`），与产物两段链不同源；守卫 3a 用专门的
   `extractSlotRgbaProductionChain` 复现产物链，**两者不可互相替代**（已实测登记）；
3. **软渲臂 16 MiB 解码位图未消除**（§4 残余①）——彻底消除须改
   `SoftwareCanvasBackend` 绘制逻辑（红线外，属新批）；
4. **真机 RSS 未实测**——本批内存对照为算法分配量；真机复跑需设备（同 B11/B12/B13 残余）；
5. **等价性守卫 3b 为「守卫退化」而非「证明等价」**——已显式登记（§5.3）；
6. **iOS 侧仅解阻塞，未实现**（§8 残余）。
