# 浮空岛崖壁地图边缘渲染系统（Island Cliff Renderer）设计文档

> 日期：2026-09（素材换代批次）
> 范围：宗门地图**左、右、下三侧 + 左下/右下转角**「可行走地面 → 浮空岛崖壁」的渲染系统。
> 前置事实：地图 128×128 格 × 48px（6144×6144 世界像素）；渲染层为 C++ RHI
> （Vulkan/GLES 同一 `Rhi.h` 接口）+ Kotlin Canvas 双后端。
> **本文档取代旧版 `island-edge-renderer.md`**（旧的 37 张薄切片图集方案已下线）。

---

## 一、背景与目标

### 需求要点（用户提供）
1. 新素材《宗门地图边缘》（左 3 / 右 3 / 下 2 / 左下角 / 右下角）接到地图**左、右、下三侧**。
2. 尺寸按**素材自身比例 1:1**（不是缩略图，也不铺满整幅地图）。
3. 同侧有多个变体时**随机拼接**。
4. **替换**旧边缘系统：删除上边缘与悬浮碎石。

### 已确认决策
| 项 | 决定 | 依据 |
|---|---|---|
| 纹理方案 | **独立纹理**（不进图集） | 单张最大 1180×3552 超出 4096² 容量；Vulkan 核心仅保证 `maxImageDimension2D ≥ 4096`（8192 属 Roadmap 2022 Profile，低端 Mali/Adreno 6xx 不保证） |
| 尺寸 | 源分辨率 1:1 | 用户确认 |
| 压缩 | ASTC 4×4 + mip 链（Vulkan）+ RGBA 回退 | 117MB → 37.67MB |

### 成功标准
① 三侧 + 两角无缝铺满、末块不拉伸素材；② 同侧变体随机且不出现相邻重复；
③ 单张纹理上传失败只丢对应条目（不整层消失、不画白）；④ 双后端同数据同 z 序；
⑤ 拖到地图边缘即完整看见崖壁。

---

## 二、素材实测结论（决策依据）

| 素材 | 尺寸 | 结构 |
|---|---|---|
| 边缘1/2/3（左缘） | 1176×3552 / 1119×3368 / 1178×3552 | 岩左、草右（右缘为草带，贴地图左边界） |
| 边缘4/5（下缘） | 2560×1696 / 2304×1888 | 草上、岩下（顶边为草带，贴地图下边界） |
| 边缘6/7（转角） | 1832×2399 / 1828×2396 | 内缘贴角点，实体岩体自内缘向内约 0.36~0.93 宽 |

### 素材缺陷与处置
1. **镜像重复对**（逐像素水平翻转比对实测）：`左边缘1↔右边缘3`（mean diff 3.68）、
   `左边缘2↔右边缘2`（6.18，次优 45.07）、`左边缘3↔右边缘1`（0.67，max 6.75）
   ⇒ 6 张侧图实为 **3 个真变体**；右侧崖壁用**镜像位**复用左侧纹理。
2. **浅灰 AI 底**：`left_3`/`right_1` 为 100% 不透明 + 19.7% 浅灰底
   （RGB>225，四角 (252,252,252,255)）⇒ 淘汰灰底张，保留干净版本。
3. **ASTC 尺寸对齐**：4 张宽高非 4 的倍数（1119/1178/2399…），`KtxLoader` 拒绝
   ⇒ 新增 `bake.roundUp4`（向上取整到 4 的倍数 + `fit:contain` 同比例画布，**不拉伸**）。

### 取整后的世界尺寸（= 布局尺寸，双路径同源）
| drawable | 尺寸 | 说明 |
|---|---|---|
| `map_edge_left_1` / `_2` / `_3` | 1176×3552 / 1120×3368 / 1180×3552 | 后两张由取整得到 |
| `map_edge_bottom_1` / `_2` | 2560×1696 / 2304×1888 | 源已合规 |
| `map_edge_corner_bl` / `_br` | 1832×2400 / 1828×2396 | bl 由 2399 取整 |

---

## 三、技术方案

### 3.1 架构

