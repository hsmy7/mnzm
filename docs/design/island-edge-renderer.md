# 浮空岛屿地图边缘渲染系统（Island Edge Renderer）设计文档

> 日期：2026-09（当前会话）
> 范围：宗门地图（Sect Map）四周「可行走地面 → 浮空岛悬崖」边缘的模块化渲染系统。
> 前置事实：项目已 C++ 化（game-core 引擎 + C++ 渲染器 + Kotlin Compose UI 双后端），
> 宗地图 128×128 格 × 48px（6144×6144 世界像素），地图内部已含地面瓦片/道路/建筑/植被/NPC。

---

## 一、背景与目标

### 需求要点（用户提供）
1. 地图四周增加「浮空岛屿边缘」：草地顶部 → 悬崖岩石，四方向独立、可无限拼接，四角自然连接。
2. 使用**模块化精灵**（切割自 PNG 图集，不得整图绘制），分类：top / bottom / left / right / corner{top_left,top_right,bottom_left,bottom_right} / variants。
3. 边缘紧贴地图可行走区：无缝隙、不覆盖地图内部、不改碰撞/寻路；按地图 Bounds 自动计算位置。
4. 与独立「FloatingIslandBase 底座」系统自然衔接（底座系统将来实现，本系统预留接口）。
5. 动态适配：地图尺寸变化时重建布局；Camera 平移/缩放不重建。
6. 视觉效果：整体连续、局部自然变化（阴影/岩层/苔藓/藤蔓/碎石），不出现人工墙式平直悬崖、不出现明显重复纹理、接缝、高度突变。
7. 移动端性能：初始化加载纹理、布局预计算、无每帧分配/重建、复用纹理、尽量少 Draw Call。
8. 为本系统的未来能力预留：不规则岛屿轮廓（Edge Mask / Boundary 数据驱动）。

### 成功标准（自检清单 18 项，见 §9）

---

## 二、技术方案

### 2.1 素材语义判读（对提供图集 1374×1145 的逐格分析结论）

图集为「2.5D 十字剖面」风格：每一格是一块「草皮顶面 + 岩石侧面」切片。客观分类（边框绿意占比分析 + 拼接验证）：

| 行 | 素材 | 判读 |
|---|---|---|
| 行1/行2 中部各 5 块 | 草带居上、岩齿下悬 | **上边缘**切片（grass-top 带为环外线） |
| 行1 左右端 / 行2 左右端 | 草带呈 L 形 | **上角**变体（top+side 两臂） |
| 行3/4 左 2 竖条 | 草带贴右、岩在左 | **左边缘**竖切片 |
| 行3/4 右 2 竖条 | 草带贴左、岩在右 | **右边缘**竖切片 |
| 行3/4 中部 2×2 | L 形草带 | **四角**（左上/右上/左下/右下） |
| 行5 中部 6 块 | 草带居上、岩下悬（无苔藓变体） | **下边缘**切片 |
| 行5 两端 2 块 | 竖切片（短） | 左/右边缘短变体 |
| 行6 7 块 | 纯岩块 | 悬浮碎石变体 |

**拼接验证**：按「草带顶线对齐、无间距拼接」实际拼图（见实现阶段验证图），草带满宽且顶线对齐 → 横向无缝；岩齿轮廓各异 → 自然咬合。结论成立。

### 2.2 环形拼装约定（世界坐标，Y 向下；地图地面 [0,W]×[0,H]）

| 环 | 锚定 | 说明 |
|---|---|---|
| 上环 | 槽位**底边**贴 y=0；槽高归一 224px（4.67 格，等比拉伸） | 草带顶线恒 y=-224；岩齿落至 y=0，由地面层覆盖接缝 |
| 下环 | 槽位**顶边**贴 y=H；自然高度 | 草带紧贴地图下边；岩齿向下自然悬垂 |
| 左环 | 槽位**右边**贴 x=0；自然尺寸 | 草带竖直贴地图左边 |
| 右环 | 槽位**左边**贴 x=W；自然尺寸 | 草带竖直贴地图右边 |
| 角 TL/TR | 槽底贴 y=0，槽右/左贴 x=0/W；高度归一 224 | 两臂与相邻环形草带线对齐 |
| 角 BL/BR | 槽顶贴 y=H | 同上 |
| 碎石 | 确定性散布于环外天空带 | 每边 3 枚 + 角区各 1 枚 |

