# 渲染纹理降采样管线统一改版（独立 mip + gutter/padding + Canvas/RGBA 回退 + 道路边缘条修正）

> 决策分级：**架构级重构**（同模式问题 ≥3 处：道路 16:1× 深度降采样 / 灵田 10.7:1× /
> 整图 mip 渗色 / Canvas 点采样烘焙 / RGBA 无真 mip；触及未来 6 个月渲染与跨平台路径）。
> 前置事实：2026-09 已落地"道路 + 灵田槽位对齐瓦片标准"（`build-atlas.mjs` LAYOUT
> 128/32/96 + 拼装器链式双线性），本方案是其**推广根治**——把"逐处调槽位"升级为
> "永远正确的降采样管线"，并补齐三处遗留（Canvas 烘焙 / RGBA 回退 / 边缘条方向）。
> 生成日期：2026-09。对标：Unity Sprite Atlas（mipmap + paddingPower）、Godot 图集
> 导入（use_texture_padding）、项目内部 `docs/research/tile-map-industry-benchmark.md`
>（27+ 来源，S/A 级，§3.3 Texture Array「无 bleeding + 完美 mipmap」结论）。

---

## 一、背景与目标

### 1.1 现状事实（实勘证据，2026-09）

| # | 事实 | 证据 |
|---|------|------|
| 1 | 图集 mip 链由**整图**逐级 lanczos 下采样生成，精灵槽位之间**零隔离边（无 gutter/padding）** | `build-atlas.mjs generateMips()`（sharp resize TMP_PNG 整图）+ `buildAtlasPng()` 直接 composite 到相邻 rect（无间隔） |
| 2 | 采样器三线性 mip（mipmapMode=LINEAR）+ UV inset 仅 `0.5/4096`（mip0 半像素）——在 mip ≥1 级 inset 不足 0.5 texel，边界片元会采到**邻居槽位**内容（渗色） | `NativeBridge.cpp UV_EPSILON` + `VulkanBackend.cpp createSampler` |
| 3 | Canvas 软渲染 chunk 烘焙全部精灵（瓦片/装饰/建筑/道路）用 `isFilterBitmap=false`（**点采样**）降采样——建筑 256→96（2.67:1×）残留摩尔纹；道路已由拼装器缓解，烘焙段未修 | `SoftwareCanvasBackend.ChunkTile.rebuildPaint` + `drawBuildingsToCanvas` |
| 4 | RGBA 回退路径（无 ASTC 设备）上传**单层**纹理；三线性 sampler 钳制 level 0 → 实际纯双线性，无 mip，2.67:1× 起混叠闪烁 | `VulkanBackend.cpp uploadTextureImpl`（`mipLevels=1`）+ `AtlasAsyncPipeline.uploadRgbaAtlas` |
| 5 | 道路边缘条 `road_edge_v.webp` 深色边在纹理**右侧**；合成器左缘条（`emitV(0,...)`）直接采样 → 深色边落在道路**内侧**（不对称观感） | `road_edge_v.webp` 96×288（右缘深色带）+ `road_compositor.h emitV` |
| 6 | 小精灵槽位（瓦片/道路/灵田/作物 128、树 256）在缩放 0.3~3.0 内 mip 跨越 ≤1.6 级，且缩放上界 3.0 时 1×1 格显示 144px = 槽位 1.125× 放大（略软但一致） | `SpriteAtlasDef` 生成物 + `CameraState.MIN_ZOOM/MAX_ZOOM` |

### 1.2 需求要点

1. **mip 生成改为"每精灵独立"**（每张精灵单独下采样生成各自 mip 内容，再按缩小后的
   槽位合成各级 mip 层位图）——消灭 mip ≥1 级的邻居渗色（事实 1+2）。
2. **图集布局加 gutter + 每级 mip 边缘 padding**（槽位间距 ≥8px；每级槽位四周含
   pad 环，pad 内容 = 该精灵自身边缘像素外扩）——消灭边界半像素混合与极深 mip 渗色
   （Unity paddingPower 同款语义）。
3. **Canvas 软渲染 chunk 烘焙改双线性 + 复用链式预降采样**（事实 3）。
4. **RGBA 回退路径生成真 mip 链（2048→4）并多级上传**（事实 4）。
5. **道路边缘条 UV 翻转**（左/上缘条水平镜像，使深色边朝外，事实 5）。
6. **顺手清理**：`RoomMigrationTest.kt` 4 处 detekt 违规（LongMethod/MaxLineLength），
   使仓库级 `detekt` 恢复全绿。
7. 登记两项"条件式"技术债（见第八节）：① 缩放上限提升时槽位需同步提升；② 未来启用
   REPEAT 整图地面 quad 时需为其单独生成 mip（REPEAT 与图集 mip 语义不同源）。

### 1.3 成功标准（可验收）

