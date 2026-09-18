#include "gamecore/state/dirty_tracker.h"

#include <map>
#include <stdexcept>
#include <utility>
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

/// 非 disciples 集合名清单（列级导出共享段；顺序 = kEntityCollections 去
/// disciples，保证输出遍历序稳定）
const std::vector<const char*> kNonDiscipleCollections = {
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

void diffTreeSegments(const json& base, const json& cur,
                      const std::vector<const char*>& names,
                      json& changed, json& removed) {
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
    for (const char* name : names) {
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
}

json stateWithoutDisciplesToJson(const GameState& s) {
    json j = json::object();
    j["gameData"] = s.gameData;
    j["equipmentStacks"] = s.equipmentStacks;
    j["equipmentInstances"] = s.equipmentInstances;
    j["manualStacks"] = s.manualStacks;
    j["manualInstances"] = s.manualInstances;
    j["pills"] = s.pills;
    j["materials"] = s.materials;
    j["herbs"] = s.herbs;
    j["seeds"] = s.seeds;
    j["storageBags"] = s.storageBags;
    return j;
}

void DirtyTracker::resetBaseline(const GameState& s) {
    baselineJson_ = stateToJson(s);
}

void DirtyTracker::syncBaselineToCurrent(const GameState& current) {
    baselineJson_ = stateToJson(current);
}

std::string DirtyTracker::diffToJson(const GameState& current) {
    return diffToTree(current).dump();
}

nlohmann::json DirtyTracker::diffToTree(const GameState& current) {
    ++version_;

    // WS-1.3：仅当前状态一次全量序列化；基线用缓存树（resetBaseline/
    // syncBaselineToCurrent/上次 diff 消费时已重建），比较后移入缓存。
    const json& base = baselineJson_;
    json cur = stateToJson(current);

    json changed = json::object();
    json removed = json::object();

    // gameData 字段级 + 全部实体集合按 id diff（与列级导出共享同一比对段）
    diffTreeSegments(base, cur,
                     std::vector<const char*>(std::begin(kEntityCollections),
                                              std::end(kEntityCollections)),
                     changed, removed);

    json out;
    out["version"] = version_;
    out["changed"] = std::move(changed);
    out["removed"] = std::move(removed);

    // 面向 kotlinx 解码器的浮点规范化（与 dumpStateJson 同一规则）——
    // protobuf 信封（R2.2 encodeGameView）消费同一棵规范化后的树，
    // 保证双传输格式逐值等价
    normalizeIntegralFloats(out);

    baselineJson_ = std::move(cur);  // 导出即消费：基线推进到当前状态
    return out;
}

}  // namespace gamecore::state
