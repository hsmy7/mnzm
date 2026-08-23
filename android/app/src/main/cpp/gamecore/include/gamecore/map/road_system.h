#pragma once

#include <cstdint>
#include <unordered_set>
#include <vector>

// ============================================================
// 石板道路系统（Kotlin→C++ 迁移批次：道路自动拼接核心）
//
// 本模块承担「玩家只负责放置道路，程序根据邻接关系自动拼接」的全部
// 确定性逻辑，与渲染解耦（渲染端只按 RoadTileType / 边框掩码取精灵）。
//
// 设计要点：
//   1. 4-bit 邻接掩码：上=1 右=2 下=4 左=8（与游戏行业 autotile 一致）
//   2. 形态 = 掩码 → RoadTileType 的唯一映射（纯函数，任意网络全覆盖）
//   3. 道路「主体(石板)」与「外边缘(描边)」逻辑分离：
//      描边侧 = 该方向【没有】道路邻居的方向 = 掩码按位取反（四方向）
//      => 道路内部相邻格永不重复描边，只有道路区域最外缘才描边
//   4. 放置/删除只重算「当前格 + 上/下/左/右」最多 5 格，O(1) 局部更新
//      => 满足大规模地图下「一个变化不重建整图」的性能要求
//   5. 可建造判定独立：canPlaceRoad / canPlaceBuilding 与占位集合互斥
//
// 零 Android 依赖、纯计算、无状态注入接口，桌面/CI/iOS 均可直接编译
// 并用 GTest 测试（对标 Kotlin 侧 RoadAutotile/RoadGrid 语义）。
// ============================================================
namespace gamecore::map {

// ── 邻接方向位（4-bit）──────────────────────────────────────────
inline constexpr int kDirUp = 1;     // 0001
inline constexpr int kDirRight = 2;  // 0010
inline constexpr int kDirDown = 4;   // 0100
inline constexpr int kDirLeft = 8;   // 1000
inline constexpr int kMaskAll = 0xF; // 1111

// ── 道路形态枚举（与 Kotlin RoadTileType 一一对应）────────────────
enum class RoadTileType : int {
    SINGLE = 0,             // 单格（无邻居/死路）
    HORIZONTAL,             // 横向直路（左右）
    VERTICAL,               // 纵向直路（上下）
    CORNER_TOP_LEFT,        // 左上转角（上+左）
    CORNER_TOP_RIGHT,       // 右上转角（上+右）
    CORNER_BOTTOM_LEFT,     // 左下转角（下+左）
    CORNER_BOTTOM_RIGHT,    // 右下转角（下+右）
    T_UP,                   // T 型（主干朝上：上+左+右）
    T_RIGHT,                // T 型（主干朝右：上+下+右）
    T_DOWN,                 // T 型（主干朝下：下+左+右）
    T_LEFT,                 // T 型（主干朝左：上+下+左）
    CROSS                   // 十字路口（上下左右全通）
};

/// 掩码中已连接的方向数。
inline int connectionCount(int mask) {
    int count = 0;
    for (int i = 0; i < 4; ++i) {
        if (mask & (1 << i)) ++count;
    }
    return count;
}

// ── 位掩码 → 道路形态（自动选图核心）─────────────────────────────
//
// 任意 4-bit 组合必然落在以下 12 类之一（12 = 1 + 2 + 4 + 4 + 1）：
//   0 连接 → 单格；1 连接 → 死路按单格处理（不做专有精灵）
//   2 连接（对边）→ 直路；(相邻边) → 转角
//   3 连接 → T 型（主干 = 单独那个方向）
//   4 连接 → 十字
inline RoadTileType tileTypeForBitmask(int mask) {
    mask &= kMaskAll;
    switch (connectionCount(mask)) {
        case 0:
            return RoadTileType::SINGLE;
        case 1:
            // 死路（道路端点）按方向归为直路——道路端点仍是横向/纵向直路主体
            if (mask == kDirLeft || mask == kDirRight) return RoadTileType::HORIZONTAL;
            if (mask == kDirUp || mask == kDirDown) return RoadTileType::VERTICAL;
            return RoadTileType::SINGLE;
        case 2:
            if (mask == (kDirUp | kDirDown)) return RoadTileType::VERTICAL;
            if (mask == (kDirLeft | kDirRight)) return RoadTileType::HORIZONTAL;
            if (mask == (kDirUp | kDirLeft)) return RoadTileType::CORNER_TOP_LEFT;
            if (mask == (kDirUp | kDirRight)) return RoadTileType::CORNER_TOP_RIGHT;
            if (mask == (kDirDown | kDirLeft)) return RoadTileType::CORNER_BOTTOM_LEFT;
            if (mask == (kDirDown | kDirRight)) return RoadTileType::CORNER_BOTTOM_RIGHT;
            return RoadTileType::SINGLE;
        case 3:
            // 主干方向 = 3 条臂中的「单独」方向：
            //   mask=上|左|右(11) 缺下 → T_UP（主干朝上）
            //   mask=下|左|右(14) 缺上 → T_DOWN
            //   mask=上|下|右(7)  缺左 → T_RIGHT
            //   mask=上|下|左(13) 缺右 → T_LEFT
            if (mask == (kDirUp | kDirLeft | kDirRight)) return RoadTileType::T_UP;
            if (mask == (kDirDown | kDirLeft | kDirRight)) return RoadTileType::T_DOWN;
            if (mask == (kDirUp | kDirDown | kDirRight)) return RoadTileType::T_RIGHT;
            if (mask == (kDirUp | kDirDown | kDirLeft)) return RoadTileType::T_LEFT;
            return RoadTileType::SINGLE;
        case 4:
            return RoadTileType::CROSS;
        default:
            return RoadTileType::SINGLE;
    }
}

// ── 道路外边缘（描边）自动判断 ─────────────────────────────────────
//
// 道路内部相邻格不应出现重复边框：只有道路区域【最外缘】才描边。
// 由于位掩码已编码「该方向是否有道路邻居」，因此：
//   需要描边的方向 = 掩码补集 = (~mask & kMaskAll) = (kMaskAll ^ mask)
// * 某侧有道路 → 该侧位为 1 → 不描边（内部）
// * 某侧无道路 → 该侧位为 0 → 描边（外缘）
//
// renderer 约定：返回值的每条边对应一条「边框条」精灵。
/// 需要绘制边框的方向掩码（与 [tileTypeForBitmask] 分离，逻辑互不耦合）。
inline int roadBorderMask(int mask) {
    return static_cast<int>(kMaskAll ^ (mask & kMaskAll));
}

/// 单侧是否有道路邻居（供 renderer 逐条边独立取精灵）。
inline bool hasNeighbor(int mask, int dir) { return (mask & dir) != 0; }

// ── 道路网格（状态式：放置/删除 + 邻居重算）───────────────────────
//
// 内存模型：
//   road_[idx] ：该格是否为道路（成员判定）
//   masks_[idx]：该格当前位掩码（由邻居实时推导，0=非道路）
// 任一道路格变化，只对「当前格 + 上下左右」5 格做掩码重算，O(1)。
class RoadGrid {
public:
    RoadGrid(int width, int height)
        : width_(width), height_(height),
          road_(static_cast<size_t>(static_cast<size_t>(width) * height), false),
          masks_(static_cast<size_t>(static_cast<size_t>(width) * height), 0) {}