```
D:\模拟宗门美术素材\宗门地图边缘\边缘1..7.png
   │ import-art-assets.mjs（bake{preserve, roundUp4}）
   ▼
drawable-nodpi/map_edge_*.webp ×7（双模块；GLES / Canvas 路径）
   │ build-edge-ktx.mjs（astcenc 4x4 + mip 链 + KTX1；复用 lib/ktx1.mjs）
   ▼
assets/atlas/edge/map_edge_*.ktx ×7（Vulkan 路径）

MapPreloadData(seed/dims/tileSize)
   │ remember(seed, dims, textureMask)
   ▼
IslandCliffBridge(core:engine) ──JNI──▶ gamecore/map/island_cliff.h（单一权威, GTest）
   ▼
islandCliffData: FloatArray, stride=10
   ▼
RenderFrame.islandCliffData
   ├─ Vulkan/GLES: VulkanRenderBackend → NativeBridge.drawIslandCliffs(data)
   └─ Canvas:      SoftwareCanvasBackend.drawIslandCliffs（同一数组、同一 z 序）
```

### 3.2 锚定约定（世界 Y 向下；地面 [0,mapW]×[0,mapH]）

| 环 | 锚定 | 拼接方向 | 裁剪 |
|---|---|---|---|
| 左崖 | 素材**右缘贴 x=0**（草贴地图），顶边贴 y=0 | +y | 末块到 y=mapH 截断 |
| 右崖 | 素材**左缘贴 x=mapW**，水平镜像复用左侧纹理 | +y | 同上 |
| 下崖 | 素材**顶边贴 y=mapH**，自左下角内缘内缩 0.5×cornerW 起 | +x | 末块到 `mapW − 0.5×cornerW` 截断 |
| 左下角 | 内缘（右端）贴 x=0，顶边贴 y=mapH | 单块 | 无 |
| 右下角 | 内缘（左端）贴 x=mapW，顶边贴 y=mapH | 单块 | 无 |

**绘制序：左 → 右 → 下 → 角**。角块最后绘制，覆盖边环/下环在角区的越界末块
（构造性保证角部无缝隙、无错位）。

**裁剪与 UV 同步**：末块越界时截断，并**按截断比例收缩 UV**（`u1 = u0 + Δu × 截断比`），
不拉伸素材。实测 128 格铺装：左 2 块（末块截 40px）、右 2 块（截 40px）、
下 2 块（末块截 1754px 中的透明留白部分）——截断部分恰为素材自身透明留白。

### 3.3 条目格式（stride = 10）

`[texIdx, x, y, w, h, u0, v0, u1, v1, flags]`

- `texIdx` → `IslandCliffBridge.TextureIndex`（0..6）
- UV **逐条目携带**：独立纹理各自归一化，且承载镜像与裁剪
- `flags` bit0 = 水平镜像（此时 `u0 > u1`，消费端取 min/max）

**为何不用全局 UV 表**：图集方案中 UV 表按精灵索引共享；独立纹理各张尺寸不同，
且镜像/裁剪使同一纹理的 UV 逐条目不同，故逐条目携带最简且消除索引错位风险。

**⚠️ 不得沿用图集的 `UV_EPSILON`**（`0.5/4096`）：该常量按图集纹素推导，用于防
相邻精灵渗色；独立纹理无邻居，加该偏移会在地图边界露出半纹素透明缝。

### 3.4 变体随机

`hash(seed, pool, seq) % poolSize`（splitmix32 派生）+「不与上一块重复」约束。
纯整数哈希，与引擎 RNG 分区无关（渲染数据不参与确定性命中回放对拍）。

### 3.5 纹理三级降级（逐张独立）

```
① ASTC KTX   → uploadIslandCliffKtx（仅 Vulkan 且设备支持 textureCompressionASTC_LDR）
                  └ 不支持/资产缺失/KTX 校验失败 → ②
② RGBA mip 链 → uploadIslandCliffMipChain（仅 Vulkan；GLES 返回 0）→ ③
③ RGBA 单级   → uploadTextureDirect（GLES；Canvas 路径走位图不传 GPU）
   任一步全败 → 该张 ID = 0 → 布局合成时以 textureMask 排除（部分降级，不整层消失）
```

- 解码/编码在**后台线程**，上传在**主线程**（C++ `g_renderer` 无锁）。
- 全部走 `ByteBuffer` direct（JNI `GetDirectBufferAddress` 零拷贝）。

### 3.6 关键类/接口

**C++（`gamecore/include/gamecore/map/island_cliff.h`，纯头文件、零 Android 依赖）**
```cpp
namespace gamecore::map {
enum class IslandCliffPool { LEFT, RIGHT, BOTTOM, CORNER_BL, CORNER_BR };  // 5 池 = 绘制序
inline constexpr int32_t kIslandCliffStride = 10;
inline constexpr int32_t kIslandCliffFlagMirrorX = 1;

struct IslandCliffConfig {
    int32_t cols, rows, tileSize, seed;
    int32_t textureCount; const float* textureW; const float* textureH;
    int32_t poolBase[5], poolCountArr[5]; const int32_t* poolFlat;
    float topInset;              // 岛面顶线内缩（观感微调点）
    float bottomStartRatio, bottomEndRatio;  // 下环与转角的水平重叠量
    uint32_t textureMask;        // bit i = 纹理 i 已成功上传
};
struct IslandCliffPiece { int32_t texIdx; float x, y, w, h, u0, v0, u1, v1; int32_t flags; };

int32_t computeIslandCliffLayout(const IslandCliffConfig&, IslandCliffPiece* out, int32_t maxPieces);
int32_t islandCliffMaxPieces(const IslandCliffConfig&);   // 容量上界（预分配）
}
```

