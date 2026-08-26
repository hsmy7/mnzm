#include "gamecore/state/dirty_tracker.h"

#include <map>
#include <stdexcept>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/state/json_codec.h"

namespace gamecore::state {

namespace {

using nlohmann::json;

/// 实体集合名清单（与 GameState 顶层字段一一对应；顺序固定保证输出稳定）
constexpr const char* kEntityCollections[] = {
    "disciples",
    "equipmentStacks",
    "equipmentInstances",
    "manualStacks",
    "manualInstances",
    "pills",
    "materials",
    "herbs",
    "seeds",
    "storageBags",
};

/// 把状态转为 JSON 树（复用快照编解码，字段名与 kotlinx 一致）
json stateToJson(const GameState& s) {
    json j = s;
    return j;
}

/// 实体数组 → id→下标有序映射（重复 id 以末次出现为准）
std::map<std::string, std::size_t> indexById(const json& arr) {
    std::map<std::string, std::size_t> idx;
    if (!arr.is_array()) return idx;
    for (std::size_t i = 0; i < arr.size(); ++i) {
        const json& e = arr[i];
        if (e.is_object() && e.contains("id") && e.at("id").is_string()) {
            idx[e.at("id").get<std::string>()] = i;
        }
    }
    return idx;
}

}  // namespace

void DirtyTracker::resetBaseline(const GameState& s) {
    baseline_ = s;
}

std::string DirtyTracker::diffToJson(const GameState& current) {
    ++version_;

    const json base = stateToJson(baseline_);
    const json cur = stateToJson(current);

    json changed = json::object();
    json removed = json::object();

    // ── gameData：顶层字段级 diff（嵌套容器为整体替换语义）──────────
    const json& baseGd = base.contains("gameData") ? base.at("gameData") : json::object();
    if (cur.contains("gameData") && cur.at("gameData").is_object()) {
        for (auto it = cur.at("gameData").begin(); it != cur.at("gameData").end(); ++it) {
            const json& curVal = it.value();
            auto baseIt = baseGd.find(it.key());
            if (baseIt == baseGd.end() || *baseIt != curVal) {
                changed["gameData." + it.key()] = curVal;
            }
        }
        // 基线存在而当前缺失的字段在固定 schema 下不可能出现（codec 全量导出）；
        // 若出现（跨版本降级），按宽松语义忽略——不产出无法应用的删除指令
    }

    // ── 实体集合：按 id upsert/remove ─────────────────────────────
    for (const char* name : kEntityCollections) {
        const json baseArr = base.contains(name) ? base.at(name) : json::array();
        if (!cur.contains(name) || !cur.at(name).is_array()) continue;
        const json& curArr = cur.at(name);

        const auto baseIdx = indexById(baseArr);
        std::map<std::string, bool> curIds;  // 有序集合（确定性迭代）
        json upserts = json::array();

        for (const json& e : curArr) {
            if (!e.is_object() || !e.contains("id") || !e.at("id").is_string()) continue;
            const std::string id = e.at("id").get<std::string>();
            curIds[id] = true;
            auto bIt = baseIdx.find(id);
            if (bIt == baseIdx.end() || baseArr.at(bIt->second) != e) {
                upserts.push_back(e);
            }
        }

        json removedIds = json::array();
        for (const auto& [id, idx] : baseIdx) {
            static_cast<void>(idx);
            if (curIds.find(id) == curIds.end()) {
                removedIds.push_back(id);
            }
        }

        if (!upserts.empty()) changed[name] = std::move(upserts);
        if (!removedIds.empty()) removed[name] = std::move(removedIds);
    }

    json out;
    out["version"] = version_;
    out["changed"] = std::move(changed);
    out["removed"] = std::move(removed);

    // 面向 kotlinx 解码器的浮点规范化（与 dumpStateJson 同一规则）
    normalizeIntegralFloats(out);

    baseline_ = current;  // 导出即消费：基线推进到当前状态
    return out.dump();
}

}  // namespace gamecore::state