> **实施记录（2026-09-05）**：①②③④⑤⑥ 全部落地，验收见 §5.3 实施结果与 CHANGELOG「渲染纹理降采样管线统一改版」条目。偏差说明：验收脚本按逐像素配对比较实现（均值法对异质边缘假阳性，见脚本头注释）；严格级收敛为 mip0（pad≡边缘逐位成立），mip1/2 用渐变容差（pad=源首行复制 vs 边缘 texel=源边缘 2^k 行混合的固有偏差，Unity 复制式 pad 同款表现）+ 渗色绝对上限 96，mip4 报告项——§5.3 原"各级 ±8"规格未计入渐变边缘与 ASTC 编码行为，实施时按上述分级落定。

- [x] `build-atlas.mjs` 产出的 KTX 各级 mip 中，任一张精灵的边缘采样不再混入相邻精灵颜色
      （验收脚本：对每精灵各级 mip 计算"边缘 1px 环"与"内容均值"色差 < 阈值，见 §5.3）。
- [x] `atlas-manifest.json` 新增 `mipMode: "per-sprite"` 字段并被 `AtlasManifestSyncTest` 断言。
- [x] Canvas 软渲染真机/模拟器：建筑/瓦片无点采样斑点；与 Vulkan 观感一致（双端同滤镜，像素测试全绿；真机观感待复验）。
- [x] RGBA 回退路径（测试用 Fake 注入）上传的纹理 mipLevels = 11（单测断言 JNI 入参）。
- [x] 道路左缘条深色边朝外（`DiffRoadComposeTest`/`road_compositor_test.cpp` 断言 flip 标志；真机目测待复验）。
- [x] `detekt` 全绿（含 `RoomMigrationTest.kt` 清理后）。
- [x] 全部相关单测 + pre-commit 门通过（守卫/渗色验收/Canvas 系列/RoomMigration 62/62；GTest 768/768；`compileReleaseKotlin`+detekt 全模块+`externalNativeBuildRelease` 全绿；2026-09-05）。

---

## 二、技术方案

### 2.1 项 1+2：每精灵独立 mip + gutter/padding（`build-atlas.mjs` + KTX 重生成）

**数据流（构建期）**：源 WebP（双模块 drawable）→ 逐精灵按目标槽位尺寸 lanczos3 下采样
（mip0）→ 对每级 mip k：逐精灵独立下采样至 `slot>>k`（**从 mip0 精灵内容重新采样，避免级联
失真**，与现状"每级从 mip0 整图重采样"同一理由）→ 拼入"含 pad 环的槽位"→ 合成到
`4096>>k` 级位图 → 逐级 astcenc → KTX1 多 mip 封装（容器结构不变）。

**规格**：

1. **gutter**：LAYOUT 任一相邻槽位间距 ≥ **8px**（mip0 尺度）。`LAYOUT` 全部 rect
   按此重排（建议起始布局见 §2.1.3；原则：先复用空闲区——道路 768² 收缩已空出
   x 2176..3072 × y 2048..2816 大区，建筑行 y 2560..2816 尚有 256px 条带）。
2. **pad 环**：每级槽位四周 pad = **4px**（当前 mip 级尺度）。pad 内容 = 该精灵内容
   **边缘像素外扩**（非透明/纯色填充——边缘外扩保证 mip 边界渐变连续）。
3. **UV 采样端零改动**：归一化 UV 由 codegen 按新 rect 自动生成；`UV_EPSILON` 保持。
   采样器（三线性 + 各向异性）不动。
4. **构建器自检（fail-fast）**：每级合成后校验"该级精灵数 == 上一级"且尺寸几何正确，
   任一精灵下采样后宽高 < 1 即报错（ASTC 4×4 块下限沿用现有 `max(ASTC_BLOCK, ...)` 逻辑）。
5. **KTX 产物结构不变**（仍多 mip：`numberOfMipmapLevels=11`；KtxLoader/VulkanBackend
   ASTC 上传零改动——它们只消费"逐级 [size4][数据]"）。

#### 2.1.3 建议布局（已做冲突检查：全部槽位在 4096² 内、任意两槽间距 ≥8px；实施者可微调，改后过守卫测试）

