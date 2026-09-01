#pragma once

#include <cstdint>
#include "gamecore/map/road_system.h"

// ============================================================
// 道路渲染合成器（计划 v2 阶段 6 / 批次 R 剩余：渲染合成器物理下沉）
//
// 逐格道路合成的**单一权威**：给定 4-bit 邻接掩码，产出该格的绘制
// 操作序列（精灵 + 格内局部几何）。双端渲染路径只消费操作序列：
//   - C++ Vulkan：NativeBridge.drawAllTiles 道路段（操作 → SpriteBatcher）
//   - Kotlin Canvas：SoftwareCanvasBackend.drawRoadsToCanvas（操作 → 图集源矩形）
// 此前双端各自硬编码同一套合成顺序（主体→描边条→转角件→十字中心），
// 由注释约定对齐；本文件将其收敛为一份可测的权威实现。
//
// 与生成式图集解耦：合成器只产出**语义精灵枚举**（RoadSprite），不持有
// 任何 UV/像素坐标/精灵名——图集布局（SpriteAtlasDef.ROAD_RECTS 声明序、
// NativeBridge roadUVMap 索引、Kotlin RoadCompositorBridge.SPRITE_KEYS）
// 由各端按枚举序号映射，图集改版不动合成器。
//
// 几何约定：格内局部**整型像素**（与 Kotlin Canvas 烘焙的整型算术逐位
// 一致）；运行时 tileSize 恒为 GameConfig.TILE_SIZE=36（4 的倍数），
// Vulkan 侧转 float 几何完全一致。quarter = tileSize/4 取整除。
//
// 零依赖（仅 road_system.h）、纯函数，桌面 GTest 直接覆盖。
// ============================================================
namespace gamecore::map {

// ── 道路精灵语义枚举 ──────────────────────────────────────────
// 序号即 ROAD_RECTS 声明序（Kotlin SpriteAtlasDef.ROAD_RECTS /
// ROAD_UV_MAP 与 C++ 纹理图集同源生成），改序须三端同步。
enum class RoadSprite : int {
    BASE = 0,         // 横向直路主体 road_base
    BASE_V,           // 纵向直路主体 road_base_v
    JUNCTION,         // 转角/T/十字路口拼接主体 road_junction
    EDGE_H,           // 水平描边条 road_edge_h
    EDGE_V,           // 垂直描边条 road_edge_v
    CORNER_TR,        // 右上外缘转角件 road_corner_tr
    CORNER_TL,        // 左上外缘转角件 road_corner_tl
    CORNER_BR,        // 右下外缘转角件 road_corner_br
    CORNER_BL,        // 左下外缘转角件 road_corner_bl
    CROSS_CENTER      // 十字中心装饰 road_cross_center
};

inline constexpr int kRoadSpriteCount = 10;

/// 单格最多产出的操作数：主体 1 + 描边条 4 + 转角件 4 + 十字中心 1。
inline constexpr int kMaxRoadDrawOpsPerTile = 10;

/// 单格绘制操作：sprite 语义枚举 + 格内局部整型像素几何（可为负——
/// 十字中心装饰外溢半格）。
struct RoadDrawOp {
    RoadSprite sprite;
    int x, y, w, h;
};

/// 按邻接掩码产出单格道路绘制操作序列（追加写入 out，返回操作数）。
///
/// 顺序契约（与 Canvas 烘焙顺序、Vulkan 批量入批顺序一致，绘制层叠
/// 语义相同）：主体 → 外缘描边条（上/下/左/右）→ 外缘转角件
/// （左上/右上/左下/右下）→ 十字中心装饰。
///
/// mask=0（非道路格）调用方应跳过；本函数仍返回主体 1 op
/// （SINGLE 主体 + 全描边），由调用方保证仅在 mask != 0 时调用。
inline int emitRoadDrawOps(int mask, int tileSize, RoadDrawOp* out) {
    const RoadTileType type = tileTypeForBitmask(mask);
    const int border = roadBorderMask(mask);  // 描边侧 = 无道路邻居的方向
    const int quarter = tileSize / 4;         // 描边条 1/4 格厚（整型除法）
    int n = 0;

    // 主体：直路按朝向取 base/base_v，转角/T/十字用路口拼接素材
    const RoadSprite base = type == RoadTileType::HORIZONTAL ? RoadSprite::BASE
                          : type == RoadTileType::VERTICAL   ? RoadSprite::BASE_V
                                                             : RoadSprite::JUNCTION;
    out[n++] = {base, 0, 0, tileSize, tileSize};

    // 外缘描边条（1/4 格厚）：仅无道路邻居的边——道路内部相邻格不重复描边
    if (border & kDirUp)    out[n++] = {RoadSprite::EDGE_H, 0, 0, tileSize, quarter};
    if (border & kDirDown)  out[n++] = {RoadSprite::EDGE_H, 0, tileSize - quarter, tileSize, quarter};
    if (border & kDirLeft)  out[n++] = {RoadSprite::EDGE_V, 0, 0, quarter, tileSize};
    if (border & kDirRight) out[n++] = {RoadSprite::EDGE_V, tileSize - quarter, 0, quarter, tileSize};

    // 外缘转角件（相邻两外缘相交的外角）
    if ((border & kDirUp) && (border & kDirLeft))
        out[n++] = {RoadSprite::CORNER_TL, 0, 0, quarter, quarter};
    if ((border & kDirUp) && (border & kDirRight))
        out[n++] = {RoadSprite::CORNER_TR, tileSize - quarter, 0, quarter, quarter};
    if ((border & kDirDown) && (border & kDirLeft))
        out[n++] = {RoadSprite::CORNER_BL, 0, tileSize - quarter, quarter, quarter};
    if ((border & kDirDown) && (border & kDirRight))
        out[n++] = {RoadSprite::CORNER_BR, tileSize - quarter, tileSize - quarter, quarter, quarter};

    // 十字中心装饰（居中，约 2×2 格视觉，外溢半格）
    if (type == RoadTileType::CROSS)
        out[n++] = {RoadSprite::CROSS_CENTER, -tileSize / 2, -tileSize / 2,
                    tileSize * 2, tileSize * 2};

    return n;
}

}  // namespace gamecore::map