    int width() const { return width_; }
    int height() const { return height_; }

    bool inBounds(int x, int y) const {
        return x >= 0 && x < width_ && y >= 0 && y < height_;
    }

    bool isRoad(int x, int y) const {
        return inBounds(x, y) && road_[idxOf(x, y)];
    }

    /// 当前格位掩码（非道路返回 0；越界返回 0——越界视为无道路邻居）。
    int bitmaskAt(int x, int y) const {
        return inBounds(x, y) ? masks_[idxOf(x, y)] : 0;
    }

    /// 当前格道路形态（非道路回退为 SINGLE）。
    RoadTileType tileAt(int x, int y) const {
        return tileTypeForBitmask(bitmaskAt(x, y));
    }

    /// 当前格需要描边的方向掩码（非道路返回全 0）。
    int borderMaskAt(int x, int y) const {
        return isRoad(x, y) ? roadBorderMask(bitmaskAt(x, y)) : 0;
    }

    /// 放置道路：目标须空闲（非道路、非固定结构/建筑占位、在可建环内）。
    /// 成功返回 true 并重算当前格 + 上下左右 5 格；失败返回 false 不改变状态。
    bool placeRoad(int x, int y,
                   const std::unordered_set<int64_t>& blockedCells,
                   int border) {
        if (!inBounds(x, y)) return false;
        if (x < border || x >= width_ - border ||
            y < border || y >= height_ - border) {
            return false;  // 树林边界环不可建造
        }
        if (blockedCells.count(packCell(x, y)) != 0) return false;  // 建筑/固定结构占位
        if (road_[idxOf(x, y)]) return false;  // 已是道路
        road_[idxOf(x, y)] = true;
        recomputeNeighborhood(x, y);
        return true;
    }