| 区 | 精灵 | 尺寸 | 位置 (x,y,w,h) |
|---|---|---|---|
| 瓦片/树/作物行 | GROUND / GRASS1~4 | 128²×5 | (0,0,128,128) / (136,0,…) / (272,0,…) / (408,0,…) / (544,0,…) |
| 同上 | STONE1~3（2026-09 新增装饰） | 128²×3 | (2048,0,128,128) / (2184,0,128,128) / (2320,0,128,128)（行 0 空带，与邻近槽位 ≥8px） |
| 同上 | TREE1 / TREE2 | 256²×2 | (800,0,256,256) / (1064,0,256,256) |
| 同上 | crop_seedling / growing / mature | 128²×3 | (1384,0,128,128) / (1520,0,128,128) / (1656,0,128,128) |
| 建筑行 1~4 | 19 座（灵田走 override，见下） | 512² / 节距 520 | 行 y=512/1032/1552/2072；列 x=0/520/1040/1560/2080（行 4 仅 4 列） |
| 灵田 | — | 128² | override (1044,516,128,128)（占行 1 col2 槽位左上，与左邻间距 8px） |
| 天枢殿 | — | 1024² | override (3008,1032,1024,1024)（与门楼 y 间距 8px） |
| 门楼 | — | 768×512 | (3072,512,768,512)（不变） |
| 道路 | road_body / road_edge_v / road_edge_h | 128² / 32×96 / 96×32 | (2048,2624,128,128) / (2176,2624,32,96) / (2176,2720,96,32)（建筑行 4 下方空带，y 2584..2816，云层下移界 2816 之上） |
| 云层 | 5 张 | 不变 | y≥2816 不变（远背景半透明，mip 均值即可，无 gutter 强制） |

> 约束备忘：① 建筑行右边界 x=2600 与天枢殿左界 3008 之间留空（供后续内容/天枢殿扩容）；
> ② 任何 rect 必须满足"与全部其他 rect 的水平和垂直双向间隙 ≥8"（守卫测试会校验）；
> ③ 道路移离原 (2048,2048) 区是为了给建筑行 4（y 2072..2584）让位——**注意：原道路区
> 已是建筑行 4 的一部分，本表已按新坐标给出，实施时不要沿用旧坐标**；
> ④ **云层行零间距风险（2026-09 审查确认）**：云 1|2|3、云 4|5 相邻零 gutter，per-sprite
> pad 环会覆盖前一张云右缘 4px——当前云素材右缘全透明（alpha=0），覆盖落在透明带内，
> 无视觉影响；**未来替换为硬边缘云素材时必须给云层行加 ≥8px gutter 并移除守卫豁免**。

### 2.2 项 3：Canvas 软渲染烘焙改双线性 + 链式预降采样

`android/feature/game/.../sect/SoftwareCanvasBackend.kt`：

1. `ChunkTile.rebuildPaint`：`isFilterBitmap = false` → `true`（该 paint 同时服务瓦片/装饰/
   建筑/道路四层，一次修改全层生效；保留 `isAntiAlias=false`、`isDither=false`）。
2. `drawBuildingsToCanvas`/`drawGroundRow`/`drawRoadSprite`：目标矩形尺寸已由调用方装配；
   在 `canvas.drawBitmap(atlas, src, dst, rebuildPaint)` 前，若 `src` 宽高 > `dst` 2 倍，
   先调 `SectAtlasAssembler.downscaleWithBilinearChain`（同模块 internal，直接复用，跨类
   调用点改 `internal` 可见性或复制同式私有实现——推荐直接复用，单一实现）得到预降采样
   位图再绘制。**注意**：中间位图生命周期随绘制结束即废（普通局部变量，GC 回收，不 recycle，
   遵循既有 double-free 规避惯例）。
3. 地面整图 REPEAT 已为双线性（`BitMapShader` + isFilterBitmap=true），不动。

**测试影响**（必须同步）：`SoftwareCanvasBackendTest` 及其派生（Atlas/Crop/Demolish/Grid/
Highlight/LodFade/RenderScale/Cloud）中用"手绘期望图 + 像素比对"的用例，期望图一律用
**同一滤镜**（`Paint(FILTER_BITMAP_FLAG)` 且下采样预链）重建，禁止手调数值基准。

### 2.3 项 4：RGBA 回退路径真 mip

**Kotlin**（`AtlasAsyncPipeline.kt` + `NativeBridge.kt`）：

1. 新增 `encodeBitmapToRgbaMipChain(bitmap): MipChainPayload`——从拼装位图（2048²）逐级
   50% 双线性缩放（`Bitmap.createScaledBitmap(filter=true)`，级联生成；2048→4 共 11 级），
   各级经既有 `encodeBitmapToRgbaBuffer` 编码后按 level-major 顺序拼接为单一 direct
   `ByteBuffer`，同时携带各 level 宽高数组。
   - 峰值内存：约 21MB 瞬时（2048² 16MB + 1024² 4MB + …），后台线程一次性，可接受。
2. `NativeBridge.kt` 新增 external 函数
   `uploadTextureMipChainDirect(pixelData: ByteBuffer, width: Int, height: Int, mipCount: Int): Int`
   （保留原 `uploadTextureDirect` 给地面 64×64 单层纹理，不动）。
3. `AtlasAsyncPipeline.uploadRgbaAtlas` 改用新函数；`AtlasPayload` 增加 mip 链字段。