**拼接保证（避免"草地断层/透明缝隙/重叠错位"）**：
- 草带线 = 槽边缘线：上/下环草带顶线 = 槽顶/槽底；左/右环草带贴边线 = 槽右/左边缘；四角同规则 → 所有相邻素材的草带线按构造重合。
- 环长适配：边环按素材自然宽/高顺序拼接，{K = ceil(长度/平均宽)|并按实际宽累进} 铺满；末块允许越入角区（角块后绘制覆盖），**不允许出现缺口**。
- 上环（唯一非自然高度环）在**图集装配期**把素材等高拉伸到 224（总高一致），运行时零拉伸、逐像素 1:1。

### 2.3 架构（对齐项目既有「渲染合成器下沉 C++ 单一权威」先例——RoadCompositor）

```
MapPreloadData(seed/dims/tileSize)
        │  remember(seed, dims) 一次性
        ▼
IslandEdgeBridge (core:engine, 无 Android 依赖)──JNI──▶ C++ gamecore::map::island_edge.h
        │                                                      （纯函数布局合成器, GTest 覆盖）
        ▼
FloatArray [spriteIdx, x, y, w, h] × N   ← 布局稳定引用（≈120 条）
        ▼
RenderFrame.islandEdgeData
        ├─ Vulkan/GLES: VulkanRenderBackend → NativeBridge.drawIslandEdges(edgeData, edgeUVMap, atlasTexId)
        └─ Canvas:      SoftwareCanvasBackend.drawIslandEdges（同数组、同 z 序）
```

- **布局计算单次执行**（地图尺寸/种子变化时）；Camera 平移/缩放只改相机，零重建（§8 验证项 9~11）。
- **双端消费同一布局**——像素级一致（与 tileData/roadData 同模式）。
- **降级契约**：native 库未加载（JVM 测试环境/极端损坏）→ `IslandEdgeBridge.compose()` 返回 null → 双端跳过边缘层（与 RoadCompositorBridge 同契约；生产 AUTHORITATIVE 恒加载 native-game-core）。
- Z 序（按照设计定稿）：`天空 → 岛屿边缘 → 地图地面 → 道路/建筑/作物/植被/NPC → 云层（保持现状：世界顶部前景云）`。边缘在世界空间（相机平移/缩放跟随地图）；云层维持现有"前景"语义不变（用户建议的 Z 序按现状坐标系校准，见需求 §八"最终按项目实际坐标系确定"）。
- **Camera 行为（2026-09-07 实测「无边缘图」根因修复）**：边缘带位于世界矩形外侧，而 `SectCameraState.clampPosition` 原把相机锁死在世界矩形内（视口 < 世界时 cameraX ∈ [0, W−visibleW]）——拖到地图边缘视口仍与边缘带零交集 → 边缘永不可见（仅缩小到整岛视图例外）。修复：clamp 外扩 `ISLAND_EDGE_VISIBLE_OUTSET=400px`（悬崖 224/186/147 + 碎石散布带 +40~160 + 石高）——拖到地图边缘即见悬崖；视口 ≥ 世界 + 2×outset 时整岛（含悬崖环绕）居中悬浮天际（天空语义保留）。契约测试：SectCameraStateTest 新增「边缘带可进入/整岛居中」两用例，既有 pan/clamp 用例期望同步外扩。
- 底座衔接接口：边缘层已锚定环外轮廓（上环 y=-224、下环底缘 = H+素材自然深度、左右环外缘 = 素材宽），底座系统只需贴合这些外缘线绘制；布局数据可经 `IslandEdgeLayout` 常量读取（§7）。

### 2.4 关键类/接口

**C++（gamecore/include/gamecore/map/island_edge.h，仅头文件、纯函数、零 Android 依赖）**

```cpp
namespace gamecore::map {
// 固定 9 池顺序：TOP, BOTTOM, LEFT, RIGHT, CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, ROCKS
struct IslandEdgeConfig {
    int32_t cols, rows, tileSize, seed;   // 地图参数（seed 决定变体确定性）
    int32_t mapW, mapH;                   // 世界像素
    int32_t spriteCount;                  // 全池精灵数
    const float* spriteW; const float* spriteH;   // 每精灵绘制尺寸（= 图集槽位尺寸）
    int32_t poolCount;                    // = 9
    const int32_t* poolBase;  const int32_t* poolCountArr;  // 每池基址/数量（绝对索引）
};
struct IslandEdgePiece { float x, y, w, h; int32_t sprite; };  // 世界像素 + 绝对精灵索引
int32_t computeIslandEdgeLayout(const IslandEdgeConfig&, IslandEdgePiece* out, int32_t maxPieces);
int32_t islandEdgeMaxPieces(const IslandEdgeConfig&);   // 容量上界（预分配）
}
```

