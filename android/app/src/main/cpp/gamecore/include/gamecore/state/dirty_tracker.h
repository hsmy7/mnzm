#pragma once

#include <cstdint>
#include <string>

#include "gamecore/state/models.h"

// ============================================================
// DirtyTracker — 状态变更集追踪器（计划 v2 阶段 1，C-06/S-06 根治）
//
// 职责：为 exportDirty 提供增量变更集——对比当前状态与上次导出基线，
// 产出 {"version", "changed", "removed"} 协议 JSON：
//   {
//     "version": 3,                          // 每次导出单调 +1
//     "changed": {
//       "gameData.spiritStones": 1234,       // gameData 顶层字段级覆盖
//       "gameData.sectPolicies": {...},      // 嵌套容器整体替换语义
//       "disciples": [ {entity...} ]         // 实体集合按 id upsert（全实体）
//     },
//     "removed": {
//       "pills": ["p_1", "p_9"]              // 实体按 id 删除
//     }
//   }
//
// 实现：基线快照 diff（resetBaseline 后每次 diffToJson 与基线做 JSON 树
// 深比较）而非写屏障标记——任何遗漏路径的修改都不会漏报（健壮性优先，
// 全量 dump 的成本随阶段 3 数据导向存储再优化，登记于 docs/cpp-engine.md）。
//
// 确定性约束：实体键收集用 std::map（有序），禁止 unordered_map 参与迭代；
// 集合内重复 id 以末次出现为准（Kotlin 侧各存储均保证 id 唯一）。
// ============================================================
namespace gamecore::state {

class DirtyTracker {
public:
    /// 重置基线为给定状态（初始化/导入/全量导出后调用）
    void resetBaseline(const GameState& s);

    /// 计算当前状态相对基线的变更集 JSON 文本，并把基线推进到当前状态
    /// （标准增量同步语义：导出即消费）。无变化时 changed/removed 为空对象。
    std::string diffToJson(const GameState& current);

    /// 把基线同步为当前状态（版本号不递增）。
    /// 计划 v2 阶段 3：反向增量（Kotlin → C++）应用后调用——C++ 状态已与
    /// Kotlin 一致，防下一旬 exportDirty 把反向应用值当变更重发回 Kotlin。
    void syncBaselineToCurrent(const GameState& current);

    /// 当前版本号（每次 diffToJson 后递增；初始 0）
    uint64_t version() const { return version_; }

private:
    GameState baseline_{};
    uint64_t version_ = 0;
};

}  // namespace gamecore::state