**C++**（`NativeBridge.cpp` + `VulkanBackend.h/.cpp`）：

1. `VulkanBackend::uploadTextureImpl` 扩展：新增 `mipLevels` 参数与"逐级从 staging 拷贝"分支
   （镜像 ASTC 路径 `uploadCompressedTexture` 的逐级 `VkBufferImageCopy` + 全层 barrier +
   `subresourceRange.levelCount = mipLevels`；数据布局：level-major 紧凑排列，`bufferOffset`
   逐级累积——**必须 16 字节对齐** 若格式非压缩则 4 字节对齐即可，RGBA8 每像素 4 字节天然
   四对齐，满足 VUID）。
2. `NativeBridge.cpp` 新 JNI 入口 `Java_..._uploadTextureMipChainDirect`（仿
   `uploadTextureDirect`：`lockDirectPixels` 校验 + 首级尺寸校验），非 Vulkan 后端返回 0。
3. 原 `uploadTextureDirect` 保持签名不变（地面纹理与潜在其他调用）。

**验证**：`AtlasAsyncPipelineTest`（或既有对应测试）以 Fake 注入断言 mipCount/各级尺寸
入参；C++ 侧随 `externalNativeBuildRelease` 编译验证。

### 2.4 项 5：道路边缘条 UV 翻转（方向修正）

**合成器**（`gamecore/include/gamecore/map/road_compositor.h`）：

1. `RoadDrawOp` 增 `bool flipU`（默认 false）。翻转规则（依据纹理深色边位置：
   `road_edge_v` 深色边在右、`road_edge_h` 为其旋转 90°（深色边在下））：
   - `emitV(0, ...)`（左缘）→ `flipU = true`；`emitV(tileSize-edgeW, ...)`（右缘）→ false；
   - `emitH(0, ...)`（上缘）→ `flipU = true`；`emitH(tileSize-edgeW, ...)`（下缘）→ false；
   - `addJoin` 交汇块 → false（8×8 方块，方向无意义，保持现状）。
   - 操作数上限 `kMaxRoadDrawOpsPerTile=6` 不变（flag 不是新操作）。
2. `road_compositor_test.cpp`：新增断言（左缘首 op flipU=true / 右缘 false；上缘 true /
   下缘 false；join false）。

**Vulkan 消费**（`NativeBridge.cpp` drawAllTiles 道路段）：`batcher.add` 时
`flipU ? swap(u0,u1) : 原样`——水平镜像即交换 u0/u1（v 不变）。

**Canvas 消费**（`SoftwareCanvasBackend.kt drawRoadSprite`）：`flipU` 时交换源矩形
`src.left/src.right`（`v` 不变），即 `kit.roadSrcRects[key]` 的左右对调后绘制。

**Kotlin 桥**（`RoadCompositorBridge.kt` + `GameCoreBridge.kt` + `SoftwareCanvasBackend` 操作
解析）：`OP_STRIDE` 5 → 6（尾部追加 flip 0/1）；`GameCoreBridge.nativeRoadCompose` 对应的
C++ 产出（`GameCoreJni.cpp`）同步 stride（C++ RoadDrawOp 增字段后，flat 数组编码加一位）。
**测试**：`DiffRoadComposeTest.kt`（镜像手算规格加 flip 字段）、`SoftwareCanvasBackendTest`
道路用例（若断言绘制结果需同步）、`road_compositor_test.cpp`。

> 若真机验证发现"深色边在内侧"其实符合美术预期（内侧阴影带），则取消本项并在
> `road_compositor.h` 注释注明意图——**必须先验证再实施**（§6.1 验收前置）。

### 2.5 项 6：RoomMigrationTest.kt detekt 清理

`android/core/data/src/test/java/com/xianxia/sect/data/local/RoomMigrationTest.kt`：

- L274 / L1222-1223：超长行折行（≤120）。
- L1184 `MIGRATION_49_50...` 63 行 → 拆 `private fun` 两步（数据准备 + 断言），
  @Test 函数名保留（`LongMethod` 按函数体行数计，拆出辅助函数即解）。

### 2.6 明确不做（YAGNI 反向检查 + 决策）

| 备选 | 不做原因 | 登记 |
|---|---|---|
| 小精灵槽位 128→256（"3× 更锐利"） | 128 槽位在缩放上界 3.0 处仅 1.125× 放大（8% 软度，与瓦片一致已验收过）；256 槽位等效于把原生点移到 zoom 5.33（超出上限），对 0.3~3.0 区间的 LOD 摆动无实质增益，却带来全 LAYOUT 重排 | 见第八节债项 T-1（触发：缩放上限 >3.0 时） |
| Compose UI 精灵（丹药/立绘）GPU 压缩 | 静态展示无动态缩放，无同类混叠问题；Compose ImageBitmap 无成熟 ASTC 路径 | 已登记（art-asset-pipeline 债项 (a)/(c)），本方案不重复 |
| 图集迁 Texture Array | 移动端兼容门槛（部分 GLES/Mali 老旧驱动），收益与本方案等价但不覆盖 Canvas/RGBA | 内部调研 §3.3 已记录，维持"图集 + 独立 mip"路线 |
| 背景 REPEAT 地面 quad 恢复 | `GROUND_QUAD_ENABLED=false` 因 Adreno 740 采样异常黑屏（2026-09 记录），与 mip 无关 | 见第八节债项 T-2 |