- 内部以 `emitSide(cfg, side, from, to, ...)` 组织——**不规则轮廓扩展点**：矩形 = 4 条直段 + 4 角；未来不规则轮廓键入 per-side 分段即可。
- 变体选择：`hash(seed, side, index) % poolSize`，附加"不与上一块相同"约束（防相邻重复）。

**Kotlin（core:engine）**

- `SpriteAtlasDef`（build-atlas.mjs 生成）：`ISLAND_EDGE_RECTS: List<Pair<String, SpriteRect>>`（37 项，声明序 = C++ 精灵索引序）+ `ISLAND_EDGE_UV_MAP`。
- `IslandEdgeBridge`（RoadCompositorBridge 同构）：`compose(cols, rows, tileSize, seed): FloatArray?`。
- `GameCoreBridge.nativeIslandEdgeCompose(cols, rows, tileSize, seed, spriteSizes, poolBase, poolCount): FloatArray`。
- `RenderFrame.islandEdgeData: FloatArray?`（[sprite, x, y, w, h] × N）。

**渲染端**

- `NativeBridge.drawIslandEdges(edgeData, edgeUVMap, atlasTexId)`：SpriteBatcher 批处理（与地面同批纹理），可见性剔除，GAP_EPSILON/UV_EPSILON/淡入 alpha 与瓦片层同式；在 drawAllTiles 之前调用（z 序）。
- `SoftwareCanvasBackend`：`composeVisibleChunks` 内 sky 之后、chunk 之前插边缘绘制（同布局数组、同 sourceScale 修正、同 fade）。

### 2.5 图集布局（build-atlas.mjs LAYOUT 增量，4096×4096）

- 上下环：10 + 6 块，槽高 224（上环）/自然高（下环），槽宽 = 素材自然宽；行 y=3696。
- 左右环：6 块自然尺寸；行 y=2624（x≥2560，与道路/云层错开）。
- 四角：8 块（3+3+1+1 变体），槽高 224；行 y=3040。
- 碎石：7 块自然尺寸；行 y=3280。
- 全部槽位满足 mipGutter ≥8px 间距；运行时 2048 位图（0.5×）内不越界。
- 素材名：`ie_top_1..10 / ie_bottom_1..6 / ie_left_1..3 / ie_right_1..3 / ie_corner_tl_1..3 / ie_corner_tr_1..3 / ie_corner_bl_1 / ie_corner_br_1 / ie_rock_1..7`。

### 2.6 资源流程（对齐 rules/static-resources.md 权威管线）

- 源图：`D:\模拟宗门美术素材\地图边缘\*.png`（一次性切片脚本把上传图集去棋盘格底 → 逐格裁剪 PNG）。
- `scripts/source-mapping.json` 新增 37 条（drawable=`island_edge_*`，bake=preserve，双模块）。
- `scripts/import-art-assets.mjs` 烘焙无损 WebP → `feature/game` 与 `app` 双模块 drawable-nodpi。
- build-atlas.mjs LAYOUT 注册（§2.5）→ codegen 产出 SpriteAtlasDef.kt / TextureAtlas.h（C++ MAP_SPRITES 增补）；KTX ASTC 由 generateAstcAtlas 重生成（本机已补装 astcenc 5.7.0 至 `scripts/tools/astcenc/bin/`，2026-09-07）。
- **不入 SpriteResRegistry**：与既有地图图集精灵（map_grass_1 / road_* / cloud_*）同一模式——地图专用精灵只经图集着色管线，不进入 UI 精灵注册表（§13.3 语义同理，UI 精灵走注册表）；在方案评审中明确该边界。

---

## 三、影响范围清单

