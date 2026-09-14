#pragma once

#include <cstdint>

// ============================================================
// 绘制层序合成器（立体层装饰 ↔ 建筑归并，双端共用语义）
//
// 装饰按"是否立体层"分两类（单一判据 = LAYOUT.tiles[].layer → 双端生成常量
// SpriteAtlasDef.isObjectDecorTile / TextureAtlas.h TILE_OBJECT_LAYER）：
//   - 地面层（草/石）：跟随地面逐格绘制，越界量 ≤0.42 格（被后绘建筑压住是预期观感）
//   - 立体层（树）：与建筑**同一画家序**绘制——树冠可向上伸出 ~2.3 格，
//     若仍留在地面层，北侧（更远）建筑会把树冠无脑压掉（层序错误：树在前却消失）
//
// 画家序键 = **地面接触点**（底边 Y，世界像素）——与 Kotlin buildBuildingDataArray
// 的 `sortedBy { gridY + height }`（Y-sorting）以及 C++ 建筑段同一语义：
// 下方（更大 Y）后绘 → 覆盖上方。
//
// 同键（装饰底边 == 建筑底边）时**建筑在后**：建筑立在土地上，同一接触点应压住装饰。
//
// 本头零依赖、纯函数（不持有 UV/几何/精灵名），桌面 GTest 直接覆盖；
// Kotlin 侧（SoftwareCanvasBackend chunk 烘焙）按同一契约实现，由
// SoftwareCanvasBackendDecorLayerTest 像素断言锁定（两后端同序）。
// ============================================================
namespace gamecore::map {

/// 归并两组**已按底边 Y 升序**的绘制项 → 输出绘制序标记。
///
/// 输出 outIsBuilding[i]：1 = 该位次绘制建筑组的下一个项，0 = 绘制装饰组的下一个项
/// （两组各自保序，调用方按标记分别递增各自的游标）。
///
/// @param decorBottomY    装饰组底边 Y（世界像素，升序）
/// @param decorCount      装饰项数（可为 0）
/// @param buildingBottomY 建筑组底边 Y（世界像素，升序）
/// @param buildingCount   建筑项数（可为 0）
/// @param outIsBuilding   输出缓冲，容量须 ≥ decorCount + buildingCount
/// @return 实际写入的项数（== decorCount + buildingCount）
inline int mergeObjectLayerOrder(const float* decorBottomY, int decorCount,
                                 const float* buildingBottomY, int buildingCount,
                                 uint8_t* outIsBuilding) {
    int i = 0;
    int j = 0;
    int n = 0;
    while (i < decorCount && j < buildingCount) {
        // 同键（含浮点相等）判定：装饰先绘，建筑随后覆盖
        if (decorBottomY[i] <= buildingBottomY[j]) {
            outIsBuilding[n++] = 0;
            ++i;
        } else {
            outIsBuilding[n++] = 1;
            ++j;
        }
    }
    while (i < decorCount) {
        outIsBuilding[n++] = 0;
        ++i;
    }
    while (j < buildingCount) {
        outIsBuilding[n++] = 1;
        ++j;
    }
    return n;
}

/// 单点判定（消费端语义自检/单测锚点）：装饰项是否应先于建筑项绘制。
///
/// @param decorBottomY    装饰底边 Y（世界像素）
/// @param buildingBottomY 建筑底边 Y（世界像素）
/// @return true = 装饰在先（同键亦为 true——建筑覆盖同接触点装饰）
inline bool decorBeforeBuilding(float decorBottomY, float buildingBottomY) {
    return decorBottomY <= buildingBottomY;
}

/// 立体层装饰单格绘制矩形（世界像素，格底边居中锚定）。
///
/// 锚点契约（与 LAYOUT.tiles[].sprite 的取值口径一致，双端同式）：
///   x = 格左 + (1 − W) / 2 格，y = 格上 + (1 − H) 格（底边对齐格底边）。
///
/// @param cellX     格左世界像素
/// @param cellY     格上世界像素
/// @param tileSize  单格像素
/// @param spriteW   显示宽（格，小数格）
/// @param spriteH   显示高（格，小数格）
/// @param outXYWH   输出 [x, y, w, h]（世界像素）
inline void decorDrawRect(float cellX, float cellY, float tileSize,
                          float spriteW, float spriteH, float* outXYWH) {
    outXYWH[0] = cellX + (tileSize - spriteW * tileSize) * 0.5f;
    outXYWH[1] = cellY + (tileSize - spriteH * tileSize);
    outXYWH[2] = spriteW * tileSize;
    outXYWH[3] = spriteH * tileSize;
}

}  // namespace gamecore::map