---

## 三、影响范围清单

| 文件 | 变更类型 | 变更说明 |
|------|---------|---------|
| `android/scripts/build-atlas.mjs` | 修改 | `generateMips()` 重写为 **per-sprite**（逐精灵独立下采样合成）；`buildAtlasPng()` 支持 gutter（LAYOUT rect 重排）；新增每级 pad 环装配；manifest 增 `mipMode: "per-sprite"`；`LAYOUT` 全量 rect 按 §2.1.3 重排 |
| `android/app/src/main/assets/atlas/atlas_astc.ktx` | 重生成 | 新布局 + 独立 mip 内容（容器结构不变，仍 11 级） |
| `android/app/src/main/assets/atlas/atlas-manifest.json` | 重生成 | layoutHash 变更 + `mipMode` 字段 |
| `android/core/engine/build/generated/sprite/SpriteAtlasDef.kt` | 重生成（codegen） | rect 随 LAYOUT（git 忽略，构建自动） |
| `android/app/build/generated/sprite/TextureAtlas.h` / `SpriteRegistryData.kt` | 重生成（codegen） | 同上 |
| `android/feature/game/.../sect/SoftwareCanvasBackend.kt` | 修改 | `rebuildPaint` 双线性；三处绘制前链式预降采样（复用/下沉 `downscaleWithBilinearChain`）；道路 flip 消费（2.4） |
| `android/feature/game/.../sect/SectAtlasAssembler.kt` | 修改 | 链式降采样可见性调整（供 Canvas 复用）；无其他 |
| `android/core/engine/.../nativebridge/NativeBridge.kt` | 修改 | 新增 `uploadTextureMipChainDirect` |
| `android/app/src/main/cpp/NativeBridge.cpp` | 修改 | 新 JNI 入口 + 道路 flip（u0/u1 交换）+ RGBA 多级上传接线 |
| `android/app/src/main/cpp/VulkanBackend.h/.cpp` | 修改 | `uploadTextureImpl` 支持多 mip 逐级拷贝（RGBA） |
| `android/app/src/main/cpp/gamecore/include/gamecore/map/road_compositor.h` | 修改 | `RoadDrawOp.flipU` + 边缘翻转规则 |
| `android/app/src/main/cpp/gamecore/jni/GameCoreJni.cpp` | 修改 | `nativeRoadCompose` flat 数组 stride 6 |
| `android/core/engine/.../render/RoadCompositorBridge.kt` | 修改 | `OP_STRIDE` 5→6 + flip 字段解析（路径：`core/engine/src/main/java/com/xianxia/sect/core/render/`） |
| `android/core/data/src/test/.../RoomMigrationTest.kt` | 修改 | detekt 违规清理（2.5） |
| 测试 | 修改/新增 | §五 清单 |
| `docs/design/art-asset-pipeline-improvement.md` | 修改 | 第八节债项 (b) 补注"独立 mip 已落地" |
| `docs/architecture.md` | 修改 | 债项登记（R 系列 + 触发条件档案 T-1/T-2 见第八节） |
| `CHANGELOG.md` / `android/app/src/main/assets/changelog_entries.json` | 追加 | 实施完成后追加（规则 12.4，两个都更） |

**经济影响（`经济` 标签）**：零——不触碰任何货币/数值/发放逻辑（显式声明）。

**iOS 影响（`iOS` 标签）**：无阻碍——管线脚本（Node/sharp）与 C++ 采样逻辑跨平台；
Metal 对等 = `MTLSamplerDescriptor.mipFilter = MTLSamplerMipFilterLinear` +
`maxAnisotropy`（无平台差异）；KTX/上传逻辑不经 Android API。实施时在 ADR 或本文档
补一句对等说明即可。`opengles`/sdk 相关路径不涉及。

**隐私合规**：不涉及（无新 SDK/权限/数据收集）。

---

## 四、兼容性分析

- **存档/Entity/序列化**：零变更（无 DB Migration、无协议字段变化）。
- **KTX 容器协议**：不变（仍 KTX1 + 11 级 + 逐级 [size4][数据]）；KtxLoader/
  `uploadCompressedTexture` **零改动**。产物内容（mip 生成方式）变化不影响加载兼容；
  旧 KTX 若回滚（git）则回到旧内容，双方可互切（仅视觉差异）。
