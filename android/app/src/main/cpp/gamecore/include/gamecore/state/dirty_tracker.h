#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/state/models.h"

// ============================================================
// DirtyTracker — 状态变更集追踪器
//
// 职责：为 exportDirty 提供增量变更集——对比当前状态与上次导出基线，
// 产出 {"version", "changed", "removed"} 协议 JSON：
//   {
//     "version": 3,
//     "changed": {
//       "gameData.spiritStones": 1234,
//       "gameData.sectPolicies": {...},
//       "disciples": [ {entity...} ]
//     },
//     "removed": { "pills": ["p_1", "p_9"] }
//   }
//
// 基线形态（memory-refactor D5/P4.1）：**字段/块级基线**，不持有嵌套全量
// GameState DOM——gameData 存单域对象；实体集合存 id→实体块映射（含
// disciples 时由 includeDisciples 打开）。稳态不存在「gameData + disciples
// 数组」同居一棵业务树的形态；导出瞬时的 changed/removed 为协议输出，
// 比较过程不构造第二棵全量业务树。
//
// 确定性约束：实体键收集用 std::map（有序），禁止 unordered_map 参与迭代；
// 集合内重复 id 以末次出现为准（Kotlin 侧各存储均保证 id 唯一）。
// ============================================================
namespace gamecore::state {

/// ── 共享 diff 段（R2.4/B09 列级导出接线）───────────────────
/// gameData 字段级 + 实体集合按 id upsert/remove 的增量比对——
/// [DirtyTracker::diffToTree] 与列级导出（ColumnDirtyTracker::exportDirtyTree）
/// 共用的比对段。[names] = 参与比对的集合名清单（顺序 = 输出遍历序）；
/// base/cur 两树形状一致（键集相同）。语义与全量 diff 逐位一致（同一循环体）。
void diffTreeSegments(const nlohmann::json& base, const nlohmann::json& cur,
                      const std::vector<const char*>& names,
                      nlohmann::json& changed, nlohmann::json& removed);

/// 序列化 gameData + 九个实体集合（**不含 disciples**——弟子域由列级位图
/// 通道承载）。@Transient 顶层域（aiSectDisciples 等）不在序列化面。
nlohmann::json stateWithoutDisciplesToJson(const GameState& s);

/// 实体集合名清单（gameData 之外的镜像面集合；与 kEntityCollections 的
/// 非 disciples 子集一致，顺序固定保证输出稳定）
extern const std::vector<const char*> kNonDiscipleCollections;

/// 全量实体集合名（含 disciples；DirtyTracker 全量臂用）
extern const std::vector<const char*> kAllEntityCollections;

// 实体集合名 → id → 实体 JSON 块（有序 map：删除/上架输出确定性）
using EntityBlockMap = std::map<std::string, std::map<std::string, nlohmann::json>>;

/**
 * 字段/块级状态基线（D5；**不是**嵌套全量 GameState 树）。
 *
 * - gameData：单域 JSON 对象（与 stateWithoutDisciplesToJson["gameData"] 同形）；
 * - 集合：collection → id → entity 块；includeDisciples 时含 disciples。
 *
 * 稳态断言（BaselineMemoryTest）：holdsNestedFullStateDom() 恒 false——
 * 本结构从不把 gameData 与 disciples 数组嵌套在同一棵业务树下。
 */
class StateBaseline {
public:
    /// @param includeDisciples true = 全量臂（DirtyTracker）；false = rest 臂（列级）
    explicit StateBaseline(bool includeDisciples) : includeDisciples_(includeDisciples) {}

    /// 重置基线为给定状态（初始化/导入/全量导出后）
    void reset(const GameState& s);

    /// 对比当前状态与基线，产出 changed/removed 并把基线推进到当前（导出即消费）。
    /// 语义与 [diffTreeSegments] 在同名集合上逐位一致（upsert 遍历当前数组序、
    /// removed 遍历基线 id 序序）。
    void diffAdvance(const GameState& current, nlohmann::json& changed,
                     nlohmann::json& removed);

    /// 恒 false：本基线不以嵌套全量 GameState DOM 形态存储（P4.1 守卫）
    bool holdsNestedFullStateDom() const { return false; }

    /// gameData 域字段数（测试观测面）
    std::size_t gameDataFieldCount() const { return gameData_.size(); }

    /// [collection][id] 块数（测试观测面；不含未出现的集合键）
    std::size_t entityBlockCount(const char* collection) const {
        auto it = collections_.find(collection);
        return it == collections_.end() ? 0 : it->second.size();
    }

private:
    void captureCollection(const char* name, const nlohmann::json& arr);

    bool includeDisciples_;
    nlohmann::json gameData_ = nlohmann::json::object();
    EntityBlockMap collections_;
};

class DirtyTracker {
public:
    /// 重置基线为给定状态（初始化/导入/全量导出后调用）
    void resetBaseline(const GameState& s);

    /// 计算当前状态相对基线的变更集 JSON 文本，并把基线推进到当前状态
    /// （标准增量同步语义：导出即消费）。无变化时 changed/removed 为空对象。
    std::string diffToJson(const GameState& current);

    /// 计算当前状态相对基线的变更集**树**（{"version","changed","removed"}，
    /// 已过 normalizeIntegralFloats 规范化），并把基线推进到当前状态——
    /// 语义与 [diffToJson] 完全同源（同一 ++version/同一树/同一基线推进）。
    nlohmann::json diffToTree(const GameState& current);

    /// 把基线同步为当前状态（版本号不递增）。
    /// 反向增量（已删除通道）保留的语义钩子：C++ 状态与 Kotlin 一致时防回流。
    void syncBaselineToCurrent(const GameState& current);

    /// 当前版本号（每次 diffToJson 后递增；初始 0）
    uint64_t version() const { return version_; }

    /// 测试面：稳态不持嵌套全量业务树（恒 false——块级基线形态）
    bool holdsNestedFullStateDom() const { return baseline_.holdsNestedFullStateDom(); }

private:
    /// 字段/块级基线（全量臂：含 disciples 实体块；见 [StateBaseline]）
    StateBaseline baseline_{/*includeDisciples=*/true};
    uint64_t version_ = 0;
};

}  // namespace gamecore::state
