#pragma once

#include <cstddef>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/settlement_detail.h"  // settle_util::toIntOrNull

// ============================================================
// 实例 owner 行索引桶视图（重构方案 R1.3 第二步）
//
// 装备/功法实例的步骤入口映射由 associateBy id 全量深拷贝 map
// （equipmentMapOf/manualMapOf，每步 E 次实例深拷贝 + 字符串键节点分配）
// 改为 owner 行索引桶：桶值 = 全局向量下标（指针级零拷贝），构建 O(E)，
// 逐行查找 = 本人桶内末次匹配（小向量线性扫描，零分配）。
//
// ## 查找语义与原 id 键 map 逐位一致
// - find(ownerRow, id)：桶内**末次**匹配——原 map `m[e.id] = e` 同 id 保留
//   最后 == 全局向量序末次；实例 id 唯一（生成期不变量）时即唯一命中；
// - 桶未命中回退全量向量**末次**线性匹配——无主实例（ownerId 空）、
//   非数值 owner、owner 不在册等非常规数据与原全局 find 同结果；
// - 确定性：std::map 行键有序 + 桶内保全局向量序，迭代/匹配序确定，
//   无 unordered 参与业务（铁律）。
//
// ## 生命周期契约
// 桶持有**向量下标**而非指针：构建后到最后一次 find 之间，实例向量
// 内容允许原地列写（孕养等），但**禁止 push_back/erase**（下标失效）；
// 弟子行 upsert（eraseAt+追加+保序旋转）不触碰实例向量与行号，桶保持
// 有效。每旬结算步骤入口构建、步骤内只读，契约满足。
// ============================================================
namespace gamecore::system {
namespace instance_bucket {

template <typename InstanceT>
struct InstanceBuckets {
    const std::vector<InstanceT>* instances = nullptr;
    std::map<std::size_t, std::vector<std::size_t>> byOwnerRow;

    /// owner 行 ownerRow 名下 id 实例；未命中回退全量末次扫描（见文件头）
    const InstanceT* find(std::size_t ownerRow, const std::string& id) const {
        const auto bit = byOwnerRow.find(ownerRow);
        if (bit != byOwnerRow.end()) {
            const std::vector<std::size_t>& bucket = bit->second;
            for (std::size_t k = bucket.size(); k-- > 0;) {
                const InstanceT& inst = (*instances)[bucket[k]];
                if (inst.id == id) return &inst;
            }
        }
        for (std::size_t i = instances->size(); i-- > 0;) {
            const InstanceT& inst = (*instances)[i];
            if (inst.id == id) return &inst;
        }
        return nullptr;
    }
};

using EquipmentInstanceBuckets = InstanceBuckets<state::EquipmentInstance>;
using ManualInstanceBuckets = InstanceBuckets<state::ManualInstance>;

/// owner 行索引桶构建（O(E)；owner 解析 = 数值 id 列直查（R1.3 第一步
/// dense 索引）；无主/非数值 owner/owner 不在册的实例不入桶——find 回退
/// 全量扫描兜底，与原全局 id 键 map 同覆盖面）
template <typename InstanceT>
InstanceBuckets<InstanceT> makeInstanceBuckets(
        const state::DiscipleStore& ds, const std::vector<InstanceT>& list) {
    InstanceBuckets<InstanceT> view;
    view.instances = &list;
    for (std::size_t i = 0; i < list.size(); ++i) {
        const std::optional<std::string>& owner = list[i].ownerId;
        if (!owner.has_value()) continue;
        const auto nid = settle_util::toIntOrNull(*owner);
        if (!nid.has_value()) continue;
        const auto rowOpt = ds.rowOfNumber(*nid);
        if (!rowOpt.has_value()) continue;
        view.byOwnerRow[*rowOpt].push_back(i);   // 桶内保全局向量序
    }
    return view;
}

}  // namespace instance_bucket
}  // namespace gamecore::system