- **codegen 契约**：rect 变更全部由 `LAYOUT` 驱动；守卫测试（`AtlasManifestSyncTest`/
  `AtlasLayoutSyncTest`/`SpriteCodegenSyncTest`）自洽重建，无硬编码期望需手改
  （唯一例外：若某测试硬编码了 rect 数值——现状经检索无，新增 guard 测试也在本文档范围内）。
- **Canvas vs Vulkan 双端一致性**：两路径同步修改（2.2 与 2.1/2.4），无单端先行。
- **运行时开关回退**：`--no-mip` 兜底保留（回退单 mip 结构）；Canvas 滤镜无开关（视觉修正，
  不引入开关）；RGBA 多级上传失败走"单级回退"（旧行为）——实现时保留单级 fallback 分支。

---

## 五、测试方案

### 5.1 新增/修改测试清单（含墙钟核算）

| 测试 | 类型 | 墙钟 | 说明 |
|------|------|------|------|
| `SpriteAtlasDefGeneratedTest` 新增：布局 rect 全量规则（gutter ≥8 校验 + 槽位上限 ≤显示×4 保持 + 现有道路专项） | 守卫（文本解析） | <1s | 枚举驱动 + 操作指引（错误消息带精灵名与"去 LAYOUT 改哪"） |
| `AtlasManifestSyncTest` 增断言：`mipMode == "per-sprite"` | 集成 | <1s | 读 manifest——防"整图 mip"回退的契约锚点 |
| `SectAtlasAssemblerDownscaleTest` 保持/微扩 | 守卫 | <1s | 既有 4 用例不动；如链式函数下沉到新文件，改引用 |
| `SoftwareCanvasBackend*` 系列像素用例 | 回归 | 各 <5s | 期望图用**同滤镜重建**（2.2）；遍历改动的用例逐个更新 |
| `DiffRoadComposeTest.kt` | 回归 | <1s | 加 flip 字段预期（手算规格镜像） |
| `road_compositor_test.cpp` | GTest | <1s | 加 flip 规则用例（左/右/上/下/join） |
| `AtlasAsyncPipeline` 单测（新增 1-2 用例） | 单元 | <1s | Fake 断言 mip 链入参（mipCount/级尺寸/mipMode 字段） |
| `mip 渗色验收脚本`（一次性，非 CI） | 脚本 | ~10s | §5.3 |

> 无 >30s 单项；新增合计 <5s，符合预算。

### 5.2 对抗性审查要点

- **mip 内容回退整图下采样** → manifest `mipMode` 断言 + 验收脚本边缘色差；万一
  `--no-mip` 被误用 → manifest `mipLevels=1` 已有既有断言（mipCount≥1），单 mip 不误判为 per-sprite。
- **gutter 漏排** → 守卫测试对 rect 两两做"水平/垂直相邻余量 ≥8"检查（新用例）。
- **pad 环用透明/纯色填充** → 验收脚本检查 pad 环与内容边缘色差（§5.3 检验项 2）。
- **UV 翻转只修了一端** → `DiffRoadComposeTest`（Kotlin 桥）+ `road_compositor_test`
  （合成器）+ Canvas/Vulkan 双端消费（各自测试），三端同步。
- **RGBA mip 上传 alignment/bufferOffset 错位**（ASTC 曾踩坑：VUID 对齐）→ implementation
  评审点：level-major 紧凑、offset 累积、RGBA4 字节对齐；`externalNativeBuildRelease` 编译门 +
  真机（或无 ASTC 模拟）确认。
- **Canvas 滤镜导致测试基准漂移** → 禁止手调期望值；一律"同滤镜重建"。
- **布局重排压到云层/闲置区** → 守卫测试（rect 全部在 4096 内 + 精灵间 gutter ≥8 + 实体矩形互不重叠，三步）。

### 5.3 mip 渗色验收脚本（实施后运行一次，记录结果到 CHANGELOG）

Node + sharp：重新解码 KTX 的 mip0/mip1/mip2/mip4，对每张精灵按 manifest rect 计算指标。
**判定以两条结构性判据为准**（2026-09 修订——原"pad 中位差 ≥96 即渗色"的绝对上限会把
边缘自身陡梯度误判为渗色）：

1. **图集实体矩形互不重叠**（含云层）：重叠 = 合成顺序决定谁被覆盖，被压者的 pad 环/
   半透明边缘混入对方颜色。实测反例：`cloud_3` 原槽位压住浮空岛左右环三张精灵，
   mip2 边界 texel 混入云朵蓝色（已把 cloud_3 移到 x≥3088 空带，并新增 JUnit 守卫
   `图集布局实体矩形互不重叠` 常态化拦截）。