| 文件 | 变更类型 | 变更说明 |
|---|---|---|
| `android/scripts/source-mapping.json` | 修改 | 新增 37 条 地图边缘 drawable 映射 |
| `android/scripts/source-mapping.json`（模板/脚手架） | 视需 | 若 scaffold 覆盖丢条目则手补（守卫校验） |
| `android/app/src/main/res/drawable-nodpi/island_edge_*.webp` | 新增 | 37 个无损 WebP（模块 A） |
| `android/feature/game/src/main/res/drawable-nodpi/island_edge_*.webp` | 新增 | 37 个无损 WebP（模块 B） |
| `D:\模拟宗门美术素材\地图边缘\*.png` | 新增 | 37 个去背景切片源图 |
| `android/scripts/build-atlas.mjs` | 修改 | LAYOUT 增 islandEdges 池/rect/尺寸 + 生成器（SpriteAtlasDef.kt / TextureAtlas.h）产物扩展 |
| `android/core/engine/build/generated/sprite/.../SpriteAtlasDef.kt` | 生成 | `ISLAND_EDGE_RECTS` / `ISLAND_EDGE_UV_MAP` |
| `android/app/build/generated/sprite/TextureAtlas.h` | 生成 | `MAP_SPRITES` 增 37 条 + 常量 |
| `android/core/engine/src/main/java/.../render/SpriteAtlasDef` 消费侧 | 无 | 消费生成物 |
| `android/app/src/main/cpp/gamecore/include/gamecore/map/island_edge.h` | 新增 | C++ 布局合成器（单一权威） |
| `android/app/src/main/cpp/gamecore/test/island_edge_test.cpp` | 新增 | GTest |
| `android/app/src/main/cpp/gamecore/test/CMakeLists.txt` | 修改 | 登记测试 |
| `android/app/src/main/cpp/GameCoreBridge.h/.cpp` | 修改 | `nativeIslandEdgeCompose` JNI |
| `android/core/engine/src/main/java/.../nativebridge/GameCoreBridge.kt` | 修改 | external 声明 |
| `android/core/engine/src/main/java/.../render/IslandEdgeBridge.kt` | 新增 | Kotlin 桥（RoadCompositorBridge 模式） |
| `android/core/engine/src/main/java/.../render/RenderFrame.kt` | 修改 | `islandEdgeData` 字段 |
| `android/core/domain/.../model/MapPreloadData.kt` | 修改 | 增 `seed`（布局变体确定性来源） |
| `android/feature/game/.../SectMapController.kt` | 修改 | buildSectMap 携带 seed |
| `android/feature/game/.../MainGameScreen.kt` | 修改 | 边缘布局构建（remember）与参数管道 |
| `android/feature/game/.../SectMapViewport.kt` | 修改 | params 透传 islandEdgeData |
| `android/feature/game/.../sect/SectAtlasAssembler.kt` | 修改 | 新增边缘精灵槽位（drawable→slot） |
| `android/feature/game/.../sect/VulkanRenderBackend.kt` | 修改 | renderFrame 调 drawIslandEdges（z 序） |
| `android/app/src/main/cpp/NativeBridge.cpp` | 修改 | `drawIslandEdges` JNI 实现 |
| `android/feature/game/.../sect/SoftwareCanvasBackend.kt` | 修改 | 边缘绘制（z 序） |
| `android/core/engine/src/test/.../SpriteAtlasDefGeneratedTest.kt` | 修改 | ISLAND_EDGE_RECTS 期望 |
| `android/feature/game/src/test/.../SoftwareCanvasBackendTest*.kt` | 视需 | 边缘绘制像素/顺序断言（若测试基建许可） |
| `docs/design/island-edge-renderer.md` | 新增 | 本文档 |
| `android/app/src/main/assets/changelog_entries.json` | 修改 | 玩家向更新日志 |
| `CHANGELOG.md` | 修改 | 开发者向更新日志 |

**经济影响**：无（纯视觉，不涉及货币/奖励）。
**iOS 影响**：布局合成器在纯 C++（零 Android 依赖）、消费契约在纯 Kotlin data class —— Metal 后端直接复用同一 `RenderFrame`/同一布局算法；无平台独占 API。

---

## 四、兼容性分析

- **存档**：零影响（纯渲染数据，不进入游戏状态/序列化；`MapPreloadData` 仅内存预加载数据，非存档结构——确认其未参与存档序列化）。
- **序列化**：无 schema 变更；`Seed` 字段仅内存传递。
- **双后端**：Vulkan / GLES / Canvas 三路径同消费同一布局数组，z 序一致。
- **低端设备**：边缘层为世界空间静态合成（≈120 顶点 quad，2 次 draw 内的批），剔除同瓦片层；不受装饰 LOD 影响（属地图本体视觉）；无逐帧分配（布局数组预计算 + 渲染循环复用）。
- **Vulkan 硬边界**：纹理仍为单图集（新增槽位并入现有 4096 图集，无新增管线/采样器）。