    /// 删除道路：当前格恢复空地，并重算 4 个邻居（非道路格自动无操作）。
    void removeRoad(int x, int y) {
        if (!isRoad(x, y)) return;
        road_[idxOf(x, y)] = false;
        masks_[idxOf(x, y)] = 0;
        recomputeNeighborhood(x, y);
    }

    /// 把当前整格集合重建为道路（用于读档/加载——一次性全量计算）。
    void rebuild(const std::vector<std::pair<int, int>>& cells) {
        std::fill(road_.begin(), road_.end(), false);
        std::fill(masks_.begin(), masks_.end(), 0);
        for (const auto& [x, y] : cells) {
            if (inBounds(x, y)) road_[idxOf(x, y)] = true;
        }
        // 全量重算掩码（读档低频，O(n) 可接受）
        for (int y = 0; y < height_; ++y) {
            for (int x = 0; x < width_; ++x) {
                if (road_[idxOf(x, y)]) recomputeMask(x, y);
            }
        }
    }

    // ── 可建造判定（独立方法，供 UI/放置路径复用，不散落逻辑）──────

    /// 目标格是否可放置道路：界内 + 可建环内 + 非建筑/固定结构占位 + 尚未是道路。
    static bool canPlaceRoad(
        int x, int y, int width, int height, int border,
        const std::unordered_set<int64_t>& blockedOrBuildingCells,
        const RoadGrid* grid) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        if (x < border || x >= width - border ||
            y < border || y >= height - border) {
            return false;
        }
        if (blockedOrBuildingCells.count(packCell(x, y)) != 0) return false;
        if (grid != nullptr && grid->isRoad(x, y)) return false;
        return true;
    }

    /// 目标格是否可放置建筑：界内 + 可建环内 + 非固定结构占位 + 目标不是道路。
    static bool canPlaceBuilding(
        int x, int y, int width, int height, int border,
        const std::unordered_set<int64_t>& blockedCells,
        const RoadGrid* grid) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        if (x < border || x >= width - border ||
            y < border || y >= height - border) {
            return false;
        }
        if (blockedCells.count(packCell(x, y)) != 0) return false;
        // 建筑不能覆盖道路（禁止叠放）：目标格有道路 → 拒绝
        if (grid != nullptr && grid->isRoad(x, y)) return false;
        return true;
    }

    /// 占位格编码（与 Kotlin GridSystem.packCell 同式：(x << 32) | y）。
    /// 用 int64_t——Windows 上 long 为 32 位，`x << 32` 会溢出（UB 丢高位）。
    static int64_t packCell(int x, int y) {
        return (static_cast<int64_t>(x) << 32) |
               (static_cast<uint64_t>(static_cast<uint32_t>(y)) & 0xFFFFFFFFull);
    }

private:
    int idxOf(int x, int y) const { return y * width_ + x; }

    /// 重算单格掩码（由邻居实时推导；非道路格为 0）。
    void recomputeMask(int x, int y) {
        if (!road_[idxOf(x, y)]) {
            masks_[idxOf(x, y)] = 0;
            return;
        }
        int mask = 0;
        if (isRoad(x, y - 1)) mask |= kDirUp;
        if (isRoad(x + 1, y)) mask |= kDirRight;
        if (isRoad(x, y + 1)) mask |= kDirDown;
        if (isRoad(x - 1, y)) mask |= kDirLeft;
        masks_[idxOf(x, y)] = mask;
    }

    /// 重算当前格 + 上/下/左/右（最多 5 格）。格本身已在 place/remove 前
    /// 更新了 road_ 成员位，此处只同步掩码，保证 O(1) 局部更新。
    void recomputeNeighborhood(int x, int y) {
        const int nx[5] = {x, x, x - 1, x + 1, x};
        const int ny[5] = {y - 1, y + 1, y, y, y};
        for (int i = 0; i < 5; ++i) {
            if (inBounds(nx[i], ny[i])) recomputeMask(nx[i], ny[i]);
        }
    }

    int width_;
    int height_;
    std::vector<bool> road_;
    std::vector<int> masks_;
};

}  // namespace gamecore::map