2. **mip0 pad ≡ 内容边缘**（RGB 各通道中位差 ≤8）：pad 环由 nearest 复制边缘像素生成、
   mip0 不重采样，二者必须逐位等价。

mip1/2/4 的 pad-vs-边缘色差为**报告项**（非门禁）：内容边缘 texel 是源边缘 2^k 行的混合、
pad texel 是首行复制，边缘自身有陡梯度时二者固有偏差（Unity paddingPower 同款语义）。
实测证据：全部超阈精灵的图集区域与**其独立重建**逐位一致（最大差 ≤1）——即无任何外来内容。
结构上 per-sprite 独立 mip + 判据 1 已排除邻居内容进入的可能。

> 管线侧配套修复（2026-09）：图集重采样全链改**预乘 alpha**（`build-atlas.mjs` 文件头
> "预乘 alpha 约定"）——素材透明区残留 RGB 在直通 alpha 重采样时会被当成真实颜色混入
> 邻近可见像素，在羽化硬边（岛边缘）产生灰白毛边。

---

## 六、风险评估与兜底

| 风险 | 缓解 |
|------|------|
| 布局重排协调错（槽位重叠/越界） | 守卫测试（两两 gutter + 4096 边界 + **实体矩形互不重叠**三步）；codegen 三端同步自动拦截 |
| 独立 mip 构建成本上升（38 精灵 × 11 级 astcenc） | 与现状同构（现状也 11 级），仅需按精灵粒度重采样；构建时间预期 +50% 内，构建机一次性 |
| Canvas 滤镜改动波及大量像素测试 | 只允许"同滤镜重建"更新；CI 串行跑（--max-workers=1） |
| RGBA 多级上传在部分驱动异常 | 保留单级 fallback（旧行为），失败回收纹理 id=0 走既有回退链 |
| 道路 flip 修正与美术意图不符 | **实施前真机确认**（2.4 注）；确认不符则取消该项并注释（不返工管线其余项） |
| manifest 字段破坏旧解析（向前兼容） | 旧客户端不读 `mipMode`（新增字段，非结构变更），无兼容问题 |

---

## 七、未来场景推演（≥6 个月档）

| 维度 | 推演 |
|------|------|
| 规模增长 | 精灵 ×10（内容扩张）时：布局/独立 mip 均为"每精灵线性增量"；守卫测试枚举驱动自动覆盖；罐装 KTX 重建一条命令。无超线性成本 |
| 生命周期 | 构建/重启/clean 后行为一致（rect 由 LAYOUT hash 驱动，mip 由 per-sprite 配方驱动；`generatedAt` 已剥离 hash）；`--no-mip` 兜底可回退 |
| 平台扩张（iOS） | 管线脚本 + C++ 采样跨平台；Metal 采样器参数对等（§三 iOS 标签）；Canvas 软渲染在 iOS 无对应需求（Apple 设备全 GPU），但管线同构 |
| 运营演进 | 换美术 = 改源 + 重导 + 重生成 KTX（无需发版，mapping 闭环已在）；材质策略变化仅改 LAYOUT |
| 兼容回退 | git 回滚即回退（产物+脚本同仓库）；manifest `mipMode` 字段使"整图 mip"回退可检测 |

---

## 八、技术债与偿还计划

| 债项 | 产生原因（为何现在不全做） | 偿还时机（触发条件） |
|------|--------------------------|--------------------|
| T-1 小精灵槽位随缩放上限提升 | 上限 3.0 下 128 槽位 1.125× 放大可接受；256+ 需全布局重排 | **触发**：`CameraState.MAX_ZOOM` 上调至 >3.0 时，槽位按新上限重算（公式：槽位 = ceil(maxDisplay/32)×32）并重排布局 |
| T-2 REPEAT 整图地面 quad 的 mip | `GROUND_QUAD_ENABLED=false`（Adreno 740 黑屏，2026-09），恢复前无消费者 | **触发**：该 quad 重新启用（驱动黑屏问题定位）时，为其生成独立 REPEAT mip 并连接上传 |
| T-3 大结构（天枢殿/门楼）3× 极限放大略柔 | 1.19×/2.67× 槽位为按显示建模；全 3× 覆盖槽位（2592px）超单图集可行性 | **触发**：出现"建筑放大细节"玩家反馈或美术要求时，评估局部高清槽位或图集拆分 |
| （无"现在不做、无触发"的隐藏债——另：本方案实施前道路/灵田/整图 mip 等欠债随 §2.1/2.2/2.3 一次性清偿） |

> 完成后同步登记 `docs/architecture.md`（R 系列 + 触发条件档案 T-1/T-2/T-3）。

---

## 九、参考来源

