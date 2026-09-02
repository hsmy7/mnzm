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
//
// 设计（2026-08-32 重构：单一主体 + 直路免旋转 + 按方向边缘）：
//   1. 每格恒有 1 个固定主体（road_body，方石板，不随方向旋转）。
//   2. 边缘条 = 1/6 格厚 × 1/2 格长；每条为「竖着为基准」的竖条，横道取预烘焙的
//      旋转 90° 横条（EDGE_H）。
//   3. 仅直行格（横/竖，含死路端点）在**无道路邻居**的横向侧出边缘（每侧 2 条拼接）；
//      T 中心仅在**缺邻居的那一侧**出 1 面（方向随该侧）；转角在两个开放侧各出
//      2 条（允许相邻，两条垂直边缘在转角区重叠覆盖、**完全在格内不外溢**）；
//      孤格固定出左右轴 2 面；十字中心无边缘。
//   4. 与生成式图集解耦：合成器只产出语义精灵枚举（RoadSprite），不持有任何
//      UV/像素坐标/精灵名——图集布局（SpriteAtlasDef.ROAD_RECTS 声明序、
//      NativeBridge roadUVMap 索引、Kotlin RoadCompositorBridge.SPRITE_KEYS）
//      由各端按枚举序号映射，图集改版不动合成器。
//
// 几何约定：格内局部**整型像素**（与 Kotlin Canvas 烘焙的整型算术逐位一致）；
// 运行时 tileSize 恒为 GameConfig.TILE_SIZE=36。edgeW = tileSize/6（36/6=6），
// edgeL = tileSize/2 = 18。Vulkan 侧转 float 几何完全一致。
//
// 零依赖（仅 road_system.h）、纯函数，桌面 GTest 直接覆盖。
// ============================================================
namespace gamecore::map {

// ── 道路精灵语义枚举 ──────────────────────────────────────────
// 序号即 ROAD_RECTS 声明序（Kotlin SpriteAtlasDef.ROAD_RECTS /
// ROAD_UV_MAP 与 C++ 纹理图集同源生成），改序须三端同步。
enum class RoadSprite : int {
    BODY = 0,     // 道路主体（方石板，恒用，不随方向旋转）road_body
    EDGE_V = 1,   // 竖直边缘条（1/6 格厚 × 1/2 格长）road_edge_v
    EDGE_H = 2    // 水平边缘条（1/2 格宽 × 1/6 格厚，edge_v 预烘焙旋转 90°）road_edge_h
};

inline constexpr int kRoadSpriteCount = 3;

/// 单格最多产出的操作数：主体 1 + 最多 2 侧 × 2 条 + 内凹角交汇块（转角格 6）。
inline constexpr int kMaxRoadDrawOpsPerTile = 6;

/// 单格绘制操作：sprite 语义枚举 + 格内局部整型像素几何（全在 [0, tileSize] 内）。
struct RoadDrawOp {
    RoadSprite sprite;
    int x, y, w, h;
};

/// 边缘条几何参数：edgeW = 1/6 格厚（垂直于道路方向），edgeL = 1/2 格长（沿道路方向）。
inline int roadEdgeWidth(int tileSize) { return tileSize / 6; }
inline int roadEdgeLength(int tileSize) { return tileSize / 2; }

/// 按邻接掩码产出单格道路绘制操作序列（追加写入 out，返回操作数）。
///
/// 顺序契约（与 Canvas 烘焙顺序、Vulkan 批量入批顺序一致，绘制层叠语义相同）：
/// 主体 → 边缘条。mask=0（非道路格）调用方应跳过；本函数仍返回主体 1 op
/// （SINGLE 主体 + 左右轴边缘），由调用方保证仅在 mask != 0 时调用。
inline int emitRoadDrawOps(int mask, int tileSize, RoadDrawOp* out) {
    const RoadTileType type = tileTypeForBitmask(mask);
    const int border = roadBorderMask(mask);  // 描边侧 = 无道路邻居的方向
    const int edgeW = roadEdgeWidth(tileSize);   // 1/6 格厚
    const int edgeL = roadEdgeLength(tileSize);  // 1/2 格长
    int n = 0;

    // 主体：恒定方石板，full cell（先画，后画边缘压在主体边上）
    out[n++] = {RoadSprite::BODY, 0, 0, tileSize, tileSize};

    // 竖直边缘（2 条）；vx 为该侧 x 坐标
    auto emitV = [&](int vx, int y0, int y1) {
        // 拆成 2 段：首段 1/2 格长，末段补齐（转角处两垂直边缘在转角区重叠，完全在格内）
        out[n++] = {RoadSprite::EDGE_V, vx, y0, edgeW, edgeL};
        out[n++] = {RoadSprite::EDGE_V, vx, y0 + edgeL, edgeW, (y1 - (y0 + edgeL))};
    };
    // 水平边缘（2 条）；hy 为该侧 y 坐标
    auto emitH = [&](int hy, int x0, int x1) {
        out[n++] = {RoadSprite::EDGE_H, x0, hy, edgeL, edgeW};
        out[n++] = {RoadSprite::EDGE_H, x0 + edgeL, hy, (x1 - (x0 + edgeL)), edgeW};
    };
    // 内凹角交汇块：相邻横/竖臂边缘延伸后在角部应交汇（同转角外缘重叠思路）。
    // 在每条内凹角放 edgeW×edgeW 路沿块，使两条边缘在角部相交、消除缺口（完全在格内）。
    auto addJoin = [&](int jx, int jy) {
        out[n++] = {RoadSprite::EDGE_V, jx, jy, edgeW, edgeW};
    };

    switch (type) {
        case RoadTileType::VERTICAL:
            // 竖直直路（含死路端点）：左右侧出边缘，仅无邻居侧
            if (border & kDirLeft)
                emitV(0, 0, tileSize);
            if (border & kDirRight)
                emitV(tileSize - edgeW, 0, tileSize);
            break;

        case RoadTileType::HORIZONTAL:
            // 水平直路（含死路端点）：上下侧出边缘，仅无邻居侧
            if (border & kDirUp)
                emitH(0, 0, tileSize);
            if (border & kDirDown)
                emitH(tileSize - edgeW, 0, tileSize);
            break;

        // T 中心：仅缺邻居那一侧出 1 面（方向随该侧），分支基座两内凹角加交汇块
        case RoadTileType::T_UP:   // 分支朝上（缺下）：内凹角在顶部左/右
            if (border & kDirDown) emitH(tileSize - edgeW, 0, tileSize);
            addJoin(0, 0);
            addJoin(tileSize - edgeW, 0);
            break;
        case RoadTileType::T_DOWN: // 分支朝下（缺上）：内凹角在底部左/右
            if (border & kDirUp) emitH(0, 0, tileSize);
            addJoin(0, tileSize - edgeW);
            addJoin(tileSize - edgeW, tileSize - edgeW);
            break;
        case RoadTileType::T_RIGHT: // 分支朝右（缺左）：内凹角在右部上/下
            if (border & kDirLeft) emitV(0, 0, tileSize);
            addJoin(tileSize - edgeW, 0);
            addJoin(tileSize - edgeW, tileSize - edgeW);
            break;
        case RoadTileType::T_LEFT: // 分支朝左（缺右）：内凹角在左部上/下
            if (border & kDirRight) emitV(tileSize - edgeW, 0, tileSize);
            addJoin(0, 0);
            addJoin(0, tileSize - edgeW);
            break;

        // 转角：两个开放侧各出 2 条（允许相邻，外缘重叠，完全在格内）；
        // 另一侧（邻两臂相接的内凹角）加交汇块，使横/竖边缘在角部相交。
        case RoadTileType::CORNER_TOP_LEFT:   // 邻上+左，外缘=下+右，内凹角=左上
            if (border & kDirDown) emitH(tileSize - edgeW, 0, tileSize);
            if (border & kDirRight) emitV(tileSize - edgeW, 0, tileSize);
            addJoin(0, 0);
            break;
        case RoadTileType::CORNER_TOP_RIGHT:  // 邻上+右，外缘=下+左，内凹角=右上
            if (border & kDirDown) emitH(tileSize - edgeW, 0, tileSize);
            if (border & kDirLeft) emitV(0, 0, tileSize);
            addJoin(tileSize - edgeW, 0);
            break;
        case RoadTileType::CORNER_BOTTOM_LEFT:  // 邻下+左，外缘=上+右，内凹角=左下
            if (border & kDirUp) emitH(0, 0, tileSize);
            if (border & kDirRight) emitV(tileSize - edgeW, 0, tileSize);
            addJoin(0, tileSize - edgeW);
            break;
        case RoadTileType::CORNER_BOTTOM_RIGHT: // 邻下+右，外缘=上+左，内凹角=右下
            if (border & kDirUp) emitH(0, 0, tileSize);
            if (border & kDirLeft) emitV(0, 0, tileSize);
            addJoin(tileSize - edgeW, tileSize - edgeW);
            break;

        // 孤格：固定左右轴（观感随意）
        case RoadTileType::SINGLE:
            emitV(0, 0, tileSize);
            emitV(tileSize - edgeW, 0, tileSize);
            break;

        // 十字中心：无边缘（纯主体石板）；四个内凹角各加一块，使四条臂边缘在角部相交
        case RoadTileType::CROSS:
            addJoin(0, 0);
            addJoin(tileSize - edgeW, 0);
            addJoin(0, tileSize - edgeW);
            addJoin(tileSize - edgeW, tileSize - edgeW);
            break;
    }

    return n;
}

}  // namespace gamecore::map