**Kotlin（core:engine）**
- `IslandCliffBridge`：`PIECE_STRIDE=10`、`POOL_COUNT=5`、`Field`/`TextureIndex` 命名常量、
  `POOLS`（池表单一数据源）、`compose(cols, rows, tileSize, seed, textureSizes, textureMask)`
- `RenderFrame.islandCliffData: FloatArray?`
- `NativeBridge`：`drawIslandCliffs(data)` / `setIslandCliffTextures(ids)` /
  `uploadIslandCliffKtx` / `uploadIslandCliffMipChain` / `isAstcSupported`

**Kotlin（feature:game）**
- `IslandCliffTextureSet`：尺寸表（**编译期常量**，由 `EdgeKtxSyncTest` 守卫）+ drawable/KTX 资产清单 + `maskFor`
- `IslandCliffTextureLoader`：后台 `prepare` + 主线程 `upload`（含 C++ 注册）+ 三级降级
- `IslandCliffTextureHolder`：Compose 可观察掩码 + Canvas 位图集

### 3.7 Z 序与相机

- Z 序：`天空 → 崖壁 → 地图地面 → 道路/建筑/作物/植被/NPC → 云层`。
  崖壁与瓦片层共用同帧淡入 alpha；崖壁**不依赖图集纹理**（图集未就绪仍可绘制）。
- 相机：`ISLAND_CLIFF_VISIBLE_OUTSET = 2500`
  = `max(左右纹理宽 1180, 下纹理高 2400) + 余量 100`。
  视口 < 世界 + 2×outset → 相机可进入崖壁带（拖到边缘即见崖壁）；
  视口 ≥ 世界 + 2×outset → 整岛居中悬浮天际。
  **注意**：整岛居中分支的门槛是「视口 ≥ 世界」（非世界 + 2×outset）——
  outset(2500) 远超常见机型的最小缩放天空余量，以 outset 为门槛则居中分支不可达。

---

## 四、影响范围清单

| 文件 | 变更 | 说明 |
|---|---|---|
| `android/scripts/import-art-assets.mjs` | 修改 | 新增 `bake.roundUp4`；修正 `bakeKey` 误含 `fit` 导致全量重烘焙 |
| `android/scripts/scaffold-source-mapping.mjs` | 修改 | `MAP_KNOWN` 追加 7 条 `map_edge_*`（preserve + roundUp4） |
| `android/scripts/build-edge-ktx.mjs` | 新增 | 7 张 ASTC KTX + mip 链 + 落盘前契约自检 |
| `android/scripts/lib/ktx1.mjs` | 新增 | KTX1 容器 + astcenc 调用（自 `build-atlas.mjs` 抽出复用） |
| `android/app/build.gradle` | 修改 | 接线 `generateEdgeKtx` |
| `.../gamecore/include/gamecore/map/island_cliff.h` | 新增 | 布局合成器（单一权威） |
| `.../gamecore/include/gamecore/map/island_edge.h` | **删除** | 旧 9 池合成器 |
| `.../gamecore/test/island_cliff_test.cpp` | 新增 | 12 用例 |
| `.../gamecore/test/island_edge_test.cpp` | **删除** | — |
| `.../gamecore/test/CMakeLists.txt` | 修改 | 换登记 |
| `app/src/main/cpp/GameCoreBridge.cpp/.h` | 修改 | `nativeIslandCliffCompose` |
| `app/src/main/cpp/NativeBridge.cpp` | 修改 | `drawIslandCliffs` + 3 个纹理上行 + `isAstcSupported` |
| `app/src/main/cpp/KtxLoader.cpp` | 修改 | mip 块数整除 → **ceil**（根因修复） |
| `app/src/main/cpp/VulkanBackend.h` | 修改 | `isAstcSupported()` 访问器 |
| `.../gamecore/jni/GameCoreJni.cpp` | 修改 | 移除旧边缘桌面通道 |
| `core/engine/.../render/IslandCliffBridge.kt` | 新增 | 桥 |
| `core/engine/.../render/IslandEdgeBridge.kt` | **删除** | — |
| `core/engine/.../render/RenderFrame.kt` | 修改 | `islandCliffData` |
| `core/engine/.../nativebridge/{GameCoreBridge,NativeBridge}.kt` | 修改 | 新 external 声明 |
| `feature/game/.../sect/IslandCliffTexture{Set,Loader,Holder}.kt` | 新增 | 纹理链 |
| `feature/game/.../sect/{Vulkan,Software}RenderBackend.kt` | 修改 | 双路径消费 |
| `feature/game/.../sect/SoftwareCanvasBackend.kt` | 修改 | 独立纹理采样 + UV 直采 |
| `feature/game/.../sect/{SectAtlasAssembler,NativeSurfaceView}.kt` | 修改 | 旧边缘下线 / 新纹理接线 |
| `feature/game/.../map/sect/SectCameraState.kt` | 修改 | outset 400 → 2500 |
| `feature/game/.../{MainGameScreen,SectMapViewport,ResourcePreloader}.kt` | 修改 | 管道改名 + 纹理表注入 |
| `android/app/src/main/assets/atlas/edge/*.ktx` | 新增 7 | 生成物 |
| 双模块 `drawable-nodpi/map_edge_*.webp` | 新增 7×2 | — |
| 双模块 `drawable-nodpi/island_edge_*.webp` | **删除 37×2** | 旧素材下线 |
| `android/scripts/build-atlas.mjs` | 修改 | 删 `islandEdges`/`islandEdgePools` 段与消费点 |
| 4 个守卫测试 | 修改 | 删旧边缘期望 |
| `feature/game/src/test/.../EdgeKtxSyncTest.kt` | 新增 | 尺寸表 ↔ WebP ↔ KTX 三向守卫 |