| 来源 | 类型 | 等级 | 核心结论 |
|------|------|------|---------|
| Unity `Sprites.AtlasSettings.paddingPower` 官方 API | 官方文档 | S | 图集 padding 的官方机制：防 mipmap 渗色（bleeding），本项目 spec 的 pad 环语义同源 |
| Godot engine issue #78743（use_texture_padding 与 mipmap 交互） | 官方追踪器 | A | 图集 mip 渗色是真实已知问题；padding 为业界标准解法 |
| Godot forum「How to avoid atlas texture bleed over」 | 社区 | B | 图集渗色场景与规避清单（配合 padding/gutter 的实操经验） |
| 项目内部 `docs/research/tile-map-industry-benchmark.md`（27+ 来源，§3.1-3.3） | 内部调研 | S | 图集逐格批处理 + Edge Case：Texture Array「无 bleeding + 完美 mipmap」→ 单图集等价手段即独立 mip + padding |
| 项目内部 `docs/design/art-asset-pipeline-improvement.md`（2026-09-02，B.1 多 mip 落地记录） | 内部方案 | S | 现状"整图 mip"决策记录与 `--no-mip` 兜底意图；本次为其缺陷修正 |

> 说明：本方案属**渲染基建类**（非玩法/商业化/社交/数据类），按 design-plan-review 决策分级
> 走架构级全流程，参考来源聚焦管线/图形（S/A 级为主，含内部 27+ 来源调研——玩法类
> 20 条硬配额不适用于本类，与 art-asset-pipeline-improvement.md 先例一致）。

---

## 十、方案自检结论（design-plan-review）

- [x] 未来场景推演小节已写（≥6 个月档：规模/生命周期/平台/运营/回退）
- [x] 技术债三列表 + 明确触发条件（T-1/T-2/T-3）；无隐藏债
- [x] YAGNI 反向检查：2.6 节显式列出"不做项"及其理由；所有新抽象（mipMode 字段/flipU/
      uploadTextureMipChainDirect）均有当前生产消费者（渲染管线/合成器/上传路径）
- [x] 测试墙钟核算：新增 <5s，无 >30s 项（§5.1）
- [x] rules/ 交叉核对：触碰 `rules/design-plan-review.md`（本表）、
      `rules/static-resources.md`（无新资产，仅布局——不涉新精灵注册）；
      `renderer-feature-checklist.md` 在仓库不存在（被 ADR/threading-contract 引用），
      双端一致性按 `SoftwareCanvasBackendTest` + `RenderBackendContractTest` 契约衡量；
      无经济/商业化/数据库/social 冲突（渲染纯视觉）
- [x] 决策分级已声明（架构级重构）
- [x] 影响范围清单含经济标签（零影响显式声明）+ iOS 标签（对等分析，无阻碍）
- [x] 兼容回退：KTX 容器协议不变 + `--no-mip` 兜底 + RGBA 单级 fallback

---

## 十一、实施顺序与验收门

推荐顺序（依赖关系：2.1 → 2.3（复用 2.1 的 mip 语义）与 2.2（复用链式工具）可并行 →
2.4（独立）→ 2.5（随时））：

1. **2.1 独立 mip + gutter/padding**：改 LAYOUT + generateMips → 跑
   `node scripts/build-atlas.mjs`（全量）+ `--atlas-def-only` + `--codegen` →
   `:core:engine:testReleaseUnitTest --tests "com.xianxia.sect.core.render.SpriteAtlasDefGeneratedTest"`
   + `:app:testReleaseUnitTest --tests "com.xianxia.sect.AtlasManifestSyncTest" --tests "com.xianxia.sect.AtlasLayoutSyncTest" --tests "com.xianxia.sect.SpriteCodegenSyncTest"`
   → §5.3 验收脚本。
2. **2.3 RGBA mip**：Kotlin 链 + JNI 签名 + C++ 多级上传 → 单测 + `externalNativeBuildRelease`。
3. **2.2 Canvas 烘焙**：paint + 链式 + 测试重建 → `:feature:game:testReleaseUnitTest`（
   `SoftwareCanvasBackend*` 全量 + `SectAtlasAssemblerDownscaleTest`）。
4. **2.4 道路 flip**：合成器 + 桥 + 双端消费 + 三个测试 → GTest（googletest 主程序）+ 
   `DiffRoadComposeTest`。
5. **2.5 清理** + **文档/更新日志**（§三最后两行）。
6. **总验收**：`./gradlew.bat compileReleaseKotlin testReleaseUnitTest --max-workers=1 -Pkover.enabled=true detekt koverHtmlReport -Pkover.enabled=true`
   ；`./gradlew.bat :app:externalNativeBuildRelease`；`lintRelease`；真机（Vulkan + 软渲染
   强制）各看一眼——道路/灵田缩放全程稳定、直路左缘深色边朝外、无边界混色。

> 注意：`detekt` 全绿是本方案验收目标之一（含 RoomMigrationTest 清理后），
> 仓库当前 detekt 唯一红点即该文件，实施后必须全绿。
