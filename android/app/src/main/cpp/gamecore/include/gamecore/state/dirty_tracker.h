#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

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
// 实现（WS-1.3 同步通道降本，2026-09-05）：基线缓存为 JSON 树——
// resetBaseline/syncBaselineToCurrent 时序列化一次并缓存，diffToJson 只对
// **当前状态**序列化一次，与缓存树按键深比较后把当前树移入缓存。原实现
// 每次导出对基线与当前各做一次全量序列化 + GameState 深拷贝（审计 P0-2：
// dirty_tracker.cpp 两次全量序列化+深拷贝），现降为每导出恰一次序列化、
// 零深拷贝。协议与消费语义（导出即消费/版本单调/键集形状）逐位不变。
// 列级写屏障（DirtyColumn 包装版号标记）按 dirty_tracker 自述既定待办随
// 计划 v2 阶段 3 数据导向存储落地——本实现保持"任何遗漏路径不漏报"的
// 健壮性优先口径。
//
// 确定性约束：实体键收集用 std::map（有序），禁止 unordered_map 参与迭代；
// 集合内重复 id 以末次出现为准（Kotlin 侧各存储均保证 id 唯一）。
// ============================================================
namespace gamecore::state {

/// ── 共享 diff 段（重构方案 R2.4/B09 列级导出接线）───────────────────
/// gameData 字段级 + 实体集合按 id upsert/remove 的增量比对——
/// [DirtyTracker::diffToTree] 与列级导出（ColumnDirtyTracker::exportDirtyTree）
/// 共用的比对段。[names] = 参与比对的集合名清单（顺序 = 输出遍历序）；
/// base/cur 两树形状一致（键集相同）。语义与全量 diff 逐位一致（同一循环体），
/// 列级通道的 gameData/集合域因此与全量导出**由构造保证等价**——列级通道的
/// 新增正确性面收窄为弟子列位图（写点标脏），由对拍守卫测试锁定。
void diffTreeSegments(const nlohmann::json& base, const nlohmann::json& cur,
                      const std::vector<const char*>& names,
                      nlohmann::json& changed, nlohmann::json& removed);

/// 序列化 gameData + 九个实体集合（**不含 disciples**——弟子域由列级位图
/// 通道承载，免去每次导出 5000×109 行的整树序列化）。@Transient 顶层域
/// （aiSectDisciples 等 AI 池）不在序列化面——与镜像协议无关（Kotlin 消费端
/// 对未知集合名宽松忽略），列级信封因此不携带（全量 diff 臂携带但同样被
/// 消费端忽略，语义等价）。
nlohmann::json stateWithoutDisciplesToJson(const GameState& s);

/// 实体集合名清单（gameData 之外的镜像面集合；与 kEntityCollections 的
/// 非 disciples 子集一致，顺序固定保证输出稳定）
extern const std::vector<const char*> kNonDiscipleCollections;

class DirtyTracker {
public:
    /// 重置基线为给定状态（初始化/导入/全量导出后调用）
    void resetBaseline(const GameState& s);

    /// 计算当前状态相对基线的变更集 JSON 文本，并把基线推进到当前状态
    /// （标准增量同步语义：导出即消费）。无变化时 changed/removed 为空对象。
    std::string diffToJson(const GameState& current);

    /// 计算当前状态相对基线的变更集**树**（{"version","changed","removed"}，
    /// 已过 normalizeIntegralFloats 规范化），并把基线推进到当前状态——
    /// 语义与 [diffToJson] 完全同源（同一 ++version/同一树/同一基线推进），
    /// JSON 文本导出（diffToJson）与 GameView protobuf 信封编码
    /// （encodeGameView，R2.2）共用本入口，保证双格式逐值等价。
    nlohmann::json diffToTree(const GameState& current);

    /// 把基线同步为当前状态（版本号不递增）。
    /// 计划 v2 阶段 3：反向增量（Kotlin → C++）应用后调用——C++ 状态已与
    /// Kotlin 一致，防下一旬 exportDirty 把反向应用值当变更重发回 Kotlin。
    void syncBaselineToCurrent(const GameState& current);

    /// 当前版本号（每次 diffToJson 后递增；初始 0）
    uint64_t version() const { return version_; }

private:
    /// 基线 JSON 树缓存（resetBaseline/syncBaselineToCurrent/diffToJson 消费
    /// 时重建；与 GameState 快照逐位等价——diff 协议只经 JSON 树观察状态）
    nlohmann::json baselineJson_ = nlohmann::json::object();
    uint64_t version_ = 0;
};

}  // namespace gamecore::state