---

## 五、测试方案

| 层 | 测试 | 覆盖 |
|---|---|---|
| C++ GTest | `island_edge_test` | ①上环无限拼接（K=ceil，末块越角、矩形锚定不变式）②下/左/右同理 ③四角锚定 ④20×20 与 128×128 尺寸适配 ⑤种子确定性/变体无相邻重复 ⑥rng 无关（纯 hash）⑦边界：1×1 地图、极长边 |
| Kotlin 生成物 | `SpriteAtlasDefGeneratedTest` | ISLAND_EDGE_RECTS 37 项与期望全等（含越界/负坐标） |
| Kotlin 桥 | `IslandEdgeBridgeTest`（JVM） | native 缺失 → null 降级；格式步长常量 |
| 渲染一致性 | SoftwareCanvasBackend（现有基建扩展） | 边缘绘制在 sky 后/chunk 前（z 序）、fade 生效、剔除正确 |
| 人工验收 | 拼装验证图 + 真机截图 | 18 项自检 |

---

## 六、风险评估与兜底

| 风险 | 等级 | 兜底 |
|---|---|---|
| 素材接缝（草带线/岩齿咬合）在中高缩放不完美 | 中 | 已按"草带线=槽边缘线"构造性保证 + 拼装验证图；若个别变体欠佳，from build-atlas LAYOUT 单独剔除该变体（无代码改动） |
| native 库缺失 → 边缘不可见 | 低 | 同道路层降级契约；生产恒加载；JVM 测试环境天然无渲染 |
| astcenc 缺失（本机）→ KTX 无法重生成 | 已解决 | 本机已补装 astcenc 5.7.0（scripts/tools/astcenc/bin/）；KTX/manifest 已重生成并通过 AtlasManifestSyncTest |
| 图集扩容挤占 4096 边界 | 低 | 已按行分布规划（最大 x≈3955/y≈3920），gutter 守卫测试覆盖 |
| 性能恶化（若未来不规则轮廓 + 大地图） | 低 | 布局数组仍为一次性预计算；绘制仍批处理；预留 LOD 阈值（现不启用，避免过度设计） |

---

## 七、未来场景推演（≥6 个月档）

1. **不规则岛屿轮廓**：`emitSide(from,to)` 已预留；未来输入边界掩码 → 逐段调用 → 角点由掩码判定。布局仍一次性预计算；图集不变。
2. **FloatingIslandBase 底座系统接入**：底座绘制于 y ≥ H + 下环自然深度之外，边缘外缘线即底座顶接线；本系统不耦合底座（仅输出锚定常量）。
3. **多地图（秘境独立地图）复用**：布局纯函数 `(cols, rows, tileSize, seed)` —— 任何尺寸直接复用。
4. **平台扩张（iOS Metal）**：布局/契约零平台依赖，直接复用。
5. **运营/活动**：无需变体配置化开关（视觉本体），若需"庆典风格边缘"可经 LAYOUT + 池替换实现，不改代码。

---

## 八、技术债与偿还计划

- **无技术债**：本方案按最终态一次性实现（无"后续优化"尾巴）。
- 一次性切片脚本与临时验证脚本在任务完成后清理（用户公约 #13）。
- 登记 2 项环境相关事项：①本机 astcenc 缺失（KTX 重生成受限）；②既有 `SectMapEdgeOverlay`（古风卷轴边缘装饰覆盖层）已停用但未删除——本次不触碰，建议后续删除（死代码）。

---

## 九、自检清单映射（用户 §十四 18 项）

1~5：布局不变式（GTest）；6~7：锚定构造 + 拼装验证；8：外缘线常量（§2.3）；9~11：布局一次性 + Camera 不触发（§2.3）；12~13：变体无相邻重复 + 草带线恒定；14~15：无逐帧分配/单纹理复用；16：仅渲染层、不触 Tile/寻路（RenderFrame 只读数据）；17：布局数组为纯值对象随 MapPreloadData 生命周期；18：C++ 单一权威、双端同源。