**经济影响**：无（纯视觉）。
**iOS 影响**：合成器纯 C++ 零 Android 依赖；Metal **原生支持 ASTC**，KTX 数据可直接复用；
纹理上传仅需换 `MTLTexture` 实现（RHI 契约不变）。

---

## 五、兼容性分析

- **存档/序列化**：零影响（`MapPreloadData` 仅内存预加载数据）。
- **双后端**：Vulkan（ASTC→mip→单级）、GLES（单级）、Canvas（位图）三层各自可跑，消费同一布局数组。
- **低端设备**：无 ASTC 时落 RGBA（117MB 级）——**主要风险**；逐张失败降级 + 若真机吃紧可把
  `MAP_KNOWN` 的 bake 改为 `maxDim`（世界布局不变，仅纹理密度下降，**单点回退**）。
- **Vulkan 硬边界**：单纹理 ≤ 4096（核心保证）满足；按纹理分批绘制（`submitFrame` 亦按
  `DrawBatch.textureId` 重绑），切换极少（布局按池分组产出）。
- **不触碰**：碰撞/寻路/网格/建筑放置零影响（纯渲染数据）。

---

## 六、测试方案

| 层 | 测试 | 覆盖 |
|---|---|---|
| C++ GTest | `island_cliff_test`（12） | 三侧+两角锚定不变式、拼接连续性、末块裁剪、**UV 跨度 ≡ 绘制比例**、变体确定性 + 无相邻重复、1×1~300 格自适应、纹理缺失降级、非法输入 |
| Kotlin 守卫 | `EdgeKtxSyncTest` | 尺寸表 ↔ 真实 WebP 头 ↔ KTX **三向逐张一致** + 4 倍数 |
| Kotlin 消费 | `SoftwareCanvasBackend*Test` | z 序、UV 直采、NaN/越界跳过、缺纹理跳过 |
| 生成物守卫 | `SpriteAtlasDefGeneratedTest` / `AtlasLayoutSyncTest` / `AtlasManifestSyncTest` / `SpriteCodegenSyncTest` | 期望表已删旧边缘段 |
| 相机契约 | `SectCameraStateTest` | outset 2500 的进入/钳制期望 |
| 人工验收 | 真机截图 | 三侧 + 两角拼接观感、缩到整岛的缩采样、显存 |

---

## 七、风险评估与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| 无 ASTC 设备落 RGBA（117MB 级） | 中 | 逐张失败降级；真机吃紧则 `MAP_KNOWN` 改 `maxDim`（单点） |
| 随机拼接出现可见硬缝 | 中 | 3 个真变体草带剖面差异 ≤9%；角块后绘覆盖；必要时合成器加 overlap 常量（单点） |
| ASTC 4×4 对平滑渐变产生块状伪影 | 中 | 边缘层常用缩放为缩采样，伪影不可见；真机可见则仅崖壁回落 RGBA |
| 缓存/生成物不同步（hash 死锁已实测） | 中 | 清 hash 恢复；**建议**产物存在性纳入 hash 判定 |
| 删旧素材遗漏引用 | 低 | 编译 + 4 个守卫测试 + 资源清单守卫全链路拦截 |

