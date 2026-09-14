#pragma once

#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

#include "gamecore/map/road_system.h"
#include "gamecore/state/models.h"

// ============================================================
// 道路放置/拆除事务（RoadFacade AUTHORITATIVE 转发——C++ 唯一真相）
//
// 承接 Kotlin RoadFacadeImpl.placeRoad/removeRoad 的写者语义（batch-07
// 下沉，判定序与 Kotlin 原实现逐字对齐）：
//   place：可建环界 → 占位（本宗建筑占地 ∪ 固定结构）→ 重复放置 →
//          灵石充足 → roads 增格 + 灵石扣减 + 5 格邻域掩码重算
//   remove：存在性 → roads 删格 + 5 格邻域掩码重算（灵石不返还）
// 任意校验失败零写入（信封 failure → Kotlin 回退原路径重执行校验链，
// 用户可见文案由 Kotlin 臂产出；本头文件 message 仅诊断用）。
//
// 零 RNG 论证：两事务均为纯确定性状态变换——校验链只读，变更段仅
// roads 增删 + spiritStones 线性扣减 + 邻域掩码重算（tileTypeForBitmask
// 纯函数，gamecore::map 同源）。全链无 rng() 调用点（签名级证据：本头
// API 不接受 RngManager/种子参数），Kotlin 原路径（RoadFacadeImpl/
// RoadTiling）同样零 Random 消费——抽取集为空集，RNG 红线平凡满足。
//
// 占位几何参数（width/height/border/cost/occupiedCells）由 Kotlin 门面按
// GameConfig.Road / FixedSectGateway / 本宗建筑占地逐次传入——单一事实源
// 留在 Kotlin，C++ 不重复常量。宗门过滤（sectId==activeSectId||empty）亦在
// Kotlin 侧完成：C++ GridBuildingData 无 sectId 字段（models.h 本批禁改，
// §3.3 协议），无法在 C++ 复现该判定——占用集合组装归 Kotlin，目标格
// 冲突判定归本头原语。packed cell 编码与 Kotlin GridSystem.packCell 同式。
// ============================================================
namespace gamecore::system {

/// 道路事务结果（失败信封载体；spiritStonesAfter 供对拍/诊断）
struct RoadTxOutcome {
    bool ok = false;
    const char* errorType = "";
    std::string message;
    int64_t spiritStonesAfter = 0;
};

/// RoadTileType → Kotlin 枚举 name（RoadData.roadType 按 name-string 承载；
/// road_system.h 枚举常量与 Kotlin RoadTileType 同名异序，名称逐字一致）
inline const char* roadTileTypeName(gamecore::map::RoadTileType type) {
    using gamecore::map::RoadTileType;
    switch (type) {
        case RoadTileType::SINGLE: return "SINGLE";
        case RoadTileType::HORIZONTAL: return "HORIZONTAL";
        case RoadTileType::VERTICAL: return "VERTICAL";
        case RoadTileType::CORNER_TOP_LEFT: return "CORNER_TOP_LEFT";
        case RoadTileType::CORNER_TOP_RIGHT: return "CORNER_TOP_RIGHT";
        case RoadTileType::CORNER_BOTTOM_LEFT: return "CORNER_BOTTOM_LEFT";
        case RoadTileType::CORNER_BOTTOM_RIGHT: return "CORNER_BOTTOM_RIGHT";
        case RoadTileType::T_UP: return "T_UP";
        case RoadTileType::T_RIGHT: return "T_RIGHT";
        case RoadTileType::T_DOWN: return "T_DOWN";
        case RoadTileType::T_LEFT: return "T_LEFT";
        case RoadTileType::CROSS: return "CROSS";
    }
    return "SINGLE";
}

/// 邻接掩码查询（与 Kotlin RoadTiling.bitmaskAt(Set) 同式：界内四邻判定，
/// 上1右2下4左8；越界视为无邻居）。
inline int roadMaskAt(const std::unordered_set<int64_t>& cells,
                      int32_t x, int32_t y, int32_t width, int32_t height) {
    if (x < 0 || y < 0 || x >= width || y >= height) return 0;
    int mask = 0;
    if (y - 1 >= 0 &&
        cells.count(gamecore::map::RoadGrid::packCell(x, y - 1)) != 0) {
        mask |= gamecore::map::kDirUp;
    }
    if (x + 1 < width &&
        cells.count(gamecore::map::RoadGrid::packCell(x + 1, y)) != 0) {
        mask |= gamecore::map::kDirRight;
    }
    if (y + 1 < height &&
        cells.count(gamecore::map::RoadGrid::packCell(x, y + 1)) != 0) {
        mask |= gamecore::map::kDirDown;
    }
    if (x - 1 >= 0 &&
        cells.count(gamecore::map::RoadGrid::packCell(x - 1, y)) != 0) {
        mask |= gamecore::map::kDirLeft;
    }
    return mask;
}

/// 对 roads 集合重算 (gridX,gridY) 及上/下/左/右（最多 5 格）的位掩码与形态。
/// 与 Kotlin RoadFacadeImpl.recomputeNeighborhood 同式：非道路格自动跳过，
/// 只更新受影响格（O(1) 局部更新）。
inline void recomputeRoadNeighborhood(std::vector<gamecore::state::RoadData>& roads,
                                      int32_t gridX, int32_t gridY,
                                      int32_t width, int32_t height) {
    std::unordered_set<int64_t> cells;
    cells.reserve(roads.size() * 2);
    for (const auto& r : roads) {
        cells.insert(gamecore::map::RoadGrid::packCell(r.gridX, r.gridY));
    }
    const int32_t nx[5] = {gridX, gridX, gridX - 1, gridX + 1, gridX};
    const int32_t ny[5] = {gridY - 1, gridY + 1, gridY, gridY, gridY};
    for (int i = 0; i < 5; ++i) {
        const int64_t key = gamecore::map::RoadGrid::packCell(nx[i], ny[i]);
        if (cells.count(key) == 0) continue;  // 该格非道路（含刚删除的中心格）
        const int mask = roadMaskAt(cells, nx[i], ny[i], width, height);
        for (auto& r : roads) {
            if (gamecore::map::RoadGrid::packCell(r.gridX, r.gridY) == key) {
                r.bitMask = mask;
                r.roadType = roadTileTypeName(gamecore::map::tileTypeForBitmask(mask));
                break;
            }
        }
    }
}

/// 道路放置事务：校验链全过后 roads 增格 + 灵石扣减 + 邻域重算；失败零写入。
/// @param occupiedCells 占位格集合（packed cell；Kotlin 组装：本宗建筑占地展开
///                      ∪ FixedSectGateway.blockedCells——宗门过滤见头注释）
inline RoadTxOutcome placeRoadTx(gamecore::state::GameData& gd,
                                 int32_t gridX, int32_t gridY,
                                 int32_t width, int32_t height, int32_t border,
                                 int64_t costPerCell,
                                 const std::vector<int64_t>& occupiedCells) {
    RoadTxOutcome out;
    // 校验序 = Kotlin placeRoad 判定序逐字（canPlaceCell 三级短路 → 资源）
    if (gridX < border || gridY < border ||
        gridX >= width - border || gridY >= height - border) {
        out.errorType = "OutOfBounds";
        out.message = "网格过小/越界可建环之外";
        return out;
    }
    const std::unordered_set<int64_t> occupied(occupiedCells.begin(),
                                               occupiedCells.end());
    if (occupied.count(gamecore::map::RoadGrid::packCell(gridX, gridY)) != 0) {
        out.errorType = "Occupied";
        out.message = "该格已有建筑或固定结构";
        return out;
    }
    for (const auto& r : gd.roads) {
        if (r.gridX == gridX && r.gridY == gridY) {
            out.errorType = "AlreadyRoad";
            out.message = "该格已是道路";
            return out;
        }
    }
    if (gd.spiritStones < costPerCell) {
        out.errorType = "Insufficient";
        out.message = "灵石不足";
        return out;
    }
    gamecore::state::RoadData added;
    added.gridX = gridX;
    added.gridY = gridY;
    added.bitMask = 0;         // 新格先以无邻居落位，邻域重算统一覆盖
    added.roadType = "SINGLE";
    gd.roads.push_back(std::move(added));
    gd.spiritStones -= costPerCell;
    recomputeRoadNeighborhood(gd.roads, gridX, gridY, width, height);
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

/// 道路拆除事务：存在性校验 → 删格 + 邻域重算（灵石不返还）；失败零写入。
inline RoadTxOutcome removeRoadTx(gamecore::state::GameData& gd,
                                  int32_t gridX, int32_t gridY,
                                  int32_t width, int32_t height) {
    RoadTxOutcome out;
    bool existed = false;
    for (const auto& r : gd.roads) {
        if (r.gridX == gridX && r.gridY == gridY) {
            existed = true;
            break;
        }
    }
    if (!existed) {
        out.errorType = "NotFound";
        out.message = "该格没有道路";
        return out;
    }
    std::vector<gamecore::state::RoadData> remaining;
    remaining.reserve(gd.roads.size() > 0 ? gd.roads.size() - 1 : 0);
    for (auto& r : gd.roads) {
        if (!(r.gridX == gridX && r.gridY == gridY)) remaining.push_back(std::move(r));
    }
    gd.roads = std::move(remaining);
    recomputeRoadNeighborhood(gd.roads, gridX, gridY, width, height);
    out.ok = true;
    out.spiritStonesAfter = gd.spiritStones;
    return out;
}

}  // namespace gamecore::system
