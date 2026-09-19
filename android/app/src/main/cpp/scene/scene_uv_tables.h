// ============================================================
// GENERATED FILE — 由 scripts/build-atlas.mjs 自动生成，禁止手改。
// 图集布局源数据位于 build-atlas.mjs 的 LAYOUT 常量，运行
//   cd android && node scripts/build-atlas.mjs --codegen
// 重新生成。仓库内生成物（与 shaders.h 同策略）：桌面 GTest 与
// Android NDK 构建无需先跑 codegen 即可编译。
// ============================================================
#pragma once

#include <cstdint>

// ============================================================
// SceneStore 路径场景常量（重构方案 2026-09-17 R3.2/B10）——
// Kotlin 不再每帧传 SpriteAtlasDef 数组，UV 表由本头同源生成进 C++。
// 值与 Kotlin SpriteAtlasDef（TILE_UV_MAP 等）逐位一致：图集尺寸为
// 2 的幂，UV 除法在二进制浮点下精确（无舍入），双端同式同值。
// ============================================================
namespace scene {

inline constexpr int kAtlasW = 4096;
inline constexpr int kAtlasH = 4096;

// UV 向内收缩 0.5 texel（防 CLAMP_TO_EDGE + NEAREST 邻居渗色；
// 与旧 NativeBridge.cpp UV_EPSILON 同式同值）
inline constexpr float kUvEpsilon = 0.5f / 4096.0f;

// ── UV 表（归一化；[u0,v0,u1,v1] × N，与 SpriteAtlasDef 同源同序）──
// 瓦片 UV（按 TileType.index 直取；TILE_BUILDING 占位复刻 GROUND rect，同 Kotlin）
inline constexpr float kTileUv[] = {
    0.0f / 4096.0f, 0.0f / 4096.0f, 128.0f / 4096.0f, 128.0f / 4096.0f,
    136.0f / 4096.0f, 0.0f / 4096.0f, 264.0f / 4096.0f, 128.0f / 4096.0f,
    272.0f / 4096.0f, 0.0f / 4096.0f, 400.0f / 4096.0f, 128.0f / 4096.0f,
    408.0f / 4096.0f, 0.0f / 4096.0f, 536.0f / 4096.0f, 128.0f / 4096.0f,
    544.0f / 4096.0f, 0.0f / 4096.0f, 672.0f / 4096.0f, 128.0f / 4096.0f,
    2048.0f / 4096.0f, 0.0f / 4096.0f, 2176.0f / 4096.0f, 128.0f / 4096.0f,
    2184.0f / 4096.0f, 0.0f / 4096.0f, 2312.0f / 4096.0f, 128.0f / 4096.0f,
    2320.0f / 4096.0f, 0.0f / 4096.0f, 2448.0f / 4096.0f, 128.0f / 4096.0f,
    800.0f / 4096.0f, 0.0f / 4096.0f, 1056.0f / 4096.0f, 256.0f / 4096.0f,
    1064.0f / 4096.0f, 0.0f / 4096.0f, 1320.0f / 4096.0f, 256.0f / 4096.0f,
    0.0f / 4096.0f, 0.0f / 4096.0f, 128.0f / 4096.0f, 128.0f / 4096.0f,
};
inline constexpr int kTileUvCount = 11;
// 建筑 UV + 固定结构尾部（nameIdx = 建筑序；结构 nameIdx = kStructureNameBase + 序，同 Kotlin BUILDING_UV_MAP）
inline constexpr float kBuildingUv[] = {
    0.0f / 4096.0f, 512.0f / 4096.0f, 512.0f / 4096.0f, 1024.0f / 4096.0f,
    520.0f / 4096.0f, 512.0f / 4096.0f, 1032.0f / 4096.0f, 1024.0f / 4096.0f,
    1044.0f / 4096.0f, 516.0f / 4096.0f, 1172.0f / 4096.0f, 644.0f / 4096.0f,
    1560.0f / 4096.0f, 512.0f / 4096.0f, 2072.0f / 4096.0f, 1024.0f / 4096.0f,
    2080.0f / 4096.0f, 512.0f / 4096.0f, 2592.0f / 4096.0f, 1024.0f / 4096.0f,
    0.0f / 4096.0f, 1032.0f / 4096.0f, 512.0f / 4096.0f, 1544.0f / 4096.0f,
    520.0f / 4096.0f, 1032.0f / 4096.0f, 1032.0f / 4096.0f, 1544.0f / 4096.0f,
    1040.0f / 4096.0f, 1032.0f / 4096.0f, 1552.0f / 4096.0f, 1544.0f / 4096.0f,
    1560.0f / 4096.0f, 1032.0f / 4096.0f, 2072.0f / 4096.0f, 1544.0f / 4096.0f,
    3008.0f / 4096.0f, 1032.0f / 4096.0f, 4032.0f / 4096.0f, 2056.0f / 4096.0f,
    0.0f / 4096.0f, 1552.0f / 4096.0f, 512.0f / 4096.0f, 2064.0f / 4096.0f,
    520.0f / 4096.0f, 1552.0f / 4096.0f, 1032.0f / 4096.0f, 2064.0f / 4096.0f,
    1040.0f / 4096.0f, 1552.0f / 4096.0f, 1552.0f / 4096.0f, 2064.0f / 4096.0f,
    1560.0f / 4096.0f, 1552.0f / 4096.0f, 2072.0f / 4096.0f, 2064.0f / 4096.0f,
    2080.0f / 4096.0f, 1552.0f / 4096.0f, 2592.0f / 4096.0f, 2064.0f / 4096.0f,
    0.0f / 4096.0f, 2072.0f / 4096.0f, 512.0f / 4096.0f, 2584.0f / 4096.0f,
    520.0f / 4096.0f, 2072.0f / 4096.0f, 1032.0f / 4096.0f, 2584.0f / 4096.0f,
    1040.0f / 4096.0f, 2072.0f / 4096.0f, 1552.0f / 4096.0f, 2584.0f / 4096.0f,
    1560.0f / 4096.0f, 2072.0f / 4096.0f, 2072.0f / 4096.0f, 2584.0f / 4096.0f,
    3072.0f / 4096.0f, 512.0f / 4096.0f, 3840.0f / 4096.0f, 768.0f / 4096.0f,
};
inline constexpr int kBuildingUvCount = 20;
// 灵田作物三阶段 UV（按阶段序直取）
inline constexpr float kCropUv[] = {
    1384.0f / 4096.0f, 0.0f / 4096.0f, 1512.0f / 4096.0f, 128.0f / 4096.0f,
    1520.0f / 4096.0f, 0.0f / 4096.0f, 1648.0f / 4096.0f, 128.0f / 4096.0f,
    1656.0f / 4096.0f, 0.0f / 4096.0f, 1784.0f / 4096.0f, 128.0f / 4096.0f,
};
inline constexpr int kCropUvCount = 3;
// 云层精灵 UV（按 LAYOUT.clouds 声明序直取）
inline constexpr float kCloudUv[] = {
    0.0f / 4096.0f, 2816.0f / 4096.0f, 968.0f / 4096.0f, 3056.0f / 4096.0f,
    968.0f / 4096.0f, 2816.0f / 4096.0f, 1872.0f / 4096.0f, 3192.0f / 4096.0f,
    3088.0f / 4096.0f, 2816.0f / 4096.0f, 4064.0f / 4096.0f, 3008.0f / 4096.0f,
    0.0f / 4096.0f, 3240.0f / 4096.0f, 1048.0f / 4096.0f, 3456.0f / 4096.0f,
    1048.0f / 4096.0f, 3240.0f / 4096.0f, 1992.0f / 4096.0f, 3640.0f / 4096.0f,
};
inline constexpr int kCloudUvCount = 5;
// 石板道路 UV（按 LAYOUT.roads 声明序直取；emitRoadDrawOps 的 sprite 下标直取）
inline constexpr float kRoadUv[] = {
    2048.0f / 4096.0f, 2624.0f / 4096.0f, 2176.0f / 4096.0f, 2752.0f / 4096.0f,
    2184.0f / 4096.0f, 2624.0f / 4096.0f, 2216.0f / 4096.0f, 2720.0f / 4096.0f,
    2264.0f / 4096.0f, 2624.0f / 4096.0f, 2360.0f / 4096.0f, 2656.0f / 4096.0f,
};
inline constexpr int kRoadUvCount = 3;

// ── 双端共享渲染常量（与 SpriteAtlasDef.kt / TextureAtlas.h 同源）──
inline constexpr float kDecorQualityThreshold = 0.6f;
inline constexpr float kShadowOffsetTiles = 0.25f;
inline constexpr float kShadowAlpha = 0.2f;
inline constexpr float kTopdownYScale = 0.75f;

// ── 世界叠加层（overlay）视觉常量（R3.3/B11：网格线/预览框/选中/拆除高亮的
//    颜色、不透明度、线宽——C++ 侧生成叠加层几何消费本表，与 Kotlin 回滚臂同源）──
// 选中高亮金色 #FFD700（R=255/255）
inline constexpr float kGoldR = 1.0f;
// 选中高亮金色 #FFD700（G=215/255）
inline constexpr float kGoldG = 0.843f;
// 选中高亮金色 #FFD700（B=0/255）
inline constexpr float kGoldB = 0.0f;
// 选中高亮填充不透明度（金色半透明填充）
inline constexpr float kHighlightFillAlpha = 0.15f;
// 选中高亮描边不透明度
inline constexpr float kHighlightEdgeAlpha = 0.9f;
// 拆除未选中绿 #4CAF50（R=76/255）
inline constexpr float kDemolishGreenR = 0.298f;
// 拆除未选中绿 #4CAF50（G=175/255）
inline constexpr float kDemolishGreenG = 0.686f;
// 拆除未选中绿 #4CAF50（B=80/255）
inline constexpr float kDemolishGreenB = 0.314f;
// 拆除选中红 #F44336（R=244/255）
inline constexpr float kDemolishRedR = 0.957f;
// 拆除选中红 #F44336（G=68/255）
inline constexpr float kDemolishRedG = 0.267f;
// 拆除选中红 #F44336（B=54/255）
inline constexpr float kDemolishRedB = 0.212f;
// 拆除填充不透明度（0x66 = 40% 半透明）
inline constexpr float kDemolishFillAlpha = 0.4f;
// 拆除描边不透明度
inline constexpr float kDemolishEdgeAlpha = 1.0f;
// 可放置 #4CAF50（R=76/255）
inline constexpr float kPreviewGreenR = 0.298f;
// 可放置 #4CAF50（G=175/255）
inline constexpr float kPreviewGreenG = 0.686f;
// 可放置 #4CAF50（B=80/255）
inline constexpr float kPreviewGreenB = 0.314f;
// 不可放置 #F44336（R=244/255）
inline constexpr float kPreviewRedR = 0.957f;
// 不可放置 #F44336（G=68/255）
inline constexpr float kPreviewRedG = 0.267f;
// 不可放置 #F44336（B=54/255）
inline constexpr float kPreviewRedB = 0.212f;
// 占地框填充不透明度（0x59 ≈ 35% 半透明）
inline constexpr float kPreviewBoxFillAlpha = 0.35f;
// 占地框描边不透明度（0xE6 ≈ 90%）
inline constexpr float kPreviewBoxEdgeAlpha = 0.9f;
// 网格线 #E4DDD0（R=228/255）
inline constexpr float kGridR = 0.894f;
// 网格线 #E4DDD0（G=221/255）
inline constexpr float kGridG = 0.867f;
// 网格线 #E4DDD0（B=208/255）
inline constexpr float kGridB = 0.816f;
// 网格线不透明度
inline constexpr float kGridAlpha = 1.0f;
// 高亮线宽（格数）：max(2px, tileSize×0.06) 的格数分量
inline constexpr float kHighlightLineWidthTiles = 0.06f;
// 高亮线宽下限（屏幕像素）
inline constexpr float kHighlightLineMinPx = 2.0f;
// 网格线世界线宽下限（防退化 quad）
inline constexpr float kGridLineWidthMinWorld = 0.5f;
// 网格线目标屏幕线宽（2 物理屏像素，防降采样整条丢弃）
inline constexpr float kGridLineWidthPx = 2.0f;
// 缩放下限（线宽除 scale 的除零防御）
inline constexpr float kOverlayMinScale = 0.001f;

// ── 瓦片分类（装饰区间/显示尺寸/绘制层/越界余量——绘制核心按表直取，
//    禁止在渲染代码硬编码瓦片序号）──
inline constexpr int kTileGround = 0;
inline constexpr int kTileTypeCount = 11;
inline constexpr int kDecorTileMin = 1;
inline constexpr int kDecorTileMax = 9;
// 装饰越界余量（格）：左右各 (maxW−1)/2 上取整、向上 maxH−1 上取整
inline constexpr int kDecorMarginCols = 1;
inline constexpr int kDecorMarginRows = 3;
inline constexpr float kTileSpriteW[] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 2.0f, 2.0f, 1.0f};
inline constexpr float kTileSpriteH[] = {1.0f, 1.157f, 1.064f, 1.157f, 1.42f, 0.959f, 0.909f, 1.105f, 3.291f, 2.791f, 1.0f};
inline constexpr int kTileObjectLayer[] = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0};
// 地面草皮变体索引（渲染器把变体格映射到自身地面纹理）
inline constexpr int kGroundVariantCount = 1;
inline constexpr int kGroundVariants[] = {0};

// ── 占地尺寸表（footprint_table.h 同源：建筑按 nameIdx；固定结构单列）──
inline constexpr int kFootprintW[] = {4, 4, 1, 4, 5, 6, 6, 4, 4, 18, 6, 4, 4, 4, 4, 6, 6, 4, 6};
inline constexpr int kFootprintH[] = {4, 3, 1, 2, 3, 4, 3, 2, 2, 13, 3, 3, 2, 4, 4, 6, 4, 3, 5};
inline constexpr int kStructureNameBase = 19;
inline constexpr int kStructureFpW[] = {6};
inline constexpr int kStructureFpH[] = {2};

}  // namespace scene