---

## 八、未来场景推演（≥6 个月档）

1. **上边缘补齐**：`IslandCliffPool` 加 `TOP` + 合成器加一段 + 1 张纹理，`stride=10` 契约不变。
2. **不规则岛屿轮廓**：合成器已收敛为 `emitSide(from,to)` 分段函数，未来输入边界掩码逐段调用。
3. **底座系统（FloatingIslandBase）**：崖壁外缘线（左环 x = -纹理宽、下环 y = mapH + 纹理高）
   由布局条目直接可读，无需耦合。
4. **多地图复用（秘境）**：合成器为纯函数 `(cols, rows, tileSize, seed)`，任意尺寸复用。
5. **iOS Metal**：Metal 原生支持 ASTC，KTX 数据直接复用；布局/契约零平台依赖。
6. **运营化风格**：换风格 = 换素材 + `MAP_KNOWN` 改名，渲染代码零改动。

---

## 九、技术债与偿还计划

- **本方案自身无新增技术债**：ASTC 压缩、mip 链、逐张降级、镜像去重全部落地，
  无"后续优化"尾巴。
- **预存事项（非本批引入，如实登记）**：
  1. `SectMapEdgeOverlay`（古风卷轴边缘装饰层）已停用 —— **已在本批删除**（全仓零引用）。
  2. **codegen 产物被 Gradle 之外删除 → 任务误判 UP-TO-DATE**（实测复现，非历史遗留）。
     精确根因：Gradle 的 UP-TO-DATE 判定只比较输入快照与历史，**不核对已声明产物是否
     仍在磁盘上** ⇒ 产物被 Gradle 之外的路径删除（并发构建、外部工具清 build/、IDE）后，
     脚本因内容 hash 未变跳过写文件 → 任务"成功"但产物缺失 → 编译报
     `Unresolved reference`。
     `docs/build-perf/baseline-20260814.md` §3.2 登记的修复只覆盖「删除发生在 Gradle
     执行链内」（hash 纳入 outputs 声明，使 Gradle 执行前清理会连带删 hash），
     **不覆盖 Gradle 之外的删除**。
     暴露面：`generateSpriteAtlasDef`（`core:engine`）与 `generateSpriteCode`（`app`）
     两个任务同族；产物均不入库，故合并/checkout 不触发，主要触发源是并发构建与外部清理。
     恢复：`Remove-Item android/core/engine/build/generated/sprite/.atlas-def.hash`
     （或 `.sprite-code.hash`）后重新构建。
     **本批未实施根治**：让任务感知产物缺失需每次构建执行一次存在性校验，与这两个
     任务的增量收益（省 2s node 启动）冲突；而暴露面窄且恢复命令明确，收益不抵成本。
     若后续并发构建成为常态，再按「产物存在性纳入任务输入」实施。
  3. `scripts/sprite-uid-map.json` 保留已删素材的 UID 墓碑（追加式设计，
     删除会使其它资源 UID 漂移，故不动）。
- **待真机验收**：显存占用、整岛缩采样观感、ASTC 块状伪影。

---

## 十、盲区自查

1. **岛面顶线（素材顶部草沿 46~67px）**：当前口径为「素材顶边贴地图边缘」，
   草沿叠进地图约 50px（落在 3 格边界树环内）。若真机观感不佳，改「草沿线对齐」
   = 合成器 `topInset` 常量 + UV 的 `v0`（**已预留，单点可调**）。
2. **下环与转角的水平重叠量**（`bottomStartRatio`/`bottomEndRatio = 0.5`）：
   实测转角实体岩体自内缘向内 0.36~0.93 宽，0.5 落在其起始之后；
   该值只影响重叠多少，不影响是否出现空隙（角块最后绘制覆盖）。**单点可调**。
3. **每帧 draw call**：按纹理分批，实测 128 格 8 条目跨 6 张纹理 ⇒ 约 6 次 draw。
   相比「逐格铺」方案（地面 quad 曾因 Adreno 驱动采样异常被禁用）风险更低，但
   真机低端 GPU 的批次开销仍需观测。
4. **未验证项**：真机显存、ASTC 伪影、整岛缩采样——列入真机验收批。
